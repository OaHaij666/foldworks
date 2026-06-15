package com.pockethomestead.menu;

import com.pockethomestead.blockentity.BaseChestBlockEntity;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 箱子Menu基类 — 容量系统 + 固定可视槽位 + 滚动偏移。
 *
 * 核心设计：
 * - 1种物品 = 1个格子（不按64拆分）
 * - 格子显示数量用 k/M 格式
 * - 固定可视槽位（3行×9列=27格），通过 scrollRow 偏移切换可见范围
 * - 槽位坐标动态计算，居中于面板
 */
public abstract class BaseChestMenu extends AbstractContainerMenu {
    public final BaseChestBlockEntity blockEntity;
    protected final Inventory playerInventory;
    public final VirtualChestContainer chestContainer;

    // 槽位网格参数
    public static final int SLOT_SIZE = 18;       // 每格 18×18
    public static final int SLOT_GAP = 0;         // 无间隙
    public static final int CHEST_COLS = 9;       // 9列
    public static final int CHEST_VISIBLE_ROWS = 3; // 3行可见
    public static final int CHEST_SLOTS = CHEST_COLS * CHEST_VISIBLE_ROWS; // 27

    // 面板设计参数（基准值，实际会根据 imageWidth 动态计算）
    // 面板宽度基准 = 9列 + 边距
    public static final int PANEL_PADDING = 8;    // 左右内边距
    public static final int HEADER_HEIGHT = 20;   // 标题栏高度
    public static final int SECTION_GAP = 8;      // 区域间距
    public static final int CONFIG_HEIGHT = 50;   // 配置栏高度

    /**
     * 计算面板宽度（基于槽位网格）
     */
    public static int calculatePanelWidth() {
        return CHEST_COLS * SLOT_SIZE + 2 * PANEL_PADDING + SCROLLBAR_WIDTH + SCROLLBAR_GAP;
    }

    public static final int SCROLLBAR_WIDTH = 8;
    public static final int SCROLLBAR_GAP = 4;

    /**
     * 计算槽位起始X坐标（居中）
     */
    public static int calculateSlotStartX(int panelWidth) {
        int gridWidth = CHEST_COLS * SLOT_SIZE;
        int remaining = panelWidth - gridWidth - SCROLLBAR_WIDTH - SCROLLBAR_GAP;
        return PANEL_PADDING + remaining / 2;
    }

    /**
     * 计算箱子槽位起始Y坐标
     */
    public static int calculateChestSlotStartY() {
        return HEADER_HEIGHT + SECTION_GAP;
    }

    /**
     * 计算玩家背包标签Y坐标
     */
    public static int calculatePlayerLabelY() {
        return calculateChestSlotStartY() + CHEST_VISIBLE_ROWS * SLOT_SIZE + SECTION_GAP;
    }

    /**
     * 计算玩家背包槽位起始Y坐标
     */
    public static int calculatePlayerInvStartY() {
        return calculatePlayerLabelY() + 12;
    }

    /**
     * 计算快捷栏起始Y坐标
     */
    public static int calculateHotbarStartY() {
        return calculatePlayerInvStartY() + 3 * SLOT_SIZE + SECTION_GAP;
    }

    /**
     * 计算配置栏Y坐标
     */
    public static int calculateConfigY() {
        return calculateHotbarStartY() + SLOT_SIZE + SECTION_GAP;
    }

    /**
     * 计算面板总高度
     */
    public static int calculatePanelHeight() {
        return calculateConfigY() + CONFIG_HEIGHT + PANEL_PADDING;
    }

    protected BaseChestMenu(MenuType<?> menuType, int containerId, Inventory playerInventory, BaseChestBlockEntity blockEntity) {
        super(menuType, containerId);
        this.blockEntity = blockEntity;
        this.playerInventory = playerInventory;
        this.chestContainer = new VirtualChestContainer(blockEntity);

        int panelW = calculatePanelWidth();
        int slotX = calculateSlotStartX(panelW);
        int chestY = calculateChestSlotStartY();
        int playerY = calculatePlayerInvStartY();
        int hotbarY = calculateHotbarStartY();

        // 箱子槽位 — 使用自定义 Slot 拦截放入操作
        for (int i = 0; i < CHEST_SLOTS; i++) {
            int row = i / CHEST_COLS;
            int col = i % CHEST_COLS;
            this.addSlot(new ChestSlot(chestContainer, i, slotX + col * SLOT_SIZE, chestY + row * SLOT_SIZE));
        }

        // 玩家背包 3×9
        for (int row = 0; row < 3; row++)
            for (int col = 0; col < 9; col++)
                this.addSlot(new Slot(playerInventory, col + row * 9 + 9, slotX + col * SLOT_SIZE, playerY + row * SLOT_SIZE));

        // 快捷栏
        for (int col = 0; col < 9; col++)
            this.addSlot(new Slot(playerInventory, col, slotX + col * SLOT_SIZE, hotbarY));
    }

    // ===== VirtualChestContainer =====

    /**
     * 自定义槽位：拦截放入操作，确保物品正确添加到 BlockEntity
     */
    public static class ChestSlot extends Slot {
        private final VirtualChestContainer container;

        public ChestSlot(VirtualChestContainer container, int slot, int x, int y) {
            super(container, slot, x, y);
            this.container = container;
        }

        @Override
        public void setByPlayer(ItemStack newStack) {
            // 只在服务端处理
            if (container.suppressSync || container.blockEntity.getLevel() == null || container.blockEntity.getLevel().isClientSide) {
                super.setByPlayer(newStack);
                return;
            }

            // 关键：从 BlockEntity 读取真实的旧值（不依赖槽位显示）
            Item newItem = newStack.isEmpty() ? null : newStack.getItem();
            int oldCount = (newItem != null) ? container.blockEntity.getItemCount(newItem) : 0;

            // 更新槽位显示
            super.setByPlayer(newStack);

            // 计算增量并同步到 BlockEntity
            int newCount = newStack.getCount();
            int delta = newCount - oldCount;

            System.out.println("════════════════════════════════════════");
            System.out.println("【setByPlayer】Slot=" + this.getContainerSlot());
            System.out.println("  物品: " + (newItem == null ? "空" : newItem.toString()));
            System.out.println("  BlockEntity旧数量: " + oldCount);
            System.out.println("  槽位新数量: " + newCount);
            System.out.println("  增量: " + delta);

            if (newItem != null && delta != 0) {
                if (delta > 0) {
                    int added = container.blockEntity.addItem(newItem, delta);
                    System.out.println("  → 实际添加: " + added);
                } else {
                    int removed = container.blockEntity.removeItem(newItem, -delta);
                    System.out.println("  → 实际移除: " + removed);
                }
            }

            int used = container.blockEntity.getUsedCapacity();
            int max = com.pockethomestead.config.ModConfig.MAX_CHEST_CAPACITY.get();
            System.out.println("  → 当前容量: " + used + " / " + max);
            System.out.println("════════════════════════════════════════");

            container.blockEntity.setChanged();
            container.blockEntity.storageDirty = true;

            container.suppressSync = true;
            container.refill(container.currentScroll());
            container.suppressSync = false;
        }

        @Override
        public ItemStack remove(int amount) {
            // 取出物品时拦截
            if (container.suppressSync) {
                return super.remove(amount);
            }

            // 只在服务端处理
            if (container.blockEntity.getLevel() == null || container.blockEntity.getLevel().isClientSide) {
                return super.remove(amount);
            }

            ItemStack current = this.getItem();
            if (current.isEmpty()) return ItemStack.EMPTY;

            System.out.println("════════════════════════════════════════");
            System.out.println("【ChestSlot.remove】Slot=" + this.getContainerSlot());
            System.out.println("  当前物品: " + current.getItem().toString() + " × " + current.getCount());
            System.out.println("  请求取出: " + amount);

            // 计算实际取出数量
            int actualAmount = Math.min(amount, current.getCount());
            ItemStack result = current.copy();
            result.setCount(actualAmount);

            System.out.println("  → 实际取出: " + actualAmount);

            // 从 BlockEntity 移除
            int removed = container.blockEntity.removeItem(current.getItem(), actualAmount);
            System.out.println("  → BlockEntity 实际移除: " + removed);

            container.blockEntity.setChanged();
            container.blockEntity.storageDirty = true;

            // 刷新显示
            container.suppressSync = true;
            container.refill(container.currentScroll());
            container.suppressSync = false;

            int used = container.blockEntity.getUsedCapacity();
            int max = com.pockethomestead.config.ModConfig.MAX_CHEST_CAPACITY.get();
            System.out.println("  → 当前容量: " + used + " / " + max);
            System.out.println("════════════════════════════════════════");

            return result;
        }
    }

    // ===== VirtualChestContainer =====

    /**
     * 虚拟容器：桥接 Map<Item,Integer> ↔ 27个固定槽位。
     * 1种物品=1格，数量存实际值（可能>64）。
     */
    public static class VirtualChestContainer extends SimpleContainer {
        private final BaseChestBlockEntity blockEntity;
        private boolean suppressSync;

        public VirtualChestContainer(BaseChestBlockEntity be) {
            super(CHEST_SLOTS);
            this.blockEntity = be;
            refill(0);
        }

        /** 处理玩家放入物品：oldStack → newStack */
        public void syncToBlockEntity(int slot, ItemStack oldStack, ItemStack newStack) {
            // 注意：newStack 已经是槽位中的新值
            // oldStack 是之前的值

            Item oldItem = oldStack.isEmpty() ? null : oldStack.getItem();
            Item newItem = newStack.isEmpty() ? null : newStack.getItem();

            System.out.println("════════════════════════════════════════");
            System.out.println("【syncToBlockEntity】Slot=" + slot);
            System.out.println("  旧物品: " + (oldItem == null ? "空" : oldItem.toString()) + " × " + oldStack.getCount());
            System.out.println("  新物品: " + (newItem == null ? "空" : newItem.toString()) + " × " + newStack.getCount());

            // 情况1：放入新物品到空槽位
            if (oldItem == null && newItem != null) {
                System.out.println("  → 情况1: 放入新物品");
                int added = blockEntity.addItem(newItem, newStack.getCount());
                System.out.println("  → 实际添加: " + added);
            }
            // 情况2：从槽位取走所有物品
            else if (oldItem != null && newItem == null) {
                System.out.println("  → 情况2: 清空槽位（由ChestSlot.remove处理，跳过）");
                return;
            }
            // 情况3：替换物品（拖拽替换）
            else if (oldItem != null && newItem != null && oldItem != newItem) {
                System.out.println("  → 情况3: 替换物品");
                int removed = blockEntity.removeItem(oldItem, oldStack.getCount());
                int added = blockEntity.addItem(newItem, newStack.getCount());
                System.out.println("  → 移除: " + removed + ", 添加: " + added);
            }
            // 情况4：同种物品数量变化（堆叠）
            else if (oldItem != null && newItem != null && oldItem == newItem) {
                int delta = newStack.getCount() - oldStack.getCount();
                System.out.println("  → 情况4: 同种物品堆叠，变化量: " + delta);
                if (delta > 0) {
                    int added = blockEntity.addItem(newItem, delta);
                    System.out.println("  → 实际添加: " + added);
                } else if (delta < 0) {
                    int removed = blockEntity.removeItem(newItem, -delta);
                    System.out.println("  → 实际移除: " + removed);
                }
            }

            int used = blockEntity.getUsedCapacity();
            int max = com.pockethomestead.config.ModConfig.MAX_CHEST_CAPACITY.get();
            System.out.println("  → 当前容量: " + used + " / " + max);
            System.out.println("════════════════════════════════════════");

            blockEntity.setChanged();
            blockEntity.storageDirty = true;

            suppressSync = true;
            refill(currentScroll());
            suppressSync = false;
        }

        /** 从 Map 填充槽位，1种物品=1格 */
        public void refill(int scrollRow) {
            if (suppressSync) return;
            Map<Item, Integer> items = blockEntity.getAllItems();
            List<Map.Entry<Item, Integer>> entries = new ArrayList<>(items.entrySet());

            int startIdx = scrollRow * CHEST_COLS;
            int slot = 0;

            for (int i = 0; i < entries.size(); i++) {
                if (i < startIdx) continue;
                if (slot >= CHEST_SLOTS) break;
                Map.Entry<Item, Integer> entry = entries.get(i);
                // 注意：ItemStack 构造函数会限制 count 到 maxStackSize
                // 但我们通过 setCount 强制设置真实数量
                ItemStack stack = new ItemStack(entry.getKey(), 1);
                stack.setCount(entry.getValue()); // 强制设置真实数量（绕过 maxStackSize 限制）
                super.setItem(slot++, stack);
            }
            for (int i = slot; i < CHEST_SLOTS; i++) super.setItem(i, ItemStack.EMPTY);
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
            // setItem 只用于刷新显示，不修改 BlockEntity
            // 真正的添加/移除通过 removeItem 处理
            suppressSync = true;
            super.setItem(slot, stack);
            suppressSync = false;
        }

        @Override
        public ItemStack removeItem(int slot, int amount) {
            ItemStack s = getItem(slot);
            if (s.isEmpty()) return ItemStack.EMPTY;
            Item item = s.getItem();
            int have = blockEntity.getItemCount(item);

            // 计算实际取出数量
            int take = Math.min(amount, have);
            if (take <= 0) return ItemStack.EMPTY;

            // 从 BlockEntity 移除
            int actualRemoved = blockEntity.removeItem(item, take);

            // 刷新显示
            suppressSync = true;
            refill(currentScroll());
            suppressSync = false;

            blockEntity.setChanged();
            blockEntity.storageDirty = true;

            // 返回取出的物品
            ItemStack result = new ItemStack(item, actualRemoved);
            return result;
        }

        @Override
        public ItemStack removeItemNoUpdate(int slot) {
            ItemStack s = getItem(slot);
            if (s.isEmpty()) return ItemStack.EMPTY;
            Item item = s.getItem();
            int cnt = blockEntity.getItemCount(item);

            if (cnt > 0) {
                blockEntity.removeItem(item, cnt);
                blockEntity.setChanged();
                blockEntity.storageDirty = true;
            }

            ItemStack result = new ItemStack(item, cnt);

            suppressSync = true;
            super.setItem(slot, ItemStack.EMPTY);
            suppressSync = false;

            return result;
        }

        private int currentScroll() {
            return blockEntity.viewScrollRow;
        }

        @Override public boolean stillValid(Player p) { return blockEntity.stillValid(p); }
        @Override public boolean canPlaceItem(int slot, ItemStack stack) { return true; }

        /** 获取指定槽位对应物品的实际数量 */
        public int getRealCount(int slot) {
            if (slot < 0 || slot >= getContainerSize()) return 0;
            ItemStack s = getItem(slot);
            if (s.isEmpty()) return 0;
            // 直接返回 ItemStack 的 count（我们通过 setCount 强制设置的真实值）
            return s.getCount();
        }
    }

    // ===== Shift+Click =====

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem()) return result;
        ItemStack s = slot.getItem();
        result = s.copy();
        int total = slots.size();
        if (index < CHEST_SLOTS) {
            if (!moveItemStackTo(s, CHEST_SLOTS, total, true)) return ItemStack.EMPTY;
        } else {
            if (!moveItemStackTo(s, 0, CHEST_SLOTS, false)) return ItemStack.EMPTY;
        }
        if (s.isEmpty()) slot.set(ItemStack.EMPTY); else slot.setChanged();
        if (s.getCount() == result.getCount()) return ItemStack.EMPTY;
        slot.onTake(player, s);
        return result;
    }

    @Override
    public void broadcastChanges() {
        if (blockEntity.storageDirty) {
            chestContainer.refill(blockEntity.viewScrollRow);
            blockEntity.storageDirty = false;
        }
        super.broadcastChanges();
    }

    @Override public boolean stillValid(Player p) { return blockEntity.stillValid(p); }
    public BaseChestBlockEntity getBlockEntity() { return blockEntity; }
    public VirtualChestContainer getChestContainer() { return chestContainer; }

    /** 获取可滚动的总行数（1种物品=1格） */
    public int totalRows() {
        if (blockEntity == null) return CHEST_VISIBLE_ROWS;
        int types = blockEntity.getAllItems().size();
        return Math.max(CHEST_VISIBLE_ROWS, (types + CHEST_COLS - 1) / CHEST_COLS);
    }

    /** 格式化数量：≥1000 用 k，≥1000000 用 M */
    public static String formatCount(int count) {
        if (count >= 1_000_000) {
            float m = count / 1_000_000f;
            return (m == (int) m ? String.valueOf((int) m) : String.format("%.1f", m)) + "M";
        }
        if (count >= 10_000) {
            return (count / 1_000) + "k";
        }
        if (count >= 1_000) {
            return String.format("%.1f", count / 1_000f) + "k";
        }
        return String.valueOf(count);
    }
}