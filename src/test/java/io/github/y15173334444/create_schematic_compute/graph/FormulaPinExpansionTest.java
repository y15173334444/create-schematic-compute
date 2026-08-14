package io.github.y15173334444.create_schematic_compute.graph;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 刀 4 测试:vec3 输出展开为 3 标量引脚(解析期静态展开),pinId 解析顺延,16 上限 WARN。
 * Knife-4 tests: vec3 output expansion into 3 scalar pins (static at parse time), pinId resolution, 16-cap WARN.
 */
class FormulaPinExpansionTest {

    @Test
    @DisplayName("@output v (vec3) expands to v.x/v.y/v.z with swizzle RPNs")
    void testVec3OutputExpansion() {
        var parsed = FormulaParser.parseScript("v = vec3(1, 2, 3)\n@output v");
        assertEquals(List.of("v.x", "v.y", "v.z"), parsed.outputLabels);
        assertEquals(3, parsed.outputRpns.size());
        // 每个输出 RPN 求值为对应分量 / each output RPN evaluates to its component
        var env = new HashMap<String, Value>();
        env.put("v", new Value.Vec3Val(1, 2, 3));
        assertEquals(1.0, FormulaParser.asScalar(FormulaParser.evaluateValue(parsed.outputRpns.get(0), env)), 1e-9);
        assertEquals(2.0, FormulaParser.asScalar(FormulaParser.evaluateValue(parsed.outputRpns.get(1), env)), 1e-9);
        assertEquals(3.0, FormulaParser.asScalar(FormulaParser.evaluateValue(parsed.outputRpns.get(2), env)), 1e-9);
    }

    @Test
    @DisplayName("Mixed outputs keep order: scalar pins and vec3 expansion interleave")
    void testMixedOutputExpansion() {
        var parsed = FormulaParser.parseScript(
            "v = vec3(1, 2, 3)\ns = length(v)\nt = length(v)\n@output s\n@output v\n@output t");
        assertEquals(List.of("s", "v.x", "v.y", "v.z", "t"), parsed.outputLabels);
    }

    @Test
    @DisplayName("@output expression yielding vec3 expands (e.g. @output normalize(v))")
    void testExpressionOutputExpansion() {
        var parsed = FormulaParser.parseScript("v = vec3(2, 0, 0)\n@output normalize(v)");
        assertEquals(3, parsed.outputLabels.size());
        assertTrue(parsed.outputLabels.get(0).endsWith(".x"));
    }

    @Test
    @DisplayName("Default output (no @output) with vec3 expands to x/y/z")
    void testDefaultOutputExpansion() {
        var parsed = FormulaParser.parseScript("p = vec3(1, 2, 3)\np");
        assertEquals(List.of("x", "y", "z"), parsed.outputLabels);
    }

    @Test
    @DisplayName("Scalar outputs stay single pins (no expansion)")
    void testScalarOutputsUnchanged() {
        var parsed = FormulaParser.parseScript("v = vec3(1, 2, 3)\nr = length(v)\n@output r");
        assertEquals(List.of("r"), parsed.outputLabels);
        assertNull(FormulaParser.parseScript("x = a + 1").ast); // 旧脚本不受影响
    }

    // ── GraphNode 层:pinId 解析顺延 / GraphNode level: pinId resolution follows ──

    @Test
    @DisplayName("GraphNode: dynamicOutputCount follows expansion, pinIds resolve")
    void testGraphNodePinIds() {
        var node = new GraphNode(1, NodeType.FORMULA, 0f, 0f);
        node.formula = "v = vec3(1, 2, 3)\n@output v\n@output r";
        node.ensureScriptParsed();
        assertEquals(4, node.dynamicOutputCount);
        assertEquals(0, node.outputPinIndex("v.x"));
        assertEquals(2, node.outputPinIndex("v.z"));
        assertEquals(3, node.outputPinIndex("r"));
        assertEquals(-1, node.outputPinIndex("v")); // 原向量标签不再存在,旧连线由 rebuildInputCache 清掉
        assertEquals("v.y", node.outputPinId(1));
        assertEquals("r", node.outputPinId(3));
    }

    @Test
    @DisplayName("GraphEvaluator: vec3 output node exposes 3 scalar pins end-to-end")
    void testGraphEvaluatorVec3Outputs() {
        var graph = new NodeGraph();
        var c1 = graph.addNode(NodeType.CONST, 0, 0);
        var c2 = graph.addNode(NodeType.CONST, 100, 0);
        var f = graph.addNode(NodeType.FORMULA, 0, 100);
        c1.params[0] = 3f;
        c2.params[0] = 4f;
        f.formula = "p = vec3(a, b, a + b)\n@output p";
        graph.addConnection(c1.id, 0, f.id, 0);
        graph.addConnection(c2.id, 0, f.id, 1);
        var evaluator = new GraphEvaluator(graph);

        FormulaCompute.beginTick();
        evaluator.evaluate(List.of(), Map.of(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));

        assertEquals(3f, evaluator.getNodeOutput(f.id, 0), 0.0001f);
        assertEquals(4f, evaluator.getNodeOutput(f.id, 1), 0.0001f);
        assertEquals(7f, evaluator.getNodeOutput(f.id, 2), 0.0001f);
    }

    // ── 16 上限 / 16-pin cap ──

    @Test
    @DisplayName("validate: expanded outputs over 16 emit WARN")
    void testOutputCapWarn() {
        // 6 个 vec3 输出 = 18 引脚 > 16 / 6 vec3 outputs = 18 pins > 16
        String script = """
            a = vec3(1, 0, 0)
            b = vec3(0, 1, 0)
            c = vec3(0, 0, 1)
            d = vec3(1, 1, 0)
            e = vec3(0, 1, 1)
            f = vec3(1, 1, 1)
            @output a
            @output b
            @output c
            @output d
            @output e
            @output f
            """;
        var issues = FormulaParser.validate(script);
        assertTrue(issues.stream().anyMatch(i ->
            i.severity() == FormulaParser.Severity.WARN && i.message().contains("上限 16")));
    }
}
