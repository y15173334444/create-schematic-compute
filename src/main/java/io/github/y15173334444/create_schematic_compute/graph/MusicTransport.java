package io.github.y15173334444.create_schematic_compute.graph;

import java.util.ArrayList;
import java.util.List;

/**
 * MUSIC 节点的传输状态机 + 音符展开。**纯逻辑，无 MC 依赖**（可单测）。
 * <p>Transport state machine + note expansion for the MUSIC node. Pure, unit-testable.</p>
 * <p>推进模型（plan §3.5）：每服务端 tick 由 {@code dt} 推进 {@code delta = tempo/100 × dt} 个
 * NBS tick；越过的整数 tick 上的音符被展开为 {@link NoteEvent}（烘焙层音量，gain=1），子 tick
 * 偏移 = 音符在本 tick 窗口内的分数位置。非循环播到尾置 finished 脉冲；循环回绕到 loopStart。</p>
 * <p>传输状态（playing/head/nextFire）由宿主 BE 经类型段 NBT 持久化并注入求值器。</p>
 */
public final class MusicTransport {

    /** 预播提前量（秒）：服务端提前展开这段窗口内的音符、事件带目标时刻（delaySeconds），
     *  客户端按目标定点播——服务端 tick 过载/进程停顿时的「晚点/成批」到达由此吸收。
     *  0.2 s 与客户端输出余量（4×80 ms）配对：预发与预填同增，恒定净延迟持平。 */
    public static final float PREROLL_SECONDS = 0.2f;

    /** 是否正在播放。 */
    public boolean playing;
    /** 播放头位置（NBS tick，浮点）。 */
    public float head;
    /** 下一个待展开的整数 tick（避免同 tick 双发）。 */
    public int nextFire;
    /** 本步是否刚非循环播到尾（consumed 一次后清零）。 */
    private boolean finishedPulse;

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

    /** 停止（复位播放头到 0 不做；仅停）。 */
    public void stop() {
        playing = false;
    }

    /** 跳转到 tick（重定位播放头，不展开该 tick 音符）。 */
    public void seek(int tick) {
        head = Math.max(0, tick);
        nextFire = (int) Math.ceil(head);
    }

    /** 本步非循环播到尾则返回 true（一次性，读后清零）。 */
    public boolean consumeFinishedPulse() {
        boolean f = finishedPulse;
        finishedPulse = false;
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
                out.add(new NoteEvent(n.instrument, n.key, vel, n.panning, n.pitch, 1f, delaySeconds));
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
}
