package io.github.y15173334444.create_schematic_compute.graph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AudioCurve 纯函数测试：线性插值求值（端点钳制、空曲线恒等兜底）、LUT 合成端点一致、
 * 复合 LUT 的先后语义（先 f 再 g）。AMP 力度曲线与 WSHAPE 整形曲线共用此实现。
 * AudioCurve pure-function tests: interpolation (clamped ends, identity fallback for
 * null curves), LUT endpoint consistency, and composition order (f first, then g).
 * Shared by the AMP dynamics curve and the WSHAPE shaping curve.
 */
class AudioCurveTest {

    @Test
    @DisplayName("空曲线恒等兜底 / a null or empty curve is identity")
    void nullCurveIsIdentity() {
        assertEquals(0.7f, AudioCurve.eval(null, null, 0.7f));
        assertEquals(0.7f, AudioCurve.eval(new float[0], new float[0], 0.7f));
    }

    @Test
    @DisplayName("线性插值与端点钳制 / linear interpolation with clamped ends")
    void interpAndClamp() {
        float[] xs = {0f, 1f}, ys = {0f, 2f};
        assertEquals(1f, AudioCurve.eval(xs, ys, 0.5f), 1e-6);
        assertEquals(0f, AudioCurve.eval(xs, ys, -1f), 1e-6, "below the first point clamps to ys[0]");
        assertEquals(2f, AudioCurve.eval(xs, ys, 2f), 1e-6, "above the last point clamps to the end");
    }

    @Test
    @DisplayName("多点曲线（力度压缩示例）/ multi-point curve (the compression example)")
    void multiPoint() {
        float[] xs = {0f, 0.5f, 1f}, ys = {0f, 0.75f, 0.75f};
        assertEquals(0.375f, AudioCurve.eval(xs, ys, 0.25f), 1e-6);
        assertEquals(0.75f, AudioCurve.eval(xs, ys, 0.75f), 1e-6, "flat segment holds the value");
    }

    @Test
    @DisplayName("LUT 端点一致 + 复合先 f 后 g / LUT endpoints match and compose applies f then g")
    void lutAndCompose() {
        float[] xs = {0f, 1f}, ys = {0f, 2f};
        float[] lut = AudioCurve.buildLut(xs, ys);
        assertEquals(AudioCurve.LUT_SIZE, lut.length);
        assertEquals(0f, lut[0], 1e-6);
        assertEquals(2f, lut[AudioCurve.LUT_SIZE - 1], 1e-6);

        float[] g = AudioCurve.buildLut(new float[]{0f, 1f}, new float[]{0f, 0.5f});
        float[] composed = AudioCurve.composeLut(lut, g);
        // compose(x) = g(f(x))：x=0.25 → f=0.5 → g=0.25；x=0.5 → f=1 → g=0.5
        // compose(x) = g(f(x)): x=0.25 → f=0.5 → g=0.25; x=0.5 → f=1 → g=0.5
        assertEquals(0.25f, AudioCurve.evalLut(composed, 0.25f), 1e-5);
        assertEquals(0.5f, AudioCurve.evalLut(composed, 0.5f), 1e-5);
    }

    @Test
    @DisplayName("恒等判据：y=x 且覆盖 [0,1] / identity means y = x covering [0,1]")
    void isIdentityCheck() {
        assertTrue(AudioCurve.isIdentity(null, null), "no curve = identity (bypass)");
        assertTrue(AudioCurve.isIdentity(new float[]{0f, 1f}, new float[]{0f, 1f}));
        assertTrue(AudioCurve.isIdentity(new float[]{0f, 0.5f, 1f}, new float[]{0f, 0.5f, 1f}),
            "collinear points on y=x are still identity");
        assertFalse(AudioCurve.isIdentity(new float[]{0f, 1f}, new float[]{0f, 2f}));
        assertFalse(AudioCurve.isIdentity(new float[]{0f, 0.5f, 1f}, new float[]{0f, 0.4f, 1f}),
            "off-line points are a real shape");
        assertFalse(AudioCurve.isIdentity(new float[]{0.2f, 1f}, new float[]{0.2f, 1f}),
            "not covering x < 0.2: the clamp end is not identity");
    }

    @Test
    @DisplayName("链烘 LUT 按应用序合成，空链无整形 / baking a chain composes in application order; an empty chain is none")
    void bakeChainInOrder() {
        var f = new AudioCurve.Curve(new float[]{0f, 1f}, new float[]{0f, 2f});    // y = 2x
        var g = new AudioCurve.Curve(new float[]{0f, 1f}, new float[]{0f, 0.5f});  // y = x/2
        float[] lut = AudioCurve.bakeLut(java.util.List.of(f, g));
        assertEquals(0.5f, AudioCurve.evalLut(lut, 0.5f), 1e-5, "g(f(0.5)) = g(1.0) = 0.5");
        assertEquals(0.25f, AudioCurve.evalLut(lut, 0.25f), 1e-5, "g(f(0.25)) = g(0.5) = 0.25");
        assertNull(AudioCurve.bakeLut(java.util.List.of()), "an empty chain bakes to nothing");
    }

    @Test
    @DisplayName("Curve 数组防御复制 + 值相等 / Curve snapshots its arrays and compares by content")
    void curveSnapshotAndEquality() {
        float[] xs = {0f, 1f}, ys = {0f, 2f};
        var c = new AudioCurve.Curve(xs, ys);
        ys[1] = 0.5f;                                  // 改动源数组不影响已建曲线 / mutate source
        assertEquals(2f, c.ys()[1], 1e-6, "the curve holds a snapshot");
        assertEquals(new AudioCurve.Curve(new float[]{0f, 1f}, new float[]{0f, 2f}), c,
            "equal content is equal, arrays compared by content");
    }
}
