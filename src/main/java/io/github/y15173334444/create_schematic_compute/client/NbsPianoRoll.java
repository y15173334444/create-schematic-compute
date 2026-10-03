package io.github.y15173334444.create_schematic_compute.client;

import io.github.y15173334444.create_schematic_compute.blocks.NodeRenderer;
import io.github.y15173334444.create_schematic_compute.graph.NbsSong;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Font;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 钢琴卷帘（tick 横轴 × 键纵轴）：网格/音符/播放头渲染 + 点放/擦除/拖拽涂抹交互。
 * 自 {@link NbsEditorScreen} 拆分（plan §3.2 三件之二）。视图状态（缩放/滚动/笔划去重集）住本类；
 * 编辑动作一律进 {@link NbsEditorKernel}（撤销由内核收口）。
 * <p>Piano roll (tick horizontal × key vertical): grid / note / playhead rendering plus
 * place / erase / drag-paint interaction, split out of {@link NbsEditorScreen}
 * (plan §3.2, part 2 of 3). View state (zoom / scroll / stroke de-dup set) lives here;
 * every edit goes through {@link NbsEditorKernel} (undo is the kernel's job).</p>
 *
 * <p><b>坐标映射</b>：左侧键槽 + 顶部刻度尺内的网格区；键轴向上为高音（NBS 0–87 = A0..C8）。
 * 一格 = 一个 (tick, layer) 单元（NBS 层是单声部），格子画在该音符的键高上。
 * <b>Coordinates</b>: the grid sits inside a left key gutter and a top ruler; the key axis
 * points up (NBS 0–87 = A0..C8). One cell = one (tick, layer) slot (an NBS layer is
 * monophonic), drawn at the note's key height.</p>
 *
 * <p><b>拖拽语义</b>：左键拖 = 涂抹（同格不重复触发；同 tick 换键 = 音符跟随移动）；
 * 右键拖 = 擦除（每 tick 一次）。锁定层不响应任何落点。
 * <b>Drag semantics</b>: LMB drags paint (a cell fires once per stroke; changing key within
 * one tick moves the note along); RMB drags erase (once per tick). Locked layers ignore input.</p>
 */
public final class NbsPianoRoll {

    // ── 布局 / layout ──
    /** 左侧键槽宽（钢琴键着色 + C 标签）。 */
    public static final int GUTTER_W = 30;
    /** 顶部刻度尺高（tick 编号）。 */
    public static final int RULER_H = 12;

    // ── 缩放范围 / zoom bounds ──
    private static final float MIN_PX_PER_TICK = 3f, MAX_PX_PER_TICK = 64f;
    private static final float MIN_PX_PER_KEY = 3f, MAX_PX_PER_KEY = 24f;

    // ── 配色 / colours ──
    private static final int C_BG = 0xFF14120E;
    private static final int C_GUTTER = 0xFF24201A;
    private static final int C_RULER = 0xFF24201A;
    private static final int C_GRID_TICK = 0xFF2A2822;
    private static final int C_GRID_BEAT = 0xFF3A362C;
    private static final int C_GRID_BAR = 0xFF4A4636;
    private static final int C_BLACK_KEY = 0xFF201C16;
    private static final int C_WHITE_KEY = 0xFF2E2A22;
    private static final int C_TEXT = 0xFFBFB8A8;
    private static final int C_TEXT_DIM = 0xFF7A7466;
    private static final int C_PLAYHEAD = 0xFFE0B040;
    private static final int C_OUT_OF_WINDOW = 0x40101018;  // 发音窗口外的键行遮罩 / dim outside 33–57

    /** 16 原版乐器各一色（音符按乐器着色）。 / one colour per vanilla instrument. */
    private static final int[] INSTRUMENT_COLORS = {
        0xFFE06C5A, 0xFFE0A24C, 0xFFD9C84C, 0xFF9FC24C, 0xFF59C26A, 0xFF4CC2A8, 0xFF4CA8C2,
        0xFF5A7FE0, 0xFF8A5AE0, 0xFFC25AE0, 0xFFE05A9C, 0xFFB08060, 0xFF8A8A8A, 0xFF6AC2C2,
        0xFFC2906A, 0xFFB0B8C8
    };

    // ── 视图状态 / view state ──
    private float pxPerTick = 10f;
    private float pxPerKey = 9f;
    /** 视口左缘的 tick（float）。 */
    private float scrollTick = 0f;
    /** 视口顶缘的键（float；键轴向上）。 */
    private float viewTopKey = 60f;

    // ── 笔划状态 / stroke state ──
    private boolean painting = false, erasing = false;
    private final Set<Long> strokeVisited = new HashSet<>();

    /** 音符是否画在可见区。 */
    public boolean isPainting() { return painting || erasing; }

    // ══════════════ 渲染 / rendering ══════════════

    /**
     * 画整块卷帘（键槽 + 刻度尺 + 网格 + 音符 + 播放头）。
     * @param areaX 卷帘区左上角 / area origin
     * @param currentLayer 当前层（本层音符高亮，他层压暗）
     * @param playheadTick 播放头位置（&lt;0 不画）
     */
    public void render(GuiGraphics g, Font font, int areaX, int areaY, int areaW, int areaH,
                       NbsSong song, int currentLayer, float playheadTick) {
        int gx = areaX + GUTTER_W, gy = areaY + RULER_H;       // 网格原点 / grid origin
        int gw = Math.max(1, areaW - GUTTER_W), gh = Math.max(1, areaH - RULER_H);
        int right = areaX + areaW, bottom = areaY + areaH;

        g.fill(areaX, areaY, right, bottom, C_BG);

        // ── 键行底色 / key row bands ──
        int firstKey = (int) Math.floor(viewTopKey);
        float visibleKeys = gh / pxPerKey;
        int lastKey = (int) Math.ceil(viewTopKey - visibleKeys);
        for (int k = Math.min(firstKey, 87); k >= Math.max(lastKey, 0); k--) {
            int yTop = gy + Math.round((viewTopKey - (k + 1)) * pxPerKey);
            int yBot = gy + Math.round((viewTopKey - k) * pxPerKey);
            if (yBot <= gy || yTop >= gy + gh) continue;
            g.fill(gx, Math.max(gy, yTop), gx + gw, Math.min(gy + gh, yBot), isBlackKey(k) ? C_BLACK_KEY : C_WHITE_KEY);
            // 发音窗口（33–57）外压暗：诚实标注精确音域（eval §四.1）
            if (k < 33 || k > 57) {
                g.fill(gx, Math.max(gy, yTop), gx + gw, Math.min(gy + gh, yBot), C_OUT_OF_WINDOW);
            }
        }

        // ── 刻度线 / grid lines ──
        float visibleTicks = gw / pxPerTick;
        int firstTick = (int) Math.floor(scrollTick);
        int lastTick = (int) Math.ceil(scrollTick + visibleTicks);
        int beatEvery = 4;                                   // 视觉节拍线：4 tick / 拍（装饰性）
        int barEvery = Math.max(1, beatEvery * Math.max(1, song.timeSignature));
        for (int t = firstTick; t <= lastTick; t++) {
            if (t < 0) continue;
            int x = gx + Math.round((t - scrollTick) * pxPerTick);
            if (x < gx || x > gx + gw) continue;
            int col = (t % barEvery == 0) ? C_GRID_BAR : (t % beatEvery == 0) ? C_GRID_BEAT : C_GRID_TICK;
            g.fill(x, gy, x + 1, gy + gh, col);
        }
        // 横向：每键下缘细线（缩放够大才画）
        if (pxPerKey >= 5f) {
            for (int k = Math.min(firstKey, 87); k >= Math.max(lastKey, 0); k--) {
                int y = gy + Math.round((viewTopKey - k) * pxPerKey);
                if (y > gy && y < gy + gh) g.fill(gx, y, gx + gw, y + 1, C_GRID_TICK);
            }
        }

        // ── 音符 / notes ──
        for (int t = firstTick; t <= lastTick; t++) {
            if (t < 0) continue;
            List<NbsSong.Note> notes = song.notesAtTick(t);
            for (NbsSong.Note n : notes) {
                if (n.key < lastKey - 1 || n.key > firstKey + 1) continue;
                int x0 = gx + Math.round((t - scrollTick) * pxPerTick);
                int x1 = gx + Math.round((t + 1 - scrollTick) * pxPerTick);
                int yTop = gy + Math.round((viewTopKey - (n.key + 1)) * pxPerKey);
                int yBot = gy + Math.round((viewTopKey - n.key) * pxPerKey);
                int xL = Math.max(gx, x0 + 1), xR = Math.min(gx + gw, x1 - 1);
                int yT = Math.max(gy, yTop + 1), yB = Math.min(gy + gh, yBot - 1);
                if (xR <= xL || yB <= yT) continue;
                int color = INSTRUMENT_COLORS[Math.floorMod(n.instrument, INSTRUMENT_COLORS.length)];
                if (n.layer != currentLayer) color = dim(color, 0.45f);       // 他层压暗 / dim other layers
                else if (n.layer < song.layers.size() && song.layers.get(n.layer).locked) color = dim(color, 0.6f);
                g.fill(xL, yT, xR, yB, color);
            }
        }

        // ── 播放头 / playhead ──
        if (playheadTick >= 0) {
            int x = gx + Math.round((playheadTick - scrollTick) * pxPerTick);
            if (x >= gx && x <= gx + gw) g.fill(x, gy, x + 1, gy + gh, C_PLAYHEAD);
        }

        // ── 键槽 / key gutter ──
        g.fill(areaX, gy, gx, gy + gh, C_GUTTER);
        for (int k = Math.min(firstKey, 87); k >= Math.max(lastKey, 0); k--) {
            int yTop = gy + Math.round((viewTopKey - (k + 1)) * pxPerKey);
            int yBot = gy + Math.round((viewTopKey - k) * pxPerKey);
            if (yBot <= gy || yTop >= gy + gh) continue;
            int yT = Math.max(gy, yTop), yB = Math.min(gy + gh, yBot);
            g.fill(areaX, yT, areaX + GUTTER_W - 1, yB, isBlackKey(k) ? C_BLACK_KEY : C_WHITE_KEY);
            if (k % 12 == 3 && yB - yT >= 8) {               // 每个 C 标音名 / label every C
                g.drawString(font, cName(k), areaX + 2, yT + (yB - yT - 8) / 2, C_TEXT_DIM, false);
            }
        }
        g.fill(areaX + GUTTER_W - 1, gy, gx, gy + gh, C_GRID_BAR);

        // ── 刻度尺 / ruler ──
        g.fill(areaX, areaY, right, gy, C_RULER);
        g.fill(areaX, gy - 1, right, gy, C_GRID_BAR);
        int labelEvery = Math.max(4, Math.round(48f / pxPerTick));   // 标号间距随缩放
        for (int t = (firstTick / labelEvery) * labelEvery; t <= lastTick; t += labelEvery) {
            if (t < 0) continue;
            int x = gx + Math.round((t - scrollTick) * pxPerTick);
            if (x >= gx && x <= gx + gw - 12) {
                g.drawString(font, Integer.toString(t), x + 2, areaY + 2, C_TEXT, false);
            }
        }
    }

    // ══════════════ 交互 / interaction ══════════════

    /** 命中区。 / hit zone. */
    public enum Zone { GRID, RULER, GUTTER, OUTSIDE }

    public Zone hit(int areaX, int areaY, int areaW, int areaH, double mx, double my) {
        if (mx < areaX || mx >= areaX + areaW || my < areaY || my >= areaY + areaH) return Zone.OUTSIDE;
        if (mx < areaX + GUTTER_W) return Zone.GUTTER;
        if (my < areaY + RULER_H) return Zone.RULER;
        return Zone.GRID;
    }

    /** 鼠标 x → tick（网格区）。 / mouse x → tick. */
    public float tickAt(int areaX, double mx) {
        return scrollTick + (float) (mx - areaX - GUTTER_W) / pxPerTick;
    }

    /** 鼠标 y → 键（网格区，float 向下取整为格）。 / mouse y → key. */
    public float keyAt(int areaY, double my) {
        return viewTopKey - (float) (my - areaY - RULER_H) / pxPerKey;
    }

    /**
     * 网格区按下：左 = 点放（同键再点删），右 = 擦除；开启笔划去重。
     * @return 是否消费
     */
    public boolean mouseDown(int areaX, int areaY, int areaW, int areaH,
                             double mx, double my, int btn,
                             NbsEditorKernel kernel, int layer, int instrument) {
        if (hit(areaX, areaY, areaW, areaH, mx, my) != Zone.GRID) return false;
        if (btn == 0) {
            painting = true;
        } else if (btn == 1) {
            erasing = true;
        } else {
            return false;
        }
        strokeVisited.clear();
        applyAt(kernel, layer, instrument, (int) Math.floor(tickAt(areaX, mx)), (int) Math.floor(keyAt(areaY, my)));
        return true;
    }

    /** 拖拽涂抹/擦除。 / continue the paint / erase stroke. */
    public boolean mouseDrag(int areaX, int areaY, double mx, double my,
                             NbsEditorKernel kernel, int layer, int instrument) {
        if (!painting && !erasing) return false;
        int t = (int) Math.floor(tickAt(areaX, mx));
        int k = (int) Math.floor(keyAt(areaY, my));
        if (erasing) {
            // 每 tick 只擦一次（层是单声部）/ one erase per tick (the layer is monophonic)
            if (strokeVisited.add(pack(t, k))) eraseAt(kernel, layer, t);
        } else {
            applyAt(kernel, layer, instrument, t, k);
        }
        return true;
    }

    /** 松手结束笔划。 / end the stroke. */
    public void mouseUp() {
        painting = false;
        erasing = false;
        strokeVisited.clear();
    }

    /**
     * 滚轮：默认横滚时间轴；Ctrl = 时间轴缩放（对准鼠标）；Shift = 键轴缩放。
     * Wheel: timeline scroll by default; Ctrl = time zoom at the cursor; Shift = key zoom.
     */
    public boolean mouseScroll(boolean ctrl, boolean shift, double sy) {
        if (shift) {
            pxPerKey = clamp(pxPerKey * (sy > 0 ? 1.15f : 1 / 1.15f), MIN_PX_PER_KEY, MAX_PX_PER_KEY);
        } else if (ctrl) {
            pxPerTick = clamp(pxPerTick * (sy > 0 ? 1.15f : 1 / 1.15f), MIN_PX_PER_TICK, MAX_PX_PER_TICK);
        } else {
            scrollTick = Math.max(0f, scrollTick - (float) sy * Math.max(1f, 24f / pxPerTick) * 2f);
        }
        return true;
    }

    /** 键轴滚动（PageUp/Down 等）。 */
    public void scrollKeys(float delta) {
        viewTopKey = clamp(viewTopKey + delta, 2f, 92f);
    }

    /** 视图对准某 tick（如从头播放时）。 */
    public void ensureTickVisible(float tick, int areaW) {
        float visible = Math.max(1, areaW - GUTTER_W) / pxPerTick;
        if (tick < scrollTick) scrollTick = Math.max(0f, tick - visible * 0.1f);
        else if (tick > scrollTick + visible) scrollTick = Math.max(0f, tick - visible * 0.5f);
    }

    /** 视图复位（打开时对准曲首）。 */
    public void resetView() {
        scrollTick = 0f;
        viewTopKey = 60f;
        pxPerTick = 10f;
        pxPerKey = 9f;
    }

    // ══════════════ 内部 / internals ══════════════

    /** 一次落点：左键 toggle 语义（空放/同键删/换键移）。 / one cell hit: toggle semantics. */
    private void applyAt(NbsEditorKernel kernel, int layer, int instrument, int tick, int key) {
        if (tick < 0 || key < 0 || key > 87) return;
        if (!strokeVisited.add(pack(tick, key))) return;
        kernel.toggleNote(tick, layer, key, instrument);
    }

    private void eraseAt(NbsEditorKernel kernel, int layer, int tick) {
        if (tick < 0) return;
        kernel.eraseNote(tick, layer);
    }

    private static long pack(int tick, int key) {
        return (((long) tick) << 32) | (key & 0xFFFFFFFFL);
    }

    /** 黑键（A 起半音序号：1/4/6/8/11）。 */
    private static boolean isBlackKey(int k) {
        int pc = Math.floorMod(k, 12);
        return pc == 1 || pc == 4 || pc == 6 || pc == 8 || pc == 11;
    }

    /** NBS 键 0 = A0；每 12 键一个 C（k%12==3 → C）。 */
    private static String cName(int k) {
        return "C" + (k + 9) / 12;
    }

    private static int dim(int color, float f) {
        int a = (color >>> 24) & 0xFF, r = (color >>> 16) & 0xFF, gg = (color >>> 8) & 0xFF, b = color & 0xFF;
        return (a << 24) | ((int) (r * f) << 16) | ((int) (gg * f) << 8) | (int) (b * f);
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
