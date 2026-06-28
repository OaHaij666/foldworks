package com.pockethomestead.network;

import com.pockethomestead.PocketHomestead;
import com.pockethomestead.client.ClientSpaceArchiveTransfer;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record SpaceArchiveServerPacket(String action, String sessionId, String fileName,
                                       int index, int totalChunks, long totalBytes,
                                       byte[] data, String message) implements CustomPacketPayload {
    public static final Type<SpaceArchiveServerPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(PocketHomestead.MODID, "space_archive_server"));

    public static final StreamCodec<ByteBuf, SpaceArchiveServerPacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> {
                ByteBufCodecs.STRING_UTF8.encode(buf, packet.action());
                ByteBufCodecs.STRING_UTF8.encode(buf, packet.sessionId());
                ByteBufCodecs.STRING_UTF8.encode(buf, packet.fileName() == null ? "" : packet.fileName());
                ByteBufCodecs.VAR_INT.encode(buf, packet.index());
                ByteBufCodecs.VAR_INT.encode(buf, packet.totalChunks());
                ByteBufCodecs.VAR_LONG.encode(buf, packet.totalBytes());
                ByteBufCodecs.BYTE_ARRAY.encode(buf, packet.data() == null ? new byte[0] : packet.data());
                ByteBufCodecs.STRING_UTF8.encode(buf, packet.message() == null ? "" : packet.message());
            },
            buf -> new SpaceArchiveServerPacket(
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_LONG.decode(buf),
                    ByteBufCodecs.BYTE_ARRAY.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf)
            )
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static SpaceArchiveServerPacket simple(String action, String sessionId, String fileName, String message) {
        return new SpaceArchiveServerPacket(action, sessionId, fileName, 0, 0, 0, new byte[0], message);
    }

    public static SpaceArchiveServerPacket beginDownload(String sessionId, String fileName, long totalBytes, int totalChunks) {
        return new SpaceArchiveServerPacket("DOWNLOAD_BEGIN", sessionId, fileName, 0, totalChunks, totalBytes, new byte[0], "开始下载");
    }

    public static SpaceArchiveServerPacket downloadChunk(String sessionId, String fileName, int index, int totalChunks, long totalBytes, byte[] data) {
        return new SpaceArchiveServerPacket("DOWNLOAD_CHUNK", sessionId, fileName, index, totalChunks, totalBytes, data, "");
    }

    public static void handleOnClient(SpaceArchiveServerPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> ClientSpaceArchiveTransfer.handleServerPacket(packet));
    }
}
