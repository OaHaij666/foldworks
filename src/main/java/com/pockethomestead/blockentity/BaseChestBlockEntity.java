package com.pockethomestead.blockentity;

import com.pockethomestead.block.AbstractHomesteadBlock;
import com.pockethomestead.config.ModConfig;
import com.pockethomestead.production.ProductionStatsStorage;
import com.pockethomestead.registry.ChestRegistryManager;
import com.pockethomestead.transfer.TransferEdge;
import com.pockethomestead.transfer.TransferGraph;
import com.pockethomestead.transfer.TransferGraphStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

import java.util.*;

/**
 * 箱子基类 - 实现容量系统（而非固定格子）
 * 支持最多 MAX_CHEST_CAPACITY 方块的总容量（默认4096），物品种类不限
 */
public abstract class BaseChestBlockEntity extends BlockEntity implements MenuProvider {
    private static final int GRAPH_TRANSFER_INTERVAL_TICKS = 20;

    private enum TransferBlockReason { NONE, SOURCE, RECEIVER }
    private record TransferResult(Map<String, Integer> movedByItem, TransferBlockReason reason, String blockItemId) {
        private int moved() { return movedByItem.values().stream().mapToInt(Integer::intValue).sum(); }
    }

    // 容量系统：完整 ItemStack 组件身份 -> 数量（总容量从ModConfig读取）
    protected final List<StoredItemStack> itemStorage = new ArrayList<>();
    private final Map<String, Integer> transferRouteCursor = new HashMap<>();

    // 流体容量系统：Fluid -> mB（1000mB=1桶）
    protected final Map<Fluid, Integer> fluidStorage = new HashMap<>();
    private final com.pockethomestead.compat.item.HomesteadChestItemHandler itemHandler =
            new com.pockethomestead.compat.item.HomesteadChestItemHandler(this);
    private final com.pockethomestead.compat.fluid.HomesteadChestFluidHandler fluidHandler =
            new com.pockethomestead.compat.fluid.HomesteadChestFluidHandler(this);

    // 箱子ID（玩家自定义，用于绑定）
    protected String chestId = "";

    // 绑定的目标箱子ID
    protected String boundTargetId = "";

    // 所有者UUID
    protected UUID ownerUUID = null;

    // 传送开关
    protected boolean transferEnabled = false;

    // 虚空模式开关
    protected boolean voidModeEnabled = false;

    // 传输筛选器：允许传输的物品集合（空=全部允许）
    protected final Set<Item> allowedItems = new HashSet<>();

    // 传输速率限制（物品/tick，0=无限制）
    protected int transferRateLimit = 0;

    // 同步间隔（秒）：供货箱每隔该时长向绑定的取货箱传输一次。
    // 属于绑定对的属性，绑定时双方保持一致。
    protected int syncIntervalSeconds = 30;

    // TODO: 信任度机制 - 占位，后续实现反作弊/可信度评估
    protected double trustScore = 1.0;

    // GUI 存货区滚动行偏移（服务端持有，供 VirtualChestContainer.refill 读取）
    public int viewScrollRow = 0;

    // 标记位：Map 存储变更后需同步到 VirtualChestContainer
    public boolean storageDirty = false;

    // 防止重复注册（onLoad 可能被多次调用）
    private boolean registered = false;

    public BaseChestBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    /**
     * 获取箱子类型（子类实现）
     */
    public abstract ChestRegistryManager.ChestType getChestType();

    @Override
    public void onLoad() {
        super.onLoad();
        registerIfReady();
    }

    /**
     * 确保箱子在拥有有效 owner+id 时注册到全局管理器（幂等）。
     * 新放置的箱子：onLoad 先于 setPlacedBy 执行，此时 owner/id 尚未就绪，
     * 因此需在 setPlacedBy 与 doTick 中再次尝试注册。
     */
    public void registerIfReady() {
        if (level == null || level.isClientSide) return;
        if (registered) return;
        if (ownerUUID == null || chestId.isEmpty()) return;
        ChestRegistryManager.getInstance().registerChest(ownerUUID, chestId, level, worldPosition, getChestType());
        registered = true;
        refreshProductionInventorySnapshot();
    }

    @Override
    public void setRemoved() {
        // 服务端卸载时从全局管理器注销
        if (!level.isClientSide && ownerUUID != null && !chestId.isEmpty() && registered) {
            ChestRegistryManager.getInstance().unregisterChest(ownerUUID, chestId, level, worldPosition, getChestType());
            registered = false;
        }
        super.setRemoved();
    }

    // ===== 容量系统方法 =====

    /**
     * 获取当前已用容量
     */
    public int getUsedCapacity() {
        return itemStorage.stream().mapToInt(StoredItemStack::count).sum();
    }

    /**
     * 获取剩余容量
     */
    public int getRemainingCapacity() {
        return ModConfig.MAX_CHEST_CAPACITY.get() - getUsedCapacity();
    }

    /**
     * 尝试添加物品（尽力而为）
     * @return 实际添加的数量
     */
    public int addItem(Item item, int count) {
        return addItem(new ItemStack(item), count);
    }

    public int addItem(ItemStack stack, int count) {
        if (stack == null || stack.isEmpty() || count <= 0) return 0;
        int remaining = getRemainingCapacity();
        int toAdd = Math.min(count, remaining);
        if (toAdd > 0) {
            StoredItemStack stored = findStoredStack(stack);
            if (stored == null) itemStorage.add(new StoredItemStack(stack, toAdd));
            else stored.grow(toAdd);
            storageDirty = true;
            setChanged();
            recordProductionInput(stack.getItem(), toAdd);
        }
        return toAdd;
    }

    /**
     * 尝试移除物品（尽力而为）
     * @return 实际移除的数量
     */
    public int removeItem(Item item, int count) {
        if (item == null || count <= 0) return 0;
        int remaining = count;
        int removed = 0;
        Iterator<StoredItemStack> it = itemStorage.iterator();
        while (it.hasNext() && remaining > 0) {
            StoredItemStack stored = it.next();
            if (stored.item() != item) continue;
            int part = stored.shrink(remaining);
            remaining -= part;
            removed += part;
            if (stored.isEmpty()) it.remove();
        }
        if (removed > 0) {
            storageDirty = true;
            setChanged();
            recordProductionOutput(item, removed);
        }
        return removed;
    }

    public int removeItem(ItemStack prototype, int count) {
        if (prototype == null || prototype.isEmpty() || count <= 0) return 0;
        StoredItemStack stored = findStoredStack(prototype);
        if (stored == null) return 0;
        int removed = stored.shrink(count);
        if (stored.isEmpty()) itemStorage.remove(stored);
        if (removed > 0) {
            storageDirty = true;
            setChanged();
            recordProductionOutput(prototype.getItem(), removed);
        }
        return removed;
    }

    /**
     * 获取某物品的数量
     */
    public int getItemCount(Item item) {
        int total = 0;
        for (StoredItemStack stored : itemStorage) {
            if (stored.item() == item) total += stored.count();
        }
        return total;
    }

    /**
     * 获取所有物品（只读视图）
     */
    public Map<Item, Integer> getAllItems() {
        Map<Item, Integer> items = new LinkedHashMap<>();
        for (StoredItemStack stored : getStoredItems()) {
            items.merge(stored.item(), stored.count(), Integer::sum);
        }
        return Collections.unmodifiableMap(items);
    }

    public List<StoredItemStack> getStoredItems() {
        List<StoredItemStack> items = new ArrayList<>(itemStorage);
        items.sort(Comparator.comparing(StoredItemStack::sortKey));
        return Collections.unmodifiableList(items);
    }

    private StoredItemStack findStoredStack(ItemStack stack) {
        for (StoredItemStack stored : itemStorage) {
            if (stored.matches(stack)) return stored;
        }
        return null;
    }

    private void addLoadedItem(ItemStack stack, int count) {
        if (stack == null || stack.isEmpty() || count <= 0) return;
        StoredItemStack stored = findStoredStack(stack);
        if (stored == null) itemStorage.add(new StoredItemStack(stack, count));
        else stored.grow(count);
    }

    private void recordProductionInput(Item item, int amount) {
        recordProductionChange(item, amount, true);
    }

    private void recordProductionOutput(Item item, int amount) {
        recordProductionChange(item, amount, false);
    }

    private void recordProductionChange(Item item, int amount, boolean input) {
        if (level == null || level.isClientSide || ownerUUID == null || amount <= 0) return;
        if (!(level instanceof ServerLevel serverLevel)) return;
        net.minecraft.resources.ResourceLocation itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
        if (itemId == null) return;
        ProductionStatsStorage storage = ProductionStatsStorage.get(serverLevel.getServer());
        String key = productionChestKey();
        String resourceKey = TransferEdge.ITEM_PREFIX + itemId;
        if (input) storage.recordInput(ownerUUID, key, resourceKey, amount, serverLevel.getGameTime());
        else storage.recordOutput(ownerUUID, key, resourceKey, amount, serverLevel.getGameTime());
    }

    private void recordProductionFluidChange(Fluid fluid, int amount, boolean input) {
        if (level == null || level.isClientSide || ownerUUID == null || amount <= 0) return;
        if (!(level instanceof ServerLevel serverLevel)) return;
        net.minecraft.resources.ResourceLocation fluidId = net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(fluid);
        if (fluidId == null) return;
        ProductionStatsStorage storage = ProductionStatsStorage.get(serverLevel.getServer());
        String key = productionChestKey();
        String resourceKey = TransferEdge.FLUID_PREFIX + fluidId;
        if (input) storage.recordInput(ownerUUID, key, resourceKey, amount, serverLevel.getGameTime());
        else storage.recordOutput(ownerUUID, key, resourceKey, amount, serverLevel.getGameTime());
    }

    private void refreshProductionInventorySnapshot() {
        if (level == null || level.isClientSide || ownerUUID == null) return;
        if (!(level instanceof ServerLevel serverLevel)) return;
        ProductionStatsStorage.get(serverLevel.getServer()).refreshChestInventory(ownerUUID, productionChestKey(), productionResourceSnapshot());
    }

    public String productionChestKey() {
        if (level == null) return "";
        return ProductionStatsStorage.chestKey(getChestType(), level.dimension().location().toString(), worldPosition);
    }

    public Map<String, Integer> productionItemSnapshot() {
        return productionResourceSnapshot();
    }

    public Map<String, Integer> productionResourceSnapshot() {
        Map<String, Integer> items = new LinkedHashMap<>();
        for (StoredItemStack stored : itemStorage) {
            net.minecraft.resources.ResourceLocation itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stored.item());
            if (itemId != null && stored.count() > 0) items.merge(TransferEdge.ITEM_PREFIX + itemId, stored.count(), Integer::sum);
        }
        for (Map.Entry<Fluid, Integer> entry : fluidStorage.entrySet()) {
            net.minecraft.resources.ResourceLocation fluidId = net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(entry.getKey());
            if (fluidId != null && entry.getValue() > 0) items.merge(TransferEdge.FLUID_PREFIX + fluidId, entry.getValue(), Integer::sum);
        }
        return items;
    }

    /** 获取当前已用流体容量（mB） */
    public int getUsedFluidCapacityMb() {
        return fluidStorage.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** 获取剩余流体容量（mB） */
    public int getRemainingFluidCapacityMb() {
        return ModConfig.MAX_CHEST_FLUID_CAPACITY_MB.get() - getUsedFluidCapacityMb();
    }

    /** 尝试添加流体（尽力而为，单位 mB） */
    public int addFluid(Fluid fluid, int amountMb) {
        if (fluid == null || fluid == Fluids.EMPTY || amountMb <= 0) return 0;
        int toAdd = Math.min(amountMb, getRemainingFluidCapacityMb());
        if (toAdd > 0) {
            fluidStorage.merge(fluid, toAdd, Integer::sum);
            storageDirty = true;
            setChanged();
            recordProductionFluidChange(fluid, toAdd, true);
        }
        return toAdd;
    }

    /** 尝试移除流体（尽力而为，单位 mB） */
    public int removeFluid(Fluid fluid, int amountMb) {
        if (fluid == null || fluid == Fluids.EMPTY || amountMb <= 0) return 0;
        int current = fluidStorage.getOrDefault(fluid, 0);
        int toRemove = Math.min(amountMb, current);
        if (toRemove > 0) {
            int newAmount = current - toRemove;
            if (newAmount <= 0) {
                fluidStorage.remove(fluid);
            } else {
                fluidStorage.put(fluid, newAmount);
            }
            storageDirty = true;
            setChanged();
            recordProductionFluidChange(fluid, toRemove, false);
        }
        return toRemove;
    }

    /** 获取所有流体（只读视图，单位 mB） */
    public Map<Fluid, Integer> getAllFluids() {
        return Collections.unmodifiableMap(fluidStorage);
    }

    public com.pockethomestead.compat.item.HomesteadChestItemHandler getItemHandler() {
        return itemHandler;
    }

    public com.pockethomestead.compat.item.HomesteadChestItemHandler getItemHandler(Direction side) {
        return new com.pockethomestead.compat.item.HomesteadChestItemHandler(this, canAutomationInsert(side), canAutomationExtract(side));
    }

    public com.pockethomestead.compat.fluid.HomesteadChestFluidHandler getFluidHandler() {
        return fluidHandler;
    }

    public com.pockethomestead.compat.fluid.HomesteadChestFluidHandler getFluidHandler(Direction side) {
        return new com.pockethomestead.compat.fluid.HomesteadChestFluidHandler(this, canAutomationInsert(side), canAutomationExtract(side));
    }

    private boolean canAutomationInsert(Direction side) {
        if (side == null) return true;
        Direction front = getBlockState().getValue(AbstractHomesteadBlock.FACING);
        Direction back = front.getOpposite();
        if (side == Direction.UP || side == back) return true;
        return side.getAxis().isHorizontal() && side != front && side != back;
    }

    private boolean canAutomationExtract(Direction side) {
        if (side == null) return true;
        Direction front = getBlockState().getValue(AbstractHomesteadBlock.FACING);
        Direction back = front.getOpposite();
        if (side == Direction.DOWN || side == front) return true;
        return side.getAxis().isHorizontal() && side != front && side != back;
    }

    /**
     * 客户端同步物品（仅用于网络数据包）
     */
    public void clientSyncItems(Map<Item, Integer> items) {
        if (level != null && level.isClientSide) {
            itemStorage.clear();
            for (Map.Entry<Item, Integer> entry : items.entrySet()) {
                if (entry.getKey() != null && entry.getValue() > 0) {
                    itemStorage.add(new StoredItemStack(new ItemStack(entry.getKey()), entry.getValue()));
                }
            }
        }
    }

    // ===== Getter/Setter =====

    public String getChestId() { return chestId; }
    public void setChestId(String id) { this.chestId = id; setChanged(); }

    public String getBoundTargetId() { return boundTargetId; }
    public void setBoundTargetId(String id) { this.boundTargetId = id; setChanged(); }

    public UUID getOwnerUUID() { return ownerUUID; }
    public void setOwnerUUID(UUID uuid) { this.ownerUUID = uuid; setChanged(); }

    public boolean isTransferEnabled() { return transferEnabled; }
    public void setTransferEnabled(boolean enabled) { this.transferEnabled = enabled; setChanged(); }

    public boolean isVoidModeEnabled() { return voidModeEnabled; }
    public void setVoidModeEnabled(boolean enabled) { this.voidModeEnabled = enabled; setChanged(); }

    public Set<Item> getAllowedItems() { return allowedItems; }

    public int getTransferRateLimit() { return transferRateLimit; }
    public void setTransferRateLimit(int limit) { this.transferRateLimit = limit; setChanged(); }

    public int getSyncIntervalSeconds() { return syncIntervalSeconds; }
    public void setSyncIntervalSeconds(int seconds) {
        this.syncIntervalSeconds = Math.max(1, seconds);
        this.tickCounter = 0; // 重置计数，立即按新间隔重新计时
        setChanged();
    }

    /** 计算距离下一次传输还有多少秒（用于UI倒计时） */
    public int getNextTransferSeconds() {
        if (!hasGraphOutgoingEdges()) return 0;
        int interval = GRAPH_TRANSFER_INTERVAL_TICKS;
        int offset = Math.abs(worldPosition.hashCode()) % interval;
        int elapsed = (tickCounter + offset) % interval;
        int remaining = interval - elapsed;
        return Math.max(1, (remaining + 19) / 20); // 向上取整到秒
    }

    public double getTrustScore() { return trustScore; }
    public void setTrustScore(double score) { this.trustScore = score; setChanged(); }

    // ===== Tick & 传输逻辑 =====

    private int tickCounter = 0;

    /**
     * 静态tick方法，供 BlockEntityTicker 调用
     */
    public static void serverTick(Level level, BlockPos pos, BlockState state, BaseChestBlockEntity be) {
        be.doTick();
    }

    /**
     * 每tick执行，驱动传输
     */
    protected void doTick() {
        if (level == null || level.isClientSide) return;
        // 兜底：确保箱子已注册（新放置时 onLoad 早于 setPlacedBy）
        registerIfReady();
        if (ownerUUID == null) return;
        if (!transferEnabled && !hasGraphOutgoingEdges()) return;

        tickCounter++;
        int interval = GRAPH_TRANSFER_INTERVAL_TICKS;
        int offset = Math.abs(worldPosition.hashCode()) % interval;
        if ((tickCounter + offset) % interval != 0) return;

        doTransferTick();
    }

    /**
     * 执行一次传输周期（仅供货箱发起：SUPPLY → PICKUP 单向传输）
     */
    protected void doTransferTick() {
        // 只有供货箱发送物品，取货箱只接收不发送
        if (getChestType() != ChestRegistryManager.ChestType.SUPPLY) return;
        if (!(level instanceof ServerLevel serverLevel)) return;
        if (itemStorage.isEmpty() && fluidStorage.isEmpty()) return;

        TransferGraph graph = TransferGraphStorage.get(serverLevel.getServer()).graphFor(ownerUUID);
        String dim = level.dimension().location().toString();
        var node = graph.findNode(chestId, dim, worldPosition, ChestRegistryManager.ChestType.SUPPLY);
        if (node == null || !node.isEnabled()) return;
        var page = graph.getPage(node.getPageId());
        if (page == null || !page.isEnabled()) return;

        boolean canVoid = ModConfig.VOID_ENABLED.get() && (voidModeEnabled || ModConfig.GLOBAL_VOID_MODE.get());
        if (!itemStorage.isEmpty()) {
            followOutgoingEdges(graph, serverLevel, node.getPageId(), node.getId(), TransferEdge.PORT_ALL, "ROOT_ITEM",
                    new ArrayList<>(), new HashSet<>(), canVoid, Integer.MAX_VALUE);
        }
        if (!fluidStorage.isEmpty()) {
            followOutgoingEdges(graph, serverLevel, node.getPageId(), node.getId(), TransferEdge.FLUID_ALL, "ROOT_FLUID",
                    new ArrayList<>(), new HashSet<>(), canVoid, Integer.MAX_VALUE);
        }
    }

    private int followTransferEdge(TransferGraph graph, ServerLevel serverLevel, String pageId, TransferEdge edge,
                                   String scopePort, List<TransferEdge> path, Set<String> visitedNodes, boolean canVoid, int routeBudget) {
        if (routeBudget <= 0) return 0;
        if (!edge.isEnabled() || !edge.getPageId().equals(pageId)) return 0;
        long gameTime = serverLevel.getGameTime();
        if (!edge.canTransferAt(gameTime)) return 0;

        scopePort = intersectPorts(scopePort, edge.getFromPortKey());
        if (scopePort == null) return 0;

        var targetNode = graph.getNode(edge.getToNodeId());
        if (targetNode == null || !targetNode.isEnabled()) return 0;
        if (!visitedNodes.add(targetNode.getId())) return 0;

        List<TransferEdge> nextPath = new ArrayList<>(path);
        nextPath.add(edge);

        if (targetNode.getNodeType() == com.pockethomestead.transfer.TransferNode.NodeType.REROUTE) {
            return followOutgoingEdges(graph, serverLevel, pageId, targetNode.getId(), scopePort, scopePort,
                    nextPath, visitedNodes, canVoid, routeBudget);
        }

        if (targetNode.getNodeType() == com.pockethomestead.transfer.TransferNode.NodeType.TRASH) {
            TransferResult result = isFluidScope(scopePort)
                    ? voidFilteredFluids(scopePort, nextPath, gameTime, routeBudget)
                    : voidFilteredItems(scopePort, nextPath, gameTime, routeBudget);
            recordTransferResult(graph, serverLevel, nextPath, gameTime, result);
            return result.moved();
        }

        if (targetNode.getNodeType() != com.pockethomestead.transfer.TransferNode.NodeType.PICKUP) return 0;
        BaseChestBlockEntity target = findNodeChest(targetNode, serverLevel);
        TransferResult result;
        if (target != null && !target.isRemoved()) {
            result = isFluidScope(scopePort)
                    ? transferFluidsTo(target, scopePort, nextPath, gameTime, routeBudget, canVoid)
                    : transferItemsTo(target, scopePort, nextPath, gameTime, routeBudget, canVoid);
        } else if (canVoid) {
            result = isFluidScope(scopePort)
                    ? voidFilteredFluids(scopePort, nextPath, gameTime, routeBudget)
                    : voidFilteredItems(scopePort, nextPath, gameTime, routeBudget);
        } else {
            result = new TransferResult(Map.of(), TransferBlockReason.RECEIVER, firstMatchingResourceId(scopePort));
        }
        recordTransferResult(graph, serverLevel, nextPath, gameTime, result);
        return result.moved();
    }

    private int followOutgoingEdges(TransferGraph graph, ServerLevel serverLevel, String pageId, String nodeId,
                                    String scopePort, String cursorKey, List<TransferEdge> path, Set<String> visitedNodes,
                                    boolean canVoid, int routeBudget) {
        if (routeBudget <= 0) return 0;
        List<TransferEdge> primaryEdges = rotatedOutgoingForScope(graph, nodeId, scopePort, cursorKey + "|PRIMARY", false);
        List<TransferEdge> trashEdges = rotatedOutgoingForScope(graph, nodeId, scopePort, cursorKey + "|TRASH", true);
        int moved = followOutgoingPhase(graph, serverLevel, pageId, nodeId, scopePort, cursorKey + "|PRIMARY",
                primaryEdges, path, visitedNodes, canVoid && trashEdges.isEmpty(), routeBudget);
        if (moved < routeBudget) {
            moved += followOutgoingPhase(graph, serverLevel, pageId, nodeId, scopePort, cursorKey + "|TRASH",
                    trashEdges, path, visitedNodes, false, routeBudget - moved);
        }
        return moved;
    }

    private int followOutgoingPhase(TransferGraph graph, ServerLevel serverLevel, String pageId, String nodeId,
                                    String scopePort, String cursorKey, List<TransferEdge> edges,
                                    List<TransferEdge> path, Set<String> visitedNodes, boolean canVoid, int routeBudget) {
        int moved = 0;
        for (int i = 0; i < edges.size() && moved < routeBudget; i++) {
            TransferEdge next = edges.get(i);
            String nextScope = intersectPorts(scopePort, next.getFromPortKey());
            if (nextScope == null) continue;
            int branchBudget = fairBranchBudget(nextScope, edges.subList(i, edges.size()), routeBudget - moved);
            int branchMoved = followTransferEdge(graph, serverLevel, pageId, next, nextScope, path, new HashSet<>(visitedNodes), canVoid, branchBudget);
            if (branchMoved > 0) {
                advanceCursor(nodeId, cursorKey, edges, next);
                moved += branchMoved;
            }
        }
        return moved;
    }

    private void recordTransferResult(TransferGraph graph, ServerLevel serverLevel, List<TransferEdge> nextPath,
                                      long gameTime, TransferResult result) {
        if (result.moved() > 0) {
            for (Map.Entry<String, Integer> moved : result.movedByItem().entrySet()) {
                for (TransferEdge pathEdge : nextPath) pathEdge.recordMoved(gameTime, moved.getKey(), moved.getValue());
            }
            if (recordRerouteFlow(graph, nextPath, gameTime, result.movedByItem())) {
                TransferGraphStorage.get(serverLevel.getServer()).setDirty();
            }
        }
        if (result.reason() == TransferBlockReason.SOURCE) {
            if (result.blockItemId() != null) {
                for (TransferEdge pathEdge : nextPath) pathEdge.recordSourceBlocked(gameTime, result.blockItemId());
            }
        } else if (result.reason() == TransferBlockReason.RECEIVER) {
            if (result.blockItemId() != null) {
                for (TransferEdge pathEdge : nextPath) pathEdge.recordReceiverBlocked(gameTime, result.blockItemId());
            }
        }
    }

    private boolean recordRerouteFlow(TransferGraph graph, List<TransferEdge> path, long gameTime, Map<String, Integer> movedByItem) {
        if (movedByItem.isEmpty()) return false;
        boolean recorded = false;
        for (TransferEdge edge : path) {
            var to = graph.getNode(edge.getToNodeId());
            if (to != null && to.getNodeType() == com.pockethomestead.transfer.TransferNode.NodeType.REROUTE) {
                for (Map.Entry<String, Integer> moved : movedByItem.entrySet()) {
                    to.recordFlowInput(gameTime, moved.getKey(), moved.getValue());
                    recorded = true;
                }
            }
            var from = graph.getNode(edge.getFromNodeId());
            if (from != null && from.getNodeType() == com.pockethomestead.transfer.TransferNode.NodeType.REROUTE) {
                for (Map.Entry<String, Integer> moved : movedByItem.entrySet()) {
                    from.recordFlowOutput(gameTime, moved.getKey(), moved.getValue());
                    recorded = true;
                }
            }
        }
        return recorded;
    }

    private List<TransferEdge> rotatedOutgoingForScope(TransferGraph graph, String nodeId, String scopePort, String cursorKey, boolean trashTargets) {
        List<TransferEdge> edges = new ArrayList<>();
        for (TransferEdge edge : graph.outgoing(nodeId)) {
            if (intersectPorts(scopePort, edge.getFromPortKey()) != null && isTrashTarget(graph, edge) == trashTargets) edges.add(edge);
        }
        if (edges.size() <= 1) return edges;
        String fullCursorKey = nodeId + "|" + cursorKey;
        int start = Math.floorMod(transferRouteCursor.getOrDefault(fullCursorKey, 0), edges.size());
        List<TransferEdge> rotated = new ArrayList<>(edges.size());
        for (int i = 0; i < edges.size(); i++) rotated.add(edges.get((start + i) % edges.size()));
        return rotated;
    }

    private boolean isTrashTarget(TransferGraph graph, TransferEdge edge) {
        var target = graph.getNode(edge.getToNodeId());
        return target != null && target.getNodeType() == com.pockethomestead.transfer.TransferNode.NodeType.TRASH;
    }

    private String intersectPorts(String current, String next) {
        if (current == null || current.isBlank()) current = TransferEdge.PORT_ALL;
        if (next == null || next.isBlank()) next = TransferEdge.PORT_ALL;
        if (TransferEdge.PORT_ALL.equals(current)) return isFluidScope(next) ? null : next;
        if (TransferEdge.PORT_ALL.equals(next)) return isFluidScope(current) ? null : current;
        if (TransferEdge.FLUID_ALL.equals(current) && isFluidScope(next)) return next;
        if (TransferEdge.FLUID_ALL.equals(next) && isFluidScope(current)) return current;
        return current.equals(next) ? current : null;
    }

    private void advanceCursor(String nodeId, String key, List<TransferEdge> rotatedEdges, TransferEdge used) {
        if (rotatedEdges.isEmpty()) return;
        int index = rotatedEdges.indexOf(used);
        if (index < 0) return;
        String cursorKey = nodeId + "|" + key;
        int previous = transferRouteCursor.getOrDefault(cursorKey, 0);
        transferRouteCursor.put(cursorKey, previous + index + 1);
    }

    private int pathBudget(List<TransferEdge> path, long gameTime, int routeBudget, String itemId) {
        int budget = routeBudget;
        for (TransferEdge edge : path) {
            budget = Math.min(budget, edge.remainingRateBudget(gameTime, itemId));
        }
        return budget;
    }

    private int fairBranchBudget(String branchScope, List<TransferEdge> remainingEdges, int maxBudget) {
        if (maxBudget <= 1) return maxBudget;
        int available = matchingSourceCount(branchScope);
        if (available <= 0) return 0;
        int branches = 0;
        for (TransferEdge edge : remainingEdges) {
            if (edge.isEnabled() && intersectPorts(branchScope, edge.getFromPortKey()) != null) branches++;
        }
        if (branches <= 1) return Math.min(maxBudget, available);
        int fair = (available + branches - 1) / branches;
        return Math.max(1, Math.min(maxBudget, fair));
    }

    private int matchingSourceCount(String scopePort) {
        if (isFluidScope(scopePort)) return matchingFluidSourceCount(scopePort);
        int total = 0;
        for (StoredItemStack stored : itemStorage) {
            if (stored.count() > 0 && portAllows(scopePort, stored.item())) total += stored.count();
        }
        return total;
    }

    private int matchingFluidSourceCount(String scopePort) {
        int total = 0;
        for (Map.Entry<Fluid, Integer> entry : fluidStorage.entrySet()) {
            if (entry.getValue() > 0 && portAllowsFluid(scopePort, entry.getKey())) total += entry.getValue();
        }
        return total;
    }

    /**
     * 向目标箱子传输物品（尽最大可能传输，无速率限制）
     * @param target 目标箱子
     * @param canVoid 当目标满时是否虚空多余物品
     */
    private TransferResult transferItemsTo(BaseChestBlockEntity target, String scopePort, List<TransferEdge> path,
                                           long gameTime, int routeBudget, boolean canVoid) {
        // 快照物品列表避免并发修改
        List<StoredItemStack> entries = getStoredItems();
        int remainingRouteBudget = routeBudget;
        Map<String, Integer> movedByItem = new LinkedHashMap<>();
        boolean matchedSourceItem = false;
        boolean rateLimitedOnly = false;
        TransferBlockReason blockReason = TransferBlockReason.NONE;
        String blockItemId = null;
        if (target.getRemainingCapacity() <= 0) {
            return canVoid ? voidFilteredItems(scopePort, path, gameTime, routeBudget)
                    : new TransferResult(Map.of(), TransferBlockReason.RECEIVER, firstMatchingItemId(scopePort));
        }

        for (StoredItemStack entry : entries) {
            Item item = entry.item();
            int count = entry.count();
            if (count <= 0) continue;
            if (!portAllows(scopePort, item)) continue;
            matchedSourceItem = true;
            String itemId = itemId(item);
            if (itemId == null) continue;

            int itemBudget = pathBudget(path, gameTime, remainingRouteBudget, itemId);
            if (itemBudget <= 0) {
                rateLimitedOnly = true;
                continue;
            }
            int toTransfer = Math.min(count, itemBudget);
            toTransfer = Math.min(toTransfer, target.getRemainingCapacity());

            if (toTransfer > 0) {
                ItemStack moving = entry.prototype();
                int removed = removeItem(moving, toTransfer);
                if (removed <= 0) continue;
                int accepted = target.addItem(moving, removed);
                if (accepted < removed) addItem(moving, removed - accepted);
                if (accepted <= 0) continue;
                toTransfer = accepted;
                movedByItem.merge(itemId, toTransfer, Integer::sum);
                remainingRouteBudget -= toTransfer;

                if (target.getRemainingCapacity() <= 0) {
                    if (canVoid && remainingRouteBudget > 0) {
                        TransferResult voided = voidFilteredItems(scopePort, path, gameTime, remainingRouteBudget);
                        for (Map.Entry<String, Integer> moved : voided.movedByItem().entrySet()) movedByItem.merge(moved.getKey(), moved.getValue(), Integer::sum);
                        remainingRouteBudget -= voided.moved();
                        blockReason = voided.reason();
                        blockItemId = voided.blockItemId();
                    } else if (remainingRouteBudget > 0) {
                        blockReason = TransferBlockReason.RECEIVER;
                        blockItemId = itemId;
                    }
                    break;
                }
                if (remainingRouteBudget <= 0) break;
            }
        }
        if (movedByItem.isEmpty()) {
            if (rateLimitedOnly) return new TransferResult(Map.of(), TransferBlockReason.NONE, null);
            return new TransferResult(Map.of(), matchedSourceItem ? TransferBlockReason.RECEIVER : TransferBlockReason.SOURCE,
                    matchedSourceItem ? firstMatchingItemId(scopePort) : scopedItemId(scopePort));
        }
        if (remainingRouteBudget <= 0) return new TransferResult(movedByItem, TransferBlockReason.NONE, null);
        return new TransferResult(movedByItem, blockReason != TransferBlockReason.NONE ? blockReason : TransferBlockReason.SOURCE,
                blockItemId != null ? blockItemId : lastMovedItemId(movedByItem));
    }

    private TransferResult transferFluidsTo(BaseChestBlockEntity target, String scopePort, List<TransferEdge> path,
                                            long gameTime, int routeBudget, boolean canVoid) {
        List<Map.Entry<Fluid, Integer>> entries = new ArrayList<>(fluidStorage.entrySet());
        entries.sort(Comparator.comparing(entry -> net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(entry.getKey()).toString()));
        int remainingRouteBudget = routeBudget;
        Map<String, Integer> movedByFluid = new LinkedHashMap<>();
        boolean matchedSourceFluid = false;
        boolean rateLimitedOnly = false;
        TransferBlockReason blockReason = TransferBlockReason.NONE;
        String blockFluidId = null;
        if (target.getRemainingFluidCapacityMb() <= 0) {
            return canVoid ? voidFilteredFluids(scopePort, path, gameTime, routeBudget)
                    : new TransferResult(Map.of(), TransferBlockReason.RECEIVER, firstMatchingFluidId(scopePort));
        }

        for (Map.Entry<Fluid, Integer> entry : entries) {
            Fluid fluid = entry.getKey();
            int amount = entry.getValue();
            if (amount <= 0 || !portAllowsFluid(scopePort, fluid)) continue;
            matchedSourceFluid = true;
            String fluidId = fluidId(fluid);
            if (fluidId == null) continue;
            int fluidBudget = pathBudget(path, gameTime, remainingRouteBudget, fluidId);
            if (fluidBudget <= 0) {
                rateLimitedOnly = true;
                continue;
            }
            int toTransfer = Math.min(amount, fluidBudget);
            toTransfer = Math.min(toTransfer, target.getRemainingFluidCapacityMb());
            if (toTransfer > 0) {
                int removed = removeFluid(fluid, toTransfer);
                if (removed <= 0) continue;
                int accepted = target.addFluid(fluid, removed);
                if (accepted < removed) addFluid(fluid, removed - accepted);
                if (accepted <= 0) continue;
                movedByFluid.merge(fluidId, accepted, Integer::sum);
                remainingRouteBudget -= accepted;

                if (target.getRemainingFluidCapacityMb() <= 0) {
                    if (canVoid && remainingRouteBudget > 0) {
                        TransferResult voided = voidFilteredFluids(scopePort, path, gameTime, remainingRouteBudget);
                        for (Map.Entry<String, Integer> moved : voided.movedByItem().entrySet()) movedByFluid.merge(moved.getKey(), moved.getValue(), Integer::sum);
                        remainingRouteBudget -= voided.moved();
                        blockReason = voided.reason();
                        blockFluidId = voided.blockItemId();
                    } else if (remainingRouteBudget > 0) {
                        blockReason = TransferBlockReason.RECEIVER;
                        blockFluidId = fluidId;
                    }
                    break;
                }
                if (remainingRouteBudget <= 0) break;
            }
        }
        if (movedByFluid.isEmpty()) {
            if (rateLimitedOnly) return new TransferResult(Map.of(), TransferBlockReason.NONE, null);
            return new TransferResult(Map.of(), matchedSourceFluid ? TransferBlockReason.RECEIVER : TransferBlockReason.SOURCE,
                    matchedSourceFluid ? firstMatchingFluidId(scopePort) : scopedFluidId(scopePort));
        }
        if (remainingRouteBudget <= 0) return new TransferResult(movedByFluid, TransferBlockReason.NONE, null);
        return new TransferResult(movedByFluid, blockReason != TransferBlockReason.NONE ? blockReason : TransferBlockReason.SOURCE,
                blockFluidId != null ? blockFluidId : lastMovedItemId(movedByFluid));
    }

    /**
     * 虚空所有通过筛选的物品（整箱清空）
     */
    private TransferResult voidFilteredItems(String scopePort, List<TransferEdge> path, long gameTime, int routeBudget) {
        boolean changed = false;
        int remainingRouteBudget = routeBudget;
        boolean matchedSourceItem = false;
        boolean rateLimitedOnly = false;
        Map<String, Integer> movedByItem = new LinkedHashMap<>();
        List<StoredItemStack> entries = getStoredItems();
        for (StoredItemStack entry : entries) {
            if (!portAllows(scopePort, entry.item())) continue;
            matchedSourceItem = true;
            String itemId = itemId(entry.item());
            if (itemId == null) continue;
            int itemBudget = pathBudget(path, gameTime, remainingRouteBudget, itemId);
            if (itemBudget <= 0) {
                rateLimitedOnly = true;
                continue;
            }
            int removed = removeItem(entry.prototype(), Math.min(entry.count(), itemBudget));
            if (removed > 0) {
                changed = true;
                movedByItem.merge(itemId, removed, Integer::sum);
                remainingRouteBudget -= removed;
                if (remainingRouteBudget <= 0) break;
            }
        }
        if (changed) setChanged();
        if (!movedByItem.isEmpty()) {
            return new TransferResult(movedByItem, remainingRouteBudget > 0 ? TransferBlockReason.SOURCE : TransferBlockReason.NONE,
                    remainingRouteBudget > 0 ? lastMovedItemId(movedByItem) : null);
        }
        if (rateLimitedOnly) return new TransferResult(Map.of(), TransferBlockReason.NONE, null);
        return new TransferResult(Map.of(), TransferBlockReason.SOURCE, matchedSourceItem ? firstMatchingItemId(scopePort) : scopedItemId(scopePort));
    }

    private TransferResult voidFilteredFluids(String scopePort, List<TransferEdge> path, long gameTime, int routeBudget) {
        boolean changed = false;
        int remainingRouteBudget = routeBudget;
        boolean matchedSourceFluid = false;
        boolean rateLimitedOnly = false;
        Map<String, Integer> movedByFluid = new LinkedHashMap<>();
        List<Map.Entry<Fluid, Integer>> entries = new ArrayList<>(fluidStorage.entrySet());
        entries.sort(Comparator.comparing(entry -> net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(entry.getKey()).toString()));
        for (Map.Entry<Fluid, Integer> entry : entries) {
            if (!portAllowsFluid(scopePort, entry.getKey())) continue;
            matchedSourceFluid = true;
            String fluidId = fluidId(entry.getKey());
            if (fluidId == null) continue;
            int fluidBudget = pathBudget(path, gameTime, remainingRouteBudget, fluidId);
            if (fluidBudget <= 0) {
                rateLimitedOnly = true;
                continue;
            }
            int removed = removeFluid(entry.getKey(), Math.min(entry.getValue(), fluidBudget));
            if (removed > 0) {
                changed = true;
                movedByFluid.merge(fluidId, removed, Integer::sum);
                remainingRouteBudget -= removed;
                if (remainingRouteBudget <= 0) break;
            }
        }
        if (changed) setChanged();
        if (!movedByFluid.isEmpty()) {
            return new TransferResult(movedByFluid, remainingRouteBudget > 0 ? TransferBlockReason.SOURCE : TransferBlockReason.NONE,
                    remainingRouteBudget > 0 ? lastMovedItemId(movedByFluid) : null);
        }
        if (rateLimitedOnly) return new TransferResult(Map.of(), TransferBlockReason.NONE, null);
        return new TransferResult(Map.of(), TransferBlockReason.SOURCE, matchedSourceFluid ? firstMatchingFluidId(scopePort) : scopedFluidId(scopePort));
    }

    private boolean portAllows(String scopePort, Item item) {
        if (TransferEdge.PORT_ALL.equals(scopePort)) return true;
        if (scopePort == null || !scopePort.startsWith(TransferEdge.ITEM_PREFIX)) return false;
        net.minecraft.resources.ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
        return id != null && scopePort.substring(TransferEdge.ITEM_PREFIX.length()).equals(id.toString());
    }

    private String itemId(Item item) {
        net.minecraft.resources.ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
        return id == null ? null : TransferEdge.ITEM_PREFIX + id;
    }

    private String scopedItemId(String scopePort) {
        return scopePort != null && scopePort.startsWith(TransferEdge.ITEM_PREFIX) ? scopePort : null;
    }

    private String firstMatchingItemId(String scopePort) {
        for (StoredItemStack stored : itemStorage) {
            if (stored.count() > 0 && portAllows(scopePort, stored.item())) return itemId(stored.item());
        }
        return scopedItemId(scopePort);
    }

    private boolean isFluidScope(String scopePort) {
        return scopePort != null && scopePort.startsWith(TransferEdge.FLUID_PREFIX);
    }

    private boolean portAllowsFluid(String scopePort, Fluid fluid) {
        if (TransferEdge.FLUID_ALL.equals(scopePort)) return true;
        if (scopePort == null || !scopePort.startsWith(TransferEdge.FLUID_PREFIX)) return false;
        net.minecraft.resources.ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(fluid);
        return id != null && scopePort.substring(TransferEdge.FLUID_PREFIX.length()).equals(id.toString());
    }

    private String fluidId(Fluid fluid) {
        net.minecraft.resources.ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(fluid);
        return id == null ? null : TransferEdge.FLUID_PREFIX + id;
    }

    private String scopedFluidId(String scopePort) {
        return scopePort != null && scopePort.startsWith(TransferEdge.FLUID_PREFIX) && !TransferEdge.FLUID_ALL.equals(scopePort) ? scopePort : null;
    }

    private String firstMatchingFluidId(String scopePort) {
        for (Map.Entry<Fluid, Integer> entry : fluidStorage.entrySet()) {
            if (entry.getValue() > 0 && portAllowsFluid(scopePort, entry.getKey())) return fluidId(entry.getKey());
        }
        return scopedFluidId(scopePort);
    }

    private String firstMatchingResourceId(String scopePort) {
        return isFluidScope(scopePort) ? firstMatchingFluidId(scopePort) : firstMatchingItemId(scopePort);
    }

    private String lastMovedItemId(Map<String, Integer> movedByItem) {
        String last = null;
        for (String itemId : movedByItem.keySet()) last = itemId;
        return last;
    }

    private BaseChestBlockEntity findNodeChest(com.pockethomestead.transfer.TransferNode node, ServerLevel currentLevel) {
        net.minecraft.resources.ResourceLocation dimLoc = net.minecraft.resources.ResourceLocation.tryParse(node.getDimensionKey());
        if (dimLoc == null) return null;
        ServerLevel targetLevel = currentLevel.getServer().getLevel(net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION,
                dimLoc
        ));
        if (targetLevel == null) return null;
        if (targetLevel.getBlockEntity(node.getPos()) instanceof BaseChestBlockEntity be
                && be.getChestType() == node.getType()
                && be.getChestId().equals(node.getChestId())) {
            return be;
        }
        return null;
    }

    private boolean hasGraphOutgoingEdges() {
        if (level == null || level.isClientSide || ownerUUID == null || chestId.isEmpty()) return false;
        if (!(level instanceof ServerLevel serverLevel)) return false;
        TransferGraph graph = TransferGraphStorage.get(serverLevel.getServer()).graphFor(ownerUUID);
        var node = graph.findNode(chestId, level.dimension().location().toString(), worldPosition, getChestType());
        return node != null && graph.hasOutgoing(node.getId());
    }

    // ===== NBT 序列化 =====

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider reg) {
        super.saveAdditional(tag, reg);

        // 保存容量系统
        ListTag itemList = new ListTag();
        for (StoredItemStack entry : itemStorage) {
            CompoundTag itemTag = new CompoundTag();
            itemTag.put("stack", entry.prototype().saveOptional(reg));
            itemTag.putInt("count", entry.count());
            itemList.add(itemTag);
        }
        tag.put("Items", itemList);

        // 保存流体容量系统（mB）
        ListTag fluidList = new ListTag();
        for (Map.Entry<Fluid, Integer> entry : fluidStorage.entrySet()) {
            CompoundTag fluidTag = new CompoundTag();
            net.minecraft.resources.ResourceLocation fluidId = net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(entry.getKey());
            fluidTag.putString("id", fluidId.toString());
            fluidTag.putInt("amount", entry.getValue());
            fluidList.add(fluidTag);
        }
        tag.put("Fluids", fluidList);

        // 保存绑定信息
        tag.putString("ChestId", chestId);
        tag.putString("BoundTargetId", boundTargetId);
        if (ownerUUID != null) tag.putUUID("Owner", ownerUUID);
        tag.putBoolean("TransferEnabled", transferEnabled);
        tag.putBoolean("VoidModeEnabled", voidModeEnabled);
        tag.putInt("TransferRateLimit", transferRateLimit);
        tag.putInt("SyncIntervalSeconds", syncIntervalSeconds);
        tag.putDouble("TrustScore", trustScore);

        // 保存筛选器
        ListTag filterList = new ListTag();
        for (Item item : allowedItems) {
            CompoundTag filterTag = new CompoundTag();
            net.minecraft.resources.ResourceLocation itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
            filterTag.putString("id", itemId.toString());
            filterList.add(filterTag);
        }
        tag.put("AllowedItems", filterList);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider reg) {
        super.loadAdditional(tag, reg);

        // 加载容量系统
        itemStorage.clear();
        ListTag itemList = tag.getList("Items", Tag.TAG_COMPOUND);
        for (Tag t : itemList) {
            CompoundTag itemTag = (CompoundTag) t;
            int count = itemTag.getInt("count");
            if (count <= 0) continue;
            if (itemTag.contains("stack")) {
                ItemStack stack = ItemStack.parseOptional(reg, itemTag.getCompound("stack"));
                if (!stack.isEmpty()) addLoadedItem(stack, count);
            } else {
                String itemId = itemTag.getString("id");
                net.minecraft.resources.ResourceLocation loc = net.minecraft.resources.ResourceLocation.tryParse(itemId);
                if (loc != null) {
                    Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(loc);
                    if (item != null && item != net.minecraft.world.item.Items.AIR) addLoadedItem(new ItemStack(item), count);
                }
            }
        }

        // 加载流体容量系统（mB）
        fluidStorage.clear();
        ListTag fluidList = tag.getList("Fluids", Tag.TAG_COMPOUND);
        for (Tag t : fluidList) {
            CompoundTag fluidTag = (CompoundTag) t;
            String fluidId = fluidTag.getString("id");
            int amount = fluidTag.getInt("amount");
            net.minecraft.resources.ResourceLocation loc = net.minecraft.resources.ResourceLocation.tryParse(fluidId);
            if (loc != null && amount > 0) {
                Fluid fluid = net.minecraft.core.registries.BuiltInRegistries.FLUID.get(loc);
                if (fluid != null && fluid != Fluids.EMPTY) {
                    fluidStorage.put(fluid, amount);
                }
            }
        }

        // 加载绑定信息
        chestId = tag.getString("ChestId");
        boundTargetId = tag.getString("BoundTargetId");
        if (tag.hasUUID("Owner")) ownerUUID = tag.getUUID("Owner");
        transferEnabled = tag.getBoolean("TransferEnabled");
        voidModeEnabled = tag.getBoolean("VoidModeEnabled");
        transferRateLimit = tag.getInt("TransferRateLimit");
        syncIntervalSeconds = Math.max(1, tag.getInt("SyncIntervalSeconds"));
        trustScore = tag.getDouble("TrustScore");

        // 加载筛选器
        allowedItems.clear();
        ListTag filterList = tag.getList("AllowedItems", Tag.TAG_COMPOUND);
        for (Tag t : filterList) {
            CompoundTag filterTag = (CompoundTag) t;
            String itemId = filterTag.getString("id");
            net.minecraft.resources.ResourceLocation loc = net.minecraft.resources.ResourceLocation.tryParse(itemId);
            if (loc != null) {
                Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(loc);
                if (item != null && item != net.minecraft.world.item.Items.AIR) {
                    allowedItems.add(item);
                }
            }
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider reg) {
        CompoundTag tag = super.getUpdateTag(reg);
        saveAdditional(tag, reg);
        return tag;
    }

    @Override
    public net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket getUpdatePacket() {
        return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
    }

    public boolean stillValid(Player player) {
        return player.distanceToSqr(worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5) <= 64.0;
    }
}
