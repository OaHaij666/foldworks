package com.pockethomestead.client.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
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
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.GameRenderer;
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
import org.joml.Matrix4f;

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
    private static final ResourceLocation CHEST_FACE_SIDE_TEXTURE = ResourceLocation.fromNamespaceAndPath("pockethomestead", "textures/block/chest_face_side.png");
    private static final ResourceLocation CHEST_FACE_CAP_TEXTURE = ResourceLocation.fromNamespaceAndPath("pockethomestead", "textures/block/chest_face_cap.png");
    private static final ResourceLocation CHEST_ITEM_PORT_TEXTURE = ResourceLocation.fromNamespaceAndPath("pockethomestead", "textures/block/chest_item_port.png");
    private static final ResourceLocation CHEST_FLUID_WINDOW_TEXTURE = ResourceLocation.fromNamespaceAndPath("pockethomestead", "textures/block/chest_fluid_window.png");
    private static final ResourceLocation CHEST_ENERGY_CORE_TEXTURE = ResourceLocation.fromNamespaceAndPath("pockethomestead", "textures/block/chest_energy_core.png");
    private static final ResourceLocation CHEST_BEARING_RING_TEXTURE = ResourceLocation.fromNamespaceAndPath("pockethomestead", "textures/block/chest_bearing_ring.png");
    private static final ResourceLocation CHEST_BEARING_SHAFT_TEXTURE = ResourceLocation.fromNamespaceAndPath("pockethomestead", "textures/block/chest_bearing_shaft.png");
    private static final int FACE_CUBE_HEIGHT = 116;
    private static final int FACE_CUBE_CENTER_Y_OFFSET = 65;
    private static final int FACE_CUBE_SCALE = 38;
    private static final int[] STRESS_SPEED_OPTIONS = {0, 16, 32, 64, 128, 256};

    /** 当前页：0=物品，1=流体(可选)，倒数第二=面配置，最后=设置 */
    private int currentPage = 0;

    private int localScrollRow = 0;
    private int settingsScroll = 0;
    private RelativeSide selectedSide = RelativeSide.FRONT;
    private double faceYaw = -34.0;
    private double facePitch = 24.0;
    private boolean rotatingFaceCube;
    private boolean faceCubeMoved;
    private int faceCubeDragX;
    private int faceCubeDragY;
    private boolean faceKindDropdownOpen;
    private String lastSyncedChestEditId = "";

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

    private boolean hasFluidPage() {
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
        faceKindDropdownOpen = false;
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
            renderFacePage(g, mx, my, partialTick);
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

    private void renderFacePage(GuiGraphics g, int mx, int my, float partialTick) {
        int cardX = leftPos + BaseChestMenu.PANEL_PADDING;
        int cardW = panelW - BaseChestMenu.PANEL_PADDING * 2;
        int cubeY = topPos + BaseChestMenu.HEADER_HEIGHT + 10;
        int cubeH = FACE_CUBE_HEIGHT;
        Theme.panel(g, cardX, cubeY, cardW, cubeH, Theme.RADIUS + 1, Theme.SURFACE_ALT, Theme.BORDER);
        Theme.text(g, font, "面配置", cardX + 10, cubeY + 8, Theme.TEXT);
        Theme.textRight(g, font, sideLabel(selectedSide), cardX + cardW - 10, cubeY + 8, Theme.PRIMARY_PRESS);
        renderFaceCube(g, mx, my, cardX + cardW / 2, cubeY + FACE_CUBE_CENTER_Y_OFFSET, FACE_CUBE_SCALE);

        int controlsY = cubeY + cubeH + 8;
        int controlsH = Math.max(72, topPos + panelH - controlsY - BaseChestMenu.PANEL_PADDING);
        Theme.panel(g, cardX, controlsY, cardW, controlsH, Theme.RADIUS + 1, Theme.SURFACE_ALT, Theme.BORDER);
        Theme.text(g, font, sideLabel(selectedSide) + "面", cardX + 10, controlsY + 8, Theme.TEXT);
        renderFaceControls(g, mx, my, cardX, controlsY, cardW);
    }

    private void renderFaceControls(GuiGraphics g, int mx, int my, int x, int y, int w) {
        ResourceKind activeKind = activeSideKind(selectedSide);
        int functionX = x + 9;
        int functionY = y + 28;
        int functionW = 50;
        int settingsX = functionX + functionW + 9;
        int settingsW = Math.max(90, x + w - settingsX - 9);

        Theme.vLine(g, functionX + functionW + 4, y + 19, Math.max(54, topPos + panelH - y - BaseChestMenu.PANEL_PADDING - 26), Theme.DIVIDER);
        Theme.text(g, font, "功能", functionX, y + 17, Theme.TEXT_MUTED);
        renderFaceFunctionSelector(g, mx, my, functionX, functionY, functionW, activeKind);

        Theme.text(g, font, kindLabel(activeKind) + "模式", settingsX, y + 17, Theme.TEXT_MUTED);
        int bx = settingsX;
        int by = y + 28;
        for (SideMode mode : sideModesFor(activeKind)) {
            int bw = 21;
            renderFaceModeButton(g, mx, my, bx, by, bw, activeKind, mode);
            bx += bw + 3;
        }
        if (activeKind == ResourceKind.STRESS && cachedSideMode(activeKind, selectedSide) == SideMode.OUTPUT) {
            renderStressOutputControls(g, mx, my, settingsX, y + 52, settingsW);
        }
    }

    private void renderFaceFunctionSelector(GuiGraphics g, int mx, int my, int x, int y, int w, ResourceKind activeKind) {
        boolean hover = Theme.inside(mx, my, x, y, w, 18);
        Theme.panel(g, x, y, w, 18, 5, hover ? Theme.PRIMARY_SOFT_H : Theme.SURFACE, hover ? Theme.PRIMARY : Theme.BORDER);
        Theme.text(g, font, Theme.ellipsize(font, kindLabel(activeKind), w - 16), x + 6, y + 6, Theme.PRIMARY_PRESS);
        if (faceKindDropdownOpen) Theme.chevronUp(g, x + w - 8, y + 9, 5, Theme.TEXT_MUTED);
        else Theme.chevronDown(g, x + w - 8, y + 9, 5, Theme.TEXT_MUTED);

        if (!faceKindDropdownOpen) return;
        List<ResourceKind> kinds = availableSideKinds();
        int rowH = 17;
        int listY = y + 20;
        Theme.panel(g, x, listY, w, kinds.size() * rowH + 4, 5, Theme.SURFACE, Theme.BORDER);
        for (int i = 0; i < kinds.size(); i++) {
            ResourceKind kind = kinds.get(i);
            int rowY = listY + 2 + i * rowH;
            boolean selected = kind == activeKind;
            boolean rowHover = Theme.inside(mx, my, x + 2, rowY, w - 4, rowH);
            if (selected || rowHover) Theme.fillRound(g, x + 2, rowY, w - 4, rowH, 4, selected ? Theme.PRIMARY_SOFT : Theme.SURFACE_ALT);
            Theme.textCentered(g, font, kindLabel(kind), x + w / 2, rowY + 5, selected ? Theme.PRIMARY_PRESS : Theme.TEXT);
        }
    }

    private void renderStressOutputControls(GuiGraphics g, int mx, int my, int x, int y, int w) {
        Theme.text(g, font, "方向", x, y + 3, Theme.TEXT_MUTED);
        renderStressToggle(g, mx, my, x + 25, y, 28, "同向", !cacheStressOutputReversed);
        renderStressToggle(g, mx, my, x + 56, y, 28, "反向", cacheStressOutputReversed);
        Theme.text(g, font, "转速", x, y + 20, Theme.TEXT_MUTED);
        renderStressToggle(g, mx, my, x + 25, y + 17, Math.min(58, w - 25), stressSpeedLabel(cacheStressOutputSpeedRpm), true);
    }

    private void renderStressToggle(GuiGraphics g, int mx, int my, int x, int y, int w, String label, boolean selected) {
        boolean hover = Theme.inside(mx, my, x, y, w, 15);
        int fill = selected ? Theme.PRIMARY_SOFT : Theme.SURFACE;
        int border = hover ? Theme.PRIMARY : selected ? Theme.PRIMARY : Theme.BORDER;
        Theme.panel(g, x, y, w, 15, 4, fill, border);
        Theme.textCentered(g, font, label, x + w / 2, y + 4, selected ? Theme.PRIMARY_PRESS : Theme.TEXT);
    }

    private void renderFaceModeButton(GuiGraphics g, int mx, int my, int x, int y, int w, ResourceKind kind, SideMode mode) {
        boolean selected = cachedSideMode(kind, selectedSide) == mode;
        boolean hover = Theme.inside(mx, my, x, y, w, 18);
        int fill = selected ? sideModeFill(mode) : Theme.SURFACE;
        int border = hover ? Theme.PRIMARY : selected ? sideModeText(mode) : Theme.BORDER;
        Theme.panel(g, x, y, w, 18, 5, fill, border);
        Theme.textCentered(g, font, sideModeLabel(mode), x + w / 2, y + 6, sideModeText(mode));
    }

    private SideMode[] sideModesFor(ResourceKind kind) {
        return kind == ResourceKind.STRESS
                ? new SideMode[]{SideMode.DISABLED, SideMode.INPUT, SideMode.OUTPUT}
                : new SideMode[]{SideMode.DISABLED, SideMode.INPUT, SideMode.OUTPUT, SideMode.BOTH};
    }

    private void renderFaceCube(GuiGraphics g, int mx, int my, int cx, int cy, int scale) {
        List<ProjectedFace> faces = projectedFaces(cx, cy, scale, true);
        for (ProjectedFace face : faces) {
            drawTexturedQuad(g, face.xs(), face.ys(), faceBaseTexture(face.side()), 0xFFFFFFFF);
            drawFaceModule(g, face);
            fillQuad(g, face.xs(), face.ys(), faceOverlay(face.side(), face.side() == selectedSide));
        }
        for (ProjectedFace face : faces) {
            Theme.textCentered(g, font, sideLabel(face.side()), (int) Math.round(face.cx()), (int) Math.round(face.cy()) - 4,
                    face.side() == selectedSide ? Theme.PRIMARY_PRESS : Theme.TEXT);
        }
        RelativeSide hover = faceAt(mx, my, cx, cy, scale);
        if (hover != null) {
            ProjectedFace face = findProjectedFace(faces, hover);
            if (face != null) fillQuad(g, face.xs(), face.ys(), 0x22FFFFFF);
        }
    }

    private ResourceLocation faceBaseTexture(RelativeSide side) {
        return side == RelativeSide.UP || side == RelativeSide.DOWN ? CHEST_FACE_CAP_TEXTURE : CHEST_FACE_SIDE_TEXTURE;
    }

    private void drawFaceModule(GuiGraphics g, ProjectedFace face) {
        ResourceKind kind = activeSideKind(face.side());
        SideMode mode = cachedSideMode(kind, face.side());
        if (mode == SideMode.DISABLED) return;

        double[] xs = inset(face.xs(), 0.25);
        double[] ys = inset(face.ys(), 0.25);
        int tint = moduleTint(kind, mode);
        switch (kind) {
            case ITEM -> drawAnimatedTexture(g, xs, ys, CHEST_ITEM_PORT_TEXTURE, tint, 12, 4);
            case FLUID -> drawAnimatedTexture(g, xs, ys, CHEST_FLUID_WINDOW_TEXTURE, tint, 10, 8);
            case ENERGY -> drawAnimatedTexture(g, xs, ys, CHEST_ENERGY_CORE_TEXTURE, tint, 8, 5);
            case STRESS -> {
                drawAnimatedTexture(g, xs, ys, CHEST_BEARING_RING_TEXTURE, tint, 8, 4);
                drawTexturedQuad(g, inset(face.xs(), 0.75), inset(face.ys(), 0.75), CHEST_BEARING_SHAFT_TEXTURE,
                        0xFFFFFFFF, 0.375f, 0.375f, 0.625f, 0.625f);
            }
        }
    }

    private void drawAnimatedTexture(GuiGraphics g, double[] xs, double[] ys, ResourceLocation texture, int color, int frames, int frameTime) {
        long tick = minecraft != null && minecraft.level != null ? minecraft.level.getGameTime() : System.currentTimeMillis() / 50L;
        int frame = (int) ((tick / Math.max(1, frameTime)) % Math.max(1, frames));
        float v0 = (float) frame / frames;
        float v1 = (float) (frame + 1) / frames;
        drawTexturedQuad(g, xs, ys, texture, color, 0.0f, v0, 1.0f, v1);
    }

    private int moduleTint(ResourceKind kind, SideMode mode) {
        if (kind == ResourceKind.STRESS || kind == ResourceKind.ENERGY) return 0xFFFFFFFF;
        return switch (mode) {
            case INPUT -> 0xFFE4FFF0;
            case OUTPUT -> 0xFFE2EEFF;
            case BOTH -> 0xFFFFF1BC;
            case DISABLED -> 0xFFFFFFFF;
        };
    }

    private int faceOverlay(RelativeSide side, boolean selected) {
        SideMode mode = cachedSideMode(activeSideKind(side), side);
        boolean in = mode.canInput();
        boolean out = mode.canOutput();
        int color = in && out ? 0xFFEFE2AC : in ? 0xFFDDF4E7 : out ? 0xFFDCEBFF : 0xFFE6EDF4;
        int mixed = selected ? mixColor(color, 0xFFFFFFFF, 0.22f) : color;
        return ((selected ? 0x64 : 0x2D) << 24) | (mixed & 0x00FFFFFF);
    }

    private double[] inset(double[] values, double amount) {
        double center = 0.0;
        for (double value : values) center += value;
        center /= values.length;
        double[] out = new double[values.length];
        for (int i = 0; i < values.length; i++) out[i] = center + (values[i] - center) * (1.0 - amount);
        return out;
    }

    private List<ProjectedFace> projectedFaces(int cx, int cy, int scale, boolean visibleOnly) {
        List<ProjectedFace> faces = new ArrayList<>();
        for (RelativeSide side : RelativeSide.values()) {
            double[][] vertices = sideVertices(side);
            double[] xs = new double[4];
            double[] ys = new double[4];
            double depth = 0.0;
            double centerX = 0.0;
            double centerY = 0.0;
            for (int i = 0; i < 4; i++) {
                double[] p = rotate(vertices[i][0], vertices[i][1], vertices[i][2]);
                xs[i] = cx + p[0] * scale;
                ys[i] = cy - p[1] * scale;
                depth += p[2];
                centerX += xs[i];
                centerY += ys[i];
            }
            double[] normal = sideNormal(side);
            double normalDepth = rotate(normal[0], normal[1], normal[2])[2];
            if (!visibleOnly || normalDepth > 0.015) {
                faces.add(new ProjectedFace(side, xs, ys, centerX / 4.0, centerY / 4.0, depth / 4.0, normalDepth));
            }
        }
        faces.sort(Comparator.comparingDouble(ProjectedFace::depth));
        return faces;
    }

    private ProjectedFace findProjectedFace(List<ProjectedFace> faces, RelativeSide side) {
        for (ProjectedFace face : faces) if (face.side() == side) return face;
        return null;
    }

    private RelativeSide faceAt(double mx, double my, int cx, int cy, int scale) {
        List<ProjectedFace> faces = projectedFaces(cx, cy, scale, true);
        faces.sort(Comparator.comparingDouble(ProjectedFace::depth).reversed());
        for (ProjectedFace face : faces) {
            if (pointInPolygon(mx, my, face.xs(), face.ys())) return face.side();
        }
        return null;
    }

    private boolean pointInPolygon(double px, double py, double[] xs, double[] ys) {
        boolean inside = false;
        for (int i = 0, j = xs.length - 1; i < xs.length; j = i++) {
            boolean cross = (ys[i] > py) != (ys[j] > py)
                    && px < (xs[j] - xs[i]) * (py - ys[i]) / (ys[j] - ys[i] + 0.00001) + xs[i];
            if (cross) inside = !inside;
        }
        return inside;
    }

    private double[] rotate(double x, double y, double z) {
        double yaw = Math.toRadians(faceYaw);
        double pitch = Math.toRadians(facePitch);
        double cy = Math.cos(yaw);
        double sy = Math.sin(yaw);
        double cp = Math.cos(pitch);
        double sp = Math.sin(pitch);
        double rx = x * cy + z * sy;
        double rz = -x * sy + z * cy;
        double ry = y * cp - rz * sp;
        double rz2 = y * sp + rz * cp;
        return new double[]{rx, ry, rz2};
    }

    private double[][] sideVertices(RelativeSide side) {
        return switch (side) {
            case FRONT -> new double[][]{{-1, -1, 1}, {1, -1, 1}, {1, 1, 1}, {-1, 1, 1}};
            case BACK -> new double[][]{{1, -1, -1}, {-1, -1, -1}, {-1, 1, -1}, {1, 1, -1}};
            case LEFT -> new double[][]{{-1, -1, -1}, {-1, -1, 1}, {-1, 1, 1}, {-1, 1, -1}};
            case RIGHT -> new double[][]{{1, -1, 1}, {1, -1, -1}, {1, 1, -1}, {1, 1, 1}};
            case UP -> new double[][]{{-1, 1, 1}, {1, 1, 1}, {1, 1, -1}, {-1, 1, -1}};
            case DOWN -> new double[][]{{-1, -1, -1}, {1, -1, -1}, {1, -1, 1}, {-1, -1, 1}};
        };
    }

    private double[] sideNormal(RelativeSide side) {
        return switch (side) {
            case FRONT -> new double[]{0, 0, 1};
            case BACK -> new double[]{0, 0, -1};
            case LEFT -> new double[]{-1, 0, 0};
            case RIGHT -> new double[]{1, 0, 0};
            case UP -> new double[]{0, 1, 0};
            case DOWN -> new double[]{0, -1, 0};
        };
    }

    private void fillQuad(GuiGraphics g, double[] xs, double[] ys, int color) {
        Matrix4f matrix = g.pose().last().pose();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i < 4; i++) vertex(buf, matrix, (float) xs[i], (float) ys[i], color);
        BufferUploader.drawWithShader(buf.buildOrThrow());
        RenderSystem.disableBlend();
    }

    private void drawTexturedQuad(GuiGraphics g, double[] xs, double[] ys, ResourceLocation texture, int color) {
        drawTexturedQuad(g, xs, ys, texture, color, 0.0f, 0.0f, 1.0f, 1.0f);
    }

    private void drawTexturedQuad(GuiGraphics g, double[] xs, double[] ys, ResourceLocation texture, int color, float u0, float v0, float u1, float v1) {
        Matrix4f matrix = g.pose().last().pose();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, texture);
        BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        vertexTex(buf, matrix, (float) xs[0], (float) ys[0], u0, v1, color);
        vertexTex(buf, matrix, (float) xs[1], (float) ys[1], u1, v1, color);
        vertexTex(buf, matrix, (float) xs[2], (float) ys[2], u1, v0, color);
        vertexTex(buf, matrix, (float) xs[3], (float) ys[3], u0, v0, color);
        BufferUploader.drawWithShader(buf.buildOrThrow());
        RenderSystem.disableBlend();
    }

    private void vertex(BufferBuilder buf, Matrix4f matrix, float x, float y, int color) {
        float a = ((color >>> 24) & 0xFF) / 255f;
        float r = ((color >> 16) & 0xFF) / 255f;
        float gr = ((color >> 8) & 0xFF) / 255f;
        float b = (color & 0xFF) / 255f;
        buf.addVertex(matrix, x, y, 0).setColor(r, gr, b, a);
    }

    private void vertexTex(BufferBuilder buf, Matrix4f matrix, float x, float y, float u, float v, int color) {
        float a = ((color >>> 24) & 0xFF) / 255f;
        float r = ((color >> 16) & 0xFF) / 255f;
        float gr = ((color >> 8) & 0xFF) / 255f;
        float b = (color & 0xFF) / 255f;
        buf.addVertex(matrix, x, y, 0).setUv(u, v).setColor(r, gr, b, a);
    }

    private int mixColor(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = Math.round(ar + (br - ar) * t);
        int g = Math.round(ag + (bg - ag) * t);
        int bl = Math.round(ab + (bb - ab) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private record ProjectedFace(RelativeSide side, double[] xs, double[] ys, double cx, double cy, double depth, double normalDepth) {
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
            if (handleFacePageClick(mx, my, button)) return true;
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
        if (button == 0 && rotatingFaceCube) {
            rotatingFaceCube = false;
            if (!faceCubeMoved) {
                int cardX = leftPos + BaseChestMenu.PANEL_PADDING;
                int cardW = panelW - BaseChestMenu.PANEL_PADDING * 2;
                int cubeY = topPos + BaseChestMenu.HEADER_HEIGHT + 10;
                RelativeSide hit = faceAt(mx, my, cardX + cardW / 2, cubeY + FACE_CUBE_CENTER_Y_OFFSET, FACE_CUBE_SCALE);
                if (hit != null) selectFace(hit);
            }
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (isFacePage() && rotatingFaceCube && button == 0) {
            faceYaw += dx * 0.8;
            facePitch = Math.max(-84.0, Math.min(84.0, facePitch - dy * 0.8));
            if (Math.abs(mx - faceCubeDragX) > 2 || Math.abs(my - faceCubeDragY) > 2) faceCubeMoved = true;
            return true;
        }
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

    private boolean handleFacePageClick(double mx, double my, int button) {
        if (button != 0) return true;
        int cardX = leftPos + BaseChestMenu.PANEL_PADDING;
        int cardW = panelW - BaseChestMenu.PANEL_PADDING * 2;
        int cubeY = topPos + BaseChestMenu.HEADER_HEIGHT + 10;
        if (Theme.inside(mx, my, cardX, cubeY, cardW, FACE_CUBE_HEIGHT)) {
            faceKindDropdownOpen = false;
            rotatingFaceCube = true;
            faceCubeMoved = false;
            faceCubeDragX = (int) mx;
            faceCubeDragY = (int) my;
            return true;
        }
        int controlsY = cubeY + FACE_CUBE_HEIGHT + 8;
        if (handleFaceModeClick(mx, my, cardX, controlsY, cardW)) return true;
        faceKindDropdownOpen = false;
        return true;
    }

    private boolean handleFaceModeClick(double mx, double my, int x, int y, int w) {
        int functionX = x + 9;
        int functionY = y + 28;
        int functionW = 50;
        int settingsX = functionX + functionW + 9;
        int settingsW = Math.max(90, x + w - settingsX - 9);

        if (Theme.inside(mx, my, functionX, functionY, functionW, 18)) {
            faceKindDropdownOpen = !faceKindDropdownOpen;
            return true;
        }
        if (faceKindDropdownOpen) {
            List<ResourceKind> kinds = availableSideKinds();
            int rowH = 17;
            int listY = functionY + 20;
            for (int i = 0; i < kinds.size(); i++) {
                int rowY = listY + 2 + i * rowH;
                if (Theme.inside(mx, my, functionX + 2, rowY, functionW - 4, rowH)) {
                    setFaceFunction(kinds.get(i));
                    faceKindDropdownOpen = false;
                    return true;
                }
            }
            if (Theme.inside(mx, my, functionX, listY, functionW, kinds.size() * rowH + 4)) {
                return true;
            }
            faceKindDropdownOpen = false;
        }

        ResourceKind activeKind = activeSideKind(selectedSide);
        int bx = settingsX;
        int by = y + 28;
        for (SideMode mode : sideModesFor(activeKind)) {
            int bw = 21;
            if (Theme.inside(mx, my, bx, by, bw, 18)) {
                setFaceMode(activeKind, mode);
                return true;
            }
            bx += bw + 3;
        }
        if (activeKind == ResourceKind.STRESS && cachedSideMode(activeKind, selectedSide) == SideMode.OUTPUT) {
            if (handleStressOutputConfigClick(mx, my, settingsX, y + 52, settingsW)) return true;
        }
        return false;
    }

    private boolean handleStressOutputConfigClick(double mx, double my, int x, int y, int w) {
        if (Theme.inside(mx, my, x + 25, y, 28, 15)) {
            setStressOutputReversed(false);
            return true;
        }
        if (Theme.inside(mx, my, x + 56, y, 28, 15)) {
            setStressOutputReversed(true);
            return true;
        }
        if (Theme.inside(mx, my, x + 25, y + 17, Math.min(58, w - 25), 15)) {
            setStressOutputSpeed(nextStressOutputSpeed(cacheStressOutputSpeedRpm));
            return true;
        }
        return false;
    }

    private void selectFace(RelativeSide side) {
        if (side == null) return;
        faceKindDropdownOpen = false;
        selectedSide = side;
        switch (side) {
            case FRONT -> { faceYaw = 0; facePitch = 0; }
            case BACK -> { faceYaw = 180; facePitch = 0; }
            case LEFT -> { faceYaw = 90; facePitch = 0; }
            case RIGHT -> { faceYaw = -90; facePitch = 0; }
            case UP -> { faceYaw = 0; facePitch = 82; }
            case DOWN -> { faceYaw = 0; facePitch = -82; }
        }
    }

    private boolean canEditSideKind(ResourceKind kind) {
        if (kind == ResourceKind.STRESS) return cacheCreateLoaded;
        return true;
    }

    private List<ResourceKind> availableSideKinds() {
        List<ResourceKind> kinds = new ArrayList<>();
        kinds.add(ResourceKind.ITEM);
        if (hasFluidPage()) kinds.add(ResourceKind.FLUID);
        kinds.add(ResourceKind.ENERGY);
        if (cacheCreateLoaded) kinds.add(ResourceKind.STRESS);
        return kinds;
    }

    private ResourceKind activeSideKind(RelativeSide side) {
        List<ResourceKind> available = availableSideKinds();
        for (ResourceKind kind : available) {
            if (cachedSideMode(kind, side) != SideMode.DISABLED) return kind;
        }
        for (ResourceKind kind : ResourceKind.values()) {
            if (cachedSideMode(kind, side) != SideMode.DISABLED) return available.contains(kind) ? kind : ResourceKind.ITEM;
        }
        return ResourceKind.ITEM;
    }

    private int faceFunctionButtonWidth(ResourceKind kind) {
        return Math.max(36, font.width(kindLabel(kind)) + 16);
    }

    private SideMode defaultModeForKind(ResourceKind kind) {
        return kind == ResourceKind.STRESS ? SideMode.INPUT : SideMode.BOTH;
    }

    private void setFaceFunction(ResourceKind kind) {
        if (!canEditSideKind(kind)) return;
        setFaceConfig(kind, defaultModeForKind(kind));
    }

    private void setFaceMode(ResourceKind kind, SideMode mode) {
        if (!canEditSideKind(kind)) return;
        setFaceConfig(kind, mode);
    }

    private void setFaceConfig(ResourceKind kind, SideMode mode) {
        for (ResourceKind other : ResourceKind.values()) {
            SideMode next = other == kind ? mode : SideMode.DISABLED;
            cacheSideConfig.computeIfAbsent(other, k -> new EnumMap<>(RelativeSide.class)).put(selectedSide, next);
        }
        send(18, selectedSide.name() + "|" + kind.name() + "|" + mode.name());
    }

    private void setStressOutputReversed(boolean reversed) {
        cacheStressOutputReversed = reversed;
        send(20, reversed ? "1" : "0");
    }

    private void setStressOutputSpeed(int rpm) {
        cacheStressOutputSpeedRpm = rpm;
        send(19, String.valueOf(rpm));
    }

    private int nextStressOutputSpeed(int current) {
        for (int i = 0; i < STRESS_SPEED_OPTIONS.length; i++) {
            if (STRESS_SPEED_OPTIONS[i] == current) return STRESS_SPEED_OPTIONS[(i + 1) % STRESS_SPEED_OPTIONS.length];
        }
        return 0;
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

    private String stressSpeedLabel(int rpm) {
        return rpm <= 0 ? "同速" : rpm + "rpm";
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
