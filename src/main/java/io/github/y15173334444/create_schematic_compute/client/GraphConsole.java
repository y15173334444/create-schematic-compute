package io.github.y15173334444.create_schematic_compute.client;

import io.github.y15173334444.create_schematic_compute.client.editor.GraphEditor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 图内 CSC 控制台的逻辑核心 + S→C 汇点（侧栏「终端」面板的数据面）。
 *
 * <p><b>位置改判（第十二轮）</b>：控制台原先是以 {@code /} 开台的独立叠加层（模态、吞掉
 * 全部按键）；现整块搬进右缘侧栏的「终端」面板——{@code /} 开台键与叠加层一起退役，面板
 * 只从图标条终端图标开，开面板即聚焦输入行。面板不再是模态层：只有输入行聚焦时吃键
 * （编辑器快捷键与画布手势照常），Esc / 点画布失焦。</p>
 *
 * <p><b>逻辑层与渲染层分开</b>：输入行编辑与历史、提交门、输出环形缓冲全部在嵌套的
 * {@link Core}（纯 JDK，零 MC 依赖，headless JUnit 可起——MC/Screen/GuiGraphics 类在
 * headless 下起不来）；面板渲染在 {@code GraphSidebar}（渲染层不测）。两根编辑器侧接线经
 * {@link Wiring} 注入：聚焦时清其它输入框焦点（防双焦点）、原始命令发送。</p>
 *
 * <p><b>数据面纪律</b>：本类是纯客户端 UI——<b>不读 {@code SignalBus}、不读任何本地求值
 * 状态</b>；输出行的唯一来源是服务端响应（{@link #deliverToOpenEditor}，
 * {@code ConsoleResponsePacket}）与本地回显。客户端零文法：输入行 trim 后<b>原样</b>发出
 * （{@code ConsoleQueryPacket}），命令表与语义都在服务端（{@code ConsoleProtocol}）。</p>
 *
 * <h2>The CSC console's logic core + the S→C sink (the data plane behind the sidebar's
 * "terminal" panel).</h2>
 *
 * <p><b>Placement (round 12).</b> The console used to be a modal "/"-opened overlay that ate
 * every key; it now lives in the right-edge sidebar's terminal panel — the "/" opener and the
 * overlay are retired, the panel opens only from the rail's terminal icon, and opening it
 * focuses the input row. The panel is not modal: keys are swallowed only while the input row
 * has focus (editor shortcuts and canvas gestures keep working), and Esc / a canvas click
 * blurs it.</p>
 *
 * <p><b>Logic and rendering are separated</b>: line editing, history, the submit gate and the
 * output ring live in the nested {@link Core} (pure JDK, headless-testable); the panel renders
 * in {@code GraphSidebar} (the render layer is untested). Two editor-side wires arrive via
 * {@link Wiring}: focus clear on taking focus (no double focus) and the raw command sender.</p>
 *
 * <p><b>Data-plane discipline</b>: this class is pure client-side UI — it never reads
 * {@code SignalBus} or any locally evaluated state; output lines originate only from the
 * server response ({@link #deliverToOpenEditor}) and the local echo. The client holds zero
 * grammar: the trimmed input goes out <b>verbatim</b>; the command table and semantics live
 * server-side.</p>
 */
public class GraphConsole {

    /** 嵌入式逻辑核心（见类注：纯 JDK，headless 可测）。 / The embedded logic core (see the
     *  class doc: pure JDK, headless-testable). */
    public final Core core;

    /** 编辑器侧接线二元组（见类注）。 / The editor-side wiring pair (see the class doc). */
    public record Wiring(Runnable onFocus, Consumer<String> querySender) { }

    /** @param wiring 两根编辑器侧接线 / the two editor-side wires */
    public GraphConsole(Wiring wiring) {
        this.core = new Core(wiring);
    }

    /** S→C 响应汇点：路由到「当前打开的图编辑器」的控制台输出环（无打开的编辑器则静默丢弃
     *  ——响应是请求者私有，本就不该有人等它）。经 {@link GraphEditor#getActiveHost()} 解析
     *  （含便携终端包裹屏的解包），网络层不 import 编辑器（E2 汇点纪律）。
     *  S→C response sink: routes into the console output ring of the currently open graph
     *  editor (silently dropped when none is open — a response is private to its requester and
     *  nobody else is waiting for it). Resolved via {@link GraphEditor#getActiveHost()} (unwraps
     *  the portable-terminal wrapper too); the network layer never imports the editor (the E2
     *  sink discipline). */
    public static void deliverToOpenEditor(List<String> lines) {
        GraphEditor.Host host = GraphEditor.getActiveHost();
        if (host == null) return;
        GraphEditor ed = host.getEditor();
        if (ed != null) ed.console.core.receiveServerLines(lines);
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    //  逻辑核心：聚焦态 + 命令行编辑与历史 + 输出环形缓冲（纯 JDK，headless 可测）
    //  Logic core: focus state + command-line editing and history + the output ring
    //  (pure JDK, headless-testable)
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * 控制台核心：输入行聚焦态、命令行编辑与历史、提交门、输出环形缓冲。零 MC 依赖——键码
     * 用 int 常量（照守卫链既有口径：ESC=256 / Enter=257 / Backspace=259），不 import GLFW
     * （其类静态初始化在 headless 下不可靠）。
     *
     * <p><b>聚焦态</b>：{@link #focus()} 由侧栏面板打开/点击输入区触发，顺带
     * {@link Wiring#onFocus()} 清其它 EditBox 焦点（防双焦点）；{@link #blur()} 由 Esc 或
     * 面板外的点击触发。聚焦时<b>全部按键被吞</b>（与聚焦 EditBox 同口径：编辑器快捷键、
     * 画布手势、Screen 的 ESC 关屏都不触发）；失焦时按键与字符一律放行。</p>
     *
     * <p><b>提交面</b>：trim、空行拒绝、长度上限；提交 = 本地回显 {@code > cmd} + 历史 +
     * 原样发送，<b>不解释命令名</b>（文法唯一真相源在服务端）。</p>
     *
     * <h2>The console core: the input row's focus state, command-line editing and history,
     * the submit gate and the output ring. Zero MC dependencies — key codes are plain int
     * constants and GLFW is not imported (its static init is unreliable headless).</h2>
     *
     * <p><b>Focus state</b>: {@link #focus()} fires when the sidebar panel opens or its input
     * area is clicked, clearing other edit-box focus via {@link Wiring#onFocus()} (no double
     * focus); {@link #blur()} fires on Esc or a click outside the panel. While focused
     * <b>every</b> key is swallowed (the focused-EditBox convention: no editor shortcuts, no
     * canvas gestures, no Screen ESC close); while blurred keys and characters pass through.</p>
     *
     * <p><b>The submit face</b>: trim, blank rejection, length cap; submitting = local echo
     * {@code > cmd} + history + verbatim send, <b>never interpreting the command name</b> (the
     * grammar's single source of truth is server-side).</p>
     */
    public static final class Core {
        /** 键码常量（守卫链既有口径，int 字面量）。 / Key-code constants (the guard chain's
         *  existing convention, plain ints). */
        static final int KEY_ESC = 256, KEY_ENTER = 257, KEY_BACKSPACE = 259, KEY_DELETE = 261,
                KEY_RIGHT = 262, KEY_LEFT = 263, KEY_DOWN = 264, KEY_UP = 265, KEY_HOME = 268, KEY_END = 269;

        /** 输入行长度上限（与原版聊天一致；服务端 ConsoleProtocol 侧同值复核）。
         *  Input-line length cap (same as vanilla chat; re-checked server-side in
         *  ConsoleProtocol). */
        public static final int MAX_INPUT = 256;

        /** 输出环形缓冲容量（行）。 / Output ring capacity (lines). */
        public static final int MAX_OUTPUT_LINES = 400;

        private final Wiring wiring;
        private boolean focused = false;
        private final StringBuilder input = new StringBuilder();
        private int caret = 0;
        private final ArrayList<String> history = new ArrayList<>();
        /** 历史浏览游标：{@code history.size()} = 活输入位。 / History browse cursor:
         *  {@code history.size()} = the live-input position. */
        private int historyCursor;
        /** 历史浏览期间的活输入草稿（UP 进入历史时保存，DOWN 回到末位时恢复）。
         *  The live-input draft while browsing history (saved on UP, restored on DOWN back
         *  to the live position). */
        private String draft = "";
        private final ArrayList<String> output = new ArrayList<>();
        /** 提交后等待服务端响应（外观标记；限流拒绝 = 永无响应，下一次提交自然覆盖——
         *  超时提示为暂缓项）。 / Set after submit while awaiting the server response
         *  (cosmetic; a rate-limit rejection means no response ever arrives and the next
         *  submit naturally supersedes it — a timeout hint is deferred). */
        private boolean awaitingResponse = false;
        private boolean hintShown = false;

        /** @param wiring 两根编辑器侧接线 / the two editor-side wires */
        public Core(Wiring wiring) {
            this.wiring = wiring;
        }

        // ── 聚焦态 / focus state ─────────────────────────────────────────────────

        /** 输入行是否聚焦（聚焦时吃全部键）。 / Whether the input row has focus (every key
         *  is swallowed while it does). */
        public boolean isFocused() {
            return focused;
        }

        /** 取得焦点（清其它 EditBox 焦点 + 首次打开提示）。 / Takes focus (clears other
         *  edit-box focus + the first-open hint). */
        public void focus() {
            if (focused) return;
            focused = true;
            wiring.onFocus().run();
            if (!hintShown) {
                hintShown = true;
                addOutput("console — 输入 help 查看命令表（文法由服务端定义）/ type help for the command list (grammar is server-defined)");
            }
        }

        /** 释放焦点（输入行草稿保留）。 / Releases focus (the input draft survives). */
        public void blur() {
            focused = false;
        }

        // ── 输入 / input ─────────────────────────────────────────────────────────

        /**
         * 按键处理（聚焦时由编辑器守卫链链首调用）。聚焦态：Esc 失焦、Enter 提交、
         * Backspace/Delete/方向键/Home/End 编辑、↑/↓ 历史，其余键一并消费（聚焦输入框优先于
         * 全局快捷键，也拦住 Screen 的 ESC 关屏）。失焦态一律放行。
         * <p>Key handling (called at the head of the editor's guard chain while focused).
         * Focused: Esc blurs, Enter submits, Backspace/Delete/arrows/Home/End edit, ↑/↓
         * history, and every other key is consumed too (a focused input beats global
         * shortcuts and Screen's ESC close). While blurred everything passes.</p>
         */
        public boolean keyPressed(int key, int sc, int mod) {
            if (!focused) return false;
            switch (key) {
                case KEY_ESC -> blur();
                case KEY_ENTER -> submit();
                case KEY_BACKSPACE -> deleteBefore();
                case KEY_DELETE -> deleteAfter();
                case KEY_LEFT -> {
                    if (caret > 0) caret--;
                }
                case KEY_RIGHT -> {
                    if (caret < input.length()) caret++;
                }
                case KEY_HOME -> caret = 0;
                case KEY_END -> caret = input.length();
                case KEY_UP -> historyUp();
                case KEY_DOWN -> historyDown();
                default -> { }
            }
            return true; // 聚焦：全部消费 / focused: consume everything
        }

        /**
         * 字符处理（聚焦时由编辑器守卫链链首调用）。聚焦态：可打印字符插入光标处（上限
         * {@link #MAX_INPUT}），其余消费不动；失焦态放行。
         * <p>Character handling (called at the head of the editor's guard chain while
         * focused). Focused: printable characters insert at the caret (capped at
         * {@link #MAX_INPUT}), the rest are consumed untouched; blurred: pass through.</p>
         */
        public boolean charTyped(char ch, int mod) {
            if (!focused) return false;
            if (ch >= 32 && ch != 127 && input.length() < MAX_INPUT) {
                input.insert(caret, ch);
                caret++;
            }
            return true;
        }

        /** 提交：trim、空行拒绝、原样发送（不解释命令名——服务端唯一真相源）；本地回显 +
         *  历史 + 等待标记。 / Submit: trim, blank rejection, verbatim send (the command name
         *  is never interpreted — the server is the single source of truth); plus local echo,
         *  history and the awaiting marker. */
        private void submit() {
            String raw = input.toString().trim();
            if (raw.isEmpty()) return;
            history.add(raw);
            historyCursor = history.size();
            draft = "";
            input.setLength(0);
            caret = 0;
            addOutput("> " + raw);
            awaitingResponse = true;
            wiring.querySender().accept(raw);
        }

        /** 服务端响应回填（S→C，经 {@link #deliverToOpenEditor} 路由）：追加文本行并解除等待
         *  标记。行内容<b>原样展示</b>——文法与语义都在服务端。
         *  Server-response backfill (S→C, routed via {@link #deliverToOpenEditor}): appends the
         *  text lines and clears the awaiting marker. Lines are displayed <b>verbatim</b> —
         *  grammar and semantics both live server-side. */
        public void receiveServerLines(List<String> lines) {
            awaitingResponse = false;
            if (lines == null) return;
            for (String line : lines) addOutput(line);
        }

        // ── 命令行编辑 / command-line editing ─────────────────────────────────────

        private void deleteBefore() {
            if (caret == 0) return;
            input.deleteCharAt(caret - 1);
            caret--;
        }

        private void deleteAfter() {
            if (caret >= input.length()) return;
            input.deleteCharAt(caret);
        }

        private void historyUp() {
            if (history.isEmpty()) return;
            if (historyCursor == history.size()) draft = input.toString();
            historyCursor = Math.max(0, historyCursor - 1);
            loadFromHistory();
        }

        private void historyDown() {
            if (historyCursor >= history.size()) return;
            historyCursor++;
            if (historyCursor == history.size()) {
                input.setLength(0);
                input.append(draft);
                caret = input.length();
                draft = "";
            } else {
                loadFromHistory();
            }
        }

        private void loadFromHistory() {
            String s = history.get(historyCursor);
            input.setLength(0);
            input.append(s);
            caret = input.length();
        }

        private void addOutput(String line) {
            output.add(line);
            while (output.size() > MAX_OUTPUT_LINES) output.remove(0);
        }

        // ── 渲染层读取面 / the render-layer read face ─────────────────────────────

        /** 输出快照（渲染层读；副本，不含历史/输入行）。 / Output snapshot (render-layer
         *  read; a copy, history and the input line excluded). */
        public List<String> outputSnapshot() {
            return new ArrayList<>(output);
        }

        /** 当前输入行。 / The current input line. */
        public String inputLine() {
            return input.toString();
        }

        /** 光标索引（插入符位置）。 / The caret index (insertion point). */
        public int caretIndex() {
            return caret;
        }

        /** 是否在等待服务端响应（外观标记）。 / Whether a server response is awaited
         *  (cosmetic marker). */
        public boolean isAwaitingResponse() {
            return awaitingResponse;
        }
    }
}
