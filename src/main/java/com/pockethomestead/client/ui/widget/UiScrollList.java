package com.pockethomestead.client.ui.widget;

import com.pockethomestead.client.ui.Theme;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;

/**
 * 通用滚动列表：固定行高，自带滚动条与裁剪。行的绘制与点击交给调用方回调，
 * 因此可承载任意复杂的行内容（如带多个按钮的空间卡片）。
 */
public class UiScrollList<T> {

    public interface Renderer<T> {
        void render(GuiGraphics g, T item, int x, int y, int w, int h, int mouseX, int mouseY, boolean hovered);
    }

    public interface ClickHandler<T> {
        /** rowX/rowY 为该行左上角绝对坐标。返回 true 表示已消费。 */
        boolean onClick(T item, double mx, double my, int button, int rowX, int rowY, int rowW, int rowH);
    }

    private int x, y, w, h;
    private final int rowH;
    private final int gap;
    private List<T> items;
    private double scroll;
    private final Renderer<T> renderer;
    private final ClickHandler<T> clickHandler;

    public UiScrollList(int rowH, int gap, Renderer<T> renderer, ClickHandler<T> clickHandler) {
        this.rowH = rowH;
        this.gap = gap;
        this.renderer = renderer;
        this.clickHandler = clickHandler;
    }

    public UiScrollList<T> bounds(int x, int y, int w, int h) {
        this.x = x; this.y = y; this.w = w; this.h = h; return this;
    }

    public void setItems(List<T> items) { this.items = items; }
    public List<T> items() { return items; }

    private int contentHeight() {
        return items == null ? 0 : items.size() * (rowH + gap);
    }

    private int maxScroll() {
        return Math.max(0, contentHeight() - h);
    }

    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        scroll = Math.max(0, Math.min(scroll, maxScroll()));
        g.enableScissor(x, y, x + w, y + h);
        if (items != null) {
            for (int i = 0; i < items.size(); i++) {
                int rowY = y + i * (rowH + gap) - (int) scroll;
                if (rowY + rowH < y || rowY > y + h) continue;
                boolean hovered = Theme.inside(mouseX, mouseY, x, rowY, w, rowH)
                        && mouseY >= y && mouseY < y + h;
                renderer.render(g, items.get(i), x, rowY, w, rowH, mouseX, mouseY, hovered);
            }
        }
        g.disableScissor();

        // 滚动条
        if (maxScroll() > 0) {
            int barX = x + w - 4;
            int barH = Math.max(20, h * h / contentHeight());
            int barY = y + (int) (scroll / maxScroll() * (h - barH));
            g.fill(barX, y, barX + 3, y + h, 0x18000000);
            Theme.fillRound(g, barX, barY, 3, barH, 1, Theme.BORDER_STRONG);
        }
    }

    public boolean mouseClicked(double mx, double my, int button) {
        if (items == null || !Theme.inside(mx, my, x, y, w, h)) return false;
        int idx = (int) ((my - y + scroll) / (rowH + gap));
        if (idx < 0 || idx >= items.size()) return false;
        int rowY = y + idx * (rowH + gap) - (int) scroll;
        // 落在 gap 间隙里则忽略
        if (my >= rowY + rowH) return false;
        return clickHandler.onClick(items.get(idx), mx, my, button, x, rowY, w, rowH);
    }

    public boolean mouseScrolled(double mx, double my, double sy) {
        if (!Theme.inside(mx, my, x, y, w, h)) return false;
        scroll = Math.max(0, Math.min(maxScroll(), scroll - sy * 20));
        return true;
    }
}
