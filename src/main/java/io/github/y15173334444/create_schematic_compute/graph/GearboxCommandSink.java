package io.github.y15173334444.create_schematic_compute.graph;

/**
 * 图求值器 → 齿轮箱 BE 的指令下沉接口。
 * Command sink from the graph evaluator into the hosting gearbox block entity.
 *
 * <p>求值器是纯求值器、不持有宿主引用；由齿轮箱 BE 实现 本接口并在每次重建求值器后
 * 经 {@code setCommandSink} 注入（radarPos 同款范式）。MOVE/ROTATE/WAIT 节点在
 * 触点上升沿调用 {@link #enqueue} 产生副作用。</p>
 *
 * <p>The evaluator stays pure and host-free; the gearbox implements this and is
 * injected via {@code setCommandSink} after every evaluator rebuild (same pattern
 * as radarPos). MOVE/ROTATE/WAIT nodes call {@link #enqueue} on rising edges.</p>
 */
public interface GearboxCommandSink {

    /** 触点上升沿入队一条运动指令（调用方保证边沿去重）。 Enqueue one motion command (caller dedups edges). */
    void enqueue(MotionCommand command);

    /** 急停：清空栈、目标归零、复位到 IDLE。 Emergency stop: clear stack, zero output, back to IDLE. */
    void emergencyStop();
}
