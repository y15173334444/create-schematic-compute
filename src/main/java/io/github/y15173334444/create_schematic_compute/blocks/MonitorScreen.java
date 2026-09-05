package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.client.GeometryConstants;
import io.github.y15173334444.create_schematic_compute.graph.*;
import io.github.y15173334444.create_schematic_compute.network.BlueprintSavePacket;
import io.github.y15173334444.create_schematic_compute.network.BlueprintTogglePacket;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import com.mojang.math.Axis;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.github.y15173334444.create_schematic_compute.client.GeometryConstants.*;

public class MonitorScreen extends AbstractGraphScreen {

    // ── Display mode state ──
    private boolean displayMode = false;

    // ── Pixel editor transfer (独立 PixelEditorScreen，v1.2.6+) ──
    // 打开像素编辑器时置位：本次 onClose 跳过离开协作会话（像素编辑器屏不 join/leave，
    // 会话保持不断开）；关闭像素编辑器后重建本屏时再正常 join。
    // Set when opening the standalone pixel editor: this onClose skips leaving the collab
    // session (the pixel-editor screen never joins/leaves, so membership must survive);
    // the rebuilt MonitorScreen re-joins (idempotent) and leaves normally afterwards.
    private boolean pixelEditorTransfer = false;

    /** 打开像素编辑器（转移）时跳过离开协作会话；消费一次即复位。
     *  Skip leaving the collab session when transferring to the pixel editor (consumed once). */
    @Override protected boolean skipLeaveOnClose() {
        boolean t = pixelEditorTransfer;
        pixelEditorTransfer = false;
        return t;
    }

    // ── Double-click tracking ──
    private long lastClickTime = 0;
    private int lastClickNodeId = -1;

    // ── Dragging state (display mode) ──
    private GraphNode draggedDisplayNode = null;
    private GraphNode selectedDisplayNode = null;
    private float dragOffX, dragOffY;
    // 显示布局拖拽的实时协作：节流流式发送 SET_DISPLAY_LAYOUT（与节点图 MOVE_NODE 同思路）
    // Live collaboration for display-layout drags: throttle-stream SET_DISPLAY_LAYOUT
    private long lastDisplayDragSendTime = 0;
    private static final long DISPLAY_DRAG_SEND_INTERVAL_MS = 100;
    // 显示模式下最近一次鼠标屏幕坐标（存在包用）
    // Last mouse screen position in display mode (for the presence packet)
    private float lastDisplayMouseX = -1, lastDisplayMouseY = -1;

    // ── Layer panel state ──
    private int layerScroll = 0;

    // ── Layer drag-and-drop state ──
    private enum LayerDragState { IDLE, PRESSED, DRAGGING }
    private LayerDragState layerDragState = LayerDragState.IDLE;
    private GraphNode layerDragNode = null;        // the node being dragged
    private int layerDragOrigIndex = -1;           // original position in full sorted list
    private int layerDropIndex = -1;               // where the drop indicator draws
    private double layerDragStartMy = 0;           // mouse Y when click started
    private long layerDragPressTime = 0;           // system time when click started
    private long lastAutoScrollTime = 0;           // throttle timer for auto-scroll
    private boolean layerScrollbarDragging = false;
    private double layerScrollDragStartY = 0;
    private int layerScrollDragStartOff = 0;

    // ── Display mode inline editing ──
    private boolean editingS = false, editingR = false;
    private String editSBuf = "", editRBuf = "";

    // ── Phase 2: Display area render cache ──
    private int lastDisplayGen = -1;
    private float lastDisplaySW = -1, lastDisplaySL = -1;
    private java.util.List<DisplayElement> cachedDisplayElements = null;
    private DisplayArea cachedDisplayArea = null;

    // ── Settings panel state ──
    private boolean showSettings = false;
    private boolean settingsInited = false;
    private net.minecraft.client.gui.components.EditBox[] settingFields;
    // HUD 模式复选框 + 虚像缩放字段（docs/monitor-mode-settings-merge-plan.md §3.1/§3.4）。
    // HUD 玻璃面板参数已删除——玻璃与 3D 悬浮屏幕共用 screen* 参数。
    // HUD-mode checkbox + virtual-image-scale field (merge-plan §3.1/§3.4). The HUD
    // glass-panel params are gone — the glass shares the 3D screen's screen* params.
    private Checkbox hudModeCheckbox;
    private net.minecraft.client.gui.components.EditBox[] hudSettingFields;
    // Live preview overrides for screen settings (negative = not previewing)
    private float previewScreenW = -1, previewScreenL = -1;
    private static final String[] SETTING_KEYS = {
        "gui.create_schematic_compute.monitor.scr_w",
        "gui.create_schematic_compute.monitor.scr_l",
        "gui.create_schematic_compute.monitor.scr_x",
        "gui.create_schematic_compute.monitor.scr_y",
        "gui.create_schematic_compute.monitor.scr_z",
        "gui.create_schematic_compute.monitor.scr_roll",
        "gui.create_schematic_compute.monitor.scr_pitch",
        "gui.create_schematic_compute.monitor.scr_yaw"
    };
    private static final String[] HUD_SETTING_KEYS = {
        "gui.create_schematic_compute.monitor.vis_scale"
    };

    // ── Fast number formatting to avoid String.format allocation in hot paths (Phase 1) ──
    private static String ff0(float v) { return Integer.toString(Math.round(v)); }
    private static String ff1(float v) { return Float.toString((float)Math.round(v * 10) / 10); }
    private static String ff2(float v) { return Float.toString((float)Math.round(v * 100) / 100); }
    private static String ff3(float v) { return Float.toString((float)Math.round(v * 1000) / 1000); }

    public MonitorScreen(BlockPos pos) {
        super(Component.translatable("container." + SchematicCompute.MOD_ID + ".monitor"), pos);
        // Settings EditBoxes — values loaded when panel opens (settingsInited flag)
        var mc = Minecraft.getInstance();
        settingFields = new net.minecraft.client.gui.components.EditBox[8];
        for (int i = 0; i < 8; i++) {
            settingFields[i] = new net.minecraft.client.gui.components.EditBox(Minecraft.getInstance().font, 0, 0, 60, 14, Component.literal(""));
            settingFields[i].setMaxLength(8);
        }
        // HUD 字段 EditBoxes（仅虚像缩放 1 项；玻璃参数已删，共用 3D screen*）
        // HUD field EditBoxes (only the virtual-image scale; glass params removed —
        // the glass shares the 3D screen* params)
        hudSettingFields = new net.minecraft.client.gui.components.EditBox[1];
        for (int i = 0; i < 1; i++) {
            hudSettingFields[i] = new net.minecraft.client.gui.components.EditBox(Minecraft.getInstance().font, 0, 0, 60, 14, Component.literal(""));
            hudSettingFields[i].setMaxLength(8);
        }
        // node filter: only input and display nodes
        editor.setNodeFilter(nt -> nt == NodeType.CONST
            || nt == NodeType.REDSTONE_IN
            || nt == NodeType.PRIVATE_IN
            || nt == NodeType.BUS_IN
            || nt == NodeType.TEXT || nt == NodeType.DATA
            || nt == NodeType.IMAGE || nt == NodeType.IMAGE_SEQUENCE
            || nt == NodeType.HUD_PITCH_LADDER
            || nt == NodeType.COMMENT
            || nt == NodeType.DEBUG_SIGNAL_GEN
            || nt == NodeType.DEBUG_PROBE);
    }

    @Override protected MonitorBlockEntity getBE() {
        if (minecraft != null && minecraft.level != null) {
            if (minecraft.level.getBlockEntity(blockPos) instanceof MonitorBlockEntity be) return be;
        }
        return null;
    }
    @Override protected boolean isBlockEntityValid() {
        return minecraft != null && minecraft.level != null
            && minecraft.level.getBlockEntity(blockPos) instanceof MonitorBlockEntity;
    }
    // ── GraphEditor.Host ──
    @Override public NodeGraph getGraph() { MonitorBlockEntity be = getBE(); return be != null ? be.getNodeGraph() : new NodeGraph(); }
    @Override public boolean isRunning() { MonitorBlockEntity be = getBE(); return be != null && be.isRunning(); }
    @Override public Map<Integer, Boolean> getFlipflopStates() { MonitorBlockEntity be = getBE(); return be != null ? be.getFlipflopStates() : null; }
    @Override public io.github.y15173334444.create_schematic_compute.graph.EvalSnapshot getCachedEvalSnapshot() {
        MonitorBlockEntity be = getBE();
        return be != null ? be.getCachedEvalSnapshot() : null;
    }
    @Override public Screen asScreen() { return this; }

    @Override
    public void saveGraph() {
        try {
            MonitorBlockEntity be = getBE();
            if (be == null || be.getLevel() == null) return;
            var tag = new CompoundTag();
            tag.put("graph", getGraph().save(be.getLevel().registryAccess()));
            var baos = new ByteArrayOutputStream();
            NbtIo.writeCompressed(tag, baos);
            PacketDistributor.sendToServer(new BlueprintSavePacket(be.getBlockPos(), baos.toByteArray()));
            editor.saveFeedbackUntil = System.currentTimeMillis() + 1500;
        } catch (Exception e) { SchematicCompute.LOGGER.error("Save", e); }
    }

    @Override
    public void toggleRunning(boolean start) {
        MonitorBlockEntity be = getBE();
        if (be != null) { be.setRunning(start); PacketDistributor.sendToServer(new BlueprintTogglePacket(be.getBlockPos(), start)); }
    }

    @Override
    protected void renderGraphCanvas(GuiGraphics g, int mx, int my, float pt) {
        if (displayMode) {
            // 显示模式也持续发送存在包（节点图模式的 renderBg 不会在此运行）
            // Keep presence flowing in display mode (the graph-mode renderBg does not run here)
            editor.sendPresenceIfNeeded();
            renderDisplayArea(g, mx, my);
            renderLayerPanel(g, mx, my);
            // 显示模式绘制显示区协作叠加层（队友光标 + 拖拽描边），不再绘制节点图光标
            // Display mode draws the display-area collaboration overlay (teammate cursors +
            // drag outlines) instead of the node-graph cursor overlay.
            renderDisplayPresence(g);
        } else {
            editor.renderBg(g, mx, my);
            renderDisplayToggleButton(g);
        }
        // Settings panel overlay
        if (showSettings) {
            renderSettingsPanel(g, mx, my);
        }
        // Color picker — always on top of everything
        if (editor.colorPicker.isVisible()) {
            editor.colorPicker.render(g, mx, my);
        }
    }

    /** 显示布局模式的协作叠加层：绘制模式为 display 的队友光标（屏幕坐标 + 名字），
     *  并对队友正在拖拽的元素画彩色描边。元素位置本身通过流式 SET_DISPLAY_LAYOUT 实时同步。
     *  Display-layout collaboration overlay: draws teammates' cursors (mode==display, screen
     *  coords + name) and a colored outline around the element a teammate is dragging. Element
     *  positions themselves sync live via the streamed SET_DISPLAY_LAYOUT ops. */
    private void renderDisplayPresence(GuiGraphics g) {
        editor.cleanupStalePresences();
        var presences = editor.getRemotePresences();
        if (presences.isEmpty()) return;
        var mc = Minecraft.getInstance();
        var graph = getBE() != null ? getBE().getNodeGraph() : null;
        if (graph == null) return;
        var elements = collectDisplayElements(graph, getEvalOutputs());
        for (var p : presences.values()) {
            if (p.mode() != 1) continue; // 仅显示布局模式的临场数据 / display-layout presences only
            if (p.cursorX() < 0 || p.cursorY() < 0) continue; // 离开哨兵 / left sentinel
            int h = p.player().hashCode();
            int color = 0xFF000000 | (((h >> 16) & 0xFF) << 16) | (((h >> 8) & 0xFF) << 8) | (h & 0xFF);
            float sx = p.cursorX(), sy = p.cursorY();
            g.fill((int)sx - 6, (int)sy - 1, (int)sx + 7, (int)sy, color);
            g.fill((int)sx - 1, (int)sy - 6, (int)sx, (int)sy + 7, color);
            g.drawString(mc.font, p.playerName(), (int)sx + 8, (int)sy - 4, color);
            // 队友正在拖拽的元素：彩色描边（软锁视觉）+ 名字标注
            // Element the teammate is dragging: colored outline (soft-lock visual) + name tag
            if (p.displayDraggedNodeId() >= 0) {
                var elem = findInElements(elements, p.displayDraggedNodeId());
                if (elem != null) {
                    var da = computeDisplayArea();
                    var ci = getContentArea(da);
                    float guiScale = da.w * FONT_BLOCK_SCALE / Math.max(getContentWorldW(), 0.01f);
                    float s = guiScale * elem.scale;
                    float ew = (elem.type == NodeType.IMAGE || elem.type == NodeType.IMAGE_SEQUENCE)
                        ? elem.imgW * IMAGE_CELL_FONT
                        : Minecraft.getInstance().font.width(elem.text.isEmpty() ? " " : elem.text);
                    float eh = (elem.type == NodeType.IMAGE || elem.type == NodeType.IMAGE_SEQUENCE)
                        ? elem.imgH * IMAGE_CELL_FONT : 10;
                    float ex = ci[0] + elem.x * ci[2];
                    float ey = ci[1] + elem.y * ci[3];
                    float[] bb = elemRotAABB(ex, ey, ew * s, eh * s, elem.rotation);
                    int dr = ci[0] + ci[2], db = ci[1] + ci[3];
                    if (bb[2] > dr) ex -= (bb[2] - dr);
                    if (bb[3] > db) ey -= (bb[3] - db);
                    if (bb[0] < ci[0]) ex += (ci[0] - bb[0]);
                    if (bb[1] < ci[1]) ey += (ci[1] - bb[1]);
                    var aabb = elemRotAABB(ex, ey, ew * s, eh * s, elem.rotation);
                    g.renderOutline((int)aabb[0] - 1, (int)aabb[1] - 1,
                        (int)(aabb[2] - aabb[0]) + 2, (int)(aabb[3] - aabb[1]) + 2, color);
                    g.drawString(mc.font, "§o" + p.playerName(), (int)aabb[0], (int)aabb[1] - 10, color);
                }
            }
        }
    }

    // ── Display area rendering ──
    private record DisplayArea(int x, int y, int w, int h) {}
    private DisplayArea computeDisplayArea() {
        int margin = MONITOR_MARGIN;
        // 工具条下方留白 + 编辑器顶栏（TOP_BAR_H 落地后显示区必须再让开它）。
        // Toolbar gap + the editor top bar (the display area must clear it too).
        int topOffset = MONITOR_TOOLBAR_H + 6 + GraphEditor.TOP_BAR_H;
        int availW = width - 2 * margin;
        int availH = height - margin - topOffset;
        float aspect = 16f / 9f;
        MonitorBlockEntity mbe = getBE();
        if (mbe != null && mbe.screenLength > 0.001f)
            aspect = mbe.screenWidth / mbe.screenLength;
        int dw, dh;
        if (availW / aspect <= availH) { dw = availW; dh = (int)(availW / aspect); }
        else { dh = availH; dw = (int)(availH * aspect); }
        return new DisplayArea((width - dw) / 2, (height - dh) / 2 + topOffset / 2, dw, dh);
    }

    /** Get effective screen dimensions, using preview overrides when settings panel is open */
    private float getEffectiveScreenW() { return previewScreenW >= 0 ? previewScreenW : (getBE() != null ? getBE().screenWidth : 1.5f); }
    private float getEffectiveScreenL() { return previewScreenL >= 0 ? previewScreenL : (getBE() != null ? getBE().screenLength : 1.2f); }

    /** Compute content area insets matching the 3D renderer's 0.04-block bezel margin.
     *  Returns {contentX, contentY, contentW, contentH} within the given DisplayArea. */
    private int[] getContentArea(DisplayArea da) {
        float sw = getEffectiveScreenW();
        float sl = getEffectiveScreenL();
        float mfX = BEZEL_MARGIN / Math.max(sw, 0.01f);
        float mfY = BEZEL_MARGIN / Math.max(sl, 0.01f);
        int ix = Math.round(da.w * mfX);
        int iy = Math.round(da.h * mfY);
        return new int[]{da.x + ix, da.y + iy, da.w - 2 * ix, da.h - 2 * iy};
    }

    /** Get the world-space content width (screenWidth - 2*margin) for guiScale computation */
    private float getContentWorldW() { return Math.max(getEffectiveScreenW() - (2 * BEZEL_MARGIN), 0.01f); }

    /** Read evaluation outputs from the server-authoritative snapshot (synced via ClientboundGraphEvalPacket). */
    private Map<Integer, float[]> getEvalOutputs() {
        var be = getBE();
        if (be == null) return java.util.Collections.emptyMap();
        return be.getCachedEvalSnapshot().outputs();
    }

    private void renderDisplayArea(GuiGraphics g, int mx, int my) {
        var da = computeDisplayArea();
        int w = width, h = height;

        // Dark background + grid (matching node editor style)
        g.fill(0, 0, w, h, 0xFF1F1E1A);
        // Placement guide grid aligned to the CONTENT (display) area so cells are exact
        // integers and the center is always locatable. 16 divisions = image-native resolution;
        // major lines every 4 cells; bold center cross at division 8/8.
        // 摆放辅助线绑定内容区：16 等分（与图像原生分辨率一致），每 4 格主线，8/8 处中心十字加粗。
        var gi = getContentArea(da);
        final int GDIV = 16;
        for (int gx = 0; gx <= GDIV; gx++) {
            int x = gi[0] + Math.round(gi[2] * (float) gx / GDIV);
            int c = (gx == GDIV / 2) ? 0xFF5A4D3A : (gx % 4 == 0 ? 0xFF3A3A3A : 0xFF2C2A24);
            g.fill(x, gi[1], x + 1, gi[1] + gi[3], c);
        }
        for (int gy = 0; gy <= GDIV; gy++) {
            int y = gi[1] + Math.round(gi[3] * (float) gy / GDIV);
            int c = (gy == GDIV / 2) ? 0xFF5A4D3A : (gy % 4 == 0 ? 0xFF3A3A3A : 0xFF2C2A24);
            g.fill(gi[0], y, gi[0] + gi[2], y + 1, c);
        }
        g.renderOutline(gi[0], gi[1], gi[2], gi[3], 0xFF5A4D3A);

        // Read server-authoritative evaluation outputs (synced via ClientboundGraphEvalPacket)
        var graph = getBE() != null ? getBE().getNodeGraph() : new NodeGraph();
        var evalOutputs = getEvalOutputs();

        // Collect and render display elements (cached when graph is static — Phase 2)
        // When running, output values change each tick so we must rebuild.
        boolean isRunning = getBE() != null && getBE().isRunning();
        float efsw = getEffectiveScreenW(), efsl = getEffectiveScreenL();
        int curGen = graph.graphGeneration;
        boolean displayChanged = curGen != lastDisplayGen || efsw != lastDisplaySW || efsl != lastDisplaySL
            || isRunning || draggedDisplayNode != null;
        if (displayChanged || cachedDisplayElements == null) {
            lastDisplayGen = curGen; lastDisplaySW = efsw; lastDisplaySL = efsl;
            cachedDisplayElements = collectDisplayElements(graph, evalOutputs);
            cachedDisplayArea = da;
        }
        var elements = cachedDisplayElements;
        var daCached = cachedDisplayArea != null ? cachedDisplayArea : da;
        // Dynamic guiScale: match world proportions
        // World: 1 font-pixel = 0.015 blocks. GUI: da.w pixels maps to cw = screenWidth-0.08 blocks.
        // So: guiScale = 0.015 * da.w / cw  (font-px → screen-px matching world scale)
        float cw = getContentWorldW();
        float guiScale = da.w * FONT_BLOCK_SCALE / Math.max(cw, 0.01f);
        var ci = getContentArea(da);
        int contentX = ci[0], contentY = ci[1], contentW = ci[2], contentH = ci[3];
        var mc = Minecraft.getInstance();
        for (var elem : elements) {
            float s = guiScale * elem.scale;

            // Compute element content size in local (unscaled) coords.
            // In the world: 1 IMAGE pixel = 0.03 blocks = 2 font-pixels (since 1 font-px = 0.015 blocks).
            // In the GUI: each IMAGE pixel = 2 font-pixels (cellSize=2), total 32 font-pixels.
            float elemW = (elem.type == NodeType.IMAGE || elem.type == NodeType.IMAGE_SEQUENCE) ? elem.imgW * IMAGE_CELL_FONT
                : elem.type == NodeType.HUD_PITCH_LADDER ? 72
                : Minecraft.getInstance().font.width(elem.text.isEmpty() && elem.type != NodeType.DATA ? " " :
                    elem.type == NodeType.DATA ? ff1(elem.value) : elem.text);
            float elemH = (elem.type == NodeType.IMAGE || elem.type == NodeType.IMAGE_SEQUENCE) ? elem.imgH * IMAGE_CELL_FONT
                : elem.type == NodeType.HUD_PITCH_LADDER ? 48 : 10;
            // Clamp using same rotated-AABB calculation as the yellow selection outline
            float ex = contentX + elem.x * contentW;
            float ey = contentY + elem.y * contentH;
            float ew = elemW * s, eh = elemH * s;
            float[] bb = elemRotAABB(ex, ey, ew, eh, elem.rotation);
            // Clamp to content area (matching 3D renderer's cx/cw bounds), not display area
            float dl = contentX, dr = contentX + contentW, dt = contentY, db = contentY + contentH;
            if (bb[2] > dr) ex -= (bb[2] - dr);
            if (bb[3] > db) ey -= (bb[3] - db);
            if (bb[0] < dl) ex += (dl - bb[0]);
            if (bb[1] < dt) ey += (dt - bb[1]);
            // Center-based rotation: translate to screen center → rotate → translate back → scale
            var pose = g.pose();
            pose.pushPose();
            pose.translate(ex + elemW * s / 2, ey + elemH * s / 2, 0);
            pose.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(elem.rotation));
            pose.scale(s, s, 1);
            pose.translate(-elemW / 2, -elemH / 2, 0);

            switch (elem.type) {
                case TEXT -> {
                    String text = elem.text.isEmpty() ? I18n.get("gui.create_schematic_compute.text_placeholder") : elem.text;
                    g.drawString(Minecraft.getInstance().font, text, 0, 0, elem.color, false);
                }
                case DATA -> {
                    String dataStr = ff1(elem.value);
                    g.drawString(Minecraft.getInstance().font, dataStr, 0, 0, elem.color, false);
                }
                case IMAGE, IMAGE_SEQUENCE -> {
                    if (elem.pixels != null) {
                        renderPixels(g, elem.pixels, 0, 0, 2, elem.imgW, elem.imgH);
                    }
                }
                case HUD_PITCH_LADDER -> {
                    // 固定相机模拟预览：正对面板中心，刻度按 tan(θ) 分布（示意共形俯仰梯）
                    // Fixed-camera mock preview: ticks spread by tan(θ) (conformal ladder sketch)
                    float halfWpx = elemW * 0.5f, halfHpx = elemH * 0.5f;
                    for (int deg = 5; deg <= 45; deg += 5) {
                        float y = (float)(Math.tan(Math.toRadians(deg)) / Math.tan(Math.toRadians(45))) * halfHpx * 0.85f;
                        g.hLine(0, (int)-y, (int)elemW, 0x55FFFFFF); // 上刻度 / upper ticks
                        g.hLine(0, (int)y, (int)elemW, 0x55FFFFFF);  // 下刻度 / lower ticks
                    }
                    g.hLine(0, (int)(halfHpx * 0.02f), (int)elemW, 0xFF88E866); // 地平线：高亮 / horizon: highlight
                    g.fill((int)(elemW / 2 - 3), (int)(halfHpx * 0.02f - 1), (int)(elemW / 2 + 3), (int)(halfHpx * 0.02f + 1), 0xFF33CC66); // 姿态标记 / marker
                }
            }
            pose.popPose();
        }
        // ── Selection highlight: draw AFTER all elements so it's always on top ──
        if (selectedDisplayNode != null) {
            for (var elem : elements) {
                if (selectedDisplayNode.id != elem.nodeId) continue;
                float s = guiScale * elem.scale;
                float elemW = (elem.type == NodeType.IMAGE || elem.type == NodeType.IMAGE_SEQUENCE) ? elem.imgW * IMAGE_CELL_FONT
                    : elem.type == NodeType.HUD_PITCH_LADDER ? 72
                    : Minecraft.getInstance().font.width(elem.text.isEmpty() && elem.type != NodeType.DATA ? " " :
                        elem.type == NodeType.DATA ? ff1(elem.value) : elem.text);
                float elemH = (elem.type == NodeType.IMAGE || elem.type == NodeType.IMAGE_SEQUENCE) ? elem.imgH * IMAGE_CELL_FONT
                    : elem.type == NodeType.HUD_PITCH_LADDER ? 48 : 10;
                float ex = contentX + elem.x * contentW;
                float ey = contentY + elem.y * contentH;
                float ew = elemW * s, eh = elemH * s;
                float[] bb = elemRotAABB(ex, ey, ew, eh, elem.rotation);
                float dl = contentX, dr = contentX + contentW, dt = contentY, db = contentY + contentH;
                if (bb[2] > dr) ex -= (bb[2] - dr);
                if (bb[3] > db) ey -= (bb[3] - db);
                if (bb[0] < dl) ex += (dl - bb[0]);
                if (bb[1] < dt) ey += (dt - bb[1]);
                var pose = g.pose();
                pose.pushPose();
                pose.translate(ex + elemW * s / 2, ey + elemH * s / 2, 0);
                pose.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(elem.rotation));
                pose.scale(s, s, 1);
                pose.translate(-elemW / 2, -elemH / 2, 0);
                int hx = -1, hy = -1, hw = (int)elemW + 2, hh = (int)elemH + 2;
                g.renderOutline(hx, hy, hw, hh, NodeRenderer.ACC());
                pose.popPose();
                break;
            }
        }
        // ── Toolbar at screen top (fixed position) ──
        // 让开编辑器顶栏：与基础工具栏同排（TOP_BAR_H+2），显示模式下整条覆盖基础按钮。
        // Clears the editor top bar: same row as the base toolbar (TOP_BAR_H+2); in
        // display mode this full-width strip covers the base buttons, as before.
        int tbx = 4, tby = GraphEditor.TOP_BAR_H + 2, tbh = MONITOR_TOOLBAR_H;
        g.fill(0, tby, width, tby + tbh, NodeRenderer.PBG());
        // < Graph
        g.fill(tbx, tby, tbx + 56, tby + tbh, 0xFF3A3832);
        g.renderOutline(tbx, tby, 56, tbh, NodeRenderer.CSB());
        g.drawString(Minecraft.getInstance().font, I18n.get("gui.create_schematic_compute.monitor.back_graph"), tbx + 6, tby + 5, 0xFFFFFFFF, false);
        tbx += 62;
        // Settings
        g.fill(tbx, tby, tbx + 56, tby + tbh, showSettings ? 0xFF3A5A2A : 0xFF3A3832);
        g.renderOutline(tbx, tby, 56, tbh, NodeRenderer.CSB());
        g.drawString(Minecraft.getInstance().font, I18n.get("gui.create_schematic_compute.monitor.settings"), tbx + 6, tby + 5, 0xFFFFFFFF, false);
        tbx += 62;

        // Selected element editing (clickable S/R values)
        if (selectedDisplayNode != null) {
            String sTxt = "§6S:";
            if (editingS) sTxt += "§e" + editSBuf + "▌";
            else sTxt += "§e" + ff1(selectedDisplayNode.displayScale);
            g.drawString(Minecraft.getInstance().font, sTxt, tbx + 4, tby + 5, NodeRenderer.ACC(), false);
            tbx += Minecraft.getInstance().font.width(sTxt) + 12;
            String rTxt = "§6R:";
            if (editingR) rTxt += "§e" + editRBuf + "▌";
            else rTxt += "§e" + ff0(selectedDisplayNode.displayRotation);
            g.drawString(Minecraft.getInstance().font, rTxt, tbx + 4, tby + 5, NodeRenderer.ACC(), false);
        }

        // Hover hints (use rotated AABB for accuracy, with bounding-box clamp)
        if (selectedDisplayNode == null) {
            var font2 = Minecraft.getInstance().font;
            for (var elem : elements) {
                float s = guiScale * elem.scale;
                float hitW, hitH;
                if (elem.type == NodeType.IMAGE || elem.type == NodeType.IMAGE_SEQUENCE) {
                    hitW = elem.imgW * IMAGE_CELL_FONT; hitH = elem.imgH * IMAGE_CELL_FONT;
                } else {
                    String displayStr = elem.type == NodeType.DATA
                        ? ff1(elem.value)
                        : (elem.text.isEmpty() ? " " : elem.text);
                    hitW = font2.width(displayStr);
                    hitH = 10;
                }
                float ex = contentX + elem.x * contentW;
                float ey = contentY + elem.y * contentH;
                float ew = hitW * s, eh = hitH * s;
                float[] bb = elemRotAABB(ex, ey, ew, eh, elem.rotation);
                float dl = contentX, dr = contentX + contentW, dt = contentY, db = contentY + contentH;
                if (bb[2] > dr) ex -= (bb[2] - dr);
                if (bb[3] > db) ey -= (bb[3] - db);
                if (bb[0] < dl) ex += (dl - bb[0]);
                if (bb[1] < dt) ey += (dt - bb[1]);
                var aabb = elemRotAABB(ex, ey, hitW * s, hitH * s, elem.rotation);
                if (mx >= aabb[0] && mx <= aabb[2] && my >= aabb[1] && my <= aabb[3]) {
                    g.renderOutline((int)aabb[0] - 1, (int)aabb[1] - 1,
                        (int)(aabb[2] - aabb[0]) + 2, (int)(aabb[3] - aabb[1]) + 2, 0xFF88AA44);
                    break;
                }
            }
        }
    }

    // ── Layer panel ──
    private List<GraphNode> getDisplayLayers(NodeGraph graph) {
        List<GraphNode> layers = new ArrayList<>();
        for (var n : graph.nodes) {
            if (n.type == NodeType.TEXT || n.type == NodeType.DATA
                || n.type == NodeType.IMAGE || n.type == NodeType.IMAGE_SEQUENCE)
                layers.add(n);
        }
        layers.sort((a, b) -> Integer.compare(b.layerIndex, a.layerIndex));
        return layers;
    }

    private void renderLayerThumbnail(GuiGraphics g, GraphNode node, int x, int y, int size) {
        // Dark background
        g.fill(x, y, x + size, y + size, NodeRenderer.PINS());

        switch (node.type) {
            case TEXT -> {
                String preview = node.displayText.isEmpty() ? "T"
                    : node.displayText.substring(0, Math.min(3, node.displayText.length()));
                int tc = node.textColor != 0 ? node.textColor : 0xFFCCCCCC;
                int tw = Minecraft.getInstance().font.width(preview);
                g.drawString(Minecraft.getInstance().font, preview,
                    x + (size - tw) / 2, y + (size - 8) / 2, tc, false);
            }
            case DATA -> {
                var graph = getBE() != null ? getBE().getNodeGraph() : new NodeGraph();
                var evalOutputs2 = getEvalOutputs();
                float val = graph.getInputValue(node.id, 0, evalOutputs2);
                String valStr = ff1(val);
                int dc = node.textColor != 0 ? node.textColor : 0xFF88FF88;
                int tw = Minecraft.getInstance().font.width(valStr);
                g.drawString(Minecraft.getInstance().font, valStr,
                    x + (size - tw) / 2, y + (size - 8) / 2, dc, false);
            }
            case IMAGE -> {
                if (node.imagePixels != null) {
                    int cellSz = 1;
                    int offsetX = x + (size - node.imageWidth * cellSz) / 2;
                    int offsetY = y + (size - node.imageHeight * cellSz) / 2;
                    renderPixels(g, node.imagePixels, offsetX, offsetY, cellSz, node.imageWidth, node.imageHeight);
                }
            }
            case IMAGE_SEQUENCE -> {
                var graph = getBE() != null ? getBE().getNodeGraph() : new NodeGraph();
                var evalOutputs3 = getEvalOutputs();
                int frameIdx = Math.round(graph.getInputValue(node.id, 2, evalOutputs3));
                int[] pixels = null;
                if (node.imageSequenceFrames != null && !node.imageSequenceFrames.isEmpty()) {
                    frameIdx = Math.max(0, Math.min(frameIdx, node.imageSequenceFrames.size() - 1));
                    pixels = node.imageSequenceFrames.get(frameIdx);
                }
                if (pixels != null) {
                    int cellSz = 1;
                    int offsetX = x + (size - node.imageWidth * cellSz) / 2;
                    int offsetY = y + (size - node.imageHeight * cellSz) / 2;
                    renderPixels(g, pixels, offsetX, offsetY, cellSz, node.imageWidth, node.imageHeight);
                }
                // "S" badge at top-right of thumbnail
                int badgeX = x + size - 7;
                int badgeY = y + 1;
                g.fill(badgeX, badgeY, badgeX + 6, badgeY + 6, 0xFF3A3A3A);
                g.renderOutline(badgeX, badgeY, 6, 6, NodeRenderer.CSB());
                g.drawString(Minecraft.getInstance().font, "S", badgeX + 1, badgeY, NodeRenderer.ACC(), false);
            }
            case HUD_PITCH_LADDER -> {
                // 缩略图：简化俯仰梯示意（中心地平线 + 上下刻度线）
                // Thumbnail: simplified ladder sketch (center horizon + ticks above/below)
                int cy = y + size / 2;
                for (int deg = 1; deg <= 3; deg++) {
                    int dy = Math.round(size * 0.22f * deg / 3f);
                    g.hLine(x + 2, cy - dy, x + size - 2, 0x55FFFFFF);
                    g.hLine(x + 2, cy + dy, x + size - 2, 0x55FFFFFF);
                }
                g.hLine(x + 2, cy, x + size - 2, 0xFF88E866);
                g.fill(x + size / 2 - 2, cy - 1, x + size / 2 + 2, cy + 1, 0xFF33CC66);
            }
        }
    }

    private void renderLayerPanel(GuiGraphics g, int mx, int my) {
        var graph = getBE() != null ? getBE().getNodeGraph() : new NodeGraph();
        List<GraphNode> layers = getDisplayLayers(graph);
        if (layers.isEmpty()) return;

        int px = width - LAYER_PANEL_W - LAYER_PANEL_PADDING;
        // 让开编辑器顶栏 + 基础工具栏行（旧硬编码 26 是顶栏落地前的值）。
        // Clears the editor top bar + the base toolbar row (the old hard-coded 26
        // predated the top bar).
        int py = GraphEditor.TOP_BAR_H + 24;
        int titleH = 12;
        int rowStartY = py + titleH + 2;
        // Calculate max visible rows below title
        int availableH = height - rowStartY - 4;
        int maxRows = Math.max(1, availableH / LAYER_ROW_H);
        int visibleRows = Math.min(layers.size(), maxRows);
        int ph = titleH + 2 + visibleRows * LAYER_ROW_H + 4;
        if (layers.size() > maxRows) ph += 2; // scrollbar foot

        // Panel background
        g.fill(px, py, px + LAYER_PANEL_W, py + ph, 0xCC1A1814);
        g.renderOutline(px, py, LAYER_PANEL_W, ph, 0xFF6A6A4A);

        // Title bar
        g.fill(px + 1, py + 1, px + LAYER_PANEL_W - 1, py + titleH + 1, NodeRenderer.PBG());
        String title = "Layers";
        int titleW = Minecraft.getInstance().font.width(title);
        g.drawString(Minecraft.getInstance().font, title,
            px + (LAYER_PANEL_W - titleW) / 2, py + 2, NodeRenderer.CSB(), false);

        int maxScroll = Math.max(0, layers.size() - maxRows);
        if (layerScroll < 0) layerScroll = 0;
        if (layerScroll > maxScroll) layerScroll = maxScroll;

        // Compute drop indicator Y position (in screen space, above the target row)
        int dropIndicatorY = -1;
        if (layerDragState == LayerDragState.DRAGGING && layerDropIndex >= 0) {
            int visibleDropIdx = layerDropIndex - layerScroll;
            if (visibleDropIdx >= 0 && visibleDropIdx <= visibleRows) {
                dropIndicatorY = rowStartY + visibleDropIdx * LAYER_ROW_H;
            }
        }

        // Draw drop indicator line (behind rows)
        if (dropIndicatorY >= rowStartY) {
            g.fill(px + 2, dropIndicatorY - 1, px + LAYER_PANEL_W - 2, dropIndicatorY + 1, NodeRenderer.ACC());
        }

        for (int vi = 0; vi < visibleRows; vi++) {
            int idx = layerScroll + vi;
            if (idx >= layers.size()) break;
            var n = layers.get(idx);
            int ry = rowStartY + vi * LAYER_ROW_H;
            boolean isSel = selectedDisplayNode != null && selectedDisplayNode.id == n.id;
            boolean isDragged = layerDragState == LayerDragState.DRAGGING
                             && layerDragNode != null && layerDragNode.id == n.id;

            if (isDragged) {
                // Ghost — dimmed placeholder at original position
                g.fill(px + 2, ry, px + LAYER_PANEL_W - 2, ry + LAYER_ROW_H, 0x442A2822);
            } else {
                int bgCol = isSel ? 0xFF4A5A2A : (idx % 2 == 0 ? 0xFF2A2822 : 0xFF22201A);
                g.fill(px + 2, ry, px + LAYER_PANEL_W - 2, ry + LAYER_ROW_H, bgCol);
                // Hover highlight (only when not dragging)
                if (layerDragState != LayerDragState.DRAGGING
                    && mx >= px && mx <= px + LAYER_PANEL_W
                    && my >= ry && my <= ry + LAYER_ROW_H) {
                    g.fill(px + 2, ry, px + LAYER_PANEL_W - 2, ry + LAYER_ROW_H, 0x33353428);
                }
            }

            // Thumbnail
            int thumbX = px + LAYER_PANEL_PADDING;
            int thumbY = ry + (LAYER_ROW_H - LAYER_THUMB_SIZE) / 2;
            renderLayerThumbnail(g, n, thumbX, thumbY, LAYER_THUMB_SIZE);

            // Type icon + node name
            String typeIcon = switch (n.type) {
                case TEXT -> "T"; case DATA -> "D"; case IMAGE -> "I"; case IMAGE_SEQUENCE -> "S";
                case HUD_PITCH_LADDER -> "H"; default -> "?";
            };
            int labelX = thumbX + LAYER_THUMB_SIZE + LAYER_THUMB_MARGIN;
            int labelY = ry + 5;
            g.drawString(Minecraft.getInstance().font, typeIcon + " #" + n.id,
                labelX, labelY, isSel ? 0xFFFFFF88 : 0xFFCCCCCC, false);

            // Color swatch for TEXT/DATA
            if ((n.type == NodeType.TEXT || n.type == NodeType.DATA) && n.textColor != 0) {
                int swatchX = px + LAYER_PANEL_W - LAYER_PANEL_PADDING - 12;
                int swatchY = ry + 4;
                g.fill(swatchX, swatchY, swatchX + 10, swatchY + 8, n.textColor);
                g.renderOutline(swatchX, swatchY, 10, 8, 0xFF666666);
            }
        }

        // ── Render dragged ghost row following cursor (on top of everything) ──
        if (layerDragState == LayerDragState.DRAGGING && layerDragNode != null) {
            int ghostY = (int)(my - LAYER_ROW_H / 2.0);
            // Clamp within visible row area
            ghostY = Math.max(rowStartY, Math.min(ghostY, rowStartY + visibleRows * LAYER_ROW_H - LAYER_ROW_H));
            g.fill(px + 2, ghostY, px + LAYER_PANEL_W - 2, ghostY + LAYER_ROW_H, 0xBB3A3A38);
            g.renderOutline(px + 2, ghostY, LAYER_PANEL_W - 4, LAYER_ROW_H, NodeRenderer.ACC());
            int ghostThumbX = px + LAYER_PANEL_PADDING;
            int ghostThumbY = ghostY + (LAYER_ROW_H - LAYER_THUMB_SIZE) / 2;
            renderLayerThumbnail(g, layerDragNode, ghostThumbX, ghostThumbY, LAYER_THUMB_SIZE);
            String ghostIcon = switch (layerDragNode.type) {
                case TEXT -> "T"; case DATA -> "D"; case IMAGE -> "I"; case IMAGE_SEQUENCE -> "S";
                case HUD_PITCH_LADDER -> "H"; default -> "?";
            };
            int ghostLabelX = ghostThumbX + LAYER_THUMB_SIZE + LAYER_THUMB_MARGIN;
            g.drawString(Minecraft.getInstance().font, ghostIcon + " #" + layerDragNode.id,
                ghostLabelX, ghostY + 5, 0xFFFFFFFF, false);
        }

        // ── Scrollbar ──
        if (maxScroll > 0) {
            int sbX = px + LAYER_PANEL_W - 8;
            int sbY = rowStartY;
            int sbH = visibleRows * LAYER_ROW_H;
            g.fill(sbX, sbY, sbX + 6, sbY + sbH, NodeRenderer.PBG());
            float thumbH = Math.max(20, (float) visibleRows / layers.size() * sbH);
            float thumbY = sbY + (float) layerScroll / maxScroll * (sbH - thumbH);
            g.fill(sbX + 1, (int) thumbY, sbX + 5, (int) (thumbY + thumbH), NodeRenderer.CSB());
        }
    }

    /** Returns clicked layer index in full sorted list, or -1 if no hit */
    private int handleLayerPanelClick(double mx, double my) {
        int px = width - LAYER_PANEL_W - LAYER_PANEL_PADDING;
        if (mx < px || mx > px + LAYER_PANEL_W) return -1;

        var graph = getBE() != null ? getBE().getNodeGraph() : new NodeGraph();
        List<GraphNode> layers = getDisplayLayers(graph);
        if (layers.isEmpty()) return -1;

        int titleH = 12;
        int rowStartY = GraphEditor.TOP_BAR_H + 24 + titleH + 2;
        int maxRows = Math.max(1, (height - rowStartY - 4) / LAYER_ROW_H);
        if (my < rowStartY || my > rowStartY + maxRows * LAYER_ROW_H) return -1;

        int idx = layerScroll + (int)((my - rowStartY) / LAYER_ROW_H);
        if (idx < 0 || idx >= layers.size()) return -1;

        selectedDisplayNode = layers.get(idx);
        return idx;
    }

    // ── Layer drag-and-drop helpers ──

    private void updateLayerDropIndex(double my) {
        var graph = getBE() != null ? getBE().getNodeGraph() : new NodeGraph();
        List<GraphNode> layers = getDisplayLayers(graph);
        if (layers.isEmpty()) return;

        int titleH = 12;
        int rowStartY = GraphEditor.TOP_BAR_H + 24 + titleH + 2;
        int maxRows = Math.max(1, (height - rowStartY - 4) / LAYER_ROW_H);
        int visibleRows = Math.min(layers.size(), maxRows);

        // Walk through ACTUAL visible rows (not empty virtual slots):
        // if mouse is below a row's center, drop advances to after that row
        int targetIdx = layerScroll;
        for (int vi = 0; vi < visibleRows; vi++) {
            int rowCenterY = rowStartY + vi * LAYER_ROW_H + LAYER_ROW_H / 2;
            if (my > rowCenterY) {
                targetIdx = layerScroll + vi + 1;
            }
        }
        // If mouse is below the last visible row, drop at the very end
        float lastRowBottom = rowStartY + visibleRows * LAYER_ROW_H;
        if (my > lastRowBottom) {
            targetIdx = layerScroll + visibleRows;
        }
        targetIdx = Math.max(0, Math.min(layers.size(), targetIdx));
        if (targetIdx != layerDropIndex) {
            layerDropIndex = targetIdx;
        }
    }

    private void handleLayerAutoScroll(double my) {
        int titleH = 12;
        int rowStartY = GraphEditor.TOP_BAR_H + 24 + titleH + 2;
        int maxRows = Math.max(1, (height - rowStartY - 4) / LAYER_ROW_H);
        int panelBottom = rowStartY + maxRows * LAYER_ROW_H;
        long now = System.currentTimeMillis();
        if (now - lastAutoScrollTime < LAYER_AUTOSCROLL_TICK) return;

        var graph = getBE() != null ? getBE().getNodeGraph() : new NodeGraph();
        List<GraphNode> layers = getDisplayLayers(graph);
        if (layers.isEmpty()) return;

        int maxScroll = Math.max(0, layers.size() - maxRows);
        if (my < rowStartY + LAYER_AUTOSCROLL_ZONE && layerScroll > 0) {
            layerScroll = Math.max(0, layerScroll - 1);
            lastAutoScrollTime = now;
        } else if (my > panelBottom - LAYER_AUTOSCROLL_ZONE && layerScroll < maxScroll) {
            layerScroll = Math.min(maxScroll, layerScroll + 1);
            lastAutoScrollTime = now;
        }
    }

    private void applyLayerReorder() {
        var graph = getBE() != null ? getBE().getNodeGraph() : new NodeGraph();
        if (layerDragNode == null) return;

        List<GraphNode> layers = getDisplayLayers(graph);
        int fromIdx = -1;
        for (int i = 0; i < layers.size(); i++) {
            if (layers.get(i).id == layerDragNode.id) { fromIdx = i; break; }
        }
        if (fromIdx < 0) return;

        int toIdx = layerDropIndex;
        if (toIdx > fromIdx) toIdx--;
        if (fromIdx == toIdx) return; // no movement

        // Remove dragged node then insert at target position
        GraphNode dragged = layers.remove(fromIdx);
        if (toIdx < 0) toIdx = 0;
        if (toIdx > layers.size()) toIdx = layers.size();
        layers.add(toIdx, dragged);

        // Reassign layerIndex: front (top) gets highest value, back gets lowest
        // Use nextLayerIndex as the base to avoid collisions
        int base = graph.nextLayerIndex + 1000;
        for (int i = 0; i < layers.size(); i++) {
            layers.get(i).layerIndex = base + (layers.size() - i);
        }
        // Update nextLayerIndex so future nodes appear in front
        graph.nextLayerIndex = base + layers.size() + 1;

        graph.bumpGeneration();
        // 定向同步图层序（不再全量上传）：每个节点的 layerIndex 各发一个 SET_LAYER_INDEX op，
        // 服务端应用并广播，不会用本客户端整图快照冲掉其他玩家的并发编辑。
        // Targeted layer-order sync (no full upload): one SET_LAYER_INDEX op per node; the
        // server applies and broadcasts them without clobbering other players' concurrent
        // edits with a whole-graph snapshot.
        var uid = minecraft.player.getUUID();
        for (var ln : layers) {
            sendOp(io.github.y15173334444.create_schematic_compute.graph.GraphOp.setLayerIndex(
                blockPos, -1, ln.id, ln.layerIndex, uid));
        }
    }

    private void resetLayerDragState() {
        layerDragState = LayerDragState.IDLE;
        layerDragNode = null;
        layerDragOrigIndex = -1;
        layerDropIndex = -1;
        layerDragStartMy = 0;
        layerDragPressTime = 0;
    }

    // ── Settings panel ──
    private void renderSettingsPanel(GuiGraphics g, int mx, int my) {
        var mc = Minecraft.getInstance();
        int pw = MONITOR_SETTINGS_PANEL_W;
        // 合并面板平衡布局：首行 = HUD 复选框 + 虚像缩放；下方 3D 8 项分左右两列
        // （4+4）并排。总高 ≈ 186px，不超出小屏；两列均有内容，无空列（merge-plan §3.1）。
        // Balanced merged panel: first row = HUD checkbox + virtual-image scale; below,
        // the 3D 8 fields split into two side-by-side columns (4+4). Total height ≈ 186px
        // fits short screens; both columns carry content, no empty column (§3.1).
        int rows = 4;
        int ph = 56 + 20 + rows * 20 + 30; // 标题行 + 首行(复选框+虚像缩放) + 3D 字段行 + 保存按钮
        int px = (width - pw) / 2, py = (height - ph) / 2;
        g.fill(px, py, px + pw, py + ph, NodeRenderer.PBG());
        g.renderOutline(px, py, pw, ph, NodeRenderer.PBR());
        g.fill(px + 2, py + 2, px + pw - 2, py + 18, NodeRenderer.PHT());
        g.drawString(Minecraft.getInstance().font, "§6§l" + I18n.get("gui.create_schematic_compute.monitor.settings_title"), px + 6, py + 5, 0xFFFFFFFF, false);
        // Close
        g.fill(px + pw - 18, py + 2, px + pw - 2, py + 18, 0xFF4A3028);
        g.renderOutline(px + pw - 18, py + 2, 16, 16, 0xFF8B5333);
        g.drawString(Minecraft.getInstance().font, "§cX", px + pw - 14, py + 5, 0xFFFFFFFF, false);

        // Load BE values into EditBoxes only once when panel opens
        MonitorBlockEntity mbe = getBE();
        if (!settingsInited && mbe != null) {
            settingFields[0].setValue(ff2(mbe.screenWidth));
            settingFields[1].setValue(ff2(mbe.screenLength));
            settingFields[2].setValue(ff2(mbe.screenX));
            settingFields[3].setValue(ff2(mbe.screenY));
            settingFields[4].setValue(ff2(mbe.screenZ));
            settingFields[5].setValue(ff2(mbe.screenRoll));
            settingFields[6].setValue(ff2(mbe.screenPitch));
            settingFields[7].setValue(ff2(mbe.screenYaw));
            hudSettingFields[0].setValue(ff2(mbe.virtualImageScale));
            // 复选框初始状态跟随服务端实际模式（取代原 tab 状态行）
            // Checkbox initial state follows the server's actual mode (replaces the old tab line)
            hudModeCheckbox = Checkbox.builder(
                Component.translatable("gui.create_schematic_compute.monitor.hud_mode_cb"),
                Minecraft.getInstance().font)
                .pos(px + 10, py + 24)
                .selected(mbe.hudMode)
                .build();
            settingsInited = true;
        }

        // 首行：HUD 模式复选框（左）+ 虚像缩放（右），并排
        // First row: HUD-mode checkbox (left) + virtual-image scale (right), side by side
        if (hudModeCheckbox != null) {
            hudModeCheckbox.setPosition(px + 10, py + 24);
            hudModeCheckbox.render(g, mx, my, 0);
        }
        boolean hudOn = hudModeCheckbox != null && hudModeCheckbox.selected();
        g.drawString(Minecraft.getInstance().font, "§7" + I18n.get(HUD_SETTING_KEYS[0]) + ":", px + 230, py + 26, 0xFFCCCCCC, false);
        var visField = hudSettingFields[0];
        visField.active = true; // 虚像缩放两模式通用（仅 HUD 生效）/ works in both modes (HUD-only effect)
        visField.setX(px + 330); visField.setY(py + 24);
        visField.render(g, mx, my, 0);

        // 3D 字段区：左列 i=0..3、右列 i=4..7，各 4 行并排。
        // 两组字段始终可编辑（hudMode 只切换渲染路径，不影响参数——用户明确要求
        // 「是否 HUD 模式都不影响面板大小/位置/姿态」，故不置灰）。
        // 3D field area: left column i=0..3, right column i=4..7, 4 rows each.
        // Both field groups are always editable — hudMode only switches the render
        // path and never locks the params (the user required hud mode to not affect
        // the panel geometry, so no greying-out here).
        int ey = py + 24 + 20 + 6;
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 2; col++) {
                int i = col == 0 ? row : row + 4;
                int lx = col == 0 ? px + 10 : px + 230;
                int bx = col == 0 ? px + 110 : px + 330;
                g.drawString(Minecraft.getInstance().font, "§7" + I18n.get(SETTING_KEYS[i]) + ":", lx, ey + 2, 0xFFCCCCCC, false);
                var f = settingFields[i];
                f.active = true;
                f.setX(bx); f.setY(ey);
                f.render(g, mx, my, 0);
            }
            ey += 20;
        }
        // Live preview: parse current field values into overrides so the display area updates in real-time
        if (!hudOn) {
            try {
                previewScreenW = Float.parseFloat(settingFields[0].getValue().trim());
                previewScreenL = Float.parseFloat(settingFields[1].getValue().trim());
            } catch (Exception e) { previewScreenW = -1; previewScreenL = -1; }
        } else {
            previewScreenW = -1; previewScreenL = -1;
        }

        // Save button
        int svX = px + 10, svY = ey + 8;
        g.fill(svX, svY, svX + 200, svY + 18, 0xFF3A5A2A);
        g.renderOutline(svX, svY, 200, 18, 0xFF5A8A3A);
        g.drawString(Minecraft.getInstance().font, "§a" + I18n.get("gui.create_schematic_compute.monitor.save_close"), svX + 60, svY + 4, 0xFFFFFFFF, false);
    }

    /** Save all settings and close the panel.
     *  保存所有设置并关闭面板 */
    private void saveAllSettings() {
        if (getBE() == null) return;
        try {
            float w = Float.parseFloat(settingFields[0].getValue().trim());
            float l = Float.parseFloat(settingFields[1].getValue().trim());
            float x = Float.parseFloat(settingFields[2].getValue().trim());
            float y = Float.parseFloat(settingFields[3].getValue().trim());
            float z = Float.parseFloat(settingFields[4].getValue().trim());
            float r = Float.parseFloat(settingFields[5].getValue().trim());
            float p = Float.parseFloat(settingFields[6].getValue().trim());
            float yw = Float.parseFloat(settingFields[7].getValue().trim());
            float vis = Float.parseFloat(hudSettingFields[0].getValue().trim());
            // 设置面板只发定向的 MonitorSettingsPacket，不做整图上传（避免覆盖其他玩家并发
            // 的图编辑）；图数据本身已由各类定向 op 增量同步。HUD 模式与虚像缩放随本包同发。
            // Settings send only the targeted MonitorSettingsPacket — no whole-graph upload
            // (which would clobber other players' concurrent graph edits); graph data is
            // already incrementally synced by the targeted ops. HUD mode + virtual-image
            // scale ride the same packet.
            boolean hud = hudModeCheckbox != null && hudModeCheckbox.selected();
            var pkt = new io.github.y15173334444.create_schematic_compute.network.MonitorSettingsPacket(
                getBE().getBlockPos(), w, l, x, y, z, r, p, yw,
                hud, vis);
            PacketDistributor.sendToServer(pkt);
        } catch (Exception e) { SchematicCompute.LOGGER.warn("Failed to parse monitor settings", e); }
        previewScreenW = -1; previewScreenL = -1;
        showSettings = false; settingsInited = false;
    }

    /** 复选框点击：立即切换模式（服务端广播 → 所见即所得）。面板参数沿用服务端当前已应用
     *  值，不做隐式提交——其余字段仍走显式 Apply/Enter 契约。
     *  Checkbox click: switch mode immediately (server broadcast → WYSIWYG). Panel params keep
     *  the server's current applied values — no implicit commit; other fields stay on the
     *  explicit Apply/Enter contract. */
    private void sendTabMode(boolean hud) {
        MonitorBlockEntity be = getBE();
        if (be == null) return;
        var pkt = new io.github.y15173334444.create_schematic_compute.network.MonitorSettingsPacket(
            be.getBlockPos(),
            be.screenWidth, be.screenLength, be.screenX, be.screenY, be.screenZ,
            be.screenRoll, be.screenPitch, be.screenYaw,
            hud, be.virtualImageScale);
        PacketDistributor.sendToServer(pkt);
    }

    private boolean handleSettingsClick(double mx, double my, int btn) {
        if (btn != 0) return false;
        int pw = MONITOR_SETTINGS_PANEL_W;
        int rows = 4; // 平衡布局：3D 8 项分两列（4+4），首行含复选框+虚像缩放
        int ph = 56 + 20 + rows * 20 + 30;
        int px = (width - pw) / 2, py = (height - ph) / 2;
        // Close button
        if (mx >= px + pw - 18 && mx <= px + pw - 2 && my >= py + 2 && my <= py + 18) {
            previewScreenW = -1; previewScreenL = -1;
            showSettings = false; settingsInited = false; return true;
        }
        // 复选框（点击非当前状态 → 立即切换模式）
        // Checkbox (clicking toggles the mode immediately)
        if (hudModeCheckbox != null && mx >= px + 10 && mx <= px + 10 + 200 && my >= py + 24 && my <= py + 44) {
            hudModeCheckbox.onPress();
            sendTabMode(hudModeCheckbox.selected());
            return true;
        }
        // Save button
        int ey = py + 24 + 20 + 6 + rows * 20;
        int svX = px + 10, svY = ey + 8;
        if (mx >= svX && mx <= svX + 200 && my >= svY && my <= svY + 18 && getBE() != null) {
            saveAllSettings();
            return true;
        }
        // EditBox focus: clear all first, then focus the clicked one.
        // 3D 与虚像缩放字段始终可聚焦——hudMode 只切换渲染路径，不锁定参数。
        // Both the 3D and virtual-image-scale fields are always focusable — hudMode
        // only switches the render path and never locks the params.
        for (int i = 0; i < 8; i++) settingFields[i].setFocused(false);
        for (int i = 0; i < 1; i++) hudSettingFields[i].setFocused(false);
        // 虚像缩放两模式都可编辑（仅 HUD 生效）/ scale editable in both modes (HUD-only effect)
        for (var f : hudSettingFields) {
            if (mx >= f.getX() && mx <= f.getX() + 60 && my >= f.getY() && my <= f.getY() + 14) {
                f.setFocused(true); f.mouseClicked(mx, my, btn); return true;
            }
        }
        for (var f : settingFields) {
            if (mx >= f.getX() && mx <= f.getX() + 60 && my >= f.getY() && my <= f.getY() + 14) {
                f.setFocused(true); f.mouseClicked(mx, my, btn); break;
            }
        }
        return true;
    }

    private record DisplayElement(int nodeId, NodeType type, String text, float value, int[] pixels,
        String label, float x, float y, float scale, float rotation, int color, int imgW, int imgH) {}

    private static DisplayElement findInElements(java.util.List<DisplayElement> elements, int nodeId) {
        for (var e : elements) if (e.nodeId == nodeId) return e;
        return null;
    }

    private java.util.List<DisplayElement> collectDisplayElements(NodeGraph graph, Map<Integer, float[]> outputs) {
        var list = new java.util.ArrayList<DisplayElement>();
        for (var n : graph.nodes) {
            switch (n.type) {
                case TEXT -> {
                    int tc = n.textColor != 0 ? n.textColor : 0xFFCCCCCC;
                    list.add(new DisplayElement(n.id, n.type, n.displayText, 0, null, "", n.layoutX, n.layoutY, n.displayScale, n.displayRotation, tc, 0, 0));
                }
                case DATA -> {
                    float val = graph.getInputValue(n.id, 0, outputs);
                    String lbl = n.params.length > 0 ? ff3(n.params[0]) : "val";
                    int dc = n.textColor != 0 ? n.textColor : 0xFF88FF88;
                    list.add(new DisplayElement(n.id, n.type, "", val, null, lbl, n.layoutX, n.layoutY, n.displayScale, n.displayRotation, dc, 0, 0));
                }
                case IMAGE -> {
                    float ox = graph.getInputValue(n.id, 0, outputs);
                    float oy = graph.getInputValue(n.id, 1, outputs);
                    float rotIn = graph.getInputValue(n.id, 2, outputs);
                    float msX = n.params.length > 0 ? n.params[0] : 0.01f;
                    float msY = n.params.length > 1 ? n.params[1] : 0.01f;
                    float rotScale = n.params.length > 2 ? n.params[2] : 1f;
                    boolean invX = n.params.length > 3 && n.params[3] > 0.5f;
                    boolean invY = n.params.length > 4 && n.params[4] > 0.5f;
                    float dx = ox * (invX ? -msX : msX);
                    float dy = oy * (invY ? -msY : msY);
                    float effRot = n.displayRotation + rotIn * rotScale;
                    float[] cp = clampImageNorm(n, n.layoutX + dx, n.layoutY + dy, effRot);
                    list.add(new DisplayElement(n.id, n.type, "", 0, n.imagePixels, "", cp[0], cp[1], n.displayScale, effRot, 0, n.imageWidth, n.imageHeight));
                }
                case IMAGE_SEQUENCE -> {
                    float ox = graph.getInputValue(n.id, 0, outputs);
                    float oy = graph.getInputValue(n.id, 1, outputs);
                    int frameIdx = Math.round(graph.getInputValue(n.id, 2, outputs));
                    float rotIn = graph.getInputValue(n.id, 3, outputs);
                    float msX = n.params.length > 0 ? n.params[0] : 0.01f;
                    float msY = n.params.length > 1 ? n.params[1] : 0.01f;
                    float rotScale = n.params.length > 2 ? n.params[2] : 1f;
                    boolean invX = n.params.length > 3 && n.params[3] > 0.5f;
                    boolean invY = n.params.length > 4 && n.params[4] > 0.5f;
                    float dx = ox * (invX ? -msX : msX);
                    float dy = oy * (invY ? -msY : msY);
                    float effRot = n.displayRotation + rotIn * rotScale;
                    int[] pixels = null;
                    if (n.imageSequenceFrames != null && !n.imageSequenceFrames.isEmpty()) {
                        frameIdx = Math.max(0, Math.min(frameIdx, n.imageSequenceFrames.size() - 1));
                        pixels = n.imageSequenceFrames.get(frameIdx);
                    }
                    float[] cp = clampImageNorm(n, n.layoutX + dx, n.layoutY + dy, effRot);
                    list.add(new DisplayElement(n.id, n.type, "", 0, pixels, "", cp[0], cp[1], n.displayScale, effRot, 0, n.imageWidth, n.imageHeight));
                }
                case HUD_PITCH_LADDER -> {
                    // 共形俯仰梯：编辑器里用固定相机模拟预览（layout 作符号组偏移，默认居中）
                    // Conformal pitch ladder: fixed-camera mock preview in the editor (layout = group offset)
                    list.add(new DisplayElement(n.id, n.type, "", 0, null, "", n.layoutX, n.layoutY, n.displayScale, 0, 0, 0, 0));
                }
            }
        }
        list.sort((a, b) -> {
            GraphNode na = graph.findNode(a.nodeId()), nb = graph.findNode(b.nodeId());
            int la = na != null ? na.layerIndex : 0;
            int lb = nb != null ? nb.layerIndex : 0;
            return Integer.compare(lb, la); // descending: higher layerIndex = front = rendered last
        });
        return list;
    }

    /** Clamp IMAGE normalized position using rotated-AABB-aware bounds,
     *  matching MonitorBlockEntityRenderer's clamping (lines 124-133).
     *  使用旋转 AABB 感知边界裁剪 IMAGE 归一化位置，
     *  与 MonitorBlockEntityRenderer 的裁剪逻辑一致（第 124-133 行）。 */
    /** Clamp IMAGE normalized position — delegates to shared GeometryConstants.
     *  裁剪 IMAGE 归一化位置 — 委托给共享的 GeometryConstants。 */
    private float[] clampImageNorm(GraphNode n, float rawX, float rawY, float rotation) {
        return GeometryConstants.clampImageNorm(n.displayScale, rawX, rawY, rotation,
            getEffectiveScreenW(), getEffectiveScreenL(), n.imageWidth, n.imageHeight);
    }

    /** Compute rotated AABB — delegates to shared GeometryConstants. */
    private static float[] elemRotAABB(float ex, float ey, float w, float h, float rot) {
        return GeometryConstants.elemRotAABB(ex, ey, w, h, rot);
    }

    private void renderPixels(GuiGraphics g, int[] pixels, int x, int y, int cellSize, int gridW, int gridH) {
        for (int py = 0; py < gridH; py++) {
            for (int px = 0; px < gridW; px++) {
                int idx = py * gridW + px;
                if (idx < pixels.length) {
                    int color = pixels[idx];
                    // Skip fully transparent pixels so background shows through
                    if ((color >>> 24) != 0) {
                        g.fill(x + px * cellSize, y + py * cellSize,
                            x + (px + 1) * cellSize, y + (py + 1) * cellSize,
                            color);
                    }
                }
            }
        }
    }

    private void drawBtn(GuiGraphics g, String label, int x, int y, int mx, int my) {
        boolean h = mx >= x && mx <= x + 14 && my >= y && my <= y + 14;
        g.fill(x, y, x + 14, y + 14, h ? 0xFF4A3F28 : 0xFF3A3832);
        g.renderOutline(x, y, 14, 14, NodeRenderer.PBR());
        g.drawString(Minecraft.getInstance().font, label, x + 1, y + 3, h ? 0xFFFFFF88 : 0xFFCCCCCC, false);
    }

    // ── Display toggle button (graph editor mode) ──
    private void renderDisplayToggleButton(GuiGraphics g) {
        var mc = Minecraft.getInstance();
        int btnX = width - 76, btnY = GraphEditor.TOP_BAR_H + 2, btnW = 60, btnH = 18;
        g.fill(btnX, btnY, btnX + btnW, btnY + btnH, 0xFF3A3832);
        g.renderOutline(btnX, btnY, btnW, btnH, NodeRenderer.CSB());
        g.renderOutline(btnX + 1, btnY + 1, btnW - 2, btnH - 2, NodeRenderer.PBG());
        g.drawString(Minecraft.getInstance().font, I18n.get("gui.create_schematic_compute.monitor.display"), btnX + 6, btnY + 4, 0xFFFFFFFF, false);
    }

    // ── Pixel editor (独立 Screen / standalone PixelEditorScreen, v1.2.6+) ──

    /** 双击 IMAGE/IMAGE_SEQUENCE 节点 → 打开独立像素编辑器 Screen（绘画软件式 UI）。
     *  转移前置位 pixelEditorTransfer：本屏 onClose 跳过离开协作会话（像素编辑器屏
     *  不 join/leave，会话保持不断开）；关闭像素编辑器后重建本屏时再正常 join（幂等）。
     *  Double-click an IMAGE/IMAGE_SEQUENCE node → open the standalone pixel editor.
     *  Sets pixelEditorTransfer so this screen's onClose skips leaving the collab
     *  session (the pixel-editor screen never joins/leaves; membership must survive);
     *  the rebuilt MonitorScreen re-joins idempotently. */
    @net.neoforged.api.distmarker.OnlyIn(net.neoforged.api.distmarker.Dist.CLIENT)
    private void openPixelEditor(GraphNode node) {
        if (node.type != NodeType.IMAGE && node.type != NodeType.IMAGE_SEQUENCE) return;
        pixelEditorTransfer = true;
        Minecraft.getInstance().setScreen(new io.github.y15173334444.create_schematic_compute.client.PixelEditorScreen(
            blockPos, node, computePixelEditorReturn()));
    }

    /** 像素编辑器关闭后要恢复的界面：便携终端包装内 → 重建包装（内部换成新 MonitorScreen，
     *  关闭后仍回到终端）；否则直接回到新 MonitorScreen（重新 join 会话，幂等）。
     *  The screen to restore after the pixel editor closes: inside the portable-terminal
     *  wrapper → rebuild the wrapper around a fresh MonitorScreen (still returns to the
     *  terminal); otherwise a fresh MonitorScreen (re-joins the session, idempotent). */
    private Screen computePixelEditorReturn() {
        var mc = Minecraft.getInstance();
        if (mc.screen instanceof io.github.y15173334444.create_schematic_compute.client.PortableTerminalScreen.HostWrapper w
            && w.getInnerScreen() == this
            && w.getTerminalScreen() instanceof io.github.y15173334444.create_schematic_compute.client.PortableTerminalScreen pts) {
            return pts.wrapForEditing(new MonitorScreen(blockPos));
        }
        return new MonitorScreen(blockPos);
    }

    /** 显示区拖拽是否进行中（整图同步守卫用）。 */
    @Override public boolean isDisplayDragInProgress() { return draggedDisplayNode != null; }

    /** 存在包编辑模式：显示布局模式下为 1。 */
    @Override public int getPresenceMode() { return displayMode ? 1 : 0; }
    /** 显示布局模式下的光标屏幕 X；节点图模式返回 -1 走图光标。 */
    @Override public float getPresenceCursorX() { return displayMode ? lastDisplayMouseX : -1f; }
    /** 显示布局模式下的光标屏幕 Y；节点图模式返回 -1 走图光标。 */
    @Override public float getPresenceCursorY() { return displayMode ? lastDisplayMouseY : -1f; }
    /** 显示布局编辑器中正在拖拽的节点 id。 */
    @Override public int getPresenceDraggedNodeId() { return draggedDisplayNode != null ? draggedDisplayNode.id : -1; }

    // ── Input handling ──
    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        // Color picker: only handle if click is inside picker; otherwise let the graph editor use it
        if (editor.colorPicker.isVisible()) {
            if (editor.colorPicker.contains((int)mx, (int)my)) {
                return editor.colorPicker.mouseClicked(mx, my, btn);
            }
            // Delegate to GraphEditor first — comment color popup / theme panel may need the picker
            if (editor.mouseClicked(mx, my, btn)) return true;
            // If GraphEditor didn't handle it, close picker on outside click
            editor.colorPicker.close();
            return true;
        }
        // Settings panel takes priority
        if (showSettings) {
            return handleSettingsClick(mx, my, btn);
        }
        if (displayMode) {
            // Check layer panel scrollbar thumb drag first
            var graph2 = getBE() != null ? getBE().getNodeGraph() : new NodeGraph();
            List<GraphNode> layers2 = getDisplayLayers(graph2);
            if (!layers2.isEmpty()) {
                int px2 = width - LAYER_PANEL_W - LAYER_PANEL_PADDING;
                int rowStartY2 = GraphEditor.TOP_BAR_H + 24 + 12 + 2;
                int maxRows2 = Math.max(1, (height - rowStartY2 - 4) / LAYER_ROW_H);
                int visRows2 = Math.min(layers2.size(), maxRows2);
                int maxScroll2 = Math.max(0, layers2.size() - maxRows2);
                if (maxScroll2 > 0) {
                    int sbX2 = px2 + LAYER_PANEL_W - 8;
                    int sbY2 = rowStartY2;
                    int sbH2 = visRows2 * LAYER_ROW_H;
                    float thumbH2 = Math.max(20, (float) visRows2 / layers2.size() * sbH2);
                    float thumbY2 = sbY2 + (float) layerScroll / maxScroll2 * (sbH2 - thumbH2);
                    if (mx >= sbX2 && mx <= sbX2 + 6 && my >= thumbY2 && my <= thumbY2 + thumbH2) {
                        layerScrollbarDragging = true;
                        layerScrollDragStartY = my;
                        layerScrollDragStartOff = layerScroll;
                        return true;
                    }
                }
            }
            int clickedLayerIdx = handleLayerPanelClick(mx, my);
            if (clickedLayerIdx >= 0) {
                // Initiate potential drag
                layerDragState = LayerDragState.PRESSED;
                layerDragNode = selectedDisplayNode;
                layerDragOrigIndex = clickedLayerIdx;
                layerDropIndex = clickedLayerIdx;
                layerDragStartMy = my;
                layerDragPressTime = System.currentTimeMillis();
                return true;
            }
            return handleDisplayAreaClick(mx, my, btn);
        }
        // Graph editor mode: check display toggle button first
        if (btn == 0 && mx >= width - 76 && mx <= width - 16
            && my >= GraphEditor.TOP_BAR_H + 2 && my <= GraphEditor.TOP_BAR_H + 20) {
            displayMode = true;
            return true;
        }
        // Double-click IMAGE/IMAGE_SEQUENCE node → open pixel editor
        // (exclude expand-indicator area to avoid conflict with expand toggle)
        if (btn == 0 && getBE() != null) {
            long now = System.currentTimeMillis();
            GraphNode clicked = null;
            float hitSx = 0, hitSy = 0;
            for (var n : getBE().getNodeGraph().nodes) {
                if (n.type != NodeType.IMAGE && n.type != NodeType.IMAGE_SEQUENCE) continue;
                float sx = editor.c2sX(n.x), sy = editor.c2sY(n.y);
                float sw = GraphEditor.NW * editor.zoom, nh = (GraphEditor.HH + GraphEditor.PH * (n.inputs() + n.outputs())) * editor.zoom + 4;
                if (mx >= sx && mx <= sx + sw && my >= sy && my <= sy + nh) { clicked = n; hitSx = sx; hitSy = sy; break; }
            }
            if (clicked != null) {
                // Check if click is on expand indicator (top-right corner of node)
                float ix = hitSx + (GraphEditor.NW - 22) * editor.zoom;
                float iy = hitSy + 2 * editor.zoom;
                float is = 12 * editor.zoom;
                boolean onExpand = mx >= ix && mx <= ix + is && my >= iy && my <= iy + is;
                if (!onExpand && clicked.id == lastClickNodeId && now - lastClickTime < 400) {
                    openPixelEditor(clicked); lastClickNodeId = -1; return true;
                }
                if (!onExpand) { lastClickTime = now; lastClickNodeId = clicked.id; }
                else { lastClickNodeId = -1; }
            } else {
                lastClickNodeId = -1;
            }
        }
        return editor.mouseClicked(mx, my, btn) || super.mouseClicked(mx, my, btn);
    }

    private boolean handleDisplayAreaClick(double mx, double my, int btn) {
        if (btn == 0) {
            var da = computeDisplayArea();
            int tby = GraphEditor.TOP_BAR_H + 2, tbh = MONITOR_TOOLBAR_H;
            // < Graph
            if (mx >= 4 && mx <= 60 && my >= tby && my <= tby + tbh)
                { displayMode = false; selectedDisplayNode = null; return true; }
            // Settings
            if (mx >= 66 && mx <= 122 && my >= tby && my <= tby + tbh)
                { showSettings = true; return true; }

            // 整图同步替换后 selectedDisplayNode 可能指向旧图的孤儿节点——按 id 重映射到当前图。
            // 否则随后的"已选元素优先检查"会把拖拽绑定到孤儿节点：本地实时更新落空（渲染冻结、
            // 松手才同步），而流式 op 仍携带孤儿位置使远端可见移动——"首次拖动正常、之后本地
            // 视觉不更新"的根因。
            // After a full-graph sync replacement selectedDisplayNode can point at an orphaned
            // node from the old graph — remap it by id to the current graph. Otherwise the
            // elevated hit check binds the next drag to the orphan: local live updates go
            // nowhere (frozen render, position lands on release) while the streamed ops carry
            // the orphan's position so remote clients still see movement.
            {
                var liveGraph = getBE() != null ? getBE().getNodeGraph() : null;
                if (selectedDisplayNode != null && liveGraph != null) {
                    var curSel = liveGraph.findNode(selectedDisplayNode.id);
                    if (curSel != selectedDisplayNode) selectedDisplayNode = curSel;
                }
            }

            // S/R editable value clicks (compute positions matching toolbar render)
            if (selectedDisplayNode != null) {
                var fw = Minecraft.getInstance().font;
                int sx = 128, sy = tby;
                String sVal = editingS ? editSBuf : ff1(selectedDisplayNode.displayScale);
                int sEnd = sx + fw.width("§6S:§e" + sVal + "▌") + 12;
                if (mx >= sx && mx <= sEnd && my >= sy && my <= sy + tbh) {
                    editingS = true; editingR = false; editSBuf = ff1(selectedDisplayNode.displayScale);
                    return true;
                }
                int rx = sEnd;
                String rVal = editingR ? editRBuf : ff0(selectedDisplayNode.displayRotation);
                int rEnd = rx + fw.width("§6R:§e" + rVal + "▌") + 4;
                if (mx >= rx && mx <= rEnd && my >= sy && my <= sy + tbh) {
                    editingR = true; editingS = false; editRBuf = ff0(selectedDisplayNode.displayRotation);
                    return true;
                }
            }

            // Check for display element hits (scaled to rendered size)
            var graph = getBE() != null ? getBE().getNodeGraph() : new NodeGraph();
            var evalOutputs4 = getEvalOutputs();
            var elements = collectDisplayElements(graph, evalOutputs4);
            float guiScale2 = da.w * FONT_BLOCK_SCALE / Math.max(getContentWorldW(), 0.01f);
            var ci = getContentArea(da);
            int contentX = ci[0], contentY = ci[1], contentW = ci[2], contentH = ci[3];

            // Check selected element first (from layer panel) — elevated hit priority
            // 队友正在拖拽的组件跳过（软锁） / skip elements a teammate is dragging (soft lock)
            if (selectedDisplayNode != null) {
                var selNode = selectedDisplayNode;
                if (editor.isDisplayNodeLocked(selNode.id)) {
                    selectedDisplayNode = null;
                } else {
                var selElem = findInElements(elements, selNode.id);
                if (selElem != null) {
                    float s2 = guiScale2 * selElem.scale;
                    float hw, hh;
                    var font3 = Minecraft.getInstance().font;
                    if (selElem.type == NodeType.IMAGE || selElem.type == NodeType.IMAGE_SEQUENCE) {
                        hw = selElem.imgW * IMAGE_CELL_FONT; hh = selElem.imgH * IMAGE_CELL_FONT;
                    } else if (selElem.type == NodeType.DATA) {
                        String vs = ff1(selElem.value);
                        hw = font3.width(vs.isEmpty() ? "0.0" : vs); hh = 10;
                    } else {
                        hw = font3.width(selElem.text.isEmpty() ? " " : selElem.text); hh = 10;
                    }
                    float sx = contentX + selElem.x * contentW;
                    float sy = contentY + selElem.y * contentH;
                    float[] bb2 = elemRotAABB(sx, sy, hw * s2, hh * s2, selElem.rotation);
                    if (mx >= bb2[0] && mx <= bb2[2] && my >= bb2[1] && my <= bb2[3]) {
                        draggedDisplayNode = selNode;
                        lastDisplayDragSendTime = 0;
                        dragOffX = (float)(mx - sx);
                        dragOffY = (float)(my - sy);
                        return true;
                    }
                }
                }
            }
            for (int i = elements.size() - 1; i >= 0; i--) {
                var elem = elements.get(i);
                if (editor.isDisplayNodeLocked(elem.nodeId)) continue; // 软锁：队友拖拽中 / soft lock
                float s = guiScale2 * elem.scale;
                float hitW, hitH;
                var font2 = Minecraft.getInstance().font;
                if (elem.type == NodeType.IMAGE || elem.type == NodeType.IMAGE_SEQUENCE) {
                    hitW = elem.imgW * IMAGE_CELL_FONT; hitH = elem.imgH * IMAGE_CELL_FONT;
                } else if (elem.type == NodeType.DATA) {
                    String valStr = ff1(elem.value);
                    hitW = font2.width(valStr.isEmpty() ? "0.0" : valStr);
                    hitH = 10;
                } else {
                    hitW = font2.width(elem.text.isEmpty() ? " " : elem.text);
                    hitH = 10;
                }
                // Clamp so full element stays in display area, then do hit test
                float ex = contentX + elem.x * contentW;
                float ey = contentY + elem.y * contentH;
                float ew = hitW * s, eh = hitH * s;
                float[] bb = elemRotAABB(ex, ey, ew, eh, elem.rotation);
                float dl = contentX, dr = contentX + contentW, dt = contentY, db = contentY + contentH;
                if (bb[2] > dr) ex -= (bb[2] - dr);
                if (bb[3] > db) ey -= (bb[3] - db);
                if (bb[0] < dl) ex += (dl - bb[0]);
                if (bb[1] < dt) ey += (dt - bb[1]);
                // Rotated AABB hit test (center-based)
                var aabb = elemRotAABB(ex, ey, hitW * s, hitH * s, elem.rotation);
                if (mx >= aabb[0] && mx <= aabb[2] && my >= aabb[1] && my <= aabb[3]) {
                    GraphNode hitNode = graph.findNode(elem.nodeId);
                    if (hitNode != null) {
                        selectedDisplayNode = hitNode;
                        draggedDisplayNode = hitNode;
                        lastDisplayDragSendTime = 0;
                        dragOffX = (float)(mx - ex);
                        dragOffY = (float)(my - ey);
                        return true;
                    }
                }
            }
            // No element hit — only drag the selected node if the press point is actually
            // inside its (clamped) AABB, never grab a stale selection. This fixes the
            // "select image 1, then drag image 2 → image 1 moves / image 2 doesn't follow" bug.
            // 未命中任何元素——仅当按下点落在已选节点（裁剪后的）AABB 内时才开拖，
            // 不再抓取陈旧选择（先点图1再拖图2 → 动的却是图1 的 bug）。
            if (selectedDisplayNode != null && !editor.isDisplayNodeLocked(selectedDisplayNode.id)) {
                var selNode = selectedDisplayNode;
                var selElem = findInElements(elements, selNode.id);
                if (selElem != null) {
                    float s2 = guiScale2 * selElem.scale;
                    float hw2, hh2;
                    var font3 = Minecraft.getInstance().font;
                    if (selElem.type == NodeType.IMAGE || selElem.type == NodeType.IMAGE_SEQUENCE) {
                        hw2 = selElem.imgW * IMAGE_CELL_FONT; hh2 = selElem.imgH * IMAGE_CELL_FONT;
                    } else if (selElem.type == NodeType.DATA) {
                        String vs = ff1(selElem.value);
                        hw2 = font3.width(vs.isEmpty() ? "0.0" : vs); hh2 = 10;
                    } else {
                        hw2 = font3.width(selElem.text.isEmpty() ? " " : selElem.text); hh2 = 10;
                    }
                    float ex = contentX + selElem.x * contentW;
                    float ey = contentY + selElem.y * contentH;
                    // Apply the same full-AABB clamp as the draw path so the guard region
                    // matches the element's rendered position at the borders.
                    // 与绘制路径一致的全 AABB 裁剪，保证守卫区域与边框处实际渲染位置吻合。
                    float[] bb = elemRotAABB(ex, ey, hw2 * s2, hh2 * s2, selElem.rotation);
                    if (bb[2] > contentX + contentW) ex -= (bb[2] - (contentX + contentW));
                    if (bb[3] > contentY + contentH) ey -= (bb[3] - (contentY + contentH));
                    if (bb[0] < contentX) ex += (contentX - bb[0]);
                    if (bb[1] < contentY) ey += (contentY - bb[1]);
                    var aabb = elemRotAABB(ex, ey, hw2 * s2, hh2 * s2, selElem.rotation);
                    if (mx >= aabb[0] && mx <= aabb[2] && my >= aabb[1] && my <= aabb[3]) {
                        draggedDisplayNode = selNode;
                        lastDisplayDragSendTime = 0;
                        dragOffX = (float)(mx - ex);
                        dragOffY = (float)(my - ey);
                        return true;
                    }
                }
            }
            selectedDisplayNode = null;
        }
        return false;
    }

    @Override
    public void mouseMoved(double mx, double my) {
        // Layer panel scrollbar drag
        if (layerScrollbarDragging) {
            var graph3 = getBE() != null ? getBE().getNodeGraph() : new NodeGraph();
            List<GraphNode> layers3 = getDisplayLayers(graph3);
            if (!layers3.isEmpty()) {
                int rowStartY3 = GraphEditor.TOP_BAR_H + 24 + 12 + 2;
                int maxRows3 = Math.max(1, (height - rowStartY3 - 4) / LAYER_ROW_H);
                int visRows3 = Math.min(layers3.size(), maxRows3);
                int maxScroll3 = Math.max(0, layers3.size() - maxRows3);
                int sbH3 = visRows3 * LAYER_ROW_H;
                float thumbH3 = Math.max(20, (float) visRows3 / layers3.size() * sbH3);
                float delta = (float) (my - layerScrollDragStartY) / (sbH3 - thumbH3);
                int newOff = layerScrollDragStartOff + Math.round(delta * maxScroll3);
                if (newOff < 0) newOff = 0;
                if (newOff > maxScroll3) newOff = maxScroll3;
                layerScroll = newOff;
            }
            return;
        }
        if (displayMode) {
            // 记录最近鼠标屏幕坐标（存在包用）
            // Track the last mouse screen position for the presence packet
            lastDisplayMouseX = (float)mx;
            lastDisplayMouseY = (float)my;
            // Layer drag-and-drop — handle here AND in mouseDragged
            // (Minecraft may call either depending on version/patches)
            if (layerDragState == LayerDragState.PRESSED) {
                if (Math.abs(my - layerDragStartMy) > LAYER_DRAG_THRESHOLD
                    || System.currentTimeMillis() - layerDragPressTime > 200) {
                    layerDragState = LayerDragState.DRAGGING;
                }
            }
            if (layerDragState == LayerDragState.DRAGGING && layerDragNode != null) {
                updateLayerDropIndex(my);
                handleLayerAutoScroll(my);
                return;
            }
            // Display-area component dragging
            if (draggedDisplayNode != null) {
                updateDisplayDrag(mx, my);
            }
            return;
        }
        editor.mouseMoved(mx, my);
    }

    /** 更新显示区拖拽位置 + 节流流式发送布局 op。
     *  必须在 mouseMoved 与 mouseDragged 中都调用：触屏设备拖动期间只产生
     *  mouseDragged 事件（无 mouseMoved），只挂在 mouseMoved 会导致触屏拖拽
     *  本地渲染冻结、松手才同步（鼠标则正常）。
     *  Update the display drag position + throttle-stream the layout op. MUST run from
     *  both mouseMoved and mouseDragged: touchscreens only emit mouseDragged during a
     *  drag (no mouseMoved), so updating only in mouseMoved froze the local render on
     *  touch until release (mouse worked fine). */
    private void updateDisplayDrag(double mx, double my) {
        var da = computeDisplayArea();
        float gsD = da.w * FONT_BLOCK_SCALE / Math.max(getContentWorldW(), 0.01f);
        float sD = gsD * draggedDisplayNode.displayScale;
        float eW, eH;
        if (draggedDisplayNode.type == NodeType.IMAGE || draggedDisplayNode.type == NodeType.IMAGE_SEQUENCE) {
            eW = draggedDisplayNode.imageWidth * IMAGE_CELL_FONT; eH = draggedDisplayNode.imageHeight * IMAGE_CELL_FONT;
        } else {
            String ts = draggedDisplayNode.type == NodeType.DATA
                ? ff1(0f)
                : (draggedDisplayNode.displayText.isEmpty() ? " " : draggedDisplayNode.displayText);
            eW = Minecraft.getInstance().font.width(ts); eH = 10;
        }
        var ciD = getContentArea(da);
        int cXD = ciD[0], cYD = ciD[1], cWD = ciD[2], cHD = ciD[3];
        float rawX = (float)(mx - cXD - dragOffX) / cWD;
        float rawY = (float)(my - cYD - dragOffY) / cHD;
        float exD = cXD + Math.max(0, Math.min(1, rawX)) * cWD;
        float eyD = cYD + Math.max(0, Math.min(1, rawY)) * cHD;
        float[] bbD = elemRotAABB(exD, eyD, eW * sD, eH * sD, draggedDisplayNode.displayRotation);
        int drD = cXD + cWD, dbD = cYD + cHD;
        if (bbD[2] > drD) exD -= (bbD[2] - drD);
        if (bbD[3] > dbD) eyD -= (bbD[3] - dbD);
        if (bbD[0] < cXD) exD += (cXD - bbD[0]);
        if (bbD[1] < cYD) eyD += (cYD - bbD[1]);
        draggedDisplayNode.layoutX = Math.max(0, Math.min(1, (exD - cXD) / cWD));
        draggedDisplayNode.layoutY = Math.max(0, Math.min(1, (eyD - cYD) / cHD));
        // 实时协作：按节流流式发送布局 op，远端客户端实时看到拖拽（松手时另有最终 op）。
        // 顺带使拖拽期间 pendingLocalOps > 0，与整图同步守卫双重保护本地图不被替换。
        // Live collaboration: throttle-stream the layout op so remote clients see the
        // drag in real time (release sends the final op). Also keeps pendingLocalOps > 0
        // during the drag as a second line of defense for the full-sync guard.
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastDisplayDragSendTime >= DISPLAY_DRAG_SEND_INTERVAL_MS) {
            lastDisplayDragSendTime = nowMs;
            sendOp(io.github.y15173334444.create_schematic_compute.graph.GraphOp.setDisplayLayout(
                blockPos, -1, draggedDisplayNode.id,
                draggedDisplayNode.layoutX, draggedDisplayNode.layoutY,
                draggedDisplayNode.displayScale, draggedDisplayNode.displayRotation,
                draggedDisplayNode.moveScale,
                minecraft.player.getUUID()));
        }
    }

    @Override
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) {
        if (editor.colorPicker.isVisible() && editor.colorPicker.contains((int)mx, (int)my))
            return editor.colorPicker.mouseDragged(mx, my, btn, dx, dy);
        if (displayMode) {
            // ── Layer drag-and-drop (same logic as mouseMoved) ──
            if (layerDragState == LayerDragState.PRESSED) {
                if (Math.abs(my - layerDragStartMy) > LAYER_DRAG_THRESHOLD
                    || System.currentTimeMillis() - layerDragPressTime > 200) {
                    layerDragState = LayerDragState.DRAGGING;
                }
            }
            if (layerDragState == LayerDragState.DRAGGING && layerDragNode != null) {
                updateLayerDropIndex(my);
                handleLayerAutoScroll(my);
                return true;
            }
            // Display-area dragging — update in BOTH mouseDragged and mouseMoved (touchscreens
            // only emit mouseDragged during drags; without this the local render freezes on touch)
            // 显示区拖拽——mouseDragged 与 mouseMoved 都要更新（触屏拖动期间只发 mouseDragged）
            if (draggedDisplayNode != null) { updateDisplayDrag(mx, my); return true; }
            return super.mouseDragged(mx, my, btn, dx, dy);
        }
        return editor.mouseDragged(mx, my, btn, dx, dy) || super.mouseDragged(mx, my, btn, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int btn) {
        if (editor.colorPicker.isVisible() && editor.colorPicker.contains((int)mx, (int)my)) {
            editor.colorPicker.mouseReleased(mx, my, btn); return true;
        }
        if (layerScrollbarDragging) { layerScrollbarDragging = false; return true; }
        if (displayMode) {
            if (layerDragState == LayerDragState.DRAGGING && layerDragNode != null) {
                applyLayerReorder();
                resetLayerDragState();
                draggedDisplayNode = null;
                return true;
            }
            if (layerDragState == LayerDragState.PRESSED) {
                resetLayerDragState();
            }
            if (draggedDisplayNode != null) {
                sendOp(io.github.y15173334444.create_schematic_compute.graph.GraphOp.setDisplayLayout(
                    blockPos, -1, draggedDisplayNode.id,
                    draggedDisplayNode.layoutX, draggedDisplayNode.layoutY,
                    draggedDisplayNode.displayScale, draggedDisplayNode.displayRotation,
                    draggedDisplayNode.moveScale,
                    minecraft.player.getUUID()));
            }
            draggedDisplayNode = null;
            return true;
        }
        editor.mouseReleased(mx, my, btn);
        return super.mouseReleased(mx, my, btn);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (editor.colorPicker.isVisible() && editor.colorPicker.mouseScrolled(mx, my, sy)) return true;
        if (displayMode) {
            int px = width - LAYER_PANEL_W - LAYER_PANEL_PADDING;
            if (mx >= px && mx <= px + LAYER_PANEL_W) { layerScroll += (sy > 0) ? -1 : 1; }
            return true;
        }
        return editor.mouseScrolled(mx, my, sx, sy);
    }

    @Override
    public boolean keyPressed(int key, int sc, int mod) {
        if (editor.colorPicker.isVisible()) {
            return editor.colorPicker.keyPressed(key, sc, mod);
        }
        if (showSettings) {
            for (var f : settingFields) if (f.isFocused()) {
                if ((key == 257 || key == 335) && getBE() != null) { saveAllSettings(); return true; } // Enter saves
                return f.keyPressed(key, sc, mod);
            }
            for (var f : hudSettingFields) if (f.isFocused()) {
                if ((key == 257 || key == 335) && getBE() != null) { saveAllSettings(); return true; } // Enter saves
                return f.keyPressed(key, sc, mod);
            }
            if (key == 256) { previewScreenW = -1; previewScreenL = -1; showSettings = false; settingsInited = false; return true; }
        }
        if (displayMode) {
            // ESC cancels layer drag
            if (key == 256 && layerDragState == LayerDragState.DRAGGING) {
                resetLayerDragState();
                return true;
            }
            if (editingS) {
                if (key == 256) { editingS = false; return true; }
                if (key == 257 || key == 335) {
                    try { selectedDisplayNode.displayScale = Math.max(0.01f, Float.parseFloat(editSBuf)); }
                    catch (Exception e) { SchematicCompute.LOGGER.debug("Hex input parse", e); }
                    sendOp(io.github.y15173334444.create_schematic_compute.graph.GraphOp.setDisplayLayout(
                        blockPos, -1, selectedDisplayNode.id,
                        selectedDisplayNode.layoutX, selectedDisplayNode.layoutY,
                        selectedDisplayNode.displayScale, selectedDisplayNode.displayRotation,
                        selectedDisplayNode.moveScale,
                        minecraft.player.getUUID()));
                    editingS = false; return true;
                }
                if (key == 259 && editSBuf.length() > 0) { editSBuf = editSBuf.substring(0, editSBuf.length() - 1); return true; }
                return true;
            }
            if (editingR) {
                if (key == 256) { editingR = false; return true; }
                if (key == 257 || key == 335) {
                    try { selectedDisplayNode.displayRotation = Float.parseFloat(editRBuf) % 360f; }
                    catch (Exception e) { SchematicCompute.LOGGER.debug("Hex input parse", e); }
                    sendOp(io.github.y15173334444.create_schematic_compute.graph.GraphOp.setDisplayLayout(
                        blockPos, -1, selectedDisplayNode.id,
                        selectedDisplayNode.layoutX, selectedDisplayNode.layoutY,
                        selectedDisplayNode.displayScale, selectedDisplayNode.displayRotation,
                        selectedDisplayNode.moveScale,
                        minecraft.player.getUUID()));
                    editingR = false; return true;
                }
                if (key == 259 && editRBuf.length() > 0) { editRBuf = editRBuf.substring(0, editRBuf.length() - 1); return true; }
                return true;
            }
            if (key == 256) { // ESC → back to graph editor
                displayMode = false;
                return true;
            }
            return true;
        }
        if (editor.keyPressed(key, sc, mod)) return true;
        if (key == 256) {
            if (showSettings) { showSettings = false; return true; }
            if (displayMode) { displayMode = false; return true; }
            onClose(); return true;
        }
        if (key >= 32 && key <= 96) return true;
        return super.keyPressed(key, sc, mod);
    }

    @Override public boolean keyReleased(int key, int sc, int mod) {
        if (displayMode) return false;
        return editor.keyReleased(key, sc, mod) || super.keyReleased(key, sc, mod);
    }
    @Override public boolean charTyped(char ch, int mod) {
        if (editor.colorPicker.isVisible()) return editor.colorPicker.charTyped(ch, mod);
        if (showSettings) {
            for (var f : settingFields) if (f.isFocused()) return f.charTyped(ch, mod);
            for (var f : hudSettingFields) if (f.isFocused()) return f.charTyped(ch, mod);
            return false;
        }
        if (displayMode) {
            if (editingS && (Character.isDigit(ch) || ch == '.' || ch == '-')) {
                if (editSBuf.length() < 8) editSBuf += ch; return true;
            }
            if (editingR && (Character.isDigit(ch) || ch == '.' || ch == '-')) {
                if (editRBuf.length() < 8) editRBuf += ch; return true;
            }
            return false;
        }
        return editor.charTyped(ch, mod) || super.charTyped(ch, mod);
    }

    /** 关界面前钩子：只提交尚未同步的局部编辑（EditBox/busBox/频段改名/
     *  进行中的显示区拖拽），全部走定向 op——不再全量上传整图。全量上传会用本客户端
     *  旧快照覆盖服务端图，冲掉其他玩家期间并发的编辑；图数据本身早已由各定向 op
     *  实时同步，服务端才是最新真相。设置面板保持显式 Apply 提交契约（ESC/× 为放弃）。
     *  Pre-close hook: commit only unsynced in-progress edits (EditBox / busBox /
     *  band renames / active display drag) via targeted ops — no whole-graph upload,
     *  which would overwrite the server graph with this client's stale snapshot and
     *  clobber other players' concurrent edits; the graph is already kept in sync live
     *  by targeted ops, so the server holds the truth. The settings panel keeps its
     *  explicit-Apply contract (ESC/× discards). */
    @Override protected void preClose() {
        if (getBE() == null) return;
        // 图编辑区的未提交输入（聚焦 EditBox、TAB 切焦点遗留文本、busBox、频段改名）
        // Pending graph-editor inputs (focused EditBox, text left by TAB focus move, busBox, band renames)
        editor.commitPendingEditsForClose();
        // 显示模式：进行中的拖拽等不到 mouseReleased → 补发最终 op
        // Display mode: an in-progress drag never gets mouseReleased → flush the final op
        if (displayMode) {
            if (layerDragState == LayerDragState.DRAGGING && layerDragNode != null) {
                applyLayerReorder();
            }
            if (draggedDisplayNode != null) {
                sendOp(io.github.y15173334444.create_schematic_compute.graph.GraphOp.setDisplayLayout(
                    blockPos, -1, draggedDisplayNode.id,
                    draggedDisplayNode.layoutX, draggedDisplayNode.layoutY,
                    draggedDisplayNode.displayScale, draggedDisplayNode.displayRotation,
                    draggedDisplayNode.moveScale,
                    minecraft.player.getUUID()));
                draggedDisplayNode = null;
            }
        }
    }

}
