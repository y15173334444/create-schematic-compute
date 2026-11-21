package io.github.y15173334444.create_schematic_compute.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 控制台逻辑核心（{@link GraphConsole.Core}）的 headless 锚——纯 JDK，零 MC 类。
 *
 * <p>第十二轮起控制台住在侧栏「终端」面板：{@code /} 开台键与模态叠加层退役，核心改为
 * <b>聚焦态</b>语义。覆盖：</p>
 * <ul>
 * <li>聚焦/失焦：{@code focus()} 每次由失焦转聚焦触发一次 {@code onFocus}（清其它输入框
 * 焦点）；Esc 只失焦并消费；失焦态按键与字符全部放行。</li>
 * <li>聚焦态吞掉全部按键（含未知键）——聚焦输入框优先于编辑器快捷键与 Screen 的 ESC 关屏。</li>
 * <li>首次聚焦只出一次 help 提示。</li>
 * <li>命令提交（客户端零文法）：trim、空行拒绝、原样发送（不解释命令名）、本地回显、
 * 响应回填。</li>
 * <li>命令行编辑（光标 / Home/End / Backspace/Delete / 长度上限）与历史导航（草稿保存）。</li>
 * <li>输出环形缓冲上界。</li>
 * </ul>
 *
 * <p>渲染层（GuiGraphics / 侧栏面板）不测——GUI headless 禁区。</p>
 *
 * <p>Headless anchors for the console logic core ({@link GraphConsole.Core}) — pure JDK, zero
 * MC classes. Since round 12 the console lives in the sidebar terminal panel: the "/" opener
 * and the modal overlay are retired and the core is <b>focus-driven</b>. Covers the focus
 * transitions (one {@code onFocus} per blur→focus transition, Esc blurs and consumes, blurred
 * keys/chars pass through), the focused state swallowing every key (a focused input beats
 * editor shortcuts and Screen's ESC close), the one-shot help hint, the zero-grammar submit
 * face (trim / blank rejection / verbatim send / local echo / response backfill), line editing
 * and history, and the output ring. The render layer (GuiGraphics / the sidebar panel) is
 * untested — the GUI headless exclusion zone.</p>
 */
class GraphConsoleCoreTest {

    private static final int ESC = 256, ENTER = 257, BACKSPACE = 259, DELETE = 261;
    private static final int LEFT = 263, RIGHT = 262, UP = 265, DOWN = 264, HOME = 268, END = 269;

    // Wiring 是 record——可记录的二元组（onFocus 计数 / 发送收集）用字段 + lambda 组装。
    // Wiring is a record — the recording pair (an onFocus counter / a send collector) is
    // assembled from fields + lambdas.
    private int focusCount = 0;
    private final List<String> sent = new ArrayList<>();

    private GraphConsole.Core newCore() {
        return new GraphConsole.Core(new GraphConsole.Wiring(
            () -> focusCount++,
            line -> sent.add(line)));
    }

    private String outputTail(GraphConsole.Core core) {
        List<String> out = core.outputSnapshot();
        return out.isEmpty() ? "" : out.get(out.size() - 1);
    }

    // ── 1. 聚焦态 / the focus state ────────────────────────────────────────────────

    @Test
    @DisplayName("失焦态：按键与字符一律放行（键属于下方守卫链）")
    void blurredCorePassesEverythingThrough() {
        GraphConsole.Core c = newCore();
        assertFalse(c.isFocused());
        assertFalse(c.keyPressed(ESC, 0, 0), "失焦 ESC 不归控制台 / ESC is not the console's while blurred");
        assertFalse(c.keyPressed(ENTER, 0, 0), "失焦 Enter 不提交 / Enter does not submit while blurred");
        assertFalse(c.keyPressed(65, 0, 0));
        assertFalse(c.charTyped('a', 0), "失焦字符放行 / characters pass through while blurred");
        assertEquals("", c.inputLine());
        assertEquals(0, sent.size());
        assertEquals(0, focusCount, "失焦态不触发 onFocus / no onFocus while blurred");
    }

    @Test
    @DisplayName("聚焦：每次失焦→聚焦触发一次 onFocus（清焦），重复 focus 不重触发")
    void focusFiresOnFocusOncePerTransition() {
        GraphConsole.Core c = newCore();
        c.focus();
        assertTrue(c.isFocused());
        assertEquals(1, focusCount, "首次聚焦触发 onFocus / the first focus fires onFocus");
        c.focus();
        assertEquals(1, focusCount, "已在聚焦态不重触发 / refocus while focused is a no-op");
        c.keyPressed(ESC, 0, 0);
        assertFalse(c.isFocused(), "ESC 只失焦 / ESC only blurs");
        c.focus();
        assertEquals(2, focusCount, "再次失焦→聚焦再触发一次 / each blur→focus transition fires again");
    }

    @Test
    @DisplayName("首次聚焦只出一次 help 提示（失焦再聚焦不重复）")
    void firstFocusShowsHelpHintOnce() {
        GraphConsole.Core c = newCore();
        c.focus();
        assertTrue(outputTail(c).contains("help"), "首开提示 / the first-open hint");
        int lines = c.outputSnapshot().size();
        c.keyPressed(ESC, 0, 0);
        c.focus();
        assertEquals(lines, c.outputSnapshot().size(), "提示只出一次 / the hint is one-shot");
    }

    @Test
    @DisplayName("聚焦态：吞掉全部按键（含未知键 / F 键），编辑器快捷键与 ESC 关屏都进不来")
    void focusedCoreSwallowsEveryKey() {
        GraphConsole.Core c = newCore();
        c.focus();
        for (int key : new int[]{65, 32, BACKSPACE, DELETE, LEFT, RIGHT, UP, DOWN, HOME, END, 258, 290, 298}) {
            assertTrue(c.keyPressed(key, 0, 0), "聚焦态全部消费 / consumed while focused: " + key);
        }
        assertTrue(c.isFocused(), "非 ESC 键不得失焦 / non-ESC keys must not blur");
        assertEquals(0, sent.size(), "聚焦态不得误发查询 / no accidental sends while focused");
    }

    @Test
    @DisplayName("关焦再开：输入行草稿保留（照聊天草稿语义）")
    void inputDraftSurvivesBlurRefocus() {
        GraphConsole.Core c = newCore();
        c.focus();
        c.charTyped('h', 0);
        c.charTyped('i', 0);
        c.keyPressed(ESC, 0, 0);
        assertFalse(c.isFocused());
        c.focus();
        assertEquals("hi", c.inputLine(), "草稿保留 / the draft survives");
    }

    @Test
    @DisplayName("聚焦态可打印字符插入、控制字符忽略")
    void focusedCoreInsertsPrintableChars() {
        GraphConsole.Core c = newCore();
        c.focus();
        assertTrue(c.charTyped('h', 0));
        c.charTyped('\n', 0); // 控制字符不插入 / control chars are not inserted
        c.charTyped('\u007f', 0);
        assertEquals("h", c.inputLine());
    }

    // ── 2. 命令提交（客户端零文法） / submit (the zero-grammar client face) ─────────

    @Test
    @DisplayName("提交：trim + 原样发送（不解释命令名）+ 本地回显 + 等待标记")
    void submitTrimsAndSendsVerbatim() {
        GraphConsole.Core c = newCore();
        c.focus();
        type(c, "  help ");
        c.keyPressed(ENTER, 0, 0);
        assertEquals(List.of("help"), sent, "trim + 原样发送 / trimmed and verbatim");
        assertEquals("", c.inputLine(), "提交后清行 / the line clears after submit");
        assertTrue(outputTail(c).startsWith("> help"), "本地回显 / local echo");
        assertTrue(c.isAwaitingResponse(), "等待服务端响应 / awaiting the server response");
        assertTrue(c.keyPressed(ESC, 0, 0));
        assertFalse(c.isFocused(), "提交不改变焦点 / submit does not change focus");
    }

    @Test
    @DisplayName("空行（纯空白）不发送、不入历史、不回显")
    void blankLineIsRejected() {
        GraphConsole.Core c = newCore();
        c.focus();
        type(c, "   ");
        c.keyPressed(ENTER, 0, 0);
        assertEquals(0, sent.size());
        assertFalse(c.isAwaitingResponse());
    }

    @Test
    @DisplayName("响应回填：追加行并解除等待标记（行内容原样，不解析）")
    void serverLinesBackfillAndClearAwaiting() {
        GraphConsole.Core c = newCore();
        c.focus();
        type(c, "help");
        c.keyPressed(ENTER, 0, 0);
        c.receiveServerLines(List.of("Console commands / 控制台命令:", "  help   本表 / this table"));
        assertFalse(c.isAwaitingResponse());
        List<String> out = c.outputSnapshot();
        assertEquals("  help   本表 / this table", out.get(out.size() - 1), "原样展示 / verbatim display");
        c.receiveServerLines(null);
        assertFalse(c.isAwaitingResponse(), "null 容忍（防御） / null tolerated (defensive)");
    }

    // ── 3. 命令行编辑 / line editing ────────────────────────────────────────────────

    @Test
    @DisplayName("光标编辑：LEFT/RIGHT 插入、HOME/END、BACKSPACE/DELETE")
    void caretEditing() {
        GraphConsole.Core c = newCore();
        c.focus();
        for (char ch : "abc".toCharArray()) c.charTyped(ch, 0);
        assertEquals("abc", c.inputLine());
        assertEquals(3, c.caretIndex());
        c.keyPressed(LEFT, 0, 0);
        c.charTyped('X', 0);
        assertEquals("abXc", c.inputLine(), "光标处插入 / insert at caret");
        c.keyPressed(HOME, 0, 0);
        c.charTyped('S', 0);
        assertEquals("SabXc", c.inputLine());
        c.keyPressed(END, 0, 0);
        c.keyPressed(BACKSPACE, 0, 0);
        assertEquals("SabX", c.inputLine());
        c.keyPressed(BACKSPACE, 0, 0);
        c.keyPressed(BACKSPACE, 0, 0);
        c.keyPressed(BACKSPACE, 0, 0);
        c.keyPressed(BACKSPACE, 0, 0); // 空行继续退格为 no-op / extra backspaces are no-ops
        assertEquals("", c.inputLine());
        c.charTyped('d', 0);
        c.keyPressed(HOME, 0, 0);
        c.keyPressed(DELETE, 0, 0);
        assertEquals("", c.inputLine(), "HOME 后 DELETE 删尾向字符 / DELETE removes forward at HOME");
        c.keyPressed(DELETE, 0, 0);
        assertEquals("", c.inputLine(), "空行 DELETE 为 no-op / DELETE on an empty line is a no-op");
    }

    @Test
    @DisplayName("输入行长度上限（与原版聊天一致；服务端仍复核）")
    void inputLineIsCapped() {
        GraphConsole.Core c = newCore();
        c.focus();
        for (int i = 0; i < GraphConsole.Core.MAX_INPUT + 20; i++) c.charTyped('a', 0);
        assertEquals(GraphConsole.Core.MAX_INPUT, c.inputLine().length(), "超限字符被忽略 / over-cap chars ignored");
    }

    // ── 4. 历史导航 / history navigation ────────────────────────────────────────────

    @Test
    @DisplayName("↑/↓ 历史导航：进入历史存草稿、回到活位恢复草稿、首尾钳制")
    void historyNavigationPreservesDraft() {
        GraphConsole.Core c = newCore();
        c.focus();
        type(c, "help");
        c.keyPressed(ENTER, 0, 0);
        type(c, "hosts");
        c.keyPressed(ENTER, 0, 0);
        type(c, "draft");
        c.keyPressed(UP, 0, 0);
        assertEquals("hosts", c.inputLine(), "↑ 最近一条 / UP = newest first");
        c.keyPressed(UP, 0, 0);
        assertEquals("help", c.inputLine());
        c.keyPressed(UP, 0, 0);
        assertEquals("help", c.inputLine(), "首端钳制 / clamped at the oldest");
        c.keyPressed(DOWN, 0, 0);
        assertEquals("hosts", c.inputLine());
        c.keyPressed(DOWN, 0, 0);
        assertEquals("draft", c.inputLine(), "回到活位恢复草稿 / the live draft is restored");
        c.keyPressed(DOWN, 0, 0);
        assertEquals("draft", c.inputLine(), "尾端钳制 / clamped at the live position");
    }

    @Test
    @DisplayName("空历史时 ↑/↓ 为 no-op；提交后游标回活位")
    void emptyHistoryIsANoopAndSubmitResetsCursor() {
        GraphConsole.Core c = newCore();
        c.focus();
        c.keyPressed(UP, 0, 0);
        c.keyPressed(DOWN, 0, 0);
        assertEquals("", c.inputLine());
        type(c, "help");
        c.keyPressed(ENTER, 0, 0);
        c.keyPressed(UP, 0, 0);
        assertEquals("help", c.inputLine(), "提交后的历史可回溯 / history is browsable after submit");
    }

    // ── 5. 输出环形缓冲 / the output ring ───────────────────────────────────────────

    @Test
    @DisplayName("输出环形缓冲：超限丢最旧行（前缀保留语义）")
    void outputRingKeepsTheNewest() {
        GraphConsole.Core c = newCore();
        List<String> big = new ArrayList<>();
        for (int i = 0; i < GraphConsole.Core.MAX_OUTPUT_LINES + 10; i++) big.add("line-" + i);
        c.receiveServerLines(big);
        List<String> out = c.outputSnapshot();
        assertEquals(GraphConsole.Core.MAX_OUTPUT_LINES, out.size(), "容量钳制 / capacity clamp");
        assertEquals("line-10", out.get(0), "最旧 10 行被丢弃 / the oldest 10 lines are dropped");
        assertEquals("line-" + (GraphConsole.Core.MAX_OUTPUT_LINES + 9), out.get(out.size() - 1));
    }

    private static void type(GraphConsole.Core c, String s) {
        for (char ch : s.toCharArray()) c.charTyped(ch, 0);
    }
}
