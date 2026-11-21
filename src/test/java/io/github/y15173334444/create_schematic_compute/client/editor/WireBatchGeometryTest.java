package io.github.y15173334444.create_schematic_compute.client.editor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 连线批几何锚测（PERF-2 后手：批提交 + pose 局部宽，方案 §3.8/§四）：
 * segmentQuad 的四角必须精确落位——水平/垂直段、45° 斜段、零长退化为方点；
 * 端头外扩契约 = 远端边中点落在 b + u·h（相邻段共享端点时互相覆盖、接头无豁口）；
 * wireHalfWidth 的屏幕宽契约 = 2×half == zoom（节点边框批 2 的 pose 局部口径）。
 * Wire batch geometry anchors (the PERF-2 follow-on: batching + pose-local width):
 * segmentQuad corners must land exactly — horizontal/vertical segments, a 45° diagonal,
 * zero-length degenerating to a dot; the end-extension contract puts the far-edge midpoint
 * at b + u·h (chained segments overlap at the shared endpoint, no joint gap);
 * wireHalfWidth's screen-width contract is 2×half == zoom (the node border's pose-local rule).
 */
class WireBatchGeometryTest {

    private static final float EPS = 1e-6f;

    private static float[] quad(float ax, float ay, float bx, float by, float half) {
        float[] q = new float[8];
        NodeWireRenderer.segmentQuad(ax, ay, bx, by, half, q);
        return q;
    }

    @Test
    @DisplayName("水平段:宽 2h、两端各外扩 h / horizontal segment: width 2h, extended by h at both ends")
    void horizontalSegment() {
        float[] q = quad(10, 10, 30, 10, 0.5f);
        // 环序约定 c1..c4 = a−ext+perp → b+ext+perp → b+ext−perp → a−ext−perp,perp=(−uy,ux)·h:
        // 左→右水平段的 perp=+y,c1/c2 在 y=10.5、c3/c4 在 y=9.5;覆盖 x∈[9.5,30.5](外扩)、y∈[9.5,10.5]。
        // Ring convention c1..c4 = a−ext+perp → b+ext+perp → b+ext−perp → a−ext−perp, perp=(−uy,ux)·h:
        // a left→right horizontal segment has perp=+y, c1/c2 at y=10.5, c3/c4 at y=9.5;
        // coverage x∈[9.5,30.5] (extended), y∈[9.5,10.5].
        assertEquals(9.5f, q[0], EPS);
        assertEquals(10.5f, q[1], EPS);
        assertEquals(30.5f, q[2], EPS);
        assertEquals(10.5f, q[3], EPS);
        assertEquals(30.5f, q[4], EPS);
        assertEquals(9.5f, q[5], EPS);
        assertEquals(9.5f, q[6], EPS);
        assertEquals(9.5f, q[7], EPS);
    }

    @Test
    @DisplayName("垂直段:宽 2h / vertical segment: width 2h")
    void verticalSegment() {
        float[] q = quad(10, 10, 10, 30, 0.5f);
        // 上→下垂直段的 perp=−x:c1/c2 在 x=9.5、c3/c4 在 x=10.5 / a top→down vertical segment
        // has perp=−x: c1/c2 at x=9.5, c3/c4 at x=10.5
        assertEquals(9.5f, q[0], EPS);
        assertEquals(9.5f, q[1], EPS);
        assertEquals(9.5f, q[2], EPS);
        assertEquals(30.5f, q[3], EPS);
        assertEquals(10.5f, q[4], EPS);
        assertEquals(30.5f, q[5], EPS);
        assertEquals(10.5f, q[6], EPS);
        assertEquals(9.5f, q[7], EPS);
    }

    @Test
    @DisplayName("45° 斜段:垂直向与外扩按单位向量精确分解 / 45° diagonal: perpendicular and extension decompose exactly")
    void diagonalSegment() {
        float[] q = quad(0, 0, 10, 10, 1f);
        float s = 1f / (float) Math.sqrt(2);
        float px = -s, py = s;   // 垂直向 (−uy, ux)·h / perpendicular ×h
        float ex = s, ey = s;    // 外扩 u·h / extension ×h
        assertEquals(0 - ex + px, q[0], EPS);
        assertEquals(0 - ey + py, q[1], EPS);
        assertEquals(10 + ex + px, q[2], EPS);
        assertEquals(10 + ey + py, q[3], EPS);
        assertEquals(10 + ex - px, q[4], EPS);
        assertEquals(10 + ey - py, q[5], EPS);
        assertEquals(0 - ex - px, q[6], EPS);
        assertEquals(0 - ey - py, q[7], EPS);
    }

    @Test
    @DisplayName("零长段退化为边长 2h 的方点 / zero-length segment degenerates to a 2h dot")
    void degenerateDot() {
        float[] q = quad(7, 7, 7, 7, 0.125f);
        // 方点环序 (min,min)→(max,min)→(max,max)→(min,max) / dot ring (min,min)→(max,min)→(max,max)→(min,max)
        assertEquals(6.875f, q[0], EPS);
        assertEquals(6.875f, q[1], EPS);
        assertEquals(7.125f, q[2], EPS);
        assertEquals(6.875f, q[3], EPS);
        assertEquals(7.125f, q[4], EPS);
        assertEquals(7.125f, q[5], EPS);
        assertEquals(6.875f, q[6], EPS);
        assertEquals(7.125f, q[7], EPS);
    }

    @Test
    @DisplayName("外扩契约:远端边中点 = b + u·h(共享端点段互覆盖) / extension contract: far-edge midpoint = b + u·h")
    void farEdgeMidpointIsExtendedPastB() {
        float ax = 3, ay = 4, bx = 19, by = 11;
        float half = 0.75f;
        float[] q = quad(ax, ay, bx, by, half);
        float dx = bx - ax, dy = by - ay;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        float mx = (q[2] + q[4]) / 2f, my = (q[3] + q[5]) / 2f;
        assertEquals(bx + dx / len * half, mx, 1e-4f);
        assertEquals(by + dy / len * half, my, 1e-4f);
        // 外扩意味着远端边越过 b 本身 / the extension pushes the far edge past b itself
        assertTrue(mx > bx && my > by);
    }

    @Test
    @DisplayName("屏幕宽契约:2×half == max(zoom, 0.5 下限) / screen-width contract: 2×half == max(zoom, 0.5 floor)")
    void halfWidthMatchesPoseLocalRule() {
        assertEquals(0.5f, NodeWireRenderer.wireHalfWidth(1.0f), EPS);
        assertEquals(1.0f, NodeWireRenderer.wireHalfWidth(2.0f), EPS);
        // 下限边界:zoom 0.5 恰好 = 下限 / the floor boundary: zoom 0.5 exactly meets it
        assertEquals(0.25f, NodeWireRenderer.wireHalfWidth(0.5f), EPS);
        // 下限生效域:低缩放保底 0.5 屏幕像素(实测「有点细」拍板) / the floor domain: low zoom keeps 0.5 screen px
        assertEquals(0.25f, NodeWireRenderer.wireHalfWidth(0.25f), EPS);
        assertEquals(0.25f, NodeWireRenderer.wireHalfWidth(0f), EPS);
    }
}
