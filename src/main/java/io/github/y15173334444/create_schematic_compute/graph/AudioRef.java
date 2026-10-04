package io.github.y15173334444.create_schematic_compute.graph;

import java.util.List;

/**
 * <b>类型化音源引用</b>（音频连线上的「传输」载体）——像 UE 的音频/向量引脚，**不是浮点**。
 * <p>A typed audio-source reference carried on audio wires (like UE's audio/vector pin),
 * distinct from a float. Audio pins connect only to audio pins (pin domain).</p>
 * <p>指向一个<b>活音源</b>的本 tick 输出：{@code events} = 传输推进展开得到的音符事件（含声像），
 * {@code gain} = 累计增益链。MUSIC 产出、AMP 改增益、CHANNEL 按声像拆声道、sink（AUDIO_OUT /
 * SPEAKER_PLAY）消费。不可变；{@link #withGain} 返回改增益的副本。</p>
 * <p>多声道：{@code events} 携带声像（panning），{@code CHANNEL} 节点据此拆出各声道引用。</p>
 * <p>{@code stopSignal} = 一次性停止标记（MUSIC 停止/跳转沿产生，事件为空）：预播窗口内已下发
 * 未播的音符因传输状态变化而失效，标记沿管线原样穿透（AMP/CHANNEL/AUDIO_OUT/频段表）到
 * sink，由宿主通知客户端清除该音响排队的未播声部——加宽预播窗口后没有它，停止会有最长
 * 一个窗口的尾巴。/ {@code stopSignal} is a one-shot stop marker (empty events, produced on
 * MUSIC's stop/seek edge): notes already dispatched inside the pre-roll window become stale,
 * and the marker rides the pipeline unchanged (AMP/CHANNEL/AUDIO_OUT/band table) to the sink,
 * whose host tells the client to cancel that speaker's queued unplayed voices — without it a
 * widened pre-roll leaves a tail up to one window long after a stop.</p>
 * <p>{@code waveLut} = 波形整形 LUT（WSHAPE 节点产生，257 点等距网格 x=|采样| 0..1 → y=输出
 * 幅度）：沿管线穿透到 sink，随 {@code NoteEventPacket} 送达客户端混音器逐样本应用。
 * / {@code waveLut} is a waveshaper LUT (produced by WSHAPE nodes; 257-entry even grid,
 * x = |sample| 0..1 → y = output magnitude) riding the pipeline to the sink and on to the
 * client mixer for per-sample application via the note packet.</p>
 */
public record AudioRef(List<NoteEvent> events, float gain, boolean stopSignal, float[] waveLut) {

    /** 兼容构造：普通音源引用（非停止标记、无波形整形）。 / compat ctor: plain source ref. */
    public AudioRef(List<NoteEvent> events, float gain) { this(events, gain, false, null); }

    /** 兼容构造：带停止标记。 / compat ctor: with stop marker. */
    public AudioRef(List<NoteEvent> events, float gain, boolean stopSignal) { this(events, gain, stopSignal, null); }

    /** 空音源（无事件）。 */
    public static final AudioRef EMPTY = new AudioRef(List.of(), 1f, false, null);

    /** 停止标记（一次性；事件恒空）。 / the stop marker (one-shot; events always empty). */
    public static final AudioRef STOP = new AudioRef(List.of(), 1f, true, null);

    /** 返回替换增益后的副本（保留事件、停止标记与 LUT）。 */
    public AudioRef withGain(float newGain) {
        return new AudioRef(events, newGain, stopSignal, waveLut);
    }

    /** 返回替换波形整形 LUT 后的副本。 / a copy with a new waveshaper LUT. */
    public AudioRef withWaveLut(float[] lut) {
        return new AudioRef(events, gain, stopSignal, lut);
    }

    /** 是否为空（无事件）。 */
    public boolean isEmpty() {
        return events == null || events.isEmpty();
    }
}
