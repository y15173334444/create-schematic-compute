package io.github.y15173334444.create_schematic_compute.graph;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for the graph-level custom name ({@code NodeGraph.customName}):
 * NBT round-trip (the real sync chain is SET_BLOCK_NAME → markDirty → getUpdateTag →
 * save(server) → load(client)) and the {@link OpExecutor} SET_BLOCK_NAME branch
 * (visual-only op — must apply the name without bumping the graph generation).
 * 图级自定义名（{@code NodeGraph.customName}）的回归测试：NBT 往返（真实同步链为
 * SET_BLOCK_NAME → markDirty → getUpdateTag → save(服务端) → load(客户端)）与
 * {@link OpExecutor} 的 SET_BLOCK_NAME 分支（纯视觉 op —— 只落地名称、不递增代际）。
 *
 * <p>GraphOp is constructed directly with null ItemStack/BlockPos/UUID — the exercised
 * branches never touch those fields, and the factory methods reference ItemStack.EMPTY,
 * which NPEs outside the game environment (same pattern as OpGenerationTest).</p>
 */
class GraphCustomNameTest {

    private NodeGraph graph;

    @BeforeEach
    void setUp() {
        graph = new NodeGraph();
    }

    /** Build a GraphOp whose fields are only what the exercised branch reads.
     *  构造只含被测分支所需字段的 GraphOp。 */
    private static GraphOp op(OpType type, String stringValue) {
        return new GraphOp(type, null, -1, 0, 0, null, 0f, 0f,
            0, 0, 0, 0, 0, 0f, stringValue,
            0, 0, 0, 0, null, 0, 0, 0,
            null, 0L, null, 0, null);
    }

    @Test
    @DisplayName("round-trip preserves customName")
    void roundTripPreservesCustomName() {
        graph.customName = "齿轮箱 A";
        graph.addNode(NodeType.CONST, 0, 0);

        var tag = graph.save(null);
        var loaded = NodeGraph.load(tag, null);

        assertEquals("齿轮箱 A", loaded.customName);
    }

    @Test
    @DisplayName("empty name is not serialized; absent key loads back as empty")
    void emptyNameStaysOptionalKey() {
        graph.addNode(NodeType.CONST, 0, 0);
        assertEquals("", graph.customName);

        var tag = graph.save(null);
        // 可选键：空名不得写入 NBT / optional key: an empty name must not be written
        assertFalse(tag.contains("customName"));

        var loaded = NodeGraph.load(tag, null);
        assertEquals("", loaded.customName);
    }

    @Test
    @DisplayName("SET_BLOCK_NAME applies the name without bumping generation")
    void setBlockNameAppliesWithoutBump() {
        graph.addNode(NodeType.CONST, 0, 0);
        int g0 = graph.graphGeneration;

        OpExecutor.apply(graph, op(OpType.SET_BLOCK_NAME, "终端 3 号"));

        assertEquals("终端 3 号", graph.customName);
        assertEquals(g0, graph.graphGeneration,
            "SET_BLOCK_NAME is visual-only and must not bump the graph generation");
    }

    @Test
    @DisplayName("SET_BLOCK_NAME with null string clears the name")
    void setBlockNameNullClears() {
        graph.customName = "old";
        OpExecutor.apply(graph, op(OpType.SET_BLOCK_NAME, null));
        assertEquals("", graph.customName);
    }

    @Test
    @DisplayName("name survives a full round-trip after being set via the op")
    void opThenRoundTrip() {
        OpExecutor.apply(graph, op(OpType.SET_BLOCK_NAME, "persisted"));
        var tag = graph.save(null);
        var loaded = NodeGraph.load(tag, null);
        assertEquals("persisted", loaded.customName);
        assertEquals(List.of(), loaded.nodes, "op touches nothing else");
    }
}
