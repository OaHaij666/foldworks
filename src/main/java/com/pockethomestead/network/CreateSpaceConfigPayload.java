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

public record CreateSpaceConfigPayload(ResourceLocation sourceDimension) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<CreateSpaceConfigPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(PocketHomestead.MODID, "create_space_config"));

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
            try {
                if (!(context.player() instanceof ServerPlayer player)) return;

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
            } catch (Exception e) {
                PocketHomestead.LOGGER.error("创建空间失败", e);
            }
        });
    }
}
