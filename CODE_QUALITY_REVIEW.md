# Foldworks 代码质量审查

审查日期：2026-07-13

## 范围与基线

- Understand Anything 全量扫描 219 个有效项目文件，排除了 `.ua` 自生成缓存。
- 知识图谱包含 2387 个节点、4320 条关系、9 个架构层和 12 步导览。
- Java、Rust、Python、Gradle、Minecraft JSON/MCMeta 与 GitHub Actions 均纳入检查。

## 已解决问题

### P1：传输图热路径重复扫描全部边

`TransferGraph.outgoing` 原先每次调用都遍历完整边集合。在线和离线传输会递归查询出边，复杂图中会反复产生 O(E) 扫描。

处理：新增惰性 `OutgoingEdgeIndex`。边结构变化时显式失效，下次读取统一重建；读取成本降为与节点出度相关。测试覆盖重建时机、插入顺序和防御性返回。

### P1：在线递归传输产生路径级集合复制

在线箱子执行器原先在每条分支复制 `HashSet` 和路径列表，深图会产生接近 O(depth²) 的临时分配；离线执行器已经采用回溯复用，两套实现不一致。

处理：在线执行器改为 `try/finally` 回溯同一份 path/visited，并预先将源节点加入 visited，避免环路回到源节点后再多走一层。

### P2：分析缓存污染项目图谱

`.ua` 运行输出未被 Git 和 Understand Anything 完整忽略，旧图谱曾被重新扫描成项目文件。

处理：`.gitignore` 与 `.ua/.understandignore` 排除运行输出，只保留可维护的忽略配置和语言配置。

### P2：无效预览脚本与脚本职责不清

4 个 `draw_*_preview.py` 只向已忽略的 `docs/client-ui/previews` 写临时图片，不参与构建，也不生成游戏资源。

处理：删除这些旧预览脚本；保留所有 `generate_*` 运行资源生成器，并新增 `scripts/README.md` 记录输入、输出和保留原则。

### P2：维护入口与编译告警不足

项目没有 README，且 Java 编译只给出笼统的 deprecated API 提示。

处理：新增项目 README；JavaCompile 启用 `-Xlint:deprecation` 与 `-Xlint:unchecked`；移除 `ModMessages` 已待删除的事件总线参数并收紧工具类构造。

## 当前结构风险

以下文件职责仍然偏重，但本轮没有进行高回归风险的大拆分：

- `TransferGraphScreen.java`：约 3185 行，渲染、输入、弹窗和菜单状态集中。
- `BaseChestBlockEntity.java`：约 2630 行，存储、升级、套件执行、图传输和序列化集中。
- `TabletChestPage.java`：约 1680 行，多个工造套件 UI 共用一个页面实现。
- `OfflineGraphTransferExecutor.java`：约 1071 行，与在线执行器存在可继续抽取的传输策略重复。
- `TabletChestActionPacket.java`：约 1000 行，payload 分发与多种套件业务操作耦合。

建议后续按“纯逻辑先行”拆分：端口匹配/预算算法、套件操作、UI 输入状态机、NBT DTO。每次拆分必须先建立行为测试，不建议仅按行数机械分文件。

## 已知外部 API 风险

`-Xlint:deprecation` 当前报告 6 处 Minecraft/NeoForge API：熔炉燃料/容器剩余物、动态维度 dirty 标记、移动方块实体状态更新。它们涉及上游生命周期语义，应结合目标 NeoForge 与 Dynamic Dimensions 版本逐项迁移，不能仅为消除警告替换。

## 验证结果

- `gradlew clean build`：通过。
- Java：9 tests，0 failures，0 errors。
- Rust：`cargo fmt --check` 通过；完整本地 Rust test/build 仍依赖 Windows MSVC linker，CI 已覆盖。
- Python：4 个保留资源生成脚本通过 `py_compile`。
- 资源：39 个 JSON/MCMeta 全部可解析。
- 本地化：`en_us` 与 `zh_cn` 均为 101 个键且集合一致。
- 旧命名：仅迁移报告中的兼容性说明保留一次历史命名引用。
