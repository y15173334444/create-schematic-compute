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
 * FUSE（保险）节点长信号支持回归测试。
 * Regression tests for FUSE (Safety Timer) long-signal support.
 * <p>
 * 真实 bug 模式（2026-08-23 用户报告）：FUSE 只能在输入**上升沿**触发一次
 * （2 tick 脉冲 → 冷却），持续高电平期间冷却结束后不会再次触发——无法当脉冲
 * 发生器用。修复后：输入持续高电平时，冷却结束自动再触发（带使能的脉冲发生器，
 * 周期 ≈ 2 + cooldown）；上升沿仍立即触发；输入在脉冲/冷却中途变低时当前一轮
 * 走完；顺带修正脉冲宽度 off-by-one（文档写 2 tick，旧代码实际只输出 1 tick）。
 * <p>
 * The real bug pattern (reported 2026-08-23): FUSE only fired once on a rising
 * edge (2-tick pulse → cooldown) and never re-fired while the input stayed high,
 * so it could not act as a pulse generator. After the fix: a held-high input
 * re-fires after each cooldown (an enable-gated pulse generator, period ≈ 2 +
 * cooldown); a rising edge still fires immediately; a drop mid-cycle lets the
 * current cycle finish; the pulse-width off-by-one (documented 2 ticks, old code
 * emitted 1) is fixed.
 */
class FuseLongSignalTest {

    private NodeGraph graph;
    private GraphEvaluator evaluator;
    private GraphNode constNode;
    private GraphNode fuse;
    private final Map<Integer, Float> pid = new HashMap<>();
    private final Map<Integer, Boolean> ffs = new HashMap<>();
    private final Map<Integer, Integer> pt = new HashMap<>();
    private final Map<Integer, ArrayDeque<Float>> dq = new HashMap<>();

    @BeforeEach
    void setUp() {
        graph = new NodeGraph();
        constNode = graph.addNode(NodeType.CONST, 0, 0);
        fuse = graph.addNode(NodeType.FUSE, 100, 0);
        fuse.params[0] = 5f; // cooldown = 5 ticks — compact test / 冷却 5 tick
        graph.addConnection(constNode.id, 0, fuse.id, 0);
        evaluator = new GraphEvaluator(graph);
    }

    /** 运行一个 tick：设置输入（CONST 参数），返回 FUSE 输出。 */
    private float tick(float input) {
        constNode.params[0] = input;
        evaluator.evaluate(List.of(), pid, 0.05f, dq, ffs, pt);
        return evaluator.getNodeOutput(fuse.id, 0);
    }

    @Test
    @DisplayName("上升沿触发：2 tick 脉冲（修复 off-by-one）→ 冷却 → 不再触发")
    void risingEdgeFiresTwoTickPulseThenCooldown() {
        assertEquals(0, tick(0), "低电平无输出");
        assertEquals(1, tick(1), "上升沿触发：脉冲第 1 tick");
        assertEquals(1, tick(0), "脉冲第 2 tick（修复前只输出 1 tick）");
        assertEquals(0, tick(0), "脉冲结束，进入冷却");
        for (int i = 0; i < 5; i++) assertEquals(0, tick(0), "冷却中无输出 tick " + i);
    }

    @Test
    @DisplayName("长信号：输入持续高电平 → 周期 ≈ 2+cooldown 重复脉冲（脉冲发生器）")
    void heldHighGeneratesRepeatingPulses() {
        // cd=5：脉冲于 t0、t6、t12 触发（周期 6 = cd+1）
        assertEquals(1, tick(1), "t0 触发");
        assertEquals(1, tick(1), "t1 脉冲第 2 tick");
        assertEquals(0, tick(1), "t2 冷却");
        assertEquals(0, tick(1), "t3 冷却");
        assertEquals(0, tick(1), "t4 冷却");
        assertEquals(0, tick(1), "t5 冷却");
        assertEquals(1, tick(1), "t6 长信号自动再触发（修复前此处保持 0）");
        assertEquals(1, tick(1), "t7 脉冲第 2 tick");
        assertEquals(0, tick(1), "t8 冷却");
        for (int i = 0; i < 3; i++) assertEquals(0, tick(1), "t" + (9 + i) + " 冷却");
        assertEquals(1, tick(1), "t12 再触发");
    }

    @Test
    @DisplayName("输入中途变低：当前轮走完，冷却结束后不再触发；新上升沿可再触发")
    void inputDropMidCycleCompletesThenStops() {
        assertEquals(1, tick(1), "t0 触发");
        assertEquals(1, tick(0), "t1 输入变低，脉冲第 2 tick 仍输出（当前轮走完）");
        for (int i = 0; i < 4; i++) assertEquals(0, tick(0), "t" + (2 + i) + " 冷却中");
        assertEquals(0, tick(0), "t6 冷却结束，输入低 → 不触发");
        assertEquals(1, tick(1), "t7 新上升沿 → 立即触发（重武装）");
    }

    @Test
    @DisplayName("冷却期间的新上升沿被忽略；冷却结束后长信号自动再触发")
    void risingEdgeDuringCooldownIgnoredThenHeldHighRefires() {
        assertEquals(0, tick(0), "t0 低电平");
        assertEquals(1, tick(1), "t1 触发");
        assertEquals(1, tick(0), "t2 脉冲第 2 tick，输入变低");
        assertEquals(0, tick(1), "t3 冷却中上升沿被忽略");
        assertEquals(0, tick(1), "t4 冷却中保持高");
        assertEquals(0, tick(1), "t5 冷却中保持高");
        assertEquals(0, tick(1), "t6 冷却最后 1 tick（触发点为 t1，再触发在 t7）");
        assertEquals(1, tick(1), "t7 冷却结束，输入仍高 → 长信号再触发");
    }
}
