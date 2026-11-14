package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import io.github.y15173334444.create_schematic_compute.graph.TopoStats;
import io.github.y15173334444.create_schematic_compute.graph.TopoStats.ChannelCensus;
import io.github.y15173334444.create_schematic_compute.graph.TopoStats.CollectSnapshot;
import io.github.y15173334444.create_schematic_compute.graph.TopoStats.HostGraph;
import io.github.y15173334444.create_schematic_compute.graph.TopoStats.HostId;
import io.github.y15173334444.create_schematic_compute.graph.TopoStats.StaleCounts;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 控制台数据命令（{@code stale}/{@code topo}/{@code ring}/{@code bus}/{@code hosts}）接线批的
 * headless 锚（§4.3 命令集 / issue #29）——纯 JDK 直测，零 MC 注册表依赖（BlockPos 只是纯数据
 * 类，{@code TopoStatsCollectTest} 同款用法）。这里锁死：
 * <ul>
 *   <li><b>respond 分发逐条替换</b>：五条命令不再回「尚未接线」，而是挂上真实数据源
 *       （采集快照 / 按 tick 缓存的依赖图 / 维度作用域内的总线表）；{@code perf} 是唯一
 *       留守的未接线命令；help 表标注随接线更新（已接线行无标注、perf 保留标注）。</li>
 *   <li><b>行形状 / 分组 / 排序</b>：{@code stale} 按通道分组 + 跨度 + 合计（合成快照喂真实
 *       {@link CollectSnapshot} 记录构造器——不另造平行形状）；{@code topo} 读集 ← 写者 /
 *       写集 → 读者按通道配对、悬空读者与自环如实标注；{@code ring} 环成员 + 环上通道、
 *       无环图明确回「无环」而非空行；{@code hosts} 清单序 + running（普查非空即入图）+
 *       陈旧计数列（采集关 = 「—」）。全部输出全序确定（两次调用逐行相等）。</li>
 *   <li><b>维度作用域读取路径（issue #29 硬口径）</b>：{@code bus} 的值/占用读取经
 *       {@link SignalBus#runInDimension} 落宿主键空间——同逻辑名在两维度各植一个值，各宿主
 *       各读到各维度的值，无作用域键空间不被观测。</li>
 *   <li><b>bus 字段来源逐项</b>：值 = BUS 载体 internalMap（频段 → 浮点）/ PRIVATE 载体
 *       SIGNALS；owner = 占用表；冲突 = 被拒注册候选（{@code CLAIMANTS} 经真实
 *       {@link SignalBus#registerChannel} 冲突路径产生）；订阅者数 = 依赖图内该通道的读者
 *       宿主数（结构性口径）。</li>
 *   <li><b>钳制不回退（§4.4 第 3 层）</b>：放大宿主数后的全局命令行经
 *       {@link ConsoleProtocol#clampResponse} 仍是 200 行硬上界 + 截断标记、前缀保留。</li>
 *   <li><b>按 tick 脏缓存（§4.4 第 2 层）</b>：同一 tick 内重复查询共享同一图实例（普查供给器
 *       不重入）；tick 推进后重算且反映最新普查。</li>
 *   <li><b>可诊断性</b>：未解析到宿主 / 未登记 / 停机（空普查）/ 无通道 / 无陈旧记录 / 快照
 *       未汇总，各有明确回执行而非空数据或假数据。</li>
 * </ul>
 *
 * <p>Headless anchors for the console data-command wiring batch ({@code stale}/{@code topo}/
 * {@code ring}/{@code bus}/{@code hosts}; §4.3 command set / issue #29) — pure JDK, no MC
 * registry dependency ({@link BlockPos} is pure data, the same usage as
 * {@code TopoStatsCollectTest}). Pinned here:</p>
 * <ul>
 *   <li><b>The dispatch replacements row by row</b>: the five commands no longer answer "not
 *       wired yet" — they ride their real data sources (the collection snapshot / the per-tick
 *       cached dependency graph / the bus tables inside the dimension scope); {@code perf} is
 *       the only command left unwired; the help-table annotations follow (wired rows carry no
 *       annotation, {@code perf} keeps its).</li>
 *   <li><b>Line shapes / grouping / ordering</b>: {@code stale} groups by channel with spans
 *       and a total (the synthetic snapshot feeds the real {@link CollectSnapshot} record
 *       constructor — no parallel shape invented); {@code topo} pairs reads ← writers and
 *       writes → readers per channel, honestly marking dangling readers and self-loops;
 *       {@code ring} lists members + on-ring channels and an acyclic graph explicitly answers
 *       "acyclic", never an empty reply; {@code hosts} lists with running (= census non-empty =
 *       in graph) and the stale-count column ("—" while collection is off). Every output is
 *       totally ordered (two calls replay line-for-line).</li>
 *   <li><b>The dimension-scope read path (the issue #29 hard rule)</b>: {@code bus}'s value/
 *       occupancy reads land in the host's keyspace through {@link SignalBus#runInDimension} —
 *       one logical name planted with a different value per dimension, each host reads its own
 *       dimension's value, and the scope-free keyspace is never observed.</li>
 *   <li><b>The bus fields, source by source</b>: value = the BUS carrier's internalMap
 *       (band → float) / the PRIVATE carrier's SIGNALS entry; owner = the occupancy tables;
 *       conflict = refused-registration claimants (produced by the real
 *       {@link SignalBus#registerChannel} conflict path); subscribers = the channel's
 *       reader-host count within the dependency graph (the structural measure).</li>
 *   <li><b>The clamps never regress (§4.4 layer 3)</b>: the global commands' lines under
 *       amplified host counts still clamp to the 200-line hard cap + marker, prefix kept,
 *       through {@link ConsoleProtocol#clampResponse}.</li>
 *   <li><b>The per-tick dirty cache (§4.4 layer 2)</b>: repeated queries within one tick share
 *       one graph instance (the census providers are not re-invoked); a tick advance rebuilds
 *       and reflects the latest censuses.</li>
 *   <li><b>Diagnosability</b>: unresolved host / unregistered / stopped (empty census) /
 *       no channels / no stale records / snapshot not yet summarized — each has an explicit
 *       reply line instead of empty or fabricated data.</li>
 * </ul>
 */
@DisplayName("ConsoleDataPlane + 接线分发：五条数据命令的 headless 锚 / ConsoleDataPlane + wired dispatch: the five data commands, headless")
class ConsoleDataCommandsTest {

    private static final String DIM = "csc_test:dim";
    private static final String DIM2 = "csc_test:dim_b";
    private static final HostId A = new HostId(DIM, 1, 2, 3);
    private static final HostId B = new HostId(DIM, 10, 2, 3);
    private static final HostId C = new HostId(DIM, 20, 2, 3);
    private static final HostId STOPPED = new HostId(DIM, 30, 2, 3);
    private static final HostId A2 = new HostId(DIM2, 1, 2, 3);

    @BeforeEach
    @AfterEach
    void resetStatics() {
        // 注册表 / 总线表 / 缓存全部复位（进程级静态，测试间互不渗漏；采集门不由本文件改——
        // 门控相关的锚在 graph 包的 ConsoleStaleDiagnosabilityTest 里，那里能碰到包内测试面）。
        // Reset the registry / bus tables / cache (process-level statics, no cross-test
        // leakage; this file never touches the collection gate — the gate anchors live in the
        // graph package's ConsoleStaleDiagnosabilityTest, which can reach the package-private
        // test face).
        TopoStats.clearRegistry();
        SignalBus.clear();
        ConsoleQueryCache.reset();
        // §5.3 切边计划面复位（空方案 = 无标记；graph 包的锚负责有切边的语义面）。
        // §5.3's cut-plan face reset (the empty plan = no marks; the graph package's anchors
        // own the with-cuts semantics).
        io.github.y15173334444.create_schematic_compute.graph.ChannelCuts
            .beginPass(io.github.y15173334444.create_schematic_compute.graph.CutEdgePlanner.CutPlan.EMPTY);
    }

    // ── 夹具 / fixtures ──────────────────────────────────────────────────────────────

    /** 经真实枚举胶水（{@link HostChannelCensus#census}，自带维度作用域）从图产普查——键
     *  即前缀形态，与生产 DAG 边标签逐位同源。 / A census produced through the real
     *  enumeration glue ({@link HostChannelCensus#census}, which carries the dimension scope
     *  itself) — the keys are the prefixed form, bit-identical in source to the production
     *  DAG's edge labels. */
    private static ChannelCensus censusOf(NodeGraph g, String dimension) {
        return HostChannelCensus.census(g, dimension);
    }

    /** 通道节点的快捷构造（仅 signalName 参与普查）。 / A channel-node shortcut (only
     *  signalName participates in the census). */
    private static void channel(NodeGraph g, NodeType type, String name, String... bands) {
        var n = g.addNode(type, 0, 0);
        n.signalName = name;
        if (bands.length > 0) n.signalBands = new ArrayList<>(List.of(bands));
    }

    private static NodeGraph graph(java.util.function.Consumer<NodeGraph> fn) {
        var g = new NodeGraph();
        fn.accept(g);
        return g;
    }

    /** 前缀通道键（真实单一源：作用域内经 {@code SignalBus.key} 归一）。 / The prefixed
     *  channel key (the real single source: normalized through {@code SignalBus.key} inside
     *  the scope). */
    private static String keyOf(String logical) {
        return SignalBus.runInDimension(DIM, () -> SignalBus.key(logical));
    }

    /** 合成采集快照（真实 {@link CollectSnapshot} 记录构造器 + 有序表——快照的键序是渲染
     *  排序的输入，不可丢）。 / A synthetic collection snapshot (the real {@link CollectSnapshot}
     *  record constructor + ordered maps — the snapshot's key order feeds the render ordering
     *  and must not be lost). */
    private static CollectSnapshot snapshot(long tick, Map<HostId, StaleCounts> byHost,
                                            Map<HostId, Map<String, StaleCounts>> byHostChannel) {
        return new CollectSnapshot(tick,
            java.util.Collections.unmodifiableMap(new TreeMap<>(byHost)),
            java.util.Collections.unmodifiableMap(new TreeMap<>(byHostChannel)),
            java.util.Collections.unmodifiableSortedSet(new TreeSet<>()), 0L, 0L);
    }

    // ══════════ 1. 目标宿主解析 / target-host resolution ══════════

    @Test
    @DisplayName("resolveHost：查询维精确匹配优先，跨维度同坐标回退，未命中 = null")
    void resolveHostPrefersTheQuerierDimensionAndFallsBackAcrossDimensions() {
        // A 在 (5,5,5)、A2 在另一维度同坐标 (1,2,3)——回退面才可单独观测
        // A at (5,5,5), A2 at the same coordinates (1,2,3) in the other dimension — the
        // fallback face becomes independently observable
        HostId a5 = new HostId(DIM, 5, 5, 5);
        TopoStats.hostLoaded(a5, () -> ChannelCensus.EMPTY);
        TopoStats.hostLoaded(A2, () -> ChannelCensus.EMPTY);
        // 查询维精确命中 / exact match in the querying dimension
        assertEquals(a5, ConsoleDataPlane.resolveHost(DIM, 5, 5, 5));
        assertEquals(A2, ConsoleDataPlane.resolveHost(DIM2, 1, 2, 3));
        // 查询维无此坐标的宿主 → 同坐标跨维度回退（查询点确是那台宿主） / no host at those
        // coordinates in the querying dimension → the cross-dimension fallback by position
        assertEquals(A2, ConsoleDataPlane.resolveHost(DIM, 1, 2, 3));
        // 未命中 = null（渲染可诊断回执，绝不猜） / no match = null (diagnosable reply, never guessed)
        assertNull(ConsoleDataPlane.resolveHost(DIM, 9, 9, 9));
    }

    // ══════════ 2. stale：行形状 / 分组 / 排序 / 可诊断性 ══════════

    @Test
    @DisplayName("stale 按通道分组 + 跨度 + 合计：合成快照走真实 CollectSnapshot 构造器，输出全序确定")
    void staleGroupsByChannelWithSpansAndTotalReplayingIdentically() {
        var host = A;
        String alpha = keyOf("alpha");
        String beta = keyOf("beta");
        var byHost = new TreeMap<HostId, StaleCounts>();
        byHost.put(host, new StaleCounts(4, 1, 3, 1));
        var byChannel = new TreeMap<HostId, Map<String, StaleCounts>>();
        var channels = new TreeMap<String, StaleCounts>();
        channels.put(alpha, new StaleCounts(3, 1, 2, 1));
        channels.put(beta, new StaleCounts(1, 0, 1, 0));
        byChannel.put(host, channels);
        var snap = snapshot(1234L, byHost, byChannel);

        List<String> lines = ConsoleDataPlane.renderStale(host, true, snap);
        assertEquals(4, lines.size(), "表头 + 两通道行 + 合计行 / header + two channel rows + total");
        assertTrue(lines.get(0).contains("stale") && lines.get(0).contains(String.valueOf(host)),
            "表头回显宿主 / the header echoes the host: " + lines.get(0));
        assertTrue(lines.get(0).contains("tick=1234"), "快照 tick 入表头 / the snapshot tick rides the header");
        // 通道行显示逻辑名（前缀经 SignalBus.logicalName 还原——单一源的逆映射），计数与跨度齐全
        // Channel rows show logical names (the prefix stripped via SignalBus.logicalName — the
        // single source's inverse), with all four counters present
        assertTrue(lines.get(1).contains("alpha") && !lines.get(1).contains("\u0001"),
            "显示逻辑名不显示前缀 / the logical name shows, never the prefix: " + lines.get(1));
        assertTrue(lines.get(1).contains("①=3") && lines.get(1).contains("②=1")
            && lines.get(1).contains("同tick=2") && lines.get(1).contains("跨tick=1"),
            "①/② + 同/跨 tick 跨度逐项 / ①/② and the same/cross-tick spans item by item: " + lines.get(1));
        assertTrue(lines.get(2).contains("beta") && lines.get(2).contains("①=1"));
        assertTrue(lines.get(3).contains("①=4") && lines.get(3).contains("②=1")
            && lines.get(3).contains("同tick=3") && lines.get(3).contains("跨tick=1"),
            "合计行 = byHost 累计 / the total row = the byHost cumulative: " + lines.get(3));
        // 输出全序确定（通道键自然序） / totally ordered output (channel-key natural order)
        assertEquals(lines, ConsoleDataPlane.renderStale(host, true, snap), "两次渲染逐行相等 / two renders replay identically");
        // 通道按前缀键自然序排列：alpha 在 beta 前 / channels ordered by prefixed key: alpha before beta
        assertTrue(lines.get(1).contains("alpha") && lines.get(2).contains("beta"));
    }

    @Test
    @DisplayName("stale 可诊断性：无记录 / 快照未汇总 / 无目标宿主，各有明确回执")
    void staleDiagnosabilityLines() {
        var snapWithTick = snapshot(77L, Map.of(), Map.of());
        String noRecords = ConsoleDataPlane.renderStale(A, true, snapWithTick).get(1);
        assertTrue(noRecords.contains("无陈旧读记录"), "门开 + 有快照 + 无记录 = 明说 / gate on + snapshot + no records says so: " + noRecords);

        String notSummarized = ConsoleDataPlane.renderStale(A, true, CollectSnapshot.EMPTY).get(1);
        assertTrue(notSummarized.contains("尚未汇总"), "快照 tick=0 = 尚未汇总 / snapshot tick=0 says not-yet-summarized: " + notSummarized);

        String noTarget = ConsoleDataPlane.renderStale(null, true, snapWithTick).get(0);
        assertTrue(noTarget.contains("未解析到登记宿主"),
            "无目标宿主 = 可诊断回执 / no target host = a diagnosable reply: " + noTarget);
    }

    // ══════════ 3. topo：读写集 + 上下游 / topo: read-write set + upstream/downstream ══════════

    @Test
    @DisplayName("topo 读写集按通道配对上下游：悬空读者 / 多写者排序 / 自环注记 / 输出全序确定")
    void topoPairsUpstreamDownstreamPerChannelAndReplaysIdentically() {
        var gA = graph(g -> {
            channel(g, NodeType.BUS_OUT, "alpha");
            channel(g, NodeType.BUS_IN, "beta");
            channel(g, NodeType.PRIVATE_IN, "ghost");   // 悬空读者 / dangling reader
        });
        var gB = graph(g -> {
            channel(g, NodeType.BUS_OUT, "beta");
            channel(g, NodeType.BUS_IN, "alpha");
        });
        var gC = graph(g -> {
            channel(g, NodeType.BUS_OUT, "beta");       // beta 第二写者（多写者排序面）/
            channel(g, NodeType.BUS_IN, "alpha");       // beta's second writer (the multi-writer ordering)
        });
        TopoStats.hostLoaded(A, () -> censusOf(gA, DIM));
        TopoStats.hostLoaded(B, () -> censusOf(gB, DIM));
        TopoStats.hostLoaded(C, () -> censusOf(gC, DIM));
        ConsoleQueryCache.reset();

        List<String> lines = ConsoleProtocol.respond("topo", A);
        assertTrue(lines.get(0).contains("topo") && lines.get(0).contains(String.valueOf(A)),
            "表头回显宿主 / the header echoes the host");
        assertTrue(lines.get(0).contains("在图"), "普查非空 = 在图 / census non-empty = in graph");
        // 读集：beta ← {B, C}（写者坐标 + 通道名，写者按 HostId 全序）；ghost 无写者如实标注
        // reads: beta ← {B, C} (writer coordinates + channel name, writers in HostId total
        // order); ghost honestly marked writer-less
        String betaRow = lines.stream().filter(l -> l.contains("beta ←")).findFirst().orElseThrow();
        int ib = betaRow.indexOf(String.valueOf(B));
        int ic = betaRow.indexOf(String.valueOf(C));
        assertTrue(ib >= 0 && ic > ib, "beta 的上游 = {B, C} 按 HostId 全序 / beta's upstream = {B, C} in HostId order: " + betaRow);
        String ghostRow = lines.stream().filter(l -> l.contains("ghost ←")).findFirst().orElseThrow();
        assertTrue(ghostRow.contains("无写者"), "悬空读者如实标注 / the dangling reader is marked: " + ghostRow);
        // 写集：alpha → 读者 {B, C} 按 HostId 全序 / writes: alpha → readers {B, C} in HostId order
        String alphaRow = lines.stream().filter(l -> l.contains("alpha →")).findFirst().orElseThrow();
        int ib2 = alphaRow.indexOf(String.valueOf(B));
        int ic2 = alphaRow.indexOf(String.valueOf(C));
        assertTrue(ib2 >= 0 && ic2 > ib2, "alpha 的下游 = {B, C} 按 HostId 全序 / alpha's downstream = {B, C} in HostId order: " + alphaRow);
        // 输出全序确定 / totally ordered output
        assertEquals(lines, ConsoleProtocol.respond("topo", A));
    }

    @Test
    @DisplayName("topo 自环：读自己写的通道——注记 + 自身出现在两侧")
    void topoAnnotatesASelfLoop() {
        var g = graph(gg -> {
            channel(gg, NodeType.BUS_OUT, "solo");
            channel(gg, NodeType.BUS_IN, "solo");   // 主图与 ENCAP 子图同通道的 §3.2 推论
        });
        TopoStats.hostLoaded(A, () -> censusOf(g, DIM));
        ConsoleQueryCache.reset();
        List<String> lines = ConsoleProtocol.respond("topo", A);
        String soloWrite = lines.stream().filter(l -> l.contains("solo →")).findFirst().orElseThrow();
        assertTrue(soloWrite.contains(String.valueOf(A)), "自环 = 自身是读者 / the self-loop makes the host its own reader");
        String soloRead = lines.stream().filter(l -> l.contains("solo ←")).findFirst().orElseThrow();
        assertTrue(soloRead.contains(String.valueOf(A)), "自环 = 自身是写者 / the self-loop makes the host its own writer");
        assertTrue(lines.stream().anyMatch(l -> l.contains("自环")), "自环注记行 / the self-loop note line");
    }

    @Test
    @DisplayName("topo 可诊断性：停机（空普查）/ 未登记 / 无目标，各有明确回执")
    void topoDiagnosabilityLines() {
        var gA = graph(g -> channel(g, NodeType.BUS_OUT, "alpha"));
        var gS = new NodeGraph();   // 空图 → 空普查 / empty graph → empty census
        TopoStats.hostLoaded(A, () -> censusOf(gA, DIM));
        TopoStats.hostLoaded(STOPPED, () -> censusOf(gS, DIM));
        ConsoleQueryCache.reset();

        String stopped = ConsoleProtocol.respond("topo", STOPPED).get(0);
        assertTrue(stopped.contains("停机或空图宿主"), "§3.2 分母外宿主明说 / the out-of-denominator host says so: " + stopped);
        String unregistered = ConsoleProtocol.respond("topo", new HostId(DIM, 99, 9, 9)).get(0);
        assertTrue(unregistered.contains("未登记"), "注册表外身份明说 / an identity outside the registry says so: " + unregistered);
        String noTarget = ConsoleProtocol.respond("topo").get(0);
        assertTrue(noTarget.contains("未解析到登记宿主"), "单参形态无目标 / the single-arg shape has no target: " + noTarget);
    }

    // ══════════ 4. ring：SCC 环列表 / ring: the SCC list ══════════

    @Test
    @DisplayName("ring 无环图明确回「无环（DAG）」而非空行——切边唯一输入的可诊断性")
    void ringOnAnAcyclicGraphExplicitlySaysAcyclic() {
        var gA = graph(g -> channel(g, NodeType.BUS_OUT, "x"));
        var gB = graph(g -> channel(g, NodeType.BUS_IN, "x"));
        TopoStats.hostLoaded(A, () -> censusOf(gA, DIM));
        TopoStats.hostLoaded(B, () -> censusOf(gB, DIM));
        ConsoleQueryCache.reset();

        List<String> lines = ConsoleProtocol.respond("ring");
        assertEquals(2, lines.size(), "表头 + 无环行 / header + the acyclic line");
        assertTrue(lines.get(0).contains("宿主 2") && lines.get(0).contains("边 1"), "表头带全局计数 / the header carries the global counts: " + lines.get(0));
        assertTrue(lines.get(1).contains("无环"), "明确回「无环」/ explicitly acyclic: " + lines.get(1));
        assertTrue(lines.get(1).contains("DAG"));
    }

    @Test
    @DisplayName("ring 环成员 + 环上通道名：两节点互写环与自环单例都列出，输出全序确定")
    void ringListsCycleMembersAndOnRingChannelsReplayingIdentically() {
        var gA = graph(g -> { channel(g, NodeType.BUS_OUT, "x"); channel(g, NodeType.BUS_IN, "y"); });
        var gB = graph(g -> { channel(g, NodeType.BUS_OUT, "y"); channel(g, NodeType.BUS_IN, "x"); });
        var gD = graph(g -> { channel(g, NodeType.BUS_OUT, "solo"); channel(g, NodeType.BUS_IN, "solo"); });
        var gE = graph(g -> channel(g, NodeType.BUS_OUT, "tail"));  // 环外尾巴 / a tail outside the cycles
        HostId D = new HostId(DIM, 40, 2, 3);
        HostId E = new HostId(DIM, 50, 2, 3);
        TopoStats.hostLoaded(A, () -> censusOf(gA, DIM));
        TopoStats.hostLoaded(B, () -> censusOf(gB, DIM));
        TopoStats.hostLoaded(D, () -> censusOf(gD, DIM));
        TopoStats.hostLoaded(E, () -> censusOf(gE, DIM));
        ConsoleQueryCache.reset();

        List<String> lines = ConsoleProtocol.respond("ring");
        assertTrue(lines.get(0).contains("宿主 4") && lines.get(0).contains("边 3"),
            "入图宿主 4（尾巴非空普查也入图）；边 = x,y 两方向 + solo 自环（tail 无读者不产生边）= 3 / "
                + "4 in-graph hosts; edges = x,y both ways + the solo self-loop (a reader-less tail produces no edge) = 3: " + lines.get(0));
        // 环分量按最小成员排序：{A,B} 在 {D} 前 / components ordered by smallest member: {A,B} before {D}
        String cycle1 = lines.stream().filter(l -> l.contains("环1")).findFirst().orElseThrow();
        assertTrue(cycle1.contains(String.valueOf(A)) && cycle1.contains(String.valueOf(B)),
            "环1 = {A,B} / cycle 1 = {A,B}: " + cycle1);
        assertTrue(!cycle1.contains(String.valueOf(D)), "尾巴 D 不在 {A,B} 里 / the tail D stays out");
        String chans1 = lines.get(lines.indexOf(cycle1) + 1);
        assertTrue(chans1.contains("x") && chans1.contains("y"), "环上通道 = 分量内边的通道 / on-ring channels = the intra-component edges': " + chans1);
        String cycle2 = lines.stream().filter(l -> l.contains("环2")).findFirst().orElseThrow();
        assertTrue(cycle2.contains(String.valueOf(D)), "自环单例是真环 / the self-loop singleton is a genuine cycle: " + cycle2);
        assertTrue(lines.get(lines.indexOf(cycle2) + 1).contains("solo"), "自环通道在环上 / the self-loop's channel is on the ring");
        // 输出全序确定 / totally ordered output
        assertEquals(lines, ConsoleProtocol.respond("ring"));
    }

    // ══════════ 5. bus：字段来源逐项 + 维度作用域 / bus: the fields source by source + the
    //             dimension scope ══════════

    @Test
    @DisplayName("bus 字段逐项：BUS 载体频段值 / PRIVATE 载体值 / owner / 订阅者数 / 冲突标记（真实注册冲突路径）")
    void busRendersEachFieldFromItsRealSource() {
        var gA = graph(g -> {
            channel(g, NodeType.BUS_OUT, "alpha", "a", "b");
            channel(g, NodeType.PRIVATE_OUT, "pv");
            channel(g, NodeType.BUS_IN, "beta");
            channel(g, NodeType.PRIVATE_IN, "pr");
        });
        var gB = graph(g -> { channel(g, NodeType.BUS_OUT, "beta"); channel(g, NodeType.BUS_IN, "alpha"); });
        TopoStats.hostLoaded(A, () -> censusOf(gA, DIM));
        TopoStats.hostLoaded(B, () -> censusOf(gB, DIM));
        ConsoleQueryCache.reset();

        // 植入服务端总线状态（全部真实公开 API，在宿主维度作用域内）
        // Plant the server bus state (all real public APIs, inside the host's dimension scope)
        SignalBus.runInDimension(DIM, () -> {
            var map = new HashMap<String, Float>();
            map.put("a", 1.0f);
            map.put("b", 2.0f);
            assertTrue(SignalBus.registerChannel("alpha", map, new ChannelOwner(new BlockPos(1, 2, 3), 7)));
            assertTrue(SignalBus.registerChannel("beta", new HashMap<>(), new ChannelOwner(new BlockPos(10, 2, 3), 9)));
            assertTrue(SignalBus.registerPrivateChannel("pv", new ChannelOwner(new BlockPos(1, 2, 3), 5)));
            SignalBus.put("pv", 0.5f);
        });

        List<String> lines = ConsoleProtocol.respond("bus", A);
        String alphaRow = lines.stream().filter(l -> l.contains("[BUS] alpha")).findFirst().orElseThrow();
        assertTrue(alphaRow.contains("a=1.0，b=2.0"), "BUS 载体 = 频段 → 浮点 internalMap / the BUS carrier = band → float internalMap: " + alphaRow);
        assertTrue(alphaRow.contains("owner: [1, 2, 3]#7"),
            "owner = 占用表（BlockPos.toShortString 带空格）/ the owner = the occupancy table (BlockPos.toShortString carries spaces)");
        assertTrue(alphaRow.contains("订阅: 1"), "订阅者数 = 图内读者宿主数（B 读 alpha）/ subscribers = reader hosts in the graph (B reads alpha): " + alphaRow);
        assertTrue(alphaRow.contains("冲突: —"), "无候选 = 无冲突 / no claimants = no conflict");

        String pvRow = lines.stream().filter(l -> l.contains("[PRIVATE] pv")).findFirst().orElseThrow();
        assertTrue(pvRow.contains("0.5"), "PRIVATE 载体 = SIGNALS 值 / the PRIVATE carrier = the SIGNALS value: " + pvRow);
        assertTrue(pvRow.contains("owner: [1, 2, 3]#5"));

        String prRow = lines.stream().filter(l -> l.contains("[PRIVATE] pr")).findFirst().orElseThrow();
        assertTrue(prRow.contains("值: —") && prRow.contains("owner: —"),
            "从未写入的通道诚实显示缺失 / a never-written channel honestly shows the absence: " + prRow);

        // 行按通道键全序：alpha < beta < pr < pv / rows in channel-key total order
        int iAlpha = lines.indexOf(alphaRow);
        assertTrue(lines.get(iAlpha + 1).contains("[BUS] beta"), "读通道也在列（B 的注册） / read channels list too (B's registration)");
        assertTrue(lines.get(iAlpha + 2).contains("[PRIVATE] pr") && lines.get(iAlpha + 3).contains("[PRIVATE] pv"),
            "行序 = 前缀键自然序 / row order = the prefixed-key natural order");
        assertEquals(lines, ConsoleProtocol.respond("bus", A), "输出全序确定 / totally ordered output");

        // 冲突标记：第二 owner 真实注册被拒 → 候选入 CLAIMANTS → 标记翻转
        // The conflict mark: a second owner's real registration refused → a claimant enters
        // CLAIMANTS → the mark flips
        SignalBus.runInDimension(DIM, () ->
            assertFalse(SignalBus.registerChannel("alpha", new HashMap<>(), new ChannelOwner(new BlockPos(99, 9, 9), 42)),
                "不同 owner 撞名 = 注册被拒 / a foreign owner's registration is refused"));
        List<String> conflicted = ConsoleProtocol.respond("bus", A);
        String conflictedAlpha = conflicted.stream().filter(l -> l.contains("[BUS] alpha")).findFirst().orElseThrow();
        assertTrue(conflictedAlpha.contains("冲突: 有"), "冲突标记 = 被拒注册候选在场 / the conflict mark = refused-registration claimants present: " + conflictedAlpha);
    }

    @Test
    @DisplayName("维度作用域读取路径（issue #29 硬口径）：同逻辑名两维度各植各值，各宿主读各维度的；无作用域键空间不被观测")
    void busReadsLandInTheHostDimensionScope() {
        var gA = graph(g -> channel(g, NodeType.PRIVATE_IN, "pv"));
        var gA2 = graph(g -> channel(g, NodeType.PRIVATE_IN, "pv"));
        TopoStats.hostLoaded(A, () -> censusOf(gA, DIM));
        TopoStats.hostLoaded(A2, () -> censusOf(gA2, DIM2));
        ConsoleQueryCache.reset();

        // 同一逻辑名 "pv" 在两个维度各植一个值（作用域内经 key() 落各自键空间）
        // The same logical name "pv" planted with a different value per dimension (each lands
        // in its own keyspace through key() inside the scope)
        SignalBus.runInDimension(DIM, () -> SignalBus.put("pv", 1.0f));
        SignalBus.runInDimension(DIM2, () -> SignalBus.put("pv", 9.9f));

        String rowA = ConsoleProtocol.respond("bus", A).stream()
            .filter(l -> l.contains("pv")).findFirst().orElseThrow();
        assertTrue(rowA.contains("1.0") && !rowA.contains("9.9"),
            "DIM 宿主读 DIM 的值 / the DIM host reads DIM's value: " + rowA);
        String rowA2 = ConsoleProtocol.respond("bus", A2).stream()
            .filter(l -> l.contains("pv")).findFirst().orElseThrow();
        assertTrue(rowA2.contains("9.9") && !rowA2.contains("1.0"),
            "DIM2 宿主读 DIM2 的值 / the DIM2 host reads DIM2's value: " + rowA2);
        // 无作用域键空间不被观测（查询结束作用域必恢复，unscoped 视图为空）
        // The scope-free keyspace is never observed (the scope is restored after the query;
        // the unscoped view stays empty)
        assertEquals(0f, SignalBus.get("pv"), "无作用域读取落在空键空间 / the unscoped read lands in the empty keyspace");
        // 植入值真的在各自前缀键下 / the planted values really sit under their prefixed keys
        assertEquals(1.0f, SignalBus.runInDimension(DIM, () -> SignalBus.get("pv")));
        assertEquals(9.9f, SignalBus.runInDimension(DIM2, () -> SignalBus.get("pv")));
    }

    @Test
    @DisplayName("bus 可诊断性：停机宿主无通道 / 无目标宿主，明确回执而非空数据")
    void busDiagnosabilityLines() {
        TopoStats.hostLoaded(STOPPED, () -> ChannelCensus.EMPTY);
        ConsoleQueryCache.reset();
        List<String> lines = ConsoleProtocol.respond("bus", STOPPED);
        assertEquals(2, lines.size());
        assertTrue(lines.get(1).contains("无通道"), "§3.2 口径：停机宿主无通道明说 / the §3.2 criterion: a stopped host says it has no channels: " + lines.get(1));
        assertTrue(ConsoleProtocol.respond("bus").get(0).contains("未解析到登记宿主"),
            "单参形态无目标 / the single-arg shape has no target");
    }

    // ══════════ 6. hosts：清单 / running / 陈旧计数列 / hosts: the listing / running /
    //             the stale-count column ══════════

    @Test
    @DisplayName("hosts 清单序 + running（普查非空即入图）+ 采集关时计数列诚实显示「—」")
    void hostsListsRegistryWithRunningFlagsAndDashCountsWhileGateOff() {
        var gA = graph(g -> channel(g, NodeType.BUS_OUT, "alpha"));
        TopoStats.hostLoaded(A, () -> censusOf(gA, DIM));
        TopoStats.hostLoaded(STOPPED, () -> ChannelCensus.EMPTY);
        ConsoleQueryCache.reset();

        List<String> lines = ConsoleProtocol.respond("hosts");
        assertEquals(3, lines.size(), "表头 + 两行 / header + two rows");
        assertTrue(lines.get(0).contains("登记宿主 2"), "表头带总数 / the header carries the total: " + lines.get(0));
        assertTrue(lines.get(0).contains("采集未启用"), "采集关 = 表头明说（可诊断性）/ gate off = the header says so (diagnosability)");
        // 清单序 = HostId 全序：A(1,2,3) 在 STOPPED(30,2,3) 前 / the listing order = the HostId
        // total order: A(1,2,3) before STOPPED(30,2,3)
        String rowA = lines.get(1);
        String rowS = lines.get(2);
        assertTrue(rowA.startsWith("  1. ") && rowA.contains(String.valueOf(A)) && rowA.contains("running=yes"),
            "普查非空 = running / census non-empty = running: " + rowA);
        assertTrue(rowS.startsWith("  2. ") && rowS.contains(String.valueOf(STOPPED)) && rowS.contains("running=no"),
            "空普查 = 不入图 / an empty census stays out of the graph: " + rowS);
        assertTrue(rowA.contains("①=—") && rowA.contains("跨=—"), "采集关计数列 = 「—」（不伪造零）/ gate off renders the count column as \"—\" (no fabricated zeros): " + rowA);
        assertEquals(lines, ConsoleProtocol.respond("hosts"), "输出全序确定 / totally ordered output");
    }

    @Test
    @DisplayName("hosts 采集开时计数列取真实快照累计（渲染面直接喂快照）")
    void hostsCountColumnRidesTheSnapshotWhenCollecting() {
        var byHost = new TreeMap<HostId, StaleCounts>();
        byHost.put(A, new StaleCounts(7, 2, 5, 2));
        var snap = snapshot(555L, byHost, Map.of());
        var hosts = new TreeMap<HostId, TopoStats.CensusProvider>();
        hosts.put(A, () -> ChannelCensus.EMPTY);
        List<String> lines = ConsoleDataPlane.renderHosts(hosts, TopoStats.buildHostGraph(Map.of()), true, snap, Map.of());
        String row = lines.get(1);
        assertTrue(row.contains("①=7") && row.contains("②=2") && row.contains("同=5") && row.contains("跨=2"),
            "计数列 = byHost 累计 / the count column = the byHost cumulative: " + row);
        assertTrue(lines.get(0).contains("tick=555"), "表头带快照 tick / the header carries the snapshot tick");
        // 快照无记录的宿主 = 全零（不「—」——采集开着零就是零）/ a host without records = zeros
        // (not "—" — with the gate on, zero means zero)
        var snap2 = snapshot(556L, Map.of(), Map.of());
        String row2 = ConsoleDataPlane.renderHosts(hosts, TopoStats.buildHostGraph(Map.of()), true, snap2, Map.of()).get(1);
        assertTrue(row2.contains("①=0") && row2.contains("②=0"), "无记录 = 全零 / no records = all zeros: " + row2);
    }

    @Test
    @DisplayName("hosts 空注册表明确回执")
    void hostsEmptyRegistrySaysSo() {
        List<String> lines = ConsoleProtocol.respond("hosts");
        assertTrue(lines.get(1).contains("注册表为空"), "空表明说 / an empty registry says so: " + lines.get(1));
    }

    // ══════════ 6b. §5.3 被切边的展示面（ring / bus 标记） ══════════

    @Test
    @DisplayName("ring 标记被切通道：被切边通道旁注「跨tick」，环内其余通道不带标记")
    void ringAnnotatesCutChannels() {
        var gA = graph(g -> { channel(g, NodeType.BUS_OUT, "x"); channel(g, NodeType.BUS_IN, "y"); });
        var gB = graph(g -> { channel(g, NodeType.BUS_OUT, "y"); channel(g, NodeType.BUS_IN, "x"); });
        TopoStats.hostLoaded(A, () -> censusOf(gA, DIM));
        TopoStats.hostLoaded(B, () -> censusOf(gB, DIM));
        ConsoleQueryCache.reset();
        // 兜底全序最小 = x 的 A→B 边 → 读者 B 对 x 的读被切。
        // The fallback total-order minimum = x's A→B edge → reader B's read of x is cut.
        var plan = io.github.y15173334444.create_schematic_compute.graph.CutEdgePlanner
            .plan(ConsoleQueryCache.hostGraph());
        io.github.y15173334444.create_schematic_compute.graph.ChannelCuts.beginPass(plan);

        List<String> lines = ConsoleProtocol.respond("ring");
        String chans = lines.stream().filter(l -> l.contains("环上通道")).findFirst().orElseThrow();
        assertTrue(chans.contains("x〔跨tick / cross-tick〕"), "被切通道带标记 / the cut channel carries the mark: " + chans);
        assertFalse(chans.contains("y〔跨tick"), "未切通道不带标记 / the uncut channel carries none: " + chans);
        // 空方案复位后标记消失（展示面跟随当前计划）。/ After resetting to the empty plan the
        // marks vanish (the display face follows the current plan).
        io.github.y15173334444.create_schematic_compute.graph.ChannelCuts
            .beginPass(io.github.y15173334444.create_schematic_compute.graph.CutEdgePlanner.CutPlan.EMPTY);
        String chansPlain = ConsoleProtocol.respond("ring").stream()
            .filter(l -> l.contains("环上通道")).findFirst().orElseThrow();
        assertFalse(chansPlain.contains("跨tick"), "无切边 = 无标记 / no cuts = no marks: " + chansPlain);
    }

    @Test
    @DisplayName("bus 标记被切行：被切读者的行带「跨tick」，写者/其余行不带")
    void busMarksCutRowsOnlyForTheCutReader() {
        var gA = graph(g -> { channel(g, NodeType.BUS_OUT, "x"); channel(g, NodeType.BUS_IN, "y"); });
        var gB = graph(g -> { channel(g, NodeType.BUS_OUT, "y"); channel(g, NodeType.BUS_IN, "x"); });
        TopoStats.hostLoaded(A, () -> censusOf(gA, DIM));
        TopoStats.hostLoaded(B, () -> censusOf(gB, DIM));
        ConsoleQueryCache.reset();
        var plan = io.github.y15173334444.create_schematic_compute.graph.CutEdgePlanner
            .plan(ConsoleQueryCache.hostGraph());
        io.github.y15173334444.create_schematic_compute.graph.ChannelCuts.beginPass(plan);

        // B 读 x，且 (x, A, B) 是被切边 → B 的 bus 行带标记；A 的行（写者视角）不带。
        // B reads x and (x, A, B) is the cut edge → B's bus row carries the mark; A's rows
        // (the writer's view) carry none.
        for (String row : ConsoleProtocol.respond("bus", B)) {
            if (row.contains("[BUS] x")) assertTrue(row.contains("〔跨tick / cross-tick〕"),
                "被切读者的行带标记 / the cut reader's row carries the mark: " + row);
        }
        for (String row : ConsoleProtocol.respond("bus", A)) {
            assertFalse(row.contains("跨tick"), "写者/其余行不带标记 / the writer's rows carry none: " + row);
        }
    }

    @Test
    @DisplayName("hosts 求值序号列：已完成遍的真实序号，遍内未求值者诚实显示「—」（条目 31.1）")
    void hostsEvalOrdinalColumnRidesThePassOrdinals() {
        var hosts = new TreeMap<HostId, TopoStats.CensusProvider>();
        hosts.put(A, () -> ChannelCensus.EMPTY);
        hosts.put(B, () -> ChannelCensus.EMPTY);
        // A=3、B=7 的求值序号（上一已完成遍）。/ Evaluation ordinals A=3, B=7 (the last
        // completed pass).
        var ordinals = new TreeMap<HostId, Integer>();
        ordinals.put(A, 3);
        ordinals.put(B, 7);
        List<String> lines = ConsoleDataPlane.renderHosts(hosts, TopoStats.buildHostGraph(Map.of()),
            false, TopoStats.CollectSnapshot.EMPTY, ordinals);
        assertTrue(lines.get(1).contains("序=3"), "A 的真实求值序号 / A's real evaluation ordinal: " + lines.get(1));
        assertTrue(lines.get(2).contains("序=7"), "B 的真实求值序号 / B's real evaluation ordinal: " + lines.get(2));
        // 无序号（本遍未求值：未走过前段 / 停机早退）= 诚实「—」，不伪造。
        // No ordinal (not evaluated this pass: the prologue never ran, or the stopped
        // early-return) = an honest "—", nothing fabricated.
        List<String> plain = ConsoleDataPlane.renderHosts(hosts, TopoStats.buildHostGraph(Map.of()),
            false, TopoStats.CollectSnapshot.EMPTY, Map.of());
        assertTrue(plain.get(1).contains("序=—"), "无序号 = 「—」 / no ordinal = \"—\": " + plain.get(1));
    }

    // ══════════ 7. 帮助表标注随接线更新 / the help-table annotations follow the wiring ══════════

    @Test
    @DisplayName("help 表：五条已接线命令无「未接线」标注，perf 保留标注，七条命令齐全")
    void helpTableAnnotationsFollowTheWiring() {
        List<String> help = ConsoleProtocol.respond("help");
        String joined = String.join("\n", help);
        for (String cmd : new String[]{"stale", "topo", "ring", "bus", "hosts"}) {
            var row = help.stream().filter(l -> l.contains("  " + cmd + "  ")).findFirst().orElseThrow();
            assertFalse(row.contains("未接线"), cmd + " 已接线，标注摘除 / " + cmd + " is wired — the annotation is gone: " + row);
        }
        var perfRow = help.stream().filter(l -> l.contains("  perf  ")).findFirst().orElseThrow();
        assertTrue(perfRow.contains("未接线"), "perf 留守标注 / perf keeps its annotation: " + perfRow);
        for (String cmd : new String[]{"help", "stale", "topo", "ring", "bus", "perf", "hosts"}) {
            assertTrue(joined.contains("  " + cmd + "  "), "七条命令齐全 / all seven commands listed: " + cmd);
        }
    }

    @Test
    @DisplayName("分发逐条替换：五条数据命令不再回「尚未接线」，perf 留守")
    void wiredCommandsNoLongerAnswerNotWiredYet() {
        // 门控关（本 JVM 默认）：stale 回采集未启用——也是数据面的真实回执，绝非「尚未接线」
        // Gate off (this JVM's default): stale answers the gate line — still a real data-plane
        // reply, never "not wired yet"
        assertFalse(ConsoleProtocol.respond("stale").get(0).contains("尚未接线"));
        assertFalse(ConsoleProtocol.respond("topo").get(0).contains("尚未接线"));
        assertFalse(ConsoleProtocol.respond("ring").get(0).contains("尚未接线"));
        assertFalse(ConsoleProtocol.respond("bus").get(0).contains("尚未接线"));
        assertFalse(ConsoleProtocol.respond("hosts").get(0).contains("尚未接线"));
        assertTrue(ConsoleProtocol.respond("perf").get(0).contains("尚未接线"), "perf 留守 / perf stays unwired");
    }

    // ══════════ 8. 钳制不回退（§4.4 第 3 层） / the clamps never regress (§4.4 layer 3) ══════════

    @Test
    @DisplayName("放大宿主数：hosts/ring 行超 200 → 钳制硬上界 + 截断标记 + 前缀保留")
    void amplifiedHostCountsStillClampToTheHardCaps() {
        // 250 台自环宿主：每台一个自环通道（ring 出 250 个环分量）+ 全部入 hosts 清单
        // 250 self-loop hosts: one self-loop channel each (ring reports 250 cycle components)
        // and all of them in the hosts listing
        for (int i = 0; i < 250; i++) {
            final int idx = i;
            var g = graph(gg -> {
                channel(gg, NodeType.BUS_OUT, "ch" + idx);
                channel(gg, NodeType.BUS_IN, "ch" + idx);
            });
            HostId h = new HostId(DIM, idx, 0, 0);
            TopoStats.hostLoaded(h, () -> censusOf(g, DIM));
        }
        ConsoleQueryCache.reset();

        List<String> hostsLines = ConsoleProtocol.respond("hosts");
        assertTrue(hostsLines.size() > ConsoleProtocol.MAX_RESPONSE_LINES, "放大面真实超界 / the amplified face really exceeds the cap: " + hostsLines.size());
        List<String> clampedHosts = ConsoleProtocol.clampResponse(hostsLines);
        // 行数与字节双上界都生效（本行宽下字节上界先触发）——断言锁定「有上界 + 截断标记 +
        // 前缀保留」，不锁定具体行数（那是钳制的实现细节，壳批锚已覆盖）。
        // Both the line and byte caps bind (the byte cap fires first at this row width) — the
        // assertions pin "capped + marker + prefix kept", not the exact count (an
        // implementation detail the shell-batch anchors already cover).
        assertTrue(clampedHosts.size() <= ConsoleProtocol.MAX_RESPONSE_LINES, "行数不超上界 / the line count stays under the cap: " + clampedHosts.size());
        assertTrue(clampedHosts.size() < hostsLines.size(), "确有截断 / truncation really happened");
        assertTrue(clampedHosts.get(0).contains("hosts"), "前缀保留（表头在前）/ the prefix is kept (header first)");
        assertTrue(clampedHosts.get(clampedHosts.size() - 1).contains("more"), "截断标记 / the truncation marker");

        List<String> ringLines = ConsoleProtocol.respond("ring");
        assertTrue(ringLines.size() > ConsoleProtocol.MAX_RESPONSE_LINES, "250 个环分量超界 / 250 cycle components exceed the cap");
        List<String> clampedRing = ConsoleProtocol.clampResponse(ringLines);
        assertTrue(clampedRing.size() <= ConsoleProtocol.MAX_RESPONSE_LINES);
        assertTrue(clampedRing.size() < ringLines.size(), "确有截断 / truncation really happened");
        assertTrue(clampedRing.get(0).contains("ring"), "表头前缀保留 / the header prefix kept");
        assertTrue(clampedRing.get(clampedRing.size() - 1).contains("more"));
    }

    // ══════════ 9. 按 tick 脏缓存（§4.4 第 2 层） / the per-tick dirty cache (§4.4 layer 2) ══════════

    @Test
    @DisplayName("脏缓存：同 tick 重复查询共享同一图实例（普查供给器不重入）；tick 推进重算并反映最新普查")
    void theCacheRebuildsAtMostOncePerTickAndReflectsFreshCensusesAfterAnAdvance() {
        // 供给器计数 + 换普查：A 第一次返回写者普查（A 写 x），tick 推进后换成读者普查（A 读
        // x）——B 恒写 x，边随之从 A→B 翻成 B→A。
        // A counting provider + a census swap: A returns a writer census first (A writes x),
        // and after a tick advance a reader one (A reads x) — B always writes x, so the edge
        // flips from A→B to B→A.
        AtomicInteger calls = new AtomicInteger();
        var gW = graph(g -> channel(g, NodeType.BUS_OUT, "x"));
        var gR = graph(g -> channel(g, NodeType.BUS_IN, "x"));
        var gBW = graph(g -> {
            channel(g, NodeType.BUS_OUT, "x");
            channel(g, NodeType.BUS_IN, "x");   // B 也读 x——tick 1 才有 A→B 边 / B also reads x,
            // so tick 1 has an A → B edge
        });
        TopoStats.hostLoaded(A, () -> calls.incrementAndGet() == 1 ? censusOf(gW, DIM) : censusOf(gR, DIM));
        TopoStats.hostLoaded(B, () -> censusOf(gBW, DIM));
        ConsoleQueryCache.reset();

        HostGraph g1 = ConsoleQueryCache.hostGraph();
        assertEquals(1, calls.get(), "首次查询现算 / the first query builds");
        assertSame(g1, ConsoleQueryCache.hostGraph(), "同 tick 再查询 = 同一实例（从缓存复制）");
        assertEquals(1, calls.get(), "缓存命中不重入供给器 / a cache hit never re-invokes the providers");

        ConsoleQueryCache.advanceTick();
        HostGraph g2 = ConsoleQueryCache.hostGraph();
        assertEquals(2, calls.get(), "tick 推进 = 重算 / a tick advance = rebuild");
        assertNotSame(g1, g2, "新图实例 / a fresh graph instance");
        assertTrue(g1.edges().stream().anyMatch(e -> e.writer().equals(A) && e.reader().equals(B)),
            "tick 1：A → B 边（A 写 x、B 读 x）/ tick 1: the A → B edge (A writes x, B reads x)");
        assertTrue(g2.edges().stream().anyMatch(e -> e.writer().equals(B) && e.reader().equals(A)),
            "tick 2：B → A 边（A 换读 x）/ tick 2: the B → A edge (A swapped to reading x)");
        assertFalse(g2.edges().stream().anyMatch(e -> e.writer().equals(A)),
            "tick 2：A 不再是写者 / tick 2: A is no longer a writer");
        // reset 后再查询重算（测试面） / after reset the next query rebuilds (the test face)
        ConsoleQueryCache.reset();
        ConsoleQueryCache.hostGraph();
        assertEquals(3, calls.get());
    }
}
