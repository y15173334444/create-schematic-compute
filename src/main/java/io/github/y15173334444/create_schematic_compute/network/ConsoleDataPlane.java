package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.graph.TopoStats;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * <h2>控制台数据命令的数据面（§4.3 命令集 / issue #29）：{@code stale} / {@code topo} /
 * {@code ring} / {@code bus} / {@code hosts} 的渲染器</h2>
 *
 * <p>壳批（issue #27）的 {@link ConsoleProtocol#respond} 只回「尚未接线」；本类把五条命令
 * 接上真实数据源（issue #29 对照表逐行落实），行形状由本类纯函数产出——<b>零 MC import</b>
 * （照壳批分层先例：文法/分发在 {@code ConsoleProtocol}，数据面在 {@code ConsoleDataPlane}，
 * 两者都可 headless 直测）。数据源全部来自 main 既有设施：</p>
 * <ul>
 * <li>{@code stale} —— {@link TopoStats#collectionSnapshot()}（口径 A 的 Post 汇总快照，
 * 按 host × 通道分组的累计 ①/② 计数 + 同/跨 tick 被超越跨度）。<b>数据真值口径（§3.3
 * 采样语义警示）</b>：只走采集快照，绝不直读总线——探针「当前值」读数是客户端本地求值、
 * 与服务端真值脱节。</li>
 * <li>{@code topo} —— {@link ConsoleQueryCache#hostGraph()} 的边集与普查（写者 → 读者；
 * 上游/下游按通道配对）。</li>
 * <li>{@code ring} —— {@link TopoStats#cyclicComponents}（§5.3 切边唯一输入；成员 + 环上
 * 通道名；无环图明确回「无环」而非空行——可诊断性）。</li>
 * <li>{@code bus} —— 通道清单取普查口径（本 host 读写集），值 / 占用 / 冲突证据<b>在宿主
 * 维度作用域内</b>读 {@code SignalBus} 服务端表（{@link SignalBus#runInDimension} 包住——
 * issue #29 硬口径：不包 = 读无作用域键空间 = 错数据）；订阅者数 = 该通道在依赖图内的
 * 读者宿主数（结构性口径——逐节点的订阅注册不存在，{@code ChannelEntry.refCount} 计的是
 * 发布方注册次数，不是订阅者）。</li>
 * <li>{@code hosts} —— {@link TopoStats#hostsSnapshot()}（登记清单）+ 缓存图的节点集
 * （running = 普查非空即入图，§3.2 口径）+ {@code collectionSnapshot().byHost}（陈旧
 * 计数；采集关时该列诚实显示「—」）。</li>
 * </ul>
 *
 * <p><b>诚实记录（条目 31.1 已接线）</b>：§4.3 的 {@code hosts} 行「本 tick 求值序号」——
 * 全局拓扑重排（§5.2 A）后求值遍（{@code HostEvalPass.runPostPass}）按求值顺序逐宿主登记
 * 1..N 序号，渲染面取最近已完成遍的真实序号（遍内未求值者诚实显示「—」，不伪造）。</p>
 *
 * <p><b>输出纪律</b>：全部键有序（通道键 / HostId 自然序）+ SCC 按最小成员序——同一数据
 * 任意次重跑输出全同（诊断可复现）；行数/字节上界由调用方 {@link ConsoleProtocol#clampResponse}
 * 统一钳制（§4.4 第 3 层），本类不重复钳制、不回退。</p>
 *
 * <hr>
 * <h2>The data plane of the console's data commands (§4.3 command set / issue #29): the
 * renderers for {@code stale} / {@code topo} / {@code ring} / {@code bus} / {@code hosts}.</h2>
 *
 * <p>The shell batch's ({@code issue #27}) {@link ConsoleProtocol#respond} only said "not
 * wired yet"; this class wires the five commands to their real data sources (the issue #29
 * table, row by row), with line shapes produced by these pure functions — <b>zero MC imports</b>
 * (the shell-batch layering precedent: grammar/dispatch in {@code ConsoleProtocol}, the data
 * plane in {@code ConsoleDataPlane}, both directly testable headless). Every source already
 * exists on main:</p>
 * <ul>
 * <li>{@code stale} — {@link TopoStats#collectionSnapshot()} (the criterion-A Post-summary
 * snapshot: cumulative ①/② counts grouped by host × channel plus the same/cross-tick
 * superseded spans). <b>Data-truth plane (the §3.3 sampling-semantics warning)</b>: the
 * collection snapshot only — never a direct bus read; the probe's "current value" readout is
 * client-local evaluation, disconnected from server truth.</li>
 * <li>{@code topo} — the cached graph's edges and censuses ({@link ConsoleQueryCache#hostGraph()};
 * writer → reader; upstream/downstream paired per channel).</li>
 * <li>{@code ring} — {@link TopoStats#cyclicComponents} (the sole input of §5.3 edge cutting;
 * members + the channels on the ring; an acyclic graph explicitly answers "acyclic", never an
 * empty reply — diagnosability).</li>
 * <li>{@code bus} — the channel list rides the census (this host's read/write sets); values /
 * occupancy / conflict evidence are read from the server's {@code SignalBus} tables <b>inside
 * the host's dimension scope</b> (wrapped in {@link SignalBus#runInDimension} — the issue #29
 * hard rule: unwrapped, reads land in the scope-free keyspace = wrong data); the subscriber
 * count = the channel's reader-host count within the dependency graph (the structural measure
 * — no per-node subscriber registration exists anywhere, and {@code ChannelEntry.refCount}
 * counts publisher registrations, not subscribers).</li>
 * <li>{@code hosts} — {@link TopoStats#hostsSnapshot()} (the registry listing) + the cached
 * graph's node set (running = census non-empty = in the graph, the §3.2 criterion) +
 * {@code collectionSnapshot().byHost} (the stale counts; honestly "—" while collection is
 * off).</li>
 * </ul>
 *
 * <p><b>Honest record (register entry 31.1 wired)</b>: §4.3's {@code hosts} row's "this-tick
 * evaluation ordinal" — after the global topology reordering (§5.2 A) the evaluation pass
 * ({@code HostEvalPass.runPostPass}) stamps each host 1..N in evaluation order; the renderer
 * takes the real ordinals of the most recently completed pass (a host the pass did not
 * evaluate honestly shows "—" — nothing fabricated).</p>
 *
 * <p><b>Output discipline</b>: every key is ordered (channel-key / HostId natural order, SCCs
 * by smallest member) — the same data replays identically on every run (reproducible
 * diagnostics); the line/byte caps are applied once by the caller through
 * {@link ConsoleProtocol#clampResponse} (§4.4 layer 3) — this class neither re-clamps nor
 * regresses them.</p>
 */
public final class ConsoleDataPlane {

    private ConsoleDataPlane() { }

    // ── 目标宿主解析 / target-host resolution ──────────────────────────────

    /**
     * 从全局注册表解析查询点的目标宿主（§4.4：查询落的是宿主 BE 的维度）。先精确匹配
     * （查询维 + 坐标——查询者与宿主通常同维度），再按坐标回退跨维度匹配（注册表跨维度
     * 平铺，两维度可能同坐标各有一台宿主）；无匹配 = {@code null}（调用方渲染「未解析到
     * 宿主」的可诊断回执，绝不猜一个宿主）。
     * <p>Resolve the query point's target host from the global registry (§4.4: the query
     * lands on the host BE's dimension). Exact match first (the querying dimension + position
     * — querier and host normally share a dimension), then a cross-dimension fallback by
     * position (the registry is flat across dimensions; two dimensions may each hold a host at
     * the same coordinates); no match = {@code null} (the caller renders a diagnosable
     * "no host resolved" reply — it never guesses a host).</p>
     *
     * @param dimension 查询方所在维度 id / the querying side's dimension id
     * @param x,y,z     查询点方块坐标 / the query point's block coordinates
     * @return 目标宿主身份（注册表内）或 null / the target host identity (in the registry) or null
     */
    public static TopoStats.HostId resolveHost(String dimension, int x, int y, int z) {
        var snap = TopoStats.hostsSnapshot();
        var exact = new TopoStats.HostId(dimension, x, y, z);
        if (snap.containsKey(exact)) return exact;
        for (var id : snap.keySet())
            if (id.x() == x && id.y() == y && id.z() == z) return id;
        return null;
    }

    // ── stale（口径 A 核心数据，§4.3） / stale (criterion-A's core data, §4.3) ───────────

    /**
     * {@code stale} 渲染：本 host 的累计 ①/② 计数，按通道分组 + 跨度（同/跨 tick 被超越）
     * + 合计行。采集门关闭时明确回「采集未启用（-Dcsc.topoCollect）」而非空数据（可诊断性
     * 硬口径）；门开但快照尚未经 Post 汇总（tick=0）时也明说。
     * <p>The {@code stale} renderer: this host's cumulative ①/② counts, grouped by channel,
     * with the spans (same/cross-tick superseded) and a total row. Gate off answers explicitly
     * "collection disabled (-Dcsc.topoCollect)" instead of empty data (the diagnosability hard
     * rule); gate on but the snapshot not yet Post-summarized (tick=0) says so too.</p>
     *
     * @param target     目标宿主（null = 查询点未解析到宿主） / the target host (null = none
     *                   resolved at the query point)
     * @param collecting 采集门（{@code TopoStats.collecting()}） / the collection gate
     * @param snap       最新采集快照 / the latest collection snapshot
     * @return 文本行 / the text lines
     */
    static List<String> renderStale(TopoStats.HostId target, boolean collecting,
                                    TopoStats.CollectSnapshot snap) {
        if (!collecting) return List.of(
            "stale：采集未启用（JVM 参数 -Dcsc.topoCollect）——无陈旧读数据 / collection disabled "
                + "(JVM property -Dcsc.topoCollect) — no stale-read data");
        if (target == null) return List.of(
            "stale：查询点未解析到登记宿主 / no registered host resolved at the query point");
        var byChannel = snap.byHostChannel().get(target);
        var out = new ArrayList<String>();
        out.add("stale — host " + target + "（累计口径 / cumulative, snapshot tick=" + snap.tick() + "）:");
        if (byChannel == null || byChannel.isEmpty()) {
            out.add(snap.tick() == 0L
                ? "  （快照尚未汇总——等第一个 Post 趟次 / snapshot not yet summarized — waiting for the first Post pass）"
                : "  无陈旧读记录 / no stale reads recorded");
            return out;
        }
        for (var e : byChannel.entrySet()) {
            var c = e.getValue();
            out.add("  " + SignalBus.logicalName(e.getKey())
                + "  ①=" + c.staleReads() + " ②=" + c.staleValueChanged()
                + " 同tick=" + c.supersededSameTick() + " 跨tick=" + c.supersededCrossTick());
        }
        var total = snap.byHost().get(target);
        if (total != null) {
            out.add("  合计 / total  ①=" + total.staleReads() + " ②=" + total.staleValueChanged()
                + " 同tick=" + total.supersededSameTick() + " 跨tick=" + total.supersededCrossTick());
        }
        return out;
    }

    // ── topo（读写集 + 上下游，§4.3） / topo (read-write set + upstream/downstream, §4.3) ──

    /**
     * {@code topo} 渲染：本 host 的读集（每通道 ← 写者宿主）与写集（每通道 → 读者宿主），
     * 即「上游 / 下游 host（坐标 + 通道名）」；悬空读者（通道无写者）如实标注。宿主停机或
     * 空图（普查为空、不在 DAG 分母内）与未登记宿主各有明确的诊断行。实现按边线性扫描配对
     * （通道数 × 边数——缓存图上的诊断面成本，现实规模千级以下，不预建索引）。
     * <p>The {@code topo} renderer: this host's read set (per channel ← its writer hosts) and
     * write set (per channel → its reader hosts) — the "upstream / downstream hosts
     * (coordinates + channel names)"; a dangling reader (a channel with no writer) is marked
     * honestly. A stopped or empty-graph host (empty census, outside the DAG denominator) and
     * an unregistered host each get their own diagnostic line. Pairing scans the edges
     * linearly (channels × edges — a diagnostic-face cost over the cached graph; realistic
     * scale stays under thousands, so no index is prebuilt).</p>
     *
     * @param target     目标宿主 / the target host
     * @param g          缓存依赖图 / the cached dependency graph
     * @param registered 目标是否在注册表内 / whether the target is registered
     * @return 文本行 / the text lines
     */
    static List<String> renderTopo(TopoStats.HostId target, TopoStats.HostGraph g, boolean registered) {
        if (target == null) return List.of(
            "topo：查询点未解析到登记宿主 / no registered host resolved at the query point");
        if (!registered) return List.of(
            "topo — host " + target + ": 未登记宿主（不在全局注册表）/ unregistered host "
                + "(absent from the global registry)");
        var census = g.censusOf(target);
        if (census.isEmpty()) return List.of(
            "topo — host " + target + ": 停机或空图宿主（普查为空，不在依赖图分母内，§3.2）/"
                + " stopped or empty-graph host (empty census, outside the DAG denominator, §3.2)");
        var out = new ArrayList<String>();
        out.add("topo — host " + target + "（在图 / in graph）:");
        out.add("  读集 (" + census.reads().size() + ") / reads:");
        for (String ch : census.reads()) {
            var writers = new TreeSet<String>();
            for (var e : g.edges())
                if (e.reader().equals(target) && e.channel().equals(ch)) writers.add(e.writer().toString());
            out.add("    " + SignalBus.logicalName(ch) + " ← "
                + (writers.isEmpty() ? "（无写者 / no writer）" : String.join("；", writers)));
        }
        out.add("  写集 (" + census.writes().size() + ") / writes:");
        for (String ch : census.writes()) {
            var readers = new TreeSet<String>();
            for (var e : g.edges())
                if (e.writer().equals(target) && e.channel().equals(ch)) readers.add(e.reader().toString());
            out.add("    " + SignalBus.logicalName(ch) + " → "
                + (readers.isEmpty() ? "（无读者 / no reader）" : String.join("；", readers)));
        }
        if (g.hasSelfLoop(target)) {
            out.add("  （自环：读自己写的通道——主图与 ENCAP 子图同通道，§3.2 / self-loop: reads a "
                + "channel it writes — main graph and ENCAP sub-graph sharing one, §3.2）");
        }
        return out;
    }

    // ── ring（SCC 环列表——切边唯一输入，§4.3/§5.3） / ring (the SCC list — the sole input
    //    of edge cutting, §4.3/§5.3) ──────────────────────────────

    /**
     * {@code ring} 渲染：全局 SCC 真环列表（成员 + 环上通道名 = 两端都在本分量内的边的通道）。
     * 无环图明确回「无环（DAG）」行而非空行（可诊断性硬口径）；表头始终带入图宿主数与边数，
     * 空图也自洽（宿主 0 / 边 0 的无环）。
     * <p>The {@code ring} renderer: the global list of genuine cyclic SCCs (members + the
     * channels on the ring = the channels of edges with both ends inside the component). An
     * acyclic graph explicitly answers the "acyclic (DAG)" line, never an empty reply (the
     * diagnosability hard rule); the header always carries the in-graph host and edge counts,
     * self-consistent even for an empty graph (0 hosts / 0 edges, acyclic).</p>
     *
     * @param g 缓存依赖图 / the cached dependency graph
     * @return 文本行 / the text lines
     */
    static List<String> renderRing(TopoStats.HostGraph g) {
        var out = new ArrayList<String>();
        out.add("ring — 入图宿主 " + g.hosts().size() + " / 边 " + g.edges().size()
            + "（全局 BE 级依赖图 / global BE-level graph）:");
        var cycles = TopoStats.cyclicComponents(g);
        if (cycles.isEmpty()) {
            out.add("  无环（DAG）——无需切边 / acyclic (DAG) — no edge cutting needed");
            return out;
        }
        int k = 1;
        for (var scc : cycles) {
            var members = new ArrayList<String>();
            for (var h : scc) members.add(h.toString());
            out.add("  环" + k + " (" + scc.size() + "): " + String.join("；", members));
            var channels = new TreeSet<String>();
            for (var e : g.edges())
                if (scc.contains(e.writer()) && scc.contains(e.reader()))
                    channels.add(SignalBus.logicalName(e.channel()));
            out.add("    环上通道 (" + channels.size() + ") / on-ring channels: "
                + String.join("，", channels));
            k++;
        }
        return out;
    }

    // ── bus（本 host 通道一览，§4.3） / bus (this host's channels, §4.3) ────────────────

    /** 单通道行视图（采集自 SignalBus 服务端表；值/占用读取发生在宿主维度作用域内——调用方
     *  {@code ConsoleProtocol.respond} 的 bus 分支以 {@link SignalBus#runInDimension} 包住）。
     *  载体判定 = 查 CHANNELS 在场（BUS 载体）否则按 PRIVATE 载体读——普查集不区分
     *  BUS_IN/PRIVATE_IN（issue #25 的普查形状），同名通道双载体并存时优先显示 BUS 载体
     *  （其状态更丰富），诚实记录于 javadoc。
     *  <p>One channel's row view (collected from the server's SignalBus tables; the value/
     *  occupancy reads happen inside the host's dimension scope — the caller's bus branch in
     *  {@code ConsoleProtocol.respond} wraps them in {@link SignalBus#runInDimension}). Carrier
     *  judgement = a CHANNELS entry present (BUS carrier), else the PRIVATE carrier — the
     *  census set does not distinguish BUS_IN/PRIVATE_IN (issue #25's census shape); when both
     *  carriers coexist under one name the BUS carrier is displayed (the richer state), an
     *  honest record in this javadoc.</p> */
    record BusChannelRow(String channel, String carrier, String value, String owner,
                         int subscribers, boolean conflict) { }

    /** 每通道 band 值的最多显示数（超出以 {@code …(+N)} 收尾；整行不至于被字节钳制整行丢弃）。
     *  The maximum band values shown per channel (beyond it the line ends {@code …(+N)}; keeps
     *  one line from being dropped wholesale by the byte clamp). */
    private static final int MAX_BANDS_SHOWN = 8;

    /**
     * 收集本 host 各通道的 bus 行（<b>调用方必须已在宿主维度作用域内</b>——值/占用/候选读取
     * 经 {@code SignalBus.key} 落宿主键空间）。浮点载体口径：BUS 载体 = 频段 → 浮点的
     * {@code internalMap}（读取时刻的一致副本）；PRIVATE 载体 = SIGNALS 值。音频/向量载体
     * 不入值列（§3.1 epoch 模型按浮点载体记账，观察面同口径；结构上这些节点仍在普查里）。
     * <p>Collect the bus rows for this host's channels (<b>the caller must already be inside
     * the host's dimension scope</b> — value/occupancy/claimant reads land in the host's
     * keyspace through {@code SignalBus.key}). Float-carrier plane: the BUS carrier = the
     * band → float {@code internalMap} (a consistent copy taken at read time); the PRIVATE
     * carrier = the SIGNALS value. Audio/vector carriers stay out of the value column (the
     * §3.1 epoch model accounts on the float carrier; the observation face matches; those
     * nodes are still in the census structurally).</p>
     *
     * @param g      缓存依赖图（订阅者计数与普查来源） / the cached dependency graph (the
     *               subscriber-count and census source)
     * @param target 目标宿主 / the target host
     * @return 按通道键有序的行 / rows ordered by channel key
     */
    static List<BusChannelRow> collectBusRows(TopoStats.HostGraph g, TopoStats.HostId target) {
        var census = g.censusOf(target);
        var channels = new TreeSet<String>();
        channels.addAll(census.reads());
        channels.addAll(census.writes());
        var rows = new ArrayList<BusChannelRow>();
        for (String ch : channels) {
            String logical = SignalBus.logicalName(ch);
            int subscribers = subscriberCount(g, ch);
            var entry = SignalBus.getChannel(logical);
            if (entry != null) {
                var bands = new TreeMap<>(entry.internalMap);
                rows.add(new BusChannelRow(logical, "BUS", bandSummary(bands),
                    ownerString(entry.owner), subscribers, !SignalBus.claimants(logical).isEmpty()));
            } else {
                Float v = SignalBus.signalValue(logical);
                rows.add(new BusChannelRow(logical, "PRIVATE",
                    v != null ? Float.toString(v) : "—",
                    ownerString(SignalBus.getPrivateOwner(logical)), subscribers, false));
            }
        }
        return rows;
    }

    /** 通道的读者宿主数（依赖图内普查含该读通道的宿主数——结构性订阅口径）。
     *  The channel's reader-host count (hosts whose census reads the channel within the graph
     *  — the structural subscriber measure). */
    private static int subscriberCount(TopoStats.HostGraph g, String channelKey) {
        int n = 0;
        for (var h : g.hosts())
            if (g.censusOf(h).reads().contains(channelKey)) n++;
        return n;
    }

    /** 频段值摘要：{@code band=value} 逗号连接，最多 {@link #MAX_BANDS_SHOWN} 个，超出
     *  {@code …(+N)}；无频段 = 「—」。 / The band-value summary: {@code band=value} joined by
     *  commas, at most {@link #MAX_BANDS_SHOWN}, beyond it {@code …(+N)}; no bands = "—". */
    private static String bandSummary(TreeMap<String, Float> bands) {
        if (bands.isEmpty()) return "—";
        var sb = new StringBuilder();
        int shown = 0;
        for (var e : bands.entrySet()) {
            if (shown == MAX_BANDS_SHOWN) {
                sb.append(" …(+").append(bands.size() - shown).append(')');
                break;
            }
            if (shown > 0) sb.append("，");
            String band = e.getKey();
            sb.append(band.isEmpty() ? "（无频段 / band-less）" : band)
                .append('=').append(Float.toString(e.getValue()));
            shown++;
        }
        return sb.toString();
    }

    /** owner 的空安全显示（headless 测试可种 null-pos owner——显示 {@code [?]}；null owner
     *  = 「—」；绝不调 {@link ChannelOwner#toString} 以外的推断）。 / The owner's null-safe
     *  display (headless tests may plant a null-pos owner — shown as {@code [?]}; a null owner
     *  = "—"). */
    static String ownerString(ChannelOwner o) {
        if (o == null) return "—";
        return "[" + (o.pos() != null ? o.pos().toShortString() : "?") + "]#" + o.nodeId();
    }

    /**
     * {@code bus} 渲染：每通道一行（载体 / 名 / 值 / owner / 订阅者数 / 冲突标记）。空通道
     * 集（停机或空图宿主，§3.2 口径）明确回一行而非空数据。
     * <p>The {@code bus} renderer: one line per channel (carrier / name / value / owner /
     * subscriber count / conflict mark). An empty channel set (a stopped or empty-graph host,
     * the §3.2 criterion) explicitly answers one line instead of empty data.</p>
     *
     * @param target 目标宿主 / the target host
     * @param rows   作用域内收集的通道行 / the rows collected inside the scope
     * @return 文本行 / the text lines
     */
    static List<String> renderBus(TopoStats.HostId target, List<BusChannelRow> rows) {
        var out = new ArrayList<String>();
        out.add("bus — host " + target + "（通道=普查口径；值/占用=宿主维度作用域内读取 / channels "
            + "per the census; values read inside the host's dimension scope）:");
        if (rows.isEmpty()) {
            out.add("  本 host 无通道（停机或空图，§3.2）/ no channels (stopped or empty graph, §3.2)");
            return out;
        }
        for (var r : rows) {
            out.add("  [" + r.carrier() + "] " + r.channel()
                + "  值: " + r.value()
                + "  owner: " + r.owner()
                + "  订阅: " + r.subscribers()
                + "  冲突: " + (r.conflict()
                    ? "有（存在被拒注册候选 / refused-registration claimants）" : "—"));
        }
        return out;
    }

    // ── hosts（全局 host 清单，§4.3） / hosts (the global host list, §4.3) ─────────────

    /**
     * {@code hosts} 渲染：登记宿主逐行（清单序号 / 坐标 / running / 本遍求值序号 / 陈旧计数）。
     * running = 普查非空即入图（§3.2 口径，经缓存图节点集判定）；求值序号取最近一次已完成
     * 求值遍（§5.2 A 的 Post 拓扑遍，{@code HostEvalPass.evalOrdinals}）的真实序号——
     * 遍内未求值（本 tick 未走过前段 / 停机早退）的宿主该列诚实显示「—」（登记册条目 31.1
     * 的接线形态；查询包处理于 tick 前段，读到的总是上一已完成遍）；陈旧计数取采集快照的
     * 按 host 累计（无记录 = 全 0）；采集关闭时该列诚实显示「—」并在表头说明（可诊断性，
     * 不回退成空数据）。
     * <p>The {@code hosts} renderer: one line per registered host (listing ordinal /
     * coordinates / running / this-pass evaluation ordinal / stale counts). running = census
     * non-empty = in the graph (the §3.2 criterion, judged over the cached graph's node
     * set); the evaluation ordinal rides the most recently completed evaluation pass (§5.2
     * A's Post topological pass, {@code HostEvalPass.evalOrdinals}) — a host the pass did
     * not evaluate (its prologue never ran this tick, or the stopped early-return) honestly
     * shows "—" (the wiring form of register entry 31.1; query packets are processed in the
     * tick prologue, so readers always see the last completed pass); stale counts ride the
     * collection snapshot's per-host cumulative tallies (no record = all zeros); with
     * collection off the column honestly shows "—" and the header says so (diagnosability,
     * no regression to empty data).</p>
     *
     * @param hosts        注册表快照（HostId 有序） / the registry snapshot (HostId-ordered)
     * @param g            缓存依赖图（running 判定） / the cached dependency graph (the
     *                     running judgement)
     * @param collecting   采集门 / the collection gate
     * @param snap         最新采集快照 / the latest collection snapshot
     * @param evalOrdinals 最近已完成求值遍的逐宿主序号 / the last completed pass's per-host
     *                     ordinals
     * @return 文本行 / the text lines
     */
    static List<String> renderHosts(Map<TopoStats.HostId, TopoStats.CensusProvider> hosts,
                                    TopoStats.HostGraph g, boolean collecting,
                                    TopoStats.CollectSnapshot snap,
                                    Map<TopoStats.HostId, Integer> evalOrdinals) {
        var out = new ArrayList<String>();
        out.add("hosts — 登记宿主 " + hosts.size() + " / registered hosts（running=普查非空即入图/"
            + "running = census non-empty = in graph"
            + (collecting
                ? "；陈旧计数=累计，快照 tick=" + snap.tick() + " / counts cumulative, snapshot tick="
                    + snap.tick()
                : "；采集未启用：陈旧计数不可用（-Dcsc.topoCollect）/ collection disabled: counts "
                    + "unavailable (-Dcsc.topoCollect)") + "）:");
        if (hosts.isEmpty()) {
            out.add("  （注册表为空 / registry empty）");
            return out;
        }
        int i = 1;
        Set<TopoStats.HostId> inGraph = g.hosts();
        for (var id : hosts.keySet()) {
            String counts;
            if (collecting) {
                var c = snap.byHost().get(id);
                counts = c != null
                    ? "①=" + c.staleReads() + " ②=" + c.staleValueChanged()
                        + " 同=" + c.supersededSameTick() + " 跨=" + c.supersededCrossTick()
                    : "①=0 ②=0 同=0 跨=0";
            } else {
                counts = "①=— ②=— 同=— 跨=—";
            }
            Integer ord = evalOrdinals.get(id);
            out.add("  " + i + ". " + id + " running=" + (inGraph.contains(id) ? "yes" : "no")
                + "  序=" + (ord != null ? ord : "—") + "  " + counts);
            i++;
        }
        return out;
    }
}
