package com.foldworks.config;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

public class ModConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // 性能预算配置
    public static final ModConfigSpec.IntValue MAX_CONCURRENT_SPACES = BUILDER
            .comment("最大同时加载的空间数量")
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

    // 命令 /foldworks create 使用的默认参数（UI 路径由玩家在界面选择，不受这些配置影响）
    public static final ModConfigSpec.IntValue DEFAULT_SPACE_HEIGHT = BUILDER
            .comment("命令创建空间的默认高度")
            .defineInRange("defaultSpaceHeight", 64, 8, 1024);

    public static final ModConfigSpec.ConfigValue<String> DEFAULT_SPACE_BIOME = BUILDER
            .comment("命令创建空间的默认群系（设为 random 则按继承维度随机）")
            .define("defaultSpaceBiome", "minecraft:plains");

    public static final ModConfigSpec.ConfigValue<String> DEFAULT_SPACE_SOURCE_DIMENSION = BUILDER
            .comment("命令创建空间默认继承的维度")
            .define("defaultSpaceSourceDimension", "minecraft:overworld");

    public static final ModConfigSpec.BooleanValue DEFAULT_SPACE_MOB_SPAWNING = BUILDER
            .comment("命令创建空间是否默认允许刷怪")
            .define("defaultSpaceMobSpawning", false);

    public static final ModConfigSpec.BooleanValue DEFAULT_SPACE_STRUCTURES = BUILDER
            .comment("命令创建空间是否默认生成建筑结构")
            .define("defaultSpaceStructures", false);

    public static final ModConfigSpec.DoubleValue DEFAULT_SPACE_AMPLITUDE = BUILDER
            .comment("命令创建空间默认的地势起伏（0=平坦，1=陡峭）")
            .defineInRange("defaultSpaceAmplitude", 0.4, 0.0, 1.0);

    // 空间单边最大尺寸（宽/深），同时用于创建界面输入校验与服务端夹紧
    public static final ModConfigSpec.IntValue MAX_SPACE_SIZE = BUILDER
            .comment("空间单边最大尺寸（宽/深）。注意：尺寸越大首次生成与占用越高")
            .defineInRange("maxSpaceSize", 4096, 32, 30000000);

    // 空间单边最小尺寸（宽/深）
    public static final ModConfigSpec.IntValue MIN_SPACE_SIZE = BUILDER
            .comment("空间单边最小尺寸（宽/深）")
            .defineInRange("minSpaceSize", 32, 8, 4096);

    // 单玩家最大空间数量（0 = 不限制）
    public static final ModConfigSpec.IntValue MAX_SPACES_PER_PLAYER = BUILDER
            .comment("每个玩家最多可创建的空间数量（0 = 不限制）")
            .defineInRange("maxSpacesPerPlayer", 16, 0, 1024);

    public static final ModConfigSpec.IntValue SPACE_CHUNK_LOADING_MAX_AREA = BUILDER
            .comment("允许开启常加载的空间最大面积（宽×深，格）。超过该值或无限空间不能开启常加载")
            .defineInRange("spaceChunkLoadingMaxArea", 65536, 1, Integer.MAX_VALUE);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> POCKET_DIMENSION_ENTITY_SPAWN_BLACKLIST = BUILDER
            .comment("空间维度禁止自然生成的实体ID列表。默认屏蔽 Alex's Mobs 的 farseer")
            .defineListAllowEmpty("productionSpaceDimensionEntitySpawnBlacklist",
                    List.of("alexsmobs:farseer"),
                    () -> "",
                    value -> value instanceof String);

    // 创建空间经验消耗：每格1点经验，无限世界固定16万
    public static final ModConfigSpec.IntValue EXP_COST_PER_BLOCK = BUILDER
            .comment("创建有限空间时每格(1x1)消耗的经验点数（创造模式免费）")
            .defineInRange("expCostPerBlock", 1, 0, 1000000);

    public static final ModConfigSpec.IntValue EXP_COST_INFINITE = BUILDER
            .comment("创建无限世界时固定消耗的经验点数（创造模式免费）")
            .defineInRange("expCostInfinite", 160000, 0, Integer.MAX_VALUE);

    // ── 箱子传输配置 ──────────────────────────────────────────────────────────

    public static final ModConfigSpec.IntValue BASE_CHEST_CAPACITY = BUILDER
            .comment("箱子基础物品容量（总方块数，所有物品合计）")
            .defineInRange("baseChestCapacity", 256, 1, 100000000);

    public static final ModConfigSpec.IntValue STORAGE_UPGRADE_CAPACITY = BUILDER
            .comment("每个存储升级增加的物品容量")
            .defineInRange("storageUpgradeCapacity", 128, 0, 100000000);

    public static final ModConfigSpec.IntValue BASE_CHEST_FLUID_TYPES = BUILDER
            .comment("箱子基础可存储的流体种类数量")
            .defineInRange("baseChestFluidTypes", 1, 0, 1000000);

    public static final ModConfigSpec.IntValue FLUID_UPGRADE_TYPES = BUILDER
            .comment("每个流体升级增加的流体种类数量")
            .defineInRange("fluidUpgradeTypes", 1, 0, 1000000);

    public static final ModConfigSpec.IntValue BASE_CHEST_FLUID_CAPACITY_MB = BUILDER
            .comment("箱子基础每种流体容量（mB，1000mB=1桶）")
            .defineInRange("baseChestFluidCapacityMb", 16000, 0, 100000000);

    public static final ModConfigSpec.IntValue FLUID_UPGRADE_CAPACITY_MB = BUILDER
            .comment("每个流体升级增加的每种流体容量（mB，1000mB=1桶）")
            .defineInRange("fluidUpgradeCapacityMb", 16000, 0, 100000000);

    public static final ModConfigSpec.IntValue BASE_CHEST_ENERGY_CAPACITY_FE = BUILDER
            .comment("箱子基础电力容量（FE）。只有安装电力传输升级后生效")
            .defineInRange("baseChestEnergyCapacityFe", 100000, 0, 1000000000);

    public static final ModConfigSpec.IntValue ENERGY_UPGRADE_CAPACITY_FE = BUILDER
            .comment("每个电力传输升级增加的电力容量（FE）")
            .defineInRange("energyUpgradeCapacityFe", 100000, 0, 1000000000);

    public static final ModConfigSpec.IntValue BASE_CHEST_ENERGY_TRANSFER_FE = BUILDER
            .comment("箱子基础单次电力收发上限（FE）")
            .defineInRange("baseChestEnergyTransferFe", 4096, 0, 1000000000);

    public static final ModConfigSpec.IntValue ENERGY_UPGRADE_TRANSFER_FE = BUILDER
            .comment("每个电力传输升级增加的单次电力收发上限（FE）")
            .defineInRange("energyUpgradeTransferFe", 4096, 0, 1000000000);

    public static final ModConfigSpec.IntValue STRESS_UPGRADE_CAPACITY_SU = BUILDER
            .comment("每个应力升级可传递的 Create 应力上限（SU）")
            .defineInRange("stressUpgradeCapacitySu", 256, 0, 100000000);

    public static final ModConfigSpec.BooleanValue GLOBAL_VOID_MODE = BUILDER
            .comment("全局虚空模式开关（服务器默认值，玩家可在箱子UI中单独开关）")
            .define("globalVoidMode", false);

    public static final ModConfigSpec.IntValue TRANSFER_TICK_INTERVAL = BUILDER
            .comment("物品传输间隔（tick），20=每秒一次，值越小传输越频繁但服务器开销越大")
            .defineInRange("transferTickInterval", 20, 1, 1200);

    public static final ModConfigSpec.IntValue NETWORK_BANDWIDTH_PER_UPGRADE = BUILDER
            .comment("每个网络升级在每个传输周期提供的带宽")
            .defineInRange("networkBandwidthPerUpgrade", 8, 0, 100000000);

    public static final ModConfigSpec.IntValue ITEM_BANDWIDTH_COST = BUILDER
            .comment("每传输 1 个物品消耗的带宽")
            .defineInRange("itemBandwidthCost", 8, 1, 1000000);

    public static final ModConfigSpec.IntValue FLUID_MB_PER_BANDWIDTH = BUILDER
            .comment("每 1 点带宽可传输的流体量（mB）")
            .defineInRange("fluidMbPerBandwidth", 25, 1, 100000000);

    public static final ModConfigSpec.IntValue ENERGY_FE_PER_BANDWIDTH = BUILDER
            .comment("每 1 点带宽可传输的电力（FE）")
            .defineInRange("energyFePerBandwidth", 50, 1, 100000000);

    public static final ModConfigSpec.IntValue STRESS_SU_PER_BANDWIDTH = BUILDER
            .comment("每 1 点带宽可传输的 Create 应力（SU）")
            .defineInRange("stressSuPerBandwidth", 2, 1, 100000000);

    public static final ModConfigSpec.BooleanValue VOID_ENABLED = BUILDER
            .comment("是否启用虚空产出（关闭时箱子UI中的虚空按钮置灰不可用，传输只在实际目标箱子存在时进行）")
            .define("voidEnabled", false);

    public static final ModConfigSpec.IntValue DEFAULT_TRANSFER_RATE = BUILDER
            .comment("默认每传输周期的最大物品传输量（0=无限制）")
            .defineInRange("defaultTransferRate", 0, 0, 10000);

    public static final ModConfigSpec.IntValue SUITE_INSTANT_OPERATION_TICKS = BUILDER
            .comment("套件自动订单中，工作台/切石机/锻造台等瞬时工具每次操作占用的tick数")
            .defineInRange("suite.instantOperationTicks", 20, 1, 20 * 60 * 60);

    public static final ModConfigSpec.IntValue SUITE_MAX_ORDERS_PER_CHEST = BUILDER
            .comment("每个维度仓最多保留的套件自动订单数量（0=无限制）")
            .defineInRange("suite.maxOrdersPerChest", 0, 0, 100000);

    public static final ModConfigSpec.IntValue SUITE_PLANNER_MAX_DEPTH = BUILDER
            .comment("套件自动订单在线找路的最大递归深度")
            .defineInRange("suite.plannerMaxDepth", 10, 1, 32);

    public static final ModConfigSpec.IntValue SUITE_PLANNER_MAX_CANDIDATES_PER_ITEM = BUILDER
            .comment("套件自动订单每个目标物品最多尝试的候选配方数量")
            .defineInRange("suite.plannerMaxCandidatesPerItem", 16, 1, 256);

    public static final ModConfigSpec.IntValue SUITE_PLANNER_MAX_BUDGET_MS = BUILDER
            .comment("套件规划器每个 tick 的最大时间预算（毫秒），超时则停止本次搜索，避免卡服")
            .defineInRange("suite.plannerMaxBudgetMs", 10, 1, 1000);

    public static final ModConfigSpec.IntValue SUITE_PLANNER_FAIL_COOLDOWN_TICKS = BUILDER
            .comment("套件订单搜索失败后的冷却 tick 数，冷却期间跳过该订单避免反复空转")
            .defineInRange("suite.plannerFailCooldownTicks", 100, 0, 1200);

    public static final ModConfigSpec.IntValue TICK_OFFSET = BUILDER
            .comment("传输tick偏移（分散不同箱子的传输时刻，减少卡顿）")
            .defineInRange("tickOffset", 3, 1, 19);

    public static final ModConfigSpec.IntValue OFFLINE_SIMULATION_MAX_CATCH_UP_SECONDS = BUILDER
            .comment("离线模拟单次最多追赶的秒数，限制长时间卸载后的瞬时计算量")
            .defineInRange("offlineSimulationMaxCatchUpSeconds", 3600, 10, 86400);

    public static final ModConfigSpec.IntValue OFFLINE_SIMULATION_RATE_WINDOW_SECONDS = BUILDER
            .comment("离线模拟估算外部机器进出的历史速率窗口（秒）")
            .defineInRange("offlineSimulationRateWindowSeconds", 600, 30, 7200);

    public static final ModConfigSpec.IntValue OFFLINE_SIMULATION_MIN_SAMPLE_SECONDS = BUILDER
            .comment("离线模拟启用历史速率前需要的最少有效采样时长（秒）")
            .defineInRange("offlineSimulationMinSampleSeconds", 30, 10, 1200);

    public static final ModConfigSpec.IntValue OFFLINE_SIMULATION_SNAPSHOTS_PER_SECOND = BUILDER
            .comment("每秒最多推进的离线箱子快照数量")
            .defineInRange("offlineSimulationSnapshotsPerSecond", 64, 1, 4096);

    public static final ModConfigSpec.IntValue SPACE_ARCHIVE_MAX_BYTES = BUILDER
            .comment("实验性空间迁移：单个 .phspace 空间包允许的最大字节数")
            .defineInRange("spaceArchiveMaxBytes", 256 * 1024 * 1024, 1024 * 1024, 1024 * 1024 * 1024);

    public static final ModConfigSpec.IntValue SPACE_ARCHIVE_CHUNK_BYTES = BUILDER
            .comment("实验性空间迁移：网络分块大小（字节）")
            .defineInRange("spaceArchiveChunkBytes", 32768, 4096, 1048576);

    public static final ModConfigSpec.IntValue SPACE_ARCHIVE_BYTES_PER_SECOND_PER_PLAYER = BUILDER
            .comment("实验性空间迁移：每名玩家空间包传输限速（字节/秒）")
            .defineInRange("spaceArchiveBytesPerSecondPerPlayer", 1024 * 1024, 4096, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue SPACE_ARCHIVE_MAX_CONCURRENT_TRANSFERS = BUILDER
            .comment("实验性空间迁移：服务器同时处理的空间包上传/下载数量")
            .defineInRange("spaceArchiveMaxConcurrentTransfers", 2, 1, 32);

    public static final ModConfigSpec.ConfigValue<String> SPACE_ARCHIVE_DOWNLOAD_MIN_LEVEL = BUILDER
            .comment("实验性空间迁移：下载服务器空间所需最低权限，可选 VIEW/USE/WRITE/MANAGE")
            .define("spaceArchiveDownloadMinLevel", "WRITE");

    public static final ModConfigSpec SPEC = BUILDER.build();
}
