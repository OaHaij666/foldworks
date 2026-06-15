package com.pockethomestead.blockentity;

import com.pockethomestead.config.ModConfig;
import com.pockethomestead.registry.ChestRegistryManager;
import net.minecraft.core.BlockPos;
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

import java.util.*;

/**
 * 箱子基类 - 实现容量系统（而非固定格子）
 * 支持最多1000方块的总容量，物品种类不限
 */
public abstract class BaseChestBlockEntity extends BlockEntity implements MenuProvider {

    // 容量系统：Item -> 数量（总容量从ModConfig读取）
    protected final Map<Item, Integer> itemStorage = new HashMap<>();

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
        // 服务端加载时注册到全局管理器（防重复注册）
        if (!level.isClientSide && ownerUUID != null && !chestId.isEmpty() && !registered) {
            ChestRegistryManager.getInstance().registerChest(ownerUUID, chestId, level, worldPosition, getChestType());
            registered = true;
        }
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
        return itemStorage.values().stream().mapToInt(Integer::intValue).sum();
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
        int remaining = getRemainingCapacity();
        int toAdd = Math.min(count, remaining);
        if (toAdd > 0) {
            itemStorage.merge(item, toAdd, Integer::sum);
            storageDirty = true;
            setChanged();
        }
        return toAdd;
    }

    /**
     * 尝试移除物品（尽力而为）
     * @return 实际移除的数量
     */
    public int removeItem(Item item, int count) {
        int current = itemStorage.getOrDefault(item, 0);
        int toRemove = Math.min(count, current);
        if (toRemove > 0) {
            int newCount = current - toRemove;
            if (newCount <= 0) {
                itemStorage.remove(item);
            } else {
                itemStorage.put(item, newCount);
            }
            storageDirty = true;
            setChanged();
        }
        return toRemove;
    }

    /**
     * 获取某物品的数量
     */
    public int getItemCount(Item item) {
        return itemStorage.getOrDefault(item, 0);
    }

    /**
     * 获取所有物品（只读视图）
     */
    public Map<Item, Integer> getAllItems() {
        return Collections.unmodifiableMap(itemStorage);
    }

    /**
     * 客户端同步物品（仅用于网络数据包）
     */
    public void clientSyncItems(Map<Item, Integer> items) {
        if (level != null && level.isClientSide) {
            itemStorage.clear();
            itemStorage.putAll(items);
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
        if (ownerUUID == null) return;
        if (!transferEnabled || boundTargetId.isEmpty()) return;

        // 使用位置hash + 配置偏移分散负载
        tickCounter++;
        int interval = ModConfig.TRANSFER_TICK_INTERVAL.get();
        int offset = Math.abs(worldPosition.hashCode()) % Math.max(1, ModConfig.TICK_OFFSET.get());
        if ((tickCounter + offset) % interval != 0) return;

        doTransferTick();
    }

    /**
     * 执行一次传输周期
     */
    protected void doTransferTick() {
        if (!(level instanceof ServerLevel serverLevel)) return;
        if (itemStorage.isEmpty()) return;

        ChestRegistryManager.ChestType myType = getChestType();
        ChestRegistryManager.ChestType targetType = (myType == ChestRegistryManager.ChestType.SUPPLY)
            ? ChestRegistryManager.ChestType.PICKUP
            : ChestRegistryManager.ChestType.SUPPLY;

        BaseChestBlockEntity target = ChestRegistryManager.getInstance()
            .findBoundChest(ownerUUID, boundTargetId, targetType, serverLevel);

        boolean canVoid = voidModeEnabled || ModConfig.GLOBAL_VOID_MODE.get();

        if (target != null && !target.isRemoved()) {
            // 正常传输：向目标箱子推送物品
            transferItemsTo(target, canVoid);
        } else if (canVoid) {
            // 虚空模式：目标不可用时删除物品
            voidFilteredItems();
        }
        // else: 目标不在线且未开虚空模式，物品留在箱子内等待
    }

    /**
     * 向目标箱子传输物品
     * @param target 目标箱子
     * @param canVoid 当目标满时是否虚空多余物品
     */
    private void transferItemsTo(BaseChestBlockEntity target, boolean canVoid) {
        int rateLimit = transferRateLimit > 0 ? transferRateLimit : ModConfig.DEFAULT_TRANSFER_RATE.get();
        int transferred = 0;

        // 快照物品列表避免并发修改
        List<Map.Entry<Item, Integer>> entries = new ArrayList<>(itemStorage.entrySet());

        for (Map.Entry<Item, Integer> entry : entries) {
            Item item = entry.getKey();
            int count = entry.getValue();
            if (count <= 0) continue;

            // 筛选器检查
            if (!allowedItems.isEmpty() && !allowedItems.contains(item)) continue;

            // 速率限制检查
            int toTransfer = count;
            if (rateLimit > 0) {
                int remaining = rateLimit - transferred;
                if (remaining <= 0) break;
                toTransfer = Math.min(toTransfer, remaining);
            }

            // 目标容量检查
            toTransfer = Math.min(toTransfer, target.getRemainingCapacity());

            if (toTransfer > 0) {
                removeItem(item, toTransfer);
                target.addItem(item, toTransfer);
                transferred += toTransfer;

                if (target.getRemainingCapacity() <= 0) {
                    // 目标已满
                    if (canVoid) voidRemainingFilteredItems(entries, entries.indexOf(entry) + 1);
                    break;
                }

                if (rateLimit > 0 && transferred >= rateLimit) break;
            }
        }
    }

    /**
     * 虚空所有通过筛选的物品（整箱清空）
     */
    private void voidFilteredItems() {
        boolean changed = false;
        Iterator<Map.Entry<Item, Integer>> it = itemStorage.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Item, Integer> entry = it.next();
            if (!allowedItems.isEmpty() && !allowedItems.contains(entry.getKey())) continue;
            it.remove();
            changed = true;
        }
        if (changed) setChanged();
    }

    /**
     * 虚空列表中剩余通过筛选的物品（从指定索引开始）
     */
    private void voidRemainingFilteredItems(List<Map.Entry<Item, Integer>> entries, int startIndex) {
        boolean changed = false;
        for (int i = startIndex; i < entries.size(); i++) {
            Map.Entry<Item, Integer> entry = entries.get(i);
            if (!allowedItems.isEmpty() && !allowedItems.contains(entry.getKey())) continue;
            if (itemStorage.remove(entry.getKey()) != null) {
                changed = true;
            }
        }
        if (changed) setChanged();
    }

    // ===== NBT 序列化 =====

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider reg) {
        super.saveAdditional(tag, reg);

        // 保存容量系统
        ListTag itemList = new ListTag();
        for (Map.Entry<Item, Integer> entry : itemStorage.entrySet()) {
            CompoundTag itemTag = new CompoundTag();
            // 获取 Item 的注册名
            net.minecraft.resources.ResourceLocation itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(entry.getKey());
            itemTag.putString("id", itemId.toString());
            itemTag.putInt("count", entry.getValue());
            itemList.add(itemTag);
        }
        tag.put("Items", itemList);

        // 保存绑定信息
        tag.putString("ChestId", chestId);
        tag.putString("BoundTargetId", boundTargetId);
        if (ownerUUID != null) tag.putUUID("Owner", ownerUUID);
        tag.putBoolean("TransferEnabled", transferEnabled);
        tag.putBoolean("VoidModeEnabled", voidModeEnabled);
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
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider reg) {
        super.loadAdditional(tag, reg);

        // 加载容量系统
        itemStorage.clear();
        ListTag itemList = tag.getList("Items", Tag.TAG_COMPOUND);
        for (Tag t : itemList) {
            CompoundTag itemTag = (CompoundTag) t;
            String itemId = itemTag.getString("id");
            int count = itemTag.getInt("count");
            // 使用 BuiltInRegistries 解析 Item
            net.minecraft.resources.ResourceLocation loc = net.minecraft.resources.ResourceLocation.tryParse(itemId);
            if (loc != null) {
                Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(loc);
                if (item != null && item != net.minecraft.world.item.Items.AIR) {
                    itemStorage.put(item, count);
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
