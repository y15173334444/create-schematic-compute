package io.github.y15173334444.create_schematic_compute.graph;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F15 回归：未知 NodeType 不再静默降级 CONST——WARN 留痕（节点 id + 未知类型名）+
 * 节点级 {@code unknownTypeFallback} 降级标记；行为保持 CONST 旧档兼容口径不变。
 * <p>
 * 测试环境无 logback（SLF4J 无绑定），WARN 本身无法捕获断言，故按「可测的降级标记」
 * 口径验证：标记 + 降级行为 + NBT 自愈三件套。
 * <p>
 * F15 regression: an unknown NodeType no longer degrades to CONST silently — a WARN is
 * logged (node id + unknown type name) and the node-level {@code unknownTypeFallback}
 * marker is raised; the behaviour stays CONST with the legacy-save contract unchanged.
 * The test environment has no logback (no SLF4J binding), so the WARN itself cannot be
 * captured — the "testable degradation marker" route is used instead: marker + degraded
 * behaviour + NBT self-healing.
 */
class GraphNodeUnknownTypeTest {

    @Test
    @DisplayName("F15: 未知类型 NBT → 降级 CONST + unknownTypeFallback 标记亮起")
    void unknownTypeDegradesToConstWithMarker() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("id", 7);
        tag.putString("type", "ghost_type"); // 不是任何 NodeType.id / not any NodeType.id
        tag.putFloat("x", 1f);
        tag.putFloat("y", 2f);

        GraphNode node = GraphNode.load(tag, null);

        assertEquals(NodeType.CONST, node.type, "unknown types must still degrade to CONST (legacy-save contract)");
        assertTrue(node.unknownTypeFallback, "the degradation marker must be raised for observability");
        assertEquals(7, node.id, "the node id must survive the degradation");
    }

    @Test
    @DisplayName("F15: 已知类型正常加载,降级标记为 false")
    void knownTypeHasNoMarker() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("id", 3);
        tag.putString("type", NodeType.CONST.id);

        GraphNode node = GraphNode.load(tag, null);

        assertEquals(NodeType.CONST, node.type);
        assertFalse(node.unknownTypeFallback, "a known type must never raise the marker");
    }

    @Test
    @DisplayName("F15: 降级节点再保存写回 const,重载后标记复位(NBT 层面自愈)")
    void degradedNodeSelfHealsOnResave() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("id", 9);
        tag.putString("type", "removed_enum");

        GraphNode node = GraphNode.load(tag, null);
        assertTrue(node.unknownTypeFallback);

        CompoundTag resaved = node.save(null);
        assertEquals(NodeType.CONST.id, resaved.getString("type"),
            "the next save must write back 'const', completing the migration in effect");

        GraphNode reloaded = GraphNode.load(resaved, null);
        assertEquals(NodeType.CONST, reloaded.type);
        assertFalse(reloaded.unknownTypeFallback, "a reloaded const node is a normal node — marker reset");
    }
}
