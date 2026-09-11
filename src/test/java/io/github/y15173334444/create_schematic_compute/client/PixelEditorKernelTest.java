package io.github.y15173334444.create_schematic_compute.client;

import io.github.y15173334444.create_schematic_compute.graph.GraphNode;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 像素内核测试：笔刷 / 透明度混合 / 泛洪填充 / 直线 / 矩形 + 撤销重做往返
 * （docs/gui-decomposition-plan.md 步骤 4：把「手动回归」升级为「有断言」的第一批用例）。
 * Pixel kernel tests: brush / alpha blend / flood fill / line / rect plus undo-redo
 * round trips (roadmap step 4 — turning the manual regression into assertions).
 */
class PixelEditorKernelTest {

    private static GraphNode imageNode(int w, int h) {
        GraphNode n = new GraphNode(1, NodeType.IMAGE, 0, 0);
        n.imageWidth = w;
        n.imageHeight = h;
        n.imagePixels = new int[w * h];
        return n;
    }

    private static GraphNode seqNode(int w, int h, int frames) {
        GraphNode n = new GraphNode(1, NodeType.IMAGE_SEQUENCE, 0, 0);
        n.imageWidth = w;
        n.imageHeight = h;
        n.imageSequenceFrames = new ArrayList<>();
        for (int i = 0; i < frames; i++) n.imageSequenceFrames.add(new int[w * h]);
        n.imagePixels = n.imageSequenceFrames.get(0);
        return n;
    }

    private static int countNonZero(int[] px) {
        int c = 0;
        for (int v : px) if (v != 0) c++;
        return c;
    }

    // ── blendAlpha ──

    @Test
    @DisplayName("blendAlpha: o=1 → src，o=0 → dst，中点舍入")
    void blendAlphaEndpointsAndMid() {
        int src = 0xFF102030, dst = 0xFF000000;
        assertEquals(src, PixelEditorKernel.blendAlpha(dst, src, 1f));
        assertEquals(dst, PixelEditorKernel.blendAlpha(dst, src, 0f));
        // r: round(32*0.5 + 0*0.5) = 16 → 0xFF101010
        assertEquals(0xFF101010, PixelEditorKernel.blendAlpha(dst, 0xFF202020, 0.5f));
    }

    // ── paintBrush ──

    @Test
    @DisplayName("paintBrush: size=1 单点；size=3 居中 3×3；越界裁剪")
    void brushSizesAndClipping() {
        GraphNode n = imageNode(4, 4);
        PixelEditorKernel.paintBrush(n, 1, 1, 0xFF00FF00, 1, 1f);
        assertEquals(1, countNonZero(n.imagePixels));
        assertEquals(0xFF00FF00, n.imagePixels[1 * 4 + 1]);

        Arrays.fill(n.imagePixels, 0);
        PixelEditorKernel.paintBrush(n, 1, 1, 0xFF00FF00, 3, 1f);
        assertEquals(9, countNonZero(n.imagePixels));
        // 偶数尺寸左偏：size=2 覆盖 (cx-1, cx) 两列 / even sizes lean left: size=2 covers columns (cx-1, cx)
        Arrays.fill(n.imagePixels, 0);
        PixelEditorKernel.paintBrush(n, 1, 1, 0xFF00FF00, 2, 1f);
        assertEquals(4, countNonZero(n.imagePixels));
        assertEquals(0, n.imagePixels[1 * 4 + 2]); // 列 cx+1=2 未覆盖 / column cx+1=2 untouched

        // 越界：画布边缘 stamp 不抛异常、不越界写入 / edge stamps neither throw nor write out of bounds
        GraphNode edge = imageNode(2, 2);
        assertDoesNotThrow(() -> PixelEditorKernel.paintBrush(edge, 0, 0, 0xFFFFFFFF, 5, 1f));
        assertEquals(4, countNonZero(edge.imagePixels));
    }

    @Test
    @DisplayName("paintBrush: 不透明度走 alpha-over 混合")
    void brushOpacityBlends() {
        GraphNode n = imageNode(1, 1);
        n.imagePixels[0] = 0xFF000000;
        PixelEditorKernel.paintBrush(n, 0, 0, 0xFF202020, 1, 0.5f);
        assertEquals(0xFF101010, n.imagePixels[0]);
    }

    @Test
    @DisplayName("paintBrush: 像素数组为 null 时安全跳过")
    void brushNullPixels() {
        GraphNode n = imageNode(2, 2);
        n.imagePixels = null;
        assertDoesNotThrow(() -> PixelEditorKernel.paintBrush(n, 0, 0, 0xFFFFFFFF, 1, 1f));
    }

    // ── floodFill ──

    @Test
    @DisplayName("floodFill: 4 邻接连通填充；同色跳过")
    void fillConnectedRegion() {
        GraphNode n = imageNode(3, 3);
        PixelEditorKernel.floodFill(n, 0, 0, 0xFF0000FF, 1f);
        assertEquals(9, countNonZero(n.imagePixels));
        // 同色再填充 = 无操作 / filling with the same colour is a no-op
        PixelEditorKernel.floodFill(n, 1, 1, 0xFF0000FF, 1f);
        assertEquals(9, countNonZero(n.imagePixels));
    }

    @Test
    @DisplayName("floodFill: 边界阻挡（不同色区域不连通）")
    void fillRespectsBarriers() {
        GraphNode n = imageNode(3, 3);
        // 中间行设为屏障 / middle row is a barrier
        for (int x = 0; x < 3; x++) n.imagePixels[1 * 3 + x] = 0xFF111111;
        PixelEditorKernel.floodFill(n, 0, 0, 0xFF0000FF, 1f);
        for (int x = 0; x < 3; x++) {
            assertEquals(0xFF0000FF, n.imagePixels[x]);       // 顶行已填充 / top row filled
            assertEquals(0xFF111111, n.imagePixels[3 + x]);   // 屏障保留 / barrier kept
            assertEquals(0, n.imagePixels[6 + x]);            // 底行 untouched
        }
    }

    @Test
    @DisplayName("floodFill: 微小透明度舍入回原色时直接跳过（防无限入栈）")
    void fillTinyOpacityNoOp() {
        GraphNode n = imageNode(2, 2);
        Arrays.fill(n.imagePixels, 0xFF202020);
        // round(33*0.01 + 32*0.99) = 32 → 混合回原色 / blend rounds back to the target
        PixelEditorKernel.floodFill(n, 0, 0, 0xFF212121, 0.01f);
        assertEquals(0xFF202020, n.imagePixels[0]);
    }

    @Test
    @DisplayName("floodFill: 像素数组为 null 时安全跳过")
    void fillNullPixels() {
        GraphNode n = imageNode(2, 2);
        n.imagePixels = null;
        assertDoesNotThrow(() -> PixelEditorKernel.floodFill(n, 0, 0, 0xFFFFFFFF, 1f));
    }

    // ── drawLineCells / drawRectCells ──

    @Test
    @DisplayName("drawLineCells: 水平/垂直/对角线的 Bresenham 覆盖")
    void lineCoverage() {
        GraphNode n = imageNode(4, 4);
        PixelEditorKernel.drawLineCells(n, 0, 0, 3, 0, 0xFF00FF00, 1, 1f);
        assertEquals(4, countNonZero(n.imagePixels));

        Arrays.fill(n.imagePixels, 0);
        PixelEditorKernel.drawLineCells(n, 0, 0, 3, 3, 0xFF00FF00, 1, 1f);
        assertEquals(4, countNonZero(n.imagePixels));
        assertEquals(0xFF00FF00, n.imagePixels[3 * 4 + 3]); // 对角端点 / diagonal endpoint

        Arrays.fill(n.imagePixels, 0);
        PixelEditorKernel.drawLineCells(n, 2, 0, 2, 3, 0xFF00FF00, 1, 1f);
        assertEquals(4, countNonZero(n.imagePixels));
    }

    @Test
    @DisplayName("drawRectCells: 只画周界，内部留白")
    void rectPerimeterOnly() {
        GraphNode n = imageNode(4, 3);
        PixelEditorKernel.drawRectCells(n, 0, 0, 3, 2, 0xFF00FF00, 1, 1f);
        assertEquals(10, countNonZero(n.imagePixels));          // 2*(4+3)-4 = 10
        assertEquals(0, n.imagePixels[1 * 4 + 1]);              // 内部 (1,1) / interior
        assertEquals(0, n.imagePixels[1 * 4 + 2]);              // 内部 (2,1)
        assertEquals(0xFF00FF00, n.imagePixels[0]);             // 角 / corner
    }

    // ── 撤销/重做 / undo & redo ──

    @Test
    @DisplayName("像素撤销/重做往返：快照是克隆，重做清空于新笔划")
    void pixelUndoRedoRoundTrip() {
        PixelEditorKernel k = new PixelEditorKernel();
        GraphNode n = imageNode(4, 4);
        int[] before = n.imagePixels.clone();

        k.captureStrokeUndo(n.imagePixels);
        PixelEditorKernel.paintBrush(n, 1, 1, 0xFF00FF00, 1, 1f);
        assertFalse(Arrays.equals(before, n.imagePixels));
        assertTrue(k.canUndo());
        assertFalse(k.canRedo());

        k.performUndo(n, 0);
        assertArrayEquals(before, n.imagePixels);
        assertTrue(k.canRedo());

        k.performRedo(n, 0);
        assertEquals(0xFF00FF00, n.imagePixels[1 * 4 + 1]);

        k.performUndo(n, 0);
        assertArrayEquals(before, n.imagePixels);

        // 新笔划捕获清空重做 / a new capture clears the redo stack
        k.captureStrokeUndo(n.imagePixels);
        assertFalse(k.canRedo());
    }

    @Test
    @DisplayName("MAX_UNDO 封顶：超出后不再入栈，仍可全部撤销")
    void undoCapAtMax() {
        PixelEditorKernel k = new PixelEditorKernel();
        GraphNode n = imageNode(2, 2);
        for (int i = 0; i < PixelEditorKernel.MAX_UNDO + 50; i++) k.captureStrokeUndo(n.imagePixels);
        int undos = 0;
        while (k.canUndo()) { k.performUndo(n, 0); undos++; }
        assertEquals(PixelEditorKernel.MAX_UNDO, undos);
    }

    @Test
    @DisplayName("帧列表撤销/重做往返：帧数与 frameIndex 钳制")
    void framesUndoRedoRoundTrip() {
        PixelEditorKernel k = new PixelEditorKernel();
        GraphNode n = seqNode(2, 2, 3);
        for (int i = 0; i < 3; i++) java.util.Arrays.fill(n.imageSequenceFrames.get(i), 0xFF000001 + i);

        k.pushFramesUndo(n);
        n.imageSequenceFrames.add(new int[4]);              // 新建一帧 / +New
        n.imagePixels = n.imageSequenceFrames.get(3);
        int fi = k.performUndo(n, 3);
        assertEquals(3, n.imageSequenceFrames.size());
        assertEquals(2, fi);                                // 3 超出恢复后的 3 帧 → 钳到 2

        fi = k.performRedo(n, fi);
        assertEquals(4, n.imageSequenceFrames.size());
        assertEquals(2, fi);            // frameIndex 不自动跳到末帧（原实现语义）/ frameIndex is not auto-advanced (as in the original)
    }

    @Test
    @DisplayName("尺寸撤销/重做往返：宽高与像素数组恢复")
    void resizeUndoRedoRoundTrip() {
        PixelEditorKernel k = new PixelEditorKernel();
        GraphNode n = imageNode(4, 4);
        int[] oldPixels = n.imagePixels.clone();

        k.pushResizeUndo(n, 4, 4);
        // 模拟 applyPixelResize 的后半段 / simulate the resize half of applyPixelResize
        n.imageWidth = 8;
        n.imageHeight = 2;
        n.imagePixels = new int[16];
        java.util.Arrays.fill(n.imagePixels, 0xFF123456);

        k.performUndo(n, 0);
        assertEquals(4, n.imageWidth);
        assertEquals(4, n.imageHeight);
        assertArrayEquals(oldPixels, n.imagePixels);

        k.performRedo(n, 0);
        assertEquals(8, n.imageWidth);
        assertEquals(2, n.imageHeight);
        assertEquals(16, n.imagePixels.length);
        assertEquals(0xFF123456, n.imagePixels[0]);
    }

    @Test
    @DisplayName("空栈时 performUndo/performRedo 原样返回 frameIndex")
    void emptyStackNoOp() {
        PixelEditorKernel k = new PixelEditorKernel();
        GraphNode n = imageNode(2, 2);
        assertEquals(2, k.performUndo(n, 2));
        assertEquals(2, k.performRedo(n, 2));
    }
}
