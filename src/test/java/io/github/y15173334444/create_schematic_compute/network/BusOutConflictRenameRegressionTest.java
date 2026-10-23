package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.graph.GraphNode;
import io.github.y15173334444.create_schematic_compute.graph.GraphOp;
import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import io.github.y15173334444.create_schematic_compute.graph.OpExecutor;
import io.github.y15173334444.create_schematic_compute.graph.OpType;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 玩家症状回归（2026-10-23）：有一个 A 号 BUS_OUT 节点，创建同名 B 号 BUS_OUT 报冲突后，
 * 把 B 号名称改走 —— A 号 BUS_OUT 的频段被一起清理了（BUS_IN 搜索不到）。
 * <p>
 * 回路各段走真实代码：{@link BusChannelHelper#registerGraphChannels}（首 tick 注册内核）、
 * {@link OpExecutor#apply} 的 SET_DISPLAY_TEXT（服务端改名应用）、applyOp 改名时的
 * {@link BusChannelHelper#applyBusOutRename}（迁移）、以及 {@link BusChannelHelper#reRegisterChannels}
 * 与 {@link BusChannelHelper#convergeBusInBands}（重编译周期 / 每 tick 收敛）。
 * <p>
 * User-symptom regression (2026-10-23): a BUS_OUT "A" exists; creating a same-named BUS_OUT "B"
 * reports a conflict; renaming B away then wipes A's bands (BUS_IN finds nothing). Every stage is
 * real code: the first-tick registration core, the server-side rename apply (OpExecutor
 * SET_DISPLAY_TEXT), applyBusOutRename (the migration), and the recompile-cycle / per-tick
 * convergence machinery.
 */
class BusOutConflictRenameRegressionTest {

    private static final BlockPos POS = new BlockPos(1, 2, 3);

    @BeforeEach @AfterEach void resetBus() { SignalBus.clear(); }

    /** 服务端改名应用的真实路径（EditSessionRegistry 应用 op 的同一条 OpExecutor 路）。
     *  The real server-side rename path (the same OpExecutor route applyOp uses). */
    private static void applyRename(NodeGraph graph, GraphNode node, String newName) {
        OpExecutor.apply(graph, new GraphOp(
            OpType.SET_DISPLAY_TEXT, POS, -1, node.id, 0, null, 0f, 0f,
            0, 0, 0, 0, 0, 0f, newName, 0, 0, 0, 0, null, 0, 0, 0,
            null, 0L, java.util.UUID.randomUUID(), 0, null));
    }

    /** 建一个带频段的 BUS_OUT 并走真实命名路径（SET_DISPLAY_TEXT + 迁移）。 */
    private static GraphNode nameBusOut(NodeGraph graph, BlockPos pos, GraphNode n, String from, String to) {
        applyRename(graph, n, to);
        BusChannelHelper.applyBusOutRename(graph, pos, n, from, null);
        return n;
    }

    /** 订阅侧收口断言：BUS_IN 必须搜得到 A 的频段（用户症状的落点）。 */
    private static void assertBusInFinds(String channel, List<String> expected) {
        var sub = new NodeGraph();
        var in = sub.addNode(NodeType.BUS_IN, 0, 0);
        in.signalName = channel;
        BusChannelHelper.convergeBusInBands(sub);
        assertEquals(expected, in.signalBands,
            "BUS_IN 搜索不到频道 '" + channel + "' 的频段（用户症状）");
    }

    @Test
    @DisplayName("同图冲突者改名走人：A 的频道/频段/值必须原样存活")
    void sameGraphConflictedRenameAwayKeepsOwnerIntact() {
        var graph = new NodeGraph();
        var a = graph.addNode(NodeType.BUS_OUT, 0, 0);
        a.signalName = "abc";
        a.signalBands = new ArrayList<>(List.of("b0", "b1"));
        BusChannelHelper.registerGraphChannels(graph, POS);
        SignalBus.put("abc\0b0", 1.5f);

        // 创建同名 B → 报冲突 / create same-named B → conflict reported
        var b = graph.addNode(NodeType.BUS_OUT, 1, 1);
        nameBusOut(graph, POS, b, "", "abc");
        assertTrue(b.busConflict, "夹具前提：B 同名必须报冲突");

        // 将 B 号名称改走 / rename B away
        nameBusOut(graph, POS, b, "abc", "zzz");

        var entry = SignalBus.getChannel("abc");
        assertNotNull(entry, "A 的频道不得被清");
        assertEquals(POS, entry.owner.pos());
        assertEquals(a.id, entry.owner.nodeId(), "A 的频道归属不得被夺");
        assertEquals(List.of("b0", "b1"), SignalBus.getBands("abc"),
            "A 的频段定义不得被清");
        assertEquals(1.5f, SignalBus.get("abc\0b0"), 0f, "A 的值不得被清");
        assertEquals(List.of("b0", "b1"), a.signalBands, "A 节点的频段不得被清");
        assertFalse(a.busConflict, "A 不得被误标冲突");
        assertBusInFinds("abc", List.of("b0", "b1"));
    }

    @Test
    @DisplayName("同图冲突者改名走人 + 重编译周期：快照差集释放不得误伤 A")
    void renameAwaySurvivesRecompileCycle() {
        var graph = new NodeGraph();
        var a = graph.addNode(NodeType.BUS_OUT, 0, 0);
        a.signalName = "abc";
        a.signalBands = new ArrayList<>(List.of("b0", "b1"));
        BusChannelHelper.registerGraphChannels(graph, POS);

        var b = graph.addNode(NodeType.BUS_OUT, 1, 1);
        nameBusOut(graph, POS, b, "", "abc");
        assertTrue(b.busConflict, "夹具前提：B 同名必须报冲突");

        // 改名落地 / the rename lands
        nameBusOut(graph, POS, b, "abc", "zzz");

        // GraphHost 重编译期接线的忠实仿真：lastBusOutKeys 快照差集（unregisterRemovedBusOutNodes
        // 的同款逻辑：消失的 B:name@id 键按 owner 释放）+ reRegisterChannels（同参）。
        // Faithful emulation of GraphHost's recompile wiring: the lastBusOutKeys snapshot diff
        // (unregisterRemovedBusOutNodes' exact rule: vanished B:name@id keys release by owner)
        // + reRegisterChannels (same arguments).
        var lastBusOutKeys = new java.util.HashSet<String>(List.of("B:abc@" + a.id, "B:abc@" + b.id));
        var currentKeys = new java.util.HashSet<String>();
        for (var n : graph.nodes)
            if (n.type == NodeType.BUS_OUT && !n.signalName.isEmpty())
                currentKeys.add("B:" + n.signalName + "@" + n.id);
        for (var key : lastBusOutKeys) {
            if (!currentKeys.contains(key)) {
                String rest = key.substring(2);
                int at = rest.lastIndexOf('@');
                String name = rest.substring(0, at);
                int nodeId = Integer.parseInt(rest.substring(at + 1));
                SignalBus.releaseChannel(name, new ChannelOwner(POS, nodeId));
            }
        }
        BusChannelHelper.reRegisterChannels(graph, graph, POS, null); // oldGraph 同对象别名（GraphHost 现状）

        var entry = SignalBus.getChannel("abc");
        assertNotNull(entry, "重编译周期不得清掉 A 的频道");
        assertEquals(a.id, entry.owner.nodeId());
        assertEquals(List.of("b0", "b1"), SignalBus.getBands("abc"), "重编译周期不得清掉 A 的频段定义");
        assertBusInFinds("abc", List.of("b0", "b1"));
    }

    @Test
    @DisplayName("每 tick 收敛不得在改名走人后清掉 A 的频段（含同图订阅者）")
    void tickConvergenceMustNotWipeOwnerBands() {
        var graph = new NodeGraph();
        var a = graph.addNode(NodeType.BUS_OUT, 0, 0);
        a.signalName = "abc";
        a.signalBands = new ArrayList<>(List.of("b0", "b1"));
        BusChannelHelper.registerGraphChannels(graph, POS);

        var b = graph.addNode(NodeType.BUS_OUT, 1, 1);
        nameBusOut(graph, POS, b, "", "abc");
        nameBusOut(graph, POS, b, "abc", "zzz");

        // 同图订阅者 + 跨图订阅者都跑一轮收敛（ensureBusRegistered 的每 tick 不变量）
        var sameGraphIn = graph.addNode(NodeType.BUS_IN, 2, 2);
        sameGraphIn.signalName = "abc";
        BusChannelHelper.convergeBusInBands(graph);
        BusChannelHelper.convergeBusInBands(graph);

        assertEquals(List.of("b0", "b1"), a.signalBands, "A 节点的频段不得被收敛清掉");
        assertEquals(List.of("b0", "b1"), sameGraphIn.signalBands, "同图 BUS_IN 搜索不到（用户症状）");
        assertBusInFinds("abc", List.of("b0", "b1"));
    }

    @Test
    @DisplayName("所有权倒置：B 先占名、A 后撞名，B 改名走人后 A 必须能接管且 BUS_IN 恢复")
    void ownerInversionRenameAwayLetsOwnerReclaim() {
        // B 先命名 'abc'（B 持有频道），A 后命名 'abc'（A 报冲突）/ B names first (holds the
        // channel), A collides second (A reports the conflict).
        var graphB = new NodeGraph();
        var b = graphB.addNode(NodeType.BUS_OUT, 0, 0);
        nameBusOut(graphB, new BlockPos(9, 9, 9), b, "", "abc");

        var graphA = new NodeGraph();
        var a = graphA.addNode(NodeType.BUS_OUT, 0, 0);
        a.signalBands = new ArrayList<>(List.of("b0", "b1"));
        nameBusOut(graphA, POS, a, "", "abc");
        assertTrue(a.busConflict, "夹具前提：A 后撞名必须报冲突");

        // B 改名走人 / B renames away
        nameBusOut(graphB, new BlockPos(9, 9, 9), b, "abc", "zzz");

        // A 的宿主下一轮自愈（recoverConflictedChannels 的 entry==null 接管支）：
        // 无 ServerLevel 起不来，这里按同款 owner 纪律重跑注册内核。
        // A's host self-heals next tick (recoverConflictedChannels' entry==null takeover branch):
        // no ServerLevel boots headless, so re-run the registration core under the same discipline.
        var reg = BusChannelHelper.registerGraphChannels(graphA, POS);
        assertFalse(a.busConflict, "B 走人后 A 必须解除冲突");
        assertNotNull(SignalBus.getChannel("abc"), "A 接管后频道必须在");
        assertEquals(List.of("b0", "b1"), SignalBus.getBands("abc"), "A 的频段定义必须随接管恢复");

        // 收敛一轮后订阅者必须搜得到 / after one convergence pass the subscriber must find it
        assertBusInFinds("abc", List.of("b0", "b1"));
    }

    @Test
    @DisplayName("同图所有权倒置：B 先占名、A 后撞名，B 改名走人后 A 的频段定义必须随重注册恢复")
    void sameGraphOwnerInversionRenameAway() {
        var graph = new NodeGraph();
        // B 先命名（持有频道）/ B names first (holds the channel)
        var b = graph.addNode(NodeType.BUS_OUT, 1, 1);
        nameBusOut(graph, POS, b, "", "abc");
        // A 后命名（撞名报冲突）/ A collides second (conflict reported)
        var a = graph.addNode(NodeType.BUS_OUT, 0, 0);
        a.signalBands = new ArrayList<>(List.of("b0", "b1"));
        nameBusOut(graph, POS, a, "", "abc");
        assertTrue(a.busConflict, "夹具前提：A 后撞名必须报冲突");

        // B 改名走人 / B renames away
        nameBusOut(graph, POS, b, "abc", "zzz");

        // applyBusOutRename 会重跑整图注册：A 必须当场接管并带回定义
        // applyBusOutRename re-runs graph registration: A must reclaim and carry the definition back
        assertFalse(a.busConflict, "B 走人后 A 必须解除冲突");
        assertNotNull(SignalBus.getChannel("abc"), "A 的频道必须在");
        assertEquals(List.of("b0", "b1"), SignalBus.getBands("abc"),
            "A 的频段定义必须随重注册恢复（否则 BUS_IN 搜索不到）");
        assertBusInFinds("abc", List.of("b0", "b1"));
    }

    @Test
    @DisplayName("跨图倒置 + A 服务端无频段：B 改名走人不得留下永久死频道")
    void crossGraphInversionRenameAwayWithBarePublisher() {
        // A：只有名字、没有频段的发布方（如恢复/同步来的副本）/ A: a bare publisher (name only)
        var graphA = new NodeGraph();
        var a = graphA.addNode(NodeType.BUS_OUT, 0, 0);
        nameBusOut(graphA, POS, a, "", "abc"); // A 先注册，持有频道 / A registers first

        var graphB = new NodeGraph();
        var b = graphB.addNode(NodeType.BUS_OUT, 0, 0);
        nameBusOut(graphB, new BlockPos(9, 9, 9), b, "", "abc");
        assertTrue(b.busConflict, "夹具前提：B 撞名必须报冲突");

        // B 改名走人 / B renames away
        nameBusOut(graphB, new BlockPos(9, 9, 9), b, "abc", "zzz");

        var entry = SignalBus.getChannel("abc");
        assertNotNull(entry, "A 持有的频道不得随冲突者改名被清");
        assertEquals(a.id, entry.owner.nodeId());
        assertBusInFinds("abc", List.of());
    }

    @Test
    @DisplayName("删除冲突节点不得清掉跨图 owner 的频段定义（删除路径的统一释放口径）")
    void deletingConflictedNodeMustNotNukeCrossGraphOwner() {
        var graphA = new NodeGraph();
        var a = graphA.addNode(NodeType.BUS_OUT, 0, 0);
        a.signalName = "abc";
        a.signalBands = new ArrayList<>(List.of("b0", "b1"));
        BusChannelHelper.registerGraphChannels(graphA, POS);
        SignalBus.put("abc\0b0", 2.5f);

        // 对端图里的冲突者 B / the conflicted B in a peer graph
        var graphB = new NodeGraph();
        var b = graphB.addNode(NodeType.BUS_OUT, 0, 0);
        b.signalName = "abc";
        BusChannelHelper.registerGraphChannels(graphB, new BlockPos(9, 9, 9));
        assertTrue(b.busConflict, "夹具前提：B 撞名必须报冲突");

        // 删除 = 确证离开：统一释放（GraphEditor.deleteSelectedNodes 修复后走这条；
        // 旧实现的 SignalBus.clearBus 不带 owner 门，会连带清掉跨图 owner 的定义与值）。
        // Delete = provable departure: the unified release (what deleteSelectedNodes routes
        // through after the fix; its old owner-less clearBus nuked a cross-graph owner's
        // definition and values along with it).
        SignalBus.releaseChannel(b.signalName, new ChannelOwner(new BlockPos(9, 9, 9), b.id));

        assertEquals(List.of("b0", "b1"), SignalBus.getBands("abc"),
            "删除冲突节点不得清掉跨图 owner 的频段定义");
        assertEquals(2.5f, SignalBus.get("abc\0b0"), 0f,
            "删除冲突节点不得清掉跨图 owner 的值");
    }

    @Test
    @DisplayName("倒置死名窗口：活候选仍在时改名走人不得让收敛剪掉 BUS_IN（用户症状全链）")
    void deadNameWindowMustNotPruneLiveClaimantsBusIns() {
        // B 先占 'abc'（发布方倒置：B 先注册成功），A 后撞名（报冲突，带频段）。
        // B owns 'abc' first (registration-order inversion); A collides second
        // (the conflict is reported) and carries bands.
        var graphB = new NodeGraph();
        var b = graphB.addNode(NodeType.BUS_OUT, 0, 0);
        b.signalBands = new ArrayList<>(List.of("x"));
        nameBusOut(graphB, new BlockPos(9, 9, 9), b, "", "abc");

        var graphA = new NodeGraph();
        var a = graphA.addNode(NodeType.BUS_OUT, 0, 0);
        a.signalBands = new ArrayList<>(List.of("b0", "b1"));
        nameBusOut(graphA, POS, a, "", "abc");
        assertTrue(a.busConflict, "夹具前提：A 撞名必须报冲突");

        // 订阅图：BUS_IN 'abc' 持有当前频段图 / subscriber graph: a BUS_IN on 'abc'
        var sub = new NodeGraph();
        var in = sub.addNode(NodeType.BUS_IN, 0, 0);
        in.signalName = "abc";
        in.signalBands = new ArrayList<>(List.of("x"));

        // B 改名走人 —— 用户手势：「报冲突后将 B 号名称改走」
        // B renames away — the user gesture: rename B away after the conflict report
        nameBusOut(graphB, new BlockPos(9, 9, 9), b, "abc", "zzz");

        // 死名窗口：A 的宿主还没接管（跨宿主 tick 顺序任意），订阅侧先跑一轮收敛。
        // Dead-name window: A's host has not reclaimed yet (cross-host tick order is
        // arbitrary) and the subscriber converges first.
        BusChannelHelper.convergeBusInBands(sub);

        assertEquals(List.of("x"), in.signalBands,
            "A 还活着（注册被拒的活候选）时，死名收敛不得清掉 BUS_IN 的频段图（用户症状）");

        // A 接管后定义随注册内核回来，收敛到 A 的频段 / after A's takeover the list converges to A's bands
        BusChannelHelper.registerGraphChannels(graphA, POS);
        BusChannelHelper.convergeBusInBands(sub);
        assertEquals(List.of("b0", "b1"), in.signalBands, "接管后 BUS_IN 应收敛到 A 的频段");
    }
}
