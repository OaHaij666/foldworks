package com.pockethomestead.network;

import com.pockethomestead.production.ProductionStatsStorage;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record RequestProductionStatsPacket() implements CustomPacketPayload {
    public static final Type<RequestProductionStatsPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("pockethomestead", "request_production_stats"));

    public static final StreamCodec<ByteBuf, RequestProductionStatsPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public RequestProductionStatsPacket decode(ByteBuf buf) {
            return new RequestProductionStatsPacket();
        }

        @Override
        public void encode(ByteBuf buf, RequestProductionStatsPacket packet) {
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(RequestProductionStatsPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) sendTo(player);
        });
    }

    public static void sendTo(ServerPlayer player) {
        ProductionStatsStorage storage = ProductionStatsStorage.get(player.server);
        PacketDistributor.sendToPlayer(player, ProductionStatsSyncPacket.from(
                storage.statsFor(player.getUUID()),
                player.server.overworld().getGameTime()
        ));
    }
}
