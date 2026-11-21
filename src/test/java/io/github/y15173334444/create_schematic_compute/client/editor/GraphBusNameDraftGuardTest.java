package io.github.y15173334444.create_schematic_compute.client.editor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * busBox 草稿基线门的回归锚（双探针实测 2026-11-21 的回弹乒乓）：`busNameUserDirty` 只
 * 回答「用户敲过键没有」，不追踪「权威名在草稿存活期间被挪动没有」——op 回声/收敛/另一端
 * 提交把权威名挪走后，本端的过期草稿照常过 #10 守卫被提交回去，两端 busBox 打乒乓（实测
 * 服务端 op 轨迹里旧名 '11'/'111'/'1' 反复回锅）。修法 = 草稿基线（draftBase）：提交门在
 * dirty 之外还要求权威名 == 基线，挪动即静默重载权威名并复位基线。
 * Regression anchors for the busBox draft-baseline gate (the bounce ping-pong measured by
 * the dual probes on 2026-11-21): `busNameUserDirty` only answers "did the user type", not
 * "did the authoritative name move while the draft was alive" — once an echo/convergence/
 * peer commit moved it, this client's stale draft sailed through the #10 gate and committed
 * the old name back, the two busBoxes ping-ponging (the server op trail shows '11'/'111'/'1'
 * repeatedly re-landed). Fix = the draft baseline (draftBase): the commit gate additionally
 * requires the authoritative name == the baseline; a move silently reloads the authoritative
 * name and resets the baseline.
 */
class GraphBusNameDraftGuardTest {

    @Test
    @DisplayName("draftBaseMoved：基线 null 放行（旧形态）；权威名挪动即过期；未挪动放行")
    void draftBaselineVerdicts() {
        assertFalse(GraphBusEditor.draftBaseMoved(null, "anything"),
            "no baseline = the legacy shape, allowed");
        assertFalse(GraphBusEditor.draftBaseMoved("abc", "abc"),
            "the authoritative name unmoved → the draft commits");
        assertTrue(GraphBusEditor.draftBaseMoved("11", "111111"),
            "an echo/peer commit moved the authoritative name → the draft is stale");
        assertFalse(GraphBusEditor.draftBaseMoved("abc", null),
            "an unreadable live name has nothing to compare against → allow (the caller guards node presence)");
    }

    @Test
    @DisplayName("源码锚：基线门在 commitBusBox 的活节点重取之后、#10 守卫之前；六个基线刷新点在位")
    void gateWiringAnchors() throws Exception {
        String b = Files.readString(Path.of(
            "src/main/java/io/github/y15173334444/create_schematic_compute/client/editor/GraphBusEditor.java"));
        int gate = b.indexOf("if (draftBaseMoved(st.busNameDraftBase, oldName)) {");
        assertTrue(gate > 0, "the draft-baseline gate exists in commitBusBox");
        int refetch = b.indexOf("g.findNode(st.busNode.id)");
        int dirtyGate = b.indexOf("shouldCommitBusName(st.busNameUserDirty", gate);
        assertTrue(refetch > 0 && gate > refetch && dirtyGate > gate,
            "the gate sits after the live re-fetch and before the #10 dirty gate");
        assertEquals(3, count(b, "st.busNameDraftBase = oldName;"),
            "the gate reload, the #10 reject snap-back and the box==name align all reset the baseline to the authoritative name");
        assertEquals(1, count(b, "st.busNameDraftBase = t;"),
            "the commit path rides the baseline to the new name");
        assertTrue(b.contains("if (t.equals(oldName)) {") && b.contains("st.busNameDraftBase = oldName; // 盒子已对齐权威名"),
            "the box==name early return aligns the baseline too");
        String f = Files.readString(Path.of(
            "src/main/java/io/github/y15173334444/create_schematic_compute/client/editor/NodeEditStateFactory.java"));
        assertTrue(f.contains("s.busNameDraftBase = node.signalName;"),
            "every panel creation seeds the baseline from the authoritative name");
    }

    private static int count(String hay, String needle) {
        int c = 0, i = 0;
        while ((i = hay.indexOf(needle, i)) >= 0) { c++; i += needle.length(); }
        return c;
    }
}
