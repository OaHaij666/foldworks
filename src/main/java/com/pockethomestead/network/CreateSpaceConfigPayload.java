package com.pockethomestead.network;

import com.pockethomestead.PocketHomestead;
import com.pockethomestead.config.ModConfig;
import com.pockethomestead.dimension.PocketDimensionManager;
import com.pockethomestead.space.SpaceData;
import com.pockethomestead.space.SpaceManager;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

public record CreateSpaceConfigPayload(ResourceLocation sourceDimension) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<CreateSpaceConfigPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(PocketHomestead.MODID, "create_space_config"));

    private static final long CREATE_COOLDILLIS = 5000L;
    private static final java.util.Map<UUID, Long> lastCreateMillis = new java.util.concurrent.ConcurrentHashMap<>();

    public static final StreamCodec<ByteBuf, CreateSpaceConfigPayload> STREAM_CODEC = StreamCodec.composite(
            ResourceLocation.STREAM_CODEC,
            CreateSpaceConfigPayload::sourceDimension,
            CreateSpaceConfigPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleOnServer(CreateSpaceConfigPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            try {
                UUID playerId = player.getUUID();
                long now = System.currentTimeMillis();
                Long last = lastCreateMillis.get(playerId);
                if (last != null && now - last < CREATE_COOLDILLIS) {
                    PocketHomestead.LOGGER.warn("玩家 {} 创建空间过快，已限速", playerId);
                    return;
                }
                lastCreateMillis.put(playerId, now);

                CreateSpacePayload pending = CreateSpacePayload.takePending(player.getUUID());
                if (pending == null) {
                    PocketHomestead.LOGGER.warn("没有待处理的空间创建请求 for player {}", player.getUUID());
                    return;
                }

                // 服务端再次夹紧尺寸到配置范围 [minSpaceSize, maxSpaceSize]
                int min = ModConfig.MIN_SPACE_SIZE.get();
                int max = ModConfig.MAX_SPACE_SIZE.get();
                int w = Math.max(min, Math.min(max, pending.width()));
                int d = Math.max(min, Math.min(max, pending.depth()));

                // 经验收费：创造模式免费；有限世界按面积，无限世界固定费用
                if (!player.isCreative()) {
                    int expCost = pending.infinite()
                            ? ModConfig.EXP_COST_INFINITE.get()
                            : w * d * ModConfig.EXP_COST_PER_BLOCK.get();
                    if (expCost > 0) {
                        int totalExp = Math.toIntExact(Math.round(player.experienceLevel * 100.0 + player.experienceProgress * player.getXpNeededForNextLevel()));
                        if (totalExp < expCost) {
                            player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                                    "pockethomestead.space.create.insufficient_exp", expCost), true);
                            return;
                        }
                        player.giveExperiencePoints(-expCost);
                    }
                }

                SpaceData space = SpaceManager.getInstance().createSpace(
                        player.server, player.getUUID(),
                        w, 64, d,
                        pending.terrain(), pending.biome(),
                        payload.sourceDimension(),
                        pending.mobs(), pending.structs(),
                        pending.infinite(), pending.amplitude());

                PocketDimensionManager.getInstance().queueTeleportToSpace(player, space);

                // 推送最新列表
                SpaceListPayload.sendToAll(player.server);
            } catch (com.pockethomestead.space.SpaceLimitExceededException e) {
                player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                        "pockethomestead.space.create.limit_exceeded", e.max()), true);
            } catch (Exception e) {
                PocketHomestead.LOGGER.error("创建空间失败", e);
            }
        });
    }
}
