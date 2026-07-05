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
 * 口袋家园主界面：固定逻辑坐标系、整体缩放居中。
 *
 * 所有页面都在同一套设计尺寸里布局，窗口尺寸变化只改变整块 UI 的缩放/居中，
 * 不再让页面内部控件随窗口重新计算相对位置。
 */
public class HomesteadScreen extends Screen {

    private static final int DESIGN_PANEL_W = 600;
    private static final int DESIGN_PANEL_H = 375;
    private static final int DESIGN_MARGIN = 8;
    private static final float TARGET_UI_SCALE = 2.0f;
    private static final int NAV_WIDTH = 44;
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
    private float uiScale = 1.0f;
    private int logicalWidth;
    private int logicalHeight;

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
        layout();
        if (!enteredOnce) {
            Page cur = router.current();
            if (cur != null) cur.onEnter();
            enteredOnce = true;
        }
    }

    private void layout() {
        float fitScaleX = (width - DESIGN_MARGIN * 2) / (float) DESIGN_PANEL_W;
        float fitScaleY = (height - DESIGN_MARGIN * 2) / (float) DESIGN_PANEL_H;
        uiScale = Math.max(0.10f, Math.min(TARGET_UI_SCALE, Math.min(fitScaleX, fitScaleY)));
        logicalWidth = Math.round(width / uiScale);
        logicalHeight = Math.round(height / uiScale);

        panelW = DESIGN_PANEL_W;
        panelH = DESIGN_PANEL_H;
        panelX = (logicalWidth - panelW) / 2;
        panelY = (logicalHeight - panelH) / 2;

        headerH = 26;
        sidebarW = NAV_WIDTH;

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
        layout();
        pendingTooltip = null;
        renderBackground(g, mouseX, mouseY, partialTick); // 默认模糊 + 暗化背景
        int logicalMouseX = toLogical(mouseX);
        int logicalMouseY = toLogical(mouseY);

        g.pose().pushPose();
        g.pose().scale(uiScale, uiScale, 1.0f);
        Theme.scissorScale(uiScale);

        // 面板
        HomesteadTabletGuiTextures.shadow(g, panelX, panelY, panelW, panelH);
        HomesteadTabletGuiTextures.panel(g, panelX, panelY, panelW, panelH);

        renderHeader(g, logicalMouseX, logicalMouseY);
        renderSidebar(g, logicalMouseX, logicalMouseY);

        // 内容区
        Page cur = router.current();
        if (cur != null) {
            cur.onLayout(contentX, contentY, contentW, contentH);
            cur.render(g, logicalMouseX, logicalMouseY, partialTick);
            cur.renderOverlay(g, logicalMouseX, logicalMouseY, partialTick);
        }

        // 工具提示（最上层）
        if (pendingTooltip != null) drawTooltip(g, pendingTooltip, logicalMouseX, logicalMouseY);

        Theme.resetScissorScale();
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
            case "tablet_chest" -> HomesteadTabletGuiTextures.Icon.TABLET_CHEST;
            default -> HomesteadTabletGuiTextures.Icon.MANAGE;
        };
        int iconSize = Math.max(16, Math.min(20, size - 10));
        HomesteadTabletGuiTextures.icon(g, icon, x + (size - iconSize) / 2, y + (size - iconSize) / 2, iconSize);
    }

    private void drawTooltip(GuiGraphics g, String text, int mouseX, int mouseY) {
        int tw = Theme.styledWidth(font, text);
        int bx = mouseX + 10;
        int by = mouseY - 14;
        if (bx + tw + 8 > logicalWidth) bx = logicalWidth - tw - 8;
        Theme.shadow(g, bx, by, tw + 8, 16, Theme.RADIUS);
        Theme.panel(g, bx, by, tw + 8, 16, Theme.RADIUS, Theme.TEXT, Theme.TEXT);
        g.drawString(font, Theme.styled(text), bx + 4, by + 4, 0xFFFFFFFF, false);
    }

    // ------------------------------------------------------------------
    // 输入
    // ------------------------------------------------------------------
    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        layout();
        double lmx = toLogical(mx);
        double lmy = toLogical(my);
        Page cur = router.current();
        // 1. overlay（如展开的下拉）优先
        if (cur != null && cur.overlayMouseClicked(lmx, lmy, button)) return true;

        // 2. 窗口控件
        int sz = 18;
        int closeX = panelX + panelW - Theme.PAD + 4 - sz;
        int cy = panelY + (headerH - sz) / 2;
        if (button == 0 && Theme.inside(lmx, lmy, closeX, cy, sz, sz)) { onClose(); return true; }

        // 3. 侧边栏导航
        int itemSize = navItemSize();
        int top = panelY + headerH + NAV_TOP_PAD;
        for (int i = 0; i < router.pages().size(); i++) {
            int iy = top + i * (itemSize + NAV_ITEM_GAP);
            int ix = panelX + (sidebarW - itemSize) / 2;
            if (button == 0 && Theme.inside(lmx, lmy, ix, iy, itemSize, itemSize)) {
                router.setActive(i);
                lastPageId = router.current().id();
                return true;
            }
        }

        // 4. 当前页
        if (cur != null && cur.mouseClicked(lmx, lmy, button)) return true;
        return super.mouseClicked(mx, my, button);
    }
    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        layout();
        Page cur = router.current();
        if (cur != null && cur.mouseScrolled(toLogical(mx), toLogical(my), sx, sy)) return true;
        return super.mouseScrolled(mx, my, sx, sy);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dragX, double dragY) {
        layout();
        Page cur = router.current();
        if (cur != null && cur.mouseDragged(toLogical(mx), toLogical(my), button, dragX / uiScale, dragY / uiScale)) return true;
        return super.mouseDragged(mx, my, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        layout();
        Page cur = router.current();
        if (cur != null && cur.mouseReleased(toLogical(mx), toLogical(my), button)) return true;
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
        return NAV_ITEM_SIZE;
    }

    private int toLogical(int value) {
        return Math.round(value / uiScale);
    }

    private double toLogical(double value) {
        return value / uiScale;
    }
}
