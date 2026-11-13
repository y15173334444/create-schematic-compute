package io.github.y15173334444.create_schematic_compute.network;

import org.junit.jupiter.api.Test;

import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * C→S 限流桶核心语义的 headless 回归锚（issue #24 / §4.4 分层缓解第 1 层）。
 * <p>覆盖：突发放行 / 桶耗尽后拒绝 / 回填速率 / per-player 隔离 / per-type 独立桶 /
 * 持续超限断开 / 单 tick 巨量尖峰不踢人 / strike 随静默衰减 / 空闲桶回收。</p>
 * <p>本测试只测「限流判定」——断开执行（MC 侧 {@code ctx.disconnect}）不在这里起，
 * 判定与执行的接缝见 {@link C2SPacketRateGuard#Verdict}。核心与 MC 类解耦，纯 JUnit 可起；
 * 时间源注入假钟做确定性断言。</p>
 *
 * <p>Headless regression anchors for the C→S rate-limit bucket core semantics (issue #24 /
 * §4.4 mitigation layer 1). Covers: burst passage / reject after exhaustion / refill rate /
 * per-player isolation / per-type independent buckets / sustained-over-limit disconnect /
 * one-tick mega-spikes never kick / strikes heal during quiet gaps / idle-bucket sweep.
 * This test exercises the "limit decision" only — disconnect execution (the MC-side
 * {@code ctx.disconnect}) does not boot here; the decision/execution seam is
 * {@link C2SPacketRateGuard#Verdict}. The core is MC-free (plain JUnit), and the clock is
 * injected for deterministic assertions.</p>
 */
class C2SPacketRateGuardTest {

    /** 确定性假钟：纳秒，测试内手动推进。 / A deterministic fake clock in nanoseconds,
     *  advanced manually by the test. */
    private static final class FakeClock implements LongSupplier {
        long now = 0;
        @Override public long getAsLong() { return now; }
    }

    private static final C2SPacketRateGuard.Policy P =
        new C2SPacketRateGuard.Policy(3, 1.0);   // 突发 3 / 每秒回填 1：断言最好算的一档
                                                  // burst 3 / refill 1 per second: the
                                                  // easiest arithmetic to assert
    private static final long SEC = 1_000_000_000L;
    private static final long QUANTUM = C2SPacketRateGuard.STRIKE_QUANTUM_NANOS;

    private static C2SPacketRateGuard.Verdict check(C2SPacketRateGuard g, String player, String type) {
        return g.check(player, type, P);
    }

    /** §4.4 锚①突发放行：突发预算内的包全部放行。 / Anchor (burst passage): every packet
     *  inside the burst budget is allowed. */
    @Test
    void burstWithinCapacityIsFullyAllowed() {
        var guard = new C2SPacketRateGuard(new FakeClock()::getAsLong);
        for (int i = 0; i < 3; i++) {
            assertEquals(C2SPacketRateGuard.Verdict.ALLOWED, check(guard, "player-a", "edit_op"),
                "token " + i + " inside the burst");
        }
    }

    /** §4.4 锚②桶耗尽后拒绝：预算外的第一包即拒绝，且不排队（再问再拒）。
     *  / Anchor (reject after exhaustion): the first packet beyond the budget is rejected,
     *  with no queueing (a repeated ask is rejected again). */
    @Test
    void exhaustedBucketRejectsWithoutQueueing() {
        var guard = new C2SPacketRateGuard(new FakeClock()::getAsLong);
        for (int i = 0; i < 3; i++) check(guard, "player-a", "edit_op");
        assertEquals(C2SPacketRateGuard.Verdict.REJECTED, check(guard, "player-a", "edit_op"));
        // 不排队不重试：同一时刻再问还是拒绝 / no queue, no retry: still rejected at once
        assertEquals(C2SPacketRateGuard.Verdict.REJECTED, check(guard, "player-a", "edit_op"));
    }

    /** §4.4 锚③回填速率：耗尽后半秒不足一令牌（仍拒绝），一秒恰好一枚（放行一包），
     *  多一包即再拒。 / Anchor (refill rate): half a second after exhaustion there is not
     *  yet a whole token (still rejected); at one second exactly one token grants (one
     *  allowed packet) and the next packet is rejected again. */
    @Test
    void refillRestoresOneTokenPerSecond() {
        FakeClock clock = new FakeClock();
        var guard = new C2SPacketRateGuard(clock::getAsLong);
        for (int i = 0; i < 3; i++) check(guard, "player-a", "edit_op");
        clock.now += SEC / 2;   // 500ms：只回填 0.5 令牌 / half a token refilled
        assertEquals(C2SPacketRateGuard.Verdict.REJECTED, check(guard, "player-a", "edit_op"),
            "half a second must not grant a whole token");
        clock.now += SEC / 2;   // 累计恰好 1s → 1.0 令牌 / exactly 1 s total → 1.0 tokens
        assertEquals(C2SPacketRateGuard.Verdict.ALLOWED, check(guard, "player-a", "edit_op"),
            "at 1 s the refill grants exactly one token");
        assertEquals(C2SPacketRateGuard.Verdict.REJECTED, check(guard, "player-a", "edit_op"),
            "and no more than one");
    }

    /** §4.4 锚④ per-player 隔离：A 灌到断开，B 的桶不受影响。 / Anchor (per-player
     *  isolation): A floods to a disconnect; B's buckets are untouched. */
    @Test
    void perPlayerBucketsAreIsolated() {
        FakeClock clock = new FakeClock();
        var guard = new C2SPacketRateGuard(clock::getAsLong);
        assertTrue(floodUntilDisconnect(guard, clock, "player-a"),
            "A's sustained flood crosses the kick threshold");
        // B 全新的一桶，照常突发放行 / B gets a fresh bucket: burst passes as usual
        for (int i = 0; i < 3; i++) assertEquals(C2SPacketRateGuard.Verdict.ALLOWED,
            check(guard, "player-b", "edit_op"),
            "B's bucket is independent of A's flood");
    }

    /** §4.4 锚⑤ per-type 独立桶：同一玩家灌穿 edit_op，别的类型照常放行（例如被拒绝的
     *  编辑流不影响同玩家的座椅输入流）。 / Anchor (per-type independence): flooding edit_op
     *  dry does not touch the same player's other types (a rejected edit stream leaves the
     *  same player's seat-input stream alone). */
    @Test
    void perTypeBucketsAreIndependent() {
        var guard = new C2SPacketRateGuard(new FakeClock()::getAsLong);
        for (int i = 0; i < 3; i++) check(guard, "player-a", "edit_op");
        assertEquals(C2SPacketRateGuard.Verdict.REJECTED, check(guard, "player-a", "edit_op"));
        assertEquals(C2SPacketRateGuard.Verdict.ALLOWED, check(guard, "player-a", "seat_input"),
            "a different type on the same player has its own tokens");
    }

    /** §4.4 锚⑥持续超限断开：跨时间片的持续拒绝累加 strikes——前几片（远低于阈值）不断开，
     *  持续下去在有限片内断开。 / Anchor (sustained over-limit disconnects): rejections
     *  sustained across quanta accumulate strikes — the first quanta (far below the
     *  threshold) do not disconnect, and continuing does disconnect within a bounded number
     *  of quanta. */
    @Test
    void sustainedRejectionAcrossQuantaDisconnects() {
        FakeClock clock = new FakeClock();
        var guard = new C2SPacketRateGuard(clock::getAsLong);
        for (int i = 0; i < 3; i++) check(guard, "player-a", "edit_op");
        // 最初 5 片（累计 5 strikes，阈值 10）：只拒绝不断开 / the first 5 quanta (5 strikes
        // against a threshold of 10): rejected, never disconnected
        for (int q = 0; q < 5; q++) {
            clock.now += QUANTUM;
            assertEquals(C2SPacketRateGuard.Verdict.REJECTED, check(guard, "player-a", "edit_op"),
                "quantum " + q + " is below the kick threshold");
        }
        // 持续灌下去：必须在有限片内断开（净累加 8/s → 约 1.1 秒） / keep flooding: the
        // disconnect must come within bounded quanta (net accrual 8/s → ~1.1 s)
        assertTrue(floodUntilDisconnect(guard, clock, "player-a"),
            "a sustained flood must disconnect");
    }

    /** §4.4 语义边界：单 tick 巨量尖峰（多选 1024 节点拖拽松手的合法形态）永远不踢人——
     *  quantum 门让同一时间片内的全部拒绝只值 1 个 strike，且突发预算精确覆盖 1100
     *  （图容量 1024 + 注释推挤余量）。 / Semantic edge (§4.4): a one-tick mega-spike (the
     *  legitimate shape of releasing a 1024-node multi-select drag) never kicks — the
     *  quantum gate prices every rejection inside one quantum as 1 strike, and the burst
     *  budget covers 1100 exactly (the 1024-node graph cap plus comment-push margin). */
    @Test
    void oneTickMegaSpikeNeverDisconnects() {
        var guard = new C2SPacketRateGuard(new FakeClock()::getAsLong);
        // 同一时刻 2000 包：1100 放行 + 900 拒绝，全部落在同一个 strike 时间片内
        // 2000 packets at one instant: 1100 allowed + 900 rejected, all inside one quantum
        int allowed = 0, rejected = 0;
        for (int i = 0; i < 2000; i++) {
            C2SPacketRateGuard.Verdict v =
                guard.check("player-a", "edit_op", C2SPacketRateGuard.EDIT_OP);
            if (v == C2SPacketRateGuard.Verdict.ALLOWED) allowed++;
            else if (v == C2SPacketRateGuard.Verdict.REJECTED) rejected++;
            else fail("a one-tick spike must never disconnect");
        }
        assertEquals(1100, allowed, "the edit-op burst budget covers the 1024-node graph cap plus margin");
        assertEquals(900, rejected, "everything beyond the burst is rejected, not queued");
    }

    /** strike 随静默衰减：攒下的 strike 在足够长的静默后愈合——重灌洪水的断开时刻与全新桶
     *  一致（若衰减丢失，残留 strikes 会在远早于全新桶的时点触发断开）。
     *  / Strikes heal during quiet gaps: strikes banked before a sufficiently long quiet
     *  heal — a renewed flood disconnects at the same point as a fresh bucket (if the decay
     *  were lost, the leftover strikes would trip the disconnect far earlier than a fresh
     *  bucket would). */
    @Test
    void strikesHealDuringQuietGaps() {
        FakeClock clock = new FakeClock();
        var guard = new C2SPacketRateGuard(clock::getAsLong);
        for (int i = 0; i < 3; i++) check(guard, "player-a", "edit_op");
        for (int q = 0; q < 5; q++) {   // 攒 5 strikes / bank 5 strikes
            clock.now += QUANTUM;
            check(guard, "player-a", "edit_op");
        }
        clock.now += 5 * SEC;           // 静默 5s：衰减 10 ≫ 5 → 清零 / 5 s quiet: decay 10 ≫ 5 → zero
        // 重灌洪水：前 9 片不得断开（全新桶行为）…… / renewed flood: no disconnect in the
        // first 9 quanta (fresh-bucket behaviour)...
        for (int q = 0; q < 9; q++) {
            clock.now += QUANTUM;
            assertNotEquals(C2SPacketRateGuard.Verdict.DISCONNECT, check(guard, "player-a", "edit_op"),
                "healed strikes must behave like a fresh bucket (quantum " + q + ")");
        }
        // ……且持续下去仍会在有限片内断开 / ...and continuing still disconnects within
        // bounded quanta
        assertTrue(floodUntilDisconnect(guard, clock, "player-a"),
            "the renewed flood must eventually disconnect");
    }

    /** 空闲桶回收：留存窗外的桶被清走（直接调用 {@code sweepIdle} 断言回收数）、空玩家
     *  条目一并移除；新触碰的桶存活，再次过期后同样被回收。
     *  / Idle sweep: buckets outside the retention window are swept (the sweep count asserted
     *  via a direct {@code sweepIdle} call) and emptied player entries go with them; a newly
     *  touched bucket survives, and is swept too once it ages out. */
    @Test
    void idleBucketsAreSwept() {
        FakeClock clock = new FakeClock();
        var guard = new C2SPacketRateGuard(clock::getAsLong);
        check(guard, "player-a", "edit_op");
        check(guard, "player-b", "edit_op");
        assertEquals(2, guard.bucketCount());
        clock.now += C2SPacketRateGuard.IDLE_RETENTION_NANOS;
        // 两桶恰好闲置留存窗整窗 → 直接回收 / both buckets idle for exactly the retention
        // window → swept directly
        assertEquals(2, guard.sweepIdle(clock.now));
        assertEquals(0, guard.bucketCount(), "stale buckets are gone");
        assertEquals(0, guard.playerCount(), "empty player entries are dropped");
        // 新桶建立后存活一整个留存窗，过期后同样被回收 / a fresh bucket survives a full
        // retention window, then ages out the same way
        check(guard, "player-b", "seat_input");
        assertEquals(1, guard.bucketCount());
        clock.now += C2SPacketRateGuard.IDLE_RETENTION_NANOS;
        assertEquals(1, guard.sweepIdle(clock.now));
        assertEquals(0, guard.bucketCount());
    }

    /** 预算表机制不变量：与类型真实使用形态绑定的下界（防预算漂移——回填低于合法持续节奏
     *  会让正常玩家被拒；突发低于合法单点峰值会拆散合法操作）与逐出延迟上界。
     *  / Budget-table mechanism invariants: lower bounds tied to each type's real usage
     *  shape (guards against budget drift — a refill below the legitimate cadence would
     *  reject normal players, a burst below the legitimate one-shot peak would shred
     *  legitimate actions) and the eviction-latency ceiling. */
    @Test
    void budgetTableMatchesRealUsageShapes() {
        // 座椅输入：合法 20/s 持续 → 回填必须 ≥ 20（否则正常乘坐会被拒）
        // Seat input: legit 20/s sustained → refill must cover 20/s
        assertTrue(C2SPacketRateGuard.SEAT_INPUT.refillPerSecond() >= 20.0,
            "seat input refill must cover the every-tick 20/s cadence");
        // 临场数据：客户端节流 8.3/s → 回填 ≥ 8.3 / Presence: client-throttled at 8.3/s
        assertTrue(C2SPacketRateGuard.PRESENCE.refillPerSecond() >= 8.3,
            "presence refill must cover the 120 ms client throttle");
        // 编辑 op：图容量上限 1024 节点 → 突发 ≥ 1024（多选拖拽松手一包一节点）
        // Edit op: the graph caps at 1024 nodes → burst ≥ 1024 (one op per dragged node)
        assertTrue(C2SPacketRateGuard.EDIT_OP.burstTokens() >= 1024,
            "edit-op burst must cover a full-graph multi-select drag release");
        // Blob 分片：编解码层 MAX_TOTAL_CHUNKS = 64 → 突发 ≥ 64（合法上传单 tick 连发）
        // Blob chunks: the codec's MAX_TOTAL_CHUNKS = 64 → burst ≥ 64 (legit uploads fire
        // in one tick)
        assertTrue(C2SPacketRateGuard.BLOB_CHUNK.burstTokens() >= 64,
            "blob-chunk burst must cover the codec chunk ceiling");
        // 逐出延迟上界：持续机器速灌包约 1 秒出头被逐出（10 片 × 100ms + 回填令牌的微扰）
        // Eviction-latency ceiling: a sustained machine-rate flood is evicted in about a
        // second (10 quanta × 100 ms plus the perturbation of refilled tokens)
        assertTrue((long) C2SPacketRateGuard.STRIKE_QUANTUM_NANOS * C2SPacketRateGuard.KICK_STRIKES <= 2 * SEC,
            "sustained floods must be evicted within two seconds");
    }

    /** 以每时间片一包的节奏灌到断开（≤ 25 片）；断开即时返回 true。假钟随循环推进。
     *  / Floods one packet per quantum until the disconnect verdict (≤ 25 quanta) and
     *  returns true as soon as it lands. The fake clock advances with the loop. */
    private static boolean floodUntilDisconnect(C2SPacketRateGuard guard, FakeClock clock, String player) {
        for (int q = 0; q < 25; q++) {
            clock.now += QUANTUM;
            if (check(guard, player, "edit_op") == C2SPacketRateGuard.Verdict.DISCONNECT) return true;
        }
        return false;
    }
}
