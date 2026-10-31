package io.github.y15173334444.create_schematic_compute.client.renderer;

import io.github.y15173334444.create_schematic_compute.graph.GraphNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 显示器/HUD 每帧分配治理批（known-open-issues 条目「显示器/HUD 每帧分配」，2026-10-31
 * 拍板「只修分配不碰恒渲染」）的回归锚：
 * <ul>
 *   <li>3D 半边 = 逐像素 mesh 烘焙（{@link MonitorClipMath#buildImageMesh}，纯函数直测）：
 *       透明跳过、顶点数、绕序、颜色归一化、cell 缩放。</li>
 *   <li>像素版本钩子：原位绘画路径（paintBrush/floodFill）必须递增
 *       {@code GraphNode.imagePixelsVersion}——mesh 缓存的 O(1) 新鲜度键。</li>
 *   <li>接线源锚：渲染器缓存键在位、HUD 复用缓冲在位（逐像素分配禁回潮）。</li>
 * </ul>
 * Regression anchors for the monitor/HUD per-frame allocation batch (the "allocation-only"
 * ruling): the 3D half's baked mesh builder (pure, tested directly), the pixel-version hook
 * (in-place painting must bump it — the mesh cache's O(1) freshness key), and wiring source
 * anchors (the renderer's cache keys and the HUD reused buffers in place).
 */
class MonitorImageMeshTest {

    @Test
    @DisplayName("透明像素跳过、顶点数 = 4×不透明像素×6 float / transparent skipped, count right")
    void meshSkipsTransparentAndCounts() {
        int[] pixels = {
            0xFF112233, 0x00000000,
            0x80445566, 0x00000000,
        }; // 2×2 画布，两个不透明像素 / 2×2 canvas, two opaque pixels
        float[] mesh = MonitorClipMath.buildImageMesh(pixels, 2, 2, 0.5f);
        assertEquals(2 * 4 * 6, mesh.length, "4 vertices × 6 floats per opaque pixel");
    }

    @Test
    @DisplayName("绕序与颜色归一化与原逐帧发射逐字节一致 / winding and colours match the old emission")
    void windingAndColours() {
        int[] pixels = {0xFF804020}; // 1×1，r=0x80 g=0x40 b=0x20 a=0xFF
        float cell = 0.25f;
        float[] mesh = MonitorClipMath.buildImageMesh(pixels, 1, 1, cell);
        // 顶点序：(x0,y0)(x1,y0)(x1,y1)(x0,y1)，x0=0, x1=cell, y0=0, y1=-cell
        // 逐顶点交错 {x, y, r, g, b, a}——与发射器的步长 6 读取一致（交错布局本身即锚：
        // 首版分组布局与发射器错位导致「炸花」，此锚防回潮）。
        // Interleaved {x, y, r, g, b, a} per vertex — matching the emitter's stride-6 read
        // (the interleaving itself is the anchor: the first draft's grouped layout desynced
        // from the emitter and exploded on screen).
        float r = 0x80 / 255f, g = 0x40 / 255f, b = 0x20 / 255f, a = 1f;
        float[] expect = {
            0f, 0f, r, g, b, a,
            cell, 0f, r, g, b, a,
            cell, -cell, r, g, b, a,
            0f, -cell, r, g, b, a,
        };
        assertEquals(expect.length, mesh.length);
        assertArrayEquals(expect, mesh, 1e-6f, "the baked stream must match the old emission exactly");
    }

    @Test
    @DisplayName("cell 缩放烘进本地坐标 / cell scaling baked into local coords")
    void cellScales() {
        int[] pixels = {0xFFFFFFFF, 0xFFFFFFFF};
        float[] a = MonitorClipMath.buildImageMesh(pixels, 2, 1, 0.03f);
        float[] b = MonitorClipMath.buildImageMesh(pixels, 2, 1, 0.06f);
        // 交错布局：首像素第二顶点的 x（下标 6）= x1 / interleaved: vertex 1's x (index 6) = x1
        assertEquals(0.03f, a[6], 1e-6f, "x1 at cell=0.03");
        assertEquals(0.06f, b[6], 1e-6f, "x1 at cell=0.06 (double scale doubles the local extents)");
    }

    @Test
    @DisplayName("空/畸形输入安全（null、零尺寸、长度超配）/ degenerate inputs are safe")
    void degenerateInputs() {
        assertEquals(0, MonitorClipMath.buildImageMesh(null, 4, 4, 0.03f).length);
        assertEquals(0, MonitorClipMath.buildImageMesh(new int[0], 4, 4, 0.03f).length);
        // 像素数组比画布长（旧档/竞态）：多出的部分不参与（渲染路径同规则）
        // A longer-than-canvas array (stale/racing peers): the excess is ignored (same rule
        // as the render path).
        int[] long_ = new int[5 * 5];
        java.util.Arrays.fill(long_, 0xFFFFFFFF);
        assertEquals(4 * 4 * 4 * 6, MonitorClipMath.buildImageMesh(long_, 4, 4, 0.1f).length);
    }

    @Test
    @DisplayName("原位绘画递增像素版本 / in-place painting bumps the pixel version")
    void kernelBumpsPixelVersion() {
        GraphNode n = new GraphNode(1, io.github.y15173334444.create_schematic_compute.graph.NodeType.IMAGE, 0, 0);
        n.imagePixels = new int[n.imageWidth * n.imageHeight];
        int v0 = n.imagePixelsVersion;
        io.github.y15173334444.create_schematic_compute.client.PixelEditorKernel.paintBrush(n, 2, 2, 0xFF112233, 1, 1f);
        assertEquals(v0 + 1, n.imagePixelsVersion, "paintBrush is an in-place write → must bump");
        io.github.y15173334444.create_schematic_compute.client.PixelEditorKernel.floodFill(n, 0, 0, 0xFF445566, 1f);
        assertEquals(v0 + 2, n.imagePixelsVersion, "floodFill is an in-place write → must bump");
    }

    // ── 接线源锚 / wiring source anchors ──

    @Test
    @DisplayName("接线源锚：渲染器缓存键 + HUD 复用缓冲在位 / cache keys and reused buffers wired")
    void wiringAnchors() throws Exception {
        String ber = Files.readString(Path.of(
            "src/main/java/io/github/y15173334444/create_schematic_compute/client/renderer/MonitorBlockEntityRenderer.java"));
        assertTrue(ber.contains("imageMeshCache"), "the renderer must keep the per-image mesh cache");
        assertTrue(ber.contains("mesh.pixelVersion != n.imagePixelsVersion"),
            "the cache validity must key on the pixel version (in-place painting)");
        assertTrue(ber.contains("mesh.pixels != pixels"),
            "the cache validity must key on the pixel array identity (op-applied ref swaps)");
        assertTrue(ber.contains("buildImageMesh"), "the mesh must be built by the pure helper");

        String hud = Files.readString(Path.of(
            "src/main/java/io/github/y15173334444/create_schematic_compute/client/renderer/HudRenderer.java"));
        assertFalse(hud.contains("float[] pxQuad = {"),
            "the per-pixel float[8] allocation must stay retired (the P1-4 headline term)");
        assertTrue(hud.contains("float[] pxQuad = scratchPxQuad;"),
            "the pixel quad must refill the reused buffer");
        assertTrue(hud.contains("float[] scratchMaskImg = new float[8];")
            && hud.contains("float[] scratchPxQuad = new float[8];"),
            "the two reusable buffers must be declared once per renderHud");

        String kernel = Files.readString(Path.of(
            "src/main/java/io/github/y15173334444/create_schematic_compute/client/PixelEditorKernel.java"));
        assertEquals(2, count(kernel, "imagePixelsVersion++"),
            "exactly the two in-place mutators (paintBrush/floodFill) bump the version");
    }

    private static int count(String s, String needle) {
        int c = 0, i = 0;
        while ((i = s.indexOf(needle, i)) >= 0) { c++; i += needle.length(); }
        return c;
    }
}
