package io.github.y15173334444.create_schematic_compute.client.editor;

import io.github.y15173334444.create_schematic_compute.graph.GraphNode;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;

/**
 * 连线曲线几何的单一真相源：两端引脚锚点（图空间）+ 三次贝塞尔取点。
 * 渲染（{@link NodeWireRenderer}）、远端拖拽预览（{@link GraphPresenceTracker}）与删除
 * 命中（{@code GraphEditor.hitConn}）共用同一份公式——命中端曾手抄一份端点公式、源端 X
 * 写死常量宽度（140），宽体源节点（FORMULA / DEBUG_* / 曲线节点，宽 240）的命中曲线
 * 整体左偏 100·zoom 像素，画线看得见却点不中（「对准了连线删不掉」）。
 * Single source of truth for wire curve geometry: the two pin anchors (graph space) plus
 * cubic-bezier evaluation. Rendering ({@link NodeWireRenderer}), remote drag preview
 * ({@link GraphPresenceTracker}) and delete hit-testing ({@code GraphEditor.hitConn}) share
 * this one formula — the hit side once hand-copied the endpoints with a hard-coded source
 * width (140), leaving the hit curve of wide source nodes (FORMULA / DEBUG_* / curve nodes,
 * 240 wide) shifted 100·zoom px left of the drawn wire: visible, yet un-hittable.
 */
final class WireGeometry {

    private WireGeometry() {}

    /** 图空间锚点。 / An anchor in graph space. */
    record Pt(float x, float y) {}

    /** 输出侧锚点（图空间）：X 在节点右缘（动态宽度 nw），Y 按引脚类型（BUS_IN 频段脚
     *  在编辑区动态行，其余在引脚行中心）。
     *  Source anchor (graph space): X at the node's right edge (dynamic width nw), Y per pin
     *  kind (BUS_IN band pins at their dynamic edit-area rows, others at the pin row centre). */
    static Pt fromAnchor(GraphNode fn, int fromPin, float zoom) {
        float x = fn.x + NodeRenderer.nw(fn);
        float y = fn.type == NodeType.BUS_IN
            ? fn.y + GraphEditor.bandPinY(fn, fromPin, zoom)
            : fn.y + NodeRenderer.HH + NodeRenderer.PH * (fn.functionalInputs() + fromPin) + NodeRenderer.PH / 2f;
        return new Pt(x, y);
    }

    /** 输入侧锚点（图空间）：X 在节点左缘，Y 按引脚类型（BUS_OUT 频段脚 / 功能引脚 /
     *  参数引脚）。
     *  Target anchor (graph space): X at the node's left edge, Y per pin kind (BUS_OUT band
     *  pin / functional input pin / parameter pin). */
    static Pt toAnchor(GraphNode tn, int toPin, float zoom) {
        float x = tn.x;
        float y;
        if (tn.type == NodeType.BUS_OUT) {
            y = tn.y + GraphEditor.bandPinY(tn, toPin, zoom);
        } else if (toPin < tn.functionalInputs()) {
            y = tn.y + NodeRenderer.HH + NodeRenderer.PH * toPin + NodeRenderer.PH / 2f;
        } else {
            int pi = toPin - tn.functionalInputs();
            y = tn.y + NodeRenderer.HH + NodeRenderer.PH * (tn.functionalInputs() + tn.outputs())
                + 4 / zoom + pi * 18 + 12;
        }
        return new Pt(x, y);
    }

    /** 三次贝塞尔取点 X（控制点水平外扩 |x2-x1|·0.4）。端点须在同一空间（图或屏幕）。
     *  Cubic bezier X (control points bulge horizontally by |x2-x1|·0.4). Both endpoints
     *  must live in the same space (graph or screen). */
    static float bezierX(float x1, float x2, float t) {
        float dx = Math.abs(x2 - x1) * 0.4f, inv = 1 - t;
        return inv*inv*inv*x1 + 3*inv*inv*t*(x1+dx) + 3*inv*t*t*(x2-dx) + t*t*t*x2;
    }

    /** 三次贝塞尔取点 Y（两端切线水平）。 / Cubic bezier Y (flat end tangents). */
    static float bezierY(float y1, float y2, float t) {
        float inv = 1 - t;
        return inv*inv*inv*y1 + 3*inv*inv*t*y1 + 3*inv*t*t*y2 + t*t*t*y2;
    }
}
