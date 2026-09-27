package io.github.y15173334444.create_schematic_compute.graph;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OpExecutor 的 ADD_CONN / REMOVE_CONN pinId 路径直测：
 * 有 pinId 编码（含单端）→ 整条走 pinId，占用/解析失败静默跳过，不整数回落；
 * 无编码 → 整数引脚旧路径。
 * Direct tests for OpExecutor's ADD_CONN / REMOVE_CONN pinId path: any pinId encoding
 * (even one-sided) routes the whole wire through pinIds (occupied / unresolvable → skip
 * silently, never integer-fallback); no encoding → legacy integer path.
 */
class OpExecutorConnPinIdTest {

    private NodeGraph graph;
    private GraphNode a, b, c;

    @BeforeEach
    void setUp() {
        graph = new NodeGraph();
        a = graph.addNode(NodeType.CONST, 0, 0);
        b = graph.addNode(NodeType.ADD, 100, 0);
        c = graph.addNode(NodeType.CONST, 50, 50);
    }

    /** 22 参兼容构造；itemStack 置 null（CONN op 不读它，避免依赖 Minecraft 的 ItemStack.EMPTY）。 */
    private static GraphOp connOp(OpType type, int fromId, int fromPin, int toId, int toPin,
                                  String packedPinIds) {
        return new GraphOp(type, null, -1, 0,
            0, null, 0f, 0f, fromId, fromPin, toId, toPin, 0, 0f,
            packedPinIds, 0, 0, 0, 0, null, 0, 0, 0,
            null, 0L, null, 0, null);
    }

    @Test
    @DisplayName("ADD_CONN with pinIds: binds by pinId even if integer indices would differ")
    void testAddConnPinIdPath() {
        OpExecutor.apply(graph, connOp(OpType.ADD_CONN, a.id, 0, b.id, 0,
            GraphOp.packConnPinIds("0", "0")));
        assertEquals(1, graph.connections.size());
        assertEquals("0", graph.connections.get(0).fromPinId);
        assertEquals("0", graph.connections.get(0).toPinId);
    }

    @Test
    @DisplayName("ADD_CONN with one-sided pinId: fills the other end from the live node, no integer drift")
    void testAddConnOneSidedPinId() {
        // 只带 fromPinId；toPinId 由 b.inputPinId(1) 补全为 "1"（而不是回落用整数 1 碰运气）
        OpExecutor.apply(graph, connOp(OpType.ADD_CONN, a.id, 0, b.id, 1,
            GraphOp.packConnPinIds("0", null)));
        assertEquals(1, graph.connections.size());
        assertEquals("0", graph.connections.get(0).fromPinId);
        assertEquals("1", graph.connections.get(0).toPinId);
    }

    @Test
    @DisplayName("ADD_CONN occupied input: skips silently and does not clobber")
    void testAddConnOccupiedSkips() {
        OpExecutor.apply(graph, connOp(OpType.ADD_CONN, a.id, 0, b.id, 0,
            GraphOp.packConnPinIds("0", "0")));
        OpExecutor.apply(graph, connOp(OpType.ADD_CONN, c.id, 0, b.id, 0,
            GraphOp.packConnPinIds("0", "0")));
        assertEquals(1, graph.connections.size());
        assertEquals(a.id, graph.connections.get(0).fromId);
    }

    @Test
    @DisplayName("ADD_CONN unresolvable pinId: skips silently, no integer fallback")
    void testAddConnUnresolvedSkips() {
        OpExecutor.apply(graph, connOp(OpType.ADD_CONN, a.id, 0, b.id, 0,
            GraphOp.packConnPinIds("nope", "0")));
        assertEquals(0, graph.connections.size());
    }

    @Test
    @DisplayName("ADD_CONN without pinId encoding: legacy integer path still works")
    void testAddConnLegacyIntegerPath() {
        OpExecutor.apply(graph, connOp(OpType.ADD_CONN, a.id, 0, b.id, 0, null));
        // 整数路径也会从节点派生 pinId（addConnection 内建），这里只断言线接上了。
        // The integer path still derives pinIds from the nodes (inside addConnection);
        // just assert the wire landed.
        assertEquals(1, graph.connections.size());
        assertEquals(a.id, graph.connections.get(0).fromId);
        assertEquals(b.id, graph.connections.get(0).toId);
    }

    @Test
    @DisplayName("REMOVE_CONN with pinIds: removes by pinId, leaves other wires alone")
    void testRemoveConnPinIdPath() {
        OpExecutor.apply(graph, connOp(OpType.ADD_CONN, a.id, 0, b.id, 0,
            GraphOp.packConnPinIds("0", "0")));
        OpExecutor.apply(graph, connOp(OpType.ADD_CONN, c.id, 0, b.id, 1,
            GraphOp.packConnPinIds("0", "1")));
        OpExecutor.apply(graph, connOp(OpType.REMOVE_CONN, a.id, 0, b.id, 0,
            GraphOp.packConnPinIds("0", "0")));
        assertEquals(1, graph.connections.size());
        assertEquals(c.id, graph.connections.get(0).fromId);
    }

    @Test
    @DisplayName("REMOVE_CONN without pinId encoding: legacy integer path")
    void testRemoveConnLegacyIntegerPath() {
        OpExecutor.apply(graph, connOp(OpType.ADD_CONN, a.id, 0, b.id, 0, null));
        OpExecutor.apply(graph, connOp(OpType.REMOVE_CONN, a.id, 0, b.id, 0, null));
        assertEquals(0, graph.connections.size());
    }

    @Test
    @DisplayName("malformed pinId encoding (multi-separator) falls back to integer path")
    void testMalformedEncodingFallsBack() {
        String sep = String.valueOf((char) 1);
        OpExecutor.apply(graph, connOp(OpType.ADD_CONN, a.id, 0, b.id, 0,
            "0" + sep + "0" + sep + "0"));
        // parseConnPinIds → null → integer path / 整数路径仍能接上
        assertEquals(1, graph.connections.size());
    }
}
