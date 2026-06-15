package com.pockethomestead.client.screen;

import com.pockethomestead.client.ui.Theme;
import com.pockethomestead.client.ui.widget.UiButton;
import com.pockethomestead.client.ui.widget.UiDropdown;
import com.pockethomestead.client.ui.widget.UiToggle;
import com.pockethomestead.config.ModConfig;
import com.pockethomestead.menu.BaseChestMenu;
import com.pockethomestead.network.ChestConfigPacket;
import com.pockethomestead.network.ChestSyncPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * 箱子Screen — 蓝白主题 + 自定义字体 + 可滚动存货区。
 * 所有布局值动态计算，使用相对位置。
 */
public abstract class BaseChestScreen<T extends BaseChestMenu> extends AbstractContainerScreen<T> {

    private int localScrollRow = 0;
    private int lastSentScrollRow = -1;

    private String cacheChestId = "";
    private String cacheBoundTargetId = "";
    private boolean cacheTransferEnabled = false;
    private boolean cacheVoidModeEnabled = false;
    private int cacheTransferRateLimit = 0;
    private List<String> cacheAvailableBindings = List.of();

    // Theme styled widgets
    private EditBox idEditBox;
    private UiDropdown bindDropdown;
    private UiToggle transferToggle;
    private UiToggle voidToggle;
    private UiButton rateDecButton;
    private UiButton rateIncButton;

    private String lastSentChestId = "";
    private Slot hoveredSlot = null;

    // 动态布局值
    private int panelW, panelH;
    private int slotStartX;
    private int chestAreaX, chestAreaY, chestAreaW, chestAreaH;
    private int scrollbarX;
    private int playerLabelY, playerInvY, hotbarY, configY;

    public BaseChestScreen(T menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth = BaseChestMenu.calculatePanelWidth();
        this.imageHeight = BaseChestMenu.calculatePanelHeight();
        this.titleLabelX = BaseChestMenu.PANEL_PADDING;
        this.titleLabelY = 4;
        this.inventoryLabelY = BaseChestMenu.calculatePlayerLabelY();
    }

    /** 根据当前 imageWidth/imageHeight 计算所有布局值 */
    private void calculateLayout() {
        panelW = imageWidth;
        panelH = imageHeight;

        slotStartX = BaseChestMenu.calculateSlotStartX(panelW);

        // 存货区：包含槽位网格 + 滚动条
        int gridW = BaseChestMenu.CHEST_COLS * BaseChestMenu.SLOT_SIZE;
        chestAreaX = slotStartX - 4;
        chestAreaY = BaseChestMenu.calculateChestSlotStartY() - 4;
        chestAreaW = gridW + BaseChestMenu.SCROLLBAR_WIDTH + BaseChestMenu.SCROLLBAR_GAP + 8;
        chestAreaH = BaseChestMenu.CHEST_VISIBLE_ROWS * BaseChestMenu.SLOT_SIZE + 8;

        scrollbarX = chestAreaX + chestAreaW - BaseChestMenu.SCROLLBAR_WIDTH - 4;

        playerLabelY = BaseChestMenu.calculatePlayerLabelY();
        playerInvY = BaseChestMenu.calculatePlayerInvStartY();
        hotbarY = BaseChestMenu.calculateHotbarStartY();
        configY = BaseChestMenu.calculateConfigY();
    }

    // ── 配置同步 ──

    public void cacheConfig(ChestSyncPacket p) {
        this.cacheChestId = p.chestId();
        this.cacheBoundTargetId = p.boundTargetId();
        this.cacheTransferEnabled = p.transferEnabled();
        this.cacheVoidModeEnabled = p.voidModeEnabled();
        this.cacheTransferRateLimit = p.transferRateLimit();
        this.cacheAvailableBindings = p.availableBindings();
        this.lastSentChestId = p.chestId();
        refreshWidgets();
    }

    private void send(int action, String value) {
        if (Minecraft.getInstance().player != null)
            PacketDistributor.sendToServer(new ChestConfigPacket(action, value));
    }

    // ── init ──

    @Override
    protected void init() {
        super.init();
        calculateLayout();

        int gl = leftPos, gt = topPos;
        // 配置栏在面板底部，使用相对位置
        int cy = gt + configY + 4;

        // ID 编辑框（小字体，紧凑布局）
        idEditBox = new EditBox(font, gl + 8 + 20, cy, 60, 14, Component.literal("ID"));
        idEditBox.setMaxLength(32);
        idEditBox.setValue(cacheChestId.isEmpty() ? "supply_1" : cacheChestId); // 显示默认值
        idEditBox.setHint(Component.literal("ID"));
        this.addRenderableWidget(idEditBox);

        // 绑定下拉菜单（紧凑）
        List<String> bindOptions = new ArrayList<>(cacheAvailableBindings);
        if (bindOptions.isEmpty()) bindOptions.add("无绑定");
        int selectedIdx = bindOptions.indexOf(cacheBoundTargetId);
        if (selectedIdx < 0) selectedIdx = 0;

        bindDropdown = new UiDropdown(bindOptions, selectedIdx)
                .bounds(gl + 8 + 90, cy, 70, 16)
                .onSelect(idx -> {
                    if (idx >= 0 && idx < cacheAvailableBindings.size()) {
                        send(3, cacheAvailableBindings.get(idx));
                    }
                });

        // 第二行：传输/虚空开关 + 速率（使用相对位置，从面板左边距开始）
        int row2Y = cy + 20;
        transferToggle = new UiToggle("传输", cacheTransferEnabled)
                .bounds(gl + 8, row2Y, 48, 16)
                .onChange(v -> send(0, ""));

        voidToggle = new UiToggle("虚空", cacheVoidModeEnabled)
                .bounds(gl + 8 + 54, row2Y, 48, 16)
                .onChange(v -> send(1, ""));

        // 速率调整（右侧，相对面板右边距）
        int rateX = gl + panelW - 8 - 60;
        rateDecButton = new UiButton("−", UiButton.Variant.GHOST)
                .bounds(rateX, row2Y, 18, 16)
                .onClick(() -> send(6, ""));
        rateIncButton = new UiButton("+", UiButton.Variant.GHOST)
                .bounds(rateX + 42, row2Y, 18, 16)
                .onClick(() -> send(5, ""));

        refreshWidgets();
    }

    private void refreshWidgets() {
        if (idEditBox != null && !idEditBox.isFocused()) {
            String displayId = cacheChestId.isEmpty() ? "supply_1" : cacheChestId;
            idEditBox.setValue(displayId);
        }
        if (bindDropdown != null) {
            List<String> bindOptions = new ArrayList<>(cacheAvailableBindings);
            if (bindOptions.isEmpty()) bindOptions.add("无绑定");
            bindDropdown.setLabels(bindOptions);
            int selectedIdx = bindOptions.indexOf(cacheBoundTargetId);
            if (selectedIdx >= 0) bindDropdown.setSelected(selectedIdx);
        }
        if (transferToggle != null) transferToggle.setValue(cacheTransferEnabled);
        if (voidToggle != null) voidToggle.setValue(cacheVoidModeEnabled);
    }

    // ── 渲染 ──

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mx, int my) {
        calculateLayout();

        int x = leftPos, y = topPos;

        // 全屏暗化背景
        g.fill(0, 0, width, height, Theme.SCRIM);

        // 主面板
        Theme.shadow(g, x, y, panelW, panelH, Theme.RADIUS + 2);
        Theme.panel(g, x, y, panelW, panelH, Theme.RADIUS + 2, Theme.SURFACE, Theme.BORDER);

        // 标题分隔线
        Theme.hLine(g, x + 1, y + BaseChestMenu.HEADER_HEIGHT, panelW - 2, Theme.DIVIDER);

        // 存货区背景
        Theme.fillRound(g, x + chestAreaX, y + chestAreaY, chestAreaW, chestAreaH, Theme.RADIUS, Theme.SURFACE_SUNK);

        // 玩家背包分隔线
        Theme.hLine(g, x + 1, y + playerLabelY - 2, panelW - 2, Theme.DIVIDER);

        // 配置栏分隔线
        Theme.hLine(g, x + 1, y + configY, panelW - 2, Theme.DIVIDER);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        calculateLayout();

        renderBg(g, partialTick, mx, my);
        renderPlayerInventorySlots(g);
        renderChestSlots(g, mx, my, partialTick);
        renderScrollbar(g);
        renderLabels(g);
        renderConfigWidgets(g, mx, my, partialTick);
        renderDraggedItem(g, mx, my);
        renderTooltip(g, mx, my);

        // Dropdown 弹出层（最上层）
        if (bindDropdown != null) bindDropdown.renderPopup(g, mx, my);
    }

    /** 渲染玩家背包槽位 */
    private void renderPlayerInventorySlots(GuiGraphics g) {
        g.pose().pushPose();
        g.pose().translate(leftPos, topPos, 0);

        for (int i = BaseChestMenu.CHEST_SLOTS; i < BaseChestMenu.CHEST_SLOTS + 27; i++) {
            if (i >= menu.slots.size()) break;
            renderSlotVanilla(g, menu.slots.get(i));
        }

        for (int i = BaseChestMenu.CHEST_SLOTS + 27; i < menu.slots.size(); i++) {
            renderSlotVanilla(g, menu.slots.get(i));
        }

        g.pose().popPose();
    }

    private void renderSlotVanilla(GuiGraphics g, Slot slot) {
        ItemStack stack = slot.getItem();
        int x = slot.x, y = slot.y;

        // 槽位边框（明显的网格）
        g.fill(x - 1, y - 1, x + 17, y, 0x80000000);           // 上
        g.fill(x - 1, y + 16, x + 17, y + 17, 0x80000000);     // 下
        g.fill(x - 1, y, x, y + 16, 0x80000000);               // 左
        g.fill(x + 16, y, x + 17, y + 16, 0x80000000);         // 右
        // 槽位背景
        g.fill(x, y, x + 16, y + 16, 0x40FFFFFF);

        if (!stack.isEmpty()) {
            g.renderItem(stack, x, y);
            g.renderItemDecorations(font, stack, x, y, null);
        }
    }

    /** 渲染存货区槽位（增强网格和数字显示） */
    private void renderChestSlots(GuiGraphics g, int mx, int my, float partialTick) {
        int x = leftPos, y = topPos;
        int slotSize = BaseChestMenu.SLOT_SIZE;

        g.enableScissor(x + chestAreaX + 2, y + chestAreaY + 2,
                        x + chestAreaX + chestAreaW - BaseChestMenu.SCROLLBAR_WIDTH - 6,
                        y + chestAreaY + chestAreaH - 2);

        hoveredSlot = null;

        for (int row = 0; row < BaseChestMenu.CHEST_VISIBLE_ROWS; row++) {
            for (int col = 0; col < BaseChestMenu.CHEST_COLS; col++) {
                int slotIdx = row * BaseChestMenu.CHEST_COLS + col;
                if (slotIdx >= BaseChestMenu.CHEST_SLOTS) break;

                int slotX = x + slotStartX + col * slotSize;
                int slotY = y + BaseChestMenu.calculateChestSlotStartY() + row * slotSize;

                if (slotIdx < menu.slots.size()) {
                    Slot slot = menu.slots.get(slotIdx);
                    ItemStack stack = slot.getItem();

                    boolean hovered = Theme.inside(mx, my, slotX, slotY, 16, 16);
                    if (hovered) {
                        hoveredSlot = slot;
                        Theme.fillRound(g, slotX - 1, slotY - 1, 18, 18, 2, Theme.PRIMARY_SOFT);
                    }

                    // 明显的网格边框
                    g.fill(slotX - 1, slotY - 1, slotX + 17, slotY, 0xA0000000);
                    g.fill(slotX - 1, slotY + 16, slotX + 17, slotY + 17, 0xA0000000);
                    g.fill(slotX - 1, slotY, slotX, slotY + 16, 0xA0000000);
                    g.fill(slotX + 16, slotY, slotX + 17, slotY + 16, 0xA0000000);
                    // 槽位背景
                    g.fill(slotX, slotY, slotX + 16, slotY + 16, hovered ? 0x50FFFFFF : 0x30FFFFFF);

                    if (!stack.isEmpty()) {
                        g.renderItem(stack, slotX, slotY);

                        // 使用原版渲染方式显示数量（右下角对齐，带阴影）
                        int realCount = menu.chestContainer.getRealCount(slotIdx);
                        String countText = BaseChestMenu.formatCount(realCount);

                        // 使用原版字体渲染：右下角对齐 + 白色 + 阴影
                        g.drawString(font, countText,
                                    slotX + 17 - font.width(countText),
                                    slotY + 9,
                                    0xFFFFFF, true);
                    }
                }
            }
        }

        g.disableScissor();
    }

    /** 渲染滚动条 */
    private void renderScrollbar(GuiGraphics g) {
        int x = leftPos, y = topPos;
        int barX = x + scrollbarX;
        int barY = y + chestAreaY + 4;
        int barH = chestAreaH - 8;

        g.fill(barX, barY, barX + BaseChestMenu.SCROLLBAR_WIDTH, barY + barH, 0x18000000);

        int totalRows = menu.totalRows();
        int maxScroll = Math.max(0, totalRows - BaseChestMenu.CHEST_VISIBLE_ROWS);

        if (maxScroll > 0) {
            double ratio = (double) BaseChestMenu.CHEST_VISIBLE_ROWS / totalRows;
            int knobH = Math.max(12, (int) (barH * ratio));
            int knobY = barY + (int) (localScrollRow / (double) maxScroll * (barH - knobH));
            Theme.fillRound(g, barX, knobY, BaseChestMenu.SCROLLBAR_WIDTH, knobH, 2, Theme.BORDER_STRONG);
        } else {
            Theme.fillRound(g, barX, barY, BaseChestMenu.SCROLLBAR_WIDTH, barH, 2, Theme.BORDER_STRONG);
        }
    }

    /** 渲染标签（小字体） */
    private void renderLabels(GuiGraphics g) {
        int x = leftPos, y = topPos;

        // 标题（小号）
        g.pose().pushPose();
        g.pose().scale(0.9f, 0.9f, 1.0f);
        Theme.text(g, font, title.getString(), (int)((x + 8) / 0.9f), (int)((y + 6) / 0.9f), Theme.TEXT);
        g.pose().popPose();

        // 容量显示（小号，右上角）
        int used = menu.blockEntity != null ? menu.blockEntity.getUsedCapacity() : 0;
        int max = ModConfig.MAX_CHEST_CAPACITY.get();
        String capText = BaseChestMenu.formatCount(used) + "/" + BaseChestMenu.formatCount(max);

        g.pose().pushPose();
        g.pose().scale(0.85f, 0.85f, 1.0f);
        int capX = (int)((x + panelW - 8 - font.width(capText) * 0.85f) / 0.85f);
        Theme.text(g, font, capText, capX, (int)((y + 6) / 0.85f), Theme.TEXT_MUTED);
        g.pose().popPose();

        // 玩家背包标签（小号）
        g.pose().pushPose();
        g.pose().scale(0.85f, 0.85f, 1.0f);
        Theme.text(g, font, playerInventoryTitle.getString(),
                   (int)((x + slotStartX) / 0.85f), (int)((y + playerLabelY) / 0.85f), Theme.TEXT_MUTED);
        g.pose().popPose();

        // 配置栏标签（小号，相对位置）
        int cy = y + configY + 4;
        g.pose().pushPose();
        g.pose().scale(0.8f, 0.8f, 1.0f);
        Theme.text(g, font, "ID:", (int)((x + 8) / 0.8f), (int)((cy + 3) / 0.8f), Theme.TEXT_MUTED);
        Theme.text(g, font, "绑定:", (int)((x + 8 + 70) / 0.8f), (int)((cy + 3) / 0.8f), Theme.TEXT_MUTED);

        // 速率标签（右侧）
        int row2Y = cy + 20;
        int rateX = x + panelW - 8 - 60;
        String rateText = String.valueOf(cacheTransferRateLimit);
        Theme.text(g, font, rateText, (int)((rateX + 22) / 0.8f), (int)((row2Y + 3) / 0.8f), Theme.TEXT);
        g.pose().popPose();
    }

    private void renderConfigWidgets(GuiGraphics g, int mx, int my, float partialTick) {
        if (bindDropdown != null) bindDropdown.render(g, mx, my);
        if (transferToggle != null) transferToggle.render(g, mx, my, partialTick);
        if (voidToggle != null) voidToggle.render(g, mx, my, partialTick);
        if (rateDecButton != null) rateDecButton.render(g, mx, my, partialTick);
        if (rateIncButton != null) rateIncButton.render(g, mx, my, partialTick);
    }

    private void renderDraggedItem(GuiGraphics g, int mx, int my) {
        ItemStack carried = menu.getCarried();
        if (!carried.isEmpty()) {
            g.pose().pushPose();
            g.pose().translate(0, 0, 232);
            g.renderItem(carried, mx - 8, my - 8);
            g.renderItemDecorations(font, carried, mx - 8, my - 8, null);
            g.pose().popPose();
        }
    }

    protected void renderTooltip(GuiGraphics g, int mx, int my) {
        if (hoveredSlot != null && hoveredSlot.hasItem()) {
            g.renderTooltip(font, hoveredSlot.getItem(), mx, my);
        }
    }

    // ── 输入处理 ──

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        calculateLayout();
        int x = leftPos, y = topPos;

        if (Theme.inside(mx, my, x + chestAreaX, y + chestAreaY, chestAreaW, chestAreaH)) {
            int total = menu.totalRows();
            int max = Math.max(0, total - BaseChestMenu.CHEST_VISIBLE_ROWS);
            localScrollRow = Math.max(0, Math.min(max, localScrollRow + (sy > 0 ? -1 : 1)));
            sendScrollIfChanged();
            return true;
        }
        return super.mouseScrolled(mx, my, sx, sy);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        calculateLayout();

        if (bindDropdown != null && bindDropdown.mouseClicked(mx, my, button)) return true;
        if (transferToggle != null && transferToggle.mouseClicked(mx, my, button)) return true;
        if (voidToggle != null && voidToggle.mouseClicked(mx, my, button)) return true;
        if (rateDecButton != null && rateDecButton.mouseClicked(mx, my, button)) return true;
        if (rateIncButton != null && rateIncButton.mouseClicked(mx, my, button)) return true;

        if (idEditBox != null && idEditBox.isFocused() && !idEditBox.isMouseOver(mx, my)) {
            commitId();
        }

        return super.mouseClicked(mx, my, button);
    }

    private void commitId() {
        if (idEditBox == null) return;
        String v = idEditBox.getValue().trim();
        if (!v.isEmpty() && !v.equals(lastSentChestId)) {
            send(2, v);
            lastSentChestId = v;
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if ((keyCode == 257 || keyCode == 335) && idEditBox != null && idEditBox.isFocused()) {
            commitId();
            idEditBox.setFocused(false);
            return true;
        }
        if (idEditBox != null && idEditBox.isFocused()) {
            if (this.minecraft != null && this.minecraft.options.keyInventory.matches(keyCode, scanCode)) {
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void removed() {
        commitId();
        super.removed();
    }

    private void sendScrollIfChanged() {
        if (localScrollRow != lastSentScrollRow) {
            lastSentScrollRow = localScrollRow;
            send(7, String.valueOf(localScrollRow));
        }
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        sendScrollIfChanged();
    }
}