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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 玩家复现（2026-10-11）：BUS_OUT 冲突时编辑频段 → 再改节点名 → 编辑区频段**回退**。
 * <p>机制：频段是节点的<b>引脚结构</b>，但旧上传把「结构」与「频道定义」混在一个包里，
 * 冲突者整体拒收（防夺取）——服务端副本永久变旧；改名夺回定义权的瞬间，旧频段随定义
 * 广播灌回客户端，编辑区回退。</p>
 * <p>两层语义：<b>结构归节点</b>（冲突者也照常落盘）、<b>定义归 owner</b>（BAND_REGISTRY、
 * 同名对齐、广播仍限未冲突的 BUS_OUT）。</p>
 * Player repro: edit bands on a conflicted BUS_OUT, then rename it — the edit-panel bands
 * revert. Bands are the node's pin structure, but the old upload mixed structure and channel
 * definition in one packet and rejected conflicted nodes wholesale (anti-hijack) — the server
 * copy went permanently stale; the moment a rename granted definition rights, the stale bands
 * broadcast back down. Two tiers now: structure to the node (always written), definition to the
 * owner (registry / same-name alignment / broadcast stay owner-only).
 */
class BandUploadOwnershipTest {

    private static final BlockPos POS = new BlockPos(4, 5, 6);

    @BeforeEach @AfterEach void resetBus() { SignalBus.clear(); }

    @Test
    @DisplayName("冲突节点上传：结构照常落盘，定义/同名对齐/他人数据不动")
    void conflictedUploadWritesStructureOnly() {
        // owner 持 'abc' / the owner holds 'abc'
        var ownerGraph = new NodeGraph();
        var owner = ownerGraph.addNode(NodeType.BUS_OUT, 0, 0);
        owner.signalName = "abc";
        owner.signalBands = new ArrayList<>(List.of("b0", "b1"));
        BusChannelHelper.registerGraphChannels(ownerGraph, new BlockPos(1, 1, 1));

        // 冲突者同名 / the conflicted same-name node
        var graph = new NodeGraph();
        var c = graph.addNode(NodeType.BUS_OUT, 0, 0);
        c.signalName = "abc";
        BusChannelHelper.registerGraphChannels(graph, POS);
        assertTrue(c.busConflict, "夹具前提：c 撞名");

        // 用户在冲突态编辑自己节点的频段 / the user edits its bands while conflicted
        boolean ok = BusChannelHelper.applyBandUpload(graph, POS, c.id, "abc",
            new ArrayList<>(java.util.List.of("x", "y")), null);
        assertTrue(ok, "上传应被受理（目标节点存在）");
        assertEquals(List.of("x", "y"), c.signalBands,
            "冲突节点的引脚结构必须落盘（结构归节点）");
        assertEquals(List.of("b0", "b1"), SignalBus.getBands("abc"),
            "冲突者不得改写频道定义（防夺取）");
        assertEquals(List.of("b0", "b1"), owner.signalBands, "不得对齐/覆盖 owner 节点");
    }

    @Test
    @DisplayName("玩家全链路：冲突态编辑频段 → 改名夺回定义权 → 编辑区频段不得回退")
    void renameAfterConflictBandEditDoesNotRevert() {
        // owner 持 'abc'；c 同名冲突 / owner holds 'abc'; c conflicts
        var ownerGraph = new NodeGraph();
        var owner = ownerGraph.addNode(NodeType.BUS_OUT, 0, 0);
        owner.signalName = "abc";
        owner.signalBands = new ArrayList<>(List.of("b0", "b1"));
        BusChannelHelper.registerGraphChannels(ownerGraph, new BlockPos(1, 1, 1));

        var serverGraph = new NodeGraph();
        var c = serverGraph.addNode(NodeType.BUS_OUT, 0, 0);
        c.signalName = "abc";
        c.signalBands = new ArrayList<>(List.of("b0", "b1")); // 服务端旧副本 / the stale server copy
        BusChannelHelper.registerGraphChannels(serverGraph, POS);
        assertTrue(c.busConflict);

        // 用户编辑频段（冲突态）/ the user edits bands while conflicted
        BusChannelHelper.applyBandUpload(serverGraph, POS, c.id, "abc",
            new ArrayList<>(java.util.List.of("x", "y")), null);

        // 改名夺回定义权 / the rename grants definition rights
        OpExecutor.apply(serverGraph, new GraphOp(OpType.SET_DISPLAY_TEXT, POS, -1, c.id, 0,
            null, 0f, 0f, 0, 0, 0, 0, 0, 0f, "def", 0, 0, 0, 0, null, 0, 0, 0,
            null, 0L, java.util.UUID.randomUUID(), 0, null));
        BusChannelHelper.applyBusOutRename(serverGraph, POS, c, "abc", null);
        assertFalse(c.busConflict, "改名到自由名后不再冲突");

        // 定义侧必须携带用户的编辑 / the definition must carry the user's edits
        assertEquals(java.util.List.of("x", "y"), SignalBus.getBands("def"),
            "夺权注册的定义必须是用户的频段");

        // 客户端从**旧状态**出发，只信同步链路送达用户的编辑（不手工预置期望值）
        // The client starts from the STALE state and only the sync chain may deliver the
        // user's edits (no hand-preloading the expected value).
        var clientGraph = NodeGraph.load(serverGraph.save(null), null);
        var clientNode = clientGraph.findNode(c.id);
        clientNode.signalBands = new ArrayList<>(java.util.List.of("b0", "b1")); // 旧状态 / stale
        BusChannelHelper.syncBandsFromServer("def", SignalBus.getBands("def"), clientGraph);

        assertEquals(java.util.List.of("x", "y"), clientNode.signalBands,
            "同步链路必须把用户的频段送达 —— 编辑区频段不得回退");
    }

    @Test
    @DisplayName("统一释放：删除（确证离开）退役死名，旧名订阅者清图；他人数据不碰")
    void releaseChannelRetiresForProvableDeparture() {
        // 发布方持有 'abc'，同图订阅者 + 跨图订阅者 / the publisher and two subscribers
        var pubGraph = new NodeGraph();
        var pub = pubGraph.addNode(NodeType.BUS_OUT, 0, 0);
        pub.signalName = "abc";
        pub.signalBands = new ArrayList<>(List.of("b0", "b1"));
        BusChannelHelper.registerGraphChannels(pubGraph, POS);

        var subGraph = new NodeGraph();
        var sub = subGraph.addNode(NodeType.BUS_IN, 0, 0);
        sub.signalName = "abc";
        sub.signalBands = new ArrayList<>(List.of("x", "y"));

        // 删除路径：统一释放 / the delete path: unified release
        assertTrue(SignalBus.releaseChannel("abc", new ChannelOwner(POS, pub.id)));
        assertEquals(List.of(), SignalBus.getBands("abc"), "确证离开须退役定义（已定义为空）");
        assertNull(SignalBus.getChannel("abc"), "频道条目须随删除释放");
        BusChannelHelper.convergeBusInBands(subGraph);
        assertEquals(List.of(), sub.signalBands, "死名订阅者须清掉旧图（含剪线，issue #11 语义）");

        // 他人持有时不碰 / a foreign owner's data is untouched
        var foreignGraph = new NodeGraph();
        var f = foreignGraph.addNode(NodeType.BUS_OUT, 0, 0);
        f.signalName = "zzz";
        f.signalBands = new ArrayList<>(List.of("q0"));
        BusChannelHelper.registerGraphChannels(foreignGraph, new BlockPos(9, 9, 9));
        SignalBus.releaseChannel("zzz", new ChannelOwner(POS, pub.id)); // 非 owner 的释放请求
        assertEquals(List.of("q0"), SignalBus.getBands("zzz"), "他人定义不得被退役");
        assertNotNull(SignalBus.getChannel("zzz"), "他人频道不得被释放");
    }

    @Test
    @DisplayName("同图订阅者同样清图：改名退役不再被同图 BUS_IN 引用阻拦")
    void sameGraphSubscriberDropsStaleList() {
        var graph = new NodeGraph();
        var pub = graph.addNode(NodeType.BUS_OUT, 0, 0);
        pub.signalName = "abc";
        pub.signalBands = new ArrayList<>(List.of("b0", "b1"));
        var sub = graph.addNode(NodeType.BUS_IN, 50, 0);
        sub.signalName = "abc";
        sub.signalBands = new ArrayList<>(List.of("x", "y"));
        BusChannelHelper.registerGraphChannels(graph, POS);

        OpExecutor.apply(graph, new GraphOp(OpType.SET_DISPLAY_TEXT, POS, -1, pub.id, 0,
            null, 0f, 0f, 0, 0, 0, 0, 0, 0f, "def", 0, 0, 0, 0, null, 0, 0, 0,
            null, 0L, java.util.UUID.randomUUID(), 0, null));
        BusChannelHelper.applyBusOutRename(graph, POS, pub, "abc", null);
        BusChannelHelper.convergeBusInBands(graph);
        assertEquals(List.of(), sub.signalBands,
            "同图订阅者也要清掉死名旧图（统一规则：确证离开 → retire）");
    }
}
