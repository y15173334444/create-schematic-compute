package io.github.y15173334444.create_schematic_compute.graph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 双前端退役（2026-10-31 拍板）的统一前端行为锚：无控制流脚本统一升格 AST，旧扁平
 * RPN 前端（旧单行表达式模式 + 逐行赋值/输出解析）随批删除。本类钉住升格后的**值与
 * 引脚面等价性**（登记册条目『AST 与 RPN 双前端并行』的旧档兼容口径）：
 * <ul>
 *   <li>旧单行/多行赋值/@output 形态的求值结果与旧前端逐值一致（默认输出 = 顶层末行
 *       独立表达式、赋值序执行、输出后算——AST 前端设计即对齐旧规则）；</li>
 *   <li>引脚面语义（inputVars）承继 extractVariables 口径：sin(PI)/裸 PI/E 是变量引脚、
 *       (PI)/(E) 分组括号内是字面量、函数名不入引脚；</li>
 *   <li>ast==null 只剩降级/空脚本形态（深度守卫 → 输出恒 0 + ERROR issue）。</li>
 * </ul>
 * 旧档兼容口径：持久化只存 formula 源文本与引脚计数（无编译产物入档），旧档加载即经
 * 统一前端重编译；连线按引脚名绑定（v1.2.4 pinId），变量顺序差异不改连线语义。
 * <p>
 * Behaviour anchors for the unified frontend (dual-frontend retirement, 2026-10-31
 * ruling): non-control-flow scripts are promoted to AST and the flat-RPN frontends are
 * gone. This class pins value and pin-face equivalence with the old frontends (the
 * legacy-save compatibility stance of open-issues entry "AST and RPN dual frontends"):
 * identical evaluation on the released source forms, the extractVariables pin-face
 * rules, and ast==null surviving only as the degraded/empty shape. Persistence stores
 * formula source text + pin counts only (no compiled artifacts), so old saves recompile
 * through the unified frontend on load; connections bind by pin name (v1.2.4 pinIds),
 * so variable-order differences never rewires a graph.
 */
class FormulaUnifiedFrontendTest {

    /** 解析 + 执行 + 取第 i 个输出。 / Parse + exec + read output i. */
    private static double output(String script, Map<String, Double> inputs, int i) {
        var parsed = FormulaParser.parseScript(script);
        assertNotNull(parsed.ast, "expected unified AST for: " + script);
        var env = new HashMap<String, Value>();
        for (var e : inputs.entrySet()) env.put(e.getKey(), new Value.Scalar(e.getValue()));
        FormulaInterpreter.exec(parsed.ast, env);
        return FormulaParser.asScalar(FormulaParser.evaluateValue(parsed.outputRpns.get(i), env));
    }

    // ── 旧形态求值等价 / released-form value parity ──

    @Test
    @DisplayName("legacy single-line: A+1 promotes to AST, A=2 → 3")
    void legacySingleLinePromotes() {
        var parsed = FormulaParser.parseScript("A+1");
        assertNotNull(parsed.ast);
        assertEquals(1, parsed.outputRpns.size());
        assertEquals("", parsed.outputLabels.get(0), "默认输出标签为空串 / default output label");
        assertEquals(3.0, output("A+1", Map.of("A", 2.0), 0), 1e-9);
    }

    @Test
    @DisplayName("assignments run in order, default output = last top-level standalone expr")
    void assignmentsAndDefaultOutput() {
        // x = A*2 后 x + 1 为默认输出；A=2 → x=4 → 5
        assertEquals(5.0, output("x = A*2\nx + 1", Map.of("A", 2.0), 0), 1e-9);
        // 全赋值无独立表达式 → 默认输出 0（与旧前端一致）
        assertEquals(0.0, output("x = A*2", Map.of("A", 2.0), 0), 1e-9);
    }

    @Test
    @DisplayName("@output name and @output expression forms keep their semantics")
    void outputMarkerForms() {
        assertEquals(4.0, output("x = A*2\n@output x", Map.of("A", 2.0), 0), 1e-9);
        var parsed = FormulaParser.parseScript("a = 1\n@output a + 1");
        assertEquals(1, parsed.outputLabels.size());
        assertEquals(2.0, output("a = 1\n@output a + 1", Map.of(), 0), 1e-9);
    }

    @Test
    @DisplayName("multi-output order preserved: sum then diff")
    void multiOutputOrder() {
        var script = "sum = A+B\ndiff = A-B\n@output sum\n@output diff";
        assertEquals(5.0, output(script, Map.of("A", 3.0, "B", 2.0), 0), 1e-9);
        assertEquals(1.0, output(script, Map.of("A", 3.0, "B", 2.0), 1), 1e-9);
    }

    // ── 引脚面语义（承继 extractVariables 口径）/ pin-face rules ──

    @Test
    @DisplayName("pin face: sin(PI)/bare PI/E are input pins; (PI)/(E) are literals")
    void pinFaceConstantRules() {
        assertTrue(FormulaParser.parseScript("sin(PI)").inputVars.contains("PI"),
            "PI in sin(PI) must be a variable (input pin), not skipped");
        assertFalse(FormulaParser.parseScript("(PI)").inputVars.contains("PI"),
            "PI in (PI) must be skipped (literal, no input pin)");
        var bare = FormulaParser.parseScript("PI + E").inputVars;
        assertTrue(bare.contains("PI"));
        assertTrue(bare.contains("E"));
        var mixed = FormulaParser.parseScript("A + sin(PI) + (E)").inputVars;
        assertTrue(mixed.contains("A"));
        assertTrue(mixed.contains("PI"), "PI in sin(PI) is a variable");
        assertFalse(mixed.contains("E"), "E in (E) is a literal");
    }

    @Test
    @DisplayName("pin face: function names and pure numbers never become pins")
    void pinFaceFunctionsAndNumbers() {
        var vars = FormulaParser.parseScript("A*B+sin(C)").inputVars;
        assertTrue(vars.contains("A"));
        assertTrue(vars.contains("B"));
        assertTrue(vars.contains("C"));
        assertFalse(vars.contains("sin"));
        assertTrue(FormulaParser.parseScript("2+3*4").inputVars.isEmpty());
    }

    // ── 降级形态 / degraded shape ──

    @Test
    @DisplayName("ast==null survives only as the degraded/empty shape")
    void astNullOnlyForDegradedOrEmpty() {
        assertNull(FormulaParser.parseScript("").ast);
        // 超深嵌套：受控降级 → ast==null + ERROR issue + 输出恒 0
        StringBuilder deep = new StringBuilder("x = 1");
        for (int i = 0; i < 200; i++) deep.append("\nrepeat 2 {");
        var degraded = FormulaParser.parseScript(deep.toString());
        assertNull(degraded.ast, "深度守卫降级形态 ast==null");
        assertFalse(degraded.issues.isEmpty(), "降级形态必须携带 ERROR issue");
        assertEquals(0.0, FormulaParser.asScalar(
            FormulaParser.evaluateValue(degraded.outputRpns.get(0), new HashMap<String, Value>())), 1e-9);
    }
}
