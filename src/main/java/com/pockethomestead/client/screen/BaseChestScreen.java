package com.pockethomestead.client.screen;

import com.pockethomestead.client.ClientProductionStatsCache;
import com.pockethomestead.client.ui.Theme;
import com.pockethomestead.client.ui.widget.UiButton;
import com.pockethomestead.blockentity.BaseChestBlockEntity;
import com.pockethomestead.blockentity.RelativeSide;
import com.pockethomestead.blockentity.ResourceKind;
import com.pockethomestead.blockentity.SideMode;
import com.pockethomestead.menu.BaseChestMenu;
import com.pockethomestead.network.ChestConfigPacket;
import com.pockethomestead.network.ChestSyncPacket;
import com.pockethomestead.network.RequestProductionStatsPacket;
import com.pockethomestead.network.UpdateProductionStatsPacket;
import com.pockethomestead.registration.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 箱子Screen — 蓝白主题，多页布局。
 *
 * 第0页（物品）：箱子区（1物品=1格，纯渲染）+ 玩家背包。
 * 第1页（流体）：流体容量与当前流体列表（仅流体兼容可用时显示）。
 * 最后一页（设置）：可视化节点编辑入口、统计设置、升级槽。
 *
 * 箱子区无真实槽位，存取全部通过网络包；服务端 itemStorage 为唯一权威，
 * 客户端通过 ChestSyncPacket 接收物品快照 cacheItems。
 */
public abstract class BaseChestScreen<T extends BaseChestMenu> extends AbstractContainerScreen<T> {

    /** 当前页：0=物品，1=流体(可选)，最后=设置 */
    private int currentPage = 0;

    private int localScrollRow = 0;

    private int cacheMaxCapacity = 4096;
    private int cacheMaxFluidCapacityMb = 16000;
    private int cacheMaxFluidTypes = 1;
    private int cacheMaxFluidCapacityPerTypeMb = 16000;
    private int cacheEnergyStored = 0;
    private int cacheMaxEnergyStored = 0;
    private int cacheEnergyTransferLimit = 0;
    private boolean cacheStressUpgradeInstalled;
    private boolean cacheCreateLoaded;
    private String cacheChestId = "";
    private boolean cacheProductionStatsEnabled;
    private String cacheProductionGroupId = "";
    private final int[] cacheUpgradeCounts = new int[BaseChestBlockEntity.UPGRADE_SLOT_COUNT];
    private final EnumMap<ResourceKind, EnumMap<RelativeSide, SideMode>> cacheSideConfig = new EnumMap<>(ResourceKind.class);

    // 客户端物品快照（按数量从多到少排序）
    private final List<ChestSyncPacket.ItemEntry> cacheItems = new ArrayList<>();
    private final List<Map.Entry<Fluid, Integer>> cacheFluids = new ArrayList<>();

    private UiButton pageButton;
    private UiButton graphButton;
    private boolean statsGroupDropdownOpen;

    // 悬停目标
    private ItemStack hoveredChestStack = ItemStack.EMPTY;
    private int hoveredChestCount = 0;
    private Fluid hoveredFluid = null;
    private int hoveredFluidAmount = 0;
    private int hoveredUpgradeSlot = -1;
    private Slot hoveredPlayerSlot = null;

    // 动态布局值
    private int panelW, panelH;
    private int slotStartX;
    private int chestAreaX, chestAreaY, chestAreaW, chestAreaH;
    private int upgradeAreaX, upgradeAreaY, upgradeAreaW;
    private int scrollbarX;
    private int playerLabelY, playerInvY, hotbarY;
    private int pageButtonX, pageButtonW;

    public BaseChestScreen(T menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth = BaseChestMenu.calculatePanelWidth();
        this.imageHeight = BaseChestMenu.calculatePanelHeight();
        this.titleLabelX = BaseChestMenu.PANEL_PADDING;
        this.titleLabelY = 4;
        this.inventoryLabelY = BaseChestMenu.calculatePlayerLabelY();
    }

    private void calculateLayout() {
        panelW = imageWidth;
        panelH = imageHeight;
        slotStartX = BaseChestMenu.calculateSlotStartX(panelW);

        chestAreaX = BaseChestMenu.PANEL_PADDING;
        chestAreaW = panelW - 2 * BaseChestMenu.PANEL_PADDING;
        chestAreaY = BaseChestMenu.calculateChestSlotStartY() - BaseChestMenu.BOX_PAD;
        chestAreaH = BaseChestMenu.CHEST_VISIBLE_ROWS * BaseChestMenu.SLOT_SIZE + 2 * BaseChestMenu.BOX_PAD;
        upgradeAreaX = BaseChestMenu.PANEL_PADDING;
        upgradeAreaY = BaseChestMenu.calculateChestSlotStartY() + BaseChestMenu.CHEST_VISIBLE_ROWS * BaseChestMenu.SLOT_SIZE + BaseChestMenu.SECTION_GAP;
        upgradeAreaW = panelW - 2 * BaseChestMenu.PANEL_PADDING;

        scrollbarX = chestAreaX + chestAreaW - BaseChestMenu.BOX_PAD - BaseChestMenu.SCROLLBAR_WIDTH;

        playerLabelY = BaseChestMenu.calculatePlayerLabelY();
        playerInvY = BaseChestMenu.calculatePlayerInvStartY();
        hotbarY = BaseChestMenu.calculateHotbarStartY();

        pageButtonW = 42;
        pageButtonX = panelW - BaseChestMenu.PANEL_PADDING - pageButtonW;
    }

    private int totalRows() {
        int types = cacheItems.size();
        return Math.max(BaseChestMenu.CHEST_VISIBLE_ROWS,
                (types + BaseChestMenu.CHEST_COLS - 1) / BaseChestMenu.CHEST_COLS);
    }

    // ── 配置同步 ──

    public void cacheConfig(ChestSyncPacket p) {
        this.cacheMaxCapacity = p.maxCapacity();
        this.cacheMaxFluidCapacityMb = p.maxFluidCapacityMb();
        this.cacheMaxFluidTypes = p.maxFluidTypes();
        this.cacheMaxFluidCapacityPerTypeMb = p.maxFluidCapacityPerTypeMb();
        this.cacheEnergyStored = p.energyStored();
        this.cacheMaxEnergyStored = p.maxEnergyStored();
        this.cacheEnergyTransferLimit = p.energyTransferLimit();
        this.cacheStressUpgradeInstalled = p.stressUpgradeInstalled();
        this.cacheCreateLoaded = p.createLoaded();
        this.cacheChestId = p.chestId();
        this.cacheProductionStatsEnabled = p.productionStatsEnabled();
        this.cacheProductionGroupId = p.productionGroupId();
        for (int i = 0; i < cacheUpgradeCounts.length; i++) {
            cacheUpgradeCounts[i] = i < p.upgradeCounts().size() ? p.upgradeCounts().get(i) : 0;
        }
        cacheSideConfig.clear();
        for (ChestSyncPacket.SideConfigEntry entry : p.sideConfig()) {
            try {
                ResourceKind kind = ResourceKind.valueOf(entry.kind());
                RelativeSide side = RelativeSide.valueOf(entry.side());
                SideMode mode = SideMode.valueOf(entry.mode());
                cacheSideConfig.computeIfAbsent(kind, k -> new EnumMap<>(RelativeSide.class)).put(side, mode);
            } catch (IllegalArgumentException ignored) {
            }
        }

        // 解析物品快照（按数量从多到少排序）
        cacheItems.clear();
        for (ChestSyncPacket.ItemEntry e : p.items()) {
            if (!e.stack().isEmpty() && e.count() > 0) cacheItems.add(new ChestSyncPacket.ItemEntry(e.stack().copyWithCount(1), e.count()));
        }
        cacheItems.sort(Comparator.<ChestSyncPacket.ItemEntry>comparingInt(ChestSyncPacket.ItemEntry::count).reversed());

        cacheFluids.clear();
        for (Map.Entry<String, Integer> e : p.fluids().entrySet()) {
            ResourceLocation loc = ResourceLocation.tryParse(e.getKey());
            if (loc != null) {
                Fluid fluid = BuiltInRegistries.FLUID.get(loc);
                if (fluid != Fluids.EMPTY) cacheFluids.add(Map.entry(fluid, e.getValue()));
            }
        }
        cacheFluids.sort(Comparator.<Map.Entry<Fluid, Integer>>comparingInt(Map.Entry::getValue).reversed());

        int maxRow = Math.max(0, totalRows() - BaseChestMenu.CHEST_VISIBLE_ROWS);
        if (localScrollRow > maxRow) localScrollRow = maxRow;

    }

    private void send(int action, String value) {
        if (Minecraft.getInstance().player != null)
            PacketDistributor.sendToServer(new ChestConfigPacket(action, value));
    }

    private void send(int action, String value, ItemStack stack) {
        if (Minecraft.getInstance().player != null)
            PacketDistributor.sendToServer(new ChestConfigPacket(action, value, stack == null ? ItemStack.EMPTY : stack.copyWithCount(1)));
    }

    private boolean hasFluidPage() {
        return BaseChestMenu.isCreateLoaded();
    }

    private int settingsPageIndex() {
        return hasFluidPage() ? 2 : 1;
    }

    private boolean isItemPage() {
        return currentPage == 0;
    }

    private boolean isFluidPage() {
        return hasFluidPage() && currentPage == 1;
    }

    private boolean isSettingsPage() {
        return currentPage == settingsPageIndex();
    }

    private void updatePageButtonLabel() {
        if (pageButton == null) return;
        if (isSettingsPage()) pageButton.label("◀ 物品");
        else if (hasFluidPage() && currentPage == 0) pageButton.label("流体 ▶");
        else pageButton.label("设置 ▶");
    }

    // ── init ──

    @Override
    protected void init() {
        super.init();
        calculateLayout();

        int gl = leftPos, gt = topPos;

        // 翻页按钮（头部右侧，两页都显示）
        pageButton = new UiButton("配置 ▶", UiButton.Variant.SECONDARY)
                .bounds(gl + pageButtonX, gt + 2, pageButtonW, 14)
                .onClick(this::switchPage);

        PacketDistributor.sendToServer(new RequestProductionStatsPacket());
        rebuildPageWidgets();
    }

    /** 切换页面 */
    private void switchPage() {
        currentPage = (currentPage + 1) % (settingsPageIndex() + 1);
        rebuildPageWidgets();
    }

    /** 根据当前页重建控件（配置页才创建配置控件） */
    private void rebuildPageWidgets() {
        graphButton = null;
        statsGroupDropdownOpen = false;

        updatePageButtonLabel();

        if (isSettingsPage()) {
            PacketDistributor.sendToServer(new RequestProductionStatsPacket());
            int cardX = leftPos + BaseChestMenu.PANEL_PADDING;
            int cardY = topPos + BaseChestMenu.HEADER_HEIGHT + 20;
            int cardW = panelW - BaseChestMenu.PANEL_PADDING * 2;
            graphButton = new UiButton("打开", UiButton.Variant.PRIMARY)
                    .bounds(cardX + cardW - 58, cardY + 11, 48, 18)
                    .onClick(() -> Minecraft.getInstance().setScreen(new TransferGraphScreen()));
        }
    }

    private String selectedProductionGroupId() {
        return cacheProductionGroupId == null || cacheProductionGroupId.isBlank()
                ? ClientProductionStatsCache.defaultGroupId()
                : cacheProductionGroupId;
    }

    private void toggleProductionStats() {
        String groupId = cacheProductionStatsEnabled ? "" : selectedProductionGroupId();
        PacketDistributor.sendToServer(new UpdateProductionStatsPacket("SET_CURRENT_CHEST_GROUP", List.of(groupId)));
    }

    // ── 渲染 ──

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mx, int my) {
        calculateLayout();
        int x = leftPos, y = topPos;

        g.fill(0, 0, width, height, Theme.SCRIM);
        Theme.shadow(g, x, y, panelW, panelH, Theme.RADIUS + 2);
        Theme.panel(g, x, y, panelW, panelH, Theme.RADIUS + 2, Theme.SURFACE, Theme.BORDER);
        Theme.hLine(g, x + 1, y + BaseChestMenu.HEADER_HEIGHT, panelW - 2, Theme.DIVIDER);

        if (isItemPage()) {
            // 存货区背景
            Theme.fillRound(g, x + chestAreaX, y + chestAreaY, chestAreaW, chestAreaH, Theme.RADIUS, Theme.SURFACE_SUNK);
            Theme.hLine(g, x + 1, y + playerLabelY - 2, panelW - 2, Theme.DIVIDER);
        }
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        calculateLayout();
        renderBg(g, partialTick, mx, my);

        hoveredUpgradeSlot = -1;
        if (isItemPage()) {
            renderChestSlots(g, mx, my);
            renderScrollbar(g);
            renderUpgradeSlots(g, mx, my, leftPos + upgradeAreaX, topPos + upgradeAreaY, upgradeAreaW, true);
            hoveredFluid = null;
            hoveredFluidAmount = 0;
            renderPlayerInventorySlots(g, mx, my);
        } else if (isFluidPage()) {
            renderFluidPage(g, mx, my);
        } else {
            renderConfigPage(g, mx, my, partialTick);
        }

        renderLabels(g);
        if (pageButton != null) pageButton.render(g, mx, my, partialTick);
        renderDraggedItem(g, mx, my);
        renderHoverTooltip(g, mx, my);
    }

    /** 渲染玩家背包槽位（真正的 Slot，索引 0..35） */
    private void renderPlayerInventorySlots(GuiGraphics g, int mx, int my) {
        hoveredPlayerSlot = null;
        g.pose().pushPose();
        g.pose().translate(leftPos, topPos, 0);

        for (Slot slot : menu.slots) {
            int sx = slot.x, sy = slot.y;
            boolean hovered = isInside(sx, sy, 16, 16, mx, my);
            if (hovered && slot.hasItem()) hoveredPlayerSlot = slot;

            g.fill(sx - 1, sy - 1, sx + 17, sy, 0x80000000);
            g.fill(sx - 1, sy + 16, sx + 17, sy + 17, 0x80000000);
            g.fill(sx - 1, sy, sx, sy + 16, 0x80000000);
            g.fill(sx + 16, sy, sx + 17, sy + 16, 0x80000000);
            g.fill(sx, sy, sx + 16, sy + 16, hovered ? 0x60FFFFFF : 0x40FFFFFF);

            ItemStack stack = slot.getItem();
            if (!stack.isEmpty()) {
                g.renderItem(stack, sx, sy);
                g.renderItemDecorations(font, stack, sx, sy, null);
            }
        }
        g.pose().popPose();
    }

    private boolean isInside(int sx, int sy, int w, int h, double mx, double my) {
        double rx = mx - leftPos, ry = my - topPos;
        return rx >= sx && rx < sx + w && ry >= sy && ry < sy + h;
    }

    /** 渲染存货区（纯渲染，1物品=1格，按滚动行切片） */
    private void renderChestSlots(GuiGraphics g, int mx, int my) {
        hoveredChestStack = ItemStack.EMPTY;
        hoveredChestCount = 0;

        int x = leftPos, y = topPos;
        int gridStartY = BaseChestMenu.calculateChestSlotStartY();
        int startIdx = localScrollRow * BaseChestMenu.CHEST_COLS;

        for (int row = 0; row < BaseChestMenu.CHEST_VISIBLE_ROWS; row++) {
            for (int col = 0; col < BaseChestMenu.CHEST_COLS; col++) {
                int viewIdx = row * BaseChestMenu.CHEST_COLS + col;
                int dataIdx = startIdx + viewIdx;

                int slotX = x + slotStartX + col * BaseChestMenu.SLOT_SIZE;
                int slotY = y + gridStartY + row * BaseChestMenu.SLOT_SIZE;

                boolean hovered = isInside(slotX - leftPos, slotY - topPos, 16, 16, mx, my);

                g.fill(slotX - 1, slotY - 1, slotX + 17, slotY, 0xA0000000);
                g.fill(slotX - 1, slotY + 16, slotX + 17, slotY + 17, 0xA0000000);
                g.fill(slotX - 1, slotY, slotX, slotY + 16, 0xA0000000);
                g.fill(slotX + 16, slotY, slotX + 17, slotY + 16, 0xA0000000);
                g.fill(slotX, slotY, slotX + 16, slotY + 16, hovered ? 0x50FFFFFF : 0x30FFFFFF);

                if (dataIdx >= 0 && dataIdx < cacheItems.size()) {
                    ChestSyncPacket.ItemEntry entry = cacheItems.get(dataIdx);
                    ItemStack stack = entry.stack();
                    int count = entry.count();

                    g.renderItem(stack, slotX, slotY);
                    renderCountText(g, BaseChestMenu.formatCount(count), slotX, slotY);

                    if (hovered) {
                        hoveredChestStack = stack.copyWithCount(1);
                        hoveredChestCount = count;
                    }
                }
            }
        }
    }

    /** 渲染数量文本（深色描边背景保证白色物品上也可读） */
    private void renderCountText(GuiGraphics g, String text, int slotX, int slotY) {
        int tw = font.width(text);
        int tx = slotX + 17 - tw;
        int ty = slotY + 16 - font.lineHeight + 1;

        g.pose().pushPose();
        g.pose().translate(0, 0, 200);
        g.fill(tx - 1, ty - 1, slotX + 17, slotY + 17, 0xB0000000);
        g.drawString(font, text, tx, ty, 0xFFFFFFFF, true);
        g.pose().popPose();
    }

    /** 流体页：显示当前储存的流体与 mB 数量 */
    private void renderFluidPage(GuiGraphics g, int mx, int my) {
        hoveredFluid = null;
        hoveredFluidAmount = 0;

        int x = leftPos + BaseChestMenu.PANEL_PADDING;
        int y = topPos + BaseChestMenu.HEADER_HEIGHT + 12;
        int w = panelW - BaseChestMenu.PANEL_PADDING * 2;
        int used = cacheFluids.stream().mapToInt(Map.Entry::getValue).sum();
        Theme.panel(g, x, y, w, panelH - BaseChestMenu.HEADER_HEIGHT - 20, Theme.RADIUS + 1, Theme.SURFACE_ALT, Theme.BORDER);
        Theme.text(g, font, "流体存储", x + 10, y + 10, Theme.TEXT);
        Theme.textRight(g, font, cacheFluids.size() + " / " + cacheMaxFluidTypes + " 种", x + w - 10, y + 10, Theme.TEXT_MUTED);
        Theme.text(g, font, BaseChestMenu.formatCount(used) + " / " + BaseChestMenu.formatCount(cacheMaxFluidCapacityMb) + " mB", x + 10, y + 25, Theme.TEXT_MUTED);

        int rowX = x + 10;
        int rowY = y + 46;
        if (cacheFluids.isEmpty()) {
            Theme.text(g, font, "空", rowX, rowY, Theme.TEXT_FAINT);
            return;
        }

        int maxRows = Math.min(cacheFluids.size(), Math.max(1, (panelH - BaseChestMenu.HEADER_HEIGHT - 76) / 24));
        for (int i = 0; i < maxRows; i++) {
            Map.Entry<Fluid, Integer> entry = cacheFluids.get(i);
            Fluid fluid = entry.getKey();
            int amount = entry.getValue();
            boolean hovered = mx >= rowX && mx < rowX + w - 20 && my >= rowY - 4 && my < rowY + 18;
            int color = 0xFF000000 | (BuiltInRegistries.FLUID.getKey(fluid).toString().hashCode() & 0x00FFFFFF);
            if (hovered) Theme.fillRound(g, rowX - 4, rowY - 5, w - 12, 20, 4, Theme.SURFACE);
            g.fill(rowX, rowY - 1, rowX + 10, rowY + 10, color);
            g.fill(rowX, rowY - 1, rowX + 10, rowY, 0xCCFFFFFF);
            String name = new FluidStack(fluid, 1).getHoverName().getString();
            Theme.text(g, font, Theme.ellipsize(font, name, 74), rowX + 16, rowY, hovered ? Theme.TEXT : Theme.TEXT_MUTED);
            Theme.textRight(g, font, BaseChestMenu.formatCount(amount) + " / " + BaseChestMenu.formatCount(cacheMaxFluidCapacityPerTypeMb) + " mB",
                    x + w - 10, rowY, hovered ? Theme.TEXT : Theme.TEXT_MUTED);
            if (hovered) {
                hoveredFluid = fluid;
                hoveredFluidAmount = amount;
            }
            rowY += 24;
        }

        if (cacheFluids.size() > maxRows) {
            Theme.text(g, font, "+" + (cacheFluids.size() - maxRows), rowX, rowY, Theme.TEXT_FAINT);
        }
    }

    /** 滚动条：仅当行数超过可视行时显示 */
    private void renderScrollbar(GuiGraphics g) {
        int total = totalRows();
        if (total <= BaseChestMenu.CHEST_VISIBLE_ROWS) return;

        int trackX = leftPos + scrollbarX;
        int trackY = topPos + chestAreaY + BaseChestMenu.BOX_PAD;
        int trackW = BaseChestMenu.SCROLLBAR_WIDTH;
        int trackH = chestAreaH - 2 * BaseChestMenu.BOX_PAD;

        Theme.fillRound(g, trackX, trackY, trackW, trackH, 2, Theme.SURFACE_SUNK);

        float visible = BaseChestMenu.CHEST_VISIBLE_ROWS;
        int thumbH = Math.max(12, (int) (trackH * (visible / total)));
        int maxRow = total - BaseChestMenu.CHEST_VISIBLE_ROWS;
        int thumbY = trackY + (maxRow <= 0 ? 0 : (int) ((trackH - thumbH) * ((float) localScrollRow / maxRow)));

        Theme.fillRound(g, trackX, thumbY, trackW, thumbH, 2, Theme.PRIMARY);
    }

    /** 配置页渲染 */
    private void renderConfigPage(GuiGraphics g, int mx, int my, float partialTick) {
        int cardX = leftPos + BaseChestMenu.PANEL_PADDING;
        int graphY = topPos + BaseChestMenu.HEADER_HEIGHT + 20;
        int cardW = panelW - BaseChestMenu.PANEL_PADDING * 2;
        int graphH = 40;

        Theme.panel(g, cardX, graphY, cardW, graphH, Theme.RADIUS + 1, Theme.SURFACE_ALT, Theme.BORDER);
        Theme.text(g, font, "可视化节点", cardX + 10, graphY + 9, Theme.TEXT);
        Theme.text(g, font, "编辑连线", cardX + 10, graphY + 23, Theme.TEXT_MUTED);
        if (graphButton != null) graphButton.render(g, mx, my, partialTick);

        int cardY = graphY + graphH + 10;
        int cardH = 78;

        Theme.panel(g, cardX, cardY, cardW, cardH, Theme.RADIUS + 1, Theme.SURFACE_ALT, Theme.BORDER);
        Theme.text(g, font, "产率统计", cardX + 10, cardY + 10, Theme.TEXT);
        Theme.text(g, font, "加入统计", cardX + 10, cardY + 31, cacheProductionStatsEnabled ? Theme.TEXT : Theme.TEXT_MUTED);
        drawStatsSwitch(g, statsToggleX(cardX, cardW), cardY + 27, cacheProductionStatsEnabled);

        Theme.text(g, font, "分组", cardX + 10, cardY + 55, Theme.TEXT_MUTED);
        drawGroupSelector(g, mx, my, groupSelectorX(cardX), cardY + 49, groupSelectorW(cardW), 18);
        if (statsGroupDropdownOpen) renderGroupDropdown(g, mx, my, groupSelectorX(cardX), cardY + 68, groupSelectorW(cardW));

        renderSideConfig(g, mx, my, cardX, cardY + cardH + 10, cardW);
    }

    private void renderSideConfig(GuiGraphics g, int mx, int my, int x, int y, int w) {
        int h = 100;
        Theme.panel(g, x, y, w, h, Theme.RADIUS + 1, Theme.SURFACE_ALT, Theme.BORDER);
        Theme.text(g, font, "面配置", x + 10, y + 8, Theme.TEXT);
        Theme.textRight(g, font, "相对面", x + w - 10, y + 8, Theme.TEXT_MUTED);

        int labelW = 36;
        int colW = Math.max(18, (w - labelW - 14) / RelativeSide.values().length);
        int headerY = y + 24;
        for (int i = 0; i < RelativeSide.values().length; i++) {
            RelativeSide side = RelativeSide.values()[i];
            Theme.textCentered(g, font, sideLabel(side), x + labelW + 7 + i * colW + colW / 2, headerY, Theme.TEXT_MUTED);
        }

        int rowY = y + 38;
        for (ResourceKind kind : ResourceKind.values()) {
            boolean editable = canEditSideKind(kind);
            Theme.text(g, font, kindLabel(kind), x + 8, rowY + 4, editable ? Theme.TEXT : Theme.TEXT_FAINT);
            for (int i = 0; i < RelativeSide.values().length; i++) {
                RelativeSide side = RelativeSide.values()[i];
                SideMode mode = cachedSideMode(kind, side);
                int cellX = x + labelW + 7 + i * colW;
                int fill = editable ? sideModeFill(mode) : Theme.SURFACE_SUNK;
                int border = Theme.inside(mx, my, cellX, rowY, colW - 2, 15) && editable ? Theme.PRIMARY : Theme.BORDER;
                Theme.panel(g, cellX, rowY, colW - 2, 15, 4, fill, border);
                Theme.textCentered(g, font, sideModeLabel(mode), cellX + (colW - 2) / 2, rowY + 4, editable ? sideModeText(mode) : Theme.TEXT_FAINT);
            }
            rowY += 15;
        }
    }

    private void renderUpgradeSlots(GuiGraphics g, int mx, int my, int x, int y, int w, boolean compact) {
        int h = compact ? BaseChestMenu.UPGRADE_SECTION_HEIGHT : 48;
        Theme.panel(g, x, y, w, h, Theme.RADIUS + 1, Theme.SURFACE_ALT, Theme.BORDER);
        Theme.text(g, font, compact ? "升级" : "升级模块", x + 8, y + (compact ? 8 : 8), Theme.TEXT_MUTED);
        int startX = compact ? x + 42 : x + w - 6 * 20 - 10;
        int slotY = compact ? y + 3 : y + 22;
        hoveredUpgradeSlot = -1;
        for (int i = 0; i < BaseChestBlockEntity.UPGRADE_SLOT_COUNT; i++) {
            int sx = startX + i * 20;
            ItemStack stack = upgradeStack(i);
            boolean enabled = !stack.isEmpty();
            boolean hovered = Theme.inside(mx, my, sx, slotY, 18, 18);
            if (hovered) hoveredUpgradeSlot = i;
            int fill = enabled
                    ? (hovered ? Theme.PRIMARY_SOFT_H : Theme.SURFACE_SUNK)
                    : (hovered ? Theme.SURFACE_ALT : Theme.SURFACE);
            int border = hovered ? Theme.PRIMARY : Theme.BORDER;
            Theme.panel(g, sx, slotY, 18, 18, 4, fill, border);
            int count = i < cacheUpgradeCounts.length ? cacheUpgradeCounts[i] : 0;
            if (!stack.isEmpty()) {
                if (count > 0) {
                    g.renderItem(stack, sx + 1, slotY + 1);
                    renderCountText(g, BaseChestMenu.formatCount(count), sx + 1, slotY + 1);
                } else {
                    Theme.textCentered(g, font, upgradeSlotMark(i), sx + 9, slotY + 5, enabled ? Theme.TEXT_FAINT : Theme.TEXT_FAINT);
                }
            } else {
                Theme.textCentered(g, font, "·", sx + 9, slotY + 5, Theme.TEXT_FAINT);
            }
        }
    }

    private ItemStack upgradeStack(int slot) {
        return switch (slot) {
            case BaseChestBlockEntity.STORAGE_UPGRADE_SLOT -> new ItemStack(ModItems.STORAGE_UPGRADE.get());
            case BaseChestBlockEntity.FLUID_UPGRADE_SLOT -> new ItemStack(ModItems.FLUID_UPGRADE.get());
            case BaseChestBlockEntity.NETWORK_UPGRADE_SLOT -> new ItemStack(ModItems.NETWORK_UPGRADE.get());
            case BaseChestBlockEntity.ENERGY_UPGRADE_SLOT -> new ItemStack(ModItems.ENERGY_TRANSFER_UPGRADE.get());
            case BaseChestBlockEntity.STRESS_UPGRADE_SLOT -> new ItemStack(ModItems.STRESS_UPGRADE.get());
            default -> ItemStack.EMPTY;
        };
    }

    private String upgradeSlotName(int slot) {
        return switch (slot) {
            case BaseChestBlockEntity.STORAGE_UPGRADE_SLOT -> "存储升级";
            case BaseChestBlockEntity.FLUID_UPGRADE_SLOT -> "流体升级";
            case BaseChestBlockEntity.NETWORK_UPGRADE_SLOT -> "网络升级";
            case BaseChestBlockEntity.ENERGY_UPGRADE_SLOT -> "电力传输升级";
            case BaseChestBlockEntity.STRESS_UPGRADE_SLOT -> "应力升级";
            default -> "预留";
        };
    }

    private String upgradeSlotMark(int slot) {
        return switch (slot) {
            case BaseChestBlockEntity.STORAGE_UPGRADE_SLOT -> "物";
            case BaseChestBlockEntity.FLUID_UPGRADE_SLOT -> "液";
            case BaseChestBlockEntity.NETWORK_UPGRADE_SLOT -> "网";
            case BaseChestBlockEntity.ENERGY_UPGRADE_SLOT -> "电";
            case BaseChestBlockEntity.STRESS_UPGRADE_SLOT -> "力";
            default -> "·";
        };
    }

    private void drawStatsSwitch(GuiGraphics g, int x, int y, boolean enabled) {
        int fill = enabled ? Theme.PRIMARY_SOFT : Theme.SURFACE_SUNK;
        int border = enabled ? Theme.PRIMARY : Theme.BORDER_STRONG;
        Theme.panel(g, x, y, 34, 14, 7, fill, border);
        Theme.fillRound(g, x + (enabled ? 19 : 3), y + 3, 9, 8, 4, enabled ? Theme.PRIMARY_PRESS : Theme.TEXT_FAINT);
    }

    private void drawGroupSelector(GuiGraphics g, int mx, int my, int x, int y, int w, int h) {
        boolean hover = Theme.inside(mx, my, x, y, w, h);
        var group = ClientProductionStatsCache.group(selectedProductionGroupId());
        String label = group == null ? "默认" : group.name();
        int fill = cacheProductionStatsEnabled ? (hover ? 0xFFFFFFFF : Theme.SURFACE) : Theme.SURFACE_SUNK;
        Theme.panel(g, x, y, w, h, 5, fill, hover ? Theme.PRIMARY : Theme.BORDER);
        Theme.text(g, font, Theme.ellipsize(font, label, w - 22), x + 7, y + 5, cacheProductionStatsEnabled ? Theme.TEXT : Theme.TEXT_MUTED);
        if (statsGroupDropdownOpen) Theme.chevronUp(g, x + w - 11, y + h / 2, 7, Theme.TEXT_MUTED);
        else Theme.chevronDown(g, x + w - 11, y + h / 2, 7, Theme.TEXT_MUTED);
    }

    private void renderGroupDropdown(GuiGraphics g, int mx, int my, int x, int y, int w) {
        List<com.pockethomestead.network.ProductionStatsSyncPacket.GroupData> groups = ClientProductionStatsCache.atomicGroups();
        int rowH = 17;
        int visible = Math.min(6, Math.max(1, groups.size()));
        int h = visible * rowH + 6;
        Theme.shadow(g, x, y, w, h, Theme.RADIUS + 1);
        Theme.panel(g, x, y, w, h, Theme.RADIUS + 1, Theme.SURFACE, Theme.BORDER_STRONG);
        if (groups.isEmpty()) {
            Theme.text(g, font, "暂无分组", x + 7, y + 7, Theme.TEXT_FAINT);
            return;
        }
        int rowY = y + 3;
        String current = selectedProductionGroupId();
        for (int i = 0; i < visible; i++) {
            var group = groups.get(i);
            boolean selected = group.id().equals(current);
            boolean hover = Theme.inside(mx, my, x + 3, rowY, w - 6, rowH);
            if (selected || hover) Theme.fillRound(g, x + 3, rowY, w - 6, rowH, 4, selected ? Theme.PRIMARY_SOFT : Theme.SURFACE_ALT);
            Theme.text(g, font, Theme.ellipsize(font, group.name(), w - 18), x + 8, rowY + 5, selected ? Theme.PRIMARY_PRESS : Theme.TEXT);
            rowY += rowH;
        }
    }

    private int statsToggleX(int cardX, int cardW) { return cardX + cardW - 44; }
    private int groupSelectorX(int cardX) { return cardX + 46; }
    private int groupSelectorW(int cardW) { return Math.max(74, cardW - 56); }

    /** 标题与分区标签 */
    private void renderLabels(GuiGraphics g) {
        int x = leftPos, y = topPos;
        Theme.text(g, font, title.getString(), x + titleLabelX, y + titleLabelY, Theme.TEXT);

        if (isItemPage()) {
            // 容量信息（头部右侧，翻页按钮左侧）— 用服务端同步的 maxCapacity
            int used = cacheItems.stream().mapToInt(ChestSyncPacket.ItemEntry::count).sum();
            int max = cacheMaxCapacity;
            String cap = BaseChestMenu.formatCount(used) + " / " + BaseChestMenu.formatCount(max);
            Theme.textRight(g, font, cap, x + pageButtonX - 6, y + titleLabelY, Theme.TEXT_MUTED);

            Theme.text(g, font, playerInventoryTitle.getString(), x + BaseChestMenu.PANEL_PADDING, y + playerLabelY - 2, Theme.TEXT_MUTED);
        }
    }

    /** 渲染光标上的物品（替代被跳过的 super.render） */
    private void renderDraggedItem(GuiGraphics g, int mx, int my) {
        ItemStack carried = menu.getCarried();
        if (carried.isEmpty()) return;
        g.pose().pushPose();
        g.pose().translate(0, 0, 300);
        g.renderItem(carried, mx - 8, my - 8);
        g.renderItemDecorations(font, carried, mx - 8, my - 8, null);
        g.pose().popPose();
    }

    /** 悬停提示：玩家槽 vanilla 物品，或存货区聚合物品 */
    private void renderHoverTooltip(GuiGraphics g, int mx, int my) {
        if (!menu.getCarried().isEmpty()) return;
        if (isItemPage() && hoveredUpgradeSlot >= 0) {
            ItemStack stack = upgradeStack(hoveredUpgradeSlot);
            int count = hoveredUpgradeSlot < cacheUpgradeCounts.length ? cacheUpgradeCounts[hoveredUpgradeSlot] : 0;
            List<Component> lines = new ArrayList<>();
            lines.add(stack.isEmpty() ? Component.literal(upgradeSlotName(hoveredUpgradeSlot)) : stack.getHoverName());
            lines.add(Component.literal("数量: " + count).withStyle(ChatFormatting.GRAY));
            if (hoveredUpgradeSlot == BaseChestBlockEntity.STORAGE_UPGRADE_SLOT) {
                lines.add(Component.literal("每个 +" + com.pockethomestead.config.ModConfig.STORAGE_UPGRADE_CAPACITY.get() + " 物品容量").withStyle(ChatFormatting.DARK_GRAY));
            } else if (hoveredUpgradeSlot == BaseChestBlockEntity.FLUID_UPGRADE_SLOT) {
                lines.add(Component.literal("每个 +" + com.pockethomestead.config.ModConfig.FLUID_UPGRADE_TYPES.get() + " 种流体，单种 +" + com.pockethomestead.config.ModConfig.FLUID_UPGRADE_CAPACITY_MB.get() + " mB").withStyle(ChatFormatting.DARK_GRAY));
            } else if (hoveredUpgradeSlot == BaseChestBlockEntity.NETWORK_UPGRADE_SLOT) {
                lines.add(Component.literal("放入后可在可视化传输中使用此箱子节点").withStyle(ChatFormatting.DARK_GRAY));
            } else if (hoveredUpgradeSlot == BaseChestBlockEntity.ENERGY_UPGRADE_SLOT) {
                lines.add(Component.literal("电力: " + cacheEnergyStored + " / " + cacheMaxEnergyStored + " FE，单次 " + cacheEnergyTransferLimit + " FE").withStyle(ChatFormatting.DARK_GRAY));
            } else if (hoveredUpgradeSlot == BaseChestBlockEntity.STRESS_UPGRADE_SLOT) {
                lines.add(Component.literal(cacheCreateLoaded ? "启用 Create 应力端口" : "Create 未安装时不生效").withStyle(ChatFormatting.DARK_GRAY));
            }
            if (!stack.isEmpty()) lines.add(Component.literal("左键放入/取一组 · 右键放入/取一个").withStyle(ChatFormatting.DARK_GRAY));
            g.renderComponentTooltip(font, lines, mx, my);
        } else if (hoveredFluid != null) {
            FluidStack stack = new FluidStack(hoveredFluid, hoveredFluidAmount);
            List<Component> lines = new ArrayList<>();
            lines.add(stack.getHoverName());
            lines.add(Component.literal(hoveredFluidAmount + " / " + cacheMaxFluidCapacityPerTypeMb + " mB").withStyle(ChatFormatting.GRAY));
            g.renderComponentTooltip(font, lines, mx, my);
        } else if (isItemPage() && hoveredPlayerSlot != null && hoveredPlayerSlot.hasItem()) {
            g.renderTooltip(font, hoveredPlayerSlot.getItem(), mx, my);
        } else if (isItemPage() && !hoveredChestStack.isEmpty()) {
            ItemStack stack = hoveredChestStack.copyWithCount(1);
            List<Component> lines = new ArrayList<>();
            lines.add(stack.getHoverName());
            lines.add(Component.literal("数量: " + hoveredChestCount).withStyle(ChatFormatting.GRAY));
            lines.add(Component.literal("左键取一组 · 右键取一个 · Shift入背包").withStyle(ChatFormatting.DARK_GRAY));
            g.renderComponentTooltip(font, lines, mx, my);
        }
    }

    // ── 输入 ──

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        // 内容页：存货区滚动（纯客户端）
        if (isItemPage()) {
            int total = totalRows();
            if (total > BaseChestMenu.CHEST_VISIBLE_ROWS && isInChestArea(mx, my)) {
                int maxRow = total - BaseChestMenu.CHEST_VISIBLE_ROWS;
                localScrollRow = Math.max(0, Math.min(maxRow, localScrollRow - (int) Math.signum(sy)));
                return true;
            }
        }
        return super.mouseScrolled(mx, my, sx, sy);
    }

    private boolean isInChestArea(double mx, double my) {
        double rx = mx - leftPos, ry = my - topPos;
        return rx >= chestAreaX && rx < chestAreaX + chestAreaW
                && ry >= chestAreaY && ry < chestAreaY + chestAreaH;
    }

    /** 命中存货区某格 → 返回 cacheItems 索引，未命中返回 -1 */
    private int chestSlotAt(double mx, double my) {
        int gridStartY = BaseChestMenu.calculateChestSlotStartY();
        for (int row = 0; row < BaseChestMenu.CHEST_VISIBLE_ROWS; row++) {
            for (int col = 0; col < BaseChestMenu.CHEST_COLS; col++) {
                int slotX = slotStartX + col * BaseChestMenu.SLOT_SIZE;
                int slotY = gridStartY + row * BaseChestMenu.SLOT_SIZE;
                double rx = mx - leftPos, ry = my - topPos;
                if (rx >= slotX && rx < slotX + 16 && ry >= slotY && ry < slotY + 16) {
                    int dataIdx = (localScrollRow + row) * BaseChestMenu.CHEST_COLS + col;
                    return dataIdx;
                }
            }
        }
        return -1;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        // 翻页按钮
        if (pageButton != null && pageButton.mouseClicked(mx, my, button)) return true;

        if (isSettingsPage()) {
            if (graphButton != null && graphButton.mouseClicked(mx, my, button)) return true;
            if (handleConfigPageClick(mx, my, button)) return true;
            return true;
        }
        if (isFluidPage()) return true;

        if (handleUpgradeSlotClick(mx, my, button)) return true;

        // 物品页：存货区交互
        if (isInChestArea(mx, my)) {
            int idx = chestSlotAt(mx, my);
            boolean shift = hasShiftDown();
            boolean carrying = !menu.getCarried().isEmpty();

            if (carrying) {
                send(button == 1 ? 12 : 8, "");   // 右键放一个，左键放全部
            } else if (idx >= 0 && idx < cacheItems.size()) {
                ItemStack stack = cacheItems.get(idx).stack().copyWithCount(1);
                String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                if (shift) {
                    send(11, itemId, stack);      // Shift → 取一组到背包
                } else if (button == 1) {
                    send(10, itemId, stack);      // 右键 → 取一个到手持
                } else {
                    send(9, itemId, stack);       // 左键 → 取一组到手持
                }
            }
            return true;
        }

        return super.mouseClicked(mx, my, button);
    }

    private boolean handleConfigPageClick(double mx, double my, int button) {
        int cardX = leftPos + BaseChestMenu.PANEL_PADDING;
        int cardY = topPos + BaseChestMenu.HEADER_HEIGHT + 70;
        int cardW = panelW - BaseChestMenu.PANEL_PADDING * 2;
        int selectorX = groupSelectorX(cardX);
        int selectorY = cardY + 49;
        int selectorW = groupSelectorW(cardW);
        if (button != 0) return true;

        if (handleSideConfigClick(mx, my, cardX, cardY + 78 + 10, cardW)) {
            statsGroupDropdownOpen = false;
            return true;
        }

        if (statsGroupDropdownOpen) {
            List<com.pockethomestead.network.ProductionStatsSyncPacket.GroupData> groups = ClientProductionStatsCache.atomicGroups();
            int rowH = 17;
            int listY = cardY + 68;
            int visible = Math.min(6, groups.size());
            for (int i = 0; i < visible; i++) {
                int rowY = listY + 3 + i * rowH;
                if (Theme.inside(mx, my, selectorX + 3, rowY, selectorW - 6, rowH)) {
                    PacketDistributor.sendToServer(new UpdateProductionStatsPacket("SET_CURRENT_CHEST_GROUP", List.of(groups.get(i).id())));
                    statsGroupDropdownOpen = false;
                    return true;
                }
            }
            if (!Theme.inside(mx, my, selectorX, listY, selectorW, visible * rowH + 6)) statsGroupDropdownOpen = false;
        }

        if (Theme.inside(mx, my, statsToggleX(cardX, cardW), cardY + 27, 34, 14)) {
            statsGroupDropdownOpen = false;
            toggleProductionStats();
            return true;
        }
        if (Theme.inside(mx, my, selectorX, selectorY, selectorW, 18)) {
            statsGroupDropdownOpen = !statsGroupDropdownOpen;
            if (ClientProductionStatsCache.atomicGroups().isEmpty()) PacketDistributor.sendToServer(new RequestProductionStatsPacket());
            return true;
        }
        return true;
    }

    private boolean handleSideConfigClick(double mx, double my, int x, int y, int w) {
        int labelW = 36;
        int colW = Math.max(18, (w - labelW - 14) / RelativeSide.values().length);
        int rowY = y + 38;
        for (ResourceKind kind : ResourceKind.values()) {
            boolean editable = canEditSideKind(kind);
            for (int i = 0; i < RelativeSide.values().length; i++) {
                int cellX = x + labelW + 7 + i * colW;
                if (Theme.inside(mx, my, cellX, rowY, colW - 2, 15)) {
                    if (!editable) return true;
                    RelativeSide side = RelativeSide.values()[i];
                    SideMode next = nextSideMode(kind, cachedSideMode(kind, side));
                    cacheSideConfig.computeIfAbsent(kind, k -> new EnumMap<>(RelativeSide.class)).put(side, next);
                    send(18, kind.name() + "|" + side.name() + "|" + next.name());
                    return true;
                }
            }
            rowY += 15;
        }
        return false;
    }

    private boolean canEditSideKind(ResourceKind kind) {
        if (kind == ResourceKind.STRESS) return cacheCreateLoaded;
        return true;
    }

    private SideMode cachedSideMode(ResourceKind kind, RelativeSide side) {
        EnumMap<RelativeSide, SideMode> modes = cacheSideConfig.get(kind);
        if (modes == null) return SideMode.DISABLED;
        return modes.getOrDefault(side, SideMode.DISABLED);
    }

    private SideMode nextSideMode(ResourceKind kind, SideMode mode) {
        if (kind == ResourceKind.STRESS) {
            return switch (mode) {
                case DISABLED -> SideMode.INPUT;
                case INPUT -> SideMode.OUTPUT;
                case OUTPUT, BOTH -> SideMode.DISABLED;
            };
        }
        return switch (mode) {
            case DISABLED -> SideMode.INPUT;
            case INPUT -> SideMode.OUTPUT;
            case OUTPUT -> SideMode.BOTH;
            case BOTH -> SideMode.DISABLED;
        };
    }

    private String kindLabel(ResourceKind kind) {
        return switch (kind) {
            case ITEM -> "物品";
            case FLUID -> "流体";
            case ENERGY -> "电力";
            case STRESS -> "应力";
        };
    }

    private String sideLabel(RelativeSide side) {
        return switch (side) {
            case FRONT -> "前";
            case BACK -> "后";
            case LEFT -> "左";
            case RIGHT -> "右";
            case UP -> "上";
            case DOWN -> "下";
        };
    }

    private String sideModeLabel(SideMode mode) {
        return switch (mode) {
            case DISABLED -> "关";
            case INPUT -> "入";
            case OUTPUT -> "出";
            case BOTH -> "双";
        };
    }

    private int sideModeFill(SideMode mode) {
        return switch (mode) {
            case DISABLED -> Theme.SURFACE;
            case INPUT -> 0xFFE8F5EE;
            case OUTPUT -> 0xFFEAF2FF;
            case BOTH -> 0xFFFFF5DA;
        };
    }

    private int sideModeText(SideMode mode) {
        return switch (mode) {
            case DISABLED -> Theme.TEXT_FAINT;
            case INPUT -> Theme.SUCCESS;
            case OUTPUT -> Theme.PRIMARY_PRESS;
            case BOTH -> 0xFF986A00;
        };
    }

    private boolean handleUpgradeSlotClick(double mx, double my, int button) {
        if (button != 0 && button != 1) return false;
        int upgradeStartX = leftPos + upgradeAreaX + 42;
        int upgradeY = topPos + upgradeAreaY + 3;
        for (int i = 0; i < BaseChestBlockEntity.UPGRADE_SLOT_COUNT; i++) {
            int sx = upgradeStartX + i * 20;
            if (Theme.inside(mx, my, sx, upgradeY, 18, 18)) {
                if (upgradeStack(i).isEmpty()) return false;
                boolean carrying = !menu.getCarried().isEmpty();
                if (carrying) send(button == 1 ? 15 : 14, String.valueOf(i));
                else if (i < cacheUpgradeCounts.length && cacheUpgradeCounts[i] > 0) send(button == 1 ? 17 : 16, String.valueOf(i));
                statsGroupDropdownOpen = false;
                return true;
            }
        }
        return false;
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mx, int my) {
        // 标签由自定义 renderLabels(g) 处理，禁用 vanilla 标签
    }
}
