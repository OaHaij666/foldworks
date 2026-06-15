# Pocket Homestead 技术栈与项目结构说明

## 1. 技术栈

| 组件 | 版本/规范 | 说明 |
|------|-----------|------|
| Minecraft | 1.21.1 | 目标游戏版本 |
| NeoForge | 21.1.x (推荐 21.1.197+) | 模组加载器 |
| Java | 21 (JDK 21) | 必须使用 64 位 JVM |
| Gradle | 8.x (wrapper 内置) | 构建工具 |
| ModDevGradle | 2.0.x+ | NeoForge 官方推荐的 Gradle 插件，替代旧版 NeoGradle |
| IDE | IntelliJ IDEA (推荐) | 集成 Gradle 支持 |

## 2. 项目初始化

### 2.1 MDK 模板

使用 NeoForge 官方 MDK 模板：[MDK-1.21-ModDevGradle](https://github.com/NeoForgeMDKs/MDK-1.21-ModDevGradle)

```bash
git clone https://github.com/NeoForgeMDKs/MDK-1.21-ModDevGradle.git Pocket_Homestead
cd Pocket_Homestead
```

### 2.2 gradle.properties 关键配置

```properties
minecraftVersion=1.21.1
neoforgeVersion=21.1.197
modId=pockethomestead
modName=Pocket Homestead
modVersion=1.0.0
modGroup=com.pockethomestead
```

### 2.3 build.gradle 关键配置

```groovy
plugins {
    id 'net.neoforged.moddev' version '2.0.107'
}

neoForge {
    version = "21.1.197"
    validateAccessTransformers = true
    runs {
        client { client() }
        server { server() }
        data { data() }
    }
    mods {
        pockethomestead {
            sourceSet sourceSets.main
        }
    }
}
```

## 3. 项目目录结构

```
Pocket_Homestead/
├── build.gradle                    # Gradle 构建脚本
├── settings.gradle                 # Gradle 设置
├── gradle.properties               # 版本号、modid 等属性
├── gradlew / gradlew.bat           # Gradle Wrapper
│
├── src/
│   └── main/
│       ├── java/
│       │   └── com/
│       │       └── pockethomestead/
│       │           │
│       │           ├── PocketHomestead.java        # @Mod 入口类
│       │           │
│       │           ├── config/                      # 配置系统
│       │           │   ├── ModConfig.java           # NeoForge ModConfigSpec 配置
│       │           │   └── PerformanceConfig.java   # 性能预算相关配置
│       │           │
│       │           ├── registration/                # 注册中心（DeferredRegister）
│       │           │   ├── ModBlocks.java           # 方块注册
│       │           │   ├── ModItems.java            # 物品注册
│       │           │   ├── ModBlockEntities.java    # BlockEntity 类型注册
│       │           │   ├── ModMenuTypes.java        # 菜单类型注册
│       │           │   ├── ModCreativeTabs.java     # 创造模式标签页
│       │           │   └── ModDimensions.java       # 维度类型注册
│       │           │
│       │           ├── block/                       # 自定义方块
│       │           │   ├── SupplyChestBlock.java    # 供货箱（继承 ChestBlock）
│       │           │   ├── PickupChestBlock.java    # 取货箱（继承 ChestBlock）
│       │           │   ├── PortalBlock.java         # 入口方块
│       │           │   └── ExportBlock.java         # 出口方块
│       │           │
│       │           ├── blockentity/                 # 自定义 BlockEntity
│       │           │   ├── SupplyChestBlockEntity.java   # 供货箱 BE
│       │           │   ├── PickupChestBlockEntity.java   # 取货箱 BE
│       │           │   ├── PortalBlockEntity.java       # 入口方块 BE
│       │           │   └── ExportBlockEntity.java       # 出口方块 BE（含速率统计）
│       │           │
│       │           ├── item/                        # 自定义物品
│       │           │   ├── SpaceCreatorItem.java    # 空间创建道具
│       │           │   └── SpaceEnterItem.java      # 空间进入道具
│       │           │
│       │           ├── dimension/                   # 维度管理
│       │           │   ├── PocketDimensionManager.java  # 维度生命周期管理
│       │           │   ├── PocketChunkGenerator.java    # 自定义 ChunkGenerator
│       │           │   └── PocketDimensionType.java     # 维度类型定义
│       │           │
│       │           ├── space/                       # 空间数据管理
│       │           │   ├── SpaceData.java           # 空间数据模型（ID、权限、尺寸等）
│       │           │   ├── SpaceManager.java        # 空间 CRUD、持久化
│       │           │   ├── SpacePermission.java     # 权限枚举与黑白名单
│       │           │   └── SpaceStorage.java        # NBT/JSON 持久化存储
│       │           │
│       │           ├── scheduler/                   # 计算调度器
│       │           │   ├── SpaceScheduler.java      # 空间轮流加载调度
│       │           │   ├── PerformanceBudget.java   # 性能预算管理
│       │           │   └── RateCalculator.java      # 产出速率计算（滑动窗口/EMA）
│       │           │
│       │           ├── network/                     # 网络包
│       │           │   ├── ModNetwork.java          # 网络频道注册
│       │           │   ├── SpaceListPacket.java     # 空间列表同步
│       │           │   └── RateUpdatePacket.java    # 速率数据同步
│       │           │
│       │           ├── menu/                        # GUI 菜单
│       │           │   ├── SupplyChestMenu.java     # 供货箱菜单
│       │           │   ├── PickupChestMenu.java     # 取货箱菜单
│       │           │   ├── SpaceCreatorMenu.java    # 空间创建菜单
│       │           │   └── SpaceSelectorMenu.java   # 空间选择菜单
│       │           │
│       │           ├── client/                      # 客户端专属代码
│       │           │   ├── screen/                  # GUI Screen
│       │           │   │   ├── SupplyChestScreen.java
│       │           │   │   ├── PickupChestScreen.java
│       │           │   │   ├── SpaceCreatorScreen.java
│       │           │   │   └── SpaceSelectorScreen.java
│       │           │   └── renderer/               # 方块实体渲染器
│       │           │
│       │           ├── command/                     # 聊天命令
│       │           │   └── ModCommands.java         # /pockethomestead 命令注册
│       │           │
│       │           └── util/                        # 工具类
│       │               ├── Constants.java           # 常量定义
│       │               └── NbtHelper.java           # NBT 读写工具
│       │
│       ├── resources/
│       │   ├── assets/
│       │   │   └── pockethomestead/
│       │   │       ├── blockstates/                 # 方块状态 JSON
│       │   │       ├── models/                      # 模型 JSON
│       │   │       │   ├── block/
│       │   │       │   └── item/
│       │   │       ├── textures/                    # 材质 PNG
│       │   │       ├── sounds/                      # 音效 OGG
│       │   │       └── lang/                        # 语言文件
│       │   │           ├── en_us.json
│       │   │           └── zh_cn.json
│       │   │
│       │   ├── data/
│       │   │   └── pockethomestead/
│       │   │       ├── dimension_type/              # 维度类型定义 JSON
│       │   │       │   └── pocket.json
│       │   │       ├── dimension/                   # 维度定义 JSON
│       │   │       │   └── pocket.json
│       │   │       ├── loot_tables/                 # 战利品表
│       │   │       ├── recipes/                     # 合成配方
│       │   │       └── tags/                        # 标签
│       │   │
│       │   ├── META-INF/
│       │   │   └── neoforge.mods.toml               # 模组元数据
│       │   │
│       │   └── pack.mcmeta                          # 资源包元数据
│       │
│       └── generated/                               # Data Generation 输出（自动）
│           └── resources/
│
├── tasks/                                           # 项目文档
│   ├── prd-pocket-homestead.md                      # PRD 文档
│   └── tech-stack-and-structure.md                  # 本文档
│
└── runs/                                            # 运行时目录（自动生成）
    ├── client/
    └── server/
```

## 4. 核心 API 与关键实现路径

### 4.1 维度系统

| 需求 | API | 说明 |
|------|-----|------|
| 注册维度类型 | `DeferredRegister<LevelStem>` → `ModDimensions` | 在 mod bus 的 `RegisterEvent` 中注册 |
| 自定义 ChunkGenerator | 继承 `ChunkGenerator` → `PocketChunkGenerator` | 生成 64×64×24 平坦虚空世界 |
| 维度数据存储 | `MinecraftServer#storageSource` + 自定义 NBT | NeoForge 将自定义维度放在 `dimensions/` 目录下 |
| 动态创建维度 | `MinecraftServer#getLevel(ResourceKey<Level>)` | 按需加载/卸载维度 |
| 卸载维度 | 卸载所有 chunk → 等待自动保存 | 无直接 API，需通过 chunk 卸载实现 |

**关键类链路：**
```
PocketDimensionManager
  ├── createSpace() → 注册新 ResourceKey<Level> + LevelStem
  ├── loadSpace()   → MinecraftServer#getLevel() 或 createLevel()
  ├── tickSpace()   → 在 ServerTickEvent 中按调度执行
  └── unloadSpace() → 卸载所有 Chunk → 保存数据
```

### 4.2 方块与 BlockEntity

| 需求 | API | 说明 |
|------|-----|------|
| 供货箱/取货箱 | 继承 `ChestBlock` + `ChestBlockEntity` | 天然兼容漏斗等管道 |
| 入口/出口方块 | `Block` + `BlockEntity` | 自定义逻辑，存储连接 ID 和统计数据 |
| BlockEntity 注册 | `DeferredRegister<BlockEntityType<?>>` | 使用 `BlockEntityType.Builder.of()` |
| BlockEntity 数据持久化 | `saveAdditional()` / `loadAdditional()` | 1.21.1 新签名含 `HolderLookup.Provider` |
| BlockEntity Tick | `EntityBlock#getTicker()` → `BlockEntityTicker` | 用于出口方块的速率统计 |

**供货箱/取货箱继承链：**
```
ChestBlockEntity
  └── SupplyChestBlockEntity (供货箱)
      ├── 绑定空间 ID
      ├── 绑定入口方块坐标
      └── 定期扣除原料逻辑

ChestBlockEntity
  └── PickupChestBlockEntity (取货箱)
      ├── 绑定空间 ID
      ├── 绑定出口方块坐标
      ├── 显示产出速率
      └── 定期添加产物逻辑
```

### 4.3 物品与交互

| 需求 | API | 说明 |
|------|-----|------|
| 空间创建道具 | 继承 `Item`，重写 `use()` | 打开创建 GUI |
| 空间进入道具 | 继承 `Item`，重写 `use()` | 打开空间列表 GUI |
| 传送玩家 | `Player#teleportTo()` 或 `Entity#changeDimension()` | 跨维度传送 |

### 4.4 配置系统

| 需求 | API | 说明 |
|------|-----|------|
| 模组配置 | `ModConfigSpec` + `ModContainer#registerConfig()` | NeoForge 原生配置系统 |
| 配置文件格式 | TOML（`run/config/pockethomestead-common.toml`） | NeoForge 标准格式 |

**关键配置项：**
```toml
# 性能预算
performanceBudget {
    # 最大同时加载空间数
    maxConcurrentSpaces = 3
    # 每个空间每次加载的计算时长（tick）
    ticksPerSession = 200
    # 验证间隔（秒）
    verificationInterval = 3600
    # 总性能预算（ms/tick）
    maxMilliPerTick = 10
}

# 空间默认设置
spaceDefaults {
    # 默认尺寸
    defaultSize = "64x64x24"
    # 默认权限模式
    defaultPermission = "PRIVATE"
}
```

### 4.5 网络同步

| 需求 | API | 说明 |
|------|-----|------|
| 注册网络频道 | `PayloadRegistrar` (NeoForge 1.21.1 新网络 API) | 替代旧版 SimpleChannel |
| 自定义 Payload | 实现 `CustomPacketPayload` 接口 | 定义数据包结构 |
| 发送到客户端 | `PacketDistributor#sendToPlayer()` | 同步空间列表、速率数据 |

### 4.6 事件系统

| 事件 | 总线 | 用途 |
|------|------|------|
| `ServerTickEvent.Post` | NeoForge.EVENT_BUS | 调度器主循环 |
| `RegisterEvent` | mod bus | 注册方块、物品、维度等 |
| `PlayerEvent.PlayerChangedDimensionEvent` | NeoForge.EVENT_BUS | 玩家跨维度传送 |
| `EntityJoinLevelEvent` | NeoForge.EVENT_BUS | 实体进入维度 |

## 5. 数据流架构

```
主世界                              口袋空间
┌──────────────┐                   ┌──────────────────┐
│  供货箱       │ ──── 原料 ────→  │  入口方块         │
│  (SupplyChest)│                   │  (PortalBlock)    │
│               │                   │        │          │
│  定期扣除原料  │                   │        ▼          │
│               │                   │  [玩家机器运行]    │
│               │                   │        │          │
│  取货箱       │ ←── 产物 ────   │  出口方块         │
│  (PickupChest)│                   │  (ExportBlock)    │
│               │                   │  统计产出速率      │
│  定期添加产物  │                   │                   │
└──────────────┘                   └──────────────────┘
       ↑                                    ↑
       │                                    │
       └──── SpaceScheduler 调度 ───────────┘
            (定期加载/卸载/验证)
```

## 6. 关键技术决策

| 决策 | 选择 | 原因 |
|------|------|------|
| 构建插件 | ModDevGradle (非 NeoGradle) | NeoForge 官方推荐，更现代 |
| 箱子实现 | 继承 ChestBlock/ChestBlockEntity | 天然兼容漏斗、管道等 |
| 维度存储 | 每个空间独立维度实例 | 完全隔离，避免坐标冲突 |
| 速率算法 | 滑动窗口 | 比 EMA 更直观，便于调试 |
| 数据持久化 | 自定义 NBT + SavedData | NeoForge 标准，服务器重启恢复 |
| 网络层 | NeoForge 1.21.1 Payload API | 新版标准，替代旧版 SimpleChannel |
| 配置格式 | TOML via ModConfigSpec | NeoForge 原生支持 |

## 7. 开发环境搭建步骤

1. 克隆 MDK 模板
2. 修改 `gradle.properties` 中的 modid、版本号
3. 修改 `build.gradle` 中的 NeoForge 版本
4. 修改 `src/main/resources/META-INF/neoforge.mods.toml`
5. 删除示例包，创建 `com.pockethomestead` 包
6. 创建入口类 `PocketHomestead.java`
7. 运行 `gradlew runClient` 验证环境

## 8. 注意事项

- **1.21.1 API 变化**：`BlockEntity#saveAdditional()` 和 `loadAdditional()` 新增 `HolderLookup.Provider` 参数
- **维度目录**：NeoForge 将自定义维度数据放在 `saves/<world>/dimensions/` 下，格式为 `namespace/dimension_name/`
- **modid 规范**：只能包含小写字母、数字和下划线
- **Data Generation**：推荐使用 NeoForge 的 DataGen 系统自动生成 JSON 文件，而非手写
- **客户端/服务端隔离**：维度管理、调度器等逻辑仅在服务端运行，客户端仅负责 GUI 渲染
