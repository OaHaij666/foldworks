package com.pockethomestead.network;

import com.pockethomestead.PocketHomestead;
import com.pockethomestead.space.SpaceData;
import com.pockethomestead.space.SpaceManager;
import com.pockethomestead.space.SpacePermission;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import io.netty.handler.codec.DecoderException;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/** C→S：设置某空间的访问模式（仅所有者）。 */
public record UpdatePermissionPayload(UUID spaceId, SpacePermission.AccessMode mode) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<UpdatePermissionPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(PocketHomestead.MODID, "update_permission"));

    private static final SpacePermission.AccessMode[] MODE_VALUES = SpacePermission.AccessMode.values();

    public static final StreamCodec<ByteBuf, UpdatePermissionPayload> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC,
            UpdatePermissionPayload::spaceId,
            ByteBufCodecs.VAR_INT.map(
                    i -> {
                        if (i < 0 || i >= MODE_VALUES.length)
                            throw new DecoderException("Invalid access mode ordinal: " + i);
                        return MODE_VALUES[i];
                    },
                    SpacePermission.AccessMode::ordinal
            ),
            UpdatePermissionPayload::mode,
            UpdatePermissionPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handleOnServer(UpdatePermissionPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            SpaceData space = SpaceManager.getInstance().getSpace(payload.spaceId());
            if (space == null || !space.isOwner(player.getUUID())) return;
            space.getPermission().setMode(payload.mode());
            com.pockethomestead.space.SpaceStorage.markDirty();
            SpaceListPayload.sendTo(player);
        });
    }
}
