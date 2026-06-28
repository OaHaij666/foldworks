package com.pockethomestead.client.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.pockethomestead.client.ClientTransferGraphCache;
import com.pockethomestead.client.ui.Theme;
import com.pockethomestead.network.RequestTransferGraphPacket;
import com.pockethomestead.network.SaveTransferGraphPacket;
import com.pockethomestead.network.TransferGraphSyncPacket;
import com.pockethomestead.network.TransferGraphValidationPacket;
import com.pockethomestead.transfer.TransferEdge;
import com.pockethomestead.transfer.TransferGraph;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
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
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class TransferGraphScreen extends Screen {
    private static final int NODE_W = 190;
    private static final int REROUTE_W = 220;
    private static final int TRASH_W = 172;
    private static final int REROUTE_ROW_Y = 54;
    private static final int SEARCH_W = 190;
    private static final int HELP_W = 292;
    private static final int HELP_H = 274;
    private static final int PORT = 7;
    private static final int HEADER_H = 30;
    private static final int TAB_H = 24;
    private static final int CARD_RADIUS = 7;
    private static final int POPUP_RADIUS = 6;
    private static final String SCOPE_ALL = "*";
    private static final int PORT_INSET = 12;
    private static final int COLLAPSED_CHEST_H = 128;
    private static final int COLLAPSED_REROUTE_H = 92;

    private int panX = 70;
    private int panY = 70;
    private double zoom = 1.0;
    private String activePageId = TransferGraph.DEFAULT_PAGE_ID;
    private MenuMode menuMode = MenuMode.NONE;
    private PopupMode popupMode = PopupMode.NONE;
    private int menuX, menuY, popupX, popupY, pendingNodeX, pendingNodeY;
    private String selectedNodeId, selectedEdgeId, selectedItemId, pendingDeletePageId;
    private String draggingNodeId;
    private int dragOffX, dragOffY;
    private int dragPreviewX, dragPreviewY;
    private boolean dragMoved;
    private boolean draggingPopup;
    private boolean exitAfterSave;
    private int popupDragOffX, popupDragOffY;
    private String linkingFromNodeId, linkingFromPort = TransferEdge.PORT_ALL;
    private EditBox pageNameEdit;
    private EdgeField focusedEdgeField = EdgeField.NONE;
    private String selectedRateItemId;
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
    private int graphSyncTicker;

    private enum MenuMode { NONE, ROOT, CHEST_LIST, PAGE_ACTION, PAGE_DELETE_CONFIRM, PAGE_RENAME, PAGE_CREATE }
    private enum PopupMode { NONE, NODE, EDGE, SEARCH, HELP, EXIT_CONFIRM }
    private enum EdgeField { NONE, SECONDS, ITEMS }
    private enum SearchKind { ALL, ITEM, FLUID }

    public TransferGraphScreen() {
        super(Component.literal("可视化传输图"));
    }

    @Override
    protected void init() {
        pageNameEdit = new EditBox(font, 0, 0, 108, 16, Component.literal("分页名称"));
        pageNameEdit.setMaxLength(24);
        pageNameEdit.visible = false;
        addRenderableWidget(pageNameEdit);

        if (draftPages.isEmpty()) loadDraftFromCache();
        PacketDistributor.sendToServer(new RequestTransferGraphPacket());
    }

    public void onGraphSynced() {
        if (!dirty || savePending || draftPages.isEmpty()) {
            loadDraftFromCache();
            dirty = false;
            savePending = false;
            validationStale = true;
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
        g.fill(0, 0, width, height, 0xFFF7FBFF);
        renderGrid(g);
        renderTabs(g, mx, my);
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
            PacketDistributor.sendToServer(new RequestTransferGraphPacket());
        }
    }

    private void syncWidgets() {
        if (pageNameEdit != null) {
            pageNameEdit.visible = menuMode == MenuMode.PAGE_RENAME || menuMode == MenuMode.PAGE_CREATE;
            pageNameEdit.setX(menuX + 8);
            pageNameEdit.setY(menuY + 8);
        }
    }

    private void renderHeader(GuiGraphics g) {
        g.fill(0, 0, width, HEADER_H, Theme.HEADER);
        g.fill(0, HEADER_H - 2, width, HEADER_H, 0x12000000);
        Theme.hLine(g, 0, HEADER_H - 1, width, Theme.BORDER);
        text(g, "可视化传输图", 10, 10, Theme.TEXT);

        int statusColor = hasValidationErrors() ? Theme.DANGER : dirty ? Theme.PRIMARY_PRESS : Theme.SUCCESS;
        String status = hasValidationErrors() ? "有错误，保存会被拒绝" : dirty ? "未保存" : "已保存";
        text(g, status, 122, 10, statusColor);
        if (!validationStale && !ClientTransferGraphCache.validationIssues().isEmpty()) {
            text(g, "问题 " + ClientTransferGraphCache.validationIssues().size(), 232, 10, statusColor);
        }

        int saveX = width - 214;
        chip(g, saveX, 6, 54, 18, dirty ? Theme.PRIMARY_SOFT : Theme.SURFACE_ALT, dirty ? Theme.PRIMARY : Theme.BORDER);
        text(g, savePending ? "保存中" : "保存", saveX + 13, 11, dirty ? Theme.PRIMARY_PRESS : Theme.TEXT_MUTED);
        chip(g, saveX + 62, 6, 54, 18, Theme.SURFACE, Theme.BORDER);
        text(g, "放弃", saveX + 75, 11, dirty ? Theme.DANGER : Theme.TEXT_MUTED);
        int helpX = width - 86;
        chip(g, helpX, 6, 20, 18, popupMode == PopupMode.HELP ? Theme.PRIMARY_SOFT : Theme.SURFACE, popupMode == PopupMode.HELP ? Theme.PRIMARY : Theme.BORDER);
        text(g, "?", helpX + 7, 11, Theme.PRIMARY_PRESS);
        textRight(g, Math.round(zoom * 100) + "%", width - 10, 10, Theme.PRIMARY_PRESS);
    }

    private void renderTabs(GuiGraphics g, int mx, int my) {
        int x = 8;
        int y = HEADER_H + 6;
        TransferGraphSyncPacket.PageData activePage = page(activePageId);
        if (activePage == null) activePage = firstPage();
        String label = activePage.enabled() ? activePage.name() : "⊘ " + activePage.name();
        int w = Math.max(74, Theme.styledWidth(font, label) + 28);
        int fill = activePage.enabled() ? Theme.PRIMARY_SOFT : 0xFFE7EBEF;
        int border = activePage.enabled() ? Theme.PRIMARY : 0xFFC9D2DC;
        chip(g, x, y, w, TAB_H - 4, fill, border);
        text(g, label, x + 8, y + 6, Theme.TEXT);
        x += w + 5;
        chip(g, x, y, 22, TAB_H - 4, Theme.SURFACE, Theme.BORDER);
        text(g, "+", x + 8, y + 6, Theme.PRIMARY_PRESS);
        x += 28;
        chip(g, x, y, 22, TAB_H - 4, fill, Theme.BORDER_STRONG);
        text(g, "○", x + 6, y + 6, Theme.PRIMARY_PRESS);
    }

    private void crispPanel(GuiGraphics g, int x, int y, int w, int h, int fill, int border) {
        Theme.shadow(g, x, y, w, h, POPUP_RADIUS);
        Theme.panel(g, x, y, w, h, POPUP_RADIUS, fill, border);
    }

    private void chip(GuiGraphics g, int x, int y, int w, int h, int fill, int border) {
        Theme.panel(g, x, y, w, h, Math.min(8, h / 2), fill, border);
    }

    private void text(GuiGraphics g, String s, int x, int y, int color) {
        Theme.text(g, font, s, x, y, color);
    }

    private void textRight(GuiGraphics g, String s, int rx, int y, int color) {
        Theme.textRight(g, font, s, rx, y, color);
    }

    private void renderGrid(GuiGraphics g) {
        int grid = Math.max(18, (int) Math.round(48 * zoom));
        int top = HEADER_H + TAB_H + 8;
        g.fill(0, top, width, height, 0xFFF7FBFF);
        int index = 0;
        for (int x = Math.floorMod(panX, grid); x < width; x += grid) {
            Theme.vLine(g, x, top, height - top, index++ % 4 == 0 ? 0x147CB3E8 : 0x087CB3E8);
        }
        index = 0;
        for (int y = top + Math.floorMod(panY, grid); y < height; y += grid) {
            Theme.hLine(g, 0, y, width, index++ % 4 == 0 ? 0x147CB3E8 : 0x087CB3E8);
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
            if (node.type().equals("TRASH")) {
                renderTrashNode(g, node, border);
                g.pose().popPose();
                continue;
            }

            Theme.shadow(g, 0, 0, NODE_W, h, CARD_RADIUS);
            Theme.panel(g, 0, 0, NODE_W, h, CARD_RADIUS, fill, border);
            int header = node.enabled() ? Theme.PRIMARY_SOFT : 0xFFDDE3EA;
            Theme.fillRound(g, 2, 2, NODE_W - 4, 22, CARD_RADIUS - 1, header);
            text(g, (node.enabled() ? "" : "⊘ ") + "传输箱 " + node.chestId(), 8, 7, node.enabled() ? Theme.TEXT : Theme.TEXT_MUTED);
            drawExpandButton(g, node, NODE_W);
            text(g, shortPos(node.pos()), 8, 30, Theme.TEXT_MUTED);
            renderBandwidthStatus(g, node, 82, 29);
            renderChestNode(g, node, 0, 0);
            g.pose().popPose();
        }
    }

    private void renderRerouteNode(GuiGraphics g, TransferGraphSyncPacket.NodeData node, int border) {
        int h = nodeHeight(node);
        Theme.shadow(g, 0, 0, REROUTE_W, h, CARD_RADIUS);
        Theme.panel(g, 0, 0, REROUTE_W, h, CARD_RADIUS, node.enabled() ? Theme.SURFACE : 0xFFE9EDF2, border);
        Theme.fillRound(g, 2, 2, REROUTE_W - 4, 24, CARD_RADIUS - 1, node.enabled() ? 0xFFEAF6FF : 0xFFDDE3EA);
        text(g, (node.enabled() ? "" : "⊘ ") + "中转节点", 10, 8, node.enabled() ? Theme.TEXT : Theme.TEXT_MUTED);
        drawExpandButton(g, node, REROUTE_W);
        if (isExpanded(node)) text(g, "+ 输出", REROUTE_W - 78, 8, node.enabled() ? Theme.PRIMARY_PRESS : Theme.TEXT_MUTED);
        text(g, "聚合输入", 24, 34, node.enabled() ? Theme.TEXT_MUTED : Theme.TEXT_FAINT);
        if (isExpanded(node)) {
            text(g, "入", 112, 34, Theme.TEXT_MUTED);
            text(g, "出", 158, 34, Theme.TEXT_MUTED);
        } else {
            text(g, "输出 " + rerouteOutputPorts(node).size() + " 项", 92, 34, Theme.TEXT_MUTED);
        }
        drawPort(g, inputPortLocalX(), h / 2, node.enabled() ? Theme.SUCCESS : Theme.TEXT_FAINT);

        Map<String, TransferGraphSyncPacket.NodeFlowData> flows = rerouteFlowMap(node);
        List<String> ports = visibleRerouteOutputPorts(node);
        for (String port : ports) {
            int py = rerouteOutputLocalY(node, port);
            TransferGraphSyncPacket.NodeFlowData flow = flowForPort(port, flows);
            drawPort(g, rerouteOutputPortLocalX(), py, node.enabled() ? Theme.PRIMARY : Theme.TEXT_FAINT);
            if (TransferEdge.PORT_ALL.equals(port)) {
                Theme.fillRound(g, 12, py - 6, 12, 12, 6, node.enabled() ? Theme.PRIMARY_SOFT_H : Theme.SURFACE_SUNK);
                text(g, "全部物品", 32, py - 4, node.enabled() ? Theme.TEXT : Theme.TEXT_MUTED);
            } else if (TransferEdge.FLUID_ALL.equals(port)) {
                if (zoom > 0.24) g.renderItem(new ItemStack(Items.WATER_BUCKET), 12, py - 8);
                text(g, "全部流体", 32, py - 4, node.enabled() ? Theme.TEXT : Theme.TEXT_MUTED);
            } else if (TransferEdge.ENERGY_FE.equals(port)) {
                Theme.fillRound(g, 12, py - 6, 12, 12, 6, node.enabled() ? 0x33E0B43A : Theme.SURFACE_SUNK);
                text(g, "电力 FE", 32, py - 4, node.enabled() ? Theme.TEXT : Theme.TEXT_MUTED);
            } else if (TransferEdge.STRESS_SU.equals(port)) {
                Theme.fillRound(g, 12, py - 6, 12, 12, 6, node.enabled() ? 0x33D781D8 : Theme.SURFACE_SUNK);
                text(g, "应力 SU", 32, py - 4, node.enabled() ? Theme.TEXT : Theme.TEXT_MUTED);
            } else if (port.startsWith(TransferEdge.FLUID_PREFIX)) {
                if (zoom > 0.24) g.renderItem(new ItemStack(Items.WATER_BUCKET), 12, py - 8);
                text(g, Theme.ellipsize(font, shortResource(port), 62), 32, py - 4, node.enabled() ? Theme.TEXT : Theme.TEXT_MUTED);
            } else if (port.startsWith(TransferEdge.ITEM_PREFIX) && zoom > 0.24) {
                Item item = resolveItem(port.substring(TransferEdge.ITEM_PREFIX.length()));
                if (item != null) g.renderItem(new ItemStack(item), 12, py - 8);
                text(g, Theme.ellipsize(font, shortResource(port), 62), 32, py - 4, node.enabled() ? Theme.TEXT : Theme.TEXT_MUTED);
            }
            if (isExpanded(node)) {
                text(g, resourceRateLabel(flow.inputRatePerMinute(), flow.itemId()), 104, py - 4, node.enabled() ? Theme.SUCCESS : Theme.TEXT_FAINT);
                text(g, resourceRateLabel(flow.outputRatePerMinute(), flow.itemId()), 148, py - 4, node.enabled() ? Theme.PRIMARY_PRESS : Theme.TEXT_FAINT);
            }
            if (isExpanded(node) && node.filterItemIds().contains(filterFromPort(port))) {
                text(g, "×", REROUTE_W - 28, py - 4, Theme.DANGER);
            }
        }
        Theme.hLine(g, 8, h - 28, REROUTE_W - 16, Theme.DIVIDER);
        text(g, node.enabled() ? "禁用" : "启用", 10, h - 18, Theme.PRIMARY_PRESS);
        text(g, "删除", 62, h - 18, Theme.DANGER);
    }

    private void renderTrashNode(GuiGraphics g, TransferGraphSyncPacket.NodeData node, int border) {
        int h = nodeHeight(node);
        Theme.shadow(g, 0, 0, TRASH_W, h, CARD_RADIUS);
        Theme.panel(g, 0, 0, TRASH_W, h, CARD_RADIUS, node.enabled() ? Theme.SURFACE : 0xFFE9EDF2, border);
        Theme.fillRound(g, 2, 2, TRASH_W - 4, 24, CARD_RADIUS - 1, node.enabled() ? 0xFFFFEFF3 : 0xFFDDE3EA);
        text(g, (node.enabled() ? "" : "⊘ ") + "销毁节点", 10, 8, node.enabled() ? Theme.TEXT : Theme.TEXT_MUTED);
        drawExpandButton(g, node, TRASH_W);
        drawPort(g, inputPortLocalX(), h / 2, node.enabled() ? 0xFFE05768 : Theme.TEXT_FAINT);
        text(g, "剩余产物终点", 12, 36, node.enabled() ? Theme.TEXT : Theme.TEXT_MUTED);
        if (isExpanded(node)) text(g, "按连线限速销毁", 12, 52, node.enabled() ? Theme.TEXT_MUTED : Theme.TEXT_FAINT);
        Theme.hLine(g, 8, h - 28, TRASH_W - 16, Theme.DIVIDER);
        text(g, node.enabled() ? "禁用" : "启用", 10, h - 18, Theme.PRIMARY_PRESS);
        text(g, "删除", 62, h - 18, Theme.DANGER);
    }

    private void renderChestNode(GuiGraphics g, TransferGraphSyncPacket.NodeData node, int x, int y) {
        renderBandwidthBar(g, node, x + 8, y + 43, NODE_W - 16);
        drawResourceRow(g, node, x, y + chestItemLocalY(), "物品输入", "物品输出", Theme.SUCCESS, Theme.PRIMARY, SearchKind.ITEM);
        drawResourceRow(g, node, x, y + chestFluidLocalY(), "流体输入", "流体输出", 0xFF62CDBD, 0xFF359E92, SearchKind.FLUID);
        drawResourceRow(g, node, x, y + chestEnergyLocalY(), "电力输入", "电力输出", 0xFFE0B43A, 0xFFC48E14, null);
        drawResourceRow(g, node, x, y + chestStressLocalY(), "应力输入", "应力输出", 0xFFD781D8, 0xFFB560B8, null);
        if (!isExpanded(node)) return;
        text(g, "+ 其他过滤", x + 8, y + chestAddFilterLocalY(), node.enabled() ? Theme.PRIMARY_PRESS : Theme.TEXT_MUTED);
        int iy = y + chestFirstFilterLocalY();
        for (String filter : node.filterItemIds()) {
            Item item = resolveItem(filter);
            if (item != null && zoom > 0.22) g.renderItem(new ItemStack(item), x + 8, iy - 7);
            else if (isFluidResource(filter) && zoom > 0.22) g.renderItem(new ItemStack(Items.WATER_BUCKET), x + 8, iy - 7);
            text(g, Theme.ellipsize(font, shortResource(filter), 112), x + (zoom > 0.22 ? 28 : 8), iy - 3, node.enabled() ? Theme.TEXT : Theme.TEXT_MUTED);
            Theme.fillRound(g, x + filterRemoveLocalX() - 5, iy - 6, 12, 12, 6, 0x22E05768);
            text(g, "×", x + filterRemoveLocalX() - 2, iy - 5, Theme.DANGER);
            drawPort(g, outputPortLocalX(), iy, node.enabled() ? portColor(filterPort(filter), false) : Theme.TEXT_FAINT);
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
        if (chest.stressBandwidthUsed() > 0) label += " 力-" + chest.stressBandwidthUsed();
        textRight(g, Theme.ellipsize(font, label, NODE_W - x - 6), NODE_W - 8, y, node.enabled() ? color : Theme.TEXT_FAINT);
    }

    private void renderBandwidthBar(GuiGraphics g, TransferGraphSyncPacket.NodeData node, int x, int y, int w) {
        TransferGraphSyncPacket.ChestData chest = chestDataFor(node);
        int total = chest == null ? 0 : Math.max(0, chest.networkBandwidth());
        int stress = chest == null ? 0 : Math.max(0, Math.min(total, chest.stressBandwidthUsed()));
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
        drawPort(g, inputPortLocalX(), y, node.enabled() ? inColor : Theme.TEXT_FAINT);
        text(g, inLabel, x + 26, y - 4, node.enabled() ? Theme.TEXT : Theme.TEXT_MUTED);
        text(g, outLabel, x + NODE_W - 82, y - 4, node.enabled() ? Theme.TEXT : Theme.TEXT_MUTED);
        if (addKind != null) {
            int bx = x + NODE_W - 36;
            int fill = node.enabled() ? Theme.SURFACE_ALT : Theme.SURFACE_SUNK;
            chip(g, bx, y - 8, 18, 14, fill, Theme.BORDER);
            text(g, "+", bx + 6, y - 5, node.enabled() ? outColor : Theme.TEXT_FAINT);
        }
        drawPort(g, outputPortLocalX(), y, node.enabled() ? outColor : Theme.TEXT_FAINT);
    }

    private void drawOutput(GuiGraphics g, int x, int y, String label, int color) {
        text(g, label, x + 8, y - 4, color);
        drawPort(g, outputPortLocalX(), y, Theme.PRIMARY);
    }

    private void drawExpandButton(GuiGraphics g, TransferGraphSyncPacket.NodeData node, int nodeW) {
        int bx = nodeW - 21;
        Theme.fillRound(g, bx, 6, 14, 12, 4, 0x66FFFFFF);
        text(g, isExpanded(node) ? "▾" : "▸", bx + 4, 8, node.enabled() ? Theme.TEXT_MUTED : Theme.TEXT_FAINT);
    }

    private int inputPortLocalX() {
        return PORT_INSET;
    }

    private int outputPortLocalX() {
        return NODE_W - PORT_INSET;
    }

    private int filterRemoveLocalX() {
        return NODE_W - 33;
    }

    private int rerouteOutputPortLocalX() {
        return REROUTE_W - PORT_INSET;
    }

    private void drawPort(GuiGraphics g, int cx, int cy, int color) {
        Theme.fillRound(g, cx - 5, cy - 5, 10, 10, 5, 0x22FFFFFF);
        Theme.fillRound(g, cx - 4, cy - 4, 8, 8, 4, color);
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
            drawBezier(g, a[0], a[1], b[0], b[1], laneOffset(edge), color, selected);
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
        float s = canvasUiScale();
        int tw = Theme.styledWidth(font, label);
        g.pose().pushPose();
        g.pose().translate(cx, y, 0);
        g.pose().scale(s, s, 1.0f);
        Theme.fillRound(g, -tw / 2 - 6, -4, tw + 12, 16, 8, 0xEAF7FBFF);
        Theme.outlineRound(g, -tw / 2 - 6, -4, tw + 12, 16, 8, 0x88D4E4F5);
        text(g, label, -tw / 2, 0, Theme.TEXT_MUTED);
        g.pose().popPose();
    }

    private void renderActiveLink(GuiGraphics g, int mx, int my) {
        TransferGraphSyncPacket.NodeData from = node(linkingFromNodeId);
        if (from == null) return;
        int[] a = outputPos(from, linkingFromPort);
        drawBezier(g, a[0], a[1], mx, my, 0, Theme.PRIMARY_HOVER, true);
    }

    private void drawBezier(GuiGraphics g, double x0, double y0, double x3, double y3, float laneOffset, int color, boolean thick) {
        double dx = Math.max(42, Math.abs(x3 - x0) * 0.46);
        double x1 = x0 + dx;
        double x2 = x3 - dx;
        double y1 = y0 + laneOffset;
        double y2 = y3 + laneOffset;
        double curveSpan = Math.hypot(x3 - x0, y3 - y0) + Math.abs(laneOffset) * 1.6;
        int segments = Math.max(72, Math.min(260, (int) Math.ceil(curveSpan / 3.0)));
        float coreWidth = scaledLineWidth(thick ? 3.1f : 2.25f);
        drawBezierStroke(g, x0, y0, x1, y1, x2, y2, x3, y3, segments, coreWidth, color);
        int cap = Math.max(2, Math.round(coreWidth + 0.8f));
        Theme.fillRound(g, (int) Math.round(x0) - cap / 2, (int) Math.round(y0) - cap / 2, cap, cap, Math.max(1, cap / 2), color);
        Theme.fillRound(g, (int) Math.round(x3) - cap / 2, (int) Math.round(y3) - cap / 2, cap, cap, Math.max(1, cap / 2), color);
    }

    private float scaledLineWidth(float base) {
        return Math.max(0.35f, Math.min(5.0f, base * (float) Math.max(0.12, zoom)));
    }

    private float canvasUiScale() {
        return (float) Math.max(0.42, Math.min(1.25, zoom));
    }

    private void drawBezierStroke(GuiGraphics g, double x0, double y0, double x1, double y1, double x2, double y2,
                                  double x3, double y3, int segments, float width, int color) {
        Matrix4f matrix = g.pose().last().pose();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        Tesselator tess = Tesselator.getInstance();
        BufferBuilder buf = tess.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        double px = x0, py = y0;
        for (int i = 1; i <= segments; i++) {
            double t = i / (double) segments;
            double x = cubic(x0, x1, x2, x3, t);
            double y = cubic(y0, y1, y2, y3, t);
            addStrokeSegment(buf, matrix, (float) px, (float) py, (float) x, (float) y, width, color);
            px = x;
            py = y;
        }
        BufferUploader.drawWithShader(buf.buildOrThrow());
        RenderSystem.disableBlend();
    }

    private void drawFlowParticles(GuiGraphics g, double x0, double y0, double x1, double y1, double x2, double y2,
                                   double x3, double y3, int color, int rate, float partialTick) {
        int visualRate = Math.max(0, rate);
        int count = clamp(2 + visualRate / 1400, 2, 5);
        double speed = 0.28 + Math.min(visualRate, 12000) / 24000.0;
        double time = (System.currentTimeMillis() % 120000L) / 1000.0 + partialTick / 20.0;
        int glowColor = withAlpha(color, 0x55);
        int coreColor = mixColor(color, 0xFFFFFFFF, 0.42f);
        int dot = Math.max(2, Math.round(scaledLineWidth(3.0f)));
        int glow = dot + 4;
        for (int i = 0; i < count; i++) {
            double t = (time * speed + i / (double) count) % 1.0;
            double px = cubic(x0, x1, x2, x3, t);
            double py = cubic(y0, y1, y2, y3, t);
            Theme.fillRound(g, (int) Math.round(px) - glow / 2, (int) Math.round(py) - glow / 2, glow, glow, Math.max(1, glow / 2), glowColor);
            Theme.fillRound(g, (int) Math.round(px) - dot / 2, (int) Math.round(py) - dot / 2, dot, dot, Math.max(1, dot / 2), coreColor);
        }
    }

    private double cubic(double a, double b, double c, double d, double t) {
        double u = 1.0 - t;
        return u * u * u * a + 3 * u * u * t * b + 3 * u * t * t * c + t * t * t * d;
    }

    private void addStrokeSegment(BufferBuilder buf, Matrix4f matrix, float x0, float y0, float x1, float y1, float width, int color) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 0.001f) return;
        float nx = -dy / len * width * 0.5f;
        float ny = dx / len * width * 0.5f;
        vertex(buf, matrix, x0 + nx, y0 + ny, color);
        vertex(buf, matrix, x1 + nx, y1 + ny, color);
        vertex(buf, matrix, x1 - nx, y1 - ny, color);
        vertex(buf, matrix, x0 - nx, y0 - ny, color);
    }

    private void vertex(BufferBuilder buf, Matrix4f matrix, float x, float y, int color) {
        float a = ((color >>> 24) & 0xFF) / 255f;
        float r = ((color >> 16) & 0xFF) / 255f;
        float gr = ((color >> 8) & 0xFF) / 255f;
        float b = (color & 0xFF) / 255f;
        buf.addVertex(matrix, x, y, 0).setColor(r, gr, b, a);
    }

    private int withAlpha(int color, int alpha) {
        return (clamp(alpha, 0, 255) << 24) | (color & 0x00FFFFFF);
    }

    private int mixColor(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = Math.round(ar + (br - ar) * t);
        int g = Math.round(ag + (bg - ag) * t);
        int bl = Math.round(ab + (bb - ab) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private void renderMenu(GuiGraphics g) {
        if (menuMode == MenuMode.NONE) return;
        if (menuMode == MenuMode.PAGE_ACTION) {
            int rows = pages().size();
            int h = 30 + rows * 18 + 22;
            crispPanel(g, menuX, menuY, 210, h, Theme.SURFACE, Theme.BORDER_STRONG);
            text(g, "分页", menuX + 8, menuY + 8, Theme.TEXT);
            int y = menuY + 28;
            for (TransferGraphSyncPacket.PageData page : pages()) {
                int color = page.id().equals(activePageId) ? Theme.PRIMARY_PRESS : Theme.TEXT;
                text(g, page.enabled() ? "●" : "○", menuX + 8, y, page.enabled() ? Theme.SUCCESS : Theme.TEXT_FAINT);
                text(g, page.name(), menuX + 28, y, color);
                text(g, "✎", menuX + 150, y, Theme.PRIMARY_PRESS);
                text(g, "×", menuX + 176, y, Theme.DANGER);
                y += 18;
            }
            text(g, "+ 新建分页", menuX + 8, y + 2, Theme.PRIMARY_PRESS);
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
        if (menuMode == MenuMode.PAGE_RENAME || menuMode == MenuMode.PAGE_CREATE) {
            crispPanel(g, menuX, menuY, 132, 50, Theme.SURFACE, Theme.BORDER_STRONG);
            if (pageNameEdit != null) pageNameEdit.render(g, 0, 0, 0);
            text(g, "Enter 保存", menuX + 8, menuY + 31, Theme.TEXT_MUTED);
            return;
        }
        if (menuMode == MenuMode.ROOT) {
            crispPanel(g, menuX, menuY, 150, 72, Theme.SURFACE, Theme.BORDER_STRONG);
            text(g, "新建箱子节点", menuX + 9, menuY + 10, Theme.TEXT);
            text(g, "新建中转节点", menuX + 9, menuY + 30, Theme.TEXT);
            text(g, "新建销毁节点", menuX + 9, menuY + 50, Theme.TEXT);
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
            text(g, chest.chestId() + "  " + shortPos(chest.pos()), menuX + 8, menuY + 10 + i * 18, Theme.TEXT);
        }
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
        text(g, "×", x + HELP_W - 20, y + 12, Theme.TEXT_MUTED);
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
        if (node.type().equals("REROUTE")) return;
        int h = node.type().equals("CHEST") ? 82 : 62;
        crispPanel(g, popupX, popupY, 150, h, Theme.SURFACE, hasIssueForNode(node.id()) ? Theme.DANGER : Theme.BORDER_STRONG);
        text(g, node.type().equals("TRASH") ? "销毁节点" : node.chestId(), popupX + 9, popupY + 9, Theme.TEXT);
        text(g, node.enabled() ? "禁用节点" : "启用节点", popupX + 9, popupY + 28, Theme.PRIMARY_PRESS);
        if (node.type().equals("CHEST")) text(g, "添加过滤", popupX + 9, popupY + 46, Theme.PRIMARY_PRESS);
        text(g, "删除节点", popupX + 9, popupY + (node.type().equals("CHEST") ? 64 : 46), Theme.DANGER);
    }

    private void renderReroutePopup(GuiGraphics g, TransferGraphSyncPacket.NodeData node) {
        float s = canvasUiScale();
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
                Item item = resolveItem(flow.itemId());
                if (item != null && zoom > 0.18) g.renderItem(new ItemStack(item), 10, y - 5);
                else if (isFluidResource(flow.itemId()) && zoom > 0.18) g.renderItem(new ItemStack(Items.WATER_BUCKET), 10, y - 5);
                text(g, Theme.ellipsize(font, shortResource(flow.itemId()), 66), 30, y, Theme.TEXT);
                text(g, resourceRateLabel(flow.inputRatePerMinute(), flow.itemId()), 100, y, Theme.SUCCESS);
                text(g, resourceRateLabel(flow.outputRatePerMinute(), flow.itemId()), 146, y, Theme.PRIMARY_PRESS);
                if (node.filterItemIds().contains(filterFromPort(flow.itemId()))) text(g, "×", 190, y, Theme.DANGER);
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
        float s = canvasUiScale();
        List<TransferGraphSyncPacket.EdgeItemRateData> rows = edgeRateRows(edge);
        int panelH = 74 + rows.size() * 24 + 28;
        int panelW = edgePopupWidth(rows);
        g.pose().pushPose();
        g.pose().translate(popupX, popupY, 0);
        g.pose().scale(s, s, 1.0f);
        crispPanel(g, 0, 0, panelW, panelH, Theme.SURFACE, hasIssueForEdge(edge.id()) ? Theme.DANGER : Theme.BORDER_STRONG);
        text(g, portLabel(edge.fromPortKey()), 9, 9, Theme.TEXT);
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
        text(g, edge.enabled() ? "禁用" : "启用", 10, panelH - 18, Theme.PRIMARY_PRESS);
        text(g, "删除", 64, panelH - 18, Theme.DANGER);
        g.pose().popPose();
    }

    private void renderEdgeRateRow(GuiGraphics g, TransferGraphSyncPacket.EdgeItemRateData row, int y) {
        Item item = resolveItem(row.itemId());
        if (item != null && zoom > 0.18) g.renderItem(new ItemStack(item), 10, y - 5);
        else if (isFluidResource(row.itemId()) && zoom > 0.18) g.renderItem(new ItemStack(Items.WATER_BUCKET), 10, y - 5);
        chip(g, 31, y - 3, 28, 16, row.rateLimitEnabled() ? Theme.PRIMARY_SOFT : Theme.SURFACE_ALT,
                row.rateLimitEnabled() ? Theme.PRIMARY : Theme.BORDER);
        text(g, row.rateLimitEnabled() ? "开" : "关", 39, y + 1, row.rateLimitEnabled() ? Theme.PRIMARY_PRESS : Theme.TEXT_MUTED);
        text(g, Theme.ellipsize(font, shortResource(row.itemId()), 58), 64, y, Theme.TEXT);
        String seconds = row.itemId().equals(selectedRateItemId) ? rateSecondsValue : String.valueOf(row.rateLimitSeconds());
        String items = row.itemId().equals(selectedRateItemId) ? rateItemsValue : String.valueOf(row.rateLimitItems());
        int secX = 126;
        int itemX = 176;
        renderEdgeInput(g, secX, y - 4, 24, seconds, focusedEdgeField == EdgeField.SECONDS && row.itemId().equals(selectedRateItemId));
        text(g, "秒", secX + 28, y + 1, Theme.TEXT_MUTED);
        renderEdgeInput(g, itemX, y - 4, 34, items, focusedEdgeField == EdgeField.ITEMS && row.itemId().equals(selectedRateItemId));
        text(g, isFluidResource(row.itemId()) ? "mB" : "个", itemX + 38, y + 1, Theme.TEXT_MUTED);
        text(g, resourceRateLabel(row.actualRatePerMinute(), row.itemId()), 242, y, healthTextColor(row.health()));
    }

    private int edgePopupWidth(List<TransferGraphSyncPacket.EdgeItemRateData> rows) {
        return 300;
    }

    private void renderEdgeInput(GuiGraphics g, int x, int y, int w, String value, boolean focused) {
        Theme.panel(g, x, y, w, 18, 5, focused ? 0xFFFFFFFF : Theme.SURFACE_SUNK, focused ? Theme.PRIMARY : Theme.BORDER);
        String shown = Theme.ellipsize(font, value.isEmpty() ? "0" : value, w - 8);
        text(g, shown, x + 6, y + 5, focused ? Theme.TEXT : Theme.TEXT_MUTED);
    }

    private int healthTextColor(String health) {
        return switch (health) {
            case "HEALTHY" -> Theme.SUCCESS;
            case "SOURCE_SHORTAGE" -> 0xFFE0B43F;
            case "RECEIVER_BLOCKED" -> 0xFFE675AE;
            case "DEADLOCKED" -> 0xFF98283A;
            default -> Theme.TEXT_MUTED;
        };
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
        return healthTextColor(edge.health());
    }

    private String ratePerMinuteLabel(int rate) {
        if (rate >= 1000000) return (rate / 1000000) + "m/分";
        if (rate >= 10000) return (rate / 1000) + "k/分";
        return rate + "/分";
    }

    private void renderSearchPopup(GuiGraphics g, int mx, int my, float partialTick) {
        List<String> results = searchResults();
        int panelH = searchPopupHeight(results);
        crispPanel(g, popupX, popupY, SEARCH_W, panelH, Theme.SURFACE, Theme.BORDER_STRONG);
        TransferGraphSyncPacket.NodeData selectedNode = node(selectedNodeId);
        String title = searchForEdgeItem ? "添加资源行"
                : selectedNode != null && selectedNode.type().equals("REROUTE") ? "添加输出"
                : searchKind == SearchKind.ITEM ? "添加物品过滤"
                : searchKind == SearchKind.FLUID ? "添加流体过滤"
                : "添加过滤";
        text(g, title, popupX + 9, popupY + 10, Theme.TEXT);
        Theme.panel(g, popupX + 8, popupY + 26, SEARCH_W - 16, 18, 5, 0xFFFFFFFF, Theme.BORDER);
        String shown = searchValue.isEmpty() ? "搜索资源" : searchValue;
        int textColor = searchValue.isEmpty() ? Theme.TEXT_FAINT : Theme.TEXT;
        text(g, Theme.ellipsize(font, shown + (searchFocused && (graphSyncTicker / 10) % 2 == 0 ? "_" : ""), SEARCH_W - 28), popupX + 13, popupY + 31, textColor);
        int y = popupY + 55;
        for (String resourceId : results) {
            Item item = resolveItem(resourceId);
            if (item != null) g.renderItem(new ItemStack(item), popupX + 10, y - 6);
            else if (isFluidResource(resourceId)) g.renderItem(new ItemStack(Items.WATER_BUCKET), popupX + 10, y - 6);
            text(g, Theme.ellipsize(font, shortResource(resourceId), SEARCH_W - 42), popupX + 32, y - 1, Theme.TEXT);
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
        if (button == 0 && handleHeaderClick(mx, my)) return true;
        if (button == 0 && handleTabs(mx, my)) return true;
        if (handleMenuClick(mx, my, button)) return true;
        if (handlePopupClick(mx, my, button)) return true;
        if (button == 1) {
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
                selectedRateItemId = defaultRateItemId(edge);
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
                    linkingFromPort = filterPort(filter.itemId());
                }
                return true;
            }
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
        if (my < 0 || my >= HEADER_H) return false;
        int saveX = width - 214;
        if (inside(mx, my, saveX, 6, 54, 18)) {
            saveDraft();
            return true;
        }
        if (inside(mx, my, saveX + 62, 6, 54, 18)) {
            discardDraft();
            return true;
        }
        int helpX = width - 86;
        if (inside(mx, my, helpX, 6, 20, 18)) {
            popupMode = popupMode == PopupMode.HELP ? PopupMode.NONE : PopupMode.HELP;
            menuMode = MenuMode.NONE;
            focusedEdgeField = EdgeField.NONE;
            return true;
        }
        return false;
    }

    private boolean handleTabs(double mx, double my) {
        int x = 8, y = HEADER_H + 6;
        TransferGraphSyncPacket.PageData activePage = page(activePageId);
        if (activePage == null) activePage = firstPage();
        String label = activePage.enabled() ? activePage.name() : "⊘ " + activePage.name();
        int w = Math.max(74, Theme.styledWidth(font, label) + 28);
        if (mx >= x && mx < x + w && my >= y && my < y + TAB_H - 4) return true;
        x += w + 5;
        if (mx >= x && mx < x + 22 && my >= y && my < y + TAB_H - 4) {
            menuMode = MenuMode.PAGE_CREATE;
            menuX = x;
            menuY = y + TAB_H;
            if (pageNameEdit != null) {
                pageNameEdit.setValue("新页面");
                pageNameEdit.setFocused(true);
            }
            return true;
        }
        x += 28;
        if (mx >= x && mx < x + 22 && my >= y && my < y + TAB_H - 4) {
            menuMode = MenuMode.PAGE_ACTION;
            menuX = x;
            menuY = y + TAB_H;
            popupMode = PopupMode.NONE;
            return true;
        }
        return false;
    }

    private boolean handleMenuClick(double mx, double my, int button) {
        if (menuMode == MenuMode.NONE) return false;
        if (button != 0) {
            menuMode = MenuMode.NONE;
            return true;
        }
        if (menuMode == MenuMode.PAGE_ACTION) {
            if (mx < menuX || mx >= menuX + 210) {
                menuMode = MenuMode.NONE;
                return true;
            }
            int row = ((int) my - (menuY + 24)) / 18;
            if (row >= 0 && row < pages().size()) {
                TransferGraphSyncPacket.PageData page = pages().get(row);
                if (mx < menuX + 24) {
                    togglePage(page.id());
                } else if (mx >= menuX + 146 && mx < menuX + 170) {
                    pendingDeletePageId = page.id();
                    menuMode = MenuMode.PAGE_RENAME;
                    if (pageNameEdit != null) {
                        pageNameEdit.setValue(page.name());
                        pageNameEdit.setFocused(true);
                    }
                } else if (mx >= menuX + 172 && mx < menuX + 196) {
                    pendingDeletePageId = page.id();
                    if (pageNodeCount(page.id()) > 0) menuMode = MenuMode.PAGE_DELETE_CONFIRM;
                    else deletePage(page.id());
                } else {
                    activePageId = page.id();
                    menuMode = MenuMode.NONE;
                }
                return true;
            }
            int createY = menuY + 30 + pages().size() * 18;
            if (my >= createY && my < createY + 20) {
                menuMode = MenuMode.PAGE_CREATE;
                if (pageNameEdit != null) {
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
        if (menuMode == MenuMode.PAGE_RENAME || menuMode == MenuMode.PAGE_CREATE) {
            if (pageNameEdit != null && pageNameEdit.mouseClicked(mx, my, button)) return true;
            return true;
        }
        if (menuMode == MenuMode.ROOT) {
            if (mx >= menuX && mx < menuX + 150 && my >= menuY && my < menuY + 72) {
                if (my < menuY + 24) menuMode = MenuMode.CHEST_LIST;
                else if (my < menuY + 48) {
                    addRerouteNode(activePageId, pendingNodeX, pendingNodeY);
                    menuMode = MenuMode.NONE;
                } else {
                    addTrashNode(activePageId, pendingNodeX, pendingNodeY);
                    menuMode = MenuMode.NONE;
                }
            } else menuMode = MenuMode.NONE;
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
                addFilterItem(selectedNodeId, results.get(idx));
                popupMode = PopupMode.NONE;
                searchFocused = false;
                return true;
            }
            return true;
        }
        if (popupMode == PopupMode.NODE && selectedNodeId != null) {
            TransferGraphSyncPacket.NodeData node = node(selectedNodeId);
            if (node == null) return true;
            if (node.type().equals("REROUTE")) {
                double lx = scaledPopupLocalX(mx);
                double ly = scaledPopupLocalY(my);
                if (inside(lx, ly, 150, 5, 50, 18)) {
                    openSearchPopup(node, mx, my, SearchKind.ALL);
                    return true;
                }
                List<TransferGraphSyncPacket.NodeFlowData> flows = rerouteFlowRows(node);
                int row = ((int) ly - 41) / 20;
                if (row >= 0 && row < Math.min(5, flows.size()) && inside(lx, ly, 186, 42 + row * 20, 16, 16)) {
                    TransferGraphSyncPacket.NodeFlowData flow = flows.get(row);
                    if (node.filterItemIds().contains(flow.itemId())) removeFilterItem(node.id(), flow.itemId());
                    return true;
                }
                int h = reroutePopupHeight(node);
                if (ly >= h - 24 && ly < h - 2) {
                    if (lx < 64) toggleNode(selectedNodeId);
                    else if (lx < 136) {
                        deleteNode(selectedNodeId);
                        popupMode = PopupMode.NONE;
                    }
                }
                return true;
            }
            int row = ((int) my - popupY - 22) / 18;
            if (row == 0) toggleNode(selectedNodeId);
            else if (row == 1 && node.type().equals("CHEST")) openSearchPopup(node, popupX, popupY, SearchKind.ALL);
            else if ((row == 1 && !node.type().equals("CHEST")) || row == 2) {
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
            int panelW = edgePopupWidth(edgeRateRows(edge));
            if (inside(lx, ly, panelW - 52, 5, 50, 18)) {
                openEdgeItemSearch(mx, my);
                return true;
            }
            List<TransferGraphSyncPacket.EdgeItemRateData> rows = edgeRateRows(edge);
            int row = ((int) ly - 60) / 24;
            if (row >= 0 && row < rows.size()) {
                TransferGraphSyncPacket.EdgeItemRateData rate = rows.get(row);
                int rowY = 64 + row * 24;
                if (inside(lx, ly, 31, rowY - 3, 28, 16)) {
                    updateEdgeItemRate(edge.id(), rate.itemId(), !rate.rateLimitEnabled(), rate.rateLimitSeconds(), rate.rateLimitItems());
                    return true;
                }
                int secX = 126;
                int itemX = 176;
                if (inside(lx, ly, secX, rowY - 4, 24, 18)) {
                    selectedRateItemId = rate.itemId();
                    syncRateEditors(edge);
                    focusedEdgeField = EdgeField.SECONDS;
                    return true;
                }
                if (inside(lx, ly, itemX, rowY - 4, 34, 18)) {
                    selectedRateItemId = rate.itemId();
                    syncRateEditors(edge);
                    focusedEdgeField = EdgeField.ITEMS;
                    return true;
                }
            }
            focusedEdgeField = EdgeField.NONE;
            int panelH = 74 + rows.size() * 24 + 28;
            if (ly >= panelH - 22 && ly < panelH - 2) {
                if (lx < 54) toggleEdge(selectedEdgeId);
                else if (lx < 108) {
                    deleteEdge(selectedEdgeId);
                    popupMode = PopupMode.NONE;
                }
                return true;
            }
        }
        return false;
    }

    private void openSearchPopup(TransferGraphSyncPacket.NodeData node, double mx, double my) {
        openSearchPopup(node, mx, my, SearchKind.ALL);
    }

    private void openSearchPopup(TransferGraphSyncPacket.NodeData node, double mx, double my, SearchKind kind) {
        selectedNodeId = node.id();
        searchForEdgeItem = false;
        searchKind = kind == null ? SearchKind.ALL : kind;
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
        if (isExpanded(node) && inside(lx, ly, REROUTE_W - 84, 4, 58, 22)) {
            openSearchPopup(node, nodeScreenX(node) + scaled(REROUTE_W - 58), nodeScreenY(node) + scaled(20), SearchKind.ALL);
            return true;
        }
        List<String> ports = visibleRerouteOutputPorts(node);
        for (String port : ports) {
            int py = rerouteOutputLocalY(node, port);
            String filter = filterFromPort(port);
            if (isExpanded(node) && node.filterItemIds().contains(filter)
                    && inside(lx, ly, REROUTE_W - 34, py - 8, 20, 16)) {
                if (node.filterItemIds().contains(filter)) removeFilterItem(node.id(), filter);
                return true;
            }
        }
        int h = nodeHeight(node);
        if (inside(lx, ly, 8, h - 24, 46, 20)) {
            toggleNode(node.id());
            return true;
        }
        if (inside(lx, ly, 58, h - 24, 46, 20)) {
            deleteNode(node.id());
            popupMode = PopupMode.NONE;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
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
                    if (node.type().equals("REROUTE")) {
                        popupMode = PopupMode.NONE;
                    } else {
                        popupMode = PopupMode.NODE;
                        popupX = nodeScreenX(node) + scaled(nodeWidth(node)) + 10;
                        popupY = nodeScreenY(node);
                    }
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
        if (keyCode == 256) {
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
        if ((menuMode == MenuMode.PAGE_RENAME || menuMode == MenuMode.PAGE_CREATE) && pageNameEdit != null) {
            if (keyCode == 257 || keyCode == 335) {
                String name = pageNameEdit.getValue().trim();
                if (name.isEmpty()) name = "新页面";
                if (menuMode == MenuMode.PAGE_CREATE) addPage(name);
                else if (pendingDeletePageId != null) renamePage(pendingDeletePageId, name);
                pageNameEdit.setFocused(false);
                menuMode = MenuMode.PAGE_ACTION;
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
                TransferGraphSyncPacket.EdgeData edge = edge(selectedEdgeId);
                if (edge != null && selectedRateItemId != null) {
                    TransferGraphSyncPacket.EdgeItemRateData row = edgeRateRow(edge, selectedRateItemId);
                    updateEdgeItemRate(edge.id(), selectedRateItemId, row == null || row.rateLimitEnabled(), parseRateSeconds(), parseRateItems());
                }
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
        if ((menuMode == MenuMode.PAGE_RENAME || menuMode == MenuMode.PAGE_CREATE) && pageNameEdit != null && pageNameEdit.charTyped(c, modifiers)) return true;
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

    private void createEdge(String toNodeId, String toPortKey) {
        if (linkingFromNodeId == null) return;
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
        for (int i = 0; i < draftEdges.size(); i++) {
            TransferGraphSyncPacket.EdgeData edge = draftEdges.get(i);
            if (edge.fromNodeId().equals(from.id()) && edge.toNodeId().equals(to.id())
                    && edge.fromPortKey().equals(linkingFromPort) && edge.toPortKey().equals(toPortKey)) {
                draftEdges.set(i, copyEdge(edge, edge.enabled(), edge.itemRates()));
                markDirty();
                linkingFromNodeId = null;
                return;
            }
        }
        draftEdges.add(new TransferGraphSyncPacket.EdgeData(UUID.randomUUID().toString(), from.pageId(), from.id(), to.id(),
                linkingFromPort, toPortKey, true, false, 1, 64, "UNMEASURED", 0, defaultItemRatesForPort(linkingFromPort)));
        markDirty();
        linkingFromNodeId = null;
    }

    private void saveDraft() {
        ensureDraft();
        if (savePending) return;
        savePending = true;
        validationStale = false;
        PacketDistributor.sendToServer(new SaveTransferGraphPacket(copyPages(draftPages), copyNodes(draftNodes), copyEdges(draftEdges)));
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
                draftNodes.set(i, new TransferGraphSyncPacket.NodeData(n.id(), activePageId, n.type(), n.chestId(), n.dimensionKey(), n.pos(), x, y, n.expanded(), n.enabled(), List.copyOf(n.filterItemIds()), List.copyOf(n.flowStats())));
                markDirty();
                return;
            }
        }
        draftNodes.add(new TransferGraphSyncPacket.NodeData(UUID.randomUUID().toString(), activePageId, "CHEST", chest.chestId(), chest.dimensionKey(), chest.pos(), x, y, false, true, List.of(), List.of()));
        markDirty();
    }

    private void addRerouteNode(String pageId, int x, int y) {
        draftNodes.add(new TransferGraphSyncPacket.NodeData(UUID.randomUUID().toString(), pageId, "REROUTE", "", "", BlockPos.ZERO.asLong(), x, y, false, true, List.of(), List.of()));
        markDirty();
    }

    private void addTrashNode(String pageId, int x, int y) {
        draftNodes.add(new TransferGraphSyncPacket.NodeData(UUID.randomUUID().toString(), pageId, "TRASH", "", "", BlockPos.ZERO.asLong(), x, y, false, true, List.of(), List.of()));
        markDirty();
    }

    private void moveNode(String nodeId, int x, int y) {
        TransferGraphSyncPacket.NodeData n = node(nodeId);
        if (n == null) return;
        replaceNode(new TransferGraphSyncPacket.NodeData(n.id(), n.pageId(), n.type(), n.chestId(), n.dimensionKey(), n.pos(), x, y, n.expanded(), n.enabled(), List.copyOf(n.filterItemIds()), List.copyOf(n.flowStats())));
    }

    private void toggleNode(String nodeId) {
        TransferGraphSyncPacket.NodeData n = node(nodeId);
        if (n == null) return;
        replaceNode(new TransferGraphSyncPacket.NodeData(n.id(), n.pageId(), n.type(), n.chestId(), n.dimensionKey(), n.pos(), n.x(), n.y(), n.expanded(), !n.enabled(), List.copyOf(n.filterItemIds()), List.copyOf(n.flowStats())));
    }

    private void toggleNodeExpanded(String nodeId) {
        TransferGraphSyncPacket.NodeData n = node(nodeId);
        if (n == null) return;
        replaceNode(new TransferGraphSyncPacket.NodeData(n.id(), n.pageId(), n.type(), n.chestId(), n.dimensionKey(), n.pos(), n.x(), n.y(), !n.expanded(), n.enabled(), List.copyOf(n.filterItemIds()), List.copyOf(n.flowStats())));
    }

    private void deleteNode(String nodeId) {
        draftNodes.removeIf(n -> n.id().equals(nodeId));
        draftEdges.removeIf(e -> e.fromNodeId().equals(nodeId) || e.toNodeId().equals(nodeId));
        markDirty();
    }

    private void addFilterItem(String nodeId, String itemId) {
        itemId = normalizeFilterResource(itemId);
        TransferGraphSyncPacket.NodeData n = node(nodeId);
        if (n == null || (!n.type().equals("CHEST") && !n.type().equals("REROUTE")) || n.filterItemIds().contains(itemId)) return;
        List<String> filters = new ArrayList<>(n.filterItemIds());
        filters.add(itemId);
        boolean expanded = n.expanded() || n.type().equals("CHEST");
        replaceNode(new TransferGraphSyncPacket.NodeData(n.id(), n.pageId(), n.type(), n.chestId(), n.dimensionKey(), n.pos(), n.x(), n.y(), expanded, n.enabled(), filters, List.copyOf(n.flowStats())));
    }

    private void removeFilterItem(String nodeId, String itemId) {
        itemId = normalizeFilterResource(itemId);
        TransferGraphSyncPacket.NodeData n = node(nodeId);
        if (n == null) return;
        List<String> filters = new ArrayList<>(n.filterItemIds());
        if (!filters.remove(itemId)) return;
        replaceNode(new TransferGraphSyncPacket.NodeData(n.id(), n.pageId(), n.type(), n.chestId(), n.dimensionKey(), n.pos(), n.x(), n.y(), n.expanded(), n.enabled(), filters, List.copyOf(n.flowStats())));
        String port = filterPort(itemId);
        draftEdges.removeIf(e -> e.fromNodeId().equals(nodeId) && e.fromPortKey().equals(port));
        markDirty();
    }

    private String normalizeFilterResource(String resourceId) {
        if (resourceId == null) return "";
        if (resourceId.startsWith(TransferEdge.ITEM_PREFIX)) return resourceId.substring(TransferEdge.ITEM_PREFIX.length());
        if (resourceId.startsWith(TransferEdge.FLUID_PREFIX)) return resourceId;
        return resourceId;
    }

    private void addEdgeItemRate(String edgeId, String itemId) {
        updateEdgeItemRate(edgeId, itemId, false, 1, 64);
    }

    private void updateEdgeItemRate(String edgeId, String itemId, boolean enabled, int seconds, int items) {
        if (itemId == null || itemId.isBlank()) return;
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
                draftEdges.set(i, copyEdge(e, e.enabled(), rows));
                selectedRateItemId = itemId;
                markDirty();
                return;
            }
        }
    }

    private void toggleEdge(String edgeId) {
        for (int i = 0; i < draftEdges.size(); i++) {
            TransferGraphSyncPacket.EdgeData e = draftEdges.get(i);
            if (e.id().equals(edgeId)) {
                draftEdges.set(i, copyEdge(e, !e.enabled(), e.itemRates()));
                markDirty();
                return;
            }
        }
    }

    private void deleteEdge(String edgeId) {
        draftEdges.removeIf(e -> e.id().equals(edgeId));
        markDirty();
    }

    private TransferGraphSyncPacket.EdgeData copyEdge(TransferGraphSyncPacket.EdgeData e, boolean enabled, List<TransferGraphSyncPacket.EdgeItemRateData> itemRates) {
        return new TransferGraphSyncPacket.EdgeData(e.id(), e.pageId(), e.fromNodeId(), e.toNodeId(), e.fromPortKey(), e.toPortKey(),
                enabled, false, 1, 64, aggregateHealth(itemRates, enabled), aggregateActualRate(itemRates), List.copyOf(itemRates));
    }

    private List<TransferGraphSyncPacket.EdgeItemRateData> defaultItemRatesForPort(String portKey) {
        if (portKey != null && (portKey.startsWith(TransferEdge.ITEM_PREFIX) || portKey.startsWith(TransferEdge.FLUID_PREFIX))) {
            return List.of(new TransferGraphSyncPacket.EdgeItemRateData(portKey, false, 1, 64, "UNMEASURED", 0, false));
        }
        return List.of();
    }

    private List<TransferGraphSyncPacket.EdgeItemRateData> edgeRateRows(TransferGraphSyncPacket.EdgeData edge) {
        List<TransferGraphSyncPacket.EdgeItemRateData> rows = new ArrayList<>(edge.itemRates());
        if (edge.fromPortKey().startsWith(TransferEdge.ITEM_PREFIX) || edge.fromPortKey().startsWith(TransferEdge.FLUID_PREFIX)) {
            String itemId = edge.fromPortKey();
            boolean exists = false;
            for (TransferGraphSyncPacket.EdgeItemRateData row : rows) {
                if (row.itemId().equals(itemId)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) rows.add(0, new TransferGraphSyncPacket.EdgeItemRateData(itemId, false, 1, 64, "UNMEASURED", 0, false));
        }
        return rows;
    }

    private TransferGraphSyncPacket.EdgeItemRateData edgeRateRow(TransferGraphSyncPacket.EdgeData edge, String itemId) {
        if (edge == null || itemId == null) return null;
        for (TransferGraphSyncPacket.EdgeItemRateData row : edgeRateRows(edge)) {
            if (row.itemId().equals(itemId)) return row;
        }
        return null;
    }

    private String defaultRateItemId(TransferGraphSyncPacket.EdgeData edge) {
        List<TransferGraphSyncPacket.EdgeItemRateData> rows = edgeRateRows(edge);
        return rows.isEmpty() ? null : rows.get(0).itemId();
    }

    private int edgeVisualRate(TransferGraphSyncPacket.EdgeData edge) {
        return Math.max(edge.actualRatePerMinute(), aggregateActualRate(edge.itemRates()));
    }

    private int aggregateActualRate(List<TransferGraphSyncPacket.EdgeItemRateData> rows) {
        int total = 0;
        for (TransferGraphSyncPacket.EdgeItemRateData row : rows) total += row.actualRatePerMinute();
        return total;
    }

    private String aggregateHealth(List<TransferGraphSyncPacket.EdgeItemRateData> rows, boolean enabled) {
        if (!enabled) return "DISABLED";
        boolean healthy = false;
        boolean source = false;
        boolean receiver = false;
        for (TransferGraphSyncPacket.EdgeItemRateData row : rows) {
            if ("DEADLOCKED".equals(row.health())) return "DEADLOCKED";
            if ("RECEIVER_BLOCKED".equals(row.health())) receiver = true;
            else if ("SOURCE_SHORTAGE".equals(row.health())) source = true;
            else if ("HEALTHY".equals(row.health())) healthy = true;
        }
        if (receiver) return "RECEIVER_BLOCKED";
        if (source) return "SOURCE_SHORTAGE";
        return healthy ? "HEALTHY" : "UNMEASURED";
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
        return edges().stream().filter(e -> e.pageId().equals(activePageId)).toList();
    }

    private boolean isExpanded(TransferGraphSyncPacket.NodeData node) {
        return node != null && node.expanded();
    }

    private List<String> visibleRerouteOutputPorts(TransferGraphSyncPacket.NodeData node) {
        List<String> ports = rerouteOutputPorts(node);
        if (isExpanded(node)) return ports;
        if (ports.contains(TransferEdge.PORT_ALL) || ports.isEmpty()) return List.of(TransferEdge.PORT_ALL);
        if (ports.contains(TransferEdge.FLUID_ALL)) return List.of(TransferEdge.FLUID_ALL);
        return List.of(ports.get(0));
    }

    private int nodeHeight(TransferGraphSyncPacket.NodeData node) {
        if (node.type().equals("REROUTE")) {
            if (!isExpanded(node)) return COLLAPSED_REROUTE_H;
            return 86 + Math.max(1, rerouteOutputPorts(node).size()) * 20;
        }
        if (node.type().equals("TRASH")) return isExpanded(node) ? 88 : 78;
        if (!isExpanded(node)) return COLLAPSED_CHEST_H;
        return chestFirstFilterLocalY() + Math.max(1, node.filterItemIds().size()) * 18 + 12;
    }

    private int nodeWidth(TransferGraphSyncPacket.NodeData node) {
        if (node.type().equals("REROUTE")) return REROUTE_W;
        if (node.type().equals("TRASH")) return TRASH_W;
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
        int nodeW = nodeWidth(node);
        return mx >= nodeScreenX(node) + scaled(nodeW - 23) && mx < nodeScreenX(node) + scaled(nodeW - 5)
                && my >= nodeScreenY(node) + scaled(4) && my < nodeScreenY(node) + scaled(22);
    }

    private record PortHit(String nodeId, String portKey) {}
    private record FilterHit(String itemId, boolean consumed) {}

    private int chestItemLocalY() {
        return 52;
    }

    private int chestFluidLocalY() {
        return 70;
    }

    private int chestEnergyLocalY() {
        return 88;
    }

    private int chestStressLocalY() {
        return 106;
    }

    private int chestAddFilterLocalY() {
        return 124;
    }

    private int chestFirstFilterLocalY() {
        return 144;
    }

    private int allOutputY(TransferGraphSyncPacket.NodeData node) {
        return nodeScreenY(node) + scaled(chestItemLocalY());
    }

    private int fluidOutputY(TransferGraphSyncPacket.NodeData node) {
        return nodeScreenY(node) + scaled(chestFluidLocalY());
    }

    private int energyOutputY(TransferGraphSyncPacket.NodeData node) {
        return nodeScreenY(node) + scaled(chestEnergyLocalY());
    }

    private int stressOutputY(TransferGraphSyncPacket.NodeData node) {
        return nodeScreenY(node) + scaled(chestStressLocalY());
    }

    private int firstFilterY(TransferGraphSyncPacket.NodeData node) {
        return nodeScreenY(node) + scaled(chestFirstFilterLocalY());
    }

    private int inputY(TransferGraphSyncPacket.NodeData node) {
        return nodeScreenY(node) + scaled(node.type().equals("REROUTE") || node.type().equals("TRASH") ? nodeHeight(node) / 2 : chestItemLocalY());
    }

    private int chestInputY(TransferGraphSyncPacket.NodeData node, String sourcePort) {
        if (!node.type().equals("CHEST")) return inputY(node);
        String inputPort = TransferEdge.inputPortFor(sourcePort);
        if (TransferEdge.FLUID_IN.equals(inputPort)) return fluidOutputY(node);
        if (TransferEdge.ENERGY_IN.equals(inputPort)) return energyOutputY(node);
        if (TransferEdge.STRESS_IN.equals(inputPort)) return stressOutputY(node);
        return allOutputY(node);
    }

    private int rerouteOutputLocalY(TransferGraphSyncPacket.NodeData node, String portKey) {
        List<String> ports = visibleRerouteOutputPorts(node);
        int index = Math.max(0, ports.indexOf(portKey));
        return REROUTE_ROW_Y + index * 20;
    }

    private int rerouteOutputY(TransferGraphSyncPacket.NodeData node, String portKey) {
        return nodeScreenY(node) + scaled(rerouteOutputLocalY(node, portKey));
    }

    private PortHit outputAt(double mx, double my) {
        for (TransferGraphSyncPacket.NodeData node : visibleNodes()) {
            if (!node.type().equals("CHEST") && !node.type().equals("REROUTE")) continue;
            int x = nodeScreenX(node) + scaled(node.type().equals("REROUTE") ? rerouteOutputPortLocalX() : outputPortLocalX());
            if (node.type().equals("REROUTE")) {
                for (String port : visibleRerouteOutputPorts(node)) {
                    if (hit(mx, my, x, rerouteOutputY(node, port))) return new PortHit(node.id(), port);
                }
                continue;
            }
            int y = allOutputY(node);
            if (hit(mx, my, x, y)) return new PortHit(node.id(), TransferEdge.PORT_ALL);
            if (hit(mx, my, x, fluidOutputY(node))) return new PortHit(node.id(), TransferEdge.FLUID_ALL);
            if (hit(mx, my, x, energyOutputY(node))) return new PortHit(node.id(), TransferEdge.ENERGY_FE);
            if (hit(mx, my, x, stressOutputY(node))) return new PortHit(node.id(), TransferEdge.STRESS_SU);
            if (!isExpanded(node)) continue;
            int iy = firstFilterY(node);
            for (String filter : node.filterItemIds()) {
                if (hit(mx, my, x, iy)) return new PortHit(node.id(), filterPort(filter));
                iy += scaled(18);
            }
        }
        return null;
    }

    private PortHit inputAt(double mx, double my) {
        for (TransferGraphSyncPacket.NodeData node : visibleNodes()) {
            if (!node.type().equals("CHEST") && !node.type().equals("REROUTE") && !node.type().equals("TRASH")) continue;
            int x = nodeScreenX(node) + scaled(inputPortLocalX());
            if (node.type().equals("CHEST")) {
                if (hit(mx, my, x, allOutputY(node))) return new PortHit(node.id(), TransferEdge.ITEM_IN);
                if (hit(mx, my, x, fluidOutputY(node))) return new PortHit(node.id(), TransferEdge.FLUID_IN);
                if (hit(mx, my, x, energyOutputY(node))) return new PortHit(node.id(), TransferEdge.ENERGY_IN);
                if (hit(mx, my, x, stressOutputY(node))) return new PortHit(node.id(), TransferEdge.STRESS_IN);
            } else if (hit(mx, my, x, inputY(node))) {
                String targetPort = TransferEdge.inputPortFor(linkingFromPort);
                if (node.type().equals("TRASH") && (TransferEdge.ENERGY_IN.equals(targetPort) || TransferEdge.STRESS_IN.equals(targetPort))) return null;
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
        return node.type().equals("CHEST") && isExpanded(node) && mx >= nodeScreenX(node) + scaled(8) && mx < nodeScreenX(node) + scaled(90)
                && my >= nodeScreenY(node) + scaled(chestAddFilterLocalY() - 5) && my < nodeScreenY(node) + scaled(chestAddFilterLocalY() + 14);
    }

    private SearchKind chestQuickFilterHit(TransferGraphSyncPacket.NodeData node, double mx, double my) {
        if (!node.type().equals("CHEST")) return null;
        double lx = nodeLocalX(node, mx);
        double ly = nodeLocalY(node, my);
        int bx = NODE_W - 36;
        if (inside(lx, ly, bx, chestItemLocalY() - 8, 18, 14)) return SearchKind.ITEM;
        if (inside(lx, ly, bx, chestFluidLocalY() - 8, 18, 14)) return SearchKind.FLUID;
        return null;
    }

    private FilterHit filterHit(TransferGraphSyncPacket.NodeData node, double mx, double my) {
        if (!node.type().equals("CHEST") || !isExpanded(node)) return null;
        int y = firstFilterY(node);
        for (String filter : node.filterItemIds()) {
            if (mx >= nodeScreenX(node) + scaled(filterRemoveLocalX() - 8) && mx < nodeScreenX(node) + scaled(filterRemoveLocalX() + 9)
                    && my >= y - scaled(8) && my < y + scaled(8)) {
                removeFilterItem(node.id(), filter);
                return new FilterHit(filter, true);
            }
            if (mx >= nodeScreenX(node) + scaled(6) && mx < nodeScreenX(node) + scaled(filterRemoveLocalX() - 11)
                    && my >= y - scaled(9) && my < y + scaled(9)) return new FilterHit(filter, false);
            y += scaled(18);
        }
        return null;
    }

    private int[] inputPos(TransferGraphSyncPacket.NodeData node) {
        return inputPos(node, null);
    }

    private int[] inputPos(TransferGraphSyncPacket.NodeData node, String sourcePort) {
        return new int[]{nodeScreenX(node) + scaled(inputPortLocalX()), chestInputY(node, sourcePort)};
    }

    private int[] outputPos(TransferGraphSyncPacket.NodeData node, String portKey) {
        if (node.type().equals("REROUTE")) return new int[]{nodeScreenX(node) + scaled(rerouteOutputPortLocalX()), rerouteOutputY(node, portKey)};
        if (TransferEdge.PORT_ALL.equals(portKey)) return new int[]{nodeScreenX(node) + scaled(outputPortLocalX()), allOutputY(node)};
        if (TransferEdge.FLUID_ALL.equals(portKey)) return new int[]{nodeScreenX(node) + scaled(outputPortLocalX()), fluidOutputY(node)};
        if (TransferEdge.ENERGY_FE.equals(portKey)) return new int[]{nodeScreenX(node) + scaled(outputPortLocalX()), energyOutputY(node)};
        if (TransferEdge.STRESS_SU.equals(portKey)) return new int[]{nodeScreenX(node) + scaled(outputPortLocalX()), stressOutputY(node)};
        if (!isExpanded(node)) return new int[]{nodeScreenX(node) + scaled(outputPortLocalX()), allOutputY(node)};
        int y = firstFilterY(node);
        for (String filter : node.filterItemIds()) {
            if (filterPort(filter).equals(portKey)) return new int[]{nodeScreenX(node) + scaled(outputPortLocalX()), y};
            y += scaled(18);
        }
        return new int[]{nodeScreenX(node) + scaled(outputPortLocalX()), allOutputY(node)};
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
        return bestDistance <= Math.max(7.0, scaledLineWidth(3.1f) + 5.0f) ? bestId : null;
    }

    private double distanceToBezier(double mx, double my, double x0, double y0, double x3, double y3, float laneOffset) {
        double dx = Math.max(42, Math.abs(x3 - x0) * 0.46);
        double x1 = x0 + dx, y1 = y0 + laneOffset, x2 = x3 - dx, y2 = y3 + laneOffset;
        int segments = Math.max(48, Math.min(160, (int) Math.ceil(Math.hypot(x3 - x0, y3 - y0) / 4.0)));
        double best = Double.MAX_VALUE;
        double px = x0, py = y0;
        for (int i = 1; i <= segments; i++) {
            double t = i / (double) segments;
            double x = cubic(x0, x1, x2, x3, t);
            double y = cubic(y0, y1, y2, y3, t);
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
        if (scope.contains(SCOPE_ALL) || scope.isEmpty()) {
            ports.add(TransferEdge.PORT_ALL);
        }
        if (scope.contains(TransferEdge.FLUID_ALL) || scope.isEmpty()) ports.add(TransferEdge.FLUID_ALL);
        if (scope.contains(TransferEdge.ENERGY_FE) || scope.isEmpty()) ports.add(TransferEdge.ENERGY_FE);
        if (scope.contains(TransferEdge.STRESS_SU) || scope.isEmpty()) ports.add(TransferEdge.STRESS_SU);
        for (String resource : scope) {
            if (resource.startsWith(TransferEdge.ITEM_PREFIX) || resource.startsWith(TransferEdge.FLUID_PREFIX)
                    || TransferEdge.ENERGY_FE.equals(resource) || TransferEdge.STRESS_SU.equals(resource)) ports.add(resource);
        }
        for (String filter : node.filterItemIds()) ports.add(filterPort(filter));
        for (TransferGraphSyncPacket.NodeFlowData flow : node.flowStats()) ports.add(flow.itemId());
        for (TransferGraphSyncPacket.EdgeData edge : visibleEdges()) {
            if (edge.fromNodeId().equals(node.id())) ports.add(edge.fromPortKey());
        }
        return new ArrayList<>(ports);
    }

    private List<TransferGraphSyncPacket.NodeFlowData> rerouteFlowRows(TransferGraphSyncPacket.NodeData node) {
        List<TransferGraphSyncPacket.NodeFlowData> rows = new ArrayList<>(node.flowStats());
        Set<String> seen = new LinkedHashSet<>();
        for (TransferGraphSyncPacket.NodeFlowData flow : rows) seen.add(flow.itemId());
        Set<String> scope = inputScopes().getOrDefault(node.id(), Set.of());
        if (!scope.contains(SCOPE_ALL) || !scope.contains(TransferEdge.FLUID_ALL)) {
            for (String itemId : scope) {
                if (itemId.equals(SCOPE_ALL) || itemId.equals(TransferEdge.FLUID_ALL)) continue;
                if (seen.add(itemId)) rows.add(new TransferGraphSyncPacket.NodeFlowData(itemId, 0, 0, 0, 0));
            }
        }
        for (String itemId : node.filterItemIds()) {
            String resourceId = filterPort(itemId);
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

    private int reroutePopupHeight(TransferGraphSyncPacket.NodeData node) {
        return 74 + Math.max(1, Math.min(5, rerouteFlowRows(node).size())) * 20;
    }

    private Map<String, Set<String>> inputScopes() {
        Map<String, Set<String>> scopes = new HashMap<>();
        for (TransferGraphSyncPacket.EdgeData edge : visibleEdges()) {
            TransferGraphSyncPacket.NodeData from = node(edge.fromNodeId());
            if (from != null && from.type().equals("CHEST")) addScope(scopes.computeIfAbsent(edge.toNodeId(), id -> new LinkedHashSet<>()), edge.fromPortKey(), Set.of(SCOPE_ALL, TransferEdge.FLUID_ALL, TransferEdge.ENERGY_FE, TransferEdge.STRESS_SU));
        }
        boolean changed;
        do {
            changed = false;
            for (TransferGraphSyncPacket.EdgeData edge : visibleEdges()) {
                TransferGraphSyncPacket.NodeData from = node(edge.fromNodeId());
                if (from == null || !from.type().equals("REROUTE")) continue;
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

    private List<String> searchResults() {
        String q = searchValue.trim().toLowerCase();
        if (q.equals(lastSearch)) return cachedSearch;
        lastSearch = q;
        if (q.isEmpty()) return cachedSearch = List.of();
        List<String> result = new ArrayList<>();
        if (searchKind != SearchKind.ITEM) {
            for (Fluid fluid : BuiltInRegistries.FLUID) {
                if (fluid == Fluids.EMPTY) continue;
                ResourceLocation id = BuiltInRegistries.FLUID.getKey(fluid);
                if (id.getPath().startsWith("flowing_")) continue;
                String full = id.toString().toLowerCase();
                String name = new FluidStack(fluid, 1).getHoverName().getString().toLowerCase();
                if (full.contains(q) || id.getPath().contains(q) || name.contains(q)) {
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
                String name = new ItemStack(item).getHoverName().getString().toLowerCase();
                if (full.contains(q) || id.getPath().contains(q) || name.contains(q)) {
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
            copy.add(new TransferGraphSyncPacket.NodeData(n.id(), n.pageId(), n.type(), n.chestId(), n.dimensionKey(), n.pos(), n.x(), n.y(), n.expanded(), n.enabled(), List.copyOf(n.filterItemIds()), List.copyOf(n.flowStats())));
        }
        return copy;
    }

    private List<TransferGraphSyncPacket.EdgeData> copyEdges(List<TransferGraphSyncPacket.EdgeData> edges) {
        List<TransferGraphSyncPacket.EdgeData> copy = new ArrayList<>();
        for (TransferGraphSyncPacket.EdgeData e : edges) {
            copy.add(new TransferGraphSyncPacket.EdgeData(e.id(), e.pageId(), e.fromNodeId(), e.toNodeId(), e.fromPortKey(), e.toPortKey(),
                    e.enabled(), false, 1, 64, e.health(), e.actualRatePerMinute(), List.copyOf(e.itemRates())));
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
        TransferGraphSyncPacket.EdgeItemRateData row = edgeRateRow(edge, selectedRateItemId);
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
        return (mx - popupX) / canvasUiScale();
    }

    private double scaledPopupLocalY(double my) {
        return (my - popupY) / canvasUiScale();
    }

    private boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private String edgeRateLabel(TransferGraphSyncPacket.EdgeData edge) {
        if (edge.itemRates().isEmpty()) return "未测";
        return edge.itemRates().size() + "项 · " + aggregateActualRate(edge.itemRates()) + "/分";
    }

    private String portLabel(String portKey) {
        if (TransferEdge.PORT_ALL.equals(portKey)) return "全部物品";
        if (TransferEdge.FLUID_ALL.equals(portKey)) return "全部流体";
        if (TransferEdge.ENERGY_FE.equals(portKey)) return "电力 FE";
        if (TransferEdge.STRESS_SU.equals(portKey)) return "应力 SU";
        if (portKey.startsWith(TransferEdge.ITEM_PREFIX) || portKey.startsWith(TransferEdge.FLUID_PREFIX)) return shortResource(portKey);
        return portKey;
    }

    private String filterPort(String filter) {
        if (filter != null && filter.startsWith(TransferEdge.FLUID_PREFIX)) return filter;
        if (filter != null && filter.startsWith(TransferEdge.ITEM_PREFIX)) return filter;
        return TransferEdge.itemPort(filter);
    }

    private String filterFromPort(String port) {
        if (port != null && port.startsWith(TransferEdge.FLUID_PREFIX)) return port;
        if (port != null && port.startsWith(TransferEdge.ITEM_PREFIX)) return port.substring(TransferEdge.ITEM_PREFIX.length());
        return port;
    }

    private boolean isFilterPort(String port) {
        return port != null && (port.startsWith(TransferEdge.ITEM_PREFIX) || port.startsWith(TransferEdge.FLUID_PREFIX));
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
        if (TransferEdge.FLUID_ALL.equals(resourceId)) return "全部流体";
        if (TransferEdge.PORT_ALL.equals(resourceId)) return "全部物品";
        if (TransferEdge.ENERGY_FE.equals(resourceId)) return "电力 FE";
        if (TransferEdge.STRESS_SU.equals(resourceId)) return "应力 SU";
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
        return shortItem(id);
    }

    private String resourceRateLabel(int value, String resourceId) {
        if (TransferEdge.ENERGY_FE.equals(resourceId)) return value + "FE/分";
        if (TransferEdge.STRESS_SU.equals(resourceId)) return value + "SU/分";
        return value + (isFluidResource(resourceId) ? "mB/分" : "/分");
    }

    private String shortItem(String itemId) {
        int slash = itemId.indexOf(':');
        return slash >= 0 ? itemId.substring(slash + 1) : itemId;
    }

    private String shortPos(long packed) {
        BlockPos pos = BlockPos.of(packed);
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
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
