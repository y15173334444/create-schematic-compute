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
}
