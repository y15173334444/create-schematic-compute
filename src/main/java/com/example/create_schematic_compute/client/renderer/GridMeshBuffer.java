package com.example.create_schematic_compute.client.renderer;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.renderer.GameRenderer;

/**
 * Pre-baked VBO for the editor grid. Rebuilt only when zoom, screen size,
 * or camera offset changes enough to matter. Eliminates 140+ individual
 * g.fill() calls per frame.
 *
 * All rendering goes through Mojang's VertexBuffer / BufferBuilder API —
 * fully Vulkan-safe, no raw OpenGL calls.
 */
public class GridMeshBuffer implements AutoCloseable {

    private VertexBuffer vertexBuffer;
    private boolean needsRebuild = true;

    private float lastZoom = Float.NaN;
    private int lastWidth = -1;
    private int lastHeight = -1;
    private float lastCamX = Float.NaN;
    private float lastCamY = Float.NaN;

    private final java.util.function.IntSupplier gridColorSupplier;
    private static final float GRID_SPACING = 30f;
    private static final float REBUILD_OFFSET_THRESHOLD = 1.0f; // pixels

    /**
     * @param gridColorSupplier provides the grid line color (e.g. NodeRenderer::CGL)
     */
    public GridMeshBuffer(java.util.function.IntSupplier gridColorSupplier) {
        this.gridColorSupplier = gridColorSupplier;
    }

    public void markDirty() {
        needsRebuild = true;
    }

    /**
     * Conditionally rebuild the grid mesh. Returns true if rebuilt.
     * Call every frame before draw().
     */
    public boolean rebuildIfNeeded(float camX, float camY, float zoom, int width, int height) {
        if (width <= 0 || height <= 0) return false;

        float spacing = GRID_SPACING * zoom;
        if (spacing < 1f) spacing = 1f;

        float ox = (camX * zoom) % spacing;
        float oy = (camY * zoom) % spacing;

        boolean zoomChanged = Float.isNaN(lastZoom) || Math.abs(zoom - lastZoom) > 1e-4f;
        boolean sizeChanged = width != lastWidth || height != lastHeight;

        float lastSpacing = GRID_SPACING * lastZoom;
        if (lastSpacing < 1f) lastSpacing = 1f;
        float lastOx = Float.isNaN(lastCamX) ? -999f : (lastCamX * lastZoom) % lastSpacing;
        float lastOy = Float.isNaN(lastCamY) ? -999f : (lastCamY * lastZoom) % lastSpacing;
        boolean offsetChanged = Math.abs(ox - lastOx) > REBUILD_OFFSET_THRESHOLD
                              || Math.abs(oy - lastOy) > REBUILD_OFFSET_THRESHOLD;

        if (!needsRebuild && !zoomChanged && !sizeChanged && !offsetChanged) {
            return false;
        }

        lastZoom = zoom;
        lastWidth = width;
        lastHeight = height;
        lastCamX = camX;
        lastCamY = camY;
        needsRebuild = false;

        int numVertical = (int) Math.ceil(width / spacing) + 2;
        int numHorizontal = (int) Math.ceil(height / spacing) + 2;
        int totalQuads = (numVertical * 2) + (numHorizontal * 2); // pos + neg for each
        int totalVertices = totalQuads * 4;
        int vertexSize = DefaultVertexFormat.POSITION_COLOR.getVertexSize(); // 16 bytes

        var bbBuilder = new ByteBufferBuilder(totalVertices * vertexSize);
        var bufBuilder = new BufferBuilder(bbBuilder, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        int gridColor = gridColorSupplier.getAsInt();

        float startX = width / 2f + ox;
        float startY = height / 2f + oy;
        float w = (float) width, h = (float) height;

        // Vertical lines — positive direction
        for (int i = 0; i < numVertical; i++) {
            float x = startX + i * spacing;
            if (x < -2 || x > w + 2) continue;
            float x1 = Math.round(x);
            float x2 = x1 + 1f;
            bufBuilder.addVertex(x1, 0f, 0f).setColor(gridColor);
            bufBuilder.addVertex(x2, 0f, 0f).setColor(gridColor);
            bufBuilder.addVertex(x2, h, 0f).setColor(gridColor);
            bufBuilder.addVertex(x1, h, 0f).setColor(gridColor);
        }
        // Vertical lines — negative direction
        for (int i = 1; i < numVertical; i++) {
            float x = startX - i * spacing;
            if (x < -2 || x > w + 2) continue;
            float x1 = Math.round(x);
            float x2 = x1 + 1f;
            bufBuilder.addVertex(x1, 0f, 0f).setColor(gridColor);
            bufBuilder.addVertex(x2, 0f, 0f).setColor(gridColor);
            bufBuilder.addVertex(x2, h, 0f).setColor(gridColor);
            bufBuilder.addVertex(x1, h, 0f).setColor(gridColor);
        }
        // Horizontal lines — positive direction
        for (int i = 0; i < numHorizontal; i++) {
            float y = startY + i * spacing;
            if (y < -2 || y > h + 2) continue;
            float y1 = Math.round(y);
            float y2 = y1 + 1f;
            bufBuilder.addVertex(0f, y1, 0f).setColor(gridColor);
            bufBuilder.addVertex(w, y1, 0f).setColor(gridColor);
            bufBuilder.addVertex(w, y2, 0f).setColor(gridColor);
            bufBuilder.addVertex(0f, y2, 0f).setColor(gridColor);
        }
        // Horizontal lines — negative direction
        for (int i = 1; i < numHorizontal; i++) {
            float y = startY - i * spacing;
            if (y < -2 || y > h + 2) continue;
            float y1 = Math.round(y);
            float y2 = y1 + 1f;
            bufBuilder.addVertex(0f, y1, 0f).setColor(gridColor);
            bufBuilder.addVertex(w, y1, 0f).setColor(gridColor);
            bufBuilder.addVertex(w, y2, 0f).setColor(gridColor);
            bufBuilder.addVertex(0f, y2, 0f).setColor(gridColor);
        }

        MeshData mesh = bufBuilder.buildOrThrow();

        if (vertexBuffer != null) {
            vertexBuffer.close();
        }
        vertexBuffer = new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
        vertexBuffer.upload(mesh);
        // mesh is closed by upload()

        return true;
    }

    /** Draw the pre-baked grid. Must be called after rebuildIfNeeded has been called at least once. */
    public void draw() {
        if (vertexBuffer == null || vertexBuffer.isInvalid()) return;

        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        vertexBuffer.bind();
        vertexBuffer.drawWithShader(
            RenderSystem.getModelViewMatrix(),
            RenderSystem.getProjectionMatrix(),
            RenderSystem.getShader()
        );
        VertexBuffer.unbind();

        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    @Override
    public void close() {
        if (vertexBuffer != null) {
            vertexBuffer.close();
            vertexBuffer = null;
        }
    }
}
