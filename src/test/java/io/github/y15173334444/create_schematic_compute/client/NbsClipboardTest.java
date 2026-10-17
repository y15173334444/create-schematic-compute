package io.github.y15173334444.create_schematic_compute.client;

import io.github.y15173334444.create_schematic_compute.graph.NbsSong;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link NbsClipboard} 片段与社区压缩串编解码测试（无 MC bootstrap）。
 * Fragment plus community compressed-string codec tests.
 */
class NbsClipboardTest {

    private static NbsClipboard.Entry entry(int dt, int dl, int ins, int key, int vel, int pan, int pit) {
        return new NbsClipboard.Entry(dt, dl, ins, key, vel, pan, pit);
    }

    @Test
    void wireFormatIsTheCommunitySelectionCode() {
        // 参考实现逐字格式：列跳|行跳|乐器|键|力度|声像|细音高| … −1|；声像 0–200 原样
        // The reference's literal format: colJump|rowJump|ins|key|vel|pan|pit| … -1|;
        // panning 0-200 verbatim.
        NbsClipboard clip = NbsClipboard.of(List.of(entry(0, 0, 0, 0, 0, 0, 0)));
        assertEquals("0|0|0|0|0|0|0|-1|", clip.encodeRaw());
        assertEquals("0|0|0|0|0|0|0|-1|", clip.encode(), "too short / incompressible stays raw");

        NbsClipboard two = NbsClipboard.of(List.of(
            entry(0, 0, 3, 45, 100, 100, 0),
            entry(2, 1, 5, 50, 80, 120, -25)));
        assertEquals("0|0|3|45|100|100|0|-1|2|1|5|50|80|120|-25|-1|", two.encodeRaw(),
            "column/row gaps are distances from the previous note column / row");
    }

    @Test
    void decodeParsesTheReferenceFormat() {
        NbsClipboard clip = NbsClipboard.decode("0|0|3|45|100|100|0|-1|2|1|5|50|80|120|-25|-1|");
        assertNotNull(clip);
        List<NbsClipboard.Entry> es = clip.entries();
        assertEquals(2, es.size());
        assertEquals(0, es.get(0).dTick);
        assertEquals(0, es.get(0).dLayer);
        assertEquals(3, es.get(0).instrument);
        assertEquals(45, es.get(0).key);
        assertEquals(100, es.get(0).velocity);
        assertEquals(100, es.get(0).panning);
        assertEquals(0, es.get(0).pitch);
        assertEquals(2, es.get(1).dTick);
        assertEquals(1, es.get(1).dLayer);
        assertEquals(-25, es.get(1).pitch, "signed fine pitch round-trips");
        assertEquals(120, es.get(1).panning, "panning is the 0-200 convention on the wire");
    }

    @Test
    void encodeDecodeRoundTrips() {
        NbsClipboard clip = NbsClipboard.of(List.of(
            entry(0, 0, 0, 33, 100, 100, 0),
            entry(0, 2, 1, 45, 55, 180, 1200),
            entry(4, 0, 15, 87, 0, 0, -32767),
            entry(4, 1, 12, 60, 100, 200, 32767)));
        NbsClipboard back = NbsClipboard.decode(clip.encode());
        assertNotNull(back);
        assertEquals(clip.entries(), back.entries(), "entries are value-compared");
    }

    @Test
    void largeSelectionsUseTheCompressedForm() {
        // 重复结构压得动 → 编码无 "|"（base64 字符集），解码还回原片段（参考 try_compress 语义）
        // Repetitive content compresses: the code carries no "|" (base64), and decodes back.
        NbsSong s = new NbsSong();
        NbsSelection sel = new NbsSelection();
        for (int t = 0; t < 40; t++) {
            s.putNote(t, 0, t % 16, 33 + (t % 25), 100, 100, 0);
            sel.toggleCell(s, t, 0);
        }
        NbsClipboard clip = NbsClipboard.fromSelection(s, sel);
        String wire = clip.encode();
        assertTrue(wire.indexOf('|') < 0, "compressed form is base64, no pipes");
        NbsClipboard back = NbsClipboard.decode(wire);
        assertNotNull(back);
        assertEquals(clip.entries(), back.entries());
    }

    @Test
    void fromSelectionNormalisesToTheBoundingOrigin() {
        NbsSong s = new NbsSong();
        s.addLayer();
        s.putNote(5, 1, 2, 50, 90, 150, 25);
        s.putNote(8, 0, 3, 60, 80, 120, -5);
        NbsSelection sel = new NbsSelection();
        sel.selectRect(s, 0, 20, 0, 5, NbsSelection.Mode.REPLACE);
        NbsClipboard clip = NbsClipboard.fromSelection(s, sel);
        List<NbsClipboard.Entry> es = clip.entries();
        assertEquals(2, es.size());
        // 原点 = 最小 tick/layer：(5,1) → (0,1)、(8,0) → (3,0)；排序按 (dTick, dLayer)
        // Origin = min tick/layer: (5,1) → (0,1), (8,0) → (3,0); sorted by (dTick, dLayer).
        assertEquals(0, es.get(0).dTick);
        assertEquals(1, es.get(0).dLayer);
        assertEquals(2, es.get(0).instrument);
        assertEquals(3, es.get(1).dTick);
        assertEquals(0, es.get(1).dLayer);
        assertEquals(3, es.get(1).instrument);
    }

    @Test
    void garbageDecodesToNull() {
        assertNull(NbsClipboard.decode(null));
        assertNull(NbsClipboard.decode(""));
        assertNull(NbsClipboard.decode("   "));
        assertNull(NbsClipboard.decode("not|a|code"));
        assertNull(NbsClipboard.decode("0|0|3|45"), "unterminated column");
        assertNull(NbsClipboard.decode("0|0|3|45|100|100|0|-1|junk"), "non-numeric tail");
        assertNull(NbsClipboard.decode("aGVsbG8gd29ybGQ="), "base64 that is not zlib");
    }
}
