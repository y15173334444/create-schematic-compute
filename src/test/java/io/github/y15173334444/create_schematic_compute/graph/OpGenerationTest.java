package io.github.y15173334444.create_schematic_compute.graph;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for the generation-bump discipline in {@link OpExecutor}.
 * 对 {@link OpExecutor} 代际递增纪律的回归测试。
 *
 * <p>Visual-only ops (MOVE_NODE, SET_ZORDER, SET_COMMENT_*) must NOT bump the graph
 * generation: bumping made the server full-recompile on every drag op (up to 20Hz,
 * recompileEvaluatorFull → runtimeState.clear() wipes DELAY/flipflop/pulse/PID
 * sequential state) and made the client rebuild every expanded EditState. Eval-
 * affecting ops must still bump.</p>
 * <p>纯视觉 op（MOVE_NODE、SET_ZORDER、SET_COMMENT_*）不得递增图代际：此前每次拖拽 op
 * （最高 20Hz）都触发服务端全量重编译（recompileEvaluatorFull → runtimeState.clear()
 * 清空 DELAY/flipflop/pulse/PID 时序状态），并使客户端重建全部展开节点的编辑区。
 * 影响求值的 op 仍必须递增。</p>
 *
 * <p>GraphOp is constructed directly with null ItemStack/BlockPos/UUID — the exercised
 * branches never touch those fields. The factory methods reference ItemStack.EMPTY,
 * which NPEs outside the game environment (see GraphNodeImageSizeTest).</p>
 */
class OpGenerationTest {

    private NodeGraph graph;

    @BeforeEach
    void setUp() {
        graph = new NodeGraph();
    }

    private int gen() { return graph.graphGeneration; }

    /** Build a GraphOp whose fields are only what the exercised branch reads.
     *  构造只含被测分支所需字段的 GraphOp。 */
    private static GraphOp op(OpType type, int targetId, float x, float y,
                              int paramIndex, float paramValue, String stringValue,
                              int sortB, List<String> bands,
                              int colorBg, int colorBorder, int colorText) {
        return new GraphOp(type, null, -1, targetId, 0, null, x, y,
            0, 0, 0, 0, paramIndex, paramValue, stringValue,
            colorBg, colorBorder, colorText, sortB, bands, 0, 0, 0,
            null, 0L, null, 0, null);
    }

    // ══════════════ Visual-only ops must NOT bump / 纯视觉 op 不 bump ══════════════

    @Test
    @DisplayName("MOVE_NODE: lands coordinates but does not bump generation")
    void testMoveNodeDoesNotBumpGeneration() {
        GraphNode n = graph.addNode(NodeType.CONST, 10, 20);
        int g0 = gen();
        OpExecutor.apply(graph, op(OpType.MOVE_NODE, n.id, 100f, 200f, 0, 0f, null, 0, null, 0, 0, 0));
        assertEquals(100f, n.x, "MOVE_NODE must still land x");
        assertEquals(200f, n.y, "MOVE_NODE must still land y");
        assertEquals(g0, gen(),
            "MOVE_NODE must not bump generation — bumping recompiles the evaluator and wipes sequential state on every drag op");
    }

    @Test
    @DisplayName("SET_ZORDER: updates stacking but does not bump generation")
    void testSetZOrderDoesNotBumpGeneration() {
        GraphNode n = graph.addNode(NodeType.CONST, 0, 0);
        int g0 = gen();
        OpExecutor.apply(graph, op(OpType.SET_ZORDER, n.id, 0, 0, 0, 0f, null, 42, null, 0, 0, 0));
        assertEquals(42, n.sortB);
        assertEquals(g0, gen());
    }

    @Test
    @DisplayName("SET_COMMENT_TEXT/COLORS/SIZE: update visuals but do not bump generation")
    void testCommentOpsDoNotBumpGeneration() {
        GraphNode c = graph.addNode(NodeType.COMMENT, 0, 0);
        int g0 = gen();

        OpExecutor.apply(graph, op(OpType.SET_COMMENT_TEXT, c.id, 0, 0, 0, 0f, "note", 0, null, 0, 0, 0));
        assertEquals("note", c.displayText);

        OpExecutor.apply(graph, op(OpType.SET_COMMENT_COLORS, c.id, 0, 0, 0, 0f, null, 0, null, 0xFF0000, 0x00FF00, 0x0000FF));
        assertEquals(0xFF0000, c.commentBgColor);
        assertEquals(0x00FF00, c.commentBorderColor);
        assertEquals(0x0000FF, c.commentTextColor);

        OpExecutor.apply(graph, op(OpType.SET_COMMENT_SIZE, c.id, 200f, 120f, 0, 0f, null, 0, null, 0, 0, 0));
        assertEquals(200f, c.commentWidth);
        assertEquals(120f, c.commentHeight);

        assertEquals(g0, gen(), "comment ops are pure visuals — must not bump");
    }

    // ══════════════ Eval-affecting ops must still bump / 影响求值的 op 仍要 bump ══════════════

    @Test
    @DisplayName("SET_PARAM bumps generation (feeds the evaluator)")
    void testSetParamStillBumps() {
        GraphNode n = graph.addNode(NodeType.CONST, 0, 0);
        int g0 = gen();
        OpExecutor.apply(graph, op(OpType.SET_PARAM, n.id, 0, 0, 0, 3.5f, null, 0, null, 0, 0, 0));
        assertEquals(3.5f, n.params[0]);
        assertTrue(gen() > g0, "SET_PARAM changes evaluation inputs — must bump");
    }

    @Test
    @DisplayName("SET_FORMULA bumps generation (re-parses the script)")
    void testSetFormulaStillBumps() {
        GraphNode n = graph.addNode(NodeType.FORMULA, 0, 0);
        int g0 = gen();
        OpExecutor.apply(graph, op(OpType.SET_FORMULA, n.id, 0, 0, 0, 0f, "x + 1", 0, null, 0, 0, 0));
        assertEquals("x + 1", n.formula);
        assertTrue(gen() > g0, "SET_FORMULA changes evaluation inputs — must bump");
    }

    @Test
    @DisplayName("SET_TEXT_COLOR still bumps (TEXT node is monitor display content)")
    void testSetTextColorStillBumps() {
        GraphNode n = graph.addNode(NodeType.TEXT, 0, 0);
        int g0 = gen();
        OpExecutor.apply(graph, op(OpType.SET_TEXT_COLOR, n.id, 0, 0, 0, 0f, null, 0, null, 0, 0, 0x123456));
        assertEquals(0x123456, n.textColor);
        assertTrue(gen() > g0, "SET_TEXT_COLOR refreshes display-mode cache — must bump");
    }

    @Test
    @DisplayName("SET_DISPLAY_LAYOUT still bumps (display-mode cache invalidation)")
    void testSetDisplayLayoutStillBumps() {
        GraphNode n = graph.addNode(NodeType.IMAGE, 0, 0);
        int g0 = gen();
        OpExecutor.apply(graph, op(OpType.SET_DISPLAY_LAYOUT, n.id, 12f, 34f, 0, 0f, null, 0, null, 0, 0, 0));
        assertEquals(12f, n.layoutX);
        assertEquals(34f, n.layoutY);
        assertTrue(gen() > g0, "SET_DISPLAY_LAYOUT refreshes display-mode cache — must bump");
    }

    @Test
    @DisplayName("SET_CTRL_POINTS still bumps (feeds DebugSignals evaluation)")
    void testSetCtrlPointsStillBumps() {
        GraphNode n = graph.addNode(NodeType.DEBUG_SIGNAL_GEN, 0, 0);
        int g0 = gen();
        OpExecutor.apply(graph, op(OpType.SET_CTRL_POINTS, n.id, 0, 0, 0, 0f, "0.0,0.0;0.5,1.0;1.0,0.0", 0, null, 0, 0, 0));
        assertNotNull(n.debugCtrlX);
        assertEquals(3, n.debugCtrlX.length);
        assertEquals(0.5f, n.debugCtrlX[1], 0.0001f);
        assertTrue(gen() > g0, "control points change DEBUG_SIGNAL_GEN output — must bump");
    }

    @Test
    @DisplayName("SET_BANDS still bumps (re-maps BUS pins via rebuildInputCache)")
    void testSetBandsStillBumps() {
        GraphNode n = graph.addNode(NodeType.BUS_OUT, 0, 0);
        int g0 = gen();
        OpExecutor.apply(graph, op(OpType.SET_BANDS, n.id, 0, 0, 0, 0f, null, 0, List.of("a", "b"), 0, 0, 0));
        assertEquals(2, n.signalBands.size());
        assertTrue(gen() > g0, "SET_BANDS changes pin structure — must bump");
    }

    // ══════════════ Baseline: structural changes still bump / 结构变更仍 bump（基线） ══════════════

    @Test
    @DisplayName("Structural changes still bump (MOVE must not be conflated with them)")
    void testStructuralOpsStillBump() {
        GraphNode a = graph.addNode(NodeType.CONST, 0, 0);
        GraphNode b = graph.addNode(NodeType.ADD, 100, 0);
        int g0 = gen();
        graph.addConnection(a.id, 0, b.id, 0);
        assertTrue(gen() > g0, "topology changes must bump generation");
    }
}
