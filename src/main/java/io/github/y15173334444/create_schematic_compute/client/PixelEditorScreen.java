package io.github.y15173334444.create_schematic_compute.client;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.blocks.GraphEditor;
import io.github.y15173334444.create_schematic_compute.blocks.MonitorBlockEntity;
import io.github.y15173334444.create_schematic_compute.blocks.NodeRenderer;
import io.github.y15173334444.create_schematic_compute.client.colorpicker.ColorPickerWidget;
import io.github.y15173334444.create_schematic_compute.client.colorpicker.RecentColors;
import io.github.y15173334444.create_schematic_compute.graph.GraphNode;
import io.github.y15173334444.create_schematic_compute.graph.GraphOp;
import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import io.github.y15173334444.create_schematic_compute.network.GraphEditOpPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import static io.github.y15173334444.create_schematic_compute.client.PixelEditorToolRail.Tool;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 独立像素编辑器（绘画软件式 UI），双击 IMAGE / IMAGE_SEQUENCE 节点打开。
 * <p><b>布局（瓦片式响应式，保证任何窗口尺寸都不重叠）：</b>
 * 顶部工具条（标题 + 画笔状态【工具/笔刷大小/透明度/当前色，居中】+ 撤销/重做 + 尺寸 + 关闭 + Fit，右对齐、动态计算）；
 * 左侧面板（PS 式工具列：单列、紧贴左缘与右缘，无单独按钮边框，仅悬停/选中整块高亮矩形，约 30px 宽）；
 * 右侧取色器面板（**内嵌式常驻**，约 0.8x；面板铺满右缘到屏幕底部、无顶标题；「常用/最近使用」标题字放大、
 * 各显示更多行；实心底 + 左分隔线、非浮空弹窗；画布止于其左缘）；中央可缩放/平移画布（无边框、无 scissor 遮罩——平移/缩放时
 * 网格直接画在背景上，超出部分被组件用深度缓冲遮挡）；底部帧条
 * （IMAGE_SEQUENCE：±/导航/新建/删除等按钮行紧挨在缩略图条上方，缩略图条紧贴屏幕底部，
 * 导航/新建/删除/缩略图/拖拽重排，仅在中央列内；缩略图按宽高比动态缩放——高固定、宽动态，无边框）。</p>
 * <p>每个面板的内容都被约束在自身横向（或纵向）条带内，因此无论窗口大小都不会互相重叠：
 * 顶栏内容只在 y∈[0,TOP_H]；左面板内容只在 x∈[0,LEFT_W]；取色器只在右缘带；画布/帧条只在
 * 中央列（左面板右缘 ↔ 取色器左缘）内。</p>
 * <p>实现 {@link GraphEditor.Host} 使整图同步守卫（isPixelEditorOpen）与 sendOp 的
 * pendingLocalOps 计数继续生效。绘画本地进行，关闭/切帧时定向 SET_IMAGE_PIXELS op 同步
 * （与旧浮层契约一致）。画布尺寸改为「尺寸」按钮 + 弹窗，应用/取消分离。</p>
 * Standalone pixel editor (painting-app style UI), opened by double-clicking an IMAGE /
 * IMAGE_SEQUENCE node. <b>Tile layout (responsive; never overlaps at any window size):</b>
 * top bar (title + centred brush state (tool/size/opacity/current colour) + undo/redo + Canvas/Fit right-aligned and
 * dynamically computed); left panel (PS-style tool rail: single column, flush against both the
 * left edge and the right divider, no per-cell borders and only a full-cell highlight on
 * hover/selected, a narrow ~30px rail); right color-picker panel
 * (an embedded always-on right panel, scaled ~0.8x; the panel fills the right band down to the
 * screen bottom, with bigger section titles and more favorite/recent rows, a solid background +
 * left divider, not a floating popup; the canvas stops at its left edge); centered zoomable/pannable canvas (borderless, no scissor
 * mask — the grid draws straight onto the background and anything overhanging the viewport is
 * occluded by the panels via the depth buffer); bottom frame area for sequences (a button row
 * with ◀/▶ nav, +New and Delete directly above a thumbnail strip that is flush against the
 * bottom of the screen; click/switch/drag-reorder, only within the central column). Every panel
 * keeps its content inside its own horizontal (or
 * vertical) strip so panels can never overlap regardless of window size. Implements
 * GraphEditor.Host so the full-sync guard (isPixelEditorOpen) and the sendOp pendingLocalOps
 * counter keep working. Painting is local; the current frame syncs via a targeted
 * SET_IMAGE_PIXELS op on close/frame switch. Canvas size moved into a "Canvas" button popup
 * with separate Apply/Cancel.
 */
public class PixelEditorScreen extends Screen implements GraphEditor.Host, PixelEditorFrameStrip.Host, PixelEditorToolRail.Host {

    // ── 布局常量 / layout constants (tiled, non-overlapping) ──
    private static final int TOP_H = 30;                       // 顶栏高（容纳两行）/ top bar height (holds two rows)
    private static final int LEFT_W = 30;                      // 左面板宽（PS 式工具列，紧贴左缘）/ left panel width (PS-style tool rail, flush against the left)
    private static final int LEFT_PAD = 6;                     // 面板内边距 / panel padding
    private static final int PAL_W = Math.round(ColorPickerWidget.WIDTH * 0.8f); // 取色器分区宽（紧贴取色器）/ palette band width (hugs the palette)
    private static final float PALETTE_SCALE = 0.8f;       // 常驻右侧取色器缩放 / always-on palette scale
    private static final int FRAME_STRIP_H = 44;               // 序列缩略图条高（紧贴屏幕底部、更紧凑）/ sequence thumbnail-strip height (flush to the bottom, compact)
    private static final int FS_BTN_H = 20;                    // 序列按钮行高（紧邻缩略图条上方、更紧凑）/ sequence button-row height (directly above the thumbnail strip, compact)
    private static final float MIN_ZOOM = 0.4f, MAX_ZOOM = 8f;

    // ── 调色板（沿用基线配色）/ palette (baseline colours) ──
    private static final int C_BG = NodeRenderer.PBG();                // 面板底 / panel bg
    private static final int C_BORDER = NodeRenderer.PBR();            // 面板描边 / panel border
    private static final int C_BTN = 0xFF3A3428;               // 按钮底 / button bg
    private static final int C_HOVER = 0xFF5A4A3A;             // 悬停 / hover
    private static final int C_SEL = 0xFF3A5A2A;               // 选中 / selected
    private static final int C_DEL = 0xFF7A4A3A;               // 关闭/删除危险 / destructive
    private static final int C_TXT_BRIGHT = 0xFFFFFFFF;
    private static final int C_TXT_DIM = 0xFFCCCCCC;
    private static final int C_CANVAS = 0xFF14120E;            // 画布区底 / canvas area bg
    private static final int C_CELL = 0xFF3A3830;              // 单元格描边 / cell outline
    private static final int C_OPACITY = 0xFF8A9A5A;           // 透明度滑杆填充 / opacity fill

    // ── 工具（枚举与列序在 PixelEditorToolRail；当前工具归屏幕 —— 画布/快捷键/顶栏共用）──
    //    Tools (the enum and rail order live in PixelEditorToolRail; the active tool stays on
    //    the screen — canvas, shortcuts and the top bar all use it).
    private Tool tool = Tool.BRUSH;
    private int brushSize = 1;
    /** 笔刷大小范围（滑块与 [ / ] 快捷键共用）。/ brush-size range (shared by the slider and the [ / ] keys). */
    private static final int BRUSH_MIN = 1, BRUSH_MAX = 32;
    /** 笔刷透明度 0..1（与现有像素 alpha 混合；左面板滑杆调节）/ brush opacity */
    private float brushOpacity = 1f;
    /** 顶栏第二行右侧笔刷大小滑块拖动中 / brush-size slider (top-bar row 2) being dragged */
    private boolean brushSizeDragging = false;
    private int selectedColor = 0xFFFFFFFF;
    /** 网格开关（G 键切换；默认关闭避免像素间出现间隙——关闭时画布更干净）。/
     *  grid on/off (toggled by G; off by default so pixels have no gaps — a cleaner canvas). */
    private boolean showGrid = false;

    // ── 核心状态 / core state ──
    private final BlockPos blockPos;
    private final GraphNode node;          // BE 图中的活跃引用（守卫防止整图替换）/ live node ref (guarded)
    private final Screen returnScreen;     // 关闭后恢复的界面 / screen to restore on close

    // ── 画布视图 / canvas view ──
    private float zoom = 1f;
    private float panX = 0f, panY = 0f;
    private boolean panning = false;       // 中键 / 空格+左键 / middle-drag or space+LMB
    private boolean spaceDown = false;
    private boolean paintingStroke = false; // 笔划进行中 / stroke in progress

    // ── 形状预览（直线/矩形）/ shape preview ──
    private boolean shapeInProgress = false;
    private int shapeStartX = -1, shapeStartY = -1;
    private int shapeCurX = -1, shapeCurY = -1;

    // ── 像素内核（绘制算法 + 撤销栈，已拆出，docs/gui-decomposition-plan.md 步骤 4）──
    //    Pixel kernel (painting algorithms + undo stacks, split out). View state (zoom/pan/
    //    tool/brush size/opacity) stays here and is passed in per call.
    private final PixelEditorKernel kernel = new PixelEditorKernel();
    private boolean strokeUndoCaptured = false;
    /** 左侧工具列视图（同批拆分）：渲染与点击，工具选择经 Host 读写回屏幕。
     *  Left tool-rail view (same batch): render + clicks; tool selection round-trips through its Host. */
    private final PixelEditorToolRail toolRail = new PixelEditorToolRail(this);

    // ── 取色器 / color picker (docked into the right band; collapsed by default) ──
    private final ColorPickerWidget colorPicker = new ColorPickerWidget();

    // ── 画布尺寸（「尺寸」按钮 + 弹窗）/ canvas size (button + popup) ──
    private final EditBox sizeWField, sizeHField;
    private boolean sizeDialogOpen = false;
    /** 操作指南弹窗是否显示 / operation-guide popup visible */
    private boolean showGuide = false;

    // ── 帧条（IMAGE_SEQUENCE，已拆分，docs/gui-decomposition-plan.md 步骤 4）──
    //    Frame strip (split out): frame state (index/scroll/drag/+New menu) lives in the
    //    strip; strip geometry, persistence and sync come back through its Host.
    private final PixelEditorFrameStrip frameStrip = new PixelEditorFrameStrip(this, kernel);

    public PixelEditorScreen(BlockPos pos, GraphNode node, Screen returnScreen) {
        super(Component.translatable("container." + SchematicCompute.MOD_ID + ".monitor"));
        this.blockPos = pos;
        this.node = node;
        this.returnScreen = returnScreen;
        if (node.type == NodeType.IMAGE_SEQUENCE) {
            if (node.imageSequenceFrames == null || node.imageSequenceFrames.isEmpty()) {
                node.imageSequenceFrames = new ArrayList<>();
                int[] frame = new int[node.imageWidth * node.imageHeight];
                java.util.Arrays.fill(frame, 0x00000000);
                node.imageSequenceFrames.add(frame);
            }
            node.imagePixels = node.imageSequenceFrames.get(0);
        }
        sizeWField = new EditBox(Minecraft.getInstance().font, 0, 0, 44, 16, Component.literal(""));
        sizeHField = new EditBox(Minecraft.getInstance().font, 0, 0, 44, 16, Component.literal(""));
        for (var f : new EditBox[]{sizeWField, sizeHField}) {
            f.setMaxLength(2);
            f.setValue("16");
            f.setFilter(s -> {
                for (int i = 0; i < s.length(); i++) {
                    char c = s.charAt(i);
                    if (c < '0' || c > '9') return false;
                }
                return true;
            });
        }
        sizeWField.setValue(String.valueOf(node.imageWidth));
        sizeHField.setValue(String.valueOf(node.imageHeight));
    }

    @Override public boolean isPauseScreen() { return false; }

    /** 取色器内嵌式常驻右侧面板：设为内嵌模式（非浮空弹窗）、持久（OK 不关闭）并一次打开、常驻不隐藏。
     *  The palette is an embedded always-on right panel: set embedded mode (not a floating popup),
     *  persistent (OK keeps it open) and open it once so it stays visible. */
    @Override protected void init() {
        super.init();
        colorPicker.setPersistent(true);
        colorPicker.setScale(PALETTE_SCALE);
        colorPicker.setEmbedded(true);
        colorPicker.open(width / 2, height / 2, selectedColor,
            c -> selectedColor = c,
            c -> selectedColor = c,
            false);  // right-side / 右侧
    }

    // ══════════════ GraphEditor.Host ══════════════

    @Override public NodeGraph getGraph() { MonitorBlockEntity be = getBE(); return be != null ? be.getNodeGraph() : new NodeGraph(); }
    @Override public void saveGraph() { /* 像素编辑器不做整图保存 / no full-graph save here */ }
    @Override public void toggleRunning(boolean start) { /* no-op */ }
    @Override public boolean isRunning() { MonitorBlockEntity be = getBE(); return be != null && be.isRunning(); }
    @Override public Screen asScreen() { return this; }
    @Override public BlockPos getBlockPos() { return blockPos; }
    @Override public UUID getPlayerUUID() {
        return minecraft != null && minecraft.player != null ? minecraft.player.getUUID() : UUID.randomUUID();
    }
    @Override public String getPlayerName() {
        return minecraft != null && minecraft.player != null ? minecraft.player.getName().getString() : "";
    }
    @Override public boolean isPixelEditorOpen() { return true; }
    @Override public GraphEditor getEditor() { return null; }
    @Override public void sendOp(GraphOp op) {
        var be = getBE();
        if (be != null) be.setPendingLocalOps(be.getPendingLocalOps() + 1);
        PacketDistributor.sendToServer(new GraphEditOpPacket(op));
    }

    private MonitorBlockEntity getBE() {
        if (minecraft != null && minecraft.level != null
            && minecraft.level.getBlockEntity(blockPos) instanceof MonitorBlockEntity be) return be;
        return null;
    }

    // ══════════════ PixelEditorFrameStrip.Host（帧条接缝）/ frame-strip host seam ══════════════
    // sendOp(GraphOp) 已由上方 GraphEditor.Host 实现承担 / sendOp is already implemented for GraphEditor.Host above.

    @Override public int height() { return height; }
    @Override public GraphNode node() { return node; }
    @Override public BlockPos blockPos() { return blockPos; }
    @Override public UUID playerUUID() { return getPlayerUUID(); }

    // ══════════════ PixelEditorToolRail.Host（工具列接缝）/ tool-rail host seam ══════════════

    @Override public Tool tool() { return tool; }
    @Override public void setTool(Tool t) { tool = t; }
    @Override public int brushSize() { return brushSize; }
    @Override public void setBrushSize(int v) { brushSize = v; }
    @Override public float brushOpacity() { return brushOpacity; }
    @Override public void setBrushOpacity(float v) { brushOpacity = v; }
    @Override public int selectedColor() { return selectedColor; }
    @Override public int topH() { return TOP_H; }
    @Override public int leftW() { return LEFT_W; }
    @Override public int leftPad() { return LEFT_PAD; }

    // ══════════════ 生命周期 / lifecycle ══════════════

    @Override public void tick() {
        if (getBE() == null) {
            minecraft.setScreen(null);
            return;
        }
    }

    @Override public void onClose() {
        var be = getBE();
        if (be != null) sendFrameSync();
        closeSizeDialog();      // 丢弃未应用的尺寸 / discard a pending size dialog
        colorPicker.close();
        if (minecraft != null) {
            if (returnScreen != null && getBE() != null) minecraft.setScreen(returnScreen);
            else minecraft.setScreen(null);
        }
        // 不要调用 super.onClose()：Minecraft 默认的 onClose 会 setScreen(null)，会覆盖上面设的返回界面，
        // 导致关闭像素编辑器后直接回到游戏而不是图编辑器。/ Do NOT call super.onClose(): the default
        // Screen.onClose() calls setScreen(null), which would override the return screen set above and send
        // the player to the game instead of back to the graph editor.
    }

    @Override public void removed() {
        // 收尾全部在 onClose() 完成 / all teardown happens in onClose()
    }

    // ══════════════ 区域几何（瓦片式，互不重叠）/ region geometry (tiled) ══════════════

    private boolean isSeq() { return node.type == NodeType.IMAGE_SEQUENCE; }
    /** 中央列左缘（左面板右缘）。/ central column left edge (right edge of the left panel). */
    private int centralLeft() { return LEFT_W + LEFT_PAD; }
    /** 取色器面板展开时的左缘；收起时等于右缘。/ palette panel left edge when expanded (else screen right). */
    @Override public int paletteLeft() { return width - (colorPicker.isVisible() ? PAL_W : 0); }
    /** 中央列右缘（取色器左缘减内边距）。/ central column right edge. */
    private int centralRight() { return Math.max(centralLeft() + 20, paletteLeft() - LEFT_PAD); }
    private int canvasY() { return TOP_H + LEFT_PAD; }
    /** 序列缩略图条顶部（紧贴屏幕底部，条从这延续到 height）。/ thumbnail strip top (flush to the bottom; the strip spans here..height). */
    @Override public int frameStripY() { return height - FRAME_STRIP_H; }
    /** 序列按钮行顶部（紧挨在缩略图条上方）。/ sequence button-row top (directly above the thumbnail strip). */
    private int frameBtnY() { return frameStripY() - FS_BTN_H; }
    @Override public int frameBtnH() { return FS_BTN_H; }
    private int canvasBottom() { return isSeq() ? frameBtnY() : height - LEFT_PAD; }

    /** 画布视口矩形：[x, y, w, h]。/ canvas viewport rect. */
    private int[] canvasRect() {
        int x = centralLeft();
        int y = canvasY();
        int w = centralRight() - x;
        int h = canvasBottom() - y;
        if (w < 20) w = 20;
        if (h < 20) h = 20;
        return new int[]{x, y, w, h};
    }

    /** 序列按钮行矩形（紧邻缩略图条上方、同样横跨）：[x, y, w, h]。/
     *  sequence button-row rect (directly above the strip, same full span). */
    @Override public int[] frameBtnRect() {
        return new int[]{LEFT_W, frameBtnY(), paletteLeft() - LEFT_W, FS_BTN_H};
    }

    /** 帧条首缩略图 x（紧贴左工具列右缘）。/ first-thumbnail x (right beside the left rail). */
    @Override public int thumbStartX() { return LEFT_W + 8; }

    // ── 左面板内部几何 / left-panel inner geometry ──

    /** 归一化基准格（zoom=1 时恰好适配视口）。/ base cell size (zoom=1 fits the viewport). */
    private int baseCell() {
        int[] cr = canvasRect();
        int imgW = node.imageWidth, imgH = node.imageHeight;
        return Math.max(1, (int)(Math.min(cr[2] * 0.92f, cr[3] * 0.92f) / Math.max(1, Math.max(imgW, imgH))));
    }

    /** 使画布适配到视口：zoom=1、居中、清空平移。 / fit the canvas to the viewport. */
    private void fitCanvas() { zoom = 1f; panX = 0f; panY = 0f; }

    /** 网格几何：返回 [ox, oy, cell]（画布左上角 + 单元格像素尺寸）。 */
    private int[] gridGeom() {
        int[] cr = canvasRect();
        int imgW = node.imageWidth, imgH = node.imageHeight;
        int base = baseCell();
        int cell = Math.max(1, Math.round(base * zoom));
        int gridPx = cell * imgW, gridPy = cell * imgH;
        int ox = cr[0] + (cr[2] - gridPx) / 2 + (int)panX;
        int oy = cr[1] + (cr[3] - gridPy) / 2 + (int)panY;
        return new int[]{ox, oy, cell};
    }

    /** 鼠标 → 单元格（越界返回 [-1,-1]）。/ mouse → cell (out-of-bounds = [-1,-1]). */
    private int[] cellAt(double mx, double my) {
        int[] cr = canvasRect();
        if (mx < cr[0] || my < cr[1] || mx >= cr[0] + cr[2] || my >= cr[1] + cr[3])
            return new int[]{-1, -1};
        int[] ge = gridGeom();
        int ox = ge[0], oy = ge[1], cell = ge[2];
        if (mx < ox || my < oy || mx >= ox + cell * node.imageWidth || my >= oy + cell * node.imageHeight)
            return new int[]{-1, -1};
        return new int[]{(int)Math.floor((mx - ox) / cell), (int)Math.floor((my - oy) / cell)};
    }

    // ── 左面板内部几何（已随 PixelEditorToolRail 拆出；屏幕保留布局常量供其余面板使用）──
    //    Left-panel inner geometry moved with PixelEditorToolRail; the layout constants stay
    //    for the other panels.

    private static boolean hit(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    // ══════════════ 渲染 / render ══════════════

    @Override public void render(GuiGraphics g, int mx, int my, float pt) {
        // 不透明背景（替换半透明 renderBackground），放在最深 / opaque background (replaces
        // renderBackground) at the furthest depth so the canvas can sit behind the panels.
        g.fill(0, 0, width, height, C_BG);
        // 组件/面板整体抬到近处深度（z=+100），画布抬到中间深度（z=+50）：画布被组件用
        // 真·深度缓冲遮挡（RenderType.gui 为 LEQUAL + 写深度），而不是用 scissor 遮罩硬裁。
        // Panels sit at a near depth (z=+100); the canvas sits at a mid depth (z=+50), so the
        // panels occlude the canvas via the real depth buffer (RenderType.gui is LEQUAL + writes
        // depth) instead of hard-scissoring it.
        g.pose().pushPose();
        g.pose().translate(0f, 0f, 100f);
        renderTopBar(g, mx, my);
        toolRail.render(g, mx, my);
        g.pose().pushPose();
        g.pose().translate(0f, 0f, -50f);
        renderCanvas(g, mx, my);
        g.pose().popPose();
        // 帧条画在中层深度（同画布）：翻动时越界的缩略图会被左右面板（近层 +100）用深度缓冲遮挡，
        // 不会盖住左侧工具栏/右调色板。/ frame strip at mid depth (like the canvas): overflowing thumbs
        // are occluded by the near-depth panels so they never cover the left rail / right palette.
        if (isSeq()) {
            g.pose().pushPose();
            g.pose().translate(0f, 0f, -50f);
            frameStrip.render(g, mx, my);
            g.pose().popPose();
        }
        // 取色器：内嵌式常驻右侧面板，面板铺满右缘到屏幕底部；标题字放大、常用/最近更多行。
        // Palette: embedded always-on right panel that fills the right band down to the screen bottom.
        if (colorPicker.isVisible()) {
            colorPicker.setScale(PALETTE_SCALE);
            colorPicker.setEmbedded(true);
            int pleft = paletteLeft();
            g.fill(pleft, TOP_H, width, height, C_BG);
            g.fill(pleft - 1, TOP_H, pleft, height, C_BORDER);
            colorPicker.setPosition(pleft, TOP_H);
            colorPicker.render(g, mx, my);
        }
        if (sizeDialogOpen) renderSizeDialog(g, mx, my);
        if (showGuide) renderGuide(g, mx, my);
        g.pose().popPose();
    }

    /** 操作指南弹窗矩形（居中）。/ operation-guide popup rect (centred). */
    private int[] guideRect() {
        int w = 300, h = 150;
        return new int[]{(width - w) / 2, (height - h) / 2, w, h};
    }

    /** 操作指南弹窗：标题 + 快捷键列表 + 关闭。 / operation-guide popup: title + shortcuts + close. */
    private void renderGuide(GuiGraphics g, int mx, int my) {
        int[] r = guideRect();
        int x = r[0], y = r[1], w = r[2], h = r[3];
        var f = Minecraft.getInstance().font;
        g.fill(x, y, x + w, y + h, 0xF02A2822);
        g.renderOutline(x, y, w, h, C_BORDER);
        g.renderOutline(x + 1, y + 1, w - 2, h - 2, 0xFF444444);
        g.fill(x, y, x + w, y + 18, NodeRenderer.PHT());
        g.drawString(f, "§e" + I18n.get("gui.create_schematic_compute.monitor.pixel_guide_title"), x + 8, y + 5, C_TXT_BRIGHT, false);
        g.drawString(f, "§7✕", x + w - 16, y + 5, C_TXT_DIM, false);
        int cy = y + 26;
        for (String ln : guideLines()) { g.drawString(f, ln, x + 10, cy, C_TXT_DIM, false); cy += 12; }
    }

    /** 操作指南内容行：纯当前语言（随语言切换，只显示对应语言）。/ guide lines: localized to the current language only. */
    private List<String> guideLines() {
        return List.of(
            I18n.get("gui.create_schematic_compute.monitor.pixel_guide_tools"),
            I18n.get("gui.create_schematic_compute.monitor.pixel_guide_order"),
            I18n.get("gui.create_schematic_compute.monitor.pixel_guide_size"),
            I18n.get("gui.create_schematic_compute.monitor.pixel_guide_grid"),
            I18n.get("gui.create_schematic_compute.monitor.pixel_guide_undo"),
            I18n.get("gui.create_schematic_compute.monitor.pixel_guide_zoom_pan"),
            I18n.get("gui.create_schematic_compute.monitor.pixel_guide_erase"),
            I18n.get("gui.create_schematic_compute.monitor.pixel_guide_canvas")
        );
    }

    /** 操作指南点击：点 ✕ 或弹窗外关闭，内部吞掉。 / guide click: close on ✕/outside, swallow inside. */
    private boolean handleGuideClick(double mx, double my, int btn) {
        if (btn != 0) return true;
        int[] r = guideRect();
        if (hit(mx, my, r[0] + r[2] - 18, r[1] + 3, 14, 14)
            || mx < r[0] || mx >= r[0] + r[2] || my < r[1] || my >= r[1] + r[3]) showGuide = false;
        return true;
    }

    /** 顶栏：第一行＝画笔状态＋按钮（无标题文本，更简洁）；第二行＝坐标显示。
     *  Top bar: row 1 = brush state + buttons (no title text, cleaner); row 2 = coordinate display. */
    private void renderTopBar(GuiGraphics g, int mx, int my) {
        g.fill(0, 0, width, TOP_H, C_BG);
        g.fill(0, TOP_H - 1, width, TOP_H, C_BORDER);
        var f = Minecraft.getInstance().font;
        int[] rc = rightCluster();
        // 无标题文本：更简洁，画笔状态居中填满 / no title text — cleaner; brush state centred in the bar
        renderBrushState(g, 10, rc[0] - 8);
        drawBarButton(g, rc[5], 3, 48, 14, I18n.get("gui.create_schematic_compute.monitor.pixel_close"), hit(mx, my, rc[5], 3, 48, 14), true);
        drawBarButton(g, rc[4], 3, 44, 14, I18n.get("gui.create_schematic_compute.monitor.pixel_size"), hit(mx, my, rc[4], 3, 44, 14), false);
        drawBarButton(g, rc[3], 3, 44, 14, I18n.get("gui.create_schematic_compute.monitor.pixel_redo"), hit(mx, my, rc[3], 3, 44, 14), false);
        drawBarButton(g, rc[2], 3, 44, 14, I18n.get("gui.create_schematic_compute.monitor.pixel_undo"), hit(mx, my, rc[2], 3, 44, 14), false);
        drawBarButton(g, rc[1], 3, 44, 14, I18n.get("gui.create_schematic_compute.monitor.pixel_guide"), hit(mx, my, rc[1], 3, 44, 14), false);
        drawBarButton(g, rc[0], 3, 40, 14, I18n.get("gui.create_schematic_compute.monitor.pixel_fit"), hit(mx, my, rc[0], 3, 40, 14), false);
        // ── 第二行：坐标/缩放/网格/帧号（左）+ 笔刷大小滑块（右，右对齐） ──
        g.fill(0, 18, width, 19, C_BORDER);
        int statusMaxW = brushSliderLabelLeft() - 12;   // 状态文本最宽（止于滑块标签前）/ status max width (ends before the slider label)
        var fb = new StringBuilder();
        int[] c = cellAt(mx, my);
        fb.append("§7坐标 (").append(c[0] >= 0 ? c[0] + ", " + c[1] : "—, —").append(")");
        if (isSeq()) fb.append("  F").append(frameStrip.frameIndex() + 1).append("/").append(node.imageSequenceFrames.size());
        fb.append("  ").append(gridGeom()[2]).append("px");
        fb.append("  ").append(showGrid ? "Grid" : "No grid");
        g.drawString(f, fitRowText(f, fb.toString(), statusMaxW), 10, 20, C_TXT_DIM, false);
        renderBrushSizeSlider(g, mx, my);
    }

    /** 顶栏第二行状态文本：超宽时依次丢弃最右侧段（Grid → px → F）直到放得下。
     *  Row-2 status text: drop rightmost segments (Grid → px → F) until it fits the row. */
    private static String fitRowText(Font f, String s, int maxW) {
        if (f.width(s) <= maxW) return s;
        while (f.width(s) > maxW) {
            int sp = s.lastIndexOf("  ");
            if (sp <= 0) break;
            s = s.substring(0, sp);
        }
        return s;
    }

    /** 顶栏第二行右侧笔刷大小滑块轨道几何：[x, y, w, h]（右对齐，紧贴右缘）。/
     *  brush-size slider track geom in the top-bar second row (right-aligned, flush to the right). */
    private int[] brushSliderGeom() {
        int w = 78, h = 5;
        return new int[]{width - 8 - w, 21, w, h};
    }

    /** 滑块标签块的左缘（状态文本不得超过此位置）。/ left edge of the slider label block (status text must stop before it). */
    private int brushSliderLabelLeft() {
        var f = Minecraft.getInstance().font;
        int[] bs = brushSliderGeom();
        String label = "§7" + I18n.get("gui.create_schematic_compute.monitor.pixel_brush_size") + ":";
        String val = "§e" + brushSize;
        return bs[0] - 8 - f.width(val) - 4 - f.width(label);
    }

    /** 顶栏第二行右侧笔刷大小滑块：标签 + 数值 + 轨道 + 滑块头（线性映射 1..32）。
     *  brush-size slider (top-bar row 2, right): label + value + track + thumb (linear 1..32). */
    private void renderBrushSizeSlider(GuiGraphics g, int mx, int my) {
        var f = Minecraft.getInstance().font;
        int[] bs = brushSliderGeom();
        int tx = bs[0], ty = bs[1], tw = bs[2], th = bs[3];
        String label = "§7" + I18n.get("gui.create_schematic_compute.monitor.pixel_brush_size") + ":";
        String val = "§e" + brushSize;
        int lx = brushSliderLabelLeft();   // 标签+数值右对齐到轨道左侧 / label+value right-aligned before the track
        g.drawString(f, label, lx, ty - 1, C_TXT_DIM, false);
        g.drawString(f, val, lx + f.width(label) + 4, ty - 1, C_TXT_BRIGHT, false);
        boolean hov = hit(mx, my, tx - 3, ty - 3, tw + 6, th + 6);
        g.fill(tx, ty, tx + tw, ty + th, hov ? 0xFF2A2A24 : C_CANVAS);
        g.renderOutline(tx, ty, tw, th, C_BORDER);
        float t = (brushSize - BRUSH_MIN) / (float)(BRUSH_MAX - BRUSH_MIN);
        int fx = tx + Math.round(tw * t);
        g.fill(tx, ty, fx, ty + th, C_OPACITY);
        g.fill(fx - 2, ty - 2, fx + 3, ty + th + 2, C_TXT_DIM);
    }

    /** 从鼠标 x 设置笔刷大小（1..32 线性映射）。/ set brush size from mouse x (linear 1..32). */
    private void setBrushSizeFromX(double mx) {
        int[] bs = brushSliderGeom();
        float t = (float)((mx - bs[0]) / bs[2]);
        brushSize = Math.max(BRUSH_MIN, Math.min(BRUSH_MAX, Math.round(BRUSH_MIN + t * (BRUSH_MAX - BRUSH_MIN))));
    }

    /** 顶栏画笔状态：工具 + 笔刷大小 + 透明度 + 当前色块（居中于标题与右按钮之间；空间不足靠左）。
     *  Top-bar brush state: tool + brush size + opacity + current colour swatch (centred, falls back left). */
    private void renderBrushState(GuiGraphics g, int titleRight, int availEnd) {
        var f = Minecraft.getInstance().font;
        boolean paintTool = tool == Tool.BRUSH || tool == Tool.ERASER || tool == Tool.LINE || tool == Tool.RECT;
        StringBuilder sb = new StringBuilder();
        sb.append("§e").append(I18n.get("gui.create_schematic_compute.monitor.pixel_tool_" + tool.name().toLowerCase()));
        if (paintTool) sb.append(" §7").append(brushSize).append("px");
        if (tool == Tool.BRUSH || tool == Tool.FILL) sb.append(" §7").append(Math.round(brushOpacity * 100)).append("%");
        String txt = sb.toString();
        int sw = 12, gap = 6;
        int total = f.width(txt) + gap + sw;
        int x = (titleRight + availEnd) / 2 - total / 2;
        if (x < titleRight + 4) x = titleRight + 4;
        g.drawString(f, txt, x, 3, 0xFFD0D0D0, false);
        int cxx = x + f.width(txt) + gap;
        g.fill(cxx, 4, cxx + sw, 4 + sw, selectedColor);
        g.renderOutline(cxx, 4, sw, sw, C_BORDER);
    }

    /** 顶栏右侧按钮簇（右对齐）：[fitX, guideX, undoX, redoX, sizeX, closeX]。宽 40/44/44/44/44/48。 */
    private int[] rightCluster() {
        int right = width - 8;
        int closeX = right - 48;
        int sizeX = closeX - 6 - 44;
        int redoX = sizeX - 6 - 44;
        int undoX = redoX - 6 - 44;
        int guideX = undoX - 6 - 44;
        int fitX = guideX - 6 - 40;
        return new int[]{fitX, guideX, undoX, redoX, sizeX, closeX};
    }

    private void drawBarButton(GuiGraphics g, int x, int y, int w, int h, String label, boolean hover, boolean danger) {
        int bg = danger ? C_DEL : (hover ? C_HOVER : C_BTN);
        g.fill(x, y, x + w, y + h, bg);
        g.renderOutline(x, y, w, h, C_BORDER);
        var f = Minecraft.getInstance().font;
        g.drawString(f, "§7" + label, x + (w - f.width("§7" + label)) / 2, y + (h - f.lineHeight) / 2, C_TXT_DIM, false);
    }

    private void renderCanvas(GuiGraphics g, int mx, int my) {
        int[] ge = gridGeom();
        int ox = ge[0], oy = ge[1], cell = ge[2];
        int imgW = node.imageWidth, imgH = node.imageHeight;
        int gridPx = cell * imgW, gridPy = cell * imgH;
        // 没有边框、没有 scissor 遮罩、没有独立视口底色：网格/像素直接画在背景上，超出原视口的
        // 部分由上方组件用深度缓冲遮挡（画布整体位于组件之下的中间深度）。/ No border, no
        // scissor mask and no separate viewport backdrop: the grid draws straight onto the
        // background, and anything extending past the old viewport is now occluded by the panels'
        // near depth (the canvas sits at the mid depth underneath them).
        g.fill(ox - 3, oy - 3, ox + gridPx + 3, oy + gridPy + 3, C_BG);
        int[] pixels = node.imagePixels;
        for (int py = 0; py < imgH; py++) {
            for (int px = 0; px < imgW; px++) {
                int idx = py * imgW + px;
                int color = (pixels != null && idx < pixels.length) ? pixels[idx] : 0;
                int x1 = ox + px * cell, y1 = oy + py * cell;
                if ((color & 0xFF000000) == 0) {
                    int ck = ((px + py) & 1) * 0x222222;
                    g.fill(x1, y1, x1 + cell, y1 + cell, 0xFF222222 + ck);
                } else {
                    g.fill(x1, y1, x1 + cell, y1 + cell, color);
                }
                if (showGrid) g.renderOutline(x1, y1, cell, cell, C_CELL);   // 网格开关 / grid toggle (G)
            }
        }
        if (shapeInProgress && (tool == Tool.LINE || tool == Tool.RECT)) {
            previewShape(g, ox, oy, cell, 0x66FFFFFF);
        }
    }

    /** 预览直线/矩形：覆盖在像素上的半透明幽灵。Ghost preview of line/rect over the pixels. */
    private void previewShape(GuiGraphics g, int ox, int oy, int cell, int color) {
        int x0 = shapeStartX, y0 = shapeStartY, x1 = shapeCurX, y1 = shapeCurY;
        if (x0 < 0 || x1 < 0) return;
        int imgW = node.imageWidth, imgH = node.imageHeight;
        if (tool == Tool.LINE) {
            int dx = Math.abs(x1 - x0), dy = Math.abs(y1 - y0);
            int sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1;
            int err = dx - dy;
            int cx = x0, cy = y0;
            while (true) {
                stampPreview(g, ox, oy, cell, cx, cy, color, imgW, imgH);
                if (cx == x1 && cy == y1) break;
                int e2 = 2 * err;
                if (e2 > -dy) { err -= dy; cx += sx; }
                if (e2 < dx) { err += dx; cy += sy; }
            }
        } else {
            int minX = Math.min(x0, x1), maxX = Math.max(x0, x1);
            int minY = Math.min(y0, y1), maxY = Math.max(y0, y1);
            for (int x = minX; x <= maxX; x++) { stampPreview(g, ox, oy, cell, x, minY, color, imgW, imgH); stampPreview(g, ox, oy, cell, x, maxY, color, imgW, imgH); }
            for (int y = minY; y <= maxY; y++) { stampPreview(g, ox, oy, cell, minX, y, color, imgW, imgH); stampPreview(g, ox, oy, cell, maxX, y, color, imgW, imgH); }
        }
    }

    private void stampPreview(GuiGraphics g, int ox, int oy, int cell, int cx, int cy, int color, int imgW, int imgH) {
        int half = brushSize / 2;
        for (int dy = -half; dy < brushSize - half; dy++)
            for (int dx = -half; dx < brushSize - half; dx++) {
                int x = cx + dx, y = cy + dy;
                if (x >= 0 && x < imgW && y >= 0 && y < imgH)
                    g.fill(ox + x * cell, oy + y * cell, ox + (x + 1) * cell, oy + (y + 1) * cell, color);
            }
    }

    // ══════════════ 画布尺寸弹窗 / canvas-size dialog ══════════════

    private int[] sizeDialogRect() {
        int w = 190, h = 118;
        int x = (width - w) / 2, y = (height - h) / 2;
        return new int[]{x, y, w, h};
    }

    private void openSizeDialog() {
        sizeDialogOpen = true;
        sizeWField.setValue(String.valueOf(node.imageWidth));
        sizeHField.setValue(String.valueOf(node.imageHeight));
        sizeWField.setFocused(true);
        sizeHField.setFocused(false);
    }

    private void closeSizeDialog() {
        sizeDialogOpen = false;
        sizeWField.setValue(String.valueOf(node.imageWidth));
        sizeHField.setValue(String.valueOf(node.imageHeight));
        sizeWField.setFocused(false);
        sizeHField.setFocused(false);
    }

    private void commitSizeDialog() {
        commitSizeFields();   // parse + clamp + applyPixelResize
        sizeDialogOpen = false;
        sizeWField.setFocused(false);
        sizeHField.setFocused(false);
        sizeWField.setValue(String.valueOf(node.imageWidth));
        sizeHField.setValue(String.valueOf(node.imageHeight));
    }

    private void renderSizeDialog(GuiGraphics g, int mx, int my) {
        int[] r = sizeDialogRect();
        int x = r[0], y = r[1], w = r[2], h = r[3];
        g.fill(x, y, x + w, y + h, C_BG);
        g.renderOutline(x, y, w, h, C_BORDER);
        g.renderOutline(x + 1, y + 1, w - 2, h - 2, C_BORDER);
        var f = Minecraft.getInstance().font;
        g.drawString(f, "§e" + I18n.get("gui.create_schematic_compute.monitor.pixel_size_title"), x + 10, y + 8, C_TXT_BRIGHT, false);
        int fx = x + 10, fieldW = 44;
        // W 行 / W row
        g.drawString(f, "W:", fx, y + 30, C_TXT_DIM, false);
        sizeWField.setX(fx + 18); sizeWField.setY(y + 28); sizeWField.setWidth(fieldW);
        sizeWField.render(g, mx, my, 0);
        // H 行 / H row
        g.drawString(f, "H:", fx, y + 54, C_TXT_DIM, false);
        sizeHField.setX(fx + 18); sizeHField.setY(y + 52); sizeHField.setWidth(fieldW);
        sizeHField.render(g, mx, my, 0);
        // 按钮 / buttons
        int btnY = y + h - 30;
        int bx = x + 10, bw = (w - 20 - 6) / 2;
        boolean apH = hit(mx, my, bx, btnY, bw, 20);
        g.fill(bx, btnY, bx + bw, btnY + 20, apH ? C_HOVER : C_BTN);
        g.renderOutline(bx, btnY, bw, 20, C_BORDER);
        g.drawString(f, "§7" + I18n.get("gui.create_schematic_compute.monitor.pixel_apply"), bx + (bw - f.width("§7" + I18n.get("gui.create_schematic_compute.monitor.pixel_apply"))) / 2, btnY + 5, C_TXT_DIM, false);
        int cx = bx + bw + 6;
        boolean cnH = hit(mx, my, cx, btnY, bw, 20);
        g.fill(cx, btnY, cx + bw, btnY + 20, cnH ? C_HOVER : C_BTN);
        g.renderOutline(cx, btnY, bw, 20, C_BORDER);
        g.drawString(f, "§7" + I18n.get("gui.create_schematic_compute.monitor.pixel_cancel"), cx + (bw - f.width("§7" + I18n.get("gui.create_schematic_compute.monitor.pixel_cancel"))) / 2, btnY + 5, C_TXT_DIM, false);
    }

    // ══════════════ 输入 / input ══════════════

    @Override public boolean mouseClicked(double mx, double my, int btn) {
        if (showGuide) return handleGuideClick(mx, my, btn);
        if (colorPicker.isVisible() && colorPicker.contains((int)mx, (int)my))
            return colorPicker.mouseClicked(mx, my, btn);
        if (sizeDialogOpen) return handleSizeDialogClick(mx, my, btn);
        if (my <= TOP_H) return handleTopBarClick(mx, my, btn);
        if (mx < LEFT_W) return toolRail.handleClick(mx, my, btn);
        if (isSeq() && my >= frameBtnY()) return frameStrip.handleClick(mx, my, btn);
        // 取色器展开时，点击其保留带（不含组件）一律吞掉，避免落入画布
        // When the palette is expanded, swallow clicks in its reserved band so they never
        // reach the canvas.
        if (colorPicker.isVisible() && mx >= paletteLeft()) return true;
        // 画布 / canvas
        // 平移：中键拖拽、空格+左键，或抓手工具（HAND）左键拖拽
        // Pan: middle-drag, Space+LMB, or the Hand tool's LMB drag
        if (btn == 2 || (btn == 0 && spaceDown) || (btn == 0 && tool == Tool.HAND)) {
            if (my >= canvasY()) { panning = true; return true; }
            return false;
        }
        int[] c = cellAt(mx, my);
        if (c[0] < 0) return false;
        applyToolClick(c[0], c[1], btn);
        return true;
    }

    private boolean handleTopBarClick(double mx, double my, int btn) {
        if (btn != 0) return false;
        int[] rc = rightCluster();
        if (hit(mx, my, rc[5], 3, 48, 14)) { onClose(); return true; }
        if (hit(mx, my, rc[4], 3, 44, 14)) { openSizeDialog(); return true; }
        if (hit(mx, my, rc[3], 3, 44, 14)) { performRedo(); return true; }
        if (hit(mx, my, rc[2], 3, 44, 14)) { performUndo(); return true; }
        if (hit(mx, my, rc[1], 3, 44, 14)) { showGuide = !showGuide; return true; }
        if (hit(mx, my, rc[0], 3, 40, 14)) { fitCanvas(); return true; }
        // 笔刷大小滑块（第二行右侧）：点击轨道任意处直接跳转并开始拖动 / brush-size slider (row 2, right): click anywhere on the track to jump & start dragging
        int[] bs = brushSliderGeom();
        if (hit(mx, my, bs[0] - 3, bs[1] - 3, bs[2] + 6, bs[3] + 6)) {
            brushSizeDragging = true;
            setBrushSizeFromX(mx);
            return true;
        }
        return false;
    }

    private boolean handleSizeDialogClick(double mx, double my, int btn) {
        int[] r = sizeDialogRect();
        int x = r[0], y = r[1], w = r[2], h = r[3];
        int fx = x + 10, fieldW = 44;
        // 使字段位置与渲染一致 / sync field geometry with the render
        sizeWField.setX(fx + 18); sizeWField.setY(y + 28); sizeWField.setWidth(fieldW);
        sizeHField.setX(fx + 18); sizeHField.setY(y + 52); sizeHField.setWidth(fieldW);
        int btnY = y + h - 30;
        int bx = x + 10, bw = (w - 20 - 6) / 2;
        int cx = bx + bw + 6;
        if (btn == 0) {
            if (sizeWField.isMouseOver(mx, my)) { sizeWField.setFocused(true); sizeHField.setFocused(false); sizeWField.mouseClicked(mx, my, btn); return true; }
            if (sizeHField.isMouseOver(mx, my)) { sizeHField.setFocused(true); sizeWField.setFocused(false); sizeHField.mouseClicked(mx, my, btn); return true; }
            if (hit(mx, my, bx, btnY, bw, 20)) { commitSizeDialog(); return true; }
            if (hit(mx, my, cx, btnY, bw, 20)) { closeSizeDialog(); return true; }
        }
        // 点击弹窗外部 → 取消 / click outside the dialog → cancel
        if (mx < x || mx >= x + w || my < y || my >= y + h) { closeSizeDialog(); return true; }
        return true;  // 弹窗内非按钮点击吞掉 / swallow clicks inside the dialog
    }

    private void applyToolClick(int cx, int cy, int btn) {
        // 右键 = 直接擦除（任何工具下）；形状工具忽略右键（RMB reserved for erase; shape tools ignore it）
        boolean erasing = (btn == 1) || (tool == Tool.ERASER && btn == 0);
        switch (tool) {
            case BRUSH, ERASER -> {
                captureStrokeUndo();
                PixelEditorKernel.paintBrush(node, cx, cy, erasing ? 0x00000000 : selectedColor, brushSize, brushOpacity);
                if (!erasing) RecentColors.addRecent(selectedColor);
                paintingStroke = true;
                bump();
            }
            case FILL -> {
                if (btn == 1) return;
                captureStrokeUndo();
                PixelEditorKernel.floodFill(node, cx, cy, erasing ? 0x00000000 : selectedColor, brushOpacity);
                bump();
            }
            case EYEDROPPER -> {
                int[] px = node.imagePixels;
                if (px != null && cy * node.imageWidth + cx < px.length) {
                    selectedColor = px[cy * node.imageWidth + cx];
                    RecentColors.addRecent(selectedColor);
                    // 同步内嵌调色板：SV 平面/色相/透明度滑条/hex 输入都切到吸取的颜色
                    // sync the embedded palette so its SV plane / hue / alpha / hex show the picked colour
                    colorPicker.rebind(selectedColor, c -> selectedColor = c, c -> selectedColor = c);
                }
            }
            case LINE, RECT -> {
                if (btn == 1) return;
                shapeInProgress = true;
                shapeStartX = shapeCurX = cx;
                shapeStartY = shapeCurY = cy;
            }
            case HAND -> { /* 抓手左键平移在 mouseClicked 处理，不会到达此处 / Hand panning is handled in mouseClicked */ }
        }
    }

    /** 图代际 +1（绘画 / 帧操作 / 撤销重做后调用；帧条 Host 接缝）。
     *  Bump the graph generation (after painting / frame ops / undo-redo; the frame-strip Host seam). */
    @Override public void bump() {
        var be = getBE();
        if (be != null) be.getNodeGraph().bumpGeneration();
    }

    @Override public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) {
        if (showGuide) return true;
        if (colorPicker.isVisible() && colorPicker.contains((int)mx, (int)my))
            return colorPicker.mouseDragged(mx, my, btn, dx, dy);
        if (sizeDialogOpen) {
            if (sizeWField.isFocused()) return sizeWField.mouseDragged(mx, my, btn, dx, dy);
            if (sizeHField.isFocused()) return sizeHField.mouseDragged(mx, my, btn, dx, dy);
        }
        if (toolRail.handleOpacityDragged(mx)) {
            return true;
        }
        if (brushSizeDragging) {
            setBrushSizeFromX(mx);
            return true;
        }
        if (isSeq() && frameStrip.handleScrollbarDragged(mx)) return true;
        if (panning) {
            panX += (float)dx;
            panY += (float)dy;
            return true;
        }
        if (isSeq() && frameStrip.handleDragged(mx, my)) return true;
        if (shapeInProgress && (tool == Tool.LINE || tool == Tool.RECT)) {
            int[] c = cellAt(mx, my);
            if (c[0] >= 0) { shapeCurX = c[0]; shapeCurY = c[1]; }
            return true;
        }
        if (tool == Tool.BRUSH || tool == Tool.ERASER) {
            int[] c = cellAt(mx, my);
            if (c[0] >= 0) {
                boolean erasing = (btn == 1) || tool == Tool.ERASER;
                captureStrokeUndo();
                PixelEditorKernel.paintBrush(node, c[0], c[1], erasing ? 0x00000000 : selectedColor, brushSize, brushOpacity);
                if (!erasing) RecentColors.addRecent(selectedColor);
                bump();
            }
            return true;
        }
        return super.mouseDragged(mx, my, btn, dx, dy);
    }

    @Override public void mouseMoved(double mx, double my) {
        if (panning || frameStrip.isDragging()) return;
        if (paintingStroke && (tool == Tool.BRUSH || tool == Tool.ERASER)) {
            int[] c = cellAt(mx, my);
            if (c[0] >= 0) {
                boolean erasing = tool == Tool.ERASER;
                captureStrokeUndo();
                PixelEditorKernel.paintBrush(node, c[0], c[1], erasing ? 0x00000000 : selectedColor, brushSize, brushOpacity);
                if (!erasing) RecentColors.addRecent(selectedColor);
                bump();
            }
        }
        if (shapeInProgress && (tool == Tool.LINE || tool == Tool.RECT)) {
            int[] c = cellAt(mx, my);
            if (c[0] >= 0) { shapeCurX = c[0]; shapeCurY = c[1]; }
        }
    }

    @Override public boolean mouseReleased(double mx, double my, int btn) {
        if (showGuide) return true;
        if (colorPicker.isVisible() && colorPicker.contains((int)mx, (int)my)) {
            colorPicker.mouseReleased(mx, my, btn);
            return true;
        }
        if (toolRail.releaseOpacityDrag()) return true;
        if (brushSizeDragging) { brushSizeDragging = false; return true; }
        if (frameStrip.releaseScrollbar()) return true;
        if (panning) { panning = false; return true; }
        if (frameStrip.releaseDrag()) return true;
        if (shapeInProgress && (tool == Tool.LINE || tool == Tool.RECT)) {
            captureStrokeUndo();
            int color = selectedColor;
            if (tool == Tool.LINE) PixelEditorKernel.drawLineCells(node, shapeStartX, shapeStartY, shapeCurX, shapeCurY, color, brushSize, brushOpacity);
            else PixelEditorKernel.drawRectCells(node, shapeStartX, shapeStartY, shapeCurX, shapeCurY, color, brushSize, brushOpacity);
            RecentColors.addRecent(color);
            shapeInProgress = false;
            bump();
            return true;
        }
        paintingStroke = false;
        strokeUndoCaptured = false;
        return super.mouseReleased(mx, my, btn);
    }

    @Override public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (showGuide) return true;
        if (colorPicker.isVisible() && colorPicker.mouseScrolled(mx, my, sy)) return true;
        // 帧条上滚动帧 / scroll the frame strip
        if (isSeq() && my >= frameBtnY()) return frameStrip.handleScrolled(sy);
        // 画布上缩放（以光标为锚点）/ zoom anchored at the cursor
        int[] cr = canvasRect();
        if (mx >= cr[0] && mx <= cr[0] + cr[2] && my >= cr[1] && my <= cr[1] + cr[3]) {
            int[] ge = gridGeom();
            int ox = ge[0], oy = ge[1], cell = ge[2];
            double keepX = mx - ox, keepY = my - oy;
            float newZoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom * (sy > 0 ? 1.1f : 1f / 1.1f)));
            float ratio = newZoom / zoom;
            zoom = newZoom;
            int imgW = node.imageWidth, imgH = node.imageHeight;
            int newCell = Math.max(1, Math.round((int)(Math.min(cr[2] * 0.92f, cr[3] * 0.92f) / Math.max(1, Math.max(imgW, imgH))) * zoom));
            int newGridPx = newCell * imgW, newGridPy = newCell * imgH;
            panX = (float)(mx - (cr[0] + (cr[2] - newGridPx) / 2) - keepX * ratio);
            panY = (float)(my - (cr[1] + (cr[3] - newGridPy) / 2) - keepY * ratio);
            return true;
        }
        return false;
    }

    @Override public boolean keyPressed(int key, int sc, int mod) {
        if (showGuide) { if (key == 256) showGuide = false; return true; }   // 指南打开时 ESC 关闭 / ESC closes the guide
        if (net.minecraft.client.gui.screens.Screen.hasControlDown()) {
            if (key == 90) { performUndo(); return true; }
            if (key == 89) { performRedo(); return true; }
        }
        if (key == 32) { spaceDown = true; return true; } // Space 平移 / pan
        // 尺寸弹窗聚焦的字段优先路由 / route keys to the focused size-dialog field first
        if (sizeDialogOpen) {
            if (key == 256) { closeSizeDialog(); return true; }  // ESC 关闭弹窗 / ESC closes the dialog
            if (key == 257 || key == 335) { commitSizeDialog(); return true; } // Enter 应用 / Enter applies
            if (key == 258) { boolean w = sizeWField.isFocused(); sizeWField.setFocused(!w); sizeHField.setFocused(w); return true; }
            return (sizeWField.isFocused() ? sizeWField : sizeHField).keyPressed(key, sc, mod);
        }
        // 取色器 hex 输入聚焦时路由按键（ESC 仍关闭编辑器）/ route to the palette hex input when focused
        if (colorPicker.isVisible() && colorPicker.isHexFocused()) {
            if (key == 256) { onClose(); return true; }
            return colorPicker.keyPressed(key, sc, mod);
        }
        // PS 风格快捷键：1..7 按工具列顺序、B/E/F/I/L/R/H 工具、[ / ] 笔刷大小、G 网格开关。
        // PS-style shortcuts: 1..7 (rail order), B/E/F/I/L/R/H (tools), [ / ] (brush size), G (grid).
        if (key >= 49 && key <= 55) { tool = PixelEditorToolRail.TOOLS[key - 49]; return true; }
        switch (key) {
            case 66: tool = Tool.BRUSH; return true;           // B
            case 69: tool = Tool.ERASER; return true;          // E
            case 70: tool = Tool.FILL; return true;            // F
            case 73: tool = Tool.EYEDROPPER; return true;      // I
            case 76: tool = Tool.LINE; return true;            // L
            case 82: tool = Tool.RECT; return true;            // R
            case 72: tool = Tool.HAND; return true;            // H 抓手 / hand
            case 71: showGrid = !showGrid; return true;        // G 网格开关 / grid toggle
            case 219: if (brushSize > BRUSH_MIN) brushSize--; return true;   // [ 更小笔刷 / smaller brush
            case 221: if (brushSize < BRUSH_MAX) brushSize++; return true;  // ] 更大笔刷 / bigger brush
        }
        if (key == 256) { onClose(); return true; } // ESC
        return super.keyPressed(key, sc, mod);
    }

    @Override public boolean keyReleased(int key, int sc, int mod) {
        if (key == 32) spaceDown = false;
        return super.keyReleased(key, sc, mod);
    }

    @Override public boolean charTyped(char ch, int mod) {
        if (sizeDialogOpen && (sizeWField.isFocused() || sizeHField.isFocused()))
            return (sizeWField.isFocused() ? sizeWField : sizeHField).charTyped(ch, mod);
        if (colorPicker.isVisible() && colorPicker.isHexFocused()) return colorPicker.charTyped(ch, mod);
        return false;
    }

    // ══════════════ 画布尺寸 / canvas size ══════════════

    private void commitSizeFields() {
        int w, h;
        try { w = Integer.parseInt(sizeWField.getValue().trim()); }
        catch (Exception e) { w = node.imageWidth; }
        try { h = Integer.parseInt(sizeHField.getValue().trim()); }
        catch (Exception e) { h = node.imageHeight; }
        w = Math.max(1, Math.min(GraphNode.IMAGE_MAX_SIZE, w));
        h = Math.max(1, Math.min(GraphNode.IMAGE_MAX_SIZE, h));
        sizeWField.setValue(String.valueOf(w));
        sizeHField.setValue(String.valueOf(h));
        applyPixelResize(w, h);
    }

    private void applyPixelResize(int newW, int newH) {
        var be = getBE();
        if (be == null) return;
        int oldW = node.imageWidth, oldH = node.imageHeight;
        if (newW == oldW && newH == oldH) return;
        kernel.pushResizeUndo(node, oldW, oldH);
        GraphNode.resizeImagePixels(node, newW, newH);
        if (node.type == NodeType.IMAGE_SEQUENCE && node.imageSequenceFrames != null
            && frameStrip.frameIndex() >= 0 && frameStrip.frameIndex() < node.imageSequenceFrames.size())
            node.imagePixels = node.imageSequenceFrames.get(frameStrip.frameIndex());
        sendOp(GraphOp.setImageSize(blockPos, -1, node.id, newW, newH, getPlayerUUID()));
        be.getNodeGraph().bumpGeneration();
    }

    /** 定向同步当前帧（SET_IMAGE_PIXELS；帧条 Host 接缝）。 / Targeted current-frame sync (frame-strip Host seam). */
    @Override public void sendFrameSync() {
        var be = getBE();
        if (be == null) return;
        int frameIdx = node.type == NodeType.IMAGE_SEQUENCE ? frameStrip.frameIndex() : 0;
        int[] data = node.imagePixels != null ? node.imagePixels.clone()
            : new int[node.imageWidth * node.imageHeight];
        sendOp(GraphOp.setImagePixels(blockPos, -1, node.id, frameIdx, data, getPlayerUUID()));
    }

    // ══════════════ 撤销/重做 / undo & redo（实现 Host 接口；状态机在 PixelEditorKernel）══════════════

    /** 捕获一次笔划撤销快照（整帧克隆，一次笔划一条；幂等标志属笔划生命周期，随 mouseReleased 复位）。
     *  Capture a stroke undo snapshot (full-frame clone, one per stroke; the idempotence flag belongs
     *  to the stroke lifecycle and resets in mouseReleased). */
    private void captureStrokeUndo() {
        if (strokeUndoCaptured) return;
        kernel.captureStrokeUndo(node.imagePixels);
        strokeUndoCaptured = true;
    }

    /** 像素级撤销（实现 Host.performUndo，供 Host 接口/顶栏按钮调用；无操作时不 bump）。
     *  Pixel-level undo (implements Host.performUndo; a no-op does not bump). */
    @Override public void performUndo() {
        if (!kernel.canUndo()) return;
        frameStrip.setFrameIndex(kernel.performUndo(node, frameStrip.frameIndex()));
        bump();
    }

    /** 像素级重做（实现 Host.performRedo）。 / Pixel-level redo (implements Host.performRedo). */
    @Override public void performRedo() {
        if (!kernel.canRedo()) return;
        frameStrip.setFrameIndex(kernel.performRedo(node, frameStrip.frameIndex()));
        bump();
    }
}
