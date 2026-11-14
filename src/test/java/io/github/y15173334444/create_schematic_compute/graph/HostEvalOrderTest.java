package io.github.y15173334444.create_schematic_compute.graph;

import io.github.y15173334444.create_schematic_compute.graph.TopoStats.ChannelCensus;
import io.github.y15173334444.create_schematic_compute.graph.TopoStats.ChannelEdge;
import io.github.y15173334444.create_schematic_compute.graph.TopoStats.HostGraph;
import io.github.y15173334444.create_schematic_compute.graph.TopoStats.HostId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 宿主级求值序核心的锚（§5.2 方案 A + §5.6）：全局拓扑重排后，求值遍历顺序由
 * {@link HostEvalOrder#evaluationOrder} 产出——headless 纯数据直测（核心零 MC 依赖，
 * {@code C2SPacketRateGuard} 判定/执行分离同款；编排薄胶在 blocks 侧随结构锁面另锚）。
 * 这里锁死：
 * <ul>
 *   <li>无环链（A→B→C）：写者先于读者——S2 的「按依赖拓扑序的不动点」在序面上成立。</li>
 *   <li>扇出 / 汇聚（菱形）：合法拓扑序（上游全在前、下游全在后）。</li>
 *   <li><b>§5.6 铁律</b>：环分量（A↔B）的成员<b>全部进序、一个不丢</b>——绝不交给 naive
 *       Kahn 顺手吞环（那会让整条环上的宿主静默停更）；切边落地前环内按 HostId 全序的
 *       确定性兜底可复现。</li>
 *   <li>自环单例（读自己写的通道，§3.2 推论）：出现且仅出现一次。</li>
 *   <li>确定性：同一图集任意次重算输出逐位相同（诊断与将来的稳定切边都要求可复现）。</li>
 *   <li>全序完整：图内每宿主恰出现一次（无遗漏、无重复——S6「每 host 每 tick 恰一次求值」
 *       的序面前提）。</li>
 *   <li>跨维度：两维度的宿主互不施加序约束（通道边同维度天然隔离），全局序仍确定。</li>
 *   <li>被切边不进序约束（§5.3 接线点）：环 A↔B 切掉一条边后，余下方向变成合法的
 *       写者→读者顺序；残余环成员仍全数在序（不丢成员）。</li>
 * </ul>
 *
 * <p>Anchors for the host-level evaluation-order core (§5.2 plan A + §5.6): after the global
 * topology reordering, the pass traversal order is produced by
 * {@link HostEvalOrder#evaluationOrder} — headless on pure data (the core carries no MC
 * dependency, the same decision/execution split as {@code C2SPacketRateGuard}; the thin
 * orchestration glue is anchored on the blocks side through the structural lock). Pinned
 * here:</p>
 * <ul>
 *   <li>An acyclic chain (A→B→C): writers before readers — S2's "the fixpoint in dependency
 *       topological order" holds on the order face.</li>
 *   <li>Fan-out / fan-in (the diamond): a valid topological order (all upstream first, all
 *       downstream last).</li>
 *   <li><b>The §5.6 iron rule</b>: every member of a cyclic component (A↔B) <b>enters the
 *       order, not one dropped</b> — never handed to a naive Kahn that would silently freeze
 *       the whole ring's hosts; before edge cutting lands, the in-cycle HostId-total-order
 *       fallback is deterministic and reproducible.</li>
 *   <li>A self-loop singleton (reads a channel it writes, the §3.2 corollary): present
 *       exactly once.</li>
 *   <li>Determinism: the same graph set replays bit-identically any number of times
 *       (diagnostics and future stable edge cutting both need reproducibility).</li>
 *   <li>Total-order completeness: every host in the graph appears exactly once (no missing,
 *       no duplicate — the order-face prerequisite of S6's "each host evaluates exactly once
 *       per tick").</li>
 *   <li>Across dimensions: hosts of two dimensions impose no order constraints on each other
 *       (channel edges are naturally same-dimension), while the global order stays
 *       deterministic.</li>
 *   <li>Cut edges never enter the order constraints (the §5.3 wiring point): after cutting
 *       one edge of the A↔B cycle, the remaining direction becomes a legal writer→reader
 *       order; residual cyclic members still all appear (never dropped).</li>
 * </ul>
 */
@DisplayName("HostEvalOrder：宿主级求值序（§5.2 A + §5.6）/ HostEvalOrder: the host-level evaluation order (§5.2 A + §5.6)")
class HostEvalOrderTest {

    private static final HostId A = new HostId("csc_test:dim_a", 1, 2, 3);
    private static final HostId B = new HostId("csc_test:dim_a", 10, 2, 3);
    private static final HostId C = new HostId("csc_test:dim_a", 20, 2, 3);
    private static final HostId D = new HostId("csc_test:dim_a", 30, 2, 3);
    private static final HostId B_DIM2 = new HostId("csc_test:dim_b", 10, 2, 3);

    private static final String CH1 = "csc_test:dim_a\u0001ch1";
    private static final String CH2 = "csc_test:dim_a\u0001ch2";

    /** 读/写普查。 / A reads/writes census. */
    private static ChannelCensus rw(Set<String> reads, Set<String> writes) {
        return new ChannelCensus(reads, writes);
    }

    /** 从「写者 → 读者」关系构建宿主图。 / Build the host graph from writer→reader relations. */
    private static HostGraph graphOf(Map<HostId, ChannelCensus> census) {
        return TopoStats.buildHostGraph(census);
    }

    private static Map<HostId, ChannelCensus> censusMap(Object... pairs) {
        var m = new HashMap<HostId, ChannelCensus>();
        for (int i = 0; i < pairs.length; i += 2) m.put((HostId) pairs[i], (ChannelCensus) pairs[i + 1]);
        return m;
    }

    // ══════════ 无环链：写者先于读者（S2 序面） ══════════

    @Test
    @DisplayName("无环链 A→B→C：写者先于读者（S2 的拓扑序不动点在序面上成立）")
    void acyclicChainOrdersWritersBeforeReaders() {
        var g = graphOf(censusMap(
            A, rw(Set.of(), Set.of(CH1)),
            B, rw(Set.of(CH1), Set.of(CH2)),
            C, rw(Set.of(CH2), Set.of())));
        List<HostId> order = HostEvalOrder.evaluationOrder(g, Set.of());
        assertEquals(List.of(A, B, C), order, "写者 A → 读者 B（再写 CH2）→ 读者 C");
    }

    @Test
    @DisplayName("菱形汇聚：上游全在下游前（合法拓扑序）")
    void diamondOrdersUpstreamBeforeDownstream() {
        // A 写 CH1/CH2；B 读 CH1、C 读 CH2；D 读 CH1+CH2（汇聚）。
        // A writes CH1/CH2; B reads CH1, C reads CH2; D reads both (the fan-in).
        var g = graphOf(censusMap(
            A, rw(Set.of(), Set.of(CH1, CH2)),
            B, rw(Set.of(CH1), Set.of()),
            C, rw(Set.of(CH2), Set.of()),
            D, rw(Set.of(CH1, CH2), Set.of())));
        List<HostId> order = HostEvalOrder.evaluationOrder(g, Set.of());
        assertEquals(4, order.size());
        assertTrue(order.indexOf(A) < order.indexOf(B), "A 先于 B");
        assertTrue(order.indexOf(A) < order.indexOf(C), "A 先于 C");
        assertTrue(order.indexOf(B) < order.indexOf(D), "B 先于 D");
        assertTrue(order.indexOf(C) < order.indexOf(D), "C 先于 D");
    }

    // ══════════ §5.6 铁律：环分量成员一个不丢 ══════════

    @Test
    @DisplayName("§5.6：环 A↔B 成员全部进序（绝不交给 naive Kahn 静默吞环）+ 兜底序确定")
    void cycleMembersAllEnterTheOrder() {
        var g = graphOf(censusMap(
            A, rw(Set.of(CH2), Set.of(CH1)),
            B, rw(Set.of(CH1), Set.of(CH2))));
        List<HostId> order1 = HostEvalOrder.evaluationOrder(g, Set.of());
        assertEquals(2, order1.size(), "环上两宿主都进序（naive Kahn 会双双丢失——§5.6 的病）");
        assertTrue(order1.contains(A) && order1.contains(B));
        assertEquals(order1, HostEvalOrder.evaluationOrder(g, Set.of()),
            "同一图集重算输出逐位相同（确定性兜底可复现）");
        // HostId 全序兜底：分量内按 HostId 升序。/ The HostId-total-order fallback: members
        // in ascending HostId order within the component.
        assertEquals(List.of(A, B), order1, "切边落地前：环内成员按 HostId 全序兜底");
    }

    @Test
    @DisplayName("自环单例：出现且恰一次（§3.2 主图与 ENCAP 子图同通道推论）")
    void selfLoopSingletonAppearsExactlyOnce() {
        var g = graphOf(censusMap(
            A, rw(Set.of(CH1), Set.of(CH1)),
            B, rw(Set.of(), Set.of(CH2))));
        List<HostId> order = HostEvalOrder.evaluationOrder(g, Set.of());
        assertEquals(2, order.size());
        assertEquals(1, order.stream().filter(A::equals).count(), "自环宿主恰一次（S6 的序面前提）");
        assertEquals(1, order.stream().filter(B::equals).count());
    }

    @Test
    @DisplayName("环 + 尾巴：尾巴不拖累环、环成员不丢")
    void cycleWithTailKeepsEveryone() {
        // A↔B 环 + C 读 CH1（尾巴：C 依赖 A）。/ An A↔B cycle + C reading CH1 (the tail).
        var g = graphOf(censusMap(
            A, rw(Set.of(CH2), Set.of(CH1)),
            B, rw(Set.of(CH1), Set.of(CH2)),
            C, rw(Set.of(CH1), Set.of())));
        List<HostId> order = HostEvalOrder.evaluationOrder(g, Set.of());
        assertEquals(3, order.size(), "三宿主全在序");
        assertTrue(order.indexOf(C) > order.indexOf(A), "尾巴 C 在其上游 A 之后（分量级依赖仍然成立）");
    }

    // ══════════ 确定性 + 全序完整 ══════════

    @Test
    @DisplayName("确定性 + 全序完整：任意次重算逐位相同，每宿主恰一次")
    void deterministicAndTotal() {
        var g = graphOf(censusMap(
            A, rw(Set.of(), Set.of(CH1)),
            B, rw(Set.of(CH1), Set.of(CH2)),
            C, rw(Set.of(CH1), Set.of()),
            B_DIM2, rw(Set.of(CH2), Set.of())));
        List<HostId> first = HostEvalOrder.evaluationOrder(g, Set.of());
        for (int i = 0; i < 5; i++)
            assertEquals(first, HostEvalOrder.evaluationOrder(g, Set.of()), "重算 #" + i);
        assertEquals(g.hosts().size(), first.size(), "图内每宿主恰一次");
        assertEquals(g.hosts(), new java.util.TreeSet<>(first), "序集合 = 图节点集");
    }

    @Test
    @DisplayName("跨维度：两维度宿主互不施加序约束（通道边天然同维度），全局序仍确定")
    void crossDimensionHostsStayIndependent() {
        // 维度 a：A 写 CH1 → B 读；维度 b：B_DIM2 写同名逻辑通道（前缀隔离 → 另一边）
        // 与 A/B 无边。/ Dimension a: A writes CH1 → B reads; dimension b: B_DIM2 writes the
        // same logical name (prefix isolation → a different channel) with no edge to A/B.
        var dim2ch = "csc_test:dim_b\u0001ch1";
        var g = graphOf(censusMap(
            A, rw(Set.of(), Set.of(CH1)),
            B, rw(Set.of(CH1), Set.of()),
            B_DIM2, rw(Set.of(), Set.of(dim2ch))));
        List<HostId> order = HostEvalOrder.evaluationOrder(g, Set.of());
        assertEquals(3, order.size());
        assertTrue(order.indexOf(A) < order.indexOf(B), "维度 a 内部：A 先于 B");
        // 跨维度对无约束：B_DIM2 的位置由 HostId 全序决定（csc_test:dim_b 字典序在 dim_a 后）。
        // No cross-dimension constraint: B_DIM2's position rides the HostId total order
        // (csc_test:dim_b sorts after dim_a).
        assertEquals(2, order.indexOf(B_DIM2), "跨维度宿主按 HostId 全序排在维度 a 分量之后");
    }

    // ══════════ 被切边不进序约束（§5.3 接线点） ══════════

    @Test
    @DisplayName("切边批接线点：环 A↔B 切掉一条边后，余方向变成合法写者→读者顺序，成员仍全数在序")
    void cutEdgeLeavesTheOrderConstraints() {
        var g = graphOf(censusMap(
            A, rw(Set.of(CH2), Set.of(CH1)),
            B, rw(Set.of(CH1), Set.of(CH2))));
        var edges = g.edges();
        assertEquals(2, edges.size());
        // 切 B→A 那条（写者 B 读者 A 的边）：余下 A→B 仍受序约束。
        // Cut the B→A edge (writer B, reader A): the remaining A→B edge still constrains.
        ChannelEdge bToA = edges.stream()
            .filter(e -> e.writer().equals(B) && e.reader().equals(A)).findFirst().orElseThrow();
        List<HostId> order = HostEvalOrder.evaluationOrder(g, Set.of(bToA));
        assertEquals(List.of(A, B), order,
            "被切边不进序约束：A 不再等 B（读上 tick 快照），B 等 A（同 tick）");
        assertEquals(2, order.size(), "切边不丢成员（残余环兜底纪律同一）");
    }

    @Test
    @DisplayName("残余环兜底：多环分量切一条边后仍成环的残余按 HostId 全序追加，不丢成员")
    void residualCyclicSubsetFallsBackInHostIdOrder() {
        // 双环分量：A↔B（CH1/CH2）与 A↔C（CH3/CH4）共享 A。切掉 B→A 一条，A↔C 仍是环 →
        // {A, C} 残余按 HostId 全序追加（每 SCC 恰切一条的规格口径下，残余环保持确定性）。
        // A two-cycle component: A↔B (CH1/CH2) and A↔C (CH3/CH4) sharing A. Cutting B→A
        // leaves A↔C cyclic → the {A, C} residue appends in HostId order (under the one-cut-
        // per-SCC ruling the residue stays deterministic).
        String ch3 = "csc_test:dim_a\u0001ch3", ch4 = "csc_test:dim_a\u0001ch4";
        var g = graphOf(censusMap(
            A, rw(Set.of(CH2, ch4), Set.of(CH1, ch3)),
            B, rw(Set.of(CH1), Set.of(CH2)),
            C, rw(Set.of(ch3), Set.of(ch4))));
        assertEquals(1, TopoStats.cyclicComponents(g).size(), "三宿主同属一个环分量");
        ChannelEdge bToA = g.edges().stream()
            .filter(e -> e.writer().equals(B) && e.reader().equals(A)).findFirst().orElseThrow();
        List<HostId> order = HostEvalOrder.evaluationOrder(g, Set.of(bToA));
        assertEquals(3, order.size(), "三宿主一个不丢");
        assertTrue(order.indexOf(B) > order.indexOf(A), "B 等 A（余方向 A→B 同 tick 有序）");
        // 残余 {A, C} 按 HostId 全序：A 在 C 前。/ The residue {A, C} in HostId order: A before C.
        assertTrue(order.indexOf(A) < order.indexOf(C), "残余环成员按 HostId 全序兜底");
    }

    // ══════════ 边界 ══════════

    @Test
    @DisplayName("空图返回空序；null 切边集视为空集")
    void emptyGraphAndNullCuts() {
        assertTrue(HostEvalOrder.evaluationOrder(TopoStats.buildHostGraph(Map.of()), Set.of()).isEmpty());
        var g = graphOf(censusMap(A, rw(Set.of(), Set.of(CH1))));
        assertEquals(List.of(A), HostEvalOrder.evaluationOrder(g, null), "null 切边集 = 空集");
    }
}
