package io.github.y15173334444.create_schematic_compute.graph;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PID / PID_POWER 积分死区：|err|≤deadband 时积分保持（不增长也不清除），
 * |err|>deadband 时正常积分。V5→V6 迁移为旧节点补默认 0.001（与 ENCODER 清零同一步）。
 * PID / PID_POWER integral deadband: the integral holds (neither grows nor clears)
 * when |err| ≤ deadband and integrates normally outside. V5→V6 migration fills
 * the default 0.001 on legacy nodes (same V5→V6 step as the ENCODER reset pin).
 */
class PidDeadbandTest {

    private static final float DT = 0.05f;

    private static GraphNode pidNode(int id, float deadband) {
        GraphNode n = new GraphNode(id, NodeType.PID, 0, 0);
        n.params[5] = deadband;
        return n;
    }

    private static Map<Integer, Float> evalPid(GraphNode n, float sp, float pv,
                                               Map<Integer, Float> pidState) {
        NodeGraph g = new NodeGraph();
        // adopt 保留 id，便于断言 pidState 键 / adopt keeps the id so pidState keys match
        GraphNode constSp = g.addNode(NodeType.CONST, -100, 0);
        constSp.params[0] = sp;
        GraphNode constPv = g.addNode(NodeType.CONST, -100, 50);
        constPv.params[0] = pv;
        g.adoptNode(n);
        g.addConnection(constSp.id, 0, n.id, 0);
        g.addConnection(constPv.id, 0, n.id, 1);
        var eval = new GraphEvaluator(g);
        List<GraphEvaluator.InputSource> in = new ArrayList<>();
        eval.evaluate(in, pidState, DT);
        return pidState;
    }

    @Test
    @DisplayName("inside deadband: integral holds (neither grows nor clears)")
    void holdInsideDeadband() {
        GraphNode n = pidNode(10, 0.5f);
        Map<Integer, Float> st = new HashMap<>();
        // 先积出非零积分 / first accumulate a non-zero integral
        st = evalPid(n, 2.0f, 0.0f, st); // err=2 > 0.5 → grows
        float before = st.get(10);
        assertTrue(before > 0f);
        // err=0.3 ≤ 0.5 → hold / 保持
        st = evalPid(n, 0.3f, 0.0f, st);
        assertEquals(before, st.get(10), 1e-6f, "must hold inside deadband");
        // 连续多个 tick 仍保持 / still held across more ticks
        st = evalPid(n, 0.1f, 0.0f, st);
        assertEquals(before, st.get(10), 1e-6f);
        st = evalPid(n, -0.4f, 0.0f, st);
        assertEquals(before, st.get(10), 1e-6f, "negative err inside deadband also holds");
    }

    @Test
    @DisplayName("outside deadband: integral grows; zero deadband always integrates")
    void growOutsideDeadband() {
        GraphNode n = pidNode(10, 0.5f);
        Map<Integer, Float> st = new HashMap<>();
        st = evalPid(n, 1.0f, 0.0f, st); // err=1 > 0.5
        float a = st.get(10);
        st = evalPid(n, 1.0f, 0.0f, st);
        assertTrue(st.get(10) > a, "must grow outside deadband");

        GraphNode n0 = pidNode(11, 0f);
        Map<Integer, Float> st0 = new HashMap<>();
        st0 = evalPid(n0, 0.0001f, 0.0f, st0);
        assertTrue(st0.get(11) != 0f, "deadband=0 integrates even for tiny err");
    }

    @Test
    @DisplayName("legacy clear-at-0.001 is gone: tiny err holds, does not wipe the integral")
    void noLegacyClear() {
        GraphNode n = pidNode(10, 0.001f);
        Map<Integer, Float> st = new HashMap<>();
        st = evalPid(n, 5.0f, 0.0f, st);
        float before = st.get(10);
        assertTrue(before > 0f);
        // 旧逻辑会在 |err|≤0.001 时清零 / old logic cleared the integral here
        st = evalPid(n, 0.0005f, 0.0f, st);
        assertEquals(before, st.get(10), 1e-6f, "must hold, not clear");
    }

    @Test
    @DisplayName("PID_POWER: same hold semantics on its deadband slot")
    void pidPowerHolds() {
        GraphNode n = new GraphNode(20, NodeType.PID_POWER, 0, 0);
        n.params[4] = 0.5f;
        NodeGraph g = new NodeGraph();
        GraphNode constSp = g.addNode(NodeType.CONST, -100, 0);
        constSp.params[0] = 1.0f; // err=1 > 0.5
        GraphNode constPv = g.addNode(NodeType.CONST, -100, 50);
        constPv.params[0] = 0.0f;
        GraphNode constBase = g.addNode(NodeType.CONST, -100, 100);
        constBase.params[0] = 0.0f;
        g.adoptNode(n);
        g.addConnection(constSp.id, 0, n.id, 0);
        g.addConnection(constPv.id, 0, n.id, 1);
        g.addConnection(constBase.id, 0, n.id, 2);
        var eval = new GraphEvaluator(g);
        Map<Integer, Float> st = new HashMap<>();
        eval.evaluate(new ArrayList<>(), st, DT);
        float before = st.get(20);
        assertTrue(before > 0f);

        constSp.params[0] = 0.2f; // err=0.2 ≤ 0.5 → hold
        eval.evaluate(new ArrayList<>(), st, DT);
        assertEquals(before, st.get(20), 1e-6f);
    }

    // ══════════════════ V5→V6 migration ══════════════════

    private static CompoundTag pidNbt(int id, String type, int pcount, float[] ps) {
        CompoundTag n = new CompoundTag();
        n.putInt("id", id);
        n.putString("type", type);
        n.putFloat("x", 0);
        n.putFloat("y", 0);
        n.putInt("pcount", pcount);
        for (int i = 0; i < ps.length; i++) n.putFloat("p" + i, ps[i]);
        return n;
    }

    @Test
    @DisplayName("V5→V6: legacy PID/PID_POWER gain deadband=0.001; existing values untouched")
    void migrateFillsDeadband() {
        CompoundTag g = new CompoundTag();
        ListTag nodes = new ListTag();
        // 旧 PID：5 参 / legacy PID with 5 params
        nodes.add(pidNbt(1, "pid", 5, new float[]{1f, 0.1f, 0.05f, 1f, 3f}));
        // 旧 PID_POWER：4 参 / legacy PID_POWER with 4 params
        nodes.add(pidNbt(2, "pid_power", 4, new float[]{2f, 0.05f, 3f, 3f}));
        // 用户已把死区设为 0 的新档：不覆盖 / user already set deadband=0 — do not clobber
        nodes.add(pidNbt(3, "pid", 6, new float[]{1f, 0.1f, 0.05f, 1f, 3f, 0f}));
        // 无关节点 / unrelated node
        nodes.add(pidNbt(4, "const", 1, new float[]{5f}));
        g.put("nodes", nodes);
        g.putInt(NbtVersions.VERSION_KEY, 5);

        CompoundTag out = GraphMigration.migrate(g, null);
        assertEquals(6, NbtVersions.getVersion(out));

        ListTag ns = out.getList("nodes", net.minecraft.nbt.Tag.TAG_COMPOUND);
        CompoundTag pid = ns.getCompound(0);
        assertEquals(6, pid.getInt("pcount"));
        assertEquals(0.001f, pid.getFloat("p5"), 1e-6f);
        assertEquals(3f, pid.getFloat("p4"), 1e-6f);

        CompoundTag pp = ns.getCompound(1);
        assertEquals(5, pp.getInt("pcount"));
        assertEquals(0.001f, pp.getFloat("p4"), 1e-6f);

        CompoundTag already = ns.getCompound(2);
        assertEquals(0f, already.getFloat("p5"), 1e-6f, "user-set deadband must survive");

        CompoundTag k = ns.getCompound(3);
        assertEquals(1, k.getInt("pcount"), "unrelated node untouched");
        assertFalse(k.contains("p5"));
    }

    @Test
    @DisplayName("V5→V6: recursion into encapsulation sub-graphs")
    void migrateRecursesSubGraph() {
        CompoundTag g = new CompoundTag();
        ListTag nodes = new ListTag();
        CompoundTag encap = new CompoundTag();
        encap.putInt("id", 1);
        encap.putString("type", "encapsulation");
        CompoundTag sub = new CompoundTag();
        ListTag subNodes = new ListTag();
        subNodes.add(pidNbt(2, "pid", 5, new float[]{1f, 0.1f, 0.05f, 1f, 3f}));
        sub.put("nodes", subNodes);
        encap.put("subGraph", sub);
        nodes.add(encap);
        g.put("nodes", nodes);
        g.putInt(NbtVersions.VERSION_KEY, 5);

        CompoundTag out = GraphMigration.migrate(g, null);
        CompoundTag subOut = out.getList("nodes", net.minecraft.nbt.Tag.TAG_COMPOUND)
            .getCompound(0).getCompound("subGraph");
        CompoundTag pid = subOut.getList("nodes", net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0);
        assertEquals(6, pid.getInt("pcount"));
        assertEquals(0.001f, pid.getFloat("p5"), 1e-6f);
        assertEquals(6, NbtVersions.getVersion(subOut));
    }

    @Test
    @DisplayName("constructor defaults: new PID/PID_POWER nodes start at deadband=0.001")
    void constructorDefaults() {
        GraphNode p = new GraphNode(1, NodeType.PID, 0, 0);
        assertEquals(6, p.params.length);
        assertEquals(0.001f, p.params[5], 1e-6f);
        GraphNode pp = new GraphNode(2, NodeType.PID_POWER, 0, 0);
        assertEquals(5, pp.params.length);
        assertEquals(0.001f, pp.params[4], 1e-6f);
        assertEquals("deadband", NodeType.PID.paramNames[5]);
        assertEquals("deadband", NodeType.PID_POWER.paramNames[4]);
    }
}
