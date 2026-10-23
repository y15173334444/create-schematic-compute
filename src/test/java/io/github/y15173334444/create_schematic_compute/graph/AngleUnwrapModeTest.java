package io.github.y15173334444.create_schematic_compute.graph;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 角度解绕双角度比对：输出 = a-b 差角折进所选区间——mode 0 折入 (-180,180]
 * （双向有号差角），mode 1 折入 [0,360)（0~360 正向角）。无状态、输出有界
 * （旧逐 tick 累计形态已移除）。V6→V7 迁移为旧节点补 mode 槽（默认 0，不覆盖
 * 用户已设值）。
 * Angle Unwrap two-angle compare: the output is the a-b delta folded into the picked
 * range — mode 0 into (-180,180] (signed shortest delta), mode 1 into [0,360)
 * (0~360 forward delta). Stateless, bounded output (the old per-tick accumulator is
 * gone). V6→V7 migration fills the mode slot (default 0) on legacy nodes without
 * overwriting user-set values.
 */
class AngleUnwrapModeTest {

    private static final float DT = 0.05f;

    private static GraphNode unwrapNode(int id, float mode) {
        GraphNode n = new GraphNode(id, NodeType.ANGLE_UNWRAP, 0, 0);
        n.params[0] = mode;
        return n;
    }

    /** 单拍求值，返回节点输出；b 传 null = 不接线（旧单输入形态）。
     *  Evaluates one tick and returns the node output; b = null leaves input 1 unwired
     *  (the legacy single-input shape). */
    private static float evalDelta(GraphNode n, float a, Float b, Map<Integer, Float> st) {
        NodeGraph g = new NodeGraph();
        GraphNode constA = g.addNode(NodeType.CONST, -100, 0);
        constA.params[0] = a;
        g.adoptNode(n);
        g.addConnection(constA.id, 0, n.id, 0);
        if (b != null) {
            GraphNode constB = g.addNode(NodeType.CONST, -100, 50);
            constB.params[0] = b;
            g.addConnection(constB.id, 0, n.id, 1);
        }
        var eval = new GraphEvaluator(g);
        eval.evaluate(new ArrayList<GraphEvaluator.InputSource>(), st, DT);
        return eval.getNodeOutput(n.id, 0);
    }

    @Test
    @DisplayName("mode 0 (default): signed shortest delta folded into (-180,180]")
    void signedShortestDeltaIsTheDefault() {
        Map<Integer, Float> st = new HashMap<>();
        GraphNode n = unwrapNode(10, 0f);
        assertEquals(20f, evalDelta(n, 10f, 350f, st), 1e-6f, "10-350 is +20 across the seam");
        assertEquals(-20f, evalDelta(n, 350f, 10f, st), 1e-6f, "350-10 is -20 the short way");
        assertEquals(-2f, evalDelta(n, 359f, 1f, st), 1e-6f, "wrap seam 359 vs 1 gives -2");
        assertEquals(0f, evalDelta(n, 123f, 123f, st), 1e-6f);
    }

    @Test
    @DisplayName("mode 1: forward delta folded into [0,360)")
    void forwardDeltaMode() {
        Map<Integer, Float> st = new HashMap<>();
        GraphNode n = unwrapNode(20, 1f);
        assertEquals(340f, evalDelta(n, 350f, 10f, st), 1e-6f, "a ahead of b reads 340, not -20");
        assertEquals(20f, evalDelta(n, 10f, 350f, st), 1e-6f);
        assertEquals(2f, evalDelta(n, 1f, 359f, st), 1e-6f, "wrap seam 1 vs 359 gives 2");
        assertEquals(0f, evalDelta(n, 123f, 123f, st), 1e-6f);
    }

    @Test
    @DisplayName("unwired b compares against 0 — the legacy single-input shape normalises")
    void unwiredBComparesAgainstZero() {
        Map<Integer, Float> st = new HashMap<>();
        assertEquals(-10f, evalDelta(unwrapNode(30, 0f), 350f, null, st), 1e-6f);
        assertEquals(350f, evalDelta(unwrapNode(31, 1f), 350f, null, st), 1e-6f);
    }

    @Test
    @DisplayName("stateless and bounded: no accumulator slot, no drift across ticks")
    void statelessBoundedNoDrift() {
        Map<Integer, Float> st = new HashMap<>();
        GraphNode n = unwrapNode(40, 0f);
        for (int i = 0; i < 5; i++) {
            assertEquals(20f, evalDelta(n, 10f, 350f, st), 1e-6f,
                "same pair must read the same every tick");
        }
        assertTrue(st.isEmpty(), "no per-tick state may be written (the old accumulator is gone)");
    }

    @Test
    @DisplayName("body keeps two pins; legacy wires on pinId \"0\" still resolve to input a")
    void bodyPinsKeepLegacyWiresOnInputZero() {
        GraphNode n = new GraphNode(1, NodeType.ANGLE_UNWRAP, 0, 0);
        assertEquals(2, n.inputs(), "dual input: two angles to compare");
        assertEquals("0", n.inputPinId(0));
        assertEquals("1", n.inputPinId(1));
        assertEquals(0, n.inputPinIndex("0"), "old wires targeting \"0\" land on input a");
        assertEquals(1, n.inputPinIndex("1"));
    }

    // ── V6→V7 迁移 / migration ──────────────────────────────────────────

    private static CompoundTag unwrapNbt(int id, int pcount, Float p0) {
        CompoundTag n = new CompoundTag();
        n.putInt("id", id);
        n.putString("type", "angle_unwrap");
        n.putFloat("x", 0);
        n.putFloat("y", 0);
        n.putInt("pcount", pcount);
        if (p0 != null) n.putFloat("p0", p0);
        return n;
    }

    private static CompoundTag graphOf(CompoundTag... nodes) {
        CompoundTag g = new CompoundTag();
        ListTag list = new ListTag();
        for (CompoundTag n : nodes) list.add(n);
        g.put("nodes", list);
        g.putInt(NbtVersions.VERSION_KEY, 6);
        return g;
    }

    @Test
    @DisplayName("V6→V7 fills the missing mode slot with 0 and stamps the current version")
    void migrateFillsFoldModeParam() {
        CompoundTag out = GraphMigration.migrate(graphOf(unwrapNbt(7, 0, null)), null);
        assertEquals(NbtVersions.DATA_VERSION, NbtVersions.getVersion(out));
        CompoundTag n = out.getList("nodes", net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0);
        assertEquals(1, n.getInt("pcount"));
        assertEquals(0f, n.getFloat("p0"), 1e-6f, "default mode 0 = the signed shortest delta");
    }

    @Test
    @DisplayName("V6→V7 never overwrites a user-set mode (incl. 0)")
    void migrateKeepsUserSetMode() {
        CompoundTag out = GraphMigration.migrate(graphOf(unwrapNbt(8, 1, 1f)), null);
        CompoundTag n = out.getList("nodes", net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0);
        assertEquals(1f, n.getFloat("p0"), 1e-6f, "user-picked 0~360 mode survives");

        CompoundTag zero = GraphMigration.migrate(graphOf(unwrapNbt(9, 1, 0f)), null);
        CompoundTag zn = zero.getList("nodes", net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0);
        assertEquals(0f, zn.getFloat("p0"), 1e-6f);
    }

    @Test
    @DisplayName("V6→V7 recurses into ENCAPSULATION sub-graphs")
    void migrateRecursesIntoSubGraph() {
        CompoundTag encap = new CompoundTag();
        encap.putInt("id", 1);
        encap.putString("type", "encapsulation");
        encap.putFloat("x", 0);
        encap.putFloat("y", 0);
        encap.putInt("pcount", 0);
        encap.put("subGraph", graphOf(unwrapNbt(2, 0, null)));

        CompoundTag out = GraphMigration.migrate(graphOf(encap), null);
        CompoundTag sub = out.getList("nodes", net.minecraft.nbt.Tag.TAG_COMPOUND)
            .getCompound(0).getCompound("subGraph");
        CompoundTag n = sub.getList("nodes", net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0);
        assertEquals(1, n.getInt("pcount"), "sub-graph angle_unwrap gets the mode slot too");
        assertEquals(NbtVersions.DATA_VERSION, NbtVersions.getVersion(sub), "sub-graph is stamped as well");
    }
}
