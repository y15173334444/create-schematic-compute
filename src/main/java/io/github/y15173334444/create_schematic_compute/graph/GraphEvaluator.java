package io.github.y15173334444.create_schematic_compute.graph;

import net.minecraft.world.item.ItemStack;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


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
    /** 取本刻某节点音频输出的引用（{@link #evaluate} 返回后有效，只在当刻有意义）。
     *  观察者用途：宿主 BE 读自己 MUSIC 节点展开的音符（如功放电脑的频谱取数）——拉取式，
     *  不像 sink 那样介入音频通路，读不读都不影响求值结果。
     *  This tick's audio-out reference of a node (valid right after {@link #evaluate}, for that
     *  tick only). Observer use: a host BE reads its own MUSIC node's expanded notes (e.g. the
     *  amplifier computer's spectrum tap) — pull-based, never in the audio path, so reading (or
     *  not) cannot change the evaluation result. */
    public AudioRef audioOut(int nodeId, int pin) {
        AudioRef r = ctx.audioRefs.get(EvalContext.audioKey(nodeId, pin));
        return r == null ? AudioRef.EMPTY : r;
    }
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

    // ── 阶段 0 桥（audioKey/consumerKey/inputsUnchanged 的实现已迁 EvalContext；
    //    case 体仍以裸调用形态引用，桥保持接收者零改动，随抽取批次退役）──
    // Stage-0 bridges (audioKey/consumerKey/inputsUnchanged implementations moved to
    // EvalContext; the case bodies still call them bare — the bridges keep the
    // receivers untouched and retire with the extraction batches).

    public GraphEvaluator(NodeGraph graph) { this.ctx = new EvalContext(graph); }

    /** 从之前保存的 RuntimeState 恢复子图状态。
     *  必须在构造后、首次 {@link #evaluate} 前调用一次。
     *  Restore sub-graph state from a previously-saved RuntimeState.
     *  Must be called once after construction and before the first {@link #evaluate}. */
    public void restoreSubState(RuntimeState rs) {
        ctx.runtimeState = rs;
        // 恢复主图 DEBUG_SIGNAL_GEN 相位 / restore main-graph DEBUG_SIGNAL_GEN phase
        if (!rs.debugTime.isEmpty()) ctx.debugTime.putAll(rs.debugTime);
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
        ctx.debugTime.putAll(saved);
    }

    /** 将当前 debugTime 相位写回 RuntimeState 以便 NBT 持久化。
     *  Write accumulated debugTime phase state back to RuntimeState for NBT persistence. */
    public void saveDebugTimes(RuntimeState rs) {
        rs.debugTime.clear();
        rs.debugTime.putAll(ctx.debugTime);
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

    /** 4 参 SeatInput 档已与全状态档合一（2026-10-31 拍板）：一律走 evalExt 全状态路径，
     *  七类时序/状态节点（DELAY/LATCH/GATE/T_FLIPFLOP/PULSE_EXTEND/LOOP/FUSE）随
     *  {@code TimingEval.evalState} 真正工作，§三-4 的静默空转怪癖随之熄灭。本重载随调用
     *  提供弃置三表（与 3 参重载同形）——跨 tick 的时序状态请传表（宿主经 runtimeState
     *  三表）或用带表重载。
     *  The 4-arg SeatInput form is unified with the full-state tier (2026-10-31 ruling):
     *  every call runs the evalExt full-state path, so the seven timing/state types
     *  (DELAY/LATCH/GATE/T_FLIPFLOP/PULSE_EXTEND/LOOP/FUSE) actually run via
     *  {@code TimingEval.evalState} and the §三-4 silently-idling quirk is gone. This
     *  overload supplies throwaway tables (same shape as the 3-arg form) — for cross-tick
     *  timing state, pass the maps (hosts ride runtimeState's tables) or use a
     *  table-taking overload. */
    public List<OutputResult> evaluate(List<InputSource> inputs, Map<Integer, Float> pidState, float dt, SeatInputState seat) {
        return evaluate(inputs, pidState, dt, seat, new HashMap<>(), new HashMap<>(), new HashMap<>());
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
                    // 注意：不清除 subEval.debugTime — 它是跨 tick 累积的相位状态，
                    // DEBUG_SIGNAL_GEN 依赖它推进频率。残留条目仅在被删除节点上泄漏一个 float。
                    // Note: do NOT clear subEval.debugTime — it is phase state that
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

    /** 通用参数引脚：连线值临时覆盖 node.params（evalExt 入口统一应用，eval 档随行消费）。
     *  Generic parameter pins: wired values temporarily override node.params
     *  (applied uniformly at the evalExt entry; the eval tier consumes them along).
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
        // 每调用/每节点槽落位（阶段 0：只填充 context，case 体零改动）/ land the per-call /
        // per-node slots (stage 0: fill the context only — case bodies untouched)
        ctx.node = node;
        ctx.o = node.outputValues;
        ctx.inputs = inputs;
        ctx.pidState = pidState;
        ctx.dt = dt;
        ctx.seat = seat;
        ctx.delayQueues = delayQueues;
        ctx.flipflopStates = flipflopStates;
        ctx.pulseTimers = pulseTimers;
        float[] o = ctx.o;
        switch (node.type) {
            // 时序状态档（evalExt 七类，含 GATE）→ TimingEval.evalState（决策文档 §四）；
            // 其余类型落 eval() 档（SEQUENTIAL_ACC 的 ACCUMULATOR/INTEGRATOR 随行）。
            case DELAY, LATCH, GATE, T_FLIPFLOP, PULSE_EXTEND, LOOP, FUSE -> TimingEval.evalState(ctx);
            default -> { eval(node, inputs, pidState, dt, seat); node.params = origParams; return; }
        }
        node.params = origParams; // 恢复参数（可能被连线值临时覆盖）  /  Restore params (may have been temporarily overwritten by wired values)
        o = ctx.o;
        ctx.outputs.put(node.id, o.clone());
    }

    private void eval(GraphNode node, List<InputSource> inputs, Map<Integer, Float> pidState, float dt, SeatInputState seat) {
        // 每调用/每节点槽落位（阶段 0：只填充 context，case 体零改动）/ land the per-call /
        // per-node slots (stage 0: fill the context only — case bodies untouched)
        ctx.node = node;
        ctx.o = node.outputValues;
        ctx.inputs = inputs;
        ctx.pidState = pidState;
        ctx.dt = dt;
        ctx.seat = seat;

        // 参数引脚覆盖由调用方（evalExt）负责，本方法只消费
        // node.params（连线覆盖已在进入前应用，返回后由调用方恢复）。
        // Param-pin overrides are applied by the caller (evalExt); this method only
        // consumes node.params — wired overrides are active on entry and restored by
        // the caller on return.
        // 分派骨架（决策文档 §四）：按 NodeCategory 分派到分类处理器；覆盖校验启动即炸
        //（NodeCategory 静态块），分派必然穷尽；刻意无 default（形态不变）。
        // 档位合一（2026-10-31 拍板，§三-4 怪癖随之熄灭）：本方法只作为 evalExt 的
        // 落档分派被调用——GATE 等七类时序/状态节点在 evalExt 入口即转
        // TimingEval.evalState，不再落到这里。FormulaEval 仍无 GATE case（GATE 不再
        // 走 FormulaEval）；TimingEval.eval 仍只有 ACCUMULATOR/INTEGRATOR。
        // Dispatch skeleton (decision doc §四): dispatch on NodeCategory to the
        // category handlers; the coverage check explodes at class-init
        // (NodeCategory's static block) so the dispatch is necessarily exhaustive;
        // deliberately no default (shape unchanged). Tier unification (2026-10-31
        // ruling, the §三-4 quirk extinguished with it): this method is only called
        // as evalExt's fall-through dispatch — the seven timing/state types are
        // routed to TimingEval.evalState at the evalExt entry and never land here.
        // FormulaEval still lacks GATE (GATE no longer routes to FormulaEval);
        // TimingEval.eval still implements only ACCUMULATOR/INTEGRATOR.
        switch (NodeCategory.of(node.type)) {
            case MATH_BASIC, MATH_ADVANCED, TRIG, LOGIC, CONTROL -> FormulaEval.eval(ctx);
            case SEQUENTIAL_ACC, SEQUENTIAL_STATE -> TimingEval.eval(ctx);
            case GEARBOX, TRANSMISSION, SPEED, KINETIC -> MotionEval.eval(ctx);
            case VALUES, OUTPUT -> BusEval.eval(ctx);
            case AUDIO -> AudioEval.eval(ctx);
            case INPUT_CTRL, INPUT_VIEW, INPUT_MOTION, INPUT_POSE -> InputEval.eval(ctx);
            case RADAR -> RadarEval.eval(ctx);
            case DISPLAY -> DisplayEval.eval(ctx);
            case DEBUG -> DebugEval.eval(ctx);
            case STRUCTURE -> EncapEval.eval(ctx);
        }
        // 尾部记账直读 ctx.o（处理器结尾以 ctx.o = o 回写重建数组随行——FORMULA /
        // BUS_IN / ENCAPSULATION 的 o = new float[...]; node.outputValues = o; 均在
        // 处理器内成对完成并回写）。与旧口径 outputs.put(node.id, o.clone()) 逐位一致。
        // Tail bookkeeping reads ctx.o (handlers end with ctx.o = o so rebuilt arrays
        // ride along — FORMULA / BUS_IN / ENCAPSULATION's paired
        // o = new float[...]; node.outputValues = o; all complete inside their handlers).
        // Bit-identical to the old outputs.put(node.id, o.clone()).
        ctx.outputs.put(node.id, ctx.o.clone());
    }

    public record InputSource(long freqKey, int signal) {}
    public record OutputResult(ItemStack freq1, ItemStack freq2, int signal) {}
}
