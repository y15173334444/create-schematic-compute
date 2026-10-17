package io.github.y15173334444.create_schematic_compute.client;

import io.github.y15173334444.create_schematic_compute.graph.NbsSong;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link NbsSelection} 选区模型测试：框选/单格加减选/全选/反选 + 锁定层豁免（无 MC bootstrap）。
 * Selection-model tests: marquee / single-cell toggles / select-all / invert, plus the
 * locked-layer exemption.
 */
class NbsSelectionTest {

    private static NbsSong songWithNotes() {
        NbsSong s = new NbsSong();
        s.addLayer();                       // 2 层 / two layers
        s.putNote(2, 0, 0, 45, 100, 100, 0);
        s.putNote(4, 0, 1, 50, 100, 100, 0);
        s.putNote(4, 1, 1, 62, 100, 100, 0);
        s.putNote(9, 1, 2, 40, 100, 100, 0);
        return s;
    }

    @Test
    void marqueeCollectsOccupiedCellsInclusive() {
        NbsSong s = songWithNotes();
        NbsSelection sel = new NbsSelection();
        sel.selectRect(s, 2, 4, 0, 1, NbsSelection.Mode.REPLACE);
        assertEquals(3, sel.size(), "the rect covers (2,0) (4,0) (4,1)");
        assertTrue(sel.contains(2, 0));
        assertTrue(sel.contains(4, 0));
        assertTrue(sel.contains(4, 1));
        assertFalse(sel.contains(9, 1));
    }

    @Test
    void marqueeModesReplaceThenAdd() {
        NbsSong s = songWithNotes();
        NbsSelection sel = new NbsSelection();
        sel.selectRect(s, 2, 2, 0, 0, NbsSelection.Mode.REPLACE);
        assertEquals(1, sel.size());
        sel.selectRect(s, 9, 9, 1, 1, NbsSelection.Mode.REPLACE);
        assertEquals(1, sel.size(), "REPLACE drops the old selection");
        assertTrue(sel.contains(9, 1));
        sel.selectRect(s, 2, 2, 0, 0, NbsSelection.Mode.ADD);
        assertEquals(2, sel.size(), "ADD unions with the old selection");
        assertTrue(sel.contains(2, 0));
        assertTrue(sel.contains(9, 1));
    }

    @Test
    void rollMarqueeSelectsByNoteKey() {
        NbsSong s = songWithNotes();
        NbsSelection sel = new NbsSelection();
        // 键带 44–51：(2,0)@45 与 (4,0)@50 中选，(4,1)@62 与 (9,1)@40 不选
        // Key band 44-51 picks (2,0)@45 and (4,0)@50, not (4,1)@62 or (9,1)@40.
        sel.selectByTickKey(s, 0, 8, 44, 51, NbsSelection.Mode.REPLACE);
        assertEquals(2, sel.size());
        assertTrue(sel.contains(2, 0));
        assertTrue(sel.contains(4, 0));
    }

    @Test
    void toggleCellAddsAndRemoves() {
        NbsSong s = songWithNotes();
        NbsSelection sel = new NbsSelection();
        sel.toggleCell(s, 2, 0);
        assertTrue(sel.contains(2, 0));
        sel.toggleCell(s, 2, 0);
        assertTrue(sel.isEmpty(), "second toggle removes");
        sel.toggleCell(s, 3, 0);
        assertTrue(sel.isEmpty(), "empty cells never join");
    }

    @Test
    void lockedLayersNeverJoin() {
        NbsSong s = songWithNotes();
        s.layers.get(0).locked = true;
        NbsSelection sel = new NbsSelection();
        sel.toggleCell(s, 2, 0);
        assertTrue(sel.isEmpty(), "locked-layer cells refuse the toggle");
        sel.selectRect(s, 0, 10, 0, 1, NbsSelection.Mode.REPLACE);
        assertEquals(2, sel.size(), "the marquee only takes the unlocked layer's notes");
        assertFalse(sel.contains(2, 0));
        sel.clear();
        sel.selectAll(s);
        assertEquals(2, sel.size(), "select-all skips locked layers too");
        assertFalse(sel.contains(4, 0));
    }

    @Test
    void selectAllAndInvert() {
        NbsSong s = songWithNotes();
        NbsSelection sel = new NbsSelection();
        sel.selectAll(s);
        assertEquals(4, sel.size());
        sel.invert(s);
        assertTrue(sel.isEmpty(), "invert of select-all is empty");
        sel.toggleCell(s, 2, 0);
        sel.invert(s);
        assertEquals(3, sel.size(), "invert is the complement over selectable cells");
        assertFalse(sel.contains(2, 0));
        assertTrue(sel.contains(4, 0));
    }

    @Test
    void cellsAreOrderedAndClearWorks() {
        NbsSong s = songWithNotes();
        NbsSelection sel = new NbsSelection();
        sel.selectRect(s, 0, 10, 0, 1, NbsSelection.Mode.REPLACE);
        List<int[]> cs = sel.cells();
        assertEquals(4, cs.size());
        assertArrayEquals(new int[]{2, 0}, cs.get(0));
        assertArrayEquals(new int[]{4, 0}, cs.get(1));
        assertArrayEquals(new int[]{4, 1}, cs.get(2));
        assertArrayEquals(new int[]{9, 1}, cs.get(3));
        sel.clear();
        assertTrue(sel.isEmpty());
    }
}
