package io.github.y15173334444.create_schematic_compute.client;

import io.github.y15173334444.create_schematic_compute.graph.NbsSong;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link NbsPianoRoll} 网格交互契约（无 MC bootstrap）：右键 = 擦除、<b>永不放置</b>
 * （实测反馈回归：右键在空格上放出了音符——mouseDown 曾把右键走成点放路径）。
 * Roll interaction contracts: RMB erases and <b>never places</b> (field regression:
 * RMB on blank cells used to place notes — mouseDown routed the right button through
 * the place path).
 */
class NbsPianoRollTest {

    // 卷帘默认视图：pxPerTick=10、pxPerKey=9、scrollTick=0、viewTopKey=60；
    // 网格原点 (GUTTER_W=30, RULER_H=12)。取格 (tick=4, key=45) 的中心。
    // Roll defaults: grid origin (30, 12); the centre of cell (4, 45).
    private static final int AREA_W = 400, AREA_H = 300;
    private static final double MX = 75, MY = 142.5;

    private static NbsSong songWithNoteAt4() {
        NbsSong s = new NbsSong();
        s.putNote(4, 0, 0, 45, 100, 100, 0);
        return s;
    }

    @Test
    void rightPressOnBlankNeverPlaces() {
        NbsSong song = new NbsSong();
        NbsEditorKernel k = new NbsEditorKernel(song);
        NbsPianoRoll roll = new NbsPianoRoll();

        assertTrue(roll.mouseDown(0, 0, AREA_W, AREA_H, MX, MY, 1, k, 0, 0));
        assertEquals(0, song.noteCount(), "RMB on a blank cell places nothing");
        assertFalse(k.canUndo(), "and records nothing");
    }

    @Test
    void rightPressErasesTheTickNote() {
        NbsSong song = songWithNoteAt4();
        NbsEditorKernel k = new NbsEditorKernel(song);
        NbsPianoRoll roll = new NbsPianoRoll();

        // 同 tick 另一键高（空格）：擦除作用域 = 本层该 tick（层是单声部）
        // Blank key height in the same tick: erase scope = the layer's tick (monophonic).
        assertTrue(roll.mouseDown(0, 0, AREA_W, AREA_H, 75, 52.5, 1, k, 0, 0));
        assertNull(song.getNote(4, 0), "RMB erases the note at that tick");
        assertTrue(k.performUndo(), "the erase is undoable");
        assertNotNull(song.getNote(4, 0));

        // 直接点在音符上同样只擦不放 / pressing on the note itself only erases
        roll.mouseUp();
        roll.mouseDown(0, 0, AREA_W, AREA_H, MX, MY, 1, k, 0, 0);
        assertNull(song.getNote(4, 0));
        assertEquals(0, song.noteCount(), "still nothing placed");
    }

    @Test
    void leftPressStillPlacesAndRightDragsErase() {
        NbsSong song = new NbsSong();
        NbsEditorKernel k = new NbsEditorKernel(song);
        NbsPianoRoll roll = new NbsPianoRoll();

        // 左键 = 点放（原语义不变）/ LMB places (unchanged)
        assertTrue(roll.mouseDown(0, 0, AREA_W, AREA_H, MX, MY, 0, k, 0, 0));
        assertNotNull(song.getNote(4, 0), "LMB on blank places a note");
        assertTrue(roll.isPainting());
        roll.mouseUp();

        // 右键拖 = 擦除笔划（每 tick 一次）/ RMB drag erases once per tick
        assertTrue(roll.mouseDown(0, 0, AREA_W, AREA_H, MX, MY, 1, k, 0, 0));
        assertTrue(roll.isPainting(), "the erase stroke is armed");
        assertNull(song.getNote(4, 0));
    }

    @Test
    void keyRowsTileExactlyAcrossKeys() {
        // 面板键位列表与谱面行共用 keyRowTop/Bottom：行首尾严格相接、行高≈键高——
        // 卷帘模式「面板行与谱面行 1:1 对齐」由构造保证。
        // Shared key-row geometry: rows tile exactly and the height tracks pxPerKey —
        // roll-mode panel/score alignment is guaranteed by construction.
        float top = 60f, ppk = 9f;
        for (int k = 1; k < 87; k++) {
            assertEquals(NbsPianoRoll.keyRowTop(top, ppk, k - 1), NbsPianoRoll.keyRowBottom(top, ppk, k),
                "rows tile: bottom of key k equals top of key k-1");
            int h = NbsPianoRoll.keyRowBottom(top, ppk, k) - NbsPianoRoll.keyRowTop(top, ppk, k);
            assertTrue(h >= (int) ppk - 1 && h <= (int) ppk + 1, "row height tracks pxPerKey");
        }
    }

    @Test
    void secondStrokeInSameColumnKeepsFirstNoteAndStacks() {
        // 实测反馈回归（拖动放置）：同列画第二笔时，第一笔的音符被清除并跟到当前位置
        // （换键挪移）。现在第一笔原地不动，第二笔落到下一层成和弦。
        // Field regression (drag-place): the second stroke in the same column used to clear
        // the first note and pull it to the cursor. The first note now stays and the second
        // stroke stacks on the next layer as a chord.
        NbsSong song = new NbsSong();
        NbsEditorKernel k = new NbsEditorKernel(song);
        NbsPianoRoll roll = new NbsPianoRoll();

        roll.mouseDown(0, 0, AREA_W, AREA_H, MX, MY, 0, k, 0, 0);   // (tick 4, key 45)
        roll.mouseUp();
        assertNotNull(song.getNote(4, 0));

        roll.mouseDown(0, 0, AREA_W, AREA_H, MX, 97.5, 0, k, 0, 0); // 同列 key 50 / same column
        roll.mouseUp();
        assertEquals(2, song.noteCount(), "both strokes survive");
        assertEquals(45, song.getNote(4, 0).key, "the first note keeps its key");
        assertNotNull(song.getNote(4, 1), "the second stroke stacks on the next layer");
        assertEquals(50, song.getNote(4, 1).key);
    }

    @Test
    void verticalDragPaintsAChordStack() {
        // 同一次拖动涂抹里同列换键 = 逐层落和弦音，不再「音符跟随光标」。
        // One drag painting a new key in an occupied column stacks chord tones per layer —
        // the note no longer follows the cursor.
        NbsSong song = new NbsSong();
        NbsEditorKernel k = new NbsEditorKernel(song);
        NbsPianoRoll roll = new NbsPianoRoll();

        roll.mouseDown(0, 0, AREA_W, AREA_H, MX, MY, 0, k, 0, 0);    // (4, 45)
        roll.mouseDrag(0, 0, MX, 97.5, k, 0, 0);                     // (4, 50)
        roll.mouseUp();
        assertEquals(2, song.noteCount(), "the drag paints two chord tones");
        assertEquals(45, song.getNote(4, 0).key);
        assertEquals(50, song.getNote(4, 1).key);
    }
}
