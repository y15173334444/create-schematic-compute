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
 * <p>鼠标动作不参与序列（拖动/按住语义与连招不兼容），保持单键绑定。
 * Mouse actions stay single-button (drag/hold semantics don't fit sequences).
 *
 * <p>冲突规则：等长且逐位键相同的两条键盘序列必歧义（并集修饰事件会同时命中，引擎
 * 只触发枚举序靠前的一条，另一条永远失效）→ 拒绝；不等长时，短者是长者的键前缀且
 * 短者每步修饰 ⊆ 长者对应步才歧义（完成长者的任何按键都会先在短者处触发）——否则
 * 两者可按修饰状态区分、允许共存（如 Ctrl+D 单步 与 D→K 连招）。鼠标动作之间按键唯一。
 * 跨类冲突（鼠标/键盘走不同事件流）允许。
 * Conflict rule: two equal-length keyboard sequences with per-step equal keys are
 * always ambiguous (the union-modifier event matches both; the engine fires only the
 * enum-first, leaving the other dead) → refused. For different lengths, the shorter
 * must be a key-prefix of the longer with its per-step mods contained in the longer's
 * (any completion of the longer would fire the shorter first) — otherwise they are
 * distinguishable by modifier state and may coexist (a Ctrl+D single step vs a D→K
 * combo). Mouse actions need distinct buttons. Cross-kind clashes are allowed.
 *
 * <p>持久化复用既有客户端配置文件
 * {@code config/create_schematic_compute-client.properties}
 * （与 NodeRenderer 的颜色 / 网格吸附 / 工具栏位置同一机制）。新格式
 * {@code editorKeys.<动作>.seq = "键,修饰;键,修饰"}；旧的单键 {@code key/mods} 在读取时
 * 回退为单步序列，存量配置无损迁移。
 * Persisted into the existing client config file, same mechanism NodeRenderer uses.
 * New format {@code editorKeys.<ACTION>.seq = "key,mods;key,mods"}; the legacy single
 * {@code key/mods} entries fall back to a single-step sequence on read, so existing
 * configs migrate losslessly.
 */
public final class EditorKeys {

    /** 可绑定的编辑器动作。 / Bindable editor actions. */
    public enum Action {
        /** 平移画布（拖动图） / pan the canvas (drag the graph) */
        PAN(true, "editorkeys.pan"),
        /** 打开画布上下文菜单 / open the canvas context menu */
        CONTEXT_MENU(true, "editorkeys.context_menu"),
        /** 删除悬停节点 / delete the hovered node */
        DELETE_NODE(false, "editorkeys.delete"),
        /** 删除选中节点（原 Backspace/Delete 硬编码，现默认 Delete） / delete the selected nodes (was hardcoded to Backspace/Delete; default is Delete now) */
        DELETE_SELECTED(false, "editorkeys.delete_selected"),
        /** 撤销 / undo */
        UNDO(false, "editorkeys.undo"),
        /** 重做 / redo */
        REDO(false, "editorkeys.redo"),
        /** 复制选中 / duplicate selection */
        DUPLICATE(false, "editorkeys.duplicate"),
        /** 重置视角 / reset the view */
        RESET_VIEW(false, "editorkeys.reset_view"),
        /** 保存视角书签 / save a view bookmark */
        SAVE_BOOKMARK(false, "editorkeys.save_bookmark"),
        /** 框选多选（按住生效；原 Tab 硬编码） / box-select mode (held; was hardcoded to Tab) */
        BOX_SELECT(false, "editorkeys.box_select");

        /** true = 鼠标动作（绑定的是按键索引），false = 键盘动作（绑定键序列）。
         *  true = mouse action (bound to a button index); false = keyboard action
         *  (bound to a key sequence). */
        public final boolean mouse;
        /** lang 键（设置界面显示用，渲染时补 gui.create_schematic_compute. 前缀） / lang key (prefixed at render time). */
        public final String langKey;

        Action(boolean mouse, String langKey) { this.mouse = mouse; this.langKey = langKey; }
    }

    /** 修饰位掩码（与 GLFW_KEY_MOD_* 的语义对应，取值即 bit0=Ctrl bit1=Shift bit2=Alt）。
     *  Modifier mask bits: bit0=Ctrl, bit1=Shift, bit2=Alt. */
    public static final int MOD_CTRL = 1, MOD_SHIFT = 2, MOD_ALT = 4;

    /** 序列步数上限。 / Max steps per sequence. */
    public static final int MAX_STEPS = 4;

    /** 序列步骤：GLFW 键码 + 修饰位掩码。 / A sequence step: GLFW keycode + modifier mask. */
    public record Step(int key, int mods) {}

    private static final int ACTION_COUNT = Action.values().length;

    /** 鼠标动作的按键索引（0=左 1=右 2=中）。 / Mouse button index per mouse action (0=L, 1=R, 2=M). */
    private static final int[] mouseBindings = new int[ACTION_COUNT];
    /** 键盘动作的绑定序列（每动作一条；鼠标动作位为空表）。 / Bound sequence per keyboard action (empty slot for mouse actions). */
    private static final java.util.List<java.util.List<Step>> sequences = new java.util.ArrayList<>(ACTION_COUNT);

    // ── 序列匹配引擎状态 / sequence matcher state ──
    /** 等待后续步骤的缓冲。 / Buffer of steps waiting to continue. */
    private static final java.util.List<Step> buffer = new java.util.ArrayList<>();
    /** 上次喂入时间戳（超时作废用）。 / Timestamp of the last feed (for the timeout). */
    private static long lastFeedMs = 0L;
    /** 序列缓冲超时：间隔超过它缓冲作废，防止久远的按键意外接龙。 / Buffer timeout: stale buffers are discarded. */
    public static final long SEQUENCE_TIMEOUT_MS = 1500;

    /** 默认绑定 —— 与重构前硬编码行为逐项一致（单步序列表达旧单键绑定）。
     *  取值经 defaultXxx 的 switch 表达式提供：编译器强制穷举所有枚举值，
     *  新增动作漏配默认键是编译错误，而不是 ordinal 错位导致的静默错绑。
     *  Defaults — identical to the pre-refactor hardcodes, expressed as single-step
     *  sequences. Values come from the defaultXxx switch expressions: the compiler
     *  enforces exhaustiveness, so adding an action without a default is a compile
     *  error, never a silent mis-mapping via ordinals. */
    static {
        for (var a : Action.values()) {
            if (a.mouse) {
                mouseBindings[a.ordinal()] = defaultMouseButton(a);
                sequences.add(java.util.List.of());
            } else {
                sequences.add(java.util.List.of(new Step(defaultKeyCode(a), defaultKeyMods(a))));
            }
        }
        load();
    }

    /** 鼠标动作的出厂按键索引（-1 = 非鼠标动作）。
     *  Factory button index for a mouse action (-1 = not a mouse action). */
    private static int defaultMouseButton(Action a) {
        return switch (a) {
            case PAN -> 0;            // 左键拖动图 / left-drag pan
            case CONTEXT_MENU -> 1;   // 右键菜单 / right-click menu
            case DELETE_NODE, DELETE_SELECTED, UNDO, REDO, DUPLICATE, RESET_VIEW, SAVE_BOOKMARK, BOX_SELECT -> -1;
        };
    }

    /** 键盘动作的出厂键码（-1 = 非键盘动作）。 / Factory keycode for a keyboard action (-1 = not a keyboard action). */
    private static int defaultKeyCode(Action a) {
        return switch (a) {
            case DELETE_NODE -> 88;          // X
            case DELETE_SELECTED -> 261;     // Delete
            case UNDO -> 90;                 // Ctrl+Z
            case REDO -> 89;                 // Ctrl+Y
            case DUPLICATE -> 68;            // Ctrl+D
            case RESET_VIEW -> 268;          // Home
            case SAVE_BOOKMARK -> 77;        // Ctrl+M
            case BOX_SELECT -> 258;          // Tab
            case PAN, CONTEXT_MENU -> -1;
        };
    }

    /** 键盘动作的出厂修饰位。 / Factory modifier mask for a keyboard action. */
    private static int defaultKeyMods(Action a) {
        return switch (a) {
            case DELETE_NODE, DELETE_SELECTED, RESET_VIEW, BOX_SELECT -> 0;
            case UNDO, REDO, DUPLICATE, SAVE_BOOKMARK -> MOD_CTRL;
            case PAN, CONTEXT_MENU -> 0;
        };
    }

    private EditorKeys() {}

    // ── 查询 / queries ──────────────────────────────────────────────────

    /** 鼠标动作当前绑定的按键索引。 / Button index bound to a mouse action. */
    public static int mouseButton(Action a) { return mouseBindings[a.ordinal()]; }

    /** 键盘动作当前绑定的序列（只读；鼠标动作返回空表）。
     *  The keyboard action's bound sequence (read-only; empty for mouse actions). */
    public static java.util.List<Step> sequence(Action a) { return sequences.get(a.ordinal()); }

    /** 序列缓冲是否在等待后续步骤（true 时刚喂入的键已被引擎消费）。
     *  Whether the matcher buffer is waiting for more steps (keys are consumed then). */
    public static boolean bufferActive() { return !buffer.isEmpty(); }

    /** 修饰位掩码的可读文本（Ctrl+…），供设置界面显示。 / Readable modifier text for the settings UI. */
    public static String modsText(int mods) {
        StringBuilder sb = new StringBuilder();
        if ((mods & MOD_CTRL) != 0) sb.append("Ctrl+");
        if ((mods & MOD_SHIFT) != 0) sb.append("Shift+");
        if ((mods & MOD_ALT) != 0) sb.append("Alt+");
        return sb.toString();
    }

    // ── 序列匹配引擎 / sequence matcher ─────────────────────────────────

    /** 喂入一次键盘事件（仅在画布交互态调用 —— 输入框聚焦时不要喂）。
     *  匹配规则：缓冲 + 本键完整命中某动作序列 → 触发并清空；是某绑定的真前缀 →
     *  缓冲等待；否则清空缓冲并把本键当作新首步重新评估（vim 式重开）。
     *  距上次喂入超过 {@link #SEQUENCE_TIMEOUT_MS} 时缓冲先作废。
     *  返回触发的动作；用 {@link #bufferActive()} 区分「前缀等待（应消费按键）」
     *  与「无关键（可继续传给后续处理）」。
     *  Feed one keyboard event (canvas-interaction state only — do not feed while an
     *  input field is focused). A full match triggers and clears; a true prefix
     *  buffers; a miss clears the buffer and re-evaluates this key as a new first
     *  step (vim-style restart). Buffers older than {@link #SEQUENCE_TIMEOUT_MS} are
     *  discarded first. Returns the triggered action; use {@link #bufferActive()} to
     *  tell "prefix waiting (consume the key)" from "irrelevant key". */
    public static Action feedKey(int glfwKey, int mods, long nowMs) {
        if (nowMs - lastFeedMs > SEQUENCE_TIMEOUT_MS) buffer.clear();
        lastFeedMs = nowMs;
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

    /** 候选是否完整命中某动作序列（等长 + 逐位键相等且实际修饰 ⊇ 绑定修饰）。
     *  Does the candidate fully match some action's sequence (equal length, per-step
     *  key equality and actual mods ⊇ bound mods)? */
    private static Action fullMatch(java.util.List<Step> candidate) {
        for (var a : Action.values()) {
            if (a.mouse) continue;
            if (stepsMatch(candidate, sequence(a))) return a;
        }
        return null;
    }

    /** 候选是否为某动作序列的真前缀（长度更短 + 前缀逐位匹配）。
     *  Is the candidate a proper prefix of some action's sequence? */
    private static boolean isPrefix(java.util.List<Step> candidate) {
        for (var a : Action.values()) {
            if (a.mouse) continue;
            var seq = sequence(a);
            if (seq.size() > candidate.size() && stepsMatch(candidate, seq.subList(0, candidate.size()))) return true;
        }
        return false;
    }

    /** 逐位匹配：键相等且实际修饰位包含绑定修饰位（旧单键 matchesKey 同语义 ——
     *  X / Home 不看修饰，Ctrl+K 步在 Ctrl+Shift+K 按下时同样命中）。
     *  Per-step match: key equality and containment (same semantics as the old
     *  single-key matcher). */
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

    /** 重绑鼠标动作的按键。与其它鼠标动作冲突时返回 false 并拒绝（数据不动）。
     *  Rebind a mouse action. Returns false and refuses on a clash with another
     *  mouse action (data untouched). */
    public static boolean setMouseBinding(Action a, int button) {
        for (var other : Action.values())
            if (other != a && other.mouse && mouseBindings[other.ordinal()] == button) return false;
        mouseBindings[a.ordinal()] = button;
        save();
        return true;
    }

    /** 替换键盘动作的绑定序列。与其它动作的序列存在前缀歧义时返回 false 并拒绝
     *  （数据不动）—— 短者在长者前缀处就会命中，两者不能共存。
     *  Replace a keyboard action's sequence. Returns false and refuses on a prefix
     *  ambiguity with another action's sequence (data untouched) — the shorter would
     *  fire inside the longer, so they cannot coexist. */
    public static boolean setSequence(Action a, java.util.List<Step> seq) {
        if (a.mouse || seq == null || seq.isEmpty() || seq.size() > MAX_STEPS) return false;
        for (var other : Action.values())
            if (other != a && !other.mouse && prefixAmbiguous(seq, sequence(other))) return false;
        sequences.set(a.ordinal(), java.util.List.copyOf(seq));
        buffer.clear();
        save();
        return true;
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

    /** 两个修饰位掩码是否存在事件歧义：任一方完全包含另一方（子集关系）即视为冲突。
     *  Do two modifier masks create ambiguous events? Either one containing the other
     *  (a subset relation) counts as a clash. */
    private static boolean modsAmbiguous(int m1, int m2) {
        return (m1 & m2) == m1 || (m1 & m2) == m2;
    }

    /** 恢复单个动作为默认绑定。 / Restore one action to its default binding. */
    public static void resetToDefault(Action a) {
        // 与静态初始化共用 defaultXxx 查表 —— 单一数据来源，无 ordinal 硬编码。
        // Shares the defaultXxx lookups with the static initializer — one source of
        // truth, no hard-coded ordinals.
        if (a.mouse) mouseBindings[a.ordinal()] = defaultMouseButton(a);
        else sequences.set(a.ordinal(), java.util.List.of(new Step(defaultKeyCode(a), defaultKeyMods(a))));
        buffer.clear();
        save();
    }

    // ── 持久化 / persistence ────────────────────────────────────────────

    private static String CONFIG_PATH = "config/create_schematic_compute-client.properties";
    private static final String PREFIX = "editorKeys.";

    /** 测试专用：重定向配置文件路径（生产代码勿用）。 / Test-only: redirect the config path. */
    static void setConfigPathForTest(String path) { CONFIG_PATH = path; }

    /** 测试专用：重置为默认表并从当前路径重载。 / Test-only: reset to defaults and reload. */
    static void reloadForTest() {
        for (var a : Action.values()) {
            if (a.mouse) mouseBindings[a.ordinal()] = defaultMouseButton(a);
            else sequences.set(a.ordinal(), java.util.List.of(new Step(defaultKeyCode(a), defaultKeyMods(a))));
        }
        buffer.clear();
        load();
    }

    private static void load() {
        try {
            var props = new Properties();
            var path = java.nio.file.Path.of(CONFIG_PATH);
            if (java.nio.file.Files.exists(path))
                try (var in = java.nio.file.Files.newInputStream(path)) { props.load(in); }
            for (var a : Action.values()) {
                String base = PREFIX + a.name() + ".";
                if (a.mouse) {
                    String v = props.getProperty(base + "mouse");
                    if (v != null) try { mouseBindings[a.ordinal()] = Integer.parseInt(v); } catch (NumberFormatException ignored) {}
                } else {
                    List<Step> seq = parseSeq(props.getProperty(base + "seq"));
                    if (seq == null) {
                        // 旧格式回退：单键 key/mods → 单步序列（存量配置无损迁移）。
                        // Legacy fallback: single key/mods → single-step sequence.
                        String k = props.getProperty(base + "key");
                        String m = props.getProperty(base + "mods");
                        if (k != null) {
                            try { seq = List.of(new Step(Integer.parseInt(k), m != null ? Integer.parseInt(m) : 0)); }
                            catch (NumberFormatException ignored) {}
                        }
                    }
                    if (seq != null && !seq.isEmpty()) sequences.set(a.ordinal(), seq);
                }
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
            for (var a : Action.values()) {
                String base = PREFIX + a.name() + ".";
                if (a.mouse) {
                    props.setProperty(base + "mouse", String.valueOf(mouseBindings[a.ordinal()]));
                } else {
                    var sb = new StringBuilder();
                    for (var st : sequence(a)) {
                        if (sb.length() > 0) sb.append(';');
                        sb.append(st.key()).append(',').append(st.mods());
                    }
                    props.setProperty(base + "seq", sb.toString());
                    // 旧键移除（读取侧仍回退兼容旧版残留） / legacy keys removed (read side still falls back)
                    props.remove(base + "key");
                    props.remove(base + "mods");
                }
            }
            try (var out = java.nio.file.Files.newOutputStream(path)) { props.store(out, "Editor key bindings"); }
        } catch (Exception ignored) {
            // 写失败不打断编辑 —— 下次改动会再试。 / A failed write must not interrupt editing.
        }
    }
}
