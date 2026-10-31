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

    /** 蓝图旧黑名单取反：全类型 − 34 项排除。
     *  再减「迁移后新增」类型（如音频节点）：它们晚于 2026-09-25 迁移，不属于迁移对照，
     *  蓝图本就不含（AUDIO 类只给功放），不应算作蓝图「失去」的节点。
     *  Blueprint old blocklist negated: all types − 34 exclusions, minus post-migration
     *  additions (e.g. audio nodes) which postdate the 2026-09-25 migration baseline. */
    private static EnumSet<NodeType> oldBlueprint() {
        EnumSet<NodeType> s = EnumSet.allOf(NodeType.class);
        // CUSTOM 哨兵（公开 API 波 1）不属于迁移对照的旧集——迁移前它不存在，不算「失去」。
        // The CUSTOM sentinel (public-API wave 1) is anachronistic to the migration baseline —
        // it did not exist before the migration and is not a "lost" node.
        s.remove(NodeType.CUSTOM);
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
            NodeType.TX_OUT,
            // 迁移后新增（音频类，功放/音响专属；频段读取由 BUS_IN 音频分支承担，无专属读取节点）
            NodeType.MUSIC, NodeType.AMP, NodeType.AUDIO_OUT,
            NodeType.CHANNEL, NodeType.SPEAKER_PLAY, NodeType.WSHAPE));
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
        assertEquals(2, BlockNodeAllowances.AMPLIFIER.exclusions().size(),
            "SPEAKER_PLAY (speaker-host playback sink) + AUDIO_OUT (only a speaker truly emits)");
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
        // 公开 API 波 1 批 C：蓝图收编 CUSTOM 类（验收①闭环宿主；机械默认可否决）——
        // 新增 = 恰好 CUSTOM 哨兵一个（服务端注册表 id 校验把关，未注册 id 仍进不来）。
        // Wave-1 batch C: the blueprint host admits the CUSTOM category (acceptance-1's
        // loop host; mechanical default, reviewable) — the gain is exactly the CUSTOM
        // sentinel, with the server-side registered-id check guarding the door.
        assertEquals(EnumSet.of(NodeType.CUSTOM), gained, "blueprint gains exactly the CUSTOM sentinel");
        assertTrue(newSet.contains(NodeType.ACCUMULATOR));
        assertTrue(newSet.contains(NodeType.INTEGRATOR));
        assertTrue(newSet.contains(NodeType.POSE_CONVERT));
        assertTrue(newSet.contains(NodeType.SPLIT));
    }

    @Test
    void programComputerGainsDeclared24() {
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
            // logic 比较 + AND/OR
            NodeType.GT, NodeType.LT, NodeType.GE, NodeType.LE, NodeType.EQ,
            NodeType.AND, NodeType.OR,
            // math_advanced 缺口
            NodeType.FORMULA, NodeType.INTERP,
            // control 整类
            NodeType.PID, NodeType.PID_POWER, NodeType.CLAMP, NodeType.MAP
        ), gained);
        assertEquals(24, gained.size());
    }

    @Test
    void cncGearboxFunctionalSurface() {
        EnumSet<NodeType> oldSet = oldCncGearbox();
        EnumSet<NodeType> newSet = BlockNodeAllowances.CNC_GEARBOX.allowedTypes();
        EnumSet<NodeType> lost = EnumSet.copyOf(oldSet);
        lost.removeAll(newSet);
        EnumSet<NodeType> gained = EnumSet.copyOf(newSet);
        gained.removeAll(oldSet);
        // 2026-10-24 按功能面指派（用户裁定）：去高级运算（DIRECTION 随类离开）+ 隐藏
        // 控制/累计/动力读数三组（ACCUMULATOR/INTEGRATOR/STRESS/RPM 随类离开）。
        // Functional-surface assignment (user decision): math_advanced withheld
        // (DIRECTION leaves with the category) plus the control, accumulator and
        // kinetic groups hidden whole-category.
        assertEquals(EnumSet.of(NodeType.DIRECTION, NodeType.ACCUMULATOR, NodeType.INTEGRATOR,
            NodeType.STRESS, NodeType.RPM), lost, "only DIRECTION + the three hidden groups leave");
        assertEquals(EnumSet.of(
            // math_basic 补齐 11
            NodeType.ADD, NodeType.SUB, NodeType.MUL, NodeType.DIV, NodeType.MOD,
            NodeType.POW, NodeType.ROOT, NodeType.ABS, NodeType.CEIL, NodeType.FLOOR,
            NodeType.ROUND,
            // logic 比较 + AND/OR
            NodeType.GT, NodeType.LT, NodeType.GE, NodeType.LE, NodeType.EQ,
            NodeType.AND, NodeType.OR
        ), gained);
        assertEquals(59, newSet.size(), "8 categories · 59 nodes");
        assertFalse(newSet.contains(NodeType.FORMULA));
        assertFalse(newSet.contains(NodeType.INTERP));
        assertFalse(newSet.contains(NodeType.PID));
    }

    /** 功放 2026-10-24 按功能面指派后的完整允许面：音频（去 SPEAKER_PLAY）+ 控制面 +
     *  时序/触发逻辑（含比较）+ 调试。
     *  The amplifier's functional-surface set (2026-10-24): audio minus SPEAKER_PLAY +
     *  the control plane + sequential/trigger logic (compare rides the whole category)
     *  + debug. */
    private static EnumSet<NodeType> curatedAmplifier() {
        return EnumSet.of(
            NodeType.CONST, NodeType.REDSTONE_IN, NodeType.PRIVATE_IN, NodeType.BUS_IN,
            NodeType.REDSTONE_OUT, NodeType.PRIVATE_OUT, NodeType.BUS_OUT,
            NodeType.ACCUMULATOR, NodeType.INTEGRATOR,
            NodeType.DELAY, NodeType.LATCH, NodeType.T_FLIPFLOP, NodeType.PULSE_EXTEND,
            NodeType.LOOP, NodeType.FUSE,
            NodeType.GT, NodeType.LT, NodeType.GE, NodeType.LE, NodeType.EQ,
            NodeType.BOOL, NodeType.GATE, NodeType.AND, NodeType.OR,
            NodeType.RELAY_A, NodeType.RELAY_B,
            NodeType.MUSIC, NodeType.AMP, NodeType.WSHAPE, NodeType.CHANNEL,
            NodeType.DEBUG_SIGNAL_GEN, NodeType.DEBUG_PROBE, NodeType.COMMENT);
    }

    @Test
    void amplifierMenuIsCurated() {
        assertEquals(curatedAmplifier(), BlockNodeAllowances.AMPLIFIER.allowedTypes(),
            "amplifier allowed set changed");
        assertFalse(BlockNodeAllowances.AMPLIFIER.allows(NodeType.SPEAKER_PLAY),
            "SPEAKER_PLAY is the speaker host's playback sink, never offered on the amplifier");
        assertFalse(BlockNodeAllowances.AMPLIFIER.allows(NodeType.AUDIO_OUT),
            "AUDIO_OUT promises sound output - only a speaker emits; external publishing rides the audio-carrying BUS_OUT (v1.2.6.2)");
        // 功能面之外整类不在：数学/三角/脚本/控制
        assertFalse(BlockNodeAllowances.AMPLIFIER.allows(NodeType.ADD));
        assertFalse(BlockNodeAllowances.AMPLIFIER.allows(NodeType.SIN));
        assertFalse(BlockNodeAllowances.AMPLIFIER.allows(NodeType.FORMULA));
        assertFalse(BlockNodeAllowances.AMPLIFIER.allows(NodeType.PID));
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
    void kineticGaugeGainsAnd() {
        EnumSet<NodeType> oldSet = oldKineticGauge();
        EnumSet<NodeType> newSet = BlockNodeAllowances.KINETIC_GAUGE.allowedTypes();
        assertTrue(minus(oldSet, newSet).isEmpty(), "kinetic gauge must not lose nodes");
        assertEquals(EnumSet.of(NodeType.AND), minus(newSet, oldSet),
            "only the new AND node joins (LOGIC category, 2026-10-18)");
    }

    @Test
    void simpleBlocksUnchanged() {
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
