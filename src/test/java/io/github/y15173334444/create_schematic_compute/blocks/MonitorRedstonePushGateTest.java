package io.github.y15173334444.create_schematic_compute.blocks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P2-17 回归测试：Monitor 红石输入推送的"变化才推"门（KineticGauge.pushReadoutIfChanged
 * 正面样板同口径）。此前每 tick 对每条链路无条件发包（N 条链路 × 20 包/秒恒发）。
 * 门本身是 {@link MonitorBlockEntity#redstonePushDiff} / {@link MonitorBlockEntity#prunePushedKeys}
 * 两个纯函数，BE 侧只做投递——这里直接锁纯逻辑。
 * <p>
 * Regression tests for P2-17: Monitor's push-on-change gate for redstone inputs (the same
 * discipline as the KineticGauge.pushReadoutIfChanged exemplar). The gate is the pure pair
 * {@link MonitorBlockEntity#redstonePushDiff} / {@link MonitorBlockEntity#prunePushedKeys};
 * the BE only ships what they return.
 * </p>
 */
class MonitorRedstonePushGateTest {

    @Test
    @DisplayName("初次推送必须发：空缓存 → 全部键都进差异 / first push always ships: empty cache → every key")
    void firstPushSendsEverything() {
        var lastPushed = new HashMap<Long, Integer>();
        var current = new HashMap<Long, Integer>();
        current.put(101L, 5);
        current.put(202L, 7);

        var diff = MonitorBlockEntity.redstonePushDiff(lastPushed, current);

        assertEquals(2, diff.size(), "both links are first pushes and must ship");
        assertEquals(5, diff.get(101L));
        assertEquals(7, diff.get(202L));
    }

    @Test
    @DisplayName("值未变不推：同值下一 tick 差异为空 / unchanged values ship nothing next tick")
    void unchangedSendsNothing() {
        var lastPushed = new HashMap<Long, Integer>();
        lastPushed.put(101L, 5);
        lastPushed.put(202L, 7);
        var current = new HashMap<Long, Integer>();
        current.put(101L, 5);
        current.put(202L, 7);

        assertTrue(MonitorBlockEntity.redstonePushDiff(lastPushed, current).isEmpty(),
            "steady state must produce zero packets (the P2-17 complaint was N x 20 pkt/s)");
    }

    @Test
    @DisplayName("仅变化的键进差异 / only the changed key enters the diff")
    void onlyChangedKeyShips() {
        var lastPushed = new HashMap<Long, Integer>();
        lastPushed.put(101L, 5);
        lastPushed.put(202L, 7);
        var current = new HashMap<Long, Integer>();
        current.put(101L, 5);
        current.put(202L, 9);

        var diff = MonitorBlockEntity.redstonePushDiff(lastPushed, current);

        assertEquals(1, diff.size());
        assertEquals(9, diff.get(202L), "changed key ships");
        assertNull(diff.get(101L), "unchanged key stays silent");
    }

    @Test
    @DisplayName("0 是真实信号值：非零→0 必须推 / 0 is a real signal: nonzero→0 must ship")
    void zeroIsARealValue() {
        var lastPushed = new HashMap<Long, Integer>();
        lastPushed.put(101L, 5);
        var current = new HashMap<Long, Integer>();
        current.put(101L, 0);

        var diff = MonitorBlockEntity.redstonePushDiff(lastPushed, current);

        assertEquals(1, diff.size(), "falling to zero is a change, not an absence");
        assertEquals(0, diff.get(101L));
    }

    @Test
    @DisplayName("链路注销清缓存：键重现为重推初值 / vanished link prunes the cache; re-added link re-pushes")
    void removedKeyRepushesOnReturn() {
        var lastPushed = new HashMap<Long, Integer>();
        lastPushed.put(101L, 5);
        lastPushed.put(202L, 7);

        // 链路 202 注销：lastInputs 里消失 → 差异为空（没东西可推），但缓存要清
        // Link 202 unregistered: gone from lastInputs → empty diff, but the cache must prune
        var current = new HashMap<Long, Integer>();
        current.put(101L, 5);
        assertTrue(MonitorBlockEntity.redstonePushDiff(lastPushed, current).isEmpty());
        MonitorBlockEntity.prunePushedKeys(lastPushed, current);
        assertFalse(lastPushed.containsKey(202L), "vanished key must leave the cache");
        assertTrue(lastPushed.containsKey(101L), "live key stays cached");

        // 链路 202 重新注册 → 键缺席 = 从未推过 → 初值必发（而不是被旧缓存挡住静默不推）
        // Link 202 re-registered: absent key = never pushed → the initial value must ship
        current.put(202L, 3);
        var diff = MonitorBlockEntity.redstonePushDiff(lastPushed, current);
        assertEquals(1, diff.size());
        assertEquals(3, diff.get(202L), "re-added link re-pushes its initial value");
    }

    @Test
    @DisplayName("新链路首推不惊动已缓存链路 / a new link's first push does not disturb cached links")
    void newLinkShipsAlone() {
        var lastPushed = new HashMap<Long, Integer>();
        lastPushed.put(101L, 5);
        var current = new HashMap<Long, Integer>();
        current.put(101L, 5);
        current.put(303L, 12);

        var diff = MonitorBlockEntity.redstonePushDiff(lastPushed, current);

        assertEquals(1, diff.size());
        assertEquals(12, diff.get(303L));
    }
}
