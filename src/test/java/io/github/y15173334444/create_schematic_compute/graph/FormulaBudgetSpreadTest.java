package io.github.y15173334444.create_schematic_compute.graph;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 刀 5 测试:协作超时挂起/续算(寻径执行)、emit-on-done、输入冻结 vs 温启动、
 * done 跳过/冷复位、MAX_ITER shed、N_heavy 计数、无引脚 warm 参数。
 * Knife-5 tests: cooperative suspend/resume (seek execution), emit-on-done,
 * input freeze vs warm restart, done skip/cold reset, MAX_ITER shed, N_heavy counting, pinless warm param.
 */
class FormulaBudgetSpreadTest {

    /** 收敛型测试脚本:v 指数趋近 target(0.9^k),@output 显式导出 v。
     *  Converging test script: v approaches target (0.9^k), @output exports v explicitly. */
    private static final String CONVERGE = "v = 0\nrepeat 100000 { v = v * 0.9 + target * 0.1 }\n@output v";

    @AfterEach
    void tearDown() {
        FormulaCompute.clearAll();
    }

    // ── 解释器层:挂起/续算 / interpreter-level suspend/resume ──

    @Test
    @DisplayName("Suspend/resume: prefix re-execution must not clobber loop-carried vars")
    void testSuspendResumePrefixProtection() {
        var parsed = FormulaParser.parseScript("acc = 0\nrepeat 100 { acc = acc + 1 }");
        assertNotNull(parsed.ast);
        var env = new HashMap<String, Value>();
        var car = new FormulaInterpreter.Carrier();
        int resumes = 0;
        while (true) {
            try {
                FormulaInterpreter.exec(parsed.ast, env, 0L, car.loopStack, car.totalIterations);
                break; // 完成 / done
            } catch (FormulaInterpreter.SuspendSignal sus) {
                car = sus.carrier;
                resumes++;
                assertTrue(resumes < 30, "resumes must be bounded");
            }
        }
        // 寻径执行保证前缀 acc=0 不会把快照中的 acc 覆盖掉 / seek execution keeps acc from being reset by the prefix
        assertEquals(100.0, FormulaParser.asScalar(env.get("acc")), 1e-9);
        assertTrue(resumes >= 1, "should have suspended at least once");
    }

    @Test
    @DisplayName("Warm continue: input change mid-spread keeps k (no reset) and tracks the new target")
    void testWarmContinueKeepsProgress() {
        var graph = new NodeGraph();
        var c = graph.addNode(NodeType.CONST, 0, 0);
        var f = graph.addNode(NodeType.FORMULA, 100, 100);
        c.params[0] = 10f;
        f.formula = "v = 0\nrepeat 50000 { v = v * 0.9 + target * 0.1 }\n@output v";
        f.ensureScriptParsed();
        graph.addConnection(c.id, 0, f.id, 0);
        f.params[0] = 1f; // warm / 温启动
        var ev = new GraphEvaluator(graph);

        // 压低 slice → 挂起 / tiny slice → suspend
        FormulaCompute.beginTick();
        for (int i = 0; i < 200; i++) FormulaCompute.reportYield();
        FormulaCompute.beginTick();
        ev.evaluate(List.of(), Map.of(), 0.05f, new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));
        assertTrue(f.formulaCarrier != null && !f.formulaCarrier.done);
        long k0 = f.formulaCarrier.loopStack.get(0).k;

        // 输入变更 mid-spread(信号发生器式持续变化也允许)/ input change mid-spread (continuous changes allowed)
        c.params[0] = 20f;

        long lastK = k0;
        boolean done = false;
        for (int t = 0; t < 400; t++) {
            FormulaCompute.beginTick();
            // 每 3 tick 输入再变一次(模拟信号发生器持续变化)/ change the input every 3 ticks (signal-gen-like)
            if (t % 3 == 0) c.params[0] = 15f + 5f * (float) Math.sin(t * 0.5);
            ev.evaluate(List.of(), Map.of(), 0.05f, new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));
            if (f.formulaCarrier != null) {
                if (f.formulaCarrier.done) { done = true; break; } // done carrier 的 loopStack 为空 / done carrier has an empty loop stack
                long k = f.formulaCarrier.loopStack.get(0).k;
                assertTrue(k >= lastK, "warm continue must never reset k (tick " + t + ": " + k + " < " + lastK + ")");
                lastK = k;
            }
        }
        assertTrue(done, "should converge with warm continue");
        // 终值应逼近最后输入的 target(15±5 的近期值)/ output should approach the last fed target
        assertTrue(ev.getNodeOutput(f.id, 0) > 5f && ev.getNodeOutput(f.id, 0) < 25f);
    }

    @Test
    @DisplayName("Progress: nested loops report outermost-repeat progress — monotonic, no bar jumping")
    void testNestedProgressMonotonic() {
        // 弹道脚本同构:外层扫描 × 内层模拟;内层反复 0→1 不应造成进度条横跳
        // Ballistic-script shape: outer scan × inner simulation; the inner sweep must not reset the bar
        var parsed = FormulaParser.parseScript("a = 0\nrepeat 10 { repeat 100 { a = a + 1 } }");
        var env = new HashMap<String, Value>();
        var car = new FormulaInterpreter.Carrier();
        float last = -1f;
        int suspensions = 0;
        while (true) {
            try {
                FormulaInterpreter.exec(parsed.ast, env, 0L, car.loopStack, car.totalIterations);
                break;
            } catch (FormulaInterpreter.SuspendSignal sus) {
                car = sus.carrier;
                suspensions++;
                float p = car.progress;
                assertTrue(p >= last, "progress must be monotonic (suspend " + suspensions + ": " + p + " < " + last + ")");
                last = p;
                assertTrue(p >= 0f && p <= 1f, "progress in [0,1], got " + p);
            }
        }
        assertEquals(1000.0, FormulaParser.asScalar(env.get("a")), 1e-9);
        assertTrue(suspensions >= 1);
    }

    @Test
    @DisplayName("Nested loops resume correctly through seek execution")
    void testSuspendResumeNested() {
        var parsed = FormulaParser.parseScript("a = 0\nrepeat 3 { repeat 100 { a = a + 1 } }");
        var env = new HashMap<String, Value>();
        var car = new FormulaInterpreter.Carrier();
        int resumes = 0;
        while (true) {
            try {
                FormulaInterpreter.exec(parsed.ast, env, 0L, car.loopStack, car.totalIterations);
                break;
            } catch (FormulaInterpreter.SuspendSignal sus) {
                car = sus.carrier;
                resumes++;
                assertTrue(resumes < 300, "resumes must be bounded");
            }
        }
        assertEquals(300.0, FormulaParser.asScalar(env.get("a")), 1e-9);
    }

    @Test
    @DisplayName("MAX_ITER shed: infinite loop sheds at the 1M spread-wide cap")
    void testMaxIterShed() {
        var parsed = FormulaParser.parseScript("x = 0\nwhile (1) { x = x }");
        assertNotNull(parsed.ast);
        assertThrows(FormulaInterpreter.ShedSignal.class, () ->
            FormulaInterpreter.exec(parsed.ast, new HashMap<String, Value>(), Long.MAX_VALUE / 2));
    }

    // ── GraphEvaluator 层:spread + emit-on-done / evaluator-level spread ──

    /** 构造 CONST→FORMULA 图,返回 (const, formula, evaluator)。 */
    private static Object[] buildGraph() {
        var graph = new NodeGraph();
        var c = graph.addNode(NodeType.CONST, 0, 0);
        var f = graph.addNode(NodeType.FORMULA, 100, 100);
        c.params[0] = 10f;
        f.formula = CONVERGE;
        graph.addConnection(c.id, 0, f.id, 0);
        return new Object[]{c, f, new GraphEvaluator(graph)};
    }

    /** 推进若干 tick 直到节点 done(或超时失败)。 */
    private static void runUntilDone(GraphEvaluator ev, GraphNode f, int maxTicks) {
        for (int t = 0; t < maxTicks; t++) {
            FormulaCompute.beginTick();
            ev.evaluate(List.of(), Map.of(), 0.05f,
                new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));
            if (f.formulaCarrier != null && f.formulaCarrier.done) return;
        }
        fail("spread did not converge within " + maxTicks + " ticks");
    }

    @Test
    @DisplayName("Spread: suspends under tiny slice, output frozen, resumes to done with fresh output")
    void testSpreadEmitOnDone() {
        var parts = buildGraph();
        var f = (GraphNode) parts[1];
        var ev = (GraphEvaluator) parts[2];

        // tick 1:压低 slice(模拟重负载)/ tiny slice (simulated overload)
        FormulaCompute.beginTick();
        for (int i = 0; i < 200; i++) FormulaCompute.reportYield();
        FormulaCompute.beginTick(); // 轮转:prev=200 → slice=15µs / rotate: slice = 3ms/200
        ev.evaluate(List.of(), Map.of(), 0.05f, new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));

        assertNotNull(f.formulaCarrier, "carrier should exist after suspend");
        assertFalse(f.formulaCarrier.done, "should not be done after one tiny-slice tick");
        assertTrue(f.formulaSpreadProgress > 0f, "render progress should be positive");
        assertEquals(0f, ev.getNodeOutput(f.id, 0), 0.0001f); // emit-on-done:输出冻结 / output frozen

        FormulaCompute.beginTick();
        assertEquals(1, FormulaCompute.heavyYieldCountPrev(), "N_heavy should count the suspended node");

        // 后续 tick:slice 恢复 3ms → 逐步推进至 done / normal slice → converge
        runUntilDone(ev, f, 50);
        assertEquals(10f, ev.getNodeOutput(f.id, 0), 0.01f);
        assertEquals(0f, f.formulaSpreadProgress, 0f);

        // done 跳过:输入未变再推一 tick,输出保持 / done skip: unchanged inputs, outputs hold
        FormulaCompute.beginTick();
        ev.evaluate(List.of(), Map.of(), 0.05f, new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));
        assertTrue(f.formulaCarrier.done);
        assertEquals(10f, ev.getNodeOutput(f.id, 0), 0.01f);
    }

    @Test
    @DisplayName("Freeze mode (default): input change mid-spread completes the OLD snapshot")
    void testFreezeModeCompletesOldSnapshot() {
        var parts = buildGraph();
        var c = (GraphNode) parts[0];
        var f = (GraphNode) parts[1];
        var ev = (GraphEvaluator) parts[2];

        FormulaCompute.beginTick();
        for (int i = 0; i < 200; i++) FormulaCompute.reportYield();
        FormulaCompute.beginTick();
        ev.evaluate(List.of(), Map.of(), 0.05f, new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));
        assertTrue(f.formulaCarrier != null && !f.formulaCarrier.done);

        c.params[0] = 20f; // 输入变更 mid-spread / input changes mid-spread
        runUntilDone(ev, f, 50);
        // 严格冻结:算完的是旧输入快照的解 / strict freeze: the completed solution is for the OLD snapshot
        assertEquals(10f, ev.getNodeOutput(f.id, 0), 0.01f);
    }

    @Test
    @DisplayName("Warm mode (param=1): input change mid-spread restarts toward the NEW inputs")
    void testWarmRestartTracksNewInput() {
        var parts = buildGraph();
        var c = (GraphNode) parts[0];
        var f = (GraphNode) parts[1];
        var ev = (GraphEvaluator) parts[2];
        f.params[0] = 1f; // warm 节点参数 / warm node param

        FormulaCompute.beginTick();
        for (int i = 0; i < 200; i++) FormulaCompute.reportYield();
        FormulaCompute.beginTick();
        ev.evaluate(List.of(), Map.of(), 0.05f, new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));
        assertTrue(f.formulaCarrier != null && !f.formulaCarrier.done);

        c.params[0] = 20f;
        runUntilDone(ev, f, 50);
        // 温启动:旧 Env 作初值、新输入刷新 → 收敛到新目标 / warm restart converges to the NEW target
        assertEquals(20f, ev.getNodeOutput(f.id, 0), 0.01f);
    }

    @Test
    @DisplayName("Done + input change → cold reset recomputes fresh (both modes)")
    void testDoneColdReset() {
        var parts = buildGraph();
        var c = (GraphNode) parts[0];
        var f = (GraphNode) parts[1];
        var ev = (GraphEvaluator) parts[2];

        runUntilDone(ev, f, 60); // 正常负载 ~3ms/tick,100k 迭代需 ~14 tick 分摊;负载高时放宽到 60
        assertEquals(10f, ev.getNodeOutput(f.id, 0), 0.01f);

        c.params[0] = 20f;
        runUntilDone(ev, f, 60);
        assertEquals(20f, ev.getNodeOutput(f.id, 0), 0.01f); // 冷复位重算 / cold reset recompute
    }

    @Test
    @DisplayName("Shed freeze: pathological script is marked and skipped until formula edit")
    void testShedFreezeUntilFormulaEdit() {
        var parts = buildGraph();
        var f = (GraphNode) parts[1];
        var ev = (GraphEvaluator) parts[2];
        f.formula = "x = 0\nwhile (1) { x = x }"; // 病态死循环 / pathological infinite loop
        f.ensureScriptParsed(); // 重新解析 + 清 carrier / re-parse + clear carrier

        FormulaCompute.beginTick();
        // slice=3ms 下每 tick 跑约 10k 迭代;1M 上限需要多次 spread 推进 — 直接压低 MAX_ITER 不可行,用循环推
        // with slice=3ms each tick runs ~10k iterations; reaching the 1M cap needs many spreads — drive it in a loop
        boolean shed = false;
        // 每 tick ~3ms ≈ 8.5k 迭代,1M 上限需要 ~120 tick 推进 / ~120 ticks needed to reach the 1M cap
        for (int t = 0; t < 200 && !shed; t++) {
            FormulaCompute.beginTick();
            ev.evaluate(List.of(), Map.of(), 0.05f, new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));
            shed = f.formulaShedWarned;
        }
        assertTrue(shed, "pathological loop should shed at the 1M cap");
        assertNull(f.formulaCarrier);

        // shed 冻结:后续 tick 不再执行 / shed freeze: no further execution
        FormulaCompute.beginTick();
        ev.evaluate(List.of(), Map.of(), 0.05f, new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));
        assertTrue(f.formulaShedWarned);

        // 公式编辑解冻 / formula edit unfreezes
        f.formula = "x = 1\n@output x";
        f.ensureScriptParsed();
        assertFalse(f.formulaShedWarned);
        FormulaCompute.beginTick();
        ev.evaluate(List.of(), Map.of(), 0.05f, new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));
        assertEquals(1f, ev.getNodeOutput(f.id, 0), 0.0001f);
    }

    // ── warm 参数引脚 / warm param pin ──

    @Test
    @DisplayName("GraphNode: warm is a pinless eval-policy param (edit-panel button, no input pin)")
    void testWarmParamPinless() {
        var node = new GraphNode(1, NodeType.FORMULA, 0f, 0f);
        node.formula = "x = a + 1\n@output x";
        // 镜像编辑面板 responder:解析后 din = inputVars.size()(真实编辑流程)
        // Mirror the edit-panel responder: after parsing, din = inputVars.size() (the real edit flow)
        var res = FormulaParser.parseScript(node.formula);
        node.cachedScript = res;
        node.dynamicInputCount = res.inputVars.size();
        node.dynamicOutputCount = Math.max(1, res.outputLabels.size());
        node.ensureScriptParsed();
        assertEquals(1, node.inputs()); // 仅变量引脚;warm 不占引脚 / variable pins only; warm takes no pin
        assertEquals(0, node.inputPinIndex("a"));
        assertEquals(-1, node.inputPinIndex("warm")); // warm 无引脚 / warm has no pin
        assertEquals("a", node.inputPinId(0));
        assertNull(node.inputPinId(1)); // 没有第二个引脚 / no second pin
        assertEquals(1, node.type.editableParamCount()); // 参数存储仍在(params[0]) / param storage remains
        assertEquals(1, node.functionalInputs()); // 功能引脚 = 全部输入 / functional = total inputs
    }
}
