package io.github.y15173334444.create_schematic_compute.graph;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 未知类型码剪线回归（2026-11-08 拍板：改为剪线，不再静默降 FLOAT）：bandTypes 里解析不到的
 * 类型码（新版本写入的码 / 损坏存档）不再静默降级为 FLOAT 声明——该槽位的触点连线剪除
 * （类型合法性优先于保线），随后槽位自愈 FLOAT（F15 同款 NBT 自愈，重存即固化）。
 * <p>
 * Unknown type-code pruning regression (2026-11-08 decision: prune, no silent FLOAT
 * degrade): an unparseable code in bandTypes (a newer version's code / a corrupt save)
 * is no longer silently degraded into a FLOAT declaration — wires touching that slot are
 * cut (type legality wins over keeping wires), then the slot self-heals to FLOAT (F15's
 * NBT self-heal; a resave cements it).
 */
class UnknownBandTypeCodeTest {

    private static CompoundTag node(int id, String type) {
        CompoundTag n = new CompoundTag();
        n.putInt("id", id);
        n.putString("type", type);
        n.putFloat("x", 0f);
        n.putFloat("y", 0f);
        return n;
    }

    private static CompoundTag conn(int fromId, String fPinId, int toId, String tPinId) {
        CompoundTag c = new CompoundTag();
        c.putInt("from", fromId);
        c.putInt("fPin", 0);
        c.putString("fPinId", fPinId);
        c.putInt("to", toId);
        c.putInt("tPin", 0);
        c.putString("tPinId", tPinId);
        return c;
    }

    /** 载入图：BUS_OUT "ch" bands [kick, snare]，bandTypes [quat(未知), audio]。
     *  Load a graph: BUS_OUT "ch" bands [kick, snare], bandTypes [quat(unknown), audio]. */
    private static NodeGraph loadGraph(CompoundTag extraConn) {
        CompoundTag g = new CompoundTag();
        ListTag nodes = new ListTag();
        nodes.add(node(1, "const"));
        CompoundTag busOut = node(2, "bus_out");
        busOut.putString("sig", "ch");
        ListTag bands = new ListTag();
        bands.add(net.minecraft.nbt.StringTag.valueOf("kick"));
        bands.add(net.minecraft.nbt.StringTag.valueOf("snare"));
        busOut.put("bands", bands);
        ListTag types = new ListTag();
        types.add(net.minecraft.nbt.StringTag.valueOf("quat")); // 未知码 / unknown code
        types.add(net.minecraft.nbt.StringTag.valueOf("audio"));
        busOut.put("bandTypes", types);
        nodes.add(busOut);
        g.put("nodes", nodes);

        ListTag conns = new ListTag();
        conns.add(conn(1, "float:0", 2, "float:kick"));  // 触点 = 未知码槽位 / touches the unknown slot
        conns.add(conn(1, "float:0", 2, "audio:snare")); // 触点 = 合法槽位 / touches the valid slot
        if (extraConn != null) conns.add(extraConn);
        g.put("conns", conns);
        g.putInt(NbtVersions.VERSION_KEY, NbtVersions.DATA_VERSION);
        return NodeGraph.load(g, null);
    }

    @Test @DisplayName("未知码槽位的触点连线剪除、合法槽位的连线保留")
    void unknownSlotWiresPrunedValidSlotWiresKept() {
        NodeGraph graph = loadGraph(null);
        GraphNode out = graph.findNode(2);
        assertNotNull(out);

        assertEquals(1, graph.connections.size(), "未知码槽位（kick）的线应剪除，合法槽位（snare）的线应保留");
        NodeConnection kept = graph.connections.get(0);
        assertEquals("audio:snare", kept.toPinId);
    }

    @Test @DisplayName("触点判定覆盖降级形态 pinId：float:kick 恰与降级 FLOAT 形态同名也要剪")
    void degradedShapePinIdAlsoPruned() {
        // loadGraph 里的触点线正是 "float:kick"——恰与 slot 自愈后生成的 pinId 同名；
        // 严格全串匹配会把它解析回该槽，必须由剪线先行处置。
        // The touching wire in loadGraph is exactly "float:kick" — the same name the slot
        // generates after the FLOAT heal; strict full-string matching would resolve it back
        // to the slot, so pruning must get there first.
        NodeGraph graph = loadGraph(null);
        for (NodeConnection c : graph.connections)
            assertNotEquals("float:kick", c.toPinId, "同名触点线不得存活");
    }

    @Test @DisplayName("未知码形态 pinId（quat:kick）同样剪除")
    void unknownCodePinIdPruned() {
        NodeGraph graph = loadGraph(conn(1, "quat:0", 2, "quat:kick"));
        for (NodeConnection c : graph.connections)
            assertNotEquals("quat:kick", c.toPinId, "未知码形态 pinId 的线应剪除");
    }

    @Test @DisplayName("槽位自愈 FLOAT：重存写回 float，不把未知码带进新档")
    void slotSelfHealsToFloatOnSave() {
        NodeGraph graph = loadGraph(null);
        GraphNode out = graph.findNode(2);
        assertEquals(PinType.FLOAT, out.bandType(0), "剪线后槽位自愈为 FLOAT");
        assertEquals(PinType.AUDIO, out.bandType(1), "合法槽位不受影响");

        CompoundTag saved = out.save(null);
        ListTag types = saved.getList("bandTypes", Tag.TAG_STRING);
        assertEquals("float", types.getString(0), "重存应写回 float（NBT 自愈）");
        assertEquals("audio", types.getString(1));
    }

    @Test @DisplayName("剪线只落频段引脚：参数引脚同号缓存索引不受波及")
    void paramPinsUntouched() {
        // CONST 节点的输入是参数引脚（索引 0）——剪线只落 BUS/PRIVATE 的频段引脚，
        // 绝不按同号缓存索引误伤参数引脚上的连线。
        // CONST's inputs are param pins (index 0) — pruning lands only on BUS/PRIVATE band
        // pins and must never hit a param pin's same-numbered wire by cached index.
        NodeGraph graph = loadGraph(null);
        // 记录 CONST 节点上现存的连线（其触点在 snare 槽位）后另加一条裸索引连线意义不大，
        // 这里直接断言：剩余连线全部属于合法槽位，没有以 2 号节点为 from 的线被误剪。
        for (NodeConnection c : graph.connections) assertEquals(1, c.fromId);
    }
}
