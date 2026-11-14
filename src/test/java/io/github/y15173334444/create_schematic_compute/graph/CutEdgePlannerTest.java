package io.github.y15173334444.create_schematic_compute.graph;

import io.github.y15173334444.create_schematic_compute.graph.TopoStats.ChannelCensus;
import io.github.y15173334444.create_schematic_compute.graph.TopoStats.ChannelEdge;
import io.github.y15173334444.create_schematic_compute.graph.TopoStats.HostGraph;
import io.github.y15173334444.create_schematic_compute.graph.TopoStats.HostId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * §5.3 切边规划核心的锚（纯自动规则：自环边优先 + 全序最小兜底）：headless 纯数据直测
 * （核心零 MC 依赖）。这里锁死：
 * <ul>
 *   <li><b>切边选择确定性</b>：同一输入两次构建输出全同（方案的硬锚——被切边必须可复现，
 *       不能随构建顺序抖动）。</li>
 *   <li><b>自环边优先</b>：混合 SCC（自环边与跨宿主边并存）必切自环边——切自环只延迟该
 *       宿主自己的反馈读，零跨宿主序变化；即使自环边按 (通道,写者,读者) 全序排在跨宿主边
 *       之后也照样选它（优先级压过全序）。多自环不叠加——仍恰切一条（自环边中全序最小）。</li>
 *   <li><b>全序最小兜底</b>：环内无自环边时切 (通道,写者,读者) 全序最小边——确定性可复现
 *       不变（2026-11-14 用户拍板：拓扑自动切，显式声明路线整体退出）。</li>
 *   <li><b>每 SCC 恰切一条</b>：独立双环 → 恰两条（每分量一条）；自环分量切自己的边；
 *       无环图 = 空方案。</li>
 *   <li><b>序无关性（headless 形态）</b>：普查映射的迭代序（注册序的替身）不改变方案——
 *       被 GraphHost 构建面归一的边集决定一切；S2 直线链的求值序同样与插入序无关
 *       （{@code HostEvalOrderTest} 的确定性锚承接同一性质）。</li>
 *   <li><b>读者路由键面</b>：方案的 readerChannels 恰含被切读者 → 被切通道键。</li>
 * </ul>
 *
 * <p>Anchors for the §5.3 cut-edge planning core (the pure automatic rule: self-loop edges
 * first + the total-order-minimum fallback): headless on pure data (the core carries no MC
 * dependency). Pinned here:</p>
 * <ul>
 *   <li><b>Cut-selection determinism</b>: the same input builds identical output twice (the
 *       plan's hard anchor — the cut edge must be reproducible, never jitter with build
 *       order).</li>
 *   <li><b>Self-loop edges first</b>: a mixed SCC (self-loop and cross-host edges side by
 *       side) always cuts the self-loop — a self-loop cut delays only that host's own
 *       feedback read, zero cross-host order change; the self-loop wins even when it sorts
 *       after the cross-host edges in the (channel, writer, reader) total order. Multiple
 *       self-loops never stack — still exactly one cut (the total-order minimum among the
 *       self-loops).</li>
 *   <li><b>The total-order-minimum fallback</b>: no self-loop in the ring → the (channel,
 *       writer, reader) total-order minimum is cut — deterministic reproducibility
 *       unchanged (the user's 2026-11-14 ruling: cut by topology automatically, the
 *       explicit-declaration route retired wholesale).</li>
 *   <li><b>Exactly one cut per SCC</b>: two independent rings → exactly two cuts (one per
 *       component); a self-loop component cuts its own edge; an acyclic graph = the empty
 *       plan.</li>
 *   <li><b>Order independence (the headless form)</b>: the census map's iteration order (the
 *       stand-in for registration order) never changes the plan — the HostGraph build face
 *       normalizes the edge set; the S2 straight-chain order carries the same property
 *       (HostEvalOrderTest's determinism anchors).</li>
 *   <li><b>The reader routing key face</b>: the plan's readerChannels holds exactly the cut
 *       reader → its cut channel key.</li>
 * </ul>
 */
@DisplayName("CutEdgePlanner：§5.3 切边规划（自环优先 + 全序最小兜底）/ CutEdgePlanner: §5.3 cut planning (self-loop first, total-order-min fallback)")
class CutEdgePlannerTest {

    private static final HostId A = new HostId("csc_test:dim_a", 1, 2, 3);
    private static final HostId B = new HostId("csc_test:dim_a", 10, 2, 3);
    private static final HostId C = new HostId("csc_test:dim_a", 20, 2, 3);
    private static final HostId D = new HostId("csc_test:dim_a", 30, 2, 3);

    private static final String CHA = "csc_test:dim_a\u0001chA";
    private static final String CHB = "csc_test:dim_a\u0001chB";
    private static final String CHZ = "csc_test:dim_a\u0001chZ";

    private static ChannelCensus rw(Set<String> reads, Set<String> writes) {
        return new ChannelCensus(reads, writes);
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
        var g = abRing(rw(Set.of(CHB), Set.of(CHA)),
                       rw(Set.of(CHA), Set.of(CHB)));
        var p1 = CutEdgePlanner.plan(g);
        var p2 = CutEdgePlanner.plan(g);
        assertEquals(p1, p2, "方案逐位相同 / the plans replay identically");
        assertEquals(p1.cutEdges(), CutEdgePlanner.plan(TopoStats.buildHostGraph(new TreeMap<>(Map.of(
                B, rw(Set.of(CHA), Set.of(CHB)),
                A, rw(Set.of(CHB), Set.of(CHA)))))).cutEdges(),
            "重建（不同宿主映射形态）方案仍全同 / a rebuild from a different map shape replays identically");
    }

    // ══════════ 自环优先 + 多自环不叠加 ══════════

    @Test
    @DisplayName("自环优先：混合 SCC 必切自环边（即使它按全序排在跨宿主边之后）")
    void selfLoopEdgeBeatsTheCrossHostFallback() {
        // A↔B 环（chA/chB 跨宿主边）+ A 的自环（chZ）：全序最小是 (chA,A,B)，但自环优先
        // 压过全序——切 (chZ,A,A)，只延迟 A 自己的反馈读，跨宿主序零变化。
        // The A↔B ring (the chA/chB cross-host edges) + A's self-loop (chZ): the total-order
        // minimum is (chA,A,B), but self-loop priority beats the order — cut (chZ,A,A),
        // delaying only A's own feedback read, zero cross-host order change.
        var g = abRing(rw(Set.of(CHB, CHZ), Set.of(CHA, CHZ)),
                       rw(Set.of(CHA), Set.of(CHB)));
        var plan = CutEdgePlanner.plan(g);
        assertEquals(1, plan.cutEdges().size(), "恰一条 / exactly one cut");
        assertEquals(new ChannelEdge(CHZ, A, A), plan.cutEdges().iterator().next(),
            "自环边被选中（尽管 chA 的跨宿主边按全序更小）/ the self-loop edge is chosen "
                + "(even though chA's cross-host edge sorts lower in the total order)");
        assertEquals(Map.of(A, Set.of(CHZ)), plan.readerChannels(),
            "读者路由键面 = 被切读者 A → chZ / the routing key face: the cut reader A → chZ");
    }

    @Test
    @DisplayName("多自环不叠加：SCC 内两条自环边仍恰切一条（自环边中全序最小）")
    void multipleSelfLoopsStillOneCut() {
        // 双向环双方各带一条自环：多自环不叠加切多条（多余切边 = 多余固定延迟）——
        // 取自环边中全序最小。
        // Both ring sides carry a self-loop: multiple self-loops never stack into multiple
        // cuts (extra cuts = extra fixed latency) — the total-order minimum among the
        // self-loops wins.
        var g = abRing(rw(Set.of(CHB, CHA), Set.of(CHA)),
                       rw(Set.of(CHA, CHB), Set.of(CHB)));
        var plan = CutEdgePlanner.plan(g);
        assertEquals(1, plan.cutEdges().size(), "多自环不叠加：仍恰一条");
        assertEquals(new ChannelEdge(CHA, A, A), plan.cutEdges().iterator().next(),
            "自环边中的全序最小 = chA 的 A 自环");
    }

    // ══════════ 全序最小兜底（无自环的跨宿主环） ══════════

    @Test
    @DisplayName("无自环：兜底切全序最小边（chA 的 A→B 边）")
    void noSelfLoopFallsBackToTheOrderMinimum() {
        var g = abRing(rw(Set.of(CHB), Set.of(CHA)),
                       rw(Set.of(CHA), Set.of(CHB)));
        var plan = CutEdgePlanner.plan(g);
        assertEquals(1, plan.cutEdges().size(), "恰一条");
        assertEquals(new ChannelEdge(CHA, A, B), plan.cutEdges().iterator().next(),
            "(通道,写者,读者) 全序最小 = chA 的 A→B 边");
    }

    // ══════════ 每 SCC 恰切一条 ══════════

    @Test
    @DisplayName("独立双环：恰两条（每分量一条）；自环分量切自己的边；无环图 = 空方案")
    void oneCutPerComponent() {
        String chC = "csc_test:dim_a\u0001chC", chD = "csc_test:dim_a\u0001chD";
        var m = new HashMap<HostId, ChannelCensus>();
        m.put(A, rw(Set.of(CHB), Set.of(CHA)));
        m.put(B, rw(Set.of(CHA), Set.of(CHB)));
        m.put(C, rw(Set.of(chD), Set.of(chC)));
        m.put(D, rw(Set.of(chC), Set.of(chD)));
        var plan = CutEdgePlanner.plan(TopoStats.buildHostGraph(m));
        assertEquals(2, plan.cutEdges().size(), "独立双环 = 恰两条");
        assertEquals(2, plan.readerChannels().size(), "两个被切读者");

        var selfLoop = TopoStats.buildHostGraph(Map.of(
            A, rw(Set.of(CHA), Set.of(CHA)),
            B, rw(Set.of(), Set.of(CHB))));
        var plan2 = CutEdgePlanner.plan(selfLoop);
        assertEquals(1, plan2.cutEdges().size(), "自环分量恰切自己的边");
        assertEquals(new ChannelEdge(CHA, A, A), plan2.cutEdges().iterator().next());

        var acyclic = TopoStats.buildHostGraph(Map.of(
            A, rw(Set.of(), Set.of(CHA)),
            B, rw(Set.of(CHA), Set.of())));
        assertTrue(CutEdgePlanner.plan(acyclic).isEmpty(), "无环图 = 空方案（全链零开销）");
        assertEquals(1, plan2.readerChannels().size(), "自环被切读者 = 自环宿主自身");
    }

    // ══════════ 序无关性（headless 形态：普查映射迭代序 = 注册序替身） ══════════

    @Test
    @DisplayName("序无关性：普查映射迭代序（注册序替身）不改变方案与 S2 直线链求值序")
    void insertionOrderNeverChangesThePlanOrTheOrder() {
        var aC = rw(Set.of(CHB), Set.of(CHA));
        var bC = rw(Set.of(CHA), Set.of(CHB));
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
            A, rw(Set.of(), Set.of(CHA)),
            B, rw(Set.of(CHA), Set.of())));
        var chain2 = TopoStats.buildHostGraph(new TreeMap<>(Map.of(
            B, rw(Set.of(CHA), Set.of()),
            A, rw(Set.of(), Set.of(CHA)))));
        assertEquals(HostEvalOrder.evaluationOrder(chain1, CutEdgePlanner.plan(chain1).cutEdges()),
            HostEvalOrder.evaluationOrder(chain2, CutEdgePlanner.plan(chain2).cutEdges()),
            "交换构建序：直线链求值序逐位相同");
    }
}
