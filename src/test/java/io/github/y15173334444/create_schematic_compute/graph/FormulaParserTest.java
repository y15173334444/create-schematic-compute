package io.github.y15173334444.create_schematic_compute.graph;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link FormulaParser} — parsing, compilation, and evaluation.
 */
class FormulaParserTest {

    // ── Compile (RPN generation) ──

    @Test
    @DisplayName("Basic arithmetic: compile returns non-empty RPN")
    void testCompileArithmetic() {
        List<Object> rpn = FormulaParser.compile("2+3*4");
        assertNotNull(rpn);
        assertFalse(rpn.isEmpty());
    }

    @Test
    @DisplayName("Compile with parentheses returns valid RPN")
    void testCompileParentheses() {
        List<Object> rpn = FormulaParser.compile("(1+2)*3");
        assertNotNull(rpn);
        assertFalse(rpn.isEmpty());
    }

    @Test
    @DisplayName("Unary minus: compile(-5) returns valid RPN")
    void testCompileUnaryMinus() {
        List<Object> rpn = FormulaParser.compile("-5");
        assertNotNull(rpn);
        assertFalse(rpn.isEmpty());
    }

    @Test
    @DisplayName("Function call: sin(45) compiles successfully")
    void testCompileFunctionCall() {
        List<Object> rpn = FormulaParser.compile("sin(45)");
        assertNotNull(rpn);
        assertFalse(rpn.isEmpty());
    }

    @Test
    @DisplayName("atan2(y, x) two-argument function compiles")
    void testCompileAtan2() {
        List<Object> rpn = FormulaParser.compile("atan2(1, 2)");
        assertNotNull(rpn);
        assertFalse(rpn.isEmpty());
    }

    @Test
    @DisplayName("Full-width parentheses auto-converted to half-width")
    void testCompileFullWidthParens() {
        // Full-width （ and ） are auto-converted to half-width ( and )
        List<Object> rpn = FormulaParser.compile("sin（45）");
        assertNotNull(rpn);
        assertFalse(rpn.isEmpty());
    }

    // ── Evaluate (RPN execution) ──

    @Test
    @DisplayName("Evaluate simple expression: 2+3*4 = 14")
    void testEvaluateSimple() {
        double result = FormulaParser.evaluate(FormulaParser.compile("2+3*4"), Map.of());
        assertEquals(14.0, result, 0.0001);
    }

    @Test
    @DisplayName("Evaluate with variables: A+B")
    void testEvaluateWithVariables() {
        double result = FormulaParser.evaluate(
            FormulaParser.compile("A+B"),
            Map.of("A", 3.0, "B", 7.0));
        assertEquals(10.0, result, 0.0001);
    }

    @Test
    @DisplayName("Trig functions use degrees: sin(90) = 1")
    void testTrigSin90() {
        double result = FormulaParser.evaluate(FormulaParser.compile("sin(90)"), Map.of());
        assertEquals(1.0, result, 0.0001);
    }

    @Test
    @DisplayName("cos(0) = 1")
    void testTrigCos0() {
        double result = FormulaParser.evaluate(FormulaParser.compile("cos(0)"), Map.of());
        assertEquals(1.0, result, 0.0001);
    }

    @Test
    @DisplayName("tan(45) ≈ 1")
    void testTrigTan45() {
        double result = FormulaParser.evaluate(FormulaParser.compile("tan(45)"), Map.of());
        assertEquals(1.0, result, 0.01);
    }

    @Test
    @DisplayName("Division by zero returns 0 safely")
    void testDivisionByZero() {
        double result = FormulaParser.evaluate(FormulaParser.compile("5/0"), Map.of());
        assertEquals(0.0, result, 0.0001);
    }

    @Test
    @DisplayName("Modulo by zero returns 0 safely")
    void testModuloByZero() {
        double result = FormulaParser.evaluate(FormulaParser.compile("5%0"), Map.of());
        assertEquals(0.0, result, 0.0001);
    }

    @Test
    @DisplayName("sqrt of negative returns 0")
    void testSqrtNegative() {
        double result = FormulaParser.evaluate(FormulaParser.compile("sqrt(-1)"), Map.of());
        assertEquals(0.0, result, 0.0001);
    }

    @Test
    @DisplayName("ln of zero returns 0")
    void testLnZero() {
        double result = FormulaParser.evaluate(FormulaParser.compile("ln(0)"), Map.of());
        assertEquals(0.0, result, 0.0001);
    }

    @Test
    @DisplayName("sec/csc: reciprocal trig")
    void testReciprocalTrig() {
        double sec0 = FormulaParser.evaluate(FormulaParser.compile("sec(0)"), Map.of());
        assertEquals(1.0, sec0, 0.0001);
        double csc90 = FormulaParser.evaluate(FormulaParser.compile("csc(90)"), Map.of());
        assertEquals(1.0, csc90, 0.0001);
    }

    @Test
    @DisplayName("exp(ln(5)) ≈ 5")
    void testExpLn() {
        double result = FormulaParser.evaluate(FormulaParser.compile("exp(ln(5))"), Map.of());
        assertEquals(5.0, result, 0.01);
    }

    // ── Variable extraction ──

    @Test
    @DisplayName("extractVariables filters out function names")
    void testExtractVariables() {
        List<String> vars = FormulaParser.extractVariables("A*B+sin(C)");
        assertTrue(vars.contains("A"));
        assertTrue(vars.contains("B"));
        assertTrue(vars.contains("C"));
        assertFalse(vars.contains("sin"));
    }

    @Test
    @DisplayName("extractVariables returns empty for pure constants")
    void testExtractVariablesConstants() {
        List<String> vars = FormulaParser.extractVariables("2+3*4");
        assertTrue(vars.isEmpty());
    }

    // ── Script parsing (v1.2+) ──

    @Test
    @DisplayName("parseScript legacy mode: single expression")
    void testParseScriptLegacy() {
        FormulaParser.ScriptParseResult result = FormulaParser.parseScript("A+1");
        assertTrue(result.isLegacy);
        // Legacy mode produces one default output (empty label)
        assertEquals(1, result.outputRpns.size());
        assertEquals(1, result.inputVars.size());
        assertEquals("A", result.inputVars.get(0));
    }

    @Test
    @DisplayName("parseScript multi-line with assignments and @output")
    void testParseScriptMultiLine() {
        FormulaParser.ScriptParseResult result = FormulaParser.parseScript(
            "x = A*2\n@output x");
        assertFalse(result.isLegacy);
        assertEquals(1, result.outputLabels.size());
        assertEquals("x", result.outputLabels.get(0));
        assertEquals(1, result.inputVars.size());
        assertEquals("A", result.inputVars.get(0));
        assertEquals(1, result.assignments.size());
        assertEquals("x", result.assignments.get(0).varName());
    }

    @Test
    @DisplayName("parseScript comments: -- lines ignored")
    void testParseScriptComments() {
        FormulaParser.ScriptParseResult result = FormulaParser.parseScript(
            "-- this is a comment\nx = 5\n@output x");
        assertFalse(result.isLegacy);
        assertEquals("x", result.outputLabels.get(0));
    }

    @Test
    @DisplayName("parseScript with multiple outputs")
    void testParseScriptMultipleOutputs() {
        FormulaParser.ScriptParseResult result = FormulaParser.parseScript(
            "sum = A+B\ndiff = A-B\n@output sum\n@output diff");
        assertEquals(2, result.outputLabels.size());
        assertEquals("sum", result.outputLabels.get(0));
        assertEquals("diff", result.outputLabels.get(1));
    }

    // ═══════════════════════════ Tokenize ═══════════════════════════

    @Test
    @DisplayName("tokenize: FUNCTION consumes LPAREN — sin(PI) → PI is IDENT not CONSTANT")
    void testTokenizeFunctionConsumesLParen() {
        var tokens = FormulaParser.tokenize("sin(PI)");
        // Should be: FUNCTION sin, IDENT PI, RPAREN )
        // NOT: FUNCTION sin, LPAREN (, CONSTANT PI, RPAREN )
        assertEquals(3, tokens.size(), "sin(PI) should produce 3 tokens");
        assertEquals(FormulaParser.TokType.FUNCTION, tokens.get(0).type());
        assertEquals("sin", tokens.get(0).text());
        assertEquals(FormulaParser.TokType.IDENT, tokens.get(1).type(),
            "PI in sin(PI) must be IDENT (variable), not CONSTANT (literal)");
        assertEquals("PI", tokens.get(1).text());
        assertEquals(FormulaParser.TokType.RPAREN, tokens.get(2).type());
    }

    @Test
    @DisplayName("tokenize: (PI) → PI is CONSTANT (grouping parentheses)")
    void testTokenizeGroupingParenPI() {
        var tokens = FormulaParser.tokenize("(PI)");
        assertEquals(3, tokens.size());
        assertEquals(FormulaParser.TokType.LPAREN, tokens.get(0).type());
        assertEquals(FormulaParser.TokType.CONSTANT, tokens.get(1).type(),
            "PI in (PI) must be CONSTANT (literal)");
        assertEquals("PI", tokens.get(1).text());
        assertEquals(FormulaParser.TokType.RPAREN, tokens.get(2).type());
    }

    @Test
    @DisplayName("tokenize: bare PI (no preceding paren) is IDENT")
    void testTokenizeBarePI() {
        var tokens = FormulaParser.tokenize("PI + 1");
        assertEquals(3, tokens.size()); // IDENT PI, OPERATOR +, NUMBER 1
        assertEquals(FormulaParser.TokType.IDENT, tokens.get(0).type(),
            "Bare PI must be IDENT (variable)");
    }

    @Test
    @DisplayName("tokenize: nested function sin(cos(x)) — both arg LPARENs consumed")
    void testTokenizeNestedFunction() {
        var tokens = FormulaParser.tokenize("sin(cos(x))");
        // FUNCTION sin, FUNCTION cos, IDENT x, RPAREN ), RPAREN )
        assertEquals(5, tokens.size());
        assertEquals(FormulaParser.TokType.FUNCTION, tokens.get(0).type());
        assertEquals("sin", tokens.get(0).text());
        assertEquals(FormulaParser.TokType.FUNCTION, tokens.get(1).type());
        assertEquals("cos", tokens.get(1).text());
        assertEquals(FormulaParser.TokType.IDENT, tokens.get(2).type());
        assertEquals("x", tokens.get(2).text());
    }

    @Test
    @DisplayName("tokenize: (sin(PI)) grouping around function call")
    void testTokenizeGroupedFunctionCall() {
        var tokens = FormulaParser.tokenize("(sin(PI))");
        // LPAREN (, FUNCTION sin, IDENT PI, RPAREN ), RPAREN )
        assertEquals(5, tokens.size());
        assertEquals(FormulaParser.TokType.LPAREN, tokens.get(0).type());
        assertEquals(FormulaParser.TokType.FUNCTION, tokens.get(1).type());
        assertEquals(FormulaParser.TokType.IDENT, tokens.get(2).type(),
            "PI inside sin() must be IDENT even when sin() is inside grouping parens");
    }

    // ═══════════════════════════ extractVariables ═══════════════════════════

    @Test
    @DisplayName("extractVariables: sin(PI) — PI is a variable")
    void testExtractVarsSinPI() {
        var vars = FormulaParser.extractVariables("sin(PI)");
        assertTrue(vars.contains("PI"),
            "PI in sin(PI) must be a variable (input pin), not skipped");
    }

    @Test
    @DisplayName("extractVariables: (PI) — PI is a literal, NOT a variable")
    void testExtractVarsGroupingPI() {
        var vars = FormulaParser.extractVariables("(PI)");
        assertFalse(vars.contains("PI"),
            "PI in (PI) must be skipped (literal, no input pin)");
    }

    @Test
    @DisplayName("extractVariables: bare PI is a variable")
    void testExtractVarsBarePI() {
        var vars = FormulaParser.extractVariables("PI + E");
        assertTrue(vars.contains("PI"));
        assertTrue(vars.contains("E"));
    }

    @Test
    @DisplayName("extractVariables: PI in assignment RHS is a variable")
    void testExtractVarsPIInExpr() {
        var vars = FormulaParser.extractVariables("A + sin(PI) + (E)");
        assertTrue(vars.contains("A"));
        assertTrue(vars.contains("PI"), "PI in sin(PI) is a variable");
        assertFalse(vars.contains("E"), "E in (E) is a literal");
    }

    // ═══════════════════════════ Compile + Evaluate (PI/E disambiguation) ═══════════════════════════

    @Test
    @DisplayName("compile/evaluate: sin(PI) uses PI as variable input")
    void testEvalSinPIVariable() {
        // sin(PI) where PI=1.0 (degrees) → sin(1°) ≈ 0.01745
        double result = FormulaParser.evaluate(
            FormulaParser.compile("sin(PI)"), Map.of("PI", 1.0));
        assertEquals(Math.sin(Math.toRadians(1.0)), result, 0.0001);
    }

    @Test
    @DisplayName("compile/evaluate: (PI) is literal π ≈ 3.14159")
    void testEvalGroupingPILiteral() {
        // (PI) is literal π → sin(π rad) = sin(180°) = 0
        double result = FormulaParser.evaluate(
            FormulaParser.compile("sin((PI))"), Map.of());
        assertEquals(0.0, result, 0.0001,
            "sin((PI)) where (PI) is literal π → sin(180°) = 0");
    }

    @Test
    @DisplayName("compile: RPN for sin(PI) contains 'PI' string (variable ref)")
    void testCompileSinPIRpnHasStringPI() {
        var rpn = FormulaParser.compile("sin(PI)");
        assertTrue(rpn.stream().anyMatch(tok -> "PI".equals(tok)),
            "RPN must contain 'PI' as a variable reference string");
    }

    @Test
    @DisplayName("compile: RPN for (PI) contains Double (literal), no 'PI' string")
    void testCompileGroupingPIRpnHasDouble() {
        var rpn = FormulaParser.compile("(PI) + 1");
        assertTrue(rpn.stream().anyMatch(tok -> tok instanceof Double),
            "RPN must contain Double for literal (PI)");
        assertFalse(rpn.stream().anyMatch(tok -> "PI".equals(tok)),
            "RPN must NOT contain 'PI' string when it's a grouping literal");
    }

    @Test
    @DisplayName("compile/evaluate: (PI) + (E) uses both literals")
    void testEvalBothLiterals() {
        double result = FormulaParser.evaluate(
            FormulaParser.compile("(PI)+(E)"), Map.of());
        assertEquals(Math.PI + Math.E, result, 0.0001);
    }

    // ═══════════════════════════ Validate ═══════════════════════════

    @Test
    @DisplayName("validate: empty/null formula has no issues")
    void testValidateEmpty() {
        assertTrue(FormulaParser.validate(null).isEmpty());
        assertTrue(FormulaParser.validate("").isEmpty());
    }

    @Test
    @DisplayName("validate: valid expression has no issues")
    void testValidateClean() {
        assertTrue(FormulaParser.validate("sin(x) + 1").isEmpty());
    }

    @Test
    @DisplayName("validate: unmatched opening bracket is an error")
    void testValidateUnmatchedOpen() {
        var issues = FormulaParser.validate("sin(x");
        assertFalse(issues.isEmpty());
        assertTrue(issues.stream().anyMatch(i -> i.message().contains("未闭合")));
    }

    @Test
    @DisplayName("validate: unmatched closing bracket is an error")
    void testValidateUnmatchedClose() {
        var issues = FormulaParser.validate("sin(x))");
        assertFalse(issues.isEmpty());
        assertTrue(issues.stream().anyMatch(i -> i.message().contains("多余的")));
    }

    @Test
    @DisplayName("validate: unknown function is an error")
    void testValidateUnknownFunction() {
        var issues = FormulaParser.validate("foo(x)");
        assertFalse(issues.isEmpty());
        assertTrue(issues.stream().anyMatch(i -> i.message().contains("未知函数")));
    }

    @Test
    @DisplayName("validate: wrong arity is an error (sin with 2 args)")
    void testValidateWrongArity() {
        var issues = FormulaParser.validate("sin(x, y)");
        assertFalse(issues.isEmpty());
        assertTrue(issues.stream().anyMatch(i ->
            i.message().contains("期望") && i.message().contains("参数")));
    }

    @Test
    @DisplayName("validate: correct arity has no error (atan2 with 2 args)")
    void testValidateCorrectArity() {
        var issues = FormulaParser.validate("atan2(y, x)");
        assertTrue(issues.stream().noneMatch(i -> i.message().contains("参数")),
            "atan2(y,x) has correct arity, should not report arity error");
    }

    @Test
    @DisplayName("validate: assignment left side must be identifier")
    void testValidateBadAssignment() {
        var issues = FormulaParser.validate("5 = x + 1");
        assertFalse(issues.isEmpty());
        assertTrue(issues.stream().anyMatch(i -> i.message().contains("赋值")));
    }

    @Test
    @DisplayName("validate: unknown character '.' is an error")
    void testValidateUnknownChar() {
        var issues = FormulaParser.validate("x . y");
        assertFalse(issues.isEmpty());
        assertTrue(issues.stream().anyMatch(i -> i.message().contains("无法识别")));
    }

    @Test
    @DisplayName("validate: @output with invalid start is a warning (expressions are valid since knife 3/4)")
    void testValidateOutputMissingVar() {
        var issues = FormulaParser.validate("@output )\nx=1");
        assertFalse(issues.isEmpty());
        assertTrue(issues.stream().anyMatch(i -> i.severity() == FormulaParser.Severity.WARN));
        // 表达式输出合法(刀3/4):@output 5 / @output length(v) 不再误报 / expression outputs are legal
        assertTrue(FormulaParser.validate("@output 5\nx=1").stream()
            .noneMatch(i -> i.message().contains("合法的变量名")));
        assertTrue(FormulaParser.validate("v = vec3(1, 2, 3)\n@output length(v)").stream()
            .noneMatch(i -> i.message().contains("合法的变量名")));
    }

    // ═══════════════════════════ parseScript + PI/E ═══════════════════════════

    @Test
    @DisplayName("parseScript legacy: sin(PI) → PI is an input variable")
    void testParseScriptLegacySinPI() {
        var result = FormulaParser.parseScript("sin(PI)");
        assertTrue(result.isLegacy);
        assertTrue(result.inputVars.contains("PI"),
            "Legacy mode: PI in sin(PI) must be an input variable");
    }

    @Test
    @DisplayName("parseScript legacy: (PI) → PI is NOT an input variable")
    void testParseScriptLegacyGroupingPI() {
        var result = FormulaParser.parseScript("(PI)+1");
        assertTrue(result.isLegacy);
        assertFalse(result.inputVars.contains("PI"),
            "Legacy mode: (PI) is literal, must NOT be an input variable");
    }

    @Test
    @DisplayName("parseScript multi-line: sin(PI) with assignment")
    void testParseScriptMultiLineSinPI() {
        var result = FormulaParser.parseScript("x = sin(PI)\n@output x");
        assertFalse(result.isLegacy);
        // PI in sin(PI) should be an external input
        assertTrue(result.inputVars.contains("PI"));
    }

    // ═══════════════════════════ countFunctionArgs (via validate) ═══════════════════════════

    @Test
    @DisplayName("countFunctionArgs: sin(x) = 1 arg (no LPAREN token)")
    void testCountArgsSimple() {
        // No arity error expected — sin(x) has correct arity 1
        var issues = FormulaParser.validate("sin(x)");
        assertTrue(issues.stream().noneMatch(i -> i.message().contains("参数")),
            "sin(x) has correct arity, no LPAREN token after FUNCTION");
    }

    @Test
    @DisplayName("countFunctionArgs: atan2(y, x) = 2 args")
    void testCountArgsTwoArgs() {
        var issues = FormulaParser.validate("atan2(y, x)");
        assertTrue(issues.stream().noneMatch(i -> i.message().contains("参数")));
    }

    @Test
    @DisplayName("countFunctionArgs: sin() = 0 args → arity mismatch detected")
    void testCountArgsZero() {
        var issues = FormulaParser.validate("sin()");
        assertTrue(issues.stream().anyMatch(i -> i.message().contains("参数")));
    }

    @Test
    @DisplayName("countFunctionArgs: sin((x)) nested grouping = 1 arg")
    void testCountArgsNestedGrouping() {
        var issues = FormulaParser.validate("sin((x))");
        assertTrue(issues.stream().noneMatch(i -> i.message().contains("参数")),
            "sin((x)) has 1 arg with nested grouping parens");
    }

    @Test
    @DisplayName("countFunctionArgs: sin(cos(x)) nested function = 1 arg")
    void testCountArgsNestedFunction() {
        var issues = FormulaParser.validate("sin(cos(x))");
        // sin has 1 arg (cos(x)), cos has 1 arg (x) → no arity errors for either
        assertTrue(issues.stream().noneMatch(i -> i.message().contains("参数")),
            "sin(cos(x)) — both functions have correct arity");
    }

    @Test
    @DisplayName("countFunctionArgs: function with comma inside nested call")
    void testCountArgsCommaInNested() {
        var issues = FormulaParser.validate("atan2(sin(x), cos(y))");
        assertTrue(issues.stream().noneMatch(i -> i.message().contains("参数")),
            "atan2(sin(x), cos(y)) — atan2 has 2 args (comma at depth 0)");
    }

    // ═══════════════════════════ Edge cases ═══════════════════════════

    @Test
    @DisplayName("Mixed: (PI) literal + bare PI variable in same expression")
    void testMixedLiteralAndVariablePI() {
        var result = FormulaParser.parseScript("(PI) + PI");
        // (PI) is literal, bare PI is variable → only bare PI in inputVars
        assertTrue(result.inputVars.contains("PI"),
            "Bare PI is a variable, even when (PI) literal appears elsewhere");
        assertEquals(1, result.inputVars.size());
    }

    @Test
    @DisplayName("tokenize: full-width parens normalized before tokenization")
    void testTokenizeFullWidthParens() {
        var tokens = FormulaParser.tokenize("sin（PI）");
        assertEquals(3, tokens.size());
        assertEquals(FormulaParser.TokType.FUNCTION, tokens.get(0).type());
        assertEquals(FormulaParser.TokType.IDENT, tokens.get(1).type(),
            "PI after full-width （ must still be IDENT (inside function call)");
    }

    @Test
    @DisplayName("tokenize: comment is recognized")
    void testTokenizeComment() {
        var tokens = FormulaParser.tokenize("x + 1 -- comment\n y");
        assertTrue(tokens.stream().anyMatch(t -> t.type() == FormulaParser.TokType.COMMENT));
        assertTrue(tokens.stream().anyMatch(t -> t.text().equals("-- comment")));
    }

    @Test
    @DisplayName("tokenize: @output marker is recognized")
    void testTokenizeAtOutput() {
        var tokens = FormulaParser.tokenize("@output x");
        assertEquals(2, tokens.size());
        assertEquals(FormulaParser.TokType.AT_OUTPUT, tokens.get(0).type());
        assertEquals(FormulaParser.TokType.IDENT, tokens.get(1).type());
    }

    @Test
    @DisplayName("tokenize: assignment operator is recognized")
    void testTokenizeAssignment() {
        var tokens = FormulaParser.tokenize("x = 5");
        assertEquals(3, tokens.size());
        assertEquals(FormulaParser.TokType.IDENT, tokens.get(0).type());
        assertEquals(FormulaParser.TokType.ASSIGN, tokens.get(1).type());
        assertEquals(FormulaParser.TokType.NUMBER, tokens.get(2).type());
    }
}
