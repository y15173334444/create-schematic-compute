package io.github.y15173334444.create_schematic_compute.graph;

import net.minecraft.world.item.ItemStack;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 将 {@link NodeGraph} 编译为可执行形式，并在每个 tick 进行评估。
 * Compiles a {@link NodeGraph} into an executable form and evaluates it each tick.
 *
 * <p><b>仅服务端使用。</b>客户端通过 {@code ClientboundGraphEvalPacket} 接收评估结果
 * 并缓存为 {@link EvalSnapshot}。客户端代码绝对不应实例化此类 —— 请使用
 * {@code be.cachedEvalSnapshot} 读取评估输出以进行渲染。
 * <b>Server-side only.</b> The client receives evaluation results via
 * {@code ClientboundGraphEvalPacket} and caches them as {@link EvalSnapshot}.
 * Client code must never instantiate this class — use {@code be.cachedEvalSnapshot}
 * to read evaluation outputs for rendering.</p>
 */
public class GraphEvaluator {
    private static final Logger LOGGER = LoggerFactory.getLogger(GraphEvaluator.class);

    /** 求值状态（图与输出表、子图机械、运行时、各端口、每调用/每节点槽）住在
     *  {@link EvalContext}（决策文档 §四）——本类保留 setter/getter 门面，写穿 ctx。
     *  Eval state (graph &amp; output tables, sub-graph machinery, runtime, ports,
     *  per-call / per-node slots) lives in {@link EvalContext} (decision doc §四) —
     *  this class keeps the setter/getter facade and writes through ctx. */
    /** 求值上下文。包内可见（EncapEval 需要跨实例读写子求值器的 ctx——注入收敛
     *  inheritPortsFrom、子图输出/相位镜像、restoreSubState 挂接）；final 不变。
     *  Evaluation context. Package-visible (EncapEval needs cross-instance access to a
     *  sub-evaluator's ctx — the inheritPortsFrom injection convergence, sub-graph
     *  output/phase mirroring, restoreSubState attach); still final. */
    final EvalContext ctx;

    public void setRadarPos(net.minecraft.core.BlockPos pos) { this.ctx.radarPos = pos; }
    /** 雷达目标查询（接口下沉，宿主雷达注入 radar.TargetAssignment::getTarget）；
     *  未接线 = 恒无目标。 / radar target lookup (interface sinking; the hosting radar
     *  wires radar.TargetAssignment::getTarget); unwired = never a target. */
    public void setTargetLookup(TargetLookup lookup) { this.ctx.targetLookup = lookup != null ? lookup : (pos, id) -> null; }
    /** 信号总线端口（接口下沉，宿主接 network.SignalBus.PORT）；未接线 = 频道恒缺席。
     *  Signal bus port (interface sinking; the host wires network.SignalBus.PORT);
     *  unwired = every channel reads as absent. */
    public void setSignalBus(SignalBusPort port) { if (port != null) this.ctx.signalBus = port; }
    /** 音频频段端口（接口下沉，宿主接 network.AudioBands.PORT）；未接线 = 恒空音源。
     *  Audio band port (interface sinking; the host wires network.AudioBands.PORT);
     *  unwired = permanently the empty source. */
    public void setAudioBands(AudioBandsPort port) { if (port != null) this.ctx.audioBands = port; }
    /** ENCODER 节点的宿主视图（由托管齿轮箱 BE 在重建求值器后注入；null 时输出 0）。
     *  Host view for ENCODER nodes (injected by the hosting gearbox BE after evaluator
     *  rebuilds; outputs 0 when null). */
    public void setEncoderView(KineticEncoderView view) { this.ctx.encoderView = view; }
    /** STRESS / RPM 节点的宿主视图（动力仪表等动力宿主 BE 在重建求值器后注入；null 时输出 0）。
     *  Host view for STRESS / RPM nodes (injected by the kinetic gauge's host BE after
     *  evaluator rebuilds; outputs 0 when null). */
    public void setKineticNetworkView(KineticNetworkView view) { this.ctx.kineticNetworkView = view; }
    /** 指令下沉引用（宿主齿轮箱在重建求值器后注入；MOVE/ROTATE/WAIT 上升沿入队）。
     *  Command sink (host gearbox injects after evaluator rebuilds). */
    public void setCommandSink(GearboxCommandSink sink) { this.ctx.commandSink = sink; }
    /** 完成脉冲载体：指令完成时由宿主置为该指令的入队节点 ID，下一 tick 对应节点
     *  o[0] 输出一帧 1，随后宿主清空。瞬态、不持久化。
     *  Done-pulse carrier: set by the host when a command finishes; next tick the
     *  matching node outputs a one-frame 1, then the host clears it. Transient. */
    public void setCompletedNodeId(Integer nodeId) { this.ctx.completedNodeId = nodeId; }
    public void clearCompletedNodeId() { this.ctx.completedNodeId = null; }

    // ── 音频（功放/音响宿主）/ Audio (amplifier & speaker hosts) ──
    /** MUSIC 传输状态表（nodeId → 传输），由宿主 BE 注入并经类型段 NBT 持久化。
     *  Per-MUSIC-node transport, injected + persisted by the host BE. */
    public void setAudioTransports(java.util.Map<Integer, MusicTransport> map) { this.ctx.audioTransports = map; }
    /** 音频宿主方块坐标（AUDIO_OUT 频道 owner = 坐标 + 节点 id）。
     *  Host block position for AUDIO_OUT channel ownership. */
    public void setAudioHostPos(net.minecraft.core.BlockPos pos) { this.ctx.audioHostPos = pos; }
    /** 播放下沉（SPEAKER_PLAY）：宿主音响 BE 注入；SPEAKER_PLAY 把音源交给宿主发声。
     *  Speaker playback sink injected by the hosting speaker BE. */
    public void setSpeakerSink(SpeakerSink sink) { this.ctx.speakerSink = sink; }
    /** 本 tick 的 game time（宿主 BE 每 tick 注入）——音频频段读取的新鲜度门控基准：
     *  AudioBands 只认本刻/上一刻发布的引用，发布方停机/卸载后陈旧事件自愈失效。
     *  Current game time injected by the host BE each tick; freshness gate for AudioBands reads. */
    public void setAudioTickStamp(long tick) { this.ctx.audioTickStamp = tick; }
    // FORMULA script parsing is now unified through GraphNode.cachedScript.
    // The eval branch calls node.ensureScriptParsed() which checks sourceFormula
    // freshness (方案 A) — this guarantees the same ScriptParseResult is used for
    // both pin-index resolution (inputPinIndex/outputPinIndex) and evaluation,
    // eliminating the dual-cache inconsistency that caused ~20° angle errors.
    // FORMULA 脚本解析现已通过 GraphNode.cachedScript 统一。
    // eval 分支调用 node.ensureScriptParsed()，由 sourceFormula 检查新鲜度（方案 A）
    // — 保证引脚索引解析与求值使用同一 ScriptParseResult，消除双缓存不一致。

    // 子图机械（subEvaluators/subGraphGenerations/时序三表）、debugTime 与 runtimeState
    // 已迁入 EvalContext（决策文档 §四）；ENCAP 处的注入清单收敛点 inheritPortsFrom 同在。
    // Sub-graph machinery (subEvaluators / subGraphGenerations / timing tables), debugTime
    // and runtimeState moved into EvalContext (decision doc §四); ENCAP's injection-list
    // convergence point inheritPortsFrom lives there too.

    public GraphEvaluator(NodeGraph graph) { this.ctx = new EvalContext(graph); }

    /** 从之前保存的 RuntimeState 恢复子图状态。
     *  必须在构造后、首次 {@link #evaluate} 前调用一次。
     *  Restore sub-graph state from a previously-saved RuntimeState.
     *  Must be called once after construction and before the first {@link #evaluate}. */
    public void restoreSubState(RuntimeState rs) {
        this.ctx.runtimeState = rs;
        // 恢复主图 DEBUG_SIGNAL_GEN 相位 / restore main-graph DEBUG_SIGNAL_GEN phase
        if (!rs.debugTime.isEmpty()) this.ctx.debugTime.putAll(rs.debugTime);
        for (var entry : rs.subStates.entrySet()) {
            int encapId = entry.getKey();
            RuntimeState.SubState ss = entry.getValue();
            if (!ss.delayQueues.isEmpty()) {
                ctx.subDelayQueues.put(encapId, new HashMap<>(ss.delayQueues));
            }
            if (!ss.flipflopStates.isEmpty()) {
                ctx.subFlipflopStates.put(encapId, new HashMap<>(ss.flipflopStates));
            }
            if (!ss.pulseTimers.isEmpty()) {
                ctx.subPulseTimers.put(encapId, new HashMap<>(ss.pulseTimers));
            }
        }
    }

    /** 从保存的映射恢复 debugTime 相位（在运行时状态清除后使用）。
     *  Restore debugTime phase from a saved map (used after runtime state clear). */
    public void restoreDebugTimes(Map<Integer, Float> saved) {
        this.ctx.debugTime.putAll(saved);
    }

    /** 将当前 debugTime 相位写回 RuntimeState 以便 NBT 持久化。
     *  Write accumulated debugTime phase state back to RuntimeState for NBT persistence. */
    public void saveDebugTimes(RuntimeState rs) {
        rs.debugTime.clear();
        rs.debugTime.putAll(this.ctx.debugTime);
        for (var entry : ctx.subEvaluators.entrySet()) {
            int encapId = entry.getKey();
            var subEval = entry.getValue();
            var ss = rs.getOrCreateSubState(encapId);
            ss.debugTime.clear();
            ss.debugTime.putAll(subEval.ctx.debugTime);
        }
    }

    /** 控制座椅输入状态 / Control seat input state */
    public record SeatInputState(long keyBits, float mouseX, float mouseY, float yaw, float pitch,
        float worldYaw, float worldPitch,
        int mouseButtons, float gpadLX, float gpadLY, float gpadRX, float gpadRY, float gpadLT, float gpadRT, long gpadButtons,
        float blockYaw, float attitudeYaw, float attitudePitch, float attitudeRoll, float forwardYaw, float forwardPitch,
        float accelX, float accelY, float accelZ,
        float velX, float velY, float velZ,
        float worldX, float worldY, float worldZ) {

        /** 原始摇杆量 → 归一摇杆（-1..1）的缩放契约：备制方与消费方共用此值。
         *  满偏约 3°/tick (60°/s)，匹配旧版 dx*0.05 手感。
         *  Scaling contract from raw stick deltas to normalised stick (-1..1): the preparer
         *  (input handler) and the consumer (evaluator) share these; ~3°/tick at full
         *  deflection (~60°/s), matching the legacy dx*0.05 feel. */
        public static final float JOYSTICK_SCALE = 1.0f / 3.0f;
        /** 绝对位移模式的每 tick 累积系数（比增量缓和，避免过冲）。 / absolute-mode
         *  per-tick accumulation scale, gentler than incremental to avoid overshoot. */
        public static final float ABS_SCALE = 1.0f / 6.0f;

        public SeatInputState(long keyBits, float mouseX, float mouseY, float yaw, float pitch) {
            this(keyBits, mouseX, mouseY, yaw, pitch, yaw, pitch, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    public List<OutputResult> evaluate(List<InputSource> inputs, Map<Integer, Float> pidState, float dt) {
        return evaluate(inputs, pidState, dt, new SeatInputState(0, 0, 0, 0, 0),
            new HashMap<>(), new HashMap<>(), new HashMap<>());
    }

    public List<OutputResult> evaluate(List<InputSource> inputs, Map<Integer, Float> pidState, float dt, SeatInputState seat) {
        ctx.outputs.clear();
        ctx.audioRefs.clear();
        // 使用缓存的拓扑排序  /  Use cached topological order
        List<Integer> sorted = ctx.graph.getTopoOrder();
        for (int id : sorted) {
            GraphNode n = ctx.graph.findNode(id);
            if (n != null) {
                // 无状态路径也应用参数引脚覆盖（eval 不再自管），并立即恢复
                // The stateless path applies param-pin overrides too (eval no longer
                // self-manages them) and restores immediately.
                float[] op = n.params;
                applyParamPinOverrides(n);
                eval(n, inputs, pidState, dt, seat);
                n.params = op;
            }
        }
        List<OutputResult> res = new ArrayList<>();
        for (GraphNode n : ctx.graph.nodes) {
            if (n.type == NodeType.REDSTONE_OUT) {
                float v = ctx.graph.getInputValue(n.id, 0, ctx.outputs);
                res.add(new OutputResult(
                    n.itemParams.length > 0 ? n.itemParams[0] : ItemStack.EMPTY,
                    n.itemParams.length > 1 ? n.itemParams[1] : ItemStack.EMPTY,
                    Math.max(0, Math.min(15, Math.round(v)))));

            }
        }
        return res;
    }

    /** 获取当前输出映射表（供显示区域等外部查询输入值）。
     *  Get the current output map (for external queries of input values, e.g. display area). */
    public Map<Integer, float[]> getCurrentOutputs() { return ctx.outputs; }

    /** 创建当前评估输出的不可变快照（服务端→客户端同步）。
     *  合并主图输出 + 所有子图（ENCAPSULATION）输出和 debugTime，使客户端中的 DEBUG_PROBE /
     *  DEBUG_SIGNAL_GEN 能够在封装内部正常工作。
     *  Create an immutable snapshot of the current evaluation outputs (server → client sync).
     *  Merges main graph outputs + all sub-graph (ENCAPSULATION) outputs and debugTimes so that
     *  DEBUG_PROBE / DEBUG_SIGNAL_GEN works inside encapsulation on the client. */
    public EvalSnapshot captureSnapshot() {
        Map<Integer, Map<Integer, float[]>> subOutputs;
        Map<Integer, Map<Integer, Float>> subDebugTimes;
        if (ctx.subEvaluators.isEmpty()) {
            subOutputs = java.util.Collections.emptyMap();
            subDebugTimes = java.util.Collections.emptyMap();
        } else {
            subOutputs = new HashMap<>(ctx.subEvaluators.size());
            subDebugTimes = new HashMap<>(ctx.subEvaluators.size());
            for (var entry : ctx.subEvaluators.entrySet()) {
                int encapId = entry.getKey();
                GraphEvaluator subEval = entry.getValue();
                if (!subEval.ctx.outputs.isEmpty()) {
                    Map<Integer, float[]> copy = new HashMap<>(subEval.ctx.outputs.size());
                    for (var oe : subEval.ctx.outputs.entrySet()) {
                        copy.put(oe.getKey(), oe.getValue().clone());
                    }
                    subOutputs.put(encapId, copy);
                    subEval.ctx.outputs.clear(); // 清除以避免删除节点的残留 id 堆积 / clear to prevent stale deleted-node IDs
                }
                if (!subEval.ctx.debugTime.isEmpty()) {
                    subDebugTimes.put(encapId, new HashMap<>(subEval.ctx.debugTime));
                    // 注意：不清除 subEval.ctx.debugTime — 它是跨 tick 累积的相位状态，
                    // DEBUG_SIGNAL_GEN 依赖它推进频率。残留条目仅在被删除节点上泄漏一个 float。
                    // Note: do NOT clear subEval.ctx.debugTime — it is phase state that
                    // accumulates across ticks; DEBUG_SIGNAL_GEN depends on it to advance.
                }
            }
        }
        // 刀5:FORMULA spread 渲染态(无数值,进度条用)/ knife 5: spread render state (bar only, no value)
        Map<Integer, Float> spreads = null;
        for (GraphNode n : ctx.graph.nodes) {
            if (n.formulaSpreadProgress != 0f) {
                if (spreads == null) spreads = new HashMap<>();
                spreads.put(n.id, n.formulaSpreadProgress);
            }
        }
        return EvalSnapshot.capture(ctx.outputs, ctx.debugTime, subOutputs, subDebugTimes, spreads);
    }

    /** 读取任意节点某引脚的计算结果（供外部查询）。
     *  Read the computed result of any node's pin (for external queries). */
    public float getNodeOutput(int nodeId, int pinIndex) {
        float[] out = ctx.outputs.get(nodeId);
        return out != null && pinIndex < out.length ? out[pinIndex] : 0;
    }

    /** 读取某节点某输入引脚的值（用于延时节点入队）。
     *  Read the value at a node's input pin (used for delay node enqueue). */
    public float getNodeInput(int nodeId, int pinIndex) {
        return ctx.graph.getInputValue(nodeId, pinIndex, ctx.outputs);
    }

    /** 带扩展状态的评估（时序节点用）。
     *  Evaluate with extended state (for sequential/timing nodes). */
    public List<OutputResult> evaluate(List<InputSource> inputs, Map<Integer, Float> pidState, float dt,
                                        Map<Integer, java.util.ArrayDeque<Float>> delayQueues,
                                        Map<Integer, Boolean> flipflopStates,
                                        Map<Integer, Integer> pulseTimers) {
        return evaluate(inputs, pidState, dt, new SeatInputState(0, 0, 0, 0, 0), delayQueues, flipflopStates, pulseTimers);
    }

    public List<OutputResult> evaluate(List<InputSource> inputs, Map<Integer, Float> pidState, float dt,
                                        SeatInputState seat,
                                        Map<Integer, java.util.ArrayDeque<Float>> delayQueues,
                                        Map<Integer, Boolean> flipflopStates,
                                        Map<Integer, Integer> pulseTimers) {
        ctx.outputs.clear();
        ctx.audioRefs.clear();
        List<Integer> sorted = ctx.graph.getTopoOrder();
        for (int id : sorted) {
            GraphNode n = ctx.graph.findNode(id);
            if (n != null) evalExt(n, inputs, pidState, dt, seat, delayQueues, flipflopStates, pulseTimers);
        }
        List<OutputResult> res = new ArrayList<>();
        for (GraphNode n : ctx.graph.nodes) {
            if (n.type == NodeType.REDSTONE_OUT) {
                float v = ctx.graph.getInputValue(n.id, 0, ctx.outputs);
                res.add(new OutputResult(
                    n.itemParams.length > 0 ? n.itemParams[0] : ItemStack.EMPTY,
                    n.itemParams.length > 1 ? n.itemParams[1] : ItemStack.EMPTY,
                    Math.max(0, Math.min(15, Math.round(v)))));

            }
        }
        return res;
    }

    /** 通用参数引脚：连线值临时覆盖 node.params（eval 与 evalExt 共用）。
     *  Generic parameter pins: wired values temporarily override node.params
     *  (shared by eval and evalExt).
     *  <p>参数引脚排在功能引脚之后；FORMULA 的 warm 参数无引脚（编辑区按钮，刀5 决策），
     *  不参与连线覆盖。注意：覆盖通过替换 node.params 实现，断开连线后原默认值需
     *  重新编辑才会恢复（与旧 eval() 行为一致）。</p>
     *  <p>Param pins come after the functional inputs; FORMULA's warm param has no pin
     *  (edit-panel button, knife-5 decision) and never participates. Note: the override
     *  replaces node.params, so after un-wiring the original default returns only when
     *  re-edited — same behavior as the old eval() path.</p>
     *
     * @param node 目标节点 / the target node
     * @return 是否发生了覆盖 / whether any wired override occurred
     */
    private boolean applyParamPinOverrides(GraphNode node) {
        int extraBase = node.functionalInputs();
        int extraCnt = node.type.editableParamCount();
        if (extraCnt > 0 && node.type != NodeType.FORMULA) {
            boolean hasOverride = false;
            float[] effParams = node.params;
            for (int pi = 0; pi < extraCnt; pi++) {
                if (ctx.graph.hasInputConnection(node.id, extraBase + pi)) {
                    if (!hasOverride) { effParams = node.params.clone(); hasOverride = true; }
                    if (pi < effParams.length) effParams[pi] = ctx.graph.getInputValue(node.id, extraBase + pi, ctx.outputs);
                }
            }
            if (hasOverride) node.params = effParams;
            return hasOverride;
        }
        return false;
    }

    // 包内可见（EncapEval 跨实例驱动子图逐节点求值时调用 subEval.evalExt）；签名不变。
    // Package-visible (EncapEval drives per-node sub-graph evaluation across instances
    // via subEval.evalExt); signature unchanged.
    void evalExt(GraphNode node, List<InputSource> inputs, Map<Integer, Float> pidState, float dt,
                         SeatInputState seat,
                         Map<Integer, java.util.ArrayDeque<Float>> delayQueues,
                         Map<Integer, Boolean> flipflopStates,
                         Map<Integer, Integer> pulseTimers) {
        // 通用参数引脚：连线值临时覆盖 node.params（时序节点同享；此前只应用于 eval()，
        // 导致 DELAY/PULSE_EXTEND/LOOP/FUSE 等时序节点的参数引脚连线无效）
        // Generic parameter pins: wired values temporarily override node.params (shared with
        // eval(); previously only applied in eval(), so wiring the param pins of sequential
        // nodes like DELAY/PULSE_EXTEND/LOOP/FUSE had no effect)
        float[] origParams = node.params;
        applyParamPinOverrides(node);
        float[] o = node.outputValues;
        // 每调用/每节点槽落位（阶段 0：只填充 context，case 体零改动）/ land the per-call /
        // per-node slots (stage 0: fill the context only — case bodies untouched)
        ctx.node = node;
        ctx.o = o;
        ctx.inputs = inputs;
        ctx.pidState = pidState;
        ctx.dt = dt;
        ctx.seat = seat;
        ctx.delayQueues = delayQueues;
        ctx.flipflopStates = flipflopStates;
        ctx.pulseTimers = pulseTimers;
        // 方法内局部别名（接收者改写）：case 体逐字保留 / local aliases (receiver rewrite):
        // the case bodies stay verbatim
        NodeGraph graph = ctx.graph;
        Map<Integer, float[]> outputs = ctx.outputs;
        Map<Integer, Float> debugTime = ctx.debugTime;
        SignalBusPort signalBus = ctx.signalBus;
        AudioBandsPort audioBands = ctx.audioBands;
        Map<Long, AudioRef> audioRefs = ctx.audioRefs;
        Map<Integer, MusicTransport> audioTransports = ctx.audioTransports;
        net.minecraft.core.BlockPos audioHostPos = ctx.audioHostPos;
        SpeakerSink speakerSink = ctx.speakerSink;
        long audioTickStamp = ctx.audioTickStamp;
        KineticEncoderView encoderView = ctx.encoderView;
        KineticNetworkView kineticNetworkView = ctx.kineticNetworkView;
        GearboxCommandSink commandSink = ctx.commandSink;
        Integer completedNodeId = ctx.completedNodeId;
        RuntimeState runtimeState = ctx.runtimeState;
        net.minecraft.core.BlockPos radarPos = ctx.radarPos;
        TargetLookup targetLookup = ctx.targetLookup;
        Map<Integer, GraphEvaluator> subEvaluators = ctx.subEvaluators;
        Map<Integer, Integer> subGraphGenerations = ctx.subGraphGenerations;
        Map<Integer, Map<Integer, java.util.ArrayDeque<Float>>> subDelayQueues = ctx.subDelayQueues;
        Map<Integer, Map<Integer, Boolean>> subFlipflopStates = ctx.subFlipflopStates;
        Map<Integer, Map<Integer, Integer>> subPulseTimers = ctx.subPulseTimers;
        switch (node.type) {
            // 时序状态档（evalExt 七类，含 GATE）→ TimingEval.evalState（决策文档 §四）；
            // 其余类型落 eval() 档（SEQUENTIAL_ACC 的 ACCUMULATOR/INTEGRATOR 随行）。
            // Timing state tier (the seven evalExt types, GATE included) →
            // TimingEval.evalState (decision §四); everything else falls to the eval()
            // tier (SEQUENTIAL_ACC's ACCUMULATOR/INTEGRATOR included).
            case DELAY, LATCH, GATE, T_FLIPFLOP, PULSE_EXTEND, LOOP, FUSE -> TimingEval.evalState(ctx);
            default -> { eval(node, inputs, pidState, dt, seat); node.params = origParams; return; }
        }
        node.params = origParams; // 恢复参数（可能被连线值临时覆盖）  /  Restore params (may have been temporarily overwritten by wired values)
        o = ctx.o; // 处理器回写后同步输出数组 / resync the output array after the handler's write-back
        outputs.put(node.id, o.clone());
    }

    private void eval(GraphNode node, List<InputSource> inputs, Map<Integer, Float> pidState, float dt, SeatInputState seat) {
        float[] o = node.outputValues;
        // 每调用/每节点槽落位（阶段 0：只填充 context，case 体零改动）/ land the per-call /
        // per-node slots (stage 0: fill the context only — case bodies untouched)
        ctx.node = node;
        ctx.o = o;
        ctx.inputs = inputs;
        ctx.pidState = pidState;
        ctx.dt = dt;
        ctx.seat = seat;
        // 方法内局部别名（接收者改写）：case 体逐字保留 / local aliases (receiver rewrite):
        // the case bodies stay verbatim
        NodeGraph graph = ctx.graph;
        Map<Integer, float[]> outputs = ctx.outputs;
        Map<Integer, Float> debugTime = ctx.debugTime;
        SignalBusPort signalBus = ctx.signalBus;
        AudioBandsPort audioBands = ctx.audioBands;
        Map<Long, AudioRef> audioRefs = ctx.audioRefs;
        Map<Integer, MusicTransport> audioTransports = ctx.audioTransports;
        net.minecraft.core.BlockPos audioHostPos = ctx.audioHostPos;
        SpeakerSink speakerSink = ctx.speakerSink;
        long audioTickStamp = ctx.audioTickStamp;
        KineticEncoderView encoderView = ctx.encoderView;
        KineticNetworkView kineticNetworkView = ctx.kineticNetworkView;
        GearboxCommandSink commandSink = ctx.commandSink;
        Integer completedNodeId = ctx.completedNodeId;
        RuntimeState runtimeState = ctx.runtimeState;
        net.minecraft.core.BlockPos radarPos = ctx.radarPos;
        TargetLookup targetLookup = ctx.targetLookup;
        Map<Integer, GraphEvaluator> subEvaluators = ctx.subEvaluators;
        Map<Integer, Integer> subGraphGenerations = ctx.subGraphGenerations;
        Map<Integer, Map<Integer, java.util.ArrayDeque<Float>>> subDelayQueues = ctx.subDelayQueues;
        Map<Integer, Map<Integer, Boolean>> subFlipflopStates = ctx.subFlipflopStates;
        Map<Integer, Map<Integer, Integer>> subPulseTimers = ctx.subPulseTimers;

        // 参数引脚覆盖由调用方（evalExt / 无状态 evaluate 包装）负责，本方法只消费
        // node.params（连线覆盖已在进入前应用，返回后由调用方恢复）。
        // Param-pin overrides are applied by the caller (evalExt / the stateless
        // evaluate wrapper); this method only consumes node.params — wired overrides
        // are active on entry and restored by the caller on return.
        switch (node.type) {
            case KEYBOARD -> {
                int keyIndex = node.params.length > 0 ? (int)node.params[0] : 0;
                keyIndex = Math.max(0, Math.min(57, keyIndex)); // 58 键: A-Z, 0-9, Space, Shift, Ctrl 等 / 58 keys: A-Z, 0-9, Space, Shift, Ctrl, etc.
                o[0] = ((seat.keyBits >> keyIndex) & 1L) != 0 ? 1f : 0f;
            }
            case MOUSE_JOYSTICK -> {
                // abs mode (params[0]==1): accumulate mouse delta → stick position with memory.
                // inc mode (params[0]==0): direct output → mouse delta, stops=zero.
                // 绝对值模式：累积鼠标增量 → 带记忆的摇杆位置。
                // 增量模式：直接输出鼠标增量，停手归零。
                if (node.params.length > 0 && node.params[0] > 0.5f) {
                    float rawDx = seat.mouseX() / SeatInputState.JOYSTICK_SCALE;
                    float rawDy = seat.mouseY() / SeatInputState.JOYSTICK_SCALE;
                    node.runtimeStickX += rawDx * SeatInputState.ABS_SCALE;
                    node.runtimeStickY += rawDy * SeatInputState.ABS_SCALE;
                    node.runtimeStickX = Math.max(-1f, Math.min(1f, node.runtimeStickX));
                    node.runtimeStickY = Math.max(-1f, Math.min(1f, node.runtimeStickY));
                    o[0] = node.runtimeStickX;
                    o[1] = node.runtimeStickY;
                } else {
                    o[0] = seat.mouseX(); o[1] = seat.mouseY();
                }
            }
            case VIEW_ANGLE -> { o[0] = seat.pitch(); o[1] = seat.yaw(); }
            case MOUSE_BUTTON -> { o[0] = (seat.mouseButtons() & 1) != 0 ? 1 : 0; o[1] = (seat.mouseButtons() & 2) != 0 ? 1 : 0; }
            case GAMEPAD_JOYSTICK -> { o[0] = seat.gpadLX(); o[1] = seat.gpadLY(); o[2] = seat.gpadRX(); o[3] = seat.gpadRY(); }
            case GAMEPAD_BUTTON -> {
                int bi = node.params.length > 0 ? (int)node.params[0] : 0;
                o[0] = bi >= 0 && bi < 64 && ((seat.gpadButtons() >> bi) & 1L) != 0 ? 1 : 0;
            }
            case GAMEPAD_TRIGGER -> { o[0] = seat.gpadLT(); o[1] = seat.gpadRT(); }
            case WORLD_VIEW -> { o[0] = seat.worldYaw(); o[1] = seat.worldPitch(); }
            case ATTITUDE -> { o[0] = seat.attitudePitch(); o[1] = seat.attitudeRoll(); }
            case FORWARD -> { o[0] = seat.forwardYaw(); o[1] = seat.forwardPitch(); }
            case ACCELERATION -> { o[0] = seat.accelX(); o[1] = seat.accelY(); o[2] = seat.accelZ(); }
            case VELOCITY -> { o[0] = seat.velX() * 2f; o[1] = seat.velY() * 2f; o[2] = seat.velZ() * 2f; }
            case SPLIT -> {
                float v = graph.getInputValue(node.id, 0, outputs);
                o[0] = Math.max(0, v); o[1] = Math.max(0, -v);
            }
            case RELAY_A -> {
                float a = graph.getInputValue(node.id, 0, outputs);
                float b = graph.getInputValue(node.id, 1, outputs);
                boolean c = graph.getInputValue(node.id, 2, outputs) > 0.5f;
                // SPDT: contact false → A throw connected; contact true → B throw connected; disconnected side = 0
                o[0] = c ? 0f : a;  // A输出
                o[1] = c ? b : 0f;  // B输出
            }
            case RELAY_B -> {
                float a = graph.getInputValue(node.id, 0, outputs);
                float b = graph.getInputValue(node.id, 1, outputs);
                boolean c = graph.getInputValue(node.id, 2, outputs) > 0.5f;
                o[0] = c ? b : a;   // 输出 = selected source
            }
            case POSE_CONVERT -> {
                float pa = graph.getInputValue(node.id, 0, outputs);
                float ya = graph.getInputValue(node.id, 1, outputs);
                float roll = graph.getInputValue(node.id, 2, outputs);
                double r = Math.toRadians(roll);
                double sr = Math.sin(r), cr = Math.cos(r);
                o[0] = (float)(sr * pa + cr * ya);
                o[1] = (float)(cr * pa - sr * ya);
            }
            case HUD_PITCH_LADDER -> {
                // 透传姿态输入（pitch/roll），符号形态由渲染解释 / passthrough attitude
                // inputs (pitch/roll); the symbol shape is interpreted by the renderer
                o[0] = graph.getInputValue(node.id, 0, outputs);
                o[1] = graph.getInputValue(node.id, 1, outputs);
            }
            case CONST -> o[0] = node.params.length > 0 ? node.params[0] : 0;
            case DEBUG_SIGNAL_GEN -> {
                int setMode = node.params.length > 0 ? (int) node.params[0] : 0;
                int outMode = node.params.length > 1 ? (int) node.params[1] : 0;
                float inputX = node.params.length > 4 ? node.params[4] : 0.5f;
                // speed/amp 仅手动模式生效；公式模式用默认值
                // speed/amp only effective in manual mode; formula mode uses defaults
                float speed = (setMode == DebugSignals.SET_MANUAL && node.params.length > 2) ? node.params[2] : (1f / 20f);
                float amp = (setMode == DebugSignals.SET_MANUAL && node.params.length > 3) ? node.params[3] : 1f;
                float x;
                if (outMode == DebugSignals.OUT_FREQ) {
                    // 频率发生：x 自动 0→1 循环推进 / frequency generate: x auto-advances 0→1
                    x = debugTime.getOrDefault(node.id, 0f);
                    x += speed;
                    x -= (float) Math.floor(x);
                    debugTime.put(node.id, x);
                } else {
                    // 指定模式：x 来自节点参数 / input-driven: x from node param
                    x = inputX;
                }
                // 编译缓存（避免每 tick 重新编译公式） / compile cache
                // F13: 编译器对残缺公式是 lenient 的（如 "x +" 能编出坏 RPN），真正的炸点在求值期
                // （栈机 pop 空栈）——以往每 tick 抛一次、evalFormula 每 tick 告警一次。改为：编译时
                // 试算一次探测坏公式，失败态按节点缓存（失效键=脚本文本），只告警一次；后续 tick
                // 复用失败态短路（不再重编译/重试算），输出保持归零口径；脚本被编辑（缓存失效 op
                // 置空 debugFormulaRpn）后自动重新编译，修好即恢复。
                // F13: the compiler is lenient toward malformed formulas ("x +" still yields a broken
                // RPN); the real crash point is evaluation time (stack-machine pop on an empty stack) —
                // which used to throw per tick and warn per tick from evalFormula. Now: a trial
                // evaluation at compile time probes for broken formulas; the failure state is cached
                // per node (invalidation key = script text), warns exactly once, and later ticks
                // reuse the failure state as a short circuit (no recompile/re-probe) with the output
                // pinned to 0. Editing the script (the invalidation op clears debugFormulaRpn)
                // recompiles automatically, so a fixed formula recovers on its own.
                boolean formulaDead = false;
                if (setMode == DebugSignals.SET_FORMULA) {
                    if (node.debugFormulaCompileWarned && node.formula.equals(node.debugFormulaWarnedScript)) {
                        // 已知坏脚本（旗标亮 + 脚本文本未变）：直接复用失败态 / known-bad script (flag up,
                        // text unchanged): reuse the failure state directly
                        formulaDead = true;
                    } else if (node.debugFormulaRpn == null && node.formula != null && !node.formula.isBlank()) {
                        node.debugFormulaRpn = DebugSignals.compileFormula(node.formula);
                        if (node.debugFormulaRpn != null) {
                            try {
                                // 试算用任意 x 均可——栈机的抛错只取决于 RPN 结构，与数值无关
                                // Any x works for the probe — the stack machine throws on RPN
                                // structure alone, never on values
                                FormulaParser.evaluate(node.debugFormulaRpn, Map.of("x", 0.0));
                            } catch (Exception e) {
                                node.debugFormulaRpn = null; // 坏 RPN 不入缓存 / broken RPN is not cached
                            }
                        }
                        if (node.debugFormulaRpn == null) {
                            node.debugFormulaCompileWarned = true;
                            node.debugFormulaWarnedScript = node.formula;
                            LOGGER.warn("DEBUG_SIGNAL_GEN node {}: formula '{}' failed to compile/evaluate — output pinned to 0 (warn once per script)",
                                node.id, node.formula);
                            formulaDead = true;
                        } else {
                            // 编译恢复：复位告警旗标（脚本可能曾被编辑过）/ recovered: reset the warn flag
                            node.debugFormulaCompileWarned = false;
                            node.debugFormulaWarnedScript = null;
                        }
                    }
                    // 失败态以 null 公式短路 computeCurve：evalFormula 的 null 分支直接归 0，
                    // 不再触发其内部每 tick 的 WARN 路径。
                    // The failure state short-circuits computeCurve via a null formula: evalFormula's
                    // null branch pins 0 without ever reaching its per-tick WARN path.
                    o[0] = amp * DebugSignals.computeCurve(setMode, x, node.curveX, node.curveY,
                        formulaDead ? null : node.formula, node.debugFormulaRpn);
                } else {
                    o[0] = amp * DebugSignals.computeCurve(setMode, x, node.curveX, node.curveY, node.formula, node.debugFormulaRpn);
                }
            }
            case DEBUG_PROBE -> {
                // pass-through：输出 = 输入，供客户端从 EvalSnapshot 读取
                o[0] = graph.getInputValue(node.id, 0, outputs);
            }
            case REDSTONE_IN -> {
                long nodeKey = FreqItems.freqKey(node.itemParams);
                int sig = 0;
                for (InputSource s : inputs) if (s.freqKey == nodeKey) { sig = s.signal; break; }
                o[0] = sig;
            }
            case REDSTONE_OUT -> { }
            case ADD, SUB, MUL, DIV, MOD, POW, ROOT, ABS, ROUND, SIN, COS, TAN, ASIN, ACOS,
                 ATAN2, SINH, COSH, SQRT, LN, LOG, EXP, SEC, CSC, COT, ANGLE_UNWRAP, DIRECTION,
                 INTERP, CEIL, FLOOR, OR, BOOL, GT, LT, GE, LE, EQ, PID, PID_POWER, CLAMP, MAP,
                 FORMULA -> FormulaEval.eval(ctx);
            case SPEED_CTRL, TX_OUT, CLUTCH, ENCODER, STRESS, RPM, MOVE, ROTATE, WAIT -> MotionEval.eval(ctx);
            case PRIVATE_IN -> {
                // 私有频段音频分支（R1-2 完整落地）：输出接了音频线（下游 AUDIO 域引脚）→ 按
                // consumerKey 读 AudioBands（exactly-once 游标 + 新鲜度门控，与 BUS_IN 同款；
                // 空名/缺席/陈旧均为空音源）；否则走浮点 SIGNALS。
                // Private-band audio branch: output wired to an audio pin reads AudioBands
                // (same cursor/freshness discipline as BUS_IN); otherwise float SIGNALS.
                if (graph.hasAudioSinkConnection(node.id)) {
                    audioRefs.put(EvalContext.audioKey(node.id, 0),
                        audioBands
                            .get(ctx.consumerKey(node), node.signalName, audioTickStamp));
                    break;
                }
                o[0] = signalBus.get(node.signalName);
            }
            case PRIVATE_OUT -> {
                // 私有频段音频分支（R1-2）：输入来自音频线 → 发布 AudioRef 到 AudioBands
                //（名字 = signalName，owner/冲突纪律与 AUDIO_OUT 同款，空名不发布）；否则浮点。
                // Private-band audio branch: audio input publishes the AudioRef to AudioBands
                // (owner/conflict discipline as AUDIO_OUT); otherwise float.
                // 频道定义（引脚类型变换的属性源）：输入对端是音频 → 频道承载音频，随版本戳
                // 同步给订阅方着色；属性跟频道走（拓扑判定、稳态零开销），冲突节点不算定义。
                // Channel definition (the property behind pin typing): an audio pin peer makes
                // the channel audio-carrying, version-synced to subscribers; the property rides
                // the channel (topology-derived), and a conflicted node defines nothing.
                if (!node.busConflict && !node.signalName.isEmpty()) {
                    signalBus.setPrivateAudio(node.signalName, graph.isBandPinAudio(node.id, 0, false));
                }
                AudioRef aref = graph.getAudioInputRef(node.id, 0, audioRefs);
                if (aref != null) {
                    node.audioConflict = !node.signalName.isEmpty()
                        && !audioBands
                            .publish(node.signalName, aref, audioTickStamp, ctx.consumerKey(node));
                    break;
                }
                // 浮点写入（占用检测，BUS 同款）：冲突节点不写——写入权属于首个注册者；
                // 空名不写（BUS_OUT 同款早退口径，P2-5）——否则未命名节点互写 SIGNALS[""]
                // 幽灵频道，释放路径 clearSignal("") 清不掉。
                // Float write (BUS-parity occupancy): a conflicted node writes nothing — write
                // rights belong to the first registrant; an empty name writes nothing either
                // (BUS_OUT's same early-out, P2-5) — otherwise unnamed nodes cross-write the
                // SIGNALS[""] ghost channel that release paths can never clear.
                if (!node.busConflict && !node.signalName.isEmpty())
                    signalBus.put(node.signalName, graph.getInputValue(node.id, 0, outputs));
            }
            case BUS_IN -> {
                // 按频段分流（属性跟频段走）：某频段的输出接了音频线 → 该频段读自己的
                // AudioBands 频段键（频道\0频段，游标 exactly-once + 新鲜度门控同前）；
                // 其余频段走浮点 internalMap——同一频道可混载音频与浮点频段，互不妨碍。
                // Per-band lanes: a band whose output enters an audio pin reads its own
                // AudioBands band key (channel\0band, cursor + freshness as before); the rest
                // read the float internalMap — one channel can mix audio and float bands.
                if (node.signalName.isEmpty()) break; // 空名称不允许通信  /  Empty name disallows communication
                int bc = node.bandCount();
                if (bc > 0) {
                    if (o.length != bc) { o = new float[bc]; node.outputValues = o; }
                    // Band definition is owned by the channel's BUS_OUT and stored in the
                    // global BAND_REGISTRY. A BUS_IN in a DIFFERENT block may hold a stale
                    // signalBands list (BusBandUploadPacket only updates the sender's block),
                    // so prefer the registry's authoritative band list for KEY LOOKUP. This
                    // keeps the map key aligned with what BUS_OUT writes, fixing "BUS_OUT
                    // writes but BUS_IN reads 0" across blocks. The output array size still
                    // follows the node's own bandCount (pin count is node-defined); if the
                    // registry is longer we just read the leading bands, if shorter the rest
                    // fall back to the node's own names.
                    // band 定义归频道 BUS_OUT 所有，存于全局 BAND_REGISTRY。其他方块中的
                    // BUS_IN 可能持有过时的 signalBands（BusBandUploadPacket 只更新发送者
                    // 方块），故 KEY 查询优先用注册表的权威 band 列表，使 map key 与 BUS_OUT
                    // 写入一致，修复跨方块 "BUS_OUT 写入但 BUS_IN 读 0"。输出数组大小仍
                    // 遵循节点自身 bandCount（引脚数由节点定义）；注册表更长时只读前几个，
                    // 更短时其余回退到节点自身名称。
                    var registered = signalBus.getBands(node.signalName);
                    var chanMap = signalBus.channelMap(node.signalName);
                    for (int bi = 0; bi < bc; bi++) {
                        String key;
                        if (registered != null && bi < registered.size())
                            key = registered.get(bi);        // authoritative key / 权威 key
                        else
                            key = node.signalBands.get(bi);    // fallback to node list / 回退到节点列表
                        // 音频频段：该频段的输出接了音频线 → 读该频段的音频键。音频面独立于
                        // 浮点频道表（CHANNELS 由宿主 tick 引用计数注册，纯求值环境可能缺席）。
                        // Audio band: this band's output is wired to an audio pin. The audio
                        // plane is independent of the float CHANNELS table (ref-counted by host
                        // ticks, absent in pure-eval contexts).
                        if (graph.isAudioWired(node.id, bi)) {
                            audioRefs.put(EvalContext.audioKey(node.id, bi),
                                audioBands
                                    .get(ctx.consumerKey(node),
                                        audioBands
                                            .bandKey(node.signalName, key), audioTickStamp));
                            continue;
                        }
                        o[bi] = chanMap != null ? chanMap.getOrDefault(key, 0f) : 0f;
                    }
                } else {
                    if (o.length < 1) { o = new float[1]; node.outputValues = o; }
                    var chanMap = signalBus.channelMap(node.signalName);
                    o[0] = (chanMap != null) ? chanMap.getOrDefault("", 0f) : 0;
                }
            }
            case BUS_OUT -> {
                if (node.signalName.isEmpty()) break;
                int bc = node.bandCount();
                if (bc > 0) {
                    if (node.busInternalMap == null) node.busInternalMap = new java.util.HashMap<>();
                    // 冲突节点不注册频段，防止劫持 BAND_REGISTRY
                    // bandsDirty 跳过稳态下每 tick 的冗余 ArrayList 分配
                    // Conflicting nodes skip band registration to prevent BAND_REGISTRY hijacking
                    // bandsDirty avoids redundant ArrayList allocations per tick in steady state
                    if (!node.busConflict && node.bandsDirty) {
                        signalBus.registerBands(node.signalName, node.signalBands);
                        node.bandsDirty = false;
                    }
                    // 冲突节点跳过 busInternalMap 写入 — MAP 修改权属于首个注册的 BUS_OUT
                    // Conflicting nodes skip busInternalMap writes — MAP mutation rights belong to
                    // the first registered BUS_OUT
                    if (!node.busConflict) {
                        // v1.2.4: Build pinId→value map from connections, then feed by band name.
                        // This avoids depending on cache position being in sync with signalBands order.
                        // 构建 pinId→值映射，再按频段名注入，避免依赖缓存位置与 signalBands 顺序一致。
                        var pinValues = new java.util.HashMap<String, Float>();
                        for (var c : graph.connections) {
                            if (c.toId == node.id) {
                                float[] out = outputs.get(c.fromId);
                                if (out != null) {
                                    float val = c.fromPin < out.length ? out[c.fromPin] : 0;
                                    String pid = c.toPinId != null ? c.toPinId : String.valueOf(c.toPin);
                                    pinValues.put(pid, val);
                                }
                            }
                        }
                        // 音频频段引脚：接音频线 → 发到 AudioBands（单引脚多声道，跨方块，带发布时刻）。
                        // owner = 宿主坐标+节点 id（R1-2 冲突纪律，与 AUDIO_OUT 同款）；
                        // 混载频道任一音频频段冲突即置旗标，无音频的频段不影响旗标。
                        node.audioConflict = false;
                        // 频段音频标志（按引脚对端域的拓扑判定，随频段定义同步给订阅方着色；
                        // isBandPinAudio 走拓扑版本缓存，稳态零开销）。冲突节点不算定义。
                        // Per-band audio flags (topology-based via pin peers, synced with the
                        // band definition for subscriber tinting); conflicting nodes define nothing.
                        var audioBandSet = new java.util.HashSet<String>();
                        for (int bi = 0; bi < bc; bi++)
                            if (graph.isBandPinAudio(node.id, bi, false))
                                audioBandSet.add(node.signalBands.get(bi));
                        signalBus.setAudioBands(node.signalName, audioBandSet);
                        for (int bi = 0; bi < bc; bi++) {
                            String bandName = node.signalBands.get(bi);
                            AudioRef aref = graph.getAudioInputRef(node.id, bi, audioRefs);
                            if (aref != null) {
                                // 频段键 = 频道\0频段：每个频段独立占用/冲突/游标，互不覆盖。
                                // Per-band key = channel\0band: independent ownership, conflict
                                // and cursor per band — no cross-band overwrites.
                                if (!audioBands
                                    .publish(audioBands
                                            .bandKey(node.signalName, bandName),
                                        aref, audioTickStamp, ctx.consumerKey(node)))
                                    node.audioConflict = true;
                            } else {
                                node.busInternalMap.put(bandName, pinValues.getOrDefault(bandName, 0f));
                            }
                        }
                    }
                }
            }
            case POSITION -> {
                float wx = seat.worldX(), wy = seat.worldY(), wz = seat.worldZ();
                // 应用编辑区偏移（左/右、上/下、前/后），按方块世界朝向旋转
                // Apply editor offsets (left/right, up/down, front/back), rotated by block world facing
                if (node.params.length >= 3) {
                    float offX = node.params[0], offY = node.params[1], offZ = node.params[2];
                    if (offX != 0 || offY != 0 || offZ != 0) {
                        // 使用 forwardYaw 而非 blockYaw — forwardYaw 包含了 Sable 结构旋转
                        // Use forwardYaw instead of blockYaw — forwardYaw includes Sable structure rotation
                        float rad = (float) Math.toRadians(seat.forwardYaw());
                        float cos = (float) Math.cos(rad), sin = (float) Math.sin(rad);
                        // +X = 方块右侧, +Z = 方块前方（世界空间）  /  +X = block right, +Z = block forward (world space)
                        wx += offX * cos + offZ * sin;
                        wy += offY;
                        wz += offX * sin - offZ * cos;
                    }
                }
                o[0] = wx; o[1] = wy; o[2] = wz;
            }
            case TARGET_OUT -> {
                var t = radarPos != null ? targetLookup.get(radarPos, node.id) : null;
                if (t != null) { o[0] = (float) t.x(); o[1] = (float) t.y(); o[2] = (float) t.z(); o[3] = t.entityId(); o[4] = t.distance(); }
            }
            case ACCUMULATOR, INTEGRATOR -> TimingEval.eval(ctx);
            // 显示节点 — 无浮点输出；数据由渲染器从 GraphNode 字段读取
            // Display nodes — no float output; data read from GraphNode fields by renderer
            case TEXT, DATA, IMAGE, IMAGE_SEQUENCE, COMMENT -> {}
            // ── 音频（功放电脑）/ Audio (amplifier computer) ──
            case MUSIC -> {
                // 输入：play/stop 触点（上升沿）、seek 数值（值变化当帧快照跳转，含回 0）。
                // 输出：audio(handle)、playing、tick、seconds、done。
                MusicTransport tr;
                if (audioTransports != null) {
                    tr = audioTransports.get(node.id);
                    if (tr == null) { tr = new MusicTransport(); audioTransports.put(node.id, tr); }
                } else {
                    tr = new MusicTransport();
                }
                float playIn = graph.getInputValue(node.id, 0, outputs);
                float stopIn = graph.getInputValue(node.id, 1, outputs);
                float seekIn = graph.getInputValueOrDefault(node.id, 2, outputs, 0f);
                NbsSong song = node.song;
                // seek：值变化即当帧快照跳转；上次值记 pidState 辅助槽位 id+300000（随运行时状态
                // 持久化，重载/重编译不误跳；剪除由 aliveStateKeys 的同款辅助槽位覆盖）。
                float seekLast = (runtimeState != null)
                    ? runtimeState.pidState.getOrDefault(node.id + 300000, 0f) : 0f;
                if (runtimeState != null) runtimeState.pidState.put(node.id + 300000, seekIn);
                if (Math.round(seekIn) != Math.round(seekLast)) tr.seek(Math.round(seekIn));
                boolean playCur = playIn > 0.5f, stopCur = stopIn > 0.5f;
                boolean playPrev = runtimeState != null && runtimeState.nodeEdge.getOrDefault(node.id, false);
                boolean stopPrev = runtimeState != null && runtimeState.nodeEdge.getOrDefault(node.id + 100000, false);
                if (playCur && !playPrev) tr.playFromHead(song); // seek 已先行；头在曲尾则回头重播
                if (stopCur && !stopPrev) tr.stop();
                if (runtimeState != null) {
                    runtimeState.nodeEdge.put(node.id, playCur);
                    runtimeState.nodeEdge.put(node.id + 100000, stopCur);
                }
                // 预播提前量：提前 PREROLL_SECONDS 展开、事件带目标时刻（delaySeconds）
                List<NoteEvent> events = (song != null)
                    ? tr.advance(song, dt, MusicTransport.PREROLL_SECONDS) : List.of();
                boolean done = tr.consumeFinishedPulse();
                // 停止/跳转沿（且未同 tick 续播）→ 一次性停止标记穿透管线到 sink：
                // 客户端清除该音响预播窗口内已下发未播的声部，加宽窗口才不会留尾巴。
                // Stop/seek edge (no same-tick resume) → a one-shot stop marker rides the
                // pipeline to the sink: the client cancels this speaker's dispatched-but-
                // unplayed voices, so a widened pre-roll leaves no tail after a stop.
                boolean flush = tr.consumeFlushPulse() && !tr.isPlaying();
                audioRefs.put(EvalContext.audioKey(node.id, 0), new AudioRef(events, 1f, flush));
                o[0] = 0; // 音频引脚经 audioRefs（类型化，非浮点）
                o[1] = tr.isPlaying() ? 1f : 0f;
                o[2] = tr.headTick();
                o[3] = (song != null) ? tr.headSeconds(song) : 0f;
                o[4] = done ? 1f : 0f;
            }
            case AMP -> {
                AudioRef in = graph.getAudioInputRef(node.id, 0, audioRefs);
                float gainIn = graph.getInputValueOrDefault(node.id, 1, outputs,
                    node.params.length > 0 ? node.params[0] : 1f);
                float gain = Math.max(0f, Math.min(4f, gainIn));
                if (in == null) {
                    audioRefs.put(EvalContext.audioKey(node.id, 0), AudioRef.EMPTY);
                } else {
                    // 力度曲线（方案一，2026-10-05 作者拍板）：x = 音符自身力度（速度×层音量 =
                    // 实例音量，0..1），y = 输出力度（写回实例音量）——曲线管形状、链上标量（含引脚）
                    // 在曲线后线性叠加管电平，两者正交；恒等曲线 = 原行为。曲线点间线性插值，端点钳制。
                    // x 不是 gain 字段：运输层把速度×层音量烘焙进 velocity、gain 恒 1，
                    // 响度住在 instanceVolume()——用 gain 当 x 会让曲线只见常数、塑不到力度。
                    // Dynamics curve (option 1, author call): x = the note's own loudness
                    // (velocity × layer = the instance volume, 0..1), y = output level written
                    // back as the instance volume — the curve shapes, the chain scalar (incl.
                    // the pin) scales afterwards; the two are orthogonal. Identity curve = old
                    // behaviour. Linear interpolation between points, clamped ends. Note x is
                    // NOT the gain field: the transport bakes velocity × layer into velocity and
                    // fixes gain at 1, so loudness lives in instanceVolume() — using gain as x
                    // would show the curve a constant and shape no dynamics at all.
                    // 恒等曲线 = 真旁路（不参与，与没动过逐位一致）——「默认」随时可达，
                    // 不是一次性状态。Identity curve = true bypass (bit-identical to
                    // untouched), so the default is always reachable, not one-shot.
                    if (node.isCurveNode() && node.curveX != null && node.curveY != null
                        && !AudioCurve.isIdentity(node.curveX, node.curveY)
                        && in.events() != null && !in.events().isEmpty()) {
                        var shaped = new java.util.ArrayList<NoteEvent>(in.events().size());
                        for (NoteEvent e : in.events())
                            shaped.add(e.withInstanceVolume(
                                AudioCurve.eval(node.curveX, node.curveY, e.instanceVolume())));
                        in = new AudioRef(shaped, in.gain(), in.stopSignal(), in.waveCurves());
                    }
                    audioRefs.put(EvalContext.audioKey(node.id, 0), in.withGain(in.gain() * gain));
                }
            }
            case WSHAPE -> {
                // 波形整形：把自身曲线（控制点对）追加进引用的整形链（应用序；客户端烘 LUT 时
                // 按序合成 g∘f），随 AudioRef → 频段表 → NoteEventPacket 送达客户端——起音时
                // 保号整形固化进采样（整形先于重采样插值，混音内环零附加成本）；控制点直达，
                // 无烘焙量化失真。恒等曲线 = 真旁路（不入链、不进包，与没动过逐位一致）。
                // 事件与标量原样——服务端不做任何采样级处理（功放图与音响图均可放）。
                // Wave shaper: appends this node's curve (control-point pairs) to the ref's
                // shaping chain (application order; the client composes g∘f when baking) —
                // it rides the AudioRef → band table → note packet to the client, which bakes
                // the sign-preserving shape into the sample at voice start (shaping precedes
                // resampling interpolation; zero cost in the mix inner loop), points
                // delivered raw (no bake-quantization distortion). An identity curve is a
                // true bypass (never enters the chain or the wire — bit-identical to
                // untouched). Events and the scalar pass untouched (no server-side sample
                // processing); works in amplifier and speaker graphs alike.
                AudioRef in = graph.getAudioInputRef(node.id, 0, audioRefs);
                if (in == null) {
                    audioRefs.put(EvalContext.audioKey(node.id, 0), AudioRef.EMPTY);
                } else if (AudioCurve.isIdentity(node.curveX, node.curveY)) {
                    audioRefs.put(EvalContext.audioKey(node.id, 0), in);
                } else {
                    audioRefs.put(EvalContext.audioKey(node.id, 0),
                        in.withWaveCurve(new AudioCurve.Curve(node.curveX, node.curveY)));
                }
            }
            case CHANNEL -> {
                // 声道拆分：audio 入 → 各声道出（引脚集合 = 布局表；拆分口径见 ChannelLayout）。
                AudioRef in = graph.getAudioInputRef(node.id, 0, audioRefs);
                int chCount = node.signalBands != null ? node.signalBands.size() : 0;
                boolean hasCenter = ChannelLayout.hasCenter(
                    node.params.length > 0 ? (int) node.params[0] : ChannelLayout.STEREO);
                for (int ci = 0; ci < chCount; ci++)
                    audioRefs.put(EvalContext.audioKey(node.id, ci),
                        ChannelLayout.channelRef(in, node.signalBands.get(ci), hasCenter));
            }
            case AUDIO_OUT -> {
                // 单引脚多声道：audio 入（多声道 AudioRef）→ 发布到音频频段（band=signalName，带发布时刻）。
                // owner = 宿主坐标+节点 id（R1-2 冲突纪律）：同名双发布方 → 双方旗标、整体静默；
                // 空名无可发布对象，不算冲突。
                AudioRef in = graph.getAudioInputRef(node.id, 0, audioRefs);
                if (audioHostPos != null) {
                    node.audioConflict = !node.signalName.isEmpty()
                        && !audioBands
                            .publish(audioBands
                                    .bandKey(node.signalName, node.signalName),
                                in != null ? in : AudioRef.EMPTY, audioTickStamp, ctx.consumerKey(node));
                }
            }
            case SPEAKER_PLAY -> {
                // 播放 sink：把音源交给宿主音响 BE，在其坐标发声（R1-3/R1-D）。
                // 单引脚多声道、**恒等直通**（2026-10-04 作者口径：声道拆分归上游 CHANNEL——
                // 每路一个频道、音响是哑终端；单音响放不了聚合以上的多声道摆放，
                // 消费端二次选路是职责错位）。低音炮等单路需求 = 上游 CHANNEL 拆分后发布成频道。
                // Playback sink: hand the source to the host speaker BE (R1-3/R1-D).
                // Single-pin multi-channel, **identity passthrough** (author call 2026-10-04:
                // splitting belongs to the upstream CHANNEL - one band per channel, the speaker
                // is a dumb terminal; a single physical speaker cannot place multi-channel
                // audio, so a consumer-side re-pick is a responsibility leak).
                AudioRef in = graph.getAudioInputRef(node.id, 0, audioRefs);
                if (in != null && speakerSink != null) {
                    if (in.stopSignal()) speakerSink.stopPlayback();
                    else speakerSink.play(in.events(), in.gain(), in.waveCurves());
                }
            }
            case ENCAPSULATION -> {
                if (node.subGraph == null) break;
                var outNodes = node.getSubNodes(NodeType.ENCAP_OUTPUT);
                int nOut = outNodes.size();
                // 节点数量超出上限 → 禁用所有功能，输出 0
                // Node count exceeds limit → disable all functions, output 0
                if (node.subGraph.nodes.size() > 1024) {
                    o = new float[nOut];
                    node.outputValues = o;
                    break;
                }
                var inpNodes = node.getSubNodes(NodeType.ENCAP_INPUT);
                o = new float[nOut];
                node.outputValues = o;
                // 子图 evaluator 缓存 + 陈旧检测：若子图 generation 变化，丢弃旧缓存重建
                int currentGen = node.subGraph.graphGeneration;
                Integer lastGen = subGraphGenerations.get(node.id);
                if (lastGen == null || lastGen != currentGen) {
                    subEvaluators.remove(node.id);
                    subGraphGenerations.put(node.id, currentGen);
                }
                var subEval = subEvaluators.get(node.id);
                if (subEval == null) {
                    subEval = new GraphEvaluator(node.subGraph);
                    // 宿主注入必须传给子图求值器：封装内的 ENCODER 读不到视图会恒输出 0，
                    // MOVE/ROTATE/WAIT/CLUTCH 的指令入栈会被静默丢弃，雷达节点同样退化
                    // —— 与宿主 BE 在重建求值器后向顶层求值器注入的机制对齐。
                    // Host injections must reach sub-graph evaluators: an encapsulated
                    // ENCODER otherwise reads a null view (pinned at 0), motion-command
                    // enqueues are silently dropped, and radar lookups degrade — same
                    // contract as the host BE injecting into the top-level evaluator
                    // after every rebuild.
                    subEval.setEncoderView(ctx.encoderView);
                    subEval.setCommandSink(ctx.commandSink);
                    subEval.setRadarPos(ctx.radarPos);
                    subEval.setTargetLookup(ctx.targetLookup);
                    subEval.setSignalBus(ctx.signalBus);
                    subEval.setAudioBands(ctx.audioBands);
                    subEval.setKineticNetworkView(ctx.kineticNetworkView);
                    subEval.setAudioTransports(ctx.audioTransports);
                    subEval.setAudioHostPos(ctx.audioHostPos);
                    subEval.setSpeakerSink(ctx.speakerSink);
                    subEval.setAudioTickStamp(ctx.audioTickStamp);
                    subEvaluators.put(node.id, subEval);
                    if (runtimeState != null) {
                        RuntimeState.SubState ss = runtimeState.subStates.get(node.id);
                        if (ss != null && !ss.debugTime.isEmpty()) {
                            subEval.ctx.debugTime.putAll(ss.debugTime);
                        }
                    }
                }
                int encapId = node.id;
                // ── 跨 tick 透传（创建与复用路径都要做，P1-4/P1-5）──
                // ── Cross-tick passthrough (both the create and reuse paths, P1-4/P1-5) ──
                // 子求值器被 subEvaluators 缓存复用，只在创建时注入的标量会冻结：
                // audioTickStamp 冻结在创建 tick → 封装内发布的音频对外 1–2 tick 即判过期
                //（封装内→外静音）、封装内消费永不判陈旧（幽灵长音）。每 tick 与顶层同款刷新。
                // Sub-evaluators are cached in subEvaluators and reused; scalars injected only
                // at creation freeze: an audioTickStamp pinned at the creation tick makes
                // encapsulated audio read as stale 1–2 ticks later (encap→out silence) while
                // encapsulated consumers never see staleness (ghost notes). Refresh per tick,
                // same as the top level.
                subEval.setAudioTickStamp(ctx.audioTickStamp);
                // 运行时状态接入：子求值器的 MOVE/ROTATE/WAIT/MUSIC 边沿记忆（nodeEdge）与
                // MUSIC seek 槽位（pidState 辅助键）必须落在权威 SubState 表上（共享映射，
                // 求值即持久化）。缺此接入时封装内常高触发每 tick 被误判为新上升沿 → 指令
                // 无限重复入队（v2"指令永不停止"事故的子图翻版）、MUSIC 每 tick 重头播、
                // done 引脚永不出现。幂等：已接入同一 SubState 时跳过（putAllFrom 换对象
                // 时按映射身份重建）。
                // Runtime-state attach: the sub-evaluator's MOVE/ROTATE/WAIT/MUSIC edge memory
                // (nodeEdge) and the MUSIC seek slot (pidState auxiliary key) must land on the
                // authoritative SubState tables (shared maps — evaluating IS persisting).
                // Without it, a held-high trigger inside an encapsulation is misjudged as a
                // fresh rising edge every tick → commands re-enqueued forever (the sub-graph
                // replay of the v2 "command never stops" incident), MUSIC restarts from the
                // head every tick, and the done pin never fires. Idempotent: skipped when the
                // same SubState is already attached (rebuilt by map identity after putAllFrom
                // swaps the object).
                if (runtimeState != null) {
                    RuntimeState.SubState ss = runtimeState.getOrCreateSubState(encapId);
                    if (subEval.ctx.runtimeState == null || subEval.ctx.runtimeState.pidState != ss.pidState)
                        subEval.restoreSubState(RuntimeState.subView(ss));
                }
                // 完成脉冲透传：宿主把 done 指令的 sourceNodeId 置在顶层求值器上；封装内
                // 节点同款消费（下一 tick o[0] 输出一帧 1，宿主随后清空、此处同步归 null）。
                // 已知歧义残留：主图与子图 id 空间独立，同号且同为运动节点时完成脉冲可能
                // 互串一帧 —— MotionCommand 不携带图路径，宿主侧的彻底消歧归后续批次。
                // Done-pulse passthrough: the host sets the finished command's sourceNodeId on
                // the top-level evaluator; encapsulated nodes consume it the same way (next
                // tick outputs a one-frame 1; the host clears it and this mirrors the clear).
                // Known residual ambiguity: main- and sub-graph id spaces are independent, so
                // colliding ids on two motion nodes can cross-fire one frame — MotionCommand
                // carries no graph path; full disambiguation on the host side is a later batch.
                subEval.setCompletedNodeId(ctx.completedNodeId);
                // 将外部输入注入 ENCAP_INPUT 节点（v1.2.4: 按 pinId 匹配连线，不依赖缓存位置）
                // Inject outer inputs into ENCAP_INPUT nodes (v1.2.4: match by pinId, not cache position)
                // Build pinId→sourceValue map from connections targeting this ENCAPSULATION node
                var pinValues = new java.util.HashMap<String, Float>();
                for (var c : graph.connections) {
                    if (c.toId == node.id) {
                        float[] out = outputs.get(c.fromId);
                        if (out != null) {
                            float val = c.fromPin < out.length ? out[c.fromPin] : 0;
                            String pid = c.toPinId != null ? c.toPinId : String.valueOf(c.toPin);
                            pinValues.put(pid, val);
                        }
                    }
                }
                for (var inp : inpNodes) {
                    float val = pinValues.getOrDefault(String.valueOf(inp.id), 0f);
                    subEval.ctx.outputs.put(inp.id, new float[]{val});
                }
                // 评估子图 — 使用 RuntimeState 支持的映射表，以封装节点 ID 为键
                // Evaluate sub-graph — use RuntimeState-backed maps keyed by encap node ID
                var subPidState = runtimeState != null
                    ? runtimeState.getOrCreateSubState(encapId).pidState
                    : new java.util.HashMap<Integer, Float>();
                var sdq = subDelayQueues.computeIfAbsent(encapId, k -> new java.util.HashMap<>());
                var sff = subFlipflopStates.computeIfAbsent(encapId, k -> new java.util.HashMap<>());
                var spt = subPulseTimers.computeIfAbsent(encapId, k -> new java.util.HashMap<>());
                var topo = node.subGraph.getTopoOrder();
                for (int nid : topo) {
                    var subNode = node.subGraph.findNode(nid);
                    if (subNode == null || subNode.type == NodeType.ENCAP_INPUT) continue;
                    if (subNode.type == NodeType.ENCAP_OUTPUT) {
                        float val = node.subGraph.getInputValue(nid, 0, subEval.ctx.outputs);
                        subEval.ctx.outputs.put(nid, new float[]{val});
                    } else {
                        subEval.evalExt(subNode, inputs, subPidState, dt, seat,
                            sdq, sff, spt);
                    }
                }
                // 收集 ENCAP_OUTPUT 值
                // Collect ENCAP_OUTPUT values
                for (int i = 0; i < outNodes.size(); i++) {
                    o[i] = node.subGraph.getInputValue(outNodes.get(i).id, 0, subEval.ctx.outputs);
                }
                // 将子图状态写回 RuntimeState 以进行 NBT 持久化
                // Write sub-graph state back to RuntimeState for NBT persistence
                if (runtimeState != null) {
                    RuntimeState.SubState ss = runtimeState.getOrCreateSubState(encapId);
                    ss.delayQueues.clear(); ss.delayQueues.putAll(sdq);
                    ss.flipflopStates.clear(); ss.flipflopStates.putAll(sff);
                    ss.pulseTimers.clear(); ss.pulseTimers.putAll(spt);
                }
            }
        }
        // 尾部记账：守卫同步 ctx.o（处理器以 ctx.o = o 回写，内联重建数组的
        // BUS_IN / ENCAP 靠它把 node.outputValues 的新数组带出）。
        // 与旧口径 outputs.put(node.id, o.clone()) 逐位一致。
        // Tail bookkeeping: the guard resyncs ctx.o (handlers end with ctx.o = o; the
        // still-inlined rebuild paths of BUS_IN / ENCAP carry the new array through
        // node.outputValues). Bit-identical to the old outputs.put(node.id, o.clone()).
        if (ctx.o != node.outputValues) ctx.o = node.outputValues;
        outputs.put(node.id, ctx.o.clone());
    }
    public record InputSource(long freqKey, int signal) {}
    public record OutputResult(ItemStack freq1, ItemStack freq2, int signal) {}
}
