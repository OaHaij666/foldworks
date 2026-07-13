# Foldworks UI design system

Foldworks 使用“浅蓝精密工业控制台”视觉语言。Minecraft GUI 的具体实现规则、token、组件状态和资源约束以 `pages/minecraft-gui.md` 为准。

## 核心原则

- 冷白工作面、浅天蓝交互面、深灰蓝文字与结构线。
- 高信息密度，但通过边框、分隔线和间距建立清晰层级。
- 状态不能只依赖颜色；selected、pressed、disabled 必须同时具备形态或明度差异。
- 图标统一使用几何线框语言，不混用卡通填充、emoji 或装饰性高光。
- 危险红、安全绿、警告橙仅用于语义状态。

## Source of truth

- Java token：`src/main/java/com/foldworks/client/ui/Theme.java`
- Minecraft 规则：`design-system/foldworks/pages/minecraft-gui.md`
- Atlas 生成器：`scripts/generate_*_gui_atlas.py`
