package io.github.y15173334444.create_schematic_compute.client.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 连线视口截取的数学锚测（PERF-2 批，2026-11-14）：「单调贝塞尔 × 视口边 → 交点 t」
 * 的契约。渲染端以 t 区间截取替代全量采样（jstack 实测连线占 Render thread 78–83%），
 * 反解错一条曲线就会把线画短/画偏——这里逐轴锚住反解的正确性：
 * X 与 Y 是**两条不同的**单调曲线（X 控制点外扩 0.4、Y 平切线），反解必须各用各的。
 * Headless math anchor for the wire viewport truncation (PERF-2 batch, 2026-11-14): the
 * contract "monotone bezier × viewport edge → crossing t". The renderer now samples a
 * sub-interval instead of the full curve (jstack: wires were 78–83% of the Render thread),
 * and inverting the wrong curve would draw wires short or skewed — this pins each axis to
 * its own curve: X and Y are **two different** monotone curves (X bulges 0.4, Y has flat
 * tangents), so each inversion must evaluate its own.
 */
class WireViewportTruncationTest {

    /** 10 次二分的 t 精度 2^-10；X 曲线最大斜率 ≈1.5×跨度 → 值域误差 <1px（600px 跨度）。
     *  10 bisections give t precision 2^-10; the X curve's max slope ≈1.5×span → value
     *  error under 1px at a 600px span. */
    private static final float EPS = 1.5f;

    @Test
    void increasingXCurveInvertsToTheCrossing() {
        float t = NodeWireRenderer.crossT(0f, 600f, 300f, true);
        assertTrue(t >= 0f && t <= 1f);
        assertEquals(300f, WireGeometry.bezierX(0f, 600f, t), EPS);
    }

    @Test
    void decreasingXCurveInvertsToTheCrossing() {
        float t = NodeWireRenderer.crossT(600f, 0f, 300f, true);
        assertTrue(t >= 0f && t <= 1f);
        assertEquals(300f, WireGeometry.bezierX(600f, 0f, t), EPS);
    }

    @Test
    void flatTangentYCurveUsesItsOwnCurve() {
        // 故意取不对称 target：Y 平切线曲线早段起步慢（Y'(0)=0），X 外扩曲线早段快
        // （X'(0)=3·0.4·跨度）——接错轴时 bezierY(0,200,~0.19)≈19 与 50 差 ~31px ≫ EPS，
        // 锚测能真咬住。/ Deliberately asymmetric: the flat-tangent Y curve starts slow
        // (Y'(0)=0) while the bulging X curve starts fast (X'(0)=3·0.4·span) — a crossed
        // axis yields bezierY(t)≈19 against 50, a ~31px miss ≫ EPS, so the anchor bites.
        float t = NodeWireRenderer.crossT(0f, 200f, 50f, false);
        assertEquals(50f, WireGeometry.bezierY(0f, 200f, t), EPS);
    }

    @Test
    void targetsOutsideTheCurveRangeClampByDirection() {
        assertEquals(0f, NodeWireRenderer.crossT(0f, 600f, -50f, true), 1e-6f);
        assertEquals(1f, NodeWireRenderer.crossT(0f, 600f, 650f, true), 1e-6f);
        // 递减方向两端对调。/ Decreasing direction flips the clamps.
        assertEquals(1f, NodeWireRenderer.crossT(600f, 0f, -50f, true), 1e-6f);
        assertEquals(0f, NodeWireRenderer.crossT(600f, 0f, 650f, true), 1e-6f);
    }

    @Test
    void degenerateVerticalSpanImposesNoTruncation() {
        // a==b（竖直线）：值域塌缩，左/右边界落在值域外 → 回 0/1，两区间并起来仍是全区间
        // （bezier() 侧 t0=0、t1=1 不被截）。/ a==b (vertical wire): the range collapses;
        // both edges sit outside it → 0/1, so the renderer's [t0,t1] stays the full span.
        assertEquals(0f, NodeWireRenderer.crossT(100f, 100f, 50f, true), 1e-6f);
        assertEquals(1f, NodeWireRenderer.crossT(100f, 100f, 150f, true), 1e-6f);
    }
}
