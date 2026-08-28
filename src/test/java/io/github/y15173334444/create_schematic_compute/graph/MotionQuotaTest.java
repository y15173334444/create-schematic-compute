package io.github.y15173334444.create_schematic_compute.graph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配额完成模型（MotionQuota）测试 —— 前代"位置窗口采样"在高转速下跳过窗口导致
 * 指令永不完成（栈卡死），配额模型必须对任意转速/角度组合都能完成。
 * Quota completion model (MotionQuota) tests — the legacy position-window sampling
 * skipped the window at high RPM, wedging the command stack forever; the quota model
 * must complete for every speed/angle combination.
 */
class MotionQuotaTest {

    private static final float DT = 0.05f;

    @Test
    @DisplayName("90° at 64 RPM completes (legacy window sampling never did: min miss 3.6°)")
    void rotate90At64RpmCompletes() {
        var q = MotionQuota.of(90f);
        int ticks = 0;
        while (!q.done() && ticks < 10_000) {
            q.consumeAbs(MotionQuota.degreesPerTick(64f));
            ticks++;
        }
        assertTrue(q.done(), "90 deg at 64 rpm must complete");
        // 64 rpm × 0.3 = 19.2°/tick → 5 ticks（96° ≥ 90°，允许最后一步超调）
        // 64 rpm × 0.3 = 19.2 deg/tick → 5 ticks (96 >= 90, final overshoot allowed)
        assertEquals(5, ticks);
        assertEquals(90f - 5 * 19.2f, q.remaining(), 0.0001f);
    }

    @Test
    @DisplayName("90° at max 256 RPM completes in few ticks")
    void rotate90AtMaxRpmCompletes() {
        var q = MotionQuota.of(90f);
        int ticks = 0;
        while (!q.done() && ticks < 10_000) {
            q.consumeAbs(MotionQuota.degreesPerTick(256f));
            ticks++;
        }
        assertTrue(q.done());
        assertEquals(2, ticks);   // 76.8°/tick → 2 ticks（153.6 ≥ 90）
    }

    @Test
    @DisplayName("1 meter at 16 RPM completes")
    void moveOneMeterAt16RpmCompletes() {
        var q = MotionQuota.of(1f);
        int ticks = 0;
        while (!q.done() && ticks < 1_000_000) {
            q.consumeAbs(MotionQuota.metersPerTick(16f));
            ticks++;
        }
        assertTrue(q.done());
        // 16 rpm → 16/512×0.05 = 0.0015625 m/tick → 640 ticks 理论值；
        // 0.05f 的浮点尾差允许 641（dt 非精确表示）。
        // 640 theoretical; the 0.05f float tail allows 641.
        assertTrue(ticks == 640 || ticks == 641, "ticks=" + ticks);
    }

    @Test
    @DisplayName("negative speed still books travel (direction is the transmission's business)")
    void negativeSpeedStillBooksTravel() {
        var q = MotionQuota.of(90f);
        for (int i = 0; i < 5 && !q.done(); i++)
            q.consumeAbs(MotionQuota.degreesPerTick(-64f));
        assertTrue(q.done(), "absolute travel booking must ignore the sign");
    }

    @Test
    @DisplayName("signed negative travel passed raw to consumeAbs still books (the -32 RPM wedge)")
    void negativeRawTravelStillBooks() {
        // 复刻事故调用形态：调用方直接传 getSpeed()×0.3（负方向网络为负值）。
        // The exact accident call shape: the caller passes getSpeed()×0.3 raw.
        var q = MotionQuota.of(36f);
        int ticks = 0;
        while (!q.done() && ticks < 10_000) {
            q.consumeAbs(-32f * MotionQuota.DEG_PER_RPM_TICK);   // -9.6°/tick
            ticks++;
        }
        assertTrue(q.done(), "negative-direction network must still complete the command");
        assertEquals(4, ticks);
    }

    @Test
    @DisplayName("zero speed never completes but never corrupts (power-gating freezes the loop)")
    void zeroSpeedNeverCompletes() {
        var q = MotionQuota.of(90f);
        for (int i = 0; i < 100; i++)
            q.consumeAbs(MotionQuota.degreesPerTick(0f));
        assertFalse(q.done());
        assertEquals(90f, q.remaining(), 0.0001f);
    }

    @Test
    @DisplayName("conversion constants match the official Create formulas")
    void officialConversions() {
        assertEquals(0.3f, MotionQuota.degreesPerTick(1f), 1e-6f);
        assertEquals(1f / 512f * 0.05f, MotionQuota.metersPerTick(1f), 1e-9f);
    }
}
