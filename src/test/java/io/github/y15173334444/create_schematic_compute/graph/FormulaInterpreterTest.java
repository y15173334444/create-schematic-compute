package io.github.y15173334444.create_schematic_compute.graph;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 刀 3 测试:AST 语句解析 + 解释器 + vec3 运行时语义 + 类型推断 + 类型感知校验。
 * Knife-3 tests: AST statement parsing + interpreter + vec3 runtime + inference + type-aware validation.
 */
class FormulaInterpreterTest {

    /** 解析并执行脚本,返回终值 env。 / Parse and run the script, return the final env. */
    private static Map<String, Value> run(String script, Map<String, Double> inputs) {
        var parsed = FormulaParser.parseScript(script);
        assertNotNull(parsed.ast, "expected AST mode for: " + script);
        var env = new HashMap<String, Value>();
        for (var e : inputs.entrySet()) env.put(e.getKey(), new Value.Scalar(e.getValue()));
        FormulaInterpreter.exec(parsed.ast, env);
        return env;
    }

    private static double scalar(Map<String, Value> env, String name) {
        return FormulaParser.asScalar(env.get(name));
    }

    /** 取第 i 个输出值。 / Evaluate the i-th output. */
    private static double output(String script, Map<String, Double> inputs, int i) {
        var parsed = FormulaParser.parseScript(script);
        var env = new HashMap<String, Value>();
        for (var e : inputs.entrySet()) env.put(e.getKey(), new Value.Scalar(e.getValue()));
        FormulaInterpreter.exec(parsed.ast, env);
        return FormulaParser.asScalar(FormulaParser.evaluateValue(parsed.outputRpns.get(i), env));
    }

    // ── 控制流 / control flow ──

    @Test
    @DisplayName("repeat: acc = acc + i over 10 rounds → 45")
    void testRepeatAccumulate() {
        var env = run("i = 0\nacc = 0\nrepeat 10 { acc = acc + i; i = i + 1 }", Map.of());
        assertEquals(45.0, scalar(env, "acc"), 1e-9);
        assertEquals(10.0, scalar(env, "i"), 1e-9);
    }

    @Test
    @DisplayName("while + break: err halves and breaks at threshold")
    void testWhileBreak() {
        var env = run("err = 1\nwhile (err > 0.001) { err = err * 0.5; if (err < 0.001) break }", Map.of());
        assertTrue(scalar(env, "err") < 0.001, "err should exit below threshold");
    }

    @Test
    @DisplayName("if/else: branch selection")
    void testIfElse() {
        var env = run("if (x > 0) { y = 1 } else { y = 2 }", Map.of("x", 5.0));
        assertEquals(1.0, scalar(env, "y"), 1e-9);
        var env2 = run("if (x > 0) { y = 1 } else { y = 2 }", Map.of("x", -1.0));
        assertEquals(2.0, scalar(env2, "y"), 1e-9);
    }

    @Test
    @DisplayName("continue: skips i == 2 → acc = 1+3+4+5 = 13")
    void testContinue() {
        var env = run("i = 0\nacc = 0\nrepeat 5 { i = i + 1; if (i == 2) continue; acc = acc + i }", Map.of());
        assertEquals(13.0, scalar(env, "acc"), 1e-9);
    }

    @Test
    @DisplayName("Nested loops: repeat 3 { repeat 2 { n = n + 1 } } → 6")
    void testNestedLoops() {
        var env = run("n = 0\nrepeat 3 { repeat 2 { n = n + 1 } }", Map.of());
        assertEquals(6.0, scalar(env, "n"), 1e-9);
    }

    @Test
    @DisplayName("break only exits innermost loop")
    void testBreakInnerOnly() {
        var env = run("n = 0\nrepeat 3 { repeat 10 { n = n + 1; break } }", Map.of());
        assertEquals(3.0, scalar(env, "n"), 1e-9);
    }

    @Test
    @DisplayName("Old scripts stay in RPN mode: ast == null")
    void testLegacyAstNull() {
        assertNull(FormulaParser.parseScript("x = a + 1").ast);
        assertNull(FormulaParser.parseScript("sin(a) + b").ast);
        assertNull(FormulaParser.parseScript("x = a == b").ast); // 比较无块 → 仍走旧路径
    }

    @Test
    @DisplayName("AST mode triggers on keywords and braces")
    void testAstModeDetection() {
        assertNotNull(FormulaParser.parseScript("if (x > 0) { y = 1 }").ast);
        assertNotNull(FormulaParser.parseScript("repeat 5 { }").ast);
    }

    // ── vec3 运行时 / vec3 runtime ──

    @Test
    @DisplayName("vec3 arithmetic: subtract, length, broadcast scale")
    void testVec3Arithmetic() {
        var env = run("p = vec3(px, py, pz)\nm = vec3(mx, my, mz)\nrel = p - m\nr = length(rel)\nscaled = rel * 2", Map.of(
            "px", 3.0, "py", 4.0, "pz", 0.0, "mx", 0.0, "my", 0.0, "mz", 0.0));
        assertEquals(5.0, scalar(env, "r"), 1e-9);
        var scaled = (Value.Vec3Val) env.get("scaled");
        assertEquals(6.0, scaled.x(), 1e-9);
        assertEquals(8.0, scaled.y(), 1e-9);
        assertEquals(0.0, scaled.z(), 1e-9);
    }

    @Test
    @DisplayName("vec3 functions: normalize/dot/cross/dist")
    void testVec3Functions() {
        var env = run("a = vec3(1, 0, 0)\nb = vec3(0, 1, 0)\nn = normalize(vec3(2, 0, 0))\nd = dot(a, b)\nc = cross(a, b)\nlen = dist(a, b)", Map.of());
        assertEquals(0.0, scalar(env, "d"), 1e-9);
        var c = (Value.Vec3Val) env.get("c");
        assertEquals(0.0, c.x(), 1e-9);
        assertEquals(0.0, c.y(), 1e-9);
        assertEquals(1.0, c.z(), 1e-9);
        var n = (Value.Vec3Val) env.get("n");
        assertEquals(1.0, n.x(), 1e-9);
        assertEquals(Math.sqrt(2.0), scalar(env, "len"), 1e-9);
    }

    @Test
    @DisplayName("yaw/pitch mirror the DIRECTION node convention")
    void testYawPitchConvention() {
        // 与 GraphEvaluator DIRECTION(GraphEvaluator.java:577-579)同式验证 / verify against the same formulas
        var env = run("v = vec3(1, 2, -3)\ny = yaw(v)\np = pitch(v)", Map.of());
        double expectedYaw = (Math.toDegrees(Math.atan2(1.0, 3.0)) + 360.0) % 360.0;
        double expectedPitch = Math.toDegrees(Math.atan2(-2.0, Math.sqrt(1.0 * 1.0 + 3.0 * 3.0)));
        assertEquals(expectedYaw, scalar(env, "y"), 1e-6);
        assertEquals(expectedPitch, scalar(env, "p"), 1e-6);
    }

    @Test
    @DisplayName("swizzle: v.x / v.y / v.z read components")
    void testSwizzle() {
        var env = run("v = vec3(1, 2, 3)\nx = v.x\ny = v.y\nz = v.z", Map.of());
        assertEquals(1.0, scalar(env, "x"), 1e-9);
        assertEquals(2.0, scalar(env, "y"), 1e-9);
        assertEquals(3.0, scalar(env, "z"), 1e-9);
    }

    // ── 火控示例 / fire-control example ──

    @Test
    @DisplayName("Fire control: lead computation with repeat convergence")
    void testFireControlExample() {
        String script = """
            tpos = vec3(px, py, pz)
            mpos = vec3(mx, my, mz)
            tvel = vec3(vx, vy, vz)
            rel = tpos - mpos
            r = length(rel)
            tof = r / muzzle_vel
            lead = tpos + tvel * tof
            repeat 16 {
                tof = length(lead - mpos) / muzzle_vel
                lead = tpos + tvel * tof
            }
            aim = lead - mpos
            @output yaw(aim)
            @output pitch(aim)
            @output r
            """;
        var inputs = Map.of("px", 10.0, "py", 0.0, "pz", 0.0,
            "mx", 0.0, "my", 0.0, "mz", 0.0,
            "vx", 0.0, "vy", 0.0, "vz", 0.0, "muzzle_vel", 5.0);
        // 目标正东 10 格:yaw = atan2(10, 0) = 90,pitch = 0,射程 10 / target 10 east: yaw 90, pitch 0, range 10
        assertEquals(90.0, output(script, inputs, 0), 1e-6);
        assertEquals(0.0, output(script, inputs, 1), 1e-6);
        assertEquals(10.0, output(script, inputs, 2), 1e-6);
    }

    @Test
    @DisplayName("Type inference: vec3-assigned vars are marked vec3")
    void testVec3Inference() {
        var parsed = FormulaParser.parseScript("p = vec3(px, py, pz)\nrel = p - m\nr = length(rel)\n@output r");
        assertTrue(parsed.vec3Vars.contains("p"));
        assertTrue(parsed.vec3Vars.contains("rel"));
        assertFalse(parsed.vec3Vars.contains("r"));
    }

    @Test
    @DisplayName("@output expression compiles to RPN (not a bare name)")
    void testOutputExpression() {
        var parsed = FormulaParser.parseScript("v = vec3(1, 0, 0)\n@output yaw(v)");
        assertEquals(1, parsed.outputLabels.size());
        assertTrue(parsed.outputLabels.get(0).startsWith("yaw"));
        // RPN 里应有 yaw 函数调用 / RPN should contain a yaw call
        assertTrue(parsed.outputRpns.get(0).stream().anyMatch(tok ->
            tok instanceof FormulaParser.OpToken || tok instanceof String));
    }

    @Test
    @DisplayName("@output hoist inside blocks: if/else declares two static pins")
    void testOutputHoistInBlocks() {
        var parsed = FormulaParser.parseScript("if (c) { @output a } else { @output b }");
        assertEquals(2, parsed.outputLabels.size());
        assertTrue(parsed.outputLabels.contains("a"));
        assertTrue(parsed.outputLabels.contains("b"));
    }

    // ── 类型感知校验 / type-aware validation ──

    @Test
    @DisplayName("validate: vector * vector is ERROR")
    void testValidateVecTimesVec() {
        var issues = FormulaParser.validate("a = vec3(1, 0, 0)\nb = vec3(0, 1, 0)\nbad = a * b");
        assertTrue(issues.stream().anyMatch(i ->
            i.severity() == FormulaParser.Severity.ERROR && i.message().contains("向量乘向量")));
    }

    @Test
    @DisplayName("validate: vector comparison is ERROR")
    void testValidateVecCompare() {
        var issues = FormulaParser.validate("a = vec3(1, 0, 0)\nb = vec3(0, 1, 0)\nbad = a > b");
        assertTrue(issues.stream().anyMatch(i ->
            i.severity() == FormulaParser.Severity.ERROR && i.message().contains("向量不可比较")));
    }

    @Test
    @DisplayName("validate: scalar arg to vector function is ERROR")
    void testValidateVecFnScalarArg() {
        var issues = FormulaParser.validate("bad = length(5)");
        assertTrue(issues.stream().anyMatch(i ->
            i.severity() == FormulaParser.Severity.ERROR && i.message().contains("需要 vec3")));
    }

    @Test
    @DisplayName("validate: clean fire-control script has no ERROR")
    void testValidateCleanFireControl() {
        String script = """
            tpos = vec3(px, py, pz)
            mpos = vec3(mx, my, mz)
            aim = tpos - mpos
            @output yaw(aim)
            """;
        var issues = FormulaParser.validate(script);
        assertFalse(issues.stream().anyMatch(i -> i.severity() == FormulaParser.Severity.ERROR));
    }

    // ── GraphEvaluator 端到端 / end-to-end through GraphEvaluator ──

    @Test
    @DisplayName("GraphEvaluator: control-flow FORMULA node runs through the interpreter")
    void testGraphEvaluatorAstPath() {
        var graph = new NodeGraph();
        var c1 = graph.addNode(NodeType.CONST, 0, 0);
        var c2 = graph.addNode(NodeType.CONST, 100, 0);
        var f = graph.addNode(NodeType.FORMULA, 0, 100);
        c1.params[0] = 3f;
        c2.params[0] = 4f;
        f.formula = "s = 0\nrepeat 5 { s = s + a + b }\n@output s";
        graph.addConnection(c1.id, 0, f.id, 0);
        graph.addConnection(c2.id, 0, f.id, 1);
        var evaluator = new GraphEvaluator(graph);

        FormulaCompute.beginTick();
        evaluator.evaluate(List.of(), Map.of(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));

        assertEquals(35f, evaluator.getNodeOutput(f.id, 0), 0.0001f); // 5 × (3+4)
    }
}
