package io.github.y15173334444.create_schematic_compute.graph;

/** 非浮点引脚的**图内类型化载荷**（音频/向量等）——浮点仍走每节点输出数组（性能不动）。
 *  {@link EvalContext#payloadRefs} 以引脚键存载荷，{@link NodeGraph#getInputAudio} /
 *  {@link NodeGraph#getInputVec3} 按声明类型取用。新载荷类型 = 实现本接口 + {@link PinType} 加值。
 *  <p>The in-graph typed payload for non-float pins (audio, vector, ...) — floats still
 *  ride the per-node output arrays untouched. {@link EvalContext#payloadRefs} stores
 *  payloads by pin key; {@link NodeGraph#getInputAudio} / {@link NodeGraph#getInputVec3}
 *  read them by declared type. A new payload type = implement this interface + add a
 *  {@link PinType} value.</p>
 */
public sealed interface PinPayload permits AudioRef, Vec3f {
}
