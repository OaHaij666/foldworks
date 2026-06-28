package com.pockethomestead.network;

import com.pockethomestead.client.ClientTransferGraphCache;
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
        List<PageData> pages,
        List<NodeData> nodes,
        List<EdgeData> edges,
        List<ChestData> chests
) implements CustomPacketPayload {
    public static final Type<TransferGraphSyncPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("pockethomestead", "transfer_graph_sync"));

    public record PageData(String id, String name, boolean enabled, int order) {}
    public record NodeFlowData(String itemId, int inputRatePerMinute, int outputRatePerMinute, long inputTotal, long outputTotal) {}
    public record NodeData(String id, String pageId, String type, String chestId, String dimensionKey, long pos, int x, int y,
                           boolean expanded, boolean enabled, List<String> filterItemIds, List<NodeFlowData> flowStats) {}
    public record EdgeItemRateData(String itemId, boolean rateLimitEnabled, int rateLimitSeconds, int rateLimitItems,
                                   String health, int actualRatePerMinute, boolean configured) {}
    public record EdgeData(String id, String pageId, String fromNodeId, String toNodeId, String fromPortKey, String toPortKey,
                           boolean enabled, boolean rateLimitEnabled, int rateLimitSeconds, int rateLimitItems,
                           String health, int actualRatePerMinute, List<EdgeItemRateData> itemRates) {}
    public record ChestData(String chestId, String dimensionKey, long pos,
                            int networkBandwidth, int stressBandwidthUsed, int remainingTransferBandwidth) {}

    public static final StreamCodec<ByteBuf, TransferGraphSyncPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public TransferGraphSyncPacket decode(ByteBuf buf) {
            List<PageData> pages = new ArrayList<>();
            int pageCount = ByteBufCodecs.VAR_INT.decode(buf);
            for (int i = 0; i < pageCount; i++) {
                pages.add(new PageData(ByteBufCodecs.STRING_UTF8.decode(buf), ByteBufCodecs.STRING_UTF8.decode(buf), buf.readBoolean(), ByteBufCodecs.VAR_INT.decode(buf)));
            }

            List<NodeData> nodes = new ArrayList<>();
            int nodeCount = ByteBufCodecs.VAR_INT.decode(buf);
            for (int i = 0; i < nodeCount; i++) {
                nodes.add(new NodeData(
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
                        decodeNodeFlowStats(buf)
                ));
            }

            List<EdgeData> edges = new ArrayList<>();
            int edgeCount = ByteBufCodecs.VAR_INT.decode(buf);
            for (int i = 0; i < edgeCount; i++) {
                edges.add(new EdgeData(
                        ByteBufCodecs.STRING_UTF8.decode(buf),
                        ByteBufCodecs.STRING_UTF8.decode(buf),
                        ByteBufCodecs.STRING_UTF8.decode(buf),
                        ByteBufCodecs.STRING_UTF8.decode(buf),
                        ByteBufCodecs.STRING_UTF8.decode(buf),
                        ByteBufCodecs.STRING_UTF8.decode(buf),
                        buf.readBoolean(),
                        buf.readBoolean(),
                        ByteBufCodecs.VAR_INT.decode(buf),
                        ByteBufCodecs.VAR_INT.decode(buf),
                        ByteBufCodecs.STRING_UTF8.decode(buf),
                        ByteBufCodecs.VAR_INT.decode(buf),
                        decodeEdgeItemRates(buf)
                ));
            }

            return new TransferGraphSyncPacket(pages, nodes, edges, decodeChests(buf));
        }

        @Override
        public void encode(ByteBuf buf, TransferGraphSyncPacket pkt) {
            ByteBufCodecs.VAR_INT.encode(buf, pkt.pages.size());
            for (PageData page : pkt.pages) {
                ByteBufCodecs.STRING_UTF8.encode(buf, page.id);
                ByteBufCodecs.STRING_UTF8.encode(buf, page.name);
                buf.writeBoolean(page.enabled);
                ByteBufCodecs.VAR_INT.encode(buf, page.order);
            }

            ByteBufCodecs.VAR_INT.encode(buf, pkt.nodes.size());
            for (NodeData node : pkt.nodes) {
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
                encodeNodeFlowStats(buf, node.flowStats);
            }

            ByteBufCodecs.VAR_INT.encode(buf, pkt.edges.size());
            for (EdgeData edge : pkt.edges) {
                ByteBufCodecs.STRING_UTF8.encode(buf, edge.id);
                ByteBufCodecs.STRING_UTF8.encode(buf, edge.pageId);
                ByteBufCodecs.STRING_UTF8.encode(buf, edge.fromNodeId);
                ByteBufCodecs.STRING_UTF8.encode(buf, edge.toNodeId);
                ByteBufCodecs.STRING_UTF8.encode(buf, edge.fromPortKey);
                ByteBufCodecs.STRING_UTF8.encode(buf, edge.toPortKey);
                buf.writeBoolean(edge.enabled);
                buf.writeBoolean(edge.rateLimitEnabled);
                ByteBufCodecs.VAR_INT.encode(buf, edge.rateLimitSeconds);
                ByteBufCodecs.VAR_INT.encode(buf, edge.rateLimitItems);
                ByteBufCodecs.STRING_UTF8.encode(buf, edge.health);
                ByteBufCodecs.VAR_INT.encode(buf, edge.actualRatePerMinute);
                encodeEdgeItemRates(buf, edge.itemRates);
            }

            encodeChests(buf, pkt.chests);
        }
    };

    private static List<String> decodeStrings(ByteBuf buf) {
        List<String> strings = new ArrayList<>();
        int count = ByteBufCodecs.VAR_INT.decode(buf);
        for (int i = 0; i < count; i++) strings.add(ByteBufCodecs.STRING_UTF8.decode(buf));
        return strings;
    }

    private static void encodeStrings(ByteBuf buf, List<String> strings) {
        ByteBufCodecs.VAR_INT.encode(buf, strings.size());
        for (String value : strings) ByteBufCodecs.STRING_UTF8.encode(buf, value);
    }

    private static List<NodeFlowData> decodeNodeFlowStats(ByteBuf buf) {
        List<NodeFlowData> rows = new ArrayList<>();
        int count = ByteBufCodecs.VAR_INT.decode(buf);
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
        List<EdgeItemRateData> rows = new ArrayList<>();
        int count = ByteBufCodecs.VAR_INT.decode(buf);
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
        List<ChestData> chests = new ArrayList<>();
        int count = ByteBufCodecs.VAR_INT.decode(buf);
        for (int i = 0; i < count; i++) {
            chests.add(new ChestData(
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    buf.readLong(),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf)
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
        }
    }

    public static TransferGraphSyncPacket from(TransferGraph graph, List<ChestData> chests) {
        List<PageData> pages = new ArrayList<>();
        for (TransferGraphPage page : graph.getPages()) pages.add(new PageData(page.getId(), page.getName(), page.isEnabled(), page.getOrder()));
        List<NodeData> nodes = new ArrayList<>();
        for (TransferNode node : graph.getNodes()) {
            List<NodeFlowData> flows = new ArrayList<>();
            for (TransferNode.FlowSnapshot flow : node.getFlowStats()) {
                flows.add(new NodeFlowData(flow.itemId(), flow.inputRatePerMinute(), flow.outputRatePerMinute(), flow.inputTotal(), flow.outputTotal()));
            }
            nodes.add(new NodeData(node.getId(), node.getPageId(), node.getNodeType().name(), node.getChestId(), node.getDimensionKey(), node.getPos().asLong(), node.getX(), node.getY(), node.isExpanded(), node.isEnabled(), List.copyOf(node.getFilterItemIds()), flows));
        }
        List<EdgeData> edges = new ArrayList<>();
        for (TransferEdge edge : graph.getEdges()) {
            List<EdgeItemRateData> rows = new ArrayList<>();
            for (TransferEdge.ItemRateSnapshot row : edge.getItemRates()) {
                rows.add(new EdgeItemRateData(row.itemId(), row.rateLimitEnabled(), row.rateLimitSeconds(), row.rateLimitItems(),
                        row.health(), row.actualRatePerMinute(), row.configured()));
            }
            edges.add(new EdgeData(edge.getId(), edge.getPageId(), edge.getFromNodeId(), edge.getToNodeId(), edge.getFromPortKey(), edge.getToPortKey(),
                    edge.isEnabled(), edge.isRateLimitEnabled(), edge.getRateLimitSeconds(), edge.getRateLimitItems(), edge.getHealth(), edge.getActualRatePerMinute(), rows));
        }
        return new TransferGraphSyncPacket(pages, nodes, edges, chests);
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
