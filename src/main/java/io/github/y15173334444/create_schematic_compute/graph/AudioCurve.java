package io.github.y15173334444.create_schematic_compute.graph;

import java.util.Arrays;
import java.util.List;

/**
 * 曲线求值与 LUT 合成（纯函数，服务端与客户端共用）：曲线点对（X 递增 0..1，Y 任意）
 * 上的线性插值。AMP 的力度曲线（x=音符力度 0..1 → y=输出力度）与 WSHAPE 的整形曲线
 * （x=|采样值| 0..1 → y=输出幅度）共用本实现；恒等曲线 = (0,0)→(1,1)，即真旁路判据。
 * <p>Curve evaluation and LUT baking (pure; shared by server and client): linear
 * interpolation over control-point pairs (X ascending 0..1, Y free). AMP's dynamics
 * curve (note velocity → output level) and WSHAPE's shaping curve (|sample| → output
 * magnitude) share this; the identity curve is (0,0)→(1,1) and doubles as the
 * true-bypass criterion.</p>
 */
public final class AudioCurve {

    /** LUT 采样点数（含两端）：256 步 + 1。 / LUT resolution (inclusive ends). */
    public static final int LUT_SIZE = 257;

    private AudioCurve() {}

    /**
     * 一条曲线的控制点对（X 递增 0..1）。数组防御性复制、值相等按内容——record 对数组
     * 分量默认只有引用相等。曲线节点的点列是可变的（编辑器拖点），复制让引用持有快照。
     * <p>One curve's control-point pairs (X ascending 0..1). Arrays are defensively
     * copied and compared by content (a record would otherwise give reference equality);
     * curve nodes' points are mutable (editor drags), so the ref holds a snapshot.</p>
     */
    public record Curve(float[] xs, float[] ys) {
        public Curve {
            xs = xs == null ? new float[0] : xs.clone();
            ys = ys == null ? new float[0] : ys.clone();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Curve c && Arrays.equals(xs, c.xs) && Arrays.equals(ys, c.ys);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(xs) * 31 + Arrays.hashCode(ys);
        }
    }

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

    /** 曲线是否恒等（y=x 且覆盖 [0,1]）：**旁路判据**——恒等曲线该级完全不参与（不塑形、
     *  不入整形链、不进包），「没动过」与「拉回恒等」因此逐位一致、随时可达。null/空点列
     *  按恒等兜底。 / whether the curve is identity (y = x covering [0,1]): the bypass
     *  criterion — an identity stage does not participate at all (no shaping, never
     *  enters the chain or the wire), so "untouched" and "dragged back to identity" stay
     *  bit-identical and always reachable. Null/empty points count as identity. */
    public static boolean isIdentity(float[] xs, float[] ys) {
        if (xs == null || ys == null || xs.length == 0 || xs.length != ys.length) return true;
        if (xs[0] != 0f || xs[xs.length - 1] != 1f) return false;
        for (int i = 0; i < xs.length; i++) if (xs[i] != ys[i]) return false;
        return true;
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

    /** 整形链按序烘成 LUT（客户端收包时用）：每条曲线先烤再按应用序合成（链头先作用），
     *  float 精度、零量化失真。空链返回 null（无整形）。 / bake a shaping chain into one
     *  LUT (client side, at packet receive): each curve bakes, then they compose in
     *  application order (head first) — float precision, zero quantization. An empty
     *  chain yields null (no shaping). */
    public static float[] bakeLut(List<Curve> chain) {
        if (chain == null || chain.isEmpty()) return null;
        float[] lut = buildLut(chain.get(0).xs(), chain.get(0).ys());
        for (int i = 1; i < chain.size(); i++)
            lut = composeLut(lut, buildLut(chain.get(i).xs(), chain.get(i).ys()));
        return lut;
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
