package io.github.y15173334444.create_schematic_compute.graph;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.ArrayDeque;
import java.util.List;
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

    // ══════════════════ pruneToAliveIds ══════════════════

    @Test
    @DisplayName("pruneToAliveIds: keeps alive node state incl. auxiliary slots, drops dead nodes")
    void testPruneKeepsAliveDropsDead() {
        var rs = new RuntimeState();
        // 存活节点 7：pid + ACCUMULATOR/INTEGRATOR 辅助槽 + flipflop 辅助槽
        rs.pidState.put(7, 12f);
        rs.pidState.put(7 + 100000, 1f);   // ACC prev+ / INT tick
        rs.pidState.put(7 + 200000, 0f);   // ACC prev-
        rs.flipflopStates.put(7, true);
        rs.flipflopStates.put(-(7 + 1), false);
        rs.pulseTimers.put(7, 3);
        rs.pulseTimers.put(-(7 + 1), 5);
        rs.delayQueues.put(7, new ArrayDeque<>());
        rs.debugTime.put(7, 0.5f);
        // 已删除节点 99：同族条目必须全部剪除
        rs.pidState.put(99, 1f);
        rs.pidState.put(99 + 100000, 1f);
        rs.flipflopStates.put(99, true);
        rs.flipflopStates.put(-(99 + 1), true);
        rs.pulseTimers.put(99, 2);
        rs.delayQueues.put(99, new ArrayDeque<>());
        rs.debugTime.put(99, 0.25f);
        // 已删除封装 55
        rs.subStates.put(55, new RuntimeState.SubState());

        rs.pruneToAliveIds(java.util.Set.of(7));

        // 存活节点的所有状态保留
        assertEquals(12f, rs.pidState.get(7));
        assertTrue(rs.pidState.containsKey(7 + 100000));
        assertTrue(rs.pidState.containsKey(7 + 200000));
        assertTrue(rs.flipflopStates.get(7));
        assertTrue(rs.flipflopStates.containsKey(-(7 + 1)));
        assertEquals(3, rs.pulseTimers.get(7));
        assertEquals(5, rs.pulseTimers.get(-(7 + 1)));
        assertTrue(rs.delayQueues.containsKey(7));
        assertEquals(0.5f, rs.debugTime.get(7), 0.0001f);
        // 已删除节点的所有条目（含辅助槽）剪除
        assertFalse(rs.pidState.containsKey(99));
        assertFalse(rs.pidState.containsKey(99 + 100000));
        assertFalse(rs.flipflopStates.containsKey(99));
        assertFalse(rs.flipflopStates.containsKey(-(99 + 1)));
        assertFalse(rs.pulseTimers.containsKey(99));
        assertFalse(rs.delayQueues.containsKey(99));
        assertFalse(rs.debugTime.containsKey(99));
        assertFalse(rs.subStates.containsKey(55));
    }

    @Test
    @DisplayName("pruneToAliveIds: empty alive set clears everything")
    void testPruneEmptyAliveClearsAll() {
        var rs = new RuntimeState();
        rs.pidState.put(1, 1f);
        rs.flipflopStates.put(1, true);
        rs.delayQueues.put(1, new ArrayDeque<>());
        rs.subStates.put(2, new RuntimeState.SubState());

        rs.pruneToAliveIds(java.util.Set.of());

        assertTrue(rs.pidState.isEmpty());
        assertTrue(rs.flipflopStates.isEmpty());
        assertTrue(rs.delayQueues.isEmpty());
        assertTrue(rs.subStates.isEmpty());
    }

    @Test
    @DisplayName("aliveStateKeys: expands ids with all auxiliary slot families")
    void testAliveStateKeys() {
        var keys = RuntimeState.aliveStateKeys(java.util.Set.of(7, 99));
        assertEquals(5 + 5, keys.size()); // 2 ids × (id, -(id+1), id+100000, id+200000, id+300000)
        assertTrue(keys.contains(7));
        assertTrue(keys.contains(-8));
        assertTrue(keys.contains(100007));
        assertTrue(keys.contains(200007));
        assertTrue(keys.contains(300007));   // MUSIC 上次 seek 值槽位
        assertTrue(keys.contains(99));
        assertTrue(keys.contains(-100));
        assertTrue(keys.contains(100099));
        assertTrue(keys.contains(200099));
        assertTrue(keys.contains(300099));
    }

    @Test
    @DisplayName("nodeEdge persists through save/load (trigger-level memory survives reload)")
    void nodeEdgeSaveLoadRoundTrip() {
        var rs = new RuntimeState();
        rs.nodeEdge.put(7, true);
        rs.nodeEdge.put(9, false);
        rs.pidState.put(7, 1.5f);

        var restored = RuntimeState.load(rs.save());
        assertEquals(Boolean.TRUE, restored.nodeEdge.get(7));
        assertEquals(Boolean.FALSE, restored.nodeEdge.get(9));
        assertEquals(1.5f, restored.pidState.get(7), 0.0001f);
    }

    // ══════════════════ F17: 坏档键兜底 / corrupt-key backstop ══════════════════
    // 修复前:Integer.parseInt(k) 无守卫,坏档字符串键 → NumberFormatException 直接炸区块加载。
    // 修复后:逐键 try/catch,坏键跳过 + WARN,好键照常恢复;save 侧不动,对称性保持。
    // Before the fix: unguarded Integer.parseInt(k) let a corrupted string key throw
    // NumberFormatException straight through chunk loading. After: per-key try/catch —
    // bad keys are skipped with a WARN, good keys restore as usual; save is untouched,
    // keeping save/load symmetric.

    @Test
    @DisplayName("F17: pid 的坏键被跳过,好键照常恢复,加载不炸")
    void corruptPidKeyIsSkipped() {
        var rs = new RuntimeState();
        rs.pidState.put(5, 1.5f);
        var tag = rs.save();
        // 模拟坏档:外部工具/手工编辑混入非数字键 / simulate corruption: a non-numeric key
        tag.getCompound("pid").putFloat("garbage", 42f);

        var loaded = assertDoesNotThrow(() -> RuntimeState.load(tag),
            "a corrupt key must not blow up the load");
        assertEquals(1, loaded.pidState.size(), "only the good key survives");
        assertEquals(1.5f, loaded.pidState.get(5), 0.0001f);
        // save/load 对称性:恢复态再保存只含合法键 / save/load symmetry: the restored state re-saves legal keys only
        var resaved = loaded.save();
        assertTrue(resaved.getCompound("pid").contains("5"));
        assertFalse(resaved.getCompound("pid").contains("garbage"));
    }

    @Test
    @DisplayName("F17: 全坏键 → 空状态,绝不抛 NumberFormatException")
    void allCorruptKeysProduceEmptyState() {
        var rs = new RuntimeState();
        rs.flipflopStates.put(1, true);
        var tag = rs.save();
        var ff = tag.getCompound("ff");
        ff.remove("1");
        ff.putBoolean("oops", true);

        var loaded = assertDoesNotThrow(() -> RuntimeState.load(tag));
        assertTrue(loaded.flipflopStates.isEmpty());
    }

    @Test
    @DisplayName("F17: delay/nodeEdge/pt/dt 的坏键同样逐键跳过")
    void corruptKeysSkippedAcrossAllMaps() {
        var rs = new RuntimeState();
        rs.delayQueues.put(2, new ArrayDeque<>(List.of(1f, 2f)));
        rs.nodeEdge.put(3, true);
        rs.pulseTimers.put(4, 7);
        rs.debugTime.put(6, 0.25f);
        var tag = rs.save();
        tag.getCompound("delay").put("bad", new net.minecraft.nbt.ListTag());
        tag.getCompound("nodeEdge").putBoolean("bad", false);
        tag.getCompound("pt").putInt("bad", 1);
        tag.getCompound("dt").putFloat("bad", 1f);

        var loaded = assertDoesNotThrow(() -> RuntimeState.load(tag));
        assertEquals(1, loaded.delayQueues.size());
        assertEquals(List.of(1f, 2f), List.copyOf(loaded.delayQueues.get(2)), "FIFO order preserved for the good queue");
        assertEquals(1, loaded.nodeEdge.size());
        assertEquals(Boolean.TRUE, loaded.nodeEdge.get(3));
        assertEquals(7, loaded.pulseTimers.get(4));
        assertEquals(0.25f, loaded.debugTime.get(6), 1e-6f);
    }

    @Test
    @DisplayName("F17: sub 的坏封装键跳过整个条目;好封装键内部的坏子键跳过单项")
    void corruptSubKeysAreContained() {
        var rs = new RuntimeState();
        var good = rs.getOrCreateSubState(10);
        good.pidState.put(1, 0.5f);
        good.flipflopStates.put(2, true);
        var tag = rs.save();
        var sub = tag.getCompound("sub");
        sub.put("notanumber", new CompoundTag()); // 坏封装键 / corrupt encap key
        var ss10 = sub.getCompound("10");
        ss10.getCompound("pid").putFloat("bad", 9f); // 好封装内的坏子键 / corrupt key inside a good encap

        var loaded = assertDoesNotThrow(() -> RuntimeState.load(tag));
        assertEquals(1, loaded.subStates.size(), "the corrupt encap entry is dropped as a whole");
        var restored = loaded.subStates.get(10);
        assertNotNull(restored);
        assertEquals(0.5f, restored.pidState.get(1), 1e-6f, "the corrupt sub-key is dropped, good sub-keys survive");
        assertEquals(1, restored.pidState.size());
        assertTrue(restored.flipflopStates.get(2));
    }
}