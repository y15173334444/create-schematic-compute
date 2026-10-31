package io.github.y15173334444.create_schematic_compute.graph;

import net.minecraft.core.BlockPos;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 求值上下文：{@link GraphEvaluator} 的求值状态即本类的字段（扁平数据载体，无继承体系）。
 * 分派化施工（eval() 按 {@link NodeCategory} 分派）的共享上下文——分类处理器是函数包，
 * 接收本类读写状态；字段域见《eval() 按 NodeCategory 分派化 · 决策文档》§四。
 * <p>Evaluation context: the evaluator's state lives as this class's fields (a flat data
 * carrier, no inheritance hierarchy). The shared context of the category-dispatch
 * refactor (eval() dispatches on {@link NodeCategory}) — category handlers are function
 * packs that read/write this state; field domains see the decision doc §四.</p>
 *
 * <p><b>扁平口径：</b>只做字段搬家与门面写穿（阶段 0），行为零变更；字段按域分组：
 * 图与输出表、子图机械、运行时、各端口、每调用槽、每节点槽。
 * <b>Flat discipline:</b> stage 0 only relocates fields and writes through the
 * {@link GraphEvaluator} facade — zero behavior change; fields are grouped by domain:
 * graph &amp; output tables, sub-graph machinery, runtime, ports, per-call slots,
 * per-node slots.</p>
 */
public final class EvalContext {

    // ── 图与输出表 / Graph & output tables ──
    /** 被求值的图。 / the graph under evaluation. */
    public final NodeGraph graph;
    /** 输出表：nodeId → 各引脚值（每 tick 重建）。 / output table: nodeId → per-pin values
     *  (rebuilt each tick). */
    public final Map<Integer, float[]> outputs = new HashMap<>();
    /** 音频输出引用表：{@code pack(nodeId, 输出引脚)} → {@link AudioRef}（类型化音源，非浮点）。
     *  每 tick 重建；音频连线经 {@link NodeGraph#getAudioInputRef} 解析到上游引用。
     *  MUSIC 产 AudioRef → AMP 改增益 → CHANNEL 按声像拆声道 → sink（AUDIO_OUT/SPEAKER_PLAY）消费。
     *  拓扑序保证源在汇前。 Typed audio-output refs keyed by (nodeId, output pin). */
    public final Map<Long, AudioRef> audioRefs = new HashMap<>();
    /** DEBUG_SIGNAL_GEN 的内部时间状态（nodeId → 归一化时间 0~1）。
     *  Internal time state for DEBUG_SIGNAL_GEN (nodeId → normalized time 0~1). */
    public final Map<Integer, Float> debugTime = new HashMap<>();

    // 缓存子图 evaluator 和运行时状态，供 ENCAPSULATION 节点使用
    // 键值：封装节点 ID（拥有该 subGraph 的节点）
    // Cache sub-graph evaluators and runtime state for ENCAPSULATION nodes
    // Key: encapsulation node ID (node that owns the subGraph)
    /** 缓存子图 evaluator，键 = 封装节点 ID（拥有该 subGraph 的节点）。
     *  Cached sub-graph evaluators, key = encapsulation node ID. */
    public final Map<Integer, GraphEvaluator> subEvaluators = new HashMap<>();
    /** 各封装节点上次已知的 {@code subGraph.graphGeneration}（陈旧检测）。
     *  last known {@code subGraph.graphGeneration} per encapId (staleness detection). */
    public final Map<Integer, Integer> subGraphGenerations = new HashMap<>();
    /** 子图时序三表（以封装节点 ID 为键）/ sub-graph timing tables, keyed by encap node ID. */
    public final Map<Integer, Map<Integer, ArrayDeque<Float>>> subDelayQueues = new HashMap<>();
    public final Map<Integer, Map<Integer, Boolean>> subFlipflopStates = new HashMap<>();
    public final Map<Integer, Map<Integer, Integer>> subPulseTimers = new HashMap<>();

    // ── 运行时 / Runtime ──
    /** 外部 RuntimeState 引用 — 在首次 evaluate() 前设置以恢复子图状态。
     *  External RuntimeState reference — set before first evaluate() to restore sub-graph state. */
    public RuntimeState runtimeState;
    /** 完成脉冲载体：指令完成时由宿主置为该指令的入队节点 ID，下一 tick 对应节点
     *  o[0] 输出一帧 1，随后宿主清空。瞬态、不持久化。
     *  Done-pulse carrier: set by the host when a command finishes; next tick the
     *  matching node outputs a one-frame 1, then the host clears it. Transient. */
    public Integer completedNodeId;
    /** 本 tick 的 game time（宿主 BE 每 tick 注入）——音频频段读取的新鲜度门控基准：
     *  AudioBands 只认本刻/上一刻发布的引用，发布方停机/卸载后陈旧事件自愈失效。
     *  Current game time injected by the host BE each tick; freshness gate for AudioBands reads. */
    public long audioTickStamp = 0L;

    // ── 各端口（接口下沉，宿主注入）/ Ports (interface sinking, host-injected) ──
    /** 信号总线端口（接口下沉，宿主接 network.SignalBus.PORT）；未接线 = 频道恒缺席。
     *  Signal bus port (interface sinking; the host wires network.SignalBus.PORT);
     *  unwired = every channel reads as absent. */
    public SignalBusPort signalBus = new SignalBusPort() {
        @Override public float get(String channel) { return 0f; }
        @Override public void put(String channel, float value) {}
        @Override public void setPrivateAudio(String channel, boolean audio) {}
        @Override public void setAudioBands(String busName, java.util.Set<String> audioBandNames) {}
        @Override public void registerBands(String busName, java.util.List<String> bands) {}
        @Override public java.util.List<String> getBands(String busName) { return null; }
        @Override public java.util.Map<String, Float> channelMap(String channel) { return null; }
    };
    /** 音频频段端口（接口下沉，宿主接 network.AudioBands.PORT）；未接线 = 恒空音源。
     *  Audio band port (interface sinking; the host wires network.AudioBands.PORT);
     *  unwired = permanently the empty source. */
    public AudioBandsPort audioBands = new AudioBandsPort() {
        @Override public String bandKey(String channel, String band) { return channel + " " + band; }
        @Override public boolean publish(String bandKey, AudioRef ref, long gameTick, String owner) { return true; }
        @Override public AudioRef get(String consumer, String bandKey, long nowTick) { return AudioRef.EMPTY; }
    };
    /** ENCODER 节点的宿主视图（由托管齿轮箱 BE 在重建求值器后注入；null 时输出 0）。
     *  Host view for ENCODER nodes (injected by the hosting gearbox BE after evaluator
     *  rebuilds; outputs 0 when null). */
    public KineticEncoderView encoderView;
    /** STRESS / RPM 节点的宿主视图（动力仪表等动力宿主 BE 在重建求值器后注入；null 时输出 0）。
     *  Host view for STRESS / RPM nodes (injected by the kinetic gauge's host BE after
     *  evaluator rebuilds; outputs 0 when null). */
    public KineticNetworkView kineticNetworkView;
    /** 指令下沉引用（宿主齿轮箱在重建求值器后注入；MOVE/ROTATE/WAIT 上升沿入队）。
     *  Command sink (host gearbox injects after evaluator rebuilds). */
    public GearboxCommandSink commandSink;
    /** 播放下沉（SPEAKER_PLAY）：宿主音响 BE 注入；SPEAKER_PLAY 把音源交给宿主发声。
     *  Speaker playback sink injected by the hosting speaker BE. */
    public SpeakerSink speakerSink;
    /** MUSIC 传输状态表（nodeId → 传输），由宿主 BE 注入并经类型段 NBT 持久化。
     *  Per-MUSIC-node transport, injected + persisted by the host BE. */
    public Map<Integer, MusicTransport> audioTransports;
    /** 音频宿主方块坐标（AUDIO_OUT 频道 owner = 坐标 + 节点 id）。
     *  Host block position for AUDIO_OUT channel ownership. */
    public BlockPos audioHostPos;
    /** 雷达宿主坐标（TARGET_OUT 查询的坐标空间）。 / radar host position (TARGET_OUT's
     *  coordinate space). */
    public BlockPos radarPos;
    /** 自定义节点（公开 API 波 1）的宿主世界与坐标（api.CustomNodeContext 的 level/hostPos
     *  来源；宿主在重建求值器时注入，未注入/单测 = null —— 提供方必须空检）。
     *  The custom-node host level and position (the source of api.CustomNodeContext's
     *  level/hostPos; injected by hosts at evaluator rebuild, null when unwired/in tests —
     *  providers must null-check). */
    public net.minecraft.server.level.ServerLevel customLevel;
    public BlockPos customHostPos;
    /** 雷达目标查询（接口下沉，宿主雷达注入 radar.TargetAssignment::getTarget）；
     *  未接线 = 恒无目标。 / radar target lookup (interface sinking; the hosting radar
     *  wires radar.TargetAssignment::getTarget); unwired = never a target. */
    public TargetLookup targetLookup = (pos, id) -> null;

    // ── 每调用槽 / Per-call slots ──
    /** 本次求值调用的外部输入、PID 状态与步长。 / this evaluation call's external inputs,
     *  PID state and time step. */
    public List<GraphEvaluator.InputSource> inputs;
    public Map<Integer, Float> pidState;
    public float dt;
    /** 控制座椅输入状态。 / control seat input state. */
    public GraphEvaluator.SeatInputState seat;
    /** 本次调用的时序三表（evalExt 档）。 / this call's timing tables (evalExt path). */
    public Map<Integer, ArrayDeque<Float>> delayQueues;
    public Map<Integer, Boolean> flipflopStates;
    public Map<Integer, Integer> pulseTimers;

    // ── 每节点槽 / Per-node slots ──
    /** 当前求值节点与其输出数组。 / the node currently being evaluated and its output array. */
    public GraphNode node;
    public float[] o;

    /** 构造求值上下文（图与输出表/子图机械表就地初始化，端口取默认 no-op）。
     *  Construct an evaluation context (graph & output/sub-graph tables initialized in
     *  place; ports take their default no-op impls). */
    public EvalContext(NodeGraph graph) {
        this.graph = graph;
    }

    /** 端口族继承（封装子求值器注入清单的收敛点，决策 §四）：复制端口族 + radarPos /
     *  targetLookup / audioTickStamp。setter 门面的空值守卫在这里不重复——继承是原样
     *  复制（含 null），守卫语义仍归 setter。
     *  Port-family inheritance (the convergence point for the encapsulation sub-evaluator
     *  injection list, decision §四): copies the port family plus radarPos / targetLookup /
     *  audioTickStamp. The setters' null guards are not repeated here — inheritance copies
     *  verbatim (null included); guard semantics stay with the setters.
     *
     * @param src 源上下文（通常为外层/顶层求值器的 ctx）/ the source context (usually the
     *            outer/top-level evaluator's ctx)
     */
    public void inheritPortsFrom(EvalContext src) {
        this.signalBus = src.signalBus;
        this.audioBands = src.audioBands;
        this.encoderView = src.encoderView;
        this.kineticNetworkView = src.kineticNetworkView;
        this.commandSink = src.commandSink;
        this.speakerSink = src.speakerSink;
        this.audioTransports = src.audioTransports;
        this.audioHostPos = src.audioHostPos;
        this.radarPos = src.radarPos;
        this.targetLookup = src.targetLookup;
        this.audioTickStamp = src.audioTickStamp;
        this.customLevel = src.customLevel;
        this.customHostPos = src.customHostPos;
    }

    /** 音频频段消费者身份（宿主坐标 + 节点 id）：AudioBands 的 exactly-once 游标按它去重
     *  （跨 BE tick 顺序翻转时同批发布防重播）。
     *  Audio-band consumer identity (host position + node id): AudioBands' exactly-once
     *  cursor dedups by it (prevents same-batch replays across BE tick-order flips). */
    public String consumerKey(GraphNode node) {
        return (audioHostPos != null ? audioHostPos.asLong() : 0L) + "#" + node.id;
    }

    /** 音频引用表键：nodeId << 16 | pin。 / audio-ref table key: nodeId << 16 | pin. */
    public static long audioKey(int nodeId, int pin) { return ((long) nodeId << 16) | (pin & 0xFFFF); }

    /** spread 输入变更检测:每引脚 1e-3 容差(决策 §五)。 / Spread input-change detection:
     *  1e-3 tolerance per pin (decisions §五). */
    public static boolean inputsUnchanged(float[] frozen, float[] current) {
        if (frozen == null || frozen.length != current.length) return false;
        for (int i = 0; i < frozen.length; i++) {
            if (Math.abs(frozen[i] - current[i]) > 1e-3f) return false;
        }
        return true;
    }
}
