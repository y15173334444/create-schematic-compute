package com.example.create_schematic_compute.blocks;

import com.example.create_schematic_compute.SchematicCompute;
import com.example.create_schematic_compute.graph.*;
import com.example.create_schematic_compute.network.BlueprintSavePacket;
import com.example.create_schematic_compute.network.BlueprintTogglePacket;
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

    // ── Display mode inline editing ──
    private boolean editingS = false, editingR = false;
    private String editSBuf = "", editRBuf = "";

    // ── Evaluator cache (reused across display-mode frames) ──
    private NodeGraph lastEvalGraph = null;
    private GraphEvaluator cachedEval = null;
    private java.util.HashMap<Integer, Float> cachedPidState = null;
    private ArrayList<GraphEvaluator.InputSource> cachedEmptyInputs = null;

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
            settingFields[i] = new net.minecraft.client.gui.components.EditBox(mc.font, 0, 0, 60, 14, Component.literal(""));
            settingFields[i].setMaxLength(8);
        }
        // node filter: only input and display nodes
        editor.setNodeFilter(nt -> nt == NodeType.CONST
            || nt == NodeType.REDSTONE_IN
            || nt == NodeType.PRIVATE_IN
            || nt == NodeType.TEXT || nt == NodeType.DATA
            || nt == NodeType.IMAGE || nt == NodeType.IMAGE_SEQUENCE);
    }

    // ── GraphEditor.Host ──
    @Override public NodeGraph getGraph() { return blockEntity != null ? blockEntity.graph : new NodeGraph(); }
    @Override public boolean isRunning() { return blockEntity != null && blockEntity.running; }
    @Override public Screen asScreen() { return this; }

    @Override
    public void saveGraph() {
        try {
            MonitorBlockEntity be = blockEntity;
            if (be == null && menu.blockPos != null && minecraft != null && minecraft.level != null)
                if (minecraft.level.getBlockEntity(menu.blockPos) instanceof MonitorBlockEntity found) be = found;
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
        if (blockEntity != null)
            PacketDistributor.sendToServer(new BlueprintTogglePacket(blockEntity.getBlockPos(), start));
    }

    // ── Rendering ──
    @Override
    protected void renderBg(GuiGraphics g, float pt, int mx, int my) {
        if (displayMode) {
            renderDisplayArea(g, mx, my);
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
    private static final int TOOLBAR_H = 20; // toolbar at screen top
    private record DisplayArea(int x, int y, int w, int h) {}
    private DisplayArea computeDisplayArea() {
        int margin = 30;
        int topOffset = TOOLBAR_H + 6; // toolbar + gap
        int availW = width - 2 * margin;
        int availH = height - margin - topOffset;
        float aspect = 16f / 9f;
        if (blockEntity != null && blockEntity.screenLength > 0.001f)
            aspect = blockEntity.screenWidth / blockEntity.screenLength;
        int dw, dh;
        if (availW / aspect <= availH) { dw = availW; dh = (int)(availW / aspect); }
        else { dh = availH; dw = (int)(availH * aspect); }
        return new DisplayArea((width - dw) / 2, (height - dh) / 2 + topOffset / 2, dw, dh);
    }

    /** Get effective screen dimensions, using preview overrides when settings panel is open */
    private float getEffectiveScreenW() { return previewScreenW >= 0 ? previewScreenW : (blockEntity != null ? blockEntity.screenWidth : 1.5f); }
    private float getEffectiveScreenL() { return previewScreenL >= 0 ? previewScreenL : (blockEntity != null ? blockEntity.screenLength : 1.2f); }

    /** Compute content area insets matching the 3D renderer's 0.04-block bezel margin.
     *  Returns {contentX, contentY, contentW, contentH} within the given DisplayArea. */
    private int[] getContentArea(DisplayArea da) {
        float sw = getEffectiveScreenW();
        float sl = getEffectiveScreenL();
        float mfX = 0.04f / Math.max(sw, 0.01f);
        float mfY = 0.04f / Math.max(sl, 0.01f);
        int ix = Math.round(da.w * mfX);
        int iy = Math.round(da.h * mfY);
        return new int[]{da.x + ix, da.y + iy, da.w - 2 * ix, da.h - 2 * iy};
    }

    /** Get the world-space content width (screenWidth - 2*margin) for guiScale computation */
    private float getContentWorldW() { return Math.max(getEffectiveScreenW() - 0.08f, 0.01f); }

    private GraphEvaluator getCachedEvaluator(NodeGraph graph) {
        if (cachedEval == null || lastEvalGraph != graph) {
            lastEvalGraph = graph;
            cachedEval = new GraphEvaluator(graph);
            if (cachedPidState == null) cachedPidState = new java.util.HashMap<>();
            cachedPidState.clear();
        }
        // Build InputSource list from synced redstone inputs for REDSTONE_IN nodes
        var inputs = new ArrayList<GraphEvaluator.InputSource>();
        for (var n : graph.nodes) {
            if (n.type == NodeType.REDSTONE_IN) {
                long fk = com.example.create_schematic_compute.ModUtils.freqKey(n.itemParams);
                int sig = blockEntity != null ? blockEntity.getRedstoneInput(fk) : 0;
                inputs.add(new GraphEvaluator.InputSource(fk, sig));
            }
        }
        cachedEval.evaluate(inputs, cachedPidState, 0.05f);
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
        var graph = blockEntity != null ? blockEntity.graph : new NodeGraph();
        var localEval = getCachedEvaluator(graph);

        // Collect and render display elements (clipped to display area)
        var elements = collectDisplayElements(graph, localEval);
        // Dynamic guiScale: match world proportions
        // World: 1 font-pixel = 0.015 blocks. GUI: da.w pixels maps to cw = screenWidth-0.08 blocks.
        // So: guiScale = 0.015 * da.w / cw  (font-px → screen-px matching world scale)
        float cw = getContentWorldW();
        float guiScale = da.w * 0.015f / Math.max(cw, 0.01f);
        var ci = getContentArea(da);
        int contentX = ci[0], contentY = ci[1], contentW = ci[2], contentH = ci[3];
        var mc = Minecraft.getInstance();
        for (var elem : elements) {
            float s = guiScale * elem.scale;
            boolean isSelected = selectedDisplayNode != null && selectedDisplayNode.id == elem.nodeId;

            // Compute element content size in local (unscaled) coords.
            // In the world: 1 IMAGE pixel = 0.03 blocks = 2 font-pixels (since 1 font-px = 0.015 blocks).
            // In the GUI: each IMAGE pixel = 2 font-pixels (cellSize=2), total 32 font-pixels.
            float elemW = (elem.type == NodeType.IMAGE || elem.type == NodeType.IMAGE_SEQUENCE) ? 16 * 2
                : Minecraft.getInstance().font.width(elem.text.isEmpty() && elem.type != NodeType.DATA ? " " :
                    elem.type == NodeType.DATA ? String.format("%.1f", elem.value) : elem.text);
            float elemH = (elem.type == NodeType.IMAGE || elem.type == NodeType.IMAGE_SEQUENCE) ? 16 * 2 : 10;
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
                    g.drawString(mc.font, text, 0, 0, elem.color, false);
                }
                case DATA -> {
                    String dataStr = String.format("%.1f", elem.value);
                    g.drawString(mc.font, dataStr, 0, 0, elem.color, false);
                }
                case IMAGE, IMAGE_SEQUENCE -> {
                    if (elem.pixels != null) {
                        renderPixels(g, elem.pixels, 0, 0, 2, 16);
                    }
                }
            }
            pose.popPose();

            // Selected highlight (rotated with component, same transform)
            if (isSelected) {
                pose.pushPose();
                pose.translate(ex + elemW * s / 2, ey + elemH * s / 2, 0);
                pose.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(elem.rotation));
                pose.scale(s, s, 1);
                pose.translate(-elemW / 2, -elemH / 2, 0);
                int hx = -1, hy = -1, hw = (int)elemW + 2, hh = (int)elemH + 2;
                g.renderOutline(hx, hy, hw, hh, 0xFFFFAA44);
                pose.popPose();
            }
        }
        // ── Toolbar at screen top (fixed position) ──
        int tbx = 4, tby = 4, tbh = TOOLBAR_H;
        g.fill(0, tby, width, tby + tbh, 0xFF2A2822);
        // < Graph
        g.fill(tbx, tby, tbx + 56, tby + tbh, 0xFF3A3832);
        g.renderOutline(tbx, tby, 56, tbh, 0xFF8B7533);
        g.drawString(mc.font, I18n.get("gui.create_schematic_compute.monitor.back_graph"), tbx + 6, tby + 5, 0xFFFFFFFF, false);
        tbx += 62;
        // Settings
        g.fill(tbx, tby, tbx + 56, tby + tbh, showSettings ? 0xFF3A5A2A : 0xFF3A3832);
        g.renderOutline(tbx, tby, 56, tbh, 0xFF8B7533);
        g.drawString(mc.font, I18n.get("gui.create_schematic_compute.monitor.settings"), tbx + 6, tby + 5, 0xFFFFFFFF, false);
        tbx += 62;

        // Selected element editing (clickable S/R values)
        if (selectedDisplayNode != null) {
            String sTxt = "§6S:";
            if (editingS) sTxt += "§e" + editSBuf + "▌";
            else sTxt += "§e" + String.format("%.1f", selectedDisplayNode.displayScale);
            g.drawString(mc.font, sTxt, tbx + 4, tby + 5, 0xFFFFAA44, false);
            tbx += mc.font.width(sTxt) + 12;
            String rTxt = "§6R:";
            if (editingR) rTxt += "§e" + editRBuf + "▌";
            else rTxt += "§e" + String.format("%.0f", selectedDisplayNode.displayRotation);
            g.drawString(mc.font, rTxt, tbx + 4, tby + 5, 0xFFFFAA44, false);
        }

        // Hover hints (use rotated AABB for accuracy, with bounding-box clamp)
        if (selectedDisplayNode == null) {
            var font2 = Minecraft.getInstance().font;
            for (var elem : elements) {
                float s = guiScale * elem.scale;
                float hitW, hitH;
                if (elem.type == NodeType.IMAGE || elem.type == NodeType.IMAGE_SEQUENCE) {
                    hitW = 16 * 2; hitH = 16 * 2;
                } else {
                    String displayStr = elem.type == NodeType.DATA
                        ? String.format("%.1f", elem.value)
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

    // ── Settings panel ──
    private void renderSettingsPanel(GuiGraphics g, int mx, int my) {
        var mc = Minecraft.getInstance();
        int pw = 220, ph = 56 + 8 * 20 + 30;
        int px = (width - pw) / 2, py = (height - ph) / 2;
        g.fill(px, py, px + pw, py + ph, 0xFF2A2822);
        g.renderOutline(px, py, pw, ph, 0xFF5A4D3A);
        g.fill(px + 2, py + 2, px + pw - 2, py + 18, 0xFF4A3F28);
        g.drawString(mc.font, "§6§l" + I18n.get("gui.create_schematic_compute.monitor.settings_title"), px + 6, py + 5, 0xFFFFFFFF, false);
        // Close
        g.fill(px + pw - 18, py + 2, px + pw - 2, py + 18, 0xFF4A3028);
        g.renderOutline(px + pw - 18, py + 2, 16, 16, 0xFF8B5333);
        g.drawString(mc.font, "§cX", px + pw - 14, py + 5, 0xFFFFFFFF, false);

        // Load BE values into EditBoxes only once when panel opens
        if (!settingsInited && blockEntity != null) {
            settingFields[0].setValue(String.format("%.2f", blockEntity.screenWidth));
            settingFields[1].setValue(String.format("%.2f", blockEntity.screenLength));
            settingFields[2].setValue(String.format("%.2f", blockEntity.screenX));
            settingFields[3].setValue(String.format("%.2f", blockEntity.screenY));
            settingFields[4].setValue(String.format("%.2f", blockEntity.screenZ));
            settingFields[5].setValue(String.format("%.2f", blockEntity.screenRoll));
            settingFields[6].setValue(String.format("%.2f", blockEntity.screenPitch));
            settingFields[7].setValue(String.format("%.2f", blockEntity.screenYaw));
            settingsInited = true;
        }

        // EditBoxes
        int ey = py + 24;
        for (int i = 0; i < 8; i++) {
            g.drawString(mc.font, "§7" + I18n.get(SETTING_KEYS[i]) + ":", px + 10, ey + 2, 0xFFCCCCCC, false);
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
        g.drawString(mc.font, "§a" + I18n.get("gui.create_schematic_compute.monitor.save_close"), svX + 60, svY + 4, 0xFFFFFFFF, false);
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
            var pkt = new com.example.create_schematic_compute.network.MonitorSettingsPacket(
                blockEntity.getBlockPos(), w, l, x, y, z, r, p, yw);
            PacketDistributor.sendToServer(pkt);
        } catch (Exception e) { SchematicCompute.LOGGER.warn("Failed to parse monitor settings", e); }
        previewScreenW = -1; previewScreenL = -1;
        showSettings = false; settingsInited = false;
    }

    private boolean handleSettingsClick(double mx, double my, int btn) {
        if (btn != 0) return false;
        int pw = 220, ph = 56 + 8 * 20 + 30;
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
                    String lbl = n.params.length > 0 ? String.format("%.3f", n.params[0]) : "val";
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
        return list;
    }

    /** Clamp IMAGE normalized position using rotated-AABB-aware bounds,
     *  matching MonitorBlockEntityRenderer's clamping (lines 124-133). */
    private float[] clampImageNorm(GraphNode n, float rawX, float rawY, float rotation) {
        float sw = getEffectiveScreenW();
        float sl = getEffectiveScreenL();
        float cww = Math.max(sw - 0.08f, 0.01f);
        float cwl = Math.max(sl - 0.08f, 0.01f);
        float hw = 8f * 0.03f * n.displayScale;
        float hh = 8f * 0.03f * n.displayScale;
        float rA = (float)Math.abs(Math.cos(Math.toRadians(rotation)));
        float rB = (float)Math.abs(Math.sin(Math.toRadians(rotation)));
        float bbHalfW = (hw * rA + hh * rB) / cww;
        float bbHalfH = (hw * rB + hh * rA) / cwl;
        float px = Math.max(0, Math.min(1 - bbHalfW, rawX));
        float py = Math.max(0, Math.min(1 - bbHalfH, rawY));
        return new float[]{px, py};
    }

    /** Compute rotated AABB: returns [minX, minY, maxX, maxY] (center-based rotation) */
    private float[] elemRotAABB(float ex, float ey, float w, float h, float rot) {
        float hw = w / 2, hh = h / 2;
        float cx = ex + hw, cy = ey + hh; // center
        float mnX = Float.MAX_VALUE, mnY = Float.MAX_VALUE, mxX = -Float.MAX_VALUE, mxY = -Float.MAX_VALUE;
        float[] lx = {-hw, hw, hw, -hw}, ly = {-hh, -hh, hh, hh};
        if (rot != 0) {
            float rad = (float)Math.toRadians(rot), c = (float)Math.cos(rad), s = (float)Math.sin(rad);
            for (int i = 0; i < 4; i++) {
                float rx = lx[i] * c - ly[i] * s, ry = lx[i] * s + ly[i] * c;
                mnX = Math.min(mnX, cx + rx); mxX = Math.max(mxX, cx + rx);
                mnY = Math.min(mnY, cy + ry); mxY = Math.max(mxY, cy + ry);
            }
        } else {
            mnX = ex; mxX = ex + w; mnY = ey; mxY = ey + h;
        }
        return new float[]{mnX, mnY, mxX, mxY};
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
        g.drawString(mc.font, I18n.get("gui.create_schematic_compute.monitor.display"), btnX + 6, btnY + 4, 0xFFFFFFFF, false);
    }

    // ── Pixel editor overlay ──
    private void renderPixelEditor(GuiGraphics g, int mx, int my) {
        if (pixelEdit == null || pixelEdit.node == null) return;
        var mc = Minecraft.getInstance();
        int w = width, h = height;
        int fh = mc.font.lineHeight;

        // Layout constants (2-column palette, hex input at top-right)
        final int PAL_CELL = 16, PAL_GAP = 2, PAL_LEFT = 8, PAL_COLS = 2;
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
        int[] palette = {0xFFFFFFFF, 0xFFCCCCCC, 0xFF888888, 0xFF444444, 0xFF000000,
                         0xFFFF0000, 0xFFCC0000, 0xFFFF8800, 0xFF8B4513, 0xFFFFFF00,
                         0xFF88FF00, 0xFF00FF00, 0xFF008800, 0xFF00FFFF, 0xFF008888,
                         0xFF88CCFF, 0xFF0000FF, 0xFF000088, 0xFF8800FF, 0xFFFF00FF,
                         0xFFFF88CC, 0xFF880044, 0x00000000};
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
            g.drawString(mc.font, navTxt, trX, trY, 0xFFCCCCCC, false);
            g.drawString(mc.font, "§a▸ " + I18n.get("gui.create_schematic_compute.monitor.pixel_new"), trX + 120, trY, 0xFFCCCCCC, false);
            if (pixelEdit.newFrameMenuOpen) {
                g.fill(trX + 110, trY + 12, trX + 210, trY + 34, 0xFF2A2822);
                g.renderOutline(trX + 110, trY + 12, 100, 22, 0xFF5A4D3A);
                g.drawString(mc.font, "§7" + I18n.get("gui.create_schematic_compute.monitor.pixel_blank"), trX + 116, trY + 14, 0xFFCCCCCC, false);
                g.drawString(mc.font, "§7" + I18n.get("gui.create_schematic_compute.monitor.pixel_from_current"), trX + 116, trY + 24, 0xFFCCCCCC, false);
            }
        }
        // Hex color + OK button (always visible, below nav)
        int hexTopY = pixelEdit.node.type == NodeType.IMAGE_SEQUENCE ? trY + 24 : trY;
        String hexStr = pixelEdit.editingHex ? ("§e#" + pixelEdit.hexInput + "▌") : ("§7#" + String.format("%08X", pixelEdit.selectedColor));
        g.drawString(mc.font, hexStr, trX, hexTopY, 0xFFCCCCCC, false);
        if (pixelEdit.editingHex) {
            int okX = trX + mc.font.width(hexStr) + 8;
            g.fill(okX, hexTopY - 1, okX + 30, hexTopY + 11, 0xFF3A5A2A);
            g.renderOutline(okX, hexTopY - 1, 30, 12, 0xFF5A8A3A);
            g.drawString(mc.font, "§a" + I18n.get("gui.create_schematic_compute.ok"), okX + 4, hexTopY, 0xFFFFFFFF, false);
        }

        // Close hint (bottom center)
        String hint = "§7" + I18n.get("gui.create_schematic_compute.monitor.pixel_close_hint");
        g.drawString(mc.font, hint, (w - mc.font.width(hint)) / 2, h - 20, 0xFF888888, false);
    }

    private void openPixelEditor(GraphNode node) {
        if (node.type != NodeType.IMAGE && node.type != NodeType.IMAGE_SEQUENCE) return;
        var state = new PixelEditState();
        state.node = node;
        state.open = true;
        state.frameIndex = -1;
        // Pre-compute grid origin for first-click accuracy (match render layout)
        final int PAL_W = 28, PAL_LEFT = 10, PAL_CELL = 24, PAL_GAP = 2;
        int palAreaW = PAL_LEFT + PAL_W + 6;
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
            return handleDisplayAreaClick(mx, my, btn);
        }
        // Graph editor mode: check display toggle button first
        if (btn == 0 && mx >= width - 76 && mx <= width - 16 && my >= 4 && my <= 22) {
            displayMode = true;
            return true;
        }
        // Double-click IMAGE/IMAGE_SEQUENCE node → open pixel editor
        // (exclude expand-indicator area to avoid conflict with expand toggle)
        if (btn == 0 && blockEntity != null) {
            long now = System.currentTimeMillis();
            GraphNode clicked = null;
            float hitSx = 0, hitSy = 0;
            for (var n : blockEntity.graph.nodes) {
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
            int tby = 4, tbh = TOOLBAR_H;
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
                String sVal = editingS ? editSBuf : String.format("%.1f", selectedDisplayNode.displayScale);
                int sEnd = sx + fw.width("§6S:§e" + sVal + "▌") + 12;
                if (mx >= sx && mx <= sEnd && my >= sy && my <= sy + tbh) {
                    editingS = true; editingR = false; editSBuf = String.format("%.1f", selectedDisplayNode.displayScale);
                    return true;
                }
                int rx = sEnd;
                String rVal = editingR ? editRBuf : String.format("%.0f", selectedDisplayNode.displayRotation);
                int rEnd = rx + fw.width("§6R:§e" + rVal + "▌") + 4;
                if (mx >= rx && mx <= rEnd && my >= sy && my <= sy + tbh) {
                    editingR = true; editingS = false; editRBuf = String.format("%.0f", selectedDisplayNode.displayRotation);
                    return true;
                }
            }

            // Check for display element hits (scaled to rendered size)
            var graph = blockEntity != null ? blockEntity.graph : new NodeGraph();
            var localEval = getCachedEvaluator(graph);
            var elements = collectDisplayElements(graph, localEval);
            float guiScale2 = da.w * 0.015f / Math.max(getContentWorldW(), 0.01f);
            var ci = getContentArea(da);
            int contentX = ci[0], contentY = ci[1], contentW = ci[2], contentH = ci[3];

            for (int i = elements.size() - 1; i >= 0; i--) {
                var elem = elements.get(i);
                float s = guiScale2 * elem.scale;
                float hitW, hitH;
                var font2 = Minecraft.getInstance().font;
                if (elem.type == NodeType.IMAGE || elem.type == NodeType.IMAGE_SEQUENCE) {
                    hitW = 16 * 2; hitH = 16 * 2;
                } else if (elem.type == NodeType.DATA) {
                    String valStr = String.format("%.1f", elem.value);
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
            selectedDisplayNode = null;
        }
        return false;
    }

    @Override
    public void mouseMoved(double mx, double my) {
        if (pixelEdit != null && pixelEdit.open) {
            if (pixelEdit.painting) {
                // Recalculate grid (same as renderPixelEditor)
                final int PAL_CELL = 16, PAL_GAP = 2, PAL_LEFT = 8, PAL_COLS = 2;
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
                        if (pixelEdit.node.imagePixels != null && idx < pixelEdit.node.imagePixels.length)
                            pixelEdit.node.imagePixels[idx] = pixelEdit.selectedColor;
                    }
                }
            }
            return;
        }
        if (displayMode) {
            if (draggedDisplayNode != null) {
                var da = computeDisplayArea();
                float gsD = da.w * 0.015f / Math.max(getContentWorldW(), 0.01f);
                float sD = gsD * draggedDisplayNode.displayScale;
                float eW, eH;
                if (draggedDisplayNode.type == NodeType.IMAGE || draggedDisplayNode.type == NodeType.IMAGE_SEQUENCE) {
                    eW = 16 * 2; eH = 16 * 2;
                } else {
                    String ts = draggedDisplayNode.type == NodeType.DATA
                        ? String.format("%.1f", 0f)
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
    public boolean mouseReleased(double mx, double my, int btn) {
        if (pixelEdit != null && pixelEdit.open) { pixelEdit.painting = false; return false; }
        if (displayMode) { draggedDisplayNode = null; return true; }
        editor.mouseReleased(mx, my, btn);
        return super.mouseReleased(mx, my, btn);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (pixelEdit != null && pixelEdit.open) return true;
        if (displayMode) return true;
        return editor.mouseScrolled(mx, my, sx, sy);
    }

    private boolean handlePixelEditorClick(double mx, double my, int btn) {
        if (pixelEdit == null || !pixelEdit.open) return false;
        if (btn != 0) return false;

        // Recalculate layout (same as render)
        final int PAL_CELL = 16, PAL_GAP = 2, PAL_LEFT = 8, PAL_COLS = 2;
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
                    pixelEdit.node.imagePixels[idx] = pixelEdit.selectedColor;
                    pixelEdit.painting = true;
                }
            }
            return true;
        }

        // Palette (2-column grid)
        int[] palette = {0xFFFFFFFF, 0xFFCCCCCC, 0xFF888888, 0xFF444444, 0xFF000000,
                         0xFFFF0000, 0xFFCC0000, 0xFFFF8800, 0xFF8B4513, 0xFFFFFF00,
                         0xFF88FF00, 0xFF00FF00, 0xFF008800, 0xFF00FFFF, 0xFF008888,
                         0xFF88CCFF, 0xFF0000FF, 0xFF000088, 0xFF8800FF, 0xFFFF00FF,
                         0xFFFF88CC, 0xFF880044, 0x00000000};
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
        String hexShow = "#" + String.format("%08X", pixelEdit.selectedColor);
        int hexW = Minecraft.getInstance().font.width(hexShow);
        if (mx >= trX && mx <= trX + hexW + 4 && my >= hexTopY - 2 && my <= hexTopY + 12) {
            pixelEdit.editingHex = !pixelEdit.editingHex;
            if (pixelEdit.editingHex) pixelEdit.hexInput = String.format("%08X", pixelEdit.selectedColor);
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
            if (pixelEdit.newFrameMenuOpen && mx >= navX + 110 && mx <= navX + 210 && my >= navY + 12 && my <= navY + 22) {
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
        if (key == 256) { onClose(); return true; }
        return editor.keyPressed(key, sc, mod) || super.keyPressed(key, sc, mod);
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
