package io.github.y15173334444.create_schematic_compute.client;

import io.github.y15173334444.create_schematic_compute.blocks.NodeRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;

/**
 * 左侧工具列（PS 式单列）：工具图标、悬停提示与（默认隐藏的）笔刷大小 / 透明度 / 当前色
 * 区块，自 {@link PixelEditorScreen} 拆分（docs/gui-decomposition-plan.md 步骤 4 第三刀）。
 * The left tool rail (PS-style single column): tool icons, hover tooltips and the (default-hidden)
 * brush-size / opacity / current-colour section, split out of {@link PixelEditorScreen}
 * (roadmap step 4, third cut).
 *
 * <p><b>行为零变更</b>：实现逐字搬迁。工具选择状态（{@code tool}）与画笔参数（大小 / 透明度 /
 * 当前色）归屏幕 —— 画布绘制、快捷键与顶栏都使用它们 —— 经 {@link Host} 读写；本类只持有
 * 透明度滑杆的拖拽标志与自身几何。画布上的工具调度（{@code applyToolClick}）留在屏幕
 * （属画布交互而非面板逻辑，与原方案的分组的偏差已记录在计划文档）。
 * <b>Behaviour-preserving</b>: bodies moved verbatim. The tool selection and the brush
 * parameters (size / opacity / current colour) belong to the screen — canvas painting,
 * shortcuts and the top bar all use them — and are reached through {@link Host}; this class
 * only owns the opacity-slider drag flag and its own geometry. The canvas-side tool dispatch
 * ({@code applyToolClick}) stays on the screen (canvas interaction, not panel logic — the
 * deviation from the plan's grouping is recorded in the plan doc).</p>
 */
final class PixelEditorToolRail {

    /** 宿主：屏幕侧提供的最小接缝 / Host: the minimal screen-side surface. */
    interface Host {
        /** 当前工具（工具列高亮与点击选择）。/ the active tool (rail highlight + click selection). */
        Tool tool();
        void setTool(Tool t);
        int brushSize();
        void setBrushSize(int v);
        float brushOpacity();
        void setBrushOpacity(float v);
        int selectedColor();
        /** 屏幕布局：顶栏高 / 左列宽 / 列内边距 / 屏幕高。/ layout: top-bar height, rail width, padding, screen height. */
        int topH();
        int leftW();
        int leftPad();
        int height();
    }

    /** 绘图工具 / painting tools. */
    enum Tool { BRUSH, ERASER, FILL, EYEDROPPER, LINE, RECT, HAND }
    /** 工具栏固定顺序（快捷键 1..7 与字母键都按此映射）。/ fixed tool rail order (1..7 & letter keys map by this). */
    static final Tool[] TOOLS = {Tool.BRUSH, Tool.ERASER, Tool.FILL, Tool.EYEDROPPER, Tool.LINE, Tool.RECT, Tool.HAND};

    /** 临时隐藏左面板的笔刷大小/透明度/当前色，工具栏只放工具（改回 true 即恢复）。/
     *  Temporarily hide the brush-size/opacity/current-color controls in the left panel so the
     *  toolbar shows only the tools; flip back to true to restore them. */
    private static final boolean SHOW_BRUSH_CONTROLS = false;

    // 左面板内部成员 / rail inner members
    private static final int TOOL_BTN = 22, TOOL_GAP = 4;      // 工具按钮（缩小）/ tool button (compact)
    private static final int BA_SIZE = 16, BA_PITCH = 18;      // 笔刷大小档 / brush-size chip

    // ── 主题色（与屏幕同源的字面量；屏幕保留自己的副本供顶栏/画布用）──
    // ── theme colours (same-source literals; the screen keeps its own copies for the top bar/canvas) ──
    private static final int C_BG = NodeRenderer.PBG();               // 面板底 / panel bg
    private static final int C_BORDER = NodeRenderer.PBR();           // 面板描边 / panel border
    private static final int C_BTN = 0xFF3A3428;                      // 按钮底 / button bg
    private static final int C_HOVER = 0xFF5A4A3A;                    // 悬停 / hover
    private static final int C_SEL = 0xFF3A5A2A;                      // 选中 / selected
    private static final int C_TXT_BRIGHT = 0xFFFFFFFF;
    private static final int C_TXT_DIM = 0xFFCCCCCC;
    private static final int C_CANVAS = 0xFF14120E;                   // 画布区底 / canvas area bg
    private static final int C_OPACITY = 0xFF8A9A5A;                  // 透明度滑杆填充 / opacity fill

    private final Host host;

    /** 透明度滑杆拖动中（仅 SHOW_BRUSH_CONTROLS 时可达）/ opacity slider being dragged (reachable only when SHOW_BRUSH_CONTROLS). */
    private boolean opacityDragging = false;

    PixelEditorToolRail(Host host) { this.host = host; }

    // ── 几何 / geometry ──

    private int toolsGridTop() { return host.topH() + 8; }
    /** 工具按钮坐标（单列、整列紧贴左缘与右缘）：[x, y, w, h]。/
     *  tool cell (single column, flush against the left edge and stopping just left of the divider). */
    private int[] toolCell(int i) {
        int x = 0;
        int y = toolsGridTop() + i * (TOOL_BTN + TOOL_GAP);
        return new int[]{x, y, host.leftW() - 1, TOOL_BTN};
    }
    private int brushSectionY() { return toolsGridTop() + TOOLS.length * (TOOL_BTN + TOOL_GAP) + 8; }
    private int brushBtnY() { return brushSectionY() + 18; }
    private int opacityLabelY() { return brushSectionY() + 40; }
    private int opacitySliderY() { return opacityLabelY() + 16; }
    private int previewY() { return opacitySliderY() + 26; }
    private int[] opacitySliderGeom() {
        int x = host.leftPad() + 2;
        int w = host.leftW() - 2 * host.leftPad() - 6;
        return new int[]{x, opacitySliderY(), w, 6};
    }

    private void setOpacityFromX(double mx, int[] os) {
        host.setBrushOpacity(Math.max(0f, Math.min(1f, (float) ((mx - os[0]) / os[2]))));
    }

    private static boolean hit(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    // ══════════════ 渲染 / render ══════════════

    /** 左面板：PS 式单列工具列（`SHOW_BRUSH_CONTROLS` 为 false 时只放工具，隐藏笔刷大小/透明度/当前色）。
     *  Left panel: PS-style single-column tool rail (hides brush-size/opacity/current-color when
     *  `SHOW_BRUSH_CONTROLS` is false, leaving only the tools).
     *  工具主体都落在 x∈[0,LEFT_W] 内；唯一例外是悬停时在其右侧浮出的提示框（近深度，遮住画布）。
     *  The tools themselves stay within x∈[0,LEFT_W]; the only exception is the floating tooltip
     *  shown to the right on hover (near depth, so it occludes the canvas beneath). */
    void render(GuiGraphics g, int mx, int my) {
        int leftW = host.leftW(), leftPad = host.leftPad(), topH = host.topH();
        g.fill(0, topH, leftW, host.height(), C_BG);
        // 分界线从顶栏底部（TOP_H）贯通到屏幕底部，让左侧一整列、且不再穿过顶栏横线。
        // the divider runs from the top bar's bottom (TOP_H) to the screen bottom so the rail reads
        // as one full-height column without crossing the top bar's horizontal line.
        g.fill(leftW - 1, topH, leftW, host.height(), C_BORDER);
        var f = Minecraft.getInstance().font;
        // ── 工具列 / tool rail ──
        Tool[] order = TOOLS;
        for (int i = 0; i < order.length; i++) {
            int[] c = toolCell(i);
            boolean hov = hit(mx, my, c[0], c[1], c[2], c[3]);
            boolean sel = host.tool() == order[i];
            // PS 式：默认无单独按钮填充/边框，仅悬停或选中时高亮整块矩形。
            // PS style: no per-cell fill or border by default; only hover/selected highlight the whole cell.
            if (sel || hov)
                g.fill(c[0], c[1], c[0] + c[2], c[1] + c[3], sel ? C_SEL : C_HOVER);
            int icX = (leftW - 12) / 2;                 // 图标在整列宽内居中 / centre icon in rail width
            int icY = (TOOL_BTN - 12) / 2;              // 图标在单元格高内居中 / centre icon in cell height
            drawToolIcon(g, c[0] + icX, c[1] + icY, order[i]);
            if (hov) {
                // PS 式浮动提示：小背景框 + 文字（近深度，遮住下方画布）。
                // PS-style floating tooltip: small bg box + text, drawn at near depth over the canvas.
                String tip = "§7" + I18n.get("gui.create_schematic_compute.monitor.pixel_tool_" + order[i].name().toLowerCase());
                int tw = f.width(tip), tx = c[0] + c[2] + 6, ty = c[1];
                g.fill(tx, ty, tx + tw + 10, ty + 15, C_BG);
                g.renderOutline(tx, ty, tw + 10, 15, C_BORDER);
                g.drawString(f, tip, tx + 5, ty + 4, C_TXT_DIM, false);
            }
        }
        if (SHOW_BRUSH_CONTROLS) {
            // ── 笔刷大小 / brush size ──
            g.drawString(f, "§7" + I18n.get("gui.create_schematic_compute.monitor.pixel_brush_size") + ":", leftPad + 2, brushSectionY(), C_TXT_DIM, false);
            for (int s = 1; s <= 5; s++) {
                int bx = leftPad + 2 + (s - 1) * BA_PITCH, by = brushBtnY();
                boolean hov = hit(mx, my, bx, by, BA_SIZE, BA_SIZE);
                g.fill(bx, by, bx + BA_SIZE, by + BA_SIZE, s == host.brushSize() ? C_SEL : hov ? C_HOVER : C_BTN);
                g.renderOutline(bx, by, BA_SIZE, BA_SIZE, C_BORDER);
                int d = Math.min(12, s * 2 + 1);
                g.fill(bx + BA_SIZE / 2 - d / 2, by + BA_SIZE / 2 - d / 2, bx + BA_SIZE / 2 - d / 2 + d, by + BA_SIZE / 2 - d / 2 + d, C_TXT_DIM);
            }
            // ── 透明度滑杆 / opacity slider ──
            g.drawString(f, "§7" + I18n.get("gui.create_schematic_compute.monitor.pixel_opacity") + ":", leftPad + 2, opacityLabelY(), C_TXT_DIM, false);
            int[] os = opacitySliderGeom();
            g.fill(os[0], os[1], os[0] + os[2], os[1] + os[3], C_CANVAS);
            g.renderOutline(os[0], os[1], os[2], os[3], C_BORDER);
            g.fill(os[0], os[1], os[0] + (int) (os[2] * host.brushOpacity()), os[1] + os[3], C_OPACITY);
            int thumbX = os[0] + (int) (os[2] * host.brushOpacity()) - 3;
            g.fill(thumbX, os[1] - 2, thumbX + 6, os[1] + os[3] + 2, C_TXT_DIM);
            g.drawString(f, "§7" + Math.round(host.brushOpacity() * 100) + "%", leftPad + 2, opacitySliderY() + 9, C_TXT_DIM, false);
            // ── 当前色 + 笔刷预览 / current color + brush preview ──
            g.drawString(f, "§7" + I18n.get("gui.create_schematic_compute.monitor.pixel_current_color") + ":", leftPad + 2, previewY(), C_TXT_DIM, false);
            int sw = 26;
            g.fill(leftPad + 2, previewY() + 12, leftPad + 2 + sw, previewY() + 12 + sw, host.selectedColor());
            g.renderOutline(leftPad + 2, previewY() + 12, sw, sw, C_BORDER);
        }
    }

    private void drawToolIcon(GuiGraphics g, int x, int y, Tool t) {
        int P = 0xFFD8D8D8, A = 0xFF8A8A8A;   // 主体亮色 / 描边暗色 (primary / accent)
        switch (t) {
            case BRUSH -> {
                // 斜向画笔：手柄(accent) 下行到左下的亮色笔尖 / diagonal brush, handle then bright bristle
                for (int i = 0; i < 5; i++) g.fill(x + 8 - i, y + 2 + i, x + 10 - i, y + 4 + i, A);
                g.fill(x + 3, y + 6, x + 6, y + 9, P);
                g.fill(x + 2, y + 8, x + 5, y + 11, P);
            }
            case ERASER -> {
                // 斜置橡皮块：亮顶面 + 两条错位的主体 / slanted eraser block with a bright top face
                g.fill(x + 3, y + 4, x + 10, y + 7, A);
                g.fill(x + 2, y + 7, x + 9, y + 10, A);
                g.fill(x + 4, y + 2, x + 11, y + 5, P);
            }
            case FILL -> {
                // 油漆桶：提手 + 向下变宽的桶身 + 倾倒口 / paint bucket: rim + widening body + spout
                g.fill(x + 3, y + 2, x + 10, y + 4, A);
                for (int r = 0; r < 6; r++) { int half = r / 2; g.fill(x + 6 - half, y + 4 + r, x + 8 + half, y + 6 + r, P); }
                g.fill(x + 6, y + 8, x + 8, y + 11, P);
                g.fill(x + 9, y + 11, x + 10, y + 12, P);
            }
            case EYEDROPPER -> {
                // 吸管：顶部球泡 + 斜向管身 + 尖端 / eyedropper: bulb + diagonal barrel + tip
                g.fill(x + 4, y + 2, x + 7, y + 5, P);
                g.fill(x + 6, y + 4, x + 9, y + 7, A);
                g.fill(x + 8, y + 7, x + 10, y + 9, A);
                g.fill(x + 9, y + 9, x + 11, y + 11, P);
            }
            case LINE -> {
                // 2px 粗斜线（亮芯 + 暗边）/ 2px-thick diagonal line (bright core + accent edge)
                for (int i = 0; i < 6; i++) {
                    g.fill(x + 2 + i, y + 2 + i, x + 4 + i, y + 4 + i, A);
                    g.fill(x + 3 + i, y + 3 + i, x + 5 + i, y + 5 + i, P);
                }
            }
            case RECT -> {
                // 空心矩形 + 加粗的顶/左边缘 / hollow rect with a thickened top & left edge
                g.renderOutline(x + 2, y + 2, 8, 8, P);
                g.fill(x + 2, y + 2, x + 10, y + 3, P);
                g.fill(x + 2, y + 2, x + 3, y + 10, P);
            }
            case HAND -> {
                // 抓手：四指 + 掌部（亮色掌、暗色指缝）/ hand: four fingers + palm (bright palm, dark gaps)
                g.fill(x + 2, y + 3, x + 3, y + 7, P);   // 小指 / pinky
                g.fill(x + 4, y + 1, x + 5, y + 8, P);   // 无名指 / ring
                g.fill(x + 6, y + 1, x + 7, y + 8, P);   // 中指 / middle
                g.fill(x + 8, y + 2, x + 9, y + 7, P);   // 食指 / index
                g.fill(x + 2, y + 7, x + 9, y + 9, A);   // 指缝底 / web between fingers
                g.fill(x + 2, y + 7, x + 9, y + 11, P);  // 掌部 / palm
                g.fill(x + 9, y + 8, x + 11, y + 10, P); // 拇指 / thumb
            }
        }
    }

    // ══════════════ 输入 / input ══════════════

    /** 工具列点击：工具选择 +（SHOW_BRUSH_CONTROLS 时）笔刷档 / 透明度滑杆。返回 true = 已消费。
     *  Rail click: tool selection + (when SHOW_BRUSH_CONTROLS) the brush chips / opacity slider.
     *  true = consumed. */
    boolean handleClick(double mx, double my, int btn) {
        if (btn != 0) return false;
        Tool[] order = TOOLS;
        for (int i = 0; i < order.length; i++) {
            int[] c = toolCell(i);
            if (hit(mx, my, c[0], c[1], c[2], c[3])) { host.setTool(order[i]); return true; }
        }
        if (SHOW_BRUSH_CONTROLS) {
            for (int s = 1; s <= 5; s++) {
                int bx = host.leftPad() + 2 + (s - 1) * BA_PITCH, by = brushBtnY();
                if (hit(mx, my, bx, by, BA_SIZE, BA_SIZE)) { host.setBrushSize(s); return true; }
            }
            int[] os = opacitySliderGeom();
            if (hit(mx, my, os[0], os[1], os[2], os[3])) { opacityDragging = true; setOpacityFromX(mx, os); return true; }
        }
        return false;
    }

    /** 透明度滑杆拖动（mouseDragged 的左面板块）。返回 true = 已消费。
     *  Opacity-slider drag (the left-panel block in mouseDragged). true = consumed. */
    boolean handleOpacityDragged(double mx) {
        if (!opacityDragging) return false;
        setOpacityFromX(mx, opacitySliderGeom());
        return true;
    }

    /** 透明度滑杆释放。返回 true = 已消费。 / Opacity-slider release. true = consumed. */
    boolean releaseOpacityDrag() {
        if (!opacityDragging) return false;
        opacityDragging = false;
        return true;
    }
}
