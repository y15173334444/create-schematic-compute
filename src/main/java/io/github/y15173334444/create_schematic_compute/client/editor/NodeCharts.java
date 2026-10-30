package io.github.y15173334444.create_schematic_compute.client.editor;

import io.github.y15173334444.create_schematic_compute.graph.AudioCurve;
import io.github.y15173334444.create_schematic_compute.graph.DebugSignals;
import io.github.y15173334444.create_schematic_compute.graph.EvalSnapshot;
import io.github.y15173334444.create_schematic_compute.graph.GraphNode;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;

/**
 * 调试节点图表渲染（graph space，坐标相对于节点左上角）——自 NodeRenderer 拆出
 * （docs/file-topology-split-review-2026-10-11.md §三 P2 的 NodeCharts 切口）。
 * Debug-node chart rendering (graph space, coords relative to node top-left) — extracted from
 * NodeRenderer (the NodeCharts cut of the topology review §三 P2).
 *
 * <p>覆盖三张图：DEBUG_SIGNAL_GEN 的 XY 坐标图 + 波形曲线、AMP/WSHAPE 的力度/整形曲线、
 * DEBUG_PROBE 的当前值 + 迷你趋势图。NodeRenderer.drawNode 的图表分支直呼本类
 * （Bresenham drawLine 随迁至此）。
 * Covers three charts: the DEBUG_SIGNAL_GEN XY chart + waveform, the AMP/WSHAPE dynamics/shaping
 * curve, and the DEBUG_PROBE value + mini trend. NodeRenderer.drawNode's chart branch calls this
 * class directly (the Bresenham drawLine moved here with it).</p>
 *
 * <p>每帧分配治理（复审 P3 的无风险版）：控制点 {@code float[2]} 与返回列表逐帧复用——
 * 唯一调用方 {@code NodeRenderer.drawNode} 在**同一帧内**立即消费返回列表（收集 → 边框上方渲染），
 * 下一帧收集前会被清空重填；**返回列表绝不许跨帧持有**（那是约定，不是防御）。
 * Per-frame allocation discipline (the risk-free slice of tech-debt P3): control-point
 * {@code float[2]}s and the returned list are reused across frames — the sole caller
 * ({@code NodeRenderer.drawNode}) consumes the returned list **within the same frame**
 * (collect → render above border) and it is cleared and refilled before the next collect;
 * the returned list must **never be held across frames** (a documented contract, not defended).</p>
 */
final class NodeCharts {

    private NodeCharts() {}

    /** 控制点坐标对象池（按需增长，跨帧复用）/ control-point pool (grows on demand, reused across frames). */
    private static final java.util.ArrayList<float[]> CTRL_POOL = new java.util.ArrayList<>();
    /** 返回视图（每帧清空重填，指向池内对象）/ the returned view (cleared and refilled each frame, wrapping pool entries). */
    private static final java.util.ArrayList<float[]> CTRL_VIEW = new java.util.ArrayList<>();

    /** 借出 count 个控制点槽位（复用池内对象，绝不返回池本体）。
     *  Borrow `count` control-point slots (pool objects reused; the pool itself is never returned). */
    private static List<float[]> borrowCtrlPoints(int count) {
        CTRL_VIEW.clear();
        while (CTRL_POOL.size() < count) CTRL_POOL.add(new float[2]);
        for (int i = 0; i < count; i++) CTRL_VIEW.add(CTRL_POOL.get(i));
        return CTRL_VIEW;
    }

    /** DEBUG_SIGNAL_GEN：XY 坐标图 + 波形曲线。返回控制点本地坐标列表（供边框上方渲染）。
     *  XY chart + waveform curve. Returns control point local coords for above-border rendering. */
    static List<float[]> renderDebugSignalGenChart(GuiGraphics g, GraphNode n, int nodeW,
                                                    EvalSnapshot evalSnapshot, int currentEncapId) {
        float bodyH = NodeRenderer.HH + NodeRenderer.PH * (n.functionalInputs() + n.outputs());
        int chartX = 2;
        int chartY = (int) bodyH;
        int chartW = nodeW - 4;
        int chartH = 80;
        int setMode = n.params.length > 0 ? (int) n.params[0] : 0;
        int samples = 60;

        // ── 计算 Y 范围（自动缩放）/ compute Y range (auto-scale) ──
        float[] visRange = DebugSignals.computeVisibleRange(
            setMode, n.curveX, n.curveY, n.formula, n.debugFormulaRpn);
        float minV = visRange[0], maxV = visRange[1], range = visRange[2];
        float scale = chartH / range;

        // ── 渲染 / rendering ──
        // 背景
        g.fill(chartX, chartY, chartX + chartW, chartY + chartH, 0xFF1A1A2E);
        // 网格线（每 1/4 一条）
        for (int i = 1; i < 4; i++) {
            int gx = chartX + chartW * i / 4;
            g.fill(gx, chartY, gx + 1, chartY + chartH, 0xFF2A2A4E);
            int gy = chartY + chartH * i / 4;
            g.fill(chartX, gy, chartX + chartW, gy + 1, 0xFF2A2A4E);
        }
        // 0 值线（如果在可见范围内）/ zero line (if within visible range)
        if (minV <= 0 && maxV >= 0) {
            int zeroY = chartY + chartH - (int) ((0 - minV) * scale);
            g.fill(chartX, zeroY, chartX + chartW, zeroY + 1, 0xFF3A3A6E);
        }

        // 曲线 / curve
        int prevPX = -1, prevPY = -1;
        float prevV = 0;
        boolean prevValid = false;
        for (int i = 0; i <= samples; i++) {
            float x = (float) i / samples;
            float v = DebugSignals.computeCurve(
                setMode, x, n.curveX, n.curveY, n.formula, n.debugFormulaRpn);
            boolean discontinuity = prevValid && Math.abs(v - prevV) > range * 1.5f;
            int px = chartX + (int) (x * chartW);
            int py = chartY + chartH - (int) ((v - minV) * scale);
            py = Math.max(chartY, Math.min(chartY + chartH - 1, py));
            if (prevPX >= 0 && !discontinuity) drawLine(g, prevPX, prevPY, px, py, 0xFF4ADE80);
            prevPX = px; prevPY = py;
            prevV = v;
            prevValid = true;
        }

        // 控制点位置收集（仅手动曲线模式）— 在边框上方渲染；逐帧复用，见类头契约
        // Collect control point positions (manual curve only) — rendered above border;
        // per-frame reuse, see the class-header contract
        List<float[]> ctrlPoints = null;
        boolean showCtrl = (setMode == DebugSignals.SET_MANUAL);
        if (showCtrl && n.curveX != null) {
            ctrlPoints = borrowCtrlPoints(n.curveX.length);
            for (int i = 0; i < n.curveX.length; i++) {
                float[] cp = ctrlPoints.get(i);
                cp[0] = chartX + n.curveX[i] * chartW;
                cp[1] = chartY + chartH - (n.curveY[i] - minV) * scale;
            }
        }

        // x 标记线
        int outMode = n.params.length > 1 ? (int) n.params[1] : 0;
        float xPos;
        if (outMode == DebugSignals.OUT_FREQ) {
            xPos = evalSnapshot != null
                ? (currentEncapId >= 0 ? evalSnapshot.getSubDebugTime(currentEncapId, n.id) : evalSnapshot.getDebugTime(n.id))
                : 0f;
        } else {
            xPos = n.params.length > 4 ? n.params[4] : 0.5f;
        }
        xPos = Math.max(0f, Math.min(1f, xPos));
        int mxLine = chartX + (int) (xPos * chartW);
        int xMarkerColor = outMode == DebugSignals.OUT_FREQ
            ? 0xFF00BFFF : 0xFF44DDFF;
        g.fill(mxLine, chartY, mxLine + 1, chartY + chartH, xMarkerColor);

        // 模式标签
        drawStr(g, DebugSignals.setModeName(setMode),
            chartX + 4, chartY + 2, 0xFFCCCCCC);
        // Y 轴范围标注（右上角）/ Y-axis range label (top-right)
        String rangeStr = String.format("%.1f … %.1f", minV, maxV);
        int rangeW = Minecraft.getInstance().font.width(rangeStr);
        drawStr(g, rangeStr, chartX + chartW - rangeW - 3, chartY + 2, 0xFF888899);
        return ctrlPoints;
    }

    /** AMP/WSHAPE：力度/整形曲线图（固定值域：AMP 0..1；WSHAPE 0..2 允许过驱）。
     *  返回控制点本地坐标（供边框上方渲染），语义与 DEBUG 图表一致。
     *  AMP/WSHAPE dynamics/shaping chart (fixed Y range: AMP 0..1; WSHAPE 0..2 allows
     *  overdrive). Returns control point local coords, same contract as the DEBUG chart. */
    static List<float[]> renderCurveChart(GuiGraphics g, GraphNode n, int nodeW) {
        float bodyH = NodeRenderer.HH + NodeRenderer.PH * (n.functionalInputs() + n.outputs());
        int chartX = 2, chartY = (int) bodyH, chartW = nodeW - 4, chartH = 80;
        float yMax = n.type == NodeType.WSHAPE ? 2f : 1f;
        int samples = 60;

        g.fill(chartX, chartY, chartX + chartW, chartY + chartH, 0xFF1A1A2E);
        // 网格线（每 1/4 一条）/ grid lines every quarter
        for (int i = 1; i < 4; i++) {
            int gx = chartX + chartW * i / 4;
            g.fill(gx, chartY, gx + 1, chartY + chartH, 0xFF2A2A4E);
            int gy = chartY + chartH * i / 4;
            g.fill(chartX, gy, chartX + chartW, gy + 1, 0xFF2A2A4E);
        }
        // 恒等参考线（y=x，仅 AMP 值域 0..1 内可见）/ identity reference (visible in AMP range)
        if (n.type == NodeType.AMP) {
            for (int i = 0; i < chartW; i += 2) {
                int ix = chartX + i, iy = chartY + chartH - i * chartH / chartW;
                g.fill(ix, iy, ix + 1, iy + 1, 0xFF3A3A6E);
            }
        }

        // 曲线（AudioCurve 线性插值，与求值/LUT 同一实现）/ the curve (same interp as eval/LUT)
        int prevPX = -1, prevPY = -1;
        for (int i = 0; i <= samples; i++) {
            float x = (float) i / samples;
            float v = AudioCurve.eval(n.curveX, n.curveY, x);
            int px2 = chartX + (int) (x * chartW);
            int py2 = chartY + chartH - (int) ((v / yMax) * chartH);
            py2 = Math.max(chartY, Math.min(chartY + chartH - 1, py2));
            if (prevPX >= 0) drawLine(g, prevPX, prevPY, px2, py2, 0xFF4ADE80);
            prevPX = px2; prevPY = py2;
        }

        // 控制点位置收集（边框上方渲染；逐帧复用，见类头契约）/ collect control points for
        // above-border rendering (per-frame reuse, see the class-header contract)
        List<float[]> ctrlPoints = borrowCtrlPoints(n.curveX != null ? n.curveX.length : 0);
        if (n.curveX != null) {
            for (int i = 0; i < n.curveX.length; i++) {
                float[] cp = ctrlPoints.get(i);
                cp[0] = chartX + n.curveX[i] * chartW;
                cp[1] = chartY + chartH - (n.curveY[i] / yMax) * chartH;
            }
        }
        // Y 轴范围标注（右上角）/ Y-axis range label (top-right)
        String rangeStr = String.format("%.1f … %.1f", 0f, yMax);
        int rangeW = Minecraft.getInstance().font.width(rangeStr);
        drawStr(g, rangeStr, chartX + chartW - rangeW - 3, chartY + 2, 0xFF888899);
        return ctrlPoints;
    }

    /** DEBUG_PROBE：当前数值 + 迷你趋势折线图。 */
    static void renderDebugProbeChart(GuiGraphics g, GraphNode n, int nodeW) {
        float bodyH = NodeRenderer.HH + NodeRenderer.PH * (n.functionalInputs() + n.outputs());
        int valY = (int) bodyH;
        int chartX = 2;
        int chartY = valY + 20;
        int chartW = nodeW - 4;
        int chartH = 44;

        // 读取最新采样值
        int lastIdx = (n.probeHead - 1 + n.probeHistory.length) % n.probeHistory.length;
        float curVal = n.probeCount > 0 ? n.probeHistory[lastIdx] : 0f;
        boolean hasData = n.probeCount > 0;

        // 数值显示
        String valStr = hasData ? String.format("%.3f", curVal) : "---";
        int valCol = !hasData ? 0xFF888888
            : (Float.isNaN(curVal) || Float.isInfinite(curVal) ? 0xFF888888
            : (Math.abs(curVal) > 10f ? 0xFFFF4444 : 0xFF4ADE80));
        drawStr(g, valStr, chartX + 4, valY + 2, valCol);

        // 趋势图背景
        g.fill(chartX, chartY, chartX + chartW, chartY + chartH, 0xFF1A1A2E);
        int midY = chartY + chartH / 2;
        g.fill(chartX, midY, chartX + chartW, midY + 1, 0xFF2A2A4E);

        if (!hasData || n.probeCount < 2) {
            drawStr(g, "...", chartX + chartW / 2 - 8, chartY + chartH / 2 - 3, 0xFF666666);
            return;
        }

        // 读取参数
        int windowSize = n.params.length > 0 ? (int) n.params[0] : 50;
        windowSize = Math.max(2, Math.min(n.probeHistory.length, windowSize));
        boolean autoScale = n.params.length > 1 ? n.params[1] != 0 : true;
        float fixedRange = 10f;

        int count = Math.min(n.probeCount, windowSize);
        int start = (n.probeHead - count + n.probeHistory.length) % n.probeHistory.length;

        // 计算窗口内数据范围（百分位数稳健范围，与信号发生器一致）
        // 不再使用固定 ±5 截断，避免大值域数据（如 x*360）的范围被错误压垮。
        // Compute window data range (percentile-based robust range, same as signal generator).
        // Fixed ±5 clipping is removed so large-scale data (e.g. x*360) displays correctly.
        java.util.List<Float> pvals = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int idx = (start + i) % n.probeHistory.length;
            float v = n.probeHistory[idx];
            if (Float.isFinite(v) && Math.abs(v) < 1e6f) pvals.add(v);
        }
        float minV, maxV;
        if (pvals.isEmpty()) { minV = -1f; maxV = 1f; }
        else {
            java.util.Collections.sort(pvals);
            int lo = (int)(pvals.size() * 0.01f);
            int hi = (int)(pvals.size() * 0.99f);
            if (lo >= hi) { lo = 0; hi = pvals.size() - 1; }
            minV = pvals.get(lo);
            maxV = pvals.get(hi);
        }
        if (minV > maxV) { minV = -1f; maxV = 1f; }
        float range = autoScale ? Math.max(maxV - minV, 0.001f) : (2 * fixedRange);
        float base = autoScale ? minV : -fixedRange;
        if (autoScale && range > 0.001f) {
            float pad = range * 0.1f;
            base -= pad;
            range += pad * 2;
        }

        // 绘制折线
        int prevPX = -1, prevPY = -1;
        int lineCol = 0xFF4ADE80;
        for (int i = 0; i < count; i++) {
            int idx = (start + i) % n.probeHistory.length;
            float v = n.probeHistory[idx];
            int px = chartX + (int) ((float) i / (count - 1) * chartW);
            int py = chartY + chartH - (int) ((v - base) / range * chartH);
            py = Math.max(chartY, Math.min(chartY + chartH - 1, py));
            if (prevPX >= 0) drawLine(g, prevPX, prevPY, px, py, lineCol);
            prevPX = px; prevPY = py;
        }

        // 冻结指示
        if (n.probeFrozen) {
            drawStr(g, "FROZEN", chartX + chartW - 36, valY + 2, 0xFFFFAA00);
        }
    }

    /** Bresenham 逐像素画线（GuiGraphics 无直接画线 API）。 */
    private static void drawLine(GuiGraphics g, int x0, int y0, int x1, int y1, int col) {
        int dx = Math.abs(x1 - x0), dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1;
        int err = dx - dy;
        int x = x0, y = y0;
        int guard = 0;
        while (guard++ < 2000) {
            g.fill(x, y, x + 1, y + 1, col);
            if (x == x1 && y == y1) break;
            int e2 = 2 * err;
            if (e2 > -dy) { err -= dy; x += sx; }
            if (e2 < dx) { err += dx; y += sy; }
        }
    }

    /** 文字绘制（与 NodeRenderer.drawStr 同实现）。
     *  Text drawing (same implementation as NodeRenderer.drawStr). */
    private static void drawStr(GuiGraphics g, String t, float x, float y, int c) {
        g.drawString(Minecraft.getInstance().font, t, (int)x, (int)y, c, false);
    }
}
