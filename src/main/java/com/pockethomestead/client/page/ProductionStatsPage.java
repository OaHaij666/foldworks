package com.pockethomestead.client.page;

import com.pockethomestead.client.ClientProductionStatsCache;
import com.pockethomestead.client.ui.Page;
import com.pockethomestead.client.ui.Theme;
import com.pockethomestead.network.ProductionStatsSyncPacket;
import com.pockethomestead.network.RequestProductionStatsPacket;
import com.pockethomestead.network.UpdateProductionStatsPacket;
import com.pockethomestead.production.ProductionStatsStorage;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class ProductionStatsPage extends Page {
    private static final int[] RANGES = {1, 5, 10, 30, 60};
    private static final String[] SORT_LABELS = {"净产率", "生产", "消耗", "名称", "库存"};

    private String selectedGroupId = ProductionStatsStorage.DEFAULT_GROUP_ID;
    private int rangeIndex = 2;
    private int sortMode;
    private int rowScroll;
    private boolean hideZero;
    private String searchValue = "";
    private boolean searchFocused;
    private int syncTicker;
    private InputMode inputMode = InputMode.NONE;
    private String inputValue = "";
    private String inputTargetGroupId = "";

    private enum InputMode { NONE, CREATE_ATOMIC, CREATE_AGGREGATE, RENAME }

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
        int groupW = Math.max(104, Math.min(138, w / 3));
        int mainX = x + groupW + 8;
        int mainW = w - groupW - 8;
        renderGroupPanel(g, mouseX, mouseY, x, y, groupW, h);
        renderStatsPanel(g, mouseX, mouseY, mainX, y, mainW, h);
        if (inputMode != InputMode.NONE) renderInputOverlay(g);
    }

    private void renderGroupPanel(GuiGraphics g, int mx, int my, int gx, int gy, int gw, int gh) {
        Theme.panel(g, gx, gy, gw, gh, Theme.RADIUS + 1, Theme.SURFACE_ALT, Theme.BORDER);
        Theme.text(g, font, "分组", gx + 9, gy + 8, Theme.TEXT);
        chip(g, gx + 8, gy + 24, 46, 16, "+ 原子", Theme.PRIMARY_SOFT, Theme.PRIMARY_PRESS);
        chip(g, gx + 58, gy + 24, 46, 16, "+ 聚合", Theme.SURFACE, Theme.PRIMARY_PRESS);

        int rowY = gy + 48;
        for (ProductionStatsSyncPacket.GroupData group : ClientProductionStatsCache.groups()) {
            boolean selected = group.id().equals(selectedGroupId);
            boolean hover = Theme.inside(mx, my, gx + 6, rowY, gw - 12, 19);
            int fill = selected ? Theme.PRIMARY_SOFT : hover ? Theme.SURFACE : 0x00000000;
            if (selected || hover) Theme.fillRound(g, gx + 6, rowY, gw - 12, 19, 5, fill);
            int color = selected ? Theme.PRIMARY_PRESS : Theme.TEXT;
            Theme.text(g, font, group.aggregate() ? "◇" : "●", gx + 12, rowY + 5, group.aggregate() ? Theme.PRIMARY_PRESS : Theme.SUCCESS);
            Theme.text(g, font, Theme.ellipsize(font, group.name(), gw - 42), gx + 28, rowY + 5, color);
            rowY += 22;
        }

        int actionY = gy + gh - 44;
        chip(g, gx + 8, actionY, 48, 16, "重命名", Theme.SURFACE, Theme.PRIMARY_PRESS);
        chip(g, gx + 62, actionY, 38, 16, "删除", Theme.SURFACE, Theme.DANGER);

        ProductionStatsSyncPacket.GroupData selected = ClientProductionStatsCache.group(selectedGroupId);
        if (selected != null && selected.aggregate()) {
            Theme.text(g, font, "包含", gx + 9, actionY - 74, Theme.TEXT_MUTED);
            int cy = actionY - 58;
            for (ProductionStatsSyncPacket.GroupData child : ClientProductionStatsCache.groups()) {
                if (child.id().equals(selected.id())) continue;
                boolean checked = ClientProductionStatsCache.isChild(selected.id(), child.id());
                Theme.text(g, font, checked ? "☑" : "☐", gx + 12, cy, checked ? Theme.PRIMARY_PRESS : Theme.TEXT_FAINT);
                Theme.text(g, font, Theme.ellipsize(font, child.name(), gw - 42), gx + 30, cy, Theme.TEXT_MUTED);
                cy += 14;
                if (cy > actionY - 4) break;
            }
        }
    }

    private void renderStatsPanel(GuiGraphics g, int mx, int my, int px, int py, int pw, int ph) {
        Theme.panel(g, px, py, pw, ph, Theme.RADIUS + 1, Theme.SURFACE, Theme.BORDER);
        ProductionStatsSyncPacket.GroupData selected = ClientProductionStatsCache.group(selectedGroupId);
        String title = selected == null ? "产率统计" : selected.name();
        Theme.text(g, font, title, px + 10, py + 9, Theme.TEXT);
        chip(g, px + pw - 72, py + 7, 62, 17, RANGES[rangeIndex] + "分钟", Theme.PRIMARY_SOFT, Theme.PRIMARY_PRESS);
        chip(g, px + pw - 140, py + 7, 62, 17, SORT_LABELS[sortMode], Theme.SURFACE_ALT, Theme.TEXT_MUTED);
        chip(g, px + pw - 202, py + 7, 56, 17, hideZero ? "显示零" : "隐藏零", Theme.SURFACE_ALT, Theme.TEXT_MUTED);

        int searchW = Math.min(104, Math.max(60, pw - 228));
        Theme.panel(g, px + 10, py + 29, searchW, 18, 5, searchFocused ? 0xFFFFFFFF : Theme.SURFACE_SUNK, searchFocused ? Theme.PRIMARY : Theme.BORDER);
        Theme.text(g, font, searchValue.isEmpty() ? "搜索方块" : searchValue, px + 16, py + 34, searchValue.isEmpty() ? Theme.TEXT_FAINT : Theme.TEXT);

        List<ClientProductionStatsCache.ProductionRow> rows = filteredRows();
        int listY = py + 54;
        int rowH = 44;
        int maxRows = Math.max(1, (ph - 64) / rowH);
        rowScroll = Math.max(0, Math.min(rowScroll, Math.max(0, rows.size() - maxRows)));
        if (rows.isEmpty()) {
            Theme.text(g, font, "暂无产率数据", px + 12, listY + 12, Theme.TEXT_MUTED);
            Theme.text(g, font, "箱子加入统计后，库存变化会自动出现在这里。", px + 12, listY + 28, Theme.TEXT_FAINT);
            return;
        }
        for (int i = 0; i < maxRows && i + rowScroll < rows.size(); i++) {
            renderRow(g, rows.get(i + rowScroll), px + 8, listY + i * rowH, pw - 16, rowH - 5);
        }
    }

    private void renderRow(GuiGraphics g, ClientProductionStatsCache.ProductionRow row, int rx, int ry, int rw, int rh) {
        Theme.panel(g, rx, ry, rw, rh, 6, 0xFFF8FCFF, Theme.DIVIDER);
        Item item = resolveItem(row.itemId());
        if (item != null) g.renderItem(new ItemStack(item), rx + 8, ry + 9);
        Theme.text(g, font, Theme.ellipsize(font, shortItem(row.itemId()), 82), rx + 30, ry + 7, Theme.TEXT);
        Theme.text(g, font, "库存 " + formatCount(row.currentCount()), rx + 30, ry + 22, Theme.TEXT_MUTED);

        int chartX = rx + Math.min(124, rw / 3);
        int chartW = Math.max(54, rw - 250);
        int chartY = ry + 7;
        Theme.fillRound(g, chartX, chartY, chartW, 26, 4, Theme.SURFACE_SUNK);
        drawTrend(g, row.trendNet(), chartX + 3, chartY + 3, chartW - 6, 20);

        int statX = rx + rw - 116;
        Theme.text(g, font, "生产", statX, ry + 7, Theme.PRIMARY_PRESS);
        Theme.textRight(g, font, formatRate(row.inputRatePerMinute()), rx + rw - 8, ry + 7, Theme.PRIMARY_PRESS);
        Theme.text(g, font, "消耗", statX, ry + 21, 0xFFE0B43F);
        Theme.textRight(g, font, formatRate(row.outputRatePerMinute()), rx + rw - 8, ry + 21, 0xFFE0B43F);
        int netColor = row.netRatePerMinute() >= 0 ? Theme.SUCCESS : Theme.DANGER;
        Theme.fillRound(g, rx + rw - 148, ry + 12, 20, 12, 6, row.netRatePerMinute() >= 0 ? 0xFFE5F7EF : Theme.DANGER_SOFT);
        Theme.text(g, font, (row.netRatePerMinute() >= 0 ? "+" : "") + row.netRatePerMinute(), rx + rw - 145, ry + 15, netColor);
    }

    private void drawTrend(GuiGraphics g, List<Integer> trend, int x, int y, int w, int h) {
        if (trend.isEmpty() || w <= 0) return;
        int max = 1;
        for (int value : trend) max = Math.max(max, Math.abs(value));
        int mid = y + h / 2;
        Theme.hLine(g, x, mid, w, 0x88D4E4F5);
        int barW = Math.max(1, w / trend.size());
        for (int i = 0; i < trend.size(); i++) {
            int value = trend.get(i);
            int bh = Math.max(1, Math.round(Math.abs(value) * (h / 2f - 1) / max));
            int bx = x + i * barW;
            int color = value >= 0 ? 0xAA7CB3E8 : 0xAADDCC43;
            if (value >= 0) g.fill(bx, mid - bh, bx + Math.max(1, barW - 1), mid, color);
            else g.fill(bx, mid, bx + Math.max(1, barW - 1), mid + bh, color);
        }
    }

    private void renderInputOverlay(GuiGraphics g) {
        g.fill(0, 0, mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight(), Theme.SCRIM);
        int bw = 190;
        int bh = 76;
        int bx = x + (w - bw) / 2;
        int by = y + (h - bh) / 2;
        Theme.panel(g, bx, by, bw, bh, Theme.RADIUS + 1, Theme.SURFACE, Theme.BORDER_STRONG);
        String title = inputMode == InputMode.RENAME ? "重命名分组" : inputMode == InputMode.CREATE_AGGREGATE ? "新建聚合组" : "新建原子组";
        Theme.text(g, font, title, bx + 10, by + 10, Theme.TEXT);
        Theme.panel(g, bx + 10, by + 28, bw - 20, 18, 5, 0xFFFFFFFF, Theme.PRIMARY);
        Theme.text(g, font, inputValue + ((syncTicker / 10) % 2 == 0 ? "_" : ""), bx + 16, by + 33, Theme.TEXT);
        chip(g, bx + 10, by + 54, 52, 16, "保存", Theme.PRIMARY_SOFT, Theme.PRIMARY_PRESS);
        chip(g, bx + 70, by + 54, 52, 16, "取消", Theme.SURFACE_ALT, Theme.TEXT_MUTED);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return false;
        if (inputMode != InputMode.NONE) return handleInputClick(mx, my);
        int groupW = Math.max(104, Math.min(138, w / 3));
        if (Theme.inside(mx, my, x, y, groupW, h)) return handleGroupClick(mx, my, groupW);
        return handleStatsClick(mx, my, x + groupW + 8, y, w - groupW - 8);
    }

    private boolean handleGroupClick(double mx, double my, int groupW) {
        if (Theme.inside(mx, my, x + 8, y + 24, 46, 16)) {
            openInput(InputMode.CREATE_ATOMIC, "新原子组", "");
            return true;
        }
        if (Theme.inside(mx, my, x + 58, y + 24, 46, 16)) {
            openInput(InputMode.CREATE_AGGREGATE, "新聚合组", "");
            return true;
        }
        int rowY = y + 48;
        for (ProductionStatsSyncPacket.GroupData group : ClientProductionStatsCache.groups()) {
            if (Theme.inside(mx, my, x + 6, rowY, groupW - 12, 19)) {
                selectedGroupId = group.id();
                rowScroll = 0;
                return true;
            }
            rowY += 22;
        }
        int actionY = y + h - 44;
        if (Theme.inside(mx, my, x + 8, actionY, 48, 16)) {
            ProductionStatsSyncPacket.GroupData group = ClientProductionStatsCache.group(selectedGroupId);
            if (group != null) openInput(InputMode.RENAME, group.name(), group.id());
            return true;
        }
        if (Theme.inside(mx, my, x + 62, actionY, 38, 16)) {
            if (!ProductionStatsStorage.DEFAULT_GROUP_ID.equals(selectedGroupId)) {
                PacketDistributor.sendToServer(new UpdateProductionStatsPacket("DELETE_GROUP", List.of(selectedGroupId)));
                selectedGroupId = ProductionStatsStorage.DEFAULT_GROUP_ID;
            }
            return true;
        }
        ProductionStatsSyncPacket.GroupData selected = ClientProductionStatsCache.group(selectedGroupId);
        if (selected != null && selected.aggregate()) {
            int cy = actionY - 58;
            for (ProductionStatsSyncPacket.GroupData child : ClientProductionStatsCache.groups()) {
                if (child.id().equals(selected.id())) continue;
                if (Theme.inside(mx, my, x + 8, cy - 2, groupW - 16, 14)) {
                    PacketDistributor.sendToServer(new UpdateProductionStatsPacket("TOGGLE_CHILD", List.of(selected.id(), child.id())));
                    return true;
                }
                cy += 14;
                if (cy > actionY - 4) break;
            }
        }
        return true;
    }

    private boolean handleStatsClick(double mx, double my, int px, int py, int pw) {
        int searchW = Math.min(104, Math.max(60, pw - 228));
        if (Theme.inside(mx, my, px + 10, py + 29, searchW, 18)) {
            searchFocused = true;
            return true;
        }
        searchFocused = false;
        if (Theme.inside(mx, my, px + pw - 72, py + 7, 62, 17)) {
            rangeIndex = (rangeIndex + 1) % RANGES.length;
            rowScroll = 0;
            return true;
        }
        if (Theme.inside(mx, my, px + pw - 140, py + 7, 62, 17)) {
            sortMode = (sortMode + 1) % SORT_LABELS.length;
            rowScroll = 0;
            return true;
        }
        if (Theme.inside(mx, my, px + pw - 202, py + 7, 56, 17)) {
            hideZero = !hideZero;
            rowScroll = 0;
            return true;
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
        int groupW = Math.max(104, Math.min(138, w / 3));
        int mainX = x + groupW + 8;
        if (Theme.inside(mx, my, mainX, y, w - groupW - 8, h)) {
            List<ClientProductionStatsCache.ProductionRow> rows = filteredRows();
            int maxRows = Math.max(1, (h - 64) / 44);
            rowScroll = Math.max(0, Math.min(Math.max(0, rows.size() - maxRows), rowScroll - (int) Math.signum(sy)));
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

    private void openInput(InputMode mode, String value, String target) {
        inputMode = mode;
        inputValue = value == null ? "" : value;
        inputTargetGroupId = target == null ? "" : target;
        searchFocused = false;
    }

    private void commitInput() {
        String value = inputValue.trim();
        if (value.isEmpty()) value = inputMode == InputMode.CREATE_AGGREGATE ? "新聚合组" : "新原子组";
        if (inputMode == InputMode.CREATE_ATOMIC) {
            PacketDistributor.sendToServer(new UpdateProductionStatsPacket("CREATE_GROUP", List.of(value, "false")));
        } else if (inputMode == InputMode.CREATE_AGGREGATE) {
            PacketDistributor.sendToServer(new UpdateProductionStatsPacket("CREATE_GROUP", List.of(value, "true")));
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
            if (!q.isEmpty() && !row.itemId().toLowerCase(Locale.ROOT).contains(q) && !shortItem(row.itemId()).toLowerCase(Locale.ROOT).contains(q)) continue;
            rows.add(row);
        }
        Comparator<ClientProductionStatsCache.ProductionRow> comparator = switch (sortMode) {
            case 1 -> Comparator.comparingInt(ClientProductionStatsCache.ProductionRow::inputRatePerMinute).reversed();
            case 2 -> Comparator.comparingInt(ClientProductionStatsCache.ProductionRow::outputRatePerMinute).reversed();
            case 3 -> Comparator.comparing(row -> shortItem(row.itemId()));
            case 4 -> Comparator.comparingInt(ClientProductionStatsCache.ProductionRow::currentCount).reversed();
            default -> Comparator.comparingInt((ClientProductionStatsCache.ProductionRow row) -> Math.abs(row.netRatePerMinute())).reversed();
        };
        rows.sort(comparator);
        return rows;
    }

    private void ensureSelectedGroup() {
        if (ClientProductionStatsCache.group(selectedGroupId) == null) selectedGroupId = ClientProductionStatsCache.firstGroupId();
    }

    private void chip(GuiGraphics g, int x, int y, int w, int h, String label, int fill, int color) {
        Theme.panel(g, x, y, w, h, Math.min(7, h / 2), fill, Theme.BORDER);
        Theme.text(g, font, label, x + 7, y + 4, color);
    }

    private Item resolveItem(String itemId) {
        ResourceLocation id = ResourceLocation.tryParse(itemId);
        if (id == null) return null;
        Item item = BuiltInRegistries.ITEM.get(id);
        return item == Items.AIR ? null : item;
    }

    private String shortItem(String itemId) {
        int slash = itemId.indexOf(':');
        return slash >= 0 ? itemId.substring(slash + 1) : itemId;
    }

    private String formatRate(int value) {
        return value + "/分";
    }

    private String formatCount(int value) {
        if (value >= 1000000) return (value / 1000000) + "m";
        if (value >= 10000) return (value / 1000) + "k";
        return String.valueOf(value);
    }
}
