package com.pockethomestead.util;

public class Constants {
    // 空间默认尺寸（XZ）
    public static final int DEFAULT_WIDTH = 64;
    public static final int DEFAULT_DEPTH = 64;

    // 维度高度（匹配 overworld DimensionType：min_y=-64, height=384）
    public static final int WORLD_MIN_Y = -64;
    public static final int WORLD_HEIGHT = 384;

    // 维度最高可建造 Y（min_y + height，独占上界）= 320
    public static final int WORLD_MAX_Y = WORLD_MIN_Y + WORLD_HEIGHT;

    // 基岩层 Y
    public static final int BEDROCK_LAYER_Y = WORLD_MIN_Y;

    // 草方块 / 地表 Y
    public static final int GRASS_Y = WORLD_MIN_Y + 3;

    // 传送点 Y（地表上方两格）
    public static final int SPAWN_Y = WORLD_MIN_Y + 5;

    public static final int DEFAULT_HEIGHT = WORLD_HEIGHT;
}
