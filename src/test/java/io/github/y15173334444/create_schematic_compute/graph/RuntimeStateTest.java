package io.github.y15173334444.create_schematic_compute.graph;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.ArrayDeque;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link RuntimeState} — NBT serialization roundtrip for all state types.
 */
class RuntimeStateTest {

    @Test
    @DisplayName("Empty state: save→load roundtrip produces empty state")
    void testEmptyRoundtrip() {
        var rs = new RuntimeState();
        var tag = rs.save();
        var loaded = RuntimeState.load(tag);

        assertTrue(loaded.pidState.isEmpty());
        assertTrue(loaded.delayQueues.isEmpty());
        assertTrue(loaded.flipflopStates.isEmpty());
        assertTrue(loaded.pulseTimers.isEmpty());
        assertTrue(loaded.debugTime.isEmpty());
        assertTrue(loaded.subStates.isEmpty());
    }

    @Test
    @DisplayName("pidState: single entry roundtrip")
    void testPidStateRoundtrip() {
        var rs = new RuntimeState();
        rs.pidState.put(42, 3.14f);

        var loaded = RuntimeState.load(rs.save());
        assertEquals(1, loaded.pidState.size());
        assertEquals(3.14f, loaded.pidState.get(42), 0.0001f);
    }

    @Test
    @DisplayName("pidState: multiple entries roundtrip")
    void testPidStateMultiple() {
        var rs = new RuntimeState();
        rs.pidState.put(1, 10f);
        rs.pidState.put(2, 20f);
        rs.pidState.put(3, 30f);

        var loaded = RuntimeState.load(rs.save());
        assertEquals(3, loaded.pidState.size());
        assertEquals(10f, loaded.pidState.get(1));
        assertEquals(20f, loaded.pidState.get(2));
        assertEquals(30f, loaded.pidState.get(3));
    }

    @Test
    @DisplayName("pidState: negative values survive roundtrip")
    void testPidStateNegative() {
        var rs = new RuntimeState();
        rs.pidState.put(1, -5.5f);
        rs.pidState.put(2, 0f);

        var loaded = RuntimeState.load(rs.save());
        assertEquals(-5.5f, loaded.pidState.get(1), 0.0001f);
        assertEquals(0f, loaded.pidState.get(2), 0.0001f);
    }

    // ══════════════════ flipflopStates ══════════════════

    @Test
    @DisplayName("flipflopStates: boolean entries roundtrip")
    void testFlipflopStateRoundtrip() {
        var rs = new RuntimeState();
        rs.flipflopStates.put(10, true);
        rs.flipflopStates.put(20, false);

        var loaded = RuntimeState.load(rs.save());
        assertEquals(2, loaded.flipflopStates.size());
        assertTrue(loaded.flipflopStates.get(10));
        assertFalse(loaded.flipflopStates.get(20));
    }

    // ══════════════════ delayQueues ══════════════════

    @Test
    @DisplayName("delayQueues: queue entries roundtrip in order")
    void testDelayQueueRoundtrip() {
        var rs = new RuntimeState();
        var q = new ArrayDeque<Float>();
        q.addLast(1f);
        q.addLast(2f);
        q.addLast(3f);
        rs.delayQueues.put(5, q);

        var loaded = RuntimeState.load(rs.save());
        assertEquals(1, loaded.delayQueues.size());
        var loadedQ = loaded.delayQueues.get(5);
        assertNotNull(loadedQ);
        assertEquals(3, loadedQ.size());
        assertEquals(1f, loadedQ.pollFirst(), 0.0001f);
        assertEquals(2f, loadedQ.pollFirst(), 0.0001f);
        assertEquals(3f, loadedQ.pollFirst(), 0.0001f);
    }

    @Test
    @DisplayName("delayQueues: empty queue roundtrip")
    void testDelayQueueEmpty() {
        var rs = new RuntimeState();
        rs.delayQueues.put(7, new ArrayDeque<>());

        var loaded = RuntimeState.load(rs.save());
        assertTrue(loaded.delayQueues.containsKey(7));
        assertTrue(loaded.delayQueues.get(7).isEmpty());
    }

    // ══════════════════ pulseTimers ══════════════════

    @Test
    @DisplayName("pulseTimers: integer entries roundtrip")
    void testPulseTimersRoundtrip() {
        var rs = new RuntimeState();
        rs.pulseTimers.put(100, 25);
        rs.pulseTimers.put(200, 0);

        var loaded = RuntimeState.load(rs.save());
        assertEquals(2, loaded.pulseTimers.size());
        assertEquals(25, loaded.pulseTimers.get(100));
        assertEquals(0, loaded.pulseTimers.get(200));
    }

    // ══════════════════ debugTime ══════════════════

    @Test
    @DisplayName("debugTime: float entries roundtrip")
    void testDebugTimeRoundtrip() {
        var rs = new RuntimeState();
        rs.debugTime.put(1, 0.5f);
        rs.debugTime.put(2, 0.75f);

        var loaded = RuntimeState.load(rs.save());
        assertEquals(2, loaded.debugTime.size());
        assertEquals(0.5f, loaded.debugTime.get(1), 0.0001f);
        assertEquals(0.75f, loaded.debugTime.get(2), 0.0001f);
    }

    // ══════════════════ subStates ══════════════════

    @Test
    @DisplayName("subStates: single sub-graph with pidState roundtrip")
    void testSubStatePidOnly() {
        var rs = new RuntimeState();
        var sub = rs.getOrCreateSubState(99);
        sub.pidState.put(1, 42f);

        var loaded = RuntimeState.load(rs.save());
        assertEquals(1, loaded.subStates.size());
        var loadedSub = loaded.subStates.get(99);
        assertNotNull(loadedSub);
        assertEquals(42f, loadedSub.pidState.get(1), 0.0001f);
    }

    @Test
    @DisplayName("subStates: full sub-graph state roundtrip")
    void testSubStateFullRoundtrip() {
        var rs = new RuntimeState();
        var sub = rs.getOrCreateSubState(50);
        sub.pidState.put(10, 3.14f);
        sub.flipflopStates.put(20, true);
        sub.pulseTimers.put(30, 5);
        sub.debugTime.put(40, 0.25f);
        var q = new ArrayDeque<Float>();
        q.addLast(1f); q.addLast(2f);
        sub.delayQueues.put(60, q);

        var loaded = RuntimeState.load(rs.save());
        var loadedSub = loaded.subStates.get(50);
        assertNotNull(loadedSub);
        assertEquals(3.14f, loadedSub.pidState.get(10), 0.0001f);
        assertTrue(loadedSub.flipflopStates.get(20));
        assertEquals(5, loadedSub.pulseTimers.get(30));
        assertEquals(0.25f, loadedSub.debugTime.get(40), 0.0001f);
        assertEquals(2, loadedSub.delayQueues.get(60).size());
    }

    @Test
    @DisplayName("subStates: multiple sub-graphs roundtrip")
    void testMultipleSubStates() {
        var rs = new RuntimeState();
        rs.getOrCreateSubState(1).pidState.put(1, 10f);
        rs.getOrCreateSubState(2).pidState.put(2, 20f);
        rs.getOrCreateSubState(3).pidState.put(3, 30f);

        var loaded = RuntimeState.load(rs.save());
        assertEquals(3, loaded.subStates.size());
        assertEquals(10f, loaded.subStates.get(1).pidState.get(1));
        assertEquals(20f, loaded.subStates.get(2).pidState.get(2));
        assertEquals(30f, loaded.subStates.get(3).pidState.get(3));
    }

    // ══════════════════ Full state roundtrip ══════════════════

    @Test
    @DisplayName("Full roundtrip: all state types simultaneously")
    void testFullRoundtrip() {
        var rs = new RuntimeState();
        rs.pidState.put(1, 100f);
        rs.flipflopStates.put(2, true);
        rs.pulseTimers.put(3, 42);
        rs.debugTime.put(4, 0.123f);
        var q = new ArrayDeque<Float>();
        q.addLast(5f); q.addLast(6f);
        rs.delayQueues.put(5, q);
        var sub = rs.getOrCreateSubState(10);
        sub.pidState.put(11, 200f);
        sub.flipflopStates.put(12, false);

        var loaded = RuntimeState.load(rs.save());

        // Main state
        assertEquals(100f, loaded.pidState.get(1));
        assertTrue(loaded.flipflopStates.get(2));
        assertEquals(42, loaded.pulseTimers.get(3));
        assertEquals(0.123f, loaded.debugTime.get(4), 0.0001f);
        assertEquals(2, loaded.delayQueues.get(5).size());
        // Sub state
        assertEquals(1, loaded.subStates.size());
        var loadedSub = loaded.subStates.get(10);
        assertNotNull(loadedSub);
        assertEquals(200f, loadedSub.pidState.get(11));
        assertFalse(loadedSub.flipflopStates.get(12));
    }

    // ══════════════════ clear ══════════════════

    @Test
    @DisplayName("clear: removes all state")
    void testClear() {
        var rs = new RuntimeState();
        rs.pidState.put(1, 10f);
        rs.flipflopStates.put(2, true);
        rs.pulseTimers.put(3, 5);
        rs.debugTime.put(4, 0.5f);
        rs.getOrCreateSubState(10).pidState.put(11, 20f);

        rs.clear();
        assertTrue(rs.pidState.isEmpty());
        assertTrue(rs.flipflopStates.isEmpty());
        assertTrue(rs.pulseTimers.isEmpty());
        assertTrue(rs.debugTime.isEmpty());
        assertTrue(rs.subStates.isEmpty());
        assertTrue(rs.delayQueues.isEmpty());
    }

    // ══════════════════ getOrCreateSubState ══════════════════

    @Test
    @DisplayName("getOrCreateSubState: returns same instance on repeated calls")
    void testGetOrCreateSameInstance() {
        var rs = new RuntimeState();
        var s1 = rs.getOrCreateSubState(5);
        s1.pidState.put(1, 42f);
        var s2 = rs.getOrCreateSubState(5);

        assertSame(s1, s2);
        assertEquals(42f, s2.pidState.get(1));
    }

    @Test
    @DisplayName("load: null/empty tag returns empty RuntimeState")
    void testLoadEmptyTag() {
        var loaded = RuntimeState.load(new CompoundTag());
        assertTrue(loaded.pidState.isEmpty());
        assertTrue(loaded.flipflopStates.isEmpty());
    }
}
