package com.pockethomestead.client.ui.widget;

import com.pockethomestead.client.ui.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.function.Consumer;

/**
 * 开关样式切换控件：左侧标签 + 右侧滑块轨道，开/关状态有滑动动画。
 */
public class UiToggle {
    private final Font font = Minecraft.getInstance().font;

    private int x, y, w, h;
    private String label;
    private boolean value;
    private float knobAnim;
    private Consumer<Boolean> onChange = b -> {};

    private static final int TRACK_W = 30;
    private static final int TRACK_H = 16;

    public UiToggle(String label, boolean value) {
        this.label = label;
        this.value = value;
        this.knobAnim = value ? 1f : 0f;
    }

    public UiToggle bounds(int x, int y, int w, int h) {
        this.x = x; this.y = y; this.w = w; this.h = h; return this;
    }

    public UiToggle label(String label) { this.label = label; return this; }
    public UiToggle onChange(Consumer<Boolean> c) { this.onChange = c; return this; }
    public boolean value() { return value; }
    public void setValue(boolean v) { this.value = v; }

    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        boolean hovered = Theme.inside(mouseX, mouseY, x, y, w, h);
        float target = value ? 1f : 0f;
        knobAnim += (target - knobAnim) * Math.min(1f, partialTick * 0.5f + 0.2f);

        // 背板（hover 时淡蓝底）
        if (hovered) Theme.fillRound(g, x, y, w, h, Theme.RADIUS, Theme.SURFACE_ALT);

        // 标签
        g.drawString(font, Theme.styled(label), x + 8, y + (h - font.lineHeight) / 2 + 1, Theme.TEXT, false);

        // 轨道（靠右）
        int trackX = x + w - TRACK_W - 8;
        int trackY = y + (h - TRACK_H) / 2;
        int trackColor = Theme.lerpColor(Theme.BORDER_STRONG, Theme.PRIMARY, knobAnim);
        Theme.fillRound(g, trackX, trackY, TRACK_W, TRACK_H, TRACK_H / 2, trackColor);

        // 滑块
        int knobR = TRACK_H - 4;
        int knobTravel = TRACK_W - knobR - 4;
        int knobX = trackX + 2 + (int) (knobTravel * knobAnim);
        int knobY = trackY + 2;
        Theme.fillRound(g, knobX, knobY, knobR, knobR, knobR / 2, 0xFFFFFFFF);
    }

    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0 && Theme.inside(mx, my, x, y, w, h)) {
            value = !value;
            onChange.accept(value);
            Minecraft.getInstance().getSoundManager().play(
                    net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                            net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, value ? 1.1F : 0.9F));
            return true;
        }
        return false;
    }
}
