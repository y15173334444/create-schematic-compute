package com.example.create_schematic_compute.blocks;

import com.example.create_schematic_compute.SchematicCompute;
import com.example.create_schematic_compute.graph.NodeGraph;
import com.example.create_schematic_compute.graph.NodeType;
import com.example.create_schematic_compute.network.BlueprintSavePacket;
import com.example.create_schematic_compute.network.BlueprintTogglePacket;
import com.example.create_schematic_compute.network.RadarSettingsPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;
import java.io.ByteArrayOutputStream;

public class RadarScreen extends AbstractContainerScreen<RadarMenu> implements GraphEditor.Host {
    private final RadarBlockEntity blockEntity;
    private final GraphEditor editor;
    private boolean showDisplaySettings;
    private EditBox rangeInput, scaleInput, lockDistInput, offXInput, offYInput, offZInput;
    private static final int H = 18;

    private static boolean isAllowedNode(NodeType nt) {
        return nt == NodeType.TARGET_OUT || nt == NodeType.REDSTONE_OUT
            || nt == NodeType.PRIVATE_OUT || nt == NodeType.BUS_OUT;
    }

    public RadarScreen(RadarMenu m, Inventory inv, Component t) {
        super(m, inv, t);
        this.blockEntity = m.blockEntity;
        this.imageWidth = 9999;
        this.editor = new GraphEditor(this, this);
        editor.setNodeFilter(RadarScreen::isAllowedNode);
    }

    private RadarBlockEntity getBE() {
        if (blockEntity != null) return blockEntity;
        if (menu.blockPos != null && minecraft != null && minecraft.level != null) {
            if (minecraft.level.getBlockEntity(menu.blockPos) instanceof RadarBlockEntity be) return be;
        }
        return null;
    }

    @Override public NodeGraph getGraph() { RadarBlockEntity be = getBE(); return be != null ? be.graph : new NodeGraph(); }
    @Override public boolean isRunning() { RadarBlockEntity be = getBE(); return be != null && be.running; }
    @Override public net.minecraft.client.gui.screens.Screen asScreen() { return this; }
    @Override public void saveGraph() {
        try {
            RadarBlockEntity be = getBE();
            if (be == null || be.getLevel() == null) return;
            applyInputs(be); send(be); // 先同步设置
            var tag = new CompoundTag(); tag.put("graph", getGraph().save(be.getLevel().registryAccess()));
            var baos = new ByteArrayOutputStream(); NbtIo.writeCompressed(tag, baos);
            PacketDistributor.sendToServer(new BlueprintSavePacket(be.getBlockPos(), baos.toByteArray()));
            editor.saveFeedbackUntil = System.currentTimeMillis() + 1500;
        } catch (Exception e) { SchematicCompute.LOGGER.error("Save", e); }
    }
    @Override public void toggleRunning(boolean start) {
        RadarBlockEntity be = getBE();
        if (be != null) { be.running = start; PacketDistributor.sendToServer(new BlueprintTogglePacket(be.getBlockPos(), start)); }
    }

    @Override protected void init() {
        super.init();
        int sy = (NodeRenderer.isToolbarBottom() ? height - 44 : 26) + 22;
        rangeInput   = new EditBox(font, 0, sy, 36, 14, Component.literal("R"));
        scaleInput   = new EditBox(font, 0, sy, 36, 14, Component.literal("S"));
        lockDistInput = new EditBox(font, 0, sy, 36, 14, Component.literal("L"));
        offXInput    = new EditBox(font, 0, sy, 44, 14, Component.literal("X"));
        offYInput    = new EditBox(font, 0, sy, 44, 14, Component.literal("Y"));
        offZInput    = new EditBox(font, 0, sy, 44, 14, Component.literal("Z"));
        hideInputs();
        addRenderableWidget(rangeInput); addRenderableWidget(scaleInput); addRenderableWidget(lockDistInput);
        addRenderableWidget(offXInput); addRenderableWidget(offYInput); addRenderableWidget(offZInput);
    }

    @Override protected void renderBg(GuiGraphics g, float pt, int mx, int my) {
        editor.renderBg(g, mx, my);
        RadarBlockEntity be = getBE();
        if (be == null) return;
        int y = NodeRenderer.isToolbarBottom() ? height - 44 : 26;
        renderRadarToolbar(g, mx, my, y, be);
        if (showDisplaySettings) renderDisplaySettings(g, mx, my, y, be);
    }

    // ── Toolbar ──
    private void renderRadarToolbar(GuiGraphics g, int mx, int my, int y, RadarBlockEntity be) {
        int x = 4;
        bt(g, x, y, 48, be.scanMode == 1 ? t("mode_single") : t("mode_multi"), mx, my, 0xFF3A3832, true); x += 52;
        btn2(g, x, y, 42, be.lockMode == 1 ? t("lock_manual") : t("lock_auto"), mx, my); x += 46;
        btn(g, x, y, 54, t("show_players"), mx, my, be.showPlayers); x += 58;
        btn(g, x, y, 46, t("show_mobs"), mx, my, be.showMobs); x += 50;
        btn(g, x, y, 46, t("show_sable"), mx, my, be.showSable); x += 50;
        int sc2 = be.displayStyle == 1 ? 0xFF224488 : 0xFF3A3832;
        String styleLabel = be.displayStyle == 1 ? t("style_holo") : t("style_classic");
        bt(g, x, y, 48, styleLabel, mx, my, sc2, true); x += 52;
        bt(g, x, y, 42, t("settings"), mx, my, showDisplaySettings ? 0xFF444422 : 0xFF3A3832, true);
    }

    // 普通按钮（有悬停）
    private void bt(GuiGraphics g, int x, int y, int w, String text, int mx, int my, int bg, boolean hoverable) {
        boolean h = hoverable && mx >= x && mx <= x + w && my >= y && my <= y + H;
        g.fill(x, y, x + w, y + H, h ? 0xFF555555 : bg);
        g.renderOutline(x, y, w, H, NodeRenderer.CSB);
        g.drawString(font, text, x + 4, y + 5, h ? 0xFFFFFFFF : 0xFFCCCCCC);
    }

    // 开关按钮（激活=绿色填充，边框保持 CSB，无悬停高亮）
    private void btn(GuiGraphics g, int x, int y, int w, String text, int mx, int my, boolean active) {
        g.fill(x, y, x + w, y + H, active ? 0xFF224422 : 0xFF3A3832);
        g.renderOutline(x, y, w, H, NodeRenderer.CSB);
        g.drawString(font, text, x + 4, y + 5, active ? 0xFFAAFFAA : 0xFF888888);
    }
    // 纯展示按钮（无激活态）
    private void btn2(GuiGraphics g, int x, int y, int w, String text, int mx, int my) {
        g.fill(x, y, x + w, y + H, 0xFF3A3832);
        g.renderOutline(x, y, w, H, NodeRenderer.CSB);
        g.drawString(font, text, x + 4, y + 5, 0xFFCCCCCC);
    }

    // ── Display Settings Panel ──
    private void renderDisplaySettings(GuiGraphics g, int mx, int my, int toolY, RadarBlockEntity be) {
        RadarBlockEntity rb = getBE(); if (rb == null) return;
        insureInputs(rb);
        boolean bottom = NodeRenderer.isToolbarBottom();
        int py = bottom ? toolY - 86 : toolY + 22;
        int px = 4, pw = 340, ph = 80;
        g.fill(px, py, px + pw, py + ph, 0xDD1F1E1A);
        g.renderOutline(px, py, pw, ph, NodeRenderer.CSB);
        g.drawString(font, "§6§l" + t("settings_title"), px + 6, py + 4, 0xFFFFFFFF);
        // 右下角保存按钮
        bt(g, px + pw - 46, py + ph - H - 2, 42, I18n.get("gui.create_schematic_compute.save"), mx, my, 0xFF224422, true);

        int iy = py + 22, ly = iy + 22;
        g.drawString(font, "§7" + t("scan_range"),  px + 6,  ly, 0xFFAAAAAA);
        g.drawString(font, "§7" + t("display_scale"), px + 56, ly, 0xFFAAAAAA);
        g.drawString(font, "§7" + t("lock_dist"),   px + 106, ly, 0xFFAAAAAA);
        g.drawString(font, "§7" + t("offset_x"), px + 166, ly, 0xFFAAAAAA);
        g.drawString(font, "§7" + t("offset_y"), px + 226, ly, 0xFFAAAAAA);
        g.drawString(font, "§7" + t("offset_z"), px + 286, ly, 0xFFAAAAAA);
    }

    private void hideInputs() { rangeInput.setVisible(false); scaleInput.setVisible(false); lockDistInput.setVisible(false); offXInput.setVisible(false); offYInput.setVisible(false); offZInput.setVisible(false); }
    private boolean inputsNeedInit = true;

    private void insureInputs(RadarBlockEntity be) {
        int toolY = NodeRenderer.isToolbarBottom() ? height - 44 : 26;
        boolean bottom = NodeRenderer.isToolbarBottom();
        int px = 4, py = bottom ? toolY - 86 : toolY + 22;
        int iy = py + 22;
        // 仅首次设置位置
        if (inputsNeedInit) {
            inputsNeedInit = false;
            rangeInput.setX(px + 6);    rangeInput.setY(iy);
            scaleInput.setX(px + 56);   scaleInput.setY(iy);
            lockDistInput.setX(px + 106); lockDistInput.setY(iy);
            offXInput.setX(px + 162);   offXInput.setY(iy);
            offYInput.setX(px + 222);   offYInput.setY(iy);
            offZInput.setX(px + 282);   offZInput.setY(iy);
            rangeInput.setVisible(true); scaleInput.setVisible(true); lockDistInput.setVisible(true);
            offXInput.setVisible(true); offYInput.setVisible(true); offZInput.setVisible(true);
        }
        // 每帧更新值（无焦点时同步BE值，有焦点时读取输入值实时应用）
        if (!rangeInput.isFocused())  rangeInput.setValue(String.valueOf(be.scanRange));
        else try { be.scanRange = Math.max(1, Math.min(128, Integer.parseInt(rangeInput.getValue()))); } catch(Exception ignored) {}
        if (!scaleInput.isFocused())  scaleInput.setValue(String.valueOf(be.displayScale));
        else try { be.displayScale = Math.max(1, Math.min(32, Integer.parseInt(scaleInput.getValue()))); } catch(Exception ignored) {}
        if (!lockDistInput.isFocused()) lockDistInput.setValue(fmt1(be.lockDistance));
        else try { be.lockDistance = Math.max(0, Float.parseFloat(lockDistInput.getValue())); } catch(Exception ignored) {}
        if (!offXInput.isFocused())   offXInput.setValue(fmt1(be.displayX));
        else try { be.displayX = Float.parseFloat(offXInput.getValue()); } catch(Exception ignored) {}
        if (!offYInput.isFocused())   offYInput.setValue(fmt1(be.displayY));
        else try { be.displayY = Float.parseFloat(offYInput.getValue()); } catch(Exception ignored) {}
        if (!offZInput.isFocused())   offZInput.setValue(fmt1(be.displayZ));
        else try { be.displayZ = Float.parseFloat(offZInput.getValue()); } catch(Exception ignored) {}
    }
    private static String fmt1(float v) { return String.format("%.1f", v); }
    private String t(String key) { return I18n.get("gui.create_schematic_compute.radar." + key); }

    // ── Clicks ──
    @Override public boolean mouseClicked(double mx, double my, int btn) {
        RadarBlockEntity be = getBE();
        if (be != null && btn == 0) {
            int y = NodeRenderer.isToolbarBottom() ? height - 44 : 26;
            // 设置面板保存按钮：右下角 42x18 区域
            if (showDisplaySettings) {
                boolean bottom = NodeRenderer.isToolbarBottom();
                int py = bottom ? y - 86 : y + 22;
                int sx = 4 + 340 - 46, sy = py + 60; // 右下角：pw=286, 保存按钮 42px 宽
                if ((int)my >= sy && (int)my <= sy + H && (int)mx >= sx && (int)mx <= sx + 42) {
                    applyInputs(be);
                    PacketDistributor.sendToServer(new RadarSettingsPacket(be.getBlockPos(),
                        be.scanRange, be.scanMode, be.displayScale,
                        be.showPlayers, be.showMobs, be.showSable, be.lockMode,
                        be.displayX, be.displayY, be.displayZ, be.excludeHost, be.displayStyle, be.lockDistance));
                    showDisplaySettings = false; hideInputs(); return true;
                }
            }
            if (handleToolbarClick((int)mx, (int)my, y, be)) return true;
        }
        return editor.mouseClicked(mx, my, btn) || super.mouseClicked(mx, my, btn);
    }

    private boolean handleToolbarClick(int mx, int my, int y, RadarBlockEntity be) {
        if (my < y || my > y + H) return false;
        int x = 4;
        if (in(mx, x, 48)) { be.scanMode ^= 1; send(be); return true; }
        x += 52;
        if (in(mx, x, 42)) { be.lockMode ^= 1; send(be); return true; }
        x += 46;
        if (in(mx, x, 54)) { be.showPlayers = !be.showPlayers; send(be); return true; }
        x += 58;
        if (in(mx, x, 46)) { be.showMobs = !be.showMobs; send(be); return true; }
        x += 50;
        if (in(mx, x, 46)) { be.showSable = !be.showSable; send(be); return true; }
        x += 50;
        if (in(mx, x, 48)) { be.displayStyle ^= 1; send(be); return true; }
        x += 52;
        if (in(mx, x, 42)) { showDisplaySettings = !showDisplaySettings; if (showDisplaySettings) inputsNeedInit = true; else { applyInputs(be); hideInputs(); } return true; }
        return false;
    }

    private void applyInputs(RadarBlockEntity be) {
        try { be.scanRange = Math.max(1, Math.min(128, Integer.parseInt(rangeInput.getValue()))); } catch(Exception ignored) {}
        try { be.displayScale = Math.max(1, Math.min(32, Integer.parseInt(scaleInput.getValue()))); } catch(Exception ignored) {}
        try { be.displayX = Float.parseFloat(offXInput.getValue()); } catch(Exception ignored) {}
        try { be.displayY = Float.parseFloat(offYInput.getValue()); } catch(Exception ignored) {}
        try { be.lockDistance = Math.max(0, Float.parseFloat(lockDistInput.getValue())); } catch(Exception ignored) {}
        try { be.displayZ = Float.parseFloat(offZInput.getValue()); } catch(Exception ignored) {}
    }

    private boolean in(int mx, int x, int w) { return mx >= x && mx <= x + w; }

    private void send(RadarBlockEntity be) {
        be.scanRange = Math.max(1, Math.min(128, be.scanRange));
        be.displayScale = Math.max(1, Math.min(32, be.displayScale));
        PacketDistributor.sendToServer(new RadarSettingsPacket(be.getBlockPos(),
            be.scanRange, be.scanMode, be.displayScale,
            be.showPlayers, be.showMobs, be.showSable, be.lockMode,
            be.displayX, be.displayY, be.displayZ, be.excludeHost, be.displayStyle, be.lockDistance));
    }

    @Override public void removed() { RadarBlockEntity be = getBE(); if (be != null) { applyInputs(be); hideInputs(); } super.removed(); }
    @Override public boolean mouseReleased(double mx, double my, int btn) { editor.mouseReleased(mx, my, btn); return super.mouseReleased(mx, my, btn); }
    @Override public void mouseMoved(double mx, double my) { editor.mouseMoved(mx, my); }
    @Override public boolean mouseScrolled(double mx, double my, double sx, double sy) { return editor.mouseScrolled(mx, my, sx, sy); }
    @Override public boolean keyPressed(int key, int sc, int mod) { if (key == 256) { applyInputs(getBE()); onClose(); return true; } return editor.keyPressed(key, sc, mod) || super.keyPressed(key, sc, mod); }
    @Override public boolean keyReleased(int key, int sc, int mod) { return editor.keyReleased(key, sc, mod) || super.keyReleased(key, sc, mod); }
    @Override public boolean charTyped(char ch, int mod) { return editor.charTyped(ch, mod) || super.charTyped(ch, mod); }
}
