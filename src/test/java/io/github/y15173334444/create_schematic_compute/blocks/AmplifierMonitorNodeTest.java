package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.graph.GraphNode;
import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 功放监听目标解析回归：盖子频谱的取数源 = 图最上方 MUSIC 节点（自动）或面板指定的那个。
 * 「最上方」与封装 IO 同口径（y 升序、id 决胜，GraphNode.getSubNodes 同款比较器）——测试里的
 * 期望值按这一口径独立写死，不借道被测代码。指定编号悬空（节点删除）时显示回落最上方，
 * 编号本身保留（撤销重建后自动重挂）。
 * Amplifier monitor resolution regression: the lid spectrum taps the graph's topmost MUSIC
 * node (auto) or the one pinned in the panel. "Topmost" shares the encapsulation-IO ordering
 * (y ascending, id tie-break — the GraphNode.getSubNodes comparator); expectations below pin
 * that contract independently, never via the code under test. A dangling pin falls back to
 * the topmost for display while the id is kept (undo-recreate re-attaches it).
 */
class AmplifierMonitorNodeTest {

    private static final int AUTO = AmplifierComputerBlockEntity.AUTO_MONITOR;

    private static GraphNode addMusic(NodeGraph g, float y) {
        return g.addNode(NodeType.MUSIC, 100, y);
    }

    private static GraphNode resolve(NodeGraph g, int pinned) {
        return AmplifierComputerBlockEntity.resolveMonitoredNode(g, pinned);
    }

    @Test
    @DisplayName("自动：图最上方 MUSIC 胜出；同 y 时 id 小者胜 / auto: topmost MUSIC wins; smaller id breaks y ties")
    void autoPicksTopmostWithIdTieBreak() {
        var g = new NodeGraph();
        var lower = addMusic(g, 300);
        var top = addMusic(g, 100);
        addMusic(g, 200);
        assertSame(top, resolve(g, AUTO), "y 最小者胜 / smallest y wins");
        assertSame(top, resolve(g, top.id + 1000), "悬空编号回落最上方 / dangling pin falls back");

        var g2 = new NodeGraph();
        var first = addMusic(g2, 100);   // id 0 / id 0
        addMusic(g2, 100);               // id 1
        assertSame(first, resolve(g2, AUTO), "同 y → id 决胜 / y tie → smaller id");
    }

    @Test
    @DisplayName("指定：非最上方的有效 MUSIC 编号照常生效 / pinned: a valid non-topmost id wins")
    void pinnedWinsOverTopmost() {
        var g = new NodeGraph();
        addMusic(g, 100);
        var bottom = addMusic(g, 400);
        assertSame(bottom, resolve(g, bottom.id), "指定的下方节点生效 / the pinned lower node wins");
    }

    @Test
    @DisplayName("指定悬空：删除后回落最上方；非 MUSIC 编号同样回落 / dangling pin falls back; so does a non-MUSIC id")
    void danglingAndNonMusicPinsFallBack() {
        var g = new NodeGraph();
        var top = addMusic(g, 100);
        var pinned = addMusic(g, 400);
        g.removeNode(pinned.id);
        assertSame(top, resolve(g, pinned.id), "删除后回落 / falls back after deletion");

        var other = g.addNode(NodeType.ADD, 0, 500);   // 非 MUSIC / not MUSIC
        assertSame(top, resolve(g, other.id), "非 MUSIC 编号回落 / non-MUSIC id falls back");
    }

    @Test
    @DisplayName("自动跟随：最上方节点挪走后目标切到新的最上方 / auto follows: relocating the top node re-targets")
    void autoFollowsRelocation() {
        var g = new NodeGraph();
        var a = addMusic(g, 100);
        var b = addMusic(g, 200);
        assertSame(a, resolve(g, AUTO));
        a.y = 500;
        assertSame(b, resolve(g, AUTO), "挪走后 b 成为最上方 / after moving a down, b is topmost");
    }

    @Test
    @DisplayName("无 MUSIC 节点 → null（频谱自然静默）/ no MUSIC node → null (the spectrum goes silent on its own)")
    void noMusicNodesYieldsNull() {
        assertNull(resolve(new NodeGraph(), AUTO), "空图 / empty graph");
        var g = new NodeGraph();
        g.addNode(NodeType.ADD, 0, 0);
        assertNull(resolve(g, AUTO), "只有非 MUSIC 节点 / non-MUSIC nodes only");
        assertNull(resolve(g, 42), "悬空指定同样 null / dangling pin also null");
    }

    @Test
    @DisplayName("校验核：AUTO 与有效 MUSIC 编号接受；未知/非 MUSIC 编号维持原值 / sanitizer: AUTO and valid MUSIC accepted; unknown/non-MUSIC keep current")
    void sanitizerAcceptsAndRejects() {
        var g = new NodeGraph();
        var m = addMusic(g, 100);
        int current = 12345;

        assertEquals(AUTO, AmplifierComputerBlockEntity.sanitizedMonitor(current, AUTO, g),
            "AUTO 恒接受 / AUTO always accepted");
        assertEquals(m.id, AmplifierComputerBlockEntity.sanitizedMonitor(current, m.id, g),
            "有效 MUSIC 编号接受 / a valid MUSIC id is accepted");
        assertEquals(current, AmplifierComputerBlockEntity.sanitizedMonitor(current, 99999, g),
            "未知编号拒收 / unknown id rejected");
        var notMusic = g.addNode(NodeType.ADD, 0, 50);
        assertEquals(current, AmplifierComputerBlockEntity.sanitizedMonitor(current, notMusic.id, g),
            "非 MUSIC 编号拒收 / non-MUSIC id rejected");
    }
}
