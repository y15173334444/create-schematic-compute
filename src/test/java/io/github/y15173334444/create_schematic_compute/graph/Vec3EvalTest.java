package io.github.y15173334444.create_schematic_compute.graph;

import io.github.y15173334444.create_schematic_compute.network.AudioBands;
import io.github.y15173334444.create_schematic_compute.network.SignalBus;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * VEC3 向量核心运算集求值测试（v1.3.0）：十节点逐项、向量经 BUS/私有跨方块收发、
 * 严格类型匹配（向量脚只接向量线）。
 * VEC3 core math evaluation tests (v1.3.0): each of the ten nodes, vectors crossing
 * the bus/private channels, strict type matching (vector pins wire only to vectors).
 */
class Vec3EvalTest {

    private static final float DT = 0.1f;

    @AfterEach void cleanup() { AudioBands.clear(); SignalBus.clear(); }

    private static GraphEvaluator evaluator(NodeGraph g) {
        GraphEvaluator ev = new GraphEvaluator(g);
        ev.setSignalBus(SignalBus.PORT);
        ev.setAudioBands(AudioBands.PORT);
        ev.restoreSubState(new RuntimeState());
        ev.setAudioTransports(new HashMap<>());
        ev.setAudioHostPos(new BlockPos(0, 0, 0));
        return ev;
    }

    private static void tick(GraphEvaluator ev) {
        ev.evaluate(List.of(), Map.of(), DT, new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));
    }

    @Test
    @DisplayName("常数/合拆：三分量往返 / const and join-split: three components round trip")
    void constJoinSplit() {
        NodeGraph g = new NodeGraph();
        GraphNode cst = g.addNode(NodeType.VEC3_CONST, 0, 0);
        cst.params[0] = 1f; cst.params[1] = 2f; cst.params[2] = 3f;
        GraphNode split = g.addNode(NodeType.VEC3_SPLIT, 100, 0);
        assertTrue(g.addConnection(cst.id, 0, split.id, 0));
        GraphEvaluator ev = evaluator(g);
        tick(ev);
        assertEquals(1f, ev.getNodeOutput(split.id, 0), 1e-6);
        assertEquals(2f, ev.getNodeOutput(split.id, 1), 1e-6);
        assertEquals(3f, ev.getNodeOutput(split.id, 2), 1e-6);
        assertEquals(new Vec3f(1f, 2f, 3f), ev.vec3Out(cst.id, 0));

        NodeGraph g2 = new NodeGraph();
        GraphNode x = g2.addNode(NodeType.CONST, 0, 0); x.params[0] = 4f;
        GraphNode y = g2.addNode(NodeType.CONST, 0, 20); y.params[0] = 5f;
        GraphNode z = g2.addNode(NodeType.CONST, 0, 40); z.params[0] = 6f;
        GraphNode join = g2.addNode(NodeType.VEC3_JOIN, 100, 0);
        assertTrue(g2.addConnection(x.id, 0, join.id, 0));
        assertTrue(g2.addConnection(y.id, 0, join.id, 1));
        assertTrue(g2.addConnection(z.id, 0, join.id, 2));
        GraphEvaluator ev2 = evaluator(g2);
        tick(ev2);
        assertEquals(new Vec3f(4f, 5f, 6f), ev2.vec3Out(join.id, 0));
    }

    @Test
    @DisplayName("算术：加减缩放点叉长度归一 / arithmetic: add, sub, scale, dot, cross, length, normalize")
    void arithmetic() {
        NodeGraph g = new NodeGraph();
        GraphNode a = g.addNode(NodeType.VEC3_CONST, 0, 0);
        a.params[0] = 1f; a.params[1] = 0f; a.params[2] = 0f;
        GraphNode b = g.addNode(NodeType.VEC3_CONST, 0, 30);
        b.params[0] = 0f; b.params[1] = 2f; b.params[2] = 0f;
        GraphNode add = g.addNode(NodeType.VEC3_ADD, 100, 0);
        GraphNode sub = g.addNode(NodeType.VEC3_SUB, 100, 30);
        GraphNode scale = g.addNode(NodeType.VEC3_SCALE, 100, 60);
        GraphNode dot = g.addNode(NodeType.VEC3_DOT, 100, 90);
        GraphNode cross = g.addNode(NodeType.VEC3_CROSS, 100, 120);
        GraphNode len = g.addNode(NodeType.VEC3_LENGTH, 100, 150);
        GraphNode norm = g.addNode(NodeType.VEC3_NORMALIZE, 100, 180);
        GraphNode k = g.addNode(NodeType.CONST, 0, 200); k.params[0] = 3f;
        GraphNode scaledSrc = g.addNode(NodeType.VEC3_CONST, 0, 230);
        scaledSrc.params[0] = 1f; scaledSrc.params[1] = 1f; scaledSrc.params[2] = 1f;
        assertTrue(g.addConnection(a.id, 0, add.id, 0));
        assertTrue(g.addConnection(b.id, 0, add.id, 1));
        assertTrue(g.addConnection(a.id, 0, sub.id, 0));
        assertTrue(g.addConnection(b.id, 0, sub.id, 1));
        assertTrue(g.addConnection(scaledSrc.id, 0, scale.id, 0));
        assertTrue(g.addConnection(k.id, 0, scale.id, 1));
        assertTrue(g.addConnection(a.id, 0, dot.id, 0));
        assertTrue(g.addConnection(b.id, 0, dot.id, 1));
        assertTrue(g.addConnection(a.id, 0, cross.id, 0));
        assertTrue(g.addConnection(b.id, 0, cross.id, 1));
        assertTrue(g.addConnection(b.id, 0, len.id, 0));
        assertTrue(g.addConnection(b.id, 0, norm.id, 0));
        GraphEvaluator ev = evaluator(g);
        tick(ev);
        assertEquals(new Vec3f(1f, 2f, 0f), ev.vec3Out(add.id, 0));
        assertEquals(new Vec3f(1f, -2f, 0f), ev.vec3Out(sub.id, 0));
        assertEquals(new Vec3f(3f, 3f, 3f), ev.vec3Out(scale.id, 0));
        assertEquals(0f, ev.getNodeOutput(dot.id, 0), 1e-6, "orthogonal dot = 0");
        assertEquals(new Vec3f(0f, 0f, 2f), ev.vec3Out(cross.id, 0), "x cross y = z*2");
        assertEquals(2f, ev.getNodeOutput(len.id, 0), 1e-6);
        assertEquals(new Vec3f(0f, 1f, 0f), ev.vec3Out(norm.id, 0));
    }

    @Test
    @DisplayName("严格匹配：向量脚只接向量线 / strict matching: vector pins wire only to vectors")
    void strictMatching() {
        NodeGraph g = new NodeGraph();
        GraphNode cst = g.addNode(NodeType.CONST, 0, 0);
        GraphNode split = g.addNode(NodeType.VEC3_SPLIT, 100, 0);
        GraphNode join = g.addNode(NodeType.VEC3_JOIN, 100, 100);
        GraphNode vec = g.addNode(NodeType.VEC3_CONST, 0, 100);
        assertFalse(g.addConnection(cst.id, 0, split.id, 0), "float into a vector pin refused");
        assertFalse(g.addConnection(vec.id, 0, join.id, 0), "vector into a float pin refused");
        assertTrue(g.addConnection(vec.id, 0, split.id, 0), "vector into a vector pin accepted");
    }

    @Test
    @DisplayName("向量过总线：声明 VEC3 频段跨图收发 / vectors over the bus: a VEC3-declared band crosses graphs")
    void vec3OverBus() {
        // 发布方：VEC3_CONST → BUS_OUT("vb") 的 vv（声明 VEC3）
        NodeGraph g1 = new NodeGraph();
        GraphNode cst = g1.addNode(NodeType.VEC3_CONST, 0, 0);
        cst.params[0] = 7f; cst.params[1] = 8f; cst.params[2] = 9f;
        GraphNode busOut = g1.addNode(NodeType.BUS_OUT, 100, 0);
        busOut.signalName = "vb";
        busOut.signalBands = new java.util.ArrayList<>(List.of("vv"));
        busOut.bandTypes = new java.util.ArrayList<>(List.of(PinType.VEC3));
        assertTrue(g1.addConnection(cst.id, 0, busOut.id, 0), "vector wire into a VEC3 band");
        tick(evaluator(g1));

        // 订阅方：BUS_IN("vb") 的 vv → VEC3_SPLIT
        NodeGraph g2 = new NodeGraph();
        GraphNode busIn = g2.addNode(NodeType.BUS_IN, 0, 0);
        busIn.signalName = "vb";
        busIn.signalBands = new java.util.ArrayList<>(List.of("vv"));
        busIn.bandTypes = new java.util.ArrayList<>(List.of(PinType.VEC3));
        GraphNode split = g2.addNode(NodeType.VEC3_SPLIT, 100, 0);
        assertTrue(g2.addConnection(busIn.id, 0, split.id, 0));
        GraphEvaluator ev2 = evaluator(g2);
        tick(ev2);
        assertEquals(7f, ev2.getNodeOutput(split.id, 0), 1e-6);
        assertEquals(8f, ev2.getNodeOutput(split.id, 1), 1e-6);
        assertEquals(9f, ev2.getNodeOutput(split.id, 2), 1e-6);
    }

    @Test
    @DisplayName("向量过私有：声明 VEC3 的频道收发 / vectors over a private channel: declared VEC3")
    void vec3OverPrivate() {
        NodeGraph g1 = new NodeGraph();
        GraphNode cst = g1.addNode(NodeType.VEC3_CONST, 0, 0);
        cst.params[0] = -1f; cst.params[1] = 0.5f; cst.params[2] = 12f;
        GraphNode pout = g1.addNode(NodeType.PRIVATE_OUT, 100, 0);
        pout.signalName = "vp";
        pout.bandTypes = new java.util.ArrayList<>(List.of(PinType.VEC3));
        assertTrue(g1.addConnection(cst.id, 0, pout.id, 0));
        tick(evaluator(g1));

        NodeGraph g2 = new NodeGraph();
        GraphNode pin = g2.addNode(NodeType.PRIVATE_IN, 0, 0);
        pin.signalName = "vp";
        pin.bandTypes = new java.util.ArrayList<>(List.of(PinType.VEC3));
        GraphNode split = g2.addNode(NodeType.VEC3_SPLIT, 100, 0);
        assertTrue(g2.addConnection(pin.id, 0, split.id, 0));
        GraphEvaluator ev2 = evaluator(g2);
        tick(ev2);
        assertEquals(-1f, ev2.getNodeOutput(split.id, 0), 1e-6);
        assertEquals(0.5f, ev2.getNodeOutput(split.id, 1), 1e-6);
        assertEquals(12f, ev2.getNodeOutput(split.id, 2), 1e-6);
    }
}
