package com.foldworks.blockentity;

import com.foldworks.block.AbstractFoldworksBlock;
import com.foldworks.config.ModConfig;
import com.foldworks.moving.MovingChestRegistry;
import com.foldworks.offline.OfflineChestSnapshotStorage;
import com.foldworks.production.ProductionStatsStorage;
import com.foldworks.registration.ModItems;
import com.foldworks.registry.ChestRegistryManager;
import com.foldworks.space.SpaceData;
import com.foldworks.space.SpaceManager;
import com.foldworks.suite.SuiteOrderSystem;
import com.foldworks.transfer.TransferEdge;
import com.foldworks.transfer.GraphKey;
import com.foldworks.transfer.TransferGraph;
import com.foldworks.transfer.TransferGraphStorage;
import com.foldworks.transfer.TransferNode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.BlastingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.item.crafting.SmokingRecipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

import java.util.*;

/**
 * 箱子基类 - 实现容量系统（而非固定格子）
 * 容量由基础配置与箱子内升级槽共同决定，物品种类不限。
 */
public abstract class BaseChestBlockEntity extends BlockEntity implements MenuProvider, FoldworksChestAccess {
    private static final String ITEM_DATA_ROOT = "FoldworksChest";
    public static final int UPGRADE_SLOT_COUNT = 6;
    public static final int STORAGE_UPGRADE_SLOT = 0;
    public static final int FLUID_UPGRADE_SLOT = 1;
    public static final int NETWORK_UPGRADE_SLOT = 2;
    public static final int ENERGY_UPGRADE_SLOT = 3;
    public static final int STRESS_UPGRADE_SLOT = 4;
    public static final int SUITE_UPGRADE_SLOT = 5;

    private enum TransferBlockReason { NONE, SOURCE, RECEIVER }
    private record TransferResult(Map<String, Integer> movedByItem, TransferBlockReason reason, String blockItemId) {
        private int moved() { return movedByItem.values().stream().mapToInt(Integer::intValue).sum(); }
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

    private static final int[] STRESS_OUTPUT_SPEED_OPTIONS = {0, 16, 32, 64, 128, 256};

    // 容量系统：完整 ItemStack 组件身份 -> 数量（总容量从ModConfig读取）
    protected final List<StoredItemStack> itemStorage = new ArrayList<>();
    private final int[] upgradeCounts = new int[UPGRADE_SLOT_COUNT];
    private final Map<String, Integer> transferRouteCursor = new HashMap<>();
    private final EnumMap<ResourceKind, EnumMap<RelativeSide, SideMode>> sideConfig = new EnumMap<>(ResourceKind.class);
    private int productionStatsSuppressionDepth = 0;
    private int offlineExternalStatsSuppressionDepth = 0;
    private int transferResourceCursor = 0;
    private int graphStressBandwidthUsed = 0;
    private long graphStressBandwidthGameTime = Long.MIN_VALUE;

    // 流体容量系统：Fluid -> mB（1000mB=1桶）
    protected final Map<Fluid, Integer> fluidStorage = new HashMap<>();
    protected int energyStored = 0;
    private int stressOutputSpeedRpm = 0;
    private boolean stressOutputReversed = false;
    private final com.foldworks.compat.item.FoldworksChestItemHandler itemHandler =
            new com.foldworks.compat.item.FoldworksChestItemHandler(this);
    private final com.foldworks.compat.fluid.FoldworksChestFluidHandler fluidHandler =
            new com.foldworks.compat.fluid.FoldworksChestFluidHandler(this);
    private final com.foldworks.compat.energy.FoldworksChestEnergyStorage energyHandler =
            new com.foldworks.compat.energy.FoldworksChestEnergyStorage(this);

    private final NonNullList<ItemStack> suiteFurnaceItems = NonNullList.withSize(3, ItemStack.EMPTY);
    private int suiteFurnaceLitTime;
    private int suiteFurnaceLitDuration;
    private int suiteFurnaceCookingProgress;
    private int suiteFurnaceCookingTotalTime;
    private int suiteFurnaceMode;
    private final SuiteOrderSystem suiteOrders = new SuiteOrderSystem(this);

    private record SuiteCookingMatch(RecipeHolder<? extends AbstractCookingRecipe> recipe, int mode) {
    }

    // 箱子ID（玩家自定义，用于传输图节点识别）
    protected String chestId = "";

    // 稳定箱子 UUID：玩家改名后仍可被工造终端等绑定引用追踪。
    protected UUID chestUUID = UUID.randomUUID();

    // 所有者UUID
    protected UUID ownerUUID = null;

    // 所属工域缓存：避免 canChest 热路径每次查 SpaceManager.dimensionIndex。
    // SpaceData.markDeleted() 会使缓存失效；空间删除后重新查找返回 null（非工域维度）。
    private SpaceData cachedContainingSpace;

    // 传输图权限层级
    protected GraphKey.Kind graphKind = GraphKey.Kind.PRIVATE;
    protected UUID graphTeamId = null;

    // 传送开关
    protected boolean transferEnabled = false;

    // 虚空模式开关
    protected boolean voidModeEnabled = false;

    // 箱子级离线快照接管开关。空间开关或此开关任一启用时，卸载后可由快照顶替运行。
    protected boolean offlineSnapshotEnabled = false;

    // 传输筛选器：允许传输的物品集合（空=全部允许）
    protected final Set<Item> allowedItems = new HashSet<>();

    // 传输速率限制（物品/tick，0=无限制）
    protected int transferRateLimit = 0;

    // 信任度机制占位，当前固定为可信。
    protected double trustScore = 1.0;

    // GUI 存货区滚动行偏移（服务端持有，供 VirtualChestContainer.refill 读取）
    private int viewScrollRow = 0;

    public int getViewScrollRow() { return viewScrollRow; }
    public void setViewScrollRow(int row) { this.viewScrollRow = Math.max(0, row); }

    // 标记位：Map 存储变更后需同步到 VirtualChestContainer
    public boolean storageDirty = false;

    // 防止重复注册（onLoad 可能被多次调用）
    private boolean registered = false;
    private boolean destroyedForOfflineSnapshot = false;

    // 用于检测方块被搬运（Mekanism Cardboard Box / Create 机械臂等）：
    // saveAdditional 写入当前坐标，loadAdditional 后 onLoad 比较保存值与当前 level/worldPosition，
    // 不一致则触发 ChestRegistryManager / TransferGraph / 离线快照 / 生产统计 的坐标迁移。
    private String lastKnownDimensionKey = "";
    private long lastKnownPosLong = Long.MIN_VALUE;
    private boolean relocationHandled = false;

    public BaseChestBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
        resetDefaultSideConfig();
    }

    @Override
    public BaseChestBlockEntity foldworksChest() {
        return this;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel serverLevel) {
            handleRelocationIfMoved(serverLevel);
            OfflineChestSnapshotStorage.get(serverLevel.getServer()).applySnapshotToLoadedChest(this, serverLevel.getGameTime());
        }
        registerIfReady();
    }

    /**
     * 检测方块是否被搬运（Mekanism Cardboard Box / Create 机械臂等）。
     * 比较 NBT 中保存的 lastKnownDimensionKey/lastKnownPosLong 与当前 level/worldPosition，
     * 不一致则触发各外部存储的坐标迁移，并强制失效 cachedContainingSpace（跨维度时尤为重要）。
     *
     * 必须在 applySnapshotToLoadedChest 与 registerIfReady 之前执行：前者按新坐标查找已迁移的快照，
     * 后者按新坐标注册。迁移是幂等的——若旧 BE 的 setRemoved 已先清理过旧位置，迁移退化为仅注册新位置。
     */
    private void handleRelocationIfMoved(ServerLevel serverLevel) {
        if (relocationHandled) return;
        relocationHandled = true;
        if (lastKnownPosLong == Long.MIN_VALUE || lastKnownDimensionKey.isEmpty()) return;
        if (level == null) return;
        String currentDimKey = level.dimension().location().toString();
        long currentPosLong = worldPosition.asLong();
        if (currentDimKey.equals(lastKnownDimensionKey) && currentPosLong == lastKnownPosLong) return;

        // 检测到搬运
        String oldDimKey = lastKnownDimensionKey;
        BlockPos oldPos = BlockPos.of(lastKnownPosLong);
        String newDimKey = currentDimKey;
        BlockPos newPos = worldPosition.immutable();

        boolean crossDimension = !oldDimKey.equals(newDimKey);
        if (crossDimension) {
            // 跨维度搬运：旧 cachedContainingSpace 指向旧维度空间，必须失效
            cachedContainingSpace = null;
        }

        MinecraftServer server = serverLevel.getServer();
        if (ownerUUID != null && !chestId.isEmpty()) {
            // 1. ChestRegistryManager 迁移（幂等，旧位置可能已被旧 BE 的 setRemoved 注销）
            ChestRegistryManager.getInstance().relocateChest(ownerUUID, chestId, oldDimKey, oldPos, newDimKey, newPos);
            // 2. TransferGraph 节点坐标迁移
            TransferGraphStorage.get(server).relocateChest(chestId, oldDimKey, oldPos, newDimKey, newPos);
            // 3. 离线快照迁移（含历史速率桶）
            OfflineChestSnapshotStorage.get(server).relocateSnapshot(ownerUUID, chestId, oldDimKey, oldPos, newDimKey, newPos);
            // 4. 生产统计 chestKey 迁移
            String oldChestKey = ProductionStatsStorage.chestKey(oldDimKey, oldPos);
            String newChestKey = ProductionStatsStorage.chestKey(newDimKey, newPos);
            ProductionStatsStorage.get(server).relocateChest(productionStatsScopeKey(), oldChestKey, newChestKey);
        }

        // 更新保存值为当前坐标，避免下次 onLoad 重复触发迁移
        lastKnownDimensionKey = newDimKey;
        lastKnownPosLong = currentPosLong;
        setChanged();
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
        ChestRegistryManager.getInstance().registerChest(ownerUUID, chestId, level, worldPosition);
        registered = true;
        refreshProductionInventorySnapshot();
        if (level instanceof ServerLevel serverLevel) {
            OfflineChestSnapshotStorage.get(serverLevel.getServer()).captureLoaded(this, serverLevel.getGameTime());
        }
    }

    @Override
    public void setRemoved() {
        if (isRemoved()) return;
        // 服务端卸载时从全局管理器注销
        if (level != null && !level.isClientSide && ownerUUID != null && !chestId.isEmpty()) {
            boolean moving = false;
            if (level instanceof ServerLevel serverLevel) {
                OfflineChestSnapshotStorage storage = OfflineChestSnapshotStorage.get(serverLevel.getServer());
                String dimKey = level.dimension().location().toString();
                moving = MovingChestRegistry.isMoving(dimKey, worldPosition, chestId);
                if (destroyedForOfflineSnapshot) storage.deleteSnapshot(dimKey, worldPosition);
                else if (moving) storage.captureMoving(this, serverLevel.getGameTime());
                else storage.captureUnloaded(this, serverLevel.getGameTime());
            }
            if (registered && !moving) {
                ChestRegistryManager.getInstance().unregisterChest(ownerUUID, chestId, level, worldPosition);
                registered = false;
            }
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

    public int getMaxItemCapacity() {
        return projectedMaxItemCapacity(getUpgradeCount(STORAGE_UPGRADE_SLOT));
    }

    /**
     * 获取剩余容量
     */
    public int getRemainingCapacity() {
        return Math.max(0, getMaxItemCapacity() - getUsedCapacity());
    }

    public int getUpgradeCount(int slot) {
        return slot >= 0 && slot < upgradeCounts.length ? upgradeCounts[slot] : 0;
    }

    public int[] getUpgradeCounts() {
        return Arrays.copyOf(upgradeCounts, upgradeCounts.length);
    }

    public Item getUpgradeItem(int slot) {
        return switch (slot) {
            case STORAGE_UPGRADE_SLOT -> ModItems.STORAGE_UPGRADE.get();
            case FLUID_UPGRADE_SLOT -> ModItems.FLUID_UPGRADE.get();
            case NETWORK_UPGRADE_SLOT -> ModItems.NETWORK_UPGRADE.get();
            case ENERGY_UPGRADE_SLOT -> ModItems.ENERGY_TRANSFER_UPGRADE.get();
            case STRESS_UPGRADE_SLOT -> ModItems.STRESS_UPGRADE.get();
            case SUITE_UPGRADE_SLOT -> ModItems.SUITE_UPGRADE.get();
            default -> null;
        };
    }

    public boolean isUpgradeSlotEnabled(int slot) {
        return getUpgradeItem(slot) != null;
    }

    public boolean canPlaceUpgrade(int slot, ItemStack stack) {
        Item expected = getUpgradeItem(slot);
        return expected != null && stack != null && !stack.isEmpty() && stack.getItem() == expected;
    }

    public int addUpgrade(int slot, ItemStack stack, int count) {
        if (!canPlaceUpgrade(slot, stack) || count <= 0) return 0;
        long next = (long) upgradeCounts[slot] + count;
        upgradeCounts[slot] = (int) Math.min(Integer.MAX_VALUE, next);
        if (slot == STRESS_UPGRADE_SLOT) updateStressAxisFromConfig();
        storageDirty = true;
        setChanged();
        return count;
    }

    public ItemStack removeUpgrade(int slot, int count) {
        Item item = getUpgradeItem(slot);
        if (item == null || count <= 0 || getUpgradeCount(slot) <= 0) return ItemStack.EMPTY;
        int removed = Math.min(count, upgradeCounts[slot]);
        if (!canRemoveUpgrade(slot, removed)) return ItemStack.EMPTY;
        upgradeCounts[slot] -= removed;
        if (slot == STRESS_UPGRADE_SLOT) updateStressAxisFromConfig();
        storageDirty = true;
        setChanged();
        return new ItemStack(item, removed);
    }

    public boolean canRemoveUpgrade(int slot, int count) {
        if (slot < 0 || slot >= upgradeCounts.length || count <= 0) return false;
        int removed = Math.min(count, upgradeCounts[slot]);
        if (removed <= 0) return false;
        int remainingUpgrades = upgradeCounts[slot] - removed;
        return switch (slot) {
            case STORAGE_UPGRADE_SLOT -> projectedMaxItemCapacity(remainingUpgrades) >= getUsedCapacity();
            case FLUID_UPGRADE_SLOT -> canFitFluidsWithUpgradeCount(remainingUpgrades);
            case ENERGY_UPGRADE_SLOT -> projectedMaxEnergyStored(remainingUpgrades) >= energyStored;
            case SUITE_UPGRADE_SLOT -> !hasSuiteFurnaceContent() && !suiteOrders.hasContent();
            default -> true;
        };
    }

    private int projectedMaxItemCapacity(int storageUpgradeCount) {
        long value = (long) ModConfig.BASE_CHEST_CAPACITY.get()
                + (long) Math.max(0, storageUpgradeCount) * ModConfig.STORAGE_UPGRADE_CAPACITY.get();
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, value));
    }

    private int projectedMaxFluidTypes(int fluidUpgradeCount) {
        long value = (long) ModConfig.BASE_CHEST_FLUID_TYPES.get()
                + (long) Math.max(0, fluidUpgradeCount) * ModConfig.FLUID_UPGRADE_TYPES.get();
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, value));
    }

    private int projectedMaxFluidCapacityPerTypeMb(int fluidUpgradeCount) {
        long value = (long) ModConfig.BASE_CHEST_FLUID_CAPACITY_MB.get()
                + (long) Math.max(0, fluidUpgradeCount) * ModConfig.FLUID_UPGRADE_CAPACITY_MB.get();
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, value));
    }

    private boolean canFitFluidsWithUpgradeCount(int fluidUpgradeCount) {
        int maxTypes = projectedMaxFluidTypes(fluidUpgradeCount);
        int maxPerType = projectedMaxFluidCapacityPerTypeMb(fluidUpgradeCount);
        int usedTypes = 0;
        for (int amount : fluidStorage.values()) {
            if (amount <= 0) continue;
            usedTypes++;
            if (amount > maxPerType) return false;
        }
        return usedTypes <= maxTypes;
    }

    public boolean hasNetworkUpgrade() {
        return getUpgradeCount(NETWORK_UPGRADE_SLOT) > 0;
    }

    public boolean hasEnergyUpgrade() {
        return getUpgradeCount(ENERGY_UPGRADE_SLOT) > 0;
    }

    public boolean hasStressUpgrade() {
        return getUpgradeCount(STRESS_UPGRADE_SLOT) > 0;
    }

    public boolean hasSuiteUpgrade() {
        return getUpgradeCount(SUITE_UPGRADE_SLOT) > 0;
    }

    public NonNullList<ItemStack> getSuiteFurnaceItems() {
        return suiteFurnaceItems;
    }

    public List<ItemStack> getSuiteFurnaceItemsForSync() {
        List<ItemStack> copy = new ArrayList<>();
        for (ItemStack stack : suiteFurnaceItems) copy.add(stack.copy());
        return copy;
    }

    public int getSuiteFurnaceLitTime() { return suiteFurnaceLitTime; }
    public int getSuiteFurnaceLitDuration() { return suiteFurnaceLitDuration; }
    public int getSuiteFurnaceCookingProgress() { return suiteFurnaceCookingProgress; }
    public int getSuiteFurnaceCookingTotalTime() { return suiteFurnaceCookingTotalTime; }
    public int getSuiteFurnaceMode() { return suiteFurnaceMode; }

    public void refreshSuiteFurnaceRecipe() {
        Optional<SuiteCookingMatch> match = findSuiteCookingRecipe(suiteFurnaceItems.get(0));
        suiteFurnaceMode = match.map(SuiteCookingMatch::mode).orElse(0);
        suiteFurnaceCookingTotalTime = match.map(value -> Math.max(1, value.recipe().value().getCookingTime())).orElse(0);
        if (suiteFurnaceCookingTotalTime == 0) suiteFurnaceCookingProgress = 0;
        setChanged();
    }

    public boolean hasSuiteFurnaceContent() {
        for (ItemStack stack : suiteFurnaceItems) {
            if (!stack.isEmpty()) return true;
        }
        return suiteFurnaceLitTime > 0 || suiteFurnaceCookingProgress > 0;
    }

    public SuiteOrderSystem getSuiteOrders() {
        return suiteOrders;
    }

    public int getNetworkBandwidthCapacity() {
        long value = (long) getUpgradeCount(NETWORK_UPGRADE_SLOT) * ModConfig.NETWORK_BANDWIDTH_PER_UPGRADE.get();
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, value));
    }

    public int getStressBandwidthUsed() {
        return currentGraphStressBandwidthUsed();
    }

    public int getRemainingTransferBandwidth() {
        return Math.max(0, getNetworkBandwidthCapacity() - currentGraphStressBandwidthUsed());
    }

    public int getStressTransferLimit() {
        if (!hasStressUpgrade()) return 0;
        long value = (long) getUpgradeCount(STRESS_UPGRADE_SLOT) * ModConfig.STRESS_UPGRADE_CAPACITY_SU.get();
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, value));
    }

    public int getStressOutputSpeedRpm() {
        return stressOutputSpeedRpm;
    }

    public void setStressOutputSpeedRpm(int rpm) {
        int normalized = normalizeStressOutputSpeed(rpm);
        if (stressOutputSpeedRpm == normalized) return;
        stressOutputSpeedRpm = normalized;
        updateStressAxisFromConfig();
        storageDirty = true;
        setChanged();
    }

    public boolean isStressOutputReversed() {
        return stressOutputReversed;
    }

    public void setStressOutputReversed(boolean reversed) {
        if (stressOutputReversed == reversed) return;
        stressOutputReversed = reversed;
        updateStressAxisFromConfig();
        storageDirty = true;
        setChanged();
    }

    public float configuredStressOutputSpeed(float sourceSpeed) {
        if (sourceSpeed == 0) return 0;
        float sign = Math.signum(sourceSpeed) * (stressOutputReversed ? -1.0f : 1.0f);
        float magnitude = stressOutputSpeedRpm > 0 ? stressOutputSpeedRpm : Math.abs(sourceSpeed);
        return sign * magnitude;
    }

    private int normalizeStressOutputSpeed(int rpm) {
        if (rpm <= 0) return 0;
        int best = STRESS_OUTPUT_SPEED_OPTIONS[1];
        int bestDistance = Math.abs(rpm - best);
        for (int i = 2; i < STRESS_OUTPUT_SPEED_OPTIONS.length; i++) {
            int option = STRESS_OUTPUT_SPEED_OPTIONS[i];
            int distance = Math.abs(rpm - option);
            if (distance < bestDistance) {
                best = option;
                bestDistance = distance;
            }
        }
        return best;
    }

    private int projectedMaxEnergyStored(int energyUpgradeCount) {
        long value = (long) ModConfig.BASE_CHEST_ENERGY_CAPACITY_FE.get()
                + (long) Math.max(0, energyUpgradeCount) * ModConfig.ENERGY_UPGRADE_CAPACITY_FE.get();
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, value));
    }

    public int getMaxEnergyStored() {
        return hasEnergyUpgrade() ? projectedMaxEnergyStored(getUpgradeCount(ENERGY_UPGRADE_SLOT)) : 0;
    }

    public int getEnergyTransferLimit() {
        if (!hasEnergyUpgrade()) return 0;
        long value = (long) ModConfig.BASE_CHEST_ENERGY_TRANSFER_FE.get()
                + (long) Math.max(0, getUpgradeCount(ENERGY_UPGRADE_SLOT)) * ModConfig.ENERGY_UPGRADE_TRANSFER_FE.get();
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, value));
    }

    private int graphTransferIntervalTicks() {
        return Math.max(1, ModConfig.TRANSFER_TICK_INTERVAL.get());
    }

    private BandwidthBudget newNormalTransferBudget() {
        return new BandwidthBudget(getNetworkBandwidthCapacity(), currentGraphStressBandwidthUsed());
    }

    private BandwidthBudget newStressTransferBudget() {
        return new BandwidthBudget(getNetworkBandwidthCapacity(), 0);
    }

    private int currentGraphStressBandwidthUsed() {
        if (!(level instanceof ServerLevel serverLevel)) return 0;
        long gameTime = serverLevel.getGameTime();
        if (graphStressBandwidthGameTime == Long.MIN_VALUE || gameTime - graphStressBandwidthGameTime > 2) return 0;
        return Math.max(0, Math.min(getNetworkBandwidthCapacity(), graphStressBandwidthUsed));
    }

    private void recordGraphStressBandwidthUse(long gameTime, int used) {
        graphStressBandwidthUsed = Math.max(0, Math.min(getNetworkBandwidthCapacity(), used));
        graphStressBandwidthGameTime = gameTime;
    }

    private static int maxAmountForBandwidth(String scopePort, int bandwidth) {
        if (bandwidth <= 0) return 0;
        if (isItemBandwidthScope(scopePort)) {
            return bandwidth / itemBandwidthCost();
        }
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
        if (scopePort != null && scopePort.startsWith(TransferEdge.FLUID_PREFIX)) {
            return Math.max(1, ModConfig.FLUID_MB_PER_BANDWIDTH.get());
        }
        if (TransferEdge.ENERGY_FE.equals(scopePort)) {
            return Math.max(1, ModConfig.ENERGY_FE_PER_BANDWIDTH.get());
        }
        if (TransferEdge.STRESS_SU.equals(scopePort)) {
            return Math.max(1, ModConfig.STRESS_SU_PER_BANDWIDTH.get());
        }
        return 1;
    }

    private static int itemBandwidthCost() {
        return Math.max(1, ModConfig.ITEM_BANDWIDTH_COST.get());
    }

    public int getEnergyStored() {
        return Math.min(energyStored, getMaxEnergyStored());
    }

    public int getRemainingEnergyCapacity() {
        return Math.max(0, getMaxEnergyStored() - getEnergyStored());
    }

    public int receiveEnergyInternal(int amount, boolean simulate) {
        if (!hasEnergyUpgrade() || amount <= 0) return 0;
        int accepted = Math.min(amount, Math.min(getEnergyTransferLimit(), getRemainingEnergyCapacity()));
        if (accepted > 0 && !simulate) {
            energyStored += accepted;
            storageDirty = true;
            setChanged();
            recordProductionEnergyChange(accepted, true);
        }
        return accepted;
    }

    public int extractEnergyInternal(int amount, boolean simulate) {
        if (!hasEnergyUpgrade() || amount <= 0) return 0;
        int extracted = Math.min(amount, Math.min(getEnergyTransferLimit(), getEnergyStored()));
        if (extracted > 0 && !simulate) {
            energyStored -= extracted;
            storageDirty = true;
            setChanged();
            recordProductionEnergyChange(extracted, false);
        }
        return extracted;
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
        List<StoredItemStack> items = new ArrayList<>();
        for (StoredItemStack stored : itemStorage) {
            items.add(new StoredItemStack(stored.prototype(), stored.count()));
        }
        items.sort(Comparator.comparing(StoredItemStack::sortKey));
        return Collections.unmodifiableList(items);
    }

    public List<StoredItemStack> getStoredItemsDirect() {
        return itemStorage;
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

    private int withoutProductionStats(java.util.function.IntSupplier action) {
        productionStatsSuppressionDepth++;
        try {
            return action.getAsInt();
        } finally {
            productionStatsSuppressionDepth--;
        }
    }

    private int withoutOfflineExternalStats(java.util.function.IntSupplier action) {
        offlineExternalStatsSuppressionDepth++;
        try {
            return action.getAsInt();
        } finally {
            offlineExternalStatsSuppressionDepth--;
        }
    }

    private void withoutOfflineExternalStats(Runnable action) {
        offlineExternalStatsSuppressionDepth++;
        try {
            action.run();
        } finally {
            offlineExternalStatsSuppressionDepth--;
        }
    }

    private int addItemWithoutProduction(ItemStack stack, int count) {
        return withoutProductionStats(() -> addItem(stack, count));
    }

    private int removeItemWithoutProduction(ItemStack stack, int count) {
        return withoutProductionStats(() -> removeItem(stack, count));
    }

    public int addItemFromGraph(ItemStack stack, int count) {
        return addItemWithoutProduction(stack, count);
    }

    public int removeItemFromGraph(ItemStack stack, int count) {
        return removeItemWithoutProduction(stack, count);
    }

    public void recordGraphProductionInput(Item item, int amount) {
        withoutOfflineExternalStats(() -> recordProductionInput(item, amount));
    }

    public void recordGraphProductionOutput(Item item, int amount) {
        withoutOfflineExternalStats(() -> recordProductionOutput(item, amount));
    }

    private int addFluidWithoutProduction(Fluid fluid, int amountMb) {
        return withoutProductionStats(() -> addFluid(fluid, amountMb));
    }

    private int removeFluidWithoutProduction(Fluid fluid, int amountMb) {
        return withoutProductionStats(() -> removeFluid(fluid, amountMb));
    }

    public int addFluidFromGraph(Fluid fluid, int amountMb) {
        return addFluidWithoutProduction(fluid, amountMb);
    }

    public int removeFluidFromGraph(Fluid fluid, int amountMb) {
        return removeFluidWithoutProduction(fluid, amountMb);
    }

    public int receiveEnergyFromGraph(int amount) {
        return withoutOfflineExternalStats(() -> receiveEnergyInternal(amount, false));
    }

    public int extractEnergyFromGraph(int amount) {
        return withoutOfflineExternalStats(() -> extractEnergyInternal(amount, false));
    }

    private int receiveEnergyWithoutProduction(int amount) {
        return withoutProductionStats(() -> receiveEnergyInternal(amount, false));
    }

    public void recordGraphProductionFluidInput(Fluid fluid, int amount) {
        withoutOfflineExternalStats(() -> recordProductionFluidChange(fluid, amount, true));
    }

    public void recordGraphProductionFluidOutput(Fluid fluid, int amount) {
        withoutOfflineExternalStats(() -> recordProductionFluidChange(fluid, amount, false));
    }

    private void recordProductionInput(Item item, int amount) {
        recordProductionChange(item, amount, true);
    }

    private void recordProductionOutput(Item item, int amount) {
        recordProductionChange(item, amount, false);
    }

    private void recordProductionChange(Item item, int amount, boolean input) {
        if (productionStatsSuppressionDepth > 0) return;
        if (level == null || level.isClientSide || ownerUUID == null || amount <= 0) return;
        if (!(level instanceof ServerLevel serverLevel)) return;
        net.minecraft.resources.ResourceLocation itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
        if (itemId == null) return;
        ProductionStatsStorage storage = ProductionStatsStorage.get(serverLevel.getServer());
        String key = productionChestKey();
        String scopeKey = productionStatsScopeKey();
        if (scopeKey.isBlank()) return;
        String resourceKey = TransferEdge.ITEM_PREFIX + itemId;
        if (input) storage.recordInput(scopeKey, key, resourceKey, amount, serverLevel.getGameTime());
        else storage.recordOutput(scopeKey, key, resourceKey, amount, serverLevel.getGameTime());
        if (offlineExternalStatsSuppressionDepth <= 0) {
            OfflineChestSnapshotStorage.get(serverLevel.getServer())
                    .recordExternalChange(this, resourceKey, amount, input, serverLevel.getGameTime());
        }
    }

    private void recordProductionFluidChange(Fluid fluid, int amount, boolean input) {
        if (productionStatsSuppressionDepth > 0) return;
        if (level == null || level.isClientSide || ownerUUID == null || amount <= 0) return;
        if (!(level instanceof ServerLevel serverLevel)) return;
        net.minecraft.resources.ResourceLocation fluidId = net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(fluid);
        if (fluidId == null) return;
        ProductionStatsStorage storage = ProductionStatsStorage.get(serverLevel.getServer());
        String key = productionChestKey();
        String scopeKey = productionStatsScopeKey();
        if (scopeKey.isBlank()) return;
        String resourceKey = TransferEdge.FLUID_PREFIX + fluidId;
        if (input) storage.recordInput(scopeKey, key, resourceKey, amount, serverLevel.getGameTime());
        else storage.recordOutput(scopeKey, key, resourceKey, amount, serverLevel.getGameTime());
        if (offlineExternalStatsSuppressionDepth <= 0) {
            OfflineChestSnapshotStorage.get(serverLevel.getServer())
                    .recordExternalChange(this, resourceKey, amount, input, serverLevel.getGameTime());
        }
    }

    private void recordProductionEnergyChange(int amount, boolean input) {
        if (productionStatsSuppressionDepth > 0) return;
        if (level == null || level.isClientSide || ownerUUID == null || amount <= 0) return;
        if (!(level instanceof ServerLevel serverLevel)) return;
        ProductionStatsStorage storage = ProductionStatsStorage.get(serverLevel.getServer());
        String key = productionChestKey();
        String scopeKey = productionStatsScopeKey();
        if (scopeKey.isBlank()) return;
        if (input) storage.recordInput(scopeKey, key, TransferEdge.ENERGY_FE, amount, serverLevel.getGameTime());
        else storage.recordOutput(scopeKey, key, TransferEdge.ENERGY_FE, amount, serverLevel.getGameTime());
        if (offlineExternalStatsSuppressionDepth <= 0) {
            OfflineChestSnapshotStorage.get(serverLevel.getServer())
                    .recordExternalChange(this, TransferEdge.ENERGY_FE, amount, input, serverLevel.getGameTime());
        }
    }

    private void refreshProductionInventorySnapshot() {
        if (level == null || level.isClientSide || ownerUUID == null) return;
        if (!(level instanceof ServerLevel serverLevel)) return;
        String scopeKey = productionStatsScopeKey();
        if (scopeKey.isBlank()) return;
        ProductionStatsStorage.get(serverLevel.getServer()).refreshChestInventory(scopeKey, productionChestKey(), productionResourceSnapshot());
    }

    public String productionChestKey() {
        if (level == null) return "";
        return ProductionStatsStorage.chestKey(level.dimension().location().toString(), worldPosition);
    }

    public String productionStatsScopeKey() {
        if (ownerUUID == null) return "";
        return switch (graphKind) {
            case PUBLIC -> "";
            case PRIVATE -> ProductionStatsStorage.privateScope(ownerUUID);
            case PROTECTED -> graphTeamId == null ? "" : ProductionStatsStorage.teamScope(graphTeamId);
            case SPACE -> {
                SpaceData space = getContainingSpace();
                yield space == null ? "" : ProductionStatsStorage.spaceScope(space.getSpaceId());
            }
        };
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
        if (getEnergyStored() > 0) items.merge(TransferEdge.ENERGY_FE, getEnergyStored(), Integer::sum);
        return items;
    }

    /** 获取当前已用流体容量（mB） */
    public int getUsedFluidCapacityMb() {
        return fluidStorage.values().stream().mapToInt(Integer::intValue).sum();
    }

    public int getMaxFluidTypes() {
        return projectedMaxFluidTypes(getUpgradeCount(FLUID_UPGRADE_SLOT));
    }

    public int getMaxFluidCapacityPerTypeMb() {
        return projectedMaxFluidCapacityPerTypeMb(getUpgradeCount(FLUID_UPGRADE_SLOT));
    }

    public int getMaxFluidCapacityMb() {
        long value = (long) getMaxFluidTypes() * getMaxFluidCapacityPerTypeMb();
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, value));
    }

    /** 获取剩余流体容量（mB） */
    public int getRemainingFluidCapacityMb() {
        long remaining = 0;
        int perType = getMaxFluidCapacityPerTypeMb();
        for (int amount : fluidStorage.values()) {
            remaining += Math.max(0, perType - amount);
        }
        int emptyTypes = Math.max(0, getMaxFluidTypes() - fluidStorage.size());
        remaining += (long) emptyTypes * perType;
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, remaining));
    }

    public int getRemainingFluidCapacityMb(Fluid fluid) {
        if (fluid == null || fluid == Fluids.EMPTY) return 0;
        int current = fluidStorage.getOrDefault(fluid, 0);
        if (current <= 0 && fluidStorage.size() >= getMaxFluidTypes()) return 0;
        return Math.max(0, getMaxFluidCapacityPerTypeMb() - current);
    }

    /** 尝试添加流体（尽力而为，单位 mB） */
    public int addFluid(Fluid fluid, int amountMb) {
        if (fluid == null || fluid == Fluids.EMPTY || amountMb <= 0) return 0;
        int toAdd = Math.min(amountMb, getRemainingFluidCapacityMb(fluid));
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

    private void resetDefaultSideConfig() {
        sideConfig.clear();
        for (ResourceKind kind : ResourceKind.values()) {
            EnumMap<RelativeSide, SideMode> modes = new EnumMap<>(RelativeSide.class);
            for (RelativeSide side : RelativeSide.values()) modes.put(side, SideMode.DISABLED);
            sideConfig.put(kind, modes);
        }
        // 新方块默认所有面为（物品，无）：上面循环已将 ITEM 各面设为 DISABLED，
        // 不再将 ITEM 默认设为 BOTH，玩家需手动配置面用途。
    }

    private void setDefault(ResourceKind kind, RelativeSide side, SideMode mode) {
        sideConfig.computeIfAbsent(kind, k -> new EnumMap<>(RelativeSide.class)).put(side, mode);
    }

    public SideMode getSideMode(ResourceKind kind, RelativeSide side) {
        EnumMap<RelativeSide, SideMode> modes = sideConfig.get(kind);
        if (modes == null) return SideMode.DISABLED;
        return modes.getOrDefault(side, SideMode.DISABLED);
    }

    public void setSideFunction(RelativeSide side, ResourceKind kind, SideMode mode) {
        if (kind == null || side == null || mode == null) return;
        if (kind == ResourceKind.STRESS && mode == SideMode.BOTH) mode = SideMode.INPUT;

        boolean stressTouched = kind == ResourceKind.STRESS || getSideMode(ResourceKind.STRESS, side) != SideMode.DISABLED;
        for (ResourceKind other : ResourceKind.values()) {
            setDefault(other, side, SideMode.DISABLED);
        }

        if (mode != SideMode.DISABLED) {
            if (kind == ResourceKind.STRESS) {
                EnumMap<RelativeSide, SideMode> stressModes = sideConfig.computeIfAbsent(ResourceKind.STRESS, k -> new EnumMap<>(RelativeSide.class));
                for (RelativeSide existing : RelativeSide.values()) {
                    if (existing == side) continue;
                    SideMode existingMode = stressModes.getOrDefault(existing, SideMode.DISABLED);
                    if ((mode.canInput() && existingMode.canInput()) || (mode.canOutput() && existingMode.canOutput())) {
                        stressModes.put(existing, SideMode.DISABLED);
                    }
                }
            }
            setDefault(kind, side, mode);
        }

        if (stressTouched) updateStressAxisFromConfig();
        markSideConfigChanged();
    }

    private void markSideConfigChanged() {
        storageDirty = true;
        setChanged();
        if (level == null) return;
        if (level.isClientSide) {
            requestModelDataUpdate();
            return;
        }

        com.foldworks.compat.create.CreateStressHooks.syncClientData(level, worldPosition);
        BlockState state = getBlockState();
        level.sendBlockUpdated(worldPosition, state, state, 11);
    }

    /**
     * 客户端专用：用 ChestSyncPacket 携带的 sideConfig 直接覆盖本地 sideConfig，
     * 不触发 setChanged/sendBlockUpdated，避免循环同步。服务端 setSideFunction 已经
     * 写入某个面的最终功能状态，客户端只需镜像该结果，
     * FoldworksChestRenderer 下一帧即可读到新配置并刷新面渲染。
     */
    public void applySideConfigFromSync(java.util.List<com.foldworks.network.ChestSyncPacket.SideConfigEntry> entries) {
        if (entries == null) return;
        resetDefaultSideConfig();
        for (com.foldworks.network.ChestSyncPacket.SideConfigEntry e : entries) {
            try {
                ResourceKind kind = ResourceKind.valueOf(e.kind());
                RelativeSide side = RelativeSide.valueOf(e.side());
                SideMode mode = SideMode.valueOf(e.mode());
                setDefault(kind, side, mode);
            } catch (IllegalArgumentException ignored) {
            }
        }
        refreshClientModelData();
    }

    @Override
    public void onDataPacket(Connection net, ClientboundBlockEntityDataPacket pkt, HolderLookup.Provider lookupProvider) {
        CompoundTag tag = pkt.getTag();
        if (!tag.isEmpty()) loadWithComponents(tag, lookupProvider);
        refreshClientModelData();
    }

    @Override
    public net.neoforged.neoforge.client.model.data.ModelData getModelData() {
        return com.foldworks.client.model.FoldworksChestModelData.from(this);
    }

    private void refreshClientModelData() {
        if (level == null || !level.isClientSide) return;
        requestModelDataUpdate();
        BlockState state = getBlockState();
        level.sendBlockUpdated(worldPosition, state, state, 8);
    }

    private void normalizeSingleFunctionSideConfig() {
        for (RelativeSide side : RelativeSide.values()) {
            ResourceKind activeKind = null;
            SideMode activeMode = SideMode.DISABLED;
            for (ResourceKind kind : ResourceKind.values()) {
                SideMode mode = getSideMode(kind, side);
                if (mode != SideMode.DISABLED) {
                    activeKind = kind;
                    activeMode = kind == ResourceKind.STRESS && mode == SideMode.BOTH ? SideMode.INPUT : mode;
                    break;
                }
            }
            for (ResourceKind kind : ResourceKind.values()) {
                setDefault(kind, side, kind == activeKind ? activeMode : SideMode.DISABLED);
            }
        }
    }

    private void updateStressAxisFromConfig() {
        if (level == null || level.isClientSide) return;
        Direction stressSide = getConfiguredStressWorldSide();
        if (stressSide == null) {
            com.foldworks.compat.create.CreateStressHooks.refreshStressKinetics(level, worldPosition);
            return;
        }
        com.foldworks.compat.create.CreateStressHooks.updateStressAxis(level, worldPosition, stressSide.getAxis());
    }

    public int configuredStressInputSides() {
        int count = 0;
        for (RelativeSide side : RelativeSide.values()) {
            if (getSideMode(ResourceKind.STRESS, side).canInput()) count++;
        }
        return count;
    }

    public int configuredStressOutputSides() {
        int count = 0;
        for (RelativeSide side : RelativeSide.values()) {
            if (getSideMode(ResourceKind.STRESS, side).canOutput()) count++;
        }
        return count;
    }

    public Direction getConfiguredStressInputWorldSide() {
        return getConfiguredStressWorldSide(true);
    }

    public Direction getConfiguredStressOutputWorldSide() {
        return getConfiguredStressWorldSide(false);
    }

    public Direction getConfiguredStressWorldSide() {
        Direction input = getConfiguredStressInputWorldSide();
        return input != null ? input : getConfiguredStressOutputWorldSide();
    }

    private Direction getConfiguredStressWorldSide(boolean input) {
        Direction front = getBlockState().getValue(AbstractFoldworksBlock.FACING);
        for (RelativeSide side : RelativeSide.values()) {
            SideMode mode = getSideMode(ResourceKind.STRESS, side);
            if (input ? mode.canInput() : mode.canOutput()) return side.toWorld(front);
        }
        return null;
    }

    public Map<ResourceKind, Map<RelativeSide, SideMode>> getSideConfigSnapshot() {
        EnumMap<ResourceKind, Map<RelativeSide, SideMode>> copy = new EnumMap<>(ResourceKind.class);
        for (ResourceKind kind : ResourceKind.values()) {
            copy.put(kind, new EnumMap<>(sideConfig.getOrDefault(kind, new EnumMap<>(RelativeSide.class))));
        }
        return Collections.unmodifiableMap(copy);
    }

    public com.foldworks.compat.item.FoldworksChestItemHandler getItemHandler() {
        return itemHandler;
    }

    public com.foldworks.compat.item.FoldworksChestItemHandler getItemHandler(Direction side) {
        return new com.foldworks.compat.item.FoldworksChestItemHandler(this,
                canAutomation(ResourceKind.ITEM, side, true),
                canAutomation(ResourceKind.ITEM, side, false));
    }

    public com.foldworks.compat.fluid.FoldworksChestFluidHandler getFluidHandler() {
        return fluidHandler;
    }

    public com.foldworks.compat.fluid.FoldworksChestFluidHandler getFluidHandler(Direction side) {
        return new com.foldworks.compat.fluid.FoldworksChestFluidHandler(this,
                canAutomation(ResourceKind.FLUID, side, true),
                canAutomation(ResourceKind.FLUID, side, false));
    }

    public com.foldworks.compat.energy.FoldworksChestEnergyStorage getEnergyHandler() {
        return energyHandler;
    }

    public com.foldworks.compat.energy.FoldworksChestEnergyStorage getEnergyHandler(Direction side) {
        return new com.foldworks.compat.energy.FoldworksChestEnergyStorage(this,
                canAutomation(ResourceKind.ENERGY, side, true),
                canAutomation(ResourceKind.ENERGY, side, false));
    }

    public boolean canStressInput(Direction side) {
        return hasStressUpgrade() && canAutomation(ResourceKind.STRESS, side, true);
    }

    public boolean canStressOutput(Direction side) {
        return hasStressUpgrade() && canAutomation(ResourceKind.STRESS, side, false);
    }

    private boolean canAutomation(ResourceKind kind, Direction side, boolean input) {
        if (side == null) return true;
        if (kind == ResourceKind.ENERGY && !hasEnergyUpgrade()) return false;
        if (kind == ResourceKind.STRESS && !hasStressUpgrade()) return false;
        Direction front = getBlockState().getValue(AbstractFoldworksBlock.FACING);
        SideMode mode = getSideMode(kind, RelativeSide.fromWorld(side, front));
        return input ? mode.canInput() : mode.canOutput();
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

    public UUID getChestUUID() {
        if (chestUUID == null) chestUUID = UUID.randomUUID();
        return chestUUID;
    }

    public UUID getOwnerUUID() { return ownerUUID; }
    public void setOwnerUUID(UUID uuid) { this.ownerUUID = uuid; setChanged(); }

    /**
     * 返回箱子所在维度所属的工域（null 表示非工域维度）。
     * 缓存查找结果，空间删除时通过 SpaceData.isDeleted() 失效。
     */
    public SpaceData getContainingSpace() {
        if (cachedContainingSpace != null && !cachedContainingSpace.isDeleted()) return cachedContainingSpace;
        if (level == null) return null;
        cachedContainingSpace = SpaceManager.getInstance().getSpaceByDimension(level.dimension().location());
        return cachedContainingSpace;
    }

    public GraphKey.Kind getGraphKind() { return graphKind; }
    public UUID getGraphTeamId() { return graphTeamId; }

    public GraphKey getGraphKey() {
        return switch (graphKind) {
            case PUBLIC -> GraphKey.publicGraph();
            case PROTECTED -> graphTeamId == null ? null : GraphKey.protectedGraph(graphTeamId);
            case SPACE -> {
                SpaceData space = level == null ? null : SpaceManager.getInstance().getSpaceByDimension(level.dimension().location());
                yield space == null ? null : GraphKey.spaceGraph(space.getSpaceId());
            }
            case PRIVATE -> ownerUUID == null ? null : GraphKey.privateGraph(ownerUUID);
        };
    }

    public void setGraphAccess(GraphKey.Kind kind, UUID teamId) {
        String oldProductionScope = productionStatsScopeKey();
        String chestKey = productionChestKey();
        GraphKey.Kind next = kind == null ? GraphKey.Kind.PRIVATE : kind;
        if (next == GraphKey.Kind.SPACE
                && (level == null || SpaceManager.getInstance().getSpaceByDimension(level.dimension().location()) == null)) {
            next = GraphKey.Kind.PRIVATE;
        }
        this.graphKind = next;
        this.graphTeamId = this.graphKind == GraphKey.Kind.PROTECTED ? teamId : null;
        String newProductionScope = productionStatsScopeKey();
        if (!oldProductionScope.isBlank() && !oldProductionScope.equals(newProductionScope)
                && level instanceof ServerLevel serverLevel && !chestKey.isBlank()) {
            ProductionStatsStorage.get(serverLevel.getServer()).clearChestGroup(oldProductionScope, chestKey);
        }
        setChanged();
    }

    public boolean isTransferEnabled() { return transferEnabled; }
    public void setTransferEnabled(boolean enabled) { this.transferEnabled = enabled; setChanged(); }

    public boolean isVoidModeEnabled() { return voidModeEnabled; }
    public void setVoidModeEnabled(boolean enabled) { this.voidModeEnabled = enabled; setChanged(); }

    public boolean isOfflineSnapshotEnabled() { return offlineSnapshotEnabled; }
    public void setOfflineSnapshotEnabled(boolean enabled) { this.offlineSnapshotEnabled = enabled; setChanged(); }

    public void markDestroyedForOfflineSnapshot() {
        destroyedForOfflineSnapshot = true;
    }

    public void replaceStorageFromOfflineSnapshot(List<StoredItemStack> items, Map<Fluid, Integer> fluids, int energy) {
        itemStorage.clear();
        if (items != null) {
            for (StoredItemStack entry : items) {
                if (entry != null && entry.count() > 0) addLoadedItem(entry.prototype(), entry.count());
            }
        }
        fluidStorage.clear();
        if (fluids != null) {
            for (Map.Entry<Fluid, Integer> entry : fluids.entrySet()) {
                if (entry.getKey() != null && entry.getKey() != Fluids.EMPTY && entry.getValue() > 0) {
                    fluidStorage.put(entry.getKey(), Math.min(entry.getValue(), getMaxFluidCapacityPerTypeMb()));
                }
            }
        }
        energyStored = Math.max(0, Math.min(energy, getMaxEnergyStored()));
        storageDirty = true;
        setChanged();
        refreshProductionInventorySnapshot();
    }

    public Set<Item> getAllowedItems() { return allowedItems; }

    public int getTransferRateLimit() { return transferRateLimit; }
    public void setTransferRateLimit(int limit) { this.transferRateLimit = limit; setChanged(); }

    /** 计算距离下一次传输还有多少秒（用于UI倒计时） */
    public int getNextTransferSeconds() {
        if (!hasGraphOutgoingEdges()) return 0;
        int interval = graphTransferIntervalTicks();
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
        tickSuiteFurnace();
        if (level instanceof ServerLevel serverLevel) suiteOrders.tick(serverLevel);
        // 兜底：确保箱子已注册（新放置时 onLoad 早于 setPlacedBy）
        registerIfReady();
        if (ownerUUID == null) return;
        if (hasNetworkUpgrade() && hasStressUpgrade()) doStressLinkTick();
        if (!hasGraphOutgoingEdges()) return;

        tickCounter++;
        int interval = graphTransferIntervalTicks();
        int offset = Math.abs(worldPosition.hashCode()) % interval;
        if ((tickCounter + offset) % interval != 0) return;

        doTransferTick();
    }

    private void tickSuiteFurnace() {
        if (!(level instanceof ServerLevel)) return;
        if (!hasSuiteUpgrade() && !hasSuiteFurnaceContent()) return;

        SuiteCookingMatch match = findSuiteCookingRecipe(suiteFurnaceItems.get(0)).orElse(null);
        if (match == null) {
            suiteFurnaceMode = 0;
            suiteFurnaceCookingTotalTime = 0;
            suiteFurnaceCookingProgress = 0;
            if (suiteFurnaceLitTime > 0) {
                suiteFurnaceLitTime--;
                setChanged();
            }
            return;
        }

        suiteFurnaceMode = match.mode();
        suiteFurnaceCookingTotalTime = Math.max(1, match.recipe().value().getCookingTime());
        ItemStack result = match.recipe().value().assemble(new SingleRecipeInput(suiteFurnaceItems.get(0)), level.registryAccess());
        if (result.isEmpty() || !result.isItemEnabled(level.enabledFeatures()) || !canSuiteFurnaceOutputAccept(result)) {
            suiteFurnaceCookingProgress = 0;
            if (suiteFurnaceLitTime > 0) suiteFurnaceLitTime--;
            setChanged();
            return;
        }

        if (suiteFurnaceLitTime <= 0) {
            int burn = suiteFuelBurnDuration(suiteFurnaceItems.get(1));
            if (burn <= 0) {
                if (suiteFurnaceCookingProgress > 0) {
                    suiteFurnaceCookingProgress = Math.max(0, suiteFurnaceCookingProgress - 2);
                    setChanged();
                }
                return;
            }
            suiteFurnaceLitTime = burn;
            suiteFurnaceLitDuration = burn;
            consumeSuiteFurnaceFuel();
        }

        suiteFurnaceLitTime--;
        suiteFurnaceCookingProgress++;
        if (suiteFurnaceCookingProgress >= suiteFurnaceCookingTotalTime) {
            suiteFurnaceCookingProgress = 0;
            finishSuiteFurnaceRecipe(result);
            refreshSuiteFurnaceRecipe();
        }
        setChanged();
    }

    private void finishSuiteFurnaceRecipe(ItemStack result) {
        ItemStack output = suiteFurnaceItems.get(2);
        if (output.isEmpty()) {
            suiteFurnaceItems.set(2, result.copy());
        } else if (ItemStack.isSameItemSameComponents(output, result)) {
            output.grow(result.getCount());
        }
        ItemStack input = suiteFurnaceItems.get(0);
        input.shrink(1);
        if (input.isEmpty()) suiteFurnaceItems.set(0, ItemStack.EMPTY);
    }

    private void consumeSuiteFurnaceFuel() {
        ItemStack fuel = suiteFurnaceItems.get(1);
        if (fuel.isEmpty()) return;
        Item remainderItem = fuel.getItem().getCraftingRemainingItem();
        fuel.shrink(1);
        if (fuel.isEmpty()) suiteFurnaceItems.set(1, ItemStack.EMPTY);
        if (remainderItem != null && remainderItem != Items.AIR) {
            ItemStack remainder = new ItemStack(remainderItem);
            if (suiteFurnaceItems.get(1).isEmpty()) {
                suiteFurnaceItems.set(1, remainder);
            } else {
                int added = addItem(remainder, remainder.getCount());
                remainder.shrink(added);
                if (!remainder.isEmpty() && level instanceof ServerLevel serverLevel) {
                    net.minecraft.world.Containers.dropItemStack(serverLevel, worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5, remainder);
                }
            }
        }
    }

    private boolean canSuiteFurnaceOutputAccept(ItemStack result) {
        ItemStack output = suiteFurnaceItems.get(2);
        if (output.isEmpty()) return true;
        return ItemStack.isSameItemSameComponents(output, result)
                && output.getCount() + result.getCount() <= output.getMaxStackSize();
    }

    private int suiteFuelBurnDuration(ItemStack stack) {
        if (stack.isEmpty()) return 0;
        return AbstractFurnaceBlockEntity.getFuel().getOrDefault(stack.getItem(), 0);
    }

    private Optional<SuiteCookingMatch> findSuiteCookingRecipe(ItemStack input) {
        if (level == null || input.isEmpty()) return Optional.empty();
        SingleRecipeInput recipeInput = new SingleRecipeInput(input);
        Optional<RecipeHolder<BlastingRecipe>> blasting = level.getRecipeManager().getRecipeFor(RecipeType.BLASTING, recipeInput, level);
        if (blasting.isPresent()) return Optional.of(new SuiteCookingMatch(blasting.get(), 1));
        Optional<RecipeHolder<SmokingRecipe>> smoking = level.getRecipeManager().getRecipeFor(RecipeType.SMOKING, recipeInput, level);
        if (smoking.isPresent()) return Optional.of(new SuiteCookingMatch(smoking.get(), 2));
        Optional<RecipeHolder<SmeltingRecipe>> smelting = level.getRecipeManager().getRecipeFor(RecipeType.SMELTING, recipeInput, level);
        return smelting.map(recipe -> new SuiteCookingMatch(recipe, 3));
    }

    /**
     * 执行一次传输周期。统一箱子只要拥有网络升级和有效输出边即可作为源节点。
     */
    protected void doTransferTick() {
        if (!(level instanceof ServerLevel serverLevel)) return;
        if (!hasNetworkUpgrade()) return;
        if (itemStorage.isEmpty() && fluidStorage.isEmpty() && energyStored <= 0) return;
        BandwidthBudget bandwidth = newNormalTransferBudget();
        if (bandwidth.remaining() <= 0) return;

        GraphKey graphKey = getGraphKey();
        if (graphKey == null || !graphKey.isValid()) return;
        TransferGraph graph = TransferGraphStorage.get(serverLevel.getServer()).graphFor(graphKey);
        String dim = level.dimension().location().toString();
        var node = graph.findNode(chestId, dim, worldPosition);
        if (node == null || !node.isEnabled()) return;
        var page = graph.getPage(node.getPageId());
        if (page == null || !page.isEnabled()) return;

        boolean canVoid = ModConfig.VOID_ENABLED.get() && (voidModeEnabled || ModConfig.GLOBAL_VOID_MODE.get());
        String[] scopes = {TransferEdge.ITEM_ALL, TransferEdge.FLUID_ALL, TransferEdge.ENERGY_FE};
        String[] cursors = {"ROOT_ITEM", "ROOT_FLUID", "ROOT_ENERGY"};
        boolean[] available = {!itemStorage.isEmpty(), !fluidStorage.isEmpty(), energyStored > 0};
        boolean[] voidAllowed = {canVoid, canVoid, false};
        int start = Math.floorMod(transferResourceCursor++, scopes.length);
        for (int i = 0; i < scopes.length && bandwidth.remaining() > 0; i++) {
            int index = (start + i) % scopes.length;
            if (!available[index] || bandwidth.maxTransferable(scopes[index]) <= 0) continue;
            followOutgoingEdges(graph, serverLevel, node.getPageId(), node.getId(), scopes[index], cursors[index],
                    new ArrayList<>(), new HashSet<>(Set.of(node.getId())), voidAllowed[index], Integer.MAX_VALUE, bandwidth);
        }
    }

    private void doStressLinkTick() {
        if (!(level instanceof ServerLevel serverLevel)) return;
        long gameTime = serverLevel.getGameTime();
        recordGraphStressBandwidthUse(gameTime, 0);
        if (!hasNetworkUpgrade() || !hasStressUpgrade() || ownerUUID == null || chestId.isEmpty()) return;
        GraphKey graphKey = getGraphKey();
        if (graphKey == null || !graphKey.isValid()) return;
        TransferGraph graph = TransferGraphStorage.get(serverLevel.getServer()).graphFor(graphKey);
        String dim = level.dimension().location().toString();
        var node = graph.findNode(chestId, dim, worldPosition);
        if (node == null || !node.isEnabled()) return;
        var page = graph.getPage(node.getPageId());
        if (page == null || !page.isEnabled()) return;
        FoldworksStressEndpoint endpoint = stressEndpoint(this);
        if (endpoint == null || !endpoint.canSendGraphStress()) return;
        BandwidthBudget bandwidth = newStressTransferBudget();
        int stressBudget = Math.min(getStressTransferLimit(), Math.max(0, (int) endpoint.graphStressCapacity()));
        stressBudget = Math.min(stressBudget, bandwidth.maxTransferable(TransferEdge.STRESS_SU));
        if (stressBudget <= 0) return;
        followOutgoingEdges(graph, serverLevel, node.getPageId(), node.getId(), TransferEdge.STRESS_SU, "ROOT_STRESS",
                new ArrayList<>(), new HashSet<>(Set.of(node.getId())), false, stressBudget, bandwidth);
        recordGraphStressBandwidthUse(gameTime, bandwidth.used());
    }

    private int followTransferEdge(TransferGraph graph, ServerLevel serverLevel, String pageId, TransferEdge edge,
                                   String scopePort, List<TransferEdge> path, Set<String> visitedNodes, boolean canVoid,
                                   int routeBudget, BandwidthBudget bandwidth) {
        if (routeBudget <= 0 || bandwidth.remaining() <= 0) return 0;
        if (!edge.isEnabled() || !edge.getPageId().equals(pageId)) return 0;
        long gameTime = serverLevel.getGameTime();

        scopePort = intersectPorts(scopePort, edge.getFromPortKey());
        if (scopePort == null) return 0;

        var targetNode = graph.getNode(edge.getToNodeId());
        if (targetNode == null || !targetNode.isEnabled()) return 0;
        if (!visitedNodes.add(targetNode.getId())) return 0;

        path.add(edge);
        try {
            if (targetNode.getNodeType() == TransferNode.NodeType.LIMIT_GATE) {
                int gateBudget = limitGateBudget(graph, serverLevel, pageId, targetNode, scopePort, routeBudget);
                if (gateBudget <= 0) {
                    recordTransferResult(graph, serverLevel, path, gameTime,
                            new TransferResult(Map.of(), TransferBlockReason.RECEIVER, firstMatchingResourceId(scopePort)));
                    return 0;
                }
                return followOutgoingEdges(graph, serverLevel, pageId, targetNode.getId(), scopePort, scopePort,
                        path, visitedNodes, canVoid, gateBudget, bandwidth);
            }

            if (targetNode.getNodeType() == TransferNode.NodeType.JUMP_INPUT) {
                TransferNode output = linkedJumpOutput(graph, targetNode);
                if (output == null || !output.isEnabled() || !visitedNodes.add(output.getId())) return 0;
                try {
                    return followOutgoingEdges(graph, serverLevel, pageId, output.getId(), scopePort, scopePort,
                            path, visitedNodes, canVoid, routeBudget, bandwidth);
                } finally {
                    visitedNodes.remove(output.getId());
                }
            }

            if (targetNode.getNodeType() == com.foldworks.transfer.TransferNode.NodeType.REROUTE) {
                return followOutgoingEdges(graph, serverLevel, pageId, targetNode.getId(), scopePort, scopePort,
                        path, visitedNodes, canVoid, routeBudget, bandwidth);
            }

            if (targetNode.getNodeType() == com.foldworks.transfer.TransferNode.NodeType.TRASH) {
                if (isEnergyScope(scopePort) || isStressScope(scopePort)) return 0;
                TransferResult result = isFluidScope(scopePort)
                        ? voidFilteredFluids(scopePort, path, gameTime, routeBudget, bandwidth)
                        : voidFilteredItems(scopePort, path, gameTime, routeBudget, bandwidth);
                recordTransferResult(graph, serverLevel, path, gameTime, result);
                return result.moved();
            }

            if (targetNode.getNodeType() == com.foldworks.transfer.TransferNode.NodeType.PLAYER_INVENTORY) {
                TransferResult result = isFluidScope(scopePort) || isEnergyScope(scopePort) || isStressScope(scopePort)
                        ? new TransferResult(Map.of(), TransferBlockReason.RECEIVER, firstMatchingResourceId(scopePort))
                        : transferItemsToPlayer(targetNode, scopePort, path, gameTime, routeBudget, bandwidth);
                recordTransferResult(graph, serverLevel, path, gameTime, result);
                return result.moved();
            }

            if (targetNode.getNodeType() != com.foldworks.transfer.TransferNode.NodeType.CHEST) return 0;
            BaseChestBlockEntity target = findNodeChest(targetNode, serverLevel);
            TransferResult result;
            if (target != null && !target.isRemoved() && com.foldworks.transfer.TransferGraphAccess.chestMatchesGraph(target, graph.getKey())) {
                if (isEnergyScope(scopePort)) {
                    result = transferEnergyTo(target, path, gameTime, routeBudget, bandwidth);
                } else if (isStressScope(scopePort)) {
                    result = transferStressTo(target, path, gameTime, routeBudget, bandwidth);
                } else {
                    result = isFluidScope(scopePort)
                            ? transferFluidsTo(target, targetNode, scopePort, path, gameTime, routeBudget, canVoid, bandwidth)
                            : transferItemsTo(target, targetNode, scopePort, path, gameTime, routeBudget, canVoid, bandwidth);
                }
            } else if (findNodeSnapshot(targetNode, serverLevel, graph.getKey()) instanceof OfflineChestSnapshotStorage.Snapshot snapshot) {
                if (isEnergyScope(scopePort)) {
                    result = transferEnergyToSnapshot(serverLevel, snapshot, path, gameTime, routeBudget, bandwidth);
                } else if (isStressScope(scopePort)) {
                    result = transferStressToSnapshot(snapshot, path, gameTime, routeBudget, bandwidth);
                } else {
                    result = isFluidScope(scopePort)
                            ? transferFluidsToSnapshot(serverLevel, snapshot, targetNode, scopePort, path, gameTime, routeBudget, canVoid, bandwidth)
                            : transferItemsToSnapshot(serverLevel, snapshot, targetNode, scopePort, path, gameTime, routeBudget, canVoid, bandwidth);
                }
            } else if (canVoid) {
                result = isFluidScope(scopePort)
                        ? voidFilteredFluids(scopePort, path, gameTime, routeBudget, bandwidth)
                        : voidFilteredItems(scopePort, path, gameTime, routeBudget, bandwidth);
            } else {
                result = new TransferResult(Map.of(), TransferBlockReason.RECEIVER, firstMatchingResourceId(scopePort));
            }
            recordTransferResult(graph, serverLevel, path, gameTime, result);
            return result.moved();
        } finally {
            path.remove(path.size() - 1);
            visitedNodes.remove(targetNode.getId());
        }
    }

    private int followOutgoingEdges(TransferGraph graph, ServerLevel serverLevel, String pageId, String nodeId,
                                    String scopePort, String cursorKey, List<TransferEdge> path, Set<String> visitedNodes,
                                    boolean canVoid, int routeBudget, BandwidthBudget bandwidth) {
        if (routeBudget <= 0 || bandwidth.remaining() <= 0) return 0;
        List<TransferEdge> primaryEdges = rotatedOutgoingForScope(graph, nodeId, scopePort, cursorKey + "|PRIMARY", false);
        List<TransferEdge> trashEdges = rotatedOutgoingForScope(graph, nodeId, scopePort, cursorKey + "|TRASH", true);
        int moved = followOutgoingPhase(graph, serverLevel, pageId, nodeId, scopePort, cursorKey + "|PRIMARY",
                primaryEdges, path, visitedNodes, canVoid && trashEdges.isEmpty(), routeBudget, bandwidth);
        if (moved < routeBudget && bandwidth.remaining() > 0) {
            moved += followOutgoingPhase(graph, serverLevel, pageId, nodeId, scopePort, cursorKey + "|TRASH",
                    trashEdges, path, visitedNodes, false, routeBudget - moved, bandwidth);
        }
        return moved;
    }

    private int followOutgoingPhase(TransferGraph graph, ServerLevel serverLevel, String pageId, String nodeId,
                                    String scopePort, String cursorKey, List<TransferEdge> edges,
                                    List<TransferEdge> path, Set<String> visitedNodes, boolean canVoid,
                                    int routeBudget, BandwidthBudget bandwidth) {
        int moved = 0;
        for (int i = 0; i < edges.size() && moved < routeBudget && bandwidth.remaining() > 0; i++) {
            TransferEdge next = edges.get(i);
            String nextScope = intersectPorts(scopePort, next.getFromPortKey());
            if (nextScope == null) continue;
            int remainingBudget = Math.min(routeBudget - moved, bandwidth.maxTransferable(nextScope));
            int branchBudget = fairBranchBudget(nextScope, edges.subList(i, edges.size()), remainingBudget);
            int branchMoved = followTransferEdge(graph, serverLevel, pageId, next, nextScope, path, visitedNodes,
                    canVoid, branchBudget, bandwidth);
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
            if (to != null && to.getNodeType() == com.foldworks.transfer.TransferNode.NodeType.REROUTE) {
                for (Map.Entry<String, Integer> moved : movedByItem.entrySet()) {
                    to.recordFlowInput(gameTime, moved.getKey(), moved.getValue());
                    recorded = true;
                }
            }
            var from = graph.getNode(edge.getFromNodeId());
            if (from != null && from.getNodeType() == com.foldworks.transfer.TransferNode.NodeType.REROUTE) {
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
        return target != null && target.getNodeType() == com.foldworks.transfer.TransferNode.NodeType.TRASH;
    }

    private String intersectPorts(String current, String next) {
        if (current == null || current.isBlank()) current = TransferEdge.ITEM_ALL;
        if (next == null || next.isBlank()) next = TransferEdge.ITEM_ALL;
        if (TransferEdge.ITEM_ALL.equals(current)) return isFluidScope(next) || isEnergyScope(next) || isStressScope(next) ? null : next;
        if (TransferEdge.ITEM_ALL.equals(next)) return isFluidScope(current) || isEnergyScope(current) || isStressScope(current) ? null : current;
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
        if (isEnergyScope(scopePort)) return getEnergyStored();
        if (isStressScope(scopePort)) {
            FoldworksStressEndpoint endpoint = stressEndpoint(this);
            return endpoint == null ? 0 : Math.max(0, (int) endpoint.graphStressCapacity());
        }
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
    private TransferResult transferItemsTo(BaseChestBlockEntity target, com.foldworks.transfer.TransferNode targetNode, String scopePort, List<TransferEdge> path,
                                           long gameTime, int routeBudget, boolean canVoid, BandwidthBudget bandwidth) {
        // 快照物品列表避免并发修改
        List<StoredItemStack> entries = getStoredItems();
        int remainingRouteBudget = routeBudget;
        Map<String, Integer> movedByItem = new LinkedHashMap<>();
        boolean matchedSourceItem = false;
        boolean rateLimitedOnly = false;
        TransferBlockReason blockReason = TransferBlockReason.NONE;
        String blockItemId = null;
        if (target.getRemainingCapacity() <= 0) {
            return canVoid ? voidFilteredItems(scopePort, path, gameTime, routeBudget, bandwidth)
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
            if (!receiveFilterAllows(targetNode, itemId)) continue;

            int itemBudget = Math.min(pathBudget(path, gameTime, remainingRouteBudget, itemId), bandwidth.maxTransferable(itemId));
            if (itemBudget <= 0) {
                rateLimitedOnly = true;
                continue;
            }
            int toTransfer = Math.min(count, itemBudget);
            toTransfer = Math.min(toTransfer, target.getRemainingCapacity());

            if (toTransfer > 0) {
                ItemStack moving = entry.prototype();
                int removed = removeItemWithoutProduction(moving, toTransfer);
                if (removed <= 0) continue;
                int accepted = target.addItemWithoutProduction(moving, removed);
                if (accepted < removed) addItemWithoutProduction(moving, removed - accepted);
                if (accepted <= 0) continue;
                toTransfer = accepted;
                recordGraphProductionOutput(item, toTransfer);
                target.recordGraphProductionInput(moving.getItem(), toTransfer);
                bandwidth.consume(itemId, toTransfer);
                movedByItem.merge(itemId, toTransfer, Integer::sum);
                remainingRouteBudget -= toTransfer;

                if (target.getRemainingCapacity() <= 0) {
                    if (canVoid && remainingRouteBudget > 0 && bandwidth.remaining() > 0) {
                        TransferResult voided = voidFilteredItems(scopePort, path, gameTime, remainingRouteBudget, bandwidth);
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
                if (remainingRouteBudget <= 0 || bandwidth.remaining() <= 0) break;
            }
        }
        if (movedByItem.isEmpty()) {
            if (rateLimitedOnly) return new TransferResult(Map.of(), TransferBlockReason.NONE, null);
            return new TransferResult(Map.of(), matchedSourceItem ? TransferBlockReason.RECEIVER : TransferBlockReason.SOURCE,
                    matchedSourceItem ? firstMatchingItemId(scopePort) : scopedItemId(scopePort));
        }
        if (remainingRouteBudget <= 0 || bandwidth.remaining() <= 0) return new TransferResult(movedByItem, TransferBlockReason.NONE, null);
        return new TransferResult(movedByItem, blockReason != TransferBlockReason.NONE ? blockReason : TransferBlockReason.SOURCE,
                blockItemId != null ? blockItemId : lastMovedItemId(movedByItem));
    }

    private TransferResult transferItemsToPlayer(com.foldworks.transfer.TransferNode targetNode, String scopePort, List<TransferEdge> path,
                                                 long gameTime, int routeBudget, BandwidthBudget bandwidth) {
        if (!(level instanceof ServerLevel serverLevel) || targetNode.getTargetPlayerId() == null) {
            return new TransferResult(Map.of(), TransferBlockReason.RECEIVER, firstMatchingItemId(scopePort));
        }
        ServerPlayer targetPlayer = serverLevel.getServer().getPlayerList().getPlayer(targetNode.getTargetPlayerId());
        if (targetPlayer == null) {
            return new TransferResult(Map.of(), TransferBlockReason.RECEIVER, firstMatchingItemId(scopePort));
        }
        List<StoredItemStack> entries = getStoredItems();
        int remainingRouteBudget = routeBudget;
        Map<String, Integer> movedByItem = new LinkedHashMap<>();
        boolean matchedSourceItem = false;
        boolean rateLimitedOnly = false;
        for (StoredItemStack entry : entries) {
            Item item = entry.item();
            int count = entry.count();
            if (count <= 0 || !portAllows(scopePort, item)) continue;
            matchedSourceItem = true;
            String itemId = itemId(item);
            String bareItemId = bareItemId(item);
            if (itemId == null || bareItemId == null) continue;
            if (!receiveFilterAllows(targetNode, itemId)) continue;
            ItemStack prototype = entry.prototype();
            int targetCount = targetNode.targetCountFor(bareItemId, prototype.getMaxStackSize());
            if (targetCount <= 0) continue;
            int current = countPlayerMainInventory(targetPlayer, item);
            int missing = Math.max(0, targetCount - current);
            if (missing <= 0) continue;

            int itemBudget = Math.min(pathBudget(path, gameTime, remainingRouteBudget, itemId), bandwidth.maxTransferable(itemId));
            if (itemBudget <= 0) {
                rateLimitedOnly = true;
                continue;
            }
            int toTransfer = Math.min(Math.min(count, missing), itemBudget);
            toTransfer = Math.min(toTransfer, playerMainInventoryRoom(targetPlayer, prototype));
            if (toTransfer <= 0) continue;

            int removed = removeItemWithoutProduction(prototype, toTransfer);
            if (removed <= 0) continue;
            int accepted = insertIntoPlayerMainInventory(targetPlayer, prototype, removed);
            if (accepted < removed) addItemWithoutProduction(prototype, removed - accepted);
            if (accepted <= 0) continue;
            recordGraphProductionOutput(item, accepted);
            bandwidth.consume(itemId, accepted);
            movedByItem.merge(itemId, accepted, Integer::sum);
            remainingRouteBudget -= accepted;
            targetPlayer.containerMenu.broadcastChanges();
            targetPlayer.inventoryMenu.broadcastChanges();
            if (remainingRouteBudget <= 0 || bandwidth.remaining() <= 0) break;
        }
        if (movedByItem.isEmpty()) {
            if (rateLimitedOnly) return new TransferResult(Map.of(), TransferBlockReason.NONE, null);
            return new TransferResult(Map.of(), matchedSourceItem ? TransferBlockReason.RECEIVER : TransferBlockReason.SOURCE,
                    matchedSourceItem ? firstMatchingItemId(scopePort) : scopedItemId(scopePort));
        }
        if (remainingRouteBudget <= 0 || bandwidth.remaining() <= 0) return new TransferResult(movedByItem, TransferBlockReason.NONE, null);
        return new TransferResult(movedByItem, TransferBlockReason.SOURCE, lastMovedItemId(movedByItem));
    }

    private TransferResult transferFluidsTo(BaseChestBlockEntity target, com.foldworks.transfer.TransferNode targetNode, String scopePort, List<TransferEdge> path,
                                            long gameTime, int routeBudget, boolean canVoid, BandwidthBudget bandwidth) {
        List<Map.Entry<Fluid, Integer>> entries = new ArrayList<>(fluidStorage.entrySet());
        entries.sort(Comparator.comparing(entry -> net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(entry.getKey()).toString()));
        int remainingRouteBudget = routeBudget;
        Map<String, Integer> movedByFluid = new LinkedHashMap<>();
        boolean matchedSourceFluid = false;
        boolean rateLimitedOnly = false;
        TransferBlockReason blockReason = TransferBlockReason.NONE;
        String blockFluidId = null;
        if (target.getRemainingFluidCapacityMb() <= 0) {
            return canVoid ? voidFilteredFluids(scopePort, path, gameTime, routeBudget, bandwidth)
                    : new TransferResult(Map.of(), TransferBlockReason.RECEIVER, firstMatchingFluidId(scopePort));
        }

        for (Map.Entry<Fluid, Integer> entry : entries) {
            Fluid fluid = entry.getKey();
            int amount = entry.getValue();
            if (amount <= 0 || !portAllowsFluid(scopePort, fluid)) continue;
            matchedSourceFluid = true;
            String fluidId = fluidId(fluid);
            if (fluidId == null) continue;
            if (!receiveFilterAllows(targetNode, fluidId)) continue;
            int fluidBudget = Math.min(pathBudget(path, gameTime, remainingRouteBudget, fluidId), bandwidth.maxTransferable(fluidId));
            if (fluidBudget <= 0) {
                rateLimitedOnly = true;
                continue;
            }
            int toTransfer = Math.min(amount, fluidBudget);
            toTransfer = Math.min(toTransfer, target.getRemainingFluidCapacityMb(fluid));
            if (toTransfer > 0) {
                int removed = removeFluidWithoutProduction(fluid, toTransfer);
                if (removed <= 0) continue;
                int accepted = target.addFluidWithoutProduction(fluid, removed);
                if (accepted < removed) addFluidWithoutProduction(fluid, removed - accepted);
                if (accepted <= 0) continue;
                recordGraphProductionFluidOutput(fluid, accepted);
                target.recordGraphProductionFluidInput(fluid, accepted);
                bandwidth.consume(fluidId, accepted);
                movedByFluid.merge(fluidId, accepted, Integer::sum);
                remainingRouteBudget -= accepted;

                if (target.getRemainingFluidCapacityMb(fluid) <= 0) {
                    if (canVoid && remainingRouteBudget > 0 && bandwidth.remaining() > 0) {
                        TransferResult voided = voidFilteredFluids(scopePort, path, gameTime, remainingRouteBudget, bandwidth);
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
                if (remainingRouteBudget <= 0 || bandwidth.remaining() <= 0) break;
            }
        }
        if (movedByFluid.isEmpty()) {
            if (rateLimitedOnly) return new TransferResult(Map.of(), TransferBlockReason.NONE, null);
            return new TransferResult(Map.of(), matchedSourceFluid ? TransferBlockReason.RECEIVER : TransferBlockReason.SOURCE,
                    matchedSourceFluid ? firstMatchingFluidId(scopePort) : scopedFluidId(scopePort));
        }
        if (remainingRouteBudget <= 0 || bandwidth.remaining() <= 0) return new TransferResult(movedByFluid, TransferBlockReason.NONE, null);
        return new TransferResult(movedByFluid, blockReason != TransferBlockReason.NONE ? blockReason : TransferBlockReason.SOURCE,
                blockFluidId != null ? blockFluidId : lastMovedItemId(movedByFluid));
    }

    private TransferResult transferItemsToSnapshot(ServerLevel serverLevel, OfflineChestSnapshotStorage.Snapshot target,
                                                   com.foldworks.transfer.TransferNode targetNode, String scopePort,
                                                   List<TransferEdge> path, long gameTime, int routeBudget,
                                                   boolean canVoid, BandwidthBudget bandwidth) {
        List<StoredItemStack> entries = getStoredItems();
        int remainingRouteBudget = routeBudget;
        Map<String, Integer> movedByItem = new LinkedHashMap<>();
        boolean matchedSourceItem = false;
        boolean rateLimitedOnly = false;
        TransferBlockReason blockReason = TransferBlockReason.NONE;
        String blockItemId = null;
        if (target.remainingItemCapacity() <= 0) {
            return canVoid ? voidFilteredItems(scopePort, path, gameTime, routeBudget, bandwidth)
                    : new TransferResult(Map.of(), TransferBlockReason.RECEIVER, firstMatchingItemId(scopePort));
        }

        OfflineChestSnapshotStorage storage = OfflineChestSnapshotStorage.get(serverLevel.getServer());
        for (StoredItemStack entry : entries) {
            Item item = entry.item();
            int count = entry.count();
            if (count <= 0 || !portAllows(scopePort, item)) continue;
            matchedSourceItem = true;
            String itemId = itemId(item);
            if (itemId == null || !receiveFilterAllows(targetNode, itemId)) continue;
            int itemBudget = Math.min(pathBudget(path, gameTime, remainingRouteBudget, itemId), bandwidth.maxTransferable(itemId));
            if (itemBudget <= 0) {
                rateLimitedOnly = true;
                continue;
            }
            int toTransfer = Math.min(Math.min(count, itemBudget), target.remainingItemCapacity());
            if (toTransfer <= 0) continue;

            ItemStack moving = entry.prototype();
            int removed = removeItemWithoutProduction(moving, toTransfer);
            if (removed <= 0) continue;
            int accepted = target.addItem(moving, removed);
            if (accepted < removed) addItemWithoutProduction(moving, removed - accepted);
            if (accepted <= 0) continue;
            recordGraphProductionOutput(item, accepted);
            storage.recordSnapshotGraphInput(serverLevel.getServer(), target, itemId, accepted, gameTime);
            bandwidth.consume(itemId, accepted);
            movedByItem.merge(itemId, accepted, Integer::sum);
            remainingRouteBudget -= accepted;

            if (target.remainingItemCapacity() <= 0) {
                if (canVoid && remainingRouteBudget > 0 && bandwidth.remaining() > 0) {
                    TransferResult voided = voidFilteredItems(scopePort, path, gameTime, remainingRouteBudget, bandwidth);
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
            if (remainingRouteBudget <= 0 || bandwidth.remaining() <= 0) break;
        }
        if (movedByItem.isEmpty()) {
            if (rateLimitedOnly) return new TransferResult(Map.of(), TransferBlockReason.NONE, null);
            return new TransferResult(Map.of(), matchedSourceItem ? TransferBlockReason.RECEIVER : TransferBlockReason.SOURCE,
                    matchedSourceItem ? firstMatchingItemId(scopePort) : scopedItemId(scopePort));
        }
        if (remainingRouteBudget <= 0 || bandwidth.remaining() <= 0) return new TransferResult(movedByItem, TransferBlockReason.NONE, null);
        return new TransferResult(movedByItem, blockReason != TransferBlockReason.NONE ? blockReason : TransferBlockReason.SOURCE,
                blockItemId != null ? blockItemId : lastMovedItemId(movedByItem));
    }

    private TransferResult transferFluidsToSnapshot(ServerLevel serverLevel, OfflineChestSnapshotStorage.Snapshot target,
                                                    com.foldworks.transfer.TransferNode targetNode, String scopePort,
                                                    List<TransferEdge> path, long gameTime, int routeBudget,
                                                    boolean canVoid, BandwidthBudget bandwidth) {
        List<Map.Entry<Fluid, Integer>> entries = new ArrayList<>(fluidStorage.entrySet());
        entries.sort(Comparator.comparing(entry -> net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(entry.getKey()).toString()));
        int remainingRouteBudget = routeBudget;
        Map<String, Integer> movedByFluid = new LinkedHashMap<>();
        boolean matchedSourceFluid = false;
        boolean rateLimitedOnly = false;
        TransferBlockReason blockReason = TransferBlockReason.NONE;
        String blockFluidId = null;

        OfflineChestSnapshotStorage storage = OfflineChestSnapshotStorage.get(serverLevel.getServer());
        for (Map.Entry<Fluid, Integer> entry : entries) {
            Fluid fluid = entry.getKey();
            int amount = entry.getValue();
            if (amount <= 0 || !portAllowsFluid(scopePort, fluid)) continue;
            matchedSourceFluid = true;
            String fluidId = fluidId(fluid);
            if (fluidId == null || !receiveFilterAllows(targetNode, fluidId)) continue;
            int fluidBudget = Math.min(pathBudget(path, gameTime, remainingRouteBudget, fluidId), bandwidth.maxTransferable(fluidId));
            if (fluidBudget <= 0) {
                rateLimitedOnly = true;
                continue;
            }
            String bareFluidId = fluidId.substring(TransferEdge.FLUID_PREFIX.length());
            int toTransfer = Math.min(Math.min(amount, fluidBudget), target.remainingFluidCapacity(bareFluidId));
            if (toTransfer <= 0) continue;

            int removed = removeFluidWithoutProduction(fluid, toTransfer);
            if (removed <= 0) continue;
            int accepted = target.addFluid(bareFluidId, removed);
            if (accepted < removed) addFluidWithoutProduction(fluid, removed - accepted);
            if (accepted <= 0) continue;
            recordGraphProductionFluidOutput(fluid, accepted);
            storage.recordSnapshotGraphInput(serverLevel.getServer(), target, fluidId, accepted, gameTime);
            bandwidth.consume(fluidId, accepted);
            movedByFluid.merge(fluidId, accepted, Integer::sum);
            remainingRouteBudget -= accepted;

            if (target.remainingFluidCapacity(bareFluidId) <= 0) {
                if (canVoid && remainingRouteBudget > 0 && bandwidth.remaining() > 0) {
                    TransferResult voided = voidFilteredFluids(scopePort, path, gameTime, remainingRouteBudget, bandwidth);
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
            if (remainingRouteBudget <= 0 || bandwidth.remaining() <= 0) break;
        }
        if (movedByFluid.isEmpty()) {
            if (rateLimitedOnly) return new TransferResult(Map.of(), TransferBlockReason.NONE, null);
            return new TransferResult(Map.of(), matchedSourceFluid ? TransferBlockReason.RECEIVER : TransferBlockReason.SOURCE,
                    matchedSourceFluid ? firstMatchingFluidId(scopePort) : scopedFluidId(scopePort));
        }
        if (remainingRouteBudget <= 0 || bandwidth.remaining() <= 0) return new TransferResult(movedByFluid, TransferBlockReason.NONE, null);
        return new TransferResult(movedByFluid, blockReason != TransferBlockReason.NONE ? blockReason : TransferBlockReason.SOURCE,
                blockFluidId != null ? blockFluidId : lastMovedItemId(movedByFluid));
    }

    private TransferResult transferEnergyToSnapshot(ServerLevel serverLevel, OfflineChestSnapshotStorage.Snapshot target,
                                                    List<TransferEdge> path, long gameTime, int routeBudget, BandwidthBudget bandwidth) {
        if (!hasEnergyUpgrade() || !target.hasEnergyUpgrade()) {
            return new TransferResult(Map.of(), TransferBlockReason.RECEIVER, TransferEdge.ENERGY_FE);
        }
        int available = getEnergyStored();
        if (available <= 0) return new TransferResult(Map.of(), TransferBlockReason.SOURCE, TransferEdge.ENERGY_FE);
        int receiverRoom = target.remainingEnergyCapacity();
        if (receiverRoom <= 0) return new TransferResult(Map.of(), TransferBlockReason.RECEIVER, TransferEdge.ENERGY_FE);
        int budget = Math.min(pathBudget(path, gameTime, routeBudget, TransferEdge.ENERGY_FE), bandwidth.maxTransferable(TransferEdge.ENERGY_FE));
        if (budget <= 0) return new TransferResult(Map.of(), TransferBlockReason.NONE, null);
        int moving = Math.min(Math.min(available, receiverRoom), Math.min(budget, getEnergyTransferLimit()));
        moving = Math.min(moving, target.energyTransferLimit());
        if (moving <= 0) return new TransferResult(Map.of(), TransferBlockReason.NONE, null);
        int extracted = extractEnergyFromGraph(moving);
        int accepted = target.receiveEnergy(extracted);
        if (accepted < extracted) receiveEnergyWithoutProduction(extracted - accepted);
        if (accepted <= 0) return new TransferResult(Map.of(), TransferBlockReason.RECEIVER, TransferEdge.ENERGY_FE);
        OfflineChestSnapshotStorage.get(serverLevel.getServer()).recordSnapshotGraphInput(serverLevel.getServer(), target, TransferEdge.ENERGY_FE, accepted, gameTime);
        bandwidth.consume(TransferEdge.ENERGY_FE, accepted);
        return new TransferResult(Map.of(TransferEdge.ENERGY_FE, accepted), TransferBlockReason.NONE, null);
    }

    private TransferResult transferStressToSnapshot(OfflineChestSnapshotStorage.Snapshot target, List<TransferEdge> path,
                                                    long gameTime, int routeBudget, BandwidthBudget bandwidth) {
        if (!hasStressUpgrade() || !target.hasStressUpgrade()) {
            return new TransferResult(Map.of(), TransferBlockReason.RECEIVER, TransferEdge.STRESS_SU);
        }
        FoldworksStressEndpoint sourceEndpoint = stressEndpoint(this);
        if (sourceEndpoint == null) {
            return new TransferResult(Map.of(), TransferBlockReason.RECEIVER, TransferEdge.STRESS_SU);
        }
        if (!sourceEndpoint.canSendGraphStress()) {
            return new TransferResult(Map.of(), TransferBlockReason.SOURCE, TransferEdge.STRESS_SU);
        }
        if (!target.canReceiveGraphStress()) {
            return new TransferResult(Map.of(), TransferBlockReason.RECEIVER, TransferEdge.STRESS_SU);
        }
        int budget = Math.min(routeBudget, Math.min((int) sourceEndpoint.graphStressCapacity(), target.stressTransferLimit()));
        budget = Math.min(budget, bandwidth.maxTransferable(TransferEdge.STRESS_SU));
        if (budget <= 0) return new TransferResult(Map.of(), TransferBlockReason.NONE, null);
        float speed = sourceEndpoint.graphStressSpeed();
        if (speed == 0) return new TransferResult(Map.of(), TransferBlockReason.SOURCE, TransferEdge.STRESS_SU);
        String leaseId = stressLeaseId(path, target);
        int accepted = Math.max(0, Math.min(budget, (int) target.receiveGraphStressLease(leaseId, speed, budget, gameTime)));
        if (accepted <= 0) return new TransferResult(Map.of(), TransferBlockReason.RECEIVER, TransferEdge.STRESS_SU);
        sourceEndpoint.recordGraphStressLease(leaseId, speed, accepted, gameTime);
        if (level instanceof ServerLevel serverLevel) OfflineChestSnapshotStorage.get(serverLevel.getServer()).setDirty();
        bandwidth.consume(TransferEdge.STRESS_SU, accepted);
        return new TransferResult(Map.of(TransferEdge.STRESS_SU, accepted), TransferBlockReason.NONE, null);
    }

    private TransferResult transferEnergyTo(BaseChestBlockEntity target, List<TransferEdge> path, long gameTime, int routeBudget, BandwidthBudget bandwidth) {
        if (!hasEnergyUpgrade() || !target.hasEnergyUpgrade()) {
            return new TransferResult(Map.of(), TransferBlockReason.RECEIVER, TransferEdge.ENERGY_FE);
        }
        int available = getEnergyStored();
        if (available <= 0) return new TransferResult(Map.of(), TransferBlockReason.SOURCE, TransferEdge.ENERGY_FE);
        int receiverRoom = target.getRemainingEnergyCapacity();
        if (receiverRoom <= 0) return new TransferResult(Map.of(), TransferBlockReason.RECEIVER, TransferEdge.ENERGY_FE);
        int budget = Math.min(pathBudget(path, gameTime, routeBudget, TransferEdge.ENERGY_FE), bandwidth.maxTransferable(TransferEdge.ENERGY_FE));
        if (budget <= 0) return new TransferResult(Map.of(), TransferBlockReason.NONE, null);
        int moving = Math.min(Math.min(available, receiverRoom), Math.min(budget, getEnergyTransferLimit()));
        moving = Math.min(moving, target.getEnergyTransferLimit());
        if (moving <= 0) return new TransferResult(Map.of(), TransferBlockReason.NONE, null);
        int extracted = extractEnergyFromGraph(moving);
        int accepted = target.receiveEnergyFromGraph(extracted);
        if (accepted < extracted) receiveEnergyWithoutProduction(extracted - accepted);
        if (accepted <= 0) return new TransferResult(Map.of(), TransferBlockReason.RECEIVER, TransferEdge.ENERGY_FE);
        bandwidth.consume(TransferEdge.ENERGY_FE, accepted);
        return new TransferResult(Map.of(TransferEdge.ENERGY_FE, accepted), TransferBlockReason.NONE, null);
    }

    private TransferResult transferStressTo(BaseChestBlockEntity target, List<TransferEdge> path, long gameTime, int routeBudget, BandwidthBudget bandwidth) {
        if (!hasStressUpgrade() || !target.hasStressUpgrade()) {
            return new TransferResult(Map.of(), TransferBlockReason.RECEIVER, TransferEdge.STRESS_SU);
        }
        FoldworksStressEndpoint sourceEndpoint = stressEndpoint(this);
        FoldworksStressEndpoint targetEndpoint = stressEndpoint(target);
        if (sourceEndpoint == null || targetEndpoint == null) {
            return new TransferResult(Map.of(), TransferBlockReason.RECEIVER, TransferEdge.STRESS_SU);
        }
        if (!sourceEndpoint.canSendGraphStress()) {
            return new TransferResult(Map.of(), TransferBlockReason.SOURCE, TransferEdge.STRESS_SU);
        }
        if (!targetEndpoint.canReceiveGraphStress()) {
            return new TransferResult(Map.of(), TransferBlockReason.RECEIVER, TransferEdge.STRESS_SU);
        }
        int budget = Math.min(routeBudget, Math.min((int) sourceEndpoint.graphStressCapacity(), target.getStressTransferLimit()));
        budget = Math.min(budget, bandwidth.maxTransferable(TransferEdge.STRESS_SU));
        if (budget <= 0) return new TransferResult(Map.of(), TransferBlockReason.NONE, null);
        float speed = sourceEndpoint.graphStressSpeed();
        if (speed == 0) return new TransferResult(Map.of(), TransferBlockReason.SOURCE, TransferEdge.STRESS_SU);
        String leaseId = stressLeaseId(path, target);
        int accepted = Math.max(0, Math.min(budget, (int) targetEndpoint.receiveGraphStressLease(leaseId, speed, budget, gameTime)));
        if (accepted <= 0) return new TransferResult(Map.of(), TransferBlockReason.RECEIVER, TransferEdge.STRESS_SU);
        sourceEndpoint.recordGraphStressLease(leaseId, speed, accepted, gameTime);
        bandwidth.consume(TransferEdge.STRESS_SU, accepted);
        return new TransferResult(Map.of(TransferEdge.STRESS_SU, accepted), TransferBlockReason.NONE, null);
    }

    private FoldworksStressEndpoint stressEndpoint(BaseChestBlockEntity chest) {
        if (chest == null || chest.getLevel() == null) return null;
        BlockEntity blockEntity = chest.getLevel().getBlockEntity(chest.getBlockPos());
        if (blockEntity instanceof FoldworksStressEndpoint endpoint && endpoint.foldworksChest() == chest) return endpoint;
        return null;
    }

    private String stressLeaseId(List<TransferEdge> path, BaseChestBlockEntity target) {
        StringBuilder id = new StringBuilder();
        if (level != null) id.append(level.dimension().location());
        id.append('|').append(worldPosition.asLong()).append('|');
        for (TransferEdge edge : path) id.append(edge.getId()).append('>');
        id.append(target.getBlockPos().asLong());
        return id.toString();
    }

    private String stressLeaseId(List<TransferEdge> path, OfflineChestSnapshotStorage.Snapshot target) {
        StringBuilder id = new StringBuilder();
        if (level != null) id.append(level.dimension().location());
        id.append('|').append(worldPosition.asLong()).append('|');
        for (TransferEdge edge : path) id.append(edge.getId()).append('>');
        id.append(target.posLong());
        return id.toString();
    }

    /**
     * 虚空所有通过筛选的物品（整箱清空）
     */
    private TransferResult voidFilteredItems(String scopePort, List<TransferEdge> path, long gameTime, int routeBudget, BandwidthBudget bandwidth) {
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
            int itemBudget = Math.min(pathBudget(path, gameTime, remainingRouteBudget, itemId), bandwidth.maxTransferable(itemId));
            if (itemBudget <= 0) {
                rateLimitedOnly = true;
                continue;
            }
            int removed = removeItemWithoutProduction(entry.prototype(), Math.min(entry.count(), itemBudget));
            if (removed > 0) {
                changed = true;
                recordGraphProductionOutput(entry.item(), removed);
                bandwidth.consume(itemId, removed);
                movedByItem.merge(itemId, removed, Integer::sum);
                remainingRouteBudget -= removed;
                if (remainingRouteBudget <= 0 || bandwidth.remaining() <= 0) break;
            }
        }
        if (changed) setChanged();
        if (!movedByItem.isEmpty()) {
            boolean blocked = remainingRouteBudget > 0 && bandwidth.remaining() > 0;
            return new TransferResult(movedByItem, blocked ? TransferBlockReason.SOURCE : TransferBlockReason.NONE,
                    blocked ? lastMovedItemId(movedByItem) : null);
        }
        if (rateLimitedOnly) return new TransferResult(Map.of(), TransferBlockReason.NONE, null);
        return new TransferResult(Map.of(), TransferBlockReason.SOURCE, matchedSourceItem ? firstMatchingItemId(scopePort) : scopedItemId(scopePort));
    }

    private TransferResult voidFilteredFluids(String scopePort, List<TransferEdge> path, long gameTime, int routeBudget, BandwidthBudget bandwidth) {
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
            int fluidBudget = Math.min(pathBudget(path, gameTime, remainingRouteBudget, fluidId), bandwidth.maxTransferable(fluidId));
            if (fluidBudget <= 0) {
                rateLimitedOnly = true;
                continue;
            }
            int removed = removeFluidWithoutProduction(entry.getKey(), Math.min(entry.getValue(), fluidBudget));
            if (removed > 0) {
                changed = true;
                recordGraphProductionFluidOutput(entry.getKey(), removed);
                bandwidth.consume(fluidId, removed);
                movedByFluid.merge(fluidId, removed, Integer::sum);
                remainingRouteBudget -= removed;
                if (remainingRouteBudget <= 0 || bandwidth.remaining() <= 0) break;
            }
        }
        if (changed) setChanged();
        if (!movedByFluid.isEmpty()) {
            boolean blocked = remainingRouteBudget > 0 && bandwidth.remaining() > 0;
            return new TransferResult(movedByFluid, blocked ? TransferBlockReason.SOURCE : TransferBlockReason.NONE,
                    blocked ? lastMovedItemId(movedByFluid) : null);
        }
        if (rateLimitedOnly) return new TransferResult(Map.of(), TransferBlockReason.NONE, null);
        return new TransferResult(Map.of(), TransferBlockReason.SOURCE, matchedSourceFluid ? firstMatchingFluidId(scopePort) : scopedFluidId(scopePort));
    }

    private boolean portAllows(String scopePort, Item item) {
        if (TransferEdge.ITEM_ALL.equals(scopePort)) return true;
        if (scopePort == null || !scopePort.startsWith(TransferEdge.ITEM_PREFIX)) return false;
        net.minecraft.resources.ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
        return id != null && scopePort.substring(TransferEdge.ITEM_PREFIX.length()).equals(id.toString());
    }

    private boolean receiveFilterAllows(com.foldworks.transfer.TransferNode targetNode, String resourceId) {
        if (targetNode == null || targetNode.getReceiveFilterIds().isEmpty()) return true;
        if (resourceId == null || resourceId.isBlank()) return false;
        if (targetNode.getReceiveFilterIds().contains(resourceId)) return true;
        if (resourceId.startsWith(TransferEdge.ITEM_PREFIX)) {
            String bare = resourceId.substring(TransferEdge.ITEM_PREFIX.length());
            return targetNode.getReceiveFilterIds().contains(bare) || targetNode.getReceiveFilterIds().contains(TransferEdge.ITEM_ALL);
        }
        if (resourceId.startsWith(TransferEdge.FLUID_PREFIX)) {
            return targetNode.getReceiveFilterIds().contains(TransferEdge.FLUID_ALL);
        }
        return false;
    }

    private String itemId(Item item) {
        net.minecraft.resources.ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
        return id == null ? null : TransferEdge.ITEM_PREFIX + id;
    }

    private String bareItemId(Item item) {
        net.minecraft.resources.ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
        return id == null ? null : id.toString();
    }

    private int countPlayerMainInventory(ServerPlayer player, Item item) {
        int total = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (!stack.isEmpty() && stack.getItem() == item) total += stack.getCount();
        }
        return total;
    }

    private int playerMainInventoryRoom(ServerPlayer player, ItemStack prototype) {
        if (prototype == null || prototype.isEmpty()) return 0;
        int room = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (stack.isEmpty()) room += prototype.getMaxStackSize();
            else if (ItemStack.isSameItemSameComponents(stack, prototype)) room += Math.max(0, stack.getMaxStackSize() - stack.getCount());
        }
        return room;
    }

    private int insertIntoPlayerMainInventory(ServerPlayer player, ItemStack prototype, int amount) {
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

    private boolean isEnergyScope(String scopePort) {
        return TransferEdge.ENERGY_FE.equals(scopePort);
    }

    private boolean isStressScope(String scopePort) {
        return TransferEdge.STRESS_SU.equals(scopePort);
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
        if (isEnergyScope(scopePort)) return TransferEdge.ENERGY_FE;
        if (isStressScope(scopePort)) return TransferEdge.STRESS_SU;
        return isFluidScope(scopePort) ? firstMatchingFluidId(scopePort) : firstMatchingItemId(scopePort);
    }

    private String lastMovedItemId(Map<String, Integer> movedByItem) {
        String last = null;
        for (String itemId : movedByItem.keySet()) last = itemId;
        return last;
    }

    private BaseChestBlockEntity findNodeChest(com.foldworks.transfer.TransferNode node, ServerLevel currentLevel) {
        net.minecraft.resources.ResourceLocation dimLoc = net.minecraft.resources.ResourceLocation.tryParse(node.getDimensionKey());
        if (dimLoc == null) return null;
        ServerLevel targetLevel = currentLevel.getServer().getLevel(net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION,
                dimLoc
        ));
        if (targetLevel == null) return null;
        BaseChestBlockEntity be = FoldworksChestAccess.resolve(targetLevel.getBlockEntity(node.getPos()));
        if (be != null
                && be.getChestId().equals(node.getChestId())
                && be.hasNetworkUpgrade()) {
            return be;
        }
        return MovingChestRegistry.findChest(node.getDimensionKey(), node.getPos(), node.getChestId());
    }

    private OfflineChestSnapshotStorage.Snapshot findNodeSnapshot(com.foldworks.transfer.TransferNode node, ServerLevel currentLevel, GraphKey graphKey) {
        if (node == null || graphKey == null || !graphKey.isValid()) return null;
        OfflineChestSnapshotStorage.Snapshot snapshot = OfflineChestSnapshotStorage.get(currentLevel.getServer())
                .findSnapshot(node.getDimensionKey(), node.getPos(), node.getChestId());
        if (snapshot == null || snapshot.loaded() || snapshot.moving() || !snapshot.hasNetworkUpgrade()) return null;
        if (!snapshot.matchesGraph(graphKey) || !snapshot.shouldSimulate(currentLevel.getServer())) return null;
        return snapshot;
    }

    private int limitGateBudget(TransferGraph graph, ServerLevel currentLevel, String pageId, TransferNode gate,
                                String scopePort, int routeBudget) {
        if (gate == null || gate.getNodeType() != TransferNode.NodeType.LIMIT_GATE || routeBudget <= 0) return 0;
        String resourceId = exactGateResource(scopePort);
        if (resourceId == null) return 0;
        if (gate.isGateCheckSource()) {
            int amount = loadedResourceAmount(this, resourceId);
            return gate.sourceGateBudgetWithinPassRange(amount, routeBudget);
        }
        TransferNode destination = resolveGateDestination(graph, pageId, gate, scopePort, new HashSet<>());
        if (destination == null) return 0;
        int amount = destinationResourceAmount(graph, currentLevel, destination, resourceId);
        return gate.gateBudgetWithinPassRange(amount, routeBudget);
    }

    private TransferNode resolveGateDestination(TransferGraph graph, String pageId, TransferNode start,
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

    private TransferEdge singleOutgoingForScope(TransferGraph graph, String nodeId, String scopePort) {
        TransferEdge result = null;
        for (TransferEdge edge : graph.outgoing(nodeId)) {
            if (!edge.isEnabled() || intersectPorts(scopePort, edge.getFromPortKey()) == null) continue;
            if (result != null) return null;
            result = edge;
        }
        return result;
    }

    private TransferNode linkedJumpOutput(TransferGraph graph, TransferNode input) {
        if (input == null || input.getNodeType() != TransferNode.NodeType.JUMP_INPUT) return null;
        TransferNode direct = graph.getNode(input.getLinkedNodeId());
        if (direct != null && direct.getNodeType() == TransferNode.NodeType.JUMP_OUTPUT
                && input.getId().equals(direct.getLinkedNodeId())) return direct;
        for (TransferNode node : graph.getNodes()) {
            if (node.getNodeType() == TransferNode.NodeType.JUMP_OUTPUT && input.getId().equals(node.getLinkedNodeId())) return node;
        }
        return null;
    }

    private int destinationResourceAmount(TransferGraph graph, ServerLevel currentLevel, TransferNode destination, String resourceId) {
        if (destination.getNodeType() == TransferNode.NodeType.PLAYER_INVENTORY) return playerResourceAmount(currentLevel, destination, resourceId);
        if (destination.getNodeType() != TransferNode.NodeType.CHEST) return -1;
        BaseChestBlockEntity loaded = findNodeChest(destination, currentLevel);
        if (loaded != null && !loaded.isRemoved() && com.foldworks.transfer.TransferGraphAccess.chestMatchesGraph(loaded, graph.getKey())) {
            return loadedResourceAmount(loaded, resourceId);
        }
        OfflineChestSnapshotStorage.Snapshot snapshot = findNodeSnapshot(destination, currentLevel, graph.getKey());
        return snapshot == null ? -1 : snapshotResourceAmount(snapshot, resourceId, currentLevel.getGameTime());
    }

    private int loadedResourceAmount(BaseChestBlockEntity chest, String resourceId) {
        if (resourceId.startsWith(TransferEdge.ITEM_PREFIX)) {
            String itemId = resourceId.substring(TransferEdge.ITEM_PREFIX.length());
            int total = 0;
            for (StoredItemStack stored : chest.getStoredItems()) {
                ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stored.item());
                if (id != null && id.toString().equals(itemId)) total += stored.count();
            }
            return total;
        }
        if (resourceId.startsWith(TransferEdge.FLUID_PREFIX)) {
            Fluid fluid = resolveFluidResource(resourceId);
            return fluid == Fluids.EMPTY ? -1 : chest.getAllFluids().getOrDefault(fluid, 0);
        }
        if (TransferEdge.ENERGY_FE.equals(resourceId)) return chest.getEnergyStored();
        if (TransferEdge.STRESS_SU.equals(resourceId)) {
            FoldworksStressEndpoint endpoint = stressEndpoint(chest);
            return endpoint == null ? 0 : Math.max(0, Math.round(endpoint.graphStressCapacity()));
        }
        return -1;
    }

    private int snapshotResourceAmount(OfflineChestSnapshotStorage.Snapshot snapshot, String resourceId, long gameTime) {
        if (resourceId.startsWith(TransferEdge.ITEM_PREFIX)) {
            String itemId = resourceId.substring(TransferEdge.ITEM_PREFIX.length());
            int total = 0;
            for (StoredItemStack stored : snapshot.items()) {
                ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stored.item());
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

    private int playerResourceAmount(ServerLevel currentLevel, TransferNode destination, String resourceId) {
        if (destination.getTargetPlayerId() == null || !resourceId.startsWith(TransferEdge.ITEM_PREFIX)) return -1;
        ServerPlayer player = currentLevel.getServer().getPlayerList().getPlayer(destination.getTargetPlayerId());
        Item item = resolveItemResource(resourceId);
        return player == null || item == Items.AIR ? -1 : countPlayerMainInventory(player, item);
    }

    private String exactGateResource(String scopePort) {
        if (scopePort == null || scopePort.isBlank() || TransferEdge.PORT_ALL.equals(scopePort) || TransferEdge.FLUID_ALL.equals(scopePort)) return null;
        if (scopePort.startsWith(TransferEdge.ITEM_PREFIX) || scopePort.startsWith(TransferEdge.FLUID_PREFIX)
                || TransferEdge.ENERGY_FE.equals(scopePort) || TransferEdge.STRESS_SU.equals(scopePort)) return scopePort;
        return null;
    }

    private Item resolveItemResource(String resourceId) {
        if (resourceId == null || !resourceId.startsWith(TransferEdge.ITEM_PREFIX)) return Items.AIR;
        ResourceLocation id = ResourceLocation.tryParse(resourceId.substring(TransferEdge.ITEM_PREFIX.length()));
        return id == null ? Items.AIR : net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id);
    }

    private Fluid resolveFluidResource(String resourceId) {
        if (resourceId == null || !resourceId.startsWith(TransferEdge.FLUID_PREFIX)) return Fluids.EMPTY;
        ResourceLocation id = ResourceLocation.tryParse(resourceId.substring(TransferEdge.FLUID_PREFIX.length()));
        return id == null ? Fluids.EMPTY : net.minecraft.core.registries.BuiltInRegistries.FLUID.get(id);
    }

    private boolean hasGraphOutgoingEdges() {
        if (level == null || level.isClientSide || ownerUUID == null || chestId.isEmpty()) return false;
        if (!hasNetworkUpgrade()) return false;
        if (!(level instanceof ServerLevel serverLevel)) return false;
        GraphKey graphKey = getGraphKey();
        if (graphKey == null || !graphKey.isValid()) return false;
        TransferGraph graph = TransferGraphStorage.get(serverLevel.getServer()).graphFor(graphKey);
        var node = graph.findNode(chestId, level.dimension().location().toString(), worldPosition);
        return node != null && graph.hasOutgoing(node.getId());
    }

    // ===== NBT 序列化 =====

    public void saveToItem(ItemStack stack, HolderLookup.Provider reg) {
        if (stack == null || stack.isEmpty()) return;
        CompoundTag chestTag = new CompoundTag();
        saveAdditional(chestTag, reg);

        CustomData data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        CompoundTag root = data.copyTag();
        root.put(ITEM_DATA_ROOT, chestTag);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
    }

    public void applyDroppedItemName(ItemStack stack) {
        if (stack == null || stack.isEmpty() || chestId == null || chestId.isBlank()) return;
        stack.set(DataComponents.ITEM_NAME, Component.translatable(stack.getDescriptionId()).append(" (" + chestId + ")"));
    }

    public boolean loadFromItem(ItemStack stack, HolderLookup.Provider reg) {
        if (stack == null || stack.isEmpty()) return false;
        CustomData data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        CompoundTag root = data.copyTag();
        if (!root.contains(ITEM_DATA_ROOT, Tag.TAG_COMPOUND)) return false;

        loadAdditional(root.getCompound(ITEM_DATA_ROOT), reg);
        if (level instanceof ServerLevel serverLevel) {
            relocationHandled = false;
            handleRelocationIfMoved(serverLevel);
        }
        storageDirty = true;
        setChanged();
        return true;
    }

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

        ListTag upgradeList = new ListTag();
        for (int i = 0; i < upgradeCounts.length; i++) {
            if (upgradeCounts[i] <= 0) continue;
            CompoundTag upgradeTag = new CompoundTag();
            upgradeTag.putInt("slot", i);
            upgradeTag.putInt("count", upgradeCounts[i]);
            upgradeList.add(upgradeTag);
        }
        tag.put("UpgradeSlots", upgradeList);

        tag.putInt("EnergyStored", getEnergyStored());
        tag.putInt("StressOutputSpeedRpm", stressOutputSpeedRpm);
        tag.putBoolean("StressOutputReversed", stressOutputReversed);

        CompoundTag furnaceTag = new CompoundTag();
        ListTag furnaceItems = new ListTag();
        for (int i = 0; i < suiteFurnaceItems.size(); i++) {
            ItemStack stack = suiteFurnaceItems.get(i);
            if (stack.isEmpty()) continue;
            CompoundTag slotTag = new CompoundTag();
            slotTag.putInt("Slot", i);
            slotTag.put("Stack", stack.saveOptional(reg));
            furnaceItems.add(slotTag);
        }
        furnaceTag.put("Items", furnaceItems);
        furnaceTag.putInt("LitTime", suiteFurnaceLitTime);
        furnaceTag.putInt("LitDuration", suiteFurnaceLitDuration);
        furnaceTag.putInt("CookingProgress", suiteFurnaceCookingProgress);
        furnaceTag.putInt("CookingTotalTime", suiteFurnaceCookingTotalTime);
        furnaceTag.putInt("Mode", suiteFurnaceMode);
        tag.put("SuiteFurnace", furnaceTag);

        CompoundTag suiteOrderTag = new CompoundTag();
        suiteOrders.save(suiteOrderTag, reg);
        tag.put("SuiteOrders", suiteOrderTag);

        ListTag sideList = new ListTag();
        for (ResourceKind kind : ResourceKind.values()) {
            for (RelativeSide side : RelativeSide.values()) {
                SideMode mode = getSideMode(kind, side);
                CompoundTag sideTag = new CompoundTag();
                sideTag.putString("Kind", kind.name());
                sideTag.putString("Side", side.name());
                sideTag.putString("Mode", mode.name());
                sideList.add(sideTag);
            }
        }
        tag.put("SideConfig", sideList);

        // 保存基础信息
        tag.putString("ChestId", chestId);
        tag.putUUID("ChestUuid", getChestUUID());
        if (ownerUUID != null) tag.putUUID("Owner", ownerUUID);
        tag.putString("GraphKind", graphKind.name());
        if (graphKind == GraphKey.Kind.PROTECTED && graphTeamId != null) tag.putUUID("GraphTeamId", graphTeamId);
        tag.putBoolean("TransferEnabled", transferEnabled);
        tag.putBoolean("VoidModeEnabled", voidModeEnabled);
        tag.putBoolean("OfflineSnapshotEnabled", offlineSnapshotEnabled);
        tag.putInt("TransferRateLimit", transferRateLimit);
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

        // 保存当前坐标，用于后续检测方块是否被搬运（Mekanism Cardboard Box 等）
        if (level != null) {
            tag.putString("LastKnownDimension", level.dimension().location().toString());
            tag.putLong("LastKnownPos", worldPosition.asLong());
        }
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

        Arrays.fill(upgradeCounts, 0);
        ListTag upgradeList = tag.getList("UpgradeSlots", Tag.TAG_COMPOUND);
        for (Tag t : upgradeList) {
            CompoundTag upgradeTag = (CompoundTag) t;
            int slot = upgradeTag.getInt("slot");
            int count = upgradeTag.getInt("count");
            if (slot >= 0 && slot < upgradeCounts.length && count > 0 && isUpgradeSlotEnabled(slot)) {
                upgradeCounts[slot] = count;
            }
        }

        energyStored = Math.max(0, Math.min(tag.getInt("EnergyStored"), getMaxEnergyStored()));
        stressOutputSpeedRpm = normalizeStressOutputSpeed(tag.getInt("StressOutputSpeedRpm"));
        stressOutputReversed = tag.getBoolean("StressOutputReversed");

        for (int i = 0; i < suiteFurnaceItems.size(); i++) suiteFurnaceItems.set(i, ItemStack.EMPTY);
        suiteFurnaceLitTime = 0;
        suiteFurnaceLitDuration = 0;
        suiteFurnaceCookingProgress = 0;
        suiteFurnaceCookingTotalTime = 0;
        suiteFurnaceMode = 0;
        CompoundTag furnaceTag = tag.getCompound("SuiteFurnace");
        ListTag furnaceItems = furnaceTag.getList("Items", Tag.TAG_COMPOUND);
        for (Tag t : furnaceItems) {
            CompoundTag slotTag = (CompoundTag) t;
            int slot = slotTag.getInt("Slot");
            if (slot < 0 || slot >= suiteFurnaceItems.size()) continue;
            ItemStack stack = ItemStack.parseOptional(reg, slotTag.getCompound("Stack"));
            suiteFurnaceItems.set(slot, stack);
        }
        suiteFurnaceLitTime = Math.max(0, furnaceTag.getInt("LitTime"));
        suiteFurnaceLitDuration = Math.max(0, furnaceTag.getInt("LitDuration"));
        suiteFurnaceCookingProgress = Math.max(0, furnaceTag.getInt("CookingProgress"));
        suiteFurnaceCookingTotalTime = Math.max(0, furnaceTag.getInt("CookingTotalTime"));
        suiteFurnaceMode = Math.max(0, furnaceTag.getInt("Mode"));

        suiteOrders.load(tag.getCompound("SuiteOrders"), reg);

        resetDefaultSideConfig();
        ListTag sideList = tag.getList("SideConfig", Tag.TAG_COMPOUND);
        for (Tag t : sideList) {
            CompoundTag sideTag = (CompoundTag) t;
            try {
                ResourceKind kind = ResourceKind.valueOf(sideTag.getString("Kind"));
                RelativeSide side = RelativeSide.valueOf(sideTag.getString("Side"));
                SideMode mode = SideMode.valueOf(sideTag.getString("Mode"));
                setDefault(kind, side, mode);
            } catch (IllegalArgumentException ignored) {
            }
        }
        normalizeSingleFunctionSideConfig();

        // 加载基础信息
        chestId = tag.getString("ChestId");
        chestUUID = tag.hasUUID("ChestUuid") ? tag.getUUID("ChestUuid") : UUID.randomUUID();
        if (tag.hasUUID("Owner")) ownerUUID = tag.getUUID("Owner");
        try {
            graphKind = tag.contains("GraphKind") ? GraphKey.Kind.valueOf(tag.getString("GraphKind")) : GraphKey.Kind.PRIVATE;
        } catch (IllegalArgumentException e) {
            graphKind = GraphKey.Kind.PRIVATE;
        }
        graphTeamId = tag.hasUUID("GraphTeamId") ? tag.getUUID("GraphTeamId") : null;
        if (graphKind != GraphKey.Kind.PROTECTED) graphTeamId = null;
        transferEnabled = tag.getBoolean("TransferEnabled");
        voidModeEnabled = tag.getBoolean("VoidModeEnabled");
        offlineSnapshotEnabled = tag.getBoolean("OfflineSnapshotEnabled");
        transferRateLimit = tag.getInt("TransferRateLimit");
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

        // 加载历史坐标（用于 onLoad 阶段检测搬运）
        lastKnownDimensionKey = tag.contains("LastKnownDimension") ? tag.getString("LastKnownDimension") : "";
        lastKnownPosLong = tag.contains("LastKnownPos") ? tag.getLong("LastKnownPos") : Long.MIN_VALUE;

        if (level != null && level.isClientSide) requestModelDataUpdate();
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
