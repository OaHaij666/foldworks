package com.pockethomestead.network;

import com.pockethomestead.PocketHomestead;
import com.pockethomestead.space.SpaceChunkLoadingManager;
import com.pockethomestead.space.SpaceData;
import com.pockethomestead.space.SpaceManager;
import com.pockethomestead.space.SpacePermission;
import io.netty.buffer.ByteBuf;
import net.minecraft.ChatFormatting;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

public record UpdateSpaceChunkLoadingPayload(UUID spaceId, boolean enabled) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<UpdateSpaceChunkLoadingPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(PocketHomestead.MODID, "update_space_chunk_loading"));

    public static final StreamCodec<ByteBuf, UpdateSpaceChunkLoadingPayload> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC,
            UpdateSpaceChunkLoadingPayload::spaceId,
            ByteBufCodecs.BOOL,
            UpdateSpaceChunkLoadingPayload::enabled,
            UpdateSpaceChunkLoadingPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleOnServer(UpdateSpaceChunkLoadingPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            SpaceData space = SpaceManager.getInstance().getSpace(payload.spaceId());
            if (space == null || !space.can(player.getUUID(), SpacePermission.AccessLevel.MANAGE)) return;
            boolean ok = SpaceManager.getInstance().setChunkLoadingEnabled(player.server, payload.spaceId(), payload.enabled());
            if (payload.enabled() && !ok) {
                long maxArea = Math.max(1, com.pockethomestead.config.ModConfig.SPACE_CHUNK_LOADING_MAX_AREA.get());
                long area = SpaceChunkLoadingManager.area(space);
                player.sendSystemMessage(Component.literal("该空间不能开启常加载：无限空间或面积超过 " + maxArea + " 格（当前 " + area + " 格）")
                        .withStyle(ChatFormatting.RED));
            }
            SpaceListPayload.sendToAll(player.server);
        });
    }
}
