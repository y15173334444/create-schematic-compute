package io.github.y15173334444.create_schematic_compute.graph;

import java.util.Map;

/**
 * 运动域处理器（决策文档 §四）：GEARBOX / TRANSMISSION / SPEED / KINETIC —— 运动指令与
 * 动力读数（MOVE/ROTATE/WAIT 共享 case、CLUTCH、ENCODER、TX_OUT、SPEED_CTRL、STRESS、RPM）。
 * case 体从 {@link GraphEvaluator} 的 eval() switch 逐字搬迁（§三-2：方法体不改写，只改
 * 接收者/归属）；本类是静态函数包，不建继承体系（§三-3）。
 * <p>Motion-domain handler (decision doc §四): GEARBOX / TRANSMISSION / SPEED /
 * KINETIC — motion commands and kinetic readouts (the shared MOVE/ROTATE/WAIT case,
 * CLUTCH, ENCODER, TX_OUT, SPEED_CTRL, STRESS, RPM). Case bodies are relocated
 * verbatim from {@link GraphEvaluator}'s eval() switch (§三-2: bodies are never
 * rewritten, only their owner changes); this class is a static function pack, no
 * inheritance hierarchy (§三-3).</p>
 */
public final class MotionEval {

    private MotionEval() {}

    /** 求值运动域节点（case 体逐字搬迁，见类注释）。方法开头放局部别名块（接收者改写），
     *  让 case 体一个字都不用改；结尾 {@code ctx.o = o} 回写输出数组（通用契约）。
     *  Evaluate a motion-domain node (case bodies relocated verbatim, see the class doc).
     *  The method opens with local aliases (receiver rewrite) so the case bodies stay
     *  untouched; it ends with a {@code ctx.o = o} write-back of the output array (the
     *  general contract).
     *
     * @param ctx 求值上下文（每调用/每节点槽已由调用方填好）/ evaluation context
     *            (per-call / per-node slots already filled by the caller)
     */
    static void eval(EvalContext ctx) {
        // 方法内局部别名（接收者改写）：case 体逐字保留 / local aliases (receiver rewrite):
        // the case bodies stay verbatim
        GraphNode node = ctx.node;
        float[] o = ctx.o;
        NodeGraph graph = ctx.graph;
        Map<Integer, float[]> outputs = ctx.outputs;
        KineticEncoderView encoderView = ctx.encoderView;
        KineticNetworkView kineticNetworkView = ctx.kineticNetworkView;
        GearboxCommandSink commandSink = ctx.commandSink;
        RuntimeState runtimeState = ctx.runtimeState;
        Integer completedNodeId = ctx.completedNodeId;
        switch (node.type) {
            case SPEED_CTRL -> {
                float speed = graph.getInputValue(node.id, 0, outputs);
                float dir = graph.getInputValue(node.id, 1, outputs);
                // dir 引脚与 rev 参数（正/反转按钮）同号才反：两个开关都取反 = 恢复正向。
                // dir pin XOR rev toggle: both on = forward again.
                float sign = (dir > 0.5f ? -1f : 1f)
                    * (node.params.length > 0 && node.params[0] > 0.5f ? -1f : 1f);
                o[0] = sign * speed;
            }
            case TX_OUT -> {
                // 透传期望输出转速；rev 参数（正/反转按钮）在结果上取反。
                // 宿主变速器在目标变更时经官方拆建序列写入 —— 求值器无副作用。
                // Passthrough desired output speed; the rev toggle negates the result.
                // The host transmission writes it via the official teardown on target
                // change — the evaluator itself has no side effects.
                float rpm = graph.getInputValue(node.id, 0, outputs);
                if (node.params.length > 0 && node.params[0] > 0.5f)
                    rpm = -rpm;
                o[0] = rpm;
            }
            case CLUTCH -> {
                // 常接合意图（>0.5 视为接合）：参数 EditBox 为默认值，连线覆盖。
                // Standing clutch intent (>0.5 = engaged): the param EditBox is the
                // default, a wire overrides it. The write happens in the host clutch
                // state machine.
                float v = graph.getInputValueOrDefault(node.id, 0, outputs,
                        node.params.length > 0 ? node.params[0] : 0f);
                o[0] = Float.isFinite(v) && v > 0.5f ? 1f : 0f;
            }
            case ENCODER -> {
                // 读运动反馈：角度位置（度）、线性位置（米）、实际转速（RPM）。
                // 清零 = 节点体 0 号输入引脚，电平触发：拉高即清零累计（持续拉高 =
                // 持续保持零）；未接线 = 0（不清零）。旧编辑区 reset 参数连线由
                // V6 迁移钉到该引脚。
                // Motion feedback: angle (deg), linear position (m), actual speed (RPM).
                // Reset is body input pin 0, level-triggered: high = zero the
                // accumulators (held high = held at zero); unwired = 0 (no reset).
                // Old edit-area "reset" param wires are pinned here by the V6 migration.
                float rst = graph.getInputValue(node.id, 0, outputs);
                if (encoderView != null && Float.isFinite(rst) && rst > 0.5f)
                    encoderView.resetEncoder();
                o[0] = encoderView != null ? encoderView.encoderPosition() : 0;
                o[1] = encoderView != null ? encoderView.encoderPositionMeters() : 0;
                o[2] = encoderView != null ? encoderView.encoderVelocity() : 0;
            }
            case STRESS -> {
                // 读动力网络应力：占比（0..1）、已用（SU）、未用（0..1）、剩余（SU）。
                // 无网络/容量为 0 时全部输出 0（除以容量前先判零，且未用/剩余在无
                // 网络时置 0 而非容量值 —— 「没有网络」不能看起来像「满容量空闲」）。
                // Kinetic network stress: ratio (0..1), used (SU), unused (0..1),
                // remaining (SU). All zero without a network / zero capacity (guard the
                // division, and zero unused/remaining when offline — "no network" must
                // not look like "full idle capacity").
                float used = kineticNetworkView != null ? Math.max(0f, kineticNetworkView.kineticStress()) : 0f;
                float cap = kineticNetworkView != null ? Math.max(0f, kineticNetworkView.kineticCapacity()) : 0f;
                if (cap <= 0f) {
                    o[0] = 0; o[1] = 0; o[2] = 0; o[3] = 0;
                } else {
                    float ratio = Math.min(used / cap, Float.MAX_VALUE);
                    o[0] = ratio;
                    o[1] = used;
                    o[2] = Math.max(0f, 1f - ratio);
                    o[3] = Math.max(0f, cap - used);
                }
            }
            case RPM -> {
                // 读网络转速（RPM，带符号；过载/无动力时为 0，与 ENCODER vel 同语义）。
                // Network rotation speed (RPM, signed; 0 when overstressed/unpowered —
                // same semantics as ENCODER's vel pin).
                o[0] = kineticNetworkView != null ? kineticNetworkView.kineticSpeed() : 0;
            }
            case MOVE, ROTATE, WAIT -> {
                // 触点上升沿 → 采样数值/rpm 入队；完成脉冲经 completedNodeId 一帧回放。
                // Rising edge → snapshot value/rpm and enqueue; done pulse replays via completedNodeId.
                float trig = graph.getInputValue(node.id, 0, outputs);
                boolean cur = trig > 0.5f;
                boolean prev = runtimeState != null && runtimeState.nodeEdge.getOrDefault(node.id, false);
                if (cur && !prev && commandSink != null) {
                    // 数值：参数 EditBox 为默认值，连线覆盖（上升沿当帧快照）。
                    // rev 按钮独立于数值（不改 EditBox）：入栈时对快照值取反。
                    // Value: param EditBox default, wire overrides (snapshotted at edge).
                    // The rev toggle is independent of the value (does not touch the
                    // EditBox): negates the snapshotted value at enqueue.
                    float val = graph.getInputValueOrDefault(node.id, 1, outputs,
                            node.params.length > 0 ? node.params[0] : 0f);
                    if (node.params.length > 1 && node.params[1] > 0.5f)
                        val = -val;
                    float rpmIn = node.type.inputs > 2 ? graph.getInputValue(node.id, 2, outputs) : 0f;
                    commandSink.enqueue(new MotionCommand(node.type, val, rpmIn, node.id));
                }
                if (runtimeState != null) runtimeState.nodeEdge.put(node.id, cur);
                o[0] = completedNodeId != null && completedNodeId == node.id ? 1f : 0f;
            }
        }
        ctx.o = o;
    }
}
