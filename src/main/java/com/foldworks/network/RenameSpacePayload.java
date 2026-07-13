package com.foldworks.network;

import com.foldworks.Foldworks;
import com.foldworks.space.SpaceData;
import com.foldworks.space.SpaceManager;
import com.foldworks.space.SpacePermission;
import com.foldworks.space.SpaceStorage;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

public record RenameSpacePayload(UUID spaceId, String name) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<RenameSpacePayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Foldworks.MODID, "rename_space"));

    public static final StreamCodec<ByteBuf, RenameSpacePayload> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC,
            RenameSpacePayload::spaceId,
            ByteBufCodecs.STRING_UTF8,
            RenameSpacePayload::name,
            RenameSpacePayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleOnServer(RenameSpacePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            SpaceData space = SpaceManager.getInstance().getSpace(payload.spaceId());
            if (space == null || !space.can(player.getUUID(), SpacePermission.AccessLevel.MANAGE)) return;
            String name = payload.name() == null ? "" : payload.name().trim();
            if (name.isEmpty()) return;
            if (name.length() > 24) name = name.substring(0, 24);
            space.setName(name);
            SpaceStorage.markDirty();
            SpaceListPayload.sendToAll(player.server);
        });
    }
}
