package com.foldworks.client.page;

import com.foldworks.client.ClientSpaceCache;
import com.foldworks.client.ui.Page;
import com.foldworks.client.ui.Theme;
import com.foldworks.client.search.SearchSupport;
import com.foldworks.client.ui.widget.UiButton;
import com.foldworks.client.ui.widget.UiScrollList;
import com.foldworks.dimension.ProductionSpaceManager;
import com.foldworks.network.PermissionMemberPayload;
import com.foldworks.network.RequestSpaceListPayload;
import com.foldworks.network.RenameSpacePayload;
import com.foldworks.network.SpaceActionPayload;
import com.foldworks.network.SpaceInfo;
import com.foldworks.network.UpdateOfflineSimulationPayload;
import com.foldworks.network.UpdatePermissionPayload;
import com.foldworks.network.UpdateSpaceChunkLoadingPayload;
import com.foldworks.space.SpacePermission;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** 管理页：列表 + 进入/退出/删除（二次确认）+ 权限编辑模态。 */
public class ManagePage extends Page {

    private enum Modal { NONE, CONFIRM_DELETE, PERMISSION, RENAME }

    private final List<SpaceInfo> spaces = new ArrayList<>();
    private final List<SpaceInfo> filteredSpaces = new ArrayList<>();
    private boolean inProductionSpace;

    private UiScrollList<SpaceInfo> list;
    private UiButton createCta, exitBtn;

    private Modal modal = Modal.NONE;
    private SpaceInfo target;
    private EditBox permNameInput;
    private EditBox renameInput;
    private EditBox ownerFilterInput;
    private long lastSeenSpaceVersion = -1;

    private static final int ROW_H = 60;
    private static final int ROW_GAP = 6;
    private static final int BTN = 20;
    private static final int PILL_W = 50;
    private static final int CUR_W = 54;
    private static final int OFFLINE_W = 36;
    private static final int CHUNKLOAD_W = 36;
    private static final int PILL_H = 22;
    private static final int FILTER_H = 48;
    private static final SpacePermission.AccessMode[] MODES = SpacePermission.AccessMode.values();
    private static final SpacePermission.AccessLevel[] FILTER_LEVELS = {
            SpacePermission.AccessLevel.VIEW,
            SpacePermission.AccessLevel.USE,
            SpacePermission.AccessLevel.WRITE,
            SpacePermission.AccessLevel.MANAGE
    };
    private final EnumSet<SpacePermission.AccessLevel> selectedLevels = EnumSet.noneOf(SpacePermission.AccessLevel.class);
    private final Set<UUID> selectedOwnerIds = new LinkedHashSet<>();
    private boolean ownerSelfFilter;
    private boolean ownerDropdownOpen;
    private int ownerDropdownScroll;
    private int onlineX, onlineY, onlineW, onlineH;

    // 卡片右侧按钮命中区（由 computeCardRects 写入）
    private boolean hasDelete, hasSettings;
    private boolean hasRename;
    private boolean hasOfflineToggle;
    private boolean hasChunkLoadToggle;
    private int delX, setX, renameX, offlineX, chunkLoadX, pillX, btnY, pillY;

    @Override public String id() { return "manage"; }
    @Override public String navTitle() { return Component.translatable("foldworks.ui.nav.manage").getString(); }
    @Override public String navIcon() { return "❖"; }

    @Override
    public void onEnter() {
        inProductionSpace = mc.player != null
                && ProductionSpaceManager.getInstance().isProductionSpaceDimension(mc.player.level().dimension());
        spaces.clear();
        spaces.addAll(ClientSpaceCache.get());
        lastSeenSpaceVersion = ClientSpaceCache.version();
        modal = Modal.NONE;
        target = null;
        PacketDistributor.sendToServer(new RequestSpaceListPayload());
        if (list == null) buildWidgets();
    }

    @Override
    public void tick() {
        long v = ClientSpaceCache.version();
        if (v != lastSeenSpaceVersion) {
            lastSeenSpaceVersion = v;
            spaces.clear();
            spaces.addAll(ClientSpaceCache.get());
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
        createCta = new UiButton(Component.translatable("foldworks.space.list.new_space").getString(),
                UiButton.Variant.PRIMARY).onClick(() -> { if (router != null) router.setActive("create"); });
        exitBtn = new UiButton(Component.translatable("foldworks.space.list.exit").getString(),
                UiButton.Variant.SECONDARY).onClick(this::exitSpace);
        ownerFilterInput = new EditBox(font, 0, 0, 80, 14, Component.literal("Owner UUID"));
        ownerFilterInput.setMaxLength(36);
        ownerFilterInput.setBordered(false);
        ownerFilterInput.setTextColor(Theme.TEXT);
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

        String sub = inProductionSpace
                ? Component.translatable("foldworks.space.list.in_space").getString()
                : Component.translatable("foldworks.space.list.choose").getString();
        g.drawString(font, Theme.styled(sub), listX, listY, Theme.TEXT_MUTED, false);
        listY += 16; listH -= 16;

        renderFilters(g, mouseX, mouseY, partialTick, listX, listY, listW);
        listY += FILTER_H;
        listH -= FILTER_H;

        rebuildFilteredSpaces();
        list.bounds(listX, listY, listW, listH);
        list.setItems(filteredSpaces);
        if (filteredSpaces.isEmpty()) drawEmptyState(g, listX, listY, listW, listH);
        else list.render(g, mouseX, mouseY, partialTick);

        int formBottom = y + h - footerH;
        Theme.hLine(g, x, formBottom, w, Theme.DIVIDER);
        int by = formBottom + 8;
        if (inProductionSpace) {
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
        String title = spaces.isEmpty()
                ? Component.translatable("foldworks.space.list.no_spaces").getString()
                : "没有符合筛选的空间";
        String body = spaces.isEmpty()
                ? Component.translatable("foldworks.space.list.create_first").getString()
                : "调整权限或 owner 筛选后再试";
        Theme.textCentered(g, font, title, x + w / 2, y + h / 2 - 6, Theme.TEXT);
        Theme.textCentered(g, font, body, x + w / 2, y + h / 2 + 8, Theme.TEXT_MUTED);
    }

    private void renderFilters(GuiGraphics g, int mouseX, int mouseY, float partialTick, int fx, int fy, int fw) {
        Theme.panel(g, fx, fy, fw, FILTER_H - 6, Theme.RADIUS, 0xFFF8FCFF, Theme.DIVIDER);
        int row1 = fy + 5;
        Theme.text(g, font, "权限", fx + 8, row1 + 5, Theme.TEXT_MUTED);
        int cx = fx + 38;
        for (SpacePermission.AccessLevel level : FILTER_LEVELS) {
            int chipW = level == SpacePermission.AccessLevel.MANAGE ? 38 : 34;
            drawFilterChip(g, cx, row1, chipW, 17, levelLabel(level), selectedLevels.contains(level), mouseX, mouseY);
            cx += chipW + 4;
        }

        int row2 = fy + 25;
        Theme.text(g, font, "Owner", fx + 8, row2 + 5, Theme.TEXT_MUTED);
        int selfX = fx + 45;
        drawFilterChip(g, selfX, row2, 36, 17, "自己", ownerSelfFilter, mouseX, mouseY);

        int clearW = 34;
        onlineW = 38;
        int inputX = selfX + 40;
        int inputW = Math.max(68, fw - (inputX - fx) - onlineW - clearW - 20);
        if (ownerFilterInput == null) buildWidgets();
        renderEditText(g, ownerFilterInput, inputX, row2, inputW, 17, "Owner UUID", mouseX, mouseY, partialTick, true);

        onlineX = inputX + inputW + 4;
        onlineY = row2;
        onlineH = 17;
        drawFilterChip(g, onlineX, onlineY, onlineW, onlineH, "在线", ownerDropdownOpen || !selectedOwnerIds.isEmpty(), mouseX, mouseY);

        int clearX = onlineX + onlineW + 4;
        drawFilterChip(g, clearX, row2, clearW, 17, "清除", hasAnyFilter(), mouseX, mouseY);
    }

    private void drawFilterChip(GuiGraphics g, int x, int y, int w, int h, String label, boolean active, int mouseX, int mouseY) {
        boolean hover = Theme.inside(mouseX, mouseY, x, y, w, h);
        int fill = active ? Theme.PRIMARY_SOFT : (hover ? Theme.SURFACE_ALT : Theme.SURFACE_SUNK);
        int border = active ? Theme.PRIMARY : Theme.BORDER;
        int color = active ? Theme.PRIMARY_PRESS : Theme.TEXT_MUTED;
        Theme.panel(g, x, y, w, h, Math.min(5, h / 2), fill, border);
        Theme.textInBox(g, font, label, x, y, w, h, color);
    }

    private void computeCardRects(int rx, int ry, int rw, int rh, boolean owner, boolean current) {
        int rightEdge = rx + rw - 12;
        btnY = ry + rh - BTN - 8;
        pillY = ry + rh - PILL_H - 7;
        hasDelete = owner && !current;
        hasSettings = owner;
        hasRename = owner;
        hasOfflineToggle = owner;
        hasChunkLoadToggle = owner;
        if (hasDelete) { delX = rightEdge - BTN; rightEdge = delX - 6; }
        if (hasSettings) { setX = rightEdge - BTN; rightEdge = setX - 6; }
        if (hasRename) { renameX = rightEdge - BTN; rightEdge = renameX - 6; }
        if (hasOfflineToggle) { offlineX = rightEdge - OFFLINE_W; rightEdge = offlineX - 6; }
        if (hasChunkLoadToggle) { chunkLoadX = rightEdge - CHUNKLOAD_W; rightEdge = chunkLoadX - 6; }
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
        String tag = space.infinite() ? Component.translatable("foldworks.space.infinite_tag").getString()
                : space.width() + "×" + space.depth();
        String biome = space.biome().contains(":") ? space.biome().substring(space.biome().indexOf(':') + 1) : space.biome();
        String meta = tag + "   " + pretty(space.terrain().name()) + "   " + pretty(biome) + "   " + modeLabel(space.mode());
        g.drawString(font, Theme.styled(Theme.ellipsize(font, meta, textRight - (rx + 12))), rx + 12, ry + 23, Theme.TEXT_MUTED, false);
        String permission = "权限 " + levelLabel(selfLevel(space));
        int permissionW = Theme.styledWidth(font, permission);
        int ownerW = Math.max(34, textRight - (rx + 12) - permissionW - 8);
        Theme.text(g, font, Theme.ellipsize(font, "Owner " + space.ownerId(), ownerW), rx + 12, ry + 39, Theme.TEXT_FAINT);
        Theme.textRight(g, font, permission, textRight, ry + 39, Theme.TEXT_FAINT);

        // 进入 / 当前 药丸
        if (current) {
            Theme.fillRound(g, pillX, pillY, CUR_W, PILL_H, Theme.RADIUS, Theme.SUCCESS);
            Theme.textInBox(g, font, Component.translatable("foldworks.space.list.current").getString(), pillX, pillY, CUR_W, PILL_H, Theme.TEXT_ON_PRIM);
        } else {
            boolean ph = Theme.inside(mouseX, mouseY, pillX, pillY, PILL_W, PILL_H);
            Theme.fillRound(g, pillX, pillY, PILL_W, PILL_H, Theme.RADIUS, ph ? Theme.PRIMARY_HOVER : Theme.PRIMARY);
            Theme.textInBox(g, font, Component.translatable("foldworks.space.list.enter").getString(), pillX, pillY, PILL_W, PILL_H, Theme.TEXT_ON_PRIM);
        }
        // 设置 ⚙
        if (hasSettings) {
            boolean sh = Theme.inside(mouseX, mouseY, setX, btnY, BTN, BTN);
            Theme.fillRound(g, setX, btnY, BTN, BTN, Theme.RADIUS, sh ? Theme.PRIMARY_SOFT : Theme.SURFACE_SUNK);
            Theme.textInBox(g, font, "⚙", setX, btnY, BTN, BTN, Theme.TEXT_MUTED);
        }
        if (hasRename) {
            boolean renameHover = Theme.inside(mouseX, mouseY, renameX, btnY, BTN, BTN);
            Theme.fillRound(g, renameX, btnY, BTN, BTN, Theme.RADIUS, renameHover ? Theme.PRIMARY_SOFT : Theme.SURFACE_SUNK);
            Theme.textInBox(g, font, "名", renameX, btnY, BTN, BTN, Theme.TEXT_MUTED);
        }
        if (hasOfflineToggle) {
            boolean allowed = offlineAllowed(space);
            boolean oh = Theme.inside(mouseX, mouseY, offlineX, btnY, OFFLINE_W, BTN) && allowed;
            int offlineFill = !allowed ? Theme.SURFACE_SUNK : space.offlineSimulationEnabled() ? Theme.PRIMARY_SOFT : (oh ? Theme.SURFACE_ALT : Theme.SURFACE_SUNK);
            int offlineBorder = space.offlineSimulationEnabled() ? Theme.PRIMARY : Theme.BORDER;
            int color = !allowed ? Theme.TEXT_FAINT : space.offlineSimulationEnabled() ? Theme.PRIMARY_PRESS : Theme.TEXT_MUTED;
            Theme.panel(g, offlineX, btnY, OFFLINE_W, BTN, Theme.RADIUS, offlineFill, offlineBorder);
            Theme.textInBox(g, font, "离线", offlineX, btnY, OFFLINE_W, BTN, color);
        }
        if (hasChunkLoadToggle) {
            boolean allowed = space.chunkLoadingAllowed();
            boolean ch = Theme.inside(mouseX, mouseY, chunkLoadX, btnY, CHUNKLOAD_W, BTN) && allowed;
            int chunkFill = !allowed ? Theme.SURFACE_SUNK : space.chunkLoadingEnabled() ? Theme.PRIMARY_SOFT : (ch ? Theme.SURFACE_ALT : Theme.SURFACE_SUNK);
            int chunkBorder = space.chunkLoadingEnabled() ? Theme.PRIMARY : Theme.BORDER;
            int chunkColor = !allowed ? Theme.TEXT_FAINT : space.chunkLoadingEnabled() ? Theme.PRIMARY_PRESS : Theme.TEXT_MUTED;
            Theme.panel(g, chunkLoadX, btnY, CHUNKLOAD_W, BTN, Theme.RADIUS, chunkFill, chunkBorder);
            Theme.textInBox(g, font, "常载", chunkLoadX, btnY, CHUNKLOAD_W, BTN, chunkColor);
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
        if (hasRename && Theme.inside(mx, my, renameX, btnY, BTN, BTN)) { openRename(space); return true; }
        if (hasOfflineToggle && Theme.inside(mx, my, offlineX, btnY, OFFLINE_W, BTN)) {
            if (offlineAllowed(space)) {
                PacketDistributor.sendToServer(new UpdateOfflineSimulationPayload(space.spaceId(), !space.offlineSimulationEnabled()));
            }
            return true;
        }
        if (hasChunkLoadToggle && Theme.inside(mx, my, chunkLoadX, btnY, CHUNKLOAD_W, BTN)) {
            if (space.chunkLoadingAllowed()) {
                PacketDistributor.sendToServer(new UpdateSpaceChunkLoadingPayload(space.spaceId(), !space.chunkLoadingEnabled()));
            }
            return true;
        }
        if (!current && Theme.inside(mx, my, pillX, pillY, PILL_W, PILL_H)) { enterSpace(space); return true; }
        if (!current) { enterSpace(space); return true; }
        return false;
    }

    // ------------------------------------------------------------------
    // 模态
    // ------------------------------------------------------------------
    @Override
    public void renderOverlay(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (modal == Modal.NONE) {
            if (ownerDropdownOpen) renderOwnerDropdown(g, mouseX, mouseY);
            return;
        }
        g.fill(x, y, x + w, y + h, 0x990C1626); // 内容区遮罩
        if (modal == Modal.CONFIRM_DELETE) renderConfirm(g, mouseX, mouseY);
        else if (modal == Modal.RENAME) renderRename(g, mouseX, mouseY, partialTick);
        else if (modal == Modal.PERMISSION) renderPermission(g, mouseX, mouseY, partialTick);
    }

    private void renderOwnerDropdown(GuiGraphics g, int mouseX, int mouseY) {
        List<OnlinePlayer> players = onlinePlayers();
        int rows = Math.min(6, Math.max(1, players.size()));
        int rowH = 18;
        int dw = 158;
        int dx = Math.min(x + w - dw - 8, Math.max(x + 8, onlineX + onlineW - dw));
        int dy = onlineY + onlineH + 3;
        int dh = rows * rowH + 10;
        Theme.shadow(g, dx, dy, dw, dh, Theme.RADIUS);
        Theme.panel(g, dx, dy, dw, dh, Theme.RADIUS, Theme.SURFACE, Theme.BORDER_STRONG);

        if (players.isEmpty()) {
            Theme.textCentered(g, font, "无在线玩家", dx + dw / 2, dy + 9, Theme.TEXT_FAINT);
            return;
        }

        ownerDropdownScroll = clamp(ownerDropdownScroll, 0, Math.max(0, players.size() - rows));
        for (int i = 0; i < rows && i + ownerDropdownScroll < players.size(); i++) {
            OnlinePlayer player = players.get(i + ownerDropdownScroll);
            int ry = dy + 5 + i * rowH;
            boolean selected = selectedOwnerIds.contains(player.id());
            boolean hover = Theme.inside(mouseX, mouseY, dx + 5, ry, dw - 10, rowH - 2);
            if (selected || hover) Theme.fillRound(g, dx + 5, ry, dw - 10, rowH - 2, 4, selected ? Theme.PRIMARY_SOFT : Theme.SURFACE_ALT);
            Theme.text(g, font, selected ? "✓" : "+", dx + 10, ry + 5, selected ? Theme.PRIMARY_PRESS : Theme.TEXT_FAINT);
            Theme.text(g, font, Theme.ellipsize(font, player.name(), dw - 44), dx + 24, ry + 5, selected ? Theme.PRIMARY_PRESS : Theme.TEXT);
        }
    }

    private void renderConfirm(GuiGraphics g, int mouseX, int mouseY) {
        int mw = Math.min(w - 24, 260), mh = 116;
        int mx = x + (w - mw) / 2, my = y + (h - mh) / 2;
        Theme.shadow(g, mx, my, mw, mh, Theme.RADIUS);
        Theme.panel(g, mx, my, mw, mh, Theme.RADIUS, Theme.SURFACE, Theme.BORDER);
        Theme.textCentered(g, font, Component.translatable("foldworks.space.delete.confirm_title").getString(), mx + mw / 2, my + 14, Theme.TEXT);
        String line = Component.translatable("foldworks.space.delete.confirm_body", target != null ? target.name() : "").getString();
        Theme.textCentered(g, font, Theme.ellipsize(font, line, mw - 20), mx + mw / 2, my + 38, Theme.TEXT_MUTED);

        int bw = (mw - 16 - Theme.GAP) / 2;
        int by = my + mh - 36;
        drawModalButton(g, mx + 8, by, bw, Component.translatable("foldworks.space.cancel").getString(), false, mouseX, mouseY);
        drawModalButton(g, mx + 8 + bw + Theme.GAP, by, bw, Component.translatable("foldworks.space.delete.confirm_ok").getString(), true, mouseX, mouseY);
    }

    private void renderRename(GuiGraphics g, int mouseX, int mouseY, float pt) {
        if (target == null) { modal = Modal.NONE; return; }
        int mw = Math.min(w - 24, 268), mh = 112;
        int mx = x + (w - mw) / 2, my = y + (h - mh) / 2;
        Theme.shadow(g, mx, my, mw, mh, Theme.RADIUS);
        Theme.panel(g, mx, my, mw, mh, Theme.RADIUS, Theme.SURFACE, Theme.BORDER);
        Theme.text(g, font, "重命名工造", mx + 12, my + 12, Theme.TEXT);

        if (renameInput == null) renameInput = makeRenameInput();
        renderEditText(g, renameInput, mx + 12, my + 38, mw - 24, 22, "工造名称", mouseX, mouseY, pt, true);

        int bw = (mw - 24 - Theme.GAP) / 2;
        int by = my + mh - 34;
        drawModalButton(g, mx + 12, by, bw, "取消", false, mouseX, mouseY);
        drawModalButton(g, mx + 12 + bw + Theme.GAP, by, bw, "保存", false, mouseX, mouseY);
    }

    private void renderPermission(GuiGraphics g, int mouseX, int mouseY, float pt) {
        if (target == null) { modal = Modal.NONE; return; }
        int mw = Math.min(w - 16, 290), mh = Math.min(h - 16, 220);
        int mx = x + (w - mw) / 2, my = y + (h - mh) / 2;
        Theme.shadow(g, mx, my, mw, mh, Theme.RADIUS);
        Theme.panel(g, mx, my, mw, mh, Theme.RADIUS, Theme.SURFACE, Theme.BORDER);

        String title = Component.translatable("foldworks.permission.title").getString() + " · " + target.name();
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
            Theme.textCentered(g, font, Component.translatable("foldworks.permission.no_list_hint").getString(), mx + mw / 2, my + 70, Theme.TEXT_FAINT);
            return;
        }

        // 名字输入 + 添加
        int inputY = chipsY + 30;
        int addW = 48;
        int inputW = mw - 24 - addW - Theme.GAP;
        if (permNameInput == null) permNameInput = makeNameInput();
        renderEditText(g, permNameInput, mx + 12, inputY, inputW, 20,
                Component.translatable("foldworks.permission.name_hint").getString(), mouseX, mouseY, pt, true);
        boolean addHov = Theme.inside(mouseX, mouseY, mx + 12 + inputW + Theme.GAP, inputY, addW, 20);
        Theme.fillRound(g, mx + 12 + inputW + Theme.GAP, inputY, addW, 20, Theme.RADIUS, addHov ? Theme.PRIMARY_HOVER : Theme.PRIMARY);
        Theme.textInBox(g, font, Component.translatable("foldworks.permission.add").getString(), mx + 12 + inputW + Theme.GAP, inputY, addW, 20, Theme.TEXT_ON_PRIM);

        // 成员列表
        int memTop = inputY + 26;
        int memBottom = my + mh - 10;
        Theme.enableScissor(g, mx + 12, memTop, mx + mw - 12, memBottom);
        List<SpaceInfo.Member> members = target.members();
        if (members.isEmpty()) {
            Theme.textCentered(g, font, Component.translatable("foldworks.permission.empty").getString(), mx + mw / 2, memTop + 8, Theme.TEXT_FAINT);
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
        EditBox box = new EditBox(font, 0, 0, 80, 14, Component.translatable("foldworks.permission.name_hint"));
        box.setMaxLength(16);
        box.setBordered(false);
        box.setTextColor(Theme.TEXT);
        return box;
    }

    private EditBox makeRenameInput() {
        EditBox box = new EditBox(font, 0, 0, 80, 14, Component.literal("工造名称"));
        box.setMaxLength(24);
        box.setBordered(false);
        box.setTextColor(Theme.TEXT);
        return box;
    }

    private void renderEditText(GuiGraphics g, EditBox input, int ix, int iy, int iw, int ih, String placeholder,
                                int mouseX, int mouseY, float pt, boolean enabled) {
        Theme.panel(g, ix, iy, iw, ih, Math.min(5, ih / 2), enabled ? Theme.SURFACE_SUNK : Theme.SURFACE_ALT,
                input != null && input.isFocused() ? Theme.PRIMARY : Theme.BORDER);
        if (input == null) return;
        input.setX(ix + 6);
        input.setY(iy + 6);
        input.setWidth(iw - 12);
        input.setEditable(enabled);
        String value = input.getValue();
        if (value.isBlank() && !input.isFocused()) {
            g.drawString(font, placeholder, ix + 6, iy + (ih - 8) / 2, Theme.TEXT_FAINT, false);
            return;
        }
        String visible = visibleTail(value, iw - 16);
        int tx = ix + 6;
        int ty = iy + (ih - 8) / 2;
        Theme.enableScissor(g, ix + 4, iy + 2, ix + iw - 4, iy + ih - 2);
        g.drawString(font, visible, tx, ty, enabled ? Theme.TEXT : Theme.TEXT_FAINT, false);
        if (input.isFocused() && ((System.currentTimeMillis() / 500L) & 1L) == 0L) {
            int cx = Math.min(ix + iw - 7, tx + font.width(visible) + 1);
            g.fill(cx, ty - 1, cx + 1, ty + 10, Theme.TEXT);
        }
        g.disableScissor();
    }

    private String visibleTail(String value, int width) {
        if (value == null || font.width(value) <= width) return value == null ? "" : value;
        String text = value;
        while (text.length() > 1 && font.width(text) > width) text = text.substring(1);
        return text;
    }

    @Override
    public boolean overlayMouseClicked(double mx, double my, int button) {
        if (modal == Modal.NONE && ownerDropdownOpen) {
            return ownerDropdownClick(mx, my, button);
        }
        if (modal == Modal.NONE) return false;
        if (button != 0) return true;
        if (modal == Modal.CONFIRM_DELETE) { confirmClick(mx, my); return true; }
        if (modal == Modal.RENAME) { renameClick(mx, my); return true; }
        if (modal == Modal.PERMISSION) { permissionClick(mx, my); return true; }
        return true;
    }

    private boolean ownerDropdownClick(double mx, double my, int button) {
        if (button != 0) return true;
        List<OnlinePlayer> players = onlinePlayers();
        int rows = Math.min(6, Math.max(1, players.size()));
        int rowH = 18;
        int dw = 158;
        int dx = Math.min(x + w - dw - 8, Math.max(x + 8, onlineX + onlineW - dw));
        int dy = onlineY + onlineH + 3;
        int dh = rows * rowH + 10;
        if (!Theme.inside(mx, my, dx, dy, dw, dh) && !Theme.inside(mx, my, onlineX, onlineY, onlineW, onlineH)) {
            ownerDropdownOpen = false;
            return true;
        }
        if (Theme.inside(mx, my, onlineX, onlineY, onlineW, onlineH)) {
            ownerDropdownOpen = false;
            return true;
        }
        if (players.isEmpty()) return true;
        ownerDropdownScroll = clamp(ownerDropdownScroll, 0, Math.max(0, players.size() - rows));
        for (int i = 0; i < rows && i + ownerDropdownScroll < players.size(); i++) {
            int ry = dy + 5 + i * rowH;
            if (Theme.inside(mx, my, dx + 5, ry, dw - 10, rowH - 2)) {
                UUID id = players.get(i + ownerDropdownScroll).id();
                if (!selectedOwnerIds.remove(id)) selectedOwnerIds.add(id);
                return true;
            }
        }
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

    private void renameClick(double mx, double my) {
        int mw = Math.min(w - 24, 268), mh = 112;
        int mx0 = x + (w - mw) / 2, my0 = y + (h - mh) / 2;
        if (renameInput != null) renameInput.setFocused(false);
        if (Theme.inside(mx, my, mx0 + 12, my0 + 38, mw - 24, 22)) {
            if (renameInput != null) renameInput.setFocused(true);
            return;
        }
        int bw = (mw - 24 - Theme.GAP) / 2;
        int by = my0 + mh - 34;
        if (Theme.inside(mx, my, mx0 + 12, by, bw, 26)) {
            closeRename();
            return;
        }
        if (Theme.inside(mx, my, mx0 + 12 + bw + Theme.GAP, by, bw, 26)) {
            submitRename();
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
                PacketDistributor.sendToServer(new UpdatePermissionPayload(target.spaceId(), MODES[i], target.protectedLevel(), target.publicLevel()));
                // 乐观更新本地 target 模式
                target = new SpaceInfo(target.spaceId(), target.ownerId(), target.name(), target.width(), target.depth(),
                        target.biome(), target.terrain(), target.dimensionId(), target.infinite(), target.amplitude(),
                        MODES[i], target.protectedLevel(), target.publicLevel(),
                        target.offlineSimulationEnabled() && (MODES[i] == SpacePermission.AccessMode.PRIVATE || MODES[i] == SpacePermission.AccessMode.WHITELIST),
                        target.chunkLoadingEnabled(), target.chunkLoadingAllowed(),
                        target.members());
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
        if (modal == Modal.RENAME && renameInput != null) {
            if (keyCode == 257 || keyCode == 335) { submitRename(); return true; }
            if (keyCode == 256) { closeRename(); return true; }
            if (renameInput.keyPressed(keyCode, scanCode, modifiers)) return true;
            return renameInput.isFocused();
        }
        if (modal == Modal.PERMISSION && permNameInput != null) {
            if (keyCode == 257 || keyCode == 335) { submitAdd(); return true; } // Enter
            if (permNameInput.keyPressed(keyCode, scanCode, modifiers)) return true;
            return permNameInput.isFocused();
        }
        if (modal == Modal.NONE && ownerFilterInput != null && ownerFilterInput.isFocused()) {
            if (keyCode == 256) {
                ownerFilterInput.setFocused(false);
                return true;
            }
            if (ownerFilterInput.keyPressed(keyCode, scanCode, modifiers)) return true;
            return true;
        }
        return false;
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (modal == Modal.RENAME && renameInput != null) {
            if (renameInput.charTyped(codePoint, modifiers)) return true;
            return renameInput.isFocused();
        }
        if (modal == Modal.PERMISSION && permNameInput != null) {
            if (permNameInput.charTyped(codePoint, modifiers)) return true;
            return permNameInput.isFocused();
        }
        if (modal == Modal.NONE && ownerFilterInput != null && ownerFilterInput.isFocused()) {
            return ownerFilterInput.charTyped(codePoint, modifiers);
        }
        return false;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (modal != Modal.NONE) return false; // 模态时点击走 overlayMouseClicked
        if (button == 0 && handleFilterClick(mx, my)) return true;
        if (ownerFilterInput != null) ownerFilterInput.setFocused(false);
        ownerDropdownOpen = false;
        if (list != null && !filteredSpaces.isEmpty() && list.mouseClicked(mx, my, button)) return true;
        if (createCta != null && createCta.mouseClicked(mx, my, button)) return true;
        if (inProductionSpace && exitBtn != null && exitBtn.mouseClicked(mx, my, button)) return true;
        return false;
    }

    private boolean handleFilterClick(double mx, double my) {
        int pad = Theme.PAD;
        int fx = x + pad;
        int fy = y + pad + 16;
        int fw = w - pad * 2;
        if (!Theme.inside(mx, my, fx, fy, fw, FILTER_H - 6)) return false;
        if (ownerFilterInput != null) ownerFilterInput.setFocused(false);

        int row1 = fy + 5;
        int cx = fx + 38;
        for (SpacePermission.AccessLevel level : FILTER_LEVELS) {
            int chipW = level == SpacePermission.AccessLevel.MANAGE ? 38 : 34;
            if (Theme.inside(mx, my, cx, row1, chipW, 17)) {
                if (!selectedLevels.remove(level)) selectedLevels.add(level);
                return true;
            }
            cx += chipW + 4;
        }

        int row2 = fy + 25;
        int selfX = fx + 45;
        if (Theme.inside(mx, my, selfX, row2, 36, 17)) {
            ownerSelfFilter = !ownerSelfFilter;
            return true;
        }

        int clearW = 34;
        int inputX = selfX + 40;
        int inputW = Math.max(68, fw - (inputX - fx) - onlineW - clearW - 20);
        if (Theme.inside(mx, my, inputX, row2, inputW, 17)) {
            if (ownerFilterInput != null) ownerFilterInput.setFocused(true);
            ownerDropdownOpen = false;
            return true;
        }

        if (Theme.inside(mx, my, onlineX, onlineY, onlineW, onlineH)) {
            ownerDropdownOpen = !ownerDropdownOpen;
            return true;
        }

        int clearX = onlineX + onlineW + 4;
        if (Theme.inside(mx, my, clearX, row2, clearW, 17)) {
            clearFilters();
            return true;
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (modal != Modal.NONE) return true;
        if (ownerDropdownOpen) {
            List<OnlinePlayer> players = onlinePlayers();
            int rows = Math.min(6, Math.max(1, players.size()));
            int dw = 158;
            int dx = Math.min(x + w - dw - 8, Math.max(x + 8, onlineX + onlineW - dw));
            int dy = onlineY + onlineH + 3;
            int dh = rows * 18 + 10;
            if (Theme.inside(mx, my, dx, dy, dw, dh)) {
                ownerDropdownScroll = clamp(ownerDropdownScroll - (int) Math.signum(sy), 0, Math.max(0, players.size() - rows));
                return true;
            }
        }
        return list != null && list.mouseScrolled(mx, my, sy);
    }

    // ------------------------------------------------------------------
    // 动作
    // ------------------------------------------------------------------
    private void openDeleteConfirm(SpaceInfo space) { target = space; modal = Modal.CONFIRM_DELETE; }
    private void openRename(SpaceInfo space) {
        target = space;
        modal = Modal.RENAME;
        if (renameInput == null) renameInput = makeRenameInput();
        renameInput.setValue(space == null ? "" : space.name());
        renameInput.setFocused(true);
    }
    private void openPermission(SpaceInfo space) {
        if (router != null) {
            router.setActive("permissions");
            return;
        }
        target = space;
        modal = Modal.PERMISSION;
        if (permNameInput != null) permNameInput.setValue("");
    }
    private void closeRename() { modal = Modal.NONE; target = null; if (renameInput != null) renameInput.setFocused(false); }
    private void closePermission() { modal = Modal.NONE; target = null; if (permNameInput != null) permNameInput.setFocused(false); }

    private void submitRename() {
        if (target == null || renameInput == null) { closeRename(); return; }
        String name = renameInput.getValue().trim();
        if (!name.isEmpty() && !name.equals(target.name())) {
            PacketDistributor.sendToServer(new RenameSpacePayload(target.spaceId(), name));
        }
        closeRename();
    }

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

    private void rebuildFilteredSpaces() {
        filteredSpaces.clear();
        for (SpaceInfo space : spaces) {
            if (matchesPermissionFilter(space) && matchesOwnerFilter(space)) filteredSpaces.add(space);
        }
    }

    private boolean matchesPermissionFilter(SpaceInfo space) {
        return selectedLevels.isEmpty() || selectedLevels.contains(selfLevel(space));
    }

    private boolean matchesOwnerFilter(SpaceInfo space) {
        if (!hasOwnerFilter()) return true;
        UUID self = mc.player == null ? null : mc.player.getUUID();
        if (ownerSelfFilter && self != null && space.ownerId().equals(self)) return true;
        if (selectedOwnerIds.contains(space.ownerId())) return true;

        String query = ownerFilterInput == null ? "" : ownerFilterInput.getValue().trim();
        if (query == null || query.isEmpty()) return false;
        return SearchSupport.contains(space.ownerId().toString(), query);
    }

    private boolean hasOwnerFilter() {
        return ownerSelfFilter
                || !selectedOwnerIds.isEmpty()
                || (ownerFilterInput != null && !ownerFilterInput.getValue().trim().isEmpty());
    }

    private boolean hasAnyFilter() {
        return !selectedLevels.isEmpty() || hasOwnerFilter();
    }

    private void clearFilters() {
        selectedLevels.clear();
        selectedOwnerIds.clear();
        ownerSelfFilter = false;
        ownerDropdownOpen = false;
        ownerDropdownScroll = 0;
        if (ownerFilterInput != null) {
            ownerFilterInput.setValue("");
            ownerFilterInput.setFocused(false);
        }
    }

    private SpacePermission.AccessLevel selfLevel(SpaceInfo space) {
        return mc.player == null ? SpacePermission.AccessLevel.NONE : space.effectiveLevel(mc.player.getUUID());
    }

    private List<OnlinePlayer> onlinePlayers() {
        if (mc.getConnection() == null) return List.of();
        Collection<PlayerInfo> infos = mc.getConnection().getOnlinePlayers();
        return infos.stream()
                .map(info -> new OnlinePlayer(info.getProfile().getId(), info.getProfile().getName()))
                .filter(player -> player.id() != null && player.name() != null)
                .sorted(Comparator.comparing(OnlinePlayer::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private record OnlinePlayer(UUID id, String name) {}

    private String levelLabel(SpacePermission.AccessLevel level) {
        return switch (level) {
            case NONE -> "拒绝";
            case VIEW -> "可见";
            case USE -> "使用";
            case WRITE -> "编辑";
            case MANAGE -> "管理";
        };
    }

    private String modeLabel(SpacePermission.AccessMode mode) {
        return switch (mode) {
            case PRIVATE -> Component.translatable("foldworks.permission.private").getString();
            case PUBLIC -> Component.translatable("foldworks.permission.public").getString();
            case WHITELIST -> Component.translatable("foldworks.permission.whitelist").getString();
            case BLACKLIST -> Component.translatable("foldworks.permission.blacklist").getString();
        };
    }

    private boolean offlineAllowed(SpaceInfo space) {
        return space.mode() == SpacePermission.AccessMode.PRIVATE || space.mode() == SpacePermission.AccessMode.WHITELIST;
    }

    private static String pretty(String id) {
        String lower = id.toLowerCase().replace('_', ' ');
        StringBuilder out = new StringBuilder();
        for (String part : lower.split(" ")) {
            if (!part.isEmpty()) out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1)).append(' ');
        }
        return out.toString().trim();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
