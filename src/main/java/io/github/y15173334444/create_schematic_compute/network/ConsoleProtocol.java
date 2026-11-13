package io.github.y15173334444.create_schematic_compute.network;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 控制台命令协议的服务端侧（C 阶段步骤 5，规格 §4.3 / §4.4）——<b>文法唯一真相源</b>。
 *
 * <p>规格 §4.4 拍板：客户端把<b>原始字符串</b>发给服务端，服务端解析后回文本行
 * （客户端零文法，命令集演进不需要改客户端）。本类承载服务端的两件纯逻辑：</p>
 * <ul>
 * <li>{@link #respond(String)} —— 命令分发骨架：首词解析（trim / 小写 / 长度上限）、
 * {@code help} 返回完整命令表（<b>含未接线命令名</b>——规格 §4.3 服务端 help 表）；已知但
 * 未接线的命令回「尚未接线」行；未知命令回「未知命令」行。本批<b>不实现任何数据命令</b>——
 * {@code stale}/{@code topo}/{@code ring}/{@code bus}/{@code perf}/{@code hosts} 的数据面
 * 由后续接线批接入（它们届时在本 switch 挂上自己的处理分支）。</li>
 * <li>{@link #clampResponse(List)} —— 规格 §4.4 分层缓解第 3 层：响应行数 / 字节硬上界 +
 * 截断标记（单次请求成本有上界）。纯 JDK、headless 可测（照 {@link C2SPacketRateGuard}
 * 的「判定与执行分离」手法——本类只算行，不碰 MC 类）。</li>
 * </ul>
 *
 * <p><b>数据面纪律（规格 §3.3 采样语义警示）</b>：后续接线批的 {@code stale} / 探针类数据
 * 必须取<b>服务端求值快照口径</b>（{@code broadcastEvalSnapshot}）——探针「当前值」读数是
 * 客户端本地求值、读者侧无写者时恒 0 冻结，不是真值。本骨架不预置任何直读总线的入口。</p>
 *
 * <h2>The server side of the console command protocol (Phase-C step 5, spec §4.3 / §4.4)
 * — <b>the grammar's single source of truth</b>.</h2>
 *
 * <p>Spec §4.4 ruling: the client sends the <b>raw string</b> and the server parses and
 * replies with text lines (zero grammar client-side, so the command set can evolve without
 * touching the client). This class carries the server's two pieces of pure logic:
 * {@link #respond(String)} (the dispatch skeleton — first-word parse, {@code help} returning
 * the <b>full</b> command table <b>including the not-yet-wired names</b> per spec §4.3, and
 * polite "not wired yet" lines for the known-but-unwired commands) and {@link #clampResponse(List)}
 * (layer 3 of the §4.4 layered mitigation — hard line/byte caps + truncation marker). Pure
 * JDK, headless-testable (the C2SPacketRateGuard "decision separated from execution"
 * playbook — this class only computes lines, never touches MC classes).</p>
 *
 * <p><b>Data-plane discipline (spec §3.3 sampling-semantics warning)</b>: the wiring batch's
 * {@code stale} / probe-like data must come from the <b>server eval-snapshot plane</b>
 * ({@code broadcastEvalSnapshot}) — the probe's "current value" readout is client-local
 * evaluation, frozen at 0 when the reader's side has no writer, and is not truth. This
 * skeleton pre-plants no direct-bus read entry.</p>
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
            "  stale  本 host 陈旧读 ①/② 计数（按通道分组）/ stale-read counts (tier 1/2, by channel)   [未接线 / not wired yet]",
            "  topo   本 host 读写集 + 上下游 host / read-write set + upstream/downstream hosts   [未接线 / not wired yet]",
            "  ring   BE 级依赖图 SCC 环列表 / BE-level dependency SCC rings   [未接线 / not wired yet]",
            "  bus    本 host 通道一览 / this host's channels (name/value/owner/subscribers)   [未接线 / not wired yet]",
            "  perf   求值耗时与预算计数 / eval timing + budget counters   [未接线 / not wired yet — see /cscperf]",
            "  hosts  全局 host 清单 / global host list   [未接线 / not wired yet]",
            "只读命令（C 阶段无任何写操作）/ read-only commands (no write operations in phase C)."
    );

    /**
     * 服务端命令分发骨架：首词解析 → {@code help} 命令表 / 未接线提示 / 未知命令提示。
     * <b>不做任何数据查询</b>——数据面是后续接线批的事（规格 §4.3 分批口径）。
     * <p>The server dispatch skeleton: first-word parse → the {@code help} table / a
     * not-wired-yet line / an unknown-command line. <b>No data queries here</b> — the data
     * plane belongs to the later wiring batch (spec §4.3 batching).</p>
     *
     * @param raw 客户端原始命令字符串 / the client's raw command string
     * @return 回给客户端的文本行 / the text lines to send back
     */
    public static List<String> respond(String raw) {
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
            // 已发布但未接线的命令：明确说「尚未接线」，不给假数据、不静默（规格 §4.3 服务端表
            // 含未接线名，接线批逐条替换本分支）。
            // Published-but-unwired commands: say so explicitly — no fake data, no silence
            // (spec §4.3's server table lists the unwired names; the wiring batch replaces
            // this branch entry by entry).
            case "stale", "topo", "ring", "bus", "perf", "hosts" ->
                    List.of(head + " 尚未接线（数据面由后续批次接入）/ not wired to the data plane yet (a later batch wires it)");
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
