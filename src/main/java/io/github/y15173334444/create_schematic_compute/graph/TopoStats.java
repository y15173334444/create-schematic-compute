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
import java.util.concurrent.atomic.AtomicLong;

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
 * <p><b>步骤 4 落地面（issue #26）</b>：口径 A 采集已在本类第 4 节 —— 门控（
 * {@code csc.topoCollect}，{@code BusTrace} 同款：默认关、关闭路径近零开销）、静态
 * {@code currentHost} 求值宿主上下文（{@code GraphHost.tickBody} ⑨ 单点设/清，子 evaluator
 * 经同一静态天然延续）、通道 epoch 表 + 待判定记录表、§3.4 Post 汇总（①/② 判定 + 陈旧跨度
 * + 受影响闭包）与只读快照消费面。这些 per-tick 收集表是步骤 4 新增的 <b>tick 态</b>，与
 * 第 1 节的生命周期注册表（生命周期态）分面管理、分面清空。</p>
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
 * <p><b>Step-4 landing face (issue #26)</b>: the criterion-A collection lives in section 4 of
 * this class — the gate ({@code csc.topoCollect}, the {@code BusTrace} style: default off,
 * near-zero cost on the off path), the static {@code currentHost} evaluation-host context
 * (set/cleared at the single {@code GraphHost.tickBody} ⑨ point; ENCAP sub-evaluators inherit
 * through the same static), the channel epoch table + pending-record table, the §3.4 Post
 * summary (①/② judgement + stale span + affected closure) and the read-only snapshot
 * consumption face. Those per-tick collector tables are step 4's new <b>tick state</b>, managed
 * and cleared as a face separate from section 1's lifecycle registry.</p>
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
     *  {@code SignalBus.key()}-prefixed channel keys. Immutable and ordered
     *  (TreeSet-normalized). */
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
     *  摘除从未登记的键都安全（返回 false）。采集开启时顺带摘除离场宿主的待判定记录
     *  （第 4 节卫生面）：离场者不会再产生新读取，滞留记录永无替换者，通道随后的写入只会
     *  对已不存在的宿主累积跨 tick 陈旧噪声。
     *  Host gone: drop the entry. Idempotent — a repeated removal (both the chunk-unload and
     *  the remove paths may fire) or a removal of a never-registered key is safe (returns
     *  false). While collecting, the departing host's pending records go with it (the section-4
     *  hygiene face): a gone host never reads again so its pending record has no replacer, and
     *  later channel writes would only accumulate cross-tick stale noise against a host that
     *  no longer exists. */
    public static boolean hostGone(HostId id) {
        boolean removed = id != null && HOSTS.remove(id) != null;
        if (collect && id != null) PENDING.remove(id);
        return removed;
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

    // ── 4. 口径 A 采集（§3.1 判定规则 + §3.3 采集点 + §3.4 Post 汇总；issue #26） ──────────────────────────────
    //
    // 口径 A（§3.1 原文）：写入侧 PRIVATE_OUT / BUS_OUT 分支 → channelEpoch.bump(key)；
    // 读取侧 PRIVATE_IN / BUS_IN 分支 → 记录 (key, epoch, value)；Post：epoch > 记录时 epoch
    // → ① 陈旧读，当前值 ≠ 记录值 → ② 陈旧且值不同；附带陈旧跨度（同 tick 内被超越 / 跨 tick）
    // 与受影响闭包（沿 DAG 下游传播，O(边数) 不重算）。
    //
    // 钩子挂点（§3.3 关键实现选择）：求值器的 BUS_IN / PRIVATE_IN / BUS_OUT / PRIVATE_OUT
    // 分支（BusEval 的浮点载体路径），不是 12 个 BE 的 tick 体；宿主归属走本节静态
    // currentHost 上下文（GraphHost.tickBody ⑨ 单点设/清，ENCAP 子 evaluator 经同一静态
    // 天然延续，无需改求值重载）。
    //
    // ⚠ 采样语义警示（§3.3，2026-11-14 实测）：采集只走服务端求值路径 —— 读取钩子要求
    // currentHost 在场，而客户端线程永不设置它（探针「当前值」读数是客户端本地求值、读者侧
    // 无写者恒 0 冻结，与服务端真值脱节），显示通道与本采集彻底无关。
    //
    // Criterion-A collection (§3.1 rules + §3.3 record points + §3.4 Post summary; issue #26).
    // §3.1 verbatim: write side PRIVATE_OUT / BUS_OUT branches → channelEpoch.bump(key); read
    // side PRIVATE_IN / BUS_IN branches → record (key, epoch, value); Post: epoch past the
    // record's epoch → ① stale read, current value ≠ recorded value → ② stale and value
    // changed; with the stale span (superseded within the same tick / across ticks) and the
    // affected closure (propagated down the DAG, O(edge count), no re-evaluation) as by-products.
    // Hook sites (§3.3, the key implementation choice): the evaluator's BUS_IN / PRIVATE_IN /
    // BUS_OUT / PRIVATE_OUT branches (BusEval's float-carrier paths), never the twelve BE tick
    // bodies; host attribution rides this section's static currentHost context (set/cleared at
    // the single GraphHost.tickBody ⑨ point; ENCAP sub-evaluators continue through the same
    // static, no evaluation overload changes).
    // Sampling-semantics warning (§3.3, live-measured 2026-11-14): collection rides the server
    // evaluation path only — the read hook requires currentHost, which the client thread never
    // sets (the probe's "current value" reading is client-local evaluation, frozen at 0 when
    // the reader's side has no writer, disconnected from the server truth); the display channel
    // has nothing to do with this collection.

    /** 采集门控属性（{@code BusTrace} 同款风格：JVM 属性、仅类加载时读取、运行期不可改）。
     *  判定 = 属性在场且值不是 {@code false} / {@code 0}（忽略大小写、容忍首尾空白）——裸
     *  {@code -Dcsc.topoCollect}（空值）即开启。关闭时零行为：每个钩子只多一次布尔读
     *  （近零开销），epoch 表 / 记录表 / 计数表不分配、tick 戳不推进、currentHost 不设；
     *  开启后每次读写 O(1) 记账（常量次表查找）。观测数据（①/② 计数、陈旧跨度、受影响
     *  闭包）经 {@link #collectionSnapshot()} 在每个 Post 汇总后整体发布 —— §四 控制台
     *  {@code stale} / {@code hosts} 命令的接线批（步骤 6 实机观测）的读面。
     *  <p>The collection gate property (the {@code BusTrace} style: a JVM property, read once
     *  at class load, immutable at runtime). Enabled iff the property is present and its value
     *  is not {@code false} / {@code 0} (case-insensitive, surrounding whitespace tolerated) —
     *  a bare {@code -Dcsc.topoCollect} (empty value) enables. Disabled, it costs nothing:
     *  every hook adds one boolean read (near-zero overhead) and the epoch / record / tally
     *  tables are never allocated, the tick stamp never advances, currentHost is never set.
     *  Enabled, each read/write is O(1) accounting (a constant number of map lookups). The
     *  observation data (①/② counts, stale spans, the affected closure) is published wholesale
     *  through {@link #collectionSnapshot()} after every Post summary — the read face for the
     *  §四 console {@code stale} / {@code hosts} wiring batch (step-6 live observation).</p> */
    public static final String COLLECT_PROPERTY = "csc.topoCollect";

    /** 采集门（仅类加载时从 {@link #COLLECT_PROPERTY} 解析一次；headless 测试经包内
     *  {@link #configureCollection} 重设，生产不可运行期更改）。volatile：生产只读本无竞争，
     *  volatile 只为测试改门后的可见性自洽。
     *  The collection gate (parsed once from {@link #COLLECT_PROPERTY} at class load; headless
     *  tests reset it through the package-private {@link #configureCollection}, production can
     *  never change it at runtime). volatile: production only reads it (no race), volatile
     *  merely keeps post-test-flip visibility honest. */
    private static volatile boolean collect = parseCollectFlag(System.getProperty(COLLECT_PROPERTY));

    /** 门判定：属性缺席 = 关；在场时值为 {@code false} / {@code 0}（忽略大小写、trim）= 关，
     *  其余（含空串，即裸 {@code -Dcsc.topoCollect}）= 开。
     *  Gate parse: absent = off; present with {@code false} / {@code 0} (case-insensitive,
     *  trimmed) = off; anything else (the empty string included — a bare
     *  {@code -Dcsc.topoCollect}) = on. */
    static boolean parseCollectFlag(String value) {
        if (value == null) return false;
        String v = value.trim();
        return !(v.equalsIgnoreCase("false") || v.equals("0"));
    }

    /** 仅测试用：重设采集门（与 {@code BusTrace.configure} 同口径的包内测试面；生产面由
     *  {@link #COLLECT_PROPERTY} 类加载时定死）。
     *  Test-only: reset the gate (the package-private test face, same shape as
     *  {@code BusTrace.configure}; the production face is fixed at class load by
     *  {@link #COLLECT_PROPERTY}). */
    static void configureCollection(boolean on) { collect = on; }

    /** 采集门开吗 —— 一切钩子的唯一守卫：关闭路径只有这一次布尔读。
     *  Whether collection is enabled — the single guard of every hook: the disabled path is
     *  this one boolean read. */
    public static boolean collecting() { return collect; }

    /** 服务端 tick 戳（口径 A 的时间轴）：模组入口既有 {@code ServerTickEvent.Pre} 钩子经
     *  {@link #beginCollectTick} 推进、Post 汇总读取。记录戳 = 当前戳 → 同 tick 内被超越；
     *  记录戳 &lt; 当前戳 → 跨 tick 被超越（§3.1 陈旧跨度）。关闭时不推进（零行为）。
     *  The server tick stamp (criterion A's time axis): advanced by the mod entry's existing
     *  {@code ServerTickEvent.Pre} hook through {@link #beginCollectTick}, read by the Post
     *  summary. A record stamped equal to the current tick was superseded same-tick; stamped
     *  earlier — cross-tick (§3.1's stale span). Never advances while collection is off
     *  (zero behaviour). */
    private static final AtomicLong COLLECT_TICK = new AtomicLong();

    /** tick 计数入口（{@code ServerTickEvent.Pre} 里调用；关闭时一次布尔读、不推进）。
     *  The tick-counter entry (call from ServerTickEvent.Pre; one boolean read and no advance
     *  when disabled). */
    public static void beginCollectTick() { if (collect) COLLECT_TICK.incrementAndGet(); }

    /** 当前求值宿主上下文（§3.3 静态 per-tick 收集器形态的「当前 host」半边）。由
     *  {@code GraphHost.tickBody} ⑨（服务端守卫内、求值窗口两端）成对设/清 —— 采集关闭时
     *  根本不设。子 evaluator（ENCAP 封装求值走同一 eval switch）经同一静态天然延续，无需
     *  改求值重载。读取钩子要求它在场：无上下文不落记录 —— 客户端线程永不设置它，§3.3 的
     *  采样语义闸门就在这一判。
     *  The current evaluation-host context (the "current host" half of §3.3's static per-tick
     *  collector shape). Set/cleared in pairs by {@code GraphHost.tickBody} ⑨ (inside the
     *  server guard, around the evaluation window) — never even set while collection is off.
     *  Sub-evaluators (ENCAP encapsulated evaluation rides the same eval switch) continue
     *  through the same static, no evaluation overloads changed. The read hook requires it:
     *  no record without a context — the client thread never sets one, which is exactly where
     *  §3.3's sampling-semantics gate lands. */
    private static volatile HostId currentHost;

    /** 进入求值宿主上下文，返回先前的宿主（配对 {@link #endEvalContext} 恢复，嵌套安全）。
     *  Enter the evaluation-host context and return the previous host (for the paired
     *  {@link #endEvalContext} to restore — nesting-safe). */
    public static HostId beginEvalContext(HostId host) {
        HostId prev = currentHost;
        currentHost = host;
        return prev;
    }

    /** 恢复 {@link #beginEvalContext} 返回的先前宿主；null = 清除上下文。
     *  Restore the previous host returned by {@link #beginEvalContext}; null clears the
     *  context. */
    public static void endEvalContext(HostId prev) { currentHost = prev; }

    /** 当前求值宿主（无上下文 = null；包内测试与断言用）。
     *  The current evaluation host (null when no context; for in-package tests and asserts). */
    static HostId currentEvalHost() { return currentHost; }

    /** 通道 epoch 表（前缀键 → 版本计数 + 各浮点槽位最近写入值）。键 = {@code SignalBus.key()}
     *  前缀形态的通道身份（与 DAG 边标签同一单一源）；槽位 = {@code ""}（PRIVATE 通道 /
     *  无频段 BUS 读）或频段名（BUS 频段）—— §3.1 的「通道的当前值」在多频段 BUS 通道上按
     *  记录的槽位取值，② 的值比较落在读者实际读的那一槽。普通 HashMap：服务端单线程（求值
     *  与 Post 同线程），采集又是临时诊断态。
     *  The channel epoch table (prefixed key → version counter + each float slot's last-written
     *  value). Keys = the {@code SignalBus.key()} prefixed channel identity (one single source
     *  with the DAG's edge labels); slots = {@code ""} (PRIVATE channels / band-less BUS reads)
     *  or the band name (BUS bands) — §3.1's "the channel's current value" resolves on a
     *  multi-band BUS channel through the record's slot, so ②'s value compare lands on the very
     *  slot the reader read. Plain HashMap: the server is single-threaded (evaluation and the
     *  Post pass share one thread) and collection is a temporary diagnostic state. */
    private static final HashMap<String, ChannelEpochState> EPOCHS = new HashMap<>();

    /** epoch 表条目：版本计数 + 槽位 → 最近写入值。 / An epoch-table entry: the version
     *  counter + slot → last-written value. */
    private static final class ChannelEpochState {
        long epoch;
        final HashMap<String, Float> slotValues = new HashMap<>();
    }

    /** 写入侧钩子（§3.1）：PRIVATE_OUT 浮点 put / BUS_OUT 频段浮点写入的分支尾挂点 ——
     *  epoch +1 并把该槽位的最近写入值记为「当前值」。冲突节点不写存储，自然不 bump（记账
     *  不查瞬时冲突旗标，与普查的结构性口径一致：epoch 观测的是<b>实际写</b>，DAG 保留的是
     *  <b>可能依赖</b>）。O(1)：一次表查找 + 一次自增 + 一次槽位写。音频平面不经此路
     *  （AudioBands 的 exactly-once 游标门控，§3.2 范围注），向量平面亦然（last-write-wins
     *  状态语义，无浮点可比较）——§3.1 的 epoch 模型按浮点载体记账。
     *  <p>The write-side hook (§3.1): rides the tails of the PRIVATE_OUT float put / BUS_OUT
     *  band float writes — epoch +1, and the slot's last-written value becomes the "current
     *  value". A conflicted node writes no storage and therefore bumps nothing (the accounting
     *  never checks transient conflict flags, matching the census's structural criterion: the
     *  epoch observes <b>actual writes</b>, the DAG keeps <b>possible dependencies</b>). O(1):
     *  one table lookup, one increment, one slot write. The audio plane never rides this path
     *  (AudioBands' exactly-once cursor gates it, the §3.2 scope note), nor does the vector
     *  plane (last-write-wins state semantics, no float to compare) — the §3.1 epoch model
     *  accounts on the float carrier.</p> */
    public static void noteChannelWrite(String channelKey, String slot, float value) {
        if (!collect) return;
        ChannelEpochState st = EPOCHS.computeIfAbsent(channelKey, k -> new ChannelEpochState());
        st.epoch++;
        st.slotValues.put(slot, value);
    }

    /** 通道当前 epoch（无记录 = 0）。
     *  The channel's current epoch (0 when unrecorded). */
    static long epochOf(String channelKey) {
        ChannelEpochState st = EPOCHS.get(channelKey);
        return st != null ? st.epoch : 0L;
    }

    /** 待判定记录（§3.1 读取侧的 {@code (key, epoch, value)} + 记录 tick 戳）。tick 戳支撑
     *  §3.1 的陈旧跨度（同 tick 内被超越 / 跨 tick）。
     *  A pending record (§3.1's read-side {@code (key, epoch, value)} plus the record's tick
     *  stamp). The stamp backs §3.1's stale span (superseded within the same tick / across
     *  ticks). */
    private static final class PendingRead {
        final String channelKey;
        final String slot;
        final long epoch;
        final float value;
        final long tick;
        PendingRead(String channelKey, String slot, long epoch, float value, long tick) {
            this.channelKey = channelKey;
            this.slot = slot;
            this.epoch = epoch;
            this.value = value;
            this.tick = tick;
        }
    }

    /** 待判定记录表：宿主 → 通道键 → 槽位 → 记录。同一宿主对同一槽位的重复读取只保留最新
     *  一条（读者已消费更新版本，旧记录的陈旧性失效、直接退役不计）—— 表规模上限 = 活跃
     *  读者 × 通道 × 槽位，无跨 tick 无界增长；{@link #hostGone} 摘离场宿主的整棵子树。
     *  The pending-record table: host → channel key → slot → record. A repeated read of the
     *  same slot by the same host keeps only the newest record (the reader has consumed a newer
     *  version; the old record's staleness is moot and retires untallied) — the table is bounded
     *  by active readers × channels × slots with no unbounded cross-tick growth; {@link #hostGone}
     *  drops a departing host's whole subtree. */
    private static final HashMap<HostId, HashMap<String, HashMap<String, PendingRead>>> PENDING = new HashMap<>();

    /** 读取侧钩子（§3.1）：PRIVATE_IN 浮点 get / BUS_IN 频段浮点读取的分支尾挂点 —— 落一条
     *  {@code (key, epoch, value)} 记录（epoch = 记录时刻的通道版本）。O(1)（三次表查找）。
     *  <b>无 currentHost 上下文不落记录</b>（§3.3：采集只走服务端求值路径；客户端本地求值
     *  永不设上下文）。
     *  <p><b>§5.3 切边豁免</b>：被切读者的读由上一 tick 末快照接手——设计内跨 tick，不是
     *  陈旧读；该读不落记录（{@link ChannelCuts#isSnapshotServed} 判定），①/② 口径只量
     *  「计划外陈旧」，否则被切通道的计数会按设计永久增长、判读表失真。</p>
     *  <p>The read-side hook (§3.1): rides the tails of the PRIVATE_IN float get / BUS_IN band
     *  float reads — one {@code (key, epoch, value)} record (epoch = the channel version at
     *  read time). O(1) (three map lookups). <b>No record without a currentHost context</b>
     *  (§3.3: collection rides the server evaluation path only; client-local evaluation never
     *  sets the context).</p>
     *  <p><b>The §5.3 cut exemption</b>: a cut reader's read is served by last tick's
     *  snapshot — cross-tick <b>by design</b>, not a stale read; it records nothing
     *  (judged by {@link ChannelCuts#isSnapshotServed}), so the ①/② metric only measures
     *  "unplanned staleness" — otherwise a cut channel's counts would grow by design forever
     *  and distort the verdict table.</p> */
    public static void noteChannelRead(String channelKey, String slot, float value) {
        if (!collect) return;
        HostId host = currentHost;
        if (host == null) return;
        if (ChannelCuts.isSnapshotServed(channelKey)) return;
        PENDING.computeIfAbsent(host, h -> new HashMap<>())
            .computeIfAbsent(channelKey, k -> new HashMap<>())
            .put(slot, new PendingRead(channelKey, slot, epochOf(channelKey), value, COLLECT_TICK.get()));
    }

    /** 计数表槽位：[0]=① 陈旧读、[1]=② 陈旧且值不同、[2]=同 tick 被超越、[3]=跨 tick 被超越。
     *  Tally slots: [0]=① stale reads, [1]=② stale and value changed, [2]=superseded same-tick,
     *  [3]=superseded cross-tick. */
    private static final int TALLY_STALE = 0, TALLY_CHANGED = 1, TALLY_SAME_TICK = 2, TALLY_CROSS_TICK = 3;
    private static final int TALLY_WIDTH = 4;

    /** 按宿主的累计计数。 / Cumulative counts by host. */
    private static final HashMap<HostId, long[]> TALLY_BY_HOST = new HashMap<>();

    /** 按宿主 × 通道键的累计计数（{@code stale} 按通道分组的读面）。
     *  Cumulative counts by host × channel key (the {@code stale} per-channel-grouped face). */
    private static final HashMap<HostId, HashMap<String, long[]>> TALLY_BY_CHANNEL = new HashMap<>();

    /** 最新采集快照（Post 汇总末尾引用整体换；关闭 / 复位 = {@link CollectSnapshot#EMPTY}）。
     *  The latest collection snapshot (reference-swapped at the end of the Post summary;
     *  {@link CollectSnapshot#EMPTY} when disabled / reset). */
    private static volatile CollectSnapshot snapshot = CollectSnapshot.EMPTY;

    /** 最新 Post 汇总快照（不可变、任意线程可读；关闭时恒为 {@link CollectSnapshot#EMPTY}）。
     *  消费面：§四 控制台 {@code stale} / {@code hosts} 命令的接线批（步骤 6 实机观测据此
     *  读数）。
     *  The latest Post-summary snapshot (immutable, readable from any thread; always
     *  {@link CollectSnapshot#EMPTY} while disabled). Consumption face: the §四 console
     *  {@code stale} / {@code hosts} wiring batch (step-6 live observation reads this). */
    public static CollectSnapshot collectionSnapshot() { return snapshot; }

    /** §3.1 单项累计口径（不可变值）：① 陈旧读次数、② 陈旧且值不同次数、同 tick / 跨 tick
     *  被超越跨度。 / One §3.1 cumulative count (an immutable value): ① stale reads,
     *  ② stale-and-value-changed, superseded same-tick / cross-tick spans. */
    public record StaleCounts(long staleReads, long staleValueChanged,
                              long supersededSameTick, long supersededCrossTick) {}

    /** Post 汇总快照（不可变整体发布）：按宿主与宿主 × 通道的累计计数 + 最近一次有种子趟次
     *  的受影响闭包（{@code affectedClosure}；{@code closureTick} 为其计算趟次，无种子的趟次
     *  保留上一个闭包 —— 它仍是最近一次观测到的受影响范围）。{@code seedsThisPass} = 本趟
     *  判出的 ① 记录数。全部键有序（HostId / 通道键自然序）—— 诊断与判读可复现。
     *  <p>The Post-summary snapshot (published immutably, wholesale): cumulative counts by host
     *  and by host × channel plus the most recent seeded pass's affected closure
     *  ({@code affectedClosure}; {@code closureTick} stamps the pass that computed it, and a
     *  seedless pass keeps the previous closure — it is still the most recently observed
     *  affected range). {@code seedsThisPass} = the number of ① records judged in this pass.
     *  All keys ordered (HostId / channel-key natural order) — reproducible diagnostics and
     *  verdicts.</p> */
    public record CollectSnapshot(long tick,
                                  Map<HostId, StaleCounts> byHost,
                                  Map<HostId, Map<String, StaleCounts>> byHostChannel,
                                  SortedSet<HostId> affectedClosure,
                                  long closureTick,
                                  long seedsThisPass) {
        /** 空快照常量：关闭 / 复位 / 从未汇总。 / The empty snapshot: disabled / reset / never
         *  summarized. */
        public static final CollectSnapshot EMPTY = new CollectSnapshot(0L, Map.of(), Map.of(),
            Collections.unmodifiableSortedSet(new TreeSet<>()), 0L, 0L);
    }

    /** Post 汇总（§3.4：模组入口既有 {@code ServerTickEvent.Post} 钩子里调用；只读采集态 +
     *  引用换发，<b>不改变求值时序</b>；关闭时一次布尔读返回）。对每条待判定记录按 §3.1
     *  判定：通道 epoch 已超越记录时 epoch → <b>① 陈旧读</b>（记录 tick = 当前 tick → 同
     *  tick 内被超越，否则跨 tick）；记录槽位的最近写入值存在且 ≠ 记录值 → <b>② 陈旧且值
     *  不同</b>（槽位从未被写 = 值比较不可判，② 诚实不记）。判过的记录移除（判定只计一次），
     *  未被超越的滞留到后续 Post（跨 tick 被超越在那里的发现面）。本趟判出 ① 的宿主作为
     *  种子沿 BE 级依赖图向下游传播受影响标记（{@link #affectedClosure}，O(边数) 不重算；
     *  有种子才现算图 —— 平静 tick 零普查成本）。
     *  <p>The Post summary (§3.4: called from the mod entry's existing ServerTickEvent.Post
     *  hook; reads the collection state and swaps the published reference, <b>never touching
     *  the evaluation timeline</b>; one boolean read when disabled). Each pending record is
     *  judged by §3.1: a channel epoch past the record's epoch → <b>① stale read</b> (record
     *  tick equal to the current tick = superseded same-tick, otherwise cross-tick); a
     *  last-written value present for the record's slot and different from the recorded value →
     *  <b>② stale and value changed</b> (a never-written slot leaves the compare undecidable —
     *  ② honestly not counted). Judged records are removed (a record counts exactly once);
     *  un-superseded ones stay pending for later Posts (where cross-tick supersession is
     *  discovered). Hosts with a fresh ① seed the affected-mark propagation down the BE-level
     *  dependency graph ({@link #affectedClosure}, O(edge count), no re-evaluation; the graph
     *  is built only on seeded passes — a quiet tick costs zero census work).</p> */
    public static void postCollectSummary() {
        if (!collect) return;
        long nowTick = COLLECT_TICK.get();
        Set<HostId> seeds = new TreeSet<>();
        for (var hostEntry : PENDING.entrySet()) {
            for (var chEntry : hostEntry.getValue().entrySet()) {
                var bySlot = chEntry.getValue();
                for (var slotIt = bySlot.entrySet().iterator(); slotIt.hasNext(); ) {
                    PendingRead rec = slotIt.next().getValue();
                    ChannelEpochState st = EPOCHS.get(rec.channelKey);
                    long cur = st != null ? st.epoch : 0L;
                    if (cur <= rec.epoch) continue;            // 未被超越：滞留 / not superseded: stays pending
                    slotIt.remove();
                    boolean sameTick = rec.tick == nowTick;
                    HostId host = hostEntry.getKey();
                    tally(host, rec.channelKey, TALLY_STALE);
                    if (st != null) {
                        Float curVal = st.slotValues.get(rec.slot);
                        if (curVal != null && curVal.floatValue() != rec.value)
                            tally(host, rec.channelKey, TALLY_CHANGED);
                    }
                    tally(host, rec.channelKey, sameTick ? TALLY_SAME_TICK : TALLY_CROSS_TICK);
                    seeds.add(host);
                }
            }
        }
        // 剪掉被清空的嵌套枝，表不残留宿主/通道骨架。 / Prune emptied nested branches so the
        // table keeps no host/channel skeletons.
        PENDING.entrySet().removeIf(e -> e.getValue().isEmpty());
        for (var e : PENDING.values()) e.entrySet().removeIf(x -> x.getValue().isEmpty());
        // 受影响闭包：有种子才现算（图按注册表现普查构建 —— 活枚举零缓存失效面）；无种子的
        // 趟次保留上一个闭包。 / The affected closure: computed only on seeded passes (the graph
        // is built from a live registry census — live enumeration, zero invalidation surface);
        // a seedless pass keeps the previous closure.
        SortedSet<HostId> closure;
        long closureTick;
        if (seeds.isEmpty()) {
            closure = snapshot.affectedClosure();
            closureTick = snapshot.closureTick();
        } else {
            closure = affectedClosure(seeds, buildHostGraph());
            closureTick = nowTick;
        }
        var byHostOut = new TreeMap<HostId, StaleCounts>();
        for (var e : TALLY_BY_HOST.entrySet()) byHostOut.put(e.getKey(), toCounts(e.getValue()));
        var byChannelOut = new TreeMap<HostId, Map<String, StaleCounts>>();
        for (var e : TALLY_BY_CHANNEL.entrySet()) {
            var m = new TreeMap<String, StaleCounts>();
            for (var e2 : e.getValue().entrySet()) m.put(e2.getKey(), toCounts(e2.getValue()));
            byChannelOut.put(e.getKey(), Collections.unmodifiableMap(m));
        }
        snapshot = new CollectSnapshot(nowTick, Collections.unmodifiableMap(byHostOut),
            Collections.unmodifiableMap(byChannelOut), Collections.unmodifiableSortedSet(closure),
            closureTick, seeds.size());
    }

    /** long[] 计数 → 不可变口径值。 / A long[] tally → the immutable count value. */
    private static StaleCounts toCounts(long[] t) {
        return new StaleCounts(t[TALLY_STALE], t[TALLY_CHANGED], t[TALLY_SAME_TICK], t[TALLY_CROSS_TICK]);
    }

    /** 累加一档判定到两级计数表。 / Accumulate one verdict tier into the two tally tables. */
    private static void tally(HostId host, String channelKey, int idx) {
        TALLY_BY_HOST.computeIfAbsent(host, k -> new long[TALLY_WIDTH])[idx]++;
        TALLY_BY_CHANNEL.computeIfAbsent(host, k -> new HashMap<>())
            .computeIfAbsent(channelKey, k -> new long[TALLY_WIDTH])[idx]++;
    }

    /** 受影响标记的传递闭包（§3.1「沿 BE 级依赖图向下游传播受影响标记」）：种子（判出 ① 的
     *  读者宿主）沿 {@link HostGraph#downstreamOf} 邻接向下游 BFS —— 读者的输入旧 → 其输出
     *  可疑 → 读它通道的宿主同批受影响。访问集去重，总代价 O(宿主 + 边数)，<b>不重算</b>
     *  任何求值（影子重算的一阶近似；§3.5 拍板：补跑 / 口径 B 不做主线出口）。输出全序
     *  （TreeSet）—— {@code stale}/{@code topo} 的展示与判读可复现。种子不在图中（停机后
     *  普查为空）也计入受影响集 —— 它们是真实的 ① 来源。
     *  <p>The transitive closure of affected marks (§3.1: "propagate affected marks downstream
     *  along the BE-level dependency graph"): seeds (reader hosts with a fresh ①) BFS downstream
     *  over the {@link HostGraph#downstreamOf} adjacency — a reader's input is old → its output
     *  is suspect → whoever reads its channels is affected in the same batch. A visited set
     *  dedups; the total cost is O(hosts + edges) with <b>no re-evaluation</b> (the first-order
     *  approximation of a shadow re-run; the §3.5 ruling keeps re-runs / criterion B off the
     *  mainline exit). The output is totally ordered (a TreeSet) — reproducible for
     *  {@code stale}/{@code topo} display and verdicts. Seeds outside the graph (stopped since,
     *  empty census) still enter the affected set — they are genuine ① sources.</p> */
    public static SortedSet<HostId> affectedClosure(Set<HostId> seeds, HostGraph g) {
        var affected = new TreeSet<HostId>();
        var queue = new ArrayDeque<HostId>();
        if (seeds != null) for (HostId s : seeds) if (s != null && affected.add(s)) queue.add(s);
        if (g != null) {
            while (!queue.isEmpty()) {
                HostId cur = queue.poll();
                for (HostId next : g.downstreamOf(cur)) if (affected.add(next)) queue.add(next);
            }
        }
        return affected;
    }

    /** 清空采集态：epoch 表 / 待判定记录 / 累计计数 / 快照 / tick 戳全部归零（currentHost
     *  是求值窗口两端成对设清的瞬态，不在清空面）。服务端停止面（模组入口，跨存档防污染）
     *  与测试复位用 —— 与 {@link #clearRegistry} 各清各的面。
     *  Clear the collection state: the epoch table, pending records, cumulative tallies,
     *  snapshot and tick stamp all reset (currentHost is transient, set/cleared in pairs around
     *  the evaluation window — not part of this face). For the server-stop face (the mod entry,
     *  the cross-world pollution guard) and test resets — orthogonal to
     *  {@link #clearRegistry}. */
    public static void clearCollection() {
        EPOCHS.clear();
        PENDING.clear();
        TALLY_BY_HOST.clear();
        TALLY_BY_CHANNEL.clear();
        COLLECT_TICK.set(0L);
        snapshot = CollectSnapshot.EMPTY;
    }
}
