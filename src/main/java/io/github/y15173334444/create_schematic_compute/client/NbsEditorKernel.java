package io.github.y15173334444.create_schematic_compute.client;

import io.github.y15173334444.create_schematic_compute.graph.NbsSong;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * NBS 编辑器内核：曲目上的编辑操作 + 撤销/重做状态机（自 {@link NbsEditorScreen} 拆分，
 * plan §3.2 三件之一）。**不持有任何视图状态**——zoom / 滚动 / 当前层 / 当前乐器归屏幕，
 * 编辑时作为参数传入。**无 Minecraft 依赖**（可在无 bootstrap 的 JUnit 里测试，
 * {@code NbsEditorKernelTest}）。
 * <p>NBS editor kernel: the edit operations over a song plus the undo/redo state machine,
 * split out of {@link NbsEditorKernel screen} (plan §3.2, one of the three parts).
 * <b>No view state lives here</b> — zoom / scroll / current layer / current instrument stay
 * on the screen and are passed in per call. No Minecraft dependencies (testable in plain
 * JUnit, see {@code NbsEditorKernelTest}).</p>
 *
 * <p><b>撤销模型</b>：音符格 / 层参数 / 速度 / 循环等高频编辑记<b>精确逆操作</b>（只存被改的
 * 那一格/那一个字段）；结构级编辑（导入替换、删层）记<b>整曲快照</b>（低频、绝对值无条件安全）。
 * 严格 LIFO 解栈保证精确逆操作安全（后续编辑先于其撤销）。
 * <b>Undo model</b>: high-frequency edits (note cells / layer fields / tempo / loop) record a
 * <b>precise inverse</b> (only the touched cell or field); structural edits (import replace,
 * layer removal) record a <b>full-song snapshot</b> (rare, absolute-state safe). Strict LIFO
 * unwinding keeps the precise inverses sound.</p>
 *
 * <p><b>笔划分组</b>：拖拽涂抹时 {@link #beginBatch}/{@link #endBatch} 把一次笔划的多格落点
 * 收拢为一条撤销记录（与像素编辑器「一次笔划一条」同语义）。
 * <b>Stroke grouping</b>: {@link #beginBatch}/{@link #endBatch} collapse one drag-paint stroke
 * into a single undo entry (same semantics as the pixel editor's once-per-stroke snapshot).</p>
 */
public final class NbsEditorKernel {

    /** 撤销栈深度上限。 / undo-stack depth cap. */
    public static final int MAX_UNDO = 200;

    /** 正在编辑的曲目（活引用——调用方持有的节点曲目本体）。
     *  The song being edited (live reference — the caller's node song itself). */
    private final NbsSong song;

    private final ArrayDeque<Edit> undoStack = new ArrayDeque<>();
    private final ArrayDeque<Edit> redoStack = new ArrayDeque<>();
    /** 批量组收集器（null = 非批量；空组在 endBatch 时丢弃）。 / batch collector (null = no batch). */
    private List<Edit> currentBatch = null;
    private int batchDepth = 0;
    /** 自上次 {@link #markClean()} 以来是否有未同步的编辑（屏幕据此决定保存/关闭时上传）。 */
    private boolean dirty = false;

    /** 一条可撤销编辑：redo() 首次执行（构造后由 push 调用），undo()/redo() 严格 LIFO。
     *  One undoable edit: redo() runs first (from push), then strictly LIFO. */
    private interface Edit {
        void undo();
        void redo();
    }

    public NbsEditorKernel(NbsSong song) {
        this.song = song;
    }

    public NbsSong song() { return song; }

    // ══════════════ 编辑操作 / edit ops ══════════════

    /**
     * 点放/删除音符（卷帘点击语义，Note Block Studio 同款）：
     * 该 (tick, layer) 已有同键音符 → 删；已有他键音符 → 移到新键（保力度/声像/细音高）；
     * 空 → 放新音符（默认力度 100 / 声像居中 / 无细音高）。层锁定时不做任何编辑。
     * <p>Toggle note at (tick, layer) — roll-click semantics (Note Block Studio style):
     * same-key note present → remove; other-key note present → move to the new key (keeps
     * velocity/panning/pitch); empty → place a fresh note (velocity 100, centred panning,
     * no fine pitch). Locked layers are never edited.</p>
     */
    public void toggleNote(int tick, int layer, int key, int instrument) {
        if (layer < 0 || layer >= song.layers.size()) return;
        if (song.layers.get(layer).locked) return;
        NbsSong.Note old = song.getNote(tick, layer);
        NbsSong.Note oldCopy = old == null ? null : copyOf(old);
        NbsSong.Note fresh;
        if (old != null && old.key == key) {
            fresh = null;                              // toggle off / 点灭
        } else if (old != null) {
            fresh = new NbsSong.Note(tick, layer, instrument, key, old.velocity, old.panning, old.pitch);
        } else {
            fresh = new NbsSong.Note(tick, layer, instrument, key, 100, 100, 0);
        }
        apply(new CellEdit(tick, layer, oldCopy, fresh));
    }

    /** 擦除 (tick, layer) 音符（右键；层锁定时不做）。 / Erase the note at (tick, layer) (right-click). */
    public void eraseNote(int tick, int layer) {
        if (layer < 0 || layer >= song.layers.size()) return;
        if (song.layers.get(layer).locked) return;
        NbsSong.Note old = song.getNote(tick, layer);
        if (old == null) return;
        apply(new CellEdit(tick, layer, copyOf(old), null));
    }

    /** 层名称。 / layer display name. */
    public void setLayerName(int layer, String name) {
        if (layer < 0 || layer >= song.layers.size()) return;
        String n = name == null ? "" : name;
        String old = song.layers.get(layer).name;
        if (old.equals(n)) return;
        apply(new LayerFieldEdit(layer, 0, old, n));
    }

    /** 层音量 0–100（越界钳边缘）。 / layer volume 0–100 (clamped). */
    public void setLayerVolume(int layer, int volume) {
        setLayerIntField(layer, 1, clamp(volume, 0, 100));
    }

    /** 层声像 0–200、100=居中（越界钳边缘）。 / layer panning 0–200, 100 = centre (clamped). */
    public void setLayerPanning(int layer, int panning) {
        setLayerIntField(layer, 2, clamp(panning, 0, 200));
    }

    /** 层锁定（锁定层不可放/删音符）。 / layer lock (locked layers refuse note edits). */
    public void setLayerLocked(int layer, boolean locked) {
        if (layer < 0 || layer >= song.layers.size()) return;
        boolean old = song.layers.get(layer).locked;
        if (old == locked) return;
        apply(new LayerFieldEdit(layer, 3, old, locked));
    }

    private void setLayerIntField(int layer, int field, int value) {
        if (layer < 0 || layer >= song.layers.size()) return;
        int old = field == 1 ? song.layers.get(layer).volume : song.layers.get(layer).panning;
        if (old == value) return;
        apply(new LayerFieldEdit(layer, field, old, value));
    }

    /** 追加一层（undo 移除之；严格 LIFO 下精确逆安全）。 / Append a layer (undo removes it). */
    public void addLayer() {
        apply(new Edit() {
            private int index = -1;
            @Override public void undo() {
                if (index >= 0 && index < song.layers.size()) song.layers.remove(index);
            }
            @Override public void redo() {
                index = song.addLayer();
            }
        });
    }

    /** 删除一层（连带音符重编号；结构级 → 整曲快照撤销）。最后一层不可删。 */
    public void removeLayer(int index) {
        if (index < 0 || index >= song.layers.size()) return;
        if (song.layers.size() <= 1) return;           // NBS 恒至少 1 层 / NBS keeps ≥1 layer
        NbsSong before = song.copy();
        song.removeLayer(index);
        NbsSong after = song.copy();
        push(new SnapshotEdit(before, after));
    }

    /** 速度 = 每秒 tick × 100（NBS 头部 tempo）。 / tempo = ticks-per-second × 100. */
    public void setTempo(int tempo) {
        int t = clamp(tempo, 1, 65535);
        if (song.tempo == t) return;
        apply(new Edit() {
            private final int oldT = song.tempo, newT = t;
            @Override public void undo() { song.tempo = oldT; }
            @Override public void redo() { song.tempo = newT; }
        });
    }

    /** 循环三项（NBS v4+ 头部）。 / the three loop fields (NBS v4+ header). */
    public void setLoop(boolean loop, int maxLoopCount, int loopStartTick) {
        boolean nl = loop;
        int nc = clamp(maxLoopCount, 0, 255);
        int ns = clamp(loopStartTick, 0, 65535);
        if (song.loop == nl && song.maxLoopCount == nc && song.loopStartTick == ns) return;
        apply(new Edit() {
            private final boolean oldL = song.loop;
            private final int oldC = song.maxLoopCount, oldS = song.loopStartTick;
            @Override public void undo() { song.loop = oldL; song.maxLoopCount = oldC; song.loopStartTick = oldS; }
            @Override public void redo() { song.loop = nl; song.maxLoopCount = nc; song.loopStartTick = ns; }
        });
    }

    /**
     * 导入替换：以 incoming 的内容整体覆盖本曲（结构级 → 整曲快照撤销，"导入 .nbs + 撤销"）。
     * Import replace: overwrite the working song with {@code incoming} (structural —
     * snapshot undo, i.e. "import a .nbs and undo it").
     */
    public void replaceSong(NbsSong incoming) {
        NbsSong before = song.copy();
        NbsSong src = incoming == null ? new NbsSong() : incoming;
        song.copyFrom(src);
        NbsSong after = song.copy();
        push(new SnapshotEdit(before, after));
    }

    // ══════════════ 撤销/重做状态机 / undo & redo state machine ══════════════

    /** 开始批量组（拖拽笔划开始）。可重入计数。 / Begin a batch group (stroke start); re-entrant. */
    public void beginBatch() {
        if (batchDepth == 0) currentBatch = new ArrayList<>();
        batchDepth++;
    }

    /** 结束批量组（拖拽笔划结束）；空组丢弃、单条直入。 / End the batch group; empty dropped, single unwrapped. */
    public void endBatch() {
        if (batchDepth <= 0) return;
        batchDepth--;
        if (batchDepth == 0 && currentBatch != null) {
            List<Edit> batch = currentBatch;
            currentBatch = null;
            if (batch.isEmpty()) return;
            if (batch.size() == 1) { push(batch.get(0)); return; }
            push(new Edit() {
                @Override public void undo() { for (int i = batch.size() - 1; i >= 0; i--) batch.get(i).undo(); }
                @Override public void redo() { for (Edit e : batch) e.redo(); }
            });
        }
    }

    public boolean canUndo() { return !undoStack.isEmpty(); }
    public boolean canRedo() { return !redoStack.isEmpty(); }

    /** 撤销一步；无可撤销返回 false。 / Undo one step; false when the stack is empty. */
    public boolean performUndo() {
        if (undoStack.isEmpty()) return false;
        Edit e = undoStack.pop();
        e.undo();
        dirty = true;
        if (redoStack.size() < MAX_UNDO) redoStack.push(e);
        return true;
    }

    /** 重做一步；无可重做返回 false。 / Redo one step; false when the stack is empty. */
    public boolean performRedo() {
        if (redoStack.isEmpty()) return false;
        Edit e = redoStack.pop();
        e.redo();
        dirty = true;
        if (undoStack.size() < MAX_UNDO) undoStack.push(e);
        return true;
    }

    /** 清空撤销/重做栈（导入直换等不需撤销历史时调用方自行决定）。 */
    public void clearUndo() {
        undoStack.clear();
        redoStack.clear();
    }

    /** 自上次 {@link #markClean()} 以来是否有编辑（含撤销/重做）。 */
    public boolean isDirty() { return dirty; }

    /** 标记已同步（保存/上传成功后调用）。 */
    public void markClean() { dirty = false; }

    // ══════════════ 内部 / internals ══════════════

    /** 执行并入栈（新编辑清空 redo 栈）。 / execute and record (a new edit clears the redo stack). */
    private void apply(Edit e) {
        e.redo();
        dirty = true;
        if (currentBatch != null && batchDepth > 0) {
            currentBatch.add(e);
            return;
        }
        push(e);
    }

    private void push(Edit e) {
        dirty = true;
        if (undoStack.size() >= MAX_UNDO) undoStack.removeLast();
        undoStack.push(e);
        redoStack.clear();
    }

    private static NbsSong.Note copyOf(NbsSong.Note n) {
        return new NbsSong.Note(n.tick, n.layer, n.instrument, n.key, n.velocity, n.panning, n.pitch);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** 音符格编辑：newNote=null 表示删除。 / cell edit; newNote == null means removal. */
    private final class CellEdit implements Edit {
        private final int tick, layer;
        private final NbsSong.Note oldNote, newNote;
        CellEdit(int tick, int layer, NbsSong.Note oldNote, NbsSong.Note newNote) {
            this.tick = tick; this.layer = layer; this.oldNote = oldNote; this.newNote = newNote;
        }
        @Override public void undo() {
            if (newNote != null) song.removeNote(tick, layer);
            if (oldNote != null) song.putNote(tick, layer, oldNote.instrument, oldNote.key,
                oldNote.velocity, oldNote.panning, oldNote.pitch);
        }
        @Override public void redo() {
            if (oldNote != null) song.removeNote(tick, layer);
            if (newNote != null) song.putNote(tick, layer, newNote.instrument, newNote.key,
                newNote.velocity, newNote.panning, newNote.pitch);
        }
    }

    /** 层字段编辑：field 0=名称 / 1=音量 / 2=声像 / 3=锁定（值为 String/Integer/Boolean）。 */
    private final class LayerFieldEdit implements Edit {
        private final int layer, field;
        private final Object oldV, newV;
        LayerFieldEdit(int layer, int field, Object oldV, Object newV) {
            this.layer = layer; this.field = field; this.oldV = oldV; this.newV = newV;
        }
        @Override public void undo() { set(oldV); }
        @Override public void redo() { set(newV); }
        private void set(Object v) {
            if (layer < 0 || layer >= song.layers.size()) return;
            NbsSong.Layer l = song.layers.get(layer);
            switch (field) {
                case 0 -> l.name = (String) v;
                case 1 -> l.volume = (Integer) v;
                case 2 -> l.panning = (Integer) v;
                case 3 -> l.locked = (Boolean) v;
            }
        }
    }

    /** 结构级编辑：整曲快照双向恢复（导入替换 / 删层）。 */
    private final class SnapshotEdit implements Edit {
        private final NbsSong before, after;
        SnapshotEdit(NbsSong before, NbsSong after) { this.before = before; this.after = after; }
        @Override public void undo() { song.copyFrom(before); }
        @Override public void redo() { song.copyFrom(after); }
    }
}
