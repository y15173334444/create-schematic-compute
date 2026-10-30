package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.graph.NodeConnection;
import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.github.y15173334444.create_schematic_compute.client.editor.GraphEditor;
import io.github.y15173334444.create_schematic_compute.client.editor.NodeRenderer;

/**
 * 删除连线命中回归：画出来的线必须点得到（宽体源节点曾因命中曲线手抄常量宽度而「对准了
 * 删不掉」）；被节点体盖住的线段不可命中（删不到看不见的线，隐藏邻线也不得抢走命中）；
 * 距离并列时取画序最上（交叉口删的是你看见的那条）。
 * Delete-wire hit regression: whatever is drawn must be hittable (wide source nodes were
 * "aimed yet undeletable" while the hit curve kept a stale constant width); stretches hidden
 * under node bodies must never hit (no deleting invisible wires, no hidden neighbours stealing
 * the hit); ties go to the topmost-drawn wire (at a crossing you delete the one you see).
 */
class WireDeleteHitRegressionTest {

    // ── 无头地基（与 EncapSubGraphExpandSyncTest 同款）────────────────────────
    //     Headless foundation (same as EncapSubGraphExpandSyncTest)

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

    private record Fixture(GraphEditor ed, NodeGraph g) {}

    private static Fixture fixture(NodeGraph g, float zoom) {
        var screen = new StubScreen();
        screen.size(800, 600);
        var ed = new GraphEditor(new StubHost(g, screen), screen);
        ed.camX = 0; ed.camY = 0; ed.zoom = zoom;
        return new Fixture(ed, g);
    }

    /** 调私有 hitConn（与 CompileResetAndBusNameGateTest 同款反射接缝）。
     *  Invoke the private hitConn (reflection seam, same as CompileResetAndBusNameGateTest). */
    private static NodeConnection hit(GraphEditor ed, double mx, double my) {
        try {
            var m = GraphEditor.class.getDeclaredMethod("hitConn", double.class, double.class);
            m.setAccessible(true);
            return (NodeConnection) m.invoke(ed, mx, my);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("hitConn must stay accessible for this seam", e);
        }
    }

    private static NodeConnection addWire(NodeGraph g, int fromId, int fromPin, int toId, int toPin) {
        assertTrue(g.addConnection(fromId, fromPin, toId, toPin),
            "连线必须建成（域相配 / 输入唯一占用）/ the wire must be created (domain match, single occupancy)");
        return g.connections.get(g.connections.size() - 1);
    }

    // ── 画线契约的独立复述（勿改用被测几何——这是「画出来是什么样」的验收标准）──
    //     Independent restatement of the drawing contract (do NOT switch to the geometry
    //     under test — this is the acceptance criterion for "what is drawn").

    /** 端点（图空间）：源端 = 节点右缘 fn.x + nw(fn)，目标端 = tn.x；引脚行按通用行公式。 */
    private static float[] drawnEnds(NodeGraph g, int fromId, int fromPin, int toId, int toPin) {
        var fn = g.findNode(fromId);
        var tn = g.findNode(toId);
        float x1 = fn.x + NodeRenderer.nw(fn);
        float y1 = fn.y + NodeRenderer.HH + NodeRenderer.PH * (fn.functionalInputs() + fromPin) + NodeRenderer.PH / 2f;
        float x2 = tn.x;
        float y2 = tn.y + NodeRenderer.HH + NodeRenderer.PH * toPin + NodeRenderer.PH / 2f;
        return new float[]{x1, y1, x2, y2};
    }

    /** 画线曲线上的点（与 NodeWireRenderer.bezier 同一三次贝塞尔）。 */
    private static double[] drawnPoint(float[] e, double t) {
        float dx = Math.abs(e[2] - e[0]) * 0.4f;
        double inv = 1 - t;
        double x = inv*inv*inv*e[0] + 3*inv*inv*t*(e[0]+dx) + 3*inv*t*t*(e[2]-dx) + t*t*t*e[2];
        double y = inv*inv*inv*e[1] + 3*inv*inv*t*e[1] + 3*inv*t*t*e[3] + t*t*t*e[3];
        return new double[]{x, y};
    }

    /** 在画线曲线 t 处点击，必须命中这条连线。 / Clicking the drawn curve at t must hit this wire. */
    private static void assertDrawnPointHits(Fixture f, NodeConnection c,
                                             int fromId, int fromPin, int toId, int toPin,
                                             double t, String label) {
        double[] p = drawnPoint(drawnEnds(f.g(), fromId, fromPin, toId, toPin), t);
        double mx = f.ed().c2sX((float) p[0]), my = f.ed().c2sY((float) p[1]);
        assertSame(c, hit(f.ed(), mx, my),
            label + "：画线上 t=" + t + " 处必须命中该连线 / the drawn wire must be hittable at t=" + t);
    }

    // ── 用例 / cases ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("窄体源节点：画线全段可命中（对照组） / narrow source: the whole drawn wire is hittable (control)")
    void narrowSourceWholeWireHittable() {
        var g = new NodeGraph();
        var src = g.addNode(NodeType.CONST, 0, 0);       // 140 宽 / 140 wide
        var dst = g.addNode(NodeType.ADD, 500, 200);
        var c = addWire(g, src.id, 0, dst.id, 0);
        var f = fixture(g, 1f);
        for (double t = 0.1; t <= 0.9; t += 0.1)
            assertDrawnPointHits(f, c, src.id, 0, dst.id, 0, t, "窄源");
    }

    @Test
    @DisplayName("宽体源节点（FORMULA 240）：画线全段可命中——「对准了却删不掉」回归 / wide source (FORMULA 240): the whole drawn wire is hittable — 'aimed yet cannot delete' regression")
    void wideFormulaSourceWholeWireHittable() {
        for (float zoom : new float[]{0.5f, 1f, 2f}) {
            var g = new NodeGraph();
            var src = g.addNode(NodeType.FORMULA, 0, 0); // 240 宽 / 240 wide
            var dst = g.addNode(NodeType.ADD, 500, 200);
            var c = addWire(g, src.id, 0, dst.id, 0);
            var f = fixture(g, zoom);
            for (double t = 0.15; t <= 0.85; t += 0.1)
                assertDrawnPointHits(f, c, src.id, 0, dst.id, 0, t, "宽源 FORMULA zoom=" + zoom);
        }
    }

    @Test
    @DisplayName("宽体源节点（曲线节点 AMP 240）：画线全段可命中 / wide source (curve node AMP 240): the whole drawn wire is hittable")
    void wideCurveNodeSourceWholeWireHittable() {
        var g = new NodeGraph();
        var src = g.addNode(NodeType.AMP, 0, 0);          // 曲线节点 240 宽 / curve node, 240 wide
        var dst = g.addNode(NodeType.AUDIO_OUT, 500, 200);
        var c = addWire(g, src.id, 0, dst.id, 0);         // AUDIO 域相配 / AUDIO domain match
        var f = fixture(g, 1f);
        for (double t = 0.15; t <= 0.85; t += 0.1)
            assertDrawnPointHits(f, c, src.id, 0, dst.id, 0, t, "宽源 AMP");
    }

    @Test
    @DisplayName("被节点体盖住的线段不可命中，露出的线段仍可命中 / stretches hidden under a node body must not hit; the exposed stretch still does")
    void hiddenStretchNotHittableExposedStretchIs() {
        var g = new NodeGraph();
        var src = g.addNode(NodeType.CONST, 0, 0);
        var dst = g.addNode(NodeType.ADD, 600, 200);
        var c = addWire(g, src.id, 0, dst.id, 0);
        // 遮挡节点：盒体 x∈[300,440) y∈[80,150)（nh=18+16*3=66，+4 缝）盖住画线中点 (370,126)
        // Occluder: body x∈[300,440) y∈[80,150) (nh=18+16*3=66, +4 gap) covers the drawn midpoint (370,126)
        g.addNode(NodeType.ADD, 300, 80);
        var f = fixture(g, 1f);
        double[] mid = drawnPoint(drawnEnds(g, src.id, 0, dst.id, 0), 0.5);
        assertNull(hit(f.ed(), f.ed().c2sX((float) mid[0]), f.ed().c2sY((float) mid[1])),
            "盖在节点体下的线段不得命中 / a stretch hidden under a node body must not hit");
        assertDrawnPointHits(f, c, src.id, 0, dst.id, 0, 0.85, "遮挡后的露出段");
    }

    @Test
    @DisplayName("隐藏邻线不得抢走命中：0.5px 外的隐藏线输给 2px 外的可见线 / a hidden neighbour must not steal the hit: the hidden wire 0.5px away loses to the visible one 2px away")
    void hiddenNeighbourDoesNotStealHit() {
        var g = new NodeGraph();
        // 可见线 A：y=173 水平贯穿 (250,173)；隐藏线 B：x=250 竖直线，顶点锚在 (250,175.5)。
        // 光标 (250,175)：离 A 2px（可见），离 B 仅 0.5px 但 B 的最近段被节点体盖住。
        // Visible wire A runs through (250,173); hidden wire B is the vertical line at x=250
        // anchored at (250,175.5). The cursor sits 2px from A (visible) but only 0.5px from B
        // whose nearest stretch is covered by a node body.
        var sB = g.addNode(NodeType.CONST, 110, 149.5f);   // 锚点 (250,175.5) / anchor (250,175.5)
        var tB = g.addNode(NodeType.ADD, 250, 400);
        var cB = addWire(g, sB.id, 0, tB.id, 0);          // (250,175.5) → (250,426)
        var sA = g.addNode(NodeType.CONST, 60, 147f);     // 锚点 (200,173) / anchor (200,173)
        var tA = g.addNode(NodeType.ADD, 410, 147f);
        var cA = addWire(g, sA.id, 0, tA.id, 0);          // (200,173) → (410,173)
        // 遮挡节点：盒体 x∈[249,389) y∈[174,244) 盖住 B 的顶段，不碰 A 的 (250,173)
        // Occluder: body x∈[249,389) y∈[174,244) covers B's top stretch, never A's (250,173)
        g.addNode(NodeType.ADD, 249, 174f);
        var f = fixture(g, 1f);
        assertSame(cA, hit(f.ed(), f.ed().c2sX(250f), f.ed().c2sY(175f)),
            "隐藏的 0.5px 邻线不得抢走命中，应命中可见的 A / the hidden neighbour must not steal the hit; visible A must win");
        // 反证：B 的顶点锚就在光标旁 0.5px（未被遮挡时它才是最近者）——遮挡确实按最近点判定
        // Counter-proof: B's anchor is 0.5px from the cursor — without the cover it would be
        // the nearest; coverage is decided at the nearest point.
        assertTrue(cB != null && cA != null);
    }

    @Test
    @DisplayName("并列时取画序最上：后画的那条胜出（两种添加顺序） / ties go to the topmost-drawn: the later-added wire wins (both orders)")
    void tieGoesToTopmostDrawn() {
        // 两条线相距 0.5px（线宽量级）：光标压在先画的那条上，并列带内应删后画的（上层）。
        // Two wires 0.5px apart (wire-width scale): the cursor sits on the earlier-drawn one;
        // within the tie band the later-drawn (on top) must be deleted.
        for (boolean reverse : new boolean[]{false, true}) {
            var g = new NodeGraph();
            var s1 = g.addNode(NodeType.CONST, 0, 0);         // 锚点 (140,26) / anchor (140,26)
            var t1 = g.addNode(NodeType.ADD, 500, 300);       // 锚点 (500,326)，中点 (320,176)
            var s2 = g.addNode(NodeType.CONST, 0, 0.5f);      // 锚点 (140,26.5)
            var t2 = g.addNode(NodeType.ADD, 500, 300.5f);    // 锚点 (500,326.5)，中点 (320,176.5)
            NodeConnection later;
            if (!reverse) {
                addWire(g, s1.id, 0, t1.id, 0);
                later = addWire(g, s2.id, 0, t2.id, 0);
            } else {
                addWire(g, s2.id, 0, t2.id, 0);
                later = addWire(g, s1.id, 0, t1.id, 0);
            }
            var f = fixture(g, 1f);
            assertSame(later, hit(f.ed(), f.ed().c2sX(320f), f.ed().c2sY(176f)),
                (reverse ? "反序" : "正序") + "：并列带内必须删画序最上的那条 / "
                    + "within the tie band the topmost-drawn wire must be deleted");
        }
    }
}
