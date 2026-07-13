package com.foldworks.client.ui.widget;

import com.foldworks.client.ui.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 主题化按钮，支持 4 种变体与 hover 平滑过渡动画。
 */
public class UiButton {
    public enum Variant { PRIMARY, SECONDARY, DANGER, GHOST }
    public enum Skin { TABLET, CHEST }

    private final Font font = Minecraft.getInstance().font;

    private int x, y, w, h;
    private String label;
    private Variant variant = Variant.PRIMARY;
    private Skin skin = Skin.TABLET;
    private boolean enabled = true;
    private Runnable onClick = () -> {};
    private float hoverAnim = 0f;
    private long pressedUntilMs;

    public UiButton(String label, Variant variant) {
        this.label = label;
        this.variant = variant;
    }

    public UiButton bounds(int x, int y, int w, int h) {
        this.x = x; this.y = y; this.w = w; this.h = h; return this;
    }

    public UiButton label(String label) { this.label = label; return this; }
    public UiButton onClick(Runnable r) { this.onClick = r; return this; }
    public UiButton enabled(boolean e) { this.enabled = e; return this; }
    public UiButton skin(Skin skin) { this.skin = skin; return this; }
    public boolean enabled() { return enabled; }

    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        boolean hovered = enabled && Theme.inside(mouseX, mouseY, x, y, w, h);
        // 平滑趋近目标 hover 值
        float target = hovered ? 1f : 0f;
        hoverAnim += (target - hoverAnim) * Math.min(1f, partialTick * 0.6f + 0.25f);

        boolean pressed = enabled && System.currentTimeMillis() < pressedUntilMs;
        int fill;
        int border;
        int textColor;
        switch (variant) {
            case PRIMARY -> {
                fill = pressed ? Theme.lerpColor(Theme.PRIMARY, Theme.PRIMARY_PRESS, 0.35f)
                        : hovered ? Theme.PRIMARY_HOVER : Theme.PRIMARY;
                border = Theme.PRIMARY_PRESS;
                textColor = Theme.TEXT_ON_PRIM;
            }
            case DANGER -> {
                fill = pressed ? Theme.DANGER : hovered ? Theme.DANGER_HOVER : Theme.DANGER_SOFT;
                border = Theme.DANGER;
                textColor = pressed || hovered ? Theme.TEXT_ON_DANGER : Theme.DANGER;
            }
            case SECONDARY -> {
                fill = pressed ? Theme.PRIMARY_SOFT_H : hovered ? Theme.PRIMARY_SOFT : Theme.SURFACE;
                border = hovered || pressed ? Theme.PRIMARY : Theme.BORDER_STRONG;
                textColor = Theme.TEXT;
            }
            default /*GHOST*/ -> {
                fill = pressed ? Theme.PRIMARY_SOFT_H : Theme.PRIMARY_SOFT;
                border = pressed ? Theme.PRIMARY : Theme.PRIMARY_SOFT_H;
                textColor = Theme.PRIMARY_PRESS;
            }
        }

        if (variant == Variant.GHOST && hoverAnim < 0.02f && enabled) {
            // 透明态：仅文字
            Theme.textInBox(g, font, label, x, y, w, h, textColor);
            return;
        }

        if (!enabled) {
            fill = Theme.SURFACE_SUNK;
            border = Theme.BORDER;
            textColor = Theme.TEXT_FAINT;
        }

        int radius = skin == Skin.CHEST ? 2 : Theme.RADIUS;
        Theme.panel(g, x, y, w, h, radius, fill, border);
        if (variant == Variant.PRIMARY && enabled) {
            Theme.hLine(g, x + 3, y + h - 2, Math.max(0, w - 6), pressed ? Theme.PRIMARY_HOVER : Theme.PRIMARY_PRESS);
        }
        Theme.textInBox(g, font, label, x, y, w, h, textColor);
    }

    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0 && enabled && Theme.inside(mx, my, x, y, w, h)) {
            pressedUntilMs = System.currentTimeMillis() + 140L;
            Minecraft.getInstance().getSoundManager().play(
                    net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                            net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0F));
            onClick.run();
            return true;
        }
        return false;
    }
}
