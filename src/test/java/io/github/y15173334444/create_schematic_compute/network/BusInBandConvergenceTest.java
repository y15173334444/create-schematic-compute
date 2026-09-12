package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.graph.GraphNode;
import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
 * <p>These tests drive the network-free convergence core directly ({@link SignalBus} is manipulated
 * as the process-wide static it is — no Minecraft bootstrap). One constraint keeps it that way:
 * {@link SignalBus#registerChannel} logs through the mod class, whose static init needs the
 * Minecraft registries, so a test that needs a CHANNELS entry plants it reflectively (see
 * {@code plantChannel}). Pruning uses {@code SET_BANDS} semantics plus the legacy index fallback,
 * because a BUS_IN's input pins are index-bound rather than name-bound. One guard shapes the whole
 * suite: <b>absence is not a definition</b> — a channel with no <i>loaded</i> publisher is skipped
 * (a publisher between chunk loads must not cost the BUS_IN its wires), while a loaded publisher
 * defining zero bands still converges to empty.</p>
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

    /** Plant a CHANNELS entry directly — {@link SignalBus#registerChannel} would log through the
     *  mod class, whose static init needs the Minecraft bootstrap (unavailable in unit tests).
     *  The owner's BlockPos stays null on purpose: instantiating one would likewise touch
     *  Minecraft's registry statics. */
    private static void plantChannel(String name) {
        try {
            var field = SignalBus.class.getDeclaredField("CHANNELS");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            var channels = (ConcurrentHashMap<String, ChannelEntry>) field.get(null);
            channels.put(name, new ChannelEntry(new HashMap<>(), new ChannelOwner(null, 1)));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("failed to plant a CHANNELS entry for " + name, e);
        }
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
    @DisplayName("a name with no loaded publisher is left alone — absence is not a definition")
    void testPublisherAbsenceKeepsListAndWires() {
        // No BAND_REGISTRY entry and no CHANNELS entry: the publisher's chunk may be unloaded, its
        // host may tick later on a fresh server, or the block may be gone — none of that proves the
        // definition is empty. Converging anyway used to empty the list, prune every input wire by
        // index and persist the loss; when the publisher returned, the bands came back but the
        // wires did not. (A BUS_IN renamed onto a dead name is still emptied — by the rename path's
        // authoritative SET_BANDS, covered by BusInBandResolutionTest — not by this invariant.)
        GraphNode src = graph.addNode(NodeType.CONST, 0, 0);
        GraphNode in = busIn("DEAD", "leftover_0", "leftover_1");
        assertTrue(graph.addConnection(src.id, 0, in.id, 0));
        assertTrue(graph.addConnection(src.id, 0, in.id, 1));

        assertTrue(BusChannelHelper.convergeBusInBands(graph).isEmpty(),
            "absence ⇒ the pass skips the channel entirely; nothing is reported");

        assertEquals(List.of("leftover_0", "leftover_1"), in.signalBands,
            "the stale-but-consistent list survives until a definition is provable again");
        assertEquals(2, graph.connections.size(),
            "no wire may be touched while the publisher is merely absent");
    }

    @Test
    @DisplayName("a loaded publisher that defines zero bands still converges the BUS_IN to empty")
    void testLoadedPublisherZeroBandsConvergesToEmpty() {
        // A CHANNELS entry proves the publisher is loaded right now, so an empty definition from
        // it is authoritative (the user deleted every band on the BUS_OUT) — converge and prune.
        plantChannel("CH");
        GraphNode src = graph.addNode(NodeType.CONST, 0, 0);
        GraphNode in = busIn("CH", "band_0", "band_1");
        assertTrue(graph.addConnection(src.id, 0, in.id, 0));
        assertTrue(graph.addConnection(src.id, 0, in.id, 1));

        Map<String, List<String>> changed = BusChannelHelper.convergeBusInBands(graph);

        assertTrue(in.signalBands.isEmpty(), "a loaded publisher's empty definition is authoritative");
        assertTrue(changed.containsKey("CH"), "the emptied list must still be pushed");
        assertTrue(changed.get("CH").isEmpty());
        assertTrue(graph.connections.isEmpty(),
            "with the definition provably empty, the SET_BANDS pruning semantics apply in full");
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
        // A BUS_IN's input pins are INDEX-bound: GraphNode.inputPinId returns a band name only for
        // BUS_OUT (a BUS_IN resolves no input pinId at all — inputs() is not band-driven), so the
        // name-matched prune can never catch these connections. The legacy index fallback
        // (toPin >= keptCount) is what actually prunes band "gone" — the same treatment
        // releaseOldBusName already applies. BUS_IN 的输入引脚是索引绑定的：按名剪线抓不到，
        // 真正剪掉 "gone" 的是 legacy 索引回退（与 releaseOldBusName 一致）。
        assertTrue(graph.addConnection(src.id, 0, in.id, 0));
        assertTrue(graph.addConnection(src.id, 0, in.id, 1));
        assertEquals(2, graph.connections.size());

        assertFalse(BusChannelHelper.convergeBusInBands(graph).isEmpty());

        assertEquals(List.of("keep"), in.signalBands);
        assertEquals(1, graph.connections.size(),
            "only the connection whose index fell out of the converged list goes away");
        assertEquals(0, graph.connections.get(0).toPin,
            "the surviving wire is the one on the band that stayed (\"keep\")");
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
