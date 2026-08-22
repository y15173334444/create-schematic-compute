package io.github.y15173334444.create_schematic_compute.client.renderer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the HUD canvas math in {@link MonitorBlockEntityRenderer} —
 * the pitch-ladder attitude indicator (tan-perspective ticks, pitch shift) and
 * the player-screen-positioned 4-gon glass mask (perspective projection of the
 * glass corners onto the far canvas + Sutherland-Hodgman convex clipping).
 * Pure functions only (no GL, no MC state).
 * <p>
 * {@link MonitorBlockEntityRenderer} 中 HUD 画布数学的单元测试——俯仰梯姿态仪
 * （tan 透视刻度、pitch 平移）与玩家屏幕定位 4 边形玻璃遮罩（玻璃角点到远处画布
 * 的透视投影 + Sutherland-Hodgman 凸裁剪）。仅纯函数（无 GL、无 MC 状态）。
 */
class ConformalProjectionTest {

    @Test
    @DisplayName("ladderCanvasY: level flight (pitch=0) puts horizon at canvas center")
    void testLadderCenterLevel() {
        // pitch=0, θ=0 → y = -K·tan(0) = 0
        assertEquals(0, MonitorBlockEntityRenderer.ladderCanvasY(0, 0, 0.6), 1e-9);
    }

    @Test
    @DisplayName("ladderCanvasY: nose-up pitch shifts the horizon down (y negative)")
    void testLadderPitchShift() {
        // pitch=+10°（抬头）→ 地平线（θ=0）下移：y = -K·tan(10°) < 0（y-up）
        double y = MonitorBlockEntityRenderer.ladderCanvasY(10, 0, 0.6);
        assertTrue(y < 0);
        // 2026-08-24 修复（反向叠加）：抬头 10° 的地平线位置 == 平飞时 -10° 刻度
        // 位置（地平线下移 = 下方负角）；+10° 刻度平飞时在中心上方（y > 0）
        // Fixed (opposite-direction sum): the horizon at pitch=+10° equals the
        // -10° tick at level flight (horizon down = negative below); the +10° tick
        // sits above center at level flight (y > 0).
        assertEquals(
            MonitorBlockEntityRenderer.ladderCanvasY(10, 0, 0.6),
            MonitorBlockEntityRenderer.ladderCanvasY(0, -10, 0.6), 1e-9);
        assertTrue(MonitorBlockEntityRenderer.ladderCanvasY(0, 10, 0.6) > 0,
            "+10° tick must be above center at level flight");
    }

    @Test
    @DisplayName("ladderCanvasY: tan perspective — ticks crowd near the horizon")
    void testLadderTanPerspective() {
        // 距地平线等角距的刻度，靠近地平线的更密：|y(20°)-y(10°)| < |y(30°)-y(20°)|
        double hh = 0.6;
        double y10 = Math.abs(MonitorBlockEntityRenderer.ladderCanvasY(0, 10, hh));
        double y20 = Math.abs(MonitorBlockEntityRenderer.ladderCanvasY(0, 20, hh));
        double y30 = Math.abs(MonitorBlockEntityRenderer.ladderCanvasY(0, 30, hh));
        assertTrue((y20 - y10) < (y30 - y20));
    }

    @Test
    @DisplayName("ladderCanvasY: beyond ±90° the tick leaves the canvas (tan → ∞)")
    void testLadderOutOfCanvas() {
        // θ=90°（pitch=0）：tan(90°) → ∞ → |y| 远超画布半高 → 裁剪后不可见
        double y = MonitorBlockEntityRenderer.ladderCanvasY(0, 90, 0.6);
        assertTrue(Math.abs(y) > 10); // far outside any panel
    }

    // ── renderHud 几何（2026-08-20 调试）：面板/虚像世界位置 vs 玩家视角 ──
    // ── renderHud geometry (2026-08-20 debug): panel/virtual-image world positions
    //    relative to the player's view ──

    /** renderHud 的面板矩阵（FACING=NORTH，blockPos (0,64,3)，panelDistance 0.05）。 */
    private static org.joml.Matrix4f hudPanelMatrix(float panelDistance) {
        return new org.joml.Matrix4f()
            .translate(0.5f, 64.5f, 3.5f)
            .rotateY((float) Math.toRadians(-180f)) // FACING=NORTH → toYRot=180
            .translate(0f, 0f, -(0.5f + panelDistance));
    }

    @Test
    @DisplayName("renderHud geometry: panel sits on the -FACING (player) side of the block")
    void testPanelWorldPosition() {
        // 面板在方块南侧（-FACING=+Z 世界，blockPos (0,64,3) → 面板 z ≈ 4.05）
        // Panel sits south of the block (blockPos (0,64,3) → panel z ≈ 4.05)
        var m = hudPanelMatrix(0.05f);
        var pc = new org.joml.Vector4f(0f, 0f, 0f, 1f);
        m.transform(pc);
        assertEquals(0.5f, pc.x, 1e-4f);
        assertEquals(64.5f, pc.y, 1e-4f);
        assertTrue(pc.z > 3.5f, "panel must sit on the +Z (south/-FACING) side, got z=" + pc.z);
    }

    @Test
    @DisplayName("renderHud geometry: virtual image at -FACING lies in front of a player facing the panel")
    void testVirtualImageInFrontOfPlayer() {
        // 2026-08-20 日志铁证：玩家正对面板时，面板在相机前方（panelCam.z<0），而
        // +FACING（局部 +Z）虚像在相机 z=+76.56（玩家背后）。即玩家在面板局部 +Z 侧
        //（FACING 朝向玩家），面向面板 = 面向局部 -Z；画布 -FACING（局部 -100）在
        // 玩家视线前方。此前测试假设「玩家面向 +FACING」是错的。
        // 2026-08-20 log proof: while facing the panel it sits in front of the camera,
        // and a +FACING (local +Z) image landed behind the player — the player stands on
        // the panel's +Z side (FACING faces the player) and looks along local -Z, so the
        // -FACING (local -100) canvas lies in the view direction.
        var m = hudPanelMatrix(0.05f);
        var vMinus = new org.joml.Vector4f(0f, 0f, -100f, 1f); m.transform(vMinus);
        var vPlus = new org.joml.Vector4f(0f, 0f, 100f, 1f); m.transform(vPlus);
        // 玩家在面板正面（面板局部 +Z 侧）3 格，面向面板（视线 = 面板局部 -Z 方向）
        var player = new org.joml.Vector4f(0f, 0f, 3f, 1f); m.transform(player);
        var look = new org.joml.Vector4f(0f, 0f, -1f, 0f); m.transform(look); // 面板局部 -Z → 世界方向
        double dotMinus = (vMinus.x - player.x) * look.x + (vMinus.y - player.y) * look.y + (vMinus.z - player.z) * look.z;
        double dotPlus = (vPlus.x - player.x) * look.x + (vPlus.y - player.y) * look.y + (vPlus.z - player.z) * look.z;
        assertTrue(dotMinus > 0, "-FACING virtual image must be in front of the player, dot=" + dotMinus);
        assertTrue(dotPlus < 0, "+FACING virtual image must be BEHIND the player, dot=" + dotPlus);
    }

    // ── 4 边形遮罩（玩家屏幕定位）：projectGlassCornersToCanvas / pointInConvexQuad /
    //    clipPolyToQuad / polyAabb ──
    // 4-gon mask (player-screen-positioned): projectGlassCornersToCanvas /
    // pointInConvexQuad / clipPolyToQuad / polyAabb

    @Test
    @DisplayName("projectGlassCornersToCanvas: centered player → axis-aligned rectangular mask")
    void testMaskCentered() {
        // 玩家正对玻璃中心（ex=ey=0, ez=3），hw=1, hh=0.6, D=100：
        // t=(100+3)/3≈34.33；mask 内容局部 = 玻璃角×t/D
        float t = 103f / 3f;
        float[] q = MonitorBlockEntityRenderer.projectGlassCornersToCanvas(0f, 0f, 3f, 1f, 0.6f, 100f);
        assertEquals(-1f * t / 100f, q[0], 1e-4f);
        assertEquals(-0.6f * t / 100f, q[1], 1e-4f);
        assertEquals(1f * t / 100f, q[2], 1e-4f);
        assertEquals(-0.6f * t / 100f, q[3], 1e-4f);
        assertEquals(1f * t / 100f, q[4], 1e-4f);
        assertEquals(0.6f * t / 100f, q[5], 1e-4f);
        assertEquals(-1f * t / 100f, q[6], 1e-4f);
        assertEquals(0.6f * t / 100f, q[7], 1e-4f);
    }

    @Test
    @DisplayName("projectGlassCornersToCanvas: player off to +X → mask shifts opposite")
    void testMaskOffCenter() {
        // 玩家偏右（ex=1）：mask 中心 x = ex*(1-t)/D < 0（视线锥向左偏）
        float[] q = MonitorBlockEntityRenderer.projectGlassCornersToCanvas(1f, 0f, 3f, 1f, 0.6f, 100f);
        float t = 103f / 3f;
        float centerX = (q[0] + q[2] + q[4] + q[6]) / 4f;
        float expect = (1f - t) / 100f; // ex*(1-t)/D
        assertEquals(expect, centerX, 1e-3f);
        assertEquals(0f, (q[1] + q[3] + q[5] + q[7]) / 4f, 1e-3f);
    }

    @Test
    @DisplayName("pointInConvexQuad: inside / outside / corner")
    void testPointInQuad() {
        float[] q = {-1f, -1f, 1f, -1f, 1f, 1f, -1f, 1f};
        assertTrue(MonitorBlockEntityRenderer.pointInConvexQuad(0f, 0f, q));
        assertTrue(MonitorBlockEntityRenderer.pointInConvexQuad(0.99f, 0.99f, q));
        assertFalse(MonitorBlockEntityRenderer.pointInConvexQuad(1.01f, 0f, q));
        assertFalse(MonitorBlockEntityRenderer.pointInConvexQuad(0f, -1.01f, q));
    }

    @Test
    @DisplayName("clipPolyToQuad: fully inside → unchanged; fully outside → empty; partial → clipped")
    void testClipPolyToQuad() {
        float[] mask = {-1f, -1f, 1f, -1f, 1f, 1f, -1f, 1f};
        // 全内：quad 不变
        float[] inner = {0f, 0f, 0.5f, 0f, 0.5f, 0.5f, 0f, 0.5f};
        float[] c1 = MonitorBlockEntityRenderer.clipPolyToQuad(inner, mask);
        assertEquals(8, c1.length);
        assertEquals(0f, c1[0], 1e-4f); // 首顶点 (0,0) 保留
        // 全外：空
        float[] outer = {2f, 2f, 3f, 2f, 3f, 3f, 2f, 3f};
        float[] c2 = MonitorBlockEntityRenderer.clipPolyToQuad(outer, mask);
        assertEquals(0, c2.length);
        // 部分：跨越右边界（x∈[0.5,1.5]）→ 裁剪后 x ≤ 1
        float[] cross = {0.5f, -0.5f, 1.5f, -0.5f, 1.5f, 0.5f, 0.5f, 0.5f};
        float[] c3 = MonitorBlockEntityRenderer.clipPolyToQuad(cross, mask);
        assertTrue(c3.length >= 8);
        for (int i = 0; i < c3.length; i += 2) {
            assertTrue(c3[i] <= 1f + 1e-4f, "clipped x must stay ≤ 1, got " + c3[i]);
            assertTrue(c3[i + 1] >= -1f - 1e-4f && c3[i + 1] <= 1f + 1e-4f, "y within mask");
        }
    }

    @Test
    @DisplayName("polyAabb: min/max of a polygon")
    void testPolyAabb() {
        float[] p = {2f, -3f, -5f, 4f, 1f, 6f};
        float[] aabb = MonitorBlockEntityRenderer.polyAabb(p);
        assertEquals(-5f, aabb[0], 1e-6f);
        assertEquals(-3f, aabb[1], 1e-6f);
        assertEquals(2f, aabb[2], 1e-6f);
        assertEquals(6f, aabb[3], 1e-6f);
    }

    @Test
    @DisplayName("rotatedAabb: zero rotation keeps the rect; 90° swaps extents")
    void testRotatedAabb() {
        // 绕原点旋转 0°：AABB = 原矩形
        float[] a0 = MonitorBlockEntityRenderer.rotatedAabb(-1f, -2f, 1f, 2f, 0f, 0f, 1f, 0f);
        assertEquals(-1f, a0[0], 1e-4f);
        assertEquals(-2f, a0[1], 1e-4f);
        assertEquals(1f, a0[2], 1e-4f);
        assertEquals(2f, a0[3], 1e-4f);
        // 绕原点旋转 90°：宽高互换
        float[] a90 = MonitorBlockEntityRenderer.rotatedAabb(-1f, -2f, 1f, 2f, 0f, 0f, 0f, 1f);
        assertEquals(-2f, a90[0], 1e-4f);
        assertEquals(-1f, a90[1], 1e-4f);
        assertEquals(2f, a90[2], 1e-4f);
        assertEquals(1f, a90[3], 1e-4f);
    }
}
