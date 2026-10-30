package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.graph.BlockNodeAllowances;
import io.github.y15173334444.create_schematic_compute.graph.EvalSnapshot;
import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import io.github.y15173334444.create_schematic_compute.network.BlueprintTogglePacket;
import io.github.y15173334444.create_schematic_compute.network.RadarSettingsPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.y15173334444.create_schematic_compute.client.editor.AbstractGraphScreen;
import io.github.y15173334444.create_schematic_compute.client.editor.GraphEditor;
import io.github.y15173334444.create_schematic_compute.client.editor.NodeRenderer;
public class RadarScreen extends AbstractGraphScreen {
    /** 独立 logger：不引用 (root) 包的 @Mod 类——其静态初始化依赖 MC 注册表，
     *  而 LoggerFactory 不依赖注册表，headless/JUnit 下也能安全完成类初始化。日志名为本类 FQCN。
     *  Same-name standalone logger: never references the @Mod class — its static init needs
     *  the Minecraft registries, while LoggerFactory does not, so class init stays safe in
     *  plain JUnit too. The log name is this class's FQCN. */
    private static final Logger LOGGER = LoggerFactory.getLogger(RadarScreen.class);

    private boolean showDisplaySettings;
    private EditBox rangeInput, scaleInput, lockDistInput, offXInput, offYInput, offZInput;
    private static final int H = 18;

    public RadarScreen(BlockPos pos) {
        super(Component.translatable("container." + SchematicCompute.MOD_ID + ".radar"), pos);
        setNodeAllowance(BlockNodeAllowances.RADAR);
    }

    @Override protected RadarBlockEntity getBE() {
        if (minecraft != null && minecraft.level != null) {
            if (minecraft.level.getBlockEntity(blockPos) instanceof RadarBlockEntity be) return be;
        }
        return null;
    }
    @Override protected boolean isBlockEntityValid() {
        return minecraft != null && minecraft.level != null
            && minecraft.level.getBlockEntity(blockPos) instanceof RadarBlockEntity;
    }

    @Override public NodeGraph getGraph() { RadarBlockEntity be = getBE(); return be != null ? be.getNodeGraph() : new NodeGraph(); }
    @Override public boolean isRunning() { RadarBlockEntity be = getBE(); return be != null && be.isRunning(); }
    @Override public EvalSnapshot getCachedEvalSnapshot() {
        RadarBlockEntity be = getBE();
        return be != null ? be.getCachedEvalSnapshot() : null;
    }
    @Override public void saveGraph() {
        RadarBlockEntity be = getBE();
        if (be != null) { applyInputs(be); send(be); } // 先同步设置 / sync settings first
        super.saveGraph();
    }
    @Override public void toggleRunning(boolean start) {
        RadarBlockEntity be = getBE();
        if (be != null) { be.setRunning(start); PacketDistributor.sendToServer(new BlueprintTogglePacket(be.getBlockPos(), start)); }
    }

    /** 雷达工具行 y：紧贴基础工具栏下沿（顶置 = 顶栏 + 基础工具栏 + 4px 间距；
     *  底置 = 基础工具栏上方）。旧硬编码 26 是顶栏（TOP_BAR_H）落地前的值，
     *  顶栏加入后基础工具栏下移，雷达行与其重叠 —— 统一收敛到本方法。
     *  Radar toolbar row y: hugs the base toolbar's edge (top: below the editor
     *  top bar + base toolbar with a 4px gap; bottom: above the base toolbar).
     *  The old hard-coded 26 predated the top bar and overlapped the base toolbar
     *  once it moved down — all sites converge here. */
    private int toolbarRowY() {
        return NodeRenderer.isToolbarBottom() ? height - 44 : GraphEditor.TOP_BAR_H + 24;
    }

    @Override protected void init() {
        super.init(); // Screen.init 清空 widget + 发送 GraphJoinPacket / clears widgets + sends GraphJoinPacket
        int sy = toolbarRowY() + 22;
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

    /** 画布钩子：节点编辑器 + 雷达工具栏 + 设置面板 / canvas hook: graph editor + radar toolbar + settings panel */
    @Override protected void renderGraphCanvas(GuiGraphics g, int mx, int my, float pt) {
        editor.renderBg(g, mx, my);
        RadarBlockEntity be = getBE();
        if (be == null) return;
        int y = toolbarRowY();
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
        g.renderOutline(x, y, w, H, NodeRenderer.CSB());
        g.drawString(font, text, x + 4, y + 5, h ? 0xFFFFFFFF : 0xFFCCCCCC);
    }

    // 开关按钮（激活=绿色填充，边框保持 CSB()，无悬停高亮）
    private void btn(GuiGraphics g, int x, int y, int w, String text, int mx, int my, boolean active) {
        g.fill(x, y, x + w, y + H, active ? 0xFF224422 : 0xFF3A3832);
        g.renderOutline(x, y, w, H, NodeRenderer.CSB());
        g.drawString(font, text, x + 4, y + 5, active ? 0xFFAAFFAA : 0xFF888888);
    }
    // 纯展示按钮（无激活态）
    private void btn2(GuiGraphics g, int x, int y, int w, String text, int mx, int my) {
        g.fill(x, y, x + w, y + H, 0xFF3A3832);
        g.renderOutline(x, y, w, H, NodeRenderer.CSB());
        g.drawString(font, text, x + 4, y + 5, 0xFFCCCCCC);
    }

    // ── Display Settings Panel ──
    /** 面板矩形 {px, py, pw, ph}——渲染、命中、输入框定位三处同源。
     *  Panel rect {px, py, pw, ph} — one source for render, hit-testing and input-box layout. */
    private int[] displaySettingsRect() {
        boolean bottom = NodeRenderer.isToolbarBottom();
        int py = bottom ? toolbarRowY() - 86 : toolbarRowY() + 22;
        return new int[]{4, py, 340, 80};
    }

    private void renderDisplaySettings(GuiGraphics g, int mx, int my, int toolY, RadarBlockEntity be) {
        RadarBlockEntity rb = getBE(); if (rb == null) return;
        insureInputs(rb);
        int[] r = displaySettingsRect();
        int px = r[0], py = r[1], pw = r[2], ph = r[3];
        g.fill(px, py, px + pw, py + ph, 0xDD1F1E1A);
        g.renderOutline(px, py, pw, ph, NodeRenderer.CSB());
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
        int px = 4, py = displaySettingsRect()[1];
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
        else try { be.scanRange = Math.max(1, Math.min(128, Integer.parseInt(rangeInput.getValue()))); } catch(Exception ignored) { LOGGER.debug("Invalid numeric input in radar settings"); }
        if (!scaleInput.isFocused())  scaleInput.setValue(String.valueOf(be.displayScale));
        else try { be.displayScale = Math.max(1, Math.min(32, Integer.parseInt(scaleInput.getValue()))); } catch(Exception ignored) { LOGGER.debug("Invalid numeric input in radar settings"); }
        if (!lockDistInput.isFocused()) lockDistInput.setValue(fmt1(be.lockDistance));
        else try { be.lockDistance = Math.max(0, Float.parseFloat(lockDistInput.getValue())); } catch(Exception ignored) { LOGGER.debug("Invalid numeric input in radar settings"); }
        if (!offXInput.isFocused())   offXInput.setValue(fmt1(be.displayX));
        else try { be.displayX = Float.parseFloat(offXInput.getValue()); } catch(Exception ignored) { LOGGER.debug("Invalid numeric input in radar settings"); }
        if (!offYInput.isFocused())   offYInput.setValue(fmt1(be.displayY));
        else try { be.displayY = Float.parseFloat(offYInput.getValue()); } catch(Exception ignored) { LOGGER.debug("Invalid numeric input in radar settings"); }
        if (!offZInput.isFocused())   offZInput.setValue(fmt1(be.displayZ));
        else try { be.displayZ = Float.parseFloat(offZInput.getValue()); } catch(Exception ignored) { LOGGER.debug("Invalid numeric input in radar settings"); }
    }
    private static String fmt1(float v) { return String.format("%.1f", v); }
    private String t(String key) { return I18n.get("gui.create_schematic_compute.radar." + key); }

    // ── Clicks ──
    @Override public boolean mouseClicked(double mx, double my, int btn) {
        RadarBlockEntity be = getBE();
        if (be != null && btn == 0) {
            int y = toolbarRowY();
            // 设置面板保存按钮：右下角 42x18 区域
            if (showDisplaySettings) {
                int[] r = displaySettingsRect();
                // 设置面板保存按钮：右下角 42x18 区域
                int sx = r[0] + r[2] - 46, sy = r[1] + r[3] - H - 2; // 右下角：与渲染的保存按钮同源
                if ((int)my >= sy && (int)my <= sy + H && (int)mx >= sx && (int)mx <= sx + 42) {
                    applyInputs(be);
                    PacketDistributor.sendToServer(new RadarSettingsPacket(be.getBlockPos(),
                        be.scanRange, be.scanMode, be.displayScale,
                        be.showPlayers, be.showMobs, be.showSable, be.lockMode,
                        be.displayX, be.displayY, be.displayZ, be.excludeHost, be.displayStyle, be.lockDistance));
                    showDisplaySettings = false; hideInputs(); return true;
                }
                // 面板矩形吞掉区域内一切点击（空隙不穿透到画布节点）；EditBox 聚焦手动分发
                // ——经 super 会先落图编辑器，面板后方的节点会被点中/拖走（与 SpeakerScreen
                // 面板同款缺陷、同批修复）。分发后补 vanilla children 契约后半段：消费点击的
                // 框成为屏幕焦点（键盘按 getFocused() 寻址）；点空隙 = 全部失焦。
                // The panel rect swallows every click inside it (gaps never reach the nodes
                // behind); EditBox focus is dispatched manually — the super path reaches the
                // graph editor first, so nodes behind the panel were clicked/dragged (same
                // defect as the SpeakerScreen panel, fixed in the same batch). After dispatch,
                // the vanilla children contract's second half follows: a consuming box becomes
                // the SCREEN focus (keyboard input is addressed through getFocused()); a gap
                // click clears everything.
                if (mx >= r[0] && mx <= r[0] + r[2] && my >= r[1] && my <= r[1] + r[3]) {
                    EditBox hit = null;
                    for (EditBox box : new EditBox[]{rangeInput, scaleInput, lockDistInput,
                            offXInput, offYInput, offZInput}) {
                        if (hit == null && box.mouseClicked(mx, my, btn)) hit = box;
                    }
                    this.setFocused(hit);
                    return true;
                }
                // 面板外部点击 = 收回面板，与开关按钮同语义（收回即 applyInputs，不发包——
                // 发包只归保存按钮）；工具行以上的点击例外——让给既有 UI。
                // Outside clicks collapse the panel with the toggle's own semantics (collapse
                // applies inputs but never ships — shipping belongs to the save button);
                // clicks above the toolbar row are exempt — they belong to the existing UI.
                if (my > y + H) {
                    applyInputs(be);
                    showDisplaySettings = false; hideInputs();
                    return true;
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
        try { be.scanRange = Math.max(1, Math.min(128, Integer.parseInt(rangeInput.getValue()))); } catch(Exception ignored) { LOGGER.debug("Invalid numeric input in radar settings"); }
        try { be.displayScale = Math.max(1, Math.min(32, Integer.parseInt(scaleInput.getValue()))); } catch(Exception ignored) { LOGGER.debug("Invalid numeric input in radar settings"); }
        try { be.displayX = Float.parseFloat(offXInput.getValue()); } catch(Exception ignored) { LOGGER.debug("Invalid numeric input in radar settings"); }
        try { be.displayY = Float.parseFloat(offYInput.getValue()); } catch(Exception ignored) { LOGGER.debug("Invalid numeric input in radar settings"); }
        try { be.lockDistance = Math.max(0, Float.parseFloat(lockDistInput.getValue())); } catch(Exception ignored) { LOGGER.debug("Invalid numeric input in radar settings"); }
        try { be.displayZ = Float.parseFloat(offZInput.getValue()); } catch(Exception ignored) { LOGGER.debug("Invalid numeric input in radar settings"); }
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

    /** 关界面前钩子：把 EditBox 输入写回 BE 并隐藏输入框（原 removed() 中的子类逻辑）
     *  / pre-close hook: write EditBox input back to the BE and hide the boxes (was in removed()) */
    @Override protected void preClose() {
        RadarBlockEntity be = getBE();
        if (be != null) { applyInputs(be); hideInputs(); }
    }

    @Override public boolean mouseReleased(double mx, double my, int btn) { editor.mouseReleased(mx, my, btn); return super.mouseReleased(mx, my, btn); }
    @Override public void mouseMoved(double mx, double my) { editor.mouseMoved(mx, my); }
    @Override public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) { return editor.mouseDragged(mx, my, btn, dx, dy) || super.mouseDragged(mx, my, btn, dx, dy); }
    @Override public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        // 打开的面板内滚轮吞掉：面板自身不滚动，但不得缩放身后的画布。
        // Wheel inside the open panel is swallowed: the panel never scrolls, but must not
        // zoom the canvas behind it.
        if (showDisplaySettings) {
            int[] r = displaySettingsRect();
            if (mx >= r[0] && mx <= r[0] + r[2] && my >= r[1] && my <= r[1] + r[3]) return true;
        }
        return editor.mouseScrolled(mx, my, sx, sy);
    }
    @Override public boolean keyPressed(int key, int sc, int mod) { if (editor.keyPressed(key, sc, mod)) return true; if (key == 256) { onClose(); return true; } if (key >= 32 && key <= 96) return true; return super.keyPressed(key, sc, mod); }
    @Override public boolean keyReleased(int key, int sc, int mod) { return editor.keyReleased(key, sc, mod) || super.keyReleased(key, sc, mod); }
    @Override public boolean charTyped(char ch, int mod) { return editor.charTyped(ch, mod) || super.charTyped(ch, mod); }
}
