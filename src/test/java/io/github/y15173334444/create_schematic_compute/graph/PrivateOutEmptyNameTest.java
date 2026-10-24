package io.github.y15173334444.create_schematic_compute.graph;

import io.github.y15173334444.create_schematic_compute.network.SignalBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * PRIVATE_OUT 空名守卫回归（P2-5）：BUS_OUT 有 {@code isEmpty} 早退，PRIVATE_OUT 的
 * 浮点 put 路径以往没有 —— 未命名节点把输出写进 {@code SIGNALS[""]} 幽灵频道，未命名
 * 节点互相串音，且释放路径清不掉。修复：浮点写入加同款空名早退。
 * <p>
 * PRIVATE_OUT empty-name guard regression (P2-5): BUS_OUT has an {@code isEmpty} early-out
 * but the PRIVATE_OUT float put path did not — an unnamed node wrote its output into the
 * {@code SIGNALS[""]} ghost channel, unnamed nodes cross-talked, and release paths could
 * never clear it. Fix: the same empty-name early-out on the float write.
 */
class PrivateOutEmptyNameTest {

    @AfterEach void cleanup() { SignalBus.clear(); }

    @Test
    @DisplayName("空名 PRIVATE_OUT 不写 SIGNALS[\"\"]")
    void emptyNameWritesNothing() {
        var graph = new NodeGraph();
        var src = graph.addNode(NodeType.CONST, 0, 0);
        src.params[0] = 3.5f;
        var priv = graph.addNode(NodeType.PRIVATE_OUT, 10, 0);
        priv.signalName = "";   // 未命名 / unnamed
        graph.addConnection(src.id, 0, priv.id, 0);

        var evaluator = new GraphEvaluator(graph);
        evaluator.setSignalBus(SignalBus.PORT);
        evaluator.restoreSubState(new RuntimeState());
        evaluator.evaluate(List.of(), Map.of(), 0.05f, new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));

        assertEquals(0f, SignalBus.get(""), 0.0001f,
            "an unnamed PRIVATE_OUT must never write the SIGNALS[\"\"] ghost channel");
    }

    @Test
    @DisplayName("对照组：具名 PRIVATE_OUT 照常写入")
    void namedChannelStillWrites() {
        var graph = new NodeGraph();
        var src = graph.addNode(NodeType.CONST, 0, 0);
        src.params[0] = 3.5f;
        var priv = graph.addNode(NodeType.PRIVATE_OUT, 10, 0);
        priv.signalName = "sig";
        graph.addConnection(src.id, 0, priv.id, 0);

        var evaluator = new GraphEvaluator(graph);
        evaluator.setSignalBus(SignalBus.PORT);
        evaluator.restoreSubState(new RuntimeState());
        evaluator.evaluate(List.of(), Map.of(), 0.05f, new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));

        assertEquals(3.5f, SignalBus.get("sig"), 0.0001f, "a named PRIVATE_OUT writes as before");
    }
}
