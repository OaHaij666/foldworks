package com.pockethomestead.client;

import com.pockethomestead.client.page.CreatePage;
import com.pockethomestead.client.page.ManagePage;
import com.pockethomestead.client.page.ProductionStatsPage;
import com.pockethomestead.client.ui.Page;
import com.pockethomestead.client.ui.Router;
import com.pockethomestead.client.ui.Theme;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * 口袋家园主界面：自适应窗口大小、带左侧导航栏与页面 router 的根 Screen。
 * 支持 Ctrl+滚轮 / Ctrl +/− 缩放、Ctrl+0 复位。
 */
public class HomesteadScreen extends Screen {

    private static final float MIN_SCALE = 0.6f;
    private static final float MAX_SCALE = 1.6f;
    private static final float STEP = 0.1f;
    private static final int NAV_WIDTH_MIN = 46;
    private static final int NAV_WIDTH_MAX = 56;
    private static final int NAV_ITEM_SIZE = 34;
    private static final int NAV_ITEM_GAP = 6;
    private static final int NAV_TOP_PAD = 10;

    // 跨开启持久化的状态
    private static float uiScale = MAX_SCALE;
    private static String lastPageId = "manage";

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
        router.register(new CreatePage());
        router.register(new ManagePage());
        router.register(new ProductionStatsPage());
        router.selectInitial(initialPageId != null ? initialPageId : lastPageId);
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
        panelW = clamp(Math.round(width * 0.52f * uiScale), 240, width - 16);
        panelH = clamp(Math.round(height * 0.60f * uiScale), 180, height - 16);
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;

        headerH = 34;
        sidebarW = clamp(Math.round(panelW * 0.045f), NAV_WIDTH_MIN, NAV_WIDTH_MAX);

        contentX = panelX + sidebarW + 1;
        contentY = panelY + headerH + 1;
        contentW = panelW - sidebarW - 2;
        contentH = panelH - headerH - 2;
    }

    @Override public boolean isPauseScreen() { return false; }

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
        Theme.shadow(g, panelX, panelY, panelW, panelH, Theme.RADIUS + 2);
        Theme.panel(g, panelX, panelY, panelW, panelH, Theme.RADIUS + 2, Theme.SURFACE, Theme.BORDER);

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
        Theme.hLine(g, panelX + 1, panelY + headerH, panelW - 2, Theme.DIVIDER);

        // 窗口控件：[−][＋][✕]
        int sz = 18, gap = 4;
        int closeX = panelX + panelW - Theme.PAD + 4 - sz;
        int plusX = closeX - gap - sz;
        int minusX = plusX - gap - sz;
        int cy = panelY + (headerH - sz) / 2;

        boolean atMin = uiScale <= MIN_SCALE + 1e-3;
        boolean atMax = uiScale >= MAX_SCALE - 1e-3;
        drawControl(g, minusX, cy, sz, "−", mouseX, mouseY, false, atMin,
                Component.translatable("pockethomestead.ui.zoom_out").getString());
        drawControl(g, plusX, cy, sz, "＋", mouseX, mouseY, false, atMax,
                Component.translatable("pockethomestead.ui.zoom_in").getString());
        drawControl(g, closeX, cy, sz, "✕", mouseX, mouseY, true, false,
                Component.translatable("pockethomestead.ui.close").getString());
    }

    private void drawControl(GuiGraphics g, int x, int y, int sz, String sym, int mouseX, int mouseY,
                             boolean danger, boolean disabled, String tooltip) {
        boolean hover = !disabled && Theme.inside(mouseX, mouseY, x, y, sz, sz);
        if (hover) {
            Theme.fillRound(g, x, y, sz, sz, Theme.RADIUS, danger ? Theme.DANGER_SOFT : Theme.SURFACE_ALT);
            pendingTooltip = tooltip;
        }
        int col = disabled ? Theme.TEXT_FAINT : (hover ? (danger ? Theme.DANGER : Theme.PRIMARY_PRESS) : Theme.TEXT_MUTED);
        Theme.textInBox(g, font, sym, x, y, sz, sz, col);
    }

    private void renderSidebar(GuiGraphics g, int mouseX, int mouseY) {
        // 分隔线
        Theme.vLine(g, panelX + sidebarW, panelY + headerH + 1, panelH - headerH - 2, Theme.DIVIDER);

        int itemSize = navItemSize();
        int top = panelY + headerH + NAV_TOP_PAD;
        for (int i = 0; i < router.pages().size(); i++) {
            Page p = router.pages().get(i);
            int iy = top + i * (itemSize + NAV_ITEM_GAP);
            int ix = panelX + (sidebarW - itemSize) / 2;
            boolean active = router.currentIndex() == i;
            boolean hover = Theme.inside(mouseX, mouseY, ix, iy, itemSize, itemSize);

            if (active) {
                Theme.fillRound(g, ix, iy, itemSize, itemSize, Theme.RADIUS + 1, Theme.PRIMARY_SOFT);
                Theme.fillRound(g, ix + 5, iy + itemSize - 4, itemSize - 10, 2, 1, Theme.PRIMARY);
            } else if (hover) {
                Theme.fillRound(g, ix, iy, itemSize, itemSize, Theme.RADIUS + 1, Theme.SURFACE_ALT);
            }
            int col = active ? Theme.PRIMARY_PRESS : (hover ? Theme.TEXT : Theme.TEXT_MUTED);
            drawNavIcon(g, p, ix, iy, itemSize, col);
            if (hover) pendingTooltip = p.navTitle();
        }
    }

    private void drawNavIcon(GuiGraphics g, Page page, int x, int y, int size, int color) {
        int cx = x + size / 2;
        int cy = y + size / 2;
        switch (page.id()) {
            case "create" -> {
                int len = 16;
                int thick = 3;
                Theme.fillRound(g, cx - len / 2, cy - thick / 2, len, thick, 1, color);
                Theme.fillRound(g, cx - thick / 2, cy - len / 2, thick, len, 1, color);
            }
            case "manage" -> {
                int dot = 5;
                int gap = 5;
                int startX = cx - dot - gap / 2;
                int startY = cy - dot - gap / 2;
                Theme.fillRound(g, startX, startY, dot, dot, 2, color);
                Theme.fillRound(g, startX + dot + gap, startY, dot, dot, 2, color);
                Theme.fillRound(g, startX, startY + dot + gap, dot, dot, 2, color);
                Theme.fillRound(g, startX + dot + gap, startY + dot + gap, dot, dot, 2, color);
            }
            case "production" -> {
                int cell = 4;
                int gap = 3;
                int startX = cx - (cell * 3 + gap * 2) / 2;
                int startY = cy - (cell * 3 + gap * 2) / 2;
                for (int row = 0; row < 3; row++) {
                    for (int col = 0; col < 3; col++) {
                        Theme.fillRound(g, startX + col * (cell + gap), startY + row * (cell + gap), cell, cell, 1, color);
                    }
                }
            }
            default -> {
                String icon = page.navIcon();
                g.drawString(font, icon, x + (size - font.width(icon)) / 2, y + (size - font.lineHeight) / 2 + 1, color, false);
            }
        }
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
        int sz = 18, gap = 4;
        int closeX = panelX + panelW - Theme.PAD + 4 - sz;
        int plusX = closeX - gap - sz;
        int minusX = plusX - gap - sz;
        int cy = panelY + (headerH - sz) / 2;
        if (button == 0 && Theme.inside(mx, my, closeX, cy, sz, sz)) { onClose(); return true; }
        if (button == 0 && Theme.inside(mx, my, plusX, cy, sz, sz)) { zoom(+STEP); return true; }
        if (button == 0 && Theme.inside(mx, my, minusX, cy, sz, sz)) { zoom(-STEP); return true; }

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
        if (Screen.hasControlDown()) {
            zoom(sy > 0 ? STEP : -STEP);
            return true;
        }
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
        if (Screen.hasControlDown()) {
            switch (keyCode) {
                case GLFW.GLFW_KEY_EQUAL, GLFW.GLFW_KEY_KP_ADD -> { zoom(+STEP); return true; }
                case GLFW.GLFW_KEY_MINUS, GLFW.GLFW_KEY_KP_SUBTRACT -> { zoom(-STEP); return true; }
                case GLFW.GLFW_KEY_0, GLFW.GLFW_KEY_KP_0 -> { resetZoom(); return true; }
                default -> {}
            }
        }
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

    // ------------------------------------------------------------------
    // 缩放
    // ------------------------------------------------------------------
    private void zoom(float delta) {
        float old = uiScale;
        uiScale = clampF(uiScale + delta, MIN_SCALE, MAX_SCALE);
        if (uiScale != old) layout();
    }

    private void resetZoom() {
        if (uiScale != MAX_SCALE) { uiScale = MAX_SCALE; layout(); }
    }

    private int navItemSize() { return Math.min(NAV_ITEM_SIZE, Math.max(24, sidebarW - 12)); }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
    private static float clampF(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
}
