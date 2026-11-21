package io.github.y15173334444.create_schematic_compute.client.editor;

import io.github.y15173334444.create_schematic_compute.graph.GraphNode;
import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import io.github.y15173334444.create_schematic_compute.network.GraphDragStreamPacket;
import io.github.y15173334444.create_schematic_compute.network.GraphDragStreamPacket.Entry;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 拖动临场流跟踪器的无头测试：发送侧（100ms 节流、512 上限旋转续传、last-write-wins）与
 * 接收侧帧影（fresh 覆盖推进节点显示位、帧末还原图权威值、作用域过滤、静默过期、
 * 缺节点安全）。
 * Headless tests for the drag stream tracker: the send side (100 ms pacing, 512-cap rotating
 * continuation, last-write-wins) and the receive-side frame shadow (fresh overlays advance the
 * node's display position, graph truth restored at frame end, scope filtering, silence expiry,
 * missing-node safety).
 */
class GraphDragStreamTrackerTest {

    private static final UUID SENDER = UUID.fromString("00000000-0000-0000-0000-00000000beef");
    private static final Function<List<Entry>, GraphDragStreamPacket> FIXED_FACTORY =
        slice -> new GraphDragStreamPacket(BlockPos.ZERO, SENDER, -1, slice);

    // ───────────────────────── 发送侧 / send side ─────────────────────────

    @Test
    @DisplayName("首窗立即出站；未到 100ms 节流间隔不再发 / first window flushes at once; pacing suppresses the rest")
    void firstFlushImmediateThenThrottled() {
        long[] now = {1_000_000L};
        var sent = new ArrayList<GraphDragStreamPacket>();
        var tr = new GraphDragStreamTracker(() -> now[0], FIXED_FACTORY, sent::add);

        tr.submit(List.of(Entry.pos(1, 1f, 2f)));
        assertEquals(1, sent.size(), "first submit flushes immediately (bucket-born pacing)");
        tr.submit(List.of(Entry.pos(1, 3f, 4f)));
        assertEquals(1, sent.size(), "within the 100 ms window nothing more is sent");
        now[0] += GraphDragStreamTracker.STREAM_INTERVAL_MS;
        tr.submit(List.of(Entry.pos(1, 5f, 6f)));
        assertEquals(2, sent.size(), "after the window the latest value goes out");
        assertEquals(List.of(Entry.pos(1, 5f, 6f)), sent.get(1).entries(),
            "pending is wholesale-replaced — last-write-wins");
    }

    @Test
    @DisplayName("512 上限旋转续传：1024 条两窗覆盖全部节点并循环 / rotating continuation covers 1024 entries in two windows and cycles")
    void rotationCoversAllEntriesAcrossWindows() {
        long[] now = {1_000_000L};
        var sent = new ArrayList<GraphDragStreamPacket>();
        var tr = new GraphDragStreamTracker(() -> now[0], FIXED_FACTORY, sent::add);

        var big = new ArrayList<Entry>();
        for (int i = 0; i < 1024; i++) big.add(Entry.pos(i, i, i));
        tr.submit(big);
        assertEquals(1, sent.size(), "one window = at most one packet");
        assertEquals(512, sent.get(0).entries().size());
        for (int i = 0; i < 512; i++) assertEquals(i, sent.get(0).entries().get(i).nodeId(), "first half");
        now[0] += GraphDragStreamTracker.STREAM_INTERVAL_MS;
        tr.submit(big);
        assertEquals(2, sent.size());
        assertEquals(512, sent.get(1).entries().size());
        for (int i = 0; i < 512; i++) assertEquals(512 + i, sent.get(1).entries().get(i).nodeId(), "second half");
        now[0] += GraphDragStreamTracker.STREAM_INTERVAL_MS;
        tr.submit(big);
        assertEquals(3, sent.size());
        assertEquals(0, sent.get(2).entries().get(0).nodeId(), "third window wraps back to the head");
    }

    @Test
    @DisplayName("小集合一包带走全部条目 / a small set rides one packet whole")
    void smallSetRidesOnePacket() {
        long[] now = {1_000_000L};
        var sent = new ArrayList<GraphDragStreamPacket>();
        var tr = new GraphDragStreamTracker(() -> now[0], FIXED_FACTORY, sent::add);
        tr.submit(List.of(Entry.pos(1, 1f, 1f), Entry.pos(2, 2f, 2f), Entry.commentSize(3, 9f, 9f)));
        assertEquals(1, sent.size());
        assertEquals(3, sent.get(0).entries().size());
        now[0] += GraphDragStreamTracker.STREAM_INTERVAL_MS;
        tr.submit(List.of(Entry.pos(1, 1f, 1f), Entry.pos(2, 2f, 2f), Entry.commentSize(3, 9f, 9f)));
        assertEquals(2, sent.size());
        assertEquals(1, sent.get(1).entries().get(0).nodeId(), "no rotation skip below the cap");
    }

    @Test
    @DisplayName("空提交清空 pending（拖拽结束）/ an empty submission drops pending (drag over)")
    void emptySubmissionDropsPending() {
        long[] now = {1_000_000L};
        var sent = new ArrayList<GraphDragStreamPacket>();
        var tr = new GraphDragStreamTracker(() -> now[0], FIXED_FACTORY, sent::add);
        tr.submit(List.of(Entry.pos(1, 1f, 1f)));
        now[0] += GraphDragStreamTracker.STREAM_INTERVAL_MS / 2;
        tr.submit(List.of());                                  // drag ended before the window opened
        now[0] += GraphDragStreamTracker.STREAM_INTERVAL_MS;
        assertEquals(1, sent.size(), "no stale flush after the drag ends");
    }

    // ───────────────────────── 接收侧帧影 / receive-side frame shadow ─────────────────────────

    @Test
    @DisplayName("帧影推进显示位、帧末还原图权威值 / shadow advances the display position, truth restored at frame end")
    void shadowMovesAndRestores() {
        var g = new NodeGraph();
        GraphNode n = g.addNode(NodeType.TEXT, 10f, 20f);
        var tr = newTracker();

        tr.storeRemoteDrag(pkt(-1, Entry.pos(n.id, 100f, 200f)));
        tr.beginFrame(g, -1);
        assertTrue(n.x > 10f && n.x < 100f, "first frame steps toward the overlay target");
        assertTrue(n.y > 20f && n.y < 200f, "y steps too");
        tr.endFrame(g);
        assertEquals(10f, n.x, 0f, "graph truth restored");
        assertEquals(20f, n.y, 0f, "graph truth restored");
        assertFalse(tr.hasStash(), "stash cleared at frame end");
    }

    @Test
    @DisplayName("重复帧单调逼近目标并精确收敛 / repeated frames approach monotonically and converge exactly")
    void shadowConvergesToTarget() {
        var g = new NodeGraph();
        GraphNode n = g.addNode(NodeType.TEXT, 0f, 0f);
        var tr = newTracker();
        tr.storeRemoteDrag(pkt(-1, Entry.pos(n.id, 80f, 0f)));

        float prev = -1f;
        for (int i = 0; i < 10; i++) {
            tr.beginFrame(g, -1);
            assertTrue(n.x >= prev, "display position must advance monotonically toward the target");
            prev = n.x;
            tr.endFrame(g);
        }
        assertEquals(80f, prev, 0.0001f, "smoothstep converges exactly onto the target");
    }

    @Test
    @DisplayName("作用域不符的覆盖不画 / overlays from another scope are not applied")
    void otherScopeOverlayIgnored() {
        var g = new NodeGraph();
        GraphNode n = g.addNode(NodeType.TEXT, 7f, 8f);
        var tr = newTracker();
        tr.storeRemoteDrag(pkt(5, Entry.pos(n.id, 100f, 100f)));
        assertEquals(1, tr.overlayCount(), "the entry is stored (apply-time filtering)");
        tr.beginFrame(g, -1);
        assertEquals(7f, n.x, 0f, "scope mismatch → untouched");
        assertEquals(8f, n.y, 0f, "scope mismatch → untouched");
        tr.endFrame(g);
    }

    @Test
    @DisplayName("静默超过 500ms 覆盖失效回落图真值 / an overlay silent past 500 ms expires back to graph truth")
    void staleOverlayExpires() {
        long[] now = {1_000_000L};
        var g = new NodeGraph();
        GraphNode n = g.addNode(NodeType.TEXT, 5f, 6f);
        var tr = new GraphDragStreamTracker(() -> now[0], FIXED_FACTORY, p -> { });
        tr.storeRemoteDrag(pkt(-1, Entry.pos(n.id, 300f, 300f)));
        tr.beginFrame(g, -1);
        tr.endFrame(g);
        now[0] += GraphDragStreamTracker.OVERLAY_TIMEOUT_MS + 1;
        tr.beginFrame(g, -1);
        assertEquals(5f, n.x, 0f, "expired overlay → graph truth");
        assertEquals(6f, n.y, 0f, "expired overlay → graph truth");
        tr.endFrame(g);
        assertEquals(0, tr.overlayCount(), "stale entry swept");
    }

    @Test
    @DisplayName("注释尺寸条目推动宽高、帧末还原 / comment-size entries drive w/h and are restored")
    void commentSizeShadowAndRestore() {
        var g = new NodeGraph();
        GraphNode c = g.addNode(NodeType.COMMENT, 0f, 0f);
        float w0 = c.commentWidth, h0 = c.commentHeight;
        var tr = newTracker();
        tr.storeRemoteDrag(pkt(-1, Entry.commentSize(c.id, 400f, 300f)));
        tr.beginFrame(g, -1);
        assertTrue(c.commentWidth > w0 && c.commentWidth < 400f, "width steps toward the overlay");
        tr.endFrame(g);
        assertEquals(w0, c.commentWidth, 0f, "width restored to truth");
        assertEquals(h0, c.commentHeight, 0f, "height restored to truth");
    }

    @Test
    @DisplayName("图中不存在的节点条目安全跳过 / entries for nodes missing from the graph are skipped safely")
    void missingNodeEntryIsSkipped() {
        var g = new NodeGraph();
        g.addNode(NodeType.TEXT, 0f, 0f);
        var tr = newTracker();
        tr.storeRemoteDrag(pkt(-1, Entry.pos(9999, 50f, 50f)));
        tr.beginFrame(g, -1);
        tr.endFrame(g);
        assertFalse(tr.hasStash(), "nothing stashed for a missing node");
    }

    @Test
    @DisplayName("clear() 清空全部覆盖状态（编辑器关闭，帧间调用——帧末已还原）/ clear() wipes all overlay state (editor close, between frames — the frame has already restored)")
    void clearWipesEverything() {
        var g = new NodeGraph();
        GraphNode n = g.addNode(NodeType.TEXT, 0f, 0f);
        var tr = newTracker();
        tr.storeRemoteDrag(pkt(-1, Entry.pos(n.id, 50f, 50f)));
        tr.beginFrame(g, -1);
        assertTrue(n.x > 0f, "shadow active");
        tr.endFrame(g);
        assertEquals(0f, n.x, 0f, "restored before the close");
        tr.clear();
        assertEquals(0, tr.overlayCount(), "overlays gone");
        assertFalse(tr.hasStash(), "stash gone");
    }

    @Test
    @DisplayName("权威 MOVE 落地接管覆盖层目标：影子滑向终值而非冻结在流末位 / an authoritative MOVE adopts a fresh overlay's target")
    void authoritativeMoveAdoptedIntoOverlay() {
        var g = new NodeGraph();
        GraphNode n = g.addNode(NodeType.TEXT, 10f, 20f);
        var tr = newTracker();
        tr.storeRemoteDrag(pkt(-1, Entry.pos(n.id, 100f, 200f)));
        // 松手 op：权威终值 (300, 400)。 / The release op: authoritative final (300, 400).
        tr.adoptAuthoritative(io.github.y15173334444.create_schematic_compute.graph.GraphOp.moveNode(
            BlockPos.ZERO, -1, n.id, 300f, 400f, SENDER));

        float prevX = -1f, prevY = -1f;
        for (int i = 0; i < 12; i++) {
            tr.beginFrame(g, -1);
            assertTrue(n.x >= prevX, "display advances monotonically toward the adopted final");
            prevX = n.x;
            prevY = n.y;   // 帧内取值——endFrame 会还原图真值 / captured in-frame; endFrame restores truth
            tr.endFrame(g);
        }
        assertEquals(300f, prevX, 0.0001f, "shadow converges onto the authoritative final, not the last stream position");
        assertEquals(400f, prevY, 0.0001f, "y converges onto the authoritative final");
    }

    @Test
    @DisplayName("权威 SET_COMMENT_SIZE 同样接管 / an authoritative SET_COMMENT_SIZE adopts too")
    void authoritativeCommentSizeAdopted() {
        var g = new NodeGraph();
        GraphNode c = g.addNode(NodeType.COMMENT, 0f, 0f);
        var tr = newTracker();
        tr.storeRemoteDrag(pkt(-1, Entry.commentSize(c.id, 100f, 80f)));
        tr.adoptAuthoritative(io.github.y15173334444.create_schematic_compute.graph.GraphOp.setCommentSize(
            BlockPos.ZERO, -1, c.id, 400f, 300f, SENDER));
        float w = 0f, h = 0f;
        for (int i = 0; i < 12; i++) {
            tr.beginFrame(g, -1);
            w = c.commentWidth;
            h = c.commentHeight;   // 帧内取值 / captured in-frame
            tr.endFrame(g);
        }
        assertEquals(400f, w, 0.0001f, "width converges onto the authoritative final");
        assertEquals(300f, h, 0.0001f, "height converges onto the authoritative final");
    }

    @Test
    @DisplayName("跨作用域或无覆盖的 op 接管是空操作 / adoption is a no-op without a same-scope overlay")
    void adoptionWithoutOverlayIsNoOp() {
        var g = new NodeGraph();
        GraphNode n = g.addNode(NodeType.TEXT, 7f, 8f);
        var tr = newTracker();
        tr.adoptAuthoritative(io.github.y15173334444.create_schematic_compute.graph.GraphOp.moveNode(
            BlockPos.ZERO, -1, n.id, 500f, 500f, SENDER));
        tr.beginFrame(g, -1);
        assertEquals(7f, n.x, 0f, "no overlay entry → untouched");
        tr.endFrame(g);
    }

    @Test
    @DisplayName("保活条目构建：空闲 null；优先级照分发序（尺寸>注释框>多选>单节点）/ keep-alive entry builder: idle null, dispatch-ordered priority")
    void buildDragEntriesPriorityAndShape() {
        var g = new NodeGraph();
        GraphNode comment = g.addNode(NodeType.COMMENT, 0, 0);
        GraphNode a = g.addNode(NodeType.TEXT, 10, 10);
        GraphNode b = g.addNode(NodeType.TEXT, 20, 20);
        var contained = java.util.List.of(a);
        var pushed = java.util.List.of(b);
        var selection = java.util.List.of(a, comment);

        assertNull(GraphEditor.buildDragEntries(null, null, contained, pushed, false, selection),
            "idle drag → no entries");

        var nodeDrag = GraphEditor.buildDragEntries(null, a, contained, pushed, false, selection);
        assertEquals(1, nodeDrag.size(), "plain node drag = one position entry");
        assertEquals(Entry.pos(a.id, a.x, a.y), nodeDrag.get(0));

        var multi = GraphEditor.buildDragEntries(null, null, contained, pushed, true, selection);
        assertEquals(2, multi.size(), "multi drag = one entry per selected node");
        assertEquals(a.id, multi.get(0).nodeId());
        assertEquals(comment.id, multi.get(1).nodeId());

        var commentMove = GraphEditor.buildDragEntries(null, comment, contained, pushed, true, selection);
        assertEquals(3, commentMove.size(), "comment move carries the box + contained + pushed");
        assertEquals(comment.id, commentMove.get(0).nodeId());
        assertEquals(a.id, commentMove.get(1).nodeId());
        assertEquals(b.id, commentMove.get(2).nodeId());

        var resize = GraphEditor.buildDragEntries(comment, a, contained, pushed, true, selection);
        assertEquals(1, resize.size(), "resize outranks everything (dispatch order)");
        assertEquals(GraphDragStreamPacket.KIND_COMMENT_SIZE, resize.get(0).kind());
        assertEquals(comment.id, resize.get(0).nodeId());
    }

    @Test
    @DisplayName("显示器布局条目走 layout 影子：推进 layoutX/Y、帧末还原 / display-layout entries drive layoutX/Y via the shadow, restored at frame end")
    void displayLayoutShadowAndRestore() {
        var g = new NodeGraph();
        GraphNode n = g.addNode(NodeType.TEXT, 0, 0);
        n.layoutX = 0.2f; n.layoutY = 0.3f;
        var tr = newTracker();
        tr.storeRemoteDrag(pkt(-1, Entry.displayLayout(n.id, 0.8f, 0.9f)));
        tr.beginFrame(g, -1);
        assertTrue(n.layoutX > 0.2f && n.layoutX < 0.8f, "layout shadow advances toward the overlay");
        tr.endFrame(g);
        assertEquals(0.2f, n.layoutX, 0f, "layout truth restored");
        assertEquals(0.3f, n.layoutY, 0f, "layout truth restored");
        assertEquals(0f, n.x, 0f, "graph-space x untouched by a layout shadow");
    }

    @Test
    @DisplayName("权威 SET_DISPLAY_LAYOUT 落地接管覆盖层 / an authoritative SET_DISPLAY_LAYOUT adopts too")
    void authoritativeDisplayLayoutAdopted() {
        var g = new NodeGraph();
        GraphNode n = g.addNode(NodeType.TEXT, 0, 0);
        n.layoutX = 0.2f; n.layoutY = 0.2f;
        var tr = newTracker();
        tr.storeRemoteDrag(pkt(-1, Entry.displayLayout(n.id, 0.3f, 0.3f)));
        tr.adoptAuthoritative(io.github.y15173334444.create_schematic_compute.graph.GraphOp.setDisplayLayout(
            BlockPos.ZERO, -1, n.id, 0.9f, 0.9f, 1f, 0f, 1f, SENDER));
        float lx = 0f;
        for (int i = 0; i < 12; i++) {
            tr.beginFrame(g, -1);
            lx = n.layoutX;   // 帧内取值——endFrame 会还原 / captured in-frame; endFrame restores
            tr.endFrame(g);
        }
        assertEquals(0.9f, lx, 0.0001f, "converges onto the authoritative final");
    }

    // ── helpers ──

    private static GraphDragStreamTracker newTracker() {
        return new GraphDragStreamTracker(() -> 1_000_000L, FIXED_FACTORY, p -> { });
    }

    private static GraphDragStreamPacket pkt(int owner, Entry... es) {
        return new GraphDragStreamPacket(BlockPos.ZERO, SENDER, owner, List.of(es));
    }
}
