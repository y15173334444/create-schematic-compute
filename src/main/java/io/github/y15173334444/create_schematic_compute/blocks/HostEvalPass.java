package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.graph.CustomNodeCompute;
import io.github.y15173334444.create_schematic_compute.graph.FormulaCompute;
import io.github.y15173334444.create_schematic_compute.graph.HostEvalOrder;
import io.github.y15173334444.create_schematic_compute.graph.TopoStats;
import io.github.y15173334444.create_schematic_compute.network.ConsoleQueryCache;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * <h2>宿主级拓扑序求值遍（§5.2 方案 A 的编排层）——薄胶层</h2>
 *
 * <p>全局拓扑重排的机制面：12 宿主的图求值从各自 BE tick 体（原版注册序）挪到
 * {@code ServerTickEvent.Post} 的<b>宿主级拓扑序一遍求值</b>——无环通道边全部同 tick 收敛
 * （S2 不动点），0/1 tick 翻转窗口全局关闭。编排层只做三件薄事：</p>
 * <ol>
 * <li><b>登记</b>（{@link #arm}）：BE tick 的前段（{@code GraphHost.tickBody}，求值段之前
 *     的一切：BUS 注册/收敛/频段、LIT、图变更检测、重编译、停机路径、世界侧输入采样）跑完
 *     且未早退时，把宿主 + 其 {@link GraphHost.TickHooks} + 维度 id 登记进本 tick 的待求值
 *     集。只有本 tick 真正走过 tick 前段的宿主才登记——未 tick（区块不活跃等）的宿主绝不
 *     求值，行为面与前状态一致。</li>
 * <li><b>求值遍</b>（{@link #runPostPass}，模组入口 Post 监听器在
 *     {@code TopoStats.postCollectSummary} <b>之前</b>调用——本 tick 记录与本 tick 写入的
 *     比对才成立）：先做 §5.5 强制连带（FORMULA / 自定义节点两个预算池的「去重清空 +
 *     deadline 建立」从 Pre 挪到求值遍起点——否则循环型公式被压到不前进）；再经
 *     {@code ConsoleQueryCache.hostGraph()} 取本 tick 的 BE 级依赖图（按 tick 缓存复用，
 *     每 tick 至多构图一次），{@link HostEvalOrder#evaluationOrder} 产出确定拓扑序
 *     （§5.6：环分量显式 SCC，绝不交给 Kahn 顺手吞环），逐宿主调
 *     {@code GraphHost.evalSegment}（⑧buildInputs→⑨evaluate→⑩write→⑪broadcast→⑫频段
 *     同步→⑬触发器差分→尾段）。不在图内的已登记宿主（空普查——不读不写通道，求值序无
 *     语义）按 HostId 全序追加在队尾，求值面一个不丢。</li>
 * <li><b>求值序号登记</b>（{@link #evalOrdinals}）：本遍按求值顺序给每宿主记 1..N 序号——
 *     登记册条目 31.1 的 {@code hosts} 求值序号列就此接线（控制台读上一已完成遍的序号：
 *     查询包在 tick 前段处理，先于本遍）。</li>
 * </ol>
 *
 * <p><b>与 MC 类解耦的边界</b>：序的核心（SCC + 收缩 + Kahn + 切边剔除）全在纯 JDK 的
 * {@link HostEvalOrder}（headless 直测）；本类是引用 {@code GraphHost} / 包事件面的一层
 * 薄胶，无业务逻辑。服务端单线程约束下本类的登记表是普通 {@code ArrayList}（arm 与消费
 * 同线程）；序号表以不可变引用发布（控制台读侧任意线程安全）。</p>
 *
 * <hr>
 * <h2>The host-level topological evaluation pass (§5.2 plan A's orchestration layer) —
 * thin glue.</h2>
 *
 * <p>The mechanism face of the global topology reordering: the twelve hosts' graph
 * evaluation moves out of their own BE tick bodies (vanilla registration order) into
 * <b>one host-level topologically-ordered pass in {@code ServerTickEvent.Post}</b> — every
 * acyclic channel edge converges same-tick (the S2 fixpoint) and the 0/1-tick flip window
 * closes globally. The orchestration layer does three thin things:</p>
 * <ol>
 * <li><b>Arming</b> ({@link #arm}): when a host's BE-tick prologue ({@code
 *     GraphHost.tickBody} — everything ahead of the evaluation segment: BUS
 *     registration/convergence/bands, LIT, graph-change detection, recompiles, the stop
 *     path, world-side input sampling) completes without an early return, the host + its
 *     {@link GraphHost.TickHooks} + dimension id join this tick's pending set. Only hosts
 *     that genuinely ran their prologue this tick arm — a host that did not tick (inactive
 *     chunk etc.) never evaluates, matching the pre-reorder behaviour face.</li>
 * <li><b>The pass</b> ({@link #runPostPass}, called by the mod entry's Post listener
 *     <b>before</b> {@code TopoStats.postCollectSummary} — the record-vs-write compare of
 *     this tick only holds then): first the §5.5 mandatory relocation (both budget pools'
 *     "dedup clear + deadline establishment" move from Pre to the head of the pass —
 *     otherwise loop-heavy formulas get squeezed to a standstill); then this tick's
 *     BE-level dependency graph rides {@code ConsoleQueryCache.hostGraph()} (the per-tick
 *     cache reused — at most one build per tick) and {@link HostEvalOrder#evaluationOrder}
 *     produces the deterministic topological order (§5.6: explicit SCC for cycles, never
 *     handed to Kahn to silently swallow), and each host runs {@code GraphHost.evalSegment}
 *     (⑧ buildInputs → ⑨ evaluate → ⑩ write → ⑪ broadcast → ⑫ band sync → ⑬ flipflop diff
 *     → the tail). Armed hosts outside the graph (an empty census — they neither read nor
 *     write channels, so their order position carries no semantics) append at the tail in
 *     HostId total order — not one evaluation lost.</li>
 * <li><b>Eval-ordinal registration</b> ({@link #evalOrdinals}): the pass stamps each host
 *     1..N in evaluation order — register entry 31.1's {@code hosts} evaluation-ordinal
 *     column wires up here (the console reads the last completed pass's ordinals: query
 *     packets are processed in the tick's prologue, ahead of this pass).</li>
 * </ol>
 *
 * <p><b>The MC-decoupling boundary</b>: the order core (SCC + condensation + Kahn + cut-edge
 * exclusion) is pure JDK in {@link HostEvalOrder} (directly testable headless); this class
 * is one thin glue layer referencing {@code GraphHost} / the event face, carrying no
 * business logic. Under the single-server-thread constraint the arm list is a plain
 * {@code ArrayList} (arming and consumption share one thread); the ordinal table publishes
 * an immutable reference (console readers may come from any thread).</p>
 */
public final class HostEvalPass {

    private HostEvalPass() {}

    /** 本 tick 的待求值登记（服务端单线程；runPostPass 消费后清空）。
     *  This tick's pending evaluation arms (single server thread; consumed and cleared by
     *  runPostPass). */
    private static final ArrayList<Armed> ARMED = new ArrayList<>();

    /** 一条待求值登记：宿主身份 + 引擎 + 声明钩子 + 维度 id（求值段在宿主自己的维度键空间
     *  内跑——与前段同一作用域形态）。
     *  One pending arm: the host identity + engine + declared hooks + dimension id (the
     *  evaluation segment runs inside the host's own dimension keyspace — the same scope
     *  shape as the prologue). */
    private static final class Armed {
        final TopoStats.HostId id;
        final GraphHost host;
        final GraphHost.TickHooks hooks;
        final String dimensionId;

        Armed(TopoStats.HostId id, GraphHost host, GraphHost.TickHooks hooks, String dimensionId) {
            this.id = id;
            this.host = host;
            this.hooks = hooks;
            this.dimensionId = dimensionId;
        }
    }

    /** 最近一次已完成求值遍的逐宿主求值序号（HostId 有序的不可变快照；查询面=控制台
     *  {@code hosts} 列。消费包处理于 tick 前段，读到的总是上一已完成遍）。volatile：
     *  服务端写、读者可能来自其它线程，引用换发保可见性。
     *  The eval ordinals of the most recently completed pass (an immutable HostId-ordered
     *  snapshot; consumer = the console {@code hosts} column. Query packets are processed
     *  in the tick prologue, so readers always see the last completed pass). volatile: the
     *  server writes, readers may come from other threads — reference swapping keeps
     *  visibility honest. */
    private static volatile Map<TopoStats.HostId, Integer> evalOrdinals = Map.of();

    /** 登记一个已完成 tick 前段的宿主（{@code GraphHost.tickBody} 前段末尾调用；同宿主重复
     *  登记由消费端按首条保留——正常一 tick 一宿主恰一次，防御面不产生双求值）。
     *  Arm a host whose tick prologue completed (called at the tail of {@code
     *  GraphHost.tickBody}'s prologue; duplicate arms keep the first at consumption — one
     *  host arms at most once per tick in practice, and the guard makes double evaluation
     *  impossible). */
    public static void arm(TopoStats.HostId id, GraphHost host, GraphHost.TickHooks hooks,
                           String dimensionId) {
        ARMED.add(new Armed(id, host, hooks, dimensionId));
    }

    /** 上一已完成求值遍的求值序号（空 = 本遍未见/未求值）。
     *  The last completed pass's eval ordinal (absent = not seen / not evaluated this
     *  pass). */
    public static Map<TopoStats.HostId, Integer> evalOrdinals() { return evalOrdinals; }

    /** 仅测试用：清空登记面与序号面（生产面靠每 tick 自然消费——arm 在 BE tick、消费在
     *  Post，同一 tick 内一进一出）。
     *  Test-only: clear the arm and ordinal faces (production needs none — arms land in the
     *  BE tick and are consumed in Post, one in / one out per tick). */
    static void resetForTest() {
        ARMED.clear();
        evalOrdinals = Map.of();
    }

    /**
     * Post 求值遍（模组入口 {@code ServerTickEvent.Post} 监听器调用，排在
     * {@code TopoStats.postCollectSummary} 之前）：§5.5 预算基准搬迁 + 拓扑序逐宿主求值段
     * + 求值序号登记。服务端单线程；异常路径与既有 BE tick 体一致向上传播（无伪造兜底）。
     * <p>The Post evaluation pass (called by the mod entry's {@code ServerTickEvent.Post}
     * listener, ahead of {@code TopoStats.postCollectSummary}): the §5.5 budget-baseline
     * relocation + the topologically ordered per-host evaluation segments + the eval-ordinal
     * registration. Single server thread; exceptions propagate exactly like the old BE tick
     * bodies (no fabricated fallbacks).</p>
     */
    public static void runPostPass() {
        // §5.5 强制连带（规格原文：接上兜底的那次提交必须同时做基准搬迁）：预算池的
        // 「去重清空 + deadline 建立」从 Pre 挪到求值遍起点——求值现在发生在 Post，Pre 时点
        // 建立的 deadline 隔着实体刻/区块刻/网络，会把循环型公式压到不前进。
        // The §5.5 mandatory relocation (the spec verbatim: the commit wiring the backstop
        // must also relocate the baseline): both pools' "dedup clear + deadline
        // establishment" move from Pre to the head of the evaluation pass — evaluation now
        // happens in Post, and a deadline established at Pre (with entity ticks, chunk ticks
        // and networking in between) would squeeze loop-heavy formulas to a standstill.
        FormulaCompute.beginTick();
        CustomNodeCompute.beginTick();

        if (ARMED.isEmpty()) {
            evalOrdinals = Map.of();
            return;
        }
        // 同宿主重复登记按首条保留（防御面）。/ Duplicate arms keep the first (guard face).
        var byId = new LinkedHashMap<TopoStats.HostId, Armed>();
        for (Armed a : ARMED) byId.putIfAbsent(a.id, a);

        // 本 tick 的 BE 级依赖图：ConsoleQueryCache 的按 tick 缓存复用——控制台查询与本遍
        // 共享同一构图（每 tick 至多一次，O(hosts+边)）。
        // This tick's BE-level dependency graph: the ConsoleQueryCache per-tick cache is
        // reused — console queries and this pass share one build (at most one per tick,
        // O(hosts + edges)).
        var g = ConsoleQueryCache.hostGraph();
        List<TopoStats.HostId> order = HostEvalOrder.evaluationOrder(g, Set.of());

        // 图外已登记宿主（空普查——不读不写通道，序位置无语义）按 HostId 全序追加队尾。
        // Armed hosts outside the graph (empty census — neither reading nor writing channels;
        // the order position carries no semantics) append at the tail in HostId total order.
        var outside = new TreeSet<TopoStats.HostId>();
        for (TopoStats.HostId id : byId.keySet())
            if (!g.hosts().contains(id)) outside.add(id);
        var fullOrder = new ArrayList<TopoStats.HostId>(order.size() + outside.size());
        fullOrder.addAll(order);
        fullOrder.addAll(outside);

        var ordinals = new TreeMap<TopoStats.HostId, Integer>();
        int i = 1;
        for (TopoStats.HostId id : fullOrder) {
            Armed a = byId.get(id);
            if (a == null) continue;   // 图内宿主本 tick 未走过前段（未 tick）：跳过 / an in-graph host that never ran its prologue this tick: skip
            ordinals.put(id, i++);
            a.host.evalSegment(a.hooks, a.dimensionId);
        }
        evalOrdinals = Collections.unmodifiableSortedMap(ordinals);
        ARMED.clear();
    }
}
