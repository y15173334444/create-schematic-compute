package io.github.y15173334444.create_schematic_compute.graph;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ENCODER 清零引脚从编辑区参数迁到节点体 0 号输入（V5→V6），并随 V8→V9 获得类型段。
 * 旧连线 tPinId="0"/"reset" 必须最终解析为 float:0；指向不存在引脚的连线剪除。
 * Encoder reset pin moves from the edit-area param to body input 0 (V5→V6), gaining the
 * type segment at V8→V9. Old wires with tPinId="0"/"reset" must end up as float:0; wires
 * on nonexistent pins are pruned.
 */
class EncoderResetPinMigrationTest {

    private static CompoundTag encoderNode(int id) {
        CompoundTag n = new CompoundTag();
        n.putInt("id", id);
        n.putString("type", "encoder");
        n.putFloat("x", 0);
        n.putFloat("y", 0);
        n.putInt("pcount", 1);
        n.putFloat("p0", 1f);   // 旧 reset 默认值 / legacy reset default
        return n;
    }

    private static CompoundTag constNode(int id) {
        CompoundTag n = new CompoundTag();
        n.putInt("id", id);
        n.putString("type", "const");
        n.putFloat("x", 0);
        n.putFloat("y", 0);
        n.putInt("pcount", 1);
        n.putFloat("p0", 0f);
        return n;
    }

    private static CompoundTag conn(int fromId, int toId, int tPin, String tPinId) {
        CompoundTag c = new CompoundTag();
        c.putInt("from", fromId);
        c.putInt("fPin", 0);
        c.putString("fPinId", "0");
        c.putInt("to", toId);
        c.putInt("tPin", tPin);
        if (tPinId != null) c.putString("tPinId", tPinId);
        return c;
    }

    @Test
    @DisplayName("Old reset wires stay on body input 0 and gain the type segment; dead wires prune")
    void migrateEncoderResetToBodyPin() {
        CompoundTag g = new CompoundTag();
        ListTag nodes = new ListTag();
        nodes.add(constNode(1));
        nodes.add(constNode(2));
        nodes.add(constNode(3));
        nodes.add(encoderNode(7));
        g.put("nodes", nodes);
        ListTag conns = new ListTag();
        conns.add(conn(1, 7, 0, "0"));
        conns.add(conn(2, 7, 0, "reset"));   // 极少数以参数名存档 / rare param-name pinId
        conns.add(conn(3, 7, 1, "1"));       // 指向不存在的引脚 → 剪除 / dead pin → pruned
        g.put("conns", conns);
        g.putInt(NbtVersions.VERSION_KEY, 5);

        CompoundTag out = GraphMigration.migrate(g, null);
        // V5→V6（1.2.5.2）：ENCODER 清零 + PID 死区同一步；migrate() 一路升到当前版
        // One V5→V6 step for both; migrate() runs on to the current version.
        assertEquals(NbtVersions.DATA_VERSION, NbtVersions.getVersion(out));

        CompoundTag n = out.getList("nodes", net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(3);
        assertEquals(0, n.getInt("pcount"), "stale reset param must be dropped");
        assertFalse(n.contains("p0"));

        ListTag oc = out.getList("conns", net.minecraft.nbt.Tag.TAG_COMPOUND);
        assertEquals(2, oc.size(), "the wire on the nonexistent pin is pruned");
        assertEquals("float:0", oc.getCompound(0).getString("tPinId"), "typed to float:0");
        assertEquals(0, oc.getCompound(0).getInt("tPin"));
        assertEquals("float:0", oc.getCompound(0).getString("fPinId"), "source end typed too");
        assertEquals("float:0", oc.getCompound(1).getString("tPinId"), "param-name pinId normalised and typed");
        assertEquals(0, oc.getCompound(1).getInt("tPin"));
    }

    @Test
    @DisplayName("Runtime pinId of the body reset pin is typed float:0 — existing wires resolve")
    void bodyResetPinIdStaysZero() {
        GraphNode n = new GraphNode(1, NodeType.ENCODER, 0, 0);
        assertEquals(1, n.inputs());
        assertEquals("float:0", n.inputPinId(0));
        assertEquals(0, n.inputPinIndex("float:0"));
        assertEquals("pin.create_schematic_compute.reset", NodeType.ENCODER.inputLabel(0));
    }
}
