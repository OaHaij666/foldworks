package com.pockethomestead.client.page;

import com.pockethomestead.client.ClientSpaceCache;
import com.pockethomestead.client.ui.Page;
import com.pockethomestead.client.ui.Theme;
import com.pockethomestead.client.ui.widget.UiButton;
import com.pockethomestead.client.ui.widget.UiScrollList;
import com.pockethomestead.dimension.PocketDimensionManager;
import com.pockethomestead.network.PermissionMemberPayload;
import com.pockethomestead.network.RequestSpaceListPayload;
import com.pockethomestead.network.SpaceActionPayload;
import com.pockethomestead.network.SpaceInfo;
import com.pockethomestead.network.UpdatePermissionPayload;
import com.pockethomestead.space.SpacePermission;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 管理页：列表 + 进入/退出/删除（二次确认）+ 权限编辑模态。 */
public class ManagePage extends Page {

    private enum Modal { NONE, CONFIRM_DELETE, PERMISSION }

    private final List<SpaceInfo> spaces = new ArrayList<>();
    private boolean inPocketDim;

    private UiScrollList<SpaceInfo> list;
    private UiButton createCta, exitBtn;

    private Modal modal = Modal.NONE;
    private SpaceInfo target;
    private EditBox permNameInput;

    private static final int ROW_H = 46;
    private static final int ROW_GAP = 6;
    private static final int BTN = 20;
    private static final int PILL_W = 50;
    private static final int CUR_W = 54;
    private static final int PILL_H = 22;
    private static final SpacePermission.AccessMode[] MODES = SpacePermission.AccessMode.values();

    // 卡片右侧按钮命中区（由 computeCardRects 写入）
    private boolean hasDelete, hasSettings;
    private int delX, setX, pillX, btnY, pillY;

    @Override public String id() { return "manage"; }
    @Override public String navTitle() { return Component.translatable("pockethomestead.ui.nav.manage").getString(); }
    @Override public String navIcon() { return "❖"; }

    @Override
    public void onEnter() {
        inPocketDim = mc.player != null
                && PocketDimensionManager.getInstance().isPocketDimension(mc.player.level().dimension());
        spaces.clear();
        spaces.addAll(ClientSpaceCache.get());
        modal = Modal.NONE;
        target = null;
        PacketDistributor.sendToServer(new RequestSpaceListPayload());
        if (list == null) buildWidgets();
    }

    @Override
    public void tick() {
        List<SpaceInfo> fresh = ClientSpaceCache.get();
        if (fresh.size() != spaces.size() || !spaces.containsAll(fresh)) {
            spaces.clear();
            spaces.addAll(fresh);
        }
        // 模态打开时刷新 target（反映服务端权限变更）
        if (modal == Modal.PERMISSION && target != null) {
            for (SpaceInfo s : spaces) {
                if (s.spaceId().equals(target.spaceId())) { target = s; break; }
            }
        }
    }

    private void buildWidgets() {
        list = new UiScrollList<>(ROW_H, ROW_GAP, this::renderCard, this::onCardClick);
        createCta = new UiButton(Component.translatable("pockethomestead.space.list.new_homestead").getString(),
                UiButton.Variant.PRIMARY).onClick(() -> { if (router != null) router.setActive("create"); });
        exitBtn = new UiButton(Component.translatable("pockethomestead.space.list.exit").getString(),
                UiButton.Variant.SECONDARY).onClick(this::exitSpace);
    }

    // ------------------------------------------------------------------
    // 渲染
    // ------------------------------------------------------------------
    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (list == null) buildWidgets();

        int pad = Theme.PAD;
        int footerH = 44;
        int listX = x + pad, listY = y + pad, listW = w - pad * 2, listH = h - pad - footerH;

        String sub = inPocketDim
                ? Component.translatable("pockethomestead.space.list.in_space").getString()
                : Component.translatable("pockethomestead.space.list.choose").getString();
        g.drawString(font, Theme.styled(sub), listX, listY, Theme.TEXT_MUTED, false);
        listY += 16; listH -= 16;

        list.bounds(listX, listY, listW, listH);
        list.setItems(spaces);
        if (spaces.isEmpty()) drawEmptyState(g, listX, listY, listW, listH);
        else list.render(g, mouseX, mouseY, partialTick);

        int formBottom = y + h - footerH;
        Theme.hLine(g, x, formBottom, w, Theme.DIVIDER);
        int by = formBottom + 8;
        if (inPocketDim) {
            int half = (w - pad * 2 - Theme.GAP) / 2;
            createCta.bounds(x + pad, by, half, 28).render(g, mouseX, mouseY, partialTick);
            exitBtn.bounds(x + pad + half + Theme.GAP, by, half, 28).render(g, mouseX, mouseY, partialTick);
        } else {
            createCta.bounds(x + pad, by, w - pad * 2, 28).render(g, mouseX, mouseY, partialTick);
        }
    }

    private void drawEmptyState(GuiGraphics g, int x, int y, int w, int h) {
        Theme.panel(g, x, y, w, h, Theme.RADIUS, Theme.SURFACE_ALT, Theme.BORDER);
        Theme.textCentered(g, font, "❖", x + w / 2, y + h / 2 - 24, Theme.BORDER_STRONG);
        Theme.textCentered(g, font, Component.translatable("pockethomestead.space.list.no_homesteads").getString(), x + w / 2, y + h / 2 - 6, Theme.TEXT);
        Theme.textCentered(g, font, Component.translatable("pockethomestead.space.list.create_first").getString(), x + w / 2, y + h / 2 + 8, Theme.TEXT_MUTED);
    }

    private void computeCardRects(int rx, int ry, int rw, int rh, boolean owner, boolean current) {
        int rightEdge = rx + rw - 12;
        btnY = ry + (rh - BTN) / 2;
        pillY = ry + (rh - PILL_H) / 2;
        hasDelete = owner && !current;
        hasSettings = owner;
        if (hasDelete) { delX = rightEdge - BTN; rightEdge = delX - 6; }
        if (hasSettings) { setX = rightEdge - BTN; rightEdge = setX - 6; }
        pillX = rightEdge - (current ? CUR_W : PILL_W);
    }

    private void renderCard(GuiGraphics g, SpaceInfo space, int rx, int ry, int rw, int rh, int mouseX, int mouseY, boolean hovered) {
        boolean current = isCurrent(space);
        boolean owner = mc.player != null && space.isOwner(mc.player.getUUID());
        computeCardRects(rx, ry, rw, rh, owner, current);

        int fill = current ? 0xFFE7F6EC : (hovered ? Theme.PRIMARY_SOFT : Theme.SURFACE_ALT);
        int border = current ? Theme.SUCCESS : (hovered ? Theme.PRIMARY : Theme.BORDER);
        Theme.panel(g, rx, ry, rw, rh, Theme.RADIUS, fill, border);

        int textRight = pillX - 8; // 标题可用右界
        String name = Theme.ellipsize(font, space.name(), textRight - (rx + 12));
        g.drawString(font, Theme.styled(name), rx + 12, ry + 8, Theme.TEXT, false);
        String tag = space.infinite() ? Component.translatable("pockethomestead.space.infinite_tag").getString()
                : space.width() + "×" + space.depth();
        String biome = space.biome().contains(":") ? space.biome().substring(space.biome().indexOf(':') + 1) : space.biome();
        String meta = tag + "   " + pretty(space.terrain().name()) + "   " + pretty(biome) + "   " + modeLabel(space.mode());
        g.drawString(font, Theme.styled(Theme.ellipsize(font, meta, textRight - (rx + 12))), rx + 12, ry + 26, Theme.TEXT_MUTED, false);

        // 进入 / 当前 药丸
        if (current) {
            Theme.fillRound(g, pillX, pillY, CUR_W, PILL_H, Theme.RADIUS, Theme.SUCCESS);
            Theme.textInBox(g, font, Component.translatable("pockethomestead.space.list.current").getString(), pillX, pillY, CUR_W, PILL_H, Theme.TEXT_ON_PRIM);
        } else {
            boolean ph = Theme.inside(mouseX, mouseY, pillX, pillY, PILL_W, PILL_H);
            Theme.fillRound(g, pillX, pillY, PILL_W, PILL_H, Theme.RADIUS, ph ? Theme.PRIMARY_HOVER : Theme.PRIMARY);
            Theme.textInBox(g, font, Component.translatable("pockethomestead.space.list.enter").getString(), pillX, pillY, PILL_W, PILL_H, Theme.TEXT_ON_PRIM);
        }
        // 设置 ⚙
        if (hasSettings) {
            boolean sh = Theme.inside(mouseX, mouseY, setX, btnY, BTN, BTN);
            Theme.fillRound(g, setX, btnY, BTN, BTN, Theme.RADIUS, sh ? Theme.PRIMARY_SOFT : Theme.SURFACE_SUNK);
            Theme.textInBox(g, font, "⚙", setX, btnY, BTN, BTN, Theme.TEXT_MUTED);
        }
        // 删除 ✕
        if (hasDelete) {
            boolean dh = Theme.inside(mouseX, mouseY, delX, btnY, BTN, BTN);
            Theme.fillRound(g, delX, btnY, BTN, BTN, Theme.RADIUS, dh ? Theme.DANGER_HOVER : Theme.DANGER_SOFT);
            Theme.textInBox(g, font, "✕", delX, btnY, BTN, BTN, dh ? Theme.TEXT_ON_PRIM : Theme.DANGER);
        }
    }

    private boolean onCardClick(SpaceInfo space, double mx, double my, int button, int rx, int ry, int rw, int rh) {
        if (button != 0) return false;
        boolean current = isCurrent(space);
        boolean owner = mc.player != null && space.isOwner(mc.player.getUUID());
        computeCardRects(rx, ry, rw, rh, owner, current);

        if (hasDelete && Theme.inside(mx, my, delX, btnY, BTN, BTN)) { openDeleteConfirm(space); return true; }
        if (hasSettings && Theme.inside(mx, my, setX, btnY, BTN, BTN)) { openPermission(space); return true; }
        if (!current && Theme.inside(mx, my, pillX, pillY, PILL_W, PILL_H)) { enterSpace(space); return true; }
        if (!current) { enterSpace(space); return true; }
        return false;
    }

    // ------------------------------------------------------------------
    // 模态
    // ------------------------------------------------------------------
    @Override
    public void renderOverlay(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (modal == Modal.NONE) return;
        g.fill(x, y, x + w, y + h, 0x990C1626); // 内容区遮罩
        if (modal == Modal.CONFIRM_DELETE) renderConfirm(g, mouseX, mouseY);
        else if (modal == Modal.PERMISSION) renderPermission(g, mouseX, mouseY, partialTick);
    }

    private void renderConfirm(GuiGraphics g, int mouseX, int mouseY) {
        int mw = Math.min(w - 24, 260), mh = 116;
        int mx = x + (w - mw) / 2, my = y + (h - mh) / 2;
        Theme.shadow(g, mx, my, mw, mh, Theme.RADIUS);
        Theme.panel(g, mx, my, mw, mh, Theme.RADIUS, Theme.SURFACE, Theme.BORDER);
        Theme.textCentered(g, font, Component.translatable("pockethomestead.space.delete.confirm_title").getString(), mx + mw / 2, my + 14, Theme.TEXT);
        String line = Component.translatable("pockethomestead.space.delete.confirm_body", target != null ? target.name() : "").getString();
        Theme.textCentered(g, font, Theme.ellipsize(font, line, mw - 20), mx + mw / 2, my + 38, Theme.TEXT_MUTED);

        int bw = (mw - 16 - Theme.GAP) / 2;
        int by = my + mh - 36;
        drawModalButton(g, mx + 8, by, bw, Component.translatable("pockethomestead.space.cancel").getString(), false, mouseX, mouseY);
        drawModalButton(g, mx + 8 + bw + Theme.GAP, by, bw, Component.translatable("pockethomestead.space.delete.confirm_ok").getString(), true, mouseX, mouseY);
    }

    private void renderPermission(GuiGraphics g, int mouseX, int mouseY, float pt) {
        if (target == null) { modal = Modal.NONE; return; }
        int mw = Math.min(w - 16, 290), mh = Math.min(h - 16, 220);
        int mx = x + (w - mw) / 2, my = y + (h - mh) / 2;
        Theme.shadow(g, mx, my, mw, mh, Theme.RADIUS);
        Theme.panel(g, mx, my, mw, mh, Theme.RADIUS, Theme.SURFACE, Theme.BORDER);

        String title = Component.translatable("pockethomestead.permission.title").getString() + " · " + target.name();
        g.drawString(font, Theme.styled(Theme.ellipsize(font, title, mw - 40)), mx + 12, my + 12, Theme.TEXT, false);
        // 关闭 ✕
        boolean ch = Theme.inside(mouseX, mouseY, mx + mw - 24, my + 10, 16, 16);
        Theme.textInBox(g, font, "✕", mx + mw - 24, my + 10, 16, 16, ch ? Theme.DANGER : Theme.TEXT_MUTED);

        // 模式分段
        int chipsY = my + 32;
        int chipW = (mw - 24 - 3 * 4) / 4;
        for (int i = 0; i < MODES.length; i++) {
            int cx = mx + 12 + i * (chipW + 4);
            boolean active = target.mode() == MODES[i];
            boolean hov = Theme.inside(mouseX, mouseY, cx, chipsY, chipW, 22);
            Theme.panel(g, cx, chipsY, chipW, 22, Theme.RADIUS, active ? Theme.PRIMARY_SOFT : (hov ? Theme.SURFACE_ALT : Theme.SURFACE_SUNK), active ? Theme.PRIMARY : Theme.BORDER);
            Theme.textInBox(g, font, modeLabel(MODES[i]), cx, chipsY, chipW, 22, active ? Theme.PRIMARY_PRESS : Theme.TEXT);
        }

        boolean listMode = target.mode() == SpacePermission.AccessMode.WHITELIST || target.mode() == SpacePermission.AccessMode.BLACKLIST;
        if (!listMode) {
            Theme.textCentered(g, font, Component.translatable("pockethomestead.permission.no_list_hint").getString(), mx + mw / 2, my + 70, Theme.TEXT_FAINT);
            return;
        }

        // 名字输入 + 添加
        int inputY = chipsY + 30;
        int addW = 48;
        int inputW = mw - 24 - addW - Theme.GAP;
        Theme.panel(g, mx + 12, inputY, inputW, 20, Theme.RADIUS, Theme.SURFACE_SUNK, Theme.BORDER_STRONG);
        if (permNameInput == null) permNameInput = makeNameInput();
        permNameInput.setX(mx + 16);
        permNameInput.setY(inputY + 6);
        permNameInput.setWidth(inputW - 8);
        permNameInput.render(g, 0, 0, pt);
        boolean addHov = Theme.inside(mouseX, mouseY, mx + 12 + inputW + Theme.GAP, inputY, addW, 20);
        Theme.fillRound(g, mx + 12 + inputW + Theme.GAP, inputY, addW, 20, Theme.RADIUS, addHov ? Theme.PRIMARY_HOVER : Theme.PRIMARY);
        Theme.textInBox(g, font, Component.translatable("pockethomestead.permission.add").getString(), mx + 12 + inputW + Theme.GAP, inputY, addW, 20, Theme.TEXT_ON_PRIM);

        // 成员列表
        int memTop = inputY + 26;
        int memBottom = my + mh - 10;
        g.enableScissor(mx + 12, memTop, mx + mw - 12, memBottom);
        List<SpaceInfo.Member> members = target.members();
        if (members.isEmpty()) {
            Theme.textCentered(g, font, Component.translatable("pockethomestead.permission.empty").getString(), mx + mw / 2, memTop + 8, Theme.TEXT_FAINT);
        } else {
            int row = memTop;
            for (SpaceInfo.Member m : members) {
                if (row > memBottom) break;
                Theme.fillRound(g, mx + 12, row, mw - 24, 18, 3, Theme.SURFACE_ALT);
                g.drawString(font, Theme.styled(Theme.ellipsize(font, m.name(), mw - 24 - 24)), mx + 16, row + 5, Theme.TEXT, false);
                boolean rh = Theme.inside(mouseX, mouseY, mx + mw - 12 - 18, row, 18, 18);
                Theme.textInBox(g, font, "✕", mx + mw - 12 - 18, row, 18, 18, rh ? Theme.DANGER : Theme.TEXT_MUTED);
                row += 20;
            }
        }
        g.disableScissor();
    }

    private void drawModalButton(GuiGraphics g, int x, int y, int w, String label, boolean danger, int mouseX, int mouseY) {
        boolean hov = Theme.inside(mouseX, mouseY, x, y, w, 26);
        int fill = danger ? (hov ? Theme.DANGER_HOVER : Theme.DANGER) : (hov ? Theme.SURFACE_ALT : Theme.SURFACE_SUNK);
        if (danger) Theme.fillRound(g, x, y, w, 26, Theme.RADIUS, fill);
        else Theme.panel(g, x, y, w, 26, Theme.RADIUS, fill, Theme.BORDER_STRONG);
        Theme.textInBox(g, font, label, x, y, w, 26, danger ? Theme.TEXT_ON_PRIM : Theme.TEXT);
    }

    private EditBox makeNameInput() {
        EditBox box = new EditBox(font, 0, 0, 80, 14, Component.translatable("pockethomestead.permission.name_hint"));
        box.setMaxLength(16);
        box.setBordered(false);
        box.setTextColor(Theme.TEXT);
        return box;
    }

    @Override
    public boolean overlayMouseClicked(double mx, double my, int button) {
        if (modal == Modal.NONE) return false;
        if (button != 0) return true;
        if (modal == Modal.CONFIRM_DELETE) { confirmClick(mx, my); return true; }
        if (modal == Modal.PERMISSION) { permissionClick(mx, my); return true; }
        return true;
    }

    private void confirmClick(double mx, double my) {
        int mw = Math.min(w - 24, 260), mh = 116;
        int mx0 = x + (w - mw) / 2, my0 = y + (h - mh) / 2;
        int bw = (mw - 16 - Theme.GAP) / 2;
        int by = my0 + mh - 36;
        if (Theme.inside(mx, my, mx0 + 8, by, bw, 26)) { modal = Modal.NONE; target = null; return; }
        if (Theme.inside(mx, my, mx0 + 8 + bw + Theme.GAP, by, bw, 26)) {
            if (target != null) { spaces.remove(target); PacketDistributor.sendToServer(new SpaceActionPayload(SpaceActionPayload.Action.DELETE, target.spaceId())); }
            modal = Modal.NONE; target = null;
        }
    }

    private void permissionClick(double mx, double my) {
        if (target == null) { modal = Modal.NONE; return; }
        int mw = Math.min(w - 16, 290), mh = Math.min(h - 16, 220);
        int mx0 = x + (w - mw) / 2, my0 = y + (h - mh) / 2;

        if (permNameInput != null) permNameInput.setFocused(false);

        // 关闭
        if (Theme.inside(mx, my, mx0 + mw - 24, my0 + 10, 16, 16)) { closePermission(); return; }
        // 模式分段
        int chipsY = my0 + 32;
        int chipW = (mw - 24 - 3 * 4) / 4;
        for (int i = 0; i < MODES.length; i++) {
            int cx = mx0 + 12 + i * (chipW + 4);
            if (Theme.inside(mx, my, cx, chipsY, chipW, 22)) {
                PacketDistributor.sendToServer(new UpdatePermissionPayload(target.spaceId(), MODES[i]));
                // 乐观更新本地 target 模式
                target = new SpaceInfo(target.spaceId(), target.ownerId(), target.name(), target.width(), target.depth(),
                        target.biome(), target.terrain(), target.dimensionId(), target.infinite(), target.amplitude(),
                        MODES[i], target.members());
                return;
            }
        }

        boolean listMode = target.mode() == SpacePermission.AccessMode.WHITELIST || target.mode() == SpacePermission.AccessMode.BLACKLIST;
        if (!listMode) return;

        int inputY = chipsY + 30;
        int addW = 48;
        int inputW = mw - 24 - addW - Theme.GAP;
        // 输入框聚焦
        if (Theme.inside(mx, my, mx0 + 12, inputY, inputW, 20)) {
            if (permNameInput != null) permNameInput.setFocused(true);
            return;
        }
        // 添加
        if (Theme.inside(mx, my, mx0 + 12 + inputW + Theme.GAP, inputY, addW, 20)) {
            submitAdd();
            return;
        }
        // 成员移除
        int memTop = inputY + 26;
        int memBottom = my0 + mh - 10;
        int row = memTop;
        for (SpaceInfo.Member m : target.members()) {
            if (row > memBottom) break;
            if (Theme.inside(mx, my, mx0 + mw - 12 - 18, row, 18, 18)) {
                PacketDistributor.sendToServer(PermissionMemberPayload.remove(target.spaceId(), m.id()));
                return;
            }
            row += 20;
        }
    }

    private void submitAdd() {
        if (target == null || permNameInput == null) return;
        String name = permNameInput.getValue().trim();
        if (name.isEmpty()) return;
        PacketDistributor.sendToServer(PermissionMemberPayload.addByName(target.spaceId(), name));
        permNameInput.setValue("");
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (modal == Modal.PERMISSION && permNameInput != null) {
            if (keyCode == 257 || keyCode == 335) { submitAdd(); return true; } // Enter
            if (permNameInput.keyPressed(keyCode, scanCode, modifiers)) return true;
            return permNameInput.isFocused();
        }
        return false;
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (modal == Modal.PERMISSION && permNameInput != null) {
            if (permNameInput.charTyped(codePoint, modifiers)) return true;
            return permNameInput.isFocused();
        }
        return false;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (modal != Modal.NONE) return false; // 模态时点击走 overlayMouseClicked
        if (list != null && !spaces.isEmpty() && list.mouseClicked(mx, my, button)) return true;
        if (createCta != null && createCta.mouseClicked(mx, my, button)) return true;
        if (inPocketDim && exitBtn != null && exitBtn.mouseClicked(mx, my, button)) return true;
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (modal != Modal.NONE) return true;
        return list != null && list.mouseScrolled(mx, my, sy);
    }

    // ------------------------------------------------------------------
    // 动作
    // ------------------------------------------------------------------
    private void openDeleteConfirm(SpaceInfo space) { target = space; modal = Modal.CONFIRM_DELETE; }
    private void openPermission(SpaceInfo space) { target = space; modal = Modal.PERMISSION; if (permNameInput != null) permNameInput.setValue(""); }
    private void closePermission() { modal = Modal.NONE; target = null; if (permNameInput != null) permNameInput.setFocused(false); }

    private boolean isCurrent(SpaceInfo space) {
        if (mc.player == null) return false;
        return space.dimensionId().equals(mc.player.level().dimension().location().toString());
    }

    private void enterSpace(SpaceInfo space) {
        PacketDistributor.sendToServer(new SpaceActionPayload(SpaceActionPayload.Action.ENTER, space.spaceId()));
        mc.setScreen(null);
    }

    private void exitSpace() {
        PacketDistributor.sendToServer(new SpaceActionPayload(SpaceActionPayload.Action.EXIT, new UUID(0, 0)));
        mc.setScreen(null);
    }

    private String modeLabel(SpacePermission.AccessMode mode) {
        return switch (mode) {
            case PRIVATE -> Component.translatable("pockethomestead.permission.private").getString();
            case PUBLIC -> Component.translatable("pockethomestead.permission.public").getString();
            case WHITELIST -> Component.translatable("pockethomestead.permission.whitelist").getString();
            case BLACKLIST -> Component.translatable("pockethomestead.permission.blacklist").getString();
        };
    }

    private static String pretty(String id) {
        String lower = id.toLowerCase().replace('_', ' ');
        StringBuilder out = new StringBuilder();
        for (String part : lower.split(" ")) {
            if (!part.isEmpty()) out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1)).append(' ');
        }
        return out.toString().trim();
    }
}
