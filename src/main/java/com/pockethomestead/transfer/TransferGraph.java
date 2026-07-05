package com.pockethomestead.transfer;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class TransferGraph {
    public static final String DEFAULT_PAGE_ID = "default";

    private final GraphKey key;
    private final Map<String, TransferGraphPage> pages = new LinkedHashMap<>();
    private final Map<String, TransferNode> nodes = new LinkedHashMap<>();
    private final Map<String, TransferEdge> edges = new LinkedHashMap<>();

    public TransferGraph(UUID owner) {
        this(GraphKey.privateGraph(owner));
    }

    public TransferGraph(GraphKey key) {
        this.key = key == null ? GraphKey.publicGraph() : key;
        ensureDefaultPage();
    }

    public UUID getOwner() { return key.kind() == GraphKey.Kind.PRIVATE ? key.id() : null; }
    public GraphKey getKey() { return key; }
    public Collection<TransferGraphPage> getPages() { return pages.values().stream().sorted(Comparator.comparingInt(TransferGraphPage::getOrder)).toList(); }
    public Collection<TransferNode> getNodes() { return nodes.values(); }
    public Collection<TransferEdge> getEdges() { return edges.values(); }
    public TransferGraphPage getPage(String id) { return pages.get(id); }
    public TransferNode getNode(String id) { return nodes.get(id); }
    public TransferEdge getEdge(String id) { return edges.get(id); }

    public void putPage(TransferGraphPage page) { pages.put(page.getId(), page); }
    public void putNode(TransferNode node) { nodes.put(node.getId(), node); }
    public void putEdge(TransferEdge edge) { edges.put(edge.getId(), edge); }
    public void clearAll() { pages.clear(); nodes.clear(); edges.clear(); }

    public TransferGraphPage ensureDefaultPage() {
        return pages.computeIfAbsent(DEFAULT_PAGE_ID, id -> new TransferGraphPage(id, "默认页", true, 0));
    }

    public TransferGraphPage addPage(String name) {
        int order = pages.size();
        TransferGraphPage page = new TransferGraphPage(UUID.randomUUID().toString(), name, true, order);
        pages.put(page.getId(), page);
        return page;
    }

    public void removePage(String pageId) {
        if (DEFAULT_PAGE_ID.equals(pageId)) return;
        pages.remove(pageId);
        nodes.values().removeIf(node -> node.getPageId().equals(pageId));
        edges.values().removeIf(edge -> edge.getPageId().equals(pageId));
        if (pages.isEmpty()) ensureDefaultPage();
    }

    public TransferNode findNode(String chestId, String dimensionKey, BlockPos pos) {
        for (TransferNode node : nodes.values()) if (node.matches(chestId, dimensionKey, pos)) return node;
        return null;
    }

    public boolean updateChestId(String oldChestId, String newChestId, String dimensionKey, BlockPos pos) {
        boolean changed = false;
        for (TransferNode node : nodes.values()) {
            if (node.getNodeType() == TransferNode.NodeType.CHEST
                    && node.getChestId().equals(oldChestId)
                    && node.getDimensionKey().equals(dimensionKey)
                    && node.getPos().equals(pos)) {
                node.setChestId(newChestId);
                changed = true;
            }
        }
        return changed;
    }

    public boolean relocateChest(String chestId, String oldDimensionKey, BlockPos oldPos, String newDimensionKey, BlockPos newPos) {
        boolean changed = false;
        for (TransferNode node : nodes.values()) {
            if (node.getNodeType() == TransferNode.NodeType.CHEST
                    && node.getChestId().equals(chestId)
                    && node.getDimensionKey().equals(oldDimensionKey)
                    && node.getPos().equals(oldPos)) {
                node.setLocation(newDimensionKey, newPos);
                changed = true;
            }
        }
        return changed;
    }

    public TransferNode addNode(String pageId, String chestId, String dimensionKey, BlockPos pos, int x, int y) {
        if (!pages.containsKey(pageId)) pageId = ensureDefaultPage().getId();
        TransferNode existing = findNode(chestId, dimensionKey, pos);
        if (existing != null) {
            existing.setPageId(pageId);
            existing.setPosition(x, y);
            return existing;
        }
        TransferNode node = new TransferNode(UUID.randomUUID().toString(), pageId, TransferNode.NodeType.CHEST, chestId, dimensionKey, pos, x, y, false, true, List.of());
        nodes.put(node.getId(), node);
        return node;
    }

    public TransferNode addNode(String chestId, String dimensionKey, BlockPos pos, int x, int y) {
        return addNode(DEFAULT_PAGE_ID, chestId, dimensionKey, pos, x, y);
    }

    public TransferNode addRerouteNode(String pageId, int x, int y) {
        if (!pages.containsKey(pageId)) pageId = ensureDefaultPage().getId();
        TransferNode node = new TransferNode(UUID.randomUUID().toString(), pageId, TransferNode.NodeType.REROUTE, "", "", BlockPos.ZERO, x, y, false, true, List.of());
        nodes.put(node.getId(), node);
        return node;
    }

    public TransferNode addLimitGateNode(String pageId, int x, int y) {
        if (!pages.containsKey(pageId)) pageId = ensureDefaultPage().getId();
        TransferNode node = new TransferNode(UUID.randomUUID().toString(), pageId, TransferNode.NodeType.LIMIT_GATE,
                "", "", BlockPos.ZERO, x, y, false, true, List.of(), List.of(), null, List.of());
        nodes.put(node.getId(), node);
        return node;
    }

    public TransferNode addJumpInputNode(String pageId, String label, int x, int y) {
        if (!pages.containsKey(pageId)) pageId = ensureDefaultPage().getId();
        TransferNode node = new TransferNode(UUID.randomUUID().toString(), pageId, TransferNode.NodeType.JUMP_INPUT,
                "", "", BlockPos.ZERO, x, y, false, true, List.of(), List.of(), null, List.of(),
                label, "", TransferNode.DEFAULT_GATE_MIN, TransferNode.DEFAULT_GATE_MAX);
        nodes.put(node.getId(), node);
        return node;
    }

    public TransferNode addJumpOutputNode(String pageId, TransferNode inputNode, int x, int y) {
        if (inputNode == null || inputNode.getNodeType() != TransferNode.NodeType.JUMP_INPUT) return null;
        if (!pages.containsKey(pageId)) pageId = ensureDefaultPage().getId();
        TransferNode node = new TransferNode(UUID.randomUUID().toString(), pageId, TransferNode.NodeType.JUMP_OUTPUT,
                "", "", BlockPos.ZERO, x, y, false, true, List.of(), List.of(), null, List.of(),
                inputNode.getLabel(), inputNode.getId(), TransferNode.DEFAULT_GATE_MIN, TransferNode.DEFAULT_GATE_MAX);
        inputNode.setLinkedNodeId(node.getId());
        nodes.put(node.getId(), node);
        return node;
    }

    public TransferNode addTrashNode(String pageId, int x, int y) {
        if (!pages.containsKey(pageId)) pageId = ensureDefaultPage().getId();
        TransferNode node = new TransferNode(UUID.randomUUID().toString(), pageId, TransferNode.NodeType.TRASH, "", "", BlockPos.ZERO, x, y, false, true, List.of());
        nodes.put(node.getId(), node);
        return node;
    }

    public TransferNode addPlayerInventoryNode(String pageId, UUID playerId, int x, int y) {
        if (!pages.containsKey(pageId)) pageId = ensureDefaultPage().getId();
        for (TransferNode node : nodes.values()) {
            if (node.getNodeType() == TransferNode.NodeType.PLAYER_INVENTORY && Objects.equals(node.getTargetPlayerId(), playerId)) {
                node.setPageId(pageId);
                node.setPosition(x, y);
                return node;
            }
        }
        TransferNode node = new TransferNode(UUID.randomUUID().toString(), pageId, TransferNode.NodeType.PLAYER_INVENTORY,
                "", "", BlockPos.ZERO, x, y, false, true, List.of(), List.of(), playerId, List.of());
        nodes.put(node.getId(), node);
        return node;
    }

    public void removeNode(String nodeId) {
        nodes.remove(nodeId);
        edges.values().removeIf(edge -> edge.getFromNodeId().equals(nodeId) || edge.getToNodeId().equals(nodeId));
    }

    public TransferEdge addEdge(String pageId, String fromNodeId, String fromPortKey, String toNodeId, String toPortKey,
                                boolean rateLimitEnabled, int rateLimitSeconds, int rateLimitItems) {
        TransferNode from = nodes.get(fromNodeId);
        TransferNode to = nodes.get(toNodeId);
        if (from == null || to == null) return null;
        if (!from.getPageId().equals(to.getPageId())) return null;
        pageId = from.getPageId();
        for (TransferEdge edge : edges.values()) {
            if (edge.getFromNodeId().equals(fromNodeId) && edge.getToNodeId().equals(toNodeId)
                    && edge.getFromPortKey().equals(fromPortKey) && edge.getToPortKey().equals(toPortKey)) {
                edge.setRateLimit(rateLimitEnabled, rateLimitSeconds, rateLimitItems);
                edge.setEnabled(true);
                return edge;
            }
        }
        TransferEdge edge = new TransferEdge(UUID.randomUUID().toString(), pageId, fromNodeId, toNodeId, fromPortKey, toPortKey,
                rateLimitEnabled, rateLimitSeconds, rateLimitItems, true);
        edges.put(edge.getId(), edge);
        return edge;
    }

    public void removeEdge(String edgeId) { edges.remove(edgeId); }

    public void removeFilterItem(String nodeId, String itemId) {
        TransferNode node = nodes.get(nodeId);
        if (node == null) return;
        node.removeFilterItem(itemId);
        String port = itemId != null && itemId.startsWith(TransferEdge.FLUID_PREFIX)
                ? itemId
                : TransferEdge.itemPort(itemId);
        edges.values().removeIf(edge -> edge.getFromNodeId().equals(nodeId) && edge.getFromPortKey().equals(port));
    }

    public List<TransferEdge> outgoing(String nodeId) {
        List<TransferEdge> out = new ArrayList<>();
        for (TransferEdge edge : edges.values()) if (edge.getFromNodeId().equals(nodeId)) out.add(edge);
        return out;
    }

    public boolean hasExecutableOutgoing(String nodeId) {
        TransferNode node = nodes.get(nodeId);
        if (node == null || !node.isEnabled()) return false;
        TransferGraphPage page = pages.get(node.getPageId());
        if (page == null || !page.isEnabled()) return false;
        for (TransferEdge edge : edges.values()) {
            if (!edge.getFromNodeId().equals(nodeId) || !edge.isEnabled()) continue;
            TransferNode target = nodes.get(edge.getToNodeId());
            if (target != null && target.isEnabled()) return true;
        }
        return false;
    }

    public boolean hasOutgoing(String nodeId) { return hasExecutableOutgoing(nodeId); }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.put("GraphKey", key.save());
        if (key.kind() == GraphKey.Kind.PRIVATE && key.id() != null) tag.putUUID("Owner", key.id());
        ListTag pageList = new ListTag();
        for (TransferGraphPage page : pages.values()) pageList.add(page.save());
        tag.put("Pages", pageList);
        ListTag nodeList = new ListTag();
        for (TransferNode node : nodes.values()) nodeList.add(node.save());
        tag.put("Nodes", nodeList);
        ListTag edgeList = new ListTag();
        for (TransferEdge edge : edges.values()) edgeList.add(edge.save());
        tag.put("Edges", edgeList);
        return tag;
    }

    public static TransferGraph load(CompoundTag tag) {
        UUID legacyOwner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        TransferGraph graph = new TransferGraph(GraphKey.load(tag, legacyOwner));
        graph.pages.clear();
        ListTag pageList = tag.getList("Pages", Tag.TAG_COMPOUND);
        if (pageList.isEmpty()) graph.ensureDefaultPage();
        for (int i = 0; i < pageList.size(); i++) {
            TransferGraphPage page = TransferGraphPage.load(pageList.getCompound(i));
            graph.pages.put(page.getId(), page);
        }
        String defaultPage = graph.pages.isEmpty()
                ? graph.ensureDefaultPage().getId()
                : graph.pages.values().iterator().next().getId();
        ListTag nodeList = tag.getList("Nodes", Tag.TAG_COMPOUND);
        for (int i = 0; i < nodeList.size(); i++) {
            TransferNode node = TransferNode.load(nodeList.getCompound(i), defaultPage);
            if (node != null) graph.nodes.put(node.getId(), node);
        }
        ListTag edgeList = tag.getList("Edges", Tag.TAG_COMPOUND);
        for (int i = 0; i < edgeList.size(); i++) {
            TransferEdge edge = TransferEdge.load(edgeList.getCompound(i), defaultPage);
            if (graph.nodes.containsKey(edge.getFromNodeId()) && graph.nodes.containsKey(edge.getToNodeId())) {
                graph.edges.put(edge.getId(), edge);
            }
        }
        return graph;
    }
}
