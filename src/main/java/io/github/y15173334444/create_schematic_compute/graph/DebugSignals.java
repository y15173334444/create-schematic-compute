package io.github.y15173334444.create_schematic_compute.graph;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** DEBUG_SIGNAL_GEN 的曲线计算（无状态静态方法）。
 *  Curve computation for DEBUG_SIGNAL_GEN (stateless static methods).
 *
 *  <p>两种设置模式（定义曲线 y=f(x)）：
 *  两种设置模式（定义曲线 y=f(x)）：
 *  <ul>
 *    <li>{@link #SET_MANUAL}：手动曲线 — 控制点线性插值</li>
 *    <li>{@link #SET_FORMULA}：自定义公式 — y=f(x) 表达式</li>
 *  </ul></p>
 *
 *  <p><b>公式角度约定</b>：{@link FormulaParser} 的 sin/cos/tan 输入为<b>度</b>。
 *  Formula angle convention: FormulaParser sin/cos/tan take <b>degrees</b>.</p>
 */
public final class DebugSignals {

    /** 设置模式：手动曲线（控制点插值）。 / Set mode: manual curve (control point interpolation). */
    public static final int SET_MANUAL = 0;
    /** 设置模式：自定义公式。 / Set mode: custom formula. */
    public static final int SET_FORMULA = 1;
    /** 输出模式：频率发生（x 自动 0→1 循环推进）。 / Output mode: frequency generate (x auto-advances 0→1). */
    public static final int OUT_FREQ = 0;
    /** 输出模式：指定 x（x 来自输入引脚）。 / Output mode: input-driven (x from input pin). */
    public static final int OUT_INPUT = 1;

    private static final Logger LOGGER = LoggerFactory.getLogger(DebugSignals.class);

    private DebugSignals() {}

    /** 计算曲线值 y(x)。x 归一化到 [0,1]。
     *  Compute curve value y(x). x is clamped to [0,1]. */
    public static float computeCurve(int setMode, float x, float[] ctrlX, float[] ctrlY,
                                      String formula, List<Object> cachedRpn) {
        float xc = Math.max(0f, Math.min(1f, x));
        return switch (setMode) {
            case SET_MANUAL -> interpolate(xc, ctrlX, ctrlY);
            case SET_FORMULA -> evalFormula(formula, cachedRpn, xc);
            default -> 0f;
        };
    }

    /** 手动曲线线性插值。控制点按 X 升序排列。
     *  Manual curve linear interpolation. Control points sorted by X ascending. */
    private static float interpolate(float x, float[] ctrlX, float[] ctrlY) {
        if (ctrlX == null || ctrlY == null || ctrlX.length == 0) return 0f;
        if (ctrlX.length == 1) return ctrlY[0];
        if (x <= ctrlX[0]) return ctrlY[0];
        if (x >= ctrlX[ctrlX.length - 1]) return ctrlY[ctrlY.length - 1];
        for (int i = 0; i < ctrlX.length - 1; i++) {
            if (x >= ctrlX[i] && x <= ctrlX[i + 1]) {
                float dx = ctrlX[i + 1] - ctrlX[i];
                if (dx <= 0f) return ctrlY[i];
                float t = (x - ctrlX[i]) / dx;
                return ctrlY[i] + t * (ctrlY[i + 1] - ctrlY[i]);
            }
        }
        return ctrlY[ctrlY.length - 1];
    }

    /** 自定义公式 y=f(x)。变量 x 为 0~1。
     *  Custom formula y=f(x). Variable x is 0~1. */
    private static float evalFormula(String formula, List<Object> cachedRpn, double x) {
        if (formula == null || formula.isBlank()) return 0f;
        try {
            List<Object> rpn = cachedRpn != null ? cachedRpn : FormulaParser.compile(formula);
            return (float) FormulaParser.evaluate(rpn, Map.of("x", x));
        } catch (Exception e) {
            LOGGER.warn("Failed to evaluate formula '{}': {}", formula, e.getMessage());
            return 0f;
        }
    }

    /** 编译公式为 RPN（缓存用）。失败返回 null。
     *  Compile formula to RPN (for caching). Returns null on failure. */
    public static List<Object> compileFormula(String formula) {
        if (formula == null || formula.isBlank()) return null;
        try { return FormulaParser.compile(formula); }
        catch (Exception e) {
            LOGGER.debug("Failed to compile formula '{}': {}", formula, e.getMessage());
            return null;
        }
    }

    /** 设置模式名称（用于渲染标签）。 / Set mode name (for render label). */
    public static String setModeName(int setMode) {
        return switch (setMode) {
            case 0 -> "手动曲线";
            case 1 -> "f(x)";
            default -> "?";
        };
    }

    /** 计算曲线在 [0,1] 范围内的可见 Y 区间（供自动缩放和坐标转换）。
     *  超出 [-5,5] 的极端值被截断，避免 tan 等渐近线撑爆缩放。
     *  Compute the visible Y range of the curve over [0,1] (for auto-scale and coordinate mapping).
     *  Extreme values beyond [-5,5] are clipped to prevent tan asymptotes from blowing up the scale.
     *  @return float[] {minY, maxY, range} */
    public static float[] computeVisibleRange(int setMode, float[] ctrlX, float[] ctrlY,
                                               String formula, java.util.List<Object> cachedRpn) {
        // 手动曲线模式使用固定范围——只有公式模式需要自动缩放（公式可能产生极端值）
        // Manual curve mode uses a fixed range — only formula mode needs auto-scale (formulas can produce extreme values)
        if (setMode == SET_MANUAL) {
            return new float[]{-1.1f, 1.1f, 2.2f};
        }
        int samples = 60;
        float CLIP = 5f; // 超出此值的采样点被截断 / samples beyond this are clipped
        float minV = Float.MAX_VALUE, maxV = -Float.MAX_VALUE;
        for (int i = 0; i <= samples; i++) {
            float x = (float) i / samples;
            float v = computeCurve(setMode, x, ctrlX, ctrlY, formula, cachedRpn);
            if (Float.isFinite(v)) {
                if (v < -CLIP) v = -CLIP;
                if (v > CLIP) v = CLIP;
                if (v < minV) minV = v; if (v > maxV) maxV = v;
            }
        }
        if (setMode == SET_MANUAL && ctrlY != null) {
            for (float y : ctrlY) {
                if (Float.isFinite(y)) {
                    if (y < -CLIP) y = -CLIP;
                    if (y > CLIP) y = CLIP;
                    if (y < minV) minV = y; if (y > maxV) maxV = y;
                }
            }
        }
        if (minV > maxV) { minV = -1f; maxV = 1f; }
        float range = maxV - minV;
        if (range < 0.001f) range = 2f;
        float pad = range * 0.1f;
        minV -= pad; maxV += pad;
        range = maxV - minV;
        return new float[]{minV, maxV, range};
    }
}
