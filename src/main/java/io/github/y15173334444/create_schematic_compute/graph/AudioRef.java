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
 */
public record AudioRef(List<NoteEvent> events, float gain) {

    /** 空音源（无事件）。 */
    public static final AudioRef EMPTY = new AudioRef(List.of(), 1f);

    /** 返回替换增益后的副本（保留事件）。 */
    public AudioRef withGain(float newGain) {
        return new AudioRef(events, newGain);
    }

    /** 是否为空（无事件）。 */
    public boolean isEmpty() {
        return events == null || events.isEmpty();
    }
}
