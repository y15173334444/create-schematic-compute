package io.github.y15173334444.create_schematic_compute.graph;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 布尔时序族（LATCH / GATE / T_FLIPFLOP）缺席播种契约 + 「初始状态」退役批（1.3.0）的回归锚。
 * <ul>
 *   <li><b>播种源 = params[1] 状态镜像</b>：权威态在 runtimeState.flipflopStates（跨重编译
 *       save/restore 保真）；映射缺席时（新建节点首拍 / 已删节点撤销恢复 / 无 runtime 段的
 *       旧档）从 params[1] 播种——快照里 params[1] = 删除时刻状态，撤销/重做语义随之保真。</li>
 *   <li><b>params[0]（旧「初始状态」参数）退役</b>：服务端权威下编译不再重置时序态
 *       （applyCompileReset 已删），该参数无存活语义——求值不再读取，槽位保留仅为存档
 *       形状不变；编辑区「初始状态」行随之移除。</li>
 * </ul>
 * Regression anchors for the boolean-sequential (LATCH / GATE / T_FLIPFLOP) absent-state
 * seed contract and the "initial state" retirement batch (1.3.0): the seed is the params[1]
 * state mirror (so an undo-restore snapshot carries the state at removal — faithful
 * undo/redo), and the old params[0] "initial state" parameter is retired (never read;
 * slot kept for save-shape stability; the edit-panel row is gone along with the
 * save-time compile reset).
 */
class FlipflopStateSeedTest {

    private NodeGraph graph;
    private GraphEvaluator evaluator;
    private final Map<Integer, Float> pid = new HashMap<>();
    private final Map<Integer, Boolean> ffs = new HashMap<>();
    private final Map<Integer, Integer> pt = new HashMap<>();
    private final Map<Integer, ArrayDeque<Float>> dq = new HashMap<>();

    private GraphEvaluator.SeatInputState seat() {
        return new GraphEvaluator.SeatInputState(0b1, 1f, 2f, 3f, 4f);
    }

    private void setUpGraph() {
        graph = new NodeGraph();
        evaluator = new GraphEvaluator(graph);
        // 每情景全新运行时表：节点 id 各图各自从 1 起计，不清表会让下一情景同 id
        // 命中上一情景的旧状态条目（映射赢过播种，缺席前提被破坏）。
        // Fresh runtime tables per scenario: node ids restart at 1 per graph, so stale
        // entries would let the next scenario's same-id node read the previous state
        // (map beats seed, breaking the absent-state precondition).
        dq.clear(); ffs.clear(); pt.clear(); pid.clear();
    }

    private void tick() {
        evaluator.evaluate(List.of(), Map.of(), 0.05f, seat(), dq, ffs, pt);
    }

    @Test
    @DisplayName("LATCH 缺席播种读 params[1] 镜像、无视退役的 params[0]")
    void latchSeedsFromMirror() {
        // 两个情景各自全新运行时表——首拍求值即把播种态写进映射，缺席播种只在映射
        // 缺席的第一拍发生（这正是撤销恢复的形态：同 id 重加 + 快照镜像 + 空表）。
        // Each scenario gets a fresh runtime map — the first tick writes the seeded state
        // into the map, so absent-state seeding only happens on the first tick (exactly
        // the undo-restore shape: same-id re-add + snapshot mirror + empty map).
        setUpGraph();
        GraphNode latch = graph.addNode(NodeType.LATCH, 0, 0);
        latch.params[0] = 1f;   // 退役参数：必须被无视 / retired, must be ignored
        latch.params[1] = 0f;   // 镜像 = 复位 / mirror = reset
        tick();
        assertEquals(0, evaluator.getNodeOutput(latch.id, 0),
            "empty runtime map → seed from the params[1] mirror (reset), params[0] ignored");

        setUpGraph();
        GraphNode restored = graph.addNode(NodeType.LATCH, 0, 0);
        restored.params[0] = 0f;
        restored.params[1] = 1f;   // 镜像 = 置位（撤销恢复快照的形态）/ mirror set (undo-restore shape)
        tick();
        assertEquals(1, evaluator.getNodeOutput(restored.id, 0),
            "the mirror seeds the state — an undo-restored node comes back as it was at removal");
    }

    @Test
    @DisplayName("T_FLIPFLOP 缺席播种读 params[1] 镜像，无沿保持")
    void flipFlopSeedsFromMirror() {
        setUpGraph();
        GraphNode ff = graph.addNode(NodeType.T_FLIPFLOP, 0, 0);
        ff.params[0] = 1f;      // 退役参数 / retired
        ff.params[1] = 1f;
        tick();
        assertEquals(1, evaluator.getNodeOutput(ff.id, 0),
            "seeded on from the mirror despite the retired initial param being on too");
        // 无输入 → 无上升沿 → 保持播种态（输入恒 0 不翻转）；此拍读映射而非重播种。
        // No input → no rising edge → the seeded state holds; this tick reads the map,
        // it does not re-seed.
        tick();
        assertEquals(1, evaluator.getNodeOutput(ff.id, 0), "no edge → seed state holds");
    }

    @Test
    @DisplayName("GATE 缺席播种：镜像关则闸门输出 0，镜像开则放行 val")
    void gateSeedsFromMirror() {
        setUpGraph();
        GraphNode val = graph.addNode(NodeType.CONST, 0, 0);
        GraphNode gate = graph.addNode(NodeType.GATE, 100, 0);
        val.params[0] = 3f;
        graph.addConnection(val.id, 0, gate.id, 0);
        gate.params[0] = 0f;    // 退役参数 / retired
        gate.params[1] = 0f;    // 镜像 = 关 / mirror = closed
        tick();
        assertEquals(0, evaluator.getNodeOutput(gate.id, 0),
            "mirror closed → gate outputs 0 (open/close/tog inputs unconnected = 0)");

        setUpGraph();
        GraphNode val2 = graph.addNode(NodeType.CONST, 0, 0);
        GraphNode gate2 = graph.addNode(NodeType.GATE, 100, 0);
        val2.params[0] = 3f;
        graph.addConnection(val2.id, 0, gate2.id, 0);
        gate2.params[1] = 1f;   // 镜像 = 开（撤销恢复形态）/ mirror = open (undo-restore shape)
        tick();
        assertEquals(3, evaluator.getNodeOutput(gate2.id, 0),
            "mirror open → gate passes val through");
    }

    // ── 退役锁（源码锚）：applyCompileReset 与「初始状态」行禁重现 ──
    // Retirement locks (source anchors): the compile reset and the initial-state row
    // must never come back.

    @Test
    @DisplayName("退役锁：applyCompileReset 不复存在 / compile reset is gone")
    void compileResetRetired() throws Exception {
        String be = Files.readString(Path.of(
            "src/main/java/io/github/y15173334444/create_schematic_compute/blocks/GraphBlockEntity.java"));
        assertFalse(be.contains("applyCompileReset"), "the compile-reset method must stay retired");
        String save = Files.readString(Path.of(
            "src/main/java/io/github/y15173334444/create_schematic_compute/network/GraphSaveRequestPacket.java"));
        assertFalse(save.contains("applyCompileReset"),
            "the save path must not re-arm flip-flops (save = cycle check + persist + sync)");
        String editor = Files.readString(Path.of(
            "src/main/java/io/github/y15173334444/create_schematic_compute/client/editor/GraphEditor.java"));
        assertFalse(editor.contains("params[1] = params[0]"),
            "the editor's inline compile-reset copy must stay retired too");
    }

    @Test
    @DisplayName("编辑区高度 = 底 6 + 单行 16（旧行数 32 禁回潮）/ edit height = one 16px row")
    void editHeightCountsOneRow() {
        for (NodeType t : new NodeType[]{NodeType.LATCH, NodeType.T_FLIPFLOP, NodeType.GATE}) {
            NodeGraph g = new NodeGraph();
            GraphNode n = g.addNode(t, 0, 0);
            assertEquals(22, SpatialIndex.editHeight(n),
                t + " expanded edit area = base 6 + one 16px toggle row (a 32 here means the "
                    + "retired initial-state row came back and leaves a blank strip)");
        }
    }

    @Test
    @DisplayName("退役锁：编辑区「初始状态」行不复存在 / the initial-state row is gone")
    void initialStateRowRetired() throws Exception {
        String panel = Files.readString(Path.of(
            "src/main/java/io/github/y15173334444/create_schematic_compute/client/editor/EditPanel.java"));
        for (String dead : new String[]{"flipflop_default_on", "latch_default_set", "edit.default_open"})
            assertFalse(panel.contains(dead), "stale default-row lang key in EditPanel: " + dead);
        String editor = Files.readString(Path.of(
            "src/main/java/io/github/y15173334444/create_schematic_compute/client/editor/GraphEditor.java"));
        for (String dead : new String[]{"gateLocalY", "ffLocalY", "latchLocalY"})
            assertFalse(editor.contains(dead), "removed default-row click branch leftover: " + dead);
    }

    @Test
    @DisplayName("退役锁：求值缺席播种不再读 params[0] / the seed never reads params[0]")
    void seedNeverReadsParam0() throws Exception {
        String src = Files.readString(Path.of(
            "src/main/java/io/github/y15173334444/create_schematic_compute/graph/TimingEval.java"));
        assertFalse(src.contains("node.params[0] > 0.5f"),
            "the absent-state seed must read the params[1] mirror, never params[0]");
    }

    @Test
    @DisplayName("退役锁：lang 死键清空 / dead lang keys cleared")
    void deadLangKeysRetired() throws Exception {
        for (String loc : new String[]{"zh_cn", "en_us"}) {
            String lang = Files.readString(Path.of(
                "src/main/resources/assets/create_schematic_compute/lang/" + loc + ".json"));
            for (String dead : new String[]{"flipflop_default_on", "gate_init_open",
                                            "latch_default_set", "edit.default_open"})
                assertFalse(lang.contains("\"gui.create_schematic_compute.edit." + dead.replace("edit.", "") + "\""),
                    loc + " still carries the dead key: " + dead);
        }
    }

    @Test
    @DisplayName("悬停帧锁：renderAt 两调用点必须传节点局部鼠标 / hover frame lock")
    void hoverMouseIsLocalAtBothCallSites() throws Exception {
        // renderAt 的悬停矩形全是节点局部坐标——调用方必须先做 (mx-sx)/zoom（与点击侧同口径）。
        // 屏幕坐标直传会让悬停高亮在「屏幕数值 = 局部矩形」的空白区点亮（感知位置错误）。
        // renderAt's hover rects are node-local — both call sites must transform with
        // (mx-sx)/zoom first (the click side's convention); raw screen coords light the
        // hover outline wherever the screen position numerically matches the local rect.
        String nr = Files.readString(Path.of(
            "src/main/java/io/github/y15173334444/create_schematic_compute/client/editor/NodeRenderer.java"));
        assertTrue(nr.contains("(mx - sx) / zoom"), "drawNode must transform the mouse to node-local for renderAt");
        assertFalse(nr.contains("renderAt(g, 0, editLocalY, nodeW, n, editSt, zoom, mx, my,"),
            "drawNode must not pass raw screen mouse into renderAt");
        String cr = Files.readString(Path.of(
            "src/main/java/io/github/y15173334444/create_schematic_compute/client/editor/NodeCommentRenderer.java"));
        assertTrue(cr.contains("(mx - sx) / zoom"), "the comment call site must transform too");
        assertFalse(cr.contains("n, editSt, zoom, mx, my, null"),
            "the comment call site must not pass raw screen mouse into renderAt");
    }
}
