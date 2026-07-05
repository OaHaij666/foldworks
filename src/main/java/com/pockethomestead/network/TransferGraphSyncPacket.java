package com.pockethomestead.network;

import com.pockethomestead.client.ClientTransferGraphCache;
import com.pockethomestead.transfer.GraphKey;
import com.pockethomestead.transfer.TransferEdge;
import com.pockethomestead.transfer.TransferGraph;
import com.pockethomestead.transfer.TransferGraphPage;
import com.pockethomestead.transfer.TransferNode;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

public record TransferGraphSyncPacket(
        String graphKind,
        String graphId,
        List<GraphOptionData> graphOptions,
        List<TeamData> teams,
        List<PageData> pages,
        List<NodeData> nodes,
        List<EdgeData> edges,
        List<ChestData> chests
) implements CustomPacketPayload {
    public static final Type<TransferGraphSyncPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("pockethomestead", "transfer_graph_sync"));

    public record GraphOptionData(String kind, String id, String label, boolean writable) {}
    public record TeamMemberData(String id, String name, String level, boolean pending) {}
    public record TeamData(String id, String name, String ownerId, String selfLevel, boolean invited, List<TeamMemberData> members) {}
    public record PageData(String id, String name, boolean enabled, int order) {}
    public record NodeFlowData(String itemId, int inputRatePerMinute, int outputRatePerMinute, long inputTotal, long outputTotal) {}
    public record ReplenishRuleData(String itemId, int targetCount) {}
    public record NodeData(String id, String pageId, String type, String chestId, String dimensionKey, long pos, int x, int y,
                           boolean expanded, boolean enabled, List<String> filterItemIds, List<String> receiveFilterIds,
                           String targetPlayerId, List<ReplenishRuleData> replenishRules, List<NodeFlowData> flowStats,
                           String label, String linkedNodeId, int gateMin, int gateMax, boolean gateCheckSource) {}
    public record EdgeItemRateData(String itemId, boolean rateLimitEnabled, int rateLimitSeconds, int rateLimitItems,
                                   String health, int actualRatePerMinute, boolean configured) {}
    public record EdgeData(String id, String pageId, String fromNodeId, String toNodeId, String fromPortKey, String toPortKey,
                           boolean enabled,
                           String health, int actualRatePerMinute, List<EdgeItemRateData> itemRates) {}
    public record ChestData(String chestId, String dimensionKey, long pos,
                            int networkBandwidth, int stressBandwidthUsed, int remainingTransferBandwidth,
                            String graphKind, String graphId, String status, long lastSimulatedGameTime,
                            String statusMessage, boolean offlineSnapshotEnabled) {}

    private static final int MAX_GRAPH_OPTIONS = 256;
    private static final int MAX_TEAMS = 64;
    private static final int MAX_PAGES = 64;
    private static final int MAX_NODES = 512;
    private static final int MAX_EDGES = 1024;
    private static final int MAX_CHESTS = 512;
    private static final int MAX_STRINGS = 1024;
    private static final int MAX_TEAM_MEMBERS = 256;
    private static final int MAX_REPLENISH_RULES = 256;
    private static final int MAX_NODE_FLOW_STATS = 256;
    private static final int MAX_EDGE_ITEM_RATES = 256;

    private static int checkCount(int count, int max) {
        if (count < 0 || count > max) throw new io.netty.handler.codec.DecoderException("List count out of range: " + count + " > " + max);
        return count;
    }

    public static final StreamCodec<ByteBuf, TransferGraphSyncPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public TransferGraphSyncPacket decode(ByteBuf buf) {
            String graphKind = ByteBufCodecs.STRING_UTF8.decode(buf);
            String graphId = ByteBufCodecs.STRING_UTF8.decode(buf);
            List<GraphOptionData> graphOptions = decodeGraphOptions(buf);
            List<TeamData> teams = decodeTeams(buf);

            List<PageData> pages = new ArrayList<>();
            int pageCount = checkCount(ByteBufCodecs.VAR_INT.decode(buf), MAX_PAGES);
            for (int i = 0; i < pageCount; i++) {
                pages.add(new PageData(ByteBufCodecs.STRING_UTF8.decode(buf), ByteBufCodecs.STRING_UTF8.decode(buf), buf.readBoolean(), ByteBufCodecs.VAR_INT.decode(buf)));
            }

            List<NodeData> nodes = new ArrayList<>();
            int nodeCount = checkCount(ByteBufCodecs.VAR_INT.decode(buf), MAX_NODES);
            for (int i = 0; i < nodeCount; i++) nodes.add(decodeNode(buf));

            List<EdgeData> edges = new ArrayList<>();
            int edgeCount = checkCount(ByteBufCodecs.VAR_INT.decode(buf), MAX_EDGES);
            for (int i = 0; i < edgeCount; i++) edges.add(decodeEdge(buf));

            return new TransferGraphSyncPacket(graphKind, graphId, graphOptions, teams, pages, nodes, edges, decodeChests(buf));
        }

        @Override
        public void encode(ByteBuf buf, TransferGraphSyncPacket pkt) {
            ByteBufCodecs.STRING_UTF8.encode(buf, pkt.graphKind);
            ByteBufCodecs.STRING_UTF8.encode(buf, pkt.graphId);
            encodeGraphOptions(buf, pkt.graphOptions);
            encodeTeams(buf, pkt.teams);

            ByteBufCodecs.VAR_INT.encode(buf, pkt.pages.size());
            for (PageData page : pkt.pages) {
                ByteBufCodecs.STRING_UTF8.encode(buf, page.id);
                ByteBufCodecs.STRING_UTF8.encode(buf, page.name);
                buf.writeBoolean(page.enabled);
                ByteBufCodecs.VAR_INT.encode(buf, page.order);
            }

            ByteBufCodecs.VAR_INT.encode(buf, pkt.nodes.size());
            for (NodeData node : pkt.nodes) encodeNode(buf, node);

            ByteBufCodecs.VAR_INT.encode(buf, pkt.edges.size());
            for (EdgeData edge : pkt.edges) encodeEdge(buf, edge);

            encodeChests(buf, pkt.chests);
        }
    };

    public static NodeData decodeNode(ByteBuf buf) {
        return new NodeData(
                ByteBufCodecs.STRING_UTF8.decode(buf),
                ByteBufCodecs.STRING_UTF8.decode(buf),
                ByteBufCodecs.STRING_UTF8.decode(buf),
                ByteBufCodecs.STRING_UTF8.decode(buf),
                ByteBufCodecs.STRING_UTF8.decode(buf),
                buf.readLong(),
                ByteBufCodecs.VAR_INT.decode(buf),
                ByteBufCodecs.VAR_INT.decode(buf),
                buf.readBoolean(),
                buf.readBoolean(),
                decodeStrings(buf),
                decodeStrings(buf),
                ByteBufCodecs.STRING_UTF8.decode(buf),
                decodeReplenishRules(buf),
                decodeNodeFlowStats(buf),
                ByteBufCodecs.STRING_UTF8.decode(buf),
                ByteBufCodecs.STRING_UTF8.decode(buf),
                ByteBufCodecs.VAR_INT.decode(buf),
                ByteBufCodecs.VAR_INT.decode(buf),
                buf.readBoolean()
        );
    }

    public static void encodeNode(ByteBuf buf, NodeData node) {
        ByteBufCodecs.STRING_UTF8.encode(buf, node.id);
        ByteBufCodecs.STRING_UTF8.encode(buf, node.pageId);
        ByteBufCodecs.STRING_UTF8.encode(buf, node.type);
        ByteBufCodecs.STRING_UTF8.encode(buf, node.chestId);
        ByteBufCodecs.STRING_UTF8.encode(buf, node.dimensionKey);
        buf.writeLong(node.pos);
        ByteBufCodecs.VAR_INT.encode(buf, node.x);
        ByteBufCodecs.VAR_INT.encode(buf, node.y);
        buf.writeBoolean(node.expanded);
        buf.writeBoolean(node.enabled);
        encodeStrings(buf, node.filterItemIds);
        encodeStrings(buf, node.receiveFilterIds);
        ByteBufCodecs.STRING_UTF8.encode(buf, node.targetPlayerId == null ? "" : node.targetPlayerId);
        encodeReplenishRules(buf, node.replenishRules);
        encodeNodeFlowStats(buf, node.flowStats);
        ByteBufCodecs.STRING_UTF8.encode(buf, node.label == null ? "" : node.label);
        ByteBufCodecs.STRING_UTF8.encode(buf, node.linkedNodeId == null ? "" : node.linkedNodeId);
        ByteBufCodecs.VAR_INT.encode(buf, node.gateMin);
        ByteBufCodecs.VAR_INT.encode(buf, node.gateMax);
        buf.writeBoolean(node.gateCheckSource);
    }

    public static EdgeData decodeEdge(ByteBuf buf) {
        return new EdgeData(
                ByteBufCodecs.STRING_UTF8.decode(buf),
                ByteBufCodecs.STRING_UTF8.decode(buf),
                ByteBufCodecs.STRING_UTF8.decode(buf),
                ByteBufCodecs.STRING_UTF8.decode(buf),
                ByteBufCodecs.STRING_UTF8.decode(buf),
                ByteBufCodecs.STRING_UTF8.decode(buf),
                buf.readBoolean(),
                ByteBufCodecs.STRING_UTF8.decode(buf),
                ByteBufCodecs.VAR_INT.decode(buf),
                decodeEdgeItemRates(buf)
        );
    }

    public static void encodeEdge(ByteBuf buf, EdgeData edge) {
        ByteBufCodecs.STRING_UTF8.encode(buf, edge.id);
        ByteBufCodecs.STRING_UTF8.encode(buf, edge.pageId);
        ByteBufCodecs.STRING_UTF8.encode(buf, edge.fromNodeId);
        ByteBufCodecs.STRING_UTF8.encode(buf, edge.toNodeId);
        ByteBufCodecs.STRING_UTF8.encode(buf, edge.fromPortKey);
        ByteBufCodecs.STRING_UTF8.encode(buf, edge.toPortKey);
        buf.writeBoolean(edge.enabled);
        ByteBufCodecs.STRING_UTF8.encode(buf, edge.health);
        ByteBufCodecs.VAR_INT.encode(buf, edge.actualRatePerMinute);
        encodeEdgeItemRates(buf, edge.itemRates);
    }

    private static List<String> decodeStrings(ByteBuf buf) {
        int count = checkCount(ByteBufCodecs.VAR_INT.decode(buf), MAX_STRINGS);
        List<String> strings = new ArrayList<>(count);
        for (int i = 0; i < count; i++) strings.add(ByteBufCodecs.STRING_UTF8.decode(buf));
        return strings;
    }

    public static void encodeStrings(ByteBuf buf, List<String> strings) {
        ByteBufCodecs.VAR_INT.encode(buf, strings.size());
        for (String value : strings) ByteBufCodecs.STRING_UTF8.encode(buf, value);
    }

    private static List<GraphOptionData> decodeGraphOptions(ByteBuf buf) {
        int count = checkCount(ByteBufCodecs.VAR_INT.decode(buf), MAX_GRAPH_OPTIONS);
        List<GraphOptionData> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            rows.add(new GraphOptionData(
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    buf.readBoolean()
            ));
        }
        return rows;
    }

    private static void encodeGraphOptions(ByteBuf buf, List<GraphOptionData> rows) {
        ByteBufCodecs.VAR_INT.encode(buf, rows.size());
        for (GraphOptionData row : rows) {
            ByteBufCodecs.STRING_UTF8.encode(buf, row.kind);
            ByteBufCodecs.STRING_UTF8.encode(buf, row.id);
            ByteBufCodecs.STRING_UTF8.encode(buf, row.label);
            buf.writeBoolean(row.writable);
        }
    }

    private static List<TeamData> decodeTeams(ByteBuf buf) {
        int count = checkCount(ByteBufCodecs.VAR_INT.decode(buf), MAX_TEAMS);
        List<TeamData> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            rows.add(new TeamData(
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    buf.readBoolean(),
                    decodeTeamMembers(buf)
            ));
        }
        return rows;
    }

    private static void encodeTeams(ByteBuf buf, List<TeamData> rows) {
        ByteBufCodecs.VAR_INT.encode(buf, rows.size());
        for (TeamData row : rows) {
            ByteBufCodecs.STRING_UTF8.encode(buf, row.id);
            ByteBufCodecs.STRING_UTF8.encode(buf, row.name);
            ByteBufCodecs.STRING_UTF8.encode(buf, row.ownerId);
            ByteBufCodecs.STRING_UTF8.encode(buf, row.selfLevel);
            buf.writeBoolean(row.invited);
            encodeTeamMembers(buf, row.members);
        }
    }

    private static List<TeamMemberData> decodeTeamMembers(ByteBuf buf) {
        int count = checkCount(ByteBufCodecs.VAR_INT.decode(buf), MAX_TEAM_MEMBERS);
        List<TeamMemberData> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            rows.add(new TeamMemberData(
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    buf.readBoolean()
            ));
        }
        return rows;
    }

    private static void encodeTeamMembers(ByteBuf buf, List<TeamMemberData> rows) {
        if (rows == null) rows = List.of();
        ByteBufCodecs.VAR_INT.encode(buf, rows.size());
        for (TeamMemberData row : rows) {
            ByteBufCodecs.STRING_UTF8.encode(buf, row.id);
            ByteBufCodecs.STRING_UTF8.encode(buf, row.name);
            ByteBufCodecs.STRING_UTF8.encode(buf, row.level);
            buf.writeBoolean(row.pending);
        }
    }

    private static List<ReplenishRuleData> decodeReplenishRules(ByteBuf buf) {
        int count = checkCount(ByteBufCodecs.VAR_INT.decode(buf), MAX_REPLENISH_RULES);
        List<ReplenishRuleData> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            rows.add(new ReplenishRuleData(ByteBufCodecs.STRING_UTF8.decode(buf), ByteBufCodecs.VAR_INT.decode(buf)));
        }
        return rows;
    }

    private static void encodeReplenishRules(ByteBuf buf, List<ReplenishRuleData> rows) {
        ByteBufCodecs.VAR_INT.encode(buf, rows.size());
        for (ReplenishRuleData row : rows) {
            ByteBufCodecs.STRING_UTF8.encode(buf, row.itemId);
            ByteBufCodecs.VAR_INT.encode(buf, row.targetCount);
        }
    }

    private static List<NodeFlowData> decodeNodeFlowStats(ByteBuf buf) {
        int count = checkCount(ByteBufCodecs.VAR_INT.decode(buf), MAX_NODE_FLOW_STATS);
        List<NodeFlowData> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            rows.add(new NodeFlowData(
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    buf.readLong(),
                    buf.readLong()
            ));
        }
        return rows;
    }

    private static void encodeNodeFlowStats(ByteBuf buf, List<NodeFlowData> rows) {
        ByteBufCodecs.VAR_INT.encode(buf, rows.size());
        for (NodeFlowData row : rows) {
            ByteBufCodecs.STRING_UTF8.encode(buf, row.itemId);
            ByteBufCodecs.VAR_INT.encode(buf, row.inputRatePerMinute);
            ByteBufCodecs.VAR_INT.encode(buf, row.outputRatePerMinute);
            buf.writeLong(row.inputTotal);
            buf.writeLong(row.outputTotal);
        }
    }

    private static List<EdgeItemRateData> decodeEdgeItemRates(ByteBuf buf) {
        int count = checkCount(ByteBufCodecs.VAR_INT.decode(buf), MAX_EDGE_ITEM_RATES);
        List<EdgeItemRateData> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            rows.add(new EdgeItemRateData(
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    buf.readBoolean(),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    buf.readBoolean()
            ));
        }
        return rows;
    }

    private static void encodeEdgeItemRates(ByteBuf buf, List<EdgeItemRateData> rows) {
        ByteBufCodecs.VAR_INT.encode(buf, rows.size());
        for (EdgeItemRateData row : rows) {
            ByteBufCodecs.STRING_UTF8.encode(buf, row.itemId);
            buf.writeBoolean(row.rateLimitEnabled);
            ByteBufCodecs.VAR_INT.encode(buf, row.rateLimitSeconds);
            ByteBufCodecs.VAR_INT.encode(buf, row.rateLimitItems);
            ByteBufCodecs.STRING_UTF8.encode(buf, row.health);
            ByteBufCodecs.VAR_INT.encode(buf, row.actualRatePerMinute);
            buf.writeBoolean(row.configured);
        }
    }

    private static List<ChestData> decodeChests(ByteBuf buf) {
        int count = checkCount(ByteBufCodecs.VAR_INT.decode(buf), MAX_CHESTS);
        List<ChestData> chests = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            chests.add(new ChestData(
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    buf.readLong(),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    buf.readLong(),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    buf.readBoolean()
            ));
        }
        return chests;
    }

    private static void encodeChests(ByteBuf buf, List<ChestData> chests) {
        ByteBufCodecs.VAR_INT.encode(buf, chests.size());
        for (ChestData chest : chests) {
            ByteBufCodecs.STRING_UTF8.encode(buf, chest.chestId);
            ByteBufCodecs.STRING_UTF8.encode(buf, chest.dimensionKey);
            buf.writeLong(chest.pos);
            ByteBufCodecs.VAR_INT.encode(buf, chest.networkBandwidth);
            ByteBufCodecs.VAR_INT.encode(buf, chest.stressBandwidthUsed);
            ByteBufCodecs.VAR_INT.encode(buf, chest.remainingTransferBandwidth);
            ByteBufCodecs.STRING_UTF8.encode(buf, chest.graphKind);
            ByteBufCodecs.STRING_UTF8.encode(buf, chest.graphId);
            ByteBufCodecs.STRING_UTF8.encode(buf, chest.status);
            buf.writeLong(chest.lastSimulatedGameTime);
            ByteBufCodecs.STRING_UTF8.encode(buf, chest.statusMessage);
            buf.writeBoolean(chest.offlineSnapshotEnabled);
        }
    }

    public static TransferGraphSyncPacket from(GraphKey key, TransferGraph graph, List<ChestData> chests,
                                               List<GraphOptionData> graphOptions, List<TeamData> teams) {
        List<PageData> pages = new ArrayList<>();
        for (TransferGraphPage page : graph.getPages()) pages.add(new PageData(page.getId(), page.getName(), page.isEnabled(), page.getOrder()));
        List<NodeData> nodes = new ArrayList<>();
        for (TransferNode node : graph.getNodes()) {
            List<NodeFlowData> flows = new ArrayList<>();
            for (TransferNode.FlowSnapshot flow : node.getFlowStats()) {
                flows.add(new NodeFlowData(flow.itemId(), flow.inputRatePerMinute(), flow.outputRatePerMinute(), flow.inputTotal(), flow.outputTotal()));
            }
            List<ReplenishRuleData> replenish = new ArrayList<>();
            for (TransferNode.ReplenishRule rule : node.getReplenishRules()) replenish.add(new ReplenishRuleData(rule.itemId(), rule.targetCount()));
            nodes.add(new NodeData(node.getId(), node.getPageId(), node.getNodeType().name(), node.getChestId(), node.getDimensionKey(),
                    node.getPos().asLong(), node.getX(), node.getY(), node.isExpanded(), node.isEnabled(),
                    List.copyOf(node.getFilterItemIds()), List.copyOf(node.getReceiveFilterIds()),
                    node.getTargetPlayerId() == null ? "" : node.getTargetPlayerId().toString(), replenish, flows,
                    node.getLabel(), node.getLinkedNodeId(), node.getGateMin(), node.getGateMax(), node.isGateCheckSource()));
        }
        List<EdgeData> edges = new ArrayList<>();
        for (TransferEdge edge : graph.getEdges()) {
            List<EdgeItemRateData> rows = new ArrayList<>();
            for (TransferEdge.ItemRateSnapshot row : edge.getItemRates()) {
                rows.add(new EdgeItemRateData(row.itemId(), row.rateLimitEnabled(), row.rateLimitSeconds(), row.rateLimitItems(),
                        row.health(), row.actualRatePerMinute(), row.configured()));
            }
            edges.add(new EdgeData(edge.getId(), edge.getPageId(), edge.getFromNodeId(), edge.getToNodeId(), edge.getFromPortKey(), edge.getToPortKey(),
                    edge.isEnabled(), edge.getHealth(), edge.getActualRatePerMinute(), rows));
        }
        return new TransferGraphSyncPacket(key.kind().name(), key.id() == null ? "" : key.id().toString(), graphOptions, teams, pages, nodes, edges, chests);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(TransferGraphSyncPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            ClientTransferGraphCache.update(packet);
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof com.pockethomestead.client.screen.TransferGraphScreen screen) screen.onGraphSynced();
        });
    }
}
