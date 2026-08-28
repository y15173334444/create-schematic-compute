package io.github.y15173334444.create_schematic_compute.graph;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayDeque;

/**
 * 运动指令数据对象 —— 可编程齿轮箱指令栈的元素。
 * Motion command data object — one element of the gearbox command stack.
 *
 * <p>由 MOVE/ROTATE/WAIT 图节点在触点上升沿采样入队；数值在入队当帧快照，
 * 之后不随引脚变化。</p>
 * <p>Sampled by MOVE/ROTATE/WAIT graph nodes on the trigger's rising edge; the
 * value is snapshotted at enqueue time and never follows the pin afterwards.</p>
 *
 * @param kind         指令类型（复用 NodeType.ROTATE/MOVE/WAIT）/ command kind
 * @param value        ROTATE=度、MOVE=米、WAIT=tick / degrees, meters, or ticks
 * @param rpm          指令级输出转速；0 = 回落输入轴转速 / command-level RPM; 0 = fall back to input shaft
 * @param sourceNodeId 入队节点 ID（完成脉冲回写目标）/ enqueueing node id (done-pulse target)
 */
public record MotionCommand(NodeType kind, float value, float rpm, int sourceNodeId) {

    /** 栈容量上限（防高频触发无限积压）。 Max stack depth (anti-flood). */
    public static final int MAX_STACK = 64;

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putString("k", kind.id);
        t.putFloat("v", value);
        t.putFloat("r", rpm);
        t.putInt("src", sourceNodeId);
        return t;
    }

    public static MotionCommand load(CompoundTag t) {
        NodeType kind = NodeType.BY_ID.get(t.getString("k"));
        if (kind == null) kind = NodeType.WAIT;
        return new MotionCommand(kind, t.getFloat("v"), t.getFloat("r"), t.getInt("src"));
    }

    /** 栈序列化/恢复辅助。 Stack list (de)serialisation helpers. */
    public static ListTag saveStack(ArrayDeque<MotionCommand> stack) {
        ListTag list = new ListTag();
        for (MotionCommand c : stack) list.add(c.save());
        return list;
    }

    public static void loadStack(ListTag list, ArrayDeque<MotionCommand> into) {
        into.clear();
        for (int i = 0; i < list.size(); i++) {
            Tag t = list.get(i);
            if (t instanceof CompoundTag ct) into.addLast(load(ct));
        }
    }
}
