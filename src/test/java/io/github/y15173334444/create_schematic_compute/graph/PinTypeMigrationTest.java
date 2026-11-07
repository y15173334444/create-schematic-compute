package io.github.y15173334444.create_schematic_compute.graph;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V8→V9 迁移测试：pinId 带类型段改写、bandTypes 拓扑回填、解析不到剪线、子图递归。
 * V8→V9 migration: typed pinId rewrite, bandTypes topology backfill, unresolvable-wire
 * pruning, sub-graph recursion.
 */
class PinTypeMigrationTest {

    private static CompoundTag node(int id, String type, float x, float y) {
        CompoundTag n = new CompoundTag();
        n.putInt("id", id);
        n.putString("type", type);
        n.putFloat("x", x);
        n.putFloat("y", y);
        n.putInt("pcount", 0);
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

    private static String typeCode(CompoundTag g, int nodeIdx, String key) {
        ListTag types = g.getList("nodes", Tag.TAG_COMPOUND).getCompound(nodeIdx)
            .getList(key, Tag.TAG_STRING);
        return types.size() > 0 ? types.getString(0) : "";
    }

    @Test
    @DisplayName("pinId 带段化 + bandTypes 拓扑回填 / typed pinId rewrite + bandTypes backfill")
    void typedPinIdsAndBandTypeBackfill() {
        CompoundTag g = new CompoundTag();
        ListTag nodes = new ListTag();
        nodes.add(node(1, "music", 0, 0));      // out0 = AUDIO
        CompoundTag busOut = node(2, "bus_out", 0, 0);
        busOut.putString("sig", "ch");
        ListTag bands = new ListTag();
        bands.add(net.minecraft.nbt.StringTag.valueOf("band_0"));
        bands.add(net.minecraft.nbt.StringTag.valueOf("band_1"));
        busOut.put("bands", bands);
        nodes.add(busOut);
        CompoundTag busIn = node(3, "bus_in", 0, 0);
        busIn.putString("sig", "ch");
        busIn.put("bands", bands.copy());
        nodes.add(busIn);
        nodes.add(node(4, "amp", 0, 0));        // in0 = AUDIO
        nodes.add(node(5, "const", 0, 0));
        nodes.add(node(6, "add", 0, 0));
        CompoundTag formula = node(7, "formula", 0, 0);
        formula.putString("formula", "A+B");
        formula.putInt("din", 2);
        nodes.add(formula);
        g.put("nodes", nodes);

        ListTag conns = new ListTag();
        conns.add(conn(1, "0", 2, "band_0"));   // MUSIC.audio → BUS_OUT band_0
        conns.add(conn(3, "band_0", 4, "0"));   // BUS_IN band_0 → AMP.audio
        conns.add(conn(5, "0", 6, "0"));        // CONST → ADD
        conns.add(conn(5, "0", 7, "A"));        // CONST → FORMULA.A
        conns.add(conn(5, "0", 7, "zzz"));      // 解析不到 → 剪除 / unresolvable → pruned
        g.put("conns", conns);
        g.putInt(NbtVersions.VERSION_KEY, 8);

        CompoundTag out = GraphMigration.migrate(g, null);
        assertEquals(NbtVersions.DATA_VERSION, NbtVersions.getVersion(out));

        // pinId 带段化：频段类型由拓扑回填（band_0 对端是 AUDIO → audio；band_1 无连线 → float）
        // Typed pinIds: band types backfilled from topology (band_0 peers AUDIO → audio;
        // band_1 unwired → float).
        ListTag oc = out.getList("conns", Tag.TAG_COMPOUND);
        assertEquals(4, oc.size(), "the unresolvable wire is pruned");
        assertEquals("audio:0", oc.getCompound(0).getString("fPinId"));
        assertEquals("audio:band_0", oc.getCompound(0).getString("tPinId"));
        assertEquals("audio:band_0", oc.getCompound(1).getString("fPinId"));
        assertEquals("audio:0", oc.getCompound(1).getString("tPinId"));
        assertEquals("float:0", oc.getCompound(2).getString("fPinId"));
        assertEquals("float:0", oc.getCompound(2).getString("tPinId"));
        assertEquals("float:A", oc.getCompound(3).getString("tPinId"));

        // bandTypes 写入：逐频段声明 / bandTypes written per band
        assertEquals("audio", typeCode(out, 1, "bandTypes"));
        ListTag outTypes = out.getList("nodes", Tag.TAG_COMPOUND).getCompound(1)
            .getList("bandTypes", Tag.TAG_STRING);
        assertEquals(2, outTypes.size());
        assertEquals("float", outTypes.getString(1), "unwired band_1 stays float");
        assertEquals("audio", typeCode(out, 2, "bandTypes"));
    }

    @Test
    @DisplayName("旧档含 ':' 的频段名不被误读成类型段 / legacy band names with ':' are not misread as typed")
    void colonBandNameKept() {
        CompoundTag g = new CompoundTag();
        ListTag nodes = new ListTag();
        CompoundTag busOut = node(1, "bus_out", 0, 0);
        busOut.putString("sig", "ch");
        ListTag bands = new ListTag();
        bands.add(net.minecraft.nbt.StringTag.valueOf("float:x"));
        busOut.put("bands", bands);
        nodes.add(busOut);
        nodes.add(node(2, "const", 0, 0));
        g.put("nodes", nodes);

        ListTag conns = new ListTag();
        // 旧档裸参 pinId "float:x" = 频段名（不是「类型 float + 参段 x」）
        // The legacy bare pinId "float:x" is a band name (not typed float + param "x").
        conns.add(conn(2, "0", 1, "float:x"));
        g.put("conns", conns);
        g.putInt(NbtVersions.VERSION_KEY, 8);

        CompoundTag out = GraphMigration.migrate(g, null);
        ListTag oc = out.getList("conns", Tag.TAG_COMPOUND);
        assertEquals(1, oc.size(), "the wire must survive");
        assertEquals("float:float:x", oc.getCompound(0).getString("tPinId"),
            "bare reading wins: param stays the whole legacy name");
    }

    @Test
    @DisplayName("规则③已删：带段但类型码过期的 dev 形态剪除，不再剥段改写 / rule ③ deleted: a typed pinId with a stale code is pruned, never strip-rewritten")
    void staleCodedTypedPinIdPrunedNotRewritten() {
        // dev 中间形态：pinId 已带段但类型码过期（"float:0" 对 MUSIC 的 audio 出脚）。
        // 规则③（剥段读取）曾把它改写成 "audio:0"；按「未发版形态不写兼容」删除后，
        // 解析不到即剪。裸参读取（规则②）不受影响——见 colonBandNameKept。
        // Dev intermediate shape: a typed pinId whose code went stale ("float:0" against
        // MUSIC's audio output). Rule ③ (strip-segment reading) used to rewrite it to
        // "audio:0"; deleted under "no compat for unreleased shapes" — unresolvable now
        // means pruned. Bare-param reading (rule ②) is unaffected — see colonBandNameKept.
        CompoundTag g = new CompoundTag();
        ListTag nodes = new ListTag();
        nodes.add(node(1, "music", 0, 0)); // out0 = AUDIO
        nodes.add(node(2, "amp", 0, 0));   // in0 = AUDIO
        g.put("nodes", nodes);

        ListTag conns = new ListTag();
        conns.add(conn(1, "float:0", 2, "float:0")); // 类型码过期 / stale type code
        g.put("conns", conns);
        g.putInt(NbtVersions.VERSION_KEY, 8);

        CompoundTag out = GraphMigration.migrate(g, null);
        ListTag oc = out.getList("conns", Tag.TAG_COMPOUND);
        assertEquals(0, oc.size(),
            "带段但类型码过期的 pinId 解析不到即剪（规则③删除的拍板代价：dev 档这些线会丢）");
    }

    @Test
    @DisplayName("已带段 pinId 原样保留（幂等）；悬空连线剪除 / already-typed pinIds kept (idempotent); dangling wires pruned")
    void typedPinIdsKeptAndDanglingPruned() {
        CompoundTag g = new CompoundTag();
        ListTag nodes = new ListTag();
        nodes.add(node(1, "const", 0, 0));
        nodes.add(node(2, "add", 0, 0));
        g.put("nodes", nodes);

        ListTag conns = new ListTag();
        conns.add(conn(1, "float:0", 2, "float:0")); // 已带段 / already typed
        CompoundTag dangling = conn(99, "float:0", 2, "float:1");
        conns.add(dangling);                        // 悬空 from / dangling source
        g.put("conns", conns);
        g.putInt(NbtVersions.VERSION_KEY, 8);

        CompoundTag out = GraphMigration.migrate(g, null);
        ListTag oc = out.getList("conns", Tag.TAG_COMPOUND);
        assertEquals(1, oc.size(), "dangling wire pruned");
        assertEquals("float:0", oc.getCompound(0).getString("fPinId"), "already-typed pinId kept");
        assertEquals("float:0", oc.getCompound(0).getString("tPinId"));
    }

    @Test
    @DisplayName("子图递归迁移 / sub-graphs migrate recursively")
    void subGraphRecursion() {
        CompoundTag sub = new CompoundTag();
        ListTag subNodes = new ListTag();
        subNodes.add(node(11, "const", 0, 0));
        subNodes.add(node(12, "add", 0, 0));
        sub.put("nodes", subNodes);
        ListTag subConns = new ListTag();
        subConns.add(conn(11, "0", 12, "0"));
        sub.put("conns", subConns);
        sub.putInt(NbtVersions.VERSION_KEY, 8);

        CompoundTag g = new CompoundTag();
        ListTag nodes = new ListTag();
        CompoundTag encap = node(1, "encapsulation", 0, 0);
        encap.put("subGraph", sub);
        nodes.add(encap);
        g.put("nodes", nodes);
        g.put("conns", new ListTag());
        g.putInt(NbtVersions.VERSION_KEY, 8);

        CompoundTag out = GraphMigration.migrate(g, null);
        CompoundTag subOut = out.getList("nodes", Tag.TAG_COMPOUND).getCompound(0)
            .getCompound("subGraph");
        assertEquals(NbtVersions.DATA_VERSION, NbtVersions.getVersion(subOut), "sub-graph stamped");
        ListTag sc = subOut.getList("conns", Tag.TAG_COMPOUND);
        assertEquals(1, sc.size());
        assertEquals("float:0", sc.getCompound(0).getString("fPinId"), "sub-graph pinIds typed");
        assertEquals("float:0", sc.getCompound(0).getString("tPinId"));
    }

    @Test
    @DisplayName("PRIVATE 频道类型单元素回填 / private channel type backfills as a single entry")
    void privateChannelBackfill() {
        CompoundTag g = new CompoundTag();
        ListTag nodes = new ListTag();
        nodes.add(node(1, "music", 0, 0));
        CompoundTag privOut = node(2, "private_out", 0, 0);
        privOut.putString("sig", "p");
        nodes.add(privOut);
        nodes.add(node(3, "const", 0, 0));
        g.put("nodes", nodes);

        ListTag conns = new ListTag();
        conns.add(conn(1, "0", 2, "0"));  // MUSIC.audio → PRIVATE_OUT（对端 AUDIO → 频道声明 audio）
        g.put("conns", conns);
        g.putInt(NbtVersions.VERSION_KEY, 8);

        CompoundTag out = GraphMigration.migrate(g, null);
        ListTag oc = out.getList("conns", Tag.TAG_COMPOUND);
        assertEquals(1, oc.size());
        assertEquals("audio:0", oc.getCompound(0).getString("tPinId"),
            "private pin typed from its declared channel type");
        ListTag types = out.getList("nodes", Tag.TAG_COMPOUND).getCompound(1)
            .getList("bandTypes", Tag.TAG_STRING);
        assertEquals(1, types.size(), "private channels declare a single type");
        assertEquals("audio", types.getString(0));
    }
}
