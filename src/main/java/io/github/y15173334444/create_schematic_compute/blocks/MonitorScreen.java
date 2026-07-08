package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.client.GeometryConstants;
import io.github.y15173334444.create_schematic_compute.graph.*;
import io.github.y15173334444.create_schematic_compute.network.BlueprintSavePacket;
import io.github.y15173334444.create_schematic_compute.network.BlueprintTogglePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;
import com.mojang.math.Axis;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.github.y15173334444.create_schematic_compute.client.GeometryConstants.*;

public class MonitorScreen extends AbstractContainerScreen<MonitorMenu> implements GraphEditor.Host {
    private final MonitorBlockEntity blockEntity;
    private final GraphEditor editor;

    // ── Display mode state ──
    private boolean displayMode = false;

    // ── Pixel editor overlay state ──
    private PixelEditState pixelEdit = null;

    // ── Double-click tracking ──
    private long lastClickTime = 0;
    private int lastClickNodeId = -1;

    // ── Dragging state (display mode) ──
    private GraphNode draggedDisplayNode = null;
    private GraphNode selectedDisplayNode = null;
    private float dragOffX, dragOffY;

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
    private boolean pixelDragUndoCaptured = false;

    // ── Display mode inline editing ──
    private boolean editingS = false, editingR = false;
    private String editSBuf = "", editRBuf = "";

    // ── Evaluator cache (reused across display-mode frames) ──
    private NodeGraph lastEvalGraph = null;
    private GraphEvaluator cachedEval = null;
    private java.util.HashMap<Integer, Float> cachedPidState = null;
    private ArrayList<GraphEvaluator.InputSource> cachedEmptyInputs = null;

    // ── Phase 2: Display area render cache ──
    private int lastDisplayGen = -1;
    private float lastDisplaySW = -1, lastDisplaySL = -1;
    private java.util.List<DisplayElement> cachedDisplayElements = null;
    private DisplayArea cachedDisplayArea = null;

    // ── Settings panel state ──
    private boolean showSettings = false;
    private boolean settingsInited = false;
    private net.minecraft.client.gui.components.EditBox[] settingFields;
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

    // ── Fast number formatting to avoid String.format allocation in hot paths (Phase 1) ──
    private static String ff0(float v) { return Integer.toString(Math.round(v)); }
    private static String ff1(float v) { return Float.toString((float)Math.round(v * 10) / 10); }
    private static String ff2(float v) { return Float.toString((float)Math.round(v * 100) / 100); }
    private static String ff3(float v) { return Float.toString((float)Math.round(v * 1000) / 1000); }
    private static String hex8(int v) { String h = Integer.toHexString(v).toUpperCase(); return "00000000".substring(h.length()) + h; }

    private static class PixelEditState {
        GraphNode node;
        int frameIndex; // -1 for IMAGE, 0+ for IMAGE_SEQUENCE
        int selectedColor = 0xFFFFFFFF;
        boolean open;
        int gridOriginX, gridOriginY;
        boolean painting = false; // for drag painting
        boolean newFrameMenuOpen = false; // IMAGE_SEQUENCE "+New" dropdown
        String hexInput = ""; // hex color being typed
        boolean editingHex = false; // hex input active
        // Pixel-only undo stacks (independent of graph-level undo)
        java.util.List<int[]> pixelUndoStack = new java.util.ArrayList<>();
        java.util.List<int[]> pixelRedoStack = new java.util.ArrayList<>();
    }

    public MonitorScreen(MonitorMenu m, Inventory inv, Component t) {
        super(m, inv, t);
        this.blockEntity = m.blockEntity;
        this.imageWidth = 9999;
        this.editor = new GraphEditor(this, this);
        // Settings EditBoxes — values loaded when panel opens (settingsInited flag)
        var mc = Minecraft.getInstance();
        settingFields = new net.minecraft.client.gui.components.EditBox[8];
        for (int i = 0; i < 8; i++) {
            settingFields[i] = new net.minecraft.client.gui.components.EditBox(Minecraft.getInstance().font, 0, 0, 60, 14, Component.literal(""));
            settingFields[i].setMaxLength(8);
        }
        // node filter: only input and display nodes
        editor.setNodeFilter(nt -> nt == NodeType.CONST
            || nt == NodeType.REDSTONE_IN
            || nt == NodeType.PRIVATE_IN
            || nt == NodeType.BUS_IN
            || nt == NodeType.TEXT || nt == NodeType.DATA
            || nt == NodeType.IMAGE || nt == NodeType.IMAGE_SEQUENCE
            || nt == NodeType.COMMENT);
    }

    private MonitorBlockEntity getBE() {
        if (blockEntity != null) return blockEntity;
        if (menu.blockPos != null && minecraft != null && minecraft.level != null) {
            if (minecraft.level.getBlockEntity(menu.blockPos) instanceof MonitorBlockEntity be) return be;
        }
        return null;
    }
    // ── GraphEditor.Host ──
    @Override public NodeGraph getGraph() { MonitorBlockEntity be = getBE(); return be != null ? be.graph : new NodeGraph(); }
    @Override public boolean isRunning() { MonitorBlockEntity be = getBE(); return be != null && be.running; }
    @Override public Map<Integer, Boolean> getFlipflopStates() { MonitorBlockEntity be = getBE(); return be != null ? be.runtimeState.flipflopStates : null; }
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
        if (be != null) { be.running = start; PacketDistributor.sendToServer(new BlueprintTogglePacket(be.getBlockPos(), start)); }
    }

    // ── Undo / Redo (delegates to GraphEditor static methods) ──

    @Override
    public void pushUndoSnapshot() {
        var be = getBE();
        if (be == null || be.getLevel() == null) return;
        try {
            var tag = be.graph.save(be.getLevel().registryAccess());
            GraphEditor.undoStack().add(tag);
            GraphEditor.redoStack().clear();
            while (GraphEditor.undoStack().size() > 50) GraphEditor.undoStack().remove(0);
        } catch (Exception e) {
            SchematicCompute.LOGGER.error("pushUndoSnapshot", e);
        }
    }

    @Override
    public void performUndo() {
        if (pixelEdit != null && pixelEdit.open) { performPixelUndo(); return; }
        var be = getBE();
        if (be == null || be.getLevel() == null) return;
        GraphEditor.performUndo(this, be.getLevel().registryAccess());
    }

    @Override
    public void performRedo() {
        if (pixelEdit != null && pixelEdit.open) { performPixelRedo(); return; }
        var be = getBE();
        if (be == null || be.getLevel() == null) return;
        GraphEditor.performRedo(this, be.getLevel().registryAccess());
    }

    private void performPixelUndo() {
        if (pixelEdit == null || pixelEdit.pixelUndoStack.isEmpty()) return;
        var be = getBE();
        int[] top = pixelEdit.pixelUndoStack.remove(pixelEdit.pixelUndoStack.size() - 1);
        if (top.length == 1) {
            // Count marker: restore full frames list (new-frame undo)
            int count = top[0];
            // Save current frames to redo
            int curCount = pixelEdit.node.imageSequenceFrames.size();
            for (int i = curCount - 1; i >= 0; i--)
                pixelEdit.pixelRedoStack.add(pixelEdit.node.imageSequenceFrames.get(i).clone());
            pixelEdit.pixelRedoStack.add(new int[]{curCount});
            // Restore old frames
            pixelEdit.node.imageSequenceFrames.clear();
            for (int i = 0; i < count; i++)
                pixelEdit.node.imageSequenceFrames.add(0, pixelEdit.pixelUndoStack.remove(pixelEdit.pixelUndoStack.size() - 1));
            if (pixelEdit.frameIndex >= pixelEdit.node.imageSequenceFrames.size())
                pixelEdit.frameIndex = pixelEdit.node.imageSequenceFrames.size() - 1;
            if (pixelEdit.frameIndex >= 0)
                pixelEdit.node.imagePixels = pixelEdit.node.imageSequenceFrames.get(pixelEdit.frameIndex);
        } else {
            // Single frame undo (paint operation)
            pixelEdit.pixelRedoStack.add(pixelEdit.node.imagePixels.clone());
            pixelEdit.node.imagePixels = top;
            if (pixelEdit.frameIndex >= 0 && pixelEdit.node.type == NodeType.IMAGE_SEQUENCE
                && pixelEdit.node.imageSequenceFrames != null
                && pixelEdit.frameIndex < pixelEdit.node.imageSequenceFrames.size()) {
                pixelEdit.node.imageSequenceFrames.set(pixelEdit.frameIndex, top);
            }
        }
        if (be != null) be.graph.bumpGeneration();
    }

    private void performPixelRedo() {
        if (pixelEdit == null || pixelEdit.pixelRedoStack.isEmpty()) return;
        var be = getBE();
        pixelEdit.pixelUndoStack.add(pixelEdit.node.imagePixels.clone());
        pixelEdit.node.imagePixels = pixelEdit.pixelRedoStack.remove(pixelEdit.pixelRedoStack.size() - 1);
        if (pixelEdit.frameIndex >= 0 && pixelEdit.node.type == NodeType.IMAGE_SEQUENCE
            && pixelEdit.node.imageSequenceFrames != null
            && pixelEdit.frameIndex < pixelEdit.node.imageSequenceFrames.size()) {
            pixelEdit.node.imageSequenceFrames.set(pixelEdit.frameIndex, pixelEdit.node.imagePixels);
        }
        if (be != null) be.graph.bumpGeneration();
    }

    @Override
    protected void renderBg(GuiGraphics g, float pt, int mx, int my) {
        if (displayMode) {
            renderDisplayArea(g, mx, my);
            renderLayerPanel(g, mx, my);
        } else {
            editor.renderBg(g, mx, my);
            renderDisplayToggleButton(g);
        }
        // Pixel editor overlay renders on top of either mode
        if (pixelEdit != null && pixelEdit.open) {
            renderPixelEditor(g, mx, my);
        }
        // Settings panel overlay
        if (showSettings) {
            renderSettingsPanel(g, mx, my);
        }
    }

    // ── Display area rendering ──
    private record DisplayArea(int x, int y, int w, int h) {}
    private DisplayArea computeDisplayArea() {
        int margin = MONITOR_MARGIN;
        int topOffset = MONITOR_TOOLBAR_H + 6; // toolbar + gap
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

    private GraphEvaluator getCachedEvaluator(NodeGraph graph) {
        if (cachedEval == null || lastEvalGraph != graph) {
            lastEvalGraph = graph;
            cachedEval = new GraphEvaluator(graph);
            if (cachedPidState == null) cachedPidState = new java.util.HashMap<>();
            cachedPidState.clear();
        }
        // Build InputSource list from synced redstone inputs (reuse list to avoid allocation)
        if (cachedEmptyInputs == null) cachedEmptyInputs = new ArrayList<>();
        cachedEmptyInputs.clear();
        for (var n : graph.nodes) {
            if (n.type == NodeType.REDSTONE_IN) {
                long fk = io.github.y15173334444.create_schematic_compute.ModUtils.freqKey(n.itemParams);
                int sig = getBE() != null ? getBE().getRedstoneInput(fk) : 0;
                cachedEmptyInputs.add(new GraphEvaluator.InputSource(fk, sig));
            }
        }
        cachedEval.evaluate(cachedEmptyInputs, cachedPidState, 0.05f);
        return cachedEval;
    }

    private void renderDisplayArea(GuiGraphics g, int mx, int my) {
        var da = computeDisplayArea();
        int w = width, h = height;

        // Dark background + grid (matching node editor style)
        g.fill(0, 0, w, h, 0xFF1F1E1A);
        int gs = 30;
        for (int gx = da.x; gx < da.x + da.w; gx += gs)
            g.fill(gx, da.y, gx + 1, da.y + da.h, 0xFF2C2A24);
        for (int gy = da.y; gy < da.y + da.h; gy += gs)
            g.fill(da.x, gy, da.x + da.w, gy + 1, 0xFF2C2A24);
        g.renderOutline(da.x, da.y, da.w, da.h, 0xFF3A3A3A);

        // Local evaluation to get display values (cached across frames)
        var graph = getBE() != null ? getBE().graph : new NodeGraph();
        var localEval = getCachedEvaluator(graph);

        // Collect and render display elements (cached when graph is static — Phase 2)
        // When running, output values change each tick so we must rebuild.
        boolean isRunning = getBE() != null && getBE().running;
        float efsw = getEffectiveScreenW(), efsl = getEffectiveScreenL();
        int curGen = graph.graphGeneration;
        boolean displayChanged = curGen != lastDisplayGen || efsw != lastDisplaySW || efsl != lastDisplaySL
            || isRunning || draggedDisplayNode != null;
        if (displayChanged || cachedDisplayElements == null) {
            lastDisplayGen = curGen; lastDisplaySW = efsw; lastDisplaySL = efsl;
            cachedDisplayElements = collectDisplayElements(graph, localEval);
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
            float elemW = (elem.type == NodeType.IMAGE || elem.type == NodeType.IMAGE_SEQUENCE) ? IMAGE_GRID * IMAGE_CELL_FONT
                : Minecraft.getInstance().font.width(elem.text.isEmpty() && elem.type != NodeType.DATA ? " " :
                    elem.type == NodeType.DATA ? ff1(elem.value) : elem.text);
            float elemH = (elem.type == NodeType.IMAGE || elem.type == NodeType.IMAGE_SEQUENCE) ? IMAGE_GRID * IMAGE_CELL_FONT : 10;
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
                        renderPixels(g, elem.pixels, 0, 0, 2, 16);
                    }
                }
            }
            pose.popPose();
        }
        // ── Selection highlight: draw AFTER all elements so it's always on top ──
        if (selectedDisplayNode != null) {
            for (var elem : elements) {
                if (selectedDisplayNode.id != elem.nodeId) continue;
                float s = guiScale * elem.scale;
                float elemW = (elem.type == NodeType.IMAGE || elem.type == NodeType.IMAGE_SEQUENCE) ? IMAGE_GRID * IMAGE_CELL_FONT
                    : Minecraft.getInstance().font.width(elem.text.isEmpty() && elem.type != NodeType.DATA ? " " :
                        elem.type == NodeType.DATA ? ff1(elem.value) : elem.text);
                float elemH = (elem.type == NodeType.IMAGE || elem.type == NodeType.IMAGE_SEQUENCE) ? IMAGE_GRID * IMAGE_CELL_FONT : 10;
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
                g.renderOutline(hx, hy, hw, hh, 0xFFFFAA44);
                pose.popPose();
                break;
            }
        }
        // ── Toolbar at screen top (fixed position) ──
        int tbx = 4, tby = 4, tbh = MONITOR_TOOLBAR_H;
        g.fill(0, tby, width, tby + tbh, 0xFF2A2822);
        // < Graph
        g.fill(tbx, tby, tbx + 56, tby + tbh, 0xFF3A3832);
        g.renderOutline(tbx, tby, 56, tbh, 0xFF8B7533);
        g.drawString(Minecraft.getInstance().font, I18n.get("gui.create_schematic_compute.monitor.back_graph"), tbx + 6, tby + 5, 0xFFFFFFFF, false);
        tbx += 62;
        // Settings
        g.fill(tbx, tby, tbx + 56, tby + tbh, showSettings ? 0xFF3A5A2A : 0xFF3A3832);
        g.renderOutline(tbx, tby, 56, tbh, 0xFF8B7533);
        g.drawString(Minecraft.getInstance().font, I18n.get("gui.create_schematic_compute.monitor.settings"), tbx + 6, tby + 5, 0xFFFFFFFF, false);
        tbx += 62;

        // Selected element editing (clickable S/R values)
        if (selectedDisplayNode != null) {
            String sTxt = "§6S:";
            if (editingS) sTxt += "§e" + editSBuf + "▌";
            else sTxt += "§e" + ff1(selectedDisplayNode.displayScale);
            g.drawString(Minecraft.getInstance().font, sTxt, tbx + 4, tby + 5, 0xFFFFAA44, false);
            tbx += Minecraft.getInstance().font.width(sTxt) + 12;
            String rTxt = "§6R:";
            if (editingR) rTxt += "§e" + editRBuf + "▌";
            else rTxt += "§e" + ff0(selectedDisplayNode.displayRotation);
            g.drawString(Minecraft.getInstance().font, rTxt, tbx + 4, tby + 5, 0xFFFFAA44, false);
        }

        // Hover hints (use rotated AABB for accuracy, with bounding-box clamp)
        if (selectedDisplayNode == null) {
            var font2 = Minecraft.getInstance().font;
            for (var elem : elements) {
                float s = guiScale * elem.scale;
                float hitW, hitH;
                if (elem.type == NodeType.IMAGE || elem.type == NodeType.IMAGE_SEQUENCE) {
                    hitW = IMAGE_GRID * IMAGE_CELL_FONT; hitH = IMAGE_GRID * IMAGE_CELL_FONT;
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
        g.fill(x, y, x + size, y + size, 0xFF1A1814);

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
                var graph = getBE() != null ? getBE().graph : new NodeGraph();
                var eval = getCachedEvaluator(graph);
                float val = graph.getInputValue(node.id, 0, eval.getCurrentOutputs());
                String valStr = ff1(val);
                int dc = node.textColor != 0 ? node.textColor : 0xFF88FF88;
                int tw = Minecraft.getInstance().font.width(valStr);
                g.drawString(Minecraft.getInstance().font, valStr,
                    x + (size - tw) / 2, y + (size - 8) / 2, dc, false);
            }
            case IMAGE -> {
                if (node.imagePixels != null) {
                    int cellSz = 1;
                    int offsetX = x + (size - 16 * cellSz) / 2;
                    int offsetY = y + (size - 16 * cellSz) / 2;
                    renderPixels(g, node.imagePixels, offsetX, offsetY, cellSz, 16);
                }
            }
            case IMAGE_SEQUENCE -> {
                var graph = getBE() != null ? getBE().graph : new NodeGraph();
                var eval = getCachedEvaluator(graph);
                int frameIdx = Math.round(graph.getInputValue(node.id, 2, eval.getCurrentOutputs()));
                int[] pixels = null;
                if (node.imageSequenceFrames != null && !node.imageSequenceFrames.isEmpty()) {
                    frameIdx = Math.max(0, Math.min(frameIdx, node.imageSequenceFrames.size() - 1));
                    pixels = node.imageSequenceFrames.get(frameIdx);
                }
                if (pixels != null) {
                    int cellSz = 1;
                    int offsetX = x + (size - 16 * cellSz) / 2;
                    int offsetY = y + (size - 16 * cellSz) / 2;
                    renderPixels(g, pixels, offsetX, offsetY, cellSz, 16);
                }
                // "S" badge at top-right of thumbnail
                int badgeX = x + size - 7;
                int badgeY = y + 1;
                g.fill(badgeX, badgeY, badgeX + 6, badgeY + 6, 0xFF3A3A3A);
                g.renderOutline(badgeX, badgeY, 6, 6, 0xFF8B7533);
                g.drawString(Minecraft.getInstance().font, "S", badgeX + 1, badgeY, 0xFFFFAA44, false);
            }
        }
    }

    private void renderLayerPanel(GuiGraphics g, int mx, int my) {
        var graph = getBE() != null ? getBE().graph : new NodeGraph();
        List<GraphNode> layers = getDisplayLayers(graph);
        if (layers.isEmpty()) return;

        int px = width - LAYER_PANEL_W - LAYER_PANEL_PADDING;
        int py = 26;
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
        g.fill(px + 1, py + 1, px + LAYER_PANEL_W - 1, py + titleH + 1, 0xFF2A2822);
        String title = "Layers";
        int titleW = Minecraft.getInstance().font.width(title);
        g.drawString(Minecraft.getInstance().font, title,
            px + (LAYER_PANEL_W - titleW) / 2, py + 2, 0xFF8B7533, false);

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
            g.fill(px + 2, dropIndicatorY - 1, px + LAYER_PANEL_W - 2, dropIndicatorY + 1, 0xFFFFAA44);
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
                case TEXT -> "T"; case DATA -> "D"; case IMAGE -> "I"; case IMAGE_SEQUENCE -> "S"; default -> "?";
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
            g.renderOutline(px + 2, ghostY, LAYER_PANEL_W - 4, LAYER_ROW_H, 0xFFFFAA44);
            int ghostThumbX = px + LAYER_PANEL_PADDING;
            int ghostThumbY = ghostY + (LAYER_ROW_H - LAYER_THUMB_SIZE) / 2;
            renderLayerThumbnail(g, layerDragNode, ghostThumbX, ghostThumbY, LAYER_THUMB_SIZE);
            String ghostIcon = switch (layerDragNode.type) {
                case TEXT -> "T"; case DATA -> "D"; case IMAGE -> "I"; case IMAGE_SEQUENCE -> "S"; default -> "?";
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
            g.fill(sbX, sbY, sbX + 6, sbY + sbH, 0xFF2A2822);
            float thumbH = Math.max(20, (float) visibleRows / layers.size() * sbH);
            float thumbY = sbY + (float) layerScroll / maxScroll * (sbH - thumbH);
            g.fill(sbX + 1, (int) thumbY, sbX + 5, (int) (thumbY + thumbH), 0xFF8B7533);
        }
    }

    /** Returns clicked layer index in full sorted list, or -1 if no hit */
    private int handleLayerPanelClick(double mx, double my) {
        int px = width - LAYER_PANEL_W - LAYER_PANEL_PADDING;
        if (mx < px || mx > px + LAYER_PANEL_W) return -1;

        var graph = getBE() != null ? getBE().graph : new NodeGraph();
        List<GraphNode> layers = getDisplayLayers(graph);
        if (layers.isEmpty()) return -1;

        int titleH = 12;
        int rowStartY = 26 + titleH + 2;
        int maxRows = Math.max(1, (height - rowStartY - 4) / LAYER_ROW_H);
        if (my < rowStartY || my > rowStartY + maxRows * LAYER_ROW_H) return -1;

        int idx = layerScroll + (int)((my - rowStartY) / LAYER_ROW_H);
        if (idx < 0 || idx >= layers.size()) return -1;

        selectedDisplayNode = layers.get(idx);
        return idx;
    }

    // ── Layer drag-and-drop helpers ──

    private void updateLayerDropIndex(double my) {
        var graph = getBE() != null ? getBE().graph : new NodeGraph();
        List<GraphNode> layers = getDisplayLayers(graph);
        if (layers.isEmpty()) return;

        int titleH = 12;
        int rowStartY = 26 + titleH + 2;
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
        int rowStartY = 26 + titleH + 2;
        int maxRows = Math.max(1, (height - rowStartY - 4) / LAYER_ROW_H);
        int panelBottom = rowStartY + maxRows * LAYER_ROW_H;
        long now = System.currentTimeMillis();
        if (now - lastAutoScrollTime < LAYER_AUTOSCROLL_TICK) return;

        var graph = getBE() != null ? getBE().graph : new NodeGraph();
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
        var graph = getBE() != null ? getBE().graph : new NodeGraph();
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
        saveGraph();
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
        int pw = MONITOR_SETTINGS_PANEL_W, ph = 56 + 8 * 20 + 30;
        int px = (width - pw) / 2, py = (height - ph) / 2;
        g.fill(px, py, px + pw, py + ph, 0xFF2A2822);
        g.renderOutline(px, py, pw, ph, 0xFF5A4D3A);
        g.fill(px + 2, py + 2, px + pw - 2, py + 18, 0xFF4A3F28);
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
            settingsInited = true;
        }

        // EditBoxes
        int ey = py + 24;
        for (int i = 0; i < 8; i++) {
            g.drawString(Minecraft.getInstance().font, "§7" + I18n.get(SETTING_KEYS[i]) + ":", px + 10, ey + 2, 0xFFCCCCCC, false);
            var f = settingFields[i];
            f.setX(px + 110); f.setY(ey);
            f.render(g, mx, my, 0);
            ey += 20;
        }

        // Live preview: parse current field values into overrides so the display area updates in real-time
        try {
            previewScreenW = Float.parseFloat(settingFields[0].getValue().trim());
            previewScreenL = Float.parseFloat(settingFields[1].getValue().trim());
        } catch (Exception e) { previewScreenW = -1; previewScreenL = -1; }

        // Save button
        int svX = px + 10, svY = ey + 8;
        g.fill(svX, svY, svX + 200, svY + 18, 0xFF3A5A2A);
        g.renderOutline(svX, svY, 200, 18, 0xFF5A8A3A);
        g.drawString(Minecraft.getInstance().font, "§a" + I18n.get("gui.create_schematic_compute.monitor.save_close"), svX + 60, svY + 4, 0xFFFFFFFF, false);
    }

    /** 保存所有设置并关闭面板 */
    private void saveAllSettings() {
        if (blockEntity == null) return;
        try {
            float w = Float.parseFloat(settingFields[0].getValue().trim());
            float l = Float.parseFloat(settingFields[1].getValue().trim());
            float x = Float.parseFloat(settingFields[2].getValue().trim());
            float y = Float.parseFloat(settingFields[3].getValue().trim());
            float z = Float.parseFloat(settingFields[4].getValue().trim());
            float r = Float.parseFloat(settingFields[5].getValue().trim());
            float p = Float.parseFloat(settingFields[6].getValue().trim());
            float yw = Float.parseFloat(settingFields[7].getValue().trim());
            saveGraph(); // sync graph to server before settings trigger a block update
            var pkt = new io.github.y15173334444.create_schematic_compute.network.MonitorSettingsPacket(
                getBE().getBlockPos(), w, l, x, y, z, r, p, yw);
            PacketDistributor.sendToServer(pkt);
        } catch (Exception e) { SchematicCompute.LOGGER.warn("Failed to parse monitor settings", e); }
        previewScreenW = -1; previewScreenL = -1;
        showSettings = false; settingsInited = false;
    }

    private boolean handleSettingsClick(double mx, double my, int btn) {
        if (btn != 0) return false;
        int pw = MONITOR_SETTINGS_PANEL_W, ph = 56 + 8 * 20 + 30;
        int px = (width - pw) / 2, py = (height - ph) / 2;
        // Close button
        if (mx >= px + pw - 18 && mx <= px + pw - 2 && my >= py + 2 && my <= py + 18) {
            previewScreenW = -1; previewScreenL = -1;
            showSettings = false; settingsInited = false; return true;
        }
        // Save button
        int ey = py + 24 + 8 * 20;
        int svX = px + 10, svY = ey + 8;
        if (mx >= svX && mx <= svX + 200 && my >= svY && my <= svY + 18 && blockEntity != null) {
            saveAllSettings();
            return true;
        }
        // EditBox focus: clear all first, then focus the clicked one
        for (int i = 0; i < 8; i++) settingFields[i].setFocused(false);
        for (int i = 0; i < 8; i++) {
            var f = settingFields[i];
            if (mx >= f.getX() && mx <= f.getX() + 60 && my >= f.getY() && my <= f.getY() + 14) {
                f.setFocused(true); f.mouseClicked(mx, my, btn); break;
            }
        }
        return true;
    }

    private record DisplayElement(int nodeId, NodeType type, String text, float value, int[] pixels,
        String label, float x, float y, float scale, float rotation, int color) {}

    private java.util.List<DisplayElement> collectDisplayElements(NodeGraph graph, GraphEvaluator eval) {
        var list = new java.util.ArrayList<DisplayElement>();
        for (var n : graph.nodes) {
            switch (n.type) {
                case TEXT -> {
                    int tc = n.textColor != 0 ? n.textColor : 0xFFCCCCCC;
                    list.add(new DisplayElement(n.id, n.type, n.displayText, 0, null, "", n.layoutX, n.layoutY, n.displayScale, n.displayRotation, tc));
                }
                case DATA -> {
                    float val = graph.getInputValue(n.id, 0, eval.getCurrentOutputs());
                    String lbl = n.params.length > 0 ? ff3(n.params[0]) : "val";
                    int dc = n.textColor != 0 ? n.textColor : 0xFF88FF88;
                    list.add(new DisplayElement(n.id, n.type, "", val, null, lbl, n.layoutX, n.layoutY, n.displayScale, n.displayRotation, dc));
                }
                case IMAGE -> {
                    float ox = graph.getInputValue(n.id, 0, eval.getCurrentOutputs());
                    float oy = graph.getInputValue(n.id, 1, eval.getCurrentOutputs());
                    float rotIn = graph.getInputValue(n.id, 2, eval.getCurrentOutputs());
                    float msX = n.params.length > 0 ? n.params[0] : 0.01f;
                    float msY = n.params.length > 1 ? n.params[1] : 0.01f;
                    float rotScale = n.params.length > 2 ? n.params[2] : 1f;
                    boolean invX = n.params.length > 3 && n.params[3] > 0.5f;
                    boolean invY = n.params.length > 4 && n.params[4] > 0.5f;
                    float dx = ox * (invX ? -msX : msX);
                    float dy = oy * (invY ? -msY : msY);
                    float effRot = n.displayRotation + rotIn * rotScale;
                    float[] cp = clampImageNorm(n, n.layoutX + dx, n.layoutY + dy, effRot);
                    list.add(new DisplayElement(n.id, n.type, "", 0, n.imagePixels, "", cp[0], cp[1], n.displayScale, effRot, 0));
                }
                case IMAGE_SEQUENCE -> {
                    float ox = graph.getInputValue(n.id, 0, eval.getCurrentOutputs());
                    float oy = graph.getInputValue(n.id, 1, eval.getCurrentOutputs());
                    int frameIdx = Math.round(graph.getInputValue(n.id, 2, eval.getCurrentOutputs()));
                    float rotIn = graph.getInputValue(n.id, 3, eval.getCurrentOutputs());
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
                    list.add(new DisplayElement(n.id, n.type, "", 0, pixels, "", cp[0], cp[1], n.displayScale, effRot, 0));
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
     *  matching MonitorBlockEntityRenderer's clamping (lines 124-133). */
    /** Clamp IMAGE normalized position — delegates to shared GeometryConstants. */
    private float[] clampImageNorm(GraphNode n, float rawX, float rawY, float rotation) {
        return GeometryConstants.clampImageNorm(n.displayScale, rawX, rawY, rotation,
            getEffectiveScreenW(), getEffectiveScreenL());
    }

    /** Compute rotated AABB — delegates to shared GeometryConstants. */
    private static float[] elemRotAABB(float ex, float ey, float w, float h, float rot) {
        return GeometryConstants.elemRotAABB(ex, ey, w, h, rot);
    }

    private void renderPixels(GuiGraphics g, int[] pixels, int x, int y, int cellSize, int gridSize) {
        for (int py = 0; py < gridSize; py++) {
            for (int px = 0; px < gridSize; px++) {
                int idx = py * gridSize + px;
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
        g.renderOutline(x, y, 14, 14, 0xFF5A4D3A);
        g.drawString(Minecraft.getInstance().font, label, x + 1, y + 3, h ? 0xFFFFFF88 : 0xFFCCCCCC, false);
    }

    // ── Display toggle button (graph editor mode) ──
    private void renderDisplayToggleButton(GuiGraphics g) {
        var mc = Minecraft.getInstance();
        int btnX = width - 76, btnY = 4, btnW = 60, btnH = 18;
        g.fill(btnX, btnY, btnX + btnW, btnY + btnH, 0xFF3A3832);
        g.renderOutline(btnX, btnY, btnW, btnH, 0xFF8B7533);
        g.renderOutline(btnX + 1, btnY + 1, btnW - 2, btnH - 2, 0xFF2A2822);
        g.drawString(Minecraft.getInstance().font, I18n.get("gui.create_schematic_compute.monitor.display"), btnX + 6, btnY + 4, 0xFFFFFFFF, false);
    }

    // ── Pixel editor overlay ──
    private void renderPixelEditor(GuiGraphics g, int mx, int my) {
        if (pixelEdit == null || pixelEdit.node == null) return;
        var mc = Minecraft.getInstance();
        int w = width, h = height;
        int fh = Minecraft.getInstance().font.lineHeight;

        // Layout constants (2-column palette, hex input at top-right)
        final int PAL_CELL = PALETTE_CELL, PAL_GAP = PALETTE_GAP, PAL_LEFT = PALETTE_LEFT, PAL_COLS = PALETTE_COLS;
        int palNumColors = 23; // 22 colors + sentinel 0
        int palRows = (palNumColors + PAL_COLS - 1) / PAL_COLS; // 12
        int palH = palRows * (PAL_CELL + PAL_GAP);
        int palW2 = PAL_CELL * PAL_COLS + PAL_GAP * (PAL_COLS - 1);
        int palAreaW = PAL_LEFT + palW2 + 12;
        int maxPx = (int)(Math.min((w - palAreaW) * 0.65f, (h - 40) * 0.72f));
        int cellSize = Math.max(6, maxPx / 16);
        int gridPx = cellSize * 16;
        int ox = palAreaW + (w - palAreaW - gridPx) / 2;
        int palStartY = (h - palH) / 2;
        int oy = (h - gridPx) / 2;

        pixelEdit.gridOriginX = ox;
        pixelEdit.gridOriginY = oy;

        // Dim background
        g.fill(0, 0, w, h, 0xAA000000);

        // Grid background with border
        g.fill(ox - 4, oy - 4, ox + gridPx + 4, oy + gridPx + 4, 0xFF2A2822);
        g.renderOutline(ox - 4, oy - 4, gridPx + 8, gridPx + 8, 0xFF5A4D3A);

        // Draw pixels with actual alpha (transparent → checkerboard visible)
        int[] pixels = pixelEdit.node.imagePixels;
        for (int py = 0; py < 16; py++) {
            for (int px = 0; px < 16; px++) {
                int idx = py * 16 + px;
                int color = (idx < pixels.length) ? pixels[idx] : 0;
                int x1 = ox + px * cellSize, y1 = oy + py * cellSize;
                int x2 = x1 + cellSize, y2 = y1 + cellSize;
                if ((color & 0xFF000000) == 0) {
                    int ck = ((px + py) & 1) * 0x222222;
                    g.fill(x1, y1, x2, y2, 0xFF222222 + ck);
                } else {
                    g.fill(x1, y1, x2, y2, color);
                }
                g.renderOutline(x1, y1, cellSize, cellSize, 0xFF444444);
            }
        }

        // Palette (2-column grid)
        int[] palette = PIXEL_PALETTE;
        for (int i = 0; i < palette.length; i++) {
            int col = i % PAL_COLS, row = i / PAL_COLS;
            int px = PAL_LEFT + col * (PAL_CELL + PAL_GAP);
            int py = palStartY + row * (PAL_CELL + PAL_GAP);
            g.fill(px, py, px + PAL_CELL, py + PAL_CELL, palette[i]);
            g.renderOutline(px, py, PAL_CELL, PAL_CELL, pixelEdit.selectedColor == palette[i] ? 0xFFFFAA44 : 0xFF666666);
        }

        // Frame navigation + Hex color (screen top-right)
        int trX = w - 240, trY = 6;
        if (pixelEdit.node.type == NodeType.IMAGE_SEQUENCE) {
            String navTxt = "§7◀  " + pixelEdit.frameIndex + "  ▶";
            g.drawString(Minecraft.getInstance().font, navTxt, trX, trY, 0xFFCCCCCC, false);
            g.drawString(Minecraft.getInstance().font, "§a▸ " + I18n.get("gui.create_schematic_compute.monitor.pixel_new"), trX + 120, trY, 0xFFCCCCCC, false);
            if (pixelEdit.newFrameMenuOpen) {
                g.fill(trX + 110, trY + 12, trX + 210, trY + 34, 0xFF2A2822);
                g.renderOutline(trX + 110, trY + 12, 100, 22, 0xFF5A4D3A);
                g.drawString(Minecraft.getInstance().font, "§7" + I18n.get("gui.create_schematic_compute.monitor.pixel_blank"), trX + 116, trY + 14, 0xFFCCCCCC, false);
                g.drawString(Minecraft.getInstance().font, "§7" + I18n.get("gui.create_schematic_compute.monitor.pixel_from_current"), trX + 116, trY + 24, 0xFFCCCCCC, false);
            }
        }
        // Hex color + OK button (always visible, below nav)
        int hexTopY = pixelEdit.node.type == NodeType.IMAGE_SEQUENCE ? trY + 24 : trY;
        String hexStr = pixelEdit.editingHex ? ("§e#" + pixelEdit.hexInput + "▌") : ("§7#" + hex8(pixelEdit.selectedColor));
        g.drawString(Minecraft.getInstance().font, hexStr, trX, hexTopY, 0xFFCCCCCC, false);
        if (pixelEdit.editingHex) {
            int okX = trX + Minecraft.getInstance().font.width(hexStr) + 8;
            g.fill(okX, hexTopY - 1, okX + 30, hexTopY + 11, 0xFF3A5A2A);
            g.renderOutline(okX, hexTopY - 1, 30, 12, 0xFF5A8A3A);
            g.drawString(Minecraft.getInstance().font, "§a" + I18n.get("gui.create_schematic_compute.ok"), okX + 4, hexTopY, 0xFFFFFFFF, false);
        }

        // Close hint (bottom center)
        String hint = "§7" + I18n.get("gui.create_schematic_compute.monitor.pixel_close_hint");
        g.drawString(Minecraft.getInstance().font, hint, (w - Minecraft.getInstance().font.width(hint)) / 2, h - 20, 0xFF888888, false);
    }

    private void openPixelEditor(GraphNode node) {
        if (node.type != NodeType.IMAGE && node.type != NodeType.IMAGE_SEQUENCE) return;
        var state = new PixelEditState();
        state.node = node;
        state.open = true;
        state.frameIndex = -1;
        // Pre-compute grid origin for first-click accuracy (match render layout)
        int palW2 = PALETTE_CELL * PALETTE_COLS + PALETTE_GAP * (PALETTE_COLS - 1);
        int palAreaW = PALETTE_LEFT + palW2 + 12;
        int maxPx = (int)(Math.min((width - palAreaW) * 0.65f, height * 0.72f));
        int cellSize = Math.max(8, maxPx / 16);
        state.gridOriginX = palAreaW + (width - palAreaW - cellSize * 16) / 2;
        state.gridOriginY = (height - cellSize * 16) / 2;
        if (node.type == NodeType.IMAGE_SEQUENCE) {
            state.frameIndex = 0;
            if (node.imageSequenceFrames == null || node.imageSequenceFrames.isEmpty()) {
                node.imageSequenceFrames = new java.util.ArrayList<>();
                // Start with one frame
                int[] frame = new int[256];
                java.util.Arrays.fill(frame, 0x00000000);
                node.imageSequenceFrames.add(frame);
            }
            // Link imagePixels to frame 0 so painting targets the correct array
            node.imagePixels = node.imageSequenceFrames.get(0);
        }
        pixelEdit = state;
    }

    // ── Input handling ──
    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        // Settings panel takes priority
        if (showSettings) {
            return handleSettingsClick(mx, my, btn);
        }
        // Pixel editor takes priority
        if (pixelEdit != null && pixelEdit.open) {
            return handlePixelEditorClick(mx, my, btn);
        }
        if (displayMode) {
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
        if (btn == 0 && mx >= width - 76 && mx <= width - 16 && my >= 4 && my <= 22) {
            displayMode = true;
            return true;
        }
        // Double-click IMAGE/IMAGE_SEQUENCE node → open pixel editor
        // (exclude expand-indicator area to avoid conflict with expand toggle)
        if (btn == 0 && getBE() != null) {
            long now = System.currentTimeMillis();
            GraphNode clicked = null;
            float hitSx = 0, hitSy = 0;
            for (var n : getBE().graph.nodes) {
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
            int tby = 4, tbh = MONITOR_TOOLBAR_H;
            // < Graph
            if (mx >= 4 && mx <= 60 && my >= tby && my <= tby + tbh)
                { displayMode = false; selectedDisplayNode = null; return true; }
            // Settings
            if (mx >= 66 && mx <= 122 && my >= tby && my <= tby + tbh)
                { showSettings = true; return true; }

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
            var graph = getBE() != null ? getBE().graph : new NodeGraph();
            var localEval = getCachedEvaluator(graph);
            var elements = collectDisplayElements(graph, localEval);
            float guiScale2 = da.w * FONT_BLOCK_SCALE / Math.max(getContentWorldW(), 0.01f);
            var ci = getContentArea(da);
            int contentX = ci[0], contentY = ci[1], contentW = ci[2], contentH = ci[3];

            for (int i = elements.size() - 1; i >= 0; i--) {
                var elem = elements.get(i);
                float s = guiScale2 * elem.scale;
                float hitW, hitH;
                var font2 = Minecraft.getInstance().font;
                if (elem.type == NodeType.IMAGE || elem.type == NodeType.IMAGE_SEQUENCE) {
                    hitW = IMAGE_GRID * IMAGE_CELL_FONT; hitH = IMAGE_GRID * IMAGE_CELL_FONT;
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
                        dragOffX = (float)(mx - ex);
                        dragOffY = (float)(my - ey);
                        return true;
                    }
                }
            }
            // No element hit — if already selected via layer panel, start dragging it
            if (selectedDisplayNode != null) {
                draggedDisplayNode = selectedDisplayNode;
                var da2 = computeDisplayArea();
                var ci2 = getContentArea(da2);
                float sx = ci2[0] + selectedDisplayNode.layoutX * ci2[2];
                float sy = ci2[1] + selectedDisplayNode.layoutY * ci2[3];
                dragOffX = (float)(mx - sx);
                dragOffY = (float)(my - sy);
                return true;
            }
            selectedDisplayNode = null;
        }
        return false;
    }

    @Override
    public void mouseMoved(double mx, double my) {
        if (pixelEdit != null && pixelEdit.open) {
            if (pixelEdit.painting) {
                // Recalculate grid (same as renderPixelEditor)
                final int PAL_CELL = PALETTE_CELL, PAL_GAP = PALETTE_GAP, PAL_LEFT = PALETTE_LEFT, PAL_COLS = PALETTE_COLS;
                int palW2 = PAL_CELL * PAL_COLS + PAL_GAP * (PAL_COLS - 1);
                int palAreaW = PAL_LEFT + palW2 + 12;
                int cs = Math.max(6, (int)(Math.min((width - palAreaW) * 0.65f, (height - 40) * 0.72f)) / 16);
                int gp = cs * 16;
                int ox = palAreaW + (width - palAreaW - gp) / 2, oy = (height - gp) / 2;
                if (mx >= ox && mx < ox + gp && my >= oy && my < oy + gp) {
                    int px = (int)((mx - ox) / cs);
                    int py = (int)((my - oy) / cs);
                    if (px >= 0 && px < 16 && py >= 0 && py < 16) {
                        int idx = py * 16 + px;
                        if (pixelEdit.node.imagePixels != null && idx < pixelEdit.node.imagePixels.length) {
                            if (!pixelDragUndoCaptured) { if (pixelEdit.pixelUndoStack.size() < 100) { pixelEdit.pixelUndoStack.add(pixelEdit.node.imagePixels.clone()); pixelEdit.pixelRedoStack.clear(); } pixelDragUndoCaptured = true; }
                            pixelEdit.node.imagePixels[idx] = pixelEdit.selectedColor;
                            if (blockEntity != null) getBE().graph.bumpGeneration();
                        }
                    }
                }
            }
            return;
        }
        if (displayMode) {
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
                var da = computeDisplayArea();
                float gsD = da.w * FONT_BLOCK_SCALE / Math.max(getContentWorldW(), 0.01f);
                float sD = gsD * draggedDisplayNode.displayScale;
                float eW, eH;
                if (draggedDisplayNode.type == NodeType.IMAGE || draggedDisplayNode.type == NodeType.IMAGE_SEQUENCE) {
                    eW = IMAGE_GRID * IMAGE_CELL_FONT; eH = IMAGE_GRID * IMAGE_CELL_FONT;
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
            }
            return;
        }
        editor.mouseMoved(mx, my);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) {
        if (pixelEdit != null && pixelEdit.open) return super.mouseDragged(mx, my, btn, dx, dy);
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
            // Display-area dragging (existing behavior)
            if (draggedDisplayNode != null) return true;
            return super.mouseDragged(mx, my, btn, dx, dy);
        }
        return editor.mouseDragged(mx, my, btn, dx, dy) || super.mouseDragged(mx, my, btn, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int btn) {
        if (pixelEdit != null && pixelEdit.open) { pixelEdit.painting = false; pixelDragUndoCaptured = false; return false; }
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
            draggedDisplayNode = null;
            return true;
        }
        editor.mouseReleased(mx, my, btn);
        return super.mouseReleased(mx, my, btn);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (pixelEdit != null && pixelEdit.open) return true;
        if (displayMode) {
            int px = width - LAYER_PANEL_W - LAYER_PANEL_PADDING;
            if (mx >= px && mx <= px + LAYER_PANEL_W) { layerScroll += (sy > 0) ? -1 : 1; }
            return true;
        }
        return editor.mouseScrolled(mx, my, sx, sy);
    }

    private boolean handlePixelEditorClick(double mx, double my, int btn) {
        if (pixelEdit == null || !pixelEdit.open) return false;
        if (btn != 0) return false;

        // Recalculate layout (same as render)
        final int PAL_CELL = PALETTE_CELL, PAL_GAP = PALETTE_GAP, PAL_LEFT = PALETTE_LEFT, PAL_COLS = PALETTE_COLS;
        int palNumColors = 23;
        int palRows = (palNumColors + PAL_COLS - 1) / PAL_COLS;
        int palH = palRows * (PAL_CELL + PAL_GAP);
        int palW2b = PAL_CELL * PAL_COLS + PAL_GAP * (PAL_COLS - 1);
        int palAreaW = PAL_LEFT + palW2b + 12;
        int maxPx = (int)(Math.min((width - palAreaW) * 0.65f, (height - 40) * 0.72f));
        int cellSize = Math.max(6, maxPx / 16);
        int gridPx = cellSize * 16;
        int ox = palAreaW + (width - palAreaW - gridPx) / 2;
        int oy = (height - gridPx) / 2;
        int palStartY = (height - palH) / 2;

        // Grid click → paint pixel
        if (mx >= ox && mx < ox + gridPx && my >= oy && my < oy + gridPx) {
            pixelEdit.newFrameMenuOpen = false;
            int px = (int)((mx - ox) / cellSize);
            int py = (int)((my - oy) / cellSize);
            if (px >= 0 && px < 16 && py >= 0 && py < 16) {
                int idx = py * 16 + px;
                if (pixelEdit.node.imagePixels != null && idx < pixelEdit.node.imagePixels.length) {
                    if (!pixelDragUndoCaptured) { if (pixelEdit.pixelUndoStack.size() < 100) { pixelEdit.pixelUndoStack.add(pixelEdit.node.imagePixels.clone()); pixelEdit.pixelRedoStack.clear(); } pixelDragUndoCaptured = true; }
                    pixelEdit.node.imagePixels[idx] = pixelEdit.selectedColor;
                    pixelEdit.painting = true;
                    if (blockEntity != null) getBE().graph.bumpGeneration();
                }
            }
            return true;
        }

        // Palette (2-column grid)
        int[] palette = PIXEL_PALETTE;
        int palColsC = 2;
        for (int i = 0; i < palette.length; i++) {
            int col = i % palColsC, row = i / palColsC;
            int px = PAL_LEFT + col * (PAL_CELL + PAL_GAP);
            int py = palStartY + row * (PAL_CELL + PAL_GAP);
            if (mx >= px && mx <= px + PAL_CELL && my >= py && my <= py + PAL_CELL) {
                pixelEdit.selectedColor = palette[i];
                pixelEdit.newFrameMenuOpen = false;
                pixelEdit.editingHex = false;
                return true;
            }
        }
        // Hex color + OK (top-right)
        int trX = width - 240, trY = 6;
        int hexTopY = pixelEdit.node.type == NodeType.IMAGE_SEQUENCE ? trY + 24 : trY;
        String hexShow = "#" + hex8(pixelEdit.selectedColor);
        int hexW = Minecraft.getInstance().font.width(hexShow);
        if (mx >= trX && mx <= trX + hexW + 4 && my >= hexTopY - 2 && my <= hexTopY + 12) {
            pixelEdit.editingHex = !pixelEdit.editingHex;
            if (pixelEdit.editingHex) pixelEdit.hexInput = hex8(pixelEdit.selectedColor);
            return true;
        }
        // OK button (visible when editing hex)
        if (pixelEdit.editingHex) {
            String hx = pixelEdit.editingHex ? ("#" + pixelEdit.hexInput + "▌") : hexShow;
            int okX = trX + Minecraft.getInstance().font.width(hx) + 8;
            if (mx >= okX && mx <= okX + 30 && my >= hexTopY - 1 && my <= hexTopY + 11) {
                try { pixelEdit.selectedColor = (int)(Long.parseLong(pixelEdit.hexInput, 16) & 0xFFFFFFFFL); }
                catch (Exception e) { SchematicCompute.LOGGER.debug("Hex input parse", e); }
                pixelEdit.editingHex = false;
                return true;
            }
        }

        // Frame navigation (screen top-right, font-width based hit areas)
        if (pixelEdit.node.type == NodeType.IMAGE_SEQUENCE) {
            int navX = width - 240, navY = 6;
            var fw = Minecraft.getInstance().font;
            int prevW = fw.width("◀") + 2;
            int numW = fw.width("" + pixelEdit.frameIndex);
            int nextOff = navX + prevW + numW + 6;
            int nextW = fw.width("▶") + 2;

            // ◀ prev
            if (mx >= navX && mx <= navX + prevW + 4 && my >= navY && my <= navY + 12) {
                pixelEdit.frameIndex = Math.max(0, pixelEdit.frameIndex - 1);
                if (pixelEdit.frameIndex < pixelEdit.node.imageSequenceFrames.size())
                    pixelEdit.node.imagePixels = pixelEdit.node.imageSequenceFrames.get(pixelEdit.frameIndex);
                pixelEdit.newFrameMenuOpen = false;
                return true;
            }
            // ▶ next
            if (mx >= nextOff && mx <= nextOff + nextW + 4 && my >= navY && my <= navY + 12) {
                pixelEdit.frameIndex = Math.min(pixelEdit.node.imageSequenceFrames.size() - 1, pixelEdit.frameIndex + 1);
                if (pixelEdit.frameIndex >= 0 && pixelEdit.frameIndex < pixelEdit.node.imageSequenceFrames.size())
                    pixelEdit.node.imagePixels = pixelEdit.node.imageSequenceFrames.get(pixelEdit.frameIndex);
                pixelEdit.newFrameMenuOpen = false;
                return true;
            }
            // +New button → toggle dropdown
            if (mx >= navX + 120 && mx <= navX + 175 && my >= navY && my <= navY + 12) {
                pixelEdit.newFrameMenuOpen = !pixelEdit.newFrameMenuOpen;
                return true;
            }
            // Dropdown: "Blank"
            if (pixelEdit.newFrameMenuOpen && mx >= navX + 110 && mx <= navX + 210 && my >= navY + 22 && my <= navY + 34) {
                // Save all current frames for undo
                int frameCount = pixelEdit.node.imageSequenceFrames.size();
                if (pixelEdit.pixelUndoStack.size() + frameCount < 100) {
                    for (int i = frameCount - 1; i >= 0; i--)
                        pixelEdit.pixelUndoStack.add(pixelEdit.node.imageSequenceFrames.get(i).clone());
                    pixelEdit.pixelUndoStack.add(new int[]{frameCount}); // count marker
                    pixelEdit.pixelRedoStack.clear();
                }
                int[] newFrame = new int[256];
                java.util.Arrays.fill(newFrame, 0x00000000);
                pixelEdit.node.imageSequenceFrames.add(newFrame);
                pixelEdit.frameIndex = pixelEdit.node.imageSequenceFrames.size() - 1;
                pixelEdit.node.imagePixels = newFrame;
                pixelEdit.newFrameMenuOpen = false;
                return true;
            }
            // Dropdown: "From current"
            if (pixelEdit.newFrameMenuOpen && mx >= navX + 110 && mx <= navX + 210 && my >= navY + 22 && my <= navY + 34) {
                int frameCount = pixelEdit.node.imageSequenceFrames.size();
                if (pixelEdit.pixelUndoStack.size() + frameCount < 100) {
                    for (int i = frameCount - 1; i >= 0; i--)
                        pixelEdit.pixelUndoStack.add(pixelEdit.node.imageSequenceFrames.get(i).clone());
                    pixelEdit.pixelUndoStack.add(new int[]{frameCount}); // count marker
                    pixelEdit.pixelRedoStack.clear();
                }
                int[] newFrame = pixelEdit.node.imagePixels.clone();
                pixelEdit.node.imageSequenceFrames.add(newFrame);
                pixelEdit.frameIndex = pixelEdit.node.imageSequenceFrames.size() - 1;
                pixelEdit.node.imagePixels = newFrame;
                pixelEdit.newFrameMenuOpen = false;
                return true;
            }
        }

        // Close dropdown if clicking elsewhere
        if (pixelEdit.newFrameMenuOpen) { pixelEdit.newFrameMenuOpen = false; return true; }

        // Click outside → close
        if (mx < ox - 20 || mx > ox + gridPx + 20 || my < oy - 20 || my > oy + gridPx + 40) {
            pixelEdit = null;
            return true;
        }

        return false;
    }

    @Override
    public boolean keyPressed(int key, int sc, int mod) {
        // ── Global undo/redo (display mode + pixel editor; graph mode handled by GraphEditor) ──
        if (net.minecraft.client.gui.screens.Screen.hasControlDown()) {
            if (key == 90) { performUndo(); return true; }
            if (key == 89) { performRedo(); return true; }
        }
        if (showSettings) {
            for (var f : settingFields) if (f.isFocused()) {
                if ((key == 257 || key == 335) && blockEntity != null) { saveAllSettings(); return true; } // Enter saves
                return f.keyPressed(key, sc, mod);
            }
            if (key == 256) { previewScreenW = -1; previewScreenL = -1; showSettings = false; settingsInited = false; return true; }
        }
        if (pixelEdit != null && pixelEdit.open) {
            if (pixelEdit.editingHex) {
                if (key == 256) { pixelEdit.editingHex = false; return true; } // ESC cancel
                if (key == 257 || key == 335) { // Enter → apply hex
                    try { pixelEdit.selectedColor = (int)(Long.parseLong(pixelEdit.hexInput, 16) & 0xFFFFFFFFL); }
                    catch (Exception e) { SchematicCompute.LOGGER.debug("Hex input parse", e); }
                    pixelEdit.editingHex = false;
                    return true;
                }
                if (key == 259 && pixelEdit.hexInput.length() > 0) { // Backspace
                    pixelEdit.hexInput = pixelEdit.hexInput.substring(0, pixelEdit.hexInput.length() - 1);
                    return true;
                }
                return true; // consume all keys
            }
            if (key == 256) { // ESC
                pixelEdit = null;
                return true;
            }
            return true; // consume all keys while pixel editor is open
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
        if (key == 256) {
            // Esc closes sub-panels first, then the screen
            if (pixelEdit != null && pixelEdit.open) { pixelEdit.open = false; return true; }
            if (showSettings) { showSettings = false; return true; }
            if (displayMode) { displayMode = false; return true; }
            onClose(); return true;
        }
        if (editor.keyPressed(key, sc, mod)) return true;
        if (key >= 32 && key <= 96) return true;
        return super.keyPressed(key, sc, mod);
    }

    @Override public boolean keyReleased(int key, int sc, int mod) {
        if (displayMode) return false;
        return editor.keyReleased(key, sc, mod) || super.keyReleased(key, sc, mod);
    }
    @Override public boolean charTyped(char ch, int mod) {
        if (showSettings) {
            for (var f : settingFields) if (f.isFocused()) return f.charTyped(ch, mod);
            return false;
        }
        if (pixelEdit != null && pixelEdit.open) {
            if (pixelEdit.editingHex && (Character.isDigit(ch) || (ch >= 'a' && ch <= 'f') || (ch >= 'A' && ch <= 'F'))) {
                if (pixelEdit.hexInput.length() < 8) { pixelEdit.hexInput += ch; }
                return true;
            }
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

    @Override
    public void onClose() {
        if (blockEntity != null) {
            saveGraph();
        }
        super.onClose();
    }
}
