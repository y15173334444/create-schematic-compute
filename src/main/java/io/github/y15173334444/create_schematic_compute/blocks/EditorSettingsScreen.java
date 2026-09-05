package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.client.colorpicker.ColorPickerWidget;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

import java.util.List;

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

    /** 键位 tab：展开虚拟键盘后正在设置的动作 ordinal，-1 = 未选中/收起。
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
    /** 颜色列表滚动条拖拽中（thumb 上按下未松开）。 / the color-list scrollbar thumb is being dragged. */
    private boolean colorScrollbarDrag = false;
    /** 拖拽起点鼠标 y 与起始偏移（相对增量式，书签面板同款）。 / drag-start mouse y and start offset (relative delta, bookmark-panel style). */
    private float colorScrollbarDragStartY = 0f;
    private int colorScrollbarDragStartOff = 0;
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
            renderKeysTab(g, mx, my, cx, cy, contentRight, contentBottom);
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
                if (adjustIndex >= 0) NodeRenderer.stagingColors[adjustIndex] = workingColor;
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
            // 滚动条：thumb 上按下 = 拖拽；thumb 上下轨道 = 翻 3 行（书签面板同款）。
            // Scrollbar: press on the thumb = drag; track above/below = page by 3 rows (bookmark-panel style).
            if (colorsMaxScroll() > 0) {
                int[] sb = colorsScrollbarThumb(cx, contentW);
                if (mx >= sb[0] && mx <= sb[0] + sb[2] && my >= listTop && my <= listBot) {
                    if (my < sb[1]) { colorScroll = Math.max(0, colorScroll - 3); }
                    else if (my > sb[1] + sb[3]) { colorScroll = Math.min(colorsMaxScroll(), colorScroll + 3); }
                    else { colorScrollbarDrag = true; colorScrollbarDragStartY = (float) my; colorScrollbarDragStartOff = colorScroll; }
                    return true;
                }
            }
            // 颜色行：调整按钮 / color rows: adjust buttons
            if (my >= listTop && my < listBot) {
                int idx = colorScroll + (int) ((my - listTop) / COLOR_ROW_H);
                if (idx >= 0 && idx < NodeRenderer._NUM_COLORS) {
                    int btnX = cx + contentW - 62;
                    if (mx >= btnX && mx <= btnX + 44) { beginAdjust(idx); return true; }
                }
            }
            return true;
        }
        // ── 键位 tab：选项卡列 / 动作行 / 键帽 / 鼠标键 / 清除·确定 ──
        //    Keys tab: tab column / action rows / keycaps / mouse buttons / clear·bind.
        if (tab == 1) {
            // 选项卡列仍可见时优先响应选项卡/返回点击 —— 展开态该列已滑出屏幕，
            // 此区域变成平移后的列表区（mx < TAB_W - shift 自然为空）。
            if (mx < TAB_W - shift) { tabColumnClick(my); return true; }
            var actions = EditorKeys.Action.values();
            int cx = TAB_W + 12 - shift;
            int listRight = expanded ? cx + keysListW() : width - 14 - shift;
            int listTop = keysListTop(), listBot = keysListBot();
            if (expanded && keybindTarget >= 0) {
                // 展开态：先键盘区（键帽 / 鼠标键 / 清除·确定），后动作行。
                // Expanded: keyboard region first (caps / mouse buttons / clear·bind), then rows.
                float u = keysUnit();
                int chipsX = width - 14 - KEYS_CHIPS_W;
                for (int m = 0; m < 3; m++) {
                    int chy = cy() + 2 + m * 24;
                    if (mx >= chipsX && mx <= chipsX + KEYS_CHIPS_W && my >= chy && my <= chy + 20) {
                        handleChipClick(actions[keybindTarget], m); return true;
                    }
                }
                float kx0 = chipsX - 12 - keysGridW(u);
                float ky = cy() + 2;
                float gap = keysGap(u);
                for (var row : KEY_ROWS) {
                    float kx = kx0;
                    for (var c : row) {
                        float w = c.w() * u;
                        if (mx >= kx && mx <= kx + w && my >= ky && my <= ky + u) { handleKeycapClick(c); return true; }
                        kx += w + gap;
                    }
                    ky += u + 3;
                }
                int[] bar = keysBarGeometry(ky, listRight); // 与渲染同一几何 / same geometry as render
                int barY = bar[1];
                int confirmX = width - 14 - 66, clearX = confirmX - 62, defX = clearX - 62, backX = bar[0];
                if (my >= barY && my <= barY + 16) {
                    if (mx >= backX && mx <= backX + 58) { // 删一步 / step-back
                        if (!pendingSeq.isEmpty()) pendingSeq.remove(pendingSeq.size() - 1);
                        rebindConflict = null; return true;
                    }
                    if (mx >= defX && mx <= defX + 56) { // 默认：恢复出厂绑定并重预填 / restore default and re-prefill
                        EditorKeys.resetToDefault(actions[keybindTarget]);
                        selectKeybindRow(keybindTarget); return true;
                    }
                    if (mx >= clearX && mx <= clearX + 56) { pendingSeq.clear(); latchedMods = 0; rebindConflict = null; return true; } // 清除 / clear
                    if (mx >= confirmX && mx <= confirmX + 66) { confirmKeybind(); return true; }                                  // 确定 / bind
                }
            }
            // 滚动条：thumb 上按下 = 拖拽；thumb 上下轨道 = 翻 3 行（颜色列表同款）。
            // Scrollbar: press on the thumb = drag; track above/below = page by 3 rows.
            if (keysMaxScroll() > 0) {
                int[] sb = keysScrollbarThumb(listRight);
                if (mx >= sb[0] && mx <= sb[0] + sb[2] && my >= listTop && my <= listBot) {
                    if (my < sb[1]) { keysScroll = Math.max(0, keysScroll - 3); }
                    else if (my > sb[1] + sb[3]) { keysScroll = Math.min(keysMaxScroll(), keysScroll + 3); }
                    else { keysScrollbarDrag = true; keysScrollbarDragStartY = (float) my; keysScrollbarDragStartOff = keysScroll; }
                    return true;
                }
            }
            // 动作行（滚动窗口内）：行 = 选中并展开键盘（渲染与命中共用同一行几何与滚动偏移）。
            for (int i = keysScroll; i < actions.length; i++) {
                int ry = listTop + (i - keysScroll) * KEY_ROW_H;
                if (ry + KEY_ROW_H > listBot) break;
                if (mx >= cx && mx <= listRight - 10 && my >= ry && my <= ry + KEY_ROW_H - 2) {
                    selectKeybindRow(i); return true;
                }
            }
            // 收起按钮（列表下方固定位）/ collapse button (fixed below the list)
            int clY = listBot + 4;
            if (expanded && mx >= cx && mx <= cx + 64 && my >= clY && my <= clY + 16) { collapseExpanded(); return true; }
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
    private void tabColumnClick(double my) {
        int idx = (int) ((my - 36) / 30);
        if (idx < 0 || idx > 3) return;
        if (idx == 3) { onClose(); return; } // 返回项 / back entry
        if (expanded) collapseExpanded();
        if (tab == 0 && idx != 0) stagingInited = false; // 离开颜色 tab 丢弃未应用暂存 / leaving colors discards unapplied staging
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
            colorScroll -= (int) Math.signum(sy);
            if (colorScroll < 0) colorScroll = 0;
            if (colorScroll > colorsMaxScroll()) colorScroll = colorsMaxScroll();
            return true;
        }
        // 键位列表滚轮 / key-list wheel
        if (tab == 1) {
            keysScroll -= (int) Math.signum(sy);
            if (keysScroll < 0) keysScroll = 0;
            if (keysScroll > keysMaxScroll()) keysScroll = keysMaxScroll();
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
        // 颜色列表滚动条拖拽优先于调色板转发（两者区域互斥，同一时刻只有一个生效）。
        // Color-list scrollbar drag takes priority over the palette forward (the two
        // regions are disjoint; only one can be active at a time).
        if (colorScrollbarDrag) { applyColorScrollbarDrag(my); return true; }
        if (keysScrollbarDrag) { applyKeysScrollbarDrag(my); return true; }
        // 调色板拖拽（SV / Hue / Alpha 渐变条）转发到组件 / forward SV/hue/alpha drags to the widget
        if (expanded && picker.isVisible()) return picker.mouseDragged(mx, my, btn, dx, dy);
        return true;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int btn) {
        colorScrollbarDrag = false;
        keysScrollbarDrag = false;
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
        int listTop = colorsListTop();
        int btnRowY = colorsListBot() + 6;
        int listBot = colorsListBot();
        int visible = colorsVisibleRows();
        int maxScroll = colorsMaxScroll();
        if (colorScroll < 0) colorScroll = 0;
        if (colorScroll > maxScroll) colorScroll = maxScroll;
        int rowRight = colorsRowRight(cx, contentW);

        for (int i = colorScroll; i < NodeRenderer._NUM_COLORS; i++) {
            int ri = i - colorScroll;
            int ry = listTop + ri * COLOR_ROW_H;
            if (ry + COLOR_ROW_H > listBot) break;
            boolean adjustingThis = expanded && adjustIndex == i;
            if (adjustingThis) g.fill(cx, ry, rowRight, ry + COLOR_ROW_H - 2, 0xFF2A3A5A);
            else if (ri % 2 == 0) g.fill(cx, ry, rowRight, ry + COLOR_ROW_H - 2, 0xFF222020);
            // 色块恒显示暂存色 —— 工作色仅在确认时填入（实时预览会让"确认"失去意义）。
            // The swatch always shows the staging color — the working color is filled
            // only on confirm (a live preview would make "confirm" meaningless).
            g.fill(cx + 2, ry + 4, cx + 18, ry + 18, NodeRenderer.stagingColors[i]);
            g.renderOutline(cx + 2, ry + 4, 16, 14, 0xFF888888);
            // 名称 / name
            g.drawString(font, I18n.get("gui.create_schematic_compute.color." + NodeRenderer.COLOR_KEYS[i]),
                cx + 26, ry + 7, 0xFFCCCCCC, false);
            // 调整按钮 / adjust button
            boolean hov = mx >= rowRight - 52 && mx <= rowRight - 8
                && my >= ry + 1 && my <= ry + COLOR_ROW_H - 3;
            g.fill(rowRight - 52, ry + 1, rowRight - 8, ry + COLOR_ROW_H - 3,
                hov ? 0xFF3A4A6A : 0xFF2A3A5A);
            g.renderOutline(rowRight - 52, ry + 1, 44, COLOR_ROW_H - 4, NodeRenderer.CSB());
            g.drawString(font, I18n.get("gui.create_schematic_compute.settings.adjust"),
                rowRight - 48, ry + 7, 0xFFCCCCFF, false);
        }

        // 滚动条（thumb 可拖拽）——几何与命中/拖拽共用 colorsScrollbarThumb。
        // Scrollbar (draggable thumb) — geometry shared with hit-testing/dragging via colorsScrollbarThumb.
        if (maxScroll > 0) {
            int[] sb = colorsScrollbarThumb(cx, contentW);
            g.fill(sb[0], listTop, sb[0] + sb[2], listBot, 0xFF2A2822);
            g.fill(sb[0] + 1, sb[1], sb[0] + sb[2] - 1, sb[1] + sb[3], 0xFF8B7533);
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

    /** 虚拟键帽：label 显示文本、code GLFW 键码（0 = 纯修饰开关）、w 宽度（键帽单位）、
     *  modBit 非零 = 修饰开关（点击翻转该修饰位，不进主键槽）。
     *  A virtual keycap: label, GLFW code (0 = pure modifier toggle), width in keycap
     *  units, modBit != 0 = modifier toggle (clicks flip the bit, never the main-key slot). */
    private record Keycap(String label, int code, float w, int modBit) { }

    private static Keycap cap(String label, int code, float w, int modBit) { return new Keycap(label, code, w, modBit); }

    /** 无 F 行紧凑配列：Esc + 数字行 + 三行字母 + 底部修饰行，右缘塞下 Home 与方向键。
     *  Esc 键帽 = 清空当前选择（Esc 是编辑器保留键，不可绑）；L/R 修饰键帽共用同一开关位。
     *  No-F-row compact layout. The Esc cap clears the selection (Esc is a reserved
     *  editor key, not bindable); the L/R modifier caps share one toggle bit. */
    private static final Keycap[][] KEY_ROWS = {
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
    private int keysListW() { return Math.max(110, Math.min(260, width / 3)); }

    // ── 键位列表几何（渲染 / 命中 / 拖拽共用单一来源） ──
    // ── Key-list geometry (single source shared by render, hit-testing and dragging) ──

    /** 列表顶部 y。 / list top y. */
    private static int keysListTop() { return cy() + 2; }
    /** 列表底部 y（内容区底再上提 24px，给「收起」按钮留固定位置）。 / list bottom y (24px above the content bottom, reserving a fixed slot for the Collapse button). */
    private int keysListBot() { return height - 8 - 24; }
    private int keysVisibleRows() { return Math.max(1, (keysListBot() - keysListTop()) / KEY_ROW_H); }
    private int keysMaxScroll() { return Math.max(0, EditorKeys.Action.values().length - keysVisibleRows()); }
    private static final int KEYS_CHIPS_W = 72;

    /** 键帽单位尺寸：可用宽度 ÷ 最宽行（≈15.5 单位），钳制 10..24（窄窗口自动缩小）。 / keycap unit: available width ÷ the widest row (~15.5 units), clamped 10..24. */
    private float keysUnit() {
        int avail = width - 14 - keysListW() - 12 - KEYS_CHIPS_W - 12 - 24;
        return Math.max(10f, Math.min(24f, avail / 15.75f));
    }

    /** 键帽间距：小键帽缩到 1px 省宽（渲染与命中共用同一规则）。 / cap gap: 1px for small caps (shared by render and hit-testing). */
    private static float keysGap(float u) { return u < 13f ? 1f : 2f; }

    /** 键盘网格总宽（像素，含键帽间距）。 / keyboard grid width in pixels (gaps included). */
    private static float keysGridW(float u) {
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
    private void renderKeysTab(GuiGraphics g, int mx, int my, int cx, int cy,
                               int contentRight, int contentBottom) {
        var actions = EditorKeys.Action.values();
        int listRight = expanded ? cx + keysListW() : contentRight;
        int listTop = keysListTop(), listBot = keysListBot();
        int maxScroll = keysMaxScroll();
        if (keysScroll < 0) keysScroll = 0;
        if (keysScroll > maxScroll) keysScroll = maxScroll;
        for (int i = keysScroll; i < actions.length; i++) {
            int ry = listTop + (i - keysScroll) * KEY_ROW_H;
            if (ry + KEY_ROW_H > listBot) break;
            var a = actions[i];
            boolean selected = expanded && keybindTarget == i;
            boolean hov = !selected && mx >= cx && mx <= listRight - 10 && my >= ry && my <= ry + KEY_ROW_H - 2;
            if (selected) g.fill(cx, ry, listRight, ry + KEY_ROW_H - 2, 0xFF2A3A5A);
            else if (hov) g.fill(cx, ry, listRight, ry + KEY_ROW_H - 2, 0xFF3A4A3A);
            String cur;
            if (a.mouse) {
                cur = I18n.get("gui.create_schematic_compute.editorkeys.mouse." + EditorKeys.mouseButton(a));
            } else {
                cur = seqText(EditorKeys.sequence(a));
            }
            // 动作名 + 当前绑定，超宽按窄列截断（预留滚动条条带；展开态列表变窄时防压进键盘区）。
            // Action name + current binding, truncated to the narrowed list width (reserving
            // the scrollbar strip).
            String text = I18n.get("gui.create_schematic_compute." + a.langKey) + ":  " + cur;
            text = font.plainSubstrByWidth(text, listRight - (cx + 6) - 14);
            g.drawString(font, text, cx + 6, ry + 7, 0xFFCCCCCC, false);
        }
        // 滚动条（thumb 可拖拽）——几何与命中/拖拽共用 keysScrollbarThumb。
        // Scrollbar (draggable thumb) — geometry shared with hit-testing/dragging.
        if (maxScroll > 0) {
            int[] sb = keysScrollbarThumb(listRight);
            g.fill(sb[0], listTop, sb[0] + sb[2], listBot, 0xFF2A2822);
            g.fill(sb[0] + 1, sb[1], sb[0] + sb[2] - 1, sb[1] + sb[3], 0xFF8B7533);
        }
        // 冲突提示：收起态在列表底部；展开态移到操作条下方（contentBottom-12 处会被
        // 「收起」按钮盖住 —— 按钮后画）。
        // Clash message: list bottom when collapsed; below the bar when expanded (at
        // contentBottom-12 it is painted over by the Collapse button, which draws later).
        if (rebindConflict != null && !expanded)
            g.drawString(font, "§c" + rebindConflict, cx, contentBottom - 12, 0xFFFFFFFF, false);
        if (!expanded || keybindTarget < 0) return;

        // ── 展开态：右侧虚拟键盘 + 鼠标三键 + 底部 预览/默认/清除/确定 ──
        // Expanded: virtual keyboard + mouse buttons on the right, preview/default/clear/bind bar.
        float u = keysUnit();
        int chipsX = width - 14 - KEYS_CHIPS_W;
        var target = actions[keybindTarget];

        // 鼠标三键（竖排；点击即直接绑定 —— 鼠标动作无修饰概念）。当前绑定的键绿描边
        // （与键帽的现值描边同语义）。
        // Mouse buttons (vertical; a click binds immediately — no modifier concept). The
        // currently bound button gets the green outline, same semantics as the keycaps.
        for (int m = 0; m < 3; m++) {
            int chy = cy + 2 + m * 24;
            boolean chov = mx >= chipsX && mx <= chipsX + KEYS_CHIPS_W && my >= chy && my <= chy + 20;
            boolean bound = target.mouse && EditorKeys.mouseButton(target) == m;
            g.fill(chipsX, chy, chipsX + KEYS_CHIPS_W, chy + 20, chov ? 0xFF3A4A6A : 0xFF2A2A3A);
            g.renderOutline(chipsX, chy, KEYS_CHIPS_W, 20, bound ? 0xFF5A8A3A : NodeRenderer.CSB());
            g.drawString(font, I18n.get("gui.create_schematic_compute.editorkeys.mouse." + m),
                chipsX + 8, chy + 6, bound ? 0xFFCCFFCC : 0xFFCCCCFF, false);
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
                int bg = hov ? 0xFF3A4A6A : 0xFF2A2832;
                if (c.modBit() != 0 && (shownMods & c.modBit()) != 0) bg = 0xFF2A4A6A; // 挂起/末步修饰 / latched or last-step mods
                g.fill((int) kx, (int) ky, (int) (kx + w), (int) (ky + u), bg);
                // 录入中序列的键帽绿描边（打开时预填 = 现绑定，录入后 = 已录步骤）。
                // Caps of the recorded sequence get the green outline (pre-filled with the
                // current binding on open, then the recorded steps).
                boolean inSeq = !target.mouse && c.code() > 0;
                if (inSeq) {
                    inSeq = false;
                    for (var st : pendingSeq) if (st.key() == c.code()) { inSeq = true; break; }
                }
                g.renderOutline((int) kx, (int) ky, (int) w, (int) u, inSeq ? 0xFF5A8A3A : NodeRenderer.CSB());
                g.drawString(font, c.label(), (int) (kx + w / 2 - font.width(c.label()) / 2), (int) (ky + u / 2 - 4), 0xFFCCCCCC, false);
                kx += w + gap;
            }
            ky += u + 3;
        }

        // 预览行（键盘下方独立一行）：录入中的序列；挂起修饰以 … 收尾提示「下一步将带上」。
        // Preview line under the keyboard: the recorded sequence; latched mods trail with
        // an ellipsis ("the next step will carry them").
        int previewY = (int) ky + 6;
        String preview = I18n.get("gui.create_schematic_compute.settings.bind_label") + ": " + seqText(pendingSeq)
            + (latchedMods != 0 ? (pendingSeq.isEmpty() ? "" : " → ") + EditorKeys.modsText(latchedMods) + "…" : "");
        g.drawString(font, "§e" + preview, (int) kx0, previewY, 0xFFFFFFFF, false);
        // 操作条：删一步 / 默认 / 清除 / 确定绑定 —— 宽度平衡、右缘锚定；左缘压到列表
        // 滚动条时整条下移到滚动条下方（几何经 keysBarGeometry 与命中共用）。
        // Bar: step-back / default / clear / bind — balanced widths, right-anchored;
        // when its left edge would cover the list scrollbar the whole bar drops below
        // the track (geometry shared with hit-testing via keysBarGeometry).
        int[] bar = keysBarGeometry(ky, listRight);
        int barY = bar[1];
        int confirmX = width - 14 - 66;
        int clearX = confirmX - 62;
        int defX = clearX - 62;
        int backX = bar[0];
        // 删一步（移除最后录入的步骤）/ step-back (remove the last recorded step)
        boolean bHov = mx >= backX && mx <= backX + 58 && my >= barY && my <= barY + 16;
        g.fill(backX, barY, backX + 58, barY + 16, bHov ? 0xFF4A5A2A : 0xFF3A3428);
        g.renderOutline(backX, barY, 58, 16, 0xFF6A8A3A);
        g.drawString(font, "§a" + I18n.get("gui.create_schematic_compute.settings.bind_step_back"), backX + 15, barY + 4, 0xFFFFFFFF, false);
        // 默认（恢复当前选中动作的出厂绑定，录入状态同步重预填）/ default (restore the
        // selected action's factory binding and re-prefill the recording from it)
        boolean dHov = mx >= defX && mx <= defX + 56 && my >= barY && my <= barY + 16;
        g.fill(defX, barY, defX + 56, barY + 16, dHov ? 0xFF4A5A2A : 0xFF3A3428);
        g.renderOutline(defX, barY, 56, 16, 0xFF6A8A3A);
        g.drawString(font, "§a" + I18n.get("gui.create_schematic_compute.settings.reset_default"), defX + 19, barY + 4, 0xFFFFFFFF, false);
        g.fill(clearX, barY, clearX + 56, barY + 16, 0xFF3A3428);
        g.renderOutline(clearX, barY, 56, 16, NodeRenderer.CSB());
        g.drawString(font, "§7" + I18n.get("gui.create_schematic_compute.settings.bind_clear"), clearX + 19, barY + 4, 0xFFFFFFFF, false);
        g.fill(confirmX, barY, confirmX + 66, barY + 16, 0xFF3A5A2A);
        g.renderOutline(confirmX, barY, 66, 16, 0xFF5A8A3A);
        g.drawString(font, "§a" + I18n.get("gui.create_schematic_compute.settings.bind_confirm"), confirmX + 15, barY + 4, 0xFFFFFFFF, false);
        // 展开态冲突提示：紧跟操作条下方（键盘区左缘），不与「收起」按钮同域。
        // Expanded clash message: right below the bar at the keyboard's left edge.
        if (rebindConflict != null)
            g.drawString(font, "§c" + rebindConflict, (int) kx0, barY + 18, 0xFFFFFFFF, false);

        // 收起按钮（列表列底部，与颜色 tab 的收起同款样式） / collapse button (list column bottom)
        int clY = keysListBot() + 4; // 列表下方固定位，不随行数增长 / fixed below the list
        boolean clHov = mx >= cx && mx <= cx + 64 && my >= clY && my <= clY + 16;
        g.fill(cx, clY, cx + 64, clY + 16, clHov ? 0xFF3A4A5A : 0xFF2A3A5A);
        g.renderOutline(cx, clY, 64, 16, NodeRenderer.CSB());
        g.drawString(font, "§f" + I18n.get("gui.create_schematic_compute.settings.collapse"), cx + 16, clY + 4, 0xFFFFFFFF, false);
    }

    /** 键帽点击：修饰键帽翻转挂起开关；Esc 键帽清空录入；其余追加为下一步
     *  （挂起修饰随步骤入列，步数达上限提示）。
     *  Keycap click: modifier caps flip the latched toggles; the Esc cap clears the
     *  recording; everything else appends the next step (capped at MAX_STEPS). */
    private void handleKeycapClick(Keycap c) {
        if (c.modBit() != 0) { latchedMods ^= c.modBit(); return; }
        if (c.code() == 256) { pendingSeq.clear(); latchedMods = 0; rebindConflict = null; return; }
        var a = EditorKeys.Action.values()[keybindTarget];
        if (a.mouse) { rebindConflict = I18n.get("gui.create_schematic_compute.editorkeys.rebind_key_only"); return; }
        if (pendingSeq.size() >= EditorKeys.MAX_STEPS) { rebindConflict = I18n.get("gui.create_schematic_compute.settings.bind_max_steps"); return; }
        pendingSeq.add(new EditorKeys.Step(c.code(), latchedMods));
        latchedMods = 0; // 修饰随步骤入列复位 / mods clear with the recorded step
    }

    /** 鼠标键点击：鼠标动作直接绑定该键（无修饰概念）；键盘动作不可绑鼠标键。 / Mouse-chip click: mouse actions bind immediately (no modifiers); keyboard actions refuse. */
    private void handleChipClick(EditorKeys.Action a, int button) {
        if (!a.mouse) { rebindConflict = I18n.get("gui.create_schematic_compute.editorkeys.rebind_mouse_only"); return; }
        rebindConflict = EditorKeys.setMouseBinding(a, button)
            ? null : I18n.get("gui.create_schematic_compute.editorkeys.conflict");
    }

    /** 「确定绑定」：把录入序列落到当前动作（前缀歧义拒绝）；成功后预览保持为生效序列。
     *  鼠标动作在键位点击时就已即时绑定 —— 确定对它是静默无操作。
     *  Bind: commit the recorded sequence (prefix-ambiguity refused); the preview then
     *  shows the live binding. Mouse actions bind on chip click, so Bind is a silent
     *  no-op for them. */
    private void confirmKeybind() {
        var a = EditorKeys.Action.values()[keybindTarget];
        if (a.mouse) return;
        if (pendingSeq.isEmpty()) { rebindConflict = I18n.get("gui.create_schematic_compute.settings.bind_need_key"); return; }
        rebindConflict = EditorKeys.setSequence(a, List.copyOf(pendingSeq))
            ? null : I18n.get("gui.create_schematic_compute.editorkeys.conflict");
        if (rebindConflict == null) pendingSeq.clear();
        if (rebindConflict == null) pendingSeq.addAll(EditorKeys.sequence(a));
    }

    /** 选中动作行并展开虚拟键盘：预填该动作当前绑定序列，所见即所改。
     *  Select an action row and open the keyboard, pre-filled with the current sequence. */
    private void selectKeybindRow(int idx) {
        keybindTarget = idx;
        var a = EditorKeys.Action.values()[idx];
        pendingSeq.clear();
        pendingSeq.addAll(EditorKeys.sequence(a));
        latchedMods = 0;
        rebindConflict = null;
        expanded = true;
    }

    /** 收起当前 tab 的展开区（颜色调色板 / 键位键盘）并清空各自的暂选状态。 / Collapse whichever expansion is open (palette / keyboard) and clear its pending state. */
    private void collapseExpanded() {
        if (tab == 0) { collapsePalette(); return; }
        expanded = false;
        keybindTarget = -1;
        pendingSeq.clear();
        latchedMods = 0;
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

    /** 键位列表行高。 / key-list row height. */
    private static final int KEY_ROW_H = 22;

    /** 键位列表滚动条 thumb {x, y, w, h}（x 依赖当次列表右缘；渲染与拖拽共用几何）。
     *  Key-list scrollbar thumb {x, y, w, h} (x depends on the current list right edge;
     *  geometry shared by render and dragging). */
    private int[] keysScrollbarThumb(int listRight) {
        int trackH = keysListBot() - keysListTop();
        int thumbH = Math.max(12, trackH * keysVisibleRows() / EditorKeys.Action.values().length);
        int maxScroll = keysMaxScroll();
        int thumbY = keysListTop() + (maxScroll > 0 ? (trackH - thumbH) * keysScroll / maxScroll : 0);
        return new int[]{listRight - 8, thumbY, 6, thumbH};
    }

    /** 拖拽推进键位列表：thumb 相对增量换算为行偏移（颜色列表同款）。 / Advance the key list by the dragged thumb delta (colors-list style). */
    private void applyKeysScrollbarDrag(double my) {
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
    private int[] keysBarGeometry(float ky, int listRight) {
        int backX = width - 14 - 254;
        int barY = (int) ky + 20;
        if (backX < listRight + 6) barY = keysListBot() + 4;
        return new int[]{backX, barY};
    }

    // ── 颜色列表几何（渲染 / 命中 / 拖拽共用单一来源） ──
    // ── Color-list geometry (single source shared by render, hit-testing and dragging) ──

    /** 列表顶部 y。 / list top y. */
    private static int colorsListTop() { return cy() + 2; }
    /** 列表底部 y（底部按钮行上方 6px）。 / list bottom y (6px above the bottom button row). */
    private int colorsListBot() { return height - 8 - 16 - 6; }
    private int colorsVisibleRows() { return Math.max(1, (colorsListBot() - colorsListTop()) / COLOR_ROW_H); }
    private int colorsMaxScroll() { return Math.max(0, NodeRenderer._NUM_COLORS - colorsVisibleRows()); }
    /** 行区右缘：为滚动条预留 10px 条带。 / row right edge: a 10px strip is reserved for the scrollbar. */
    private static int colorsRowRight(int cx, int contentW) { return cx + contentW - 10; }
    /** 滚动条 thumb {x, y, w, h}；轨道与 thumb 同宽、纵跨列表全高。 / scrollbar thumb {x, y, w, h}; the track shares x/w and spans the full list height. */
    private int[] colorsScrollbarThumb(int cx, int contentW) {
        int trackH = colorsListBot() - colorsListTop();
        int thumbH = Math.max(12, trackH * colorsVisibleRows() / NodeRenderer._NUM_COLORS);
        int maxScroll = colorsMaxScroll();
        int thumbY = colorsListTop() + (maxScroll > 0 ? (trackH - thumbH) * colorScroll / maxScroll : 0);
        return new int[]{cx + contentW - 8, thumbY, 6, thumbH};
    }

    /** 拖拽推进颜色列表：thumb 相对增量换算为行偏移（书签 / 添加菜单滚动条同款）。
     *  Advance the color list by the dragged thumb delta (bookmark / add-menu scrollbar style). */
    private void applyColorScrollbarDrag(double my) {
        int maxScroll = colorsMaxScroll();
        if (maxScroll <= 0) return;
        int trackH = colorsListBot() - colorsListTop();
        int thumbH = Math.max(12, trackH * colorsVisibleRows() / NodeRenderer._NUM_COLORS);
        if (trackH - thumbH <= 0) return;
        float delta = (float) (my - colorScrollbarDragStartY) / (trackH - thumbH);
        int newOff = colorScrollbarDragStartOff + Math.round(delta * maxScroll);
        colorScroll = Math.max(0, Math.min(maxScroll, newOff));
    }

    /** 序列的可读文本（Ctrl+K → D；空 = —）。 / Readable sequence text (Ctrl+K → D; empty = —). */
    private static String seqText(java.util.List<EditorKeys.Step> seq) {
        if (seq.isEmpty()) return "—";
        var sb = new StringBuilder();
        for (var st : seq) {
            if (sb.length() > 0) sb.append(" → ");
            sb.append(EditorKeys.modsText(st.mods())).append(keyName(st.key()));
        }
        return sb.toString();
    }

    /** GLFW 键码的可读名（设置界面显示用，覆盖虚拟键盘全部键帽）。 / Readable name for a GLFW keycode (settings UI; covers every virtual cap). */
    private static String keyName(int k) {
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
}
