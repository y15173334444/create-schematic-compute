package io.github.y15173334444.create_schematic_compute.graph;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * IMAGE_SEQUENCE 帧列表 op（REMOVE_IMAGE_FRAME / MOVE_IMAGE_FRAME）经 OpExecutor 的应用行为。
 * <p>GraphOp 直接用 null ItemStack/BlockPos 构造（被测分支不触碰这些字段），
 * 与 {@link OpGenerationTest} 同法。</p>
 * Frame-list ops (REMOVE_IMAGE_FRAME / MOVE_IMAGE_FRAME) applied via OpExecutor.
 * GraphOp is built directly with null ItemStack/BlockPos — the exercised branches never
 * touch those fields (same technique as OpGenerationTest).
 */
class ImageFrameOpsTest {

    private NodeGraph graph;
    private GraphNode seq;

    @BeforeEach
    void setUp() {
        graph = new NodeGraph();
        seq = graph.addNode(NodeType.IMAGE_SEQUENCE, 0, 0);
        seq.imageSequenceFrames = new ArrayList<>();
        seq.imageSequenceFrames.add(new int[]{0xFF0000FF}); // frame 0: blue
        seq.imageSequenceFrames.add(new int[]{0xFF00FF00}); // frame 1: green
        seq.imageSequenceFrames.add(new int[]{0xFFFF0000}); // frame 2: red
        seq.imagePixels = seq.imageSequenceFrames.get(0);
    }

    /** paramIndex=frameIndex（REMOVE）/ from（MOVE），keyIndex=to（MOVE）。 */
    private static GraphOp frameOp(OpType type, int nodeId, int paramIndex, int keyIndex) {
        return new GraphOp(type, null, -1, nodeId,
            0, null, 0f, 0f, 0, 0, 0, 0, paramIndex, 0f,
            null, 0, 0, 0, 0, null, keyIndex, 0, 0,
            null, 0L, null, 0, null);
    }

    @Test
    @DisplayName("REMOVE_IMAGE_FRAME: removes the frame at index and re-links imagePixels")
    void testRemoveFrame() {
        int g0 = graph.graphGeneration;
        OpExecutor.apply(graph, frameOp(OpType.REMOVE_IMAGE_FRAME, seq.id, 1, 0));
        assertEquals(2, seq.imageSequenceFrames.size());
        assertEquals(0xFF0000FF, seq.imageSequenceFrames.get(0)[0]);
        assertEquals(0xFFFF0000, seq.imageSequenceFrames.get(1)[0]);
        assertSame(seq.imageSequenceFrames.get(0), seq.imagePixels,
            "after removal imagePixels must point at a live frame");
        assertTrue(graph.graphGeneration > g0, "frame removal changes display content — must bump");
    }

    @Test
    @DisplayName("REMOVE_IMAGE_FRAME: last frame is cleared to blank, list never empties")
    void testRemoveLastFrameKeepsOne() {
        // 3 帧删 2 次剩 1 帧；再删第 3 次时最后一帧被清空而非删除
        // three removals: two shrink the list, the third clears the single remaining frame
        OpExecutor.apply(graph, frameOp(OpType.REMOVE_IMAGE_FRAME, seq.id, 0, 0));
        OpExecutor.apply(graph, frameOp(OpType.REMOVE_IMAGE_FRAME, seq.id, 0, 0));
        assertEquals(1, seq.imageSequenceFrames.size(), "two removals leave exactly one frame");
        OpExecutor.apply(graph, frameOp(OpType.REMOVE_IMAGE_FRAME, seq.id, 0, 0));
        assertEquals(1, seq.imageSequenceFrames.size());
        assertEquals(0x00000000, seq.imageSequenceFrames.get(0)[0],
            "the single remaining frame is cleared to transparent");
    }

    @Test
    @DisplayName("REMOVE_IMAGE_FRAME: out-of-range index clamps safely")
    void testRemoveFrameClamps() {
        OpExecutor.apply(graph, frameOp(OpType.REMOVE_IMAGE_FRAME, seq.id, 99, 0));
        assertEquals(2, seq.imageSequenceFrames.size());
    }

    @Test
    @DisplayName("MOVE_IMAGE_FRAME: reorders the frame list (remove-then-insert)")
    void testMoveFrame() {
        int g0 = graph.graphGeneration;
        OpExecutor.apply(graph, frameOp(OpType.MOVE_IMAGE_FRAME, seq.id, 0, 2));
        assertEquals(3, seq.imageSequenceFrames.size());
        assertEquals(0xFF00FF00, seq.imageSequenceFrames.get(0)[0]);
        assertEquals(0xFFFF0000, seq.imageSequenceFrames.get(1)[0]);
        assertEquals(0xFF0000FF, seq.imageSequenceFrames.get(2)[0]);
        assertTrue(graph.graphGeneration > g0, "frame reorder changes display content — must bump");
    }

    @Test
    @DisplayName("MOVE_IMAGE_FRAME: same-index and out-of-range are safe no-ops")
    void testMoveFrameNoOp() {
        OpExecutor.apply(graph, frameOp(OpType.MOVE_IMAGE_FRAME, seq.id, 1, 1));
        OpExecutor.apply(graph, frameOp(OpType.MOVE_IMAGE_FRAME, seq.id, 0, 9));
        OpExecutor.apply(graph, frameOp(OpType.MOVE_IMAGE_FRAME, seq.id, 9, 0));
        assertEquals(3, seq.imageSequenceFrames.size());
    }
}
