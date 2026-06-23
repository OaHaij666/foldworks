package com.pockethomestead.network;

import com.pockethomestead.transfer.*;
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
        List<TransferGraphSyncPacket.PageData> pages,
        List<TransferGraphSyncPacket.NodeData> nodes,
        List<TransferGraphSyncPacket.EdgeData> edges
) implements CustomPacketPayload {
    public static final Type<SaveTransferGraphPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("pockethomestead", "save_transfer_graph"));

    public static final StreamCodec<ByteBuf, SaveTransferGraphPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public SaveTransferGraphPacket decode(ByteBuf buf) {
            List<TransferGraphSyncPacket.PageData> pages = new ArrayList<>();
            int pageCount = ByteBufCodecs.VAR_INT.decode(buf);
            for (int i = 0; i < pageCount; i++) {
                pages.add(new TransferGraphSyncPacket.PageData(
                        ByteBufCodecs.STRING_UTF8.decode(buf),
                        ByteBufCodecs.STRING_UTF8.decode(buf),
                        buf.readBoolean(),
                        ByteBufCodecs.VAR_INT.decode(buf)
                ));
            }

            List<TransferGraphSyncPacket.NodeData> nodes = new ArrayList<>();
            int nodeCount = ByteBufCodecs.VAR_INT.decode(buf);
            for (int i = 0; i < nodeCount; i++) {
                String id = ByteBufCodecs.STRING_UTF8.decode(buf);
                String pageId = ByteBufCodecs.STRING_UTF8.decode(buf);
                String type = ByteBufCodecs.STRING_UTF8.decode(buf);
                String chestId = ByteBufCodecs.STRING_UTF8.decode(buf);
                String dim = ByteBufCodecs.STRING_UTF8.decode(buf);
                long pos = buf.readLong();
                int x = ByteBufCodecs.VAR_INT.decode(buf);
                int y = ByteBufCodecs.VAR_INT.decode(buf);
                boolean expanded = buf.readBoolean();
                boolean enabled = buf.readBoolean();
                nodes.add(new TransferGraphSyncPacket.NodeData(id, pageId, type, chestId, dim, pos, x, y, expanded, enabled, decodeStrings(buf), decodeNodeFlowStats(buf)));
            }

            List<TransferGraphSyncPacket.EdgeData> edges = new ArrayList<>();
            int edgeCount = ByteBufCodecs.VAR_INT.decode(buf);
            for (int i = 0; i < edgeCount; i++) {
                edges.add(new TransferGraphSyncPacket.EdgeData(
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
            return new SaveTransferGraphPacket(pages, nodes, edges);
        }

        @Override
        public void encode(ByteBuf buf, SaveTransferGraphPacket pkt) {
            ByteBufCodecs.VAR_INT.encode(buf, pkt.pages.size());
            for (TransferGraphSyncPacket.PageData page : pkt.pages) {
                ByteBufCodecs.STRING_UTF8.encode(buf, page.id());
                ByteBufCodecs.STRING_UTF8.encode(buf, page.name());
                buf.writeBoolean(page.enabled());
                ByteBufCodecs.VAR_INT.encode(buf, page.order());
            }

            ByteBufCodecs.VAR_INT.encode(buf, pkt.nodes.size());
            for (TransferGraphSyncPacket.NodeData node : pkt.nodes) {
                ByteBufCodecs.STRING_UTF8.encode(buf, node.id());
                ByteBufCodecs.STRING_UTF8.encode(buf, node.pageId());
                ByteBufCodecs.STRING_UTF8.encode(buf, node.type());
                ByteBufCodecs.STRING_UTF8.encode(buf, node.chestId());
                ByteBufCodecs.STRING_UTF8.encode(buf, node.dimensionKey());
                buf.writeLong(node.pos());
                ByteBufCodecs.VAR_INT.encode(buf, node.x());
                ByteBufCodecs.VAR_INT.encode(buf, node.y());
                buf.writeBoolean(node.expanded());
                buf.writeBoolean(node.enabled());
                encodeStrings(buf, node.filterItemIds());
                encodeNodeFlowStats(buf, node.flowStats());
            }

            ByteBufCodecs.VAR_INT.encode(buf, pkt.edges.size());
            for (TransferGraphSyncPacket.EdgeData edge : pkt.edges) {
                ByteBufCodecs.STRING_UTF8.encode(buf, edge.id());
                ByteBufCodecs.STRING_UTF8.encode(buf, edge.pageId());
                ByteBufCodecs.STRING_UTF8.encode(buf, edge.fromNodeId());
                ByteBufCodecs.STRING_UTF8.encode(buf, edge.toNodeId());
                ByteBufCodecs.STRING_UTF8.encode(buf, edge.fromPortKey());
                ByteBufCodecs.STRING_UTF8.encode(buf, edge.toPortKey());
                buf.writeBoolean(edge.enabled());
                buf.writeBoolean(edge.rateLimitEnabled());
                ByteBufCodecs.VAR_INT.encode(buf, edge.rateLimitSeconds());
                ByteBufCodecs.VAR_INT.encode(buf, edge.rateLimitItems());
                ByteBufCodecs.STRING_UTF8.encode(buf, edge.health());
                ByteBufCodecs.VAR_INT.encode(buf, edge.actualRatePerMinute());
                encodeEdgeItemRates(buf, edge.itemRates());
            }
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(SaveTransferGraphPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            UUID owner = player.getUUID();
            TransferGraphStorage storage = TransferGraphStorage.get(player.server);
            TransferGraph graph = toGraph(owner, packet, storage.graphFor(owner));
            List<TransferGraphValidator.Issue> issues = new ArrayList<>(TransferGraphValidator.validate(graph));
            issues.addAll(TransferGraphValidator.validateRuntime(graph, player.server, owner));
            if (TransferGraphValidator.hasErrors(issues)) {
                PacketDistributor.sendToPlayer(player, TransferGraphValidationPacket.from(issues));
                return;
            }
            storage.replaceGraph(owner, graph);
            PacketDistributor.sendToPlayer(player, TransferGraphValidationPacket.from(issues));
            RequestTransferGraphPacket.sendGraphTo(player);
        });
    }

    private static TransferGraph toGraph(UUID owner, SaveTransferGraphPacket packet, TransferGraph previousGraph) {
        TransferGraph graph = new TransferGraph(owner);
        graph.clearAll();
        for (TransferGraphSyncPacket.PageData page : packet.pages) {
            graph.putPage(new TransferGraphPage(page.id(), page.name(), page.enabled(), page.order()));
        }
        if (packet.pages.isEmpty()) graph.ensureDefaultPage();
        for (TransferGraphSyncPacket.NodeData node : packet.nodes) {
            if ("SUPPLY".equals(node.type()) || "PICKUP".equals(node.type())) continue;
            TransferNode.NodeType type;
            try { type = TransferNode.NodeType.valueOf(node.type()); } catch (Exception e) { type = TransferNode.NodeType.CHEST; }
            TransferNode transferNode = new TransferNode(node.id(), node.pageId(), type, node.chestId(), node.dimensionKey(), BlockPos.of(node.pos()), node.x(), node.y(), node.expanded(), node.enabled(), node.filterItemIds());
            if (previousGraph != null) transferNode.copyFlowStatsFrom(previousGraph.getNode(node.id()));
            graph.putNode(transferNode);
        }
        for (TransferGraphSyncPacket.EdgeData edge : packet.edges) {
            if (graph.getNode(edge.fromNodeId()) == null || graph.getNode(edge.toNodeId()) == null) continue;
            TransferEdge transferEdge = new TransferEdge(edge.id(), edge.pageId(), edge.fromNodeId(), edge.toNodeId(), edge.fromPortKey(), edge.toPortKey(), edge.rateLimitEnabled(), edge.rateLimitSeconds(), edge.rateLimitItems(), edge.enabled());
            for (TransferGraphSyncPacket.EdgeItemRateData row : edge.itemRates()) {
                if (row.configured()) transferEdge.setItemRate(row.itemId(), row.rateLimitEnabled(), row.rateLimitSeconds(), row.rateLimitItems());
            }
            graph.putEdge(transferEdge);
        }
        return graph;
    }

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

    private static List<TransferGraphSyncPacket.NodeFlowData> decodeNodeFlowStats(ByteBuf buf) {
        List<TransferGraphSyncPacket.NodeFlowData> rows = new ArrayList<>();
        int count = ByteBufCodecs.VAR_INT.decode(buf);
        for (int i = 0; i < count; i++) {
            rows.add(new TransferGraphSyncPacket.NodeFlowData(
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    buf.readLong(),
                    buf.readLong()
            ));
        }
        return rows;
    }

    private static void encodeNodeFlowStats(ByteBuf buf, List<TransferGraphSyncPacket.NodeFlowData> rows) {
        ByteBufCodecs.VAR_INT.encode(buf, rows.size());
        for (TransferGraphSyncPacket.NodeFlowData row : rows) {
            ByteBufCodecs.STRING_UTF8.encode(buf, row.itemId());
            ByteBufCodecs.VAR_INT.encode(buf, row.inputRatePerMinute());
            ByteBufCodecs.VAR_INT.encode(buf, row.outputRatePerMinute());
            buf.writeLong(row.inputTotal());
            buf.writeLong(row.outputTotal());
        }
    }

    private static List<TransferGraphSyncPacket.EdgeItemRateData> decodeEdgeItemRates(ByteBuf buf) {
        List<TransferGraphSyncPacket.EdgeItemRateData> rows = new ArrayList<>();
        int count = ByteBufCodecs.VAR_INT.decode(buf);
        for (int i = 0; i < count; i++) {
            rows.add(new TransferGraphSyncPacket.EdgeItemRateData(
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

    private static void encodeEdgeItemRates(ByteBuf buf, List<TransferGraphSyncPacket.EdgeItemRateData> rows) {
        ByteBufCodecs.VAR_INT.encode(buf, rows.size());
        for (TransferGraphSyncPacket.EdgeItemRateData row : rows) {
            ByteBufCodecs.STRING_UTF8.encode(buf, row.itemId());
            buf.writeBoolean(row.rateLimitEnabled());
            ByteBufCodecs.VAR_INT.encode(buf, row.rateLimitSeconds());
            ByteBufCodecs.VAR_INT.encode(buf, row.rateLimitItems());
            ByteBufCodecs.STRING_UTF8.encode(buf, row.health());
            ByteBufCodecs.VAR_INT.encode(buf, row.actualRatePerMinute());
            buf.writeBoolean(row.configured());
        }
    }
}
