package io.github.y15173334444.create_schematic_compute.graph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Full-graph NBT round-trip tests mirroring the real-world sync chain:
 * a display-editor drag triggers SET_DISPLAY_LAYOUT → flagFullSync → getUpdateTag
 * → NodeGraph.save(server) → NodeGraph.load(client). Any pixel data lost here
 * would surface as "image turns transparent after drag".
 */
class NodeGraphImageRoundTripTest {

    /** Mirrors the real IMAGE_SEQUENCE state: imagePixels is RE-LINKED to the current
     *  frame (same array reference), frames have content, W×H is non-default. */
    @Test
    @DisplayName("round-trip preserves frames, current-frame pixels and non-default W×H")
    void roundTripSequenceWithLinkedFrame() {
        var g = new NodeGraph();
        var n = g.addNode(NodeType.IMAGE_SEQUENCE, 0, 0);
        GraphNode.resizeImagePixels(n, 32, 8);
        n.imageSequenceFrames = new ArrayList<>();
        n.imageSequenceFrames.add(new int[256]);
        n.imageSequenceFrames.add(new int[256]);
        n.imagePixels = n.imageSequenceFrames.get(1); // re-linked current frame
        n.imagePixels[5] = 0xFF112233;
        n.imageSequenceFrames.get(0)[9] = 0xFF445566;

        var tag = g.save(null);
        var loaded = NodeGraph.load(tag, null);
        var ln = loaded.findNode(n.id);

        assertEquals(32, ln.imageWidth);
        assertEquals(8, ln.imageHeight);
        assertEquals(2, ln.imageSequenceFrames.size());
        assertEquals(256, ln.imagePixels.length);
        assertEquals(0xFF112233, ln.imagePixels[5]);
        assertEquals(0xFF445566, ln.imageSequenceFrames.get(0)[9]);
    }

    /** Plain IMAGE with painted pixels and default 16×16. */
    @Test
    @DisplayName("round-trip preserves painted IMAGE pixels at default 16×16")
    void roundTripImage() {
        var g = new NodeGraph();
        var n = g.addNode(NodeType.IMAGE, 0, 0);
        n.imagePixels[0] = 0xFFAABBCC;
        n.imagePixels[255] = 0xFF010203;

        var tag = g.save(null);
        var loaded = NodeGraph.load(tag, null);
        var ln = loaded.findNode(n.id);

        assertEquals(256, ln.imagePixels.length);
        assertEquals(0xFFAABBCC, ln.imagePixels[0]);
        assertEquals(0xFF010203, ln.imagePixels[255]);
    }

    /** IMAGE_SEQUENCE where imagePixels was never painted (null) but frames exist. */
    @Test
    @DisplayName("round-trip preserves frames when imagePixels is null")
    void roundTripNullPixelsWithFrames() {
        var g = new NodeGraph();
        var n = g.addNode(NodeType.IMAGE_SEQUENCE, 0, 0);
        n.imagePixels = null;
        n.imageSequenceFrames = new ArrayList<>();
        n.imageSequenceFrames.add(new int[256]);
        n.imageSequenceFrames.get(0)[77] = 0xFF778899;

        var tag = g.save(null);
        var loaded = NodeGraph.load(tag, null);
        var ln = loaded.findNode(n.id);

        assertEquals(1, ln.imageSequenceFrames.size());
        assertEquals(0xFF778899, ln.imageSequenceFrames.get(0)[77]);
        // constructor re-allocated the default canvas; length must still match W×H
        assertEquals(256, ln.imagePixels.length);
    }
}
