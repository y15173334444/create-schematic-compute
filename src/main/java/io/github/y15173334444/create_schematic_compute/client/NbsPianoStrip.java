package io.github.y15173334444.create_schematic_compute.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 底部横排钢琴条（双样式·层序器视图的选键器）：社区布局的签名件——音高不是网格轴，
 * 而是用钢琴条点选（点击 = 选键 + 试听），配合层序器格子落点。八度窗用 ◀/▶ 或滚轮平移。
 * <p>Bottom piano strip (the pitch picker of the lane view): the signature of the community
 * layout — pitch is not a grid axis but is picked on the keyboard (click = select key +
 * audition) for lane-cell placement. The octave window shifts via ◀/▶ or the wheel.</p>
 *
 * <p>琴键映射与社区 Note Block Studio 同款（数字行+QWERTY 行+ZXCVG 行覆盖精确窗口 33–57）：
 * 打字即选键+试听。The typing map matches community Note Block Studio (number row + QWERTY
 * row + ZXCVG row covering the exact window 33–57): typing selects and auditions.</p>
 */
public final class NbsPianoStrip {

    /** 条高（白键高 + 上下留白）。 / strip height (white-key height plus padding). */
    public static final int STRIP_H = 52;
    /** 八度窗按钮/标签区宽。 / width reserved for the octave buttons + range label. */
    public static final int BTN_W = 44;
    /** 窗口内键数（含黑键）。 / keys visible in the window. */
    public static final int WINDOW_KEYS = 28;

    /** 社区琴键映射：字符（大小写不敏感）→ NBS 键 33..57（25 键无缺口）。 / community typing map. */
    private static final String TYPED_KEYS = "1Q2W3ER5T6YU8I9O0PZSXDCVG";

    // ── 视图状态 / view state ──
    private int startKey = 33;

    /** 当前窗口首键。 */
    public int startKey() { return startKey; }

    /** 八度窗平移（键数，可负；钳在 0..87−窗口内）。 / shift the octave window. */
    public void shift(int keys) {
        startKey = Math.max(0, Math.min(87 - WINDOW_KEYS + 1, startKey + keys));
    }

    /** 打字键 → NBS 键（33..57）；未映射 -1。 / typed char → NBS key; -1 when unmapped. */
    public static int typedKey(char c) {
        int idx = TYPED_KEYS.indexOf(Character.toUpperCase(c));
        return idx < 0 ? -1 : 33 + idx;
    }

    // ══════════════ 几何（单一真相源，渲染与命中共用）/ geometry (single source) ══════════════

    /**
     * 窗口键矩形表（相对键区坐标）：先白键按槽位、后黑键叠上层（命中测试必须黑键优先）。
     * 每行 {key, x, y, w, h}。<b>纯函数</b>。
     * Key rects relative to the key area: whites first (by slot), then blacks on top
     * (hit-testing must try blacks first). Each row is {key, x, y, w, h}. <b>Pure.</b>
     */
    static int[][] keyRects(int keyAreaW, int keyAreaH, int startKey, int windowKeys) {
        int whites = 0;
        for (int k = startKey; k < startKey + windowKeys && k <= 87; k++)
            if (!NbsPianoRoll.isBlackKey(k)) whites++;
        int ww = Math.max(4, keyAreaW / Math.max(1, whites));
        int bw = Math.max(3, (int) (ww * 0.62f));
        int bh = (int) (keyAreaH * 0.62f);
        int[][] out = new int[windowKeys][5];
        int n = 0, slot = 0;
        for (int k = startKey; k < startKey + windowKeys && k <= 87; k++) {
            if (NbsPianoRoll.isBlackKey(k)) continue;
            out[n++] = new int[]{k, slot * ww, 0, ww, keyAreaH};
            slot++;
        }
        int slotAt = 0;
        for (int k = startKey; k < startKey + windowKeys && k <= 87; k++) {
            if (NbsPianoRoll.isBlackKey(k)) {
                int x = slotAt * ww - bw / 2;         // 骑在左邻白键右缘 / straddles the left white's edge
                out[n++] = new int[]{k, Math.max(0, x), 0, bw, bh};
            } else {
                slotAt++;
            }
        }
        int[][] res = new int[n][5];
        System.arraycopy(out, 0, res, 0, n);
        return res;
    }

    /** 命中：黑键（上 62%）优先，其次白键；返回 NBS 键或 -1。 / hit: blacks first, then whites. */
    public int keyAt(int keyAreaW, int keyAreaH, double mx, double my, int startKey, int windowKeys) {
        int[][] rects = keyRects(keyAreaW, keyAreaH, startKey, windowKeys);
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < rects.length; i++) {
                boolean black = NbsPianoRoll.isBlackKey(rects[i][0]);
                if ((pass == 0) != black) continue;
                int[] r = rects[i];
                if (mx >= r[1] && mx < r[1] + r[3] && my >= r[2] && my < r[2] + r[4]) return r[0];
            }
        }
        return -1;
    }

    // ══════════════ 渲染 / rendering ══════════════

    /**
     * 画钢琴条（八度按钮 + 范围标签 + 白键/黑键 + 选中键高亮 + 发音窗口遮罩）。
     * @param toolKey 当前工具键（选中态）
     */
    public void render(GuiGraphics g, Font font, int x, int y, int w, int h, int toolKey) {
        g.fill(x, y, x + w, y + h, NbsPianoRoll.C_BG);
        g.fill(x, y, x + w, y + 1, NbsPianoRoll.C_GRID_BAR);

        // 八度按钮 + 范围标签 / octave buttons + range label
        drawOctaveButtons(g, font, x, y);
        int keyAreaX = x + BTN_W, keyAreaW = w - BTN_W;
        int keyAreaY = y + 2, keyAreaH = h - 4;
        int[][] rects = keyRects(keyAreaW, keyAreaH, startKey, WINDOW_KEYS);
        for (int pass = 0; pass < 2; pass++) {           // 白键先画、黑键叠上 / whites first, blacks on top
            for (int[] r : rects) {
                boolean black = NbsPianoRoll.isBlackKey(r[0]);
                if ((pass == 0) != black) continue;
                int k = r[0];
                int kx = keyAreaX + r[1], ky = keyAreaY + r[2];
                int color = black ? NbsPianoRoll.C_BLACK_KEY : NbsPianoRoll.C_WHITE_KEY;
                if (k == toolKey) color = 0xFF3A5A2A;    // 选中键 / selected key
                g.fill(kx, ky, kx + r[3], ky + r[4], color);
                g.renderOutline(kx, ky, r[3], r[4], k == toolKey ? 0xFF88FF88 : NbsPianoRoll.C_GRID_BAR);
                if (k < 33 || k > 57) {
                    g.fill(kx, ky, kx + r[3], ky + r[4], NbsPianoRoll.C_OUT_OF_WINDOW); // 发音窗口外 / outside 33–57
                }
                if (!black && r[3] >= 14 && k % 12 == 3) {
                    g.drawString(font, NbsPianoRoll.cName(k), kx + 2, ky + keyAreaH - 10, NbsPianoRoll.C_TEXT_DIM, false);
                }
            }
        }
    }

    private void drawOctaveButtons(GuiGraphics g, Font font, int x, int y) {
        g.fill(x + 2, y + 4, x + 20, y + 22, NbsPianoRoll.C_GRID_BEAT);
        g.renderOutline(x + 2, y + 4, 18, 18, NbsPianoRoll.C_GRID_BAR);
        g.drawString(font, "◀", x + 6, y + 9, NbsPianoRoll.C_TEXT, false);
        g.fill(x + 22, y + 4, x + 40, y + 22, NbsPianoRoll.C_GRID_BEAT);
        g.renderOutline(x + 22, y + 4, 18, 18, NbsPianoRoll.C_GRID_BAR);
        g.drawString(font, "▶", x + 26, y + 9, NbsPianoRoll.C_TEXT, false);
        String range = NbsPianoRoll.cName(startKey) + "–" + NbsPianoRoll.cName(Math.min(87, startKey + WINDOW_KEYS - 1));
        g.drawString(font, range, x + 4, y + 30, NbsPianoRoll.C_TEXT_DIM, false);
    }

    /** 八度按钮命中：-1 = ◀（低八度），+1 = ▶（高八度），0 = 无。 / octave-button hit. */
    public int octaveButtonAt(double mx, double my, int x, int y) {
        if (mx >= x + 2 && mx < x + 20 && my >= y + 4 && my < y + 22) return -1;
        if (mx >= x + 22 && mx < x + 40 && my >= y + 4 && my < y + 22) return 1;
        return 0;
    }
}
