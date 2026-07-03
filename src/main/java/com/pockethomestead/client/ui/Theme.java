package com.pockethomestead.client.ui;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

/**
 * 口袋家园 UI 主题：淡蓝 + 白配色，配套纯代码绘制工具（圆角矩形 / 描边 / 柔和阴影 / 文本）。
 * 圆角矩形通过运行时生成的抗锯齿纹理实现类似 CSS border-radius 的光滑效果。
 */
public final class Theme {
    private Theme() {}

    // ---------------------------------------------------------------------
    // 抗锯齿圆角纹理（运行时生成）
    // ---------------------------------------------------------------------

    /** 纹理中圆角半径（像素），更高分辨率用于降低圆角/按钮边缘锯齿。 */
    private static final int TEX_RADIUS = 24;
    private static final int TEX_SIZE = TEX_RADIUS * 4;
    private static int roundTexId = -1;

    private static volatile boolean textureReady = false;

    private static void ensureTexture() {
        if (textureReady) return;
        synchronized (Theme.class) {
            if (textureReady) return;
            int texId = TextureUtil.generateTextureId();

            NativeImage image = new NativeImage(TEX_SIZE, TEX_SIZE, false);
            for (int py = 0; py < TEX_SIZE; py++) {
                for (int px = 0; px < TEX_SIZE; px++) {
                    int a = Math.round(roundedAlpha(px, py) * 255);
                    image.setPixelRGBA(px, py, (a << 24) | 0x00FFFFFF);
                }
            }

            GlStateManager._bindTexture(texId);
            GlStateManager._texParameter(
                    org.lwjgl.opengl.GL11.GL_TEXTURE_2D,
                    org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER,
                    org.lwjgl.opengl.GL11.GL_LINEAR);
            GlStateManager._texParameter(
                    org.lwjgl.opengl.GL11.GL_TEXTURE_2D,
                    org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER,
                    org.lwjgl.opengl.GL11.GL_LINEAR);
            TextureUtil.prepareImage(texId, TEX_SIZE, TEX_SIZE);
            image.upload(0, 0, 0, false);
            image.close();

            roundTexId = texId;
            textureReady = true;
        }
    }

    /** 计算纹理中某像素的圆角 alpha 值（0=透明, 1=不透明），含约 2px 抗锯齿过渡带 */
    private static float roundedAlpha(int px, int py) {
        int w = TEX_SIZE, h = TEX_SIZE, r = TEX_RADIUS;
        int cx = -1, cy = -1;
        if (px < r && py < r)          { cx = r; cy = r; }
        else if (px >= w - r && py < r)  { cx = w - r; cy = r; }
        else if (px < r && py >= h - r)  { cx = r; cy = h - r; }
        else if (px >= w - r && py >= h - r) { cx = w - r; cy = h - r; }
        if (cx == -1) return 1f;

        float dx = px + 0.5f - cx;
        float dy = py + 0.5f - cy;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        float feather = 1.35f;
        if (dist <= r - feather) return 1f;
        if (dist >= r + feather) return 0f;
        float t = (r + feather - dist) / (feather * 2f);
        return t * t * (3f - 2f * t);
    }

    // ---------------------------------------------------------------------
    // 自定义字体
    // ---------------------------------------------------------------------

    /** 自定义平滑字体 ID，对应 assets/pockethomestead/font/smooth_ui.json */
    public static final ResourceLocation SMOOTH_FONT_ID =
            ResourceLocation.fromNamespaceAndPath("pockethomestead", "smooth_ui");

    /** 将字符串包装为使用自定义字体的 Component，用于 drawString 的 Component 重载 */
    public static Component styled(String text) {
        return Component.literal(text).withStyle(s -> s.withFont(SMOOTH_FONT_ID));
    }

    /** 使用自定义字体测量文本宽度 */
    public static int styledWidth(Font font, String text) {
        return font.width(styled(text));
    }

    // ===== 背景 / 表面 =====
    public static final int SCRIM        = 0x600F1720; // 更淡的半透明背景遮罩
    public static final int SURFACE      = 0xFFFAFDFF; // 柔和白（微蓝）
    public static final int SURFACE_ALT  = 0xFFF0F7FD; // 淡蓝表面
    public static final int SURFACE_SUNK = 0xFFE5F0FA; // 凹陷 / 输入框底
    public static final int SIDEBAR      = 0xFFF3F8FD; // 侧边栏底色
    public static final int HEADER       = 0xFFF8FBFF; // 头部底色

    // ===== 主色（淡蓝 - 更柔和） =====
    public static final int PRIMARY        = 0xFF7CB3E8;
    public static final int PRIMARY_HOVER  = 0xFF9AC9F5;
    public static final int PRIMARY_PRESS  = 0xFF5A9DD8;
    public static final int PRIMARY_SOFT   = 0xFFE8F4FC; // 选中项淡蓝底
    public static final int PRIMARY_SOFT_H = 0xFFD9EBF7;

    // ===== 中性 / 边框（更柔和） =====
    public static final int BORDER       = 0xFFD4E4F5;
    public static final int BORDER_STRONG= 0xFFB5D0EB;
    public static final int DIVIDER      = 0xFFEDF4FA;

    // ===== 文本 =====
    public static final int TEXT         = 0xFF2C3E50;
    public static final int TEXT_MUTED   = 0xFF7A8BA0;
    public static final int TEXT_FAINT   = 0xFFA8B8C8;
    public static final int TEXT_ON_PRIM = 0xFFFFFFFF;

    // ===== 语义色（更柔和） =====
    public static final int DANGER       = 0xFFF08080;
    public static final int DANGER_HOVER = 0xFFF59595;
    public static final int DANGER_SOFT  = 0xFFFDE8E8;
    public static final int SUCCESS      = 0xFF6BCFA0;
    public static final int SUCCESS_HOVER= 0xFF85DBB0;

    // ===== 阴影（更柔和） =====
    public static final int SHADOW       = 0x1A000000;

    // ===== 间距 =====
    public static final int PAD    = 12;
    public static final int GAP    = 8;
    public static final int RADIUS = 5;

    // ---------------------------------------------------------------------
    // 圆角矩形
    // ---------------------------------------------------------------------

    /** 绘制圆角实心矩形（抗锯齿纹理 + 9-slice）。
     *  半径会被夹紧到 [0, min(w,h)/2]。 */
    public static void fillRound(GuiGraphics g, int x, int y, int w, int h, int radius, int color) {
        if (w <= 0 || h <= 0) return;
        int r = Math.max(0, Math.min(radius, Math.min(w, h) / 2));
        if (r == 0) {
            g.fill(x, y, x + w, y + h, color);
            return;
        }
        ensureTexture();

        float a = ((color >> 24) & 0xFF) / 255f;
        float red = ((color >> 16) & 0xFF) / 255f;
        float green = ((color >> 8) & 0xFF) / 255f;
        float blue = (color & 0xFF) / 255f;

        RenderSystem.setShaderTexture(0, roundTexId);
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        Matrix4f matrix = g.pose().last().pose();
        Tesselator tess = Tesselator.getInstance();
        BufferBuilder buf = tess.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);

        float texR = (float) TEX_RADIUS / TEX_SIZE; // 纹理中圆角区域比例

        // 9-slice: 四角 + 四边 + 中心
        blit9(buf, matrix, x,         y,         x + r,     y + r,     0,      0,      texR,   texR,   red, green, blue, a);
        blit9(buf, matrix, x + r,     y,         x + w - r, y + r,     texR,   0,      1-texR, texR,   red, green, blue, a);
        blit9(buf, matrix, x + w - r, y,         x + w,     y + r,     1-texR, 0,      1,      texR,   red, green, blue, a);
        blit9(buf, matrix, x,         y + r,     x + r,     y + h - r, 0,      texR,   texR,   1-texR, red, green, blue, a);
        blit9(buf, matrix, x + r,     y + r,     x + w - r, y + h - r, texR,   texR,   1-texR, 1-texR, red, green, blue, a);
        blit9(buf, matrix, x + w - r, y + r,     x + w,     y + h - r, 1-texR, texR,   1,      1-texR, red, green, blue, a);
        blit9(buf, matrix, x,         y + h - r, x + r,     y + h,     0,      1-texR, texR,   1,      red, green, blue, a);
        blit9(buf, matrix, x + r,     y + h - r, x + w - r, y + h,     texR,   1-texR, 1-texR, 1,      red, green, blue, a);
        blit9(buf, matrix, x + w - r, y + h - r, x + w,     y + h,     1-texR, 1-texR, 1,      1,      red, green, blue, a);

        BufferUploader.drawWithShader(buf.buildOrThrow());

        RenderSystem.disableBlend();
    }

    /** 向 BufferBuilder 添加一个带纹理坐标的着色矩形 */
    private static void blit9(BufferBuilder buf, Matrix4f matrix,
                              int x0, int y0, int x1, int y1,
                              float u0, float v0, float u1, float v1,
                              float r, float g, float b, float a) {
        buf.addVertex(matrix, x0, y1, 0).setUv(u0, v1).setColor(r, g, b, a);
        buf.addVertex(matrix, x1, y1, 0).setUv(u1, v1).setColor(r, g, b, a);
        buf.addVertex(matrix, x1, y0, 0).setUv(u1, v0).setColor(r, g, b, a);
        buf.addVertex(matrix, x0, y0, 0).setUv(u0, v0).setColor(r, g, b, a);
    }

    /** 圆角描边矩形：外层边框色 + 内层填充色，形成 1px 描边。 */
    public static void panel(GuiGraphics g, int x, int y, int w, int h, int radius, int fill, int border) {
        fillRound(g, x, y, w, h, radius, border);
        fillRound(g, x + 1, y + 1, w - 2, h - 2, Math.max(0, radius - 1), fill);
    }

    /** 仅描边（不填充内部，用于高亮选中态外圈）。绘制4条1px边框条近似圆角描边。 */
    public static void outlineRound(GuiGraphics g, int x, int y, int w, int h, int radius, int border) {
        if (w <= 0 || h <= 0) return;
        int r = Math.max(0, Math.min(radius, Math.min(w, h) / 2));
        // 上边
        for (int i = 0; i < r; i++) {
            int dx = r - (int) Math.ceil(Math.sqrt(r * r - (r - 1 - i) * (r - 1 - i)));
            g.fill(x + dx, y + i, x + w - dx, y + i + 1, border);
        }
        g.fill(x + r, y, x + w - r, y + 1, border);
        // 下边
        for (int i = 0; i < r; i++) {
            int dx = r - (int) Math.ceil(Math.sqrt(r * r - (r - 1 - i) * (r - 1 - i)));
            g.fill(x + dx, y + h - 1 - i, x + w - dx, y + h - i, border);
        }
        g.fill(x + r, y + h - 1, x + w - r, y + h, border);
        // 左边
        for (int i = 0; i < r; i++) {
            int dy = r - (int) Math.ceil(Math.sqrt(r * r - (r - 1 - i) * (r - 1 - i)));
            g.fill(x + i, y + dy, x + i + 1, y + h - dy, border);
        }
        g.fill(x, y + r, x + 1, y + h - r, border);
        // 右边
        for (int i = 0; i < r; i++) {
            int dy = r - (int) Math.ceil(Math.sqrt(r * r - (r - 1 - i) * (r - 1 - i)));
            g.fill(x + w - 1 - i, y + dy, x + w - i, y + h - dy, border);
        }
        g.fill(x + w - 1, y + r, x + w, y + h - r, border);
    }

    /** 柔和投影：在目标矩形下方堆叠几层递减 alpha 的圆角矩形。 */
    public static void shadow(GuiGraphics g, int x, int y, int w, int h, int radius) {
        for (int i = 3; i >= 1; i--) {
            int a = 0x18 - i * 0x06;
            if (a <= 0) a = 0x04;
            int color = (a << 24);
            fillRound(g, x - i, y + i + 1, w + i * 2, h + i * 2, radius + i, color);
        }
    }

    // ---------------------------------------------------------------------
    // 文本
    // ---------------------------------------------------------------------

    public static void text(GuiGraphics g, Font font, String s, int x, int y, int color) {
        g.drawString(font, styled(s), x, y, color, false);
    }

    public static void textCentered(GuiGraphics g, Font font, String s, int cx, int y, int color) {
        g.drawString(font, styled(s), cx - styledWidth(font, s) / 2, y, color, false);
    }

    public static void textRight(GuiGraphics g, Font font, String s, int rx, int y, int color) {
        g.drawString(font, styled(s), rx - styledWidth(font, s), y, color, false);
    }

    /** 居中（含垂直居中）到给定矩形。 */
    public static void textInBox(GuiGraphics g, Font font, String s, int x, int y, int w, int h, int color) {
        g.drawString(font, styled(s), x + (w - styledWidth(font, s)) / 2, y + (h - font.lineHeight) / 2 + 1, color, false);
    }

    /** 截断到最大宽度，超出加省略号。 */
    public static String ellipsize(Font font, String s, int maxWidth) {
        if (maxWidth <= 0) return "";
        if (styledWidth(font, s) <= maxWidth) return s;
        String ell = "…";
        int ellW = styledWidth(font, ell);
        if (maxWidth <= ellW) return ell;
        StringBuilder sb = new StringBuilder();
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            int cw = styledWidth(font, String.valueOf(s.charAt(i)));
            if (w + cw + ellW > maxWidth) break;
            sb.append(s.charAt(i));
            w += cw;
        }
        return sb.append(ell).toString();
    }

    // ---------------------------------------------------------------------
    // 杂项
    // ---------------------------------------------------------------------

    public static void hLine(GuiGraphics g, int x, int y, int w, int color) {
        g.fill(x, y, x + w, y + 1, color);
    }

    public static void vLine(GuiGraphics g, int x, int y, int h, int color) {
        g.fill(x, y, x + 1, y + h, color);
    }

    public static void line(GuiGraphics g, float x1, float y1, float x2, float y2, float thickness, int color) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len <= 0.001f || thickness <= 0f) return;

        float nx = -dy / len * thickness / 2f;
        float ny = dx / len * thickness / 2f;
        float a = ((color >> 24) & 0xFF) / 255f;
        float red = ((color >> 16) & 0xFF) / 255f;
        float green = ((color >> 8) & 0xFF) / 255f;
        float blue = (color & 0xFF) / 255f;

        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        Matrix4f matrix = g.pose().last().pose();
        Tesselator tess = Tesselator.getInstance();
        BufferBuilder buf = tess.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        buf.addVertex(matrix, x1 + nx, y1 + ny, 0).setColor(red, green, blue, a);
        buf.addVertex(matrix, x2 + nx, y2 + ny, 0).setColor(red, green, blue, a);
        buf.addVertex(matrix, x2 - nx, y2 - ny, 0).setColor(red, green, blue, a);
        buf.addVertex(matrix, x1 - nx, y1 - ny, 0).setColor(red, green, blue, a);
        BufferUploader.drawWithShader(buf.buildOrThrow());

        RenderSystem.disableBlend();
    }

    public static void chevronDown(GuiGraphics g, int cx, int cy, int size, int color) {
        float half = size / 2f;
        float top = cy - size / 4f;
        float bottom = cy + size / 4f;
        float thick = Math.max(1.15f, size / 5f);
        line(g, cx - half, top, cx, bottom, thick, color);
        line(g, cx + half, top, cx, bottom, thick, color);
    }

    public static void chevronUp(GuiGraphics g, int cx, int cy, int size, int color) {
        float half = size / 2f;
        float top = cy - size / 4f;
        float bottom = cy + size / 4f;
        float thick = Math.max(1.15f, size / 5f);
        line(g, cx - half, bottom, cx, top, thick, color);
        line(g, cx + half, bottom, cx, top, thick, color);
    }

    public static void chevronRight(GuiGraphics g, int cx, int cy, int size, int color) {
        float left = cx - size / 4f;
        float right = cx + size / 4f;
        float half = size / 2f;
        float thick = Math.max(1.15f, size / 5f);
        line(g, left, cy - half, right, cy, thick, color);
        line(g, left, cy + half, right, cy, thick, color);
    }

    public static void chevronLeft(GuiGraphics g, int cx, int cy, int size, int color) {
        float left = cx - size / 4f;
        float right = cx + size / 4f;
        float half = size / 2f;
        float thick = Math.max(1.15f, size / 5f);
        line(g, right, cy - half, left, cy, thick, color);
        line(g, right, cy + half, left, cy, thick, color);
    }

    public static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /** 颜色按比例线性插值（用于 hover 过渡），t∈[0,1]。 */
    public static int lerpColor(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int aa = (a >>> 24), ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int ba = (b >>> 24), br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int ca = (int) (aa + (ba - aa) * t);
        int cr = (int) (ar + (br - ar) * t);
        int cg = (int) (ag + (bg - ag) * t);
        int cb = (int) (ab + (bb - ab) * t);
        return (ca << 24) | (cr << 16) | (cg << 8) | cb;
    }
}
