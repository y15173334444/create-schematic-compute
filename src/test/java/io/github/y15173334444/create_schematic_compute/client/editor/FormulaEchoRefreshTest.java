package io.github.y15173334444.create_schematic_compute.client.editor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * known-open-issues 条目『打字途中面板重建（补全弹窗被吞 · 输入框每击键换血）』的三处一批
 * 源码锚（GUI 绘制/焦点语义无法 headless 观测，走源码锚——PresenceOverlayLayerTest 同款
 * 手法）。三处缺一无效（登记册 ⚠️：只删指纹行 = 远端回声通道照旧每键重建 + 多人新回归）：
 * <ol>
 *   <li>指纹剔除 formula.hashCode——公式文本是脚本框的「值」，结构效应已由
 *       inputs()/outputs()/bandCount 覆盖，文本进指纹即每击键换血；</li>
 *   <li>GraphRemoteApplier 的 SET_FORMULA 改定点刷新——不再落「整 EditState 重建」分支；</li>
 *   <li>草稿策略（2026-11-21 拍板 = 跳过保草稿）——本端聚焦时不覆盖；定点 setValue 必须
 *       抑制 MLE 响应器回声（suppressEditBoxResponder），否则回环重发 op。</li>
 * </ol>
 * Source anchors for register entry "mid-typing panel rebuild" (its three-part batch; GUI
 * paint/focus semantics are not headless-observable, so the source-anchor convention applies —
 * the PresenceOverlayLayerTest playbook). All three are required (the register's ⚠️: removing
 * only the fingerprint line leaves the remote-echo channel rebuilding per keystroke and adds a
 * multiplayer regression):
 * (1) the fingerprint drops formula.hashCode — the formula text is the script box's "value",
 * its structural effect already rides inputs()/outputs()/bandCount; (2) the applier refreshes
 * SET_FORMULA in place instead of falling into the whole-EditState rebuild; (3) draft policy
 * (ruled 2026-11-21 = skip and keep the draft) — a focused local box is never overwritten, and
 * the targeted setValue must suppress the MLE responder echo (suppressEditBoxResponder) or the
 * op re-sends in a loop.
 */
class FormulaEchoRefreshTest {

    private static final String EDITOR =
        "src/main/java/io/github/y15173334444/create_schematic_compute/client/editor/GraphEditor.java";
    private static final String APPLIER =
        "src/main/java/io/github/y15173334444/create_schematic_compute/client/editor/GraphRemoteApplier.java";
    private static final String FACTORY =
        "src/main/java/io/github/y15173334444/create_schematic_compute/client/editor/NodeEditStateFactory.java";

    @Test
    @DisplayName("指纹剔公式文本：signature 不再编 formula.hashCode，契约注记在位")
    void fingerprintDropsFormulaText() throws Exception {
        String t = Files.readString(Path.of(EDITOR));
        int sig = t.indexOf("private int editStateSignature(GraphNode n)");
        assertTrue(sig > 0, "editStateSignature exists");
        int end = t.indexOf("\n    }", sig);
        String body = t.substring(sig, end);
        assertFalse(body.contains("n.formula.hashCode()"),
            "the formula text must not ride the structural fingerprint (per-keystroke rebuilds)");
        assertTrue(body.contains("n.inputs()") && body.contains("n.outputs()") && body.contains("n.bandCount()"),
            "the structural effect of formula text stays covered by the pin-face fields");
        // 契约注记与「参数数值不进指纹」同段（同款例外逻辑）
        // The contract note rides the same paragraph as the param-value exception.
        assertTrue(t.contains("公式文本") || t.contains("formula text"),
            "the fingerprint javadoc records the formula-text exception alongside the param-value one");
    }

    @Test
    @DisplayName("回声定点刷新：SET_FORMULA 独立分支带聚焦守卫与回声抑制，先于整框重建")
    void applierRefreshesFormulaInPlace() throws Exception {
        String t = Files.readString(Path.of(APPLIER));
        // 「SET_FORMULA) {」唯一命中新分支——条件链里既有的 SET_FORMULA（|| 串联）不带此尾
        // The "SET_FORMULA) {" tail uniquely matches the new branch — the pre-existing
        // SET_FORMULA inside the || chain lacks it.
        int branch = t.indexOf("OpType.SET_FORMULA) {");
        assertTrue(branch > 0, "the SET_FORMULA targeted-refresh branch exists");
        // 分支体内：聚焦守卫（跳过保草稿）+ 回声抑制 + 不再整框重建
        // Inside the branch: the focus guard (skip, keep the draft) + echo suppression +
        // no whole-EditState rebuild.
        int end = t.indexOf("\n            } else if", branch);
        assertTrue(end > 0, "the branch closes with an else-if chain");
        String body = t.substring(branch, end);
        assertTrue(body.contains("MultiLineEditBox"), "the refresh targets the script editor box");
        assertTrue(body.contains("!mle.isFocused()") || body.contains("isFocused()"),
            "the draft policy guards on local focus");
        assertTrue(body.contains("suppressEditBoxResponder"),
            "the targeted setValue suppresses the responder echo");
        assertFalse(body.contains("NodeEditStateFactory.create("),
            "the targeted branch must not rebuild the EditState");
    }

    @Test
    @DisplayName("响应器回声抑制：公式 MLE 响应器顶部带 suppress 守卫")
    void formulaResponderSuppressible() throws Exception {
        String t = Files.readString(Path.of(FACTORY));
        // 从公式框的锚（formulaNodeId 捕获）向后找 setResponder——首个 mle.setResponder
        // 是 TEXT/DATA 节点的框，不是公式框
        // Anchor from the formula box's marker (the formulaNodeId capture) — the first
        // mle.setResponder belongs to the TEXT/DATA box, not the formula one.
        int marker = t.indexOf("final int formulaNodeId = node.id;");
        assertTrue(marker > 0, "the formula factory section exists");
        int responder = t.indexOf("mle.setResponder(t -> {", marker);
        assertTrue(responder > 0, "the formula MLE responder exists");
        int end = t.indexOf("});", responder);
        String body = t.substring(responder, end);
        assertTrue(body.contains("if (ed.suppressEditBoxResponder) return;"),
            "the formula responder honours the suppression flag (same as the EditBox responders)");
    }

    @Test
    @DisplayName("徽章面不回潮：hasErrors 可达 applier（包内可见，校验单源）")
    void badgeFaceReachable() throws Exception {
        String t = Files.readString(Path.of(FACTORY));
        assertTrue(t.contains("static boolean hasErrors("),
            "hasErrors widens to package-private (the applier's badge refresh reuses the one source)");
        assertFalse(t.contains("private static boolean hasErrors("),
            "hasErrors must not be private again");
    }
}
