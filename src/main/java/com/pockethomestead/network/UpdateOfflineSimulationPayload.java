package com.pockethomestead.network;

import com.pockethomestead.PocketHomestead;
import com.pockethomestead.space.SpaceData;
import com.pockethomestead.space.SpaceManager;
import com.pockethomestead.space.SpacePermission;
import com.pockethomestead.space.SpaceStorage;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

public record UpdateOfflineSimulationPayload(UUID spaceId, boolean enabled) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<UpdateOfflineSimulationPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(PocketHomestead.MODID, "update_offline_simulation"));

    public static final StreamCodec<ByteBuf, UpdateOfflineSimulationPayload> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC,
            UpdateOfflineSimulationPayload::spaceId,
            ByteBufCodecs.BOOL,
            UpdateOfflineSimulationPayload::enabled,
            UpdateOfflineSimulationPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleOnServer(UpdateOfflineSimulationPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            SpaceData space = SpaceManager.getInstance().getSpace(payload.spaceId());
            if (space == null || !space.can(player.getUUID(), SpacePermission.AccessLevel.MANAGE)) return;
            space.setOfflineSimulationEnabled(payload.enabled());
            SpaceStorage.markDirty();
            SpaceListPayload.sendToAll(player.server);
        });
    }
}
