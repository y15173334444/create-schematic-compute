package io.github.y15173334444.create_schematic_compute.client.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link AudioTimelineDiag} 时间线诊断测试：漂移直方图分桶、钳制计数、报告内容。
 * Timeline-diagnostic tests: drift histogram bucketing, clamp counting, report contents.
 */
class AudioTimelineDiagTest {

    private static long ms(double v) { return (long) (v * 1_000_000); }

    @Test
    void driftBucketsAndClampCounting() {
        AudioTimelineDiag d = new AudioTimelineDiag();
        long t0 = System.nanoTime();
        d.onSchedule(100, 100, t0);                  // 漂移 0
        d.onSchedule(200, 200 + ms(3), t0 + 1);      // +3 ms → <=5 桶
        d.onSchedule(300, 300 + ms(40), t0 + 2);     // +40 ms → <=50 桶 + 计钳制
        d.onSchedule(400, 400 - ms(2), t0 + 3);      // 提前 2 ms → <=0 桶，不计钳制

        assertEquals(4, d.schedCount());
        assertEquals(2, d.clampCount(), "any positive drift is a late placement (+3 ms and +40 ms)");
        long[] hist = d.driftHistSnapshot();
        assertEquals(2, hist[0], "<=0 bucket holds zero-drift and negative-drift");
        assertEquals(1, hist[1], "<=5 bucket");
        assertEquals(1, hist[3], "<=50 bucket");
    }

    @Test
    void reportRendersAfterIntervalAndResets() {
        AudioTimelineDiag d = new AudioTimelineDiag();
        long t0 = System.nanoTime();
        d.onSchedule(100, 100 + ms(10), t0);
        d.onRender(ms(2), 42, t0 + ms(1));
        assertNull(d.reportIfDue(t0 + ms(100)), "not due before the 5 s window");

        String rep = d.reportIfDue(t0 + ms(5100));
        assertNotNull(rep);
        assertTrue(rep.contains("[TimelineDiag]"), "tagged for log grepping");
        assertTrue(rep.contains("sched=1"), "scheduling count");
        assertTrue(rep.contains("clamp=1"), "clamp count");
        assertTrue(rep.contains("voices=42"), "voice peak");
        assertTrue(rep.contains("hist:"), "drift histogram present");

        assertEquals(0, d.schedCount(), "window resets after the report");
        assertNull(d.reportIfDue(t0 + ms(10300)), "idle window reports nothing");
    }

    @Test
    void arrivalGapTracked() {
        AudioTimelineDiag d = new AudioTimelineDiag();
        long t0 = System.nanoTime();
        d.onSchedule(100, 100, t0);
        d.onSchedule(200, 200, t0 + ms(25));         // 正常 20 Hz 节奏
        d.onSchedule(300, 300, t0 + ms(25) + ms(120)); // 120 ms 迟到批次
        String rep = d.reportIfDue(t0 + ms(5100));
        assertNotNull(rep);
        assertTrue(rep.contains("gapMax=120"), "the late-batch arrival gap shows up: " + rep);
    }

    @Test
    void clampEpisodeEvidenceIsRateLimited() {
        AudioTimelineDiag d = new AudioTimelineDiag();
        long t0 = System.nanoTime();
        String ep = d.episode(ms(52), 0.12f, 300, t0);
        assertNotNull(ep, "first episode logs");
        assertTrue(ep.contains("CLAMP-Episode"), ep);
        assertTrue(ep.contains("drift=52.0ms"), ep);
        assertTrue(ep.contains("delaySeconds=0.120"), ep);
        assertNull(d.episode(ms(60), 0.12f, 300, t0 + ms(500)), "within 2 s the batch stays quiet");
        assertNotNull(d.episode(ms(60), 0.12f, 300, t0 + ms(2100)), "the next episode window logs again");
    }
}
