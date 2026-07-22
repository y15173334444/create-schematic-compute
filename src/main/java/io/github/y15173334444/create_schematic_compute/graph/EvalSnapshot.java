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
public record EvalSnapshot(Map<Integer, float[]> outputs, Map<Integer, Float> debugTimes,
                           Map<Integer, Map<Integer, float[]>> subOutputs,
                           Map<Integer, Map<Integer, Float>> subDebugTimes) {

    public static final EvalSnapshot EMPTY = new EvalSnapshot(
        Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap());

    /** 从此快照中读取单个输出值（不存在时返回 0）。
     *  Read a single output value from this snapshot (0 if not present). */
    public float get(int nodeId, int pinIndex) {
        float[] out = outputs.get(nodeId);
        if (out == null || pinIndex < 0 || pinIndex >= out.length) return 0;
        return out[pinIndex];
    }

    /** 从子图快照中读取单个输出值（不存在时返回 0）。
     *  encapId = 拥有该子图的 ENCAPSULATION 节点 ID。
     *  Read a single output value from a sub-graph snapshot (0 if not present).
     *  encapId = the ENCAPSULATION node ID that owns the sub-graph. */
    public float getSub(int encapId, int nodeId, int pinIndex) {
        Map<Integer, float[]> sub = subOutputs.get(encapId);
        if (sub == null) return 0;
        float[] out = sub.get(nodeId);
        if (out == null || pinIndex < 0 || pinIndex >= out.length) return 0;
        return out[pinIndex];
    }

    /** 从此快照中读取 DEBUG_SIGNAL_GEN 的当前 debugTime（不存在时返回 0）。
     *  Read the current debugTime for a DEBUG_SIGNAL_GEN node (0 if not present). */
    public float getDebugTime(int nodeId) {
        return debugTimes.getOrDefault(nodeId, 0f);
    }

    /** 从子图快照中读取 DEBUG_SIGNAL_GEN 的当前 debugTime（不存在时返回 0）。
     *  Read the current debugTime for a DEBUG_SIGNAL_GEN node in a sub-graph (0 if not present). */
    public float getSubDebugTime(int encapId, int nodeId) {
        Map<Integer, Float> sub = subDebugTimes.get(encapId);
        if (sub == null) return 0f;
        return sub.getOrDefault(nodeId, 0f);
    }

    /** 输出映射的不可修改视图。 / Unmodifiable view of the output map. */
    @Override
    public Map<Integer, float[]> outputs() {
        return Collections.unmodifiableMap(outputs);
    }

    /** 子图输出映射的不可修改视图。 / Unmodifiable view of the sub-output map. */
    public Map<Integer, Map<Integer, float[]>> subOutputs() {
        return Collections.unmodifiableMap(subOutputs);
    }

    /** 子图 debugTime 映射的不可修改视图。 / Unmodifiable view of the sub-debugTime map. */
    public Map<Integer, Map<Integer, Float>> subDebugTimes() {
        return Collections.unmodifiableMap(subDebugTimes);
    }

    /** 从可变输出映射创建快照（防御性拷贝）。
     *  Create a snapshot from a mutable outputs map (defensive copy). */
    public static EvalSnapshot capture(Map<Integer, float[]> outputs, Map<Integer, Float> debugTimes,
                                        Map<Integer, Map<Integer, float[]>> subOutputs,
                                        Map<Integer, Map<Integer, Float>> subDebugTimes) {
        if (outputs.isEmpty() && (debugTimes == null || debugTimes.isEmpty())
            && (subOutputs == null || subOutputs.isEmpty())
            && (subDebugTimes == null || subDebugTimes.isEmpty())) return EMPTY;
        var copy = new HashMap<Integer, float[]>(outputs.size());
        for (var e : outputs.entrySet()) {
            copy.put(e.getKey(), e.getValue().clone());
        }
        var dtCopy = (debugTimes != null && !debugTimes.isEmpty())
            ? new HashMap<>(debugTimes) : Collections.<Integer, Float>emptyMap();
        Map<Integer, Map<Integer, float[]>> subCopy;
        if (subOutputs == null || subOutputs.isEmpty()) {
            subCopy = Collections.emptyMap();
        } else {
            subCopy = new HashMap<>(subOutputs.size());
            for (var entry : subOutputs.entrySet()) {
                Map<Integer, float[]> inner = new HashMap<>(entry.getValue().size());
                for (var ie : entry.getValue().entrySet()) {
                    inner.put(ie.getKey(), ie.getValue().clone());
                }
                subCopy.put(entry.getKey(), Collections.unmodifiableMap(inner));
            }
            subCopy = Collections.unmodifiableMap(subCopy);
        }
        Map<Integer, Map<Integer, Float>> subDtCopy;
        if (subDebugTimes == null || subDebugTimes.isEmpty()) {
            subDtCopy = Collections.emptyMap();
        } else {
            subDtCopy = new HashMap<>(subDebugTimes.size());
            for (var entry : subDebugTimes.entrySet()) {
                subDtCopy.put(entry.getKey(),
                    Collections.unmodifiableMap(new HashMap<>(entry.getValue())));
            }
            subDtCopy = Collections.unmodifiableMap(subDtCopy);
        }
        return new EvalSnapshot(Collections.unmodifiableMap(copy), Collections.unmodifiableMap(dtCopy),
            subCopy, subDtCopy);
    }
}
