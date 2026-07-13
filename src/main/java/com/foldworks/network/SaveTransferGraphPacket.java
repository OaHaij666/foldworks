package com.foldworks.network;

import com.foldworks.transfer.GraphKey;
import com.foldworks.transfer.TransferEdge;
import com.foldworks.transfer.TransferGraph;
import com.foldworks.transfer.TransferGraphAccess;
import com.foldworks.transfer.TransferGraphPage;
import com.foldworks.transfer.TransferGraphStorage;
import com.foldworks.transfer.TransferGraphValidator;
import com.foldworks.transfer.TransferNode;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record SaveTransferGraphPacket(
        String graphKind,
        String graphId,
        List<TransferGraphSyncPacket.PageData> pages,
        List<TransferGraphSyncPacket.NodeData> nodes,
        List<TransferGraphSyncPacket.EdgeData> edges
) implements CustomPacketPayload {
    private static final int MAX_PAGES = 64;
    private static final int MAX_NODES = 512;
    private static final int MAX_EDGES = 1024;
    public static final Type<SaveTransferGraphPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("foldworks", "save_transfer_graph"));

    public static final StreamCodec<ByteBuf, SaveTransferGraphPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public SaveTransferGraphPacket decode(ByteBuf buf) {
            String graphKind = ByteBufCodecs.STRING_UTF8.decode(buf);
            String graphId = ByteBufCodecs.STRING_UTF8.decode(buf);
            List<TransferGraphSyncPacket.PageData> pages = new ArrayList<>();
            int pageCount = NetworkDecodeLimits.checkedCount(ByteBufCodecs.VAR_INT.decode(buf), MAX_PAGES, "pages");
            for (int i = 0; i < pageCount; i++) {
                pages.add(new TransferGraphSyncPacket.PageData(
                        ByteBufCodecs.STRING_UTF8.decode(buf),
                        ByteBufCodecs.STRING_UTF8.decode(buf),
                        buf.readBoolean(),
                        ByteBufCodecs.VAR_INT.decode(buf)
                ));
            }

            List<TransferGraphSyncPacket.NodeData> nodes = new ArrayList<>();
            int nodeCount = NetworkDecodeLimits.checkedCount(ByteBufCodecs.VAR_INT.decode(buf), MAX_NODES, "nodes");
            for (int i = 0; i < nodeCount; i++) nodes.add(TransferGraphSyncPacket.decodeNode(buf));

            List<TransferGraphSyncPacket.EdgeData> edges = new ArrayList<>();
            int edgeCount = NetworkDecodeLimits.checkedCount(ByteBufCodecs.VAR_INT.decode(buf), MAX_EDGES, "edges");
            for (int i = 0; i < edgeCount; i++) edges.add(TransferGraphSyncPacket.decodeEdge(buf));
            return new SaveTransferGraphPacket(graphKind, graphId, pages, nodes, edges);
        }

        @Override
        public void encode(ByteBuf buf, SaveTransferGraphPacket pkt) {
            ByteBufCodecs.STRING_UTF8.encode(buf, pkt.graphKind);
            ByteBufCodecs.STRING_UTF8.encode(buf, pkt.graphId);
            ByteBufCodecs.VAR_INT.encode(buf, pkt.pages.size());
            for (TransferGraphSyncPacket.PageData page : pkt.pages) {
                ByteBufCodecs.STRING_UTF8.encode(buf, page.id());
                ByteBufCodecs.STRING_UTF8.encode(buf, page.name());
                buf.writeBoolean(page.enabled());
                ByteBufCodecs.VAR_INT.encode(buf, page.order());
            }

            ByteBufCodecs.VAR_INT.encode(buf, pkt.nodes.size());
            for (TransferGraphSyncPacket.NodeData node : pkt.nodes) TransferGraphSyncPacket.encodeNode(buf, node);

            ByteBufCodecs.VAR_INT.encode(buf, pkt.edges.size());
            for (TransferGraphSyncPacket.EdgeData edge : pkt.edges) TransferGraphSyncPacket.encodeEdge(buf, edge);
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(SaveTransferGraphPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            GraphKey key = GraphKey.parse(packet.graphKind, packet.graphId, player.getUUID());
            if (!TransferGraphAccess.canWrite(player, key)) {
                PacketDistributor.sendToPlayer(player, TransferGraphValidationPacket.from(List.of(
                        new TransferGraphValidator.Issue(TransferGraphValidator.Severity.ERROR, "", "", "没有权限编辑当前连线图"))));
                RequestTransferGraphPacket.sendGraphTo(player, key);
                return;
            }
            TransferGraphStorage storage = TransferGraphStorage.get(player.server);
            TransferGraph previous = storage.graphFor(key);
            TransferGraph graph = toGraph(key, packet, previous, player);
            List<TransferGraphValidator.Issue> issues = new ArrayList<>(TransferGraphValidator.validate(graph));
            issues.addAll(TransferGraphValidator.validateRuntime(graph, player.server, key));
            if (TransferGraphValidator.hasErrors(issues)) {
                PacketDistributor.sendToPlayer(player, TransferGraphValidationPacket.from(issues));
                return;
            }
            storage.replaceGraph(key, graph);
            PacketDistributor.sendToPlayer(player, TransferGraphValidationPacket.from(issues));
            RequestTransferGraphPacket.sendGraphTo(player, key);
        });
    }

    private static TransferGraph toGraph(GraphKey key, SaveTransferGraphPacket packet, TransferGraph previousGraph, ServerPlayer player) {
        TransferGraph graph = new TransferGraph(key);
        graph.clearAll();
        for (TransferGraphSyncPacket.PageData page : packet.pages) {
            graph.putPage(new TransferGraphPage(page.id(), page.name(), page.enabled(), page.order()));
        }
        if (packet.pages.isEmpty()) graph.ensureDefaultPage();
        for (TransferGraphSyncPacket.NodeData node : packet.nodes) {
            if ("SUPPLY".equals(node.type()) || "PICKUP".equals(node.type())) continue;
            TransferNode transferNode = toNode(node, previousGraph == null ? null : previousGraph.getNode(node.id()), player);
            if (transferNode == null) continue;
            graph.putNode(transferNode);
        }
        for (TransferGraphSyncPacket.EdgeData edge : packet.edges) {
            if (graph.getNode(edge.fromNodeId()) == null || graph.getNode(edge.toNodeId()) == null) continue;
            TransferEdge transferEdge = new TransferEdge(edge.id(), edge.pageId(), edge.fromNodeId(), edge.toNodeId(), edge.fromPortKey(), edge.toPortKey(), false, 1, 64, edge.enabled());
            for (TransferGraphSyncPacket.EdgeItemRateData row : edge.itemRates()) {
                if (row.configured()) transferEdge.setItemRate(row.itemId(), row.rateLimitEnabled(), row.rateLimitSeconds(), row.rateLimitItems());
            }
            graph.putEdge(transferEdge);
        }
        return graph;
    }

    private static TransferNode toNode(TransferGraphSyncPacket.NodeData node, TransferNode previous, ServerPlayer player) {
        TransferNode.NodeType type;
        try { type = TransferNode.NodeType.valueOf(node.type()); } catch (Exception e) { type = TransferNode.NodeType.CHEST; }
        UUID targetPlayerId = parseUuid(node.targetPlayerId());
        List<TransferNode.ReplenishRule> rules = new ArrayList<>();
        for (TransferGraphSyncPacket.ReplenishRuleData rule : node.replenishRules()) {
            rules.add(new TransferNode.ReplenishRule(normalizeItemId(rule.itemId()), rule.targetCount()));
        }
        if (type == TransferNode.NodeType.PLAYER_INVENTORY) {
            if (targetPlayerId == null) targetPlayerId = player.getUUID();
            if (!player.getUUID().equals(targetPlayerId)) {
                if (previous == null || previous.getNodeType() != TransferNode.NodeType.PLAYER_INVENTORY
                        || !targetPlayerId.equals(previous.getTargetPlayerId())) return null;
                rules = new ArrayList<>(previous.getReplenishRules());
            }
        }
        TransferNode transferNode = new TransferNode(node.id(), node.pageId(), type, node.chestId(), node.dimensionKey(), BlockPos.of(node.pos()),
                node.x(), node.y(), node.expanded(), node.enabled(), node.filterItemIds(), node.receiveFilterIds(), targetPlayerId, rules,
                node.label(), node.linkedNodeId(), node.gateMin(), node.gateMax(), node.gateCheckSource());
        if (previous != null) transferNode.copyFlowStatsFrom(previous);
        return transferNode;
    }

    private static UUID parseUuid(String value) {
        try {
            return value == null || value.isBlank() ? null : UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String normalizeItemId(String itemId) {
        if (itemId == null) return "";
        return itemId.startsWith(TransferEdge.ITEM_PREFIX) ? itemId.substring(TransferEdge.ITEM_PREFIX.length()) : itemId;
    }
}
