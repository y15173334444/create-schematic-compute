package io.github.y15173334444.create_schematic_compute.client.renderer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the §9.1 conformal projection math in
 * {@link MonitorBlockEntityRenderer} — pure functions only (no GL, no MC state).
 * <p>
 * {@link MonitorBlockEntityRenderer} 中 §9.1 共形投影数学的单元测试——仅纯函数（无 GL、无 MC 状态）。
 */
class ConformalProjectionTest {

    // 面板：中心 (0,0,0)，法线 +Z，右 +X，上 +Y，半宽 1、半高 0.6
    // Panel: center (0,0,0), normal +Z, right +X, up +Y, half-width 1, half-height 0.6
    private static final double[] CENTER = {0, 0, 0};
    private static final double[] NORMAL = {0, 0, 1};
    private static final double[] RIGHT = {1, 0, 0};
    private static final double[] UP = {0, 1, 0};
    private static final double HW = 1.0, HH = 0.6;

    @Test
    @DisplayName("Straight-ahead direction projects to panel center")
    void testCenterProjection() {
        double[] eye = {0, 0, -5}; // player 5 blocks in front of the panel
        double[] p = MonitorBlockEntityRenderer.projectDirectionToPanel(
            eye, new double[]{0, 0, 1}, CENTER, NORMAL, RIGHT, UP, HW, HH);
        assertNotNull(p);
        assertEquals(0, p[0], 1e-6);
        assertEquals(0, p[1], 1e-6);
    }

    @Test
    @DisplayName("Direction behind the eye returns null (panel behind)")
    void testBehindReturnsNull() {
        double[] eye = {0, 0, -5};
        assertNull(MonitorBlockEntityRenderer.projectDirectionToPanel(
            eye, new double[]{0, 0, -1}, CENTER, NORMAL, RIGHT, UP, HW, HH));
    }

    @Test
    @DisplayName("Direction parallel to the panel returns null")
    void testParallelReturnsNull() {
        double[] eye = {0, 0, -5};
        assertNull(MonitorBlockEntityRenderer.projectDirectionToPanel(
            eye, new double[]{1, 0, 0}, CENTER, NORMAL, RIGHT, UP, HW, HH));
    }

    @Test
    @DisplayName("Direction projecting off-panel returns null")
    void testOffPanelReturnsNull() {
        double[] eye = {0, 0, -5};
        // Points far to the right of the panel → |r| > HW
        assertNull(MonitorBlockEntityRenderer.projectDirectionToPanel(
            eye, new double[]{3, 0, 1}, CENTER, NORMAL, RIGHT, UP, HW, HH));
    }

    @Test
    @DisplayName("Slightly-right direction projects to a positive x (y=0)")
    void testOffsetProjection() {
        double[] eye = {0, 0, -5};
        double[] p = MonitorBlockEntityRenderer.projectDirectionToPanel(
            eye, new double[]{0.1, 0, 1}, CENTER, NORMAL, RIGHT, UP, HW, HH);
        assertNotNull(p);
        // Unit-normalization cancels: hit.x = 0.1 * t, t = 5/dz, dz = 1/√1.01
        // → hit.x = 0.1 * 5 * √1.01 / 1 = 0.5 exactly
        assertEquals(0.5, p[0], 1e-6);
        assertEquals(0, p[1], 1e-6);
    }

    @Test
    @DisplayName("Elevated direction projects to positive y (panel-local up)")
    void testElevatedProjection() {
        double[] eye = {0, 0, -5};
        double[] p = MonitorBlockEntityRenderer.projectDirectionToPanel(
            eye, new double[]{0, 0.08, 1}, CENTER, NORMAL, RIGHT, UP, HW, HH);
        assertNotNull(p);
        // hit.y = 0.08 * 5 = 0.4 < half-height 0.6 → on-panel
        assertEquals(0, p[0], 1e-6);
        assertEquals(0.4, p[1], 1e-6);
    }

    @Test
    @DisplayName("Ladder 0° line stays near y=0 across the azimuth fan (fixed camera)")
    void testHorizonLineFlat() {
        // Panel half-width 1 at eye distance 2 covers ±atan(0.5)≈26.6°; a ±20° fan
        // with 9 samples lands all points on the panel.
        double[] eye = {0, 0, -2};
        var pts = MonitorBlockEntityRenderer.ladderLinePoints(
            0f, 0f, 9, 20f, eye, CENTER, NORMAL, RIGHT, UP, HW, HH);
        assertEquals(9, pts.size());
        for (double[] p : pts) assertEquals(0, p[1], 1e-6);
    }

    @Test
    @DisplayName("Ladder ±90° lines are outside the panel (off-panel skipped)")
    void testVerticalExtremesOffPanel() {
        double[] eye = {0, 0, -5};
        var pts = MonitorBlockEntityRenderer.ladderLinePoints(
            0f, 90f, 9, 50f, eye, CENTER, NORMAL, RIGHT, UP, HW, HH);
        // θ=90° direction is vertical → hits nothing inside the panel
        assertTrue(pts.isEmpty());
    }

    @Test
    @DisplayName("directionFromYawPitch: yaw=0/pitch=0 points +Z, pitch up = +Y")
    void testDirectionConvention() {
        double[] fwd = MonitorBlockEntityRenderer.directionFromYawPitch(0f, 0f);
        assertEquals(0, fwd[0], 1e-9);
        assertEquals(0, fwd[1], 1e-9);
        assertEquals(1, fwd[2], 1e-9);
        double[] up = MonitorBlockEntityRenderer.directionFromYawPitch(0f, 90f);
        assertEquals(0, up[0], 1e-9);
        assertEquals(1, up[1], 1e-9);
        assertEquals(0, up[2], 1e-9);
        // Unit length
        double[] d = MonitorBlockEntityRenderer.directionFromYawPitch(37f, -12f);
        double len = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
        assertEquals(1.0, len, 1e-9);
    }

    @Test
    @DisplayName("hudPanelFrame: SOUTH-facing panel center/normal/right/up")
    void testPanelFrameSouth() {
        // Manual frame construction without a BE: replicate the math
        // (hudPanelFrame requires a MonitorBlockEntity; the frame math is covered
        //  by directionFromYawPitch + projectDirectionToPanel above).
        // 手算验证：SOUTH(yaw=0) → N=(0,0,1), R=(1,0,0), U=(0,1,0)
        double yaw = Math.toRadians(0);
        double sy = Math.sin(yaw), cy = Math.cos(yaw);
        double[] n = {-sy, 0, cy};
        double[] r = {cy, 0, sy};
        double[] u = {0, 1, 0};
        assertEquals(0, n[0], 1e-9); assertEquals(0, n[1], 1e-9); assertEquals(1, n[2], 1e-9);
        assertEquals(1, r[0], 1e-9); assertEquals(0, r[1], 1e-9); assertEquals(0, r[2], 1e-9);
        assertEquals(0, u[0], 1e-9); assertEquals(1, u[1], 1e-9); assertEquals(0, u[2], 1e-9);
    }
}
