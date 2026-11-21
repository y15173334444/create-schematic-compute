package io.github.y15173334444.create_schematic_compute.client.editor;

import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.y15173334444.create_schematic_compute.graph.GraphNode;
import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import io.github.y15173334444.create_schematic_compute.graph.PinType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;

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
    /** 本渲染器的连线批（复用实例，begin/end 按调用配对）。 / This renderer's wire batch
     *  (reused instance; begin/end paired per call). */
    private final Batch batch = new Batch();

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
        // A=2 全段一个批：所有连线四边形攒进同一 gui 批，段末一次 endBatch（PERF-2 后手，
        // 方案 §3.8 取证后可行——逐 fill 的自动 endBatch ~5 万次/帧是实测主开销）。
        // One batch for the whole A=2 section: every wire quad accumulates into the same
        // gui batch with a single endBatch at the end (the PERF-2 follow-on, viable after
        // the §3.8 audit — the per-fill auto endBatch ~50k/frame was the measured cost).
        batch.begin(g);
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
            bezier(batch, zoom, x1, y1, x2, y2, wt == PinType.AUDIO ? NodeRenderer.CWA()
                : wt == PinType.VEC3 ? NodeRenderer.CV() : NodeRenderer.CW());
        }
        batch.end();
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
        batch.begin(g);
        bezier(batch, zoom, x1, y1, x2, y2, source == PinType.AUDIO ? NodeRenderer.CWA()
            : source == PinType.VEC3 ? NodeRenderer.CV() : NodeRenderer.CWD());
        batch.end();
    }

    /**
     * 连线绘制（渲染专用；命中侧走 WireGeometry 自采样，与本方法密度解耦）。
     * Wire drawing (render-only; the hit side samples via WireGeometry itself, decoupled
     * from this method's density).
     *
     * <p>四层成本控制（jstack 实测连线占 Render thread 78–83%，2026-11-14，见
     * docs/graph-editor-render-performance-plan.md §3.7；第四层为 2026-11-21 §3.8 取证后落地）：</p>
     * <ol>
     * <li><b>bbox 裁剪</b>：X(t)/Y(t) 单调且控制点都在端点凸包内 → 曲线 bbox = 端点
     *     bbox，与视口（外扩 1px）分离的整条跳过；</li>
     * <li><b>视口内分段截取</b>：对触及的视口边二分反解 t（单调 → 二分 10 次达像素级），
     *     可见区间外不采样——横贯全图的行转移长线从「全量 ~480 步」降到「可见段」；</li>
     * <li><b>步数封顶</b>：步数按可见弦长分配、clamp(·, 6, 48)——折线感可接受
     *     （PERF-2 拍板 2026-11-14）；</li>
     * <li><b>批提交 + pose 局部宽</b>：每步一段旋转四边形攒进调用方的 {@link Batch}
     *     （A=2 全段一次 endBatch，替代逐 fill 自动 endBatch）；线宽 = max(1 局部像素 × zoom,
     *     0.5 屏幕像素)——pose 局部口径沿边框批 2，另带 0.5px 屏幕下限防低缩放亚像素断线
     *     （2026-11-21 实测拍板）。
     *     旋转四边形是 pose 局部宽的必要形态——逐行 DDA 打点表达不了亚像素宽，
     *     zoom 1 下的斜线观感从「行阶梯增厚」变为精确 1px。</li>
     * </ol>
     */
    private void bezier(Batch batch, float zoom, float x1, float y1, float x2, float y2, int c) {
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
        // 步数按 16px 弦长桶派生（抖动治理批 1）：端点 1px 步进不改步数，避免拖动中整线
        // 重采样蠕动。
        // Steps come off a 16px-bucketed chord (jitter batch 1): a 1px endpoint step cannot
        // change the step count, so the wire never re-samples wholesale mid-drag.
        int steps = Math.max(6, Math.min(48, (int) (((int) chord / 16 * 16) * 0.15f)));
        float half = wireHalfWidth(zoom);
        float px = ax, py = ay;
        for (int i = 1; i <= steps; i++) {
            float t = t0 + (t1 - t0) * i / steps;
            // 曲线取点与命中同源 / curve sampling shared with hit-testing
            float nx = WireGeometry.bezierX(x1, x2, t);
            float ny = WireGeometry.bezierY(y1, y2, t);
            batch.quad(px, py, nx, ny, half, c);
            px = nx; py = ny;
        }
    }

    /**
     * 连线段四边形几何（纯函数，headless 锚测）：a→b 线段、半宽 h 的旋转四边形，
     * 两端各沿方向外扩 h——相邻段共享端点时接头互相覆盖、无豁口；a==b 退化为
     * 边长 2h 的方点（零长步保持可见性）。写出 4 角到 out（x,y × 4，环序）——
     * out 用调用方复用的数组（静态 scratch），热路径零分配（条目 14 同纪律）。
     * Segment quad geometry (pure, headless-anchor): the rotated quad for the a→b
     * segment at half width h, extended by h along its direction at both ends so
     * chained segments sharing an endpoint overlap and never gap; a==b degenerates to
     * a 2h dot (keeps zero-length steps visible). Writes the 4 corners into out
     * (x,y × 4, ring order) — out is a caller-reused array (a static scratch), zero
     * allocation on the hot path (entry 14's discipline).
     */
    static void segmentQuad(float ax, float ay, float bx, float by, float half, float[] out) {
        float dx = bx - ax, dy = by - ay;
        float len2 = dx * dx + dy * dy;
        if (len2 < 1e-6f) {
            out[0] = ax - half; out[1] = ay - half;
            out[2] = ax + half; out[3] = ay - half;
            out[4] = ax + half; out[5] = ay + half;
            out[6] = ax - half; out[7] = ay + half;
            return;
        }
        float len = (float) Math.sqrt(len2);
        float ux = dx / len, uy = dy / len;
        float px = -uy * half, py = ux * half; // 垂直向 / perpendicular
        float ex = ux * half, ey = uy * half;  // 端头外扩 / end extension
        out[0] = ax - ex + px; out[1] = ay - ey + py;
        out[2] = bx + ex + px; out[3] = by + ey + py;
        out[4] = bx + ex - px; out[5] = by + ey - py;
        out[6] = ax - ex - px; out[7] = ay - ey - py;
    }

    /**
     * 连线半宽（屏幕宽 = max(1 局部像素 × zoom, 0.5 屏幕像素) 的对半）——pose 局部口径
     * 沿节点边框批 2（zoom 1 恰为 1px），但带 0.5px **屏幕下限**：纯 pose 局部在低缩放
     * 退化到亚像素、像素覆盖间歇命中读作断线（2026-11-21 实测拍板「有点细」）——
     * 下限让低缩放线保底半像素（覆盖 ~50%，连续感恢复），zoom ≥ 0.5 观感不变。
     * 下限值拍板调整只改这一个常量。
     * Wire half width (screen width = max(1 local pixel × zoom, 0.5 screen pixels), halved)
     * — the pose-local rule follows the node border (exactly 1px at zoom 1) with a 0.5px
     * screen floor: pure pose-local degrades below half a pixel at low zoom where pixel
     * coverage hits intermittently and reads as broken lines (live-tested 2026-11-21,
     * "a bit thin"). The floor keeps low-zoom wires at half a pixel (~50% coverage,
     * continuous again); zoom ≥ 0.5 is unchanged. Rulings adjust this one constant.
     */
    static float wireHalfWidth(float zoom) {
        return Math.max(zoom, 0.5f) * 0.5f;
    }

    /**
     * 单条连线（临场拖拽预览等）：一批一收、自带步数，无视口截取（单条无需）。
     * A single wire (presence drag previews): one batch, caller-supplied step count,
     * no viewport truncation (unnecessary for a single curve).
     */
    static void drawWire(GuiGraphics g, float x1, float y1, float x2, float y2, float zoom, int steps, int c) {
        Batch b = new Batch();
        b.begin(g);
        float half = wireHalfWidth(zoom);
        float px = x1, py = y1;
        for (int i = 1; i <= steps; i++) {
            float t = i / (float) steps;
            float nx = WireGeometry.bezierX(x1, x2, t);
            float ny = WireGeometry.bezierY(y1, y2, t);
            b.quad(px, py, nx, ny, half, c);
            px = nx; py = ny;
        }
        b.end();
    }

    /**
     * 连线批：四边形攒进一个 {@link RenderType#gui()} 缓冲、{@link #end()} 一次收口
     * （方案 §3.8：GuiGraphics 逐图元自动 endBatch，批提交必须绕开 fill 包装直用
     * 公共的 {@code bufferSource()}——同类型连续请求在 QUADS 模式下续攒、不触发收口）。
     * 矩阵取首四边形时的 pose 栈顶——A=2 段内无 pose push/pop，全段共用一份。
     * The wire batch: quads accumulate into one {@link RenderType#gui()} buffer and
     * {@link #end()} flushes once (plan §3.8: GuiGraphics auto-flushes per primitive, so
     * batching must bypass the fill wrappers via the public {@code bufferSource()} —
     * consecutive same-type requests consolidate in QUADS mode instead of flushing).
     * The matrix is captured at the first quad — no pose push/pop happens inside the A=2
     * section, so the whole section shares one matrix.
     */
    static final class Batch {
        private GuiGraphics g;
        private VertexConsumer vc;
        private Matrix4f mat;
        /** 段四边形 scratch（渲染线程单线程复用，热路径零分配）。 / Segment quad scratch
         *  (render-thread-only reuse, zero allocation on the hot path). */
        private final float[] scratch = new float[8];

        void begin(GuiGraphics g) {
            this.g = g;
            this.vc = null;
            this.mat = null;
        }

        void quad(float ax, float ay, float bx, float by, float half, int color) {
            if (vc == null) {
                vc = g.bufferSource().getBuffer(RenderType.gui());
                mat = g.pose().last().pose();
            }
            segmentQuad(ax, ay, bx, by, half, scratch);
            vc.addVertex(mat, scratch[0], scratch[1], 0).setColor(color);
            vc.addVertex(mat, scratch[2], scratch[3], 0).setColor(color);
            vc.addVertex(mat, scratch[4], scratch[5], 0).setColor(color);
            vc.addVertex(mat, scratch[6], scratch[7], 0).setColor(color);
        }

        void end() {
            if (vc != null) {
                g.bufferSource().endBatch(RenderType.gui());
            }
            vc = null;
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
