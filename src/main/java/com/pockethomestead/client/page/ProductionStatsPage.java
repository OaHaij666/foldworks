package com.pockethomestead.client.page;

import com.pockethomestead.client.ClientProductionStatsCache;
import com.pockethomestead.client.ui.Page;
import com.pockethomestead.client.ui.Theme;
import com.pockethomestead.network.ProductionStatsSyncPacket;
import com.pockethomestead.network.RequestProductionStatsPacket;
import com.pockethomestead.network.UpdateProductionStatsPacket;
import com.pockethomestead.production.ProductionStatsStorage;
import com.pockethomestead.transfer.TransferEdge;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class ProductionStatsPage extends Page {
    private static final int[] RANGES = {1, 5, 10, 30, 60};
    private static final String[] SORT_LABELS = {"净产率", "生产", "消耗", "名称", "库存"};

    private static final int TOOLBAR_H = 36;
    private static final int HEADER_H = 20;
    private static final int ROW_H = 58;
    private static final int ROW_GAP = 6;
    private static final int GROUP_ROW_H = 18;

    private static final int PRODUCTION_COLOR = 0xFF18A8F2;
    private static final int CONSUMPTION_COLOR = 0xFFD9AD24;
    private static final int GRAPH_BG = 0xFFE9F1F8;
    private static final int GRAPH_GRID = 0x66FFFFFF;
    private static final int GRAPH_GRID_DARK = 0x44B9CADB;

    private String selectedGroupId = ProductionStatsStorage.DEFAULT_GROUP_ID;
    private int rangeIndex = 2;
    private int sortMode;
    private int rowScroll;
    private int groupScroll;
    private boolean hideZero;
    private String searchValue = "";
    private boolean searchFocused;
    private boolean groupDropdownOpen;
    private int syncTicker;
    private InputMode inputMode = InputMode.NONE;
    private String inputValue = "";
    private String inputTargetGroupId = "";
    private final Set<String> mergeSelection = new LinkedHashSet<>();
    private final Set<String> expandedGroups = new LinkedHashSet<>();

    private enum InputMode { NONE, CREATE_GROUP, RENAME }
    private record GroupRow(ProductionStatsSyncPacket.GroupData group, int depth) {}

    @Override
    public String id() {
        return "production";
    }

    @Override
    public String navTitle() {
        return "产率统计";
    }

    @Override
    public String navIcon() {
        return "▦";
    }

    @Override
    public void onEnter() {
        PacketDistributor.sendToServer(new RequestProductionStatsPacket());
    }

    @Override
    public void onExit() {
        groupDropdownOpen = false;
        searchFocused = false;
        inputMode = InputMode.NONE;
    }

    @Override
    public void tick() {
        syncTicker++;
        if (syncTicker >= 60) {
            syncTicker = 0;
            PacketDistributor.sendToServer(new RequestProductionStatsPacket());
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        ensureSelectedGroup();
        Theme.panel(g, x, y, w, h, Theme.RADIUS + 1, Theme.SURFACE, Theme.BORDER);
        renderToolbar(g, mouseX, mouseY);
        renderTable(g, mouseX, mouseY);
    }

    @Override
    public void renderOverlay(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (groupDropdownOpen) renderGroupDropdown(g, mouseX, mouseY);
        if (inputMode != InputMode.NONE) renderInputOverlay(g);
    }

    private void renderToolbar(GuiGraphics g, int mx, int my) {
        int toolbarY = y + 7;
        if (w >= 300) Theme.text(g, font, "产率统计", x + 11, toolbarY + 5, Theme.TEXT);

        int groupX = groupButtonX();
        int groupY = groupButtonY();
        int groupW = groupButtonWidth();
        boolean groupHover = Theme.inside(mx, my, groupX, groupY, groupW, 18);
        pill(g, groupX, groupY, groupW, 18, selectedGroupName(), groupHover || groupDropdownOpen ? Theme.PRIMARY_SOFT : Theme.SURFACE_ALT,
                groupHover || groupDropdownOpen ? Theme.PRIMARY_PRESS : Theme.TEXT_MUTED);
        Theme.chevronDown(g, groupX + groupW - 11, groupY + 9, 6, groupHover || groupDropdownOpen ? Theme.PRIMARY_PRESS : Theme.TEXT_MUTED);

        int rangeX = rangeButtonX();
        int sortX = sortButtonX();
        int zeroX = zeroButtonX();
        if (showHideZeroControl()) chip(g, zeroX, toolbarY + 1, 56, 17, hideZero ? "显示零" : "隐藏零", Theme.SURFACE_ALT, Theme.TEXT_MUTED);
        if (showSortControl()) chip(g, sortX, toolbarY + 1, 62, 17, SORT_LABELS[sortMode], Theme.SURFACE_ALT, Theme.TEXT_MUTED);
        chip(g, rangeX, toolbarY + 1, 62, 17, RANGES[rangeIndex] + "分钟", Theme.PRIMARY_SOFT, Theme.PRIMARY_PRESS);

        int searchX = groupX + groupW + 8;
        int searchRight = searchRightX();
        if (searchRight - searchX >= 66) {
            int searchW = Math.min(132, searchRight - searchX);
            Theme.panel(g, searchX, toolbarY + 1, searchW, 18, 7, searchFocused ? 0xFFFFFFFF : Theme.SURFACE_SUNK,
                    searchFocused ? Theme.PRIMARY : Theme.BORDER);
            Theme.text(g, font, searchValue.isEmpty() ? "搜索资源" : Theme.ellipsize(font, searchValue, searchW - 14),
                    searchX + 7, toolbarY + 6, searchValue.isEmpty() ? Theme.TEXT_FAINT : Theme.TEXT);
        }
    }

    private void renderTable(GuiGraphics g, int mx, int my) {
        int innerX = x + 8;
        int innerW = w - 16;
        int headerY = y + TOOLBAR_H + 6;
        int listY = headerY + HEADER_H + 6;
        int bottomPad = 8;
        int maxRows = Math.max(1, (h - (listY - y) - bottomPad) / (ROW_H + ROW_GAP));
        List<ClientProductionStatsCache.ProductionRow> rows = filteredRows();
        rowScroll = clamp(rowScroll, 0, Math.max(0, rows.size() - maxRows));

        renderTableHeader(g, innerX, headerY, innerW);
        if (rows.isEmpty()) {
            renderEmptyState(g, innerX, listY, innerW, h - (listY - y) - bottomPad);
            return;
        }
        for (int i = 0; i < maxRows && i + rowScroll < rows.size(); i++) {
            int rowY = listY + i * (ROW_H + ROW_GAP);
            renderRow(g, mx, my, rows.get(i + rowScroll), innerX, rowY, innerW, ROW_H);
        }
    }

    private void renderTableHeader(GuiGraphics g, int hx, int hy, int hw) {
        int infoW = infoColumnWidth(hw);
        int statW = statColumnWidth(hw);
        int gap = columnGap(hw);
        int trendW = Math.max(72, hw - infoW - statW - gap * 2);
        int trendX = hx + infoW + gap;
        int statX = trendX + trendW + gap;

        Theme.fillRound(g, hx, hy, hw, HEADER_H, 9, 0x99E5EDF5);
        Theme.vLine(g, trendX - gap / 2, hy + 3, HEADER_H - 6, 0x99C8D7E6);
        Theme.vLine(g, statX - gap / 2, hy + 3, HEADER_H - 6, 0x99C8D7E6);
        Theme.textCentered(g, font, "产物信息", hx + infoW / 2, hy + 6, Theme.TEXT_MUTED);
        Theme.textCentered(g, font, "产率/消耗率走势", trendX + trendW / 2, hy + 6, Theme.TEXT_MUTED);
        Theme.textCentered(g, font, "当前统计", statX + statW / 2, hy + 6, Theme.TEXT_MUTED);
    }

    private void renderEmptyState(GuiGraphics g, int ex, int ey, int ew, int eh) {
        int boxW = Math.min(260, Math.max(170, ew - 28));
        int boxH = 78;
        int bx = ex + (ew - boxW) / 2;
        int by = ey + Math.max(10, (eh - boxH) / 2);
        Theme.panel(g, bx, by, boxW, boxH, Theme.RADIUS + 2, 0xFFF8FCFF, Theme.DIVIDER);
        Theme.fillRound(g, bx + 14, by + 16, 24, 24, 8, Theme.PRIMARY_SOFT);
        drawMiniBars(g, bx + 21, by + 22, Theme.PRIMARY_PRESS);
        Theme.text(g, font, "暂无产率数据", bx + 48, by + 18, Theme.TEXT);
        Theme.text(g, font, "箱子加入统计后，库存变化会自动出现在这里。", bx + 48, by + 36, Theme.TEXT_FAINT);
    }

    private void renderRow(GuiGraphics g, int mx, int my, ClientProductionStatsCache.ProductionRow row,
                           int rx, int ry, int rw, int rh) {
        boolean hover = Theme.inside(mx, my, rx, ry, rw, rh);
        Theme.panel(g, rx, ry, rw, rh, 7, hover ? 0xFFFFFFFF : 0xFFF8FCFF, hover ? Theme.BORDER_STRONG : Theme.DIVIDER);

        int infoW = infoColumnWidth(rw);
        int statW = statColumnWidth(rw);
        int gap = columnGap(rw);
        int trendW = Math.max(72, rw - infoW - statW - gap * 2);
        int trendX = rx + infoW + gap;
        int statX = trendX + trendW + gap;
        boolean fluid = isFluidResource(row.itemId());

        renderResourceInfo(g, row, fluid, rx, ry, infoW, rh);
        drawTrendGraph(g, row.trendInput(), row.trendOutput(), row.trendNet(), trendX, ry + 8, trendW, rh - 16);
        renderCurrentStats(g, row, fluid, statX, ry, statW, rh);
    }

    private void renderResourceInfo(GuiGraphics g, ClientProductionStatsCache.ProductionRow row, boolean fluid,
                                    int ix, int iy, int iw, int ih) {
        Theme.text(g, font, "★", ix + 7, iy + 22, Theme.TEXT_FAINT);
        int iconX = ix + 25;
        int iconY = iy + 18;
        g.pose().pushPose();
        g.pose().translate(iconX, iconY, 0);
        g.pose().scale(1.15f, 1.15f, 1f);
        if (fluid) g.renderItem(new ItemStack(Items.WATER_BUCKET), 0, 0);
        else {
            Item item = resolveItem(row.itemId());
            if (item != null) g.renderItem(new ItemStack(item), 0, 0);
        }
        g.pose().popPose();

        int textX = ix + 50;
        int textW = Math.max(36, iw - 56);
        Theme.text(g, font, Theme.ellipsize(font, shortResource(row.itemId()), textW), textX, iy + 14, Theme.TEXT);
        Theme.text(g, font, "库存 " + formatCount(row.currentCount()) + (fluid ? " mB" : ""), textX, iy + 31, Theme.TEXT_MUTED);
    }

    private void renderCurrentStats(GuiGraphics g, ClientProductionStatsCache.ProductionRow row, boolean fluid,
                                    int sx, int sy, int sw, int sh) {
        int labelX = sx + 8;
        int valueX = sx + sw - 8;
        String inputText = Theme.ellipsize(font, formatRate(row.inputRatePerMinute(), fluid), Math.max(32, sw - 44));
        String outputText = Theme.ellipsize(font, formatRate(row.outputRatePerMinute(), fluid), Math.max(32, sw - 44));
        String netText = Theme.ellipsize(font, signedRate(row.netRatePerMinute(), fluid), Math.max(36, sw - 18));
        Theme.text(g, font, "生产", labelX, sy + 10, PRODUCTION_COLOR);
        Theme.textRight(g, font, inputText, valueX, sy + 10, PRODUCTION_COLOR);
        Theme.text(g, font, "消耗", labelX, sy + 25, CONSUMPTION_COLOR);
        Theme.textRight(g, font, outputText, valueX, sy + 25, CONSUMPTION_COLOR);

        int netColor = row.netRatePerMinute() >= 0 ? Theme.SUCCESS : Theme.DANGER;
        int badgeFill = row.netRatePerMinute() >= 0 ? 0xFFE6F8F0 : Theme.DANGER_SOFT;
        int badgeW = Math.min(sw - 12, Math.max(54, Theme.styledWidth(font, netText) + 12));
        Theme.fillRound(g, valueX - badgeW, sy + sh - 20, badgeW, 14, 7, badgeFill);
        Theme.textRight(g, font, netText, valueX - 6, sy + sh - 17, netColor);
    }

    private void drawTrendGraph(GuiGraphics g, List<Integer> input, List<Integer> output, List<Integer> net,
                                int gx, int gy, int gw, int gh) {
        Theme.fillRound(g, gx, gy, gw, gh, 5, GRAPH_BG);
        drawFineGrid(g, gx, gy, gw, gh);
        if (input.isEmpty() && output.isEmpty() && net.isEmpty()) return;

        int max = 1;
        for (int v : input) max = Math.max(max, Math.abs(v));
        for (int v : output) max = Math.max(max, Math.abs(v));
        for (int v : net) max = Math.max(max, Math.abs(v));

        int mid = gy + gh / 2;
        Theme.hLine(g, gx + 3, mid, gw - 6, 0x88C4D4E3);
        int points = Math.max(1, Math.max(input.size(), Math.max(output.size(), net.size())));
        int barW = Math.max(1, gw / points);
        for (int i = 0; i < net.size(); i++) {
            int value = net.get(i);
            if (value == 0) continue;
            int bx = gx + i * gw / points;
            int bh = Math.max(1, Math.round(Math.abs(value) * (gh / 2f - 3) / max));
            int color = value >= 0 ? 0x5520A8F0 : 0x55D9AD24;
            if (value >= 0) g.fill(bx, mid - bh, bx + Math.max(1, barW - 1), mid, color);
            else g.fill(bx, mid, bx + Math.max(1, barW - 1), mid + bh, color);
        }
        drawSparkline(g, input, gx + 4, gy + 4, gw - 8, gh - 8, max, PRODUCTION_COLOR);
        drawSparkline(g, output, gx + 4, gy + 4, gw - 8, gh - 8, max, CONSUMPTION_COLOR);
    }

    private void drawFineGrid(GuiGraphics g, int x, int y, int w, int h) {
        for (int gx = x + 12; gx < x + w; gx += 12) g.fill(gx, y + 2, gx + 1, y + h - 2, GRAPH_GRID);
        for (int gy = y + 9; gy < y + h; gy += 9) g.fill(x + 2, gy, x + w - 2, gy + 1, GRAPH_GRID_DARK);
    }

    private void drawSparkline(GuiGraphics g, List<Integer> values, int x, int y, int w, int h, int max, int color) {
        if (values.size() < 2 || w <= 0 || h <= 0) return;
        float step = w / (float) (values.size() - 1);
        float prevX = x;
        float prevY = sparkY(values.get(0), y, h, max);
        for (int i = 1; i < values.size(); i++) {
            float nextX = x + step * i;
            float nextY = sparkY(values.get(i), y, h, max);
            Theme.line(g, prevX, prevY, nextX, nextY, 1.35f, color);
            prevX = nextX;
            prevY = nextY;
        }
    }

    private float sparkY(int value, int y, int h, int max) {
        return y + h - 1 - Math.max(0, value) * (h - 2f) / Math.max(1, max);
    }

    private void drawMiniBars(GuiGraphics g, int x, int y, int color) {
        Theme.fillRound(g, x, y + 8, 3, 8, 1, color);
        Theme.fillRound(g, x + 6, y + 4, 3, 12, 1, color);
        Theme.fillRound(g, x + 12, y, 3, 16, 1, color);
    }

    private void renderGroupDropdown(GuiGraphics g, int mx, int my) {
        int px = groupPopupX();
        int py = groupPopupY();
        int pw = groupPopupW();
        int ph = groupPopupH();
        Theme.shadow(g, px, py, pw, ph, Theme.RADIUS + 2);
        Theme.panel(g, px, py, pw, ph, Theme.RADIUS + 2, 0xFFFBFEFF, Theme.BORDER_STRONG);

        Theme.text(g, font, "分组", px + 10, py + 9, Theme.TEXT);
        Theme.textRight(g, font, mergeSelection.isEmpty() ? "选择统计范围" : "已选 " + mergeSelection.size(),
                px + pw - 10, py + 9, Theme.TEXT_FAINT);
        Theme.hLine(g, px + 8, py + 28, pw - 16, Theme.DIVIDER);

        List<GroupRow> rows = visibleGroupRows();
        int listY = py + 34;
        int buttonY = py + ph - 28;
        int maxRows = Math.max(1, (buttonY - listY - 4) / GROUP_ROW_H);
        groupScroll = clamp(groupScroll, 0, Math.max(0, rows.size() - maxRows));
        for (int i = 0; i < maxRows && i + groupScroll < rows.size(); i++) {
            int rowY = listY + i * GROUP_ROW_H;
            renderGroupDropdownRow(g, mx, my, rows.get(i + groupScroll), px + 8, rowY, pw - 16);
        }

        boolean canMerge = mergeSelection.size() >= 2;
        int addW = groupActionAddWidth(pw);
        int renameW = groupActionRenameWidth(pw);
        int addX = px + 8;
        int mergeX = addX + addW + 4;
        int renameX = mergeX + 38 + 4;
        int deleteX = renameX + renameW + 4;
        boolean compact = pw < 204;
        chip(g, addX, buttonY, addW, 17, compact ? "+" : "+分组", Theme.PRIMARY_SOFT, Theme.PRIMARY_PRESS);
        chip(g, mergeX, buttonY, 38, 17, "合并", canMerge ? Theme.SURFACE_ALT : Theme.SURFACE_SUNK,
                canMerge ? Theme.PRIMARY_PRESS : Theme.TEXT_FAINT);
        chip(g, renameX, buttonY, renameW, 17, compact ? "改名" : "重命名", Theme.SURFACE_ALT, Theme.PRIMARY_PRESS);
        chip(g, deleteX, buttonY, 36, 17, "删除", Theme.SURFACE_ALT,
                ProductionStatsStorage.DEFAULT_GROUP_ID.equals(selectedGroupId) ? Theme.TEXT_FAINT : Theme.DANGER);
    }

    private void renderGroupDropdownRow(GuiGraphics g, int mx, int my, GroupRow row, int rx, int ry, int rw) {
        ProductionStatsSyncPacket.GroupData group = row.group();
        boolean selected = group.id().equals(selectedGroupId);
        boolean hover = Theme.inside(mx, my, rx, ry, rw, GROUP_ROW_H - 1);
        if (selected || hover) Theme.fillRound(g, rx, ry, rw, GROUP_ROW_H - 1, 5, selected ? Theme.PRIMARY_SOFT : Theme.SURFACE_ALT);

        int indent = Math.min(34, row.depth() * 10);
        int cursorX = rx + 5 + indent;
        if (group.aggregate()) {
            if (expandedGroups.contains(group.id())) Theme.chevronDown(g, cursorX + 4, ry + 9, 6, Theme.TEXT_MUTED);
            else Theme.chevronRight(g, cursorX + 4, ry + 9, 6, Theme.TEXT_MUTED);
        } else {
            Theme.fillRound(g, cursorX + 1, ry + 7, 6, 6, 3, Theme.SUCCESS);
        }
        cursorX += 11;
        drawCheckBox(g, cursorX, ry + 5, mergeSelection.contains(group.id()));
        cursorX += 13;

        int color = selected ? Theme.PRIMARY_PRESS : Theme.TEXT;
        Theme.text(g, font, Theme.ellipsize(font, group.name(), Math.max(28, rw - (cursorX - rx) - 4)), cursorX, ry + 5, color);
    }

    private void drawCheckBox(GuiGraphics g, int x, int y, boolean checked) {
        Theme.panel(g, x, y, 8, 8, 2, checked ? Theme.PRIMARY_SOFT : Theme.SURFACE, checked ? Theme.PRIMARY : Theme.BORDER_STRONG);
        if (checked) Theme.fillRound(g, x + 2, y + 2, 4, 4, 2, Theme.PRIMARY_PRESS);
    }

    private void renderInputOverlay(GuiGraphics g) {
        g.fill(0, 0, mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight(), Theme.SCRIM);
        int bw = 190;
        int bh = 76;
        int bx = x + (w - bw) / 2;
        int by = y + (h - bh) / 2;
        Theme.shadow(g, bx, by, bw, bh, Theme.RADIUS + 1);
        Theme.panel(g, bx, by, bw, bh, Theme.RADIUS + 1, Theme.SURFACE, Theme.BORDER_STRONG);
        String title = inputMode == InputMode.RENAME ? "重命名分组" : "新增分组";
        Theme.text(g, font, title, bx + 10, by + 10, Theme.TEXT);
        Theme.panel(g, bx + 10, by + 28, bw - 20, 18, 5, 0xFFFFFFFF, Theme.PRIMARY);
        Theme.text(g, font, inputValue + ((syncTicker / 10) % 2 == 0 ? "_" : ""), bx + 16, by + 33, Theme.TEXT);
        chip(g, bx + 10, by + 54, 52, 16, "保存", Theme.PRIMARY_SOFT, Theme.PRIMARY_PRESS);
        chip(g, bx + 70, by + 54, 52, 16, "取消", Theme.SURFACE_ALT, Theme.TEXT_MUTED);
    }

    @Override
    public boolean overlayMouseClicked(double mx, double my, int button) {
        if (button != 0) return false;
        if (inputMode != InputMode.NONE) return handleInputClick(mx, my);
        if (groupDropdownOpen) return handleGroupDropdownClick(mx, my);
        return false;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return false;
        if (Theme.inside(mx, my, groupButtonX(), groupButtonY(), groupButtonWidth(), 18)) {
            groupDropdownOpen = !groupDropdownOpen;
            searchFocused = false;
            return true;
        }
        return handleStatsClick(mx, my);
    }

    private boolean handleStatsClick(double mx, double my) {
        int toolbarY = y + 7;
        int rangeX = rangeButtonX();
        int sortX = sortButtonX();
        int zeroX = zeroButtonX();
        int groupX = groupButtonX();
        int searchX = groupX + groupButtonWidth() + 8;
        int searchRight = searchRightX();
        if (searchRight - searchX >= 66) {
            int searchW = Math.min(132, searchRight - searchX);
            if (Theme.inside(mx, my, searchX, toolbarY + 1, searchW, 18)) {
                searchFocused = true;
                return true;
            }
        }
        searchFocused = false;
        if (Theme.inside(mx, my, rangeX, toolbarY + 1, 62, 17)) {
            rangeIndex = (rangeIndex + 1) % RANGES.length;
            rowScroll = 0;
            return true;
        }
        if (showSortControl() && Theme.inside(mx, my, sortX, toolbarY + 1, 62, 17)) {
            sortMode = (sortMode + 1) % SORT_LABELS.length;
            rowScroll = 0;
            return true;
        }
        if (showHideZeroControl() && Theme.inside(mx, my, zeroX, toolbarY + 1, 56, 17)) {
            hideZero = !hideZero;
            rowScroll = 0;
            return true;
        }
        return Theme.inside(mx, my, x, y, w, h);
    }

    private boolean handleGroupDropdownClick(double mx, double my) {
        if (Theme.inside(mx, my, groupButtonX(), groupButtonY(), groupButtonWidth(), 18)) {
            groupDropdownOpen = false;
            return true;
        }
        int px = groupPopupX();
        int py = groupPopupY();
        int pw = groupPopupW();
        int ph = groupPopupH();
        if (!Theme.inside(mx, my, px, py, pw, ph)) {
            groupDropdownOpen = false;
            return true;
        }

        int buttonY = py + ph - 28;
        int addW = groupActionAddWidth(pw);
        int renameW = groupActionRenameWidth(pw);
        int addX = px + 8;
        int mergeX = addX + addW + 4;
        int renameX = mergeX + 38 + 4;
        int deleteX = renameX + renameW + 4;
        if (Theme.inside(mx, my, addX, buttonY, addW, 17)) {
            openInput(InputMode.CREATE_GROUP, "新分组", "");
            return true;
        }
        if (Theme.inside(mx, my, mergeX, buttonY, 38, 17)) {
            if (mergeSelection.size() >= 2) {
                List<String> values = new ArrayList<>();
                values.add("新大组");
                values.addAll(mergeSelection);
                PacketDistributor.sendToServer(new UpdateProductionStatsPacket("MERGE_GROUPS", values));
                mergeSelection.clear();
            }
            return true;
        }
        if (Theme.inside(mx, my, renameX, buttonY, renameW, 17)) {
            ProductionStatsSyncPacket.GroupData group = ClientProductionStatsCache.group(selectedGroupId);
            if (group != null) openInput(InputMode.RENAME, group.name(), group.id());
            return true;
        }
        if (Theme.inside(mx, my, deleteX, buttonY, 36, 17)) {
            if (!ProductionStatsStorage.DEFAULT_GROUP_ID.equals(selectedGroupId)) {
                PacketDistributor.sendToServer(new UpdateProductionStatsPacket("DELETE_GROUP", List.of(selectedGroupId)));
                selectedGroupId = ProductionStatsStorage.DEFAULT_GROUP_ID;
                groupDropdownOpen = false;
            }
            return true;
        }

        int listY = py + 34;
        int maxRows = Math.max(1, (buttonY - listY - 4) / GROUP_ROW_H);
        List<GroupRow> rows = visibleGroupRows();
        groupScroll = clamp(groupScroll, 0, Math.max(0, rows.size() - maxRows));
        for (int i = 0; i < maxRows && i + groupScroll < rows.size(); i++) {
            int rowY = listY + i * GROUP_ROW_H;
            GroupRow row = rows.get(i + groupScroll);
            ProductionStatsSyncPacket.GroupData group = row.group();
            int rx = px + 8;
            int indent = Math.min(34, row.depth() * 10);
            int markerX = rx + 5 + indent;
            int boxX = markerX + 11;
            if (group.aggregate() && Theme.inside(mx, my, markerX - 2, rowY + 2, 12, 14)) {
                if (expandedGroups.contains(group.id())) expandedGroups.remove(group.id());
                else expandedGroups.add(group.id());
                return true;
            }
            if (Theme.inside(mx, my, boxX - 2, rowY + 3, 12, 12)) {
                if (mergeSelection.contains(group.id())) mergeSelection.remove(group.id());
                else mergeSelection.add(group.id());
                return true;
            }
            if (Theme.inside(mx, my, rx, rowY, pw - 16, GROUP_ROW_H - 1)) {
                selectedGroupId = group.id();
                rowScroll = 0;
                groupDropdownOpen = false;
                return true;
            }
        }
        return true;
    }

    private boolean handleInputClick(double mx, double my) {
        int bw = 190;
        int bh = 76;
        int bx = x + (w - bw) / 2;
        int by = y + (h - bh) / 2;
        if (Theme.inside(mx, my, bx + 10, by + 54, 52, 16)) {
            commitInput();
            return true;
        }
        if (Theme.inside(mx, my, bx + 70, by + 54, 52, 16) || !Theme.inside(mx, my, bx, by, bw, bh)) {
            inputMode = InputMode.NONE;
            return true;
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (inputMode != InputMode.NONE) return true;
        if (groupDropdownOpen && Theme.inside(mx, my, groupPopupX(), groupPopupY(), groupPopupW(), groupPopupH())) {
            int maxRows = Math.max(1, ((groupPopupY() + groupPopupH() - 28) - (groupPopupY() + 34) - 4) / GROUP_ROW_H);
            List<GroupRow> rows = visibleGroupRows();
            groupScroll = clamp(groupScroll - (int) Math.signum(sy), 0, Math.max(0, rows.size() - maxRows));
            return true;
        }
        if (Theme.inside(mx, my, x, y, w, h)) {
            List<ClientProductionStatsCache.ProductionRow> rows = filteredRows();
            int listY = y + TOOLBAR_H + 6 + HEADER_H + 6;
            int maxRows = Math.max(1, (h - (listY - y) - 8) / (ROW_H + ROW_GAP));
            rowScroll = clamp(rowScroll - (int) Math.signum(sy), 0, Math.max(0, rows.size() - maxRows));
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (inputMode != InputMode.NONE) {
            if (keyCode == 256) {
                inputMode = InputMode.NONE;
                return true;
            }
            if (keyCode == 257 || keyCode == 335) {
                commitInput();
                return true;
            }
            if (keyCode == 259 && !inputValue.isEmpty()) {
                inputValue = inputValue.substring(0, inputValue.length() - 1);
                return true;
            }
            return true;
        }
        if (groupDropdownOpen && keyCode == 256) {
            groupDropdownOpen = false;
            return true;
        }
        if (searchFocused) {
            if (keyCode == 256) {
                searchFocused = false;
                return true;
            }
            if (keyCode == 259 && !searchValue.isEmpty()) {
                searchValue = searchValue.substring(0, searchValue.length() - 1);
                rowScroll = 0;
                return true;
            }
            if (keyCode == 261) {
                searchValue = "";
                rowScroll = 0;
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (inputMode != InputMode.NONE && !Character.isISOControl(codePoint) && inputValue.length() < 24) {
            inputValue += codePoint;
            return true;
        }
        if (searchFocused && !Character.isISOControl(codePoint) && searchValue.length() < 32) {
            searchValue += codePoint;
            rowScroll = 0;
            return true;
        }
        return false;
    }

    private List<GroupRow> visibleGroupRows() {
        List<ProductionStatsSyncPacket.GroupData> groups = ClientProductionStatsCache.groups();
        Set<String> childIds = new HashSet<>();
        for (ProductionStatsSyncPacket.GroupData group : groups) {
            if (group.aggregate()) childIds.addAll(group.childIds());
        }

        List<GroupRow> rows = new ArrayList<>();
        for (ProductionStatsSyncPacket.GroupData group : groups) {
            if (!childIds.contains(group.id())) appendGroupRow(rows, group, 0, new HashSet<>());
        }
        if (rows.isEmpty()) {
            for (ProductionStatsSyncPacket.GroupData group : groups) appendGroupRow(rows, group, 0, new HashSet<>());
        }
        return rows;
    }

    private void appendGroupRow(List<GroupRow> rows, ProductionStatsSyncPacket.GroupData group, int depth, Set<String> visiting) {
        if (group == null || !visiting.add(group.id())) return;
        rows.add(new GroupRow(group, depth));
        if (group.aggregate() && expandedGroups.contains(group.id())) {
            for (String childId : group.childIds()) {
                appendGroupRow(rows, ClientProductionStatsCache.group(childId), depth + 1, visiting);
            }
        }
        visiting.remove(group.id());
    }

    private void openInput(InputMode mode, String value, String target) {
        inputMode = mode;
        inputValue = value == null ? "" : value;
        inputTargetGroupId = target == null ? "" : target;
        searchFocused = false;
        groupDropdownOpen = false;
    }

    private void commitInput() {
        String value = inputValue.trim();
        if (value.isEmpty()) value = "新分组";
        if (inputMode == InputMode.CREATE_GROUP) {
            PacketDistributor.sendToServer(new UpdateProductionStatsPacket("CREATE_GROUP", List.of(value, "false")));
        } else if (inputMode == InputMode.RENAME && !inputTargetGroupId.isBlank()) {
            PacketDistributor.sendToServer(new UpdateProductionStatsPacket("RENAME_GROUP", List.of(inputTargetGroupId, value)));
        }
        inputMode = InputMode.NONE;
    }

    private List<ClientProductionStatsCache.ProductionRow> filteredRows() {
        String q = searchValue.trim().toLowerCase(Locale.ROOT);
        List<ClientProductionStatsCache.ProductionRow> rows = new ArrayList<>();
        for (ClientProductionStatsCache.ProductionRow row : ClientProductionStatsCache.rowsFor(selectedGroupId, RANGES[rangeIndex])) {
            if (hideZero && row.inputRatePerMinute() == 0 && row.outputRatePerMinute() == 0 && row.currentCount() == 0) continue;
            if (!q.isEmpty() && !row.itemId().toLowerCase(Locale.ROOT).contains(q) && !shortResource(row.itemId()).toLowerCase(Locale.ROOT).contains(q)) continue;
            rows.add(row);
        }
        Comparator<ClientProductionStatsCache.ProductionRow> comparator = switch (sortMode) {
            case 1 -> Comparator.comparingInt(ClientProductionStatsCache.ProductionRow::inputRatePerMinute).reversed();
            case 2 -> Comparator.comparingInt(ClientProductionStatsCache.ProductionRow::outputRatePerMinute).reversed();
            case 3 -> Comparator.comparing(row -> shortResource(row.itemId()));
            case 4 -> Comparator.comparingInt(ClientProductionStatsCache.ProductionRow::currentCount).reversed();
            default -> Comparator.comparingInt((ClientProductionStatsCache.ProductionRow row) -> Math.abs(row.netRatePerMinute())).reversed();
        };
        rows.sort(comparator);
        return rows;
    }

    private void ensureSelectedGroup() {
        if (ClientProductionStatsCache.group(selectedGroupId) == null) selectedGroupId = ClientProductionStatsCache.firstGroupId();
    }

    private int groupButtonX() {
        return w < 300 ? x + 10 : x + 73;
    }

    private int groupButtonY() {
        return y + 8;
    }

    private int groupButtonWidth() {
        int rightLimit = rangeButtonX() - 8;
        int maxWidth = Math.max(64, rightLimit - groupButtonX());
        return Math.max(64, Math.min(Math.min(132, maxWidth), Theme.styledWidth(font, selectedGroupName()) + 25));
    }

    private int groupPopupX() {
        int popupX = groupButtonX();
        int popupW = groupPopupW();
        if (popupX + popupW > x + w - 8) popupX = x + w - popupW - 8;
        return Math.max(x + 8, popupX);
    }

    private int groupPopupY() {
        return y + 31;
    }

    private int groupPopupW() {
        return Math.min(220, Math.max(176, w - 16));
    }

    private int groupPopupH() {
        return Math.max(108, Math.min(232, h - 40));
    }

    private int rangeButtonX() {
        return x + w - 72;
    }

    private int sortButtonX() {
        return rangeButtonX() - 68;
    }

    private int zeroButtonX() {
        return sortButtonX() - 62;
    }

    private int searchRightX() {
        if (showHideZeroControl()) return zeroButtonX() - 8;
        if (showSortControl()) return sortButtonX() - 8;
        return rangeButtonX() - 8;
    }

    private boolean showSortControl() {
        return w >= 300;
    }

    private boolean showHideZeroControl() {
        return w >= 360;
    }

    private int groupActionAddWidth(int popupW) {
        return popupW < 204 ? 24 : 52;
    }

    private int groupActionRenameWidth(int popupW) {
        return popupW < 204 ? 38 : 50;
    }

    private String selectedGroupName() {
        ProductionStatsSyncPacket.GroupData selected = ClientProductionStatsCache.group(selectedGroupId);
        return selected == null ? "默认" : selected.name();
    }

    private int columnGap(int totalW) {
        return totalW < 360 ? 5 : 8;
    }

    private int infoColumnWidth(int totalW) {
        return clamp(Math.round(totalW * 0.24f), totalW < 380 ? 96 : 116, 158);
    }

    private int statColumnWidth(int totalW) {
        return clamp(Math.round(totalW * 0.22f), totalW < 380 ? 92 : 112, 140);
    }

    private void chip(GuiGraphics g, int x, int y, int w, int h, String label, int fill, int color) {
        Theme.panel(g, x, y, w, h, Math.min(7, h / 2), fill, Theme.BORDER);
        Theme.textCentered(g, font, label, x + w / 2, y + (h - font.lineHeight) / 2 + 1, color);
    }

    private void pill(GuiGraphics g, int x, int y, int w, int h, String label, int fill, int color) {
        Theme.panel(g, x, y, w, h, h / 2, fill, Theme.BORDER);
        Theme.text(g, font, Theme.ellipsize(font, label, w - 22), x + 9, y + (h - font.lineHeight) / 2 + 1, color);
    }

    private Item resolveItem(String itemId) {
        if (itemId != null && itemId.startsWith(TransferEdge.ITEM_PREFIX)) itemId = itemId.substring(TransferEdge.ITEM_PREFIX.length());
        if (itemId != null && itemId.startsWith(TransferEdge.FLUID_PREFIX)) return null;
        ResourceLocation id = ResourceLocation.tryParse(itemId);
        if (id == null) return null;
        Item item = BuiltInRegistries.ITEM.get(id);
        return item == Items.AIR ? null : item;
    }

    private boolean isFluidResource(String resourceId) {
        return resourceId != null && resourceId.startsWith(TransferEdge.FLUID_PREFIX);
    }

    private String shortResource(String resourceId) {
        if (resourceId == null) return "";
        String id = resourceId;
        if (id.startsWith(TransferEdge.ITEM_PREFIX)) id = id.substring(TransferEdge.ITEM_PREFIX.length());
        if (id.startsWith(TransferEdge.FLUID_PREFIX)) id = id.substring(TransferEdge.FLUID_PREFIX.length());
        int slash = id.indexOf(':');
        return slash >= 0 ? id.substring(slash + 1) : id;
    }

    private String formatRate(int value, boolean fluid) {
        return value + (fluid ? "mB/分" : "/分");
    }

    private String signedRate(int value, boolean fluid) {
        return (value >= 0 ? "+" : "") + formatRate(value, fluid);
    }

    private String formatCount(int value) {
        if (value >= 1000000) return (value / 1000000) + "m";
        if (value >= 10000) return (value / 1000) + "k";
        return String.valueOf(value);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
