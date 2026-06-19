package com.pockethomestead.network;

import com.pockethomestead.registry.ChestRegistryManager;
import com.pockethomestead.transfer.TransferEdge;
import com.pockethomestead.transfer.TransferGraph;
import com.pockethomestead.transfer.TransferGraphPage;
import com.pockethomestead.transfer.TransferGraphStorage;
import com.pockethomestead.transfer.TransferNode;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

public record UpdateTransferGraphPacket(String action, List<String> values, int x, int y, int rate) implements CustomPacketPayload {
    public static final Type<UpdateTransferGraphPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("pockethomestead", "update_transfer_graph"));

    public static final StreamCodec<ByteBuf, UpdateTransferGraphPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public UpdateTransferGraphPacket decode(ByteBuf buf) {
            String action = ByteBufCodecs.STRING_UTF8.decode(buf);
            int count = ByteBufCodecs.VAR_INT.decode(buf);
            List<String> values = new ArrayList<>();
            for (int i = 0; i < count; i++) values.add(ByteBufCodecs.STRING_UTF8.decode(buf));
            return new UpdateTransferGraphPacket(action, values, ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_INT.decode(buf));
        }

        @Override
        public void encode(ByteBuf buf, UpdateTransferGraphPacket pkt) {
            ByteBufCodecs.STRING_UTF8.encode(buf, pkt.action);
            ByteBufCodecs.VAR_INT.encode(buf, pkt.values.size());
            for (String value : pkt.values) ByteBufCodecs.STRING_UTF8.encode(buf, value);
            ByteBufCodecs.VAR_INT.encode(buf, pkt.x);
            ByteBufCodecs.VAR_INT.encode(buf, pkt.y);
            ByteBufCodecs.VAR_INT.encode(buf, pkt.rate);
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(UpdateTransferGraphPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            TransferGraphStorage storage = TransferGraphStorage.get(player.server);
            TransferGraph graph = storage.graphFor(player.getUUID());
            boolean changed = apply(packet, graph, player);
            if (changed) storage.setDirty();
            RequestTransferGraphPacket.sendGraphTo(player);
        });
    }

    private static boolean apply(UpdateTransferGraphPacket packet, TransferGraph graph, ServerPlayer player) {
        List<String> v = packet.values;
        switch (packet.action) {
            case "ADD_PAGE" -> { graph.addPage(v.isEmpty() ? "新页面" : v.get(0)); return true; }
            case "RENAME_PAGE" -> {
                if (v.size() < 2) return false;
                TransferGraphPage page = graph.getPage(v.get(0));
                if (page == null) return false;
                page.setName(v.get(1));
                return true;
            }
            case "DELETE_PAGE" -> { if (v.isEmpty()) return false; graph.removePage(v.get(0)); return true; }
            case "TOGGLE_PAGE" -> {
                if (v.isEmpty()) return false;
                TransferGraphPage page = graph.getPage(v.get(0));
                if (page == null) return false;
                page.setEnabled(!page.isEnabled());
                return true;
            }
            case "ADD_NODE" -> {
                if (v.size() < 5) return false;
                String pageId = v.get(0);
                ChestRegistryManager.ChestType type = parseType(v.get(1));
                if (type == null) return false;
                BlockPos pos = parsePos(v.get(4));
                if (pos == null || !ownsChest(player, type, v.get(2), v.get(3), pos)) return false;
                graph.addNode(pageId, type, v.get(2), v.get(3), pos, packet.x, packet.y);
                return true;
            }
            case "ADD_REROUTE_NODE" -> {
                if (v.isEmpty()) return false;
                graph.addRerouteNode(v.get(0), packet.x, packet.y);
                return true;
            }
            case "ADD_TRASH_NODE" -> {
                if (v.isEmpty()) return false;
                graph.addTrashNode(v.get(0), packet.x, packet.y);
                return true;
            }
            case "MOVE_NODE" -> {
                if (v.isEmpty()) return false;
                TransferNode node = graph.getNode(v.get(0));
                if (node == null) return false;
                node.setPosition(packet.x, packet.y);
                return true;
            }
            case "SET_EXPANDED" -> {
                if (v.size() < 2) return false;
                TransferNode node = graph.getNode(v.get(0));
                if (node == null) return false;
                node.setExpanded(Boolean.parseBoolean(v.get(1)));
                return true;
            }
            case "TOGGLE_NODE" -> {
                if (v.isEmpty()) return false;
                TransferNode node = graph.getNode(v.get(0));
                if (node == null) return false;
                node.setEnabled(!node.isEnabled());
                return true;
            }
            case "ADD_FILTER_ITEM" -> {
                if (v.size() < 2 || !validItem(v.get(1))) return false;
                TransferNode node = graph.getNode(v.get(0));
                if (node == null || (node.getNodeType() != TransferNode.NodeType.SUPPLY && node.getNodeType() != TransferNode.NodeType.REROUTE)) return false;
                node.addFilterItem(v.get(1));
                return true;
            }
            case "REMOVE_FILTER_ITEM" -> {
                if (v.size() < 2) return false;
                graph.removeFilterItem(v.get(0), v.get(1));
                return true;
            }
            case "ADD_EDGE" -> {
                if (v.size() < 4) return false;
                TransferNode from = graph.getNode(v.get(0));
                TransferNode to = graph.getNode(v.get(2));
                if (from == null || to == null || from.getId().equals(to.getId())) return false;
                if (!from.getPageId().equals(to.getPageId())) return false;
                if (!validEdgeDirection(from, to)) return false;
                if (!validPort(from, v.get(1))) return false;
                graph.addEdge(from.getPageId(), from.getId(), v.get(1), to.getId(), v.get(3), false, 1, TransferEdge.clampRate(packet.rate));
                return true;
            }
            case "UPDATE_EDGE_RATE_LIMIT" -> {
                if (v.isEmpty()) return false;
                TransferEdge edge = graph.getEdge(v.get(0));
                if (edge == null) return false;
                boolean enabled = v.size() < 2 || Boolean.parseBoolean(v.get(1));
                edge.setRateLimit(enabled, packet.x, packet.y);
                return true;
            }
            case "TOGGLE_EDGE" -> {
                if (v.isEmpty()) return false;
                TransferEdge edge = graph.getEdge(v.get(0));
                if (edge == null) return false;
                edge.setEnabled(!edge.isEnabled());
                return true;
            }
            case "DELETE_EDGE" -> { if (v.isEmpty()) return false; graph.removeEdge(v.get(0)); return true; }
            case "DELETE_NODE" -> { if (v.isEmpty()) return false; graph.removeNode(v.get(0)); return true; }
            default -> { return false; }
        }
    }

    private static boolean validPort(TransferNode from, String port) {
        if (from.getNodeType() == TransferNode.NodeType.REROUTE) {
            return TransferEdge.PORT_ALL.equals(port)
                    || (port != null && port.startsWith(TransferEdge.ITEM_PREFIX) && validItem(port.substring(TransferEdge.ITEM_PREFIX.length())));
        }
        if (TransferEdge.PORT_ALL.equals(port)) return true;
        if (!port.startsWith(TransferEdge.ITEM_PREFIX)) return false;
        String itemId = port.substring(TransferEdge.ITEM_PREFIX.length());
        return from.getFilterItemIds().contains(itemId) && validItem(itemId);
    }

    private static boolean validEdgeDirection(TransferNode from, TransferNode to) {
        if (from.getNodeType() == TransferNode.NodeType.PICKUP || from.getNodeType() == TransferNode.NodeType.TRASH) return false;
        if (to.getNodeType() == TransferNode.NodeType.SUPPLY) return false;
        return from.getNodeType() == TransferNode.NodeType.SUPPLY || from.getNodeType() == TransferNode.NodeType.REROUTE;
    }

    private static ChestRegistryManager.ChestType parseType(String value) {
        try { return ChestRegistryManager.ChestType.valueOf(value); } catch (Exception e) { return null; }
    }

    private static BlockPos parsePos(String value) {
        try { return BlockPos.of(Long.parseLong(value)); } catch (NumberFormatException e) { return null; }
    }

    private static boolean ownsChest(ServerPlayer player, ChestRegistryManager.ChestType type, String chestId, String dimensionKey, BlockPos pos) {
        for (ChestRegistryManager.ChestLocation loc : ChestRegistryManager.getInstance().getChestLocationsByType(player.getUUID(), chestId, type)) {
            if (loc.dimensionKey.equals(dimensionKey) && loc.pos.equals(pos)) return true;
        }
        return false;
    }

    private static boolean validItem(String id) {
        ResourceLocation loc = ResourceLocation.tryParse(id);
        if (loc == null) return false;
        Item item = BuiltInRegistries.ITEM.get(loc);
        return item != Items.AIR;
    }
}
