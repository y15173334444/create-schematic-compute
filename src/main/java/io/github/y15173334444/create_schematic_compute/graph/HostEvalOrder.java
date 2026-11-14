package io.github.y15173334444.create_schematic_compute.graph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * <h2>宿主级求值序的核心（§5.2 方案 A / §5.6）——纯 JDK，零 MC import</h2>
 *
 * <p>把「每宿主各自在 BE tick 里求值（原版注册序，0/1 tick 翻转不确定）」改成「{@code
 * ServerTickEvent.Post} 的宿主级拓扑序一遍求值」之后，<b>求值遍历顺序由本类产出</b>。
 * 通道键按维度隔离（{@code SignalBus.key()} 前缀）→ 通道边天然同维度，拓扑序按维度内
 * 计算即可；跨维度宿主互相独立，全局遍历顺序不影响语义——本类仍产出一份<b>全局确定序</b>
 * （跨维度靠 HostId 全序自然分层），使诊断与测试可复现。</p>
 *
 * <p><b>§5.6 的铁律不在本类手上犯</b>：naive 全局拓扑（Kahn 只把入度归零者放进有序表）
 * 遇到跨 BE 通道环会让<b>整条环上的宿主静默停更</b>——比现状的 1 tick 糟糕得多。因此本类
 * 先做 SCC（{@link TopoStats#stronglyConnectedComponents}），把图收缩成分量 DAG 后按
 * Kahn 排分量；<b>环分量内部的成员永远全部进序</b>（环上切边落地前，分量内按 HostId 全序
 * 求值——确定性取代原版注册序的偶然性；切边批落地后，被切边不进序约束，分量内余下的
 * DAG 部分按 Kahn 有序，残余环仍按 HostId 全序兜底，绝不丢成员）。</p>
 *
 * <p><b>被切边（{@code cutEdges}）不进序约束</b>：被切边按语义恒定跨 tick（读上一 tick 末
 * 快照），它不是同 tick 依赖——排序时从边集里剔除（§5.3 切边批的接线点；观测批传空集即
 * 回到纯 §5.2 A 形态）。</p>
 *
 * <hr>
 * <h2>The host-level evaluation-order core (§5.2 plan A / §5.6) — pure JDK, zero MC
 * imports.</h2>
 *
 * <p>Once "each host evaluates inside its own BE tick (vanilla registration order, an
 * uncertain 0/1-tick flip)" becomes "one host-level topological evaluation pass in
 * {@code ServerTickEvent.Post}", <b>the traversal order is produced here</b>. Channel keys
 * are dimension-scoped ({@code SignalBus.key()} prefix) so channel edges are naturally
 * same-dimension — the topological order is computed within a dimension; hosts across
 * dimensions are independent and the global traversal order does not affect semantics. This
 * class still produces one <b>globally deterministic order</b> (cross-dimension layering
 * falls out of the HostId total order) so diagnostics and tests replay identically.</p>
 *
 * <p><b>§5.6's iron rule is not violated here</b>: a naive global topology (Kahn admitting
 * only in-degree-zero nodes) silently freezes every host on a cross-BE channel cycle — far
 * worse than the status quo's 1 tick. So this class runs SCC first
 * ({@link TopoStats#stronglyConnectedComponents}), condenses the graph into a component DAG
 * and Kahn-orders the components; <b>every member of a cyclic component always enters the
 * order</b> (before edge cutting lands, members evaluate in HostId total order —
 * determinism replacing the vanilla registration order's accident; after the edge-cutting
 * batch, a cut edge leaves the order constraints and the component's remaining DAG part
 * Kahn-orders itself, with any still-cyclic residue falling back to the HostId total order —
 * never dropping a member).</p>
 *
 * <p><b>Cut edges ({@code cutEdges}) never enter the order constraints</b>: a cut edge is
 * by semantics permanently cross-tick (it reads last tick's committed snapshot) and is not a
 * same-tick dependency — it is excluded from the edge set at ordering time (the §5.3
 * edge-cutting batch's wiring point; the observation batches pass the empty set, which is
 * exactly the plain §5.2 A shape).</p>
 */
public final class HostEvalOrder {

    private HostEvalOrder() {}

    /**
     * 宿主求值序（全图宿主恰好各出现一次；空图返回空表）。确定性锚：同一图集 + 同一切边集
     * 任意次调用输出逐位相同。
     * <p>The host evaluation order (every host in the graph appears exactly once; an empty
     * graph yields an empty list). Determinism anchor: the same graph set + cut-edge set
     * replays bit-identically any number of times.</p>
     *
     * <p>算法 / Algorithm：
     * <ol>
     * <li>SCC 分区（Tarjan，{@link TopoStats#stronglyConnectedComponents} 的输出已按分量
     *     最小成员排序）。</li>
     * <li>收缩成分量 DAG：非切边的跨分量边成为依赖（读者分量依赖写者分量——写者先求值）；
     *     切边（{@code cutEdges} 内的边）一概剔除，不产生任何依赖。</li>
     * <li>分量级 Kahn：就绪集恒取「最小成员序」最小的分量（分量已按最小成员排序，取下标
     *     序即全序确定）。</li>
     * <li>分量内部：平凡分量（大小 1 且无自环）直接出成员；环分量在「内部边 − 切边」上做
     *     成员级 Kahn（就绪集取 HostId 最小者），残余的仍成环成员按 HostId 全序追加——
     *     §5.6 口径：成员一个不丢，绝不静默停更。</li>
     * </ol>
     * <ol>
     * <li>SCC partition (Tarjan; {@link TopoStats#stronglyConnectedComponents}'s output is
     *     already ordered by each component's smallest member).</li>
     * <li>Condense into a component DAG: cross-component non-cut edges become dependencies
     *     (a reader's component depends on the writer's — writers evaluate first); cut edges
     *     (edges inside {@code cutEdges}) are excluded outright and produce no dependency.</li>
     * <li>Component-level Kahn: the ready set always takes the component with the smallest
     *     minimum member (components are already sorted by their minimum member, so index
     *     order is the deterministic total order).</li>
     * <li>Within a component: a trivial component (size 1, no self-loop) yields its member;
     *     a cyclic component runs member-level Kahn over (internal edges − cut edges) with
     *     the ready set taking the smallest HostId, and any still-cyclic residue appends in
     *     HostId total order — the §5.6 rule: not one member dropped, never a silent
     *     freeze.</li>
     * </ol>
     *
     * @param g         BE 级依赖图（普查非空宿主） / the BE-level dependency graph
     *                  (census-non-empty hosts)
     * @param cutEdges  被切边集合（恒跨 tick，不进序约束；null 视为空集） / the cut-edge set
     *                  (permanently cross-tick, excluded from ordering; null = empty)
     * @return 全图宿主的确定求值序 / the deterministic evaluation order of every host in the
     *         graph
     */
    public static List<TopoStats.HostId> evaluationOrder(TopoStats.HostGraph g,
                                                         Set<TopoStats.ChannelEdge> cutEdges) {
        var out = new ArrayList<TopoStats.HostId>();
        if (g == null || g.hosts().isEmpty()) return out;
        Set<TopoStats.ChannelEdge> cuts = cutEdges != null ? cutEdges : Set.of();

        // 1. SCC 分区（输出已按分量最小成员排序——分量下标序即确定的全序键）。
        //    1. SCC partition (the output is ordered by each component's smallest member —
        //       the component index order is the deterministic total-order key).
        var sccs = TopoStats.stronglyConnectedComponents(g);
        Map<TopoStats.HostId, Integer> compOf = new HashMap<>();
        for (int i = 0; i < sccs.size(); i++)
            for (TopoStats.HostId h : sccs.get(i)) compOf.put(h, i);

        // 2. 收缩：分量 → 前置分量集（写者分量先于读者分量）；分量内邻接（writer → readers，
        //    减切边）。自环边落在分量内部邻接里，使带自环的平凡分量永远成不了「就绪」——
        //    与环分量走同一条残余兜底（成员仍求值，只是无同 tick 序约束）。
        //    2. Condense: component → prerequisite component set (writer components before
        //       reader components); the per-component internal adjacency (writer → readers,
        //       minus cut edges). A self-loop edge lands in the internal adjacency, so a
        //       trivial component carrying one never becomes "ready" — it rides the same
        //       residual fallback as cyclic components (the member still evaluates, it just
        //       carries no same-tick order constraint).
        int n = sccs.size();
        var prereqs = new ArrayList<Set<Integer>>(n);
        var internalAdj = new ArrayList<Map<TopoStats.HostId, Set<TopoStats.HostId>>>(n);
        for (int i = 0; i < n; i++) {
            prereqs.add(new HashSet<>());
            internalAdj.add(new HashMap<>());
        }
        for (TopoStats.ChannelEdge e : g.edges()) {
            if (cuts.contains(e)) continue;   // 被切边：跨 tick，不是同 tick 依赖 / cut: cross-tick, no same-tick dependency
            int cw = compOf.get(e.writer()), cr = compOf.get(e.reader());
            if (cw != cr) prereqs.get(cr).add(cw);
            else internalAdj.get(cr).computeIfAbsent(e.writer(), k -> new HashSet<>()).add(e.reader());
        }

        // 3. 分量级 Kahn（就绪集取最小下标；下标序 = 最小成员序）。
        //    3. Component-level Kahn (the ready set takes the smallest index; index order =
        //       minimum-member order).
        int[] remaining = new int[n];
        Map<Integer, Set<Integer>> dependents = new HashMap<>();
        for (int c = 0; c < n; c++) {
            remaining[c] = prereqs.get(c).size();
            for (int p : prereqs.get(c)) dependents.computeIfAbsent(p, k -> new TreeSet<>()).add(c);
        }
        var ready = new TreeSet<Integer>();
        for (int c = 0; c < n; c++) if (remaining[c] == 0) ready.add(c);
        while (!ready.isEmpty()) {
            int c = ready.pollFirst();
            emitComponent(sccs.get(c), internalAdj.get(c), out);
            for (int d : dependents.getOrDefault(c, Set.of()))
                if (--remaining[d] == 0) ready.add(d);
        }
        return out;
    }

    /**
     * 单分量出序：平凡分量直接出成员；环分量在「内部边 − 切边」（切边已在
     * {@code internalAdj} 剔除）上做成员级 Kahn，残余成环成员按 HostId 全序追加。
     * <p>Emit one component: a trivial component yields its member; a cyclic component runs
     * member-level Kahn over the internal adjacency (cut edges already removed), appending
     * any still-cyclic residue in HostId total order.</p>
     */
    private static void emitComponent(SortedSet<TopoStats.HostId> scc,
                                      Map<TopoStats.HostId, Set<TopoStats.HostId>> adj,
                                      List<TopoStats.HostId> out) {
        if (adj.isEmpty()) {
            // 无内部边（平凡分量；或环分量的边全被切光——理论边界）：成员按序直出。
            // No internal edges (a trivial component; or a cyclic component whose edges are
            // all cut — a theoretical boundary): members emit in order.
            out.addAll(scc);
            return;
        }
        // 成员级 Kahn：入度 = 内部边计数（自环给成员自带 1 入度 → 永不入就绪 → 落残余）。
        // Member-level Kahn: in-degree = internal edge count (a self-loop pins one in-degree
        // on its own member → never ready → falls into the residue).
        var indegree = new TreeMap<TopoStats.HostId, Integer>();
        for (TopoStats.HostId h : scc) indegree.put(h, 0);
        for (var e : adj.entrySet())
            for (TopoStats.HostId r : e.getValue())
                indegree.merge(r, 1, Integer::sum);
        var memberReady = new TreeSet<TopoStats.HostId>();
        for (var e : indegree.entrySet()) if (e.getValue() == 0) memberReady.add(e.getKey());
        int emitted = 0;
        while (!memberReady.isEmpty()) {
            TopoStats.HostId h = memberReady.pollFirst();
            out.add(h);
            emitted++;
            for (TopoStats.HostId r : adj.getOrDefault(h, Set.of()))
                if (indegree.merge(r, -1, Integer::sum) == 0) memberReady.add(r);
        }
        // 残余（仍成环或自环）：按 HostId 全序追加——§5.6 铁律：成员一个不丢。
        // The residue (still cyclic or self-looped): append in HostId total order — the
        // §5.6 iron rule: not one member dropped.
        if (emitted < scc.size())
            for (TopoStats.HostId h : scc)
                if (indegree.get(h) > 0) out.add(h);
    }
}
