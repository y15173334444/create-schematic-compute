package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.graph.GraphNode;
import io.github.y15173334444.create_schematic_compute.graph.GraphOp;
import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import io.github.y15173334444.create_schematic_compute.graph.OpExecutor;
import io.github.y15173334444.create_schematic_compute.graph.OpType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for issue #11 — BUS_IN band-list resolution.
 * issue #11 的回归测试 —— BUS_IN 频段列表解析。
 *
 * <p>Background / 背景：a renamed BUS_IN's band list used to be resolved independently on
 * every side (client commit, server apply, and each client re-applying the broadcast op),
 * each consulting its <b>own</b> {@code BAND_REGISTRY} — a per-side cache. Because the
 * server prunes a dead channel name locally without telling clients, those caches diverged
 * and the <b>same</b> BUS_IN ended up with different band graphs on different clients
 * (one empty, one showing the old graph).</p>
 * <p>The fix makes the server the <b>single</b> resolution point
 * ({@link BusChannelHelper#resolveBusInBands}) and ships the resolved list to every editor
 * as an authoritative {@code SET_BANDS}.</p>
 *
 * <p>These tests pin the two invariants that make the divergence impossible:</p>
 * <ol>
 *   <li>applying a rename ({@code SET_DISPLAY_TEXT}) must <b>not</b> touch a node's bands —
 *       only the server's authoritative {@code SET_BANDS} may;</li>
 *   <li>the resolver is deterministic given the graph + registry, with a documented order.</li>
 * </ol>
 *
 * <p>GraphOp is built directly with null BlockPos/ItemStack/UUID — the exercised branches
 * never read those fields (same convention as {@code OpGenerationTest}).</p>
 */
class BusInBandResolutionTest {

    private NodeGraph graph;

    @BeforeEach
    void setUp() {
        graph = new NodeGraph();
        SignalBus.clear(); // BAND_REGISTRY / CHANNELS are process-wide statics
    }

    @AfterEach
    void tearDown() {
        SignalBus.clear();
    }

    /** GraphOp carrying only what {@code SET_DISPLAY_TEXT} / {@code SET_BANDS} read. */
    private static GraphOp renameOp(int nodeId, String newName) {
        return new GraphOp(OpType.SET_DISPLAY_TEXT, null, -1, nodeId, 0, null, 0f, 0f,
            0, 0, 0, 0, 0, 0f, newName, 0, 0, 0, 0, null, 0, 0, 0,
            null, 0L, null, 0, null);
    }

    private static GraphOp setBandsOp(int nodeId, List<String> bands) {
        return new GraphOp(OpType.SET_BANDS, null, -1, nodeId, 0, null, 0f, 0f,
            0, 0, 0, 0, 0, 0f, null, 0, 0, 0, 0, bands, 0, 0, 0,
            null, 0L, null, 0, null);
    }

    // ══════════ 1. Renaming must not touch bands / 改名不得触碰频段 ══════════

    @Test
    @DisplayName("SET_DISPLAY_TEXT on a BUS_IN changes the name but never the band list")
    void testRenameLeavesBandsUntouched() {
        GraphNode busIn = graph.addNode(NodeType.BUS_IN, 0, 0);
        OpExecutor.apply(graph, setBandsOp(busIn.id, List.of("band_0", "band_1")));
        assertEquals(List.of("band_0", "band_1"), busIn.signalBands, "precondition: bands applied");

        // No definition exists for the new name — the old code emptied the list right here,
        // which is what made different sides disagree.
        OpExecutor.apply(graph, renameOp(busIn.id, "DBG_UNKNOWN"));

        assertEquals("DBG_UNKNOWN", busIn.signalName);
        assertEquals(List.of("band_0", "band_1"), busIn.signalBands,
            "rename must not resolve/replace bands locally — only the server's SET_BANDS may");
    }

    // ══════════ 2. Resolver: documented order / 解析点：有据可依的顺序 ══════════

    @Test
    @DisplayName("resolveBusInBands prefers a same-graph node that already has bands")
    void testResolverPrefersSameGraphNode() {
        // A stale-looking registry entry must NOT win over replicated graph data.
        SignalBus.registerBands("CH", List.of("stale_0"));

        GraphNode busOut = graph.addNode(NodeType.BUS_OUT, 0, 0);
        busOut.signalName = "CH";
        OpExecutor.apply(graph, setBandsOp(busOut.id, List.of("band_0", "band_1", "band_2")));

        GraphNode busIn = graph.addNode(NodeType.BUS_IN, 0, 0);
        busIn.signalName = "CH";

        assertEquals(List.of("band_0", "band_1", "band_2"),
            BusChannelHelper.resolveBusInBands(graph, busIn, "CH"));
    }

    @Test
    @DisplayName("resolveBusInBands falls back to the band registry when the graph has no definition")
    void testResolverFallsBackToRegistry() {
        SignalBus.registerBands("CH", List.of("band_0", "band_1"));

        GraphNode busIn = graph.addNode(NodeType.BUS_IN, 0, 0);
        busIn.signalName = "CH";

        assertEquals(List.of("band_0", "band_1"),
            BusChannelHelper.resolveBusInBands(graph, busIn, "CH"));
    }

    @Test
    @DisplayName("resolveBusInBands returns empty for a name with no publisher anywhere")
    void testResolverReturnsEmptyForDeadName() {
        GraphNode busIn = graph.addNode(NodeType.BUS_IN, 0, 0);
        busIn.signalName = "DEAD";

        List<String> resolved = BusChannelHelper.resolveBusInBands(graph, busIn, "DEAD");
        assertNotNull(resolved);
        assertTrue(resolved.isEmpty(), "a name with no publisher resolves to an empty band list");
    }

    @Test
    @DisplayName("resolveBusInBands ignores the node itself (never self-adopts its own list)")
    void testResolverExcludesSelf() {
        GraphNode busIn = graph.addNode(NodeType.BUS_IN, 0, 0);
        busIn.signalName = "CH";
        busIn.signalBands = new ArrayList<>(List.of("own_0"));

        // Only the node itself carries bands for "CH" — the resolver must not read them back.
        assertTrue(BusChannelHelper.resolveBusInBands(graph, busIn, "CH").isEmpty());
    }

    @Test
    @DisplayName("resolveBusInBands is empty for an empty channel name")
    void testResolverEmptyName() {
        GraphNode busIn = graph.addNode(NodeType.BUS_IN, 0, 0);
        assertTrue(BusChannelHelper.resolveBusInBands(graph, busIn, "").isEmpty());
    }

    // ══════════ 3. SET_BANDS no-op guard / SET_BANDS 的未变守卫 ══════════

    @Test
    @DisplayName("SET_BANDS with an identical list neither bumps the generation nor prunes")
    void testIdenticalSetBandsIsNoOp() {
        GraphNode busIn = graph.addNode(NodeType.BUS_IN, 0, 0);
        OpExecutor.apply(graph, setBandsOp(busIn.id, List.of("band_0")));
        int g0 = graph.graphGeneration;

        OpExecutor.apply(graph, setBandsOp(busIn.id, List.of("band_0")));

        assertEquals(g0, graph.graphGeneration,
            "an authoritative SET_BANDS that carries the value a side already holds must be a no-op");
    }
}
