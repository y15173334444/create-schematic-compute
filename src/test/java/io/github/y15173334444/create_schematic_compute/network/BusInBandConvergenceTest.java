package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.graph.GraphNode;
import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for issue #15 — a BUS_IN's band list is kept equal to the channel definition by
 * a server-side invariant ({@link BusChannelHelper#convergeBusInBands}).
 * issue #15 的回归测试 —— BUS_IN 的频段列表由服务端不变量保持等于频道定义。
 *
 * <p>Background / 背景：a BUS_IN's list <b>is</b> its pin structure. It used to be refreshed only
 * in the block that initiated a change (a band upload or a rename); every other block kept a stale
 * list, and the client used to hide that by re-deriving the list from its own band registry — the
 * divergence issue #11 removed. With that crutch gone the staleness became visible everywhere, and
 * even reopening the editor did not help because the <b>server's own copy</b> was stale.</p>
 * <p>BUS_IN 的列表**就是**它的引脚结构。过去只有发起变更的那个方块会被刷新，其它方块一直保留
 * 过期列表；客户端靠按自己的频段表重新推导来遮掩 —— 而那正是 issue #11 去掉的分叉源。遮掩没了
 * 之后，过期就处处可见，而且重开编辑器也没用，因为**服务端自己那份就是旧的**。</p>
 *
 * <p>{@code convergeBusInBands} returns the channels that changed, mapped to their converged list.
 * The caller ships that over the <b>node-data channel</b> ({@code BusBandSyncPacket}) — never as a
 * whole-graph NBT push, which would clobber edits in flight.</p>
 *
 * <p>These tests drive the (network-free, Minecraft-free) convergence core directly. Pruning uses
 * {@code SET_BANDS} semantics plus the legacy index fallback, because a BUS_IN's input pins are
 * index-bound rather than name-bound.</p>
 */
class BusInBandConvergenceTest {

    private NodeGraph graph;

    @BeforeEach
    void setUp() {
        graph = new NodeGraph();
        SignalBus.clear(); // BAND_REGISTRY is a process-wide static
    }

    @AfterEach
    void tearDown() {
        SignalBus.clear();
    }

    private GraphNode busIn(String name, String... bands) {
        GraphNode n = graph.addNode(NodeType.BUS_IN, 0, 0);
        n.signalName = name;
        n.signalBands = new ArrayList<>(List.of(bands));
        return n;
    }

    // ══════════ 1. The invariant itself / 不变量本身 ══════════

    @Test
    @DisplayName("a stale BUS_IN band list is converged and the channel is reported as changed")
    void testStaleListConverged() {
        SignalBus.registerBands("CH", List.of("b0", "b1", "b2"));
        GraphNode in = busIn("CH", "old_0", "old_1");

        Map<String, List<String>> changed = BusChannelHelper.convergeBusInBands(graph);

        assertEquals(List.of("b0", "b1", "b2"), in.signalBands);
        assertEquals(List.of("b0", "b1", "b2"), changed.get("CH"),
            "the caller needs the converged list to ship over the node-data channel");
    }

    @Test
    @DisplayName("an already-matching list is left alone and nothing is reported")
    void testMatchingListUntouched() {
        SignalBus.registerBands("CH", List.of("b0"));
        GraphNode in = busIn("CH", "b0");

        assertTrue(BusChannelHelper.convergeBusInBands(graph).isEmpty(),
            "no change ⇒ no packet is sent");
        assertEquals(List.of("b0"), in.signalBands);
    }

    @Test
    @DisplayName("a name with no publisher at all converges to empty")
    void testDeadNameConvergesToEmpty() {
        GraphNode in = busIn("DEAD", "leftover_0");

        Map<String, List<String>> changed = BusChannelHelper.convergeBusInBands(graph);

        assertTrue(in.signalBands.isEmpty(), "no publisher ⇒ no bands (the agreed semantics)");
        assertTrue(changed.containsKey("DEAD"), "the emptied list must be pushed too");
        assertTrue(changed.get("DEAD").isEmpty());
    }

    @Test
    @DisplayName("several BUS_INs on the same channel converge to one reported entry")
    void testSameChannelConvergesAll() {
        SignalBus.registerBands("CH", List.of("b0", "b1"));
        GraphNode a = busIn("CH", "stale_a");
        GraphNode b = busIn("CH", "stale_b", "stale_c", "stale_d");

        Map<String, List<String>> changed = BusChannelHelper.convergeBusInBands(graph);

        assertEquals(List.of("b0", "b1"), a.signalBands);
        assertEquals(List.of("b0", "b1"), b.signalBands);
        assertEquals(1, changed.size(), "one channel ⇒ one packet, not one per node");
        assertEquals(List.of("b0", "b1"), changed.get("CH"));
    }

    // ══════════ 2. Definition source rules (issue #14) hold here too ══════════

    @Test
    @DisplayName("a conflicted BUS_OUT in the same graph is not used as the definition source")
    void testConflictedSourceIgnored() {
        GraphNode loser = graph.addNode(NodeType.BUS_OUT, 0, 0);
        loser.signalName = "DUP";
        loser.signalBands = new ArrayList<>(List.of("loser_0"));
        loser.busConflict = true;
        SignalBus.registerBands("DUP", List.of("winner_0"));

        GraphNode in = busIn("DUP", "whatever");

        assertFalse(BusChannelHelper.convergeBusInBands(graph).isEmpty());
        assertEquals(List.of("winner_0"), in.signalBands,
            "a conflicted BUS_OUT owns nothing — the registry's (winner's) list must win");
    }

    // ══════════ 3. Pruning semantics match SET_BANDS / 剪线语义与 SET_BANDS 一致 ══════════

    @Test
    @DisplayName("connections on bands that disappeared are pruned; surviving bands keep theirs")
    void testPrunesOnlyRemovedBands() {
        SignalBus.registerBands("CH", List.of("keep"));
        GraphNode src = graph.addNode(NodeType.CONST, 0, 0);
        GraphNode in = busIn("CH", "keep", "gone");
        assertTrue(graph.addConnection(src.id, 0, in.id, 0)); // binds pinId "keep"
        assertTrue(graph.addConnection(src.id, 0, in.id, 1)); // binds pinId "gone"
        assertEquals(2, graph.connections.size());

        assertFalse(BusChannelHelper.convergeBusInBands(graph).isEmpty());

        assertEquals(List.of("keep"), in.signalBands);
        assertEquals(1, graph.connections.size(),
            "only the connection on the band that actually disappeared goes away");
    }

    // ══════════ 4. Edges / 边界 ══════════

    @Test
    @DisplayName("null graph is safe and a nameless BUS_IN is not ours to converge")
    void testNullSafeAndNamelessIgnored() {
        assertTrue(BusChannelHelper.convergeBusInBands(null).isEmpty());

        GraphNode nameless = graph.addNode(NodeType.BUS_IN, 0, 0); // signalName stays ""
        nameless.signalBands = new ArrayList<>(List.of("x"));

        assertTrue(BusChannelHelper.convergeBusInBands(graph).isEmpty());
        assertEquals(List.of("x"), nameless.signalBands,
            "without a channel name there is nothing to converge to");
    }
}
