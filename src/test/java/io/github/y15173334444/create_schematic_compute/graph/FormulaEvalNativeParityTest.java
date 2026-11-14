package io.github.y15173334444.create_schematic_compute.graph;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * R3 对拍网（§4.7-5）：FormulaEval native 面（真节点 + 真状态机 + 真 JNI）与 Java
 * 解释器链的**逐位**对拍。锁的是消费面语义——节点输出 o[0..n)、挂起态与公平记账；
 * 引擎级全槽位终值由 cargo parity/bridge_cross 锁，两层合起来覆盖 §4.4「双路径由
 * 对拍测试网锁定同语义」的验收口径。
 *
 * <p>参考链 = 同一次 parse 的 AST 直接跑 {@code FormulaInterpreter.exec}（不限预算）
 * + 逐输出 {@code evaluateValue(outputRpn, env) → asScalar → (float)}——与 Java 路径
 * 的 FORMULA case 逐字同构，零硬编码。面级判定 = {@code Float.floatToIntBits} 逐位。</p>
 *
 * <p>挂起/续算、warm/freeze、done-skip 的 spread 行为面由
 * {@link FormulaBudgetSpreadTest}（R3 起 native 分流后即在 native 面上跑）覆盖；
 * MAX_ITER shed 面由其 testShedFreezeUntilFormulaEdit 覆盖——本类不重复。</p>
 *
 * <p>The R3 parity net (§4.7-5): bit-exact comparison of the FormulaEval native face
 * (real nodes, real state machine, real JNI) against the Java interpreter chain. The
 * reference chain re-parses the same script and runs it unbudgeted — nothing
 * hard-coded; face comparisons are float-bit-exact per output pin. Spread-level
 * behaviour (suspend/resume, warm/freeze, done-skip, MAX_ITER shed) already rides the
 * native path inside FormulaBudgetSpreadTest since the R3 dispatch.</p>
 */
class FormulaEvalNativeParityTest {

    /** native 缺席 → 整类跳过（本类只锁 native 面；Java 兜底路径由存量测试网覆盖）。 */
    private static void assumeNative() {
        assumeTrue(FormulaNative.AVAILABLE,
            "formula_native cdylib unavailable — skipping the native-face parity net");
    }

    @AfterEach
    void restoreBudget() {
        FormulaCompute.setBudgetMsSource(null); // 回落默认 3.0ms / fall back to the 3.0ms default
        FormulaCompute.beginTick();
    }

    // ── 面级进料（图构造；输入按 inputVars 序接 CONST——引脚序 ≠ 槽位序的坑点在此过网） ──

    private record Face(GraphEvaluator ev, GraphNode node) {}

    /** 一脚本一面：CONST 按名接引脚（未给的引脚缺席 = lenient 0，双端同口径）。 */
    private static Face face(String script, Map<String, Double> pins) {
        var graph = new NodeGraph();
        var f = graph.addNode(NodeType.FORMULA, 500, 0);
        f.formula = script;
        f.ensureScriptParsed();
        int vi = 0;
        for (String var : f.cachedScript.inputVars) {
            Double v = pins.get(var);
            if (v == null) {
                continue;
            }
            var c = graph.addNode(NodeType.CONST, vi * 200, 200);
            c.params[0] = v.floatValue();
            graph.addConnection(c.id, 0, f.id, vi);
            vi++;
        }
        return new Face(new GraphEvaluator(graph), f);
    }

    /** 参考链：同一次 parse 的 AST 跑 Java 解释器（不限预算）+ 逐输出 RPN 求值 → float 位。 */
    private static int[] referenceBits(FormulaParser.ScriptParseResult script, Map<String, Double> pins) {
        var env = new HashMap<String, Value>();
        for (String var : script.inputVars) {
            Double v = pins.get(var);
            if (v != null) {
                env.put(var, new Value.Scalar(v));
            }
        }
        FormulaInterpreter.exec(script.ast, env);
        int nOut = Math.max(1, script.outputRpns.size());
        int[] bits = new int[nOut];
        for (int oi = 0; oi < nOut; oi++) {
            double want = oi < script.outputRpns.size()
                ? FormulaParser.asScalar(FormulaParser.evaluateValue(script.outputRpns.get(oi), env))
                : 0.0;
            bits[oi] = Float.floatToIntBits((float) want);
        }
        return bits;
    }

    /** 面级逐位判定：真节点 + 真状态机 + 真 JNI 输出 vs 参考链。 */
    private static void assertFaceBitExact(String script, Map<String, Double> pins) {
        var parsed = FormulaParser.parseScript(script);
        assertNotNull(parsed.ast, "script must parse to AST mode: " + script);
        assertTrue(parsed.issues.isEmpty(), "script issues: " + parsed.issues + " — " + script);
        int[] want = referenceBits(parsed, pins);
        Face face = face(script, pins);
        // 推进到 done 再判定——重脚本（弹道 ~15ms）单 tick 会 slice 挂起、输出冻结
        // (emit-on-done),轻脚本一 tick 即完成;done-skip 让后续 tick 幂等。
        // Drive to done before judging — heavy scripts slice-suspend on one tick with
        // frozen outputs; light ones finish immediately; done-skip makes extra ticks idempotent.
        boolean done = false;
        for (int t = 0; t < 400 && !done; t++) {
            FormulaCompute.beginTick();
            face.ev().evaluate(List.of(), Map.of(), 0.05f, new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));
            done = FormulaNative.AVAILABLE
                ? face.node().formulaNativeDone
                : (face.node().formulaCarrier != null && face.node().formulaCarrier.done);
        }
        assertTrue(done, "script must converge through the face: " + script);
        int nOut = face.node().outputs();
        assertEquals(want.length, nOut, "output pin count — " + script);
        for (int oi = 0; oi < nOut; oi++) {
            int got = Float.floatToIntBits(face.ev().getNodeOutput(face.node().id, oi));
            assertEquals(want[oi], got, "output " + oi + " bit-exact vs Java chain — " + script
                + String.format("%n  want=%08x (%.9g) got=%08x (%.9g)",
                    want[oi], Float.intBitsToFloat(want[oi]), got, Float.intBitsToFloat(got)));
        }
    }

    // ── 1. 全函数表 + 算子 + lenient 形态（§4.7-5 脚本矩阵的一半） ──

    private static final List<String> FN_TABLE = List.of(
        // 向量族（vec3 输出经解析期展开成 3 标量引脚）
        "y = vec3(1.0, 2.0, 3.0)\n@output y",
        "v = vec3(3.0, 4.0, 0.0)\nw = vec3(1.0, 0.0, 2.0)\n"
            + "a = length(v)\nb = normalize(v)\nc = dot(v, w)\nd = cross(v, w)\ne = dist(v, w)\n"
            + "@output a\n@output b\n@output c\n@output d\n@output e",
        "v = vec3(1.0, -1.0, 2.0)\na = yaw(v)\nb = pitch(v)\n@output a\n@output b",
        // 标量三角/超越（定义域内）
        "a = sin(30.0)\nb = cos(60.0)\nc = tan(45.0)\nd = asin(0.5)\ne = acos(0.5)\nf = atan2(1.0, 2.0)\n"
            + "@output a\n@output b\n@output c\n@output d\n@output e\n@output f",
        "a = sinh(1.0)\nb = cosh(1.0)\nc = sqrt(9.0)\nd = ln(10.0)\ne = log(100.0)\nf = exp(1.0)\n"
            + "@output a\n@output b\n@output c\n@output d\n@output e\n@output f",
        "a = sec(45.0)\nb = csc(30.0)\nc = cot(45.0)\n@output a\n@output b\n@output c",
        // lenient 形态（定义域外 → 0、除零 → 0、多分量 swizzle → 0、未知函数 → 末参、PI 变量态）
        "v = vec3(1.0, 2.0, 3.0)\na = ln(-1.0)\nb = asin(2.0)\nc = sqrt(-4.0)\nd = sec(90.0)\n"
            + "e = 1.0 / 0.0\nf = 0.0 % 0.0\ng = v.xy\nh = min(1.0, 2.0)\ni = sin(PI)\n"
            + "@output a\n@output b\n@output c\n@output d\n@output e\n@output f\n@output g\n@output h\n@output i",
        // 算子/比较/逻辑/一元负号/取反/优先级
        "a = (1.0 + 2.0) * 3.0 ^ 2.0\nb = 7.0 % 3.0\nc = 2.0 == 2.0\nd = 1.0 != 1.0\ne = 3.0 <= 3.0\n"
            + "f = 2.0 >= 3.0\ng = 1.0 < 2.0\nh = 2.0 > 1.0\ni = 1.0 && 1.0\nj = 0.0 || 1.0\n"
            + "k = !0.0\nl = -3.0 + 1.0\nm = 5.0 - -2.0\n@output a\n@output b\n@output c\n@output d\n@output e\n"
            + "@output f\n@output g\n@output h\n@output i\n@output j\n@output k\n@output l\n@output m",
        // 输入引脚（引脚序 ≠ 槽位序：y 先互敦，a/b 后——native 的 pinSlots 映射在此过网）
        "y = a * 2.0 + b\n@output y",
        "y = dist(vec3(a, b, 0.0), vec3(0.0, 0.0, 0.0))\n@output y");

    @Test
    @DisplayName("fn table + operators + lenient shapes: face vs Java chain bit-exact")
    void fnTableFaceParity() {
        assumeNative();
        for (String script : FN_TABLE) {
            assertFaceBitExact(script, Map.of());
        }
        // 带引脚形态：pinSlots 映射（a=3, b=4 → 10；dist(3,4)=5）
        assertFaceBitExact("y = a * 2.0 + b\n@output y", Map.of("a", 3.0, "b", 4.0));
        assertFaceBitExact("y = dist(vec3(a, b, 0.0), vec3(0.0, 0.0, 0.0))\n@output y",
            Map.of("a", 3.0, "b", 4.0));
    }

    // ── 2. 控制流矩阵 ──

    private static final List<String> CONTROL_FLOW = List.of(
        "s = 0\nrepeat 100 { s = s + 1\n  if (s >= 10) { break } }\n@output s",
        "s = 0\ni = 0\nwhile (i < 10) { i = i + 1\n  if (i == 5) { continue }\n  s = s + 2 }\n@output s",
        "a = 0\nrepeat 3 { repeat 4 { a = a + 1 } }\n@output a",
        "x = 3.0\nif (x > 2.0) { y = 1.0 } else { y = 2.0 }\n@output y",
        "s = 0\nrepeat 5 { s = s + s + 1 }\n@output s",
        "s = 0\nrepeat 2 { repeat 2 { repeat 2 { s = s + 1 } } }\n@output s");

    @Test
    @DisplayName("control-flow matrix: repeat/while/if/break/continue/nesting face parity")
    void controlFlowFaceParity() {
        assumeNative();
        for (String script : CONTROL_FLOW) {
            assertFaceBitExact(script, Map.of());
        }
    }

    // ── 3. 挂起→续算经面级状态机收敛（与不限预算参考链逐位） ──

    @Test
    @DisplayName("suspend/resume through the face: carrier bytes + spread + bit-exact convergence")
    void suspendResumeFaceConvergesBitExact() {
        assumeNative();
        String script = "v = 0.0\nrepeat 200000 { v = v * 0.9 + 1.0 }\n@output v";
        var parsed = FormulaParser.parseScript(script);
        int[] want = referenceBits(parsed, Map.of());
        Face face = face(script, Map.of());

        // tick 1:压低 slice(模拟重负载)→ native 挂起（字节 carrier 落节点）。
        FormulaCompute.beginTick();
        for (int i = 0; i < 200; i++) FormulaCompute.reportYield();
        FormulaCompute.beginTick();
        face.ev().evaluate(List.of(), Map.of(), 0.05f, new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));
        assertTrue(face.node().formulaNativeStatus == 1 || face.node().formulaNativeStatus == 4,
            "suspend status must ride the node (carrier state is native-held since R3b)");
        assertTrue(face.node().formulaSpreadProgress > 0f, "render progress positive");
        assertEquals(0f, face.ev().getNodeOutput(face.node().id, 0), 0.0001f, "emit-on-done: output frozen");

        // 续算至 done（正常 slice），终值与不限预算参考链逐位。
        boolean done = false;
        for (int t = 0; t < 400 && !done; t++) {
            FormulaCompute.beginTick();
            face.ev().evaluate(List.of(), Map.of(), 0.05f, new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));
            done = face.node().formulaNativeDone;
        }
        assertTrue(done, "spread must converge");
        int got = Float.floatToIntBits(face.ev().getNodeOutput(face.node().id, 0));
        assertEquals(want[0], got, "converged output bit-exact vs unbudgeted Java chain");
    }

    // ── 4. deadline 挂起契约（status 4:carrier 有效、免计 yield、输出保持） ──
    // 采样口径提醒：256-op 采样是**调用内局部**的——短表达式永不采样，deadline 只在
    // ≥256 ops 的巨表达式上命中（K6 的目标形态 = 闭式巨脚本），脚本必须按此构造。
    // Sampling reminder: the 256-op sample is call-local — short expressions never
    // sample; the deadline only bites on ≥256-op monster expressions (K6's target
    // shape = closed-form monsters), so the script must be built that way.

    @Test
    @DisplayName("deadline suspend (fromDeadline): carrier + yield NOT counted + outputs hold")
    void deadlineSuspendFaceContract() {
        assumeNative();
        // 巨表达式：~300 项加法（~600 token）→ 首个 256-op 采样即命中。
        StringBuilder sb = new StringBuilder("v = 0.0\ny = v");
        for (int i = 0; i < 300; i++) {
            sb.append(" + 1.0");
        }
        sb.append("\n@output y");
        String script = sb.toString();
        Face face = face(script, Map.of());

        // 预算压到 500ns：beginTick 建立的 deadline 在 exec 进入点前已过 → 剩余 = -1 哨兵
        // → native 表达式机首个采样即命中 → DeadlineSuspended（Java deadlineExhausted()
        // 恒真形态同语义）。
        FormulaCompute.setBudgetMsSource(() -> 0.0005);
        FormulaCompute.beginTick();
        face.ev().evaluate(List.of(), Map.of(), 0.05f, new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));
        assertTrue(face.node().formulaNativeStatus == 4, "deadline suspend keeps the carrier (status 4)");
        assertTrue(face.node().formulaNativeDone == false, "not done — suspended");
        FormulaCompute.beginTick();
        assertEquals(0, FormulaCompute.heavyYieldCountPrev(),
            "fromDeadline suspend must NOT count as a yield (K6 fairness rule)");
        assertEquals(0f, face.ev().getNodeOutput(face.node().id, 0), 0.0001f,
            "outputs hold (fresh node's lastGood = 0) while suspended");
    }

    // ── 5. 弹道真脚本 5 案例经面级状态机逐位（CBC 金标准同参进料） ──

    private static final double[][] BALLISTIC_CASES = {
        {0, 80, 0, 250, 82, -30},    // close flat shot
        {0, 80, 0, 500, 70, -100},   // mid-range with drag
        {0, 200, 0, 400, 20, 0},     // downward over height gap
        {0, 80, 0, 3, 190, 0},       // near-vertical
        {0, 80, 0, 3000, 50, 0},     // out-of-range (hit=0)
    };

    @Test
    @DisplayName("ballistic: 5 CBC-golden cases through the native face, bit-exact vs Java chain")
    void ballisticFiveCasesFaceParity() {
        assumeNative();
        String script;
        try {
            script = Files.readString(Path.of("docs/examples/ballistic_solver.formula"));
        } catch (Exception e) {
            fail("cannot read docs/examples/ballistic_solver.formula: " + e);
            return;
        }
        String[] pinNames = {"mx", "my", "mz", "tx", "ty", "tz"};
        for (double[] c : BALLISTIC_CASES) {
            Map<String, Double> pins = new LinkedHashMap<>();
            for (int i = 0; i < pinNames.length; i++) {
                pins.put(pinNames[i], c[i]);
            }
            assertFaceBitExact(script, pins);
        }
    }
}
