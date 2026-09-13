package io.github.y15173334444.create_schematic_compute.graph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 动力网络读数节点（STRESS / RPM）的求值测试：宿主注入视图的读通、
 * 无注入时的零退化、封装（ENCAPSULATION）内的视图传播，以及零容量守卫。
 * Evaluation tests for the kinetic network reading nodes (STRESS / RPM): host-view
 * injection, zero degradation without injection, view propagation into
 * ENCAPSULATION sub-evaluators, and the zero-capacity guard.
 */
class KineticNodesEvalTest {

    /** 固定读数的测试视图 / fixed-reading test view. */
    private static KineticNetworkView view(float speed, float stress, float capacity) {
        return new KineticNetworkView() {
            @Override public float kineticSpeed() { return speed; }
            @Override public float kineticStress() { return stress; }
            @Override public float kineticCapacity() { return capacity; }
        };
    }

    private static GraphEvaluator evalOf(NodeGraph graph) {
        return new GraphEvaluator(graph);
    }

    @Test
    @DisplayName("RPM: without host injection outputs 0")
    void testRpmDefaultsToZero() {
        var graph = new NodeGraph();
        var rpm = graph.addNode(NodeType.RPM, 0, 0);
        var evaluator = evalOf(graph);
        evaluator.evaluate(List.of(), Map.of(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));
        assertEquals(0f, evaluator.getNodeOutput(rpm.id, 0), 0.0001f);
    }

    @Test
    @DisplayName("RPM: signed speed flows from the injected view")
    void testRpmReadsView() {
        var graph = new NodeGraph();
        var rpm = graph.addNode(NodeType.RPM, 0, 0);
        var evaluator = evalOf(graph);
        evaluator.setKineticNetworkView(view(-64f, 0f, 0f));
        evaluator.evaluate(List.of(), Map.of(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));
        assertEquals(-64f, evaluator.getNodeOutput(rpm.id, 0), 0.0001f);
    }

    @Test
    @DisplayName("STRESS: four pins report ratio/used/unused/left")
    void testStressPins() {
        var graph = new NodeGraph();
        var stress = graph.addNode(NodeType.STRESS, 0, 0);
        var evaluator = evalOf(graph);
        evaluator.setKineticNetworkView(view(0f, 512f, 1024f));
        evaluator.evaluate(List.of(), Map.of(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));
        assertEquals(0.5f, evaluator.getNodeOutput(stress.id, 0), 0.0001f);   // 占比
        assertEquals(512f, evaluator.getNodeOutput(stress.id, 1), 0.0001f);   // 已用
        assertEquals(0.5f, evaluator.getNodeOutput(stress.id, 2), 0.0001f);   // 未用
        assertEquals(512f, evaluator.getNodeOutput(stress.id, 3), 0.0001f);   // 剩余
    }

    @Test
    @DisplayName("STRESS: overload keeps ratio above 1 and zeroes unused/left")
    void testStressOverload() {
        var graph = new NodeGraph();
        var stress = graph.addNode(NodeType.STRESS, 0, 0);
        var evaluator = evalOf(graph);
        evaluator.setKineticNetworkView(view(0f, 1200f, 1000f));
        evaluator.evaluate(List.of(), Map.of(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));
        assertEquals(1.2f, evaluator.getNodeOutput(stress.id, 0), 0.0001f);
        assertEquals(1200f, evaluator.getNodeOutput(stress.id, 1), 0.0001f);
        assertEquals(0f, evaluator.getNodeOutput(stress.id, 2), 0.0001f);
        assertEquals(0f, evaluator.getNodeOutput(stress.id, 3), 0.0001f);
    }

    @Test
    @DisplayName("STRESS: zero capacity (no network) pins all read 0")
    void testStressZeroCapacity() {
        var graph = new NodeGraph();
        var stress = graph.addNode(NodeType.STRESS, 0, 0);
        var evaluator = evalOf(graph);
        evaluator.setKineticNetworkView(view(0f, 0f, 0f));
        evaluator.evaluate(List.of(), Map.of(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));
        for (int i = 0; i < 4; i++)
            assertEquals(0f, evaluator.getNodeOutput(stress.id, i), 0.0001f);
    }

    @Test
    @DisplayName("STRESS/RPM: view propagates into ENCAPSULATION sub-evaluators")
    void testViewReachesSubGraph() {
        var outer = new NodeGraph();
        var encap = outer.addNode(NodeType.ENCAPSULATION, 0, 0);
        var sub = new NodeGraph();
        var subStress = sub.addNode(NodeType.STRESS, 0, 0);
        var subOut = sub.addNode(NodeType.ENCAP_OUTPUT, 0, 0);
        sub.addConnection(subStress.id, 0, subOut.id, 0);
        encap.subGraph = sub;

        var evaluator = evalOf(outer);
        evaluator.setKineticNetworkView(view(0f, 256f, 1024f));
        evaluator.evaluate(List.of(), Map.of(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));
        // 子图封装输出 = STRESS 占比引脚 / encapsulated output = the ratio pin
        assertEquals(0.25f, evaluator.getNodeOutput(encap.id, 0), 0.0001f);
    }
}
