package io.github.y15173334444.create_schematic_compute.graph;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * K6 预算兜底接线回归（2026-10-31 拍板：保持 + 升级冻结）：
 * <ul>
 *   <li>{@code deadlineExhausted} 的 0=未接线守卫（单测/测试环境免护栏）；</li>
 *   <li>表达式栈机 256-op 采样超 deadline 抛 {@code DeadlineShedException}（闭式巨脚本
 *       的紧急 shed——刀 1 javadoc 承诺的兑现）；</li>
 *   <li>解释器把 deadline shed 转为 {@code fromDeadline} 挂起（carrier 保住，spread 续算）；</li>
 *   <li>公平规则：节点自身超份额（执行墙钟 > slice）连续 20 tick → MAX_ITER 同款冻结；
 *       聚合压力受害者（自身在份额内）只保持、永不冻结；</li>
 *   <li>DEBUG 试算/曲线的瞬态豁免（deadline 打断 ≠ 坏公式）。</li>
 * </ul>
 * K6 budget-backstop wiring regression (2026-10-31 ruling: hold + escalate-to-freeze):
 * the 0=unwired guard on deadlineExhausted; the expression machine's 256-op sample throwing
 * DeadlineShedException (the knife-1 javadoc promise finally wired); the interpreter's
 * fromDeadline suspend conversion (carrier preserved); the fairness rule (over-own-slice
 * sheds count toward the 20-tick freeze; aggregate-pressure victims just hold); and the
 * DEBUG probe/curve transient exemption (a deadline cut-off is not a broken formula).
 */
class FormulaDeadlineBackstopTest {

    /** 闭式巨表达式（裸表达式语句 → 自身即输出 RPN）：约 2400 token，无循环边界可 yield。
     *  Closed-form monster (a bare expression statement IS the output RPN): ~2400 tokens,
     *  no loop boundary to yield at. */
    private static final String MONSTER = "1" + "+1".repeat(1200);
    /** 受害者脚本：约 300 token——采样点(256)必然命中，但自身开销在份额内的形态。
     *  Victim script: ~300 tokens — the sample point (256) always fires, yet its own cost
     *  fits inside its slice. */
    private static final String VICTIM = "1" + "+1".repeat(150);

    @BeforeEach
    void resetBudget() { FormulaCompute.clearAll(); }

    @AfterEach
    void tearDown() {
        FormulaCompute.setBudgetMsSource(() -> 3.0); // 恢复全局预算源 / restore the global budget source
        FormulaCompute.clearAll();
    }

    // ── 栈机层 / stack-machine level ──

    @Test
    @DisplayName("未接线形态(deadline=0)下采样永不 shed——单测环境免护栏")
    void unwiredDeadlineNeverSheds() {
        var parsed = FormulaParser.parseScript(MONSTER);
        assertNotNull(parsed.ast);
        assertDoesNotThrow(() -> FormulaParser.evaluateValue(parsed.outputRpns.get(0), Map.of()),
            "with budgetDeadlineNs==0 the sampler must never shed (clearAll()'s reset shape)");
    }

    @Test
    @DisplayName("deadline 已过:表达式栈机 256-op 采样抛 DeadlineShedException")
    void monsterExpressionThrowsAtDeadline() throws InterruptedException {
        FormulaCompute.setBudgetMsSource(() -> 0.001); // 1μs 预算 / a 1μs budget
        FormulaCompute.beginTick();
        Thread.sleep(2); // deadline 必然已过 / the deadline has surely passed
        var parsed = FormulaParser.parseScript(MONSTER);
        assertThrows(FormulaParser.DeadlineShedException.class,
            () -> FormulaParser.evaluateValue(parsed.outputRpns.get(0), Map.of()),
            "the closed-form monster must be shed at the first sample after the deadline");
    }

    // ── 解释器层 / interpreter level ──

    @Test
    @DisplayName("解释器把 deadline shed 转为 fromDeadline 挂起,carrier 保住")
    void interpreterConvertsToDeadlineSuspend() throws InterruptedException {
        FormulaCompute.setBudgetMsSource(() -> 0.001);
        FormulaCompute.beginTick();
        Thread.sleep(2);
        var parsed = FormulaParser.parseScript(MONSTER);
        var env = new HashMap<String, Value>();
        var ex = assertThrows(FormulaInterpreter.SuspendSignal.class,
            () -> FormulaInterpreter.exec(parsed.ast, env, FormulaCompute.sliceNs()),
            "exec must convert the deadline shed into a suspend (never leak the raw signal)");
        assertTrue(ex.fromDeadline, "the suspend must carry the fromDeadline flag");
        assertNotNull(ex.carrier, "the carrier snapshot must be present for resume");
        assertFalse(ex.carrier.done, "a deadline suspend is never a done state");
    }

    // ── 求值器层:公平规则 / evaluator level: the fairness rule ──

    /** 主图单 FORMULA 节点脚手架。 / One FORMULA node on a fresh graph. */
    private record OneNode(GraphEvaluator ev, GraphNode node) {}

    private static OneNode oneNode(String formula) {
        var graph = new NodeGraph();
        var n = graph.addNode(NodeType.FORMULA, 0, 0);
        n.formula = formula;
        return new OneNode(new GraphEvaluator(graph), n);
    }

    private static void tick(OneNode t) {
        t.ev().evaluate(List.of(), Map.of(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));
    }

    @Test
    @DisplayName("公平规则·主动方:自身超份额连续超时 → 20 tick 冻结(输出保持 lastGood)")
    void overSliceMonsterEscalatesToFreeze() {
        OneNode t = oneNode(MONSTER);
        FormulaCompute.setBudgetMsSource(() -> 0.001); // slice=1μs:巨脚本必超自身份额 / slice=1μs: the monster always outlives its slice
        for (int i = 0; i < 25 && !t.node().formulaShedWarned; i++) {
            FormulaCompute.beginTick();
            tick(t);
        }
        assertTrue(t.node().formulaShedWarned,
            "a node that outlives its own slice every tick must freeze within ~20 ticks");
        assertEquals(0f, t.ev().getNodeOutput(t.node().id, 0), 0.0001f,
            "outputs hold lastGood (initial 0) — the shed never writes zeros over a mid-flight value");
    }

    @Test
    @DisplayName("公平规则·被动方:聚合压力受害者(自身在份额内)只保持,永不冻结")
    void withinSliceVictimNeverFreezes() throws InterruptedException {
        OneNode t = oneNode(VICTIM);
        FormulaCompute.setBudgetMsSource(() -> 3.0); // slice=3ms:受害者 ≈ 数十 μs ≪ 份额 / slice=3ms: the victim (~tens of μs) fits well inside
        for (int i = 0; i < 30; i++) {
            FormulaCompute.beginTick();
            Thread.sleep(4); // 把 tick deadline(3ms) 拖过——模拟其它源造成的聚合压力 / push the tick deadline (3ms) past — simulated aggregate pressure
            tick(t);
            assertFalse(t.node().formulaShedWarned,
                "a within-its-slice victim must never be frozen for the tick's aggregate overrun");
        }
        assertEquals(0f, t.ev().getNodeOutput(t.node().id, 0), 0.0001f,
            "the victim's outputs hold (transient shed), never zeroed");
    }

    // ── 预算解冻 / budget thaw ──

    @Test
    @DisplayName("预算解冻:冻结后调大预算 → 下一 tick 自动解冻并用新预算值完成")
    void freezeThawsWhenBudgetRaised() {
        OneNode t = oneNode(MONSTER);
        FormulaCompute.setBudgetMsSource(() -> 0.001); // 1μs:巨脚本必然冻结 / 1μs: the monster inevitably freezes
        for (int i = 0; i < 25 && !t.node().formulaShedWarned; i++) {
            FormulaCompute.beginTick();
            tick(t);
        }
        assertTrue(t.node().formulaShedWarned, "the monster must freeze first");
        assertTrue(t.node().formulaFrozenBudgetMs > 0, "the freeze must record the budget it froze under");
        // 调大预算(1μs → 20ms):下一 tick 用新预算值重试并完成——「冻结」变「按预算值悬停」。
        // Raise the budget (1μs → 20ms): the next tick retries under the new value and finishes —
        // the freeze becomes a hold keyed to the budget value.
        FormulaCompute.setBudgetMsSource(() -> 20.0);
        FormulaCompute.beginTick();
        tick(t);
        assertFalse(t.node().formulaShedWarned, "a raised budget must thaw the deadline freeze");
        assertEquals(0.0, t.node().formulaFrozenBudgetMs, 1e-9, "the thaw must clear the frozen-budget key");
        assertEquals(1201f, t.ev().getNodeOutput(t.node().id, 0), 0.5f,
            "the monster completes under the raised budget (1+1+... = 1201)");
    }

    @Test
    @DisplayName("MAX_ITER 冻结与预算无关:调大预算不解冻")
    void maxIterFreezeStaysFrozenOnBudgetRaise() {
        OneNode t = oneNode("v = 0\nrepeat 2000000 { v = v + 1 }\n@output v"); // >1M 迭代 → MAX_ITER
        for (int i = 0; i < 40 && !t.node().formulaShedWarned; i++) {
            FormulaCompute.beginTick();
            tick(t); // 默认 3ms 预算:每 tick 推进数十万迭代,数 tick 内撞 MAX_ITER / the 3ms budget advances ~200k iterations per tick, hitting MAX_ITER within a few
        }
        assertTrue(t.node().formulaShedWarned, "the pathological loop must hit the MAX_ITER freeze");
        assertEquals(0.0, t.node().formulaFrozenBudgetMs, 1e-9,
            "the MAX_ITER freeze must not carry a budget key (budget-independent)");
        FormulaCompute.setBudgetMsSource(() -> 20.0); // 调大预算也不解冻——编辑公式是唯一解 / raising the budget must not thaw it either — editing is the only remedy
        FormulaCompute.beginTick();
        tick(t);
        assertTrue(t.node().formulaShedWarned, "a MAX_ITER freeze stays frozen across budget raises");
        assertEquals(0f, t.ev().getNodeOutput(t.node().id, 0), 0.0001f, "outputs stay held");
    }

    // ── DEBUG 瞬态豁免 / DEBUG transient exemption ──

    @Test
    @DisplayName("DEBUG 试算撞 deadline ≠ 坏公式:RPN 保留、失败旗标不亮")
    void debugProbeDeadlineIsTransient() throws InterruptedException {
        var graph = new NodeGraph();
        var node = graph.addNode(NodeType.DEBUG_SIGNAL_GEN, 0, 0);
        node.params[0] = DebugSignals.SET_FORMULA;
        node.params[1] = DebugSignals.OUT_INPUT;
        node.params[4] = 0.5f;
        node.formula = MONSTER; // y=f(x) 单表达式巨形态 / a monster single-expression y=f(x)
        var ev = new GraphEvaluator(graph);
        FormulaCompute.setBudgetMsSource(() -> 0.001);
        for (int i = 0; i < 3; i++) {
            FormulaCompute.beginTick();
            Thread.sleep(2);
            ev.evaluate(List.of(), Map.of(), 0.05f,
                new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));
            assertNull(node.debugFormulaWarnedScript,
                "a deadline-interrupted probe must not raise the broken-formula marker");
            assertFalse(node.debugFormulaCompileWarned, "no failure state for transient sheds");
            assertNotNull(node.debugFormulaRpn, "the compiled RPN must stay cached across transient sheds");
        }
    }
}
