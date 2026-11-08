package io.github.y15173334444.create_schematic_compute.graph;

import io.github.y15173334444.create_schematic_compute.network.SignalBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 类型化信号传输测试（v1.3.0 收编）：Vec3f 载荷数学、SignalBus 向量值表生命周期、
 * SET_PIN_TYPE op 的类型换段重绑（兼容线保留、不兼容线剪除）。
 * Typed signal transport tests (the v1.3.0 consolidation): Vec3f payload math, the
 * SignalBus vector-value table lifecycle, and SET_PIN_TYPE's type-segment rebind
 * (compatible wires keep, mismatched prune).
 */
class TypedSignalTransportTest {

    @AfterEach void cleanup() { SignalBus.clear(); }

    // ── Vec3f 载荷数学 / Vec3f payload math ──

    @Test
    @DisplayName("Vec3f 运算：加减缩放点叉长度归一 / Vec3f math")
    void vec3fMath() {
        var a = new Vec3f(1f, 2f, 3f);
        var b = new Vec3f(4f, 5f, 6f);
        assertEquals(new Vec3f(5f, 7f, 9f), a.add(b));
        assertEquals(new Vec3f(-3f, -3f, -3f), a.sub(b));
        assertEquals(new Vec3f(2f, 4f, 6f), a.scale(2f));
        assertEquals(32f, a.dot(b), 1e-6);
        assertEquals(new Vec3f(-3f, 6f, -3f), a.cross(b));
        assertEquals(5f, new Vec3f(3f, 4f, 0f).length(), 1e-6);
        assertEquals(new Vec3f(0.6f, 0.8f, 0f), new Vec3f(3f, 4f, 0f).normalize());
        assertEquals(Vec3f.ZERO, Vec3f.ZERO.normalize(), "zero normalizes to zero (no NaN)");
    }

    // ── SignalBus 向量值表 / vector-value table ──

    @Test
    @DisplayName("SignalBus 向量值：读写 + 清理生命周期 / vector values: write, read, cleanup lifecycle")
    void signalBusVec3Values() {
        var v = new Vec3f(1f, 2f, 3f);
        SignalBus.putVec3("pv", v);
        assertEquals(v, SignalBus.getVec3("pv"), "private key round trip");
        SignalBus.putVec3("ch\0band", new Vec3f(9f, 8f, 7f));
        assertEquals(new Vec3f(9f, 8f, 7f), SignalBus.getVec3("ch\0band"), "band-key round trip");

        SignalBus.clearSignal("pv");
        assertNull(SignalBus.getVec3("pv"), "clearSignal drops the private vector value");
        assertNotNull(SignalBus.getVec3("ch\0band"), "other keys untouched");

        SignalBus.clearBus("ch");
        assertNull(SignalBus.getVec3("ch\0band"), "clearBus drops the channel's band vector values");
    }

    // ── SET_PIN_TYPE op / declared-type retypes ──

    @Test
    @DisplayName("SET_PIN_TYPE：频段改类型换段重绑（兼容线保留、不兼容线剪除）/ band retype rebinds the type segment")
    void setPinTypeBandRebind() {
        NodeGraph g = new NodeGraph();
        GraphNode music = g.addNode(NodeType.MUSIC, 0, 0);
        GraphNode busOut = g.addNode(NodeType.BUS_OUT, 100, 0);
        busOut.signalName = "t";
        busOut.signalBands = new ArrayList<>(List.of("ba", "bb"));
        busOut.bandTypes = new ArrayList<>(List.of(PinType.AUDIO, PinType.FLOAT));
        GraphNode amp = g.addNode(NodeType.AMP, 200, 0);
        assertTrue(g.addConnection(music.id, 0, busOut.id, 0), "audio wire into band ba");
        assertEquals("audio:ba", g.connections.get(0).toPinId);

        // ba: AUDIO → FLOAT（对端 MUSIC 输出是 AUDIO → 剪除）
        OpExecutor.apply(g, GraphOp.setPinType(null, -1, busOut.id, 0, PinType.FLOAT.code, null));
        assertEquals(PinType.FLOAT, busOut.bandType(0));
        assertTrue(g.connections.isEmpty(), "the AUDIO-peer wire is pruned on retype");

        // bb: FLOAT → AUDIO 后接 AMP（新声明带段可接）/ retype then wire
        OpExecutor.apply(g, GraphOp.setPinType(null, -1, busOut.id, 1, PinType.AUDIO.code, null));
        assertEquals(PinType.AUDIO, busOut.bandType(1));
        assertTrue(g.addConnection(music.id, 0, busOut.id, 1), "the retyped band now accepts audio");
        assertEquals("audio:bb", g.connections.get(0).toPinId);
    }

    @Test
    @DisplayName("SET_PIN_TYPE：私有频道改类型；同值重放是空操作 / private retype; same-value replay is a no-op")
    void setPinTypePrivate() {
        NodeGraph g = new NodeGraph();
        GraphNode pout = g.addNode(NodeType.PRIVATE_OUT, 0, 0);
        pout.signalName = "pt";
        GraphNode cst = g.addNode(NodeType.CONST, 100, 0);
        assertTrue(g.addConnection(cst.id, 0, pout.id, 0), "float wire into a FLOAT private pin");

        OpExecutor.apply(g, GraphOp.setPinType(null, -1, pout.id, 0, PinType.AUDIO.code, null));
        assertEquals(PinType.AUDIO, pout.bandType(0));
        assertTrue(g.connections.isEmpty(), "the float wire is pruned (peer is FLOAT)");
        assertEquals("audio:0", pout.inputPinId(0), "the pinId moved to the new type segment");

        // 同值重放不改写 / same-value replay is a no-op
        OpExecutor.apply(g, GraphOp.setPinType(null, -1, pout.id, 0, PinType.AUDIO.code, null));
        assertEquals(PinType.AUDIO, pout.bandType(0));
    }
}
