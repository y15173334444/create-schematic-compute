package io.github.y15173334444.create_schematic_compute.client.renderer;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 视角摇晃（view bob）的设计决策约束（2026-09-19 修订）。。
 * View-bobbing design-decision constraints (revised 2026-09-19)..
 *
 * <p>背景：Minecraft 把 {@code bobHurt}/{@code bobView} 写进一个 PoseStack 后**乘进投影
 * 矩阵**（GameRenderer.renderLevel），作用于 view space，从不进入 BER 的 poseStack。
 * Background: Minecraft folds bobHurt/bobView into the PROJECTION matrix, acting in
 * view space; it never reaches the BER poseStack.
 *
 * <p><b>历史（防止凭直觉改回去）</b>：v1.2.5.2（93e3530）曾钉死两条结论——"锚定必须
 * 跟随 bob"与"遮罩眼必须带 +B·0 平移"。用户实测（2026-09-19）否决：内容仍随 bob 晃
 * （锚定在预 bob 坐标上，bob 的平移按玻璃深度做透视除 → 近物视差），且 +B·0 遮罩眼
 * 与任何锚定方式都不封闭（Y 向窗口滑差 0.046 NDC）。旧测试的"补偿更差 3.7 倍"是
 * 稻草人对比：拿静态内容对比摇晃玻璃，量出来的本来就是玻璃自己的晃动。
 * <b>History (so nobody reverts on intuition)</b>: v1.2.5.2 (93e3530) pinned "the
 * anchor must follow bob" and "the mask eye carries +B·0". In-game testing
 * (2026-09-19) rejected both: the content still swayed with bob (anchoring in pre-bob
 * coords makes bob's translation divide at the near glass depth — near parallax), and
 * the +B·0 mask eye closes with no anchoring (Y window slide 0.046 NDC). The old
 * "compensating drifts 3.7× more" was a strawman: static content vs a bobbing glass
 * measures the glass's own motion.
 *
 * <p><b>现行设计（2026-09-19 修订，用户选定方案 C「远处虚像」）</b>：内容沿 **bob 后**射线
 * 锚定（{@link MonitorClipMath#anchoredEmit}）——屏幕位置保持远处画布投影（只随全
 * 世界一起转），深度仍在玻璃平面（遮挡不变）；遮罩眼 = **视觉眼** B⁻¹·0（bob 后视
 * 线束在预 bob 视空间的汇聚点）。配对与全量数值见 {@link ViewBobWobbleDiagTest}。
 * <b>Current design (2026-09-19 revision, user option C "far virtual image")</b>: content anchors
 * along the **post-bob** ray ({@link MonitorClipMath#anchoredEmit}) — the screen
 * position keeps the far-canvas projection (only the world-rotation sway remains)
 * while depth stays on the glass plane (occlusion unchanged); the mask eye is the
 * **visual eye** B⁻¹·0 (where the bobed view rays converge in pre-bob view space).
 * Pairings and the full numeric matrix: {@link ViewBobWobbleDiagTest}.
 */
class ViewBobAnchorTest {

    /** 站定时（amp=0）一切必须照旧：bob 是单位矩阵、eye 仍在原点。
     *  Standing still (amp=0): bob is the identity and the eye stays at the origin. */
    @Test
    @DisplayName("Standing still (amp=0) changes nothing")
    void standingStillIsIdentity() {
        var bob = MonitorBlockEntityRenderer.bobTransform(0.5f, 0f);
        var v = new org.joml.Vector3f(1.2f, -0.4f, -3f);
        var out = bob.transformPosition(new org.joml.Vector3f(v));
        assertTrue(Math.abs(out.x - v.x) < 1e-6f && Math.abs(out.y - v.y) < 1e-6f
                && Math.abs(out.z - v.z) < 1e-6f, "amp=0 must be the identity, got " + out);
        var eye = new Vector4f(0f, 0f, 0f, 1f);
        bob.transform(eye);
        assertTrue(Math.abs(eye.x) < 1e-6f && Math.abs(eye.y) < 1e-6f && Math.abs(eye.z) < 1e-6f,
            "amp=0 must leave the eye at the origin");
    }

    /** 行走时视觉眼（B⁻¹·0）与"原点被 bob 搬到的位置"（B·0）是**两个不同的点**：
     *  前者是 bob 后视线束在预 bob 视空间的汇聚点（遮罩眼必须是它），后者方向正好
     *  相反（v1.2.5.2 的错配）。本测试钉住二者不可混用。
     *  While walking, the visual eye (B⁻¹·0) and "where the origin gets carried"
     *  (B·0) are DIFFERENT points: the former is where the bobed view rays converge
     *  in pre-bob view space (the mask eye must be it), the latter is the opposite
     *  side (the v1.2.5.2 mismatch). This test pins that the two must not be
     *  conflated. */
    @Test
    @DisplayName("The visual eye is B⁻¹·0 — the opposite side of +B·0")
    void visualEyeIsInverseSide() {
        var bob = MonitorBlockEntityRenderer.bobTransform(0.25f, 0.1f);
        var carried = new Vector4f(0f, 0f, 0f, 1f);
        bob.transform(carried);
        var visual = new Vector4f(0f, 0f, 0f, 1f);
        bob.invert(new Matrix4f()).transform(visual);
        // 两者都非零（bob 确实挪了视线），且不重合（方向相反）。
        // Both non-zero (bob does move the view) and distinct (opposite sides).
        assertTrue(Math.abs(carried.x) + Math.abs(carried.y) > 1e-6f,
            "walking bob must displace B·0");
        assertTrue(Math.abs(visual.x) + Math.abs(visual.y) > 1e-6f,
            "walking bob must displace the visual eye B⁻¹·0 too");
        float dx = carried.x - visual.x, dy = carried.y - visual.y;
        assertTrue(Math.sqrt(dx * dx + dy * dy) > 1e-4f,
            "B·0 and B⁻¹·0 are different points; the mask eye must use B⁻¹·0");
    }
}
