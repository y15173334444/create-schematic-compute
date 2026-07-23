package io.github.y15173334444.create_schematic_compute.graph;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link NodeType} — enum consistency and metadata correctness.
 */
class NodeTypeTest {

    @Test
    @DisplayName("BY_ID map: all enum values have valid round-trip lookup")
    void testByIdRoundTrip() {
        for (NodeType type : NodeType.values()) {
            // Every type's id must map back to the same type
            assertEquals(type, NodeType.BY_ID.get(type.id),
                "BY_ID lookup failed for " + type.name());
        }
    }

    @Test
    @DisplayName("No duplicate ids across all node types")
    void testNoDuplicateIds() {
        var seen = new java.util.HashSet<String>();
        for (NodeType type : NodeType.values()) {
            assertTrue(seen.add(type.id),
                "Duplicate id '" + type.id + "' found for " + type.name());
        }
    }

    @Test
    @DisplayName("Node counts: CONST has 0 inputs, 1 output")
    void testConstIo() {
        assertEquals(0, NodeType.CONST.inputs);
        assertEquals(1, NodeType.CONST.outputs);
    }

    @Test
    @DisplayName("Math nodes have 2 inputs, 1 output")
    void testMathIo() {
        assertEquals(2, NodeType.ADD.inputs);
        assertEquals(1, NodeType.ADD.outputs);
        assertEquals(2, NodeType.SUB.inputs);
        assertEquals(2, NodeType.MUL.inputs);
        assertEquals(2, NodeType.DIV.inputs);
    }

    @Test
    @DisplayName("PID has 2 inputs (SP, PV), 1 output")
    void testPidIo() {
        assertEquals(2, NodeType.PID.inputs);
        assertEquals(1, NodeType.PID.outputs);
    }

    @Test
    @DisplayName("FORMULA has 0 base inputs (dynamic), 1 base output")
    void testFormulaIo() {
        assertEquals(0, NodeType.FORMULA.inputs);
        assertEquals(1, NodeType.FORMULA.outputs);
    }

    @Test
    @DisplayName("Debug nodes marked as debug")
    void testIsDebug() {
        assertTrue(NodeType.DEBUG_SIGNAL_GEN.isDebug());
        assertTrue(NodeType.DEBUG_PROBE.isDebug());
        assertFalse(NodeType.CONST.isDebug());
        assertFalse(NodeType.ADD.isDebug());
    }

    @Test
    @DisplayName("Sequential nodes have correct input/output counts")
    void testSequentialIo() {
        assertEquals(1, NodeType.DELAY.inputs);
        assertEquals(1, NodeType.DELAY.outputs);
        assertEquals(2, NodeType.LATCH.inputs);
        assertEquals(1, NodeType.LATCH.outputs);
        assertEquals(1, NodeType.T_FLIPFLOP.inputs);
    }

    @Test
    @DisplayName("Input nodes have 0 inputs")
    void testInputNodes() {
        assertEquals(0, NodeType.KEYBOARD.inputs);
        assertEquals(0, NodeType.MOUSE_JOYSTICK.inputs);
        assertEquals(0, NodeType.GAMEPAD_JOYSTICK.inputs);
        assertEquals(0, NodeType.ATTITUDE.inputs);
        assertEquals(0, NodeType.FORWARD.inputs);
        assertEquals(0, NodeType.VELOCITY.inputs);
    }

    @Test
    @DisplayName("ENCAPSULATION has 0 base inputs / 0 base outputs (dynamic)")
    void testEncapsulationIo() {
        assertEquals(0, NodeType.ENCAPSULATION.inputs);
        assertEquals(0, NodeType.ENCAPSULATION.outputs);
    }

    @Test
    @DisplayName("All param name strings are non-null")
    void testParamNames() {
        for (NodeType type : NodeType.values()) {
            assertNotNull(type.paramNames, "paramNames null for " + type.name());
            for (String name : type.paramNames) {
                assertNotNull(name, "param name null in " + type.name());
            }
        }
    }
}
