package io.github.y15173334444.create_schematic_compute.graph;

import io.github.y15173334444.create_schematic_compute.network.BlobDataPacket;
import io.github.y15173334444.create_schematic_compute.network.BlobRegistry;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 曲目写入/替换 op（SET_SONG）语义：blob 分片重组 → 引用 op 应用 → 坏数据/超限拒存。
 * Song write/replace op semantics: blob reassembly → referenced application →
 * malformed / oversized data is rejected without breaking the node.
 */
class SongWriteOpTest {

    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final BlockPos POS = new BlockPos(1, 2, 3);

    /** SET_SONG op；itemStack 置 null（不读它，避免依赖 Minecraft 的 ItemStack.EMPTY）。 */
    private static GraphOp setSongOp(int nodeId, int blobId) {
        return new GraphOp(OpType.SET_SONG, POS, -1, nodeId,
            0, null, 0f, 0f, 0, 0, 0, 0, 0, 0f,
            null, 0, 0, 0, 0, null, 0, 0, 0,
            null, 0L, ACTOR, blobId, null);
    }

    private static NbsSong sampleSong() {
        NbsSong s = new NbsSong();
        s.songName = "sample";
        s.tempo = 1200;
        s.putNote(0, 0, 3, 45, 100, 100, 0);
        s.putNote(4, 0, 3, 47, 100, 100, 0);
        return s;
    }

    /** 把字节按分片送进 BlobRegistry，返回重组句柄（同 SongSync 的上传契约）。 */
    private static int feedBlob(int blobId, byte[] bytes) {
        var chunks = BlobDataPacket.fromBytes(POS, blobId, 0, bytes);
        byte[] last = null;
        for (BlobDataPacket c : chunks) last = BlobRegistry.receive(c);
        assertNotNull(last, "all chunks fed → reassembly completes");
        assertArrayEquals(bytes, last);
        return blobId;
    }

    @Test
    void setSongAppliesReassembledBytes() {
        NodeGraph g = new NodeGraph();
        GraphNode music = g.addNode(NodeType.MUSIC, 0, 0);
        NbsSong original = music.song;
        assertNotNull(original);

        byte[] bytes = sampleSong().write();
        int blobId = feedBlob(4242, bytes);
        OpExecutor.apply(g, setSongOp(music.id, blobId));

        assertNotNull(music.song);
        assertNotSame(original, music.song, "apply installs a parsed copy");
        assertEquals("sample", music.song.songName);
        assertEquals(1200, music.song.tempo);
        assertEquals(2, music.song.noteCount());
        assertEquals(45, music.song.getNote(0, 0).key);
    }

    @Test
    void setSongIsTargetedAtMusicNodesOnly() {
        NodeGraph g = new NodeGraph();
        GraphNode other = g.addNode(NodeType.CONST, 0, 0);
        NbsSong before = sampleSong();
        int blobId = feedBlob(71, before.write());
        OpExecutor.apply(g, setSongOp(other.id, blobId));
        assertNull(other.song, "non-MUSIC nodes never take song data");
    }

    @Test
    void missingBlobLeavesSongUntouched() {
        NodeGraph g = new NodeGraph();
        GraphNode music = g.addNode(NodeType.MUSIC, 0, 0);
        music.song.putNote(1, 0, 0, 40, 100, 100, 0);
        NbsSong before = music.song;

        OpExecutor.apply(g, setSongOp(music.id, 999));
        assertSame(before, music.song, "no reassembled bytes → no mutation");
        assertEquals(1, music.song.noteCount());
    }

    @Test
    void malformedBytesAreRejectedKeepingOldSong() {
        NodeGraph g = new NodeGraph();
        GraphNode music = g.addNode(NodeType.MUSIC, 0, 0);
        music.song.putNote(1, 0, 0, 40, 100, 100, 0);
        NbsSong before = music.song;

        byte[] garbage = new byte[]{1, 2, 3, 4, 5, 6, 7, 8};
        int blobId = feedBlob(4243, garbage);
        OpExecutor.apply(g, setSongOp(music.id, blobId));
        assertSame(before, music.song, "parse failure keeps the old song");
        assertEquals(1, music.song.noteCount());
    }

    @Test
    void oversizeBytesAreRejected() {
        NodeGraph g = new NodeGraph();
        GraphNode music = g.addNode(NodeType.MUSIC, 0, 0);
        NbsSong before = music.song;

        byte[] huge = new byte[NbsSong.MAX_BYTES + 1];
        int blobId = feedBlob(4244, huge);
        OpExecutor.apply(g, setSongOp(music.id, blobId));
        assertSame(before, music.song, "songs above the cap are refused");
    }

    @Test
    void blobRefIsConsumedOnce() {
        NodeGraph g = new NodeGraph();
        GraphNode music = g.addNode(NodeType.MUSIC, 0, 0);
        byte[] bytes = sampleSong().write();
        int blobId = feedBlob(4245, bytes);

        OpExecutor.apply(g, setSongOp(music.id, blobId));
        music.song.putNote(50, 0, 0, 40, 100, 100, 0);
        OpExecutor.apply(g, setSongOp(music.id, blobId));
        assertEquals(3, music.song.noteCount(),
            "the handle is consumed on first apply — a replay is a no-op");
    }

    @Test
    void writeReadRoundTripThroughBlobMatchesSource() {
        NbsSong src = sampleSong();
        src.addLayer();
        src.layers.get(1).name = "bass";
        src.putNote(8, 1, 2, 33, 70, 100, -30);
        src.songLength = src.computeLength();   // 读回时 songLength=0 会回填曲长——对齐期望
                                                // read() back-fills songLength when 0 — align the expectation

        NodeGraph g = new NodeGraph();
        GraphNode music = g.addNode(NodeType.MUSIC, 0, 0);
        int blobId = feedBlob(4246, src.write());
        OpExecutor.apply(g, setSongOp(music.id, blobId));
        assertEquals(src, music.song, "byte round-trip preserves song semantics");
    }
}
