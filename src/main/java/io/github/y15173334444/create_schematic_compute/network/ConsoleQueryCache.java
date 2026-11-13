package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.graph.TopoStats;

/**
 * <h2>控制台全局查询的按 tick 脏缓存（§4.4 分层缓解第 2 层 / issue #29）</h2>
 *
 * <p>{@code topo} / {@code ring} / {@code hosts}（以及 {@code bus} 的通道普查面）都要现算
 * BE 级依赖图——构图 = 注册表快照 + 逐宿主活普查枚举，代价 O(hosts + 边)。§4.4 的威胁模型：
 * 小请求换 O(hosts) 的服务端主线程 CPU 与出站带宽（响应放大）。本缓存把该放大系数消掉：
 * <b>每个服务端 tick 至多重算一次图，同一 tick 内的任意次查询都从缓存复制</b>——单请求摊还
 * O(1)，服务端每 tick 的构图成本有硬上界（与查询速率无关）。</p>
 *
 * <p><b>为什么按 tick 缓存而不是按图代际（机制级论证）</b>：普查是<b>活枚举</b>（issue #25
 * 的刻意设计——供给器每次现枚举当前图，图变更零失效面），因此不存在可用的「代际」计数器；
 * 而图的真实变更只发生在服务端单线程求值路径上（编辑 → 重编译 → 次日起的 tick 里枚举到新
 * 图）。按 tick 缓存给出：同一 tick 内的查询视图一致（单线程求值语义下 tick 是天然的共识
 * 边界），跨 tick 的查询至多滞后一次构图（1/20 秒）——对一个诊断面这是恰当的新鲜度，且
 * <b>不引入任何新的失效钩子</b>。配合 {@code C2SPacketRateGuard.CONSOLE_QUERY} 的限流
 * （突发 8 / 回填 1/s——REPL 限流 1/s 下人类节奏不可能打爆），现实节奏 ≈ 每秒一次构图；
 * 持续灌包者也只会造成每 tick 一次的固定成本。服务端 tick 戳由模组入口既有的
 * {@code ServerTickEvent.Pre} 钩子经 {@link #advanceTick()} 推进（与
 * {@code TopoStats.beginCollectTick} 同一挂点，一次自增）；headless 测试直接调
 * {@link #advanceTick()} 模拟 tick 推进。</p>
 *
 * <p><b>缓存内容与一致性</b>：只缓存不可变的 {@link TopoStats.HostGraph}（节点 / 边 /
 * 逐宿主普查皆不可变视图）——{@code hosts} 的注册表快照仍现取（O(log n) 复制），故跨
 * 「注册表刚变而 tick 未推进」的窗口，{@code running} 列可能滞后到下一 tick——这是本缓存
 * 语义的一部分（按 tick 一致，按 §4.4「请求从缓存复制」）。缓存只服务读，绝不改写注册表
 * 或普查供给器。</p>
 *
 * <hr>
 * <h2>The per-tick dirty cache behind the console's global queries (§4.4 layer 2 of the
 * layered mitigation / issue #29).</h2>
 *
 * <p>{@code topo} / {@code ring} / {@code hosts} (and the census face of {@code bus}) all
 * compute the BE-level dependency graph on the spot — building = registry snapshot + a live
 * census enumeration per host, costing O(hosts + edges). The §4.4 threat model: a small
 * request buys O(hosts) main-thread CPU and outbound bandwidth (response amplification). This
 * cache dissolves that factor: <b>the graph is rebuilt at most once per server tick and every
 * query inside the same tick copies from the cache</b> — O(1) amortized per request, a hard
 * per-tick ceiling on build cost regardless of query rate.</p>
 *
 * <p><b>Why tick-keyed rather than graph-generation-keyed (mechanism-level argument)</b>: the
 * census is a <b>live enumeration</b> (issue #25's deliberate design — the provider enumerates
 * the current graph on every call, so graph changes need no invalidation), hence no usable
 * generation counter exists; and the graph's real changes happen only on the single-threaded
 * server evaluation path (an edit recompiles, the next tick's enumeration sees it). A
 * tick-keyed cache gives: a consistent query view within one tick (under single-threaded
 * evaluation the tick is the natural consensus boundary), and at most one build of staleness
 * across ticks (1/20 s) — the right freshness for a diagnostic face, with <b>no new
 * invalidation hooks introduced</b>. Combined with the {@code C2SPacketRateGuard.CONSOLE_QUERY}
 * budget (burst 8 / refill 1/s — at the 1/s REPL refill no human pace can flood it), the
 * realistic pace is ≈ one build per second; a sustained flooder buys at most one fixed build
 * per tick. The tick stamp is advanced by the mod entry's existing {@code ServerTickEvent.Pre}
 * hook through {@link #advanceTick()} (the same attachment point as
 * {@code TopoStats.beginCollectTick}; one increment); headless tests drive {@link #advanceTick()}
 * directly to simulate ticks.</p>
 *
 * <p><b>Cached content and consistency</b>: only the immutable {@link TopoStats.HostGraph} is
 * cached (nodes / edges / per-host censuses are all immutable views); the {@code hosts}
 * registry snapshot is still taken fresh (an O(log n) copy), so across a window where the
 * registry changed but the tick has not advanced, the {@code running} column may lag into the
 * next tick — part of this cache's semantics (consistent per tick, "requests copy from the
 * cache" per §4.4). The cache serves reads only; it never mutates the registry or the census
 * providers.</p>
 */
public final class ConsoleQueryCache {

    private ConsoleQueryCache() { }

    /** 服务端 tick 戳（模组入口 Pre 钩子推进；headless 测试直接调 {@link #advanceTick()}）。
     *  The server tick stamp (advanced by the mod entry's Pre hook; headless tests call
     *  {@link #advanceTick()} directly). */
    private static volatile long tick = 0L;

    /** 缓存图的构建 tick（-1 = 无缓存）。 / The tick the cached graph was built at (-1 = none). */
    private static volatile long builtAtTick = -1L;

    /** 缓存的依赖图（不可变；null = 从未构建）。 / The cached dependency graph (immutable;
     *  null = never built). */
    private static volatile TopoStats.HostGraph cached;

    /** 推进 tick 戳（每 tick 一次，使下一查询重算图——脏缓存的「脏」半边）。调用方：模组入口
     *  的 {@code ServerTickEvent.Pre} 钩子（与 {@code TopoStats.beginCollectTick} 同一挂点）。
     *  <p>Advance the tick stamp (once per tick, so the next query rebuilds the graph — the
     *  "dirty" half of the dirty cache). Caller: the mod entry's {@code ServerTickEvent.Pre}
     *  hook (the same attachment point as {@code TopoStats.beginCollectTick}).</p> */
    public static void advanceTick() { tick++; }

    /**
     * 当前 tick 的 BE 级依赖图（按 tick 至多重算一次；同 tick 内的调用从缓存复制）。
     * 服务端单线程下无竞态；volatile 只为控制台读者可能来自其它线程时的可见性自洽。
     * 竞态下最多重复构建一次（纯函数、幂等），无正确性影响。
     * <p>The BE-level dependency graph for the current tick (rebuilt at most once per tick;
     * calls within the same tick copy from the cache). No race on the single server thread;
     * volatile merely keeps visibility honest for console readers that may come from other
     * threads. A race costs at most one redundant build (a pure, idempotent function) — no
     * correctness impact.</p>
     *
     * @return 本 tick 的依赖图（不可变） / this tick's dependency graph (immutable)
     */
    public static TopoStats.HostGraph hostGraph() {
        long t = tick;
        TopoStats.HostGraph g = cached;
        if (g != null && builtAtTick == t) return g;
        g = TopoStats.buildHostGraph();
        cached = g;
        builtAtTick = t;
        return g;
    }

    /** 仅测试用复位（清缓存与 tick 戳；生产无需复位——tick 每拍推进即天然失效）。
     *  Test-only reset (clears the cache and the tick stamp; production needs none — the
     *  tick advance invalidates naturally every tick). */
    static void reset() {
        cached = null;
        builtAtTick = -1L;
        tick = 0L;
    }
}
