package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.graph.GraphNode;
import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 显示布局远端拖拽平滑回归测试：`SET_DISPLAY_LAYOUT` 落地权威坐标后，客户端按 smoothstep
 * 每帧推进 `layoutX/Y`（与节点模式 remote* 同款），插值期间 `advanceLayoutLerp` 必须报
 * 「仍在动画中」以便强制重建元素缓存；收敛时精确落到目标坐标并结束。
 * Regression: remote display-layout drags interpolate via smoothstep instead of jumping.
 */
class DisplayLayoutLerpTest {

    @Test
    @DisplayName("lerp converges exactly onto the target and reports done / 插值精确收敛到目标并报告结束")
    void lerpConvergesOntoTarget() {
        NodeGraph g = new NodeGraph();
        GraphNode n = g.addNode(NodeType.TEXT, 0, 0);
        n.layoutLerpT = 0f;
        n.layoutStartX = 0.2f; n.layoutStartY = 0.3f;
        n.layoutTargetX = 0.8f; n.layoutTargetY = 0.9f;

        assertTrue(MonitorDisplayEditor.advanceLayoutLerp(g), "first frame must report an active lerp");
        assertTrue(n.layoutX > 0.2f && n.layoutX < 0.8f, "x is between start and target after a frame");
        assertTrue(n.layoutY > 0.3f && n.layoutY < 0.9f, "y is between start and target after a frame");

        float prevX = n.layoutX;
        int guard = 0;
        while (MonitorDisplayEditor.advanceLayoutLerp(g)) {
            assertTrue(n.layoutX >= prevX, "x must advance monotonically toward the target");
            prevX = n.layoutX;
            assertTrue(++guard < 100, "lerp must terminate (0.12 step → ≤9 frames)");
        }
        assertEquals(0.8f, n.layoutX, 0f, "final frame lands exactly on the target x");
        assertEquals(0.9f, n.layoutY, 0f, "final frame lands exactly on the target y");
        assertFalse(MonitorDisplayEditor.advanceLayoutLerp(g), "after convergence the lerp is idle");
    }

    @Test
    @DisplayName("idle lerp leaves positions untouched / 未激活的插值不动坐标")
    void idleLerpIsANoOp() {
        NodeGraph g = new NodeGraph();
        GraphNode n = g.addNode(NodeType.TEXT, 0, 0);
        n.layoutLerpT = 1f;
        n.layoutX = 0.4f; n.layoutY = 0.5f;

        assertFalse(MonitorDisplayEditor.advanceLayoutLerp(g), "no active lerp → not animating");
        assertEquals(0.4f, n.layoutX, 0f, "position untouched");
        assertEquals(0.5f, n.layoutY, 0f, "position untouched");
    }

    @Test
    @DisplayName("empty graph reports idle / 空图报告空闲")
    void emptyGraphIsIdle() {
        assertFalse(MonitorDisplayEditor.advanceLayoutLerp(new NodeGraph()));
    }
}
