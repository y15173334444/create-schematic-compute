package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.graph.GraphOp;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import io.github.y15173334444.create_schematic_compute.network.GraphEditOpPacket;
import io.github.y15173334444.create_schematic_compute.network.GraphJoinPacket;
import io.github.y15173334444.create_schematic_compute.network.GraphLeavePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.UUID;
import java.util.function.Predicate;

/**
 * 所有节点图编辑界面的公共基类 / Common base for all node-graph editor screens.
 *
 * <p>持有 BlockPos 与 GraphEditor，管理界面生命周期（加入/离开协作会话、
 * BE 失效自动关闭），并实现 {@link GraphEditor.Host} 的多人协作样板方法。
 * 取代此前 7 个 Screen 各自复制粘贴的 AbstractContainerScreen 样板。</p>
 * <p>Holds BlockPos + GraphEditor, manages the screen lifecycle (join/leave
 * collab session, auto-close when the BE is invalidated), and implements the
 * {@link GraphEditor.Host} boilerplate previously copy-pasted into all 7 screens.</p>
 */
public abstract class AbstractGraphScreen extends Screen implements GraphEditor.Host {

    /** 编辑目标方块坐标 / position of the block being edited */
    protected final BlockPos blockPos;
    /** 节点图编辑器 / node graph editor */
    protected final GraphEditor editor;

    protected AbstractGraphScreen(Component title, BlockPos pos) {
        super(title);
        this.blockPos = pos;
        this.editor = new GraphEditor(this, this);
    }

    /** 子类在构造器中调用，设置节点过滤器 / called from subclass constructors to set the node filter */
    protected void setNodeFilter(Predicate<NodeType> filter) {
        editor.setNodeFilter(filter);
    }

    // ── Screen 生命周期 / lifecycle ──

    @Override
    protected void init() {
        super.init();
        // 加入多人协作编辑会话 / join collaborative editing session
        PacketDistributor.sendToServer(new GraphJoinPacket(blockPos));
    }

    @Override
    public void tick() {
        // BE 失效（方块被破坏 / 被卸载）时自动关闭，与迁移前 containerTick 行为一致
        // auto-close when the BE is gone (block broken / chunk unloaded) — same as pre-migration containerTick
        if (!isBlockEntityValid()) {
            onClose();
            return;
        }
        editor.clientTick();
    }

    @Override
    public void onClose() {
        preClose();                               // 子类钩子：关界面前把 EditBox 输入写回 BE 等 / subclass hook: write EditBox input back to the BE etc.
        var be = getBE();
        if (be != null) be.pendingLocalOps = 0;   // 复位本地编辑 op 守卫（对应 5892caa 的 pendingLocalOps 修复）/ reset pending-op guard
        editor.onClose();                         // 保存临时视角书签 / save temporary view bookmark
        editor.clearRemotePresences();
        // 离开多人协作编辑会话 / leave collaborative editing session
        PacketDistributor.sendToServer(new GraphLeavePacket(blockPos));
        super.onClose();
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        this.renderBackground(g, mx, my, pt);     // 背景层 / background layer
        renderGraphCanvas(g, mx, my, pt);         // 节点画布 / graph canvas
        for (var r : this.renderables)            // EditBox 等 widget 在画布之上（复刻 AbstractContainerScreen 契约）/ widgets above the canvas
            r.render(g, mx, my, pt);
        // tooltip 由 GraphEditor/NodeRenderer 自行绘制（AbstractContainerScreen.renderTooltip 仅画槽位提示，无槽位界面下为空操作）
        // tooltips are drawn by GraphEditor/NodeRenderer themselves; AbstractContainerScreen.renderTooltip only draws slot tips (no-op without slots)
    }

    /**
     * 节点画布渲染钩子。默认委托给 {@link GraphEditor#renderBg}；
     * 子类可覆盖以叠加自定义绘制（如 RadarScreen 的工具栏、MonitorScreen 的显示模式画布）。
     * Canvas rendering hook. Defaults to {@link GraphEditor#renderBg};
     * subclasses may override to layer custom drawing on top (radar toolbar, monitor display mode).
     */
    protected void renderGraphCanvas(GuiGraphics g, int mx, int my, float pt) {
        editor.renderBg(g, mx, my);
    }

    // ── 输入事件委托（所有子类共享）/ input delegation shared by all subclasses ──

    @Override public boolean mouseClicked(double mx, double my, int btn) { return editor.mouseClicked(mx, my, btn) || super.mouseClicked(mx, my, btn); }
    @Override public boolean mouseReleased(double mx, double my, int btn) { editor.mouseReleased(mx, my, btn); return super.mouseReleased(mx, my, btn); }
    @Override public void mouseMoved(double mx, double my) { editor.mouseMoved(mx, my); }
    @Override public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) { return editor.mouseDragged(mx, my, btn, dx, dy) || super.mouseDragged(mx, my, btn, dx, dy); }
    @Override public boolean mouseScrolled(double mx, double my, double sx, double sy) { return editor.mouseScrolled(mx, my, sx, sy); }
    @Override public boolean keyPressed(int key, int sc, int mod) {
        if (editor.keyPressed(key, sc, mod)) return true;
        if (key == 256) { onClose(); return true; } // ESC
        if (key >= 32 && key <= 96) return true;     // 可打印字符交给 charTyped / printable keys go to charTyped
        return super.keyPressed(key, sc, mod);
    }
    @Override public boolean keyReleased(int key, int sc, int mod) { return editor.keyReleased(key, sc, mod) || super.keyReleased(key, sc, mod); }
    @Override public boolean charTyped(char ch, int mod) { return editor.charTyped(ch, mod) || super.charTyped(ch, mod); }

    // ── GraphEditor.Host 多人协作 / multiplayer collaboration ──

    @Override public BlockPos getBlockPos() { return blockPos; }
    @Override public UUID getPlayerUUID() {
        return Minecraft.getInstance().player != null
            ? Minecraft.getInstance().player.getUUID() : UUID.randomUUID();
    }
    @Override public GraphEditor getEditor() { return editor; }
    @Override public String getPlayerName() {
        return Minecraft.getInstance().player != null
            ? Minecraft.getInstance().player.getName().getString() : "";
    }
    @Override public Screen asScreen() { return this; }
    @Override public void sendOp(GraphOp op) {
        var be = getBE();
        if (be != null) be.pendingLocalOps++;     // 本地编辑 op 计数（回弹保护）/ count local edit op
        PacketDistributor.sendToServer(new GraphEditOpPacket(op));
    }
    @Override public void onRemoteOp(GraphOp op) { editor.onRemoteOp(op); }

    // ── 子类覆盖点 / subclass hooks ──

    /**
     * BE 失效检查（方块被破坏 / 卸载时为 false），供 {@link #tick()} 自动关界面。
     * Validity check used by {@link #tick()} to auto-close; false once the block is gone.
     */
    protected abstract boolean isBlockEntityValid();

    /**
     * 返回当前客户端 BlockEntity，供 sendOp / onClose 访问 pendingLocalOps 守卫。
     * 子类返回各自具体类型（协变返回即可）。
     * Current client-side BlockEntity for the pendingLocalOps guard; subclasses return their concrete type (covariant).
     */
    protected abstract SyncedGraphBlockEntity getBE();

    /**
     * 关界面前钩子：子类可在此把 EditBox 输入写回 BE（如 RadarScreen 的 applyInputs + hideInputs）。
     * 默认空实现。 / Pre-close hook; default no-op.
     */
    protected void preClose() {}
}
