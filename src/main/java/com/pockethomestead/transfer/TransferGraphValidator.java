package com.pockethomestead.transfer;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;

import java.util.*;

public final class TransferGraphValidator {
    private TransferGraphValidator() {}

    public enum Severity { ERROR, WARN }

    public record Issue(Severity severity, String nodeId, String edgeId, String message) {}

    public static List<Issue> validate(TransferGraph graph) {
        List<Issue> issues = new ArrayList<>();
        Map<String, TransferNode> nodes = new LinkedHashMap<>();
        for (TransferNode node : graph.getNodes()) nodes.put(node.getId(), node);

        Map<String, List<TransferEdge>> incoming = new HashMap<>();
        Map<String, List<TransferEdge>> outgoing = new HashMap<>();

        for (TransferEdge edge : graph.getEdges()) {
            TransferNode from = nodes.get(edge.getFromNodeId());
            TransferNode to = nodes.get(edge.getToNodeId());
            if (from == null || to == null) {
                issues.add(new Issue(Severity.ERROR, "", edge.getId(), "连线引用了不存在的节点"));
                continue;
            }
            incoming.computeIfAbsent(to.getId(), id -> new ArrayList<>()).add(edge);
            outgoing.computeIfAbsent(from.getId(), id -> new ArrayList<>()).add(edge);

            if (from.getId().equals(to.getId())) {
                issues.add(new Issue(Severity.ERROR, from.getId(), edge.getId(), "节点不能连接到自身"));
            }
            if (!from.getPageId().equals(to.getPageId()) || !edge.getPageId().equals(from.getPageId())) {
                issues.add(new Issue(Severity.ERROR, from.getId(), edge.getId(), "连线两端必须在同一分页"));
            }
            if (!validDirection(from, to)) {
                issues.add(new Issue(Severity.ERROR, from.getId(), edge.getId(), "连线方向不合法"));
            }
            if (!validPort(from, edge.getFromPortKey())) {
                issues.add(new Issue(Severity.ERROR, from.getId(), edge.getId(), "输出端口无效"));
            }
            if (!validTargetPort(to, edge.getToPortKey())) {
                issues.add(new Issue(Severity.ERROR, to.getId(), edge.getId(), "输入端口无效"));
            }
        }

        for (TransferNode node : nodes.values()) {
            List<TransferEdge> in = incoming.getOrDefault(node.getId(), List.of());
            List<TransferEdge> out = outgoing.getOrDefault(node.getId(), List.of());
            switch (node.getNodeType()) {
                case SUPPLY -> {
                    if (!in.isEmpty()) issues.add(new Issue(Severity.ERROR, node.getId(), "", "供货箱不能有输入"));
                }
                case PICKUP -> {
                    if (!out.isEmpty()) issues.add(new Issue(Severity.ERROR, node.getId(), "", "取货箱不能有输出"));
                    if (in.isEmpty()) issues.add(new Issue(Severity.WARN, node.getId(), "", "取货箱没有输入"));
                }
                case REROUTE -> {
                    if (in.isEmpty()) issues.add(new Issue(Severity.ERROR, node.getId(), "", "中转点不能只有输出，没有输入"));
                    if (out.isEmpty()) issues.add(new Issue(Severity.ERROR, node.getId(), "", "中转点不能只收不出"));
                }
                case TRASH -> {
                    if (!out.isEmpty()) issues.add(new Issue(Severity.ERROR, node.getId(), "", "销毁节点不能有输出"));
                    if (in.isEmpty()) issues.add(new Issue(Severity.WARN, node.getId(), "", "销毁节点没有输入"));
                }
            }
        }

        detectCycles(nodes, outgoing, issues);
        validateRerouteItemScopes(nodes, incoming, outgoing, issues);
        return issues;
    }

    public static boolean hasErrors(List<Issue> issues) {
        for (Issue issue : issues) if (issue.severity() == Severity.ERROR) return true;
        return false;
    }

    private static boolean validDirection(TransferNode from, TransferNode to) {
        if (from.getNodeType() == TransferNode.NodeType.PICKUP || from.getNodeType() == TransferNode.NodeType.TRASH) return false;
        if (to.getNodeType() == TransferNode.NodeType.SUPPLY) return false;
        return from.getNodeType() == TransferNode.NodeType.SUPPLY || from.getNodeType() == TransferNode.NodeType.REROUTE;
    }

    private static boolean validPort(TransferNode from, String port) {
        if (from.getNodeType() == TransferNode.NodeType.REROUTE) return isAll(port) || validItemPort(port);
        if (isAll(port)) return true;
        if (!validItemPort(port)) return false;
        return from.getFilterItemIds().contains(port.substring(TransferEdge.ITEM_PREFIX.length()));
    }

    private static boolean validTargetPort(TransferNode to, String port) {
        return (to.getNodeType() == TransferNode.NodeType.PICKUP || to.getNodeType() == TransferNode.NodeType.REROUTE || to.getNodeType() == TransferNode.NodeType.TRASH)
                && TransferEdge.PORT_IN.equals(port);
    }

    private static boolean isAll(String port) {
        return TransferEdge.PORT_ALL.equals(port);
    }

    private static boolean validItemPort(String port) {
        if (port == null || !port.startsWith(TransferEdge.ITEM_PREFIX)) return false;
        ResourceLocation id = ResourceLocation.tryParse(port.substring(TransferEdge.ITEM_PREFIX.length()));
        return id != null && BuiltInRegistries.ITEM.get(id) != Items.AIR;
    }

    private static void detectCycles(Map<String, TransferNode> nodes, Map<String, List<TransferEdge>> outgoing, List<Issue> issues) {
        Set<String> done = new HashSet<>();
        Set<String> stack = new HashSet<>();
        for (String nodeId : nodes.keySet()) visitCycle(nodeId, outgoing, done, stack, issues);
    }

    private static void visitCycle(String nodeId, Map<String, List<TransferEdge>> outgoing, Set<String> done, Set<String> stack, List<Issue> issues) {
        if (done.contains(nodeId)) return;
        if (!stack.add(nodeId)) {
            issues.add(new Issue(Severity.ERROR, nodeId, "", "检测到中转环路"));
            return;
        }
        for (TransferEdge edge : outgoing.getOrDefault(nodeId, List.of())) visitCycle(edge.getToNodeId(), outgoing, done, stack, issues);
        stack.remove(nodeId);
        done.add(nodeId);
    }

    private static void validateRerouteItemScopes(Map<String, TransferNode> nodes,
                                                  Map<String, List<TransferEdge>> incoming,
                                                  Map<String, List<TransferEdge>> outgoing,
                                                  List<Issue> issues) {
        Map<String, Set<String>> inputScopes = new HashMap<>();
        for (TransferNode node : nodes.values()) {
            if (node.getNodeType() == TransferNode.NodeType.SUPPLY) {
                for (TransferEdge edge : outgoing.getOrDefault(node.getId(), List.of())) {
                    addScope(inputScopes.computeIfAbsent(edge.getToNodeId(), id -> new LinkedHashSet<>()), edge.getFromPortKey());
                }
            }
        }

        boolean changed;
        do {
            changed = false;
            for (TransferNode node : nodes.values()) {
                if (node.getNodeType() != TransferNode.NodeType.REROUTE) continue;
                Set<String> scope = inputScopes.getOrDefault(node.getId(), Set.of());
                for (TransferEdge out : outgoing.getOrDefault(node.getId(), List.of())) {
                    Set<String> target = inputScopes.computeIfAbsent(out.getToNodeId(), id -> new LinkedHashSet<>());
                    int before = target.size();
                    addScope(target, out.getFromPortKey(), scope);
                    changed |= target.size() != before;
                }
            }
        } while (changed);

        for (TransferNode node : nodes.values()) {
            if (node.getNodeType() != TransferNode.NodeType.REROUTE) continue;
            Set<String> in = inputScopes.getOrDefault(node.getId(), Set.of());
            Set<String> out = new LinkedHashSet<>();
            for (TransferEdge edge : outgoing.getOrDefault(node.getId(), List.of())) addScope(out, edge.getFromPortKey(), in);
            boolean hasAllOut = false;
            for (TransferEdge edge : outgoing.getOrDefault(node.getId(), List.of())) {
                if (TransferEdge.PORT_ALL.equals(edge.getFromPortKey())) hasAllOut = true;
                if (edge.getFromPortKey().startsWith(TransferEdge.ITEM_PREFIX)) {
                    String item = edge.getFromPortKey().substring(TransferEdge.ITEM_PREFIX.length());
                    if (!in.contains("*") && !in.contains(item)) {
                        issues.add(new Issue(Severity.ERROR, node.getId(), edge.getId(), "中转点输出了未接收的 " + shortItem(item)));
                    }
                }
            }
            if (in.contains("*")) continue;
            for (String item : in) {
                if (!out.contains(item) && !out.contains("*")) {
                    issues.add(new Issue(Severity.ERROR, node.getId(), "", "中转点接收了 " + shortItem(item) + " 但没有输出"));
                }
            }
        }
    }

    private static void addScope(Set<String> scope, String port) {
        addScope(scope, port, Set.of("*"));
    }

    private static void addScope(Set<String> target, String port, Set<String> allowed) {
        if (TransferEdge.PORT_ALL.equals(port)) {
            target.addAll(allowed);
        } else if (port != null && port.startsWith(TransferEdge.ITEM_PREFIX)) {
            String item = port.substring(TransferEdge.ITEM_PREFIX.length());
            if (allowed.contains("*") || allowed.contains(item)) target.add(item);
        }
    }

    private static String shortItem(String itemId) {
        int slash = itemId.indexOf(':');
        return slash >= 0 ? itemId.substring(slash + 1) : itemId;
    }
}
