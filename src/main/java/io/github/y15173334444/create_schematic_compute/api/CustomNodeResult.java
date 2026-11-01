package io.github.y15173334444.create_schematic_compute.api;

import net.minecraft.nbt.CompoundTag;

/**
 * 一次自定义节点求值的返回（本模组公开 API 的组成部分）：完成（写输出）或挂起（可恢复重节点的
 * 检查点）。值类型，工厂方法收敛三种合法形态 —— 其余状态（null 结果、抛异常）由
 * 求值器按「本次空转、输出保持」的 fail-closed 口径处理。
 * One custom-node evaluation's return (part of this mod's public API): completion (writes outputs)
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
     *  semantics — pass it back via {@link #doneWithState(float[], CompoundTag)} to keep it).</p>
     *  @param outputs 输出值，按 schema 输出引脚顺序（多出的忽略、缺少的补 0；null 视为空）/
     *                 output values in schema output order (extras ignored, missing ones read 0;
     *                 null reads as empty)
     *  @return 完成结果 / the completion result */
    public static CustomNodeResult done(float... outputs) {
        return new CustomNodeResult(outputs == null ? new float[0] : outputs.clone(), null, false, true);
    }

    /** 完成并写输出 + 替换既存状态（{@code newState} 为 null 等价 {@link #done}）。新状态
     *  同受 {@link #suspended} 所述的 32768 字符帽约束（超帽拒收、既存状态保持）。
     *  <p>Complete with outputs and replace the stored state ({@code null} newState equals
     *  {@link #done}). The new state is bound by the same 32768-character cap described on
     *  {@link #suspended} (over the cap it is refused and the stored state is kept).</p>
     *  @param outputs  输出值，同 {@link #done} / output values, as in {@link #done}
     *  @param newState 替换既存状态的新状态（null 等价 {@link #done}）/ the state replacing the
     *                  stored one (null equals {@link #done})
     *  @return 完成结果 / the completion result */
    public static CustomNodeResult doneWithState(float[] outputs, CompoundTag newState) {
        if (newState == null) return done(outputs);
        return new CustomNodeResult(outputs == null ? new float[0] : outputs.clone(), newState.copy(), false, false);
    }

    /**
     * 可恢复重节点挂起：输出保持上一次完成值、不计完成；检查点随宿主的运行时状态
     * 持久化（存档/重载与图重编译后保留，节点删除即丢弃），下一 tick 以其为本节点
     * {@link CustomNodeContext#state()} 续算。<b>超出自身时间片</b>的挂起才计入独立预算池
     * 的重节点份额（slice = 预算 / max(1, 上 tick 此类挂起数)）；提前的协作挂起不计。
     * 检查点（SNBT 文本）超过 32768 字符即拒收：既存检查点保持、本次不落表，并记节流告警。
     * <p>Suspend a resumable heavy node: outputs hold the last completed values, no
     * completion this tick; the checkpoint persists with the host's runtime state (kept
     * across save/reload and graph recompiles, dropped with the node) and arrives back as
     * {@link CustomNodeContext#state()} next tick. Only a suspension that <b>overran its own
     * slice</b> feeds the independent pool's heavy share (slice = budget / max(1, last
     * tick's such suspensions)); an early cooperative suspend does not. A checkpoint whose
     * SNBT text exceeds 32768 characters is refused: the stored one is kept, the new one is
     * not stored, and a throttled warning is logged.</p>
     * @param checkpoint 检查点（null 视为空 tag）/ the checkpoint (null reads as an empty tag)
     * @return 挂起结果 / the suspension result
     */
    public static CustomNodeResult suspended(CompoundTag checkpoint) {
        return new CustomNodeResult(null, checkpoint == null ? new CompoundTag() : checkpoint.copy(), true, false);
    }

    /** 输出数组（挂起结果为 null）。/ the outputs (null on a suspended result).
     *  @return 输出数组；挂起结果为 {@code null} / the outputs; {@code null} on a suspended result */
    public float[] outputs() { return outputs; }
    /** 新状态（null = 不动既存状态）。/ the new state (null = leave the stored state).
     *  @return 新状态；{@code null} 表示不动既存状态 / the new state; {@code null} leaves the stored state */
    public CompoundTag newState() { return newState; }
    /** 是否挂起结果。/ whether this is a suspension.
     *  @return 挂起结果为 true / true for a suspension */
    public boolean isSuspended() { return suspended; }
    /** 完成时是否清除既存状态（{@link #done} 产出的结果为 true）。/ whether completion clears
     *  the stored state (true for results from {@link #done}).
     *  @return 清除为 true / true when the stored state is cleared */
    public boolean shouldClearState() { return clearState; }
}
