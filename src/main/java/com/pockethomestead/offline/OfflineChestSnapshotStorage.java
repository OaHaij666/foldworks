package com.pockethomestead.offline;

import com.pockethomestead.blockentity.BaseChestBlockEntity;
import com.pockethomestead.blockentity.HomesteadStressEndpoint;
import com.pockethomestead.blockentity.StoredItemStack;
import com.pockethomestead.config.ModConfig;
import com.pockethomestead.production.ProductionStatsStorage;
import com.pockethomestead.space.SpaceData;
import com.pockethomestead.space.SpaceManager;
import com.pockethomestead.transfer.GraphKey;
import com.pockethomestead.transfer.TransferEdge;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class OfflineChestSnapshotStorage extends SavedData {
    public static final String DATA_NAME = "pockethomestead_offline_chest_snapshots";
    private static final int BUCKET_TICKS = 20 * 10;
    private static final int PRUNE_INTERVAL_TICKS = 20 * 10;

    private final Map<String, Snapshot> snapshots = new LinkedHashMap<>();
    private final Map<String, Map<String, LinkedHashMap<Long, RateBucket>>> externalRates = new LinkedHashMap<>();
    private final List<Snapshot> tickSortBuffer = new ArrayList<>();
    private long lastPruneGameTime = Long.MIN_VALUE;

    public static OfflineChestSnapshotStorage get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(factory(), DATA_NAME);
    }

    public static SavedData.Factory<OfflineChestSnapshotStorage> factory() {
        return new SavedData.Factory<>(OfflineChestSnapshotStorage::new, OfflineChestSnapshotStorage::load);
    }

    public Collection<Snapshot> snapshots() {
        return List.copyOf(snapshots.values());
    }

    public Snapshot findSnapshot(String dimensionKey, BlockPos pos, String chestId) {
        String location = locationKey(dimensionKey, pos);
        for (Snapshot snapshot : snapshots.values()) {
            if (snapshot.locationKey().equals(location) && snapshot.chestId.equals(chestId)) return snapshot;
        }
        return null;
    }

    public void captureLoaded(BaseChestBlockEntity chest, long gameTime) {
        if (!validChest(chest)) return;
        Snapshot snapshot = snapshotFrom(chest, true, gameTime);
        snapshots.put(snapshot.key(), snapshot);
        setDirty();
    }

    public void captureUnloaded(BaseChestBlockEntity chest, long gameTime) {
        if (!validChest(chest)) return;
        Snapshot snapshot = snapshotFrom(chest, false, gameTime);
        Snapshot previous = findSnapshot(snapshot.dimensionKey, snapshot.pos(), snapshot.chestId);
        if (previous != null && previous.lastSimulatedGameTime > gameTime) {
            snapshot.items.clear();
            snapshot.items.addAll(previous.copyItems());
            snapshot.fluids.clear();
            snapshot.fluids.putAll(previous.fluids);
            snapshot.energyStored = previous.energyStored;
            snapshot.residuals.clear();
            snapshot.residuals.putAll(previous.residuals);
            snapshot.stressLeases.clear();
            snapshot.stressLeases.putAll(previous.copyStressLeases());
            snapshot.lastSimulatedGameTime = previous.lastSimulatedGameTime;
        }
        snapshots.put(snapshot.key(), snapshot);
        setDirty();
    }

    /**
     * 将离线快照合并到刚加载的箱子。
     *
     * 调用点约束：仅可在 {@link BaseChestBlockEntity#onLoad()} 中调用——此时箱子刚从磁盘加载、
     * 尚未被玩家修改，合并离线进度安全。若在加载后被调用（如玩家正在操作箱子），会覆盖玩家修改。
     * 已通过 {@code snapshot.lastSimulatedGameTime > snapshot.lastLoadedGameTime} 校验确保只应用离线期间产生的变化。
     */
    public void applySnapshotToLoadedChest(BaseChestBlockEntity chest, long gameTime) {
        if (!validChest(chest)) return;
        Snapshot snapshot = findSnapshot(chest.getLevel().dimension().location().toString(), chest.getBlockPos(), chest.getChestId());
        if (snapshot != null
                && snapshot.owner != null
                && snapshot.owner.equals(chest.getOwnerUUID())
                && snapshot.lastSimulatedGameTime > snapshot.lastLoadedGameTime) {
            chest.replaceStorageFromOfflineSnapshot(snapshot.copyItems(), snapshot.fluidMap(), snapshot.energyStored());
        }
        captureLoaded(chest, gameTime);
    }

    public void deleteSnapshot(String dimensionKey, BlockPos pos) {
        String location = locationKey(dimensionKey, pos);
        snapshots.entrySet().removeIf(entry -> entry.getValue().locationKey().equals(location));
        externalRates.remove(location);
        setDirty();
    }

    public void recordExternalChange(BaseChestBlockEntity chest, String resourceKey, int amount, boolean input, long gameTime) {
        if (!validChest(chest) || resourceKey == null || resourceKey.isBlank() || amount <= 0) return;
        Snapshot snapshot = findSnapshot(chest.getLevel().dimension().location().toString(), chest.getBlockPos(), chest.getChestId());
        if (snapshot == null) {
            snapshot = snapshotFrom(chest, true, gameTime);
            snapshots.put(snapshot.key(), snapshot);
        }
        String location = snapshot.locationKey();
        long start = (gameTime / BUCKET_TICKS) * BUCKET_TICKS;
        RateBucket bucket = externalRates
                .computeIfAbsent(location, key -> new LinkedHashMap<>())
                .computeIfAbsent(resourceKey, key -> new LinkedHashMap<>())
                .computeIfAbsent(start, RateBucket::new);
        if (input) bucket.input += amount;
        else bucket.output += amount;
        // 节流：仅当距上次 prune 超过 PRUNE_INTERVAL_TICKS（10 秒）才执行，避免每次外部变更都全量遍历
        if (gameTime - lastPruneGameTime >= PRUNE_INTERVAL_TICKS) {
            pruneRates(gameTime);
            lastPruneGameTime = gameTime;
        }
        setDirty();
    }

    public void tick(MinecraftServer server) {
        if (server == null) return;
        long gameTime = server.overworld().getGameTime();
        int budget = Math.max(1, ModConfig.OFFLINE_SIMULATION_SNAPSHOTS_PER_SECOND.get());
        int processed = 0;
        // 复用排序缓冲区避免每秒 new ArrayList 的 GC 压力；sort 本身仍 O(n log n) 但数百快照下开销可接受
        tickSortBuffer.clear();
        tickSortBuffer.addAll(snapshots.values());
        tickSortBuffer.sort(Comparator.comparingLong(snapshot -> snapshot.lastSimulatedGameTime));
        for (Snapshot snapshot : tickSortBuffer) {
            if (processed >= budget) break;
            if (snapshot.loaded) continue;
            processed++;
            tickSnapshot(server, snapshot, gameTime);
        }
        pruneRates(gameTime);
        lastPruneGameTime = gameTime;
    }

    private void tickSnapshot(MinecraftServer server, Snapshot snapshot, long gameTime) {
        if (snapshot.lastSimulatedGameTime <= 0) snapshot.lastSimulatedGameTime = gameTime;
        if (!snapshot.hasNetworkUpgrade) {
            snapshot.status = SnapshotStatus.OFFLINE_DISABLED;
            return;
        }
        if (!snapshot.shouldSimulate(server)) {
            snapshot.status = SnapshotStatus.OFFLINE_DISABLED;
            return;
        }
        long elapsed = Math.max(0, gameTime - snapshot.lastSimulatedGameTime);
        int maxCatchUpTicks = Math.max(1, ModConfig.OFFLINE_SIMULATION_MAX_CATCH_UP_SECONDS.get()) * 20;
        elapsed = Math.min(elapsed, maxCatchUpTicks);
        if (elapsed <= 0) return;
        applyExternalRates(snapshot, gameTime, elapsed);
        OfflineGraphTransferExecutor.run(server, this, snapshot, gameTime);
        snapshot.lastSimulatedGameTime = gameTime;
        snapshot.status = SnapshotStatus.OFFLINE_SIMULATED;
        refreshProductionInventory(server, snapshot);
        setDirty();
    }

    private void applyExternalRates(Snapshot snapshot, long gameTime, long elapsedTicks) {
        Map<String, LinkedHashMap<Long, RateBucket>> byResource = externalRates.get(snapshot.locationKey());
        if (byResource == null || byResource.isEmpty()) {
            snapshot.statusMessage = "无历史速率";
            return;
        }
        long windowTicks = Math.max(BUCKET_TICKS, ModConfig.OFFLINE_SIMULATION_RATE_WINDOW_SECONDS.get() * 20L);
        long minSampleTicks = Math.max(BUCKET_TICKS, ModConfig.OFFLINE_SIMULATION_MIN_SAMPLE_SECONDS.get() * 20L);
        long cutoff = gameTime - windowTicks;
        boolean applied = false;
        for (Map.Entry<String, LinkedHashMap<Long, RateBucket>> entry : byResource.entrySet()) {
            String resource = entry.getKey();
            int input = 0;
            int output = 0;
            long first = Long.MAX_VALUE;
            long last = Long.MIN_VALUE;
            for (RateBucket bucket : entry.getValue().values()) {
                if (bucket.start < cutoff) continue;
                input += bucket.input;
                output += bucket.output;
                first = Math.min(first, bucket.start);
                last = Math.max(last, bucket.start + BUCKET_TICKS);
            }
            // 时间窗上限 gameTime：最新桶 start+BUCKET_TICKS 可能超过当前 gameTime，
            // 导致分母偏大、估算速率偏低。用 gameTime 作为上界避免高估时间窗。
            last = Math.min(gameTime, last);
            if (first == Long.MAX_VALUE || last - first < minSampleTicks) continue;
            double net = (input - output) * (double) elapsedTicks / Math.max(1, last - first);
            double withResidual = snapshot.residuals.getOrDefault(resource, 0.0) + net;
            int whole = withResidual > 0 ? (int) Math.floor(withResidual) : (int) Math.ceil(withResidual);
            snapshot.residuals.put(resource, withResidual - whole);
            if (whole > 0) applied |= snapshot.addResource(resource, whole) > 0;
            else if (whole < 0) applied |= snapshot.removeResource(resource, -whole) > 0;
        }
        snapshot.statusMessage = applied ? "按历史速率估算" : "无可应用的历史速率";
    }

    public void recordSnapshotGraphInput(MinecraftServer server, Snapshot snapshot, String resourceKey, int amount, long gameTime) {
        recordSnapshotProduction(server, snapshot, resourceKey, amount, true, gameTime);
    }

    public void recordSnapshotGraphOutput(MinecraftServer server, Snapshot snapshot, String resourceKey, int amount, long gameTime) {
        recordSnapshotProduction(server, snapshot, resourceKey, amount, false, gameTime);
    }

    private void recordSnapshotProduction(MinecraftServer server, Snapshot snapshot, String resourceKey, int amount, boolean input, long gameTime) {
        if (server == null || snapshot.owner == null || resourceKey == null || amount <= 0) return;
        ProductionStatsStorage stats = ProductionStatsStorage.get(server);
        String chestKey = ProductionStatsStorage.chestKey(snapshot.dimensionKey, snapshot.pos());
        if (input) stats.recordInput(snapshot.owner, chestKey, resourceKey, amount, gameTime);
        else stats.recordOutput(snapshot.owner, chestKey, resourceKey, amount, gameTime);
        refreshProductionInventory(server, snapshot);
        setDirty();
    }

    private void refreshProductionInventory(MinecraftServer server, Snapshot snapshot) {
        if (server == null || snapshot.owner == null) return;
        ProductionStatsStorage.get(server).refreshChestInventory(
                snapshot.owner,
                ProductionStatsStorage.chestKey(snapshot.dimensionKey, snapshot.pos()),
                snapshot.productionResourceSnapshot()
        );
    }

    private Snapshot snapshotFrom(BaseChestBlockEntity chest, boolean loaded, long gameTime) {
        String dimensionKey = chest.getLevel().dimension().location().toString();
        SpaceData space = SpaceManager.getInstance().getSpaceByDimension(chest.getLevel().dimension().location());
        Snapshot snapshot = new Snapshot();
        snapshot.dimensionKey = dimensionKey;
        snapshot.posLong = chest.getBlockPos().asLong();
        snapshot.chestId = chest.getChestId();
        snapshot.owner = chest.getOwnerUUID();
        snapshot.spaceId = space == null ? null : space.getSpaceId();
        snapshot.loaded = loaded;
        snapshot.lastLoadedGameTime = gameTime;
        snapshot.lastSimulatedGameTime = loaded ? gameTime : Math.max(gameTime, snapshot.lastSimulatedGameTime);
        snapshot.chestOfflineEnabled = chest.isOfflineSnapshotEnabled();
        snapshot.hasNetworkUpgrade = chest.hasNetworkUpgrade();
        snapshot.voidModeEnabled = chest.isVoidModeEnabled();
        snapshot.networkBandwidth = chest.getNetworkBandwidthCapacity();
        snapshot.maxItemCapacity = chest.getMaxItemCapacity();
        snapshot.maxFluidTypes = chest.getMaxFluidTypes();
        snapshot.maxFluidCapacityPerTypeMb = chest.getMaxFluidCapacityPerTypeMb();
        snapshot.hasEnergyUpgrade = chest.hasEnergyUpgrade();
        snapshot.energyStored = chest.getEnergyStored();
        snapshot.maxEnergyStored = chest.getMaxEnergyStored();
        snapshot.energyTransferLimit = chest.getEnergyTransferLimit();
        snapshot.hasStressUpgrade = chest.hasStressUpgrade();
        snapshot.stressTransferLimit = chest.getStressTransferLimit();
        snapshot.configuredStressInputSides = chest.configuredStressInputSides();
        snapshot.configuredStressOutputSides = chest.configuredStressOutputSides();
        snapshot.stressOutputSpeedRpm = chest.getStressOutputSpeedRpm();
        snapshot.stressOutputReversed = chest.isStressOutputReversed();
        HomesteadStressEndpoint stressEndpoint = loaded ? stressEndpoint(chest) : null;
        if (stressEndpoint != null && stressEndpoint.canSendGraphStress()) {
            snapshot.sampledStressSpeed = stressEndpoint.graphStressSpeed();
            snapshot.sampledStressCapacity = Math.max(0, stressEndpoint.graphStressCapacity());
        }
        snapshot.graphKind = chest.getGraphKind();
        snapshot.graphTeamId = chest.getGraphTeamId();
        snapshot.items.clear();
        snapshot.items.addAll(chest.getStoredItems());
        snapshot.fluids.clear();
        for (Map.Entry<Fluid, Integer> entry : chest.getAllFluids().entrySet()) {
            ResourceLocation id = BuiltInRegistries.FLUID.getKey(entry.getKey());
            if (id != null && entry.getValue() > 0) snapshot.fluids.put(id.toString(), entry.getValue());
        }
        snapshot.status = loaded ? SnapshotStatus.LOADED : SnapshotStatus.OFFLINE_DISABLED;
        snapshot.statusMessage = loaded ? "已加载" : "等待离线模拟";
        return snapshot;
    }

    private static HomesteadStressEndpoint stressEndpoint(BaseChestBlockEntity chest) {
        if (chest == null || chest.isRemoved() || !(chest.getLevel() instanceof ServerLevel serverLevel)) return null;
        LevelChunk chunk = serverLevel.getChunkSource().getChunkNow(chest.getBlockPos().getX() >> 4, chest.getBlockPos().getZ() >> 4);
        if (chunk == null) return null;
        BlockEntity blockEntity = chunk.getBlockEntity(chest.getBlockPos(), LevelChunk.EntityCreationType.CHECK);
        if (blockEntity instanceof HomesteadStressEndpoint endpoint
                && endpoint.homesteadChest() == chest) {
            return endpoint;
        }
        return null;
    }

    private static boolean validChest(BaseChestBlockEntity chest) {
        return chest != null
                && chest.getLevel() != null
                && !chest.getLevel().isClientSide
                && chest.getOwnerUUID() != null
                && chest.getChestId() != null
                && !chest.getChestId().isBlank();
    }

    private void pruneRates(long gameTime) {
        long cutoff = gameTime - Math.max(BUCKET_TICKS, ModConfig.OFFLINE_SIMULATION_RATE_WINDOW_SECONDS.get() * 40L);
        for (Map<String, LinkedHashMap<Long, RateBucket>> byResource : externalRates.values()) {
            for (LinkedHashMap<Long, RateBucket> buckets : byResource.values()) {
                buckets.entrySet().removeIf(entry -> entry.getKey() < cutoff);
            }
            byResource.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        }
        externalRates.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    private static String locationKey(String dimensionKey, BlockPos pos) {
        return (dimensionKey == null ? "" : dimensionKey) + "|" + (pos == null ? 0L : pos.asLong());
    }

    public static OfflineChestSnapshotStorage load(CompoundTag tag, HolderLookup.Provider reg) {
        OfflineChestSnapshotStorage storage = new OfflineChestSnapshotStorage();
        ListTag snapshotList = tag.getList("Snapshots", Tag.TAG_COMPOUND);
        for (int i = 0; i < snapshotList.size(); i++) {
            Snapshot snapshot = Snapshot.load(snapshotList.getCompound(i), reg);
            if (snapshot != null) storage.snapshots.put(snapshot.key(), snapshot);
        }
        ListTag rateList = tag.getList("ExternalRates", Tag.TAG_COMPOUND);
        for (int i = 0; i < rateList.size(); i++) {
            CompoundTag rateTag = rateList.getCompound(i);
            String location = rateTag.getString("Location");
            String resource = rateTag.getString("Resource");
            ListTag buckets = rateTag.getList("Buckets", Tag.TAG_COMPOUND);
            LinkedHashMap<Long, RateBucket> rows = storage.externalRates
                    .computeIfAbsent(location, key -> new LinkedHashMap<>())
                    .computeIfAbsent(resource, key -> new LinkedHashMap<>());
            for (int j = 0; j < buckets.size(); j++) {
                RateBucket bucket = RateBucket.load(buckets.getCompound(j));
                rows.put(bucket.start, bucket);
            }
        }
        return storage;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider reg) {
        ListTag snapshotList = new ListTag();
        for (Snapshot snapshot : snapshots.values()) snapshotList.add(snapshot.save(reg));
        tag.put("Snapshots", snapshotList);

        ListTag rateList = new ListTag();
        for (Map.Entry<String, Map<String, LinkedHashMap<Long, RateBucket>>> locationEntry : externalRates.entrySet()) {
            for (Map.Entry<String, LinkedHashMap<Long, RateBucket>> resourceEntry : locationEntry.getValue().entrySet()) {
                CompoundTag rateTag = new CompoundTag();
                rateTag.putString("Location", locationEntry.getKey());
                rateTag.putString("Resource", resourceEntry.getKey());
                ListTag buckets = new ListTag();
                for (RateBucket bucket : resourceEntry.getValue().values()) buckets.add(bucket.save());
                rateTag.put("Buckets", buckets);
                rateList.add(rateTag);
            }
        }
        tag.put("ExternalRates", rateList);
        return tag;
    }

    public enum SnapshotStatus {
        LOADED,
        OFFLINE_SIMULATED,
        OFFLINE_DISABLED,
        MISSING
    }

    public static final class Snapshot {
        private String dimensionKey = "";
        private long posLong;
        private String chestId = "";
        private UUID owner;
        private UUID spaceId;
        private boolean loaded;
        private boolean chestOfflineEnabled;
        private boolean hasNetworkUpgrade;
        private boolean voidModeEnabled;
        private int networkBandwidth;
        private int maxItemCapacity;
        private int maxFluidTypes;
        private int maxFluidCapacityPerTypeMb;
        private boolean hasEnergyUpgrade;
        private int energyStored;
        private int maxEnergyStored;
        private int energyTransferLimit;
        private boolean hasStressUpgrade;
        private int stressTransferLimit;
        private int configuredStressInputSides;
        private int configuredStressOutputSides;
        private int stressOutputSpeedRpm;
        private boolean stressOutputReversed;
        private float sampledStressSpeed;
        private float sampledStressCapacity;
        private int graphStressBandwidthUsed;
        private long graphStressBandwidthGameTime = Long.MIN_VALUE;
        private GraphKey.Kind graphKind = GraphKey.Kind.PRIVATE;
        private UUID graphTeamId;
        private long lastLoadedGameTime;
        private long lastSimulatedGameTime;
        private SnapshotStatus status = SnapshotStatus.OFFLINE_DISABLED;
        private String statusMessage = "";
        private final List<StoredItemStack> items = new ArrayList<>();
        private final Map<String, Integer> fluids = new LinkedHashMap<>();
        private final Map<String, Double> residuals = new LinkedHashMap<>();
        private final Map<String, StressLease> stressLeases = new LinkedHashMap<>();

        public String dimensionKey() { return dimensionKey; }
        public BlockPos pos() { return BlockPos.of(posLong); }
        public long posLong() { return posLong; }
        public String chestId() { return chestId; }
        public UUID owner() { return owner; }
        public UUID spaceId() { return spaceId; }
        public boolean loaded() { return loaded; }
        public boolean chestOfflineEnabled() { return chestOfflineEnabled; }
        public boolean hasNetworkUpgrade() { return hasNetworkUpgrade; }
        public boolean voidModeEnabled() { return voidModeEnabled; }
        public int networkBandwidth() { return networkBandwidth; }
        public boolean hasEnergyUpgrade() { return hasEnergyUpgrade; }
        public int energyStored() { return Math.max(0, Math.min(energyStored, maxEnergyStored)); }
        public int maxEnergyStored() { return maxEnergyStored; }
        public int energyTransferLimit() { return energyTransferLimit; }
        public boolean hasStressUpgrade() { return hasStressUpgrade; }
        public int stressTransferLimit() { return stressTransferLimit; }
        public int configuredStressInputSides() { return configuredStressInputSides; }
        public int configuredStressOutputSides() { return configuredStressOutputSides; }
        public int stressOutputSpeedRpm() { return stressOutputSpeedRpm; }
        public boolean stressOutputReversed() { return stressOutputReversed; }
        public SnapshotStatus status() { return status; }
        public String statusMessage() { return statusMessage; }
        public long lastSimulatedGameTime() { return lastSimulatedGameTime; }

        public GraphKey graphKey() {
            return switch (graphKind) {
                case PUBLIC -> GraphKey.publicGraph();
                case PROTECTED -> graphTeamId == null ? null : GraphKey.protectedGraph(graphTeamId);
                case SPACE -> spaceId == null ? null : GraphKey.spaceGraph(spaceId);
                case PRIVATE -> owner == null ? null : GraphKey.privateGraph(owner);
            };
        }

        public boolean matchesGraph(GraphKey key) {
            GraphKey own = graphKey();
            return own != null && own.equals(key);
        }

        public boolean shouldSimulate(MinecraftServer server) {
            if (chestOfflineEnabled) return true;
            if (spaceId == null) return false;
            SpaceData space = SpaceManager.getInstance().getSpace(spaceId);
            return space != null && space.isOfflineSimulationEnabled();
        }

        public String key() {
            return locationKey() + "|" + chestId + "|" + (owner == null ? "" : owner);
        }

        public String locationKey() {
            return OfflineChestSnapshotStorage.locationKey(dimensionKey, pos());
        }

        public List<StoredItemStack> copyItems() {
            List<StoredItemStack> copy = new ArrayList<>();
            for (StoredItemStack entry : items) copy.add(new StoredItemStack(entry.prototype(), entry.count()));
            return copy;
        }

        public Map<Fluid, Integer> fluidMap() {
            Map<Fluid, Integer> result = new LinkedHashMap<>();
            for (Map.Entry<String, Integer> entry : fluids.entrySet()) {
                ResourceLocation id = ResourceLocation.tryParse(entry.getKey());
                if (id == null) continue;
                Fluid fluid = BuiltInRegistries.FLUID.get(id);
                if (fluid != Fluids.EMPTY && entry.getValue() > 0) result.put(fluid, entry.getValue());
            }
            return result;
        }

        public List<StoredItemStack> items() {
            return copyItems();
        }

        public Map<String, Integer> fluids() {
            return Map.copyOf(fluids);
        }

        public int remainingEnergyCapacity() {
            return Math.max(0, maxEnergyStored - energyStored());
        }

        public int receiveEnergy(int amount) {
            if (!hasEnergyUpgrade || amount <= 0) return 0;
            int accepted = Math.min(amount, Math.min(energyTransferLimit, remainingEnergyCapacity()));
            if (accepted > 0) energyStored = energyStored() + accepted;
            return accepted;
        }

        public int extractEnergy(int amount) {
            if (!hasEnergyUpgrade || amount <= 0) return 0;
            int extracted = Math.min(amount, Math.min(energyTransferLimit, energyStored()));
            if (extracted > 0) energyStored = energyStored() - extracted;
            return extracted;
        }

        private int addEnergyStored(int amount) {
            if (!hasEnergyUpgrade || amount <= 0) return 0;
            int accepted = Math.min(amount, remainingEnergyCapacity());
            if (accepted > 0) energyStored = energyStored() + accepted;
            return accepted;
        }

        private int removeEnergyStored(int amount) {
            if (!hasEnergyUpgrade || amount <= 0) return 0;
            int removed = Math.min(amount, energyStored());
            if (removed > 0) energyStored = energyStored() - removed;
            return removed;
        }

        public int usedItemCapacity() {
            int total = 0;
            for (StoredItemStack entry : items) total += entry.count();
            return total;
        }

        public int remainingItemCapacity() {
            return Math.max(0, maxItemCapacity - usedItemCapacity());
        }

        public int addItem(ItemStack stack, int amount) {
            if (stack == null || stack.isEmpty() || amount <= 0) return 0;
            int accepted = Math.min(amount, remainingItemCapacity());
            if (accepted <= 0) return 0;
            for (StoredItemStack entry : items) {
                if (entry.matches(stack)) {
                    entry.grow(accepted);
                    return accepted;
                }
            }
            items.add(new StoredItemStack(stack, accepted));
            return accepted;
        }

        public int removeItem(ItemStack stack, int amount) {
            if (stack == null || stack.isEmpty() || amount <= 0) return 0;
            int remaining = amount;
            int removed = 0;
            for (int i = 0; i < items.size() && remaining > 0; i++) {
                StoredItemStack entry = items.get(i);
                if (!entry.matches(stack)) continue;
                int part = entry.shrink(remaining);
                removed += part;
                remaining -= part;
                if (entry.isEmpty()) {
                    items.remove(i);
                    i--;
                }
            }
            return removed;
        }

        public int addResource(String resourceKey, int amount) {
            if (resourceKey == null || amount <= 0) return 0;
            if (resourceKey.startsWith(TransferEdge.ITEM_PREFIX)) {
                String idValue = resourceKey.substring(TransferEdge.ITEM_PREFIX.length());
                ResourceLocation id = ResourceLocation.tryParse(idValue);
                if (id == null) return 0;
                Item item = BuiltInRegistries.ITEM.get(id);
                if (item == Items.AIR) return 0;
                return addItem(new ItemStack(item), amount);
            }
            if (resourceKey.startsWith(TransferEdge.FLUID_PREFIX)) {
                return addFluid(resourceKey.substring(TransferEdge.FLUID_PREFIX.length()), amount);
            }
            if (TransferEdge.ENERGY_FE.equals(resourceKey)) {
                return addEnergyStored(amount);
            }
            return 0;
        }

        public int removeResource(String resourceKey, int amount) {
            if (resourceKey == null || amount <= 0) return 0;
            if (resourceKey.startsWith(TransferEdge.ITEM_PREFIX)) {
                String idValue = resourceKey.substring(TransferEdge.ITEM_PREFIX.length());
                int remaining = amount;
                int removed = 0;
                for (int i = 0; i < items.size() && remaining > 0; i++) {
                    StoredItemStack entry = items.get(i);
                    ResourceLocation id = BuiltInRegistries.ITEM.getKey(entry.item());
                    if (id == null || !id.toString().equals(idValue)) continue;
                    int part = entry.shrink(remaining);
                    removed += part;
                    remaining -= part;
                    if (entry.isEmpty()) {
                        items.remove(i);
                        i--;
                    }
                }
                return removed;
            }
            if (resourceKey.startsWith(TransferEdge.FLUID_PREFIX)) {
                return removeFluid(resourceKey.substring(TransferEdge.FLUID_PREFIX.length()), amount);
            }
            if (TransferEdge.ENERGY_FE.equals(resourceKey)) {
                return removeEnergyStored(amount);
            }
            return 0;
        }

        public int addFluid(String fluidId, int amount) {
            if (fluidId == null || fluidId.isBlank() || amount <= 0) return 0;
            int current = fluids.getOrDefault(fluidId, 0);
            if (current <= 0 && fluids.size() >= maxFluidTypes) return 0;
            int accepted = Math.min(amount, Math.max(0, maxFluidCapacityPerTypeMb - current));
            if (accepted > 0) fluids.put(fluidId, current + accepted);
            return accepted;
        }

        public int removeFluid(String fluidId, int amount) {
            if (fluidId == null || amount <= 0) return 0;
            int current = fluids.getOrDefault(fluidId, 0);
            int removed = Math.min(amount, current);
            if (removed <= 0) return 0;
            int next = current - removed;
            if (next <= 0) fluids.remove(fluidId);
            else fluids.put(fluidId, next);
            return removed;
        }

        public int remainingFluidCapacity(String fluidId) {
            int current = fluids.getOrDefault(fluidId, 0);
            if (current <= 0 && fluids.size() >= maxFluidTypes) return 0;
            return Math.max(0, maxFluidCapacityPerTypeMb - current);
        }

        public boolean canSendGraphStress(long gameTime) {
            return graphStressSpeed(gameTime) != 0 && graphStressCapacity(gameTime) > 0;
        }

        public boolean canReceiveGraphStress() {
            return hasStressUpgrade && configuredStressOutputSides == 1 && stressTransferLimit > 0;
        }

        public float graphStressSpeed(long gameTime) {
            pruneStressLeases(gameTime);
            if (configuredStressOutputSides == 1) {
                float speed = leasedOutputSpeed();
                if (speed != 0 && graphStressCapacity(gameTime) > 0) return speed;
            }
            if (configuredStressInputSides == 1 && sampledStressSpeed != 0 && sampledStressCapacity > 0) return sampledStressSpeed;
            return 0;
        }

        public float graphStressCapacity(long gameTime) {
            pruneStressLeases(gameTime);
            if (configuredStressOutputSides == 1) {
                float capacity = 0;
                for (StressLease lease : stressLeases.values()) capacity += lease.capacity();
                if (capacity > 0) return Math.min(stressTransferLimit, capacity);
            }
            if (configuredStressInputSides == 1) return Math.min(stressTransferLimit, Math.max(0, sampledStressCapacity));
            return 0;
        }

        public float receiveGraphStressLease(String leaseId, float speed, float capacity, long gameTime) {
            if (!canReceiveGraphStress() || leaseId == null || leaseId.isBlank() || speed == 0 || capacity <= 0) return 0;
            pruneStressLeases(gameTime);
            float configuredSpeed = configuredStressOutputSpeed(speed);
            float otherCapacity = 0;
            for (Map.Entry<String, StressLease> entry : stressLeases.entrySet()) {
                if (leaseId.equals(entry.getKey())) continue;
                StressLease lease = entry.getValue();
                if (Float.compare(configuredStressOutputSpeed(lease.speed()), configuredSpeed) != 0) return 0;
                otherCapacity += lease.capacity();
            }
            float acceptedCapacity = Math.min(capacity, Math.max(0, stressTransferLimit - otherCapacity));
            if (acceptedCapacity <= 0) return 0;
            stressLeases.put(leaseId, new StressLease(speed, acceptedCapacity, gameTime + 40));
            return acceptedCapacity;
        }

        public void recordGraphStressBandwidthUse(long gameTime, int used) {
            graphStressBandwidthUsed = Math.max(0, Math.min(networkBandwidth, used));
            graphStressBandwidthGameTime = gameTime;
        }

        public int graphStressBandwidthUsed(long gameTime) {
            if (graphStressBandwidthGameTime == Long.MIN_VALUE || gameTime - graphStressBandwidthGameTime > 40) return 0;
            return Math.max(0, Math.min(networkBandwidth, graphStressBandwidthUsed));
        }

        private float configuredStressOutputSpeed(float sourceSpeed) {
            if (sourceSpeed == 0) return 0;
            float sign = Math.signum(sourceSpeed) * (stressOutputReversed ? -1.0f : 1.0f);
            float magnitude = stressOutputSpeedRpm > 0 ? stressOutputSpeedRpm : Math.abs(sourceSpeed);
            return sign * magnitude;
        }

        private float leasedOutputSpeed() {
            float speed = 0;
            for (StressLease lease : stressLeases.values()) {
                float configured = configuredStressOutputSpeed(lease.speed());
                if (configured == 0) continue;
                if (speed == 0) speed = configured;
                else if (Float.compare(speed, configured) != 0) return 0;
            }
            return speed;
        }

        private void pruneStressLeases(long gameTime) {
            stressLeases.entrySet().removeIf(entry -> entry.getValue().untilGameTime() < gameTime);
        }

        private Map<String, StressLease> copyStressLeases() {
            return new LinkedHashMap<>(stressLeases);
        }

        private Map<String, Integer> productionResourceSnapshot() {
            Map<String, Integer> result = new LinkedHashMap<>();
            for (StoredItemStack entry : items) {
                ResourceLocation id = BuiltInRegistries.ITEM.getKey(entry.item());
                if (id != null && entry.count() > 0) result.merge(TransferEdge.ITEM_PREFIX + id, entry.count(), Integer::sum);
            }
            for (Map.Entry<String, Integer> entry : fluids.entrySet()) {
                if (entry.getValue() > 0) result.merge(TransferEdge.FLUID_PREFIX + entry.getKey(), entry.getValue(), Integer::sum);
            }
            if (energyStored() > 0) result.merge(TransferEdge.ENERGY_FE, energyStored(), Integer::sum);
            return result;
        }

        private CompoundTag save(HolderLookup.Provider reg) {
            CompoundTag tag = new CompoundTag();
            tag.putString("Dimension", dimensionKey);
            tag.putLong("Pos", posLong);
            tag.putString("ChestId", chestId);
            if (owner != null) tag.putUUID("Owner", owner);
            if (spaceId != null) tag.putUUID("SpaceId", spaceId);
            tag.putBoolean("Loaded", loaded);
            tag.putBoolean("ChestOfflineEnabled", chestOfflineEnabled);
            tag.putBoolean("HasNetworkUpgrade", hasNetworkUpgrade);
            tag.putBoolean("VoidModeEnabled", voidModeEnabled);
            tag.putInt("NetworkBandwidth", networkBandwidth);
            tag.putInt("MaxItemCapacity", maxItemCapacity);
            tag.putInt("MaxFluidTypes", maxFluidTypes);
            tag.putInt("MaxFluidCapacityPerTypeMb", maxFluidCapacityPerTypeMb);
            tag.putBoolean("HasEnergyUpgrade", hasEnergyUpgrade);
            tag.putInt("EnergyStored", energyStored());
            tag.putInt("MaxEnergyStored", maxEnergyStored);
            tag.putInt("EnergyTransferLimit", energyTransferLimit);
            tag.putBoolean("HasStressUpgrade", hasStressUpgrade);
            tag.putInt("StressTransferLimit", stressTransferLimit);
            tag.putInt("ConfiguredStressInputSides", configuredStressInputSides);
            tag.putInt("ConfiguredStressOutputSides", configuredStressOutputSides);
            tag.putInt("StressOutputSpeedRpm", stressOutputSpeedRpm);
            tag.putBoolean("StressOutputReversed", stressOutputReversed);
            tag.putFloat("SampledStressSpeed", sampledStressSpeed);
            tag.putFloat("SampledStressCapacity", sampledStressCapacity);
            tag.putInt("GraphStressBandwidthUsed", graphStressBandwidthUsed);
            tag.putLong("GraphStressBandwidthGameTime", graphStressBandwidthGameTime);
            tag.putString("GraphKind", graphKind.name());
            if (graphTeamId != null) tag.putUUID("GraphTeamId", graphTeamId);
            tag.putLong("LastLoadedGameTime", lastLoadedGameTime);
            tag.putLong("LastSimulatedGameTime", lastSimulatedGameTime);
            tag.putString("Status", status.name());
            tag.putString("StatusMessage", statusMessage);

            ListTag itemList = new ListTag();
            for (StoredItemStack entry : items) {
                CompoundTag itemTag = new CompoundTag();
                itemTag.put("Stack", entry.prototype().saveOptional(reg));
                itemTag.putInt("Count", entry.count());
                itemList.add(itemTag);
            }
            tag.put("Items", itemList);

            ListTag fluidList = new ListTag();
            for (Map.Entry<String, Integer> entry : fluids.entrySet()) {
                CompoundTag fluidTag = new CompoundTag();
                fluidTag.putString("Id", entry.getKey());
                fluidTag.putInt("Amount", entry.getValue());
                fluidList.add(fluidTag);
            }
            tag.put("Fluids", fluidList);

            ListTag residualList = new ListTag();
            for (Map.Entry<String, Double> entry : residuals.entrySet()) {
                CompoundTag residualTag = new CompoundTag();
                residualTag.putString("Resource", entry.getKey());
                residualTag.putDouble("Value", entry.getValue());
                residualList.add(residualTag);
            }
            tag.put("Residuals", residualList);

            ListTag stressLeaseList = new ListTag();
            for (Map.Entry<String, StressLease> entry : stressLeases.entrySet()) {
                CompoundTag leaseTag = new CompoundTag();
                leaseTag.putString("Id", entry.getKey());
                leaseTag.putFloat("Speed", entry.getValue().speed());
                leaseTag.putFloat("Capacity", entry.getValue().capacity());
                leaseTag.putLong("Until", entry.getValue().untilGameTime());
                stressLeaseList.add(leaseTag);
            }
            tag.put("StressLeases", stressLeaseList);
            return tag;
        }

        private static Snapshot load(CompoundTag tag, HolderLookup.Provider reg) {
            Snapshot snapshot = new Snapshot();
            snapshot.dimensionKey = tag.getString("Dimension");
            snapshot.posLong = tag.getLong("Pos");
            snapshot.chestId = tag.getString("ChestId");
            snapshot.owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
            snapshot.spaceId = tag.hasUUID("SpaceId") ? tag.getUUID("SpaceId") : null;
            snapshot.loaded = tag.getBoolean("Loaded");
            snapshot.chestOfflineEnabled = tag.getBoolean("ChestOfflineEnabled");
            snapshot.hasNetworkUpgrade = tag.getBoolean("HasNetworkUpgrade");
            snapshot.voidModeEnabled = tag.getBoolean("VoidModeEnabled");
            snapshot.networkBandwidth = tag.getInt("NetworkBandwidth");
            snapshot.maxItemCapacity = tag.getInt("MaxItemCapacity");
            snapshot.maxFluidTypes = tag.getInt("MaxFluidTypes");
            snapshot.maxFluidCapacityPerTypeMb = tag.getInt("MaxFluidCapacityPerTypeMb");
            snapshot.hasEnergyUpgrade = tag.getBoolean("HasEnergyUpgrade");
            snapshot.energyStored = tag.getInt("EnergyStored");
            snapshot.maxEnergyStored = tag.getInt("MaxEnergyStored");
            snapshot.energyTransferLimit = tag.getInt("EnergyTransferLimit");
            snapshot.hasStressUpgrade = tag.getBoolean("HasStressUpgrade");
            snapshot.stressTransferLimit = tag.getInt("StressTransferLimit");
            snapshot.configuredStressInputSides = tag.getInt("ConfiguredStressInputSides");
            snapshot.configuredStressOutputSides = tag.getInt("ConfiguredStressOutputSides");
            snapshot.stressOutputSpeedRpm = tag.getInt("StressOutputSpeedRpm");
            snapshot.stressOutputReversed = tag.getBoolean("StressOutputReversed");
            snapshot.sampledStressSpeed = tag.getFloat("SampledStressSpeed");
            snapshot.sampledStressCapacity = tag.getFloat("SampledStressCapacity");
            snapshot.graphStressBandwidthUsed = tag.getInt("GraphStressBandwidthUsed");
            snapshot.graphStressBandwidthGameTime = tag.contains("GraphStressBandwidthGameTime") ? tag.getLong("GraphStressBandwidthGameTime") : Long.MIN_VALUE;
            try {
                snapshot.graphKind = tag.contains("GraphKind") ? GraphKey.Kind.valueOf(tag.getString("GraphKind")) : GraphKey.Kind.PRIVATE;
            } catch (IllegalArgumentException e) {
                snapshot.graphKind = GraphKey.Kind.PRIVATE;
            }
            snapshot.graphTeamId = tag.hasUUID("GraphTeamId") ? tag.getUUID("GraphTeamId") : null;
            snapshot.lastLoadedGameTime = tag.getLong("LastLoadedGameTime");
            snapshot.lastSimulatedGameTime = tag.getLong("LastSimulatedGameTime");
            try {
                snapshot.status = tag.contains("Status") ? SnapshotStatus.valueOf(tag.getString("Status")) : SnapshotStatus.OFFLINE_DISABLED;
            } catch (IllegalArgumentException e) {
                snapshot.status = SnapshotStatus.OFFLINE_DISABLED;
            }
            snapshot.statusMessage = tag.getString("StatusMessage");

            ListTag itemList = tag.getList("Items", Tag.TAG_COMPOUND);
            for (int i = 0; i < itemList.size(); i++) {
                CompoundTag itemTag = itemList.getCompound(i);
                ItemStack stack = ItemStack.parseOptional(reg, itemTag.getCompound("Stack"));
                int count = itemTag.getInt("Count");
                if (!stack.isEmpty() && count > 0) snapshot.items.add(new StoredItemStack(stack, count));
            }

            ListTag fluidList = tag.getList("Fluids", Tag.TAG_COMPOUND);
            for (int i = 0; i < fluidList.size(); i++) {
                CompoundTag fluidTag = fluidList.getCompound(i);
                String id = fluidTag.getString("Id");
                int amount = fluidTag.getInt("Amount");
                if (!id.isBlank() && amount > 0) snapshot.fluids.put(id, amount);
            }

            ListTag residualList = tag.getList("Residuals", Tag.TAG_COMPOUND);
            for (int i = 0; i < residualList.size(); i++) {
                CompoundTag residualTag = residualList.getCompound(i);
                snapshot.residuals.put(residualTag.getString("Resource"), residualTag.getDouble("Value"));
            }
            ListTag stressLeaseList = tag.getList("StressLeases", Tag.TAG_COMPOUND);
            for (int i = 0; i < stressLeaseList.size(); i++) {
                CompoundTag leaseTag = stressLeaseList.getCompound(i);
                String id = leaseTag.getString("Id");
                if (id.isBlank()) continue;
                snapshot.stressLeases.put(id, new StressLease(
                        leaseTag.getFloat("Speed"),
                        leaseTag.getFloat("Capacity"),
                        leaseTag.getLong("Until")
                ));
            }
            return snapshot;
        }
    }

    public record StressLease(float speed, float capacity, long untilGameTime) {}

    private static final class RateBucket {
        private final long start;
        private int input;
        private int output;

        private RateBucket(long start) {
            this.start = start;
        }

        private CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putLong("Start", start);
            tag.putInt("Input", input);
            tag.putInt("Output", output);
            return tag;
        }

        private static RateBucket load(CompoundTag tag) {
            RateBucket bucket = new RateBucket(tag.getLong("Start"));
            bucket.input = tag.getInt("Input");
            bucket.output = tag.getInt("Output");
            return bucket;
        }
    }
}
