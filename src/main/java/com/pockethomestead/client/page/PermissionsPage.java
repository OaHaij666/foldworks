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
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class PermissionsPage extends Page {
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
            Theme.textCentered(g, font, "选择左侧团队，或新建一个团队", dx + dw / 2, dy + dh / 2 - 4, Theme.TEXT_FAINT);
            return;
        }
        syncTeamNameInput(team);
        boolean canManage = levelFromName(team.selfLevel()).allows(SpacePermission.AccessLevel.MANAGE);
        boolean owner = isSelf(team.ownerId());
        Theme.text(g, font, Theme.ellipsize(font, team.name(), Math.max(40, dw - 96)), dx + 10, dy + 9, Theme.TEXT);
        Theme.textRight(g, font, owner ? "所有者" : levelLabel(levelFromName(team.selfLevel())), dx + dw - 10, dy + 9,
                owner ? Theme.SUCCESS : Theme.TEXT_MUTED);

        int curY = dy + 28;
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
            renderInput(g, teamMemberInput, dx + 10, curY, inputW, 20, "玩家 UUID", mx, my, pt, true);
            chip(g, dx + 14 + inputW, curY, levelW, 20, levelLabel(teamAddLevel), true, mx, my);
            button(g, dx + 18 + inputW + levelW, curY, addW, 20, "添加", true, mx, my);
            curY += 28;
        }

        Theme.hLine(g, dx + 10, curY - 4, dw - 20, Theme.DIVIDER);
        renderTeamMembers(g, mx, my, team, dx + 10, curY, dw - 20, dy + dh - curY - 8, canManage);
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
            Theme.text(g, font, shortId(member.id()) + (ownerRow ? "  所有者" : ""), lx + 6, ry + 5, Theme.TEXT);
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
        renderInput(g, privateMemberInput, dx + 10, addY, inputW, 20, "玩家名或 UUID", mx, my, pt, true);
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
        if (team != null && Theme.inside(mx, my, detailX, listY, detailW, teamH)
                && handleTeamDetailClick(mx, my, team, detailX, listY, detailW, teamH)) {
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
            if (Theme.inside(mx, my, dx + 10, curY, addInputW, 20)) {
                teamMemberInput.setFocused(true);
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
        if (Theme.inside(mx, my, dx + 10, addY, inputW, 20)) {
            privateMemberInput.setFocused(true);
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
                for (SpaceInfo ownedSpace : owned) {
                    PacketDistributor.sendToServer(PermissionMemberPayload.remove(ownedSpace.spaceId(), member.id()));
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (!Theme.inside(mx, my, x, y, w, h)) return false;
        int delta = (int) Math.signum(sy);
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
            teamMemberInput = new EditBox(font, 0, 0, 80, 14, Component.literal("玩家 UUID"));
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
    }

    private void renderInput(GuiGraphics g, EditBox input, int ix, int iy, int iw, int ih, String placeholder,
                             int mx, int my, float pt, boolean enabled) {
        Theme.panel(g, ix, iy, iw, ih, 5, enabled ? Theme.SURFACE_SUNK : Theme.SURFACE_ALT,
                input != null && input.isFocused() ? Theme.PRIMARY : Theme.BORDER);
        input.setX(ix + 6);
        input.setY(iy + 6);
        input.setWidth(iw - 12);
        input.setEditable(enabled);
        if (input.getValue().isBlank() && !input.isFocused()) {
            Theme.text(g, font, placeholder, ix + 6, iy + 6, Theme.TEXT_FAINT);
        } else {
            input.render(g, mx, my, pt);
        }
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
        String uuid = teamMemberInput == null ? "" : teamMemberInput.getValue().trim();
        if (parseUuid(uuid) == null) return;
        setTeamMember(team, uuid, teamAddLevel);
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
        for (SpaceInfo space : owned) {
            PacketDistributor.sendToServer(new UpdatePermissionPayload(space.spaceId(), mode, SpacePermission.AccessLevel.USE, publicLevel));
        }
    }

    private void addPrivateMember(List<SpaceInfo> owned) {
        String value = privateMemberInput == null ? "" : privateMemberInput.getValue().trim();
        if (value.isEmpty() || owned.isEmpty()) return;
        UUID id = parseUuid(value);
        for (SpaceInfo space : owned) {
            if (id != null) {
                PacketDistributor.sendToServer(PermissionMemberPayload.updateById(space.spaceId(), id,
                        roleFor(privateAddLevel), privateAddLevel));
            } else {
                PacketDistributor.sendToServer(PermissionMemberPayload.addByName(space.spaceId(), value,
                        roleFor(privateAddLevel), privateAddLevel));
            }
        }
        privateMemberInput.setValue("");
    }

    private void setPrivateMember(List<SpaceInfo> owned, UUID memberId, SpacePermission.AccessLevel level) {
        for (SpaceInfo space : owned) {
            PacketDistributor.sendToServer(PermissionMemberPayload.updateById(space.spaceId(), memberId, roleFor(level), level));
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

    private String shortId(String id) {
        if (id == null || id.isBlank()) return "?";
        return id.substring(0, Math.min(8, id.length()));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
