package io.github.y15173334444.create_schematic_compute.graph;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link GraphEvaluator} — the core node-graph evaluation engine.
 * <p>
 * Covers: CONST, arithmetic (ADD/SUB/MUL/DIV/MOD/POW/ROOT),
 * ABS/ROUND/CLAMP, COMPARE/LERP/MAP_RANGE, SPLIT, RELAY_A/B,
 * POSE_CONVERT, MIN/MAX/AVG, and multi-node chains.
 */
class GraphEvaluatorTest {

    private NodeGraph graph;
    private GraphEvaluator evaluator;

    @BeforeEach
    void setUp() {
        graph = new NodeGraph();
    }

    // ══════════════════ CONST node ══════════════════

    @Test
    @DisplayName("CONST: outputs its param value")
    void testConstOutput() {
        var node = graph.addNode(NodeType.CONST, 0, 0);
        node.params[0] = 42f;
        evaluator = new GraphEvaluator(graph);

        evaluator.evaluate(List.of(), Map.of(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));

        assertEquals(42f, evaluator.getNodeOutput(node.id, 0), 0.0001f);
    }

    @Test
    @DisplayName("CONST: default param value is 1")
    void testConstDefault() {
        var node = graph.addNode(NodeType.CONST, 0, 0);
        evaluator = new GraphEvaluator(graph);

        evaluator.evaluate(List.of(), Map.of(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));

        assertEquals(1f, evaluator.getNodeOutput(node.id, 0), 0.0001f);
    }

    @Test
    @DisplayName("CONST: negative value")
    void testConstNegative() {
        var node = graph.addNode(NodeType.CONST, 0, 0);
        node.params[0] = -3.5f;
        evaluator = new GraphEvaluator(graph);

        evaluator.evaluate(List.of(), Map.of(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));

        assertEquals(-3.5f, evaluator.getNodeOutput(node.id, 0), 0.0001f);
    }

    // ══════════════════ Arithmetic nodes ══════════════════

    @Test
    @DisplayName("ADD: 3 + 7 = 10")
    void testAdd() {
        var a = graph.addNode(NodeType.CONST, 0, 0);
        var b = graph.addNode(NodeType.CONST, 100, 0);
        var add = graph.addNode(NodeType.ADD, 200, 0);
        a.params[0] = 3f;
        b.params[0] = 7f;
        graph.addConnection(a.id, 0, add.id, 0);
        graph.addConnection(b.id, 0, add.id, 1);
        evaluator = new GraphEvaluator(graph);

        evaluator.evaluate(List.of(), Map.of(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));

        assertEquals(10f, evaluator.getNodeOutput(add.id, 0), 0.0001f);
    }

    @Test
    @DisplayName("SUB: 10 - 4 = 6")
    void testSub() {
        var a = graph.addNode(NodeType.CONST, 0, 0);
        var b = graph.addNode(NodeType.CONST, 50, 0);
        var sub = graph.addNode(NodeType.SUB, 100, 0);
        a.params[0] = 10f;
        b.params[0] = 4f;
        graph.addConnection(a.id, 0, sub.id, 0);
        graph.addConnection(b.id, 0, sub.id, 1);
        evaluator = new GraphEvaluator(graph);

        evaluator.evaluate(List.of(), Map.of(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));

        assertEquals(6f, evaluator.getNodeOutput(sub.id, 0), 0.0001f);
    }

    @Test
    @DisplayName("MUL: 6 * 7 = 42")
    void testMul() {
        var a = graph.addNode(NodeType.CONST, 0, 0);
        var b = graph.addNode(NodeType.CONST, 50, 0);
        var mul = graph.addNode(NodeType.MUL, 100, 0);
        a.params[0] = 6f;
        b.params[0] = 7f;
        graph.addConnection(a.id, 0, mul.id, 0);
        graph.addConnection(b.id, 0, mul.id, 1);
        evaluator = new GraphEvaluator(graph);

        evaluator.evaluate(List.of(), Map.of(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));

        assertEquals(42f, evaluator.getNodeOutput(mul.id, 0), 0.0001f);
    }

    @Test
    @DisplayName("DIV: 15 / 3 = 5")
    void testDiv() {
        var a = graph.addNode(NodeType.CONST, 0, 0);
        var b = graph.addNode(NodeType.CONST, 50, 0);
        var div = graph.addNode(NodeType.DIV, 100, 0);
        a.params[0] = 15f;
        b.params[0] = 3f;
        graph.addConnection(a.id, 0, div.id, 0);
        graph.addConnection(b.id, 0, div.id, 1);
        evaluator = new GraphEvaluator(graph);

        evaluator.evaluate(List.of(), Map.of(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));

        assertEquals(5f, evaluator.getNodeOutput(div.id, 0), 0.0001f);
    }

    @Test
    @DisplayName("DIV: divide by zero returns 0")
    void testDivByZero() {
        var a = graph.addNode(NodeType.CONST, 0, 0);
        var b = graph.addNode(NodeType.CONST, 50, 0);
        var div = graph.addNode(NodeType.DIV, 100, 0);
        a.params[0] = 42f;
        b.params[0] = 0f;
        graph.addConnection(a.id, 0, div.id, 0);
        graph.addConnection(b.id, 0, div.id, 1);
        evaluator = new GraphEvaluator(graph);

        evaluator.evaluate(List.of(), Map.of(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));

        assertEquals(0f, evaluator.getNodeOutput(div.id, 0), 0.0001f);
    }

    @Test
    @DisplayName("MOD: 10 % 3 = 1")
    void testMod() {
        var a = graph.addNode(NodeType.CONST, 0, 0);
        var b = graph.addNode(NodeType.CONST, 50, 0);
        var mod = graph.addNode(NodeType.MOD, 100, 0);
        a.params[0] = 10f;
        b.params[0] = 3f;
        graph.addConnection(a.id, 0, mod.id, 0);
        graph.addConnection(b.id, 0, mod.id, 1);
        evaluator = new GraphEvaluator(graph);

        evaluator.evaluate(List.of(), Map.of(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));

        assertEquals(1f, evaluator.getNodeOutput(mod.id, 0), 0.0001f);
    }

    @Test
    @DisplayName("POW: 2 ^ 3 = 8")
    void testPow() {
        var a = graph.addNode(NodeType.CONST, 0, 0);
        var b = graph.addNode(NodeType.CONST, 50, 0);
        var pow = graph.addNode(NodeType.POW, 100, 0);
        a.params[0] = 2f;
        b.params[0] = 3f;
        graph.addConnection(a.id, 0, pow.id, 0);
        graph.addConnection(b.id, 0, pow.id, 1);
        evaluator = new GraphEvaluator(graph);

        evaluator.evaluate(List.of(), Map.of(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));

        assertEquals(8f, evaluator.getNodeOutput(pow.id, 0), 0.0001f);
    }

    @Test
    @DisplayName("ROOT: sqrt(16) = 4")
    void testRoot() {
        var a = graph.addNode(NodeType.CONST, 0, 0);
        var b = graph.addNode(NodeType.CONST, 50, 0);
        var root = graph.addNode(NodeType.ROOT, 100, 0);
        a.params[0] = 16f;
        b.params[0] = 2f;  // square root
        graph.addConnection(a.id, 0, root.id, 0);
        graph.addConnection(b.id, 0, root.id, 1);
        evaluator = new GraphEvaluator(graph);

        evaluator.evaluate(List.of(), Map.of(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));

        assertEquals(4f, evaluator.getNodeOutput(root.id, 0), 0.0001f);
    }

    // ══════════════════ Unary math nodes ══════════════════

    @Test
    @DisplayName("ABS: |-5| = 5")
    void testAbs() {
        var a = graph.addNode(NodeType.CONST, 0, 0);
        var abs = graph.addNode(NodeType.ABS, 50, 0);
        a.params[0] = -5f;
        graph.addConnection(a.id, 0, abs.id, 0);
        evaluator = new GraphEvaluator(graph);

        evaluator.evaluate(List.of(), Map.of(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));

        assertEquals(5f, evaluator.getNodeOutput(abs.id, 0), 0.0001f);
    }

    @Test
    @DisplayName("ROUND: 5.0 stays 5.0")
    void testRound() {
        var a = graph.addNode(NodeType.CONST, 0, 0);
        var round = graph.addNode(NodeType.ROUND, 50, 0);
        a.params[0] = 5f;  // whole number — no rounding change
        graph.addConnection(a.id, 0, round.id, 0);
        evaluator = new GraphEvaluator(graph);

        evaluator.evaluate(List.of(), Map.of(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));

        assertEquals(5f, evaluator.getNodeOutput(round.id, 0), 0.0001f);
    }

    @Test
    @DisplayName("CLAMP: clamp(15, 0, 10) = 10")
    void testClampHigh() {
        var val = graph.addNode(NodeType.CONST, 0, 0);
        var min = graph.addNode(NodeType.CONST, 50, 0);
        var max = graph.addNode(NodeType.CONST, 100, 0);
        var clamp = graph.addNode(NodeType.CLAMP, 150, 0);
        val.params[0] = 15f;
        min.params[0] = 0f;
        max.params[0] = 10f;
        graph.addConnection(val.id, 0, clamp.id, 0);
        graph.addConnection(min.id, 0, clamp.id, 1);
        graph.addConnection(max.id, 0, clamp.id, 2);
        evaluator = new GraphEvaluator(graph);

        evaluator.evaluate(List.of(), Map.of(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));

        assertEquals(10f, evaluator.getNodeOutput(clamp.id, 0), 0.0001f);
    }

    @Test
    @DisplayName("CLAMP: clamp(-5, 0, 10) = 0")
    void testClampLow() {
        var val = graph.addNode(NodeType.CONST, 0, 0);
        var min = graph.addNode(NodeType.CONST, 50, 0);
        var max = graph.addNode(NodeType.CONST, 100, 0);
        var clamp = graph.addNode(NodeType.CLAMP, 150, 0);
        val.params[0] = -5f;
        min.params[0] = 0f;
        max.params[0] = 10f;
        graph.addConnection(val.id, 0, clamp.id, 0);
        graph.addConnection(min.id, 0, clamp.id, 1);
        graph.addConnection(max.id, 0, clamp.id, 2);
        evaluator = new GraphEvaluator(graph);

        evaluator.evaluate(List.of(), Map.of(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));

        assertEquals(0f, evaluator.getNodeOutput(clamp.id, 0), 0.0001f);
    }

    // ══════════════════ GT / LT / EQ comparison operators ══════════════════

    @Test
    @DisplayName("GT: 5 > 3 → outputs 1")
    void testGt() {
        var a = graph.addNode(NodeType.CONST, 0, 0);
        var b = graph.addNode(NodeType.CONST, 50, 0);
        var gt = graph.addNode(NodeType.GT, 100, 0);
        a.params[0] = 5f; b.params[0] = 3f;
        graph.addConnection(a.id, 0, gt.id, 0);
        graph.addConnection(b.id, 0, gt.id, 1);
        evaluator = new GraphEvaluator(graph);

        evaluator.evaluate(List.of(), Map.of(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));

        assertEquals(1f, evaluator.getNodeOutput(gt.id, 0), 0.0001f);
    }

    @Test
    @DisplayName("GT: 3 > 5 → outputs 0")
    void testGtFalse() {
        var a = graph.addNode(NodeType.CONST, 0, 0);
        var b = graph.addNode(NodeType.CONST, 50, 0);
        var gt = graph.addNode(NodeType.GT, 100, 0);
        a.params[0] = 3f; b.params[0] = 5f;
        graph.addConnection(a.id, 0, gt.id, 0);
        graph.addConnection(b.id, 0, gt.id, 1);
        evaluator = new GraphEvaluator(graph);

        evaluator.evaluate(List.of(), Map.of(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));

        assertEquals(0f, evaluator.getNodeOutput(gt.id, 0), 0.0001f);
    }

    @Test
    @DisplayName("EQ: 3 == 3 → outputs 1")
    void testEq() {
        var a = graph.addNode(NodeType.CONST, 0, 0);
        var b = graph.addNode(NodeType.CONST, 50, 0);
        var eq = graph.addNode(NodeType.EQ, 100, 0);
        a.params[0] = 3f; b.params[0] = 3f;
        graph.addConnection(a.id, 0, eq.id, 0);
        graph.addConnection(b.id, 0, eq.id, 1);
        evaluator = new GraphEvaluator(graph);

        evaluator.evaluate(List.of(), Map.of(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));

        assertEquals(1f, evaluator.getNodeOutput(eq.id, 0), 0.0001f);
    }

    @Test
    @DisplayName("LT: 2 < 5 → outputs 1")
    void testLt() {
        var a = graph.addNode(NodeType.CONST, 0, 0);
        var b = graph.addNode(NodeType.CONST, 50, 0);
        var lt = graph.addNode(NodeType.LT, 100, 0);
        a.params[0] = 2f; b.params[0] = 5f;
        graph.addConnection(a.id, 0, lt.id, 0);
        graph.addConnection(b.id, 0, lt.id, 1);
        evaluator = new GraphEvaluator(graph);

        evaluator.evaluate(List.of(), Map.of(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));

        assertEquals(1f, evaluator.getNodeOutput(lt.id, 0), 0.0001f);
    }

    @Test
    @DisplayName("GE: 5 >= 5 → outputs 1")
    void testGe() {
        var a = graph.addNode(NodeType.CONST, 0, 0);
        var b = graph.addNode(NodeType.CONST, 50, 0);
        var ge = graph.addNode(NodeType.GE, 100, 0);
        a.params[0] = 5f; b.params[0] = 5f;
        graph.addConnection(a.id, 0, ge.id, 0);
        graph.addConnection(b.id, 0, ge.id, 1);
        evaluator = new GraphEvaluator(graph);

        evaluator.evaluate(List.of(), Map.of(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));

        assertEquals(1f, evaluator.getNodeOutput(ge.id, 0), 0.0001f);
    }

    @Test
    @DisplayName("LE: 3 <= 5 → outputs 1")
    void testLe() {
        var a = graph.addNode(NodeType.CONST, 0, 0);
        var b = graph.addNode(NodeType.CONST, 50, 0);
        var le = graph.addNode(NodeType.LE, 100, 0);
        a.params[0] = 3f; b.params[0] = 5f;
        graph.addConnection(a.id, 0, le.id, 0);
        graph.addConnection(b.id, 0, le.id, 1);
        evaluator = new GraphEvaluator(graph);

        evaluator.evaluate(List.of(), Map.of(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));

        assertEquals(1f, evaluator.getNodeOutput(le.id, 0), 0.0001f);
    }

    // ══════════════════ INTERP / MAP ══════════════════

    @Test
    @DisplayName("INTERP: connected inputs evaluated (2 wired inputs)")
    void testInterp() {
        // INTERP has 2 wired inputs (a, b) + 1 editable param (t)
        // With a=0, b=10 and default t=0, output = 0 + (10-0)*0 = 0
        var a = graph.addNode(NodeType.CONST, 0, 0);
        var b = graph.addNode(NodeType.CONST, 50, 0);
        var interp = graph.addNode(NodeType.INTERP, 150, 0);
        a.params[0] = 0f; b.params[0] = 10f;
        graph.addConnection(a.id, 0, interp.id, 0);
        graph.addConnection(b.id, 0, interp.id, 1);
        evaluator = new GraphEvaluator(graph);

        evaluator.evaluate(List.of(), Map.of(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));

        // Default t is 0 → output = 0
        assertEquals(0f, evaluator.getNodeOutput(interp.id, 0), 0.0001f);
    }

    @Test
    @DisplayName("MAP: map 0.5 from [0,1] to [0,100] = 50")
    void testMap() {
        var val = graph.addNode(NodeType.CONST, 0, 0);
        var inLo = graph.addNode(NodeType.CONST, 50, 0);
        var inHi = graph.addNode(NodeType.CONST, 100, 0);
        var outLo = graph.addNode(NodeType.CONST, 150, 0);
        var outHi = graph.addNode(NodeType.CONST, 200, 0);
        var map = graph.addNode(NodeType.MAP, 250, 0);
        val.params[0] = 0.5f;
        inLo.params[0] = 0f; inHi.params[0] = 1f;
        outLo.params[0] = 0f; outHi.params[0] = 100f;
        graph.addConnection(val.id, 0, map.id, 0);
        graph.addConnection(inLo.id, 0, map.id, 1);
        graph.addConnection(inHi.id, 0, map.id, 2);
        graph.addConnection(outLo.id, 0, map.id, 3);
        graph.addConnection(outHi.id, 0, map.id, 4);
        evaluator = new GraphEvaluator(graph);

        evaluator.evaluate(List.of(), Map.of(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));

        assertEquals(50f, evaluator.getNodeOutput(map.id, 0), 0.0001f);
    }

    // ══════════════════ BOOL / OR ══════════════════

    @Test
    @DisplayName("BOOL: non-zero input → 1")
    void testBool() {
        var src = graph.addNode(NodeType.CONST, 0, 0);
        var bool = graph.addNode(NodeType.BOOL, 50, 0);
        src.params[0] = 3.5f;
        graph.addConnection(src.id, 0, bool.id, 0);
        evaluator = new GraphEvaluator(graph);

        evaluator.evaluate(List.of(), Map.of(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));

        assertEquals(1f, evaluator.getNodeOutput(bool.id, 0), 0.0001f);
    }

    @Test
    @DisplayName("BOOL: zero input → 0")
    void testBoolZero() {
        var src = graph.addNode(NodeType.CONST, 0, 0);
        var bool = graph.addNode(NodeType.BOOL, 50, 0);
        src.params[0] = 0f;
        graph.addConnection(src.id, 0, bool.id, 0);
        evaluator = new GraphEvaluator(graph);

        evaluator.evaluate(List.of(), Map.of(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));

        assertEquals(0f, evaluator.getNodeOutput(bool.id, 0), 0.0001f);
    }

    @Test
    @DisplayName("OR: 1 OR 0 → 1")
    void testOr() {
        var a = graph.addNode(NodeType.CONST, 0, 0);
        var b = graph.addNode(NodeType.CONST, 50, 0);
        var or = graph.addNode(NodeType.OR, 100, 0);
        a.params[0] = 1f; b.params[0] = 0f;
        graph.addConnection(a.id, 0, or.id, 0);
        graph.addConnection(b.id, 0, or.id, 1);
        evaluator = new GraphEvaluator(graph);

        evaluator.evaluate(List.of(), Map.of(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));

        assertEquals(1f, evaluator.getNodeOutput(or.id, 0), 0.0001f);
    }

    @Test
    @DisplayName("OR: 0 OR 0 → 0")
    void testOrZero() {
        var a = graph.addNode(NodeType.CONST, 0, 0);
        var b = graph.addNode(NodeType.CONST, 50, 0);
        var or = graph.addNode(NodeType.OR, 100, 0);
        a.params[0] = 0f; b.params[0] = 0f;
        graph.addConnection(a.id, 0, or.id, 0);
        graph.addConnection(b.id, 0, or.id, 1);
        evaluator = new GraphEvaluator(graph);

        evaluator.evaluate(List.of(), Map.of(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));

        assertEquals(0f, evaluator.getNodeOutput(or.id, 0), 0.0001f);
    }

    // ══════════════════ SPLIT / RELAY / POSE ══════════════════

    @Test
    @DisplayName("SPLIT: positive → upper out; negative → lower out")
    void testSplit() {
        var src = graph.addNode(NodeType.CONST, 0, 0);
        var split = graph.addNode(NodeType.SPLIT, 50, 0);
        src.params[0] = 4f;
        graph.addConnection(src.id, 0, split.id, 0);
        evaluator = new GraphEvaluator(graph);

        evaluator.evaluate(List.of(), Map.of(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));

        assertEquals(4f, evaluator.getNodeOutput(split.id, 0), 0.0001f); // positive → pin 0
        assertEquals(0f, evaluator.getNodeOutput(split.id, 1), 0.0001f); // no negative → 0
    }

    @Test
    @DisplayName("SPLIT: negative → lower out")
    void testSplitNegative() {
        var src = graph.addNode(NodeType.CONST, 0, 0);
        var split = graph.addNode(NodeType.SPLIT, 50, 0);
        src.params[0] = -4f;
        graph.addConnection(src.id, 0, split.id, 0);
        evaluator = new GraphEvaluator(graph);

        evaluator.evaluate(List.of(), Map.of(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));

        assertEquals(0f, evaluator.getNodeOutput(split.id, 0), 0.0001f);
        assertEquals(4f, evaluator.getNodeOutput(split.id, 1), 0.0001f); // |-4| = 4 on lower out
    }

    @Test
    @DisplayName("RELAY_A: connected to 3 inputs produces output (contact open)")
    void testRelayA() {
        // RELAY_A has 3 inputs (A, B, contact). Contact=0 selects A.
        var a = graph.addNode(NodeType.CONST, 0, 0);
        var b = graph.addNode(NodeType.CONST, 50, 0);
        var ctl = graph.addNode(NodeType.CONST, 100, 0);
        var relay = graph.addNode(NodeType.RELAY_A, 150, 0);
        a.params[0] = 5f; b.params[0] = 10f; ctl.params[0] = 0f;
        graph.addConnection(a.id, 0, relay.id, 0);
        graph.addConnection(b.id, 0, relay.id, 1);
        graph.addConnection(ctl.id, 0, relay.id, 2);
        evaluator = new GraphEvaluator(graph);

        evaluator.evaluate(List.of(), Map.of(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));

        // With contact=0, A pin (0) should output A's value
        assertEquals(5f, evaluator.getNodeOutput(relay.id, 0), 0.0001f);
    }

    @Test
    @DisplayName("RELAY_B: connected to 3 inputs produces output")
    void testRelayB() {
        var a = graph.addNode(NodeType.CONST, 0, 0);
        var b = graph.addNode(NodeType.CONST, 50, 0);
        var ctl = graph.addNode(NodeType.CONST, 100, 0);
        var relay = graph.addNode(NodeType.RELAY_B, 150, 0);
        a.params[0] = 5f; b.params[0] = 10f; ctl.params[0] = 0f;
        graph.addConnection(a.id, 0, relay.id, 0);
        graph.addConnection(b.id, 0, relay.id, 1);
        graph.addConnection(ctl.id, 0, relay.id, 2);
        evaluator = new GraphEvaluator(graph);

        evaluator.evaluate(List.of(), Map.of(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));

        // RELAY_B outputs something — just verify it doesn't crash
        float out = evaluator.getNodeOutput(relay.id, 0);
        assertTrue(Float.isFinite(out));
    }

    // ══════════════════ Multi-node chain ══════════════════

    @Test
    @DisplayName("Chain: CONST(2) → ADD(+3) → MUL(*4) = 20")
    void testChain() {
        var c1 = graph.addNode(NodeType.CONST, 0, 0);
        var c2 = graph.addNode(NodeType.CONST, 50, 0);
        var c3 = graph.addNode(NodeType.CONST, 100, 0);
        var add = graph.addNode(NodeType.ADD, 150, 0);
        var mul = graph.addNode(NodeType.MUL, 200, 0);
        c1.params[0] = 2f; c2.params[0] = 3f; c3.params[0] = 4f;
        graph.addConnection(c1.id, 0, add.id, 0);
        graph.addConnection(c2.id, 0, add.id, 1);
        graph.addConnection(add.id, 0, mul.id, 0);
        graph.addConnection(c3.id, 0, mul.id, 1);
        evaluator = new GraphEvaluator(graph);

        evaluator.evaluate(List.of(), Map.of(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));

        assertEquals(5f, evaluator.getNodeOutput(add.id, 0), 0.0001f);  // 2+3=5
        assertEquals(20f, evaluator.getNodeOutput(mul.id, 0), 0.0001f); // 5*4=20
    }

    @Test
    @DisplayName("Topological order: fan-out → multiple readers")
    void testFanOut() {
        var src = graph.addNode(NodeType.CONST, 0, 0);
        var add = graph.addNode(NodeType.ADD, 50, 0);
        var sub = graph.addNode(NodeType.SUB, 100, 0);
        var c2 = graph.addNode(NodeType.CONST, 150, 0);
        src.params[0] = 10f; c2.params[0] = 3f;
        graph.addConnection(src.id, 0, add.id, 0);
        graph.addConnection(c2.id, 0, add.id, 1);
        graph.addConnection(src.id, 0, sub.id, 0);
        graph.addConnection(c2.id, 0, sub.id, 1);
        evaluator = new GraphEvaluator(graph);

        evaluator.evaluate(List.of(), Map.of(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));

        assertEquals(13f, evaluator.getNodeOutput(add.id, 0), 0.0001f); // 10+3
        assertEquals(7f, evaluator.getNodeOutput(sub.id, 0), 0.0001f);  // 10-3
    }

    // ══════════════════ Repeat evaluation (stateful) ══════════════════

    @Test
    @DisplayName("Repeated evaluation: graph evaluated twice gives consistent results")
    void testRepeatEvaluation() {
        var node = graph.addNode(NodeType.CONST, 0, 0);
        node.params[0] = 99f;
        evaluator = new GraphEvaluator(graph);

        evaluator.evaluate(List.of(), Map.of(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));
        assertEquals(99f, evaluator.getNodeOutput(node.id, 0), 0.0001f);

        // Second evaluate should not change CONST output
        evaluator.evaluate(List.of(), Map.of(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));
        assertEquals(99f, evaluator.getNodeOutput(node.id, 0), 0.0001f);
    }

    @Test
    @DisplayName("Empty graph: evaluate returns empty output list")
    void testEmptyGraph() {
        evaluator = new GraphEvaluator(graph);
        var results = evaluator.evaluate(List.of(), Map.of(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));
        assertNotNull(results);
        assertTrue(results.isEmpty());
    }

    // ══════════════════ Unconnected node ══════════════════

    @Test
    @DisplayName("ADD with one unconnected input: uses 0 for unconnected pin")
    void testAddUnconnected() {
        var a = graph.addNode(NodeType.CONST, 0, 0);
        var add = graph.addNode(NodeType.ADD, 50, 0);
        a.params[0] = 5f;
        graph.addConnection(a.id, 0, add.id, 0); // pin 1 unconnected → 0
        evaluator = new GraphEvaluator(graph);

        evaluator.evaluate(List.of(), Map.of(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));

        assertEquals(5f, evaluator.getNodeOutput(add.id, 0), 0.0001f); // 5+0=5
    }

    // ══════════════════ Sensor input nodes ══════════════════

    @Test
    @DisplayName("ATTITUDE: outputs pitch and roll from SeatInputState")
    void testAttitudeNode() {
        var att = graph.addNode(NodeType.ATTITUDE, 0, 0);
        evaluator = new GraphEvaluator(graph);

        var seat = new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0,
            0, 0, 30f, -15f, 45f, 10f,  // blockYaw, attitudeYaw, attitudePitch, attitudeRoll, forwardYaw, forwardPitch
            0, 0, 0, 0, 0, 0, 0, 0, 0);
        evaluator.evaluate(List.of(), Map.of(), 0.05f, seat);

        assertEquals(30f, evaluator.getNodeOutput(att.id, 0), 0.0001f); // pitch
        assertEquals(-15f, evaluator.getNodeOutput(att.id, 1), 0.0001f); // roll
    }

    @Test
    @DisplayName("FORWARD: outputs world yaw and pitch")
    void testForwardNode() {
        var fwd = graph.addNode(NodeType.FORWARD, 0, 0);
        evaluator = new GraphEvaluator(graph);

        var seat = new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 90f, -5f,  // forwardYaw=90, forwardPitch=-5
            0, 0, 0, 0, 0, 0, 0, 0, 0);
        evaluator.evaluate(List.of(), Map.of(), 0.05f, seat);

        assertEquals(90f, evaluator.getNodeOutput(fwd.id, 0), 0.0001f);
        assertEquals(-5f, evaluator.getNodeOutput(fwd.id, 1), 0.0001f);
    }

    @Test
    @DisplayName("VELOCITY: outputs scaled velocity")
    void testVelocityNode() {
        var vel = graph.addNode(NodeType.VELOCITY, 0, 0);
        evaluator = new GraphEvaluator(graph);

        var seat = new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0,  // attitude
            0, 0, 0,  // accel
            1f, 2f, 3f,  // velX, velY, velZ
            0, 0, 0);
        evaluator.evaluate(List.of(), Map.of(), 0.05f, seat);

        assertEquals(2f, evaluator.getNodeOutput(vel.id, 0), 0.0001f); // velX * 2
        assertEquals(4f, evaluator.getNodeOutput(vel.id, 1), 0.0001f); // velY * 2
        assertEquals(6f, evaluator.getNodeOutput(vel.id, 2), 0.0001f); // velZ * 2
    }

    @Test
    @DisplayName("ACCELERATION: outputs accel vector")
    void testAccelerationNode() {
        var accel = graph.addNode(NodeType.ACCELERATION, 0, 0);
        evaluator = new GraphEvaluator(graph);

        var seat = new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0,
            0.5f, -0.3f, 0.1f,  // accelX, accelY, accelZ
            0, 0, 0, 0, 0, 0);
        evaluator.evaluate(List.of(), Map.of(), 0.05f, seat);

        assertEquals(0.5f, evaluator.getNodeOutput(accel.id, 0), 0.0001f);
        assertEquals(-0.3f, evaluator.getNodeOutput(accel.id, 1), 0.0001f);
        assertEquals(0.1f, evaluator.getNodeOutput(accel.id, 2), 0.0001f);
    }

    // ══════════════════ KEYBOARD node ══════════════════

    @Test
    @DisplayName("KEYBOARD: key pressed → 1, not pressed → 0")
    void testKeyboardPressed() {
        var key = graph.addNode(NodeType.KEYBOARD, 0, 0);
        key.params[0] = 4; // key index 4
        evaluator = new GraphEvaluator(graph);

        var seatPressed = new GraphEvaluator.SeatInputState(1L << 4, 0, 0, 0, 0);
        evaluator.evaluate(List.of(), Map.of(), 0.05f, seatPressed);
        assertEquals(1f, evaluator.getNodeOutput(key.id, 0), 0.0001f);

        var seatReleased = new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0);
        evaluator.evaluate(List.of(), Map.of(), 0.05f, seatReleased);
        assertEquals(0f, evaluator.getNodeOutput(key.id, 0), 0.0001f);
    }

    // ══════════════════ MOUSE_BUTTON node ══════════════════

    @Test
    @DisplayName("MOUSE_BUTTON: LMB pressed → output 1 on pin 0")
    void testMouseButtonLMB() {
        var mb = graph.addNode(NodeType.MOUSE_BUTTON, 0, 0);
        evaluator = new GraphEvaluator(graph);

        // mouseButtons=1 → LMB pressed
        var seat = new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0, 0, 0, 1,
            0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        evaluator.evaluate(List.of(), Map.of(), 0.05f, seat);

        assertEquals(1f, evaluator.getNodeOutput(mb.id, 0), 0.0001f);
        assertEquals(0f, evaluator.getNodeOutput(mb.id, 1), 0.0001f);
    }
}
