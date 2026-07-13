package com.foldworks.client.page;

import com.foldworks.client.ClientSpaceArchiveTransfer;
import com.foldworks.client.ClientSpaceCache;
import com.foldworks.client.ui.Page;
import com.foldworks.client.ui.Theme;
import com.foldworks.client.ui.widget.UiButton;
import com.foldworks.client.ui.widget.UiScrollList;
import com.foldworks.network.RequestSpaceListPayload;
import com.foldworks.network.SpaceInfo;
import com.foldworks.space.SpacePermission;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.neoforge.network.PacketDistributor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MigrationPage extends Page {
    private static final int SERVER_ROW_H = 54;
    private static final int ARCHIVE_ROW_H = 48;
    private static final int ROW_GAP = 6;
    private static final int ACTION_W = 46;
    private static final int ACTION_H = 18;
    private static final int LOCAL_ARCHIVE_REFRESH_TICKS = 100;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private record ArchiveView(Path path, long size, long modified, int expCost, String error) {}

    private final List<SpaceInfo> spaces = new ArrayList<>();
    private final List<ArchiveView> archives = new ArrayList<>();
    private final Map<Path, ArchiveView> archiveInfoCache = new HashMap<>();
    private UiScrollList<SpaceInfo> serverList;
    private UiScrollList<ArchiveView> archiveList;
    private UiButton refreshButton;
    private UiButton openDirButton;
    private int refreshTicker;
    private int archiveRefreshTicker;
    private boolean openDirButtonVisible;

    @Override
    public String id() {
        return "migration";
    }

    @Override
    public String navTitle() {
        return "迁移";
    }

    @Override
    public String navIcon() {
        return "⇄";
    }

    @Override
    public void onEnter() {
        buildWidgets();
        PacketDistributor.sendToServer(new RequestSpaceListPayload());
        refreshTicker = 0;
        archiveRefreshTicker = 0;
        refreshSpaces();
        refreshArchives();
    }

    @Override
    public void tick() {
        ClientSpaceArchiveTransfer.tickUpload();
        refreshTicker++;
        if (refreshTicker >= 40) {
            refreshTicker = 0;
            refreshSpaces();
        }
        archiveRefreshTicker++;
        if (archiveRefreshTicker >= LOCAL_ARCHIVE_REFRESH_TICKS) {
            archiveRefreshTicker = 0;
            refreshArchives();
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        buildWidgets();

        int pad = Theme.PAD;
        int innerX = x + pad;
        int innerW = w - pad * 2;
        int warningH = 48;
        int footerH = 30;
        int warningY = y + pad;
        int footerY = y + h - footerH;

        renderWarning(g, innerX, warningY, innerW, warningH);

        int bodyY = warningY + warningH + 8;
        int bodyH = footerY - bodyY - 6;
        if (bodyH < 90) {
            Theme.textCentered(g, font, "窗口太小，无法显示迁移列表", x + w / 2, y + h / 2, Theme.TEXT_FAINT);
            renderFooter(g, mouseX, mouseY, partialTick, innerX, footerY, innerW);
            return;
        }

        serverList.setItems(spaces);
        archiveList.setItems(archives);

        if (innerW >= 390) {
            int colGap = 8;
            int colW = (innerW - colGap) / 2;
            renderServerColumn(g, mouseX, mouseY, partialTick, innerX, bodyY, colW, bodyH);
            renderArchiveColumn(g, mouseX, mouseY, partialTick, innerX + colW + colGap, bodyY, innerW - colW - colGap, bodyH);
        } else {
            int firstH = (bodyH - 24) / 2;
            renderServerColumn(g, mouseX, mouseY, partialTick, innerX, bodyY, innerW, firstH);
            renderArchiveColumn(g, mouseX, mouseY, partialTick, innerX, bodyY + firstH + 24, innerW, bodyH - firstH - 24);
        }

        renderFooter(g, mouseX, mouseY, partialTick, innerX, footerY, innerW);
    }

    private void buildWidgets() {
        if (serverList != null) return;
        serverList = new UiScrollList<>(SERVER_ROW_H, ROW_GAP, this::renderServerRow, this::onServerRowClick);
        archiveList = new UiScrollList<>(ARCHIVE_ROW_H, ROW_GAP, this::renderArchiveRow, this::onArchiveRowClick);
        refreshButton = new UiButton("刷新", UiButton.Variant.SECONDARY).onClick(() -> {
            PacketDistributor.sendToServer(new RequestSpaceListPayload());
            refreshSpaces();
            refreshArchives();
        });
        openDirButton = new UiButton("目录", UiButton.Variant.SECONDARY).onClick(this::openArchiveDirectory);
    }

    private void refreshSpaces() {
        List<SpaceInfo> fresh = ClientSpaceCache.get().stream()
                .sorted(Comparator.comparing(SpaceInfo::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
        if (fresh.size() != spaces.size() || !spaces.containsAll(fresh)) {
            spaces.clear();
            spaces.addAll(fresh);
        }
    }

    private void refreshArchives() {
        List<ArchiveView> fresh = ClientSpaceArchiveTransfer.localArchives().stream()
                .map(this::archiveView)
                .toList();
        if (fresh.size() != archives.size() || !archives.containsAll(fresh)) {
            archives.clear();
            archives.addAll(fresh);
        }
        archiveInfoCache.keySet().removeIf(path -> fresh.stream()
                .noneMatch(view -> view.path().toAbsolutePath().normalize().equals(path)));
    }

    private ArchiveView archiveView(Path path) {
        Path key = path.toAbsolutePath().normalize();
        long size = fileSize(path);
        long modified = modifiedTime(path);
        ArchiveView cached = archiveInfoCache.get(key);
        if (cached != null && cached.size() == size && cached.modified() == modified) return cached;
        int expCost = -1;
        String error = "";
        try {
            expCost = ClientSpaceArchiveTransfer.estimateUploadExpCost(path);
        } catch (IOException e) {
            error = e.getMessage() == null ? "无法读取计费信息" : e.getMessage();
        }
        ArchiveView view = new ArchiveView(path, size, modified, expCost, error);
        archiveInfoCache.put(key, view);
        return view;
    }

    private void renderWarning(GuiGraphics g, int wx, int wy, int ww, int wh) {
        Theme.panel(g, wx, wy, ww, wh, Theme.RADIUS, 0xFFFFF7E6, 0xFFE2BA6C);
        Theme.text(g, font, "实验性空间迁移", wx + 10, wy + 8, 0xFF7A5316);
        drawWrapped(g,
                "不稳定，请谨慎使用。下载会保存空间包；上传只会在服务器中新建空间。导出时只携带绑定该空间的空间图。",
                wx + 10, wy + 23, ww - 20, 0xFF8A6A32, 2);
    }

    private void renderServerColumn(GuiGraphics g, int mouseX, int mouseY, float partialTick,
                                    int cx, int cy, int cw, int ch) {
        renderColumnHeader(g, "服务器空间", spaces.size(), cx, cy, cw);
        int listY = cy + 18;
        int listH = ch - 18;
        serverList.bounds(cx, listY, cw, listH);
        if (spaces.isEmpty()) drawEmpty(g, cx, listY, cw, listH, "暂无可访问空间");
        else serverList.render(g, mouseX, mouseY, partialTick);
    }

    private void renderArchiveColumn(GuiGraphics g, int mouseX, int mouseY, float partialTick,
                                     int cx, int cy, int cw, int ch) {
        renderArchiveColumnHeader(g, mouseX, mouseY, partialTick, cx, cy, cw);
        int listY = cy + 18;
        int listH = ch - 18;
        archiveList.bounds(cx, listY, cw, listH);
        if (archives.isEmpty()) drawEmpty(g, cx, listY, cw, listH, "本地目录中没有 .phspace 文件");
        else archiveList.render(g, mouseX, mouseY, partialTick);
    }

    private void renderColumnHeader(GuiGraphics g, String title, int count, int hx, int hy, int hw) {
        Theme.text(g, font, title, hx + 2, hy + 4, Theme.TEXT);
        Theme.textRight(g, font, String.valueOf(count), hx + hw - 2, hy + 4, Theme.TEXT_FAINT);
    }

    private void renderArchiveColumnHeader(GuiGraphics g, int mouseX, int mouseY, float partialTick, int hx, int hy, int hw) {
        Theme.text(g, font, "本地空间包", hx + 2, hy + 4, Theme.TEXT);
        Theme.textRight(g, font, String.valueOf(archives.size()), hx + hw - 2, hy + 4, Theme.TEXT_FAINT);
        int buttonW = 40;
        int buttonX = hx + hw - buttonW - 14;
        openDirButtonVisible = buttonX > hx + 68;
        if (openDirButtonVisible) {
            openDirButton.bounds(buttonX, hy + 1, buttonW, 16).render(g, mouseX, mouseY, partialTick);
        } else {
            openDirButton.bounds(-1000, -1000, 1, 1);
        }
    }

    private void renderServerRow(GuiGraphics g, SpaceInfo space, int rx, int ry, int rw, int rh,
                                 int mouseX, int mouseY, boolean hovered) {
        Theme.panel(g, rx, ry, rw, rh, Theme.RADIUS, hovered ? 0xFFFFFFFF : Theme.SURFACE_ALT,
                hovered ? Theme.BORDER_STRONG : Theme.BORDER);
        int bx = rx + rw - ACTION_W - 8;
        int by = ry + rh - ACTION_H - 8;
        int textRight = bx - 7;

        Theme.text(g, font, Theme.ellipsize(font, space.name(), Math.max(30, textRight - rx - 10)), rx + 10, ry + 8, Theme.TEXT);
        String meta = modeLabel(space.mode()) + " · 权限 " + levelLabel(selfLevel(space));
        Theme.text(g, font, Theme.ellipsize(font, meta, Math.max(30, textRight - rx - 10)), rx + 10, ry + 23, Theme.TEXT_MUTED);
        Theme.text(g, font, Theme.ellipsize(font, "Owner " + space.ownerId().toString().substring(0, 8), Math.max(30, textRight - rx - 10)),
                rx + 10, ry + 38, Theme.TEXT_FAINT);

        drawActionButton(g, bx, by, ACTION_W, ACTION_H, "下载", true, Theme.inside(mouseX, mouseY, bx, by, ACTION_W, ACTION_H));
    }

    private void renderArchiveRow(GuiGraphics g, ArchiveView archive, int rx, int ry, int rw, int rh,
                                  int mouseX, int mouseY, boolean hovered) {
        Theme.panel(g, rx, ry, rw, rh, Theme.RADIUS, hovered ? 0xFFFFFFFF : Theme.SURFACE_ALT,
                hovered ? Theme.BORDER_STRONG : Theme.BORDER);
        int bx = rx + rw - ACTION_W - 8;
        int by = ry + rh - ACTION_H - 8;
        int textRight = bx - 7;

        Path path = archive.path();
        String name = path.getFileName() == null ? path.toString() : path.getFileName().toString();
        Theme.text(g, font, Theme.ellipsize(font, name, Math.max(30, textRight - rx - 10)), rx + 10, ry + 8, Theme.TEXT);
        String meta = formatSize(archive) + " · " + formatModified(archive);
        Theme.text(g, font, Theme.ellipsize(font, meta, Math.max(30, textRight - rx - 10)), rx + 10, ry + 25, Theme.TEXT_MUTED);
        drawExpCost(g, archive, bx, by - 11, ACTION_W);
        drawActionButton(g, bx, by, ACTION_W, ACTION_H, "上传", true, Theme.inside(mouseX, mouseY, bx, by, ACTION_W, ACTION_H));
    }

    private boolean onServerRowClick(SpaceInfo space, double mx, double my, int button, int rowX, int rowY, int rowW, int rowH) {
        if (button != 0) return false;
        int bx = rowX + rowW - ACTION_W - 8;
        int by = rowY + rowH - ACTION_H - 8;
        if (Theme.inside(mx, my, bx, by, ACTION_W, ACTION_H)) {
            ClientSpaceArchiveTransfer.requestDownload(space.spaceId());
            return true;
        }
        return false;
    }

    private boolean onArchiveRowClick(ArchiveView archive, double mx, double my, int button, int rowX, int rowY, int rowW, int rowH) {
        if (button != 0) return false;
        int bx = rowX + rowW - ACTION_W - 8;
        int by = rowY + rowH - ACTION_H - 8;
        if (Theme.inside(mx, my, bx, by, ACTION_W, ACTION_H)) {
            ClientSpaceArchiveTransfer.requestUpload(archive.path());
            return true;
        }
        return false;
    }

    private void renderFooter(GuiGraphics g, int mouseX, int mouseY, float partialTick, int fx, int fy, int fw) {
        Theme.hLine(g, fx, fy, fw, Theme.DIVIDER);
        refreshButton.bounds(fx, fy + 6, 54, 18).render(g, mouseX, mouseY, partialTick);
        String status = ClientSpaceArchiveTransfer.status();
        Theme.text(g, font, Theme.ellipsize(font, status, Math.max(40, fw - 64)), fx + 64, fy + 11, Theme.TEXT_MUTED);
    }

    private void drawActionButton(GuiGraphics g, int bx, int by, int bw, int bh, String label, boolean enabled, boolean hovered) {
        int fill = !enabled ? Theme.SURFACE_SUNK : hovered ? Theme.PRIMARY_HOVER : Theme.PRIMARY;
        int text = enabled ? Theme.TEXT_ON_PRIM : Theme.TEXT_FAINT;
        Theme.fillRound(g, bx, by, bw, bh, Theme.RADIUS, fill);
        Theme.textInBox(g, font, label, bx, by, bw, bh, text);
    }

    private void drawExpCost(GuiGraphics g, ArchiveView archive, int x, int y, int w) {
        boolean known = archive.expCost() >= 0;
        String text = known ? Integer.toString(archive.expCost()) : "?";
        int color = known ? 0xFF43A047 : Theme.TEXT_FAINT;
        int dot = known ? 0xFF63C85E : Theme.TEXT_FAINT;
        int textW = Math.round(font.width(text) * 0.75f);
        int totalW = 7 + textW;
        int bx = x + Math.max(0, (w - totalW) / 2);
        Theme.fillRound(g, bx, y + 4, 5, 5, 3, dot);
        g.pose().pushPose();
        g.pose().translate(bx + 8, y + 2, 0);
        g.pose().scale(0.75f, 0.75f, 1.0f);
        g.drawString(font, Theme.styled(text), 0, 0, color, false);
        g.pose().popPose();
    }

    private void drawEmpty(GuiGraphics g, int ex, int ey, int ew, int eh, String text) {
        Theme.panel(g, ex, ey, ew, eh, Theme.RADIUS, Theme.SURFACE_ALT, Theme.BORDER);
        Theme.textCentered(g, font, text, ex + ew / 2, ey + Math.max(10, eh / 2 - 4), Theme.TEXT_FAINT);
    }

    private void drawWrapped(GuiGraphics g, String text, int tx, int ty, int maxW, int color, int maxLines) {
        String remaining = text;
        for (int line = 0; line < maxLines && !remaining.isEmpty(); line++) {
            String out = fitLine(remaining, maxW);
            if (line == maxLines - 1 && out.length() < remaining.length()) {
                out = Theme.ellipsize(font, remaining, maxW);
                remaining = "";
            } else {
                remaining = remaining.substring(out.length()).trim();
            }
            Theme.text(g, font, out, tx, ty + line * 11, color);
        }
    }

    private String fitLine(String text, int maxW) {
        if (Theme.styledWidth(font, text) <= maxW) return text;
        int best = 0;
        for (int i = 1; i <= text.length(); i++) {
            if (Theme.styledWidth(font, text.substring(0, i)) > maxW) break;
            best = i;
        }
        return text.substring(0, Math.max(1, best)).trim();
    }

    private SpacePermission.AccessLevel selfLevel(SpaceInfo space) {
        return mc.player == null ? SpacePermission.AccessLevel.NONE : space.effectiveLevel(mc.player.getUUID());
    }

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
            case PRIVATE -> "私有";
            case PUBLIC -> "公开";
            case WHITELIST -> "白名单";
            case BLACKLIST -> "黑名单";
        };
    }

    private void openArchiveDirectory() {
        try {
            Path dir = ClientSpaceArchiveTransfer.archiveDir();
            Files.createDirectories(dir);
            Util.getPlatform().openFile(dir.toFile());
            ClientSpaceArchiveTransfer.setStatus("已打开本地空间包目录: " + dir);
        } catch (IOException e) {
            ClientSpaceArchiveTransfer.setStatus("打开本地空间包目录失败: " + e.getMessage());
        }
    }

    private String formatSize(ArchiveView archive) {
        long bytes = archive.size();
        if (bytes < 0) return "未知大小";
        if (bytes >= 1024L * 1024L) return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0);
        if (bytes >= 1024L) return String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return bytes + " B";
    }

    private String formatModified(ArchiveView archive) {
        if (archive.modified() <= 0) return "未知时间";
        LocalDateTime time = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(archive.modified()), ZoneId.systemDefault());
        return TIME_FORMAT.format(time);
    }

    private long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return -1L;
        }
    }

    private long modifiedTime(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (openDirButtonVisible && openDirButton != null && openDirButton.mouseClicked(mx, my, button)) return true;
        if (refreshButton != null && refreshButton.mouseClicked(mx, my, button)) return true;
        if (serverList != null && serverList.mouseClicked(mx, my, button)) return true;
        return archiveList != null && archiveList.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (serverList != null && serverList.mouseScrolled(mx, my, sy)) return true;
        return archiveList != null && archiveList.mouseScrolled(mx, my, sy);
    }
}
