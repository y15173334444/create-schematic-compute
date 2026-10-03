package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.graph.AudioRef;
import io.github.y15173334444.create_schematic_compute.graph.NoteEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AudioBands 消费者游标（exactly-once）测试：跨 BE tick 顺序翻转时同批发布防重播、
 * 不同消费者互不影响、迟到首读照常接收。
 * Consumer-cursor (exactly-once) tests: tick-order flips must not replay a publish to the
 * same consumer; consumers are independent; a late first read still delivers.
 */
class AudioBandsCursorTest {

    private static AudioRef ref() {
        return new AudioRef(List.of(new NoteEvent(0, 45, 100, 100, 0, 1f, 0.1f)), 1f);
    }

    @BeforeEach
    void reset() {
        AudioBands.clear();
    }

    @AfterEach
    void cleanup() {
        // 全局静态表跨类共享：stamp 残留会让后续测试类相对 tick 0 的发布判为陈旧/冲突
        // The global static tables leak across classes; stale stamps poison the next class.
        AudioBands.clear();
    }

    @Test
    void replayIsRefusedWithinFreshnessWindow() {
        AudioBands.publish("B", ref(), 10L);
        assertNotSame(AudioRef.EMPTY, AudioBands.get("c1", "B", 10L), "first read delivers");
        // 顺序翻转：下一 tick 仍能读到 stamp=10（新鲜窗内）——同批必须拒收
        assertSame(AudioRef.EMPTY, AudioBands.get("c1", "B", 11L), "the same publish must not replay");
        // 更新一批 → 照常接收
        AudioBands.publish("B", ref(), 11L);
        assertNotSame(AudioRef.EMPTY, AudioBands.get("c1", "B", 11L), "a newer publish delivers");
    }

    @Test
    void lateFirstReadStillDelivers() {
        AudioBands.publish("B", ref(), 10L);
        // 消费者在 T+1 才首次读到（BE 顺序固定为读者在后）→ 不算重播
        assertNotSame(AudioRef.EMPTY, AudioBands.get("c2", "B", 11L), "a late first read is not a replay");
    }

    @Test
    void consumersAreIndependent() {
        AudioBands.publish("B", ref(), 10L);
        assertNotSame(AudioRef.EMPTY, AudioBands.get("c1", "B", 10L));
        assertNotSame(AudioRef.EMPTY, AudioBands.get("c2", "B", 10L), "another consumer reads it once too");
        assertSame(AudioRef.EMPTY, AudioBands.get("c2", "B", 10L), "and only once");
    }

    @Test
    void staleExpiryStillWorks() {
        AudioBands.publish("B", ref(), 10L);
        assertSame(AudioRef.EMPTY, AudioBands.get("c1", "B", 13L), "older than the freshness window reads empty");
    }
}
