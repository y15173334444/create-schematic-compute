package io.github.y15173334444.create_schematic_compute.client;

import io.github.y15173334444.create_schematic_compute.graph.NbsSong;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link NbsEditorKernel} 纯内核测试：编辑语义 + 撤销/重做状态机（无 MC bootstrap）。
 * Pure-kernel tests for the NBS editor: edit semantics plus the undo/redo state machine.
 */
class NbsEditorKernelTest {

    private static NbsSong newSong() {
        NbsSong s = new NbsSong();
        return s;
    }

    @Test
    void togglePlacesThenRemoves() {
        NbsSong song = newSong();
        NbsEditorKernel k = new NbsEditorKernel(song);

        k.toggleNote(4, 0, 45, 3);
        assertNotNull(song.getNote(4, 0), "place creates a note");
        assertEquals(45, song.getNote(4, 0).key);
        assertEquals(3, song.getNote(4, 0).instrument);
        assertEquals(100, song.getNote(4, 0).velocity, "fresh notes default to full velocity");

        k.toggleNote(4, 0, 45, 3);
        assertNull(song.getNote(4, 0), "same-key toggle removes the note");
    }

    @Test
    void toggleOnOtherKeyMovesKeepingDynamics() {
        NbsSong song = newSong();
        NbsEditorKernel k = new NbsEditorKernel(song);
        k.toggleNote(4, 0, 45, 3);
        song.getNote(4, 0).velocity = 40;
        song.getNote(4, 0).pitch = -15;

        k.toggleNote(4, 0, 50, 7);
        NbsSong.Note n = song.getNote(4, 0);
        assertNotNull(n);
        assertEquals(50, n.key, "moved to the clicked key");
        assertEquals(7, n.instrument, "instrument follows the click");
        assertEquals(40, n.velocity, "velocity preserved on key move");
        assertEquals(-15, n.pitch, "fine pitch preserved on key move");
    }

    @Test
    void undoRedoRoundTripsCellEdits() {
        NbsSong song = newSong();
        NbsEditorKernel k = new NbsEditorKernel(song);

        k.toggleNote(4, 0, 45, 3);          // place
        k.toggleNote(8, 0, 47, 3);          // place
        k.eraseNote(4, 0);                  // erase

        assertNull(song.getNote(4, 0));
        assertNotNull(song.getNote(8, 0));

        assertTrue(k.performUndo());        // undo erase
        assertNotNull(song.getNote(4, 0), "undo restores the erased note");
        assertTrue(k.performUndo());        // undo place @8
        assertNull(song.getNote(8, 0));
        assertTrue(k.performUndo());        // undo place @4
        assertNull(song.getNote(4, 0));
        assertFalse(k.canUndo());

        assertTrue(k.performRedo());        // redo place @4
        assertNotNull(song.getNote(4, 0));
        assertTrue(k.performRedo());        // redo place @8
        assertTrue(k.performRedo());        // redo erase
        assertNull(song.getNote(4, 0));
        assertNotNull(song.getNote(8, 0));
        assertFalse(k.canRedo());
    }

    @Test
    void newEditClearsRedoStack() {
        NbsSong song = newSong();
        NbsEditorKernel k = new NbsEditorKernel(song);
        k.toggleNote(1, 0, 40, 0);
        k.performUndo();
        assertTrue(k.canRedo());
        k.toggleNote(2, 0, 41, 0);
        assertFalse(k.canRedo(), "a fresh edit drops the redo history");
    }

    @Test
    void strokeBatchIsOneUndoStep() {
        NbsSong song = newSong();
        NbsEditorKernel k = new NbsEditorKernel(song);

        k.beginBatch();
        for (int t = 0; t < 5; t++) k.toggleNote(t, 0, 45, 3);
        k.endBatch();

        assertEquals(5, song.noteCount());
        assertTrue(k.performUndo(), "one stroke undoes as a unit");
        assertEquals(0, song.noteCount(), "the whole stroke is reverted in one step");
        assertFalse(k.canUndo());
        assertTrue(k.performRedo());
        assertEquals(5, song.noteCount(), "redo restores the whole stroke");
    }

    @Test
    void emptyBatchPushesNothing() {
        NbsSong song = newSong();
        NbsEditorKernel k = new NbsEditorKernel(song);
        k.beginBatch();
        k.endBatch();
        assertFalse(k.canUndo());
    }

    @Test
    void layerFieldEditsAreUndoable() {
        NbsSong song = newSong();
        NbsEditorKernel k = new NbsEditorKernel(song);
        k.addLayer();

        k.setLayerName(0, "Melody");
        k.setLayerVolume(0, 55);
        k.setLayerPanning(0, 160);
        k.setLayerLocked(0, true);

        assertEquals("Melody", song.layers.get(0).name);
        assertEquals(55, song.layers.get(0).volume);
        assertEquals(160, song.layers.get(0).panning);
        assertTrue(song.layers.get(0).locked);

        for (int i = 0; i < 4; i++) assertTrue(k.performUndo());
        assertEquals("", song.layers.get(0).name);
        assertEquals(100, song.layers.get(0).volume);
        assertEquals(100, song.layers.get(0).panning);
        assertFalse(song.layers.get(0).locked);
    }

    @Test
    void layerVolumeAndPanningClamp() {
        NbsSong song = newSong();
        NbsEditorKernel k = new NbsEditorKernel(song);
        k.setLayerVolume(0, 250);
        assertEquals(100, song.layers.get(0).volume);
        k.setLayerPanning(0, -20);
        assertEquals(0, song.layers.get(0).panning);
    }

    @Test
    void lockedLayerRefusesNoteEdits() {
        NbsSong song = newSong();
        NbsEditorKernel k = new NbsEditorKernel(song);
        k.setLayerLocked(0, true);
        k.clearUndo();                       // 锁定操作自身可撤销；此处只测被拒绝的落点 / isolate the refused edit

        k.toggleNote(3, 0, 45, 0);
        assertEquals(0, song.noteCount(), "locked layers never take note edits");
        assertFalse(k.canUndo(), "a refused edit records nothing");
    }

    @Test
    void addRemoveLayerRoundTrip() {
        NbsSong song = newSong();
        NbsEditorKernel k = new NbsEditorKernel(song);
        k.toggleNote(2, 0, 45, 0);

        k.addLayer();
        assertEquals(2, song.layers.size());
        k.toggleNote(2, 1, 50, 0);
        assertEquals(2, song.noteCount());

        assertTrue(k.performUndo());        // undo note on layer 1
        assertTrue(k.performUndo());        // undo addLayer
        assertEquals(1, song.layers.size());

        assertTrue(k.performRedo());        // redo addLayer
        assertEquals(2, song.layers.size());
        assertTrue(k.performRedo());        // redo note
        assertEquals(2, song.noteCount());
    }

    @Test
    void removeLayerRestoresNotesAndRenumbering() {
        NbsSong song = newSong();
        NbsEditorKernel k = new NbsEditorKernel(song);
        k.addLayer();
        k.addLayer();
        k.toggleNote(5, 0, 40, 0);
        k.toggleNote(5, 1, 42, 0);
        k.toggleNote(5, 2, 44, 0);

        k.removeLayer(1);
        assertEquals(2, song.layers.size());
        assertEquals(2, song.noteCount());
        assertEquals(40, song.getNote(5, 0).key);
        assertEquals(44, song.getNote(5, 1).key, "notes above the removed layer renumber down");

        assertTrue(k.performUndo());
        assertEquals(3, song.layers.size());
        assertEquals(3, song.noteCount());
        assertEquals(42, song.getNote(5, 1).key, "the removed layer's notes come back");
        assertEquals(44, song.getNote(5, 2).key);
    }

    @Test
    void lastLayerCannotBeRemoved() {
        NbsSong song = newSong();
        NbsEditorKernel k = new NbsEditorKernel(song);
        k.removeLayer(0);
        assertEquals(1, song.layers.size(), "NBS keeps at least one layer");
        assertFalse(k.canUndo(), "a refused edit records nothing");
    }

    @Test
    void importReplaceIsUndoable() {
        NbsSong song = newSong();
        NbsEditorKernel k = new NbsEditorKernel(song);
        k.toggleNote(1, 0, 45, 0);
        song.songName = "old";

        NbsSong imported = new NbsSong();
        imported.songName = "imported";
        imported.tempo = 2000;
        imported.putNote(9, 0, 5, 60, 80, 100, 0);
        imported.putNote(10, 0, 5, 62, 80, 100, 0);
        k.replaceSong(imported);

        assertEquals("imported", song.songName);
        assertEquals(2000, song.tempo);
        assertEquals(2, song.noteCount(), "the working song now holds the imported notes");

        assertTrue(k.performUndo(), "import + undo (plan: 导入 .nbs + 撤销)");
        assertEquals("old", song.songName);
        assertEquals(1, song.noteCount(), "pre-import notes are restored");
        assertNotNull(song.getNote(1, 0));

        assertTrue(k.performRedo());
        assertEquals("imported", song.songName);
        assertEquals(2, song.noteCount());
    }

    @Test
    void tempoAndLoopEditsAreUndoable() {
        NbsSong song = newSong();
        NbsEditorKernel k = new NbsEditorKernel(song);

        k.setTempo(1500);
        k.setLoop(true, 3, 8);
        assertEquals(1500, song.tempo);
        assertTrue(song.loop);
        assertEquals(3, song.maxLoopCount);
        assertEquals(8, song.loopStartTick);

        assertTrue(k.performUndo());
        assertTrue(k.performUndo());
        assertEquals(1000, song.tempo);
        assertFalse(song.loop);
    }

    @Test
    void undoDepthIsCapped() {
        NbsSong song = newSong();
        NbsEditorKernel k = new NbsEditorKernel(song);
        for (int i = 0; i < NbsEditorKernel.MAX_UNDO + 50; i++) k.toggleNote(i, 0, 45, 0);
        int undoable = 0;
        while (k.performUndo()) undoable++;
        assertEquals(NbsEditorKernel.MAX_UNDO, undoable, "oldest entries drop past the cap");
    }
}
