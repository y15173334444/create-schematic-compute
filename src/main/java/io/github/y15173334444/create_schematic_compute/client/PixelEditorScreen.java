package io.github.y15173334444.create_schematic_compute.client;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.blocks.GraphEditor;
import io.github.y15173334444.create_schematic_compute.blocks.MonitorBlockEntity;
import io.github.y15173334444.create_schematic_compute.blocks.SyncedGraphBlockEntity;
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

import java.util.ArrayDeque;
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
public class PixelEditorScreen extends Screen implements GraphEditor.Host {

    // ── 布局常量 / layout constants (tiled, non-overlapping) ──
    private static final int TOP_H = 30;                       // 顶栏高（容纳两行）/ top bar height (holds two rows)
    private static final int LEFT_W = 30;                      // 左面板宽（PS 式工具列，紧贴左缘）/ left panel width (PS-style tool rail, flush against the left)
    private static final int LEFT_PAD = 6;                     // 面板内边距 / panel padding
    private static final int PAL_W = Math.round(ColorPickerWidget.WIDTH * 0.8f); // 取色器分区宽（紧贴取色器）/ palette band width (hugs the palette)
    private static final float PALETTE_SCALE = 0.8f;       // 常驻右侧取色器缩放 / always-on palette scale
    private static final int FRAME_STRIP_H = 44;               // 序列缩略图条高（紧贴屏幕底部、更紧凑）/ sequence thumbnail-strip height (flush to the bottom, compact)
    private static final int FS_BTN_H = 20;                    // 序列按钮行高（紧邻缩略图条上方、更紧凑）/ sequence button-row height (directly above the thumbnail strip, compact)
    private static final int FS_BTN = 16;                      // 序列按钮高（紧凑）/ sequence button height (compact)
    private static final int THUMB = 36, THUMB_GAP = 6;        // 缩略图高 / 间距（宽按宽高比动态、贴底紧凑）/ thumbnail height & gap (dynamic width, flush bottom)
    private static final float MIN_ZOOM = 0.4f, MAX_ZOOM = 8f;
    private static final int MAX_UNDO = 100;
    /** 临时隐藏左面板的笔刷大小/透明度/当前色，工具栏只放工具（改回 true 即恢复）。/
     *  Temporarily hide the brush-size/opacity/current-color controls in the left panel so the
     *  toolbar shows only the tools; flip back to true to restore them. */
    private static final boolean SHOW_BRUSH_CONTROLS = false;

    // 左面板内部成员 / left-panel inner geometry
    private static final int TOOL_BTN = 22, TOOL_GAP = 4;      // 工具按钮（缩小）/ tool button (compact)
    private static final int BA_SIZE = 16, BA_PITCH = 18;      // 笔刷大小档 / brush-size chip

    // ── 调色板（沿用基线配色）/ palette (baseline colours) ──
    private static final int C_BG = 0xFF2A2822;                // 面板底 / panel bg
    private static final int C_BORDER = 0xFF5A4D3A;            // 面板描边 / panel border
    private static final int C_BTN = 0xFF3A3428;               // 按钮底 / button bg
    private static final int C_HOVER = 0xFF5A4A3A;             // 悬停 / hover
    private static final int C_SEL = 0xFF3A5A2A;               // 选中 / selected
    private static final int C_DEL = 0xFF7A4A3A;               // 关闭/删除危险 / destructive
    private static final int C_TXT_BRIGHT = 0xFFFFFFFF;
    private static final int C_TXT_DIM = 0xFFCCCCCC;
    private static final int C_CANVAS = 0xFF14120E;            // 画布区底 / canvas area bg
    private static final int C_CELL = 0xFF3A3830;              // 单元格描边 / cell outline
    private static final int C_OPACITY = 0xFF8A9A5A;           // 透明度滑杆填充 / opacity fill

    // ── 工具 / tools ──
    private enum Tool { BRUSH, ERASER, FILL, EYEDROPPER, LINE, RECT, HAND }
    /** 工具栏固定顺序（快捷键 1..7 与字母键都按此映射）。/ fixed tool rail order (1..7 & letter keys map by this). */
    private static final Tool[] TOOLS = {Tool.BRUSH, Tool.ERASER, Tool.FILL, Tool.EYEDROPPER, Tool.LINE, Tool.RECT, Tool.HAND};
    private Tool tool = Tool.BRUSH;
    private int brushSize = 1;
    /** 笔刷大小范围（滑块与 [ / ] 快捷键共用）。/ brush-size range (shared by the slider and the [ / ] keys). */
    private static final int BRUSH_MIN = 1, BRUSH_MAX = 32;
    /** 笔刷透明度 0..1（与现有像素 alpha 混合；左面板滑杆调节）/ brush opacity */
    private float brushOpacity = 1f;
    private boolean opacityDragging = false;
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

    // ── 像素撤销/重做（meta：-1=像素数组，N≥0=帧数标记，-2=尺寸标记）/ pixel undo/redo ──
    private final List<int[]> undoStack = new ArrayList<>();
    private final List<Integer> undoMeta = new ArrayList<>();
    private final List<int[]> redoStack = new ArrayList<>();
    private final List<Integer> redoMeta = new ArrayList<>();
    private boolean strokeUndoCaptured = false;

    // ── 取色器 / color picker (docked into the right band; collapsed by default) ──
    private final ColorPickerWidget colorPicker = new ColorPickerWidget();

    // ── 画布尺寸（「尺寸」按钮 + 弹窗）/ canvas size (button + popup) ──
    private final EditBox sizeWField, sizeHField;
    private boolean sizeDialogOpen = false;
    /** 操作指南弹窗是否显示 / operation-guide popup visible */
    private boolean showGuide = false;

    // ── 帧条（IMAGE_SEQUENCE）/ frame strip ──
    private int frameIndex = 0;
    private int frameScroll = 0;
    private boolean frameMenuOpen = false;
    private enum FrameDrag { IDLE, PRESSED, DRAGGING }
    private FrameDrag frameDrag = FrameDrag.IDLE;
    private int frameDragIndex = -1, frameDropIndex = -1;
    private double frameDragStartX = 0;
    private long frameDragPressTime = 0;
    private boolean frameScrollbarDragging = false;
    private double frameSbDragStartX = 0;
    private int frameSbDragStartOff = 0;
    private long lastFrameAutoScroll = 0;

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
            frameIndex = 0;
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

    @Override public NodeGraph getGraph() { MonitorBlockEntity be = getBE(); return be != null ? be.graph : new NodeGraph(); }
    @Override public void saveGraph() { /* 像素编辑器不做整图保存 / no full-graph save here */ }
    @Override public void toggleRunning(boolean start) { /* no-op */ }
    @Override public boolean isRunning() { MonitorBlockEntity be = getBE(); return be != null && be.running; }
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
        if (be != null) be.pendingLocalOps++;
        PacketDistributor.sendToServer(new GraphEditOpPacket(op));
    }

    private MonitorBlockEntity getBE() {
        if (minecraft != null && minecraft.level != null
            && minecraft.level.getBlockEntity(blockPos) instanceof MonitorBlockEntity be) return be;
        return null;
    }

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
    private int paletteLeft() { return width - (colorPicker.isVisible() ? PAL_W : 0); }
    /** 中央列右缘（取色器左缘减内边距）。/ central column right edge. */
    private int centralRight() { return Math.max(centralLeft() + 20, paletteLeft() - LEFT_PAD); }
    private int canvasY() { return TOP_H + LEFT_PAD; }
    /** 序列缩略图条顶部（紧贴屏幕底部，条从这延续到 height）。/ thumbnail strip top (flush to the bottom; the strip spans here..height). */
    private int frameStripY() { return height - FRAME_STRIP_H; }
    /** 序列按钮行顶部（紧挨在缩略图条上方）。/ sequence button-row top (directly above the thumbnail strip). */
    private int frameBtnY() { return frameStripY() - FS_BTN_H; }
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

    /** 序列缩略图条矩形（紧贴屏幕底部、横跨左工具列与右调色板之间）：[x, y, w, h]。/
     *  sequence thumbnail-strip rect (flush to the bottom, spans between the left rail & the palette). */
    private int[] frameStripRect() {
        return new int[]{LEFT_W, frameStripY(), paletteLeft() - LEFT_W, FRAME_STRIP_H};
    }

    /** 序列按钮行矩形（紧邻缩略图条上方、同样横跨）：[x, y, w, h]。/
     *  sequence button-row rect (directly above the strip, same full span). */
    private int[] frameBtnRect() {
        return new int[]{LEFT_W, frameBtnY(), paletteLeft() - LEFT_W, FS_BTN_H};
    }

    /** 帧条缩略图起始 x（紧贴左工具列右缘）。/ first-thumbnail x (right beside the left rail). */
    private int thumbStartX() { return LEFT_W + 8; }
    /** 帧条右缘（紧贴右调色板左缘）。/ frame strip right edge (right beside the palette). */
    private int frameStripRight() { return paletteLeft(); }

    /** 缩略图动态宽度：高度固定 THUMB，宽度随图像宽高比（上下不变、左右动态）。/
     *  thumbnail dynamic width (fixed THUMB height, width follows the image aspect). */
    private int thumbW() {
        return Math.max(2, Math.round(THUMB * (float)Math.max(1, node.imageWidth) / Math.max(1, node.imageHeight)));
    }
    /** 缩略图步进（动态宽 + 间距）。/ thumbnail pitch (dynamic width + gap). */
    private int thumbPitch() { return thumbW() + THUMB_GAP; }

    private int maxVisibleThumbs() {
        int avail = frameStripRight() - thumbStartX();
        return Math.max(1, avail / thumbPitch());
    }

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

    // ── 左面板内部几何 / left-panel inner geometry ──
    private int toolsGridTop() { return TOP_H + 8; }
    /** 工具按钮坐标（单列、整列紧贴左缘与右缘）：[x, y, w, h]。/
     *  tool cell (single column, flush against the left edge and stopping just left of the divider). */
    private int[] toolCell(int i) {
        int x = 0;
        int y = toolsGridTop() + i * (TOOL_BTN + TOOL_GAP);
        return new int[]{x, y, LEFT_W - 1, TOOL_BTN};
    }
    private int brushSectionY() { return toolsGridTop() + TOOLS.length * (TOOL_BTN + TOOL_GAP) + 8; }
    private int brushBtnY() { return brushSectionY() + 18; }
    private int opacityLabelY() { return brushSectionY() + 40; }
    private int opacitySliderY() { return opacityLabelY() + 16; }
    private int previewY() { return opacitySliderY() + 26; }
    private int[] opacitySliderGeom() {
        int x = LEFT_PAD + 2;
        int w = LEFT_W - 2 * LEFT_PAD - 6;
        return new int[]{x, opacitySliderY(), w, 6};
    }

    private void setOpacityFromX(double mx, int[] os) {
        brushOpacity = Math.max(0f, Math.min(1f, (float)((mx - os[0]) / os[2])));
    }

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
        renderLeftPanel(g, mx, my);
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
            renderFrameStrip(g, mx, my);
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
        g.fill(x, y, x + w, y + 18, 0xFF4A3F28);
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
        if (isSeq()) fb.append("  F").append(frameIndex + 1).append("/").append(node.imageSequenceFrames.size());
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

    /** 左面板：PS 式单列工具列（`SHOW_BRUSH_CONTROLS` 为 false 时只放工具，隐藏笔刷大小/透明度/当前色）。
     *  Left panel: PS-style single-column tool rail (hides brush-size/opacity/current-color when
     *  `SHOW_BRUSH_CONTROLS` is false, leaving only the tools).
     *  工具主体都落在 x∈[0,LEFT_W] 内；唯一例外是悬停时在其右侧浮出的提示框（近深度，遮住画布）。
     *  The tools themselves stay within x∈[0,LEFT_W]; the only exception is the floating tooltip
     *  shown to the right on hover (near depth, so it occludes the canvas beneath). */
    private void renderLeftPanel(GuiGraphics g, int mx, int my) {
        g.fill(0, TOP_H, LEFT_W, height, C_BG);
        // 分界线从顶栏底部（TOP_H）贯通到屏幕底部，让左侧一整列、且不再穿过顶栏横线。
        // the divider runs from the top bar's bottom (TOP_H) to the screen bottom so the rail reads
        // as one full-height column without crossing the top bar's horizontal line.
        g.fill(LEFT_W - 1, TOP_H, LEFT_W, height, C_BORDER);
        var f = Minecraft.getInstance().font;
        // ── 工具列 / tool rail ──
        Tool[] order = TOOLS;
        for (int i = 0; i < order.length; i++) {
            int[] c = toolCell(i);
            boolean hov = hit(mx, my, c[0], c[1], c[2], c[3]);
            boolean sel = tool == order[i];
            // PS 式：默认无单独按钮填充/边框，仅悬停或选中时高亮整块矩形。
            // PS style: no per-cell fill or border by default; only hover/selected highlight the whole cell.
            if (sel || hov)
                g.fill(c[0], c[1], c[0] + c[2], c[1] + c[3], sel ? C_SEL : C_HOVER);
            int icX = (LEFT_W - 12) / 2;                // 图标在整列宽内居中 / centre icon in rail width
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
            g.drawString(f, "§7" + I18n.get("gui.create_schematic_compute.monitor.pixel_brush_size") + ":", LEFT_PAD + 2, brushSectionY(), C_TXT_DIM, false);
            for (int s = 1; s <= 5; s++) {
                int bx = LEFT_PAD + 2 + (s - 1) * BA_PITCH, by = brushBtnY();
                boolean hov = hit(mx, my, bx, by, BA_SIZE, BA_SIZE);
                g.fill(bx, by, bx + BA_SIZE, by + BA_SIZE, s == brushSize ? C_SEL : hov ? C_HOVER : C_BTN);
                g.renderOutline(bx, by, BA_SIZE, BA_SIZE, C_BORDER);
                int d = Math.min(12, s * 2 + 1);
                g.fill(bx + BA_SIZE / 2 - d / 2, by + BA_SIZE / 2 - d / 2, bx + BA_SIZE / 2 - d / 2 + d, by + BA_SIZE / 2 - d / 2 + d, C_TXT_DIM);
            }
            // ── 透明度滑杆 / opacity slider ──
            g.drawString(f, "§7" + I18n.get("gui.create_schematic_compute.monitor.pixel_opacity") + ":", LEFT_PAD + 2, opacityLabelY(), C_TXT_DIM, false);
            int[] os = opacitySliderGeom();
            g.fill(os[0], os[1], os[0] + os[2], os[1] + os[3], C_CANVAS);
            g.renderOutline(os[0], os[1], os[2], os[3], C_BORDER);
            g.fill(os[0], os[1], os[0] + (int)(os[2] * brushOpacity), os[1] + os[3], C_OPACITY);
            int thumbX = os[0] + (int)(os[2] * brushOpacity) - 3;
            g.fill(thumbX, os[1] - 2, thumbX + 6, os[1] + os[3] + 2, C_TXT_DIM);
            g.drawString(f, "§7" + Math.round(brushOpacity * 100) + "%", LEFT_PAD + 2, opacitySliderY() + 9, C_TXT_DIM, false);
            // ── 当前色 + 笔刷预览 / current color + brush preview ──
            g.drawString(f, "§7" + I18n.get("gui.create_schematic_compute.monitor.pixel_current_color") + ":", LEFT_PAD + 2, previewY(), C_TXT_DIM, false);
            int sw = 26;
            g.fill(LEFT_PAD + 2, previewY() + 12, LEFT_PAD + 2 + sw, previewY() + 12 + sw, selectedColor);
            g.renderOutline(LEFT_PAD + 2, previewY() + 12, sw, sw, C_BORDER);
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

    // ── 帧条 / frame strip ──
    private void renderFrameStrip(GuiGraphics g, int mx, int my) {
        int[] br = frameBtnRect();                    // 按钮行 / button row
        int[] sr = frameStripRect();                  // 缩略图条 / thumbnail strip
        int fl = br[0], frr = br[0] + br[2];
        int btnY = br[1];
        int fy = sr[1];
        // 整个序列区背景（按钮行 + 缩略图条），底部紧贴屏幕底 / whole frame-area bg (button row + strip), flush to the bottom.
        g.fill(fl, btnY, frr, height, C_BG);
        // 按钮行顶部与画布的分隔线；按钮行与缩略图条之间的分隔线 / border above the row and between the row & strip.
        g.fill(fl, btnY, frr, btnY + 1, C_BORDER);
        g.fill(fl, fy, frr, fy + 1, C_BORDER);
        var f = Minecraft.getInstance().font;
        List<int[]> frames = node.imageSequenceFrames;
        if (frames == null || frames.isEmpty()) return;
        int n = frames.size();
        int by = btnY + (FS_BTN_H - FS_BTN) / 2;      // 按钮行内垂直居中 / centred in the row
        // 导航 / nav (relative to fl)
        boolean pH = hit(mx, my, fl + 8, by, 18, FS_BTN);
        g.fill(fl + 8, by, fl + 26, by + FS_BTN, pH ? C_HOVER : C_BTN);
        g.renderOutline(fl + 8, by, 18, FS_BTN, C_BORDER);
        g.drawString(f, "§7◀", fl + 13, by + 3, C_TXT_DIM, false);
        boolean nH = hit(mx, my, fl + 30, by, 18, FS_BTN);
        g.fill(fl + 30, by, fl + 48, by + FS_BTN, nH ? C_HOVER : C_BTN);
        g.renderOutline(fl + 30, by, 18, FS_BTN, C_BORDER);
        g.drawString(f, "§7▶", fl + 35, by + 3, C_TXT_DIM, false);
        g.drawString(f, "§7" + (frameIndex + 1) + "/" + n, fl + 54, by + 3, C_TXT_DIM, false);
        // 新建 / +New
        boolean newH = hit(mx, my, fl + 94, by, 44, FS_BTN);
        g.fill(fl + 94, by, fl + 138, by + FS_BTN, newH ? C_HOVER : C_BTN);
        g.renderOutline(fl + 94, by, 44, FS_BTN, C_BORDER);
        g.drawString(f, "§a" + I18n.get("gui.create_schematic_compute.monitor.pixel_new"), fl + 100, by + 3, C_TXT_DIM, false);
        // 删除 / delete
        boolean delH = hit(mx, my, fl + 146, by, 48, FS_BTN);
        g.fill(fl + 146, by, fl + 194, by + FS_BTN, delH ? C_DEL : C_BTN);
        g.renderOutline(fl + 146, by, 48, FS_BTN, 0xFF8A5A4A);
        g.drawString(f, "§c" + I18n.get("gui.create_schematic_compute.monitor.pixel_delete"), fl + 150, by + 3, C_TXT_DIM, false);
        // 缩略图 / thumbnails（高度固定、宽度随宽高比动态；去边框，选中帧用底色高亮）
        int tx = thumbStartX();
        int tw = thumbW();
        int pitch = thumbPitch();
        int maxScroll = Math.max(0, n - maxVisibleThumbs());
        int scroll = Math.max(0, Math.min(maxScroll, frameScroll));
        frameScroll = scroll;
        for (int i = 0; i < n; i++) {
            int x = tx + i * pitch - scroll * pitch;
            if (x + tw < fl || x > frr) continue;
            int y = fy + 2;
            if (i == frameIndex) g.fill(x - 1, y - 1, x + tw + 1, y + THUMB + 1, 0xFF3A5A2A);
            else if (frameDrag == FrameDrag.DRAGGING && frameDragIndex == i) g.fill(x - 1, y - 1, x + tw + 1, y + THUMB + 1, 0xFF7A4A3A);
            g.fill(x, y, x + tw, y + THUMB, 0xFF1A1814);
            renderThumb(g, frames.get(i), x, y, tw, THUMB);
        }
        // 拖拽落点指示 / drop indicator
        if (frameDrag == FrameDrag.DRAGGING && frameDropIndex >= 0 && frameDropIndex <= n) {
            int dx = tx + frameDropIndex * pitch - scroll * pitch - 2;
            g.fill(dx, fy + 2, dx + 2, fy + THUMB + 2, 0xFFFFAA44);
        }
        // 滚动条 / scrollbar
        if (maxScroll > 0) {
            int sbW = frr - tx;
            int sbX = tx;
            float sbThumbW = Math.max(18, (float)maxVisibleThumbs() / n * sbW);
            float thumbX = sbX + (float)scroll / maxScroll * (sbW - sbThumbW);
            g.fill(sbX, height - 5, sbX + sbW, height - 3, C_BTN);
            g.fill((int)thumbX, height - 5, (int)(thumbX + sbThumbW), height - 3, 0xFF8A7A5A);
        }
        // 新建菜单（Blank / From current）最后画，浮在缩略图条上方 / draw the "+New" menu last so it floats above the strip.
        if (frameMenuOpen) {
            g.fill(fl + 94, by + 18, fl + 184, by + 40, C_BG);
            g.renderOutline(fl + 94, by + 18, 90, 22, C_BORDER);
            g.drawString(f, "§7" + I18n.get("gui.create_schematic_compute.monitor.pixel_blank"), fl + 100, by + 20, C_TXT_DIM, false);
            g.drawString(f, "§7" + I18n.get("gui.create_schematic_compute.monitor.pixel_from_current"), fl + 100, by + 30, C_TXT_DIM, false);
        }
    }

    private void renderThumb(GuiGraphics g, int[] frame, int x, int y, int w, int h) {
        if (frame == null || frame.length == 0) return;
        int imgW = node.imageWidth, imgH = node.imageHeight;
        if (imgW <= 0 || imgH <= 0) return;
        // 缩放到填满 w×h（保持宽高比），采用像素级整数矩形（支持放大/缩小）/ scale to fill w×h preserving aspect via per-pixel integer rects.
        float cf = Math.min((float)h / imgH, (float)w / imgW);
        float offX = x + (w - imgW * cf) / 2f, offY = y + (h - imgH * cf) / 2f;
        for (int py = 0; py < imgH; py++)
            for (int px = 0; px < imgW; px++) {
                int idx = py * imgW + px;
                int c = (idx < frame.length) ? frame[idx] : 0;
                int x1 = Math.round(offX + px * cf), y1 = Math.round(offY + py * cf);
                int x2 = Math.max(x1 + 1, Math.round(offX + (px + 1) * cf));
                int y2 = Math.max(y1 + 1, Math.round(offY + (py + 1) * cf));
                if ((c & 0xFF000000) == 0) {
                    int ck = ((px + py) & 1) * 0x222222;
                    g.fill(x1, y1, x2, y2, 0xFF333333 + ck);
                } else {
                    g.fill(x1, y1, x2, y2, c);
                }
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
        if (mx < LEFT_W) return handleLeftPanelClick(mx, my, btn);
        if (isSeq() && my >= frameBtnY()) return handleFrameStripClick(mx, my, btn);
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

    private boolean handleLeftPanelClick(double mx, double my, int btn) {
        if (btn != 0) return false;
        Tool[] order = TOOLS;
        for (int i = 0; i < order.length; i++) {
            int[] c = toolCell(i);
            if (hit(mx, my, c[0], c[1], c[2], c[3])) { tool = order[i]; return true; }
        }
        if (SHOW_BRUSH_CONTROLS) {
            for (int s = 1; s <= 5; s++) {
                int bx = LEFT_PAD + 2 + (s - 1) * BA_PITCH, by = brushBtnY();
                if (hit(mx, my, bx, by, BA_SIZE, BA_SIZE)) { brushSize = s; return true; }
            }
            int[] os = opacitySliderGeom();
            if (hit(mx, my, os[0], os[1], os[2], os[3])) { opacityDragging = true; setOpacityFromX(mx, os); return true; }
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

    private boolean handleFrameStripClick(double mx, double my, int btn) {
        int fl = LEFT_W;                                    // 与渲染的按钮行锚点一致（br[0]）/ match the render anchor (br[0])
        int fy = frameStripY();
        int by = frameBtnY() + (FS_BTN_H - FS_BTN) / 2;   // 按钮行内垂直居中 / centred in the row
        List<int[]> frames = node.imageSequenceFrames;
        if (frames == null || frames.isEmpty()) return false;
        int n = frames.size();
        if (btn == 0) {
            if (hit(mx, my, fl + 8, by, 18, FS_BTN)) { switchFrame(frameIndex - 1); return true; }
            if (hit(mx, my, fl + 30, by, 18, FS_BTN)) { switchFrame(frameIndex + 1); return true; }
            if (hit(mx, my, fl + 94, by, 44, FS_BTN)) { frameMenuOpen = !frameMenuOpen; return true; }
            if (frameMenuOpen && mx >= fl + 94 && mx <= fl + 184 && my >= by + 18 && my <= by + 40) {
                boolean blank = my <= by + 28;
                addFrame(blank);
                frameMenuOpen = false;
                return true;
            }
            if (hit(mx, my, fl + 146, by, 48, FS_BTN)) { deleteFrame(); return true; }
            // 缩略图点击 / thumbnail clicks
            int tx = thumbStartX();
            int frr = frameStripRight();
            for (int i = 0; i < n; i++) {
                int x = tx + i * thumbPitch() - frameScroll * thumbPitch();
                if (x + thumbW() < fl || x > frr) continue;
                if (hit(mx, my, x, fy + 2, thumbW(), THUMB)) {
                    if (frameDrag != FrameDrag.DRAGGING) {
                        frameDrag = FrameDrag.PRESSED;
                        frameDragIndex = i;
                        frameDropIndex = i;
                        frameDragStartX = mx;
                        frameDragPressTime = System.currentTimeMillis();
                    }
                    return true;
                }
            }
            // 滚动条 / scrollbar
            int maxScroll = Math.max(0, n - maxVisibleThumbs());
            if (maxScroll > 0 && my >= height - 5 && my <= height - 3) {
                int sbW = frr - tx;
                float sbThumbW = Math.max(18, (float)maxVisibleThumbs() / n * sbW);
                float thumbX = tx + (float)frameScroll / maxScroll * (sbW - sbThumbW);
                if (mx >= thumbX && mx <= thumbX + sbThumbW) {
                    frameScrollbarDragging = true;
                    frameSbDragStartX = mx;
                    frameSbDragStartOff = frameScroll;
                    return true;
                }
            }
        } else if (btn == 1) {
            return false;
        }
        return false;
    }

    private void applyToolClick(int cx, int cy, int btn) {
        // 右键 = 直接擦除（任何工具下）；形状工具忽略右键（RMB reserved for erase; shape tools ignore it）
        boolean erasing = (btn == 1) || (tool == Tool.ERASER && btn == 0);
        switch (tool) {
            case BRUSH, ERASER -> {
                captureStrokeUndo();
                paintBrush(cx, cy, erasing ? 0x00000000 : selectedColor);
                if (!erasing) RecentColors.addRecent(selectedColor);
                paintingStroke = true;
                bump();
            }
            case FILL -> {
                if (btn == 1) return;
                captureStrokeUndo();
                floodFill(cx, cy, erasing ? 0x00000000 : selectedColor);
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

    private void bump() {
        var be = getBE();
        if (be != null) be.graph.bumpGeneration();
    }

    @Override public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) {
        if (showGuide) return true;
        if (colorPicker.isVisible() && colorPicker.contains((int)mx, (int)my))
            return colorPicker.mouseDragged(mx, my, btn, dx, dy);
        if (sizeDialogOpen) {
            if (sizeWField.isFocused()) return sizeWField.mouseDragged(mx, my, btn, dx, dy);
            if (sizeHField.isFocused()) return sizeHField.mouseDragged(mx, my, btn, dx, dy);
        }
        if (opacityDragging) {
            setOpacityFromX(mx, opacitySliderGeom());
            return true;
        }
        if (brushSizeDragging) {
            setBrushSizeFromX(mx);
            return true;
        }
        if (frameScrollbarDragging) {
            int n = node.imageSequenceFrames.size();
            int maxScroll = Math.max(0, n - maxVisibleThumbs());
            int tx = thumbStartX();
            int sbW = frameStripRight() - tx;
            float sbThumbW = Math.max(18, (float)maxVisibleThumbs() / n * sbW);
            float delta = (float)(mx - frameSbDragStartX) / (sbW - sbThumbW);
            frameScroll = Math.max(0, Math.min(maxScroll, frameSbDragStartOff + Math.round(delta * maxScroll)));
            return true;
        }
        if (panning) {
            panX += (float)dx;
            panY += (float)dy;
            return true;
        }
        if (isSeq() && frameDrag == FrameDrag.PRESSED) {
            if (Math.abs(mx - frameDragStartX) > 5 || System.currentTimeMillis() - frameDragPressTime > 200) {
                frameDrag = FrameDrag.DRAGGING;
            }
        }
        if (isSeq() && frameDrag == FrameDrag.DRAGGING && frameDragIndex >= 0) {
            updateFrameDropIndex(mx);
            frameAutoScroll(mx);
            return true;
        }
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
                paintBrush(c[0], c[1], erasing ? 0x00000000 : selectedColor);
                if (!erasing) RecentColors.addRecent(selectedColor);
                bump();
            }
            return true;
        }
        return super.mouseDragged(mx, my, btn, dx, dy);
    }

    @Override public void mouseMoved(double mx, double my) {
        if (panning || frameDrag == FrameDrag.DRAGGING) return;
        if (paintingStroke && (tool == Tool.BRUSH || tool == Tool.ERASER)) {
            int[] c = cellAt(mx, my);
            if (c[0] >= 0) {
                boolean erasing = tool == Tool.ERASER;
                captureStrokeUndo();
                paintBrush(c[0], c[1], erasing ? 0x00000000 : selectedColor);
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
        if (opacityDragging) { opacityDragging = false; return true; }
        if (brushSizeDragging) { brushSizeDragging = false; return true; }
        if (frameScrollbarDragging) { frameScrollbarDragging = false; return true; }
        if (panning) { panning = false; return true; }
        if (frameDrag == FrameDrag.DRAGGING && frameDragIndex >= 0) {
            applyFrameReorder();
            resetFrameDrag();
            return true;
        }
        if (frameDrag == FrameDrag.PRESSED) {
            switchFrame(frameDragIndex);
            resetFrameDrag();
        }
        if (shapeInProgress && (tool == Tool.LINE || tool == Tool.RECT)) {
            captureStrokeUndo();
            int color = selectedColor;
            if (tool == Tool.LINE) drawLineCells(shapeStartX, shapeStartY, shapeCurX, shapeCurY, color);
            else drawRectCells(shapeStartX, shapeStartY, shapeCurX, shapeCurY, color);
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
        if (isSeq() && my >= frameBtnY()) {
            int n = node.imageSequenceFrames.size();
            int maxScroll = Math.max(0, n - maxVisibleThumbs());
            frameScroll = Math.max(0, Math.min(maxScroll, frameScroll + (sy > 0 ? -1 : 1)));
            return true;
        }
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
        if (key >= 49 && key <= 55) { tool = TOOLS[key - 49]; return true; }
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

    // ══════════════ 绘制工具 / painting tools ══════════════

    private void paintBrush(int cx, int cy, int color) {
        int[] px = node.imagePixels;
        if (px == null) return;
        int imgW = node.imageWidth, imgH = node.imageHeight;
        int half = brushSize / 2;
        boolean full = brushOpacity >= 0.999f;
        for (int dy = -half; dy < brushSize - half; dy++)
            for (int dx = -half; dx < brushSize - half; dx++) {
                int x = cx + dx, y = cy + dy;
                if (x >= 0 && x < imgW && y >= 0 && y < imgH) {
                    int idx = y * imgW + x;
                    px[idx] = full ? color : blendAlpha(px[idx], color, brushOpacity);
                }
            }
    }

    /** alpha-over 混合：out = src·o + dst·(1−o)，各通道含 alpha（透明度滑杆用）。
     *  Alpha-over blend per channel (incl. alpha) for the opacity slider. */
    private static int blendAlpha(int dst, int src, float o) {
        int sa = (src >>> 24) & 0xFF, sr = (src >>> 16) & 0xFF, sg = (src >>> 8) & 0xFF, sb = src & 0xFF;
        int da = (dst >>> 24) & 0xFF, dr = (dst >>> 16) & 0xFF, dg = (dst >>> 8) & 0xFF, db = dst & 0xFF;
        int a = Math.round(sa * o + da * (1 - o));
        int r = Math.round(sr * o + dr * (1 - o));
        int g = Math.round(sg * o + dg * (1 - o));
        int b = Math.round(sb * o + db * (1 - o));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private void floodFill(int sx, int sy, int color) {
        int[] px = node.imagePixels;
        if (px == null) return;
        int imgW = node.imageWidth, imgH = node.imageHeight;
        int target = px[sy * imgW + sx];
        if (target == color) return;
        boolean full = brushOpacity >= 0.999f;
        // 透明度过小（含 0）时混合结果可能舍入回原色：填充无视觉变化且会无限入栈，直接跳过
        // Tiny/zero opacity can round the blend back to the target color: the fill would be a
        // no-op yet keep re-pushing neighbours forever — bail out instead.
        if (!full && blendAlpha(target, color, brushOpacity) == target) return;
        ArrayDeque<int[]> stack = new ArrayDeque<>();
        stack.push(new int[]{sx, sy});
        while (!stack.isEmpty()) {
            int[] p = stack.pop();
            int x = p[0], y = p[1];
            if (x < 0 || x >= imgW || y < 0 || y >= imgH) continue;
            int idx = y * imgW + x;
            if (px[idx] != target) continue;
            px[idx] = full ? color : blendAlpha(px[idx], color, brushOpacity);
            stack.push(new int[]{x + 1, y});
            stack.push(new int[]{x - 1, y});
            stack.push(new int[]{x, y + 1});
            stack.push(new int[]{x, y - 1});
        }
    }

    private void drawLineCells(int x0, int y0, int x1, int y1, int color) {
        int dx = Math.abs(x1 - x0), dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1;
        int err = dx - dy;
        while (true) {
            paintBrush(x0, y0, color);
            if (x0 == x1 && y0 == y1) break;
            int e2 = 2 * err;
            if (e2 > -dy) { err -= dy; x0 += sx; }
            if (e2 < dx) { err += dx; y0 += sy; }
        }
    }

    private void drawRectCells(int x0, int y0, int x1, int y1, int color) {
        int minX = Math.min(x0, x1), maxX = Math.max(x0, x1);
        int minY = Math.min(y0, y1), maxY = Math.max(y0, y1);
        for (int x = minX; x <= maxX; x++) { paintBrush(x, minY, color); paintBrush(x, maxY, color); }
        for (int y = minY; y <= maxY; y++) { paintBrush(minX, y, color); paintBrush(maxX, y, color); }
    }

    // ══════════════ 帧操作 / frame ops ══════════════

    private void switchFrame(int newIndex) {
        List<int[]> frames = node.imageSequenceFrames;
        if (frames == null || frames.isEmpty()) return;
        if (newIndex < 0 || newIndex >= frames.size()) return;
        sendFrameSync();
        frameIndex = newIndex;
        node.imagePixels = frames.get(frameIndex);
        int maxScroll = Math.max(0, frames.size() - maxVisibleThumbs());
        if (frameIndex < frameScroll) frameScroll = frameIndex;
        else if (frameIndex > frameScroll + maxVisibleThumbs() - 1) frameScroll = frameIndex - maxVisibleThumbs() + 1;
        frameScroll = Math.max(0, Math.min(maxScroll, frameScroll));
        frameMenuOpen = false;
    }

    private void addFrame(boolean blank) {
        List<int[]> frames = ensureFrames();
        pushFramesUndo();
        int[] f;
        if (blank) {
            f = new int[node.imageWidth * node.imageHeight];
            java.util.Arrays.fill(f, 0x00000000);
        } else {
            f = node.imagePixels.clone();
        }
        frames.add(f);
        frameIndex = frames.size() - 1;
        node.imagePixels = f;
        frameMenuOpen = false;
        sendFrameSync();
        bump();
    }

    private void deleteFrame() {
        List<int[]> frames = ensureFrames();
        if (frames.isEmpty()) return;
        pushFramesUndo();
        int removed = frameIndex;
        if (frames.size() > 1) {
            frames.remove(removed);
        } else {
            int[] blank = new int[node.imageWidth * node.imageHeight];
            java.util.Arrays.fill(blank, 0x00000000);
            frames.set(0, blank);
        }
        if (frameIndex >= frames.size()) frameIndex = frames.size() - 1;
        node.imagePixels = frames.get(Math.max(0, Math.min(frameIndex, frames.size() - 1)));
        sendOp(GraphOp.removeImageFrame(blockPos, -1, node.id, removed, getPlayerUUID()));
        bump();
    }

    private void updateFrameDropIndex(double mx) {
        List<int[]> frames = node.imageSequenceFrames;
        if (frames == null || frames.isEmpty()) return;
        int tx = thumbStartX();
        int target = (int)Math.floor((mx - tx + frameScroll * thumbPitch()) / thumbPitch());
        target = Math.max(0, Math.min(frames.size(), target));
        frameDropIndex = target;
    }

    private void frameAutoScroll(double mx) {
        List<int[]> frames = node.imageSequenceFrames;
        if (frames == null) return;
        int maxScroll = Math.max(0, frames.size() - maxVisibleThumbs());
        long now = System.currentTimeMillis();
        if (now - lastFrameAutoScroll < 100) return;
        if (mx < thumbStartX() + thumbW() + 4 && frameScroll > 0) {
            frameScroll = Math.max(0, frameScroll - 1);
            lastFrameAutoScroll = now;
        } else if (mx > frameStripRight() - thumbW() - 20 && frameScroll < maxScroll) {
            frameScroll = Math.min(maxScroll, frameScroll + 1);
            lastFrameAutoScroll = now;
        }
    }

    private void applyFrameReorder() {
        List<int[]> frames = node.imageSequenceFrames;
        if (frames == null || frameDragIndex < 0) return;
        int from = frameDragIndex;
        int to = frameDropIndex;
        if (to > from) to--;
        if (from == to) return;
        pushFramesUndo();
        int[] f = frames.remove(from);
        if (to < 0) to = 0;
        if (to > frames.size()) to = frames.size();
        frames.add(to, f);
        frameIndex = to;
        node.imagePixels = frames.get(to);
        sendOp(GraphOp.moveImageFrame(blockPos, -1, node.id, from, to, getPlayerUUID()));
        bump();
    }

    private void resetFrameDrag() {
        frameDrag = FrameDrag.IDLE;
        frameDragIndex = -1;
        frameDropIndex = -1;
        frameDragStartX = 0;
        frameDragPressTime = 0;
    }

    private List<int[]> ensureFrames() {
        if (node.imageSequenceFrames == null) {
            node.imageSequenceFrames = new ArrayList<>();
            int[] f = new int[node.imageWidth * node.imageHeight];
            java.util.Arrays.fill(f, 0x00000000);
            node.imageSequenceFrames.add(f);
        }
        return node.imageSequenceFrames;
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
        pushResizeUndo(oldW, oldH);
        GraphNode.resizeImagePixels(node, newW, newH);
        if (node.type == NodeType.IMAGE_SEQUENCE && node.imageSequenceFrames != null
            && frameIndex >= 0 && frameIndex < node.imageSequenceFrames.size())
            node.imagePixels = node.imageSequenceFrames.get(frameIndex);
        sendOp(GraphOp.setImageSize(blockPos, -1, node.id, newW, newH, getPlayerUUID()));
        be.graph.bumpGeneration();
    }

    /** 定向同步当前帧（SET_IMAGE_PIXELS）。 / Targeted current-frame sync. */
    private void sendFrameSync() {
        var be = getBE();
        if (be == null) return;
        int frameIdx = node.type == NodeType.IMAGE_SEQUENCE ? frameIndex : 0;
        int[] data = node.imagePixels != null ? node.imagePixels.clone()
            : new int[node.imageWidth * node.imageHeight];
        sendOp(GraphOp.setImagePixels(blockPos, -1, node.id, frameIdx, data, getPlayerUUID()));
    }

    // ══════════════ 撤销/重做 / undo & redo ══════════════

    /** 捕获一次笔划撤销快照（整帧克隆，一次笔划一条）。 / Capture a stroke undo snapshot. */
    private void captureStrokeUndo() {
        if (strokeUndoCaptured) return;
        if (undoStack.size() < MAX_UNDO) {
            undoStack.add(node.imagePixels.clone());
            undoMeta.add(-1);
            redoStack.clear();
            redoMeta.clear();
        }
        strokeUndoCaptured = true;
    }

    /** 帧数变更（新建/删除/重排）的撤销快照：全部帧 + 帧数标记。 / Frames-list undo snapshot. */
    private void pushFramesUndo() {
        List<int[]> frames = ensureFrames();
        int n = frames.size();
        if (undoStack.size() + n + 1 > MAX_UNDO) return;
        for (int i = n - 1; i >= 0; i--) {
            undoStack.add(frames.get(i).clone());
            undoMeta.add(-1);
        }
        undoStack.add(new int[]{n});
        undoMeta.add(n);
        redoStack.clear();
        redoMeta.clear();
    }

    /** 尺寸变更撤销快照（meta=-2 标记 {oldW,oldH} + 全部帧）。 / Resize undo snapshot. */
    private void pushResizeUndo(int oldW, int oldH) {
        int count = 1;
        if (node.type == NodeType.IMAGE_SEQUENCE && node.imageSequenceFrames != null) {
            count = node.imageSequenceFrames.size();
        } else if (node.imagePixels == null) {
            count = 0;
        }
        if (undoStack.size() + count + 1 > MAX_UNDO) return;
        for (int i = count - 1; i >= 0; i--) {
            int[] f = node.type == NodeType.IMAGE_SEQUENCE
                ? node.imageSequenceFrames.get(i) : node.imagePixels;
            undoStack.add(f.clone());
            undoMeta.add(-1);
        }
        undoStack.add(new int[]{oldW, oldH});
        undoMeta.add(-2);
        redoStack.clear();
        redoMeta.clear();
    }

    /** 像素级撤销（实现 Host.performUndo，供 Host 接口/顶栏按钮调用）。
     *  Pixel-level undo (implements Host.performUndo). */
    @Override public void performUndo() {
        if (undoStack.isEmpty()) return;
        int[] top = undoStack.remove(undoStack.size() - 1);
        int meta = undoMeta.remove(undoMeta.size() - 1);
        if (meta >= 0) {
            int count = meta;
            int curCount = node.imageSequenceFrames != null ? node.imageSequenceFrames.size() : 0;
            for (int i = curCount - 1; i >= 0; i--) {
                redoStack.add(node.imageSequenceFrames.get(i).clone());
                redoMeta.add(-1);
            }
            redoStack.add(new int[]{curCount});
            redoMeta.add(curCount);
            List<int[]> frames = ensureFrames();
            frames.clear();
            for (int i = 0; i < count; i++) {
                frames.add(0, undoStack.remove(undoStack.size() - 1));
                undoMeta.remove(undoMeta.size() - 1);
            }
            if (frameIndex >= frames.size()) frameIndex = frames.size() - 1;
            if (frameIndex >= 0 && !frames.isEmpty()) node.imagePixels = frames.get(frameIndex);
        } else if (meta == -2) {
            applyResizeUndoRedo(top, redoStack, redoMeta, undoStack, undoMeta);
        } else {
            redoStack.add(node.imagePixels.clone());
            redoMeta.add(-1);
            node.imagePixels = top;
            if (node.type == NodeType.IMAGE_SEQUENCE && node.imageSequenceFrames != null
                && frameIndex >= 0 && frameIndex < node.imageSequenceFrames.size()) {
                node.imageSequenceFrames.set(frameIndex, top);
            }
        }
        bump();
    }

    /** 像素级重做（实现 Host.performRedo）。 / Pixel-level redo (implements Host.performRedo). */
    @Override public void performRedo() {
        if (redoStack.isEmpty()) return;
        int[] top = redoStack.remove(redoStack.size() - 1);
        int meta = redoMeta.remove(redoMeta.size() - 1);
        if (meta >= 0) {
            int count = meta;
            int curCount = node.imageSequenceFrames != null ? node.imageSequenceFrames.size() : 0;
            for (int i = curCount - 1; i >= 0; i--) {
                undoStack.add(node.imageSequenceFrames.get(i).clone());
                undoMeta.add(-1);
            }
            undoStack.add(new int[]{curCount});
            undoMeta.add(curCount);
            List<int[]> frames = ensureFrames();
            frames.clear();
            for (int i = 0; i < count; i++) {
                frames.add(0, redoStack.remove(redoStack.size() - 1));
                redoMeta.remove(redoMeta.size() - 1);
            }
            if (frameIndex >= frames.size()) frameIndex = frames.size() - 1;
            if (frameIndex >= 0 && !frames.isEmpty()) node.imagePixels = frames.get(frameIndex);
        } else if (meta == -2) {
            applyResizeUndoRedo(top, undoStack, undoMeta, redoStack, redoMeta);
        } else {
            undoStack.add(node.imagePixels.clone());
            undoMeta.add(-1);
            node.imagePixels = top;
            if (node.type == NodeType.IMAGE_SEQUENCE && node.imageSequenceFrames != null
                && frameIndex >= 0 && frameIndex < node.imageSequenceFrames.size()) {
                node.imageSequenceFrames.set(frameIndex, node.imagePixels);
            }
        }
        bump();
    }

    /** 尺寸标记（meta=-2）恢复：当前状态存对侧栈，从本侧栈恢复旧尺寸与全部帧。
     *  Resize marker restore: save current state to the opposite stack, restore old size + frames. */
    private void applyResizeUndoRedo(int[] sizeMarker,
                                     List<int[]> saveToStack, List<Integer> saveToMeta,
                                     List<int[]> popFromStack, List<Integer> popFromMeta) {
        int count = 1;
        if (node.type == NodeType.IMAGE_SEQUENCE && node.imageSequenceFrames != null) {
            count = node.imageSequenceFrames.size();
        } else if (node.imagePixels == null) {
            count = 0;
        }
        int curW = node.imageWidth, curH = node.imageHeight;
        for (int i = count - 1; i >= 0; i--) {
            int[] f = node.type == NodeType.IMAGE_SEQUENCE
                ? node.imageSequenceFrames.get(i) : node.imagePixels;
            saveToStack.add(f.clone());
            saveToMeta.add(-1);
        }
        saveToStack.add(new int[]{curW, curH});
        saveToMeta.add(-2);
        node.imageWidth = sizeMarker[0]; node.imageHeight = sizeMarker[1];
        if (node.type == NodeType.IMAGE_SEQUENCE && node.imageSequenceFrames != null) {
            List<int[]> frames = node.imageSequenceFrames;
            frames.clear();
            for (int i = 0; i < count; i++) {
                frames.add(0, popFromStack.remove(popFromStack.size() - 1));
                popFromMeta.remove(popFromMeta.size() - 1);
            }
            if (frameIndex >= frames.size()) frameIndex = frames.size() - 1;
            if (frameIndex >= 0 && frameIndex < frames.size()) node.imagePixels = frames.get(frameIndex);
        } else if (count > 0) {
            node.imagePixels = popFromStack.remove(popFromStack.size() - 1);
            popFromMeta.remove(popFromMeta.size() - 1);
        }
    }
}
