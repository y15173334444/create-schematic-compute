package io.github.y15173334444.create_schematic_compute.blocks;

import java.util.List;
import java.util.Properties;

/**
 * 编辑器画布交互的键位绑定表 —— 「动作 → 鼠标键 / 键序序列」的单一来源。
 * Single source of truth for the editor's canvas-interaction bindings —
 * "action → mouse button / key sequence".
 *
 * <p>键盘动作的绑定是一条<b>有序步骤序列</b>（1..{@value #MAX_STEPS} 步，每步 = 主键+修饰位），
 * 由 {@link #feedKey} 引擎逐键推进：完整命中即触发；是某绑定的前缀则缓冲等待（1.5s 超时作废）；
 * 无匹配则清空缓冲并把当前键当作新首步重新评估（vim 式重开）。单步序列与旧版单键绑定行为一致。
 * A keyboard action's binding is an <b>ordered step sequence</b> (1..{@value #MAX_STEPS}
 * steps, each = key + modifier mask), advanced per keystroke by the {@link #feedKey}
 * engine: a full match triggers; a prefix buffers (1.5s timeout); a miss clears the
 * buffer and re-evaluates the current key as a new first step (vim-style restart).
 * Single-step sequences behave exactly like the old single-key bindings.
 *
 * <p><b>统一序列模型</b>：每个动作只有一条触发器 = 一条 1..{@value #MAX_STEPS} 步的
 * 有序序列，<b>步骤既可以是按键也可以是鼠标点击</b>（鼠标步编码为负键码，
 * {@link #mouseStepKey}）——纯键序（Ctrl+Z）、纯鼠标（右键开菜单 = 单鼠标步）、
 * 键鼠组合（Tab → 左键）、乃至键鼠交错的多步队列（Tab → 左键 → A → 右键）都是同
 * 一种东西。引擎分两个独立域逐事件推进：<b>纯键域</b>（全键序列，keyPressed 逐键
 * 推进、完整命中触发）；<b>混合域</b>（含鼠标步的序列，keyPressed 推进键步、
 * {@link #feedClick} 推进鼠标步，任一类事件完成即触发）。两域各自缓冲、互不清理
 * ——出厂 DELETE_WIRE=Tab → 左键 与 BOX_SELECT=Tab 并存即因此：Tab 按键触发框选的
 * 同时为删线组合入混合缓冲，随后的左键完成触发。
 * <b>Unified sequence model</b>: an action holds ONE trigger = one ordered 1..
 * {@value #MAX_STEPS}-step sequence whose steps may be KEY presses or MOUSE clicks
 * (mouse steps encode as negative keycodes, {@link #mouseStepKey}) — pure key
 * sequences (Ctrl+Z), pure mouse gestures (right-click opens the menu = a single
 * mouse step), key+mouse combos (Tab → left-click) and interleaved multi-step queues
 * (Tab → left-click → A → right-click) are all the same thing. The engine advances
 * two independent domains per event: the <b>pure-key domain</b> (all-key sequences,
 * advanced per keystroke, fired on a full match) and the <b>mixed domain</b>
 * (sequences containing mouse steps, key steps advanced by keyPressed, mouse steps by
 * {@link #feedClick}, fired by whichever event completes them). The domains buffer
 * separately and never clear each other — which is exactly why the factory
 * DELETE_WIRE=Tab → left-click coexists with BOX_SELECT=Tab: the Tab press fires
 * box-select while arming the wire chord in the mixed buffer, and the following
 * left-click completes it.
 *
 * <p>PAN 的序列是单步特例：鼠标步 = 该按钮拖拽平移（拖拽管线消费）；键步 = 按住抓图
 * （keyPressed 拦截、press 引擎永不触发、{@link #panKeyBound} 停用按钮拖动）。其抓图
 * 键对<b>所有</b>序列的键步独占（按键被手势拦截，任何序列用到都无法完成）。
 * PAN's sequence is a single-step special case: a mouse step = drag-pan on that
 * button (consumed by the drag pipeline); a key step = the hold-grab (intercepted by
 * keyPressed, never press-fired, {@link #panKeyBound} disables button-drag). Its grab
 * key is exclusive against the key steps of ALL sequences (the gesture intercepts the
 * key; any sequence using it could never complete).
 *
 * <p>像素动作与 BOX_SELECT 不开放鼠标步（绘画/按住语义独占鼠标）；其余动作键步、
 * 鼠标步皆可——菜单恢复键序触发（按绑定键在光标处打开）。
 * The pixel actions and BOX_SELECT take no mouse steps (painting / hold semantics own
 * the mouse); every other action accepts both — the menu regains its key-sequence
 * trigger (opens at the cursor on the bound key).
 *
 * <p>冲突规则（同域内校验，跨域事件流不同可共存）：同域两条序列经
 * {@link #prefixAmbiguous} 判定——等长逐位键相同必拒（并集修饰事件会同时命中、
 * 引擎只触发枚举序靠前的一条）；不等长时短者是长者的键前缀且修饰 ⊆ 才拒，修饰可
 * 区分的不等长对可共存（Ctrl+左键 与 左键→右键 各自只在己方修饰态命中，同理）。
 * 鼠标步没有额外规则：判定按键码走（负键码 = 按钮），共享首按钮的两条队列靠后续
 * 步骤区分（左键→右键 与 左键→A 各等自己的第二事件）；只有等长全同对与修饰 ⊆
 * 前缀对才拒。再加 PAN 抓图键独占（对所有序列的键步，键相同即拒）。
 * Conflict rules (checked within a domain; cross-domain event streams coexist): two
 * same-domain sequences go through {@link #prefixAmbiguous} — equal length with
 * per-step equal keys is always refused (the union-modifier event matches both; the
 * engine keeps only the enum-first); for different lengths the shorter must be a
 * key-prefix of the longer with its mods contained — modifier-distinguishable pairs
 * coexist (Ctrl+left-click vs left-click → right-click each fire only in their own
 * modifier state). Mouse steps carry no extra rule: the check keys off the keycode
 * (a negative keycode = a button); two queues sharing the first button stay
 * distinguishable by their later steps (left→right vs left→A each await their own
 * second event); only equal-length all-equal pairs and mods-contained prefixes are
 * refused. Plus PAN's grab-key exclusivity (against the key steps of all sequences,
 * key equality suffices).
 *
 * <p>持久化复用既有客户端配置文件
 * {@code config/create_schematic_compute-client.properties}
 * （与 NodeRenderer 的颜色 / 网格吸附 / 工具栏位置同一机制）。格式
 * {@code editorKeys.<动作>.seq = "键,修饰;键,修饰"}（负键码 = 鼠标步），由
 * {@code editorKeys.format=2} 标记。旧版保存从不写标记——无标记≠脏表：seq 键序
 * 值原样保留；历史脏数据只在旧 {@code .mouse} 鼠标槽字段（未初始化槽默认 0=左键
 * 泄漏进保存），读取时忽略、保存时移除并写回标记。更老的 key/mods 单键配置没有
 * seq 值可读，自然落回出厂。
 * Persisted into the existing client config file, same mechanism NodeRenderer uses.
 * Format: {@code editorKeys.<ACTION>.seq = "key,mods;key,mods"} (a negative keycode =
 * a mouse step), stamped {@code editorKeys.format=2}. The old save() never wrote a
 * marker — marker-less does NOT mean dirty: seq values carry over verbatim; the
 * historical dirty data lived only in the legacy {@code .mouse} slot fields
 * (uninitialized slots defaulted to 0 = left button and leaked into saves), which
 * are ignored on load, removed on save, with the marker written back. Older
 * key/mods configs hold no seq values at all and fall back to the factory defaults.
 */
public final class EditorKeys {

    /** 可绑定的编辑器动作。 / Bindable editor actions. */
    public enum Action {
        /** 平移画布（拖动图） / pan the canvas (drag the graph) */
        PAN("editorkeys.pan"),
        /** 打开画布上下文菜单 / open the canvas context menu */
        CONTEXT_MENU("editorkeys.context_menu"),
        /** 删除悬停节点 / delete the hovered node */
        DELETE_NODE("editorkeys.delete"),
        /** 删除选中节点（原 Backspace/Delete 硬编码，现默认 Delete） / delete the selected nodes (was hardcoded to Backspace/Delete; default is Delete now) */
        DELETE_SELECTED("editorkeys.delete_selected"),
        /** 撤销 / undo */
        UNDO("editorkeys.undo"),
        /** 重做 / redo */
        REDO("editorkeys.redo"),
        /** 复制选中 / duplicate selection */
        DUPLICATE("editorkeys.duplicate"),
        /** 重置视角 / reset the view */
        RESET_VIEW("editorkeys.reset_view"),
        /** 保存视角书签 / save a view bookmark */
        SAVE_BOOKMARK("editorkeys.save_bookmark"),
        /** 框选多选（按住生效；原 Tab 硬编码） / box-select mode (held; was hardcoded to Tab) */
        BOX_SELECT("editorkeys.box_select"),
        /** 删除悬停连线（原 Tab+左键 点连线；该组合路径保留） / delete the hovered wire (was Tab+left-click; that chord path stays) */
        DELETE_WIRE("editorkeys.delete_wire"),

        // ── 像素编辑器 / pixel editor (PS-style hardcodes, now bindable) ──
        /** 像素编辑：画笔工具 / pixel editor: brush tool */
        PIXEL_BRUSH("editorkeys.pixel_brush"),
        /** 像素编辑：橡皮工具 / pixel editor: eraser tool */
        PIXEL_ERASER("editorkeys.pixel_eraser"),
        /** 像素编辑：填充工具 / pixel editor: fill tool */
        PIXEL_FILL("editorkeys.pixel_fill"),
        /** 像素编辑：取色工具 / pixel editor: eyedropper tool */
        PIXEL_EYEDROPPER("editorkeys.pixel_eyedropper"),
        /** 像素编辑：直线工具 / pixel editor: line tool */
        PIXEL_LINE("editorkeys.pixel_line"),
        /** 像素编辑：矩形工具 / pixel editor: rect tool */
        PIXEL_RECT("editorkeys.pixel_rect"),
        /** 像素编辑：抓手工具 / pixel editor: hand (pan) tool */
        PIXEL_HAND("editorkeys.pixel_hand"),
        /** 像素编辑：网格开关 / pixel editor: toggle the grid */
        PIXEL_GRID("editorkeys.pixel_grid"),
        /** 像素编辑：笔刷变小（原 [ 硬编码） / pixel editor: smaller brush (was hardcoded to [) */
        PIXEL_BRUSH_SMALLER("editorkeys.pixel_brush_smaller"),
        /** 像素编辑：笔刷变大（原 ] 硬编码） / pixel editor: bigger brush (was hardcoded to ]) */
        PIXEL_BRUSH_BIGGER("editorkeys.pixel_brush_bigger");

        /** lang 键（设置界面显示用，渲染时补 gui.create_schematic_compute. 前缀） / lang key (prefixed at render time). */
        public final String langKey;

        Action(String langKey) { this.langKey = langKey; }
    }

    /** 修饰位掩码（与 GLFW_KEY_MOD_* 的语义对应，取值即 bit0=Ctrl bit1=Shift bit2=Alt）。
     *  Modifier mask bits: bit0=Ctrl, bit1=Shift, bit2=Alt. */
    public static final int MOD_CTRL = 1, MOD_SHIFT = 2, MOD_ALT = 4;

    /** 序列步数上限。 / Max steps per sequence. */
    public static final int MAX_STEPS = 4;

    /** 序列步骤：GLFW 键码 + 修饰位掩码。 / A sequence step: GLFW keycode + modifier mask. */
    public record Step(int key, int mods) {}

    private static final int ACTION_COUNT = Action.values().length;

    /** 每动作一条触发序列（步骤 = 按键或鼠标点击，鼠标步为负键码）。
     *  One trigger sequence per action (steps = key presses or mouse clicks; mouse
     *  steps encode as negative keycodes). */
    private static final java.util.List<java.util.List<Step>> sequences = new java.util.ArrayList<>(ACTION_COUNT);

    // ── 序列匹配引擎状态 / sequence matcher state ──
    /** 纯键域缓冲（全键序列的前缀推进）。 / Pure-key domain buffer (prefix progress of all-key sequences). */
    private static final java.util.List<Step> buffer = new java.util.ArrayList<>();
    /** 混合域缓冲（含鼠标步序列的推进，keyPressed 与 feedClick 共同推进）。
     *  Mixed-domain buffer (progress of mouse-step sequences, advanced by both keyPressed and feedClick). */
    private static final java.util.List<Step> mouseBuffer = new java.util.ArrayList<>();
    /** 上次喂入时间戳（超时作废用）。 / Timestamp of the last feed (for the timeout). */
    private static long lastFeedMs = 0L;
    /** 序列缓冲超时：间隔超过它缓冲作废，防止久远的按键意外接龙。 / Buffer timeout: stale buffers are discarded. */
    public static final long SEQUENCE_TIMEOUT_MS = 1500;

    /** 默认触发 —— 与历代出厂行为一致（删除连线 = 老组合 Tab → 左键）。
     *  取值经 defaultSequence 的穷举 switch 提供：编译器强制穷举所有枚举值，
     *  新增动作漏配默认触发是编译错误，而不是 ordinal 错位导致的静默错绑。
     *  Defaults — identical to the historical factory behaviour (delete-wire = the
     *  legacy Tab → left-click chord). Values come from defaultSequence's exhaustive
     *  switch: the compiler enforces exhaustiveness, so adding an action without a
     *  default is a compile error, never a silent mis-mapping via ordinals. */
    static {
        for (var a : Action.values()) {
            sequences.add(defaultSequence(a));
        }
        load();
    }

    /** 鼠标步编码：负键码，button → {@code -1 - button}（0=左 → -1，1=右 → -2，2=中 → -3）；
     *  {@link #stepMouseButton} 反解。属性文件里即 "-1,0" 这类负值，原样往返。
     *  Mouse-step encoding: a negative keycode, button → {@code -1 - button} (0=L → -1,
     *  1=R → -2, 2=M → -3); {@link #stepMouseButton} reverses it. Persisted verbatim as
     *  negative values like "-1,0". */
    public static int mouseStepKey(int button) { return -1 - button; }

    /** 鼠标步的按钮索引（非鼠标步返回 -1）。 / The button of a mouse step (-1 for key steps). */
    public static int stepMouseButton(Step step) { return step.key() < 0 ? -1 - step.key() : -1; }

    /** 序列是否含鼠标步（混合域归属判定）。 / Whether the sequence contains a mouse step (mixed-domain membership). */
    public static boolean hasMouseStep(java.util.List<Step> seq) {
        for (var st : seq) if (st.key() < 0) return true;
        return false;
    }

    /** 出厂触发序列：平移=左键拖动、菜单=右键（单鼠标步）、删除连线=Tab → 左键
     *  （老组合）；其余键盘动作 = 旧硬编码单步序列。
     *  Factory trigger sequences: pan = left-drag, menu = right-click (single mouse
     *  steps), delete-wire = Tab → left-click (the legacy chord); the other keyboard
     *  actions = the old hardcode single-step sequences. */
    private static java.util.List<Step> defaultSequence(Action a) {
        return switch (a) {
            case PAN -> java.util.List.of(new Step(mouseStepKey(0), 0));            // 左键拖动图 / left-drag pan
            case CONTEXT_MENU -> java.util.List.of(new Step(mouseStepKey(1), 0));   // 右键菜单 / right-click menu
            case DELETE_WIRE -> java.util.List.of(new Step(258, 0), new Step(mouseStepKey(0), 0)); // Tab → 左键（老组合）/ Tab → left-click (legacy chord)
            case DELETE_NODE -> java.util.List.of(new Step(88, 0));          // X
            case DELETE_SELECTED -> java.util.List.of(new Step(261, 0));     // Delete
            case UNDO -> java.util.List.of(new Step(90, MOD_CTRL));          // Ctrl+Z
            case REDO -> java.util.List.of(new Step(89, MOD_CTRL));          // Ctrl+Y
            case DUPLICATE -> java.util.List.of(new Step(68, MOD_CTRL));     // Ctrl+D
            case RESET_VIEW -> java.util.List.of(new Step(268, 0));          // Home
            case SAVE_BOOKMARK -> java.util.List.of(new Step(77, MOD_CTRL)); // Ctrl+M
            case BOX_SELECT -> java.util.List.of(new Step(258, 0));          // Tab
            // 像素编辑器出厂键 = 原 PS 式硬编码 / pixel editor factory keys = the old PS-style hardcodes
            case PIXEL_BRUSH -> java.util.List.of(new Step(66, 0));          // B
            case PIXEL_ERASER -> java.util.List.of(new Step(69, 0));         // E
            case PIXEL_FILL -> java.util.List.of(new Step(70, 0));           // F
            case PIXEL_EYEDROPPER -> java.util.List.of(new Step(73, 0));     // I
            case PIXEL_LINE -> java.util.List.of(new Step(76, 0));           // L
            case PIXEL_RECT -> java.util.List.of(new Step(82, 0));           // R
            case PIXEL_HAND -> java.util.List.of(new Step(72, 0));           // H
            case PIXEL_GRID -> java.util.List.of(new Step(71, 0));           // G
            // [ ] 用 GLFW 键码 91/93 —— 旧硬编码的 219/221 是 Windows VK 码，经 GLFW
            // keyPressed 永远不命中（[ ] 快捷键此前形同虚设）。
            // [ / ] use the GLFW codes 91/93 - the old hardcode's 219/221 were Windows VK
            // codes that GLFW's keyPressed never delivers, so those shortcuts never fired.
            case PIXEL_BRUSH_SMALLER -> java.util.List.of(new Step(91, 0));  // [
            case PIXEL_BRUSH_BIGGER -> java.util.List.of(new Step(93, 0));   // ]
        };
    }

    private EditorKeys() {}

    // ── 查询 / queries ──────────────────────────────────────────────────

    /** 动作当前绑定的序列（只读；未绑键盘返回空表）。PAN 的序列即按住组合键（至多一步）。
     *  The action's bound sequence (read-only; empty when unbound). PAN's sequence is
     *  its hold combo (at most one step). */
    public static java.util.List<Step> sequence(Action a) { return sequences.get(a.ordinal()); }

    /** 纯键域缓冲是否在等待后续步骤（true 时刚喂入的键已被引擎消费）。
     *  Whether the pure-key buffer is waiting for more steps (keys are consumed then). */
    public static boolean bufferActive() { return !buffer.isEmpty(); }

    /** 混合域缓冲是否在等待后续步骤（含鼠标步序列的键步已入缓冲）。
     *  Whether the mixed buffer is waiting for more steps (a mouse-step sequence's key step is armed). */
    public static boolean mixedBufferActive() { return !mouseBuffer.isEmpty(); }

    /** 清空序列缓冲（PAN 按住键被手势拦截时由 GraphEditor 调用 —— 该键被组合消费，
     *  不参与序列推进，缓冲里的半截连招就此作废）。
     *  Clears the matcher buffer (called by GraphEditor when the PAN hold-key is
     *  intercepted by the gesture — the key never advances sequences, so any partial
     *  combo in the buffer is voided). */
    public static void clearBuffer() { buffer.clear(); }

    /** (key, mods) 是否命中 PAN 的按住组合键（修饰包含语义，与 {@link #stepsMatch} 同：
     *  裸键绑定时任何修饰组合都命中）。未绑键盘返回 false。
     *  Does (key, mods) hit PAN's hold combo (modifier containment, same as
     *  {@link #stepsMatch}: a bare-key binding matches under any modifier combo)?
     *  False when PAN has no keyboard binding. */
    public static boolean matchesPanHold(int key, int mods) {
        var seq = sequence(Action.PAN);
        if (seq.size() != 1) return false;
        var st = seq.get(0);
        return st.key() == key && (mods & st.mods()) == st.mods();
    }

    /** key 是否 PAN 按住组合键的键（不看修饰 —— keyReleased 时修饰可能已先松开，
     *  且开始与结束必须对称）。 / Is key the PAN hold-combo's key (mods ignored — they
     *  may already be released at keyReleased, and start/stop must stay symmetric)? */
    public static boolean isPanHoldKey(int key) {
        var seq = sequence(Action.PAN);
        return seq.size() == 1 && seq.get(0).key() == key;
    }

    /** 修饰位掩码的可读文本（Ctrl+…），供设置界面显示。 / Readable modifier text for the settings UI. */
    public static String modsText(int mods) {
        StringBuilder sb = new StringBuilder();
        if ((mods & MOD_CTRL) != 0) sb.append("Ctrl+");
        if ((mods & MOD_SHIFT) != 0) sb.append("Shift+");
        if ((mods & MOD_ALT) != 0) sb.append("Alt+");
        return sb.toString();
    }

    /** 序列的可读文本（Ctrl+K → D、Tab → 左键；空 = —）。鼠标步经 mouseNames
     *  解析（按钮索引 → 显示名，UI 传 I18n 标签）。
     *  Readable sequence text (Ctrl+K → D, Tab → left-click; empty = —). Mouse steps
     *  resolve through mouseNames (button index → label; the UI passes I18n). */
    public static String seqText(java.util.List<Step> seq, java.util.function.IntFunction<String> mouseNames) {
        if (seq.isEmpty()) return "—";
        var sb = new StringBuilder();
        for (var st : seq) {
            if (sb.length() > 0) sb.append(" → ");
            if (st.key() < 0) { sb.append(mouseNames.apply(stepMouseButton(st))); continue; }
            sb.append(modsText(st.mods())).append(keyName(st.key()));
        }
        return sb.toString();
    }

    /** 序列的可读文本（鼠标步渲染为 M0/M1/M2——供无 I18n 环境；UI 用带解析器重载）。
     *  Readable sequence text (mouse steps render as M0/M1/M2 for I18n-free callers;
     *  the UI uses the resolving overload). */
    public static String seqText(java.util.List<Step> seq) {
        return seqText(seq, b -> "M" + b);
    }

    /** GLFW 键码的可读名（设置界面显示用，覆盖虚拟键盘全部键帽）。 / Readable name for a GLFW keycode (settings UI; covers every virtual cap). */
    public static String keyName(int k) {
        if (k >= 65 && k <= 90) return String.valueOf((char) ('A' + (k - 65)));
        if (k >= 48 && k <= 57) return String.valueOf((char) ('0' + (k - 48)));
        return switch (k) {
            case 256 -> "Esc"; case 257 -> "Enter"; case 258 -> "Tab"; case 259 -> "Backspace";
            case 260 -> "Ins"; case 261 -> "Del"; case 263 -> "Left"; case 262 -> "Right";
            case 265 -> "Up"; case 264 -> "Down"; case 266 -> "PgUp"; case 267 -> "PgDn";
            case 268 -> "Home"; case 269 -> "End";
            case 32 -> "Space"; case 280 -> "Caps";
            case 39 -> "'"; case 44 -> ","; case 45 -> "-"; case 46 -> "."; case 47 -> "/";
            case 59 -> ";"; case 61 -> "="; case 91 -> "["; case 92 -> "\\"; case 93 -> "]"; case 96 -> "`";
            case 340, 344 -> "Shift"; case 341, 345 -> "Ctrl"; case 342, 346 -> "Alt";
            default -> "Key " + k;
        };
    }

    // ── 序列匹配引擎 / sequence matcher ─────────────────────────────────

    /** 喂入一次键盘事件（仅在画布交互态调用 —— 输入框聚焦时不要喂）。
     *  匹配规则：缓冲 + 本键完整命中某动作序列 → 触发并清空；是某绑定的真前缀 →
     *  缓冲等待；否则清空缓冲并把本键当作新首步重新评估（vim 式重开）。
     *  距上次喂入超过 {@link #SEQUENCE_TIMEOUT_MS} 时缓冲先作废。
     *  返回触发的动作；用 {@link #bufferActive()}/{@link #mixedBufferActive()}
     *  区分「前缀等待（应消费按键）」与「无关键（可继续传给后续处理）」。
     *  Feed one keyboard event (canvas-interaction state only — do not feed while an
     *  input field is focused). Advances the mixed domain first (key steps of
     *  mouse-step sequences), then the pure-key domain by the classic rules: a full
     *  match fires and clears; a true prefix buffers; a miss clears the buffer and
     *  re-evaluates this key as a new first step (vim-style restart). Buffers older
     *  than {@link #SEQUENCE_TIMEOUT_MS} are discarded first. Returns the triggered
     *  action; use {@link #bufferActive()}/{@link #mixedBufferActive()} to tell
     *  "prefix waiting (consume the key)" from "irrelevant key". */
    public static Action feedKey(int glfwKey, int mods, long nowMs) {
        if (nowMs - lastFeedMs > SEQUENCE_TIMEOUT_MS) { buffer.clear(); mouseBuffer.clear(); }
        lastFeedMs = nowMs;
        // 混合域先行（键步）——纯键域的命中/前缀不得打断其缓冲：出厂 Tab 按键触发
        // 框选的同时为删线组合入缓冲即依赖此序。
        // The mixed domain advances first (key step) — the pure-key domain's fires and
        // prefixes must never disturb its buffer: the factory Tab press firing
        // box-select while arming the wire chord depends on this order.
        Action mixedHit = mixedAdvance(new Step(glfwKey, mods));
        if (mixedHit != null) return mixedHit;
        var candidate = new java.util.ArrayList<Step>(buffer);
        candidate.add(new Step(glfwKey, mods));
        Action hit = fullMatch(candidate);
        if (hit != null) { buffer.clear(); return hit; }
        if (isPrefix(candidate)) { buffer.clear(); buffer.addAll(candidate); return null; }
        // 重开：清缓冲，本键作为新首步重新评估（可能立即触发单步绑定）。
        // Restart: clear, re-evaluate this key alone (a single-step binding may fire).
        buffer.clear();
        var solo = java.util.List.of(new Step(glfwKey, mods));
        hit = fullMatch(solo);
        if (hit != null) return hit;
        if (isPrefix(solo)) buffer.add(new Step(glfwKey, mods));
        return null;
    }

    /** 候选是否完整命中某动作的<b>纯键</b>序列（等长 + 逐位键相等且实际修饰 ⊇ 绑定修饰）。
     *  含鼠标步的序列在混合域完成；PAN 的键步是按住抓图、由手势拦截，均不在此触发。
     *  Does the candidate fully match some action's <b>pure-key</b> sequence (equal
     *  length, per-step key equality and actual mods ⊇ bound mods)? Sequences with
     *  mouse steps complete in the mixed domain; PAN's key step is the hold-grab
     *  intercepted by the gesture — neither fires here. */
    private static Action fullMatch(java.util.List<Step> candidate) {
        for (var a : Action.values()) {
            if (a == Action.PAN || sequence(a).isEmpty() || hasMouseStep(sequence(a))) continue;
            if (stepsMatch(candidate, sequence(a))) return a;
        }
        return null;
    }

    /** 候选是否为某纯键序列的真前缀（长度更短 + 前缀逐位匹配；混合域序列不参与——
     *  其键步在混合域缓冲）。
     *  Is the candidate a proper prefix of some pure-key sequence (mixed-domain
     *  sequences excluded — their key steps buffer in the mixed domain)? */
    private static boolean isPrefix(java.util.List<Step> candidate) {
        for (var a : Action.values()) {
            if (hasMouseStep(sequence(a))) continue;
            var seq = sequence(a);
            if (seq.size() > candidate.size() && stepsMatch(candidate, seq.subList(0, candidate.size()))) return true;
        }
        return false;
    }

    /** 喂入一次鼠标点击（混合域的鼠标步）：完整命中某含鼠标步的序列 → 触发并清缓冲；
     *  前缀推进缓冲；未中以本步重开（某混合序列以此按钮为首步才入缓冲）。PAN 不参与
     *  （其按钮属拖拽管线）。仅在画布交互态调用；修饰位取点击时的 Screen.has*Down，
     *  由调用方折算为修饰掩码传入。
     *  Feed one mouse click (a mixed-domain mouse step): a full match of some
     *  mouse-step sequence fires and clears; a prefix buffers; a miss restarts with
     *  this step (buffered only if some mixed sequence starts with this button). PAN
     *  never participates (its button belongs to the drag pipeline). Call in
     *  canvas-interaction state only; mods from Screen.has*Down at click time, folded
     *  into the mask by the caller. */
    public static Action feedClick(int button, int mods, long nowMs) {
        if (nowMs - lastFeedMs > SEQUENCE_TIMEOUT_MS) { buffer.clear(); mouseBuffer.clear(); }
        lastFeedMs = nowMs;
        return mixedAdvance(new Step(mouseStepKey(button), mods));
    }

    /** 混合域推进一步（键步或鼠标步均可）：完整命中触发并清缓冲；前缀缓冲等待；
     *  未中则清缓冲、以本步重开（某混合序列以此为首步才入缓冲）。PAN 不参与。
     *  Advances the mixed domain by one step (key or mouse alike): a full match fires
     *  and clears; a prefix buffers; a miss clears and restarts with this step
     *  (buffered only if some mixed sequence starts with it). PAN never participates. */
    private static Action mixedAdvance(Step event) {
        var candidate = new java.util.ArrayList<Step>(mouseBuffer);
        candidate.add(event);
        Action hit = mixedFullMatch(candidate);
        if (hit != null) { mouseBuffer.clear(); return hit; }
        if (mixedIsPrefix(candidate)) { mouseBuffer.clear(); mouseBuffer.addAll(candidate); return null; }
        mouseBuffer.clear();
        var solo = java.util.List.of(event);
        hit = mixedFullMatch(solo);
        if (hit != null) return hit;
        if (mixedIsPrefix(solo)) mouseBuffer.add(event);
        return null;
    }

    /** 候选是否完整命中某混合序列。 / Does the candidate fully match some mixed sequence? */
    private static Action mixedFullMatch(java.util.List<Step> candidate) {
        for (var a : Action.values()) {
            if (a == Action.PAN || !hasMouseStep(sequence(a))) continue;
            if (stepsMatch(candidate, sequence(a))) return a;
        }
        return null;
    }

    /** 候选是否为某混合序列的真前缀。 / Is the candidate a proper prefix of some mixed sequence? */
    private static boolean mixedIsPrefix(java.util.List<Step> candidate) {
        for (var a : Action.values()) {
            if (a == Action.PAN) continue;
            var seq = sequence(a);
            if (!hasMouseStep(seq)) continue;
            if (seq.size() > candidate.size() && stepsMatch(candidate, seq.subList(0, candidate.size()))) return true;
        }
        return false;
    }

    /** 逐位匹配：键相等且实际修饰位包含绑定修饰位（旧单键 matchesKey 同语义 ——
     *  X / Home 不看修饰，Ctrl+K 步在 Ctrl+Shift+K 按下时同样命中；鼠标步比按钮）。
     *  Per-step match: key equality and containment (same semantics as the old
     *  single-key matcher — X / Home ignore mods, a Ctrl+K step also fires under
     *  Ctrl+Shift+K; mouse steps compare buttons). */
    private static boolean stepsMatch(java.util.List<Step> actual, java.util.List<Step> bound) {
        if (actual.size() != bound.size()) return false;
        for (int i = 0; i < actual.size(); i++) {
            var p = actual.get(i);
            var b = bound.get(i);
            if (p.key() != b.key() || (p.mods() & b.mods()) != b.mods()) return false;
        }
        return true;
    }

    // ── 重绑 / rebinding ────────────────────────────────────────────────

    /** 该动作是否开放鼠标步：图编辑器 10 个动作（平移/菜单/删除节点/删除选中/删除连线/
     *  撤销/重做/复制/重置视角/保存书签）。BOX_SELECT 的按住语义与像素编辑器的
     *  画笔/擦除独占鼠标——它们的序列只允许键步。
     *  Whether the action accepts mouse steps: the 10 graph-editor actions (pan / menu /
     *  delete node / delete selected / delete wire / undo / redo / duplicate / reset
     *  view / save bookmark). BOX_SELECT's hold semantics and the pixel editor's
     *  painting own the mouse — their sequences take key steps only. */
    public static boolean mouseStepAllowed(Action a) {
        return a == Action.PAN || a == Action.CONTEXT_MENU || a == Action.DELETE_NODE
            || a == Action.DELETE_SELECTED || a == Action.DELETE_WIRE || a == Action.UNDO
            || a == Action.REDO || a == Action.DUPLICATE || a == Action.RESET_VIEW
            || a == Action.SAVE_BOOKMARK;
    }

    /** PAN 序列的鼠标步按钮（单鼠标步 = 该按钮拖拽平移；键步 / 异常态 -1）。
     *  The button of PAN's mouse step (a single mouse step = drag-pan on that button;
     *  -1 for key steps / malformed). */
    public static int panMouseButton() {
        var seq = sequence(Action.PAN);
        return seq.size() == 1 ? stepMouseButton(seq.get(0)) : -1;
    }

    /** PAN 是否为按住抓图（序列为单键步；true 时按钮拖动平移停用——抓图是该动作
     *  唯一触发）。
     *  Whether PAN is a hold-grab (its sequence is a single key step; true disables
     *  button-drag panning — the grab is the action's only trigger). */
    public static boolean panKeyBound() {
        var seq = sequence(Action.PAN);
        return seq.size() == 1 && seq.get(0).key() >= 0;
    }

    /** 替换动作的触发序列（该动作的唯一触发，整体替换）。校验：步数 1..
     *  {@value #MAX_STEPS}（PAN 恒单步）；鼠标步仅 {@link #mouseStepAllowed} 动作
     *  可用；PAN 抓图键对<b>所有</b>序列的键步独占（双向——该键被手势拦截、不进引擎，
     *  任何序列用到都无法完成）；<b>同域</b>前缀歧义拒绝（纯键域与混合域各自校验，
     *  跨域事件流不同可共存——出厂 BOX_SELECT=Tab 纯键域与 DELETE_WIRE=Tab → 左键
     *  混合域并存即此）。拒绝时数据不动。
     *  Replace an action's trigger sequence (the action's single trigger, wholesale).
     *  Checks: 1..{@value #MAX_STEPS} steps (PAN always single-step); mouse steps only
     *  for {@link #mouseStepAllowed} actions; PAN's grab key is exclusive against the
     *  key steps of <b>all</b> sequences (both ways — the intercepted key never
     *  reaches the engine, dead-ending any sequence that uses it); <b>same-domain</b>
     *  prefix ambiguity refused (the pure-key and mixed domains validate separately;
     *  cross-domain event streams coexist — the factory BOX_SELECT=Tab in the key
     *  domain and DELETE_WIRE=Tab → left-click in the mixed domain ship side by side).
     *  Data untouched on refusal. */
    public static boolean setSequence(Action a, java.util.List<Step> seq) {
        if (seq == null || seq.isEmpty() || seq.size() > MAX_STEPS) return false;
        if (a == Action.PAN && seq.size() != 1) return false;
        if (hasMouseStep(seq) && !mouseStepAllowed(a)) return false;
        // PAN 抓图键独占（双向、所有序列的键步） / PAN grab-key exclusivity (both ways, the key steps of all sequences)
        int panKey = panHoldKey();
        if (a != Action.PAN && panKey >= 0) {
            for (var st : seq) if (st.key() == panKey) return false;
        } else if (a == Action.PAN) {
            for (var other : Action.values()) {
                if (other == a) continue;
                for (var st : sequence(other)) if (st.key() == seq.get(0).key()) return false;
            }
        }
        // 同域前缀歧义（跨域事件流不同、可共存） / same-domain prefix ambiguity (cross-domain coexists)
        boolean mixed = hasMouseStep(seq);
        for (var other : Action.values()) {
            if (other == a || sequence(other).isEmpty() || hasMouseStep(sequence(other)) != mixed) continue;
            if (prefixAmbiguous(seq, sequence(other))) return false;
        }
        sequences.set(a.ordinal(), java.util.List.copyOf(seq));
        buffer.clear();
        mouseBuffer.clear();
        save();
        return true;
    }

    /** PAN 按住组合键当前绑定的键码（未绑 / 异常态返回 -1）。供 GraphEditor 在
     *  mouseMoved 轮询物理键态（keyReleased 可能因切屏 / 失焦丢失，轮询自愈卡死平移）。
     *  The keycode bound to PAN's hold combo (-1 when unbound / malformed). GraphEditor
     *  polls the physical key state with it in mouseMoved (keyReleased can be lost when
     *  switching screens or on focus loss; the poll heals a stuck pan). */
    public static int panHoldKey() {
        var seq = sequence(Action.PAN);
        return seq.size() == 1 ? seq.get(0).key() : -1;
    }

    /** 框选模式按住键（BOX_SELECT 绑定序列末步的键，默认 Tab）。「框选键+左键点连线 =
     *  删除悬停连线」老组合的键半边随它走，设置界面据此展示组合。
     *  The box-select hold key (last step of the BOX_SELECT binding, Tab by default).
     *  The key half of the legacy "box-select key + left-click deletes the hovered
     *  wire" chord follows it; the settings tab displays the chord from this. */
    public static int boxSelectHoldKey() {
        var seq = sequence(Action.BOX_SELECT);
        return seq.isEmpty() ? 258 : seq.get(seq.size() - 1).key();
    }

    /** 两条绑定序列是否事件歧义。
     *  等长且逐位键相同：任意修饰组合的并集事件都会同时命中两条（引擎按枚举序只触发
     *  一条，另一条永远失效）→ 必须拒绝，修饰位不影响判定。
     *  不等长：短者是长者的键前缀且<b>短者每步修饰 ⊆ 长者对应步</b>时歧义 —— 完成长者
     *  的任何按键事件都先在短者处触发；若存在更宽的修饰事件（长者步修饰 ⊋ 短者步修饰），
     *  两者可按修饰状态区分、允许共存（如 Ctrl+D 单步 与 D→K 连招）。
     *  Do two bound sequences create event ambiguity?
     *  Equal length with per-step equal keys: the union-modifier event matches both
     *  (the engine fires only the enum-first one, leaving the other dead) → always
     *  ambiguous, mods irrelevant. Different length: the shorter is a key-prefix of the
     *  longer AND its per-step mods are contained in the longer's — otherwise the two
     *  are distinguishable by modifier state and may coexist (e.g. a Ctrl+D single step
     *  vs a D→K combo). */
    private static boolean prefixAmbiguous(java.util.List<Step> s1, java.util.List<Step> s2) {
        int n = Math.min(s1.size(), s2.size());
        if (n == 0) return false;
        List<Step> shorter = s1.size() <= s2.size() ? s1 : s2;
        List<Step> longer = shorter == s1 ? s2 : s1;
        boolean sameLength = s1.size() == s2.size();
        for (int i = 0; i < n; i++) {
            Step p = shorter.get(i), q = longer.get(i);
            if (p.key() != q.key()) return false;
            if (!sameLength && (p.mods() & q.mods()) != p.mods()) return false; // 修饰可区分 / distinguishable by mods
        }
        return true;
    }

    /** 恢复单个动作为默认绑定。 / Restore one action to its default binding. */
    public static void resetToDefault(Action a) {
        // 与静态初始化共用 defaultSequence 查表 —— 单一数据来源，无 ordinal 硬编码。
        // Shares defaultSequence with the static initializer — one source of truth,
        // no hard-coded ordinals.
        sequences.set(a.ordinal(), defaultSequence(a));
        buffer.clear();
        mouseBuffer.clear();
        save();
    }

    // ── 持久化 / persistence ────────────────────────────────────────────

    private static String CONFIG_PATH = "config/create_schematic_compute-client.properties";
    private static final String PREFIX = "editorKeys.";

    /** 测试专用：重定向配置文件路径（生产代码勿用）。 / Test-only: redirect the config path. */
    static void setConfigPathForTest(String path) { CONFIG_PATH = path; }

    /** 测试专用：重置为默认表并从当前路径重载。 / Test-only: reset to defaults and reload. */
    static void reloadForTest() {
        for (var a : Action.values()) sequences.set(a.ordinal(), defaultSequence(a));
        buffer.clear();
        mouseBuffer.clear();
        load();
    }

    private static void load() {
        try {
            var props = new Properties();
            var path = java.nio.file.Path.of(CONFIG_PATH);
            if (java.nio.file.Files.exists(path))
                try (var in = java.nio.file.Files.newInputStream(path)) { props.load(in); }
            // 格式标记：v2 = 单触发组合键模型（写入前经过完整的同类冲突校验）。旧版
            // 保存从不写标记，「无标记」是现网配置的常态而非脏表——seq 键序值照常
            // 读取、原样保留（历史脏数据只在 .mouse 槽字段，本就不读；更老的
            // key/mods 配置没有 seq 值，自然落回出厂）。只有出现未知的新标记值才跳过
            // 读取（向前兼容，防 v3 语法被当 v2 误解析），首次保存写回标记。
            // Format marker: v2 = the one-trigger combo model (every write passed the
            // full same-kind clash checks). The old save() never wrote a marker, so a
            // missing marker is the norm for real-world configs, not a dirty table —
            // seq values are read and carried over verbatim (the historical dirty data
            // lived only in the .mouse slot fields, which are never read; older
            // key/mods configs hold no seq values and fall back to the factory
            // defaults). Only an unknown future marker skips the read (forward compat,
            // so v3 syntax is never mis-parsed as v2); the marker is written back on
            // the first save.
            String format = props.getProperty(PREFIX + "format");
            if (format != null && !"2".equals(format)) return;
            for (var a : Action.values()) {
                // 统一序列模型：触发全在 seq（负键码 = 鼠标步），旧 .mouse 字段不再读取
                // （保存时移除）。
                // Unified sequence model: the trigger lives entirely in seq (negative
                // keycodes = mouse steps); the legacy .mouse field is no longer read
                // (and is removed on save).
                List<Step> seq = parseSeq(props.getProperty(PREFIX + a.name() + ".seq"));
                if (seq != null && !seq.isEmpty()) sequences.set(a.ordinal(), seq);
            }
        } catch (Exception e) {
            // 读取失败即用默认表 —— 配置损坏不应导致编辑器不可用。
            // On a read failure keep the defaults; a broken config must not brick the editor.
        }
    }

    /** 解析 "key,mods;key,mods" 序列编码；无 / 损坏 / 超长返回 null。
     *  Parses the "key,mods;key,mods" encoding; null when absent/corrupt/over-long. */
    private static java.util.List<Step> parseSeq(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            var parts = s.split(";");
            if (parts.length == 0 || parts.length > MAX_STEPS) return null;
            var out = new java.util.ArrayList<Step>(parts.length);
            for (var p : parts) {
                var kv = p.split(",");
                out.add(new Step(Integer.parseInt(kv[0]), kv.length > 1 ? Integer.parseInt(kv[1]) : 0));
            }
            return out.isEmpty() ? null : List.copyOf(out);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void save() {
        try {
            var props = new Properties();
            var path = java.nio.file.Path.of(CONFIG_PATH);
            if (java.nio.file.Files.exists(path))
                try (var in = java.nio.file.Files.newInputStream(path)) { props.load(in); }
            // 格式标记（v2 = 单触发组合键模型，读取方据此信任整表）。
            // The format marker (v2 = the one-trigger combo model; readers trust the
            // table only when it is present).
            props.setProperty(PREFIX + "format", "2");
            for (var a : Action.values()) {
                String base = PREFIX + a.name() + ".";
                var sb = new StringBuilder();
                for (var st : sequence(a)) {
                    if (sb.length() > 0) sb.append(';');
                    sb.append(st.key()).append(',').append(st.mods());
                }
                props.setProperty(base + "seq", sb.toString());
                // 旧键移除（key/mods = 最早的旧格式；mouse = 统一序列模型前的鼠标槽）。
                // Stale keys removed (key/mods = the oldest format; mouse = the pre-model
                // mouse slot).
                props.remove(base + "key");
                props.remove(base + "mods");
                props.remove(base + "mouse");
            }
            try (var out = java.nio.file.Files.newOutputStream(path)) { props.store(out, "Editor key bindings"); }
        } catch (Exception ignored) {
            // 写失败不打断编辑 —— 下次改动会再试。 / A failed write must not interrupt editing.
        }
    }
}
