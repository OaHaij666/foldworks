package com.pockethomestead.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public class ModConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // 性能预算配置
    public static final ModConfigSpec.IntValue MAX_CONCURRENT_SPACES = BUILDER
            .comment("最大同时加载的口袋空间数量")
            .defineInRange("maxConcurrentSpaces", 3, 1, 20);

    public static final ModConfigSpec.IntValue TICKS_PER_SESSION = BUILDER
            .comment("每个空间每次加载的计算时长(tick)")
            .defineInRange("ticksPerSession", 200, 20, 6000);

    public static final ModConfigSpec.IntValue VERIFICATION_INTERVAL_SECONDS = BUILDER
            .comment("验证间隔(秒)，定期重新加载空间验证产出速率")
            .defineInRange("verificationIntervalSeconds", 3600, 60, 86400);

    public static final ModConfigSpec.IntValue MAX_MILLI_PER_TICK = BUILDER
            .comment("每tick最大性能预算(毫秒)")
            .defineInRange("maxMilliPerTick", 10, 1, 50);

    // 空间默认设置
    public static final ModConfigSpec.ConfigValue<String> DEFAULT_SPACE_SIZE = BUILDER
            .comment("默认空间尺寸")
            .define("defaultSpaceSize", "64x64x24");

    // 空间单边最大尺寸（宽/深），同时用于创建界面输入校验与服务端夹紧
    public static final ModConfigSpec.IntValue MAX_SPACE_SIZE = BUILDER
            .comment("口袋空间单边最大尺寸（宽/深）。注意：尺寸越大首次生成与占用越高")
            .defineInRange("maxSpaceSize", 4096, 32, 30000000);

    // 空间单边最小尺寸（宽/深）
    public static final ModConfigSpec.IntValue MIN_SPACE_SIZE = BUILDER
            .comment("口袋空间单边最小尺寸（宽/深）")
            .defineInRange("minSpaceSize", 32, 8, 4096);

    // ── 箱子传输配置 ──────────────────────────────────────────────────────────

    public static final ModConfigSpec.IntValue MAX_CHEST_CAPACITY = BUILDER
            .comment("箱子最大容量（总方块数，所有物品合计）")
            .defineInRange("maxChestCapacity", 4096, 27, 100000);

    public static final ModConfigSpec.BooleanValue GLOBAL_VOID_MODE = BUILDER
            .comment("全局虚空模式开关（服务器默认值，玩家可在箱子UI中单独开关）")
            .define("globalVoidMode", false);

    public static final ModConfigSpec.IntValue TRANSFER_TICK_INTERVAL = BUILDER
            .comment("物品传输间隔（tick），20=每秒一次，值越小传输越频繁但服务器开销越大")
            .defineInRange("transferTickInterval", 20, 1, 1200);

    public static final ModConfigSpec.IntValue SYNC_INTERVAL_SECONDS = BUILDER
            .comment("同步时间（秒）：供货箱每隔该时长将物品传输到绑定的取货箱一次。默认30秒。")
            .defineInRange("syncIntervalSeconds", 30, 1, 3600);

    public static final ModConfigSpec.BooleanValue VOID_ENABLED = BUILDER
            .comment("是否启用虚空产出（关闭时箱子UI中的虚空按钮置灰不可用，传输只在实际目标箱子存在时进行）")
            .define("voidEnabled", false);

    public static final ModConfigSpec.IntValue DEFAULT_TRANSFER_RATE = BUILDER
            .comment("默认每传输周期的最大物品传输量（0=无限制）")
            .defineInRange("defaultTransferRate", 0, 0, 10000);

    public static final ModConfigSpec.IntValue TICK_OFFSET = BUILDER
            .comment("传输tick偏移（分散不同箱子的传输时刻，减少卡顿）")
            .defineInRange("tickOffset", 3, 1, 19);

    public static final ModConfigSpec SPEC = BUILDER.build();
}
