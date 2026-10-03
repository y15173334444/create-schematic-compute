package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.graph.BlockNodeAllowances;
import io.github.y15173334444.create_schematic_compute.graph.EvalSnapshot;
import io.github.y15173334444.create_schematic_compute.graph.GraphNode;
import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import io.github.y15173334444.create_schematic_compute.network.BlueprintTogglePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.Map;

/**
 * 功放电脑图编辑器屏幕（ProgramComputer 同款，节点准入 = AMPLIFIER：含 AUDIO 类 + 时序）。
 * <p>双击 MUSIC 节点打开 NBS 钢琴卷帘编辑器（像素编辑器同款管线：转移期间跳过离开协作会话，
 * 关闭后恢复图编辑器或便携终端包装）。Double-click a MUSIC node to open the NBS piano-roll
 * editor (pixel-editor pipeline: the collab session survives the transfer; closing returns
 * to the graph editor or the portable-terminal wrapper).</p>
 */
public class AmplifierComputerScreen extends AbstractGraphScreen {

    // ── Double-click tracking ──
    private long lastClickTime = 0;
    private int lastClickNodeId = -1;
    /** NBS 编辑器转移时置位：本次 onClose 跳过离开协作会话。 */
    private boolean transferToNbsEditor = false;

    public AmplifierComputerScreen(BlockPos pos) {
        super(Component.translatable("container." + SchematicCompute.MOD_ID + ".amplifier_computer"), pos);
        setNodeAllowance(BlockNodeAllowances.AMPLIFIER);
    }

    /** 关闭时是否跳过离开协作会话（NBS 编辑器转移用；消费一次即复位）。 */
    @Override protected boolean skipLeaveOnClose() {
        boolean t = transferToNbsEditor;
        transferToNbsEditor = false;
        return t;
    }

    @Override protected AmplifierComputerBlockEntity getBE() {
        if (minecraft != null && minecraft.level != null) {
            if (minecraft.level.getBlockEntity(blockPos) instanceof AmplifierComputerBlockEntity be) return be;
        }
        return null;
    }
    @Override protected boolean isBlockEntityValid() {
        return minecraft != null && minecraft.level != null
            && minecraft.level.getBlockEntity(blockPos) instanceof AmplifierComputerBlockEntity;
    }
    @Override public NodeGraph getGraph() { AmplifierComputerBlockEntity be = getBE(); return be != null ? be.getNodeGraph() : new NodeGraph(); }
    @Override public boolean isRunning() { AmplifierComputerBlockEntity be = getBE(); return be != null && be.isRunning(); }
    @Override public Map<Integer, Boolean> getFlipflopStates() { AmplifierComputerBlockEntity be = getBE(); return be != null ? be.getFlipflopStates() : null; }
    @Override public EvalSnapshot getCachedEvalSnapshot() {
        AmplifierComputerBlockEntity be = getBE();
        return be != null ? be.getCachedEvalSnapshot() : null;
    }

    @Override
    public void toggleRunning(boolean start) {
        AmplifierComputerBlockEntity be = getBE();
        if (be != null) { be.setRunning(start); PacketDistributor.sendToServer(new BlueprintTogglePacket(be.getBlockPos(), start)); }
    }

    // ── 双击 MUSIC 节点 → NBS 编辑器 / double-click a MUSIC node → the NBS editor ──

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        if (btn == 0 && getBE() != null) {
            long now = System.currentTimeMillis();
            GraphNode clicked = null;
            float hitSx = 0, hitSy = 0;
            for (var n : getBE().getNodeGraph().nodes) {
                if (n.type != NodeType.MUSIC) continue;
                float sx = editor.c2sX(n.x), sy = editor.c2sY(n.y);
                float sw = GraphEditor.NW * editor.zoom;
                float nh = (GraphEditor.HH + GraphEditor.PH * (n.inputs() + n.outputs())) * editor.zoom + 4;
                if (mx >= sx && mx <= sx + sw && my >= sy && my <= sy + nh) {
                    clicked = n; hitSx = sx; hitSy = sy; break;
                }
            }
            if (clicked != null) {
                // 展开指示器区域让给展开切换（与 MonitorScreen 同口径）
                float ix = hitSx + (GraphEditor.NW - 22) * editor.zoom;
                float iy = hitSy + 2 * editor.zoom;
                float is = 12 * editor.zoom;
                boolean onExpand = mx >= ix && mx <= ix + is && my >= iy && my <= iy + is;
                if (!onExpand && clicked.id == lastClickNodeId && now - lastClickTime < 400) {
                    openNbsEditor(clicked);
                    lastClickNodeId = -1;
                    return true;
                }
                if (!onExpand) { lastClickTime = now; lastClickNodeId = clicked.id; }
                else { lastClickNodeId = -1; }
            } else {
                lastClickNodeId = -1;
            }
        }
        return super.mouseClicked(mx, my, btn);
    }

    /** 打开 NBS 钢琴卷帘编辑器（转移前标记跳过离开协作会话）。
     *  @OnlyIn(CLIENT)：new NbsEditorScreen 是客户端类引用，专用服务器上由 dist cleaner 剥离。 */
    @net.neoforged.api.distmarker.OnlyIn(net.neoforged.api.distmarker.Dist.CLIENT)
    private void openNbsEditor(GraphNode node) {
        if (node.type != NodeType.MUSIC) return;
        transferToNbsEditor = true;
        Minecraft.getInstance().setScreen(new io.github.y15173334444.create_schematic_compute.client.NbsEditorScreen(
            blockPos, node, computeNbsEditorReturn()));
    }

    /** NBS 编辑器关闭后要恢复的界面：便携终端包装内 → 重建包装；否则新的本屏（重 join 会话，幂等）。 */
    private Screen computeNbsEditorReturn() {
        var mc = Minecraft.getInstance();
        if (mc.screen instanceof io.github.y15173334444.create_schematic_compute.client.PortableTerminalScreen.HostWrapper w
            && w.getInnerScreen() == this
            && w.getTerminalScreen() instanceof io.github.y15173334444.create_schematic_compute.client.PortableTerminalScreen pts) {
            return pts.wrapForEditing(new AmplifierComputerScreen(blockPos));
        }
        return new AmplifierComputerScreen(blockPos);
    }
}
