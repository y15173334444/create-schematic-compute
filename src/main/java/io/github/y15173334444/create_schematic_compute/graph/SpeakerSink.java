package io.github.y15173334444.create_schematic_compute.graph;

import java.util.List;

/**
 * 播放下沉（SPEAKER_PLAY）：宿主音响 BE 在重建求值器后经 {@code setSpeakerSink} 注入；
 * {@code SPEAKER_PLAY} 求值把音源交给宿主，在音响坐标发声（R1-3/R1-D）。
 * <p>Speaker playback sink: injected by the hosting speaker BE after evaluator rebuilds;
 * {@code SPEAKER_PLAY} hands the source to the host to play at the speaker's position.</p>
 */
public interface SpeakerSink {
    /** 播放本 tick 音源（事件 + 累计增益）；宿主在其世界坐标发声。 */
    void play(List<NoteEvent> events, float gain);
}
