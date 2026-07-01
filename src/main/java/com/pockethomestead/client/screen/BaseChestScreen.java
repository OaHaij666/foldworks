package com.pockethomestead.client.screen;

import com.pockethomestead.client.ClientProductionStatsCache;
import com.pockethomestead.client.ClientTransferGraphCache;
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
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
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
public abstract class BaseChestScreen<T extends BaseChestMenu> extends AbstractContainerScreen<T> implements ChestScreenHost {
    /** 当前页：0=物品，1=流体(可选)，倒数第二=面配置，最后=设置 */
    private int currentPage = 0;

    private int localScrollRow = 0;
    private int settingsScroll = 0;
    private String lastSyncedChestEditId = "";

    private final ChestFaceConfigPanel faceConfigPanel = new ChestFaceConfigPanel(this);

    private int cacheMaxCapacity = 4096;
    private int cacheMaxFluidCapacityMb = 16000;
    private int cacheMaxFluidTypes = 1;
    private int cacheMaxFluidCapacityPerTypeMb = 16000;
    private int cacheEnergyStored = 0;
    private int cacheMaxEnergyStored = 0;
    private int cacheEnergyTransferLimit = 0;
    private int cacheNetworkBandwidth = 0;
    private int cacheStressBandwidthUsed = 0;
    private int cacheRemainingTransferBandwidth = 0;
    private boolean cacheStressUpgradeInstalled;
    private boolean cacheCreateLoaded;
    private int cacheStressOutputSpeedRpm;
    private boolean cacheStressOutputReversed;
    private String cacheChestId = "";
    private String cacheGraphKind = "PRIVATE";
    private String cacheGraphTeamId = "";
    private boolean cacheProductionStatsEnabled;
    private String cacheProductionGroupId = "";
    private boolean cacheOfflineSnapshotEnabled;
    private boolean cacheSpaceOfflineSimulationEnabled;
    private final int[] cacheUpgradeCounts = new int[BaseChestBlockEntity.UPGRADE_SLOT_COUNT];
    private final EnumMap<ResourceKind, EnumMap<RelativeSide, SideMode>> cacheSideConfig = new EnumMap<>(ResourceKind.class);

    // 客户端物品快照（按数量从多到少排序）
    private final List<ChestSyncPacket.ItemEntry> cacheItems = new ArrayList<>();
    private final List<Map.Entry<Fluid, Integer>> cacheFluids = new ArrayList<>();

    private UiButton pageButton;
    private UiButton graphButton;
    private EditBox chestIdEdit;
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
        this.cacheNetworkBandwidth = p.networkBandwidth();
        this.cacheStressBandwidthUsed = p.stressBandwidthUsed();
        this.cacheRemainingTransferBandwidth = p.remainingTransferBandwidth();
        this.cacheStressUpgradeInstalled = p.stressUpgradeInstalled();
        this.cacheCreateLoaded = p.createLoaded();
        this.cacheStressOutputSpeedRpm = p.stressOutputSpeedRpm();
        this.cacheStressOutputReversed = p.stressOutputReversed();
        this.cacheChestId = p.chestId();
        this.cacheGraphKind = p.graphKind();
        this.cacheGraphTeamId = p.graphTeamId();
        this.cacheOfflineSnapshotEnabled = p.offlineSnapshotEnabled();
        this.cacheSpaceOfflineSimulationEnabled = p.spaceOfflineSimulationEnabled();
        syncChestIdEdit(false);
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

    @Override public boolean hasFluidPage() {
        return BaseChestMenu.isCreateLoaded();
    }

    private int facePageIndex() {
        return hasFluidPage() ? 2 : 1;
    }

    private int settingsPageIndex() {
        return facePageIndex() + 1;
    }

    private boolean isItemPage() {
        return currentPage == 0;
    }

    private boolean isFluidPage() {
        return hasFluidPage() && currentPage == 1;
    }

    private boolean isFacePage() {
        return currentPage == facePageIndex();
    }

    private boolean isSettingsPage() {
        return currentPage == settingsPageIndex();
    }

    private void updatePageButtonLabel() {
        if (pageButton == null) return;
        if (isSettingsPage()) pageButton.label("◀ 物品");
        else if (hasFluidPage() && currentPage == 0) pageButton.label("流体 ▶");
        else if (isFluidPage() || (!hasFluidPage() && currentPage == 0)) pageButton.label("面 ▶");
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

        chestIdEdit = new EditBox(font, 0, 0, 88, 16, Component.literal("箱子标识"));
        chestIdEdit.setMaxLength(32);
        chestIdEdit.setEditable(true);
        chestIdEdit.visible = false;
        addRenderableWidget(chestIdEdit);
        syncChestIdEdit(true);

        PacketDistributor.sendToServer(new RequestProductionStatsPacket());
        rebuildPageWidgets();
    }

    /** 切换页面 */
    private void switchPage() {
        currentPage = (currentPage + 1) % (settingsPageIndex() + 1);
        if (!isSettingsPage()) settingsScroll = 0;
        rebuildPageWidgets();
    }

    /** 根据当前页重建控件（配置页才创建配置控件） */
    private void rebuildPageWidgets() {
        graphButton = null;
        statsGroupDropdownOpen = false;
        faceConfigPanel.reset();
        if (chestIdEdit != null) {
            chestIdEdit.visible = isSettingsPage();
            chestIdEdit.setFocused(false);
            syncChestIdEdit(true);
        }

        updatePageButtonLabel();

        if (isSettingsPage()) {
            PacketDistributor.sendToServer(new RequestProductionStatsPacket());
            int cardX = leftPos + BaseChestMenu.PANEL_PADDING;
            int cardY = topPos + BaseChestMenu.HEADER_HEIGHT + 8;
            int cardW = panelW - BaseChestMenu.PANEL_PADDING * 2;
            graphButton = new UiButton("打开", UiButton.Variant.PRIMARY)
                    .bounds(cardX + cardW - 58, cardY + 9, 48, 18)
                    .onClick(() -> Minecraft.getInstance().setScreen(new TransferGraphScreen(cacheGraphKind, cacheGraphTeamId)));
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

    private void syncChestIdEdit(boolean force) {
        if (chestIdEdit == null) return;
        if (!force && chestIdEdit.isFocused()) return;
        String value = cacheChestId == null ? "" : cacheChestId;
        if (force || !value.equals(lastSyncedChestEditId)) {
            chestIdEdit.setValue(value);
            lastSyncedChestEditId = value;
        }
    }

    private void submitChestRename() {
        if (chestIdEdit == null) return;
        String value = chestIdEdit.getValue().trim();
        if (value.isEmpty() || value.equals(cacheChestId)) return;
        send(2, value);
        cacheChestId = value;
        lastSyncedChestEditId = value;
        chestIdEdit.setFocused(false);
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
        } else if (isFacePage()) {
            faceConfigPanel.render(g, mx, my, partialTick);
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
        int cardW = panelW - BaseChestMenu.PANEL_PADDING * 2;
        int gap = 6;
        int graphH = 36;
        int viewportTop = settingsViewportTop();
        int viewportBottom = settingsViewportBottom();
        settingsScroll = Math.max(0, Math.min(maxSettingsScroll(), settingsScroll));
        int graphY = viewportTop + 4 - settingsScroll;

        g.enableScissor(leftPos + 1, viewportTop, leftPos + panelW - 1, viewportBottom);
        Theme.panel(g, cardX, graphY, cardW, graphH, Theme.RADIUS + 1, Theme.SURFACE_ALT, Theme.BORDER);
        Theme.text(g, font, "可视化节点", cardX + 10, graphY + 7, Theme.TEXT);
        Theme.text(g, font, "编辑连线", cardX + 10, graphY + 20, Theme.TEXT_MUTED);
        if (graphButton != null) graphButton.bounds(cardX + cardW - 58, graphY + 9, 48, 18).render(g, mx, my, partialTick);

        int renameY = graphY + graphH + gap;
        int renameH = 48;
        Theme.panel(g, cardX, renameY, cardW, renameH, Theme.RADIUS + 1, Theme.SURFACE_ALT, Theme.BORDER);
        Theme.text(g, font, "箱子标识", cardX + 10, renameY + 7, Theme.TEXT);
        layoutChestIdEdit(cardX, renameY, cardW);
        if (chestIdEdit != null) chestIdEdit.render(g, mx, my, partialTick);
        int saveX = renameSaveX(cardX, cardW);
        boolean changed = chestIdEdit != null && !chestIdEdit.getValue().trim().equals(cacheChestId);
        boolean saveHover = Theme.inside(mx, my, saveX, renameY + 25, 42, 18);
        Theme.panel(g, saveX, renameY + 25, 42, 18, 5, changed ? Theme.PRIMARY_SOFT : Theme.SURFACE_SUNK, saveHover && changed ? Theme.PRIMARY : Theme.BORDER);
        Theme.textCentered(g, font, "保存", saveX + 21, renameY + 31, changed ? Theme.PRIMARY_PRESS : Theme.TEXT_FAINT);

        int accessY = renameY + renameH + gap;
        int accessH = 38;

        Theme.panel(g, cardX, accessY, cardW, accessH, Theme.RADIUS + 1, Theme.SURFACE_ALT, Theme.BORDER);
        Theme.text(g, font, "箱子权限", cardX + 10, accessY + 7, Theme.TEXT);
        renderGraphTierButton(g, mx, my, graphTierButtonX(cardX, cardW, 0), accessY + 22, graphTierButtonW(cardW, 0), "私有", "PRIVATE");
        renderGraphTierButton(g, mx, my, graphTierButtonX(cardX, cardW, 1), accessY + 22, graphTierButtonW(cardW, 1), "团队", "PROTECTED");
        renderGraphTierButton(g, mx, my, graphTierButtonX(cardX, cardW, 2), accessY + 22, graphTierButtonW(cardW, 2), "公开", "PUBLIC");
        renderGraphTierButton(g, mx, my, graphTierButtonX(cardX, cardW, 3), accessY + 22, graphTierButtonW(cardW, 3), "空间", "SPACE");

        int offlineY = accessY + accessH + gap;
        int offlineH = 50;
        Theme.panel(g, cardX, offlineY, cardW, offlineH, Theme.RADIUS + 1, Theme.SURFACE_ALT, Theme.BORDER);
        Theme.text(g, font, "离线模拟（实验）", cardX + 10, offlineY + 7, Theme.TEXT);
        Theme.text(g, font, "箱子卸载后由快照顶替运行", cardX + 10, offlineY + 22,
                cacheOfflineSnapshotEnabled || cacheSpaceOfflineSimulationEnabled ? Theme.TEXT_MUTED : Theme.TEXT_FAINT);
        if (cacheSpaceOfflineSimulationEnabled && !cacheOfflineSnapshotEnabled) {
            Theme.textRight(g, font, "空间已启用", cardX + cardW - 52, offlineY + 9, Theme.PRIMARY_PRESS);
        }
        drawStatsSwitch(g, statsToggleX(cardX, cardW), offlineY + 18, cacheOfflineSnapshotEnabled);

        int cardY = offlineY + offlineH + gap;
        int cardH = Math.max(58, topPos + panelH - cardY - BaseChestMenu.PANEL_PADDING);

        Theme.panel(g, cardX, cardY, cardW, cardH, Theme.RADIUS + 1, Theme.SURFACE_ALT, Theme.BORDER);
        Theme.text(g, font, "产率统计", cardX + 10, cardY + 7, Theme.TEXT);
        Theme.text(g, font, "加入统计", cardX + 10, cardY + 22, cacheProductionStatsEnabled ? Theme.TEXT : Theme.TEXT_MUTED);
        drawStatsSwitch(g, statsToggleX(cardX, cardW), cardY + 18, cacheProductionStatsEnabled);

        Theme.text(g, font, "分组", cardX + 10, cardY + 43, Theme.TEXT_MUTED);
        drawGroupSelector(g, mx, my, groupSelectorX(cardX), cardY + 37, groupSelectorW(cardW), 18);
        if (statsGroupDropdownOpen) renderGroupDropdown(g, mx, my, groupSelectorX(cardX), cardY + 56, groupSelectorW(cardW));
        g.disableScissor();
        renderSettingsScrollbar(g);
    }

    private int settingsViewportTop() {
        return topPos + BaseChestMenu.HEADER_HEIGHT + 4;
    }

    private int settingsViewportBottom() {
        return topPos + panelH - BaseChestMenu.PANEL_PADDING;
    }

    private int settingsContentHeight() {
        return 4 + 36 + 6 + 48 + 6 + 38 + 6 + 50 + 6 + 72 + 8;
    }

    private int maxSettingsScroll() {
        return Math.max(0, settingsContentHeight() - Math.max(1, settingsViewportBottom() - settingsViewportTop()));
    }

    private void renderSettingsScrollbar(GuiGraphics g) {
        int max = maxSettingsScroll();
        if (max <= 0) return;
        int trackX = leftPos + panelW - BaseChestMenu.PANEL_PADDING - 4;
        int trackY = settingsViewportTop() + 4;
        int trackH = settingsViewportBottom() - settingsViewportTop() - 8;
        Theme.fillRound(g, trackX, trackY, 3, trackH, 2, Theme.SURFACE_SUNK);
        int thumbH = Math.max(14, trackH * (settingsViewportBottom() - settingsViewportTop()) / settingsContentHeight());
        int thumbY = trackY + (trackH - thumbH) * settingsScroll / max;
        Theme.fillRound(g, trackX, thumbY, 3, thumbH, 2, Theme.PRIMARY);
    }

    private void renderGraphTierButton(GuiGraphics g, int mx, int my, int x, int y, int w, String label, String kind) {
        boolean selected = kind.equals(cacheGraphKind);
        boolean enabled = !"PROTECTED".equals(kind) || firstVisibleTeamId() != null || !cacheGraphTeamId.isBlank();
        int fill = selected ? Theme.PRIMARY_SOFT : enabled ? Theme.SURFACE : Theme.SURFACE_SUNK;
        int border = Theme.inside(mx, my, x, y, w, 16) && enabled ? Theme.PRIMARY : selected ? Theme.PRIMARY : Theme.BORDER;
        Theme.panel(g, x, y, w, 16, 5, fill, border);
        Theme.textCentered(g, font, label, x + w / 2, y + 5, enabled ? selected ? Theme.PRIMARY_PRESS : Theme.TEXT : Theme.TEXT_FAINT);
    }

    private int graphTierButtonX(int cardX, int cardW, int index) {
        int gap = 4;
        int buttonW = graphTierButtonW(cardW, 0);
        return cardX + 10 + index * (buttonW + gap);
    }

    private int graphTierButtonW(int cardW, int index) {
        int gap = 4;
        int total = Math.max(72, cardW - 20);
        int base = Math.max(22, (total - gap * 3) / 4);
        return index == 3 ? Math.max(22, total - (base + gap) * 3) : base;
    }

    private String firstVisibleTeamId() {
        if ("PROTECTED".equals(cacheGraphKind) && cacheGraphTeamId != null && !cacheGraphTeamId.isBlank()) return cacheGraphTeamId;
        for (com.pockethomestead.network.TransferGraphSyncPacket.TeamData team : ClientTransferGraphCache.teams()) {
            if (team.id() != null && !team.id().isBlank()) return team.id();
        }
        return null;
    }

    private void layoutChestIdEdit(int cardX, int cardY, int cardW) {
        if (chestIdEdit == null) return;
        int editX = cardX + 10;
        int editW = Math.max(68, cardW - 62);
        chestIdEdit.setX(editX);
        chestIdEdit.setY(cardY + 25);
        chestIdEdit.setWidth(editW);
        chestIdEdit.visible = isSettingsPage();
    }

    private int renameSaveX(int cardX, int cardW) {
        return cardX + cardW - 48;
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
                lines.add(Component.literal("总带宽: " + cacheNetworkBandwidth + " / 周期").withStyle(ChatFormatting.DARK_GRAY));
                lines.add(Component.literal("应力占用: " + cacheStressBandwidthUsed + "，普通传输可用: " + cacheRemainingTransferBandwidth).withStyle(ChatFormatting.DARK_GRAY));
                lines.add(Component.literal("每个网络升级 +" + com.pockethomestead.config.ModConfig.NETWORK_BANDWIDTH_PER_UPGRADE.get() + " 带宽").withStyle(ChatFormatting.DARK_GRAY));
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
        if (isSettingsPage() && mx >= leftPos && mx < leftPos + panelW
                && my >= settingsViewportTop() && my < settingsViewportBottom()) {
            settingsScroll = Math.max(0, Math.min(maxSettingsScroll(), settingsScroll - (int) Math.signum(sy) * 18));
            return true;
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

        if (isFacePage()) {
            if (faceConfigPanel.mouseClicked(mx, my, button)) return true;
            return true;
        }

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

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (isFacePage() && faceConfigPanel.mouseReleased(mx, my, button)) return true;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (isFacePage() && faceConfigPanel.mouseDragged(mx, my, button, dx, dy)) return true;
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (isSettingsPage() && chestIdEdit != null && chestIdEdit.isFocused()) {
            if (keyCode == 257 || keyCode == 335) {
                submitChestRename();
                return true;
            }
            if (chestIdEdit.keyPressed(keyCode, scanCode, modifiers)) return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        if (isSettingsPage() && chestIdEdit != null && chestIdEdit.isFocused() && chestIdEdit.charTyped(c, modifiers)) return true;
        return super.charTyped(c, modifiers);
    }

    private boolean handleConfigPageClick(double mx, double my, int button) {
        int cardX = leftPos + BaseChestMenu.PANEL_PADDING;
        if (my < settingsViewportTop() || my >= settingsViewportBottom()) return true;
        settingsScroll = Math.max(0, Math.min(maxSettingsScroll(), settingsScroll));
        int graphY = settingsViewportTop() + 4 - settingsScroll;
        int renameY = graphY + 36 + 6;
        int accessY = renameY + 48 + 6;
        int offlineY = accessY + 38 + 6;
        int cardY = offlineY + 50 + 6;
        int cardW = panelW - BaseChestMenu.PANEL_PADDING * 2;
        int selectorX = groupSelectorX(cardX);
        int selectorY = cardY + 37;
        int selectorW = groupSelectorW(cardW);
        if (button != 0) return true;

        layoutChestIdEdit(cardX, renameY, cardW);
        if (chestIdEdit != null && chestIdEditHit(mx, my, cardX, renameY, cardW)) {
            chestIdEdit.visible = true;
            chestIdEdit.setFocused(true);
            setFocused(chestIdEdit);
            chestIdEdit.mouseClicked(mx, my, button);
            statsGroupDropdownOpen = false;
            return true;
        }
        int saveX = renameSaveX(cardX, cardW);
        if (Theme.inside(mx, my, saveX, renameY + 25, 42, 18)) {
            statsGroupDropdownOpen = false;
            submitChestRename();
            return true;
        }
        if (chestIdEdit != null) {
            chestIdEdit.setFocused(false);
            setFocused(null);
        }

        if (Theme.inside(mx, my, graphTierButtonX(cardX, cardW, 0), accessY + 22, graphTierButtonW(cardW, 0), 16)) {
            send(21, "PRIVATE|");
            cacheGraphKind = "PRIVATE";
            cacheGraphTeamId = "";
            return true;
        }
        if (Theme.inside(mx, my, graphTierButtonX(cardX, cardW, 1), accessY + 22, graphTierButtonW(cardW, 1), 16)) {
            String teamId = firstVisibleTeamId();
            if (teamId != null) {
                send(21, "PROTECTED|" + teamId);
                cacheGraphKind = "PROTECTED";
                cacheGraphTeamId = teamId;
            }
            return true;
        }
        if (Theme.inside(mx, my, graphTierButtonX(cardX, cardW, 2), accessY + 22, graphTierButtonW(cardW, 2), 16)) {
            send(21, "PUBLIC|");
            cacheGraphKind = "PUBLIC";
            cacheGraphTeamId = "";
            return true;
        }
        if (Theme.inside(mx, my, graphTierButtonX(cardX, cardW, 3), accessY + 22, graphTierButtonW(cardW, 3), 16)) {
            send(21, "SPACE|");
            cacheGraphKind = "SPACE";
            return true;
        }

        if (Theme.inside(mx, my, statsToggleX(cardX, cardW), offlineY + 18, 34, 14)) {
            statsGroupDropdownOpen = false;
            send(22, "");
            cacheOfflineSnapshotEnabled = !cacheOfflineSnapshotEnabled;
            return true;
        }

        if (statsGroupDropdownOpen) {
            List<com.pockethomestead.network.ProductionStatsSyncPacket.GroupData> groups = ClientProductionStatsCache.atomicGroups();
            int rowH = 17;
            int listY = cardY + 56;
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

        if (Theme.inside(mx, my, statsToggleX(cardX, cardW), cardY + 18, 34, 14)) {
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

    private boolean chestIdEditHit(double mx, double my, int cardX, int cardY, int cardW) {
        return Theme.inside(mx, my, cardX + 10, cardY + 25, Math.max(68, cardW - 62), 16);
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

    // ── ChestScreenHost 实现 ──

    @Override public Font font() { return font; }
    @Override public Minecraft minecraft() { return minecraft; }
    @Override public int leftPos() { return leftPos; }
    @Override public int topPos() { return topPos; }
    @Override public int panelWidth() { return panelW; }
    @Override public int panelHeight() { return panelH; }
    @Override public void sendConfig(int action, String value) { send(action, value); }
    @Override public EnumMap<ResourceKind, EnumMap<RelativeSide, SideMode>> sideConfigMap() { return cacheSideConfig; }
    @Override public int stressOutputSpeedRpm() { return cacheStressOutputSpeedRpm; }
    @Override public void stressOutputSpeedRpm(int rpm) { cacheStressOutputSpeedRpm = rpm; }
    @Override public boolean stressOutputReversed() { return cacheStressOutputReversed; }
    @Override public void stressOutputReversed(boolean reversed) { cacheStressOutputReversed = reversed; }
    @Override public boolean createLoaded() { return cacheCreateLoaded; }
}
