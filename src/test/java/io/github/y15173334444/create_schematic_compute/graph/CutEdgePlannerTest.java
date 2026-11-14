package io.github.y15173334444.create_schematic_compute.graph;

import io.github.y15173334444.create_schematic_compute.graph.TopoStats.ChannelCensus;
import io.github.y15173334444.create_schematic_compute.graph.TopoStats.ChannelEdge;
import io.github.y15173334444.create_schematic_compute.graph.TopoStats.HostGraph;
import io.github.y15173334444.create_schematic_compute.graph.TopoStats.HostId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * §5.3 切边规划核心的锚（声明 (c) 为主 + 全序最小 (a) 兜底）：headless 纯数据直测（核心
 * 零 MC 依赖）。这里锁死：
 * <ul>
 *   <li><b>切边选择确定性</b>：同一输入两次构建输出全同（方案的硬锚——被切边必须可复现，
 *       不能随构建顺序抖动）。</li>
 *   <li><b>声明优先于兜底</b>：环内存在声明边（写者普查 feedbackWrites 覆盖）时切声明边，
 *       无声明时切 (通道,写者,读者) 全序最小边；多声明不叠加——仍恰切一条（取声明边中
 *       全序最小），多余切边只会增加固定延迟、对收敛零增益。</li>
 *   <li><b>每 SCC 恰切一条</b>：独立双环 → 恰两条（每分量一条）；自环分量切自己的边；
 *       无环图 = 空方案。</li>
 *   <li><b>序无关性（headless 形态）</b>：普查映射的迭代序（注册序的替身）不改变方案——
 *       被 GraphHost 构建面归一的边集决定一切；S2 直线链的求值序同样与插入序无关
 *       （{@code HostEvalOrderTest} 的确定性锚承接同一性质）。</li>
 *   <li><b>读者路由键面</b>：方案的 readerChannels 恰含被切读者 → 被切通道键。</li>
 *   <li><b>声明持久化</b>：{@code GraphNode.feedbackOut} 的 NBT roundtrip（键 {@code fb}，
 *       缺键 = 未声明——未发版形态不写迁移）。</li>
 * </ul>
 *
 * <p>Anchors for the §5.3 cut-edge planning core (declared (c) first + the total-order
 * minimum (a) fallback): headless on pure data (the core carries no MC dependency). Pinned
 * here:</p>
 * <ul>
 *   <li><b>Cut-selection determinism</b>: the same input builds identical output twice (the
 *       plan's hard anchor — the cut edge must be reproducible, never jitter with build
 *       order).</li>
 *   <li><b>Declared beats the fallback</b>: when the ring carries a declared edge (covered by
 *       the writer census's feedbackWrites) it is cut; with none, the (channel, writer,
 *       reader) total-order minimum is cut; multiple declarations never stack — still exactly
 *       one cut (the declared minimum), since extra cuts only add fixed latency with zero
 *       convergence gain.</li>
 *   <li><b>Exactly one cut per SCC</b>: two independent rings → exactly two cuts (one per
 *       component); a self-loop component cuts its own edge; an acyclic graph = the empty
 *       plan.</li>
 *   <li><b>Order independence (the headless form)</b>: the census map's iteration order (the
 *       stand-in for registration order) never changes the plan — the HostGraph build face
 *       normalizes the edge set; the S2 straight-chain order carries the same property
 *       (HostEvalOrderTest's determinism anchors).</li>
 *   <li><b>The reader routing key face</b>: the plan's readerChannels holds exactly the cut
 *       reader → its cut channel key.</li>
 *   <li><b>Declaration persistence</b>: the {@code GraphNode.feedbackOut} NBT roundtrip (the
 *       {@code fb} key, absent = undeclared — the unreleased shape writes no migration).</li>
 * </ul>
 */
@DisplayName("CutEdgePlanner：§5.3 切边规划（声明优先 + 全序最小兜底）/ CutEdgePlanner: §5.3 cut planning (declared first, total-order-min fallback)")
class CutEdgePlannerTest {

    private static final HostId A = new HostId("csc_test:dim_a", 1, 2, 3);
    private static final HostId B = new HostId("csc_test:dim_a", 10, 2, 3);
    private static final HostId C = new HostId("csc_test:dim_a", 20, 2, 3);
    private static final HostId D = new HostId("csc_test:dim_a", 30, 2, 3);

    private static final String CHA = "csc_test:dim_a\u0001chA";
    private static final String CHB = "csc_test:dim_a\u0001chB";

    private static ChannelCensus rw(Set<String> reads, Set<String> writes, Set<String> feedback) {
        return new ChannelCensus(reads, writes, feedback);
    }

    /** A↔B 双向环：A 写 chA 读 chB，B 写 chB 读 chA。 / The A↔B ring: A writes chA reads
     *  chB; B writes chB reads chA. */
    private static HostGraph abRing(ChannelCensus a, ChannelCensus b) {
        var m = new HashMap<HostId, ChannelCensus>();
        m.put(A, a);
        m.put(B, b);
        return TopoStats.buildHostGraph(m);
    }

    // ══════════ 确定性 ══════════

    @Test
    @DisplayName("同输入两次构建方案全同（切边必须可复现）")
    void sameInputBuildsIdenticalPlans() {
        var g = abRing(rw(Set.of(CHB), Set.of(CHA), Set.of()),
                       rw(Set.of(CHA), Set.of(CHB), Set.of()));
        var p1 = CutEdgePlanner.plan(g);
        var p2 = CutEdgePlanner.plan(g);
        assertEquals(p1, p2, "方案逐位相同 / the plans replay identically");
        assertEquals(p1.cutEdges(), CutEdgePlanner.plan(TopoStats.buildHostGraph(new TreeMap<>(Map.of(
                B, rw(Set.of(CHA), Set.of(CHB), Set.of()),
                A, rw(Set.of(CHB), Set.of(CHA), Set.of()))))).cutEdges(),
            "重建（不同宿主映射形态）方案仍全同 / a rebuild from a different map shape replays identically");
    }

    // ══════════ 声明优先 + 多声明不叠加 ══════════

    @Test
    @DisplayName("无声明：兜底切全序最小边（chA 的 A→B 边）")
    void undeclaredFallsBackToTheOrderMinimum() {
        var g = abRing(rw(Set.of(CHB), Set.of(CHA), Set.of()),
                       rw(Set.of(CHA), Set.of(CHB), Set.of()));
        var plan = CutEdgePlanner.plan(g);
        assertEquals(1, plan.cutEdges().size(), "恰一条");
        assertEquals(new ChannelEdge(CHA, A, B), plan.cutEdges().iterator().next(),
            "(通道,写者,读者) 全序最小 = chA 的 A→B 边");
    }

    @Test
    @DisplayName("声明优先：写者 B 声明 chB 反馈 → 切 chB 的 B→A 边（用户语义断点）")
    void declaredEdgeBeatsTheFallback() {
        var g = abRing(rw(Set.of(CHB), Set.of(CHA), Set.of()),
                       rw(Set.of(CHA), Set.of(CHB), Set.of(CHB)));
        var plan = CutEdgePlanner.plan(g);
        assertEquals(1, plan.cutEdges().size(), "声明不叠加：仍恰一条");
        assertEquals(new ChannelEdge(CHB, B, A), plan.cutEdges().iterator().next(),
            "声明边被选中（尽管 chA 的边按全序更小）");
        assertEquals(Map.of(A, Set.of(CHB)), plan.readerChannels(),
            "读者路由键面 = 被切读者 A → chB");
    }

    @Test
    @DisplayName("多声明不叠加：双方都声明仍恰切一条（声明边中全序最小）")
    void multipleDeclarationsStillOneCut() {
        var g = abRing(rw(Set.of(CHB), Set.of(CHA), Set.of(CHA)),
                       rw(Set.of(CHA), Set.of(CHB), Set.of(CHB)));
        var plan = CutEdgePlanner.plan(g);
        assertEquals(1, plan.cutEdges().size(), "多声明不叠加切多条（多余切边 = 多余固定延迟）");
        assertEquals(new ChannelEdge(CHA, A, B), plan.cutEdges().iterator().next(),
            "声明边中的全序最小 = chA 的 A→B 边");
    }

    // ══════════ 每 SCC 恰切一条 ══════════

    @Test
    @DisplayName("独立双环：恰两条（每分量一条）；自环分量切自己的边；无环图 = 空方案")
    void oneCutPerComponent() {
        String chC = "csc_test:dim_a\u0001chC", chD = "csc_test:dim_a\u0001chD";
        var m = new HashMap<HostId, ChannelCensus>();
        m.put(A, rw(Set.of(CHB), Set.of(CHA), Set.of()));
        m.put(B, rw(Set.of(CHA), Set.of(CHB), Set.of()));
        m.put(C, rw(Set.of(chD), Set.of(chC), Set.of()));
        m.put(D, rw(Set.of(chC), Set.of(chD), Set.of()));
        var plan = CutEdgePlanner.plan(TopoStats.buildHostGraph(m));
        assertEquals(2, plan.cutEdges().size(), "独立双环 = 恰两条");
        assertEquals(2, plan.readerChannels().size(), "两个被切读者");

        var selfLoop = TopoStats.buildHostGraph(Map.of(
            A, rw(Set.of(CHA), Set.of(CHA), Set.of()),
            B, rw(Set.of(), Set.of(CHB), Set.of())));
        var plan2 = CutEdgePlanner.plan(selfLoop);
        assertEquals(1, plan2.cutEdges().size(), "自环分量恰切自己的边");
        assertEquals(new ChannelEdge(CHA, A, A), plan2.cutEdges().iterator().next());

        var acyclic = TopoStats.buildHostGraph(Map.of(
            A, rw(Set.of(), Set.of(CHA), Set.of()),
            B, rw(Set.of(CHA), Set.of(), Set.of())));
        assertTrue(CutEdgePlanner.plan(acyclic).isEmpty(), "无环图 = 空方案（全链零开销）");
        assertEquals(1, plan2.readerChannels().size(), "自环被切读者 = 自环宿主自身");
    }

    // ══════════ 序无关性（headless 形态：普查映射迭代序 = 注册序替身） ══════════

    @Test
    @DisplayName("序无关性：普查映射迭代序（注册序替身）不改变方案与 S2 直线链求值序")
    void insertionOrderNeverChangesThePlanOrTheOrder() {
        var aC = rw(Set.of(CHB), Set.of(CHA), Set.of());
        var bC = rw(Set.of(CHA), Set.of(CHB), Set.of());
        // 两种插入序（HashMap 乱序 + LinkedHashMap 反转）→ 方案逐位相同。
        // Two insertion orders (a scrambled HashMap + a reversed LinkedHashMap) → identical
        // plans.
        var m1 = new HashMap<HostId, ChannelCensus>();
        m1.put(B, bC); m1.put(A, aC);
        var m2 = new LinkedHashMap<HostId, ChannelCensus>();
        m2.put(B, bC); m2.put(A, aC);
        assertEquals(CutEdgePlanner.plan(TopoStats.buildHostGraph(m1)),
            CutEdgePlanner.plan(TopoStats.buildHostGraph(m2)),
            "插入序不改变切边方案（确定性灵魂锚的 headless 形态）");
        // S2 直线链：写者→读者求值序同样与插入序无关。/ The S2 straight chain: the
        // writer→reader order is insertion-order-independent too.
        var chain1 = TopoStats.buildHostGraph(Map.of(
            A, rw(Set.of(), Set.of(CHA), Set.of()),
            B, rw(Set.of(CHA), Set.of(), Set.of())));
        var chain2 = TopoStats.buildHostGraph(new TreeMap<>(Map.of(
            B, rw(Set.of(CHA), Set.of(), Set.of()),
            A, rw(Set.of(), Set.of(CHA), Set.of()))));
        assertEquals(HostEvalOrder.evaluationOrder(chain1, CutEdgePlanner.plan(chain1).cutEdges()),
            HostEvalOrder.evaluationOrder(chain2, CutEdgePlanner.plan(chain2).cutEdges()),
            "交换构建序：直线链求值序逐位相同");
    }

    // ══════════ 声明持久化（NBT roundtrip） ══════════

    @Test
    @DisplayName("feedbackOut NBT roundtrip：置位落 fb 键、缺键 = 未声明（无迁移面）")
    void feedbackDeclarationNbtRoundtrip() {
        var g = new NodeGraph();
        var n = g.addNode(NodeType.BUS_OUT, 0, 0);
        n.signalName = "chX";
        n.feedbackOut = true;
        var tag = g.save(null);
        // 键形态锚：置位才落键（nodes 列表中本节点的 compound 含 fb）。
        // The key-shape anchor: written only when set (this node's compound in the nodes list
        // carries fb).
        boolean found = false;
        for (var t : tag.getList("nodes", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            var nt = (net.minecraft.nbt.CompoundTag) t;
            if (nt.contains("id") && nt.getInt("id") == n.id && nt.contains("fb")) found = true;
        }
        assertTrue(found, "置位落 fb 键 / a set flag writes the fb key");

        var g2 = NodeGraph.load(tag, null);
        var n2 = g2.findNode(n.id);
        assertNotNull(n2);
        assertTrue(n2.feedbackOut, "roundtrip 保真 / the roundtrip is faithful");

        // 缺键 = 未声明：未声明节点 roundtrip 后仍 false。/ Absent = undeclared: an
        // undeclared node reloads false.
        var g3 = new NodeGraph();
        g3.addNode(NodeType.BUS_OUT, 0, 0).signalName = "chY";
        var g4 = NodeGraph.load(g3.save(null), null);
        assertFalse(g4.nodes.get(0).feedbackOut, "缺键 = false（未发版形态不写迁移）");

        // 声明面 ⊆ 写面（结构性投影不变式）。/ The declaration face ⊆ the write face (the
        // structural-projection invariant).
        var census = new ChannelCensus(Set.of(), Set.of(CHA), Set.of(CHA));
        assertTrue(census.writes().containsAll(census.feedbackWrites()),
            "声明面 ⊆ 写面（结构性投影不变式）");
    }

    // ══════════ 普查声明面（host 级投影） ══════════

    @Test
    @DisplayName("声明面投影：声明 BUS_OUT 的通道进 feedbackWrites（无作用域 headless 形态）")
    void censusCarriesTheDeclarationFace() {
        var g = new NodeGraph();
        var out = g.addNode(NodeType.BUS_OUT, 0, 0);
        out.signalName = "busCh";
        out.feedbackOut = true;
        var plain = g.addNode(NodeType.PRIVATE_OUT, 20, 0);
        plain.signalName = "privCh";
        var in = g.addNode(NodeType.BUS_IN, 40, 0);
        in.signalName = "busCh";

        // headless 直调枚举胶水（null 维度 = 无作用域键空间，javadoc 口径）：声明 = 写者侧
        // 子集。/ The headless enumeration glue (a null dimension = the scope-free keyspace,
        // per its javadoc): the declaration face is a write-side subset.
        var c = io.github.y15173334444.create_schematic_compute.network.HostChannelCensus
            .census(g, null);
        assertTrue(c.writes().contains("busCh") && c.writes().contains("privCh"), "写面完整");
        assertTrue(c.feedbackWrites().contains("busCh"), "声明的 BUS_OUT 通道进声明面");
        assertFalse(c.feedbackWrites().contains("privCh"), "未声明的 PRIVATE_OUT 不进声明面");
        assertTrue(c.reads().contains("busCh"), "读面不受声明面影响");
    }

    // ══════════ 声明 op 面（SET_FEEDBACK：自反 + fail-closed） ══════════

    @Test
    @DisplayName("SET_FEEDBACK：发布节点自反翻转 + 代际 bump；非发布节点拒收（fail-closed）")
    void setFeedbackOpIsSelfInverseAndFailClosed() {
        var g = new NodeGraph();
        var out = g.addNode(NodeType.PRIVATE_OUT, 0, 0);
        out.signalName = "chX";
        var in = g.addNode(NodeType.BUS_IN, 20, 0);
        int gen0 = g.graphGeneration;
        var op = new GraphOp(OpType.SET_FEEDBACK, null, 0, out.id, null);
        OpExecutor.apply(g, op);
        assertTrue(out.feedbackOut, "首次切换 = 声明");
        assertTrue(g.graphGeneration > gen0, "翻转 bump 代际（普查/切边/求值序随代际传播）");
        OpExecutor.apply(g, op);
        assertFalse(out.feedbackOut, "再次切换 = 撤销声明（自反）");

        int gen1 = g.graphGeneration;
        OpExecutor.apply(g, new GraphOp(OpType.SET_FEEDBACK, null, 0, in.id, null));
        assertFalse(in.feedbackOut, "BUS_IN 非发布节点：拒收不翻转");
        assertEquals(gen1, g.graphGeneration, "拒收不 bump（fail-closed，无行为）");
    }
}
