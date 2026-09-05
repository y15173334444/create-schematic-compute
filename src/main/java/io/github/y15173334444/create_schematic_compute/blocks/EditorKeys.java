package io.github.y15173334444.create_schematic_compute.blocks;

import java.util.Properties;

/**
 * 编辑器画布交互的键位绑定表 —— 「动作 → 鼠标键 / 键盘键 + 修饰位」的单一来源。
 * Single source of truth for the editor's canvas-interaction bindings —
 * "action → mouse button / keyboard key + modifier mask".
 *
 * <p>为什么需要这张表：平移画布、右键菜单、删除/撤销等操作的按键此前全部硬编码
 * （左键平移、右键菜单、X=88 删除、Ctrl+Z…），玩家无法把"左键拖动图"改成
 * "中键拖动图"。所有输入处理经此查表，改绑定只动一处数据。
 * Why this table exists: pan, context menu, delete, undo… were all hardcoded
 * (left-drag pan, right-click menu, X=88 delete, Ctrl+Z…), so a player could not
 * switch "left-drag to pan" to "middle-drag". All input handling goes through this
 * table; changing a binding touches exactly one place.
 *
 * <p>持久化复用既有客户端配置文件
 * {@code config/create_schematic_compute-client.properties}
 * （与 NodeRenderer 的颜色 / 网格吸附 / 工具栏位置同一机制，键前缀 {@code editorKeys.}）。
 * Persisted into the existing client config file, the same mechanism NodeRenderer
 * uses for colors / grid snap / toolbar position, keyed with {@code editorKeys.}.
 *
 * <p>冲突规则：鼠标动作之间按键唯一，键盘动作之间 (键, 修饰位) 唯一；鼠标与键盘
 * 走不同事件流，允许同键。跨类冲突（如把平移改成右键）被允许但由设置界面提示。
 * Conflict rule: mouse actions must have distinct buttons; keyboard actions distinct
 * (key, mods) pairs. Mouse and keyboard live on different event streams and may
 * share. Cross-kind clashes (panning on right-click) are allowed but flagged in the
 * settings UI.
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
        /** 撤销 / undo */
        UNDO(false, "editorkeys.undo"),
        /** 重做 / redo */
        REDO(false, "editorkeys.redo"),
        /** 复制选中 / duplicate selection */
        DUPLICATE(false, "editorkeys.duplicate"),
        /** 重置视角 / reset the view */
        RESET_VIEW(false, "editorkeys.reset_view"),
        /** 保存视角书签 / save a view bookmark */
        SAVE_BOOKMARK(false, "editorkeys.save_bookmark");

        /** true = 鼠标动作（绑定的是按键索引），false = 键盘动作（绑定键 + 修饰位）。
         *  true = mouse action (bound to a button index); false = keyboard action
         *  (bound to a key + modifier mask). */
        public final boolean mouse;
        /** lang 键（设置界面显示用） / lang key for the settings UI */
        public final String langKey;

        Action(boolean mouse, String langKey) { this.mouse = mouse; this.langKey = langKey; }
    }

    /** 修饰位掩码（与 GLFW_KEY_MOD_* 的语义对应，取值即 bit0=Ctrl bit1=Shift bit2=Alt）。
     *  Modifier mask bits: bit0=Ctrl, bit1=Shift, bit2=Alt. */
    public static final int MOD_CTRL = 1, MOD_SHIFT = 2, MOD_ALT = 4;

    private static final int ACTION_COUNT = Action.values().length;

    /** 鼠标动作的按键索引（0=左 1=右 2=中）。 / Mouse button index per mouse action (0=L, 1=R, 2=M). */
    private static final int[] mouseBindings = new int[ACTION_COUNT];
    /** 键盘动作的 GLFW 键码与修饰位。 / GLFW keycode and modifier mask per keyboard action. */
    private static final int[] keyBindings = new int[ACTION_COUNT];
    private static final int[] keyMods = new int[ACTION_COUNT];

    /** 默认绑定 —— 与重构前硬编码行为逐项一致（保持现有玩家习惯不变）。
     *  取值经 defaultXxx 的 switch 表达式提供：编译器强制穷举所有枚举值，
     *  新增动作漏配默认键是编译错误，而不是 ordinal 错位导致的静默错绑。
     *  Defaults — identical to the pre-refactor hardcodes, so nobody's habits change.
     *  Values come from the defaultXxx switch expressions: the compiler enforces
     *  exhaustiveness, so adding an action without a default is a compile error,
     *  never a silent mis-mapping via ordinals. */
    static {
        for (var a : Action.values()) {
            if (a.mouse) mouseBindings[a.ordinal()] = defaultMouseButton(a);
            else {
                keyBindings[a.ordinal()] = defaultKeyCode(a);
                keyMods[a.ordinal()] = defaultKeyMods(a);
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
            case DELETE_NODE, UNDO, REDO, DUPLICATE, RESET_VIEW, SAVE_BOOKMARK -> -1;
        };
    }

    /** 键盘动作的出厂键码（-1 = 非键盘动作）。 / Factory keycode for a keyboard action (-1 = not a keyboard action). */
    private static int defaultKeyCode(Action a) {
        return switch (a) {
            case DELETE_NODE -> 88;   // X
            case UNDO -> 90;          // Ctrl+Z
            case REDO -> 89;          // Ctrl+Y
            case DUPLICATE -> 68;     // Ctrl+D
            case RESET_VIEW -> 268;   // Home
            case SAVE_BOOKMARK -> 77; // Ctrl+M
            case PAN, CONTEXT_MENU -> -1;
        };
    }

    /** 键盘动作的出厂修饰位。 / Factory modifier mask for a keyboard action. */
    private static int defaultKeyMods(Action a) {
        return switch (a) {
            case DELETE_NODE, RESET_VIEW -> 0;
            case UNDO, REDO, DUPLICATE, SAVE_BOOKMARK -> MOD_CTRL;
            case PAN, CONTEXT_MENU -> 0;
        };
    }

    private EditorKeys() {}

    // ── 查询 / queries ──────────────────────────────────────────────────

    /** 鼠标动作当前绑定的按键索引。 / Button index bound to a mouse action. */
    public static int mouseButton(Action a) { return mouseBindings[a.ordinal()]; }

    /** 键盘动作当前绑定的键码。 / Keycode bound to a keyboard action. */
    public static int keyCode(Action a) { return keyBindings[a.ordinal()]; }

    /** 键盘动作当前绑定的修饰位掩码。 / Modifier mask bound to a keyboard action. */
    public static int keyModifiers(Action a) { return keyMods[a.ordinal()]; }

    /** 本次键盘事件是否命中某键盘动作。
     *  匹配规则：键码相等且按下的修饰位<b>包含</b>绑定的修饰位（按下 ⊇ 绑定）——
     *  与重构前硬编码逐项一致：X / Home 不看修饰位，Ctrl+Z 在 Ctrl+Shift+Z 下同样命中
     *  （旧代码只查 hasControlDown()）。精确相等会让 Home+Shift、Ctrl+Shift+Z 失效。
     *  Does this keyboard event match the action? A match requires the key to equal AND
     *  the pressed modifiers to <b>contain</b> the bound ones (pressed ⊇ bound) —
     *  exactly the pre-refactor hardcodes: X / Home ignored modifiers, and Ctrl+Z also
     *  fired as Ctrl+Shift+Z (the old code only checked hasControlDown()).
     *  Exact equality would break Home+Shift and Ctrl+Shift+Z. */
    public static boolean matchesKey(Action a, int glfwKey, boolean ctrl, boolean shift, boolean alt) {
        int mods = (ctrl ? MOD_CTRL : 0) | (shift ? MOD_SHIFT : 0) | (alt ? MOD_ALT : 0);
        int bound = keyMods[a.ordinal()];
        return keyBindings[a.ordinal()] == glfwKey && (mods & bound) == bound;
    }

    /** 两个修饰位掩码是否存在事件歧义：任一方完全包含另一方（子集关系）即视为冲突。
     *  Do two modifier masks create ambiguous events? Either one containing the other
     *  (a subset relation) counts as a clash. */
    private static boolean modsAmbiguous(int m1, int m2) {
        return (m1 & m2) == m1 || (m1 & m2) == m2;
    }

    /** 修饰位掩码的可读文本（Ctrl+…），供设置界面显示。 / Readable modifier text for the settings UI. */
    public static String modsText(int mods) {
        StringBuilder sb = new StringBuilder();
        if ((mods & MOD_CTRL) != 0) sb.append("Ctrl+");
        if ((mods & MOD_SHIFT) != 0) sb.append("Shift+");
        if ((mods & MOD_ALT) != 0) sb.append("Alt+");
        return sb.toString();
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

    /** 重绑键盘动作的 (键, 修饰位)。与其它键盘动作冲突时返回 false 并拒绝。
     *  冲突判定与 {@link #matchesKey} 的包含语义一致：同键且修饰位互为子集即歧义
     *  （Ctrl+Z 与 Ctrl+Shift+Z 同时存在时两者都会在 Ctrl+Z 按下时命中）。
     *  Rebind a keyboard action. Returns false and refuses on a clash with another
     *  keyboard action. The clash rule mirrors matchesKey's containment semantics:
     *  same key and subset-related masks are ambiguous (Ctrl+Z and Ctrl+Shift+Z would
     *  both fire on a Ctrl+Z press). */
    public static boolean setKeyBinding(Action a, int glfwKey, int mods) {
        for (var other : Action.values())
            if (other != a && !other.mouse
                && keyBindings[other.ordinal()] == glfwKey && modsAmbiguous(mods, keyMods[other.ordinal()])) return false;
        keyBindings[a.ordinal()] = glfwKey;
        keyMods[a.ordinal()] = mods;
        save();
        return true;
    }

    /** 恢复单个动作为默认绑定。 / Restore one action to its default binding. */
    public static void resetToDefault(Action a) {
        // 与静态初始化共用 defaultXxx 查表 —— 单一数据来源，无 ordinal 硬编码。
        // Shares the defaultXxx lookups with the static initializer — one source of
        // truth, no hard-coded ordinals.
        if (a.mouse) mouseBindings[a.ordinal()] = defaultMouseButton(a);
        else {
            keyBindings[a.ordinal()] = defaultKeyCode(a);
            keyMods[a.ordinal()] = defaultKeyMods(a);
        }
        save();
    }

    // ── 持久化 / persistence ────────────────────────────────────────────

    private static final String CONFIG_PATH = "config/create_schematic_compute-client.properties";
    private static final String PREFIX = "editorKeys.";

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
                    String k = props.getProperty(base + "key");
                    String m = props.getProperty(base + "mods");
                    if (k != null) try { keyBindings[a.ordinal()] = Integer.parseInt(k); } catch (NumberFormatException ignored) {}
                    if (m != null) try { keyMods[a.ordinal()] = Integer.parseInt(m); } catch (NumberFormatException ignored) {}
                }
            }
        } catch (Exception e) {
            // 读取失败即用默认表 —— 配置损坏不应导致编辑器不可用。
            // On a read failure keep the defaults; a broken config must not brick the editor.
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
                if (a.mouse) props.setProperty(base + "mouse", String.valueOf(mouseBindings[a.ordinal()]));
                else {
                    props.setProperty(base + "key", String.valueOf(keyBindings[a.ordinal()]));
                    props.setProperty(base + "mods", String.valueOf(keyMods[a.ordinal()]));
                }
            }
            try (var out = java.nio.file.Files.newOutputStream(path)) { props.store(out, "Editor key bindings"); }
        } catch (Exception ignored) {
            // 写失败不打断编辑 —— 下次改动会再试。 / A failed write must not interrupt editing.
        }
    }
}
