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
 * <p>覆盖规格 §4.2 的输入守卫语义与 §4.1 的开关状态机：</p>
 * <ul>
 * <li>{@code /} 开关状态机：关闭态仅 {@code /} 且无文本输入面激活时开台；其余键放行。</li>
 * <li>守卫链链首语义（不引 GUI 类的部分）：打开态模态吞掉全部按键——<b>ESC 只关控制台</b>
 * （返回 true 使编辑器守卫链短路，绝不会落到 Screen 默认 onClose）；关闭态 charTyped 放行。</li>
 * <li>开键字符吞没（menuOpenedByKey 同款纪律）：开台后紧随的字符被吃掉；后续按键事件
 * 作废标志。</li>
 * <li>命令提交（客户端解析面，规格 §4.4）：trim、空行拒绝、原样发送（不解释命令名）、
 * 本地回显、响应回填。</li>
 * <li>命令行编辑（光标 / Home/End / Backspace/Delete / 长度上限）与历史导航（草稿保存）。</li>
 * <li>输出环形缓冲上界。</li>
 * </ul>
 *
 * <p>渲染层（GuiGraphics）不测——GUI headless 禁区；外层 GraphConsole 壳与本测试无关。</p>
 *
 * <p>Headless anchors for the console logic core ({@link GraphConsole.Core}) — pure JDK,
 * zero MC classes. Covers the §4.2 input-guard semantics and the §4.1 toggle state machine:
 * the "/" gate, the chain-head semantics (the MC-free parts — while open ESC returns true
 * so the editor chain short-circuits and Screen's default onClose is never reached), the
 * opening-char swallow (the menuOpenedByKey discipline), the client-side submit face (trim /
 * blank rejection / verbatim send, spec §4.4), line editing + history, and the output ring.
 * The render layer (GuiGraphics) is untested — the GUI headless exclusion zone.</p>
 */
class GraphConsoleCoreTest {

    /** 开台键 = {@code /}（GLFW_KEY_SLASH）。 / The toggle key (GLFW_KEY_SLASH). */
    private static final int SLASH = GraphConsole.Core.TOGGLE_KEY;
    private static final int ESC = 256, ENTER = 257, BACKSPACE = 259, DELETE = 261;
    private static final int LEFT = 263, RIGHT = 262, UP = 265, DOWN = 264, HOME = 268, END = 269;

    // Wiring 是 record——可记录的三元组（可切换门 / onOpen 计数 / 发送收集）用字段 + lambda 组装。
    // Wiring is a record — the recording triple (a switchable gate / an onOpen counter / a
    // send collector) is assembled from fields + lambdas.
    private boolean gate = false;
    private int openCount = 0;
    private final List<String> sent = new ArrayList<>();

    private GraphConsole.Core newCore() {
        return new GraphConsole.Core(new GraphConsole.Wiring(
            () -> gate,
            () -> openCount++,
            line -> sent.add(line)));
    }

    private String outputTail(GraphConsole.Core core) {
        List<String> out = core.outputSnapshot();
        return out.isEmpty() ? "" : out.get(out.size() - 1);
    }

    // ── 1. `/` 开关状态机 / the "/" toggle state machine ─────────────────────────────

    @Test
    @DisplayName("关闭态：仅 `/` 开台（返回 true），其余键放行（返回 false）")
    void closedStateSlashTogglesOtherKeysPassThrough() {
        GraphConsole.Core c = newCore();
        assertFalse(c.isOpen());
        assertTrue(c.keyPressed(SLASH, 0, 0), "开台必须消费 `/` / the toggle must consume \"/\"");
        assertTrue(c.isOpen());
        assertEquals(1, openCount, "onOpen（清焦）恰好一次 / onOpen (focus clear) fires exactly once");

        GraphConsole.Core c2 = newCore();
        assertFalse(c2.keyPressed(65, 0, 0), "关闭态其余键放行 / other keys pass through while closed");
        assertFalse(c2.keyPressed(ESC, 0, 0), "关闭态 ESC 不归控制台 / ESC is not the console's while closed");
        assertFalse(c2.isOpen());
    }

    @Test
    @DisplayName("关闭态：文本输入面激活时 `/` 不抢键（规格 §4.2 与守卫链既有约定）")
    void closedStateSlashYieldsToTextEntrySurfaces() {
        gate = true; // 菜单搜索框 / 节点编辑框等激活 / a menu search box or node box is active
        GraphConsole.Core c = newCore();
        assertFalse(c.keyPressed(SLASH, 0, 0), "文本输入面激活时 `/` 放行 / \"/\" passes through when a text surface is active");
        assertFalse(c.isOpen());
        gate = false;
        assertTrue(c.keyPressed(SLASH, 0, 0));
        assertTrue(c.isOpen());
    }

    @Test
    @DisplayName("打开态：模态吞掉全部按键，ESC 只关控制台（链首拦截，不落 Screen onClose）")
    void openStateIsModalAndEscClosesOnlyTheConsole() {
        GraphConsole.Core c = newCore();
        c.keyPressed(SLASH, 0, 0);
        // ESC 拦截优先级：返回 true = 编辑器守卫链短路，AbstractGraphScreen 的 onClose 分支不可达
        // ESC priority: returning true short-circuits the editor chain — Screen.onClose is unreachable
        assertTrue(c.keyPressed(ESC, 0, 0));
        assertFalse(c.isOpen(), "ESC 只关控制台 / ESC closes only the console");
        assertEquals(1, openCount, "关闭不重触发 onOpen / closing never re-fires onOpen");

        c.keyPressed(SLASH, 0, 0);
        for (int key : new int[]{65, BACKSPACE, DELETE, LEFT, RIGHT, UP, DOWN, HOME, END, 32, 258}) {
            assertTrue(c.keyPressed(key, 0, 0), "打开态全部消费（含未知键）/" + key);
        }
        assertTrue(c.isOpen(), "非 ESC 键不得关台 / non-ESC keys must not close");
        assertEquals(0, sent.size(), "打开态不得误发查询 / no accidental sends while open");
    }

    @Test
    @DisplayName("关台再开：输入行草稿保留（照聊天草稿语义）")
    void inputDraftSurvivesCloseReopen() {
        GraphConsole.Core c = newCore();
        c.keyPressed(SLASH, 0, 0);
        c.charTyped('h', 0);
        c.charTyped('i', 0);
        c.keyPressed(ESC, 0, 0);
        assertFalse(c.isOpen());
        c.keyPressed(SLASH, 0, 0);
        assertEquals("hi", c.inputLine(), "草稿保留 / the draft survives");
    }

    // ── 2. 开键字符吞没 / the opening-char swallow ──────────────────────────────────

    @Test
    @DisplayName("开台键自身的字符被吞没：`/` 开台后紧随的 charTyped 不进输入行")
    void openingCharIsSwallowed() {
        GraphConsole.Core c = newCore();
        c.keyPressed(SLASH, 0, 0);
        assertTrue(c.charTyped('/', 0), "开台后的本次 charTyped 被消费 / the char following the open is consumed");
        assertEquals("", c.inputLine(), "开键字符不清进输入行 / the opening char is not inserted");
        c.charTyped('h', 0);
        c.charTyped('e', 0);
        assertEquals("he", c.inputLine());
    }

    @Test
    @DisplayName("吞没标志只对紧随的 charTyped 有效：后续按键事件作废（menuOpenedByKey 同款）")
    void swallowFlagDiesWithTheNextKeyEvent() {
        GraphConsole.Core c = newCore();
        c.keyPressed(SLASH, 0, 0);          // 开台，swallow 置位 / open, swallow set
        c.keyPressed(65, 0, 0);             // 后续按键事件作废标志 / the next key event voids it
        assertTrue(c.charTyped('x', 0));
        assertEquals("x", c.inputLine(), "滞留标志不得误吞下一个输入 / a stale flag must not eat the next input");
    }

    @Test
    @DisplayName("关闭态 charTyped 放行（字符属于下方守卫链）")
    void closedStateCharTypedPassesThrough() {
        GraphConsole.Core c = newCore();
        assertFalse(c.charTyped('/', 0), "关闭态字符放行 / characters pass through while closed");
        assertFalse(c.charTyped('a', 0));
    }

    // ── 3. 命令提交（客户端解析面，规格 §4.4） / submit (the client-side parse face) ──

    @Test
    @DisplayName("提交：trim + 原样发送（不解释命令名）+ 本地回显 + 等待标记")
    void submitTrimsAndSendsVerbatim() {
        GraphConsole.Core c = newCore();
        c.keyPressed(SLASH, 0, 0);
        c.charTyped('h', 0); c.charTyped('e', 0); c.charTyped('l', 0);
        c.charTyped('p', 0);
        c.keyPressed(ENTER, 0, 0);
        assertEquals(List.of("help"), sent, "原样发送 / verbatim send");
        assertEquals("", c.inputLine(), "提交后清行 / the line clears after submit");
        assertTrue(outputTail(c).startsWith("> help"), "本地回显 / local echo");
        assertTrue(c.isAwaitingResponse(), "等待服务端响应 / awaiting the server response");
        assertTrue(c.keyPressed(ESC, 0, 0));
        assertFalse(c.isOpen(), "提交不关台 / submit does not close");
    }

    @Test
    @DisplayName("空行（纯空白）不发送、不入历史、不回显")
    void blankLineIsRejected() {
        GraphConsole.Core c = newCore();
        c.keyPressed(SLASH, 0, 0);
        c.keyPressed(ENTER, 0, 0);
        assertEquals("", c.inputLine());
        assertEquals(0, sent.size());
        assertFalse(c.isAwaitingResponse());
    }

    @Test
    @DisplayName("响应回填：追加行并解除等待标记（行内容原样，不解析）")
    void serverLinesBackfillAndClearAwaiting() {
        GraphConsole.Core c = newCore();
        c.keyPressed(SLASH, 0, 0);
        c.charTyped('h', 0); c.charTyped('e', 0); c.charTyped('l', 0); c.charTyped('p', 0);
        c.keyPressed(ENTER, 0, 0);
        c.receiveServerLines(List.of("Console commands / 控制台命令:", "  help   本表 / this table"));
        assertFalse(c.isAwaitingResponse());
        List<String> out = c.outputSnapshot();
        assertEquals("  help   本表 / this table", out.get(out.size() - 1), "原样展示 / verbatim display");
        c.receiveServerLines(null);
        assertFalse(c.isAwaitingResponse(), "null 容忍（防御） / null tolerated (defensive)");
    }

    // ── 4. 命令行编辑 / line editing ────────────────────────────────────────────────

    @Test
    @DisplayName("光标编辑：LEFT/RIGHT 插入、HOME/END、BACKSPACE/DELETE")
    void caretEditing() {
        GraphConsole.Core c = newCore();
        c.keyPressed(SLASH, 0, 0);
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
        c.keyPressed(SLASH, 0, 0);
        for (int i = 0; i < GraphConsole.Core.MAX_INPUT + 20; i++) c.charTyped('a', 0);
        assertEquals(GraphConsole.Core.MAX_INPUT, c.inputLine().length(), "超限字符被忽略 / over-cap chars ignored");
    }

    // ── 5. 历史导航 / history navigation ────────────────────────────────────────────

    @Test
    @DisplayName("↑/↓ 历史导航：进入历史存草稿、回到活位恢复草稿、首尾钳制")
    void historyNavigationPreservesDraft() {
        GraphConsole.Core c = newCore();
        c.keyPressed(SLASH, 0, 0);
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
        c.keyPressed(SLASH, 0, 0);
        c.keyPressed(UP, 0, 0);
        c.keyPressed(DOWN, 0, 0);
        assertEquals("", c.inputLine());
        type(c, "help");
        c.keyPressed(ENTER, 0, 0);
        c.keyPressed(UP, 0, 0);
        assertEquals("help", c.inputLine(), "提交后的历史可回溯 / history is browsable after submit");
    }

    // ── 6. 输出环形缓冲 / the output ring ───────────────────────────────────────────

    @Test
    @DisplayName("输出环形缓冲：超限丢最旧行（前缀保留语义）")
    void outputRingKeepsTheNewest() {
        GraphConsole.Core c = newCore();
        c.keyPressed(SLASH, 0, 0);
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
