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
}
