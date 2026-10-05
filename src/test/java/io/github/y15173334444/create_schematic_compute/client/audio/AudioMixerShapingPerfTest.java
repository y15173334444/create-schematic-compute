package io.github.y15173334444.create_schematic_compute.client.audio;

import io.github.y15173334444.create_schematic_compute.graph.AudioCurve;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 整形混音性能基准（性能回归钉）：800 声部（实测 STALL 高峰规模）、3528 帧/块（
 * {@code CscAudioStream.BUFFER_FRAMES} = 80 ms 量子）下，无整形 vs 带 LUT 整形的
 * 每块渲染耗时。补泵架构要求混音器跟得上实时（80 ms 音频的渲染必须远小于 80 ms），
 * 且整形增量要小——实机症状是「后处理播放 FPS 118→70」，成本必须在混音内环里收掉。
 * <p>Shaped-mixing perf pin (regression): render cost per 80 ms block at 800 voices
 * (the observed STALL peak) without and with a waveshaper LUT. The queue-pump
 * architecture assumes the mixer keeps up with real time, and the shaping increment must
 * stay marginal — the in-game symptom (118 → 70 FPS while playing shaped) means the cost
 * has to come out of the mix inner loop.</p>
 */
class AudioMixerShapingPerfTest {

    private static final int VOICES = 800;
    private static final int BLOCK = CscAudioStream.BUFFER_FRAMES;   // 3528 = 80 ms
    private static final int WARMUP = 3, MEASURE = 10;

    /** 1 秒正弦样本（音符盒采样的现实长度），幅度 0.5。 */
    private static float[] sample() {
        float[] s = new float[AudioMixer.SAMPLE_RATE];
        for (int i = 0; i < s.length; i++)
            s[i] = 0.5f * (float) Math.sin(2 * Math.PI * 220 * i / AudioMixer.SAMPLE_RATE);
        return s;
    }

    private static AudioMixer build(float[] lut) {
        AudioMixer m = new AudioMixer();
        float[] s = sample();
        for (int i = 0; i < VOICES; i++)
            m.scheduleAtFrame(0, s, AudioMixer.SAMPLE_RATE, 1.0, 0.3f, 0.3f, i, false, lut);
        return m;
    }

    /** 渲染 MEASURE 块，返回每块平均毫秒。 */
    private static double msPerBlock(AudioMixer m) {
        float[] out = new float[BLOCK * 2];
        for (int i = 0; i < WARMUP; i++) m.render(out, BLOCK);
        long t0 = System.nanoTime();
        for (int i = 0; i < MEASURE; i++) m.render(out, BLOCK);
        return (System.nanoTime() - t0) / 1e6 / MEASURE;
    }

    @Test
    @DisplayName("整形内环成本受控：渲染与无整形同价 / shaped rendering costs plain-level time")
    void shapedRenderStaysCheap() {
        float[] lut = AudioCurve.buildLut(new float[]{0f, 0.5f, 1f}, new float[]{0f, 0.6f, 1.2f});
        double plain = msPerBlock(build(null));
        double shaped = msPerBlock(build(lut));
        System.out.printf("[perf] voices=%d block=%d frames | plain=%.2f ms/block, shaped=%.2f ms/block (%.2fx)%n",
            VOICES, BLOCK, plain, shaped, shaped / Math.max(plain, 1e-9));

        // 整形在起音时烘进样本（shapedSample），渲染内环永远是纯插值——带整形与无整形
        // 应当同价。3× 的回归（56 ms/块 = 实机 FPS 118→70 的根因）必须被钉住。
        // Shaping bakes into the sample at voice start (shapedSample), so the render inner
        // loop is always plain interp — shaped and plain must cost the same. The 3x
        // regression (56 ms/block, the 118→70 FPS root cause) must stay pinned.
        assertTrue(shaped < 80.0 * 0.35,
            () -> String.format("shaped render must stay under 35%% of real time, took %.1f ms per 80 ms block", shaped));
        assertTrue(shaped <= plain * 1.25 + 1.0,
            () -> String.format("shaping must cost plain-level time (%.2f ms vs %.2f ms)", shaped, plain));
    }

    @Test
    @DisplayName("整形缓存命中：调度不做逐音符重烘 / the shape cache keeps scheduling off per-note rebakes")
    void scheduleCacheHits() {
        float[] lut = AudioCurve.buildLut(new float[]{0f, 0.5f, 1f}, new float[]{0f, 0.6f, 1.2f});
        long t0 = System.nanoTime();
        build(lut);   // 800 声部共享同一 (样本, LUT) → 一次烘焙 / one bake for the shared pair
        double ms = (System.nanoTime() - t0) / 1e6;
        System.out.printf("[perf] schedule %d shaped voices = %.2f ms%n", VOICES, ms);
        assertTrue(ms < 30.0,
            () -> String.format("shared (sample, LUT) must bake once; scheduling took %.1f ms (per-note rebake would be ~100+ ms)", ms));
    }
}
