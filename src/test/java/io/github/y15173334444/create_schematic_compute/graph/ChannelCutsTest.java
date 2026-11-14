package io.github.y15173334444.create_schematic_compute.graph;

import io.github.y15173334444.create_schematic_compute.graph.TopoStats.ChannelCensus;
import io.github.y15173334444.create_schematic_compute.graph.TopoStats.HostId;
import io.github.y15173334444.create_schematic_compute.network.ChannelOwner;
import io.github.y15173334444.create_schematic_compute.network.SignalBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * §5.3 被切边运行面的锚（每 tick 计划 + 上一 tick 末快照 + 读侧路由）：headless 直测，
 * 求值器经真实 {@code SignalBus.PORT}（包装后）读写——S3 的「被切边恒跨 tick、环外同 tick」
 * 在值语义层面成立。这里锁死：
 * <ul>
 *   <li><b>灵魂锚（headless 形态）</b>：A↔B 环 + 独立读者 C——被切读者（B）读上一 tick 末
 *       快照（本 tick 改写后仍是旧值），环外读者（C）读同 tick 现值，写者侧不受影响；
 *       两 tick 序贯验证（tick1 快照为空 → 读 0；tick2 快照 = tick1 遍末提交值）。</li>
 *   <li><b>BUS 载体快照</b>：自环宿主的 BUS_IN 被切后读频段映射快照（tick1 遍末值），
 *       活跃 CHANNELS 已是新值。</li>
 *   <li><b>口径 A 豁免</b>：被切读不落 ①/② 待判定记录（设计内跨 tick），环外活跃读照常
 *       记录——采集口径只量计划外陈旧。</li>
 *   <li><b>零开销快路</b>：无切边时 {@code wrap} 原样返回委托（同一引用）。</li>
 * </ul>
 *
 * <p>Anchors for the §5.3 cut-edge runtime face (the per-tick plan + the last-tick snapshot +
 * the read-side routing): headless, the evaluator rides the real wrapped {@code SignalBus.PORT}
 * — S3's "the cut edge is permanently cross-tick, everything outside it stays same-tick" holds
 * at the value-semantics level. Pinned here:</p>
 * <ul>
 *   <li><b>The soul anchor (headless form)</b>: an A↔B ring + an independent reader C — the
 *       cut reader (B) reads last tick's committed snapshot (still the old value after this
 *       tick's rewrite), the outside reader (C) reads this tick's live value, and the writer
 *       side is untouched; verified across two sequential ticks (tick 1's snapshot is empty →
 *       reads 0; tick 2's snapshot = tick 1's pass-tail committed values).</li>
 *   <li><b>The BUS-carrier snapshot</b>: a self-loop host's cut BUS_IN reads the band-map
 *       snapshot (the pass-tail value) while the live CHANNELS already carry the new value.</li>
 *   <li><b>The criterion-A exemption</b>: a cut read drops no ①/② pending record (cross-tick
 *       by design) while an outside live read records normally — the collection metric only
 *       measures unplanned staleness.</li>
 *   <li><b>The zero-overhead fast path</b>: with no cuts {@code wrap} returns the delegate
 *       as-is (the same reference).</li>
 * </ul>
 */
@DisplayName("ChannelCuts：§5.3 被切边运行面（快照路由）/ ChannelCuts: §5.3's cut-edge runtime face (snapshot routing)")
class ChannelCutsTest {

    private static final HostId HA = new HostId("csc_test:dim_a", 1, 2, 3);
    private static final HostId HB = new HostId("csc_test:dim_a", 10, 2, 3);
    private static final HostId HC = new HostId("csc_test:dim_a", 20, 2, 3);

    private static final String CHA = "chA";
    private static final String CHB = "chB";

    @BeforeEach @AfterEach
    void resetFaces() {
        ChannelCuts.resetForTest();
        // 采集门一并回落（本类的豁免锚把它打开——进程级静态，测试间不得渗漏）。
        // The collection gate falls back too (the exemption anchors here turn it on — a
        // process-level static must not leak across tests).
        TopoStats.configureCollection(false);
        TopoStats.clearRegistry();
        TopoStats.clearCollection();
        SignalBus.clear();
    }

    private static ChannelCensus rw(Set<String> reads, Set<String> writes) {
        return new ChannelCensus(reads, writes, Set.of());
    }

    /** A↔B 环 + 环外读者 C 的普查图。 / The census graph of the A↔B ring + the outside
     *  reader C. */
    private static TopoStats.HostGraph ringGraph() {
        var m = new HashMap<HostId, ChannelCensus>();
        m.put(HA, rw(Set.of(CHB), Set.of(CHA)));
        m.put(HB, rw(Set.of(CHA), Set.of(CHB)));
        m.put(HC, rw(Set.of(CHA), Set.of()));
        return TopoStats.buildHostGraph(m);
    }

    /** 装一个 PRIVATE 载体宿主图：CONST(param) → 可选 PRIVATE_OUT(name) + 可选 PRIVATE_IN
     *  集合；返回求值器与各读节点。 / Wire one PRIVATE-carrier host graph: CONST(param) →
     *  an optional PRIVATE_OUT(name) + optional PRIVATE_IN set; returns the evaluator and the
     *  read nodes. */
    private record HostRig(GraphEvaluator evaluator, NodeGraph graph, GraphNode src,
                           Map<String, GraphNode> reads) { }

    private static HostRig privateHost(float constValue, String outName, String... inNames) {
        var g = new NodeGraph();
        var src = g.addNode(NodeType.CONST, 0, 0);
        src.params[0] = constValue;
        if (outName != null) {
            var out = g.addNode(NodeType.PRIVATE_OUT, 100, 0);
            out.signalName = outName;
            g.addConnection(src.id, 0, out.id, 0);
        }
        var reads = new HashMap<String, GraphNode>();
        int x = 200;
        for (String in : inNames) {
            var node = g.addNode(NodeType.PRIVATE_IN, x, 0);
            node.signalName = in;
            x += 100;
            reads.put(in, node);
        }
        var ev = new GraphEvaluator(g);
        ev.setSignalBus(SignalBus.PORT);
        return new HostRig(ev, g, src, reads);
    }

    private static void evaluate(HostRig rig, HostId host) {
        TopoStats.beginEvalContext(host);
        try {
            rig.evaluator().evaluate(List.of(), Map.of(), 0.05f,
                new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));
        } finally {
            TopoStats.endEvalContext(null);
        }
    }

    // ══════════ 灵魂锚：被切读者跨 tick、环外读者同 tick ══════════

    @Test
    @DisplayName("S3 灵魂锚：被切读者 B 读上 tick 末快照（本 tick 改写不影响），环外读者 C 读同 tick 现值")
    void cutReaderReadsPrevTickSnapshotLiveReaderStaysLive() {
        var g = ringGraph();
        var plan = CutEdgePlanner.plan(g);
        // 兜底全序最小 = (chA, A, B)：读者 B 被切；C 读 chA（环外）不被切。
        // The fallback total-order minimum = (chA, A, B): reader B is cut; C's outside read
        // of chA is not.
        assertEquals(1, plan.cutEdges().size());
        assertEquals(HB, plan.cutEdges().iterator().next().reader(), "被切读者 = B");

        var a = privateHost(5f, CHA, CHB);          // A 写 chA、读 chB（环内非切边读）
        var b = privateHost(7f, CHB, CHA);          // B 写 chB、读 chA（被切读）
        var c = privateHost(0f, null, CHA);         // C 环外读 chA（活跃读）

        // tick 1：快照为空。B 的被切读 = 0（无上一 tick 提交值，缺省语义）；A 写 chA=5。
        // tick 1: the snapshot is empty. B's cut read = 0 (no last-tick committed value, the
        // default semantics); A writes chA=5.
        ChannelCuts.beginPass(plan);
        b.evaluator().setSignalBus(ChannelCuts.wrap(SignalBus.PORT));
        a.evaluator().setSignalBus(ChannelCuts.wrap(SignalBus.PORT));
        c.evaluator().setSignalBus(ChannelCuts.wrap(SignalBus.PORT));
        evaluate(a, HA);
        evaluate(b, HB);
        evaluate(c, HC);
        assertEquals(0f, b.evaluator().getNodeOutput(b.reads().get(CHA).id, 0), 1e-4,
            "tick1 被切读 = 快照缺席 → 0");
        // 遍终点快照捕获（HostEvalPass 的生产位置）：chA=5、chB=7。
        // The pass-tail snapshot capture (production site: HostEvalPass): chA=5, chB=7.
        var snap = new TreeMap<String, ChannelCuts.CutSample>();
        for (var e : plan.cutEdges()) snap.putIfAbsent(e.channel(), SignalBus.sampleForSnapshot(e.channel()));
        ChannelCuts.installSnapshot(snap);

        // tick 2：A 的 CONST 改 9 → chA 现值 9；B 的被切读仍是 tick1 提交值 5；
        // C 的活跃读 = 9（同 tick 现值）；A 的环内非切边读 chB = 7（活跃同 tick）。
        // tick 2: A's CONST → 9, so chA's live value is 9; B's cut read still holds tick 1's
        // committed 5; C's live read = 9 (this tick's value); A's in-ring non-cut read of chB
        // = 7 (live, same tick).
        a.src().params[0] = 9f;
        ChannelCuts.beginPass(plan);
        evaluate(a, HA);
        evaluate(b, HB);
        evaluate(c, HC);
        assertEquals(5f, b.evaluator().getNodeOutput(b.reads().get(CHA).id, 0), 1e-4,
            "被切读者 B = 上 tick 末提交值 5（本 tick 的 9 不进来——恒定跨 tick）");
        assertEquals(9f, SignalBus.get(CHA), 1e-4, "总线现值 = 9（写侧实时）");
        assertEquals(9f, c.evaluator().getNodeOutput(c.reads().get(CHA).id, 0), 1e-4,
            "环外读者 C = 同 tick 现值 9（S2 语义不受切边影响）");
        assertEquals(7f, a.evaluator().getNodeOutput(a.reads().get(CHB).id, 0), 1e-4,
            "A 的环内非切边读 = 同 tick 现值（写者 B 已在拓扑序先求值）");
    }

    // ══════════ BUS 载体快照（自环分量） ══════════

    @Test
    @DisplayName("BUS 载体：自环宿主的被切 BUS_IN 读频段映射快照（tick1 遍末值），活跃表已新值")
    void busCarrierCutReadRidesTheSnapshot() {
        // 自环宿主：BUS_OUT busCh(频段 b0) 与 BUS_IN busCh 同图（读自己写的通道，§3.2 推论）。
        // The self-loop host: BUS_OUT busCh (band b0) + BUS_IN busCh in one graph (reads a
        // channel it writes, the §3.2 corollary).
        var g = new NodeGraph();
        var src = g.addNode(NodeType.CONST, 0, 0);
        src.params[0] = 11f;
        var out = g.addNode(NodeType.BUS_OUT, 100, 0);
        out.signalName = "busCh";
        out.signalBands = new java.util.ArrayList<>(List.of("b0"));
        g.addConnection(src.id, 0, out.id, 0);
        var in = g.addNode(NodeType.BUS_IN, 200, 0);
        in.signalName = "busCh";
        in.signalBands = new java.util.ArrayList<>(List.of("b0"));
        var ev = new GraphEvaluator(g);
        ev.setSignalBus(SignalBus.PORT);

        var m = new HashMap<HostId, ChannelCensus>();
        m.put(HA, rw(Set.of("busCh"), Set.of("busCh")));
        var graph = TopoStats.buildHostGraph(m);
        var plan = CutEdgePlanner.plan(graph);
        assertEquals(1, plan.cutEdges().size(), "自环分量恰切一条");
        assertEquals(HA, plan.cutEdges().iterator().next().reader());

        // CHANNELS 注册（生产走宿主 tick ① 的 BusChannelHelper；headless 手工注册同一公开面）：
        // internalMap 引用与 BUS_OUT 节点共享——求值写入立即可见。
        // The CHANNELS registration (production rides the host tick ① via BusChannelHelper;
        // headless registers through the same public face by hand): the internalMap reference
        // is shared with the BUS_OUT node — eval writes are immediately visible.
        assertTrue(SignalBus.registerChannel("busCh", out.busInternalMap == null
                ? (out.busInternalMap = new java.util.HashMap<>()) : out.busInternalMap,
            new ChannelOwner(null, out.id)), "手工注册 busCh");

        ev.setSignalBus(ChannelCuts.wrap(SignalBus.PORT));
        // tick 1：被切读 = 快照缺席 → 0；写 b0=11；遍末快照捕获 busCh。
        // tick 1: the cut read = a snapshot miss → 0; writes b0=11; the pass-tail capture.
        ChannelCuts.beginPass(plan);
        TopoStats.beginEvalContext(HA);
        try { ev.evaluate(List.of(), Map.of(), 0.05f, new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0)); }
        finally { TopoStats.endEvalContext(null); }
        assertEquals(0f, ev.getNodeOutput(in.id, 0), 1e-4, "tick1 被切 BUS 读 = 0");
        var snap = new TreeMap<String, ChannelCuts.CutSample>();
        for (var e : plan.cutEdges()) snap.putIfAbsent(e.channel(), SignalBus.sampleForSnapshot(e.channel()));
        ChannelCuts.installSnapshot(snap);

        // tick 2：CONST → 22；被切 BUS_IN 读 tick1 频段快照 b0=11（活跃表已是 22）。
        // tick 2: CONST → 22; the cut BUS_IN reads tick 1's band snapshot b0=11 (the live
        // table already carries 22).
        src.params[0] = 22f;
        ChannelCuts.beginPass(plan);
        TopoStats.beginEvalContext(HA);
        try { ev.evaluate(List.of(), Map.of(), 0.05f, new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0)); }
        finally { TopoStats.endEvalContext(null); }
        assertEquals(11f, ev.getNodeOutput(in.id, 0), 1e-4, "被切 BUS 读 = 上 tick 频段快照 11");
        assertEquals(22f, SignalBus.getChannel("busCh").internalMap.get("b0"), 1e-4, "活跃表 = 22");
    }

    // ══════════ 口径 A 豁免 + 零开销快路 ══════════

    @Test
    @DisplayName("口径 A 豁免：被切读不落 ①/② 记录（设计内跨 tick），活跃读被后续写超越时照常记录")
    void cutReadsAreExemptFromCriterionA() {
        TopoStats.configureCollection(true);
        // D = chA 的第二写者（在 C 读之后写）——C 的活跃读被同 tick 后续写超越 → ① 有据。
        // D = a second chA writer (writes after C's read) — C's live read is superseded by a
        // later same-tick write → a genuine ①.
        var m = new HashMap<HostId, ChannelCensus>();
        m.put(HA, rw(Set.of(CHB), Set.of(CHA)));
        m.put(HB, rw(Set.of(CHA), Set.of(CHB)));
        m.put(HC, rw(Set.of(CHA), Set.of()));
        m.put(new HostId("csc_test:dim_a", 30, 2, 3), rw(Set.of(), Set.of(CHA)));
        var plan = CutEdgePlanner.plan(TopoStats.buildHostGraph(m));
        assertEquals(1, plan.cutEdges().size(), "SCC 面不变（D 在环外）");
        ChannelCuts.beginPass(plan);
        var a = privateHost(5f, CHA, CHB);
        var b = privateHost(7f, CHB, CHA);
        var c = privateHost(0f, null, CHA);
        var d = privateHost(11f, CHA);
        a.evaluator().setSignalBus(ChannelCuts.wrap(SignalBus.PORT));
        b.evaluator().setSignalBus(ChannelCuts.wrap(SignalBus.PORT));
        c.evaluator().setSignalBus(ChannelCuts.wrap(SignalBus.PORT));
        d.evaluator().setSignalBus(ChannelCuts.wrap(SignalBus.PORT));
        evaluate(a, HA);
        evaluate(b, HB);
        evaluate(c, HC);
        evaluate(d, new HostId("csc_test:dim_a", 30, 2, 3));

        TopoStats.postCollectSummary();
        var snap = TopoStats.collectionSnapshot();
        // B（被切读者）无任何记录；C（活跃读者）被 D 的后续写超越 → ① 有据——采集口径
        // 只量计划外陈旧。/ B (the cut reader) carries no record; C (the live reader) was
        // superseded by D's later write → a genuine ① — the collection metric only measures
        // unplanned staleness.
        assertEquals(0L, snap.byHost().getOrDefault(HB, new TopoStats.StaleCounts(0, 0, 0, 0)).staleReads(),
            "被切读者的读不入 ① 口径（设计内跨 tick）");
        assertTrue(snap.byHost().getOrDefault(HC, new TopoStats.StaleCounts(0, 0, 0, 0)).staleReads() > 0,
            "环外活跃读者的照常入 ① 口径（本 tick 被 D 的写超越）");
    }

    @Test
    @DisplayName("零开销快路：无切边时 wrap 原样返回同一引用；有切边返回包装")
    void wrapReturnsTheDelegateWhenNoCuts() {
        SignalBusPort port = SignalBus.PORT;
        assertSame(port, ChannelCuts.wrap(port), "空方案 = 原端口（零包装）");
        var g = TopoStats.buildHostGraph(Map.of(
            HA, rw(Set.of("chA"), Set.of("chA"))));
        ChannelCuts.beginPass(CutEdgePlanner.plan(g));
        assertNotSame(port, ChannelCuts.wrap(port), "有切边 = 快照感知包装");
    }
}
