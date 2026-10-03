package io.github.y15173334444.create_schematic_compute.graph;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 传输连线引脚缓存测试：接入 BUS/PRIVATE 传输的引脚判为「传输着色」，普通连线不受影响，
 * 删线即失效（缓存随拓扑版本 bump）。传输双方端点都算。
 * Transfer-wired pin cache tests: pins wired into BUS/PRIVATE transfers count as
 * transfer-tinted, plain wires never do, and removing the wire untints immediately
 * (cache invalidated with the topology version). Both endpoints count.
 */
class NodeGraphTransferPinTest {

    @Test
    void pinsWiredToTransferTypesTint() {
        NodeGraph g = new NodeGraph();
        GraphNode constant = g.addNode(NodeType.CONST, 0, 0);
        GraphNode busOut = g.addNode(NodeType.BUS_OUT, 200, 0);
        GraphNode button = g.addNode(NodeType.KEYBOARD, 0, 200);
        GraphNode privateOut = g.addNode(NodeType.PRIVATE_OUT, 200, 200);
        assertTrue(g.addConnection(constant.id, 0, busOut.id, 0));
        assertTrue(g.addConnection(button.id, 0, privateOut.id, 0));

        assertTrue(g.isTransferWired(constant.id, 0, true), "source pin into BUS_OUT tints");
        assertTrue(g.isTransferWired(busOut.id, 0, false), "BUS_OUT input pin tints");
        assertTrue(g.isTransferWired(button.id, 0, true), "source pin into PRIVATE_OUT tints");
        assertTrue(g.isTransferWired(privateOut.id, 0, false), "PRIVATE_OUT input pin tints");

        GraphNode busIn = g.addNode(NodeType.BUS_IN, 400, 0);
        GraphNode display = g.addNode(NodeType.TEXT, 600, 0);
        assertTrue(g.addConnection(busIn.id, 0, display.id, 0));
        assertTrue(g.isTransferWired(busIn.id, 0, true), "BUS_IN output pin tints");
        assertTrue(g.isTransferWired(display.id, 0, false), "sink pin from BUS_IN tints");
    }

    @Test
    void plainWiresNeverTint() {
        NodeGraph g = new NodeGraph();
        GraphNode a = g.addNode(NodeType.CONST, 0, 0);
        GraphNode b = g.addNode(NodeType.ADD, 200, 0);
        assertTrue(g.addConnection(a.id, 0, b.id, 0));
        assertFalse(g.isTransferWired(a.id, 0, true), "plain source pin stays normal");
        assertFalse(g.isTransferWired(b.id, 0, false), "plain sink pin stays normal");
    }

    @Test
    void removingTheWireUntintsImmediately() {
        NodeGraph g = new NodeGraph();
        GraphNode a = g.addNode(NodeType.CONST, 0, 0);
        GraphNode busOut = g.addNode(NodeType.BUS_OUT, 200, 0);
        assertTrue(g.addConnection(a.id, 0, busOut.id, 0));
        assertTrue(g.isTransferWired(a.id, 0, true));
        g.removeConnection(a.id, 0, busOut.id, 0);
        assertFalse(g.isTransferWired(a.id, 0, true), "cache invalidated with the topology version");
        assertFalse(g.isTransferWired(busOut.id, 0, false));
    }

    @Test
    void samePinIndexOnInputAndOutputSidesDoNotCollide() {
        NodeGraph g = new NodeGraph();
        GraphNode busOut = g.addNode(NodeType.BUS_OUT, 200, 0);
        GraphNode busIn = g.addNode(NodeType.BUS_IN, 400, 0);
        GraphNode source = g.addNode(NodeType.CONST, 0, 0);
        GraphNode sink = g.addNode(NodeType.TEXT, 600, 0);
        assertTrue(g.addConnection(source.id, 0, busOut.id, 0));
        assertTrue(g.addConnection(busIn.id, 0, sink.id, 0));
        // input 0 与 output 0 是不同的键——仅实际接线的一侧变色
        // input 0 and output 0 are distinct keys — only the actually wired side tints.
        assertFalse(g.isTransferWired(busOut.id, 0, true), "BUS_OUT output side unwired");
        assertFalse(g.isTransferWired(busIn.id, 0, false), "BUS_IN input side unwired");
    }
}
