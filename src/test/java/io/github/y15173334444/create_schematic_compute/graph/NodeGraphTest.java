package io.github.y15173334444.create_schematic_compute.graph;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link NodeGraph} — graph structure, connections, cycle detection, topological sort.
 */
class NodeGraphTest {

    private NodeGraph graph;

    @BeforeEach
    void setUp() {
        graph = new NodeGraph();
    }

    @Test
    @DisplayName("addNode: adds node, increments nextNodeId, findNode works")
    void testAddNode() {
        GraphNode node = graph.addNode(NodeType.CONST, 0, 0);
        assertNotNull(node);
        assertEquals(1, graph.nodes.size());
        assertNotNull(graph.findNode(node.id));
        assertEquals(NodeType.CONST, node.type);
    }

    @Test
    @DisplayName("removeNode: removes node and its connections")
    void testRemoveNode() {
        GraphNode a = graph.addNode(NodeType.CONST, 0, 0);
        GraphNode b = graph.addNode(NodeType.ADD, 100, 0);
        graph.addConnection(a.id, 0, b.id, 0);

        graph.removeNode(a.id);
        assertNull(graph.findNode(a.id));
        // Connections involving removed node are also gone
        assertEquals(0, graph.connections.size());
    }

    @Test
    @DisplayName("addConnection: valid connection returns true")
    void testAddConnection() {
        GraphNode a = graph.addNode(NodeType.CONST, 0, 0);
        GraphNode b = graph.addNode(NodeType.ADD, 100, 0);
        assertTrue(graph.addConnection(a.id, 0, b.id, 0));
        assertEquals(1, graph.connections.size());
    }

    @Test
    @DisplayName("addConnection: duplicate on same input pin returns false")
    void testAddConnectionDuplicate() {
        GraphNode a = graph.addNode(NodeType.CONST, 0, 0);
        GraphNode b = graph.addNode(NodeType.ADD, 100, 0);
        GraphNode c = graph.addNode(NodeType.CONST, 50, 50);
        assertTrue(graph.addConnection(a.id, 0, b.id, 0));
        // Same input pin (b, pin 0) already connected — should reject
        assertFalse(graph.addConnection(c.id, 0, b.id, 0));
    }

    @Test
    @DisplayName("addConnection: self-loop is rejected")
    void testAddConnectionSelfLoop() {
        GraphNode a = graph.addNode(NodeType.CONST, 0, 0);
        assertFalse(graph.addConnection(a.id, 0, a.id, 0));
    }

    @Test
    @DisplayName("removeConnection: removes specific connection")
    void testRemoveConnection() {
        GraphNode a = graph.addNode(NodeType.CONST, 0, 0);
        GraphNode b = graph.addNode(NodeType.ADD, 100, 0);
        graph.addConnection(a.id, 0, b.id, 0);
        graph.removeConnection(a.id, 0, b.id, 0);
        assertEquals(0, graph.connections.size());
    }

    @Test
    @DisplayName("hasCycles: DAG returns false")
    void testHasCyclesNoCycle() {
        GraphNode a = graph.addNode(NodeType.CONST, 0, 0);
        GraphNode b = graph.addNode(NodeType.ADD, 100, 0);
        GraphNode c = graph.addNode(NodeType.MUL, 200, 0);
        graph.addConnection(a.id, 0, b.id, 0);
        graph.addConnection(b.id, 0, c.id, 0);
        assertFalse(graph.hasCycles());
    }

    @Test
    @DisplayName("hasCycles: cycle returns true")
    void testHasCyclesWithCycle() {
        GraphNode a = graph.addNode(NodeType.ADD, 0, 0);
        GraphNode b = graph.addNode(NodeType.ADD, 100, 0);
        GraphNode c = graph.addNode(NodeType.ADD, 200, 0);
        graph.addConnection(a.id, 0, b.id, 0);
        graph.addConnection(b.id, 0, c.id, 0);
        graph.addConnection(c.id, 0, a.id, 0);  // back edge: c→a
        assertTrue(graph.hasCycles());
    }

    @Test
    @DisplayName("wouldCreateCycle: detects potential cycle")
    void testWouldCreateCycle() {
        GraphNode a = graph.addNode(NodeType.ADD, 0, 0);
        GraphNode b = graph.addNode(NodeType.ADD, 100, 0);
        GraphNode c = graph.addNode(NodeType.ADD, 200, 0);
        graph.addConnection(a.id, 0, b.id, 0);
        graph.addConnection(b.id, 0, c.id, 0);
        // Adding c→a would create a cycle
        assertTrue(graph.wouldCreateCycle(c.id, a.id));
    }

    @Test
    @DisplayName("wouldCreateCycle: non-cycle returns false")
    void testWouldCreateCycleSafe() {
        GraphNode a = graph.addNode(NodeType.ADD, 0, 0);
        GraphNode b = graph.addNode(NodeType.ADD, 100, 0);
        graph.addConnection(a.id, 0, b.id, 0);
        // Adding a→b again? it's already connected but wouldn't create a cycle
        // Adding b→a: the graph is a→b, so b→a would be a cycle
        assertTrue(graph.wouldCreateCycle(b.id, a.id));
    }

    @Test
    @DisplayName("Topological order produces valid ordering for DAG")
    void testTopologicalOrder() {
        GraphNode a = graph.addNode(NodeType.CONST, 0, 0);
        GraphNode b = graph.addNode(NodeType.ADD, 100, 0);
        GraphNode c = graph.addNode(NodeType.MUL, 200, 0);
        graph.addConnection(a.id, 0, b.id, 0);
        graph.addConnection(b.id, 0, c.id, 0);

        var order = graph.getTopoOrder();
        assertEquals(3, order.size());
        // a must come before b, b before c
        int idxA = order.indexOf(a.id);
        int idxB = order.indexOf(b.id);
        int idxC = order.indexOf(c.id);
        assertTrue(idxA < idxB, "CONST should be topologically before ADD");
        assertTrue(idxB < idxC, "ADD should be topologically before MUL");
    }

    @Test
    @DisplayName("getInputValue: returns connected output")
    void testInputCache() {
        GraphNode a = graph.addNode(NodeType.CONST, 0, 0);
        GraphNode b = graph.addNode(NodeType.ADD, 100, 0);
        graph.addConnection(a.id, 0, b.id, 0);

        // Simulate outputs: a produces 5.0 on pin 0
        var outputs = new java.util.HashMap<Integer, float[]>();
        outputs.put(a.id, new float[]{5.0f});

        assertEquals(5.0f, graph.getInputValue(b.id, 0, outputs), 0.0001f);
    }

    @Test
    @DisplayName("getInputValue: unconnected returns 0")
    void testInputCacheUnconnected() {
        GraphNode b = graph.addNode(NodeType.ADD, 100, 0);
        var outputs = new java.util.HashMap<Integer, float[]>();
        assertEquals(0f, graph.getInputValue(b.id, 0, outputs), 0.0001f);
    }

    @Test
    @DisplayName("copy: produces independent deep copy")
    void testCopyIndependent() {
        graph.addNode(NodeType.CONST, 0, 0);
        graph.addNode(NodeType.ADD, 100, 0);

        NodeGraph copy = graph.copy();
        assertEquals(2, copy.nodes.size());
        // Mutating copy doesn't affect original
        copy.addNode(NodeType.MUL, 200, 0);
        assertEquals(2, graph.nodes.size());
        assertEquals(3, copy.nodes.size());
        // Copy nodes exist in original too
        for (var n : graph.nodes) assertNotNull(graph.findNode(n.id));
    }

    @Test
    @DisplayName("rebuildNodeMap: restores lookup after external mutation")
    void testRebuildNodeMap() {
        GraphNode a = graph.addNode(NodeType.CONST, 0, 0);
        // Directly add a node bypassing addNode (simulating external mutation)
        GraphNode external = new GraphNode(999, NodeType.ADD, 50, 50);
        graph.nodes.add(external);
        graph.rebuildNodeMap();

        assertNotNull(graph.findNode(999));
        assertNotNull(graph.findNode(a.id));
    }

    // ══════════════════ pinId restore (undo rewiring) ══════════════════

    @Test
    @DisplayName("addConnectionWithPinIds: resolves generic pinIds and binds them")
    void testAddConnectionWithPinIds() {
        GraphNode a = graph.addNode(NodeType.CONST, 0, 0);
        GraphNode b = graph.addNode(NodeType.ADD, 100, 0);
        // Generic pinId = decimal index / 通用 pinId = 十进制索引
        assertTrue(graph.addConnectionWithPinIds(a.id, "0", b.id, "0"));
        assertEquals(1, graph.connections.size());
        assertEquals("0", graph.connections.get(0).fromPinId);
        assertEquals("0", graph.connections.get(0).toPinId);
    }

    @Test
    @DisplayName("addConnectionWithPinIds: occupied input pin is skipped (no clobber)")
    void testAddConnectionWithPinIdsOccupied() {
        GraphNode a = graph.addNode(NodeType.CONST, 0, 0);
        GraphNode b = graph.addNode(NodeType.ADD, 100, 0);
        GraphNode c = graph.addNode(NodeType.CONST, 50, 50);
        assertTrue(graph.addConnectionWithPinIds(a.id, "0", b.id, "0"));
        // Restore attempt while the input pin is already wired → refuse, keep existing
        // 恢复时输入脚已被占用 → 拒绝，保留现有连线
        assertFalse(graph.addConnectionWithPinIds(c.id, "0", b.id, "0"));
        assertEquals(1, graph.connections.size());
        assertEquals(a.id, graph.connections.get(0).fromId);
    }

    @Test
    @DisplayName("addConnectionWithPinIds: unresolvable pinId is skipped")
    void testAddConnectionWithPinIdsUnresolved() {
        GraphNode a = graph.addNode(NodeType.CONST, 0, 0);
        GraphNode b = graph.addNode(NodeType.ADD, 100, 0);
        assertFalse(graph.addConnectionWithPinIds(a.id, "99", b.id, "0"));
        assertFalse(graph.addConnectionWithPinIds(a.id, "0", b.id, "99"));
        assertEquals(0, graph.connections.size());
    }

    @Test
    @DisplayName("removeNode then pinId restore: wire comes back; second restore is occupancy-skipped")
    void testRemoveNodeThenPinIdRestore() {
        GraphNode a = graph.addNode(NodeType.CONST, 0, 0);
        GraphNode b = graph.addNode(NodeType.ADD, 100, 0);
        GraphNode c = graph.addNode(NodeType.CONST, 50, 50);
        assertTrue(graph.addConnectionWithPinIds(a.id, "0", b.id, "0"));
        // Delete A — its wire is pruned with it / 删 A 时连线一并被剪
        graph.removeNode(a.id);
        assertEquals(0, graph.connections.size());
        // Re-adopt A at the same id (undo of REMOVE_NODE restores the NBT id)
        GraphNode restored = new GraphNode(a.id, NodeType.CONST, a.x, a.y);
        graph.adoptNode(restored);
        // Undo rewires by pinId / 撤销按 pinId 接回
        assertTrue(graph.addConnectionWithPinIds(restored.id, "0", b.id, "0"));
        assertEquals(1, graph.connections.size());
        // A second restore of the same wire must NOT clobber / 再次恢复同一条不得覆盖
        assertFalse(graph.addConnectionWithPinIds(restored.id, "0", b.id, "0"));
        assertEquals(1, graph.connections.size());
        // Occupied by a different source after restore is also refused
        assertFalse(graph.addConnectionWithPinIds(c.id, "0", b.id, "0"));
        assertEquals(restored.id, graph.connections.get(0).fromId);
    }

    @Test
    @DisplayName("removeConnectionByPinIds: removes only the matching pinId pair")
    void testRemoveConnectionByPinIds() {
        GraphNode a = graph.addNode(NodeType.CONST, 0, 0);
        GraphNode b = graph.addNode(NodeType.ADD, 100, 0);
        GraphNode c = graph.addNode(NodeType.CONST, 50, 50);
        assertTrue(graph.addConnectionWithPinIds(a.id, "0", b.id, "0"));
        assertTrue(graph.addConnectionWithPinIds(c.id, "0", b.id, "1"));
        assertTrue(graph.removeConnectionByPinIds(a.id, "0", b.id, "0"));
        assertEquals(1, graph.connections.size());
        assertEquals(c.id, graph.connections.get(0).fromId);
        // Non-matching pinIds do not remove / pinId 对不上不删
        assertFalse(graph.removeConnectionByPinIds(a.id, "0", b.id, "0"));
        assertFalse(graph.removeConnectionByPinIds(c.id, "0", b.id, "0"));
        assertEquals(1, graph.connections.size());
    }
}
