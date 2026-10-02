package io.github.y15173334444.create_schematic_compute.graph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NBS 曲目解析/写出/编辑测试：v5 往返、稀疏 tick/layer 增量编码、按规范手编码的解析校验、
 * 编辑操作与层删除重编号。
 * NbsSong tests: v5 round trip, sparse tick/layer delta encoding, spec-faithful parse of a
 * hand-encoded stream, edit ops and layer-removal reindexing.
 */
class NbsSongTest {

    /** 手工按官方规范拼一段 LE 字节。 */
    private static final class Enc {
        final ByteArrayOutputStream b = new ByteArrayOutputStream();
        Enc u8(int v) { b.write(v & 0xFF); return this; }
        Enc i16(int v) { b.write(v & 0xFF); b.write((v >>> 8) & 0xFF); return this; }
        Enc i32(int v) { b.write(v & 0xFF); b.write((v >>> 8) & 0xFF); b.write((v >>> 16) & 0xFF); b.write((v >>> 24) & 0xFF); return this; }
        Enc str(String s) { byte[] x = s.getBytes(StandardCharsets.UTF_8); i32(x.length); b.write(x, 0, x.length); return this; }
        byte[] bytes() { return b.toByteArray(); }
    }

    /** 按规范手编码一首最小 v5（1 音符 + 1 层），独立于 writer，用于校验 parser 严格对照规范。
     *  声像字节按官方规范传**有符号值**（−100..+100，0=居中），方法内转成字节。 */
    private static byte[] handEncodedV5(int notePanningSigned, int layerPanningSigned) {
        return new Enc()
            .i16(0)                 // zero marker
            .u8(5)                  // version
            .u8(16)                 // vanilla instrument count
            .i16(10)                // song length (v3+)
            .i16(1)                 // layer count
            .str("A")               // name
            .str("")                // author
            .str("")                // original author
            .str("")                // description
            .i16(1000)              // tempo (×100)
            .u8(0)                  // auto-save
            .u8(1)                  // auto-save duration
            .u8(4)                  // time signature
            .i32(7).i32(8).i32(9).i32(10).i32(11)   // minutes/left/right/added/removed
            .str("")                // import name
            .u8(1)                  // loop on (v4+)
            .u8(2)                  // max loop count (v4+)
            .i16(3)                 // loop start tick (v4+)
            // notes: one note at tick 0, layer 0
            .i16(1)                 // tick jump (-1 -> 0)
            .i16(1)                 // layer jump (-1 -> 0)
            .u8(5).u8(45).u8(80).u8(notePanningSigned).i16(300)   // instrument,key,velocity,panning,pitch (v4+)
            .i16(0)                 // end layers of tick
            .i16(0)                 // end notes
            // layers: one
            .str("L").u8(1).u8(100).u8(layerPanningSigned)          // name, lock(v4+), volume, panning(v2+)
            // custom instruments: none
            .u8(0)
            .bytes();
    }

    private static byte[] handEncodedV5() {
        return handEncodedV5(0, 0);   // 音符/层声像都居中（字节 0）
    }

    @Test
    @DisplayName("按规范手编码的 v5 解析出预期字段 / hand-encoded v5 parses to expected fields")
    void parseHandEncodedV5() {
        NbsSong s = NbsSong.read(handEncodedV5());
        assertEquals(5, s.version);
        assertEquals("A", s.songName);
        assertEquals(10, s.songLength);
        assertEquals(1000, s.tempo);
        assertTrue(s.loop);
        assertEquals(2, s.maxLoopCount);
        assertEquals(3, s.loopStartTick);
        assertEquals(7, s.minutesSpent);
        // note
        NbsSong.Note n = s.getNote(0, 0);
        assertNotNull(n);
        assertEquals(5, n.instrument);
        assertEquals(45, n.key);
        assertEquals(80, n.velocity);
        assertEquals(100, n.panning);   // 文件字节 0 = 居中 → 内存 100
        assertEquals(300, n.pitch);
        // layer
        assertEquals(1, s.layers.size());
        assertEquals("L", s.layers.get(0).name);
        assertTrue(s.layers.get(0).locked);
        assertEquals(100, s.layers.get(0).volume);
        assertEquals(100, s.layers.get(0).panning);   // 文件字节 0 = 居中 → 内存 100
    }

    @Test
    @DisplayName("声像契约：文件有符号字节 −100..+100 ↔ 内存 0–200 / panning: signed file byte ↔ internal 0–200")
    void panningSignedByteContract() {
        // 读：官方规范为有符号字节（−100 全左 / 0 居中 / +100 全右）
        assertEquals(0, NbsSong.read(handEncodedV5(-100, 0)).getNote(0, 0).panning,
            "file byte -100 (full left) must read as internal 0");
        assertEquals(200, NbsSong.read(handEncodedV5(100, 0)).getNote(0, 0).panning,
            "file byte +100 (full right) must read as internal 200");
        assertEquals(0, NbsSong.read(handEncodedV5(0, -100)).layers.get(0).panning,
            "layer panning follows the same signed-byte convention");

        // 写：内存极值往返（写侧换算由读侧契约传递钉死）
        for (int internal : new int[]{0, 100, 200}) {
            NbsSong s = new NbsSong();
            s.putNote(0, 0, 0, 45, 100, internal, 0);
            NbsSong r = NbsSong.read(s.write());
            assertEquals(internal, r.getNote(0, 0).panning,
                "internal " + internal + " must survive write/read");
        }
    }

    @Test
    @DisplayName("v5 写出后往返一致 / v5 write-read round trip preserves data")
    void roundTrip() {
        NbsSong s = new NbsSong();
        s.songName = "Round";
        s.songAuthor = "AU";
        s.originalAuthor = "OA";
        s.description = "DESC";
        s.tempo = 1250;
        s.songLength = 20;
        s.loop = true;
        s.maxLoopCount = 5;
        s.loopStartTick = 4;
        s.timeSignature = 3;
        s.importName = "orig.mid";
        s.layers.clear();
        NbsSong.Layer l0 = new NbsSong.Layer();
        l0.name = "Bass"; l0.locked = true; l0.volume = 77; l0.panning = 33;
        s.layers.add(l0);
        NbsSong.Layer l1 = new NbsSong.Layer();
        l1.name = "Lead"; l1.volume = 100; l1.panning = 200;
        s.layers.add(l1);
        s.putNote(0, 0, 2, 33, 100, 100, 0);
        s.putNote(0, 1, 7, 57, 90, 50, -1200);
        s.putNote(50, 0, 15, 45, 55, 150, 600);   // sparse tick gap
        s.putNote(51, 1, 16, 40, 40, 200, 0);     // custom instrument index
        NbsSong.CustomInstrument ci = new NbsSong.CustomInstrument();
        ci.name = "Glock"; ci.soundFile = "glock.ogg"; ci.key = 48; ci.pressKey = true;
        s.customInstruments.add(ci);

        byte[] bytes = s.write();
        NbsSong r = NbsSong.read(bytes);
        assertEquals(s, r, "v5 round trip must preserve all fields");
        assertEquals(4, r.noteCount());
        // sparse ticks preserved
        assertNotNull(r.getNote(50, 0));
        assertNotNull(r.getNote(51, 1));
        assertEquals(1, r.customInstruments.size());
        assertEquals("Glock", r.customInstruments.get(0).name);
    }

    @Test
    @DisplayName("写出恒为 v5（含 velocity/panning/pitch）/ writer emits v5 with per-note fields")
    void writeIsV5() {
        NbsSong s = new NbsSong();
        s.putNote(0, 0, 3, 40, 100, 100, 0);
        NbsSong r = NbsSong.read(s.write());
        assertEquals(5, r.version);
        // per-note fields survive -> they were written (v4+)
        NbsSong.Note n = r.getNote(0, 0);
        assertEquals(100, n.velocity);
        assertEquals(100, n.panning);
    }

    @Test
    @DisplayName("编辑操作：增删移与层删除重编号 / edit ops: put-remove-move and layer-removal reindex")
    void editOps() {
        NbsSong s = new NbsSong();
        s.layers.clear();
        s.ensureLayerIndex(2);            // layers 0..2
        assertEquals(3, s.layers.size());

        s.putNote(5, 2, 1, 33, 100, 100, 0);
        assertNotNull(s.getNote(5, 2));
        assertEquals(1, s.noteCount());

        s.moveNote(5, 2, 10, 1);
        assertNull(s.getNote(5, 2));
        assertNotNull(s.getNote(10, 1));
        assertEquals(1, s.noteCount());

        assertTrue(s.removeNote(10, 1));
        assertEquals(0, s.noteCount());

        // removeLayer reindex: notes above the removed layer shift down
        s.layers.clear();
        s.ensureLayerIndex(2);            // 0,1,2
        s.putNote(0, 0, 1, 33, 100, 100, 0);
        s.putNote(0, 1, 1, 34, 100, 100, 0);
        s.putNote(0, 2, 1, 35, 100, 100, 0);
        s.removeLayer(0);
        assertEquals(2, s.layers.size());
        assertEquals(2, s.noteCount());
        // layer-0 note dropped; old layer1 -> 0 (key 34), old layer2 -> 1 (key 35)
        assertNotNull(s.getNote(0, 0));
        assertEquals(34, s.getNote(0, 0).key);
        assertNotNull(s.getNote(0, 1));
        assertEquals(35, s.getNote(0, 1).key);
        assertNull(s.getNote(0, 2));
    }

    @Test
    @DisplayName("maxTick/computeLength / length computation")
    void length() {
        NbsSong s = new NbsSong();
        assertEquals(-1, s.maxTick());
        assertEquals(0, s.computeLength());
        s.putNote(0, 0, 0, 33, 100, 100, 0);
        s.putNote(9, 0, 0, 33, 100, 100, 0);
        assertEquals(9, s.maxTick());
        assertEquals(10, s.computeLength());
    }
}
