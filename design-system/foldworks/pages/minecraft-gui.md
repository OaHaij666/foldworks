# Foldworks Minecraft GUI override

本文件覆盖 `../MASTER.md` 中面向 Web/移动端的建议。Foldworks 使用 Minecraft GUI、固定 atlas 坐标与代码绘制组件；不套用 CSS、网页字体、SVG 或响应式布局。

## 视觉方向

- 名称：浅蓝精密工业控制台（Light Blue Industrial V4）。
- 冷白工作面承载内容，灰蓝凹槽划分结构，浅天蓝表示主要操作，深灰蓝文字与结构线维持清晰层级。
- 信息密度保持现状；用 1 px 结构线、窄侧标和方圆角建立层级，不用大阴影、大药丸和装饰性高光。
- 危险红、安全绿、警告橙只表示语义状态，不参与普通装饰。

## 颜色 token

| Role | ARGB / Hex | Usage |
|---|---|---|
| Canvas | `#EAF7FE` | 传输图工作区 |
| Surface | `#F8FBFD` | 主面板、节点、卡片 |
| Surface alt | `#E4F2FA` | hover、分组、次级卡片 |
| Surface sunk | `#D5E8F3` | 输入、轨道、凹槽 |
| Primary | `#68BFEA` | 主操作、选中标识 |
| Primary hover | `#86D2F5` | hover |
| Primary strong | `#2577A2` | pressed 结构线、选中文字、强调边框 |
| Border | `#A5C7D9` | 默认结构线 |
| Border strong | `#6FA6C4` | 可交互边框 |
| Text | `#172B3A` | 主文字 |
| Muted text | `#526B7A` | 辅助文字 |
| Danger | `#B43D50` | 删除、错误、阻断 |
| Success | `#2F7D5D` | 正常、完成 |
| Warning | `#A96818` | 警告、风险 |

所有 Java 页面优先引用 `Theme` token。资源类型色允许保留独立映射，但 surface、border、shadow、text、focus 不得再散落硬编码。

## 组件状态

- Primary：浅天蓝底、深蓝字；hover 提亮；pressed 使用中蓝并保留底部结构线。
- Secondary：冷白底、强边框；hover 使用浅蓝底与蓝边。
- Danger：默认浅红底红边；hover/pressed 转红底白字。
- Ghost：静止时仅文字；hover 显示浅蓝结构底。
- Disabled：灰蓝底、弱边框、淡文字，且不响应点击。
- Dropdown：选中项同时使用浅蓝底与左侧 1 px 蓝标，不能只靠颜色；箭头使用统一绘制函数。
- Toggle：3 px 方圆角轨道、白色方圆滑块；开启时增加左侧刻线。
- Slider：凹槽轨道、蓝色进度、白面海军蓝边滑块；hover/drag 有外圈。
- Scrollbar：灰蓝轨道与强蓝灰滑块，禁止透明黑轨道。

## 图标规则

- 导航与大操作图标统一为 2 logical px 几何线框。
- 不使用巨型圆形容器、卡通高光点或同一图标内混合粗细悬殊的笔画。
- active 状态由组件底色/边框表达，图标本体不随状态改变几何尺寸。
- 资源类型不能只靠颜色：物品用网格、流体用水滴、能量用闪电、应力用轴承形状。

## 资源约束

- 不改变 atlas logical size、sprite UV、九宫格坐标、组件 hitbox、槽位、payload、网络或服务端行为。
- 三个 `generate_*_gui_atlas.py` 是运行时 atlas 的唯一生成来源；禁止手工只改 PNG。
