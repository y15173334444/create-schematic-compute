package io.github.y15173334444.create_schematic_compute.graph;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 图评估结果的不可变快照。
 * Immutable snapshot of a graph evaluation result.
 *
 * <p>在服务端每次调用 {@link GraphEvaluator#evaluate} 后创建。快照通过
 * {@code ClientboundGraphEvalPacket} 广播给追踪客户端，因此客户端永远不需要
 * 运行自己的 {@link GraphEvaluator}。
 * Created on the server after each call to {@link GraphEvaluator#evaluate}.
 * The snapshot is broadcast to tracking clients via {@code ClientboundGraphEvalPacket}
 * so the client never needs to run its own {@link GraphEvaluator}.</p>
 *
 * <p>此方案替代了之前的架构 — 在该架构中客户端每帧创建本地 GraphEvaluator 并调用
 * evaluate()，而该方法从静态 {@code SignalBus} 读取数据，但 SignalBus 在客户端
 * 始终为空，导致 PRIVATE_IN 和 BUS_IN 始终返回 0。
 * This replaces the previous architecture where the client created a local
 * GraphEvaluator and called evaluate() every frame — which read from the
 * static {@code SignalBus} that is always empty on the client,
 * causing PRIVATE_IN and BUS_IN to always return 0.</p>
 */
public record EvalSnapshot(Map<Integer, float[]> outputs, Map<Integer, Float> debugTimes) {

    public static final EvalSnapshot EMPTY = new EvalSnapshot(Collections.emptyMap(), Collections.emptyMap());

    /** 从此快照中读取单个输出值（不存在时返回 0）。
     *  Read a single output value from this snapshot (0 if not present). */
    public float get(int nodeId, int pinIndex) {
        float[] out = outputs.get(nodeId);
        if (out == null || pinIndex < 0 || pinIndex >= out.length) return 0;
        return out[pinIndex];
    }

    /** 从此快照中读取 DEBUG_SIGNAL_GEN 的当前 debugTime（不存在时返回 0）。
     *  Read the current debugTime for a DEBUG_SIGNAL_GEN node (0 if not present). */
    public float getDebugTime(int nodeId) {
        return debugTimes.getOrDefault(nodeId, 0f);
    }

    /** 输出映射的不可修改视图。 / Unmodifiable view of the output map. */
    @Override
    public Map<Integer, float[]> outputs() {
        return Collections.unmodifiableMap(outputs);
    }

    /** 从可变输出映射创建快照（防御性拷贝）。
     *  Create a snapshot from a mutable outputs map (defensive copy). */
    public static EvalSnapshot capture(Map<Integer, float[]> outputs, Map<Integer, Float> debugTimes) {
        if (outputs.isEmpty() && (debugTimes == null || debugTimes.isEmpty())) return EMPTY;
        var copy = new HashMap<Integer, float[]>(outputs.size());
        for (var e : outputs.entrySet()) {
            copy.put(e.getKey(), e.getValue().clone());
        }
        var dtCopy = (debugTimes != null && !debugTimes.isEmpty())
            ? new HashMap<>(debugTimes) : Collections.<Integer, Float>emptyMap();
        return new EvalSnapshot(Collections.unmodifiableMap(copy), Collections.unmodifiableMap(dtCopy));
    }
}
