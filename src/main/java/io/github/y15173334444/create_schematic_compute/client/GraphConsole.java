package io.github.y15173334444.create_schematic_compute.client;

import io.github.y15173334444.create_schematic_compute.client.editor.GraphEditor;
import io.github.y15173334444.create_schematic_compute.client.editor.NodeRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 图内 {@code /} 控制台叠加层（C 阶段步骤 5，规格 §4.1 落地形态 Q6=A）。
 *
 * <p><b>逻辑层与渲染层分开</b>：命令解析、开关状态机、输入守卫语义全部在嵌套的
 * {@link Core}（纯 JDK，零 MC 依赖，headless JUnit 可起——MC/Screen/GuiGraphics 类
 * 在 headless 下起不来）；外层 {@link GraphConsole} 只是把核心接到编辑器/网络上并
 * 画那一层薄壳（渲染层不测）。三根编辑器侧接线经 {@link Wiring} 注入：</p>
 * <ul>
 * <li>{@code textInputActive} —— 关闭态 {@code /} 开台的门（规格 §4.2 与守卫链既有约定
 * 一致：聚焦的输入框优先于全局快捷键）。</li>
 * <li>{@code onOpen} —— 开台清焦（规格 §4.2「清掉其他 EditBox 焦点，防双焦点」）。</li>
 * <li>{@code querySender} —— 发送原始命令字符串（规格 §4.4：客户端零文法，服务端唯一
 * 真相源；{@link io.github.y15173334444.create_schematic_compute.network.ConsoleQueryPacket}）。</li>
 * </ul>
 *
 * <p><b>数据面纪律（规格 §3.3 采样语义警示）</b>：本类是纯客户端 UI——<b>不读
 * {@code SignalBus}、不读任何本地求值状态</b>；将来任何数据展示（{@code stale}/探针类）
 * 必须走服务端求值快照口径，经 {@link io.github.y15173334444.create_schematic_compute.network.ConsoleResponsePacket}
 * 的文本行回到这里。核心里没有给「直读本地总线」留任何口子：输出行的唯一来源是
 * {@link #deliverToOpenEditor}（S→C）与本地回显。</p>
 *
 * <h2>In-editor "/" console overlay (Phase-C step 5, spec §4.1 placement Q6=A).</h2>
 *
 * <p><b>Logic and rendering are separated</b>: command parsing, the open/close state
 * machine and the input-guard semantics all live in the nested {@link Core} (pure JDK,
 * zero MC imports, boots in headless JUnit — the MC/Screen/GuiGraphics classes cannot
 * start headless); the outer {@link GraphConsole} only bridges the core to the editor /
 * network and paints the thin shell (the render layer is untested). Three editor-side
 * wires are injected via {@link Wiring}: the closed-state "/" open-gate, the focus-clear
 * on open, and the raw-command sender. The client holds <b>zero grammar</b> (spec §4.4:
 * the server is the single source of truth for the command table).</p>
 *
 * <p><b>Data-plane discipline (spec §3.3 sampling-semantics warning)</b>: this class is a
 * pure client-side UI — it never reads {@code SignalBus} or any locally evaluated state;
 * every future data display (stale / probe-like) must come from the server eval-snapshot
 * plane as text lines via {@code ConsoleResponsePacket}. The core leaves no seam for
 * "reading the local bus directly": output lines originate only from
 * {@link #deliverToOpenEditor} (S→C) and the local echo.</p>
 */
public class GraphConsole {

    /** 嵌入式逻辑核心（见类注：纯 JDK，headless 可测）。 / The embedded logic core (see
     *  the class doc: pure JDK, headless-testable). */
    public final Core core;

    /** 编辑器侧接线三元组（见类注）。 / The editor-side wiring triple (see the class doc). */
    public record Wiring(BooleanSupplier textInputActive, Runnable onOpen, Consumer<String> querySender) { }

    /** @param wiring 三根编辑器侧接线 / the three editor-side wires */
    public GraphConsole(Wiring wiring) {
        this.core = new Core(wiring);
    }

    /** 按键转发（编辑器守卫链链首调用，规格 §4.2）。 / Key forwarding (called at the head
     *  of the editor's guard chain, spec §4.2). */
    public boolean keyPressed(int key, int sc, int mod) {
        return core.keyPressed(key, sc, mod);
    }

    /** 字符转发（编辑器守卫链链首调用，规格 §4.2）。 / Character forwarding (called at the
     *  head of the editor's guard chain, spec §4.2). */
    public boolean charTyped(char ch, int mod) {
        return core.charTyped(ch, mod);
    }

    /** 控制台是否打开。 / Whether the console is open. */
    public boolean isOpen() {
        return core.isOpen();
    }

    /** S→C 响应汇点：路由到「当前打开的图编辑器」的控制台（无打开的编辑器则静默丢弃——
     *  响应是请求者私有，本就不该有人等它）。经 {@link GraphEditor#getActiveHost()} 解析
     *  （含便携终端包裹屏的解包），网络层不 import 编辑器（E2 汇点纪律）。
     *  S→C response sink: routes to the console of the currently open graph editor (silently
     *  dropped when none is open — a response is private to its requester and nobody else
     *  is waiting for it). Resolved via {@link GraphEditor#getActiveHost()} (unwraps the
     *  portable-terminal wrapper too); the network layer never imports the editor (the E2
     *  sink discipline). */
    public static void deliverToOpenEditor(List<String> lines) {
        GraphEditor.Host host = GraphEditor.getActiveHost();
        if (host == null) return;
        GraphEditor ed = host.getEditor();
        if (ed != null) ed.console.core.receiveServerLines(lines);
    }

    // ── 渲染层薄壳（不测 / the thin render shell, untested） ──────────────────────────

    /** 叠加层渲染：底部左侧半透明面板 = 输出尾行 + 输入行（含光标闪烁与等待标记）。
     *  由 {@code AbstractGraphScreen.render} 在全部 widget 之后调用（最上层）。
     *  Overlay render: a bottom-left translucent panel = output tail + input row (with a
     *  blinking caret and an awaiting marker). Called by {@code AbstractGraphScreen.render}
     *  after every widget (topmost). */
    public void renderConsole(GuiGraphics g, int mx, int my) {
        if (!core.isOpen()) return;
        var mc = Minecraft.getInstance();
        var theme = EditorTheme.get();
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        int w = Math.min(sw - 16, CONSOLE_W);
        int h = OUTPUT_ROWS * LINE_H + LINE_H + 10;
        int x0 = 8, y1 = sh - 8, y0 = y1 - h;
        g.fill(x0, y0, x0 + w, y1, NodeRenderer.withAlpha(theme.panelBg(), 0xE8));
        g.renderOutline(x0, y0, w, h, theme.sysBorder());
        // 输出尾行（环形截断后的可见窗口）
        // Output tail (the visible window over the trimmed ring).
        List<String> lines = core.outputSnapshot();
        int start = Math.max(0, lines.size() - OUTPUT_ROWS);
        int ty = y0 + 5;
        for (int i = start; i < lines.size(); i++) {
            g.drawString(mc.font, lines.get(i), x0 + 6, ty, theme.menuText(), false);
            ty += LINE_H;
        }
        // 输入行 + 光标（插入符在 caret 索引处，半秒闪烁）
        // Input row + caret (insertion point at the caret index, half-second blink).
        String in = core.inputLine();
        int caret = core.caretIndex();
        int iy = y1 - LINE_H - 4;
        String prompt = "> " + in;
        g.drawString(mc.font, prompt, x0 + 6, iy, theme.accent(), false);
        if ((System.currentTimeMillis() / 500) % 2 == 0) {
            int cx = x0 + 6 + mc.font.width(in.substring(0, Math.min(caret, in.length()))) + mc.font.width("> ");
            g.fill(cx, iy - 1, cx + 1, iy + 9, theme.menuText());
        }
        if (core.isAwaitingResponse()) {
            String wait = "…";
            g.drawString(mc.font, wait, x0 + w - 6 - mc.font.width(wait), iy, theme.menuText(), false);
        }
    }

    /** 面板宽度上限（gui 缩放像素）。 / Panel width cap (gui-scaled pixels). */
    private static final int CONSOLE_W = 460;
    /** 行高（vanilla 字体行 9px + 1px 间距）。 / Line height (vanilla font 9px + 1px gap). */
    private static final int LINE_H = 10;
    /** 输出可见行数。 / Visible output rows. */
    private static final int OUTPUT_ROWS = 9;

    // ═══════════════════════════════════════════════════════════════════════════════
    //  逻辑核心：状态机 + 输入守卫语义 + 命令行编辑（纯 JDK，headless 可测）
    //  Logic core: state machine + input-guard semantics + command-line editing
    //  (pure JDK, headless-testable)
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * 控制台核心：{@code /} 开关状态机、模态输入守卫语义、命令行编辑与历史、输出环形缓冲。
     * 零 MC 依赖——键码用 int 常量（照守卫链既有口径：ESC=256 / Enter=257 / Backspace=259），
     * 不 import GLFW（其类静态初始化在 headless 下不可靠）。
     *
     * <p><b>开关状态机</b>：关闭态只有 {@code /} 能开台，且仅在 {@link Wiring#textInputActive}
     * 为 false 时（菜单搜索框 / 导出命名框 / 取色器 / 节点编辑框 / 书签命名 / 按键绑定捕获
     * 激活时 {@code /} 必须先喂给那些输入面——照守卫链「聚焦输入框优先于全局快捷键」的既有
     * 约定）；打开态吞掉<b>全部</b>按键（模态）——ESC 只关控制台（规格 §4.2 链首拦截，绝不
     * 落到 Screen 默认 onClose）。</p>
     *
     * <p><b>开键字符吞没（menuOpenedByKey 同款纪律）</b>：{@code /} 开台后紧随的 charTyped
     * 是开台键自己的字符，吞掉不清进输入行；标志只在紧随其后的本次 charTyped 有效且只匹配
     * {@link #TOGGLE_CHAR}——开台键不产生字符的布局下，紧随的第一个真实输入照常插入（标志
     * 一并消费），任何后续按键事件也先作废——不产生字符的键不会滞留误吞下一个输入。</p>
     *
     * <p><b>The opening-char swallow (the menuOpenedByKey discipline)</b>: the charTyped that
     * follows a "/" open is the opening key's own character — it is eaten, not inserted; the
     * flag is valid only for the charTyped immediately following and only matches
     * {@link #TOGGLE_CHAR} — on char-less-opener layouts the first real input still inserts
     * (consuming the flag), and any later key event voids the flag first, so nothing lingers
     * to eat the next input.</p>
     *
     * <p><b>命令解析面（客户端侧）</b>：规格 §4.4——客户端把<b>原始字符串</b>发给服务端，
     * 服务端解析（文法唯一真相源在服务端，见 ConsoleProtocol）。本核心只做提交门：trim、
     * 空行拒绝、长度上限；提交动作 = 本地回显 {@code > cmd} + 历史 + 原样发送，<b>不解释
     * 命令名</b>。</p>
     *
     * <h2>The console core: the "/" toggle state machine, modal input-guard semantics,
     * command-line editing and history, and the output ring. Zero MC dependencies — key
     * codes are plain int constants (the guard chain's existing convention: ESC=256 /
     * Enter=257 / Backspace=259) and GLFW is not imported (its class static-init is
     * unreliable headless).</h2>
     *
     * <p><b>Toggle state machine</b>: while closed only "/" opens, and only when
     * {@link Wiring#textInputActive} is false (the menu search box / export name box / color
     * picker / node edit boxes / bookmark name dialog / key-binding capture own "/" first —
     * the guard chain's existing "focused inputs beat global shortcuts" convention); while
     * open <b>every</b> key is swallowed (modal) — ESC closes only the console (spec §4.2
     * chain-head interception, never falling through to Screen's default onClose).</p>
     *
     * <p><b>Opening-char swallow (the menuOpenedByKey discipline)</b>: the charTyped that
     * follows a "/" open is the opening key's own character — it is eaten, not inserted;
     * the flag is valid only for the charTyped immediately following, and any later key
     * event voids it first (a char-less key can't linger and eat the next input).</p>
     *
     * <p><b>Client-side parse face</b> (spec §4.4): the client sends the <b>raw string</b>
     * to the server and the server parses (the grammar's single source of truth lives in
     * ConsoleProtocol). This core only gates the submit: trim, blank rejection, length cap;
     * submitting = local echo {@code > cmd} + history + verbatim send, <b>never
     * interpreting the command name</b>.</p>
     */
    public static final class Core {
        /** 开台键 = {@code /}（GLFW_KEY_SLASH 的数值；全仓无编辑器级绑定——规格 §4.1 前提，
         *  实查仅 EditPanel 的节点绑定映射与座椅按键表提及，均非编辑器快捷键）。
         *  The toggle key = "/" (GLFW_KEY_SLASH's value; no editor-level binding anywhere —
         *  spec §4.1's premise; only EditPanel's per-node binding map and the seat key list
         *  mention it, neither is an editor shortcut). */
        public static final int TOGGLE_KEY = 47;

        /** 开台键的字符形态（GLFW_KEY_SLASH=47 的可打印字符本身）。吞没标志只匹配它——
         *  开台键不产生字符的键盘布局下，标志不会误吞紧随的第一个输入（见 charTyped）。
         *  The toggle key's character form (GLFW_KEY_SLASH=47's printable char itself). The
         *  swallow flag only matches it — on layouts where the opener produces no character,
         *  the flag cannot eat the first real input (see charTyped). */
        static final char TOGGLE_CHAR = (char) TOGGLE_KEY;

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
        private boolean open = false;
        /** 开台键字符吞没标志（见类注：只在紧随其后的 charTyped 有效）。
         *  The opening-char swallow flag (see the class doc: valid only for the immediately
         *  following charTyped). */
        private boolean swallowNextChar = false;
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

        /** @param wiring 三根编辑器侧接线 / the three editor-side wires */
        public Core(Wiring wiring) {
            this.wiring = wiring;
        }

        // ── 开关状态机 / toggle state machine ─────────────────────────────────────

        /** 控制台是否打开。 / Whether the console is open. */
        public boolean isOpen() {
            return open;
        }

        /** 打开控制台（清焦 + 吞没开键字符 + 首开提示）。 / Opens the console (focus clear +
         *  opening-char swallow + first-open hint). */
        void open() {
            open = true;
            swallowNextChar = true;
            wiring.onOpen().run();
            if (!hintShown) {
                hintShown = true;
                addOutput("console — 输入 help 查看命令表（文法由服务端定义）/ type help for the command list (grammar is server-defined)");
            }
        }

        /** 关闭控制台（吞没标志一并清除，杜绝滞留误吞）。 / Closes the console (also clears
         *  the swallow flag so nothing lingers to eat the next input). */
        void close() {
            open = false;
            swallowNextChar = false;
        }

        // ── 输入守卫语义 / input-guard semantics ──────────────────────────────────

        /**
         * 按键处理（编辑器守卫链链首调用）。关闭态：仅 {@code /} 在无文本输入面激活时开台
         * （返回 true = 已消费），其余放行（false）。打开态：模态吞掉全部键——ESC 关台、
         * Enter 提交、Backspace/Delete/方向键/Home/End 编辑、↑/↓ 历史，其余消费不动。
         * <p>入口先作废吞没标志（menuOpenedByKey 同款纪律）。</p>
         * <p>Key handling (called at the head of the editor's guard chain). While closed:
         * only "/" opens — and only with no text-entry surface active — returning true
         * (consumed); everything else passes (false). While open: modal — every key is
         * swallowed; ESC closes, Enter submits, Backspace/Delete/arrows/Home/End edit,
         * ↑/↓ history, the rest are consumed untouched. The entry voids the swallow flag
         * first (the menuOpenedByKey discipline).</p>
         */
        public boolean keyPressed(int key, int sc, int mod) {
            swallowNextChar = false;
            if (!open) {
                if (key == TOGGLE_KEY && !wiring.textInputActive().getAsBoolean()) {
                    open();
                    return true;
                }
                return false;
            }
            switch (key) {
                case KEY_ESC -> close();
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
            return true; // 模态：全部消费（规格 §4.2 链首拦截）/ modal: consume everything (spec §4.2 chain head)
        }

        /**
         * 字符处理（编辑器守卫链链首调用）。关闭态：放行（false——字符属于下方守卫链）。
         * 打开态：吞没标志有效时只吞掉开台键自己的字符（{@link #TOGGLE_CHAR}）——开台键
         * 未产生字符的布局下紧随的第一个输入照常插入（标志一并消费，不滞留）；可打印字符
         * 插入光标处（上限 {@link #MAX_INPUT}），其余消费不动。
         * <p>Character handling (called at the head of the editor's guard chain). While
         * closed: pass through (false — the character belongs to the guard chain below).
         * While open: a valid swallow flag eats only the toggle key's own character
         * ({@link #TOGGLE_CHAR}) — on layouts where the opener produces no character, the
         * first real input still inserts (and consumes the flag, nothing lingers); printable
         * characters insert at the caret (capped at {@link #MAX_INPUT}); the rest are
         * consumed untouched.</p>
         */
        public boolean charTyped(char ch, int mod) {
            if (!open) return false;
            if (swallowNextChar) {
                swallowNextChar = false;
                if (ch == TOGGLE_CHAR) return true; // 开台键自己的字符吞掉 / the opener's own char is eaten
            }
            if (ch >= 32 && ch != 127 && input.length() < MAX_INPUT) {
                input.insert(caret, ch);
                caret++;
            }
            return true;
        }

        // ── 命令提交（客户端解析面 / the client-side parse face） ─────────────────

        /** 提交：trim、空行拒绝、原样发送（不解释命令名——规格 §4.4 服务端唯一真相源）；
         *  本地回显 + 历史 + 等待标记。 / Submit: trim, blank rejection, verbatim send (the
         *  command name is never interpreted — spec §4.4 server-side single source of
         *  truth); plus local echo, history and the awaiting marker. */
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

        /** 服务端响应回填（S→C，经 {@link #deliverToOpenEditor} 路由）：追加文本行并解除
         *  等待标记。行内容<b>原样展示</b>——文法与语义都在服务端（规格 §4.4）。
         *  Server-response backfill (S→C, routed via {@link #deliverToOpenEditor}): appends
         *  the text lines and clears the awaiting marker. Lines are displayed <b>verbatim</b>
         *  — grammar and semantics both live server-side (spec §4.4). */
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
