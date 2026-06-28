package com.pockethomestead.network;

import com.pockethomestead.blockentity.BaseChestBlockEntity;
import com.pockethomestead.blockentity.HomesteadChestAccess;
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
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
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
                if (v.size() < 4) return false;
                String pageId = v.get(0);
                String chestId = v.get(1);
                String dimensionKey = v.get(2);
                BlockPos pos = parsePos(v.get(3));
                if (pos == null || !ownsChest(player, chestId, dimensionKey, pos)) return false;
                graph.addNode(pageId, chestId, dimensionKey, pos, packet.x, packet.y);
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
                if (v.size() < 2 || !validFilterResource(v.get(1))) return false;
                TransferNode node = graph.getNode(v.get(0));
                if (node == null || (node.getNodeType() != TransferNode.NodeType.CHEST && node.getNodeType() != TransferNode.NodeType.REROUTE)) return false;
                node.addFilterItem(normalizeFilterResource(v.get(1)));
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
                String fromPort = v.get(1);
                String toPort = normalizeInputPort(fromPort, v.get(3));
                if (!validEdgeDirection(from, to)) return false;
                if (!validPort(from, fromPort)) return false;
                if (!validTargetPort(to, toPort)) return false;
                if (!TransferEdge.sameResourceKind(fromPort, toPort)) return false;
                graph.addEdge(from.getPageId(), from.getId(), fromPort, to.getId(), toPort, false, 1, TransferEdge.clampRate(packet.rate));
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

    private static String normalizeInputPort(String fromPort, String requested) {
        if (requested == null || requested.isBlank() || TransferEdge.PORT_IN.equals(requested)) {
            return TransferEdge.inputPortFor(fromPort);
        }
        return requested;
    }

    private static boolean validPort(TransferNode from, String port) {
        if (from.getNodeType() == TransferNode.NodeType.TRASH) return false;
        if (TransferEdge.ITEM_ALL.equals(port) || TransferEdge.FLUID_ALL.equals(port) || TransferEdge.ENERGY_FE.equals(port) || TransferEdge.STRESS_SU.equals(port)) return true;
        if (port == null) return false;
        if (port.startsWith(TransferEdge.FLUID_PREFIX)) return validFluid(port.substring(TransferEdge.FLUID_PREFIX.length()));
        if (!port.startsWith(TransferEdge.ITEM_PREFIX)) return false;
        String itemId = port.substring(TransferEdge.ITEM_PREFIX.length());
        return (from.getNodeType() == TransferNode.NodeType.REROUTE || from.getFilterItemIds().contains(itemId)) && validItem(itemId);
    }

    private static boolean validTargetPort(TransferNode to, String port) {
        if (to.getNodeType() == TransferNode.NodeType.TRASH) {
            return TransferEdge.ITEM_IN.equals(port) || TransferEdge.FLUID_IN.equals(port);
        }
        return to.getNodeType() == TransferNode.NodeType.CHEST || to.getNodeType() == TransferNode.NodeType.REROUTE;
    }

    private static boolean validEdgeDirection(TransferNode from, TransferNode to) {
        if (from.getNodeType() == TransferNode.NodeType.TRASH) return false;
        return from.getNodeType() == TransferNode.NodeType.CHEST || from.getNodeType() == TransferNode.NodeType.REROUTE;
    }

    private static BlockPos parsePos(String value) {
        try { return BlockPos.of(Long.parseLong(value)); } catch (NumberFormatException e) { return null; }
    }

    private static boolean ownsChest(ServerPlayer player, String chestId, String dimensionKey, BlockPos pos) {
        for (ChestRegistryManager.ChestLocation loc : ChestRegistryManager.getInstance().getChestLocations(player.getUUID(), chestId)) {
            if (loc.dimensionKey.equals(dimensionKey) && loc.pos.equals(pos)) {
                BaseChestBlockEntity be = loadedChest(player, dimensionKey, pos);
                return be != null && be.hasNetworkUpgrade();
            }
        }
        return false;
    }

    private static BaseChestBlockEntity loadedChest(ServerPlayer player, String dimensionKey, BlockPos pos) {
        ResourceLocation dimLoc = ResourceLocation.tryParse(dimensionKey);
        if (dimLoc == null) return null;
        net.minecraft.server.level.ServerLevel level = player.server.getLevel(net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION,
                dimLoc
        ));
        if (level == null) return null;
        return HomesteadChestAccess.resolve(level.getBlockEntity(pos));
    }

    private static boolean validItem(String id) {
        ResourceLocation loc = ResourceLocation.tryParse(id);
        if (loc == null) return false;
        Item item = BuiltInRegistries.ITEM.get(loc);
        return item != Items.AIR;
    }

    private static boolean validFluid(String id) {
        if ("*".equals(id)) return true;
        ResourceLocation loc = ResourceLocation.tryParse(id);
        if (loc == null) return false;
        Fluid fluid = BuiltInRegistries.FLUID.get(loc);
        return fluid != Fluids.EMPTY;
    }

    private static boolean validFilterResource(String id) {
        if (id == null || id.isBlank()) return false;
        if (id.startsWith(TransferEdge.ITEM_PREFIX)) return validItem(id.substring(TransferEdge.ITEM_PREFIX.length()));
        if (id.startsWith(TransferEdge.FLUID_PREFIX)) return validFluid(id.substring(TransferEdge.FLUID_PREFIX.length()));
        return validItem(id);
    }

    private static String normalizeFilterResource(String id) {
        if (id == null) return "";
        if (id.startsWith(TransferEdge.ITEM_PREFIX)) return id.substring(TransferEdge.ITEM_PREFIX.length());
        return id;
    }
}
