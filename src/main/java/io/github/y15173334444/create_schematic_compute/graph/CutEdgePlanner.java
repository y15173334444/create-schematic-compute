package io.github.y15173334444.create_schematic_compute.graph;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * <h2>§5.3 切边规划核心（环 SCC → 每 SCC 恰切一条边）——纯 JDK，零 MC import</h2>
 *
 * <p>跨 BE 通道环（{@link TopoStats#cyclicComponents} 的输出）按 P1 约束切边，使环变成可
 * 拓扑的 DAG——被切边<b>恒定跨 tick</b>（读上一 tick 末快照），环外全部同 tick（S3）。
 * 切边选择 = <b>(a) 纯自动规则</b>（2026-11-14 用户拍板：「玩家可能不太懂这个，我们按拓扑
 * 自动切吧」——原 (c) 显式声明路线整体退出，自动规则成为唯一路径）：</p>
 * <ul>
 * <li><b>自环边优先</b>：SCC 内存在自环边（写者 == 读者的边——同宿主主图与 ENCAP 子图
 *     读写同一通道，§3.2 顺带推论）时必切自环。机制级理由：切自环边<b>只延迟该宿主自己
 *     的反馈读</b>（它读自己上一 tick 写出的值）——跨宿主拓扑序一字不动，零跨宿主序变化；
     *     真实世界 ring 首跑（2026-11-14 实测）的三个环分量全是自环（宿主自读自写），此规则
     *     在现实形态下从不落到跨宿主兜底。</li>
 * <li><b>全序最小兜底</b>：SCC 内无自环边（真跨宿主环）时，按<b>边全序最小</b>自动切一条
 *     —— {@link TopoStats.ChannelEdge#compareTo} 的 (通道, 写者, 读者) 字典序。选它的理由：
 *     边集已按该全序排列（{@code HostGraph} 构建处排序），"最小"即迭代序首个——确定性
 *     可复现（同输入两次构建全同，headless 锚），且不引入任何与坐标/模组状态相关的
 *     抖动源；坐标字典序（候选 (b)）与全序最小等确定，但「通道名字典序」对用户更可解释
 *     （环上通道名一目了然），故取通道名优先形态。</li>
 * <li><b>每个 SCC 恰切一条</b>：切一条即可让该 SCC 的连通性破开（自环 SCC 切自环边即成
 *     平凡宿主）；<b>多自环不叠加切多条</b>——多余的切边只会把更多同 tick 路径打成跨
 *     tick（更多固定 1 tick 延迟），对收敛毫无增益（§5.3 的目标是"可拓扑"，不是"多切"）。
 *     多环共享宿主的"双环分量"切一条后残余仍成环的子集，由求值序
 *     （{@code HostEvalOrder}）按 HostId 全序兜底，成员一个不丢。</li>
 * </ul>
 *
 * <p><b>输出形态（{@link CutPlan}）</b>：{@code cutEdges} = 被切边集（进
 *     {@code HostEvalOrder.evaluationOrder} 的剔除面 + 展示面标记）；
 *     {@code readerChannels} = 读者宿主 → 被切通道键集（读侧快照路由的键面）。
 *     全键有序——同输入两次构建逐位相同（确定性锚）。</p>
 *
 * <hr>
 * <h2>The §5.3 cut-edge planning core (ring SCCs → exactly one cut per SCC) — pure JDK,
 * zero MC imports.</h2>
 *
 * <p>Cross-BE channel cycles (the output of {@link TopoStats#cyclicComponents}) are cut
 * under the P1 constraint so the ring becomes a topologizable DAG — the cut edge is
 * <b>permanently cross-tick</b> (reads last tick's committed snapshot) while everything
 * outside it stays same-tick (S3). The cut selection = <b>(a) the pure automatic rule</b>
 * (the user's 2026-11-14 ruling: "players probably don't understand this — let's cut by
 * topology automatically"; the former (c) explicit-declaration route is retired wholesale,
 * the automatic rule is now the only path):</p>
 * <ul>
 * <li><b>Self-loop edges first</b>: when an SCC carries a self-loop edge (writer == reader
 *     — one host's main graph and its ENCAP sub-graph reading/writing the same channel,
 *     the §3.2 corollary), the self-loop is always the cut. Mechanism-level why: cutting a
 *     self-loop <b>delays only that host's own feedback read</b> (it reads what it wrote
 *     last tick itself) — the cross-host topology order is untouched, zero cross-host order
 *     change. The real-world ring first-run (live-measured 2026-11-14) had three ring
 *     components, all self-loops (hosts reading their own writes), so this rule never
 *     reaches the cross-host fallback in the realistic shape.</li>
 * <li><b>The total-order-minimum fallback</b>: when an SCC carries no self-loop edge (a
 *     genuinely cross-host ring), auto-cut the <b>total-order-minimum</b> edge — the
 *     (channel, writer, reader) lexicographic order of {@link TopoStats.ChannelEdge#compareTo}.
 *     Why: the edge list is already sorted in that total order at HostGraph build time, so
     *     "minimal" is the iteration-head — reproducibly deterministic (the same input builds
 *     identically twice, the headless anchor), with no coordinate- or mod-state-dependent
 *     jitter source; the coordinate order (candidate (b)) is equally deterministic, but
 *     "channel-name order" reads better for users (the ring's channel names are right
 *     there), so the channel-led form is taken.</li>
 * <li><b>Exactly one cut per SCC</b>: one cut already breaks the SCC's strong connectivity
 *     (a self-loop SCC becomes a trivial host by cutting its own edge);
 *     <b>multiple self-loops never stack into multiple cuts</b> — every extra cut only
 *     converts more same-tick paths into cross-tick ones (more fixed 1-tick latencies) with
 *     zero convergence gain (§5.3's goal is "topologizable", not "cut more"). For a
 *     multi-cycle component sharing one host, a still-cyclic residue after the one cut is
 *     caught by the evaluation order ({@code HostEvalOrder}) with the HostId total-order
 *     fallback — not one member dropped.</li>
 * </ul>
 *
 * <p><b>Output shape ({@link CutPlan})</b>: {@code cutEdges} = the cut-edge set (feeding
 *     {@code HostEvalOrder.evaluationOrder}'s exclusion face + the display markers);
 *     {@code readerChannels} = reader host → cut channel-key set (the key face of the
 *     read-side snapshot routing). Every key ordered — the same input builds bit-identically
 *     twice (the determinism anchor).</p>
 */
public final class CutEdgePlanner {

    private CutEdgePlanner() {}

    /** 切边方案（不可变、全键有序）：被切边集 + 读者宿主 → 被切通道键集。
     *  A cut plan (immutable, every key ordered): the cut-edge set + reader host → cut
     *  channel-key set. */
    public record CutPlan(Set<TopoStats.ChannelEdge> cutEdges,
                          Map<TopoStats.HostId, Set<String>> readerChannels) {
        /** 空方案常量：无环图 / 复位面。 / The empty plan: an acyclic graph / the reset face. */
        public static final CutPlan EMPTY =
            new CutPlan(Set.of(), Map.of());
        /** 无切边。 / No cut edges. */
        public boolean isEmpty() { return cutEdges.isEmpty(); }
    }

    /**
     * 从宿主图规划切边（纯核心，headless 直测）：每个真环分量（{@link TopoStats#cyclicComponents}）
     * 恰选一条边——自环边优先（写者 == 读者），无自环时按 (通道, 写者, 读者) 全序最小。
     * <p>Plan the cuts for a host graph (the pure core, headless-testable): exactly one edge
     * per genuine cyclic component ({@link TopoStats#cyclicComponents}) — self-loop edges
     * (writer == reader) first, else the (channel, writer, reader) total-order minimum.</p>
     *
     * @param g BE 级依赖图（普查只含读/写面） / the BE-level dependency graph (the census
     *          carries the read/write faces only)
     * @return 切边方案（不可变；无环图 = {@link CutPlan#EMPTY}） / the cut plan (immutable;
     *         an acyclic graph = {@link CutPlan#EMPTY})
     */
    public static CutPlan plan(TopoStats.HostGraph g) {
        if (g == null) return CutPlan.EMPTY;
        var cycles = TopoStats.cyclicComponents(g);
        if (cycles.isEmpty()) return CutPlan.EMPTY;
        var cutEdges = new TreeSet<TopoStats.ChannelEdge>();
        var readerChannels = new TreeMap<TopoStats.HostId, Set<String>>();
        for (var scc : cycles) {
            TopoStats.ChannelEdge chosen = null;
            boolean selfLoopMode = false;
            // 边视图已按 (通道, 写者, 读者) 全序排列：线性扫一遍，自环模式切换后按序首条
            // 即自环边中的全序最小；兜底模式下全程取序最小。
            // The edge view is already in the (channel, writer, reader) total order: one
            // linear sweep — after switching into self-loop mode the first hit is the
            // total-order minimum among self-loops; the fallback mode keeps the order
            // minimum throughout.
            for (TopoStats.ChannelEdge e : g.edges()) {
                if (!scc.contains(e.writer()) || !scc.contains(e.reader())) continue;
                boolean selfLoop = e.writer().equals(e.reader());
                if (selfLoopMode && !selfLoop) continue;
                if (selfLoop && !selfLoopMode) {
                    // 进入自环模式：边序保证这是第一条自环边 = 自环边中的全序最小。
                    // Entering self-loop mode: the edge order guarantees this is the first
                    // self-loop edge = the total-order minimum among self-loop edges.
                    chosen = e;
                    selfLoopMode = true;
                    continue;
                }
                if (chosen == null || e.compareTo(chosen) < 0) chosen = e;
            }
            // 环分量必有内部边（SCC 强连通定义），chosen 恒非 null。
            // A cyclic component always carries internal edges (the SCC definition), chosen is
            // never null.
            if (chosen == null) continue;
            cutEdges.add(chosen);
            readerChannels.computeIfAbsent(chosen.reader(), k -> new TreeSet<>()).add(chosen.channel());
        }
        return new CutPlan(java.util.Collections.unmodifiableSortedSet(cutEdges),
            java.util.Collections.unmodifiableMap(readerChannels));
    }
}
