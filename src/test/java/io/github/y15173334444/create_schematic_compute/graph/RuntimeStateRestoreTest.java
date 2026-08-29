package io.github.y15173334444.create_schematic_compute.graph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 存档恢复回归测试：{@link RuntimeState#putAllFrom} 必须一次恢复全部七类状态。
 * Regression tests for save restore: {@link RuntimeState#putAllFrom} must restore all
 * seven state categories in one call.
 *
 * <p>背景（阶段 0 分叉）：saveAdditional / saveHostNBT 写的始终是
 * {@link RuntimeState#save()} 全量，但恢复侧长期只取 pidState 一项，且
 * Blueprint / ProgramComputer / Radar 各自在子类里补了不同的子集。结果是存档重载后
 * 延时队列、触发器、脉冲计时、调试相位被静默清零，触发电平丢失还会把"常高"信号
 * 误判成新的上升沿。
 * Background (phase-0 divergence): saveAdditional / saveHostNBT always wrote the full
 * {@link RuntimeState#save()} payload, but the restore side kept reading only pidState,
 * with Blueprint / ProgramComputer / Radar each patching in a different subset. World
 * reloads therefore silently zeroed delay queues, flipflops, pulse timers and debug
 * phases, and losing the trigger level re-fired a held-high signal as a new rising edge.
 */
class RuntimeStateRestoreTest {

    // ── 辅助 / helpers ──────────────────────────────────────────────────

    /** 记录型 sink。 / Recording sink. */
    private static final class RecordingSink implements GearboxCommandSink {
        final List<MotionCommand> enqueued = new ArrayList<>();
        @Override public void enqueue(MotionCommand command) { enqueued.add(command); }
        @Override public void emergencyStop() { }
    }

    /** 构造 CONST(触点) + CONST(数值) → MOVE 的最小图。
     *  Build a minimal CONST(trigger) + CONST(value) → MOVE graph. */
    private record Setup(NodeGraph graph, GraphNode cmd, GraphNode trig) {}

    private static Setup moveGraph() {
        var graph = new NodeGraph();
        var trig = graph.addNode(NodeType.CONST, 0, 0);
        trig.params[0] = 0f;   // CONST 默认参数为 1，显式归零 / CONST defaults to 1; zero it
        var val = graph.addNode(NodeType.CONST, 2, 0);
        val.params[0] = 2f;
        var cmd = graph.addNode(NodeType.MOVE, 4, 0);
        graph.addConnection(trig.id, 0, cmd.id, 0);
        graph.addConnection(val.id, 0, cmd.id, 1);
        return new Setup(graph, cmd, trig);
    }

    /** 用给定运行时状态跑一 tick。 / Run one tick against the given runtime state. */
    private static void tick(NodeGraph graph, RuntimeState rs, RecordingSink sink) {
        var evaluator = new GraphEvaluator(graph);
        evaluator.restoreSubState(rs);   // nodeEdge 需要 runtimeState 引用 / nodeEdge needs the runtimeState ref
        evaluator.setCommandSink(sink);
        evaluator.evaluate(new ArrayList<>(), new HashMap<>(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));
    }

    /** 造一份七类俱全的运行时状态。 / Build a runtime state with all seven categories populated. */
    private static RuntimeState populatedState() {
        var rs = new RuntimeState();
        rs.pidState.put(7, 12.5f);
        rs.flipflopStates.put(8, true);
        rs.pulseTimers.put(9, 3);
        rs.debugTime.put(10, 0.25f);
        rs.nodeEdge.put(11, true);
        var q = new ArrayDeque<Float>();
        q.add(1f); q.add(2f);
        rs.delayQueues.put(12, q);
        var sub = rs.getOrCreateSubState(13);
        sub.pidState.put(1, 4f);
        sub.flipflopStates.put(2, false);
        return rs;
    }

    // ── 全量恢复 / full restore ─────────────────────────────────────────

    @Test
    @DisplayName("putAllFrom: all seven categories survive a save→restore round trip")
    void testAllSevenCategoriesRestored() {
        var saved = RuntimeState.load(populatedState().save());
        var target = new RuntimeState();
        target.putAllFrom(saved);

        assertEquals(12.5f, target.pidState.get(7), 0.0001f);
        assertTrue(target.flipflopStates.get(8));
        assertEquals(3, target.pulseTimers.get(9));
        assertEquals(0.25f, target.debugTime.get(10), 0.0001f);
        assertTrue(target.nodeEdge.get(11));
        assertEquals(2, target.delayQueues.get(12).size());
        assertEquals(1f, target.delayQueues.get(12).peekFirst(), 0.0001f);

        var sub = target.subStates.get(13);
        assertTrue(sub != null, "subStates must be restored");
        assertEquals(4f, sub.pidState.get(1), 0.0001f);
        assertFalse(sub.flipflopStates.get(2));
    }

    @Test
    @DisplayName("putAllFrom: merges into existing state instead of replacing it")
    void testMergeSemantics() {
        var target = new RuntimeState();
        target.pidState.put(1, 1f);
        target.putAllFrom(RuntimeState.load(populatedState().save()));

        // 既有条目保留，存档条目并入 / pre-existing entries kept, saved entries merged in
        assertEquals(1f, target.pidState.get(1), 0.0001f);
        assertEquals(12.5f, target.pidState.get(7), 0.0001f);
    }

    @Test
    @DisplayName("putAllFrom: empty save payload is a no-op")
    void testEmptyPayloadIsNoOp() {
        var target = new RuntimeState();
        target.pidState.put(1, 1f);
        target.putAllFrom(RuntimeState.load(new net.minecraft.nbt.CompoundTag()));
        assertEquals(1f, target.pidState.get(1), 0.0001f);
        assertTrue(target.nodeEdge.isEmpty());
    }

    // ── 核心回归：触发电平跨重载存活 / core regression: trigger level survives reload ──

    @Test
    @DisplayName("Held-high trigger does NOT re-fire after a full restore (the fix)")
    void testHeldHighTriggerDoesNotRefireAfterRestore() {
        var s = moveGraph();
        var rs = new RuntimeState();

        // 拉高触点并跑一 tick：入队一条，nodeEdge 记住电平
        // Raise the trigger and tick once: one command enqueued, nodeEdge remembers the level
        s.trig().params[0] = 1f;
        var before = new RecordingSink();
        tick(s.graph(), rs, before);
        assertEquals(1, before.enqueued.size(), "first rising edge must enqueue exactly one command");
        assertFalse(rs.nodeEdge.isEmpty(), "trigger level must be recorded");

        // 模拟存档重载：状态经 NBT 往返后灌进一份全新的 RuntimeState
        // Simulate a world reload: state round-trips through NBT into a brand-new RuntimeState
        var reloaded = new RuntimeState();
        reloaded.putAllFrom(RuntimeState.load(rs.save()));

        // 触点仍为高 → 不是新的上升沿 → 不得再入队
        // Trigger still high → not a new rising edge → nothing more may be enqueued
        var after = new RecordingSink();
        tick(s.graph(), reloaded, after);
        assertEquals(0, after.enqueued.size(),
            "a held-high trigger must not re-enqueue after a reload (nodeEdge lost)");
    }

    @Test
    @DisplayName("Negative guard: restoring only pidState DOES re-fire (proves the test bites)")
    void testPartialRestoreRefires() {
        var s = moveGraph();
        var rs = new RuntimeState();

        s.trig().params[0] = 1f;
        var before = new RecordingSink();
        tick(s.graph(), rs, before);
        assertEquals(1, before.enqueued.size());

        // 旧行为：只恢复 pidState，触发电平丢失
        // Old behaviour: only pidState restored, trigger level lost
        var partial = new RuntimeState();
        partial.pidState.putAll(RuntimeState.load(rs.save()).pidState);

        var after = new RecordingSink();
        tick(s.graph(), partial, after);
        assertEquals(1, after.enqueued.size(),
            "guards the regression above: without nodeEdge the held-high trigger re-fires");
    }

    @Test
    @DisplayName("Low→high after a restore still fires exactly one command")
    void testRealEdgeAfterRestoreStillFires() {
        var s = moveGraph();
        var rs = new RuntimeState();

        s.trig().params[0] = 1f;
        var first = new RecordingSink();
        tick(s.graph(), rs, first);
        assertEquals(1, first.enqueued.size());

        var reloaded = new RuntimeState();
        reloaded.putAllFrom(RuntimeState.load(rs.save()));

        // 拉低再拉高：真正的上升沿，必须入队
        // Drop then raise again: a real rising edge, must enqueue
        s.trig().params[0] = 0f;
        tick(s.graph(), reloaded, new RecordingSink());
        s.trig().params[0] = 1f;
        var second = new RecordingSink();
        tick(s.graph(), reloaded, second);
        assertEquals(1, second.enqueued.size(),
            "a genuine low→high transition after a reload must still fire once");
    }

    // ── 时序状态跨重载存活 / sequential state survives reload ──

    @Test
    @DisplayName("Delay queues and pulse timers survive a restore (not just pid)")
    void testSequentialStateSurvivesRestore() {
        var rs = new RuntimeState();
        var q = new ArrayDeque<Float>();
        q.add(5f);
        rs.delayQueues.put(3, q);
        rs.pulseTimers.put(4, 7);
        rs.flipflopStates.put(5, true);

        var reloaded = new RuntimeState();
        reloaded.putAllFrom(RuntimeState.load(rs.save()));

        assertEquals(1, reloaded.delayQueues.get(3).size(),
            "delay queues must survive — RADAR/ControlSeat/Sensor used to lose these");
        assertEquals(7, reloaded.pulseTimers.get(4));
        assertTrue(reloaded.flipflopStates.get(5));
    }
}
