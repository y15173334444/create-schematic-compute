package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.graph.GraphNode;
import io.github.y15173334444.create_schematic_compute.graph.GraphOp;
import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import io.github.y15173334444.create_schematic_compute.graph.OpType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 封装子图展开状态的跨玩家同步回归测试：
 * 玩家 B 停在**主图**作用域时，玩家 A 在封装子图内展开/折叠节点 X —— 服务端会广播
 * EXPAND_NODE/COLLAPSE_NODE（ownerNodeId=E），B 的本端应用器必须把 **图数据字段
 * {@code node.expanded}** 更新到自己的子图副本上；B 随后双击进入子图时，renderBg 的
 * init 恢复块**只读这个字段**。该字段被作用域守卫门掉，B 进子图就恢复出过期状态
 * （「其他玩家进入封装子图时无法正确获取节点展开状态」）。
 * Regression: expansion state must reach another player's sub-graph copy even while that
 * player is outside the sub-graph — the init-restore on entry reads only {@code n.expanded}.
 */
class EncapSubGraphExpandSyncTest {

    /** 最小 Host 桩：只带一张主图。 / Minimal Host stub carrying one main graph. */
    private static final class StubHost implements GraphEditor.Host {
        final NodeGraph graph = new NodeGraph();
        @Override public NodeGraph getGraph() { return graph; }
        @Override public void saveGraph() {}
        @Override public void toggleRunning(boolean start) {}
        @Override public boolean isRunning() { return false; }
        @Override public net.minecraft.client.gui.screens.Screen asScreen() { return null; }
    }

    /** 主图 + 封装节点 E + 子图内节点 X。X 用 T_FLIPFLOP：shouldOpenPanel 通过
     *  （paramNames 非空），且 create() 不建任何控件（editableParamCount=0）——无头安全。 */
    private record Fixture(GraphEditor ed, GraphNode encap, GraphNode x) {}

    private static Fixture fixture() {
        var host = new StubHost();
        var ed = new GraphEditor(host, null); // 无头构造：渲染期才碰 Minecraft / headless-safe
        var encap = host.graph.addNode(NodeType.ENCAPSULATION, 0, 0);
        var sub = new NodeGraph();
        var x = sub.addNode(NodeType.T_FLIPFLOP, 5, 5);
        encap.subGraph = sub;
        return new Fixture(ed, encap, x);
    }

    /** 模拟服务端广播的展开/折叠 op（ownerNodeId 路由到封装子图）。
     *  用规范 28 参构造器 + null itemStack：ItemStack.EMPTY 的 <clinit> 需要注册表引导，
     *  纯 JUnit 跑不动（见 GraphNodeImageSizeTest 的注记）；展开/折叠路径不读该字段。
     *  Canonical 28-arg ctor with null itemStack: ItemStack.EMPTY's <clinit> needs registry
     *  bootstrap unavailable in plain JUnit (see GraphNodeImageSizeTest); the expand/collapse
     *  path never reads the field. */
    private static GraphOp expansionOp(OpType type, int ownerNodeId, int targetNodeId) {
        return new GraphOp(type, net.minecraft.core.BlockPos.ZERO, ownerNodeId, targetNodeId,
            0, null, 0f, 0f, 0, 0, 0, 0, 0, 0f, null, 0, 0, 0, 0, null, 0, 0, 0,
            (net.minecraft.world.item.ItemStack) null, 0L, java.util.UUID.randomUUID(), 0, null);
    }

    @Test
    @DisplayName("cross-scope remote EXPAND updates the sub-graph copy's data flag / 跨作用域远端展开必须更新子图副本的数据字段")
    void crossScopeRemoteExpandMarksGraphData() {
        Fixture f = fixture();
        assertFalse(f.x().expanded, "precondition: collapsed in B's copy");

        // B 在主图作用域收到 A 在子图内的展开 op
        f.ed().onRemoteOp(expansionOp(OpType.EXPAND_NODE, f.encap().id, f.x().id));

        assertTrue(f.x().expanded,
            "n.expanded is graph data, not scope UI — the remote applier must apply it "
            + "even cross-scope, or entering the sub-graph restores a stale state");
    }

    @Test
    @DisplayName("cross-scope remote COLLAPSE clears the sub-graph copy's data flag / 跨作用域远端折叠必须清掉子图副本的数据字段")
    void crossScopeRemoteCollapseClearsGraphData() {
        Fixture f = fixture();
        f.x().expanded = true; // B 的副本里已展开（随后 A 折叠它）

        f.ed().onRemoteOp(expansionOp(OpType.COLLAPSE_NODE, f.encap().id, f.x().id));

        assertFalse(f.x().expanded,
            "a cross-scope collapse must clear the data flag, or entering the sub-graph "
            + "shows a node another player already collapsed");
    }

    @Test
    @DisplayName("cross-scope expansion must not touch this scope's UI state / 跨作用域展开不得触碰本作用域 UI 状态")
    void crossScopeExpandLeavesUiStateAlone() {
        Fixture f = fixture();

        f.ed().onRemoteOp(expansionOp(OpType.EXPAND_NODE, f.encap().id, f.x().id));

        // 数据字段走通了，但编辑器的展开集合/编辑状态是**当前作用域**的 UI：
        // B 还没进子图，本作用域（主图）不该凭空出现子图节点的展开项。
        assertFalse(f.ed().expandedNodeIds.contains(f.x().id),
            "expandedNodeIds is the on-screen scope's UI state — cross-scope ops must not mutate it");
        assertFalse(f.ed().nodeEditStatesById.containsKey(f.x().id),
            "no EditState should be built for a node outside the current scope");
    }

    @Test
    @DisplayName("entering the sub-graph restores the remotely-expanded node / 进入子图恢复远端展开的节点")
    void enteringSubGraphRestoresRemoteExpandedNodes() {
        Fixture f = fixture();
        // 用户主诉的完整链路：A 在子图内展开 X → B（停在主图）收到广播 → B 双击进入子图
        // → renderBg 的 init 恢复块读 n.expanded → X 应以展开态出现。
        // Full user-reported chain: A expands X inside the sub-graph → B (on the main graph)
        // receives the broadcast → B enters the sub-graph → the init-restore reads n.expanded.
        f.ed().onRemoteOp(expansionOp(OpType.EXPAND_NODE, f.encap().id, f.x().id));
        f.ed().enterSubGraph(f.encap());
        f.ed().restoreOrRebuildEditStates(); // renderBg 首帧恢复块（抽出的测试缝隙）

        assertTrue(f.ed().expandedNodeIds.contains(f.x().id),
            "the remotely-expanded node must appear expanded after entering the sub-graph");
        assertNotNull(f.ed().nodeEditStatesById.get(f.x().id),
            "an edit state must exist for the restored expanded node");
    }

    @Test
    @DisplayName("entering the sub-graph respects a remote collapse / 进入子图尊重远端折叠")
    void enteringSubGraphRespectsRemoteCollapse() {
        Fixture f = fixture();
        f.x().expanded = true; // B 的副本里已展开；随后 A 在子图内折叠它
        f.ed().onRemoteOp(expansionOp(OpType.COLLAPSE_NODE, f.encap().id, f.x().id));
        f.ed().enterSubGraph(f.encap());
        f.ed().restoreOrRebuildEditStates();

        assertFalse(f.ed().expandedNodeIds.contains(f.x().id),
            "a remotely-collapsed node must not appear expanded after entering the sub-graph");
    }

    // ══════════ 删除守卫：封装占用判定 / delete guard: encapsulation occupancy ══════════

    /** 造一条远端临场记录（纯 record，无头可构造）。 / A remote presence entry (plain record). */
    private static io.github.y15173334444.create_schematic_compute.network.GraphPresencePacket presence(
        int ownerNodeId, byte mode) {
        return new io.github.y15173334444.create_schematic_compute.network.GraphPresencePacket(
            net.minecraft.core.BlockPos.ZERO, java.util.UUID.randomUUID(), "Dev2",
            ownerNodeId, 0f, 0f, -1, -1, -1, 0, 0f, 0f, null, mode, -1, false);
    }

    @Test
    @DisplayName("encapOccupied: node-editor presence inside the encap counts / 子图内有节点编辑临场即占用")
    void encapOccupiedByNodeEditorPresence() {
        var map = new java.util.HashMap<java.util.UUID, io.github.y15173334444.create_schematic_compute.network.GraphPresencePacket>();
        map.put(java.util.UUID.randomUUID(), presence(7, (byte) 0));
        assertTrue(GraphPresenceTracker.encapOccupied(map, 7),
            "a player rooted inside encap 7 must block its deletion");
        assertFalse(GraphPresenceTracker.encapOccupied(map, 8),
            "a different encap is not occupied");
    }

    @Test
    @DisplayName("encapOccupied: mode/non-owner/empty/invalid all clear / 显示模式、异作用域、空表、非法 id 均不占用")
    void encapOccupiedNegativeCases() {
        var map = new java.util.HashMap<java.util.UUID, io.github.y15173334444.create_schematic_compute.network.GraphPresencePacket>();
        assertFalse(GraphPresenceTracker.encapOccupied(map, 7), "empty table → not occupied");
        // 显示布局模式（mode 1）的 ownerNodeId 语义不同，不得当占用判定
        // Display-layout mode (1) gives ownerNodeId a different meaning — never counts.
        map.put(java.util.UUID.randomUUID(), presence(7, (byte) 1));
        assertFalse(GraphPresenceTracker.encapOccupied(map, 7), "display-mode presence is not an occupant");
        // 非法 id（主图 -1 / 0）直接不判定
        assertFalse(GraphPresenceTracker.encapOccupied(map, -1), "main-graph sentinel is never occupied");
        assertFalse(GraphPresenceTracker.encapOccupied(map, 0), "id 0 is never occupied");
    }
}
