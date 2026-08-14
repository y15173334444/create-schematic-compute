package io.github.y15173334444.create_schematic_compute.graph;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 刀 2 语法扩展测试:新 token、比较/逻辑运算符(1e-6 容差)、旧脚本逐位不变。
 * Knife-2 syntax tests: new tokens, comparison/logical operators (1e-6 tolerance), legacy bit-identity.
 */
class FormulaParserSyntaxTest {

    // ── Tokenizer: 新 token / new tokens ──

    @Test
    @DisplayName("tokenize: braces and keywords")
    void testTokenizeBracesAndKeywords() {
        var tokens = FormulaParser.tokenize("repeat 10 { x = x + 1 }");
        assertEquals(FormulaParser.TokType.KEYWORD, tokens.get(0).type());
        assertEquals("repeat", tokens.get(0).text());
        assertEquals(FormulaParser.TokType.NUMBER, tokens.get(1).type());
        assertEquals(FormulaParser.TokType.LBRACE, tokens.get(2).type());
        assertEquals(FormulaParser.TokType.RBRACE, tokens.get(tokens.size() - 1).type());
    }

    @Test
    @DisplayName("tokenize: if/while/else/break/continue are keywords, not identifiers")
    void testTokenizeKeywordTypes() {
        for (String kw : List.of("while", "if", "else", "break", "continue")) {
            var tokens = FormulaParser.tokenize(kw);
            assertEquals(1, tokens.size(), kw);
            assertEquals(FormulaParser.TokType.KEYWORD, tokens.get(0).type(), kw);
        }
    }

    @Test
    @DisplayName("tokenize: two-char operators == != <= >= && || and single ! < >")
    void testTokenizeTwoCharOperators() {
        var tokens = FormulaParser.tokenize("a == b && c != d || e <= f && g >= h || !x");
        assertEquals("==", tokens.get(1).text());
        assertEquals("&&", tokens.get(3).text());
        assertEquals("!=", tokens.get(5).text());
        assertEquals("||", tokens.get(7).text());
        assertEquals("<=", tokens.get(9).text());
        assertEquals("&&", tokens.get(11).text());
        assertEquals(">=", tokens.get(13).text());
        assertEquals("||", tokens.get(15).text());
        assertEquals("!", tokens.get(16).text());
    }

    @Test
    @DisplayName("tokenize: 1.5 stays one NUMBER; v.x is IDENT + SWIZZLE")
    void testTokenizeNumberAndSwizzle() {
        var num = FormulaParser.tokenize("1.5");
        assertEquals(1, num.size());
        assertEquals(FormulaParser.TokType.NUMBER, num.get(0).type());
        assertEquals("1.5", num.get(0).text());

        var sw = FormulaParser.tokenize("v.x");
        assertEquals(2, sw.size());
        assertEquals(FormulaParser.TokType.IDENT, sw.get(0).type());
        assertEquals(FormulaParser.TokType.SWIZZLE, sw.get(1).type());
        assertEquals("x", sw.get(1).text());
    }

    @Test
    @DisplayName("tokenize: vec3(1,2,3) is a FUNCTION token")
    void testTokenizeVec3Literal() {
        var tokens = FormulaParser.tokenize("vec3(1, 2, 3)");
        assertEquals(FormulaParser.TokType.FUNCTION, tokens.get(0).type());
        assertEquals("vec3", tokens.get(0).text());
    }

    // ── 比较/逻辑求值 / comparison & logical evaluation ──

    @Test
    @DisplayName("== with 1e-6 tolerance: 0.0000001 == 0 is true, 0.001 == 0 is false")
    void testEqualityTolerance() {
        assertEquals(1.0, FormulaParser.evaluate(FormulaParser.compile("0.0000001 == 0"), Map.of()), 1e-9);
        assertEquals(1.0, FormulaParser.evaluate(FormulaParser.compile("2 == 2"), Map.of()), 1e-9);
        assertEquals(0.0, FormulaParser.evaluate(FormulaParser.compile("0.001 == 0"), Map.of()), 1e-9);
        assertEquals(0.0, FormulaParser.evaluate(FormulaParser.compile("2 == 3"), Map.of()), 1e-9);
        assertEquals(1.0, FormulaParser.evaluate(FormulaParser.compile("2 != 3"), Map.of()), 1e-9);
    }

    @Test
    @DisplayName("Comparisons: < > <= >= are exact")
    void testComparisonsExact() {
        assertEquals(1.0, FormulaParser.evaluate(FormulaParser.compile("1 < 2"), Map.of()), 1e-9);
        assertEquals(0.0, FormulaParser.evaluate(FormulaParser.compile("2 < 2"), Map.of()), 1e-9);
        assertEquals(1.0, FormulaParser.evaluate(FormulaParser.compile("2 >= 2"), Map.of()), 1e-9);
        assertEquals(0.0, FormulaParser.evaluate(FormulaParser.compile("3 >= 4"), Map.of()), 1e-9);
        assertEquals(1.0, FormulaParser.evaluate(FormulaParser.compile("4 > 3"), Map.of()), 1e-9);
    }

    @Test
    @DisplayName("Logical ops: && || ! with != 0 truthiness")
    void testLogicalOps() {
        assertEquals(1.0, FormulaParser.evaluate(FormulaParser.compile("(1<2) && (3>2)"), Map.of()), 1e-9);
        assertEquals(0.0, FormulaParser.evaluate(FormulaParser.compile("(1<2) && (3<2)"), Map.of()), 1e-9);
        assertEquals(1.0, FormulaParser.evaluate(FormulaParser.compile("0 || 1"), Map.of()), 1e-9);
        assertEquals(1.0, FormulaParser.evaluate(FormulaParser.compile("!(1>2)"), Map.of()), 1e-9);
        assertEquals(0.0, FormulaParser.evaluate(FormulaParser.compile("!0.5"), Map.of()), 1e-9);
    }

    @Test
    @DisplayName("Precedence: && binds tighter than || (1 || 0 && 0 == 1)")
    void testLogicalPrecedence() {
        assertEquals(1.0, FormulaParser.evaluate(FormulaParser.compile("1 || 0 && 0"), Map.of()), 1e-9);
        assertEquals(0.0, FormulaParser.evaluate(FormulaParser.compile("0 || 0 && 1 || 0"), Map.of()), 1e-9);
    }

    @Test
    @DisplayName("Comparison binds tighter than &&: 1 < 2 && 2 < 1 is false")
    void testComparisonPrecedence() {
        assertEquals(0.0, FormulaParser.evaluate(FormulaParser.compile("1 < 2 && 2 < 1"), Map.of()), 1e-9);
    }

    // ── 旧脚本逐位不变 / legacy bit-identity ──

    @Test
    @DisplayName("Legacy RPN shape unchanged: compile(2+3*4)")
    void testLegacyRpnShape() {
        assertEquals(List.of(2.0, 3.0, 4.0, '*', '+'), FormulaParser.compile("2+3*4"));
    }

    @Test
    @DisplayName("Legacy evaluate adapter returns identical scalars")
    void testLegacyEvaluateIdentity() {
        var rpn = FormulaParser.compile("sin(30) + 2 * 3");
        double legacy = FormulaParser.evaluate(rpn, Map.of());
        double unified = FormulaParser.asScalar(
            FormulaParser.evaluateValue(rpn, Map.of()));
        assertEquals(legacy, unified, 0.0);
    }

    // ── parseScript 与比较运算符 / parseScript with comparison operators ──

    @Test
    @DisplayName("parseScript: x = a == b parses as assignment with comparison RHS")
    void testParseScriptAssignmentWithComparison() {
        var result = FormulaParser.parseScript("x = a == b");
        assertEquals(1, result.assignments.size());
        assertEquals("x", result.assignments.get(0).varName());
        assertTrue(result.inputVars.contains("a"));
        assertTrue(result.inputVars.contains("b"));
        // 求值: a=1, b=1 → x=1 / evaluate: a=1, b=1 → x=1
        var vars = Map.of("a", 1.0, "b", 1.0);
        assertEquals(1.0, FormulaParser.evaluate(result.assignments.get(0).rpn(), vars), 1e-9);
    }

    @Test
    @DisplayName("parseScript: standalone a == b becomes the default output")
    void testParseScriptStandaloneComparison() {
        var result = FormulaParser.parseScript("a == b");
        assertEquals(1, result.outputRpns.size());
        assertEquals(0.0, FormulaParser.evaluate(result.outputRpns.get(0), Map.of("a", 1.0, "b", 2.0)), 1e-9);
        assertEquals(1.0, FormulaParser.evaluate(result.outputRpns.get(0), Map.of("a", 2.0, "b", 2.0)), 1e-9);
    }

    // ── validate 新规则 / new validation rules ──

    @Test
    @DisplayName("validate: unclosed brace is ERROR, extra brace is ERROR")
    void testValidateBraceMatching() {
        assertTrue(FormulaParser.validate("repeat 5 {").stream()
            .anyMatch(i -> i.severity() == FormulaParser.Severity.ERROR && i.message().contains("左大括号")));
        assertTrue(FormulaParser.validate("}").stream()
            .anyMatch(i -> i.severity() == FormulaParser.Severity.ERROR && i.message().contains("右大括号")));
    }

    @Test
    @DisplayName("validate: repeat without number is ERROR")
    void testValidateRepeatSyntax() {
        assertTrue(FormulaParser.validate("repeat x { }").stream()
            .anyMatch(i -> i.severity() == FormulaParser.Severity.ERROR && i.message().contains("repeat")));
    }

    @Test
    @DisplayName("validate: empty block is WARN")
    void testValidateEmptyBlock() {
        assertTrue(FormulaParser.validate("repeat 5 {}").stream()
            .anyMatch(i -> i.severity() == FormulaParser.Severity.WARN && i.message().contains("空块")));
    }

    @Test
    @DisplayName("validate: while(true) is WARN (no exit condition)")
    void testValidateWhileTrue() {
        assertTrue(FormulaParser.validate("while (1) {}").stream()
            .anyMatch(i -> i.severity() == FormulaParser.Severity.WARN && i.message().contains("退出条件")));
    }

    @Test
    @DisplayName("validate: invalid swizzle component is ERROR")
    void testValidateSwizzleComponent() {
        assertTrue(FormulaParser.validate("v.q").stream()
            .anyMatch(i -> i.severity() == FormulaParser.Severity.ERROR && i.message().contains("分量")));
    }

    @Test
    @DisplayName("validate: valid new syntax produces no ERROR")
    void testValidateCleanNewSyntax() {
        var issues = FormulaParser.validate("repeat 10 { x = x + 1 }\nif (x > 0) { y = 1 } else { y = 2 }");
        assertFalse(issues.stream().anyMatch(i -> i.severity() == FormulaParser.Severity.ERROR));
    }
}
