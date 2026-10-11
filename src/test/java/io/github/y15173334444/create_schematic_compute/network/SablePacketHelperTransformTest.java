package io.github.y15173334444.create_schematic_compute.network;

import dev.ryanhcode.sable.companion.math.Pose3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P2-20 回归锚：{@link SablePacketHelper#extractPoseTransform(Pose3dc)} 的纯逻辑部分。
 * 旧 {@code subTransformCache} 以"原点坐标"为键且永不失效——结构原地旋转时位置不变、
 * 四元数已变，终端扫描持续用陈旧朝向（静默错误）+ 条目永久累积。缓存已删除、变换改为
 * 每次扫描实时提取，本测试锁定该语义：
 *  - 同一位置、不同朝向 → 两次提取结果不同（若有位置键缓存，第二次会命中陈旧值）
 *  - 同一位姿重复提取结果稳定（无跨调用状态）
 *  - 数组布局 [ox,oy,oz,rpx,rpy,rpz,qx,qy,qz,qw]；旋转中心缺失回退零向量（旧版 Sable 兼容，
 *    与已退役 SableReflection.extractFullTransform 语义一致）；null 位姿/分量 → null（fail-soft）
 *
 * 测试替身直接用 Sable 具体类 {@link Pose3d}：其 4 参构造器对字段原样存储（javap 核实无
 * null 检查），因此可以构造出旋转中心/朝向/位置为 null 的真实位姿对象——恰好复现旧版
 * Sable 与异常 Sable 状态下的分量缺失场景。{@code Pose3dc} 是密封接口，匿名实现不可用。
 *
 * P2-20 regression anchor: the pure logic of
 * {@link SablePacketHelper#extractPoseTransform(Pose3dc)}. The old {@code subTransformCache}
 * keyed by origin coordinates and never invalidated — a structure rotating in place kept its
 * position while its quaternion changed, so terminal scans kept using a stale orientation
 * (silent wrongness) and entries accumulated forever. The cache is gone and transforms are
 * now extracted live per scan; these tests pin that semantics:
 *  - same position, different orientation → the two extractions differ (a position-keyed
 *    cache would serve the stale first value for the second call)
 *  - repeated extraction of one pose is stable (no cross-call state)
 *  - array layout [ox,oy,oz,rpx,rpy,rpz,qx,qy,qz,qw]; null rotation point falls back to
 *    zeros (older-Sable parity with the retired SableReflection.extractFullTransform);
 *    null pose / null components → null (fail-soft)
 *
 * The test double is Sable's concrete {@link Pose3d}: its 4-arg constructor stores the
 * fields verbatim (javap-verified, no null checks), so a real pose with a null rotation
 * point / orientation / position can be built — exactly the missing-component shapes of
 * older or misbehaving Sable states. {@code Pose3dc} is a sealed interface, so anonymous
 * implementations are not available.
 */
class SablePacketHelperTransformTest {

    private static final Vector3d ORIGIN = new Vector3d(10.0, 64.0, -20.0);
    private static final Quaterniond IDENTITY = new Quaterniond(0, 0, 0, 1);
    /** 绕 Y 轴约 45° 的四元数（与 IDENTITY 逐分量可区分）。 */
    private static final Quaterniond YAW45 = new Quaterniond(0, 0.3826834323650898, 0, 0.9238795325112867);
    private static final Vector3d UNIT_SCALE = new Vector3d(1, 1, 1);

    @Test
    void arrayLayoutMatchesContract() {
        // [0..2]=原点，[3..5]=旋转中心，[6..9]=四元数 (x,y,z,w) —— scanDevices 按下标取值。
        // [0..2]=origin, [3..5]=rotation pivot, [6..9]=quaternion (x,y,z,w) — read by index in scanDevices.
        double[] t = SablePacketHelper.extractPoseTransform(
            new Pose3d(ORIGIN, IDENTITY, new Vector3d(4, 5, 6), UNIT_SCALE));
        assertNotNull(t);
        assertEquals(10, t.length);
        assertEquals(10.0, t[0]); assertEquals(64.0, t[1]); assertEquals(-20.0, t[2]);
        assertEquals(4.0, t[3]);  assertEquals(5.0, t[4]);  assertEquals(6.0, t[5]);
        assertEquals(0.0, t[6]);  assertEquals(0.0, t[7]);  assertEquals(0.0, t[8]); assertEquals(1.0, t[9]);
    }

    @Test
    void nullRotationPointFallsBackToZero() {
        // 旧版 Sable 无旋转中心：回退 {0,0,0} 而非失败（与退役的 extractFullTransform 一致）。
        // Older Sable has no rotation point: fall back to {0,0,0} instead of failing
        // (parity with the retired extractFullTransform).
        double[] t = SablePacketHelper.extractPoseTransform(new Pose3d(ORIGIN, IDENTITY, null, UNIT_SCALE));
        assertNotNull(t);
        assertEquals(0.0, t[3]); assertEquals(0.0, t[4]); assertEquals(0.0, t[5]);
        assertEquals(1.0, t[9], "四元数不受旋转中心缺失影响 / quaternion unaffected by missing pivot");
    }

    @Test
    void orientationChangeIsReflectedImmediately() {
        // P2-20 核心断言：同原点、朝向改变 → 实时提取出新的四元数。
        // 旧位置键缓存的病灶正是这里：第二次提取会命中第一次的陈旧值。
        // The core P2-20 assertion: same origin, changed orientation → the live extraction
        // yields the new quaternion. This is exactly where the old position-keyed cache
        // failed: the second extraction would hit the stale first value.
        double[] before = SablePacketHelper.extractPoseTransform(new Pose3d(ORIGIN, IDENTITY, null, UNIT_SCALE));
        double[] after = SablePacketHelper.extractPoseTransform(new Pose3d(ORIGIN, YAW45, null, UNIT_SCALE));
        assertNotNull(before); assertNotNull(after);
        // 原点相同 / same origin
        for (int i = 0; i < 3; i++) assertEquals(before[i], after[i], "原点分量应一致 / origin components must match");
        // 四元数已变 / quaternion changed
        boolean quatChanged = false;
        for (int i = 6; i < 10; i++) if (Double.compare(before[i], after[i]) != 0) quatChanged = true;
        assertTrue(quatChanged, "原地旋转后朝向必须立即反映在变换中（P2-20）/ in-place rotation must be reflected immediately");
    }

    @Test
    void repeatedExtractionIsStable() {
        // 同一位姿重复提取结果一致——证明无跨调用缓存状态（每次都实时计算）。
        // Repeated extraction of one pose is identical — proves no cross-call cached state
        // (every call computes live).
        Pose3d p = new Pose3d(ORIGIN, YAW45, new Vector3d(1, 2, 3), UNIT_SCALE);
        double[] a = SablePacketHelper.extractPoseTransform(p);
        double[] b = SablePacketHelper.extractPoseTransform(p);
        assertArrayEquals(a, b, 0.0);
    }

    @Test
    void nullPoseOrComponentsReturnNull() {
        // fail-soft：null 位姿 / null position / null orientation → null（调用方跳过该子关卡）。
        // fail-soft: null pose / null position / null orientation → null (caller skips the sub-level).
        assertNull(SablePacketHelper.extractPoseTransform(null));
        assertNull(SablePacketHelper.extractPoseTransform(new Pose3d(null, IDENTITY, null, UNIT_SCALE)));
        assertNull(SablePacketHelper.extractPoseTransform(new Pose3d(ORIGIN, null, null, UNIT_SCALE)));
    }
}
