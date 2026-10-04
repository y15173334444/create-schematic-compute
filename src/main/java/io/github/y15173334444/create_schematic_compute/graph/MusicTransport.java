package io.github.y15173334444.create_schematic_compute.graph;

import java.util.ArrayList;
import java.util.List;

/**
 * MUSIC 节点的传输状态机 + 音符展开。**纯逻辑，无 MC 依赖**（可单测）。
 * <p>Transport state machine + note expansion for the MUSIC node. Pure, unit-testable.</p>
 * <p>推进模型（plan §3.5）：每服务端 tick 由 {@code dt} 推进 {@code delta = tempo/100 × dt} 个
 * NBS tick；越过的整数 tick 上的音符被展开为 {@link NoteEvent}（烘焙层音量与层声像，gain=1），子 tick
 * 偏移 = 音符在本 tick 窗口内的分数位置。非循环播到尾置 finished 脉冲；循环回绕到 loopStart。</p>
 * <p>传输状态（playing/head/nextFire）由宿主 BE 经类型段 NBT 持久化并注入求值器。</p>
 */
public final class MusicTransport {

    /** 预播提前量（秒）：服务端提前展开这段窗口内的音符、事件带目标时刻（delaySeconds），
     *  客户端按目标定点播——服务端 tick 过载/进程停顿时的「晚点/成批」到达由此吸收。
     *  2 s 覆盖实测的服务端停顿（750–950 ms）并留一倍余量；播放时刻由客户端墙钟定点，
     *  加宽只增加吸收带宽、不推迟任何音符；代价（停止/跳转后已下发未播的尾巴）由
     *  {@link #consumeFlushPulse()} 驱动的停止清队列机制抵消。
     *  2 s covers the measured server-side stalls (750–950 ms) with double headroom; the
     *  client places every note on its own wall clock, so widening only adds absorption
     *  band and delays nothing; the cost (a tail of dispatched-but-unplayed notes after a
     *  stop/seek) is cancelled by the flush mechanism driven by {@link #consumeFlushPulse()}. */
    public static final float PREROLL_SECONDS = 2.0f;

    /** 是否正在播放。 */
    public boolean playing;
    /** 播放头位置（NBS tick，浮点）。 */
    public float head;
    /** 下一个待展开的整数 tick（避免同 tick 双发）。 */
    public int nextFire;
    /** 本步是否刚非循环播到尾（consumed 一次后清零）。 */
    private boolean finishedPulse;
    /** 本步是否刚发生停止/跳转沿（consumed 一次后清零）：预播窗口内已下发未播的音符
     *  因传输状态变化而失效，求值器据此在音频引用里打一次性停止标记，穿透管线到
     *  客户端清除排队声部。
     *  A stop/seek edge happened this step (consumed once): notes already dispatched inside
     *  the pre-roll window are stale; the evaluator stamps a one-shot stop marker onto the
     *  audio ref so the client cancels its queued voices. */
    private boolean flushPulse;

    public MusicTransport() {}

    /** 播放：从 startTick 起（该 tick 音符会展开）。 */
    public void play(int startTick) {
        head = Math.max(0, startTick);
        playFromHead(null);
    }

    /**
     * 从当前播放头起播（play 触点上升沿语义：已 seek 的先跳、暂停的续播；不重展开已越过的音符）。
     * 播放头停在曲尾（非循环播完后的常态）时<b>回到曲首重播</b>——否则"播完后 play"会立刻再次
     * 判定到尾而永远无声。
     */
    public void playFromHead(NbsSong song) {
        playing = true;
        finishedPulse = false;
        if (song != null) {
            int passEnd = song.songLength > 0 ? song.songLength : song.computeLength();
            if (passEnd > 0 && head >= passEnd) head = 0f;
        }
        nextFire = (int) Math.ceil(head);
    }

    /** 停止（复位播放头到 0 不做；仅停）。置 flush 脉冲：窗口内已下发未播的音符失效。 */
    public void stop() {
        playing = false;
        flushPulse = true;
    }

    /** 跳转到 tick（重定位播放头，不展开该 tick 音符）。置 flush 脉冲：跳走前已下发的
     *  未播音符属于旧位置，客户端要清掉。 */
    public void seek(int tick) {
        head = Math.max(0, tick);
        nextFire = (int) Math.ceil(head);
        flushPulse = true;
    }

    /** 本步非循环播到尾则返回 true（一次性，读后清零）。 */
    public boolean consumeFinishedPulse() {
        boolean f = finishedPulse;
        finishedPulse = false;
        return f;
    }

    /** 本步是否刚发生停止/跳转沿（一次性，读后清零）。求值器在「停止后未续播」时把标记
     *  打进音频引用；同 tick 停止又续播则吞掉标记（新位置的音符不能被清）。
     *  A stop/seek edge this step (consumed once). The evaluator stamps the marker onto the
     *  audio ref only when playback did NOT resume the same tick — a same-tick resume must
     *  not have its fresh notes cancelled. */
    public boolean consumeFlushPulse() {
        boolean f = flushPulse;
        flushPulse = false;
        return f;
    }

    public boolean isPlaying() { return playing; }
    public int headTick() { return (int) Math.floor(head); }
    public float headSeconds(NbsSong song) {
        float tps = song != null ? Math.max(0.01f, song.tempo / 100f) : 10f;
        return head / tps;
    }

    /**
     * 推进 dt 秒并展开本步越过的整数 tick 上的音符（无预播，事件 delaySeconds ∈ [0, dt)）。
     * Advance {@code dt} seconds, expanding notes on integer ticks crossed this step.
     */
    public List<NoteEvent> advance(NbsSong song, float dt) {
        return advance(song, dt, 0f);
    }

    /**
     * 推进 dt 秒并展开音符；lookaheadSeconds &gt; 0 时把这段窗口内的音符**提前**展开，
     * 每个事件携带 {@code delaySeconds} = 自此刻到音符目标时刻（乐理时间）的秒数。
     * 提前量内的事件不重发（nextFire 单调），到点由客户端按目标时刻播出。
     * <p>Advance {@code dt} seconds and expand notes; with {@code lookaheadSeconds > 0} notes
     * within that window fire <b>early</b>, each carrying {@code delaySeconds} — the musical
     * time from now until the note's target. Fired-ahead notes never re-fire (nextFire is
     * monotonic); the client plays them at their target time.</p>
     */
    public List<NoteEvent> advance(NbsSong song, float dt, float lookaheadSeconds) {
        List<NoteEvent> out = new ArrayList<>();
        finishedPulse = false;
        if (!playing || song == null || dt <= 0) return out;

        int passEnd = song.songLength > 0 ? song.songLength : song.computeLength();
        if (passEnd <= 0) { playing = false; finishedPulse = true; return out; }

        float tps = Math.max(0.01f, song.tempo / 100f);
        float delta = tps * dt;
        if (delta <= 0) return out;
        float headAtCall = head;
        float newHead = head + delta;
        float lookTicks = Math.max(0f, lookaheadSeconds) * tps;
        float fireBound = Math.min(newHead + lookTicks, (float) passEnd);

        for (int t = nextFire; t < fireBound; t++) {
            float delaySeconds = Math.max(0f, (t - headAtCall) / tps);
            for (NbsSong.Note n : song.notesAtTick(t)) {
                float lv = layerVolume(song, n.layer);
                int vel = (int) Math.round(lv * n.velocity / 100f);
                vel = Math.max(0, Math.min(100, vel));
                int pan = bakePanning(layerPanning(song, n.layer), n.panning);
                out.add(new NoteEvent(n.instrument, n.key, vel, pan, n.pitch, 1f, delaySeconds));
            }
            nextFire = t + 1;
        }

        if (newHead >= passEnd) {
            if (song.loop) {
                head = Math.max(0, song.loopStartTick);
                nextFire = (int) Math.ceil(head);
            } else {
                head = passEnd;
                playing = false;
                finishedPulse = true;
            }
        } else {
            head = newHead;
        }
        return out;
    }

    /** 层音量（0..100，越界/缺层取 100）。 */
    private static float layerVolume(NbsSong song, int layer) {
        if (layer < 0 || layer >= song.layers.size()) return 100f;
        return song.layers.get(layer).volume;
    }

    /** 层声像（0..200、100=居中；越界/缺层取 100）。 */
    private static int layerPanning(NbsSong song, int layer) {
        if (layer < 0 || layer >= song.layers.size()) return 100;
        return song.layers.get(layer).panning;
    }

    /** 层/音符声像混合（G2，NBS Calculations 同式）：层居中（100）取音符声像；否则取两者平均
     *  （四舍五入）。结果钳 0–200（本事件是播放副本，不回写曲目）。 */
    private static int bakePanning(int layerPan, int notePan) {
        int mixed = layerPan == 100 ? notePan : (int) Math.round((layerPan + notePan) / 2.0);
        return Math.max(0, Math.min(200, mixed));
    }
}
