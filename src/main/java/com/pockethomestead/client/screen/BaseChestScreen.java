package com.pockethomestead.client.screen;

import com.pockethomestead.client.ClientProductionStatsCache;
import com.pockethomestead.client.ui.Theme;
import com.pockethomestead.client.ui.widget.UiButton;
import com.pockethomestead.menu.BaseChestMenu;
import com.pockethomestead.network.ChestConfigPacket;
import com.pockethomestead.network.ChestSyncPacket;
import com.pockethomestead.network.RequestProductionStatsPacket;
import com.pockethomestead.network.UpdateProductionStatsPacket;
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
import java.util.List;
import java.util.Map;

/**
 * 箱子Screen — 蓝白主题，两页布局。
 *
 * 第0页（内容）：箱子区（1物品=1格，纯渲染）+ 玩家背包。
 * 第1页（节点）：可视化节点编辑入口。
 *
 * 箱子区无真实槽位，存取全部通过网络包；服务端 itemStorage 为唯一权威，
 * 客户端通过 ChestSyncPacket 接收物品快照 cacheItems。
 */
public abstract class BaseChestScreen<T extends BaseChestMenu> extends AbstractContainerScreen<T> {

    /** 当前页：0=内容，1=配置 */
    private int currentPage = 0;

    private int localScrollRow = 0;

    private int cacheMaxCapacity = 4096;
    private int cacheMaxFluidCapacityMb = 16000;
    private String cacheChestId = "";
    private boolean cacheProductionStatsEnabled;
    private String cacheProductionGroupId = "";

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
    private Slot hoveredPlayerSlot = null;

    // 动态布局值
    private int panelW, panelH;
    private int slotStartX;
    private int chestAreaX, chestAreaY, chestAreaW, chestAreaH;
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
        this.cacheChestId = p.chestId();
        this.cacheProductionStatsEnabled = p.productionStatsEnabled();
        this.cacheProductionGroupId = p.productionGroupId();

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
        currentPage = (currentPage == 0) ? 1 : 0;
        rebuildPageWidgets();
    }

    /** 根据当前页重建控件（配置页才创建配置控件） */
    private void rebuildPageWidgets() {
        graphButton = null;
        statsGroupDropdownOpen = false;

        if (pageButton != null) {
            pageButton.label(currentPage == 0 ? "配置 ▶" : "◀ 内容");
        }

        if (currentPage == 1) {
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

        if (currentPage == 0) {
            // 存货区背景
            Theme.fillRound(g, x + chestAreaX, y + chestAreaY, chestAreaW, chestAreaH, Theme.RADIUS, Theme.SURFACE_SUNK);
            if (BaseChestMenu.isCreateLoaded()) {
                int fluidY = y + BaseChestMenu.calculateChestSlotStartY() + BaseChestMenu.CHEST_VISIBLE_ROWS * BaseChestMenu.SLOT_SIZE + 4;
                Theme.fillRound(g, x + BaseChestMenu.PANEL_PADDING, fluidY, panelW - 2 * BaseChestMenu.PANEL_PADDING, 16, 4, Theme.SURFACE_SUNK);
            }
            Theme.hLine(g, x + 1, y + playerLabelY - 2, panelW - 2, Theme.DIVIDER);
        }
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        calculateLayout();
        renderBg(g, partialTick, mx, my);

        if (currentPage == 0) {
            renderChestSlots(g, mx, my);
            renderScrollbar(g);
            if (BaseChestMenu.isCreateLoaded()) renderFluidSection(g, mx, my);
            else { hoveredFluid = null; hoveredFluidAmount = 0; }
            renderPlayerInventorySlots(g, mx, my);
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

    /** 流体区域：显示当前储存的流体与 mB 数量 */
    private void renderFluidSection(GuiGraphics g, int mx, int my) {
        hoveredFluid = null;
        hoveredFluidAmount = 0;

        int x = leftPos + BaseChestMenu.PANEL_PADDING + 4;
        int y = topPos + BaseChestMenu.calculateChestSlotStartY() + BaseChestMenu.CHEST_VISIBLE_ROWS * BaseChestMenu.SLOT_SIZE + 7;
        Theme.text(g, font, "流体:", x, y, Theme.TEXT_MUTED);

        int cursorX = x + font.width("流体:") + 6;
        if (cacheFluids.isEmpty()) {
            Theme.text(g, font, "空", cursorX, y, Theme.TEXT_FAINT);
            return;
        }

        int maxCards = Math.min(3, cacheFluids.size());
        for (int i = 0; i < maxCards; i++) {
            Map.Entry<Fluid, Integer> entry = cacheFluids.get(i);
            Fluid fluid = entry.getKey();
            int amount = entry.getValue();
            int cardW = 42;
            boolean hovered = mx >= cursorX && mx < cursorX + cardW && my >= y - 2 && my < y + 12;
            int color = 0xFF000000 | (BuiltInRegistries.FLUID.getKey(fluid).toString().hashCode() & 0x00FFFFFF);
            g.fill(cursorX, y - 1, cursorX + 8, y + 9, color);
            g.fill(cursorX, y - 1, cursorX + 8, y, 0xCCFFFFFF);
            String label = BaseChestMenu.formatCount(amount) + "mB";
            Theme.text(g, font, label, cursorX + 11, y, hovered ? Theme.TEXT : Theme.TEXT_MUTED);
            if (hovered) {
                hoveredFluid = fluid;
                hoveredFluidAmount = amount;
            }
            cursorX += cardW;
        }

        if (cacheFluids.size() > maxCards) {
            Theme.text(g, font, "+" + (cacheFluids.size() - maxCards), cursorX, y, Theme.TEXT_FAINT);
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

        if (currentPage == 0) {
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
        if (currentPage != 0) return;
        if (hoveredPlayerSlot != null && hoveredPlayerSlot.hasItem()) {
            g.renderTooltip(font, hoveredPlayerSlot.getItem(), mx, my);
        } else if (!hoveredChestStack.isEmpty()) {
            ItemStack stack = hoveredChestStack.copyWithCount(1);
            List<Component> lines = new ArrayList<>();
            lines.add(stack.getHoverName());
            lines.add(Component.literal("数量: " + hoveredChestCount).withStyle(ChatFormatting.GRAY));
            lines.add(Component.literal("左键取一组 · 右键取一个 · Shift入背包").withStyle(ChatFormatting.DARK_GRAY));
            g.renderComponentTooltip(font, lines, mx, my);
        } else if (hoveredFluid != null) {
            FluidStack stack = new FluidStack(hoveredFluid, hoveredFluidAmount);
            List<Component> lines = new ArrayList<>();
            lines.add(stack.getHoverName());
            lines.add(Component.literal(hoveredFluidAmount + " / " + cacheMaxFluidCapacityMb + " mB").withStyle(ChatFormatting.GRAY));
            g.renderComponentTooltip(font, lines, mx, my);
        }
    }

    // ── 输入 ──

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        // 内容页：存货区滚动（纯客户端）
        if (currentPage == 0) {
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

        if (currentPage == 1) {
            if (graphButton != null && graphButton.mouseClicked(mx, my, button)) return true;
            if (handleConfigPageClick(mx, my, button)) return true;
            return true;
        }

        // 内容页：控件（无）→ 存货区交互
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
        if (button != 0) return true;
        int cardX = leftPos + BaseChestMenu.PANEL_PADDING;
        int cardY = topPos + BaseChestMenu.HEADER_HEIGHT + 70;
        int cardW = panelW - BaseChestMenu.PANEL_PADDING * 2;
        int selectorX = groupSelectorX(cardX);
        int selectorY = cardY + 49;
        int selectorW = groupSelectorW(cardW);

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

    @Override
    protected void renderLabels(GuiGraphics g, int mx, int my) {
        // 标签由自定义 renderLabels(g) 处理，禁用 vanilla 标签
    }
}
