package io.github.y15173334444.create_schematic_compute.client.audio;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * CSC 音频引擎混音核心：把音符事件调度成声音（样本重采样 + 声像/增益烘焙），渲染立体声 PCM。
 * **纯逻辑，无 Minecraft 依赖**（可单测，{@code AudioMixerTest}）。
 * <p>CSC audio-engine mixing core: schedules note events into voices (sample resampling with
 * baked panning/gain) and renders stereo PCM. Pure logic, no Minecraft dependencies
 * (unit-testable, see {@code AudioMixerTest}).</p>
 *
 * <p><b>为什么自管混音</b>（eval §三 B 路线「自管混音层」）：原版播放是「一音符一声部」，
 * 打满 OpenAL 声部池（247）后**静默丢音**——高密度曲目普遍复现（plan 风险 1 / F6）。本混音器把
 * 任意多音符合成进**一条 PCM 流**（一个声部输出），并发上限只受本类 {@code MAX_VOICES} 约束，
 * 与原版声部池无关。
 * <b>Why self-mixing</b> (eval §三 route B, the mixing tier): vanilla playback is
 * one-voice-per-note and silently drops notes once the 247-channel pool saturates (plan risk 1
 * / F6, reproduced on dense songs). This mixer folds any number of notes into <b>one PCM
 * stream</b> (a single output voice), bounded only by {@code MAX_VOICES}.</p>
 *
 * <p><b>时序口径</b>：调度以「渲染游标 + 延迟帧」定位；输出通路（{@code CscAudioStream}）以
 * 50 ms 小缓冲回填，预填队列 ≈ 200 ms——相对时序（同 tick 子 tick 偏移）精确保持，绝对延迟
 * ≈ 预填队列长（文档口径 ±1 游戏刻 + 子 tick 尽力）。
 * <b>Timing</b>: notes are placed at "render cursor + delay frames"; the output path refills
 * 50 ms chunks (≈200 ms pre-queue). Relative timing (sub-tick offsets within a tick) is exact;
 * absolute latency ≈ the pre-queue length (documented as ±1 game tick + best-effort sub-tick).</p>
 */
public final class AudioMixer {

    /** 输出采样率（立体声 16-bit LE 由 {@code CscAudioStream} 落字节）。 */
    public static final int SAMPLE_RATE = 44100;
    /** 并发声部上限（远高于原版 247 声部池；超出时优先剥夺最轻声部）。 */
    public static final int MAX_VOICES = 1024;
    /** 默认衰减半径（格）：无 radius 覆盖时的线性衰减距离（plan D13 口径 48）。 */
    public static final double DEFAULT_RADIUS = 48.0;
    /** 限幅阈值（峰值上限，留 5% 余量）。 */
    public static final float LIMIT = 0.95f;
    /** 密度增益分级参考声部数：活跃声部 ≤ 此值不缩放（独奏/小编制保持原响度），
     *  超出后每声部按 √(REF/N) 缩放——N 个非相干声部叠加和值 ~√N 增长，收拢后限幅器
     *  只做安全网（此前密集段被压 −7~−11 dB，听感「挤/压扁」）。
     *  Density gain-staging reference: no scaling at or below this voice count (solos keep
     *  full level); beyond it each voice scales by √(REF/N) — N incoherent voices sum to ~√N,
     *  so scaling keeps the mix near unity and the limiter stays a safety net (dense sections
     *  were being crushed −7~−11 dB, the "squashed" listening complaint). */
    public static final int DENSITY_REF = 24;

    private static final class Voice {
        float[] sample;         // 单声道样本（-1..1）
        double pos;             // 样本内浮点游标
        double step;            // 每输出帧前进量（变调）
        float gainL, gainR;
        long startFrame;        // 起音的全局输出帧
    }

    private final ArrayList<Voice> voices = new ArrayList<>();
    private long renderedFrames = 0;
    /** 限幅器增益状态（块级 attack/release，见 render）。 */
    private float limitGain = 1f;

    // ══════════════ 调度 / scheduling ══════════════

    /**
     * 调度一条音符：延迟 delayFrames 后以 pitchRatio 变调播放样本，增益按左右声像烘焙。
     * Schedule one note: play {@code sample} after {@code delayFrames} frames at
     * {@code pitchRatio} speed, with baked stereo gains.
     *
     * @param sample     单声道浮点样本（-1..1） / mono float samples
     * @param srcRate    样本原始采样率 / the sample's native rate
     * @param pitchRatio 频率倍率（1 = 原速） / frequency multiplier
     */
    public synchronized void schedule(long delayFrames, float[] sample, int srcRate,
                                      double pitchRatio, float gainL, float gainR) {
        scheduleAtFrame(renderedFrames + Math.max(0, delayFrames), sample, srcRate, pitchRatio, gainL, gainR);
    }

    /**
     * 按**绝对输出帧**调度（落点与调用时的游标位置解耦）。调度端做墙钟锚定时若混音渲染正在
     * 进行，{@link #schedule} 的「游标 + 延迟」会在拿到锁后吃进已完成渲染的步进（每批音符
     * 整体后移一块，块数随撞锁次数漂移 = 「推迟/挤堆」）；绝对帧落点不受此影响。
     * 已越过的帧钳到游标（尽早点亮）。返回**实际起音帧**（诊断漂移 = 返回值 − 目标）。
     * <p>Schedule at an <b>absolute output frame</b>, decoupled from the cursor at call time.
     * With wall-clock anchoring, {@link #schedule}'s "cursor + delay" placement absorbs any
     * render that completes while waiting on the lock (shifting whole batches late by a
     * buffer — the "delay/bunch" symptom). Absolute-frame placement is immune. Frames already
     * rendered clamp to the cursor (fire ASAP). Returns the <b>actual start frame</b>
     * (diagnostic drift = return − target).</p>
     */
    public synchronized long scheduleAtFrame(long absoluteFrame, float[] sample, int srcRate,
                                             double pitchRatio, float gainL, float gainR) {
        if (sample == null || sample.length < 2) return renderedFrames;
        if (gainL == 0f && gainR == 0f) return renderedFrames;
        if (voices.size() >= MAX_VOICES) {
            // 优先剥夺最轻声部（同轻重取更旧）——比盲删最旧少砍听感 / steal the quietest first
            int worst = 0;
            for (int i = 1; i < voices.size(); i++) {
                Voice a = voices.get(i), b = voices.get(worst);
                if (a.gainL + a.gainR < b.gainL + b.gainR) worst = i;
            }
            voices.remove(worst);
        }
        Voice v = new Voice();
        v.sample = sample;
        v.pos = 0;
        v.step = pitchRatio <= 0 ? 1.0 : pitchRatio * srcRate / (double) SAMPLE_RATE;
        float density = densityGain(voices.size() + 1);
        v.gainL = gainL * density;
        v.gainR = gainR * density;
        v.startFrame = Math.max(renderedFrames, absoluteFrame);
        voices.add(v);
        return v.startFrame;
    }

    /** 密度增益：活跃声部 ≤ {@link #DENSITY_REF} 时 1，超出按 √(REF/N) 收拢（纯函数，可单测）。 */
    public static float densityGain(int activeVoices) {
        if (activeVoices <= DENSITY_REF) return 1f;
        return (float) Math.sqrt((double) DENSITY_REF / activeVoices);
    }

    /** 当前在响/待响声部数（诊断用）。 */
    public synchronized int activeVoices() {
        return voices.size();
    }

    /** 已渲染的总输出帧数（渲染游标；调度端做墙钟锚定用）。 */
    public synchronized long renderedFrames() {
        return renderedFrames;
    }

    /** 当前限幅增益（<1 = 抽吸中；诊断采样用）。 */
    public synchronized float limitGain() {
        return limitGain;
    }

    // ══════════════ 渲染 / rendering ══════════════

    /**
     * 渲染 frames 帧立体声（out 长度 ≥ frames×2，交错 L/R），推进渲染游标。
     * 无声部时输出静音（保持流不断）。块级包络限幅防密集和弦破音：
     * 每块先求峰值，目标增益 = LIMIT/峰值（超限才压）；压低瞬间生效（attack）、
     * 回升每块 15%（≈250 ms release）——比逐样本软限幅更少「压扁」失真。
     * <p>Render {@code frames} stereo frames (interleaved L/R) and advance the cursor.
     * Silence fills the block when idle (keeps the stream alive). Block-level envelope
     * limiting protects dense chords from clipping: the block peak sets a target gain
     * ({@code LIMIT/peak}, only when over), applied instantly (attack) and released 15% per
     * block (~250 ms) — far less "squashed" distortion than per-sample soft clipping.</p>
     */
    public synchronized void render(float[] out, int frames) {
        Arrays.fill(out, 0, frames * 2, 0f);
        long base = renderedFrames;
        for (int i = voices.size() - 1; i >= 0; i--) {
            Voice v = voices.get(i);
            int startF = (int) Math.max(0, v.startFrame - base);
            int len = v.sample.length;
            for (int f = startF; f < frames; f++) {
                int idx = (int) v.pos;
                if (idx >= len - 1) break;                   // 播完 / sample exhausted
                float frac = (float) (v.pos - idx);
                float s = v.sample[idx] * (1f - frac) + v.sample[idx + 1] * frac;
                out[f * 2] += s * v.gainL;
                out[f * 2 + 1] += s * v.gainR;
                v.pos += v.step;
            }
            if (v.pos >= len - 1 && v.startFrame < base + frames) voices.remove(i);
        }
        renderedFrames = base + frames;

        // ── 块级包络限幅 / block-level envelope limiter ──
        float peak = 0f;
        for (int i = 0; i < frames * 2; i++) peak = Math.max(peak, Math.abs(out[i]));
        float target = peak > LIMIT ? LIMIT / peak : 1f;
        float gain = target < limitGain ? target : limitGain + (target - limitGain) * 0.15f;
        limitGain = gain;
        if (gain < 0.9999f) {
            for (int i = 0; i < frames * 2; i++) out[i] *= gain;
        }
        // 终段软限幅兜底（限幅器块内瞬态越界时的安全网）
        for (int i = 0; i < frames * 2; i++) out[i] = softClip(out[i]);
    }

    // ══════════════ 纯映射函数 / pure mapping helpers ══════════════

    /** 软限幅（±1.5 输入域三次近似，输出 ±1；小信号线性）。 */
    public static float softClip(float x) {
        if (x > 1.5f) return 1f;
        if (x < -1.5f) return -1f;
        return x - (x * x * x) / 6.75f;
    }

    /** 线性距离衰减：0 格全响，radius 格静音（radius ≤ 0 取默认 48）。 */
    public static float distanceGain(double distance, double radius) {
        double r = radius > 0 ? radius : DEFAULT_RADIUS;
        if (distance <= 0) return 1f;
        return (float) Math.max(0.0, 1.0 - distance / r);
    }

    /** 等功率声像：pan ∈ [−1,1]（−1 全左）→ out[0]=左增益、out[1]=右增益。 */
    public static void panGains(float pan, float[] out2) {
        float p = Math.max(-1f, Math.min(1f, pan));
        double angle = (p + 1) * (Math.PI / 4);
        out2[0] = (float) Math.cos(angle);
        out2[1] = (float) Math.sin(angle);
    }

    /**
     * 落点帧（纯函数）：base + 墙钟增量 + （delaySeconds − 预播 − 迟到）。
     * delaySeconds 自<b>下发时刻</b>起算——到达迟到（lateSeconds）必须扣除，否则迟到 1:1 转成
     * 播出推迟，积压段与后续段重叠（「积压音频挤在一起」）。
     * <p>Placement frame (pure): base + wall advance + (delaySeconds − pre-roll − lateness).
     * {@code delaySeconds} is measured from <b>dispatch</b>; arrival lateness must be subtracted.</p>
     */
    public static long placementFrames(long baseFrames, long wallAdvanceFrames,
                                       float delaySeconds, float lateSeconds, float prerollSeconds) {
        long lateFrames = Math.round(Math.max(0f, lateSeconds) * SAMPLE_RATE);
        return baseFrames + wallAdvanceFrames
            + Math.round(delaySeconds * SAMPLE_RATE)
            - Math.round(prerollSeconds * SAMPLE_RATE)
            - lateFrames;
    }
}
