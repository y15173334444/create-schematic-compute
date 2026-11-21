package io.github.y15173334444.create_schematic_compute.client.editor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 图实例整体替换后的编辑状态重置源码锚（实测 2026-11-21：全量同步替换图实例后，同 id
 * 签名值恒等 → 旧 EditState 被签名比对**保留**，其输入框/响应器/busNode 全挂在旧图的
 * 老节点对象上——后续编辑落死对象、显示与服务端权威态分歧、改名后回车提交回弹）。
 * GUI 渲染无法 headless 观测，走源码锚（PresenceOverlayLayerTest 同款手法）：钉住
 * `lastInitGraph != graph` 分支体内含 `editStateSignatures.clear()`——只清指纹不清 state，
 * createEditState 以旧 state 为 oldStRef，草稿/焦点保留路径不受影响。
 * Source anchor for the edit-state reset on wholesale graph-instance replacement (measured
 * live 2026-11-21: after a full sync replaced the graph instance, same-id signatures compared
 * equal so the old EditState was **kept** — its boxes/responders/busNode all hung off the OLD
 * graph's node objects; later edits landed on the dead object and the display diverged from
 * the authoritative state, bouncing Enter commits after a rename). GUI rendering is not
 * headless-observable, so the source-anchor convention applies: the `lastInitGraph != graph`
 * branch must contain `editStateSignatures.clear()` — fingerprints only, not the states, so
 * createEditState still consumes the old state as oldStRef and the draft/focus preservation
 * paths keep working.
 */
class GraphInstanceReplacementResetTest {

    private static final String EDITOR =
        "src/main/java/io/github/y15173334444/create_schematic_compute/client/editor/GraphEditor.java";

    @Test
    @DisplayName("图实例替换分支清指纹强制重建，但不清 state（草稿保留路径完好）")
    void replacementClearsSignaturesNotStates() throws Exception {
        String t = Files.readString(Path.of(EDITOR));
        int branch = t.indexOf("if (lastInitGraph != graph) {");
        assertTrue(branch > 0, "the graph-instance-replacement branch exists");
        int end = t.indexOf("} else if (lastInitGeneration != graph.graphGeneration)", branch);
        assertTrue(end > branch, "the branch closes with the generation compare");
        String body = t.substring(branch, end);
        assertTrue(body.contains("editStateSignatures.clear();"),
            "the replacement branch must clear the fingerprints (forces a rebuild past the equal-signature fast path)");
        assertFalse(body.contains("nodeEditStatesById.clear()"),
            "the states must survive so createEditState can preserve drafts and focus via oldStRef");
    }
}
