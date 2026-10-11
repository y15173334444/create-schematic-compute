package io.github.y15173334444.create_schematic_compute.graph;

import io.github.y15173334444.create_schematic_compute.network.AudioBands;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 封装子图跨 tick 透传回归（P1-4 + P1-5）：
 * 子求值器被 {@code subEvaluators} 缓存跨 tick 复用，注入清单以往缺
 * {@code restoreSubState}（子求值器 runtimeState 恒 null，nodeEdge 无处落）与
 * {@code setCompletedNodeId}，且 {@code audioTickStamp} 只在创建时赋值一次。
 * 后果：封装内 MOVE/ROTATE/WAIT 常高触发每 tick 重复入队（v2"指令永不停止"事故的
 * 子图翻版）、MUSIC 每 tick 重头播、done 引脚永不出现、封装内发布的音频对外
 * 1–2 tick 即判过期（封装内→外静音）。
 * <p>
 * Encapsulation cross-tick passthrough regression (P1-4 + P1-5): the sub-evaluator is
 * cached in {@code subEvaluators} and reused across ticks, but the injection checklist
 * lacked {@code restoreSubState} (the sub-evaluator's runtimeState stayed null — nodeEdge
 * had nowhere to land) and {@code setCompletedNodeId}, and {@code audioTickStamp} was
 * assigned only at creation. Consequences: a held-high trigger inside an encapsulation
 * re-enqueued a command every tick (the sub-graph replay of the v2 "command never stops"
 * incident), MUSIC restarted from the head every tick, the done pin never fired, and
 * audio published inside an encapsulation read as stale 1–2 ticks later (encap→out
 * silence).
 */
class EncapSubRuntimePassthroughTest {

    /** 记录型 sink（GearboxCommandStackEvalTest 同款）。 / Recording sink (same as GearboxCommandStackEvalTest). */
    private static final class RecordingSink implements GearboxCommandSink {
        final List<MotionCommand> enqueued = new ArrayList<>();
        boolean stopped = false;
        @Override public void enqueue(MotionCommand command) { enqueued.add(command); }
        @Override public void emergencyStop() { stopped = true; }
    }

    private static final float DT = 0.1f; // tempo 1000 (10 t/s) ⇒ delta 1.0 NBS tick

    /** 30 音符曲目（ticks 0..29）：预播窗口（PREROLL=2s = 20 NBS tick）在 tick 1 批次
     *  覆盖 ticks 0..19，tick 2（窗口 [1,21)）有新进窗音符（tick 20）→ 批次非空；
     *  传输 3 tick 内也不会播完。 / A 30-note song (ticks 0..29): the pre-roll window
     *  (PREROLL=2s = 20 NBS ticks) covers ticks 0..19 in the tick-1 batch; tick 2 (window
     *  [1,21)) admits a newly-entering note (tick 20) → a non-empty batch; the transport
     *  does not finish within 3 ticks either. */
    private static NbsSong longSong() {
        NbsSong s = new NbsSong();
        s.tempo = 1000;
        for (int t = 0; t < 30; t++) s.putNote(t, 0, 2, 45, 100, 100, 0);
        return s;
    }

    @AfterEach void cleanup() { AudioBands.clear(); }

    // ── P1-4：MOVE 边沿记忆 / edge memory ────────────────────────────────

    /** 主图 = 单封装节点；子图 = 触发+数值 → MOVE → ENCAP_OUTPUT。
     *  Main graph = one encapsulation node; sub-graph = trigger+value → MOVE → ENCAP_OUTPUT. */
    private record MoveFixture(NodeGraph outer, GraphNode encap, NodeGraph sub,
                               GraphNode trig, GraphNode mv, RuntimeState rs,
                               GraphEvaluator ev, RecordingSink sink) {}

    private static MoveFixture moveFixture() {
        var outer = new NodeGraph();
        var encap = outer.addNode(NodeType.ENCAPSULATION, 0, 0);
        var sub = new NodeGraph();
        var trig = sub.addNode(NodeType.CONST, 0, 0);
        trig.params[0] = 0f;
        var val = sub.addNode(NodeType.CONST, 2, 0);
        val.params[0] = 2f;
        var mv = sub.addNode(NodeType.MOVE, 4, 0);
        sub.addConnection(trig.id, 0, mv.id, 0);
        sub.addConnection(val.id, 0, mv.id, 1);
        var out = sub.addNode(NodeType.ENCAP_OUTPUT, 6, 0);
        sub.addConnection(mv.id, 0, out.id, 0);
        encap.subGraph = sub;

        var ev = new GraphEvaluator(outer);
        var rs = new RuntimeState();
        ev.restoreSubState(rs);   // 宿主契约：首次 evaluate 前注入运行时状态 / host contract
        var sink = new RecordingSink();
        ev.setCommandSink(sink);
        return new MoveFixture(outer, encap, sub, trig, mv, rs, ev, sink);
    }

    private static void tick(MoveFixture f) {
        f.ev().evaluate(List.of(), Map.of(), 0.05f, new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));
    }

    @Test
    @DisplayName("封装内 MOVE：常高触发跑 5 tick 只入队一条指令（v2 子图事故锚）")
    void heldHighTriggerInsideEncapEnqueuesOnce() {
        MoveFixture f = moveFixture();
        f.sub().findNode(f.trig().id).params[0] = 1f;   // 拉高并保持 / raise and hold
        for (int i = 0; i < 5; i++) tick(f);
        assertEquals(1, f.sink().enqueued.size(),
            "held-high trigger inside an encapsulation must enqueue exactly one command — "
            + "a repeated enqueue every tick is the v2 'command never stops' incident replayed in the sub-graph");
        assertEquals(2f, f.sink().enqueued.get(0).value(), 0.0001f, "value snapshotted at the edge");
        assertEquals(f.mv().id, f.sink().enqueued.get(0).sourceNodeId(), "source id = the sub-graph node id");
    }

    @Test
    @DisplayName("封装内 MOVE：边沿记忆落进 SubState 并随 NBT 持久化")
    void subNodeEdgeLandsInSubStateAndPersists() {
        MoveFixture f = moveFixture();
        f.sub().findNode(f.trig().id).params[0] = 1f;
        tick(f);
        assertEquals(1, f.sink().enqueued.size());
        assertTrue(f.rs().getOrCreateSubState(f.encap().id).nodeEdge.getOrDefault(f.mv().id, false),
            "the sub-graph edge memory must land on the authoritative SubState table");
        // NBT 往返：存档重载后常高触发不得被误判为新上升沿
        // NBT round trip: a held-high trigger must not be misjudged as a fresh edge after reload
        var back = RuntimeState.load(f.rs().save());
        assertEquals(Boolean.TRUE, back.subStates.get(f.encap().id).nodeEdge.get(f.mv().id),
            "SubState.nodeEdge must survive the save/load round trip");
        var ev2 = new GraphEvaluator(f.outer());
        ev2.restoreSubState(back);
        var sink2 = new RecordingSink();
        ev2.setCommandSink(sink2);
        ev2.evaluate(List.of(), Map.of(), 0.05f, new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));
        assertEquals(0, sink2.enqueued.size(), "held-high must not re-fire after save/load restore");
    }

    @Test
    @DisplayName("封装内 done 引脚：completedNodeId 透传后一帧脉冲")
    void completedNodeIdReachesSubGraphDonePin() {
        MoveFixture f = moveFixture();
        f.sub().findNode(f.trig().id).params[0] = 1f;
        tick(f);
        assertEquals(1, f.sink().enqueued.size());
        // 宿主完成指令：sourceNodeId 置在顶层求值器 → 必须透传给子求值器
        // Host completes the command: the source id lands on the top evaluator → must reach the sub-evaluator
        f.ev().setCompletedNodeId(f.mv().id);
        tick(f);
        var snap = f.ev().captureSnapshot();
        var subOut = snap.subOutputs().get(f.encap().id);
        assertNotNull(subOut, "sub-graph outputs must be present");
        assertEquals(1f, subOut.get(f.mv().id)[0], 0.0001f, "the encapsulated done pin must pulse for one frame");
        f.ev().clearCompletedNodeId();
        tick(f);
        var snap2 = f.ev().captureSnapshot();
        assertEquals(0f, snap2.subOutputs().get(f.encap().id).get(f.mv().id)[0], 0.0001f,
            "the done pulse must clear when the host clears completedNodeId");
    }

    // ── P1-4：封装内 MUSIC 不重头播 / MUSIC does not restart from the head ──

    @Test
    @DisplayName("封装内 MUSIC：play 常高不每 tick 重头播（headTick 单调前进）")
    void musicInsideEncapDoesNotRestartEachTick() {
        var outer = new NodeGraph();
        var encap = outer.addNode(NodeType.ENCAPSULATION, 0, 0);
        var sub = new NodeGraph();
        var play = sub.addNode(NodeType.CONST, -2, 0);
        play.params[0] = 1f;
        var music = sub.addNode(NodeType.MUSIC, 0, 0);
        music.song = longSong();
        sub.addConnection(play.id, 0, music.id, 0);
        encap.subGraph = sub;

        var ev = new GraphEvaluator(outer);
        ev.restoreSubState(new RuntimeState());
        var transports = new HashMap<Integer, MusicTransport>();
        ev.setAudioTransports(transports);
        var seat = new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0);

        // runtimeState 缺失时 play 边沿每 tick 误判 → playFromHead 每 tick 重置游标；
        // 修复后传输单调前进。
        // Without the runtime view the play edge is misjudged every tick → playFromHead
        // resets the cursor each tick; with the fix the transport advances monotonically.
        ev.evaluate(List.of(), Map.of(), DT, seat);
        float h1 = transports.get(music.id).headTick();
        ev.evaluate(List.of(), Map.of(), DT, seat);
        float h2 = transports.get(music.id).headTick();
        ev.evaluate(List.of(), Map.of(), DT, seat);
        float h3 = transports.get(music.id).headTick();
        assertEquals(1f, h1, 0.0001f, "one NBS tick advanced after the first play");
        assertTrue(h2 > h1 && h3 > h2, "held-high play must advance the transport, not restart it (h2="
            + h2 + ", h3=" + h3 + ")");
    }

    // ── P1-5：子求值器 audioTickStamp 每 tick 刷新 / per-tick stamp refresh ──

    @Test
    @DisplayName("封装内 AUDIO_OUT：发布时刻随 tick 刷新，外部消费 2 tick 后不判过期")
    void publishedStalenessFollowsCurrentTickNotCreationTick() {
        var outer = new NodeGraph();
        var encap = outer.addNode(NodeType.ENCAPSULATION, 0, 0);
        var sub = new NodeGraph();
        var play = sub.addNode(NodeType.CONST, -2, 0);
        play.params[0] = 1f;
        var music = sub.addNode(NodeType.MUSIC, 0, 0);
        music.song = longSong();
        var aout = sub.addNode(NodeType.AUDIO_OUT, 10, 0);
        aout.signalName = "B";
        sub.addConnection(play.id, 0, music.id, 0);
        sub.addConnection(music.id, 0, aout.id, 0);
        encap.subGraph = sub;

        var ev = new GraphEvaluator(outer);
        ev.setAudioBands(AudioBands.PORT);
        ev.setAudioHostPos(new BlockPos(1, 2, 3));
        ev.setAudioTransports(new HashMap<>());
        ev.restoreSubState(new RuntimeState());
        var seat = new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0);

        // 子求值器在 stamp=100 的 tick 创建；修复前 stamp 冻结在 100，stamp=101 的
        // 发布也带着 100 → now=102 的消费者按 now-1 门控判过期（封装内→外静音）。
        // The sub-evaluator is created on the stamp=100 tick; with the bug the stamp froze
        // at 100, so the tick-101 publish also carried 100 → a now=102 consumer fails the
        // now-1 gate (encap→out silence).
        ev.setAudioTickStamp(100);
        ev.evaluate(List.of(), Map.of(), DT, seat);
        ev.setAudioTickStamp(101);
        ev.evaluate(List.of(), Map.of(), DT, seat);

        String key = AudioBands.bandKey("B", "B");
        // 发布方消失（不再 evaluate）：now=102（上一刻发布）仍新鲜，now=103 起判陈旧
        // —— 1–2 tick 陈旧窗口成立，且静音点由**当前**发布时刻决定而非创建时刻。
        // The publisher disappears (no more evaluate): fresh at now=102 (last tick's
        // publish), stale from now=103 — the 1–2-tick staleness window holds, and the
        // silencing point tracks the CURRENT publish time, not the creation time.
        assertFalse(AudioBands.get("consumer-1", key, 102).events().isEmpty(),
            "audio published inside an encapsulation must stay fresh one tick after publishing — "
            + "a stamp frozen at the creation tick silences it immediately");
        assertTrue(AudioBands.get("consumer-2", key, 104).events().isEmpty(),
            "after the publisher stops, the band must go stale within the 1–2 tick window");
    }
}
