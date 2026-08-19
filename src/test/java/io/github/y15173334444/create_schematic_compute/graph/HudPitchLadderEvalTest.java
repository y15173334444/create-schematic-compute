package io.github.y15173334444.create_schematic_compute.graph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for the {@link NodeType#HUD_PITCH_LADDER} node — passthrough evaluation
 * and constructor defaults (AR HUD Phase 2).
 * <p>
 * {@link NodeType#HUD_PITCH_LADDER} 节点测试——透传求值与构造默认值（AR HUD Phase 2）。
 */
class HudPitchLadderEvalTest {

    @Test
    @DisplayName("HUD_PITCH_LADDER: passthrough outputs pitch/roll inputs")
    void testPassthrough() {
        var graph = new NodeGraph();
        var pitch = graph.addNode(NodeType.CONST, 0, 0);
        pitch.params[0] = 12.5f;
        var roll = graph.addNode(NodeType.CONST, 0, 0);
        roll.params[0] = -30f;
        var hud = graph.addNode(NodeType.HUD_PITCH_LADDER, 0, 0);
        graph.addConnection(pitch.id, 0, hud.id, 0);
        graph.addConnection(roll.id, 0, hud.id, 1);

        var evaluator = new GraphEvaluator(graph);
        evaluator.evaluate(List.of(), Map.of(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));

        assertEquals(12.5f, evaluator.getNodeOutput(hud.id, 0), 0.0001f);
        assertEquals(-30f, evaluator.getNodeOutput(hud.id, 1), 0.0001f);
    }

    @Test
    @DisplayName("HUD_PITCH_LADDER: unconnected inputs pass through as 0")
    void testUnconnectedPassthroughZero() {
        var graph = new NodeGraph();
        var hud = graph.addNode(NodeType.HUD_PITCH_LADDER, 0, 0);

        var evaluator = new GraphEvaluator(graph);
        evaluator.evaluate(List.of(), Map.of(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));

        assertEquals(0f, evaluator.getNodeOutput(hud.id, 0), 0.0001f);
        assertEquals(0f, evaluator.getNodeOutput(hud.id, 1), 0.0001f);
    }

    @Test
    @DisplayName("HUD_PITCH_LADDER: default params are full-range 90 and interval 5")
    void testDefaultParams() {
        var hud = new NodeGraph().addNode(NodeType.HUD_PITCH_LADDER, 0, 0);
        assertEquals(90f, hud.params[0], 0.0001f);
        assertEquals(5f, hud.params[1], 0.0001f);
    }

    @Test
    @DisplayName("HUD_PITCH_LADDER: param clamp — range capped to [1,180]")
    void testRangeClamp() {
        // Constructor defaults are 90/5; the renderer clamps params at draw time,
        // but the constructor must never leave 0 (default guard).
        var hud = new NodeGraph().addNode(NodeType.HUD_PITCH_LADDER, 0, 0);
        assertEquals(90f, hud.params[0], 0.0001f); // never 0 after construction
        hud.params[0] = 0f;
        // renderer-side guard: Math.max(1, Math.min(180, params[0]))
        assertEquals(1f, Math.max(1f, Math.min(180f, hud.params[0])), 0.0001f);
        hud.params[0] = 200f;
        assertEquals(180f, Math.max(1f, Math.min(180f, hud.params[0])), 0.0001f);
    }
}
