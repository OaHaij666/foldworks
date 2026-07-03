package com.pockethomestead.client.ui.widget;

import com.pockethomestead.client.ui.ChestGuiTextures;
import com.pockethomestead.client.ui.HomesteadTabletGuiTextures;
import com.pockethomestead.client.ui.Theme;
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

        int textColor;
        ChestGuiTextures.ButtonState chestState;
        HomesteadTabletGuiTextures.ButtonState tabletState;
        switch (variant) {
            case PRIMARY -> {
                chestState = hovered ? ChestGuiTextures.ButtonState.HOVER : ChestGuiTextures.ButtonState.NORMAL;
                tabletState = hovered ? HomesteadTabletGuiTextures.ButtonState.HOVER : HomesteadTabletGuiTextures.ButtonState.SELECTED;
                textColor = Theme.PRIMARY_PRESS;
            }
            case DANGER -> {
                chestState = ChestGuiTextures.ButtonState.DANGER;
                tabletState = HomesteadTabletGuiTextures.ButtonState.DANGER;
                textColor = 0xFFB65264;
            }
            case SECONDARY -> {
                chestState = hovered ? ChestGuiTextures.ButtonState.HOVER : ChestGuiTextures.ButtonState.NORMAL;
                tabletState = hovered ? HomesteadTabletGuiTextures.ButtonState.HOVER : HomesteadTabletGuiTextures.ButtonState.NORMAL;
                textColor = Theme.TEXT;
            }
            default /*GHOST*/ -> {
                chestState = hovered ? ChestGuiTextures.ButtonState.HOVER : ChestGuiTextures.ButtonState.NORMAL;
                tabletState = hovered ? HomesteadTabletGuiTextures.ButtonState.HOVER : HomesteadTabletGuiTextures.ButtonState.NORMAL;
                textColor = Theme.PRIMARY;
            }
        }

        if (variant == Variant.GHOST && hoverAnim < 0.02f && enabled) {
            // 透明态：仅文字
            Theme.textInBox(g, font, label, x, y, w, h, textColor);
            return;
        }

        if (!enabled) {
            chestState = ChestGuiTextures.ButtonState.DISABLED;
            tabletState = HomesteadTabletGuiTextures.ButtonState.DISABLED;
            textColor = Theme.TEXT_FAINT;
        }

        if (skin == Skin.CHEST) ChestGuiTextures.button(g, x, y, w, h, chestState);
        else HomesteadTabletGuiTextures.button(g, x, y, w, h, tabletState);
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
