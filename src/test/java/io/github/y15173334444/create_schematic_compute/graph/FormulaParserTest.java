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
}
