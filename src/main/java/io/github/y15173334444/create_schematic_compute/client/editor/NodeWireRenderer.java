package io.github.y15173334444.create_schematic_compute.client.editor;

import io.github.y15173334444.create_schematic_compute.graph.GraphNode;
import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import io.github.y15173334444.create_schematic_compute.graph.PinType;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 连线渲染器：节点间贝塞尔连线（含视口裁剪与 BUS 引脚动态定位）与拖拽中的悬连线，
 * 自 {@link NodeRenderer} 拆分（docs/gui-decomposition-plan.md 步骤 5 第三刀）。
 * The wire renderer: node-to-node bezier connections (viewport culling + dynamic BUS pin
 * positions) and the in-progress dragging wire, split out of {@link NodeRenderer}
 * (roadmap step 5, third cut).
 *
 * <p><b>行为零变更</b>：实现逐字搬迁，坐标映射与屏幕尺寸经构造注入；节点尺寸 / 引脚几何 /
 * 主题色仍取自 {@link NodeRenderer} 的静态成员（单一来源）。
 * <b>Behaviour-preserving</b>: bodies moved verbatim; the coordinate mappers and screen size
 * are constructor-injected, while node sizing / pin geometry / theme colours still come from
 * {@link NodeRenderer}'s static members (single source).</p>
 */
final class NodeWireRenderer {

    private final NodeRenderer.CoordMapper c2sX, c2sY;
    private final net.minecraft.client.gui.screens.Screen screen;

    NodeWireRenderer(NodeRenderer.CoordMapper c2sX, NodeRenderer.CoordMapper c2sY,
                     net.minecraft.client.gui.screens.Screen screen) {
        this.c2sX = c2sX; this.c2sY = c2sY;
        this.screen = screen;
    }

    void renderConnections(GuiGraphics g, NodeGraph graph, float camX, float camY, float zoom) {
        int sw = screen.width, sh = screen.height;
        // viewport in world coords (with generous margin for bezier curves that extend beyond endpoints)
        float vpLeft = -sw / (2f * zoom) - camX - 100 / zoom, vpRight = sw / (2f * zoom) - camX + 100 / zoom;
        float vpTop = -sh / (2f * zoom) - camY - 100 / zoom, vpBottom = sh / (2f * zoom) - camY + 100 / zoom;
        for(var c : graph.connections) {
            GraphNode fn = graph.findNode(c.fromId);
            GraphNode tn = graph.findNode(c.toId);
            if(fn==null||tn==null) continue;
            // 锚点取自 WireGeometry（命中与渲染同源）/ anchors from WireGeometry (shared with hit-testing)
            var from = WireGeometry.fromAnchor(fn, c.fromPin, zoom);
            var to = WireGeometry.toAnchor(tn, c.toPin, zoom);
            float wx1 = from.x(), wx2 = to.x();
            // cull: both endpoints outside viewport edge
            if ((wx1 < vpLeft && wx2 < vpLeft) || (wx1 > vpRight && wx2 > vpRight)) continue;
            float wy1 = from.y(), wy2 = to.y();
            if ((wy1 < vpTop && wy2 < vpTop) || (wy1 > vpBottom && wy2 > vpBottom)) continue;
            // 连线根由吸附的节点原点派生（抖动治理 JIT-1）：与引脚圆同点，拖动时锁死不爬。
            // Wire roots derive from the snapped node origins (jitter fix JIT-1): the same
            // point as the pin circles, no crawling during drags.
            float x1 = ScreenTransform.anchorX(c2sX, fn, from, zoom), y1 = ScreenTransform.anchorY(c2sY, fn, from, zoom);
            float x2 = ScreenTransform.anchorX(c2sX, tn, to, zoom), y2 = ScreenTransform.anchorY(c2sY, tn, to, zoom);
            // 连线按声明载荷类型三态着色（音频青/向量品红/浮点金；两端取音频或向量优先级高的）
            // Wires tint by declared payload type (teal=audio, magenta=vector, gold=float;
            // the higher-priority end wins).
            PinType wt = fn.outputType(c.fromPin);
            PinType wt2 = tn.inputType(c.toPin);
            if (wt == PinType.AUDIO || wt2 == PinType.AUDIO) wt = PinType.AUDIO;
            else if (wt == PinType.VEC3 || wt2 == PinType.VEC3) wt = PinType.VEC3;
            bezier(g, x1, y1, x2, y2, wt == PinType.AUDIO ? NodeRenderer.CWA()
                : wt == PinType.VEC3 ? NodeRenderer.CV() : NodeRenderer.CW());
        }
    }

    void renderDraggingWire(GuiGraphics g, NodeGraph graph, int wireFromNode, int wireFromPin,
                                    float wireEndX, float wireEndY, float camX, float camY, float zoom,
                                    PinType targetType) {
        var fn = graph.findNode(wireFromNode);
        if(fn==null) return;
        // 源端锚点取自 WireGeometry（与渲染/命中同源）/ source anchor from WireGeometry (shared with render/hit-testing)
        var from = WireGeometry.fromAnchor(fn, wireFromPin, zoom);
        float x1 = ScreenTransform.anchorX(c2sX, fn, from, zoom);
        float y1 = ScreenTransform.anchorY(c2sY, fn, from, zoom);
        // 自由端跟随光标，不吸附 / the free end follows the cursor, unsnapped
        float x2 = c2sX.apply(wireEndX), y2 = c2sY.apply(wireEndY);
        // 拖拽预览按声明载荷类型三态着色：源端声明类型优先（频段源含定义同步值），
        // 悬停目标类型只作源端为浮点时的兜底。
        // Drag preview tints by declared payload type: the source declaration leads
        // (band sources include the definition-synced value); the hover target only
        // fills in when the source is float.
        PinType source = fn.outputType(wireFromPin);
        if (fn.type == NodeType.BUS_IN || fn.type == NodeType.PRIVATE_IN) {
            PinType synced = fn.type == NodeType.BUS_IN
                ? (fn.signalBands != null && wireFromPin < fn.signalBands.size()
                    ? io.github.y15173334444.create_schematic_compute.network.SignalBus
                        .bandType(fn.signalName, fn.signalBands.get(wireFromPin))
                    : PinType.FLOAT)
                : io.github.y15173334444.create_schematic_compute.network.SignalBus
                    .privateType(fn.signalName);
            source = PinType.effective(source, synced);
        }
        if (source == PinType.FLOAT && targetType != null) source = targetType;
        bezier(g, x1, y1, x2, y2, source == PinType.AUDIO ? NodeRenderer.CWA()
            : source == PinType.VEC3 ? NodeRenderer.CV() : NodeRenderer.CWD());
    }

    /**
     * 连线绘制（渲染专用；命中侧走 WireGeometry 自采样，与本方法密度解耦）。
     * Wire drawing (render-only; the hit side samples via WireGeometry itself, decoupled
     * from this method's density).
     *
     * <p>三层成本控制（jstack 实测连线占 Render thread 78–83%，2026-11-14，见
     * docs/graph-editor-render-performance-plan.md §3.7）：</p>
     * <ol>
     * <li><b>bbox 裁剪</b>：X(t)/Y(t) 单调且控制点都在端点凸包内 → 曲线 bbox = 端点
     *     bbox，与视口（外扩 1px）分离的整条跳过；</li>
     * <li><b>视口内分段截取</b>：对触及的视口边二分反解 t（单调 → 二分 10 次达像素级），
     *     可见区间外不采样——横贯全图的行转移长线从「全量 ~480 步」降到「可见段」；</li>
     * <li><b>步数封顶</b>：步数按可见弦长分配、clamp(·, 6, 48)——1px 线宽下 48 段的
     *     折线感可接受（PERF-2 拍板 2026-11-14）。</li>
     * </ol>
     */
    private void bezier(GuiGraphics g, float x1, float y1, float x2, float y2, int c) {
        int sw = screen.width, sh = screen.height;
        if (Math.max(x1, x2) < -1f || Math.min(x1, x2) > sw + 1f
            || Math.max(y1, y2) < -1f || Math.min(y1, y2) > sh + 1f) return;
        float t0 = 0f, t1 = 1f;
        if (Math.min(x1, x2) < -1f) {
            if (x1 < x2) t0 = Math.max(t0, crossT(x1, x2, -1f, true)); else t1 = Math.min(t1, crossT(x1, x2, -1f, true));
        }
        if (Math.max(x1, x2) > sw + 1f) {
            if (x1 < x2) t1 = Math.min(t1, crossT(x1, x2, sw + 1f, true)); else t0 = Math.max(t0, crossT(x1, x2, sw + 1f, true));
        }
        if (Math.min(y1, y2) < -1f) {
            if (y1 < y2) t0 = Math.max(t0, crossT(y1, y2, -1f, false)); else t1 = Math.min(t1, crossT(y1, y2, -1f, false));
        }
        if (Math.max(y1, y2) > sh + 1f) {
            if (y1 < y2) t1 = Math.min(t1, crossT(y1, y2, sh + 1f, false)); else t0 = Math.max(t0, crossT(y1, y2, sh + 1f, false));
        }
        if (t1 <= t0) return;
        float ax = WireGeometry.bezierX(x1, x2, t0), ay = WireGeometry.bezierY(y1, y2, t0);
        float bx = WireGeometry.bezierX(x1, x2, t1), by = WireGeometry.bezierY(y1, y2, t1);
        float dxs = bx - ax, dys = by - ay;
        float chord = (float) Math.sqrt(dxs * dxs + dys * dys);
        int steps = Math.max(6, Math.min(48, (int) (chord * 0.15f)));
        float px = ax, py = ay;
        for (int i = 1; i <= steps; i++) {
            float t = t0 + (t1 - t0) * i / steps;
            // 曲线取点与命中同源 / curve sampling shared with hit-testing
            float nx = WireGeometry.bezierX(x1, x2, t);
            float ny = WireGeometry.bezierY(y1, y2, t);
            int sx = (int)px, sy = (int)py, ex = (int)nx, ey = (int)ny;
            int sdx = ex - sx, sdy = ey - sy;
            int segLen = Math.max(Math.abs(sdx), Math.abs(sdy));
            if (segLen == 0) {
                g.fill(sx, sy, sx + 1, sy + 1, c);
            } else {
                // batch same-row pixels into horizontal runs → 1-pixel-thick line at any slope
                int runStart = sx, runY = sy;
                for (int j = 1; j <= segLen; j++) {
                    int cx = sx + sdx * j / segLen;
                    int cy = sy + sdy * j / segLen;
                    if (cy != runY || j == segLen) {
                        int endX = (j == segLen) ? ex : (sx + sdx * (j - 1) / segLen);
                        int x1_ = Math.min(runStart, endX), x2_ = Math.max(runStart, endX);
                        g.fill(x1_, runY, x2_ + 1, runY + 1, c);
                        runStart = cx;
                        runY = cy;
                    }
                }
            }
            px=nx; py=ny;
        }
    }

    /**
     * 反解单调贝塞尔与视口边的交点参数 t：X(t)/Y(t) 端点插值且单调（控制点都在端点凸包
     * 内）→ 二分 10 次即像素级。target 落在曲线值域外时按方向回 0/1——与 bbox 裁剪合流
     * 后只发生在「曲线未触及该边」的情形，此时该边不产生截取。
     * Invert the monotonic bezier's crossing of a viewport edge: X(t)/Y(t) are
     * endpoint-interpolating and monotonic (control points inside the endpoint convex hull),
     * so 10 bisection steps reach pixel precision. A target outside the curve's range returns
     * 0/1 by direction — combined with the bbox test this only happens when the curve never
     * reaches that edge, i.e. the edge imposes no truncation.
     */
    /** 包级可见：headless 锚测直接复述「单调贝塞尔 × 视口边 → t」的契约。
     *  Package-visible: the headless anchor test restates the monotone-bezier × viewport-edge contract directly. */
    static float crossT(float a, float b, float target, boolean xAxis) {
        float lo = Math.min(a, b), hi = Math.max(a, b);
        if (target <= lo) return a <= b ? 0f : 1f;
        if (target >= hi) return a <= b ? 1f : 0f;
        float t0 = 0f, t1 = 1f;
        boolean increasing = b > a;
        for (int i = 0; i < 10; i++) {
            float mid = (t0 + t1) * 0.5f;
            // X 与 Y 是两条不同的单调曲线（X 控制点外扩 0.4、Y 平切线）——反解必须用同一条。
            // X and Y are two different monotonic curves (X bulges 0.4, Y has flat tangents) —
            // the inversion must evaluate the same curve being clipped.
            float v = xAxis ? WireGeometry.bezierX(a, b, mid) : WireGeometry.bezierY(a, b, mid);
            if ((v < target) == increasing) t0 = mid; else t1 = mid;
        }
        return (t0 + t1) * 0.5f;
    }
}
