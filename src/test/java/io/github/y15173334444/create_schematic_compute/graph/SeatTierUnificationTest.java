package io.github.y15173334444.create_schematic_compute.graph;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 档位合一（2026-10-31 拍板）行为锚：4 参 SeatInput 档与全状态档合一后，七类时序/状态
 * 节点在 seat 路径上真正工作——登记册条目『时序/逻辑节点在 SeatInput 档宿主静默空转
 * （op 注入/旧档形态）』的修复回归。修复前：无状态路径只走 eval()，七类落
 * {@code TimingEval.eval}（仅 ACCUMULATOR/INTEGRATOR）后 switch 落空、逐拍写回旧值。
 * <p>
 * Behavior anchors for the tier unification (2026-10-31 ruling): the 4-arg SeatInput
 * form and the full-state form are one tier, so the seven timing/state types actually
 * run on the seat path — the fix regression for the open-issues register entry
 * "Timing/logic nodes silently idle on SeatInput-tier hosts (op injection / legacy
 * save shapes)". Before: the stateless path only ran eval(), the seven landed on
 * {@code TimingEval.eval} (ACCUMULATOR/INTEGRATOR only), the switch fell through and
 * the tail bookkeeping wrote back the previous values every tick.
 */
class SeatTierUnificationTest {

    private NodeGraph graph;
    private GraphEvaluator evaluator;
    private final Map<Integer, Float> pid = new HashMap<>();
    private final Map<Integer, Boolean> ffs = new HashMap<>();
    private final Map<Integer, Integer> pt = new HashMap<>();
    private final Map<Integer, ArrayDeque<Float>> dq = new HashMap<>();

    @BeforeEach
    void setUp() {
        graph = new NodeGraph();
        evaluator = new GraphEvaluator(graph);
    }

    private GraphEvaluator.SeatInputState seat() {
        return new GraphEvaluator.SeatInputState(0b1, 1f, 2f, 3f, 4f);
    }

    @Test
    @DisplayName("4 参档 LATCH 立即置位（修复前冻结旧值恒 0）")
    void latchRespondsImmediatelyOnTheFourArgPath() {
        GraphNode set = graph.addNode(NodeType.CONST, 0, 0);
        GraphNode latch = graph.addNode(NodeType.LATCH, 100, 0);
        set.params[0] = 1f;
        graph.addConnection(set.id, 0, latch.id, 0);
        evaluator.evaluate(List.of(), Map.of(), 0.05f, seat());
        assertEquals(1, evaluator.getNodeOutput(latch.id, 0),
            "SET 置位应立即输出 1（修复前无状态路径静默空转、写回旧值 0）");
    }

    @Test
    @DisplayName("4 参档 GATE 放行（修复前经 FormulaEval 缺 case 冻结恒 0）")
    void gatePassesValueWhenOpenOnTheFourArgPath() {
        GraphNode val = graph.addNode(NodeType.CONST, 0, 0);
        GraphNode open = graph.addNode(NodeType.CONST, 0, 100);
        GraphNode gate = graph.addNode(NodeType.GATE, 100, 0);
        val.params[0] = 3f;
        open.params[0] = 1f;
        graph.addConnection(val.id, 0, gate.id, 0);
        graph.addConnection(open.id, 0, gate.id, 1);
        evaluator.evaluate(List.of(), Map.of(), 0.05f, seat());
        assertEquals(3, evaluator.getNodeOutput(gate.id, 0),
            "OPEN 置位应放行输入值 3（修复前 GATE 落 FormulaEval 无 case、冻结 0）");
    }

    @Test
    @DisplayName("统一调用形态（seat + 共享三表）下 DELAY 跨 tick 演进")
    void delayEvolvesAcrossTicksOnTheUnifiedCallShape() {
        GraphNode src = graph.addNode(NodeType.CONST, 0, 0);
        GraphNode delay = graph.addNode(NodeType.DELAY, 100, 0);
        src.params[0] = 1f;
        delay.params[0] = 2f; // 2 tick 延时 / 2-tick delay
        graph.addConnection(src.id, 0, delay.id, 0);
        // 与 GraphHost FULL_STATE case 同形：seat + 宿主权威三表跨 tick 共享。
        // Same shape as GraphHost's FULL_STATE case: seat + host-authoritative tables.
        evaluator.evaluate(List.of(), Map.of(), 0.05f, seat(), dq, ffs, pt);
        assertEquals(0, evaluator.getNodeOutput(delay.id, 0), "t0：队列空，输出 0");
        evaluator.evaluate(List.of(), Map.of(), 0.05f, seat(), dq, ffs, pt);
        assertEquals(1, evaluator.getNodeOutput(delay.id, 0), "t1：输出 2 tick 前的输入 1");
    }
}
