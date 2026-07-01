package com.pockethomestead.network;

import com.pockethomestead.PocketHomestead;
import com.pockethomestead.archive.SpaceArchiveTransferManager;
import com.pockethomestead.config.ModConfig;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record SpaceArchiveClientChunkPacket(String sessionId, int index, int totalChunks, byte[] data) implements CustomPacketPayload {
    public static final Type<SpaceArchiveClientChunkPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(PocketHomestead.MODID, "space_archive_client_chunk"));

    private static int maxChunkBytes() {
        return Math.max(4096, ModConfig.SPACE_ARCHIVE_CHUNK_BYTES.get());
    }

    public static final StreamCodec<ByteBuf, SpaceArchiveClientChunkPacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> {
                ByteBufCodecs.STRING_UTF8.encode(buf, packet.sessionId());
                ByteBufCodecs.VAR_INT.encode(buf, packet.index());
                ByteBufCodecs.VAR_INT.encode(buf, packet.totalChunks());
                ByteBufCodecs.BYTE_ARRAY.encode(buf, packet.data());
            },
            buf -> new SpaceArchiveClientChunkPacket(
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.byteArray(maxChunkBytes()).decode(buf)
            )
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleOnServer(SpaceArchiveClientChunkPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                SpaceArchiveTransferManager.receiveUploadChunk(player, packet);
            }
        });
    }
}
