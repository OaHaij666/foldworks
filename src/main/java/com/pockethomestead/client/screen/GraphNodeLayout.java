package com.pockethomestead.client.screen;

/**
 * 传输图节点布局常量与坐标计算（纯静态，无状态）。
 * 所有坐标均为节点局部坐标系（节点左上角为原点）。
 */
public final class GraphNodeLayout {
    private GraphNodeLayout() {}

    public static final int PORT = 7;
    public static final int PORT_INSET = 12;
    public static final int NODE_W = 190;
    public static final int REROUTE_W = 220;
    public static final int TRASH_W = 172;
    public static final int REROUTE_ROW_Y = 54;

    // 端口 X 坐标
    public static int inputPortLocalX() { return PORT_INSET; }
    public static int outputPortLocalX() { return NODE_W - PORT_INSET; }
    public static int filterRemoveLocalX() { return NODE_W - 33; }
    public static int rerouteOutputPortLocalX() { return REROUTE_W - PORT_INSET; }

    // 箱子节点各行 Y 坐标
    public static int chestItemLocalY() { return 52; }
    public static int chestFluidLocalY() { return 70; }
    public static int chestEnergyLocalY() { return 88; }
    public static int chestStressLocalY() { return 106; }
    public static int chestAddFilterLocalY() { return 124; }
    public static int chestFirstFilterLocalY() { return 144; }

    public static int nodeHeight(String type, boolean expanded, int filterCount, int reroutePortCount, int replenishCount) {
        if (type.equals("REROUTE")) {
            int rows = expanded ? Math.max(1, reroutePortCount) : 1;
            return Math.max(92, REROUTE_ROW_Y + rows * 20 + 36);
        }
        if (type.equals("TRASH")) {
            return expanded ? 116 : 92;
        }
        if (type.equals("PLAYER_INVENTORY")) {
            if (!expanded) return 92;
            return 74 + Math.max(1, replenishCount) * 18 + 36;
        }
        // CHEST
        int h = 144;
        if (expanded) h += filterCount * 18 + 6;
        return h;
    }

    public static int nodeWidth(String type) {
        if (type.equals("REROUTE")) return REROUTE_W;
        if (type.equals("TRASH") || type.equals("PLAYER_INVENTORY")) return TRASH_W;
        return NODE_W;
    }
}
