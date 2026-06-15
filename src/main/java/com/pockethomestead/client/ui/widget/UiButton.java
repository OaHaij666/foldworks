package com.pockethomestead.client.ui.widget;

import com.pockethomestead.client.ui.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 主题化按钮，支持 4 种变体与 hover 平滑过渡动画。
 */
public class UiButton {
    public enum Variant { PRIMARY, SECONDARY, DANGER, GHOST }

    private final Font font = Minecraft.getInstance().font;

    private int x, y, w, h;
    private String label;
    private Variant variant = Variant.PRIMARY;
    private boolean enabled = true;
    private Runnable onClick = () -> {};
    private float hoverAnim = 0f;

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
    public boolean enabled() { return enabled; }

    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        boolean hovered = enabled && Theme.inside(mouseX, mouseY, x, y, w, h);
        // 平滑趋近目标 hover 值
        float target = hovered ? 1f : 0f;
        hoverAnim += (target - hoverAnim) * Math.min(1f, partialTick * 0.6f + 0.25f);

        int base, hover, fill, textColor, border;
        switch (variant) {
            case PRIMARY -> { base = Theme.PRIMARY; hover = Theme.PRIMARY_HOVER; textColor = Theme.TEXT_ON_PRIM; border = Theme.PRIMARY_PRESS; }
            case DANGER  -> { base = Theme.DANGER;  hover = Theme.DANGER_HOVER;  textColor = Theme.TEXT_ON_PRIM; border = 0xFFD45555; }
            case SECONDARY -> { base = Theme.SURFACE_ALT; hover = Theme.PRIMARY_SOFT; textColor = Theme.TEXT; border = Theme.BORDER_STRONG; }
            default /*GHOST*/ -> { base = 0x00FFFFFF; hover = Theme.PRIMARY_SOFT; textColor = Theme.PRIMARY; border = 0x00FFFFFF; }
        }
        fill = Theme.lerpColor(base, hover, hoverAnim);

        if (!enabled) {
            fill = Theme.SURFACE_SUNK;
            textColor = Theme.TEXT_FAINT;
            border = Theme.BORDER;
        }

        if (variant == Variant.GHOST && hoverAnim < 0.02f && enabled) {
            // 透明态：仅文字
            Theme.textInBox(g, font, label, x, y, w, h, textColor);
            return;
        }

        if (variant == Variant.SECONDARY || variant == Variant.GHOST || !enabled) {
            Theme.panel(g, x, y, w, h, Theme.RADIUS, fill, border);
        } else {
            Theme.fillRound(g, x, y, w, h, Theme.RADIUS, fill);
            // 顶部高光
            Theme.fillRound(g, x + 2, y + 2, w - 4, Math.max(2, h / 2 - 2), Theme.RADIUS - 1, 0x22FFFFFF);
        }
        Theme.textInBox(g, font, label, x, y, w, h, textColor);
    }

    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0 && enabled && Theme.inside(mx, my, x, y, w, h)) {
            Minecraft.getInstance().getSoundManager().play(
                    net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                            net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0F));
            onClick.run();
            return true;
        }
        return false;
    }
}
