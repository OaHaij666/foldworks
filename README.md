# Foldworks（维度工造）

Foldworks 是一个 NeoForge 模组，以生产空间、维度仓、可视化传输图和工造终端为核心，并提供 Create 兼容、离线快照模拟与可选的 Rust/JNI 合成规划器。

## 代码结构

- `src/main/java/com/foldworks/space` 与 `dimension`：生产空间的领域数据和动态维度生命周期。
- `blockentity`、`transfer`、`offline`：维度仓存储、传输图执行及未加载区块的快照模拟。
- `network`：客户端与服务端 payload；所有集合解码必须经过 `NetworkDecodeLimits` 限流。
- `client`：工造终端、维度仓和传输图界面。
- `compat`：可选模组集成，当前主要是 Create。
- `suite` 与 `rust-crafting-planner`：工造套件编排和可选原生合成规划。
- `scripts`：可重复生成已提交美术资源的工具，详见 `scripts/README.md`。

## 构建与测试

项目需要 Java 21。仓库内的 Dynamic Dimensions 开发 JAR 不进入 Git，可通过本地参数指定：

```powershell
.\gradlew clean build --no-daemon "-PlocalDynamicDimensionsJar=libs/dynamicdimensions-neoforge-0.9.1+75e1622e-dirty.jar"
```

Java 单元测试由 Gradle `test` 任务执行。Rust 代码在 Windows CI 中运行 `cargo fmt --check`、`cargo test` 与 release 构建；本地需要可用的 MSVC linker。

## 维护约束

- 模组尚未发布，不保留旧命名空间或旧存档迁移兼容层。
- 网络 payload 必须设置数量和字符串长度上限。
- 传输执行位于服务器 tick 热路径，避免在递归分支中复制整条路径或扫描全部边。
- 资源生成脚本应保持确定性；修改脚本后同时提交生成结果。
