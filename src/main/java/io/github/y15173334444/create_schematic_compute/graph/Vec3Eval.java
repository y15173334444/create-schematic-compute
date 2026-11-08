package io.github.y15173334444.create_schematic_compute.graph;

import java.util.Map;

/**
 * 向量域处理器（v1.3.0）：VECTOR —— VEC3_CONST / JOIN / SPLIT / ADD / SUB / SCALE /
 * DOT / CROSS / LENGTH / NORMALIZE。向量载荷经 {@link EvalContext#payloadRefs} 旁路
 * （{@link PinType#VEC3}），标量引脚走浮点输出数组；上游缺载荷按 {@link Vec3f#ZERO}。
 * <p>Vector-domain handler (v1.3.0): VECTOR — the VEC3 core math set. Vector payloads
 * ride the {@link EvalContext#payloadRefs} side channel ({@link PinType#VEC3}), scalar
 * pins ride the float output arrays; a missing upstream payload reads as
 * {@link Vec3f#ZERO}.</p>
 */
public final class Vec3Eval {

    private Vec3Eval() {}

    /** 求值向量域节点。结尾 {@code ctx.o = o} 回写输出数组。
     *  Evaluate a vector-domain node. Ends with the {@code ctx.o = o} write-back. */
    static void eval(EvalContext ctx) {
        GraphNode node = ctx.node;
        float[] o = ctx.o;
        NodeGraph graph = ctx.graph;
        Map<Integer, float[]> outputs = ctx.outputs;
        Map<Long, PinPayload> payloadRefs = ctx.payloadRefs;
        switch (node.type) {
            case VEC3_CONST -> {
                float[] p = node.params;
                float x = p.length > 0 ? p[0] : 0f;
                float y = p.length > 1 ? p[1] : 0f;
                float z = p.length > 2 ? p[2] : 0f;
                payloadRefs.put(EvalContext.payloadKey(node.id, 0), new Vec3f(x, y, z));
                o[0] = 0; // 向量引脚经 payloadRefs（类型化载荷，非浮点）
            }
            case VEC3_JOIN -> {
                payloadRefs.put(EvalContext.payloadKey(node.id, 0), new Vec3f(
                    graph.getInputValue(node.id, 0, outputs),
                    graph.getInputValue(node.id, 1, outputs),
                    graph.getInputValue(node.id, 2, outputs)));
                o[0] = 0;
            }
            case VEC3_SPLIT -> {
                Vec3f v = vecIn(graph, node, payloadRefs);
                o[0] = v.x();
                o[1] = v.y();
                o[2] = v.z();
            }
            case VEC3_ADD -> {
                payloadRefs.put(EvalContext.payloadKey(node.id, 0),
                    vecIn(graph, node, payloadRefs, 0).add(vecIn(graph, node, payloadRefs, 1)));
                o[0] = 0;
            }
            case VEC3_SUB -> {
                payloadRefs.put(EvalContext.payloadKey(node.id, 0),
                    vecIn(graph, node, payloadRefs, 0).sub(vecIn(graph, node, payloadRefs, 1)));
                o[0] = 0;
            }
            case VEC3_SCALE -> {
                float k = graph.getInputValue(node.id, 1, outputs);
                payloadRefs.put(EvalContext.payloadKey(node.id, 0),
                    vecIn(graph, node, payloadRefs, 0).scale(k));
                o[0] = 0;
            }
            case VEC3_DOT -> {
                o[0] = vecIn(graph, node, payloadRefs, 0).dot(vecIn(graph, node, payloadRefs, 1));
            }
            case VEC3_CROSS -> {
                payloadRefs.put(EvalContext.payloadKey(node.id, 0),
                    vecIn(graph, node, payloadRefs, 0).cross(vecIn(graph, node, payloadRefs, 1)));
                o[0] = 0;
            }
            case VEC3_LENGTH -> {
                o[0] = vecIn(graph, node, payloadRefs).length();
            }
            case VEC3_NORMALIZE -> {
                payloadRefs.put(EvalContext.payloadKey(node.id, 0),
                    vecIn(graph, node, payloadRefs).normalize());
                o[0] = 0;
            }
        }
        ctx.o = o;
    }

    /** 上游向量载荷（缺载荷 = {@link Vec3f#ZERO}），引脚 0 简写。 */
    private static Vec3f vecIn(NodeGraph graph, GraphNode node, Map<Long, PinPayload> payloadRefs) {
        return vecIn(graph, node, payloadRefs, 0);
    }

    /** 上游向量载荷（缺载荷 = {@link Vec3f#ZERO}）。 / the upstream vector payload
     *  (missing = {@link Vec3f#ZERO}). */
    private static Vec3f vecIn(NodeGraph graph, GraphNode node, Map<Long, PinPayload> payloadRefs, int pin) {
        Vec3f v = graph.getInputVec3(node.id, pin, payloadRefs);
        return v != null ? v : Vec3f.ZERO;
    }
}
