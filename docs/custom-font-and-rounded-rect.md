# 自定义字体 & 抗锯齿圆角实现方案

> 适用版本：Minecraft 1.21.1 NeoForge  
> 相关源码：`Theme.java`、`smooth_ui.json`

---

## 一、自定义平滑字体

### 目标

只让**模组自有 UI** 使用平滑 TTF 字体（Noto Sans SC），不影响原版 Minecraft 像素字体。

### 实现方式

使用 Minecraft 原生的 `Font` + `Style.withFont()` 机制，这是官方推荐的方式，与 [Unown Font](https://www.curseforge.com/minecraft/mc-mods/unown-font) 等模组做法一致。

#### 1. 字体资源定义

`assets/pockethomestead/font/smooth_ui.json`：

```json
{
  "providers": [
    {
      "type": "ttf",
      "file": "pockethomestead:notosanssc-regular.ttf",
      "size": 11.0,
      "oversample": 2.0
    }
  ]
}
```

- TTF 文件放在 `assets/pockethomestead/font/` 目录（Minecraft TTF provider 从此目录查找）
- 文件名 **必须全小写**（`ResourceLocation` 只允许 `[a-z0-9/._-]`）
- `file` 字段需包含 `.ttf` 扩展名

#### 2. Theme 包装方法

```java
// 字体 ID
public static final ResourceLocation SMOOTH_FONT_ID =
        ResourceLocation.fromNamespaceAndPath("pockethomestead", "smooth_ui");

// 将字符串包装为使用自定义字体的 Component
public static Component styled(String text) {
    return Component.literal(text).withStyle(s -> s.withFont(SMOOTH_FONT_ID));
}

// 测量文本宽度
public static int styledWidth(Font font, String text) {
    return font.width(styled(text));
}
```

#### 3. 绘制调用

所有文本绘制统一走 `Theme` 的工具方法，内部自动使用自定义字体：

```java
// 不要直接写：
g.drawString(font, "文本", x, y, color, false);

// 改为：
g.drawString(font, Theme.styled("文本"), x, y, color, false);
// 或使用 Theme 封装方法：
Theme.text(g, font, "文本", x, y, color);
```

---

## 二、CSS 级抗锯齿光滑圆角

### 问题

Minecraft 的 `GuiGraphics.fill()` 使用 `POSITION_COLOR` 无纹理着色器，只能绘制轴对齐像素矩形。原 `fillRound` 通过圆方程逐行计算内缩绘制阶梯状圆角，缺乏子像素抗锯齿。

### 方案：运行时纹理生成 + 9-slice 绘制

#### 核心思路

1. 运行时用 `NativeImage` 生成一张 **40×40 白色圆角纹理**（10px 圆角半径，含 1px 抗锯齿过渡带）
2. 上传到 OpenGL 纹理（`GL_LINEAR` 过滤保证缩放时平滑采样）
3. 绘制时用 **9-slice（九宫格）** 方式将纹理映射到任意尺寸的目标矩形

#### 9-slice 布局

```
┌──────┬──────────┬──────┐
│  TL  │   top    │  TR  │  ← 四角：固定圆角纹理区域
├──────┼──────────┼──────┤
│ left │  center  │right │  ← 四边+中心：纯色区域，拉伸填充
├──────┼──────────┼──────┤
│  BL  │  bottom  │  BR  │
└──────┴──────────┴──────┘
```

每个切片用 `BufferBuilder` 提交带纹理坐标的着色矩形，9 个切片组成完整圆角矩形。

#### 纹理生成算法

```java
// 计算某像素是否在圆角区域内（含 1px 抗锯齿带）
private static float roundedAlpha(int px, int py) {
    // 判断像素属于哪个角（或内部）
    // 计算像素中心到角圆心的距离
    float dist = sqrt((px+0.5-cx)² + (py+0.5-cy)²);
    if (dist <= r - 0.5) return 1f;   // 完全在内
    if (dist >= r + 0.5) return 0f;   // 完全在外
    return r + 0.5f - dist;            // 过渡带线性插值
}
```

#### 绘制流程

```java
ensureTexture();  // 首次调用时生成纹理（懒加载）

// 设置 OpenGL 状态
RenderSystem.setShaderTexture(0, roundTexId);
RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
RenderSystem.enableBlend();
RenderSystem.defaultBlendFunc();

// 9-slice 绘制（染色 + 纹理坐标映射）
// ...

BufferUploader.drawWithShader(buf.buildOrThrow());
RenderSystem.disableBlend();
```

#### 颜色支持

纹理本身是白色的，颜色通过顶点着色器 `setColor(r, g, b, a)` 传入，因此 `fillRound` 接口不变，仍接受任意 ARGB 颜色。

#### 对比效果

| | 旧方案（像素级 fill） | 新方案（AA 纹理） |
|---|---|---|
| 圆角边缘 | 阶梯状锯齿 | CSS 级光滑过渡 |
| 性能 | 逐行 N 次 fill 调用 | 单次纹理绘制 |
| 颜色 | ARGB | ARGB（不变） |
| 圆角半径 | 任意 r | 任意 r（纹理缩放适配） |

---

## 三、避坑记录

1. **字体文件名必须全小写** — `ResourceLocation` 不允许大写字母
2. **TTF 文件必须放在 `font/` 目录** — Minecraft 的 TTF provider 从 `assets/<namespace>/font/` 查找，**不是** `textures/font/`
3. **`NativeImage.setPixelRGBA(x, y, abgr)`** — 颜色格式为 ABGR（`(a<<24)|(b<<16)|(g<<8)|r`）
4. **`NativeImage.upload()` 方法签名因版本而异** — 1.21.1 使用的是 4 参数版本 `upload(level, xOffset, yOffset, mipmap)`
5. **`GuiGraphics.blit()` 绑定的是 GUI 图集** — 无法直接用于自定义运行时纹理，需用 `BufferUploader` + `RenderSystem` 手动绘制
6. **`TextureUtil.prepareImage()` 需在 `GlStateManager._bindTexture()` 之后调用**
