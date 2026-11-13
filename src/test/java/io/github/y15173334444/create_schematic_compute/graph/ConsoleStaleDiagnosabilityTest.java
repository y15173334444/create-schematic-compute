package io.github.y15173334444.create_schematic_compute.graph;

import io.github.y15173334444.create_schematic_compute.graph.TopoStats.HostId;
import io.github.y15173334444.create_schematic_compute.network.ConsoleProtocol;
import io.github.y15173334444.create_schematic_compute.network.SignalBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code stale} / {@code hosts} 控制台命令在采集门两侧的可诊断性 + <b>端到端真实快照</b>锚
 * （§4.3 / §3.3 / issue #29）。本文件住在 graph 包的唯一原因：采集门的重设面
 * （{@code TopoStats.configureCollection}）是包内测试面（{@code BusTrace.configure} 同款），
 * 门控相关的断言必须能翻门；渲染函数本身在 network 包、经公开的
 * {@link ConsoleProtocol#respond(String, HostId)} 走<b>真实分发路径</b>。这里锁死：
 * <ul>
 *   <li><b>采集关（默认生产形态）</b>：{@code stale} 明确回「采集未启用（-Dcsc.topoCollect）」
 *       而非空数据、不静默（issue #29 可诊断性硬口径）；{@code hosts} 的陈旧计数列诚实显示
 *       「—」并在表头说明。</li>
 *   <li><b>采集开 · 端到端</b>：真实采集管线（{@code beginCollectTick} → 求值宿主上下文 →
 *       {@code noteChannelWrite}/{@code noteChannelRead} 真实钩子 → {@code postCollectSummary}
 *       Post 汇总）产出的快照，经 respond 渲染出按通道分组的 ①/② 计数 + 同/跨 tick 跨度 +
 *       合计——数据真值口径（§3.3）的完整闭环：控制台读的正是采集快照。</li>
 *   <li><b>陈旧跨度的两个档位</b>：同 tick 内被超越（读写写同 tick）与跨 tick 被超越（记录
 *       滞留过 Post、下一 tick 才被超越）各自落到正确的跨度列。</li>
 *   <li><b>通道键单一源</b>：测试通道键经 {@code SignalBus.PORT.channelKey} 在宿主维度作用域
 *       内归一（与生产 epoch 键 / DAG 边标签同一入口），渲染侧剥前缀回逻辑名。</li>
 *   <li><b>快照未汇总</b>：门开但尚无 Post 趟次（快照 tick=0）时明确回「尚未汇总」。</li>
 * </ul>
 *
 * <p>The diagnosability of the {@code stale} / {@code hosts} console commands on both sides of
 * the collection gate + the <b>end-to-end real snapshot</b> anchors (§4.3 / §3.3 / issue #29).
 * This file lives in the graph package for exactly one reason: the gate's reset face
 * ({@code TopoStats.configureCollection}) is a package-private test face (the
 * {@code BusTrace.configure} shape), so gate-dependent assertions must be able to flip it; the
 * renderers themselves live in the network package and run through the <b>real dispatch path</b>
 * via the public {@link ConsoleProtocol#respond(String, HostId)}. Pinned here:</p>
 * <ul>
 *   <li><b>Gate off (the default production shape)</b>: {@code stale} explicitly answers
 *       "collection disabled (-Dcsc.topoCollect)" instead of empty data, no silence (the
 *       issue #29 diagnosability hard rule); {@code hosts}' stale-count column honestly shows
 *       "—" with the header saying so.</li>
 *   <li><b>Gate on · end to end</b>: a snapshot produced by the real collection pipeline
 *       ({@code beginCollectTick} → the evaluation-host context → the real
 *       {@code noteChannelWrite}/{@code noteChannelRead} hooks → the {@code postCollectSummary}
 *       Post pass) renders through respond as ①/② counts grouped by channel + the same/cross-tick
 *       spans + the total — the data-truth plane (§3.3) closed end to end: the console reads
 *       exactly the collection snapshot.</li>
 *   <li><b>Both stale-span tiers</b>: superseded within the same tick (read-write-write in one
 *       tick) and superseded across ticks (a record lingering past a Post, superseded only in a
 *       later tick) each land in the right span column.</li>
 *   <li><b>The channel key's single source</b>: the test's channel keys normalize through
 *       {@code SignalBus.PORT.channelKey} inside the host's dimension scope (the same entry as
 *       the production epoch keys / DAG edge labels), and the render side strips the prefix back
 *       to the logical name.</li>
 *   <li><b>Snapshot not yet summarized</b>: gate on but no Post pass yet (snapshot tick=0)
 *       answers "not yet summarized" explicitly.</li>
 * </ul>
 */
@DisplayName("控制台 stale/hosts × 采集门：可诊断性 + 端到端真实快照 / console stale/hosts × the collection gate: diagnosability + the end-to-end real snapshot")
class ConsoleStaleDiagnosabilityTest {

    private static final String DIM = "csc_test:dim";
    private static final HostId A = new HostId(DIM, 1, 2, 3);

    @BeforeEach
    @AfterEach
    void resetState() {
        // 采集门与采集态、注册表、总线表全部复位 —— 门必须关回去（其他测试在门关的前提下跑）；
        // tick 推进使控制台的图缓存失效（跨测试隔离）。
        // Reset the gate and collection state, the registry and the bus tables — the gate must
        // be turned back off (every other test runs under a closed gate); a tick advance also
        // invalidates the console's graph cache (cross-test isolation).
        TopoStats.configureCollection(false);
        TopoStats.clearCollection();
        TopoStats.clearRegistry();
        TopoStats.endEvalContext(null);
        SignalBus.clear();
        io.github.y15173334444.create_schematic_compute.network.ConsoleQueryCache.advanceTick();
    }

    // ══════════ 1. 采集关：可诊断回执 / gate off: diagnosable replies ══════════

    @Test
    @DisplayName("采集关：stale 明确回「采集未启用（-Dcsc.topoCollect）」而非空数据")
    void staleGateOffAnswersDiagnosably() {
        assertFalse(TopoStats.collecting(), "前置：门关 / precondition: gate off");
        List<String> lines = ConsoleProtocol.respond("stale", A);
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("采集未启用"), "明确说采集未启用 / explicitly says collection disabled: " + lines.get(0));
        assertTrue(lines.get(0).contains("-Dcsc.topoCollect"), "给出开启方式 / it names the enabling property: " + lines.get(0));
        assertFalse(lines.get(0).contains("①="), "绝不渲染空数据伪装成计数 / never dresses empty data up as counts");
    }

    @Test
    @DisplayName("采集关：hosts 表头明说不可用原因 + 行内计数列诚实显示「—」")
    void hostsGateOffHidesCountsHonestly() {
        assertFalse(TopoStats.collecting(), "前置：门关 / precondition: gate off");
        TopoStats.hostLoaded(A, () -> TopoStats.ChannelCensus.EMPTY);
        List<String> lines = ConsoleProtocol.respond("hosts");
        assertTrue(lines.get(0).contains("采集未启用") && lines.get(0).contains("-Dcsc.topoCollect"),
            "表头说明计数不可用的原因 / the header explains why counts are unavailable: " + lines.get(0));
        String row = lines.stream().filter(l -> l.contains(String.valueOf(A))).findFirst().orElseThrow();
        assertTrue(row.contains("①=—") && row.contains("跨=—"),
            "采集关 = 计数列「—」（不伪造零）/ gate off = the count column is \"—\" (no fabricated zeros): " + row);
        assertTrue(row.contains("running=no"), "空普查宿主不入图 / an empty-census host is not in the graph");
    }

    // ══════════ 2. 采集开 · 端到端真实快照 / gate on · the end-to-end real snapshot ══════════

    @Test
    @DisplayName("端到端：真实采集管线 → Post 快照 → stale 按通道分组渲染 ①/② + 同 tick 跨度 + 合计")
    void endToEndRealCollectionRendersGroupedCountsAndSameTickSpan() {
        TopoStats.configureCollection(true);
        TopoStats.clearCollection();
        // 通道键走生产单一入口（维度作用域内归一——与 epoch 键 / DAG 边标签同源）
        // The channel key rides the production single entry (normalized inside the dimension
        // scope — one source with the epoch keys / the DAG's edge labels)
        String key = SignalBus.runInDimension(DIM, () -> SignalBus.PORT.channelKey("alpha"));
        // 同 tick 读者先写者后：读 5（epoch=1）→ 写 7 → Post 判 ①（epoch 被超越）+ ②（值不同）
        // + 同 tick 跨度
        // Reader before writer within one tick: read 5 (epoch=1) → write 7 → the Post judges
        // ① (epoch superseded) + ② (value changed) + the same-tick span
        TopoStats.beginCollectTick();
        TopoStats.beginEvalContext(A);
        TopoStats.noteChannelWrite(key, "", 5f);
        TopoStats.noteChannelRead(key, "", 5f);
        TopoStats.noteChannelWrite(key, "", 7f);
        TopoStats.endEvalContext(null);
        TopoStats.postCollectSummary();

        var snap = TopoStats.collectionSnapshot();
        assertEquals(1L, snap.byHost().get(A).staleReads(), "真实快照 ①=1 / the real snapshot's ①=1");
        assertFalse(snap == TopoStats.CollectSnapshot.EMPTY, "Post 已发布快照 / the Post has published the snapshot");

        List<String> lines = ConsoleProtocol.respond("stale", A);
        assertTrue(lines.get(0).contains("stale") && lines.get(0).contains(String.valueOf(A)),
            "表头回显宿主 / the header echoes the host: " + lines.get(0));
        assertTrue(lines.get(0).contains("tick=") && !lines.get(0).contains("tick=0"),
            "快照 tick 已推进 / the snapshot tick has advanced");
        String row = lines.get(1);
        assertTrue(row.contains("alpha") && !row.contains("\u0001"),
            "显示逻辑名（前缀经单一源逆映射剥离）/ the logical name shows (the prefix stripped via the single source's inverse): " + row);
        assertTrue(row.contains("①=1") && row.contains("②=1") && row.contains("同tick=1") && row.contains("跨tick=0"),
            "①/② 与同 tick 跨度逐项 / ①/② and the same-tick span item by item: " + row);
        String total = lines.get(2);
        assertTrue(total.contains("①=1") && total.contains("同tick=1"), "合计行 / the total row: " + total);
        // 输出全序确定 / totally ordered output
        assertEquals(lines, ConsoleProtocol.respond("stale", A));
    }

    @Test
    @DisplayName("端到端跨 tick：记录滞留过 Post、下一 tick 被超越 → 跨 tick 跨度列")
    void endToEndCrossTickSupersessionLandsInTheCrossTickSpan() {
        TopoStats.configureCollection(true);
        TopoStats.clearCollection();
        String key = SignalBus.runInDimension(DIM, () -> SignalBus.PORT.channelKey("beta"));
        // tick 1：读（epoch=0，从未写过）→ Post 不判（未被超越，记录滞留）
        // tick 1: a read (epoch=0, never written) → the Post judges nothing (not superseded,
        // the record lingers)
        TopoStats.beginCollectTick();
        TopoStats.beginEvalContext(A);
        TopoStats.noteChannelRead(key, "", 3f);
        TopoStats.endEvalContext(null);
        TopoStats.postCollectSummary();
        assertEquals(0L, TopoStats.collectionSnapshot().byHost().size(), "tick 1 无判定 / tick 1 judges nothing");
        // tick 2：写入超越记录（记录 tick=1 ≠ 当前 tick=2）→ ① + ② + 跨 tick 跨度
        // tick 2: a write supersedes the record (record tick=1 ≠ current tick=2) → ① + ② +
        // the cross-tick span
        TopoStats.beginCollectTick();
        TopoStats.noteChannelWrite(key, "", 4f);
        TopoStats.postCollectSummary();

        List<String> lines = ConsoleProtocol.respond("stale", A);
        String row = lines.get(1);
        assertTrue(row.contains("①=1") && row.contains("②=1") && row.contains("同tick=0") && row.contains("跨tick=1"),
            "跨 tick 被超越落到跨度列 / the cross-tick supersession lands in the span column: " + row);
    }

    @Test
    @DisplayName("快照未汇总：门开但无 Post 趟次（tick=0）→ 明确回执")
    void gateOnWithoutAPostPassSaysNotYetSummarized() {
        TopoStats.configureCollection(true);
        TopoStats.clearCollection();     // 快照归 EMPTY（tick=0），本测试不跑 Post / the snapshot
        // resets to EMPTY (tick=0); this test runs no Post pass
        List<String> lines = ConsoleProtocol.respond("stale", A);
        assertEquals(2, lines.size());
        assertTrue(lines.get(1).contains("尚未汇总"), "明确说尚未汇总 / explicitly says not yet summarized: " + lines.get(1));
    }

    @Test
    @DisplayName("采集开：hosts 计数列取真实快照累计（同端到端管线）")
    void hostsCountColumnRidesTheRealSnapshotWhenGateOn() {
        TopoStats.configureCollection(true);
        TopoStats.clearCollection();
        TopoStats.hostLoaded(A, () -> TopoStats.ChannelCensus.EMPTY);
        String key = SignalBus.runInDimension(DIM, () -> SignalBus.PORT.channelKey("alpha"));
        TopoStats.beginCollectTick();
        TopoStats.beginEvalContext(A);
        TopoStats.noteChannelWrite(key, "", 5f);
        TopoStats.noteChannelRead(key, "", 5f);
        TopoStats.noteChannelWrite(key, "", 7f);
        TopoStats.endEvalContext(null);
        TopoStats.postCollectSummary();

        List<String> lines = ConsoleProtocol.respond("hosts");
        String row = lines.stream().filter(l -> l.contains(String.valueOf(A))).findFirst().orElseThrow();
        assertTrue(row.contains("①=1") && row.contains("②=1") && row.contains("同=1") && row.contains("跨=0"),
            "hosts 计数列 = 真实快照累计 / the hosts count column = the real snapshot's cumulative: " + row);
        assertTrue(lines.get(0).contains("快照 tick=") && !lines.get(0).contains("快照 tick=0"),
            "表头带推进后的快照 tick / the header carries the advanced snapshot tick");
    }
}
