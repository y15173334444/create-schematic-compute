package io.github.y15173334444.create_schematic_compute.network;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * <h2>C→S 包服务端限流设施（per-player + per-packet-type 令牌桶）</h2>
 *
 * <p><b>威胁模型（§4.4 已核实）</b>：本模组的全部 C→S 自定义包在服务端只做距离校验，
 * 原版对自定义负载零限流——被改过的客户端可以以任意速率灌包，每个包都在服务端主线程
 * 落地执行（图编辑 op 还会被广播放大给全部编辑者）。本类是 §4.4 分层缓解第 1 层：
 * 在包分发处（{@link AllPackets} 注册面）按「玩家 × 包类型」放行 / 拒绝 / 断开。</p>
 *
 * <p><b>语义照原版</b>：令牌桶<b>允许突发</b>（桶容量 = 按包类型真实使用形态定的突发预算）；
 * 桶耗尽后<b>拒绝</b>（丢弃该包，不排队不重试——主线程不值得为洪水排队）；<b>持续</b>超限
 * 则<b>断开</b>连接。注意拒绝与断开的分工：桶耗尽的那一刻保护即已生效（后续包全部被丢弃，
 * 不再进 handler），断开是对持续灌包者的逐出手段，不是保护本身。</p>
 *
 * <p><b>「持续」的判据——脉冲计 strikes（时间衰减）</b>：每次拒绝累加 1 个 strike，但同一段
 * 100ms 时间片内至多累加 1 个（quantum 门）；连续 {@link #KICK_STRIKES} 个 strike（即约 1 秒的
 * 持续拒绝）→ 返回 {@link Verdict#DISCONNECT}。strike 随静默时间线性衰减
 * （{@link #STRIKE_DECAY_PER_SECOND}——一个 strike 静默半秒即愈合）。这个门是合法巨量突发的
 * 安全阀：多选 1024 节点拖拽松手会在一个 tick 内发出上千个 op，桶耗尽后同 tick 的全部拒绝只
 * 累加 1 个 strike——<b>单 tick 尖峰永远不会踢人</b>，只有跨时间片持续被拒绝（机器速率灌包）
 * 才会。衰减不用「放行即清零」：按回填速率匀速灌包的洪泛者每个回填令牌只能送出 1 包、其余
 * 全被拒——拒绝流不停，strike 照常累加，匀速 pacer 同样逃不掉「持续超限」的判定。</p>
 *
 * <p><b>与 MC 类解耦</b>：本类只依赖 JDK（纯 JUnit 可起——MC 的连接/玩家类在 headless
 * 下起不来）。时间源经构造器注入（生产用 {@link System#nanoTime}，测试注入假钟）；
 * 「限流判定」返回 {@link Verdict}，由调用方（{@code AllPackets} 的包装器）执行断开——
 * 判定与执行分离，headless 测试只测判定。</p>
 *
 * <hr>
 * <h2>Server-side rate limiting for C→S packets (per-player + per-packet-type token buckets)</h2>
 *
 * <p><b>Threat model (verified in spec §4.4)</b>: every C→S custom packet in this mod gets
 * only a distance check server-side, and vanilla rate-limits nothing on custom payloads —
 * a modified client can flood packets at any rate, each landing on the server main thread
 * (edit ops are additionally broadcast to every editor). This class is layer 1 of the §4.4
 * layered mitigation: allow / reject / disconnect per (player × packet type) at the dispatch
 * face ({@link AllPackets} registrations).</p>
 *
 * <p><b>Vanilla semantics</b>: the token bucket <b>allows bursts</b> (capacity = a per-type
 * burst budget sized to the type's real usage shape); an exhausted bucket <b>rejects</b>
 * (the packet is dropped, never queued or retried — the main thread must not queue floods);
 * <b>sustained</b> over-limit <b>disconnects</b>. Note the division of labour: protection
 * engages the moment the bucket empties (further packets are dropped before any handler
 * runs); the disconnect is how a sustained flooder is evicted, not the protection itself.</p>
 *
 * <p><b>What "sustained" means — pulsed strikes with time decay</b>: each rejection adds
 * 1 strike, but at most 1 strike per 100 ms quantum; {@link #KICK_STRIKES} strikes
 * (≈1 s of continuous rejection) return {@link Verdict#DISCONNECT}. Strikes decay linearly
 * with quiet time ({@link #STRIKE_DECAY_PER_SECOND} — one strike heals after half a second
 * without rejection). The quantum gate is the safety valve for legitimate mega-bursts:
 * releasing a multi-select drag of up to 1024 nodes sends a thousand ops in one tick, and
 * every rejection inside that tick adds only 1 strike — <b>a one-tick spike never kicks</b>;
 * only rejections sustained across quanta (machine-rate flooding) do. The decay deliberately
 * replaces "reset on allow": a flooder paced at exactly the refill rate gets 1 packet per
 * refilled token through and rejects everything else — its rejection stream never stops, so
 * strikes keep accruing and the steady pacer cannot outrun the "sustained" judgement.</p>
 *
 * <p><b>Decoupled from MC classes</b>: this class depends only on the JDK (plain JUnit can
 * boot it — MC connection/player classes cannot start headless). The clock is injected via
 * the constructor (production uses {@link System#nanoTime}, tests inject a fake clock); the
 * "limit decision" returns a {@link Verdict} and the caller (the wrapper in {@code
 * AllPackets}) executes the disconnect — decision and execution are separate seams, and
 * headless tests exercise only the decision.</p>
 */
public class C2SPacketRateGuard {
    // 本类零日志：逐包日志违反 P2-11/K21 刷盘纪律，拒绝细节由调用方走 DEBUG，断开由调用方
    // 打一条 WARN——判定核心保持安静。
    // This class logs nothing: per-packet logging violates the P2-11/K21 log-flood discipline;
    // rejection detail rides DEBUG at the caller and the disconnect emits one WARN there —
    // the decision core stays silent.

    /** 断开判定阈值：累加（quantum 门 + 时间衰减下）的 strike 数达到即断开。10 × 100ms
     *  ≈ 1 秒的持续拒绝（净累加 8/s，约 1.1 秒）。合法用户在单 tick 尖峰后即停止发包——
     *  拒绝流停止 → strike 停涨并随静默衰减（半秒一个）→ 永远到不了 10。
     *  / Kick threshold: this many accumulated strikes (quantum-gated, time-decayed)
     *  disconnect. 10 × 100 ms ≈ 1 s of continuous rejection (net accrual 8/s, about
     *  1.1 s). A legitimate user stops sending after a one-tick spike — the rejection stream
     *  stops, strikes freeze and decay with quiet time (one per half second) — 10 is never
     *  reached. */
    public static final int KICK_STRIKES = 10;

    /** strike 累加的最小间隔（100ms 一个时间片）——见类注「脉冲计 strikes（时间衰减）」。
     *  Minimum interval between strike increments (one 100 ms quantum) — see the class doc
     *  "pulsed strikes with time decay". */
    static final long STRIKE_QUANTUM_NANOS = 100_000_000L;

    /** strike 衰减速率：每静默 1 秒愈合 2 个 strike（一个 strike 静默半秒即愈合）。持续
     *  拒绝下净累加速度 = 10/s − 2/s = 8/s → 达到 {@link #KICK_STRIKES} 需约 1 秒出头的
     *  连续拒绝。 / Strike decay rate: 2 strikes heal per quiet second (one strike heals in
     *  half a second). Under sustained rejection the net accrual is 10/s − 2/s = 8/s, so
     *  {@link #KICK_STRIKES} is reached after roughly one second of continuous rejection. */
    static final double STRIKE_DECAY_PER_SECOND = 2.0;

    /** 空闲桶回收阈值：一张 (player × type) 桶连续 10 分钟未被触碰即回收。桶数量上界 =
     *  玩家数 × C→S 类型数，泄漏成本可忽略；回收只是防止长期运行下离线玩家的残留。
     *  Idle-bucket retention: a (player × type) bucket untouched for 10 minutes is swept.
     *  The bucket count is bounded by players × C→S types so the leak cost is negligible;
     *  the sweep only clears leftovers from long-gone players. */
    static final long IDLE_RETENTION_NANOS = 600_000_000_000L;

    /** 周期性回收的触发间隔：check 顺路执行，别每次都扫。 / Sweep cadence: piggybacked on
     *  check, not on every call. */
    static final long SWEEP_INTERVAL_NANOS = 60_000_000_000L;

    // ── 逐包预算表：突发容量 = 该包类型的合法单点突发上界；回填速率 = 该类型的合法持续节奏。
    //    Per-type budget table: burst capacity = the type's legitimate one-shot burst ceiling;
    //    refill rate = the type's legitimate sustained cadence. ────────────────────────────

    /** 编辑 op：合法单点突发 = 多选拖拽松手（每被拖节点 1 op，图容量上限 1024 节点）+
     *  注释框挪开节点的追加 op → 1100。持续节奏 = 人类操作速度（远低于 8/s，回填 8/s 让
     *  连续快编辑永不触顶）。服务端每 op 有广播放大（发给全部编辑者），是表内放大系数最高
     *  的类型之一。 / Edit op: the legitimate one-shot burst = a multi-select drag release
     *  (1 op per dragged node, graph cap 1024 nodes) + the pushed-aside ops a comment resize
     *  appends → 1100. Sustained pace = human actions (far below 8/s; refill 8/s keeps
     *  continuous fast editing from ever tripping). The server amplifies each op to every
     *  editor — one of the highest amplification factors in this table. */
    public static final Policy EDIT_OP = new Policy(1100, 8.0);

    /** 座椅输入：合法节奏 = 乘坐期间每个客户端 tick 一包（20/s 持续），无放大。回填
     *  25/s（&gt; 20/s，桶恒满，合法玩家零拒绝）；突发 40 容纳网络抖动下 2 秒的补发。
     *  / Seat input: the legitimate cadence = one packet per client tick while seated
     *  (20/s sustained), no amplification. Refill 25/s (&gt; 20/s keeps the bucket full —
     *  zero rejections for a legitimate player); burst 40 absorbs 2 s of catch-up after a
     *  network hiccup. */
    public static final Policy SEAT_INPUT = new Policy(40, 25.0);

    /** 临场数据（光标 / 选区 / 软锁）：客户端节流 120ms ≈ 8.3/s，图名软锁翻转可强制
     *  立即发送。回填 10/s（&gt; 8.3/s，恒满）；突发 20 容纳强制发送叠加。每包中继给
     *  全部编辑者（放大）。 / Presence (cursor / selection / soft-lock): the client throttles
     *  at 120 ms ≈ 8.3/s, with forced immediate sends on graph-name focus flips. Refill
     *  10/s (&gt; 8.3/s, bucket stays full); burst 20 absorbs the forced sends. Relayed to
     *  every editor per packet (amplification). */
    public static final Policy PRESENCE = new Policy(20, 10.0);

    /** 编辑会话加入：服务端要下发全图快照（O(图大小) 读 + 编码），放大给请求者本人。
     *  合法节奏 = 开编辑器界面，一分钟几次；突发 6 容纳快速在几个 host 间切换，回填
     *  0.5/s 挡住反复 join 触发快照重读。 / Edit-session join: the server sends a
     *  whole-graph snapshot (O(graph) read + encode), amplified to the requester.
     *  Legitimate pace = editor screen opens, a few per minute; burst 6 absorbs quickly
     *  hopping between hosts, refill 0.5/s caps snapshot re-reads under join spam. */
    public static final Policy JOIN = new Policy(6, 0.5);

    /** 编辑会话离开：退出会话 + 通知其余编辑者，成本低；预算与 {@link #JOIN} 对称（同一
     *  开合节奏），防止离开广播刷屏。 / Edit-session leave: exits the session and notifies
     *  the other editors; cheap. Budget mirrors {@link #JOIN} (the same open/close cadence),
     *  capping leave-broadcast spam. */
    public static final Policy LEAVE = new Policy(6, 0.5);

    /** 保存/编译请求：服务端环检测 + 落盘 + 全量同步（O(图大小)，且同步发给全部编辑者——
     *  放大）。合法节奏 = 保存-检查-微调循环，约每 4-10 秒一次；突发 5 容纳一次迭代里的
     *  连续保存，回填 0.25/s（每 4 秒 1 次）覆盖密集保存循环。 / Save/compile request: the
     *  server runs a cycle check + persist + a full sync (O(graph), and the sync goes to
     *  every editor — amplification). Legitimate pace = the save-check-tweak loop, once per
     *  4-10 s; burst 5 absorbs consecutive saves in one iteration, refill 0.25/s (1 per 4 s)
     *  covers a dense save loop. */
    public static final Policy SAVE_REQUEST = new Policy(5, 0.25);

    /** 频段结构上传：每次频段增删/改名点击 1 包，服务端应用并广播给订阅者（放大）。
     *  合法突发 = 配一组频段的连续点击（个位数到十几个）；突发 20 / 回填 2/s。 / BUS band
     *  structure upload: one packet per band add/remove/rename click; the server applies and
     *  broadcasts to subscribers (amplification). Legitimate burst = a dozen-odd clicks
     *  while configuring one node's bands; burst 20 / refill 2/s. */
    public static final Policy BAND_UPLOAD = new Policy(20, 2.0);

    /** Blob 分片（图像 / 乐谱上传）：合法上传在单 tick 内连发 ≤ 64 片（编解码层的
     *  MAX_TOTAL_CHUNKS 钳制上界；256KB 乐谱 ≈ 9 片），突发 64 与钳制上界对齐——合法上传
     *  零拒绝；回填 4/s（15 秒后可再传一张）。重组后经 BlobDataSync 发编辑者（放大）。
     *  / Blob chunks (image / song upload): a legitimate upload fires ≤ 64 chunks in a
     *  single tick (the codec's MAX_TOTAL_CHUNKS ceiling; a 256 KB song ≈ 9 chunks), so
     *  burst 64 is ceiling-aligned — zero rejections for legitimate uploads; refill 4/s
     *  (the next upload fits 15 s later). Reassembly is broadcast to editors via
     *  BlobDataSync (amplification). */
    public static final Policy BLOB_CHUNK = new Policy(64, 4.0);

    /** 显示器设置：设置面板 Apply 按钮（显式提交契约，无滑杆连发）。突发 10 / 回填 1/s。
     *  / Monitor settings: the settings panel's Apply button (explicit-commit contract, no
     *  slider firehose). Burst 10 / refill 1/s. */
    public static final Policy MONITOR_SETTINGS = new Policy(10, 1.0);

    /** 雷达设置：设置面板保存按钮 + 工具栏快捷路径，均为点击驱动。突发 10 / 回填 1/s。
     *  / Radar settings: the settings panel's save button plus a toolbar shortcut — both
     *  click-driven. Burst 10 / refill 1/s. */
    public static final Policy RADAR_SETTINGS = new Policy(10, 1.0);

    /** 音响设置：设置界面按钮。突发 10 / 回填 1/s。 / Speaker settings: settings-screen
     *  button. Burst 10 / refill 1/s. */
    public static final Policy SPEAKER_SETTINGS = new Policy(10, 1.0);

    /** 功放设置（功放频谱显示器目标）：界面按钮，服务端 applySettings。突发 10 / 回填 1/s。
     *  / Amplifier settings (lid-spectrum monitor target): screen button → applySettings.
     *  Burst 10 / refill 1/s. */
    public static final Policy AMPLIFIER_SETTINGS = new Policy(10, 1.0);

    /** 雷达锁定：每目标一次点击切换。合法突发 = 连续点选几个目标；突发 15 / 回填 2/s。
     *  / Radar lock: one click toggles one target. Legitimate burst = locking a handful of
     *  targets in a row; burst 15 / refill 2/s. */
    public static final Policy RADAR_LOCK = new Policy(15, 2.0);

    /** Sable 子级扫描：服务端触发子级 BE 全量遍历（本表内单包成本最高的类型之一；半径
     *  已被 P1-3 钳制，但反复触发仍是遍历放大）。合法节奏 = 飞行途中几秒扫一次；突发 3
     *  / 回填 0.5/s（每 2 秒 1 次）。 / Sable sub-level scan: the server triggers a full
     *  sub-level BE traversal (one of the costliest single packets in this table; the radius
     *  is P1-3-clamped, but repeated triggers still amplify traversals). Legitimate pace =
     *  a scan every few seconds while flying; burst 3 / refill 0.5/s (1 per 2 s). */
    public static final Policy SCAN_SABLE = new Policy(3, 0.5);

    /** 预算调整（K6，服务端 OP 裁定）：编辑器设置页按钮，改完即同步。突发 10 / 回填 1/s。
     *  / Budget adjust (K6, server-ruled OP): editor settings-tab button, synced on change.
     *  Burst 10 / refill 1/s. */
    public static final Policy BUDGET_SET = new Policy(10, 1.0);

    /** 蓝图启停（各屏幕的运行开关）：点击驱动，服务端切运行态。突发 10 / 回填 1/s。
     *  / Blueprint toggle (the run switch on every screen): click-driven, flips the BE's
     *  running state. Burst 10 / refill 1/s. */
    public static final Policy BLUEPRINT_TOGGLE = new Policy(10, 1.0);

    /** 单 (player × type) 桶的预算：突发容量 + 令牌回填速率。 / Budget for one
     *  (player × type) bucket: burst capacity + token refill rate. */
    public record Policy(int burstTokens, double refillPerSecond) { }

    /** 限流判定结果。「限流判定」与「断开执行」的接缝：本枚举只是裁决，断开由调用方
     *  （MC 侧包装器）执行。 / The rate-limit verdict. This enum is the seam between the
     *  "limit decision" and "disconnect execution": it only rules, and the caller (the MC-side
     *  wrapper) executes. */
    public enum Verdict {
        /** 放行：消耗 1 令牌，包照常进 handler。 / Allowed: 1 token consumed, the packet
         *  proceeds to its handler. */
        ALLOWED,
        /** 拒绝：桶耗尽，丢弃该包（不进 handler、不排队不重试）。 / Rejected: the bucket
         *  is empty; the packet is dropped (no handler, no queue, no retry). */
        REJECTED,
        /** 断开：持续超限（strike 达 {@link #KICK_STRIKES}）——调用方应立即断开该连接。
         *  / Disconnect: sustained over-limit (strikes reached {@link #KICK_STRIKES}) —
         *  the caller should drop the connection. */
        DISCONNECT
    }

    /** 服务端单例（生产面）：真实时钟。类无 MC 依赖，headless 下加载也安全——测试用自己
     *  new 的实例，不碰这个单例。 / The production singleton: real clock. The class carries
     *  no MC dependency and loads safely headless — tests build their own instances and leave
     *  this one alone. */
    public static final C2SPacketRateGuard SERVER = new C2SPacketRateGuard(System::nanoTime);

    /** 可注入时间源：生产用 {@link System#nanoTime}，headless 测试注入假钟做确定性回填
     *  / strike 断言。 / Injectable clock: production uses {@link System#nanoTime}; headless
     *  tests inject a fake clock for deterministic refill / strike assertions. */
    private final LongSupplier clockNanos;

    /** 玩家 → (包类型 → 桶)。玩家键取服务端权威 UUID 字符串（调用方从
     *  {@code ctx.player().getUUID()} 取，不信任包载荷）；包类型键取 TYPE id 的 path。
     *  Player → (packet type → bucket). The player key is the server-authoritative UUID
     *  string (the caller derives it from {@code ctx.player().getUUID()}, never from the
     *  payload); the type key is the TYPE id's path. */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Bucket>> players = new ConcurrentHashMap<>();

    /** 上次顺路回收的时钟戳（构造时以注入时钟播种——避免与任意起点的 nanoTime 相减
     *  溢出）。 / Clock stamp of the last piggybacked sweep (seeded from the injected clock
     *  at construction — subtraction against an arbitrary-origin nanoTime must not
     *  overflow). */
    private final AtomicLong lastSweepNanos;

    /** @param clockNanos 纳秒时钟（单调）。 / monotonic nanosecond clock. */
    public C2SPacketRateGuard(LongSupplier clockNanos) {
        this.clockNanos = clockNanos;
        this.lastSweepNanos = new AtomicLong(clockNanos.getAsLong());
    }

    /**
     * 限流判定入口：取（或建）该 (player × type) 桶，回填后尝试取 1 令牌。
     * <p>The rate-limit entry point: fetch (or create) the (player × type) bucket, refill
     * it, and try to take 1 token.</p>
     *
     * @param playerKey 服务端权威玩家键（UUID 字符串）/ server-authoritative player key (UUID string)
     * @param typeKey   包类型键（TYPE id 的 path）/ packet type key (the TYPE id's path)
     * @param policy    该类型的预算 / this type's budget
     * @return 放行 / 拒绝 / 断开（语义见 {@link Verdict}） / the verdict (semantics in {@link Verdict})
     */
    public Verdict check(String playerKey, String typeKey, Policy policy) {
        long now = clockNanos.getAsLong();
        sweepIfDue(now);
        ConcurrentHashMap<String, Bucket> types =
            players.computeIfAbsent(playerKey, k -> new ConcurrentHashMap<>());
        Bucket bucket = types.computeIfAbsent(typeKey, k -> new Bucket(now, policy));
        return bucket.tryAcquire(now, policy);
    }

    /** 测试与运维观测面：当前活桶总数（玩家 × 类型展平）。 / Test/ops view: the total
     *  number of live buckets (players × types, flattened). */
    public int bucketCount() {
        int n = 0;
        for (ConcurrentHashMap<String, Bucket> types : players.values()) n += types.size();
        return n;
    }

    /** 测试观测面：当前玩家条目数。 / Test view: the number of player entries. */
    public int playerCount() { return players.size(); }

    /**
     * 回收空闲桶：上次触碰距今超过 {@link #IDLE_RETENTION_NANOS} 的 (player × type) 桶
     * 被丢弃；空玩家条目一并移除。幂等，可在任意线程调用。
     * <p>Sweeps idle buckets: any (player × type) bucket untouched for
     * {@link #IDLE_RETENTION_NANOS} is dropped; emptied player entries go too. Idempotent
     * and callable from any thread.</p>
     *
     * @param now 当前纳秒时钟 / the current clock in nanoseconds
     * @return 本次回收的桶数 / the number of buckets swept
     */
    public int sweepIdle(long now) {
        int swept = 0;
        Iterator<Map.Entry<String, ConcurrentHashMap<String, Bucket>>> pit = players.entrySet().iterator();
        while (pit.hasNext()) {
            Map.Entry<String, ConcurrentHashMap<String, Bucket>> pe = pit.next();
            ConcurrentHashMap<String, Bucket> types = pe.getValue();
            Iterator<Map.Entry<String, Bucket>> tit = types.entrySet().iterator();
            while (tit.hasNext()) {
                Map.Entry<String, Bucket> te = tit.next();
                if (now - te.getValue().lastTouchNanos >= IDLE_RETENTION_NANOS) {
                    tit.remove();
                    swept++;
                }
            }
            if (types.isEmpty()) pit.remove();
        }
        return swept;
    }

    /** check 顺路回收：距上次回收 ≥ {@link #SWEEP_INTERVAL_NANOS} 才真正扫一遍——一次
     *  CAS 保证并发下只有一条线程扫。 / Piggybacked sweep: actually sweeps only once per
     *  {@link #SWEEP_INTERVAL_NANOS}; one CAS ensures a single sweeper under concurrency. */
    private void sweepIfDue(long now) {
        long last = lastSweepNanos.get();
        if (now - last < SWEEP_INTERVAL_NANOS) return;
        if (!lastSweepNanos.compareAndSet(last, now)) return;
        sweepIdle(now);
    }

    /** 单桶状态：令牌数 + 上次回填戳 + strike 计数。check 在服务端主线程运行（NeoForge
     *  默认 HandlerThread.MAIN），但作为可复用工具仍按线程安全写——每桶自己的监视器锁，
     *  不同 (player × type) 无竞争。 / One bucket's state: tokens + last refill stamp +
     *  strike count. check runs on the server main thread (NeoForge's default
     *  HandlerThread.MAIN), but as a reusable tool it stays thread-safe — each bucket's own
     *  monitor; different (player × type) buckets never contend. */
    private static final class Bucket {
        /** 满桶出生：初始令牌 = 突发容量（新玩家新会话的第一笔突发必须放行）。
         *  / Born full: the initial tokens are the burst capacity (a new player's first
         *  burst must pass). */
        double tokens;
        long lastRefillNanos;
        long lastTouchNanos;
        double strikes;
        /** strike 时间片门：距此戳不足 {@link C2SPacketRateGuard#STRIKE_QUANTUM_NANOS} 的
         *  拒绝不累加 strike。以桶的出生时刻播种（见构造器）——首个拒绝最早也要等过一个
         *  时间片才累加，减法不溢出。 / The strike quantum gate: rejections within
         *  {@link C2SPacketRateGuard#STRIKE_QUANTUM_NANOS} of this stamp add no strike.
         *  Seeded at the bucket's birth instant (see the constructor) — the first strike can
         *  only land one full quantum after creation, and the subtraction cannot overflow. */
        long lastStrikeNanos;

        Bucket(long now, Policy policy) {
            this.tokens = policy.burstTokens();
            this.lastRefillNanos = now;
            this.lastTouchNanos = now;
            this.lastStrikeNanos = now;
        }

        /** 回填 + 取令牌 + strike 记账（桶监视器内执行）。 / Refill + token take + strike
         *  accounting (under the bucket monitor). */
        synchronized Verdict tryAcquire(long now, Policy policy) {
            lastTouchNanos = now;
            // 回填：按流逝时间线性补令牌，钳到突发容量。 / Refill: top up linearly by
            // elapsed time, clamped to the burst capacity.
            long elapsed = Math.max(0, now - lastRefillNanos);
            if (elapsed > 0) {
                tokens = Math.min(policy.burstTokens(), tokens + (elapsed / 1_000_000_000.0) * policy.refillPerSecond());
                lastRefillNanos = now;
            }
            if (tokens >= 1.0) {
                tokens -= 1.0;
                // 放行不动 strikes——见类注：按回填速率匀速灌包的 pacer 每令牌只送出 1 包、
                // 其余全拒，拒绝流不停则 strike 照常累加。
                // An allowed packet leaves strikes alone — see the class doc: a pacer at the
                // refill rate sends 1 packet per refilled token and rejects everything else;
                // as long as the rejection stream never stops, strikes keep accruing.
                return Verdict.ALLOWED;
            }
            // 拒绝 + strike 记账：同一时间片内只累加一次——单 tick 巨量突发（多选拖拽）
            // 只值 1 个 strike，永远踢不到合法用户。累加前先按静默时长衰减（2/s）。
            // / Reject + strike accounting: at most one increment per quantum — a one-tick
            // mega-burst (a multi-select drag) is worth 1 strike and can never kick a
            // legitimate user. Decay by quiet time (2/s) applies before the increment.
            long sinceStrike = now - lastStrikeNanos;
            if (sinceStrike >= STRIKE_QUANTUM_NANOS) {
                // ceil 让连续时间片内的净累加恰好每片 +1（每片衰减 0.2 &lt; 1 被 ceil 吸收，
                // 持续拒绝 → 精确 10 片 ≈ 1 秒断开）；静默足够长时衰减到 0 再 +1。
                // ceil makes the net accrual exactly +1 per quantum under continuous
                // rejection (the per-quantum decay of 0.2 &lt; 1 is absorbed, so a sustained
                // flood disconnects after exactly 10 quanta ≈ 1 s); a long quiet decays to
                // 0 before the +1.
                double healed = Math.max(0, strikes - (sinceStrike / 1_000_000_000.0) * STRIKE_DECAY_PER_SECOND);
                strikes = Math.ceil(healed) + 1;
                lastStrikeNanos = now;
                if (strikes >= KICK_STRIKES) {
                    // 保持计数：连接即将被调用方断开，残留状态由空闲回收清走。
                    // Keep the count: the caller disconnects the connection; leftovers are
                    // cleared by the idle sweep.
                    return Verdict.DISCONNECT;
                }
            }
            return Verdict.REJECTED;
        }
    }
}
