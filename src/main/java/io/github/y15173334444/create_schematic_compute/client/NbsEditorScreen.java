package io.github.y15173334444.create_schematic_compute.client;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.blocks.GraphBlockEntity;
import io.github.y15173334444.create_schematic_compute.blocks.GraphEditor;
import io.github.y15173334444.create_schematic_compute.blocks.NodeRenderer;
import io.github.y15173334444.create_schematic_compute.client.audio.CscAudioEngine;
import io.github.y15173334444.create_schematic_compute.graph.GraphNode;
import io.github.y15173334444.create_schematic_compute.graph.GraphOp;
import io.github.y15173334444.create_schematic_compute.graph.MusicTransport;
import io.github.y15173334444.create_schematic_compute.graph.NbsSong;
import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import io.github.y15173334444.create_schematic_compute.graph.NoteEvent;
import io.github.y15173334444.create_schematic_compute.network.GraphEditOpPacket;
import io.github.y15173334444.create_schematic_compute.network.GraphSaveRequestPacket;
import io.github.y15173334444.create_schematic_compute.network.NoteEventPacket;
import io.github.y15173334444.create_schematic_compute.network.SongSync;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * NBS 钢琴卷帘编辑器屏幕（布局与输入路由），双击 MUSIC 节点打开（像素编辑器同款管线）。
 * plan §3.2 三件之三；内核 {@link NbsEditorKernel}（编辑 + 撤销）与卷帘 {@link NbsPianoRoll}
 * （网格渲染 + 落点）已拆分。形态：钢琴卷帘 + 层面板（名称/音量/声像/锁定）+ 传输条
 * （播放/停止/循环/速度）+ 试听（同款播放路径，按听者位置发声）+ 导入/导出 .nbs。
 * <p>NBS piano-roll editor screen (layout and input routing), opened by double-clicking a
 * MUSIC node (same pipeline as the pixel editor). Part 3 of 3 in plan §3.2: the kernel
 * {@link NbsEditorKernel} (edits + undo) and the roll {@link NbsPianoRoll} (grid rendering +
 * cell hits) are split out. Shape: piano roll + layer panel (name/volume/panning/lock) +
 * transport bar (play/stop/loop/tempo) + audition (same playback path, played at the listener)
 * + .nbs import/export.</p>
 *
 * <p>实现 {@link GraphEditor.Host} 使整图同步守卫（isPixelEditorOpen）与 sendOp 的
 * pendingLocalOps 计数继续生效。编辑本地进行（内核持有节点曲目活引用），保存/关闭/导入时
 * 经 {@code SET_SONG}（blob 分片 + 引用 op）定向同步，再走既有保存请求落盘（plan §3.2）。
 * Implements {@link GraphEditor.Host} so the full-sync guard (isPixelEditorOpen) and the
 * sendOp pendingLocalOps counter keep working. Edits are local (the kernel holds the node's
 * live song); save / close / import sync via {@code SET_SONG} (blob chunks + referencing op),
 * then the existing save request persists (plan §3.2).</p>
 */
public class NbsEditorScreen extends Screen implements GraphEditor.Host {

    // ── 布局常量 / layout constants ──
    private static final int TOP_H = 28;
    private static final int TRANSPORT_H = 26;
    private static final int PANEL_W = 176;
    private static final int ROW_H = 13;
    private static final int PROPS_H = 112;

    // ── 配色 / colours ──
    private static final int C_BG = 0xFF1A1712;
    private static final int C_PANEL = NodeRenderer.PBG();
    private static final int C_BORDER = NodeRenderer.PBR();
    private static final int C_BTN = 0xFF3A3428;
    private static final int C_BTN_ON = 0xFF3A5A2A;
    private static final int C_HOVER = 0xFF5A4A3A;
    private static final int C_SEL = 0xFF4A4432;
    private static final int C_DEL = 0xFF7A4A3A;
    private static final int C_TEXT = 0xFFFFFFFF;
    private static final int C_TEXT_DIM = 0xFFBFB8A8;

    /** 16 原版乐器显示名语言键后缀，**按 NBS 格式乐器索引序**（与 SampleBank/曲目字节一致；
     *  与原版枚举序不同——错序会让 1–7 号乐器标注错位）。 */
    private static final String[] INSTRUMENT_KEYS = {
        "harp", "bass", "basedrum", "snare", "hat", "guitar", "flute", "bell",
        "chime", "xylophone", "iron_xylophone", "cow_bell", "didgeridoo", "bit", "banjo", "pling"
    };

    // ── 核心状态 / core state ──
    private final BlockPos blockPos;
    private final GraphNode node;          // BE 图中的活跃引用（整图同步守卫防止被替换）
    private final Screen returnScreen;
    private final NbsEditorKernel kernel;
    private final NbsPianoRoll roll = new NbsPianoRoll();
    private final MusicTransport preview = new MusicTransport();

    // ── 双样式 / dual style ──
    // ROLL = 钢琴卷帘（默认，未调整行为不变）；LANE = 层序器 + 底部钢琴条（社区原生布局）。
    // ROLL = the piano roll (default — unchanged behaviour); LANE = the lane grid + bottom
    // piano strip (the community-native layout).
    private enum View { ROLL, LANE }
    private View view = View.ROLL;
    private final NbsLaneGrid laneGrid = new NbsLaneGrid(this::audition);
    private final NbsPianoStrip pianoStrip = new NbsPianoStrip();
    /** 层序器工具键（NBS 基准键 45 起）。 / the lane-view tool key (starts at the NBS base key 45). */
    private int toolKey = 45;
    /** 钢琴条跟弹闪烁：每键高亮到期时间戳（ms）。 / play-along key flash expiry per key (ms). */
    private final long[] keyFlashUntil = new long[88];

    private int currentLayer = 0;
    private int currentInstrument = 0;
    private int layerScroll = 0;
    private int draggingSlider = 0;        // 0=无 1=音量 2=声像
    private int draggingScrollBar = 0;     // 0=无 1=横向（时间轴） 2=纵向（层行/键轴）
    private long lastWheelAt = 0;          // 滚轮活动时间戳（滚动防误触窗口起点）
    private boolean importOpen = false, exportOpen = false, showGuide = false;
    private List<Path> importFiles = List.of();
    /** 导入弹窗可见行数（渲染 / 命中 / 滚动钳位共用）。/ import dialog visible rows (render, hit-test and scroll clamp share it). */
    static final int IMPORT_ROWS = 11;
    private int importScroll = 0;
    private String feedbackText = "";
    private long feedbackUntil = 0;

    // ── 输入框 / edit boxes ──
    private EditBox tempoBox, layerNameBox, exportNameBox;

    public NbsEditorScreen(BlockPos pos, GraphNode node, Screen returnScreen) {
        super(Component.translatable("container." + SchematicCompute.MOD_ID + ".nbs_editor"));
        this.blockPos = pos;
        this.node = node;
        this.returnScreen = returnScreen;
        if (node.song == null) node.song = new NbsSong();
        this.kernel = new NbsEditorKernel(node.song);
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override
    protected void init() {
        super.init();
        var font = Minecraft.getInstance().font;
        tempoBox = new EditBox(font, 0, 0, 40, 14, Component.literal(""));
        tempoBox.setMaxLength(6);
        tempoBox.setValue(tpsDisplay(kernel.song().tempo));
        layerNameBox = new EditBox(font, 0, 0, PANEL_W - 20, 14, Component.literal(""));
        layerNameBox.setMaxLength(32);
        exportNameBox = new EditBox(font, 0, 0, 140, 14, Component.literal(""));
        exportNameBox.setMaxLength(48);
    }

    // ══════════════ GraphEditor.Host ══════════════

    @Override public NodeGraph getGraph() { GraphBlockEntity be = getBE(); return be != null ? be.getNodeGraph() : new NodeGraph(); }
    @Override public void saveGraph() {
        var be = getBE();
        if (be == null) return;
        // 保存走既有保存请求（服务端权威落盘；plan §3.2 编辑会话）
        PacketDistributor.sendToServer(new GraphSaveRequestPacket(be.getBlockPos()));
    }
    @Override public void toggleRunning(boolean start) { /* no-op */ }
    @Override public boolean isRunning() { GraphBlockEntity be = getBE(); return be != null && be.isRunning(); }
    @Override public Screen asScreen() { return this; }
    @Override public BlockPos getBlockPos() { return blockPos; }
    @Override public UUID getPlayerUUID() {
        return minecraft != null && minecraft.player != null ? minecraft.player.getUUID() : UUID.randomUUID();
    }
    @Override public String getPlayerName() {
        return minecraft != null && minecraft.player != null ? minecraft.player.getName().getString() : "";
    }
    @Override public boolean isPixelEditorOpen() { return true; }
    // 出口收口（tech-debt P1-10）：NBS 编辑器与来源屏同属一个编辑会话（onClose 回 returnScreen），
    // SET_SONG/编辑 op 携带节点 id —— 必须共享来源屏编辑器的漏斗实例（temp-id 挂起 + ACK 重映射），
    // 否则新建 MUSIC 节点 ACK 未回的窗口内保存，曲目 op 引用临时 id 被服务端静默丢弃。
    // Outlet convergence (tech-debt P1-10): the NBS editor and its origin share one editing
    // session (onClose returns to returnScreen) and SET_SONG carries node ids — so the stash
    // and ACK remap must share the originating editor's funnel instance, or a save inside a
    // fresh MUSIC node's pre-ACK window references a temp id and is silently dropped
    // server-side (the pixel editor's outlet-convergence pattern).
    @Override public GraphEditor getEditor() {
        return returnScreen instanceof GraphEditor.Host h ? h.getEditor() : null;
    }
    @Override public void sendOp(GraphOp op) {
        var ed = getEditor();
        if (ed != null) {
            ed.sendOp(op); // 统一出口：temp-id 挂起守卫 + 来源屏的 pendingLocalOps 计数 / the funnel
            return;
        }
        var be = getBE();
        if (be != null) be.setPendingLocalOps(be.getPendingLocalOps() + 1);
        PacketDistributor.sendToServer(new GraphEditOpPacket(op));
    }

    private GraphBlockEntity getBE() {
        if (minecraft != null && minecraft.level != null) {
            if (minecraft.level.getBlockEntity(blockPos) instanceof GraphBlockEntity be) return be;
        }
        return null;
    }

    // ══════════════ 生命周期 / lifecycle ══════════════

    @Override
    public void tick() {
        if (getBE() == null) {
            minecraft.setScreen(null);
            return;
        }
        // 试听推进：同款传输推进（MusicTransport）→ 同款发声（CscAudioEngine，听者位置）
        if (preview.isPlaying()) {
            // 播放跟随（社区 page-by-page）：头越过视口缘才翻页，不与用户滚动抢方向
            // Playback follow (community page-by-page): flips only at the viewport edges.
            if (view == View.ROLL) roll.followPlayhead(preview.head, gridW());
            else laneGrid.followPlayhead(preview.head, gridW());
            List<NoteEvent> evs = preview.advance(kernel.song(), 0.05f,
                io.github.y15173334444.create_schematic_compute.graph.MusicTransport.PREROLL_SECONDS);
            for (NoteEvent e : evs) {
                flashKey(e.key());   // 钢琴条跟着谱子弹 / the strip presses along with the score
                io.github.y15173334444.create_schematic_compute.client.audio.CscAudioEngine.playAtListener(e);
            }
        }
    }

    @Override
    public void onClose() {
        // BE 已消失（方块被拆/区块卸载）：目标会话不存在——跳过同步（op 必被服务端 REJECT）、
        // 不回旧实例（tick 的存活守卫同口径）。
        // The BE is gone (block broken / chunk unloaded): the target session no longer
        // exists — skip the sync (the server would REJECT the op) and never resurface the
        // stale return screen (same policy as tick's liveness guard).
        if (getBE() == null) { minecraft.setScreen(null); return; }
        applyPendingInputs();
        syncSongToServer();                 // 关闭前把本地编辑定向同步（像素编辑器同款）
        // 不调用 super.onClose()：Screen 默认实现会 setScreen(null) 覆盖返回界面
        // （像素编辑器同款口径）——直接恢复打开前的图编辑器/终端包装。
        if (returnScreen != null) minecraft.setScreen(returnScreen);
        else super.onClose();
    }

    /** 保存/关闭/导入时把工作曲目上传到服务端（SET_SONG）；成功后标已同步。 */
    private void syncSongToServer() {
        if (!kernel.isDirty()) return;
        if (SongSync.upload(blockPos, -1, node.id, kernel.song(), getPlayerUUID(), this::sendOp)) {
            kernel.markClean();
            saveGraph();
            feedback(I18n.get("gui.create_schematic_compute.nbs_saved"));
        } else {
            feedback("§c" + I18n.get("gui.create_schematic_compute.nbs_too_big"));
        }
    }

    /** 把输入框的待提交值写回曲目（速度 / 层名）。 / commit pending EditBox values. */
    private void applyPendingInputs() {
        if (tempoBox != null && tempoBox.isFocused()) commitTempo();
        if (layerNameBox != null && layerNameBox.isFocused()) commitLayerName();
    }

    private void commitTempo() {
        try {
            // 输入框按 tick/s 编辑（小数）；NBS 头部 tempo = tick/s × 100
            float tps = Float.parseFloat(tempoBox.getValue().trim());
            kernel.setTempo(Math.round(tps * 100f));
        } catch (NumberFormatException ignored) {
            // fall through: restore display below
        }
        tempoBox.setValue(tpsDisplay(kernel.song().tempo));
        tempoBox.setFocused(false);
    }

    /** 显示用速度（tick/s）。tempo = tick/s × 100。 */
    private static String tpsDisplay(int tempo) {
        float tps = tempo / 100f;
        return tps == Math.floor(tps) && !Float.isInfinite(tps)
            ? String.valueOf((int) tps) : String.valueOf(tps);
    }

    /** 导入列表滚动钳位（纯函数）：停在 [0, max(0, 文件数 − 可见行)]。
     *  Import-list scroll clamp (pure): stays within [0, max(0, files − visible rows)]. */
    static int clampImportScroll(int scroll, int fileCount) {
        return Math.max(0, Math.min(scroll, Math.max(0, fileCount - IMPORT_ROWS)));
    }

    private void commitLayerName() {
        if (currentLayer >= 0 && currentLayer < kernel.song().layers.size()) {
            kernel.setLayerName(currentLayer, layerNameBox.getValue());
        }
        layerNameBox.setFocused(false);
    }

    private void feedback(String text) {
        feedbackText = text;
        feedbackUntil = System.currentTimeMillis() + 2500;
    }

    // ══════════════ 渲染 / rendering ══════════════

    private int rollX() { return PANEL_W; }
    private int rollY() { return TOP_H; }
    private int rollW() { return width - PANEL_W; }
    private int rollH() { return height - TOP_H - TRANSPORT_H; }
    /** 层序器视图：钢琴条顶缘（条占网格区底部）。 / lane view: piano-strip top. */
    private int stripY() { return rollY() + rollH() - NbsPianoStrip.STRIP_H; }
    /** 层序器视图：网格高（扣掉钢琴条）。 / lane view: grid height minus the strip. */
    private int laneGridH() { return rollH() - NbsPianoStrip.STRIP_H; }
    /** 层列表行高（层序器视图加高与网格行对齐；卷帘视图维持原值）。 */
    private int layerRowH() { return view == View.LANE ? LANE_ROW_H : ROW_H; }

    // ── 网格可视区与滚动条几何（单一真相源）/ grid viewport & scrollbar geometry (single source) ──
    /** 网格可视区宽/高：右缘与底缘让位给滚动条。 */
    private int gridW() { return rollW() - NbsScrollBar.THICK; }
    private int gridH() { return (view == View.LANE ? laneGridH() : rollH()) - NbsScrollBar.THICK; }
    /** 横向滚动条（时间轴）顶缘；纵向滚动条（层行/键轴）左缘。 */
    private int hbarY() { return rollY() + gridH(); }
    private int vsbX() { return rollX() + gridW(); }

    /** 滚动条映射（单一真相源，渲染与命中共用）：横 = 时间轴（tick）。
     *  返回 {总量, 可见量, 位置}。 */
    private int[] hScrollState() {
        int total = Math.max(kernel.song().songLength, kernel.song().computeLength()) + 16;
        float ppt = view == View.ROLL ? roll.pxPerTick() : laneGrid.pxPerTick();
        int visible = Math.max(1, (int) Math.ceil(gridW() / ppt));
        float st = view == View.ROLL ? roll.scrollTick() : laneGrid.scrollTick();
        return new int[]{total, visible, Math.round(st)};
    }

    /** 纵 = 层行（层列视图）/ 键轴 0–87（卷帘视图，自顶向下）。返回 {总量, 可见量, 位置}。 */
    private int[] vScrollState() {
        if (view == View.LANE) {
            int total = Math.max(1, kernel.song().layers.size());
            int visible = Math.max(1, gridH() / layerRowH());
            return new int[]{total, visible, layerScroll};
        }
        int visible = Math.max(1, (int) Math.ceil(gridH() / roll.pxPerKey()));
        return new int[]{88, visible, 88 - Math.round(roll.viewTopKey())};
    }

    private void applyHScroll(int pos) {
        if (view == View.ROLL) roll.setScrollTick(pos); else laneGrid.setScrollTick(pos);
    }

    private void applyVScroll(int pos) {
        if (view == View.LANE) layerScroll = pos;
        else roll.setViewTopKey(88 - pos);
    }

    /** 滚动条点击：滑块 = 开始拖动 + 立即定位；轨道 = 翻页朝点击侧。 */
    private boolean clickScrollBars(double mx, double my) {
        if (inRect(rollX(), hbarY(), rollW(), NbsScrollBar.THICK, mx, my)) {
            int[] s = hScrollState();
            int[] t = NbsScrollBar.thumbRect(rollX(), hbarY(), rollW(), NbsScrollBar.THICK, s[0], s[1], s[2]);
            if (inRect(t[0], t[1], t[2], t[3], mx, my)) {
                draggingScrollBar = 1;
                applyHScroll(NbsScrollBar.posFromThumbDrag(rollW(), t[2], (int) (mx - rollX()), s[0], s[1]));
            } else {
                applyHScroll(NbsScrollBar.posFromTrackClick((int) (mx - rollX()), t[0] - rollX(), t[2], s[2], s[0], s[1]));
            }
            return true;
        }
        if (inRect(vsbX(), rollY(), NbsScrollBar.THICK, gridH(), mx, my)) {
            int[] s = vScrollState();
            int[] t = NbsScrollBar.thumbRect(vsbX(), rollY(), NbsScrollBar.THICK, gridH(), s[0], s[1], s[2]);
            if (inRect(t[0], t[1], t[2], t[3], mx, my)) {
                draggingScrollBar = 2;
                applyVScroll(NbsScrollBar.posFromThumbDrag(gridH(), t[3], (int) (my - rollY()), s[0], s[1]));
            } else {
                applyVScroll(NbsScrollBar.posFromTrackClick((int) (my - rollY()), t[1] - rollY(), t[3], s[2], s[0], s[1]));
            }
            return true;
        }
        return false;
    }
    /** 层序器视图的层行高（与网格行 1:1 对齐）。 */
    static final int LANE_ROW_H = 20;

    /** 编辑试听（社区习惯：放键/覆盖/选键即发声，按听者位置）；钢琴条跟弹点亮。
     *  Edit audition (community habit: placing, overwriting and key picks sound at once);
     *  the piano strip lights the key along. */
    private void audition(NbsSong.Note n) {
        flashKey(n.key);
        CscAudioEngine.playAtListener(new NoteEvent(n.instrument, n.key, n.velocity, n.panning, n.pitch, 1f));
    }

    /** 钢琴条跟弹：点亮一键 250ms。 / play-along flash: light one key for 250ms. */
    private void flashKey(int key) {
        if (key >= 0 && key < keyFlashUntil.length) keyFlashUntil[key] = System.currentTimeMillis() + 250;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        g.fill(0, 0, width, height, C_BG);
        renderTopBar(g, mx, my);
        renderLayerPanel(g, mx, my);
        float playhead = preview.isPlaying() || preview.headTick() > 0 ? preview.head : -1f;
        if (view == View.ROLL) {
            roll.render(g, font, rollX(), rollY(), gridW(), gridH(),
                kernel.song(), currentLayer, playhead);
        } else {
            laneGrid.render(g, font, rollX(), rollY(), gridW(), gridH(),
                kernel.song(), currentLayer, layerScroll, layerRowH(), playhead);
            pianoStrip.render(g, font, rollX(), stripY(), rollW(), NbsPianoStrip.STRIP_H, toolKey,
                keyFlashUntil, System.currentTimeMillis());
        }
        // 横竖双滚动条（社区同款职责：可见位置一目了然、可拖可翻页）
        // The scrollbar pair (community role: position at a glance, draggable, pageable).
        int[] hs = hScrollState();
        NbsScrollBar.render(g, rollX(), hbarY(), rollW(), NbsScrollBar.THICK, hs[0], hs[1], hs[2],
            inRect(rollX(), hbarY(), rollW(), NbsScrollBar.THICK, mx, my));
        int[] vs = vScrollState();
        NbsScrollBar.render(g, vsbX(), rollY(), NbsScrollBar.THICK, gridH(), vs[0], vs[1], vs[2],
            inRect(vsbX(), rollY(), NbsScrollBar.THICK, gridH(), mx, my));
        renderTransportBar(g, mx, my);
        if (importOpen) renderImportDialog(g, mx, my);
        if (exportOpen) renderExportDialog(g, mx, my);
        if (showGuide) renderGuide(g);
    }

    // ══════════════ 条形按钮几何（单一真相源）/ bar button geometry (single source) ══════════════
    // 渲染与命中测试都从下面两张矩形表取几何，禁止两侧各自步进坐标（tech-debt P2-22：
    // render/click 几何双写，失步即按错按钮）。
    // Rendering and hit-testing both read these rect tables — never re-walk the layout by hand
    // on either side (tech-debt P2-22: duplicated render/click geometry drifts into misclicks).

    /** 顶栏按钮下标（= topBarRects 顺序，右起）。/ top-bar indices (topBarRects order, right to left). */
    static final int TB_CLOSE = 0, TB_SAVE = 1, TB_GUIDE = 2, TB_EXPORT = 3, TB_IMPORT = 4, TB_REDO = 5, TB_UNDO = 6, TB_VIEW = 7;
    private static final int[] TOP_BTN_W = {38, 42, 42, 48, 48, 38, 38, 50};

    /** 顶栏按钮矩形表：右起排布（右留 4px、按钮间 2px），y=4、高 18。
     *  Top-bar rects, right to left (4px right margin, 2px gaps), y=4, h=18. */
    static int[][] topBarRects(int width) {
        int[][] r = new int[TOP_BTN_W.length][4];
        int x = width - 4;
        for (int i = 0; i < r.length; i++) {
            x -= TOP_BTN_W[i] + 2;
            r[i] = new int[]{x, 4, TOP_BTN_W[i], 18};
        }
        return r;
    }

    /** 传输条下标（= transportBarRects 顺序）。/ transport-bar indices (transportBarRects order). */
    static final int TR_PLAY = 0, TR_STOP = 1, TR_LOOP = 2, TR_TEMPO = 3, TR_INST_LABEL = 4, TR_INST_PREV = 5, TR_INST_NEXT = 6;

    /** 传输条矩形表：走带三键、速度框、乐器标签框、乐器前后键。tempo 标签宽随语言，作入参。
     *  Transport-bar rects: transport trio, tempo box, instrument label + prev/next. The tempo
     *  label width is locale-dependent and comes in as a parameter. */
    static int[][] transportBarRects(int width, int height, int tempoLabelW) {
        int y = height - TRANSPORT_H + 4;
        return new int[][] {
            {4, y, 48, 18},                                      // play/pause 播放/暂停
            {56, y, 38, 18},                                     // stop 停止
            {98, y, 44, 18},                                     // loop 循环
            {148 + tempoLabelW + 4, y + 2, 40, 14},              // tempo box 速度框
            {width - 108, y, 84, 18},                            // instrument label 乐器标签框
            {width - 124, y, 16, 18},                            // instrument prev 上一乐器
            {width - 20, y, 16, 18},                             // instrument next 下一乐器
        };
    }

    private void renderTopBar(GuiGraphics g, int mx, int my) {
        g.fill(0, 0, width, TOP_H - 2, C_PANEL);
        g.fill(0, TOP_H - 2, width, TOP_H, C_BORDER);
        String title = "NBS · " + (kernel.song().songName.isEmpty() ? node.displayText : kernel.song().songName);
        g.drawString(font, title, 6, 10, C_TEXT, false);

        String[] labels = {
            I18n.get("gui.create_schematic_compute.nbs_close"),
            I18n.get("gui.create_schematic_compute.nbs_save"),
            I18n.get("gui.create_schematic_compute.nbs_guide"),
            I18n.get("gui.create_schematic_compute.nbs_export"),
            I18n.get("gui.create_schematic_compute.nbs_import"),
            I18n.get("gui.create_schematic_compute.nbs_redo"),
            I18n.get("gui.create_schematic_compute.nbs_undo"),
            I18n.get(view == View.ROLL ? "gui.create_schematic_compute.nbs_view_roll"
                                       : "gui.create_schematic_compute.nbs_view_lane"),
        };
        int[][] r = topBarRects(width);
        for (int i = 0; i < r.length; i++) {
            boolean on = (i == TB_GUIDE && showGuide) || (i == TB_EXPORT && exportOpen) || (i == TB_IMPORT && importOpen);
            button(g, r[i][0], r[i][1], r[i][2], r[i][3], labels[i], mx, my, on);
        }

        if (System.currentTimeMillis() < feedbackUntil && !feedbackText.isEmpty()) {
            g.drawString(font, feedbackText, 6 + font.width(title) + 12, 10, C_TEXT_DIM, false);
        }
    }

    private void renderTransportBar(GuiGraphics g, int mx, int my) {
        int y = height - TRANSPORT_H;
        g.fill(0, y, width, height, C_PANEL);
        g.fill(0, y, width, y + 2, C_BORDER);

        int tempoLabelW = font.width(I18n.get("gui.create_schematic_compute.nbs_tempo"));
        int[][] r = transportBarRects(width, height, tempoLabelW);
        button(g, r[TR_PLAY][0], r[TR_PLAY][1], r[TR_PLAY][2], r[TR_PLAY][3], I18n.get(preview.isPlaying()
            ? "gui.create_schematic_compute.nbs_pause" : "gui.create_schematic_compute.nbs_play"), mx, my, preview.isPlaying());
        button(g, r[TR_STOP][0], r[TR_STOP][1], r[TR_STOP][2], r[TR_STOP][3], I18n.get("gui.create_schematic_compute.nbs_stop"), mx, my, false);
        button(g, r[TR_LOOP][0], r[TR_LOOP][1], r[TR_LOOP][2], r[TR_LOOP][3], I18n.get("gui.create_schematic_compute.nbs_loop"), mx, my, kernel.song().loop);

        g.drawString(font, I18n.get("gui.create_schematic_compute.nbs_tempo"),
            r[TR_TEMPO][0] - tempoLabelW - 4, y + 9, C_TEXT_DIM, false);
        tempoBox.setX(r[TR_TEMPO][0]); tempoBox.setY(r[TR_TEMPO][1]); tempoBox.render(g, mx, my, pt0());

        float tps = Math.max(0.01f, kernel.song().tempo / 100f);
        int headT = (int) preview.head;
        int len = Math.max(kernel.song().songLength, kernel.song().computeLength());
        String pos = String.format("tick %d / %d  %d:%02d", headT, len,
            (int) (preview.head / tps) / 60, (int) (preview.head / tps) % 60);
        g.drawString(font, pos, r[TR_TEMPO][0] + 46, y + 9, C_TEXT, false);

        // 右侧乐器选择 / instrument cycle (right side)
        String instName = currentInstrument < 16
            ? I18n.get("gui.create_schematic_compute.nbs_inst_" + INSTRUMENT_KEYS[currentInstrument])
            : "#" + currentInstrument;
        int iw = font.width(instName);
        int[] li = r[TR_INST_LABEL];
        g.fill(li[0], li[1], li[0] + li[2], li[1] + li[3], C_BTN);
        g.renderOutline(li[0], li[1], li[2], li[3], C_BORDER);
        g.drawString(font, instName, li[0] + (80 - iw) / 2, y + 9, C_TEXT, false);
        button(g, r[TR_INST_PREV][0], r[TR_INST_PREV][1], r[TR_INST_PREV][2], r[TR_INST_PREV][3], "<", mx, my, false);
        button(g, r[TR_INST_NEXT][0], r[TR_INST_NEXT][1], r[TR_INST_NEXT][2], r[TR_INST_NEXT][3], ">", mx, my, false);
    }

    private float pt0() { return 0f; }

    private void renderLayerPanel(GuiGraphics g, int mx, int my) {
        int bottom = height - TRANSPORT_H;
        g.fill(0, TOP_H, PANEL_W, bottom, C_PANEL);
        g.fill(PANEL_W - 2, TOP_H, PANEL_W, bottom, C_BORDER);
        g.drawString(font, I18n.get("gui.create_schematic_compute.nbs_layers"), 6, TOP_H + 4, C_TEXT, false);

        // 层列表 / layer list（行高随视图：层序器视图与网格行 1:1 对齐）
        int listTop = TOP_H + 18;
        int listBottom = bottom - PROPS_H;
        int rh = layerRowH();
        var layers = kernel.song().layers;
        for (int i = 0; i < layers.size(); i++) {
            int y = listTop + (i - layerScroll) * rh;
            if (y < listTop - rh || y > listBottom - rh) continue;
            boolean sel = i == currentLayer;
            int color = sel ? C_SEL : (inRect(4, y, PANEL_W - 12, rh, mx, my) ? C_HOVER : C_PANEL);
            g.fill(4, y, PANEL_W - 8, y + rh - 1, color);
            String name = layers.get(i).name.isEmpty() ? I18n.get("gui.create_schematic_compute.nbs_layer") + " " + (i + 1) : layers.get(i).name;
            g.drawString(font, name, 8, y + (rh - 8) / 2, layers.get(i).locked ? C_TEXT_DIM : C_TEXT, false);
            // 行内信息：音量% + 声像(±) + 锁定标记（社区层行的信息密度）
            // Inline info: volume% + panning (±) + lock mark (the community layer row's density)
            var lyr = layers.get(i);
            int pan = lyr.panning - 100;
            String info = lyr.volume + "% " + (pan >= 0 ? "+" : "") + pan
                + (lyr.locked ? " " + I18n.get("gui.create_schematic_compute.nbs_locked_mark") : "");
            g.drawString(font, info, PANEL_W - 8 - font.width(info), y + (rh - 8) / 2, C_TEXT_DIM, false);
        }

        // 选中层属性 / selected layer properties
        int pY = bottom - PROPS_H;
        g.fill(4, pY, PANEL_W - 8, pY + 2, C_BORDER);
        g.drawString(font, I18n.get("gui.create_schematic_compute.nbs_name"), 6, pY + 8, C_TEXT_DIM, false);
        layerNameBox.setX(6); layerNameBox.setY(pY + 18);
        if (!layerNameBox.isFocused() && currentLayer < layers.size()) {
            layerNameBox.setValue(layers.get(currentLayer).name);
        }
        layerNameBox.render(g, mx, my, pt0());

        var layer = currentLayer < layers.size() ? layers.get(currentLayer) : null;
        g.drawString(font, I18n.get("gui.create_schematic_compute.nbs_volume"), 6, pY + 40, C_TEXT_DIM, false);
        slider(g, volSliderX(), pY + 40, SLIDER_W, layer == null ? 100 : layer.volume, 100, mx, my);
        g.drawString(font, I18n.get("gui.create_schematic_compute.nbs_panning"), 6, pY + 58, C_TEXT_DIM, false);
        slider(g, panSliderX(), pY + 58, SLIDER_W, layer == null ? 100 : layer.panning, 200, mx, my);

        button(g, 6, pY + 80, 50, 18, I18n.get(layer != null && layer.locked
            ? "gui.create_schematic_compute.nbs_unlock" : "gui.create_schematic_compute.nbs_lock"), mx, my,
            layer != null && layer.locked);
        button(g, 60, pY + 80, 50, 18, I18n.get("gui.create_schematic_compute.nbs_add_layer"), mx, my, false);
        button(g, 114, pY + 80, 50, 18, I18n.get("gui.create_schematic_compute.nbs_del_layer"), mx, my, false);
    }

    private int volSliderX() { return 66; }
    private int panSliderX() { return 66; }
    /** 滑杆宽（给右侧数值文本留出完整一列，修截断）。
     *  Slider width — leaves a full value column on the right (the clip fix). */
    static final int SLIDER_W = 74;

    private void slider(GuiGraphics g, int x, int y, int w, int value, int max, int mx, int my) {
        g.fill(x, y + 4, x + w, y + 8, C_BTN);
        int fill = (int) ((long) w * Math.max(0, Math.min(max, value)) / max);
        g.fill(x, y + 4, x + fill, y + 8, C_BTN_ON);
        g.renderOutline(x, y + 3, w + 1, 7, C_BORDER);
        String v = max == 200 ? (value - 100 >= 0 ? "+" : "") + (value - 100) : String.valueOf(value);
        // 数值右对齐面板缘：x+w+4 的旧位置会把 "100"/"+100" 截断在面板外
        // Value right-aligned to the panel edge: the old x+w+4 clipped "100"/"+100" off-panel
        g.drawString(font, v, PANEL_W - 8 - font.width(v), y + 2, C_TEXT_DIM, false);
    }

    private void button(GuiGraphics g, int x, int y, int w, int h, String label, int mx, int my, boolean on) {
        boolean hover = inRect(x, y, w, h, mx, my);
        g.fill(x, y, x + w, y + h, on ? C_BTN_ON : (hover ? C_HOVER : C_BTN));
        g.renderOutline(x, y, w, h, C_BORDER);
        g.drawString(font, label, x + (w - font.width(label)) / 2, y + (h - 8) / 2, C_TEXT, false);
    }

    private void renderImportDialog(GuiGraphics g, int mx, int my) {
        int w = 260, h = 170, x = (width - w) / 2, y = (height - h) / 2;
        g.fill(x, y, x + w, y + h, C_PANEL);
        g.renderOutline(x, y, w, h, C_BORDER);
        g.drawString(font, I18n.get("gui.create_schematic_compute.nbs_import_title"), x + 8, y + 6, C_TEXT, false);
        int rowY = y + 20;
        for (int i = 0; i < importFiles.size() && i < IMPORT_ROWS; i++) {
            int idx = i + importScroll;
            if (idx >= importFiles.size()) break;
            int ry = rowY + i * ROW_H;
            boolean hover = inRect(x + 8, ry, w - 16, ROW_H, mx, my);
            g.fill(x + 8, ry, x + w - 8, ry + ROW_H - 1, hover ? C_HOVER : C_BTN);
            g.drawString(font, importFiles.get(idx).getFileName().toString(), x + 12, ry + 3, C_TEXT, false);
        }
        button(g, x + w - 68, y + h - 24, 58, 18, I18n.get("gui.create_schematic_compute.nbs_close"), mx, my, false);
    }

    private void renderExportDialog(GuiGraphics g, int mx, int my) {
        int w = 220, h = 80, x = (width - w) / 2, y = (height - h) / 2;
        g.fill(x, y, x + w, y + h, C_PANEL);
        g.renderOutline(x, y, w, h, C_BORDER);
        g.drawString(font, I18n.get("gui.create_schematic_compute.nbs_export_title"), x + 8, y + 6, C_TEXT, false);
        exportNameBox.setX(x + 8); exportNameBox.setY(y + 24);
        exportNameBox.render(g, mx, my, pt0());
        button(g, x + w - 128, y + h - 24, 58, 18, I18n.get("gui.create_schematic_compute.nbs_export_ok"), mx, my, false);
        button(g, x + w - 68, y + h - 24, 58, 18, I18n.get("gui.create_schematic_compute.nbs_cancel"), mx, my, false);
    }

    private void renderGuide(GuiGraphics g) {
        int w = 300, h = 150, x = (width - w) / 2, y = (height - h) / 2;
        g.fill(x, y, x + w, y + h, C_PANEL);
        g.renderOutline(x, y, w, h, C_BORDER);
        String[] lines = {
            I18n.get("gui.create_schematic_compute.nbs_guide_title"),
            "",
            I18n.get("gui.create_schematic_compute.nbs_guide_1"),
            I18n.get("gui.create_schematic_compute.nbs_guide_2"),
            I18n.get("gui.create_schematic_compute.nbs_guide_3"),
            I18n.get("gui.create_schematic_compute.nbs_guide_4"),
            I18n.get("gui.create_schematic_compute.nbs_guide_5"),
            I18n.get("gui.create_schematic_compute.nbs_guide_6"),
            I18n.get("gui.create_schematic_compute.nbs_guide_7"),
        };
        for (int i = 0; i < lines.length; i++) {
            g.drawString(font, lines[i], x + 8, y + 8 + i * 14, i == 0 ? C_TEXT : C_TEXT_DIM, false);
        }
    }

    private static boolean inRect(int x, int y, int w, int h, double mx, double my) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    // ══════════════ 输入路由 / input routing ══════════════

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        // 对话框优先 / dialogs first
        if (showGuide) { showGuide = false; return true; }
        if (importOpen) return clickImport(mx, my, btn);
        if (exportOpen) return clickExport(mx, my, btn);

        if (btn != 0) {
            // 右键落在网格上 = 擦除；其余右键忽略。擦除同样按笔划分组撤销。
            // 播放锁（社区 playing=0 语义）：播放中网格不可编辑。
            boolean overGrid = view == View.ROLL
                ? roll.hit(rollX(), rollY(), gridW(), gridH(), mx, my) == NbsPianoRoll.Zone.GRID
                : laneGrid.hit(rollX(), rollY(), gridW(), gridH(), mx, my) == NbsLaneGrid.Zone.GRID;
            if (overGrid && (editLocked() || scrollGuarded())) return true;
            boolean consumed = view == View.ROLL
                ? roll.mouseDown(rollX(), rollY(), gridW(), gridH(), mx, my, btn, kernel, currentLayer, currentInstrument)
                : laneGrid.mouseDown(rollX(), rollY(), gridW(), gridH(), mx, my, btn, kernel,
                    currentInstrument, toolKey, layerScroll, layerRowH());
            if (consumed) kernel.beginBatch();
            return true;
        }

        // 顶栏 / top bar
        if (my < TOP_H) return clickTopBar(mx, my);
        // 传输条 / transport bar
        if (my >= height - TRANSPORT_H) return clickTransport(mx, my);
        // 层面板 / layer panel
        if (mx < PANEL_W) return clickLayerPanel(mx, my);
        // 横竖滚动条（先于网格/钢琴条）/ scrollbars first
        if (clickScrollBars(mx, my)) return true;
        if (view == View.ROLL) {
            // 卷帘（含刻度尺 seek）/ roll (ruler click = seek)
            if (roll.hit(rollX(), rollY(), gridW(), gridH(), mx, my) == NbsPianoRoll.Zone.RULER) {
                int t = (int) Math.floor(roll.tickAt(rollX(), mx));
                preview.seek(Math.max(0, t));
                return true;
            }
            if (editLocked() || scrollGuarded()) return true;
            if (roll.mouseDown(rollX(), rollY(), gridW(), gridH(), mx, my, btn, kernel, currentLayer, currentInstrument)) {
                kernel.beginBatch();
                return true;
            }
            return super.mouseClicked(mx, my, btn);
        }
        // ── 层序器视图 / lane view ──
        if (my >= stripY()) {
            // 钢琴条：八度按钮 / 点键 = 选工具键 + 试听（社区签名习惯）
            int oct = pianoStrip.octaveButtonAt(mx, my, rollX(), stripY());
            if (oct != 0) pianoStrip.shift(oct * 12);
            else {
                int k = pianoStrip.keyAt(rollW() - NbsPianoStrip.BTN_W, NbsPianoStrip.STRIP_H - 4,
                    mx - rollX() - NbsPianoStrip.BTN_W, my - stripY() - 2,
                    pianoStrip.startKey(), NbsPianoStrip.WINDOW_KEYS);
                if (k >= 0) {
                    toolKey = k;
                    audition(new NbsSong.Note(0, 0, currentInstrument, k, 100, 100, 0));
                }
            }
            return true;
        }
        if (laneGrid.hit(rollX(), rollY(), gridW(), gridH(), mx, my) == NbsLaneGrid.Zone.RULER) {
            int t = (int) Math.floor(laneGrid.tickAt(rollX(), mx));
            preview.seek(Math.max(0, t));
            return true;
        }
        if (editLocked() || scrollGuarded()) return true;
        if (laneGrid.mouseDown(rollX(), rollY(), gridW(), gridH(), mx, my, btn, kernel,
            currentInstrument, toolKey, layerScroll, layerRowH())) {
            kernel.beginBatch();
            return true;
        }
        return super.mouseClicked(mx, my, btn);
    }

    /** 播放锁（社区 {@code playing = 0} 语义）：播放中网格不可编辑，提示一次并吞掉点击。
     *  The playback edit lock (community {@code playing = 0} semantics): the grid refuses
     *  edits while playing — one feedback note, the click is swallowed. */
    private boolean editLocked() {
        if (!preview.isPlaying()) return false;
        feedback(I18n.get("gui.create_schematic_compute.nbs_edit_locked"));
        return true;
    }

    /** 滚动防误触窗口（ms）：滚轮活动后此窗口内的编辑点击视为滚动时手指蹭键。
     *  Scroll-guard window (ms): edit clicks inside it after wheel activity count as a
     *  finger brushing the button mid-scroll. */
    static final int SCROLL_GUARD_MS = 200;

    /** 滚动防误触：窗口内的网格编辑点击吞掉并提示（seek 等无害点击不受限）。
     *  Scroll mis-touch guard: edit clicks inside the window are swallowed with a notice
     *  (harmless clicks like ruler seek stay free). */
    private boolean scrollGuarded() {
        if (System.currentTimeMillis() - lastWheelAt < SCROLL_GUARD_MS) {
            feedback(I18n.get("gui.create_schematic_compute.nbs_scroll_guard"));
            return true;
        }
        return false;
    }

    private boolean clickTopBar(double mx, double my) {
        int[][] r = topBarRects(width);
        for (int i = 0; i < r.length; i++) {
            if (!inRect(r[i][0], r[i][1], r[i][2], r[i][3], mx, my)) continue;
            if (i == TB_CLOSE) { onClose(); return true; }
            if (i == TB_SAVE) { applyPendingInputs(); syncSongToServer(); return true; }
            if (i == TB_GUIDE) { showGuide = !showGuide; return true; }
            if (i == TB_EXPORT) { openExportDialog(); return true; }
            if (i == TB_IMPORT) { openImportDialog(); return true; }
            if (i == TB_VIEW) { view = (view == View.ROLL) ? View.LANE : View.ROLL; return true; }
            applyPendingInputs();
            if (i == TB_REDO) {
                if (kernel.performRedo()) feedback(I18n.get("gui.create_schematic_compute.nbs_redid"));
            } else if (kernel.performUndo()) {
                feedback(I18n.get("gui.create_schematic_compute.nbs_undid"));
            }
            return true;
        }
        return true;
    }

    private boolean clickTransport(double mx, double my) {
        int tempoLabelW = font.width(I18n.get("gui.create_schematic_compute.nbs_tempo"));
        int[][] r = transportBarRects(width, height, tempoLabelW);
        if (inRect(r[TR_PLAY][0], r[TR_PLAY][1], r[TR_PLAY][2], r[TR_PLAY][3], mx, my)) { togglePlay(); return true; }
        if (inRect(r[TR_STOP][0], r[TR_STOP][1], r[TR_STOP][2], r[TR_STOP][3], mx, my)) {
            preview.stop(); preview.seek(0); CscAudioEngine.cancelListenerFuture();
            return true;
        }
        if (inRect(r[TR_LOOP][0], r[TR_LOOP][1], r[TR_LOOP][2], r[TR_LOOP][3], mx, my)) {
            var s = kernel.song();
            kernel.setLoop(!s.loop, s.maxLoopCount, s.loopStartTick);
            return true;
        }
        if (inRect(r[TR_TEMPO][0], r[TR_TEMPO][1], r[TR_TEMPO][2], r[TR_TEMPO][3], mx, my)) {
            tempoBox.setFocused(true);
            return true;
        }
        if (inRect(r[TR_INST_NEXT][0], r[TR_INST_NEXT][1], r[TR_INST_NEXT][2], r[TR_INST_NEXT][3], mx, my)) {
            currentInstrument = (currentInstrument + 1) % 16;
            return true;
        }
        if (inRect(r[TR_INST_PREV][0], r[TR_INST_PREV][1], r[TR_INST_PREV][2], r[TR_INST_PREV][3], mx, my)) {
            currentInstrument = (currentInstrument + 15) % 16;
            return true;
        }
        // 点空白=速度框失焦：走提交路径，不丢草稿（P3 同族修复）
        // Clicking dead space blurs the tempo box through the commit path — the draft is
        // never dropped (same family as the layer-name blur fix).
        if (tempoBox.isFocused()) commitTempo();
        return true;
    }

    private void togglePlay() {
        if (preview.isPlaying()) {
            preview.stop();
            CscAudioEngine.cancelListenerFuture(); // 预播窗口内已调度未播的试听音符一并清除
        } else {
            applyPendingInputs();
            preview.playFromHead(kernel.song());
        }
    }

    private boolean clickLayerPanel(double mx, double my) {
        int bottom = height - TRANSPORT_H;
        int listTop = TOP_H + 18;
        int listBottom = bottom - PROPS_H;
        var layers = kernel.song().layers;

        // 列表行 / list rows（行高随视图，与渲染同源）
        int rh = layerRowH();
        for (int i = 0; i < layers.size(); i++) {
            int y = listTop + (i - layerScroll) * rh;
            if (y < listTop || y > listBottom - rh) continue;
            if (inRect(4, y, PANEL_W - 12, rh, mx, my)) {
                currentLayer = i;
                return true;
            }
        }

        // 属性区 / properties area
        int pY = bottom - PROPS_H;
        if (inRect(6, pY + 18, PANEL_W - 20, 14, mx, my)) {
            layerNameBox.setFocused(true);
            return true;
        }
        // 滑杆 / sliders
        if (inRect(volSliderX(), pY + 42, SLIDER_W, 8, mx, my)) {
            draggingSlider = 1;
            applySlider(mx, pY);
            return true;
        }
        if (inRect(panSliderX(), pY + 60, SLIDER_W, 8, mx, my)) {
            draggingSlider = 2;
            applySlider(mx, pY);
            return true;
        }
        if (inRect(6, pY + 80, 50, 18, mx, my)) {
            if (currentLayer < layers.size()) kernel.setLayerLocked(currentLayer, !layers.get(currentLayer).locked);
            return true;
        }
        if (inRect(60, pY + 80, 50, 18, mx, my)) {
            kernel.beginBatch();
            kernel.addLayer();
            kernel.endBatch();
            currentLayer = layers.size() - 1;
            return true;
        }
        if (inRect(114, pY + 80, 50, 18, mx, my)) {
            if (layers.size() > 1 && currentLayer < layers.size()) {
                kernel.removeLayer(currentLayer);
                if (currentLayer >= layers.size()) currentLayer = layers.size() - 1;
            }
            return true;
        }
        // 点面板空白=层名框失焦：走提交路径（P3：失焦丢字——渲染每帧用层名覆盖未聚焦框）
        // Clicking dead panel space blurs the layer-name box through the commit path (P3:
        // blur used to drop the draft — rendering refills the unfocused box from the layer).
        if (layerNameBox.isFocused()) commitLayerName();
        return true;
    }

    private void applySlider(double mx, int pY) {
        if (currentLayer >= kernel.song().layers.size()) return;
        double rel = Math.max(0, Math.min(1, (mx - volSliderX()) / (double) SLIDER_W));
        if (draggingSlider == 1) kernel.setLayerVolume(currentLayer, (int) Math.round(rel * 100));
        else if (draggingSlider == 2) kernel.setLayerPanning(currentLayer, (int) Math.round(rel * 200));
    }

    private boolean clickImport(double mx, double my, int btn) {
        int w = 260, h = 170, x = (width - w) / 2, y = (height - h) / 2;
        if (inRect(x + w - 68, y + h - 24, 58, 18, mx, my) || !inRect(x, y, w, h, mx, my)) {
            importOpen = false;
            return true;
        }
        int rowY = y + 20;
        for (int i = 0; i < importFiles.size() && i < IMPORT_ROWS; i++) {
            int idx = i + importScroll;
            if (idx >= importFiles.size()) break;
            if (inRect(x + 8, rowY + i * ROW_H, w - 16, ROW_H, mx, my)) {
                doImport(importFiles.get(idx));
                importOpen = false;
                return true;
            }
        }
        return true;
    }

    private boolean clickExport(double mx, double my, int btn) {
        int w = 220, h = 80, x = (width - w) / 2, y = (height - h) / 2;
        if (inRect(x + w - 128, y + h - 24, 58, 18, mx, my)) {
            doExport(exportNameBox.getValue().trim());
            exportOpen = false;
            return true;
        }
        if (inRect(x + w - 68, y + h - 24, 58, 18, mx, my) || !inRect(x, y, w, h, mx, my)) {
            exportOpen = false;
            return true;
        }
        exportNameBox.mouseClicked(mx, my, 0);
        return true;
    }

    // ══════════════ 导入/导出 .nbs / import & export ══════════════

    private static Path nbsDir() {
        return Minecraft.getInstance().gameDirectory.toPath()
            .resolve("create_schematic_compute").resolve("nbs");
    }

    private void openImportDialog() {
        importOpen = true;
        importScroll = 0;
        try {
            Path dir = nbsDir();
            if (Files.exists(dir)) {
                try (var s = Files.list(dir)) {
                    importFiles = s.filter(p -> p.toString().endsWith(".nbs")).sorted().toList();
                }
            } else importFiles = List.of();
        } catch (Exception e) {
            SchematicCompute.LOGGER.warn("Failed to list nbs files: {}", e.getMessage());
            importFiles = List.of();
        }
    }

    private void openExportDialog() {
        exportOpen = true;
        exportNameBox.setValue(kernel.song().songName.isEmpty() ? "song" : kernel.song().songName);
        exportNameBox.setFocused(true);
    }

    private void doImport(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length > NbsSong.MAX_BYTES) {
                feedback("§c" + I18n.get("gui.create_schematic_compute.nbs_too_big"));
                return;
            }
            NbsSong parsed = NbsSong.read(bytes);
            preview.stop();
            preview.seek(0);
            CscAudioEngine.cancelListenerFuture();
            kernel.replaceSong(parsed);     // 导入可撤销（plan：导入 .nbs + 撤销）
            syncSongToServer();
            feedback(I18n.get("gui.create_schematic_compute.nbs_imported"));
        } catch (Exception e) {
            feedback("§c" + I18n.get("gui.create_schematic_compute.nbs_import_failed"));
        }
    }

    /** 文件名消毒：平台非法字符（Windows：{@code <>:"/\\|?*} 与控制符）替换为 _，尾随点/空格去掉。
     *  曲目名可含艺名字符（如 DECO*27），直接当文件名会炸 {@code Path.resolve}。
     *  File-name sanitiser: platform-illegal characters (Windows {@code <>:"/\\|?*} and
     *  control chars) become '_', trailing dots/spaces are stripped. Song titles may carry
     *  artist punctuation (e.g. DECO*27) that would break {@code Path.resolve}. */
    static String sanitizeFileName(String name) {
        String s = name.replaceAll("[<>:\"/\\\\|?*\\u0000-\\u001F]", "_").trim();
        while (s.endsWith(".") || s.endsWith(" ")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private void doExport(String name) {
        if (name.isEmpty()) return;
        try {
            Path dir = nbsDir();
            Files.createDirectories(dir);
            String base = name.endsWith(".nbs") ? name.substring(0, name.length() - 4) : name;
            base = sanitizeFileName(base);
            if (base.isEmpty()) {
                feedback("§c" + I18n.get("gui.create_schematic_compute.nbs_export_failed"));
                return;
            }
            Path out = dir.resolve(base + ".nbs");
            int n = 1;
            while (Files.exists(out)) out = dir.resolve(base + "-" + (n++) + ".nbs");
            Files.write(out, kernel.song().write());
            feedback(I18n.get("gui.create_schematic_compute.nbs_exported"));
        } catch (Exception e) {
            // 含 InvalidPathException（文件名非法字符）——一律反馈，不崩游戏
            SchematicCompute.LOGGER.warn("NBS export failed: {}", e.toString());
            feedback("§c" + I18n.get("gui.create_schematic_compute.nbs_export_failed"));
        }
    }

    // ══════════════ 其余输入 / remaining input ══════════════

    @Override
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) {
        if (draggingSlider != 0) {
            applySlider(mx, height - TRANSPORT_H - PROPS_H);
            return true;
        }
        if (draggingScrollBar == 1) {
            int[] s = hScrollState();
            int[] t = NbsScrollBar.thumbRect(rollX(), hbarY(), rollW(), NbsScrollBar.THICK, s[0], s[1], s[2]);
            applyHScroll(NbsScrollBar.posFromThumbDrag(rollW(), t[2], (int) (mx - rollX()), s[0], s[1]));
            return true;
        }
        if (draggingScrollBar == 2) {
            int[] s = vScrollState();
            int[] t = NbsScrollBar.thumbRect(vsbX(), rollY(), NbsScrollBar.THICK, gridH(), s[0], s[1], s[2]);
            applyVScroll(NbsScrollBar.posFromThumbDrag(gridH(), t[3], (int) (my - rollY()), s[0], s[1]));
            return true;
        }
        if (preview.isPlaying()) return true; // 播放锁：笔划不得续写（静默吞，防提示刷屏）
        if (view == View.LANE) {
            return laneGrid.mouseDrag(rollX(), rollY(), gridW(), gridH(), mx, my, kernel, layerScroll, layerRowH());
        }
        return roll.mouseDrag(rollX(), rollY(), mx, my, kernel, currentLayer, currentInstrument);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int btn) {
        draggingSlider = 0;
        draggingScrollBar = 0;
        if (view == View.LANE) {
            if (laneGrid.isErasing()) {
                kernel.endBatch();
                laneGrid.mouseUp();
            }
            return super.mouseReleased(mx, my, btn);
        }
        if (roll.isPainting()) {
            kernel.endBatch();
            roll.mouseUp();
        }
        return super.mouseReleased(mx, my, btn);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        lastWheelAt = System.currentTimeMillis();   // 滚动防误触窗口起点 / arm the scroll guard
        if (mx < PANEL_W && my > TOP_H && my < height - TRANSPORT_H) {
            layerScroll = Math.max(0, layerScroll + (sy > 0 ? -1 : 1));
            return true;
        }
        if (importOpen) {
            // 导入列表滚轮：此前弹窗分支整体吞滚轮，importScroll 永不推进——目录超过一屏后
            // 下方文件点不到。Wheel now drives the import list: the dialog branch used to
            // swallow the wheel, leaving importScroll dead and rows past the first screen
            // unclickable.
            importScroll = clampImportScroll(importScroll + (sy > 0 ? -1 : 1), importFiles.size());
            return true;
        }
        if (showGuide || exportOpen) return true;
        if (view == View.LANE) {
            if (mx >= rollX() && my >= stripY()) { pianoStrip.shift(sy > 0 ? -12 : 12); return true; }
            // Alt + 悬停音符 + 滚轮 = 调声调（Shift = 八度 ±12，否则半音 ±1）。
            // **防误触**：纯滚轮一律只滚动、永不动谱——社区用 changepitch 模式门控同一风险，
            // 我们用 Alt 门控（无模式状态可忘）。
            // Alt + hover a note + wheel = adjust its key (Shift = octave ±12, plain = ±1).
            // Anti-mis-touch: the plain wheel only ever scrolls, never mutates — the community
            // gates the same risk behind its changepitch mode; we gate on Alt (no state to forget).
            if (hasAltDown() && laneGrid.hit(rollX(), rollY(), gridW(), gridH(), mx, my) == NbsLaneGrid.Zone.GRID) {
                int tick = (int) Math.floor(laneGrid.tickAt(rollX(), mx));
                int layer = laneGrid.layerAt(rollY(), my, layerScroll, layerRowH(), kernel.song().layers.size());
                NbsSong.Note n = (tick >= 0 && layer >= 0) ? kernel.song().getNote(tick, layer) : null;
                if (n != null) {
                    if (editLocked()) return true;
                    int nk = Math.max(0, Math.min(87, n.key + (sy > 0 ? 1 : -1) * (hasShiftDown() ? 12 : 1)));
                    kernel.changeNoteKey(tick, layer, nk);
                    flashKey(nk);
                    feedback(NbsPianoRoll.keyName(nk));
                    return true;
                }
            }
            if (hasShiftDown()) {
                // Shift+滚轮 = 翻层（与层面板同款）/ Shift+wheel flips layers (as in the panel)
                layerScroll = Math.max(0, layerScroll + (sy > 0 ? -1 : 1));
                return true;
            }
            if (laneGrid.hit(rollX(), rollY(), gridW(), gridH(), mx, my) != NbsLaneGrid.Zone.OUTSIDE) {
                return laneGrid.mouseScroll(hasControlDown(), sy);
            }
            return super.mouseScrolled(mx, my, sx, sy);
        }
        if (roll.hit(rollX(), rollY(), gridW(), gridH(), mx, my) != NbsPianoRoll.Zone.OUTSIDE) {
            return roll.mouseScroll(hasControlDown(), hasShiftDown(), sy);
        }
        return super.mouseScrolled(mx, my, sx, sy);
    }

    @Override
    public boolean keyPressed(int key, int sc, int mod) {
        if (key == 256) {                       // ESC
            if (showGuide) { showGuide = false; return true; }
            if (importOpen) { importOpen = false; return true; }
            if (exportOpen) { exportOpen = false; return true; }
            if (tempoBox != null && tempoBox.isFocused()) { commitTempo(); return true; }
            if (layerNameBox != null && layerNameBox.isFocused()) { commitLayerName(); return true; }
            if (exportNameBox != null && exportNameBox.isFocused()) { exportNameBox.setFocused(false); return true; }
            onClose();
            return true;
        }
        if (key == 257 || key == 335) {         // Enter（提交输入框）
            if (tempoBox != null && tempoBox.isFocused()) { commitTempo(); return true; }
            if (layerNameBox != null && layerNameBox.isFocused()) { commitLayerName(); return true; }
            if (exportOpen && exportNameBox != null && exportNameBox.isFocused()) {
                doExport(exportNameBox.getValue().trim());
                exportOpen = false;
                return true;
            }
        }
        // 输入框聚焦时把按键交给它 / focused edit boxes eat keys
        if ((tempoBox != null && tempoBox.isFocused()) || (layerNameBox != null && layerNameBox.isFocused())
            || (exportOpen && exportNameBox != null && exportNameBox.isFocused())) {
            if (exportOpen && exportNameBox != null && exportNameBox.isFocused()) return exportNameBox.keyPressed(key, sc, mod);
            if (tempoBox != null && tempoBox.isFocused()) return tempoBox.keyPressed(key, sc, mod);
            return layerNameBox.keyPressed(key, sc, mod);
        }
        if (hasControlDown() && key == 90) {    // Ctrl+Z
            applyPendingInputs();
            if (hasShiftDown()) { if (kernel.performRedo()) feedback(I18n.get("gui.create_schematic_compute.nbs_redid")); }
            else if (kernel.performUndo()) feedback(I18n.get("gui.create_schematic_compute.nbs_undid"));
            return true;
        }
        if (hasControlDown() && key == 89) {    // Ctrl+Y
            if (kernel.performRedo()) feedback(I18n.get("gui.create_schematic_compute.nbs_redid"));
            return true;
        }
        if (key == 32) {                        // Space：播放/暂停
            togglePlay();
            return true;
        }
        return super.keyPressed(key, sc, mod);
    }

    @Override
    public boolean charTyped(char ch, int mod) {
        if (tempoBox != null && tempoBox.isFocused()) return tempoBox.charTyped(ch, mod);
        if (layerNameBox != null && layerNameBox.isFocused()) return layerNameBox.charTyped(ch, mod);
        if (exportOpen && exportNameBox != null && exportNameBox.isFocused()) return exportNameBox.charTyped(ch, mod);
        // 层序器视图：打字 = 琴键（社区映射，选键 + 试听）；弹窗打开时不响应。
        // Lane view: typing plays the piano (community map — select + audition); silent
        // while a dialog is open.
        if (view == View.LANE && !showGuide && !importOpen && !exportOpen) {
            int k = NbsPianoStrip.typedKey(ch);
            if (k >= 0) {
                toolKey = k;
                audition(new NbsSong.Note(0, 0, currentInstrument, k, 100, 100, 0));
                return true;
            }
        }
        return super.charTyped(ch, mod);
    }
}
