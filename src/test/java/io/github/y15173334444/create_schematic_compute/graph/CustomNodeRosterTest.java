package io.github.y15173334444.create_schematic_compute.graph;

import io.github.y15173334444.create_schematic_compute.api.CustomNodeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D5 宿主名单登记口的纪律锚（公开 API 波 1 批 D）：登记幂等、未注册 id 拒、空键拒、
 * 快照只读（防外部改写内部集合）。
 * Discipline anchors for the D5 host-roster enrolment point (public-API wave 1 batch D):
 * idempotent enrolment, unregistered ids refused, blank keys refused, read-only snapshots
 * (no outside writes into the internal sets).
 */
class CustomNodeRosterTest {

    private static CustomNodeType spec(String name) {
        return new CustomNodeType("rostermod2:" + name, "R " + name,
            List.of(), List.of(), List.of());
    }

    @Test
    @DisplayName("登记幂等；未注册 id 抛 IAE；空键抛 IAE；快照只读")
    void enrolDiscipline() {
        CustomNodeRegistry.register(spec("widget"));
        assertThrows(IllegalArgumentException.class,
            () -> CustomNodeRoster.admit("csc:test", "rostermod2:missing"),
            "enrolling an unregistered id is a provider bug — fail fast");
        assertThrows(IllegalArgumentException.class,
            () -> CustomNodeRoster.admit(" ", "rostermod2:widget"),
            "blank host keys are refused");
        assertThrows(IllegalArgumentException.class,
            () -> CustomNodeRoster.admit(null, "rostermod2:widget"),
            "null host keys are refused");
        // 归属校验（批 D 审查拍板）：非白名单宿主键拒——任意键登记旁路收口。
        // Ownership check (batch D review ruling): non-allowlisted keys refuse — the
        // any-key enrolment bypass closes.
        assertThrows(IllegalArgumentException.class,
            () -> CustomNodeRoster.admit("othermod:whatever", "rostermod2:widget"),
            "non-allowlisted host keys refuse third-party enrolment");
        assertThrows(IllegalArgumentException.class,
            () -> CustomNodeRoster.admit(BlockNodeAllowances.SPEAKER.hostKey(),
                "rostermod2:widget"),
            "a this-mod key without the CUSTOM category is not enrolable either");

        String key = io.github.y15173334444.create_schematic_compute.graph.BlockNodeAllowances.BLUEPRINT.hostKey();
        CustomNodeRoster.admit(key, "rostermod2:widget");
        CustomNodeRoster.admit(key, "rostermod2:widget");
        assertEquals(1, CustomNodeRoster.forHost(key).size(), "duplicate enrolment is idempotent");

        var snapshot = CustomNodeRoster.forHost(key);
        assertThrows(UnsupportedOperationException.class,
            () -> snapshot.add("rostermod2:other"),
            "the snapshot is read-only — no outside writes into the roster");
        assertEquals(Set.of(), CustomNodeRoster.forHost("csc:unknown"), "unknown keys fail closed");
        assertEquals(Set.of(), CustomNodeRoster.forHost(null), "null keys fail closed");
        assertTrue(CustomNodeRoster.hostKeys().contains(key), "enrolled keys are observable");
        CustomNodeRoster.clearForTests();
        assertEquals(Set.of(), CustomNodeRoster.forHost(key), "clearForTests empties the roster");
    }
}
