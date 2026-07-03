package com.pockethomestead.client.page;

import com.pockethomestead.client.ClientSpaceCache;
import com.pockethomestead.client.ClientTransferGraphCache;
import com.pockethomestead.client.ui.Page;
import com.pockethomestead.client.ui.Theme;
import com.pockethomestead.network.PermissionMemberPayload;
import com.pockethomestead.network.RequestSpaceListPayload;
import com.pockethomestead.network.RequestTransferGraphPacket;
import com.pockethomestead.network.SpaceInfo;
import com.pockethomestead.network.TransferGraphSyncPacket;
import com.pockethomestead.network.TransferTeamPacket;
import com.pockethomestead.network.UpdatePermissionPayload;
import com.pockethomestead.space.SpacePermission;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class PermissionsPage extends Page {
    private enum PlayerPicker { NONE, TEAM, PRIVATE }
    private static final SpacePermission.AccessLevel[] PRIVATE_DEFAULT_LEVELS = {
            SpacePermission.AccessLevel.NONE,
            SpacePermission.AccessLevel.VIEW,
            SpacePermission.AccessLevel.USE,
            SpacePermission.AccessLevel.WRITE
    };
    private static final SpacePermission.AccessLevel[] TEAM_LEVELS = {
            SpacePermission.AccessLevel.VIEW,
            SpacePermission.AccessLevel.USE,
            SpacePermission.AccessLevel.WRITE,
            SpacePermission.AccessLevel.MANAGE
    };

    private final List<SpaceInfo> spaces = new ArrayList<>();
    private String selectedTeamId = "";
    private int teamScroll;
    private int teamMemberScroll;
    private int privateMemberScroll;
    private int syncTicker;
    private String lastTeamInputId = "";
    private EditBox teamNameInput;
    private EditBox teamMemberInput;
    private EditBox privateMemberInput;
    private SpacePermission.AccessLevel teamAddLevel = SpacePermission.AccessLevel.WRITE;
    private SpacePermission.AccessLevel privateAddLevel = SpacePermission.AccessLevel.USE;
    private long lastSeenSpaceVersion = -1;
    private boolean teamHelpOpen;
    private PlayerPicker playerPicker = PlayerPicker.NONE;
    private int playerPickerScroll;
    private int pickerX, pickerY, pickerW, pickerH;

    @Override
    public String id() {
        return "permissions";
    }

    @Override
    public String navTitle() {
        return "权限";
    }

    @Override
    public String navIcon() {
        return "◇";
    }

    @Override
    public void onEnter() {
        ensureInputs();
        spaces.clear();
        spaces.addAll(ClientSpaceCache.get());
        lastSeenSpaceVersion = ClientSpaceCache.version();
        requestFreshData();
        ensureTeamSelection();
    }

    @Override
    public void onExit() {
        if (teamNameInput != null) teamNameInput.setFocused(false);
        if (teamMemberInput != null) teamMemberInput.setFocused(false);
        if (privateMemberInput != null) privateMemberInput.setFocused(false);
    }

    @Override
    public void tick() {
        long v = ClientSpaceCache.version();
        if (v != lastSeenSpaceVersion) {
            lastSeenSpaceVersion = v;
            spaces.clear();
            spaces.addAll(ClientSpaceCache.get());
        }
        ensureTeamSelection();
        if (++syncTicker >= 40) {
            syncTicker = 0;
            requestFreshData();
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        ensureInputs();
        Theme.panel(g, x, y, w, h, Theme.RADIUS + 1, Theme.SURFACE, Theme.BORDER);

        int pad = 8;
        int listW = clamp(Math.round(w * 0.30f), 138, 206);
        int listX = x + pad;
        int listY = y + pad;
        int bodyH = h - pad * 2;
        renderTeamList(g, mouseX, mouseY, listX, listY, listW, bodyH);

        int detailX = listX + listW + 8;
        int detailW = x + w - pad - detailX;
        int privateH = clamp(Math.round(bodyH * 0.36f), 128, 170);
        int teamH = Math.max(154, bodyH - privateH - 8);
        renderTeamDetail(g, mouseX, mouseY, partialTick, selectedTeam(), detailX, listY, detailW, teamH);
        renderPrivateRules(g, mouseX, mouseY, partialTick, detailX, listY + teamH + 8, detailW, bodyH - teamH - 8);
        renderPlayerPicker(g, mouseX, mouseY);
    }

    private void renderTeamList(GuiGraphics g, int mx, int my, int sx, int sy, int sw, int sh) {
        Theme.panel(g, sx, sy, sw, sh, Theme.RADIUS, Theme.SURFACE_ALT, Theme.BORDER);
        Theme.text(g, font, "团队", sx + 8, sy + 8, Theme.TEXT);
        int createW = 44;
        button(g, sx + sw - createW - 8, sy + 5, createW, 18, "新建", true, mx, my);

        List<TransferGraphSyncPacket.TeamData> teams = ClientTransferGraphCache.teams();
        int rowTop = sy + 30;
        int rowH = 32;
        int maxRows = Math.max(1, (sy + sh - 8 - rowTop) / rowH);
        teamScroll = clamp(teamScroll, 0, Math.max(0, teams.size() - maxRows));
        if (teams.isEmpty()) {
            Theme.textCentered(g, font, "暂无团队", sx + sw / 2, rowTop + 22, Theme.TEXT_FAINT);
            return;
        }
        for (int i = 0; i < maxRows && i + teamScroll < teams.size(); i++) {
            TransferGraphSyncPacket.TeamData team = teams.get(i + teamScroll);
            int ry = rowTop + i * rowH;
            boolean selected = team.id().equals(selectedTeamId);
            boolean hover = Theme.inside(mx, my, sx + 5, ry, sw - 10, rowH - 4);
            int fill = selected ? Theme.PRIMARY_SOFT : hover ? 0xFFF8FCFF : Theme.SURFACE_ALT;
            int border = selected ? Theme.PRIMARY : Theme.DIVIDER;
            Theme.panel(g, sx + 5, ry, sw - 10, rowH - 4, 5, fill, border);
            Theme.text(g, font, Theme.ellipsize(font, team.name(), sw - 24), sx + 10, ry + 5, selected ? Theme.PRIMARY_PRESS : Theme.TEXT);
            Theme.text(g, font, teamSubLabel(team), sx + 10, ry + 18, Theme.TEXT_FAINT);
        }
    }

    private void renderTeamDetail(GuiGraphics g, int mx, int my, float pt, TransferGraphSyncPacket.TeamData team,
                                  int dx, int dy, int dw, int dh) {
        Theme.panel(g, dx, dy, dw, dh, Theme.RADIUS, 0xFFF8FCFF, Theme.DIVIDER);
        if (team == null) {
            Theme.text(g, font, "团队管理", dx + 10, dy + 9, Theme.TEXT);
            renderTeamHelpButton(g, mx, my, dx + 72, dy + 7);
            if (teamHelpOpen) renderTeamHelp(g, dx + 10, dy + 28, Math.min(dw - 20, 230));
            Theme.textCentered(g, font, "选择左侧团队，或新建一个团队", dx + dw / 2, dy + dh / 2 - 4, Theme.TEXT_FAINT);
            return;
        }
        syncTeamNameInput(team);
        boolean canManage = levelFromName(team.selfLevel()).allows(SpacePermission.AccessLevel.MANAGE);
        boolean owner = isSelf(team.ownerId());
        Theme.text(g, font, Theme.ellipsize(font, team.name(), Math.max(40, dw - 96)), dx + 10, dy + 9, Theme.TEXT);
        renderTeamHelpButton(g, mx, my, dx + 72, dy + 7);
        Theme.textRight(g, font, owner ? "所有者" : levelLabel(levelFromName(team.selfLevel())), dx + dw - 10, dy + 9,
                owner ? Theme.SUCCESS : Theme.TEXT_MUTED);

        int curY = dy + 28;
        if (teamHelpOpen) {
            renderTeamHelp(g, dx + 10, curY, Math.min(dw - 20, 260));
            curY += 48;
        }
        if (canManage) {
            renderInput(g, teamNameInput, dx + 10, curY, Math.max(80, dw - 136), 20, "团队名称", mx, my, pt, true);
            button(g, dx + dw - 116, curY, 52, 20, "重命名", true, mx, my);
            button(g, dx + dw - 58, curY, 48, 20, "解散", owner, mx, my);
            curY += 30;
        }

        if (canManage) {
            int addW = 44;
            int levelW = 52;
            int inputW = Math.max(76, dw - 20 - addW - levelW - 8);
            renderPlayerInput(g, teamMemberInput, dx + 10, curY, inputW, 20, "玩家名或 UUID", PlayerPicker.TEAM, mx, my, pt, true);
            chip(g, dx + 14 + inputW, curY, levelW, 20, levelLabel(teamAddLevel), true, mx, my);
            button(g, dx + 18 + inputW + levelW, curY, addW, 20, "添加", true, mx, my);
            curY += 28;
        }

        Theme.hLine(g, dx + 10, curY - 4, dw - 20, Theme.DIVIDER);
        renderTeamMembers(g, mx, my, team, dx + 10, curY, dw - 20, dy + dh - curY - 8, canManage);
    }

    private void renderTeamHelpButton(GuiGraphics g, int mx, int my, int bx, int by) {
        boolean hover = Theme.inside(mx, my, bx, by, 14, 14);
        Theme.panel(g, bx, by, 14, 14, 7, teamHelpOpen ? Theme.PRIMARY_SOFT : Theme.SURFACE_SUNK,
                hover || teamHelpOpen ? Theme.PRIMARY : Theme.BORDER);
        Theme.textInBox(g, font, "?", bx, by, 14, 14, teamHelpOpen ? Theme.PRIMARY_PRESS : Theme.TEXT_MUTED);
    }

    private void renderTeamHelp(GuiGraphics g, int hx, int hy, int hw) {
        Theme.panel(g, hx, hy, hw, 40, 5, Theme.SURFACE_SUNK, Theme.BORDER);
        Theme.text(g, font, "团队是全局设置，可用于共享传输图。", hx + 7, hy + 7, Theme.TEXT_MUTED);
        Theme.text(g, font, "成员权限不会随单个家园切换而改变。", hx + 7, hy + 22, Theme.TEXT_MUTED);
    }

    private void renderTeamMembers(GuiGraphics g, int mx, int my, TransferGraphSyncPacket.TeamData team,
                                   int lx, int ly, int lw, int lh, boolean canManage) {
        Theme.text(g, font, "成员", lx, ly, Theme.TEXT_MUTED);
        int rowTop = ly + 15;
        int rowH = 22;
        List<TransferGraphSyncPacket.TeamMemberData> members = team.members();
        int maxRows = Math.max(1, (lh - 15) / rowH);
        teamMemberScroll = clamp(teamMemberScroll, 0, Math.max(0, members.size() - maxRows));
        if (members.isEmpty()) {
            Theme.textCentered(g, font, "暂无成员", lx + lw / 2, rowTop + 12, Theme.TEXT_FAINT);
            return;
        }
        for (int i = 0; i < maxRows && i + teamMemberScroll < members.size(); i++) {
            TransferGraphSyncPacket.TeamMemberData member = members.get(i + teamMemberScroll);
            int ry = rowTop + i * rowH;
            boolean ownerRow = member.id().equals(team.ownerId());
            Theme.panel(g, lx, ry, lw, rowH - 3, 4, Theme.SURFACE_ALT, Theme.DIVIDER);
            int deleteW = canManage && !ownerRow ? 17 : 0;
            int levelW = 52;
            int nameW = Math.max(48, lw - levelW - deleteW - 18);
            String displayName = member.name() == null || member.name().isBlank() ? shortId(member.id()) : member.name();
            Theme.text(g, font, Theme.ellipsize(font, displayName + (ownerRow ? "  所有者" : ""), nameW), lx + 6, ry + 5, Theme.TEXT);
            chip(g, lx + nameW + 6, ry + 2, levelW, 15, levelLabel(levelFromName(member.level())), canManage && !ownerRow, mx, my);
            if (deleteW > 0) {
                Theme.textInBox(g, font, "×", lx + lw - 18, ry + 1, 17, 17,
                        Theme.inside(mx, my, lx + lw - 18, ry + 1, 17, 17) ? Theme.DANGER : Theme.TEXT_FAINT);
            }
        }
    }

    private void renderPrivateRules(GuiGraphics g, int mx, int my, float pt, int dx, int dy, int dw, int dh) {
        Theme.panel(g, dx, dy, dw, dh, Theme.RADIUS, 0xFFF8FCFF, Theme.DIVIDER);
        List<SpaceInfo> owned = ownedSpaces();
        Theme.text(g, font, "我的私有权限", dx + 10, dy + 8, Theme.TEXT);
        Theme.textRight(g, font, owned.size() + " 个家园", dx + dw - 10, dy + 8, owned.isEmpty() ? Theme.TEXT_FAINT : Theme.TEXT_MUTED);
        if (owned.isEmpty()) {
            Theme.textCentered(g, font, "创建家园后可配置默认访问规则", dx + dw / 2, dy + dh / 2 - 4, Theme.TEXT_FAINT);
            return;
        }

        boolean mixed = privateRulesMixed(owned);
        int rowY = dy + 28;
        Theme.text(g, font, "默认权限", dx + 10, rowY + 5, Theme.TEXT_MUTED);
        int chipX = dx + 70;
        int chipW = Math.max(38, Math.min(56, (dw - 86) / PRIVATE_DEFAULT_LEVELS.length));
        SpacePermission.AccessLevel active = privateDefaultLevel(owned.get(0));
        for (int i = 0; i < PRIVATE_DEFAULT_LEVELS.length; i++) {
            SpacePermission.AccessLevel level = PRIVATE_DEFAULT_LEVELS[i];
            drawLevelChip(g, chipX + i * (chipW + 4), rowY, chipW, 20, level, active == level, mx, my);
        }
        if (mixed) Theme.text(g, font, "规则不一致，修改后会同步全部", dx + 10, rowY + 23, Theme.TEXT_FAINT);

        int addY = rowY + (mixed ? 40 : 28);
        int addW = 44;
        int levelW = 52;
        int inputW = Math.max(76, dw - 20 - addW - levelW - 8);
        renderPlayerInput(g, privateMemberInput, dx + 10, addY, inputW, 20, "玩家名或 UUID", PlayerPicker.PRIVATE, mx, my, pt, true);
        chip(g, dx + 14 + inputW, addY, levelW, 20, levelLabel(privateAddLevel), true, mx, my);
        button(g, dx + 18 + inputW + levelW, addY, addW, 20, "添加", true, mx, my);

        int listY = addY + 28;
        Theme.hLine(g, dx + 10, listY - 4, dw - 20, Theme.DIVIDER);
        renderPrivateMembers(g, mx, my, owned.get(0), dx + 10, listY, dw - 20, dy + dh - listY - 8);
    }

    private void renderPrivateMembers(GuiGraphics g, int mx, int my, SpaceInfo space, int lx, int ly, int lw, int lh) {
        Theme.text(g, font, "显式成员", lx, ly, Theme.TEXT_MUTED);
        int rowTop = ly + 15;
        int rowH = 22;
        List<SpaceInfo.Member> members = space.members();
        int maxRows = Math.max(1, (lh - 15) / rowH);
        privateMemberScroll = clamp(privateMemberScroll, 0, Math.max(0, members.size() - maxRows));
        if (members.isEmpty()) {
            Theme.textCentered(g, font, "暂无成员规则", lx + lw / 2, rowTop + 12, Theme.TEXT_FAINT);
            return;
        }
        for (int i = 0; i < maxRows && i + privateMemberScroll < members.size(); i++) {
            SpaceInfo.Member member = members.get(i + privateMemberScroll);
            int ry = rowTop + i * rowH;
            Theme.panel(g, lx, ry, lw, rowH - 3, 4, Theme.SURFACE_ALT, Theme.DIVIDER);
            int levelW = 52;
            int nameW = Math.max(48, lw - levelW - 35);
            Theme.text(g, font, Theme.ellipsize(font, member.name(), nameW), lx + 6, ry + 5, Theme.TEXT);
            chip(g, lx + nameW + 8, ry + 2, levelW, 15, levelLabel(effectiveMemberLevel(space, member)), true, mx, my);
            Theme.textInBox(g, font, "×", lx + lw - 18, ry + 1, 17, 17,
                    Theme.inside(mx, my, lx + lw - 18, ry + 1, 17, 17) ? Theme.DANGER : Theme.TEXT_FAINT);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0 || !Theme.inside(mx, my, x, y, w, h)) return false;
        ensureInputs();
        if (handlePlayerPickerClick(mx, my)) return true;
        blurInputs();
        int pad = 8;
        int listW = clamp(Math.round(w * 0.30f), 138, 206);
        int listX = x + pad;
        int listY = y + pad;
        int bodyH = h - pad * 2;
        if (handleTeamListClick(mx, my, listX, listY, listW, bodyH)) return true;

        int detailX = listX + listW + 8;
        int detailW = x + w - pad - detailX;
        int privateH = clamp(Math.round(bodyH * 0.36f), 128, 170);
        int teamH = Math.max(154, bodyH - privateH - 8);
        TransferGraphSyncPacket.TeamData team = selectedTeam();
        if (Theme.inside(mx, my, detailX, listY, detailW, teamH)) {
            if (handleTeamHelpClick(mx, my, detailX, listY)) return true;
            if (team != null && handleTeamDetailClick(mx, my, team, detailX, listY, detailW, teamH)) return true;
            return true;
        }
        if (Theme.inside(mx, my, detailX, listY + teamH + 8, detailW, bodyH - teamH - 8)) {
            handlePrivateClick(mx, my, detailX, listY + teamH + 8, detailW, bodyH - teamH - 8);
            return true;
        }
        return true;
    }

    private boolean handleTeamListClick(double mx, double my, int sx, int sy, int sw, int sh) {
        if (!Theme.inside(mx, my, sx, sy, sw, sh)) return false;
        int createW = 44;
        if (Theme.inside(mx, my, sx + sw - createW - 8, sy + 5, createW, 18)) {
            PacketDistributor.sendToServer(new TransferTeamPacket("CREATE", "", "团队"));
            return true;
        }
        List<TransferGraphSyncPacket.TeamData> teams = ClientTransferGraphCache.teams();
        int rowTop = sy + 30;
        int rowH = 32;
        int maxRows = Math.max(1, (sy + sh - 8 - rowTop) / rowH);
        teamScroll = clamp(teamScroll, 0, Math.max(0, teams.size() - maxRows));
        for (int i = 0; i < maxRows && i + teamScroll < teams.size(); i++) {
            int ry = rowTop + i * rowH;
            if (Theme.inside(mx, my, sx + 5, ry, sw - 10, rowH - 4)) {
                selectedTeamId = teams.get(i + teamScroll).id();
                lastTeamInputId = "";
                teamMemberScroll = 0;
                return true;
            }
        }
        return true;
    }

    private boolean handleTeamDetailClick(double mx, double my, TransferGraphSyncPacket.TeamData team, int dx, int dy, int dw, int dh) {
        boolean canManage = levelFromName(team.selfLevel()).allows(SpacePermission.AccessLevel.MANAGE);
        boolean owner = isSelf(team.ownerId());
        int curY = dy + 28;
        if (teamHelpOpen) curY += 48;
        if (canManage) {
            int inputW = Math.max(80, dw - 136);
            if (Theme.inside(mx, my, dx + 10, curY, inputW, 20)) {
                teamNameInput.setFocused(true);
                return true;
            }
            if (Theme.inside(mx, my, dx + dw - 116, curY, 52, 20)) {
                renameTeam(team);
                return true;
            }
            if (owner && Theme.inside(mx, my, dx + dw - 58, curY, 48, 20)) {
                PacketDistributor.sendToServer(new TransferTeamPacket("DELETE", team.id(), ""));
                selectedTeamId = "";
                return true;
            }
            curY += 30;
            int addW = 44;
            int levelW = 52;
            int addInputW = Math.max(76, dw - 20 - addW - levelW - 8);
            if (Theme.inside(mx, my, dx + 10 + addInputW - 18, curY + 1, 17, 18)) {
                togglePlayerPicker(PlayerPicker.TEAM);
                return true;
            }
            if (Theme.inside(mx, my, dx + 10, curY, addInputW - 18, 20)) {
                teamMemberInput.setFocused(true);
                playerPicker = PlayerPicker.NONE;
                return true;
            }
            if (Theme.inside(mx, my, dx + 14 + addInputW, curY, levelW, 20)) {
                teamAddLevel = nextTeamLevel(teamAddLevel);
                return true;
            }
            if (Theme.inside(mx, my, dx + 18 + addInputW + levelW, curY, addW, 20)) {
                addTeamMember(team);
                return true;
            }
            curY += 28;
        }
        return handleTeamMemberClick(mx, my, team, dx + 10, curY, dw - 20, dy + dh - curY - 8, canManage);
    }

    private boolean handleTeamHelpClick(double mx, double my, int dx, int dy) {
        if (!Theme.inside(mx, my, dx + 72, dy + 7, 14, 14)) return false;
        teamHelpOpen = !teamHelpOpen;
        return true;
    }

    private boolean handlePlayerPickerClick(double mx, double my) {
        if (playerPicker == PlayerPicker.NONE) return false;
        List<OnlinePlayer> players = onlinePlayers();
        int rowH = 18;
        int rows = Math.min(6, Math.max(1, players.size()));
        int dh = rows * rowH + 8;
        if (!Theme.inside(mx, my, pickerX, pickerY, pickerW, dh)) return false;
        if (players.isEmpty()) return true;
        playerPickerScroll = clamp(playerPickerScroll, 0, Math.max(0, players.size() - rows));
        for (int i = 0; i < rows && i + playerPickerScroll < players.size(); i++) {
            int ry = pickerY + 4 + i * rowH;
            if (!Theme.inside(mx, my, pickerX + 4, ry, pickerW - 8, rowH - 2)) continue;
            OnlinePlayer player = players.get(i + playerPickerScroll);
            if (playerPicker == PlayerPicker.TEAM && teamMemberInput != null) {
                teamMemberInput.setValue(player.id().toString());
                teamMemberInput.setFocused(true);
            } else if (playerPicker == PlayerPicker.PRIVATE && privateMemberInput != null) {
                privateMemberInput.setValue(player.id().toString());
                privateMemberInput.setFocused(true);
            }
            playerPicker = PlayerPicker.NONE;
            return true;
        }
        return true;
    }

    private void togglePlayerPicker(PlayerPicker picker) {
        playerPicker = playerPicker == picker ? PlayerPicker.NONE : picker;
        playerPickerScroll = 0;
        if (picker == PlayerPicker.TEAM && teamMemberInput != null) teamMemberInput.setFocused(true);
        if (picker == PlayerPicker.PRIVATE && privateMemberInput != null) privateMemberInput.setFocused(true);
    }

    private boolean handleTeamMemberClick(double mx, double my, TransferGraphSyncPacket.TeamData team,
                                          int lx, int ly, int lw, int lh, boolean canManage) {
        if (!canManage) return false;
        int rowTop = ly + 15;
        int rowH = 22;
        List<TransferGraphSyncPacket.TeamMemberData> members = team.members();
        int maxRows = Math.max(1, (lh - 15) / rowH);
        teamMemberScroll = clamp(teamMemberScroll, 0, Math.max(0, members.size() - maxRows));
        for (int i = 0; i < maxRows && i + teamMemberScroll < members.size(); i++) {
            TransferGraphSyncPacket.TeamMemberData member = members.get(i + teamMemberScroll);
            if (member.id().equals(team.ownerId())) continue;
            int ry = rowTop + i * rowH;
            int levelW = 52;
            int nameW = Math.max(48, lw - levelW - 17 - 18);
            if (Theme.inside(mx, my, lx + nameW + 6, ry + 2, levelW, 15)) {
                setTeamMember(team, member.id(), nextTeamLevel(levelFromName(member.level())));
                return true;
            }
            if (Theme.inside(mx, my, lx + lw - 18, ry + 1, 17, 17)) {
                setTeamMember(team, member.id(), SpacePermission.AccessLevel.NONE);
                return true;
            }
        }
        return false;
    }

    private void handlePrivateClick(double mx, double my, int dx, int dy, int dw, int dh) {
        List<SpaceInfo> owned = ownedSpaces();
        if (owned.isEmpty()) return;
        int rowY = dy + 28;
        int chipX = dx + 70;
        int chipW = Math.max(38, Math.min(56, (dw - 86) / PRIVATE_DEFAULT_LEVELS.length));
        for (int i = 0; i < PRIVATE_DEFAULT_LEVELS.length; i++) {
            if (Theme.inside(mx, my, chipX + i * (chipW + 4), rowY, chipW, 20)) {
                setPrivateDefault(owned, PRIVATE_DEFAULT_LEVELS[i]);
                return;
            }
        }
        int addY = rowY + (privateRulesMixed(owned) ? 40 : 28);
        int addW = 44;
        int levelW = 52;
        int inputW = Math.max(76, dw - 20 - addW - levelW - 8);
        if (Theme.inside(mx, my, dx + 10 + inputW - 18, addY + 1, 17, 18)) {
            togglePlayerPicker(PlayerPicker.PRIVATE);
            return;
        }
        if (Theme.inside(mx, my, dx + 10, addY, inputW - 18, 20)) {
            privateMemberInput.setFocused(true);
            playerPicker = PlayerPicker.NONE;
            return;
        }
        if (Theme.inside(mx, my, dx + 14 + inputW, addY, levelW, 20)) {
            privateAddLevel = nextPrivateLevel(privateAddLevel);
            return;
        }
        if (Theme.inside(mx, my, dx + 18 + inputW + levelW, addY, addW, 20)) {
            addPrivateMember(owned);
            return;
        }
        handlePrivateMemberClick(mx, my, owned, dx + 10, addY + 28, dw - 20, dy + dh - (addY + 28) - 8);
    }

    private boolean handlePrivateMemberClick(double mx, double my, List<SpaceInfo> owned, int lx, int ly, int lw, int lh) {
        SpaceInfo space = owned.get(0);
        List<SpaceInfo.Member> members = space.members();
        int rowTop = ly + 15;
        int rowH = 22;
        int maxRows = Math.max(1, (lh - 15) / rowH);
        privateMemberScroll = clamp(privateMemberScroll, 0, Math.max(0, members.size() - maxRows));
        for (int i = 0; i < maxRows && i + privateMemberScroll < members.size(); i++) {
            SpaceInfo.Member member = members.get(i + privateMemberScroll);
            int ry = rowTop + i * rowH;
            int levelW = 52;
            int nameW = Math.max(48, lw - levelW - 35);
            if (Theme.inside(mx, my, lx + nameW + 8, ry + 2, levelW, 15)) {
                setPrivateMember(owned, member.id(), nextPrivateLevel(effectiveMemberLevel(space, member)));
                return true;
            }
            if (Theme.inside(mx, my, lx + lw - 18, ry + 1, 17, 17)) {
                PacketDistributor.sendToServer(PermissionMemberPayload.remove(owned.get(0).spaceId(), member.id()));
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (!Theme.inside(mx, my, x, y, w, h)) return false;
        int delta = (int) Math.signum(sy);
        if (playerPicker != PlayerPicker.NONE && Theme.inside(mx, my, pickerX, pickerY, pickerW, pickerH)) {
            List<OnlinePlayer> players = onlinePlayers();
            int rows = Math.min(6, Math.max(1, players.size()));
            playerPickerScroll = clamp(playerPickerScroll - delta, 0, Math.max(0, players.size() - rows));
            return true;
        }
        int pad = 8;
        int listW = clamp(Math.round(w * 0.30f), 138, 206);
        int listX = x + pad;
        int listY = y + pad;
        int bodyH = h - pad * 2;
        if (Theme.inside(mx, my, listX, listY, listW, bodyH)) {
            teamScroll = clamp(teamScroll - delta, 0, Math.max(0, ClientTransferGraphCache.teams().size() - 1));
            return true;
        }
        int detailX = listX + listW + 8;
        int detailW = x + w - pad - detailX;
        int privateH = clamp(Math.round(bodyH * 0.36f), 128, 170);
        int teamH = Math.max(154, bodyH - privateH - 8);
        if (Theme.inside(mx, my, detailX, listY, detailW, teamH)) {
            TransferGraphSyncPacket.TeamData team = selectedTeam();
            int count = team == null ? 0 : team.members().size();
            teamMemberScroll = clamp(teamMemberScroll - delta, 0, Math.max(0, count - 1));
        } else {
            SpaceInfo owned = ownedSpaces().isEmpty() ? null : ownedSpaces().get(0);
            int count = owned == null ? 0 : owned.members().size();
            privateMemberScroll = clamp(privateMemberScroll - delta, 0, Math.max(0, count - 1));
        }
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (teamNameInput != null && teamNameInput.isFocused()) {
            if (keyCode == 257 || keyCode == 335) {
                TransferGraphSyncPacket.TeamData team = selectedTeam();
                if (team != null) renameTeam(team);
                return true;
            }
            return teamNameInput.keyPressed(keyCode, scanCode, modifiers) || true;
        }
        if (teamMemberInput != null && teamMemberInput.isFocused()) {
            if (keyCode == 257 || keyCode == 335) {
                TransferGraphSyncPacket.TeamData team = selectedTeam();
                if (team != null) addTeamMember(team);
                return true;
            }
            return teamMemberInput.keyPressed(keyCode, scanCode, modifiers) || true;
        }
        if (privateMemberInput != null && privateMemberInput.isFocused()) {
            if (keyCode == 257 || keyCode == 335) {
                addPrivateMember(ownedSpaces());
                return true;
            }
            return privateMemberInput.keyPressed(keyCode, scanCode, modifiers) || true;
        }
        return false;
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (teamNameInput != null && teamNameInput.isFocused()) return teamNameInput.charTyped(codePoint, modifiers);
        if (teamMemberInput != null && teamMemberInput.isFocused()) return teamMemberInput.charTyped(codePoint, modifiers);
        return privateMemberInput != null && privateMemberInput.isFocused() && privateMemberInput.charTyped(codePoint, modifiers);
    }

    private void ensureInputs() {
        if (teamNameInput == null) {
            teamNameInput = new EditBox(font, 0, 0, 80, 14, Component.literal("团队名称"));
            teamNameInput.setMaxLength(24);
            teamNameInput.setBordered(false);
            teamNameInput.setTextColor(Theme.TEXT);
        }
        if (teamMemberInput == null) {
            teamMemberInput = new EditBox(font, 0, 0, 80, 14, Component.literal("玩家名或 UUID"));
            teamMemberInput.setMaxLength(36);
            teamMemberInput.setBordered(false);
            teamMemberInput.setTextColor(Theme.TEXT);
        }
        if (privateMemberInput == null) {
            privateMemberInput = new EditBox(font, 0, 0, 80, 14, Component.literal("玩家名或 UUID"));
            privateMemberInput.setMaxLength(36);
            privateMemberInput.setBordered(false);
            privateMemberInput.setTextColor(Theme.TEXT);
        }
    }

    private void requestFreshData() {
        PacketDistributor.sendToServer(new RequestSpaceListPayload());
        PacketDistributor.sendToServer(new RequestTransferGraphPacket());
    }

    private void blurInputs() {
        if (teamNameInput != null) teamNameInput.setFocused(false);
        if (teamMemberInput != null) teamMemberInput.setFocused(false);
        if (privateMemberInput != null) privateMemberInput.setFocused(false);
        playerPicker = PlayerPicker.NONE;
    }

    private void renderInput(GuiGraphics g, EditBox input, int ix, int iy, int iw, int ih, String placeholder,
                             int mx, int my, float pt, boolean enabled) {
        renderInput(g, input, ix, iy, iw, ih, placeholder, mx, my, pt, enabled, 6);
    }

    private void renderInput(GuiGraphics g, EditBox input, int ix, int iy, int iw, int ih, String placeholder,
                             int mx, int my, float pt, boolean enabled, int rightPadding) {
        Theme.panel(g, ix, iy, iw, ih, 5, enabled ? Theme.SURFACE_SUNK : Theme.SURFACE_ALT,
                input != null && input.isFocused() ? Theme.PRIMARY : Theme.BORDER);
        input.setX(ix + 6);
        input.setY(iy + 6);
        input.setWidth(iw - 6 - rightPadding);
        input.setEditable(enabled);
        if (input.getValue().isBlank() && !input.isFocused()) {
            g.drawString(font, placeholder, ix + 6, iy + (ih - 8) / 2, Theme.TEXT_FAINT, false);
            return;
        }
        String visible = visibleTail(input.getValue(), iw - 10 - rightPadding);
        int tx = ix + 6;
        int ty = iy + (ih - 8) / 2;
        g.enableScissor(ix + 4, iy + 2, ix + iw - rightPadding, iy + ih - 2);
        g.drawString(font, visible, tx, ty, enabled ? Theme.TEXT : Theme.TEXT_FAINT, false);
        if (input.isFocused() && ((System.currentTimeMillis() / 500L) & 1L) == 0L) {
            int cx = Math.min(ix + iw - rightPadding - 1, tx + font.width(visible) + 1);
            g.fill(cx, ty - 1, cx + 1, ty + 10, Theme.TEXT);
        }
        g.disableScissor();
    }

    private void renderPlayerInput(GuiGraphics g, EditBox input, int ix, int iy, int iw, int ih, String placeholder,
                                   PlayerPicker picker, int mx, int my, float pt, boolean enabled) {
        renderInput(g, input, ix, iy, iw, ih, placeholder, mx, my, pt, enabled, 24);
        int arrowX = ix + iw - 18;
        boolean open = playerPicker == picker;
        boolean hover = enabled && Theme.inside(mx, my, arrowX, iy + 1, 17, ih - 2);
        Theme.textInBox(g, font, open ? "▲" : "▼", arrowX, iy + 1, 17, ih - 2,
                hover || open ? Theme.PRIMARY_PRESS : Theme.TEXT_MUTED);
        if (open) {
            pickerX = ix;
            pickerY = iy + ih + 3;
            pickerW = iw;
            pickerH = Math.min(112, Math.max(24, onlinePlayers().size() * 18 + 8));
        }
    }

    private void renderPlayerPicker(GuiGraphics g, int mx, int my) {
        if (playerPicker == PlayerPicker.NONE) return;
        List<OnlinePlayer> players = onlinePlayers();
        int rowH = 18;
        int rows = Math.min(6, Math.max(1, players.size()));
        pickerH = rows * rowH + 8;
        Theme.panel(g, pickerX, pickerY, pickerW, pickerH, 5, Theme.SURFACE, Theme.BORDER_STRONG);
        if (players.isEmpty()) {
            Theme.textCentered(g, font, "无在线玩家", pickerX + pickerW / 2, pickerY + 8, Theme.TEXT_FAINT);
            return;
        }
        playerPickerScroll = clamp(playerPickerScroll, 0, Math.max(0, players.size() - rows));
        for (int i = 0; i < rows && i + playerPickerScroll < players.size(); i++) {
            OnlinePlayer player = players.get(i + playerPickerScroll);
            int ry = pickerY + 4 + i * rowH;
            boolean hover = Theme.inside(mx, my, pickerX + 4, ry, pickerW - 8, rowH - 2);
            if (hover) Theme.fillRound(g, pickerX + 4, ry, pickerW - 8, rowH - 2, 4, Theme.PRIMARY_SOFT);
            Theme.text(g, font, Theme.ellipsize(font, player.name(), pickerW - 18), pickerX + 8, ry + 5,
                    hover ? Theme.PRIMARY_PRESS : Theme.TEXT);
        }
    }

    private String visibleTail(String value, int width) {
        if (value == null || font.width(value) <= width) return value == null ? "" : value;
        String text = value;
        while (text.length() > 1 && font.width(text) > width) text = text.substring(1);
        return text;
    }

    private void button(GuiGraphics g, int bx, int by, int bw, int bh, String label, boolean enabled, int mx, int my) {
        boolean hover = enabled && Theme.inside(mx, my, bx, by, bw, bh);
        int fill = enabled ? hover ? Theme.PRIMARY_HOVER : Theme.PRIMARY : Theme.SURFACE_SUNK;
        int color = enabled ? Theme.TEXT_ON_PRIM : Theme.TEXT_FAINT;
        Theme.fillRound(g, bx, by, bw, bh, Math.min(5, bh / 2), fill);
        Theme.textInBox(g, font, Theme.ellipsize(font, label, bw - 6), bx, by, bw, bh, color);
    }

    private void chip(GuiGraphics g, int cx, int cy, int cw, int ch, String label, boolean enabled, int mx, int my) {
        boolean hover = enabled && Theme.inside(mx, my, cx, cy, cw, ch);
        int fill = hover ? Theme.PRIMARY_SOFT : Theme.SURFACE_SUNK;
        int border = hover ? Theme.PRIMARY : Theme.BORDER;
        Theme.panel(g, cx, cy, cw, ch, Math.min(5, ch / 2), fill, border);
        Theme.textInBox(g, font, Theme.ellipsize(font, label, Math.max(8, cw - 6)), cx, cy, cw, ch,
                enabled ? Theme.PRIMARY_PRESS : Theme.TEXT_FAINT);
    }

    private void drawLevelChip(GuiGraphics g, int cx, int cy, int cw, int ch, SpacePermission.AccessLevel level,
                               boolean active, int mx, int my) {
        boolean hover = Theme.inside(mx, my, cx, cy, cw, ch);
        int fill = active ? Theme.PRIMARY_SOFT : hover ? Theme.SURFACE_ALT : Theme.SURFACE_SUNK;
        int border = active ? Theme.PRIMARY : Theme.BORDER;
        Theme.panel(g, cx, cy, cw, ch, Math.min(5, ch / 2), fill, border);
        Theme.textInBox(g, font, Theme.ellipsize(font, levelLabel(level), Math.max(8, cw - 6)), cx, cy, cw, ch,
                active ? Theme.PRIMARY_PRESS : Theme.TEXT_MUTED);
    }

    private void ensureTeamSelection() {
        List<TransferGraphSyncPacket.TeamData> teams = ClientTransferGraphCache.teams();
        if (teams.isEmpty()) {
            selectedTeamId = "";
            return;
        }
        for (TransferGraphSyncPacket.TeamData team : teams) {
            if (team.id().equals(selectedTeamId)) return;
        }
        selectedTeamId = teams.get(0).id();
        lastTeamInputId = "";
        teamMemberScroll = 0;
    }

    private TransferGraphSyncPacket.TeamData selectedTeam() {
        ensureTeamSelection();
        for (TransferGraphSyncPacket.TeamData team : ClientTransferGraphCache.teams()) {
            if (team.id().equals(selectedTeamId)) return team;
        }
        return null;
    }

    private void syncTeamNameInput(TransferGraphSyncPacket.TeamData team) {
        if (teamNameInput == null || team == null || teamNameInput.isFocused()) return;
        if (!team.id().equals(lastTeamInputId) || !team.name().equals(teamNameInput.getValue())) {
            teamNameInput.setValue(team.name());
            lastTeamInputId = team.id();
        }
    }

    private void renameTeam(TransferGraphSyncPacket.TeamData team) {
        String name = teamNameInput == null ? "" : teamNameInput.getValue().trim();
        if (!name.isEmpty() && !name.equals(team.name())) {
            PacketDistributor.sendToServer(new TransferTeamPacket("RENAME", team.id(), name));
        }
        teamNameInput.setFocused(false);
    }

    private void addTeamMember(TransferGraphSyncPacket.TeamData team) {
        String value = teamMemberInput == null ? "" : teamMemberInput.getValue().trim();
        if (value.isEmpty()) return;
        UUID resolvedId = resolveInputPlayerId(value);
        if (resolvedId != null && teamHasMember(team, resolvedId)) {
            notifyClient("该玩家已在团队中");
            return;
        }
        if (resolvedId == null && teamHasMemberName(team, value)) {
            notifyClient("该玩家已在团队中");
            return;
        }
        setTeamMember(team, resolvedId == null ? value : resolvedId.toString(), teamAddLevel);
        teamMemberInput.setValue("");
    }

    private void setTeamMember(TransferGraphSyncPacket.TeamData team, String memberId, SpacePermission.AccessLevel level) {
        PacketDistributor.sendToServer(new TransferTeamPacket("SET_MEMBER", team.id(), memberId + "|" + level.name()));
    }

    private List<SpaceInfo> ownedSpaces() {
        List<SpaceInfo> owned = new ArrayList<>();
        UUID self = selfId();
        if (self == null) return owned;
        for (SpaceInfo space : spaces) {
            if (space.isOwner(self)) owned.add(space);
        }
        return owned;
    }

    private void setPrivateDefault(List<SpaceInfo> owned, SpacePermission.AccessLevel level) {
        SpacePermission.AccessMode mode = level == SpacePermission.AccessLevel.NONE
                ? SpacePermission.AccessMode.PRIVATE
                : SpacePermission.AccessMode.PUBLIC;
        SpacePermission.AccessLevel publicLevel = level == SpacePermission.AccessLevel.NONE
                ? SpacePermission.AccessLevel.VIEW
                : level;
        if (!owned.isEmpty()) {
            PacketDistributor.sendToServer(new UpdatePermissionPayload(owned.get(0).spaceId(), mode, SpacePermission.AccessLevel.USE, publicLevel));
        }
    }

    private void addPrivateMember(List<SpaceInfo> owned) {
        String value = privateMemberInput == null ? "" : privateMemberInput.getValue().trim();
        if (value.isEmpty() || owned.isEmpty()) return;
        UUID id = resolveInputPlayerId(value);
        SpaceInfo space = owned.get(0);
        if (id != null && privateHasMember(space, id)) {
            notifyClient("该玩家已有单人权限规则");
            return;
        }
        if (id == null && privateHasMemberName(space, value)) {
            notifyClient("该玩家已有单人权限规则");
            return;
        }
        if (id != null) {
            PacketDistributor.sendToServer(PermissionMemberPayload.updateById(space.spaceId(), id,
                    roleFor(privateAddLevel), privateAddLevel));
        } else {
            PacketDistributor.sendToServer(PermissionMemberPayload.addByName(space.spaceId(), value,
                    roleFor(privateAddLevel), privateAddLevel));
        }
        privateMemberInput.setValue("");
    }

    private void setPrivateMember(List<SpaceInfo> owned, UUID memberId, SpacePermission.AccessLevel level) {
        if (!owned.isEmpty()) {
            PacketDistributor.sendToServer(PermissionMemberPayload.updateById(owned.get(0).spaceId(), memberId, roleFor(level), level));
        }
    }

    private boolean privateRulesMixed(List<SpaceInfo> owned) {
        if (owned.size() < 2) return false;
        SpaceInfo first = owned.get(0);
        SpacePermission.AccessLevel level = privateDefaultLevel(first);
        int members = first.members().size();
        for (int i = 1; i < owned.size(); i++) {
            SpaceInfo space = owned.get(i);
            if (privateDefaultLevel(space) != level || space.members().size() != members) return true;
        }
        return false;
    }

    private SpacePermission.AccessLevel privateDefaultLevel(SpaceInfo space) {
        return switch (space.mode()) {
            case PRIVATE, WHITELIST -> SpacePermission.AccessLevel.NONE;
            case PUBLIC, BLACKLIST -> space.publicLevel();
        };
    }

    private SpacePermission.AccessLevel effectiveMemberLevel(SpaceInfo space, SpaceInfo.Member member) {
        if (member.overrideLevel() != null) return member.overrideLevel();
        return member.effectiveLevel();
    }

    private SpacePermission.MemberRole roleFor(SpacePermission.AccessLevel level) {
        return switch (level) {
            case NONE -> SpacePermission.MemberRole.BLOCKED;
            case VIEW -> SpacePermission.MemberRole.VISITOR;
            case USE, WRITE -> SpacePermission.MemberRole.MEMBER;
            case MANAGE -> SpacePermission.MemberRole.ADMIN;
        };
    }

    private SpacePermission.AccessLevel nextTeamLevel(SpacePermission.AccessLevel level) {
        for (int i = 0; i < TEAM_LEVELS.length; i++) {
            if (TEAM_LEVELS[i] == level) return TEAM_LEVELS[(i + 1) % TEAM_LEVELS.length];
        }
        return SpacePermission.AccessLevel.VIEW;
    }

    private SpacePermission.AccessLevel nextPrivateLevel(SpacePermission.AccessLevel level) {
        for (int i = 0; i < PRIVATE_DEFAULT_LEVELS.length; i++) {
            if (PRIVATE_DEFAULT_LEVELS[i] == level) return PRIVATE_DEFAULT_LEVELS[(i + 1) % PRIVATE_DEFAULT_LEVELS.length];
        }
        return SpacePermission.AccessLevel.NONE;
    }

    private SpacePermission.AccessLevel levelFromName(String value) {
        try {
            return value == null || value.isBlank() ? SpacePermission.AccessLevel.NONE : SpacePermission.AccessLevel.valueOf(value);
        } catch (IllegalArgumentException e) {
            return SpacePermission.AccessLevel.NONE;
        }
    }

    private String teamSubLabel(TransferGraphSyncPacket.TeamData team) {
        if (isSelf(team.ownerId())) return "我创建 · " + team.members().size() + " 人";
        return levelLabel(levelFromName(team.selfLevel())) + " · " + team.members().size() + " 人";
    }

    private String levelLabel(SpacePermission.AccessLevel level) {
        return switch (level) {
            case NONE -> "拒绝";
            case VIEW -> "可见";
            case USE -> "可进入";
            case WRITE -> "可放置";
            case MANAGE -> "管理";
        };
    }

    private UUID selfId() {
        return mc.player == null ? null : mc.player.getUUID();
    }

    private boolean isSelf(String uuid) {
        UUID self = selfId();
        return self != null && uuid != null && uuid.equals(self.toString());
    }

    private UUID parseUuid(String value) {
        try {
            return value == null || value.isBlank() ? null : UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private UUID resolveInputPlayerId(String value) {
        UUID id = parseUuid(value);
        if (id != null) return id;
        String query = value == null ? "" : value.trim();
        if (query.isEmpty()) return null;
        for (OnlinePlayer player : onlinePlayers()) {
            if (player.name().equalsIgnoreCase(query)) return player.id();
        }
        return null;
    }

    private boolean teamHasMember(TransferGraphSyncPacket.TeamData team, UUID id) {
        if (team == null || id == null) return false;
        String uuid = id.toString();
        for (TransferGraphSyncPacket.TeamMemberData member : team.members()) {
            if (uuid.equalsIgnoreCase(member.id())) return true;
        }
        return false;
    }

    private boolean teamHasMemberName(TransferGraphSyncPacket.TeamData team, String name) {
        if (team == null || name == null || name.isBlank()) return false;
        String query = name.trim().toLowerCase(Locale.ROOT);
        for (TransferGraphSyncPacket.TeamMemberData member : team.members()) {
            if (member.name() != null && member.name().toLowerCase(Locale.ROOT).equals(query)) return true;
        }
        return false;
    }

    private boolean privateHasMember(SpaceInfo space, UUID id) {
        if (space == null || id == null) return false;
        for (SpaceInfo.Member member : space.members()) {
            if (id.equals(member.id())) return true;
        }
        return false;
    }

    private boolean privateHasMemberName(SpaceInfo space, String name) {
        if (space == null || name == null || name.isBlank()) return false;
        String query = name.trim().toLowerCase(Locale.ROOT);
        for (SpaceInfo.Member member : space.members()) {
            if (member.name() != null && member.name().toLowerCase(Locale.ROOT).equals(query)) return true;
        }
        return false;
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

    private void notifyClient(String message) {
        if (mc.player != null) mc.player.displayClientMessage(Component.literal(message), true);
    }

    private record OnlinePlayer(UUID id, String name) {}

    private String shortId(String id) {
        if (id == null || id.isBlank()) return "?";
        return id.substring(0, Math.min(8, id.length()));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
