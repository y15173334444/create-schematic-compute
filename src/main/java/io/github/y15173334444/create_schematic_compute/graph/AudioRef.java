package io.github.y15173334444.create_schematic_compute.graph;

import java.util.ArrayList;
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
 * <p>{@code waveCurves} = 波形整形曲线链（WSHAPE 节点产生，按应用序追加；恒等曲线旁路不入链）：
 * 控制点对沿管线穿透到 sink，随 {@code NoteEventPacket} 送达客户端，客户端以 float 精度烘
 * LUT（零量化失真）、起音时保号整形固化进采样（整形先于重采样插值）。/ {@code waveCurves} is
 * the waveshaper curve chain (appended by WSHAPE nodes in application order; identity curves
 * bypass and never enter): control-point pairs ride the pipeline to the sink and on through
 * {@code NoteEventPacket} to the client, which bakes the LUT at float precision (zero
 * quantization) and bakes the sign-preserving shape into the sample at voice start (shaping
 * precedes resampling interpolation).</p>
 */
public record AudioRef(List<NoteEvent> events, float gain, boolean stopSignal,
                       List<AudioCurve.Curve> waveCurves) {

    /** 兼容构造：普通音源引用（非停止标记、无波形整形）。 / compat ctor: plain source ref. */
    public AudioRef(List<NoteEvent> events, float gain) { this(events, gain, false, List.of()); }

    /** 兼容构造：带停止标记。 / compat ctor: with stop marker. */
    public AudioRef(List<NoteEvent> events, float gain, boolean stopSignal) { this(events, gain, stopSignal, List.of()); }

    /** 空音源（无事件）。 */
    public static final AudioRef EMPTY = new AudioRef(List.of(), 1f, false, List.of());

    /** 停止标记（一次性；事件恒空）。 / the stop marker (one-shot; events always empty). */
    public static final AudioRef STOP = new AudioRef(List.of(), 1f, true, List.of());

    /** 返回替换增益后的副本（保留事件、停止标记与整形曲线链）。 */
    public AudioRef withGain(float newGain) {
        return new AudioRef(events, newGain, stopSignal, waveCurves);
    }

    /** 返回追加一条整形曲线后的副本（链尾 = 最后作用；多 WSHAPE 按节点序入链）。
     *  / a copy with one shaping curve appended (tail = applied last; WSHAPE nodes
     *  append in node order). */
    public AudioRef withWaveCurve(AudioCurve.Curve curve) {
        var chain = new ArrayList<AudioCurve.Curve>(waveCurves.size() + 1);
        chain.addAll(waveCurves);
        chain.add(curve);
        return new AudioRef(events, gain, stopSignal, List.copyOf(chain));
    }

    /** 是否为空（无事件）。 */
    public boolean isEmpty() {
        return events == null || events.isEmpty();
    }
}
