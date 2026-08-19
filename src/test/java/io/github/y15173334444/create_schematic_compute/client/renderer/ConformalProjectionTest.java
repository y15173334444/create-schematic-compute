package io.github.y15173334444.create_schematic_compute.client.renderer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the HUD canvas math in {@link MonitorBlockEntityRenderer} —
 * the pitch-ladder attitude indicator (tan-perspective ticks, pitch shift) and
 * the Liang-Barsky panel-rect clipping. Pure functions only (no GL, no MC state).
 * <p>
 * {@link MonitorBlockEntityRenderer} 中 HUD 画布数学的单元测试——俯仰梯姿态仪
 * （tan 透视刻度、pitch 平移）与 Liang-Barsky 面板矩形裁剪。仅纯函数（无 GL、无 MC 状态）。
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
        // tan 透视：pitch=10° 与 θ=+10° 对称（同向叠加）
        assertEquals(
            MonitorBlockEntityRenderer.ladderCanvasY(10, 0, 0.6),
            MonitorBlockEntityRenderer.ladderCanvasY(0, 10, 0.6), 1e-9);
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

    @Test
    @DisplayName("clipSegmentToPanel: fully-inside segment unchanged")
    void testClipInside() {
        double[] s = MonitorBlockEntityRenderer.clipSegmentToPanel(-0.5, -0.3, 0.5, 0.3, 1, 0.6);
        assertNotNull(s);
        assertEquals(-0.5, s[0], 1e-9); assertEquals(-0.3, s[1], 1e-9);
        assertEquals(0.5, s[2], 1e-9); assertEquals(0.3, s[3], 1e-9);
    }

    @Test
    @DisplayName("clipSegmentToPanel: crossing segment clipped to panel edge")
    void testClipCrossing() {
        // 从面板外 (-2,0) 到面板内 (0.5,0)：裁剪到 x=-1（边界）
        // From outside (-2,0) to inside (0.5,0): clipped to x=-1 (edge)
        double[] s = MonitorBlockEntityRenderer.clipSegmentToPanel(-2, 0, 0.5, 0, 1, 0.6);
        assertNotNull(s);
        assertEquals(-1, s[0], 1e-9);
        assertEquals(0.5, s[2], 1e-9);
    }

    @Test
    @DisplayName("clipSegmentToPanel: fully-outside segment returns null")
    void testClipOutside() {
        assertNull(MonitorBlockEntityRenderer.clipSegmentToPanel(-3, -3, -2, -2, 1, 0.6));
    }
}
