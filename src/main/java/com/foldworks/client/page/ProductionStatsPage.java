package com.foldworks.client.page;

import com.foldworks.client.ClientProductionStatsCache;
import com.foldworks.client.ui.Page;
import com.foldworks.client.ui.Theme;
import com.foldworks.client.search.SearchSupport;
import com.foldworks.network.ProductionStatsSyncPacket;
import com.foldworks.network.RequestProductionStatsPacket;
import com.foldworks.network.UpdateProductionStatsPacket;
import com.foldworks.production.ProductionStatsStorage;
import com.foldworks.transfer.TransferEdge;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
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

    private static final int TOOLBAR_H = 22;
    private static final int HEADER_H = 14;
    private static final int ROW_H = 30;
    private static final int ROW_GAP = 2;
    private static final int GROUP_ROW_H = 18;

    private static final int PRODUCTION_COLOR = 0xFF18A8F2;
    private static final int CONSUMPTION_COLOR = 0xFFD9AD24;
    private static final int GRAPH_BG = 0xFFE9F1F8;
    private static final int GRAPH_GRID = 0x77FFFFFF;
    private static final int GRAPH_GRID_DARK = 0x2294AFC7;
    private static final String[] COMPACT_SUFFIXES = {"", "K", "M", "B", "T", "P", "E"};

    private String selectedGroupId = ProductionStatsStorage.DEFAULT_GROUP_ID;
    private String selectedScopeKind = ProductionStatsStorage.PRIVATE_SCOPE;
    private String selectedScopeId = "";
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
        requestSelectedStats();
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
            requestSelectedStats();
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
        int toolbarY = y + 3;
        if (w >= 300) Theme.text(g, font, "产率统计", x + 11, toolbarY + 4, Theme.TEXT);

        syncSelectedScopeFromCache();
        int scopeX = scopeButtonX();
        int scopeY = groupButtonY();
        int scopeW = scopeButtonWidth();
        boolean scopeHover = Theme.inside(mx, my, scopeX, scopeY, scopeW, 16);
        pill(g, scopeX, scopeY, scopeW, 16, selectedScopeName(),
                scopeHover ? Theme.PRIMARY_SOFT : Theme.SURFACE_ALT,
                scopeHover ? Theme.PRIMARY_PRESS : Theme.TEXT_MUTED);

        int groupX = groupButtonX();
        int groupY = groupButtonY();
        int groupW = groupButtonWidth();
        boolean groupHover = Theme.inside(mx, my, groupX, groupY, groupW, 16);
        pill(g, groupX, groupY, groupW, 16, selectedGroupName(), groupHover || groupDropdownOpen ? Theme.PRIMARY_SOFT : Theme.SURFACE_ALT,
                groupHover || groupDropdownOpen ? Theme.PRIMARY_PRESS : Theme.TEXT_MUTED);
        Theme.chevronDown(g, groupX + groupW - 11, groupY + 8, 6, groupHover || groupDropdownOpen ? Theme.PRIMARY_PRESS : Theme.TEXT_MUTED);

        int rangeX = rangeButtonX();
        int sortX = sortButtonX();
        int zeroX = zeroButtonX();
        if (showHideZeroControl()) chip(g, zeroX, toolbarY, 56, 16, hideZero ? "显示零" : "隐藏零", Theme.SURFACE_ALT, Theme.TEXT_MUTED);
        if (showSortControl()) chip(g, sortX, toolbarY, 62, 16, SORT_LABELS[sortMode], Theme.SURFACE_ALT, Theme.TEXT_MUTED);
        chip(g, rangeX, toolbarY, 62, 16, RANGES[rangeIndex] + "分钟", Theme.PRIMARY_SOFT, Theme.PRIMARY_PRESS);

        int searchX = groupX + groupW + 8;
        int searchRight = searchRightX();
        if (searchRight - searchX >= 66) {
            int searchW = Math.min(132, searchRight - searchX);
            Theme.panel(g, searchX, toolbarY, searchW, 16, 7, searchFocused ? 0xFFFFFFFF : Theme.SURFACE_SUNK,
                    searchFocused ? Theme.PRIMARY : Theme.BORDER);
            Theme.text(g, font, searchValue.isEmpty() ? "搜索资源" : Theme.ellipsize(font, searchValue, searchW - 14),
                    searchX + 7, toolbarY + 4, searchValue.isEmpty() ? Theme.TEXT_FAINT : Theme.TEXT);
        }
    }

    private void renderTable(GuiGraphics g, int mx, int my) {
        int innerX = x + 8;
        int innerW = w - 16;
        int headerY = y + TOOLBAR_H + 4;
        int listY = headerY + HEADER_H + 4;
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

        Theme.fillRound(g, hx, hy, hw, HEADER_H, 7, 0x99E5EDF5);
        Theme.vLine(g, trendX - gap / 2, hy + 2, HEADER_H - 4, 0x99C8D7E6);
        Theme.vLine(g, statX - gap / 2, hy + 2, HEADER_H - 4, 0x99C8D7E6);
        Theme.textCentered(g, font, "产物信息", hx + infoW / 2, hy + 3, Theme.TEXT_MUTED);
        Theme.textCentered(g, font, "产率/消耗率走势", trendX + trendW / 2, hy + 3, Theme.TEXT_MUTED);
        Theme.textCentered(g, font, "当前统计", statX + statW / 2, hy + 3, Theme.TEXT_MUTED);
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
        Theme.panel(g, rx, ry, rw, rh, 6, hover ? 0xFFFFFFFF : 0xFFF8FCFF, hover ? Theme.BORDER_STRONG : Theme.DIVIDER);

        int infoW = infoColumnWidth(rw);
        int statW = statColumnWidth(rw);
        int gap = columnGap(rw);
        int trendW = Math.max(72, rw - infoW - statW - gap * 2);
        int trendX = rx + infoW + gap;
        int statX = trendX + trendW + gap;
        boolean fluid = isFluidResource(row.itemId());

        renderResourceInfo(g, row, fluid, rx, ry, infoW, rh);
        drawTrendGraph(g, row.trendInput(), row.trendOutput(), row.trendNet(), trendX, ry + 4, trendW, rh - 8);
        renderCurrentStats(g, row, fluid, statX, ry, statW, rh);
    }

    private void renderResourceInfo(GuiGraphics g, ClientProductionStatsCache.ProductionRow row, boolean fluid,
                                    int ix, int iy, int iw, int ih) {
        boolean favorite = ClientProductionStatsCache.isFavoriteResource(row.itemId());
        textScaled(g, "★", ix + 7, iy + 11, favorite ? 0xFFD5A21B : Theme.TEXT_FAINT, 0.82f);
        int iconX = ix + 24;
        int iconY = iy + 8;
        g.pose().pushPose();
        g.pose().translate(iconX, iconY, 0);
        g.pose().scale(0.78f, 0.78f, 1f);
        if (fluid) g.renderItem(new ItemStack(Items.WATER_BUCKET), 0, 0);
        else {
            Item item = resolveItem(row.itemId());
            if (item != null) g.renderItem(new ItemStack(item), 0, 0);
        }
        g.pose().popPose();

        int textX = ix + 45;
        int textW = Math.max(36, iw - 50);
        textScaled(g, Theme.ellipsize(font, shortResource(row.itemId()), Math.round(textW / 0.82f)), textX, iy + 3, Theme.TEXT, 0.82f);
        textScaled(g, "库存 " + formatCount(row.currentCount()) + (fluid ? " mB" : ""), textX, iy + 16, Theme.TEXT_MUTED, 0.82f);
    }

    private void renderCurrentStats(GuiGraphics g, ClientProductionStatsCache.ProductionRow row, boolean fluid,
                                    int sx, int sy, int sw, int sh) {
        int labelX = sx + 8;
        int valueX = sx + sw - 8;
        int labelW = Math.max(Theme.styledWidth(font, "生产"), Theme.styledWidth(font, "消耗"));
        int valueW = Math.max(28, valueX - (labelX + labelW + 6));
        float statScale = 0.82f;
        String inputText = Theme.ellipsize(font, formatRate(row.inputRatePerMinute(), fluid), Math.round(valueW / statScale));
        String outputText = Theme.ellipsize(font, formatRate(row.outputRatePerMinute(), fluid), Math.round(valueW / statScale));
        String netText = Theme.ellipsize(font, signedRate(row.netRatePerMinute(), fluid), Math.round(Math.max(36, sw - 18) / statScale));
        textScaled(g, "生产", labelX, sy + 1, PRODUCTION_COLOR, statScale);
        textRightScaled(g, inputText, valueX, sy + 1, PRODUCTION_COLOR, statScale);
        textScaled(g, "消耗", labelX, sy + 11, CONSUMPTION_COLOR, statScale);
        textRightScaled(g, outputText, valueX, sy + 11, CONSUMPTION_COLOR, statScale);

        int netColor = row.netRatePerMinute() >= 0 ? Theme.SUCCESS : Theme.DANGER;
        int badgeFill = row.netRatePerMinute() >= 0 ? 0xFFE6F8F0 : Theme.DANGER_SOFT;
        int badgeW = Math.min(sw - 12, Math.max(46, Math.round(Theme.styledWidth(font, netText) * statScale) + 10));
        int netY = sy + sh - 10;
        Theme.fillRound(g, valueX - badgeW, netY - 1, badgeW, 10, 5, badgeFill);
        textRightScaled(g, netText, valueX - 5, netY, netColor, statScale);
    }

    private void drawTrendGraph(GuiGraphics g, List<Integer> input, List<Integer> output, List<Integer> net,
                                int gx, int gy, int gw, int gh) {
        Theme.fillRound(g, gx, gy, gw, gh, 4, GRAPH_BG);
        drawFineGrid(g, gx, gy, gw, gh);
        if (input.isEmpty() && output.isEmpty() && net.isEmpty()) return;

        int max = 1;
        for (int v : input) max = Math.max(max, Math.abs(v));
        for (int v : output) max = Math.max(max, Math.abs(v));
        for (int v : net) max = Math.max(max, Math.abs(v));

        int chartX = gx + 5;
        int chartY = gy + 4;
        int chartW = gw - 10;
        int chartH = gh - 9;
        int baseline = chartY + chartH - 1;
        Theme.hLine(g, chartX, baseline, chartW, 0x88D9AD24);
        fillSparkArea(g, input, chartX, chartY, chartW, chartH, max, baseline, 0x1A20A8F0);
        drawSparkline(g, output, chartX, chartY, chartW, chartH, max, 0xA8D9AD24, 0.85f, false);
        drawSparkline(g, input, chartX, chartY, chartW, chartH, max, PRODUCTION_COLOR, 1.15f, true);
    }

    private void drawFineGrid(GuiGraphics g, int x, int y, int w, int h) {
        for (int i = 1; i < 4; i++) {
            int gx = x + i * w / 5;
            g.fill(gx, y + 4, gx + 1, y + h - 4, GRAPH_GRID);
        }
        for (int i = 1; i < 3; i++) {
            int gy = y + i * h / 3;
            g.fill(x + 3, gy, x + w - 3, gy + 1, GRAPH_GRID_DARK);
        }
    }

    private void drawSparkline(GuiGraphics g, List<Integer> values, int x, int y, int w, int h, int max, int color,
                               float width, boolean highlightLast) {
        if (values.size() < 2 || w <= 0 || h <= 0) return;
        List<Integer> sampled = sampleTrend(values);
        float step = w / (float) (sampled.size() - 1);
        float prevX = x;
        float prevY = sparkY(sampled.get(0), y, h, max);
        float nextX = prevX;
        float nextY = prevY;
        for (int i = 1; i < sampled.size(); i++) {
            nextX = x + step * i;
            nextY = sparkY(sampled.get(i), y, h, max);
            Theme.line(g, prevX, prevY, nextX, nextY, width, color);
            prevX = nextX;
            prevY = nextY;
        }
        if (highlightLast) {
            Theme.fillRound(g, Math.round(nextX) - 2, Math.round(nextY) - 2, 4, 4, 2, 0xFFFFFFFF);
            Theme.fillRound(g, Math.round(nextX) - 1, Math.round(nextY) - 1, 2, 2, 1, color);
        }
    }

    private void fillSparkArea(GuiGraphics g, List<Integer> values, int x, int y, int w, int h, int max, int baseline, int color) {
        if (values.size() < 2 || w <= 0 || h <= 0) return;
        List<Integer> sampled = sampleTrend(values);
        float step = w / (float) (sampled.size() - 1);
        for (int i = 1; i < sampled.size(); i++) {
            int x0 = Math.round(x + step * (i - 1));
            int x1 = Math.round(x + step * i);
            int y0 = Math.round(sparkY(sampled.get(i - 1), y, h, max));
            int y1 = Math.round(sparkY(sampled.get(i), y, h, max));
            int top = Math.min(y0, y1);
            if (baseline > top) g.fill(x0, top, Math.max(x0 + 1, x1), baseline, color);
        }
    }

    private List<Integer> sampleTrend(List<Integer> values) {
        int maxPoints = 32;
        if (values.size() <= maxPoints) return values;
        List<Integer> sampled = new ArrayList<>(maxPoints);
        for (int i = 0; i < maxPoints; i++) {
            int index = Math.round(i * (values.size() - 1) / (float) (maxPoints - 1));
            sampled.add(values.get(index));
        }
        return sampled;
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
        if (Theme.inside(mx, my, scopeButtonX(), groupButtonY(), scopeButtonWidth(), 16)) {
            cycleScope();
            searchFocused = false;
            groupDropdownOpen = false;
            return true;
        }
        if (Theme.inside(mx, my, groupButtonX(), groupButtonY(), groupButtonWidth(), 16)) {
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
            if (Theme.inside(mx, my, searchX, toolbarY, searchW, 16)) {
                searchFocused = true;
                return true;
            }
        }
        searchFocused = false;
        if (Theme.inside(mx, my, rangeX, toolbarY, 62, 16)) {
            rangeIndex = (rangeIndex + 1) % RANGES.length;
            rowScroll = 0;
            return true;
        }
        if (showSortControl() && Theme.inside(mx, my, sortX, toolbarY, 62, 16)) {
            sortMode = (sortMode + 1) % SORT_LABELS.length;
            rowScroll = 0;
            return true;
        }
        if (showHideZeroControl() && Theme.inside(mx, my, zeroX, toolbarY, 56, 16)) {
            hideZero = !hideZero;
            rowScroll = 0;
            return true;
        }
        if (handleFavoriteClick(mx, my)) return true;
        return Theme.inside(mx, my, x, y, w, h);
    }

    private boolean handleFavoriteClick(double mx, double my) {
        int innerX = x + 8;
        int headerY = y + TOOLBAR_H + 4;
        int listY = headerY + HEADER_H + 4;
        int maxRows = Math.max(1, (h - (listY - y) - 8) / (ROW_H + ROW_GAP));
        List<ClientProductionStatsCache.ProductionRow> rows = filteredRows();
        rowScroll = clamp(rowScroll, 0, Math.max(0, rows.size() - maxRows));
        for (int i = 0; i < maxRows && i + rowScroll < rows.size(); i++) {
            int rowY = listY + i * (ROW_H + ROW_GAP);
            if (Theme.inside(mx, my, innerX + 3, rowY + 5, 20, 20)) {
                String id = rows.get(i + rowScroll).itemId();
                ClientProductionStatsCache.setFavoriteResourceLocal(id, !ClientProductionStatsCache.isFavoriteResource(id));
                PacketDistributor.sendToServer(statsPacket("TOGGLE_FAVORITE_RESOURCE", List.of(id)));
                rowScroll = 0;
                return true;
            }
        }
        return false;
    }

    private boolean handleGroupDropdownClick(double mx, double my) {
        if (Theme.inside(mx, my, groupButtonX(), groupButtonY(), groupButtonWidth(), 16)) {
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
                PacketDistributor.sendToServer(statsPacket("MERGE_GROUPS", values));
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
                PacketDistributor.sendToServer(statsPacket("DELETE_GROUP", List.of(selectedGroupId)));
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
            int listY = y + TOOLBAR_H + 4 + HEADER_H + 4;
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
            PacketDistributor.sendToServer(statsPacket("CREATE_GROUP", List.of(value, "false")));
        } else if (inputMode == InputMode.RENAME && !inputTargetGroupId.isBlank()) {
            PacketDistributor.sendToServer(statsPacket("RENAME_GROUP", List.of(inputTargetGroupId, value)));
        }
        inputMode = InputMode.NONE;
    }

    private List<ClientProductionStatsCache.ProductionRow> filteredRows() {
        String q = searchValue.trim().toLowerCase(Locale.ROOT);
        List<ClientProductionStatsCache.ProductionRow> rows = new ArrayList<>();
        for (ClientProductionStatsCache.ProductionRow row : ClientProductionStatsCache.rowsFor(selectedGroupId, RANGES[rangeIndex])) {
            if (hideZero && row.inputRatePerMinute() == 0 && row.outputRatePerMinute() == 0 && row.currentCount() == 0) continue;
            if (!q.isEmpty() && !SearchSupport.contains(row.itemId(), q) && !SearchSupport.contains(shortResource(row.itemId()), q)) continue;
            rows.add(row);
        }
        Comparator<ClientProductionStatsCache.ProductionRow> comparator = switch (sortMode) {
            case 1 -> Comparator.comparingInt(ClientProductionStatsCache.ProductionRow::inputRatePerMinute).reversed();
            case 2 -> Comparator.comparingInt(ClientProductionStatsCache.ProductionRow::outputRatePerMinute).reversed();
            case 3 -> Comparator.comparing(row -> shortResource(row.itemId()));
            case 4 -> Comparator.comparingInt(ClientProductionStatsCache.ProductionRow::currentCount).reversed();
            default -> Comparator.comparingInt((ClientProductionStatsCache.ProductionRow row) -> Math.abs(row.netRatePerMinute())).reversed();
        };
        rows.sort((a, b) -> {
            boolean af = ClientProductionStatsCache.isFavoriteResource(a.itemId());
            boolean bf = ClientProductionStatsCache.isFavoriteResource(b.itemId());
            if (af != bf) return af ? -1 : 1;
            return comparator.compare(a, b);
        });
        return rows;
    }

    private void ensureSelectedGroup() {
        if (ClientProductionStatsCache.group(selectedGroupId) == null) selectedGroupId = ClientProductionStatsCache.firstGroupId();
    }

    private void requestSelectedStats() {
        PacketDistributor.sendToServer(new RequestProductionStatsPacket(selectedScopeKind, selectedScopeId));
    }

    private UpdateProductionStatsPacket statsPacket(String action, List<String> values) {
        return new UpdateProductionStatsPacket(selectedScopeKind, selectedScopeId, action, values);
    }

    private void syncSelectedScopeFromCache() {
        String kind = ClientProductionStatsCache.scopeKind();
        String id = ClientProductionStatsCache.scopeId();
        if (kind != null && !kind.isBlank()) selectedScopeKind = kind;
        selectedScopeId = id == null ? "" : id;
    }

    private void cycleScope() {
        List<ProductionStatsSyncPacket.ScopeData> options = ClientProductionStatsCache.scopeOptions();
        if (options.isEmpty()) return;
        int current = 0;
        for (int i = 0; i < options.size(); i++) {
            ProductionStatsSyncPacket.ScopeData option = options.get(i);
            if (option.kind().equals(selectedScopeKind) && option.id().equals(selectedScopeId)) {
                current = i;
                break;
            }
        }
        ProductionStatsSyncPacket.ScopeData next = options.get((current + 1) % options.size());
        selectedScopeKind = next.kind();
        selectedScopeId = next.id();
        selectedGroupId = ProductionStatsStorage.DEFAULT_GROUP_ID;
        rowScroll = 0;
        groupScroll = 0;
        requestSelectedStats();
    }

    private String selectedScopeName() {
        for (ProductionStatsSyncPacket.ScopeData option : ClientProductionStatsCache.scopeOptions()) {
            if (option.kind().equals(selectedScopeKind) && option.id().equals(selectedScopeId)) {
                return option.label();
            }
        }
        return "个人";
    }

    private int scopeButtonX() {
        return w < 300 ? x + 10 : x + 73;
    }

    private int scopeButtonWidth() {
        int rightLimit = rangeButtonX() - 8;
        int max = Math.max(58, rightLimit - scopeButtonX());
        return Math.max(58, Math.min(Math.min(104, max), Theme.styledWidth(font, selectedScopeName()) + 20));
    }

    private int groupButtonX() {
        return scopeButtonX() + scopeButtonWidth() + 6;
    }

    private int groupButtonY() {
        return y + 5;
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
        return y + 28;
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
        return clamp(Math.round(totalW * 0.24f), totalW < 380 ? 96 : 116, 150);
    }

    private void textScaled(GuiGraphics g, String text, int x, int y, int color, float scale) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(scale, scale, 1f);
        Theme.text(g, font, text, 0, 0, color);
        g.pose().popPose();
    }

    private void textRightScaled(GuiGraphics g, String text, int rightX, int y, int color, float scale) {
        g.pose().pushPose();
        g.pose().translate(rightX, y, 0);
        g.pose().scale(scale, scale, 1f);
        Theme.textRight(g, font, text, 0, 0, color);
        g.pose().popPose();
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

    private Fluid resolveFluid(String resourceId) {
        if (resourceId != null && resourceId.startsWith(TransferEdge.FLUID_PREFIX)) resourceId = resourceId.substring(TransferEdge.FLUID_PREFIX.length());
        ResourceLocation id = ResourceLocation.tryParse(resourceId);
        if (id == null) return null;
        Fluid fluid = BuiltInRegistries.FLUID.get(id);
        return fluid == Fluids.EMPTY ? null : fluid;
    }

    private boolean isFluidResource(String resourceId) {
        return resourceId != null && resourceId.startsWith(TransferEdge.FLUID_PREFIX);
    }

    private String shortResource(String resourceId) {
        if (resourceId == null) return "";
        if (resourceId.startsWith(TransferEdge.FLUID_PREFIX)) {
            Fluid fluid = resolveFluid(resourceId);
            if (fluid != null) return new FluidStack(fluid, 1).getHoverName().getString();
        } else {
            Item item = resolveItem(resourceId);
            if (item != null) return new ItemStack(item).getHoverName().getString();
        }
        String id = resourceId;
        if (id.startsWith(TransferEdge.ITEM_PREFIX)) id = id.substring(TransferEdge.ITEM_PREFIX.length());
        if (id.startsWith(TransferEdge.FLUID_PREFIX)) id = id.substring(TransferEdge.FLUID_PREFIX.length());
        int slash = id.indexOf(':');
        return slash >= 0 ? id.substring(slash + 1) : id;
    }

    private String formatRate(int value, boolean fluid) {
        return formatCompact(value) + (fluid ? "mB/分" : "/分");
    }

    private String signedRate(int value, boolean fluid) {
        return (value >= 0 ? "+" : "") + formatRate(value, fluid);
    }

    private String formatCount(int value) {
        return formatCompact(value);
    }

    private String formatCompact(int value) {
        long abs = Math.abs((long) value);
        int suffixIndex = 0;
        double scaled = abs;
        while (scaled >= 1000.0 && suffixIndex < COMPACT_SUFFIXES.length - 1) {
            scaled /= 1000.0;
            suffixIndex++;
        }
        if (suffixIndex == 0) return String.valueOf(value);

        String sign = value < 0 ? "-" : "";
        String number;
        if (scaled >= 100.0 || Math.abs(scaled - Math.rint(scaled)) < 0.0001) {
            number = String.format(Locale.ROOT, "%.0f", scaled);
        } else {
            number = String.format(Locale.ROOT, "%.1f", scaled);
        }
        return sign + number + COMPACT_SUFFIXES[suffixIndex];
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
