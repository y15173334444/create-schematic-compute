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
 * 时序节点参数引脚（可连线编辑区）回归测试。
 * Regression tests for sequential-node param pins (the wireable edit-area pins).
 * <p>
 * 真实 bug 模式（2026-08-23 用户报告）：DELAY/PULSE_EXTEND/LOOP/FUSE 等时序节点
 * 的「可连线编辑区」参数引脚连上信号后没有任何效果——通用参数引脚覆盖机制
 * （连线值临时覆盖 node.params）只应用在 eval() 默认路径，而时序节点走
 * evalExt() 的 switch，直接读 node.params，从未应用覆盖。修复后 evalExt 顶部
 * 统一应用覆盖，参数引脚连线即刻生效。
 * <p>
 * The real bug pattern (reported 2026-08-23): wiring the wireable edit-area
 * (param) pins of sequential nodes such as DELAY/PULSE_EXTEND/LOOP/FUSE had no
 * effect — the generic param-pin override (wired values temporarily replace
 * node.params) was only applied in the eval() default path, while sequential
 * nodes run through evalExt()'s switch and read node.params directly. After the
 * fix, evalExt applies the override up front, so param-pin wiring works.
 */
class SequentialParamPinTest {

    private NodeGraph graph;
    private GraphEvaluator evaluator;
    private GraphNode signal;   // 功能输入信号源 / functional input signal source
    private final Map<Integer, Float> pid = new HashMap<>();
    private final Map<Integer, Boolean> ffs = new HashMap<>();
    private final Map<Integer, Integer> pt = new HashMap<>();
    private final Map<Integer, ArrayDeque<Float>> dq = new HashMap<>();

    private GraphNode addParamSource(int x, float value) {
        var n = graph.addNode(NodeType.CONST, x, 0);
        n.params[0] = value;
        return n;
    }

    private float tick(GraphNode node) {
        evaluator.evaluate(List.of(), pid, 0.05f, dq, ffs, pt);
        return evaluator.getNodeOutput(node.id, 0);
    }

    @Test
    @DisplayName("FUSE：cooldown 参数引脚连线生效（cd=5 → 周期 6，非默认 40）")
    void fuseCooldownParamPinWired() {
        graph = new NodeGraph();
        signal = graph.addNode(NodeType.CONST, 0, 0);
        var cd = addParamSource(100, 5f);                       // 参数引脚：cooldown=5
        var fuse = graph.addNode(NodeType.FUSE, 200, 0);
        graph.addConnection(signal.id, 0, fuse.id, 0);          // 功能输入：触发信号
        graph.addConnection(cd.id, 0, fuse.id, fuse.functionalInputs() + 0); // 参数引脚
        evaluator = new GraphEvaluator(graph);

        signal.params[0] = 1f;
        assertEquals(1, tick(fuse), "t0 触发");
        assertEquals(1, tick(fuse), "t1 脉冲第 2 tick");
        assertEquals(0, tick(fuse), "t2 冷却");
        for (int i = 0; i < 3; i++) assertEquals(0, tick(fuse), "t" + (3 + i) + " 冷却");
        assertEquals(1, tick(fuse), "t6 长信号再触发（cd=5，修复前连线无效则此处保持 0）");
    }

    @Test
    @DisplayName("PULSE_EXTEND：ticks 参数引脚连线生效（3 tick，非默认 10）")
    void pulseExtendDurationParamPinWired() {
        graph = new NodeGraph();
        signal = graph.addNode(NodeType.CONST, 0, 0);
        var dur = addParamSource(100, 3f);                      // 参数引脚：duration=3
        var pe = graph.addNode(NodeType.PULSE_EXTEND, 200, 0);
        graph.addConnection(signal.id, 0, pe.id, 0);
        graph.addConnection(dur.id, 0, pe.id, pe.functionalInputs() + 0);
        evaluator = new GraphEvaluator(graph);

        signal.params[0] = 1f;
        assertEquals(1, tick(pe), "t0 输入高 → 计时开始");
        signal.params[0] = 0f;
        assertEquals(1, tick(pe), "t1 输入已低，仍输出（第 1 tick）");
        assertEquals(1, tick(pe), "t2 仍输出（第 2 tick）");
        assertEquals(0, tick(pe), "t3 时长 3 结束（修复前默认 10，此处仍为 1）");
    }

    @Test
    @DisplayName("LOOP：count/interval 两个参数引脚连线生效（count=2, interval=3）")
    void loopCountAndIntervalParamPinsWired() {
        graph = new NodeGraph();
        signal = graph.addNode(NodeType.CONST, 0, 0);
        var cnt = addParamSource(100, 2f);                      // 参数引脚：count=2
        var ivl = addParamSource(200, 3f);                      // 参数引脚：interval=3
        var loop = graph.addNode(NodeType.LOOP, 300, 0);
        graph.addConnection(signal.id, 0, loop.id, 0);
        graph.addConnection(cnt.id, 0, loop.id, loop.functionalInputs() + 0);
        graph.addConnection(ivl.id, 0, loop.id, loop.functionalInputs() + 1);
        evaluator = new GraphEvaluator(graph);

        signal.params[0] = 1f;  // 上升沿启动
        assertEquals(0, tick(loop), "t0 启动，interval 倒数");
        assertEquals(0, tick(loop), "t1 倒数");
        assertEquals(1, tick(loop), "t2 第 1 个脉冲（count=2, interval=3）");
        assertEquals(0, tick(loop), "t3 间隔");
        assertEquals(0, tick(loop), "t4 间隔");
        assertEquals(1, tick(loop), "t5 第 2 个脉冲");
        assertEquals(0, tick(loop), "t6 结束（修复前默认 count=5/interval=10，此处仍为 1）");
    }
}
