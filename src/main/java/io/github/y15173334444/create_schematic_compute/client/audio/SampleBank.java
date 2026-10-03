package io.github.y15173334444.create_schematic_compute.client.audio;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.JOrbisAudioStream;
import net.minecraft.resources.ResourceLocation;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 音色采样库：原版音符盒 16 音色的 PCM 解码缓存（运行时从原版资源读取——**引用 ≠ 再分发**，
 * 本模组 jar 不含任何采样资产，plan D13 法务口径）。样本落成单声道浮点数组供
 * {@link AudioMixer} 重采样变调。
 * <p>Sample bank: PCM decode cache for the 16 vanilla note-block timbres, read from vanilla
 * resources at runtime (<b>reference ≠ redistribute</b> — the mod jar ships no samples, the
 * plan D13 legal stance). Samples are downmixed to mono float arrays for
 * {@link AudioMixer}'s pitch resampling.</p>
 * <p>惰性解码 + 缓存：首次使用某音色时同步解码（单文件几毫秒），之后 O(1) 取用。
 * 解码失败返回 null（引擎侧跳过该音符），绝不抛穿到调用方。</p>
 */
public final class SampleBank {

    /**
     * 16 原版音色，**按 NBS 格式乐器索引序**（0=harp, 1=bass(双低音), 2=basedrum(底鼓),
     * 3=snare, 4=hat, 5=guitar, 6=flute, 7=bell, …，Note Block Studio 官方
     * dat_instrument 口径）——注意与原版 NoteBlockInstrument 枚举序不同，索引错位会让
     * 1–7 号乐器放错音色。
     * <p>The 16 vanilla timbres in <b>NBS format instrument order</b> (per Note Block Studio's
     * own dat_instrument) — note this differs from the vanilla enum order; a mismatch plays
     * the wrong timbre for instruments 1–7.</p>
     */
    private static final String[] INSTRUMENTS = {
        "harp", "bass", "basedrum", "snare", "hat", "guitar", "flute", "bell",
        "chime", "xylophone", "iron_xylophone", "cow_bell", "didgeridoo", "bit", "banjo", "pling"
    };

    private record Decoded(float[] mono, int rate) {}

    /** 解码失败也缓存空标记（防每音符重试 + 日志刷屏）。 */
    private static final Decoded FAILED = new Decoded(new float[0], AudioMixer.SAMPLE_RATE);

    private static final ConcurrentHashMap<Integer, Decoded> CACHE = new ConcurrentHashMap<>();

    private SampleBank() {}

    /** 取某音色的单声道样本（-1..1）；失败返回 null。 */
    public static float[] sample(int instrument) {
        Decoded d = decode(instrument);
        return d == null || d.mono().length == 0 ? null : d.mono();
    }

    /** 取某音色的原始采样率；失败返回 {@link AudioMixer#SAMPLE_RATE}。 */
    public static int rate(int instrument) {
        Decoded d = decode(instrument);
        return d == null ? AudioMixer.SAMPLE_RATE : d.rate();
    }

    private static Decoded decode(int instrument) {
        if (instrument < 0 || instrument >= INSTRUMENTS.length) return null;
        Decoded d = CACHE.computeIfAbsent(instrument, SampleBank::load);
        return d == null ? FAILED : d;
    }

    /**
     * 解码一个音色。文件路径不硬编码——走原版自己的解析链：
     * 声音事件 {@code block.note_block.<name>} → {@code Sound.getPath()} → 资源流，
     * 路径/命名随版本变化免疫（引用 ≠ 再分发：读原版资源，jar 不含采样）。
     */
    private static Decoded load(int instrument) {
        try {
            var mc = Minecraft.getInstance();
            ResourceLocation event = ResourceLocation.fromNamespaceAndPath(
                "minecraft", "block.note_block." + INSTRUMENTS[instrument]);
            var weighed = mc.getSoundManager().getSoundEvent(event);
            var sound = weighed == null ? null : weighed.getSound(net.minecraft.util.RandomSource.create());
            if (sound == null) {
                SchematicCompute.LOGGER.warn("SampleBank: no sound event {}", event);
                return FAILED;
            }
            ResourceLocation file = sound.getPath();
            try (InputStream in = mc.getResourceManager().open(file);
                 JOrbisAudioStream stream = new JOrbisAudioStream(in)) {
                int channels = Math.max(1, stream.getFormat().getChannels());
                int rate = (int) stream.getFormat().getSampleRate();
                ByteBuffer pcm = stream.readAll().order(ByteOrder.LITTLE_ENDIAN);
                int frames = pcm.remaining() / (2 * channels);
                float[] mono = new float[frames];
                for (int f = 0; f < frames; f++) {
                    float sum = 0f;
                    for (int c = 0; c < channels; c++) {
                        sum += pcm.getShort() / 32768f;
                    }
                    mono[f] = sum / channels;
                }
                // 保留原始电平——不做峰值归一化：乐器间的录音响度差（低音鼓 vs 铃）是音乐
                // 平衡的一部分；归一化会抹平相对响度、抬高整体电平反而更常撞限幅器（动态变差）。
                // NBS 同样保留原样电平，混音余量由限幅器兜底（AudioMixer 块级包络限幅）。
                // Keep raw levels — no peak normalisation: the recording-level differences between
                // instruments are part of the musical balance; normalising flattens relative
                // loudness and, by raising quiet timbres, makes the limiter engage more often
                // (worse dynamics). NBS likewise keeps raw levels; the limiter covers headroom.
                return new Decoded(mono, rate > 0 ? rate : AudioMixer.SAMPLE_RATE);
            }
        } catch (Exception e) {
            SchematicCompute.LOGGER.warn("SampleBank: failed to decode {}: {}", INSTRUMENTS[instrument], e.toString());
            return FAILED;
        }
    }
}
