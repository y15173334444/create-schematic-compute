package io.github.y15173334444.create_schematic_compute.graph;

/**
 * 曲线求值与 LUT 合成（纯函数，服务端与客户端共用）：曲线点对（X 递增 0..1，Y 任意）
 * 上的线性插值。AMP 的力度曲线（x=音符力度 0..1 → y=输出力度）与 WSHAPE 的整形曲线
 * （x=|采样值| 0..1 → y=输出幅度）共用本实现；恒等曲线 = (0,0)→(1,1)。
 * <p>Curve evaluation and LUT baking (pure; shared by server and client): linear
 * interpolation over control-point pairs (X ascending 0..1, Y free). AMP's dynamics
 * curve (note velocity → output level) and WSHAPE's shaping curve (|sample| → output
 * magnitude) share this; the identity curve is (0,0)→(1,1).</p>
 */
public final class AudioCurve {

    /** LUT 采样点数（含两端）：256 步 + 1。 / LUT resolution (inclusive ends). */
    public static final int LUT_SIZE = 257;

    private AudioCurve() {}

    /** 曲线求值：x 被两端钳制，相邻控制点间线性插值；空曲线返回 x（恒等兜底）。
     *  Evaluate: x clamps to the ends, linear between points; a null/empty curve is identity. */
    public static float eval(float[] xs, float[] ys, float x) {
        if (xs == null || ys == null || xs.length == 0 || xs.length != ys.length) return x;
        if (x <= xs[0]) return ys[0];
        int n = xs.length;
        if (x >= xs[n - 1]) return ys[n - 1];
        for (int i = 1; i < n; i++) {
            if (x <= xs[i]) {
                float x0 = xs[i - 1], x1 = xs[i];
                float t = (x1 == x0) ? 0f : (x - x0) / (x1 - x0);
                return ys[i - 1] + t * (ys[i] - ys[i - 1]);
            }
        }
        return ys[n - 1];
    }

    /** 合成 LUT（等距 x 网格 0..1 共 {@link #LUT_SIZE} 点）；客户端混音器逐样本查表用。
     *  Bake a LUT on an even 0..1 grid for the client mixer's per-sample lookups. */
    public static float[] buildLut(float[] xs, float[] ys) {
        float[] lut = new float[LUT_SIZE];
        for (int i = 0; i < LUT_SIZE; i++) lut[i] = eval(xs, ys, i / (float) (LUT_SIZE - 1));
        return lut;
    }

    /** 两条曲线串联：先 f 再 g 的复合 LUT（WSHAPE 多实例按序组合用）。
     *  Compose two curves' LUTs (apply f, then g) for chained WSHAPE nodes. */
    public static float[] composeLut(float[] f, float[] g) {
        float[] out = new float[LUT_SIZE];
        for (int i = 0; i < LUT_SIZE; i++) {
            float x = i / (float) (LUT_SIZE - 1);
            out[i] = evalLut(g, evalLut(f, x));
        }
        return out;
    }

    /** LUT 查表（x 钳 0..1，相邻线性插值）。 / LUT lookup (x clamped 0..1, linear between entries). */
    public static float evalLut(float[] lut, float x) {
        if (lut == null || lut.length == 0) return x;
        float t = Math.max(0f, Math.min(1f, x)) * (lut.length - 1);
        int i = (int) t;
        if (i >= lut.length - 1) return lut[lut.length - 1];
        float frac = t - i;
        return lut[i] + frac * (lut[i + 1] - lut[i]);
    }
}
