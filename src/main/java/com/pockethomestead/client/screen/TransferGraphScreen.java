package com.pockethomestead.client.screen;

import com.pockethomestead.client.ClientTransferGraphCache;
import com.pockethomestead.client.ui.Theme;
import com.pockethomestead.client.ui.TransferGraphGuiTextures;
import com.pockethomestead.compat.create.CreateCompat;
import com.pockethomestead.network.RequestTransferGraphPacket;
import com.pockethomestead.network.SaveTransferGraphPacket;
import com.pockethomestead.network.TransferGraphSyncPacket;
import com.pockethomestead.network.TransferTeamPacket;
import com.pockethomestead.network.TransferGraphValidationPacket;
import com.pockethomestead.transfer.TransferEdge;
import com.pockethomestead.transfer.TransferGraph;
import com.pockethomestead.transfer.TransferNode;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class TransferGraphScreen extends Screen {
    private static final int NODE_W = GraphNodeLayout.NODE_W;
    private static final int REROUTE_W = GraphNodeLayout.REROUTE_W;
    private static final int TRASH_W = GraphNodeLayout.TRASH_W;
    private static final int GATE_W = GraphNodeLayout.GATE_W;
    private static final int JUMP_W = GraphNodeLayout.JUMP_W;
    private static final int MINI_NODE_H = GraphNodeLayout.MINI_NODE_H;
    private static final int JUMP_H = GraphNodeLayout.JUMP_H;
    private static final int REROUTE_ROW_Y = GraphNodeLayout.REROUTE_ROW_Y;
    private static final int SEARCH_W = 190;
    private static final int HELP_W = 292;
    private static final int HELP_H = 274;
    private static final int PORT = 7;
    private static final int HEADER_H = 0;
    private static final int DEFAULT_CONTROL_X = 18;
    private static final int DEFAULT_CONTROL_Y = 18;
    private static final int CONTROL_W = 108;
    private static final int CONTROL_H = 38;
    private static final int CONTROL_PANEL_W = 156;
    private static final int CONTROL_PANEL_H = 118;
    private static final int CONTROL_DROPDOWN_W = 116;
    private static final int CONTROL_DROPDOWN_ROW_H = 18;
    private static final int PAGE_RENAME_ZONE_W = 28;
    private static final String SCOPE_ALL = "*";
    private static final int PORT_INSET = 12;
    private static final int COLLAPSED_CHEST_H = 160;
    private static final int COLLAPSED_CHEST_H_NO_CREATE = 124;
    private static final int CHEST_FIRST_FILTER_Y_NO_CREATE = 126;
    private static final int COLLAPSED_REROUTE_H = 146;

    private final GraphCanvas canvas = new GraphCanvas();

    private int panX = 48;
    private int panY = 48;
    private int controlX = DEFAULT_CONTROL_X;
    private int controlY = DEFAULT_CONTROL_Y;
    private double zoom = 0.62;
    private String activePageId = TransferGraph.DEFAULT_PAGE_ID;
    private MenuMode menuMode = MenuMode.NONE;
    private PopupMode popupMode = PopupMode.NONE;
    private int menuX, menuY, popupX, popupY, pendingNodeX, pendingNodeY;
    private String selectedNodeId, selectedEdgeId, selectedItemId, pendingDeletePageId, pendingTeamId;
    private String pendingRenameNodeId;
    private String draggingNodeId;
    private int dragOffX, dragOffY;
    private int dragPreviewX, dragPreviewY;
    private boolean dragMoved;
    private boolean draggingPopup;
    private boolean draggingControls;
    private boolean controlsMoved;
    private boolean exitAfterSave;
    private int popupDragOffX, popupDragOffY, controlDragOffX, controlDragOffY;
    private String linkingFromNodeId, linkingFromPort = TransferEdge.PORT_ALL;
    private String selectedReplenishItemId;
    private String replenishTargetValue = "";
    private boolean editingReplenishTarget;
    private EditBox pageNameEdit;
    private EdgeField focusedEdgeField = EdgeField.NONE;
    private GateField focusedGateField = GateField.NONE;
    private String selectedRateItemId;
    private String gateMinValue = "";
    private String gateMaxValue = "";
    private boolean searchForEdgeItem;
    private boolean searchFocused;
    private String searchValue = "";
    private String rateSecondsValue = "1";
    private String rateItemsValue = "64";
    private String lastSearch = "";
    private List<String> cachedSearch = List.of();
    private SearchKind searchKind = SearchKind.ALL;

    private List<TransferGraphSyncPacket.PageData> draftPages = new ArrayList<>();
    private List<TransferGraphSyncPacket.NodeData> draftNodes = new ArrayList<>();
    private List<TransferGraphSyncPacket.EdgeData> draftEdges = new ArrayList<>();
    private boolean dirty;
    private boolean savePending;
    private boolean validationStale = true;
    private boolean controlsExpanded;
    private int graphSyncTicker;
    private String pendingGraphKind = "";
    private String pendingGraphId = "";

    private enum MenuMode { NONE, ROOT, CHEST_LIST, JUMP_OUTPUT_LIST, GRAPH_ACTION, TEAM_MEMBER_ADD, PAGE_ACTION, PAGE_DELETE_CONFIRM, PAGE_RENAME, PAGE_CREATE, NODE_RENAME }
    private enum PopupMode { NONE, NODE, EDGE, SEARCH, HELP, EXIT_CONFIRM }
    private enum EdgeField { NONE, SECONDS, ITEMS }
    private enum GateField { NONE, MIN, MAX }
    private enum SearchKind { ALL, ITEM, FLUID }

    public TransferGraphScreen() {
        super(Component.literal("可视化传输图"));
    }

    public TransferGraphScreen(String initialGraphKind, String initialGraphId) {
        this();
        this.pendingGraphKind = initialGraphKind == null || initialGraphKind.isBlank() ? "PRIVATE" : initialGraphKind;
        this.pendingGraphId = initialGraphId == null ? "" : initialGraphId;
    }

    @Override
    protected void init() {
        pageNameEdit = new EditBox(font, 0, 0, 108, 16, Component.literal("分页名称"));
        pageNameEdit.setMaxLength(24);
        pageNameEdit.visible = false;
        addRenderableWidget(pageNameEdit);

        if (draftPages.isEmpty()) loadDraftFromCache();
        PacketDistributor.sendToServer(new RequestTransferGraphPacket(currentGraphKind(), currentGraphId()));
    }

    public void onGraphSynced() {
        if (!dirty || savePending || draftPages.isEmpty()) {
            loadDraftFromCache();
            dirty = false;
            savePending = false;
            validationStale = true;
            pendingGraphKind = "";
            pendingGraphId = "";
        }
        if (page(activePageId) == null) activePageId = firstPage().id();
        TransferGraphSyncPacket.EdgeData edge = edge(selectedEdgeId);
        if (edge != null) syncRateEditors(edge);
    }

    public void onValidationUpdated() {
        validationStale = false;
        if (savePending && !hasValidationErrors()) {
            dirty = false;
            savePending = false;
            if (exitAfterSave) {
                exitAfterSave = false;
                onClose();
            }
        }
        if (hasValidationErrors()) {
            savePending = false;
            exitAfterSave = false;
            if (popupMode == PopupMode.EXIT_CONFIRM) popupMode = PopupMode.NONE;
        }
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        ensureDraft();
        syncWidgets();
        canvas.setZoom(zoom);
        renderGrid(g);
        renderEdges(g, mx, my);
        renderNodes(g, mx, my);
        renderHeader(g);
        renderMenu(g);
        renderPopup(g, mx, my, partialTick);
        if (linkingFromNodeId != null) renderActiveLink(g, mx, my);
    }

    @Override
    public void tick() {
        super.tick();
        graphSyncTicker++;
        if (graphSyncTicker >= 40) {
            graphSyncTicker = 0;
            PacketDistributor.sendToServer(new RequestTransferGraphPacket(currentGraphKind(), currentGraphId()));
        }
    }

    private void syncWidgets() {
        if (pageNameEdit != null) {
            pageNameEdit.visible = menuMode == MenuMode.PAGE_RENAME || menuMode == MenuMode.PAGE_CREATE
                    || menuMode == MenuMode.TEAM_MEMBER_ADD || menuMode == MenuMode.NODE_RENAME;
            pageNameEdit.setX(menuX + 8);
            pageNameEdit.setY(menuY + 8);
        }
    }

    private void renderHeader(GuiGraphics g) {
        int x = controlX;
        int y = controlY;
        int statusColor = hasValidationErrors() ? Theme.DANGER : dirty ? Theme.PRIMARY_PRESS : Theme.SUCCESS;
        String status = headerStatusLabel();
        softPanel(g, x, y, CONTROL_W, CONTROL_H, Theme.SURFACE, Theme.BORDER);
        softButton(g, x + 7, y + 7, 42, 24, dirty ? Theme.PRIMARY_SOFT : Theme.PRIMARY_SOFT, dirty ? Theme.PRIMARY : Theme.PRIMARY);
        TransferGraphGuiTextures.icon(g, TransferGraphGuiTextures.Icon.SAVE, x + 19, y + 10, 20);
        Theme.fillRound(g, x + 58, y + 15, 8, 8, 4, statusColor);
        text(g, hasValidationErrors() ? "!" : "OK", x + 70, y + 12, statusColor);
        TransferGraphGuiTextures.icon(g, controlsExpanded ? TransferGraphGuiTextures.Icon.CHEVRON_DOWN : TransferGraphGuiTextures.Icon.CHEVRON_RIGHT, x + 88, y + 10, 20);

        if (!controlsExpanded) return;

        int fy = y + CONTROL_H + 8;
        softPanel(g, x, fy, CONTROL_PANEL_W, CONTROL_PANEL_H, Theme.SURFACE, Theme.BORDER);
        Theme.fillRound(g, x + 12, fy + 16, 7, 7, 4, statusColor);
        text(g, status, x + 25, fy + 11, statusColor);
        TransferGraphGuiTextures.icon(g, TransferGraphGuiTextures.Icon.CHEVRON_DOWN, x + CONTROL_PANEL_W - 29, fy + 9, 20);

        softButton(g, x + 10, fy + 36, 64, 22, Theme.SURFACE_ALT, Theme.BORDER);
        TransferGraphSyncPacket.PageData activePage = page(activePageId);
        if (activePage == null) activePage = firstPage();
        text(g, Theme.ellipsize(font, activePage.name(), 42), x + 16, fy + 42, Theme.TEXT_MUTED);
        TransferGraphGuiTextures.icon(g, TransferGraphGuiTextures.Icon.CHEVRON_DOWN, x + 55, fy + 37, 20);
        softButton(g, x + 82, fy + 36, 64, 22, Theme.SURFACE_ALT, Theme.BORDER);
        text(g, Theme.ellipsize(font, currentGraphShortLabel(), 42), x + 90, fy + 42, Theme.TEXT_MUTED);
        TransferGraphGuiTextures.icon(g, TransferGraphGuiTextures.Icon.CHEVRON_DOWN, x + 127, fy + 37, 20);

        int bx = x + 10;
        int by = fy + 68;
        renderToolbarIconButton(g, bx, by, TransferGraphGuiTextures.Icon.SAVE, dirty ? TransferGraphGuiTextures.ButtonStyle.BLUE : TransferGraphGuiTextures.ButtonStyle.NORMAL);
        renderToolbarIconButton(g, bx + 32, by, TransferGraphGuiTextures.Icon.PAGE, TransferGraphGuiTextures.ButtonStyle.NORMAL);
        renderToolbarIconButton(g, bx + 64, by, TransferGraphGuiTextures.Icon.PLUS, TransferGraphGuiTextures.ButtonStyle.NORMAL);
        renderToolbarIconButton(g, bx + 96, by, TransferGraphGuiTextures.Icon.HELP, popupMode == PopupMode.HELP ? TransferGraphGuiTextures.ButtonStyle.SELECTED : TransferGraphGuiTextures.ButtonStyle.NORMAL);
        chip(g, x + 10, fy + 94, 54, 18, Theme.SURFACE, Theme.PRIMARY);
        text(g, Math.round(zoom * 100) + "%", x + 20, fy + 99, Theme.PRIMARY_PRESS);
        chip(g, x + 84, fy + 94, 54, 18, Theme.SURFACE, Theme.DANGER);
        text(g, "放弃", x + 98, fy + 99, dirty ? Theme.DANGER : Theme.TEXT_MUTED);
    }

    private void renderToolbarIconButton(GuiGraphics g, int x, int y, TransferGraphGuiTextures.Icon icon, TransferGraphGuiTextures.ButtonStyle style) {
        int fill = style == TransferGraphGuiTextures.ButtonStyle.BLUE || style == TransferGraphGuiTextures.ButtonStyle.SELECTED ? Theme.PRIMARY_SOFT : Theme.SURFACE;
        int border = style == TransferGraphGuiTextures.ButtonStyle.BLUE || style == TransferGraphGuiTextures.ButtonStyle.SELECTED ? Theme.PRIMARY : Theme.BORDER;
        softButton(g, x, y, 26, 23, fill, border);
        TransferGraphGuiTextures.icon(g, icon, x + 4, y + 3, 18);
    }

    private String currentGraphShortLabel() {
        return switch (currentGraphKind()) {
            case "PROTECTED" -> "团队";
            case "SPACE" -> "空间";
            default -> "私有";
        };
    }

    private String headerStatusLabel() {
        if (hasValidationErrors()) return "配置错误";
        return dirty ? "未保存" : "已保存";
    }

    private void crispPanel(GuiGraphics g, int x, int y, int w, int h, int fill, int border) {
        softPanel(g, x, y, w, h, fill, border);
    }

    private void chip(GuiGraphics g, int x, int y, int w, int h, int fill, int border) {
        softButton(g, x, y, w, h, fill, border);
    }

    private void softPanel(GuiGraphics g, int x, int y, int w, int h, int fill, int border) {
        Theme.shadow(g, x, y, w, h, 12);
        Theme.panel(g, x, y, w, h, Math.min(16, Math.max(7, Math.min(w, h) / 5)), fill, border);
    }

    private void softButton(GuiGraphics g, int x, int y, int w, int h, int fill, int border) {
        Theme.panel(g, x, y, w, h, Math.min(10, Math.max(5, h / 2)), fill, border);
    }

    private void centeredIconButton(GuiGraphics g, TransferGraphGuiTextures.Icon icon, int cx, int cy,
                                    int buttonSize, int iconSize, int fill, int border) {
        softButton(g, cx - buttonSize / 2, cy - buttonSize / 2, buttonSize, buttonSize, fill, border);
        TransferGraphGuiTextures.icon(g, icon, cx - iconSize / 2, cy - iconSize / 2, iconSize);
    }

    private void centeredCloseButton(GuiGraphics g, int cx, int cy, int buttonSize, int fill, int border, int color) {
        int left = cx - buttonSize / 2;
        int top = cy - buttonSize / 2;
        softButton(g, left, top, buttonSize, buttonSize, fill, border);
        int half = Math.max(3, buttonSize / 4);
        for (int i = -half; i <= half; i++) {
            g.fill(cx + i, cy + i, cx + i + 1, cy + i + 1, color);
            g.fill(cx + i, cy - i, cx + i + 1, cy - i + 1, color);
        }
    }

    private void text(GuiGraphics g, String s, int x, int y, int color) {
        Theme.text(g, font, s, x, y, color);
    }

    private void textRight(GuiGraphics g, String s, int rx, int y, int color) {
        Theme.textRight(g, font, s, rx, y, color);
    }

    private String compactRateLabel(int value, String resourceId) {
        if (TransferEdge.ENERGY_FE.equals(resourceId)) return value + "FE/m";
        if (TransferEdge.STRESS_SU.equals(resourceId)) return value + "SU/m";
        return value + (GraphResourceUtils.isFluidResource(resourceId) ? "mB/m" : "/m");
    }

    private void renderGrid(GuiGraphics g) {
        g.fill(0, 0, width, height, 0xFFFDFEFF);
        int major = Math.max(56, (int) Math.round(72 * Math.max(0.75, Math.min(1.35, zoom))));
        int minor = Math.max(28, major / 2);
        int startX = Math.floorMod(panX, minor) - minor;
        int startY = Math.floorMod(panY, minor) - minor;
        for (int x = startX; x < width; x += minor) {
            Theme.vLine(g, x, 0, height, Math.floorMod(x - panX, major) == 0 ? 0x2EB7D7EE : 0x1DD9EAF6);
        }
        for (int y = startY; y < height; y += minor) {
            Theme.hLine(g, 0, y, width, Math.floorMod(y - panY, major) == 0 ? 0x2EB7D7EE : 0x1DD9EAF6);
        }
    }

    private void renderNodes(GuiGraphics g, int mx, int my) {
        for (TransferGraphSyncPacket.NodeData node : visibleNodes()) {
            int x = nodeScreenX(node), y = nodeScreenY(node), h = nodeHeight(node);
            int sw = scaled(nodeWidth(node)), sh = scaled(h);
            if (x + sw < 0 || y + sh < HEADER_H || x > width || y > height) continue;
            boolean selected = node.id().equals(selectedNodeId);
            boolean invalid = hasIssueForNode(node.id());
            int fill = node.enabled() ? Theme.SURFACE : 0xFFE9EDF2;
            int border = invalid ? Theme.DANGER : selected ? Theme.PRIMARY_PRESS : (node.enabled() ? Theme.BORDER_STRONG : 0xFFC8D0D9);
            g.pose().pushPose();
            g.pose().translate(x, y, 0);
            g.pose().scale((float) zoom, (float) zoom, 1.0f);
            if (node.type().equals("REROUTE")) {
                renderRerouteNode(g, node, border);
                g.pose().popPose();
                continue;
            }
            if (node.type().equals("LIMIT_GATE")) {
                renderLimitGateNode(g, node);
                g.pose().popPose();
                continue;
            }
            if (node.type().equals("JUMP_INPUT") || node.type().equals("JUMP_OUTPUT")) {
                renderJumpNode(g, node);
                g.pose().popPose();
                continue;
            }
            if (node.type().equals("TRASH")) {
                renderTrashNode(g, node, border);
                g.pose().popPose();
                continue;
            }
            if (node.type().equals("PLAYER_INVENTORY")) {
                renderPlayerInventoryNode(g, node, border);
                g.pose().popPose();
                continue;
            }

            drawNodePanel(g, NODE_W, h, node.enabled(), selected, invalid);
            drawNodeHeader(g, NODE_W, node.enabled() ? TransferGraphGuiTextures.HeaderStyle.BLUE : TransferGraphGuiTextures.HeaderStyle.DISABLED);
            TransferGraphGuiTextures.icon(g, TransferGraphGuiTextures.Icon.CHEST, 10, 6);
            text(g, (node.enabled() ? "" : "⊘ ") + "传输箱 " + node.chestId(), 32, 7, node.enabled() ? Theme.TEXT : Theme.TEXT_MUTED);
            drawExpandButton(g, node, NODE_W);
            text(g, GraphResourceUtils.shortPos(node.pos()), 12, 38, Theme.TEXT_MUTED);
            drawChestStatusMarker(g, node, 72, 42);
            renderBandwidthStatus(g, node, 82, 38);
            renderChestNode(g, node, 0, 0);
            g.pose().popPose();
        }
    }

    private void drawNodePanel(GuiGraphics g, int w, int h, boolean enabled, boolean selected, boolean invalid) {
        int fill = enabled ? Theme.SURFACE : 0xFFE9EDF2;
        int border = invalid ? Theme.DANGER : selected ? Theme.PRIMARY_PRESS : enabled ? Theme.BORDER_STRONG : 0xFFC8D0D9;
        softPanel(g, 0, 0, w, h, fill, border);
    }

    private void drawNodeHeader(GuiGraphics g, int w, TransferGraphGuiTextures.HeaderStyle style) {
        int fill = switch (style) {
            case CYAN -> 0xFFE6F7FA;
            case PINK -> 0xFFFFEEF4;
            case DISABLED -> 0xFFDDE3EA;
            default -> 0xFFE6F4FF;
        };
        int line = switch (style) {
            case CYAN -> 0xFF4BBBC9;
            case PINK -> 0xFFFF84A2;
            case DISABLED -> Theme.TEXT_FAINT;
            default -> Theme.PRIMARY;
        };
        Theme.fillRound(g, 4, 4, w - 8, 26, 13, fill);
        Theme.hLine(g, 16, 29, w - 32, line);
    }

    private void renderRerouteNode(GuiGraphics g, TransferGraphSyncPacket.NodeData node, int border) {
        int h = nodeHeight(node);
        boolean selected = node.id().equals(selectedNodeId);
        boolean invalid = hasIssueForNode(node.id());
        drawNodePanel(g, REROUTE_W, h, node.enabled(), selected, invalid);
        drawNodeHeader(g, REROUTE_W, node.enabled() ? TransferGraphGuiTextures.HeaderStyle.CYAN : TransferGraphGuiTextures.HeaderStyle.DISABLED);
        TransferGraphGuiTextures.icon(g, TransferGraphGuiTextures.Icon.REROUTE, 10, 6);
        text(g, (node.enabled() ? "" : "⊘ ") + "中转节点", 32, 8, node.enabled() ? Theme.TEXT : Theme.TEXT_MUTED);
        drawExpandButton(g, node, REROUTE_W);
        text(g, "输入", 24, 34, node.enabled() ? Theme.TEXT_MUTED : Theme.TEXT_FAINT);
        if (isExpanded(node)) {
            textRight(g, "入", REROUTE_W - 82, 34, Theme.TEXT_MUTED);
            textRight(g, "出", REROUTE_W - 34, 34, Theme.TEXT_MUTED);
        } else {
            text(g, "输出 " + visibleRerouteOutputPorts(node).size() + " 类", 112, 34, Theme.TEXT_MUTED);
        }

        Map<String, TransferGraphSyncPacket.NodeFlowData> flows = rerouteFlowMap(node);
        List<String> ports = visibleRerouteOutputPorts(node);
        for (String port : ports) {
            int py = rerouteOutputLocalY(node, port);
            TransferGraphSyncPacket.NodeFlowData flow = flowForPort(port, flows);
            int inColor = node.enabled() ? reroutePortColor(port, true) : Theme.TEXT_FAINT;
            int outColor = node.enabled() ? reroutePortColor(port, false) : Theme.TEXT_FAINT;
            drawPort(g, GraphNodeLayout.inputPortLocalX(), py, inColor);
            drawPort(g, GraphNodeLayout.rerouteOutputPortLocalX(), py, outColor);
            drawResourceGlyph(g, port, 28, py, node.enabled());
            text(g, Theme.ellipsize(font, reroutePortLabel(port), 92), 54, py - 4, node.enabled() ? Theme.TEXT : Theme.TEXT_MUTED);
            if (port.startsWith(TransferEdge.FLUID_PREFIX) && !TransferEdge.FLUID_ALL.equals(port)) {
                if (zoom > 0.24) g.renderItem(new ItemStack(Items.WATER_BUCKET), 12, py - 8);
            } else if (port.startsWith(TransferEdge.ITEM_PREFIX) && !TransferEdge.PORT_ALL.equals(port) && zoom > 0.24) {
                Item item = GraphResourceUtils.resolveItem(port.substring(TransferEdge.ITEM_PREFIX.length()));
                if (item != null) g.renderItem(new ItemStack(item), 12, py - 8);
            }
            if (isExpanded(node)) {
                textRight(g, compactRateLabel(flow.inputRatePerMinute(), flow.itemId()), REROUTE_W - 82, py - 4, node.enabled() ? inColor : Theme.TEXT_FAINT);
                textRight(g, compactRateLabel(flow.outputRatePerMinute(), flow.itemId()), REROUTE_W - 34, py - 4, node.enabled() ? outColor : Theme.TEXT_FAINT);
            } else {
                textRight(g, reroutePortShortLabel(port), REROUTE_W - 28, py - 4, node.enabled() ? outColor : Theme.TEXT_FAINT);
            }
            if (isExpanded(node) && node.filterItemIds().contains(GraphResourceUtils.filterFromPort(port))) {
                text(g, "×", REROUTE_W - 28, py - 4, Theme.DANGER);
            }
        }
    }

    private void renderLimitGateNode(GuiGraphics g, TransferGraphSyncPacket.NodeData node) {
        boolean selected = node.id().equals(selectedNodeId);
        boolean invalid = hasIssueForNode(node.id());
        String resource = incomingResource(node.id());
        int inColor = node.enabled() ? portColor(resource, true) : Theme.TEXT_FAINT;
        int outColor = node.enabled() ? portColor(resource, false) : Theme.TEXT_FAINT;
        drawNodePanel(g, GATE_W, MINI_NODE_H, node.enabled(), selected, invalid);
        TransferGraphGuiTextures.icon(g, TransferGraphGuiTextures.Icon.LIMIT_GATE, 12, 7);
        drawPort(g, GraphNodeLayout.inputPortLocalX(), MINI_NODE_H / 2, inColor);
        drawPort(g, GATE_W - GraphNodeLayout.PORT_INSET, MINI_NODE_H / 2, outColor);
        if (resource != null) drawResourceGlyph(g, resource, 34, MINI_NODE_H / 2, node.enabled());
        text(g, Theme.ellipsize(font, (node.enabled() ? "" : "⊘ ") + gateModeLabel(node) + "门", 88), 56, 9, node.enabled() ? Theme.TEXT : Theme.TEXT_MUTED);
        text(g, Theme.ellipsize(font, gateRangeLabel(node, resource), 112), 56, 27, node.enabled() ? Theme.TEXT_MUTED : Theme.TEXT_FAINT);
    }

    private void renderJumpNode(GuiGraphics g, TransferGraphSyncPacket.NodeData node) {
        boolean selected = node.id().equals(selectedNodeId);
        boolean invalid = hasIssueForNode(node.id());
        boolean input = node.type().equals("JUMP_INPUT");
        String resource = input ? incomingResource(node.id()) : jumpOutputResource(node);
        int color = node.enabled() ? portColor(resource, input) : Theme.TEXT_FAINT;
        drawNodePanel(g, JUMP_W, JUMP_H, node.enabled(), selected, invalid);
        TransferGraphGuiTextures.icon(g, input ? TransferGraphGuiTextures.Icon.JUMP_IN : TransferGraphGuiTextures.Icon.JUMP_OUT, 8, 6);
        if (input) drawPort(g, GraphNodeLayout.inputPortLocalX(), JUMP_H / 2, color);
        else drawPort(g, JUMP_W - GraphNodeLayout.PORT_INSET, JUMP_H / 2, color);
        int textX = resource == null ? 34 : input ? 52 : 48;
        int textW = Math.max(32, JUMP_W - textX - (input ? 8 : 22));
        if (resource != null) drawResourceGlyph(g, resource, input ? 30 : 26, JUMP_H / 2, node.enabled());
        String label = jumpLabel(node);
        text(g, Theme.ellipsize(font, (node.enabled() ? "" : "⊘ ") + (input ? "入口 " : "出口 ") + label, textW),
                textX, 6, node.enabled() ? Theme.TEXT : Theme.TEXT_MUTED);
        text(g, input ? (jumpBound(node) ? "已绑定" : "未绑定") : "跳线输出",
                textX, 22, node.enabled() ? Theme.TEXT_MUTED : Theme.TEXT_FAINT);
    }

    private void renderTrashNode(GuiGraphics g, TransferGraphSyncPacket.NodeData node, int border) {
        int h = nodeHeight(node);
        boolean selected = node.id().equals(selectedNodeId);
        boolean invalid = hasIssueForNode(node.id());
        drawNodePanel(g, TRASH_W, h, node.enabled(), selected, invalid);
        drawNodeHeader(g, TRASH_W, node.enabled() ? TransferGraphGuiTextures.HeaderStyle.PINK : TransferGraphGuiTextures.HeaderStyle.DISABLED);
        TransferGraphGuiTextures.icon(g, TransferGraphGuiTextures.Icon.TRASH, 10, 6);
        text(g, (node.enabled() ? "" : "⊘ ") + "销毁节点", 32, 8, node.enabled() ? Theme.TEXT : Theme.TEXT_MUTED);
        drawExpandButton(g, node, TRASH_W);
        drawPort(g, GraphNodeLayout.inputPortLocalX(), h / 2, node.enabled() ? 0xFFE05768 : Theme.TEXT_FAINT);
        text(g, "剩余产物终点", 12, 36, node.enabled() ? Theme.TEXT : Theme.TEXT_MUTED);
        if (isExpanded(node)) text(g, "按连线限速销毁", 12, 52, node.enabled() ? Theme.TEXT_MUTED : Theme.TEXT_FAINT);
    }

    private void renderPlayerInventoryNode(GuiGraphics g, TransferGraphSyncPacket.NodeData node, int border) {
        int h = nodeHeight(node);
        boolean selected = node.id().equals(selectedNodeId);
        boolean invalid = hasIssueForNode(node.id());
        drawNodePanel(g, TRASH_W, h, node.enabled(), selected, invalid);
        drawNodeHeader(g, TRASH_W, node.enabled() ? TransferGraphGuiTextures.HeaderStyle.BLUE : TransferGraphGuiTextures.HeaderStyle.DISABLED);
        TransferGraphGuiTextures.icon(g, TransferGraphGuiTextures.Icon.BACKPACK, 10, 6);
        text(g, Theme.ellipsize(font, (node.enabled() ? "" : "⊘ ") + playerInventoryTitle(node), TRASH_W - 58), 32, 8, node.enabled() ? Theme.TEXT : Theme.TEXT_MUTED);
        drawExpandButton(g, node, TRASH_W);
        drawPort(g, GraphNodeLayout.inputPortLocalX(), h / 2, node.enabled() ? Theme.SUCCESS : Theme.TEXT_FAINT);
        text(g, "补货目标", 12, 36, node.enabled() ? Theme.TEXT : Theme.TEXT_MUTED);
        String playerLabel = playerInventoryLabel(node);
        text(g, playerLabel, 82, 36, Theme.TEXT_MUTED);
        if (isExpanded(node)) {
            text(g, "+ 补货项", 12, 54, node.enabled() ? Theme.PRIMARY_PRESS : Theme.TEXT_MUTED);
            int y = 74;
            if (node.replenishRules().isEmpty()) {
                text(g, "默认补到一组", 12, y, Theme.TEXT_MUTED);
            } else {
                for (TransferGraphSyncPacket.ReplenishRuleData rule : node.replenishRules()) {
                    Item item = GraphResourceUtils.resolveItem(rule.itemId());
                    if (item != null && zoom > 0.22) g.renderItem(new ItemStack(item), 12, y - 8);
                    text(g, Theme.ellipsize(font, GraphResourceUtils.shortResource(rule.itemId()), 80), 32, y - 4, node.enabled() ? Theme.TEXT : Theme.TEXT_MUTED);
                    boolean editing = editingReplenishTarget && node.id().equals(selectedNodeId) && rule.itemId().equals(selectedReplenishItemId);
                    text(g, editing ? "目标 " + replenishTargetValue + "_" : "目标 " + rule.targetCount(), 104, y - 4, editing ? Theme.SUCCESS : Theme.PRIMARY_PRESS);
                    text(g, "×", TRASH_W - 22, y - 4, Theme.DANGER);
                    y += 18;
                }
            }
        }
    }

    private String playerInventoryTitle(TransferGraphSyncPacket.NodeData node) {
        return "玩家" + playerInventoryLabel(node) + "的背包";
    }

    private String playerInventoryLabel(TransferGraphSyncPacket.NodeData node) {
        String target = node.targetPlayerId();
        if (target == null || target.isBlank()) return "自己";
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && target.equals(mc.player.getUUID().toString())) {
            return mc.player.getGameProfile().getName();
        }
        return target.substring(0, Math.min(8, target.length()));
    }

    private void renderChestNode(GuiGraphics g, TransferGraphSyncPacket.NodeData node, int x, int y) {
        renderBandwidthBar(g, node, x + 12, y + 56, NODE_W - 24);
        drawResourceRow(g, node, x, y + GraphNodeLayout.chestItemLocalY(), "物品输入", "物品输出", Theme.SUCCESS, Theme.PRIMARY, SearchKind.ITEM);
        if (createResourcesVisible()) {
            drawResourceRow(g, node, x, y + GraphNodeLayout.chestFluidLocalY(), "流体输入", "流体输出", 0xFF62CDBD, 0xFF359E92, SearchKind.FLUID);
        }
        drawResourceRow(g, node, x, y + chestEnergyLocalY(), "电力输入", "电力输出", 0xFFE0B43A, 0xFFC48E14, null);
        if (createResourcesVisible()) {
            drawResourceRow(g, node, x, y + GraphNodeLayout.chestStressLocalY(), "应力输入", "应力输出", 0xFFD781D8, 0xFFB560B8, null);
        }
        if (!isExpanded(node)) return;
        int iy = y + chestFirstFilterLocalY();
        for (String filter : node.filterItemIds()) {
            String filterPort = GraphResourceUtils.filterPort(filter);
            if (!isVisibleResourcePort(filterPort)) continue;
            Item item = GraphResourceUtils.resolveItem(filter);
            if (item != null && zoom > 0.22) g.renderItem(new ItemStack(item), x + 8, iy - 7);
            else if (GraphResourceUtils.isFluidResource(filter) && zoom > 0.22) g.renderItem(new ItemStack(Items.WATER_BUCKET), x + 8, iy - 7);
            text(g, Theme.ellipsize(font, GraphResourceUtils.shortResource(filter), 112), x + (zoom > 0.22 ? 28 : 8), iy - 3, node.enabled() ? Theme.TEXT : Theme.TEXT_MUTED);
            centeredCloseButton(g, x + GraphNodeLayout.filterRemoveLocalX(), iy, 12, Theme.DANGER_SOFT, Theme.DANGER, Theme.DANGER);
            drawPort(g, GraphNodeLayout.outputPortLocalX(), iy, node.enabled() ? portColor(filterPort, false) : Theme.TEXT_FAINT);
            iy += 18;
        }
    }

    private void renderBandwidthStatus(GuiGraphics g, TransferGraphSyncPacket.NodeData node, int x, int y) {
        TransferGraphSyncPacket.ChestData chest = chestDataFor(node);
        if (chest == null) {
            textRight(g, "带宽 ?", NODE_W - 8, y, Theme.TEXT_FAINT);
            return;
        }
        int total = Math.max(0, chest.networkBandwidth());
        int remaining = Math.max(0, Math.min(total, chest.remainingTransferBandwidth()));
        int used = Math.max(0, total - remaining);
        int color = total <= 0 ? Theme.TEXT_FAINT : remaining <= 0 ? Theme.DANGER : used > 0 ? Theme.PRIMARY_PRESS : Theme.SUCCESS;
        String label = "带宽 " + remaining + "/" + total;
        if (createResourcesVisible() && chest.stressBandwidthUsed() > 0) label += " 力-" + chest.stressBandwidthUsed();
        textRight(g, Theme.ellipsize(font, label, NODE_W - x - 6), NODE_W - 8, y, node.enabled() ? color : Theme.TEXT_FAINT);
    }

    private void drawChestStatusMarker(GuiGraphics g, TransferGraphSyncPacket.NodeData node, int x, int y) {
        TransferGraphSyncPacket.ChestData chest = chestDataFor(node);
        if (chest == null) {
            Theme.fillRound(g, x - 3, y - 3, 6, 6, 3, Theme.DANGER);
            return;
        }
        int color = switch (chest.status()) {
            case "MOVING" -> 0xFF55C7D8;
            case "OFFLINE_SIMULATED" -> 0xFFE0B43A;
            case "OFFLINE_DISABLED" -> Theme.TEXT_FAINT;
            case "MISSING" -> Theme.DANGER;
            default -> 0;
        };
        if (color != 0) Theme.fillRound(g, x - 3, y - 3, 6, 6, 3, color);
    }

    private void renderBandwidthBar(GuiGraphics g, TransferGraphSyncPacket.NodeData node, int x, int y, int w) {
        TransferGraphSyncPacket.ChestData chest = chestDataFor(node);
        int total = chest == null ? 0 : Math.max(0, chest.networkBandwidth());
        int stress = createResourcesVisible() && chest != null ? Math.max(0, Math.min(total, chest.stressBandwidthUsed())) : 0;
        int remaining = chest == null ? 0 : Math.max(0, Math.min(total, chest.remainingTransferBandwidth()));
        int used = Math.max(0, total - remaining);
        Theme.fillRound(g, x, y, w, 5, 2, Theme.SURFACE_SUNK);
        if (total <= 0) return;
        int stressW = Math.min(w, Math.round(w * (stress / (float) total)));
        int usedW = Math.min(w, Math.round(w * (used / (float) total)));
        if (usedW > 0) Theme.fillRound(g, x, y, usedW, 5, 2, node.enabled() ? Theme.PRIMARY_SOFT_H : Theme.TEXT_FAINT);
        if (stressW > 0) Theme.fillRound(g, x, y, stressW, 5, 2, node.enabled() ? 0x66D781D8 : Theme.TEXT_FAINT);
        if (remaining > 0) {
            int freeX = x + usedW;
            int freeW = Math.max(1, w - usedW);
            Theme.fillRound(g, freeX, y, freeW, 5, 2, node.enabled() ? 0x6637B782 : Theme.TEXT_FAINT);
        }
    }

    private void drawResourceRow(GuiGraphics g, TransferGraphSyncPacket.NodeData node, int x, int y, String inLabel, String outLabel, int inColor, int outColor, SearchKind addKind) {
        drawPort(g, GraphNodeLayout.inputPortLocalX(), y, node.enabled() ? inColor : Theme.TEXT_FAINT);
        String iconPort = addKind == SearchKind.FLUID ? TransferEdge.FLUID_ALL
                : inColor == 0xFFE0B43A ? TransferEdge.ENERGY_FE
                : inColor == 0xFFD781D8 ? TransferEdge.STRESS_SU
                : TransferEdge.PORT_ALL;
        drawResourceGlyph(g, iconPort, x + 28, y, node.enabled());
        text(g, inLabel, x + 50, y - 4, node.enabled() ? Theme.TEXT : Theme.TEXT_MUTED);
        if (addKind != null) {
            int bx = x + 106;
            centeredIconButton(g, TransferGraphGuiTextures.Icon.PLUS, bx + 8, y, 16, 14,
                    node.enabled() ? Theme.PRIMARY_SOFT : Theme.SURFACE_SUNK, node.enabled() ? Theme.PRIMARY : Theme.BORDER);
        }
        textRight(g, outLabel, x + NODE_W - 26, y - 4, node.enabled() ? Theme.TEXT_MUTED : Theme.TEXT_FAINT);
        drawPort(g, GraphNodeLayout.outputPortLocalX(), y, node.enabled() ? outColor : Theme.TEXT_FAINT);
    }

    private void drawResourceGlyph(GuiGraphics g, String port, int x, int cy, boolean enabled) {
        int y = cy - 10;
        if (port != null && port.startsWith(TransferEdge.FLUID_PREFIX)) {
            TransferGraphGuiTextures.icon(g, TransferGraphGuiTextures.Icon.FLUID, x, y);
        } else if (TransferEdge.ENERGY_FE.equals(port)) {
            TransferGraphGuiTextures.icon(g, TransferGraphGuiTextures.Icon.ENERGY, x, y);
        } else if (TransferEdge.STRESS_SU.equals(port)) {
            TransferGraphGuiTextures.icon(g, TransferGraphGuiTextures.Icon.STRESS, x, y);
        } else {
            TransferGraphGuiTextures.icon(g, TransferGraphGuiTextures.Icon.ITEM, x, y);
        }
        if (!enabled) g.fill(x, y, x + 18, y + 20, 0x88FFFFFF);
    }

    private int reroutePortColor(String port, boolean input) {
        if (port != null && port.startsWith(TransferEdge.FLUID_PREFIX)) return input ? 0xFF62CDBD : 0xFF359E92;
        if (TransferEdge.ENERGY_FE.equals(port)) return input ? 0xFFE0B43A : 0xFFC48E14;
        if (TransferEdge.STRESS_SU.equals(port)) return input ? 0xFFD781D8 : 0xFFB560B8;
        return input ? Theme.SUCCESS : Theme.PRIMARY;
    }

    private String reroutePortLabel(String port) {
        if (TransferEdge.PORT_ALL.equals(port)) return "全部物品";
        if (TransferEdge.FLUID_ALL.equals(port)) return "全部流体";
        if (TransferEdge.ENERGY_FE.equals(port)) return "电力 FE";
        if (TransferEdge.STRESS_SU.equals(port)) return "应力 SU";
        return GraphResourceUtils.shortResource(port);
    }

    private String reroutePortShortLabel(String port) {
        if (TransferEdge.PORT_ALL.equals(port)) return "物品";
        if (TransferEdge.FLUID_ALL.equals(port)) return "流体";
        if (TransferEdge.ENERGY_FE.equals(port)) return "电力";
        if (TransferEdge.STRESS_SU.equals(port)) return "应力";
        return "";
    }

    private void drawOutput(GuiGraphics g, int x, int y, String label, int color) {
        text(g, label, x + 8, y - 4, color);
        drawPort(g, GraphNodeLayout.outputPortLocalX(), y, Theme.PRIMARY);
    }

    private void drawExpandButton(GuiGraphics g, TransferGraphSyncPacket.NodeData node, int nodeW) {
        int bx = nodeW - 25;
        softButton(g, bx, 7, 16, 16, 0xFFEAF8FF, node.enabled() ? Theme.PRIMARY : Theme.BORDER);
        TransferGraphGuiTextures.icon(g, isExpanded(node) ? TransferGraphGuiTextures.Icon.CHEVRON_DOWN : TransferGraphGuiTextures.Icon.CHEVRON_RIGHT, bx, 7);
    }

    private void drawPort(GuiGraphics g, int cx, int cy, int color) {
        Theme.fillRound(g, cx - 5, cy - 5, 10, 10, 5, 0xEEFFFFFF);
        Theme.outlineRound(g, cx - 5, cy - 5, 10, 10, 5, 0x88D4E4F5);
        Theme.fillRound(g, cx - 3, cy - 3, 6, 6, 3, color);
    }

    private int portColor(String portKey, boolean input) {
        if (portKey == null) return input ? Theme.SUCCESS : Theme.PRIMARY;
        if (portKey.startsWith(TransferEdge.FLUID_PREFIX)) return input ? 0xFF62CDBD : 0xFF359E92;
        if (portKey.startsWith(TransferEdge.ENERGY_PREFIX)) return input ? 0xFFE0B43A : 0xFFC48E14;
        if (portKey.startsWith(TransferEdge.STRESS_PREFIX)) return input ? 0xFFD781D8 : 0xFFB560B8;
        return input ? Theme.SUCCESS : Theme.PRIMARY;
    }

    private void renderEdges(GuiGraphics g, int mx, int my) {
        for (TransferGraphSyncPacket.EdgeData edge : visibleEdges()) {
            TransferGraphSyncPacket.NodeData from = node(edge.fromNodeId());
            TransferGraphSyncPacket.NodeData to = node(edge.toNodeId());
            if (from == null || to == null) continue;
            int[] a = outputPos(from, edge.fromPortKey());
            int[] b = inputPos(to, edge.fromPortKey());
            boolean live = edge.enabled() && from.enabled() && to.enabled() && pageEnabled(edge.pageId());
            boolean selected = edge.id().equals(selectedEdgeId);
            int color = selected ? Theme.PRIMARY_PRESS : edgeColor(edge, live);
            canvas.drawBezier(g, a[0], a[1], b[0], b[1], laneOffset(edge), color, selected);
        }
    }

    private int edgeColor(TransferGraphSyncPacket.EdgeData edge, boolean live) {
        if (!live) return 0xFFB6C2CF;
        if (hasIssueForEdge(edge.id())) return Theme.DANGER;
        return switch (edge.health()) {
            case "HEALTHY" -> 0xFF48B77B;
            case "SOURCE_SHORTAGE" -> 0xFFE0B43F;
            case "RECEIVER_BLOCKED" -> 0xFFE675AE;
            case "DEADLOCKED" -> 0xFF98283A;
            case "DISABLED" -> 0xFFB6C2CF;
            default -> edge.fromPortKey().startsWith(TransferEdge.FLUID_PREFIX) ? 0xFF62CDBD : 0xFF74BDEB;
        };
    }

    private void renderEdgeLabel(GuiGraphics g, String label, int cx, int y) {
        float s = canvas.canvasUiScale();
        int tw = Theme.styledWidth(font, label);
        g.pose().pushPose();
        g.pose().translate(cx, y, 0);
        g.pose().scale(s, s, 1.0f);
        Theme.panel(g, -tw / 2 - 6, -4, tw + 12, 16, 8, 0xEAF7FBFF, 0x88D4E4F5);
        text(g, label, -tw / 2, 0, Theme.TEXT_MUTED);
        g.pose().popPose();
    }

    private void renderActiveLink(GuiGraphics g, int mx, int my) {
        TransferGraphSyncPacket.NodeData from = node(linkingFromNodeId);
        if (from == null) return;
        int[] a = outputPos(from, linkingFromPort);
        canvas.drawBezier(g, a[0], a[1], mx, my, 0, Theme.PRIMARY_HOVER, true);
    }

    private void renderMenu(GuiGraphics g) {
        if (menuMode == MenuMode.NONE) return;
        if (menuMode == MenuMode.GRAPH_ACTION) {
            List<TransferGraphSyncPacket.GraphOptionData> options = ClientTransferGraphCache.graphOptions();
            int rows = Math.max(1, options.size());
            int h = 8 + rows * CONTROL_DROPDOWN_ROW_H;
            crispPanel(g, menuX, menuY, CONTROL_DROPDOWN_W, h, Theme.SURFACE, Theme.BORDER_STRONG);
            if (options.isEmpty()) {
                text(g, "没有可用图", menuX + 8, menuY + 10, Theme.TEXT_MUTED);
                return;
            }
            int y = menuY + 6;
            for (TransferGraphSyncPacket.GraphOptionData option : options) {
                boolean active = option.kind().equals(currentGraphKind()) && option.id().equals(currentGraphId());
                renderDropdownRow(g, menuX + 5, y, CONTROL_DROPDOWN_W - 10, active ? "●" : "○",
                        Theme.ellipsize(font, option.label(), CONTROL_DROPDOWN_W - 34),
                        active ? Theme.SUCCESS : Theme.TEXT_FAINT,
                        option.writable() ? Theme.TEXT : Theme.TEXT_MUTED);
                y += CONTROL_DROPDOWN_ROW_H;
            }
            return;
        }
        if (menuMode == MenuMode.TEAM_MEMBER_ADD) {
            crispPanel(g, menuX, menuY, 214, 56, Theme.SURFACE, Theme.BORDER_STRONG);
            text(g, "添加团队成员 UUID", menuX + 8, menuY + 8, Theme.TEXT);
            if (pageNameEdit != null) pageNameEdit.render(g, 0, 0, 0);
            text(g, "Enter 添加为可编辑", menuX + 8, menuY + 39, Theme.TEXT_MUTED);
            return;
        }
        if (menuMode == MenuMode.PAGE_ACTION) {
            int rows = pages().size();
            int h = 8 + (rows + 1) * CONTROL_DROPDOWN_ROW_H;
            crispPanel(g, menuX, menuY, CONTROL_DROPDOWN_W, h, Theme.SURFACE, Theme.BORDER_STRONG);
            int y = menuY + 6;
            for (TransferGraphSyncPacket.PageData page : pages()) {
                renderPageDropdownRow(g, menuX + 5, y, CONTROL_DROPDOWN_W - 10, page);
                y += CONTROL_DROPDOWN_ROW_H;
            }
            renderDropdownRow(g, menuX + 5, y, CONTROL_DROPDOWN_W - 10, "+", "新建分页", Theme.PRIMARY_PRESS, Theme.PRIMARY_PRESS);
            return;
        }
        if (menuMode == MenuMode.PAGE_DELETE_CONFIRM) {
            TransferGraphSyncPacket.PageData page = page(pendingDeletePageId);
            crispPanel(g, menuX, menuY, 190, 56, Theme.SURFACE, Theme.BORDER_STRONG);
            text(g, "确认删除: " + (page == null ? "分页" : page.name()), menuX + 8, menuY + 9, Theme.TEXT);
            text(g, "确认删除", menuX + 8, menuY + 32, Theme.DANGER);
            text(g, "取消", menuX + 88, menuY + 32, Theme.TEXT_MUTED);
            return;
        }
        if (menuMode == MenuMode.PAGE_RENAME || menuMode == MenuMode.PAGE_CREATE || menuMode == MenuMode.NODE_RENAME) {
            crispPanel(g, menuX, menuY, 132, 50, Theme.SURFACE, Theme.BORDER_STRONG);
            if (pageNameEdit != null) pageNameEdit.render(g, 0, 0, 0);
            text(g, "Enter 保存", menuX + 8, menuY + 31, Theme.TEXT_MUTED);
            return;
        }
        if (menuMode == MenuMode.ROOT) {
            crispPanel(g, menuX, menuY, 158, 152, Theme.SURFACE, Theme.BORDER_STRONG);
            text(g, "新建箱子节点", menuX + 9, menuY + 10, Theme.TEXT);
            text(g, "新建中转节点", menuX + 9, menuY + 30, Theme.TEXT);
            text(g, "新建限量门", menuX + 9, menuY + 50, Theme.TEXT);
            text(g, "新建跳线入口", menuX + 9, menuY + 70, Theme.TEXT);
            text(g, "新建跳线出口", menuX + 9, menuY + 90, unboundJumpInputs().isEmpty() ? Theme.TEXT_MUTED : Theme.TEXT);
            text(g, "新建销毁节点", menuX + 9, menuY + 110, Theme.TEXT);
            text(g, "新建背包节点", menuX + 9, menuY + 130, canAddPlayerNode() ? Theme.TEXT : Theme.TEXT_MUTED);
            return;
        }
        if (menuMode == MenuMode.JUMP_OUTPUT_LIST) {
            List<TransferGraphSyncPacket.NodeData> inputs = unboundJumpInputs();
            int h = Math.max(30, Math.min(10, inputs.size()) * 18 + 10);
            crispPanel(g, menuX, menuY, 190, h, Theme.SURFACE, Theme.BORDER_STRONG);
            if (inputs.isEmpty()) {
                text(g, "没有可用跳线入口", menuX + 8, menuY + 11, Theme.TEXT_MUTED);
                return;
            }
            for (int i = 0; i < Math.min(10, inputs.size()); i++) {
                TransferGraphSyncPacket.NodeData input = inputs.get(i);
                text(g, "绑定 " + Theme.ellipsize(font, jumpLabel(input), 130), menuX + 8, menuY + 10 + i * 18, Theme.TEXT);
            }
            return;
        }
        List<TransferGraphSyncPacket.ChestData> chests = ClientTransferGraphCache.chests();
        int h = Math.max(30, Math.min(10, chests.size()) * 18 + 10);
        crispPanel(g, menuX, menuY, 190, h, Theme.SURFACE, Theme.BORDER_STRONG);
        if (chests.isEmpty()) {
            text(g, "没有可用箱子", menuX + 8, menuY + 11, Theme.TEXT_MUTED);
            return;
        }
        for (int i = 0; i < Math.min(10, chests.size()); i++) {
            TransferGraphSyncPacket.ChestData chest = chests.get(i);
            String status = "MOVING".equals(chest.status()) ? " 运动中"
                    : "OFFLINE_SIMULATED".equals(chest.status()) ? " 离线"
                    : "OFFLINE_DISABLED".equals(chest.status()) ? " 未加载" : "";
            text(g, chest.chestId() + "  " + GraphResourceUtils.shortPos(chest.pos()) + status, menuX + 8, menuY + 10 + i * 18, Theme.TEXT);
        }
    }

    private void renderDropdownRow(GuiGraphics g, int x, int y, int w, String marker, String label, int markerColor, int labelColor) {
        Theme.fillRound(g, x, y - 2, w, CONTROL_DROPDOWN_ROW_H, 6, 0x00FFFFFF);
        text(g, marker, x + 4, y + 2, markerColor);
        text(g, label, x + 24, y + 2, labelColor);
    }

    private void renderPageDropdownRow(GuiGraphics g, int x, int y, int w, TransferGraphSyncPacket.PageData page) {
        boolean active = page.id().equals(activePageId);
        int labelColor = active ? Theme.PRIMARY_PRESS : Theme.TEXT;
        Theme.fillRound(g, x, y - 2, w, CONTROL_DROPDOWN_ROW_H, 6, 0x00FFFFFF);
        text(g, page.enabled() ? "●" : "○", x + 4, y + 2, page.enabled() ? Theme.SUCCESS : Theme.TEXT_FAINT);
        text(g, Theme.ellipsize(font, page.name(), w - 24 - PAGE_RENAME_ZONE_W), x + 24, y + 2, labelColor);
        text(g, "改", x + w - PAGE_RENAME_ZONE_W + 8, y + 2, Theme.PRIMARY_PRESS);
    }

    private void renderPopup(GuiGraphics g, int mx, int my, float partialTick) {
        if (popupMode == PopupMode.NONE) return;
        g.pose().pushPose();
        g.pose().translate(0, 0, 320);
        if (popupMode == PopupMode.NODE) renderNodePopup(g);
        else if (popupMode == PopupMode.EDGE) renderEdgePopup(g);
        else if (popupMode == PopupMode.SEARCH) renderSearchPopup(g, mx, my, partialTick);
        else if (popupMode == PopupMode.HELP) renderHelpPopup(g);
        else if (popupMode == PopupMode.EXIT_CONFIRM) renderExitConfirm(g);
        g.pose().popPose();
    }

    private void renderHelpPopup(GuiGraphics g) {
        int x = clamp(width - HELP_W - 10, 6, Math.max(6, width - HELP_W - 6));
        int y = HEADER_H + 8;
        g.fill(0, 0, width, height, 0x22000000);
        crispPanel(g, x, y, HELP_W, HELP_H, Theme.SURFACE, Theme.BORDER_STRONG);
        text(g, "可视化传输说明", x + 12, y + 12, Theme.TEXT);
        TransferGraphGuiTextures.icon(g, TransferGraphGuiTextures.Icon.CLOSE, x + HELP_W - 24, y + 8);
        int yy = y + 36;
        yy = renderHelpSection(g, x + 12, yy, "基础连接", List.of(
                "右键空白处创建节点，拖动节点调整布局。",
                "从右侧输出点拖到左侧输入点即可连线。"));
        yy = renderHelpSection(g, x + 12, yy + 2, "中转节点", List.of(
                "聚合多个输入，并按资源类型继续分发。",
                "多条输出会轮转尝试，降低单一路径饥饿。"));
        yy = renderHelpSection(g, x + 12, yy + 2, "销毁节点", List.of(
                "它是终点，只处理其他输出之后仍剩余的产物。",
                "连到它的线也可以限速，用来控制销毁速度。"));
        yy = renderHelpSection(g, x + 12, yy + 2, "限速与统计", List.of(
                "点击线条可按物品设置“每几秒传几个”。",
                "线色来自实际传输：未测、顺畅、生产不足、接收受阻、堵死。"));
        renderHelpSection(g, x + 12, yy + 2, "保存", List.of(
                "修改先存在草稿里，点击保存后才会生效。",
                "如果连接方向或节点规则有错误，服务端会拒绝保存。"));
    }

    private int renderHelpSection(GuiGraphics g, int x, int y, String title, List<String> lines) {
        text(g, title, x, y, Theme.PRIMARY_PRESS);
        int yy = y + 14;
        for (String line : lines) {
            text(g, line, x + 8, yy, Theme.TEXT_MUTED);
            yy += 12;
        }
        return yy + 4;
    }

    private void renderNodePopup(GuiGraphics g) {
        TransferGraphSyncPacket.NodeData node = node(selectedNodeId);
        if (node == null) return;
        if (node.type().equals("LIMIT_GATE")) {
            renderLimitGatePopup(g, node);
            return;
        }
        if (node.type().equals("JUMP_INPUT")) {
            renderJumpInputPopup(g, node);
            return;
        }
        if (node.type().equals("JUMP_OUTPUT")) {
            renderJumpOutputPopup(g, node);
            return;
        }
        boolean hasAddAction = node.type().equals("CHEST") || node.type().equals("PLAYER_INVENTORY") || node.type().equals("REROUTE");
        int h = hasAddAction ? 82 : 62;
        int w = 150;
        crispPanel(g, popupX, popupY, w, h, Theme.SURFACE, hasIssueForNode(node.id()) ? Theme.DANGER : Theme.BORDER_STRONG);
        String title = node.type().equals("TRASH") ? "销毁节点"
                : node.type().equals("PLAYER_INVENTORY") ? playerInventoryTitle(node)
                : node.type().equals("REROUTE") ? "中转节点"
                : node.chestId();
        text(g, Theme.ellipsize(font, title, 132), popupX + 9, popupY + 9, Theme.TEXT);
        renderNodePopupRow(g, popupX + 8, popupY + 26, 134, node.enabled() ? "禁用节点" : "启用节点", Theme.PRIMARY_PRESS);
        if (hasAddAction) {
            String add = node.type().equals("PLAYER_INVENTORY") ? "添加补货"
                    : node.type().equals("REROUTE") ? "添加输出"
                    : "添加过滤";
            renderNodePopupRow(g, popupX + 8, popupY + 44, 134, add, Theme.PRIMARY_PRESS);
        }
        renderNodePopupRow(g, popupX + 8, popupY + (hasAddAction ? 62 : 44), 134, "删除节点", Theme.DANGER);
    }

    private void renderJumpInputPopup(GuiGraphics g, TransferGraphSyncPacket.NodeData node) {
        boolean canCreateOutput = !jumpBound(node);
        int h = canCreateOutput ? 98 : 80;
        crispPanel(g, popupX, popupY, 158, h, Theme.SURFACE, hasIssueForNode(node.id()) ? Theme.DANGER : Theme.BORDER_STRONG);
        text(g, Theme.ellipsize(font, "跳线入口 " + jumpLabel(node), 140), popupX + 9, popupY + 9, Theme.TEXT);
        renderNodePopupRow(g, popupX + 8, popupY + 26, 142, node.enabled() ? "禁用节点" : "启用节点", Theme.PRIMARY_PRESS);
        renderNodePopupRow(g, popupX + 8, popupY + 44, 142, "重命名", Theme.PRIMARY_PRESS);
        if (canCreateOutput) renderNodePopupRow(g, popupX + 8, popupY + 62, 142, "在旁创建出口", Theme.PRIMARY_PRESS);
        renderNodePopupRow(g, popupX + 8, popupY + (canCreateOutput ? 80 : 62), 142, "删除节点", Theme.DANGER);
    }

    private void renderJumpOutputPopup(GuiGraphics g, TransferGraphSyncPacket.NodeData node) {
        int h = 80;
        crispPanel(g, popupX, popupY, 158, h, Theme.SURFACE, hasIssueForNode(node.id()) ? Theme.DANGER : Theme.BORDER_STRONG);
        text(g, Theme.ellipsize(font, "跳线出口 " + jumpLabel(node), 140), popupX + 9, popupY + 9, Theme.TEXT);
        renderNodePopupRow(g, popupX + 8, popupY + 26, 142, node.enabled() ? "禁用节点" : "启用节点", Theme.PRIMARY_PRESS);
        renderNodePopupRow(g, popupX + 8, popupY + 44, 142, "重命名", Theme.PRIMARY_PRESS);
        renderNodePopupRow(g, popupX + 8, popupY + 62, 142, "删除节点", Theme.DANGER);
    }

    private void renderLimitGatePopup(GuiGraphics g, TransferGraphSyncPacket.NodeData node) {
        int w = 202;
        int h = 138;
        crispPanel(g, popupX, popupY, w, h, Theme.SURFACE, hasIssueForNode(node.id()) ? Theme.DANGER : Theme.BORDER_STRONG);
        text(g, "限量门", popupX + 9, popupY + 9, Theme.TEXT);
        text(g, "放行区间", popupX + 9, popupY + 31, Theme.TEXT_MUTED);
        text(g, "[", popupX + 68, popupY + 31, Theme.TEXT_MUTED);
        renderGateInput(g, popupX + 78, popupY + 26, 44, gateDisplayValue(node.gateMin(), true), focusedGateField == GateField.MIN);
        text(g, ",", popupX + 127, popupY + 31, Theme.TEXT_MUTED);
        renderGateInput(g, popupX + 136, popupY + 26, 44, gateDisplayValue(node.gateMax(), false), focusedGateField == GateField.MAX);
        text(g, "]", popupX + 184, popupY + 31, Theme.TEXT_MUTED);
        String resource = incomingResource(node.id());
        text(g, "单位 " + resourceUnit(resource), popupX + 9, popupY + 54, Theme.TEXT_FAINT);
        text(g, "检测对象", popupX + 9, popupY + 76, Theme.TEXT_MUTED);
        renderGateScopeButton(g, popupX + 70, popupY + 69, 54, "目标箱", !node.gateCheckSource());
        renderGateScopeButton(g, popupX + 130, popupY + 69, 54, "来源箱", node.gateCheckSource());
        text(g, node.gateCheckSource() ? "来源现有量在区间内才放行" : "目标现有量在区间内才放行", popupX + 9, popupY + 96, Theme.TEXT_FAINT);
        renderNodePopupRow(g, popupX + 8, popupY + 114, 56, node.enabled() ? "禁用" : "启用", Theme.PRIMARY_PRESS);
        renderNodePopupRow(g, popupX + 72, popupY + 114, 56, "删除", Theme.DANGER);
    }

    private void renderGateInput(GuiGraphics g, int x, int y, int w, String value, boolean focused) {
        Theme.panel(g, x, y, w, 18, 5, focused ? 0xFFFFFFFF : Theme.SURFACE_SUNK, focused ? Theme.PRIMARY : Theme.BORDER);
        text(g, Theme.ellipsize(font, value.isEmpty() ? "∞" : value, w - 8), x + 6, y + 5, focused ? Theme.TEXT : Theme.TEXT_MUTED);
    }

    private void renderGateScopeButton(GuiGraphics g, int x, int y, int w, String label, boolean active) {
        Theme.panel(g, x, y, w, 18, 5, active ? Theme.PRIMARY_SOFT : Theme.SURFACE_SUNK, active ? Theme.PRIMARY : Theme.BORDER);
        text(g, label, x + Math.max(4, (w - font.width(label)) / 2), y + 5, active ? Theme.PRIMARY_PRESS : Theme.TEXT_MUTED);
    }

    private void renderNodePopupRow(GuiGraphics g, int x, int y, int w, String label, int color) {
        Theme.fillRound(g, x, y, w, 16, 6, 0x00FFFFFF);
        text(g, label, x + 4, y + 4, color);
    }

    private void renderReroutePopup(GuiGraphics g, TransferGraphSyncPacket.NodeData node) {
        float s = canvas.canvasUiScale();
        List<TransferGraphSyncPacket.NodeFlowData> flows = rerouteFlowRows(node);
        int rows = Math.min(5, flows.size());
        int h = reroutePopupHeight(node);
        g.pose().pushPose();
        g.pose().translate(popupX, popupY, 0);
        g.pose().scale(s, s, 1.0f);
        crispPanel(g, 0, 0, 206, h, Theme.SURFACE, hasIssueForNode(node.id()) ? Theme.DANGER : Theme.BORDER_STRONG);
        text(g, "中转点", 9, 9, Theme.TEXT);
        text(g, "+ 输出", 154, 9, Theme.PRIMARY_PRESS);
        text(g, "入", 112, 29, Theme.TEXT_MUTED);
        text(g, "出", 158, 29, Theme.TEXT_MUTED);
        int y = 45;
        if (flows.isEmpty()) {
            text(g, "暂无流量记录", 10, y, Theme.TEXT_MUTED);
        } else {
            for (int i = 0; i < rows; i++) {
                TransferGraphSyncPacket.NodeFlowData flow = flows.get(i);
                Item item = GraphResourceUtils.resolveItem(flow.itemId());
                if (item != null && zoom > 0.18) g.renderItem(new ItemStack(item), 10, y - 5);
                else if (GraphResourceUtils.isFluidResource(flow.itemId()) && zoom > 0.18) g.renderItem(new ItemStack(Items.WATER_BUCKET), 10, y - 5);
                text(g, Theme.ellipsize(font, GraphResourceUtils.shortResource(flow.itemId()), 66), 30, y, Theme.TEXT);
                text(g, GraphResourceUtils.resourceRateLabel(flow.inputRatePerMinute(), flow.itemId()), 100, y, Theme.SUCCESS);
                text(g, GraphResourceUtils.resourceRateLabel(flow.outputRatePerMinute(), flow.itemId()), 146, y, Theme.PRIMARY_PRESS);
                if (node.filterItemIds().contains(GraphResourceUtils.filterFromPort(flow.itemId()))) text(g, "×", 190, y, Theme.DANGER);
                y += 20;
            }
        }
        text(g, node.enabled() ? "禁用节点" : "启用节点", 9, h - 18, Theme.PRIMARY_PRESS);
        text(g, "删除节点", 72, h - 18, Theme.DANGER);
        g.pose().popPose();
    }

    private void renderEdgePopup(GuiGraphics g) {
        TransferGraphSyncPacket.EdgeData edge = edge(selectedEdgeId);
        if (edge == null) return;
        float s = canvas.canvasUiScale();
        List<TransferGraphSyncPacket.EdgeItemRateData> rows = GraphResourceUtils.edgeRateRows(edge);
        int panelH = edgePopupHeight(rows);
        int panelW = edgePopupWidth(rows);
        g.pose().pushPose();
        g.pose().translate(popupX, popupY, 0);
        g.pose().scale(s, s, 1.0f);
        crispPanel(g, 0, 0, panelW, panelH, Theme.SURFACE, hasIssueForEdge(edge.id()) ? Theme.DANGER : Theme.BORDER_STRONG);
        text(g, GraphResourceUtils.portLabel(edge.fromPortKey()), 9, 9, Theme.TEXT);
        text(g, "+ 资源", panelW - 48, 9, Theme.PRIMARY_PRESS);
        text(g, "状态", 9, 29, Theme.TEXT_MUTED);
        text(g, edgeStatusLabel(edge), 38, 29, edgeStatusColor(edge));
        text(g, "每 X 秒传 Y", 126, 49, Theme.TEXT_MUTED);
        text(g, "实际", 242, 49, Theme.TEXT_MUTED);
        int y = 64;
        for (TransferGraphSyncPacket.EdgeItemRateData row : rows) {
            renderEdgeRateRow(g, row, y);
            y += 24;
        }
        if (rows.isEmpty()) text(g, "传输后会自动出现物品行", 10, y, Theme.TEXT_MUTED);
        chip(g, 8, panelH - 25, 42, 18, Theme.SURFACE_ALT, Theme.BORDER);
        text(g, edge.enabled() ? "禁用" : "启用", 17, panelH - 20, Theme.PRIMARY_PRESS);
        chip(g, 60, panelH - 25, 42, 18, Theme.SURFACE_ALT, Theme.BORDER);
        text(g, "删除", 69, panelH - 20, Theme.DANGER);
        chip(g, panelW - 58, panelH - 25, 50, 18, Theme.PRIMARY_SOFT, Theme.PRIMARY);
        text(g, savePending ? "保存中" : "保存", panelW - 48, panelH - 20, Theme.PRIMARY_PRESS);
        g.pose().popPose();
    }

    private void renderEdgeRateRow(GuiGraphics g, TransferGraphSyncPacket.EdgeItemRateData row, int y) {
        Item item = GraphResourceUtils.resolveItem(row.itemId());
        if (item != null && zoom > 0.18) g.renderItem(new ItemStack(item), 10, y - 5);
        else if (GraphResourceUtils.isFluidResource(row.itemId()) && zoom > 0.18) g.renderItem(new ItemStack(Items.WATER_BUCKET), 10, y - 5);
        chip(g, 31, y - 3, 28, 16, row.rateLimitEnabled() ? Theme.PRIMARY_SOFT : Theme.SURFACE_ALT,
                row.rateLimitEnabled() ? Theme.PRIMARY : Theme.BORDER);
        text(g, row.rateLimitEnabled() ? "开" : "关", 39, y + 1, row.rateLimitEnabled() ? Theme.PRIMARY_PRESS : Theme.TEXT_MUTED);
        text(g, Theme.ellipsize(font, GraphResourceUtils.shortResource(row.itemId()), 58), 64, y, Theme.TEXT);
        String seconds = row.itemId().equals(selectedRateItemId) ? rateSecondsValue : String.valueOf(row.rateLimitSeconds());
        String items = row.itemId().equals(selectedRateItemId) ? rateItemsValue : String.valueOf(row.rateLimitItems());
        int secX = 126;
        int itemX = 176;
        renderEdgeInput(g, secX, y - 4, 24, seconds, focusedEdgeField == EdgeField.SECONDS && row.itemId().equals(selectedRateItemId));
        text(g, "秒", secX + 28, y + 1, Theme.TEXT_MUTED);
        renderEdgeInput(g, itemX, y - 4, 34, items, focusedEdgeField == EdgeField.ITEMS && row.itemId().equals(selectedRateItemId));
        text(g, GraphResourceUtils.isFluidResource(row.itemId()) ? "mB" : "个", itemX + 38, y + 1, Theme.TEXT_MUTED);
        text(g, GraphResourceUtils.resourceRateLabel(row.actualRatePerMinute(), row.itemId()), 242, y, GraphResourceUtils.healthTextColor(row.health()));
    }

    private int edgePopupWidth(List<TransferGraphSyncPacket.EdgeItemRateData> rows) {
        return 300;
    }

    private int edgePopupHeight(List<TransferGraphSyncPacket.EdgeItemRateData> rows) {
        return 74 + rows.size() * 24 + 44;
    }

    private boolean edgePopupOpen() {
        return selectedEdgeId != null && edge(selectedEdgeId) != null;
    }

    private boolean edgePopupContains(double mx, double my) {
        TransferGraphSyncPacket.EdgeData edge = edge(selectedEdgeId);
        if (edge == null) return false;
        double lx = scaledPopupLocalX(mx);
        double ly = scaledPopupLocalY(my);
        List<TransferGraphSyncPacket.EdgeItemRateData> rows = GraphResourceUtils.edgeRateRows(edge);
        return inside(lx, ly, 0, 0, edgePopupWidth(rows), edgePopupHeight(rows));
    }

    private void renderEdgeInput(GuiGraphics g, int x, int y, int w, String value, boolean focused) {
        Theme.panel(g, x, y, w, 18, 5, focused ? 0xFFFFFFFF : Theme.SURFACE_SUNK, focused ? Theme.PRIMARY : Theme.BORDER);
        String shown = Theme.ellipsize(font, value.isEmpty() ? "0" : value, w - 8);
        text(g, shown, x + 6, y + 5, focused ? Theme.TEXT : Theme.TEXT_MUTED);
    }

    private String edgeStatusLabel(TransferGraphSyncPacket.EdgeData edge) {
        if (!edge.enabled()) return "已禁用";
        if (hasIssueForEdge(edge.id())) return "配置错误";
        return switch (edge.health()) {
            case "HEALTHY" -> "顺畅传输";
            case "SOURCE_SHORTAGE" -> "生产不足";
            case "RECEIVER_BLOCKED" -> "接收受阻";
            case "DEADLOCKED" -> "堵死";
            case "DISABLED" -> "已禁用";
            default -> "尚未测速";
        };
    }

    private int edgeStatusColor(TransferGraphSyncPacket.EdgeData edge) {
        if (!edge.enabled()) return Theme.TEXT_MUTED;
        if (hasIssueForEdge(edge.id())) return Theme.DANGER;
        return GraphResourceUtils.healthTextColor(edge.health());
    }

    private void renderSearchPopup(GuiGraphics g, int mx, int my, float partialTick) {
        List<String> results = searchResults();
        int panelH = searchPopupHeight(results);
        crispPanel(g, popupX, popupY, SEARCH_W, panelH, Theme.SURFACE, Theme.BORDER_STRONG);
        TransferGraphSyncPacket.NodeData selectedNode = node(selectedNodeId);
        String title = searchForEdgeItem ? "添加资源行"
                : selectedNode != null && selectedNode.type().equals("PLAYER_INVENTORY") ? "添加补货项"
                : selectedNode != null && selectedNode.type().equals("REROUTE") ? "添加输出"
                : searchKind == SearchKind.ITEM ? "添加物品过滤"
                : searchKind == SearchKind.FLUID ? "添加流体过滤"
                : "添加过滤";
        text(g, title, popupX + 9, popupY + 10, Theme.TEXT);
        TransferGraphGuiTextures.icon(g, TransferGraphGuiTextures.Icon.CLOSE, popupX + SEARCH_W - 24, popupY + 7);
        Theme.panel(g, popupX + 8, popupY + 26, SEARCH_W - 16, 18, 5, searchFocused ? 0xFFFFFFFF : Theme.SURFACE_SUNK, searchFocused ? Theme.PRIMARY : Theme.BORDER);
        String shown = searchValue.isEmpty() ? "搜索资源" : searchValue;
        int textColor = searchValue.isEmpty() ? Theme.TEXT_FAINT : Theme.TEXT;
        text(g, Theme.ellipsize(font, shown + (searchFocused && (graphSyncTicker / 10) % 2 == 0 ? "_" : ""), SEARCH_W - 28), popupX + 13, popupY + 31, textColor);
        int y = popupY + 55;
        for (String resourceId : results) {
            Item item = GraphResourceUtils.resolveItem(resourceId);
            if (item != null) g.renderItem(new ItemStack(item), popupX + 10, y - 6);
            else if (GraphResourceUtils.isFluidResource(resourceId)) g.renderItem(new ItemStack(Items.WATER_BUCKET), popupX + 10, y - 6);
            text(g, Theme.ellipsize(font, GraphResourceUtils.shortResource(resourceId), SEARCH_W - 42), popupX + 32, y - 1, Theme.TEXT);
            y += 20;
        }
    }

    private void renderExitConfirm(GuiGraphics g) {
        int w = 246;
        int h = 94;
        int x = (width - w) / 2;
        int y = (height - h) / 2;
        g.fill(0, 0, width, height, Theme.SCRIM);
        crispPanel(g, x, y, w, h, Theme.SURFACE, Theme.BORDER_STRONG);
        text(g, "有未保存的改动", x + 12, y + 12, Theme.TEXT);
        text(g, "退出前请选择如何处理。", x + 12, y + 31, Theme.TEXT_MUTED);
        chip(g, x + 12, y + 61, 94, 22, Theme.SURFACE_ALT, Theme.BORDER);
        text(g, "不保存退出", x + 25, y + 68, Theme.DANGER);
        chip(g, x + 118, y + 61, 112, 22, Theme.PRIMARY_SOFT, Theme.PRIMARY);
        text(g, savePending ? "保存中" : "保存并退出", x + 134, y + 68, Theme.PRIMARY_PRESS);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        ensureDraft();
        if (popupMode == PopupMode.EXIT_CONFIRM) return handlePopupClick(mx, my, button);
        if (button == 0 && popupMode == PopupMode.EDGE && edgePopupOpen() && !edgePopupContains(mx, my)) {
            if (applyFocusedEdgeEdit()) saveDraft();
        }
        if (handleMenuClick(mx, my, button)) return true;
        if (button == 0 && handleHeaderClick(mx, my)) return true;
        if (handlePopupClick(mx, my, button)) return true;
        if (button == 1) {
            TransferGraphSyncPacket.NodeData node = nodeAt(mx, my);
            if (node != null) {
                selectedNodeId = node.id();
                selectedEdgeId = null;
                popupMode = PopupMode.NODE;
                menuMode = MenuMode.NONE;
                popupX = (int) mx + 8;
                popupY = (int) my + 8;
                return true;
            }
            menuMode = MenuMode.ROOT;
            popupMode = PopupMode.NONE;
            menuX = (int) mx;
            menuY = (int) my;
            pendingNodeX = cx(mx);
            pendingNodeY = cy(my);
            return true;
        }
        if (button != 0) return super.mouseClicked(mx, my, button);

        if (linkingFromNodeId != null) {
            PortHit input = inputAt(mx, my);
            if (input != null) {
                createEdge(input.nodeId(), input.portKey());
                return true;
            }
        }
        PortHit port = outputAt(mx, my);
        if (port != null) {
            linkingFromNodeId = port.nodeId;
            linkingFromPort = port.portKey;
            selectedNodeId = port.nodeId;
            selectedEdgeId = null;
            popupMode = PopupMode.NONE;
            return true;
        }
        String edgeId = edgeNear(mx, my);
        if (edgeId != null) {
            selectedEdgeId = edgeId;
            selectedNodeId = null;
            popupMode = PopupMode.EDGE;
            popupX = (int) mx + 8;
            popupY = (int) my + 8;
            focusedEdgeField = EdgeField.NONE;
            TransferGraphSyncPacket.EdgeData edge = edge(edgeId);
            if (edge != null) {
                selectedRateItemId = GraphResourceUtils.defaultRateItemId(edge);
                syncRateEditors(edge);
            }
            return true;
        }
        TransferGraphSyncPacket.NodeData node = nodeAt(mx, my);
        if (node != null) {
            selectedNodeId = node.id();
            selectedEdgeId = null;
            if (nodeExpandHit(node, mx, my)) {
                toggleNodeExpanded(node.id());
                popupMode = PopupMode.NONE;
                return true;
            }
            if (node.type().equals("REROUTE") && handleRerouteNodeClick(node, mx, my)) return true;
            SearchKind quickFilter = chestQuickFilterHit(node, mx, my);
            if (quickFilter != null) {
                openSearchPopup(node, mx, my, quickFilter);
                return true;
            }
            FilterHit filter = filterHit(node, mx, my);
            if (filter != null) {
                if (!filter.consumed()) {
                    selectedItemId = filter.itemId();
                    popupMode = PopupMode.NONE;
                    linkingFromNodeId = node.id();
                    linkingFromPort = GraphResourceUtils.filterPort(filter.itemId());
                }
                return true;
            }
            if (playerRuleHit(node, mx, my)) return true;
            if (chestAddHit(node, mx, my)) {
                openSearchPopup(node, mx, my, SearchKind.ALL);
                return true;
            }
            int baseX = nodeScreenX(node);
            int baseY = nodeScreenY(node);
            dragOffX = (int) mx - baseX;
            dragOffY = (int) my - baseY;
            dragPreviewX = baseX;
            dragPreviewY = baseY;
            draggingNodeId = node.id();
            dragMoved = false;
            popupMode = PopupMode.NONE;
            popupX = baseX + scaled(nodeWidth(node)) + 10;
            popupY = baseY;
            return true;
        }
        popupMode = PopupMode.NONE;
        focusedEdgeField = EdgeField.NONE;
        selectedNodeId = null;
        selectedEdgeId = null;
        return true;
    }

    private boolean handleHeaderClick(double mx, double my) {
        int x = controlX;
        int y = controlY;
        if (inside(mx, my, x, y, CONTROL_W, CONTROL_H)) {
            draggingControls = true;
            controlsMoved = false;
            controlDragOffX = (int) mx - controlX;
            controlDragOffY = (int) my - controlY;
            menuMode = MenuMode.NONE;
            popupMode = PopupMode.NONE;
            return true;
        }

        if (!controlsExpanded) return false;
        int fy = y + CONTROL_H + 8;
        if (!inside(mx, my, x, fy, CONTROL_PANEL_W, CONTROL_PANEL_H)) return false;
        if (inside(mx, my, x, fy, CONTROL_PANEL_W, 30)) {
            draggingControls = true;
            controlsMoved = false;
            controlDragOffX = (int) mx - controlX;
            controlDragOffY = (int) my - controlY;
            menuMode = MenuMode.NONE;
            popupMode = PopupMode.NONE;
            return true;
        }
        if (inside(mx, my, x + CONTROL_PANEL_W - 30, fy + 8, 22, 22)) {
            controlsExpanded = false;
            return true;
        }
        if (inside(mx, my, x + 10, fy + 36, 64, 22)) {
            menuMode = MenuMode.PAGE_ACTION;
            menuX = x + 10;
            menuY = fy + 58;
            popupMode = PopupMode.NONE;
            return true;
        }
        if (inside(mx, my, x + 82, fy + 36, 64, 22)) {
            menuMode = MenuMode.GRAPH_ACTION;
            menuX = x + 40;
            menuY = fy + 58;
            popupMode = PopupMode.NONE;
            return true;
        }
        int bx = x + 10;
        int by = fy + 68;
        if (inside(mx, my, bx, by, 26, 23)) {
            saveDraft();
            return true;
        }
        if (inside(mx, my, bx + 32, by, 26, 23)) {
            menuMode = MenuMode.PAGE_ACTION;
            menuX = x + 10;
            menuY = fy + 58;
            popupMode = PopupMode.NONE;
            return true;
        }
        if (inside(mx, my, bx + 64, by, 26, 23)) {
            menuMode = MenuMode.PAGE_CREATE;
            menuX = bx + 64;
            menuY = fy + CONTROL_PANEL_H + 4;
            if (pageNameEdit != null) {
                pageNameEdit.setMaxLength(24);
                pageNameEdit.setValue("新页面");
                pageNameEdit.setFocused(true);
            }
            popupMode = PopupMode.NONE;
            return true;
        }
        if (inside(mx, my, bx + 96, by, 26, 23)) {
            popupMode = popupMode == PopupMode.HELP ? PopupMode.NONE : PopupMode.HELP;
            menuMode = MenuMode.NONE;
            focusedEdgeField = EdgeField.NONE;
            return true;
        }
        if (inside(mx, my, x + 84, fy + 94, 54, 18)) {
            discardDraft();
            return true;
        }
        return true;
    }

    private boolean handleMenuClick(double mx, double my, int button) {
        if (menuMode == MenuMode.NONE) return false;
        if (button != 0) {
            menuMode = MenuMode.NONE;
            return true;
        }
        if (menuMode == MenuMode.GRAPH_ACTION) {
            List<TransferGraphSyncPacket.GraphOptionData> options = ClientTransferGraphCache.graphOptions();
            int row = ((int) my - (menuY + 6)) / CONTROL_DROPDOWN_ROW_H;
            if (mx >= menuX && mx < menuX + CONTROL_DROPDOWN_W && row >= 0 && row < options.size()) {
                TransferGraphSyncPacket.GraphOptionData option = options.get(row);
                requestGraph(option.kind(), option.id());
            }
            menuMode = MenuMode.NONE;
            return true;
        }
        if (menuMode == MenuMode.TEAM_MEMBER_ADD) {
            if (pageNameEdit != null && pageNameEdit.mouseClicked(mx, my, button)) return true;
            return true;
        }
        if (menuMode == MenuMode.PAGE_ACTION) {
            if (mx < menuX || mx >= menuX + CONTROL_DROPDOWN_W) {
                menuMode = MenuMode.NONE;
                return true;
            }
            int row = ((int) my - (menuY + 6)) / CONTROL_DROPDOWN_ROW_H;
            if (row >= 0 && row < pages().size()) {
                TransferGraphSyncPacket.PageData page = pages().get(row);
                int rowY = menuY + 6 + row * CONTROL_DROPDOWN_ROW_H;
                if (inside(mx, my, menuX + CONTROL_DROPDOWN_W - PAGE_RENAME_ZONE_W - 5, rowY - 2,
                        PAGE_RENAME_ZONE_W, CONTROL_DROPDOWN_ROW_H)) {
                    openPageRename(page);
                } else {
                    activePageId = page.id();
                    menuMode = MenuMode.NONE;
                }
                return true;
            }
            int createY = menuY + 6 + pages().size() * CONTROL_DROPDOWN_ROW_H;
            if (my >= createY && my < createY + CONTROL_DROPDOWN_ROW_H) {
                menuMode = MenuMode.PAGE_CREATE;
                if (pageNameEdit != null) {
                    pageNameEdit.setMaxLength(24);
                    pageNameEdit.setValue("新页面");
                    pageNameEdit.setFocused(true);
                }
                return true;
            }
            menuMode = MenuMode.NONE;
            return true;
        }
        if (menuMode == MenuMode.PAGE_DELETE_CONFIRM) {
            if (my >= menuY + 28 && my < menuY + 48) {
                if (mx < menuX + 80) deletePage(pendingDeletePageId);
                menuMode = MenuMode.PAGE_ACTION;
            } else {
                menuMode = MenuMode.PAGE_ACTION;
            }
            return true;
        }
        if (menuMode == MenuMode.PAGE_RENAME || menuMode == MenuMode.PAGE_CREATE || menuMode == MenuMode.NODE_RENAME) {
            if (pageNameEdit != null && pageNameEdit.mouseClicked(mx, my, button)) return true;
            return true;
        }
        if (menuMode == MenuMode.ROOT) {
            if (mx >= menuX && mx < menuX + 158 && my >= menuY && my < menuY + 152) {
                if (my < menuY + 24) menuMode = MenuMode.CHEST_LIST;
                else if (my < menuY + 48) {
                    addRerouteNode(activePageId, pendingNodeX, pendingNodeY);
                    menuMode = MenuMode.NONE;
                } else if (my < menuY + 68) {
                    addLimitGateNode(activePageId, pendingNodeX, pendingNodeY);
                    menuMode = MenuMode.NONE;
                } else if (my < menuY + 88) {
                    addJumpInputNode(activePageId, pendingNodeX, pendingNodeY);
                    menuMode = MenuMode.NONE;
                } else if (my < menuY + 108) {
                    if (!unboundJumpInputs().isEmpty()) menuMode = MenuMode.JUMP_OUTPUT_LIST;
                    else menuMode = MenuMode.NONE;
                } else if (my < menuY + 128) {
                    addTrashNode(activePageId, pendingNodeX, pendingNodeY);
                    menuMode = MenuMode.NONE;
                } else {
                    if (canAddPlayerNode()) addPlayerInventoryNode(activePageId, pendingNodeX, pendingNodeY);
                    menuMode = MenuMode.NONE;
                }
            } else menuMode = MenuMode.NONE;
            return true;
        }
        if (menuMode == MenuMode.JUMP_OUTPUT_LIST) {
            List<TransferGraphSyncPacket.NodeData> inputs = unboundJumpInputs();
            int idx = ((int) my - menuY - 6) / 18;
            if (mx >= menuX && mx < menuX + 190 && idx >= 0 && idx < Math.min(10, inputs.size())) {
                addJumpOutputNode(inputs.get(idx), pendingNodeX, pendingNodeY);
            }
            menuMode = MenuMode.NONE;
            return true;
        }
        List<TransferGraphSyncPacket.ChestData> chests = ClientTransferGraphCache.chests();
        int idx = ((int) my - menuY - 6) / 18;
        if (mx >= menuX && mx < menuX + 190 && idx >= 0 && idx < Math.min(10, chests.size())) {
            addChestNode(chests.get(idx), pendingNodeX, pendingNodeY);
        }
        menuMode = MenuMode.NONE;
        return true;
    }

    private boolean handlePopupClick(double mx, double my, int button) {
        if (popupMode == PopupMode.NONE) return false;
        if (popupMode == PopupMode.HELP) {
            if (button != 0) {
                popupMode = PopupMode.NONE;
                return true;
            }
            int x = clamp(width - HELP_W - 10, 6, Math.max(6, width - HELP_W - 6));
            int y = HEADER_H + 8;
            if (inside(mx, my, x + HELP_W - 24, y + 8, 20, 20) || !inside(mx, my, x, y, HELP_W, HELP_H)) {
                popupMode = PopupMode.NONE;
            }
            return true;
        }
        if (button != 0) return false;
        if (popupMode == PopupMode.EXIT_CONFIRM) {
            int w = 246;
            int h = 94;
            int x = (width - w) / 2;
            int y = (height - h) / 2;
            if (inside(mx, my, x + 12, y + 61, 94, 22)) {
                dirty = false;
                exitAfterSave = false;
                onClose();
                return true;
            }
            if (inside(mx, my, x + 118, y + 61, 112, 22)) {
                exitAfterSave = true;
                saveDraft();
                return true;
            }
            if (!inside(mx, my, x, y, w, h)) {
                popupMode = PopupMode.NONE;
                exitAfterSave = false;
            }
            return true;
        }
        if (popupMode == PopupMode.SEARCH) {
            int panelH = searchPopupHeight(searchResults());
            if (!inside(mx, my, popupX, popupY, SEARCH_W, panelH)) {
                popupMode = PopupMode.NONE;
                draggingPopup = false;
                searchFocused = false;
                return true;
            }
            if (inside(mx, my, popupX + SEARCH_W - 26, popupY + 5, 20, 20)) {
                popupMode = PopupMode.NONE;
                draggingPopup = false;
                searchFocused = false;
                return true;
            }
            if (inside(mx, my, popupX, popupY, SEARCH_W, 22)) {
                draggingPopup = true;
                popupDragOffX = (int) mx - popupX;
                popupDragOffY = (int) my - popupY;
                return true;
            }
            if (inside(mx, my, popupX + 8, popupY + 26, SEARCH_W - 16, 18)) {
                searchFocused = true;
                return true;
            }
            int idx = ((int) my - (popupY + 50)) / 20;
            List<String> results = searchResults();
            if (mx >= popupX && mx < popupX + SEARCH_W && idx >= 0 && idx < results.size() && searchForEdgeItem && selectedEdgeId != null) {
                addEdgeItemRate(selectedEdgeId, results.get(idx));
                popupMode = PopupMode.EDGE;
                searchForEdgeItem = false;
                searchFocused = false;
                return true;
            }
            if (mx >= popupX && mx < popupX + SEARCH_W && idx >= 0 && idx < results.size() && selectedNodeId != null) {
                TransferGraphSyncPacket.NodeData node = node(selectedNodeId);
                if (node != null && node.type().equals("PLAYER_INVENTORY")) addReplenishRule(selectedNodeId, results.get(idx), 64);
                else addFilterItem(selectedNodeId, results.get(idx));
                popupMode = PopupMode.NONE;
                searchFocused = false;
                return true;
            }
            return true;
        }
        if (popupMode == PopupMode.NODE && selectedNodeId != null) {
            TransferGraphSyncPacket.NodeData node = node(selectedNodeId);
            if (node == null) return true;
            if (node.type().equals("LIMIT_GATE")) return handleLimitGatePopupClick(node, mx, my);
            if (node.type().equals("JUMP_INPUT")) return handleJumpInputPopupClick(node, mx, my);
            if (node.type().equals("JUMP_OUTPUT")) return handleJumpOutputPopupClick(node, mx, my);
            boolean hasAddAction = node.type().equals("CHEST") || node.type().equals("PLAYER_INVENTORY") || node.type().equals("REROUTE");
            int popupH = hasAddAction ? 82 : 62;
            if (!inside(mx, my, popupX, popupY, 150, popupH)) {
                popupMode = PopupMode.NONE;
                return true;
            }
            int row = ((int) my - popupY - 24) / 18;
            if (row == 0) toggleNode(selectedNodeId);
            else if (row == 1 && node.type().equals("CHEST")) openSearchPopup(node, popupX, popupY, SearchKind.ALL);
            else if (row == 1 && node.type().equals("PLAYER_INVENTORY")) openSearchPopup(node, popupX, popupY, SearchKind.ITEM);
            else if (row == 1 && node.type().equals("REROUTE")) openSearchPopup(node, popupX, popupY, SearchKind.ALL);
            else if ((row == 1 && !hasAddAction) || row == 2) {
                deleteNode(selectedNodeId);
                popupMode = PopupMode.NONE;
            }
            return true;
        }
        if (popupMode == PopupMode.EDGE && selectedEdgeId != null) {
            double lx = scaledPopupLocalX(mx);
            double ly = scaledPopupLocalY(my);
            TransferGraphSyncPacket.EdgeData edge = edge(selectedEdgeId);
            if (edge == null) return true;
            List<TransferGraphSyncPacket.EdgeItemRateData> rows = GraphResourceUtils.edgeRateRows(edge);
            int panelW = edgePopupWidth(rows);
            int panelH = edgePopupHeight(rows);
            if (!inside(lx, ly, 0, 0, panelW, panelH)) {
                if (applyFocusedEdgeEdit()) saveDraft();
                focusedEdgeField = EdgeField.NONE;
                popupMode = PopupMode.NONE;
                return false;
            }
            if (inside(lx, ly, panelW - 52, 5, 50, 18)) {
                applyFocusedEdgeEdit();
                openEdgeItemSearch(mx, my);
                return true;
            }
            int row = ((int) ly - 60) / 24;
            if (row >= 0 && row < rows.size()) {
                TransferGraphSyncPacket.EdgeItemRateData rate = rows.get(row);
                int rowY = 64 + row * 24;
                if (inside(lx, ly, 31, rowY - 3, 28, 16)) {
                    applyFocusedEdgeEdit();
                    updateEdgeItemRate(edge.id(), rate.itemId(), !rate.rateLimitEnabled(), rate.rateLimitSeconds(), rate.rateLimitItems());
                    return true;
                }
                int secX = 126;
                int itemX = 176;
                if (inside(lx, ly, secX, rowY - 4, 24, 18)) {
                    applyFocusedEdgeEdit();
                    selectedRateItemId = rate.itemId();
                    syncRateEditors(edge);
                    focusedEdgeField = EdgeField.SECONDS;
                    return true;
                }
                if (inside(lx, ly, itemX, rowY - 4, 34, 18)) {
                    applyFocusedEdgeEdit();
                    selectedRateItemId = rate.itemId();
                    syncRateEditors(edge);
                    focusedEdgeField = EdgeField.ITEMS;
                    return true;
                }
            }
            applyFocusedEdgeEdit();
            focusedEdgeField = EdgeField.NONE;
            if (ly >= panelH - 27 && ly < panelH - 5) {
                if (inside(lx, ly, 8, panelH - 25, 42, 18)) toggleEdge(selectedEdgeId);
                else if (inside(lx, ly, 60, panelH - 25, 42, 18)) {
                    deleteEdge(selectedEdgeId);
                    popupMode = PopupMode.NONE;
                } else if (inside(lx, ly, panelW - 58, panelH - 25, 50, 18)) {
                    saveDraft();
                }
                return true;
            }
        }
        return false;
    }

    private boolean handleJumpInputPopupClick(TransferGraphSyncPacket.NodeData node, double mx, double my) {
        boolean canCreateOutput = !jumpBound(node);
        int h = canCreateOutput ? 98 : 80;
        if (!inside(mx, my, popupX, popupY, 158, h)) {
            popupMode = PopupMode.NONE;
            return true;
        }
        int row = ((int) my - popupY - 24) / 18;
        if (row == 0) toggleNode(node.id());
        else if (row == 1) openNodeRename(node);
        else if (row == 2 && canCreateOutput) {
            addJumpOutputNode(node, node.x() + 220, node.y());
            popupMode = PopupMode.NONE;
        } else if ((row == 2 && !canCreateOutput) || row == 3) {
            deleteNode(node.id());
            popupMode = PopupMode.NONE;
        }
        return true;
    }

    private boolean handleJumpOutputPopupClick(TransferGraphSyncPacket.NodeData node, double mx, double my) {
        if (!inside(mx, my, popupX, popupY, 158, 80)) {
            popupMode = PopupMode.NONE;
            return true;
        }
        int row = ((int) my - popupY - 24) / 18;
        if (row == 0) toggleNode(node.id());
        else if (row == 1) openNodeRename(node);
        else if (row == 2) {
            deleteNode(node.id());
            popupMode = PopupMode.NONE;
        }
        return true;
    }

    private boolean handleLimitGatePopupClick(TransferGraphSyncPacket.NodeData node, double mx, double my) {
        if (!inside(mx, my, popupX, popupY, 202, 138)) {
            applyGateEdit();
            focusedGateField = GateField.NONE;
            popupMode = PopupMode.NONE;
            return true;
        }
        if (inside(mx, my, popupX + 78, popupY + 26, 44, 18)) {
            selectedNodeId = node.id();
            focusedGateField = GateField.MIN;
            gateMinValue = node.gateMin() == TransferNode.GATE_UNBOUNDED ? "" : String.valueOf(node.gateMin());
            gateMaxValue = node.gateMax() == TransferNode.GATE_UNBOUNDED ? "" : String.valueOf(node.gateMax());
            return true;
        }
        if (inside(mx, my, popupX + 136, popupY + 26, 44, 18)) {
            selectedNodeId = node.id();
            focusedGateField = GateField.MAX;
            gateMinValue = node.gateMin() == TransferNode.GATE_UNBOUNDED ? "" : String.valueOf(node.gateMin());
            gateMaxValue = node.gateMax() == TransferNode.GATE_UNBOUNDED ? "" : String.valueOf(node.gateMax());
            return true;
        }
        applyGateEdit();
        focusedGateField = GateField.NONE;
        if (inside(mx, my, popupX + 70, popupY + 69, 54, 18)) setGateCheckSource(node.id(), false);
        else if (inside(mx, my, popupX + 130, popupY + 69, 54, 18)) setGateCheckSource(node.id(), true);
        else if (inside(mx, my, popupX + 8, popupY + 114, 56, 16)) toggleNode(node.id());
        else if (inside(mx, my, popupX + 72, popupY + 114, 56, 16)) {
            deleteNode(node.id());
            popupMode = PopupMode.NONE;
        }
        return true;
    }

    private void openSearchPopup(TransferGraphSyncPacket.NodeData node, double mx, double my) {
        openSearchPopup(node, mx, my, SearchKind.ALL);
    }

    private void openSearchPopup(TransferGraphSyncPacket.NodeData node, double mx, double my, SearchKind kind) {
        selectedNodeId = node.id();
        searchForEdgeItem = false;
        searchKind = !createResourcesVisible() && kind == SearchKind.FLUID ? SearchKind.ITEM : kind == null ? SearchKind.ALL : kind;
        popupMode = PopupMode.SEARCH;
        popupX = clamp((int) mx + 8, 4, Math.max(4, width - SEARCH_W - 4));
        popupY = clamp((int) my + 8, HEADER_H + 4, Math.max(HEADER_H + 4, height - 78));
        searchValue = "";
        searchFocused = true;
        lastSearch = "";
        cachedSearch = List.of();
    }

    private void openEdgeItemSearch(double mx, double my) {
        searchForEdgeItem = true;
        searchKind = SearchKind.ALL;
        popupMode = PopupMode.SEARCH;
        popupX = clamp((int) mx + 8, 4, Math.max(4, width - SEARCH_W - 4));
        popupY = clamp((int) my + 8, HEADER_H + 4, Math.max(HEADER_H + 4, height - 78));
        searchValue = "";
        searchFocused = true;
        lastSearch = "";
        cachedSearch = List.of();
    }

    private boolean handleRerouteNodeClick(TransferGraphSyncPacket.NodeData node, double mx, double my) {
        double lx = nodeLocalX(node, mx);
        double ly = nodeLocalY(node, my);
        List<String> ports = visibleRerouteOutputPorts(node);
        for (String port : ports) {
            int py = rerouteOutputLocalY(node, port);
            String filter = GraphResourceUtils.filterFromPort(port);
            if (isExpanded(node) && node.filterItemIds().contains(filter)
                    && inside(lx, ly, REROUTE_W - 34, py - 8, 20, 16)) {
                if (node.filterItemIds().contains(filter)) removeFilterItem(node.id(), filter);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (button == 0 && draggingControls) {
            if (!controlsMoved) {
                int x = controlX;
                int y = controlY;
                if (inside(mx, my, x + 7, y + 7, 42, 24)) {
                    saveDraft();
                } else if (inside(mx, my, x, y, CONTROL_W, CONTROL_H)) {
                    controlsExpanded = !controlsExpanded;
                } else if (controlsExpanded) {
                    int fy = y + CONTROL_H + 8;
                    if (inside(mx, my, x + CONTROL_PANEL_W - 30, fy + 8, 22, 22)) {
                        controlsExpanded = false;
                    }
                }
            }
            draggingControls = false;
            controlsMoved = false;
            return true;
        }
        if (button == 0 && linkingFromNodeId != null) {
            PortHit input = inputAt(mx, my);
            if (input != null) createEdge(input.nodeId(), input.portKey());
            linkingFromNodeId = null;
            return true;
        }
        if (button == 0 && draggingNodeId != null) {
            String nodeId = draggingNodeId;
            if (dragMoved) {
                moveNode(nodeId, cx(dragPreviewX), cy(dragPreviewY));
            } else {
                TransferGraphSyncPacket.NodeData node = node(nodeId);
                if (node != null) {
                    selectedNodeId = node.id();
                    popupMode = PopupMode.NONE;
                }
            }
            draggingNodeId = null;
            return true;
        }
        draggingPopup = false;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (draggingControls && button == 0) {
            controlX = clamp((int) mx - controlDragOffX, 4, Math.max(4, width - CONTROL_W - 4));
            int expandedHeight = controlsExpanded ? CONTROL_H + 8 + CONTROL_PANEL_H : CONTROL_H;
            controlY = clamp((int) my - controlDragOffY, 4, Math.max(4, height - expandedHeight - 4));
            if (Math.abs(dx) > 0.0 || Math.abs(dy) > 0.0) controlsMoved = true;
            menuMode = MenuMode.NONE;
            return true;
        }
        if (draggingPopup && button == 0) {
            popupX = clamp((int) mx - popupDragOffX, 4, Math.max(4, width - SEARCH_W - 4));
            popupY = clamp((int) my - popupDragOffY, HEADER_H + 4, Math.max(HEADER_H + 4, height - searchPopupHeight(searchResults()) - 4));
            return true;
        }
        if (draggingNodeId != null) {
            dragPreviewX = (int) mx - dragOffX;
            dragPreviewY = (int) my - dragOffY;
            dragMoved = true;
            return true;
        }
        if (linkingFromNodeId != null) return true;
        if (button == 0) {
            panX += (int) dx;
            panY += (int) dy;
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        double old = zoom;
        zoom = Math.max(0.05, Math.min(2.5, zoom + Math.signum(sy) * (zoom < 0.2 ? 0.02 : 0.1)));
        double factor = zoom / old;
        panX = (int) Math.round(mx - (mx - panX) * factor);
        panY = (int) Math.round(my - (my - panY) * factor);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (editingReplenishTarget) {
            if (keyCode == 257 || keyCode == 335) {
                applyReplenishTargetEdit();
                return true;
            }
            if (keyCode == 259) {
                if (!replenishTargetValue.isEmpty()) replenishTargetValue = replenishTargetValue.substring(0, replenishTargetValue.length() - 1);
                return true;
            }
            if (keyCode == 261) {
                replenishTargetValue = "";
                return true;
            }
            if (keyCode == 256) {
                editingReplenishTarget = false;
                selectedReplenishItemId = null;
                return true;
            }
        }
        if (focusedGateField != GateField.NONE) {
            if (keyCode == 257 || keyCode == 335) {
                applyGateEdit();
                focusedGateField = GateField.NONE;
                return true;
            }
            if (keyCode == 259) {
                backspaceGateField();
                return true;
            }
            if (keyCode == 261) {
                clearGateField();
                return true;
            }
            if (keyCode == 256) {
                focusedGateField = GateField.NONE;
                return true;
            }
        }
        if (keyCode == 256) {
            if (popupMode == PopupMode.EDGE) {
                if (applyFocusedEdgeEdit()) saveDraft();
                focusedEdgeField = EdgeField.NONE;
                popupMode = PopupMode.NONE;
                return true;
            }
            if (popupMode == PopupMode.SEARCH) {
                popupMode = PopupMode.NONE;
                searchFocused = false;
                draggingPopup = false;
                return true;
            }
            if (popupMode == PopupMode.EXIT_CONFIRM) {
                popupMode = PopupMode.NONE;
                exitAfterSave = false;
                return true;
            }
            if (popupMode == PopupMode.HELP) {
                popupMode = PopupMode.NONE;
                return true;
            }
            if (dirty) {
                popupMode = PopupMode.EXIT_CONFIRM;
                menuMode = MenuMode.NONE;
                focusedEdgeField = EdgeField.NONE;
                return true;
            }
            onClose();
            return true;
        }
        if (menuMode == MenuMode.TEAM_MEMBER_ADD && pageNameEdit != null) {
            if (keyCode == 257 || keyCode == 335) {
                String playerId = pageNameEdit.getValue().trim();
                if (!playerId.isEmpty() && pendingTeamId != null && !pendingTeamId.isBlank()) {
                    PacketDistributor.sendToServer(new TransferTeamPacket("SET_MEMBER", pendingTeamId, playerId + "|WRITE"));
                    requestGraph("PROTECTED", pendingTeamId);
                }
                pageNameEdit.setFocused(false);
                pageNameEdit.setMaxLength(24);
                menuMode = MenuMode.NONE;
                return true;
            }
            if (pageNameEdit.keyPressed(keyCode, scanCode, modifiers)) return true;
        }
        if ((menuMode == MenuMode.PAGE_RENAME || menuMode == MenuMode.PAGE_CREATE || menuMode == MenuMode.NODE_RENAME) && pageNameEdit != null) {
            if (keyCode == 257 || keyCode == 335) {
                String name = pageNameEdit.getValue().trim();
                if (name.isEmpty()) name = menuMode == MenuMode.NODE_RENAME ? "跳线" : "新页面";
                if (menuMode == MenuMode.PAGE_CREATE) addPage(name);
                else if (menuMode == MenuMode.NODE_RENAME && pendingRenameNodeId != null) renameNode(pendingRenameNodeId, name);
                else if (pendingDeletePageId != null) renamePage(pendingDeletePageId, name);
                pageNameEdit.setFocused(false);
                menuMode = menuMode == MenuMode.NODE_RENAME ? MenuMode.NONE : MenuMode.PAGE_ACTION;
                return true;
            }
            if (pageNameEdit.keyPressed(keyCode, scanCode, modifiers)) return true;
        }
        if (popupMode == PopupMode.SEARCH && searchFocused) {
            if (keyCode == 259) {
                if (!searchValue.isEmpty()) {
                    searchValue = searchValue.substring(0, searchValue.length() - 1);
                    resetSearchCache();
                }
                return true;
            }
            if (keyCode == 261) {
                searchValue = "";
                resetSearchCache();
                return true;
            }
            return true;
        }
        if (popupMode == PopupMode.EDGE && focusedEdgeField != EdgeField.NONE) {
            if (keyCode == 257 || keyCode == 335) {
                applyFocusedEdgeEdit();
                focusedEdgeField = EdgeField.NONE;
                return true;
            }
            if (keyCode == 259) {
                backspaceEdgeField();
                return true;
            }
            if (keyCode == 261) {
                clearEdgeField();
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        if (editingReplenishTarget && Character.isDigit(c) && replenishTargetValue.length() < 5) {
            replenishTargetValue += c;
            return true;
        }
        if (focusedGateField != GateField.NONE && Character.isDigit(c)) {
            appendGateField(c);
            return true;
        }
        if ((menuMode == MenuMode.PAGE_RENAME || menuMode == MenuMode.PAGE_CREATE || menuMode == MenuMode.TEAM_MEMBER_ADD || menuMode == MenuMode.NODE_RENAME) && pageNameEdit != null && pageNameEdit.charTyped(c, modifiers)) return true;
        if (popupMode == PopupMode.SEARCH && searchFocused && !Character.isISOControl(c) && searchValue.length() < 64) {
            searchValue += c;
            resetSearchCache();
            return true;
        }
        if (popupMode == PopupMode.EDGE && focusedEdgeField != EdgeField.NONE && Character.isDigit(c)) {
            appendEdgeField(c);
            return true;
        }
        return super.charTyped(c, modifiers);
    }

    private void applyReplenishTargetEdit() {
        if (selectedNodeId == null || selectedReplenishItemId == null) {
            editingReplenishTarget = false;
            return;
        }
        int target = 64;
        try {
            target = Math.max(1, Math.min(2304, Integer.parseInt(replenishTargetValue)));
        } catch (NumberFormatException ignored) {
        }
        addReplenishRule(selectedNodeId, selectedReplenishItemId, target);
        editingReplenishTarget = false;
        selectedReplenishItemId = null;
        replenishTargetValue = "";
    }

    private void applyGateEdit() {
        if (selectedNodeId == null || focusedGateField == GateField.NONE) return;
        TransferGraphSyncPacket.NodeData node = node(selectedNodeId);
        if (node == null || !node.type().equals("LIMIT_GATE")) return;
        int min = parseGateBound(gateMinValue);
        int max = parseGateBound(gateMaxValue);
        replaceNode(copyNode(node, node.pageId(), node.x(), node.y(), node.expanded(), node.enabled(),
                node.filterItemIds(), node.receiveFilterIds(), node.replenishRules(), node.label(), node.linkedNodeId(), min, max, node.gateCheckSource()));
    }

    private int parseGateBound(String value) {
        if (value == null || value.trim().isEmpty()) return TransferNode.GATE_UNBOUNDED;
        try {
            return Math.max(0, Math.min(1_000_000_000, Integer.parseInt(value.trim())));
        } catch (NumberFormatException e) {
            return TransferNode.GATE_UNBOUNDED;
        }
    }

    private void appendGateField(char c) {
        if (focusedGateField == GateField.MIN && gateMinValue.length() < 10) gateMinValue += c;
        else if (focusedGateField == GateField.MAX && gateMaxValue.length() < 10) gateMaxValue += c;
    }

    private void backspaceGateField() {
        if (focusedGateField == GateField.MIN && !gateMinValue.isEmpty()) gateMinValue = gateMinValue.substring(0, gateMinValue.length() - 1);
        else if (focusedGateField == GateField.MAX && !gateMaxValue.isEmpty()) gateMaxValue = gateMaxValue.substring(0, gateMaxValue.length() - 1);
    }

    private void clearGateField() {
        if (focusedGateField == GateField.MIN) gateMinValue = "";
        else if (focusedGateField == GateField.MAX) gateMaxValue = "";
    }

    private void createEdge(String toNodeId, String toPortKey) {
        if (linkingFromNodeId == null) return;
        if (!isVisibleResourcePort(linkingFromPort) || !isVisibleResourcePort(toPortKey)) {
            linkingFromNodeId = null;
            return;
        }
        TransferGraphSyncPacket.NodeData from = node(linkingFromNodeId);
        TransferGraphSyncPacket.NodeData to = node(toNodeId);
        if (from == null || to == null || from.id().equals(to.id())) {
            linkingFromNodeId = null;
            return;
        }
        if (!from.pageId().equals(to.pageId())) {
            linkingFromNodeId = null;
            return;
        }
        if (!canCreateEdge(from, to, linkingFromPort)) {
            linkingFromNodeId = null;
            return;
        }
        for (int i = 0; i < draftEdges.size(); i++) {
            TransferGraphSyncPacket.EdgeData edge = draftEdges.get(i);
            if (edge.fromNodeId().equals(from.id()) && edge.toNodeId().equals(to.id())
                    && edge.fromPortKey().equals(linkingFromPort) && edge.toPortKey().equals(toPortKey)) {
                draftEdges.set(i, GraphResourceUtils.copyEdge(edge, edge.enabled(), edge.itemRates()));
                markDirty();
                linkingFromNodeId = null;
                return;
            }
        }
        draftEdges.add(new TransferGraphSyncPacket.EdgeData(UUID.randomUUID().toString(), from.pageId(), from.id(), to.id(),
                linkingFromPort, toPortKey, true, "UNMEASURED", 0, GraphResourceUtils.defaultItemRatesForPort(linkingFromPort)));
        markDirty();
        linkingFromNodeId = null;
    }

    private boolean canCreateEdge(TransferGraphSyncPacket.NodeData from, TransferGraphSyncPacket.NodeData to, String fromPort) {
        if (from.type().equals("JUMP_INPUT") || to.type().equals("JUMP_OUTPUT")) return false;
        if (to.type().equals("LIMIT_GATE")) {
            if (!isExactResourcePort(fromPort) || incomingEdge(to.id()) != null) return false;
        }
        if (from.type().equals("LIMIT_GATE")) {
            String resource = incomingResource(from.id());
            if (resource == null || !resource.equals(fromPort) || outgoingEdge(from.id()) != null) return false;
        }
        if (to.type().equals("JUMP_INPUT") && incomingEdge(to.id()) != null) return false;
        if (from.type().equals("JUMP_OUTPUT")) {
            String resource = jumpOutputResource(from);
            if (resource == null || !resource.equals(fromPort) || outgoingEdge(from.id()) != null) return false;
        }
        return true;
    }

    private void saveDraft() {
        ensureDraft();
        applyFocusedEdgeEdit();
        if (savePending) return;
        savePending = true;
        validationStale = false;
        PacketDistributor.sendToServer(new SaveTransferGraphPacket(currentGraphKind(), currentGraphId(), copyPages(draftPages), copyNodes(draftNodes), copyEdges(draftEdges)));
    }

    private void discardDraft() {
        loadDraftFromCache();
        dirty = false;
        savePending = false;
        validationStale = true;
        popupMode = PopupMode.NONE;
        menuMode = MenuMode.NONE;
    }

    private void addPage(String name) {
        draftPages.add(new TransferGraphSyncPacket.PageData(UUID.randomUUID().toString(), name, true, draftPages.size()));
        activePageId = draftPages.get(draftPages.size() - 1).id();
        markDirty();
    }

    private void openPageRename(TransferGraphSyncPacket.PageData page) {
        if (page == null) return;
        pendingDeletePageId = page.id();
        menuMode = MenuMode.PAGE_RENAME;
        popupMode = PopupMode.NONE;
        if (pageNameEdit != null) {
            pageNameEdit.setMaxLength(24);
            pageNameEdit.setValue(page.name());
            pageNameEdit.setFocused(true);
        }
    }

    private void renamePage(String pageId, String name) {
        for (int i = 0; i < draftPages.size(); i++) {
            TransferGraphSyncPacket.PageData p = draftPages.get(i);
            if (p.id().equals(pageId)) {
                draftPages.set(i, new TransferGraphSyncPacket.PageData(p.id(), name, p.enabled(), p.order()));
                markDirty();
                return;
            }
        }
    }

    private void openNodeRename(TransferGraphSyncPacket.NodeData node) {
        pendingRenameNodeId = node.id();
        menuMode = MenuMode.NODE_RENAME;
        popupMode = PopupMode.NONE;
        menuX = popupX;
        menuY = popupY;
        if (pageNameEdit != null) {
            pageNameEdit.setMaxLength(24);
            pageNameEdit.setValue(jumpLabel(node));
            pageNameEdit.setFocused(true);
        }
    }

    private void renameNode(String nodeId, String name) {
        TransferGraphSyncPacket.NodeData n = node(nodeId);
        if (n == null) return;
        if (name == null || name.isBlank()) name = "跳线";
        replaceNode(copyNode(n, n.pageId(), n.x(), n.y(), n.expanded(), n.enabled(), n.filterItemIds(), n.receiveFilterIds(),
                n.replenishRules(), name, n.linkedNodeId(), n.gateMin(), n.gateMax()));
        TransferGraphSyncPacket.NodeData linked = linkedNode(n);
        if (linked != null) {
            replaceNode(copyNode(linked, linked.pageId(), linked.x(), linked.y(), linked.expanded(), linked.enabled(),
                    linked.filterItemIds(), linked.receiveFilterIds(), linked.replenishRules(), name, linked.linkedNodeId(),
                    linked.gateMin(), linked.gateMax()));
        }
    }

    private void togglePage(String pageId) {
        for (int i = 0; i < draftPages.size(); i++) {
            TransferGraphSyncPacket.PageData p = draftPages.get(i);
            if (p.id().equals(pageId)) {
                draftPages.set(i, new TransferGraphSyncPacket.PageData(p.id(), p.name(), !p.enabled(), p.order()));
                markDirty();
                return;
            }
        }
    }

    private void deletePage(String pageId) {
        if (pageId == null) return;
        draftPages.removeIf(p -> p.id().equals(pageId));
        Set<String> removedNodes = new LinkedHashSet<>();
        draftNodes.removeIf(n -> {
            boolean remove = n.pageId().equals(pageId);
            if (remove) removedNodes.add(n.id());
            return remove;
        });
        draftEdges.removeIf(e -> e.pageId().equals(pageId) || removedNodes.contains(e.fromNodeId()) || removedNodes.contains(e.toNodeId()));
        if (draftPages.isEmpty()) {
            draftPages.add(new TransferGraphSyncPacket.PageData(UUID.randomUUID().toString(), "新页面", true, 0));
        }
        if (pageId.equals(activePageId)) activePageId = firstPage().id();
        markDirty();
    }

    private void addChestNode(TransferGraphSyncPacket.ChestData chest, int x, int y) {
        for (int i = 0; i < draftNodes.size(); i++) {
            TransferGraphSyncPacket.NodeData n = draftNodes.get(i);
            if (n.type().equals("CHEST") && n.chestId().equals(chest.chestId())
                    && n.dimensionKey().equals(chest.dimensionKey()) && n.pos() == chest.pos()) {
                draftNodes.set(i, copyNode(n, activePageId, x, y, n.expanded(), n.enabled(), n.filterItemIds(), n.receiveFilterIds(), n.replenishRules()));
                markDirty();
                return;
            }
        }
        draftNodes.add(newNodeData(UUID.randomUUID().toString(), activePageId, "CHEST", chest.chestId(), chest.dimensionKey(), chest.pos(), x, y, false, true, "", "", TransferNode.DEFAULT_GATE_MIN, TransferNode.DEFAULT_GATE_MAX));
        markDirty();
    }

    private void addRerouteNode(String pageId, int x, int y) {
        draftNodes.add(newNodeData(UUID.randomUUID().toString(), pageId, "REROUTE", "", "", BlockPos.ZERO.asLong(), x, y, false, true, "", "", TransferNode.DEFAULT_GATE_MIN, TransferNode.DEFAULT_GATE_MAX));
        markDirty();
    }

    private void addLimitGateNode(String pageId, int x, int y) {
        draftNodes.add(newNodeData(UUID.randomUUID().toString(), pageId, "LIMIT_GATE", "", "", BlockPos.ZERO.asLong(), x, y, false, true,
                "", "", TransferNode.DEFAULT_GATE_MIN, TransferNode.DEFAULT_GATE_MAX));
        markDirty();
    }

    private void addJumpInputNode(String pageId, int x, int y) {
        draftNodes.add(newNodeData(UUID.randomUUID().toString(), pageId, "JUMP_INPUT", "", "", BlockPos.ZERO.asLong(), x, y, false, true,
                nextJumpLabel(), "", TransferNode.DEFAULT_GATE_MIN, TransferNode.DEFAULT_GATE_MAX));
        markDirty();
    }

    private void addJumpOutputNode(TransferGraphSyncPacket.NodeData input, int x, int y) {
        if (input == null || !input.type().equals("JUMP_INPUT") || jumpBound(input)) return;
        String outputId = UUID.randomUUID().toString();
        draftNodes.add(newNodeData(outputId, input.pageId(), "JUMP_OUTPUT", "", "", BlockPos.ZERO.asLong(), x, y, false, true,
                input.label(), input.id(), TransferNode.DEFAULT_GATE_MIN, TransferNode.DEFAULT_GATE_MAX));
        replaceNode(copyNode(input, input.pageId(), input.x(), input.y(), input.expanded(), input.enabled(),
                input.filterItemIds(), input.receiveFilterIds(), input.replenishRules(), input.label(), outputId, input.gateMin(), input.gateMax()));
        markDirty();
    }

    private void addTrashNode(String pageId, int x, int y) {
        draftNodes.add(newNodeData(UUID.randomUUID().toString(), pageId, "TRASH", "", "", BlockPos.ZERO.asLong(), x, y, false, true, "", "", TransferNode.DEFAULT_GATE_MIN, TransferNode.DEFAULT_GATE_MAX));
        markDirty();
    }

    private void addPlayerInventoryNode(String pageId, int x, int y) {
        if (Minecraft.getInstance().player == null) return;
        String playerId = Minecraft.getInstance().player.getUUID().toString();
        for (int i = 0; i < draftNodes.size(); i++) {
            TransferGraphSyncPacket.NodeData n = draftNodes.get(i);
            if (n.type().equals("PLAYER_INVENTORY") && playerId.equals(n.targetPlayerId())) {
                draftNodes.set(i, copyNode(n, pageId, x, y, n.expanded(), n.enabled(), n.filterItemIds(), n.receiveFilterIds(), n.replenishRules()));
                markDirty();
                return;
            }
        }
        draftNodes.add(new TransferGraphSyncPacket.NodeData(UUID.randomUUID().toString(), pageId, "PLAYER_INVENTORY", "", "", BlockPos.ZERO.asLong(), x, y,
                true, true, List.of(), List.of(), playerId, List.of(), List.of(), "", "",
                TransferNode.DEFAULT_GATE_MIN, TransferNode.DEFAULT_GATE_MAX, false));
        markDirty();
    }

    private void moveNode(String nodeId, int x, int y) {
        TransferGraphSyncPacket.NodeData n = node(nodeId);
        if (n == null) return;
        replaceNode(copyNode(n, n.pageId(), x, y, n.expanded(), n.enabled(), n.filterItemIds(), n.receiveFilterIds(), n.replenishRules()));
    }

    private void toggleNode(String nodeId) {
        TransferGraphSyncPacket.NodeData n = node(nodeId);
        if (n == null) return;
        replaceNode(copyNode(n, n.pageId(), n.x(), n.y(), n.expanded(), !n.enabled(), n.filterItemIds(), n.receiveFilterIds(), n.replenishRules()));
    }

    private void setGateCheckSource(String nodeId, boolean checkSource) {
        TransferGraphSyncPacket.NodeData n = node(nodeId);
        if (n == null || !n.type().equals("LIMIT_GATE")) return;
        if (n.gateCheckSource() == checkSource) return;
        replaceNode(copyNode(n, n.pageId(), n.x(), n.y(), n.expanded(), n.enabled(), n.filterItemIds(), n.receiveFilterIds(),
                n.replenishRules(), n.label(), n.linkedNodeId(), n.gateMin(), n.gateMax(), checkSource));
    }

    private void toggleNodeExpanded(String nodeId) {
        TransferGraphSyncPacket.NodeData n = node(nodeId);
        if (n == null) return;
        replaceNode(copyNode(n, n.pageId(), n.x(), n.y(), !n.expanded(), n.enabled(), n.filterItemIds(), n.receiveFilterIds(), n.replenishRules()));
    }

    private void deleteNode(String nodeId) {
        Set<String> removed = new LinkedHashSet<>();
        removed.add(nodeId);
        TransferGraphSyncPacket.NodeData node = node(nodeId);
        if (node != null) {
            for (TransferGraphSyncPacket.NodeData n : draftNodes) {
                if (node.id().equals(n.linkedNodeId()) || n.id().equals(node.linkedNodeId())) removed.add(n.id());
            }
        }
        draftNodes.removeIf(n -> removed.contains(n.id()));
        draftEdges.removeIf(e -> removed.contains(e.fromNodeId()) || removed.contains(e.toNodeId()));
        markDirty();
    }

    private void addFilterItem(String nodeId, String itemId) {
        itemId = GraphResourceUtils.normalizeFilterResource(itemId);
        if (!isVisibleResourcePort(GraphResourceUtils.filterPort(itemId))) return;
        TransferGraphSyncPacket.NodeData n = node(nodeId);
        if (n == null || (!n.type().equals("CHEST") && !n.type().equals("REROUTE")) || n.filterItemIds().contains(itemId)) return;
        List<String> filters = new ArrayList<>(n.filterItemIds());
        filters.add(itemId);
        boolean expanded = n.expanded() || n.type().equals("CHEST");
        replaceNode(copyNode(n, n.pageId(), n.x(), n.y(), expanded, n.enabled(), filters, n.receiveFilterIds(), n.replenishRules()));
    }

    private void removeFilterItem(String nodeId, String itemId) {
        itemId = GraphResourceUtils.normalizeFilterResource(itemId);
        TransferGraphSyncPacket.NodeData n = node(nodeId);
        if (n == null) return;
        List<String> filters = new ArrayList<>(n.filterItemIds());
        if (!filters.remove(itemId)) return;
        replaceNode(copyNode(n, n.pageId(), n.x(), n.y(), n.expanded(), n.enabled(), filters, n.receiveFilterIds(), n.replenishRules()));
        String port = GraphResourceUtils.filterPort(itemId);
        draftEdges.removeIf(e -> e.fromNodeId().equals(nodeId) && e.fromPortKey().equals(port));
        markDirty();
    }

    private void addReplenishRule(String nodeId, String itemId, int targetCount) {
        itemId = GraphResourceUtils.normalizeFilterResource(itemId);
        if (itemId.startsWith(TransferEdge.FLUID_PREFIX)) return;
        TransferGraphSyncPacket.NodeData n = node(nodeId);
        if (n == null || !n.type().equals("PLAYER_INVENTORY")) return;
        List<TransferGraphSyncPacket.ReplenishRuleData> rules = new ArrayList<>(n.replenishRules());
        for (int i = 0; i < rules.size(); i++) {
            if (rules.get(i).itemId().equals(itemId)) {
                rules.set(i, new TransferGraphSyncPacket.ReplenishRuleData(itemId, Math.max(1, targetCount)));
                replaceNode(copyNode(n, n.pageId(), n.x(), n.y(), true, n.enabled(), n.filterItemIds(), n.receiveFilterIds(), rules));
                return;
            }
        }
        rules.add(new TransferGraphSyncPacket.ReplenishRuleData(itemId, Math.max(1, targetCount)));
        replaceNode(copyNode(n, n.pageId(), n.x(), n.y(), true, n.enabled(), n.filterItemIds(), n.receiveFilterIds(), rules));
    }

    private void removeReplenishRule(String nodeId, String itemId) {
        TransferGraphSyncPacket.NodeData n = node(nodeId);
        if (n == null || !n.type().equals("PLAYER_INVENTORY")) return;
        List<TransferGraphSyncPacket.ReplenishRuleData> rules = new ArrayList<>(n.replenishRules());
        if (rules.removeIf(rule -> rule.itemId().equals(itemId))) {
            replaceNode(copyNode(n, n.pageId(), n.x(), n.y(), n.expanded(), n.enabled(), n.filterItemIds(), n.receiveFilterIds(), rules));
        }
    }

    private boolean playerRuleHit(TransferGraphSyncPacket.NodeData node, double mx, double my) {
        if (node == null || !node.type().equals("PLAYER_INVENTORY") || !isExpanded(node)) return false;
        double lx = nodeLocalX(node, mx);
        double ly = nodeLocalY(node, my);
        if (inside(lx, ly, 10, 50, 66, 18)) {
            openSearchPopup(node, mx, my, SearchKind.ITEM);
            return true;
        }
        int y = 74;
        for (TransferGraphSyncPacket.ReplenishRuleData rule : node.replenishRules()) {
            if (inside(lx, ly, 100, y - 10, 48, 18)) {
                selectedNodeId = node.id();
                selectedReplenishItemId = rule.itemId();
                replenishTargetValue = String.valueOf(rule.targetCount());
                editingReplenishTarget = true;
                return true;
            }
            if (inside(lx, ly, TRASH_W - 30, y - 10, 24, 18)) {
                removeReplenishRule(node.id(), rule.itemId());
                return true;
            }
            y += 18;
        }
        return false;
    }

    private void addEdgeItemRate(String edgeId, String itemId) {
        updateEdgeItemRate(edgeId, itemId, false, 1, 64);
    }

    private void updateEdgeItemRate(String edgeId, String itemId, boolean enabled, int seconds, int items) {
        if (itemId == null || itemId.isBlank()) return;
        if (!isVisibleResourcePort(GraphResourceUtils.filterPort(itemId))) return;
        for (int i = 0; i < draftEdges.size(); i++) {
            TransferGraphSyncPacket.EdgeData e = draftEdges.get(i);
            if (e.id().equals(edgeId)) {
                List<TransferGraphSyncPacket.EdgeItemRateData> rows = new ArrayList<>(e.itemRates());
                boolean replaced = false;
                for (int r = 0; r < rows.size(); r++) {
                    TransferGraphSyncPacket.EdgeItemRateData row = rows.get(r);
                    if (row.itemId().equals(itemId)) {
                        rows.set(r, new TransferGraphSyncPacket.EdgeItemRateData(itemId, enabled, TransferEdge.clampSeconds(seconds), TransferEdge.clampRate(items),
                                row.health(), row.actualRatePerMinute(), true));
                        replaced = true;
                        break;
                    }
                }
                if (!replaced) {
                    rows.add(new TransferGraphSyncPacket.EdgeItemRateData(itemId, enabled, TransferEdge.clampSeconds(seconds), TransferEdge.clampRate(items),
                            "UNMEASURED", 0, true));
                }
                draftEdges.set(i, GraphResourceUtils.copyEdge(e, e.enabled(), rows));
                selectedRateItemId = itemId;
                markDirty();
                return;
            }
        }
    }

    private boolean applyFocusedEdgeEdit() {
        if (popupMode != PopupMode.EDGE || selectedEdgeId == null || selectedRateItemId == null) return false;
        TransferGraphSyncPacket.EdgeData edge = edge(selectedEdgeId);
        if (edge == null) return false;
        TransferGraphSyncPacket.EdgeItemRateData row = GraphResourceUtils.edgeRateRow(edge, selectedRateItemId);
        int seconds = parseRateSeconds();
        int items = parseRateItems();
        rateSecondsValue = String.valueOf(seconds);
        rateItemsValue = String.valueOf(items);
        boolean enabled = row == null || row.rateLimitEnabled();
        if (row != null
                && row.configured()
                && row.rateLimitEnabled() == enabled
                && row.rateLimitSeconds() == seconds
                && row.rateLimitItems() == items) {
            return false;
        }
        if (row != null
                && !row.configured()
                && !row.rateLimitEnabled()
                && row.rateLimitSeconds() == seconds
                && row.rateLimitItems() == items) {
            return false;
        }
        updateEdgeItemRate(edge.id(), selectedRateItemId, enabled, seconds, items);
        return true;
    }

    private void toggleEdge(String edgeId) {
        for (int i = 0; i < draftEdges.size(); i++) {
            TransferGraphSyncPacket.EdgeData e = draftEdges.get(i);
            if (e.id().equals(edgeId)) {
                draftEdges.set(i, GraphResourceUtils.copyEdge(e, !e.enabled(), e.itemRates()));
                markDirty();
                return;
            }
        }
    }

    private void deleteEdge(String edgeId) {
        TransferGraphSyncPacket.EdgeData removed = edge(edgeId);
        draftEdges.removeIf(e -> e.id().equals(edgeId));
        if (removed != null) {
            TransferGraphSyncPacket.NodeData target = node(removed.toNodeId());
            if (target != null && target.type().equals("LIMIT_GATE")) {
                draftEdges.removeIf(e -> e.fromNodeId().equals(target.id()));
            } else if (target != null && target.type().equals("JUMP_INPUT")) {
                TransferGraphSyncPacket.NodeData output = linkedJumpOutput(target);
                if (output != null) draftEdges.removeIf(e -> e.fromNodeId().equals(output.id()));
            }
        }
        markDirty();
    }

    private void replaceNode(TransferGraphSyncPacket.NodeData updated) {
        for (int i = 0; i < draftNodes.size(); i++) {
            if (draftNodes.get(i).id().equals(updated.id())) {
                draftNodes.set(i, updated);
                markDirty();
                return;
            }
        }
    }

    private TransferGraphSyncPacket.NodeData copyNode(TransferGraphSyncPacket.NodeData n, String pageId, int x, int y,
                                                      boolean expanded, boolean enabled, List<String> filters,
                                                      List<String> receiveFilters,
                                                      List<TransferGraphSyncPacket.ReplenishRuleData> replenishRules) {
        return copyNode(n, pageId, x, y, expanded, enabled, filters, receiveFilters, replenishRules,
                n.label(), n.linkedNodeId(), n.gateMin(), n.gateMax(), n.gateCheckSource());
    }

    private TransferGraphSyncPacket.NodeData copyNode(TransferGraphSyncPacket.NodeData n, String pageId, int x, int y,
                                                      boolean expanded, boolean enabled, List<String> filters,
                                                      List<String> receiveFilters,
                                                      List<TransferGraphSyncPacket.ReplenishRuleData> replenishRules,
                                                      String label, String linkedNodeId, int gateMin, int gateMax) {
        return copyNode(n, pageId, x, y, expanded, enabled, filters, receiveFilters, replenishRules,
                label, linkedNodeId, gateMin, gateMax, n.gateCheckSource());
    }

    private TransferGraphSyncPacket.NodeData copyNode(TransferGraphSyncPacket.NodeData n, String pageId, int x, int y,
                                                      boolean expanded, boolean enabled, List<String> filters,
                                                      List<String> receiveFilters,
                                                      List<TransferGraphSyncPacket.ReplenishRuleData> replenishRules,
                                                      String label, String linkedNodeId, int gateMin, int gateMax,
                                                      boolean gateCheckSource) {
        return new TransferGraphSyncPacket.NodeData(n.id(), pageId, n.type(), n.chestId(), n.dimensionKey(), n.pos(),
                x, y, expanded, enabled, List.copyOf(filters), List.copyOf(receiveFilters),
                n.targetPlayerId(), List.copyOf(replenishRules), List.copyOf(n.flowStats()),
                label == null ? "" : label, linkedNodeId == null ? "" : linkedNodeId, gateMin, gateMax, gateCheckSource);
    }

    private TransferGraphSyncPacket.NodeData newNodeData(String id, String pageId, String type, String chestId, String dimensionKey,
                                                         long pos, int x, int y, boolean expanded, boolean enabled,
                                                         String label, String linkedNodeId, int gateMin, int gateMax) {
        return new TransferGraphSyncPacket.NodeData(id, pageId, type, chestId, dimensionKey, pos, x, y,
                expanded, enabled, List.of(), List.of(), "", List.of(), List.of(),
                label == null ? "" : label, linkedNodeId == null ? "" : linkedNodeId, gateMin, gateMax, false);
    }

    private void markDirty() {
        dirty = true;
        savePending = false;
        validationStale = true;
    }

    private int scaled(int value) {
        return (int) Math.round(value * zoom);
    }

    private int scaledPort() {
        return Math.max(3, scaled(PORT));
    }

    private double nodeLocalX(TransferGraphSyncPacket.NodeData node, double mx) {
        return (mx - nodeScreenX(node)) / zoom;
    }

    private double nodeLocalY(TransferGraphSyncPacket.NodeData node, double my) {
        return (my - nodeScreenY(node)) / zoom;
    }

    private int nodeScreenX(TransferGraphSyncPacket.NodeData node) {
        return node.id().equals(draggingNodeId) ? dragPreviewX : sx(node.x());
    }

    private int nodeScreenY(TransferGraphSyncPacket.NodeData node) {
        return node.id().equals(draggingNodeId) ? dragPreviewY : sy(node.y());
    }

    private int sx(int x) {
        return panX + (int) Math.round(x * zoom);
    }

    private int sy(int y) {
        return panY + (int) Math.round(y * zoom);
    }

    private int cx(double sx) {
        return (int) Math.round((sx - panX) / zoom);
    }

    private int cy(double sy) {
        return (int) Math.round((sy - panY) / zoom);
    }

    private List<TransferGraphSyncPacket.NodeData> visibleNodes() {
        return nodes().stream().filter(n -> n.pageId().equals(activePageId)).toList();
    }

    private List<TransferGraphSyncPacket.EdgeData> visibleEdges() {
        return edges().stream()
                .filter(e -> e.pageId().equals(activePageId))
                .filter(e -> createResourcesVisible() || (!isCreateResourcePort(e.fromPortKey()) && !isCreateResourcePort(e.toPortKey())))
                .toList();
    }

    private boolean isExpanded(TransferGraphSyncPacket.NodeData node) {
        return node != null && node.expanded();
    }

    private boolean createResourcesVisible() {
        return CreateCompat.isCreateLoaded();
    }

    private boolean isCreateResourcePort(String port) {
        return port != null && (port.startsWith(TransferEdge.FLUID_PREFIX) || port.startsWith(TransferEdge.STRESS_PREFIX));
    }

    private boolean isVisibleResourcePort(String port) {
        return createResourcesVisible() || !isCreateResourcePort(port);
    }

    private boolean isExactResourcePort(String port) {
        return port != null && !TransferEdge.PORT_ALL.equals(port) && !TransferEdge.FLUID_ALL.equals(port)
                && (port.startsWith(TransferEdge.ITEM_PREFIX) || port.startsWith(TransferEdge.FLUID_PREFIX)
                || TransferEdge.ENERGY_FE.equals(port) || TransferEdge.STRESS_SU.equals(port));
    }

    private TransferGraphSyncPacket.EdgeData incomingEdge(String nodeId) {
        TransferGraphSyncPacket.EdgeData found = null;
        for (TransferGraphSyncPacket.EdgeData edge : visibleEdges()) {
            if (!edge.toNodeId().equals(nodeId)) continue;
            if (found != null) return null;
            found = edge;
        }
        return found;
    }

    private TransferGraphSyncPacket.EdgeData outgoingEdge(String nodeId) {
        TransferGraphSyncPacket.EdgeData found = null;
        for (TransferGraphSyncPacket.EdgeData edge : visibleEdges()) {
            if (!edge.fromNodeId().equals(nodeId)) continue;
            if (found != null) return null;
            found = edge;
        }
        return found;
    }

    private String incomingResource(String nodeId) {
        TransferGraphSyncPacket.EdgeData edge = incomingEdge(nodeId);
        return edge == null ? null : edge.fromPortKey();
    }

    private TransferGraphSyncPacket.NodeData linkedNode(TransferGraphSyncPacket.NodeData node) {
        if (node == null || node.linkedNodeId() == null || node.linkedNodeId().isBlank()) return null;
        return node(node.linkedNodeId());
    }

    private TransferGraphSyncPacket.NodeData linkedJumpOutput(TransferGraphSyncPacket.NodeData input) {
        if (input == null || !input.type().equals("JUMP_INPUT")) return null;
        TransferGraphSyncPacket.NodeData direct = linkedNode(input);
        if (direct != null && direct.type().equals("JUMP_OUTPUT") && input.id().equals(direct.linkedNodeId())) return direct;
        for (TransferGraphSyncPacket.NodeData node : nodes()) {
            if (node.type().equals("JUMP_OUTPUT") && input.id().equals(node.linkedNodeId())) return node;
        }
        return null;
    }

    private boolean jumpBound(TransferGraphSyncPacket.NodeData input) {
        return linkedJumpOutput(input) != null;
    }

    private String jumpOutputResource(TransferGraphSyncPacket.NodeData output) {
        TransferGraphSyncPacket.NodeData input = linkedNode(output);
        return input == null ? null : incomingResource(input.id());
    }

    private List<TransferGraphSyncPacket.NodeData> unboundJumpInputs() {
        List<TransferGraphSyncPacket.NodeData> rows = new ArrayList<>();
        for (TransferGraphSyncPacket.NodeData node : visibleNodes()) {
            if (node.type().equals("JUMP_INPUT") && !jumpBound(node)) rows.add(node);
        }
        return rows;
    }

    private String jumpLabel(TransferGraphSyncPacket.NodeData node) {
        if (node == null) return "跳线";
        if (node.label() != null && !node.label().isBlank()) return node.label();
        if (node.type().equals("JUMP_OUTPUT")) {
            TransferGraphSyncPacket.NodeData input = linkedNode(node);
            if (input != null && input.label() != null && !input.label().isBlank()) return input.label();
        }
        return "跳线";
    }

    private String nextJumpLabel() {
        int next = 1;
        Set<String> names = new LinkedHashSet<>();
        for (TransferGraphSyncPacket.NodeData node : nodes()) {
            if (node.type().equals("JUMP_INPUT")) names.add(jumpLabel(node));
        }
        while (names.contains("跳线 " + next)) next++;
        return "跳线 " + next;
    }

    private String gateRangeLabel(TransferGraphSyncPacket.NodeData node, String resource) {
        return gateModeLabel(node)
                + "[" + gateBoundLabel(node.gateMin(), true) + "," + gateBoundLabel(node.gateMax(), false) + "] "
                + resourceUnit(resource);
    }

    private String gateModeLabel(TransferGraphSyncPacket.NodeData node) {
        return node.gateCheckSource() ? "来源" : "目标";
    }

    private String gateDisplayValue(int value, boolean min) {
        if (min && focusedGateField == GateField.MIN) return gateMinValue;
        if (!min && focusedGateField == GateField.MAX) return gateMaxValue;
        return gateBoundLabel(value, min);
    }

    private String gateBoundLabel(int value, boolean min) {
        if (value == TransferNode.GATE_UNBOUNDED) return min ? "-∞" : "+∞";
        return String.valueOf(value);
    }

    private String resourceUnit(String resource) {
        if (TransferEdge.ENERGY_FE.equals(resource)) return "FE";
        if (TransferEdge.STRESS_SU.equals(resource)) return "SU";
        return resource != null && resource.startsWith(TransferEdge.FLUID_PREFIX) ? "mB" : "个";
    }

    private List<String> rerouteCategoryPorts() {
        return createResourcesVisible()
                ? List.of(TransferEdge.PORT_ALL, TransferEdge.FLUID_ALL, TransferEdge.ENERGY_FE, TransferEdge.STRESS_SU)
                : List.of(TransferEdge.PORT_ALL, TransferEdge.ENERGY_FE);
    }

    private Set<String> chestSourceScopes() {
        return createResourcesVisible()
                ? Set.of(SCOPE_ALL, TransferEdge.FLUID_ALL, TransferEdge.ENERGY_FE, TransferEdge.STRESS_SU)
                : Set.of(SCOPE_ALL, TransferEdge.ENERGY_FE);
    }

    private int collapsedChestHeight() {
        return createResourcesVisible() ? COLLAPSED_CHEST_H : COLLAPSED_CHEST_H_NO_CREATE;
    }

    private int chestEnergyLocalY() {
        return createResourcesVisible() ? GraphNodeLayout.chestEnergyLocalY() : GraphNodeLayout.chestFluidLocalY();
    }

    private int chestFirstFilterLocalY() {
        return createResourcesVisible() ? GraphNodeLayout.chestFirstFilterLocalY() : CHEST_FIRST_FILTER_Y_NO_CREATE;
    }

    private List<String> visibleRerouteOutputPorts(TransferGraphSyncPacket.NodeData node) {
        List<String> ports = rerouteOutputPorts(node);
        if (isExpanded(node)) return ports;
        List<String> visible = new ArrayList<>();
        for (String port : rerouteCategoryPorts()) {
            if (ports.isEmpty() || ports.contains(port)) visible.add(port);
        }
        return visible.isEmpty() ? rerouteCategoryPorts() : visible;
    }

    private String rerouteCategoryPort(String portKey) {
        if (portKey != null && portKey.startsWith(TransferEdge.FLUID_PREFIX)) return TransferEdge.FLUID_ALL;
        if (TransferEdge.ENERGY_FE.equals(portKey) || (portKey != null && portKey.startsWith(TransferEdge.ENERGY_PREFIX))) return TransferEdge.ENERGY_FE;
        if (TransferEdge.STRESS_SU.equals(portKey) || (portKey != null && portKey.startsWith(TransferEdge.STRESS_PREFIX))) return TransferEdge.STRESS_SU;
        return TransferEdge.PORT_ALL;
    }

    private int nodeHeight(TransferGraphSyncPacket.NodeData node) {
        if (node.type().equals("REROUTE")) {
            if (!isExpanded(node)) return COLLAPSED_REROUTE_H;
            return Math.max(COLLAPSED_REROUTE_H, REROUTE_ROW_Y + Math.max(4, rerouteOutputPorts(node).size()) * 24 + 14);
        }
        if (node.type().equals("TRASH")) return isExpanded(node) ? 88 : 74;
        if (node.type().equals("LIMIT_GATE")) return MINI_NODE_H;
        if (node.type().equals("JUMP_INPUT") || node.type().equals("JUMP_OUTPUT")) return JUMP_H;
        if (node.type().equals("PLAYER_INVENTORY")) return isExpanded(node) ? 82 + Math.max(1, node.replenishRules().size()) * 18 : 78;
        if (!isExpanded(node)) return collapsedChestHeight();
        int visibleFilterCount = Math.max(1, (int) node.filterItemIds().stream()
                .filter(filter -> isVisibleResourcePort(GraphResourceUtils.filterPort(filter)))
                .count());
        return chestFirstFilterLocalY() + visibleFilterCount * 18 + 12;
    }

    private int nodeWidth(TransferGraphSyncPacket.NodeData node) {
        if (node.type().equals("REROUTE")) return REROUTE_W;
        if (node.type().equals("LIMIT_GATE")) return GATE_W;
        if (node.type().equals("JUMP_INPUT") || node.type().equals("JUMP_OUTPUT")) return JUMP_W;
        if (node.type().equals("TRASH") || node.type().equals("PLAYER_INVENTORY")) return TRASH_W;
        return NODE_W;
    }

    private TransferGraphSyncPacket.NodeData nodeAt(double mx, double my) {
        for (TransferGraphSyncPacket.NodeData node : visibleNodes()) {
            int x = nodeScreenX(node), y = nodeScreenY(node);
            if (mx >= x && mx < x + scaled(nodeWidth(node)) && my >= y && my < y + scaled(nodeHeight(node))) return node;
        }
        return null;
    }

    private boolean nodeExpandHit(TransferGraphSyncPacket.NodeData node, double mx, double my) {
        if (node.type().equals("LIMIT_GATE") || node.type().equals("JUMP_INPUT") || node.type().equals("JUMP_OUTPUT")) return false;
        int nodeW = nodeWidth(node);
        return mx >= nodeScreenX(node) + scaled(nodeW - 23) && mx < nodeScreenX(node) + scaled(nodeW - 5)
                && my >= nodeScreenY(node) + scaled(4) && my < nodeScreenY(node) + scaled(22);
    }

    private record PortHit(String nodeId, String portKey) {}
    private record FilterHit(String itemId, boolean consumed) {}

    private int allOutputY(TransferGraphSyncPacket.NodeData node) {
        return nodeScreenY(node) + scaled(GraphNodeLayout.chestItemLocalY());
    }

    private int fluidOutputY(TransferGraphSyncPacket.NodeData node) {
        return nodeScreenY(node) + scaled(GraphNodeLayout.chestFluidLocalY());
    }

    private int energyOutputY(TransferGraphSyncPacket.NodeData node) {
        return nodeScreenY(node) + scaled(chestEnergyLocalY());
    }

    private int stressOutputY(TransferGraphSyncPacket.NodeData node) {
        return nodeScreenY(node) + scaled(GraphNodeLayout.chestStressLocalY());
    }

    private int firstFilterY(TransferGraphSyncPacket.NodeData node) {
        return nodeScreenY(node) + scaled(chestFirstFilterLocalY());
    }

    private int inputY(TransferGraphSyncPacket.NodeData node) {
        return nodeScreenY(node) + scaled(node.type().equals("REROUTE") || node.type().equals("TRASH")
                || node.type().equals("PLAYER_INVENTORY") || node.type().equals("LIMIT_GATE") || node.type().equals("JUMP_INPUT")
                ? nodeHeight(node) / 2 : GraphNodeLayout.chestItemLocalY());
    }

    private int chestInputY(TransferGraphSyncPacket.NodeData node, String sourcePort) {
        if (node.type().equals("REROUTE")) return nodeScreenY(node) + scaled(rerouteInputLocalY(sourcePort));
        if (!node.type().equals("CHEST")) return inputY(node);
        String inputPort = TransferEdge.inputPortFor(sourcePort);
        if (TransferEdge.FLUID_IN.equals(inputPort)) return createResourcesVisible() ? fluidOutputY(node) : allOutputY(node);
        if (TransferEdge.ENERGY_IN.equals(inputPort)) return energyOutputY(node);
        if (TransferEdge.STRESS_IN.equals(inputPort)) return createResourcesVisible() ? stressOutputY(node) : allOutputY(node);
        return allOutputY(node);
    }

    private int rerouteOutputLocalY(TransferGraphSyncPacket.NodeData node, String portKey) {
        List<String> ports = visibleRerouteOutputPorts(node);
        int index = ports.indexOf(portKey);
        if (index < 0) index = ports.indexOf(rerouteCategoryPort(portKey));
        if (index < 0) index = 0;
        return REROUTE_ROW_Y + index * 24;
    }

    private int rerouteInputLocalY(String portKey) {
        int index = Math.max(0, rerouteCategoryPorts().indexOf(rerouteCategoryPort(portKey)));
        return REROUTE_ROW_Y + index * 24;
    }

    private int rerouteOutputY(TransferGraphSyncPacket.NodeData node, String portKey) {
        return nodeScreenY(node) + scaled(rerouteOutputLocalY(node, portKey));
    }

    private PortHit outputAt(double mx, double my) {
        for (TransferGraphSyncPacket.NodeData node : visibleNodes()) {
            if (!node.type().equals("CHEST") && !node.type().equals("REROUTE")
                    && !node.type().equals("LIMIT_GATE") && !node.type().equals("JUMP_OUTPUT")) continue;
            int x = nodeScreenX(node) + scaled(node.type().equals("REROUTE") ? GraphNodeLayout.rerouteOutputPortLocalX()
                    : node.type().equals("JUMP_OUTPUT") ? JUMP_W - GraphNodeLayout.PORT_INSET
                    : node.type().equals("LIMIT_GATE") ? GATE_W - GraphNodeLayout.PORT_INSET
                    : GraphNodeLayout.outputPortLocalX());
            if (node.type().equals("REROUTE")) {
                for (String port : visibleRerouteOutputPorts(node)) {
                    if (hit(mx, my, x, rerouteOutputY(node, port))) return new PortHit(node.id(), port);
                }
                continue;
            }
            if (node.type().equals("LIMIT_GATE")) {
                String resource = incomingResource(node.id());
                if (resource != null && isVisibleResourcePort(resource) && hit(mx, my, x, inputY(node))) return new PortHit(node.id(), resource);
                continue;
            }
            if (node.type().equals("JUMP_OUTPUT")) {
                String resource = jumpOutputResource(node);
                if (resource != null && isVisibleResourcePort(resource) && hit(mx, my, x, nodeScreenY(node) + scaled(JUMP_H / 2))) return new PortHit(node.id(), resource);
                continue;
            }
            int y = allOutputY(node);
            if (hit(mx, my, x, y)) return new PortHit(node.id(), TransferEdge.PORT_ALL);
            if (createResourcesVisible() && hit(mx, my, x, fluidOutputY(node))) return new PortHit(node.id(), TransferEdge.FLUID_ALL);
            if (hit(mx, my, x, energyOutputY(node))) return new PortHit(node.id(), TransferEdge.ENERGY_FE);
            if (createResourcesVisible() && hit(mx, my, x, stressOutputY(node))) return new PortHit(node.id(), TransferEdge.STRESS_SU);
            if (!isExpanded(node)) continue;
            int iy = firstFilterY(node);
            for (String filter : node.filterItemIds()) {
                String filterPort = GraphResourceUtils.filterPort(filter);
                if (!isVisibleResourcePort(filterPort)) continue;
                if (hit(mx, my, x, iy)) return new PortHit(node.id(), filterPort);
                iy += scaled(18);
            }
        }
        return null;
    }

    private PortHit inputAt(double mx, double my) {
        for (TransferGraphSyncPacket.NodeData node : visibleNodes()) {
            if (!node.type().equals("CHEST") && !node.type().equals("REROUTE") && !node.type().equals("TRASH")
                    && !node.type().equals("PLAYER_INVENTORY") && !node.type().equals("LIMIT_GATE") && !node.type().equals("JUMP_INPUT")) continue;
            int x = nodeScreenX(node) + scaled(GraphNodeLayout.inputPortLocalX());
            if (node.type().equals("CHEST")) {
                if (hit(mx, my, x, allOutputY(node))) return new PortHit(node.id(), TransferEdge.ITEM_IN);
                if (createResourcesVisible() && hit(mx, my, x, fluidOutputY(node))) return new PortHit(node.id(), TransferEdge.FLUID_IN);
                if (hit(mx, my, x, energyOutputY(node))) return new PortHit(node.id(), TransferEdge.ENERGY_IN);
                if (createResourcesVisible() && hit(mx, my, x, stressOutputY(node))) return new PortHit(node.id(), TransferEdge.STRESS_IN);
            } else if (node.type().equals("REROUTE")) {
                String targetPort = TransferEdge.inputPortFor(linkingFromPort);
                if (hit(mx, my, x, nodeScreenY(node) + scaled(rerouteInputLocalY(linkingFromPort)))) return new PortHit(node.id(), targetPort);
            } else if (hit(mx, my, x, inputY(node))) {
                String targetPort = TransferEdge.inputPortFor(linkingFromPort);
                if (node.type().equals("LIMIT_GATE") && (!isExactResourcePort(linkingFromPort) || incomingEdge(node.id()) != null)) return null;
                if (node.type().equals("JUMP_INPUT") && incomingEdge(node.id()) != null) return null;
                if (node.type().equals("TRASH") && (TransferEdge.ENERGY_IN.equals(targetPort) || TransferEdge.STRESS_IN.equals(targetPort))) return null;
                if (node.type().equals("PLAYER_INVENTORY") && !TransferEdge.ITEM_IN.equals(targetPort)) return null;
                return new PortHit(node.id(), targetPort);
            }
        }
        return null;
    }

    private boolean hit(double mx, double my, int x, int y) {
        int p = Math.max(5, scaledPort());
        return mx >= x - p && mx < x + p && my >= y - p && my < y + p;
    }

    private boolean chestAddHit(TransferGraphSyncPacket.NodeData node, double mx, double my) {
        return false;
    }

    private SearchKind chestQuickFilterHit(TransferGraphSyncPacket.NodeData node, double mx, double my) {
        if (!node.type().equals("CHEST")) return null;
        double lx = nodeLocalX(node, mx);
        double ly = nodeLocalY(node, my);
        int bx = 106;
        if (inside(lx, ly, bx, GraphNodeLayout.chestItemLocalY() - 8, 16, 16)) return SearchKind.ITEM;
        if (createResourcesVisible() && inside(lx, ly, bx, GraphNodeLayout.chestFluidLocalY() - 8, 16, 16)) return SearchKind.FLUID;
        return null;
    }

    private FilterHit filterHit(TransferGraphSyncPacket.NodeData node, double mx, double my) {
        if (!node.type().equals("CHEST") || !isExpanded(node)) return null;
        int y = firstFilterY(node);
        for (String filter : node.filterItemIds()) {
            String filterPort = GraphResourceUtils.filterPort(filter);
            if (!isVisibleResourcePort(filterPort)) continue;
            if (mx >= nodeScreenX(node) + scaled(GraphNodeLayout.filterRemoveLocalX() - 8) && mx < nodeScreenX(node) + scaled(GraphNodeLayout.filterRemoveLocalX() + 9)
                    && my >= y - scaled(8) && my < y + scaled(8)) {
                removeFilterItem(node.id(), filter);
                return new FilterHit(filter, true);
            }
            if (mx >= nodeScreenX(node) + scaled(6) && mx < nodeScreenX(node) + scaled(GraphNodeLayout.filterRemoveLocalX() - 11)
                    && my >= y - scaled(9) && my < y + scaled(9)) return new FilterHit(filter, false);
            y += scaled(18);
        }
        return null;
    }

    private int[] inputPos(TransferGraphSyncPacket.NodeData node) {
        return inputPos(node, null);
    }

    private int[] inputPos(TransferGraphSyncPacket.NodeData node, String sourcePort) {
        return new int[]{nodeScreenX(node) + scaled(GraphNodeLayout.inputPortLocalX()), chestInputY(node, sourcePort)};
    }

    private int[] outputPos(TransferGraphSyncPacket.NodeData node, String portKey) {
        if (node.type().equals("REROUTE")) return new int[]{nodeScreenX(node) + scaled(GraphNodeLayout.rerouteOutputPortLocalX()), rerouteOutputY(node, portKey)};
        if (node.type().equals("LIMIT_GATE")) return new int[]{nodeScreenX(node) + scaled(GATE_W - GraphNodeLayout.PORT_INSET), inputY(node)};
        if (node.type().equals("JUMP_OUTPUT")) return new int[]{nodeScreenX(node) + scaled(JUMP_W - GraphNodeLayout.PORT_INSET), nodeScreenY(node) + scaled(JUMP_H / 2)};
        if (TransferEdge.PORT_ALL.equals(portKey)) return new int[]{nodeScreenX(node) + scaled(GraphNodeLayout.outputPortLocalX()), allOutputY(node)};
        if (TransferEdge.FLUID_ALL.equals(portKey) && createResourcesVisible()) return new int[]{nodeScreenX(node) + scaled(GraphNodeLayout.outputPortLocalX()), fluidOutputY(node)};
        if (TransferEdge.ENERGY_FE.equals(portKey)) return new int[]{nodeScreenX(node) + scaled(GraphNodeLayout.outputPortLocalX()), energyOutputY(node)};
        if (TransferEdge.STRESS_SU.equals(portKey) && createResourcesVisible()) return new int[]{nodeScreenX(node) + scaled(GraphNodeLayout.outputPortLocalX()), stressOutputY(node)};
        if (!isExpanded(node)) return new int[]{nodeScreenX(node) + scaled(GraphNodeLayout.outputPortLocalX()), allOutputY(node)};
        int y = firstFilterY(node);
        for (String filter : node.filterItemIds()) {
            String filterPort = GraphResourceUtils.filterPort(filter);
            if (!isVisibleResourcePort(filterPort)) continue;
            if (filterPort.equals(portKey)) return new int[]{nodeScreenX(node) + scaled(GraphNodeLayout.outputPortLocalX()), y};
            y += scaled(18);
        }
        return new int[]{nodeScreenX(node) + scaled(GraphNodeLayout.outputPortLocalX()), allOutputY(node)};
    }

    private float laneOffset(TransferGraphSyncPacket.EdgeData edge) {
        List<TransferGraphSyncPacket.EdgeData> group = visibleEdges().stream()
                .filter(e -> e.fromNodeId().equals(edge.fromNodeId()) && e.fromPortKey().equals(edge.fromPortKey()))
                .toList();
        if (group.size() <= 1) return 0;
        int index = group.indexOf(edge);
        if (index < 0) return 0;
        float gap = (float) Math.max(7.0, 16.0 * zoom);
        return (index - (group.size() - 1) * 0.5f) * gap;
    }

    private String edgeNear(double mx, double my) {
        String bestId = null;
        double bestDistance = Double.MAX_VALUE;
        for (TransferGraphSyncPacket.EdgeData edge : visibleEdges()) {
            TransferGraphSyncPacket.NodeData from = node(edge.fromNodeId());
            TransferGraphSyncPacket.NodeData to = node(edge.toNodeId());
            if (from == null || to == null) continue;
            int[] a = outputPos(from, edge.fromPortKey());
            int[] b = inputPos(to, edge.fromPortKey());
            double distance = distanceToBezier(mx, my, a[0], a[1], b[0], b[1], laneOffset(edge));
            if (distance < bestDistance) {
                bestDistance = distance;
                bestId = edge.id();
            }
        }
        return bestDistance <= Math.max(7.0, canvas.scaledLineWidth(3.1f) + 5.0f) ? bestId : null;
    }

    private double distanceToBezier(double mx, double my, double x0, double y0, double x3, double y3, float laneOffset) {
        double dx = Math.max(42, Math.abs(x3 - x0) * 0.46);
        double x1 = x0 + dx, y1 = y0 + laneOffset, x2 = x3 - dx, y2 = y3 + laneOffset;
        int segments = Math.max(48, Math.min(160, (int) Math.ceil(Math.hypot(x3 - x0, y3 - y0) / 4.0)));
        double best = Double.MAX_VALUE;
        double px = x0, py = y0;
        for (int i = 1; i <= segments; i++) {
            double t = i / (double) segments;
            double x = GraphCanvas.cubic(x0, x1, x2, x3, t);
            double y = GraphCanvas.cubic(y0, y1, y2, y3, t);
            best = Math.min(best, distanceToSegment(mx, my, px, py, x, y));
            px = x;
            py = y;
        }
        return best;
    }

    private double distanceToSegment(double px, double py, double x0, double y0, double x1, double y1) {
        double dx = x1 - x0;
        double dy = y1 - y0;
        double len2 = dx * dx + dy * dy;
        if (len2 < 0.0001) return Math.hypot(px - x0, py - y0);
        double t = ((px - x0) * dx + (py - y0) * dy) / len2;
        t = Math.max(0.0, Math.min(1.0, t));
        return Math.hypot(px - (x0 + dx * t), py - (y0 + dy * t));
    }

    private List<String> rerouteOutputPorts(TransferGraphSyncPacket.NodeData node) {
        LinkedHashSet<String> ports = new LinkedHashSet<>();
        Set<String> scope = inputScopes().getOrDefault(node.id(), Set.of());
        boolean hasItemScope = scope.stream().anyMatch(resource -> resource.startsWith(TransferEdge.ITEM_PREFIX));
        boolean hasFluidScope = scope.stream().anyMatch(resource -> resource.startsWith(TransferEdge.FLUID_PREFIX));
        if (scope.contains(SCOPE_ALL) || scope.isEmpty()) {
            ports.add(TransferEdge.PORT_ALL);
        }
        if (hasItemScope) ports.add(TransferEdge.PORT_ALL);
        if (createResourcesVisible() && (scope.contains(TransferEdge.FLUID_ALL) || hasFluidScope || scope.isEmpty())) ports.add(TransferEdge.FLUID_ALL);
        if (scope.contains(TransferEdge.ENERGY_FE) || scope.isEmpty()) ports.add(TransferEdge.ENERGY_FE);
        if (createResourcesVisible() && (scope.contains(TransferEdge.STRESS_SU) || scope.isEmpty())) ports.add(TransferEdge.STRESS_SU);
        for (String resource : scope) {
            if (!isVisibleResourcePort(resource)) continue;
            if (resource.startsWith(TransferEdge.ITEM_PREFIX) || resource.startsWith(TransferEdge.FLUID_PREFIX)
                    || TransferEdge.ENERGY_FE.equals(resource) || TransferEdge.STRESS_SU.equals(resource)) ports.add(resource);
        }
        for (String filter : node.filterItemIds()) {
            String port = GraphResourceUtils.filterPort(filter);
            if (isVisibleResourcePort(port)) ports.add(port);
        }
        for (TransferGraphSyncPacket.NodeFlowData flow : node.flowStats()) {
            if (isVisibleResourcePort(flow.itemId())) ports.add(flow.itemId());
        }
        for (TransferGraphSyncPacket.EdgeData edge : visibleEdges()) {
            if (edge.fromNodeId().equals(node.id())) ports.add(edge.fromPortKey());
        }
        return new ArrayList<>(ports);
    }

    private List<TransferGraphSyncPacket.NodeFlowData> rerouteFlowRows(TransferGraphSyncPacket.NodeData node) {
        List<TransferGraphSyncPacket.NodeFlowData> rows = new ArrayList<>();
        for (TransferGraphSyncPacket.NodeFlowData flow : node.flowStats()) {
            if (isVisibleResourcePort(flow.itemId())) rows.add(flow);
        }
        Set<String> seen = new LinkedHashSet<>();
        for (TransferGraphSyncPacket.NodeFlowData flow : rows) seen.add(flow.itemId());
        Set<String> scope = inputScopes().getOrDefault(node.id(), Set.of());
        if (!scope.contains(SCOPE_ALL) || !scope.contains(TransferEdge.FLUID_ALL)) {
            for (String itemId : scope) {
                if (itemId.equals(SCOPE_ALL) || itemId.equals(TransferEdge.FLUID_ALL)) continue;
                if (!isVisibleResourcePort(itemId)) continue;
                if (seen.add(itemId)) rows.add(new TransferGraphSyncPacket.NodeFlowData(itemId, 0, 0, 0, 0));
            }
        }
        for (String itemId : node.filterItemIds()) {
            String resourceId = GraphResourceUtils.filterPort(itemId);
            if (!isVisibleResourcePort(resourceId)) continue;
            if (seen.add(resourceId)) rows.add(new TransferGraphSyncPacket.NodeFlowData(resourceId, 0, 0, 0, 0));
        }
        return rows;
    }

    private Map<String, TransferGraphSyncPacket.NodeFlowData> rerouteFlowMap(TransferGraphSyncPacket.NodeData node) {
        Map<String, TransferGraphSyncPacket.NodeFlowData> flows = new HashMap<>();
        for (TransferGraphSyncPacket.NodeFlowData flow : rerouteFlowRows(node)) flows.put(flow.itemId(), flow);
        return flows;
    }

    private TransferGraphSyncPacket.NodeFlowData flowForPort(String port, Map<String, TransferGraphSyncPacket.NodeFlowData> flows) {
        if (TransferEdge.PORT_ALL.equals(port)) return aggregateFlow(port, flows, TransferEdge.ITEM_PREFIX);
        if (TransferEdge.FLUID_ALL.equals(port)) return aggregateFlow(port, flows, TransferEdge.FLUID_PREFIX);
        if (TransferEdge.ENERGY_FE.equals(port) || TransferEdge.STRESS_SU.equals(port)) {
            TransferGraphSyncPacket.NodeFlowData flow = flows.get(port);
            return flow != null ? flow : new TransferGraphSyncPacket.NodeFlowData(port, 0, 0, 0, 0);
        }
        if (port != null && port.startsWith(TransferEdge.ITEM_PREFIX)) {
            TransferGraphSyncPacket.NodeFlowData flow = flows.get(port);
            return flow != null ? flow : new TransferGraphSyncPacket.NodeFlowData(port, 0, 0, 0, 0);
        }
        if (port != null && port.startsWith(TransferEdge.FLUID_PREFIX) && !TransferEdge.FLUID_ALL.equals(port)) {
            TransferGraphSyncPacket.NodeFlowData flow = flows.get(port);
            return flow != null ? flow : new TransferGraphSyncPacket.NodeFlowData(port, 0, 0, 0, 0);
        }
        int inputRate = 0;
        int outputRate = 0;
        long inputTotal = 0;
        long outputTotal = 0;
        for (TransferGraphSyncPacket.NodeFlowData flow : flows.values()) {
            inputRate += flow.inputRatePerMinute();
            outputRate += flow.outputRatePerMinute();
            inputTotal += flow.inputTotal();
            outputTotal += flow.outputTotal();
        }
        return new TransferGraphSyncPacket.NodeFlowData(port == null ? TransferEdge.PORT_ALL : port, inputRate, outputRate, inputTotal, outputTotal);
    }

    private TransferGraphSyncPacket.NodeFlowData aggregateFlow(String port, Map<String, TransferGraphSyncPacket.NodeFlowData> flows, String prefix) {
        int inputRate = 0;
        int outputRate = 0;
        long inputTotal = 0;
        long outputTotal = 0;
        for (TransferGraphSyncPacket.NodeFlowData flow : flows.values()) {
            if (!flow.itemId().startsWith(prefix)) continue;
            inputRate += flow.inputRatePerMinute();
            outputRate += flow.outputRatePerMinute();
            inputTotal += flow.inputTotal();
            outputTotal += flow.outputTotal();
        }
        return new TransferGraphSyncPacket.NodeFlowData(port, inputRate, outputRate, inputTotal, outputTotal);
    }

    private int reroutePopupHeight(TransferGraphSyncPacket.NodeData node) {
        return 74 + Math.max(1, Math.min(5, rerouteFlowRows(node).size())) * 20;
    }

    private Map<String, Set<String>> inputScopes() {
        Map<String, Set<String>> scopes = new HashMap<>();
        for (TransferGraphSyncPacket.EdgeData edge : visibleEdges()) {
            TransferGraphSyncPacket.NodeData from = node(edge.fromNodeId());
            if (from != null && from.type().equals("CHEST")) addScope(scopes.computeIfAbsent(edge.toNodeId(), id -> new LinkedHashSet<>()), edge.fromPortKey(), chestSourceScopes());
        }
        boolean changed;
        do {
            changed = false;
            for (TransferGraphSyncPacket.NodeData node : visibleNodes()) {
                if (!node.type().equals("JUMP_INPUT")) continue;
                TransferGraphSyncPacket.NodeData output = linkedJumpOutput(node);
                if (output == null) continue;
                Set<String> scope = scopes.getOrDefault(node.id(), Set.of());
                if (scope.isEmpty()) continue;
                Set<String> target = scopes.computeIfAbsent(output.id(), id -> new LinkedHashSet<>());
                int before = target.size();
                target.addAll(scope);
                changed |= target.size() != before;
            }
            for (TransferGraphSyncPacket.EdgeData edge : visibleEdges()) {
                TransferGraphSyncPacket.NodeData from = node(edge.fromNodeId());
                if (from == null || !propagatesOutgoingScope(from)) continue;
                Set<String> allowed = scopes.getOrDefault(from.id(), Set.of());
                if (allowed.isEmpty()) continue;
                Set<String> target = scopes.computeIfAbsent(edge.toNodeId(), id -> new LinkedHashSet<>());
                int before = target.size();
                addScope(target, edge.fromPortKey(), allowed);
                changed |= target.size() != before;
            }
        } while (changed);
        return scopes;
    }

    private void addScope(Set<String> target, String port, Set<String> allowed) {
        if (!isVisibleResourcePort(port)) return;
        if (TransferEdge.PORT_ALL.equals(port)) {
            if (allowed.contains(SCOPE_ALL)) target.add(SCOPE_ALL);
            for (String resource : allowed) if (resource.startsWith(TransferEdge.ITEM_PREFIX)) target.add(resource);
        } else if (TransferEdge.FLUID_ALL.equals(port)) {
            if (allowed.contains(TransferEdge.FLUID_ALL)) target.add(TransferEdge.FLUID_ALL);
            for (String resource : allowed) if (resource.startsWith(TransferEdge.FLUID_PREFIX)) target.add(resource);
        } else if (port != null && port.startsWith(TransferEdge.ITEM_PREFIX)) {
            if (allowed.contains(SCOPE_ALL) || allowed.contains(port)) target.add(port);
        } else if (port != null && port.startsWith(TransferEdge.FLUID_PREFIX)) {
            if (allowed.contains(TransferEdge.FLUID_ALL) || allowed.contains(port)) target.add(port);
        } else if (TransferEdge.ENERGY_FE.equals(port)) {
            if (allowed.contains(TransferEdge.ENERGY_FE)) target.add(port);
        } else if (TransferEdge.STRESS_SU.equals(port)) {
            if (allowed.contains(TransferEdge.STRESS_SU)) target.add(port);
        }
    }

    private boolean propagatesOutgoingScope(TransferGraphSyncPacket.NodeData node) {
        return node.type().equals("REROUTE") || node.type().equals("LIMIT_GATE") || node.type().equals("JUMP_OUTPUT");
    }

    private List<String> searchResults() {
        String q = searchValue.trim().toLowerCase();
        if (q.equals(lastSearch)) return cachedSearch;
        lastSearch = q;
        if (q.isEmpty()) return cachedSearch = List.of();
        List<String> result = new ArrayList<>();
        if (createResourcesVisible() && searchKind != SearchKind.ITEM) {
            for (Fluid fluid : BuiltInRegistries.FLUID) {
                if (fluid == Fluids.EMPTY) continue;
                ResourceLocation id = BuiltInRegistries.FLUID.getKey(fluid);
                if (id.getPath().startsWith("flowing_")) continue;
                String full = id.toString().toLowerCase();
                String name = new FluidStack(fluid, 1).getHoverName().getString();
                // 资源名走拼音（装了 JEC 即生效），id / 路径段走普通包含。
                if (com.pockethomestead.client.search.ItemSearch.matches(name, full, q)) {
                    result.add(TransferEdge.fluidPort(id.toString()));
                    if (result.size() >= 7) break;
                }
            }
        }
        if (searchKind != SearchKind.FLUID) {
            for (Item item : BuiltInRegistries.ITEM) {
                if (result.size() >= 7) break;
                if (item == Items.AIR) continue;
                ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
                String full = id.toString().toLowerCase();
                String name = new ItemStack(item).getHoverName().getString();
                if (com.pockethomestead.client.search.ItemSearch.matches(name, full, q)) {
                    result.add(id.toString());
                    if (result.size() >= 7) break;
                }
            }
        }
        return cachedSearch = result;
    }

    private void resetSearchCache() {
        lastSearch = "\u0000";
        cachedSearch = List.of();
    }

    private int searchPopupHeight(List<String> results) {
        return 54 + Math.max(1, Math.min(5, results.size())) * 20 + 8;
    }

    private String currentGraphKind() {
        if (!pendingGraphKind.isBlank()) return pendingGraphKind;
        return ClientTransferGraphCache.graphKind() == null || ClientTransferGraphCache.graphKind().isBlank()
                ? "PRIVATE"
                : ClientTransferGraphCache.graphKind();
    }

    private String currentGraphId() {
        if (!pendingGraphKind.isBlank()) return pendingGraphId;
        return ClientTransferGraphCache.graphId() == null ? "" : ClientTransferGraphCache.graphId();
    }

    private String currentGraphLabel() {
        for (TransferGraphSyncPacket.GraphOptionData option : ClientTransferGraphCache.graphOptions()) {
            if (option.kind().equals(currentGraphKind()) && option.id().equals(currentGraphId())) return option.label();
        }
        return switch (currentGraphKind()) {
            case "PUBLIC" -> "公开图";
            case "PROTECTED" -> "团队图";
            case "SPACE" -> "空间图";
            default -> "我的私有图";
        };
    }

    private void requestGraph(String kind, String id) {
        dirty = false;
        savePending = false;
        validationStale = true;
        draftPages.clear();
        draftNodes.clear();
        draftEdges.clear();
        pendingGraphKind = kind == null || kind.isBlank() ? "PRIVATE" : kind;
        pendingGraphId = id == null ? "" : id;
        graphSyncTicker = 0;
        PacketDistributor.sendToServer(new RequestTransferGraphPacket(kind, id));
    }

    private boolean canAddPlayerNode() {
        return !"PUBLIC".equals(currentGraphKind()) && !"SPACE".equals(currentGraphKind()) && Minecraft.getInstance().player != null;
    }

    private void loadDraftFromCache() {
        draftPages = copyPages(ClientTransferGraphCache.pages());
        if (draftPages.isEmpty()) draftPages.add(new TransferGraphSyncPacket.PageData(TransferGraph.DEFAULT_PAGE_ID, "默认页", true, 0));
        draftNodes = copyNodes(ClientTransferGraphCache.nodes());
        draftEdges = copyEdges(ClientTransferGraphCache.edges());
        if (page(activePageId) == null) activePageId = firstPage().id();
    }

    private void ensureDraft() {
        if (draftPages.isEmpty()) loadDraftFromCache();
    }

    private List<TransferGraphSyncPacket.PageData> pages() {
        return draftPages;
    }

    private List<TransferGraphSyncPacket.NodeData> nodes() {
        return draftNodes;
    }

    private List<TransferGraphSyncPacket.EdgeData> edges() {
        return draftEdges;
    }

    private TransferGraphSyncPacket.ChestData chestDataFor(TransferGraphSyncPacket.NodeData node) {
        if (node == null || !node.type().equals("CHEST")) return null;
        for (TransferGraphSyncPacket.ChestData chest : ClientTransferGraphCache.chests()) {
            if (chest.pos() == node.pos()
                    && chest.chestId().equals(node.chestId())
                    && chest.dimensionKey().equals(node.dimensionKey())) {
                return chest;
            }
        }
        return null;
    }

    private TransferGraphSyncPacket.EdgeData edge(String edgeId) {
        if (edgeId == null) return null;
        for (TransferGraphSyncPacket.EdgeData edge : edges()) if (edge.id().equals(edgeId)) return edge;
        return null;
    }

    private TransferGraphSyncPacket.NodeData node(String nodeId) {
        if (nodeId == null) return null;
        for (TransferGraphSyncPacket.NodeData node : nodes()) if (node.id().equals(nodeId)) return node;
        return null;
    }

    private TransferGraphSyncPacket.PageData page(String pageId) {
        if (pageId == null) return null;
        for (TransferGraphSyncPacket.PageData page : pages()) if (page.id().equals(pageId)) return page;
        return null;
    }

    private TransferGraphSyncPacket.PageData firstPage() {
        ensureDraft();
        return pages().isEmpty() ? new TransferGraphSyncPacket.PageData(TransferGraph.DEFAULT_PAGE_ID, "默认页", true, 0) : pages().get(0);
    }

    private int pageNodeCount(String pageId) {
        int count = 0;
        for (TransferGraphSyncPacket.NodeData node : nodes()) if (node.pageId().equals(pageId)) count++;
        return count;
    }

    private boolean pageEnabled(String pageId) {
        TransferGraphSyncPacket.PageData p = page(pageId);
        return p == null || p.enabled();
    }

    private List<TransferGraphSyncPacket.PageData> copyPages(List<TransferGraphSyncPacket.PageData> pages) {
        List<TransferGraphSyncPacket.PageData> copy = new ArrayList<>();
        for (TransferGraphSyncPacket.PageData p : pages) copy.add(new TransferGraphSyncPacket.PageData(p.id(), p.name(), p.enabled(), p.order()));
        return copy;
    }

    private List<TransferGraphSyncPacket.NodeData> copyNodes(List<TransferGraphSyncPacket.NodeData> nodes) {
        List<TransferGraphSyncPacket.NodeData> copy = new ArrayList<>();
        for (TransferGraphSyncPacket.NodeData n : nodes) {
            copy.add(new TransferGraphSyncPacket.NodeData(n.id(), n.pageId(), n.type(), n.chestId(), n.dimensionKey(), n.pos(), n.x(), n.y(),
                    n.expanded(), n.enabled(), List.copyOf(n.filterItemIds()), List.copyOf(n.receiveFilterIds()),
                    n.targetPlayerId(), List.copyOf(n.replenishRules()), List.copyOf(n.flowStats()),
                    n.label(), n.linkedNodeId(), n.gateMin(), n.gateMax(), n.gateCheckSource()));
        }
        return copy;
    }

    private List<TransferGraphSyncPacket.EdgeData> copyEdges(List<TransferGraphSyncPacket.EdgeData> edges) {
        List<TransferGraphSyncPacket.EdgeData> copy = new ArrayList<>();
        for (TransferGraphSyncPacket.EdgeData e : edges) {
            copy.add(new TransferGraphSyncPacket.EdgeData(e.id(), e.pageId(), e.fromNodeId(), e.toNodeId(), e.fromPortKey(), e.toPortKey(),
                    e.enabled(), e.health(), e.actualRatePerMinute(), List.copyOf(e.itemRates())));
        }
        return copy;
    }

    private boolean hasIssueForEdge(String edgeId) {
        if (validationStale || edgeId == null) return false;
        for (TransferGraphValidationPacket.IssueData issue : ClientTransferGraphCache.validationIssues()) {
            if (edgeId.equals(issue.edgeId()) && "ERROR".equals(issue.severity())) return true;
        }
        return false;
    }

    private boolean hasIssueForNode(String nodeId) {
        if (validationStale || nodeId == null) return false;
        for (TransferGraphValidationPacket.IssueData issue : ClientTransferGraphCache.validationIssues()) {
            if (nodeId.equals(issue.nodeId()) && "ERROR".equals(issue.severity())) return true;
        }
        return false;
    }

    private boolean hasValidationErrors() {
        if (validationStale) return false;
        for (TransferGraphValidationPacket.IssueData issue : ClientTransferGraphCache.validationIssues()) {
            if ("ERROR".equals(issue.severity())) return true;
        }
        return false;
    }

    private void syncRateEditors(TransferGraphSyncPacket.EdgeData edge) {
        TransferGraphSyncPacket.EdgeItemRateData row = GraphResourceUtils.edgeRateRow(edge, selectedRateItemId);
        if (row == null) {
            if (focusedEdgeField != EdgeField.SECONDS) rateSecondsValue = "1";
            if (focusedEdgeField != EdgeField.ITEMS) rateItemsValue = "64";
            return;
        }
        if (focusedEdgeField != EdgeField.SECONDS) rateSecondsValue = String.valueOf(row.rateLimitSeconds());
        if (focusedEdgeField != EdgeField.ITEMS) rateItemsValue = String.valueOf(row.rateLimitItems());
    }

    private int parseRateSeconds() {
        try {
            return TransferEdge.clampSeconds(Integer.parseInt(rateSecondsValue.trim()));
        } catch (Exception e) {
            return 1;
        }
    }

    private int parseRateItems() {
        try {
            return TransferEdge.clampRate(Integer.parseInt(rateItemsValue.trim()));
        } catch (Exception e) {
            return 64;
        }
    }

    private void appendEdgeField(char c) {
        if (focusedEdgeField == EdgeField.SECONDS && rateSecondsValue.length() < 5) rateSecondsValue += c;
        else if (focusedEdgeField == EdgeField.ITEMS && rateItemsValue.length() < 6) rateItemsValue += c;
    }

    private void backspaceEdgeField() {
        if (focusedEdgeField == EdgeField.SECONDS && !rateSecondsValue.isEmpty()) rateSecondsValue = rateSecondsValue.substring(0, rateSecondsValue.length() - 1);
        else if (focusedEdgeField == EdgeField.ITEMS && !rateItemsValue.isEmpty()) rateItemsValue = rateItemsValue.substring(0, rateItemsValue.length() - 1);
    }

    private void clearEdgeField() {
        if (focusedEdgeField == EdgeField.SECONDS) rateSecondsValue = "";
        else if (focusedEdgeField == EdgeField.ITEMS) rateItemsValue = "";
    }

    private double edgePopupLocalX(double mx) {
        return scaledPopupLocalX(mx);
    }

    private double edgePopupLocalY(double my) {
        return scaledPopupLocalY(my);
    }

    private double scaledPopupLocalX(double mx) {
        return (mx - popupX) / canvas.canvasUiScale();
    }

    private double scaledPopupLocalY(double my) {
        return (my - popupY) / canvas.canvasUiScale();
    }

    private boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}
