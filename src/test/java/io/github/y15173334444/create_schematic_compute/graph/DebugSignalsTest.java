package io.github.y15173334444.create_schematic_compute.graph;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link DebugSignals} — signal generation for DEBUG_SIGNAL_GEN node.
 */
class DebugSignalsTest {

    @Test
    @DisplayName("Default manual curve (two control points at y=0) returns 0 at any x")
    void testManualCurveDefault() {
        float[] cx = {0f, 1f};
        float[] cy = {0f, 0f};
        assertEquals(0f, DebugSignals.computeCurve(DebugSignals.SET_MANUAL, 0.5f, cx, cy, null, null), 0.0001f);
        assertEquals(0f, DebugSignals.computeCurve(DebugSignals.SET_MANUAL, 0f, cx, cy, null, null), 0.0001f);
        assertEquals(0f, DebugSignals.computeCurve(DebugSignals.SET_MANUAL, 1f, cx, cy, null, null), 0.0001f);
    }

    @Test
    @DisplayName("Custom control points: peak at x=0.5")
    void testManualCurveCustomPeak() {
        float[] cx = {0f, 0.5f, 1f};
        float[] cy = {0f, 1f, 0f};
        assertEquals(0.5f, DebugSignals.computeCurve(DebugSignals.SET_MANUAL, 0.25f, cx, cy, null, null), 0.01f);
        assertEquals(1.0f, DebugSignals.computeCurve(DebugSignals.SET_MANUAL, 0.5f, cx, cy, null, null), 0.01f);
        assertEquals(0.5f, DebugSignals.computeCurve(DebugSignals.SET_MANUAL, 0.75f, cx, cy, null, null), 0.01f);
    }

    @Test
    @DisplayName("Manual curve: x < 0 clamped to first control point")
    void testManualCurveClampedLow() {
        float[] cx = {0f, 1f};
        float[] cy = {0.5f, -0.5f};
        assertEquals(0.5f, DebugSignals.computeCurve(DebugSignals.SET_MANUAL, -0.5f, cx, cy, null, null), 0.0001f);
    }

    @Test
    @DisplayName("Manual curve: x > 1 clamped to last control point")
    void testManualCurveClampedHigh() {
        float[] cx = {0f, 1f};
        float[] cy = {0.5f, -0.5f};
        assertEquals(-0.5f, DebugSignals.computeCurve(DebugSignals.SET_MANUAL, 2.0f, cx, cy, null, null), 0.0001f);
    }

    @Test
    @DisplayName("Formula mode: f(x)=x*2 returns 1.0 at x=0.5")
    void testFormulaCurve() {
        String formula = "x*2";
        var rpn = DebugSignals.compileFormula(formula);
        assertNotNull(rpn);
        assertEquals(1.0f, DebugSignals.computeCurve(DebugSignals.SET_FORMULA, 0.5f, null, null, formula, rpn), 0.0001f);
    }

    @Test
    @DisplayName("Formula mode: empty formula returns 0")
    void testFormulaEmptyReturnsZero() {
        assertEquals(0f, DebugSignals.computeCurve(DebugSignals.SET_FORMULA, 0.5f, null, null, "", null), 0.0001f);
    }

    @Test
    @DisplayName("compileFormula: valid formula returns non-null RPN")
    void testCompileFormulaValid() {
        assertNotNull(DebugSignals.compileFormula("x*2+1"));
    }

    @Test
    @DisplayName("compileFormula: null/blank returns null")
    void testCompileFormulaNull() {
        assertNull(DebugSignals.compileFormula(null));
        assertNull(DebugSignals.compileFormula(""));
    }

    @Test
    @DisplayName("Formula with trig (degrees): sin(x*360) cycle")
    void testFormulaTrigDegrees() {
        String formula = "sin(x*360)";
        var rpn = DebugSignals.compileFormula(formula);
        // At x=0: sin(0) = 0
        assertEquals(0f, DebugSignals.computeCurve(DebugSignals.SET_FORMULA, 0f, null, null, formula, rpn), 0.001f);
        // At x=0.25: sin(90) = 1
        assertEquals(1f, DebugSignals.computeCurve(DebugSignals.SET_FORMULA, 0.25f, null, null, formula, rpn), 0.001f);
        // At x=0.5: sin(180) = 0
        assertEquals(0f, DebugSignals.computeCurve(DebugSignals.SET_FORMULA, 0.5f, null, null, formula, rpn), 0.001f);
        // At x=0.75: sin(270) = -1
        assertEquals(-1f, DebugSignals.computeCurve(DebugSignals.SET_FORMULA, 0.75f, null, null, formula, rpn), 0.001f);
    }

    @Test
    @DisplayName("setModeName returns mode names for valid modes")
    void testSetModeName() {
        assertNotNull(DebugSignals.setModeName(0));
        assertNotNull(DebugSignals.setModeName(1));
    }

    @Test
    @DisplayName("setModeName returns ? for invalid mode")
    void testSetModeNameInvalid() {
        assertEquals("?", DebugSignals.setModeName(99));
    }
}
