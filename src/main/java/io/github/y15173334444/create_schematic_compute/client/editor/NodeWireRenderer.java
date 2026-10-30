package io.github.y15173334444.create_schematic_compute.client.editor;

import io.github.y15173334444.create_schematic_compute.graph.GraphNode;
import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
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
            float x1 = c2sX.apply(wx1), y1 = c2sY.apply(wy1);
            float x2 = c2sX.apply(wx2), y2 = c2sY.apply(wy2);
            // 音频连线用专属色（任一端点是 AUDIO 域引脚；域校验保证两端同域，双查只为稳妥）
            // Audio wires take the dedicated colour (either endpoint AUDIO-domain; the domain
            // guard already forces same-domain — checking both is just belt-and-braces).
            boolean audio = fn.type.outputDomain(c.fromPin) == NodeType.PinDomain.AUDIO
                || tn.type.inputDomain(c.toPin) == NodeType.PinDomain.AUDIO;
            bezier(g, x1, y1, x2, y2, audio ? NodeRenderer.CWA() : NodeRenderer.CW());
        }
    }

    void renderDraggingWire(GuiGraphics g, NodeGraph graph, int wireFromNode, int wireFromPin,
                                    float wireEndX, float wireEndY, float camX, float camY, float zoom,
                                    boolean targetAudio) {
        var fn = graph.findNode(wireFromNode);
        if(fn==null) return;
        // 源端锚点取自 WireGeometry（与渲染/命中同源）/ source anchor from WireGeometry (shared with render/hit-testing)
        var from = WireGeometry.fromAnchor(fn, wireFromPin, zoom);
        float x1 = c2sX.apply(from.x());
        float y1 = c2sY.apply(from.y());
        float x2 = c2sX.apply(wireEndX), y2 = c2sY.apply(wireEndY);
        // 拖拽预览同规则；拖拽源固定在输出侧，只查输出域（查输入域会误伤 AMP 这类
        // 「输入 0 = AUDIO、输出 0 = FLOAT」的节点）。频段源（BUS_IN/PRIVATE_IN）是任意域
        // 直通，引脚自身无固有域——预览色**以频段自身属性优先**（同步来的定义标志 ∨ 该引脚
        // 本地音频线），悬停目标域只作无属性时的兜底。
        // Drag preview follows the same rule; the drag source is always an output pin, so only
        // outputDomain applies. Band sources (BUS_IN/PRIVATE_IN) are any-domain passthroughs
        // with no intrinsic pin domain — the preview leads with the band's own property (the
        // synced definition flag ∨ the pin's local audio wire); the hover target only fills in
        // when the source has no property of its own.
        boolean sourceAudio = fn.type.outputDomain(wireFromPin) == NodeType.PinDomain.AUDIO;
        if (!sourceAudio && (fn.type == NodeType.BUS_IN || fn.type == NodeType.PRIVATE_IN)) {
            boolean synced = fn.type == NodeType.BUS_IN
                ? fn.signalBands != null && wireFromPin < fn.signalBands.size()
                    && io.github.y15173334444.create_schematic_compute.network.SignalBus
                        .isAudioBand(fn.signalName, fn.signalBands.get(wireFromPin))
                : io.github.y15173334444.create_schematic_compute.network.SignalBus
                    .isPrivateAudio(fn.signalName);
            sourceAudio = synced || graph.isBandPinAudio(fn.id, wireFromPin, true);
        }
        boolean audio = sourceAudio || targetAudio;
        bezier(g, x1, y1, x2, y2, audio ? NodeRenderer.CWA() : NodeRenderer.CWD());
    }

    private void bezier(GuiGraphics g, float x1, float y1, float x2, float y2, int c) {
        float dist = (float)Math.sqrt((x2-x1)*(x2-x1)+(y2-y1)*(y2-y1));
        int steps = Math.max(10, (int)(dist*0.15f));
        float px=x1, py=y1;
        for(int i=1; i<=steps; i++) {
            float t = i/(float)steps;
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
}
