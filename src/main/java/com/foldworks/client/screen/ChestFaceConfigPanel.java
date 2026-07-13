package com.foldworks.client.screen;

import com.foldworks.blockentity.RelativeSide;
import com.foldworks.blockentity.ResourceKind;
import com.foldworks.blockentity.SideMode;
import com.foldworks.client.ui.ChestGuiTextures;
import com.foldworks.client.ui.Theme;
import com.foldworks.menu.BaseChestMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

/**
 * 箱子面配置页：3D 立方体预览 + 面/功能/模式选择 + 应力输出控制。
 * 状态自管理（selectedSide / faceYaw / facePitch 等），通过 ChestScreenHost 访问共享数据。
 */
public final class ChestFaceConfigPanel {
    private static final ResourceLocation CHEST_FACE_SIDE_TEXTURE = ResourceLocation.fromNamespaceAndPath("foldworks", "textures/block/chest_face_side.png");
    private static final ResourceLocation CHEST_FACE_CAP_TEXTURE = ResourceLocation.fromNamespaceAndPath("foldworks", "textures/block/chest_face_cap.png");
    private static final ResourceLocation CHEST_ITEM_PORT_TEXTURE = ResourceLocation.fromNamespaceAndPath("foldworks", "textures/block/chest_item_port.png");
    private static final ResourceLocation CHEST_FLUID_WINDOW_TEXTURE = ResourceLocation.fromNamespaceAndPath("foldworks", "textures/block/chest_fluid_window.png");
    private static final ResourceLocation CHEST_ENERGY_CORE_TEXTURE = ResourceLocation.fromNamespaceAndPath("foldworks", "textures/block/chest_energy_core.png");
    private static final ResourceLocation CHEST_BEARING_RING_TEXTURE = ResourceLocation.fromNamespaceAndPath("foldworks", "textures/block/chest_bearing_ring.png");
    private static final ResourceLocation CHEST_BEARING_SHAFT_TEXTURE = ResourceLocation.fromNamespaceAndPath("foldworks", "textures/block/chest_bearing_shaft.png");
    private static final int FACE_CUBE_HEIGHT = 116;
    private static final int FACE_CUBE_CENTER_Y_OFFSET = 65;
    private static final int FACE_CUBE_SCALE = 38;
    private static final int[] STRESS_SPEED_OPTIONS = {0, 16, 32, 64, 128, 256};

    private final ChestScreenHost host;
    private final FaceCubeRenderer renderer = new FaceCubeRenderer();

    private RelativeSide selectedSide = RelativeSide.FRONT;
    private double faceYaw = -34.0;
    private double facePitch = 24.0;
    private boolean rotatingFaceCube;
    private boolean faceCubeMoved;
    private int faceCubeDragX;
    private int faceCubeDragY;
    private boolean faceKindDropdownOpen;

    public ChestFaceConfigPanel(ChestScreenHost host) {
        this.host = host;
    }

    public void reset() {
        faceKindDropdownOpen = false;
    }

    // ── 渲染 ──

    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        Font font = host.font();
        int cardX = host.leftPos() + BaseChestMenu.PANEL_PADDING;
        int cardW = host.panelWidth() - BaseChestMenu.PANEL_PADDING * 2;
        int cubeY = host.topPos() + BaseChestMenu.HEADER_HEIGHT + 10;
        ChestGuiTextures.panelLighter(g, cardX, cubeY, cardW, FACE_CUBE_HEIGHT);
        Theme.text(g, font, "面配置", cardX + 10, cubeY + 8, Theme.TEXT);
        Theme.textRight(g, font, sideLabel(selectedSide), cardX + cardW - 10, cubeY + 8, Theme.PRIMARY_PRESS);
        renderFaceCube(g, mx, my, font, cardX + cardW / 2, cubeY + FACE_CUBE_CENTER_Y_OFFSET, FACE_CUBE_SCALE);

        int controlsY = cubeY + FACE_CUBE_HEIGHT + 8;
        int controlsH = Math.max(72, host.topPos() + host.panelHeight() - controlsY - BaseChestMenu.PANEL_PADDING);
        ChestGuiTextures.panelLighterCompact(g, cardX, controlsY, cardW, controlsH);
        Theme.text(g, font, sideLabel(selectedSide) + "面", cardX + 10, controlsY + 8, Theme.TEXT);
        renderFaceControls(g, mx, my, font, cardX, controlsY, cardW);
    }

    private void renderFaceControls(GuiGraphics g, int mx, int my, Font font, int x, int y, int w) {
        ResourceKind activeKind = activeSideKind(selectedSide);
        int functionX = x + 9;
        int functionY = y + 20;
        int functionW = 50;
        int settingsX = functionX + functionW + 9;
        int settingsW = Math.max(90, x + w - settingsX - 9);

        ChestGuiTextures.vDivider(g, functionX + functionW + 4, y + 19,
                Math.max(54, host.topPos() + host.panelHeight() - y - BaseChestMenu.PANEL_PADDING - 26));
        renderFaceFunctionSelector(g, mx, my, font, functionX, functionY, functionW, activeKind);

        Theme.text(g, font, kindLabel(activeKind) + "模式", settingsX, y + 17, Theme.TEXT_MUTED);
        int bx = settingsX;
        int by = y + 28;
        for (SideMode mode : sideModesFor(activeKind)) {
            int bw = 21;
            renderFaceModeButton(g, mx, my, font, bx, by, bw, activeKind, mode);
            bx += bw + 3;
        }
        if (activeKind == ResourceKind.STRESS && cachedSideMode(activeKind, selectedSide) == SideMode.OUTPUT) {
            renderStressOutputControls(g, mx, my, font, settingsX, y + 52, settingsW);
        }
    }

    private void renderFaceFunctionSelector(GuiGraphics g, int mx, int my, Font font, int x, int y, int w, ResourceKind activeKind) {
        boolean hover = Theme.inside(mx, my, x, y, w, 18);
        ChestGuiTextures.button(g, x, y, w, 18, hover ? ChestGuiTextures.ButtonState.HOVER : ChestGuiTextures.ButtonState.NORMAL);
        Theme.text(g, font, Theme.ellipsize(font, kindLabel(activeKind), w - 16), x + 6, y + 6, Theme.PRIMARY_PRESS);
        if (faceKindDropdownOpen) ChestGuiTextures.chevronUp(g, x + w - 8, y + 9);
        else ChestGuiTextures.chevronDown(g, x + w - 8, y + 9);

        if (!faceKindDropdownOpen) return;
        List<ResourceKind> kinds = availableSideKinds();
        int rowH = 17;
        int listY = y + 20;
        ChestGuiTextures.panelLighterCompact(g, x, listY, w, kinds.size() * rowH + 4);
        for (int i = 0; i < kinds.size(); i++) {
            ResourceKind kind = kinds.get(i);
            int rowY = listY + 2 + i * rowH;
            boolean selected = kind == activeKind;
            boolean rowHover = Theme.inside(mx, my, x + 2, rowY, w - 4, rowH);
            if (selected || rowHover) {
                ChestGuiTextures.button(g, x + 2, rowY, w - 4, rowH,
                        selected ? ChestGuiTextures.ButtonState.SELECTED : ChestGuiTextures.ButtonState.HOVER);
            }
            Theme.textCentered(g, font, kindLabel(kind), x + w / 2, rowY + 5, selected ? Theme.PRIMARY_PRESS : Theme.TEXT);
        }
    }

    private void renderStressOutputControls(GuiGraphics g, int mx, int my, Font font, int x, int y, int w) {
        Theme.text(g, font, "方向", x, y + 3, Theme.TEXT_MUTED);
        renderStressToggle(g, mx, my, font, x + 25, y, 28, "同向", !host.stressOutputReversed());
        renderStressToggle(g, mx, my, font, x + 56, y, 28, "反向", host.stressOutputReversed());
        Theme.text(g, font, "转速", x, y + 20, Theme.TEXT_MUTED);
        renderStressToggle(g, mx, my, font, x + 25, y + 17, Math.min(58, w - 25), stressSpeedLabel(host.stressOutputSpeedRpm()), true);
    }

    private void renderStressToggle(GuiGraphics g, int mx, int my, Font font, int x, int y, int w, String label, boolean selected) {
        boolean hover = Theme.inside(mx, my, x, y, w, 15);
        ChestGuiTextures.button(g, x, y, w, 15,
                selected ? ChestGuiTextures.ButtonState.SELECTED
                        : hover ? ChestGuiTextures.ButtonState.HOVER
                        : ChestGuiTextures.ButtonState.NORMAL);
        Theme.textCentered(g, font, label, x + w / 2, y + 4, selected ? Theme.PRIMARY_PRESS : Theme.TEXT);
    }

    private void renderFaceModeButton(GuiGraphics g, int mx, int my, Font font, int x, int y, int w, ResourceKind kind, SideMode mode) {
        boolean selected = cachedSideMode(kind, selectedSide) == mode;
        boolean hover = Theme.inside(mx, my, x, y, w, 18);
        ChestGuiTextures.button(g, x, y, w, 18,
                selected ? mode == SideMode.BOTH ? ChestGuiTextures.ButtonState.GOLD : ChestGuiTextures.ButtonState.SELECTED
                        : hover ? ChestGuiTextures.ButtonState.HOVER
                        : ChestGuiTextures.ButtonState.NORMAL);
        Theme.textCentered(g, font, sideModeLabel(mode), x + w / 2, y + 6, sideModeText(mode));
    }

    private SideMode[] sideModesFor(ResourceKind kind) {
        return kind == ResourceKind.STRESS
                ? new SideMode[]{SideMode.DISABLED, SideMode.INPUT, SideMode.OUTPUT}
                : new SideMode[]{SideMode.DISABLED, SideMode.INPUT, SideMode.OUTPUT, SideMode.BOTH};
    }

    private void renderFaceCube(GuiGraphics g, int mx, int my, Font font, int cx, int cy, int scale) {
        List<FaceCubeRenderer.ProjectedFace> faces = renderer.projectedFaces(faceYaw, facePitch, cx, cy, scale, true);
        for (FaceCubeRenderer.ProjectedFace face : faces) {
            renderer.drawTexturedQuad(g, face.xs(), face.ys(), faceBaseTexture(face.side()), 0xFFFFFFFF);
            drawFaceModule(g, font, face);
            renderer.fillQuad(g, face.xs(), face.ys(), faceOverlay(face.side(), face.side() == selectedSide));
        }
        for (FaceCubeRenderer.ProjectedFace face : faces) {
            Theme.textCentered(g, font, sideLabel(face.side()), (int) Math.round(face.cx()), (int) Math.round(face.cy()) - 4,
                    face.side() == selectedSide ? Theme.PRIMARY_PRESS : Theme.TEXT);
        }
        RelativeSide hover = renderer.faceAt(mx, my, cx, cy, scale, faceYaw, facePitch);
        if (hover != null) {
            FaceCubeRenderer.ProjectedFace face = renderer.findProjectedFace(faces, hover);
            if (face != null) renderer.fillQuad(g, face.xs(), face.ys(), 0x22FFFFFF);
        }
    }

    private ResourceLocation faceBaseTexture(RelativeSide side) {
        return side == RelativeSide.UP || side == RelativeSide.DOWN ? CHEST_FACE_CAP_TEXTURE : CHEST_FACE_SIDE_TEXTURE;
    }

    private void drawFaceModule(GuiGraphics g, Font font, FaceCubeRenderer.ProjectedFace face) {
        ResourceKind kind = activeSideKind(face.side());
        SideMode mode = cachedSideMode(kind, face.side());
        if (mode == SideMode.DISABLED) return;

        double[] xs = FaceCubeRenderer.inset(face.xs(), 0.25);
        double[] ys = FaceCubeRenderer.inset(face.ys(), 0.25);
        int tint = moduleTint(kind, mode);
        switch (kind) {
            case ITEM -> drawAnimatedTexture(g, xs, ys, CHEST_ITEM_PORT_TEXTURE, tint, 12, 4);
            case FLUID -> drawAnimatedTexture(g, xs, ys, CHEST_FLUID_WINDOW_TEXTURE, tint, 10, 8);
            case ENERGY -> drawAnimatedTexture(g, xs, ys, CHEST_ENERGY_CORE_TEXTURE, tint, 8, 5);
            case STRESS -> {
                drawAnimatedTexture(g, xs, ys, CHEST_BEARING_RING_TEXTURE, tint, 8, 4);
                renderer.drawTexturedQuad(g, FaceCubeRenderer.inset(face.xs(), 0.75), FaceCubeRenderer.inset(face.ys(), 0.75), CHEST_BEARING_SHAFT_TEXTURE,
                        0xFFFFFFFF, 0.375f, 0.375f, 0.625f, 0.625f);
            }
        }
    }

    private void drawAnimatedTexture(GuiGraphics g, double[] xs, double[] ys, ResourceLocation texture, int color, int frames, int frameTime) {
        Minecraft mc = host.minecraft();
        long tick = mc != null && mc.level != null ? mc.level.getGameTime() : System.currentTimeMillis() / 50L;
        int safeFrames = Math.max(1, frames);
        int frame = (int) ((tick / Math.max(1, frameTime)) % safeFrames);
        float v0 = (float) frame / safeFrames;
        float v1 = (float) (frame + 1) / safeFrames;
        renderer.drawTexturedQuad(g, xs, ys, texture, color, 0.0f, v0, 1.0f, v1);
    }

    private int moduleTint(ResourceKind kind, SideMode mode) {
        if (kind == ResourceKind.STRESS || kind == ResourceKind.ENERGY) return 0xFFFFFFFF;
        return switch (mode) {
            case INPUT -> 0xFFE4FFF0;
            case OUTPUT -> 0xFFE2EEFF;
            case BOTH -> 0xFFFFF1BC;
            case DISABLED -> 0xFFFFFFFF;
        };
    }

    private int faceOverlay(RelativeSide side, boolean selected) {
        SideMode mode = cachedSideMode(activeSideKind(side), side);
        boolean in = mode.canInput();
        boolean out = mode.canOutput();
        int color = in && out ? 0xFFEFE2AC : in ? 0xFFDDF4E7 : out ? 0xFFDCEBFF : 0xFFE6EDF4;
        int mixed = selected ? FaceCubeRenderer.mixColor(color, 0xFFFFFFFF, 0.22f) : color;
        return ((selected ? 0x64 : 0x2D) << 24) | (mixed & 0x00FFFFFF);
    }

    // ── 交互 ──

    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return true;
        int cardX = host.leftPos() + BaseChestMenu.PANEL_PADDING;
        int cardW = host.panelWidth() - BaseChestMenu.PANEL_PADDING * 2;
        int cubeY = host.topPos() + BaseChestMenu.HEADER_HEIGHT + 10;
        if (Theme.inside(mx, my, cardX, cubeY, cardW, FACE_CUBE_HEIGHT)) {
            faceKindDropdownOpen = false;
            rotatingFaceCube = true;
            faceCubeMoved = false;
            faceCubeDragX = (int) mx;
            faceCubeDragY = (int) my;
            return true;
        }
        int controlsY = cubeY + FACE_CUBE_HEIGHT + 8;
        if (handleFaceModeClick(mx, my, cardX, controlsY, cardW)) return true;
        faceKindDropdownOpen = false;
        return true;
    }

    public boolean mouseReleased(double mx, double my, int button) {
        if (button == 0 && rotatingFaceCube) {
            rotatingFaceCube = false;
            if (!faceCubeMoved) {
                int cardX = host.leftPos() + BaseChestMenu.PANEL_PADDING;
                int cardW = host.panelWidth() - BaseChestMenu.PANEL_PADDING * 2;
                int cubeY = host.topPos() + BaseChestMenu.HEADER_HEIGHT + 10;
                RelativeSide hit = renderer.faceAt(mx, my, cardX + cardW / 2, cubeY + FACE_CUBE_CENTER_Y_OFFSET, FACE_CUBE_SCALE, faceYaw, facePitch);
                if (hit != null) selectFace(hit);
            }
            return true;
        }
        return false;
    }

    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (rotatingFaceCube && button == 0) {
            faceYaw += dx * 0.8;
            facePitch = Math.max(-84.0, Math.min(84.0, facePitch - dy * 0.8));
            if (Math.abs(mx - faceCubeDragX) > 2 || Math.abs(my - faceCubeDragY) > 2) faceCubeMoved = true;
            return true;
        }
        return false;
    }

    private boolean handleFaceModeClick(double mx, double my, int x, int y, int w) {
        int functionX = x + 9;
        int functionY = y + 20;
        int functionW = 50;
        int settingsX = functionX + functionW + 9;
        int settingsW = Math.max(90, x + w - settingsX - 9);

        if (Theme.inside(mx, my, functionX, functionY, functionW, 18)) {
            faceKindDropdownOpen = !faceKindDropdownOpen;
            return true;
        }
        if (faceKindDropdownOpen) {
            List<ResourceKind> kinds = availableSideKinds();
            int rowH = 17;
            int listY = functionY + 20;
            for (int i = 0; i < kinds.size(); i++) {
                int rowY = listY + 2 + i * rowH;
                if (Theme.inside(mx, my, functionX + 2, rowY, functionW - 4, rowH)) {
                    setFaceFunction(kinds.get(i));
                    faceKindDropdownOpen = false;
                    return true;
                }
            }
            if (Theme.inside(mx, my, functionX, listY, functionW, kinds.size() * rowH + 4)) {
                return true;
            }
            faceKindDropdownOpen = false;
        }

        ResourceKind activeKind = activeSideKind(selectedSide);
        int bx = settingsX;
        int by = y + 28;
        for (SideMode mode : sideModesFor(activeKind)) {
            int bw = 21;
            if (Theme.inside(mx, my, bx, by, bw, 18)) {
                setFaceMode(activeKind, mode);
                return true;
            }
            bx += bw + 3;
        }
        if (activeKind == ResourceKind.STRESS && cachedSideMode(activeKind, selectedSide) == SideMode.OUTPUT) {
            if (handleStressOutputConfigClick(mx, my, settingsX, y + 52, settingsW)) return true;
        }
        return false;
    }

    private boolean handleStressOutputConfigClick(double mx, double my, int x, int y, int w) {
        if (Theme.inside(mx, my, x + 25, y, 28, 15)) {
            setStressOutputReversed(false);
            return true;
        }
        if (Theme.inside(mx, my, x + 56, y, 28, 15)) {
            setStressOutputReversed(true);
            return true;
        }
        if (Theme.inside(mx, my, x + 25, y + 17, Math.min(58, w - 25), 15)) {
            setStressOutputSpeed(nextStressOutputSpeed(host.stressOutputSpeedRpm()));
            return true;
        }
        return false;
    }

    private void selectFace(RelativeSide side) {
        if (side == null) return;
        faceKindDropdownOpen = false;
        selectedSide = side;
        switch (side) {
            case FRONT -> { faceYaw = 0; facePitch = 0; }
            case BACK -> { faceYaw = 180; facePitch = 0; }
            case LEFT -> { faceYaw = 90; facePitch = 0; }
            case RIGHT -> { faceYaw = -90; facePitch = 0; }
            case UP -> { faceYaw = 0; facePitch = 82; }
            case DOWN -> { faceYaw = 0; facePitch = -82; }
        }
    }

    private boolean canEditSideKind(ResourceKind kind) {
        if (kind == ResourceKind.FLUID) return host.createLoaded();
        if (kind == ResourceKind.STRESS) return host.createLoaded();
        return true;
    }

    private List<ResourceKind> availableSideKinds() {
        List<ResourceKind> kinds = new ArrayList<>();
        kinds.add(ResourceKind.ITEM);
        if (host.createLoaded()) kinds.add(ResourceKind.FLUID);
        kinds.add(ResourceKind.ENERGY);
        if (host.createLoaded()) kinds.add(ResourceKind.STRESS);
        return kinds;
    }

    private ResourceKind activeSideKind(RelativeSide side) {
        List<ResourceKind> available = availableSideKinds();
        for (ResourceKind kind : available) {
            if (cachedSideMode(kind, side) != SideMode.DISABLED) return kind;
        }
        for (ResourceKind kind : ResourceKind.values()) {
            if (cachedSideMode(kind, side) != SideMode.DISABLED) return available.contains(kind) ? kind : ResourceKind.ITEM;
        }
        return ResourceKind.ITEM;
    }

    private SideMode defaultModeForKind(ResourceKind kind) {
        return kind == ResourceKind.STRESS ? SideMode.INPUT : SideMode.BOTH;
    }

    private void setFaceFunction(ResourceKind kind) {
        if (!canEditSideKind(kind)) return;
        setFaceConfig(kind, defaultModeForKind(kind));
    }

    private void setFaceMode(ResourceKind kind, SideMode mode) {
        if (!canEditSideKind(kind)) return;
        setFaceConfig(kind, mode);
    }

    private void setFaceConfig(ResourceKind kind, SideMode mode) {
        EnumMap<ResourceKind, EnumMap<RelativeSide, SideMode>> config = host.sideConfigMap();
        for (ResourceKind other : ResourceKind.values()) {
            SideMode next = other == kind ? mode : SideMode.DISABLED;
            config.computeIfAbsent(other, k -> new EnumMap<>(RelativeSide.class)).put(selectedSide, next);
        }
        host.sendConfig(18, selectedSide.name() + "|" + kind.name() + "|" + mode.name());
    }

    private void setStressOutputReversed(boolean reversed) {
        host.stressOutputReversed(reversed);
        host.sendConfig(20, reversed ? "1" : "0");
    }

    private void setStressOutputSpeed(int rpm) {
        host.stressOutputSpeedRpm(rpm);
        host.sendConfig(19, String.valueOf(rpm));
    }

    private int nextStressOutputSpeed(int current) {
        for (int i = 0; i < STRESS_SPEED_OPTIONS.length; i++) {
            if (STRESS_SPEED_OPTIONS[i] == current) return STRESS_SPEED_OPTIONS[(i + 1) % STRESS_SPEED_OPTIONS.length];
        }
        return 0;
    }

    private SideMode cachedSideMode(ResourceKind kind, RelativeSide side) {
        EnumMap<RelativeSide, SideMode> modes = host.sideConfigMap().get(kind);
        if (modes == null) return SideMode.DISABLED;
        return modes.getOrDefault(side, SideMode.DISABLED);
    }

    // ── 标签 ──

    private String kindLabel(ResourceKind kind) {
        return switch (kind) {
            case ITEM -> "物品";
            case FLUID -> "流体";
            case ENERGY -> "电力";
            case STRESS -> "应力";
        };
    }

    private String sideLabel(RelativeSide side) {
        return switch (side) {
            case FRONT -> "前";
            case BACK -> "后";
            case LEFT -> "左";
            case RIGHT -> "右";
            case UP -> "上";
            case DOWN -> "下";
        };
    }

    private String sideModeLabel(SideMode mode) {
        return switch (mode) {
            case DISABLED -> "关";
            case INPUT -> "入";
            case OUTPUT -> "出";
            case BOTH -> "双";
        };
    }

    private String stressSpeedLabel(int rpm) {
        return rpm <= 0 ? "同速" : rpm + "rpm";
    }

    private int sideModeText(SideMode mode) {
        return switch (mode) {
            case DISABLED -> Theme.TEXT_FAINT;
            case INPUT -> Theme.SUCCESS;
            case OUTPUT -> Theme.PRIMARY_PRESS;
            case BOTH -> 0xFF986A00;
        };
    }
}
