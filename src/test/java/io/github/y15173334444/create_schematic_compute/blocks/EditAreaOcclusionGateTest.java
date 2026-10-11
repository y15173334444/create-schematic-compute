package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.graph.GraphNode;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 编辑区点击遮挡 z 序门控回归：只有严格高于 en 的候选才算遮挡者——en 缺席候选表时
 * （索引估算短于渲染面板），身后的节点不得被判成遮挡（长编辑区尾部看得见摸不着）。
 * Edit-area click occlusion z-gate regression: only candidates strictly above en count as
 * occluders — when en is absent from the candidate list (the index estimate fell short of
 * the rendered panel), nodes behind it must not be judged occluding (a long edit panel's
 * tail visible yet untouchable).
 */
class EditAreaOcclusionGateTest {

    private static GraphNode node(int id, NodeType type, int sortB) {
        var n = new GraphNode(id, type, 0, 0);
        n.sortB = sortB;
        return n;
    }

    @Test
    void nodeAboveIsOccluder_nodeBehindIsNot() {
        var en = node(1, NodeType.ADD, 5);
        assertTrue(GraphEditor.isOccluderInHitOrder(node(2, NodeType.ADD, 6), en),
            "higher sortB = visually on top = potential occluder");
        assertFalse(GraphEditor.isOccluderInHitOrder(node(3, NodeType.ADD, 4), en),
            "lower sortB = behind the panel — must never occlude");
    }

    @Test
    void selfIsNeverAnOccluder() {
        var en = node(1, NodeType.ADD, 5);
        assertFalse(GraphEditor.isOccluderInHitOrder(en, en));
    }

    @Test
    void commentNeverOccludesRegularNode() {
        var en = node(1, NodeType.ADD, 1);
        assertFalse(GraphEditor.isOccluderInHitOrder(node(2, NodeType.COMMENT, 9), en),
            "comments sit behind regular nodes regardless of sortB");
    }

    @Test
    void regularNodeOccludesCommentEditArea() {
        var en = node(1, NodeType.COMMENT, 9);
        assertTrue(GraphEditor.isOccluderInHitOrder(node(2, NodeType.ADD, 1), en),
            "regular nodes sit on top of comments regardless of sortB");
    }
}
