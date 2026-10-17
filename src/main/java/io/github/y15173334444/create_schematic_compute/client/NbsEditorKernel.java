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

    /** 逐音符属性字段（B3）：力度 / 声像 / 细音高。 / per-note fields (B3): velocity / panning / fine pitch. */
    public static final int FIELD_VELOCITY = 1, FIELD_PANNING = 2, FIELD_PITCH = 3;

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

    /**
     * 改音符音高（悬停滚轮微调，社区习惯）：保力度/声像/细音高，只换键。
     * 键限 0–87；同键 = 空操作不入撤销；锁定层拒绝。
     * <p>Change a note's key (hover-wheel micro-adjust, community habit): keeps velocity /
     * panning / fine pitch, swaps only the key. Key is limited to 0–87; the same key is a
     * no-op; locked layers refuse.</p>
     */
    public void changeNoteKey(int tick, int layer, int key) {
        if (layer < 0 || layer >= song.layers.size()) return;
        if (song.layers.get(layer).locked) return;
        if (key < 0 || key > 87) return;
        NbsSong.Note old = song.getNote(tick, layer);
        if (old == null || old.key == key) return;
        apply(new CellEdit(tick, layer, copyOf(old),
            new NbsSong.Note(tick, layer, old.instrument, key, old.velocity, old.panning, old.pitch)));
    }

    /**
     * 层序器落点（社区 {@code add_block_manual}/{@code change_block_manual} 合一）：
     * 空格放新音符、已有则整格覆盖为指定属性（乐器/键/力度/声像/细音高）——覆盖不保留旧属性，
     * 这是层序器视图的习惯语义（工具态写格子）；属性完全相同 = 空操作不入撤销。
     * 层锁定不做；键限 0–87，力度 0–100、声像 0–200、细音高 −32768..32767 钳位。
     * <p>Lane-view placement (community {@code add_block_manual}/{@code change_block_manual}
     * in one): an empty cell gets a fresh note, an occupied cell is <b>overwritten</b> with
     * the given attributes (the tool state writes the cell — no attribute carry-over, that is
     * the lane-view habit); an identical note is a no-op. Locked layers are never edited;
     * key is limited to 0–87, velocity/panning/fine-pitch are clamped.</p>
     */
    public void placeNote(int tick, int layer, int instrument, int key, int velocity, int panning, int pitch) {
        if (layer < 0 || layer >= song.layers.size()) return;
        if (song.layers.get(layer).locked) return;
        if (key < 0 || key > 87) return;
        NbsSong.Note old = song.getNote(tick, layer);
        int v = clamp(velocity, 0, 100), p = clamp(panning, 0, 200), pi = clamp(pitch, -32768, 32767);
        if (old != null && old.instrument == instrument && old.key == key
            && old.velocity == v && old.panning == p && old.pitch == pi) return;
        apply(new CellEdit(tick, layer, old == null ? null : copyOf(old),
            new NbsSong.Note(tick, layer, instrument, key, v, p, pi)));
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

    /** 层重排（上移/下移，NBS 层序语义）：from → to，其余层顺移、音符随行。
     *  结构级 → 整曲快照撤销。 / Reorder layers (move up/down, the NBS order semantics):
     *  from → to with the rest shifting and notes following. Snapshot undo. */
    public void moveLayer(int from, int to) {
        if (from < 0 || from >= song.layers.size()) return;
        if (to < 0 || to >= song.layers.size() || from == to) return;
        NbsSong before = song.copy();
        song.moveLayer(from, to);
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

    // ══════════════ 选区级操作（批次三 B1）/ selection-level ops (batch 3 B1) ══════════════
    // 选区级操作改的是成片音符，走整曲快照撤销（plan B1：SnapshotEdit 绝对值无条件安全）。
    // Selection-level ops touch note spans — snapshot undo (plan B1: absolute-state safe).

    /**
     * 删除选中音符。锁定层不删（锁定层永不被编辑）；返回删除数，0 = 空操作不入撤销。
     * Delete the selected notes. Locked layers are never edited; returns the count,
     * 0 = no-op (nothing recorded).
     */
    public int deleteSelection(NbsSelection sel) {
        List<int[]> targets = unlockedSelected(sel);
        if (targets.isEmpty()) return 0;
        NbsSong before = song.copy();
        for (int[] c : targets) song.removeNote(c[0], c[1]);
        NbsSong after = song.copy();
        push(new SnapshotEdit(before, after));
        return targets.size();
    }

    /**
     * 复制选中音符为片段（只读，不入撤销）；无内容返回 null。
     * Copy the selected notes into a fragment (read-only); null when there is nothing.
     */
    public NbsClipboard copySelection(NbsSelection sel) {
        NbsClipboard clip = NbsClipboard.fromSelection(song, sel);
        return clip.isEmpty() ? null : clip;
    }

    /**
     * 剪切 = 复制片段 + 删除选中（一次快照撤销）；无内容返回 null。
     * Cut = copy the fragment + delete the selection (one snapshot undo); null when
     * there is nothing.
     */
    public NbsClipboard cutSelection(NbsSelection sel) {
        NbsClipboard clip = copySelection(sel);
        if (clip == null) return null;
        deleteSelection(sel);
        return clip;
    }

    /**
     * 粘贴片段：片段左上角对齐 (atTick, atLayer)，整格覆盖（层列落点同语义）。
     * 不建层（目标层不存在即跳过）、锁定层跳过；键限 0–87（越界跳过）、力度/声像/细音高钳位。
     * 返回实际落格（作为新选区），空 = 空操作不入撤销。
     * <p>Paste the fragment with its top-left anchored at (atTick, atLayer), overwriting
     * whole cells (the lane-placement semantics). Never creates layers (entries past the
     * last layer are skipped), skips locked layers; keys outside 0–87 are skipped,
     * velocity / panning / fine pitch clamp. Returns the cells actually placed (as the
     * new selection); empty = no-op.</p>
     */
    public NbsSelection paste(NbsClipboard clip, int atTick, int atLayer) {
        NbsSelection placed = new NbsSelection();
        if (clip == null || clip.isEmpty()) return placed;
        List<int[]> targets = new ArrayList<>();
        for (NbsClipboard.Entry e : clip.entries()) {
            int t = atTick + e.dTick, l = atLayer + e.dLayer;
            if (t < 0 || l < 0 || l >= song.layers.size()) continue;
            if (song.layers.get(l).locked) continue;
            if (e.key < 0 || e.key > 87) continue;
            targets.add(new int[]{t, l, e.instrument, e.key,
                clamp(e.velocity, 0, 100), clamp(e.panning, 0, 200), clamp(e.pitch, -32768, 32767)});
        }
        if (targets.isEmpty()) return placed;
        NbsSong before = song.copy();
        for (int[] a : targets) {
            song.putNote(a[0], a[1], a[2], a[3], a[4], a[5], a[6]);
            placed.toggleCell(song, a[0], a[1]);
        }
        NbsSong after = song.copy();
        push(new SnapshotEdit(before, after));
        return placed;
    }

    /** 选中且未锁定层的格（删除/剪切的实际目标；选后被锁的层自动豁免）。
     *  Selected cells on unlocked layers (the delete/cut targets — a layer locked after
     *  selection is exempt). */
    private List<int[]> unlockedSelected(NbsSelection sel) {
        List<int[]> out = new ArrayList<>();
        for (int[] c : sel.cells()) {
            if (c[1] < 0 || c[1] >= song.layers.size()) continue;
            if (song.layers.get(c[1]).locked) continue;
            if (song.getNote(c[0], c[1]) != null) out.add(c);
        }
        return out;
    }

    /**
     * 逐音符属性（B3）：字段 {@link #FIELD_VELOCITY}/{@link #FIELD_PANNING}/{@link #FIELD_PITCH}
     * 置绝对值（钳位：力度 0–100、声像 0–200、细音高 −32768..32767）。同值 = 空操作；
     * 锁定层拒绝。精确逆撤销（{@link CellEdit}）。返回是否改了。
     * <p>Per-note field (B3): sets one of {@link #FIELD_VELOCITY} / {@link #FIELD_PANNING} /
     * {@link #FIELD_PITCH} absolutely (clamped: velocity 0–100, panning 0–200, fine pitch
     * −32768..32767). Same value = no-op; locked layers refuse. Precise-inverse undo.
     * Returns whether the note changed.</p>
     */
    public boolean setNoteField(int tick, int layer, int field, int value) {
        if (layer < 0 || layer >= song.layers.size()) return false;
        if (song.layers.get(layer).locked) return false;
        NbsSong.Note old = song.getNote(tick, layer);
        if (old == null) return false;
        int v = clampField(field, value);
        if (fieldValue(old, field) == v) return false;
        NbsSong.Note fresh = copyOf(old);
        setField(fresh, field, v);
        apply(new CellEdit(tick, layer, copyOf(old), fresh));
        return true;
    }

    /**
     * 选区级属性（B3，Set 弹窗）：{@code absolute} = 全部置 value，否则各自 + value（增量）。
     * 跳过锁定层格；整曲快照撤销（选区级同口径）。返回实际改了的格数（0 = 空操作）。
     * <p>Selection-wide field set (B3, the Set dialog): absolute = every note takes
     * value, relative = each note shifts by value. Locked-layer cells skip; snapshot
     * undo. Returns how many notes actually changed (0 = no-op).</p>
     */
    public int setSelectionField(NbsSelection sel, int field, int value, boolean absolute) {
        int step = clamp(value, -100000, 100000);      // 增量先钳，防 int 溢出 / clamp the step against overflow
        List<int[]> targets = unlockedSelected(sel);
        List<NbsSong.Note> changed = new ArrayList<>();
        List<Integer> newValues = new ArrayList<>();
        for (int[] c : targets) {
            NbsSong.Note n = song.getNote(c[0], c[1]);
            int v = clampField(field, absolute ? step : fieldValue(n, field) + step);
            if (v == fieldValue(n, field)) continue;
            changed.add(n);
            newValues.add(v);
        }
        if (changed.isEmpty()) return 0;
        NbsSong before = song.copy();
        for (int i = 0; i < changed.size(); i++) {
            NbsSong.Note n = changed.get(i);
            NbsSong.Note fresh = copyOf(n);
            setField(fresh, field, newValues.get(i));
            song.putNote(n.tick, n.layer, fresh.instrument, fresh.key,
                fresh.velocity, fresh.panning, fresh.pitch);
        }
        NbsSong after = song.copy();
        push(new SnapshotEdit(before, after));
        return changed.size();
    }

    private static int fieldValue(NbsSong.Note n, int field) {
        return switch (field) {
            case FIELD_VELOCITY -> n.velocity;
            case FIELD_PANNING -> n.panning;
            default -> n.pitch;
        };
    }

    private static void setField(NbsSong.Note n, int field, int v) {
        switch (field) {
            case FIELD_VELOCITY -> n.velocity = v;
            case FIELD_PANNING -> n.panning = v;
            default -> n.pitch = v;
        }
    }

    private static int clampField(int field, int v) {
        return switch (field) {
            case FIELD_VELOCITY -> clamp(v, 0, 100);
            case FIELD_PANNING -> clamp(v, 0, 200);
            default -> clamp(v, -32768, 32767);
        };
    }

    /**
     * 搬移音符（拖动，B2）：换 (tick, layer) 与键，保乐器/力度/声像/细音高。键钳 0–87；
     * 同格同键 = 空操作；同格换键 = 改音高（与悬停滚轮 {@link #changeNoteKey} 同语义）。
     * 源/目标层锁定或目标层越界拒绝（<b>不建层</b>）；目标格被占 = 整格覆盖（精确逆
     * 还原被占音符）。返回是否搬了。
     * <p>Move a note (drag, B2): relocates (tick, layer) and key, keeping instrument /
     * velocity / panning / fine pitch. Keys clamp to 0–87; same cell + same key is a
     * no-op; same cell + new key is a retune (same semantics as the hover wheel
     * {@link #changeNoteKey}). Locked or out-of-range source/target layers refuse
     * (<b>no layer creation</b>); an occupied target is overwritten (the precise inverse
     * restores the victim). Returns whether the note moved.</p>
     */
    public boolean moveNote(int fromTick, int fromLayer, int toTick, int toLayer, int key) {
        if (fromLayer < 0 || fromLayer >= song.layers.size()) return false;
        if (toLayer < 0 || toLayer >= song.layers.size()) return false;
        if (song.layers.get(fromLayer).locked || song.layers.get(toLayer).locked) return false;
        NbsSong.Note old = song.getNote(fromTick, fromLayer);
        if (old == null) return false;
        int k = clamp(key, 0, 87);
        if (fromTick == toTick && fromLayer == toLayer && old.key == k) return false;
        NbsSong.Note victim = song.getNote(toTick, toLayer);
        NbsSong.Note oldCopy = copyOf(old), victimCopy = victim == null ? null : copyOf(victim);
        apply(new MoveEdit(fromTick, fromLayer, toTick, toLayer, oldCopy, victimCopy,
            new NbsSong.Note(toTick, toLayer, old.instrument, k, old.velocity, old.panning, old.pitch)));
        return true;
    }

    /**
     * 搬移选区（拖动选中音符，B2）：整体位移 (dTick, dLayer) + 键位移 dKey（逐音符钳 0–87）。
     * 任一目标层锁定/越界 → <b>整体拒绝</b>（部分落格不可预期）；目标格被占整格覆盖。
     * 结构级 → 整曲快照撤销（选区级操作同口径）。返回搬移后的选区（空 = 未搬）。
     * <p>Move the selection (drag the selected notes, B2): a uniform (dTick, dLayer)
     * shift plus a per-note key shift clamped to 0–87. Any locked / out-of-range target
     * layer refuses the <b>whole</b> move (partial drops are unpredictable); occupied
     * targets are overwritten. Snapshot undo (the selection-op rule). Returns the moved
     * selection (empty = nothing moved).</p>
     */
    public NbsSelection moveSelection(NbsSelection sel, int dTick, int dLayer, int dKey) {
        List<int[]> cs = sel.cells();
        List<NbsSong.Note> moving = new ArrayList<>();
        for (int[] c : cs) {
            NbsSong.Note n = song.getNote(c[0], c[1]);
            if (n != null) moving.add(n);
        }
        if (moving.isEmpty()) return new NbsSelection();
        if (dTick == 0 && dLayer == 0 && dKey == 0) return new NbsSelection();
        for (NbsSong.Note n : moving) {
            int tl = n.layer + dLayer;
            if (tl < 0 || tl >= song.layers.size()) return new NbsSelection();     // 不建层 / no layer creation
            if (song.layers.get(tl).locked) return new NbsSelection();
        }
        NbsSong before = song.copy();
        for (NbsSong.Note n : moving) song.removeNote(n.tick, n.layer);
        NbsSelection moved = new NbsSelection();
        for (NbsSong.Note n : moving) {
            int t = n.tick + dTick, l = n.layer + dLayer;
            song.putNote(t, l, n.instrument, clamp(n.key + dKey, 0, 87), n.velocity, n.panning, n.pitch);
            moved.toggleCell(song, t, l);
        }
        NbsSong after = song.copy();
        push(new SnapshotEdit(before, after));
        return moved;
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

    /** 搬移编辑（B2）：源格 → 空、目标格 → 搬来的音符（目标旧音符精确还原）。
     *  Move edit (B2): source cell → empty, target cell → the moved note (the target's
     *  previous note is restored exactly on undo). */
    private final class MoveEdit implements Edit {
        private final int fromTick, fromLayer, toTick, toLayer;
        private final NbsSong.Note source, moved;
        private final NbsSong.Note victim;               // 目标格原音符（可空）/ the overwritten note
        MoveEdit(int fromTick, int fromLayer, int toTick, int toLayer,
                 NbsSong.Note source, NbsSong.Note victim, NbsSong.Note moved) {
            this.fromTick = fromTick; this.fromLayer = fromLayer;
            this.toTick = toTick; this.toLayer = toLayer;
            this.source = source; this.victim = victim; this.moved = moved;
        }
        @Override public void undo() {
            song.removeNote(toTick, toLayer);
            if (victim != null) song.putNote(toTick, toLayer, victim.instrument, victim.key,
                victim.velocity, victim.panning, victim.pitch);
            song.removeNote(fromTick, fromLayer);
            song.putNote(fromTick, fromLayer, source.instrument, source.key,
                source.velocity, source.panning, source.pitch);
        }
        @Override public void redo() {
            song.removeNote(fromTick, fromLayer);
            song.removeNote(toTick, toLayer);
            song.putNote(toTick, toLayer, moved.instrument, moved.key,
                moved.velocity, moved.panning, moved.pitch);
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
