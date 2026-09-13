package io.github.y15173334444.create_schematic_compute.graph;

/**
 * STRESS / RPM 节点的宿主视图：求值器通过它读取宿主动力方块实体所在的
 * Create 动力网络读数（转速 + 应力）。
 * Host view for the STRESS / RPM nodes: the evaluator reads the Create kinetic
 * network readings (speed + stress) of the hosting kinetic block entity through
 * this interface.
 *
 * <p>与 {@link KineticEncoderView} 同范式的宿主注入 —— 求值器是纯求值器，
 * 不持有宿主或 Level 引用；由宿主方块实体在每次重建求值器后注入
 * （{@code GraphHost#setEvaluatorCustomizer}），并在 ENCAPSULATION 构建子求值器时
 * 向子求值器传播。数值按 tick 实时变化（网络合并/分裂、过载状态），因此这里
 * 暴露的是「活的读取器」而非快照。</p>
 *
 * <p>Same host-injection pattern as {@link KineticEncoderView} — the evaluator is a
 * pure evaluator holding no host or Level reference; the hosting block entity injects
 * the view after every evaluator rebuild (via {@code GraphHost#setEvaluatorCustomizer})
 * and it is propagated into sub-evaluators built for ENCAPSULATION. Values change per
 * tick (network merge/split, overload state), so this is a live reader, not a snapshot.</p>
 *
 * <p>读数语义与 Create 的 {@code KineticBlockEntity} 一致：过载时转速读 0、
 * 应力照读（比值 &gt; 1 表达超载）；无网络时转速与应力/容量均为 0。</p>
 * <p>Semantics match Create's {@code KineticBlockEntity}: when overstressed the speed
 * reads 0 while stress keeps reading (ratio &gt; 1 expresses overload); without a
 * network speed and stress/capacity are all 0.</p>
 */
public interface KineticNetworkView {

    /** 网络转速（RPM，带符号；过载/无动力时为 0）。
     *  Network rotation speed (RPM, signed; 0 when overstressed / unpowered). */
    float kineticSpeed();

    /** 网络当前应力（SU，已用）。 Current network stress (SU, used). */
    float kineticStress();

    /** 网络应力容量（SU）。 Network stress capacity (SU). */
    float kineticCapacity();
}
