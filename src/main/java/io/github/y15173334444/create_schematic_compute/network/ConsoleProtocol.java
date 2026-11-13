package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.graph.TopoStats;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 控制台命令协议的服务端侧（C 阶段步骤 5，规格 §4.3 / §4.4）——<b>文法唯一真相源</b>。
 *
 * <p>规格 §4.4 拍板：客户端把<b>原始字符串</b>发给服务端，服务端解析后回文本行
 * （客户端零文法，命令集演进不需要改客户端）。本类承载服务端的纯逻辑：</p>
 * <ul>
 * <li>{@link #respond(String, TopoStats.HostId)} —— 命令分发：首词解析（trim / 小写 /
 * 长度上限）、{@code help} 返回完整命令表（规格 §4.3 服务端 help 表）、数据命令分发到
 * {@link ConsoleDataPlane} 的渲染器（{@code stale}/{@code topo}/{@code ring}/{@code bus}/
 * {@code hosts} —— 接线批 issue #29 逐条替换壳批的「尚未接线」行；{@code perf} 留待后续批）、
 * 未知命令回「未知命令」行。</li>
 * <li>{@link #clampResponse(List)} —— 规格 §4.4 分层缓解第 3 层：响应行数 / 字节硬上界 +
 * 截断标记（单次请求成本有上界）。纯 JDK、headless 可测（照 {@link C2SPacketRateGuard}
 * 的「判定与执行分离」手法——本类只算行，不碰 MC 类）。</li>
 * </ul>
 *
 * <p><b>数据面纪律（规格 §3.3 采样语义警示）</b>：{@code stale} 等观测面只走采集快照
 * （{@link TopoStats#collectionSnapshot()}）——探针「当前值」读数是客户端本地求值、读者侧
 * 无写者时恒 0 冻结，不是真值；本类不预置任何直读总线的入口（{@code bus} 的总线表读取在
 * 分发处显式以维度作用域包裹，见 switch 的 bus 分支注记）。</p>
 *
 * <h2>The server side of the console command protocol (Phase-C step 5, spec §4.3 / §4.4)
 * — <b>the grammar's single source of truth</b>.</h2>
 *
 * <p>Spec §4.4 ruling: the client sends the <b>raw string</b> and the server parses and
 * replies with text lines (zero grammar client-side, so the command set can evolve without
 * touching the client). This class carries the server's pure logic:
 * {@link #respond(String, TopoStats.HostId)} (the dispatch — first-word parse, {@code help}
 * returning the full command table per spec §4.3, the data commands dispatched to
 * {@link ConsoleDataPlane}'s renderers — the wiring batch, issue #29, replaced the shell
 * batch's "not wired yet" lines one by one, {@code perf} waits for a later batch — and the
 * unknown-command line) and {@link #clampResponse(List)} (layer 3 of the §4.4 layered
 * mitigation — hard line/byte caps + truncation marker). Pure JDK, headless-testable (the
 * C2SPacketRateGuard "decision separated from execution" playbook — this class only computes
 * lines, never touches MC classes).</p>
 *
 * <p><b>Data-plane discipline (spec §3.3 sampling-semantics warning)</b>: the observation
 * faces such as {@code stale} ride the collection snapshot ({@link TopoStats#collectionSnapshot()})
 * only — the probe's "current value" readout is client-local evaluation, frozen at 0 when the
 * reader's side has no writer, and is not truth; this class pre-plants no direct-bus read
 * entry (the {@code bus} command's bus-table reads are wrapped in the dimension scope
 * explicitly at the dispatch — see the bus branch's notes in the switch).</p>
 */
public final class ConsoleProtocol {

    private ConsoleProtocol() { }

    /** 命令字符串长度上限（客户端 GraphConsole.Core.MAX_INPUT 同值——服务端复核，不信任
     *  客户端）。 / Command-string length cap (same value as GraphConsole.Core.MAX_INPUT
     *  client-side — re-checked here; the client is never trusted). */
    public static final int MAX_COMMAND_LENGTH = 256;

    /** 响应行数硬上界（§4.4 建议值 200）。 / Hard response line cap (§4.4's suggested 200). */
    public static final int MAX_RESPONSE_LINES = 200;

    /** 响应字节硬上界（§4.4 建议值 8KB，按 UTF-8 计 + 每行 1 字节换行）。
     *  Hard response byte cap (§4.4's suggested 8KB, UTF-8 + 1 newline byte per line). */
    public static final int MAX_RESPONSE_BYTES = 8192;

    /** 截断标记格式（占位行数 + 预留字节见 {@link #clampResponse}）。
     *  Truncation-marker format (reservation rationale in {@link #clampResponse}). */
    public static final String TRUNCATION_MARK = "…(+%d more / 其余 %d 行省略)";

    /**
     * 命令表（服务端定义文法；客户端零文法——规格 §4.3）。<b>含未接线命令名</b>：表先于
     * 数据面落地（issue #27 的「壳」批），未接线者以 {@code [未接线 / not wired yet]} 标注，
     * 接线批逐条替换。不可变副本——调用方拿不到可改引用。
     * <p>The command table (the grammar is server-defined; zero grammar client-side — spec
     * §4.3). <b>Includes the not-yet-wired command names</b>: the table lands with the shell
     * batch (issue #27) before the data plane, unwired rows are annotated
     * {@code [未接线 / not wired yet]}, and the wiring batch replaces them one by one. An
     * immutable copy — callers get no mutable reference.</p>
     */
    public static final List<String> HELP_LINES = List.of(
            "Console commands / 控制台命令 —— grammar is server-defined / 文法由服务端定义:",
            "  help   本表 / this table",
            "  stale  本 host 陈旧读 ①/② 计数（按通道分组）/ stale-read counts (tier 1/2, by channel)",
            "  topo   本 host 读写集 + 上下游 host / read-write set + upstream/downstream hosts",
            "  ring   BE 级依赖图 SCC 环列表 / BE-level dependency SCC rings",
            "  bus    本 host 通道一览 / this host's channels (name/value/owner/subscribers)",
            "  perf   求值耗时与预算计数 / eval timing + budget counters   [未接线 / not wired yet — see /cscperf]",
            "  hosts  全局 host 清单 / global host list",
            "只读命令（C 阶段无任何写操作）/ read-only commands (no write operations in phase C)."
    );

    /**
     * 命令分发（单参形态）：无目标宿主上下文——数据命令里需要「本 host」视角的
     * {@code stale}/{@code topo}/{@code bus} 回「查询点未解析到登记宿主」的可诊断回执；
     * 全局命令 {@code ring}/{@code hosts} 不受影响。
     * <p>The dispatch (the single-argument shape): no target-host context — of the data
     * commands, the "this host" ones ({@code stale}/{@code topo}/{@code bus}) answer the
     * diagnosable "no registered host resolved" line; the global {@code ring}/{@code hosts}
     * are unaffected.</p>
     *
     * @param raw 客户端原始命令字符串 / the client's raw command string
     * @return 回给客户端的文本行 / the text lines to send back
     */
    public static List<String> respond(String raw) {
        return respond(raw, null);
    }

    /**
     * 命令分发：首词解析 → {@code help} 命令表 / 数据命令（{@code ConsoleDataPlane} 渲染，
     * 数据源 = 采集快照 + 按 tick 缓存的依赖图 + 维度作用域内的总线表读取）/ 未知命令提示。
     * <b>数据真值口径（§3.3）</b>：{@code stale} 只走 {@link TopoStats#collectionSnapshot()}；
     * <b>维度作用域（issue #29 硬口径）</b>：{@code bus} 的总线表读取以
     * {@link SignalBus#runInDimension} 包住宿主 BE 的维度；<b>放大控制（§4.4 第 2 层）</b>：
     * {@code topo}/{@code ring}/{@code hosts}/{@code bus} 的构图走
     * {@link ConsoleQueryCache#hostGraph()}（按 tick 至多重算一次）。
     * <p>The dispatch: first-word parse → the {@code help} table / the data commands (rendered
     * by {@code ConsoleDataPlane}; sources = the collection snapshot + the per-tick cached
     * dependency graph + the bus-table reads inside the dimension scope) / an unknown-command
     * line. <b>Data-truth plane (§3.3)</b>: {@code stale} rides {@link TopoStats#collectionSnapshot()}
     * only; <b>dimension scope (the issue #29 hard rule)</b>: {@code bus}'s bus-table reads are
     * wrapped in {@link SignalBus#runInDimension} over the host BE's dimension;
     * <b>amplification control (§4.4 layer 2)</b>: the graph builds of
     * {@code topo}/{@code ring}/{@code hosts}/{@code bus} ride
     * {@link ConsoleQueryCache#hostGraph()} (rebuilt at most once per tick).</p>
     *
     * @param raw    客户端原始命令字符串 / the client's raw command string
     * @param target 查询点解析出的目标宿主（null = 未解析到——需要本 host 视角的命令回可诊断
     *               回执） / the target host resolved at the query point (null = none resolved —
     *               the this-host commands answer diagnosably)
     * @return 回给客户端的文本行 / the text lines to send back
     */
    public static List<String> respond(String raw, TopoStats.HostId target) {
        String cmd = raw == null ? "" : raw.trim();
        if (cmd.length() > MAX_COMMAND_LENGTH) {
            return List.of("命令超长（上限 " + MAX_COMMAND_LENGTH + " 字符）/ command too long (cap "
                    + MAX_COMMAND_LENGTH + " chars)");
        }
        if (cmd.isEmpty()) {
            return List.of("输入 help 查看命令表 / type help for the command list");
        }
        String head = cmd.split("\\s+")[0].toLowerCase(Locale.ROOT);
        return switch (head) {
            case "help" -> HELP_LINES;
            // 口径 A 核心数据：只走采集快照（§3.3 采样语义警示——绝不直读总线）。
            // Criterion-A's core data: the collection snapshot only (the §3.3 sampling-semantics
            // warning — never a direct bus read).
            case "stale" -> ConsoleDataPlane.renderStale(target, TopoStats.collecting(),
                    TopoStats.collectionSnapshot());
            // 全局查询：图按 tick 缓存（§4.4 第 2 层——O(hosts) 摊还 O(1)）。
            // Global queries: the graph is cached per tick (§4.4 layer 2 — O(hosts) amortized to O(1)).
            case "topo" -> ConsoleDataPlane.renderTopo(target, ConsoleQueryCache.hostGraph(),
                    target != null && TopoStats.hostsSnapshot().containsKey(target));
            case "ring" -> ConsoleDataPlane.renderRing(ConsoleQueryCache.hostGraph());
            // 硬口径（issue #29）：bus 读服务端总线表必须落在宿主 BE 的维度作用域内——
            // 不包则经 key() 落进无作用域键空间 = 读错数据。
            // Hard rule (issue #29): bus's server bus-table reads must run inside the host BE's
            // dimension scope — unwrapped they land in the scope-free keyspace through key()
            // = wrong data.
            case "bus" -> {
                if (target == null) {
                    yield List.of("bus：查询点未解析到登记宿主 / no registered host resolved at the query point");
                }
                var g = ConsoleQueryCache.hostGraph();
                yield SignalBus.runInDimension(target.dimension(),
                        () -> ConsoleDataPlane.renderBus(target, ConsoleDataPlane.collectBusRows(g, target)));
            }
            case "hosts" -> ConsoleDataPlane.renderHosts(TopoStats.hostsSnapshot(),
                    ConsoleQueryCache.hostGraph(), TopoStats.collecting(), TopoStats.collectionSnapshot());
            // 已发布但未接线的命令：明确说「尚未接线」，不给假数据、不静默（规格 §4.3 服务端表
            // 含未接线名；接线批逐条替换——本批已接 stale/topo/ring/bus/hosts，perf 留待后续批）。
            // Published-but-unwired commands: say so explicitly — no fake data, no silence
            // (spec §4.3's server table lists the unwired names; the wiring batches replace them
            // one by one — this batch wires stale/topo/ring/bus/hosts, perf waits for a later one).
            case "perf" ->
                    List.of("perf 尚未接线（数据面由后续批次接入；当前用 /cscperf）/ not wired to the data plane yet (use /cscperf)");
            default -> List.of("未知命令 / unknown command: " + head + " — try help");
        };
    }

    /**
     * 响应上界钳制（§4.4 分层缓解第 3 层）：行数 ≤ {@link #MAX_RESPONSE_LINES} 且 UTF-8
     * 字节（含每行 1 字节换行）≤ {@link #MAX_RESPONSE_BYTES}；超出即截断并在末尾追加
     * {@link #TRUNCATION_MARK} 行。截断为<b>前缀保留</b>（顺序即语义：表头在前）。
     * <p>预留：截断发生时标记行自身也占一行 / 若干字节，为它常驻预留 1 行 + 128 字节——
     * 避免边界抖动（截断与否的判定不需要两遍扫描）。</p>
     * <p>The response cap (layer 3 of the §4.4 layered mitigation): at most
     * {@link #MAX_RESPONSE_LINES} lines and {@link #MAX_RESPONSE_BYTES} UTF-8 bytes (one
     * newline byte per line counted); anything beyond is truncated with a trailing
     * {@link #TRUNCATION_MARK} line. Truncation keeps the <b>prefix</b> (order is
     * semantics: table headers come first).</p>
     * <p>Reservation: the marker line itself costs a line and some bytes when truncation
     * fires, so 1 line + 128 bytes are always reserved for it — no two-pass sizing and no
     * boundary thrash.</p>
     *
     * @param lines 服务端产出的原始行 / the raw lines the server produced
     * @return 钳制后的行（可能带截断标记） / the clamped lines (possibly with the marker)
     */
    public static List<String> clampResponse(List<String> lines) {
        if (lines.isEmpty()) return List.of();
        int lineCap = MAX_RESPONSE_LINES - 1;               // 标记行常驻预留 / marker reservation
        int byteCap = MAX_RESPONSE_BYTES - 128;
        int kept = 0;
        long bytes = 0;
        for (String line : lines) {
            int lineBytes = line.getBytes(StandardCharsets.UTF_8).length + 1;
            if (kept >= lineCap || bytes + lineBytes > byteCap) break;
            bytes += lineBytes;
            kept++;
        }
        if (kept == lines.size()) return List.copyOf(lines);
        List<String> out = new ArrayList<>(kept + 1);
        out.addAll(lines.subList(0, kept));
        int suppressed = lines.size() - kept;
        out.add(String.format(Locale.ROOT, TRUNCATION_MARK, suppressed, suppressed));
        return List.copyOf(out);
    }
}
