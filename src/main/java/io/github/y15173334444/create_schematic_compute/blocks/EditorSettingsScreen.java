package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.client.colorpicker.ColorPickerWidget;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

/**
 * 编辑器设置：独立全屏 Screen —— 左侧选项卡列（界面颜色 / 键位绑定 / 节点指南），
 * 右侧内容区。从编辑器顶栏的设置按钮进入；关闭后返回编辑器。
 * 界面 chrome（列底 / 行底 / 描边 / 强调字）全部取自主题色（NodeRenderer 的
 * PBG/PINS/HOV/CSB/ACC 等），随「界面颜色」调整即时生效。
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
 * The screen chrome (column bg / row bg / outlines / accent text) comes from the theme
 * colors (NodeRenderer PBG/PINS/HOV/CSB/ACC…), so it follows the 界面颜色 settings.
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
public class EditorSettingsScreen extends Screen implements EditorSettingsHost {

    /** 关闭后返回的编辑器屏幕（复用同一实例，setScreen 会重新 init）。 / the editor screen returned to on close (reused; setScreen re-inits it). */
    private final Screen parent;

    /** 上次打开时的选项卡（跨多次打开记住位置，与旧弹窗行为一致）。 / last open tab (remembered across opens, matching the old dialog). */
    private static int lastTab = 0;
    /** 当前选项卡；初始值取 lastTab。 / active tab; initialized from lastTab. */
    private int tab = lastTab;

    /** 指南 tab 视图（自本类拆分，docs/gui-decomposition-plan.md 步骤 3）；指南状态随实现搬入该类。 */
    private final EditorSettingsGuideTab guideTab = new EditorSettingsGuideTab(this);
    /** 颜色 tab 视图（同批拆分）；颜色状态随实现搬入该类。 */
    private final EditorSettingsColorsTab colorsTab = new EditorSettingsColorsTab();
    /** 键位 tab 视图（同批拆分，最后一刀）；键位状态随实现搬入该类。 */
    private final EditorSettingsKeysTab keysTab = new EditorSettingsKeysTab(this);

    // ── 颜色调整状态 / color-adjustment state ──

    /** 展开形态：整个界面左滑、调色板停靠右侧（展开/收起按钮与"调整"共同控制）。
     *  Expanded form: the UI slides left and the palette docks right (toggled by the
     *  expand/collapse button and the per-row adjust buttons). */
    private boolean expanded = false;
    /** 左滑动画进度 0..1（渲染每帧推进）。 / slide animation progress 0..1 (advanced per render frame). */
    private float slide = 0f;
    /** 停靠在右侧的调色板（嵌入模式：无浮空外框、不随外部点击关闭）。 / the palette docked on the right (embedded: no floating frame, no outside-click close). */
    private final ColorPickerWidget picker = new ColorPickerWidget();

    private static final int COLOR_ROW_H = 24;

    /** 左侧选项卡列宽度。 / left tab-column width. */
    private static final int TAB_W = 170;

    public EditorSettingsScreen(Screen parent) {
        super(Component.translatable("gui.create_schematic_compute.settings.title"));
        this.parent = parent;
        colorsTab.bind(this);   // 颜色 tab 的宿主（列表几何经 Host 取，颜色状态在 tab 内）
    }

    @Override protected void init() { }

    /** 编辑器不暂停单机游戏，设置界面亦然。 / the editor never pauses the game; neither do its settings. */
    @Override public boolean isPauseScreen() { return false; }

    // ══════ Host 实现（tab 视图的宿主接口）/ Host implementation for the tab views ══════

    @Override public int w() { return width; }
    @Override public int h() { return height; }
    @Override public net.minecraft.client.gui.Font font() { return font; }
    @Override public ColorPickerWidget picker() { return picker; }
    @Override public boolean expanded() { return expanded; }
    @Override public void setExpanded(boolean v) { expanded = v; }
    @Override public int slide() { return Math.round(TAB_W * slide); }
    // 颜色状态随实现搬入 EditorSettingsColorsTab，这里只转发 / colour state lives in the tab now
    /** 开始调整颜色槽（实现体在 EditorSettingsColorsTab）。 / begin adjusting a colour slot, implemented in the tab. */
    @Override public void beginAdjust(int idx) { colorsTab.beginAdjust(idx); }
    /** 收起调色板（实现体在 EditorSettingsColorsTab）。 / collapse the palette, implemented in the tab. */
    @Override public void collapsePalette() { colorsTab.collapsePalette(); }
    @Override public void rebindPicker() { colorsTab.rebindPicker(); }
    @Override public int colorScroll() { return colorsTab.colorScroll(); }
    @Override public void setColorScroll(int v) { colorsTab.setColorScroll(v); }
    @Override public boolean colorScrollbarDrag() { return colorsTab.colorScrollbarDrag(); }
    @Override public void setColorScrollbarDrag(boolean v) { colorsTab.setColorScrollbarDrag(v); }
    @Override public float colorScrollbarDragStartY() { return colorsTab.colorScrollbarDragStartY(); }
    @Override public int colorScrollbarDragStartOff() { return colorsTab.colorScrollbarDragStartOff(); }
    @Override public void setColorScrollbarDragStart(float y, int off) { colorsTab.setColorScrollbarDragStart(y, off); }

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
        // 遮罩用主题画布底 + 固定透明度：任何主题下都保持压暗可读，同时随主题变色。
        // Scrim = theme canvas background at fixed alpha: readable under any theme yet follows it.
        g.fill(0, 0, w, h, NodeRenderer.withAlpha(NodeRenderer.CG(), 0xF0));
        g.fill(-shift, 0, TAB_W - shift, h, NodeRenderer.PBG()); // 列底随主题 panel_bg / column bg follows the theme
        g.fill(TAB_W - shift, 0, TAB_W - shift + 1, h, NodeRenderer.PBR()); // 列右缘分隔线 = 面板边框 / column edge = panel border
        g.drawString(font, "§6§l" + I18n.get("gui.create_schematic_compute.settings.title"), 12 - shift, 12, 0xFFFFFFFF, false);

        // 选项卡（纵向）+ 末尾返回项 / vertical tabs + back entry at the end
        String[] tabs = {
            I18n.get("gui.create_schematic_compute.settings.tab.colors"),
            I18n.get("gui.create_schematic_compute.settings.tab.keys"),
            I18n.get("gui.create_schematic_compute.settings.tab.guide")};
        for (int i = 0; i < tabs.length; i++) {
            int tx = 10 - shift, ty = 36 + i * 30;
            boolean active = i == tab;
            // 激活 tab：悬停高亮底 + 强调字（描边与文字已区分选中态）；非激活 = 内凹井色。
            // Active tab: hover-highlight fill + accent text (state is carried by text);
            // inactive tabs are inset-well color.
            g.fill(tx, ty, tx + TAB_W - 20, ty + 24, active ? NodeRenderer.HOV() : NodeRenderer.PINS());
            g.renderOutline(tx, ty, TAB_W - 20, 24, NodeRenderer.CSB());
            g.drawString(font, tabs[i], tx + 8, ty + 8, active ? NodeRenderer.ACC() : 0xFFAAAAAA, false);
        }
        // 返回项（书签列末尾，样式与选项卡一致、灰字表示动作而非状态）
        // Back entry (end of the tab column, tab-styled with gray text to read as an
        // action rather than a state).
        int btx = 10 - shift, bty = 36 + tabs.length * 30;
        boolean backHov = mx >= btx && mx <= btx + TAB_W - 20 && my >= bty && my <= bty + 24;
        g.fill(btx, bty, btx + TAB_W - 20, bty + 24, backHov ? NodeRenderer.HOV() : NodeRenderer.PINS());
        g.renderOutline(btx, bty, TAB_W - 20, 24, NodeRenderer.CSB());
        g.drawString(font, "§7" + I18n.get("gui.create_schematic_compute.back"), btx + 8, bty + 8, backHov ? 0xFFFFFFFF : 0xFFAAAAAA, false);

        // 内容区（随界面平移）/ content area (slides with the UI)
        int cx = TAB_W + 12 - shift, cy = 8;
        int contentRight = w - 14 - shift, contentBottom = h - 8;
        int contentW = contentRight - cx;
        if (tab == 0) {
            colorsTab.renderColorsTab(g, mx, my, cx, cy, contentRight, contentBottom, contentW);
        } else if (tab == 1) {
            keysTab.renderKeysTab(g, mx, my, cx, cy, contentRight, contentBottom);
        } else {
            // 节点指南：从 NodeType 元数据自动生成 —— 名称走既有 lang 键，引脚与参数
            // 来自枚举字段；详细说明走 guide.* lang 键（悬停/详情面板共用）。
            // Node guide: generated from NodeType metadata — names from the existing
            // lang keys, pins/params from the enum fields; long copy from guide.* keys.
            guideTab.renderGuideTab(g, mx, my, cx, contentBottom);
        }
    }

    // ── 输入（全屏界面消费一切；重绑监听与 ESC 优先）──
    //    Input (the full-screen GUI consumes everything; rebind listening and ESC
    //    take priority).

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        int shift = Math.round(TAB_W * slide);
        // ── 颜色 tab：调色板优先，其次完成/恢复默认/应用/行 ──
        //    Colors tab: palette first, then done/defaults/apply/rows.
        if (tab == 0) {
            // 选项卡列仍可见时优先响应选项卡/返回点击 —— 调整模式下该列已滑出屏幕，
            // 此区域变成平移后的行列表（mx < TAB_W - shift 自然为空）。
            // While the tab column is on-screen it responds first — in adjust mode it
            // has slid off-screen and this region is the shifted row list instead.
            if (mx < TAB_W - shift) { tabColumnClick(my); return true; }
            if (expanded && picker.contains((int) mx, (int) my)) return picker.mouseClicked(mx, my, btn);
            // 确认按钮：把工作色填入槽位 —— 调色板保持展开，不自行关闭。
            // Confirm button: fills the working color into the slot — the palette
            // stays open and never collapses on its own.
            int doneY = paletteDoneY();
            if (expanded && mx >= paletteX() && mx <= paletteX() + ColorPickerWidget.WIDTH
                && my >= doneY && my <= doneY + 18) {
                if (colorsTab.adjustIndex() >= 0) NodeRenderer.stagingColors[colorsTab.adjustIndex()] = colorsTab.workingColor();
                return true;
            }
            int cx = TAB_W + 12 - shift;
            int contentW = width - TAB_W - 26;
            int listTop = colorsListTop(), listBot = colorsListBot();
            // 收起/展开 + 恢复默认 / 应用 / collapse toggle + defaults + apply
            if (my >= listBot + 6 && my <= listBot + 22) {
                // 展开形态下点击 = 收起调色板；收起形态下点击 = 展开并绑定当前槽位。
                // Clicking in the expanded form collapses the palette; in the collapsed
                // form it expands and binds the current slot.
                if (mx >= cx && mx <= cx + 64) {
                    if (expanded) colorsTab.collapsePalette();
                    else colorsTab.beginAdjust(colorsTab.adjustIndex() >= 0 ? colorsTab.adjustIndex() : 0);
                    return true;
                }
                if (mx >= cx + 72 && mx <= cx + 142) {
                    NodeRenderer.stagingColors = NodeRenderer.DEFAULT_COLORS.clone();
                    colorsTab.rebindPicker(); return true;
                }
                if (mx >= cx + 150 && mx <= cx + 220) {
                    NodeRenderer.setColors(NodeRenderer.stagingColors.clone());
                    NodeRenderer.saveColorConfig();
                    return true;
                }
            }
            // 滚动条：thumb 上按下 = 拖拽；thumb 上下轨道 = 翻 3 行（书签面板同款）。
            // Scrollbar: press on the thumb = drag; track above/below = page by 3 rows (bookmark-panel style).
            if (colorsMaxScroll() > 0) {
                int[] sb = colorsScrollbarThumb(cx, contentW);
                if (mx >= sb[0] && mx <= sb[0] + sb[2] && my >= listTop && my <= listBot) {
                    if (my < sb[1]) { colorsTab.setColorScroll(Math.max(0, colorsTab.colorScroll() - 3)); }
                    else if (my > sb[1] + sb[3]) { colorsTab.setColorScroll(Math.min(colorsMaxScroll(), colorsTab.colorScroll() + 3)); }
                    else { colorsTab.setColorScrollbarDrag(true); colorsTab.setColorScrollbarDragStart((float) my, colorsTab.colorScroll()); }
                    return true;
                }
            }
            // 颜色行：调整按钮 / color rows: adjust buttons
            if (my >= listTop && my < listBot) {
                int idx = colorsTab.colorScroll() + (int) ((my - listTop) / EditorSettingsColorsTab.COLOR_ROW_H);
                if (idx >= 0 && idx < NodeRenderer._NUM_COLORS) {
                    int btnX = cx + contentW - 62;
                    if (mx >= btnX && mx <= btnX + 44) { colorsTab.beginAdjust(idx); return true; }
                }
            }
            return true;
        }
        // ── 键位 tab：选项卡列 / 动作行 / 键帽 / 鼠标键 / 清除·确定 ──
        //    Keys tab: tab column / action rows / keycaps / mouse buttons / clear·bind.
        if (tab == 1) {
            // 选项卡列仍可见时优先响应选项卡/返回点击 —— 展开态该列已滑出屏幕，
            // 此区域变成平移后的列表区（mx < TAB_W - shift 自然为空）。
            // While the tab column is on-screen it responds first — in the expanded form it
            // has slid off-screen and this region is the shifted list area instead.
            if (mx < TAB_W - shift) { tabColumnClick(my); return true; }
            var actions = EditorKeys.Action.values();
            int cx = TAB_W + 12 - shift;
            int listRight = expanded ? cx + keysTab.keysListW() : width - 14 - shift;
            int listTop = keysTab.keysListTop(), listBot = keysTab.keysListBot();
            if (expanded && keysTab.keybindTarget() >= 0) {
                // 展开态：先键盘区（键帽 / 鼠标键 / 清除·确定），后动作行。
                // Expanded: keyboard region first (caps / mouse buttons / clear·bind), then rows.
                float u = keysTab.keysUnit();
                int chipsX = width - 14 - EditorSettingsKeysTab.KEYS_CHIPS_W;
                for (int m = 0; m < 3; m++) {
                    int chy = cy() + 2 + m * 24;
                    if (mx >= chipsX && mx <= chipsX + EditorSettingsKeysTab.KEYS_CHIPS_W && my >= chy && my <= chy + 20) {
                        keysTab.handleChipClick(actions[keysTab.keybindTarget()], m); return true;
                    }
                }
                float kx0 = chipsX - 12 - keysTab.keysGridW(u);
                float ky = cy() + 2;
                float gap = keysTab.keysGap(u);
                for (var row : EditorSettingsKeysTab.KEY_ROWS) {
                    float kx = kx0;
                    for (var c : row) {
                        float w = c.w() * u;
                        if (mx >= kx && mx <= kx + w && my >= ky && my <= ky + u) { keysTab.handleKeycapClick(c); return true; }
                        kx += w + gap;
                    }
                    ky += u + 3;
                }
                int[] bar = keysTab.keysBarGeometry(ky, listRight); // 与渲染同一几何 / same geometry as render
                int barY = bar[1];
                int confirmX = width - 14 - 66, clearX = confirmX - 62, defX = clearX - 62, backX = bar[0];
                if (my >= barY && my <= barY + 16) {
                    if (mx >= backX && mx <= backX + 58) { // 删一步 / step-back
                        if (!keysTab.pendingSeq().isEmpty()) keysTab.pendingSeq().remove(keysTab.pendingSeq().size() - 1);
                        keysTab.setRebindConflict(null); return true;
                    }
                    if (mx >= defX && mx <= defX + 56) { // 默认：恢复出厂绑定并重预填 / restore default and re-prefill
                        EditorKeys.resetToDefault(actions[keysTab.keybindTarget()]);
                        keysTab.selectKeybindRow(keysTab.keybindTarget()); return true;
                    }
                    if (mx >= clearX && mx <= clearX + 56) { keysTab.pendingSeq().clear(); keysTab.setLatchedMods(0); keysTab.setRebindConflict(null); return true; } // 清除 / clear
                    if (mx >= confirmX && mx <= confirmX + 66) { keysTab.confirmKeybind(); return true; }                                  // 确定 / bind
                }
            }
            // 滚动条：thumb 上按下 = 拖拽；thumb 上下轨道 = 翻 3 行（颜色列表同款）。
            // Scrollbar: press on the thumb = drag; track above/below = page by 3 rows.
            if (keysTab.keysMaxScroll() > 0) {
                int[] sb = keysTab.keysScrollbarThumb(listRight);
                if (mx >= sb[0] && mx <= sb[0] + sb[2] && my >= listTop && my <= listBot) {
                    if (my < sb[1]) { keysTab.setKeysScroll(Math.max(0, keysTab.keysScroll() - 3)); }
                    else if (my > sb[1] + sb[3]) { keysTab.setKeysScroll(Math.min(keysTab.keysMaxScroll(), keysTab.keysScroll() + 3)); }
                    else { keysTab.setKeysScrollbarDrag(true); keysTab.setKeysScrollbarDragStart((float) my, keysTab.keysScroll()); }
                    return true;
                }
            }
            // 动作行（滚动窗口内）：行 = 选中并展开键盘（渲染与命中共用同一行几何与滚动偏移）。
            for (int i = keysTab.keysScroll(); i < actions.length; i++) {
                int ry = listTop + (i - keysTab.keysScroll()) * EditorSettingsKeysTab.KEY_ROW_H;
                if (ry + EditorSettingsKeysTab.KEY_ROW_H > listBot) break;
                if (mx >= cx && mx <= listRight - 10 && my >= ry && my <= ry + EditorSettingsKeysTab.KEY_ROW_H - 2) {
                    keysTab.selectKeybindRow(i); return true;
                }
            }
            // 收起按钮（列表下方固定位）/ collapse button (fixed below the list)
            int clY = listBot + 4;
            if (expanded && mx >= cx && mx <= cx + 64 && my >= clY && my <= clY + 16) { collapseExpanded(); return true; }
            return true;
        }
        // ── 指南 tab：详情面板吞掉 / 收起按钮 / 滚动条 / 行选中（收起态点行即展开）──
        //    Guide tab: pane swallows clicks / collapse button / scrollbar / row select
        //    (a click expands the detail from the collapsed state).
        if (tab == 2) {
            if (mx < TAB_W - shift) { tabColumnClick(my); return true; }
            int cx = TAB_W + 12 - shift;
            int rowRight = guideTab.guideRowRight();
            int listTop = guideTab.guideListTop(), listBot = guideTab.guideListBot();
            if (expanded && mx >= guideTab.paneX()) return true; // 详情面板吞掉所有点击 / the detail pane swallows clicks
            if (expanded) {
                int clY = guideTab.guideListBot() + 2;
                if (mx >= cx && mx <= cx + 64 && my >= clY && my <= clY + 16) { guideTab.guideCollapse(); return true; }
            }
            if (guideTab.guideMaxScroll() > 0) {
                int[] sb = guideTab.guideScrollbarThumb(rowRight);
                if (mx >= sb[0] && mx <= sb[0] + sb[2] && my >= listTop && my <= listBot) {
                    if (my < sb[1]) { guideTab.setScroll(Math.max(0, guideTab.scroll() - 3)); }
                    else if (my > sb[1] + sb[3]) { guideTab.setScroll(Math.min(guideTab.guideMaxScroll(), guideTab.scroll() + 3)); }
                    else { guideTab.setScrollbarDrag(true); guideTab.setScrollbarDragStart((float) my, guideTab.scroll()); }
                    return true;
                }
            }
            if (my >= listTop && my < listBot && mx >= cx && mx <= rowRight) {
                int idx = guideTab.scroll() + (int) ((my - listTop) / EditorSettingsGuideTab.GUIDE_ROW_H);
                if (idx >= 0 && idx < NodeType.values().length) {
                    guideTab.setTarget(idx);
                    guideTab.setDetailScroll(0);
                    expanded = true; // 点击行 = 选中并展开详情 / a row click selects and expands the detail
                    return true;
                }
            }
            return true;
        }
        // 左侧选项卡列（列内空白也消费 —— 全屏界面不穿透）；末尾返回项关闭界面。
        // Left tab column (blank areas included — the full-screen GUI never falls
        // through); the trailing back entry closes the screen.
        if (mx < TAB_W) tabColumnClick(my);
        return true; // 全屏设置界面消费一切点击 / the full-screen settings GUI consumes all clicks
    }

    /** 选项卡列点击：末项返回界面；重复点击当前 tab 收起其展开区；切换 tab 先收起再切。
     *  Tab-column click: the last entry goes back; re-clicking the active tab collapses
     *  its expansion; switching tabs collapses first. */
    @Override public void tabColumnClick(double my) {
        int idx = (int) ((my - 36) / 30);
        if (idx < 0 || idx > 3) return;
        if (idx == 3) { onClose(); return; } // 返回项 / back entry
        if (expanded) collapseExpanded();
        if (tab == 0 && idx != 0) colorsTab.setStagingInited(false); // 离开颜色 tab 丢弃未应用暂存 / leaving colors discards unapplied staging
        tab = idx; lastTab = idx;
    }

    @Override
    public boolean keyPressed(int key, int sc, int mod) {
        // 调色板 HEX 输入框聚焦时转发按键（退格/方向键等），ESC 仍归本界面。
        // While the palette's hex input is focused, forward keys (backspace/arrows);
        // ESC still belongs to this screen.
        if (expanded && picker.isHexFocused() && key != 256) return picker.keyPressed(key, sc, mod);
        if (key == 256) {
            if (expanded) { collapseExpanded(); return true; } // ESC 先收起展开区（调色板/键盘） / ESC collapses the open palette or keyboard first
            onClose(); return true;
        }
        // 物理键盘一律忽略 —— 键位绑定是纯屏幕操作（点键帽选择 + 确定落绑定）；
        // 无其他文本输入，其余按键全部消费（不落到背后世界）。
        // Physical keys are ignored — rebinding is a pure on-screen flow (pick caps,
        // then Bind). No other text inputs: consume everything else (nothing leaks).
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
            colorsTab.setColorScroll(colorsTab.colorScroll() - (int) Math.signum(sy));
            if (colorsTab.colorScroll() < 0) colorsTab.setColorScroll(0);
            if (colorsTab.colorScroll() > colorsMaxScroll()) colorsTab.setColorScroll(colorsMaxScroll());
            return true;
        }
        // 键位列表滚轮 / key-list wheel
        if (tab == 1) {
            keysTab.setKeysScroll(keysTab.keysScroll() - (int) Math.signum(sy));
            if (keysTab.keysScroll() < 0) keysTab.setKeysScroll(0);
            if (keysTab.keysScroll() > keysTab.keysMaxScroll()) keysTab.setKeysScroll(keysTab.keysMaxScroll());
            return true;
        }
        // 指南滚轮：展开态光标在右侧详情面板内 = 滚动说明行；其余一律滚动左侧节点列表
        // （含展开态左移后的整条列表——旧的 mx >= TAB_W 门槛是收起态列宽留下的判断，
        // 展开后列表已贴到屏幕左缘，光标停在列表左侧时会被门槛吞掉导致无法滚动）。
        // Guide wheel: over the right detail pane (expanded) it scrolls the description;
        // everywhere else it scrolls the node list. The old `mx >= TAB_W` gate is gone —
        // after the slide the list hugs the screen's left edge, so that gate used to
        // swallow the wheel over the list's left half.
        if (tab == 2) {
            if (expanded && mx >= guideTab.paneX()) {
                guideTab.setDetailScroll(guideTab.detailScroll() - (int) Math.signum(sy));
                return true;
            }
            guideTab.setScroll(guideTab.scroll() - (int) Math.signum(sy));
            if (guideTab.scroll() < 0) guideTab.setScroll(0);
            if (guideTab.scroll() > guideTab.guideMaxScroll()) guideTab.setScroll(guideTab.guideMaxScroll());
            return true; // 全屏界面消费一切滚轮 / the full-screen GUI consumes all wheels
        }
        return true;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) {
        // 颜色列表滚动条拖拽优先于调色板转发（两者区域互斥，同一时刻只有一个生效）。
        // Color-list scrollbar drag takes priority over the palette forward (the two
        // regions are disjoint; only one can be active at a time).
        if (colorsTab.colorScrollbarDrag()) { applyColorScrollbarDrag(my); return true; }
        if (keysTab.keysScrollbarDrag()) { keysTab.applyKeysScrollbarDrag(my); return true; }
        if (guideTab.scrollbarDrag()) { guideTab.applyGuideScrollbarDrag(my); return true; }
        // 调色板拖拽（SV / Hue / Alpha 渐变条）转发到组件 / forward SV/hue/alpha drags to the widget
        if (expanded && picker.isVisible()) return picker.mouseDragged(mx, my, btn, dx, dy);
        return true;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int btn) {
        colorsTab.setColorScrollbarDrag(false);
        keysTab.setKeysScrollbarDrag(false);
        guideTab.setScrollbarDrag(false);
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

    /** 收起当前 tab 的展开区（颜色调色板 / 键位键盘 / 指南详情）并清空各自的暂选状态。
     *  Collapse whichever expansion is open (palette / keyboard / guide detail) and clear its pending state. */
    @Override public void collapseExpanded() {
        if (tab == 0) { colorsTab.collapsePalette(); return; }
        expanded = false;
        keysTab.collapseRebind();
        guideTab.setTarget(-1);
        guideTab.setDetailScroll(0);
        guideTab.setScrollbarDrag(false);
    }
    /** 调色板停靠位置（内容区右侧）。 / docked palette position (right side of the content area). */
    @Override public int paletteX() { return width - ColorPickerWidget.WIDTH - 14; }
    @Override public int paletteY() { return 10; }

    /** 调色板缩放：矮窗口按可用高度缩小（嵌入态原高 310px，另需给确认按钮留位）。
     *  Palette scale: shrink to the available height on short windows (embedded
     *  height is 310px, plus room for the confirm button). */
    @Override public float paletteScale(int h) { return Math.min(1f, (h - paletteY() - 28) / 310f); }

    /** 确认按钮 y：调色板底缘（含缩放）+ 2px 间距。 / confirm-button y: palette bottom (scaled) + 2px gap. */
    @Override public int paletteDoneY() { return paletteY() + (int) (310 * paletteScale(height)) + 2; }

    /** 内容区起始 y（渲染与命中共用，三个 tab 同源）。 / content-area top y. */
    @Override public int cy() { return 8; }

    // ── 颜色列表几何（渲染 / 命中 / 拖拽共用单一来源） ──
    // ── Color-list geometry (single source shared by render, hit-testing and dragging) ──

    /** 列表顶部 y。 / list top y. */
    @Override public int colorsListTop() { // cy() 恒为 8；静态几何里内联（原为静态方法，现由 Host 提供实例方法）
        return 8 + 2; }
    /** 列表底部 y（底部按钮行上方 6px）。 / list bottom y (6px above the bottom button row). */
    @Override public int colorsListBot() { return height - 8 - 16 - 6; }
    @Override public int colorsVisibleRows() { return Math.max(1, (colorsListBot() - colorsListTop()) / COLOR_ROW_H); }
    @Override public int colorsMaxScroll() { return Math.max(0, NodeRenderer._NUM_COLORS - colorsVisibleRows()); }
    /** 行区右缘：为滚动条预留 10px 条带。 / row right edge: a 10px strip is reserved for the scrollbar. */
    @Override public int colorsRowRight(int cx, int contentW) { return cx + contentW - 10; }
    /** 滚动条 thumb {x, y, w, h}；轨道与 thumb 同宽、纵跨列表全高。 / scrollbar thumb {x, y, w, h}; the track shares x/w and spans the full list height. */
    @Override public int[] colorsScrollbarThumb(int cx, int contentW) {
        int trackH = colorsListBot() - colorsListTop();
        int thumbH = Math.max(12, trackH * colorsVisibleRows() / NodeRenderer._NUM_COLORS);
        int maxScroll = colorsMaxScroll();
        int thumbY = colorsListTop() + (maxScroll > 0 ? (trackH - thumbH) * colorsTab.colorScroll() / maxScroll : 0);
        return new int[]{cx + contentW - 8, thumbY, 6, thumbH};
    }

    /** 拖拽推进颜色列表：thumb 相对增量换算为行偏移（书签 / 添加菜单滚动条同款）。
     *  Advance the color list by the dragged thumb delta (bookmark / add-menu scrollbar style). */
    @Override public void applyColorScrollbarDrag(double my) {
        int maxScroll = colorsMaxScroll();
        if (maxScroll <= 0) return;
        int trackH = colorsListBot() - colorsListTop();
        int thumbH = Math.max(12, trackH * colorsVisibleRows() / NodeRenderer._NUM_COLORS);
        if (trackH - thumbH <= 0) return;
        float delta = (float) (my - colorsTab.colorScrollbarDragStartY()) / (trackH - thumbH);
        int newOff = colorsTab.colorScrollbarDragStartOff() + Math.round(delta * maxScroll);
        colorsTab.setColorScroll(Math.max(0, Math.min(maxScroll, newOff)));
    }
}
