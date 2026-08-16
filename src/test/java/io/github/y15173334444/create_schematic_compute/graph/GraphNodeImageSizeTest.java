package io.github.y15173334444.create_schematic_compute.graph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for IMAGE / IMAGE_SEQUENCE custom canvas size (W×H, 1..32):
 * allocation, content-preserving resize, NBT round-trip and legacy-save migration.
 * <p>
 * 注：SET_IMAGE_SIZE op 的端到端用例无法在纯 JUnit 环境运行——GraphOp 工厂引用
 * ItemStack.EMPTY 会触发 MC 注册表初始化（Bootstrap.bootStrap() 在无游戏环境下 NPE）。
 * op 内的 clamp(1..32) + resizeImagePixels 已由下方用例覆盖。
 * Note: an end-to-end SET_IMAGE_SIZE op test is not feasible in plain JUnit — the GraphOp
 * factory touches ItemStack.EMPTY, which requires MC registry bootstrap that NPEs outside
 * a game environment. The op's clamp(1..32) + resizeImagePixels logic is covered below.
 */
class GraphNodeImageSizeTest {

    @Test
    @DisplayName("default canvas is 16×16 and allocates 256 pixels")
    void defaultCanvas() {
        var n = new GraphNode(1, NodeType.IMAGE, 0, 0);
        assertEquals(16, n.imageWidth);
        assertEquals(16, n.imageHeight);
        assertEquals(256, n.imagePixels.length);
    }

    @Test
    @DisplayName("resizeImagePixels keeps top-left content row-wise, drops overflow, fills transparent")
    void resizePreservesContent() {
        var n = new GraphNode(1, NodeType.IMAGE, 0, 0);
        n.imagePixels[0] = 0xFF112233;   // (0,0) → preserved
        n.imagePixels[7] = 0xFF445566;   // (7,0) → preserved
        n.imagePixels[8] = 0xFF778899;   // (8,0) → dropped (beyond new width 8)
        n.imagePixels[16] = 0xFFAABBCC;  // (0,1) → preserved at (0,1)
        GraphNode.resizeImagePixels(n, 8, 32);
        assertEquals(8, n.imageWidth);
        assertEquals(32, n.imageHeight);
        assertEquals(256, n.imagePixels.length);
        assertEquals(0xFF112233, n.imagePixels[0]);
        assertEquals(0xFF445566, n.imagePixels[7]);
        assertEquals(0xFFAABBCC, n.imagePixels[8]);  // row 1 starts at index 8
        assertEquals(0, n.imagePixels[15]);          // (7,1) never written
        assertEquals(0, n.imagePixels[16 * 8 - 1]);  // last row still transparent
    }

    @Test
    @DisplayName("resizeImagePixels resizes all IMAGE_SEQUENCE frames")
    void resizeFrames() {
        var n = new GraphNode(2, NodeType.IMAGE_SEQUENCE, 0, 0);
        n.imageSequenceFrames = new ArrayList<>();
        n.imageSequenceFrames.add(new int[256]);
        n.imageSequenceFrames.get(0)[0] = 0xFF123456;
        n.imageSequenceFrames.add(new int[256]);
        n.imageSequenceFrames.get(1)[255] = 0xFF654321;
        GraphNode.resizeImagePixels(n, 4, 4);
        assertEquals(16, n.imagePixels.length);
        assertEquals(2, n.imageSequenceFrames.size());
        assertEquals(16, n.imageSequenceFrames.get(0).length);
        assertEquals(0xFF123456, n.imageSequenceFrames.get(0)[0]);
        assertEquals(16, n.imageSequenceFrames.get(1).length);
        // frame 1's (15,15) dropped — beyond 4×4
        for (int v : n.imageSequenceFrames.get(1)) assertEquals(0, v);
    }

    @Test
    @DisplayName("NBT round-trip preserves W×H, pixels and frames; legacy save stays 16×16")
    void nbtRoundTrip() {
        var n = new GraphNode(7, NodeType.IMAGE_SEQUENCE, 3, 4);
        GraphNode.resizeImagePixels(n, 24, 8);
        n.imageSequenceFrames = new ArrayList<>();
        n.imageSequenceFrames.add(new int[24 * 8]);
        n.imageSequenceFrames.add(new int[24 * 8]);
        n.imagePixels[0] = 0xFF010203;
        n.imagePixels[24 * 8 - 1] = 0xFF040506;
        n.imageSequenceFrames.get(1)[10] = 0xFF0A0B0C;
        var tag = n.save(null);
        assertEquals(24, tag.getInt("iw"));
        assertEquals(8, tag.getInt("ih"));
        var loaded = GraphNode.load(tag, null);
        assertEquals(24, loaded.imageWidth);
        assertEquals(8, loaded.imageHeight);
        assertEquals(24 * 8, loaded.imagePixels.length);
        assertEquals(0xFF010203, loaded.imagePixels[0]);
        assertEquals(0xFF040506, loaded.imagePixels[24 * 8 - 1]);
        assertEquals(2, loaded.imageSequenceFrames.size());
        assertEquals(24 * 8, loaded.imageSequenceFrames.get(0).length);
        assertEquals(0xFF0A0B0C, loaded.imageSequenceFrames.get(1)[10]);

        // legacy 16×16 save without iw/ih → defaults to 16×16, pixels intact
        var legacy = new GraphNode(8, NodeType.IMAGE, 0, 0);
        legacy.imagePixels[0] = 0xFF050607;
        var legacyTag = legacy.save(null);
        assertFalse(legacyTag.contains("iw"));
        var legacyLoaded = GraphNode.load(legacyTag, null);
        assertEquals(16, legacyLoaded.imageWidth);
        assertEquals(16, legacyLoaded.imageHeight);
        assertEquals(0xFF050607, legacyLoaded.imagePixels[0]);
    }

    @Test
    @DisplayName("migration guard re-fits mismatched pixel arrays to W×H")
    void migrationGuard() {
        var n = new GraphNode(9, NodeType.IMAGE, 0, 0);
        n.imageWidth = 8; n.imageHeight = 4; // 32 expected
        n.imagePixels = new int[30];         // legacy/partial length
        n.imagePixels[0] = 0xFF0F0F0F;
        GraphNode.fixImagePixelsToSize(n);
        assertEquals(32, n.imagePixels.length);
        assertEquals(0xFF0F0F0F, n.imagePixels[0]);
        assertEquals(0, n.imagePixels[31]);
    }
}
