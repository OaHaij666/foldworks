# Foldworks / 维度工造命名迁移报告

日期：2026-07-12

## 最终命名

- 英文品牌：`Foldworks`
- 中文品牌：`维度工造`
- Gradle 根项目：`foldworks`
- mod ID：`foldworks`
- Java group/package：`com.foldworks`
- 资源、数据、网络与维度命名空间：`foldworks`
- SavedData：`foldworks_spaces`
- 动态维度：`foldworks:space_<uuid>`
- ChunkGenerator codec：`foldworks:production_space`
- 命令：`/foldworks`
- Rust crate：`foldworks-crafting-planner`
- JNI 包符号：`Java_com_foldworks_suite_NativeCraftingPlanner_*`

## 用户侧术语

- 可进入的生产空间：`Production Space` / `工域`
- 核心存储设备：`Dimensional Depot` / `维度仓`
- 便携管理设备：`Works Terminal` / `工造终端`

## 迁移范围

- Java 主类、包目录、类名、常量和测试包
- NeoForge mod 元数据、Gradle group、JAR 名称和 CI
- 所有 `assets/foldworks`、`data/foldworks` 资源目录
- 方块状态、模型、纹理、配方、字体、翻译键和网络 payload ID
- 动态维度、SavedData、配置目录、归档目录与注册表路径
- Rust JNI 符号、原生库文件名和 GitHub Actions artifact
- Python 美术生成与 UI 预览脚本

## 兼容策略

项目尚未发布，因此本次采用干净迁移，不保留 `pockethomestead:*` 注册表、维度或 SavedData 兼容逻辑。迁移前创建的开发世界应视为不兼容，建议删除后重新生成。

## 验证结果

- `gradlew clean build`：成功（使用显式本地 DynamicDimensions JAR）
- Java 测试：3 个套件、7 项测试，全部通过
- 资源 JSON/MCMeta：39 个文件全部可解析
- 中英文翻译：各 101 个键，键集合完全一致
- 构建产物：`build/libs/foldworks-0.0.1.jar`
- JAR 内旧命名空间条目：0
- Rust 格式检查：`cargo fmt --check` 通过
- Rust Windows 测试与 `safe_v2` DLL 构建：由 GitHub Actions `rust-native` job 执行
