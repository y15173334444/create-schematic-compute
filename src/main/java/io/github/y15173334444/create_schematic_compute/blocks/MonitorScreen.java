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
import java.util.UUID;

import static io.github.y15173334444.create_schematic_compute.client.GeometryConstants.*;

public class MonitorScreen extends AbstractGraphScreen implements MonitorDisplayEditor.Host {

    // ── 显示编辑 GUI（已拆分，docs/gui-decomposition-plan.md 步骤 2）──
    // Display-layout editor GUI (split out, roadmap step 2): MonitorScreen owns only the
    // node-graph mode and delegates here while display mode is active. Display-mode state,
    // layer panel, settings panel, presence reporting and input routing live in the editor.
    private final MonitorDisplayEditor displayEditor = new MonitorDisplayEditor(this);

    /** 像素编辑器转移时跳过离开协作会话（委托显示编辑器，消费一次即复位）。
     *  Skip leaving the collab session during pixel-editor transfer (delegated). */
    @Override protected boolean skipLeaveOnClose() { return displayEditor.consumeSkipLeave(); }

    // ── Double-click tracking ──
    private long lastClickTime = 0;
    private int lastClickNodeId = -1;

    public MonitorScreen(BlockPos pos) {
        super(Component.translatable("container." + SchematicCompute.MOD_ID + ".monitor"), pos);
        // 设置面板的 EditBox 由 MonitorDisplayEditor 持有（打开面板时装载数值，settingsInited 标志）
        // Settings EditBoxes live in MonitorDisplayEditor (values load when the panel opens).

        // values + display + debug（见 BlockNodeAllowances.MONITOR）
        // values + display + debug (see BlockNodeAllowances.MONITOR)
        setNodeAllowance(BlockNodeAllowances.MONITOR);
    }

    // ══════ MonitorDisplayEditor.Host 实现（屏幕侧最小接口）══════
    // Host implementation exposing the screen surface to the display editor.

    @Override public int screenWidth() { return width; }
    @Override public int screenHeight() { return height; }
    @Override public MonitorBlockEntity be() { return getBE(); }
    @Override public GraphEditor editor() { return editor; }
    @Override public BlockPos blockPos() { return blockPos; }
    @Override public void sendOp(GraphOp op) { MonitorScreen.super.sendOp(op); }
    @Override public UUID playerUUID() { return getPlayerUUID(); }

    @Override public boolean screenMouseDragged(double mx, double my, int btn, double dx, double dy) {
        return MonitorScreen.super.mouseDragged(mx, my, btn, dx, dy);
    }
    @Override public void openPixelEditor(GraphNode node) { openPixelEditorImpl(node); }

    /** 像素编辑器的实际打开逻辑（与 Host 接口方法解耦）/ the real pixel-editor opener. */
    private void openPixelEditorImpl(GraphNode node) { openPixelEditorImplBody(node); }

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

    // ── Display toggle button (graph editor mode) ──
    @Override
    protected void renderGraphCanvas(GuiGraphics g, int mx, int my, float pt) {
        if (displayEditor.active()) {
            displayEditor.render(g, mx, my);
        } else {
            editor.renderBg(g, mx, my);
            renderDisplayToggleButton(g);
        }
        // Settings panel overlay（显示模式与节点图模式都要画）/ settings overlay
        displayEditor.renderSettingsOverlay(g, mx, my);
        // Color picker — always on top of everything
        if (editor.colorPicker.isVisible()) {
            editor.colorPicker.render(g, mx, my);
        }
    }

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
    private void openPixelEditorImplBody(GraphNode node) {
        if (node.type != NodeType.IMAGE && node.type != NodeType.IMAGE_SEQUENCE) return;
        displayEditor.markPixelEditorTransfer();
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
    @Override public boolean isDisplayDragInProgress() { return displayEditor.displayDragInProgress(); }

    /** 存在包编辑模式：显示布局模式下为 1。 */
    @Override public int getPresenceMode() { return displayEditor.presenceMode(); }
    /** 显示布局模式下的光标屏幕 X；节点图模式返回 -1 走图光标。 */
    @Override public float getPresenceCursorX() { return displayEditor.presenceCursorX(); }
    /** 显示布局模式下的光标屏幕 Y；节点图模式返回 -1 走图光标。 */
    @Override public float getPresenceCursorY() { return displayEditor.presenceCursorY(); }
    /** 显示布局编辑器中正在拖拽的节点 id。 */
    @Override public int getPresenceDraggedNodeId() { return displayEditor.presenceDraggedNodeId(); }

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
        // 设置面板优先（与拆分前一致，与模式无关）：面板在离开显示模式后并不关闭，
        // 节点图模式下它仍然渲染，点击必须先归它，否则关闭/保存按钮与输入框不可点。
        // Settings panel takes priority (as before the split, regardless of mode): the panel
        // stays open after leaving display mode and still renders in graph mode, so its
        // clicks must come first or the close/save buttons and EditBoxes are unreachable.
        if (displayEditor.settingsOpen()) {
            return displayEditor.handleSettingsClick(mx, my, btn);
        }
        if (displayEditor.active()) {
            return displayEditor.handleClick(mx, my, btn);
        }
        // Graph editor mode: check display toggle button first
        if (btn == 0 && mx >= width - 76 && mx <= width - 16
            && my >= GraphEditor.TOP_BAR_H + 2 && my <= GraphEditor.TOP_BAR_H + 20) {
            displayEditor.setActive(true);
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

    @Override
    public void mouseMoved(double mx, double my) {
        if (displayEditor.active()) { displayEditor.handleMouseMoved(mx, my); return; }
        editor.mouseMoved(mx, my);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) {
        if (editor.colorPicker.isVisible() && editor.colorPicker.contains((int)mx, (int)my))
            return editor.colorPicker.mouseDragged(mx, my, btn, dx, dy);
        if (displayEditor.active()) {
            return displayEditor.handleMouseDragged(mx, my, btn, dx, dy);
        }
        return editor.mouseDragged(mx, my, btn, dx, dy) || super.mouseDragged(mx, my, btn, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int btn) {
        if (editor.colorPicker.isVisible() && editor.colorPicker.contains((int)mx, (int)my)) {
            editor.colorPicker.mouseReleased(mx, my, btn); return true;
        }
        if (displayEditor.active()) {
            return displayEditor.handleMouseReleased(mx, my);
        }
        editor.mouseReleased(mx, my, btn);
        return super.mouseReleased(mx, my, btn);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (editor.colorPicker.isVisible() && editor.colorPicker.mouseScrolled(mx, my, sy)) return true;
        if (displayEditor.active()) {
            return displayEditor.handleMouseScrolled(mx, sy);
        }
        return editor.mouseScrolled(mx, my, sx, sy);
    }

    @Override
    public boolean keyPressed(int key, int sc, int mod) {
        if (editor.colorPicker.isVisible()) {
            return editor.colorPicker.keyPressed(key, sc, mod);
        }
        // 设置面板打开时（显示模式或节点图模式都可能是它）输入优先交给设置面板：
        // 面板的 EditBox 焦点与 ESC 关闭语义都归 MonitorDisplayEditor。
        // While the settings panel is open (in either mode) it takes input first: the
        // EditBox focus and the ESC-to-close semantics belong to MonitorDisplayEditor.
        if (displayEditor.active() || displayEditor.settingsOpen()) {
            Boolean r = displayEditor.handleKeyPressed(key, sc, mod);
            if (r != null) return r;
        }
        if (editor.keyPressed(key, sc, mod)) return true;
        if (key == 256) {
            onClose(); return true;
        }
        if (key >= 32 && key <= 96) return true;
        return super.keyPressed(key, sc, mod);
    }

    @Override public boolean keyReleased(int key, int sc, int mod) {
        if (displayEditor.active()) return false;
        return editor.keyReleased(key, sc, mod) || super.keyReleased(key, sc, mod);
    }

    @Override public boolean charTyped(char ch, int mod) {
        if (editor.colorPicker.isVisible()) return editor.colorPicker.charTyped(ch, mod);
        // 与 keyPressed 同一条件：显示模式**或**设置面板打开时，先让显示编辑器处理；
        // 面板开着但没字段聚焦时它返回 false，字符再落到下面的节点图编辑区。
        // Same condition as keyPressed: let the display editor try first while display mode is
        // active OR the settings panel is open; when the panel has no focused field it returns
        // false and the character falls through to the node-graph edit boxes below.
        if (displayEditor.active() || displayEditor.settingsOpen()) {
            boolean r = displayEditor.handleCharTyped(ch, mod);
            if (r) return true;
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
        if (displayEditor.active()) displayEditor.preClose();
    }
}
