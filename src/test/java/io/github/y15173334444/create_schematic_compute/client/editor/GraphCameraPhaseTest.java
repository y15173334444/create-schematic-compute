package io.github.y15173334444.create_schematic_compute.client.editor;

import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 相机平移相位锁定（抖动治理批 1，docs/blender-shimmer-free-drawing-brief.md 启示 1）：
 * 翻译项整像素量化后，平移时所有元素的取整落点必须整体同幅位移——零相位差、零互爬。
 * Camera phase lock (jitter batch 1, brief implication 1): with the translation term
 * quantized to whole pixels, panning must shift every element's rounded position by the
 * same integer — zero phase difference, zero crawl.
 */
class GraphCameraPhaseTest {

    // ── 无头地基（与 WireDeleteHitRegressionTest 同款）────────────────────────
    //     Headless foundation (same as WireDeleteHitRegressionTest)

    private static final class StubScreen extends Screen {
        StubScreen() { super(Component.literal("t")); }
        void size(int w, int h) { this.width = w; this.height = h; }
        @Override public void render(GuiGraphics g, int mx, int my, float pt) {}
    }

    private static final class StubHost implements GraphEditor.Host {
        final NodeGraph graph;
        final Screen screen;
        StubHost(NodeGraph g, Screen s) { graph = g; screen = s; }
        @Override public NodeGraph getGraph() { return graph; }
        @Override public void saveGraph() {}
        @Override public void toggleRunning(boolean start) {}
        @Override public boolean isRunning() { return false; }
        @Override public Screen asScreen() { return screen; }
    }

    private static GraphEditor editor(float zoom) {
        var screen = new StubScreen();
        screen.size(800, 600);
        var ed = new GraphEditor(new StubHost(new NodeGraph(), screen), screen);
        ed.camX = 0; ed.camY = 0; ed.zoom = zoom;
        return ed;
    }

    @Test
    @DisplayName("翻译项量化到整像素：任意小数相机下 c2s(0) 落整像素 / the translation term is whole-pixel for any fractional camera")
    void translationTermIsWholePixel() {
        var ed = editor(1.3f);
        for (float camX : new float[]{0f, 10.2f, -7.77f, 1234.567f}) {
            ed.camX = camX;
            float t = ed.c2sX(0f);
            assertEquals(t, Math.round(t), "平移项必须是整像素 / the translation term must be a whole pixel, got " + t);
        }
    }

    @Test
    @DisplayName("平移锁步：所有元素取整落点同幅位移，互不爬移 / pans move every element's rounded position by one shared integer — no crawling")
    void panMovesEveryElementInLockstep() {
        var ed = editor(1.3f);
        // 元素相位各不相同的图坐标（小数部分铺开）/ graph coords whose fractional phases differ
        float[] xs = {0f, 10.3f, -25.7f, 140.5f, 999.9f, -0.25f};
        ed.camX = 10.2f;
        int[] before = new int[xs.length];
        for (int i = 0; i < xs.length; i++) before[i] = Math.round(ed.c2sX(xs[i]));
        // 整数鼠标位移的平移 + 一档纯小数相位推进（高精度设备形态）/ an integer mouse-delta pan
        // plus a purely fractional phase push (high-precision device shape)
        for (float delta : new float[]{3f / 1.3f, 0.001f, -0.4f / 1.3f}) {
            ed.camX = 10.2f + delta;
            Integer shift = null;
            for (int i = 0; i < xs.length; i++) {
                int d = Math.round(ed.c2sX(xs[i])) - before[i];
                if (shift == null) shift = d;
                assertEquals(shift.intValue(), d,
                    "元素 " + xs[i] + " 与整组位移不同幅（互爬）/ element moved by a different amount (crawl)");
            }
        }
    }

    @Test
    @DisplayName("整数鼠标位移的平移恰好整体走整像素 / an integer mouse-delta pan shifts the scene by exactly that many pixels")
    void integerMouseDeltaPanIsExact() {
        var ed = editor(1.3f);
        ed.camX = 10.2f;
        float before = ed.c2sX(0f);
        ed.camX += 3f / 1.3f; // 鼠标右移 3px / mouse moved 3px
        assertEquals(3f, ed.c2sX(0f) - before, 1e-3f);
    }

    @Test
    @DisplayName("正反换算闭环：s2c(c2s(x)) 回到 x / the transforms round-trip")
    void roundTripStaysExact() {
        var ed = editor(0.75f);
        ed.camX = -33.33f; ed.camY = 88.88f;
        for (float x : new float[]{0f, 42.42f, -100.5f}) {
            assertEquals(x, ed.s2cX(ed.c2sX(x)), 1e-2f);
            assertEquals(x, ed.s2cY(ed.c2sY(x)), 1e-2f);
        }
    }
}
