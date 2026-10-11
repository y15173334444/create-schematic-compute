package io.github.y15173334444.create_schematic_compute.radar;

import io.github.y15173334444.create_schematic_compute.graph.GraphNode;
import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import io.github.y15173334444.create_schematic_compute.graph.TargetRecord;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TargetAssignment 静态表回归（此前零测试，审查工程面登记项）。覆盖：
 * <ul>
 *   <li>多目标轮流分配 / 单目标全节点同目标；</li>
 *   <li>锁定距离过滤（lockDistance 内的目标不参与分配）；</li>
 *   <li>手动锁定分配（assignLocked）与距离过滤组合；</li>
 *   <li>{@code clear}（方块拆除 / 区块卸载回收路径，P2-15/P3）与 {@code clearAll}
 *       （小停机路径）释放条目。</li>
 * </ul>
 * 全部纯 Java（BlockPos / GraphNode / TargetRecord），headless 直测。
 *
 * <p>TargetAssignment static-table regression (previously zero tests, a review
 * engineering-findings item). Covers: multi-target round-robin / single-target
 * same-closest, the lock-distance filter, manual-lock assignment, and {@code clear}
 * (block-removal / chunk-unload reclaim path, P2-15/P3) plus {@code clearAll}
 * (server-stop path). All plain Java — tested headlessly.</p>
 */
class TargetAssignmentTableTest {

    private static final BlockPos RADAR = new BlockPos(10, 64, 10);

    @BeforeEach
    @AfterEach
    void resetTable() { TargetAssignment.clearAll(); }

    private static List<GraphNode> targetOutNodes(int count) {
        var g = new NodeGraph();
        var nodes = new ArrayList<GraphNode>();
        for (int i = 0; i < count; i++) nodes.add(g.addNode(NodeType.TARGET_OUT, i * 30, 0));
        return nodes;
    }

    private static TargetRecord target(int id, double dist) {
        // 距离即 dist 字段（扫描中心距离，从近到远排序由调用方保证）
        // distance rides the record's dist field (caller keeps the list near→far sorted)
        return new TargetRecord(0, 0, 0, id, (float) dist, TargetRecord.TYPE_PLAYER, "t" + id);
    }

    @Test
    @DisplayName("多目标模式：TARGET_OUT 节点按序轮流分配不同目标")
    void multiTargetRoundRobin() {
        var nodes = targetOutNodes(3);
        var targets = List.of(target(101, 5f), target(102, 10f), target(103, 15f));

        TargetAssignment.assign(RADAR, nodes, targets, 0, 0f);

        assertEquals(101, TargetAssignment.getTarget(RADAR, nodes.get(0).id).entityId());
        assertEquals(102, TargetAssignment.getTarget(RADAR, nodes.get(1).id).entityId());
        assertEquals(103, TargetAssignment.getTarget(RADAR, nodes.get(2).id).entityId());
    }

    @Test
    @DisplayName("单目标模式：所有节点拿到同一个最近目标")
    void singleTargetAllNodesGetClosest() {
        var nodes = targetOutNodes(2);
        var targets = List.of(target(201, 3f), target(202, 8f));

        TargetAssignment.assign(RADAR, nodes, targets, 1, 0f);

        assertEquals(201, TargetAssignment.getTarget(RADAR, nodes.get(0).id).entityId());
        assertEquals(201, TargetAssignment.getTarget(RADAR, nodes.get(1).id).entityId());
    }

    @Test
    @DisplayName("锁定距离过滤：lockDistance 内的目标不参与自动分配")
    void lockDistanceFiltersTargets() {
        var nodes = targetOutNodes(2);
        var targets = List.of(target(301, 1f), target(302, 9f));

        TargetAssignment.assign(RADAR, nodes, targets, 0, 5f);

        assertEquals(302, TargetAssignment.getTarget(RADAR, nodes.get(0).id).entityId(),
            "dist=1 的目标应被 lockDistance=5 过滤掉");
        assertNull(TargetAssignment.getTarget(RADAR, nodes.get(1).id), "过滤后只剩 1 个目标，第二个节点应为空");
    }

    @Test
    @DisplayName("手动锁定：只分配被锁定的目标，未锁定不分配")
    void assignLockedDistributesLockedOnly() {
        var nodes = targetOutNodes(2);
        var targets = List.of(target(401, 2f), target(402, 6f), target(403, 9f));

        TargetAssignment.assignLocked(RADAR, nodes, targets, Set.of(402, 403), 0, 0f);

        assertEquals(402, TargetAssignment.getTarget(RADAR, nodes.get(0).id).entityId());
        assertEquals(403, TargetAssignment.getTarget(RADAR, nodes.get(1).id).entityId());
    }

    @Test
    @DisplayName("手动锁定 + 锁定距离：过滤后锁定的近目标同样不分配")
    void assignLockedHonorsLockDistance() {
        var nodes = targetOutNodes(2);
        var targets = List.of(target(501, 1f), target(502, 9f));

        TargetAssignment.assignLocked(RADAR, nodes, targets, Set.of(501, 502), 0, 5f);

        assertEquals(502, TargetAssignment.getTarget(RADAR, nodes.get(0).id).entityId());
        assertNull(TargetAssignment.getTarget(RADAR, nodes.get(1).id));
    }

    @Test
    @DisplayName("clear 释放该坐标条目（方块拆除 / 区块卸载回收路径）")
    void clearReleasesTableEntry() {
        var nodes = targetOutNodes(1);
        TargetAssignment.assign(RADAR, nodes, List.of(target(601, 5f)), 0, 0f);
        assertNotNull(TargetAssignment.getTarget(RADAR, nodes.get(0).id));

        TargetAssignment.clear(RADAR);

        assertNull(TargetAssignment.getTarget(RADAR, nodes.get(0).id), "clear 后不得残留分配");
    }

    @Test
    @DisplayName("clearAll 清空全表；未知坐标查询返回 null")
    void clearAllAndUnknownPosAreNullSafe() {
        var nodes = targetOutNodes(1);
        TargetAssignment.assign(RADAR, nodes, List.of(target(701, 5f)), 0, 0f);

        assertNull(TargetAssignment.getTarget(new BlockPos(99, 99, 99), nodes.get(0).id),
            "未分配过的坐标查询应返回 null");

        TargetAssignment.clearAll();
        assertNull(TargetAssignment.getTarget(RADAR, nodes.get(0).id));
    }
}
