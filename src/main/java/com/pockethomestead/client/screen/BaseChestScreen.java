package com.pockethomestead.client.screen;

import com.pockethomestead.client.ui.Theme;
import com.pockethomestead.client.ui.widget.UiButton;
import com.pockethomestead.client.ui.widget.UiDropdown;
import com.pockethomestead.client.ui.widget.UiToggle;
import com.pockethomestead.config.ModConfig;
import com.pockethomestead.menu.BaseChestMenu;
import com.pockethomestead.network.ChestConfigPacket;
import com.pockethomestead.network.ChestSyncPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
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
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 箱子Screen — 蓝白主题，两页布局。
 *
 * 第0页（内容）：箱子区（1物品=1格，纯渲染）+ 玩家背包。
 * 第1页（配置）：箱子ID、绑定目标、传输开关、虚空开关。
 *
 * 箱子区无真实槽位，存取全部通过网络包；服务端 itemStorage 为唯一权威，
 * 客户端通过 ChestSyncPacket 接收物品快照 cacheItems。
 */
public abstract class BaseChestScreen<T extends BaseChestMenu> extends AbstractContainerScreen<T> {

    /** 当前页：0=内容，1=配置 */
    private int currentPage = 0;

    private int localScrollRow = 0;

    private String cacheChestId = "";
    private String cacheBoundTargetId = "";
    private boolean cacheTransferEnabled = false;
    private boolean cacheVoidModeEnabled = false;
    private int cacheSyncIntervalSeconds = 30;
    private int cacheNextTransferSeconds = 0;
    private int cacheMaxCapacity = 4096;
    private List<String> cacheAvailableBindings = List.of();

    // 倒计时本地递减：记录最后一次同步时的值与时间戳
    private long lastCountdownSyncMillis = 0L;
    private int lastCountdownValue = 0;

    // 无绑定在下拉中的固定标签与索引
    private static final String NONE_BINDING_LABEL = "无绑定";

    // 客户端物品快照（按数量从多到少排序）
    private final List<Map.Entry<Item, Integer>> cacheItems = new ArrayList<>();

    private EditBox idEditBox;
    private EditBox intervalEditBox;
    private UiDropdown bindDropdown;
    private UiToggle transferToggle;
    private UiToggle voidToggle;
    private UiButton pageButton;

    private String lastSentChestId = "";
    private int lastSentInterval = 30;

    // 悬停目标
    private Item hoveredChestItem = null;
    private int hoveredChestCount = 0;
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
        this.cacheChestId = p.chestId();
        this.cacheBoundTargetId = p.boundTargetId();
        this.cacheTransferEnabled = p.transferEnabled();
        this.cacheVoidModeEnabled = p.voidModeEnabled();
        this.cacheSyncIntervalSeconds = p.syncIntervalSeconds();
        this.cacheNextTransferSeconds = p.nextTransferSeconds();
        this.cacheMaxCapacity = p.maxCapacity();
        this.cacheAvailableBindings = p.availableBindings();
        this.lastSentChestId = p.chestId();
        // 记录倒计时基准
        this.lastCountdownValue = p.nextTransferSeconds();
        this.lastCountdownSyncMillis = System.currentTimeMillis();

        // 解析物品快照（按数量从多到少排序）
        cacheItems.clear();
        for (Map.Entry<String, Integer> e : p.items().entrySet()) {
            ResourceLocation loc = ResourceLocation.tryParse(e.getKey());
            if (loc != null) {
                Item it = BuiltInRegistries.ITEM.get(loc);
                if (it != Items.AIR) cacheItems.add(Map.entry(it, e.getValue()));
            }
        }
        cacheItems.sort(Comparator.<Map.Entry<Item, Integer>>comparingInt(Map.Entry::getValue).reversed());

        int maxRow = Math.max(0, totalRows() - BaseChestMenu.CHEST_VISIBLE_ROWS);
        if (localScrollRow > maxRow) localScrollRow = maxRow;

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

        // 翻页按钮（头部右侧，两页都显示）
        pageButton = new UiButton("配置 ▶", UiButton.Variant.SECONDARY)
                .bounds(gl + pageButtonX, gt + 2, pageButtonW, 14)
                .onClick(this::switchPage);

        rebuildPageWidgets();
    }

    /** 切换页面：离开配置页时先提交未保存的输入 */
    private void switchPage() {
        if (currentPage == 1) {
            commitId();
            commitInterval();
        }
        currentPage = (currentPage == 0) ? 1 : 0;
        rebuildPageWidgets();
    }

    /** 根据当前页重建控件（配置页才创建配置控件） */
    private void rebuildPageWidgets() {
        // 清理旧的可渲染控件
        if (idEditBox != null) { removeWidget(idEditBox); idEditBox = null; }
        if (intervalEditBox != null) { removeWidget(intervalEditBox); intervalEditBox = null; }
        bindDropdown = null;
        transferToggle = null;
        voidToggle = null;

        if (pageButton != null) {
            pageButton.label(currentPage == 0 ? "配置 ▶" : "◀ 内容");
        }

        if (currentPage == 1) {
            buildConfigWidgets(leftPos, topPos);
        }
    }

    /** 构建配置页控件 */
    private void buildConfigWidgets(int gl, int gt) {
        int availW = panelW - 2 * BaseChestMenu.PANEL_PADDING;
        int cy = gt + BaseChestMenu.HEADER_HEIGHT + BaseChestMenu.SECTION_GAP;

        // ── 第一行：ID + 绑定 ──
        int idLabelW = font.width("ID:");
        int bindLabelW = font.width("绑定:");
        int row1Gap = 6;
        int row1Available = availW - idLabelW - bindLabelW - row1Gap - 8;
        int idBoxW = Math.max(48, row1Available * 40 / 100);
        int bindW = Math.max(56, row1Available - idBoxW);

        int idBoxX = gl + BaseChestMenu.PANEL_PADDING + idLabelW + 4;
        idEditBox = new EditBox(font, idBoxX, cy, idBoxW, 14, Component.literal("ID"));
        idEditBox.setMaxLength(32);
        idEditBox.setValue(cacheChestId.isEmpty() ? "" : cacheChestId);
        idEditBox.setHint(Component.literal("ID"));
        this.addRenderableWidget(idEditBox);

        // 绑定下拉：第一项固定“无绑定”，其后为可绑定的对端箱子
        List<String> bindOptions = buildBindOptions();
        int selectedIdx = bindSelectionIndex();

        int bindX = idBoxX + idBoxW + row1Gap + bindLabelW + 4;
        bindDropdown = new UiDropdown(bindOptions, selectedIdx)
                .bounds(bindX, cy - 1, bindW, 16)
                .onSelect(idx -> {
                    // idx 0 = 无绑定（解绑）；idx i>0 = 绑定第 i-1 个可选项
                    if (idx == 0) {
                        send(3, "");
                    } else {
                        int real = idx - 1;
                        if (real >= 0 && real < cacheAvailableBindings.size()) {
                            send(3, cacheAvailableBindings.get(real));
                        }
                    }
                });

        // ── 第二行：传输 + 虚空 ──
        int row2Y = cy + 22;
        int row2Gap = 4;
        int toggleW = Math.max(40, (availW - row2Gap) / 2);
        boolean voidEnabledGlobally = ModConfig.VOID_ENABLED.get();

        int t1X = gl + BaseChestMenu.PANEL_PADDING;
        transferToggle = new UiToggle("传输", cacheTransferEnabled)
                .bounds(t1X, row2Y, toggleW, 16)
                .onChange(v -> send(0, ""));

        int t2X = t1X + toggleW + row2Gap;
        voidToggle = new UiToggle("虚空", cacheVoidModeEnabled)
                .bounds(t2X, row2Y, toggleW, 16)
                .onChange(v -> send(1, ""));
        voidToggle.setDisabled(!voidEnabledGlobally);

        // ── 第三行：同步间隔输入 ──
        int row3Y = row2Y + 22;
        int intervalLabelW = font.width("同步(秒):");
        int intervalBoxX = gl + BaseChestMenu.PANEL_PADDING + intervalLabelW + 4;
        intervalEditBox = new EditBox(font, intervalBoxX, row3Y, 40, 14, Component.literal("间隔"));
        intervalEditBox.setMaxLength(4);
        intervalEditBox.setValue(String.valueOf(cacheSyncIntervalSeconds));
        intervalEditBox.setHint(Component.literal("秒"));
        this.addRenderableWidget(intervalEditBox);

        refreshWidgets();
    }

    /** 绑定下拉选项：[无绑定, ...可绑定对端] */
    private List<String> buildBindOptions() {
        List<String> opts = new ArrayList<>();
        opts.add(NONE_BINDING_LABEL);
        opts.addAll(cacheAvailableBindings);
        return opts;
    }

    /** 当前绑定在下拉中的索引（无绑定=0） */
    private int bindSelectionIndex() {
        if (cacheBoundTargetId.isEmpty()) return 0;
        int i = cacheAvailableBindings.indexOf(cacheBoundTargetId);
        return i < 0 ? 0 : i + 1;
    }

    private void refreshWidgets() {
        if (idEditBox != null && !idEditBox.isFocused()) {
            idEditBox.setValue(cacheChestId.isEmpty() ? "" : cacheChestId);
        }
        if (intervalEditBox != null && !intervalEditBox.isFocused()) {
            intervalEditBox.setValue(String.valueOf(cacheSyncIntervalSeconds));
        }
        if (bindDropdown != null) {
            bindDropdown.setLabels(buildBindOptions());
            bindDropdown.setSelected(bindSelectionIndex());
        }
        if (transferToggle != null) transferToggle.setValue(cacheTransferEnabled);
        if (voidToggle != null) {
            voidToggle.setValue(cacheVoidModeEnabled);
            voidToggle.setDisabled(!ModConfig.VOID_ENABLED.get());
        }
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
            renderPlayerInventorySlots(g, mx, my);
        } else {
            renderConfigPage(g, mx, my, partialTick);
        }

        renderLabels(g);
        if (pageButton != null) pageButton.render(g, mx, my, partialTick);
        renderDraggedItem(g, mx, my);
        renderHoverTooltip(g, mx, my);

        // 下拉弹层最顶层
        if (currentPage == 1 && bindDropdown != null) bindDropdown.renderPopup(g, mx, my);
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
        hoveredChestItem = null;
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
                    Map.Entry<Item, Integer> entry = cacheItems.get(dataIdx);
                    Item item = entry.getKey();
                    int count = entry.getValue();

                    g.renderItem(new ItemStack(item), slotX, slotY);
                    renderCountText(g, BaseChestMenu.formatCount(count), slotX, slotY);

                    if (hovered) {
                        hoveredChestItem = item;
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
        int gl = leftPos;
        int cy = topPos + BaseChestMenu.HEADER_HEIGHT + BaseChestMenu.SECTION_GAP;
        int availW = panelW - 2 * BaseChestMenu.PANEL_PADDING;

        // 第一行标签
        Theme.text(g, font, "ID:", gl + BaseChestMenu.PANEL_PADDING, cy + 4, Theme.TEXT);
        if (idEditBox != null) idEditBox.render(g, mx, my, partialTick);

        int idLabelW = font.width("ID:");
        int bindLabelW = font.width("绑定:");
        int row1Gap = 6;
        int idBoxW = Math.max(48, (availW - idLabelW - bindLabelW - row1Gap - 8) * 40 / 100);
        int bindLabelX = gl + BaseChestMenu.PANEL_PADDING + idLabelW + 4 + idBoxW + row1Gap;
        Theme.text(g, font, "绑定:", bindLabelX, cy + 4, Theme.TEXT);
        if (bindDropdown != null) bindDropdown.render(g, mx, my);

        // 第二行
        if (transferToggle != null) transferToggle.render(g, mx, my, partialTick);
        if (voidToggle != null) voidToggle.render(g, mx, my, partialTick);

        // 第三行：同步间隔输入
        int row2Y = cy + 22;
        int row3Y = row2Y + 22;
        Theme.text(g, font, "同步(秒):", gl + BaseChestMenu.PANEL_PADDING, row3Y + 4, Theme.TEXT);
        if (intervalEditBox != null) intervalEditBox.render(g, mx, my, partialTick);

        // 倒计时小字 + 状态信息
        int infoY = row3Y + 20;
        String bindInfo = cacheBoundTargetId.isEmpty()
            ? "未绑定目标箱子" : "已绑定: " + cacheBoundTargetId;
        Theme.text(g, font, bindInfo, gl + BaseChestMenu.PANEL_PADDING, infoY, Theme.TEXT_MUTED);

        String countdownInfo;
        if (cacheBoundTargetId.isEmpty()) {
            countdownInfo = "传输未启用（未绑定）";
        } else if (!cacheTransferEnabled) {
            countdownInfo = "传输已关闭";
        } else {
            countdownInfo = "下次传输: " + liveCountdownSeconds() + " 秒后";
        }
        Theme.text(g, font, countdownInfo, gl + BaseChestMenu.PANEL_PADDING, infoY + 12, Theme.TEXT_MUTED);

        if (!ModConfig.VOID_ENABLED.get()) {
            Theme.text(g, font, "虚空产出已禁用", gl + BaseChestMenu.PANEL_PADDING, infoY + 24, Theme.TEXT_FAINT);
        }
    }

    /** 本地递减的倒计时秒数（基于最后一次同步值 - 已过秒数） */
    private int liveCountdownSeconds() {
        if (lastCountdownValue <= 0) return 0;
        long elapsed = (System.currentTimeMillis() - lastCountdownSyncMillis) / 1000L;
        int v = lastCountdownValue - (int) elapsed;
        return Math.max(0, v);
    }

    /** 标题与分区标签 */
    private void renderLabels(GuiGraphics g) {
        int x = leftPos, y = topPos;
        Theme.text(g, font, title.getString(), x + titleLabelX, y + titleLabelY, Theme.TEXT);

        if (currentPage == 0) {
            // 容量信息（头部右侧，翻页按钮左侧）— 用服务端同步的 maxCapacity
            int used = cacheItems.stream().mapToInt(Map.Entry::getValue).sum();
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
        } else if (hoveredChestItem != null) {
            ItemStack stack = new ItemStack(hoveredChestItem);
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
        // 配置页：下拉弹层滚动
        if (currentPage == 1 && bindDropdown != null && bindDropdown.popupMouseScrolled(mx, my, sy)) {
            return true;
        }
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
        // 下拉弹层打开时优先处理（修复点击选项无法切换的 bug）
        if (currentPage == 1 && bindDropdown != null && bindDropdown.isOpen()
                && bindDropdown.popupMouseClicked(mx, my, button)) {
            return true;
        }

        // 翻页按钮
        if (pageButton != null && pageButton.mouseClicked(mx, my, button)) return true;

        if (currentPage == 1) {
            // 配置页控件
            if (bindDropdown != null && bindDropdown.mouseClicked(mx, my, button)) return true;
            handleEditBoxFocus(idEditBox, mx, my, button);
            handleEditBoxFocus(intervalEditBox, mx, my, button);
            if (transferToggle != null && transferToggle.mouseClicked(mx, my, button)) return true;
            if (voidToggle != null && voidToggle.mouseClicked(mx, my, button)) return true;
            return true; // 配置页消费点击，不触达（未渲染的）背包槽
        }

        // 内容页：控件（无）→ 存货区交互
        if (isInChestArea(mx, my)) {
            int idx = chestSlotAt(mx, my);
            boolean shift = hasShiftDown();
            boolean carrying = !menu.getCarried().isEmpty();

            if (carrying) {
                send(button == 1 ? 12 : 8, "");   // 右键放一个，左键放全部
            } else if (idx >= 0 && idx < cacheItems.size()) {
                String itemId = BuiltInRegistries.ITEM.getKey(cacheItems.get(idx).getKey()).toString();
                if (shift) {
                    send(11, itemId);             // Shift → 取一组到背包
                } else if (button == 1) {
                    send(10, itemId);             // 右键 → 取一个到手持
                } else {
                    send(9, itemId);              // 左键 → 取一组到手持
                }
            }
            return true;
        }

        return super.mouseClicked(mx, my, button);
    }

    /** 根据点击命中处理 EditBox 的聚焦/失焦 */
    private void handleEditBoxFocus(EditBox box, double mx, double my, int button) {
        if (box == null) return;
        boolean hit = mx >= box.getX() && mx < box.getX() + box.getWidth()
                && my >= box.getY() && my < box.getY() + box.getHeight();
        box.setFocused(hit);
        if (hit) box.mouseClicked(mx, my, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (idEditBox != null && idEditBox.isFocused()) {
            if (keyCode == 257 || keyCode == 335) { commitId(); return true; }
            if (idEditBox.keyPressed(keyCode, scanCode, modifiers)) return true;
        }
        if (intervalEditBox != null && intervalEditBox.isFocused()) {
            if (keyCode == 257 || keyCode == 335) { commitInterval(); return true; }
            if (intervalEditBox.keyPressed(keyCode, scanCode, modifiers)) return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        if (idEditBox != null && idEditBox.isFocused() && idEditBox.charTyped(c, modifiers)) return true;
        if (intervalEditBox != null && intervalEditBox.isFocused()) {
            // 间隔输入仅允许数字
            if (Character.isDigit(c) && intervalEditBox.charTyped(c, modifiers)) return true;
            return true; // 拒绝非数字字符但消费事件
        }
        return super.charTyped(c, modifiers);
    }

    private void commitId() {
        if (idEditBox == null) return;
        String v = idEditBox.getValue().trim();
        if (!v.isEmpty() && !v.equals(lastSentChestId)) {
            send(2, v);
            lastSentChestId = v;
        }
        idEditBox.setFocused(false);
    }

    private void commitInterval() {
        if (intervalEditBox == null) return;
        String v = intervalEditBox.getValue().trim();
        if (v.isEmpty()) {
            intervalEditBox.setValue(String.valueOf(cacheSyncIntervalSeconds));
            return;
        }
        try {
            int seconds = Math.max(1, Math.min(3600, Integer.parseInt(v)));
            if (seconds != lastSentInterval) {
                send(13, String.valueOf(seconds));
                lastSentInterval = seconds;
            }
        } catch (NumberFormatException ignored) {
            intervalEditBox.setValue(String.valueOf(cacheSyncIntervalSeconds));
        }
        intervalEditBox.setFocused(false);
    }

    @Override
    public void removed() {
        commitId();
        commitInterval();
        super.removed();
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mx, int my) {
        // 标签由自定义 renderLabels(g) 处理，禁用 vanilla 标签
    }
}
