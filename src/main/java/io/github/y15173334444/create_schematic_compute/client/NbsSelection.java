package io.github.y15173334444.create_schematic_compute.client;

import io.github.y15173334444.create_schematic_compute.graph.NbsSong;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * NBS 编辑器选区模型（批次三 B1）：选中单元 = <b>(tick, layer) 单元集合</b>，只收有音符的格
 * （社区「选中音符」语义——选区是对音符的选择，不是对空白格的圈地）。框选（tick×layer 矩形 /
 * 卷帘 tick×键 矩形）与单格加减选（Ctrl+点）都归一到同一集合；成员判定以集合为准。
 * <b>锁定层永不入选</b>（锁定层不可编辑，选中即为删/剪/搬的误伤面）。
 * <p>NBS editor selection model (batch 3 B1): selected units = a set of <b>(tick, layer)
 * cells</b>, only ever holding note cells (the community "select the notes" semantics — a
 * selection picks notes, not empty territory). Both the marquee (tick×layer / tick×key
 * rectangles) and the single-cell Ctrl+click toggles converge on that one set; membership
 * is the set. <b>Locked layers never join a selection</b> (they refuse edits, and a selected
 * locked note would be a delete/cut/move hazard).</p>
 *
 * <p>纯数据、无 Minecraft 依赖（可单测）。编辑动作（删/剪/粘贴）在
 * {@link NbsEditorKernel}，以本模型为参数、走快照撤销。
 * Pure data, no Minecraft dependencies (unit-testable). The mutating operations
 * (delete / cut / paste) live on {@link NbsEditorKernel}, taking this model as a
 * parameter and undoing via snapshots.</p>
 */
public final class NbsSelection {

    /** 选中单元：pack(tick, layer)，TreeMap 序 = tick→layer 升序。 / selected cells, tick→layer order. */
    private final TreeSet<Long> cells = new TreeSet<>();

    private static long pack(int tick, int layer) {
        return (((long) tick) << 32) | (layer & 0xFFFFFFFFL);
    }

    // ── 查询 / queries ───────────────────────────────────────────────────

    public boolean isEmpty() { return cells.isEmpty(); }

    public int size() { return cells.size(); }

    public boolean contains(int tick, int layer) { return cells.contains(pack(tick, layer)); }

    /** 选中单元（升序），每项 {tick, layer}。 / the selected cells in order, each {tick, layer}. */
    public List<int[]> cells() {
        List<int[]> out = new ArrayList<>(cells.size());
        for (long c : cells) out.add(new int[]{(int) (c >>> 32), (int) c});
        return out;
    }

    // ── 单格加减选 / single-cell toggles ────────────────────────────────

    /**
     * 单格加/减选（Ctrl+点）：格上有音符且层未锁定才可入选；再点 = 移出。
     * Toggle one cell (Ctrl+click): joins only when a note lives there and its layer is
     * unlocked; clicking again drops it.
     */
    public void toggleCell(NbsSong song, int tick, int layer) {
        if (!selectable(song, tick, layer)) return;
        long c = pack(tick, layer);
        if (!cells.remove(c)) cells.add(c);
    }

    // ── 框选 / marquee ──────────────────────────────────────────────────

    /** 框选模式：替换旧选区 / 加到选区。 / marquee mode: replace the selection / add to it. */
    public enum Mode { REPLACE, ADD }

    /**
     * 层列框选（tick×layer 矩形，含端点）：收矩形内所有可选格。
     * Lane-view marquee (tick×layer rectangle, inclusive): collects every selectable
     * cell inside.
     */
    public void selectRect(NbsSong song, int tick0, int tick1, int layer0, int layer1, Mode mode) {
        if (mode == Mode.REPLACE) cells.clear();
        int tLo = Math.min(tick0, tick1), tHi = Math.max(tick0, tick1);
        int lLo = Math.min(layer0, layer1), lHi = Math.max(layer0, layer1);
        for (int t = Math.max(0, tLo); t <= tHi; t++) {
            for (int l = Math.max(0, lLo); l <= lHi; l++) {
                if (selectable(song, t, l)) cells.add(pack(t, l));
            }
        }
    }

    /**
     * 卷帘框选（tick×键 矩形，含端点）：收视觉矩形内的音符格（纵轴是音高不是层，
     * 选中的仍是音符的 (tick, layer) 单元）。
     * Roll-view marquee (tick×key rectangle, inclusive): collects the note cells whose
     * visual rect falls inside (the vertical axis is pitch, not layer — what gets selected
     * is still the notes' (tick, layer) cells).
     */
    public void selectByTickKey(NbsSong song, int tick0, int tick1, int key0, int key1, Mode mode) {
        if (mode == Mode.REPLACE) cells.clear();
        int tLo = Math.min(tick0, tick1), tHi = Math.max(tick0, tick1);
        int kLo = Math.max(0, Math.min(key0, key1)), kHi = Math.min(87, Math.max(key0, key1));
        for (int t = Math.max(0, tLo); t <= tHi; t++) {
            for (NbsSong.Note n : song.notesAtTick(t)) {
                if (n.key < kLo || n.key > kHi) continue;
                if (selectable(song, n.tick, n.layer)) cells.add(pack(n.tick, n.layer));
            }
        }
    }

    // ── 全选 / 反选 / 清空 / select all, invert, clear ──────────────────

    /** 全选：曲目上所有可选音符格。 / select every selectable note cell in the song. */
    public void selectAll(NbsSong song) {
        cells.clear();
        for (NbsSong.Note n : song.allNotes()) {
            if (selectable(song, n.tick, n.layer)) cells.add(pack(n.tick, n.layer));
        }
    }

    /** 反选：可选格的补集（全选后再反选 = 清空）。 / invert over the selectable cells. */
    public void invert(NbsSong song) {
        TreeSet<Long> next = new TreeSet<>();
        for (NbsSong.Note n : song.allNotes()) {
            long c = pack(n.tick, n.layer);
            if (!selectable(song, n.tick, n.layer)) continue;
            if (!cells.contains(c)) next.add(c);
        }
        cells.clear();
        cells.addAll(next);
    }

    public void clear() { cells.clear(); }

    // ── 内部 / internals ────────────────────────────────────────────────

    /** 可选 = 格上有音符且层未锁定。 / selectable = a note lives there and the layer is unlocked. */
    private static boolean selectable(NbsSong song, int tick, int layer) {
        if (layer < 0 || layer >= song.layers.size()) return false;
        if (song.layers.get(layer).locked) return false;
        return song.getNote(tick, layer) != null;
    }
}
