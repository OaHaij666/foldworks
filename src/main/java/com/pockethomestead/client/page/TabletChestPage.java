package com.pockethomestead.client.page;

import com.pockethomestead.client.ClientTabletChestCache;
import com.pockethomestead.client.ui.ChestGuiTextures;
import com.pockethomestead.client.ui.Page;
import com.pockethomestead.client.ui.Theme;
import com.pockethomestead.client.ui.widget.UiButton;
import com.pockethomestead.menu.BaseChestMenu;
import com.pockethomestead.network.ChestSyncPacket;
import com.pockethomestead.network.TabletChestActionPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class TabletChestPage extends Page {
    private enum SortMode {
        COUNT("数量"),
        NAME("名称"),
        ID("ID");

        private final String label;

        SortMode(String label) {
            this.label = label;
        }
    }

    private static final int SLOT = 18;
    private static final int COLS = 9;
    private static final int PLAYER_ROWS = 4;

    private final List<ChestSyncPacket.ItemEntry> visibleItems = new ArrayList<>();
    private EditBox searchBox;
    private UiButton refreshButton;
    private UiButton sortButton;
    private SortMode sortMode = SortMode.COUNT;
    private int chestScrollRow;
    private long lastVersion = -1;
    private int requestCooldown;
    private ItemStack hoveredStack = ItemStack.EMPTY;
    private int hoveredCount;
    private boolean hoveredFromChest;
    private int hoveredPlayerSlot = -1;

    @Override
    public String id() {
        return "tablet_chest";
    }

    @Override
    public String navTitle() {
        return Component.translatable("pockethomestead.ui.nav.tablet_chest").getString();
    }

    @Override
    public String navIcon() {
        return "▣";
    }

    @Override
    public void onEnter() {
        requestSync();
        if (searchBox == null) buildWidgets();
    }

    @Override
    public void onExit() {
        if (searchBox != null) searchBox.setFocused(false);
        if (mc.player != null) PacketDistributor.sendToServer(new TabletChestActionPacket(TabletChestActionPacket.RETURN_CARRIED));
    }

    @Override
    public void tick() {
        if (--requestCooldown <= 0) {
            requestCooldown = 40;
            requestSync();
        }
    }

    private void buildWidgets() {
        searchBox = new EditBox(font, 0, 0, 80, 14, Component.literal("搜索"));
        searchBox.setMaxLength(64);
        searchBox.setBordered(false);
        searchBox.setTextColor(Theme.TEXT);
        searchBox.setTextColorUneditable(Theme.TEXT_FAINT);
        refreshButton = new UiButton("刷新", UiButton.Variant.SECONDARY).onClick(this::requestSync);
        sortButton = new UiButton("排序", UiButton.Variant.SECONDARY).onClick(this::cycleSort);
    }

    private void requestSync() {
        if (mc.player != null) PacketDistributor.sendToServer(new TabletChestActionPacket(TabletChestActionPacket.REQUEST));
    }

    private void cycleSort() {
        SortMode[] modes = SortMode.values();
        sortMode = modes[(sortMode.ordinal() + 1) % modes.length];
        rebuildVisibleItems();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (searchBox == null) buildWidgets();
        hoveredStack = ItemStack.EMPTY;
        hoveredCount = 0;
        hoveredFromChest = false;
        hoveredPlayerSlot = -1;

        if (lastVersion != ClientTabletChestCache.version()) {
            lastVersion = ClientTabletChestCache.version();
            rebuildVisibleItems();
        }

        int pad = Theme.PAD;
        int top = y + pad;
        int toolbarH = 25;
        int footerH = PLAYER_ROWS * SLOT + 20;
        int chestX = x + pad;
        int chestY = top + toolbarH + 8;
        int chestW = Math.min(COLS * SLOT + 10, w - pad * 2);
        int chestH = Math.max(SLOT * 2 + 10, h - pad * 2 - toolbarH - footerH - 10);
        int playerY = y + h - pad - PLAYER_ROWS * SLOT - 10;

        renderToolbar(g, mouseX, mouseY, top, toolbarH);
        if (!ClientTabletChestCache.bound() || !ClientTabletChestCache.available()) {
            renderEmptyState(g, chestX, chestY, w - pad * 2, h - (chestY - y) - pad);
            renderHoverTooltip(g, mouseX, mouseY);
            return;
        }

        renderChestArea(g, mouseX, mouseY, chestX, chestY, chestW, chestH);
        renderPlayerInventory(g, mouseX, mouseY, chestX, playerY, chestW);
        renderHoverTooltip(g, mouseX, mouseY);
        renderCarriedStack(g, mouseX, mouseY);
    }

    private void renderToolbar(GuiGraphics g, int mouseX, int mouseY, int top, int toolbarH) {
        int pad = Theme.PAD;
        int left = x + pad;
        int right = x + w - pad;
        int searchW = Math.max(70, Math.min(130, w / 3));
        int sortW = 48;
        int refreshW = 38;

        String title = ClientTabletChestCache.available()
                ? "绑定: " + ClientTabletChestCache.chestId()
                : "玉盘仓库";
        Theme.text(g, font, Theme.ellipsize(font, title, right - left - searchW - sortW - refreshW - 22), left, top + 7, Theme.TEXT);

        int refreshX = right - refreshW;
        int sortX = refreshX - 4 - sortW;
        int searchX = sortX - 4 - searchW;
        Theme.panel(g, searchX, top + 3, searchW, 18, Theme.RADIUS, Theme.SURFACE_SUNK, searchBox.isFocused() ? Theme.PRIMARY : Theme.BORDER);
        searchBox.setX(searchX + 6);
        searchBox.setY(top + 8);
        searchBox.setWidth(searchW - 12);
        searchBox.render(g, mouseX, mouseY, 0);

        sortButton.label(sortMode.label).bounds(sortX, top + 2, sortW, 20).render(g, mouseX, mouseY, 0);
        refreshButton.bounds(refreshX, top + 2, refreshW, 20).render(g, mouseX, mouseY, 0);

        Theme.hLine(g, x, top + toolbarH, w, Theme.DIVIDER);
    }

    private void renderEmptyState(GuiGraphics g, int ex, int ey, int ew, int eh) {
        Theme.panel(g, ex, ey, ew, eh, Theme.RADIUS, Theme.SURFACE_ALT, Theme.BORDER);
        String title = ClientTabletChestCache.bound() ? ClientTabletChestCache.message() : "还没有绑定传输箱";
        String body = ClientTabletChestCache.bound() ? "箱子需要加载，并且你需要拥有使用权限" : "手持尘歌玉盘，潜行右键传输箱绑定";
        Theme.textCentered(g, font, "▣", ex + ew / 2, ey + eh / 2 - 27, Theme.BORDER_STRONG);
        Theme.textCentered(g, font, title, ex + ew / 2, ey + eh / 2 - 8, Theme.TEXT);
        Theme.textCentered(g, font, body, ex + ew / 2, ey + eh / 2 + 8, Theme.TEXT_MUTED);
    }

    private void renderChestArea(GuiGraphics g, int mouseX, int mouseY, int ax, int ay, int aw, int ah) {
        Theme.panel(g, ax, ay, aw, ah, Theme.RADIUS, Theme.SURFACE_ALT, Theme.BORDER);
        int used = ClientTabletChestCache.usedCapacity();
        int max = ClientTabletChestCache.maxCapacity();
        int remaining = Math.max(0, max - used);
        String cap = "剩余 " + BaseChestMenu.formatCount(remaining) + " / " + BaseChestMenu.formatCount(max);
        Theme.text(g, font, "箱内物品", ax + 7, ay + 6, Theme.TEXT_MUTED);
        Theme.textRight(g, font, cap, ax + aw - 8, ay + 6, Theme.TEXT_MUTED);

        int gridX = ax + (aw - COLS * SLOT) / 2 + 1;
        int gridY = ay + 20;
        int rows = Math.max(1, (ah - 26) / SLOT);
        int totalRows = Math.max(rows, (visibleItems.size() + COLS - 1) / COLS);
        chestScrollRow = clamp(chestScrollRow, 0, Math.max(0, totalRows - rows));

        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < COLS; col++) {
                int dataIdx = (chestScrollRow + row) * COLS + col;
                int sx = gridX + col * SLOT;
                int sy = gridY + row * SLOT;
                boolean hover = Theme.inside(mouseX, mouseY, sx, sy, 16, 16);
                ChestGuiTextures.slot(g, sx - 1, sy - 1, hover);
                if (dataIdx >= 0 && dataIdx < visibleItems.size()) {
                    ChestSyncPacket.ItemEntry entry = visibleItems.get(dataIdx);
                    ItemStack stack = entry.stack();
                    g.renderItem(stack, sx, sy);
                    renderCountText(g, BaseChestMenu.formatCount(entry.count()), sx, sy);
                    if (hover) {
                        hoveredStack = stack.copyWithCount(1);
                        hoveredCount = entry.count();
                        hoveredFromChest = true;
                    }
                }
            }
        }

        if (totalRows > rows) {
            int trackX = ax + aw - 7;
            int trackY = gridY;
            int trackH = rows * SLOT;
            int thumbH = Math.max(14, trackH * rows / totalRows);
            int thumbY = trackY + chestScrollRow * Math.max(1, trackH - thumbH) / Math.max(1, totalRows - rows);
            ChestGuiTextures.scrollbar(g, trackX, trackY, 6, trackH, thumbY, thumbH);
        }
    }

    private void renderPlayerInventory(GuiGraphics g, int mouseX, int mouseY, int ax, int ay, int aw) {
        Theme.panel(g, ax, ay, aw, PLAYER_ROWS * SLOT + 10, Theme.RADIUS, Theme.SURFACE, Theme.BORDER);
        Theme.text(g, font, "背包", ax + 7, ay - 10, Theme.TEXT_MUTED);
        int gridX = ax + (aw - COLS * SLOT) / 2 + 1;
        int gridY = ay + 5;
        Inventory inv = mc.player == null ? null : mc.player.getInventory();
        if (inv == null) return;
        for (int row = 0; row < PLAYER_ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                int slot = row == 3 ? col : 9 + row * COLS + col;
                int sx = gridX + col * SLOT;
                int sy = gridY + row * SLOT;
                ItemStack stack = inv.getItem(slot);
                boolean hover = Theme.inside(mouseX, mouseY, sx, sy, 16, 16);
                ChestGuiTextures.slot(g, sx - 1, sy - 1, hover);
                if (!stack.isEmpty()) {
                    g.renderItem(stack, sx, sy);
                    g.renderItemDecorations(font, stack, sx, sy, null);
                    if (hover) {
                        hoveredStack = stack;
                        hoveredPlayerSlot = slot;
                    }
                }
            }
        }
    }

    private void rebuildVisibleItems() {
        visibleItems.clear();
        String query = searchBox == null ? "" : searchBox.getValue().trim().toLowerCase(Locale.ROOT);
        for (ChestSyncPacket.ItemEntry entry : ClientTabletChestCache.items()) {
            if (query.isEmpty() || matches(entry.stack(), query)) visibleItems.add(entry);
        }
        Comparator<ChestSyncPacket.ItemEntry> comparator = switch (sortMode) {
            case COUNT -> Comparator.<ChestSyncPacket.ItemEntry>comparingInt(ChestSyncPacket.ItemEntry::count).reversed()
                    .thenComparing(entry -> itemName(entry.stack()), String.CASE_INSENSITIVE_ORDER);
            case NAME -> Comparator.comparing(entry -> itemName(entry.stack()), String.CASE_INSENSITIVE_ORDER);
            case ID -> Comparator.comparing(entry -> itemId(entry.stack()), String.CASE_INSENSITIVE_ORDER);
        };
        visibleItems.sort(comparator);
        int rows = Math.max(1, (h - Theme.PAD * 2 - 25 - PLAYER_ROWS * SLOT - 28) / SLOT);
        chestScrollRow = clamp(chestScrollRow, 0, Math.max(0, (visibleItems.size() + COLS - 1) / COLS - rows));
    }

    private boolean matches(ItemStack stack, String query) {
        return itemName(stack).toLowerCase(Locale.ROOT).contains(query)
                || itemId(stack).toLowerCase(Locale.ROOT).contains(query);
    }

    private String itemName(ItemStack stack) {
        return stack.getHoverName().getString();
    }

    private String itemId(ItemStack stack) {
        var id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id == null ? "" : id.toString();
    }

    private void renderCountText(GuiGraphics g, String text, int slotX, int slotY) {
        int color = 0xFFFFFFFF;
        int shadow = 0xAA182838;
        int tx = Math.max(slotX, slotX + 16 - font.width(text));
        int ty = slotY + 10;
        g.pose().pushPose();
        g.pose().translate(0, 0, 260);
        g.drawString(font, text, tx + 1, ty + 1, shadow, false);
        g.drawString(font, text, tx, ty, color, false);
        g.pose().popPose();
    }

    private void renderHoverTooltip(GuiGraphics g, int mouseX, int mouseY) {
        if (!ClientTabletChestCache.carriedStack().isEmpty()) return;
        if (hoveredStack.isEmpty()) return;
        if (hoveredFromChest) {
            List<Component> lines = new ArrayList<>();
            lines.add(hoveredStack.getHoverName());
            lines.add(Component.literal("数量: " + hoveredCount).withStyle(ChatFormatting.GRAY));
            lines.add(Component.literal("左键取一组 · 右键取一个").withStyle(ChatFormatting.DARK_GRAY));
            g.renderComponentTooltip(font, lines, mouseX, mouseY);
        } else {
            g.renderTooltip(font, hoveredStack, mouseX, mouseY);
        }
    }

    private void renderCarriedStack(GuiGraphics g, int mouseX, int mouseY) {
        ItemStack carried = ClientTabletChestCache.carriedStack();
        if (carried.isEmpty()) return;
        g.pose().pushPose();
        g.pose().translate(0, 0, 400);
        g.renderItem(carried, mouseX - 8, mouseY - 8);
        g.renderItemDecorations(font, carried, mouseX - 8, mouseY - 8, null);
        g.pose().popPose();
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (searchBox != null && Theme.inside(mx, my, searchBox.getX() - 6, searchBox.getY() - 5, searchBox.getWidth() + 12, 18)) {
            searchBox.setFocused(true);
            searchBox.mouseClicked(mx, my, button);
            return true;
        }
        if (searchBox != null) searchBox.setFocused(false);
        if (sortButton != null && sortButton.mouseClicked(mx, my, button)) return true;
        if (refreshButton != null && refreshButton.mouseClicked(mx, my, button)) return true;
        if (!ClientTabletChestCache.available()) return false;

        int chestIdx = chestIndexAt(mx, my);
        if (chestIdx >= 0 && chestIdx < visibleItems.size()) {
            ItemStack stack = visibleItems.get(chestIdx).stack().copyWithCount(1);
            int action = Screen.hasShiftDown()
                    ? TabletChestActionPacket.QUICK_MOVE_CHEST
                    : button == 1 ? TabletChestActionPacket.CLICK_CHEST_RIGHT : TabletChestActionPacket.CLICK_CHEST_LEFT;
            PacketDistributor.sendToServer(new TabletChestActionPacket(action, stack));
            return true;
        }
        if (isInChestArea(mx, my) && !ClientTabletChestCache.carriedStack().isEmpty()) {
            PacketDistributor.sendToServer(new TabletChestActionPacket(button == 1
                    ? TabletChestActionPacket.CLICK_CHEST_RIGHT
                    : TabletChestActionPacket.CLICK_CHEST_LEFT, ItemStack.EMPTY));
            return true;
        }

        int playerSlot = playerSlotAt(mx, my);
        if (playerSlot >= 0) {
            int action = Screen.hasShiftDown()
                    ? TabletChestActionPacket.QUICK_MOVE_PLAYER
                    : button == 1 ? TabletChestActionPacket.CLICK_PLAYER_RIGHT : TabletChestActionPacket.CLICK_PLAYER_LEFT;
            PacketDistributor.sendToServer(new TabletChestActionPacket(action, playerSlot));
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (!ClientTabletChestCache.available() || !isInChestArea(mx, my)) return false;
        int rows = currentChestRows();
        int totalRows = Math.max(rows, (visibleItems.size() + COLS - 1) / COLS);
        if (totalRows <= rows) return true;
        chestScrollRow = clamp(chestScrollRow - (int) Math.signum(sy), 0, totalRows - rows);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (searchBox != null && searchBox.isFocused()) {
            if (keyCode == 256) {
                searchBox.setFocused(false);
                return true;
            }
            boolean handled = searchBox.keyPressed(keyCode, scanCode, modifiers);
            rebuildVisibleItems();
            return handled || searchBox.isFocused();
        }
        return false;
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (searchBox != null && searchBox.isFocused()) {
            boolean handled = searchBox.charTyped(codePoint, modifiers);
            rebuildVisibleItems();
            return handled;
        }
        return false;
    }

    private boolean isInChestArea(double mx, double my) {
        int[] rect = chestRect();
        return Theme.inside(mx, my, rect[0], rect[1], rect[2], rect[3]);
    }

    private int chestIndexAt(double mx, double my) {
        int[] rect = chestRect();
        int gridX = rect[0] + (rect[2] - COLS * SLOT) / 2 + 1;
        int gridY = rect[1] + 20;
        int rows = Math.max(1, (rect[3] - 26) / SLOT);
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < COLS; col++) {
                int sx = gridX + col * SLOT;
                int sy = gridY + row * SLOT;
                if (Theme.inside(mx, my, sx, sy, 16, 16)) return (chestScrollRow + row) * COLS + col;
            }
        }
        return -1;
    }

    private int playerSlotAt(double mx, double my) {
        int[] rect = playerRect();
        int gridX = rect[0] + (rect[2] - COLS * SLOT) / 2 + 1;
        int gridY = rect[1] + 5;
        for (int row = 0; row < PLAYER_ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                int sx = gridX + col * SLOT;
                int sy = gridY + row * SLOT;
                if (Theme.inside(mx, my, sx, sy, 16, 16)) return row == 3 ? col : 9 + row * COLS + col;
            }
        }
        return -1;
    }

    private int currentChestRows() {
        int[] rect = chestRect();
        return Math.max(1, (rect[3] - 26) / SLOT);
    }

    private int[] chestRect() {
        int pad = Theme.PAD;
        int toolbarH = 25;
        int footerH = PLAYER_ROWS * SLOT + 20;
        int chestX = x + pad;
        int chestY = y + pad + toolbarH + 8;
        int chestW = Math.min(COLS * SLOT + 10, w - pad * 2);
        int chestH = Math.max(SLOT * 2 + 10, h - pad * 2 - toolbarH - footerH - 10);
        return new int[] { chestX, chestY, chestW, chestH };
    }

    private int[] playerRect() {
        int[] chest = chestRect();
        int py = y + h - Theme.PAD - PLAYER_ROWS * SLOT - 10;
        return new int[] { chest[0], py, chest[2], PLAYER_ROWS * SLOT + 10 };
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
