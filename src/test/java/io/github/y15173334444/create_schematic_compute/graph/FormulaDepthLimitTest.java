package io.github.y15173334444.create_schematic_compute.graph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FORMULA 递归深度上限回归（P1-6）：解析链（parseStatement/parseKeywordStmt/parseBody/
 * parseBlockStatements）与解释器（runBody/execStmt）以往无深度守卫，千层嵌套脚本能打出
 * StackOverflowError —— Error 拦不住、且 SO 发生在 {@code cachedScript} 赋值前 → 每 tick
 * 重解析再 SO，崩溃循环。修复后：超限抛受控异常（Exception 族），解析层降级为
 * 「输出恒 0 + ERROR issue」，求值路径由 {@code catch (Exception)} 兜成归零，绝不崩。
 * <p>
 * FORMULA recursion-depth-limit regression (P1-6): the parse chain and the interpreter
 * used to be unguarded, so a deeply nested script produced a StackOverflowError — an
 * Error nothing catches, firing before {@code cachedScript} is assigned → re-parse and SO
 * again every tick (crash loop). After the fix: the limit throws a controlled exception
 * (Exception family), the parse layer degrades to "pinned-to-0 output + ERROR issue", and
 * the eval path zeroes out through {@code catch (Exception)} — no crash, ever.
 */
class FormulaDepthLimitTest {

    /** 构造 N 层嵌套 if 的单行脚本。 / Build a single-line script with N nested ifs. */
    private static String nestedIfs(int n) {
        var sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append("if (1) { ");
        sb.append('1');
        for (int i = 0; i < n; i++) sb.append(" } ");
        return sb.toString();
    }

    // ── 解析侧 / parse side ─────────────────────────────────────────────

    @Test
    @DisplayName("200 层嵌套 if：解析降级为输出恒 0 + ERROR issue，不抛不崩")
    void deepNestedIfParsesToDegradedResult() {
        FormulaParser.ScriptParseResult r = assertDoesNotThrow(() -> FormulaParser.parseScript(nestedIfs(200)),
            "parse must degrade, never throw (and never StackOverflowError)");
        assertNull(r.ast, "an over-limit script must abandon the AST");
        assertEquals(1, r.outputRpns.size());
        assertEquals(0.0, r.outputRpns.get(0).get(0), "degraded output RPN is the literal 0");
        assertFalse(r.issues.isEmpty(), "an ERROR issue must ride the result for the editor badge");
        assertEquals(FormulaParser.Severity.ERROR, r.issues.get(0).severity());
    }

    @Test
    @DisplayName("validate(200 层) 同样安全：不抛、能看到 ERROR 徽章")
    void validateSurvivesDeepNesting() {
        var issues = assertDoesNotThrow(() -> FormulaParser.validate(nestedIfs(200)));
        assertTrue(issues.stream().anyMatch(i -> i.severity() == FormulaParser.Severity.ERROR),
            "validate must surface the depth ERROR (editor badge path)");
    }

    @Test
    @DisplayName("上限内（20 层）解析照常成功——守卫不误伤正常脚本")
    void withinLimitStillParses() {
        FormulaParser.ScriptParseResult r = FormulaParser.parseScript(nestedIfs(20));
        assertNotNull(r.ast, "20-level nesting is legal and must produce an AST");
        assertTrue(r.issues.isEmpty());
    }

    // ── 解释侧 / interpreter side ────────────────────────────────────────

    @Test
    @DisplayName("解释器 300 层嵌套体：受控 ExecTooDeepException，绝非 StackOverflowError")
    void interpreterThrowsControlledExceptionOnDeepAst() {
        // AST 只能经解析器构造时受 128 约束；这里手工构造 300 层 IfStmt 验证解释侧
        // 第二道防线本身（并验证受控异常类型而非 Error）。
        // ASTs built through the parser are capped at 128; this hand-builds 300 IfStmt
        // levels to exercise the interpreter-side second line of defence itself (and to
        // assert the controlled exception type rather than an Error).
        FormulaAst.Stmt inner = new FormulaAst.ExprStmt(List.of(1.0));
        for (int i = 0; i < 300; i++)
            inner = new FormulaAst.IfStmt(List.of(1.0), List.of(inner), List.of());
        final FormulaAst.Stmt deep = inner;
        var env = new HashMap<String, Value>();
        assertThrows(FormulaInterpreter.ExecTooDeepException.class,
            () -> FormulaInterpreter.exec(List.of(deep), env),
            "the interpreter must fail with the controlled exception, never StackOverflowError");
    }

    @Test
    @DisplayName("挂起后续算：深度计数按每次 exec 重置，不跨 tick 累计")
    void resumeDoesNotInheritPreviousDepth() {
        // repeat 4 { 嵌套体 }：每轮都从 exec 顶层重新进入，深度从 0 起算。
        // 若深度跨轮/跨 exec 累计，多轮循环会在远低于上限处误炸。
        // repeat 4 { nested body }: each round re-enters from the exec top, depth from 0.
        // If depth accumulated across rounds/execs, plain loops would misfire far below the limit.
        var parsed = FormulaParser.parseScript("acc = 0\nrepeat 4 { repeat 4 { acc = acc + 1 } }");
        assertNotNull(parsed.ast);
        var env = new HashMap<String, Value>();
        assertDoesNotThrow(() -> FormulaInterpreter.exec(parsed.ast, env,
            Long.MAX_VALUE / 2, null, 0));
        assertEquals(16.0, FormulaParser.asScalar(env.get("acc")), 1e-9);
    }

    // ── 求值路径 / eval path ─────────────────────────────────────────────

    @Test
    @DisplayName("求值路径：200 层脚本的 FORMULA 节点输出归零、不崩、缓存落定（无重解析循环）")
    void evalPathZeroesOutWithoutCrashing() {
        var graph = new NodeGraph();
        var node = graph.addNode(NodeType.FORMULA, 0, 0);
        node.formula = nestedIfs(200);
        var evaluator = new GraphEvaluator(graph);

        assertDoesNotThrow(() -> evaluator.evaluate(List.of(), Map.of(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0)));
        assertEquals(0f, evaluator.getNodeOutput(node.id, 0), 0.0001f,
            "an over-limit script must evaluate to 0");
        // cachedScript 已赋值（降级结果落缓存）→ 下个 tick 不会重新解析 → 无崩溃循环。
        // cachedScript is assigned (the degraded result is cached) → no re-parse next tick → no crash loop.
        assertNotNull(node.cachedScript, "the degraded parse must be cached, not re-parsed every tick");
        assertNull(node.cachedScript.ast);
        // 连续多 tick 求值稳定（事故形态是逐 tick 崩溃循环）。
        // Multiple consecutive ticks stay stable (the incident shape was a per-tick crash loop).
        for (int i = 0; i < 3; i++)
            assertDoesNotThrow(() -> evaluator.evaluate(List.of(), Map.of(), 0.05f,
                new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0)));
        assertEquals(0f, evaluator.getNodeOutput(node.id, 0), 0.0001f);
    }
}
