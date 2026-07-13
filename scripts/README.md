# 资源生成脚本

此目录只保留能够生成游戏实际加载资源的脚本：

- `generate_art_assets.py`：方块、升级卡、平板物品纹理与箱子模型。
- `generate_chest_gui_atlas.py`：维度仓 GUI atlas。
- `generate_foldworks_tablet_gui_atlas.py`：工造终端 GUI atlas。
- `generate_transfer_graph_gui_atlas.py`：传输图 GUI atlas。

这些脚本的输出位于 `src/main/resources/assets/foldworks`，属于源码的一部分。脚本应从仓库根目录无交互运行，并覆盖对应资源。仅生成 `docs/client-ui/previews` 临时图片的旧预览脚本已经移除；它们不参与构建，也不生成运行时资源。
