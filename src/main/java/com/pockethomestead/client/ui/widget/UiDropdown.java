package com.pockethomestead.client.ui.widget;

import com.pockethomestead.client.ui.HomesteadTabletGuiTextures;
import com.pockethomestead.client.ui.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;
import java.util.function.IntConsumer;

/**
 * 真·展开式下拉菜单：闭合态显示当前选中项 + ▼；展开态弹出可滚动列表，作为页面 overlay 渲染于最上层。
 * 列表过长时自动滚动，空间不足时向上翻转弹出。
 */
public class UiDropdown {
    private final Font font = Minecraft.getInstance().font;

    private int x, y, w, h;
    private List<String> labels;
    private int selected;
    private boolean open;
    private double scroll;
    private IntConsumer onSelect = i -> {};

    private static final int ROW_H = 17;
    private static final int MAX_VISIBLE = 8;

    public UiDropdown(List<String> labels, int selected) {
        this.labels = labels;
        this.selected = clampIndex(selected);
    }

    public UiDropdown bounds(int x, int y, int w, int h) {
        this.x = x; this.y = y; this.w = w; this.h = h; return this;
    }

    public UiDropdown onSelect(IntConsumer c) { this.onSelect = c; return this; }

    public void setLabels(List<String> labels) {
        this.labels = labels;
        this.selected = clampIndex(selected);
    }

    public int selected() { return selected; }
    public void setSelected(int i) { this.selected = clampIndex(i); }
    public boolean isOpen() { return open; }
    public void close() { open = false; }

    public void toggle() {
        open = !open;
        if (open) {
            // 打开时滚动到选中项
            scroll = 0;
            int popH = popupVisibleRows() * ROW_H;
            int selTop = selected * ROW_H;
            if (selTop > popH - ROW_H) scroll = Math.min(maxScroll(), selTop - popH + ROW_H);
        }
    }

    private int clampIndex(int i) {
        if (labels == null || labels.isEmpty()) return 0;
        return Math.max(0, Math.min(i, labels.size() - 1));
    }

    private int popupVisibleRows() {
        return Math.min(MAX_VISIBLE, Math.max(1, labels.size()));
    }

    private int maxScroll() {
        return Math.max(0, labels.size() * ROW_H - popupVisibleRows() * ROW_H);
    }

    private String currentLabel() {
        if (labels == null || labels.isEmpty()) return "—";
        return labels.get(clampIndex(selected));
    }

    /** 是否向上翻转（下方空间不足）。 */
    private boolean flipUp() {
        int popH = popupVisibleRows() * ROW_H + 2;
        int screenH = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        return y + h + popH > screenH - 4 && y - popH > 4;
    }

    private int popupTop() {
        int popH = popupVisibleRows() * ROW_H + 2;
        return flipUp() ? y - popH : y + h;
    }

    // ===== 闭合态 =====
    public void render(GuiGraphics g, int mouseX, int mouseY) {
        boolean hovered = Theme.inside(mouseX, mouseY, x, y, w, h);
        HomesteadTabletGuiTextures.button(g, x, y, w, h,
                (open || hovered) ? HomesteadTabletGuiTextures.ButtonState.HOVER : HomesteadTabletGuiTextures.ButtonState.NORMAL);

        String text = Theme.ellipsize(font, currentLabel(), w - 24);
        g.drawString(font, Theme.styled(text), x + 8, y + (h - font.lineHeight) / 2 + 1, Theme.TEXT, false);
        // 箭头
        String arrow = open ? "▲" : "▼";
        g.drawString(font, Theme.styled(arrow), x + w - 14, y + (h - font.lineHeight) / 2 + 1, Theme.TEXT_MUTED, false);
    }

    // ===== 展开态（overlay）=====
    public void renderPopup(GuiGraphics g, int mouseX, int mouseY) {
        if (!open) return;
        int rows = popupVisibleRows();
        int popH = rows * ROW_H + 2;
        int top = popupTop();

        HomesteadTabletGuiTextures.shadow(g, x, top, w, popH);
        HomesteadTabletGuiTextures.panel(g, x, top, w, popH);

        g.enableScissor(x + 1, top + 1, x + w - 1, top + popH - 1);
        int first = (int) (scroll / ROW_H);
        int offset = (int) (scroll % ROW_H);
        for (int vis = 0; vis <= rows; vis++) {
            int idx = first + vis;
            if (idx < 0 || idx >= labels.size()) continue;
            int rowY = top + 1 + vis * ROW_H - offset;
            boolean rowHover = Theme.inside(mouseX, mouseY, x + 1, rowY, w - 2, ROW_H)
                    && mouseY >= top && mouseY < top + popH;
            boolean isSel = idx == selected;
            if (isSel) {
                Theme.fillRound(g, x + 2, rowY + 1, w - 4, ROW_H - 2, 3, Theme.PRIMARY_SOFT);
            } else if (rowHover) {
                Theme.fillRound(g, x + 2, rowY + 1, w - 4, ROW_H - 2, 3, Theme.SURFACE_ALT);
            }
            String text = Theme.ellipsize(font, labels.get(idx), w - 16);
            int col = isSel ? Theme.PRIMARY_PRESS : Theme.TEXT;
            g.drawString(font, Theme.styled(text), x + 8, rowY + (ROW_H - font.lineHeight) / 2 + 1, col, false);
        }
        g.disableScissor();

        // 滚动条
        if (labels.size() > rows) {
            int trackH = popH - 4;
            int barH = Math.max(16, trackH * rows / labels.size());
            int barY = top + 2 + (int) (scroll / maxScroll() * (trackH - barH));
            g.fill(x + w - 4, top + 2, x + w - 2, top + 2 + trackH, 0x22000000);
            Theme.fillRound(g, x + w - 4, barY, 2, barH, 1, Theme.BORDER_STRONG);
        }
    }

    /** 闭合态点击：命中控件则切换开合。 */
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0 && Theme.inside(mx, my, x, y, w, h)) {
            toggle();
            return true;
        }
        return false;
    }

    /** 展开态点击：命中列表项则选择；点击控件本身关闭；点击别处关闭并不消费（让外部继续处理）。 */
    public boolean popupMouseClicked(double mx, double my, int button) {
        if (!open) return false;
        if (button != 0) { open = false; return true; }

        int rows = popupVisibleRows();
        int popH = rows * ROW_H + 2;
        int top = popupTop();

        if (Theme.inside(mx, my, x, top, w, popH)) {
            int rel = (int) (my - top - 1 + scroll);
            int idx = rel / ROW_H;
            if (idx >= 0 && idx < labels.size()) {
                selected = idx;
                onSelect.accept(idx);
                Minecraft.getInstance().getSoundManager().play(
                        net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                                net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0F));
            }
            open = false;
            return true;
        }
        // 点击控件区域：仅关闭
        if (Theme.inside(mx, my, x, y, w, h)) { open = false; return true; }
        // 点击别处：关闭，吃掉这次点击避免误触下方控件
        open = false;
        return true;
    }

    public boolean popupMouseScrolled(double mx, double my, double sy) {
        if (!open) return false;
        int rows = popupVisibleRows();
        int popH = rows * ROW_H + 2;
        int top = popupTop();
        if (Theme.inside(mx, my, x, top, w, popH)) {
            scroll = Math.max(0, Math.min(maxScroll(), scroll - sy * ROW_H));
            return true;
        }
        return false;
    }
}
