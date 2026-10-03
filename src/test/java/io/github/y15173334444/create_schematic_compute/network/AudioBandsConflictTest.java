package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.graph.AudioRef;
import io.github.y15173334444.create_schematic_compute.graph.NoteEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AudioBands 冲突纪律测试（R1-2）：同名不同 owner 在新鲜窗内相撞 → 后到者拒绝写入、
 * 双方旗标同亮、消费读取整体静默；一方停发越窗自愈；原所有者消失越窗后后到者接管；
 * 无 owner 形态不参与冲突判定。
 * Band-conflict discipline tests (R1-2): a same-name different-owner collision inside the
 * freshness window rejects the newcomer, flags BOTH publishers and silences the band for
 * consumers; the band heals once either side stops publishing; a vanished owner is taken
 * over; the ownerless publish form takes no part in conflict handling.
 */
class AudioBandsConflictTest {

    private static AudioRef ref(float gain) {
        return new AudioRef(List.of(new NoteEvent(0, 45, 100, 100, 0, 1f, 0.1f)), gain);
    }

    @BeforeEach
    void reset() {
        AudioBands.clear();
    }

    @AfterEach
    void cleanup() {
        // 全局静态表跨类共享：owner/冲突残留会污染后续测试类的发布（相对 tick 0 皆「新鲜」）
        // The global static tables leak across classes; leftovers poison the next class.
        AudioBands.clear();
    }

    @Test
    void collisionRejectsNewcomerAndSilencesBand() {
        assertTrue(AudioBands.publish("B", ref(1f), 10L, "hostA#1"), "first publisher owns");
        // 同名不同 owner：后到者被拒（不拥有、不写入）/ same name, different owner → rejected
        assertFalse(AudioBands.publish("B", ref(2f), 10L, "hostB#1"), "newcomer rejected");
        // 冲突存续期消费读取整体静默 / consumers get silence while the conflict is active
        assertSame(AudioRef.EMPTY, AudioBands.get("B", 10L), "band silent during conflict");
        // 原所有者也在冲突期报冲突（双方旗标同亮）/ the original owner flags too
        assertFalse(AudioBands.publish("B", ref(1f), 11L, "hostA#1"), "original owner flags during conflict");
        assertSame(AudioRef.EMPTY, AudioBands.get("B", 11L), "still silent on the next tick");
    }

    @Test
    void conflictHealsOnceIntruderStops() {
        assertTrue(AudioBands.publish("B", ref(1f), 10L, "hostA#1"));
        assertFalse(AudioBands.publish("B", ref(2f), 10L, "hostB#1"));
        assertFalse(AudioBands.publish("B", ref(1f), 11L, "hostA#1"), "evidence from tick 10 still in window");
        assertSame(AudioRef.EMPTY, AudioBands.get("B", 11L), "still silent while evidence is in window");
        // B 停发：证据 t10 生效条件 c >= t-1 只覆盖到 t11，t12 起自愈
        // B stopped: evidence t10 covers t <= 11 (c >= t-1) and heals from t12
        assertTrue(AudioBands.publish("B", ref(1f), 12L, "hostA#1"), "healed once the evidence leaves the window");
        assertEquals(1f, AudioBands.get("B", 12L).gain(), 0f, "the surviving owner's data serves");
    }

    @Test
    void newcomerTakesOverAfterOriginalVanishes() {
        assertTrue(AudioBands.publish("B", ref(1f), 10L, "hostA#1"));
        assertTrue(AudioBands.publish("B", ref(1f), 11L, "hostA#1"));
        // A 消失越窗后 B 接管（缺席不是定义，先到者拥有——接管语义与 SignalBus 同款）
        // After A's stamp goes stale B takes over (absence is not a definition)
        assertTrue(AudioBands.publish("B", ref(2f), 20L, "hostB#1"), "takeover accepted");
        assertEquals(2f, AudioBands.get("B", 20L).gain(), 0f, "the new owner's data serves");
    }

    @Test
    void sameOwnerRepublishStaysOwned() {
        assertTrue(AudioBands.publish("B", ref(1f), 10L, "hostA#1"));
        assertTrue(AudioBands.publish("B", ref(1f), 11L, "hostA#1"), "same owner never conflicts with itself");
        assertTrue(AudioBands.publish("B", ref(1f), 12L, "hostA#1"));
        assertNotSame(AudioRef.EMPTY, AudioBands.get("B", 12L), "band serves normally");
    }

    @Test
    void cursorReadsStayExactlyOnceAcrossAConflict() {
        assertTrue(AudioBands.publish("B", ref(1f), 10L, "hostA#1"));
        assertFalse(AudioBands.publish("B", ref(2f), 10L, "hostB#1"));
        // 冲突期游标读到静默，且游标不前移 / the cursor reads silence and does not advance
        assertSame(AudioRef.EMPTY, AudioBands.get("c1", "B", 10L), "cursor gets silence during conflict");
        // 自愈后的同批发布照常被消费一次 / after healing the same publish delivers exactly once
        assertTrue(AudioBands.publish("B", ref(1f), 13L, "hostA#1"), "healed");
        assertNotSame(AudioRef.EMPTY, AudioBands.get("c1", "B", 13L), "first read delivers");
        assertSame(AudioRef.EMPTY, AudioBands.get("c1", "B", 14L), "no replay on the next tick");
    }

    @Test
    void ownerlessPublishFormTakesNoPartInConflicts() {
        assertTrue(AudioBands.publish("B", ref(1f), 10L, "hostA#1"));
        // 无 owner 形态永远接受（测试/遗留调用），不产生冲突证据 / ownerless form always accepted
        AudioBands.publish("B", ref(3f), 11L);
        assertEquals(3f, AudioBands.get("B", 11L).gain(), 0f, "ownerless publish overwrites");
    }

    @Test
    void emptyBandNameReportsUnowned() {
        assertFalse(AudioBands.publish("", ref(1f), 10L, "hostA#1"), "empty band name owns nothing");
        assertFalse(AudioBands.publish(null, ref(1f), 10L, "hostA#1"));
    }
}
