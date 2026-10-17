package io.github.y15173334444.create_schematic_compute.graph;

import java.util.List;
import java.util.Map;

/**
 * 结构域处理器（决策文档 §四）：STRUCTURE —— 仅 ENCAPSULATION（ENCAP_INPUT /
 * ENCAP_OUTPUT 无 case、保持空转，原样）。case 体从 {@link GraphEvaluator} 的 eval()
 * switch 逐字搬迁（§三-2：方法体不改写，只改接收者/归属）；本类是静态函数包，不建继承
 * 体系（§三-3）。<b>唯一获批改写：</b>子求值器创建块的 11 个 {@code subEval.setXxx(...)}
 * 注入清单收敛为一处 {@code subEval.ctx.inheritPortsFrom(ctx)}（EvalContext §四端口族
 * 继承，语义 = 该 11 项）；其后的每 tick {@code setAudioTickStamp} 刷新、
 * {@code setCompletedNodeId} 镜像、{@code restoreSubState} 挂接、debugTime 恢复保持
 * 原语义。
 * <p>Structure-domain handler (decision doc §四): STRUCTURE — ENCAPSULATION only
 * (ENCAP_INPUT / ENCAP_OUTPUT have no case and stay no-op, as before). Case bodies
 * are relocated verbatim from {@link GraphEvaluator}'s eval() switch (§三-2: bodies
 * are never rewritten, only their owner changes); this class is a static function
 * pack, no inheritance hierarchy (§三-3). <b>The one sanctioned rewrite:</b> the
 * sub-evaluator creation block's 11 {@code subEval.setXxx(...)} injections converge to
 * a single {@code subEval.ctx.inheritPortsFrom(ctx)} (EvalContext §四 port-family
 * inheritance, semantically those 11 items); the per-tick {@code setAudioTickStamp}
 * refresh, {@code setCompletedNodeId} mirror, {@code restoreSubState} attach and
 * debugTime restore keep their original semantics.</p>
 */
public final class EncapEval {

    private EncapEval() {}

    /** 求值结构域节点（case 体逐字搬迁，见类注释）。方法开头放局部别名块（接收者改写），
     *  让 case 体一个字都不用改；结尾 {@code ctx.o = o} 回写输出数组 —— ENCAPSULATION
     *  体内 {@code o = new float[nOut]; node.outputValues = o;} 重建，调用方尾部记账
     *  直读 ctx.o 同步。
     *  Evaluate a structure-domain node (case bodies relocated verbatim, see the class
     *  doc). The method opens with local aliases (receiver rewrite) so the case bodies
     *  stay untouched; it ends with a {@code ctx.o = o} write-back of the output array —
     *  ENCAPSULATION rebuilds it in-body as {@code o = new float[nOut]; node.outputValues = o;},
     *  which the caller's tail bookkeeping picks up by reading ctx.o.
     *
     * @param ctx 求值上下文（每调用/每节点槽已由调用方填好）/ evaluation context
     *            (per-call / per-node slots already filled by the caller)
     */
    static void eval(EvalContext ctx) {
        // 方法内局部别名（接收者改写）：case 体逐字保留 / local aliases (receiver rewrite):
        // the case bodies stay verbatim
        GraphNode node = ctx.node;
        float[] o = ctx.o;
        List<GraphEvaluator.InputSource> inputs = ctx.inputs;
        Map<Integer, Float> pidState = ctx.pidState;
        float dt = ctx.dt;
        GraphEvaluator.SeatInputState seat = ctx.seat;
        NodeGraph graph = ctx.graph;
        Map<Integer, float[]> outputs = ctx.outputs;
        RuntimeState runtimeState = ctx.runtimeState;
        Map<Integer, GraphEvaluator> subEvaluators = ctx.subEvaluators;
        Map<Integer, Integer> subGraphGenerations = ctx.subGraphGenerations;
        Map<Integer, Map<Integer, java.util.ArrayDeque<Float>>> subDelayQueues = ctx.subDelayQueues;
        Map<Integer, Map<Integer, Boolean>> subFlipflopStates = ctx.subFlipflopStates;
        Map<Integer, Map<Integer, Integer>> subPulseTimers = ctx.subPulseTimers;
        switch (node.type) {
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
                    // 注入清单收敛为 EvalContext.inheritPortsFrom（原 11 个 setter 的
                    // 一处继承；端口族 + radarPos / targetLookup / audioTickStamp）。
                    // The injection list converges to EvalContext.inheritPortsFrom (one
                    // inheritance for the former 11 setters; the port family plus
                    // radarPos / targetLookup / audioTickStamp).
                    subEval.ctx.inheritPortsFrom(ctx);
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
        ctx.o = o;
    }
}
