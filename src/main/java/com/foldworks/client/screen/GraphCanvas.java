package com.foldworks.client.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.foldworks.client.ui.Theme;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;

/**
 * 传输图画布工具：Bezier 曲线绘制、流粒子动画、颜色混合等纯渲染工具。
 * zoom 值通过 {@link #setZoom} 设置，影响线宽和 UI 缩放。
 */
public final class GraphCanvas {
    private double zoom = 1.0;

    public void setZoom(double zoom) { this.zoom = zoom; }
    public double zoom() { return zoom; }

    public float scaledLineWidth(float base) {
        return Math.max(0.35f, Math.min(5.0f, base * (float) Math.max(0.12, zoom)));
    }

    public float canvasUiScale() {
        return (float) Math.max(0.42, Math.min(1.25, zoom));
    }

    public void drawBezier(GuiGraphics g, double x0, double y0, double x3, double y3, float laneOffset, int color, boolean thick) {
        double dx = Math.max(42, Math.abs(x3 - x0) * 0.46);
        double x1 = x0 + dx;
        double x2 = x3 - dx;
        double y1 = y0 + laneOffset;
        double y2 = y3 + laneOffset;
        double curveSpan = Math.hypot(x3 - x0, y3 - y0) + Math.abs(laneOffset) * 1.6;
        int segments = Math.max(72, Math.min(260, (int) Math.ceil(curveSpan / 3.0)));
        float coreWidth = scaledLineWidth(thick ? 3.1f : 2.25f);
        drawBezierStroke(g, x0, y0, x1, y1, x2, y2, x3, y3, segments, coreWidth, color);
        int cap = Math.max(2, Math.round(coreWidth + 0.8f));
        Theme.fillRound(g, (int) Math.round(x0) - cap / 2, (int) Math.round(y0) - cap / 2, cap, cap, Math.max(1, cap / 2), color);
        Theme.fillRound(g, (int) Math.round(x3) - cap / 2, (int) Math.round(y3) - cap / 2, cap, cap, Math.max(1, cap / 2), color);
    }

    public void drawBezierStroke(GuiGraphics g, double x0, double y0, double x1, double y1, double x2, double y2,
                                  double x3, double y3, int segments, float width, int color) {
        Matrix4f matrix = g.pose().last().pose();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        Tesselator tess = Tesselator.getInstance();
        BufferBuilder buf = tess.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        double px = x0, py = y0;
        for (int i = 1; i <= segments; i++) {
            double t = i / (double) segments;
            double x = cubic(x0, x1, x2, x3, t);
            double y = cubic(y0, y1, y2, y3, t);
            addStrokeSegment(buf, matrix, (float) px, (float) py, (float) x, (float) y, width, color);
            px = x;
            py = y;
        }
        BufferUploader.drawWithShader(buf.buildOrThrow());
        RenderSystem.disableBlend();
    }

    public void drawFlowParticles(GuiGraphics g, double x0, double y0, double x1, double y1, double x2, double y2,
                                   double x3, double y3, int color, int rate, float partialTick) {
        int visualRate = Math.max(0, rate);
        int count = clamp(2 + visualRate / 1400, 2, 5);
        double speed = 0.28 + Math.min(visualRate, 12000) / 24000.0;
        double time = (System.currentTimeMillis() % 120000L) / 1000.0 + partialTick / 20.0;
        int glowColor = withAlpha(color, 0x55);
        int coreColor = mixColor(color, 0xFFFFFFFF, 0.42f);
        int dot = Math.max(2, Math.round(scaledLineWidth(3.0f)));
        int glow = dot + 4;
        for (int i = 0; i < count; i++) {
            double t = (time * speed + i / (double) count) % 1.0;
            double px = cubic(x0, x1, x2, x3, t);
            double py = cubic(y0, y1, y2, y3, t);
            Theme.fillRound(g, (int) Math.round(px) - glow / 2, (int) Math.round(py) - glow / 2, glow, glow, Math.max(1, glow / 2), glowColor);
            Theme.fillRound(g, (int) Math.round(px) - dot / 2, (int) Math.round(py) - dot / 2, dot, dot, Math.max(1, dot / 2), coreColor);
        }
    }

    public static double cubic(double a, double b, double c, double d, double t) {
        double u = 1.0 - t;
        return u * u * u * a + 3 * u * u * t * b + 3 * u * t * t * c + t * t * t * d;
    }

    public static int withAlpha(int color, int alpha) {
        return (clamp(alpha, 0, 255) << 24) | (color & 0x00FFFFFF);
    }

    public static int mixColor(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = Math.round(ar + (br - ar) * t);
        int g = Math.round(ag + (bg - ag) * t);
        int bl = Math.round(ab + (bb - ab) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    public static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void addStrokeSegment(BufferBuilder buf, Matrix4f matrix, float x0, float y0, float x1, float y1, float width, int color) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 0.001f) return;
        float nx = -dy / len * width * 0.5f;
        float ny = dx / len * width * 0.5f;
        vertex(buf, matrix, x0 + nx, y0 + ny, color);
        vertex(buf, matrix, x1 + nx, y1 + ny, color);
        vertex(buf, matrix, x1 - nx, y1 - ny, color);
        vertex(buf, matrix, x0 - nx, y0 - ny, color);
    }

    private void vertex(BufferBuilder buf, Matrix4f matrix, float x, float y, int color) {
        float a = ((color >>> 24) & 0xFF) / 255f;
        float r = ((color >> 16) & 0xFF) / 255f;
        float gr = ((color >> 8) & 0xFF) / 255f;
        float b = (color & 0xFF) / 255f;
        buf.addVertex(matrix, x, y, 0).setColor(r, gr, b, a);
    }
}
