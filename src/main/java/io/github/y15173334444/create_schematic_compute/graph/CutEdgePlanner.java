package io.github.y15173334444.create_schematic_compute.graph;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * <h2>§5.3 切边规划核心（环 SCC → 每 SCC 恰切一条边）——纯 JDK，零 MC import</h2>
 *
 * <p>跨 BE 通道环（{@link TopoStats#cyclicComponents} 的输出）按 P1 约束切边，使环变成可
 * 拓扑的 DAG——被切边<b>恒定跨 tick</b>（读上一 tick 末快照），环外全部同 tick（S3）。
 * 切边选择 = <b>(c) 显式声明为主 + (a) 稳定兜底</b>（§5.3 预案原文的倾向形态）：</p>
 * <ul>
 * <li><b>(c) 显式声明优先</b>：声明边 = 读者普查的 {@code feedbackWrites}（写者侧
 *     {@code GraphNode.feedbackOut} 的结构性投影）覆盖的环内边——用户语义断点，
 *     与 P1「切在火控非关键路径」最一致：用户拍板哪条边可以慢一拍。</li>
 * <li><b>(a) 稳定兜底</b>：SCC 内无声明边时，按<b>边全序最小</b>自动切一条——
 *     {@link TopoStats.ChannelEdge#compareTo} 的 (通道, 写者, 读者) 字典序。选它的理由：
 *     边集已按该全序排列（{@code HostGraph} 构建处排序），"最小"即迭代序首个——确定性
 *     可复现（同输入两次构建全同，headless 锚），且不引入任何与坐标/模组状态相关的
 *     抖动源；坐标字典序（候选 (b)）与全序最小等确定，但「通道名字典序」对用户更可解释
 *     （环上通道名一目了然），故取 (a) 的通道名优先形态。</li>
 * <li><b>每个 SCC 恰切一条</b>：切一条即可让该 SCC 的连通性破开（自环 SCC 切自环边即成
 *     平凡宿主）；<b>多声明不叠加切多条</b>——多余的切边只会把更多同 tick 路径打成跨
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
 * outside it stays same-tick (S3). The cut selection = <b>(c) explicit declaration first +
 * (a) the stable fallback</b> (the §5.3 plan's stated preference shape):</p>
 * <ul>
 * <li><b>(c) Declared edges first</b>: a declared edge = an in-ring edge whose channel rides
 *     the reader census's {@code feedbackWrites} (the structural projection of the writer
 *     side's {@code GraphNode.feedbackOut}) — the user's semantic breakpoint, the most
 *     P1-consistent choice ("cut on the fire-control non-critical path" is the user's
 *     ruling of which edge may lag one tick).</li>
 * <li><b>(a) The stable fallback</b>: when an SCC carries no declared edge, auto-cut the
 *     <b>total-order-minimal</b> edge — the (channel, writer, reader) lexicographic order of
 *     {@link TopoStats.ChannelEdge#compareTo}. Why: the edge list is already sorted in that
 *     total order at HostGraph build time, so "minimal" is the iteration-head — reproducibly
 *     deterministic (the same input builds identically twice, the headless anchor), with no
 *     coordinate- or mod-state-dependent jitter source; the coordinate order (candidate (b))
     *     is equally deterministic, but "channel-name order" reads better for users (the ring's
 *     channel names are right there), so plan (a)'s channel-led form is taken.</li>
 * <li><b>Exactly one cut per SCC</b>: one cut already breaks the SCC's strong connectivity
 *     (a self-loop SCC becomes a trivial host by cutting its own edge);
 *     <b>multiple declarations never stack into multiple cuts</b> — every extra cut only
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
     * 恰选一条边——声明边优先、同序最小兜底；声明判定 = 写者普查的
     * {@code feedbackWrites} 含该边通道键。
     * <p>Plan the cuts for a host graph (the pure core, headless-testable): exactly one edge
     * per genuine cyclic component ({@link TopoStats#cyclicComponents}) — declared edges
     * first, the total-order-minimum as the fallback; a declaration test = the writer's
     * census {@code feedbackWrites} contains the edge's channel key.</p>
     *
     * @param g BE 级依赖图（普查含声明面） / the BE-level dependency graph (the census
     *          carries the declaration face)
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
            boolean declaredMode = false;
            // 边视图已按 (通道, 写者, 读者) 全序排列：线性扫一遍，声明模式切换后按序首条
            // 即声明最小；兜底模式下全程取序最小。
            // The edge view is already in the (channel, writer, reader) total order: one
            // linear sweep — after switching into declared mode the first hit is the declared
            // minimum; the fallback mode keeps the order minimum throughout.
            for (TopoStats.ChannelEdge e : g.edges()) {
                if (!scc.contains(e.writer()) || !scc.contains(e.reader())) continue;
                boolean declared = g.censusOf(e.writer()).feedbackWrites().contains(e.channel());
                if (declaredMode && !declared) continue;
                if (declared && !declaredMode) {
                    // 进入声明模式：边序保证这是第一条声明边 = 声明边中的全序最小。
                    // Entering declared mode: the edge order guarantees this is the first
                    // declared edge = the total-order minimum among declared edges.
                    chosen = e;
                    declaredMode = true;
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
