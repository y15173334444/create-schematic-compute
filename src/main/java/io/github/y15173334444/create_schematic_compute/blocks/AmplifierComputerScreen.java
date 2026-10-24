package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.graph.BlockNodeAllowances;
import io.github.y15173334444.create_schematic_compute.graph.EvalSnapshot;
import io.github.y15173334444.create_schematic_compute.graph.GraphNode;
import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import io.github.y15173334444.create_schematic_compute.network.AmplifierSettingsPacket;
import io.github.y15173334444.create_schematic_compute.network.BlueprintTogglePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 功放电脑图编辑器屏幕（ProgramComputer 同款，节点准入 = AMPLIFIER：含 AUDIO 类 + 时序）。
 * <p>双击 MUSIC 节点打开 NBS 钢琴卷帘编辑器（像素编辑器同款管线：转移期间跳过离开协作会话，
 * 关闭后恢复图编辑器或便携终端包装）。</p>
 * <p>第二行工具按钮（音响/雷达同款）：「监听音频节点」面板——盖子频谱的监听目标，默认自动
 * （图最上方 MUSIC 节点，封装 IO 同款排序），可指定任一 MUSIC 节点；选择即时生效（随点随发）。
 * Double-click a MUSIC node to open the NBS piano-roll editor (pixel-editor pipeline: the
 * collab session survives the transfer; closing returns to the graph editor or the
 * portable-terminal wrapper). A second toolbar row (speaker/radar-style) hosts the monitor
 * panel — the lid spectrum's monitored source, auto (graph-topmost MUSIC node, encapsulation-IO
 * ordering) by default or pinned to any MUSIC node; picks apply immediately.</p>
 */
public class AmplifierComputerScreen extends AbstractGraphScreen {

    // ── Double-click tracking ──
    private long lastClickTime = 0;
    private int lastClickNodeId = -1;
    /** NBS 编辑器转移时置位：本次 onClose 跳过离开协作会话。 */
    private boolean transferToNbsEditor = false;

    // ── 监听设置面板 / monitor panel ──
    private static final int MH = 18;             // 面板行高 / panel row height
    private static final int PANEL_W = 240;
    private static final int TITLE_H = 14;
    private static final int VISIBLE_ROWS = 6;    // 超出出滚轮 / rows beyond this scroll
    private boolean showMonitor;
    private int monitorScroll;

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

    // ── 监听设置面板（音响设置面板同款骨架）/ monitor panel (speaker-settings skeleton) ──

    /** 工具行 y（雷达同款）：紧贴基础工具栏下沿 / 底置。 / radar-style row position. */
    private int toolbarRowY() {
        return NodeRenderer.isToolbarBottom() ? height - 44 : GraphEditor.TOP_BAR_H + 24;
    }

    /** 监听候选：图内全部 MUSIC 节点，封装 IO 同款排序（y 升序、id 决胜）——与
     *  {@code resolveMonitoredNode} 的「最上方」口径一致，面板列表顺序即自动目标顺序。
     *  Monitor candidates: every MUSIC node in the graph, encapsulation-IO ordering (y
     *  ascending, id tie-break) — the same "topmost" rule as {@code resolveMonitoredNode}, so
     *  the panel list order matches the auto target. */
    private List<GraphNode> monitorCandidates() {
        var list = new ArrayList<GraphNode>();
        for (var n : getGraph().nodes) if (n.type == NodeType.MUSIC) list.add(n);
        list.sort(Comparator.<GraphNode>comparingDouble(n -> n.y).thenComparingInt(n -> n.id));
        return list;
    }

    /** 面板矩形 {px, py, pw, ph}（底置工具行时向上展开）。 / panel rect; grows upward when the toolbar is bottom-docked. */
    private int[] monitorPanelRect() {
        int rows = 1 + monitorCandidates().size();
        int ph = TITLE_H + Math.min(rows, VISIBLE_ROWS) * MH + 4;
        int toolY = toolbarRowY();
        int py = NodeRenderer.isToolbarBottom() ? toolY - ph - 6 : toolY + 22;
        return new int[]{4, py, PANEL_W, ph};
    }

    /** 画布钩子：节点编辑器 + 第二行监听按钮 + 面板 / canvas hook: editor + toolbar button + panel */
    @Override
    protected void renderGraphCanvas(GuiGraphics g, int mx, int my, float pt) {
        editor.renderBg(g, mx, my);
        int y = toolbarRowY();
        g.fill(4, y, 4 + 68, y + MH, showMonitor ? NodeRenderer.ACC() : NodeRenderer.PINS());
        g.renderOutline(4, y, 68, MH, NodeRenderer.CSB());
        g.drawString(font, I18n.get("gui.create_schematic_compute.amplifier.monitor_title"), 8, y + 5, 0xFFCCCCCC);
        if (showMonitor) renderMonitorPanel(g, mx, my);
    }

    private void renderMonitorPanel(GuiGraphics g, int mx, int my) {
        var candidates = monitorCandidates();
        int rows = 1 + candidates.size();
        int maxScroll = Math.max(0, rows - VISIBLE_ROWS);
        monitorScroll = Math.min(monitorScroll, maxScroll);
        int[] r = monitorPanelRect();
        int px = r[0], py = r[1], pw = r[2], ph = r[3];
        g.fill(px, py, px + pw, py + ph, NodeRenderer.withAlpha(NodeRenderer.PBG(), 0xDD));
        g.renderOutline(px, py, pw, ph, NodeRenderer.CSB());
        g.drawString(font, "§6§l" + I18n.get("gui.create_schematic_compute.amplifier.monitor_title"), px + 6, py + 4, 0xFFFFFFFF);

        // 生效目标行高亮：自动模式，或指定编号悬空（显示已回落最上方）→ 自动行；否则指定节点行。
        // Highlight the effective source: auto mode, or a dangling pin (display already fell
        // back to the topmost) → the auto row; otherwise the pinned node's row.
        var be = getBE();
        int pinned = be != null ? be.monitoredNode : AmplifierComputerBlockEntity.AUTO_MONITOR;
        GraphNode eff = AmplifierComputerBlockEntity.resolveMonitoredNode(getGraph(), pinned);
        boolean autoActive = pinned == AmplifierComputerBlockEntity.AUTO_MONITOR
            || eff == null || eff.id != pinned;

        int contentY = py + TITLE_H;
        for (int i = 0; i < rows; i++) {
            int row = i - monitorScroll;
            if (row < 0 || row >= VISIBLE_ROWS) continue;
            int ry = contentY + row * MH;
            boolean active = i == 0 ? autoActive
                : !autoActive && candidates.get(i - 1).id == pinned;
            boolean hover = !active && mx >= px + 2 && mx <= px + pw - 2 && my >= ry && my <= ry + MH;
            if (active) g.fill(px + 2, ry, px + pw - 2, ry + MH, NodeRenderer.ACC());
            else if (hover) g.fill(px + 2, ry, px + pw - 2, ry + MH, NodeRenderer.HOV());
            g.renderOutline(px + 2, ry, pw - 4, MH, NodeRenderer.CSB());
            String label = i == 0
                ? I18n.get("gui.create_schematic_compute.amplifier.monitor_auto")
                : songLabel(candidates.get(i - 1));
            g.drawString(font, truncate(label, pw - 24), px + 8, ry + 5,
                active ? 0xFFFFFFFF : 0xFFCCCCCC);
        }
        if (maxScroll > 0) {
            int trackX = px + pw - 3, trackY = contentY + 1, trackH = ph - TITLE_H - 6;
            int thumbH = Math.max(8, trackH * VISIBLE_ROWS / rows);
            int thumbY = trackY + (trackH - thumbH) * monitorScroll / maxScroll;
            g.fill(trackX, trackY, trackX + 2, trackY + trackH, NodeRenderer.withAlpha(NodeRenderer.PINS(), 0xAA));
            g.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, NodeRenderer.ACC());
        }
    }

    /** 行显示名：曲名（NBS 元数据），空则「未命名曲目」。 / row label: the song name (NBS metadata), or "untitled". */
    private static String songLabel(GraphNode n) {
        String s = n.song != null ? n.song.songName : "";
        return s.isBlank() ? I18n.get("gui.create_schematic_compute.amplifier.monitor_untitled") : s;
    }

    /** 超宽截断加省略号。 / truncate overwide labels with an ellipsis. */
    private String truncate(String s, int maxW) {
        if (font.width(s) <= maxW) return s;
        return font.plainSubstrByWidth(s, Math.max(0, maxW - font.width("…"))) + "…";
    }

    /** 选择监听目标：客户端字段即时反馈，权威值随块更新回推（静音钮同款随点随发）。
     *  Pick the monitor target: the client field updates at once, the authoritative value rides
     *  the block update back (same apply-on-click contract as the mute switch). */
    private void pickMonitor(int nodeId) {
        var be = getBE();
        if (be == null) return;
        be.monitoredNode = nodeId;
        PacketDistributor.sendToServer(new AmplifierSettingsPacket(be.getBlockPos(), nodeId));
    }

    /** 监听按钮/面板的点击消费（面板矩形吞点击，空隙不穿透画布）。返回 false = 不在本面板。
     *  Consume monitor button/panel clicks (the panel rect swallows everything — gaps never
     *  reach the canvas). false = not over this UI. */
    private boolean handleMonitorClicks(double mx, double my) {
        int toolY = toolbarRowY();
        if (mx >= 4 && mx <= 72 && my >= toolY && my <= toolY + MH) {
            showMonitor = !showMonitor;
            monitorScroll = 0;
            return true;
        }
        if (!showMonitor) return false;
        int[] r = monitorPanelRect();
        if (mx < r[0] || mx > r[0] + r[2] || my < r[1] || my > r[1] + r[3]) return false;
        var candidates = monitorCandidates();
        int rows = 1 + candidates.size();
        int rowIdx = (int) ((my - (r[1] + TITLE_H)) / MH) + monitorScroll;
        if (my >= r[1] + TITLE_H && rowIdx >= 0 && rowIdx < rows) {
            pickMonitor(rowIdx == 0 ? AmplifierComputerBlockEntity.AUTO_MONITOR
                : candidates.get(rowIdx - 1).id);
        }
        return true;
    }

    // ── 双击 MUSIC 节点 → NBS 编辑器 / double-click a MUSIC node → the NBS editor ──

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        // 监听按钮/面板先行消费（面板盖住的画布区域不得触发双击开 NBS 编辑器）。
        // The monitor button/panel consumes first (canvas under the panel must not trigger
        // the double-click NBS editor).
        if (btn == 0 && handleMonitorClicks(mx, my)) return true;
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

    /** 面板内滚轮滚动列表（不缩放画布）。 / wheel scrolls the panel list (never zooms the canvas). */
    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (showMonitor) {
            int[] r = monitorPanelRect();
            if (mx >= r[0] && mx <= r[0] + r[2] && my >= r[1] && my <= r[1] + r[3]) {
                int maxScroll = Math.max(0, 1 + monitorCandidates().size() - VISIBLE_ROWS);
                if (maxScroll > 0)
                    monitorScroll = Math.max(0, Math.min(maxScroll, monitorScroll - (int) Math.signum(sy)));
                return true;
            }
        }
        return super.mouseScrolled(mx, my, sx, sy);
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
