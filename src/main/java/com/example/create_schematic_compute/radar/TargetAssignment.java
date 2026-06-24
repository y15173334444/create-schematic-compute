package com.example.create_schematic_compute.radar;

import com.example.create_schematic_compute.graph.GraphNode;
import net.minecraft.core.BlockPos;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 连接 RadarBlockEntity 扫描结果与 GraphEvaluator 的静态桥梁。
 * RadarBlockEntity.tick() 写入 → GraphEvaluator.eval(TARGET_OUT) 读取。
 */
public class TargetAssignment {

    private static final ConcurrentHashMap<BlockPos, Map<Integer, TargetRecord>> ASSIGNMENTS = new ConcurrentHashMap<>();

    public static void clear(BlockPos radarPos) {
        ASSIGNMENTS.remove(radarPos);
    }

    public static void clearAll() {
        ASSIGNMENTS.clear();
    }

    /**
     * 将扫描目标分配给图中的 TARGET_OUT 节点。
     * @param targetOutNodes 图中所有 TARGET_OUT 节点（按 ID 排序）
     * @param targets 扫描到的目标列表（已按距离排序）
     * @param scanMode 0=多目标(轮流分配), 1=单目标(所有节点得最近目标)
     */
    public static void assign(BlockPos radarPos, List<GraphNode> targetOutNodes,
                              List<TargetRecord> targets, int scanMode, float lockDistance) {
        // 过滤锁定距离内的目标
        if (lockDistance > 0) targets = targets.stream().filter(t -> t.distance() >= lockDistance).toList();
        Map<Integer, TargetRecord> map = new HashMap<>();
        if (scanMode == 0) {
            // 多目标：轮流分配，每个节点对应不同目标
            for (int i = 0; i < targetOutNodes.size(); i++) {
                if (i < targets.size())
                    map.put(targetOutNodes.get(i).id, targets.get(i));
            }
        } else {
            // 单目标：所有节点得同一个最近目标
            TargetRecord closest = targets.isEmpty() ? null : targets.get(0);
            for (var n : targetOutNodes) {
                if (closest != null) map.put(n.id, closest);
            }
        }
        ASSIGNMENTS.put(radarPos, map);
    }

    /** GraphEvaluator 调用：获取指定节点分配的目标 */
    public static TargetRecord getTarget(BlockPos radarPos, int nodeId) {
        Map<Integer, TargetRecord> map = ASSIGNMENTS.get(radarPos);
        return map != null ? map.get(nodeId) : null;
    }

    /**
     * 手动锁定模式：只分配被锁定的目标
     */
    public static void assignLocked(BlockPos radarPos, List<GraphNode> targetOutNodes,
                                     List<TargetRecord> targets, Set<Integer> lockedIds, int scanMode, float lockDistance) {
        // 过滤锁定距离内的目标
        if (lockDistance > 0) targets = targets.stream().filter(t -> t.distance() >= lockDistance).toList();
        Map<Integer, TargetRecord> map = new HashMap<>();
        if (lockedIds.isEmpty() || targetOutNodes.isEmpty()) {
            ASSIGNMENTS.put(radarPos, map);
            return;
        }
        // 从扫描结果中筛选被锁定的目标
        List<TargetRecord> locked = new ArrayList<>();
        for (var t : targets) {
            if (lockedIds.contains(t.entityId())) locked.add(t);
        }
        if (scanMode == 1) {
            // 单目标：所有节点得同一个锁定目标（最近的）
            TargetRecord toAssign = locked.isEmpty() ? null : locked.get(0);
            for (var n : targetOutNodes) {
                if (toAssign != null) map.put(n.id, toAssign);
            }
        } else {
            // 多目标：锁定目标按距离顺序分配给各节点
            for (int i = 0; i < targetOutNodes.size(); i++) {
                if (i < locked.size())
                    map.put(targetOutNodes.get(i).id, locked.get(i));
            }
        }
        ASSIGNMENTS.put(radarPos, map);
    }
}
