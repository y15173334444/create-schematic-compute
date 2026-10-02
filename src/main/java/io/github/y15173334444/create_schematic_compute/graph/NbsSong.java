package io.github.y15173334444.create_schematic_compute.graph;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 一首 NBS 歌曲的完整数据 + 解析/写出/编辑操作。**纯函数，无 Minecraft 依赖**（可单测）。
 * <p>A complete NBS song's data plus parse / write / edit operations. Pure, no
 * Minecraft dependencies (unit-testable).</p>
 * <p>格式来源：Note Block Studio 官方 .nbs 规范（noteblock.studio/nbs）。版本条件字段
 * 逐条按规范处理：</p>
 * <ul>
 *   <li>头部 {@code songLength}：仅 v3+ 读写（v1 新格式头部未含、v2 缺失、v3 restored）。</li>
 *   <li>循环三项（loopOn/maxLoopCount/loopStartTick）：v4+。</li>
 *   <li>音符 velocity/panning/pitch 与层 lock：v4+；层 panning：v2+。声像在文件里是
 *       <b>有符号字节 −100..+100、0=居中</b>（OpenNBS 规范）；内存约定 0–200、100=居中，
 *       读写时在本类 I/O 边界换算。</li>
 *   <li>自定义乐器计数：unsigned byte（0–240）。</li>
 *   <li>字符串：32 位（int，小端）长度前缀 + 字节。</li>
 * </ul>
 * <p>导入读 v1–v5，写出恒为 v5（D9）。自定义乐器段导入保留、导出不丢（D9/Eval §四.2）。</p>
 * <p><b>键位窗口</b>（Eval §四.1）：NBS 键 0–87；精确发声窗口为 33–57（两八度），窗口外由
 * 播放层钳制到音符盒 0–24，但<b>保留原键数据</b>不回写破坏曲目。</p>
 */
public final class NbsSong {

    /** 曲目字节大小上限（plan §3.5：超限拒存并提示）。
     *  Song byte-size cap (plan §3.5: oversized songs are rejected with a prompt).
     *  TODO(v1.2.6-test): 临时 1024 KB 供高密度曲目压测，测毕回 256 KB。 */
    public static final int MAX_BYTES = 1024 * 1024;

    /** 音符：一个 (tick, layer) 单元。字段均存原始 NBS 值（不做任何播放钳制）。
     *  A note in one (tick, layer) cell. Values are raw NBS (no playback clamping). */
    public static final class Note {
        public int tick;
        public int layer;
        /** 乐器索引：0–15 原版，≥16 自定义。 */
        public int instrument;
        /** 键：0–87（A0..C8）；精确窗口 33–57。 */
        public int key;
        /** 力度：0–100（v4+；v1–v3 读入置 100）。 */
        public int velocity = 100;
        /** 声像：0–200，100 = 居中（v4+；v1–v3 读入置 100）。文件字节为 −100..+100 有符号，I/O 边界换算。 */
        public int panning = 100;
        /** 细音高 cents：−32768..32767（v4+；v1–v3 读入置 0）。 */
        public int pitch;

        public Note() {}

        public Note(int tick, int layer, int instrument, int key, int velocity, int panning, int pitch) {
            this.tick = tick; this.layer = layer; this.instrument = instrument; this.key = key;
            this.velocity = velocity; this.panning = panning; this.pitch = pitch;
        }
    }

    /** 层：名称/锁定/音量/声像。 */
    public static final class Layer {
        public String name = "";
        public boolean locked;
        /** 音量：0–100。 */
        public int volume = 100;
        /** 声像：0–200，100 = 居中。 */
        public int panning = 100;

        public Layer() {}
    }

    /** 自定义乐器：MVP 只保留数据不发声（Eval §四.2）。 */
    public static final class CustomInstrument {
        public String name = "";
        public String soundFile = "";
        /** 基准键：0–87，默认 45（F#4）。 */
        public int key = 45;
        public boolean pressKey;

        public CustomInstrument() {}
    }

    // ── 头部字段 / header fields ────────────────────────────────────────────
    /** 仅记录源版本（1–5）；write() 恒写出 v5。 */
    public int version = 5;
    public int vanillaInstrumentCount = 16;
    /** 曲长（tick）。v3+。 */
    public int songLength;
    public String songName = "";
    public String songAuthor = "";
    public String originalAuthor = "";
    public String description = "";
    /** 速度 = 每秒 tick × 100（默认 10 tick/s → 1000）。 */
    public int tempo = 1000;
    public boolean autoSave;
    public int autoSaveDuration = 1;
    public int timeSignature = 4;
    public int minutesSpent;
    public int leftClicks;
    public int rightClicks;
    public int noteBlocksAdded;
    public int noteBlocksRemoved;
    public String importName = "";
    public boolean loop;
    public int maxLoopCount;
    public int loopStartTick;

    // ── 数据面 / data ─────────────────────────────────────────────────────
    /** 音符表：pack(tick,layer) → Note。TreeMap 保证按 tick→layer 有序（写出即稀疏编码序）。 */
    private final TreeMap<Long, Note> notes = new TreeMap<>();
    public final List<Layer> layers = new ArrayList<>();
    public final List<CustomInstrument> customInstruments = new ArrayList<>();

    /** 默认一首空曲（1 层）。 */
    public NbsSong() {
        layers.add(new Layer());
    }

    // ── 键 / key packing ─────────────────────────────────────────────────
    private static long pack(int tick, int layer) {
        return (((long) tick) << 32) | (layer & 0xFFFFFFFFL);
    }

    // ── 查询 / queries ───────────────────────────────────────────────────
    /** 取 (tick,layer) 音符，无则 null。 */
    public Note getNote(int tick, int layer) {
        return notes.get(pack(tick, layer));
    }

    /** 该 tick 上的全部音符（按 layer 升序）。 */
    public List<Note> notesAtTick(int tick) {
        List<Note> out = new ArrayList<>();
        // 上界 pack(tick,-1)=tick<<32|0xFFFFFFFF：覆盖该 tick 所有层，且不含下一 tick。
        Map<Long, Note> sub = notes.subMap(pack(tick, 0), true, pack(tick, -1), true);
        for (Note n : sub.values()) out.add(n);
        return out;
    }

    /** 全部音符（有序）。 */
    public List<Note> allNotes() {
        return new ArrayList<>(notes.values());
    }

    public int noteCount() { return notes.size(); }

    /** 最大 tick（无音符返回 −1）。 */
    public int maxTick() {
        return notes.isEmpty() ? -1 : (int) (notes.lastKey() >>> 32);
    }

    // ── 编辑操作 / edit ops ──────────────────────────────────────────────
    /** 写入/替换一个音符（覆盖该单元格）。返回写入的音符。 */
    public Note putNote(int tick, int layer, int instrument, int key, int velocity, int panning, int pitch) {
        ensureLayerIndex(layer);
        Note n = new Note(tick, layer, instrument, key, velocity, panning, pitch);
        notes.put(pack(tick, layer), n);
        return n;
    }

    /** 删除 (tick,layer) 音符。返回是否删除了内容。 */
    public boolean removeNote(int tick, int layer) {
        return notes.remove(pack(tick, layer)) != null;
    }

    /** 移动音符到新 (tick,layer)；若目标已有音符则替换。 */
    public Note moveNote(int fromTick, int fromLayer, int toTick, int toLayer) {
        Note n = notes.remove(pack(fromTick, fromLayer));
        if (n == null) return null;
        ensureLayerIndex(toLayer);
        n.tick = toTick; n.layer = toLayer;
        notes.put(pack(toTick, toLayer), n);
        return n;
    }

    /** 保证 layers 列表至少容纳索引 layer（扩容补空层）。 */
    public void ensureLayerIndex(int layer) {
        while (layers.size() <= layer) layers.add(new Layer());
    }

    /** 追加一层，返回其索引。 */
    public int addLayer() {
        layers.add(new Layer());
        return layers.size() - 1;
    }

    /** 删除一层并把后续层前移（连带其音符重编号）。 */
    public void removeLayer(int index) {
        if (index < 0 || index >= layers.size()) return;
        layers.remove(index);
        TreeMap<Long, Note> rebuilt = new TreeMap<>();
        for (Note n : notes.values()) {
            if (n.layer == index) continue;          // drop notes on the removed layer
            if (n.layer > index) n.layer--;
            rebuilt.put(pack(n.tick, n.layer), n);
        }
        notes.clear();
        notes.putAll(rebuilt);
    }

    /** 曲长（tick）= 音符末 tick + 1；无音符为 0。 */
    public int computeLength() {
        return notes.isEmpty() ? 0 : maxTick() + 1;
    }

    /** 深拷贝（节点复制/粘贴用）。Deep copy for node copy/paste. */
    public NbsSong copy() {
        NbsSong s = new NbsSong();
        s.layers.clear();
        s.version = version;
        s.vanillaInstrumentCount = vanillaInstrumentCount;
        s.songLength = songLength;
        s.songName = songName;
        s.songAuthor = songAuthor;
        s.originalAuthor = originalAuthor;
        s.description = description;
        s.tempo = tempo;
        s.autoSave = autoSave;
        s.autoSaveDuration = autoSaveDuration;
        s.timeSignature = timeSignature;
        s.minutesSpent = minutesSpent;
        s.leftClicks = leftClicks;
        s.rightClicks = rightClicks;
        s.noteBlocksAdded = noteBlocksAdded;
        s.noteBlocksRemoved = noteBlocksRemoved;
        s.importName = importName;
        s.loop = loop;
        s.maxLoopCount = maxLoopCount;
        s.loopStartTick = loopStartTick;
        for (Note n : notes.values()) {
            Note m = new Note(n.tick, n.layer, n.instrument, n.key, n.velocity, n.panning, n.pitch);
            s.notes.put(pack(n.tick, n.layer), m);
        }
        for (Layer l : layers) {
            Layer c = new Layer();
            c.name = l.name; c.locked = l.locked; c.volume = l.volume; c.panning = l.panning;
            s.layers.add(c);
        }
        for (CustomInstrument ci : customInstruments) {
            CustomInstrument c = new CustomInstrument();
            c.name = ci.name; c.soundFile = ci.soundFile; c.key = ci.key; c.pressKey = ci.pressKey;
            s.customInstruments.add(c);
        }
        return s;
    }

    /** 就地以 other 的内容整体覆盖本曲（结构级撤销/导入替换用；等价于 {@code copy()} 的反向）。
     *  Overwrite this song in place with other's content (structural undo / import replace). */
    public void copyFrom(NbsSong other) {
        notes.clear();
        layers.clear();
        customInstruments.clear();
        version = other.version;
        vanillaInstrumentCount = other.vanillaInstrumentCount;
        songLength = other.songLength;
        songName = other.songName;
        songAuthor = other.songAuthor;
        originalAuthor = other.originalAuthor;
        description = other.description;
        tempo = other.tempo;
        autoSave = other.autoSave;
        autoSaveDuration = other.autoSaveDuration;
        timeSignature = other.timeSignature;
        minutesSpent = other.minutesSpent;
        leftClicks = other.leftClicks;
        rightClicks = other.rightClicks;
        noteBlocksAdded = other.noteBlocksAdded;
        noteBlocksRemoved = other.noteBlocksRemoved;
        importName = other.importName;
        loop = other.loop;
        maxLoopCount = other.maxLoopCount;
        loopStartTick = other.loopStartTick;
        for (Note n : other.notes.values()) {
            Note m = new Note(n.tick, n.layer, n.instrument, n.key, n.velocity, n.panning, n.pitch);
            notes.put(pack(n.tick, n.layer), m);
        }
        for (Layer l : other.layers) {
            Layer c = new Layer();
            c.name = l.name; c.locked = l.locked; c.volume = l.volume; c.panning = l.panning;
            layers.add(c);
        }
        for (CustomInstrument ci : other.customInstruments) {
            CustomInstrument c = new CustomInstrument();
            c.name = ci.name; c.soundFile = ci.soundFile; c.key = ci.key; c.pressKey = ci.pressKey;
            customInstruments.add(c);
        }
    }

    // ── 写出 / write (always v5) ─────────────────────────────────────────
    /**
     * 写出为 v5 .nbs 字节。恒为 v5（含音符 velocity/panning/pitch、层 lock、循环三项）。
     * Write as v5 .nbs bytes (always v5, D9).
     */
    public byte[] write() {
        return writeFull();
    }

    /** 完整写出（header + notes + layers + custom instruments）。 */
    private byte[] writeFull() {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(256);
        writeShort(bos, 0);
        bos.write(5);
        bos.write(vanillaInstrumentCount & 0xFF);
        writeShort(bos, songLength);
        writeShort(bos, layers.size());
        writeString(bos, songName);
        writeString(bos, songAuthor);
        writeString(bos, originalAuthor);
        writeString(bos, description);
        writeShort(bos, tempo);
        bos.write(autoSave ? 1 : 0);
        bos.write(autoSaveDuration & 0xFF);
        bos.write(timeSignature & 0xFF);
        writeInt(bos, minutesSpent);
        writeInt(bos, leftClicks);
        writeInt(bos, rightClicks);
        writeInt(bos, noteBlocksAdded);
        writeInt(bos, noteBlocksRemoved);
        writeString(bos, importName);
        bos.write(loop ? 1 : 0);
        bos.write(maxLoopCount & 0xFF);
        writeShort(bos, loopStartTick);

        // notes: group by tick (ascending), layers ascending within a tick
        int curTick = -1;
        int curLayer = -1;
        boolean inTick = false;
        for (Note n : notes.values()) {
            if (!inTick || n.tick != curTick) {
                if (inTick) writeShort(bos, 0);          // end previous tick's layer list
                writeShort(bos, n.tick - curTick);       // tick jump
                curTick = n.tick;
                curLayer = -1;
                inTick = true;
            }
            writeShort(bos, n.layer - curLayer);         // layer jump
            curLayer = n.layer;
            bos.write(n.instrument & 0xFF);
            bos.write(n.key & 0xFF);
            bos.write(n.velocity & 0xFF);                // v4+
            bos.write(panToByte(n.panning));             // v4+（0–200 → 有符号字节）
            writeShort(bos, n.pitch);                    // v4+
        }
        if (inTick) writeShort(bos, 0);                  // end last tick
        writeShort(bos, 0);                              // end note section

        // layers
        for (Layer l : layers) {
            writeString(bos, l.name);
            bos.write(l.locked ? 1 : 0);                 // v4+
            bos.write(l.volume & 0xFF);
            bos.write(panToByte(l.panning));             // v2+（0–200 → 有符号字节）
        }

        // custom instruments (count = unsigned byte)
        bos.write(customInstruments.size() & 0xFF);
        for (CustomInstrument c : customInstruments) {
            writeString(bos, c.name);
            writeString(bos, c.soundFile);
            bos.write(c.key & 0xFF);
            bos.write(c.pressKey ? 1 : 0);
        }
        return bos.toByteArray();
    }

    // ── 解析 / read (v1–v5) ─────────────────────────────────────────────
    /**
     * 解析 v1–v5 .nbs 字节。v0 经典格式抛 {@link IllegalArgumentException}（D9 遗留：暂不读）。
     * Parse v1–v5 .nbs bytes. v0 classic throws (not read in MVP).
     */
    public static NbsSong read(byte[] data) {
        if (data == null) throw new IllegalArgumentException("NBS: null data");
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        NbsSong s = new NbsSong();
        s.layers.clear();
        try {
            int first = buf.getShort() & 0xFFFF;
            if (first != 0) throw new IllegalArgumentException("NBS: classic (v0) format not supported");
            s.version = buf.get() & 0xFF;
            if (s.version < 1) throw new IllegalArgumentException("NBS: bad version " + s.version);
            s.vanillaInstrumentCount = buf.get() & 0xFF;
            if (s.version >= 3) s.songLength = buf.getShort() & 0xFFFF;
            int layerCount = buf.getShort() & 0xFFFF;
            s.songName = readString(buf);
            s.songAuthor = readString(buf);
            s.originalAuthor = readString(buf);
            s.description = readString(buf);
            s.tempo = buf.getShort() & 0xFFFF;
            s.autoSave = (buf.get() & 0xFF) != 0;
            s.autoSaveDuration = buf.get() & 0xFF;
            s.timeSignature = buf.get() & 0xFF;
            s.minutesSpent = buf.getInt();
            s.leftClicks = buf.getInt();
            s.rightClicks = buf.getInt();
            s.noteBlocksAdded = buf.getInt();
            s.noteBlocksRemoved = buf.getInt();
            s.importName = readString(buf);
            if (s.version >= 4) {
                s.loop = (buf.get() & 0xFF) != 0;
                s.maxLoopCount = buf.get() & 0xFF;
                s.loopStartTick = buf.getShort() & 0xFFFF;
            }

            // notes
            int curTick = -1;
            while (true) {
                int tickJump = buf.getShort() & 0xFFFF;
                if (tickJump == 0) break;
                curTick += tickJump;
                int curLayer = -1;
                while (true) {
                    int layerJump = buf.getShort() & 0xFFFF;
                    if (layerJump == 0) break;
                    curLayer += layerJump;
                    Note n = new Note();
                    n.tick = curTick;
                    n.layer = curLayer;
                    n.instrument = buf.get() & 0xFF;
                    n.key = buf.get() & 0xFF;
                    if (s.version >= 4) {
                        n.velocity = buf.get() & 0xFF;
                        n.panning = panFromByte(buf.get());   // 有符号字节 → 0–200
                        n.pitch = buf.getShort();
                    } else {
                        n.velocity = 100;
                        n.panning = 100;
                        n.pitch = 0;
                    }
                    s.notes.put(pack(n.tick, n.layer), n);
                }
            }

            // layers
            for (int i = 0; i < layerCount; i++) {
                Layer l = new Layer();
                l.name = readString(buf);
                if (s.version >= 4) l.locked = (buf.get() & 0xFF) != 0;
                l.volume = buf.get() & 0xFF;
                if (s.version >= 2) l.panning = panFromByte(buf.get());   // 有符号字节 → 0–200
                else l.panning = 100;
                s.layers.add(l);
            }

            // custom instruments (count = unsigned byte)
            if (buf.hasRemaining()) {
                int ciCount = buf.get() & 0xFF;
                for (int i = 0; i < ciCount; i++) {
                    CustomInstrument c = new CustomInstrument();
                    c.name = readString(buf);
                    c.soundFile = readString(buf);
                    c.key = buf.get() & 0xFF;
                    c.pressKey = (buf.get() & 0xFF) != 0;
                    s.customInstruments.add(c);
                }
            }

            // 若音符引用了超出 layerCount 的层，补层以免丢数据
            int maxLayer = -1;
            for (Note n : s.notes.values()) if (n.layer > maxLayer) maxLayer = n.layer;
            while (s.layers.size() <= maxLayer) s.layers.add(new Layer());
            if (s.songLength == 0) s.songLength = s.computeLength();
            return s;
        } catch (java.nio.BufferUnderflowException e) {
            throw new IllegalArgumentException("NBS: truncated or malformed data", e);
        }
    }

    // ── 底层 LE 读写 / little-endian primitives ──────────────────────────
    /** 文件字节（−100..+100 有符号，0=居中）→ 内存 0–200；越界钳到边缘。 */
    private static int panFromByte(byte raw) {
        return Math.max(0, Math.min(200, 100 + raw));
    }

    /** 内存 0–200 → 文件字节（−100..+100 有符号）；越界钳到边缘。 */
    private static int panToByte(int panning) {
        return Math.max(0, Math.min(200, panning)) - 100;
    }

    private static String readString(ByteBuffer buf) {
        int len = buf.getInt();
        if (len < 0 || len > buf.remaining()) throw new IllegalArgumentException("NBS: bad string length " + len);
        byte[] b = new byte[len];
        buf.get(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    private static void writeShort(ByteArrayOutputStream bos, int v) {
        bos.write(v & 0xFF);
        bos.write((v >>> 8) & 0xFF);
    }

    private static void writeInt(ByteArrayOutputStream bos, int v) {
        bos.write(v & 0xFF);
        bos.write((v >>> 8) & 0xFF);
        bos.write((v >>> 16) & 0xFF);
        bos.write((v >>> 24) & 0xFF);
    }

    private static void writeString(ByteArrayOutputStream bos, String s) {
        byte[] b = (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
        writeInt(bos, b.length);
        bos.write(b, 0, b.length);
    }

    // equals/hashCode for round-trip tests (semantic field compare)
    @Override public boolean equals(Object o) {
        if (!(o instanceof NbsSong other)) return false;
        return tempo == other.tempo && songLength == other.songLength
            && vanillaInstrumentCount == other.vanillaInstrumentCount
            && loop == other.loop && maxLoopCount == other.maxLoopCount && loopStartTick == other.loopStartTick
            && timeSignature == other.timeSignature
            && songName.equals(other.songName) && songAuthor.equals(other.songAuthor)
            && originalAuthor.equals(other.originalAuthor) && description.equals(other.description)
            && importName.equals(other.importName)
            && notesEqual(other) && layersEqual(other) && customEqual(other);
    }

    private boolean notesEqual(NbsSong o) {
        if (notes.size() != o.notes.size()) return false;
        for (Map.Entry<Long, Note> e : notes.entrySet()) {
            Note a = e.getValue(), b = o.notes.get(e.getKey());
            if (b == null) return false;
            if (a.instrument != b.instrument || a.key != b.key || a.velocity != b.velocity
                || a.panning != b.panning || a.pitch != b.pitch) return false;
        }
        return true;
    }

    private boolean layersEqual(NbsSong o) {
        if (layers.size() != o.layers.size()) return false;
        for (int i = 0; i < layers.size(); i++) {
            Layer a = layers.get(i), b = o.layers.get(i);
            if (a.locked != b.locked || a.volume != b.volume || a.panning != b.panning
                || !a.name.equals(b.name)) return false;
        }
        return true;
    }

    private boolean customEqual(NbsSong o) {
        if (customInstruments.size() != o.customInstruments.size()) return false;
        for (int i = 0; i < customInstruments.size(); i++) {
            CustomInstrument a = customInstruments.get(i), b = o.customInstruments.get(i);
            if (a.key != b.key || a.pressKey != b.pressKey
                || !a.name.equals(b.name) || !a.soundFile.equals(b.soundFile)) return false;
        }
        return true;
    }

    @Override public int hashCode() { return songName.hashCode() * 31 + notes.size(); }
}
