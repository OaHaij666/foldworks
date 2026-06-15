package com.pockethomestead.client.page;

import com.pockethomestead.PocketHomestead;
import com.pockethomestead.client.ClientDimensionBiomeCache;
import com.pockethomestead.client.LocalizationUtil;
import com.pockethomestead.client.ui.Page;
import com.pockethomestead.client.ui.Theme;
import com.pockethomestead.client.ui.widget.UiButton;
import com.pockethomestead.client.ui.widget.UiDropdown;
import com.pockethomestead.client.ui.widget.UiSlider;
import com.pockethomestead.client.ui.widget.UiToggle;
import com.pockethomestead.config.ModConfig;
import com.pockethomestead.network.CreateSpaceConfigPayload;
import com.pockethomestead.network.CreateSpacePayload;
import com.pockethomestead.network.RequestDimensionBiomesPayload;
import com.pockethomestead.space.SpaceData;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/** 创建页：地形/尺寸/继承维度/群系/世界规则/无限世界。群系随所选维度动态加载。 */
public class CreatePage extends Page {

    private record ListEntry(String id, String label) {}

    private enum TerrainMode { FLAT, NATURAL, INFINITE }
    private static final TerrainMode[] TERRAIN_MODES = TerrainMode.values();
    private static int minSize() { return ModConfig.MIN_SPACE_SIZE.get(); }
    private static int maxSize() { return ModConfig.MAX_SPACE_SIZE.get(); }

    private final List<ListEntry> dimensions = new ArrayList<>();
    private final List<String> biomeIds = new ArrayList<>();    // 与 biomeDropdown 标签同序，index0="random"
    private final Set<String> requestedDims = new HashSet<>();

    private int terrainIndex = 0;
    private boolean dataLoaded = false;
    private boolean widgetsBuilt = false;
    private String status = "";
    private double formScroll = 0;
    private int formContentHeight = 0;

    private String builtBiomeDim = null; // 当前 biome 列表对应的维度

    private EditBox widthInput, depthInput;
    private UiDropdown dimDropdown, biomeDropdown;
    private UiToggle mobToggle, structToggle, amplitudeToggle;
    private UiSlider amplitudeSlider;
    private UiButton createBtn, cancelBtn;

    @Override public String id() { return "create"; }
    @Override public String navTitle() { return Component.translatable("pockethomestead.ui.nav.create").getString(); }
    @Override public String navIcon() { return "✦"; }

    @Override
    public void onEnter() {
        if (!dataLoaded) loadData();
        formScroll = 0;
        closeDropdowns();
    }

    // ------------------------------------------------------------------
    // 数据
    // ------------------------------------------------------------------
    private void loadData() {
        var conn = mc.getConnection();
        if (conn == null) return;
        dimensions.clear();
        dimensions.add(new ListEntry("random", Component.translatable("pockethomestead.space.dimension.random").getString()));
        try {
            for (ResourceKey<Level> key : conn.levels()) {
                ResourceLocation loc = key.location();
                if (loc.getNamespace().equals(PocketHomestead.MODID) && loc.getPath().startsWith("space_")) continue;
                String localized = LocalizationUtil.localizeDimension(loc.toString());
                dimensions.add(new ListEntry(loc.toString(), localized));
            }
        } catch (Exception e) {
            PocketHomestead.LOGGER.warn("Failed to load dimensions", e);
        }
        dataLoaded = true;
        widgetsBuilt = false;
    }

    private void buildWidgets() {
        widthInput = makeSizeBox("64");
        depthInput = makeSizeBox("64");

        List<String> dimLabels = new ArrayList<>();
        for (ListEntry e : dimensions) dimLabels.add(e.label());
        dimDropdown = new UiDropdown(dimLabels, 0).onSelect(i -> onDimensionChanged());

        biomeDropdown = new UiDropdown(new ArrayList<>(List.of(
                Component.translatable("pockethomestead.space.biome.random").getString())), 0);
        biomeIds.clear();
        biomeIds.add("random");

        mobToggle = new UiToggle(Component.translatable("pockethomestead.space.mob_spawning").getString(), false);
        structToggle = new UiToggle(Component.translatable("pockethomestead.space.structures").getString(), false);
        amplitudeToggle = new UiToggle(Component.translatable("pockethomestead.space.amplitude_enable").getString(), false);
        amplitudeSlider = new UiSlider(Component.translatable("pockethomestead.space.amplitude").getString(), 0.4f);

        createBtn = new UiButton(Component.translatable("pockethomestead.space.create_and_enter").getString(), UiButton.Variant.PRIMARY)
                .onClick(this::createSpace);
        cancelBtn = new UiButton(Component.translatable("pockethomestead.space.cancel").getString(), UiButton.Variant.GHOST)
                .onClick(() -> mc.setScreen(null));

        widgetsBuilt = true;
    }

    private EditBox makeSizeBox(String def) {
        int max = maxSize();
        EditBox box = new EditBox(font, 0, 0, 60, 16, Component.translatable("pockethomestead.space.size.placeholder"));
        box.setMaxLength(Integer.toString(max).length());
        box.setValue(def);
        box.setBordered(false);
        box.setTextColor(Theme.TEXT);
        box.setFilter(s -> {
            if (s.isEmpty()) return true;
            try { int v = Integer.parseInt(s); return v >= 0 && v <= max; }
            catch (NumberFormatException e) { return false; }
        });
        return box;
    }

    private String currentDimId() {
        return dimensions.get(Math.min(dimDropdown.selected(), dimensions.size() - 1)).id();
    }

    private void onDimensionChanged() {
        biomeDropdown.setSelected(0);
        builtBiomeDim = null; // 触发 refreshBiomeList
        String dimId = currentDimId();
        if (!dimId.equals("random") && !ClientDimensionBiomeCache.has(dimId) && !requestedDims.contains(dimId)) {
            requestedDims.add(dimId);
            PacketDistributor.sendToServer(new RequestDimensionBiomesPayload(ResourceLocation.parse(dimId)));
        }
    }

    /** 依据当前维度与缓存重建群系列表。 */
    private void refreshBiomeList() {
        String dimId = currentDimId();
        boolean cacheReady = dimId.equals("random") || ClientDimensionBiomeCache.has(dimId);
        // 仅在维度变化或缓存刚到达时重建
        boolean needRebuild = !dimId.equals(builtBiomeDim) || (!dimId.equals("random") && cacheReady && biomeIds.size() <= 1);
        if (!needRebuild) return;

        biomeIds.clear();
        List<String> labels = new ArrayList<>();
        biomeIds.add("random");
        labels.add(Component.translatable("pockethomestead.space.biome.random").getString());
        if (!dimId.equals("random")) {
            List<String> cached = ClientDimensionBiomeCache.get(dimId);
            if (cached != null) {
                for (String id : cached) {
                    biomeIds.add(id);
                    labels.add(LocalizationUtil.localizeBiome(id));
                }
            }
        }
        biomeDropdown.setLabels(labels);
        if (cacheReady) builtBiomeDim = dimId;
    }

    // ------------------------------------------------------------------
    // 渲染
    // ------------------------------------------------------------------
    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (!dataLoaded) loadData();
        if (!widgetsBuilt) buildWidgets();
        refreshBiomeList();

        TerrainMode mode = TERRAIN_MODES[terrainIndex];
        boolean infinite = mode == TerrainMode.INFINITE;
        boolean natural = mode == TerrainMode.NATURAL;
        String dimId = currentDimId();

        int pad = Theme.PAD;
        int footerH = 44;
        int formX = x + pad;
        int formW = w - pad * 2;
        int formTop = y + pad;
        int formBottom = y + h - footerH;

        g.enableScissor(x, formTop, x + w, formBottom);
        int cy = formTop - (int) formScroll;

        // 空间类型：平坦 / 自然 / 无限
        cy = section(g, formX, cy, Component.translatable("pockethomestead.space.terrain").getString(), true);
        layoutTerrain(g, formX, cy, formW, mouseX, mouseY, true);
        cy += 30;

        // 地势调整（默认关闭，仅自然地形显示；效果可能不稳定，启用时给出警告）
        if (natural) {
            amplitudeToggle.bounds(formX, cy, formW, 24).render(g, mouseX, mouseY, partialTick);
            cy += 24;
            g.drawString(font, Theme.styled(Component.translatable("pockethomestead.space.amplitude.warning").getString()), formX + 8, cy + 2, Theme.TEXT_FAINT, false);
            cy += 14;
            if (amplitudeToggle.value()) {
                amplitudeSlider.enabled(true).bounds(formX, cy, formW, 28).render(g, mouseX, mouseY, partialTick);
                cy += 32;
            }
        }

        // 尺寸
        cy = section(g, formX, cy, Component.translatable("pockethomestead.space.size").getString(), !infinite);
        layoutSize(g, formX, cy, formW, mouseX, mouseY, !infinite);
        cy += 28;

        // 继承维度（始终可用）
        cy = section(g, formX, cy, Component.translatable("pockethomestead.space.dimension").getString(), true);
        dimDropdown.bounds(formX, cy, formW, 20);
        dimDropdown.render(g, mouseX, mouseY);
        cy += 28;

        // 群系（无限或随机维度时禁用）
        boolean biomeEnabled = !infinite && !dimId.equals("random");
        cy = section(g, formX, cy, Component.translatable("pockethomestead.space.biome").getString(), biomeEnabled);
        if (biomeEnabled) {
            biomeDropdown.bounds(formX, cy, formW, 20);
            biomeDropdown.render(g, mouseX, mouseY);
        } else {
            Theme.panel(g, formX, cy, formW, 20, Theme.RADIUS, Theme.SURFACE_SUNK, Theme.BORDER);
            String hint = infinite
                    ? Component.translatable("pockethomestead.space.biome.inherited").getString()
                    : Component.translatable("pockethomestead.space.biome.pick_dim_first").getString();
            g.drawString(font, Theme.styled(hint), formX + 8, cy + 6, Theme.TEXT_FAINT, false);
        }
        cy += 28;

        // 规则开关
        int half = (formW - Theme.GAP) / 2;
        mobToggle.bounds(formX, cy, half, 24).render(g, mouseX, mouseY, partialTick);
        structToggle.bounds(formX + half + Theme.GAP, cy, half, 24).render(g, mouseX, mouseY, partialTick);
        cy += 30;

        g.disableScissor();
        formContentHeight = (cy + (int) formScroll) - formTop;

        // 页脚
        Theme.hLine(g, x, formBottom, w, Theme.DIVIDER);
        int btnY = formBottom + 8;
        int cancelW = 96;
        cancelBtn.bounds(x + w - pad - cancelW, btnY, cancelW, 28);
        createBtn.label(status.isEmpty()
                        ? Component.translatable("pockethomestead.space.create_and_enter").getString()
                        : status)
                .bounds(formX, btnY, w - pad * 2 - cancelW - Theme.GAP, 28)
                .render(g, mouseX, mouseY, partialTick);
        cancelBtn.render(g, mouseX, mouseY, partialTick);
    }

    private int section(GuiGraphics g, int x, int y, String label, boolean enabled) {
        g.drawString(font, Theme.styled(label), x, y, enabled ? Theme.TEXT_MUTED : Theme.TEXT_FAINT, false);
        return y + 13;
    }

    private void layoutTerrain(GuiGraphics g, int x, int y, int w, int mouseX, int mouseY, boolean enabled) {
        int chipW = (w - Theme.GAP * 2) / 3;
        for (int i = 0; i < TERRAIN_MODES.length; i++) {
            int cx = x + i * (chipW + Theme.GAP);
            boolean active = i == terrainIndex;
            boolean hover = enabled && Theme.inside(mouseX, mouseY, cx, y, chipW, 26);
            int fill = !enabled ? Theme.SURFACE_SUNK : (active ? Theme.PRIMARY_SOFT : (hover ? Theme.SURFACE_ALT : Theme.SURFACE_SUNK));
            int border = !enabled ? Theme.BORDER : (active ? Theme.PRIMARY : Theme.BORDER);
            Theme.panel(g, cx, y, chipW, 26, Theme.RADIUS, fill, border);
            String label = switch (TERRAIN_MODES[i]) {
                case FLAT -> Component.translatable("pockethomestead.space.terrain.superflat").getString();
                case NATURAL -> Component.translatable("pockethomestead.space.terrain.natural").getString();
                case INFINITE -> Component.translatable("pockethomestead.space.infinite_tag").getString();
            };
            int textColor = !enabled ? Theme.TEXT_FAINT : (active ? Theme.PRIMARY_PRESS : Theme.TEXT);
            Theme.textInBox(g, font, label, cx, y, chipW, 26, textColor);
        }
    }

    private void layoutSize(GuiGraphics g, int x, int y, int w, int mouseX, int mouseY, boolean enabled) {
        int half = (w - Theme.GAP) / 2;
        drawField(g, x, y, half, Component.translatable("pockethomestead.space.size.width").getString(), widthInput, enabled);
        drawField(g, x + half + Theme.GAP, y, half, Component.translatable("pockethomestead.space.size.depth").getString(), depthInput, enabled);
    }

    private void drawField(GuiGraphics g, int x, int y, int w, String label, EditBox box, boolean enabled) {
        int labelW = Theme.styledWidth(font, label) + 6;
        Theme.panel(g, x, y, w, 22, Theme.RADIUS, enabled ? Theme.SURFACE_SUNK : Theme.SURFACE_ALT, enabled ? Theme.BORDER_STRONG : Theme.BORDER);
        g.drawString(font, Theme.styled(label), x + 6, y + 7, enabled ? Theme.TEXT_MUTED : Theme.TEXT_FAINT, false);
        int textX = x + labelW + 4;
        int textY = y + 7;
        box.setX(textX);
        box.setY(textY);
        box.setWidth(w - labelW - 12);
        box.setEditable(enabled);
        // 手动平绘数值（无阴影），与整体 UI 风格一致；EditBox 自带阴影会让数字看起来“重影”
        String value = box.getValue();
        g.drawString(font, Theme.styled(value), textX, textY, enabled ? Theme.TEXT : Theme.TEXT_FAINT, false);
        if (enabled && box.isFocused() && (System.currentTimeMillis() / 500) % 2 == 0) {
            int caretX = textX + Theme.styledWidth(font, value);
            g.fill(caretX + 1, textY - 1, caretX + 2, textY + font.lineHeight, Theme.TEXT);
        }
        // 记录可点击的字段面板范围（供聚焦判断）
        if (box == widthInput) { wFieldX = x; wFieldY = y; wFieldW = w; }
        else { dFieldX = x; dFieldY = y; dFieldW = w; }
    }

    private int wFieldX, wFieldY, wFieldW, dFieldX, dFieldY, dFieldW;

    @Override
    public void renderOverlay(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        dimDropdown.renderPopup(g, mouseX, mouseY);
        if (TERRAIN_MODES[terrainIndex] != TerrainMode.INFINITE && !currentDimId().equals("random")) biomeDropdown.renderPopup(g, mouseX, mouseY);
    }

    // ------------------------------------------------------------------
    // 输入
    // ------------------------------------------------------------------
    @Override
    public boolean overlayMouseClicked(double mx, double my, int button) {
        if (dimDropdown.isOpen()) return dimDropdown.popupMouseClicked(mx, my, button);
        if (biomeDropdown.isOpen()) return biomeDropdown.popupMouseClicked(mx, my, button);
        return false;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (!widgetsBuilt) return false;
        boolean infinite = TERRAIN_MODES[terrainIndex] == TerrainMode.INFINITE;

        // 尺寸框聚焦（仅非无限）
        if (!infinite) {
            boolean wHit = Theme.inside(mx, my, wFieldX, wFieldY, wFieldW, 22);
            boolean dHit = Theme.inside(mx, my, dFieldX, dFieldY, dFieldW, 22);
            widthInput.setFocused(wHit);
            depthInput.setFocused(dHit);
            if (wHit || dHit) return true;
        }

        // 下拉
        if (dimDropdown.mouseClicked(mx, my, button)) { biomeDropdown.close(); return true; }
        if (!infinite && !currentDimId().equals("random") && biomeDropdown.mouseClicked(mx, my, button)) { dimDropdown.close(); return true; }

        // 地形 chips（三选一）
        int pad = Theme.PAD;
        int formX = x + pad, formW = w - pad * 2;
        int terrainY = (y + pad) - (int) formScroll + 13;
        int chipW = (formW - Theme.GAP * 2) / 3;
        for (int i = 0; i < TERRAIN_MODES.length; i++) {
            int cx = formX + i * (chipW + Theme.GAP);
            if (Theme.inside(mx, my, cx, terrainY, chipW, 26)) { terrainIndex = i; return true; }
        }

        // 平缓度开关/滑块
        boolean natural = TERRAIN_MODES[terrainIndex] == TerrainMode.NATURAL;
        if (natural && amplitudeToggle.mouseClicked(mx, my, button)) return true;
        if (natural && amplitudeToggle.value() && amplitudeSlider.mouseClicked(mx, my, button)) return true;

        // 规则开关
        if (mobToggle.mouseClicked(mx, my, button)) return true;
        if (structToggle.mouseClicked(mx, my, button)) return true;

        // 页脚
        if (createBtn.mouseClicked(mx, my, button)) return true;
        if (cancelBtn.mouseClicked(mx, my, button)) return true;
        return false;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        return amplitudeSlider != null && amplitudeSlider.mouseDragged(mx, my, button);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        return amplitudeSlider != null && amplitudeSlider.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (dimDropdown.isOpen()) return dimDropdown.popupMouseScrolled(mx, my, sy);
        if (biomeDropdown.isOpen()) return biomeDropdown.popupMouseScrolled(mx, my, sy);
        int footerH = 44;
        if (Theme.inside(mx, my, x, y, w, h - footerH)) {
            int maxScroll = Math.max(0, formContentHeight - (h - footerH - Theme.PAD));
            formScroll = Math.max(0, Math.min(maxScroll, formScroll - sy * 18));
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!widgetsBuilt) return false;
        if (widthInput.keyPressed(keyCode, scanCode, modifiers)) return true;
        if (depthInput.keyPressed(keyCode, scanCode, modifiers)) return true;
        return widthInput.isFocused() || depthInput.isFocused();
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (!widgetsBuilt) return false;
        if (widthInput.charTyped(codePoint, modifiers)) return true;
        if (depthInput.charTyped(codePoint, modifiers)) return true;
        return widthInput.isFocused() || depthInput.isFocused();
    }

    private void closeDropdowns() {
        if (dimDropdown != null) dimDropdown.close();
        if (biomeDropdown != null) biomeDropdown.close();
    }

    // ------------------------------------------------------------------
    // 创建
    // ------------------------------------------------------------------
    private int parseSize(EditBox box) {
        try { return Integer.parseInt(box.getValue()); } catch (NumberFormatException e) { return 64; }
    }

    private void createSpace() {
        if (!status.isEmpty() || mc.player == null) return;
        TerrainMode mode = TERRAIN_MODES[terrainIndex];
        boolean infinite = mode == TerrainMode.INFINITE;

        int wv = 64, dv = 64;
        if (!infinite) {
            wv = parseSize(widthInput);
            dv = parseSize(depthInput);
            int min = minSize(), max = maxSize();
            if (wv < min || wv > max || dv < min || dv > max) {
                status = Component.translatable("pockethomestead.space.size.invalid").getString();
                return;
            }
        }
        status = Component.translatable("pockethomestead.space.creating").getString();

        SpaceData.TerrainType terrain = mode == TerrainMode.NATURAL ? SpaceData.TerrainType.NATURAL : SpaceData.TerrainType.FLAT;
        float amplitude = amplitudeToggle.value() ? amplitudeSlider.value() : 0.4f;

        // 维度：随机则客户端挑一个具体维度（无限世界需要具体源维度来克隆）
        String dimId = currentDimId();
        if (dimId.equals("random")) {
            List<String> concrete = dimensions.stream().map(ListEntry::id).filter(s -> !s.equals("random")).toList();
            dimId = concrete.isEmpty() ? "minecraft:overworld" : concrete.get(new Random().nextInt(concrete.size()));
        }

        // 群系：无限/随机维度 → "random"（服务端按源维度解析）；否则用所选
        String biome = "random";
        if (!infinite && !dimId.equals("random")) {
            int sel = biomeDropdown.selected();
            if (sel > 0 && sel < biomeIds.size()) biome = biomeIds.get(sel);
        }

        PacketDistributor.sendToServer(new CreateSpacePayload(
                wv, dv, terrain, biome, mobToggle.value(), structToggle.value(), infinite, amplitude));
        PacketDistributor.sendToServer(new CreateSpaceConfigPayload(ResourceLocation.parse(dimId)));

        status = "";
        if (router != null) router.setActive("manage");
    }
}
