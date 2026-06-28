package com.pockethomestead.network;

import com.pockethomestead.blockentity.BaseChestBlockEntity;
import com.pockethomestead.blockentity.HomesteadChestAccess;
import com.pockethomestead.registry.ChestRegistryManager;
import com.pockethomestead.space.SpacePermission;
import com.pockethomestead.transfer.GraphKey;
import com.pockethomestead.transfer.TransferGraph;
import com.pockethomestead.transfer.TransferGraphAccess;
import com.pockethomestead.transfer.TransferGraphStorage;
import com.pockethomestead.transfer.TransferGraphValidator;
import com.pockethomestead.transfer.TransferTeam;
import com.pockethomestead.transfer.TransferTeamStorage;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record RequestTransferGraphPacket(String graphKind, String graphId) implements CustomPacketPayload {
    public static final Type<RequestTransferGraphPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("pockethomestead", "request_transfer_graph"));

    public RequestTransferGraphPacket() {
        this("PRIVATE", "");
    }

    public static final StreamCodec<ByteBuf, RequestTransferGraphPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public RequestTransferGraphPacket decode(ByteBuf buf) {
            return new RequestTransferGraphPacket(ByteBufCodecs.STRING_UTF8.decode(buf), ByteBufCodecs.STRING_UTF8.decode(buf));
        }

        @Override
        public void encode(ByteBuf buf, RequestTransferGraphPacket pkt) {
            ByteBufCodecs.STRING_UTF8.encode(buf, pkt.graphKind);
            ByteBufCodecs.STRING_UTF8.encode(buf, pkt.graphId);
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(RequestTransferGraphPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) sendGraphTo(player, packet.requestedKey(player));
        });
    }

    private GraphKey requestedKey(ServerPlayer player) {
        return GraphKey.parse(graphKind, graphId, player.getUUID());
    }

    public static void sendGraphTo(ServerPlayer player) {
        sendGraphTo(player, GraphKey.privateGraph(player.getUUID()));
    }

    public static void sendGraphTo(ServerPlayer player, GraphKey requestedKey) {
        GraphKey key = TransferGraphAccess.canView(player, requestedKey) ? requestedKey : GraphKey.privateGraph(player.getUUID());
        TransferGraph graph = TransferGraphStorage.get(player.server).graphFor(key);
        PacketDistributor.sendToPlayer(player, TransferGraphSyncPacket.from(key, graph, chestsFor(player, key), graphOptions(player), teamsFor(player)));
        List<TransferGraphValidator.Issue> issues = new ArrayList<>(TransferGraphValidator.validate(graph));
        issues.addAll(TransferGraphValidator.validateRuntime(graph, player.server, key));
        PacketDistributor.sendToPlayer(player, TransferGraphValidationPacket.from(issues));
    }

    private static List<TransferGraphSyncPacket.GraphOptionData> graphOptions(ServerPlayer player) {
        List<TransferGraphSyncPacket.GraphOptionData> result = new ArrayList<>();
        GraphKey privateKey = GraphKey.privateGraph(player.getUUID());
        result.add(new TransferGraphSyncPacket.GraphOptionData(privateKey.kind().name(), privateKey.id().toString(), "我的 Private 图", true));
        GraphKey publicKey = GraphKey.publicGraph();
        result.add(new TransferGraphSyncPacket.GraphOptionData(publicKey.kind().name(), publicKey.id().toString(), "Public 图", true));
        for (TransferTeam team : TransferTeamStorage.get(player.server).teamsVisibleTo(player.getUUID())) {
            GraphKey key = GraphKey.protectedGraph(team.id());
            result.add(new TransferGraphSyncPacket.GraphOptionData(key.kind().name(), key.id().toString(),
                    "Team: " + team.name(), TransferGraphAccess.canWrite(player, key)));
        }
        return result;
    }

    private static List<TransferGraphSyncPacket.TeamData> teamsFor(ServerPlayer player) {
        List<TransferGraphSyncPacket.TeamData> result = new ArrayList<>();
        for (TransferTeam team : TransferTeamStorage.get(player.server).teamsVisibleTo(player.getUUID())) {
            SpacePermission.AccessLevel level = team.levelFor(player.getUUID());
            result.add(new TransferGraphSyncPacket.TeamData(team.id().toString(), team.name(),
                    team.owner() == null ? "" : team.owner().toString(), level.name()));
        }
        return result;
    }

    private static List<TransferGraphSyncPacket.ChestData> chestsFor(ServerPlayer player, GraphKey key) {
        List<TransferGraphSyncPacket.ChestData> result = new ArrayList<>();
        for (ChestRegistryManager.RegisteredChest registered : ChestRegistryManager.getInstance().getAllRegisteredChests()) {
            BaseChestBlockEntity be = loadedChest(player, registered.location().dimensionKey, registered.location().pos);
            if (be == null || !be.hasNetworkUpgrade() || !TransferGraphAccess.chestMatchesGraph(be, key)) continue;
            GraphKey chestKey = be.getGraphKey();
            result.add(new TransferGraphSyncPacket.ChestData(
                    registered.chestId(),
                    registered.location().dimensionKey,
                    registered.location().pos.asLong(),
                    be.getNetworkBandwidthCapacity(),
                    be.getStressBandwidthUsed(),
                    be.getRemainingTransferBandwidth(),
                    chestKey == null ? "" : chestKey.kind().name(),
                    chestKey == null || chestKey.id() == null ? "" : chestKey.id().toString()
            ));
        }
        result.sort((a, b) -> {
            int c = a.chestId().compareToIgnoreCase(b.chestId());
            if (c != 0) return c;
            c = a.dimensionKey().compareToIgnoreCase(b.dimensionKey());
            if (c != 0) return c;
            return Long.compare(a.pos(), b.pos());
        });
        return result;
    }

    private static BaseChestBlockEntity loadedChest(ServerPlayer player, String dimensionKey, net.minecraft.core.BlockPos pos) {
        ResourceLocation dimLoc = ResourceLocation.tryParse(dimensionKey);
        if (dimLoc == null) return null;
        ServerLevel level = player.server.getLevel(net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION,
                dimLoc
        ));
        return level == null ? null : HomesteadChestAccess.resolve(level.getBlockEntity(pos));
    }
}
