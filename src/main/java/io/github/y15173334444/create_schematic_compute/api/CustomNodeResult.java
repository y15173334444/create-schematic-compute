package io.github.y15173334444.create_schematic_compute.api;

import net.minecraft.nbt.CompoundTag;

/**
 * 一次自定义节点求值的返回（公开 API 波 1）：完成（写输出）或挂起（可恢复重节点的
 * 检查点）。值类型，工厂方法收敛三种合法形态 —— 其余状态（null 结果、抛异常）由
 * 求值器按「本次空转、输出保持」的 fail-closed 口径处理。
 * One custom-node evaluation's return (public-API wave 1): completion (writes outputs)
 * or suspension (a resumable heavy node's checkpoint). A value type whose factories
 * converge the three legal shapes; anything else (null, thrown) is handled by the
 * evaluator as "idle this call, outputs hold" — fail closed.
 */
public final class CustomNodeResult {

    private final float[] outputs;
    private final CompoundTag newState;   // null = 状态保持不变 / null = leave the stored state untouched
    private final boolean suspended;
    /** 完成但未携带新状态 → 清除已存检查点（工作流收尾的垃圾回收）。
     *  Completion without a new state clears the stored checkpoint (workflow-end GC). */
    private final boolean clearState;

    private CustomNodeResult(float[] outputs, CompoundTag newState, boolean suspended, boolean clearState) {
        this.outputs = outputs;
        this.newState = newState;
        this.suspended = suspended;
        this.clearState = clearState;
    }

    /** 完成并写输出：既存状态<b>清除</b>（工作流收尾语义 —— 需要保留就改用
     *  {@link #doneWithState(float[], CompoundTag)} 传回原状态）。
     *  <p>Complete with outputs: the stored state is <b>cleared</b> (workflow-end
     *  semantics — pass it back via {@link #doneWithState(float[], CompoundTag)} to keep it).</p> */
    public static CustomNodeResult done(float... outputs) {
        return new CustomNodeResult(outputs == null ? new float[0] : outputs.clone(), null, false, true);
    }

    /** 完成并写输出 + 替换既存状态（{@code newState} 为 null 等价 {@link #done}）。
     *  <p>Complete with outputs and replace the stored state ({@code null} newState equals {@link #done}).</p> */
    public static CustomNodeResult doneWithState(float[] outputs, CompoundTag newState) {
        if (newState == null) return done(outputs);
        return new CustomNodeResult(outputs == null ? new float[0] : outputs.clone(), newState.copy(), false, false);
    }

    /**
     * 可恢复重节点挂起（D2a）：输出保持 lastGood、不计完成；检查点随 RuntimeState
     * 纪律持久化，下一 tick 以其为本节点 {@link CustomNodeContext#state()} 续算。
     * 挂起计入独立预算池的重节点份额（slice = 预算 / max(1, 上 tick 挂起数)）。
     * <p>Suspend a resumable heavy node (D2a): outputs hold lastGood, no completion this
     * tick; the checkpoint persists under the RuntimeState discipline and arrives back as
     * {@link CustomNodeContext#state()} next tick. Suspensions feed the independent pool's
     * heavy share (slice = budget / max(1, last tick's suspensions)).</p>
     */
    public static CustomNodeResult suspended(CompoundTag checkpoint) {
        return new CustomNodeResult(null, checkpoint == null ? new CompoundTag() : checkpoint.copy(), true, false);
    }

    /** 输出数组（挂起结果为 null）。/ the outputs (null on a suspended result). */
    public float[] outputs() { return outputs; }
    /** 新状态（null = 不动既存状态）。/ the new state (null = leave the stored state). */
    public CompoundTag newState() { return newState; }
    public boolean isSuspended() { return suspended; }
    public boolean shouldClearState() { return clearState; }
}
