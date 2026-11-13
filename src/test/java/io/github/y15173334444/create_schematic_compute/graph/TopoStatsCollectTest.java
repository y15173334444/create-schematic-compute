package io.github.y15173334444.create_schematic_compute.graph;

import io.github.y15173334444.create_schematic_compute.network.ChannelOwner;
import io.github.y15173334444.create_schematic_compute.network.SignalBus;
import io.github.y15173334444.create_schematic_compute.graph.TopoStats.HostId;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 口径 A 采集的锚（§6.4 步骤 4 / issue #26）：epoch bump、①/② 判定、陈旧跨度（同 tick /
 * 跨 tick）、Post 汇总、门控（关 = 零行为 + 求值逐位一致）、受影响闭包（沿 DAG 下游 O(边数)
 * 传播）、currentHost 上下文贯穿 ENCAP 子 evaluator。全部纯 JDK / 纯求值器直测（核心逻辑与
 * MC 类解耦，{@code TopoStats} / {@code C2SPacketRateGuard} 先例）；全求值器场景经
 * {@code SignalBus.PORT}（无作用域线程 = 逻辑名键空间，{@code SignalBus.key} 无作用域口径）
 * 走真实挂钩路径。这里锁死：
 * <ul>
 *   <li>§3.1 判定规则逐条：读取落 {@code (key, epoch, value)} 记录、写入 bump；Post 对每条
 *       记录判 ①（epoch 被超越）与 ②（槽位当前值 ≠ 记录值）；判过的记录移除（一次判定不重复
 *       计），未被超越的滞留到后续 Post —— 跨 tick 被超越在那里的发现面；同一宿主同槽位重复
 *       读取只保留最新记录。</li>
 *   <li>验收场景（issue #26）：读者先写者后（同 tick）→ ① 记录；值不同 → ② 记录；无写者
 *       tick → 无记录。</li>
 *   <li>门控：{@code csc.topoCollect} 解析口径（缺席 = 关、{@code false}/{@code 0} = 关、
 *       其余含空串 = 开）；关闭时钩子全 no-op（epoch 表不增长、tick 戳不推进、快照恒 EMPTY）
 *       且全求值器场景的开/关两次求值<b>逐位一致</b>（无行为变更的锚）。</li>
 *   <li>受影响闭包：种子沿 {@code downstreamOf} 邻接向下游传播、多路径去重、种子不在图也计入
 *       输出、输出全序可复现。</li>
 *   <li>currentHost 上下文：ENCAP 子图内的读取记录归到<b>外层</b>宿主（同一静态延续，无需改
 *       求值重载）；无上下文不落记录（§3.3 采样语义闸门）。</li>
 *   <li>槽位语义：BUS 多频段通道按频段槽记账 —— ② 的值比较落在读者实际读的那一槽。</li>
 *   <li>生命周期卫生：{@code hostGone} 摘离场宿主的待判定记录；{@code clearCollection} 全归零。</li>
 * </ul>
 *
 * <p>Anchors for the criterion-A collection (§6.4 step 4 / issue #26): the epoch bump, the ①/②
 * judgement, the stale span (same tick / cross tick), the Post summary, the gate (off = zero
 * behaviour + bit-identical evaluation), the affected closure (downstream propagation over the
 * DAG, O(edge count)) and the currentHost context threading through ENCAP sub-evaluators.
 * Everything is tested directly on pure JDK / the pure evaluator (the core stays decoupled from
 * MC classes — the {@code TopoStats} / {@code C2SPacketRateGuard} precedent); the full-evaluator
 * scenarios ride {@code SignalBus.PORT} (an unscoped thread = the logical-name keyspace, the
 * {@code SignalBus.key} unscoped shape) through the real hook paths. Pinned here:</p>
 * <ul>
 *   <li>§3.1's rules one by one: a read drops a {@code (key, epoch, value)} record, a write
 *       bumps; the Post pass judges each record for ① (epoch superseded) and ② (the slot's
 *       current value ≠ the recorded value); judged records are removed (a record counts exactly
 *       once), un-superseded ones stay pending for later Posts — where cross-tick supersession
 *       is discovered; a repeated read of the same slot by the same host keeps only the newest
 *       record.</li>
 *   <li>The acceptance scenarios (issue #26): reader before writer (same tick) → a ① record;
 *       values differ → a ② record; a writer-less tick → no records.</li>
 *   <li>The gate: the {@code csc.topoCollect} parse shape (absent = off, {@code false}/{@code 0}
 *       = off, anything else including the empty string = on); disabled, every hook is a no-op
 *       (the epoch table never grows, the tick stamp never advances, the snapshot stays EMPTY)
 *       and two full-evaluator runs — off and on — evaluate <b>bit-identically</b> (the
 *       no-behaviour-change anchor).</li>
 *   <li>The affected closure: seeds propagate downstream over the {@code downstreamOf}
 *       adjacency, multi-path dedup, seeds outside the graph still count, the output is totally
 *       ordered and reproducible.</li>
 *   <li>The currentHost context: a read inside an ENCAP sub-graph attributes to the <b>outer</b>
 *       host (the same static continuation, no evaluation overloads changed); no record without
 *       a context (§3.3's sampling-semantics gate).</li>
 *   <li>Slot semantics: a multi-band BUS channel accounts per band slot — ②'s value compare
 *       lands on the very slot the reader read.</li>
 *   <li>Lifecycle hygiene: {@code hostGone} drops a departing host's pending records;
 *       {@code clearCollection} resets everything.</li>
 * </ul>
 */
@DisplayName("TopoStats 口径 A 采集 / TopoStats criterion-A collection")
class TopoStatsCollectTest {

    private static final HostId WRITER = new HostId("csc_test:dim", 1, 0, 0);
    private static final HostId READER = new HostId("csc_test:dim", 2, 0, 0);
    private static final HostId OTHER = new HostId("csc_test:dim", 3, 0, 0);

    @BeforeEach
    @AfterEach
    void resetState() {
        // 采集门与采集态、注册表、总线表全部复位 —— 门必须关回去（其他测试在门关的前提下跑）。
        // Reset the gate and collection state, the registry and the bus tables — the gate must
        // be turned back off (every other test runs under a closed gate).
        TopoStats.configureCollection(false);
        TopoStats.clearCollection();
        TopoStats.clearRegistry();
        TopoStats.endEvalContext(null);
        SignalBus.clear();
    }

    // ══════════ 1. 门控解析（csc.topoCollect） ══════════

    @Test
    @DisplayName("门控解析：缺席/0/false 关，空串（裸 -D）与其余值开")
    void gateParsingIsPinned() {
        assertFalse(TopoStats.parseCollectFlag(null), "属性缺席 = 关");
        assertFalse(TopoStats.parseCollectFlag("false"), "false = 关");
        assertFalse(TopoStats.parseCollectFlag("FALSE"), "大小写不敏感");
        assertFalse(TopoStats.parseCollectFlag("0"), "0 = 关");
        assertFalse(TopoStats.parseCollectFlag(" false "), "首尾空白容忍");
        assertTrue(TopoStats.parseCollectFlag(""), "空串（裸 -Dcsc.topoCollect）= 开");
        assertTrue(TopoStats.parseCollectFlag("true"), "true = 开");
        assertTrue(TopoStats.parseCollectFlag("1"), "1 = 开");
        assertTrue(TopoStats.parseCollectFlag("whatever"), "其余值按在场即开");
    }

    @Test
    @DisplayName("门控关 = 全钩子 no-op：epoch 表不增长、tick 戳不推进、快照恒 EMPTY")
    void gateOffMakesEveryHookANoOp() {
        TopoStats.beginCollectTick();
        TopoStats.beginEvalContext(READER);   // 上下文在场也无用 —— 钩子自身被门短路
        TopoStats.noteChannelRead("ch", "", 0f);
        TopoStats.noteChannelWrite("ch", "", 5f);
        TopoStats.postCollectSummary();

        assertEquals(0L, TopoStats.epochOf("ch"), "关闭路径不进 epoch 表");
        assertEquals(TopoStats.CollectSnapshot.EMPTY, TopoStats.collectionSnapshot(),
            "关闭路径不发布快照");
        assertEquals(0L, TopoStats.collectionSnapshot().tick(), "tick 戳未推进");
        assertEquals(READER, TopoStats.currentEvalHost(), "上下文本身可设（由调用方门控）；钩子不消费");
    }

    // ══════════ 2. 写入侧 bump（§3.1） ══════════

    @Test
    @DisplayName("写入侧 bump：epoch 单调递增、槽位值落表、未知通道 epoch=0")
    void writeSideBumpsEpochAndStoresSlotValue() {
        TopoStats.configureCollection(true);
        assertEquals(0L, TopoStats.epochOf("ch"), "无记录 = 0");

        TopoStats.noteChannelWrite("ch", "", 1.5f);
        assertEquals(1L, TopoStats.epochOf("ch"), "一次写入 bump 一次");
        TopoStats.noteChannelWrite("ch", "", 2.5f);
        TopoStats.noteChannelWrite("ch", "", 3.5f);
        assertEquals(3L, TopoStats.epochOf("ch"), "三次写入 epoch=3（单调递增）");

        TopoStats.noteChannelWrite("other", "", 9f);
        assertEquals(1L, TopoStats.epochOf("other"), "通道键互相独立");
        assertEquals(3L, TopoStats.epochOf("ch"), "其他通道不串 epoch");
    }

    // ══════════ 3. §3.1 判定 + 验收场景（读者先写者后） ══════════

    @Test
    @DisplayName("同 tick 读者先写者后：① 记录；值不同 → ② 记录；跨度 = 同 tick")
    void readerBeforeWriterSameTickYieldsStale1And2() {
        TopoStats.configureCollection(true);
        TopoStats.beginCollectTick();                       // tick 1
        TopoStats.beginEvalContext(READER);
        TopoStats.noteChannelRead("ch", "", 0f);            // 读者先读（epoch=0，值 0）
        TopoStats.endEvalContext(null);
        TopoStats.beginEvalContext(WRITER);
        TopoStats.noteChannelWrite("ch", "", 3.5f);         // 写者后写（epoch=1，值 3.5）
        TopoStats.endEvalContext(null);
        TopoStats.postCollectSummary();

        var snap = TopoStats.collectionSnapshot();
        assertEquals(1L, snap.tick(), "汇总落在当前 tick");
        assertEquals(new TopoStats.StaleCounts(1, 1, 1, 0), snap.byHost().get(READER),
            "① 一次 + ② 一次（0 ≠ 3.5）+ 同 tick 跨度一次");
        assertEquals(0, snap.byHost().getOrDefault(WRITER,
            new TopoStats.StaleCounts(0, 0, 0, 0)).staleReads(), "写者自己不产生 ①");
        assertEquals(new TopoStats.StaleCounts(1, 1, 1, 0),
            snap.byHostChannel().get(READER).get("ch"), "按通道分组的计数同形");
        assertEquals(1L, snap.seedsThisPass(), "本趟一个 ① 种子");
    }

    @Test
    @DisplayName("同值写入：① 记录但 ② 不记（② 用值对比滤掉一阶误报）")
    void sameValueSupersessionCountsStale1Only() {
        TopoStats.configureCollection(true);
        TopoStats.beginCollectTick();
        TopoStats.beginEvalContext(READER);
        TopoStats.noteChannelRead("ch", "", 2f);
        TopoStats.endEvalContext(null);
        TopoStats.beginEvalContext(WRITER);
        TopoStats.noteChannelWrite("ch", "", 2f);           // 同值重写：版本旧了但值没变
        TopoStats.endEvalContext(null);
        TopoStats.postCollectSummary();

        assertEquals(new TopoStats.StaleCounts(1, 0, 1, 0), TopoStats.collectionSnapshot().byHost().get(READER),
            "① 记录（输入是旧版本），② 不记（值相同 —— §3.1 局限注的诚实缺省）");
    }

    @Test
    @DisplayName("无写者 tick → 无记录：未被超越的记录滞留且不计数")
    void writerLessTickYieldsNoRecords() {
        TopoStats.configureCollection(true);
        TopoStats.beginCollectTick();
        TopoStats.beginEvalContext(READER);
        TopoStats.noteChannelRead("ch", "", 0f);
        TopoStats.endEvalContext(null);
        TopoStats.postCollectSummary();

        var snap = TopoStats.collectionSnapshot();
        assertEquals(0, snap.byHost().size(), "无写者 tick 无任何 ①/② 记录（验收口径）");
        assertEquals(0L, snap.seedsThisPass(), "无种子");
        assertEquals(0L, snap.closureTick(), "从未算过闭包");
        assertTrue(snap.affectedClosure().isEmpty(), "受影响集为空");
    }

    @Test
    @DisplayName("同宿主同槽位重复读取：只保留最新记录，判定一次")
    void repeatedReadsReplaceThePendingRecord() {
        TopoStats.configureCollection(true);
        TopoStats.beginCollectTick();
        TopoStats.beginEvalContext(READER);
        TopoStats.noteChannelRead("ch", "", 0f);
        TopoStats.noteChannelRead("ch", "", 1f);            // 同槽位重读：替换旧记录
        TopoStats.endEvalContext(null);
        TopoStats.beginEvalContext(WRITER);
        TopoStats.noteChannelWrite("ch", "", 9f);
        TopoStats.endEvalContext(null);
        TopoStats.postCollectSummary();

        assertEquals(new TopoStats.StaleCounts(1, 1, 1, 0), TopoStats.collectionSnapshot().byHost().get(READER),
            "旧记录已退役：判定恰好一次（不是两条记录各计一次）");
    }

    @Test
    @DisplayName("判过的记录移除：下一 Post 不重复计数")
    void judgedRecordsAreRemoved() {
        TopoStats.configureCollection(true);
        TopoStats.beginCollectTick();
        TopoStats.beginEvalContext(READER);
        TopoStats.noteChannelRead("ch", "", 0f);
        TopoStats.endEvalContext(null);
        TopoStats.beginEvalContext(WRITER);
        TopoStats.noteChannelWrite("ch", "", 5f);
        TopoStats.endEvalContext(null);
        TopoStats.postCollectSummary();

        TopoStats.beginCollectTick();
        TopoStats.noteChannelWrite("ch", "", 6f);           // 又一次写，但记录已判过移除
        TopoStats.postCollectSummary();
        assertEquals(new TopoStats.StaleCounts(1, 1, 1, 0), TopoStats.collectionSnapshot().byHost().get(READER),
            "累计计数不因后续写入重复增长");
    }

    // ══════════ 4. 陈旧跨度（同 tick / 跨 tick） ══════════

    @Test
    @DisplayName("跨 tick 被超越：记录在 tick1 落、写入在 tick2 → 跨度 = 跨 tick")
    void crossTickSupersessionIsDiscoveredAtTheLaterPost() {
        TopoStats.configureCollection(true);
        TopoStats.beginCollectTick();                       // tick 1：读者读，写者不动
        TopoStats.beginEvalContext(READER);
        TopoStats.noteChannelRead("ch", "", 0f);
        TopoStats.endEvalContext(null);
        TopoStats.postCollectSummary();                     // 未被超越：滞留、零计数

        TopoStats.beginCollectTick();                       // tick 2：写者写，读者没再读
        TopoStats.noteChannelWrite("ch", "", 5f);
        TopoStats.postCollectSummary();

        assertEquals(new TopoStats.StaleCounts(1, 1, 0, 1), TopoStats.collectionSnapshot().byHost().get(READER),
            "① + ②（0 ≠ 5）+ 跨 tick 跨度（记录 tick 1 < 汇总 tick 2）");
        assertEquals(2L, TopoStats.collectionSnapshot().closureTick(), "闭包在有种子的趟次（tick 2）现算");
    }

    // ══════════ 5. BUS 频段槽位语义 ══════════

    @Test
    @DisplayName("多频段 BUS：② 按槽位比较 —— b0 值未变不记 ②，b1 值变记 ②")
    void busBandSlotsCompareIndependently() {
        TopoStats.configureCollection(true);
        TopoStats.beginCollectTick();
        TopoStats.beginEvalContext(READER);
        TopoStats.noteChannelRead("bus", "b0", 1f);         // 读者读 b0=1、b1=1（旧版本）
        TopoStats.noteChannelRead("bus", "b1", 1f);
        TopoStats.endEvalContext(null);
        TopoStats.beginEvalContext(WRITER);
        TopoStats.noteChannelWrite("bus", "b0", 1f);        // b0 值没变
        TopoStats.noteChannelWrite("bus", "b1", 2f);        // b1 值变了
        TopoStats.endEvalContext(null);
        TopoStats.postCollectSummary();

        assertEquals(new TopoStats.StaleCounts(2, 1, 2, 0), TopoStats.collectionSnapshot().byHost().get(READER),
            "两个槽位各记 ①，只有值变了的 b1 记 ②");
    }

    // ══════════ 6. Post 汇总快照形状 ══════════

    @Test
    @DisplayName("快照不可变：计数表与受影响集拒绝写入")
    void snapshotIsImmutable() {
        TopoStats.configureCollection(true);
        TopoStats.beginCollectTick();
        TopoStats.beginEvalContext(READER);
        TopoStats.noteChannelRead("ch", "", 0f);
        TopoStats.endEvalContext(null);
        TopoStats.beginEvalContext(WRITER);
        TopoStats.noteChannelWrite("ch", "", 5f);
        TopoStats.endEvalContext(null);
        TopoStats.postCollectSummary();

        var snap = TopoStats.collectionSnapshot();
        assertThrows(UnsupportedOperationException.class, () -> snap.byHost().put(OTHER, null),
            "byHost 不可变");
        assertThrows(UnsupportedOperationException.class,
            () -> snap.byHostChannel().get(READER).put("x", null), "byHostChannel 内层不可变");
        assertThrows(UnsupportedOperationException.class, () -> snap.affectedClosure().add(OTHER),
            "受影响集不可变");
    }

    @Test
    @DisplayName("clearCollection 全归零：计数/epoch/记录/快照/tick 戳")
    void clearCollectionResetsEverything() {
        TopoStats.configureCollection(true);
        TopoStats.beginCollectTick();
        TopoStats.beginEvalContext(READER);
        TopoStats.noteChannelRead("ch", "", 0f);
        TopoStats.endEvalContext(null);
        TopoStats.beginEvalContext(WRITER);
        TopoStats.noteChannelWrite("ch", "", 5f);
        TopoStats.endEvalContext(null);
        TopoStats.postCollectSummary();

        TopoStats.clearCollection();
        assertEquals(TopoStats.CollectSnapshot.EMPTY, TopoStats.collectionSnapshot(), "快照归零");
        assertEquals(0L, TopoStats.epochOf("ch"), "epoch 表归零");
        TopoStats.beginCollectTick();
        TopoStats.beginEvalContext(READER);
        TopoStats.noteChannelRead("ch", "", 0f);            // epoch 已归零 → 记录 epoch=0
        TopoStats.endEvalContext(null);
        TopoStats.noteChannelWrite("ch", "", 1f);
        TopoStats.postCollectSummary();
        assertEquals(new TopoStats.StaleCounts(1, 1, 1, 0), TopoStats.collectionSnapshot().byHost().get(READER),
            "复位后从零重新累计（无残留）");
    }

    @Test
    @DisplayName("hostGone 摘离场宿主的待判定记录：离场者不再被后续写入判 ①")
    void hostGoneDropsPendingRecords() {
        TopoStats.configureCollection(true);
        TopoStats.beginCollectTick();
        TopoStats.beginEvalContext(READER);
        TopoStats.noteChannelRead("ch", "", 0f);
        TopoStats.endEvalContext(null);
        TopoStats.hostGone(READER);                          // 宿主离场（卸载/移除路径）
        TopoStats.noteChannelWrite("ch", "", 5f);
        TopoStats.postCollectSummary();

        assertFalse(TopoStats.collectionSnapshot().byHost().containsKey(READER),
            "离场宿主无记录可判 —— 不给已不存在的宿主累积跨 tick 噪声");
    }

    // ══════════ 7. 受影响闭包（§3.1 传递闭包，O(边数)） ══════════

    /** 链式图：A 写 ch1 → B 读 ch1 写 ch2 → C 读 ch2 写 ch3 → D 读 ch3。
     *  A chain: A writes ch1 → B reads ch1 writes ch2 → C reads ch2 writes ch3 → D reads ch3. */
    private static TopoStats.HostGraph chainGraph() {
        Map<HostId, TopoStats.ChannelCensus> census = new HashMap<>();
        census.put(WRITER, new TopoStats.ChannelCensus(Set.of(), Set.of("ch1")));
        census.put(READER, new TopoStats.ChannelCensus(Set.of("ch1"), Set.of("ch2")));
        HostId c = new HostId("csc_test:dim", 3, 0, 0);
        HostId d = new HostId("csc_test:dim", 4, 0, 0);
        census.put(c, new TopoStats.ChannelCensus(Set.of("ch2"), Set.of("ch3")));
        census.put(d, new TopoStats.ChannelCensus(Set.of("ch3"), Set.of()));
        return TopoStats.buildHostGraph(census);
    }

    @Test
    @DisplayName("闭包沿下游传播：种子 B → {B, C, D}；多跳覆盖全链；种子自身入集")
    void closurePropagatesDownstream() {
        var g = chainGraph();
        assertEquals(new TreeSet<>(List.of(READER,
                new HostId("csc_test:dim", 3, 0, 0), new HostId("csc_test:dim", 4, 0, 0))),
            TopoStats.affectedClosure(Set.of(READER), g),
            "B 的输入旧 → B、C、D 同批受影响（不重算，纯邻接传播）");
        var all = new TreeSet<>(List.of(WRITER, READER,
            new HostId("csc_test:dim", 3, 0, 0), new HostId("csc_test:dim", 4, 0, 0)));
        assertEquals(all, TopoStats.affectedClosure(Set.of(WRITER), g), "种子 A → 全链受影响");
    }

    @Test
    @DisplayName("闭包多路径去重 + 菱形图不重访 + 空种子空闭包")
    void closureDedupsDiamondAndHandlesEmptySeeds() {
        // 菱形：A → B、A → C、B → D、C → D（D 是两条路径的共同下游）。
        // Diamond: A → B, A → C, B → D, C → D (D is the shared downstream of both paths).
        Map<HostId, TopoStats.ChannelCensus> census = new HashMap<>();
        HostId b = new HostId("csc_test:dim", 2, 0, 0);
        HostId c = new HostId("csc_test:dim", 3, 0, 0);
        HostId d = new HostId("csc_test:dim", 4, 0, 0);
        census.put(WRITER, new TopoStats.ChannelCensus(Set.of(), Set.of("ch1", "ch2")));
        census.put(b, new TopoStats.ChannelCensus(Set.of("ch1"), Set.of("ch3")));
        census.put(c, new TopoStats.ChannelCensus(Set.of("ch2"), Set.of("ch4")));
        census.put(d, new TopoStats.ChannelCensus(Set.of("ch3", "ch4"), Set.of()));
        var g = TopoStats.buildHostGraph(census);

        var expected = new TreeSet<>(List.of(WRITER, b, c, d));
        assertEquals(expected, TopoStats.affectedClosure(Set.of(WRITER), g),
            "D 经 B、C 两条路径到达 —— 访问集去重，恰好一次入集");
        assertEquals(expected, TopoStats.affectedClosure(Set.of(WRITER), g),
            "两次调用输出全同（全序可复现）");
        assertTrue(TopoStats.affectedClosure(Set.of(), g).isEmpty(), "空种子 = 空闭包");
    }

    @Test
    @DisplayName("种子不在图内（停机后普查为空）也计入受影响集")
    void seedsOutsideTheGraphStillCount() {
        var g = chainGraph();
        // 专用图外种子：不能与链上宿主同坐标（OTHER 与链上 C 同 id，会沿下游传播）。
        // A dedicated outside seed: it must not share a position with a chain host (OTHER
        // shares C's id and would propagate downstream).
        HostId outsider = new HostId("csc_test:dim", 99, 0, 0);
        var out = TopoStats.affectedClosure(Set.of(outsider), g);
        assertEquals(Set.of(outsider), out, "图外的 ① 来源本身受影响（真实种子，不因停机丢失）");
    }

    // ══════════ 8. 全求值器场景（真实挂钩路径） ══════════

    /** 记录求值后的可观测面：读者节点输出 + 总线值。 / The observable face after an eval: the
     *  reader node's output + the bus value. */
    private record Observed(float readerOut, float busValue) {}

    /** PRIVATE 通道双宿主场景：writer 图 CONST(value) → PRIVATE_OUT(channel)；reader 图
     *  PRIVATE_IN(channel)。同构图每次新建（求值无状态，重建即同构）。
     *  The PRIVATE-channel two-host fixture: writer graph CONST(value) → PRIVATE_OUT(channel);
     *  reader graph PRIVATE_IN(channel). Isomorphic graphs are rebuilt each time (the eval is
     *  stateless, a rebuild is isomorphic). */
    private static GraphEvaluator privateWriterEval(float value, String channel) {
        var g = new NodeGraph();
        var src = g.addNode(NodeType.CONST, 0, 0);
        src.params[0] = value;
        var out = g.addNode(NodeType.PRIVATE_OUT, 10, 0);
        out.signalName = channel;
        g.addConnection(src.id, 0, out.id, 0);
        var ev = new GraphEvaluator(g);
        ev.setSignalBus(SignalBus.PORT);
        ev.restoreSubState(new RuntimeState());
        return ev;
    }

    private static GraphEvaluator privateReaderEval(String channel) {
        var g = new NodeGraph();
        var in = g.addNode(NodeType.PRIVATE_IN, 0, 0);
        in.signalName = channel;
        var ev = new GraphEvaluator(g);
        ev.setSignalBus(SignalBus.PORT);
        ev.restoreSubState(new RuntimeState());
        return ev;
    }

    /** 读者先写者后的一个 tick（服务端求值路径的 headless 形态：上下文仅在采集开启时围住
     *  每次求值 —— 与 GraphHost.tickBody ⑨ 的设/清逐字同构）。
     *  One reader-before-writer tick (the headless shape of the server evaluation path: the
     *  context wraps each evaluation only while collecting — verbatim isomorphic to
     *  GraphHost.tickBody ⑨'s set/clear). */
    private static Observed privateReaderFirstTick(GraphEvaluator writer, GraphEvaluator reader, String channel) {
        boolean ctxOn = TopoStats.collecting();
        if (ctxOn) TopoStats.beginEvalContext(READER);
        try {
            reader.evaluate(List.of(), Map.of(), 0.05f, new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));
        } finally {
            if (ctxOn) TopoStats.endEvalContext(null);
        }
        if (ctxOn) TopoStats.beginEvalContext(WRITER);
        try {
            writer.evaluate(List.of(), Map.of(), 0.05f, new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));
        } finally {
            if (ctxOn) TopoStats.endEvalContext(null);
        }
        return new Observed(reader.ctx.graph.nodes.get(0).outputValues[0], SignalBus.get(channel));
    }

    @Test
    @DisplayName("验收场景（全求值器）：读者先写者后同 tick → ① + ②；无写者 tick → 无记录")
    void fullEvaluatorReaderFirstWriterLater() {
        TopoStats.configureCollection(true);
        TopoStats.beginCollectTick();
        var writer = privateWriterEval(3.5f, "s");
        var reader = privateReaderEval("s");
        privateReaderFirstTick(writer, reader, "s");
        TopoStats.postCollectSummary();

        assertEquals(new TopoStats.StaleCounts(1, 1, 1, 0), TopoStats.collectionSnapshot().byHost().get(READER),
            "读者读到旧值（0），写者随后 bump —— ① + ② 同 tick");

        // 无写者 tick：读者重读（新记录 epoch=1 值 3.5），写者不动 → 无新记录。
        // A writer-less tick: the reader re-reads (a fresh record epoch=1 value 3.5), the
        // writer stays silent → no new records.
        TopoStats.beginCollectTick();
        TopoStats.beginEvalContext(READER);
        try {
            reader.evaluate(List.of(), Map.of(), 0.05f, new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));
        } finally {
            TopoStats.endEvalContext(null);
        }
        TopoStats.postCollectSummary();
        assertEquals(new TopoStats.StaleCounts(1, 1, 1, 0), TopoStats.collectionSnapshot().byHost().get(READER),
            "累计计数不增长 —— 无写者 tick 无记录（验收口径）");
    }

    @Test
    @DisplayName("门控开 = 求值逐位一致：开/关两次求值的读者输出与总线值完全相同")
    void collectionOnEvaluatesBitIdenticallyToOff() {
        // 关采集基线（无上下文、无 tick 推进）。 / The off baseline (no context, no tick stamp).
        var writerOff = privateWriterEval(3.5f, "s");
        var readerOff = privateReaderEval("s");
        Observed off = privateReaderFirstTick(writerOff, readerOff, "s");
        assertTrue(TopoStats.epochOf("s") == 0L, "关闭路径不进 epoch 表（钩子被门短路）");

        // 开采集同场景（有上下文、钩子真实落表）。 / The on run of the same scenario (context
        // set, hooks actually land in the tables).
        SignalBus.clear();
        TopoStats.configureCollection(true);
        TopoStats.beginCollectTick();
        var writerOn = privateWriterEval(3.5f, "s");
        var readerOn = privateReaderEval("s");
        Observed on = privateReaderFirstTick(writerOn, readerOn, "s");
        assertTrue(TopoStats.epochOf("s") > 0L, "开启路径钩子真实落表（bump 发生过）");
        TopoStats.postCollectSummary();
        assertEquals(new TopoStats.StaleCounts(1, 1, 1, 0), TopoStats.collectionSnapshot().byHost().get(READER),
            "开启路径确实采集（①/② 判定发生）");

        // 逐位一致：读者输出与总线值在开/关两档下完全相同 —— 采集是纯侧效应记账。
        // Bit-identical: the reader's output and the bus value match exactly across off/on —
        // the collection is pure side-effect accounting.
        assertEquals(off, on, "开采集与关采集的求值结果逐位一致（无行为变更锚）");
    }

    // ══════════ 9. currentHost 上下文贯穿 ENCAP 子 evaluator ══════════

    @Test
    @DisplayName("ENCAP 子图内的读取归到外层宿主（同一静态延续）")
    void encapSubReadAttributesToTheOuterHost() {
        TopoStats.configureCollection(true);
        TopoStats.beginCollectTick();
        // 主图 = 单封装节点；子图 = PRIVATE_IN "p"（封装求值走同一 eval switch，§3.2）。
        // Main graph = one encapsulation node; sub-graph = PRIVATE_IN "p" (encapsulated
        // evaluation rides the same eval switch, §3.2).
        var outer = new NodeGraph();
        var encap = outer.addNode(NodeType.ENCAPSULATION, 0, 0);
        var sub = new NodeGraph();
        var pin = sub.addNode(NodeType.PRIVATE_IN, 0, 0);
        pin.signalName = "p";
        encap.subGraph = sub;
        var ev = new GraphEvaluator(outer);
        ev.setSignalBus(SignalBus.PORT);
        ev.restoreSubState(new RuntimeState());

        TopoStats.beginEvalContext(WRITER);
        try {
            ev.evaluate(List.of(), Map.of(), 0.05f, new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));
        } finally {
            TopoStats.endEvalContext(null);
        }
        TopoStats.beginEvalContext(OTHER);   // 干扰宿主：不得收到子图记录
        try {
            TopoStats.noteChannelWrite("p", "", 1f);   // 子图记录被超越 → 判定面显现归属
        } finally {
            TopoStats.endEvalContext(null);
        }
        TopoStats.postCollectSummary();

        assertEquals(new TopoStats.StaleCounts(1, 1, 1, 0), TopoStats.collectionSnapshot().byHost().get(WRITER),
            "子 evaluator 的读取经同一静态上下文归到外层宿主 —— 无需改求值重载");
        assertFalse(TopoStats.collectionSnapshot().byHost().containsKey(OTHER),
            "干扰宿主无记录");
    }

    // ══════════ 10. BUS 全求值器场景（频段槽位经真实 internalMap） ══════════

    @Test
    @DisplayName("BUS 通道（全求值器）：读者先写者后 → b0 槽位 ① + ②；读者输出 = 旧值")
    void fullEvaluatorBusReaderFirstWriterLater() {
        TopoStats.configureCollection(true);
        TopoStats.beginCollectTick();
        // 写者：CONST(5) → BUS_OUT "bus" 频段 b0；读者：BUS_IN "bus" 频段 b0。频道注册进
        // 全局表（读者经 channelMap 读到写者的 internalMap —— 与生产同路径）。
        // Writer: CONST(5) → BUS_OUT "bus" band b0; reader: BUS_IN "bus" band b0. The channel
        // registers globally (the reader reaches the writer's internalMap through channelMap —
        // the production path).
        var wg = new NodeGraph();
        var src = wg.addNode(NodeType.CONST, 0, 0);
        src.params[0] = 5f;
        var busOut = wg.addNode(NodeType.BUS_OUT, 10, 0);
        busOut.signalName = "bus";
        busOut.signalBands = new ArrayList<>(List.of("b0"));
        busOut.busInternalMap = new HashMap<>();
        SignalBus.registerChannel("bus", busOut.busInternalMap,
            new ChannelOwner(new BlockPos(1, 0, 0), busOut.id));
        wg.connections.add(new NodeConnection(src.id, "float:0", 0, busOut.id, "float:b0", 0));
        var writer = new GraphEvaluator(wg);
        writer.setSignalBus(SignalBus.PORT);
        writer.restoreSubState(new RuntimeState());

        var rg = new NodeGraph();
        var busIn = rg.addNode(NodeType.BUS_IN, 0, 0);
        busIn.signalName = "bus";
        busIn.signalBands = new ArrayList<>(List.of("b0"));
        var reader = new GraphEvaluator(rg);
        reader.setSignalBus(SignalBus.PORT);
        reader.restoreSubState(new RuntimeState());

        TopoStats.beginEvalContext(READER);
        try {
            reader.evaluate(List.of(), Map.of(), 0.05f, new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));
        } finally {
            TopoStats.endEvalContext(null);
        }
        TopoStats.beginEvalContext(WRITER);
        try {
            writer.evaluate(List.of(), Map.of(), 0.05f, new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));
        } finally {
            TopoStats.endEvalContext(null);
        }
        TopoStats.postCollectSummary();

        assertEquals(0f, busIn.outputValues[0], 0.0001f,
            "读者先求值：读到的是写前的旧值（拾取窗口现象本身）");
        assertEquals(new TopoStats.StaleCounts(1, 1, 1, 0), TopoStats.collectionSnapshot().byHost().get(READER),
            "b0 槽位 ① + ②（旧值 0 ≠ 新值 5），同 tick 跨度");
    }
}
