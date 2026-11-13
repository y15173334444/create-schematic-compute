package io.github.y15173334444.create_schematic_compute.network;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 进程内信号总线的轻量诊断追踪 —— cross-be 步骤 0 观测装置的正式化（机制与判定口径见
 * docs/cross-be-latency-observation.md §1.4 / §3.3）。对被监视通道的 {@code put}/{@code get}
 * 逐次记一行：{@code t=<tick> seq=<序号> PUT <通道>=<值>} / {@code t=<tick> seq=<序号> GET <通道>-><值>}。
 * tick 是服务端 tick 计数（{@code ServerTickEvent.Pre} 递增），seq 是进程内单调序号——同一 tick 内
 * GET 行的 seq 落在 PUT 行之前，即跨 BE 拾取窗口的直接证据（读者宿主先于写者求值，读到上 tick 旧值）。
 *
 * <p>行格式是承重契约：步骤 0 的判定按行 grep 本日志（§1.4 活体实证），改动格式必须同一变更里
 * 同步观测文档与 {@code BusTraceTest} 的格式锚。</p>
 *
 * <p>门控：JVM 属性 {@code -Dcsc.traceChannels=ch1,ch2}（逗号分隔、条目容忍首尾空白、空条目丢弃）
 * 仅在类加载时读取，运行期不可改。未配置时零行为——每次 put/get 只多一次空表判断（近零开销），
 * tick 计数也不推进。启用后每个被监视通道的每次读写都刷一行 INFO，高频通道会显著放大日志量：
 * 只为临时诊断开启，勿常驻。</p>
 *
 * <p>Lightweight diagnostic tracing for the process-wide signal bus — the formalized step-0
 * observation rig (mechanism and verdict criteria: docs/cross-be-latency-observation.md §1.4/§3.3).
 * Every {@code put}/{@code get} on a watched channel emits one line:
 * {@code t=<tick> seq=<n> PUT <channel>=<value>} / {@code t=<tick> seq=<n> GET <channel>-><value>}.
 * The tick is the server tick counter (bumped in {@code ServerTickEvent.Pre}); the seq is a
 * process-wide monotonic number — a GET line sequenced before the same tick's PUT is direct
 * evidence of the cross-BE pickup window (the reader host evaluated before the writer and saw
 * last tick's value).</p>
 *
 * <p>The line format is load-bearing: the step-0 verdicts grep these lines (§1.4 live evidence),
 * so a format change must update the observation doc and the {@code BusTraceTest} format anchors
 * in the same change.</p>
 *
 * <p>Gating: the JVM property {@code -Dcsc.traceChannels=ch1,ch2} (comma-separated, entries
 * tolerate surrounding whitespace, blank entries dropped) is read once at class load and cannot
 * change at runtime. Unset, it costs nothing beyond one empty-set check per put/get (near-zero
 * overhead) and the tick counter never advances. Enabled, every read/write of a watched channel
 * logs one INFO line — busy channels amplify log volume considerably, so enable it only for
 * temporary diagnosis.</p>
 */
public final class BusTrace {

    private BusTrace() {}

    /** 独立 logger：不引用 @Mod 类（其静态初始化需要 MC 注册表，纯 JUnit 起不来）——SignalBus 同款口径。 */
    private static final Logger LOG = LoggerFactory.getLogger("BusTrace");

    private static final String PROPERTY = "csc.traceChannels";

    /** 监视表：仅启动期从 {@code csc.traceChannels} 解析；测试可经 {@link #configure} 重设。 */
    private static volatile Set<String> watch = parse(System.getProperty(PROPERTY, ""));

    private static final AtomicLong TICK = new AtomicLong();

    private static final AtomicLong SEQ = new AtomicLong();

    /** 行事件默认出口（INFO 日志）；测试注入收集器后须恢复此常量。 */
    static final Consumer<String> LOG_SINK = LOG::info;

    /** 行事件出口（整行已按类文档格式拼好）；包内测试注入点，不进公开 API。 */
    static volatile Consumer<String> sink = LOG_SINK;

    /** 服务端 tick 计数入口（{@code ServerTickEvent.Pre} 里调用；未监视任何通道时不推进）。/ Server tick entry (called from the ServerTickEvent.Pre hook; does not advance while nothing is watched). */
    public static void tick() {
        if (!watch.isEmpty()) TICK.incrementAndGet();
    }

    /** {@link SignalBus#put} 入口调用（写前记行）。/ Called at the {@link SignalBus#put} entry (before the write). */
    public static void onPut(String channel, float value) {
        if (!watch.isEmpty() && watch.contains(channel)) {
            sink.accept(String.format(Locale.ROOT, "t=%d seq=%d PUT %s=%s",
                TICK.get(), SEQ.incrementAndGet(), channel, value));
        }
    }

    /** {@link SignalBus#get} 出口调用（带上返回值记行）。/ Called at the {@link SignalBus#get} exit (logged with the returned value). */
    public static void onGet(String channel, float value) {
        if (!watch.isEmpty() && watch.contains(channel)) {
            sink.accept(String.format(Locale.ROOT, "t=%d seq=%d GET %s->%s",
                TICK.get(), SEQ.incrementAndGet(), channel, value));
        }
    }

    /** 仅测试用：以与 {@code csc.traceChannels} 相同的解析重设监视表。/ Test-only: resets the watch set with the same parsing as the property. */
    static void configure(String csv) {
        watch = parse(csv);
    }

    private static Set<String> parse(String csv) {
        return Arrays.stream(csv.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toUnmodifiableSet());
    }
}
