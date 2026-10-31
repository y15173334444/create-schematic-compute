package io.github.y15173334444.create_schematic_compute.client.editor;

import net.minecraft.core.BlockPos;
import java.util.Map;

/** 图宿主 BE 面向编辑器家族的**窄视图**（反向包边解环，2026-10-31 拍板）：编辑器侧只需要
 *  本地编辑 op 记账（回弹保护计数）、方块坐标与封装子图触发器状态——不再点名
 *  {@code blocks.GraphBlockEntity} 具体类型（E2 {@code EditAckSink} 同款缝-实现分离手法，
 *  窄口住消费方包）。{@code blocks.GraphBlockEntity} 继承本口，宿主 BE 类随行实现。
 *  Narrow view of a graph-host BE for the editor family (reverse package-edge cycle
 *  break, 2026-10-31 ruling): the editor side only needs the local edit-op ledger
 *  (bounce-back guard), the block position and sub-graph flipflop state — it no longer
 *  names {@code blocks.GraphBlockEntity} (the E2 {@code EditAckSink} playbook: the port
 *  lives in the consumer's package). {@code blocks.GraphBlockEntity} extends this port;
 *  host BE classes ride along.
 */
public interface EditStateView {

    /** 方块坐标 / the block position. */
    BlockPos getBlockPos();

    /** 未 ACK 的本地编辑 op 数（回弹保护计数）/ un-ACKed local edit ops (bounce-back guard). */
    int getPendingLocalOps();

    /** 设置未 ACK 的本地编辑 op数 / set the un-ACKed local edit op count. */
    void setPendingLocalOps(int pendingLocalOps);

    /** 当前封装子图的触发器状态（服务端同步）；默认空。
     *  Sub-graph flipflop states for the current encapsulation (synced); empty by default. */
    default Map<Integer, Boolean> peekSubStateFlipflops(int encapNodeId) {
        return java.util.Collections.emptyMap();
    }
}
