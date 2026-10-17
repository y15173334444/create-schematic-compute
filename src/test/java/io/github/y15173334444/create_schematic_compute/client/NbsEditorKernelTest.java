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

    // ══════════════ 层序器落点（双样式批次）/ lane-view placement (dual-style batch) ══════════════

    @Test
    void placeNoteFillsThenOverwritesWholeCell() {
        NbsSong song = newSong();
        NbsEditorKernel k = new NbsEditorKernel(song);
        k.placeNote(2, 0, 3, 50, 90, 150, 25);
        NbsSong.Note n = song.getNote(2, 0);
        assertNotNull(n, "empty cell gets a fresh note");
        assertEquals(3, n.instrument);
        assertEquals(50, n.key);
        assertEquals(90, n.velocity);
        assertEquals(150, n.panning);
        assertEquals(25, n.pitch);

        // 覆盖整格、不保留旧属性（工具态写格子）/ overwrite replaces the whole cell
        k.placeNote(2, 0, 1, 55, 40, 60, -10);
        n = song.getNote(2, 0);
        assertEquals(1, n.instrument);
        assertEquals(55, n.key);
        assertEquals(40, n.velocity);
        assertEquals(60, n.panning);
        assertEquals(-10, n.pitch);
        assertEquals(1, song.noteCount(), "still one cell");

        assertTrue(k.performUndo(), "overwrite undoes to the first state");
        n = song.getNote(2, 0);
        assertEquals(3, n.instrument);
        assertEquals(50, n.key);
        assertTrue(k.performUndo(), "placement undoes to empty");
        assertNull(song.getNote(2, 0));
    }

    @Test
    void placeNoteIdenticalIsNoOp() {
        NbsSong song = newSong();
        NbsEditorKernel k = new NbsEditorKernel(song);
        k.placeNote(4, 0, 2, 45, 100, 100, 0);
        k.placeNote(4, 0, 2, 45, 100, 100, 0);
        assertTrue(k.performUndo(), "the first placement is undoable");
        assertNull(song.getNote(4, 0), "one undo clears the cell - the identical second call recorded nothing");
        assertFalse(k.performUndo(), "and the stack is empty");
    }

    @Test
    void placeNoteClampsAndGuards() {
        NbsSong song = newSong();
        NbsEditorKernel k = new NbsEditorKernel(song);
        k.placeNote(0, 0, 0, 99, 100, 100, 0);
        assertNull(song.getNote(0, 0), "keys outside 0-87 are refused");
        k.placeNote(0, 0, 0, 45, 999, -5, 99999);
        NbsSong.Note n = song.getNote(0, 0);
        assertEquals(100, n.velocity, "velocity clamps to 100");
        assertEquals(0, n.panning, "panning clamps to 0");
        assertEquals(32767, n.pitch, "fine pitch clamps to the NBS range");

        k.setLayerLocked(0, true);
        k.placeNote(1, 0, 0, 45, 100, 100, 0);
        assertNull(song.getNote(1, 0), "locked layers refuse placement");
    }

    @Test
    void changeNoteKeyKeepsDynamicsAndGuards() {
        NbsSong song = newSong();
        NbsEditorKernel k = new NbsEditorKernel(song);
        k.placeNote(2, 0, 3, 50, 90, 150, 25);
        k.changeNoteKey(2, 0, 50);                       // 同键 = 空操作 / same key = no-op
        assertTrue(k.performUndo(), "one undo clears the cell - the same-key call recorded nothing");
        assertNull(song.getNote(2, 0));
        assertFalse(k.performUndo(), "and the stack is empty");

        k.placeNote(2, 0, 3, 50, 90, 150, 25);
        k.changeNoteKey(2, 0, 51);
        NbsSong.Note n = song.getNote(2, 0);
        assertEquals(51, n.key);
        assertEquals(3, n.instrument, "instrument kept");
        assertEquals(90, n.velocity, "velocity kept");
        assertEquals(150, n.panning, "panning kept");
        assertEquals(25, n.pitch, "fine pitch kept");
        assertTrue(k.performUndo());
        assertEquals(50, song.getNote(2, 0).key, "key change undoes");

        k.changeNoteKey(2, 0, 99);
        assertEquals(50, song.getNote(2, 0).key, "out-of-range keys refused");
        k.changeNoteKey(3, 0, 60);
        assertNull(song.getNote(3, 0), "empty cells stay empty");
        k.setLayerLocked(0, true);
        k.changeNoteKey(2, 0, 60);
        assertEquals(50, song.getNote(2, 0).key, "locked layers refuse");
    }

    // ══════════════ 选区级操作（批次三 B1）/ selection-level ops (batch 3 B1) ══════════════

    private static NbsSelection selectTwo(NbsSong song) {
        NbsSelection sel = new NbsSelection();
        sel.toggleCell(song, 2, 0);
        sel.toggleCell(song, 4, 0);
        return sel;
    }

    @Test
    void deleteSelectionRemovesAndUndoRestores() {
        NbsSong song = newSong();
        NbsEditorKernel k = new NbsEditorKernel(song);
        k.toggleNote(2, 0, 45, 0);
        k.toggleNote(4, 0, 47, 0);
        k.toggleNote(6, 0, 49, 0);
        k.clearUndo();

        assertEquals(2, k.deleteSelection(selectTwo(song)), "one snapshot deletes both");
        assertEquals(1, song.noteCount());
        assertNotNull(song.getNote(6, 0), "unselected notes stay");

        assertTrue(k.performUndo(), "delete undoes as one unit");
        assertEquals(3, song.noteCount(), "the whole span comes back");
        assertFalse(k.canUndo(), "one snapshot, one undo step");
    }

    @Test
    void deleteSelectionSkipsLockedAndEmpty() {
        NbsSong song = newSong();
        NbsEditorKernel k = new NbsEditorKernel(song);
        k.toggleNote(2, 0, 45, 0);
        k.toggleNote(4, 0, 47, 0);
        k.setLayerLocked(0, true);
        k.clearUndo();

        NbsSelection sel = selectTwo(song);
        assertEquals(0, k.deleteSelection(sel), "locked layers never take edits");
        assertEquals(2, song.noteCount());
        assertFalse(k.canUndo(), "a refused edit records nothing");

        k.setLayerLocked(0, false);
        k.clearUndo();
        assertEquals(0, k.deleteSelection(new NbsSelection()), "empty selection is a no-op");
        assertFalse(k.canUndo());
    }

    @Test
    void cutCopyPasteRoundTrip() {
        NbsSong song = newSong();
        NbsEditorKernel k = new NbsEditorKernel(song);
        k.addLayer();
        k.addLayer();                            // 3 层：粘贴目标层 (12,2) 也在层内 / paste target (12,2) stays in range
        k.toggleNote(4, 0, 45, 3);
        song.getNote(4, 0).velocity = 40;
        song.getNote(4, 0).pitch = -15;
        k.toggleNote(6, 1, 50, 7);
        song.getNote(6, 1).panning = 160;
        k.clearUndo();

        NbsSelection sel = new NbsSelection();
        sel.selectRect(song, 0, 10, 0, 5, NbsSelection.Mode.REPLACE);
        NbsClipboard cut = k.cutSelection(sel);
        assertNotNull(cut);
        assertEquals(0, song.noteCount(), "cut empties the selection's cells");
        assertTrue(k.performUndo(), "cut undoes as one snapshot");
        assertEquals(2, song.noteCount());

        // 粘贴：片段左上角对齐 (10, 1)；属性随行 / paste anchors the fragment at (10, 1)
        NbsSelection placed = k.paste(cut, 10, 1);
        assertEquals(2, placed.size());
        assertTrue(placed.contains(10, 1));
        assertTrue(placed.contains(12, 2), "relative offsets preserved (dTick 2, dLayer 1)");
        NbsSong.Note a = song.getNote(10, 1);
        assertEquals(45, a.key);
        assertEquals(3, a.instrument);
        assertEquals(40, a.velocity, "velocity travels with the fragment");
        assertEquals(-15, a.pitch);
        assertEquals(160, song.getNote(12, 2).panning);

        assertTrue(k.performUndo(), "paste undoes as one snapshot");
        assertNull(song.getNote(10, 1));
        assertEquals(2, song.noteCount());
    }

    @Test
    void pasteOverwritesClampsAndGuards() {
        NbsSong song = newSong();
        NbsEditorKernel k = new NbsEditorKernel(song);
        k.toggleNote(0, 0, 30, 1);                   // 被覆盖目标 / the overwrite victim
        NbsClipboard clip = NbsClipboard.of(java.util.List.of(
            new NbsClipboard.Entry(0, 0, 5, 50, 999, -5, 99999),
            new NbsClipboard.Entry(1, 0, 5, 99, 100, 100, 0),   // 键越界 → 跳过 / bad key skipped
            new NbsClipboard.Entry(2, 5, 5, 55, 100, 100, 0),   // 目标层越界 → 跳过、不建层 / no layer creation
            new NbsClipboard.Entry(3, 0, 5, 56, 100, 100, 0)));

        NbsSelection placed = k.paste(clip, 0, 0);
        assertEquals(2, placed.size(), "clamped + clean entries land; bad key and bad layer skipped");
        assertTrue(placed.contains(0, 0));
        assertTrue(placed.contains(3, 0));
        NbsSong.Note n = song.getNote(0, 0);
        assertEquals(50, n.key, "paste overwrites the whole cell");
        assertEquals(5, n.instrument, "the fragment's instrument overwrites the victim's");
        assertEquals(100, n.velocity, "velocity clamps to 100");
        assertEquals(0, n.panning, "panning clamps to 0");
        assertEquals(32767, n.pitch, "fine pitch clamps");
        assertEquals(56, song.getNote(3, 0).key);
        assertEquals(2, song.noteCount());

        assertTrue(k.performUndo(), "paste undoes to the victim note");
        assertEquals(30, song.getNote(0, 0).key);
        assertNull(song.getNote(3, 0));
        assertEquals(1, song.noteCount());

        // 锁定目标层跳过 / locked target layers skip
        k.setLayerLocked(0, true);
        k.clearUndo();
        assertTrue(k.paste(clip, 5, 0).isEmpty());
        assertFalse(k.canUndo(), "nothing landed, nothing recorded");
    }

    @Test
    void cutAndCopyOfNothingReturnNull() {
        NbsSong song = newSong();
        NbsEditorKernel k = new NbsEditorKernel(song);
        assertNull(k.copySelection(new NbsSelection()));
        assertNull(k.cutSelection(new NbsSelection()));
        assertFalse(k.canUndo(), "an empty cut records nothing");

        k.toggleNote(3, 0, 45, 0);
        k.clearUndo();                           // 只看复制/剪切是否记撤销 / isolate copy/cut from the setup edit
        NbsSelection sel = new NbsSelection();
        sel.toggleCell(song, 3, 0);
        NbsClipboard c = k.copySelection(sel);
        assertNotNull(c);
        assertEquals(1, song.noteCount(), "copy is read-only");
        assertFalse(k.canUndo());
    }

    // ══════════════ 拖移搬移（批次三 B2）/ drag-to-move (batch 3 B2) ══════════════

    @Test
    void moveNoteRelocatesAndUndoRestoresBothCells() {
        NbsSong song = newSong();
        NbsEditorKernel k = new NbsEditorKernel(song);
        k.toggleNote(2, 0, 45, 3);
        song.getNote(2, 0).velocity = 40;
        song.getNote(2, 0).panning = 150;
        song.getNote(2, 0).pitch = -15;
        k.toggleNote(5, 0, 30, 1);               // 被覆盖目标 / the overwrite victim
        k.clearUndo();

        assertTrue(k.moveNote(2, 0, 5, 0, 50));
        assertNull(song.getNote(2, 0), "the source cell empties");
        NbsSong.Note n = song.getNote(5, 0);
        assertEquals(50, n.key);
        assertEquals(3, n.instrument, "instrument travels with the note");
        assertEquals(40, n.velocity, "velocity preserved");
        assertEquals(150, n.panning, "panning preserved");
        assertEquals(-15, n.pitch, "fine pitch preserved");

        assertTrue(k.performUndo(), "one precise-inverse step");
        NbsSong.Note back = song.getNote(2, 0);
        assertEquals(45, back.key, "the source note is restored");
        assertEquals(30, song.getNote(5, 0).key, "the overwritten victim is restored");
        assertFalse(k.canUndo());
    }

    @Test
    void moveNoteRetunesInPlaceAndGuards() {
        NbsSong song = newSong();
        NbsEditorKernel k = new NbsEditorKernel(song);
        k.toggleNote(2, 0, 45, 3);
        song.getNote(2, 0).velocity = 40;
        k.clearUndo();

        // 同格换键 = 改音高（统一拖动语义）/ same cell + new key = retune (the unified drag)
        assertTrue(k.moveNote(2, 0, 2, 0, 51));
        assertEquals(51, song.getNote(2, 0).key);
        assertEquals(40, song.getNote(2, 0).velocity, "dynamics kept");
        assertTrue(k.performUndo());
        assertEquals(45, song.getNote(2, 0).key);

        assertFalse(k.moveNote(2, 0, 2, 0, 45), "same cell + same key is a no-op");
        assertFalse(k.moveNote(3, 0, 4, 0, 45), "no source note, no move");
        assertFalse(k.canUndo());

        k.setLayerLocked(0, true);
        assertFalse(k.moveNote(2, 0, 2, 0, 60), "locked source refuses");
        k.setLayerLocked(0, false);
        k.addLayer();
        k.setLayerLocked(1, true);
        k.clearUndo();
        assertFalse(k.moveNote(2, 0, 2, 1, 60), "locked target refuses");
        assertFalse(k.moveNote(2, 0, 2, 5, 60), "out-of-range target refuses (no layer creation)");
        assertFalse(k.canUndo(), "refused moves record nothing");
    }

    @Test
    void moveSelectionShiftsUniformlyAndUndoRestores() {
        NbsSong song = newSong();
        NbsEditorKernel k = new NbsEditorKernel(song);
        k.addLayer();
        k.addLayer();
        k.toggleNote(0, 0, 40, 3);
        song.getNote(0, 0).velocity = 40;
        k.toggleNote(2, 1, 50, 7);
        k.clearUndo();

        NbsSelection sel = new NbsSelection();
        sel.selectRect(song, 0, 10, 0, 5, NbsSelection.Mode.REPLACE);
        NbsSelection moved = k.moveSelection(sel, 3, 1, 5);
        assertEquals(2, moved.size(), "the moved selection follows the notes");
        assertTrue(moved.contains(3, 1));
        assertTrue(moved.contains(5, 2));
        assertEquals(45, song.getNote(3, 1).key, "keys shift by dKey");
        assertEquals(55, song.getNote(5, 2).key);
        assertEquals(40, song.getNote(3, 1).velocity, "dynamics preserved");
        assertEquals(2, song.noteCount(), "no notes lost or duplicated");

        assertTrue(k.performUndo(), "selection moves undo as one snapshot");
        assertEquals(40, song.getNote(0, 0).key);
        assertEquals(50, song.getNote(2, 1).key);
        assertNull(song.getNote(3, 1));
        assertFalse(k.canUndo());
    }

    @Test
    void moveSelectionGuardsRefuseAtomically() {
        NbsSong song = newSong();
        NbsEditorKernel k = new NbsEditorKernel(song);
        k.addLayer();
        k.toggleNote(1, 0, 45, 0);
        k.toggleNote(1, 1, 50, 0);
        NbsSelection sel = new NbsSelection();
        sel.selectRect(song, 0, 10, 0, 5, NbsSelection.Mode.REPLACE);
        k.clearUndo();

        assertEquals(0, k.moveSelection(sel, 0, 0, 0).size(), "zero delta is a no-op");
        assertEquals(0, k.moveSelection(sel, 0, 2, 0).size(), "no layer creation past the end");
        k.setLayerLocked(1, true);
        k.clearUndo();                           // 锁定操作自身可撤销 / isolate the refused moves
        assertEquals(0, k.moveSelection(sel, 2, 0, 0).size(),
            "a locked target layer refuses the whole move");
        assertEquals(2, song.noteCount(), "nothing moved");
        assertEquals(45, song.getNote(1, 0).key, "sources stay put");
        assertFalse(k.canUndo(), "refused moves record nothing");

        // 键钳位：85 + 12 → 87 / key clamps at the top
        k.setLayerLocked(1, false);
        song.getNote(1, 0).key = 85;
        NbsSelection moved = k.moveSelection(sel, 0, 0, 12);
        assertEquals(2, moved.size());
        assertEquals(87, song.getNote(1, 0).key, "keys clamp to 0-87");
    }

    // ══════════════ 逐音符属性（批次三 B3）/ per-note fields (batch 3 B3) ══════════════

    @Test
    void setNoteFieldEditsClampsAndUndo() {
        NbsSong song = newSong();
        NbsEditorKernel k = new NbsEditorKernel(song);
        k.toggleNote(2, 0, 45, 3);
        k.clearUndo();

        assertTrue(k.setNoteField(2, 0, NbsEditorKernel.FIELD_VELOCITY, 55));
        NbsSong.Note n = song.getNote(2, 0);
        assertEquals(55, n.velocity);
        assertEquals(45, n.key, "only the field changes");
        assertEquals(100, n.panning);
        assertTrue(k.performUndo(), "precise inverse");
        assertEquals(100, song.getNote(2, 0).velocity);
        assertFalse(k.canUndo());

        assertFalse(k.setNoteField(2, 0, NbsEditorKernel.FIELD_VELOCITY, 100), "same value = no-op");
        assertFalse(k.setNoteField(3, 0, NbsEditorKernel.FIELD_PANNING, 50), "empty cell refuses");
        assertFalse(k.canUndo());

        k.setNoteField(2, 0, NbsEditorKernel.FIELD_VELOCITY, 999);
        k.setNoteField(2, 0, NbsEditorKernel.FIELD_PANNING, -5);
        k.setNoteField(2, 0, NbsEditorKernel.FIELD_PITCH, 99999);
        n = song.getNote(2, 0);
        assertEquals(100, n.velocity, "velocity clamps to 0-100");
        assertEquals(0, n.panning, "panning clamps to 0-200");
        assertEquals(32767, n.pitch, "fine pitch clamps");

        k.setLayerLocked(0, true);
        assertFalse(k.setNoteField(2, 0, NbsEditorKernel.FIELD_PITCH, -32768), "locked layers refuse");
        assertEquals(32767, song.getNote(2, 0).pitch);
    }

    @Test
    void setSelectionFieldAbsoluteAndRelative() {
        NbsSong song = newSong();
        NbsEditorKernel k = new NbsEditorKernel(song);
        k.toggleNote(0, 0, 45, 3);
        song.getNote(0, 0).velocity = 40;
        k.toggleNote(2, 0, 50, 7);
        song.getNote(2, 0).velocity = 80;
        k.toggleNote(4, 0, 55, 7);
        song.getNote(4, 0).pitch = 100;
        k.clearUndo();

        NbsSelection sel = new NbsSelection();
        sel.selectRect(song, 0, 3, 0, 5, NbsSelection.Mode.REPLACE);   // (4,0) 不在选区 / stays out
        assertEquals(2, k.setSelectionField(sel, NbsEditorKernel.FIELD_VELOCITY, 55, true));
        assertEquals(55, song.getNote(0, 0).velocity, "absolute: both take the value");
        assertEquals(55, song.getNote(2, 0).velocity);
        assertEquals(100, song.getNote(4, 0).velocity, "unselected notes untouched");
        assertTrue(k.performUndo(), "one snapshot undoes the whole set");
        assertEquals(40, song.getNote(0, 0).velocity);
        assertEquals(80, song.getNote(2, 0).velocity);
        assertFalse(k.canUndo());

        // 增量模式：各自 ± / relative: each note shifts
        assertEquals(2, k.setSelectionField(sel, NbsEditorKernel.FIELD_VELOCITY, -10, false));
        assertEquals(30, song.getNote(0, 0).velocity);
        assertEquals(70, song.getNote(2, 0).velocity);

        // 细音高 + 钳位 / fine pitch with clamping
        NbsSelection all = new NbsSelection();
        all.selectAll(song);
        assertEquals(3, k.setSelectionField(all, NbsEditorKernel.FIELD_PITCH, 20, false));
        assertEquals(20, song.getNote(0, 0).pitch);
        assertEquals(120, song.getNote(4, 0).pitch, "existing pitch shifts too");

        assertEquals(0, k.setSelectionField(sel, NbsEditorKernel.FIELD_VELOCITY, 0, false), "zero delta = no-op");
    }
}
