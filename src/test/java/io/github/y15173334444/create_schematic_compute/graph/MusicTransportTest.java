package io.github.y15173334444.create_schematic_compute.graph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MUSIC 传输状态机 + 音符展开测试：起播展开、跨 tick 展开、层音量/声像烘焙、非循环尾脉冲、循环回绕、停止。
 * MusicTransport tests: start-tick expansion, cross-tick expansion, layer-volume/panning baking,
 * non-loop finish pulse, loop wrap, and stop.
 */
class MusicTransportTest {

    /** tempo=1000 (10 t/s) → dt=0.1s ⇒ delta=1.0 NBS tick / advance. */
    private static final float DT = 0.1f;

    private static NbsSong song(int... ticks) {
        NbsSong s = new NbsSong();
        s.tempo = 1000;
        for (int t : ticks) s.putNote(t, 0, 2, 45, 100, 100, 0);
        return s;
    }

    @Test
    @DisplayName("起播展开 start tick，gain=1 / play expands the start tick at gain 1")
    void startTickFires() {
        NbsSong s = song(0);
        MusicTransport tr = new MusicTransport();
        tr.play(0);
        List<NoteEvent> evs = tr.advance(s, DT);
        assertEquals(1, evs.size());
        assertEquals(45, evs.get(0).key());
        assertEquals(1f, evs.get(0).gain());
    }

    @Test
    @DisplayName("跨 tick 逐个展开，每 tick 一次 / ticks fire exactly once across advances")
    void crossTickExpansion() {
        NbsSong s = song(0, 1, 2);
        MusicTransport tr = new MusicTransport();
        tr.play(0);
        List<NoteEvent> all = new ArrayList<>();
        for (int i = 0; i < 3; i++) all.addAll(tr.advance(s, DT));
        assertEquals(3, all.size(), "ticks 0,1,2 each fire once");
    }

    @Test
    @DisplayName("层音量烘焙：层 50 × 音符 100 → 力度 50 / layer volume baked into velocity")
    void layerVolumeBaked() {
        NbsSong s = new NbsSong();
        s.tempo = 1000;
        s.layers.clear();
        NbsSong.Layer l0 = new NbsSong.Layer();
        l0.volume = 50;
        s.layers.add(l0);
        s.putNote(0, 0, 2, 45, 100, 100, 0);
        MusicTransport tr = new MusicTransport();
        tr.play(0);
        List<NoteEvent> evs = tr.advance(s, DT);
        assertEquals(1, evs.size());
        assertEquals(50, evs.get(0).velocity(), "layer 50 × note 100 / 100 = 50");
    }

    /** 单音符曲目展开后的声像（层声像 = layerPan，音符声像 = notePan）。 */
    private static int bakedPan(int layerPan, int notePan) {
        NbsSong s = new NbsSong();
        s.tempo = 1000;
        s.layers.clear();
        NbsSong.Layer l0 = new NbsSong.Layer();
        l0.panning = layerPan;
        s.layers.add(l0);
        s.putNote(0, 0, 2, 45, 100, notePan, 0);
        MusicTransport tr = new MusicTransport();
        tr.play(0);
        List<NoteEvent> evs = tr.advance(s, DT);
        assertEquals(1, evs.size());
        return evs.get(0).panning();
    }

    @Test
    @DisplayName("层声像烘焙：层居中取音符声像 / a centred layer keeps the note panning")
    void layerPanningCenterTakesNote() {
        assertEquals(160, bakedPan(100, 160), "layer 100 (centre) leaves the note panning untouched");
    }

    @Test
    @DisplayName("层声像烘焙：层偏置与音符取平均（G2）/ an off-centre layer averages with the note")
    void layerPanningOffCenterAverages() {
        assertEquals(100, bakedPan(0, 200), "full-left layer + full-right note = centre");
        assertEquals(150, bakedPan(200, 100), "full-right layer + centred note = midpoint 150");
        assertEquals(26, bakedPan(0, 51), "average rounds half-up (25.5 → 26)");
    }

    @Test
    @DisplayName("缺层回退音符声像 / a missing layer falls back to the note panning")
    void layerPanningMissingLayer() {
        NbsSong s = new NbsSong();
        s.tempo = 1000;
        s.putNote(0, 0, 2, 45, 100, 160, 0);
        s.layers.clear(); // 坏数据路径：音符引用的层不存在
        MusicTransport tr = new MusicTransport();
        tr.play(0);
        List<NoteEvent> evs = tr.advance(s, DT);
        assertEquals(1, evs.size());
        assertEquals(160, evs.get(0).panning(), "missing layer behaves like a centred one");
    }

    @Test
    @DisplayName("非循环播到尾：finished 脉冲 + 停 / non-loop finish emits a pulse and stops")
    void nonLoopFinish() {
        NbsSong s = song(0, 1);
        MusicTransport tr = new MusicTransport();
        tr.play(0);
        tr.advance(s, DT); // fire tick 0
        tr.advance(s, DT); // fire tick 1, reach end (passEnd=2)
        assertFalse(tr.isPlaying(), "stops at end of a non-looping song");
        assertTrue(tr.consumeFinishedPulse(), "one finished pulse at the end");
        assertFalse(tr.consumeFinishedPulse(), "pulse is one-shot");
    }

    @Test
    @DisplayName("循环回绕保持播放，无尾脉冲 / loop wraps and keeps playing without a finish pulse")
    void loopWraps() {
        NbsSong s = song(0, 1);
        s.loop = true;
        s.loopStartTick = 0;
        MusicTransport tr = new MusicTransport();
        tr.play(0);
        for (int i = 0; i < 4; i++) tr.advance(s, DT);
        assertTrue(tr.isPlaying(), "loop keeps playing past song length");
        assertFalse(tr.consumeFinishedPulse(), "no finish pulse while looping");
    }

    @Test
    @DisplayName("停止后不再展开 / stop halts expansion")
    void stopHalts() {
        NbsSong s = song(0, 1, 2);
        MusicTransport tr = new MusicTransport();
        tr.play(0);
        tr.stop();
        assertTrue(tr.advance(s, DT).isEmpty());
    }

    @Test
    @DisplayName("seek 跳转：当帧从新位置继续，不重发已越过音符 / seek jumps and never re-fires passed notes")
    void seekJumps() {
        NbsSong s = song(0, 5);
        MusicTransport tr = new MusicTransport();
        tr.play(0);
        assertEquals(1, tr.advance(s, DT).size(), "tick 0 fires");
        tr.seek(5);
        List<NoteEvent> evs = tr.advance(s, DT);
        assertEquals(1, evs.size(), "note at tick 5 fires right after the jump — tick 0 not replayed");
        assertEquals(0f, evs.get(0).delaySeconds(), 1e-6, "fired from the seeked head (target now)");
    }

    @Test
    @DisplayName("预播提前量：lookahead 内音符提前展开、带目标时刻 / pre-roll fires notes early with targets")
    void preRollLookahead() {
        NbsSong s = song(0, 1, 2, 3, 4);
        MusicTransport tr = new MusicTransport();
        tr.play(0);
        // tempo 默认 1000 → 10 tick/s → tick 时长 0.1 s；lookahead 0.2 s 应一次发 3 个 tick
        List<NoteEvent> evs = tr.advance(s, 0.05f, 0.2f);
        assertEquals(3, evs.size(), "ticks 0-2 fire within the 0.2 s look-ahead window");
        assertEquals(0f, evs.get(0).delaySeconds(), 1e-3, "tick 0 targets now");
        assertEquals(0.1f, evs.get(1).delaySeconds(), 1e-3, "tick 1 targets +0.1 s");
        assertEquals(0.2f, evs.get(2).delaySeconds(), 1e-3, "tick 2 targets +0.2 s");
        // 已提前发过的不再重发
        assertTrue(tr.advance(s, 0.05f, 0f).isEmpty(), "fired-ahead ticks never re-fire");
    }

    @Test
    @DisplayName("playFromHead 续播：不重发播放头之前的音符 / playFromHead resumes without replaying passed notes")
    void playFromHeadResumes() {
        NbsSong s = song(0, 1, 2, 3);
        MusicTransport tr = new MusicTransport();
        tr.play(0);
        tr.advance(s, DT); // fires tick 0, head → 1.0
        tr.stop();
        tr.playFromHead(s);
        List<NoteEvent> all = new ArrayList<>();
        for (int i = 0; i < 3; i++) all.addAll(tr.advance(s, DT));
        assertEquals(3, all.size(), "ticks 1,2,3 fire — tick 0 not replayed");
    }

    @Test
    @DisplayName("曲尾再 play：回到曲首重播 / play at song end restarts from the top")
    void playAtEndRestarts() {
        NbsSong s = song(0, 1);
        MusicTransport tr = new MusicTransport();
        tr.play(0);
        tr.advance(s, DT);
        tr.advance(s, DT); // non-loop finish: head=2 (passEnd), playing=false
        assertFalse(tr.isPlaying());
        tr.playFromHead(s); // 头在曲尾 → 必须回头，否则永远无声
        assertTrue(tr.isPlaying());
        assertEquals(0, tr.headTick(), "head restarted at 0");
        assertEquals(1, tr.advance(s, DT).size(), "tick 0 fires again on the replay");
    }

    @Test
    @DisplayName("停止/跳转置一次性 flush 脉冲，续播不置 / stop and seek set a one-shot flush pulse, resume does not")
    void flushPulseOnStopAndSeek() {
        NbsSong s = song(0, 5);
        MusicTransport tr = new MusicTransport();
        tr.play(0);
        assertFalse(tr.consumeFlushPulse(), "plain playback raises no pulse");
        tr.stop();
        assertTrue(tr.consumeFlushPulse(), "stop marks dispatched-but-unplayed notes stale");
        assertFalse(tr.consumeFlushPulse(), "the pulse is one-shot");
        tr.seek(3);
        assertTrue(tr.consumeFlushPulse(), "seek marks the old position's in-flight notes stale");
        tr.playFromHead(s);
        assertFalse(tr.consumeFlushPulse(), "resuming raises no pulse of its own");
    }
}
