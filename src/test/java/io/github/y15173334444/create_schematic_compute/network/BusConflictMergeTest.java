package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.graph.GraphNode;
import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for issue #12 — BUS_OUT conflict flags.
 * issue #12 的回归测试 —— BUS_OUT 冲突标志。
 *
 * <p>Background / 背景：a BUS_OUT's channel can be owned by a <b>peer block</b>, but that is
 * knowable only on the server. The client used to guess it from the global band registry (via a
 * branch that was structurally unreachable) and, worse, <b>assigned</b> the result — so the
 * authoritative {@code busConflict} that arrived with the graph was reset to {@code false}.
 * The client now merges only the locally provable part (a same-name duplicate in the same graph)
 * and never lowers the synced value.</p>
 * <p>BUS_OUT 的频道也可能被**对端方块**占用，但那只有服务端知道。客户端此前用全局频段表去猜
 *（分支结构上不可达），而且是**赋值** —— 于是随图到达的权威 {@code busConflict} 被改回 false。
 * 现在只合并本地可证明的部分（同图重名），并绝不下调同步来的值。</p>
 *
 * <p>{@code busConflict} is public and these tests drive
 * {@link BusChannelHelper#mergeLocalBusConflicts} directly — no Minecraft bootstrap needed
 * (same convention as {@code BusInBandResolutionTest}).</p>
 */
class BusConflictMergeTest {

    private NodeGraph graph;

    @BeforeEach
    void setUp() {
        graph = new NodeGraph();
    }

    /** A named BUS_OUT. / 一个带频道名的 BUS_OUT。 */
    private GraphNode busOut(String name) {
        GraphNode n = graph.addNode(NodeType.BUS_OUT, 0, 0);
        n.signalName = name;
        return n;
    }

    // ══════════ 1. The locally provable case / 本地可证明的情形 ══════════

    @Test
    @DisplayName("a same-name duplicate in the same graph raises the flag on both BUS_OUTs")
    void testSameGraphDuplicateRaisesBoth() {
        GraphNode a = busOut("CH");
        GraphNode b = busOut("CH");

        BusChannelHelper.mergeLocalBusConflicts(graph);

        assertTrue(a.busConflict, "same-graph duplicate is a conflict the client can prove");
        assertTrue(b.busConflict, "same-graph duplicate is a conflict the client can prove");
    }

    // ══════════ 2. The issue #12 regression: never lower the synced value ══════════

    @Test
    @DisplayName("the server-synced conflict value is never lowered (issue #12 regression)")
    void testNeverLowersAuthoritativeValue() {
        GraphNode solo = busOut("OWNED_ELSEWHERE");
        solo.busConflict = true; // as delivered by the authoritative graph

        BusChannelHelper.mergeLocalBusConflicts(graph);

        assertTrue(solo.busConflict,
            "a client cannot disprove a cross-block conflict — the synced value must survive");
    }

    @Test
    @DisplayName("distinct channel names keep their own synced flags")
    void testDistinctNamesKeepTheirOwnFlags() {
        GraphNode conflicted = busOut("TAKEN");
        conflicted.busConflict = true;
        GraphNode free = busOut("FREE");
        free.busConflict = false;

        BusChannelHelper.mergeLocalBusConflicts(graph);

        assertTrue(conflicted.busConflict, "unrelated names must not clear each other");
        assertFalse(free.busConflict, "no local duplicate ⇒ nothing to raise");
    }

    // ══════════ 3. Things a client *can* disprove / 客户端能证伪的情形 ══════════

    @Test
    @DisplayName("a BUS_IN sharing the name is not a conflict (normal publish/subscribe pair)")
    void testBusInIsNotAConflict() {
        GraphNode out = busOut("CH");
        GraphNode in = graph.addNode(NodeType.BUS_IN, 0, 0);
        in.signalName = "CH";

        BusChannelHelper.mergeLocalBusConflicts(graph);

        assertFalse(out.busConflict, "a BUS_IN reader is not a competing publisher");
        assertFalse(in.busConflict, "only BUS_OUTs can hold a channel");
    }

    @Test
    @DisplayName("a node that is not a named BUS_OUT cannot be conflicted")
    void testNonNamedBusOutCleared() {
        GraphNode nameless = graph.addNode(NodeType.BUS_OUT, 0, 0); // signalName stays ""
        nameless.busConflict = true;
        GraphNode constNode = graph.addNode(NodeType.CONST, 0, 0);
        constNode.busConflict = true;

        BusChannelHelper.mergeLocalBusConflicts(graph);

        assertFalse(nameless.busConflict, "a BUS_OUT without a channel name holds nothing");
        assertFalse(constNode.busConflict, "non-BUS_OUT nodes never hold a channel");
    }

    @Test
    @DisplayName("merge is idempotent and null-graph safe")
    void testIdempotentAndNullSafe() {
        assertDoesNotThrow(() -> BusChannelHelper.mergeLocalBusConflicts(null));
        GraphNode a = busOut("CH");
        GraphNode b = busOut("CH");
        BusChannelHelper.mergeLocalBusConflicts(graph);
        BusChannelHelper.mergeLocalBusConflicts(graph);
        assertTrue(a.busConflict && b.busConflict);
    }
}
