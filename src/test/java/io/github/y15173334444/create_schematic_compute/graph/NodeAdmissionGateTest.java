package io.github.y15173334444.create_schematic_compute.graph;

import io.github.y15173334444.create_schematic_compute.graph.BlockNodeAllowances;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P2-7 准入服务端化 + K10 数量上限服务端化的回归锚（2026-10-31 拍板批）。
 * 门本体 {@link NodeAdmission#violation} 是纯函数，直接行为锚；服务端接线
 * （EditSessionRegistry.applyOp / GraphHost.loadHostNBT / GraphRemoteApplier 回滚 /
 * GraphEditor 委托）用源码锚钉住调用位与次序（applyOp 需 ServerLevel，造不出来）。
 * Regression anchors for the P2-7 server-side admission gate + K10 server-side count cap
 * (2026-10-31 ruling batch). The gate itself ({@link NodeAdmission#violation}) is a pure
 * function and gets direct behavioural anchors; the server wiring (applyOp / loadHostNBT /
 * the applier rollback / the editor delegate) is pinned by source anchors since applyOp
 * needs a ServerLevel that headless tests cannot construct.
 *
 * <p>取型说明：超名单用 AUDIO 类的 AMP 而非 REDSTONE_OUT——后者构造路径分配
 * {@code ItemStack.EMPTY}（GraphNode 构造器对 REDSTONE_IN/OUT 建频率槽），纯 JUnit
 * 无注册表引导起不来（GraphNodeImageSizeTest 头注同款禁区）。</p>
 */
class NodeAdmissionGateTest {

    private static final BlockPos POS = BlockPos.ZERO;
    private static final UUID ACTOR = UUID.nameUUIDFromBytes("admission".getBytes());

    /** 前提自检：MONITOR 名单含 CONST、不含 REDSTONE_OUT —— 后续用例的取型前提。
     *  Precondition: the MONITOR allowlist admits CONST and refuses REDSTONE_OUT — the
     *  type choices below depend on it. */
    @Test
    @DisplayName("前提：MONITOR 名单放行 CONST、拒绝 REDSTONE_OUT / precondition: MONITOR list")
    void monitorListPremise() {
        assertTrue(BlockNodeAllowances.MONITOR.allows(NodeType.CONST), "CONST in MONITOR list");
        assertFalse(BlockNodeAllowances.MONITOR.allows(NodeType.AMP),
            "AMP (audio) outside MONITOR list");
    }

    @Test
    @DisplayName("名单内加点放行 / in-allowlist add passes")
    void inListAddPasses() {
        var op = new GraphOp(OpType.ADD_NODE, POS, -1, 0, NodeType.CONST, 0f, 0f, 0L, ACTOR);
        assertNull(NodeAdmission.violation(op, BlockNodeAllowances.MONITOR, java.util.Set.of(), new NodeGraph()));
    }

    @Test
    @DisplayName("超名单加点拒绝（改包客户端进路收口）/ out-of-allowlist add refused")
    void outOfListAddRejected() {
        var op = new GraphOp(OpType.ADD_NODE, POS, -1, 0, NodeType.AMP, 0f, 0f, 0L, ACTOR);
        String v = NodeAdmission.violation(op, BlockNodeAllowances.MONITOR, java.util.Set.of(), new NodeGraph());
        assertNotNull(v, "menu-excluded type must be refused");
        assertTrue(v.contains("nodeType=amp "), "violation names the type id: " + v);
    }

    @Test
    @DisplayName("ADD_NODE_REQUEST 同门（菜单加点形态）/ ADD_NODE_REQUEST rides the same gate")
    void requestShapeAlsoGated() {
        var ok = GraphOp.addNodeRequest(POS, -1, -7, NodeType.CONST, 0f, 0f, ACTOR);
        assertNull(NodeAdmission.violation(ok, BlockNodeAllowances.MONITOR, java.util.Set.of(), new NodeGraph()));
        var bad = GraphOp.addNodeRequest(POS, -1, -7, NodeType.AMP, 0f, 0f, ACTOR);
        assertNotNull(NodeAdmission.violation(bad, BlockNodeAllowances.MONITOR, java.util.Set.of(), new NodeGraph()));
    }

    @Test
    @DisplayName("旧档兼容：装载普查在场即放行（撤销恢复不受损）/ legacy census admits")
    void legacyCensusAdmits() {
        // 旧档形态：装载时图里已有 REDSTONE_OUT（旧版本名单允许），现行 MONITOR 名单已不含。
        // Legacy shape: the loaded graph already carried REDSTONE_OUT under an older
        // allowance; the current MONITOR list no longer admits it.
        var g = new NodeGraph();
        g.addNode(NodeType.AMP, 0, 0);
        var census = NodeAdmission.census(g);
        assertTrue(census.contains(NodeType.AMP));
        var op = new GraphOp(OpType.ADD_NODE, POS, -1, 0, NodeType.AMP, 0f, 0f, 0L, ACTOR);
        assertNull(NodeAdmission.violation(op, BlockNodeAllowances.MONITOR, census, new NodeGraph()),
            "a type present at load stays admissible for the session");
    }

    @Test
    @DisplayName("普查是装载时快照：删光后仍放行（A 口径非 op 时在场判定）/ census is a load-time snapshot")
    void censusIsSnapshotNotLivePresence() {
        var g = new NodeGraph();
        var n = g.addNode(NodeType.AMP, 0, 0);
        var census = NodeAdmission.census(g);
        // 装载后用户删光该类型，再撤销 → ADD_NODE 仍须放行（判定吃装载快照而非实时在场）。
        // After load the user deletes every instance, then undoes → the ADD_NODE must still
        // pass (the gate reads the load-time snapshot, not live presence).
        g.removeNode(n.id);
        assertTrue(g.nodes.isEmpty());
        var op = new GraphOp(OpType.ADD_NODE, POS, -1, 0, NodeType.AMP, 0f, 0f, 0L, ACTOR);
        assertNull(NodeAdmission.violation(op, BlockNodeAllowances.MONITOR, census, g));
    }

    @Test
    @DisplayName("普查递归封装子图 / census recurses encapsulated sub-graphs")
    void censusRecursesSubGraphs() {
        var main = new NodeGraph();
        var encap = main.addNode(NodeType.CONST, 0, 0); // 载体类型不限，普查只认图树 / carrier type irrelevant
        encap.subGraph = new NodeGraph();
        encap.subGraph.addNode(NodeType.AMP, 0, 0);
        var census = NodeAdmission.census(main);
        assertTrue(census.contains(NodeType.CONST));
        assertTrue(census.contains(NodeType.AMP), "sub-graph types count as present at load");
    }

    @Test
    @DisplayName("K10：目标图满额拒绝、差一个放行；子图各自计数 / K10 cap per target graph")
    void countCapPerTargetGraph() {
        var full = new NodeGraph();
        for (int i = 0; i < NodeAdmission.MAX_NODES; i++) full.addNode(NodeType.CONST, 0, 0);
        var op = new GraphOp(OpType.ADD_NODE, POS, -1, 0, NodeType.CONST, 0f, 0f, 0L, ACTOR);
        String v = NodeAdmission.violation(op, BlockNodeAllowances.MONITOR, java.util.Set.of(), full);
        assertNotNull(v, "a full target graph refuses further adds");
        assertTrue(v.contains("nodeCount=" + NodeAdmission.MAX_NODES), "violation names the count: " + v);

        var almost = new NodeGraph();
        for (int i = 0; i < NodeAdmission.MAX_NODES - 1; i++) almost.addNode(NodeType.CONST, 0, 0);
        assertNull(NodeAdmission.violation(op, BlockNodeAllowances.MONITOR, java.util.Set.of(), almost));

        // 子图 op 的容量只看目标子图：主图满、子图空 → 放行（客户端口径：主图与每个子图各自计）。
        // Sub-graph capacity reads the target sub-graph only: a full main graph with an empty
        // sub-graph passes (client semantics: main and each sub-graph count separately).
        assertNull(NodeAdmission.violation(op, BlockNodeAllowances.MONITOR, java.util.Set.of(), new NodeGraph()));
    }

    @Test
    @DisplayName("不可解码类型（未知字符串 id → null）拒绝 / undecodable type refused")
    void nullTypeRejected() {
        // 线上 NodeType 按字符串 id 编码；改包客户端送未知 id，解码落空成 null —— 不得建点。
        // Wire NodeTypes are string ids; a modified client sending an unknown id decodes to
        // null — no node may be created.
        var op = new GraphOp(OpType.ADD_NODE, POS, -1, 0, null, 0f, 0f, 0L, ACTOR);
        String v = NodeAdmission.violation(op, BlockNodeAllowances.MONITOR, java.util.Set.of(), new NodeGraph());
        assertNotNull(v, "an undecodable add must be refused");
        assertEquals("nodeType=unknown", v);
    }

    @Test
    @DisplayName("非加点 op 过门直行 / non-add ops pass the gate untouched")
    void nonAddOpsPassThrough() {
        var move = GraphOp.moveNode(POS, -1, 3, 5f, 5f, ACTOR);
        assertNull(NodeAdmission.violation(move, null, java.util.Set.of(), null));
        var conn = GraphOp.addConn(POS, -1, 1, 0, 2, 0, ACTOR);
        assertNull(NodeAdmission.violation(conn, null, java.util.Set.of(), null));
    }

    @Test
    @DisplayName("K10 权威值锚定 1024 / the authoritative cap pins at 1024")
    void capValuePinned() {
        assertEquals(1024, NodeAdmission.MAX_NODES);
    }

    // ── 接线源锚（applyOp 需要 ServerLevel，headless 造不出来；调用位与次序用源码锚钉住）──
    // Wiring source anchors (applyOp needs a ServerLevel headless tests cannot construct;
    // the call sites and their ordering are pinned by source anchors).

    @Test
    @DisplayName("接线源锚：applyOp 门在 ADD_NODE_REQUEST 应用之前 / gate precedes node creation")
    void wiringGateInApplyOp() throws Exception {
        String src = Files.readString(Path.of(
            "src/main/java/io/github/y15173334444/create_schematic_compute/network/EditSessionRegistry.java"));
        assertTrue(src.contains("NodeAdmission.violation("), "applyOp must call the admission gate");
        assertTrue(src.contains("gbe.nodeAllowance()") && src.contains("gbe.loadTimeNodeTypes()"),
            "gate must read the host's allowlist and load census");
        int gate = src.indexOf("NodeAdmission.violation(");
        int request = src.indexOf("OpType.ADD_NODE_REQUEST) {");
        assertTrue(gate >= 0 && request > gate, "the gate must run before the ADD_NODE_REQUEST application");
    }

    @Test
    @DisplayName("接线源锚：普查记录在图替换点 / census recorded at the graph-replacement site")
    void wiringCensusInGraphHost() throws Exception {
        String src = Files.readString(Path.of(
            "src/main/java/io/github/y15173334444/create_schematic_compute/blocks/GraphHost.java"));
        int load = src.indexOf("graph = NodeGraph.load(");
        int census = src.indexOf("NodeAdmission.census(graph)");
        assertTrue(load >= 0 && census > load, "the census must be recorded right after the graph load");
    }

    @Test
    @DisplayName("接线源锚：REJECT 分发含 ADD_NODE 回滚 / applier rolls back rejected direct adds")
    void wiringRollbackInApplier() throws Exception {
        String src = Files.readString(Path.of(
            "src/main/java/io/github/y15173334444/create_schematic_compute/client/editor/GraphRemoteApplier.java"));
        assertTrue(src.contains("graph.removeNode(rid)"),
            "a rejected direct ADD_NODE must remove the optimistic node");
        assertTrue(src.contains("OpType.ADD_NODE\n") || src.contains("OpType.ADD_NODE\r\n")
                || src.contains("OpType.ADD_NODE "),
            "the rollback branch must key on the ADD_NODE refused type");
    }

    @Test
    @DisplayName("接线源锚：编辑器上限委托权威值 / editor cap delegates to the authority")
    void wiringEditorDelegate() throws Exception {
        String src = Files.readString(Path.of(
            "src/main/java/io/github/y15173334444/create_schematic_compute/client/editor/GraphEditor.java"));
        assertTrue(src.contains("NodeAdmission.MAX_NODES"),
            "GraphEditor.MAX_NODES must delegate to NodeAdmission.MAX_NODES");
    }
}
