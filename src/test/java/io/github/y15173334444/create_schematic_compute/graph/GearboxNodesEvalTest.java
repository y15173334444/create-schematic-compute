package io.github.y15173334444.create_schematic_compute.graph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 变速器/数控齿轮箱专属节点（TX_OUT / CLUTCH / ENCODER）的求值测试。
 * Evaluation tests for the transmission/CNC-gearbox-specific nodes (TX_OUT / CLUTCH / ENCODER).
 */
class GearboxNodesEvalTest {

    @Test
    @DisplayName("TX_OUT: passthrough desired RPM from wired input")
    void testGearOutPassthrough() {
        var graph = new NodeGraph();
        var speed = graph.addNode(NodeType.CONST, 0, 0);
        speed.params[0] = 64f;
        var txOut = graph.addNode(NodeType.TX_OUT, 0, 0);
        graph.addConnection(speed.id, 0, txOut.id, 0);

        var evaluator = new GraphEvaluator(graph);
        evaluator.evaluate(List.of(), Map.of(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));

        assertEquals(64f, evaluator.getNodeOutput(txOut.id, 0), 0.0001f);
    }

    @Test
    @DisplayName("ENCODER: without host injection outputs zeros")
    void testEncoderDefaultsToZero() {
        var graph = new NodeGraph();
        var enc = graph.addNode(NodeType.ENCODER, 0, 0);

        var evaluator = new GraphEvaluator(graph);
        evaluator.evaluate(List.of(), Map.of(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));

        assertEquals(0f, evaluator.getNodeOutput(enc.id, 0), 0.0001f);
        assertEquals(0f, evaluator.getNodeOutput(enc.id, 1), 0.0001f);
    }

    @Test
    @DisplayName("ENCODER: host view values flow through both pins (live reader)")
    void testEncoderReadsLiveHostView() {
        var graph = new NodeGraph();
        var enc = graph.addNode(NodeType.ENCODER, 0, 0);
        var evalSinkProbe = graph.addNode(NodeType.DEBUG_PROBE, 0, 0);
        graph.addConnection(enc.id, 0, evalSinkProbe.id, 0);

        var evaluator = new GraphEvaluator(graph);
        // 模拟每 tick 积分推进的宿主视图：同一求值器实例上位置随 tick 变化
        // Simulate a per-tick integrating host view on one evaluator instance
        final float[] positionDeg = {90f};
        float velocityRpm = -32f;
        evaluator.setEncoderView(new KineticEncoderView() {
            @Override public float encoderPosition() { return positionDeg[0]; }
            @Override public float encoderPositionMeters() { return 0f; }
            @Override public float encoderVelocity() { return velocityRpm; }
            @Override public void resetEncoder() { positionDeg[0] = 0f; }
        });

        evaluator.evaluate(List.of(), Map.of(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));
        assertEquals(90f, evaluator.getNodeOutput(enc.id, 0), 0.0001f);
        assertEquals(-32f, evaluator.getNodeOutput(enc.id, 2), 0.0001f);

        // 下一个 tick：视图推进（宿主积分），ENCODER 输出跟随 —— 验证是活读取器而非快照
        // Next tick: the view advanced; ENCODER follows — proving a live reader, not a snapshot
        positionDeg[0] = 91.6f;   // -32 rpm × 0.05s × 6 = -9.6 deg → wrapped
        evaluator.evaluate(List.of(), Map.of(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));
        assertEquals(91.6f, evaluator.getNodeOutput(enc.id, 0), 0.0001f);
        assertTrue(Math.abs(evaluator.getNodeOutput(enc.id, 2) + 32f) < 0.0001f);
    }

    @Test
    @DisplayName("Closed loop: ENCODER pos + SUB target + PID feeds TX_OUT inside one graph")
    void testPidLoopGraphWiring() {
        var graph = new NodeGraph();
        var target = graph.addNode(NodeType.CONST, 0, 0);
        target.params[0] = 180f;
        var enc = graph.addNode(NodeType.ENCODER, 0, 0);
        var pid = graph.addNode(NodeType.PID, 0, 0);
        pid.params[0] = 0.05f;
        var txOut = graph.addNode(NodeType.TX_OUT, 0, 0);
        graph.addConnection(target.id, 0, pid.id, 0);   // sp
        graph.addConnection(enc.id, 0, pid.id, 1);      // pv
        graph.addConnection(pid.id, 0, txOut.id, 0);  // ctrl → desired rpm

        var view = new KineticEncoderView() {
            @Override public float encoderPosition() { return 179f; }
            @Override public float encoderPositionMeters() { return 0f; }
            @Override public float encoderVelocity() { return 5f; }
            @Override public void resetEncoder() { }
        };

        var evaluator = new GraphEvaluator(graph);
        evaluator.setEncoderView(view);
        // PID 有积分状态写入 pidState，必须给可变映射（与生产侧 runtimeState.pidState 一致）
        // PID writes integral state into pidState — must be mutable (matches the
        // production-side runtimeState.pidState).
        evaluator.evaluate(List.of(), new java.util.HashMap<>(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));

        // error = 180 - 179 = 1；kp=0.05、ki/kd 默认积分与微分在首 tick 贡献有限，
        // TX_OUT 输出应为正（正向追目标）
        float desiredRpm = evaluator.getNodeOutput(txOut.id, 0);
        assertTrue(desiredRpm > 0f, "PID error positive must command positive RPM, got " + desiredRpm);
    }

    @Test
    @DisplayName("ENCODER reset pin (level-triggered): high zeroes the view, held high holds at zero")
    void testEncoderResetPin() {
        var graph = new NodeGraph();
        var rst = graph.addNode(NodeType.CONST, 0, 0);
        rst.params[0] = 0f;   // 复位引脚初始低 / reset pin starts low
        var enc = graph.addNode(NodeType.ENCODER, 2, 0);
        graph.addConnection(rst.id, 0, enc.id, 0);

        final float[] positionDeg = {123f};
        var evaluator = new GraphEvaluator(graph);
        evaluator.setEncoderView(new KineticEncoderView() {
            @Override public float encoderPosition() { return positionDeg[0]; }
            @Override public float encoderPositionMeters() { return 0f; }
            @Override public float encoderVelocity() { return 0f; }
            @Override public void resetEncoder() { positionDeg[0] = 0f; }
        });

        evaluator.evaluate(List.of(), Map.of(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));
        assertEquals(123f, evaluator.getNodeOutput(enc.id, 0), 0.0001f);   // 低电平不重置

        rst.params[0] = 1f;   // 拉高 → 清零
        evaluator.evaluate(List.of(), Map.of(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));
        assertEquals(0f, evaluator.getNodeOutput(enc.id, 0), 0.0001f);
        assertEquals(0f, positionDeg[0], 0.0001f);
    }
}
