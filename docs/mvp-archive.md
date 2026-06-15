# Pocket Homestead 归档文档

> 本文档记录项目从 MVP 到当前可用版本的功能与实现快照。
> 配套文档：[architecture.md](architecture.md)（架构图）、[technical-guide.md](technical-guide.md)（系统技术说明）。

## 1. 功能说明

Pocket Homestead（口袋家园）是一款 Minecraft Java 版 1.21.1 / NeoForge 21.1.x 模组。核心功能是为玩家提供独立的"口袋空间"（动态维度），用于放置工业模组机器，通过调度器轮流加载/卸载空间并以速率统计模拟产出，解决大量机器持续渲染与运算导致的性能问题。

### 已实现功能

| 功能 | 状态 | 说明 |
|------|------|------|
| 动态维度创建 | ✅ | 基于 `galacticraft/dynamic-dimensions` 运行时创建/删除维度 |
| 自定义地形生成 | ✅ | `PocketChunkGenerator` 支持 SUPERFLAT / FLAT / NATURAL |
| 空间创建 GUI | ✅ | `SpaceCreateScreen`：尺寸、地形、群系、填充方块、刷怪/结构开关 |
| 空间列表 GUI | ✅ | `SpaceListScreen`：浏览、进入、退出、删除 |
| 空间创建/进入道具 | ✅ | `SpaceCreatorItem` / `SpaceEnterItem`（打开对应 GUI） |
| 聊天命令 | ✅ | `/pockethomestead create\|list\|enter\|exit\|delete` |
| 供货箱 / 取货箱 | ✅ | 主世界容器，绑定空间入口/出口 |
| 入口方块 / 出口方块 | ✅ | 维度内输入/输出缓冲容器 |
| 跨维度物品传输 | ✅ | `SpaceItemTransfer`：供货箱→入口、出口→取货箱 |
| 速率统计 | ✅ | `RateCalculator` 滑动窗口算法，持久化到 NBT |
| 空间权限系统 | ✅ | 私有/公开模式 + 黑白名单 |
| 空间调度器 | ✅ | 轮流加载/卸载空间，会话计时 |
| 性能预算 | ✅ | 可配置并发空间数、单次计算时长 |
| 玩家返回 | ✅ | 记录进入锚点，退出传送回原位置 |
| 数据持久化 | ✅ | `SpaceStorage`（SavedData）+ BlockEntity NBT |
| 多人模式兼容 | ✅ | 客户端通过网络包请求空间列表，不再依赖单机服务端实例 |
| 配置系统 | ✅ | TOML 配置文件 |

### 待完善功能

| 功能 | 优先级 | 说明 |
|------|--------|------|
| 权限管理 GUI | 中 | 黑白名单目前仅能通过代码/数据操作 |
| 供货箱按需扣除节流 | 中 | 当前每周期尽量传输，无单位时间配额 |
| 纹理材质 | 低 | 使用占位纹理 |
| 合成配方 | 低 | 暂无合成配方 |

## 2. 实现思路

### 2.1 架构分层

```
┌──────────────────────────────────────────────┐
│              客户端 / GUI 层                   │
│  SpaceCreateScreen / SpaceListScreen           │
│  ClientSpaceCache / ClientScreenHooks          │
├──────────────────────────────────────────────┤
│                  网络层                        │
│  ModMessages + 5 个 Payload (C↔S)              │
├──────────────────────────────────────────────┤
│                业务逻辑层                      │
│  SpaceManager / SpaceScheduler                 │
│  SpaceItemTransfer / SpaceItemRegistry         │
│  RateCalculator / PerformanceBudget            │
├──────────────────────────────────────────────┤
│                维度管理层                      │
│  PocketDimensionManager / SpaceDimensionService│
│  PocketChunkGenerator                          │
├──────────────────────────────────────────────┤
│                数据持久层                      │
│  SpaceStorage (SavedData) / BlockEntity NBT    │
├──────────────────────────────────────────────┤
│                  注册层                        │
│  ModBlocks / ModItems / ModBlockEntities       │
│  ModMenuTypes / ModCreativeTabs / ModDimensions│
└──────────────────────────────────────────────┘
```

### 2.2 核心流程

**空间创建（GUI 两阶段）：**
1. 玩家在 `SpaceCreateScreen` 配置参数并点击创建
2. 客户端发送 `CreateSpacePayload`（尺寸/地形/群系/开关）→ 服务端暂存
3. 客户端发送 `CreateSpaceConfigPayload`（源维度/填充方块）
4. 服务端合并参数，`SpaceManager.createSpace()` 生成 `SpaceData` 并 `SpaceDimensionService.loadOrCreate()` 创建维度
5. `SpaceStorage.markDirty()` 持久化；`queueTeleportToSpace()` 排队传送；向客户端推送最新 `SpaceListPayload`

**空间进入/退出：**
1. `SpaceListScreen` 发送 `SpaceActionPayload(ENTER/EXIT, spaceId)`
2. 进入时 `PocketDimensionManager` 记录返回锚点并延迟 8 tick 传送（规避维度注册与区块加载竞态）
3. 退出时根据锚点传送回原维度位置，无锚点则回主世界出生点

**物品流转（调度器驱动）：**
1. `SpaceScheduler` 每 20 tick 选取活跃空间，调用 `SpaceItemTransfer.tick()`
2. 供货箱 → 入口方块：跨维度搬运原料
3. 出口方块 → 取货箱：清空产物，`RateCalculator` 记录速率，溢出舍弃
4. 会话计时归零后空间退出活跃集合，等待下次轮询

### 2.3 速率统计算法

滑动窗口算法（`RateCalculator`）：
- 维护最近输出事件队列，每条记录 `{游戏刻, 数量}`
- 速率 = 窗口内总数量 / 窗口时间跨度
- 持久化 `lastKnownRate`，在出口方块暂时为空时仍返回上次速率，避免显示抖动

## 3. 关键代码说明

### 3.1 入口类 — PocketHomestead.java

```java
@Mod(PocketHomestead.MODID)
public class PocketHomestead {
    public static final String MODID = "pockethomestead";

    public PocketHomestead(IEventBus modEventBus, ModContainer modContainer) {
        ModBlocks.BLOCKS.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        // ... 其他 DeferredRegister
        modContainer.registerConfig(ModConfig.Type.COMMON, ModConfig.SPEC);
    }
}
```

### 3.2 维度服务 — SpaceDimensionService.java

封装 `DynamicDimensionRegistry`：`loadOrCreate()` 加载或创建动态维度，`unload()` / `delete()` 卸载或删除，`prepareSafeSpawn()` 不强制加载区块即可推算安全出生 Y。

### 3.3 物品注册表 — SpaceItemRegistry.java

服务端线程安全（`ConcurrentHashMap.newKeySet()`）查找表，记录每个空间的入口/出口方块坐标与供货/取货箱 `GlobalPos`。BlockEntity 在 `onLoad()` 注册、`setRemoved()` 注销；入口/出口方块从维度名 `space_<uuid>` 自动推断 spaceId。

### 3.4 空间权限 — SpacePermission.java

```
PRIVATE              仅所有者可进入
PUBLIC + 白名单非空   仅白名单玩家可进入
PUBLIC + 白名单为空   所有非黑名单玩家可进入
黑名单始终优先
```

### 3.5 调度器 — SpaceScheduler.java

- 每 20 tick（1 秒）执行一次
- `schedulingQueue`（等待）/ `currentlyTicking`（活跃）/ `sessionTicksRemaining`（每空间会话倒计时）
- 按 `PerformanceBudget.maxConcurrentSpaces` 限制并发，会话耗尽后退出活跃集合

## 4. 配置说明

配置文件位置：`config/pockethomestead-common.toml`

| 配置项 | 默认值 | 范围 | 说明 |
|--------|--------|------|------|
| maxConcurrentSpaces | 3 | 1–20 | 最大同时加载空间数 |
| ticksPerSession | 200 | 20–6000 | 每空间每次计算时长(tick) |
| verificationIntervalSeconds | 3600 | 60–86400 | 验证间隔(秒) |
| maxMilliPerTick | 10 | 1–50 | 每 tick 最大性能预算(ms) |
| defaultSpaceSize | "64x64x24" | — | 默认空间尺寸 |

## 5. 使用指南

### 5.1 快速开始

1. 安装 NeoForge 1.21.1（需 `dynamic-dimensions` 依赖）
2. 将模组 JAR 放入 `mods` 目录
3. 进入游戏，在创造模式物品栏 "Pocket Homestead" 标签页找到所有物品

### 5.2 基本操作

1. **创建空间**：手持"空间创建器"右键 → 在 GUI 配置尺寸/地形/群系等 → 创建并进入
2. **管理空间**：手持"空间进入器"右键打开列表 → 进入 / 退出 / 删除
3. **放置入口/出口方块**：在空间内放置，分别接收原料、收集产物
4. **放置供货箱/取货箱**：在主世界放置并绑定空间，供货箱放入原料、取货箱收取产物
5. **命令操作**：`/pockethomestead create <名称> <尺寸> <superflat|flat|natural>`、`list`、`enter <空间>`、`exit`、`delete <空间>`

### 5.3 开发环境

```bash
.\gradlew build       # 编译
.\gradlew runClient   # 运行客户端
.\gradlew runServer   # 运行服务端
.\gradlew runData     # 生成数据
```

## 6. 项目结构

```
src/main/java/com/pockethomestead/
├── PocketHomestead.java          # 入口类
├── ModEvents.java                # 服务端生命周期事件
├── config/
│   └── ModConfig.java            # TOML 配置定义
├── registration/
│   ├── ModBlocks.java            # 方块注册
│   ├── ModItems.java             # 物品注册
│   ├── ModBlockEntities.java     # BlockEntity 注册
│   ├── ModMenuTypes.java         # 菜单类型注册
│   ├── ModCreativeTabs.java      # 创造标签页注册
│   └── ModDimensions.java        # 维度相关注册
├── block/
│   ├── SupplyChestBlock.java     # 供货箱
│   ├── PickupChestBlock.java     # 取货箱
│   ├── PortalBlock.java          # 入口方块
│   └── ExportBlock.java          # 出口方块
├── blockentity/
│   ├── SupplyChestBlockEntity.java
│   ├── PickupChestBlockEntity.java
│   ├── PortalBlockEntity.java
│   └── ExportBlockEntity.java
├── item/
│   ├── SpaceCreatorItem.java     # 空间创建道具（打开创建 GUI）
│   └── SpaceEnterItem.java       # 空间进入道具（打开列表 GUI）
├── dimension/
│   ├── PocketDimensionManager.java  # 玩家传送 / 返回锚点
│   ├── SpaceDimensionService.java   # 动态维度封装
│   └── PocketChunkGenerator.java    # 自定义地形生成
├── space/
│   ├── SpaceData.java            # 空间数据模型
│   ├── SpaceManager.java         # 空间管理器（内存注册表）
│   ├── SpacePermission.java      # 权限管理
│   ├── SpaceStorage.java         # 持久化存储 (SavedData)
│   └── SpaceItemRegistry.java    # 物品方块位置查找表
├── scheduler/
│   ├── SpaceScheduler.java       # 调度器
│   ├── SpaceItemTransfer.java    # 跨维度物品传输
│   ├── PerformanceBudget.java    # 性能预算
│   └── RateCalculator.java       # 速率计算
├── network/
│   ├── ModMessages.java              # 网络包注册
│   ├── SpaceInfo.java                # 客户端安全 DTO
│   ├── CreateSpacePayload.java       # C→S 创建阶段1
│   ├── CreateSpaceConfigPayload.java # C→S 创建阶段2
│   ├── SpaceActionPayload.java       # C→S 进入/退出/删除
│   ├── RequestSpaceListPayload.java  # C→S 请求列表
│   └── SpaceListPayload.java         # S→C 推送列表
├── client/
│   ├── SpaceCreateScreen.java    # 空间创建界面
│   ├── SpaceListScreen.java      # 空间列表界面
│   ├── ClientSpaceCache.java     # 客户端列表缓存
│   └── ClientScreenHooks.java    # 按键打开界面
├── command/
│   └── PocketHomesteadCommand.java  # /pockethomestead 命令
└── util/
    └── Constants.java            # 常量定义
```
