package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.client.colorpicker.ColorPickerWidget;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

/**
 * 编辑器设置：独立全屏 Screen —— 左侧选项卡列（界面颜色 / 键位绑定 / 节点指南），
 * 右侧内容区。从编辑器顶栏的设置按钮进入；关闭后返回编辑器。
 *
 * <p>会话语义：{@code setScreen} 只触发旧屏的 {@code removed()}（不发 LeavePacket），
 * 编辑会话在设置期间保持；返回编辑器时 {@code init()} 幂等重 join（与像素编辑器
 * 转移同一模式）。打开时会收起编辑器的浮层（添加菜单 / 颜色面板）。
 *
 * <p>内容全部是客户端全局状态：键位绑定持久化在 {@link EditorKeys}，颜色在
 * {@link NodeRenderer}，指南由 {@link NodeType} 元数据生成 —— 均不依赖具体 BE。
 *
 * <p>Editor settings: a standalone full-screen Screen — vertical tab column on the
 * left (colors / key bindings / node guide), content on the right. Entered from the
 * editor's top-bar button; closing returns to the editor.
 *
 * <p>Session semantics: {@code setScreen} only triggers the old screen's
 * {@code removed()} (no LeavePacket), so the edit session survives while settings are
 * open; returning re-runs the editor's {@code init()} which re-joins idempotently
 * (same pattern as the pixel-editor transfer). Opening collapses the editor's
 * floating panels (add-node menu / color panel).
 *
 * <p>All content is client-global state: key bindings persist in {@link EditorKeys},
 * colors in {@link NodeRenderer}, the guide is generated from {@link NodeType}
 * metadata — nothing depends on a specific block entity.
 */
public class EditorSettingsScreen extends Screen {

    /** 关闭后返回的编辑器屏幕（复用同一实例，setScreen 会重新 init）。 / the editor screen returned to on close (reused; setScreen re-inits it). */
    private final Screen parent;

    /** 上次打开时的选项卡（跨多次打开记住位置，与旧弹窗行为一致）。 / last open tab (remembered across opens, matching the old dialog). */
    private static int lastTab = 0;
    /** 当前选项卡；初始值取 lastTab。 / active tab; initialized from lastTab. */
    private int tab = lastTab;

    /** 正在等待重绑的动作 ordinal，-1 = 无。 / the action awaiting a rebind (ordinal), -1 = none. */
    private int listeningBinding = -1;
    /** 重绑冲突提示（显示在内容区底部，非空即显示）。 / rebind clash message shown at the content bottom when non-null. */
    private String rebindConflict;
    /** 节点指南列表的滚动偏移（行数）。 / node-guide list scroll offset, in rows. */
    private int guideScroll = 0;

    // ── 颜色调整状态 / color-adjustment state ──

    /** 展开形态：整个界面左滑、调色板停靠右侧（展开/收起按钮与"调整"共同控制）。
     *  Expanded form: the UI slides left and the palette docks right (toggled by the
     *  expand/collapse button and the per-row adjust buttons). */
    private boolean expanded = false;
    /** 左滑动画进度 0..1（渲染每帧推进）。 / slide animation progress 0..1 (advanced per render frame). */
    private float slide = 0f;
    /** 正在调整的颜色槽（NodeRenderer.stagingColors 下标），-1 = 无。 / the color slot being adjusted (index into NodeRenderer.stagingColors), -1 = none. */
    private int adjustIndex = -1;
    /** 调色板的工作色：实时跟随调色板操作，点击确认键才填入 adjustIndex 槽位。
     *  The palette's working color: follows the palette live, filled into the
     *  adjustIndex slot only when the confirm button is pressed. */
    private int workingColor = 0xFF000000;
    /** 颜色列表滚动偏移（行数）。 / color list scroll offset, in rows. */
    private int colorScroll = 0;
    /** 是否已为本次进入颜色 tab 初始化暂存色。 / whether staging colors were initialized for this colors-tab visit. */
    private boolean stagingInited = false;
    /** 停靠在右侧的调色板（嵌入模式：无浮空外框、不随外部点击关闭）。 / the palette docked on the right (embedded: no floating frame, no outside-click close). */
    private final ColorPickerWidget picker = new ColorPickerWidget();

    private static final int COLOR_ROW_H = 24;

    /** 左侧选项卡列宽度。 / left tab-column width. */
    private static final int TAB_W = 170;

    public EditorSettingsScreen(Screen parent) {
        super(Component.translatable("gui.create_schematic_compute.settings.title"));
        this.parent = parent;
    }

    @Override protected void init() { }

    /** 编辑器不暂停单机游戏，设置界面亦然。 / the editor never pauses the game; neither do its settings. */
    @Override public boolean isPauseScreen() { return false; }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        int w = this.width, h = this.height;

        // 左滑动画推进：展开形态 → 整个界面（含选项卡列）向左平移，右侧停靠调色板。
        // Slide animation: in the expanded form the whole UI (tab column included)
        // slides left, docking the palette on the right.
        float slideTarget = expanded ? 1f : 0f;
        slide += (slideTarget - slide) * 0.25f;
        if (Math.abs(slideTarget - slide) < 0.01f) slide = slideTarget;
        int shift = Math.round(TAB_W * slide);

        // 全屏底 + 左侧选项卡列 / full-screen base + left tab column
        g.fill(0, 0, w, h, 0xF0101018);
        g.fill(-shift, 0, TAB_W - shift, h, 0xFF16141E);
        g.fill(TAB_W - shift, 0, TAB_W - shift + 1, h, 0xFF3A3832);
        g.drawString(font, "§6§l" + I18n.get("gui.create_schematic_compute.settings.title"), 12 - shift, 12, 0xFFFFFFFF, false);

        // 选项卡（纵向）+ 末尾返回项 / vertical tabs + back entry at the end
        String[] tabs = {
            I18n.get("gui.create_schematic_compute.settings.tab.colors"),
            I18n.get("gui.create_schematic_compute.settings.tab.keys"),
            I18n.get("gui.create_schematic_compute.settings.tab.guide")};
        for (int i = 0; i < tabs.length; i++) {
            int tx = 10 - shift, ty = 36 + i * 30;
            boolean active = i == tab;
            g.fill(tx, ty, tx + TAB_W - 20, ty + 24, active ? 0xFF2A3A5A : 0xFF222020);
            g.renderOutline(tx, ty, TAB_W - 20, 24, active ? 0xFF5A7AAA : NodeRenderer.CSB());
            g.drawString(font, tabs[i], tx + 8, ty + 8, active ? 0xFFAACCFF : 0xFFAAAAAA, false);
        }
        // 返回项（书签列末尾，样式与选项卡一致、灰字表示动作而非状态）
        // Back entry (end of the tab column, tab-styled with gray text to read as an
        // action rather than a state).
        int btx = 10 - shift, bty = 36 + tabs.length * 30;
        boolean backHov = mx >= btx && mx <= btx + TAB_W - 20 && my >= bty && my <= bty + 24;
        g.fill(btx, bty, btx + TAB_W - 20, bty + 24, backHov ? 0xFF3A4A6A : 0xFF2A2020);
        g.renderOutline(btx, bty, TAB_W - 20, 24, NodeRenderer.CSB());
        g.drawString(font, "§7" + I18n.get("gui.create_schematic_compute.back"), btx + 8, bty + 8, backHov ? 0xFFFFFFFF : 0xFFAAAAAA, false);

        // 内容区（随界面平移）/ content area (slides with the UI)
        int cx = TAB_W + 12 - shift, cy = 8;
        int contentRight = w - 14 - shift, contentBottom = h - 8;
        int contentW = contentRight - cx;
        if (tab == 0) {
            renderColorsTab(g, mx, my, cx, cy, contentRight, contentBottom, contentW);
        } else if (tab == 1) {
            // 键位绑定列表：点击行进入监听，随后按下的鼠标键 / 键盘键成为新绑定。
            // Key-binding list: click a row to listen, then the next mouse button or
            // key becomes the new binding.
            var actions = EditorKeys.Action.values();
            int rowH = 26;
            int rowY = cy + 2;
            for (int i = 0; i < actions.length; i++) {
                var a = actions[i];
                boolean listening = listeningBinding == a.ordinal();
                boolean hov = !listening && mx >= cx && mx <= contentRight - 40 && my >= rowY && my <= rowY + rowH - 2;
                if (listening) g.fill(cx, rowY, contentRight, rowY + rowH - 2, 0xFF5A3A2A);
                else if (hov) g.fill(cx, rowY, contentRight, rowY + rowH - 2, 0xFF3A4A3A);
                String cur;
                if (a.mouse) {
                    cur = I18n.get("gui.create_schematic_compute.editorkeys.mouse." + EditorKeys.mouseButton(a));
                } else {
                    cur = EditorKeys.modsText(EditorKeys.keyModifiers(a)) + keyName(EditorKeys.keyCode(a));
                }
                String text = listening
                    ? I18n.get("gui.create_schematic_compute.editorkeys.rebind_hint")
                    : I18n.get(a.langKey) + ":  " + cur;
                g.drawString(font, listening ? "§e" + text : text, cx + 6, rowY + 9, 0xFFCCCCCC, false);
                // 行尾"默认"按钮 —— 单动作恢复出厂绑定 / row-end reset-to-default button
                boolean rbHov = mx >= contentRight - 34 && mx <= contentRight - 8 && my >= rowY + 4 && my <= rowY + rowH - 6;
                g.fill(contentRight - 34, rowY + 4, contentRight - 8, rowY + rowH - 6, rbHov ? 0xFF4A5A2A : 0xFF3A3428);
                g.renderOutline(contentRight - 34, rowY + 4, 26, rowH - 10, 0xFF6A8A3A);
                g.drawString(font, "§a" + I18n.get("gui.create_schematic_compute.settings.reset_default"), contentRight - 32, rowY + 9, 0xFFFFFFFF, false);
                rowY += rowH;
            }
            if (rebindConflict != null)
                g.drawString(font, "§c" + rebindConflict, cx, contentBottom - 12, 0xFFFFFFFF, false);
        } else {
            // 节点指南：从 NodeType 元数据自动生成 —— 名称走既有 lang 键，引脚与参数
            // 来自枚举字段，新增节点零成本跟上；说明文案（guide.* lang 键）按需补，
            // 缺省自动隐藏，不写死文案。
            // Node guide: generated from NodeType metadata — names from the existing
            // lang keys, pins and params from the enum fields, so new nodes show up
            // for free. Descriptions (guide.* lang keys) appear only when present.
            var types = NodeType.values();
            int rowH2 = 16;
            int listTop = cy + 18;
            int listBot = contentBottom - 18;
            int visible = Math.max(1, (listBot - listTop) / rowH2);
            int maxScroll = Math.max(0, types.length - visible);
            if (guideScroll < 0) guideScroll = 0;
            if (guideScroll > maxScroll) guideScroll = maxScroll;
            g.drawString(font, "§7" + I18n.get("gui.create_schematic_compute.settings.guide_hint"), cx, cy, 0xFFCCCCCC, false);
            g.enableScissor(cx, listTop, contentRight - 12, listBot);
            NodeType hoveredGuide = null;
            for (int i = guideScroll; i < types.length; i++) {
                int ry = listTop + (i - guideScroll) * rowH2;
                if (ry + rowH2 > listBot) break;
                var t = types[i];
                if (i % 2 == 0) g.fill(cx, ry, contentRight - 12, ry + rowH2, 0xFF222020);
                if (mx >= cx && mx <= contentRight - 12 && my >= ry && my <= ry + rowH2) hoveredGuide = t;
                String name = I18n.get(t.displayName);
                String pins = I18n.get("gui.create_schematic_compute.guide.inputs") + t.inputs
                    + " → " + I18n.get("gui.create_schematic_compute.guide.outputs") + t.outputs;
                g.drawString(font, "§e" + name, cx + 4, ry + 4, 0xFFCCCCCC, false);
                g.drawString(font, "§7" + pins, cx + 170, ry + 4, 0xFF999999, false);
                if (t.paramNames.length > 0) {
                    String params = String.join(", ", t.paramNames);
                    params = font.plainSubstrByWidth("§8" + params, contentW - 300);
                    g.drawString(font, params, cx + 260, ry + 4, 0xFF888888, false);
                }
            }
            g.disableScissor();
            // 逐节点说明（guide.<TYPE> lang 键，TYPE 为枚举名）：悬停该行时显示在内容区底部；
            // 未配置的键不渲染任何内容 —— 后续补文案零代码改动。
            // Per-node description (guide.<TYPE> lang key, TYPE = enum name): shown at the
            // content bottom while hovering that row; absent keys render nothing — adding
            // copy later needs no code change.
            if (hoveredGuide != null) {
                String descKey = "gui.create_schematic_compute.guide." + hoveredGuide.name();
                if (I18n.exists(descKey)) {
                    String desc = font.plainSubstrByWidth(I18n.get(descKey), contentW);
                    g.drawString(font, "§7" + desc, cx, contentBottom - 12, 0xFFCCCCCC, false);
                }
            }
            if (maxScroll > 0) {
                int sbX = contentRight - 8;
                g.fill(sbX, listTop, sbX + 6, listBot, 0xFF2A2822);
                float thumbH = Math.max(12, (listBot - listTop) * (float) visible / types.length);
                float thumbY = listTop + (float) guideScroll / maxScroll * ((listBot - listTop) - thumbH);
                g.fill(sbX + 1, (int) thumbY, sbX + 5, (int) (thumbY + thumbH), 0xFF8B7533);
            }
        }
    }

    // ── 输入（全屏界面消费一切；重绑监听与 ESC 优先）──
    //    Input (the full-screen GUI consumes everything; rebind listening and ESC
    //    take priority).

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        // 重绑监听（最高优先）：鼠标动作捕获本次按键。
        // Rebind listening (top priority): mouse actions capture this button.
        if (listeningBinding >= 0) {
            var a = EditorKeys.Action.values()[listeningBinding];
            if (!a.mouse) {
                rebindConflict = I18n.get("gui.create_schematic_compute.editorkeys.rebind_mouse_only");
            } else if (!EditorKeys.setMouseBinding(a, btn)) {
                rebindConflict = I18n.get("gui.create_schematic_compute.editorkeys.conflict");
            } else {
                rebindConflict = null;
            }
            listeningBinding = -1;
            return true;
        }
        int shift = Math.round(TAB_W * slide);
        // ── 颜色 tab：调色板优先，其次完成/恢复默认/应用/行 ──
        //    Colors tab: palette first, then done/defaults/apply/rows.
        if (tab == 0) {
            // 选项卡列仍可见时优先响应选项卡/返回点击 —— 调整模式下该列已滑出屏幕，
            // 此区域变成平移后的行列表（mx < TAB_W - shift 自然为空）。
            // While the tab column is on-screen it responds first — in adjust mode it
            // has slid off-screen and this region is the shifted row list instead.
            if (mx < TAB_W - shift) {
                int idx = (int) ((my - 36) / 30);
                if (idx >= 0 && idx <= 3) {
                    if (idx == 3) { onClose(); return true; } // 返回项 / back entry
                    if (expanded) collapsePalette();
                    if (idx != 0) stagingInited = false;
                    tab = idx; lastTab = idx;
                }
                return true;
            }
            if (expanded && picker.contains((int) mx, (int) my)) return picker.mouseClicked(mx, my, btn);
            // 确认按钮：把工作色填入槽位 —— 调色板保持展开，不自行关闭。
            // Confirm button: fills the working color into the slot — the palette
            // stays open and never collapses on its own.
            int doneY = paletteDoneY();
            if (expanded && mx >= paletteX() && mx <= paletteX() + ColorPickerWidget.WIDTH
                && my >= doneY && my <= doneY + 18) {
                if (adjustIndex >= 0) NodeRenderer.stagingColors[adjustIndex] = workingColor;
                return true;
            }
            int cx = TAB_W + 12 - shift, cy = cy();
            int contentW = width - TAB_W - 26;
            int btnRowY = height - 8 - 16;
            int listTop = cy + 2, listBot = btnRowY - 6;
            // 收起/展开 + 恢复默认 / 应用 / collapse toggle + defaults + apply
            if (my >= btnRowY && my <= btnRowY + 16) {
                // 展开形态下点击 = 收起调色板；收起形态下点击 = 展开并绑定当前槽位。
                // Clicking in the expanded form collapses the palette; in the collapsed
                // form it expands and binds the current slot.
                if (mx >= cx && mx <= cx + 64) {
                    if (expanded) collapsePalette();
                    else beginAdjust(adjustIndex >= 0 ? adjustIndex : 0);
                    return true;
                }
                if (mx >= cx + 72 && mx <= cx + 142) {
                    NodeRenderer.stagingColors = NodeRenderer.DEFAULT_COLORS.clone();
                    rebindPicker(); return true;
                }
                if (mx >= cx + 150 && mx <= cx + 220) {
                    NodeRenderer.setColors(NodeRenderer.stagingColors.clone());
                    NodeRenderer.saveColorConfig();
                    return true;
                }
            }
            // 颜色行：调整按钮 / color rows: adjust buttons
            if (my >= listTop && my < listBot) {
                int idx = colorScroll + (int) ((my - listTop) / COLOR_ROW_H);
                if (idx >= 0 && idx < NodeRenderer._NUM_COLORS) {
                    int btnX = cx + contentW - 52;
                    if (mx >= btnX && mx <= btnX + 44) { beginAdjust(idx); return true; }
                }
            }
            return true;
        }
        // 左侧选项卡列（列内空白也消费 —— 全屏界面不穿透）；末尾返回项关闭界面。
        // Left tab column (blank areas included — the full-screen GUI never falls
        // through); the trailing back entry closes the screen.
        if (mx < TAB_W) {
            int idx = (int) ((my - 36) / 30);
            if (idx >= 0 && idx <= 3) {
                if (idx == 3) { onClose(); return true; } // 返回项 / back entry
                if (expanded) collapsePalette();             // 收起调色板 / collapse the palette
                if (tab == 0 && idx != 0) stagingInited = false; // 离开颜色 tab 丢弃未应用暂存 / leaving colors discards unapplied staging
                tab = idx; lastTab = idx;
            }
            return true;
        }
        // 键位行命中 → 进入监听（渲染与命中共用同一行几何）
        // Key-binding row hit → start listening (render and hit-test share geometry).
        if (tab == 1 && my >= cy() && my <= cy() + EditorKeys.Action.values().length * 26) {
            int idx = (int) ((my - cy()) / 26);
            if (idx >= 0 && idx < EditorKeys.Action.values().length) {
                // 行尾"默认"按钮 → 恢复该动作的出厂绑定 / row-end button → restore default
                int rbX = width - 48;
                if (mx >= rbX && mx <= rbX + 26) {
                    EditorKeys.resetToDefault(EditorKeys.Action.values()[idx]);
                    rebindConflict = null; return true;
                }
                listeningBinding = idx; rebindConflict = null; return true;
            }
        }
        return true; // 全屏设置界面消费一切点击 / the full-screen settings GUI consumes all clicks
    }

    @Override
    public boolean keyPressed(int key, int sc, int mod) {
        // 重绑监听（最高优先）：键盘动作捕获本次按键（连修饰一起），ESC 取消。
        // Rebind listening (top priority): keyboard actions capture this key together
        // with the current modifiers; ESC cancels.
        if (listeningBinding >= 0) {
            if (key == 256) { listeningBinding = -1; return true; }
            var a = EditorKeys.Action.values()[listeningBinding];
            if (a.mouse) {
                rebindConflict = I18n.get("gui.create_schematic_compute.editorkeys.rebind_key_only");
            } else if (!EditorKeys.setKeyBinding(a, key,
                (Screen.hasControlDown() ? EditorKeys.MOD_CTRL : 0)
                | (Screen.hasShiftDown() ? EditorKeys.MOD_SHIFT : 0)
                | (Screen.hasAltDown() ? EditorKeys.MOD_ALT : 0))) {
                rebindConflict = I18n.get("gui.create_schematic_compute.editorkeys.conflict");
            } else {
                rebindConflict = null;
            }
            listeningBinding = -1;
            return true;
        }
        // 调色板 HEX 输入框聚焦时转发按键（退格/方向键等），ESC 仍归本界面。
        // While the palette's hex input is focused, forward keys (backspace/arrows);
        // ESC still belongs to this screen.
        if (expanded && picker.isHexFocused() && key != 256) return picker.keyPressed(key, sc, mod);
        if (key == 256) {
            if (expanded) { collapsePalette(); return true; } // ESC 先收起调色板 / ESC collapses the palette first
            onClose(); return true;
        }
        // 界面内没有文本输入框 —— 其余按键一律消费（不落到背后世界）。
        // No text inputs inside — consume every other key (nothing leaks to the
        // world behind).
        return true;
    }

    @Override
    public boolean charTyped(char ch, int mod) {
        // 调色板的 HEX 输入框需要字符事件 / the palette's hex input needs char events
        if (expanded && picker.isVisible()) return picker.charTyped(ch, mod);
        return true; // 无其他文本输入，消费全部字符 / no other text inputs, consume all chars
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        // 颜色 tab：调色板内部滚动（收藏色）优先，其次颜色列表 / colors tab: palette-internal scroll first, then the color list
        if (tab == 0) {
            if (expanded && picker.contains((int) mx, (int) my)) return picker.mouseScrolled(mx, my, sy);
            colorScroll -= (int) Math.signum(sy);
            return true;
        }
        // 节点指南滚轮 —— 仅当光标在右侧内容区时消费 / node-guide wheel, content area only
        if (tab == 2 && mx >= TAB_W) {
            guideScroll -= (int) Math.signum(sy);
            return true;
        }
        return true; // 全屏界面消费一切滚轮 / the full-screen GUI consumes all wheels
    }

    @Override
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) {
        // 调色板拖拽（SV / Hue / Alpha 渐变条）转发到组件 / forward SV/hue/alpha drags to the widget
        if (expanded && picker.isVisible()) return picker.mouseDragged(mx, my, btn, dx, dy);
        return true;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int btn) {
        if (expanded) picker.mouseReleased(mx, my, btn);
        return true;
    }

    @Override
    public void onClose() {
        // 返回编辑器（复用同一实例；setScreen 重新 init 并幂等重 join 编辑会话）。
        // Return to the editor (same instance; setScreen re-inits it and re-joins the
        // edit session idempotently).
        minecraft.setScreen(parent);
    }

    /** 颜色 tab 渲染：16 个可调颜色项（色块 + 名称 + 调整按钮）+ 恢复默认/应用；
     *  调整模式下调色板停靠在右侧（随左滑动画腾出的空间）。
     *  Colors tab rendering: 16 adjustable entries (swatch + name + adjust button)
     *  plus defaults/apply; in adjust mode the palette docks on the right (in the
     *  space freed by the left slide). */
    private void renderColorsTab(GuiGraphics g, int mx, int my, int cx, int cy,
                                 int contentRight, int contentBottom, int contentW) {
        // 进入颜色 tab 时一次性初始化暂存色（与旧 16 色面板行为一致：未应用的修改
        // 在下次进入时丢弃）。
        // Initialize staging colors once per colors-tab visit (same as the old panel:
        // unapplied edits are discarded on the next entry).
        if (!stagingInited) { NodeRenderer.initStaging(); stagingInited = true; }
        int listTop = cy + 2;
        int btnRowY = contentBottom - 16;
        int listBot = btnRowY - 6;
        int visible = Math.max(1, (listBot - listTop) / COLOR_ROW_H);
        int maxScroll = Math.max(0, NodeRenderer._NUM_COLORS - visible);
        if (colorScroll < 0) colorScroll = 0;
        if (colorScroll > maxScroll) colorScroll = maxScroll;

        for (int i = colorScroll; i < NodeRenderer._NUM_COLORS; i++) {
            int ri = i - colorScroll;
            int ry = listTop + ri * COLOR_ROW_H;
            if (ry + COLOR_ROW_H > listBot) break;
            boolean adjustingThis = expanded && adjustIndex == i;
            if (adjustingThis) g.fill(cx, ry, cx + contentW, ry + COLOR_ROW_H - 2, 0xFF2A3A5A);
            else if (ri % 2 == 0) g.fill(cx, ry, cx + contentW, ry + COLOR_ROW_H - 2, 0xFF222020);
            // 色块恒显示暂存色 —— 工作色仅在确认时填入（实时预览会让"确认"失去意义）。
            // The swatch always shows the staging color — the working color is filled
            // only on confirm (a live preview would make "confirm" meaningless).
            g.fill(cx + 2, ry + 4, cx + 18, ry + 18, NodeRenderer.stagingColors[i]);
            g.renderOutline(cx + 2, ry + 4, 16, 14, 0xFF888888);
            // 名称 / name
            g.drawString(font, I18n.get("gui.create_schematic_compute.color." + NodeRenderer.COLOR_KEYS[i]),
                cx + 26, ry + 7, 0xFFCCCCCC, false);
            // 调整按钮 / adjust button
            boolean hov = mx >= cx + contentW - 52 && mx <= cx + contentW - 8
                && my >= ry + 1 && my <= ry + COLOR_ROW_H - 3;
            g.fill(cx + contentW - 52, ry + 1, cx + contentW - 8, ry + COLOR_ROW_H - 3,
                hov ? 0xFF3A4A6A : 0xFF2A3A5A);
            g.renderOutline(cx + contentW - 52, ry + 1, 44, COLOR_ROW_H - 4, NodeRenderer.CSB());
            g.drawString(font, I18n.get("gui.create_schematic_compute.settings.adjust"),
                cx + contentW - 48, ry + 7, 0xFFCCCCFF, false);
        }

        // 底部常驻：收起/展开 + 恢复默认 / 应用
        // bottom row: collapse/expand + defaults + apply
        String toggleLabel = I18n.get(expanded
            ? "gui.create_schematic_compute.settings.collapse"
            : "gui.create_schematic_compute.settings.expand");
        g.fill(cx, btnRowY, cx + 64, btnRowY + 16, expanded ? 0xFF3A4A5A : 0xFF2A3A5A);
        g.renderOutline(cx, btnRowY, 64, 16, NodeRenderer.CSB());
        g.drawString(font, "§f" + toggleLabel, cx + 16, btnRowY + 4, 0xFFFFFFFF, false);
        g.fill(cx + 72, btnRowY, cx + 142, btnRowY + 16, 0xFF3A3428);
        g.renderOutline(cx + 72, btnRowY, 70, 16, NodeRenderer.CSB());
        g.drawString(font, "§7" + I18n.get("gui.create_schematic_compute.color.defaults"), cx + 82, btnRowY + 4, 0xFFFFFFFF, false);
        g.fill(cx + 150, btnRowY, cx + 220, btnRowY + 16, 0xFF3A5A2A);
        g.renderOutline(cx + 150, btnRowY, 70, 16, 0xFF5A8A3A);
        g.drawString(font, "§a" + I18n.get("gui.create_schematic_compute.color.apply"), cx + 166, btnRowY + 4, 0xFFFFFFFF, false);

        // 展开形态：调色板停靠右侧（小窗口按高度缩放）。填色由调色板自带的确认键完成
        // （persistent —— 只填色不关闭）。
        // Expanded form: docked palette (scaled down on short windows). Filling is done
        // by the palette's own confirm key (persistent — fills without closing).
        if (expanded) {
            picker.setScale(paletteScale(height));
            picker.setPosition(paletteX(), paletteY());
            picker.render(g, mx, my);
            // 确认按钮：把工作色填入槽位 —— 调色板保持展开，不自行关闭。
            // Confirm button: fills the working color into the slot — the palette
            // stays open and never collapses on its own.
            int doneY = paletteDoneY();
            boolean fin = mx >= paletteX() && mx <= paletteX() + ColorPickerWidget.WIDTH
                && my >= doneY && my <= doneY + 18;
            g.fill(paletteX(), doneY, paletteX() + ColorPickerWidget.WIDTH,
                doneY + 18, fin ? 0xFF3A5A2A : 0xFF2A3A5A);
            g.renderOutline(paletteX(), doneY, ColorPickerWidget.WIDTH,
                18, 0xFF5A8A3A);
            g.drawString(font, "§a" + I18n.get("gui.create_schematic_compute.color.done"),
                paletteX() + 66, doneY + 5, 0xFFFFFFFF, false);
        }
    }

    /** 开始调整某个颜色槽：切换到展开形态（整个界面左滑），调色板绑定该槽的工作色
     *  —— 实时写入工作色，确认键才填入槽位。
     *  Begin adjusting a color slot: switch to the expanded form (UI slides left)
     *  and bind the palette to that slot's working color — tweaks write the working
     *  color live, the confirm button fills it into the slot. */
    private void beginAdjust(int idx) {
        adjustIndex = idx;
        workingColor = NodeRenderer.stagingColors[idx];
        if (!expanded) {
            expanded = true;
            picker.setEmbedded(true);
            picker.setScale(paletteScale(height));
            // 双回调：liveUpdate 实时预览工作色；onSelect（组件确认键）把颜色填入槽位。
            // setPersistent 让组件确认键不自行关闭。
            // Dual callbacks: liveUpdate previews the working color; onSelect (the
            // widget's confirm key) fills it into the slot. setPersistent keeps the
            // widget's confirm from closing itself.
            picker.setPersistent(true);
            picker.open(0, 0, workingColor, c -> fillWorkingColor(c), c -> workingColor = c, false);
            picker.setPosition(paletteX(), paletteY());
        } else {
            picker.rebind(workingColor, c -> fillWorkingColor(c), c -> workingColor = c);
        }
    }

    /** 填色：更新工作色并落入当前调整的槽位（调色板确认键调用）。
     *  Fill: update the working color and stamp it into the slot being adjusted
     *  (invoked by the palette's confirm key). */
    private void fillWorkingColor(int c) {
        workingColor = c;
        if (adjustIndex >= 0) NodeRenderer.stagingColors[adjustIndex] = c;
    }

    /** 收起调色板：切回收起形态（界面滑回，选项卡列恢复）。 / Collapse the palette: switch back to the collapsed form (the UI slides back, tab column returns). */
    private void collapsePalette() {
        expanded = false;
        picker.close();
    }

    /** Defaults/暂存重置后，把展开中的调色板重新绑定到当前槽位色。 / After a staging reset, rebind the open palette to the current slot's color. */
    private void rebindPicker() {
        if (expanded && adjustIndex >= 0) {
            workingColor = NodeRenderer.stagingColors[adjustIndex];
            picker.rebind(workingColor, c -> fillWorkingColor(c), c -> workingColor = c);
        }
    }

    /** 调色板停靠位置（内容区右侧）。 / docked palette position (right side of the content area). */
    private int paletteX() { return width - ColorPickerWidget.WIDTH - 14; }
    private int paletteY() { return 10; }

    /** 调色板缩放：矮窗口按可用高度缩小（嵌入态原高 310px，另需给确认按钮留位）。
     *  Palette scale: shrink to the available height on short windows (embedded
     *  height is 310px, plus room for the confirm button). */
    private float paletteScale(int h) { return Math.min(1f, (h - paletteY() - 28) / 310f); }

    /** 确认按钮 y：调色板底缘（含缩放）+ 2px 间距。 / confirm-button y: palette bottom (scaled) + 2px gap. */
    private int paletteDoneY() { return paletteY() + (int) (310 * paletteScale(height)) + 2; }


    /** 内容区起始 y（渲染与命中共用）。 / content-area top y (shared by render and hit-test). */
    private static int cy() { return 8; }

    /** GLFW 键码的可读名（设置界面显示用）。 / Readable name for a GLFW keycode (settings UI). */
    private static String keyName(int k) {
        if (k >= 65 && k <= 90) return String.valueOf((char) ('A' + (k - 65)));
        if (k >= 48 && k <= 57) return String.valueOf((char) ('0' + (k - 48)));
        return switch (k) {
            case 256 -> "Esc"; case 257 -> "Enter"; case 258 -> "Tab"; case 259 -> "Backspace";
            case 260 -> "Ins"; case 261 -> "Del"; case 263 -> "Left"; case 262 -> "Right";
            case 265 -> "Up"; case 264 -> "Down"; case 266 -> "PgUp"; case 267 -> "PgDn";
            case 268 -> "Home"; case 269 -> "End"; default -> "Key " + k;
        };
    }
}
