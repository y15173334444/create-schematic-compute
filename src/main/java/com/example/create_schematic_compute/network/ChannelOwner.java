package com.example.create_schematic_compute.network;

import net.minecraft.core.BlockPos;

/** 唯一标识一个 BUS_OUT 节点：所属方块位置 + 图中节点 ID */
public record ChannelOwner(BlockPos pos, int nodeId) {
    @Override
    public String toString() {
        return "[" + pos.toShortString() + "]#" + nodeId;
    }
}
