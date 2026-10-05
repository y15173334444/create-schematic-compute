package io.github.y15173334444.create_schematic_compute.graph;

import net.minecraft.core.BlockPos;

/**
 * 雷达目标查询（接口下沉）：TARGET_OUT 读节点当前分配的目标——graph 不直连
 * {@code radar.TargetAssignment}，由宿主雷达注入实现。
 * <p>Radar target lookup (interface sinking): TARGET_OUT reads a node's currently
 * assigned target — the graph package never touches {@code radar.TargetAssignment};
 * the hosting radar injects the implementation.</p>
 */
public interface TargetLookup {

    /** 节点当前分配的目标（null = 无目标/未接线）。 / the node's currently assigned target
     *  (null = no target / unwired). */
    TargetRecord get(BlockPos radarPos, int nodeId);
}
