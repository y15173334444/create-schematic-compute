package io.github.y15173334444.create_schematic_compute.graph;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 频段空声明（v1.3.0）与连接时自动声明的模型层回归：EMPTY 是声明表的「未声明」初值——
 * NBT 往返保留空态（不进未知码剪线/自愈路径）、SET_PIN_TYPE 拒收回空（声明只许 空→实）、
 * 空→实改声明走类型换段重绑且对端匹配的线保留、生效类型合并把空态当最弱。
 * <p>
 * Model-layer regressions for the v1.3.0 empty band declaration and connect-time
 * auto-declaration: EMPTY is the declaration table's "undeclared" initial value — the
 * NBT round-trip preserves it (never entering the unknown-code prune/self-heal path),
 * SET_PIN_TYPE refuses to set it back (declarations only go empty→real), an empty→real
 * retype runs the type-segment rebind keeping peer-matched wires, and the effective-type
 * merge treats empty as the weakest state.
 */
class BandAutoDeclareTest {

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

    /** 载入图：VEC3_CONST(1) → BUS_OUT(2) "t" bands [ba, bb]，bandTypes [empty, vec3]。
     *  ba 上一条触点线（empty:ba），bb 上一条对照线（vec3:bb）。
     *  Load a graph: VEC3_CONST(1) → BUS_OUT(2) "t" bands [ba, bb], bandTypes [empty, vec3];
     *  one touching wire on ba (empty:ba) and one control wire on bb (vec3:bb). */
    private static NodeGraph loadGraph(CompoundTag extraConn) {
        CompoundTag g = new CompoundTag();
        ListTag nodes = new ListTag();
        nodes.add(node(1, "vec3_const"));
        CompoundTag busOut = node(2, "bus_out");
        busOut.putString("sig", "t");
        ListTag bands = new ListTag();
        bands.add(net.minecraft.nbt.StringTag.valueOf("ba"));
        bands.add(net.minecraft.nbt.StringTag.valueOf("bb"));
        busOut.put("bands", bands);
        ListTag types = new ListTag();
        types.add(net.minecraft.nbt.StringTag.valueOf("empty")); // 空声明 / the empty declaration
        types.add(net.minecraft.nbt.StringTag.valueOf("vec3"));
        busOut.put("bandTypes", types);
        nodes.add(busOut);
        g.put("nodes", nodes);

        ListTag conns = new ListTag();
        conns.add(conn(1, "vec3:0", 2, "empty:ba")); // 触点 = 空槽 / touches the empty slot
        conns.add(conn(1, "vec3:0", 2, "vec3:bb"));  // 对照 / control
        if (extraConn != null) conns.add(extraConn);
        g.put("conns", conns);
        g.putInt(NbtVersions.VERSION_KEY, NbtVersions.DATA_VERSION);
        return NodeGraph.load(g, null);
    }

    @Test
    @DisplayName("空槽载入保留：不进未知码剪线/自愈路径，触点线存活 / empty slot survives load — no prune, no heal")
    void emptySlotSurvivesLoadUntouched() {
        NodeGraph graph = loadGraph(null);
        GraphNode out = graph.findNode(2);
        assertNotNull(out);
        assertEquals(PinType.EMPTY, out.bandType(0), "empty 是可解析码，槽位不自愈 FLOAT");
        assertEquals(PinType.VEC3, out.bandType(1));
        assertEquals(2, graph.connections.size(), "空槽触点线不剪（对照未知码剪线语义）");
        assertEquals(PinType.EMPTY, out.inputType(0), "声明表原样供分派入口读取");
    }

    @Test
    @DisplayName("NBT 往返保留空态：重存写回 empty / the NBT round-trip preserves empty")
    void emptyCodeRoundTripsThroughSave() {
        NodeGraph graph = loadGraph(null);
        GraphNode out = graph.findNode(2);
        CompoundTag saved = out.save(null);
        ListTag types = saved.getList("bandTypes", Tag.TAG_STRING);
        assertEquals("empty", types.getString(0), "空槽重存写回 empty（不是 float）");
        assertEquals("vec3", types.getString(1));

        NodeGraph reloaded = loadGraph(null);
        assertEquals(PinType.EMPTY, reloaded.findNode(2).bandType(0));
        assertSame(PinType.EMPTY, PinType.byCode("empty"), "empty 是可解析的稳定码");
    }

    @Test
    @DisplayName("SET_PIN_TYPE 拒收回空：声明只许 空→实 / SET_PIN_TYPE refuses empty — declarations only go empty→real")
    void setPinTypeRefusesEmpty() {
        NodeGraph g = new NodeGraph();
        GraphNode busOut = g.addNode(NodeType.BUS_OUT, 0, 0);
        busOut.signalName = "t";
        busOut.signalBands = new ArrayList<>(List.of("ba"));
        busOut.bandTypes = new ArrayList<>(List.of(PinType.VEC3));
        OpExecutor.apply(g, GraphOp.setPinType(null, -1, busOut.id, 0, PinType.EMPTY.code, null));
        assertEquals(PinType.VEC3, busOut.bandType(0), "回空 op 被拒收，显式声明保持");

        busOut.bandTypes.set(0, PinType.EMPTY);
        OpExecutor.apply(g, GraphOp.setPinType(null, -1, busOut.id, 0, PinType.EMPTY.code, null));
        assertEquals(PinType.EMPTY, busOut.bandType(0), "空槽上的回空重放是空操作");
    }

    @Test
    @DisplayName("空→实改声明换段重绑：对端匹配的线保留 / an empty→real retype rebinds the segment and keeps matched wires")
    void emptyToRealRetypeRebindsAndKeepsMatchedWires() {
        NodeGraph graph = loadGraph(null);
        GraphNode out = graph.findNode(2);

        OpExecutor.apply(graph, GraphOp.setPinType(null, -1, out.id, 0, PinType.VEC3.code, null));
        assertEquals(PinType.VEC3, out.bandType(0));
        assertEquals(2, graph.connections.size(), "对端 VEC3 匹配，线全部保留");
        for (NodeConnection c : graph.connections)
            assertNotEquals("empty:ba", c.toPinId, "旧空段 pinId 应已换段");
        assertTrue(graph.connections.stream().anyMatch(c -> "vec3:ba".equals(c.toPinId)),
            "触点线换段到 vec3:ba");
    }

    @Test
    @DisplayName("生效类型合并：空态最弱，让位频道定义 / effective merge: empty is weakest, yields to the channel definition")
    void effectiveMergeTreatsEmptyAsWeakest() {
        assertEquals(PinType.EMPTY, PinType.effective(null, null));
        assertEquals(PinType.EMPTY, PinType.effective(PinType.EMPTY, null));
        assertEquals(PinType.EMPTY, PinType.effective(PinType.EMPTY, PinType.EMPTY));
        assertEquals(PinType.FLOAT, PinType.effective(PinType.EMPTY, PinType.FLOAT), "空让位给频道定义");
        assertEquals(PinType.VEC3, PinType.effective(PinType.EMPTY, PinType.VEC3));
        assertEquals(PinType.AUDIO, PinType.effective(PinType.EMPTY, PinType.AUDIO));
        assertEquals(PinType.FLOAT, PinType.effective(PinType.FLOAT, PinType.EMPTY));
        assertEquals(PinType.FLOAT, PinType.effective(PinType.FLOAT, null));
        assertEquals(PinType.VEC3, PinType.effective(PinType.FLOAT, PinType.VEC3), "强序 AUDIO > VEC3 > 本地");
        assertEquals(PinType.AUDIO, PinType.effective(PinType.VEC3, PinType.AUDIO));
    }

    @Test
    @DisplayName("类型码发布保留空态：bandTypeCodes 出 empty / the published codes keep empty")
    void bandTypeCodesEmitEmpty() {
        NodeGraph graph = loadGraph(null);
        GraphNode out = graph.findNode(2);
        assertEquals(List.of("empty", "vec3"), out.bandTypeCodes());
        assertEquals(PinType.EMPTY, out.bandTypeMap().get("ba"));
    }

    @Test
    @DisplayName("落线即声明：空槽按对端类型自动声明且 pinId 带段 / landing a wire declares the empty slot (pinId carries the type)")
    void connectDeclaresEmptySlotFromPeer() {
        NodeGraph g = new NodeGraph();
        GraphNode vec = g.addNode(NodeType.VEC3_CONST, 0, 0);
        GraphNode busOut = g.addNode(NodeType.BUS_OUT, 100, 0);
        busOut.signalName = "t";
        busOut.signalBands = new ArrayList<>(List.of("ba"));
        busOut.bandTypes = new ArrayList<>(List.of(PinType.EMPTY));
        assertTrue(g.addConnection(vec.id, 0, busOut.id, 0), "空槽落线先声明后过门");
        assertEquals(PinType.VEC3, busOut.bandType(0), "声明 := 对端类型");
        assertEquals("vec3:ba", g.connections.get(0).toPinId);
    }

    @Test
    @DisplayName("灰态频段拒接（下游门）：空↔空暗桥与暗源都拒 / grey band pins refuse wires (the downstream gate)")
    void darkBandPinsRefuseWires() {
        NodeGraph g = new NodeGraph();
        GraphNode busIn = g.addNode(NodeType.BUS_IN, 0, 0);
        busIn.signalName = "s";
        busIn.signalBands = new ArrayList<>(List.of("ba"));
        busIn.bandTypes = new ArrayList<>(List.of(PinType.EMPTY));
        GraphNode busOut = g.addNode(NodeType.BUS_OUT, 100, 0);
        busOut.signalName = "t";
        busOut.signalBands = new ArrayList<>(List.of("bb"));
        busOut.bandTypes = new ArrayList<>(List.of(PinType.EMPTY));
        assertFalse(g.addConnection(busIn.id, 0, busOut.id, 0), "空↔空暗桥拒接");
        assertTrue(g.connections.isEmpty());

        busOut.bandTypes.set(0, PinType.VEC3);
        assertFalse(g.addConnection(busIn.id, 0, busOut.id, 0), "暗源对显式目标也拒（上游未连线/声明）");
        assertTrue(g.connections.isEmpty());
    }

    @Test
    @DisplayName("断开一律回灰、重接自动恢复（撤销形态）/ disconnect always greys; reconnect re-declares (the undo shape)")
    void disconnectRevertsAndReconnectRedeclares() {
        NodeGraph g = new NodeGraph();
        GraphNode vec = g.addNode(NodeType.VEC3_CONST, 0, 0);
        GraphNode busOut = g.addNode(NodeType.BUS_OUT, 100, 0);
        busOut.signalName = "t";
        busOut.signalBands = new ArrayList<>(List.of("ba"));
        busOut.bandTypes = new ArrayList<>(List.of(PinType.VEC3)); // 存量声明（任意来源）也清 / an existing declaration from any source clears too
        assertTrue(g.addConnection(vec.id, 0, busOut.id, 0));
        g.removeConnection(vec.id, 0, busOut.id, 0);
        assertEquals(PinType.EMPTY, busOut.bandType(0), "上游断开一律回灰（手动声明同样清）");
        assertTrue(g.addConnection(vec.id, 0, busOut.id, 0), "撤销重接走同一声明规则");
        assertEquals(PinType.VEC3, busOut.bandType(0));
    }

    @Test
    @DisplayName("pinId 删除路径同样回灰 / the pinId removal path reverts to grey too")
    void removeConnectionByPinIdsAlsoRevertsToEmpty() {
        NodeGraph g = new NodeGraph();
        GraphNode vec = g.addNode(NodeType.VEC3_CONST, 0, 0);
        GraphNode busOut = g.addNode(NodeType.BUS_OUT, 100, 0);
        busOut.signalName = "t";
        busOut.signalBands = new ArrayList<>(List.of("ba"));
        busOut.bandTypes = new ArrayList<>(List.of(PinType.VEC3));
        assertTrue(g.addConnection(vec.id, 0, busOut.id, 0));
        assertTrue(g.removeConnectionByPinIds(vec.id, "vec3:0", busOut.id, "vec3:ba"));
        assertEquals(PinType.EMPTY, busOut.bandType(0));
    }

    @Test
    @DisplayName("存档自愈：未连接槽位加载即回灰、已连槽位保留 / the save self-heal: unwired slots grey at load, wired slots keep")
    void saveHealGreysUnwiredSlotsAtLoad() {
        CompoundTag g = new CompoundTag();
        ListTag nodes = new ListTag();
        nodes.add(node(1, "vec3_const"));
        CompoundTag busOut = node(2, "bus_out");
        busOut.putString("sig", "t");
        ListTag bands = new ListTag();
        bands.add(net.minecraft.nbt.StringTag.valueOf("wired"));
        bands.add(net.minecraft.nbt.StringTag.valueOf("orphan"));
        busOut.put("bands", bands);
        ListTag types = new ListTag();
        types.add(net.minecraft.nbt.StringTag.valueOf("vec3"));  // 有线：保留 / wired: kept
        types.add(net.minecraft.nbt.StringTag.valueOf("float")); // 无线：旧档存量声明 / unwired: legacy declaration
        busOut.put("bandTypes", types);
        nodes.add(busOut);
        g.put("nodes", nodes);
        ListTag conns = new ListTag();
        conns.add(conn(1, "vec3:0", 2, "vec3:wired"));
        g.put("conns", conns);
        g.putInt(NbtVersions.VERSION_KEY, NbtVersions.DATA_VERSION);

        NodeGraph graph = NodeGraph.load(g, null);
        GraphNode out = graph.findNode(2);
        assertEquals(PinType.VEC3, out.bandType(0), "已连槽位声明保留");
        assertEquals(PinType.EMPTY, out.bandType(1), "未连接槽位存档自愈回灰（旧档存量声明不带进新会话）");
    }

    @Test
    @DisplayName("回灰剪下游线：对齐把空声明带到订阅侧即剪对端不匹配线 / reverting to grey prunes downstream wires")
    void emptyRetypePrunesDownstreamWires() {
        NodeGraph g = new NodeGraph();
        GraphNode busIn = g.addNode(NodeType.BUS_IN, 0, 0);
        busIn.signalName = "s";
        busIn.signalBands = new ArrayList<>(List.of("ba"));
        busIn.bandTypes = new ArrayList<>(List.of(PinType.AUDIO));
        GraphNode busOut = g.addNode(NodeType.BUS_OUT, 100, 0);
        busOut.signalName = "t";
        busOut.signalBands = new ArrayList<>(List.of("bb"));
        busOut.bandTypes = new ArrayList<>(List.of(PinType.AUDIO));
        assertTrue(g.addConnection(busIn.id, 0, busOut.id, 0), "音频下游桥线");
        // 发布端回灰经收敛到达订阅侧（对齐路径统一走 reconcileBands）
        g.reconcileBands(busIn, new ArrayList<>(List.of("ba")), new ArrayList<>(List.of(PinType.EMPTY)));
        assertEquals(PinType.EMPTY, busIn.bandType(0));
        assertTrue(g.connections.isEmpty(), "对端声明（音频）与空不匹配——按剪线契约剪除");
    }

    @Test
    @DisplayName("连接声明判定（全切换单一源）：空补/实改/同型与空对端不动 / the connect-declaration decision (full switch, single source)")
    void connectDeclarationDecision() {
        // 空槽补声明 / empty slots fill
        assertEquals(PinType.FLOAT, PinType.autoDeclareFrom(PinType.EMPTY, PinType.FLOAT));
        assertEquals(PinType.AUDIO, PinType.autoDeclareFrom(PinType.EMPTY, PinType.AUDIO));
        assertEquals(PinType.VEC3, PinType.autoDeclareFrom(PinType.EMPTY, PinType.VEC3));
        // 显式声明随连接改写（全切换）/ explicit declarations rewrite on connect
        assertEquals(PinType.VEC3, PinType.autoDeclareFrom(PinType.FLOAT, PinType.VEC3));
        assertEquals(PinType.FLOAT, PinType.autoDeclareFrom(PinType.VEC3, PinType.FLOAT));
        assertEquals(PinType.VEC3, PinType.autoDeclareFrom(PinType.AUDIO, PinType.VEC3));
        assertEquals(PinType.VEC3, PinType.autoDeclareFrom(null, PinType.VEC3), "标记槽同属非显式声明");
        // 同型与空对端不动 / matching type or empty peer inert
        assertNull(PinType.autoDeclareFrom(PinType.FLOAT, PinType.FLOAT), "同型免发空 op");
        assertNull(PinType.autoDeclareFrom(PinType.EMPTY, PinType.EMPTY));
        assertNull(PinType.autoDeclareFrom(PinType.EMPTY, null));
        assertNull(PinType.autoDeclareFrom(PinType.FLOAT, PinType.EMPTY), "空对端不回空");
        assertNull(PinType.autoDeclareFrom(PinType.FLOAT, null));
    }

    @Test
    @DisplayName("被拒的连接不改声明（录取先行）/ a rejected connect leaves the declaration alone (admission first)")
    void rejectedConnectLeavesDeclaration() {
        NodeGraph g = new NodeGraph();
        GraphNode vec = g.addNode(NodeType.VEC3_CONST, 0, 0);
        GraphNode music = g.addNode(NodeType.MUSIC, 0, 50);
        GraphNode busOut = g.addNode(NodeType.BUS_OUT, 100, 0);
        busOut.signalName = "t";
        busOut.signalBands = new ArrayList<>(List.of("ba"));
        busOut.bandTypes = new ArrayList<>(List.of(PinType.FLOAT));
        // 自环拒接不改声明 / a self-loop is refused and leaves the declaration alone
        assertFalse(g.addConnectionWithPinIds(busOut.id, "float:ba", busOut.id, "float:ba"));
        assertEquals(PinType.FLOAT, busOut.bandType(0));
        // 落线即全切换：显式 FLOAT 随 VEC3 线改写 / landing switches an explicit declaration
        assertTrue(g.addConnection(vec.id, 0, busOut.id, 0), "全切换后类型匹配过门");
        assertEquals(PinType.VEC3, busOut.bandType(0), "模型层全切换（显式声明随连接改写）");
        // 占用拒接不改声明 / an occupied input is refused and leaves the declaration alone
        assertFalse(g.addConnection(music.id, 0, busOut.id, 0), "占用拒接");
        assertEquals(PinType.VEC3, busOut.bandType(0), "占用拒接不被音频源改写");
        assertEquals(1, g.connections.size());
    }

    @Test
    @DisplayName("未摘掉线的 REMOVE_CONN 不回灰 / a remove that removed nothing does not grey the slot")
    void staleRemoveDoesNotGrey() {
        NodeGraph g = new NodeGraph();
        GraphNode vec = g.addNode(NodeType.VEC3_CONST, 0, 0);
        GraphNode busOut = g.addNode(NodeType.BUS_OUT, 100, 0);
        busOut.signalName = "t";
        busOut.signalBands = new ArrayList<>(List.of("ba"));
        busOut.bandTypes = new ArrayList<>(List.of(PinType.EMPTY));
        assertTrue(g.addConnection(vec.id, 0, busOut.id, 0));
        g.removeConnection(vec.id, 0, busOut.id, 0);
        assertEquals(PinType.EMPTY, busOut.bandType(0), "真摘线回灰");
        assertTrue(g.addConnection(vec.id, 0, busOut.id, 0));
        assertEquals(PinType.VEC3, busOut.bandType(0));
        g.removeConnection(999, 0, busOut.id, 0); // 迟到/错位的摘除请求 / a late or misrouted removal
        assertEquals(PinType.VEC3, busOut.bandType(0), "没摘掉线的请求不清声明");
        assertEquals(1, g.connections.size(), "活线不受影响");
    }

    @Test
    @DisplayName("无型信息的新同步槽缺省 EMPTY（灰不被拨回）/ a synced-in slot without type info defaults to EMPTY")
    void reconcileNewSlotDefaultsEmpty() {
        NodeGraph g = new NodeGraph();
        GraphNode busIn = g.addNode(NodeType.BUS_IN, 0, 0);
        busIn.signalName = "s";
        busIn.signalBands = new ArrayList<>();
        busIn.bandTypes = new ArrayList<>();
        g.reconcileBands(busIn, new ArrayList<>(List.of("ba")), null);
        assertEquals(PinType.EMPTY, busIn.bandType(0), "新槽缺省 EMPTY（旧 FLOAT 缺省会把灰拨回）");
    }

    @Test
    @DisplayName("私有频道同口径：落线即声明、摘线即回灰 / the private channel follows the same wire lifecycle")
    void privateOutFollowsWireLifecycle() {
        NodeGraph g = new NodeGraph();
        GraphNode vec = g.addNode(NodeType.VEC3_CONST, 0, 0);
        GraphNode priv = g.addNode(NodeType.PRIVATE_OUT, 100, 0);
        priv.signalName = "p";
        priv.bandTypes = new ArrayList<>(List.of(PinType.EMPTY));
        assertTrue(g.addConnection(vec.id, 0, priv.id, 0), "私有槽落线即声明");
        assertEquals(PinType.VEC3, priv.bandType(0));
        g.removeConnection(vec.id, 0, priv.id, 0);
        assertEquals(PinType.EMPTY, priv.bandType(0), "摘线即回灰");
    }

    @Test
    @DisplayName("存档自愈覆盖私有频道槽 / the save self-heal covers private channel slots too")
    void saveHealCoversPrivateSlots() {
        CompoundTag g = new CompoundTag();
        ListTag nodes = new ListTag();
        CompoundTag priv = node(1, "private_out");
        priv.putString("sig", "p");
        ListTag types = new ListTag();
        types.add(net.minecraft.nbt.StringTag.valueOf("vec3")); // 无线的存量声明 / legacy declaration without a wire
        priv.put("bandTypes", types);
        nodes.add(priv);
        g.put("nodes", nodes);
        g.put("conns", new ListTag());
        g.putInt(NbtVersions.VERSION_KEY, NbtVersions.DATA_VERSION);
        NodeGraph graph = NodeGraph.load(g, null);
        assertEquals(PinType.EMPTY, graph.findNode(1).bandType(0), "未连接的私有槽加载即回灰");
    }

    @Test
    @DisplayName("私有编辑区高度账本覆盖两行（频道行+类型片行）/ the private edit-height ledger covers both rows")
    void privateEditHeightCoversChipRow() {
        NodeGraph g = new NodeGraph();
        GraphNode priv = g.addNode(NodeType.PRIVATE_OUT, 0, 0);
        // 绘制几何：频道行 y=4..20、类型片行 y=23..35 —— 面板高必须 ≥ 36（缺行会让类型片
        // 戳出面板底 = 穿模）。/ Drawn geometry: channel row y=4..20, chip row y=23..35 —
        // the panel must be ≥ 36 tall (a missing row clips the chip out of the panel).
        assertTrue(SpatialIndex.editHeight(priv) >= 36,
            "私有编辑区两行必须装进面板高度账本（否则类型片行戳出面板底）");
    }

    @Test
    @DisplayName("新建私有节点自带空槽：落线即声明、芯片随线变色 / a fresh private node ships its empty slot — the wire declares and the chip follows")
    void freshPrivateNodeDeclaresFromWire() {
        NodeGraph g = new NodeGraph();
        GraphNode vec = g.addNode(NodeType.VEC3_CONST, 0, 0);
        GraphNode priv = g.addNode(NodeType.PRIVATE_OUT, 100, 0);
        priv.signalName = "p";
        assertEquals(PinType.EMPTY, priv.bandType(0), "新建私有节点的单槽初始为空（灰），不是降级 f32");
        assertTrue(g.addConnection(vec.id, 0, priv.id, 0), "VEC3 线应能落上空槽（先声明后过门）");
        assertEquals(PinType.VEC3, priv.bandType(0), "落线即声明——芯片随线变 v3");
    }
}
