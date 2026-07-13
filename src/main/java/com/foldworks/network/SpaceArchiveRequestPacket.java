package com.foldworks.network;

import com.foldworks.Foldworks;
import com.foldworks.archive.SpaceArchiveTransferManager;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

public record SpaceArchiveRequestPacket(String action, String sessionId, UUID spaceId,
                                        String fileName, long totalBytes, String sha256,
                                        int archiveWidth, int archiveDepth, boolean archiveInfinite) implements CustomPacketPayload {
    public static final Type<SpaceArchiveRequestPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Foldworks.MODID, "space_archive_request"));

    public static final StreamCodec<ByteBuf, SpaceArchiveRequestPacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> {
                ByteBufCodecs.STRING_UTF8.encode(buf, packet.action());
                ByteBufCodecs.STRING_UTF8.encode(buf, packet.sessionId());
                UUIDUtil.STREAM_CODEC.encode(buf, packet.spaceId() == null ? new UUID(0L, 0L) : packet.spaceId());
                ByteBufCodecs.STRING_UTF8.encode(buf, packet.fileName() == null ? "" : packet.fileName());
                ByteBufCodecs.VAR_LONG.encode(buf, packet.totalBytes());
                ByteBufCodecs.STRING_UTF8.encode(buf, packet.sha256() == null ? "" : packet.sha256());
                ByteBufCodecs.VAR_INT.encode(buf, packet.archiveWidth());
                ByteBufCodecs.VAR_INT.encode(buf, packet.archiveDepth());
                ByteBufCodecs.BOOL.encode(buf, packet.archiveInfinite());
            },
            buf -> new SpaceArchiveRequestPacket(
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    UUIDUtil.STREAM_CODEC.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.VAR_LONG.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.BOOL.decode(buf)
            )
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static SpaceArchiveRequestPacket download(UUID spaceId, String sessionId) {
        return new SpaceArchiveRequestPacket("DOWNLOAD", sessionId, spaceId, "", 0, "", 0, 0, false);
    }

    public static SpaceArchiveRequestPacket uploadBegin(String sessionId, String fileName, long totalBytes, String sha256,
                                                        int archiveWidth, int archiveDepth, boolean archiveInfinite) {
        return new SpaceArchiveRequestPacket("UPLOAD_BEGIN", sessionId, new UUID(0L, 0L), fileName, totalBytes, sha256,
                archiveWidth, archiveDepth, archiveInfinite);
    }

    public static void handleOnServer(SpaceArchiveRequestPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            if ("DOWNLOAD".equals(packet.action())) {
                SpaceArchiveTransferManager.beginDownload(player, packet.spaceId(), packet.sessionId());
            } else if ("UPLOAD_BEGIN".equals(packet.action())) {
                SpaceArchiveTransferManager.beginUpload(player, packet.sessionId(), packet.fileName(), packet.totalBytes(), packet.sha256(),
                        packet.archiveWidth(), packet.archiveDepth(), packet.archiveInfinite());
            }
        });
    }
}
