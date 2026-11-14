package io.github.y15173334444.create_schematic_compute.graph;

import java.util.Map;

/**
 * <h2>§5.3 被切边的运行面（每 tick 计划 + 上一 tick 末快照 + 读侧端口包装）——纯 JDK</h2>
 *
 * <p>被切边的语义（§5.3）：读侧改读<b>上一 tick 末提交值</b>——求值遍结束（全部求值 +
 * broadcast 完）对被监视通道做 prev 快照，被切边的读钩子改读快照。本类是这套语义的
 * <b>tick 态面</b>（与 {@link TopoStats} 第 4 节的采集 tick 态同款管理形态）：</p>
 * <ul>
 * <li><b>计划面</b>（{@link #beginPass}）：求值遍起点安装本 tick 的 {@link CutEdgePlanner.CutPlan}
 *     ——读者宿主 → 被切通道键集。快照面<b>不动</b>（还是上一 tick 遍末捕获的）。</li>
 * <li><b>快照面</b>（{@link #installSnapshot}）：求值遍终点整体换发——通道键（
 *     {@code SignalBus.key()} 前缀形态，与 epoch/采集同一键空间，严禁复刻前缀格式）→
 *     双载体采样（PRIVATE 浮点 + BUS 频段映射副本）。无上一 tick 提交值（首 tick /
 *     通道缺席）= 读缺省 0/缺席，与活跃读的缺席语义一致。</li>
 * <li><b>读侧路由</b>（{@link #wrap}）：被切读者的求值端口经 {@link SnapshotPort} 包装——
 *     {@code get}/{@code channelMap} 命中「当前读者 × 被切通道」时改读快照，其余一律
 *     透传。读者身份复用 {@link TopoStats#currentEvalHost()} 求值宿主上下文（ENCAP 子求值
 *     器经同一静态天然延续）。音频/向量载体不路由（不在 §3.1 浮点载体口径内，快照面按
 *     浮点载体捕获）。无切边时 {@link #wrap} 原样返回委托——零包装零开销。</li>
 * <li><b>口径 A 豁免</b>（{@link #isSnapshotServed}）：被切读是<b>设计内跨 tick</b>，
 *     不是陈旧读——{@link TopoStats#noteChannelRead} 对其豁免 ①/② 记账，采集口径只量
 *     「计划外陈旧」。</li>
 * </ul>
 *
 * <p>服务端单线程约束下两面都是普通引用换发（volatile 保控制台/展示读侧可见性自洽）。</p>
 *
 * <hr>
 * <h2>The runtime face of §5.3's cut edges (per-tick plan + last-tick snapshot + the
 * read-side port wrapper) — pure JDK.</h2>
 *
 * <p>The cut-edge semantics (§5.3): the read side reads <b>last tick's committed value</b> —
 * when the evaluation pass ends (all hosts evaluated + broadcast), a prev snapshot is taken
 * of the watched channels, and a cut edge's read hook reads the snapshot. This class is that
 * semantics' <b>tick-state face</b> (managed like {@link TopoStats} section 4's collection
 * tick state):</p>
 * <ul>
 * <li><b>The plan face</b> ({@link #beginPass}): the pass head installs this tick's
 *     {@link CutEdgePlanner.CutPlan} — reader host → cut channel-key set. The snapshot face
 *     is <b>untouched</b> (it still carries what the previous pass's tail captured).</li>
 * <li><b>The snapshot face</b> ({@link #installSnapshot}): swapped wholesale at the pass
 *     tail — channel key (the {@code SignalBus.key()} prefixed form, the same keyspace as
 *     epochs/collection; the prefix format is never replicated) → the dual-carrier sample
 *     (the PRIVATE float + a BUS band-map copy). No last-tick committed value (the first
 *     tick / an absent channel) = read the default 0/absent — consistent with the live
 *     read's absent semantics.</li>
 * <li><b>The read-side routing</b> ({@link #wrap}): a cut reader's evaluation port rides the
 *     {@link SnapshotPort} wrapper — {@code get}/{@code channelMap} switch to the snapshot
 *     when the "current reader × cut channel" pair hits, everything else delegates
 *     untouched. The reader identity reuses {@link TopoStats#currentEvalHost()} (the
 *     evaluation-host context; ENCAP sub-evaluators continue through the same static). The
 *     audio/vector carriers are not routed (outside §3.1's float-carrier scope; the snapshot
 *     face captures on the float carrier). With no cuts {@link #wrap} returns the delegate
 *     as-is — zero wrapping, zero overhead.</li>
 * <li><b>The criterion-A exemption</b> ({@link #isSnapshotServed}): a cut read is
 *     <b>cross-tick by design</b>, not a stale read — {@link TopoStats#noteChannelRead}
 *     exempts it from ①/② accounting, so the collection metric only measures
 *     "unplanned staleness".</li>
 * </ul>
 *
 * <p>Under the single-server-thread constraint both faces are plain reference swaps
 * (volatile keeps the console/display readers' visibility honest).</p>
 */
public final class ChannelCuts {

    private ChannelCuts() {}

    /** 单通道快照采样（双载体，皆可 null = 该载体无提交值）。 / One channel's snapshot sample
     *  (dual carrier, either null = that carrier has no committed value). */
    public record CutSample(Float privateValue, Map<String, Float> busMap) {}

    /** 当前 tick 的切边计划（求值遍起点换发；无环图 = {@link CutEdgePlanner.CutPlan#EMPTY}）。
     *  The current tick's cut plan (swapped at the pass head; an acyclic graph =
     *  {@link CutEdgePlanner.CutPlan#EMPTY}). */
    private static volatile CutEdgePlanner.CutPlan plan = CutEdgePlanner.CutPlan.EMPTY;

    /** 上一 tick 遍末的通道快照（前缀键 → 双载体采样；遍终点整体换发）。
     *  The last pass-tail channel snapshot (prefixed key → dual-carrier sample; swapped
     *  wholesale at the pass tail). */
    private static volatile Map<String, CutSample> snapshot = Map.of();

    /** 求值遍起点：安装本 tick 计划（快照面不动——仍为上一 tick 遍末的）。测试面
     *  {@link #resetForTest} 之外，生产调用方 = {@code HostEvalPass.runPostPass}。
     *  Pass head: install this tick's plan (the snapshot face untouched — still last tick's).
     *  Besides {@link #resetForTest}, the production caller = {@code HostEvalPass.runPostPass}. */
    public static void beginPass(CutEdgePlanner.CutPlan p) {
        plan = p != null ? p : CutEdgePlanner.CutPlan.EMPTY;
    }

    /** 当前切边计划（展示面读侧：{@code ring}/{@code bus} 标记被切边）。
     *  The current cut plan (the display-face reader: {@code ring}/{@code bus} mark cut
     *  edges). */
    public static CutEdgePlanner.CutPlan currentPlan() { return plan; }

    /** 求值遍终点：整体换发上一遍末快照。生产调用方 = {@code HostEvalPass.runPostPass}
     *  （求值 + broadcast 全部完成后捕获）。
     *  Pass tail: swap the snapshot wholesale. The production caller =
     *  {@code HostEvalPass.runPostPass} (captures after all evaluation + broadcast). */
    public static void installSnapshot(Map<String, CutSample> s) {
        snapshot = s != null ? Map.copyOf(s) : Map.of();
    }

    /** 某读者宿主对某通道键的读是否被切（读侧路由与展示标记的同一判定）。
     *  Whether a reader host's read of a channel key is cut (one judgement shared by the
     *  read-side routing and the display markers). */
    public static boolean isReaderCut(TopoStats.HostId reader, String channelKey) {
        var set = plan.readerChannels().get(reader);
        return set != null && set.contains(channelKey);
    }

    /** 当前求值上下文的这次通道读是否被切边快照接手（{@link TopoStats#noteChannelRead} 的
     *  ①/② 豁免判定——设计内跨 tick 不入陈旧口径；无宿主上下文（客户端本地求值）恒否）。
     *  Whether the current evaluation context's channel read is served by the cut snapshot
     *  (the ①/② exemption judgement of {@link TopoStats#noteChannelRead} — cross-tick by
     *  design stays out of the staleness metric; no host context (client-local evaluation)
     *  always answers no). */
    public static boolean isSnapshotServed(String channelKey) {
        TopoStats.HostId host = TopoStats.currentEvalHost();
        return host != null && isReaderCut(host, channelKey);
    }

    /** 仅测试用：清空计划与快照两面。 / Test-only: clear both the plan and snapshot faces. */
    static void resetForTest() {
        plan = CutEdgePlanner.CutPlan.EMPTY;
        snapshot = Map.of();
    }

    /**
     * 包装求值端口：无切边原样返回（零开销快路）；有切边返回快照感知包装——命中
     * 「当前读者 × 被切通道」的 {@code get}/{@code channelMap} 改读快照，其余透传。
     * <p>Wrap an evaluation port: no cuts returns the delegate as-is (the zero-overhead fast
     * path); with cuts returns the snapshot-aware wrapper — {@code get}/{@code channelMap}
     * hits on "current reader × cut channel" read the snapshot, everything else delegates.</p>
     *
     * @param delegate 生产端口（{@code SignalBus.PORT}） / the production port
     *                 ({@code SignalBus.PORT})
     * @return 原端口或快照感知包装 / the original port or the snapshot-aware wrapper
     */
    public static SignalBusPort wrap(SignalBusPort delegate) {
        if (plan.isEmpty() || delegate == null) return delegate;
        return new SnapshotPort(delegate);
    }

    /** 快照感知端口：读侧两方法按「当前读者 × 被切通道」路由，其余全透传。
     *  The snapshot-aware port: the two read methods route on "current reader × cut
     *  channel", everything else delegates. */
    private static final class SnapshotPort implements SignalBusPort {
        private final SignalBusPort d;

        SnapshotPort(SignalBusPort d) { this.d = d; }

        /** 当前读者是否切了这个通道键。 / Whether the current reader has this channel key cut. */
        private boolean cutNow(String key) {
            TopoStats.HostId host = TopoStats.currentEvalHost();
            return host != null && isReaderCut(host, key);
        }

        @Override public float get(String channel) {
            String key = d.channelKey(channel);
            if (cutNow(key)) {
                CutSample s = snapshot.get(key);
                return s != null && s.privateValue() != null ? s.privateValue() : 0f;
            }
            return d.get(channel);
        }

        @Override public void put(String channel, float value) { d.put(channel, value); }

        @Override public Map<String, Float> channelMap(String channel) {
            String key = d.channelKey(channel);
            if (cutNow(key)) {
                CutSample s = snapshot.get(key);
                return s != null ? s.busMap() : null;
            }
            return d.channelMap(channel);
        }

        @Override public void setPrivateType(String channel, PinType type) { d.setPrivateType(channel, type); }
        @Override public void setBandTypes(String busName, Map<String, PinType> bandTypes) { d.setBandTypes(busName, bandTypes); }
        @Override public void registerBands(String busName, java.util.List<String> bands) { d.registerBands(busName, bands); }
        @Override public java.util.List<String> getBands(String busName) { return d.getBands(busName); }
        @Override public String channelKey(String channel) { return d.channelKey(channel); }
        @Override public void putVec3(String key, Vec3f value) { d.putVec3(key, value); }
        @Override public Vec3f getVec3(String key) { return d.getVec3(key); }
        // put 透传——写侧无快照路由：被切边只切「读」，写入仍实时落总线，快照在遍末
        // 统一捕获（上一 tick 末提交值 = 全部写完后的值，语义自洽）。
        // put delegates — the write side has no snapshot routing: a cut edge cuts the "read"
        // only, writes land live, and the snapshot captures uniformly at the pass tail (last
        // tick's committed value = the value after all writes — semantically self-consistent).
    }
}
