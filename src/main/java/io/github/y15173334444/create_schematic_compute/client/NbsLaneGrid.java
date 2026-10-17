package io.github.y15173334444.create_schematic_compute.client;

import io.github.y15173334444.create_schematic_compute.graph.NbsSong;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 层序器网格（双样式的社区视图）：tick 横轴 × <b>层行</b>纵轴，一格 = 一个 (tick, layer) 音符，
 * 音高是格子属性（由底部钢琴条选键、工具态写格子）——NBS 原厂/社区编辑器的原生布局模型。
 * 自 {@link NbsEditorScreen} 的视图路由接管，与卷帘共用同一 {@link NbsEditorKernel}（撤销收口不变）。
 * <p>Lane grid (the community view of the dual-style editor): tick horizontal × <b>layer
 * row</b> vertical, one cell = one (tick, layer) note, pitch is a <b>cell attribute</b>
 * (picked on the bottom piano strip, written from the tool state) — the native layout model
 * of the original/ community NBS editors. Driven by the screen's view routing, sharing the
 * same {@link NbsEditorKernel} as the roll (undo stays in the kernel).</p>
 *
 * <p><b>行对齐</b>：层行与左层面板的层列表逐行对齐（同 {@code rowsTop}、同行高、同滚动）——
 * 社区布局的签名（左面板行 = 网格行）。<b>Row alignment</b>: layer rows align 1:1 with the
 * left panel's layer list (same top, row height and scroll) — the signature of the
 * community layout (panel row = grid row).</p>
 *
 * <p><b>交互语义</b>（社区习惯）：左键单击空格放音符 / 点已有格整格覆盖为工具属性（不保留旧属性），
 * <b>左键拖动刻意不放音符</b>——社区左键拖 = 框选（随选择/剪贴板批次落地），涂抹会污染肌肉记忆；
 * 右键按住擦除（每格一次）。<b>Interaction</b> (community habits): LMB click places on an empty
 * cell / overwrites an occupied cell with the tool attributes (nothing carried over), <b>LMB
 * drag deliberately places nothing</b> — the community drag is box-select (landing with the
 * selection/clipboard batch); painting would poison the muscle memory. RMB hold erases (once
 * per cell per stroke). Locked layers are never edited.</p>
 */
public final class NbsLaneGrid {

    /** 顶部刻度尺高：18px 使层行起点 = 层面板列表起点（TOP_H + 18），达成逐行对齐。 */
    public static final int RULER_H = 18;

    private static final float MIN_PX_PER_TICK = 3f, MAX_PX_PER_TICK = 64f;

    // ── 视图状态 / view state ──
    private float pxPerTick = 10f;
    private float scrollTick = 0f;

    // ── 擦除笔划 / erase stroke ──
    private boolean erasing = false;
    private final Set<Long> strokeVisited = new HashSet<>();

    /** 落点/覆盖时的试听回调（null = 不试听）。 / audition callback on place/overwrite (null = silent). */
    private final Consumer<NbsSong.Note> audition;

    public NbsLaneGrid() { this(null); }

    public NbsLaneGrid(Consumer<NbsSong.Note> audition) { this.audition = audition; }

    /** 是否在擦除笔划中（屏幕据此收批量组）。 / mid erase-stroke (the screen ends the batch on release). */
    public boolean isErasing() { return erasing; }

    // ══════════════ 渲染 / rendering ══════════════

    /**
     * 画层序器网格（刻度尺 + 层行 + 格子 + 播放头）。
     * @param layerScroll 层滚动（与左层面板共用）
     * @param rowH        层行高（与左层面板共用）
     */
    public void render(GuiGraphics g, Font font, int areaX, int areaY, int areaW, int areaH,
                       NbsSong song, int currentLayer, int layerScroll, int rowH, float playheadTick) {
        int rowsTop = areaY + RULER_H;
        int rowsH = Math.max(1, areaH - RULER_H);
        int right = areaX + areaW, bottom = rowsTop + rowsH;

        g.fill(areaX, areaY, right, bottom, NbsPianoRoll.C_BG);

        float visibleTicks = areaW / pxPerTick;
        int firstTick = (int) Math.floor(scrollTick);
        int lastTick = (int) Math.ceil(scrollTick + visibleTicks);
        int beatEvery = 4;
        int barEvery = Math.max(1, beatEvery * Math.max(1, song.timeSignature));

        // ── 层行底色 / row bands ──
        int visibleRows = rowsH / rowH + 2;
        for (int i = 0; i < visibleRows; i++) {
            int layer = layerScroll + i;
            if (layer < 0 || layer >= song.layers.size()) continue;
            int y0 = rowsTop + i * rowH, y1 = Math.min(bottom, y0 + rowH);
            if (y0 >= bottom) break;
            if (layer == currentLayer) g.fill(areaX, y0, right, y1, 0x14FFFFFF);   // 当前行高亮 / current row
            if (song.layers.get(layer).locked) g.fill(areaX, y0, right, y1, 0x22000000); // 锁定压暗 / locked dim
            g.fill(areaX, y1 - 1, right, y1, NbsPianoRoll.C_GRID_TICK);             // 行分隔 / row separator
        }

        // ── 刻度线 / tick lines ──
        for (int t = firstTick; t <= lastTick; t++) {
            if (t < 0) continue;
            int x = areaX + Math.round((t - scrollTick) * pxPerTick);
            if (x < areaX || x > right) continue;
            int col = (t % barEvery == 0) ? NbsPianoRoll.C_GRID_BAR
                : (t % beatEvery == 0) ? NbsPianoRoll.C_GRID_BEAT : NbsPianoRoll.C_GRID_TICK;
            g.fill(x, rowsTop, x + 1, bottom, col);
        }

        // ── 格子（音符块）/ note cells ──
        for (int t = firstTick; t <= lastTick; t++) {
            if (t < 0) continue;
            List<NbsSong.Note> notes = song.notesAtTick(t);
            if (notes.isEmpty()) continue;
            int x0 = areaX + Math.round((t - scrollTick) * pxPerTick);
            int x1 = areaX + Math.round((t + 1 - scrollTick) * pxPerTick);
            for (NbsSong.Note n : notes) {
                int i = n.layer - layerScroll;
                if (i < 0 || i >= visibleRows) continue;
                int y0 = rowsTop + i * rowH, y1 = Math.min(bottom, y0 + rowH);
                int xL = Math.max(areaX, x0 + 1), xR = Math.min(right, x1 - 1);
                int yT = y0 + 1, yB = y1 - 1;
                if (xR <= xL || yB <= yT) continue;
                int color = NbsPianoRoll.INSTRUMENT_COLORS[Math.floorMod(n.instrument, NbsPianoRoll.INSTRUMENT_COLORS.length)];
                if (n.layer < song.layers.size() && song.layers.get(n.layer).locked)
                    color = NbsPianoRoll.dim(color, 0.6f);
                g.fill(xL, yT, xR, yB, color);
            }
        }

        // ── 播放头 / playhead ──
        if (playheadTick >= 0) {
            int x = areaX + Math.round((playheadTick - scrollTick) * pxPerTick);
            if (x >= areaX && x <= right) g.fill(x, rowsTop, x + 1, bottom, NbsPianoRoll.C_PLAYHEAD);
        }

        // ── 刻度尺 / ruler ──
        g.fill(areaX, areaY, right, rowsTop, NbsPianoRoll.C_RULER);
        g.fill(areaX, rowsTop - 1, right, rowsTop, NbsPianoRoll.C_GRID_BAR);
        int labelEvery = Math.max(4, Math.round(48f / pxPerTick));
        for (int t = (firstTick / labelEvery) * labelEvery; t <= lastTick; t += labelEvery) {
            if (t < 0) continue;
            int x = areaX + Math.round((t - scrollTick) * pxPerTick);
            if (x >= areaX && x <= right - 12) {
                g.drawString(font, Integer.toString(t), x + 2, areaY + 5, NbsPianoRoll.C_TEXT, false);
            }
        }
    }

    // ══════════════ 交互 / interaction ══════════════

    /** 命中区。 / hit zone. */
    public enum Zone { GRID, RULER, OUTSIDE }

    public Zone hit(int areaX, int areaY, int areaW, int areaH, double mx, double my) {
        if (mx < areaX || mx >= areaX + areaW || my < areaY || my >= areaY + areaH) return Zone.OUTSIDE;
        if (my < areaY + RULER_H) return Zone.RULER;
        return Zone.GRID;
    }

    /** 鼠标 x → tick（float，向下取整为格）。 / mouse x → tick. */
    public float tickAt(int areaX, double mx) {
        return scrollTick + (float) (mx - areaX) / pxPerTick;
    }

    /** 鼠标 y → 层号（与左层面板同滚动同行高；越界 -1）。 / mouse y → layer index (-1 out of range). */
    public int layerAt(int areaY, double my, int layerScroll, int rowH, int layerCount) {
        int i = (int) Math.floor((my - (areaY + RULER_H)) / (double) rowH);
        int layer = layerScroll + i;
        return (layer < 0 || layer >= layerCount) ? -1 : layer;
    }

    /**
     * 网格按下：左 = 落点/覆盖（工具态写格子，落点即试听），右 = 擦除（开笔划去重）。
     * @return 是否消费
     */
    public boolean mouseDown(int areaX, int areaY, int areaW, int areaH, double mx, double my, int btn,
                             NbsEditorKernel kernel, int toolInstrument, int toolKey, int layerScroll, int rowH) {
        if (hit(areaX, areaY, areaW, areaH, mx, my) != Zone.GRID) return false;
        int tick = (int) Math.floor(tickAt(areaX, mx));
        int layer = layerAt(areaY, my, layerScroll, rowH, kernel.song().layers.size());
        if (layer < 0 || tick < 0) return true;
        if (btn == 0) {
            // 左键单击 = 落点/覆盖；左键拖动留白（社区 = 框选，随选择批次落地）
            kernel.placeNote(tick, layer, toolInstrument, toolKey, 100, 100, 0);
            if (audition != null) audition.accept(new NbsSong.Note(tick, layer, toolInstrument, toolKey, 100, 100, 0));
            return true;
        }
        if (btn == 1) {
            erasing = true;
            strokeVisited.clear();
            if (strokeVisited.add(pack(tick, layer))) kernel.eraseNote(tick, layer);
            return true;
        }
        return false;
    }

    /** 右键拖动擦除（每格一次）。左键拖动刻意无操作。 / RMB drag erases; LMB drag does nothing by design. */
    public boolean mouseDrag(int areaX, int areaY, int areaW, int areaH, double mx, double my,
                             NbsEditorKernel kernel, int layerScroll, int rowH) {
        if (!erasing) return false;
        if (hit(areaX, areaY, areaW, areaH, mx, my) != Zone.GRID) return true;
        int tick = (int) Math.floor(tickAt(areaX, mx));
        int layer = layerAt(areaY, my, layerScroll, rowH, kernel.song().layers.size());
        if (layer < 0 || tick < 0) return true;
        if (strokeVisited.add(pack(tick, layer))) kernel.eraseNote(tick, layer);
        return true;
    }

    /** 松手结束擦除笔划。 / end the erase stroke. */
    public void mouseUp() {
        erasing = false;
        strokeVisited.clear();
    }

    /** 滚轮：横滚时间轴；Ctrl = 时间缩放（对准鼠标）。 / wheel scrolls time; Ctrl zooms at the cursor. */
    public boolean mouseScroll(boolean ctrl, double sy) {
        if (ctrl) {
            pxPerTick = clamp(pxPerTick * (sy > 0 ? 1.15f : 1 / 1.15f), MIN_PX_PER_TICK, MAX_PX_PER_TICK);
        } else {
            scrollTick = Math.max(0f, scrollTick - (float) sy * Math.max(1f, 24f / pxPerTick) * 2f);
        }
        return true;
    }

    private static long pack(int tick, int layer) {
        return (((long) tick) << 32) | (layer & 0xFFFFFFFFL);
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
