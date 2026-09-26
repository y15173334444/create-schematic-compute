package io.github.y15173334444.create_schematic_compute.blocks;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;

import java.util.List;

/**
 * 键位绑定 tab：动作列表 + 屏幕虚拟键盘 + 键序录入（自 {@link EditorSettingsScreen} 拆分，
 * docs/gui-decomposition-plan.md 步骤 3 最后一刀）。
 * The key-bindings tab: the action list, the on-screen virtual keyboard and the
 * key-sequence recording, split out of {@link EditorSettingsScreen} (the last cut of
 * roadmap step 3).
 *
 * <p><b>行为零变更</b>：实现逐字搬迁，只有隐式外层访问改为经 {@link EditorSettingsHost} 取得；
 * 本 tab 自己的状态（录入目标 / 录入序列 / 挂起修饰 / 列表滚动与滚动条拖拽 / 冲突提示）随实现
 * 搬入，屏幕的输入分发经访问器读写。序列 / 键码的可读文本随 {@code seqText}/{@code keyName}
 * 下沉 {@link EditorKeys}（原屏幕静态助手，搬迁时仅本 tab 使用）。
 * <b>Behaviour-preserving</b>: the implementation moved verbatim and the implicit outer-class
 * accesses now go through {@link EditorSettingsHost}. This tab's own state (the rebind target /
 * pending sequence / latched modifiers / list scroll and scrollbar drag / clash message) moved
 * with it, and the screen's input dispatch reaches it through accessors. The sequence/keycode
 * formatting sank into {@link EditorKeys} as {@code seqText}/{@code keyName} (formerly static
 * screen helpers whose only caller was this tab).</p>
 */
final class EditorSettingsKeysTab {

    private final EditorSettingsHost h;

    // ── 键位状态（原 EditorSettingsScreen 字段）/ keys state (moved from the screen) ──

    /** 展开虚拟键盘后正在设置的动作 ordinal，-1 = 未选中/收起。
     *  Keys tab: the action being set while the virtual keyboard is open, -1 = none/collapsed. */
    private int keybindTarget = -1;
    /** 录入中的序列（打开键盘时预填当前绑定，点键帽追加步骤）。
     *  The sequence being recorded (pre-filled with the current binding on open; cap clicks append steps). */
    private final java.util.ArrayList<EditorKeys.Step> pendingSeq = new java.util.ArrayList<>();
    /** 挂起的修饰开关（点键帽随步骤入列后自动复位）。
     *  Latched modifier toggles (cleared automatically when a step is recorded). */
    private int latchedMods = 0;
    /** 键位列表滚动偏移（行数）与滚动条拖拽状态（颜色列表同款交互）。
     *  Key-list scroll offset (rows) and scrollbar drag state (same interaction as the colors list). */
    private int keysScroll = 0;
    private boolean keysScrollbarDrag = false;
    private float keysScrollbarDragStartY = 0f;
    private int keysScrollbarDragStartOff = 0;
    /** 重绑冲突提示（显示在内容区底部，非空即显示）。 / rebind clash message shown at the content bottom when non-null. */
    private String rebindConflict;

    EditorSettingsKeysTab(EditorSettingsHost h) { this.h = h; }

    // ── 访问器：屏幕输入分发读写 / accessors used by the screen's input dispatch ──
    int keysScroll() { return keysScroll; }
    void setKeysScroll(int v) { keysScroll = v; }
    boolean keysScrollbarDrag() { return keysScrollbarDrag; }
    void setKeysScrollbarDrag(boolean v) { keysScrollbarDrag = v; }
    void setKeysScrollbarDragStart(float y, int off) { keysScrollbarDragStartY = y; keysScrollbarDragStartOff = off; }
    int keybindTarget() { return keybindTarget; }
    void setLatchedMods(int v) { latchedMods = v; }
    void setRebindConflict(String v) { rebindConflict = v; }
    java.util.ArrayList<EditorKeys.Step> pendingSeq() { return pendingSeq; }

    /** 收起重绑区：清空录入目标 / 序列 / 挂起修饰（屏幕 collapseExpanded 的键位部分；
     *  展开态标志 expanded 归屏幕，由屏幕自己复位）。
     *  Collapse the rebind flow: clear the target / pending sequence / latched mods
     *  (the keys half of the screen's collapseExpanded; the expanded flag itself stays
     *  on the screen, which resets it). */
    void collapseRebind() {
        keybindTarget = -1;
        pendingSeq.clear();
        latchedMods = 0;
    }

    /** 虚拟键帽：label 显示文本、code GLFW 键码（0 = 纯修饰开关）、w 宽度（键帽单位）、
     *  modBit 非零 = 修饰开关（点击翻转该修饰位，不进主键槽）。
     *  A virtual keycap: label, GLFW code (0 = pure modifier toggle), width in keycap
     *  units, modBit != 0 = modifier toggle (clicks flip the bit, never the main-key slot). */
    record Keycap(String label, int code, float w, int modBit) { }

    private static Keycap cap(String label, int code, float w, int modBit) { return new Keycap(label, code, w, modBit); }

    /** 无 F 行紧凑配列：Esc + 数字行 + 三行字母 + 底部修饰行，右缘塞下 Home 与方向键。
     *  Esc 键帽 = 清空当前选择（Esc 是编辑器保留键，不可绑）；L/R 修饰键帽共用同一开关位。
     *  No-F-row compact layout. The Esc cap clears the selection (Esc is a reserved
     *  editor key, not bindable); the L/R modifier caps share one toggle bit. */
    static final Keycap[][] KEY_ROWS = {
        { cap("Esc", 256, 1, 0), cap("`", 96, 1, 0), cap("1", 49, 1, 0), cap("2", 50, 1, 0), cap("3", 51, 1, 0),
          cap("4", 52, 1, 0), cap("5", 53, 1, 0), cap("6", 54, 1, 0), cap("7", 55, 1, 0), cap("8", 56, 1, 0),
          cap("9", 57, 1, 0), cap("0", 48, 1, 0), cap("-", 45, 1, 0), cap("=", 61, 1, 0), cap("Bksp", 259, 2, 0) },
        { cap("Tab", 258, 1.5f, 0), cap("Q", 81, 1, 0), cap("W", 87, 1, 0), cap("E", 69, 1, 0), cap("R", 82, 1, 0),
          cap("T", 84, 1, 0), cap("Y", 89, 1, 0), cap("U", 85, 1, 0), cap("I", 73, 1, 0), cap("O", 79, 1, 0),
          cap("P", 80, 1, 0), cap("[", 91, 1, 0), cap("]", 93, 1, 0), cap("\\", 92, 1.5f, 0) },
        { cap("Caps", 280, 1.75f, 0), cap("A", 65, 1, 0), cap("S", 83, 1, 0), cap("D", 68, 1, 0), cap("F", 70, 1, 0),
          cap("G", 71, 1, 0), cap("H", 72, 1, 0), cap("J", 74, 1, 0), cap("K", 75, 1, 0), cap("L", 76, 1, 0),
          cap(";", 59, 1, 0), cap("'", 39, 1, 0), cap("Enter", 257, 2.25f, 0) },
        { cap("Shift", 0, 2.25f, EditorKeys.MOD_SHIFT), cap("Z", 90, 1, 0), cap("X", 88, 1, 0), cap("C", 67, 1, 0),
          cap("V", 86, 1, 0), cap("B", 66, 1, 0), cap("N", 78, 1, 0), cap("M", 77, 1, 0), cap(",", 44, 1, 0),
          cap(".", 46, 1, 0), cap("/", 47, 1, 0), cap("Home", 268, 1.5f, 0), cap("Del", 261, 1, 0), cap("▲", 265, 1, 0) },
        { cap("Ctrl", 0, 1.5f, EditorKeys.MOD_CTRL), cap("Alt", 0, 1.25f, EditorKeys.MOD_ALT), cap("Space", 32, 6, 0),
          cap("Alt", 0, 1.25f, EditorKeys.MOD_ALT), cap("Ctrl", 0, 1.5f, EditorKeys.MOD_CTRL),
          cap("◀", 263, 1, 0), cap("▼", 264, 1, 0), cap("▶", 262, 1, 0) },
    };

    /** 键位 tab 展开态：动作列表窄列宽 / 鼠标键列宽。 / expanded keys tab: narrow list width / mouse-column width. */
    int keysListW() { return Math.max(110, Math.min(260, h.w() / 3)); }

    // ── 键位列表几何（渲染 / 命中 / 拖拽共用单一来源） ──
    // ── Key-list geometry (single source shared by render, hit-testing and dragging) ──

    /** 列表顶部 y。 / list top y. */
    int keysListTop() { return h.cy() + 2; }
    /** 列表底部 y（内容区底再上提 24px，给「收起」按钮留固定位置）。 / list bottom y (24px above the content bottom, reserving a fixed slot for the Collapse button). */
    int keysListBot() { return h.h() - 8 - 24; }
    int keysVisibleRows() { return Math.max(1, (keysListBot() - keysListTop()) / KEY_ROW_H); }
    int keysMaxScroll() { return Math.max(0, EditorKeys.Action.values().length - keysVisibleRows()); }
    /** 鼠标键列宽（渲染与命中共用）。 / mouse-chip column width (shared by render and hit-testing). */
    static final int KEYS_CHIPS_W = 72;

    /** 键帽单位尺寸：可用宽度 ÷ 最宽行（≈15.5 单位），钳制 10..24（窄窗口自动缩小）。 / keycap unit: available width ÷ the widest row (~15.5 units), clamped 10..24. */
    float keysUnit() {
        int avail = h.w() - 14 - keysListW() - 12 - KEYS_CHIPS_W - 12 - 24;
        return Math.max(10f, Math.min(24f, avail / 15.75f));
    }

    /** 键帽间距：小键帽缩到 1px 省宽（渲染与命中共用同一规则）。 / cap gap: 1px for small caps (shared by render and hit-testing). */
    static float keysGap(float u) { return u < 13f ? 1f : 2f; }

    /** 键盘网格总宽（像素，含键帽间距）。 / keyboard grid width in pixels (gaps included). */
    static float keysGridW(float u) {
        float gap = keysGap(u);
        float max = 0;
        for (var row : KEY_ROWS) {
            float w = 0;
            for (var c : row) w += c.w() * u + gap;
            max = Math.max(max, w - gap);
        }
        return max;
    }

    /** 键位 tab 渲染：收起态为全宽动作列表；点击行后界面左滑（展开态）—— 左侧窄列列表 +
     *  右侧虚拟键盘 + 鼠标三键。选择语义：修饰键帽 = 开关可多选，非修饰键帽 = 主键单选槽；
     *  底部实时预览，点「确定绑定」才落绑定（冲突检查）；选中动作的现值键帽描边显示。
     *  Keys tab rendering: collapsed = full-width action list; clicking a row slides the
     *  UI left (expanded) into a narrow list + virtual keyboard + three mouse buttons.
     *  Modifier caps are toggles, non-modifier caps fill the single main-key slot; the
     *  bottom bar previews live and only Bind commits (clash-checked); the action's
     *  current binding caps are outlined. */
    void renderKeysTab(GuiGraphics g, int mx, int my, int cx, int cy,
                               int contentRight, int contentBottom) {
        var actions = EditorKeys.Action.values();
        int listRight = h.expanded() ? cx + keysListW() : contentRight;
        int listTop = keysListTop(), listBot = keysListBot();
        int maxScroll = keysMaxScroll();
        if (keysScroll < 0) keysScroll = 0;
        if (keysScroll > maxScroll) keysScroll = maxScroll;
        for (int i = keysScroll; i < actions.length; i++) {
            int ry = listTop + (i - keysScroll) * KEY_ROW_H;
            if (ry + KEY_ROW_H > listBot) break;
            var a = actions[i];
            boolean selected = h.expanded() && keybindTarget == i;
            boolean hov = !selected && mx >= cx && mx <= listRight - 10 && my >= ry && my <= ry + KEY_ROW_H - 2;
            if (selected) g.fill(cx, ry, listRight, ry + KEY_ROW_H - 2, NodeRenderer.HOV()); // 选中行 = 悬停高亮 / selected row = hover highlight
            else if (hov) g.fill(cx, ry, listRight, ry + KEY_ROW_H - 2, NodeRenderer.HOV());
            // 行文本 = 该动作唯一触发（统一序列，鼠标步照渲染）：Ctrl+Z、右键、
            // Tab → 左键、Tab → 左键 → A → 右键，未绑为「—」。
            // Row text = the action's single trigger (the unified sequence, mouse steps
            // rendered as-is): Ctrl+Z, right-click, Tab → left-click,
            // Tab → left-click → A → right-click; "—" when unbound.
            String cur = EditorKeys.seqText(EditorKeys.sequence(a), EditorKeysTabMouseNames);
            // 动作名 + 当前绑定，超宽按窄列截断（预留滚动条条带；展开态列表变窄时防压进键盘区）。
            // Action name + current binding, truncated to the narrowed list width (reserving
            // the scrollbar strip).
            String text = I18n.get("gui.create_schematic_compute." + a.langKey) + ":  " + cur;
            text = h.font().plainSubstrByWidth(text, listRight - (cx + 6) - 14);
            g.drawString(h.font(), text, cx + 6, ry + 7, 0xFFCCCCCC, false);
        }
        // 滚动条（thumb 可拖拽）——几何与命中/拖拽共用 keysScrollbarThumb。
        // Scrollbar (draggable thumb) — geometry shared with hit-testing/dragging.
        if (maxScroll > 0) {
            int[] sb = keysScrollbarThumb(listRight);
            g.fill(sb[0], listTop, sb[0] + sb[2], listBot, NodeRenderer.PINS()); // 滚动条轨道 = 内凹井 / track = inset well
            g.fill(sb[0] + 1, sb[1], sb[0] + sb[2] - 1, sb[1] + sb[3], NodeRenderer.CSB());
        }
        // 冲突提示：收起态在列表底部；展开态移到操作条下方（contentBottom-12 处会被
        // 「收起」按钮盖住 —— 按钮后画）。
        // Clash message: list bottom when collapsed; below the bar when expanded (at
        // contentBottom-12 it is painted over by the Collapse button, which draws later).
        if (rebindConflict != null && !h.expanded())
            g.drawString(h.font(), "§c" + rebindConflict, cx, contentBottom - 12, 0xFFFFFFFF, false);
        if (!h.expanded() || keybindTarget < 0) return;

        // ── 展开态：右侧虚拟键盘 + 鼠标三键 + 底部 预览/默认/清除/确定 ──
        // Expanded: virtual keyboard + mouse buttons on the right, preview/default/clear/bind bar.
        float u = keysUnit();
        int chipsX = h.w() - 14 - KEYS_CHIPS_W;
        var target = actions[keybindTarget];

        // 鼠标三键（竖排；点击 = 鼠标步进/出录入队列，与键帽步骤自由交错）。
        // 仅 mouseStepAllowed 动作显示；当前触发/待录含该按钮时绿描边（与键帽现值描边同语义）。
        // Mouse buttons (vertical; a click moves the button in/out of the recording
        // queue, interleaving freely with keycap steps). Shown for mouseStepAllowed
        // actions only; green outline when the live trigger or the pending queue holds
        // the button, same semantics as the keycaps.
        if (EditorKeys.mouseStepAllowed(target)) {
            for (int m = 0; m < 3; m++) {
                int btn = m; // lambda 捕获需实际最终变量 / lambdas need an effectively-final copy
                int chy = cy + 2 + m * 24;
                boolean chov = mx >= chipsX && mx <= chipsX + KEYS_CHIPS_W && my >= chy && my <= chy + 20;
                // 绿描边 = 当前触发或待录队列含有该按钮的鼠标步（待录与现值同语义高亮，
                // 再次点击取消）。
                // Green outline = the live trigger or the pending queue holds a mouse
                // step of this button (pending highlights like the live value; a second
                // click cancels it).
                boolean bound = EditorKeys.sequence(target).stream().anyMatch(s -> s.key() < 0 && -1 - s.key() == btn)
                    || pendingSeq.stream().anyMatch(s -> s.key() < 0 && -1 - s.key() == btn);
                g.fill(chipsX, chy, chipsX + KEYS_CHIPS_W, chy + 20, chov ? NodeRenderer.HOV() : NodeRenderer.PINS());
                g.renderOutline(chipsX, chy, KEYS_CHIPS_W, 20, bound ? 0xFF5A8A3A : NodeRenderer.CSB());
                g.drawString(h.font(), I18n.get("gui.create_schematic_compute.editorkeys.mouse." + m),
                    chipsX + 8, chy + 6, bound ? 0xFFCCFFCC : NodeRenderer.ACC(), false);
            }
        }

        // 键盘（行左对齐，宽键向右伸出，真实配列观感）。
        // Keyboard rows left-aligned with wide keys overhanging right, like a real board.
        // 修饰键帽点亮 = 挂起开关 ∪ 已录末步的修饰 —— 只看挂起开关的话，打开键盘预填
        // 现绑定（如 Ctrl+Z）时 Ctrl 不亮、追加步骤后（修饰随步入列）又立刻熄灭。
        // Modifier caps light up = latched toggles ∪ the last recorded step's mods —
        // latched alone would leave Ctrl dark on prefill (Ctrl+Z) and right after a
        // step absorbs the latched mods.
        int shownMods = latchedMods;
        if (!pendingSeq.isEmpty()) shownMods |= pendingSeq.get(pendingSeq.size() - 1).mods();
        float kx0 = chipsX - 12 - keysGridW(u);
        float ky = cy + 2;
        float gap = keysGap(u);
        for (var row : KEY_ROWS) {
            float kx = kx0;
            for (var c : row) {
                float w = c.w() * u;
                boolean hov = mx >= kx && mx <= kx + w && my >= ky && my <= ky + u;
                int bg = hov ? NodeRenderer.HOV() : NodeRenderer.PINS();
                if (c.modBit() != 0 && (shownMods & c.modBit()) != 0) bg = NodeRenderer.HOV(); // 挂起/末步修饰点亮 / latched or last-step mods lit
                g.fill((int) kx, (int) ky, (int) (kx + w), (int) (ky + u), bg);
                // 录入中序列的键帽绿描边（打开时预填 = 现绑定，录入后 = 已录步骤）。
                // Caps of the recorded sequence get the green outline (pre-filled with the
                // current binding on open, then the recorded steps).
                boolean inSeq = c.code() > 0;
                if (inSeq) {
                    inSeq = false;
                    for (var st : pendingSeq) if (st.key() == c.code()) { inSeq = true; break; }
                }
                g.renderOutline((int) kx, (int) ky, (int) w, (int) u, inSeq ? 0xFF5A8A3A : NodeRenderer.CSB());
                g.drawString(h.font(), c.label(), (int) (kx + w / 2 - h.font().width(c.label()) / 2), (int) (ky + u / 2 - 4), 0xFFCCCCCC, false);
                kx += w + gap;
            }
            ky += u + 3;
        }

        // 预览行（键盘下方独立一行）：录入中的队列（键步/鼠标步交错，「绑定：
        // Tab → 左键 → A → 右键」）；挂起修饰以 … 收尾提示「下一步将带上」。
        // Preview line under the keyboard: the recording queue (key/mouse steps
        // interleaved, "Bind: Tab → left-click → A → right-click"); latched mods trail
        // with an ellipsis ("the next step will carry them").
        int previewY = (int) ky + 6;
        String preview = I18n.get("gui.create_schematic_compute.settings.bind_label") + ": " + EditorKeys.seqText(pendingSeq, EditorKeysTabMouseNames)
            + (latchedMods != 0 ? (pendingSeq.isEmpty() ? "" : " → ") + EditorKeys.modsText(latchedMods) + "…" : "");
        g.drawString(h.font(), "§e" + preview, (int) kx0, previewY, 0xFFFFFFFF, false);
        // 操作条：删一步 / 默认 / 清除 / 确定绑定 —— 宽度平衡、右缘锚定；左缘压到列表
        // 滚动条时整条下移到滚动条下方（几何经 keysBarGeometry 与命中共用）。
        // Bar: step-back / default / clear / bind — balanced widths, right-anchored;
        // when its left edge would cover the list scrollbar the whole bar drops below
        // the track (geometry shared with hit-testing via keysBarGeometry).
        int[] bar = keysBarGeometry(ky, listRight);
        int barY = bar[1];
        int confirmX = h.w() - 14 - 66;
        int clearX = confirmX - 62;
        int defX = clearX - 62;
        int backX = bar[0];
        // 删一步（移除最后录入的步骤）/ step-back (remove the last recorded step)
        boolean bHov = mx >= backX && mx <= backX + 58 && my >= barY && my <= barY + 16;
        g.fill(backX, barY, backX + 58, barY + 16, bHov ? 0xFF4A5A2A : NodeRenderer.PBG());
        g.renderOutline(backX, barY, 58, 16, 0xFF6A8A3A);
        g.drawString(h.font(), "§a" + I18n.get("gui.create_schematic_compute.settings.bind_step_back"), backX + 15, barY + 4, 0xFFFFFFFF, false);
        // 默认（恢复当前选中动作的出厂绑定，录入状态同步重预填）/ default (restore the
        // selected action's factory binding and re-prefill the recording from it)
        boolean dHov = mx >= defX && mx <= defX + 56 && my >= barY && my <= barY + 16;
        g.fill(defX, barY, defX + 56, barY + 16, dHov ? 0xFF4A5A2A : NodeRenderer.PBG());
        g.renderOutline(defX, barY, 56, 16, 0xFF6A8A3A);
        g.drawString(h.font(), "§a" + I18n.get("gui.create_schematic_compute.settings.reset_default"), defX + 19, barY + 4, 0xFFFFFFFF, false);
        g.fill(clearX, barY, clearX + 56, barY + 16, NodeRenderer.PBG());
        g.renderOutline(clearX, barY, 56, 16, NodeRenderer.CSB());
        g.drawString(h.font(), "§7" + I18n.get("gui.create_schematic_compute.settings.bind_clear"), clearX + 19, barY + 4, 0xFFFFFFFF, false);
        g.fill(confirmX, barY, confirmX + 66, barY + 16, 0xFF3A5A2A);
        g.renderOutline(confirmX, barY, 66, 16, 0xFF5A8A3A);
        g.drawString(h.font(), "§a" + I18n.get("gui.create_schematic_compute.settings.bind_confirm"), confirmX + 15, barY + 4, 0xFFFFFFFF, false);
        // 展开态冲突提示：紧跟操作条下方（键盘区左缘），不与「收起」按钮同域。
        // Expanded clash message: right below the bar at the keyboard's left edge.
        if (rebindConflict != null)
            g.drawString(h.font(), "§c" + rebindConflict, (int) kx0, barY + 18, 0xFFFFFFFF, false);

        // 收起按钮（列表列底部，与颜色 tab 的收起同款样式） / collapse button (list column bottom)
        int clY = keysListBot() + 4; // 列表下方固定位，不随行数增长 / fixed below the list
        boolean clHov = mx >= cx && mx <= cx + 64 && my >= clY && my <= clY + 16;
        g.fill(cx, clY, cx + 64, clY + 16, clHov ? NodeRenderer.HOV() : NodeRenderer.PBG());
        g.renderOutline(cx, clY, 64, 16, NodeRenderer.CSB());
        g.drawString(h.font(), "§f" + I18n.get("gui.create_schematic_compute.settings.collapse"), cx + 16, clY + 4, 0xFFFFFFFF, false);
    }

    /** 键帽点击：修饰键帽翻转挂起开关；Esc 键帽清空整条录入队列（键序 + 鼠标末步）；
     *  其余追加为下一步（挂起修饰随步骤入列）。菜单键步、鼠标步皆可（键序触发已
     *  恢复）；PAN 是按住抓图组合键，录满一步后再点键帽提示单步上限。
     *  Keycap click: modifier caps flip the latched toggles; the Esc cap clears the
     *  whole recording queue (key steps + the pending mouse step); everything else
     *  appends the next step (mods latch with the step). The menu takes key and mouse
     *  steps (its key-sequence trigger is back); PAN is a hold-grab combo — a cap
     *  click past the first step hints the single-step cap. */
    void handleKeycapClick(Keycap c) {
        if (c.modBit() != 0) { latchedMods ^= c.modBit(); return; }
        if (c.code() == 256) { pendingSeq.clear(); latchedMods = 0; rebindConflict = null; return; }
        var a = EditorKeys.Action.values()[keybindTarget];
        if (a == EditorKeys.Action.PAN && !pendingSeq.isEmpty()) {
            rebindConflict = I18n.get("gui.create_schematic_compute.editorkeys.pan_hold_single");
            return;
        }
        if (pendingSeq.size() >= EditorKeys.MAX_STEPS) { rebindConflict = I18n.get("gui.create_schematic_compute.settings.bind_max_steps"); return; }
        pendingSeq.add(new EditorKeys.Step(c.code(), latchedMods));
        latchedMods = 0; // 修饰随步骤入列复位 / mods clear with the recorded step
    }

    /** 鼠标键点击 = 把该按钮作为<b>鼠标步</b>加入录入队列——队列里已有该按钮的
     *  鼠标步则<b>移除最后一处</b>（再次点击取消），否则追加到队尾；键帽步骤与鼠标步
     *  可自由交错（Tab → 左键 → A → 右键），挂起修饰随步入列。像素动作与 BOX_SELECT
     *  独占鼠标，拒绝。真正的绑定在「确定绑定」时整条提交。
     *  Mouse-chip click = add the button as a <b>mouse step</b> to the recording
     *  queue — if the queue already holds a mouse step of this button, <b>remove the
     *  last one</b> (a second click cancels), else append at the tail; key steps and
     *  mouse steps interleave freely (Tab → left-click → A → right-click) and latched
     *  mods ride with the step. The pixel actions and BOX_SELECT own the mouse and
     *  refuse. The actual binding is committed wholesale by Bind. */
    void handleChipClick(EditorKeys.Action a, int button) {
        if (!EditorKeys.mouseStepAllowed(a)) { rebindConflict = I18n.get("gui.create_schematic_compute.editorkeys.rebind_key_only"); return; }
        for (int i = pendingSeq.size() - 1; i >= 0; i--) {
            if (pendingSeq.get(i).key() < 0 && -1 - pendingSeq.get(i).key() == button) {
                pendingSeq.remove(i);
                rebindConflict = null;
                return;
            }
        }
        pendingSeq.add(new EditorKeys.Step(EditorKeys.mouseStepKey(button), latchedMods));
        latchedMods = 0; // 修饰随步入列复位 / mods clear with the recorded step
    }

    /** 「确定绑定」：把录入队列整条落为该动作的唯一触发（统一序列——键步/鼠标步
     *  任意交错，纯键序、纯鼠标、键鼠组合、四步交错都是同一种东西）；PAN 恒单步
     *  （键步=按住抓图、鼠标步=按钮拖动，混录或多步提示）。前缀歧义 / 同域按钮冲突 /
     *  能力越界拒绝。成功后预填当前生效触发（所见即所改）。
     *  Bind: commit the recording queue wholesale as the action's single trigger (the
     *  unified sequence — key/mouse steps interleave freely; a pure key sequence, a
     *  pure mouse gesture, a key+mouse combo and a 4-step interleave are all the same
     *  thing); PAN is always single-step (a key step = the hold-grab, a mouse step =
     *  button-drag; mixing or multi-step is hinted). Prefix ambiguity / same-domain
     *  button clashes / capability overruns are refused. On success the live trigger
     *  pre-fills the recording (what you see is what you bound). */
    void confirmKeybind() {
        var a = EditorKeys.Action.values()[keybindTarget];
        if (pendingSeq.isEmpty()) { rebindConflict = I18n.get("gui.create_schematic_compute.settings.bind_need_key"); return; }
        if (a == EditorKeys.Action.PAN && pendingSeq.size() > 1) {
            rebindConflict = I18n.get("gui.create_schematic_compute.editorkeys.pan_hold_single");
            return;
        }
        rebindConflict = EditorKeys.setSequence(a, List.copyOf(pendingSeq))
            ? null : I18n.get("gui.create_schematic_compute.editorkeys.conflict");
        if (rebindConflict == null) {
            pendingSeq.clear();
            pendingSeq.addAll(EditorKeys.sequence(a));
        }
    }

    /** 选中动作行并展开虚拟键盘：预填该动作当前触发序列（含鼠标步），所见即所改。
     *  Select an action row and open the keyboard, pre-filled with the current trigger
     *  sequence (mouse steps included). */
    void selectKeybindRow(int idx) {
        keybindTarget = idx;
        var a = EditorKeys.Action.values()[idx];
        pendingSeq.clear();
        pendingSeq.addAll(EditorKeys.sequence(a));
        latchedMods = 0;
        rebindConflict = null;
        h.setExpanded(true);
    }

    /** 键位列表行高。 / key-list row height. */
    static final int KEY_ROW_H = 22;

    /** 键位列表滚动条 thumb {x, y, w, h}（x 依赖当次列表右缘；渲染与拖拽共用几何）。
     *  Key-list scrollbar thumb {x, y, w, h} (x depends on the current list right edge;
     *  geometry shared by render and dragging). */
    int[] keysScrollbarThumb(int listRight) {
        int trackH = keysListBot() - keysListTop();
        int thumbH = Math.max(12, trackH * keysVisibleRows() / EditorKeys.Action.values().length);
        int maxScroll = keysMaxScroll();
        int thumbY = keysListTop() + (maxScroll > 0 ? (trackH - thumbH) * keysScroll / maxScroll : 0);
        return new int[]{listRight - 8, thumbY, 6, thumbH};
    }

    /** 拖拽推进键位列表：thumb 相对增量换算为行偏移（颜色列表同款）。 / Advance the key list by the dragged thumb delta (colors-list style). */
    void applyKeysScrollbarDrag(double my) {
        int maxScroll = keysMaxScroll();
        if (maxScroll <= 0) return;
        int trackH = keysListBot() - keysListTop();
        int thumbH = Math.max(12, trackH * keysVisibleRows() / EditorKeys.Action.values().length);
        if (trackH - thumbH <= 0) return;
        float delta = (float) (my - keysScrollbarDragStartY) / (trackH - thumbH);
        int newOff = keysScrollbarDragStartOff + Math.round(delta * maxScroll);
        keysScroll = Math.max(0, Math.min(maxScroll, newOff));
    }

    /** 键位操作条几何 {backX, barY}：宽度平衡（58/56/56/66 + 6px 间距，总 254）右缘锚定；
     *  左缘压到列表滚动条（窄窗口）时整条下移到滚动条轨道之下。渲染与命中共用同一来源。
     *  Key-bar geometry {backX, barY}: balanced widths (58/56/56/66 + 6px gaps, 254
     *  total), right-anchored; when the left edge would cover the list scrollbar (narrow
     *  windows) the whole bar drops below the track. One source shared by render and
     *  hit-testing. */
    int[] keysBarGeometry(float ky, int listRight) {
        int backX = h.w() - 14 - 254;
        int barY = (int) ky + 20;
        if (backX < listRight + 6) barY = keysListBot() + 4;
        return new int[]{backX, barY};
    }

    /** seqText 的鼠标步显示名解析器（按钮索引 → I18n 标签：左键/右键/中键）。
     *  The mouse-step label resolver for seqText (button index → I18n label). */
    private static final java.util.function.IntFunction<String> EditorKeysTabMouseNames =
        b -> I18n.get("gui.create_schematic_compute.editorkeys.mouse." + b);
}
