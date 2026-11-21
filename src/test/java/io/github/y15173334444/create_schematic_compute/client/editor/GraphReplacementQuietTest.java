package io.github.y15173334444.create_schematic_compute.client.editor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 全量同步的整图替换必须「安静」+ 提交判定按 id 重取活节点（双探针实测 2026-11-21 定性）：
 * 全量同步按 40 tick 合并窗周期性到达（SET_BANDS/改名路径的 requestFullSync，实测 2.0s 一轮），
 * 每次都整体替换图实例。清指纹强制重建让每次替换把全部展开面板齐闪一遍（「sig null → X」
 * 三连，实测）；不重建则 commit 判定会读到旧图的死 busNode（改名回弹，条目 41 实测）。
 * 两侧同时对锚：替换分支**不清指纹也不清 state**（内容相同的替换零重建），busNode 的提交
 * 判定一律按 id 重取活节点（{@code liveBusNode} / commitBusBox 的 {@code findNode}）——stale
 * 绑定在源头消灭，而不是靠全量重建兜底。
 * A wholesale graph sync must stay quiet, and commit decisions re-fetch the bus node by id
 * (dual-probe verdict, measured live 2026-11-21): full syncs arrive periodically on the
 * 40-tick coalescing window (the SET_BANDS/rename paths' requestFullSync, exactly 2.0s per
 * wave measured), each wholesale-replacing the graph instance. Clearing the fingerprints
 * flashed every expanded panel per replacement (the measured "sig null → X" triples); keeping
 * them made commit decisions read the dead old-graph busNode (rename bounces, measured as
 * register entry 41). Both sides are anchored: the replacement branch clears neither the
 * fingerprints nor the states (content-identical replacements rebuild nothing), and busNode
 * commit decisions always re-fetch the live node by id ({@code liveBusNode} / commitBusBox's
 * {@code findNode}) — stale bindings die at the source instead of relying on blanket rebuilds.
 */
class GraphReplacementQuietTest {

    private static final String EDITOR =
        "src/main/java/io/github/y15173334444/create_schematic_compute/client/editor/GraphEditor.java";
    private static final String BUS_EDITOR =
        "src/main/java/io/github/y15173334444/create_schematic_compute/client/editor/GraphBusEditor.java";

    @Test
    @DisplayName("替换分支不清指纹不清 state：内容相同的全量同步零重建")
    void replacementKeepsStatesAndFingerprints() throws Exception {
        String t = Files.readString(Path.of(EDITOR));
        int branch = t.indexOf("if (lastInitGraph != graph) {");
        assertTrue(branch > 0, "the graph-instance-replacement branch exists");
        int end = t.indexOf("} else if (lastInitGeneration != graph.graphGeneration)", branch);
        assertTrue(end > branch, "the branch closes with the generation compare");
        String body = t.substring(branch, end);
        assertFalse(body.contains("editStateSignatures.clear()"),
            "the replacement branch must not clear the fingerprints (the 40-tick full-sync cadence would flash every panel)");
        assertFalse(body.contains("nodeEditStatesById.clear()"),
            "the states must survive so content-identical replacements hit the signature fast path");
    }

    @Test
    @DisplayName("提交判定按 id 重取活节点：liveBusNode 助手 + commitBusBox findNode")
    void commitPathsRefetchTheLiveNode() throws Exception {
        String t = Files.readString(Path.of(EDITOR));
        assertTrue(t.contains("private GraphNode liveBusNode(EditState st)"),
            "the live re-fetch helper exists on the editor");
        assertTrue(t.contains("g.findNode(st.busNode.id)"),
            "the helper re-fetches by node id (never trusts the captured object)");
        // 提交判定五路全走活节点：差异检测 / 频段待同步 / 频段写回 / 防抖 / 失焦提交
        // All five commit faces ride the live node: diff detection, bandBoxesPending,
        // syncBandBoxes, the debounce, the focus-lost commit.
        assertEquals(5, count(t, "liveBusNode(st);"),
            "bandBoxesPending / syncBandBoxes / tickDebouncedBusEdits / focus-lost all use the live node");
        String b = Files.readString(Path.of(BUS_EDITOR));
        assertTrue(b.contains("g.findNode(st.busNode.id)"),
            "commitBusBox re-fetches the live node before comparing/writing signalName");
        assertTrue(b.contains("var node = (g != null) ? g.findNode(st.busNode.id) : null;"),
            "commitBusBox guards the graph being present");
    }

    private static int count(String hay, String needle) {
        int c = 0, i = 0;
        while ((i = hay.indexOf(needle, i)) >= 0) { c++; i += needle.length(); }
        return c;
    }
}
