package io.github.y15173334444.create_schematic_compute.network;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 控制台命令协议服务端侧（{@link ConsoleProtocol}）的 headless 锚——纯 JDK，零 MC 类。
 *
 * <p>覆盖规格 §4.3 / §4.4：</p>
 * <ul>
 * <li>文法唯一真相源：{@code help} 表含全部七条命令名（含未接线者，服务端定义文法，
 * 客户端零文法）。</li>
 * <li>命令分发骨架：首词解析、大小写不敏感、未知命令提示、已知未接线命令的明确提示
 * （不给假数据、不静默）、超长命令钳制。</li>
 * <li>响应上界钳制（§4.4 第 3 层）：行数 / 字节硬上界 + 截断标记（前缀保留）。</li>
 * </ul>
 *
 * <p>Headless anchors for the server side of the console protocol ({@link ConsoleProtocol})
 * — pure JDK, zero MC classes. Covers spec §4.3 / §4.4: the grammar's single source of truth
 * (the help table lists all seven command names including the unwired ones), the dispatch
 * skeleton (first-word parse, case-insensitivity, unknown vs unwired responses, the length
 * cap), and the response clamps (§4.4 layer 3: hard line/byte caps with a prefix-keeping
 * truncation marker).</p>
 */
class ConsoleProtocolTest {

    /** §4.3 命令集全集（七条，全部只读）。 / The full §4.3 command set (all seven, read-only). */
    private static final List<String> ALL_COMMANDS =
        List.of("help", "stale", "topo", "ring", "bus", "perf", "hosts");

    // ── 1. 文法唯一真相源 / the grammar's single source of truth ─────────────────────

    @Test
    @DisplayName("help 表含全部七条命令名（含未接线者）——客户端零文法的服务端定义面")
    void helpTableListsAllSevenCommands() {
        List<String> help = ConsoleProtocol.respond("help");
        assertEquals(ConsoleProtocol.HELP_LINES, help, "help 分发即命令表本身 / help dispatches the table itself");
        String joined = String.join("\n", help);
        for (String cmd : ALL_COMMANDS) {
            assertTrue(joined.contains("  " + cmd + "  "), "表内含命令行: " + cmd + " / the table lists: " + cmd);
        }
        assertTrue(joined.contains("未接线"), "未接线命令名必须显式标注 / unwired rows must be annotated");
        assertTrue(joined.contains("read-only"), "只读口径入表 / the read-only scope is in the table");
    }

    @Test
    @DisplayName("命令表为不可变副本——调用方拿不到可改引用")
    void helpTableIsImmutable() {
        List<String> help = ConsoleProtocol.respond("help");
        try {
            help.add("hacked");
            throw new AssertionError("must have thrown UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            // 预期 / expected
        }
    }

    // ── 2. 命令分发骨架 / the dispatch skeleton ─────────────────────────────────────

    @Test
    @DisplayName("已知未接线命令：明确「尚未接线」提示——不给假数据、不静默")
    void unwiredCommandsSaySoExplicitly() {
        for (String cmd : new String[]{"stale", "topo", "ring", "bus", "perf", "hosts"}) {
            List<String> lines = ConsoleProtocol.respond(cmd);
            assertEquals(1, lines.size());
            assertTrue(lines.get(0).contains("尚未接线"), cmd + " 必须提示未接线 / " + cmd + " must say not-wired");
            assertTrue(lines.get(0).contains(cmd), "提示回含命令名 / the line echoes the command name");
        }
    }

    @Test
    @DisplayName("未知命令：回显命令名 + 指向 help")
    void unknownCommandEchoesAndPointsToHelp() {
        List<String> lines = ConsoleProtocol.respond("frobnicate --all");
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("frobnicate"), "回显首词 / the first word is echoed");
        assertTrue(lines.get(0).contains("help"));
    }

    @Test
    @DisplayName("首词解析：大小写不敏感、尾随参数忽略（help 同样收参）")
    void firstWordParsingIsCaseInsensitiveAndIgnoresArguments() {
        assertEquals(ConsoleProtocol.HELP_LINES, ConsoleProtocol.respond("HELP"));
        assertEquals(ConsoleProtocol.HELP_LINES, ConsoleProtocol.respond("  Help  with args  "),
            "首词后的参数不影响分发 / arguments after the first word do not change dispatch");
        List<String> mixed = ConsoleProtocol.respond("Stale --verbose");
        assertTrue(mixed.get(0).contains("尚未接线"), "大小写折叠到首词 / the head word is case-folded");
        assertTrue(mixed.get(0).contains("stale"), "回显用折叠后小写名 / the echo uses the folded lower-case name");
    }

    @Test
    @DisplayName("空命令（null / 空串 / 纯空白）：提示而非报错")
    void blankCommandGetsTheHint() {
        assertTrue(ConsoleProtocol.respond(null).get(0).contains("help"));
        assertTrue(ConsoleProtocol.respond("").get(0).contains("help"));
        assertTrue(ConsoleProtocol.respond("   \t ").get(0).contains("help"));
    }

    @Test
    @DisplayName("超长命令：钳制提示（服务端复核，不信任客户端）")
    void overlongCommandIsCapped() {
        StringBuilder sb = new StringBuilder("help ");
        for (int i = 0; i < ConsoleProtocol.MAX_COMMAND_LENGTH + 1; i++) sb.append('a');
        List<String> lines = ConsoleProtocol.respond(sb.toString());
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("超长"), "明确提示超长 / explicitly says too-long");
    }

    // ── 3. 响应上界钳制（§4.4 第 3 层） / the response clamps (§4.4 layer 3) ─────────

    @Test
    @DisplayName("行数钳制：200 行硬上界 + 截断标记（前缀保留）")
    void lineCapTruncatesKeepingThePrefix() {
        List<String> big = new ArrayList<>();
        for (int i = 0; i < ConsoleProtocol.MAX_RESPONSE_LINES + 50; i++) big.add("row-" + i);
        List<String> clamped = ConsoleProtocol.clampResponse(big);
        assertEquals(ConsoleProtocol.MAX_RESPONSE_LINES, clamped.size());
        assertEquals("row-0", clamped.get(0), "表头前缀保留 / the prefix is kept");
        assertEquals("row-198", clamped.get(ConsoleProtocol.MAX_RESPONSE_LINES - 2));
        String mark = clamped.get(ConsoleProtocol.MAX_RESPONSE_LINES - 1);
        assertTrue(mark.contains("+51"), "标记行数正确 / the marker counts the suppressed lines: " + mark);
        assertTrue(mark.contains("more"));
    }

    @Test
    @DisplayName("字节钳制：UTF-8 计费的多字节文本按真实字节数截断")
    void byteCapCountsRealUtf8Bytes() {
        // 每行 300 个中文字符 = 900 UTF-8 字节；30 行 ≈ 27KB > 8KB / each line = 900 UTF-8 bytes
        List<String> big = new ArrayList<>();
        for (int i = 0; i < 30; i++) big.add("行" + "中".repeat(299));
        List<String> clamped = ConsoleProtocol.clampResponse(big);
        long bytes = 0;
        for (String line : clamped) bytes += line.getBytes(StandardCharsets.UTF_8).length + 1;
        assertTrue(bytes <= ConsoleProtocol.MAX_RESPONSE_BYTES,
            "钳制后总字节（含标记）不超上界 / the clamped total (incl. marker) stays under the cap: " + bytes);
        assertTrue(clamped.size() < 30);
        assertTrue(clamped.get(clamped.size() - 1).contains("more"), "截断必带标记 / truncation always carries the marker");
        assertEquals("行中中中中", clamped.get(0).substring(0, 5), "前缀保留 / prefix kept");
    }

    @Test
    @DisplayName("单行即超界：截断为标记行（前缀可为空集之上仍回标记）")
    void singleOversizedLineTruncatesToTheMarker() {
        List<String> one = List.of("x".repeat(9000));
        List<String> clamped = ConsoleProtocol.clampResponse(one);
        assertEquals(1, clamped.size());
        assertTrue(clamped.get(0).contains("+1"), "标记计 1 行被省略 / the marker counts 1 suppressed line");
    }

    @Test
    @DisplayName("未超界响应原样通过且不可变")
    void withinCapResponsePassesThroughUnchanged() {
        List<String> help = ConsoleProtocol.respond("help");
        List<String> clamped = ConsoleProtocol.clampResponse(help);
        assertEquals(help, clamped);
        assertTrue(clamped.get(0).contains("grammar is server-defined"));
    }

    @Test
    @DisplayName("空响应：原样空表（无标记）")
    void emptyResponseStaysEmpty() {
        assertEquals(List.of(), ConsoleProtocol.clampResponse(List.of()));
    }
}
