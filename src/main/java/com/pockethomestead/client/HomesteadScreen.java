package com.pockethomestead.client;

import com.pockethomestead.client.page.CreatePage;
import com.pockethomestead.client.page.ManagePage;
import com.pockethomestead.client.page.MigrationPage;
import com.pockethomestead.client.page.PermissionsPage;
import com.pockethomestead.client.page.ProductionStatsPage;
import com.pockethomestead.client.page.TabletChestPage;
import com.pockethomestead.client.ui.HomesteadTabletGuiTextures;
import com.pockethomestead.client.ui.Page;
import com.pockethomestead.client.ui.Router;
import com.pockethomestead.client.ui.Theme;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 口袋家园主界面：固定宽高比自适应窗口大小、带左侧导航栏与页面 router 的根 Screen。
 */
public class HomesteadScreen extends Screen {

    private static final float PANEL_ASPECT = 1.6f;
    private static final int NAV_WIDTH_MIN = 40;
    private static final int NAV_WIDTH_MAX = 48;
    private static final int NAV_ITEM_SIZE = 30;
    private static final int NAV_ITEM_GAP = 4;
    private static final int NAV_TOP_PAD = 6;

    // 跨开启持久化的状态（敏感页面不持久化，避免信息短暂泄露）
    private static String lastPageId = "tablet_chest";
    private static final String SAFE_DEFAULT_PAGE = "tablet_chest";

    private final Router router = new Router();

    // 面板布局（由 layout() 计算）
    private int panelX, panelY, panelW, panelH;
    private int headerH, sidebarW;
    private int contentX, contentY, contentW, contentH;

    private long openedAt;
    private boolean enteredOnce = false;
    private String pendingTooltip = null;

    public HomesteadScreen() { this(null); }

    public HomesteadScreen(String initialPageId) {
        super(Component.translatable("pockethomestead.ui.title"));
        router.register(new TabletChestPage());
        router.register(new CreatePage());
        router.register(new ManagePage());
        router.register(new PermissionsPage());
        router.register(new ProductionStatsPage());
        router.register(new MigrationPage());
        String initial = initialPageId != null ? initialPageId : safeLastPageId();
        router.selectInitial(initial);
    }

    /** 权限页含其他玩家权限信息，不跨会话持久化，避免下次打开短暂显示旧数据。 */
    private static String safeLastPageId() {
        return "permissions".equals(lastPageId) ? SAFE_DEFAULT_PAGE : lastPageId;
    }

    @Override
    protected void init() {
        openedAt = System.currentTimeMillis();
        layout();
        if (!enteredOnce) {
            Page cur = router.current();
            if (cur != null) cur.onEnter();
            enteredOnce = true;
        }
    }

    private void layout() {
        int maxW = Math.max(240, width - 16);
        int maxH = Math.max(180, height - 16);
        panelW = Math.min(maxW, Math.round(maxH * PANEL_ASPECT));
        panelH = Math.round(panelW / PANEL_ASPECT);
        if (panelH > maxH) {
            panelH = maxH;
            panelW = Math.round(panelH * PANEL_ASPECT);
        }
        panelW = clamp(panelW, 240, maxW);
        panelH = clamp(panelH, 180, maxH);
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;

        headerH = 26;
        sidebarW = clamp(Math.round(panelW * 0.045f), NAV_WIDTH_MIN, NAV_WIDTH_MAX);

        contentX = panelX + sidebarW + 1;
        contentY = panelY + headerH + 1;
        contentW = panelW - sidebarW - 2;
        contentH = panelH - headerH - 2;
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override
    public void removed() {
        Page cur = router.current();
        if (cur != null) cur.onExit();
        super.removed();
    }

    @Override
    public void tick() {
        Page cur = router.current();
        if (cur != null) cur.tick();
    }

    // ------------------------------------------------------------------
    // 渲染
    // ------------------------------------------------------------------
    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        pendingTooltip = null;
        renderBackground(g, mouseX, mouseY, partialTick); // 默认模糊 + 暗化背景

        // 开场轻微上浮动画
        float t = Math.min(1f, (System.currentTimeMillis() - openedAt) / 180f);
        int pop = (int) ((1f - t) * 10);

        g.pose().pushPose();
        g.pose().translate(0, pop, 0);

        // 面板
        HomesteadTabletGuiTextures.shadow(g, panelX, panelY, panelW, panelH);
        HomesteadTabletGuiTextures.panel(g, panelX, panelY, panelW, panelH);

        renderHeader(g, mouseX, mouseY);
        renderSidebar(g, mouseX, mouseY);

        // 内容区
        Page cur = router.current();
        if (cur != null) {
            cur.onLayout(contentX, contentY, contentW, contentH);
            cur.render(g, mouseX, mouseY, partialTick);
            cur.renderOverlay(g, mouseX, mouseY, partialTick);
        }

        // 工具提示（最上层）
        if (pendingTooltip != null) drawTooltip(g, pendingTooltip, mouseX, mouseY);

        g.pose().popPose();
    }

    private void renderHeader(GuiGraphics g, int mouseX, int mouseY) {
        // 标题
        g.drawString(font, Theme.styled(getTitle().getString()), panelX + Theme.PAD, panelY + (headerH - font.lineHeight) / 2 + 1, Theme.TEXT, false);
        HomesteadTabletGuiTextures.hDivider(g, panelX + 1, panelY + headerH, panelW - 2);

        // 窗口控件：[关闭]
        int sz = 18;
        int closeX = panelX + panelW - Theme.PAD + 4 - sz;
        int cy = panelY + (headerH - sz) / 2;

        drawCloseControl(g, closeX, cy, sz, mouseX, mouseY);
    }

    private void drawCloseControl(GuiGraphics g, int x, int y, int sz, int mouseX, int mouseY) {
        boolean hover = Theme.inside(mouseX, mouseY, x, y, sz, sz);
        if (hover) {
            HomesteadTabletGuiTextures.button(g, x, y, sz, sz, HomesteadTabletGuiTextures.ButtonState.DANGER);
            pendingTooltip = Component.translatable("pockethomestead.ui.close").getString();
        }
        HomesteadTabletGuiTextures.icon(g, HomesteadTabletGuiTextures.Icon.CLOSE, x + 2, y + 2, sz - 4);
    }

    private void renderSidebar(GuiGraphics g, int mouseX, int mouseY) {
        // 分隔线
        HomesteadTabletGuiTextures.vDivider(g, panelX + sidebarW, panelY + headerH + 1, panelH - headerH - 2);

        int itemSize = navItemSize();
        int top = panelY + headerH + NAV_TOP_PAD;
        for (int i = 0; i < router.pages().size(); i++) {
            Page p = router.pages().get(i);
            int iy = top + i * (itemSize + NAV_ITEM_GAP);
            int ix = panelX + (sidebarW - itemSize) / 2;
            boolean active = router.currentIndex() == i;
            boolean hover = Theme.inside(mouseX, mouseY, ix, iy, itemSize, itemSize);

            HomesteadTabletGuiTextures.button(g, ix, iy, itemSize, itemSize,
                    active ? HomesteadTabletGuiTextures.ButtonState.SELECTED
                            : hover ? HomesteadTabletGuiTextures.ButtonState.HOVER
                            : HomesteadTabletGuiTextures.ButtonState.NORMAL);
            if (active) {
                HomesteadTabletGuiTextures.hDivider(g, ix + 5, iy + itemSize - 4, itemSize - 10);
            }
            drawNavIcon(g, p, ix, iy, itemSize);
            if (hover) pendingTooltip = p.navTitle();
        }
    }

    private void drawNavIcon(GuiGraphics g, Page page, int x, int y, int size) {
        HomesteadTabletGuiTextures.Icon icon = switch (page.id()) {
            case "create" -> HomesteadTabletGuiTextures.Icon.CREATE;
            case "permissions" -> HomesteadTabletGuiTextures.Icon.PERMISSIONS;
            case "production" -> HomesteadTabletGuiTextures.Icon.PRODUCTION;
            case "migration" -> HomesteadTabletGuiTextures.Icon.MIGRATION;
            case "tablet_chest" -> HomesteadTabletGuiTextures.Icon.MANAGE;
            default -> HomesteadTabletGuiTextures.Icon.MANAGE;
        };
        int iconSize = Math.max(16, Math.min(20, size - 10));
        HomesteadTabletGuiTextures.icon(g, icon, x + (size - iconSize) / 2, y + (size - iconSize) / 2, iconSize);
    }

    private void drawTooltip(GuiGraphics g, String text, int mouseX, int mouseY) {
        int tw = Theme.styledWidth(font, text);
        int bx = mouseX + 10;
        int by = mouseY - 14;
        if (bx + tw + 8 > width) bx = width - tw - 8;
        Theme.shadow(g, bx, by, tw + 8, 16, Theme.RADIUS);
        Theme.panel(g, bx, by, tw + 8, 16, Theme.RADIUS, Theme.TEXT, Theme.TEXT);
        g.drawString(font, Theme.styled(text), bx + 4, by + 4, 0xFFFFFFFF, false);
    }

    // ------------------------------------------------------------------
    // 输入
    // ------------------------------------------------------------------
    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        Page cur = router.current();
        // 1. overlay（如展开的下拉）优先
        if (cur != null && cur.overlayMouseClicked(mx, my, button)) return true;

        // 2. 窗口控件
        int sz = 18;
        int closeX = panelX + panelW - Theme.PAD + 4 - sz;
        int cy = panelY + (headerH - sz) / 2;
        if (button == 0 && Theme.inside(mx, my, closeX, cy, sz, sz)) { onClose(); return true; }

        // 3. 侧边栏导航
        int itemSize = navItemSize();
        int top = panelY + headerH + NAV_TOP_PAD;
        for (int i = 0; i < router.pages().size(); i++) {
            int iy = top + i * (itemSize + NAV_ITEM_GAP);
            int ix = panelX + (sidebarW - itemSize) / 2;
            if (button == 0 && Theme.inside(mx, my, ix, iy, itemSize, itemSize)) {
                router.setActive(i);
                lastPageId = router.current().id();
                return true;
            }
        }

        // 4. 当前页
        if (cur != null && cur.mouseClicked(mx, my, button)) return true;
        return super.mouseClicked(mx, my, button);
    }
    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        Page cur = router.current();
        if (cur != null && cur.mouseScrolled(mx, my, sx, sy)) return true;
        return super.mouseScrolled(mx, my, sx, sy);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dragX, double dragY) {
        Page cur = router.current();
        if (cur != null && cur.mouseDragged(mx, my, button, dragX, dragY)) return true;
        return super.mouseDragged(mx, my, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        Page cur = router.current();
        if (cur != null && cur.mouseReleased(mx, my, button)) return true;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        Page cur = router.current();
        if (cur != null && cur.keyPressed(keyCode, scanCode, modifiers)) return true;
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        Page cur = router.current();
        if (cur != null && cur.charTyped(codePoint, modifiers)) return true;
        return super.charTyped(codePoint, modifiers);
    }

    private int navItemSize() {
        int count = Math.max(1, router.pages().size());
        int availableH = Math.max(20, panelH - headerH - NAV_TOP_PAD * 2 - (count - 1) * NAV_ITEM_GAP);
        int byHeight = availableH / count;
        return Math.min(NAV_ITEM_SIZE, Math.max(20, Math.min(sidebarW - 12, byHeight)));
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
