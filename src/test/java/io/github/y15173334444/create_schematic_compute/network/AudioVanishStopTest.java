package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.graph.AudioRef;
import io.github.y15173334444.create_schematic_compute.graph.GraphEvaluator;
import io.github.y15173334444.create_schematic_compute.graph.GraphNode;
import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import io.github.y15173334444.create_schematic_compute.graph.NoteEvent;
import io.github.y15173334444.create_schematic_compute.graph.PinType;
import io.github.y15173334444.create_schematic_compute.graph.RuntimeState;
import io.github.y15173334444.create_schematic_compute.graph.SpeakerSink;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 消失哨兵（真·骤停，2026-11-21 拍板 = 方案A含图停机）测试：发布方停发 ≥2 刻后，上一读
 * 新鲜的消费者收到<b>一次性停止标记</b>（空事件 + stopSignal），经既有 SPEAKER_PLAY→sink
 * 管线让客户端撤销预播声部——骤停即刻静音，而不是等 2 秒余音自然衰减。锁死：
 * <ul>
 *   <li>标记只在「新鲜 → 陈旧」转换沿发一次，闩锁沿新鲜读复位（二次消失再触发）；</li>
 *   <li>从未读过就陈旧的冷消费者不触发（无预播可撤）；</li>
 *   <li>切边环/遍尾发布方的<b>恒定上一刻读</b>（stamp 恒等于 now-1）够不到陈旧阈值，稳态
 *       绝不误报；</li>
 *   <li>冲突存续期的静音不是消失（stamp 照常前进，消费者不收标记）；</li>
 *   <li>管线端到端：标记经 PRIVATE_IN(audio)→SPEAKER_PLAY 触发 {@code sink.stopPlayback()}。</li>
 * </ul>
 *
 * <p>Vanish-sentinel tests (true abrupt stop, ruled 2026-11-21 = plan A incl. graph-stop):
 * after a publisher goes silent for >=2 ticks, a previously-fresh consumer receives a
 * <b>one-shot stop marker</b> (empty events + stopSignal) that rides the existing
 * SPEAKER_PLAY→sink pipeline so the client cancels the preroll voices — immediate silence
 * instead of a 2-second ring-out. Pinned here:</p>
 * <ul>
 *   <li>the marker fires exactly once on the fresh→stale edge; the latch resets on a fresh
 *       read (a second vanish fires again);</li>
 *   <li>a cold consumer (never read before going stale) gets no marker (nothing to cancel);</li>
 *   <li>the constant now-1 reads of cut edges / tail publishers never reach the stale
 *       threshold — steady state never misfires;</li>
 *   <li>conflict silence is not vanish (stamps keep advancing, consumers get no marker);</li>
 *   <li>end-to-end: the marker rides PRIVATE_IN(audio)→SPEAKER_PLAY into
 *       {@code sink.stopPlayback()}.</li>
 * </ul>
 */
class AudioVanishStopTest {

    @BeforeEach
    void reset() { AudioBands.clear(); }

    @AfterEach
    void cleanup() {
        // 全局静态表跨类共享：stamp/游标残留会污染后续测试类
        // The global static tables leak across classes; leftovers poison the next class.
        AudioBands.clear();
        SignalBus.clear();
    }

    private static AudioRef ref() {
        return new AudioRef(List.of(new NoteEvent(0, 45, 100, 100, 0, 1f, 0.1f)), 1f);
    }

    @Test
    @DisplayName("消失哨兵：停发 ≥2 刻后上一读新鲜的消费者收到一次性停止标记，之后回 EMPTY")
    void vanishFiresOneShotStopMarker() {
        AudioBands.publish("B", ref(), 10L);
        assertNotSame(AudioRef.EMPTY, AudioBands.get("c1", "B", 10L), "fresh read delivers");
        // T+1：stamp 仍在窗内（游标拒重放 → EMPTY，不是标记）
        assertSame(AudioRef.EMPTY, AudioBands.get("c1", "B", 11L),
            "within the window the cursor blocks the re-serve without a marker");
        // T+2：stamp 落到窗下沿之下 → 一次性停止标记
        AudioRef stop = AudioBands.get("c1", "B", 12L);
        assertTrue(stop.stopSignal(), "the vanish verdict yields the stop marker");
        assertTrue(stop.events().isEmpty(), "the marker carries no events");
        // T+3：闩锁已清 → 标记每消失恰一次
        assertSame(AudioRef.EMPTY, AudioBands.get("c1", "B", 13L), "once per vanish");
    }

    @Test
    @DisplayName("恢复发布复位闩锁：二次消失再触发；从未读过就陈旧的冷消费者不触发")
    void resumeResetsLatchAndColdStaleNeverFires() {
        AudioBands.publish("B", ref(), 10L);
        assertNotSame(AudioRef.EMPTY, AudioBands.get("c1", "B", 10L));
        assertSame(AudioRef.EMPTY, AudioBands.get("c1", "B", 11L));
        assertTrue(AudioBands.get("c1", "B", 12L).stopSignal(), "first vanish fires");
        assertSame(AudioRef.EMPTY, AudioBands.get("c1", "B", 13L), "latch cleared");
        // 发布方回归 → 新鲜读复位闩锁
        AudioBands.publish("B", ref(), 20L);
        assertNotSame(AudioRef.EMPTY, AudioBands.get("c1", "B", 20L), "resume reads fresh");
        assertSame(AudioRef.EMPTY, AudioBands.get("c1", "B", 21L));
        assertTrue(AudioBands.get("c1", "B", 22L).stopSignal(), "second vanish fires again");
        // 冷消费者：从未新鲜过就遇陈旧 → 无标记（无预播可撤）
        assertSame(AudioRef.EMPTY, AudioBands.get("c2", "B", 22L),
            "a never-fresh consumer gets no marker");
    }

    @Test
    @DisplayName("切边稳态不误报：恒定上一刻读（stamp=now-1）够不到陈旧阈值，二十刻零标记")
    void steadyLastTickReadsNeverFire() {
        // 被切音频边/遍尾发布方的稳态形态：发布方每刻发布，读者恒读上一刻的发布。
        // The cut-edge / tail-publisher steady state: the publisher publishes every tick and
        // the reader perpetually reads last tick's publish.
        for (long t = 10; t < 30; t++) {
            AudioBands.publish("B", ref(), t);
            AudioRef r = AudioBands.get("c1", "B", t + 1);
            assertNotSame(AudioRef.EMPTY, r, "steady state keeps delivering");
            assertFalse(r.stopSignal(), "steady state never fires the sentinel");
        }
    }

    @Test
    @DisplayName("冲突静音不是消失：stamp 照常前进，窗满自愈即恢复；冲突后的真消失照常触发")
    void conflictSilenceIsNotVanish() {
        assertTrue(AudioBands.publish("B", ref(), 10L, "A#1"));
        assertFalse(AudioBands.publish("B", ref(), 10L, "B#1"), "collision");
        // 原所有者冲突期报 false 但照常写入（stamp 前进）——消费者静音但不判消失
        assertFalse(AudioBands.publish("B", ref(), 11L, "A#1"));
        assertSame(AudioRef.EMPTY, AudioBands.get("c1", "B", 11L), "conflict silence");
        // T12：证据出窗自愈——消费者拿到恢复的音频（stamp 前进 = 发布方还在，绝不判消失）
        assertNotSame(AudioRef.EMPTY, AudioBands.get("c1", "B", 12L),
            "the healed band serves again — conflict silence is not vanish");
        // T13：发布方真消失（stamp 冻结在 11，落后窗下沿）→ 恰一次标记
        assertTrue(AudioBands.get("c1", "B", 13L).stopSignal(),
            "a real vanish after a conflict still fires exactly once");
        assertSame(AudioRef.EMPTY, AudioBands.get("c1", "B", 14L), "latch cleared");
    }

    @Test
    @DisplayName("管线端到端：消失标记经 PRIVATE_IN(audio)→SPEAKER_PLAY 触发 sink.stopPlayback 恰一次")
    void stopMarkerRidesThePipelineToTheSink() {
        var g = new NodeGraph();
        var in = g.addNode(NodeType.PRIVATE_IN, 0, 0);
        in.signalName = "B";
        in.bandTypes = new ArrayList<>(List.of(PinType.AUDIO));   // 声明 AUDIO → 读 AudioBands
        var spk = g.addNode(NodeType.SPEAKER_PLAY, 10, 0);
        assertTrue(g.addConnection(in.id, 0, spk.id, 0));

        GraphEvaluator ev = new GraphEvaluator(g);
        ev.setSignalBus(SignalBus.PORT);
        ev.setAudioBands(AudioBands.PORT);
        ev.restoreSubState(new RuntimeState());
        ev.setAudioHostPos(new BlockPos(1, 2, 3));
        List<NoteEvent> played = new ArrayList<>();
        List<Boolean> stops = new ArrayList<>();
        ev.setSpeakerSink(new SpeakerSink() {
            @Override public void play(List<NoteEvent> events, float gain) { played.addAll(events); }
            @Override public void stopPlayback() { stops.add(Boolean.TRUE); }
        });
        var seat = new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0);

        // T10：发布 + 求值 → 音符到 sink
        AudioBands.publish("B", ref(), 10L);
        ev.setAudioTickStamp(10L);
        ev.evaluate(List.of(), Map.of(), 0.05f, seat);
        assertEquals(1, played.size(), "the fresh batch reaches the sink");
        assertTrue(stops.isEmpty());

        // T11：发布方消失第一刻 —— 窗内，游标拒重放，无标记
        ev.setAudioTickStamp(11L);
        ev.evaluate(List.of(), Map.of(), 0.05f, seat);
        assertTrue(stops.isEmpty(), "the in-window tick ships no marker");

        // T12：真陈旧 → 停止标记到达 sink，预播撤销
        ev.setAudioTickStamp(12L);
        ev.evaluate(List.of(), Map.of(), 0.05f, seat);
        assertEquals(1, stops.size(), "the vanish verdict cancels the preroll exactly once");

        // T13：闩锁已清 → 不再发
        ev.setAudioTickStamp(13L);
        ev.evaluate(List.of(), Map.of(), 0.05f, seat);
        assertEquals(1, stops.size(), "the marker is once per vanish");

        // 恢复发布 → 新鲜音符回来（闩锁复位）
        AudioBands.publish("B", ref(), 20L);
        ev.setAudioTickStamp(20L);
        ev.evaluate(List.of(), Map.of(), 0.05f, seat);
        assertEquals(2, played.size(), "audio resumes after the publisher returns");
        assertEquals(1, stops.size());
    }
}
