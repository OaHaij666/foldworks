package com.foldworks.network;

import com.foldworks.production.ProductionStatsStorage;
import com.foldworks.space.SpaceData;
import com.foldworks.space.SpaceManager;
import com.foldworks.transfer.GraphKey;
import com.foldworks.transfer.TransferGraphAccess;
import com.foldworks.transfer.TransferTeam;
import com.foldworks.transfer.TransferTeamStorage;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

public record RequestProductionStatsPacket(String scopeKind, String scopeId) implements CustomPacketPayload {
    public static final Type<RequestProductionStatsPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("foldworks", "request_production_stats"));

    public RequestProductionStatsPacket() {
        this(ProductionStatsStorage.PRIVATE_SCOPE, "");
    }

    public static final StreamCodec<ByteBuf, RequestProductionStatsPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public RequestProductionStatsPacket decode(ByteBuf buf) {
            return new RequestProductionStatsPacket(ByteBufCodecs.STRING_UTF8.decode(buf), ByteBufCodecs.STRING_UTF8.decode(buf));
        }

        @Override
        public void encode(ByteBuf buf, RequestProductionStatsPacket packet) {
            ByteBufCodecs.STRING_UTF8.encode(buf, packet.scopeKind);
            ByteBufCodecs.STRING_UTF8.encode(buf, packet.scopeId);
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(RequestProductionStatsPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) sendTo(player, packet.scopeKind(), packet.scopeId());
        });
    }

    public static void sendTo(ServerPlayer player) {
        sendTo(player, ProductionStatsStorage.PRIVATE_SCOPE, "");
    }

    public static void sendTo(ServerPlayer player, String requestedKind, String requestedId) {
        ProductionStatsStorage storage = ProductionStatsStorage.get(player.server);
        String scopeKey = UpdateProductionStatsPacket.resolveScopeKey(requestedKind, requestedId, player, false);
        if (scopeKey.isBlank()) {
            requestedKind = ProductionStatsStorage.PRIVATE_SCOPE;
            requestedId = player.getUUID().toString();
            scopeKey = ProductionStatsStorage.privateScope(player.getUUID());
        }
        GraphKey key = GraphKey.parse(requestedKind, requestedId, player.getUUID());
        PacketDistributor.sendToPlayer(player, ProductionStatsSyncPacket.from(
                key.kind().name(),
                key.id() == null ? "" : key.id().toString(),
                scopeOptions(player),
                storage.getStats(scopeKey),
                player.server.overworld().getGameTime()
        ));
    }

    private static List<ProductionStatsSyncPacket.ScopeData> scopeOptions(ServerPlayer player) {
        List<ProductionStatsSyncPacket.ScopeData> result = new ArrayList<>();
        result.add(new ProductionStatsSyncPacket.ScopeData(
                ProductionStatsStorage.PRIVATE_SCOPE,
                player.getUUID().toString(),
                "个人",
                true));
        for (TransferTeam team : TransferTeamStorage.get(player.server).teamsVisibleTo(player.getUUID())) {
            if (!team.can(player.getUUID(), com.foldworks.space.SpacePermission.AccessLevel.VIEW)) continue;
            GraphKey key = GraphKey.protectedGraph(team.id());
            result.add(new ProductionStatsSyncPacket.ScopeData(
                    key.kind().name(),
                    key.id().toString(),
                    "团队: " + team.name(),
                    TransferGraphAccess.canWrite(player, key)));
        }
        for (SpaceData space : SpaceManager.getInstance().getAccessibleSpaces(player.getUUID())) {
            GraphKey key = GraphKey.spaceGraph(space.getSpaceId());
            result.add(new ProductionStatsSyncPacket.ScopeData(
                    key.kind().name(),
                    key.id().toString(),
                    "社团: " + space.getName(),
                    TransferGraphAccess.canWrite(player, key)));
        }
        return result;
    }
}
