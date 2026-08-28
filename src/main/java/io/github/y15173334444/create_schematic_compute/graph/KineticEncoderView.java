package io.github.y15173334444.create_schematic_compute.graph;

/**
 * ENCODER 节点的宿主视图：求值器通过它读取齿轮箱实体的运动反馈。
 * Host view for the ENCODER node: the evaluator reads the kinetic gearbox's
 * motion feedback through this interface.
 *
 * <p>与 {@code setRadarPos(BlockPos)} 同范式的宿主注入 —— 求值器是纯求值器，
 * 不持有宿主引用；由宿主方块实体在每次重建求值器后注入。注入的视图按 tick
 * 实时变化（位置积分推进、转速跟随网络），因此这里暴露的是「活的读取器」
 * 而非快照值。</p>
 *
 * <p>Same host-injection pattern as {@code setRadarPos(BlockPos)} — the evaluator is
 * a pure evaluator with no knowledge of its host; the hosting block entity injects the
 * view after every evaluator rebuild. The values change per tick (position integration,
 * speed follows the network), so this is a live reader, not a snapshot.</p>
 */
public interface KineticEncoderView {

    /** 当前累计角度位置（度，0-360 归一）。
     *  Accumulated angular position (degrees, normalized 0-360). */
    float encoderPosition();

    /** 当前累计线性位置（米）。
     *  Accumulated linear position (meters). */
    float encoderPositionMeters();

    /** 输出轴实际转速（RPM，带符号；过载/无动力时为 0）。
     *  Actual output-shaft speed (RPM, signed; 0 when overstressed / unpowered). */
    float encoderVelocity();

    /** 复位累计状态（角度与线性位置同时清零）。由 ENCODER 节点的复位引脚电平触发。
     *  Reset the accumulated state (angular AND linear positions to zero). Level-
     *  triggered by the ENCODER node's reset pin: held high = held at zero. */
    void resetEncoder();
}
