package io.github.y15173334444.create_schematic_compute.client;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.blocks.GraphBlockEntity;
import io.github.y15173334444.create_schematic_compute.blocks.GraphEditor;
import io.github.y15173334444.create_schematic_compute.blocks.NodeRenderer;
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

    private int currentLayer = 0;
    private int currentInstrument = 0;
    private int layerScroll = 0;
    private int draggingSlider = 0;        // 0=无 1=音量 2=声像
    private boolean importOpen = false, exportOpen = false, showGuide = false;
    private List<Path> importFiles = List.of();
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
    @Override public GraphEditor getEditor() { return null; }
    @Override public void sendOp(GraphOp op) {
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
            List<NoteEvent> evs = preview.advance(kernel.song(), 0.05f,
                io.github.y15173334444.create_schematic_compute.graph.MusicTransport.PREROLL_SECONDS);
            for (NoteEvent e : evs) {
                io.github.y15173334444.create_schematic_compute.client.audio.CscAudioEngine.playAtListener(e);
            }
        }
    }

    @Override
    public void onClose() {
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

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        g.fill(0, 0, width, height, C_BG);
        renderTopBar(g, mx, my);
        renderLayerPanel(g, mx, my);
        roll.render(g, font, rollX(), rollY(), rollW(), rollH(),
            kernel.song(), currentLayer, preview.isPlaying() || preview.headTick() > 0 ? preview.head : -1f);
        renderTransportBar(g, mx, my);
        if (importOpen) renderImportDialog(g, mx, my);
        if (exportOpen) renderExportDialog(g, mx, my);
        if (showGuide) renderGuide(g);
    }

    private void renderTopBar(GuiGraphics g, int mx, int my) {
        g.fill(0, 0, width, TOP_H - 2, C_PANEL);
        g.fill(0, TOP_H - 2, width, TOP_H, C_BORDER);
        String title = "NBS · " + (kernel.song().songName.isEmpty() ? node.displayText : kernel.song().songName);
        g.drawString(font, title, 6, 10, C_TEXT, false);

        // 右侧按钮簇（从右往左）/ right-side button cluster
        int x = width - 4;
        x -= 40; button(g, x, 4, 38, 18, I18n.get("gui.create_schematic_compute.nbs_close"), mx, my, false);
        x -= 44; button(g, x, 4, 42, 18, I18n.get("gui.create_schematic_compute.nbs_save"), mx, my, false);
        x -= 44; button(g, x, 4, 42, 18, I18n.get("gui.create_schematic_compute.nbs_guide"), mx, my, showGuide);
        x -= 50; button(g, x, 4, 48, 18, I18n.get("gui.create_schematic_compute.nbs_export"), mx, my, exportOpen);
        x -= 50; button(g, x, 4, 48, 18, I18n.get("gui.create_schematic_compute.nbs_import"), mx, my, importOpen);
        x -= 40; button(g, x, 4, 38, 18, I18n.get("gui.create_schematic_compute.nbs_redo"), mx, my, false);
        x -= 40; button(g, x, 4, 38, 18, I18n.get("gui.create_schematic_compute.nbs_undo"), mx, my, false);

        if (System.currentTimeMillis() < feedbackUntil && !feedbackText.isEmpty()) {
            g.drawString(font, feedbackText, 6 + font.width(title) + 12, 10, C_TEXT_DIM, false);
        }
    }

    private void renderTransportBar(GuiGraphics g, int mx, int my) {
        int y = height - TRANSPORT_H;
        g.fill(0, y, width, height, C_PANEL);
        g.fill(0, y, width, y + 2, C_BORDER);

        int x = 4;
        button(g, x, y + 4, 48, 18, I18n.get(preview.isPlaying()
            ? "gui.create_schematic_compute.nbs_pause" : "gui.create_schematic_compute.nbs_play"), mx, my, preview.isPlaying());
        x += 52;
        button(g, x, y + 4, 38, 18, I18n.get("gui.create_schematic_compute.nbs_stop"), mx, my, false);
        x += 42;
        button(g, x, y + 4, 44, 18, I18n.get("gui.create_schematic_compute.nbs_loop"), mx, my, kernel.song().loop);
        x += 50;

        g.drawString(font, I18n.get("gui.create_schematic_compute.nbs_tempo"), x, y + 9, C_TEXT_DIM, false);
        x += font.width(I18n.get("gui.create_schematic_compute.nbs_tempo")) + 4;
        tempoBox.setX(x); tempoBox.setY(y + 6); tempoBox.render(g, mx, my, pt0());
        x += 46;

        float tps = Math.max(0.01f, kernel.song().tempo / 100f);
        int headT = (int) preview.head;
        int len = Math.max(kernel.song().songLength, kernel.song().computeLength());
        String pos = String.format("tick %d / %d  %d:%02d", headT, len,
            (int) (preview.head / tps) / 60, (int) (preview.head / tps) % 60);
        g.drawString(font, pos, x, y + 9, C_TEXT, false);

        // 右侧乐器选择 / instrument cycle (right side)
        int ix = width - 4 - 16;
        button(g, ix, y + 4, 16, 18, ">", mx, my, false);
        ix -= 84;
        String instName = currentInstrument < 16
            ? I18n.get("gui.create_schematic_compute.nbs_inst_" + INSTRUMENT_KEYS[currentInstrument])
            : "#" + currentInstrument;
        int iw = font.width(instName);
        g.fill(ix - 4, y + 4, ix + 80, y + 22, C_BTN);
        g.renderOutline(ix - 4, y + 4, 84, 18, C_BORDER);
        g.drawString(font, instName, ix - 4 + (80 - iw) / 2, y + 9, C_TEXT, false);
        ix -= 20;
        button(g, ix, y + 4, 16, 18, "<", mx, my, false);
    }

    private float pt0() { return 0f; }

    private void renderLayerPanel(GuiGraphics g, int mx, int my) {
        int bottom = height - TRANSPORT_H;
        g.fill(0, TOP_H, PANEL_W, bottom, C_PANEL);
        g.fill(PANEL_W - 2, TOP_H, PANEL_W, bottom, C_BORDER);
        g.drawString(font, I18n.get("gui.create_schematic_compute.nbs_layers"), 6, TOP_H + 4, C_TEXT, false);

        // 层列表 / layer list
        int listTop = TOP_H + 18;
        int listBottom = bottom - PROPS_H;
        var layers = kernel.song().layers;
        for (int i = 0; i < layers.size(); i++) {
            int y = listTop + (i - layerScroll) * ROW_H;
            if (y < listTop - ROW_H || y > listBottom - ROW_H) continue;
            boolean sel = i == currentLayer;
            int color = sel ? C_SEL : (inRect(4, y, PANEL_W - 12, ROW_H, mx, my) ? C_HOVER : C_PANEL);
            g.fill(4, y, PANEL_W - 8, y + ROW_H - 1, color);
            String name = layers.get(i).name.isEmpty() ? I18n.get("gui.create_schematic_compute.nbs_layer") + " " + (i + 1) : layers.get(i).name;
            g.drawString(font, name, 8, y + 3, layers.get(i).locked ? C_TEXT_DIM : C_TEXT, false);
            String vol = layers.get(i).volume + "%";
            g.drawString(font, vol, PANEL_W - 8 - font.width(vol) - 6, y + 3, C_TEXT_DIM, false);
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
        slider(g, volSliderX(), pY + 40, 96, layer == null ? 100 : layer.volume, 100, mx, my);
        g.drawString(font, I18n.get("gui.create_schematic_compute.nbs_panning"), 6, pY + 58, C_TEXT_DIM, false);
        slider(g, panSliderX(), pY + 58, 96, layer == null ? 100 : layer.panning, 200, mx, my);

        button(g, 6, pY + 80, 50, 18, I18n.get(layer != null && layer.locked
            ? "gui.create_schematic_compute.nbs_unlock" : "gui.create_schematic_compute.nbs_lock"), mx, my,
            layer != null && layer.locked);
        button(g, 60, pY + 80, 50, 18, I18n.get("gui.create_schematic_compute.nbs_add_layer"), mx, my, false);
        button(g, 114, pY + 80, 50, 18, I18n.get("gui.create_schematic_compute.nbs_del_layer"), mx, my, false);
    }

    private int volSliderX() { return 66; }
    private int panSliderX() { return 66; }

    private void slider(GuiGraphics g, int x, int y, int w, int value, int max, int mx, int my) {
        g.fill(x, y + 4, x + w, y + 8, C_BTN);
        int fill = (int) ((long) w * Math.max(0, Math.min(max, value)) / max);
        g.fill(x, y + 4, x + fill, y + 8, C_BTN_ON);
        g.renderOutline(x, y + 3, w + 1, 7, C_BORDER);
        String v = max == 200 ? (value - 100 >= 0 ? "+" : "") + (value - 100) : String.valueOf(value);
        g.drawString(font, v, x + w + 4, y + 2, C_TEXT_DIM, false);
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
        for (int i = 0; i < importFiles.size() && i < 11; i++) {
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
            // 右键落在卷帘上 = 擦除；其余右键忽略。擦除同样按笔划分组撤销。
            if (roll.mouseDown(rollX(), rollY(), rollW(), rollH(), mx, my, btn, kernel, currentLayer, currentInstrument)) {
                kernel.beginBatch();
            }
            return true;
        }

        // 顶栏 / top bar
        if (my < TOP_H) return clickTopBar(mx, my);
        // 传输条 / transport bar
        if (my >= height - TRANSPORT_H) return clickTransport(mx, my);
        // 层面板 / layer panel
        if (mx < PANEL_W) return clickLayerPanel(mx, my);
        // 卷帘（含刻度尺 seek）/ roll (ruler click = seek)
        if (roll.hit(rollX(), rollY(), rollW(), rollH(), mx, my) == NbsPianoRoll.Zone.RULER) {
            int t = (int) Math.floor(roll.tickAt(rollX(), mx));
            preview.seek(Math.max(0, t));
            return true;
        }
        if (roll.mouseDown(rollX(), rollY(), rollW(), rollH(), mx, my, btn, kernel, currentLayer, currentInstrument)) {
            kernel.beginBatch();
            return true;
        }
        return super.mouseClicked(mx, my, btn);
    }

    private boolean clickTopBar(double mx, double my) {
        int x = width - 4;
        x -= 40; if (inRect(x, 4, 38, 18, mx, my)) { onClose(); return true; }
        x -= 44; if (inRect(x, 4, 42, 18, mx, my)) { applyPendingInputs(); syncSongToServer(); return true; }
        x -= 44; if (inRect(x, 4, 42, 18, mx, my)) { showGuide = !showGuide; return true; }
        x -= 50; if (inRect(x, 4, 48, 18, mx, my)) { openExportDialog(); return true; }
        x -= 50; if (inRect(x, 4, 48, 18, mx, my)) { openImportDialog(); return true; }
        x -= 40; if (inRect(x, 4, 38, 18, mx, my)) {
            applyPendingInputs();
            if (kernel.performRedo()) feedback(I18n.get("gui.create_schematic_compute.nbs_redid"));
            return true;
        }
        x -= 40; if (inRect(x, 4, 38, 18, mx, my)) {
            applyPendingInputs();
            if (kernel.performUndo()) feedback(I18n.get("gui.create_schematic_compute.nbs_undid"));
            return true;
        }
        return true;
    }

    private boolean clickTransport(double mx, double my) {
        int y = height - TRANSPORT_H;
        int x = 4;
        if (inRect(x, y + 4, 48, 18, mx, my)) { togglePlay(); return true; }
        x += 52;
        if (inRect(x, y + 4, 38, 18, mx, my)) { preview.stop(); preview.seek(0); return true; }
        x += 42;
        if (inRect(x, y + 4, 44, 18, mx, my)) {
            var s = kernel.song();
            kernel.setLoop(!s.loop, s.maxLoopCount, s.loopStartTick);
            return true;
        }
        x += 50;
        x += font.width(I18n.get("gui.create_schematic_compute.nbs_tempo")) + 4;
        if (inRect(x, y + 6, 40, 14, mx, my)) {
            tempoBox.setFocused(true);
            return true;
        }
        // 乐器选择 / instrument cycle
        int ix = width - 4 - 16;
        if (inRect(ix, y + 4, 16, 18, mx, my)) {
            currentInstrument = (currentInstrument + 1) % 16;
            return true;
        }
        ix -= 84 + 20;
        if (inRect(ix, y + 4, 16, 18, mx, my)) {
            currentInstrument = (currentInstrument + 15) % 16;
            return true;
        }
        tempoBox.setFocused(false);
        return true;
    }

    private void togglePlay() {
        if (preview.isPlaying()) {
            preview.stop();
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

        // 列表行 / list rows
        for (int i = 0; i < layers.size(); i++) {
            int y = listTop + (i - layerScroll) * ROW_H;
            if (y < listTop || y > listBottom - ROW_H) continue;
            if (inRect(4, y, PANEL_W - 12, ROW_H, mx, my)) {
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
        if (inRect(volSliderX(), pY + 42, 96, 8, mx, my)) {
            draggingSlider = 1;
            applySlider(mx, pY);
            return true;
        }
        if (inRect(panSliderX(), pY + 60, 96, 8, mx, my)) {
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
        layerNameBox.setFocused(false);
        return true;
    }

    private void applySlider(double mx, int pY) {
        if (currentLayer >= kernel.song().layers.size()) return;
        double rel = Math.max(0, Math.min(1, (mx - volSliderX()) / 96.0));
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
        for (int i = 0; i < importFiles.size() && i < 11; i++) {
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
        return roll.mouseDrag(rollX(), rollY(), mx, my, kernel, currentLayer, currentInstrument);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int btn) {
        draggingSlider = 0;
        if (roll.isPainting()) {
            kernel.endBatch();
            roll.mouseUp();
        }
        return super.mouseReleased(mx, my, btn);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (mx < PANEL_W && my > TOP_H && my < height - TRANSPORT_H) {
            layerScroll = Math.max(0, layerScroll + (sy > 0 ? -1 : 1));
            return true;
        }
        if (showGuide || importOpen || exportOpen) return true;
        if (roll.hit(rollX(), rollY(), rollW(), rollH(), mx, my) != NbsPianoRoll.Zone.OUTSIDE) {
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
        return super.charTyped(ch, mod);
    }
}
