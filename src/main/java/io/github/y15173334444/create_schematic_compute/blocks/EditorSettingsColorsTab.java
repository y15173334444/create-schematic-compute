package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.client.colorpicker.ColorPickerWidget;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;

/**
 * 颜色 tab：颜色列表渲染、调色板绑定与工作色（自 {@link EditorSettingsScreen} 拆分）。
 * The colours tab: colour-list rendering, palette binding and the working colour, split out of
 * {@link EditorSettingsScreen} (docs/gui-decomposition-plan.md step 3).
 *
 * <p><b>行为零变更</b>：实现逐字搬迁，只有隐式外层访问改为经 {@link EditorSettingsHost} 取得；
 * 本 tab 自己的状态（滚动偏移 / 滚动条拖拽 / 调整槽 / 工作色 / 暂存初始化）随实现搬入，
 * 父类输入分发经访问器读写。列表几何仍留在屏幕侧（渲染/命中/拖拽共用同一来源）并经宿主暴露。
 * <b>Behaviour-preserving</b>: the implementation moved verbatim and implicit outer-class accesses
 * now go through {@link EditorSettingsHost}. This tab's state moved with it; the list geometry stays
 * on the screen (one source shared by render / hit-test / drag) and is reached through the host.</p>
 */
final class EditorSettingsColorsTab {

    private EditorSettingsHost h;

    // ── 颜色状态（原 EditorSettingsScreen 字段）/ colour state (moved from the screen) ──
    private int colorScroll = 0;
    private boolean colorScrollbarDrag = false;
    private float colorScrollbarDragStartY = 0f;
    private int colorScrollbarDragStartOff = 0;
    private int adjustIndex = -1;
    private int workingColor = 0xFF000000;
    private boolean stagingInited = false;

    void bind(EditorSettingsHost h) { this.h = h; }

    // ── 访问器：屏幕输入分发读写 / accessors used by the screen's input dispatch ──
    int colorScroll() { return colorScroll; }
    void setColorScroll(int v) { colorScroll = v; }
    boolean colorScrollbarDrag() { return colorScrollbarDrag; }
    void setColorScrollbarDrag(boolean v) { colorScrollbarDrag = v; }
    float colorScrollbarDragStartY() { return colorScrollbarDragStartY; }
    int colorScrollbarDragStartOff() { return colorScrollbarDragStartOff; }
    void setColorScrollbarDragStart(float y, int off) { colorScrollbarDragStartY = y; colorScrollbarDragStartOff = off; }
    int adjustIndex() { return adjustIndex; }
    int workingColor() { return workingColor; }
    void setStagingInited(boolean v) { stagingInited = v; }

    /** 颜色行高（渲染 / 命中 / 拖拽共用）。 / colour row height (shared by render / hit-test / drag). */
    static final int COLOR_ROW_H = 24;

    /** 颜色 tab 渲染（列表 + 底部按钮行 + 展开态的调色板停靠）。
     *  Colours tab render: the list, the bottom button row and the docked palette when expanded. */
    void renderColorsTab(GuiGraphics g, int mx, int my, int cx, int cy,
                         int contentRight, int contentBottom, int contentW) {
        // 进入颜色 tab 时一次性初始化暂存色（与旧 23 色面板行为一致：未应用的修改
        // 在下次进入时丢弃）。
        // Initialize staging colors once per colors-tab visit (same as the old panel:
        // unapplied edits are discarded on the next entry).
        if (!stagingInited) { NodeRenderer.initStaging(); stagingInited = true; }
        int listTop = h.colorsListTop();
        int btnRowY = h.colorsListBot() + 6;
        int listBot = h.colorsListBot();
        int visible = h.colorsVisibleRows();
        int maxScroll = h.colorsMaxScroll();
        if (colorScroll < 0) colorScroll = 0;
        if (colorScroll > maxScroll) colorScroll = maxScroll;
        int rowRight = h.colorsRowRight(cx, contentW);

        for (int i = colorScroll; i < NodeRenderer._NUM_COLORS; i++) {
            int ri = i - colorScroll;
            int ry = listTop + ri * COLOR_ROW_H;
            if (ry + COLOR_ROW_H > listBot) break;
            boolean adjustingThis = h.expanded() && adjustIndex == i;
            if (adjustingThis) g.fill(cx, ry, rowRight, ry + COLOR_ROW_H - 2, NodeRenderer.HOV()); // 「调整中」行 = 悬停高亮 / adjusting row = hover highlight
            else if (ri % 2 == 0) g.fill(cx, ry, rowRight, ry + COLOR_ROW_H - 2, NodeRenderer.PINS());
            // 色块恒显示暂存色 —— 工作色仅在确认时填入（实时预览会让"确认"失去意义）。
            // The swatch always shows the staging color — the working color is filled
            // only on confirm (a live preview would make "confirm" meaningless).
            g.fill(cx + 2, ry + 4, cx + 18, ry + 18, NodeRenderer.stagingColors[i]);
            g.renderOutline(cx + 2, ry + 4, 16, 14, 0xFF888888);
            // 名称 / name
            g.drawString(h.font(), I18n.get("gui.create_schematic_compute.color." + NodeRenderer.COLOR_KEYS[i]),
                cx + 26, ry + 7, 0xFFCCCCCC, false);
            // 调整按钮 / adjust button
            boolean hov = mx >= rowRight - 52 && mx <= rowRight - 8
                && my >= ry + 1 && my <= ry + COLOR_ROW_H - 3;
            g.fill(rowRight - 52, ry + 1, rowRight - 8, ry + COLOR_ROW_H - 3,
                hov ? NodeRenderer.HOV() : NodeRenderer.PBG());
            g.renderOutline(rowRight - 52, ry + 1, 44, COLOR_ROW_H - 4, NodeRenderer.CSB());
            g.drawString(h.font(), I18n.get("gui.create_schematic_compute.settings.adjust"),
                rowRight - 48, ry + 7, NodeRenderer.ACC(), false);
        }

        // 滚动条（thumb 可拖拽）——几何与命中/拖拽共用 colorsScrollbarThumb。
        // Scrollbar (draggable thumb) — geometry shared with hit-testing/dragging via colorsScrollbarThumb.
        if (maxScroll > 0) {
            int[] sb = h.colorsScrollbarThumb(cx, contentW);
            g.fill(sb[0], listTop, sb[0] + sb[2], listBot, NodeRenderer.PINS()); // 滚动条轨道 = 内凹井 / track = inset well
            g.fill(sb[0] + 1, sb[1], sb[0] + sb[2] - 1, sb[1] + sb[3], NodeRenderer.CSB());
        }

        // 底部常驻：收起/展开 + 恢复默认 / 应用
        // bottom row: collapse/expand + defaults + apply
        String toggleLabel = I18n.get(h.expanded()
            ? "gui.create_schematic_compute.settings.collapse"
            : "gui.create_schematic_compute.settings.expand");
        g.fill(cx, btnRowY, cx + 64, btnRowY + 16, h.expanded() ? NodeRenderer.HOV() : NodeRenderer.PBG()); // 展开态=激活高亮 / expanded = active highlight
        g.renderOutline(cx, btnRowY, 64, 16, NodeRenderer.CSB());
        g.drawString(h.font(), "§f" + toggleLabel, cx + 16, btnRowY + 4, 0xFFFFFFFF, false);
        g.fill(cx + 72, btnRowY, cx + 142, btnRowY + 16, NodeRenderer.PBG()); // 中性次按钮底 / neutral secondary button bg
        g.renderOutline(cx + 72, btnRowY, 70, 16, NodeRenderer.CSB());
        g.drawString(h.font(), "§7" + I18n.get("gui.create_schematic_compute.color.defaults"), cx + 82, btnRowY + 4, 0xFFFFFFFF, false);
        g.fill(cx + 150, btnRowY, cx + 220, btnRowY + 16, 0xFF3A5A2A);
        g.renderOutline(cx + 150, btnRowY, 70, 16, 0xFF5A8A3A);
        g.drawString(h.font(), "§a" + I18n.get("gui.create_schematic_compute.color.apply"), cx + 166, btnRowY + 4, 0xFFFFFFFF, false);

        // 展开形态：调色板停靠右侧（小窗口按高度缩放）。填色由调色板自带的确认键完成
        // （persistent —— 只填色不关闭）。
        // Expanded form: docked palette (scaled down on short windows). Filling is done
        // by the palette's own confirm key (persistent — fills without closing).
        if (h.expanded()) {
            h.picker().setScale(h.paletteScale(h.h()));
            h.picker().setPosition(h.paletteX(), h.paletteY());
            h.picker().render(g, mx, my);
            // 确认按钮：把工作色填入槽位 —— 调色板保持展开，不自行关闭。
            // Confirm button: fills the working color into the slot — the palette
            // stays open and never collapses on its own.
            int doneY = h.paletteDoneY();
            boolean fin = mx >= h.paletteX() && mx <= h.paletteX() + ColorPickerWidget.WIDTH
                && my >= doneY && my <= doneY + 18;
            g.fill(h.paletteX(), doneY, h.paletteX() + ColorPickerWidget.WIDTH,
                doneY + 18, fin ? 0xFF3A5A2A : NodeRenderer.PBG());
            g.renderOutline(h.paletteX(), doneY, ColorPickerWidget.WIDTH,
                18, 0xFF5A8A3A);
            g.drawString(h.font(), "§a" + I18n.get("gui.create_schematic_compute.color.done"),
                h.paletteX() + 66, doneY + 5, 0xFFFFFFFF, false);
        }
    }

    /** 开始调整某个颜色槽：切换到展开形态（整个界面左滑），调色板绑定该槽的工作色
     *  —— 实时写入工作色，确认键才填入槽位。
     *  Begin adjusting a color slot: switch to the expanded form (UI slides left)
     *  and bind the palette to that slot's working color — tweaks write the working
     *  color live, the confirm button fills it into the slot. */
    void beginAdjust(int idx) {
        adjustIndex = idx;
        workingColor = NodeRenderer.stagingColors[idx];
        if (!h.expanded()) {
            h.setExpanded(true);
            h.picker().setEmbedded(true);
            h.picker().setScale(h.paletteScale(h.h()));
            // 双回调：liveUpdate 实时预览工作色；onSelect（组件确认键）把颜色填入槽位。
            // setPersistent 让组件确认键不自行关闭。
            // Dual callbacks: liveUpdate previews the working color; onSelect (the
            // widget's confirm key) fills it into the slot. setPersistent keeps the
            // widget's confirm from closing itself.
            h.picker().setPersistent(true);
            h.picker().open(0, 0, workingColor, c -> fillWorkingColor(c), c -> workingColor = c, false);
            h.picker().setPosition(h.paletteX(), h.paletteY());
        } else {
            h.picker().rebind(workingColor, c -> fillWorkingColor(c), c -> workingColor = c);
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
    void collapsePalette() {
        h.setExpanded(false);
        h.picker().close();
    }

    /** Defaults/暂存重置后，把展开中的调色板重新绑定到当前槽位色。 / After a staging reset, rebind the open palette to the current slot's color. */
    void rebindPicker() {
        if (h.expanded() && adjustIndex >= 0) {
            workingColor = NodeRenderer.stagingColors[adjustIndex];
            h.picker().rebind(workingColor, c -> fillWorkingColor(c), c -> workingColor = c);
        }
    }
}
