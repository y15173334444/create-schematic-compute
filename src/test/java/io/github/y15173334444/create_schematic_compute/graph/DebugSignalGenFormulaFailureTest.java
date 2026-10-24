package io.github.y15173334444.create_schematic_compute.graph;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F13 回归：DEBUG_SIGNAL_GEN 公式失败只告警一次（失效键 = 脚本文本），输出保持归零口径。
 * <p>
 * 修复前：公式编译器对残缺脚本是 lenient 的（如 "x +" 能编出坏 RPN），真正的炸点在求值期
 * （栈机 pop 空栈）——evalFormula 每 tick 抛一次、每 tick WARN 一次，日志无限频。
 * 修复后：GraphEvaluator 在编译期试算一次探测坏公式，失败态按节点缓存（节点级 transient
 * 旗标 + 失效键 = 告警时的脚本文本，对齐 {@code formulaShedWarned} 的口径）；只告警一次，
 * 后续 tick 复用失败态短路；脚本被编辑（缓存失效 op 置空 {@code debugFormulaRpn}）后自动
 * 重新编译，修好即恢复、旗标复位。本测试环境无 logback，告警可观测性经由节点级
 * {@code debugFormulaCompileWarned}/{@code debugFormulaWarnedScript} 降级标记断言。
 * <p>
 * F13 regression: a broken DEBUG_SIGNAL_GEN formula warns exactly once (invalidation key =
 * script text) and the output stays pinned to 0. Before the fix: the formula compiler is
 * lenient toward malformed scripts ("x +" still yields a broken RPN), the real crash point
 * is evaluation time (stack-machine pop on an empty stack) — evalFormula threw and WARNed
 * once per tick, forever. After the fix: GraphEvaluator probes the formula with a trial
 * evaluation at compile time, caches the failure state per node (transient flag + script
 * text as the invalidation key, aligned with the {@code formulaShedWarned} convention);
 * later ticks reuse the failure state as a short circuit; editing the script (the
 * invalidation op clears {@code debugFormulaRpn}) recompiles automatically and a fixed
 * formula recovers with the flag reset. This test environment has no logback, so the warn
 * is asserted via the node-level {@code debugFormulaCompileWarned}/
 * {@code debugFormulaWarnedScript} markers.
 */
class DebugSignalGenFormulaFailureTest {

    private NodeGraph graph;

    @BeforeEach
    void setUp() {
        graph = new NodeGraph();
    }

    /** 公式模式 + 指定 x 模式的信号节点（x = params[4]，绕开相位推进）。
     *  A formula-mode, input-driven signal node (x = params[4]; bypasses phase advancement). */
    private GraphNode formulaNode(String formula) {
        var node = graph.addNode(NodeType.DEBUG_SIGNAL_GEN, 0, 0);
        node.params[0] = DebugSignals.SET_FORMULA;
        node.params[1] = DebugSignals.OUT_INPUT;
        node.params[4] = 0.5f;
        node.formula = formula;
        return node;
    }

    private float evalOnce(GraphNode node) {
        var evaluator = new GraphEvaluator(graph);
        evaluator.evaluate(List.of(), Map.of(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));
        return evaluator.getNodeOutput(node.id, 0);
    }

    @Test
    @DisplayName("F13: 坏公式输出归 0,失败旗标亮起,失效键记录当前脚本文本")
    void brokenFormulaPinsZeroAndRaisesMarker() {
        GraphNode node = formulaNode("x +"); // lenient 编译成功,求值期 pop 空栈
        assertEquals(0f, evalOnce(node), 1e-6f, "a broken formula's output must stay pinned to 0");
        assertTrue(node.debugFormulaCompileWarned, "the once-per-script failure marker must be raised");
        assertEquals("x +", node.debugFormulaWarnedScript, "the invalidation key must record the failed script");
        assertNull(node.debugFormulaRpn, "a structurally broken RPN must not be cached");
    }

    @Test
    @DisplayName("F13: 后续 tick 复用失败态——旗标保持,输出仍为 0,坏 RPN 不复活")
    void laterTicksReuseFailureState() {
        GraphNode node = formulaNode("x +");
        evalOnce(node);
        assertEquals(0f, evalOnce(node), 1e-6f);
        assertEquals(0f, evalOnce(node), 1e-6f);
        assertTrue(node.debugFormulaCompileWarned, "the marker must stay up across ticks");
        assertEquals("x +", node.debugFormulaWarnedScript, "the invalidation key must not drift");
        assertNull(node.debugFormulaRpn, "no broken RPN may re-enter the cache");
    }

    @Test
    @DisplayName("F13: 脚本修好后(缓存失效 op 置空 rpn)自动恢复,旗标复位")
    void fixedFormulaRecoversAndResetsMarker() {
        GraphNode node = formulaNode("x +");
        assertEquals(0f, evalOnce(node), 1e-6f);
        assertTrue(node.debugFormulaCompileWarned);

        // 模拟公式编辑路径(SET_FORMULA op / 编辑器):脚本更新 + 编译缓存作废
        // Simulate the formula-edit path (SET_FORMULA op / editor): script updated + cache invalidated
        node.formula = "x*2";
        node.debugFormulaRpn = null;

        assertEquals(1.0f, evalOnce(node), 1e-6f, "x*2 at x=0.5 must evaluate normally after recovery");
        assertFalse(node.debugFormulaCompileWarned, "the failure marker must reset on recovery");
        assertNull(node.debugFormulaWarnedScript, "the invalidation key must be cleared on recovery");
        assertNotNull(node.debugFormulaRpn, "the valid RPN must be cached again");
    }

    @Test
    @DisplayName("F13: 好公式从头到尾不触发失败旗标")
    void validFormulaNeverRaisesMarker() {
        GraphNode node = formulaNode("x*2");
        assertEquals(1.0f, evalOnce(node), 1e-6f);
        assertEquals(1.0f, evalOnce(node), 1e-6f);
        assertFalse(node.debugFormulaCompileWarned);
        assertNull(node.debugFormulaWarnedScript);
        assertNotNull(node.debugFormulaRpn);
    }

    @Test
    @DisplayName("F13: 空白公式是合法的静默归零——不触发失败旗标")
    void blankFormulaStaysSilentlyZero() {
        GraphNode node = formulaNode("");
        assertEquals(0f, evalOnce(node), 1e-6f);
        assertFalse(node.debugFormulaCompileWarned,
            "a blank formula is a legitimate pinned-to-zero, not a failure — no marker");
        assertNull(node.debugFormulaWarnedScript);
    }
}
