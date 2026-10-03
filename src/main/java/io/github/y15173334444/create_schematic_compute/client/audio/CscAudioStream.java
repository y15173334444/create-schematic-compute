package io.github.y15173334444.create_schematic_compute.client.audio;

import net.minecraft.client.sounds.AudioStream;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 引擎输出流：把 {@link AudioMixer} 的渲染结果落成立体声 16-bit LE PCM，经
 * {@code Channel.attachBufferStream} 喂给 OpenAL。
 * <p>Engine output stream: renders {@link AudioMixer} into stereo 16-bit LE PCM and feeds
 * OpenAL through {@code Channel.attachBufferStream}.</p>
 *
 * <p><b>小缓冲回填</b>：{@code read} 按 {@link #BUFFER_FRAMES}（80 ms）粒度吐字节，即使通道要求
 * 1 秒量——预填队列因此只有 4×80 ms（{@code Channel} 恒预填 4 块），音符定时时序误差落在
 * 既有 ±1 游戏刻口径内。调用方（声音引擎线程）与调度方（渲染线程）的并发由
 * {@link AudioMixer} 的内部锁收口。
 * <p><b>Small refill chunks</b>: {@code read} yields {@link #BUFFER_FRAMES} (80 ms) chunks even
 * when the channel requests a full second — the pre-fill queue is only 4×80 ms (the channel
 * always pre-fills 4 buffers), keeping note timing within the documented ±1 game-tick
 * precision. Concurrency between the sound-engine thread (reads) and the render thread
 * (scheduling) is serialised inside {@link AudioMixer}.</p>
 */
public final class CscAudioStream implements AudioStream {

    /** 回填粒度：80 ms。4 块预填 ≈ 320 ms 输出余量——进程级停顿（GC，实测 70–250 ms）会冻结
     *  渲染与到达，50 ms 粒度的 200 ms 余量曾被抽到临界（rGapMax 168 ms 实测）；80 ms 粒度
     *  可扛 300 ms 级停顿。恒定延迟由预播（0.2 s）对冲，净时延不随余量增长。
     *  <p>Refill quanta: 80 ms. 4-buffer pre-fill ≈ 320 ms of cushion — process hitches (GC,
     *  measured 70–250 ms) freeze both render and arrival; the 50 ms quanta's 200 ms cushion
     *  was nearly drained (rGapMax 168 ms observed). Constant latency is offset by the
     *  0.2 s pre-roll, so perceived latency does not grow with the cushion.</p> */
    public static final int BUFFER_FRAMES = 3528;

    private final AudioMixer mixer;
    private final AudioFormat format =
        new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, AudioMixer.SAMPLE_RATE, 16, 2, 4, AudioMixer.SAMPLE_RATE, false);
    private final float[] renderBuf = new float[BUFFER_FRAMES * 2];

    public CscAudioStream(AudioMixer mixer) {
        this.mixer = mixer;
    }

    @Override
    public AudioFormat getFormat() {
        return format;
    }

    @Override
    public ByteBuffer read(int size) {
        int frames = Math.min(BUFFER_FRAMES, Math.max(1, size / 4));
        float[] block = frames == BUFFER_FRAMES ? renderBuf : new float[frames * 2];
        long t0 = System.nanoTime();
        mixer.render(block, frames);
        // 更新墙钟锚点（调度端把「目标时刻」折算进渲染游标坐标，防游标停滞期间音符塌缩）
        CscAudioEngine.onFramesRendered(mixer.renderedFrames());
        // 时间线诊断：渲染耗时/间隔/声部峰值 + 限幅抽吸采样（「推迟/挤堆」定位用）
        CscAudioEngine.onRenderDiag(System.nanoTime() - t0, mixer.activeVoices(), System.nanoTime());
        CscAudioEngine.onLimiterDiag(mixer.limitGain());
        // 必须是 direct buffer：LWJGL 的 alBufferData 取 ByteBuffer 地址喂 OpenAL，
        // 堆缓冲地址为 0 → 原生空指针崩溃（实测 hs_err 立案）。原版流式路径同口径
        // （BufferUtils.createByteBuffer）。
        // Must be direct: LWJGL feeds the buffer address to OpenAL's alBufferData; a heap
        // buffer's address is 0 → native null-deref crash (pinned by hs_err). Vanilla's own
        // streaming path uses BufferUtils.createByteBuffer for the same reason.
        ByteBuffer out = org.lwjgl.BufferUtils.createByteBuffer(frames * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < frames * 2; i++) {
            float x = Math.max(-1f, Math.min(1f, block[i]));
            out.putShort((short) (x * 32767f));
        }
        out.flip();
        return out;
    }

    @Override
    public void close() throws IOException {
        // 无外部资源；通道销毁即弃用 / nothing external; the channel drop disposes us
    }
}
