package com.pockethomestead.client.screen;

/**
 * 传输图节点布局常量与坐标计算（纯静态，无状态）。
 * 所有坐标均为节点局部坐标系（节点左上角为原点）。
 */
public final class GraphNodeLayout {
    private GraphNodeLayout() {}

    public static final int PORT = 7;
    public static final int PORT_INSET = 10;
    public static final int NODE_W = 202;
    public static final int REROUTE_W = 278;
    public static final int TRASH_W = 184;
    public static final int GATE_W = 184;
    public static final int JUMP_W = 126;
    public static final int MINI_NODE_H = 46;
    public static final int JUMP_H = 38;
    public static final int REROUTE_ROW_Y = 49;

    // 端口 X 坐标
    public static int inputPortLocalX() { return PORT_INSET; }
    public static int outputPortLocalX() { return NODE_W - PORT_INSET; }
    public static int filterRemoveLocalX() { return NODE_W - 33; }
    public static int rerouteOutputPortLocalX() { return REROUTE_W - PORT_INSET; }

    // 箱子节点各行 Y 坐标
    public static int chestItemLocalY() { return 86; }
    public static int chestFluidLocalY() { return 106; }
    public static int chestEnergyLocalY() { return 126; }
    public static int chestStressLocalY() { return 146; }
    public static int chestAddFilterLocalY() { return 166; }
    public static int chestFirstFilterLocalY() { return 186; }

    public static int nodeHeight(String type, boolean expanded, int filterCount, int reroutePortCount, int replenishCount) {
        if (type.equals("REROUTE")) {
            int rows = expanded ? Math.max(4, reroutePortCount) : 4;
            return Math.max(146, REROUTE_ROW_Y + rows * 24 + 14);
        }
        if (type.equals("TRASH")) {
            return expanded ? 88 : 74;
        }
        if (type.equals("LIMIT_GATE")) {
            return MINI_NODE_H;
        }
        if (type.equals("JUMP_INPUT") || type.equals("JUMP_OUTPUT")) return JUMP_H;
        if (type.equals("PLAYER_INVENTORY")) {
            if (!expanded) return 78;
            return 74 + Math.max(1, replenishCount) * 18 + 8;
        }
        // CHEST
        int h = 160;
        if (expanded) h += filterCount * 18 + 6;
        return h;
    }

    public static int nodeWidth(String type) {
        if (type.equals("REROUTE")) return REROUTE_W;
        if (type.equals("LIMIT_GATE")) return GATE_W;
        if (type.equals("JUMP_INPUT") || type.equals("JUMP_OUTPUT")) return JUMP_W;
        if (type.equals("TRASH") || type.equals("PLAYER_INVENTORY")) return TRASH_W;
        return NODE_W;
    }
}
