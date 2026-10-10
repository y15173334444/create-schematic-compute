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
 * 用户症状回归：BUS_OUT 改名后**有时候**丢频道导致 BUS_IN 查找不到，但重新拉取权威图时
 * BUS_OUT 的名称仍在 —— 名称在节点上（SET_DISPLAY_TEXT 正常落地），丢的是**频道注册**：
 * 注册只在宿主首 tick 跑一次（busRegistrationPending），改名后 CHANNELS 条目仍挂旧名、
 * 新名无条目，BUS_IN 读 channelMap(新名) 为 null；BAND_REGISTRY 新名也没有频段定义。
 * <p>
 * User-symptom regression: after renaming a BUS_OUT the channel is <b>sometimes</b> lost and
 * BUS_IN cannot find it, yet re-pulling the authoritative graph shows the name intact — the
 * name lives on the node (SET_DISPLAY_TEXT lands fine); what is lost is the <b>channel
 * registration</b>. Registration runs once per host lifetime (busRegistrationPending); after a
 * rename the CHANNELS entry still hangs on the old name and the new name has none, so BUS_IN
 * reads channelMap(new) = null, and BAND_REGISTRY has no definition under the new name either.
 * <p>
 * 回路各段走真实代码：{@link BusChannelHelper#registerGraphChannels}（首 tick 注册内核）、
 * {@link OpExecutor#apply} 的 SET_DISPLAY_TEXT（服务端改名应用）、以及 applyOp 改名时调用的
 * {@link BusChannelHelper#applyBusOutRename}（修复点）。
 * Every stage is real code: the first-tick registration core, the server-side rename apply
 * (OpExecutor SET_DISPLAY_TEXT), and applyBusOutRename — the handler EditSessionRegistry.applyOp
 * invokes on a rename (the fix point).
 */
class BusOutRenameChannelTest {

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

    @Test
    @DisplayName("BUS_OUT 改名后新频道必须可查（BUS_IN 查找不到的回归）")
    void renamedChannelIsFindable() {
        var graph = new NodeGraph();
        var out = graph.addNode(NodeType.BUS_OUT, 0, 0);
        out.signalName = "abc";
        out.signalBands = new ArrayList<>(List.of("x", "y"));
        BusChannelHelper.registerGraphChannels(graph, POS); // 生产同款首 tick 注册
        var liveMap = out.busInternalMap;
        assertNotNull(SignalBus.getChannel("abc"), "夹具前提：旧名已注册");

        applyRename(graph, out, "def");                                    // 用户改名（服务端应用）
        var reg = BusChannelHelper.applyBusOutRename(graph, POS, out, "abc", null); // 服务端改名处理（修复点）

        var entry = SignalBus.getChannel("def");
        assertNotNull(entry, "改名后新名查不到（BUS_IN 查找不到的根因）");
        assertSame(liveMap, entry.internalMap, "新名条目必须持有 BUS_OUT 的实时映射");
        assertNull(SignalBus.getChannel("abc"), "旧名条目残留会占名（幽灵占用）");
        assertEquals(List.of("x", "y"), SignalBus.getBands("def"), "频段定义须随名字迁移");
        assertEquals(List.of(), SignalBus.getBands("abc"),
            "旧名频段定义应为「已定义为空」（死名标记，供旧名 BUS_IN 清旧图），而非残留或缺席");
        assertFalse(reg.anyConflictChanged(), "无冲突时不应触发全量同步");
    }

    @Test
    @DisplayName("改名撞上他人已注册频道：标冲突、不抢频道")
    void renameOntoForeignChannelFlagsConflict() {
        var foreignMap = new HashMap<String, Float>();
        assertTrue(SignalBus.registerChannel("def", foreignMap,
            new ChannelOwner(new BlockPos(9, 9, 9), 42)));
        var graph = new NodeGraph();
        var out = graph.addNode(NodeType.BUS_OUT, 0, 0);
        out.signalName = "abc";
        out.signalBands = new ArrayList<>(List.of("x"));
        BusChannelHelper.registerGraphChannels(graph, POS);

        applyRename(graph, out, "def");
        BusChannelHelper.applyBusOutRename(graph, POS, out, "abc", null);

        assertTrue(out.busConflict, "撞名必须标冲突旗标");
        assertSame(foreignMap, SignalBus.getChannel("def").internalMap, "不得抢走他人的频道");
        assertNull(SignalBus.getChannel("abc"), "旧名条目仍应清理");
    }

    @Test
    @DisplayName("重复迁移幂等（服务端与客户端各跑一次的现实）")
    void migrationIsIdempotent() {
        var graph = new NodeGraph();
        var out = graph.addNode(NodeType.BUS_OUT, 0, 0);
        out.signalName = "abc";
        out.signalBands = new ArrayList<>(List.of("x"));
        BusChannelHelper.registerGraphChannels(graph, POS);

        applyRename(graph, out, "def");
        BusChannelHelper.applyBusOutRename(graph, POS, out, "abc", null);
        BusChannelHelper.applyBusOutRename(graph, POS, out, "abc", null); // 第二次（如单机共享表）

        var entry = SignalBus.getChannel("def");
        assertNotNull(entry);
        assertSame(out.busInternalMap, entry.internalMap);
        assertEquals(List.of("x"), SignalBus.getBands("def"));
        assertFalse(out.busConflict);
    }

    // ── 玩家复现（2026-10-11）：改名后 BUS_IN 的图不刷新 + 新建改回旧名报冲突 ──
    //   Player repro: BUS_IN's list never refreshes after the rename, and a fresh BUS_OUT
    //   renamed back to the old name conflicts.

    /** 建一个带频段的 BUS_OUT 并走真实命名路径（SET_DISPLAY_TEXT + 迁移）。 */
    private static GraphNode nameBusOut(NodeGraph graph, BlockPos pos, GraphNode n, String from, String to) {
        applyRename(graph, n, to);
        BusChannelHelper.applyBusOutRename(graph, pos, n, from, null);
        return n;
    }

    @Test
    @DisplayName("Bug A-旧名：发布方改名后，旧名 BUS_IN 必须拿到权威刷新（清空死名），不得留旧图")
    void deadNameBusInRefreshesAfterRename() {
        var pubGraph = new NodeGraph();
        var pub = pubGraph.addNode(NodeType.BUS_OUT, 0, 0);
        pub.signalName = "abc";
        pub.signalBands = new ArrayList<>(List.of("b0", "b1"));
        BusChannelHelper.registerGraphChannels(pubGraph, POS);

        var subGraph = new NodeGraph();
        var sub = subGraph.addNode(NodeType.BUS_IN, 0, 0);
        sub.signalName = "abc";
        sub.signalBands = new ArrayList<>(List.of("x", "y", "z")); // 旧图 / stale list

        applyRename(pubGraph, pub, "def");
        BusChannelHelper.applyBusOutRename(pubGraph, POS, pub, "abc", null);
        // 服务端每 tick 收敛（BUS_IN 的图应跟着频道定义走）
        var converged = BusChannelHelper.convergeBusInBands(subGraph);
        assertNotNull(converged);
        assertEquals(List.of(), sub.signalBands,
            "发布方改名走后，旧名 BUS_IN 的图必须清空（issue #11 的死名清理），不得留旧图");
    }

    @Test
    @DisplayName("Bug A-新名：发布方改名后，新名 BUS_IN 必须收敛到发布方频段")
    void newNameBusInConvergesToPublisher() {
        var pubGraph = new NodeGraph();
        var pub = pubGraph.addNode(NodeType.BUS_OUT, 0, 0);
        pub.signalName = "abc";
        pub.signalBands = new ArrayList<>(List.of("b0", "b1"));
        BusChannelHelper.registerGraphChannels(pubGraph, POS);

        var subGraph = new NodeGraph();
        var sub = subGraph.addNode(NodeType.BUS_IN, 0, 0);
        sub.signalName = "def";
        sub.signalBands = new ArrayList<>(List.of("x"));

        applyRename(pubGraph, pub, "def");
        BusChannelHelper.applyBusOutRename(pubGraph, POS, pub, "abc", null);
        BusChannelHelper.convergeBusInBands(subGraph);
        assertEquals(List.of("b0", "b1"), sub.signalBands,
            "改名落地后新名 BUS_IN 必须收敛到发布方的频段（不得留旧图）");
    }

    @Test
    @DisplayName("Bug B：改名释放旧名后，新建 BUS_OUT 改回旧名不得报冲突")
    void freedOldNameReclaimable() {
        var graphA = new NodeGraph();
        var a = graphA.addNode(NodeType.BUS_OUT, 0, 0);
        a.signalName = "abc";
        a.signalBands = new ArrayList<>(List.of("b0"));
        BusChannelHelper.registerGraphChannels(graphA, POS);

        applyRename(graphA, a, "def");
        BusChannelHelper.applyBusOutRename(graphA, POS, a, "abc", null);

        // 新建 BUS_OUT 改回旧名 / a fresh BUS_OUT renamed back to the old name
        var graphB = new NodeGraph();
        var b = graphB.addNode(NodeType.BUS_OUT, 0, 0);
        var posB = new BlockPos(9, 9, 9);
        applyRename(graphB, b, "abc");
        BusChannelHelper.applyBusOutRename(graphB, posB, b, "", null);

        assertFalse(b.busConflict, "旧名已随改名释放，新发布方不得被误标冲突");
        var entry = SignalBus.getChannel("abc");
        assertNotNull(entry, "新发布方应持有旧名频道");
        assertTrue(entry.owner.pos().equals(posB) && entry.owner.nodeId() == b.id,
            "旧名频道应归新发布方");
        assertNotNull(SignalBus.getChannel("def"), "原发布方改名后应持有新名频道");
        assertEquals(List.of("b0"), SignalBus.getBands("def"), "频段定义随名字走");
    }

    @Test
    @DisplayName("跨 owner 保护：冲突节点改名不得清掉真正 owner 的频段定义与值")
    void conflictedRenameMustNotNukeOwnersDefinition() {
        // owner1 先注册 'abc' / owner1 legitimately holds 'abc'
        var graphX = new NodeGraph();
        var owner1 = graphX.addNode(NodeType.BUS_OUT, 0, 0);
        owner1.signalName = "abc";
        owner1.signalBands = new ArrayList<>(List.of("b0", "b1"));
        owner1.busInternalMap = new HashMap<>();
        BusChannelHelper.registerGraphChannels(graphX, new BlockPos(1, 1, 1));
        SignalBus.put("abc\0b0", 3.5f);

        // 冲突者：同名注册被拒后改名走人 / the conflicted node renames away
        var graphY = new NodeGraph();
        var c = graphY.addNode(NodeType.BUS_OUT, 0, 0);
        c.signalName = "abc";
        BusChannelHelper.registerGraphChannels(graphY, POS);
        assertTrue(c.busConflict, "夹具前提：c 与 owner1 撞名");
        applyRename(graphY, c, "zzz");
        BusChannelHelper.applyBusOutRename(graphY, POS, c, "abc", null);

        assertEquals(List.of("b0", "b1"), SignalBus.getBands("abc"),
            "冲突节点改名不得清掉真正 owner 的频段定义");
        assertNotNull(SignalBus.getChannel("abc"), "真正 owner 的频道不得被动");
        assertEquals(3.5f, SignalBus.get("abc\0b0"), 0f,
            "冲突节点改名不得清掉真正 owner 的值");
    }
}
