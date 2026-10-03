package io.github.y15173334444.create_schematic_compute.client.audio;

import java.util.Locale;

/**
 * 调度时间线诊断：把「推迟/挤堆」类听感问题量化成可定位的数字（plan R2 排查工具）。
 * 纯逻辑、独立 logger（勿引模组主类——测试环境不可初始化，AudioBands 同口径）。
 * <p>Scheduling timeline diagnostics: turns "delay/bunch" listening complaints into
 * numbers that pinpoint the culprit. Pure logic with its own logger (never pull in the mod
 * main class — it can't initialise in tests; same stance as AudioBands' probe logger).</p>
 *
 * <p><b>报告行怎么读</b>（每 5 秒一条，有活动才打）：</p>
 * <ul>
 *   <li>{@code drift 桶}：落点 − 目标帧的分布。<b>0 ms 桶外聚集</b> = 落点漂移（客户端侧）——
 *       在 50/100 ms 桶尖峰是「双钟错位/等锁吃渲染步进」类签名；</li>
 *   <li>{@code clamp}：目标帧已越过的迟放次数（应≈0；多 = 渲染跟不上或目标算晚了）；</li>
 *   <li>{@code gapMax}：音符到达的最大间隔——&gt;&gt;50 ms 且伴随批量到达 = 服务端下发节奏问题；
 *       服务端 MSPT 正常时应稳定在 ≈25 ms（20 Hz 预播节奏）；</li>
 *   <li>{@code render avg/max} 与 {@code rGapMax}：混音渲染耗时/间隔——max 接近 50 ms
 *       或 rGapMax &gt; 200 ms = 渲染侧卡顿/欠载；</li>
 *   <li>{@code voices}：并发声部峰值（逼近 1024 上限会触发剥夺）。</li>
 * </ul>
 */
final class AudioTimelineDiag {

    /** 独立 logger（勿引模组主类——测试环境不可初始化，AudioBands 同口径）。 */
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AudioTimelineDiag.class);

    /** 漂移直方图桶边界（毫秒）：≤0, ≤5, ≤25, ≤50, ≤100, ≤200, 其余。 */
    private static final long[] BUCKET_MS = {0, 5, 25, 50, 100, 200};
    private static final long REPORT_INTERVAL_NS = 5_000_000_000L;

    private static long ms(double v) { return (long) (v * 1_000_000); }

    // ── 调度侧 / scheduling side ──
    private long schedCount;
    private long clampCount;
    private long driftSum, driftMin = Long.MAX_VALUE, driftMax = Long.MIN_VALUE;
    private final long[] driftHist = new long[BUCKET_MS.length + 1];
    private long lastArrivalNanos;
    private long arrivalGapMax;

    // ── 渲染侧 / render side ──
    private long renderCount;
    private long renderNanosSum, renderNanosMax;
    private long lastRenderEndNanos;
    private long renderGapMax;
    private int voicesPeak;

    private long windowStartNanos = System.nanoTime();

    /** 一次调度落点：target=目标绝对帧，placed=实际起音帧（含钳制）。 */
    synchronized void onSchedule(long target, long placed, long nowNanos) {
        schedCount++;
        long drift = placed - target;
        if (drift > 0) clampCount++;
        driftSum += drift;
        driftMin = Math.min(driftMin, drift);
        driftMax = Math.max(driftMax, drift);
        driftHist[bucketIndex(drift)]++;
        if (lastArrivalNanos != 0) {
            arrivalGapMax = Math.max(arrivalGapMax, nowNanos - lastArrivalNanos);
        }
        lastArrivalNanos = nowNanos;
    }

    /** 一次混音渲染完成：nanosTaken=渲染耗时，voices=本次活跃声部。
     *  停顿取证：渲染 >40 ms 或渲染间隔 >120 ms 视为进程级卡顿（GC/系统抖动），
     *  限频出 {@code STALL-Episode} 行——与听感「卡/挤一下然后恢复」对时间点用。 */
    synchronized void onRender(long nanosTaken, int voices, long nowNanos) {
        renderCount++;
        renderNanosSum += nanosTaken;
        renderNanosMax = Math.max(renderNanosMax, nanosTaken);
        long gap = 0;
        if (lastRenderEndNanos != 0) {
            gap = nowNanos - lastRenderEndNanos;
            renderGapMax = Math.max(renderGapMax, gap);
        }
        lastRenderEndNanos = nowNanos;
        voicesPeak = Math.max(voicesPeak, voices);
        if (nanosTaken > ms(40) || gap > ms(120)) {
            String ep = stallEpisode(nanosTaken, gap, voices, nowNanos);
            if (ep != null) LOG.warn(ep);
        }
    }

    /** 停顿取证限频（复用发作限频窗口）。 */
    private String stallEpisode(long nanosTaken, long gapNanos, int voices, long nowNanos) {
        if (nowNanos - lastEpisodeNanos < EPISODE_NS) return null;
        lastEpisodeNanos = nowNanos;
        return String.format(Locale.ROOT,
            "[TimelineDiag] STALL-Episode render=%.1fms gap=%.1fms voices=%d",
            nanosTaken / 1e6, gapNanos / 1e6, voices);
    }

    // ── 盲区探针：重挂 / 限幅抽吸 / 目标成团 ──

    private long reattachCount;
    private float limiterGainMin = 1f;
    private float batchSpanMax;      // 一次到达批内 delaySeconds 的最大跨度（秒）
    private float batchFirstDelay;

    /** 输出通道重挂（欠载/重建）——每次必打（低频事件，不设限频）。 */
    synchronized void onReattach(long nowNanos) {
        reattachCount++;
        LOG.warn("[TimelineDiag] REATTACH #{} (output channel re-created — underrun/reload evidence)", reattachCount);
    }

    // ── 下发批次侧 / dispatch-batch side ──
    private long lastDispatchTick = Long.MIN_VALUE;
    private long lastDispatchArrivalNanos;
    private long dispatchGapMaxMs;   // 相邻批次 dispatchGameTick 差 ×50——服务端下发节奏的最大跳变
    private long clientExtraMaxMs;   // 到达间隔 − 服务端节奏差——客户端主线程积压/网络抖动的峰值
    private long lateTickMax;        // 迟到校正偏移（客户端 level 时间 − 服务端 tick）峰值
    private long lateSpikeCount;     // lateTicks ≥ 2 的批次计数（时钟歪斜/停顿签名）

    /** 一次下发批次到达（一个 NoteEventPacket 在客户端主线程被执行的时刻）。
     *  <ul>
     *    <li>{@code serverGapMs}：相邻批次 dispatchGameTick 差 ×50ms——服务端下发节奏
     *        （集成服停顿/展开卡顿会在这里跳变）；</li>
     *    <li>{@code clientExtraMs}：到达间隔 − serverGapMs——客户端主线程积压
     *        （enqueueWork 排队）或网络抖动；</li>
     *    <li>{@code lateTicks}：迟到校正偏移 = 客户端 level 时间 − 服务端下发 tick。
     *        两台钟可能歪斜，峰值 ≥2 = 校正本身可能在制造推迟（堵塞嫌疑，需警惕）。</li>
     *  </ul>
     *  One dispatch batch arrival (the moment a NoteEventPacket runs on the client main
     *  thread): server pacing = dispatch-tick delta ×50 ms; client extra = arrival gap minus
     *  server pacing (main-thread queueing / network jitter); lateTicks = the late-correction
     *  offset — a spike ≥2 means the correction itself may be deferring notes. */
    synchronized void onDispatchBatch(long dispatchGameTick, int events, long lateTicks, long nowNanos) {
        if (events > 0 && lastDispatchTick != Long.MIN_VALUE) {
            long serverGapMs = Math.max(0, dispatchGameTick - lastDispatchTick) * 50;
            long arrivalGapMs = (nowNanos - lastDispatchArrivalNanos) / 1_000_000;
            long clientExtraMs = Math.max(0, arrivalGapMs - serverGapMs);
            dispatchGapMaxMs = Math.max(dispatchGapMaxMs, serverGapMs);
            clientExtraMaxMs = Math.max(clientExtraMaxMs, clientExtraMs);
            if (serverGapMs > DISPATCH_GAP_EPISODE_MS) {
                String ep = dispatchEpisode(String.format(Locale.ROOT,
                    "[TimelineDiag] DISPATCH-GAP serverGap=%.0fms tick %d→%d (server-side dispatch stall)",
                    (double) serverGapMs, lastDispatchTick, dispatchGameTick), nowNanos);
                if (ep != null) LOG.warn(ep);
            }
            if (clientExtraMs > CLIENT_DELAY_EPISODE_MS) {
                String ep = dispatchEpisode(String.format(Locale.ROOT,
                    "[TimelineDiag] CLIENT-DELAY extra=%.0fms events=%d (main-thread queueing/network)",
                    (double) clientExtraMs, events), nowNanos);
                if (ep != null) LOG.warn(ep);
            }
        }
        if (events > 0) {
            lastDispatchTick = dispatchGameTick;
            lastDispatchArrivalNanos = nowNanos;
        }
        if (lateTicks >= 2) {
            lateSpikeCount++;
            lateTickMax = Math.max(lateTickMax, lateTicks);
            String ep = dispatchEpisode(String.format(Locale.ROOT,
                "[TimelineDiag] LATE-CORR-SPIKE lateTicks=%d events=%d (client/server clock skew — correction may defer)",
                lateTicks, events), nowNanos);
            if (ep != null) LOG.warn(ep);
        }
    }

    /** 下发侧发作取证限频（与钳制/停顿共窗口）。 */
    private String dispatchEpisode(String line, long nowNanos) {
        if (nowNanos - lastEpisodeNanos < EPISODE_NS) return null;
        lastEpisodeNanos = nowNanos;
        return line;
    }

    /** 服务端下发停顿取证阈值（≥5 tick）。 */
    private static final long DISPATCH_GAP_EPISODE_MS = 250;
    /** 客户端额外延迟取证阈值（约预播上限）。 */
    private static final long CLIENT_DELAY_EPISODE_MS = 200;

    /** 混音块限幅增益采样（<1 即抽吸中）；窗口取最小值。 */
    synchronized void onLimiterGain(float gain) {
        limiterGainMin = Math.min(limiterGainMin, gain);
    }

    /** 一次到达批（间隔 &lt;10 ms 的连续调度）内 delaySeconds 跨度：单批跨度 &gt;1 乐理 tick
     *  = 服务端目标成团下发（「挤堆」的目标侧签名）。时间戳独立维护（勿用 lastArrivalNanos
     *  ——它在 onSchedule 里已被更新，会把所有音符并进同一「批」）。 */
    synchronized void onBatch(float delaySeconds, long nowNanos) {
        if (lastBatchNanos != 0 && nowNanos - lastBatchNanos < ms(10)) {
            batchSpanMax = Math.max(batchSpanMax, Math.abs(delaySeconds - batchFirstDelay));
        } else {
            batchFirstDelay = delaySeconds;
        }
        lastBatchNanos = nowNanos;
    }
    private long lastBatchNanos;

    /** 到点输出聚合报告并重置窗口；无活动返回 null。 */
    synchronized String reportIfDue(long nowNanos) {
        if (nowNanos - windowStartNanos < REPORT_INTERVAL_NS) return null;
        String report = buildReport(nowNanos);
        reset(nowNanos);
        return report;
    }

    private String buildReport(long nowNanos) {
        if (schedCount == 0 && renderCount == 0) return null;
        double winSec = (nowNanos - windowStartNanos) / 1e9;
        StringBuilder sb = new StringBuilder(160);
        sb.append(String.format(Locale.ROOT,
            "[TimelineDiag] %.1fs sched=%d clamp=%d drift(ms) min=%.1f avg=%.1f max=%.1f | hist:",
            winSec, schedCount, clampCount,
            driftMin == Long.MAX_VALUE ? 0 : driftMin / 1e6,
            schedCount == 0 ? 0 : driftSum / (double) schedCount / 1e6,
            driftMax == Long.MIN_VALUE ? 0 : driftMax / 1e6));
        sb.append(String.format(Locale.ROOT, " <=0:%d <=5:%d <=25:%d <=50:%d <=100:%d <=200:%d >200:%d",
            driftHist[0], driftHist[1], driftHist[2], driftHist[3], driftHist[4], driftHist[5], driftHist[6]));
        sb.append(String.format(Locale.ROOT,
            " | gapMax=%.1fms render avg=%.1fms max=%.1fms rGapMax=%.1fms voices=%d",
            arrivalGapMax / 1e6,
            renderCount == 0 ? 0 : renderNanosSum / (double) renderCount / 1e6,
            renderNanosMax / 1e6, renderGapMax / 1e6, voicesPeak));
        sb.append(String.format(Locale.ROOT,
            " | limiterMin=%.2f reattach=%d batchSpan=%.3fs dGapMax=%dms cDelayMax=%dms lateTick=%d(%d)",
            limiterGainMin, reattachCount, batchSpanMax,
            dispatchGapMaxMs, clientExtraMaxMs, lateTickMax, lateSpikeCount));
        return sb.toString();
    }

    private void reset(long nowNanos) {
        schedCount = clampCount = 0;
        driftSum = 0;
        driftMin = Long.MAX_VALUE;
        driftMax = Long.MIN_VALUE;
        java.util.Arrays.fill(driftHist, 0);
        // 到达/渲染间隔跨窗口保留（持续监测节奏），只重置累计量
        renderCount = 0;
        renderNanosSum = renderNanosMax = 0;
        arrivalGapMax = renderGapMax = 0;
        voicesPeak = 0;
        limiterGainMin = 1f;
        batchSpanMax = 0;
        dispatchGapMaxMs = clientExtraMaxMs = 0;
        lateTickMax = lateSpikeCount = 0;
        windowStartNanos = nowNanos;
    }

    private static int bucketIndex(long driftNanos) {
        double ms = driftNanos / 1e6;
        for (int i = 0; i < BUCKET_MS.length; i++) {
            if (ms <= BUCKET_MS[i]) return i;
        }
        return BUCKET_MS.length;
    }

    // ── 发作取证 / episode evidence ──

    /** 钳制发作即时取证限频（ns）：2 秒内至多一条（批内其余只计数）。 */
    private static final long EPISODE_NS = 2_000_000_000L;
    private long lastEpisodeNanos;

    /**
     * 一次钳制发作（落点迟放 &gt; 5 ms）的取证行；限频 2 秒一条，返回 null 表示本条静默。
     * 带 delaySeconds 以区分「预播不足」（起播/循环回绕，乐理正确）与「漂移回拉」
     * （目标在预播余量内仍被钳 = 时钟比率漂移类，需修）。
     */
    synchronized String episode(long driftNanos, float delaySeconds, int voices, long nowNanos) {
        if (nowNanos - lastEpisodeNanos < EPISODE_NS) return null;
        lastEpisodeNanos = nowNanos;
        return String.format(Locale.ROOT,
            "[TimelineDiag] CLAMP-Episode drift=%.1fms delaySeconds=%.3f voices=%d",
            driftNanos / 1e6, delaySeconds, voices);
    }

    // ── 测试观察口 / test seams ──

    synchronized long schedCount() { return schedCount; }
    synchronized long clampCount() { return clampCount; }
    synchronized long[] driftHistSnapshot() { return driftHist.clone(); }
}
