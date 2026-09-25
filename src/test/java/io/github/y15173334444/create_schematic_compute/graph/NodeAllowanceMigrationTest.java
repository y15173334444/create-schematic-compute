package io.github.y15173334444.create_schematic_compute.graph;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 节点分类覆盖与方块准入迁移回归（docs/node-category-allowance-plan.md §6）。
 * Category coverage and per-block allowance migration regression.
 */
class NodeAllowanceMigrationTest {

    // ── 迁移前允许集（2026-09-25 从旧 setNodeFilter 固化）──
    // Pre-migration allowed sets, frozen from the old setNodeFilter predicates.

    /** 蓝图旧黑名单取反：全类型 − 34 项排除。 */
    private static EnumSet<NodeType> oldBlueprint() {
        EnumSet<NodeType> s = EnumSet.allOf(NodeType.class);
        s.removeAll(EnumSet.of(
            NodeType.SPEED_CTRL,
            NodeType.DELAY, NodeType.LATCH, NodeType.T_FLIPFLOP,
            NodeType.PULSE_EXTEND, NodeType.LOOP, NodeType.FUSE,
            NodeType.KEYBOARD, NodeType.MOUSE_JOYSTICK, NodeType.MOUSE_BUTTON,
            NodeType.GAMEPAD_JOYSTICK, NodeType.GAMEPAD_BUTTON, NodeType.GAMEPAD_TRIGGER,
            NodeType.VIEW_ANGLE, NodeType.WORLD_VIEW,
            NodeType.ATTITUDE, NodeType.FORWARD, NodeType.ACCELERATION,
            NodeType.VELOCITY, NodeType.POSITION,
            NodeType.TARGET_OUT,
            NodeType.TEXT, NodeType.DATA, NodeType.IMAGE, NodeType.IMAGE_SEQUENCE,
            NodeType.HUD_PITCH_LADDER, // isMonitorOnly()
            NodeType.ENCAP_INPUT, NodeType.ENCAP_OUTPUT,
            NodeType.MOVE, NodeType.ROTATE, NodeType.WAIT, NodeType.CLUTCH, NodeType.ENCODER,
            NodeType.TX_OUT));
        return s;
    }

    private static EnumSet<NodeType> oldProgramComputer() {
        return EnumSet.of(
            NodeType.CONST, NodeType.REDSTONE_IN, NodeType.REDSTONE_OUT,
            NodeType.PRIVATE_IN, NodeType.PRIVATE_OUT, NodeType.BUS_IN, NodeType.BUS_OUT,
            NodeType.DELAY, NodeType.LATCH, NodeType.T_FLIPFLOP, NodeType.PULSE_EXTEND,
            NodeType.LOOP, NodeType.FUSE,
            NodeType.BOOL, NodeType.ACCUMULATOR, NodeType.INTEGRATOR, NodeType.GATE,
            NodeType.SIN, NodeType.COS, NodeType.TAN, NodeType.ASIN, NodeType.ACOS,
            NodeType.ATAN2, NodeType.SINH, NodeType.COSH,
            NodeType.SQRT, NodeType.LN, NodeType.LOG, NodeType.EXP,
            NodeType.SEC, NodeType.CSC, NodeType.COT, NodeType.ANGLE_UNWRAP,
            NodeType.DIRECTION,
            NodeType.COMMENT, NodeType.DEBUG_SIGNAL_GEN, NodeType.DEBUG_PROBE,
            NodeType.RELAY_A, NodeType.RELAY_B);
    }

    private static EnumSet<NodeType> oldCncGearbox() {
        EnumSet<NodeType> s = oldProgramComputer();
        s.addAll(EnumSet.of(NodeType.STRESS, NodeType.RPM,
            NodeType.MOVE, NodeType.ROTATE, NodeType.WAIT, NodeType.CLUTCH, NodeType.ENCODER));
        return s;
    }

    private static EnumSet<NodeType> oldKineticGauge() {
        return EnumSet.of(
            NodeType.STRESS, NodeType.RPM,
            NodeType.REDSTONE_OUT, NodeType.PRIVATE_OUT, NodeType.BUS_OUT,
            NodeType.CONST, NodeType.REDSTONE_IN, NodeType.PRIVATE_IN, NodeType.BUS_IN,
            NodeType.GT, NodeType.LT, NodeType.GE, NodeType.LE, NodeType.EQ,
            NodeType.BOOL, NodeType.GATE, NodeType.OR, NodeType.RELAY_A, NodeType.RELAY_B,
            NodeType.DEBUG_SIGNAL_GEN, NodeType.DEBUG_PROBE, NodeType.COMMENT);
    }

    private static EnumSet<NodeType> oldControlSeat() {
        return EnumSet.of(
            NodeType.KEYBOARD, NodeType.MOUSE_JOYSTICK, NodeType.VIEW_ANGLE,
            NodeType.MOUSE_BUTTON, NodeType.GAMEPAD_JOYSTICK, NodeType.GAMEPAD_BUTTON,
            NodeType.GAMEPAD_TRIGGER, NodeType.WORLD_VIEW,
            NodeType.ATTITUDE, NodeType.ACCELERATION, NodeType.VELOCITY, NodeType.POSITION,
            NodeType.BUS_OUT, NodeType.POSE_CONVERT, NodeType.SPLIT,
            NodeType.REDSTONE_OUT, NodeType.PRIVATE_OUT,
            NodeType.COMMENT, NodeType.DEBUG_SIGNAL_GEN, NodeType.DEBUG_PROBE);
    }

    private static EnumSet<NodeType> oldSensor() {
        return EnumSet.of(
            NodeType.ATTITUDE, NodeType.FORWARD, NodeType.ACCELERATION,
            NodeType.VELOCITY, NodeType.POSITION, NodeType.BUS_OUT,
            NodeType.REDSTONE_OUT, NodeType.PRIVATE_OUT,
            NodeType.COMMENT, NodeType.DEBUG_SIGNAL_GEN, NodeType.DEBUG_PROBE);
    }

    private static EnumSet<NodeType> oldMonitor() {
        return EnumSet.of(
            NodeType.CONST, NodeType.REDSTONE_IN, NodeType.PRIVATE_IN, NodeType.BUS_IN,
            NodeType.TEXT, NodeType.DATA, NodeType.IMAGE, NodeType.IMAGE_SEQUENCE,
            NodeType.HUD_PITCH_LADDER,
            NodeType.COMMENT, NodeType.DEBUG_SIGNAL_GEN, NodeType.DEBUG_PROBE);
    }

    private static EnumSet<NodeType> oldRadar() {
        return EnumSet.of(
            NodeType.TARGET_OUT, NodeType.REDSTONE_OUT, NodeType.PRIVATE_OUT, NodeType.BUS_OUT,
            NodeType.COMMENT, NodeType.DEBUG_SIGNAL_GEN, NodeType.DEBUG_PROBE);
    }

    private static EnumSet<NodeType> oldSpeedProxy() {
        return EnumSet.of(
            NodeType.SPEED_CTRL,
            NodeType.CONST, NodeType.REDSTONE_IN, NodeType.PRIVATE_IN, NodeType.BUS_IN,
            NodeType.COMMENT, NodeType.DEBUG_SIGNAL_GEN, NodeType.DEBUG_PROBE);
    }

    private static EnumSet<NodeType> oldTransmission() {
        return EnumSet.of(
            NodeType.STRESS, NodeType.RPM, NodeType.TX_OUT,
            NodeType.CONST, NodeType.REDSTONE_IN, NodeType.PRIVATE_IN, NodeType.BUS_IN,
            NodeType.COMMENT, NodeType.DEBUG_SIGNAL_GEN, NodeType.DEBUG_PROBE);
    }

    // ── 覆盖比对 / coverage ──

    @Test
    void everyNodeTypeInExactlyOneCategory() {
        EnumSet<NodeType> covered = EnumSet.noneOf(NodeType.class);
        for (NodeCategory c : NodeCategory.values()) {
            for (NodeType t : c.types) {
                assertTrue(covered.add(t), "duplicate category for " + t);
            }
        }
        assertEquals(EnumSet.allOf(NodeType.class), covered, "category coverage gap");
    }

    @Test
    void categoryOfIsTotal() {
        for (NodeType t : NodeType.values()) {
            assertNotNull(NodeCategory.of(t), "no category for " + t);
        }
    }

    // ── 类内例外最小化 / exclusions stay minimal ──

    @Test
    void exclusionsAreMinimal() {
        assertEquals(2, BlockNodeAllowances.BLUEPRINT.exclusions().size());
        assertEquals(1, BlockNodeAllowances.CONTROL_SEAT.exclusions().size());
        for (NodeAllowance a : new NodeAllowance[]{
            BlockNodeAllowances.PROGRAM_COMPUTER, BlockNodeAllowances.CNC_GEARBOX,
            BlockNodeAllowances.KINETIC_GAUGE, BlockNodeAllowances.SENSOR,
            BlockNodeAllowances.MONITOR, BlockNodeAllowances.RADAR,
            BlockNodeAllowances.SPEED_PROXY, BlockNodeAllowances.TRANSMISSION}) {
            assertTrue(a.exclusions().isEmpty(), "unexpected exclusions: " + a.exclusions());
        }
    }

    // ── 允许集回归：差异必须等于已声明变更 / diffs == declared changes ──

    @Test
    void blueprintDiffIsDeclaredOnly() {
        EnumSet<NodeType> oldSet = oldBlueprint();
        EnumSet<NodeType> newSet = BlockNodeAllowances.BLUEPRINT.allowedTypes();
        EnumSet<NodeType> lost = EnumSet.copyOf(oldSet);
        lost.removeAll(newSet);
        EnumSet<NodeType> gained = EnumSet.copyOf(newSet);
        gained.removeAll(oldSet);
        // 声明：蓝图失去 STRESS/RPM（对齐"仅动力宿主图"）；保留 ACCUMULATOR/INTEGRATOR
        assertEquals(EnumSet.of(NodeType.STRESS, NodeType.RPM), lost);
        assertTrue(gained.isEmpty(), "blueprint should not gain nodes");
        assertTrue(newSet.contains(NodeType.ACCUMULATOR));
        assertTrue(newSet.contains(NodeType.INTEGRATOR));
        assertTrue(newSet.contains(NodeType.POSE_CONVERT));
        assertTrue(newSet.contains(NodeType.SPLIT));
    }

    @Test
    void programComputerGainsDeclared23() {
        EnumSet<NodeType> oldSet = oldProgramComputer();
        EnumSet<NodeType> newSet = BlockNodeAllowances.PROGRAM_COMPUTER.allowedTypes();
        EnumSet<NodeType> lost = EnumSet.copyOf(oldSet);
        lost.removeAll(newSet);
        EnumSet<NodeType> gained = EnumSet.copyOf(newSet);
        gained.removeAll(oldSet);
        assertTrue(lost.isEmpty(), "program computer must not lose nodes: " + lost);
        assertEquals(EnumSet.of(
            // math_basic 缺口 11
            NodeType.ADD, NodeType.SUB, NodeType.MUL, NodeType.DIV, NodeType.MOD,
            NodeType.POW, NodeType.ROOT, NodeType.ABS, NodeType.CEIL, NodeType.FLOOR,
            NodeType.ROUND,
            // logic 比较 + OR
            NodeType.GT, NodeType.LT, NodeType.GE, NodeType.LE, NodeType.EQ, NodeType.OR,
            // math_advanced 缺口
            NodeType.FORMULA, NodeType.INTERP,
            // control 整类
            NodeType.PID, NodeType.PID_POWER, NodeType.CLAMP, NodeType.MAP
        ), gained);
        assertEquals(23, gained.size());
    }

    @Test
    void cncGearboxGainsSame23() {
        EnumSet<NodeType> oldSet = oldCncGearbox();
        EnumSet<NodeType> newSet = BlockNodeAllowances.CNC_GEARBOX.allowedTypes();
        EnumSet<NodeType> lost = EnumSet.copyOf(oldSet);
        lost.removeAll(newSet);
        EnumSet<NodeType> gained = EnumSet.copyOf(newSet);
        gained.removeAll(oldSet);
        assertTrue(lost.isEmpty(), "cnc gearbox must not lose nodes: " + lost);
        assertEquals(23, gained.size());
        assertEquals(
            EnumSet.copyOf(BlockNodeAllowances.PROGRAM_COMPUTER.allowedTypes()),
            // 齿轮箱 = 程序计算机 + gearbox + kinetic
            minus(newSet, EnumSet.of(
                NodeType.MOVE, NodeType.ROTATE, NodeType.WAIT, NodeType.CLUTCH, NodeType.ENCODER,
                NodeType.STRESS, NodeType.RPM)));
    }

    @Test
    void sensorGainsPoseConvertAndSplit() {
        EnumSet<NodeType> oldSet = oldSensor();
        EnumSet<NodeType> newSet = BlockNodeAllowances.SENSOR.allowedTypes();
        EnumSet<NodeType> lost = EnumSet.copyOf(oldSet);
        lost.removeAll(newSet);
        EnumSet<NodeType> gained = EnumSet.copyOf(newSet);
        gained.removeAll(oldSet);
        assertTrue(lost.isEmpty(), "sensor must not lose nodes: " + lost);
        assertEquals(EnumSet.of(NodeType.POSE_CONVERT, NodeType.SPLIT), gained);
    }

    @Test
    void simpleBlocksUnchanged() {
        assertUnchanged(oldKineticGauge(), BlockNodeAllowances.KINETIC_GAUGE, "kinetic gauge");
        assertUnchanged(oldControlSeat(), BlockNodeAllowances.CONTROL_SEAT, "control seat");
        assertUnchanged(oldMonitor(), BlockNodeAllowances.MONITOR, "monitor");
        assertUnchanged(oldRadar(), BlockNodeAllowances.RADAR, "radar");
        assertUnchanged(oldSpeedProxy(), BlockNodeAllowances.SPEED_PROXY, "speed proxy");
        assertUnchanged(oldTransmission(), BlockNodeAllowances.TRANSMISSION, "transmission");
    }

    private static void assertUnchanged(EnumSet<NodeType> oldSet, NodeAllowance a, String name) {
        assertEquals(oldSet, a.allowedTypes(), name + " allowed set changed");
    }

    private static EnumSet<NodeType> minus(EnumSet<NodeType> a, EnumSet<NodeType> b) {
        EnumSet<NodeType> r = EnumSet.copyOf(a);
        r.removeAll(b);
        return r;
    }
}
