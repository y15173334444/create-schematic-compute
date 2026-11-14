package io.github.y15173334444.create_schematic_compute.graph;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * R3b 批量段面测试（§4.7-6）：切分规则、跨段连线的读者新鲜性、去重骑行、多成员
 * 挂起/续算（native 态跨 tick 自持）。对拍口径 = 面级输出与 Java 参考链逐位
 * （参考链 = 同 parse AST 跑 {@link FormulaInterpreter} + 逐输出 RPN 求值，零硬编码）。
 *
 * <p>The R3b batch-segment face tests (§4.7-6): the split rule, fresh reads across
 * segment boundaries, dedup riding, and multi-member suspend/resume with the
 * native-held state across ticks. Judged bit-exact against the Java reference chain.</p>
 */
class FormulaBatchTest {

    private static void assumeNative() {
        assumeTrue(FormulaNative.AVAILABLE, "formula_native unavailable — skipping the batch face tests");
    }

    @AfterEach
    void resetTickState() {
        FormulaCompute.beginTick();
    }

    /** 参考链逐位（单输出脚本）。 / reference chain bits (single-output scripts). */
    private static int referenceBits(String script, Map<String, Double> pins) {
        var parsed = FormulaParser.parseScript(script);
        var env = new HashMap<String, Value>();
        for (String var : parsed.inputVars) {
            Double v = pins.get(var);
            if (v != null) {
                env.put(var, new Value.Scalar(v));
            }
        }
        FormulaInterpreter.exec(parsed.ast, env);
        double want = FormulaParser.asScalar(FormulaParser.evaluateValue(parsed.outputRpns.get(0), env));
        return Float.floatToIntBits((float) want);
    }

    /** 一脚本一节点（CONST 按名接引脚）；返回节点。 / one script = one node; returns the node. */
    private static GraphNode addFormula(NodeGraph graph, int x, int y, String script, Map<String, Double> pins) {
        var f = graph.addNode(NodeType.FORMULA, x, y);
        f.formula = script;
        f.ensureScriptParsed();
        int vi = 0;
        for (String var : f.cachedScript.inputVars) {
            Double v = pins.get(var);
            if (v == null) {
                continue;
            }
            var c = graph.addNode(NodeType.CONST, x - 200, y + vi * 100);
            c.params[0] = v.floatValue();
            graph.addConnection(c.id, 0, f.id, vi);
            vi++;
        }
        return f;
    }

    /** 推进到全节点 done（批量面挂起收敛）。 / drive until every formula node is done. */
    private static void driveToDone(GraphEvaluator ev) {
        for (int t = 0; t < 400; t++) {
            FormulaCompute.beginTick();
            ev.evaluate(List.of(), Map.of(), 0.05f, new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));
            boolean allDone = true;
            for (GraphNode n : ev.ctx.graph.nodes) {
                if (n.type == NodeType.FORMULA && !n.formulaNativeDone) {
                    allDone = false;
                    break;
                }
            }
            if (allDone) {
                return;
            }
        }
        fail("batch spread did not converge within 400 ticks");
    }

    // ── 1. 切分规则（§4.7-6 的可入段判定） ──

    @Test
    @DisplayName("split rule: independent formulas coalesce; wiring in either direction splits")
    void splitRuleJoinsAndSplits() {
        assumeNative();
        String script = "y = a + 1.0\n@output y";
        // 无连线:两节点入同段。 / unwired: both join one segment.
        var g1 = new NodeGraph();
        addFormula(g1, 0, 0, script, Map.of("a", 1.0));
        addFormula(g1, 300, 0, script, Map.of("a", 1.0));
        var ev1 = new GraphEvaluator(g1);
        ev1.ctx.formulaBatch = new FormulaBatchCoordinator(ev1.ctx);
        var batch1 = ev1.ctx.formulaBatch;
        // 逐个 stage：第一个成员入段后，第二个（无连线）可入。pinInputs 取不同值——
        // 同脚本同输入会触发骑行（另一条规则的测试面），这里只验入段。
        // Stage one by one with distinct pinInputs (identical keys would ride — another
        // rule's face); this test only exercises joining.
        float[] pins1 = {1.0f};
        float[] pins2 = {2.0f};
        int idx = 0;
        for (GraphNode n : g1.nodes) {
            if (n.type != NodeType.FORMULA) {
                continue;
            }
            batch1.flushBefore(n);
            n.ensureScriptParsed();
            batch1.stage(n, n.outputValues, n.cachedScript, idx++ == 0 ? pins1 : pins2, 1);
        }
        assertEquals(2, batch1.pendingMembers(), "independent formulas coalesce into one segment");

        // 有连线(f1→f2):f2 不可入段。 / wired: f2 cannot join f1's segment.
        var g2 = new NodeGraph();
        var f1 = addFormula(g2, 0, 0, script, Map.of("a", 1.0));
        var f2 = addFormula(g2, 300, 0, script, Map.of("a", 1.0));
        // f2 的 a 引脚改接 f1 的 y 输出
        g2.removeConnection(g2.connections.get(g2.connections.size() - 1).fromId,
            g2.connections.get(g2.connections.size() - 1).fromPin,
            f2.id, 0);
        g2.addConnection(f1.id, 0, f2.id, 0);
        var ev2 = new GraphEvaluator(g2);
        ev2.ctx.formulaBatch = new FormulaBatchCoordinator(ev2.ctx);
        var batch2 = ev2.ctx.formulaBatch;
        f1.ensureScriptParsed();
        batch2.stage(f1, f1.outputValues, f1.cachedScript, new float[0], 1);
        assertFalse(batch2.canJoin(f2), "a wired candidate must not join the segment");
    }

    // ── 2. 跨段读者新鲜性 + 链式拆分逐位（精确等价的消费面） ──

    @Test
    @DisplayName("chained formulas split at the edge and stay bit-exact (f2 reads f1's fresh output)")
    void chainedFormulasBitExact() {
        assumeNative();
        var graph = new NodeGraph();
        String s1 = "y = a * 2.0\n@output y";
        String s2 = "y = b + 1.0\n@output y";
        var f1 = addFormula(graph, 0, 0, s1, Map.of("a", 10.0));
        var f2 = addFormula(graph, 300, 0, s2, Map.of());
        graph.addConnection(f1.id, 0, f2.id, 0);
        var ev = new GraphEvaluator(graph);
        driveToDone(ev);
        assertEquals(referenceBits(s1, Map.of("a", 10.0)),
            Float.floatToIntBits(ev.getNodeOutput(f1.id, 0)), "f1 bit-exact");
        assertEquals(referenceBits(s2, Map.of("b", 20.0)),
            Float.floatToIntBits(ev.getNodeOutput(f2.id, 0)), "f2 reads f1's fresh output, bit-exact");
    }

    // ── 3. 去重骑行:同脚本同输入的后到者取首员存储结果 ──

    @Test
    @DisplayName("dedup riding: same script+input sibling rides the leader (one store, one hit)")
    void dedupRidingSameOutcome() {
        assumeNative();
        var graph = new NodeGraph();
        String script = "y = a * 3.0\n@output y";
        var f1 = addFormula(graph, 0, 0, script, Map.of("a", 7.0));
        var f2 = addFormula(graph, 300, 0, script, Map.of("a", 7.0));
        var ev = new GraphEvaluator(graph);
        FormulaCompute.beginTick();
        ev.evaluate(List.of(), Map.of(), 0.05f, new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));
        assertEquals(referenceBits(script, Map.of("a", 7.0)),
            Float.floatToIntBits(ev.getNodeOutput(f1.id, 0)), "leader output");
        assertEquals(referenceBits(script, Map.of("a", 7.0)),
            Float.floatToIntBits(ev.getNodeOutput(f2.id, 0)), "rider output = leader's stored result");
        assertEquals(1, FormulaCompute.dedupHits(),
            "exactly one ride-hit: the rider takes the leader's stored result");
    }

    // ── 4. 多成员挂起/续算:native 态跨 tick 自持,收敛后与参考链逐位 ──

    @Test
    @DisplayName("multi-member suspend/resume: both members converge bit-exact with different inputs")
    void multiMemberSuspendResumeBitExact() {
        assumeNative();
        String script = "v = 0.0\nrepeat 200000 { v = v * 0.9 + 1.0 }\n@output v";
        var graph = new NodeGraph();
        var f1 = addFormula(graph, 0, 0, script, Map.of());
        var f2 = addFormula(graph, 300, 0, script, Map.of());
        // 同脚本无输入 → dedup 键相同会骑行!区分输入:各接一个不同值 CONST。
        // Same script, no inputs would ride — give them distinct const inputs.
        var c1 = graph.addNode(NodeType.CONST, -200, 0);
        c1.params[0] = 10f;
        graph.addConnection(c1.id, 0, f1.id, 0);
        f1.formula = "t = a\nv = 0.0\nrepeat 200000 { v = v * 0.9 + 1.0 }\n@output v";
        f1.ensureScriptParsed();
        var c2 = graph.addNode(NodeType.CONST, 100, 0);
        c2.params[0] = 20f;
        graph.addConnection(c2.id, 0, f2.id, 0);
        f2.formula = "t = b\nv = 0.0\nrepeat 200000 { v = v * 0.9 + 1.0 }\n@output v";
        f2.ensureScriptParsed();

        // tick 1:压低 slice → 两成员同批挂起(native 态自持)。
        FormulaCompute.beginTick();
        for (int i = 0; i < 200; i++) FormulaCompute.reportYield();
        FormulaCompute.beginTick();
        var ev = new GraphEvaluator(graph);
        ev.evaluate(List.of(), Map.of(), 0.05f, new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));
        assertTrue(f1.formulaNativeStatus == 1 || f1.formulaNativeStatus == 4, "f1 suspended");
        assertTrue(f2.formulaNativeStatus == 1 || f2.formulaNativeStatus == 4, "f2 suspended");
        FormulaCompute.beginTick();
        assertEquals(2, FormulaCompute.heavyYieldCountPrev(), "both members count as heavy yields");

        // 收敛后逐位(v 的收敛终值与输入无关——v→10;脚本输入 t 仅用于区分 dedup 键)。
        driveToDone(ev);
        int want = referenceBits(script, Map.of());
        assertEquals(want, Float.floatToIntBits(ev.getNodeOutput(f1.id, 0)), "f1 converges bit-exact");
        assertEquals(want, Float.floatToIntBits(ev.getNodeOutput(f2.id, 0)), "f2 converges bit-exact");
    }
}
