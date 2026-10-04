package io.github.y15173334444.create_schematic_compute.graph;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 频段改名不丢线（{@link NodeGraph#reconcileBands} 机制面）：同长度双射换名 → 旧名 pinId
 * 原地改绑（发布方 BUS_OUT 输入侧 / 订阅方 BUS_IN 输出侧都在内），索引不动；重排照旧保线；
 * 真删除按名剪除；改名与重排混在一次提交的歧义回退到按名剪除。SET_BANDS op 路径与四条
 * 对齐路径共享同一实现，op 落地同样重绑。
 * Band renames keep their wires (the {@link NodeGraph#reconcileBands} mechanism): a same-length
 * bijective rename rebinds the old name's pinIds in place (covering both the publisher's BUS_OUT
 * input side and the subscriber's BUS_IN output side) without moving indices; reordering still
 * keeps wires; genuine deletions prune by name; a rename mixed with reordering in one commit
 * falls back to the prune. The SET_BANDS op path shares the implementation with all four
 * alignment paths, so an op apply rebinds too.
 */
class BusBandRenameRebindTest {

    private NodeGraph graph;
    private GraphNode source, busOut, busIn, sink;

    @BeforeEach
    void setUp() {
        graph = new NodeGraph();
        source = graph.addNode(NodeType.CONST, 0, 0);
        busOut = graph.addNode(NodeType.BUS_OUT, 50, 0);
        busOut.signalName = "CH";
        busOut.signalBands = new ArrayList<>(List.of("a", "b"));
        busIn = graph.addNode(NodeType.BUS_IN, 100, 0);
        busIn.signalName = "CH";
        busIn.signalBands = new ArrayList<>(List.of("a", "b"));
        sink = graph.addNode(NodeType.ADD, 150, 0);
        // 发布方进线：CONST → BUS_OUT 频段 a（toPinId = 频段名，名字绑定）
        // Publisher input wire: CONST → BUS_OUT band a (toPinId = band name, name-bound)
        graph.connections.add(new NodeConnection(source.id, "0", 0, busOut.id, "a", 0));
        // 订阅方出线：BUS_IN 频段 a → ADD（fromPinId = 频段名，名字绑定）
        // Subscriber output wire: BUS_IN band a → ADD (fromPinId = band name, name-bound)
        graph.connections.add(new NodeConnection(busIn.id, "a", 0, sink.id, "0", 0));
    }

    @Test
    @DisplayName("改名（发布方）：同长度双射换名重绑 toPinId，连线保留 / publisher rename rebinds toPinId, wire kept")
    void publisherRenameRebinds() {
        graph.reconcileBands(busOut, List.of("x", "b"));

        assertEquals(List.of("x", "b"), busOut.signalBands);
        assertEquals(2, graph.connections.size());
        var wire = graph.connections.get(0);
        assertEquals("x", wire.toPinId, "the old band name's pinId is rewritten in place");
        assertEquals(0, wire.toPin, "the slot index does not move");
        assertTrue(busOut.bandsDirty);
    }

    @Test
    @DisplayName("改名（订阅方经收敛路径同款）：fromPinId 原地改绑 / subscriber rename rebinds fromPinId in place")
    void subscriberRenameRebinds() {
        graph.reconcileBands(busIn, List.of("x", "b"));

        assertEquals(List.of("x", "b"), busIn.signalBands);
        var wire = graph.connections.get(1);
        assertEquals("x", wire.fromPinId);
        assertEquals(0, wire.fromPin);
    }

    @Test
    @DisplayName("重排：名字集合未变，连线一条不剪 / reordering prunes nothing")
    void reorderKeepsWires() {
        graph.reconcileBands(busOut, List.of("b", "a"));
        graph.rebuildInputCache();

        assertEquals(2, graph.connections.size());
        assertEquals("a", graph.connections.get(0).toPinId, "the wire keeps following its band name");
        assertEquals(1, graph.connections.get(0).toPin, "resolved to the band's new index");
    }

    @Test
    @DisplayName("真删除：被删频段上的连线剪除（对齐是逐节点的，别的节点的线不受影响） / genuine deletion prunes the removed band's wire, scoped to the aligned node")
    void deletionPrunes() {
        // 追加一条绑定到 b 的发布方进线；对齐到 [a] 后它应被剪，a 线与订阅方线保留。
        // Add a publisher wire bound to b; aligning to [a] prunes it while the a-wire
        // and the subscriber's wire (a different node's alignment scope) survive.
        graph.connections.add(new NodeConnection(source.id, "0", 1, busOut.id, "b", 1));

        graph.reconcileBands(busOut, List.of("a"));

        assertEquals(2, graph.connections.size());
        assertEquals("a", graph.connections.get(0).toPinId, "the surviving band keeps its wire");
        assertEquals(busIn.id, graph.connections.get(1).fromId,
            "the subscriber's wire belongs to the BUS_IN's own alignment scope and is untouched");
    }

    @Test
    @DisplayName("歧义回退：改名撞上已有频段名 → 按名剪除而非错绑 / ambiguity falls back to pruning, never mis-binds")
    void ambiguousRenameFallsBackToPrune() {
        // a → b：新名 b 已在旧表（可能是重排的一部分），双射判定失败 → 回退按名剪除
        // a → b: the new name already exists in the old list (could be part of a reorder),
        // the bijective test fails → fall back to pruning by name.
        graph.reconcileBands(busOut, List.of("b", "c"));

        assertEquals(1, graph.connections.size(),
            "the publisher's a-wire is pruned rather than mis-bound to b");
        assertEquals(busIn.id, graph.connections.get(0).fromId,
            "only the untouched subscriber wire remains");
        assertEquals(List.of("b", "c"), busOut.signalBands);
    }

    @Test
    @DisplayName("SET_BANDS op 落地走同一规则：改名重绑不剪线 / the SET_BANDS op path rebinds through the same rule")
    void setBandsOpRebinds() {
        var op = new GraphOp(OpType.SET_BANDS, null, -1, busOut.id,
            0, null, 0f, 0f, 0, 0, 0, 0, 0, 0f,
            null, 0, 0, 0, 0, List.of("x", "b"), 0, 0, 0,
            null, 0L, null, 0, null);

        OpExecutor.apply(graph, op);

        assertEquals(List.of("x", "b"), busOut.signalBands);
        assertEquals(2, graph.connections.size());
        assertEquals("x", graph.connections.get(0).toPinId, "renamed via op: wire rebound, not pruned");
    }
}
