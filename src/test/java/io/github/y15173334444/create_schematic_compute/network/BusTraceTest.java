package io.github.y15173334444.create_schematic_compute.network;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BusTrace 通道采样器的格式与门控锚。行格式是承重契约——步骤 0 的判定按行 grep 采样日志
 * （观测文档 §1.4 活体实证以其为前提），这里锁死：PUT/GET 行的确切形状、门控解析（trim +
 * 空条目丢弃）、未监视路径零副作用（不推 tick、不耗 seq）、同 tick PUT/GET 的 seq 先后语义
 * （GET 在前 = 跨 BE 拾取窗口证据），以及 {@link SignalBus#put}/{@link SignalBus#get} 真实
 * 挂钩的透传。行事件经包内测试出口收集，不经日志后端，纯 JUnit 可起。
 *
 * <p>Format and gating anchors for the BusTrace channel sampler. The line format is load-bearing
 * — the step-0 verdicts grep the sampled log (the §1.4 live evidence in the observation doc
 * presumes it) — so these tests pin the exact PUT/GET line shape, gate parsing (trim + blank-entry
 * drop), the zero-side-effect unwatched path (no tick advance, no seq consumption), the same-tick
 * PUT/GET seq ordering (GET first = cross-BE pickup-window evidence), and the pass-through of the
 * real {@link SignalBus#put}/{@link SignalBus#get} hooks. Lines are collected through the
 * package-private test sink, headless.</p>
 */
@DisplayName("BusTrace 通道采样器 / BusTrace channel sampler")
class BusTraceTest {

    private static final Pattern T_SEQ = Pattern.compile("^t=(-?\\d+) seq=(-?\\d+) .*$");

    private final List<String> lines = new ArrayList<>();

    @BeforeEach
    void injectSink() {
        BusTrace.sink = lines::add;
    }

    @AfterEach
    void restore() {
        BusTrace.configure(System.getProperty("csc.traceChannels", ""));
        BusTrace.sink = BusTrace.LOG_SINK;
    }

    @Test
    @DisplayName("PUT/GET 行形状钉死：同 tick 内 seq 严格递增")
    void putGetLineShapeIsPinned() {
        BusTrace.configure("shape.a");
        BusTrace.tick();
        BusTrace.onPut("shape.a", 0.5f);
        BusTrace.onGet("shape.a", 0.5f);

        assertEquals(2, lines.size(), () -> "两行事件：" + lines);
        assertTrue(lines.get(0).matches("^t=\\d+ seq=\\d+ PUT shape\\.a=0\\.5$"),
            () -> "PUT 行形状：" + lines.get(0));
        assertTrue(lines.get(1).matches("^t=\\d+ seq=\\d+ GET shape\\.a->0\\.5$"),
            () -> "GET 行形状：" + lines.get(1));
        long[] put = tSeq(lines.get(0));
        long[] get = tSeq(lines.get(1));
        assertEquals(put[0], get[0], "同一 tick 内 t 一致");
        assertEquals(put[1] + 1, get[1], "seq 严格递增（写前记 PUT、读后记 GET）");
    }

    @Test
    @DisplayName("门控解析：条目 trim、空条目丢弃、未监视通道不产行不耗 seq")
    void gateParsingTrimsAndDropsBlankEntries() {
        BusTrace.configure(" g.a ,, g.b ,");
        BusTrace.tick();
        BusTrace.onPut("g.a", 1);
        BusTrace.onPut(" g.a ", 2); // 配置侧 trim 不改通道名本身：带空白的名不匹配
        BusTrace.onPut("g.b", 3);
        BusTrace.onPut("g.c", 4);   // 未监视

        assertEquals(2, lines.size(), () -> "仅 g.a / g.b 各一行：" + lines);
        assertEquals(tSeq(lines.get(0))[1] + 1, tSeq(lines.get(1))[1],
            "g.b 紧随 g.a —— 未监视的两次调用不耗 seq");
    }

    @Test
    @DisplayName("全关路径零副作用：tick 不推进、put/get 不耗 seq")
    void unwatchedPathIsSideEffectFree() {
        BusTrace.configure("z.a");
        BusTrace.tick();
        BusTrace.onPut("z.a", 1);
        long[] base = tSeq(lines.get(0));

        BusTrace.configure("");
        BusTrace.tick();
        BusTrace.onPut("z.a", 2);
        BusTrace.onGet("z.a", 2);

        BusTrace.configure("z.a");
        BusTrace.tick();
        BusTrace.onGet("z.a", 2);

        assertEquals(2, lines.size(), () -> "关闭期零行：" + lines);
        long[] again = tSeq(lines.get(1));
        assertEquals(base[0] + 1, again[0], "关闭期的 tick() 不推进计数");
        assertEquals(base[1] + 1, again[1], "关闭期的 put/get 不耗 seq");
    }

    @Test
    @DisplayName("同 tick 读者先于写者 = 拾取窗口证据形状（GET 行 seq 在 PUT 前）")
    void readerBeforeWriterInSameTickIsThePickupWindowShape() {
        BusTrace.configure("w.a");
        BusTrace.tick();
        BusTrace.onGet("w.a", 0); // 读者宿主先求值：读到上 tick 旧值 0
        BusTrace.onPut("w.a", 1); // 写者宿主后求值

        assertEquals(2, lines.size());
        long[] get = tSeq(lines.get(0));
        long[] put = tSeq(lines.get(1));
        assertEquals(get[0], put[0], "同一 tick");
        assertTrue(get[1] < put[1], "GET 行 seq 落在同 tick PUT 之前");
        assertTrue(lines.get(0).endsWith("GET w.a->0.0"), () -> lines.get(0));
        assertTrue(lines.get(1).endsWith("PUT w.a=1.0"), () -> lines.get(1));
    }

    @Test
    @DisplayName("SignalBus.put/get 真实挂钩透传：返回值不受追踪影响")
    void signalBusPutGetRouteThroughTheSampler() {
        BusTrace.configure("hook.a");
        BusTrace.tick();

        SignalBus.put("hook.a", 3.5f);
        assertEquals(3.5f, SignalBus.get("hook.a"), 0.0f, "返回值不受追踪影响");

        assertEquals(2, lines.size(), () -> "put 与 get 各一行：" + lines);
        assertTrue(lines.get(0).matches("^t=\\d+ seq=\\d+ PUT hook\\.a=3\\.5$"), () -> lines.get(0));
        assertTrue(lines.get(1).matches("^t=\\d+ seq=\\d+ GET hook\\.a->3\\.5$"), () -> lines.get(1));
    }

    private static long[] tSeq(String line) {
        Matcher m = T_SEQ.matcher(line);
        assertTrue(m.matches(), () -> "非采样行形状：" + line);
        return new long[] { Long.parseLong(m.group(1)), Long.parseLong(m.group(2)) };
    }
}
