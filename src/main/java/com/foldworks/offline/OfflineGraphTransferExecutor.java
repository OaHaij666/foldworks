package com.foldworks.offline;

import com.foldworks.blockentity.BaseChestBlockEntity;
import com.foldworks.blockentity.FoldworksChestAccess;
import com.foldworks.blockentity.FoldworksStressEndpoint;
import com.foldworks.blockentity.StoredItemStack;
import com.foldworks.config.ModConfig;
import com.foldworks.moving.MovingChestRegistry;
import com.foldworks.transfer.GraphKey;
import com.foldworks.transfer.TransferEdge;
import com.foldworks.transfer.TransferGraph;
import com.foldworks.transfer.TransferGraphAccess;
import com.foldworks.transfer.TransferGraphPage;
import com.foldworks.transfer.TransferGraphStorage;
import com.foldworks.transfer.TransferNode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class OfflineGraphTransferExecutor {
    private enum TransferBlockReason { NONE, SOURCE, RECEIVER }

    private record TransferResult(Map<String, Integer> movedByResource, TransferBlockReason reason, String blockResourceId) {
        private int moved() {
            return movedByResource.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    private static final class BandwidthBudget {
        private final int total;
        private int used;

        private BandwidthBudget(int total, int reserved) {
            this.total = Math.max(0, total);
            this.used = Math.max(0, Math.min(this.total, reserved));
        }

        private int remaining() {
            return Math.max(0, total - used);
        }

        private int used() {
            return used;
        }

        private int maxTransferable(String scopePort) {
            return maxAmountForBandwidth(scopePort, remaining());
        }

        private void consume(String scopePort, int amount) {
            int cost = bandwidthCost(scopePort, amount);
            if (cost <= 0) return;
            long next = (long) used + cost;
            used = (int) Math.min(Integer.MAX_VALUE, Math.min(total, next));
        }
    }

    private record Context(MinecraftServer server, OfflineChestSnapshotStorage storage,
                           OfflineChestSnapshotStorage.Snapshot source, TransferGraph graph, long gameTime) {}

    private OfflineGraphTransferExecutor() {
    }

    public static void run(MinecraftServer server, OfflineChestSnapshotStorage storage,
                           OfflineChestSnapshotStorage.Snapshot source, long gameTime) {
        if (server == null || storage == null || source == null || source.loaded() || !source.hasNetworkUpgrade()) return;
        GraphKey key = source.graphKey();
        if (key == null || !key.isValid() || !source.shouldSimulate(server)) return;
        TransferGraph graph = TransferGraphStorage.get(server).getGraph(key);
        if (graph == null) return;
        TransferNode sourceNode = graph.findNode(source.chestId(), source.dimensionKey(), source.pos());
        if (sourceNode == null || !sourceNode.isEnabled()) return;
        TransferGraphPage page = graph.getPage(sourceNode.getPageId());
        if (page == null || !page.isEnabled()) return;

        Context ctx = new Context(server, storage, source, graph, gameTime);
        // 源节点加入 visitedNodes 防止环路图 ping-pong（A→B→A 往返消耗带宽但净效果为零）
        BandwidthBudget stressBandwidth = new BandwidthBudget(source.networkBandwidth(), 0);
        if (source.canSendGraphStress(gameTime) && stressBandwidth.remaining() > 0) {
            Set<String> visited = new HashSet<>();
            visited.add(sourceNode.getId());
            followOutgoingEdges(ctx, sourceNode.getPageId(), sourceNode.getId(), TransferEdge.STRESS_SU,
                    new ArrayList<>(), visited, false,
                    Math.min(source.stressTransferLimit(), Math.max(0, (int) source.graphStressCapacity(gameTime))),
                    stressBandwidth);
        }
        source.recordGraphStressBandwidthUse(gameTime, stressBandwidth.used());

        BandwidthBudget normalBandwidth = new BandwidthBudget(source.networkBandwidth(), stressBandwidth.used());
        if (normalBandwidth.remaining() <= 0) return;
        boolean canVoid = ModConfig.VOID_ENABLED.get() && (source.voidModeEnabled() || ModConfig.GLOBAL_VOID_MODE.get());
        List<String> scopes = new ArrayList<>(3);
        if (!source.items().isEmpty()) scopes.add(TransferEdge.ITEM_ALL);
        if (!source.fluids().isEmpty()) scopes.add(TransferEdge.FLUID_ALL);
        if (source.energyStored() > 0) scopes.add(TransferEdge.ENERGY_FE);
        for (String scope : scopes) {
            if (normalBandwidth.remaining() <= 0) break;
            if (normalBandwidth.maxTransferable(scope) <= 0) continue;
            Set<String> visited = new HashSet<>();
            visited.add(sourceNode.getId());
            followOutgoingEdges(ctx, sourceNode.getPageId(), sourceNode.getId(), scope,
                    new ArrayList<>(), visited, canVoid && !isEnergyScope(scope),
                    Integer.MAX_VALUE, normalBandwidth);
        }
    }

    private static int followOutgoingEdges(Context ctx, String pageId, String nodeId, String scopePort,
                                           List<TransferEdge> path, Set<String> visitedNodes, boolean canVoid,
                                           int routeBudget, BandwidthBudget bandwidth) {
        if (routeBudget <= 0 || bandwidth.remaining() <= 0) return 0;
        List<TransferEdge> primaryEdges = outgoingForScope(ctx.graph(), nodeId, scopePort, false);
        List<TransferEdge> trashEdges = outgoingForScope(ctx.graph(), nodeId, scopePort, true);
        int moved = followOutgoingPhase(ctx, pageId, nodeId, scopePort, primaryEdges, path, visitedNodes,
                canVoid && trashEdges.isEmpty(), routeBudget, bandwidth);
        if (moved < routeBudget && bandwidth.remaining() > 0) {
            moved += followOutgoingPhase(ctx, pageId, nodeId, scopePort, trashEdges, path, visitedNodes,
                    false, routeBudget - moved, bandwidth);
        }
        return moved;
    }

    private static int followOutgoingPhase(Context ctx, String pageId, String nodeId, String scopePort,
                                           List<TransferEdge> edges, List<TransferEdge> path, Set<String> visitedNodes,
                                           boolean canVoid, int routeBudget, BandwidthBudget bandwidth) {
        int moved = 0;
        for (int i = 0; i < edges.size() && moved < routeBudget && bandwidth.remaining() > 0; i++) {
            TransferEdge next = edges.get(i);
            String nextScope = intersectPorts(scopePort, next.getFromPortKey());
            if (nextScope == null) continue;
            int remainingBudget = Math.min(routeBudget - moved, bandwidth.maxTransferable(nextScope));
            int branchBudget = fairBranchBudget(ctx.source(), nextScope, edges.subList(i, edges.size()), remainingBudget, ctx.gameTime());
            // 回溯模式：直接传入 visitedNodes 与 path，由 followTransferEdge 在递归前 add、递归后 remove。
            // 不再每条边复制 HashSet，避免深图 O(n²) 分配压力。
            int branchMoved = followTransferEdge(ctx, pageId, next, nextScope, path, visitedNodes, canVoid, branchBudget, bandwidth);
            moved += branchMoved;
        }
        return moved;
    }

    private static int followTransferEdge(Context ctx, String pageId, TransferEdge edge, String scopePort,
                                          List<TransferEdge> path, Set<String> visitedNodes, boolean canVoid,
                                          int routeBudget, BandwidthBudget bandwidth) {
        if (routeBudget <= 0 || bandwidth.remaining() <= 0) return 0;
        if (!edge.isEnabled() || !edge.getPageId().equals(pageId)) return 0;

        scopePort = intersectPorts(scopePort, edge.getFromPortKey());
        if (scopePort == null) return 0;

        TransferNode targetNode = ctx.graph().getNode(edge.getToNodeId());
        if (targetNode == null || !targetNode.isEnabled()) return 0;
        if (!visitedNodes.add(targetNode.getId())) return 0;

        // 回溯模式：递归前 path.add、递归后 finally 移除；不再每层复制 path。
        // recordTransferResult 在 finally 前调用，此时 path 已包含完整路径。
        path.add(edge);
        try {
            if (targetNode.getNodeType() == TransferNode.NodeType.LIMIT_GATE) {
                int gateBudget = limitGateBudget(ctx, pageId, targetNode, scopePort, routeBudget);
                if (gateBudget <= 0) {
                    recordTransferResult(ctx, path, new TransferResult(Map.of(), TransferBlockReason.RECEIVER,
                            firstMatchingResourceId(ctx.source(), scopePort, ctx.gameTime())));
                    return 0;
                }
                return followOutgoingEdges(ctx, pageId, targetNode.getId(), scopePort, path, visitedNodes, canVoid, gateBudget, bandwidth);
            }

            if (targetNode.getNodeType() == TransferNode.NodeType.JUMP_INPUT) {
                TransferNode output = linkedJumpOutput(ctx.graph(), targetNode);
                if (output == null || !output.isEnabled() || !visitedNodes.add(output.getId())) return 0;
                try {
                    return followOutgoingEdges(ctx, pageId, output.getId(), scopePort, path, visitedNodes, canVoid, routeBudget, bandwidth);
                } finally {
                    visitedNodes.remove(output.getId());
                }
            }

            if (targetNode.getNodeType() == TransferNode.NodeType.REROUTE) {
                return followOutgoingEdges(ctx, pageId, targetNode.getId(), scopePort, path, visitedNodes, canVoid, routeBudget, bandwidth);
            }

            TransferResult result;
            if (targetNode.getNodeType() == TransferNode.NodeType.TRASH) {
                if (isEnergyScope(scopePort) || isStressScope(scopePort)) return 0;
                result = isFluidScope(scopePort)
                        ? voidFilteredFluids(ctx, scopePort, path, routeBudget, bandwidth)
                        : voidFilteredItems(ctx, scopePort, path, routeBudget, bandwidth);
            } else if (targetNode.getNodeType() == TransferNode.NodeType.PLAYER_INVENTORY) {
                result = isFluidScope(scopePort) || isEnergyScope(scopePort) || isStressScope(scopePort)
                        ? new TransferResult(Map.of(), TransferBlockReason.RECEIVER, firstMatchingResourceId(ctx.source(), scopePort, ctx.gameTime()))
                        : transferItemsToPlayer(ctx, targetNode, scopePort, path, routeBudget, bandwidth);
            } else if (targetNode.getNodeType() == TransferNode.NodeType.CHEST) {
                BaseChestBlockEntity loadedTarget = loadedChest(ctx.server(), targetNode);
                if (loadedTarget != null && !loadedTarget.isRemoved() && TransferGraphAccess.chestMatchesGraph(loadedTarget, ctx.graph().getKey())) {
                    if (isEnergyScope(scopePort)) result = transferEnergyToLoaded(ctx, loadedTarget, path, routeBudget, bandwidth);
                    else if (isStressScope(scopePort)) result = transferStressToLoaded(ctx, loadedTarget, path, routeBudget, bandwidth);
                    else result = isFluidScope(scopePort)
                            ? transferFluidsToLoaded(ctx, loadedTarget, targetNode, scopePort, path, routeBudget, canVoid, bandwidth)
                            : transferItemsToLoaded(ctx, loadedTarget, targetNode, scopePort, path, routeBudget, canVoid, bandwidth);
                } else if (snapshotTarget(ctx, targetNode) instanceof OfflineChestSnapshotStorage.Snapshot snapshot) {
                    if (isEnergyScope(scopePort)) result = transferEnergyToSnapshot(ctx, snapshot, path, routeBudget, bandwidth);
                    else if (isStressScope(scopePort)) result = transferStressToSnapshot(ctx, snapshot, path, routeBudget, bandwidth);
                    else result = isFluidScope(scopePort)
                            ? transferFluidsToSnapshot(ctx, snapshot, targetNode, scopePort, path, routeBudget, canVoid, bandwidth)
                            : transferItemsToSnapshot(ctx, snapshot, targetNode, scopePort, path, routeBudget, canVoid, bandwidth);
                } else if (canVoid) {
                    result = isFluidScope(scopePort)
                            ? voidFilteredFluids(ctx, scopePort, path, routeBudget, bandwidth)
                            : voidFilteredItems(ctx, scopePort, path, routeBudget, bandwidth);
                } else {
                    result = new TransferResult(Map.of(), TransferBlockReason.RECEIVER, firstMatchingResourceId(ctx.source(), scopePort, ctx.gameTime()));
                }
            } else {
                return 0;
            }

            recordTransferResult(ctx, path, result);
            return result.moved();
        } finally {
            path.remove(path.size() - 1);
            visitedNodes.remove(targetNode.getId());
        }
    }

    private static TransferResult transferItemsToLoaded(Context ctx, BaseChestBlockEntity target, TransferNode targetNode,
                                                        String scopePort, List<TransferEdge> path, int routeBudget,
                                                        boolean canVoid, BandwidthBudget bandwidth) {
        List<StoredItemStack> entries = ctx.source().items();
        int remainingRouteBudget = routeBudget;
        Map<String, Integer> movedByItem = new LinkedHashMap<>();
        boolean matchedSourceItem = false;
        boolean rateLimitedOnly = false;
        TransferBlockReason blockReason = TransferBlockReason.NONE;
        String blockItemId = null;
        if (target.getRemainingCapacity() <= 0) {
            return canVoid ? voidFilteredItems(ctx, scopePort, path, routeBudget, bandwidth)
                    : new TransferResult(Map.of(), TransferBlockReason.RECEIVER, firstMatchingItemId(ctx.source(), scopePort));
        }
        for (StoredItemStack entry : entries) {
            Item item = entry.item();
            int count = entry.count();
            if (count <= 0 || !portAllows(scopePort, item)) continue;
            matchedSourceItem = true;
            String itemId = itemId(item);
            if (itemId == null || !receiveFilterAllows(targetNode, itemId)) continue;
            int itemBudget = Math.min(pathBudget(path, ctx.gameTime(), remainingRouteBudget, itemId), bandwidth.maxTransferable(itemId));
            if (itemBudget <= 0) {
                rateLimitedOnly = true;
                continue;
            }
            int toTransfer = Math.min(Math.min(count, itemBudget), target.getRemainingCapacity());
            if (toTransfer <= 0) continue;
            ItemStack moving = entry.prototype();
            int removed = ctx.source().removeItem(moving, toTransfer);
            if (removed <= 0) continue;
            int accepted = target.addItemFromGraph(moving, removed);
            if (accepted < removed) ctx.source().addItem(moving, removed - accepted);
            if (accepted <= 0) continue;
            ctx.storage().recordSnapshotGraphOutput(ctx.server(), ctx.source(), itemId, accepted, ctx.gameTime());
            target.recordGraphProductionInput(item, accepted);
            bandwidth.consume(itemId, accepted);
            movedByItem.merge(itemId, accepted, Integer::sum);
            remainingRouteBudget -= accepted;
            if (target.getRemainingCapacity() <= 0) {
                if (canVoid && remainingRouteBudget > 0 && bandwidth.remaining() > 0) {
                    TransferResult voided = voidFilteredItems(ctx, scopePort, path, remainingRouteBudget, bandwidth);
                    mergeMoved(movedByItem, voided.movedByResource());
                    remainingRouteBudget -= voided.moved();
                    blockReason = voided.reason();
                    blockItemId = voided.blockResourceId();
                } else if (remainingRouteBudget > 0) {
                    blockReason = TransferBlockReason.RECEIVER;
                    blockItemId = itemId;
                }
                break;
            }
            if (remainingRouteBudget <= 0 || bandwidth.remaining() <= 0) break;
        }
        return finishItemResult(ctx.source(), scopePort, movedByItem, matchedSourceItem, rateLimitedOnly, remainingRouteBudget, bandwidth, blockReason, blockItemId);
    }

    private static TransferResult transferItemsToSnapshot(Context ctx, OfflineChestSnapshotStorage.Snapshot target, TransferNode targetNode,
                                                          String scopePort, List<TransferEdge> path, int routeBudget,
                                                          boolean canVoid, BandwidthBudget bandwidth) {
        List<StoredItemStack> entries = ctx.source().items();
        int remainingRouteBudget = routeBudget;
        Map<String, Integer> movedByItem = new LinkedHashMap<>();
        boolean matchedSourceItem = false;
        boolean rateLimitedOnly = false;
        TransferBlockReason blockReason = TransferBlockReason.NONE;
        String blockItemId = null;
        if (target.remainingItemCapacity() <= 0) {
            return canVoid ? voidFilteredItems(ctx, scopePort, path, routeBudget, bandwidth)
                    : new TransferResult(Map.of(), TransferBlockReason.RECEIVER, firstMatchingItemId(ctx.source(), scopePort));
        }
        for (StoredItemStack entry : entries) {
            Item item = entry.item();
            int count = entry.count();
            if (count <= 0 || !portAllows(scopePort, item)) continue;
            matchedSourceItem = true;
            String itemId = itemId(item);
            if (itemId == null || !receiveFilterAllows(targetNode, itemId)) continue;
            int itemBudget = Math.min(pathBudget(path, ctx.gameTime(), remainingRouteBudget, itemId), bandwidth.maxTransferable(itemId));
            if (itemBudget <= 0) {
                rateLimitedOnly = true;
                continue;
            }
            int toTransfer = Math.min(Math.min(count, itemBudget), target.remainingItemCapacity());
            if (toTransfer <= 0) continue;
            ItemStack moving = entry.prototype();
            int removed = ctx.source().removeItem(moving, toTransfer);
            if (removed <= 0) continue;
            int accepted = target.addItem(moving, removed);
            if (accepted < removed) ctx.source().addItem(moving, removed - accepted);
            if (accepted <= 0) continue;
            ctx.storage().recordSnapshotGraphOutput(ctx.server(), ctx.source(), itemId, accepted, ctx.gameTime());
            ctx.storage().recordSnapshotGraphInput(ctx.server(), target, itemId, accepted, ctx.gameTime());
            bandwidth.consume(itemId, accepted);
            movedByItem.merge(itemId, accepted, Integer::sum);
            remainingRouteBudget -= accepted;
            if (target.remainingItemCapacity() <= 0) {
                if (canVoid && remainingRouteBudget > 0 && bandwidth.remaining() > 0) {
                    TransferResult voided = voidFilteredItems(ctx, scopePort, path, remainingRouteBudget, bandwidth);
                    mergeMoved(movedByItem, voided.movedByResource());
                    remainingRouteBudget -= voided.moved();
                    blockReason = voided.reason();
                    blockItemId = voided.blockResourceId();
                } else if (remainingRouteBudget > 0) {
                    blockReason = TransferBlockReason.RECEIVER;
                    blockItemId = itemId;
                }
                break;
            }
            if (remainingRouteBudget <= 0 || bandwidth.remaining() <= 0) break;
        }
        return finishItemResult(ctx.source(), scopePort, movedByItem, matchedSourceItem, rateLimitedOnly, remainingRouteBudget, bandwidth, blockReason, blockItemId);
    }

    private static TransferResult transferItemsToPlayer(Context ctx, TransferNode targetNode, String scopePort,
                                                        List<TransferEdge> path, int routeBudget, BandwidthBudget bandwidth) {
        if (targetNode.getTargetPlayerId() == null) {
            return new TransferResult(Map.of(), TransferBlockReason.RECEIVER, firstMatchingItemId(ctx.source(), scopePort));
        }
        ServerPlayer targetPlayer = ctx.server().getPlayerList().getPlayer(targetNode.getTargetPlayerId());
        if (targetPlayer == null) {
            return new TransferResult(Map.of(), TransferBlockReason.RECEIVER, firstMatchingItemId(ctx.source(), scopePort));
        }
        List<StoredItemStack> entries = ctx.source().items();
        int remainingRouteBudget = routeBudget;
        Map<String, Integer> movedByItem = new LinkedHashMap<>();
        boolean matchedSourceItem = false;
        boolean rateLimitedOnly = false;
        boolean movedAny = false;
        for (StoredItemStack entry : entries) {
            Item item = entry.item();
            int count = entry.count();
            if (count <= 0 || !portAllows(scopePort, item)) continue;
            matchedSourceItem = true;
            String itemId = itemId(item);
            String bareItemId = bareItemId(item);
            if (itemId == null || bareItemId == null || !receiveFilterAllows(targetNode, itemId)) continue;
            ItemStack prototype = entry.prototype();
            int targetCount = targetNode.targetCountFor(bareItemId, prototype.getMaxStackSize());
            if (targetCount <= 0) continue;
            int current = countPlayerMainInventory(targetPlayer, item);
            int missing = Math.max(0, targetCount - current);
            if (missing <= 0) continue;
            int itemBudget = Math.min(pathBudget(path, ctx.gameTime(), remainingRouteBudget, itemId), bandwidth.maxTransferable(itemId));
            if (itemBudget <= 0) {
                rateLimitedOnly = true;
                continue;
            }
            int toTransfer = Math.min(Math.min(count, missing), itemBudget);
            toTransfer = Math.min(toTransfer, playerMainInventoryRoom(targetPlayer, prototype));
            if (toTransfer <= 0) continue;
            int removed = ctx.source().removeItem(prototype, toTransfer);
            if (removed <= 0) continue;
            int accepted = insertIntoPlayerMainInventory(targetPlayer, prototype, removed);
            if (accepted < removed) ctx.source().addItem(prototype, removed - accepted);
            if (accepted <= 0) continue;
            ctx.storage().recordSnapshotGraphOutput(ctx.server(), ctx.source(), itemId, accepted, ctx.gameTime());
            bandwidth.consume(itemId, accepted);
            movedByItem.merge(itemId, accepted, Integer::sum);
            remainingRouteBudget -= accepted;
            movedAny = true;
            if (remainingRouteBudget <= 0 || bandwidth.remaining() <= 0) break;
        }
        // 广播移到循环外：N 个物品只触发一次全量背包广播，避免 N 次扫描整个背包
        if (movedAny) {
            targetPlayer.containerMenu.broadcastChanges();
            targetPlayer.inventoryMenu.broadcastChanges();
        }
        if (movedByItem.isEmpty()) {
            if (rateLimitedOnly) return new TransferResult(Map.of(), TransferBlockReason.NONE, null);
            return new TransferResult(Map.of(), matchedSourceItem ? TransferBlockReason.RECEIVER : TransferBlockReason.SOURCE,
                    matchedSourceItem ? firstMatchingItemId(ctx.source(), scopePort) : scopedItemId(scopePort));
        }
        if (remainingRouteBudget <= 0 || bandwidth.remaining() <= 0) return new TransferResult(movedByItem, TransferBlockReason.NONE, null);
        return new TransferResult(movedByItem, TransferBlockReason.SOURCE, lastMovedResourceId(movedByItem));
    }

    private static TransferResult transferFluidsToLoaded(Context ctx, BaseChestBlockEntity target, TransferNode targetNode,
                                                         String scopePort, List<TransferEdge> path, int routeBudget,
                                                         boolean canVoid, BandwidthBudget bandwidth) {
        List<Map.Entry<String, Integer>> entries = fluidEntries(ctx.source());
        int remainingRouteBudget = routeBudget;
        Map<String, Integer> movedByFluid = new LinkedHashMap<>();
        boolean matchedSourceFluid = false;
        boolean rateLimitedOnly = false;
        TransferBlockReason blockReason = TransferBlockReason.NONE;
        String blockFluidId = null;
        if (target.getRemainingFluidCapacityMb() <= 0) {
            return canVoid ? voidFilteredFluids(ctx, scopePort, path, routeBudget, bandwidth)
                    : new TransferResult(Map.of(), TransferBlockReason.RECEIVER, firstMatchingFluidId(ctx.source(), scopePort));
        }
        for (Map.Entry<String, Integer> entry : entries) {
            Fluid fluid = resolveFluid(entry.getKey());
            int amount = entry.getValue();
            if (fluid == Fluids.EMPTY || amount <= 0 || !portAllowsFluid(scopePort, fluid)) continue;
            matchedSourceFluid = true;
            String fluidId = TransferEdge.FLUID_PREFIX + entry.getKey();
            if (!receiveFilterAllows(targetNode, fluidId)) continue;
            int fluidBudget = Math.min(pathBudget(path, ctx.gameTime(), remainingRouteBudget, fluidId), bandwidth.maxTransferable(fluidId));
            if (fluidBudget <= 0) {
                rateLimitedOnly = true;
                continue;
            }
            int toTransfer = Math.min(Math.min(amount, fluidBudget), target.getRemainingFluidCapacityMb(fluid));
            if (toTransfer <= 0) continue;
            int removed = ctx.source().removeFluid(entry.getKey(), toTransfer);
            if (removed <= 0) continue;
            int accepted = target.addFluidFromGraph(fluid, removed);
            if (accepted < removed) ctx.source().addFluid(entry.getKey(), removed - accepted);
            if (accepted <= 0) continue;
            ctx.storage().recordSnapshotGraphOutput(ctx.server(), ctx.source(), fluidId, accepted, ctx.gameTime());
            target.recordGraphProductionFluidInput(fluid, accepted);
            bandwidth.consume(fluidId, accepted);
            movedByFluid.merge(fluidId, accepted, Integer::sum);
            remainingRouteBudget -= accepted;
            if (target.getRemainingFluidCapacityMb(fluid) <= 0) {
                if (canVoid && remainingRouteBudget > 0 && bandwidth.remaining() > 0) {
                    TransferResult voided = voidFilteredFluids(ctx, scopePort, path, remainingRouteBudget, bandwidth);
                    mergeMoved(movedByFluid, voided.movedByResource());
                    remainingRouteBudget -= voided.moved();
                    blockReason = voided.reason();
                    blockFluidId = voided.blockResourceId();
                } else if (remainingRouteBudget > 0) {
                    blockReason = TransferBlockReason.RECEIVER;
                    blockFluidId = fluidId;
                }
                break;
            }
            if (remainingRouteBudget <= 0 || bandwidth.remaining() <= 0) break;
        }
        return finishFluidResult(ctx.source(), scopePort, movedByFluid, matchedSourceFluid, rateLimitedOnly, remainingRouteBudget, bandwidth, blockReason, blockFluidId);
    }

    private static TransferResult transferFluidsToSnapshot(Context ctx, OfflineChestSnapshotStorage.Snapshot target, TransferNode targetNode,
                                                           String scopePort, List<TransferEdge> path, int routeBudget,
                                                           boolean canVoid, BandwidthBudget bandwidth) {
        List<Map.Entry<String, Integer>> entries = fluidEntries(ctx.source());
        int remainingRouteBudget = routeBudget;
        Map<String, Integer> movedByFluid = new LinkedHashMap<>();
        boolean matchedSourceFluid = false;
        boolean rateLimitedOnly = false;
        TransferBlockReason blockReason = TransferBlockReason.NONE;
        String blockFluidId = null;
        for (Map.Entry<String, Integer> entry : entries) {
            Fluid fluid = resolveFluid(entry.getKey());
            int amount = entry.getValue();
            if (fluid == Fluids.EMPTY || amount <= 0 || !portAllowsFluid(scopePort, fluid)) continue;
            matchedSourceFluid = true;
            String fluidId = TransferEdge.FLUID_PREFIX + entry.getKey();
            if (!receiveFilterAllows(targetNode, fluidId)) continue;
            int fluidBudget = Math.min(pathBudget(path, ctx.gameTime(), remainingRouteBudget, fluidId), bandwidth.maxTransferable(fluidId));
            if (fluidBudget <= 0) {
                rateLimitedOnly = true;
                continue;
            }
            int toTransfer = Math.min(Math.min(amount, fluidBudget), target.remainingFluidCapacity(entry.getKey()));
            if (toTransfer <= 0) continue;
            int removed = ctx.source().removeFluid(entry.getKey(), toTransfer);
            if (removed <= 0) continue;
            int accepted = target.addFluid(entry.getKey(), removed);
            if (accepted < removed) ctx.source().addFluid(entry.getKey(), removed - accepted);
            if (accepted <= 0) continue;
            ctx.storage().recordSnapshotGraphOutput(ctx.server(), ctx.source(), fluidId, accepted, ctx.gameTime());
            ctx.storage().recordSnapshotGraphInput(ctx.server(), target, fluidId, accepted, ctx.gameTime());
            bandwidth.consume(fluidId, accepted);
            movedByFluid.merge(fluidId, accepted, Integer::sum);
            remainingRouteBudget -= accepted;
            if (target.remainingFluidCapacity(entry.getKey()) <= 0) {
                if (canVoid && remainingRouteBudget > 0 && bandwidth.remaining() > 0) {
                    TransferResult voided = voidFilteredFluids(ctx, scopePort, path, remainingRouteBudget, bandwidth);
                    mergeMoved(movedByFluid, voided.movedByResource());
                    remainingRouteBudget -= voided.moved();
                    blockReason = voided.reason();
                    blockFluidId = voided.blockResourceId();
                } else if (remainingRouteBudget > 0) {
                    blockReason = TransferBlockReason.RECEIVER;
                    blockFluidId = fluidId;
                }
                break;
            }
            if (remainingRouteBudget <= 0 || bandwidth.remaining() <= 0) break;
        }
        return finishFluidResult(ctx.source(), scopePort, movedByFluid, matchedSourceFluid, rateLimitedOnly, remainingRouteBudget, bandwidth, blockReason, blockFluidId);
    }

    private static TransferResult transferEnergyToLoaded(Context ctx, BaseChestBlockEntity target, List<TransferEdge> path,
                                                         int routeBudget, BandwidthBudget bandwidth) {
        if (!ctx.source().hasEnergyUpgrade() || !target.hasEnergyUpgrade()) {
            return new TransferResult(Map.of(), TransferBlockReason.RECEIVER, TransferEdge.ENERGY_FE);
        }
        int available = ctx.source().energyStored();
        if (available <= 0) return new TransferResult(Map.of(), TransferBlockReason.SOURCE, TransferEdge.ENERGY_FE);
        int receiverRoom = target.getRemainingEnergyCapacity();
        if (receiverRoom <= 0) return new TransferResult(Map.of(), TransferBlockReason.RECEIVER, TransferEdge.ENERGY_FE);
        int budget = Math.min(pathBudget(path, ctx.gameTime(), routeBudget, TransferEdge.ENERGY_FE), bandwidth.maxTransferable(TransferEdge.ENERGY_FE));
        if (budget <= 0) return new TransferResult(Map.of(), TransferBlockReason.NONE, null);
        int moving = Math.min(Math.min(available, receiverRoom), Math.min(budget, ctx.source().energyTransferLimit()));
        moving = Math.min(moving, target.getEnergyTransferLimit());
        int extracted = ctx.source().extractEnergy(moving);
        int accepted = target.receiveEnergyFromGraph(extracted);
        if (accepted < extracted) ctx.source().receiveEnergy(extracted - accepted);
        if (accepted <= 0) return new TransferResult(Map.of(), TransferBlockReason.RECEIVER, TransferEdge.ENERGY_FE);
        ctx.storage().recordSnapshotGraphOutput(ctx.server(), ctx.source(), TransferEdge.ENERGY_FE, accepted, ctx.gameTime());
        bandwidth.consume(TransferEdge.ENERGY_FE, accepted);
        return new TransferResult(Map.of(TransferEdge.ENERGY_FE, accepted), TransferBlockReason.NONE, null);
    }

    private static TransferResult transferEnergyToSnapshot(Context ctx, OfflineChestSnapshotStorage.Snapshot target,
                                                           List<TransferEdge> path, int routeBudget, BandwidthBudget bandwidth) {
        if (!ctx.source().hasEnergyUpgrade() || !target.hasEnergyUpgrade()) {
            return new TransferResult(Map.of(), TransferBlockReason.RECEIVER, TransferEdge.ENERGY_FE);
        }
        int available = ctx.source().energyStored();
        if (available <= 0) return new TransferResult(Map.of(), TransferBlockReason.SOURCE, TransferEdge.ENERGY_FE);
        int receiverRoom = target.remainingEnergyCapacity();
        if (receiverRoom <= 0) return new TransferResult(Map.of(), TransferBlockReason.RECEIVER, TransferEdge.ENERGY_FE);
        int budget = Math.min(pathBudget(path, ctx.gameTime(), routeBudget, TransferEdge.ENERGY_FE), bandwidth.maxTransferable(TransferEdge.ENERGY_FE));
        if (budget <= 0) return new TransferResult(Map.of(), TransferBlockReason.NONE, null);
        int moving = Math.min(Math.min(available, receiverRoom), Math.min(budget, ctx.source().energyTransferLimit()));
        moving = Math.min(moving, target.energyTransferLimit());
        int extracted = ctx.source().extractEnergy(moving);
        int accepted = target.receiveEnergy(extracted);
        if (accepted < extracted) ctx.source().receiveEnergy(extracted - accepted);
        if (accepted <= 0) return new TransferResult(Map.of(), TransferBlockReason.RECEIVER, TransferEdge.ENERGY_FE);
        ctx.storage().recordSnapshotGraphOutput(ctx.server(), ctx.source(), TransferEdge.ENERGY_FE, accepted, ctx.gameTime());
        ctx.storage().recordSnapshotGraphInput(ctx.server(), target, TransferEdge.ENERGY_FE, accepted, ctx.gameTime());
        bandwidth.consume(TransferEdge.ENERGY_FE, accepted);
        return new TransferResult(Map.of(TransferEdge.ENERGY_FE, accepted), TransferBlockReason.NONE, null);
    }

    private static TransferResult transferStressToLoaded(Context ctx, BaseChestBlockEntity target, List<TransferEdge> path,
                                                         int routeBudget, BandwidthBudget bandwidth) {
        if (!ctx.source().hasStressUpgrade() || !target.hasStressUpgrade()) {
            return new TransferResult(Map.of(), TransferBlockReason.RECEIVER, TransferEdge.STRESS_SU);
        }
        FoldworksStressEndpoint targetEndpoint = stressEndpoint(target);
        if (targetEndpoint == null || !targetEndpoint.canReceiveGraphStress()) {
            return new TransferResult(Map.of(), TransferBlockReason.RECEIVER, TransferEdge.STRESS_SU);
        }
        float sourceCapacity = ctx.source().graphStressCapacity(ctx.gameTime());
        int budget = Math.min(routeBudget, Math.min((int) sourceCapacity, target.getStressTransferLimit()));
        budget = Math.min(budget, bandwidth.maxTransferable(TransferEdge.STRESS_SU));
        if (budget <= 0) return new TransferResult(Map.of(), TransferBlockReason.NONE, null);
        float speed = ctx.source().graphStressSpeed(ctx.gameTime());
        if (speed == 0) return new TransferResult(Map.of(), TransferBlockReason.SOURCE, TransferEdge.STRESS_SU);
        String leaseId = stressLeaseId(ctx.source(), target.getBlockPos(), path);
        // 用 Math.round 四舍五入而非 (int) 截断，避免容量为 x.99 SU 时丢失近 1 SU
        int accepted = Math.max(0, Math.min(budget, Math.round(targetEndpoint.receiveGraphStressLease(leaseId, speed, budget, ctx.gameTime()))));
        if (accepted <= 0) return new TransferResult(Map.of(), TransferBlockReason.RECEIVER, TransferEdge.STRESS_SU);
        bandwidth.consume(TransferEdge.STRESS_SU, accepted);
        return new TransferResult(Map.of(TransferEdge.STRESS_SU, accepted), TransferBlockReason.NONE, null);
    }

    private static TransferResult transferStressToSnapshot(Context ctx, OfflineChestSnapshotStorage.Snapshot target,
                                                           List<TransferEdge> path, int routeBudget, BandwidthBudget bandwidth) {
        if (!ctx.source().hasStressUpgrade() || !target.hasStressUpgrade()) {
            return new TransferResult(Map.of(), TransferBlockReason.RECEIVER, TransferEdge.STRESS_SU);
        }
        if (!target.canReceiveGraphStress()) {
            return new TransferResult(Map.of(), TransferBlockReason.RECEIVER, TransferEdge.STRESS_SU);
        }
        float sourceCapacity = ctx.source().graphStressCapacity(ctx.gameTime());
        int budget = Math.min(routeBudget, Math.min((int) sourceCapacity, target.stressTransferLimit()));
        budget = Math.min(budget, bandwidth.maxTransferable(TransferEdge.STRESS_SU));
        if (budget <= 0) return new TransferResult(Map.of(), TransferBlockReason.NONE, null);
        float speed = ctx.source().graphStressSpeed(ctx.gameTime());
        if (speed == 0) return new TransferResult(Map.of(), TransferBlockReason.SOURCE, TransferEdge.STRESS_SU);
        String leaseId = stressLeaseId(ctx.source(), target.pos(), path);
        // 用 Math.round 四舍五入而非 (int) 截断，避免容量为 x.99 SU 时丢失近 1 SU
        int accepted = Math.max(0, Math.min(budget, Math.round(target.receiveGraphStressLease(leaseId, speed, budget, ctx.gameTime()))));
        if (accepted <= 0) return new TransferResult(Map.of(), TransferBlockReason.RECEIVER, TransferEdge.STRESS_SU);
        bandwidth.consume(TransferEdge.STRESS_SU, accepted);
        return new TransferResult(Map.of(TransferEdge.STRESS_SU, accepted), TransferBlockReason.NONE, null);
    }

    private static TransferResult voidFilteredItems(Context ctx, String scopePort, List<TransferEdge> path,
                                                    int routeBudget, BandwidthBudget bandwidth) {
        int remainingRouteBudget = routeBudget;
        boolean matchedSourceItem = false;
        boolean rateLimitedOnly = false;
        Map<String, Integer> movedByItem = new LinkedHashMap<>();
        for (StoredItemStack entry : ctx.source().items()) {
            if (!portAllows(scopePort, entry.item())) continue;
            matchedSourceItem = true;
            String itemId = itemId(entry.item());
            if (itemId == null) continue;
            int itemBudget = Math.min(pathBudget(path, ctx.gameTime(), remainingRouteBudget, itemId), bandwidth.maxTransferable(itemId));
            if (itemBudget <= 0) {
                rateLimitedOnly = true;
                continue;
            }
            int removed = ctx.source().removeItem(entry.prototype(), Math.min(entry.count(), itemBudget));
            if (removed > 0) {
                ctx.storage().recordSnapshotGraphOutput(ctx.server(), ctx.source(), itemId, removed, ctx.gameTime());
                bandwidth.consume(itemId, removed);
                movedByItem.merge(itemId, removed, Integer::sum);
                remainingRouteBudget -= removed;
                if (remainingRouteBudget <= 0 || bandwidth.remaining() <= 0) break;
            }
        }
        if (!movedByItem.isEmpty()) {
            boolean blocked = remainingRouteBudget > 0 && bandwidth.remaining() > 0;
            return new TransferResult(movedByItem, blocked ? TransferBlockReason.SOURCE : TransferBlockReason.NONE,
                    blocked ? lastMovedResourceId(movedByItem) : null);
        }
        if (rateLimitedOnly) return new TransferResult(Map.of(), TransferBlockReason.NONE, null);
        return new TransferResult(Map.of(), TransferBlockReason.SOURCE, matchedSourceItem ? firstMatchingItemId(ctx.source(), scopePort) : scopedItemId(scopePort));
    }

    private static TransferResult voidFilteredFluids(Context ctx, String scopePort, List<TransferEdge> path,
                                                     int routeBudget, BandwidthBudget bandwidth) {
        int remainingRouteBudget = routeBudget;
        boolean matchedSourceFluid = false;
        boolean rateLimitedOnly = false;
        Map<String, Integer> movedByFluid = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : fluidEntries(ctx.source())) {
            Fluid fluid = resolveFluid(entry.getKey());
            if (fluid == Fluids.EMPTY || !portAllowsFluid(scopePort, fluid)) continue;
            matchedSourceFluid = true;
            String fluidId = TransferEdge.FLUID_PREFIX + entry.getKey();
            int fluidBudget = Math.min(pathBudget(path, ctx.gameTime(), remainingRouteBudget, fluidId), bandwidth.maxTransferable(fluidId));
            if (fluidBudget <= 0) {
                rateLimitedOnly = true;
                continue;
            }
            int removed = ctx.source().removeFluid(entry.getKey(), Math.min(entry.getValue(), fluidBudget));
            if (removed > 0) {
                ctx.storage().recordSnapshotGraphOutput(ctx.server(), ctx.source(), fluidId, removed, ctx.gameTime());
                bandwidth.consume(fluidId, removed);
                movedByFluid.merge(fluidId, removed, Integer::sum);
                remainingRouteBudget -= removed;
                if (remainingRouteBudget <= 0 || bandwidth.remaining() <= 0) break;
            }
        }
        if (!movedByFluid.isEmpty()) {
            boolean blocked = remainingRouteBudget > 0 && bandwidth.remaining() > 0;
            return new TransferResult(movedByFluid, blocked ? TransferBlockReason.SOURCE : TransferBlockReason.NONE,
                    blocked ? lastMovedResourceId(movedByFluid) : null);
        }
        if (rateLimitedOnly) return new TransferResult(Map.of(), TransferBlockReason.NONE, null);
        return new TransferResult(Map.of(), TransferBlockReason.SOURCE, matchedSourceFluid ? firstMatchingFluidId(ctx.source(), scopePort) : scopedFluidId(scopePort));
    }

    private static void recordTransferResult(Context ctx, List<TransferEdge> path, TransferResult result) {
        if (result.moved() > 0) {
            for (Map.Entry<String, Integer> moved : result.movedByResource().entrySet()) {
                for (TransferEdge pathEdge : path) pathEdge.recordMoved(ctx.gameTime(), moved.getKey(), moved.getValue());
            }
            recordRerouteFlow(ctx.graph(), path, ctx.gameTime(), result.movedByResource());
            TransferGraphStorage.get(ctx.server()).setDirty();
        }
        if (result.reason() == TransferBlockReason.SOURCE && result.blockResourceId() != null) {
            for (TransferEdge pathEdge : path) pathEdge.recordSourceBlocked(ctx.gameTime(), result.blockResourceId());
            TransferGraphStorage.get(ctx.server()).setDirty();
        } else if (result.reason() == TransferBlockReason.RECEIVER && result.blockResourceId() != null) {
            for (TransferEdge pathEdge : path) pathEdge.recordReceiverBlocked(ctx.gameTime(), result.blockResourceId());
            TransferGraphStorage.get(ctx.server()).setDirty();
        }
    }

    private static void recordRerouteFlow(TransferGraph graph, List<TransferEdge> path, long gameTime, Map<String, Integer> movedByResource) {
        if (movedByResource.isEmpty()) return;
        for (TransferEdge edge : path) {
            TransferNode to = graph.getNode(edge.getToNodeId());
            if (to != null && to.getNodeType() == TransferNode.NodeType.REROUTE) {
                for (Map.Entry<String, Integer> moved : movedByResource.entrySet()) {
                    to.recordFlowInput(gameTime, moved.getKey(), moved.getValue());
                }
            }
            TransferNode from = graph.getNode(edge.getFromNodeId());
            if (from != null && from.getNodeType() == TransferNode.NodeType.REROUTE) {
                for (Map.Entry<String, Integer> moved : movedByResource.entrySet()) {
                    from.recordFlowOutput(gameTime, moved.getKey(), moved.getValue());
                }
            }
        }
    }

    private static List<TransferEdge> outgoingForScope(TransferGraph graph, String nodeId, String scopePort, boolean trashTargets) {
        List<TransferEdge> edges = new ArrayList<>();
        for (TransferEdge edge : graph.outgoing(nodeId)) {
            if (intersectPorts(scopePort, edge.getFromPortKey()) != null && isTrashTarget(graph, edge) == trashTargets) edges.add(edge);
        }
        return edges;
    }

    private static boolean isTrashTarget(TransferGraph graph, TransferEdge edge) {
        TransferNode target = graph.getNode(edge.getToNodeId());
        return target != null && target.getNodeType() == TransferNode.NodeType.TRASH;
    }

    private static String intersectPorts(String current, String next) {
        if (current == null || current.isBlank()) current = TransferEdge.ITEM_ALL;
        if (next == null || next.isBlank()) next = TransferEdge.ITEM_ALL;
        if (TransferEdge.ITEM_ALL.equals(current)) return isFluidScope(next) || isEnergyScope(next) || isStressScope(next) ? null : next;
        if (TransferEdge.ITEM_ALL.equals(next)) return isFluidScope(current) || isEnergyScope(current) || isStressScope(current) ? null : current;
        if (TransferEdge.FLUID_ALL.equals(current) && isFluidScope(next)) return next;
        if (TransferEdge.FLUID_ALL.equals(next) && isFluidScope(current)) return current;
        return current.equals(next) ? current : null;
    }

    private static int pathBudget(List<TransferEdge> path, long gameTime, int routeBudget, String resourceId) {
        int budget = routeBudget;
        for (TransferEdge edge : path) budget = Math.min(budget, edge.remainingRateBudget(gameTime, resourceId));
        return budget;
    }

    private static int fairBranchBudget(OfflineChestSnapshotStorage.Snapshot source, String branchScope,
                                        List<TransferEdge> remainingEdges, int maxBudget, long gameTime) {
        if (maxBudget <= 1) return maxBudget;
        int available = matchingSourceCount(source, branchScope, gameTime);
        if (available <= 0) return 0;
        int branches = 0;
        for (TransferEdge edge : remainingEdges) {
            if (edge.isEnabled() && intersectPorts(branchScope, edge.getFromPortKey()) != null) branches++;
        }
        if (branches <= 1) return Math.min(maxBudget, available);
        int fair = (available + branches - 1) / branches;
        return Math.max(1, Math.min(maxBudget, fair));
    }

    private static int matchingSourceCount(OfflineChestSnapshotStorage.Snapshot source, String scopePort, long gameTime) {
        if (isFluidScope(scopePort)) return matchingFluidSourceCount(source, scopePort);
        if (isEnergyScope(scopePort)) return source.energyStored();
        if (isStressScope(scopePort)) return Math.max(0, (int) source.graphStressCapacity(gameTime));
        int total = 0;
        for (StoredItemStack stored : source.items()) {
            if (stored.count() > 0 && portAllows(scopePort, stored.item())) total += stored.count();
        }
        return total;
    }

    private static int matchingFluidSourceCount(OfflineChestSnapshotStorage.Snapshot source, String scopePort) {
        int total = 0;
        for (Map.Entry<String, Integer> entry : source.fluids().entrySet()) {
            Fluid fluid = resolveFluid(entry.getKey());
            if (fluid != Fluids.EMPTY && entry.getValue() > 0 && portAllowsFluid(scopePort, fluid)) total += entry.getValue();
        }
        return total;
    }

    private static TransferResult finishItemResult(OfflineChestSnapshotStorage.Snapshot source, String scopePort,
                                                   Map<String, Integer> movedByItem, boolean matchedSourceItem,
                                                   boolean rateLimitedOnly, int remainingRouteBudget,
                                                   BandwidthBudget bandwidth, TransferBlockReason blockReason,
                                                   String blockItemId) {
        if (movedByItem.isEmpty()) {
            if (rateLimitedOnly) return new TransferResult(Map.of(), TransferBlockReason.NONE, null);
            return new TransferResult(Map.of(), matchedSourceItem ? TransferBlockReason.RECEIVER : TransferBlockReason.SOURCE,
                    matchedSourceItem ? firstMatchingItemId(source, scopePort) : scopedItemId(scopePort));
        }
        if (remainingRouteBudget <= 0 || bandwidth.remaining() <= 0) return new TransferResult(movedByItem, TransferBlockReason.NONE, null);
        return new TransferResult(movedByItem, blockReason != TransferBlockReason.NONE ? blockReason : TransferBlockReason.SOURCE,
                blockItemId != null ? blockItemId : lastMovedResourceId(movedByItem));
    }

    private static TransferResult finishFluidResult(OfflineChestSnapshotStorage.Snapshot source, String scopePort,
                                                    Map<String, Integer> movedByFluid, boolean matchedSourceFluid,
                                                    boolean rateLimitedOnly, int remainingRouteBudget,
                                                    BandwidthBudget bandwidth, TransferBlockReason blockReason,
                                                    String blockFluidId) {
        if (movedByFluid.isEmpty()) {
            if (rateLimitedOnly) return new TransferResult(Map.of(), TransferBlockReason.NONE, null);
            return new TransferResult(Map.of(), matchedSourceFluid ? TransferBlockReason.RECEIVER : TransferBlockReason.SOURCE,
                    matchedSourceFluid ? firstMatchingFluidId(source, scopePort) : scopedFluidId(scopePort));
        }
        if (remainingRouteBudget <= 0 || bandwidth.remaining() <= 0) return new TransferResult(movedByFluid, TransferBlockReason.NONE, null);
        return new TransferResult(movedByFluid, blockReason != TransferBlockReason.NONE ? blockReason : TransferBlockReason.SOURCE,
                blockFluidId != null ? blockFluidId : lastMovedResourceId(movedByFluid));
    }

    private static BaseChestBlockEntity loadedChest(MinecraftServer server, TransferNode node) {
        ResourceLocation dimLoc = ResourceLocation.tryParse(node.getDimensionKey());
        if (dimLoc == null) return null;
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, dimLoc));
        if (level == null) return null;
        BaseChestBlockEntity be = FoldworksChestAccess.resolve(level.getBlockEntity(node.getPos()));
        if (be != null && be.getChestId().equals(node.getChestId()) && be.hasNetworkUpgrade()) return be;
        return MovingChestRegistry.findChest(node.getDimensionKey(), node.getPos(), node.getChestId());
    }

    private static OfflineChestSnapshotStorage.Snapshot snapshotTarget(Context ctx, TransferNode node) {
        OfflineChestSnapshotStorage.Snapshot snapshot = ctx.storage().findSnapshot(node.getDimensionKey(), node.getPos(), node.getChestId());
        if (snapshot == null || snapshot.loaded() || snapshot.moving() || !snapshot.hasNetworkUpgrade()) return null;
        if (!snapshot.matchesGraph(ctx.graph().getKey()) || !snapshot.shouldSimulate(ctx.server())) return null;
        return snapshot;
    }

    private static int limitGateBudget(Context ctx, String pageId, TransferNode gate, String scopePort, int routeBudget) {
        if (gate == null || gate.getNodeType() != TransferNode.NodeType.LIMIT_GATE || routeBudget <= 0) return 0;
        String resourceId = exactGateResource(scopePort);
        if (resourceId == null) return 0;
        if (gate.isGateCheckSource()) {
            int amount = snapshotResourceAmount(ctx.source(), resourceId, ctx.gameTime());
            return gate.sourceGateBudgetWithinPassRange(amount, routeBudget);
        }
        TransferNode destination = resolveGateDestination(ctx.graph(), pageId, gate, scopePort, new HashSet<>());
        if (destination == null) return 0;
        int amount = destinationResourceAmount(ctx, destination, resourceId);
        return gate.gateBudgetWithinPassRange(amount, routeBudget);
    }

    private static TransferNode resolveGateDestination(TransferGraph graph, String pageId, TransferNode start,
                                                       String scopePort, Set<String> visited) {
        TransferNode cursor = start;
        String currentScope = scopePort;
        for (int i = 0; i < 32 && cursor != null && visited.add(cursor.getId()); i++) {
            if (!cursor.isEnabled() || !cursor.getPageId().equals(pageId)) return null;
            TransferEdge edge = singleOutgoingForScope(graph, cursor.getId(), currentScope);
            if (edge == null || !edge.getPageId().equals(pageId)) return null;
            currentScope = intersectPorts(currentScope, edge.getFromPortKey());
            if (currentScope == null) return null;
            TransferNode target = graph.getNode(edge.getToNodeId());
            if (target == null || !target.isEnabled()) return null;
            if (target.getNodeType() == TransferNode.NodeType.CHEST
                    || target.getNodeType() == TransferNode.NodeType.PLAYER_INVENTORY) {
                return target;
            }
            if (target.getNodeType() == TransferNode.NodeType.JUMP_INPUT) {
                cursor = linkedJumpOutput(graph, target);
            } else if (target.getNodeType() == TransferNode.NodeType.REROUTE
                    || target.getNodeType() == TransferNode.NodeType.LIMIT_GATE
                    || target.getNodeType() == TransferNode.NodeType.JUMP_OUTPUT) {
                cursor = target;
            } else {
                return null;
            }
        }
        return null;
    }

    private static TransferEdge singleOutgoingForScope(TransferGraph graph, String nodeId, String scopePort) {
        TransferEdge result = null;
        for (TransferEdge edge : graph.outgoing(nodeId)) {
            if (!edge.isEnabled() || intersectPorts(scopePort, edge.getFromPortKey()) == null) continue;
            if (result != null) return null;
            result = edge;
        }
        return result;
    }

    private static TransferNode linkedJumpOutput(TransferGraph graph, TransferNode input) {
        if (input == null || input.getNodeType() != TransferNode.NodeType.JUMP_INPUT) return null;
        TransferNode direct = graph.getNode(input.getLinkedNodeId());
        if (direct != null && direct.getNodeType() == TransferNode.NodeType.JUMP_OUTPUT
                && input.getId().equals(direct.getLinkedNodeId())) return direct;
        for (TransferNode node : graph.getNodes()) {
            if (node.getNodeType() == TransferNode.NodeType.JUMP_OUTPUT && input.getId().equals(node.getLinkedNodeId())) return node;
        }
        return null;
    }

    private static int destinationResourceAmount(Context ctx, TransferNode destination, String resourceId) {
        if (destination.getNodeType() == TransferNode.NodeType.PLAYER_INVENTORY) return playerResourceAmount(ctx, destination, resourceId);
        if (destination.getNodeType() != TransferNode.NodeType.CHEST) return -1;
        BaseChestBlockEntity loaded = loadedChest(ctx.server(), destination);
        if (loaded != null && !loaded.isRemoved() && TransferGraphAccess.chestMatchesGraph(loaded, ctx.graph().getKey())) {
            return loadedResourceAmount(loaded, resourceId);
        }
        OfflineChestSnapshotStorage.Snapshot snapshot = snapshotTarget(ctx, destination);
        return snapshot == null ? -1 : snapshotResourceAmount(snapshot, resourceId, ctx.gameTime());
    }

    private static int loadedResourceAmount(BaseChestBlockEntity chest, String resourceId) {
        if (resourceId.startsWith(TransferEdge.ITEM_PREFIX)) {
            String itemId = resourceId.substring(TransferEdge.ITEM_PREFIX.length());
            int total = 0;
            for (StoredItemStack stored : chest.getStoredItems()) {
                ResourceLocation id = BuiltInRegistries.ITEM.getKey(stored.item());
                if (id != null && id.toString().equals(itemId)) total += stored.count();
            }
            return total;
        }
        if (resourceId.startsWith(TransferEdge.FLUID_PREFIX)) {
            Fluid fluid = resolveFluid(resourceId.substring(TransferEdge.FLUID_PREFIX.length()));
            return fluid == Fluids.EMPTY ? -1 : chest.getAllFluids().getOrDefault(fluid, 0);
        }
        if (TransferEdge.ENERGY_FE.equals(resourceId)) return chest.getEnergyStored();
        if (TransferEdge.STRESS_SU.equals(resourceId)) {
            FoldworksStressEndpoint endpoint = stressEndpoint(chest);
            return endpoint == null ? 0 : Math.max(0, Math.round(endpoint.graphStressCapacity()));
        }
        return -1;
    }

    private static int snapshotResourceAmount(OfflineChestSnapshotStorage.Snapshot snapshot, String resourceId, long gameTime) {
        if (resourceId.startsWith(TransferEdge.ITEM_PREFIX)) {
            String itemId = resourceId.substring(TransferEdge.ITEM_PREFIX.length());
            int total = 0;
            for (StoredItemStack stored : snapshot.items()) {
                ResourceLocation id = BuiltInRegistries.ITEM.getKey(stored.item());
                if (id != null && id.toString().equals(itemId)) total += stored.count();
            }
            return total;
        }
        if (resourceId.startsWith(TransferEdge.FLUID_PREFIX)) {
            return snapshot.fluids().getOrDefault(resourceId.substring(TransferEdge.FLUID_PREFIX.length()), 0);
        }
        if (TransferEdge.ENERGY_FE.equals(resourceId)) return snapshot.energyStored();
        if (TransferEdge.STRESS_SU.equals(resourceId)) return Math.max(0, Math.round(snapshot.graphStressCapacity(gameTime)));
        return -1;
    }

    private static int playerResourceAmount(Context ctx, TransferNode destination, String resourceId) {
        if (destination.getTargetPlayerId() == null || !resourceId.startsWith(TransferEdge.ITEM_PREFIX)) return -1;
        ServerPlayer player = ctx.server().getPlayerList().getPlayer(destination.getTargetPlayerId());
        Item item = resolveItem(resourceId.substring(TransferEdge.ITEM_PREFIX.length()));
        return player == null || item == Items.AIR ? -1 : countPlayerMainInventory(player, item);
    }

    private static String exactGateResource(String scopePort) {
        if (scopePort == null || scopePort.isBlank() || TransferEdge.PORT_ALL.equals(scopePort) || TransferEdge.FLUID_ALL.equals(scopePort)) return null;
        if (scopePort.startsWith(TransferEdge.ITEM_PREFIX) || scopePort.startsWith(TransferEdge.FLUID_PREFIX)
                || TransferEdge.ENERGY_FE.equals(scopePort) || TransferEdge.STRESS_SU.equals(scopePort)) return scopePort;
        return null;
    }

    private static FoldworksStressEndpoint stressEndpoint(BaseChestBlockEntity chest) {
        if (chest == null || chest.getLevel() == null) return null;
        BlockEntity blockEntity = chest.getLevel().getBlockEntity(chest.getBlockPos());
        if (blockEntity instanceof FoldworksStressEndpoint endpoint && endpoint.foldworksChest() == chest) return endpoint;
        return null;
    }

    private static boolean receiveFilterAllows(TransferNode targetNode, String resourceId) {
        if (targetNode == null || targetNode.getReceiveFilterIds().isEmpty()) return true;
        if (resourceId == null || resourceId.isBlank()) return false;
        if (targetNode.getReceiveFilterIds().contains(resourceId)) return true;
        if (resourceId.startsWith(TransferEdge.ITEM_PREFIX)) {
            String bare = resourceId.substring(TransferEdge.ITEM_PREFIX.length());
            return targetNode.getReceiveFilterIds().contains(bare) || targetNode.getReceiveFilterIds().contains(TransferEdge.ITEM_ALL);
        }
        if (resourceId.startsWith(TransferEdge.FLUID_PREFIX)) return targetNode.getReceiveFilterIds().contains(TransferEdge.FLUID_ALL);
        return false;
    }

    private static boolean portAllows(String scopePort, Item item) {
        if (TransferEdge.ITEM_ALL.equals(scopePort)) return true;
        if (scopePort == null || !scopePort.startsWith(TransferEdge.ITEM_PREFIX)) return false;
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        return id != null && scopePort.substring(TransferEdge.ITEM_PREFIX.length()).equals(id.toString());
    }

    private static boolean portAllowsFluid(String scopePort, Fluid fluid) {
        if (TransferEdge.FLUID_ALL.equals(scopePort)) return true;
        if (scopePort == null || !scopePort.startsWith(TransferEdge.FLUID_PREFIX)) return false;
        ResourceLocation id = BuiltInRegistries.FLUID.getKey(fluid);
        return id != null && scopePort.substring(TransferEdge.FLUID_PREFIX.length()).equals(id.toString());
    }

    private static String itemId(Item item) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        return id == null ? null : TransferEdge.ITEM_PREFIX + id;
    }

    private static String bareItemId(Item item) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        return id == null ? null : id.toString();
    }

    private static Fluid resolveFluid(String idValue) {
        ResourceLocation id = ResourceLocation.tryParse(idValue);
        if (id == null) return Fluids.EMPTY;
        Fluid fluid = BuiltInRegistries.FLUID.get(id);
        return fluid == null ? Fluids.EMPTY : fluid;
    }

    private static Item resolveItem(String idValue) {
        ResourceLocation id = ResourceLocation.tryParse(idValue);
        if (id == null) return Items.AIR;
        Item item = BuiltInRegistries.ITEM.get(id);
        return item == null ? Items.AIR : item;
    }

    private static List<Map.Entry<String, Integer>> fluidEntries(OfflineChestSnapshotStorage.Snapshot source) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(source.fluids().entrySet());
        entries.sort(Comparator.comparing(Map.Entry::getKey));
        return entries;
    }

    private static String scopedItemId(String scopePort) {
        return scopePort != null && scopePort.startsWith(TransferEdge.ITEM_PREFIX) ? scopePort : null;
    }

    private static String scopedFluidId(String scopePort) {
        return scopePort != null && scopePort.startsWith(TransferEdge.FLUID_PREFIX) && !TransferEdge.FLUID_ALL.equals(scopePort) ? scopePort : null;
    }

    private static String firstMatchingItemId(OfflineChestSnapshotStorage.Snapshot source, String scopePort) {
        for (StoredItemStack stored : source.items()) {
            if (stored.count() > 0 && portAllows(scopePort, stored.item())) return itemId(stored.item());
        }
        return scopedItemId(scopePort);
    }

    private static String firstMatchingFluidId(OfflineChestSnapshotStorage.Snapshot source, String scopePort) {
        for (Map.Entry<String, Integer> entry : source.fluids().entrySet()) {
            Fluid fluid = resolveFluid(entry.getKey());
            if (fluid != Fluids.EMPTY && entry.getValue() > 0 && portAllowsFluid(scopePort, fluid)) {
                return TransferEdge.FLUID_PREFIX + entry.getKey();
            }
        }
        return scopedFluidId(scopePort);
    }

    private static String firstMatchingResourceId(OfflineChestSnapshotStorage.Snapshot source, String scopePort, long gameTime) {
        if (isEnergyScope(scopePort)) return TransferEdge.ENERGY_FE;
        if (isStressScope(scopePort)) return TransferEdge.STRESS_SU;
        return isFluidScope(scopePort) ? firstMatchingFluidId(source, scopePort) : firstMatchingItemId(source, scopePort);
    }

    private static String lastMovedResourceId(Map<String, Integer> movedByResource) {
        String last = null;
        for (String resourceId : movedByResource.keySet()) last = resourceId;
        return last;
    }

    private static boolean isFluidScope(String scopePort) {
        return scopePort != null && scopePort.startsWith(TransferEdge.FLUID_PREFIX);
    }

    private static boolean isEnergyScope(String scopePort) {
        return TransferEdge.ENERGY_FE.equals(scopePort);
    }

    private static boolean isStressScope(String scopePort) {
        return TransferEdge.STRESS_SU.equals(scopePort);
    }

    private static int countPlayerMainInventory(ServerPlayer player, Item item) {
        int total = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (!stack.isEmpty() && stack.getItem() == item) total += stack.getCount();
        }
        return total;
    }

    private static int playerMainInventoryRoom(ServerPlayer player, ItemStack prototype) {
        if (prototype == null || prototype.isEmpty()) return 0;
        int room = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (stack.isEmpty()) room += prototype.getMaxStackSize();
            else if (ItemStack.isSameItemSameComponents(stack, prototype)) room += Math.max(0, stack.getMaxStackSize() - stack.getCount());
        }
        return room;
    }

    private static int insertIntoPlayerMainInventory(ServerPlayer player, ItemStack prototype, int amount) {
        if (prototype == null || prototype.isEmpty() || amount <= 0) return 0;
        int remaining = amount;
        for (ItemStack stack : player.getInventory().items) {
            if (remaining <= 0) break;
            if (stack.isEmpty() || !ItemStack.isSameItemSameComponents(stack, prototype)) continue;
            int room = Math.max(0, stack.getMaxStackSize() - stack.getCount());
            int move = Math.min(room, remaining);
            if (move > 0) {
                stack.grow(move);
                remaining -= move;
            }
        }
        for (int i = 0; i < player.getInventory().items.size() && remaining > 0; i++) {
            ItemStack stack = player.getInventory().items.get(i);
            if (!stack.isEmpty()) continue;
            int move = Math.min(prototype.getMaxStackSize(), remaining);
            player.getInventory().items.set(i, prototype.copyWithCount(move));
            remaining -= move;
        }
        if (remaining != amount) player.getInventory().setChanged();
        return amount - remaining;
    }

    private static String stressLeaseId(OfflineChestSnapshotStorage.Snapshot source, BlockPos targetPos, List<TransferEdge> path) {
        StringBuilder id = new StringBuilder();
        id.append(source.dimensionKey()).append('|').append(source.posLong()).append('|');
        for (TransferEdge edge : path) id.append(edge.getId()).append('>');
        id.append(targetPos.asLong());
        return id.toString();
    }

    private static void mergeMoved(Map<String, Integer> target, Map<String, Integer> source) {
        for (Map.Entry<String, Integer> entry : source.entrySet()) target.merge(entry.getKey(), entry.getValue(), Integer::sum);
    }

    private static int maxAmountForBandwidth(String scopePort, int bandwidth) {
        if (bandwidth <= 0) return 0;
        if (isItemBandwidthScope(scopePort)) return bandwidth / itemBandwidthCost();
        long value = (long) bandwidth * unitsPerBandwidth(scopePort);
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, value));
    }

    private static int bandwidthCost(String scopePort, int amount) {
        if (amount <= 0) return 0;
        if (isItemBandwidthScope(scopePort)) {
            long value = (long) amount * itemBandwidthCost();
            return (int) Math.max(0, Math.min(Integer.MAX_VALUE, value));
        }
        int units = unitsPerBandwidth(scopePort);
        return (amount + units - 1) / units;
    }

    private static boolean isItemBandwidthScope(String scopePort) {
        return scopePort == null || scopePort.isBlank() || scopePort.startsWith(TransferEdge.ITEM_PREFIX);
    }

    private static int unitsPerBandwidth(String scopePort) {
        if (scopePort != null && scopePort.startsWith(TransferEdge.FLUID_PREFIX)) return Math.max(1, ModConfig.FLUID_MB_PER_BANDWIDTH.get());
        if (TransferEdge.ENERGY_FE.equals(scopePort)) return Math.max(1, ModConfig.ENERGY_FE_PER_BANDWIDTH.get());
        if (TransferEdge.STRESS_SU.equals(scopePort)) return Math.max(1, ModConfig.STRESS_SU_PER_BANDWIDTH.get());
        return 1;
    }

    private static int itemBandwidthCost() {
        return Math.max(1, ModConfig.ITEM_BANDWIDTH_COST.get());
    }
}
