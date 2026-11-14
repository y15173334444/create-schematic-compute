package io.github.y15173334444.create_schematic_compute.client.audio;

import io.github.y15173334444.create_schematic_compute.graph.AudioCurve;
import org.junit.jupiter.api.DisplayName;

import java.util.Arrays;
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
 * <p>计时余量按共享 CI runner 校准：窗口级抢占是「某侧整窗变慢」，交错采样 + 三轮
 * 中位把它当离群轮丢掉，断言牙口仍咬住 3× 回归信号（2026-11-14 实证：1.25× 断言在
 * 劣化 runner 窗口连挂两次、失败组合漂移，重跑全绿）。
 * <p>Timing headroom is calibrated for shared CI runners: window-scale steal
 * slows one side's whole measurement window, so rounds are interleaved and the
 * median of three rounds drops a stolen window as an outlier — while the
 * assertions keep biting the 3x regression signal (2026-11-14: the 1.25x pin
 * failed twice on a degraded runner window with a drifting failure set, green
 * on rerun).</p>
 */
class AudioMixerShapingPerfTest {

    private static final int VOICES = 800;
    private static final int BLOCK = CscAudioStream.BUFFER_FRAMES;   // 3528 = 80 ms
    private static final int WARMUP = 3, MEASURE = 10, ROUNDS = 3;

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

    /** 渲染 MEASURE 块，返回每块平均毫秒（单轮）。 / Renders MEASURE blocks, returns the per-block mean in ms (one round). */
    private static double msPerBlockOnce(AudioMixer m) {
        float[] out = new float[BLOCK * 2];
        for (int i = 0; i < WARMUP; i++) m.render(out, BLOCK);
        long t0 = System.nanoTime();
        for (int i = 0; i < MEASURE; i++) m.render(out, BLOCK);
        return (System.nanoTime() - t0) / 1e6 / MEASURE;
    }

    /** 轮中位（拷贝后排序——不污染原数组，打印保持轮次原序）。 / Round median (sorts a copy — the printed rounds keep their true order). */
    private static double medianOf(double[] v) {
        double[] c = v.clone();
        Arrays.sort(c);
        return c[c.length / 2];
    }

    @Test
    @DisplayName("整形内环成本受控：渲染与无整形同价 / shaped rendering costs plain-level time")
    void shapedRenderStaysCheap() {
        float[] lut = AudioCurve.buildLut(new float[]{0f, 0.5f, 1f}, new float[]{0f, 0.6f, 1.2f});
        double[] plainRounds = new double[ROUNDS], shapedRounds = new double[ROUNDS];
        for (int r = 0; r < ROUNDS; r++) {
            // 每轮重建混音器：800 声部的 1 s 样本在 13 块（≈1.04 s）内播完即被移除——
            // 复用混音器的第二三轮测的是静音成本，中位会挑中它 = 钉子变空气。
            // Rebuild per round: the 800 one-second voices are exhausted and removed
            // within 13 blocks (~1.04 s), so rounds 2+ on a reused mixer measure
            // silence — and the median would pick exactly that (the pin turns to air).
            AudioMixer plainMixer = build(null);
            AudioMixer shapedMixer = build(lut);
            plainRounds[r] = msPerBlockOnce(plainMixer);   // 交错 = plain/shaped 同窗采样 / interleaved = same-window sampling
            shapedRounds[r] = msPerBlockOnce(shapedMixer);
        }
        double plain = medianOf(plainRounds), shaped = medianOf(shapedRounds);
        System.out.printf("[perf] voices=%d block=%d frames | plain=%.2f ms/block %s, shaped=%.2f ms/block %s (%.2fx)%n",
            VOICES, BLOCK, plain, Arrays.toString(plainRounds), shaped, Arrays.toString(shapedRounds),
            shaped / Math.max(plain, 1e-9));

        // 整形在起音时烘进样本（shapedSample），渲染内环永远是纯插值——带整形与无整形
        // 应当同价。回归信号 = 3×（56 ms/块 = 实机 FPS 118→70 的根因），牙口必须咬得住它；
        // 余量按共享 runner 的窗口级抢占放宽：绝对帽在慢机上由 plain 自校准（35% 实时锚
        // 只约束快机），比值钉 1.75×+2ms 仍远离 3× 回归线。
        // Shaping bakes into the sample at voice start (shapedSample), so the render inner
        // loop is always plain interp — shaped and plain must cost the same. The regression
        // signal is 3x (56 ms/block, the 118→70 FPS root cause) and the teeth must keep
        // biting it; headroom is widened for window-scale runner steal: the absolute cap
        // self-calibrates to the host via plain (the 35%-of-real-time anchor only binds on
        // fast hosts), and the ratio pin at 1.75x + 2 ms stays far from the 3x line.
        assertTrue(shaped <= Math.max(80.0 * 0.35, plain * 2.0),
            () -> String.format("shaped render must stay under 35%% of real time (or 2x plain on a slow host), took %.1f ms per 80 ms block", shaped));
        assertTrue(shaped <= plain * 1.75 + 2.0,
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
        // 60 ms = 慢机 2× 余量；逐音符重烘信号（~100+ ms 且随主机同比例缩放）仍在牙下。
        // 60 ms = 2x headroom for slow hosts; the per-note-rebake signal (~100+ ms,
        // scaling with the host) stays under the teeth.
        assertTrue(ms < 60.0,
            () -> String.format("shared (sample, LUT) must bake once; scheduling took %.1f ms (per-note rebake would be ~100+ ms)", ms));
    }
}
