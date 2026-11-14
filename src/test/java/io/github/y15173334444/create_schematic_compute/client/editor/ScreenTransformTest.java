package io.github.y15173334444.create_schematic_compute.client.editor;

import io.github.y15173334444.create_schematic_compute.graph.GraphNode;
import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 布局/变换单测（抖动方案验收项，docs/graph-editor-canvas-jitter-plan.md §五）：
 * 取整唯一入口、节点原点吸附后派生件抗爬移、网格整数格的刚性平移与 ≤1px 世界对齐。
 * Layout/transform unit tests (the jitter plan's acceptance items): the single rounding
 * entry, the anti-crawl property of origin-derived parts, and the grid lattice's rigid
 * translation with ≤1px world alignment.
 */
class ScreenTransformTest {

    /** 合成映射：把图坐标线性映到屏幕（模拟 c2sX = width/2 + (x+camX)*zoom 的任意相位）。
     *  Synthetic mapper: a linear graph→screen map standing in for c2sX at any phase. */
    private static NodeRenderer.CoordMapper map(float scale, float offset) {
        return v -> v * scale + offset;
    }

    @Test
    @DisplayName("px 是四舍五入唯一入口：无 (int) 截断的负数不对称 / px is the single rounding entry: no truncation asymmetry on negatives")
    void pxRoundsHalfUpSymmetrically() {
        assertEquals(3, ScreenTransform.px(2.6f));
        assertEquals(2, ScreenTransform.px(2.4f));
        assertEquals(-2, ScreenTransform.px(-2.4f));
        assertEquals(-3, ScreenTransform.px(-2.6f));
        // Math.round 半数向 +∞——契约固定，防有人改回截断 / half-up toward +∞ — the contract is
        // pinned so nobody reverts to truncation
        assertEquals(1, ScreenTransform.px(0.5f));
        assertEquals(0, ScreenTransform.px(-0.5f));
    }

    @Test
    @DisplayName("节点原点吸附一次：框/内容/引脚共用同一原点 / the node origin snaps once and is shared")
    void nodeOriginSnapsToNearestPixel() {
        var g = new NodeGraph();
        GraphNode n = g.addNode(NodeType.CONST, 10, 20);
        NodeRenderer.CoordMapper c2sX = map(1f, 0.6f);   // 屏幕 10.6 → 11 / screen 10.6 → 11
        NodeRenderer.CoordMapper c2sY = map(1f, -0.4f);  // 屏幕 19.6 → 20 / screen 19.6 → 20
        assertEquals(11, ScreenTransform.nodeX(c2sX, n));
        assertEquals(20, ScreenTransform.nodeY(c2sY, n));
    }

    @Test
    @DisplayName("派生件抗爬移：锚点-原点差只看局部偏移，与相机相位无关 / derived parts cannot crawl: the anchor-to-origin gap depends only on the local offset, never on the camera phase")
    void anchorKeepsLocalOffsetAcrossCameraPhases() {
        var g = new NodeGraph();
        GraphNode n = g.addNode(NodeType.FORMULA, 37.3f, 12.7f);
        var anchor = new WireGeometry.Pt(n.x + 240, n.y + 26); // 右缘引脚 / right-edge pin
        float zoom = 1.3f;
        // 两个相位：整数步进与小数相位各来一档 / two phases: an integer step and a fractional one
        NodeRenderer.CoordMapper phaseA = map(zoom, 400.0f);
        NodeRenderer.CoordMapper phaseB = map(zoom, 400.7f);
        float gapA = ScreenTransform.anchorX(phaseA, n, anchor, zoom) - ScreenTransform.nodeX(phaseA, n);
        float gapB = ScreenTransform.anchorX(phaseB, n, anchor, zoom) - ScreenTransform.nodeX(phaseB, n);
        // 锚点局部偏移 240 图单位 × 1.3 = 312px——两种相机下严格相等，拖动时连线根与框锁死
        // Local offset 240 graph units × 1.3 = 312px — identical under both cameras, so the
        // wire root stays locked to the frame during drags
        assertEquals(gapA, gapB);
        assertEquals(240 * zoom, gapA, 1e-3f);
    }

    @Test
    @DisplayName("网格线与真实世界线偏差 ≤1px / every grid line stays within 1px of its true world line")
    void gridLatticeStaysWithinOnePixelOfWorldLines() {
        float spacing = 7.5f; // zoom 0.25 档的 GS*zoom / GS*zoom at the 0.25 zoom step
        float origin = 512.35f;
        int[] lines = ScreenTransform.gridLattice(origin, spacing, 1024);
        assertTrue(lines.length > 50, "线数须覆盖视口 / the lattice must cover the viewport");
        for (int x : lines) {
            assertTrue(x >= 0 && x <= 1024, "线必须在视口内 / lines must stay inside the span");
            float k = Math.round((x - origin) / spacing);
            assertTrue(Math.abs(x - (origin + k * spacing)) <= 1.001f,
                "线 " + x + " 偏离真实位置 " + (origin + k * spacing) + " 超过 1px / line deviates by more than 1px");
        }
    }

    @Test
    @DisplayName("平移刚性：整格同步步进，无逐线呼吸 / pans are rigid: the whole lattice steps in lockstep, no per-line breathing")
    void gridLatticeTranslatesRigidlyDuringPans() {
        float spacing = 30f;
        int[] base = ScreenTransform.gridLattice(500.2f, spacing, 1024);
        // 小数相位平移：round(origin) 不动 → 所有线纹丝不动（呼吸的根源就是逐线各跳）
        // A fractional-phase pan: round(origin) holds → every line holds (the breathing came
        // from lines hopping one by one)
        int[] tiny = ScreenTransform.gridLattice(500.4f, spacing, 1024);
        assertEquals(base.length, tiny.length);
        for (int i = 0; i < base.length; i++) assertEquals(base[i], tiny[i]);
        // 整数步平移：所有线统一 +5 / an integer-step pan: every line moves +5 together
        int[] stepped = ScreenTransform.gridLattice(505.2f, spacing, 1024);
        assertEquals(base.length, stepped.length);
        for (int i = 0; i < base.length; i++) assertEquals(base[i] + 5, stepped[i]);
    }

    @Test
    @DisplayName("非正间距回空表，不炸渲染 / a non-positive spacing yields an empty lattice instead of exploding")
    void gridLatticeRejectsNonPositiveSpacing() {
        assertEquals(0, ScreenTransform.gridLattice(0f, 0f, 100).length);
        assertEquals(0, ScreenTransform.gridLattice(0f, -3f, 100).length);
    }
}
