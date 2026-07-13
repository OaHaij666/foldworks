package com.foldworks.client.ui.widget;

import com.foldworks.client.ui.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.function.Consumer;

/**
 * 可拖动滑块：上行 标签(左)+数值提示(右)，下行轨道。value ∈ [0,1]。
 */
public class UiSlider {
    private final Font font = Minecraft.getInstance().font;

    private int x, y, w, h;
    private String label;
    private float value;
    private boolean dragging;
    private boolean enabled = true;
    private Consumer<Float> onChange = v -> {};

    private static final int TRACK_H = 6;

    public UiSlider(String label, float value) {
        this.label = label;
        this.value = clamp01(value);
    }

    public UiSlider bounds(int x, int y, int w, int h) {
        this.x = x; this.y = y; this.w = w; this.h = h; return this;
    }

    public UiSlider label(String label) { this.label = label; return this; }
    public UiSlider onChange(Consumer<Float> c) { this.onChange = c; return this; }
    public UiSlider enabled(boolean e) { this.enabled = e; return this; }
    public float value() { return value; }
    public void setValue(float v) { this.value = clamp01(v); }

    private int trackX() { return x; }
    private int trackY() { return y + 15; }
    private int trackW() { return w; }

    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int textColor = enabled ? Theme.TEXT : Theme.TEXT_FAINT;
        g.drawString(font, Theme.styled(label), x, y, textColor, false);
        String hint = Math.round(value * 100) + "%";
        Theme.textRight(g, font, hint, x + w, y, enabled ? Theme.TEXT_MUTED : Theme.TEXT_FAINT);

        int tx = trackX(), ty = trackY(), tw = trackW();
        // 轨道底
        Theme.panel(g, tx, ty, tw, TRACK_H, 2, Theme.SURFACE_SUNK, Theme.BORDER);
        // 已填充
        int filled = (int) (tw * value);
        int fillColor = enabled ? Theme.PRIMARY : Theme.BORDER_STRONG;
        if (filled > 0) Theme.fillRound(g, tx + 1, ty + 1, Math.max(1, filled - 1), TRACK_H - 2, 1, fillColor);
        // 滑块
        int knob = 12;
        int knobX = clampInt(tx + filled - knob / 2, tx, tx + tw - knob);
        int knobY = ty + TRACK_H / 2 - knob / 2;
        boolean hover = enabled && Theme.inside(mouseX, mouseY, knobX - 2, knobY - 2, knob + 4, knob + 4);
        if (hover || dragging) Theme.outlineRound(g, knobX - 2, knobY - 2, knob + 4, knob + 4, 4, Theme.PRIMARY_SOFT_H);
        Theme.panel(g, knobX, knobY, knob, knob, 3, enabled ? Theme.SURFACE : Theme.SURFACE_ALT,
                (hover || dragging) ? Theme.PRIMARY_PRESS : Theme.BORDER_STRONG);
    }

    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0 || !enabled) return false;
        // 命中轨道行（含上下余量）即开始拖动
        if (Theme.inside(mx, my, trackX() - 2, trackY() - 6, trackW() + 4, TRACK_H + 12)) {
            dragging = true;
            setFromMouse(mx);
            return true;
        }
        return false;
    }

    public boolean mouseDragged(double mx, double my, int button) {
        if (dragging && enabled) {
            setFromMouse(mx);
            return true;
        }
        return false;
    }

    public boolean mouseReleased(double mx, double my, int button) {
        if (dragging) { dragging = false; return true; }
        return false;
    }

    private void setFromMouse(double mx) {
        float v = (float) ((mx - trackX()) / trackW());
        value = clamp01(v);
        onChange.accept(value);
    }

    private static float clamp01(float v) { return Math.max(0f, Math.min(1f, v)); }
    private static int clampInt(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
