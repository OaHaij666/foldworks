package com.pockethomestead.client.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.pockethomestead.blockentity.RelativeSide;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 软件 3D 立方体投影渲染器：旋转矩阵 + 多边形裁剪 + painter's algorithm 排序。
 * 投影结果按 (yaw, pitch, cx, cy, scale) 缓存，避免每帧重算。
 */
public final class FaceCubeRenderer {
    public record ProjectedFace(RelativeSide side, double[] xs, double[] ys, double cx, double cy, double depth, double normalDepth) {}

    private double cachedYaw = Double.NaN;
    private double cachedPitch = Double.NaN;
    private int cachedCx = Integer.MIN_VALUE;
    private int cachedCy = Integer.MIN_VALUE;
    private int cachedScale = Integer.MIN_VALUE;
    private boolean cachedVisibleOnly;
    private List<ProjectedFace> cachedFaces;

    public List<ProjectedFace> projectedFaces(double yaw, double pitch, int cx, int cy, int scale, boolean visibleOnly) {
        if (cachedFaces != null
                && cachedYaw == yaw
                && cachedPitch == pitch
                && cachedCx == cx
                && cachedCy == cy
                && cachedScale == scale
                && cachedVisibleOnly == visibleOnly) {
            return cachedFaces;
        }
        List<ProjectedFace> faces = new ArrayList<>();
        for (RelativeSide side : RelativeSide.values()) {
            double[][] vertices = sideVertices(side);
            double[] xs = new double[4];
            double[] ys = new double[4];
            double depth = 0.0;
            double centerX = 0.0;
            double centerY = 0.0;
            for (int i = 0; i < 4; i++) {
                double[] p = rotate(vertices[i][0], vertices[i][1], vertices[i][2], yaw, pitch);
                xs[i] = cx + p[0] * scale;
                ys[i] = cy - p[1] * scale;
                depth += p[2];
                centerX += xs[i];
                centerY += ys[i];
            }
            double[] normal = sideNormal(side);
            double normalDepth = rotate(normal[0], normal[1], normal[2], yaw, pitch)[2];
            if (!visibleOnly || normalDepth > 0.015) {
                faces.add(new ProjectedFace(side, xs, ys, centerX / 4.0, centerY / 4.0, depth / 4.0, normalDepth));
            }
        }
        faces.sort(Comparator.comparingDouble(ProjectedFace::depth));
        cachedFaces = faces;
        cachedYaw = yaw;
        cachedPitch = pitch;
        cachedCx = cx;
        cachedCy = cy;
        cachedScale = scale;
        cachedVisibleOnly = visibleOnly;
        return faces;
    }

    public ProjectedFace findProjectedFace(List<ProjectedFace> faces, RelativeSide side) {
        for (ProjectedFace face : faces) if (face.side() == side) return face;
        return null;
    }

    public RelativeSide faceAt(double mx, double my, int cx, int cy, int scale, double yaw, double pitch) {
        List<ProjectedFace> faces = projectedFaces(yaw, pitch, cx, cy, scale, true);
        for (int i = faces.size() - 1; i >= 0; i--) {
            ProjectedFace face = faces.get(i);
            if (pointInPolygon(mx, my, face.xs(), face.ys())) return face.side();
        }
        return null;
    }

    public void fillQuad(GuiGraphics g, double[] xs, double[] ys, int color) {
        Matrix4f matrix = g.pose().last().pose();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i < 4; i++) vertex(buf, matrix, (float) xs[i], (float) ys[i], color);
        BufferUploader.drawWithShader(buf.buildOrThrow());
        RenderSystem.disableBlend();
    }

    public void drawTexturedQuad(GuiGraphics g, double[] xs, double[] ys, ResourceLocation texture, int color) {
        drawTexturedQuad(g, xs, ys, texture, color, 0.0f, 0.0f, 1.0f, 1.0f);
    }

    public void drawTexturedQuad(GuiGraphics g, double[] xs, double[] ys, ResourceLocation texture, int color, float u0, float v0, float u1, float v1) {
        Matrix4f matrix = g.pose().last().pose();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, texture);
        BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        vertexTex(buf, matrix, (float) xs[0], (float) ys[0], u0, v1, color);
        vertexTex(buf, matrix, (float) xs[1], (float) ys[1], u1, v1, color);
        vertexTex(buf, matrix, (float) xs[2], (float) ys[2], u1, v0, color);
        vertexTex(buf, matrix, (float) xs[3], (float) ys[3], u0, v0, color);
        BufferUploader.drawWithShader(buf.buildOrThrow());
        RenderSystem.disableBlend();
    }

    public static double[] inset(double[] values, double amount) {
        double center = 0.0;
        for (double value : values) center += value;
        center /= values.length;
        double[] out = new double[values.length];
        for (int i = 0; i < values.length; i++) out[i] = center + (values[i] - center) * (1.0 - amount);
        return out;
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

    private double[] rotate(double x, double y, double z, double yawDeg, double pitchDeg) {
        double yaw = Math.toRadians(yawDeg);
        double pitch = Math.toRadians(pitchDeg);
        double cy = Math.cos(yaw);
        double sy = Math.sin(yaw);
        double cp = Math.cos(pitch);
        double sp = Math.sin(pitch);
        double rx = x * cy + z * sy;
        double rz = -x * sy + z * cy;
        double ry = y * cp - rz * sp;
        double rz2 = y * sp + rz * cp;
        return new double[]{rx, ry, rz2};
    }

    private double[][] sideVertices(RelativeSide side) {
        return switch (side) {
            case FRONT -> new double[][]{{-1, -1, 1}, {1, -1, 1}, {1, 1, 1}, {-1, 1, 1}};
            case BACK -> new double[][]{{1, -1, -1}, {-1, -1, -1}, {-1, 1, -1}, {1, 1, -1}};
            case LEFT -> new double[][]{{-1, -1, -1}, {-1, -1, 1}, {-1, 1, 1}, {-1, 1, -1}};
            case RIGHT -> new double[][]{{1, -1, 1}, {1, -1, -1}, {1, 1, -1}, {1, 1, 1}};
            case UP -> new double[][]{{-1, 1, 1}, {1, 1, 1}, {1, 1, -1}, {-1, 1, -1}};
            case DOWN -> new double[][]{{-1, -1, -1}, {1, -1, -1}, {1, -1, 1}, {-1, -1, 1}};
        };
    }

    private double[] sideNormal(RelativeSide side) {
        return switch (side) {
            case FRONT -> new double[]{0, 0, 1};
            case BACK -> new double[]{0, 0, -1};
            case LEFT -> new double[]{-1, 0, 0};
            case RIGHT -> new double[]{1, 0, 0};
            case UP -> new double[]{0, 1, 0};
            case DOWN -> new double[]{0, -1, 0};
        };
    }

    private boolean pointInPolygon(double px, double py, double[] xs, double[] ys) {
        boolean inside = false;
        for (int i = 0, j = xs.length - 1; i < xs.length; j = i++) {
            boolean cross = (ys[i] > py) != (ys[j] > py)
                    && px < (xs[j] - xs[i]) * (py - ys[i]) / (ys[j] - ys[i] + 0.00001) + xs[i];
            if (cross) inside = !inside;
        }
        return inside;
    }

    private void vertex(BufferBuilder buf, Matrix4f matrix, float x, float y, int color) {
        float a = ((color >>> 24) & 0xFF) / 255f;
        float r = ((color >> 16) & 0xFF) / 255f;
        float gr = ((color >> 8) & 0xFF) / 255f;
        float b = (color & 0xFF) / 255f;
        buf.addVertex(matrix, x, y, 0).setColor(r, gr, b, a);
    }

    private void vertexTex(BufferBuilder buf, Matrix4f matrix, float x, float y, float u, float v, int color) {
        float a = ((color >>> 24) & 0xFF) / 255f;
        float r = ((color >> 16) & 0xFF) / 255f;
        float gr = ((color >> 8) & 0xFF) / 255f;
        float b = (color & 0xFF) / 255f;
        buf.addVertex(matrix, x, y, 0).setUv(u, v).setColor(r, gr, b, a);
    }
}
