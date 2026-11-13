package io.github.y15173334444.create_schematic_compute.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * <h2>跨 BE 观测设施的结构核心（§6.4 步骤 3 / issue #25）——零行为接入</h2>
 *
 * <p>三件事，全部纯 JDK（headless 直测，MC 类一概不引用——{@code network/HostChannelCensus}
 * 的枚举胶水与 {@code blocks/GraphHost} 的生命周期挂钩负责把 MC 世界翻译成本类的纯数据）：</p>
 * <ol>
 * <li><b>全局 host 注册表</b>：宿主生命周期增删条目。身份 = 维度 + 方块坐标
 * （{@link HostId}），注册表<b>跨维度平铺</b>（{@code ConcurrentSkipListMap} 按 HostId 排序，
 * 不按维度分桶——§3.4「进程内总线无维度作用域，注册表与 DAG 必须同样跨维度建」，通道边
 * 才是按维度键空间天然隔离的，见 {@code HostChannelCensus}）。区块卸载/重载的稳健性来自
 * 身份键的幂等性：卸载/移除摘除条目、重载按同键重登（替换供给器，不累积重复条目）——
 * 步骤 0 实测过重载翻转行为，注册表对重载必须无幽灵也无丢失。</li>
 * <li><b>BE 级依赖图（{@link HostGraph}）</b>：host 为节点，通道为边（写者 → 读者——读者
 * 依赖写者的值；§3.1 的「沿依赖图向下游传播受影响标记」的下游就是读者侧）。边带通道标签
 * （{@link ChannelEdge}），<b>host 自环也进图</b>（同 host 主图与 ENCAP 子图可能读写同一
 * 通道，§3.2 顺带推论）——自环让该 host 成为大小 1 的真环 SCC，绝不能只算跨 host 边。
 * <b>宿主范围口径（§3.2）</b>：普查为空（停机宿主既不读也不写）的 host 不进图——分母纪律
 * 在构图处一次性落实。同一 host 对之间可有多条边（不同通道），邻接表按 host 去重。</li>
 * <li><b>SCC 计算</b>：Tarjan 强连通分量（{@link #stronglyConnectedComponents}），环分量 =
 * 大小 &gt; 1 或含自环（{@link #cyclicComponents}）。环检测是 §5.3 切边策略的唯一前置；
 * 输出全序确定（根与邻接均按 HostId 排序、分量按最小成员排序）——诊断与将来的稳定切边
 * 都要可复现的输出。</li>
 * </ol>
 *
 * <p><b>通道身份 = {@code SignalBus.key()} 前缀后的键</b>（{@code <维度id>\u0001<逻辑名>}）：
 * 由 network 侧的普查胶水在宿主维度作用域内经该单一入口产出，本类绝不复刻前缀格式
 * （单一源纪律）。因此通道边天然同维度——同一条边两端必然落在同一维度的键空间里；
 * 跨维度的只有注册表（列宿主），不是通道边。</p>
 *
 * <p><b>与 MC 类解耦</b>（{@code network/C2SPacketRateGuard} 先例）：本类零 MC import，
 * 普查数据经 {@link CensusProvider} 缝注入（生产 = GraphHost 的活图普查 lambda，测试 =
 * 合成供给器）；「注册表真相」（所有在场的宿主）与「求值真相」（实际读写的宿主，进
 * {@link HostGraph}）分开——前者由生命周期维护，后者按 §3.2 口径在构图时过滤。</p>
 *
 * <p><b>步骤 4 接口形状</b>：采集（epoch + 记录点）将复用本类的 HostId/ChannelCensus 形状
 * 与 buildHostGraph 的边集做受影响传播；本类不持任何 per-tick 状态（注册表是生命周期态，
 * 不是 tick 态），采集的静态收集表属步骤 4 另行添加。</p>
 *
 * <hr>
 * <h2>Structural core of the cross-BE observation facility (§6.4 step 3 / issue #25) —
 * zero behaviour integration</h2>
 *
 * <p>Three pieces, all pure JDK (directly testable headless; no MC imports at all — the
 * enumeration glue in {@code network/HostChannelCensus} and the lifecycle hooks in
 * {@code blocks/GraphHost} translate the MC world into this class's plain data):</p>
 * <ol>
 * <li><b>Global host registry</b>: entries added/removed from host lifecycle hooks. Identity =
 * dimension + block position ({@link HostId}); the registry is <b>flat across dimensions</b>
 * (a {@code ConcurrentSkipListMap} ordered by HostId, never bucketed per dimension — §3.4:
 * "the process-wide bus has no dimension scope, so the registry and the DAG must be built
 * across dimensions too"). It is the channel *edges* that are per-dimension by the keyspace
 * (see {@code HostChannelCensus}). Chunk unload/reload robustness comes from the identity
 * key's idempotence: unload/remove drops the entry, a reload re-registers under the same key
 * (replacing the provider, never accumulating duplicates) — step 0 measured reload flipping
 * the stale-read behaviour, so the registry must have neither ghosts nor losses across
 * reloads.</li>
 * <li><b>BE-level dependency graph ({@link HostGraph})</b>: hosts are nodes, channels are
 * edges (writer → reader — the reader depends on the writer's value; the "downstream" of
 * §3.1's affected-mark propagation is the reader side). Edges carry the channel label
 * ({@link ChannelEdge}), and <b>host self-loops enter the graph too</b> (one host's main
 * graph and its ENCAP sub-graph may read/write the same channel, §3.2's corollary) — a
 * self-loop makes that host a genuine size-1 cyclic SCC; cross-host edges alone would be
 * wrong. <b>Host-scope criterion (§3.2)</b>: a host whose census is empty (a stopped host
 * neither reads nor writes) does not enter the graph — the denominator discipline is applied
 * once, at graph construction. Multiple edges may exist between the same host pair
 * (different channels); the adjacency deduplicates by host.</li>
 * <li><b>SCC computation</b>: Tarjan strongly connected components
 * ({@link #stronglyConnectedComponents}); a cyclic component = size &gt; 1 or containing a
 * self-loop ({@link #cyclicComponents}). Cycle detection is the sole prerequisite of the §5.3
 * edge-cutting strategy; output is totally deterministic (roots and neighbours ordered by
 * HostId, components ordered by their smallest member) — diagnostics and future stable edge
 * cutting both need reproducible output.</li>
 * </ol>
 *
 * <p><b>Channel identity = the {@code SignalBus.key()}-prefixed key</b>
 * ({@code <dimensionId>\u0001<logical>}): produced by the network-side census glue inside the
 * host's dimension scope through that single normalization entry — this class never
 * replicates the prefix format (single-source discipline). Channel edges are therefore
 * naturally same-dimension — both ends of one edge always land in one dimension's keyspace;
 * what crosses dimensions is the registry (listing hosts), never a channel edge.</p>
 *
 * <p><b>Decoupled from MC classes</b> (the {@code network/C2SPacketRateGuard} precedent):
 * zero MC imports here; census data rides the {@link CensusProvider} seam (production =
 * GraphHost's live-graph census lambda, tests = synthetic providers). "Registry truth" (every
 * present host) and "evaluation truth" (hosts actually reading/writing, which enter
 * {@link HostGraph}) are kept apart — the former is maintained by lifecycle hooks, the latter
 * is filtered at construction by the §3.2 criterion.</p>
 *
 * <p><b>Interface shape left for step 4</b>: the observation (epoch + record points) will
 * reuse this class's HostId/ChannelCensus shapes and buildHostGraph's edge set for affected
 * propagation; this class holds no per-tick state (the registry is lifecycle state, not tick
 * state) — step 4 adds its own static collector tables.</p>
 */
public final class TopoStats {

    private TopoStats() {}

    // ── 1. 全局 host 注册表 / global host registry ──────────────────────────────

    /** 宿主身份键：维度 id + 方块坐标（§3.4）。实现 {@link Comparable} 使注册表与全部派生
     *  结构（边、SCC）天然全序确定——诊断与稳定切边要求可复现输出。
     *  Host identity key: dimension id + block position (§3.4). Implements {@link Comparable}
     *  so the registry and every derived structure (edges, SCCs) have a natural total order —
     *  diagnostics and future stable edge cutting need reproducible output. */
    public record HostId(String dimension, int x, int y, int z) implements Comparable<HostId> {
        @Override public int compareTo(HostId o) {
            int c = dimension.compareTo(o.dimension);
            if (c != 0) return c;
            if (x != o.x) return Integer.compare(x, o.x);
            if (y != o.y) return Integer.compare(y, o.y);
            return Integer.compare(z, o.z);
        }
        @Override public String toString() { return dimension + " " + x + "," + y + "," + z; }
    }

    /** 宿主的通道普查：读集（BUS_IN / PRIVATE_IN 枚举）与写集（BUS_OUT / PRIVATE_OUT 枚举），
     *  元素 = {@code SignalBus.key()} 前缀后的通道键。不可变、有序（TreeSet 归一）。
     *  A host's channel census: the read set (enumerated from BUS_IN / PRIVATE_IN) and the
     *  write set (enumerated from BUS_OUT / PRIVATE_OUT), elements being
     *  {@code SignalBus.key()}-prefixed channel keys. Immutable and ordered (TreeSet-normalized). */
    public record ChannelCensus(Set<String> reads, Set<String> writes) {
        /** 归一构造：null 收敛为空集 + 不可变有序副本。 / Normalizing constructor: nulls
         *  collapse to empty sets + immutable ordered copies. */
        public ChannelCensus {
            reads = Collections.unmodifiableSortedSet(
                new TreeSet<>(reads != null ? reads : Set.<String>of()));
            writes = Collections.unmodifiableSortedSet(
                new TreeSet<>(writes != null ? writes : Set.<String>of()));
        }
        /** 空普查常量：停机宿主（§3.2 口径）与测试占位用。 / The empty census: stopped hosts
         *  (the §3.2 criterion) and test placeholders. */
        public static final ChannelCensus EMPTY = new ChannelCensus(Set.of(), Set.of());
        /** 读集与写集皆空。 / Both sets empty. */
        public boolean isEmpty() { return reads.isEmpty() && writes.isEmpty(); }
    }

    /** 普查供给缝（判定与执行分离，{@code C2SPacketRateGuard} 先例）：生产实现 = GraphHost
     *  的活普查 lambda（每次调用现枚举当前图，图变更零缓存失效问题）；headless 测试实现 =
     *  合成供给器。
     *  The census provider seam (decision/execution split, the {@code C2SPacketRateGuard}
     *  precedent): the production implementation is GraphHost's live-census lambda (each call
     *  enumerates the current graph, so graph changes never need cache invalidation); the
     *  headless-test implementation is a synthetic provider. */
    public interface CensusProvider {
        /** 当前普查快照；停机宿主返回 {@link ChannelCensus#EMPTY}（§3.2 范围口径）。
         *  The current census snapshot; a stopped host returns {@link ChannelCensus#EMPTY}
         *  (the §3.2 scope criterion). */
        ChannelCensus census();
    }

    /** 注册表（进程级单例，<b>跨维度平铺</b>不按维度分桶，§3.4）：宿主身份 → 普查供给器。
     *  跳表使快照天然按 HostId 有序（诊断与测试的全序确定）。服务端单线程生命周期钩子增删，
     *  但观测/控制台读侧可能来自任意调用点——按并发容器写以自洽。
     *  The registry (process-level singleton, <b>flat across dimensions</b>, never bucketed
     *  per dimension, §3.4): host identity → census provider. The skip list keeps snapshots
     *  naturally ordered by HostId (a total order for diagnostics and tests). Lifecycle hooks
     *  add/remove from the single server thread, but observation/console readers may come from
     *  anywhere — concurrent containers keep that honest. */
    private static final ConcurrentSkipListMap<HostId, CensusProvider> HOSTS = new ConcurrentSkipListMap<>();

    /** 宿主在场：登记（或按同键替换——重载稳健性：区块卸载后重登不产生第二条目，供给器
     *  以最新注册的为准）。 / Host present: register (or replace under the same key — reload
     *  robustness: a re-registration after a chunk unload never produces a second entry; the
     *  latest provider wins). */
    public static void hostLoaded(HostId id, CensusProvider provider) {
        if (id == null || provider == null) return;
        HOSTS.put(id, provider);
    }

    /** 宿主离场：摘除条目。幂等——重复摘除（区块卸载路径与移除路径都可能各自触发）或
     *  摘除从未登记的键都安全（返回 false）。
     *  Host gone: drop the entry. Idempotent — a repeated removal (both the chunk-unload and
     *  the remove paths may fire) or a removal of a never-registered key is safe (returns
     *  false). */
    public static boolean hostGone(HostId id) {
        return id != null && HOSTS.remove(id) != null;
    }

    /** 当前登记的宿主数（测试/观测面）。 / Number of currently registered hosts
     *  (test/observation view). */
    public static int hostCount() { return HOSTS.size(); }

    /** 注册表快照（按 HostId 有序的防御性副本）：DAG 构建的输入面——先快照后逐宿主普查，
     *  生命周期变更不撕裂单次构建。 / Registry snapshot (a defensive, HostId-ordered copy):
     *  the input face of DAG building — snapshot first, census per host second, so lifecycle
     *  changes never tear a single build. */
    public static Map<HostId, CensusProvider> hostsSnapshot() { return new TreeMap<>(HOSTS); }

    /** 清空注册表（测试复位用；生产服务端停止面的接线属步骤 4/5 的挂钩面，本期零行为接入
     *  不碰模组入口）。 / Clear the registry (test reset; wiring the production server-stop
     *  face belongs to step 4/5 hook surfaces — this step's zero-behaviour rule leaves the mod
     *  entry untouched). */
    public static void clearRegistry() { HOSTS.clear(); }

    // ── 2. BE 级依赖图 / BE-level dependency graph ──────────────────────────────

    /** 通道边：写者 → 读者（读者依赖写者经该通道产出的值），通道键 = 前缀后的身份。
     *  同 host 自环 = {@code writer.equals(reader)}。实现 {@link Comparable} 使边集全序确定。
     *  A channel edge: writer → reader (the reader depends on the value the writer produces
     *  through this channel); the channel key is the prefixed identity. A host self-loop is
     *  {@code writer.equals(reader)}. Implements {@link Comparable} for a total edge order. */
    public record ChannelEdge(String channel, HostId writer, HostId reader)
        implements Comparable<ChannelEdge> {
        @Override public int compareTo(ChannelEdge o) {
            int c = channel.compareTo(o.channel);
            if (c != 0) return c;
            c = writer.compareTo(o.writer);
            if (c != 0) return c;
            return reader.compareTo(o.reader);
        }
    }

    /** BE 级依赖图：节点 = 普查非空的宿主（§3.2 分母纪律在构图处落实），边 = 通道边
     *  （写者 → 读者，自环在内，带通道标签，全序确定）。不可变。
     *  The BE-level dependency graph: nodes = hosts with a non-empty census (the §3.2
     *  denominator discipline applied at construction), edges = channel edges (writer →
     *  reader, self-loops included, channel-labelled, totally ordered). Immutable. */
    public static final class HostGraph {
        private final TreeMap<HostId, ChannelCensus> hosts;
        private final List<ChannelEdge> edges;
        /** 写者 → 直接读者邻接（按 host 去重；SCC 与将来的受影响传播走这里，通道标签走
         *  {@link #edges}）。 / Writer → direct-reader adjacency (deduplicated by host; SCC
         *  and future affected-mark propagation ride this, channel labels ride {@link #edges}). */
        private final Map<HostId, Set<HostId>> downstream;

        private HostGraph(TreeMap<HostId, ChannelCensus> hosts,
                          List<ChannelEdge> edges,
                          Map<HostId, Set<HostId>> downstream) {
            this.hosts = hosts;
            this.edges = edges;
            this.downstream = downstream;
        }

        /** 节点视图（普查非空宿主，按 HostId 有序）。 / The node view (census-non-empty
         *  hosts, ordered by HostId). */
        public SortedSet<HostId> hosts() {
            return Collections.unmodifiableSortedSet(new TreeSet<>(hosts.keySet()));
        }

        /** 某宿主的普查（未入图 = 无普查可给，返回 EMPTY）。 / One host's census (EMPTY when
         *  the host is not in the graph). */
        public ChannelCensus censusOf(HostId h) { return hosts.getOrDefault(h, ChannelCensus.EMPTY); }

        /** 边视图（全序确定：通道 → 写者 → 读者；自环在内）。 / The edge view (totally
         *  ordered: channel → writer → reader; self-loops included). */
        public List<ChannelEdge> edges() { return Collections.unmodifiableList(edges); }

        /** 写者的直接读者集（无读者返回空集）。 / The writer's direct readers (empty when none). */
        public Set<HostId> downstreamOf(HostId writer) {
            return downstream.getOrDefault(writer, Set.of());
        }

        /** 该宿主是否有自环（读自己写的通道）。 / Whether this host has a self-loop (reads a
         *  channel it writes). */
        public boolean hasSelfLoop(HostId h) { return downstreamOf(h).contains(h); }

        /** 空图判定（零宿主且零边）。 / Empty-graph test (zero hosts and zero edges). */
        public boolean isEmpty() { return hosts.isEmpty() && edges.isEmpty(); }
    }

    /**
     * 从逐宿主普查构建 BE 级依赖图（纯核心，headless 直测）。
     * <p>Build the BE-level dependency graph from per-host censuses (the pure core, directly
     * testable headless).</p>
     * <p>规则：普查为空的宿主不进节点集（§3.2「停机宿主既不读也不写，纳入只会稀释分母」）；
     * 每个读者宿主的每个读通道，对每个写该通道的宿主（含它自己——自环）生成一条带标签的边；
     * 边按 (通道, 写者, 读者) 全序排列。写集为空的通道不产生边（悬空读者只有节点没有边）。</p>
     * <p>Rules: a host with an empty census never enters the node set (§3.2: "a stopped host
     * neither reads nor writes — including it only dilutes the denominator"); for every read
     * channel of every reader host, one labelled edge is produced per host writing that
     * channel (itself included — the self-loop); edges are totally ordered by (channel,
     * writer, reader). A channel with no writer produces no edge (a dangling read stays a
     * node without edges).</p>
     */
    public static HostGraph buildHostGraph(Map<HostId, ChannelCensus> censusByHost) {
        var hosts = new TreeMap<HostId, ChannelCensus>();
        for (var e : censusByHost.entrySet())
            if (e.getValue() != null && !e.getValue().isEmpty()) hosts.put(e.getKey(), e.getValue());
        // 通道 → 写者（同通道多写者并存——占用语义冲突时读者结构上仍面对多个潜在来源，
        // DAG 如实保留两条边，观测侧由 epoch 记录区分实际来源）。
        // channel → writers (multiple writers per channel coexist — under the occupancy
        // semantics a conflict leaves the reader structurally facing several potential
        // sources; the DAG keeps both edges honestly, step 4's epoch records tell them apart).
        Map<String, List<HostId>> writersByChannel = new HashMap<>();
        for (var e : hosts.entrySet())
            for (String ch : e.getValue().writes())
                writersByChannel.computeIfAbsent(ch, k -> new ArrayList<>()).add(e.getKey());
        var edges = new ArrayList<ChannelEdge>();
        var downstream = new HashMap<HostId, Set<HostId>>();
        for (var e : hosts.entrySet()) {
            HostId reader = e.getKey();
            for (String ch : e.getValue().reads()) {
                var writers = writersByChannel.get(ch);
                if (writers == null) continue;
                for (HostId writer : writers) {
                    edges.add(new ChannelEdge(ch, writer, reader));
                    downstream.computeIfAbsent(writer, k -> new HashSet<>()).add(reader);
                }
            }
        }
        Collections.sort(edges);
        return new HostGraph(hosts, edges, downstream);
    }

    /**
     * 从全局注册表现算普查并构图（生产入口）：先快照注册表（生命周期变更不撕裂单次构建），
     * 再逐宿主调供给器普查（活图枚举，停机宿主自报空普查 → 自动按 §3.2 口径出图）。
     * <p>Build from the global registry, censusing live (the production entry): snapshot the
     * registry first (lifecycle changes never tear a single build), then census each host
     * through its provider (a live-graph enumeration; stopped hosts self-report an empty
     * census and drop out per the §3.2 criterion automatically).</p>
     */
    public static HostGraph buildHostGraph() {
        var snapshot = hostsSnapshot();
        var census = new TreeMap<HostId, ChannelCensus>();
        for (var e : snapshot.entrySet()) census.put(e.getKey(), e.getValue().census());
        return buildHostGraph(census);
    }

    // ── 3. SCC（Tarjan） / SCC (Tarjan) ──────────────────────────────

    /**
     * Tarjan 强连通分量（全图分区：孤立宿主各自成大小 1 的平凡分量）。
     * <p>Tarjan strongly connected components (the full partition: an isolated host forms its
     * own trivial size-1 component).</p>
     * <p>确定性：根按 HostId 序遍历、邻接按 HostId 序展开、分量按最小成员排序——同一图集
     * 任意次重跑输出全同（诊断 {@code ring}/{@code topo} 与 §5.3 稳定切边的前置）。
     * 递归深度 = DFS 路径长度 ≤ 宿主数；宿主规模是方块数（现实上限千级），默认栈安全。
     * 大小 1 且无自环的分量不是环——环判定走 {@link #cyclicComponents}。</p>
     * <p>Determinism: roots are visited in HostId order, neighbours expanded in HostId order,
     * components ordered by their smallest member — the same graph set replays identically
     * (the prerequisite for the {@code ring}/{@code topo} diagnostics and §5.3's stable edge
     * cutting). Recursion depth = the DFS path length ≤ host count; hosts are block entities
     * (a realistic ceiling of thousands), safe on the default stack. A size-1 component
     * without a self-loop is NOT a cycle — cycle judgement goes through
     * {@link #cyclicComponents}.</p>
     */
    public static List<SortedSet<HostId>> stronglyConnectedComponents(HostGraph g) {
        var index = new HashMap<HostId, Integer>();
        var low = new HashMap<HostId, Integer>();
        var onStack = new HashSet<HostId>();
        var stack = new ArrayDeque<HostId>();
        var out = new ArrayList<SortedSet<HostId>>();
        int[] counter = {0};
        for (HostId root : g.hosts())
            if (!index.containsKey(root))
                strongconnect(root, g, index, low, onStack, stack, out, counter);
        out.sort((a, b) -> a.first().compareTo(b.first()));
        return out;
    }

    /** Tarjan 单节点递归体（经典形态，顺序面见 {@link #stronglyConnectedComponents}）。
     *  The classic Tarjan per-node recursion (ordering face documented on
     *  {@link #stronglyConnectedComponents}). */
    private static void strongconnect(HostId v, HostGraph g,
                                      Map<HostId, Integer> index, Map<HostId, Integer> low,
                                      Set<HostId> onStack, ArrayDeque<HostId> stack,
                                      List<SortedSet<HostId>> out, int[] counter) {
        index.put(v, counter[0]);
        low.put(v, counter[0]);
        counter[0]++;
        stack.push(v);
        onStack.add(v);
        var neighbours = new ArrayList<>(g.downstreamOf(v));
        Collections.sort(neighbours);
        for (HostId w : neighbours) {
            if (!index.containsKey(w)) {
                strongconnect(w, g, index, low, onStack, stack, out, counter);
                low.put(v, Math.min(low.get(v), low.get(w)));
            } else if (onStack.contains(w)) {
                low.put(v, Math.min(low.get(v), index.get(w)));
            }
        }
        if (low.get(v).equals(index.get(v))) {
            var scc = new TreeSet<HostId>();
            HostId w;
            do {
                w = stack.pop();
                onStack.remove(w);
                scc.add(w);
            } while (!w.equals(v));
            out.add(scc);
        }
    }

    /**
     * 真环分量：大小 &gt; 1 的 SCC，或大小 1 但带自环（读自己写的通道——§3.2 顺带推论
     * 「主图与 ENCAP 子图可能读写同一通道」的 host 自环）。§5.3 切边只针对这里的输出。
     * <p>Genuine cycle components: an SCC of size &gt; 1, or size 1 carrying a self-loop
     * (reading a channel the host itself writes — the §3.2 corollary "a host's main graph and
     * its ENCAP sub-graph may read/write the same channel"). §5.3's edge cutting targets
     * exactly this output.</p>
     */
    public static List<SortedSet<HostId>> cyclicComponents(HostGraph g) {
        var out = new ArrayList<SortedSet<HostId>>();
        for (var scc : stronglyConnectedComponents(g))
            if (scc.size() > 1 || g.hasSelfLoop(scc.first())) out.add(scc);
        return out;
    }

    /** 全图是否存在真环（{@link #cyclicComponents} 非空）。
     *  Whether the graph carries any genuine cycle ({@link #cyclicComponents} non-empty). */
    public static boolean hasCycle(HostGraph g) { return !cyclicComponents(g).isEmpty(); }
}
