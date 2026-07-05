package com.pockethomestead.client.page;

import com.pockethomestead.client.ClientTabletChestCache;
import com.pockethomestead.client.ui.ChestGuiTextures;
import com.pockethomestead.client.ui.Page;
import com.pockethomestead.client.ui.Theme;
import com.pockethomestead.client.ui.widget.UiButton;
import com.pockethomestead.menu.BaseChestMenu;
import com.pockethomestead.network.ChestSyncPacket;
import com.pockethomestead.network.TabletChestActionPacket;
import com.pockethomestead.network.TabletChestSyncPacket;
import com.pockethomestead.api.suite.SuiteToolRegistry;
import com.pockethomestead.suite.VanillaSuiteAdapters;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
    private static final int SUITE_GAP = 42;
    private static final int MIN_SUITE_W = 150;
    private static final int MAX_SUITE_CONTENT_W = 300;

    private final List<ChestSyncPacket.ItemEntry> visibleItems = new ArrayList<>();
    private final List<ItemStack> suiteSearchResults = new ArrayList<>();
    private EditBox searchBox;
    private EditBox suiteSearchBox;
    private EditBox suiteQtyBox;
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
    private String hoveredSuitePanel = "";
    private int stonecutterScrollOffset;
    private int suiteOrderScroll;
    private int suiteSearchScroll;
    private int suiteToolPage;
    private int suiteResourcePage;
    private int suiteScrollDrag;
    private String suiteSearchQuery = "";

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
        if (suiteSearchBox != null) suiteSearchBox.setFocused(false);
        if (suiteQtyBox != null) {
            submitSuiteQuantity();
            suiteQtyBox.setFocused(false);
        }
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
        suiteSearchBox = new EditBox(font, 0, 0, 80, 14, Component.literal("搜索合成目标"));
        suiteSearchBox.setMaxLength(64);
        suiteSearchBox.setBordered(false);
        suiteSearchBox.setTextColor(Theme.TEXT);
        suiteSearchBox.setTextColorUneditable(Theme.TEXT_FAINT);
        suiteQtyBox = new EditBox(font, 0, 0, 28, 14, Component.literal("数量"));
        suiteQtyBox.setMaxLength(4);
        suiteQtyBox.setBordered(false);
        suiteQtyBox.setTextColor(Theme.TEXT);
        suiteQtyBox.setTextColorUneditable(Theme.TEXT_FAINT);
        suiteQtyBox.setFilter(value -> value.isEmpty() || value.chars().allMatch(Character::isDigit));
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
        hoveredSuitePanel = "";

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
        int chestW = storageColumnWidth();
        int chestH = Math.max(SLOT * 2 + 10, h - pad * 2 - toolbarH - footerH - 10);
        int playerY = y + h - pad - PLAYER_ROWS * SLOT - 10;

        renderToolbar(g, mouseX, mouseY, top, toolbarH, chestX, chestW);
        if (!ClientTabletChestCache.bound() || !ClientTabletChestCache.available()) {
            renderEmptyState(g, chestX, chestY, w - pad * 2, h - (chestY - y) - pad);
            renderHoverTooltip(g, mouseX, mouseY);
            return;
        }

        renderChestArea(g, mouseX, mouseY, chestX, chestY, chestW, chestH);
        renderPlayerInventory(g, mouseX, mouseY, chestX, playerY, chestW);
        renderSuiteArea(g, mouseX, mouseY);
        renderHoverTooltip(g, mouseX, mouseY);
        renderCarriedStack(g, mouseX, mouseY);
    }

    private void renderToolbar(GuiGraphics g, int mouseX, int mouseY, int top, int toolbarH, int left, int toolbarW) {
        int effectiveW = Math.max(toolbarW, Math.min(430, x + w - Theme.PAD - left));
        int right = left + effectiveW;
        int sortW = 34;
        int refreshW = 34;
        int searchW = Math.max(72, Math.min(130, effectiveW / 3));

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
                    g.renderItemDecorations(font, stack, sx, sy, null);
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

    private void renderSuiteArea(GuiGraphics g, int mouseX, int mouseY) {
        int[] rect = suiteRect();
        if (rect == null) return;
        if (!ClientTabletChestCache.suiteUpgradeInstalled()) return;
        int sx = rect[0], sy = rect[1], sw = rect[2], sh = rect[3];
        sw = Math.min(sw, MAX_SUITE_CONTENT_W);
        int gap = 12;
        int columnW = Math.max(1, (sw - gap) / 2);
        sw = columnW * 2 + gap;
        int upperH = Math.max(150, Math.min(170, (sh * 2) / 5));
        int lowerY = sy + upperH + gap;
        int lowerH = Math.max(44, sh - upperH - gap);
        int leftW = columnW;
        int rightW = columnW;
        int rightX = sx + leftW + gap;

        int topH = Math.max(78, Math.min(86, upperH - gap - 54));
        int bottomY = sy + topH + gap;
        int bottomH = Math.max(48, upperH - topH - gap);
        renderCraftingPanel(g, mouseX, mouseY, sx, sy, leftW, topH);
        renderFurnacePanel(g, mouseX, mouseY, rightX, sy, rightW, topH);
        renderSmithingPanel(g, mouseX, mouseY, sx, bottomY, leftW, bottomH);
        renderStonecutterPanel(g, mouseX, mouseY, rightX, bottomY, rightW, bottomH);

        renderSuiteOrderArea(g, mouseX, mouseY, sx, lowerY, sw, lowerH);
    }

    private void renderSuiteOrderArea(GuiGraphics g, int mouseX, int mouseY, int px, int py, int pw, int ph) {
        int gap = 8;
        int sideW = 62;
        int orderW = Math.max(120, pw - sideW - gap);
        renderSuiteOrderColumn(g, mouseX, mouseY, px, py, orderW, ph);
        int sideX = px + orderW + gap;
        int sideH = Math.max(44, (ph - gap) / 2);
        renderSuitePoolColumn(g, mouseX, mouseY, sideX, py, sideW, sideH, true);
        renderSuitePoolColumn(g, mouseX, mouseY, sideX, py + sideH + gap, sideW, ph - sideH - gap, false);
        markSuiteHover(mouseX, mouseY, px, py, pw, ph, "自动合成");
    }

    private void renderSuiteOrderColumn(GuiGraphics g, int mouseX, int mouseY, int px, int py, int pw, int ph) {
        Theme.panel(g, px, py, pw, ph, Theme.RADIUS, Theme.SURFACE_ALT, Theme.BORDER);
        Theme.text(g, font, "自动合成", px + 7, py + 6, Theme.TEXT_MUTED);
        int targetX = px + 8;
        int targetY = py + 21;
        boolean targetHover = Theme.inside(mouseX, mouseY, targetX, targetY, 16, 16);
        ChestGuiTextures.slot(g, targetX - 1, targetY - 1, targetHover);
        ItemStack target = ClientTabletChestCache.suiteOrderTarget();
        if (!target.isEmpty()) {
            g.renderItem(target, targetX, targetY);
            if (targetHover) hoveredStack = target;
        }
        int searchX = targetX + 22;
        int createW = 31;
        int createX = px + pw - createW - 7;
        int plusX = createX - 18;
        int qtyW = 28;
        int qtyX = plusX - qtyW - 2;
        int minusX = qtyX - 16;
        int searchW = Math.max(48, minusX - searchX - 5);
        Theme.panel(g, searchX, targetY - 1, searchW, 18, 5, Theme.SURFACE_SUNK,
                suiteSearchBox.isFocused() ? Theme.PRIMARY : Theme.BORDER);
        suiteSearchBox.setX(searchX + 5);
        suiteSearchBox.setY(targetY + 3);
        suiteSearchBox.setWidth(searchW - 10);
        renderSuiteSearchBoxText(g, searchX + 5, targetY + 5, searchW - 10);
        int qty = ClientTabletChestCache.suiteOrderQuantity();
        smallButton(g, minusX, targetY, 14, 16, "-", mouseX, mouseY);
        syncSuiteQuantityBox(qty);
        Theme.panel(g, qtyX, targetY - 1, qtyW, 18, 4, Theme.SURFACE_SUNK,
                suiteQtyBox.isFocused() ? Theme.PRIMARY : Theme.BORDER);
        suiteQtyBox.setX(qtyX + 3);
        suiteQtyBox.setY(targetY + 3);
        suiteQtyBox.setWidth(qtyW - 6);
        renderSuiteQuantityBoxText(g, qtyX, targetY, qtyW);
        smallButton(g, plusX, targetY, 14, 16, "+", mouseX, mouseY);
        int max = ClientTabletChestCache.suiteMaxOrders();
        boolean capped = max > 0 && ClientTabletChestCache.suiteOrders().size() >= max;
        smallButton(g, createX, targetY, createW, 16, "创建", mouseX, mouseY, !capped && !target.isEmpty());

        int listY = suiteOrderListY(py);
        int rowH = 19;
        int rows = Math.max(1, (py + ph - listY - 6) / rowH);
        List<TabletChestSyncPacket.OrderEntry> orders = ClientTabletChestCache.suiteOrders();
        suiteOrderScroll = clamp(suiteOrderScroll, 0, Math.max(0, orders.size() - rows));
        for (int i = 0; i < rows && suiteOrderScroll + i < orders.size(); i++) {
            TabletChestSyncPacket.OrderEntry order = orders.get(suiteOrderScroll + i);
            int y0 = listY + i * rowH;
            int fill = Theme.inside(mouseX, mouseY, px + 5, y0, pw - 10, rowH - 2)
                    ? Theme.uiColor(0x446EA6C8) : Theme.uiColor(0x223F5664);
            g.fill(px + 5, y0, px + pw - 5, y0 + rowH - 2, fill);
            g.renderItem(order.target(), px + 7, y0 + 1);
            int actionStart = order.canClaim() || order.canRecover() || order.canDelete() ? px + pw - 23 : px + pw - 41;
            int stateX = px + Math.min(94, Math.max(64, pw - 116));
            int stateW = Math.max(18, actionStart - stateX - 5);
            int nameW = Math.max(24, stateX - (px + 25) - 5);
            String name = order.target().getHoverName().getString();
            Theme.text(g, font, Theme.ellipsize(font, name, nameW), px + 25, y0 + 2, Theme.TEXT);
            Theme.text(g, font, order.ready() + "/" + order.requested(), px + 25, y0 + 11, Theme.TEXT_MUTED);
            String state = order.state();
            Theme.text(g, font, Theme.ellipsize(font, state, stateW), stateX, y0 + 6, Theme.TEXT_MUTED);
            if (order.canClaim() || order.canRecover() || order.canDelete()) {
                String action = order.canClaim() ? "✓" : order.canRecover() ? "↩" : "×";
                smallButton(g, px + pw - 23, y0 + 1, 16, 15, action, mouseX, mouseY);
            } else {
                smallButton(g, px + pw - 41, y0 + 1, 16, 15, isSuiteOrderPaused(order) ? "▶" : "Ⅱ", mouseX, mouseY);
                smallButton(g, px + pw - 23, y0 + 1, 16, 15, "×", mouseX, mouseY);
            }
            if (Theme.inside(mouseX, mouseY, px + 5, y0, pw - 10, rowH - 2) && !order.reason().isBlank()) {
                hoveredSuitePanel = order.reason();
            }
        }
        drawScrollbar(g, px + pw - 4, listY, py + ph - listY - 6, orders.size(), rows, suiteOrderScroll);
        renderSuiteSearchResults(g, mouseX, mouseY, searchX, targetY + 20, searchW, py + ph - 6);
    }

    private void renderSuiteSearchBoxText(GuiGraphics g, int x, int y, int w) {
        String value = suiteSearchBox.getValue();
        if (value.isBlank()) {
            Theme.text(g, font, "名称", x, y, Theme.TEXT_FAINT);
        } else {
            Theme.text(g, font, Theme.ellipsize(font, value, w), x, y, Theme.TEXT);
        }
        if (suiteSearchBox.isFocused() && (System.currentTimeMillis() / 500L) % 2L == 0L) {
            int cursorX = x + Math.min(w - 1, font.width(Theme.ellipsize(font, value, w)));
            g.fill(cursorX + 1, y - 1, cursorX + 2, y + 9, Theme.TEXT);
        }
    }

    private void renderSuiteQuantityBoxText(GuiGraphics g, int x, int y, int w) {
        String value = suiteQtyBox.getValue();
        if (value.isBlank()) value = "1";
        String shown = Theme.ellipsize(font, value, w - 6);
        int textX = x + Math.max(3, (w - font.width(shown)) / 2);
        Theme.text(g, font, shown, textX, y + 5, Theme.TEXT);
        if (suiteQtyBox.isFocused() && (System.currentTimeMillis() / 500L) % 2L == 0L) {
            int cursorX = x + Math.min(w - 4, Math.max(4, (w + font.width(shown)) / 2 + 1));
            g.fill(cursorX, y + 3, cursorX + 1, y + 13, Theme.TEXT);
        }
    }

    private void syncSuiteQuantityBox(int quantity) {
        if (suiteQtyBox == null || suiteQtyBox.isFocused()) return;
        String value = Integer.toString(Math.max(1, quantity));
        if (!value.equals(suiteQtyBox.getValue())) suiteQtyBox.setValue(value);
    }

    private void submitSuiteQuantity() {
        if (suiteQtyBox == null) return;
        int quantity = parseSuiteQuantity(suiteQtyBox.getValue());
        suiteQtyBox.setValue(Integer.toString(quantity));
        if (quantity != ClientTabletChestCache.suiteOrderQuantity()) {
            PacketDistributor.sendToServer(new TabletChestActionPacket(TabletChestActionPacket.SET_SUITE_ORDER_QUANTITY, quantity));
        }
    }

    private int parseSuiteQuantity(String value) {
        try {
            return clamp(Integer.parseInt(value == null || value.isBlank() ? "1" : value), 1, 9999);
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    private void renderSuiteSearchResults(GuiGraphics g, int mouseX, int mouseY, int x, int y, int w, int bottom) {
        rebuildSuiteSearchResults();
        if (!suiteSearchBox.isFocused() || suiteSearchResults.isEmpty()) return;
        int rows = Math.min(suiteSearchResults.size(), suiteSearchVisibleRows(y, bottom));
        suiteSearchScroll = clamp(suiteSearchScroll, 0, Math.max(0, suiteSearchResults.size() - rows));
        boolean scrollable = suiteSearchResults.size() > rows;
        int textW = w - 23 - (scrollable ? 7 : 0);
        for (int i = 0; i < rows; i++) {
            int rowY = y + i * 18;
            ItemStack stack = suiteSearchResults.get(suiteSearchScroll + i);
            int fill = Theme.inside(mouseX, mouseY, x, rowY, w, 17) ? Theme.PRIMARY_SOFT : Theme.SURFACE_SUNK;
            Theme.panel(g, x, rowY, w, 17, 4, fill, Theme.BORDER);
            g.renderItem(stack, x + 2, rowY);
            Theme.text(g, font, Theme.ellipsize(font, stack.getHoverName().getString(), textW), x + 21, rowY + 5, Theme.TEXT);
        }
        drawScrollbar(g, x + w - 4, y, rows * 18 - 1, suiteSearchResults.size(), rows, suiteSearchScroll);
    }

    private void rebuildSuiteSearchResults() {
        suiteSearchResults.clear();
        if (suiteSearchBox == null) return;
        String query = suiteSearchBox.getValue().trim().toLowerCase(Locale.ROOT);
        if (!query.equals(suiteSearchQuery)) {
            suiteSearchQuery = query;
            suiteSearchScroll = 0;
        }
        if (query.isEmpty()) return;
        for (var item : BuiltInRegistries.ITEM) {
            if (item == Items.AIR) continue;
            ItemStack stack = new ItemStack(item);
            String name = stack.getHoverName().getString().toLowerCase(Locale.ROOT);
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            String key = id == null ? "" : id.toString().toLowerCase(Locale.ROOT);
            if (name.contains(query) || key.contains(query)) {
                suiteSearchResults.add(stack);
            }
        }
    }

    private void renderSuitePoolColumn(GuiGraphics g, int mouseX, int mouseY, int px, int py, int pw, int ph, boolean tools) {
        Theme.panel(g, px, py, pw, ph, Theme.RADIUS, Theme.SURFACE_ALT, Theme.BORDER);
        Theme.text(g, font, tools ? "工具" : "资源", px + 7, py + 6, Theme.TEXT_MUTED);
        List<ItemStack> stacks = tools ? ClientTabletChestCache.suiteTools() : ClientTabletChestCache.suiteResources();
        int cols = suitePoolCols(pw);
        int rows = suitePoolRows(ph);
        int visible = cols * rows;
        int maxPage = suitePoolMaxPage(stacks.size(), visible);
        if (tools) suiteToolPage = clamp(suiteToolPage, 0, maxPage);
        else suiteResourcePage = clamp(suiteResourcePage, 0, maxPage);
        int page = tools ? suiteToolPage : suiteResourcePage;
        int start = page * visible;
        int gridX = px + (pw - cols * SLOT) / 2 + 1;
        int gridY = py + 22;
        for (int i = 0; i < visible; i++) {
            int idx = start + i;
            int sx = gridX + (i % cols) * SLOT;
            int sy = gridY + (i / cols) * SLOT;
            boolean hover = Theme.inside(mouseX, mouseY, sx, sy, 16, 16);
            ChestGuiTextures.slot(g, sx - 1, sy - 1, hover);
            if (idx >= stacks.size()) continue;
            ItemStack stack = stacks.get(idx);
            if (stack.isEmpty()) continue;
            if (tools && !isKnownSuiteTool(stack)) {
                g.fill(sx, sy, sx + 16, sy + 16, Theme.uiColor(0x66000000));
            }
            g.renderItem(stack, sx, sy);
            g.renderItemDecorations(font, stack, sx, sy, null);
            if (hover) hoveredStack = stack;
        }
        int pagerY = py + ph - 17;
        smallButton(g, px + 7, pagerY, 14, 14, "<", mouseX, mouseY, page > 0);
        smallButton(g, px + pw - 21, pagerY, 14, 14, ">", mouseX, mouseY, page < maxPage);
        String pageText = (page + 1) + "/" + (maxPage + 1);
        Theme.text(g, font, pageText, px + (pw - font.width(pageText)) / 2, pagerY + 4, Theme.TEXT_MUTED);
    }

    private void smallButton(GuiGraphics g, int x, int y, int w, int h, String text, int mouseX, int mouseY) {
        smallButton(g, x, y, w, h, text, mouseX, mouseY, true);
    }

    private void smallButton(GuiGraphics g, int x, int y, int w, int h, String text, int mouseX, int mouseY, boolean enabled) {
        int fill = !enabled ? Theme.uiColor(0x334A5A64)
                : Theme.inside(mouseX, mouseY, x, y, w, h) ? Theme.PRIMARY_SOFT : Theme.SURFACE_SUNK;
        Theme.panel(g, x, y, w, h, 4, fill, Theme.BORDER);
        Theme.text(g, font, text, x + Math.max(2, (w - font.width(text)) / 2), y + (h - 8) / 2 + 1,
                enabled ? Theme.TEXT : Theme.TEXT_FAINT);
    }

    private boolean isSuiteOrderPaused(TabletChestSyncPacket.OrderEntry order) {
        return order.state() != null && order.state().contains("暂停");
    }

    private boolean isKnownSuiteTool(ItemStack stack) {
        VanillaSuiteAdapters.registerBuiltIns();
        return SuiteToolRegistry.isSupportedTool(stack);
    }

    private void drawScrollbar(GuiGraphics g, int x, int y, int h, int total, int visible, int offset) {
        if (total <= visible || h <= 8) return;
        int track = Theme.uiColor(0x334A5A64);
        int thumb = Theme.uiColor(0xAA8FB4D0);
        g.fill(x, y, x + 2, y + h, track);
        int thumbH = Math.max(8, h * visible / Math.max(visible, total));
        int maxOffset = Math.max(1, total - visible);
        int thumbY = y + (h - thumbH) * clamp(offset, 0, maxOffset) / maxOffset;
        g.fill(x - 1, thumbY, x + 3, thumbY + thumbH, thumb);
    }

    private void renderCraftingPanel(GuiGraphics g, int mouseX, int mouseY, int px, int py, int pw, int ph) {
        Theme.panel(g, px, py, pw, ph, Theme.RADIUS, Theme.SURFACE_ALT, Theme.BORDER);
        Theme.text(g, font, "工作台", px + 8, py + 7, Theme.TEXT_MUTED);
        g.renderItem(new ItemStack(Items.CRAFTING_TABLE), px + pw - 23, py + 5);
        int gridX = px + 9;
        int gridY = py + 23;
        List<ItemStack> inputs = ClientTabletChestCache.workbenchInputs();
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                int idx = row * 3 + col;
                int sx = gridX + col * SLOT;
                int sy = gridY + row * SLOT;
                boolean hover = Theme.inside(mouseX, mouseY, sx, sy, 16, 16);
                ChestGuiTextures.slot(g, sx - 1, sy - 1, hover);
                if (idx < inputs.size()) {
                    ItemStack stack = inputs.get(idx);
                    if (!stack.isEmpty()) {
                        g.renderItem(stack, sx, sy);
                        g.renderItemDecorations(font, stack, sx, sy, null);
                        if (hover) hoveredStack = stack;
                    }
                }
            }
        }
        int outX = px + pw - 27;
        int outY = gridY + SLOT;
        drawArrow(g, gridX + 3 * SLOT + 5, outY + 7, Math.max(10, outX - gridX - 3 * SLOT - 9));
        boolean outputHover = Theme.inside(mouseX, mouseY, outX, outY, 16, 16);
        ChestGuiTextures.slot(g, outX - 1, outY - 1, outputHover);
        ItemStack result = ClientTabletChestCache.workbenchResult();
        if (!result.isEmpty()) {
            g.renderItem(result, outX, outY);
            g.renderItemDecorations(font, result, outX, outY, null);
            if (outputHover) hoveredStack = result;
        }
        markSuiteHover(mouseX, mouseY, px, py, pw, ph, "工作台");
    }

    private void renderFurnacePanel(GuiGraphics g, int mouseX, int mouseY, int px, int py, int pw, int ph) {
        Theme.panel(g, px, py, pw, ph, Theme.RADIUS, Theme.SURFACE_ALT, Theme.BORDER);
        Theme.text(g, font, "高级熔炉", px + 8, py + 7, Theme.TEXT_MUTED);
        g.renderItem(furnaceModeIcon(), px + pw - 23, py + 5);
        int inX = px + 28;
        int inY = py + 22;
        int fuelY = py + ph - 21;
        int arrowX = inX + SLOT + 10;
        int arrowW = 24;
        int outX = Math.min(px + pw - 27, arrowX + arrowW + 8);
        int outY = py + Math.max(30, (ph - 16) / 2 + 7);
        List<ItemStack> items = ClientTabletChestCache.furnaceItems();

        renderFurnaceSlot(g, mouseX, mouseY, inX, inY, items.size() > 0 ? items.get(0) : ItemStack.EMPTY);
        renderFurnaceSlot(g, mouseX, mouseY, inX, fuelY, items.size() > 1 ? items.get(1) : ItemStack.EMPTY);

        int flameX = inX + 1;
        int flameY = inY + 16 + Math.max(0, fuelY - inY - 16 - 14) / 2;
        drawFuelFlame(g, flameX, flameY, ClientTabletChestCache.furnaceLitTime(), ClientTabletChestCache.furnaceLitDuration());

        drawFurnaceProgressArrow(g, arrowX, outY + 3, arrowW,
                ClientTabletChestCache.furnaceCookingProgress(), ClientTabletChestCache.furnaceCookingTotalTime());

        renderFurnaceSlot(g, mouseX, mouseY, outX, outY, items.size() > 2 ? items.get(2) : ItemStack.EMPTY);
        markSuiteHover(mouseX, mouseY, px, py, pw, ph, "高级熔炉");
    }

    private void renderFurnaceSlot(GuiGraphics g, int mouseX, int mouseY, int x, int y, ItemStack stack) {
        boolean hover = Theme.inside(mouseX, mouseY, x, y, 16, 16);
        ChestGuiTextures.slot(g, x - 1, y - 1, hover);
        if (!stack.isEmpty()) {
            g.renderItem(stack, x, y);
            g.renderItemDecorations(font, stack, x, y, null);
            if (hover) hoveredStack = stack;
        }
    }

    private ItemStack furnaceModeIcon() {
        return switch (ClientTabletChestCache.furnaceMode()) {
            case 1 -> new ItemStack(Items.BLAST_FURNACE);
            case 2 -> new ItemStack(Items.SMOKER);
            default -> new ItemStack(Items.FURNACE);
        };
    }

    private void drawFuelFlame(GuiGraphics g, int x, int y, int litTime, int litDuration) {
        int empty = Theme.uiColor(0x774C5A5F);
        int fill = Theme.uiColor(0xFFFFB347);
        int hot = Theme.uiColor(0xFFFF6A2A);
        int size = 14;
        int filledRows = litTime > 0 && litDuration > 0
                ? Math.max(1, Math.min(size, (int) Math.ceil(litTime * (double) size / litDuration)))
                : 0;
        for (int row = 0; row < size; row++) {
            int half = row / 2;
            int start = size / 2 - half;
            int end = size / 2 + half + 1;
            int color = row >= size - filledRows ? fill : empty;
            g.fill(x + start, y + row, x + end, y + row + 1, color);
            if (row >= size - filledRows + 4 && row < size - 2) {
                g.fill(x + start + 3, y + row, x + Math.max(start + 4, end - 3), y + row + 1, hot);
            }
        }
    }

    private void drawFurnaceProgressArrow(GuiGraphics g, int x, int y, int w, int progress, int total) {
        if (w <= 0) return;
        int bg = Theme.uiColor(0xFF8FB4D0);
        int fill = Theme.uiColor(0xFFFFFFFF);
        drawFurnaceArrowShape(g, x, y, w, bg);
        if (progress <= 0 || total <= 0) return;
        int filled = Math.max(1, Math.min(w, (int) Math.ceil(progress * (double) w / total)));
        drawFurnaceArrowShape(g, x, y, filled, fill);
    }

    private void drawFurnaceArrowShape(GuiGraphics g, int x, int y, int w, int color) {
        int shaftW = Math.max(0, w - 8);
        if (shaftW > 0) g.fill(x, y + 4, x + shaftW, y + 10, color);
        int headX = x + shaftW;
        int headW = w - shaftW;
        if (headW <= 0) return;
        if (headW > 0) g.fill(headX, y + 1, headX + Math.min(headW, 3), y + 13, color);
        if (headW > 3) g.fill(headX + 3, y + 3, headX + Math.min(headW, 6), y + 11, color);
        if (headW > 6) g.fill(headX + 6, y + 5, headX + headW, y + 9, color);
    }

    private void renderSmallSuitePanel(GuiGraphics g, int mouseX, int mouseY, int px, int py, int pw, int ph, String label, ItemStack icon) {
        Theme.panel(g, px, py, pw, ph, Theme.RADIUS, Theme.SURFACE_ALT, Theme.BORDER);
        Theme.text(g, font, label, px + 8, py + 7, Theme.TEXT_MUTED);
        if (!icon.isEmpty()) g.renderItem(icon, px + pw - 23, py + 5);
        markSuiteHover(mouseX, mouseY, px, py, pw, ph, label);
    }

    private void renderSmithingPanel(GuiGraphics g, int mouseX, int mouseY, int px, int py, int pw, int ph) {
        Theme.panel(g, px, py, pw, ph, Theme.RADIUS, Theme.SURFACE_ALT, Theme.BORDER);
        Theme.text(g, font, "锻造台", px + 8, py + 7, Theme.TEXT_MUTED);
        g.renderItem(new ItemStack(Items.SMITHING_TABLE), px + pw - 23, py + 5);
        int slotY = py + Math.max(20, (ph - 16) / 2 + 5);
        int slotX = px + 9;
        List<ItemStack> inputs = ClientTabletChestCache.smithingInputs();
        for (int i = 0; i < 3; i++) {
            int sx = slotX + i * SLOT;
            boolean hover = Theme.inside(mouseX, mouseY, sx, slotY, 16, 16);
            ChestGuiTextures.slot(g, sx - 1, slotY - 1, hover);
            if (i < inputs.size()) {
                ItemStack stack = inputs.get(i);
                if (!stack.isEmpty()) {
                    g.renderItem(stack, sx, slotY);
                    g.renderItemDecorations(font, stack, sx, slotY, null);
                    if (hover) hoveredStack = stack;
                }
            }
        }
        int outX = px + pw - 27;
        drawArrow(g, slotX + 3 * SLOT + 4, slotY + 7, Math.max(8, outX - slotX - 3 * SLOT - 8));
        boolean outputHover = Theme.inside(mouseX, mouseY, outX, slotY, 16, 16);
        ChestGuiTextures.slot(g, outX - 1, slotY - 1, outputHover);
        ItemStack result = ClientTabletChestCache.smithingResult();
        if (!result.isEmpty()) {
            g.renderItem(result, outX, slotY);
            g.renderItemDecorations(font, result, outX, slotY, null);
            if (outputHover) hoveredStack = result;
        }
        markSuiteHover(mouseX, mouseY, px, py, pw, ph, "锻造台");
    }

    private void renderStonecutterPanel(GuiGraphics g, int mouseX, int mouseY, int px, int py, int pw, int ph) {
        Theme.panel(g, px, py, pw, ph, Theme.RADIUS, Theme.SURFACE_ALT, Theme.BORDER);
        Theme.text(g, font, "切石机", px + 8, py + 7, Theme.TEXT_MUTED);
        g.renderItem(new ItemStack(Items.STONECUTTER), px + pw - 23, py + 5);
        int slotY = py + Math.max(20, (ph - 16) / 2 + 5);
        int inX = px + 10;
        boolean inputHover = Theme.inside(mouseX, mouseY, inX, slotY, 16, 16);
        ChestGuiTextures.slot(g, inX - 1, slotY - 1, inputHover);
        ItemStack input = ClientTabletChestCache.stonecutterInput();
        if (!input.isEmpty()) {
            g.renderItem(input, inX, slotY);
            g.renderItemDecorations(font, input, inX, slotY, null);
            if (inputHover) hoveredStack = input;
        }

        int outX = px + pw - 27;
        int choiceX = inX + SLOT + 4;
        int visibleChoices = Math.max(1, (outX - choiceX - 2) / 16);
        List<ItemStack> results = ClientTabletChestCache.stonecutterResults();
        stonecutterScrollOffset = clamp(stonecutterScrollOffset, 0, Math.max(0, results.size() - visibleChoices));
        int selectedIndex = ClientTabletChestCache.stonecutterSelectedIndex();
        if (selectedIndex >= 0 && selectedIndex < stonecutterScrollOffset) {
            stonecutterScrollOffset = selectedIndex;
        } else if (selectedIndex >= stonecutterScrollOffset + visibleChoices) {
            stonecutterScrollOffset = clamp(selectedIndex - visibleChoices + 1, 0, Math.max(0, results.size() - visibleChoices));
        }
        int choiceY = slotY + 2;
        for (int i = 0; i < visibleChoices && stonecutterScrollOffset + i < results.size(); i++) {
            int idx = stonecutterScrollOffset + i;
            int sx = choiceX + i * 16;
            boolean selected = idx == selectedIndex;
            boolean hover = Theme.inside(mouseX, mouseY, sx, choiceY, 12, 12);
            int fill = selected ? Theme.uiColor(0xAA6EA6C8) : hover ? Theme.uiColor(0x665E8198) : Theme.uiColor(0x33455B68);
            int border = selected ? Theme.PRIMARY : Theme.BORDER;
            g.fill(sx, choiceY, sx + 12, choiceY + 12, fill);
            g.fill(sx, choiceY, sx + 12, choiceY + 1, border);
            g.fill(sx, choiceY + 11, sx + 12, choiceY + 12, border);
            g.fill(sx, choiceY, sx + 1, choiceY + 12, border);
            g.fill(sx + 11, choiceY, sx + 12, choiceY + 12, border);
            ItemStack result = results.get(idx);
            g.pose().pushPose();
            g.pose().translate(sx, choiceY, 0);
            g.pose().scale(0.75f, 0.75f, 1.0f);
            g.renderItem(result, 0, 0);
            g.pose().popPose();
            if (hover) hoveredStack = result;
        }

        boolean outputHover = Theme.inside(mouseX, mouseY, outX, slotY, 16, 16);
        ChestGuiTextures.slot(g, outX - 1, slotY - 1, outputHover);
        ItemStack result = ClientTabletChestCache.stonecutterResult();
        if (!result.isEmpty()) {
            g.renderItem(result, outX, slotY);
            g.renderItemDecorations(font, result, outX, slotY, null);
            if (outputHover) hoveredStack = result;
        }
        markSuiteHover(mouseX, mouseY, px, py, pw, ph, "切石机");
    }

    private void drawArrow(GuiGraphics g, int x, int y, int w) {
        if (w <= 0) return;
        int color = Theme.uiColor(0xFF8FB4D0);
        g.fill(x, y, x + w, y + 2, color);
        g.fill(x + w - 3, y - 2, x + w - 1, y + 4, color);
        g.fill(x + w - 1, y - 1, x + w + 1, y + 3, color);
    }

    private void markSuiteHover(int mouseX, int mouseY, int x, int y, int w, int h, String label) {
        if (hoveredStack.isEmpty() && hoveredSuitePanel.isEmpty() && Theme.inside(mouseX, mouseY, x, y, w, h)) hoveredSuitePanel = label;
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
        if (!hoveredStack.isEmpty() && !hoveredFromChest) {
            g.renderTooltip(font, hoveredStack, mouseX, mouseY);
            return;
        }
        if (!hoveredSuitePanel.isEmpty()) {
            g.renderTooltip(font, Component.literal(hoveredSuitePanel), mouseX, mouseY);
            return;
        }
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
            if (suiteSearchBox != null) suiteSearchBox.setFocused(false);
            if (suiteQtyBox != null && suiteQtyBox.isFocused()) {
                submitSuiteQuantity();
                suiteQtyBox.setFocused(false);
            }
            searchBox.mouseClicked(mx, my, button);
            return true;
        }
        if (searchBox != null) searchBox.setFocused(false);
        if (sortButton != null && sortButton.mouseClicked(mx, my, button)) return true;
        if (refreshButton != null && refreshButton.mouseClicked(mx, my, button)) return true;
        if (!ClientTabletChestCache.available()) return false;

        if (handleFurnaceClick(mx, my, button)) return true;
        if (handleWorkbenchClick(mx, my, button)) return true;
        if (handleSmithingClick(mx, my, button)) return true;
        if (handleStonecutterClick(mx, my, button)) return true;
        if (handleSuiteOrderClick(mx, my, button)) return true;
        if (suiteQtyBox != null && suiteQtyBox.isFocused()) {
            submitSuiteQuantity();
            suiteQtyBox.setFocused(false);
        }

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

    private boolean handleWorkbenchClick(double mx, double my, int button) {
        if (!ClientTabletChestCache.suiteUpgradeInstalled()) return false;
        int inputSlot = workbenchInputSlotAt(mx, my);
        if (inputSlot >= 0) {
            PacketDistributor.sendToServer(new TabletChestActionPacket(button == 1
                    ? TabletChestActionPacket.CLICK_WORKBENCH_RIGHT
                    : TabletChestActionPacket.CLICK_WORKBENCH_LEFT, inputSlot));
            return true;
        }
        if (isWorkbenchOutputAt(mx, my)) {
            PacketDistributor.sendToServer(new TabletChestActionPacket(Screen.hasShiftDown()
                    ? TabletChestActionPacket.QUICK_MOVE_WORKBENCH_RESULT
                    : TabletChestActionPacket.TAKE_WORKBENCH_RESULT));
            return true;
        }
        return false;
    }

    private boolean handleFurnaceClick(double mx, double my, int button) {
        if (!ClientTabletChestCache.suiteUpgradeInstalled()) return false;
        int slot = furnaceSlotAt(mx, my);
        if (slot < 0) return false;
        int action;
        if (slot == 2 && Screen.hasShiftDown()) {
            action = TabletChestActionPacket.QUICK_MOVE_FURNACE_OUTPUT;
        } else {
            action = button == 1
                    ? TabletChestActionPacket.CLICK_FURNACE_RIGHT
                    : TabletChestActionPacket.CLICK_FURNACE_LEFT;
        }
        PacketDistributor.sendToServer(new TabletChestActionPacket(action, slot));
        return true;
    }

    private boolean handleSmithingClick(double mx, double my, int button) {
        if (!ClientTabletChestCache.suiteUpgradeInstalled()) return false;
        int inputSlot = smithingInputSlotAt(mx, my);
        if (inputSlot >= 0) {
            PacketDistributor.sendToServer(new TabletChestActionPacket(button == 1
                    ? TabletChestActionPacket.CLICK_SMITHING_RIGHT
                    : TabletChestActionPacket.CLICK_SMITHING_LEFT, inputSlot));
            return true;
        }
        if (isSmithingOutputAt(mx, my)) {
            PacketDistributor.sendToServer(new TabletChestActionPacket(Screen.hasShiftDown()
                    ? TabletChestActionPacket.QUICK_MOVE_SMITHING_RESULT
                    : TabletChestActionPacket.TAKE_SMITHING_RESULT));
            return true;
        }
        return false;
    }

    private boolean handleStonecutterClick(double mx, double my, int button) {
        if (!ClientTabletChestCache.suiteUpgradeInstalled()) return false;
        if (isStonecutterInputAt(mx, my)) {
            PacketDistributor.sendToServer(new TabletChestActionPacket(button == 1
                    ? TabletChestActionPacket.CLICK_STONECUTTER_RIGHT
                    : TabletChestActionPacket.CLICK_STONECUTTER_LEFT));
            return true;
        }
        int recipe = stonecutterRecipeAt(mx, my);
        if (recipe >= 0) {
            PacketDistributor.sendToServer(new TabletChestActionPacket(TabletChestActionPacket.SELECT_STONECUTTER_RECIPE, recipe));
            return true;
        }
        if (isStonecutterOutputAt(mx, my)) {
            PacketDistributor.sendToServer(new TabletChestActionPacket(Screen.hasShiftDown()
                    ? TabletChestActionPacket.QUICK_MOVE_STONECUTTER_RESULT
                    : TabletChestActionPacket.TAKE_STONECUTTER_RESULT));
            return true;
        }
        return false;
    }

    private boolean handleSuiteOrderClick(double mx, double my, int button) {
        if (!ClientTabletChestCache.suiteUpgradeInstalled()) return false;
        if (button == 0) {
            int scrollbar = suiteScrollbarAt(mx, my);
            if (scrollbar != 0) {
                suiteScrollDrag = scrollbar;
                updateSuiteScrollbarDrag(my);
                return true;
            }
            int toolPageDelta = suitePoolPageButtonAt(mx, my, true);
            if (toolPageDelta != 0) {
                int capacity = suitePoolCapacity(suiteToolRect());
                int maxPage = suitePoolMaxPage(ClientTabletChestCache.suiteTools().size(), capacity);
                suiteToolPage = clamp(suiteToolPage + toolPageDelta, 0, maxPage);
                return true;
            }
            int resourcePageDelta = suitePoolPageButtonAt(mx, my, false);
            if (resourcePageDelta != 0) {
                int capacity = suitePoolCapacity(suiteResourceRect());
                int maxPage = suitePoolMaxPage(ClientTabletChestCache.suiteResources().size(), capacity);
                suiteResourcePage = clamp(suiteResourcePage + resourcePageDelta, 0, maxPage);
                return true;
            }
        }
        int toolSlot = suitePoolSlotAt(mx, my, true);
        if (toolSlot >= 0) {
            PacketDistributor.sendToServer(new TabletChestActionPacket(button == 1
                    ? TabletChestActionPacket.CLICK_SUITE_TOOL_RIGHT
                    : TabletChestActionPacket.CLICK_SUITE_TOOL_LEFT, toolSlot));
            return true;
        }
        int resourceSlot = suitePoolSlotAt(mx, my, false);
        if (resourceSlot >= 0) {
            PacketDistributor.sendToServer(new TabletChestActionPacket(button == 1
                    ? TabletChestActionPacket.CLICK_SUITE_RESOURCE_RIGHT
                    : TabletChestActionPacket.CLICK_SUITE_RESOURCE_LEFT, resourceSlot));
            return true;
        }
        int[] rect = suiteOrderRect();
        if (rect == null) return false;
        int px = rect[0], py = rect[1], pw = rect[2], ph = rect[3];
        if (!Theme.inside(mx, my, px, py, pw, ph)) return false;

        int targetX = px + 8;
        int targetY = py + 21;
        int searchX = targetX + 22;
        int createW = 31;
        int createX = px + pw - createW - 7;
        int plusX = createX - 18;
        int qtyW = 28;
        int qtyX = plusX - qtyW - 2;
        int minusX = qtyX - 16;
        int searchW = Math.max(48, minusX - searchX - 5);
        int result = suiteSearchResultAt(mx, my, searchX, targetY + 20, searchW, py + ph - 6);
        if (result >= 0 && result < suiteSearchResults.size()) {
            ItemStack stack = suiteSearchResults.get(result).copyWithCount(1);
            suiteSearchBox.setValue(stack.getHoverName().getString());
            suiteSearchBox.setFocused(false);
            PacketDistributor.sendToServer(new TabletChestActionPacket(TabletChestActionPacket.SET_SUITE_ORDER_TARGET, stack));
            return true;
        }
        boolean insideQty = Theme.inside(mx, my, qtyX, targetY - 1, qtyW, 18);
        if (suiteQtyBox != null && suiteQtyBox.isFocused() && !insideQty) {
            submitSuiteQuantity();
            suiteQtyBox.setFocused(false);
        }
        if (Theme.inside(mx, my, searchX, targetY - 1, searchW, 18)) {
            suiteSearchBox.setFocused(true);
            if (suiteQtyBox != null) suiteQtyBox.setFocused(false);
            suiteSearchBox.mouseClicked(mx, my, button);
            return true;
        }
        suiteSearchBox.setFocused(false);
        if (insideQty) {
            if (suiteQtyBox != null) {
                suiteQtyBox.setFocused(true);
                suiteQtyBox.mouseClicked(mx, my, button);
            }
            return true;
        }
        if (Theme.inside(mx, my, targetX, targetY, 16, 16)) {
            PacketDistributor.sendToServer(new TabletChestActionPacket(TabletChestActionPacket.SET_SUITE_ORDER_TARGET));
            return true;
        }
        if (Theme.inside(mx, my, minusX, targetY, 14, 16)) {
            submitSuiteQuantity();
            PacketDistributor.sendToServer(new TabletChestActionPacket(TabletChestActionPacket.SUITE_ORDER_QTY_MINUS, Screen.hasShiftDown() ? 64 : 1));
            return true;
        }
        if (Theme.inside(mx, my, plusX, targetY, 14, 16)) {
            submitSuiteQuantity();
            PacketDistributor.sendToServer(new TabletChestActionPacket(TabletChestActionPacket.SUITE_ORDER_QTY_PLUS, Screen.hasShiftDown() ? 64 : 1));
            return true;
        }
        if (Theme.inside(mx, my, createX, targetY, createW, 16)) {
            submitSuiteQuantity();
            PacketDistributor.sendToServer(new TabletChestActionPacket(TabletChestActionPacket.CREATE_SUITE_ORDER));
            return true;
        }

        int rowH = 19;
        int listY = suiteOrderListY(py);
        int rows = Math.max(1, (py + ph - listY - 6) / rowH);
        List<TabletChestSyncPacket.OrderEntry> orders = ClientTabletChestCache.suiteOrders();
        suiteOrderScroll = clamp(suiteOrderScroll, 0, Math.max(0, orders.size() - rows));
        for (int i = 0; i < rows && suiteOrderScroll + i < orders.size(); i++) {
            TabletChestSyncPacket.OrderEntry order = orders.get(suiteOrderScroll + i);
            int y0 = listY + i * rowH;
            if (order.canClaim() || order.canRecover() || order.canDelete()) {
                if (!Theme.inside(mx, my, px + pw - 23, y0 + 1, 16, 15)) continue;
                int action = order.canClaim() ? TabletChestActionPacket.CLAIM_SUITE_ORDER
                        : order.canRecover() ? TabletChestActionPacket.RECOVER_SUITE_ORDER
                        : TabletChestActionPacket.DELETE_SUITE_ORDER;
                PacketDistributor.sendToServer(new TabletChestActionPacket(action, order.id()));
                return true;
            }
            if (Theme.inside(mx, my, px + pw - 41, y0 + 1, 16, 15)) {
                PacketDistributor.sendToServer(new TabletChestActionPacket(TabletChestActionPacket.STOP_SUITE_ORDER, order.id()));
                return true;
            }
            if (Theme.inside(mx, my, px + pw - 23, y0 + 1, 16, 15)) {
                PacketDistributor.sendToServer(new TabletChestActionPacket(TabletChestActionPacket.CANCEL_SUITE_ORDER, order.id()));
                return true;
            }
        }
        return false;
    }

    private int suiteSearchResultAt(double mx, double my, int x, int y, int w, int bottom) {
        rebuildSuiteSearchResults();
        if (suiteSearchBox == null || !suiteSearchBox.isFocused() || suiteSearchResults.isEmpty()) return -1;
        int rows = Math.min(suiteSearchResults.size(), suiteSearchVisibleRows(y, bottom));
        suiteSearchScroll = clamp(suiteSearchScroll, 0, Math.max(0, suiteSearchResults.size() - rows));
        for (int i = 0; i < rows; i++) {
            if (Theme.inside(mx, my, x, y + i * 18, w, 17)) return suiteSearchScroll + i;
        }
        return -1;
    }

    private int suiteSearchVisibleRows(int y, int bottom) {
        return Math.max(1, (bottom - y) / 18);
    }

    private boolean suiteSearchDropdownContains(double mx, double my, int[] order) {
        if (order == null || suiteSearchBox == null || !suiteSearchBox.isFocused()) return false;
        rebuildSuiteSearchResults();
        if (suiteSearchResults.isEmpty()) return false;
        int targetX = order[0] + 8;
        int targetY = order[1] + 21;
        int searchX = targetX + 22;
        int createW = 31;
        int createX = order[0] + order[2] - createW - 7;
        int plusX = createX - 18;
        int qtyW = 28;
        int qtyX = plusX - qtyW - 2;
        int minusX = qtyX - 16;
        int searchW = Math.max(48, minusX - searchX - 5);
        int dropdownY = targetY + 20;
        int rows = Math.min(suiteSearchResults.size(), suiteSearchVisibleRows(dropdownY, order[1] + order[3] - 6));
        return Theme.inside(mx, my, searchX, dropdownY, searchW, rows * 18);
    }

    private int suiteOrderListY(int panelY) {
        return panelY + 45;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (suiteScrollDrag != 0) {
            updateSuiteScrollbarDrag(my);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (suiteScrollDrag != 0) {
            suiteScrollDrag = 0;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (handleSuiteOrderScroll(mx, my, sy)) return true;
        if (handleStonecutterScroll(mx, my, sy)) return true;
        if (!ClientTabletChestCache.available() || !isInChestArea(mx, my)) return false;
        int rows = currentChestRows();
        int totalRows = Math.max(rows, (visibleItems.size() + COLS - 1) / COLS);
        if (totalRows <= rows) return true;
        chestScrollRow = clamp(chestScrollRow - (int) Math.signum(sy), 0, totalRows - rows);
        return true;
    }

    private boolean handleStonecutterScroll(double mx, double my, double sy) {
        if (!ClientTabletChestCache.available() || !ClientTabletChestCache.suiteUpgradeInstalled()) return false;
        int[] rect = stonecutterRect();
        if (rect == null || !Theme.inside(mx, my, rect[0], rect[1], rect[2], rect[3])) return false;
        List<ItemStack> results = ClientTabletChestCache.stonecutterResults();
        int visible = stonecutterVisibleChoices(rect);
        if (results.size() <= visible) return true;
        stonecutterScrollOffset = clamp(stonecutterScrollOffset - (int) Math.signum(sy), 0, results.size() - visible);
        return true;
    }

    private boolean handleSuiteOrderScroll(double mx, double my, double sy) {
        if (!ClientTabletChestCache.available() || !ClientTabletChestCache.suiteUpgradeInstalled()) return false;
        int[] order = suiteOrderRect();
        int[] tools = suiteToolRect();
        int[] resources = suiteResourceRect();
        int dir = (int) Math.signum(sy);
        if (suiteSearchDropdownContains(mx, my, order)) {
            int targetY = order[1] + 21;
            int rows = Math.min(suiteSearchResults.size(), suiteSearchVisibleRows(targetY + 20, order[1] + order[3] - 6));
            suiteSearchScroll = clamp(suiteSearchScroll - dir, 0, Math.max(0, suiteSearchResults.size() - rows));
            return true;
        }
        if (order != null && Theme.inside(mx, my, order[0], order[1], order[2], order[3])) {
            int listY = suiteOrderListY(order[1]);
            int rows = Math.max(1, (order[1] + order[3] - listY - 6) / 19);
            suiteOrderScroll = clamp(suiteOrderScroll - dir, 0, Math.max(0, ClientTabletChestCache.suiteOrders().size() - rows));
            return true;
        }
        if (tools != null && Theme.inside(mx, my, tools[0], tools[1], tools[2], tools[3])) {
            int capacity = suitePoolCapacity(tools);
            suiteToolPage = clamp(suiteToolPage - dir, 0, suitePoolMaxPage(ClientTabletChestCache.suiteTools().size(), capacity));
            return true;
        }
        if (resources != null && Theme.inside(mx, my, resources[0], resources[1], resources[2], resources[3])) {
            int capacity = suitePoolCapacity(resources);
            suiteResourcePage = clamp(suiteResourcePage - dir, 0, suitePoolMaxPage(ClientTabletChestCache.suiteResources().size(), capacity));
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (suiteQtyBox != null && suiteQtyBox.isFocused()) {
            if (keyCode == 256) {
                suiteQtyBox.setValue(Integer.toString(ClientTabletChestCache.suiteOrderQuantity()));
                suiteQtyBox.setFocused(false);
                return true;
            }
            if (keyCode == 257 || keyCode == 335) {
                submitSuiteQuantity();
                suiteQtyBox.setFocused(false);
                return true;
            }
            return suiteQtyBox.keyPressed(keyCode, scanCode, modifiers) || suiteQtyBox.isFocused();
        }
        if (suiteSearchBox != null && suiteSearchBox.isFocused()) {
            if (keyCode == 256) {
                suiteSearchBox.setFocused(false);
                return true;
            }
            return suiteSearchBox.keyPressed(keyCode, scanCode, modifiers) || suiteSearchBox.isFocused();
        }
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
        if (suiteQtyBox != null && suiteQtyBox.isFocused()) {
            return suiteQtyBox.charTyped(codePoint, modifiers);
        }
        if (suiteSearchBox != null && suiteSearchBox.isFocused()) {
            return suiteSearchBox.charTyped(codePoint, modifiers);
        }
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

    private int workbenchInputSlotAt(double mx, double my) {
        int[] craft = craftingRect();
        if (craft == null) return -1;
        int gridX = craft[0] + 9;
        int gridY = craft[1] + 23;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                int sx = gridX + col * SLOT;
                int sy = gridY + row * SLOT;
                if (Theme.inside(mx, my, sx, sy, 16, 16)) return row * 3 + col;
            }
        }
        return -1;
    }

    private boolean isWorkbenchOutputAt(double mx, double my) {
        int[] craft = craftingRect();
        if (craft == null) return false;
        int outX = craft[0] + craft[2] - 27;
        int outY = craft[1] + 23 + SLOT;
        return Theme.inside(mx, my, outX, outY, 16, 16);
    }

    private int furnaceSlotAt(double mx, double my) {
        int[] rect = furnaceRect();
        if (rect == null) return -1;
        int inX = rect[0] + 28;
        int inY = rect[1] + 22;
        int fuelY = rect[1] + rect[3] - 21;
        int arrowX = inX + SLOT + 10;
        int outX = Math.min(rect[0] + rect[2] - 27, arrowX + 24 + 8);
        int outY = rect[1] + Math.max(30, (rect[3] - 16) / 2 + 7);
        if (Theme.inside(mx, my, inX, inY, 16, 16)) return 0;
        if (Theme.inside(mx, my, inX, fuelY, 16, 16)) return 1;
        if (Theme.inside(mx, my, outX, outY, 16, 16)) return 2;
        return -1;
    }

    private int smithingInputSlotAt(double mx, double my) {
        int[] rect = smithingRect();
        if (rect == null) return -1;
        int slotY = rect[1] + Math.max(20, (rect[3] - 16) / 2 + 5);
        int slotX = rect[0] + 9;
        for (int i = 0; i < 3; i++) {
            int sx = slotX + i * SLOT;
            if (Theme.inside(mx, my, sx, slotY, 16, 16)) return i;
        }
        return -1;
    }

    private boolean isSmithingOutputAt(double mx, double my) {
        int[] rect = smithingRect();
        if (rect == null) return false;
        int slotY = rect[1] + Math.max(20, (rect[3] - 16) / 2 + 5);
        int outX = rect[0] + rect[2] - 27;
        return Theme.inside(mx, my, outX, slotY, 16, 16);
    }

    private boolean isStonecutterInputAt(double mx, double my) {
        int[] rect = stonecutterRect();
        if (rect == null) return false;
        int slotY = rect[1] + Math.max(20, (rect[3] - 16) / 2 + 5);
        int inX = rect[0] + 10;
        return Theme.inside(mx, my, inX, slotY, 16, 16);
    }

    private int stonecutterRecipeAt(double mx, double my) {
        int[] rect = stonecutterRect();
        if (rect == null) return -1;
        int slotY = rect[1] + Math.max(20, (rect[3] - 16) / 2 + 5);
        int inX = rect[0] + 10;
        int outX = rect[0] + rect[2] - 27;
        int choiceX = inX + SLOT + 4;
        int visible = stonecutterVisibleChoices(rect);
        List<ItemStack> results = ClientTabletChestCache.stonecutterResults();
        stonecutterScrollOffset = clamp(stonecutterScrollOffset, 0, Math.max(0, results.size() - visible));
        for (int i = 0; i < visible && stonecutterScrollOffset + i < results.size(); i++) {
            int sx = choiceX + i * 16;
            if (sx + 12 <= outX && Theme.inside(mx, my, sx, slotY + 2, 12, 12)) return stonecutterScrollOffset + i;
        }
        return -1;
    }

    private boolean isStonecutterOutputAt(double mx, double my) {
        int[] rect = stonecutterRect();
        if (rect == null) return false;
        int slotY = rect[1] + Math.max(20, (rect[3] - 16) / 2 + 5);
        int outX = rect[0] + rect[2] - 27;
        return Theme.inside(mx, my, outX, slotY, 16, 16);
    }

    private int stonecutterVisibleChoices(int[] rect) {
        int inX = rect[0] + 10;
        int outX = rect[0] + rect[2] - 27;
        int choiceX = inX + SLOT + 4;
        return Math.max(1, (outX - choiceX - 2) / 16);
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
        int chestW = storageColumnWidth();
        int chestH = Math.max(SLOT * 2 + 10, h - pad * 2 - toolbarH - footerH - 10);
        return new int[] { chestX, chestY, chestW, chestH };
    }

    private int[] playerRect() {
        int[] chest = chestRect();
        int py = y + h - Theme.PAD - PLAYER_ROWS * SLOT - 10;
        return new int[] { chest[0], py, chest[2], PLAYER_ROWS * SLOT + 10 };
    }

    private int storageColumnWidth() {
        int pad = Theme.PAD;
        int base = COLS * SLOT + 10;
        int available = Math.max(base, w - pad * 2);
        return Math.min(base, available);
    }

    private int[] suiteRect() {
        int[] chest = chestRect();
        int[] player = playerRect();
        int sx = chest[0] + chest[2] + SUITE_GAP;
        int sw = x + w - Theme.PAD - sx;
        int sy = chest[1];
        int sh = player[1] + player[3] - sy;
        if (sw < MIN_SUITE_W || sh < 160) return null;
        return new int[] { sx, sy, sw, sh };
    }

    private int[] craftingRect() {
        int[] rect = suiteRect();
        if (rect == null) return null;
        int sw = Math.min(rect[2], MAX_SUITE_CONTENT_W);
        int gap = 12;
        int columnW = Math.max(1, (sw - gap) / 2);
        int upperH = suiteUpperH(rect[3]);
        int topH = suiteTopRowH(upperH);
        return new int[] { rect[0], rect[1], columnW, topH };
    }

    private int[] furnaceRect() {
        int[] rect = suiteRect();
        if (rect == null) return null;
        int sw = Math.min(rect[2], MAX_SUITE_CONTENT_W);
        int gap = 12;
        int columnW = Math.max(1, (sw - gap) / 2);
        int upperH = suiteUpperH(rect[3]);
        int topH = suiteTopRowH(upperH);
        int rightX = rect[0] + columnW + gap;
        return new int[] { rightX, rect[1], columnW, topH };
    }

    private int[] smithingRect() {
        int[] rect = suiteRect();
        if (rect == null) return null;
        int sw = Math.min(rect[2], MAX_SUITE_CONTENT_W);
        int gap = 12;
        int columnW = Math.max(1, (sw - gap) / 2);
        int upperH = suiteUpperH(rect[3]);
        int topH = suiteTopRowH(upperH);
        int bottomY = rect[1] + topH + gap;
        int bottomH = Math.max(48, upperH - topH - gap);
        return new int[] { rect[0], bottomY, columnW, bottomH };
    }

    private int[] stonecutterRect() {
        int[] rect = suiteRect();
        if (rect == null) return null;
        int sw = Math.min(rect[2], MAX_SUITE_CONTENT_W);
        int gap = 12;
        int columnW = Math.max(1, (sw - gap) / 2);
        int upperH = suiteUpperH(rect[3]);
        int topH = suiteTopRowH(upperH);
        int rightX = rect[0] + columnW + gap;
        int bottomY = rect[1] + topH + gap;
        int bottomH = Math.max(48, upperH - topH - gap);
        return new int[] { rightX, bottomY, columnW, bottomH };
    }

    private int[] suiteOrderAreaRect() {
        int[] rect = suiteRect();
        if (rect == null) return null;
        int sw = Math.min(rect[2], MAX_SUITE_CONTENT_W);
        int gap = 12;
        int upperH = suiteUpperH(rect[3]);
        int lowerY = rect[1] + upperH + gap;
        int lowerH = Math.max(44, rect[3] - upperH - gap);
        return new int[] { rect[0], lowerY, sw, lowerH };
    }

    private int suiteUpperH(int suiteHeight) {
        return Math.max(150, Math.min(170, (suiteHeight * 2) / 5));
    }

    private int suiteTopRowH(int upperH) {
        return Math.max(78, Math.min(86, upperH - 12 - 54));
    }

    private int[] suiteOrderRect() {
        int[] area = suiteOrderAreaRect();
        if (area == null) return null;
        int gap = 8;
        int sideW = 62;
        int orderW = Math.max(120, area[2] - sideW - gap);
        return new int[] { area[0], area[1], orderW, area[3] };
    }

    private int[] suiteToolRect() {
        int[] order = suiteOrderRect();
        if (order == null) return null;
        int sideH = Math.max(44, (order[3] - 8) / 2);
        return new int[] { order[0] + order[2] + 8, order[1], 62, sideH };
    }

    private int[] suiteResourceRect() {
        int[] tool = suiteToolRect();
        if (tool == null) return null;
        int[] order = suiteOrderRect();
        if (order == null) return null;
        return new int[] { tool[0], tool[1] + tool[3] + 8, tool[2], order[3] - tool[3] - 8 };
    }

    private int suitePoolCols(int width) {
        return Math.max(1, (width - 10) / SLOT);
    }

    private int suitePoolRows(int height) {
        return Math.max(1, (height - 40) / SLOT);
    }

    private int suitePoolCapacity(int[] rect) {
        if (rect == null) return 1;
        return Math.max(1, suitePoolCols(rect[2]) * suitePoolRows(rect[3]));
    }

    private int suitePoolMaxPage(int stackCount, int capacity) {
        return Math.max(0, stackCount / Math.max(1, capacity));
    }

    private int suitePoolSlotAt(double mx, double my, boolean tools) {
        int[] rect = tools ? suiteToolRect() : suiteResourceRect();
        if (rect == null || !Theme.inside(mx, my, rect[0], rect[1], rect[2], rect[3])) return -1;
        int cols = suitePoolCols(rect[2]);
        int rows = suitePoolRows(rect[3]);
        int capacity = cols * rows;
        int page = tools ? suiteToolPage : suiteResourcePage;
        List<ItemStack> stacks = tools ? ClientTabletChestCache.suiteTools() : ClientTabletChestCache.suiteResources();
        page = clamp(page, 0, suitePoolMaxPage(stacks.size(), capacity));
        int gridX = rect[0] + (rect[2] - cols * SLOT) / 2 + 1;
        int gridY = rect[1] + 22;
        for (int i = 0; i < capacity; i++) {
            int sx = gridX + (i % cols) * SLOT;
            int sy = gridY + (i / cols) * SLOT;
            if (Theme.inside(mx, my, sx, sy, 16, 16)) return Math.min(page * capacity + i, stacks.size());
        }
        return -1;
    }

    private int suitePoolPageButtonAt(double mx, double my, boolean tools) {
        int[] rect = tools ? suiteToolRect() : suiteResourceRect();
        if (rect == null || !Theme.inside(mx, my, rect[0], rect[1], rect[2], rect[3])) return 0;
        int y = rect[1] + rect[3] - 17;
        if (Theme.inside(mx, my, rect[0] + 7, y, 14, 14)) return -1;
        if (Theme.inside(mx, my, rect[0] + rect[2] - 21, y, 14, 14)) return 1;
        return 0;
    }

    private int suiteScrollbarAt(double mx, double my) {
        int[] order = suiteOrderRect();
        if (order != null) {
            int listY = suiteOrderListY(order[1]);
            int rows = Math.max(1, (order[1] + order[3] - listY - 6) / 19);
            if (ClientTabletChestCache.suiteOrders().size() > rows
                    && Theme.inside(mx, my, order[0] + order[2] - 6, listY, 8, order[1] + order[3] - listY - 6)) {
                return 1;
            }
        }
        return 0;
    }

    private void updateSuiteScrollbarDrag(double my) {
        if (suiteScrollDrag == 1) {
            int[] rect = suiteOrderRect();
            if (rect == null) return;
            int listY = suiteOrderListY(rect[1]);
            int rows = Math.max(1, (rect[1] + rect[3] - listY - 6) / 19);
            int max = Math.max(0, ClientTabletChestCache.suiteOrders().size() - rows);
            suiteOrderScroll = scrollValueFromMouse(my, listY, rect[1] + rect[3] - listY - 6, max);
        }
    }

    private int scrollValueFromMouse(double my, int y, int h, int max) {
        if (max <= 0 || h <= 0) return 0;
        double ratio = (my - y) / Math.max(1.0, h);
        return clamp((int) Math.round(ratio * max), 0, max);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
