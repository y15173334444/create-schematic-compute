package io.github.y15173334444.create_schematic_compute.client;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.client.editor.EditorKeys;
import io.github.y15173334444.create_schematic_compute.blocks.GraphBlockEntity;
import io.github.y15173334444.create_schematic_compute.client.editor.GraphEditor;
import io.github.y15173334444.create_schematic_compute.client.editor.NodeRenderer;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    /** 独立 logger：不引用 (root) 包的 @Mod 类——其静态初始化依赖 MC 注册表，
     *  而 LoggerFactory 不依赖注册表，headless/JUnit 下也能安全完成类初始化。日志名为本类 FQCN。
     *  Same-name standalone logger: never references the @Mod class — its static init needs
     *  the Minecraft registries, while LoggerFactory does not, so class init stays safe in
     *  plain JUnit too. The log name is this class's FQCN. */
    private static final Logger LOGGER = LoggerFactory.getLogger(NbsEditorScreen.class);

    // ── 布局常量 / layout constants ──
    private static final int TOP_H = 28;
    private static final int TRANSPORT_H = 26;
    private static final int PANEL_W = 176;
    static final int ROW_H = 13;

    // ── 配色（渲染时读用户颜色配置，换主题即时生效）/ colours from the user colour config ──
    private static int C_BG() { return EditorTheme.get().bg(); }
    private static int C_PANEL() { return EditorTheme.get().panelBg(); }
    private static int C_BORDER() { return EditorTheme.get().panelBorder(); }
    private static int C_BTN() { return EditorTheme.get().insetBg(); }
    private static int C_BTN_ON() { return EditorTheme.get().accent(); }
    private static int C_HOVER() { return EditorTheme.get().hover(); }
    private static int C_SEL() { return EditorTheme.get().accent(); }
    private static int C_DEL() { return EditorTheme.get().error(); }
    private static final int C_TEXT = NodeRenderer.CT;
    private static final int C_TEXT_DIM = NodeRenderer.CD;

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
    /** 来源图的作用域（-1=主图，>0=封装节点 ID）——presence 保活的 ownerNodeId，占用锁
     *  的同作用域判定靠它。 / The source graph's scope (-1=main, >0=encap node ID) — the
     *  presence keep-alive's ownerNodeId; the same-scope soft-lock lookup rides on it. */
    private final int ownerNodeId;
    /** presence 保活节流（120ms，与 GraphEditor 同间隔）。 / keep-alive throttle (120 ms, same interval as GraphEditor). */
    private long lastPresenceSendMs = 0;
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
    /** 行内改名（点名称 → 通用输入框，-1 = 无）。 / inline name editing (name → the input box). */
    private int nameEditLayer = -1;
    /** 层设置小面板（带滚动条：声道/声音设置类）。 / the layer settings panel (scrollable). */
    private boolean layerSettingsOpen = false;
    private int settingsLayer = 0;
    private int settingsScroll = 0;
    /** 层总览面板（点行选层、面板留着便于连点；Esc/点外/× 关）。
     *  The layer overview panel (a row click picks the layer and keeps the panel open for
     *  browsing; Esc / an outside click / × closes). */
    private boolean layerOverviewOpen = false;
    private int overviewScroll = 0;
    /** 行图标钮悬停提示（渲染尾部绘制）。 / pending tooltip for row icon buttons. */
    private String hoverTip = "";
    private int draggingSlider = 0;        // 0=无 1=音量 2=声像
    private int draggingScrollBar = 0;     // 0=无 1=横向（时间轴） 2=纵向（层行/键轴）
    private long lastWheelAt = 0;          // 滚轮活动时间戳（滚动防误触窗口起点）

    // ── 选择与剪贴板（批次三 B1）/ selection & clipboard (batch 3 B1) ──
    /** 当前选区（粘贴后 = 落格，随后可整体搬/删）。 / current selection (after paste = the placed cells). */
    private NbsSelection selection = new NbsSelection();
    /** 内部剪贴板（系统剪贴板是外来文本时回落）。 / internal clipboard (fallback for foreign system clips). */
    private NbsClipboard clipboard = null;
    /** 选择手势：无 / 落点待判 / 单格加减选 / 框选 / 搬移待判 / 搬移中。
     *  Select gesture: none / deferred place / cell toggle / marquee / deferred move / moving. */
    private static final int GESTURE_NONE = 0, GESTURE_PLACE = 1, GESTURE_TOGGLE = 2, GESTURE_MARQUEE = 3,
        GESTURE_MOVE = 4, GESTURE_MOVING = 5;
    private int gesture = GESTURE_NONE;
    private boolean marqueeAdd;            // Ctrl = 加选 / additive marquee
    private double pressX, pressY, marqX, marqY;
    /** 搬移手势（B2）：按下音符的 (tick, layer, key) 与是否拖整段选区。
     *  Move gesture (B2): the pressed note's (tick, layer, key) and whether the whole
     *  selection travels. */
    private int moveTick, moveLayer, moveKey;
    private boolean moveSelDrag;

    // ── Set 弹窗（批次三 B3）/ the Set dialog (batch 3 B3) ──
    private boolean setOpen = false;
    private int setField = NbsEditorKernel.FIELD_VELOCITY;
    private boolean setAbsolute = true;
    private double hoverX = -1, hoverY = -1;
    /** 单击 vs 拖动的像素阈值（层列「单击落点、拖动框选」的判定）。
     *  Click vs drag threshold (the lane view's click-places / drag-selects split). */
    static final int DRAG_THRESHOLD = 4;
    private boolean importOpen = false, exportOpen = false, showGuide = false, fileMenuOpen = false;
    /** 顶栏曲名行内改名进行中（Enter 提交；失焦即提交，Esc 取消——层名框是「点别处取消」，
     *  曲名框口径不同）。 The top-bar song title is being renamed inline (Enter commits,
     *  blur commits, Esc cancels — unlike the layer box, which cancels on outside click). */
    private boolean songNameEdit = false;
    private List<Path> importFiles = List.of();
    /** 导入弹窗可见行数（渲染 / 命中 / 滚动钳位共用）。/ import dialog visible rows (render, hit-test and scroll clamp share it). */
    static final int IMPORT_ROWS = 10;
    /** 导入弹窗几何（渲染与命中共用——P2-22 口径）：列表 10 行 + 底栏互不侵占。
     *  Import dialog geometry shared by render and hit-testing (P2-22): the ten-row list
     *  and the bottom bar never overlap. */
    static final int IMPORT_W = 260, IMPORT_H = 176;
    private int importScroll = 0;
    private String feedbackText = "";
    private long feedbackUntil = 0;

    // ── 输入框 / edit boxes ──
    private EditBox tempoBox, layerNameBox, exportNameBox, setValueBox, songNameBox;

    public NbsEditorScreen(BlockPos pos, GraphNode node, Screen returnScreen, int ownerNodeId) {
        super(Component.translatable("container." + SchematicCompute.MOD_ID + ".nbs_editor"));
        this.blockPos = pos;
        this.node = node;
        this.returnScreen = returnScreen;
        this.ownerNodeId = ownerNodeId;
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
        setValueBox = new EditBox(font, 0, 0, 80, 14, Component.literal(""));
        setValueBox.setMaxLength(8);
        songNameBox = new EditBox(font, 0, 0, 140, 14, Component.literal(""));
        songNameBox.setMaxLength(48);
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
    // 出口收口（P1-10，与 PixelEditorScreen 同款）：NBS 与来源屏同属一个编辑会话
    //（transferToNbsEditor 跳过离开），temp-id 挂起/ACK 重映射必须经来源屏编辑器的
    // 漏斗实例 —— 否则"新增 MUSIC 节点后一个 RTT 内打开 NBS 保存"的 SET_SONG 携带
    // 未 ACK 的临时 id，服务端 findNode 落空静默丢弃，整首曲目丢失。便携终端包装
    // 屏（HostWrapper）本身不是 GraphEditor.Host，向内解一层拿被包装的来源屏。
    // Outlet convergence (P1-10, same shape as PixelEditorScreen): the NBS editor and
    // its origin share one editing session (transferToNbsEditor skips the leave), so the
    // temp-id stash and ACK remap must go through the originating editor's funnel —
    // otherwise a SET_SONG saved within one RTT after adding the MUSIC node still carries
    // the un-ACKed temp id, the server's findNode misses and silently drops it, losing
    // the whole song. The portable-terminal wrapper (HostWrapper) is not itself a
    // GraphEditor.Host — unwrap one level to the wrapped origin screen.
    @Override public GraphEditor getEditor() {
        if (returnScreen instanceof GraphEditor.Host h) return h.getEditor();
        if (returnScreen instanceof PortableTerminalScreen.HostWrapper w
            && w.getInnerScreen() instanceof GraphEditor.Host h) return h.getEditor();
        return null;
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
        // 子编辑器 presence 保活：GraphEditor 的每帧保活不跑（屏幕已转移），占用软锁由本包
        // 维持——其他玩家重开图也能立即看到占用框（与封装同屏路径同口径）。光标停在节点上。
        // Sub-editor presence keep-alive: GraphEditor's per-frame keep-alive is gone (the
        // screen transferred), so the occupancy soft lock rides on this packet — other players
        // reopening the graph see the lock frame immediately (same contract as the same-screen
        // encapsulation path). The cursor parks on the node.
        long now = System.currentTimeMillis();
        var mcPlayer = minecraft.player;
        if (now - lastPresenceSendMs >= 120 && mcPlayer != null) {
            lastPresenceSendMs = now;
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                io.github.y15173334444.create_schematic_compute.network.GraphPresencePacket.subEditor(
                    blockPos, mcPlayer.getUUID(), mcPlayer.getGameProfile().getName(),
                    ownerNodeId, node.id,
                    node.x + NodeRenderer.nw(node) * 0.5f, node.y + NodeRenderer.HH * 0.5f));
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
        if (songNameEdit && songNameBox != null && songNameBox.isFocused()) commitSongName();
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

    /** 像素滚动 → 行滚动（滚动条像素模型到行列表的桥，四舍五入后钳位）。
     *  Pixel scroll → row scroll (the scrollbar's pixel model bridges to the row list). */
    static int importScrollFromPx(int px, int fileCount) {
        return clampImportScroll(Math.round(px / (float) ROW_H), fileCount);
    }

    private void commitLayerName() {
        int layer = nameEditLayer >= 0 ? nameEditLayer : currentLayer;
        if (layer >= 0 && layer < kernel.song().layers.size()) {
            kernel.setLayerName(layer, layerNameBox.getValue());
        }
        nameEditLayer = -1;
        layerNameBox.setFocused(false);
    }

    /** 取消行内改名（Esc / 点别处）：草稿丢弃、绝不写回（实测反馈：点别处后输入框
     *  不取消，滞留吃键）。 / Abandon the inline rename (Esc / an outside click): the draft
     *  is discarded and never written back (field feedback: the box used to linger and eat
     *  keystrokes). */
    private void cancelNameEdit() {
        nameEditLayer = -1;
        if (layerNameBox != null) layerNameBox.setFocused(false);
    }

    /** 顶栏曲名命中矩形（点击 = 行内改名；编辑中热区 = 输入框自身，见 clickSongTitle）。
     *  The top-bar song-title hit rect (click = inline rename; while editing the hot zone
     *  is the box itself, see clickSongTitle). */
    private int[] songTitleRect() {
        String title = "NBS · " + (kernel.song().songName.isEmpty() ? node.displayText : kernel.song().songName);
        return new int[]{6, 8, font.width(title), 12};
    }

    /** 曲名点击 = 行内改名（层名同款）：框值置当前曲名并聚焦；焦点独占——速度框正聚焦
     *  时先走其提交路径（本屏失焦即提交口径），否则 charTyped 会被路由更前的速度框截走。 */
    private void startSongNameEdit() {
        if (nameEditLayer >= 0) commitLayerName();
        if (tempoBox != null && tempoBox.isFocused()) commitTempo();
        songNameEdit = true;
        songNameBox.setValue(kernel.song().songName);
        songNameBox.setFocused(true);
    }

    /** 提交曲名：只标脏（同值内核不记撤销、不上传）——上传走既有契约（保存/关闭/导入/新建）。 */
    private void commitSongName() {
        kernel.setSongName(songNameBox.getValue());
        songNameEdit = false;
        songNameBox.setFocused(false);
    }

    /** 取消曲名改名（Esc / 点别处）：草稿丢弃、绝不写回（层名同款口径）。 */
    private void cancelSongNameEdit() {
        songNameEdit = false;
        if (songNameBox != null) songNameBox.setFocused(false);
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
        g.fill(0, 0, width, height, C_BG());
        renderTopBar(g, mx, my);
        renderLayerPanel(g, mx, my);
        float playhead = preview.isPlaying() || preview.headTick() > 0 ? preview.head : -1f;
        if (view == View.ROLL) {
            roll.render(g, font, rollX(), rollY(), gridW(), gridH(),
                kernel.song(), currentLayer, playhead, selection);
        } else {
            laneGrid.render(g, font, rollX(), rollY(), gridW(), gridH(),
                kernel.song(), currentLayer, layerScroll, layerRowH(), playhead, selection);
            pianoStrip.render(g, font, rollX(), stripY(), rollW(), NbsPianoStrip.STRIP_H, toolKey,
                !preview.isPlaying(), keyFlashUntil, System.currentTimeMillis());
        }
        // 框选橡皮筋（拖动中）/ marquee rubber band while dragging
        if (gesture == GESTURE_MARQUEE) {
            int x0 = (int) Math.min(pressX, marqX), x1 = (int) Math.max(pressX, marqX);
            int y0 = (int) Math.min(pressY, marqY), y1 = (int) Math.max(pressY, marqY);
            g.fill(x0, y0, x1, y1, 0x30FFFFFF);
            g.renderOutline(x0, y0, Math.max(1, x1 - x0), Math.max(1, y1 - y0), NbsPianoRoll.C_SELECTED());
        }
        // 搬移预览（拖动中）：目标格描边 / move preview while dragging: target-cell outlines
        if (gesture == GESTURE_MOVING) drawMovePreview(g);
        // 横竖双滚动条（社区同款职责：可见位置一目了然、可拖可翻页）
        // The scrollbar pair (community role: position at a glance, draggable, pageable).
        int[] hs = hScrollState();
        NbsScrollBar.render(g, rollX(), hbarY(), rollW(), NbsScrollBar.THICK, hs[0], hs[1], hs[2],
            inRect(rollX(), hbarY(), rollW(), NbsScrollBar.THICK, mx, my));
        int[] vs = vScrollState();
        NbsScrollBar.render(g, vsbX(), rollY(), NbsScrollBar.THICK, gridH(), vs[0], vs[1], vs[2],
            inRect(vsbX(), rollY(), NbsScrollBar.THICK, gridH(), mx, my));
        renderTransportBar(g, mx, my);
        if (fileMenuOpen) renderFileMenu(g, mx, my);
        if (importOpen) renderImportDialog(g, mx, my);
        if (exportOpen) renderExportDialog(g, mx, my);
        if (setOpen) renderSetDialog(g, mx, my);
        if (layerSettingsOpen) renderLayerSettings(g, mx, my);
        if (layerOverviewOpen) renderLayerOverview(g, mx, my);
        if (showGuide) renderGuide(g);
        renderTopBarTip(g, mx, my);       // 图标钮提示画最上层 / tooltips on top of everything
        if (!hoverTip.isEmpty()) {
            drawTip(g, hoverTip, mx, my);
            hoverTip = "";
        }
    }

    /** 图标钮悬停提示（图标无文字，提示补可发现性）。 / hover tips for the icon buttons. */
    private void renderTopBarTip(GuiGraphics g, int mx, int my) {
        if (my >= TOP_H) return;
        if (!songNameEdit) {
            int[] t = songTitleRect();
            if (inRect(t[0], t[1], t[2], t[3], mx, my)) {
                drawTip(g, I18n.get("gui.create_schematic_compute.nbs_rename_song"), mx, my);
                return;
            }
        }
        int[][] r = topBarRects(width);
        for (int i = 0; i < r.length; i++) {
            if (!inRect(r[i][0], r[i][1], r[i][2], r[i][3], mx, my)) continue;
            String tip = switch (i) {
                case TB_CLOSE -> I18n.get("gui.create_schematic_compute.nbs_close");
                case TB_GUIDE -> I18n.get("gui.create_schematic_compute.nbs_guide");
                case TB_FILE -> I18n.get("gui.create_schematic_compute.nbs_file");
                case TB_REDO -> I18n.get("gui.create_schematic_compute.nbs_redo");
                case TB_UNDO -> I18n.get("gui.create_schematic_compute.nbs_undo");
                default -> "";
            };
            if (!tip.isEmpty()) drawTip(g, tip, mx, my);
            return;
        }
    }

    private void drawTip(GuiGraphics g, String text, int mx, int my) {
        int w = font.width(text) + 8, h = 14;
        int x = Math.max(1, Math.min(mx + 8, width - w - 2));
        int y = my + 10;
        if (y + h > height - TRANSPORT_H) y = my - 16;
        g.fill(x, y, x + w, y + h, 0xF0101010);
        g.renderOutline(x, y, w, h, C_BORDER());
        g.drawString(font, text, x + 4, y + 3, C_TEXT, false);
    }

    // ══════════════ 条形按钮几何（单一真相源）/ bar button geometry (single source) ══════════════
    // 渲染与命中测试都从下面两张矩形表取几何，禁止两侧各自步进坐标（tech-debt P2-22：
    // render/click 几何双写，失步即按错按钮）。
    // Rendering and hit-testing both read these rect tables — never re-walk the layout by hand
    // on either side (tech-debt P2-22: duplicated render/click geometry drifts into misclicks).

    /** 顶栏按钮下标（= topBarRects 顺序，右起）。/ top-bar indices (topBarRects order, right to left). */
    static final int TB_CLOSE = 0, TB_SAVE = 1, TB_GUIDE = 2, TB_FILE = 3, TB_REDO = 4, TB_UNDO = 5, TB_VIEW = 6;
    /** 顶栏按钮宽（图标钮 20、文字钮 36——紧凑排列；实测反馈：文字钮太挤）。
     *  Top-bar widths (icon buttons 20, text buttons 36 — compact; field feedback: the
     *  text buttons were too crowded). */
    private static final int[] TOP_BTN_W = {20, 36, 20, 20, 20, 20, 36};
    /** 图标钮字形：× ? ▤ ↷ ↶（保存/视图保持文字——主操作与模式指示要可读）。
     *  ▤ = 文件钮：导入/导出两键并成一枚，点开文件菜单（新建/导入/导出）。
     *  Icon glyphs (save / view stay textual — the primary action and the mode indicator
     *  need to read). ▤ = the file button: import & export merged into one icon opening
     *  the file menu (new / import / export). */
    private static final String IC_CLOSE = "×", IC_GUIDE = "?", IC_FILE = "▤", IC_REDO = "↷", IC_UNDO = "↶";

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

    // ══════════════ 文件菜单（新建/导入/导出）/ file menu (new / import / export) ══════════════
    // 导入/导出并成一枚文件图标后的下拉菜单；文件命名仍在导出弹窗里。
    // The dropdown behind the merged file icon; file naming stays in the export dialog.

    /** 文件菜单行下标（= fileMenuRects 顺序）。/ file-menu row indices (fileMenuRects order). */
    static final int FM_NEW = 0, FM_IMPORT = 1, FM_EXPORT = 2;
    static final int FM_ROWS = 3, FM_ROW_H = 16, FM_W = 96;

    /** 文件菜单行矩形表（渲染与命中共用）：锚在文件钮正下方、右缘对齐按钮。
     *  File-menu row rects (render & hit share them): anchored under the file button,
     *  right edges aligned. */
    static int[][] fileMenuRects(int width) {
        int[] anchor = topBarRects(width)[TB_FILE];
        int[][] r = new int[FM_ROWS][4];
        for (int i = 0; i < FM_ROWS; i++) {
            r[i] = new int[]{anchor[0] + anchor[2] - FM_W, anchor[1] + anchor[3] + 2 + i * FM_ROW_H, FM_W, FM_ROW_H};
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
        g.fill(0, 0, width, TOP_H - 2, C_PANEL());
        g.fill(0, TOP_H - 2, width, TOP_H, C_BORDER());
        String title = "NBS · " + (kernel.song().songName.isEmpty() ? node.displayText : kernel.song().songName);
        if (songNameEdit) {
            // 曲名行内改名：框顶替标题（框宽 140，与导出文件名框同宽级）
            songNameBox.setX(6); songNameBox.setY(8);
            songNameBox.render(g, mx, my, pt0());
        } else {
            g.drawString(font, title, 6, 10, C_TEXT, false);
        }

        String[] labels = {
            IC_CLOSE,
            I18n.get("gui.create_schematic_compute.nbs_save"),
            IC_GUIDE,
            IC_FILE,
            IC_REDO,
            IC_UNDO,
            I18n.get(view == View.ROLL ? "gui.create_schematic_compute.nbs_view_roll"
                                       : "gui.create_schematic_compute.nbs_view_lane"),
        };
        int[][] r = topBarRects(width);
        for (int i = 0; i < r.length; i++) {
            boolean on = (i == TB_GUIDE && showGuide) || (i == TB_FILE && fileMenuOpen);
            button(g, r[i][0], r[i][1], r[i][2], r[i][3], labels[i], mx, my, on);
        }

        if (System.currentTimeMillis() < feedbackUntil && !feedbackText.isEmpty()) {
            int fx = songNameEdit ? 6 + songNameBox.getWidth() + 8 : 6 + font.width(title) + 12;
            g.drawString(font, feedbackText, fx, 10, C_TEXT_DIM, false);
        }
    }

    private void renderTransportBar(GuiGraphics g, int mx, int my) {
        int y = height - TRANSPORT_H;
        g.fill(0, y, width, height, C_PANEL());
        g.fill(0, y, width, y + 2, C_BORDER());

        int tempoLabelW = font.width(I18n.get("gui.create_schematic_compute.nbs_tempo"));
        int[][] r = transportBarRects(width, height, tempoLabelW);
        button(g, r[TR_PLAY][0], r[TR_PLAY][1], r[TR_PLAY][2], r[TR_PLAY][3], I18n.get(preview.isPlaying()
            ? "gui.create_schematic_compute.nbs_pause" : "gui.create_schematic_compute.nbs_play"), mx, my, preview.isPlaying());
        button(g, r[TR_STOP][0], r[TR_STOP][1], r[TR_STOP][2], r[TR_STOP][3], I18n.get("gui.create_schematic_compute.nbs_stop"), mx, my, false);
        button(g, r[TR_LOOP][0], r[TR_LOOP][1], r[TR_LOOP][2], r[TR_LOOP][3], I18n.get("gui.create_schematic_compute.nbs_loop"), mx, my, kernel.song().loop);

        g.drawString(font, I18n.get("gui.create_schematic_compute.nbs_tempo"),
            r[TR_TEMPO][0] - tempoLabelW - 4, y + 9, C_TEXT_DIM, false);
        // 速度框镜像（层名框同口径）：未聚焦时每帧跟随曲目值——导入/撤销/重做换速后
        // 旧显示值不得滞留（实测反馈：导入后显示第一首的速度，再点框失焦提交即把旧速度
        // 写回新曲、上传错配）。
        // Tempo-box mirror (same policy as the layer-name box): the unfocused box follows
        // the song every frame — after import / undo / redo a stale display can never
        // linger and get committed back over the new song (field feedback).
        if (!tempoBox.isFocused()) tempoBox.setValue(tpsDisplay(kernel.song().tempo));
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
        g.fill(li[0], li[1], li[0] + li[2], li[1] + li[3], C_BTN());
        g.renderOutline(li[0], li[1], li[2], li[3], C_BORDER());
        g.drawString(font, instName, li[0] + (80 - iw) / 2, y + 9, C_TEXT, false);
        button(g, r[TR_INST_PREV][0], r[TR_INST_PREV][1], r[TR_INST_PREV][2], r[TR_INST_PREV][3], "<", mx, my, false);
        button(g, r[TR_INST_NEXT][0], r[TR_INST_NEXT][1], r[TR_INST_NEXT][2], r[TR_INST_NEXT][3], ">", mx, my, false);
    }

    private float pt0() { return 0f; }

    /** 层行小按钮下标（= layerRowButtons 顺序）：静音 / 细 / 锁 / ↑ / ↓ / 删。
     *  Layer-row button indices: mute / detail / lock / up / down / delete. */
    static final int LR_MUTE = 0, LR_DETAIL = 1, LR_LOCK = 2, LR_UP = 3, LR_DOWN = 4, LR_DELETE = 5;
    /** 层总览图标（仅层头用，不入层行按钮序）。 / the overview icon (header-only, not a row button). */
    static final int LR_OVERVIEW = 6;
    /** 扬声器+声波图标（音频钮的开启态字形，不入层行按钮序；静音态是 LR_MUTE 裸扬声器）。
     *  The speaker-with-waves glyph (the audio button's on state, not a row button; the
     *  muted state is the bare LR_MUTE speaker). */
    static final int LR_SOUND = 7;

    /** 卷帘层头槽位（= rollHeaderRects 顺序）：◀ / 名称 / ▶ / 总览 / 细 / 加层 / 删。
     *  Roll header slots: prev / name / next / overview / detail / add / delete. */
    static final int HB_PREV = 0, HB_NAME = 1, HB_NEXT = 2, HB_OVERVIEW = 3, HB_DETAIL = 4, HB_ADD = 5, HB_DELETE = 6;

    /** 卷帘层头矩形表（渲染与命中共用——P2-22 单一几何源口径；层头共 176px，
     *  名称槽让位给总览/删两钮后 70px，与层列行名列同宽级）。
     *  Roll-header rects shared by render and hit-testing (the P2-22 policy). */
    static int[][] rollHeaderRects() {
        int y = TOP_H + 2, h = 14;
        return new int[][]{
            {4, y, 14, h}, {22, y, 70, h}, {94, y, 14, h}, {110, y, 14, h},
            {126, y, 14, h}, {142, y, 14, h}, {158, y, 14, h},
        };
    }

    /** 层行按钮矩形表（行内相对坐标，渲染与命中共用——P2-22 单一几何源口径）：
     *  右起排布、行内垂直居中。 / Layer-row button rects (row-relative), shared by render
     *  and hit-testing (the P2-22 single-geometry-source policy): right-aligned and
     *  vertically centred in the row. */
    static int[][] layerRowButtons(int rowW, int rowH) {
        int bw = 12, gap = 1, bh = Math.min(14, rowH - 2);
        int x = rowW - (bw + gap) * 6;
        int y = Math.max(0, (rowH - bh) / 2);
        int[][] r = new int[6][4];
        for (int i = 0; i < 6; i++) r[i] = new int[]{x + i * (bw + gap), y, bw, bh};
        return r;
    }


    /** 层行图标（像素点阵自绘，像素编辑器工具列同风格；icon id = LR_* 下标）。
     *  Layer-row icons (hand-drawn pixel glyphs in the pixel tool-rail style; icon id = LR_*). */
    private static void drawLayerIcon(GuiGraphics g, int x, int y, int icon) {
        int P = NodeRenderer.CT, A = NodeRenderer.CD;
        switch (icon) {
            case LR_MUTE -> {                     // 静音：裸扬声器（不带声波）/ muted: bare speaker
                g.fill(x + 1, y + 3, x + 3, y + 7, A);
                g.fill(x + 3, y + 2, x + 5, y + 8, A);
                g.fill(x + 5, y + 1, x + 7, y + 9, A);
            }
            case LR_SOUND -> {                    // 有声：扬声器 + 右侧双声波弧 / audible: speaker + twin wave arcs
                g.fill(x + 1, y + 3, x + 3, y + 7, A);
                g.fill(x + 3, y + 2, x + 5, y + 8, A);
                g.fill(x + 5, y + 1, x + 7, y + 9, A);
                // 内弧 rows 3–6、外弧 rows 1–8，均朝右凸（远离扬声器）；弧点/弧腹错列不互撞
                g.fill(x + 7, y + 3, x + 8, y + 4, P);
                g.fill(x + 8, y + 4, x + 9, y + 6, P);
                g.fill(x + 7, y + 6, x + 8, y + 7, P);
                g.fill(x + 8, y + 1, x + 9, y + 2, P);
                g.fill(x + 9, y + 2, x + 10, y + 8, P);
                g.fill(x + 8, y + 8, x + 9, y + 9, P);
            }
            case LR_DETAIL -> {                   // 推子：三轨带钮 / sliders
                g.fill(x + 1, y + 2, x + 9, y + 3, A);
                g.fill(x + 1, y + 5, x + 9, y + 6, A);
                g.fill(x + 1, y + 8, x + 9, y + 9, A);
                g.fill(x + 6, y + 1, x + 8, y + 4, P);
                g.fill(x + 2, y + 4, x + 4, y + 7, P);
                g.fill(x + 5, y + 7, x + 7, y + 10, P);
            }
            case LR_LOCK -> {                     // 挂锁：锁梁 + 锁身 / padlock
                g.fill(x + 3, y + 1, x + 7, y + 2, A);
                g.fill(x + 2, y + 2, x + 3, y + 5, A);
                g.fill(x + 7, y + 2, x + 8, y + 5, A);
                g.fill(x + 2, y + 4, x + 8, y + 9, P);
                g.fill(x + 4, y + 6, x + 6, y + 8, C_BTN());
            }
            case LR_UP -> {                       // 上箭头 / arrow up
                g.fill(x + 4, y + 2, x + 6, y + 3, P);
                g.fill(x + 3, y + 3, x + 7, y + 4, P);
                g.fill(x + 2, y + 4, x + 8, y + 5, A);
                g.fill(x + 4, y + 5, x + 6, y + 9, P);
            }
            case LR_DOWN -> {                     // 下箭头 / arrow down
                g.fill(x + 4, y + 6, x + 6, y + 7, P);
                g.fill(x + 3, y + 5, x + 7, y + 6, P);
                g.fill(x + 2, y + 4, x + 8, y + 5, A);
                g.fill(x + 4, y + 1, x + 6, y + 5, P);
            }
            case LR_OVERVIEW -> {                 // 总览：列表四横线 / list
                g.fill(x + 1, y + 2, x + 9, y + 3, P);
                g.fill(x + 1, y + 4, x + 9, y + 5, A);
                g.fill(x + 1, y + 6, x + 9, y + 7, P);
                g.fill(x + 1, y + 8, x + 9, y + 9, A);
            }
            default -> {                          // 删除：垃圾桶 / trash
                g.fill(x + 4, y + 1, x + 6, y + 2, A);
                g.fill(x + 2, y + 2, x + 8, y + 3, P);
                g.fill(x + 3, y + 4, x + 7, y + 9, P);
                g.fill(x + 4, y + 5, x + 5, y + 8, C_BTN());
                g.fill(x + 6, y + 5, x + 7, y + 8, C_BTN());
            }
        }
    }

    /** 行图标钮提示文本（图标无字，提示补可发现性）。 / tooltip text for the row icons. */
    private String layerRowTip(int b) {
        return switch (b) {
            case LR_MUTE -> I18n.get("gui.create_schematic_compute.nbs_lr_mute");
            case LR_DETAIL -> I18n.get("gui.create_schematic_compute.nbs_lr_detail");
            case LR_LOCK -> I18n.get("gui.create_schematic_compute.nbs_lr_lock");
            case LR_UP -> I18n.get("gui.create_schematic_compute.nbs_lr_up");
            case LR_DOWN -> I18n.get("gui.create_schematic_compute.nbs_lr_down");
            default -> I18n.get("gui.create_schematic_compute.nbs_lr_delete");
        };
    }

    /** 图标按钮（底 + 描边 + 居中图标）。 / icon button: bg + outline + centred glyph. */
    private void iconButton(GuiGraphics g, int x, int y, int w, int h, int icon, boolean hover, boolean on) {
        g.fill(x, y, x + w, y + h, on ? C_BTN_ON() : (hover ? C_HOVER() : C_BTN()));
        g.renderOutline(x, y, w, h, C_BORDER());
        drawLayerIcon(g, x + (w - 10) / 2, y + (h - 10) / 2, icon);
    }

    private void renderLayerPanel(GuiGraphics g, int mx, int my) {
        int bottom = height - TRANSPORT_H;
        g.fill(0, TOP_H, PANEL_W, bottom, C_PANEL());
        g.fill(PANEL_W - 2, TOP_H, PANEL_W, bottom, C_BORDER());
        if (view == View.ROLL) {
            renderRollKeyList(g, mx, my);
            return;
        }
        g.drawString(font, I18n.get("gui.create_schematic_compute.nbs_layers"), 6, TOP_H + 4, C_TEXT, false);
        // 面板头 + 层（原底部固定属性区改为逐行小按钮 + 设置面板）
        // Panel header + add-layer (the fixed bottom properties area gives way to the
        // per-row buttons and the settings panel).
        button(g, PANEL_W - 40, TOP_H + 2, 34, 14, I18n.get("gui.create_schematic_compute.nbs_add_layer"), mx, my, false);

        // 层列表 / layer list（行高随视图：层序器视图与网格行 1:1 对齐）
        int listTop = TOP_H + 18;
        int listBottom = bottom;
        int rh = layerRowH();
        int rowW = PANEL_W - 12;
        int[][] br = layerRowButtons(rowW, rh);
        int nameW = br[0][0] - 6;
        var layers = kernel.song().layers;
        for (int i = 0; i < layers.size(); i++) {
            int y = listTop + (i - layerScroll) * rh;
            if (y < listTop - rh || y > listBottom - rh) continue;
            boolean sel = i == currentLayer;
            int color = sel ? C_SEL() : (inRect(4, y, rowW, rh, mx, my) ? C_HOVER() : C_PANEL());
            g.fill(4, y, PANEL_W - 8, y + rh - 1, color);
            if (nameEditLayer == i) {
                // 名称 = 点击进通用输入框（行内改名）/ name edits through the common input box
                layerNameBox.setWidth(nameW);      // 宽度贴名称列，不盖行内按钮 / fit the name column
                layerNameBox.setX(8); layerNameBox.setY(y + (rh - 12) / 2);
                layerNameBox.render(g, mx, my, pt0());
            } else {
                String name = layers.get(i).name.isEmpty()
                    ? I18n.get("gui.create_schematic_compute.nbs_layer") + " " + (i + 1) : layers.get(i).name;
                g.drawString(font, font.plainSubstrByWidth(name, nameW), 8, y + (rh - 8) / 2,
                    layers.get(i).locked ? C_TEXT_DIM : C_TEXT, false);
            }
            for (int b = 0; b < br.length; b++) {
                boolean hov = inRect(4 + br[b][0], y + br[b][1], br[b][2], br[b][3], mx, my);
                if (hov) hoverTip = b == LR_MUTE && layers.get(i).muted
                    ? I18n.get("gui.create_schematic_compute.nbs_lr_unmute") : layerRowTip(b);
                // 音频钮字形随静音态换：有声 = 声波，静音 = 斜线（底色高亮口径不变）
                // The audio button swaps glyphs with the mute state: audible = waveform,
                // muted = slash (the lit-background policy is unchanged).
                int icon = b == LR_MUTE ? (layers.get(i).muted ? LR_MUTE : LR_SOUND) : b;
                iconButton(g, 4 + br[b][0], y + br[b][1], br[b][2], br[b][3], icon, hov,
                    (b == LR_LOCK && layers.get(i).locked) || (b == LR_MUTE && layers.get(i).muted));
            }
        }
    }

    /**
     * 卷帘模式左面板：**与谱面键行 1:1 对齐的键位列表**（同起点/同滚动/同键高，
     * {@link NbsPianoRoll#keyRowTop} 单一真相源）+ 层头（◀名称▶ + 设置 + 加层）。
     * Roll-mode left panel: a key list aligned 1:1 with the score rows (same origin / scroll /
     * row height via {@link NbsPianoRoll#keyRowTop}) plus the layer header.
     */
    private void renderRollKeyList(GuiGraphics g, int mx, int my) {
        // 层头：◀ 名称（点击=输入框）▶ 总览 细 加层 删（rollHeaderRects 单一几何源）
        // layer header: prev / name (click = edit) / next / overview / detail / add / delete
        int[][] hr = rollHeaderRects();
        button(g, hr[HB_PREV][0], hr[HB_PREV][1], hr[HB_PREV][2], hr[HB_PREV][3], "<", mx, my, false);
        if (nameEditLayer == currentLayer) {
            layerNameBox.setWidth(hr[HB_NAME][2]);
            layerNameBox.setX(hr[HB_NAME][0]); layerNameBox.setY(hr[HB_NAME][1] + 1);
            layerNameBox.render(g, mx, my, pt0());
        } else {
            String name = currentLayer < kernel.song().layers.size()
                ? kernel.song().layers.get(currentLayer).name : "";
            if (name.isEmpty()) name = I18n.get("gui.create_schematic_compute.nbs_layer") + " " + (currentLayer + 1);
            g.drawString(font, font.plainSubstrByWidth(name, hr[HB_NAME][2] - 4), hr[HB_NAME][0] + 2, TOP_H + 6, C_TEXT, false);
        }
        button(g, hr[HB_NEXT][0], hr[HB_NEXT][1], hr[HB_NEXT][2], hr[HB_NEXT][3], ">", mx, my, false);
        boolean hovOv = inRect(hr[HB_OVERVIEW][0], hr[HB_OVERVIEW][1], hr[HB_OVERVIEW][2], hr[HB_OVERVIEW][3], mx, my);
        if (hovOv) hoverTip = I18n.get("gui.create_schematic_compute.nbs_layer_overview");
        iconButton(g, hr[HB_OVERVIEW][0], hr[HB_OVERVIEW][1], hr[HB_OVERVIEW][2], hr[HB_OVERVIEW][3],
            LR_OVERVIEW, hovOv, layerOverviewOpen);
        boolean hovDt = inRect(hr[HB_DETAIL][0], hr[HB_DETAIL][1], hr[HB_DETAIL][2], hr[HB_DETAIL][3], mx, my);
        if (hovDt) hoverTip = layerRowTip(LR_DETAIL);
        iconButton(g, hr[HB_DETAIL][0], hr[HB_DETAIL][1], hr[HB_DETAIL][2], hr[HB_DETAIL][3],
            LR_DETAIL, hovDt, layerSettingsOpen);
        button(g, hr[HB_ADD][0], hr[HB_ADD][1], hr[HB_ADD][2], hr[HB_ADD][3], "+", mx, my, false);
        boolean hovDel = inRect(hr[HB_DELETE][0], hr[HB_DELETE][1], hr[HB_DELETE][2], hr[HB_DELETE][3], mx, my);
        if (hovDel) hoverTip = layerRowTip(LR_DELETE);
        iconButton(g, hr[HB_DELETE][0], hr[HB_DELETE][1], hr[HB_DELETE][2], hr[HB_DELETE][3], LR_DELETE, hovDel, false);

        // 键位列表：逐键行，与谱面行同几何 / key rows, same geometry as the score rows
        float topKey = roll.viewTopKey(), ppk = roll.pxPerKey();
        int gridTop = rollY() + NbsPianoRoll.RULER_H;
        int listBottom = height - TRANSPORT_H;
        for (int k = (int) Math.ceil(topKey - (listBottom - gridTop) / ppk) - 1; k <= (int) Math.ceil(topKey) + 1; k++) {
            if (k < 0 || k > 87) continue;
            int y0 = gridTop + NbsPianoRoll.keyRowTop(topKey, ppk, k);
            int y1 = gridTop + NbsPianoRoll.keyRowBottom(topKey, ppk, k);
            if (y1 <= TOP_H + 18 || y0 >= listBottom) continue;
            boolean hov = inRect(4, y0, PANEL_W - 8, y1 - y0, mx, my);
            int bg = NbsPianoRoll.isBlackKey(k) ? NbsPianoRoll.C_BLACK_KEY : NbsPianoRoll.C_WHITE_KEY;
            if (hov) bg = C_HOVER();
            if (k < 33 || k > 57) bg = NbsPianoRoll.dim(bg, 0.75f);
            g.fill(4, Math.max(y0, TOP_H + 18), PANEL_W - 8, Math.min(y1, listBottom), bg);
            if (y1 - y0 >= 8) {
                g.drawString(font, NbsPianoRoll.keyName(k), 8, y0 + (y1 - y0 - 8) / 2,
                    k < 33 || k > 57 ? C_TEXT_DIM : C_TEXT, false);
            }
        }
    }

    /** 层设置小面板：行 = 音量/声像/静音/锁定/上移/下移/删除，内容高过视口走滚动条。
     *  The layer settings panel: rows = volume / panning / mute / lock / up / down / delete;
     *  the content scrolls behind a scrollbar when it overflows the viewport. */
    static final int SETTINGS_ROW_H = 30, SETTINGS_VIEW_H = 90, SETTINGS_ROWS = 7;

    /** 层总览面板几何：居中，10 行视口（行 14px），超出滚。
     *  Layer overview geometry: centred, a ten-row viewport (14px rows), scrolling past it. */
    static final int OVERVIEW_W = 200, OVERVIEW_H = 170, OVERVIEW_ROW_H = 14, OVERVIEW_VIEW_H = 140;

    /** 总览面板行矩形（绝对坐标，渲染与命中共用）。 / the overview row rects (absolute). */
    static int[] layerOverviewRow(int width, int height, int row, int scrollY) {
        int x = (width - OVERVIEW_W) / 2 + 8;
        int y = (height - OVERVIEW_H) / 2 + 26 + row * OVERVIEW_ROW_H - scrollY;
        return new int[]{x, y, OVERVIEW_W - 16, OVERVIEW_ROW_H};
    }

    /** 总览滚动钳位（纯函数，像素）：停在 [0, max(0, 层数×行高 − 视口)]。
     *  Overview scroll clamp (pure, pixels). */
    static int clampOverviewScroll(int scroll, int layerCount) {
        int max = Math.max(0, layerCount * OVERVIEW_ROW_H - OVERVIEW_VIEW_H);
        return Math.max(0, Math.min(scroll, max));
    }

    /** 设置面板行矩形（绝对坐标）。 / the settings-panel row rects (absolute). */
    static int[] layerSettingsRow(int width, int height, int row, int scrollY) {
        int x = (width - 180) / 2 + 8;
        int y = (height - 132) / 2 + 26 + row * SETTINGS_ROW_H - scrollY;
        return new int[]{x, y, 152, SETTINGS_ROW_H};
    }

    private void renderLayerSettings(GuiGraphics g, int mx, int my) {
        int px = (width - 180) / 2, py = (height - 132) / 2;
        int contentTop = py + 26, contentBottom = contentTop + SETTINGS_VIEW_H;
        settingsScroll = Math.max(0, Math.min(settingsScroll, SETTINGS_ROWS * SETTINGS_ROW_H - SETTINGS_VIEW_H));
        g.fill(px, py, px + 180, py + 132, C_PANEL());
        g.renderOutline(px, py, 180, 132, C_BORDER());
        g.drawString(font, I18n.get("gui.create_schematic_compute.nbs_layer_props") + " #" + (settingsLayer + 1),
            px + 8, py + 8, C_TEXT, false);
        var layer = settingsLayer < kernel.song().layers.size() ? kernel.song().layers.get(settingsLayer) : null;
        for (int row = 0; row < SETTINGS_ROWS; row++) {
            int[] r = layerSettingsRow(width, height, row, settingsScroll);
            if (r[1] + SETTINGS_ROW_H <= contentTop || r[1] >= contentBottom) continue;
            switch (row) {
                case 0 -> {
                    g.drawString(font, I18n.get("gui.create_schematic_compute.nbs_volume"), r[0], r[1] + 10, C_TEXT_DIM, false);
                    slider(g, r[0] + 40, r[1] + 11, SLIDER_W, layer == null ? 100 : layer.volume, 100, mx, my);
                }
                case 1 -> {
                    g.drawString(font, I18n.get("gui.create_schematic_compute.nbs_panning"), r[0], r[1] + 10, C_TEXT_DIM, false);
                    slider(g, r[0] + 40, r[1] + 11, SLIDER_W, layer == null ? 100 : layer.panning, 200, mx, my);
                }
                case 2 -> button(g, r[0], r[1] + 6, 120, 18, I18n.get(layer != null && layer.muted
                    ? "gui.create_schematic_compute.nbs_unmute" : "gui.create_schematic_compute.nbs_mute"),
                    mx, my, layer != null && layer.muted);
                case 3 -> button(g, r[0], r[1] + 6, 120, 18, I18n.get(layer != null && layer.locked
                    ? "gui.create_schematic_compute.nbs_unlock" : "gui.create_schematic_compute.nbs_lock"),
                    mx, my, layer != null && layer.locked);
                case 4 -> button(g, r[0], r[1] + 6, 120, 18, I18n.get("gui.create_schematic_compute.nbs_lr_up"), mx, my, false);
                case 5 -> button(g, r[0], r[1] + 6, 120, 18, I18n.get("gui.create_schematic_compute.nbs_lr_down"), mx, my, false);
                default -> button(g, r[0], r[1] + 6, 120, 18, I18n.get("gui.create_schematic_compute.nbs_lr_delete"), mx, my, false);
            }
        }
        int sbX = px + 180 - NbsScrollBar.THICK - 2;
        NbsScrollBar.render(g, sbX, contentTop, NbsScrollBar.THICK, SETTINGS_VIEW_H,
            SETTINGS_ROWS * SETTINGS_ROW_H, SETTINGS_VIEW_H, settingsScroll,
            inRect(sbX, contentTop, NbsScrollBar.THICK, SETTINGS_VIEW_H, mx, my));
    }

    /**
     * 层总览面板（卷帘层头 ☰ 打开）：全层层名列，点行 = 切当前层、面板留着便于连点比较，
     * Esc / 点外 / × 关闭，层多过 10 行走滚动条。◀/→ 逐层翻的快车道（实测反馈）。
     * The layer overview panel (roll header ☰): every layer as a name row — clicking a row
     * switches the current layer and keeps the panel open for browsing, Esc / an outside
     * click / × closes, and past ten rows it scrolls. The fast lane for one-by-one ◀/→ paging.
     */
    private void renderLayerOverview(GuiGraphics g, int mx, int my) {
        int px = (width - OVERVIEW_W) / 2, py = (height - OVERVIEW_H) / 2;
        int contentTop = py + 26, contentBottom = contentTop + OVERVIEW_VIEW_H;
        var layers = kernel.song().layers;
        overviewScroll = clampOverviewScroll(overviewScroll, layers.size());
        g.fill(px, py, px + OVERVIEW_W, py + OVERVIEW_H, C_PANEL());
        g.renderOutline(px, py, OVERVIEW_W, OVERVIEW_H, C_BORDER());
        g.drawString(font, I18n.get("gui.create_schematic_compute.nbs_layer_overview"), px + 8, py + 8, C_TEXT, false);
        button(g, px + OVERVIEW_W - 18, py + 4, 12, 12, "x", mx, my, false);
        g.enableScissor(px + 1, contentTop, px + OVERVIEW_W - 1, contentBottom);
        for (int i = 0; i < layers.size(); i++) {
            int[] r = layerOverviewRow(width, height, i, overviewScroll);
            if (r[1] + OVERVIEW_ROW_H <= contentTop || r[1] >= contentBottom) continue;
            boolean hov = inRect(r[0], r[1], r[2], r[3], mx, my);
            boolean cur = i == currentLayer;
            if (cur || hov) g.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], cur ? C_BTN_ON() : C_HOVER());
            String name = layers.get(i).name;
            if (name.isEmpty()) name = I18n.get("gui.create_schematic_compute.nbs_layer") + " " + (i + 1);
            g.drawString(font, font.plainSubstrByWidth((i + 1) + ". " + name, r[2] - 24),
                r[0] + 4, r[1] + 3, C_TEXT, false);
            if (layers.get(i).locked) {
                g.drawString(font, I18n.get("gui.create_schematic_compute.nbs_locked_mark"),
                    r[0] + r[2] - 14, r[1] + 3, C_TEXT_DIM, false);
            }
        }
        g.disableScissor();
        if (layers.size() * OVERVIEW_ROW_H > OVERVIEW_VIEW_H) {
            int sbX = px + OVERVIEW_W - NbsScrollBar.THICK - 2;
            NbsScrollBar.render(g, sbX, contentTop, NbsScrollBar.THICK, OVERVIEW_VIEW_H,
                layers.size() * OVERVIEW_ROW_H, OVERVIEW_VIEW_H, overviewScroll,
                inRect(sbX, contentTop, NbsScrollBar.THICK, OVERVIEW_VIEW_H, mx, my));
        }
    }

    private boolean clickLayerOverview(double mx, double my) {
        int px = (width - OVERVIEW_W) / 2, py = (height - OVERVIEW_H) / 2;
        int contentTop = py + 26, contentBottom = contentTop + OVERVIEW_VIEW_H;
        var layers = kernel.song().layers;
        if (inRect(px + OVERVIEW_W - 18, py + 4, 12, 12, mx, my)) {
            layerOverviewOpen = false;
            return true;
        }
        int sbX = px + OVERVIEW_W - NbsScrollBar.THICK - 2;
        if (layers.size() * OVERVIEW_ROW_H > OVERVIEW_VIEW_H
            && inRect(sbX, contentTop, NbsScrollBar.THICK, OVERVIEW_VIEW_H, mx, my)) {
            int[] t = NbsScrollBar.thumbRect(sbX, contentTop, NbsScrollBar.THICK, OVERVIEW_VIEW_H,
                layers.size() * OVERVIEW_ROW_H, OVERVIEW_VIEW_H, overviewScroll);
            if (inRect(t[0], t[1], t[2], t[3], mx, my)) {
                draggingScrollBar = 4;
                overviewScroll = clampOverviewScroll(NbsScrollBar.posFromThumbDrag(OVERVIEW_VIEW_H, t[3],
                    (int) (my - contentTop), layers.size() * OVERVIEW_ROW_H, OVERVIEW_VIEW_H), layers.size());
            } else {
                overviewScroll = clampOverviewScroll(NbsScrollBar.posFromTrackClick((int) (my - contentTop),
                    t[1] - contentTop, t[3], overviewScroll, layers.size() * OVERVIEW_ROW_H, OVERVIEW_VIEW_H),
                    layers.size());
            }
            return true;
        }
        for (int i = 0; i < layers.size(); i++) {
            int[] r = layerOverviewRow(width, height, i, overviewScroll);
            if (r[1] + OVERVIEW_ROW_H <= contentTop || r[1] >= contentBottom) continue;
            if (inRect(r[0], r[1], r[2], r[3], mx, my)) {
                currentLayer = i;          // 点行换层，面板留着连点 / pick and keep browsing
                return true;
            }
        }
        if (!inRect(px, py, OVERVIEW_W, OVERVIEW_H, mx, my)) layerOverviewOpen = false;
        return true;
    }

    private boolean clickLayerSettings(double mx, double my) {
        int px = (width - 180) / 2, py = (height - 132) / 2;
        int contentTop = py + 26, contentBottom = contentTop + SETTINGS_VIEW_H;
        int sbX = px + 180 - NbsScrollBar.THICK - 2;
        if (inRect(sbX, contentTop, NbsScrollBar.THICK, SETTINGS_VIEW_H, mx, my)) {
            int[] t = NbsScrollBar.thumbRect(sbX, contentTop, NbsScrollBar.THICK, SETTINGS_VIEW_H,
                SETTINGS_ROWS * SETTINGS_ROW_H, SETTINGS_VIEW_H, settingsScroll);
            if (inRect(t[0], t[1], t[2], t[3], mx, my)) {
                draggingScrollBar = 3;
                settingsScroll = NbsScrollBar.posFromThumbDrag(SETTINGS_VIEW_H, t[3], (int) (my - contentTop),
                    SETTINGS_ROWS * SETTINGS_ROW_H, SETTINGS_VIEW_H);
            } else {
                settingsScroll = NbsScrollBar.posFromTrackClick((int) (my - contentTop), t[1] - contentTop, t[3],
                    settingsScroll, SETTINGS_ROWS * SETTINGS_ROW_H, SETTINGS_VIEW_H);
            }
            return true;
        }
        for (int row = 0; row < SETTINGS_ROWS; row++) {
            int[] r = layerSettingsRow(width, height, row, settingsScroll);
            if (r[1] + SETTINGS_ROW_H <= contentTop || r[1] >= contentBottom) continue;
            if (row == 0 && inRect(r[0] + 40, r[1] + 7, SLIDER_W, 16, mx, my)) {
                draggingSlider = 1;
                applySlider(mx);
                return true;
            }
            if (row == 1 && inRect(r[0] + 40, r[1] + 7, SLIDER_W, 16, mx, my)) {
                draggingSlider = 2;
                applySlider(mx);
                return true;
            }
            if (row >= 2 && inRect(r[0], r[1] + 6, 120, 18, mx, my)) {
                layerRowAction(settingsLayer, row == 2 ? LR_MUTE : row == 3 ? LR_LOCK
                    : row == 4 ? LR_UP : row == 5 ? LR_DOWN : LR_DELETE);
                return true;
            }
        }
        if (!inRect(px, py, 180, 132, mx, my)) layerSettingsOpen = false;
        return true;
    }

    /** 滑杆宽（给右侧数值文本留出完整一列，修截断）。
     *  Slider width — leaves a full value column on the right (the clip fix). */
    static final int SLIDER_W = 74;

    private void slider(GuiGraphics g, int x, int y, int w, int value, int max, int mx, int my) {
        g.fill(x, y + 4, x + w, y + 8, C_BTN());
        int fill = (int) ((long) w * Math.max(0, Math.min(max, value)) / max);
        g.fill(x, y + 4, x + fill, y + 8, C_BTN_ON());
        g.renderOutline(x, y + 3, w + 1, 7, C_BORDER());
        String v = max == 200 ? (value - 100 >= 0 ? "+" : "") + (value - 100) : String.valueOf(value);
        g.drawString(font, v, x + w + 6, y + 2, C_TEXT_DIM, false);
    }

    private void button(GuiGraphics g, int x, int y, int w, int h, String label, int mx, int my, boolean on) {
        boolean hover = inRect(x, y, w, h, mx, my);
        g.fill(x, y, x + w, y + h, on ? C_BTN_ON() : (hover ? C_HOVER() : C_BTN()));
        g.renderOutline(x, y, w, h, C_BORDER());
        g.drawString(font, label, x + (w - font.width(label)) / 2, y + (h - 8) / 2, C_TEXT, false);
    }

    private void renderImportDialog(GuiGraphics g, int mx, int my) {
        int x = (width - IMPORT_W) / 2, y = (height - IMPORT_H) / 2;
        g.fill(x, y, x + IMPORT_W, y + IMPORT_H, C_PANEL());
        g.renderOutline(x, y, IMPORT_W, IMPORT_H, C_BORDER());
        g.drawString(font, I18n.get("gui.create_schematic_compute.nbs_import_title"), x + 8, y + 6, C_TEXT, false);
        int rowY = y + 20;
        for (int i = 0; i < importFiles.size() && i < IMPORT_ROWS; i++) {
            int idx = i + importScroll;
            if (idx >= importFiles.size()) break;
            int ry = rowY + i * ROW_H;
            boolean hover = inRect(x + 8, ry, IMPORT_W - 16, ROW_H, mx, my);
            g.fill(x + 8, ry, x + IMPORT_W - 8, ry + ROW_H - 1, hover ? C_HOVER() : C_BTN());
            g.drawString(font, importFiles.get(idx).getFileName().toString(), x + 12, ry + 3, C_TEXT, false);
        }
        // 滚动条：过一屏才画（与滚轮同源滚动；拇指可拖、点轨翻页——此前只有滚轮、滚了无反馈）
        // The scrollbar appears past one screen (same scroll as the wheel; draggable thumb and
        // track paging — there used to be no feedback at all when the wheel scrolled).
        int listH = IMPORT_ROWS * ROW_H;
        if (importFiles.size() > IMPORT_ROWS) {
            int sbX = x + IMPORT_W - NbsScrollBar.THICK - 2;
            NbsScrollBar.render(g, sbX, rowY, NbsScrollBar.THICK, listH,
                importFiles.size() * ROW_H, listH, importScroll * ROW_H,
                inRect(sbX, rowY, NbsScrollBar.THICK, listH, mx, my));
        }
        g.drawString(font, I18n.get("gui.create_schematic_compute.nbs_import_count", importFiles.size()),
            x + 8, y + IMPORT_H - 19, C_TEXT_DIM, false);
        button(g, x + IMPORT_W - 68, y + IMPORT_H - 24, 58, 18, I18n.get("gui.create_schematic_compute.nbs_close"), mx, my, false);
    }

    /** 文件菜单：新建曲目 / 导入 / 导出 三行（行样式同导入列表）。 */
    private void renderFileMenu(GuiGraphics g, int mx, int my) {
        int[][] r = fileMenuRects(width);
        g.fill(r[0][0] - 1, r[0][1] - 1, r[0][0] + FM_W + 1, r[FM_ROWS - 1][1] + FM_ROW_H + 1, C_BORDER());
        String[] labels = {
            I18n.get("gui.create_schematic_compute.nbs_file_new"),
            I18n.get("gui.create_schematic_compute.nbs_import"),
            I18n.get("gui.create_schematic_compute.nbs_export"),
        };
        for (int i = 0; i < FM_ROWS; i++) {
            boolean hover = inRect(r[i][0], r[i][1], r[i][2], r[i][3], mx, my);
            g.fill(r[i][0], r[i][1], r[i][0] + FM_W, r[i][1] + FM_ROW_H, hover ? C_HOVER() : C_PANEL());
            g.drawString(font, labels[i], r[i][0] + 8, r[i][1] + (FM_ROW_H - 8) / 2, C_TEXT, false);
        }
    }

    /** 文件菜单点击：行内命中执行对应动作；点外面（含再点文件钮）收起——模态吞点击。 */
    private boolean clickFileMenu(double mx, double my) {
        int[][] r = fileMenuRects(width);
        for (int i = 0; i < FM_ROWS; i++) {
            if (!inRect(r[i][0], r[i][1], r[i][2], r[i][3], mx, my)) continue;
            fileMenuOpen = false;
            switch (i) {
                case FM_NEW -> doNewSong();
                case FM_IMPORT -> openImportDialog();
                case FM_EXPORT -> openExportDialog();
            }
            return true;
        }
        fileMenuOpen = false;
        return true;
    }

    /** Set 弹窗（B3）：字段三键 + 数值框 + 绝对/增量 + 应用/取消。
     *  The Set dialog (B3): field buttons + value box + absolute/relative + apply/cancel. */
    private void renderSetDialog(GuiGraphics g, int mx, int my) {
        int w = 260, h = 118, x = (width - w) / 2, y = (height - h) / 2;
        g.fill(x, y, x + w, y + h, C_PANEL());
        g.renderOutline(x, y, w, h, C_BORDER());
        g.drawString(font, I18n.get("gui.create_schematic_compute.nbs_set_title"), x + 8, y + 6, C_TEXT, false);
        button(g, x + 8, y + 22, 76, 16, I18n.get("gui.create_schematic_compute.nbs_set_velocity"),
            mx, my, setField == NbsEditorKernel.FIELD_VELOCITY);
        button(g, x + 90, y + 22, 76, 16, I18n.get("gui.create_schematic_compute.nbs_set_panning"),
            mx, my, setField == NbsEditorKernel.FIELD_PANNING);
        button(g, x + 172, y + 22, 80, 16, I18n.get("gui.create_schematic_compute.nbs_set_pitch"),
            mx, my, setField == NbsEditorKernel.FIELD_PITCH);
        setValueBox.setX(x + 8); setValueBox.setY(y + 47);
        setValueBox.render(g, mx, my, pt0());
        button(g, x + 96, y + 46, 76, 16, I18n.get("gui.create_schematic_compute.nbs_set_abs"), mx, my, setAbsolute);
        button(g, x + 178, y + 46, 76, 16, I18n.get("gui.create_schematic_compute.nbs_set_rel"), mx, my, !setAbsolute);
        button(g, x + w - 128, y + h - 24, 58, 18, I18n.get("gui.create_schematic_compute.nbs_set_ok"), mx, my, false);
        button(g, x + w - 68, y + h - 24, 58, 18, I18n.get("gui.create_schematic_compute.nbs_cancel"), mx, my, false);
    }

    private boolean clickSetDialog(double mx, double my) {
        int w = 260, h = 118, x = (width - w) / 2, y = (height - h) / 2;
        if (inRect(x + 8, y + 22, 76, 16, mx, my)) { setField = NbsEditorKernel.FIELD_VELOCITY; return true; }
        if (inRect(x + 90, y + 22, 76, 16, mx, my)) { setField = NbsEditorKernel.FIELD_PANNING; return true; }
        if (inRect(x + 172, y + 22, 80, 16, mx, my)) { setField = NbsEditorKernel.FIELD_PITCH; return true; }
        if (inRect(x + 8, y + 46, 80, 14, mx, my)) { setValueBox.setFocused(true); return true; }
        if (inRect(x + 96, y + 46, 76, 16, mx, my)) { setAbsolute = true; return true; }
        if (inRect(x + 178, y + 46, 76, 16, mx, my)) { setAbsolute = false; return true; }
        if (inRect(x + w - 128, y + h - 24, 58, 18, mx, my)) {
            applySet();
            return true;
        }
        if (inRect(x + w - 68, y + h - 24, 58, 18, mx, my) || !inRect(x, y, w, h, mx, my)) {
            setOpen = false;
            return true;
        }
        setValueBox.setFocused(false);
        return true;
    }

    /** 打开 Set 弹窗（选区非空）；数值框聚焦。 / open the Set dialog on a non-empty selection. */
    private void openSetDialog() {
        if (selection.isEmpty()) {
            feedback(I18n.get("gui.create_schematic_compute.nbs_sel_empty"));
            return;
        }
        if (editLocked()) return;
        setOpen = true;
        setField = NbsEditorKernel.FIELD_VELOCITY;
        setAbsolute = true;
        setValueBox.setValue("0");
        setValueBox.setFocused(true);
    }

    /** 应用 Set：选区级属性（绝对/增量，快照撤销）。 / apply: the selection-wide field set. */
    private void applySet() {
        int v;
        try {
            v = Integer.parseInt(setValueBox.getValue().trim());
        } catch (NumberFormatException e) {
            return;                       // 数值没写好 = 不动 / a malformed value applies nothing
        }
        int n = kernel.setSelectionField(selection, setField, v, setAbsolute);
        setOpen = false;
        setValueBox.setFocused(false);
        if (n == 0) {
            feedback(I18n.get("gui.create_schematic_compute.nbs_set_nothing"));
            return;
        }
        feedback(I18n.get("gui.create_schematic_compute.nbs_set_done", n));
    }

    private void renderExportDialog(GuiGraphics g, int mx, int my) {
        int w = 220, h = 80, x = (width - w) / 2, y = (height - h) / 2;
        g.fill(x, y, x + w, y + h, C_PANEL());
        g.renderOutline(x, y, w, h, C_BORDER());
        g.drawString(font, I18n.get("gui.create_schematic_compute.nbs_export_title"), x + 8, y + 6, C_TEXT, false);
        exportNameBox.setX(x + 8); exportNameBox.setY(y + 24);
        exportNameBox.render(g, mx, my, pt0());
        button(g, x + w - 128, y + h - 24, 58, 18, I18n.get("gui.create_schematic_compute.nbs_export_ok"), mx, my, false);
        button(g, x + w - 68, y + h - 24, 58, 18, I18n.get("gui.create_schematic_compute.nbs_cancel"), mx, my, false);
    }

    private void renderGuide(GuiGraphics g) {
        int w = 300, h = 200, x = (width - w) / 2, y = (height - h) / 2;
        g.fill(x, y, x + w, y + h, C_PANEL());
        g.renderOutline(x, y, w, h, C_BORDER());
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
            I18n.get("gui.create_schematic_compute.nbs_guide_8"),
            I18n.get("gui.create_schematic_compute.nbs_guide_9"),
            I18n.get("gui.create_schematic_compute.nbs_guide_10"),
            I18n.get("gui.create_schematic_compute.nbs_guide_11"),
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
        // 点别处 = 取消行内改名（草稿丢弃；点在框内不打断——实测反馈：输入框滞留吃键）
        // An outside click cancels the inline rename (the draft is discarded; clicks inside
        // the box do not interrupt — field feedback: the box lingered and ate keystrokes).
        if (nameEditLayer >= 0 && (layerNameBox == null || !inRect(layerNameBox.getX(), layerNameBox.getY(),
            layerNameBox.getWidth(), layerNameBox.getHeight(), mx, my))) {
            cancelNameEdit();
        }
        // 曲名改名失焦即提交（实测反馈：点别处想的是提交，直接回退读作丢稿）：框外点击先
        // 提交草稿，这一下不吞、后续路由照走；Esc 仍走取消。层名改名维持「点别处取消」的
        // 旧契约不动（各自的实测反馈口径不同）。
        // The song rename commits on blur (field feedback: an outside click means commit —
        // reverting reads as losing the draft): an outside click commits the draft first and
        // is not consumed; Esc still cancels. The layer-name rename keeps its own
        // outside-click-cancels contract (different feedback rounds).
        if (songNameEdit && (songNameBox == null || !inRect(songNameBox.getX(), songNameBox.getY(),
            songNameBox.getWidth(), songNameBox.getHeight(), mx, my))) {
            commitSongName();
        }
        // 对话框优先 / dialogs first
        if (showGuide) { showGuide = false; return true; }
        if (fileMenuOpen) return clickFileMenu(mx, my);
        if (importOpen) return clickImport(mx, my, btn);
        if (exportOpen) return clickExport(mx, my, btn);
        if (setOpen) return clickSetDialog(mx, my);
        if (layerSettingsOpen) return clickLayerSettings(mx, my);
        if (layerOverviewOpen) return clickLayerOverview(mx, my);

        if (btn != 0) {
            // 右键落在网格上 = 擦除；其余右键忽略。擦除同样按笔划分组撤销。
            // 播放锁（社区 playing=0 语义）：播放中网格不可编辑。
            boolean overGrid = view == View.ROLL
                ? roll.hit(rollX(), rollY(), gridW(), gridH(), mx, my) == NbsPianoRoll.Zone.GRID
                : laneGrid.hit(rollX(), rollY(), gridW(), gridH(), mx, my) == NbsLaneGrid.Zone.GRID;
            // 右键点空白 = 取消选择（实测反馈：选中后无路可退）：无害操作，不进播放锁/防误触；
            // 有得擦（当前层该 tick 有音符）就只擦不取消，擦除笔划照旧可从空格起划。
            // RMB on blank cells clears the selection (field feedback: a selection had no
            // way out) — harmless, never gated; where an erase can land (a note at that
            // tick on the current layer) it only erases, and erase strokes still sweep
            // from blank cells.
            boolean blankForErase = view == View.ROLL
                ? kernel.song().getNote((int) Math.floor(roll.tickAt(rollX(), mx)), currentLayer) == null
                : laneNoteAt(mx, my) == null;
            if (btn == 1 && overGrid && blankForErase) {
                selection.clear();
            }
            if (overGrid && (editLocked() || scrollGuarded())) return true;
            boolean consumed = view == View.ROLL
                ? roll.mouseDown(rollX(), rollY(), gridW(), gridH(), mx, my, btn, kernel, currentLayer, currentInstrument)
                : laneGrid.mouseDown(rollX(), rollY(), gridW(), gridH(), mx, my, btn, kernel,
                    currentInstrument, toolKey, layerScroll, layerRowH());
            if (consumed) kernel.beginBatch();
            return true;
        }

        // 顶栏 / top bar
        if (my < TOP_H) {
            if (clickSongTitle(mx, my)) return true;
            return clickTopBar(mx, my);
        }
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
            // 选择手势（框选键按住 / Ctrl 加减选）：无害手势，不进播放锁与防误触
            // Select gestures (box-select hold / Ctrl toggle): harmless — bypass the
            // playback lock and the scroll guard.
            if (btn == 0 && (boxSelectHeld() || hasControlDown())) {
                armSelectGesture(mx, my);
                return true;
            }
            if (editLocked() || scrollGuarded()) return true;
            if (btn == 0 && roll.hit(rollX(), rollY(), gridW(), gridH(), mx, my) == NbsPianoRoll.Zone.GRID) {
                // 按在音符上 = 搬移待判（拖 = 搬移，单击 = 原点放语义）；只抓当前层音符
                // （卷帘编辑作用域 = 当前层）或选中音符（拖选区）。
                // Pressing a note arms the move gesture (drag = move, click = the original
                // toggle semantics); only current-layer notes (the roll edits that layer)
                // or selected notes (drag the selection) are grabbable.
                NbsSong.Note pressed = noteAt(mx, my);
                if (pressed != null
                    && (selection.contains(pressed.tick, pressed.layer) || pressed.layer == currentLayer)) {
                    armMoveGesture(pressed, mx, my);
                    return true;
                }
            }
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
            if (oct != 0) dispatchNbsAction(oct < 0 ? EditorKeys.Action.NBS_OCTAVE_PREV : EditorKeys.Action.NBS_OCTAVE_NEXT);
            else {
                int k = pianoStrip.keyAt(rollW() - NbsPianoStrip.BTN_W, NbsPianoStrip.STRIP_H - 4,
                    mx - rollX() - NbsPianoStrip.BTN_W, my - stripY() - 2,
                    pianoStrip.startKey(), NbsPianoStrip.WINDOW_KEYS);
                if (k >= 0) {
                    toolKey = k;
                    pianoStrip.ensureVisible(k);
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
        if (btn == 0 && (boxSelectHeld() || hasControlDown())) {
            armSelectGesture(mx, my);
            return true;
        }
        if (editLocked() || scrollGuarded()) return true;
        if (btn == 0) {
            // 按在音符上 = 搬移待判（拖 = 搬移，单击 = 整格覆盖原语义）；空格 = 落点待判
            // （单击落点 / 拖框选——社区左键拖语义，落点延到松手判定）。
            // Pressing a note arms the move gesture (drag = move, click = the original
            // whole-cell overwrite); an empty cell arms place-or-marquee (community
            // left-drag semantics, placement defers to release).
            NbsSong.Note pressed = laneNoteAt(mx, my);
            if (pressed != null) {
                armMoveGesture(pressed, mx, my);
                return true;
            }
            armPlaceGesture(mx, my);
            return true;
        }
        return super.mouseClicked(mx, my, btn);
    }

    // ══════════════ 选择手势（批次三 B1）/ selection gestures (batch 3 B1) ══════════════

    /** 框选键按住 = 框选（Ctrl = 加选）；只按 Ctrl = 单格加减选（拖动即转加选框选）。
     *  Box-select held = marquee (Ctrl adds); Ctrl alone = cell toggle (dragging upgrades
     *  to an additive marquee). */
    private void armSelectGesture(double mx, double my) {
        gesture = boxSelectHeld() ? GESTURE_MARQUEE : GESTURE_TOGGLE;
        marqueeAdd = hasControlDown();
        pressX = marqX = mx;
        pressY = marqY = my;
    }

    /** 层列左键按下：落点待判（单击放音符 / 拖动框选）。 / lane LMB press: place-or-marquee. */
    private void armPlaceGesture(double mx, double my) {
        gesture = GESTURE_PLACE;
        marqueeAdd = false;
        pressX = marqX = mx;
        pressY = marqY = my;
    }

    /** 框选键是否按住（轮询物理键态，keyReleased 丢失即自愈）。
     *  Whether the box-select key is held (physical poll — heals lost keyReleased). */
    private boolean boxSelectHeld() {
        int k = io.github.y15173334444.create_schematic_compute.client.editor.EditorKeys.nbsBoxSelectHoldKey();
        if (k < 0 || minecraft == null) return false;
        long win = minecraft.getWindow().getWindow();
        return org.lwjgl.glfw.GLFW.glfwGetKey(win, k) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
    }

    /** 松手提交：框选成区、单格加减选、落点（层列单击）、搬移、或搬移手势的单击回落。 */
    private void commitGesture() {
        int g = gesture;
        gesture = GESTURE_NONE;
        if (g == GESTURE_MARQUEE) commitMarquee();
        else if (g == GESTURE_TOGGLE) toggleCellAt(pressX, pressY);
        else if (g == GESTURE_PLACE) placeAtPress();
        else if (g == GESTURE_MOVE) clickAtPress();
        else if (g == GESTURE_MOVING) commitMove();
    }

    /** 框选提交：矩形内可选音符格进选区（REPLACE = 换选，Ctrl = 加选）。
     *  Marquee commit: the rect's selectable note cells join (REPLACE swaps, Ctrl adds). */
    private void commitMarquee() {
        double x0 = Math.min(pressX, marqX), x1 = Math.max(pressX, marqX);
        double y0 = Math.min(pressY, marqY), y1 = Math.max(pressY, marqY);
        NbsSelection.Mode mode = marqueeAdd ? NbsSelection.Mode.ADD : NbsSelection.Mode.REPLACE;
        int t0 = (int) Math.floor(view == View.ROLL ? roll.tickAt(rollX(), x0) : laneGrid.tickAt(rollX(), x0));
        int t1 = (int) Math.floor(view == View.ROLL ? roll.tickAt(rollX(), x1) : laneGrid.tickAt(rollX(), x1));
        if (view == View.ROLL) {
            // 纵轴是键：框选收视觉矩形内的音符格 / roll: collect the notes inside the visual rect
            int kHi = (int) Math.floor(roll.keyAt(rollY(), y0)), kLo = (int) Math.floor(roll.keyAt(rollY(), y1));
            selection.selectByTickKey(kernel.song(), t0, t1, kLo, kHi, mode);
        } else {
            selection.selectRect(kernel.song(), t0, t1, layerRowAt(y0), layerRowAt(y1), mode);
        }
    }

    /** 层列视图：鼠标 y → 层号（可能越界；选区收集时按音符过滤）。 / lane view: mouse y → raw layer row. */
    private int layerRowAt(double my) {
        return layerScroll + (int) Math.floor((my - (rollY() + NbsLaneGrid.RULER_H)) / (double) layerRowH());
    }

    /** 单格加减选（Ctrl+点音符格）。 / Cell toggle (Ctrl+click a note cell). */
    private void toggleCellAt(double mx, double my) {
        if (view == View.ROLL) {
            NbsSong.Note hit = noteAt(mx, my);
            if (hit != null) selection.toggleCell(kernel.song(), hit.tick, hit.layer);
        } else {
            int t = (int) Math.floor(laneGrid.tickAt(rollX(), mx));
            int l = laneGrid.layerAt(rollY(), my, layerScroll, layerRowH(), kernel.song().layers.size());
            if (t >= 0 && l >= 0) selection.toggleCell(kernel.song(), t, l);
        }
    }

    /** 层列单击落点（松手未拖时）：走 laneGrid 同一落点路径（覆盖 + 试听）。
     *  Lane click placement (release without a drag): the lane grid's own path (overwrite + audition). */
    private void placeAtPress() {
        laneGrid.mouseDown(rollX(), rollY(), gridW(), gridH(), pressX, pressY, 0,
            kernel, currentInstrument, toolKey, layerScroll, layerRowH());
    }

    /** 鼠标下的音符（卷帘：同键多层先取当前层）。 / the note under the cursor (roll prefers the current layer). */
    private NbsSong.Note noteAt(double mx, double my) {
        int t = (int) Math.floor(roll.tickAt(rollX(), mx));
        int key = (int) Math.floor(roll.keyAt(rollY(), my));
        NbsSong.Note hit = null;
        for (NbsSong.Note n : kernel.song().notesAtTick(t)) {
            if (n.key != key) continue;
            if (n.layer == currentLayer) return n;
            if (hit == null) hit = n;
        }
        return hit;
    }

    /** 鼠标下的层列音符（行 = 层）。 / the lane note under the cursor (row = layer). */
    private NbsSong.Note laneNoteAt(double mx, double my) {
        int t = (int) Math.floor(laneGrid.tickAt(rollX(), mx));
        int l = laneGrid.layerAt(rollY(), my, layerScroll, layerRowH(), kernel.song().layers.size());
        return (t >= 0 && l >= 0) ? kernel.song().getNote(t, l) : null;
    }

    /** 搬移手势（B2）：按住音符待判——拖 = 搬移（选中音符拖整段选区），单击 = 原点放语义。
     *  Move gesture (B2): holding a note — drag moves it (a selected note drags the whole
     *  selection), a plain click keeps the original toggle semantics. */
    private void armMoveGesture(NbsSong.Note pressed, double mx, double my) {
        gesture = GESTURE_MOVE;
        moveTick = pressed.tick;
        moveLayer = pressed.layer;
        moveKey = pressed.key;
        moveSelDrag = selection.contains(pressed.tick, pressed.layer);
        pressX = marqX = mx;
        pressY = marqY = my;
    }

    /** 拖动增量 {dTick, dLayer, dKey}：卷帘 = tick×键、层列 = tick×层（按下格 → 当前格）。
     *  Drag delta {dTick, dLayer, dKey}: roll = tick×key, lane = tick×layer (press cell → current cell). */
    private int[] moveDelta() {
        if (view == View.ROLL) {
            int dT = (int) Math.floor(roll.tickAt(rollX(), marqX)) - (int) Math.floor(roll.tickAt(rollX(), pressX));
            int dK = (int) Math.floor(roll.keyAt(rollY(), marqY)) - (int) Math.floor(roll.keyAt(rollY(), pressY));
            return new int[]{dT, 0, dK};
        }
        int dT = (int) Math.floor(laneGrid.tickAt(rollX(), marqX)) - (int) Math.floor(laneGrid.tickAt(rollX(), pressX));
        return new int[]{dT, layerRowAt(marqY) - layerRowAt(pressY), 0};
    }

    /** 搬移提交（松手）：没跨格 = 不改谱（拖动意图已定，不回落单击——防蹭键误删）。
     *  Move commit (release): no cell delta = no score change (the drag intent is set —
     *  never falls back to click semantics, which could delete on a jitter). */
    private void commitMove() {
        int[] d = moveDelta();
        if (d[0] == 0 && d[1] == 0 && d[2] == 0) return;
        if (moveSelDrag) {
            NbsSelection moved = kernel.moveSelection(selection, d[0], d[1], d[2]);
            if (moved.isEmpty()) {
                feedback(I18n.get("gui.create_schematic_compute.nbs_move_blocked"));
                return;
            }
            selection = moved;
            return;
        }
        if (!kernel.moveNote(moveTick, moveLayer, moveTick + d[0], moveLayer + d[1], moveKey + d[2])) {
            feedback(I18n.get("gui.create_schematic_compute.nbs_move_blocked"));
        }
    }

    /** 搬移手势单击回落（松手未拖）：原点放语义——卷帘 toggleNote / 层列整格覆盖。
     *  Move-gesture click fallback (release without a drag): the original toggle
     *  semantics — roll toggleNote / lane whole-cell overwrite. */
    private void clickAtPress() {
        if (view == View.LANE) {
            placeAtPress();
            return;
        }
        int t = (int) Math.floor(roll.tickAt(rollX(), pressX));
        int key = (int) Math.floor(roll.keyAt(rollY(), pressY));
        if (t >= 0 && key >= 0 && key <= 87) kernel.toggleNote(t, currentLayer, key, currentInstrument);
    }

    /** 搬移预览：把将要落格的音符画成描边（不动谱，只画目标）。 / move preview: outline the
     *  target cells (the score is untouched until release). */
    private void drawMovePreview(GuiGraphics g) {
        int[] d = moveDelta();
        if (d[0] == 0 && d[1] == 0 && d[2] == 0) return;
        if (moveSelDrag) {
            for (int[] c : selection.cells()) {
                NbsSong.Note n = kernel.song().getNote(c[0], c[1]);
                if (n != null) drawTargetOutline(g, n.tick + d[0], n.layer + d[1], n.key + d[2]);
            }
        } else {
            drawTargetOutline(g, moveTick + d[0], moveLayer + d[1], moveKey + d[2]);
        }
    }

    /** 目标格描边（卷帘按 (tick, key)、层列按 (tick, layer)），裁剪到网格可视区。
     *  Target-cell outline (roll by (tick, key), lane by (tick, layer)), clipped to the
     *  grid viewport. */
    private void drawTargetOutline(GuiGraphics g, int tick, int layer, int key) {
        int keyClamped = Math.max(0, Math.min(87, key));
        int[] r;
        if (view == View.ROLL) {
            float x0 = rollX() + NbsPianoRoll.GUTTER_W + (tick - roll.scrollTick()) * roll.pxPerTick();
            float x1 = rollX() + NbsPianoRoll.GUTTER_W + (tick + 1 - roll.scrollTick()) * roll.pxPerTick();
            float yTop = rollY() + NbsPianoRoll.RULER_H + (roll.viewTopKey() - (keyClamped + 1)) * roll.pxPerKey();
            float yBot = rollY() + NbsPianoRoll.RULER_H + (roll.viewTopKey() - keyClamped) * roll.pxPerKey();
            r = new int[]{(int) x0, (int) yTop, (int) x1, (int) yBot};
        } else {
            float x0 = rollX() + (tick - laneGrid.scrollTick()) * laneGrid.pxPerTick();
            float x1 = rollX() + (tick + 1 - laneGrid.scrollTick()) * laneGrid.pxPerTick();
            int y0 = rollY() + NbsLaneGrid.RULER_H + (layer - layerScroll) * layerRowH();
            r = new int[]{(int) x0, y0, (int) x1, y0 + layerRowH()};
        }
        int xL = Math.max(r[0], rollX()), yT = Math.max(r[1], rollY());
        int xR = Math.min(r[2], rollX() + gridW()), yB = Math.min(r[3], rollY() + gridH());
        if (xR <= xL || yB <= yT) return;
        g.renderOutline(xL, yT, xR - xL, yB - yT, NbsPianoRoll.C_SELECTED());
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

    /** 顶栏曲名点击：未编辑 = 进行内改名；编辑中框内 = 光标定位（框 rect 是唯一编辑
     *  热区，顶栏其余按钮照旧）。 / Song-title clicks: idle = start the inline rename;
     *  while editing, a click inside the box positions the caret (the box rect is the
     *  only edit hot zone — the rest of the top bar behaves as usual). */
    private boolean clickSongTitle(double mx, double my) {
        if (songNameEdit) {
            if (inRect(songNameBox.getX(), songNameBox.getY(), songNameBox.getWidth(), songNameBox.getHeight(), mx, my)) {
                songNameBox.mouseClicked(mx, my, 0);
                return true;
            }
            return false;
        }
        int[] t = songTitleRect();
        if (inRect(t[0], t[1], t[2], t[3], mx, my)) { startSongNameEdit(); return true; }
        return false;
    }

    private boolean clickTopBar(double mx, double my) {
        int[][] r = topBarRects(width);
        for (int i = 0; i < r.length; i++) {
            if (!inRect(r[i][0], r[i][1], r[i][2], r[i][3], mx, my)) continue;
            if (i == TB_CLOSE) { onClose(); return true; }
            if (i == TB_GUIDE) { showGuide = !showGuide; return true; }
            if (i == TB_FILE) { fileMenuOpen = !fileMenuOpen; return true; }
            // 其余按钮走命令派发（与键位绑定同一条路径，行为不漂移）
            // The remaining buttons share the command dispatch (one path with the keys).
            var act = switch (i) {
                case TB_SAVE -> EditorKeys.Action.NBS_SAVE;
                case TB_VIEW -> EditorKeys.Action.NBS_VIEW_TOGGLE;
                case TB_REDO -> EditorKeys.Action.NBS_REDO;
                default -> EditorKeys.Action.NBS_UNDO;
            };
            dispatchNbsAction(act);
            return true;
        }
        return true;
    }

    private boolean clickTransport(double mx, double my) {
        int tempoLabelW = font.width(I18n.get("gui.create_schematic_compute.nbs_tempo"));
        int[][] r = transportBarRects(width, height, tempoLabelW);
        if (inRect(r[TR_PLAY][0], r[TR_PLAY][1], r[TR_PLAY][2], r[TR_PLAY][3], mx, my)) { dispatchNbsAction(EditorKeys.Action.NBS_PLAY_PAUSE); return true; }
        if (inRect(r[TR_STOP][0], r[TR_STOP][1], r[TR_STOP][2], r[TR_STOP][3], mx, my)) { dispatchNbsAction(EditorKeys.Action.NBS_STOP); return true; }
        if (inRect(r[TR_LOOP][0], r[TR_LOOP][1], r[TR_LOOP][2], r[TR_LOOP][3], mx, my)) { dispatchNbsAction(EditorKeys.Action.NBS_LOOP); return true; }
        if (inRect(r[TR_TEMPO][0], r[TR_TEMPO][1], r[TR_TEMPO][2], r[TR_TEMPO][3], mx, my)) {
            tempoBox.setFocused(true);
            return true;
        }
        if (inRect(r[TR_INST_NEXT][0], r[TR_INST_NEXT][1], r[TR_INST_NEXT][2], r[TR_INST_NEXT][3], mx, my)) { dispatchNbsAction(EditorKeys.Action.NBS_INSTRUMENT_NEXT); return true; }
        if (inRect(r[TR_INST_PREV][0], r[TR_INST_PREV][1], r[TR_INST_PREV][2], r[TR_INST_PREV][3], mx, my)) { dispatchNbsAction(EditorKeys.Action.NBS_INSTRUMENT_PREV); return true; }
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
        if (view == View.ROLL) return clickRollKeyList(mx, my);
        int listTop = TOP_H + 18;
        int listBottom = height - TRANSPORT_H;
        var layers = kernel.song().layers;

        // 面板头 + 层 / header add-layer
        if (inRect(PANEL_W - 40, TOP_H + 2, 34, 14, mx, my)) {
            kernel.beginBatch();
            kernel.addLayer();
            kernel.endBatch();
            currentLayer = kernel.song().layers.size() - 1;
            return true;
        }

        // 列表行：行内小按钮优先 → 名称 = 点击进输入框 → 其余点选
        // rows: per-row buttons first → name = click for the input box → else select
        int rh = layerRowH();
        int rowW = PANEL_W - 12;
        int[][] br = layerRowButtons(rowW, rh);
        int nameW = br[0][0] - 6;
        for (int i = 0; i < layers.size(); i++) {
            int y = listTop + (i - layerScroll) * rh;
            if (y < listTop || y > listBottom - rh) continue;
            if (!inRect(4, y, rowW, rh, mx, my)) continue;
            for (int b = 0; b < br.length; b++) {
                if (inRect(4 + br[b][0], y + br[b][1], br[b][2], br[b][3], mx, my)) {
                    layerRowAction(i, b);
                    return true;
                }
            }
            currentLayer = i;
            if (inRect(8, y, nameW, rh, mx, my)) {
                startNameEdit(i);
                return true;
            }
            return true;
        }
        return true;
    }

    /** 名称点击 = 通用输入框行内改名（Enter 提交；Esc/点别处取消，草稿丢弃）。
     *  Click the name → inline edit (Enter commits; Esc or an outside click cancels). */
    private void startNameEdit(int layer) {
        if (layer < 0 || layer >= kernel.song().layers.size()) return;
        if (nameEditLayer >= 0) commitLayerName();
        nameEditLayer = layer;
        layerNameBox.setValue(kernel.song().layers.get(layer).name);
        layerNameBox.setFocused(true);
    }

    /** 卷帘面板点击：层头（◀名称▶ 总览 细 加层 删）+ 键位行（点 = 试听该键）。
     *  Roll-panel clicks: the layer header (prev / name / next / overview / detail / add /
     *  delete) plus key rows (click auditions the key). */
    private boolean clickRollKeyList(double mx, double my) {
        if (my < TOP_H + 18) {
            int[][] hr = rollHeaderRects();
            if (inRect(hr[HB_PREV][0], hr[HB_PREV][1], hr[HB_PREV][2], hr[HB_PREV][3], mx, my)) {
                currentLayer = Math.max(0, currentLayer - 1);
                return true;
            }
            if (inRect(hr[HB_NAME][0], hr[HB_NAME][1], hr[HB_NAME][2], hr[HB_NAME][3], mx, my)) {
                startNameEdit(currentLayer);
                return true;
            }
            if (inRect(hr[HB_NEXT][0], hr[HB_NEXT][1], hr[HB_NEXT][2], hr[HB_NEXT][3], mx, my)) {
                currentLayer = Math.min(kernel.song().layers.size() - 1, currentLayer + 1);
                return true;
            }
            if (inRect(hr[HB_OVERVIEW][0], hr[HB_OVERVIEW][1], hr[HB_OVERVIEW][2], hr[HB_OVERVIEW][3], mx, my)) {
                layerOverviewOpen = !layerOverviewOpen;
                overviewScroll = 0;
                return true;
            }
            if (inRect(hr[HB_DETAIL][0], hr[HB_DETAIL][1], hr[HB_DETAIL][2], hr[HB_DETAIL][3], mx, my)) {
                settingsLayer = currentLayer;
                layerSettingsOpen = true;
                settingsScroll = 0;
                return true;
            }
            if (inRect(hr[HB_ADD][0], hr[HB_ADD][1], hr[HB_ADD][2], hr[HB_ADD][3], mx, my)) {
                kernel.beginBatch();
                kernel.addLayer();
                kernel.endBatch();
                currentLayer = kernel.song().layers.size() - 1;
                return true;
            }
            if (inRect(hr[HB_DELETE][0], hr[HB_DELETE][1], hr[HB_DELETE][2], hr[HB_DELETE][3], mx, my)) {
                layerRowAction(currentLayer, LR_DELETE);   // 守卫/钳位走层行同一条 / same guards as the row button
                return true;
            }
            return true;
        }
        float topKey = roll.viewTopKey(), ppk = roll.pxPerKey();
        int gridTop = rollY() + NbsPianoRoll.RULER_H;
        int k = (int) Math.floor(topKey - (my - gridTop) / ppk);
        if (k >= 0 && k <= 87) {
            audition(new NbsSong.Note(0, 0, currentInstrument, k, 100, 100, 0));
        }
        return true;
    }

    /** 层行小按钮动作（静音/细/锁/↑/↓/删）。 / the per-row button actions. */
    private void layerRowAction(int layer, int action) {
        var layers = kernel.song().layers;
        if (layer < 0 || layer >= layers.size()) return;
        switch (action) {
            case LR_MUTE -> kernel.setLayerMuted(layer, !layers.get(layer).muted);
            case LR_DETAIL -> {
                settingsLayer = layer;
                layerSettingsOpen = true;
                settingsScroll = 0;
            }
            case LR_LOCK -> kernel.setLayerLocked(layer, !layers.get(layer).locked);
            case LR_UP -> {
                kernel.moveLayer(layer, layer - 1);
                if (currentLayer == layer) currentLayer = layer - 1;
                else if (currentLayer == layer - 1) currentLayer = layer;
            }
            case LR_DOWN -> {
                kernel.moveLayer(layer, layer + 1);
                if (currentLayer == layer) currentLayer = layer + 1;
                else if (currentLayer == layer + 1) currentLayer = layer;
            }
            case LR_DELETE -> {
                if (layers.size() > 1) {
                    kernel.removeLayer(layer);
                    if (currentLayer >= layers.size()) currentLayer = layers.size() - 1;
                    if (layerSettingsOpen && settingsLayer >= kernel.song().layers.size()) layerSettingsOpen = false;
                }
            }
        }
    }

    private void applySlider(double mx) {
        if (settingsLayer >= kernel.song().layers.size()) return;
        int row = draggingSlider == 1 ? 0 : 1;
        int[] r = layerSettingsRow(width, height, row, settingsScroll);
        double rel = Math.max(0, Math.min(1, (mx - (r[0] + 40)) / (double) SLIDER_W));
        if (draggingSlider == 1) kernel.setLayerVolume(settingsLayer, (int) Math.round(rel * 100));
        else if (draggingSlider == 2) kernel.setLayerPanning(settingsLayer, (int) Math.round(rel * 200));
    }

    private boolean clickImport(double mx, double my, int btn) {
        int x = (width - IMPORT_W) / 2, y = (height - IMPORT_H) / 2;
        if (inRect(x + IMPORT_W - 68, y + IMPORT_H - 24, 58, 18, mx, my) || !inRect(x, y, IMPORT_W, IMPORT_H, mx, my)) {
            importOpen = false;
            return true;
        }
        int rowY = y + 20;
        int listH = IMPORT_ROWS * ROW_H;
        int sbX = x + IMPORT_W - NbsScrollBar.THICK - 2;
        if (importFiles.size() > IMPORT_ROWS && inRect(sbX, rowY, NbsScrollBar.THICK, listH, mx, my)) {
            int contentH = importFiles.size() * ROW_H;
            int[] t = NbsScrollBar.thumbRect(sbX, rowY, NbsScrollBar.THICK, listH, contentH, listH, importScroll * ROW_H);
            if (inRect(t[0], t[1], t[2], t[3], mx, my)) {
                draggingScrollBar = 5;
                importScroll = importScrollFromPx(NbsScrollBar.posFromThumbDrag(listH, t[3], (int) (my - rowY),
                    contentH, listH), importFiles.size());
            } else {
                importScroll = importScrollFromPx(NbsScrollBar.posFromTrackClick((int) (my - rowY), t[1] - rowY, t[3],
                    importScroll * ROW_H, contentH, listH), importFiles.size());
            }
            return true;
        }
        for (int i = 0; i < importFiles.size() && i < IMPORT_ROWS; i++) {
            int idx = i + importScroll;
            if (idx >= importFiles.size()) break;
            if (inRect(x + 8, rowY + i * ROW_H, IMPORT_W - 16, ROW_H, mx, my)) {
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
            LOGGER.warn("Failed to list nbs files: {}", e.getMessage());
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
            kernel.replaceSong(parsed);     // 导入可撤销（plan：导入 .nbs + 撤销）
            afterSongSwap();
            syncSongToServer();
            feedback(I18n.get("gui.create_schematic_compute.nbs_imported"));
        } catch (Exception e) {
            feedback("§c" + I18n.get("gui.create_schematic_compute.nbs_import_failed"));
        }
    }

    /** 新建曲目：工作曲目整体换成空白曲（整曲快照撤销，与导入同口径），上传服务端。
     *  New song: swap the working song for a blank one (whole-song snapshot undo, same
     *  contract as import), then upload to the server. */
    private void doNewSong() {
        kernel.replaceSong(new NbsSong());
        afterSongSwap();
        syncSongToServer();
        feedback(I18n.get("gui.create_schematic_compute.nbs_new_song"));
    }

    /** 换曲收尾（导入/新建共用）：停试听、草稿框让位、钳位层状态。
     *  Song-swap tail (shared by import & new): stop the preview, yield the edit-box
     *  drafts, clamp the layer state to the new shape. */
    private void afterSongSwap() {
        preview.stop();
        preview.seek(0);
        CscAudioEngine.cancelListenerFuture();
        // 输入框草稿让位于换入内容：失焦不提交（提交会用旧草稿覆盖新曲的速度/层名）
        // Edit-box drafts yield to the swapped-in song: blur without committing (a commit
        // would overwrite the new tempo / layer name with the stale draft).
        tempoBox.setFocused(false);
        layerNameBox.setFocused(false);
        // 换曲后钳位选中层与层滚动（层数可增可减）/ clamp layer state to the new shape
        currentLayer = Math.min(Math.max(0, currentLayer), kernel.song().layers.size() - 1);
        layerScroll = Math.min(Math.max(0, layerScroll), Math.max(0, kernel.song().layers.size() - 1));
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
            LOGGER.warn("NBS export failed: {}", e.toString());
            feedback("§c" + I18n.get("gui.create_schematic_compute.nbs_export_failed"));
        }
    }

    // ══════════════ 其余输入 / remaining input ══════════════

    @Override
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) {
        if (draggingSlider != 0) {
            applySlider(mx);
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
        if (draggingScrollBar == 3) {
            int contentTop = (height - 132) / 2 + 26;
            int sbX = (width - 180) / 2 + 180 - NbsScrollBar.THICK - 2;
            int[] t = NbsScrollBar.thumbRect(sbX, contentTop, NbsScrollBar.THICK, SETTINGS_VIEW_H,
                SETTINGS_ROWS * SETTINGS_ROW_H, SETTINGS_VIEW_H, settingsScroll);
            settingsScroll = NbsScrollBar.posFromThumbDrag(SETTINGS_VIEW_H, t[3], (int) (my - contentTop),
                SETTINGS_ROWS * SETTINGS_ROW_H, SETTINGS_VIEW_H);
            return true;
        }
        if (draggingScrollBar == 4) {
            int contentTop = (height - OVERVIEW_H) / 2 + 26;
            int sbX = (width - OVERVIEW_W) / 2 + OVERVIEW_W - NbsScrollBar.THICK - 2;
            int contentH = kernel.song().layers.size() * OVERVIEW_ROW_H;
            int[] t = NbsScrollBar.thumbRect(sbX, contentTop, NbsScrollBar.THICK, OVERVIEW_VIEW_H,
                contentH, OVERVIEW_VIEW_H, overviewScroll);
            overviewScroll = clampOverviewScroll(NbsScrollBar.posFromThumbDrag(OVERVIEW_VIEW_H, t[3],
                (int) (my - contentTop), contentH, OVERVIEW_VIEW_H), kernel.song().layers.size());
            return true;
        }
        if (draggingScrollBar == 5) {
            int rowY = (height - IMPORT_H) / 2 + 20, listH = IMPORT_ROWS * ROW_H;
            int sbX = (width - IMPORT_W) / 2 + IMPORT_W - NbsScrollBar.THICK - 2;
            int contentH = importFiles.size() * ROW_H;
            int[] t = NbsScrollBar.thumbRect(sbX, rowY, NbsScrollBar.THICK, listH, contentH, listH, importScroll * ROW_H);
            importScroll = importScrollFromPx(NbsScrollBar.posFromThumbDrag(listH, t[3], (int) (my - rowY),
                contentH, listH), importFiles.size());
            return true;
        }
        if (gesture != GESTURE_NONE) {
            marqX = mx;
            marqY = my;
            // 拖过阈值定意图：音符上按住 = 搬移中；其余 = 框选（层列落点 / 单格加减选让位，
            // 单击语义松手才判定）。
            // Crossing the threshold fixes the intent: a note press becomes a move; the
            // rest become a marquee (lane place / cell toggle hand over — click semantics
            // resolve at release).
            if (gesture != GESTURE_MARQUEE && gesture != GESTURE_MOVING
                && (Math.abs(mx - pressX) > DRAG_THRESHOLD || Math.abs(my - pressY) > DRAG_THRESHOLD)) {
                gesture = (gesture == GESTURE_MOVE) ? GESTURE_MOVING : GESTURE_MARQUEE;
            }
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
        if (gesture != GESTURE_NONE) {
            commitGesture();
            return true;
        }
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
    public void mouseMoved(double mx, double my) {
        hoverX = mx;
        hoverY = my;
        super.mouseMoved(mx, my);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        lastWheelAt = System.currentTimeMillis();   // 滚动防误触窗口起点 / arm the scroll guard
        if (layerSettingsOpen) return true;         // 设置面板吞滚轮 / the settings panel swallows the wheel
        if (fileMenuOpen) return true;              // 文件菜单吞滚轮 / the file menu swallows the wheel
        if (layerOverviewOpen) {                    // 总览滚轮翻行 / the overview scrolls by rows
            overviewScroll = clampOverviewScroll(overviewScroll + (sy > 0 ? -1 : 1) * OVERVIEW_ROW_H,
                kernel.song().layers.size());
            return true;
        }
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
        if (showGuide || exportOpen || setOpen) return true;
        // Alt+Ctrl + 悬停音符 + 滚轮 = 力度 ±5（Shift = ±15）（B3 悬停微调；细音高/声像走 Set 弹窗）
        // Alt+Ctrl + hover a note + wheel = velocity ±5 (Shift ±15) — the B3 micro-adjust
        // (fine pitch / panning go through the Set dialog).
        if (hasAltDown() && hasControlDown()) {
            NbsSong.Note n = view == View.ROLL ? noteAt(mx, my) : laneNoteAt(mx, my);
            if (n != null) {
                if (editLocked()) return true;
                int step = (sy > 0 ? 5 : -5) * (hasShiftDown() ? 3 : 1);
                int nv = Math.max(0, Math.min(100, n.velocity + step));
                kernel.setNoteField(n.tick, n.layer, NbsEditorKernel.FIELD_VELOCITY, nv);
                feedback(I18n.get("gui.create_schematic_compute.nbs_set_velocity") + " " + nv);
                return true;
            }
        }
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
            if (fileMenuOpen) { fileMenuOpen = false; return true; }
            if (importOpen) { importOpen = false; return true; }
            if (exportOpen) { exportOpen = false; return true; }
            if (setOpen) { setOpen = false; setValueBox.setFocused(false); return true; }
            if (nameEditLayer >= 0) { cancelNameEdit(); return true; }
            if (songNameEdit) { cancelSongNameEdit(); return true; }
            if (layerSettingsOpen) { layerSettingsOpen = false; return true; }
            if (layerOverviewOpen) { layerOverviewOpen = false; return true; }
            if (tempoBox != null && tempoBox.isFocused()) { commitTempo(); return true; }
            if (layerNameBox != null && layerNameBox.isFocused()) { cancelNameEdit(); return true; }
            if (exportNameBox != null && exportNameBox.isFocused()) { exportNameBox.setFocused(false); return true; }
            onClose();
            return true;
        }
        if (key == 257 || key == 335) {         // Enter（提交输入框）
            if (setOpen) { applySet(); return true; }
            if (tempoBox != null && tempoBox.isFocused()) { commitTempo(); return true; }
            if (layerNameBox != null && layerNameBox.isFocused()) { commitLayerName(); return true; }
            if (songNameEdit && songNameBox != null && songNameBox.isFocused()) { commitSongName(); return true; }
            if (exportOpen && exportNameBox != null && exportNameBox.isFocused()) {
                doExport(exportNameBox.getValue().trim());
                exportOpen = false;
                return true;
            }
        }
        // 输入框聚焦时把按键交给它 / focused edit boxes eat keys
        if ((tempoBox != null && tempoBox.isFocused()) || (layerNameBox != null && layerNameBox.isFocused())
            || (exportOpen && exportNameBox != null && exportNameBox.isFocused())
            || (songNameEdit && songNameBox != null && songNameBox.isFocused())
            || (setOpen && setValueBox != null && setValueBox.isFocused())) {
            if (setOpen && setValueBox != null && setValueBox.isFocused()) return setValueBox.keyPressed(key, sc, mod);
            if (exportOpen && exportNameBox != null && exportNameBox.isFocused()) return exportNameBox.keyPressed(key, sc, mod);
            if (songNameEdit && songNameBox != null && songNameBox.isFocused()) return songNameBox.keyPressed(key, sc, mod);
            if (tempoBox != null && tempoBox.isFocused()) return tempoBox.keyPressed(key, sc, mod);
            return layerNameBox.keyPressed(key, sc, mod);
        }
        // 命令键位（作用域 NBS）：统一序列引擎——绑定可在设置里改；撤销/重做与图编辑器
        // 同名不同作用域（同一键在三个屏幕各绑各的）。
        // Command bindings (NBS scope): the unified sequence engine — rebindable in
        // settings; undo/redo share names with the graph editor across scopes (the same
        // key binds in each screen).
        int seqMods = (hasControlDown() ? io.github.y15173334444.create_schematic_compute.client.editor.EditorKeys.MOD_CTRL : 0)
            | (hasShiftDown() ? io.github.y15173334444.create_schematic_compute.client.editor.EditorKeys.MOD_SHIFT : 0)
            | (hasAltDown() ? io.github.y15173334444.create_schematic_compute.client.editor.EditorKeys.MOD_ALT : 0);
        var hit = io.github.y15173334444.create_schematic_compute.client.editor.EditorKeys
            .feedKey(key, seqMods, System.currentTimeMillis(),
                io.github.y15173334444.create_schematic_compute.client.editor.EditorKeys.Scope.NBS);
        if (hit != null && dispatchNbsAction(hit)) return true;
        if (io.github.y15173334444.create_schematic_compute.client.editor.EditorKeys.bufferActive()
            || io.github.y15173334444.create_schematic_compute.client.editor.EditorKeys.mixedBufferActive()) {
            return true;    // 序列前缀等待（吞键）/ prefix waiting (consume the key)
        }
        // 旧别名保留（DELETE_WIRE 老组合保留先例）：Ctrl+Shift+Z = 重做
        // Legacy alias kept (the DELETE_WIRE precedent): Ctrl+Shift+Z = redo.
        if (hasControlDown() && hasShiftDown() && key == 90) {
            dispatchNbsAction(io.github.y15173334444.create_schematic_compute.client.editor.EditorKeys.Action.NBS_REDO);
            return true;
        }
        return super.keyPressed(key, sc, mod);
    }

    /** NBS 命令动作派发（键位绑定与面板按钮共用一条路径，行为不漂移）。
     *  NBS command-action dispatch (one path shared by key bindings and panel buttons). */
    private boolean dispatchNbsAction(io.github.y15173334444.create_schematic_compute.client.editor.EditorKeys.Action a) {
        switch (a) {
            case NBS_PLAY_PAUSE -> { togglePlay(); return true; }
            case NBS_STOP -> {
                preview.stop(); preview.seek(0); CscAudioEngine.cancelListenerFuture();
                return true;
            }
            case NBS_UNDO -> {
                applyPendingInputs();
                if (kernel.performUndo()) feedback(I18n.get("gui.create_schematic_compute.nbs_undid"));
                return true;
            }
            case NBS_REDO -> {
                applyPendingInputs();
                if (kernel.performRedo()) feedback(I18n.get("gui.create_schematic_compute.nbs_redid"));
                return true;
            }
            case NBS_SAVE -> { applyPendingInputs(); syncSongToServer(); return true; }
            case NBS_IMPORT -> { openImportDialog(); return true; }
            case NBS_EXPORT -> { openExportDialog(); return true; }
            case NBS_VIEW_TOGGLE -> { view = (view == View.ROLL) ? View.LANE : View.ROLL; return true; }
            case NBS_LOOP -> {
                var s = kernel.song();
                kernel.setLoop(!s.loop, s.maxLoopCount, s.loopStartTick);
                return true;
            }
            case NBS_INSTRUMENT_PREV -> { currentInstrument = (currentInstrument + 15) % 16; return true; }
            case NBS_INSTRUMENT_NEXT -> { currentInstrument = (currentInstrument + 1) % 16; return true; }
            case NBS_OCTAVE_PREV -> { pianoStrip.shift(-12); return true; }
            case NBS_OCTAVE_NEXT -> { pianoStrip.shift(12); return true; }
            // 选择/剪贴板命令（批次三 B1）/ selection & clipboard commands (batch 3 B1)
            case NBS_BOX_SELECT -> { return true; }  // 按住型：手势处轮询物理键态 / hold action — polled at gesture time
            case NBS_COPY -> { doCopy(); return true; }
            case NBS_CUT -> { doCut(); return true; }
            case NBS_PASTE -> { doPaste(); return true; }
            case NBS_DELETE_SELECTED -> { doDeleteSelection(); return true; }
            case NBS_SELECT_ALL -> { selection.selectAll(kernel.song()); return true; }
            case NBS_SELECT_INVERT -> { selection.invert(kernel.song()); return true; }
            case NBS_SET -> { openSetDialog(); return true; }
            default -> { return false; }
        }
    }

    // ══════════════ 选区命令（批次三 B1）/ selection commands (batch 3 B1) ══════════════

    /** 复制选中 → 内部 + 系统剪贴板（社区压缩串，可与社区编辑器互贴）。 */
    private void doCopy() {
        NbsClipboard clip = kernel.copySelection(selection);
        if (clip == null) {
            feedback(I18n.get("gui.create_schematic_compute.nbs_sel_empty"));
            return;
        }
        setClipboards(clip);
        feedback(I18n.get("gui.create_schematic_compute.nbs_sel_copied", clip.entries().size()));
    }

    /** 剪切选中（快照撤销）→ 剪贴板；播放锁拦下。 / cut = fragment + delete (snapshot undo). */
    private void doCut() {
        if (editLocked()) return;
        NbsClipboard clip = kernel.cutSelection(selection);
        if (clip == null) {
            feedback(I18n.get("gui.create_schematic_compute.nbs_sel_empty"));
            return;
        }
        setClipboards(clip);
        selection.clear();
        feedback(I18n.get("gui.create_schematic_compute.nbs_sel_cut", clip.entries().size()));
    }

    /** 删除选中（快照撤销）；播放锁拦下。 / delete the selection (snapshot undo). */
    private void doDeleteSelection() {
        if (editLocked()) return;
        int n = kernel.deleteSelection(selection);
        selection.clear();
        if (n == 0) {
            feedback(I18n.get("gui.create_schematic_compute.nbs_sel_empty"));
            return;
        }
        feedback(I18n.get("gui.create_schematic_compute.nbs_sel_deleted", n));
    }

    /** 粘贴片段（快照撤销）：落格成为新选区（随后可整体搬/删）。 / paste (snapshot undo);
     *  the placed cells become the new selection (ready to move / delete as one). */
    private void doPaste() {
        if (editLocked()) return;
        NbsClipboard clip = loadClipboard();
        if (clip == null) {
            feedback(I18n.get("gui.create_schematic_compute.nbs_clipboard_empty"));
            return;
        }
        int[] anchor = pasteAnchor();
        NbsSelection placed = kernel.paste(clip, anchor[0], anchor[1]);
        if (placed.isEmpty()) {
            feedback(I18n.get("gui.create_schematic_compute.nbs_paste_nothing"));
            return;
        }
        selection = placed;
        feedback(I18n.get("gui.create_schematic_compute.nbs_sel_pasted", placed.size()));
    }

    /** 系统剪贴板优先（与社区编辑器互贴），外来文本回落内部剪贴板。
     *  System clipboard first (interop with the community editors); foreign text falls
     *  back to the internal clip. */
    private NbsClipboard loadClipboard() {
        if (minecraft == null) return clipboard;
        NbsClipboard sys = NbsClipboard.decode(minecraft.keyboardHandler.getClipboard());
        return sys != null ? sys : clipboard;
    }

    private void setClipboards(NbsClipboard clip) {
        clipboard = clip;
        if (minecraft != null) minecraft.keyboardHandler.setClipboard(clip.encode());
    }

    /** 粘贴锚点：鼠标所指格优先；不在网格上 = 播放头 tick × 当前层。
     *  Paste anchor: the hovered cell first; off-grid = the playhead tick × current layer. */
    private int[] pasteAnchor() {
        boolean overGrid = view == View.ROLL
            ? roll.hit(rollX(), rollY(), gridW(), gridH(), hoverX, hoverY) == NbsPianoRoll.Zone.GRID
            : hoverY < stripY()
                && laneGrid.hit(rollX(), rollY(), gridW(), gridH(), hoverX, hoverY) == NbsLaneGrid.Zone.GRID;
        if (overGrid) {
            int tick = (int) Math.floor(view == View.ROLL
                ? roll.tickAt(rollX(), hoverX) : laneGrid.tickAt(rollX(), hoverX));
            int layer = currentLayer;
            if (view == View.LANE) {
                int l = laneGrid.layerAt(rollY(), hoverY, layerScroll, layerRowH(), kernel.song().layers.size());
                if (l >= 0) layer = l;
            }
            return new int[]{Math.max(0, tick), layer};
        }
        return new int[]{Math.max(0, (int) Math.floor(preview.head)), currentLayer};
    }

    @Override
    public boolean charTyped(char ch, int mod) {
        if (setOpen && setValueBox != null && setValueBox.isFocused()) return setValueBox.charTyped(ch, mod);
        if (tempoBox != null && tempoBox.isFocused()) return tempoBox.charTyped(ch, mod);
        if (layerNameBox != null && layerNameBox.isFocused()) return layerNameBox.charTyped(ch, mod);
        if (songNameEdit && songNameBox != null && songNameBox.isFocused()) return songNameBox.charTyped(ch, mod);
        if (exportOpen && exportNameBox != null && exportNameBox.isFocused()) return exportNameBox.charTyped(ch, mod);
        // 层序器视图：打字 = 琴键（社区映射，选键 + 试听）；弹窗打开时不响应。
        // Lane view: typing plays the piano (community map — select + audition); silent
        // while a dialog is open.
        if (view == View.LANE && !showGuide && !importOpen && !exportOpen && !setOpen && !layerSettingsOpen) {
            int k = NbsPianoStrip.typedKey(ch);
            if (k >= 0) {
                toolKey = k;
                pianoStrip.ensureVisible(k);   // 窗口跟随选键（打字映射恒 33–57，窗外要拉回）
                audition(new NbsSong.Note(0, 0, currentInstrument, k, 100, 100, 0));
                return true;
            }
        }
        return super.charTyped(ch, mod);
    }
}
