package io.github.y15173334444.create_schematic_compute.graph;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link GraphOp} — utility functions that don't require Minecraft runtime.
 * <p>Note: GraphOp factory methods require {@code ItemStack.EMPTY} which needs Minecraft
 * to be initialized. Those are tested implicitly by integration tests.</p>
 */
class GraphOpTest {

    // ══════════════════ parseCtrlPoints ══════════════════

    @Test
    @DisplayName("parseCtrlPoints: roundtrips correctly")
    void testParseCtrlPointsRoundtrip() {
        String input = "0.0,0.0;0.5,1.0;1.0,0.0";
        float[][] result = GraphOp.parseCtrlPoints(input);
        assertNotNull(result);
        assertEquals(3, result[0].length);
        assertEquals(3, result[1].length);
        assertEquals(0f, result[0][0], 0.0001f);
        assertEquals(0.5f, result[0][1], 0.0001f);
        assertEquals(1f, result[0][2], 0.0001f);
        assertEquals(0f, result[1][0], 0.0001f);
        assertEquals(1f, result[1][1], 0.0001f);
        assertEquals(0f, result[1][2], 0.0001f);
    }

    @Test
    @DisplayName("parseCtrlPoints: single control point pair")
    void testParseCtrlPointsSingle() {
        float[][] result = GraphOp.parseCtrlPoints("0.25,0.75");
        assertNotNull(result);
        assertEquals(1, result[0].length);
        assertEquals(0.25f, result[0][0], 0.0001f);
        assertEquals(0.75f, result[1][0], 0.0001f);
    }

    @Test
    @DisplayName("parseCtrlPoints: null/empty input returns null")
    void testParseCtrlPointsNull() {
        assertNull(GraphOp.parseCtrlPoints(null));
        assertNull(GraphOp.parseCtrlPoints(""));
    }

    @Test
    @DisplayName("parseCtrlPoints: malformed input returns null")
    void testParseCtrlPointsMalformed() {
        assertNull(GraphOp.parseCtrlPoints("not;valid"));
        assertNull(GraphOp.parseCtrlPoints("1.0")); // missing comma
        assertNull(GraphOp.parseCtrlPoints("a,b;c,d")); // non-numeric
    }

    @Test
    @DisplayName("parseCtrlPoints: negative values preserved")
    void testParseCtrlPointsNegative() {
        float[][] result = GraphOp.parseCtrlPoints("-1.0,-2.5;0.5,3.0");
        assertNotNull(result);
        assertEquals(2, result[0].length);
        assertEquals(-1.0f, result[0][0], 0.0001f);
        assertEquals(-2.5f, result[1][0], 0.0001f);
        assertEquals(0.5f, result[0][1], 0.0001f);
        assertEquals(3.0f, result[1][1], 0.0001f);
    }

    // ══════════════════ packConnPinIds / parseConnPinIds ══════════════════

    @Test
    @DisplayName("packConnPinIds: both-null stays null (legacy integer-pin path)")
    void testPackConnPinIdsBothNull() {
        assertNull(GraphOp.packConnPinIds(null, null));
        assertNull(GraphOp.parseConnPinIds(null));
        assertNull(GraphOp.parseConnPinIds(""));
    }

    @Test
    @DisplayName("packConnPinIds: roundtrips both ends")
    void testPackConnPinIdsRoundtrip() {
        String packed = GraphOp.packConnPinIds("A", "band_0");
        assertNotNull(packed);
        String[] pids = GraphOp.parseConnPinIds(packed);
        assertNotNull(pids);
        assertEquals("A", pids[0]);
        assertEquals("band_0", pids[1]);
    }

    @Test
    @DisplayName("packConnPinIds: empty slots parse back to null")
    void testPackConnPinIdsPartial() {
        String[] pids = GraphOp.parseConnPinIds(GraphOp.packConnPinIds(null, "0"));
        assertNotNull(pids);
        assertNull(pids[0]);
        assertEquals("0", pids[1]);
        pids = GraphOp.parseConnPinIds(GraphOp.packConnPinIds("out0", null));
        assertNotNull(pids);
        assertEquals("out0", pids[0]);
        assertNull(pids[1]);
    }

    @Test
    @DisplayName("parseConnPinIds: malformed input returns null")
    void testParseConnPinIdsMalformed() {
        assertNull(GraphOp.parseConnPinIds("no-separator"));
        assertNull(GraphOp.parseConnPinIds("just-a-var-name"));
    }

    @Test
    @DisplayName("parseConnPinIds: multiple separators are malformed (not a garbage toPinId)")
    void testParseConnPinIdsMultiSeparator() {
        String sep = String.valueOf((char) 1);
        assertNull(GraphOp.parseConnPinIds("A" + sep + "B" + sep + "C"));
        assertNull(GraphOp.parseConnPinIds(sep + sep + "x"));
    }
}
