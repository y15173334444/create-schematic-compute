package io.github.y15173334444.create_schematic_compute.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 双样式·层序器视图回归：钢琴条几何（白键槽位/黑键骑缝/黑键命中优先）、打字琴键映射
 * （社区同款、恰好覆盖精确窗口 33–57）、八度窗钳位、层行映射（与左层面板同滚动同行高）、
 * 网格 tick 映射。
 * Lane-view regressions: piano-strip geometry (white slots, black-key straddling, blacks hit
 * first), the community typing map (exactly the 33–57 window), octave-window clamping, the
 * row→layer mapping (shared scroll/row-height with the panel) and the grid tick mapping.
 */
class NbsLaneViewTest {

    // ══════════════ 钢琴条几何 / piano-strip geometry ══════════════

    @Test
    @DisplayName("key rects: whites in slots first, blacks straddle on top / 白键按槽、黑键骑缝叠上")
    void testKeyRectsLayout() {
        int[][] r = NbsPianoStrip.keyRects(240, 44, 33, NbsPianoStrip.WINDOW_KEYS);
        // 白键全部在前且按 x 升序 / whites first, x ascending
        int prevX = -1;
        boolean seenBlack = false;
        for (int[] k : r) {
            boolean black = NbsPianoRoll.isBlackKey(k[0]);
            if (!black) {
                assertFalse(seenBlack, "all whites precede all blacks");
                assertTrue(k[1] > prevX, "white slots advance");
                assertEquals(44, k[4], "white keys span the full height");
                prevX = k[1];
            } else {
                seenBlack = true;
                assertTrue(k[4] < 44, "black keys are shorter than whites");
            }
        }
        assertTrue(seenBlack, "the window holds black keys");
    }

    @Test
    @DisplayName("black keys win the hit where they overlap whites / 重叠处黑键命中优先")
    void testKeyAtBlacksFirst() {
        int w = 240, h = 44;
        int[][] r = NbsPianoStrip.keyRects(w, h, 33, NbsPianoStrip.WINDOW_KEYS);
        // 找第一个黑键，取其中心点：白键槽位同样覆盖该 x，但必须命中黑键
        // Take the first black key's centre: a white slot covers the same x, yet the black must win.
        int[] black = null;
        for (int[] k : r) if (NbsPianoRoll.isBlackKey(k[0])) { black = k; break; }
        assertNotNull(black);
        var strip = new NbsPianoStrip();
        double cx = black[1] + black[3] / 2.0, cy = black[2] + black[4] / 2.0;
        assertEquals(black[0], strip.keyAt(w, h, cx, cy, 33, NbsPianoStrip.WINDOW_KEYS),
            "black key wins at its own centre");
        assertEquals(-1, strip.keyAt(w, h, -3, 2, 33, NbsPianoStrip.WINDOW_KEYS), "outside → -1");
    }

    @Test
    @DisplayName("octave window clamps inside 0..87 / 八度窗钳在键域内")
    void testOctaveWindowClamps() {
        var strip = new NbsPianoStrip();
        assertEquals(33, strip.startKey());
        strip.shift(-120);
        assertEquals(0, strip.startKey());
        strip.shift(1000);
        assertEquals(87 - NbsPianoStrip.WINDOW_KEYS + 1, strip.startKey());
    }

    // ══════════════ 打字琴键映射 / typing map ══════════════

    @Test
    @DisplayName("typing map covers keys 33..57 exactly, community layout / 社区映射恰好覆盖 33–57")
    void testTypingMap() {
        assertEquals(33, NbsPianoStrip.typedKey('1'));
        assertEquals(-1, NbsPianoStrip.typedKey('!'), "only the mapped chars fire");
        assertEquals(45, NbsPianoStrip.typedKey('8'), "key 45 = '8' (the NBS base key)");
        assertEquals(51, NbsPianoStrip.typedKey('z'), "case-insensitive");
        assertEquals(57, NbsPianoStrip.typedKey('G'));
        assertEquals(-1, NbsPianoStrip.typedKey('a'), "unmapped chars are silent");
        assertEquals(-1, NbsPianoStrip.typedKey(' '));
        // 映射连续覆盖 33..57（25 键）/ the 25 entries cover 33..57 with no gaps
        for (int k = 33; k <= 57; k++) {
            boolean found = false;
            for (char c = 32; c < 127; c++) if (NbsPianoStrip.typedKey(c) == k) { found = true; break; }
            assertTrue(found, "key " + k + " reachable from the keyboard");
        }
    }

    // ══════════════ 层行映射 / row→layer mapping ══════════════

    @Test
    @DisplayName("grid rows map to layers with the shared scroll / 网格行按共享滚动映射层号")
    void testLayerRowAt() {
        var grid = new NbsLaneGrid();
        int areaY = 28, rowH = 20;
        // 行 0 顶 = areaY + RULER_H（与层面板 listTop 对齐的约定）
        assertEquals(0, grid.layerAt(areaY, areaY + NbsLaneGrid.RULER_H, 0, rowH, 5));
        assertEquals(2, grid.layerAt(areaY, areaY + NbsLaneGrid.RULER_H + 2 * rowH + 5, 0, rowH, 5));
        assertEquals(3, grid.layerAt(areaY, areaY + NbsLaneGrid.RULER_H, 3, rowH, 5), "scroll offsets rows");
        assertEquals(-1, grid.layerAt(areaY, areaY + NbsLaneGrid.RULER_H - 1, 0, rowH, 5), "ruler band → -1");
        assertEquals(-1, grid.layerAt(areaY, areaY + NbsLaneGrid.RULER_H + 9 * rowH, 0, rowH, 5), "past the last layer → -1");
    }

    @Test
    @DisplayName("tick mapping is linear in pxPerTick / tick 映射随像素每 tick 线性")
    void testTickAt() {
        var grid = new NbsLaneGrid();
        assertEquals(1.0f, grid.tickAt(0, 10), 1e-4, "default pxPerTick=10");
        assertEquals(2.5f, grid.tickAt(50, 75), 1e-4, "origin offsets");
    }
}
