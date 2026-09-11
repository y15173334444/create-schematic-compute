package io.github.y15173334444.create_schematic_compute.blocks;

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
            // cull: both endpoints outside viewport edge
            float wx1 = fn.x + NodeRenderer.nw(fn), wx2 = tn.x;
            if ((wx1 < vpLeft && wx2 < vpLeft) || (wx1 > vpRight && wx2 > vpRight)) continue;
            // 输出引脚Y（BUS_IN 编辑区引脚动态计算）
            float wy1;
            if (fn.type == NodeType.BUS_IN) {
                wy1 = fn.y + GraphEditor.bandPinY(fn, c.fromPin, zoom);
            } else {
                wy1 = fn.y + NodeRenderer.HH + NodeRenderer.PH*(fn.functionalInputs() + c.fromPin) + NodeRenderer.PH/2f;
            }
            // 输入引脚Y（BUS_OUT 编辑区引脚动态计算）
            float wy2;
            if (tn.type == NodeType.BUS_OUT) {
                wy2 = tn.y + GraphEditor.bandPinY(tn, c.toPin, zoom);
            } else if (c.toPin < tn.functionalInputs()) {
                wy2 = tn.y + NodeRenderer.HH + NodeRenderer.PH*c.toPin + NodeRenderer.PH/2f;
            } else {
                int paramIdx = c.toPin - tn.functionalInputs();
                wy2 = tn.y + NodeRenderer.HH + NodeRenderer.PH*(tn.functionalInputs() + tn.outputs()) + 4/zoom + paramIdx*18 + 12;
            }
            if ((wy1 < vpTop && wy2 < vpTop) || (wy1 > vpBottom && wy2 > vpBottom)) continue;
            float x1 = c2sX.apply(wx1), y1 = c2sY.apply(wy1);
            float x2 = c2sX.apply(wx2), y2 = c2sY.apply(wy2);
            bezier(g, x1, y1, x2, y2, NodeRenderer.CW());
        }
    }

    void renderDraggingWire(GuiGraphics g, NodeGraph graph, int wireFromNode, int wireFromPin,
                                    float wireEndX, float wireEndY, float camX, float camY, float zoom) {
        var fn = graph.findNode(wireFromNode);
        if(fn==null) return;
        float y1;
        if (fn.type == NodeType.BUS_IN) {
            y1 = c2sY.apply(fn.y + GraphEditor.bandPinY(fn, wireFromPin, zoom));
        } else {
            y1 = c2sY.apply(fn.y+NodeRenderer.HH+NodeRenderer.PH*(fn.functionalInputs() + wireFromPin)+NodeRenderer.PH/2f);
        }
        float x1 = c2sX.apply(fn.x + NodeRenderer.nw(fn));
        float x2 = c2sX.apply(wireEndX), y2 = c2sY.apply(wireEndY);
        bezier(g, x1, y1, x2, y2, NodeRenderer.CWD());
    }

    private void bezier(GuiGraphics g, float x1, float y1, float x2, float y2, int c) {
        float dx = Math.abs(x2-x1)*0.4f;
        float dist = (float)Math.sqrt((x2-x1)*(x2-x1)+(y2-y1)*(y2-y1));
        int steps = Math.max(10, (int)(dist*0.15f));
        float px=x1, py=y1;
        for(int i=1; i<=steps; i++) {
            float t = i/(float)steps, inv = 1-t;
            float nx=inv*inv*inv*x1 + 3*inv*inv*t*(x1+dx) + 3*inv*t*t*(x2-dx) + t*t*t*x2;
            float ny=inv*inv*inv*y1 + 3*inv*inv*t*y1 + 3*inv*t*t*y2 + t*t*t*y2;
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
