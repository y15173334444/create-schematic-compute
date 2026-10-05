package io.github.y15173334444.create_schematic_compute.client.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link AudioMixer} 纯混音核心测试：调度时序 / 重采样变调 / 声像增益 / 声部上限 / 软限幅。
 * Pure mixing-core tests: scheduling timing / pitch resampling / pan gains / voice cap / soft clip.
 */
class AudioMixerTest {

    /** 斜坡样本：便于观察插值与位置推进。 */
    private static float[] ramp(int n) {
        float[] s = new float[n];
        for (int i = 0; i < n; i++) s[i] = i / (float) n;
        return s;
    }

    private static float peak(float[] block) {
        float m = 0f;
        for (float x : block) m = Math.max(m, Math.abs(x));
        return m;
    }

    @Test
    void silentWhenIdle() {
        AudioMixer m = new AudioMixer();
        float[] out = new float[256 * 2];
        m.render(out, 256);
        assertEquals(0f, peak(out), 1e-6f);
        assertEquals(0, m.activeVoices());
    }

    @Test
    void voiceStartsAtScheduledDelay() {
        AudioMixer m = new AudioMixer();
        float[] sample = new float[64];
        java.util.Arrays.fill(sample, 1f);
        m.schedule(100, sample, AudioMixer.SAMPLE_RATE, 1.0, 1f, 1f);

        float[] early = new float[100 * 2];
        m.render(early, 100);
        assertEquals(0f, peak(early), 1e-6f, "nothing before the start frame");

        float[] late = new float[10 * 2];
        m.render(late, 10);
        assertTrue(peak(late) > 0.75f, "voice is sounding after the delay");
    }

    @Test
    void delayZeroStartsImmediately() {
        AudioMixer m = new AudioMixer();
        float[] sample = new float[64];
        java.util.Arrays.fill(sample, 0.5f);
        m.schedule(0, sample, AudioMixer.SAMPLE_RATE, 1.0, 1f, 1f);
        float[] out = new float[8 * 2];
        m.render(out, 8);
        assertTrue(peak(out) > 0.4f);
    }

    @Test
    void cancelFutureRemovesOnlyTaggedUnstartedVoices() {
        AudioMixer m = new AudioMixer();
        float[] sample = new float[64];
        java.util.Arrays.fill(sample, 1f);
        m.schedule(50, sample, AudioMixer.SAMPLE_RATE, 1.0, 1f, 1f, 7L, false);  // speaker 7
        m.schedule(50, sample, AudioMixer.SAMPLE_RATE, 1.0, 1f, 1f, 9L, false);  // speaker 9
        m.schedule(100, sample, AudioMixer.SAMPLE_RATE, 1.0, 1f, 1f, 0L, true);  // audition, still future

        assertEquals(1, m.cancelFuture(7L), "only speaker 7's unstarted voices go");

        float[] head = new float[50 * 2];
        m.render(head, 50);  // advance 50 frames: speaker 9's first voice has started
        m.schedule(10, sample, AudioMixer.SAMPLE_RATE, 1.0, 1f, 1f, 9L, false);
        assertEquals(1, m.cancelFuture(9L),
            "the sounding voice survives its stop; only the future one goes");
        assertEquals(1, m.cancelListenerFuture(), "the audition voice is still future and goes");

        float[] tail = new float[10 * 2];
        m.render(tail, 10);
        assertTrue(peak(tail) > 0f, "a voice that had started keeps ringing through the stop");
    }

    @Test
    void pitchRatioSpeedsUpPlayback() {
        AudioMixer m = new AudioMixer();
        float[] sample = ramp(1000);
        m.schedule(0, sample, AudioMixer.SAMPLE_RATE, 2.0, 1f, 1f);
        float[] out = new float[100 * 2];
        m.render(out, 100);
        // 双倍速下 100 帧推进 200 样本：左声道末值 ≈ 200/1000
        assertEquals(200f / 1000f, out[99 * 2], 0.05f);
    }

    @Test
    void sampleRateIsResampled() {
        AudioMixer m = new AudioMixer();
        float[] sample = ramp(441);
        // 半采样率样本以 pitchRatio=1 播放 → 每输出帧走 0.5 样本
        m.schedule(0, sample, AudioMixer.SAMPLE_RATE / 2, 1.0, 1f, 1f);
        float[] out = new float[100 * 2];
        m.render(out, 100);
        assertEquals(50f / 441f, out[99 * 2], 0.03f);
    }

    @Test
    void panGainsSplitLeftRight() {
        AudioMixer m = new AudioMixer();
        float[] sample = new float[64];
        java.util.Arrays.fill(sample, 1f);
        m.schedule(0, sample, AudioMixer.SAMPLE_RATE, 1.0, 1f, 0f);   // 全左
        float[] out = new float[8 * 2];
        m.render(out, 8);
        assertTrue(out[0] > 0.5f, "left channel loud");
        assertEquals(0f, out[1], 1e-6f, "right channel silent");
    }

    @Test
    void voicesMixAdditively() {
        AudioMixer m = new AudioMixer();
        float[] sample = new float[64];
        java.util.Arrays.fill(sample, 0.1f);
        m.schedule(0, sample, AudioMixer.SAMPLE_RATE, 1.0, 1f, 1f);
        m.schedule(0, sample, AudioMixer.SAMPLE_RATE, 1.0, 1f, 1f);
        float[] out = new float[4 * 2];
        m.render(out, 4);
        // 两声部 0.2 和 → 软限幅 ≈ 0.199（小信号近线性区）
        assertEquals(0.2f, out[0], 0.01f, "two voices sum");
    }

    @Test
    void voicesEndAndAreRemoved() {
        AudioMixer m = new AudioMixer();
        float[] sample = new float[32];
        java.util.Arrays.fill(sample, 1f);
        m.schedule(0, sample, AudioMixer.SAMPLE_RATE, 1.0, 1f, 1f);
        assertEquals(1, m.activeVoices());
        float[] out = new float[256 * 2];
        m.render(out, 256);
        assertEquals(0, m.activeVoices(), "finished voices are dropped");
        float[] tail = java.util.Arrays.copyOfRange(out, 64 * 2, out.length);
        assertEquals(0f, peak(tail), 1e-6f, "silence after the sample ends");
    }

    @Test
    void voiceCapStealsQuietest() {
        AudioMixer m = new AudioMixer();
        float[] sample = new float[64];
        java.util.Arrays.fill(sample, 1f);
        // 造满：前 10 个大声部（增益 0.05，合起来 0.55 不触发限幅），其余小声部
        for (int i = 0; i < 10; i++) m.schedule(0, sample, AudioMixer.SAMPLE_RATE, 1.0, 0.05f, 0.05f);
        for (int i = 10; i < AudioMixer.MAX_VOICES; i++) m.schedule(0, sample, AudioMixer.SAMPLE_RATE, 1.0, 0.005f, 0.005f);
        assertEquals(AudioMixer.MAX_VOICES, m.activeVoices());
        // 满员后再来一个大声部 → 剥夺最轻旧声部；若错剥夺大声部峰值会掉到 0.5 以下
        m.schedule(0, sample, AudioMixer.SAMPLE_RATE, 1.0, 0.05f, 0.05f);
        assertEquals(AudioMixer.MAX_VOICES, m.activeVoices(), "cap holds; the quietest voice is stolen");
        float[] out = new float[2 * 2];
        m.render(out, 2);
        assertTrue(peak(out) > 0.52f, "loud voices survive the steal (11×0.05 ≈ 0.55)");
    }

    @Test
    void scheduleAtFrameIsCursorDecoupled() {
        AudioMixer m = new AudioMixer();
        float[] sample = new float[300];
        java.util.Arrays.fill(sample, 1f);
        float[] warm = new float[100 * 2];
        m.render(warm, 100);                       // 游标 → 100
        long target = 150;                         // 目标帧按此时锚点算
        float[] drift = new float[50 * 2];
        m.render(drift, 50);                       // 模拟等锁期间渲染完成：游标 → 150
        m.scheduleAtFrame(target, sample, AudioMixer.SAMPLE_RATE, 1.0, 1f, 1f);
        float[] out = new float[100 * 2];
        m.render(out, 100);                        // 覆盖帧 150–250
        assertTrue(out[0] > 0.5f,
            "voice starts at the absolute target frame, not cursor+delay (which would be frame 200)");
        assertEquals(1, m.activeVoices(), "300-frame sample still sounding after the 100-frame block");
    }

    @Test
    void densityGainStagingFormula() {
        assertEquals(1f, AudioMixer.densityGain(1), 1e-6);
        assertEquals(1f, AudioMixer.densityGain(AudioMixer.DENSITY_REF), 1e-6, "no scaling at or below the reference");
        assertEquals((float) Math.sqrt(24.0 / 100.0), AudioMixer.densityGain(100), 1e-6);
        assertTrue(AudioMixer.densityGain(400) < 0.3f, "big ensembles scale down hard");
    }

    @Test
    void densityGainStagingTamesUncorrelatedEnsembles() {
        AudioMixer m = new AudioMixer();
        var rnd = new java.util.Random(42);
        // 每声部独立随机波形（互不相干 = 真实乐器叠加的统计形态）
        for (int i = 0; i < 200; i++) {
            float[] sample = new float[256];
            for (int j = 0; j < sample.length; j++) sample[j] = rnd.nextBoolean() ? 1f : -1f;
            m.schedule(0, sample, AudioMixer.SAMPLE_RATE, 1.0, 0.02f, 0.02f);
        }
        float[] out = new float[256 * 2];
        m.render(out, 256);
        assertTrue(peak(out) < 0.95f, "staged ensemble stays under the limit without crushing (peak=" + peak(out) + ")");
        assertTrue(m.limitGain() > 0.9f, "the limiter stays out of the way (gain=" + m.limitGain() + ")");
    }

    @Test
    void placementFramesSubtractsArrivalLateness() {
        int rate = AudioMixer.SAMPLE_RATE;
        // 准点：base 1000 + 墙钟增量 2205 + (0.25 − 0.2)s = 1000 + 2205 + 2205
        long onTime = AudioMixer.placementFrames(1000, 2205, 0.25f, 0f, 0.2f);
        assertEquals(1000 + 2205 + Math.round(0.05f * rate), onTime, 2);
        // 迟到 0.4 s：落点提前 0.4s——播出回到乐理位置（不与后续段重叠）
        long late = AudioMixer.placementFrames(1000, 2205, 0.25f, 0.4f, 0.2f);
        assertEquals(onTime - Math.round(0.4f * rate), late, 2, "lateness is subtracted from placement");
    }

    @Test
    void placementFramesKeepsStaggerForOnTimeDispatch() {
        int rate = AudioMixer.SAMPLE_RATE;
        // 起播/跳转后的第一批：delaySeconds < 预播 → 不减预播，按 delay 排帧——
        // 若按稳态公式减预播，这批落点全为负、被混音器钳到同一帧（「开头没了」）。
        // First batch after a start/seek (delaySeconds < pre-roll): no pre-roll subtraction,
        // so the batch keeps its intra-batch stagger instead of clamping onto one frame.
        long first = AudioMixer.placementFrames(1000, 2205, 0.1f, 0f, 2.0f);
        long second = AudioMixer.placementFrames(1000, 2205, 1.7f, 0f, 2.0f);
        assertEquals(1000 + 2205 + Math.round(0.1f * rate), first, 2);
        assertEquals(first + Math.round(1.6f * rate), second, 2,
            "on-time dispatch keeps musical distance between notes");
        // 边界无缝：delay == 预播时两式同值
        long boundary = AudioMixer.placementFrames(1000, 2205, 2.0f, 0f, 2.0f);
        assertEquals(1000 + 2205, boundary, 2);
    }

    @Test
    void waveLutShapesSamples() {
        AudioMixer m = new AudioMixer();
        float[] sample = new float[64];
        for (int i = 0; i < 64; i++) sample[i] = (i % 2 == 0) ? 0.5f : -0.5f; // 双极性 / bipolar
        float[] lut = new float[257];
        for (int i = 0; i <= 256; i++) lut[i] = (i / 256f) * (i / 256f); // y = x²
        m.schedule(0, sample, AudioMixer.SAMPLE_RATE, 1.0, 1f, 1f, 0L, false, lut);
        float[] out = new float[16 * 2];
        m.render(out, 16);
        assertEquals(0.25f, peak(out), 0.02f, "0.5 through the square curve lands at 0.25 (sign preserved)");
        // 负半周保号对称：-0.5 → -0.25
        float min = 0f;
        for (float v : out) min = Math.min(min, v);
        assertEquals(-0.25f, min, 0.02f, "the negative half mirrors through the sign-preserving shape");
    }

    @Test
    void shapingBakesBeforeResampling() {
        // 语义钉（作者口径 2026-10-05）：整形固化在源采样上、先于重采样插值——
        // 变调位置取的是整形点的插值，不是插值点的整形（后者是 0.5625）。
        // Semantic pin (author call 2026-10-05): the shape is baked into the source
        // samples before resampling interpolation - a resampled frame reads the lerp of
        // shaped points, not the shape of the lerp (which would be 0.5625 here).
        AudioMixer m = new AudioMixer();
        float[] sample = {0f, 0.5f, 1f};
        float[] lut = new float[257];
        for (int i = 0; i <= 256; i++) lut[i] = (i / 256f) * (i / 256f); // y = x²
        m.schedule(0, sample, AudioMixer.SAMPLE_RATE, 1.5, 1f, 1f, 0L, false, lut); // step=1.5
        float[] out = new float[2 * 2];
        m.render(out, 2);
        // f=0：pos=0 → 0² = 0；f=1：pos=1.5 → lerp(0.5², 1², 0.5) = 0.625（经软限幅）
        float expected = AudioMixer.softClip(0.625f);
        assertEquals(expected, out[2], 0.005f,
            "resampled frames read the lerp of shaped points (0.625 pre-clip), not sh(lerp) = 0.5625");
        assertNotEquals(AudioMixer.softClip(0.5625f), out[2], 0.01f, "the two semantics must stay distinguishable");
    }

    @Test
    void limiterRecoversAfterLoudChord() {
        AudioMixer m = new AudioMixer();
        float[] sample = new float[64];
        java.util.Arrays.fill(sample, 1f);
        for (int i = 0; i < 20; i++) m.schedule(0, sample, AudioMixer.SAMPLE_RATE, 1.0, 1f, 1f);
        float[] loud = new float[64 * 2];
        m.render(loud, 64);                       // 限幅生效
        assertTrue(peak(loud) <= 1f);
        // 样本已尽 → 静音块中限幅增益按块释放（15%/块 ≈ 300 ms 时间常数）
        float[] tail = new float[64 * 2];
        for (int i = 0; i < 30; i++) m.render(tail, 64);
        assertEquals(0f, peak(tail), 1e-6f);
        m.schedule(0, sample, AudioMixer.SAMPLE_RATE, 1.0, 0.5f, 0.5f);
        float[] quiet = new float[64 * 2];
        m.render(quiet, 64);
        // 释放充分后小信号应接近原增益（不被压扁）
        assertTrue(peak(quiet) > 0.45f, "limiter gain has recovered for quiet content");
    }

    @Test
    void softClipBoundsOutput() {
        assertEquals(1f, AudioMixer.softClip(99f));
        assertEquals(-1f, AudioMixer.softClip(-99f));
        assertEquals(0f, AudioMixer.softClip(0f));
        assertTrue(AudioMixer.softClip(0.5f) < 0.5f && AudioMixer.softClip(0.5f) > 0.4f, "small signals stay near-linear");
    }

    @Test
    void denseChordDoesNotClipHard() {
        AudioMixer m = new AudioMixer();
        float[] sample = new float[64];
        java.util.Arrays.fill(sample, 1f);
        for (int i = 0; i < 40; i++) m.schedule(0, sample, AudioMixer.SAMPLE_RATE, 1.0, 1f, 1f);
        float[] out = new float[4 * 2];
        m.render(out, 4);
        assertTrue(peak(out) <= 1f, "40-voice chord is limited to [-1,1]");
        assertTrue(peak(out) > 0.75f, "and still loud (limit target 0.95, soft-clip tail)");
    }

    @Test
    void distanceGainLinearToRadius() {
        assertEquals(1f, AudioMixer.distanceGain(0, 48), 1e-6f);
        assertEquals(0.5f, AudioMixer.distanceGain(24, 48), 1e-6f);
        assertEquals(0f, AudioMixer.distanceGain(48, 48), 1e-6f);
        assertEquals(0f, AudioMixer.distanceGain(100, 48), 1e-6f);
        // radius ≤ 0 → 默认 48
        assertEquals(0.5f, AudioMixer.distanceGain(24, 0), 1e-6f);
    }

    @Test
    void panGainsEqualPower() {
        float[] lr = new float[2];
        AudioMixer.panGains(0f, lr);
        assertEquals(lr[0], lr[1], 1e-6f, "centre pans equally");
        AudioMixer.panGains(-1f, lr);
        assertEquals(1f, lr[0], 1e-6f);
        assertEquals(0f, lr[1], 1e-6f);
        AudioMixer.panGains(1f, lr);
        assertEquals(0f, lr[0], 1e-6f);
        assertEquals(1f, lr[1], 1e-6f);
        AudioMixer.panGains(0f, lr);
        assertEquals(1f, lr[0] * lr[0] + lr[1] * lr[1], 1e-3f, "equal power (unit vector)");
    }
}
