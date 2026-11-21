package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.graph.GraphNode;
import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * known-open-issues 条目『频道释放依赖快照路径（含别名死守卫）』的行为锚：
 * <ul>
 *   <li>发布者键差集不别名——快照先于图变更拷贝，diff 恒真（旧 lastEvaluatedGraph 别名
 *       守卫在同对象面前恒空转）；</li>
 *   <li>已删 BUS 名推导——同名由另一节点续发时不推送清空，私有键不入列；</li>
 *   <li>删除的撤销语义——恢复节点以原 owner 身份经不别名重注册内核重新拿回频道，
 *       名字被他人接走时按先注册者胜标冲突、不抢。</li>
 * </ul>
 * Behaviour anchors for register entry "channel release rode the snapshot path (with the
 * aliased dead guard)": the publisher-key diff holds no alias (the snapshot predates the
 * graph mutation, so the diff is always true — the old lastEvaluatedGraph guard spun
 * forever against the same object); the deleted-BUS-name derivation skips names still
 * published by another node and excludes private keys; and the undo of a deletion
 * re-registers the restored node under its original owner through the non-aliased core —
 * flagging a conflict instead of stealing when the name was taken over meanwhile.
 */
class ChannelSnapshotDiffTest {

    private static final BlockPos POS = new BlockPos(1, 2, 3);

    @BeforeEach @AfterEach void resetBus() { SignalBus.clear(); }

    private static Set<String> snapshotOf(NodeGraph g) {
        var keys = new HashSet<String>();
        for (var n : g.nodes)
            if ((n.type == NodeType.BUS_OUT || n.type == NodeType.PRIVATE_OUT) && !n.signalName.isEmpty())
                keys.add(BusChannelHelper.publisherKey(n));
        return keys;
    }

    @Test
    @DisplayName("键差集不别名：快照先于变更拷贝，删除/改名都进差集")
    void snapshotDiffIsHonest() {
        var graph = new NodeGraph();
        var out = graph.addNode(NodeType.BUS_OUT, 0, 0);
        out.signalName = "abc";
        var priv = graph.addNode(NodeType.PRIVATE_OUT, 1, 0);
        priv.signalName = "secret";

        // 快照 = 独立拷贝（GraphHost.snapshotBusOutKeys 的同款时点：上次重编译收尾）
        var snapshot = snapshotOf(graph);
        assertEquals(Set.of("B:abc@" + out.id, "P:secret@" + priv.id), snapshot,
            "the snapshot carries the type tag + name@id for both tables");

        // 删除 + 改名（图原地变更——旧别名守卫在这一步之后 diff 恒空）
        graph.removeNode(priv.id);
        out.signalName = "def";
        var removed = BusChannelHelper.removedPublisherKeys(graph, snapshot);

        assertEquals(Set.of("B:abc@" + out.id, "P:secret@" + priv.id), removed,
            "the rename's old key and the deletion's key both land in the diff (no alias)");
        assertTrue(BusChannelHelper.deletedBusNames(graph, removed).contains("abc"),
            "the renamed-away BUS name is fully gone → cleanup push");
    }

    @Test
    @DisplayName("已删名推导：同名仍被另一节点续发时不推送；私有键不入列")
    void deletedNamesSkipLivePublishers() {
        var graph = new NodeGraph();
        var gone = graph.addNode(NodeType.BUS_OUT, 0, 0);
        gone.signalName = "abc";
        var alive = graph.addNode(NodeType.BUS_OUT, 1, 0);
        alive.signalName = "shared";
        var priv = graph.addNode(NodeType.PRIVATE_OUT, 2, 0);
        priv.signalName = "secret";
        var snapshot = snapshotOf(graph);

        graph.removeNode(gone.id); // abc 彻底死名
        var removed = BusChannelHelper.removedPublisherKeys(graph, snapshot);
        assertEquals(Set.of("B:abc@" + gone.id), removed);
        assertEquals(Set.of("abc"), BusChannelHelper.deletedBusNames(graph, removed),
            "'shared' is still published (by another node) and must not be pushed empty; "
            + "private keys never enter the BUS cleanup push");

        alive.signalName = ""; // 'shared' 的发布方清名（键差集随之含 shared，但图内已无 BUS_OUT 持名）
        removed = BusChannelHelper.removedPublisherKeys(graph, snapshot);
        assertTrue(removed.contains("B:shared@" + alive.id), "the emptied publisher's key leaves the snapshot");
        assertEquals(Set.of("abc", "shared"), BusChannelHelper.deletedBusNames(graph, removed),
            "a name whose publisher emptied its own name counts as gone for the push");
    }

    @Test
    @DisplayName("删除的撤销：恢复节点经不别名内核以原 owner 重新注册频道与频段")
    void undoRestoreReRegistersThroughSnapshotDiff() {
        var graph = new NodeGraph();
        var out = graph.addNode(NodeType.BUS_OUT, 0, 0);
        out.signalName = "abc";
        out.signalBands = new ArrayList<>(List.of("b0", "b1"));
        BusChannelHelper.registerGraphChannels(graph, POS);
        assertNotNull(SignalBus.getChannel("abc"), "fixture: registered");

        // 快照 → 删除 → 快照差集释放（GraphHost.unregisterRemovedBusOutNodes 的同款内核）
        var snapshot = snapshotOf(graph);
        graph.removeNode(out.id);
        var removed = BusChannelHelper.removedPublisherKeys(graph, snapshot);
        assertEquals(Set.of("B:abc@" + out.id), removed);
        SignalBus.releaseChannel("abc", new ChannelOwner(POS, out.id));
        assertNull(SignalBus.getChannel("abc"), "fixture: the delete released the channel");

        // 撤销恢复：外部构造节点以原 id 回图（NodeGraph.adoptNode，不触碰计数器）。
        // 快照取删除前（快删快撤同周期形态）：键仍在快照里 → existing → updateChannel 落空
        // → registerChannel 兜底——殊途同归地拿回频道（删除后隔轮再撤销的形态见下一个用例
        // 的注释）。快删快撤不翻冲突旗标 → anyConflict = false。
        var restored = new GraphNode(out.id, NodeType.BUS_OUT, 0, 0);
        restored.signalName = "abc";
        restored.signalBands = new ArrayList<>(List.of("b0", "b1"));
        restored.busInternalMap = new HashMap<>();
        graph.adoptNode(restored);

        assertFalse(BusChannelHelper.reRegisterChannelsScoped(graph, snapshot, POS, null),
            "the fast delete+undo within one cycle re-registers without a conflict transition");
        var entry = SignalBus.getChannel("abc");
        assertNotNull(entry, "the undo restore re-registers the channel");
        assertEquals(POS, entry.owner.pos(), "the channel returns under the original owner position");
        assertEquals(out.id, entry.owner.nodeId(), "the channel returns under the original node id");
        assertSame(restored.busInternalMap, entry.internalMap, "the restored node owns the live map");
        assertEquals(List.of("b0", "b1"), SignalBus.getBands("abc"), "the band definition returns with registration");
        assertFalse(restored.busConflict, "no foreign claimant → no conflict");
    }

    @Test
    @DisplayName("撤销撞上新占主：恢复节点标冲突、不抢频道")
    void undoRestoreOntoForeignClaimantFlagsConflict() {
        var graph = new NodeGraph();
        var out = graph.addNode(NodeType.BUS_OUT, 0, 0);
        out.signalName = "abc";
        out.busInternalMap = new HashMap<>();
        BusChannelHelper.registerGraphChannels(graph, POS);
        var snapshot = snapshotOf(graph);
        graph.removeNode(out.id);
        SignalBus.releaseChannel("abc", new ChannelOwner(POS, out.id));

        // 撤销之前，他人已接管同名 / a foreign publisher took the name meanwhile
        var foreignMap = new HashMap<String, Float>();
        assertTrue(SignalBus.registerChannel("abc", foreignMap, new ChannelOwner(new BlockPos(9, 9, 9), 42)));

        var restored = new GraphNode(out.id, NodeType.BUS_OUT, 0, 0);
        restored.signalName = "abc";
        restored.signalBands = new ArrayList<>(List.of("b0"));
        restored.busInternalMap = new HashMap<>();
        graph.adoptNode(restored);

        assertTrue(BusChannelHelper.reRegisterChannelsScoped(graph, snapshot, POS, null),
            "the conflict-flag transition (false → true) is reported as a change");
        assertTrue(restored.busConflict, "the restored node flags the conflict (first-registrant-wins)");
        assertSame(foreignMap, SignalBus.getChannel("abc").internalMap, "the foreign claimant keeps the channel");
    }

    // ── GraphHost 接线源码锚（recompile 通路无法 headless 实例化；源码锚惯例） ──
    //   Source anchors on GraphHost's recompile wiring (the path cannot be instantiated
    //   headless; the source-anchor convention applies).

    private static final String HOST =
        "src/main/java/io/github/y15173334444/create_schematic_compute/blocks/GraphHost.java";

    @Test
    @DisplayName("源码锚：别名字段退役 + 重编译吃键差集 + 注册即快照")
    void hostWiringAnchors() throws Exception {
        String t = Files.readString(Path.of(HOST));
        assertFalse(t.contains("NodeGraph lastEvaluatedGraph"),
            "the aliased old-graph field must stay retired (the dead guard's root)");
        assertFalse(t.contains("= lastEvaluatedGraph"),
            "no reader may resurrect the alias (the snapshot diff is the only baseline)");
        assertTrue(t.contains("BusChannelHelper.removedPublisherKeys(graph, lastBusOutKeys)"),
            "both recompile paths derive the release set from the non-aliased key snapshot");
        assertTrue(t.contains("BusChannelHelper.syncDeletedBusNames(deletedNames, pos(), lvl())"),
            "the client stale-name push rides the derived deleted-name set");
        assertTrue(t.contains("BusChannelHelper.reRegisterChannels(graph, lastBusOutKeys, pos(), lvl())"),
            "re-registration takes the snapshot keys, not a graph object");
        // 注册即快照（窄窗收口）：registerBusChannels 方法体内含 snapshotBusOutKeys()
        int reg = t.indexOf("public void registerBusChannels()");
        assertTrue(reg > 0, "registerBusChannels exists");
        int snap = t.indexOf("snapshotBusOutKeys();", reg);
        int end = t.indexOf("\n    }", reg);
        assertTrue(snap > 0 && snap < end, "registerBusChannels snapshots right after registering");
        assertTrue(t.contains("BusChannelHelper.publisherKey(n)"),
            "the snapshot key format's single source lives in BusChannelHelper");
    }
}
