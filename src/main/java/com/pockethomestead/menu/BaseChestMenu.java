package com.pockethomestead.menu;

import com.pockethomestead.blockentity.BaseChestBlockEntity;
import com.pockethomestead.network.ChestConfigPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 箱子Menu基类 — 仅管理玩家背包槽位。
 *
 * 箱子区不使用真正的槽位，由 BaseChestScreen 纯渲染并通过网络包交互。
 * 服务端 itemStorage 是唯一权威，客户端通过 ChestSyncPacket 接收物品快照。
 */
public abstract class BaseChestMenu extends AbstractContainerMenu {
    public final BaseChestBlockEntity blockEntity;
    protected final Inventory playerInventory;

    // 槽位网格参数（箱子区为纯渲染，这些常量供布局/Screen使用）
    public static final int SLOT_SIZE = 18;
    public static final int CHEST_COLS = 9;
    public static final int CHEST_VISIBLE_ROWS = 3;
    public static final int CHEST_SLOTS = CHEST_COLS * CHEST_VISIBLE_ROWS; // 27（仅用于显示行数）

    // 面板设计参数
    public static final int PANEL_PADDING = 8;
    public static final int HEADER_HEIGHT = 20;
    public static final int SECTION_GAP = 8;
    public static final int SCROLLBAR_WIDTH = 6;
    public static final int SCROLLBAR_GAP = 2;
    public static final int BOX_PAD = 4; // 存货区内边距
    public static final int FLUID_SECTION_HEIGHT = 0; // 流体已独立为单页，物品页不再预留流体区
    public static final int UPGRADE_SECTION_HEIGHT = 24;

    public static int calculatePanelWidth() {
        return CHEST_COLS * SLOT_SIZE + 2 * PANEL_PADDING + SCROLLBAR_WIDTH + SCROLLBAR_GAP;
    }

    public static int calculateSlotStartX(int panelWidth) {
        int gridWidth = CHEST_COLS * SLOT_SIZE;
        int remaining = panelWidth - gridWidth - SCROLLBAR_WIDTH - SCROLLBAR_GAP;
        return PANEL_PADDING + remaining / 2;
    }

    public static int calculateChestSlotStartY() {
        return HEADER_HEIGHT + SECTION_GAP;
    }

    public static int calculatePlayerLabelY() {
        return calculateChestSlotStartY() + CHEST_VISIBLE_ROWS * SLOT_SIZE + SECTION_GAP + UPGRADE_SECTION_HEIGHT + SECTION_GAP;
    }

    public static boolean isCreateLoaded() {
        return net.neoforged.fml.ModList.get().isLoaded("create");
    }

    public static int calculatePlayerInvStartY() {
        return calculatePlayerLabelY() + 12;
    }

    public static int calculateHotbarStartY() {
        return calculatePlayerInvStartY() + 3 * SLOT_SIZE + SECTION_GAP;
    }

    public static int calculatePanelHeight() {
        // 第0页（内容页）高度：头部 + 存货区 + 玩家背包 + 快捷栏 + 底边距
        int itemPageHeight = calculateHotbarStartY() + SLOT_SIZE + PANEL_PADDING;
        int facePageHeight = HEADER_HEIGHT + 10 + 126 + 8 + 78 + PANEL_PADDING;
        int settingsPageHeight = HEADER_HEIGHT + 8 + 36 + 6 + 48 + 6 + 38 + 6 + 60 + PANEL_PADDING;
        return Math.max(itemPageHeight, Math.max(facePageHeight, settingsPageHeight));
    }

    protected BaseChestMenu(MenuType<?> menuType, int containerId, Inventory playerInventory, BaseChestBlockEntity blockEntity) {
        super(menuType, containerId);
        this.blockEntity = blockEntity;
        this.playerInventory = playerInventory;

        int panelW = calculatePanelWidth();
        int slotX = calculateSlotStartX(panelW);
        int playerY = calculatePlayerInvStartY();
        int hotbarY = calculateHotbarStartY();

        // 仅玩家背包槽位（索引 0..26 主背包，27..35 快捷栏）
        for (int row = 0; row < 3; row++)
            for (int col = 0; col < 9; col++)
                this.addSlot(new Slot(playerInventory, col + row * 9 + 9, slotX + col * SLOT_SIZE, playerY + row * SLOT_SIZE));

        for (int col = 0; col < 9; col++)
            this.addSlot(new Slot(playerInventory, col, slotX + col * SLOT_SIZE, hotbarY));
    }

    /**
     * Shift+点击玩家背包槽位 → 物品转入箱子（仅服务端执行）
     */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (blockEntity.getLevel() != null && blockEntity.getLevel().isClientSide) return ItemStack.EMPTY;
        if (index < 0 || index >= slots.size()) return ItemStack.EMPTY;

        Slot slot = slots.get(index);
        if (slot == null || !slot.hasItem()) return ItemStack.EMPTY;

        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();

        int added = blockEntity.addItem(stack, stack.getCount());
        if (added <= 0) return ItemStack.EMPTY;

        stack.shrink(added);
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY); else slot.setChanged();

        // 同步箱子快照到客户端
        if (player instanceof ServerPlayer sp) {
            ChestConfigPacket.sendSyncToClient(sp, blockEntity);
        }

        slot.onTake(player, stack);
        return ItemStack.EMPTY; // 返回EMPTY阻止vanilla循环调用
    }

    private int countdownSyncTick = 0;

    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        if (!(playerInventory.player instanceof ServerPlayer sp)) return;

        // 物品快照变更时主动推送（服务端）
        if (blockEntity.storageDirty) {
            blockEntity.storageDirty = false;
            ChestConfigPacket.sendSyncToClient(sp, blockEntity);
            countdownSyncTick = 0;
            return;
        }

        // 每秒（20tick）推送一次，让客户端倒计时/电力/状态保持新鲜。
        if (blockEntity.isTransferEnabled()) {
            if (++countdownSyncTick >= 20) {
                countdownSyncTick = 0;
                ChestConfigPacket.sendSyncToClient(sp, blockEntity);
            }
        }
    }

    @Override
    public void addSlotListener(net.minecraft.world.inventory.ContainerListener listener) {
        super.addSlotListener(listener);
        // 玩家首次打开时立即同步
        if (playerInventory.player instanceof ServerPlayer sp) {
            ChestConfigPacket.sendSyncToClient(sp, blockEntity);
        }
    }

    @Override
    public boolean stillValid(Player p) {
        return blockEntity.stillValid(p);
    }

    public BaseChestBlockEntity getBlockEntity() {
        return blockEntity;
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
