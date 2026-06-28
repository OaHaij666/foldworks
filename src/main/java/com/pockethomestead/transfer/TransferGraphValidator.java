package com.pockethomestead.transfer;

import com.pockethomestead.blockentity.BaseChestBlockEntity;
import com.pockethomestead.blockentity.HomesteadChestAccess;
import com.pockethomestead.offline.OfflineChestSnapshotStorage;
import com.pockethomestead.space.SpacePermission;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.fml.ModList;

import java.util.*;

public final class TransferGraphValidator {
    private TransferGraphValidator() {}

    public enum Severity { ERROR, WARN }
    private enum ResourceLane { ITEM, FLUID, ENERGY, STRESS }

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
            if (!TransferEdge.sameResourceKind(edge.getFromPortKey(), edge.getToPortKey())) {
                issues.add(new Issue(Severity.ERROR, to.getId(), edge.getId(), "连线两端资源类型不一致"));
            }
            if (to.getNodeType() == TransferNode.NodeType.TRASH
                    && (edge.getFromPortKey().startsWith(TransferEdge.ENERGY_PREFIX) || edge.getFromPortKey().startsWith(TransferEdge.STRESS_PREFIX))) {
                issues.add(new Issue(Severity.ERROR, to.getId(), edge.getId(), "销毁节点不能接收电力或应力"));
            }
        }

        for (TransferNode node : nodes.values()) {
            List<TransferEdge> in = incoming.getOrDefault(node.getId(), List.of());
            List<TransferEdge> out = outgoing.getOrDefault(node.getId(), List.of());
            switch (node.getNodeType()) {
                case CHEST -> {
                    if (in.isEmpty() && out.isEmpty()) issues.add(new Issue(Severity.WARN, node.getId(), "", "箱子节点没有连线"));
                }
                case REROUTE -> {
                    if (in.isEmpty()) issues.add(new Issue(Severity.ERROR, node.getId(), "", "中转点不能只有输出，没有输入"));
                    if (out.isEmpty()) issues.add(new Issue(Severity.ERROR, node.getId(), "", "中转点不能只收不出"));
                }
                case TRASH -> {
                    if (!out.isEmpty()) issues.add(new Issue(Severity.ERROR, node.getId(), "", "销毁节点不能有输出"));
                    if (in.isEmpty()) issues.add(new Issue(Severity.WARN, node.getId(), "", "销毁节点没有输入"));
                }
                case PLAYER_INVENTORY -> {
                    if (!out.isEmpty()) issues.add(new Issue(Severity.ERROR, node.getId(), "", "玩家背包节点不能有输出"));
                    if (in.isEmpty()) issues.add(new Issue(Severity.WARN, node.getId(), "", "玩家背包节点没有输入"));
                    if (node.getTargetPlayerId() == null) issues.add(new Issue(Severity.ERROR, node.getId(), "", "玩家背包节点缺少目标玩家"));
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

    public static List<Issue> validateRuntime(TransferGraph graph, MinecraftServer server, UUID owner) {
        return validateRuntime(graph, server, GraphKey.privateGraph(owner));
    }

    public static List<Issue> validateRuntime(TransferGraph graph, MinecraftServer server, GraphKey key) {
        List<Issue> issues = new ArrayList<>();
        if (server == null || key == null || !key.isValid()) return issues;
        boolean createLoaded = ModList.get().isLoaded("create");
        Map<String, Boolean> stressOutgoing = new HashMap<>();
        Map<String, Boolean> stressIncoming = new HashMap<>();
        for (TransferEdge edge : graph.getEdges()) {
            if (TransferEdge.STRESS_SU.equals(edge.getFromPortKey())) {
                stressOutgoing.put(edge.getFromNodeId(), true);
                stressIncoming.put(edge.getToNodeId(), true);
            }
        }
        for (TransferNode node : graph.getNodes()) {
            if (node.getNodeType() == TransferNode.NodeType.PLAYER_INVENTORY) {
                validatePlayerInventoryRuntime(node, server, key, issues);
                continue;
            }
            if (node.getNodeType().isVirtual()) continue;
            ResourceLocation dimLoc = ResourceLocation.tryParse(node.getDimensionKey());
            if (dimLoc == null) {
                issues.add(new Issue(Severity.ERROR, node.getId(), "", "节点维度无效"));
                continue;
            }
            ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, dimLoc));
            if (level == null) {
                validateSnapshotRuntime(node, server, key, createLoaded,
                        stressOutgoing.getOrDefault(node.getId(), false),
                        stressIncoming.getOrDefault(node.getId(), false), issues);
                continue;
            }
            BaseChestBlockEntity be = HomesteadChestAccess.resolve(level.getBlockEntity(node.getPos()));
            if (be == null) {
                validateSnapshotRuntime(node, server, key, createLoaded,
                        stressOutgoing.getOrDefault(node.getId(), false),
                        stressIncoming.getOrDefault(node.getId(), false), issues);
                continue;
            }
            if (!be.getChestId().equals(node.getChestId())) {
                issues.add(new Issue(Severity.ERROR, node.getId(), "", "节点位置上的箱子已变化"));
                continue;
            }
            if (!TransferGraphAccess.chestMatchesGraph(be, key)) {
                issues.add(new Issue(Severity.ERROR, node.getId(), "", "箱子的图层级与当前连线图不一致"));
                continue;
            }
            if (!be.hasNetworkUpgrade()) {
                issues.add(new Issue(Severity.ERROR, node.getId(), "", "箱子缺少网络升级，不能作为可视化传输节点"));
            }
            boolean stressOut = stressOutgoing.getOrDefault(node.getId(), false);
            boolean stressIn = stressIncoming.getOrDefault(node.getId(), false);
            if (stressOut || stressIn) {
                if (!createLoaded) {
                    issues.add(new Issue(Severity.WARN, node.getId(), "", "Create 未安装，应力连线暂不会工作"));
                }
                if (!be.hasStressUpgrade()) {
                    issues.add(new Issue(Severity.WARN, node.getId(), "", "箱子缺少应力升级，应力连线暂不会工作"));
                }
                int inputSides = be.configuredStressInputSides();
                int outputSides = be.configuredStressOutputSides();
                if (inputSides > 1) {
                    issues.add(new Issue(Severity.WARN, node.getId(), "", "同一箱子最多只能配置一个应力输入面"));
                }
                if (outputSides > 1) {
                    issues.add(new Issue(Severity.WARN, node.getId(), "", "同一箱子最多只能配置一个应力输出面"));
                }
                if (stressIn && outputSides != 1) {
                    issues.add(new Issue(Severity.WARN, node.getId(), "", "接收图中应力的箱子需要配置一个应力输出面才会对外供能"));
                }
                if (stressOut && !stressIn && inputSides != 1) {
                    issues.add(new Issue(Severity.WARN, node.getId(), "", "应力源箱子需要配置一个应力输入面才会接收外部动力"));
                }
                Direction inputSide = be.getConfiguredStressInputWorldSide();
                Direction outputSide = be.getConfiguredStressOutputWorldSide();
                if (inputSide != null && outputSide != null && inputSide.getAxis() != outputSide.getAxis()) {
                    issues.add(new Issue(Severity.WARN, node.getId(), "", "同一箱子的应力输入面和输出面必须在同一轴向才会工作"));
                }
            }
        }
        return issues;
    }

    private static void validateSnapshotRuntime(TransferNode node, MinecraftServer server, GraphKey key, boolean createLoaded,
                                                boolean stressOut, boolean stressIn, List<Issue> issues) {
        OfflineChestSnapshotStorage.Snapshot snapshot = OfflineChestSnapshotStorage.get(server)
                .findSnapshot(node.getDimensionKey(), node.getPos(), node.getChestId());
        if (snapshot == null) {
            issues.add(new Issue(Severity.ERROR, node.getId(), "", "节点引用的箱子未加载，且没有可用快照"));
            return;
        }
        if (!snapshot.matchesGraph(key)) {
            issues.add(new Issue(Severity.ERROR, node.getId(), "", "箱子的图层级与当前连线图不一致"));
            return;
        }
        if (!snapshot.hasNetworkUpgrade()) {
            issues.add(new Issue(Severity.ERROR, node.getId(), "", "箱子缺少网络升级，不能作为可视化传输节点"));
            return;
        }
        if (!snapshot.shouldSimulate(server)) {
            issues.add(new Issue(Severity.ERROR, node.getId(), "", "箱子未加载，且未启用离线模拟"));
            return;
        }
        issues.add(new Issue(Severity.WARN, node.getId(), "", "箱子未加载，正在使用离线快照模拟"));
        if (stressOut || stressIn) {
            if (!createLoaded) {
                issues.add(new Issue(Severity.WARN, node.getId(), "", "Create 未安装，应力连线暂不会工作"));
            }
            if (!snapshot.hasStressUpgrade()) {
                issues.add(new Issue(Severity.WARN, node.getId(), "", "箱子缺少应力升级，应力连线暂不会工作"));
            }
            int inputSides = snapshot.configuredStressInputSides();
            int outputSides = snapshot.configuredStressOutputSides();
            if (inputSides > 1) {
                issues.add(new Issue(Severity.WARN, node.getId(), "", "同一箱子最多只能配置一个应力输入面"));
            }
            if (outputSides > 1) {
                issues.add(new Issue(Severity.WARN, node.getId(), "", "同一箱子最多只能配置一个应力输出面"));
            }
            if (stressIn && outputSides != 1) {
                issues.add(new Issue(Severity.WARN, node.getId(), "", "接收图中应力的箱子需要配置一个应力输出面才会对外供能"));
            }
            if (stressOut && !stressIn && inputSides != 1) {
                issues.add(new Issue(Severity.WARN, node.getId(), "", "应力源箱子需要配置一个应力输入面才会接收外部动力"));
            }
        }
    }

    private static void validatePlayerInventoryRuntime(TransferNode node, MinecraftServer server, GraphKey key, List<Issue> issues) {
        if (key.kind() == GraphKey.Kind.PUBLIC) {
            issues.add(new Issue(Severity.ERROR, node.getId(), "", "公开图不能包含玩家背包节点"));
            return;
        }
        UUID target = node.getTargetPlayerId();
        if (target == null) {
            issues.add(new Issue(Severity.ERROR, node.getId(), "", "玩家背包节点缺少目标玩家"));
            return;
        }
        if (key.kind() == GraphKey.Kind.PRIVATE && !target.equals(key.id())) {
            issues.add(new Issue(Severity.ERROR, node.getId(), "", "私有图只能包含自己的背包节点"));
            return;
        }
        if (key.kind() == GraphKey.Kind.PROTECTED) {
            TransferTeam team = TransferTeamStorage.get(server).getTeam(key.id());
            if (team == null || !team.can(target, SpacePermission.AccessLevel.VIEW)) {
                issues.add(new Issue(Severity.ERROR, node.getId(), "", "玩家背包节点目标不在当前团队中"));
                return;
            }
        }
        if (server.getPlayerList().getPlayer(target) == null) {
            issues.add(new Issue(Severity.WARN, node.getId(), "", "目标玩家离线，背包补货暂不工作"));
        }
    }

    private static boolean validDirection(TransferNode from, TransferNode to) {
        if (from.getNodeType() == TransferNode.NodeType.TRASH) return false;
        if (from.getNodeType() == TransferNode.NodeType.PLAYER_INVENTORY) return false;
        return from.getNodeType() == TransferNode.NodeType.CHEST || from.getNodeType() == TransferNode.NodeType.REROUTE;
    }

    private static boolean validPort(TransferNode from, String port) {
        if (from.getNodeType() == TransferNode.NodeType.TRASH) return false;
        if (from.getNodeType() == TransferNode.NodeType.PLAYER_INVENTORY) return false;
        if (from.getNodeType() == TransferNode.NodeType.REROUTE) return isAll(port) || validItemPort(port) || validFluidPort(port) || validEnergyPort(port) || validStressPort(port);
        if (isAll(port) || TransferEdge.FLUID_ALL.equals(port) || TransferEdge.ENERGY_FE.equals(port) || TransferEdge.STRESS_SU.equals(port)) return true;
        if (validFluidPort(port)) return true;
        if (!validItemPort(port)) return false;
        return from.getFilterItemIds().contains(port.substring(TransferEdge.ITEM_PREFIX.length()));
    }

    private static boolean validTargetPort(TransferNode to, String port) {
        if (to.getNodeType() == TransferNode.NodeType.TRASH) {
            return TransferEdge.ITEM_IN.equals(port) || TransferEdge.FLUID_IN.equals(port) || TransferEdge.PORT_IN.equals(port);
        }
        if (to.getNodeType() == TransferNode.NodeType.PLAYER_INVENTORY) {
            return TransferEdge.ITEM_IN.equals(port);
        }
        return (to.getNodeType() == TransferNode.NodeType.CHEST || to.getNodeType() == TransferNode.NodeType.REROUTE)
                && (TransferEdge.PORT_IN.equals(port)
                || TransferEdge.ITEM_IN.equals(port)
                || TransferEdge.FLUID_IN.equals(port)
                || TransferEdge.ENERGY_IN.equals(port)
                || TransferEdge.STRESS_IN.equals(port));
    }

    private static boolean isAll(String port) {
        return TransferEdge.PORT_ALL.equals(port);
    }

    private static boolean validItemPort(String port) {
        if (port == null || !port.startsWith(TransferEdge.ITEM_PREFIX)) return false;
        ResourceLocation id = ResourceLocation.tryParse(port.substring(TransferEdge.ITEM_PREFIX.length()));
        return id != null && BuiltInRegistries.ITEM.get(id) != Items.AIR;
    }

    private static boolean validFluidPort(String port) {
        if (TransferEdge.FLUID_ALL.equals(port)) return true;
        if (port == null || !port.startsWith(TransferEdge.FLUID_PREFIX)) return false;
        ResourceLocation id = ResourceLocation.tryParse(port.substring(TransferEdge.FLUID_PREFIX.length()));
        return id != null && BuiltInRegistries.FLUID.get(id) != Fluids.EMPTY;
    }

    private static boolean validEnergyPort(String port) {
        return TransferEdge.ENERGY_FE.equals(port);
    }

    private static boolean validStressPort(String port) {
        return TransferEdge.STRESS_SU.equals(port);
    }

    private static void detectCycles(Map<String, TransferNode> nodes, Map<String, List<TransferEdge>> outgoing, List<Issue> issues) {
        for (ResourceLane lane : ResourceLane.values()) {
            Set<String> done = new HashSet<>();
            Set<String> stack = new HashSet<>();
            for (String nodeId : nodes.keySet()) visitCycle(nodeId, lane, outgoing, done, stack, issues);
        }
    }

    private static void visitCycle(String nodeId, ResourceLane lane, Map<String, List<TransferEdge>> outgoing, Set<String> done, Set<String> stack, List<Issue> issues) {
        if (done.contains(nodeId)) return;
        if (!stack.add(nodeId)) {
            issues.add(new Issue(Severity.ERROR, nodeId, "", "检测到" + laneLabel(lane) + "环路"));
            return;
        }
        for (TransferEdge edge : outgoing.getOrDefault(nodeId, List.of())) {
            if (resourceLane(edge.getFromPortKey()) == lane) {
                visitCycle(edge.getToNodeId(), lane, outgoing, done, stack, issues);
            }
        }
        stack.remove(nodeId);
        done.add(nodeId);
    }

    private static void validateRerouteItemScopes(Map<String, TransferNode> nodes,
                                                  Map<String, List<TransferEdge>> incoming,
                                                  Map<String, List<TransferEdge>> outgoing,
                                                  List<Issue> issues) {
        Map<String, Set<String>> inputScopes = new HashMap<>();
        for (TransferNode node : nodes.values()) {
            if (node.getNodeType() == TransferNode.NodeType.CHEST) {
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
                if (TransferEdge.PORT_ALL.equals(edge.getFromPortKey()) || TransferEdge.FLUID_ALL.equals(edge.getFromPortKey())) hasAllOut = true;
                if (edge.getFromPortKey().startsWith(TransferEdge.ITEM_PREFIX) || edge.getFromPortKey().startsWith(TransferEdge.FLUID_PREFIX)) {
                    String resource = edge.getFromPortKey();
                    if (!scopeCovers(in, resource)) {
                        issues.add(new Issue(Severity.ERROR, node.getId(), edge.getId(), "中转点输出了未接收的 " + shortResource(resource)));
                    }
                }
            }
            if (in.contains("*") && in.contains(TransferEdge.FLUID_ALL)) continue;
            for (String resource : in) {
                if (!scopeCovers(out, resource)) {
                    issues.add(new Issue(Severity.ERROR, node.getId(), "", "中转点接收了 " + shortResource(resource) + " 但没有输出"));
                }
            }
        }
    }

    private static void addScope(Set<String> scope, String port) {
        addScope(scope, port, Set.of("*", TransferEdge.FLUID_ALL, TransferEdge.ENERGY_FE, TransferEdge.STRESS_SU));
    }

    private static void addScope(Set<String> target, String port, Set<String> allowed) {
        if (TransferEdge.PORT_ALL.equals(port)) {
            if (allowed.contains("*")) target.add("*");
            for (String resource : allowed) if (resource.startsWith(TransferEdge.ITEM_PREFIX)) target.add(resource);
        } else if (TransferEdge.FLUID_ALL.equals(port)) {
            if (allowed.contains(TransferEdge.FLUID_ALL)) target.add(TransferEdge.FLUID_ALL);
            for (String resource : allowed) if (resource.startsWith(TransferEdge.FLUID_PREFIX)) target.add(resource);
        } else if (port != null && port.startsWith(TransferEdge.ITEM_PREFIX)) {
            if (allowed.contains("*") || allowed.contains(port)) target.add(port);
        } else if (port != null && port.startsWith(TransferEdge.FLUID_PREFIX)) {
            if (allowed.contains(TransferEdge.FLUID_ALL) || allowed.contains(port)) target.add(port);
        } else if (TransferEdge.ENERGY_FE.equals(port)) {
            target.add(port);
        } else if (TransferEdge.STRESS_SU.equals(port)) {
            target.add(port);
        }
    }

    private static boolean scopeCovers(Set<String> scope, String resource) {
        if (TransferEdge.PORT_ALL.equals(resource) || "*".equals(resource)) return scope.contains("*");
        if (TransferEdge.FLUID_ALL.equals(resource)) return scope.contains(TransferEdge.FLUID_ALL);
        if (resource != null && resource.startsWith(TransferEdge.ITEM_PREFIX)) return scope.contains("*") || scope.contains(resource);
        if (resource != null && resource.startsWith(TransferEdge.FLUID_PREFIX)) return scope.contains(TransferEdge.FLUID_ALL) || scope.contains(resource);
        if (TransferEdge.ENERGY_FE.equals(resource)) return scope.contains(TransferEdge.ENERGY_FE);
        if (TransferEdge.STRESS_SU.equals(resource)) return scope.contains(TransferEdge.STRESS_SU);
        return false;
    }

    private static ResourceLane resourceLane(String port) {
        if (port != null && port.startsWith(TransferEdge.FLUID_PREFIX)) return ResourceLane.FLUID;
        if (port != null && port.startsWith(TransferEdge.ENERGY_PREFIX)) return ResourceLane.ENERGY;
        if (port != null && port.startsWith(TransferEdge.STRESS_PREFIX)) return ResourceLane.STRESS;
        return ResourceLane.ITEM;
    }

    private static String laneLabel(ResourceLane lane) {
        return switch (lane) {
            case ITEM -> "物品";
            case FLUID -> "流体";
            case ENERGY -> "电力";
            case STRESS -> "应力";
        };
    }

    private static String shortResource(String resourceId) {
        if ("*".equals(resourceId)) return "全部物品";
        if (TransferEdge.FLUID_ALL.equals(resourceId)) return "全部流体";
        if (TransferEdge.ENERGY_FE.equals(resourceId)) return "电力";
        if (TransferEdge.STRESS_SU.equals(resourceId)) return "应力";
        String id = resourceId;
        if (id.startsWith(TransferEdge.ITEM_PREFIX)) id = id.substring(TransferEdge.ITEM_PREFIX.length());
        if (id.startsWith(TransferEdge.FLUID_PREFIX)) id = id.substring(TransferEdge.FLUID_PREFIX.length());
        int slash = id.indexOf(':');
        return slash >= 0 ? id.substring(slash + 1) : id;
    }
}
