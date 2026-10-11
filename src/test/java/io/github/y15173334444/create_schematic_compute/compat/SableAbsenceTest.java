package io.github.y15173334444.create_schematic_compute.compat;

import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证「无 Sable 环境」下编译期直连类的安全回退（P2-19 退役 SableReflection 后的新语义）。
 * 用自定义类加载器排除 {@code dev.ryanhcode.*}（模拟 Sable 未安装），断言：
 *  - 直连类（SablePacketHelper / SableAccess / SablePoseHelper）可加载、可初始化
 *    （懒解析：类与静态初始化不引用任何 Sable 类型）
 *  - 守卫路径（空参入口）返回 null / 空列表 / false，不抛 NoClassDefFoundError
 *  —— 即「无 Sable 时功能关闭、不崩」的 fail-soft 语义。
 *  注意：NoClassDefFoundError 是 Error，catch(Exception) 拦不住，所以生产环境的守卫
 *  必须在触碰 Sable 类<b>之前</b>用 ModList 短路——本测试验证的正是"守卫短路路径本身
 *  不触碰 Sable 类"这一结构性质（单元测试无 FML，ModList 之后的分支无法端到端模拟，
 *  与退役前的测试口径一致）。
 *
 * Verifies safe fallback of the compile-time direct-connect classes without Sable
 * installed (the post-P2-19 semantics after SableReflection was retired): an isolated
 * classloader that refuses {@code dev.ryanhcode.*} still loads and initializes our
 * compat classes, and every guarded entry point returns null / empty / false instead of
 * throwing NoClassDefFoundError — "feature off, no crash". NoClassDefFoundError is an
 * Error that catch(Exception) cannot intercept, so production guards must short-circuit
 * via ModList <i>before</i> touching Sable classes; what this test proves is exactly that
 * structural property (the short-circuit paths never load Sable classes). As before the
 * retirement, a unit test has no FML, so the post-ModList branches cannot be simulated
 * end-to-end.
 */
class SableAbsenceTest {

    /** 自定义类加载器：对 dev.ryanhcode.* 抛 ClassNotFoundException，模拟无 Sable。 */
    private static ClassLoader noSableLoader() {
        // 用当前线程上下文 classloader 作为父（含 MC/NeoForge/JOML），但拦截 dev.ryanhcode
        ClassLoader parent = Thread.currentThread().getContextClassLoader();
        return new URLClassLoader(new URL[0], parent) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("dev.ryanhcode.")) {
                    throw new ClassNotFoundException("sable not installed: " + name);
                }
                return super.loadClass(name, resolve);
            }
        };
    }

    @Test
    void sablePacketHelperLoadsWithoutSable() throws Exception {
        // initialize=true：连静态初始化也不引用 Sable 类型（P2-19 后该类直接 import Sable
        // API，但全部位于方法体内、由守卫短路保护）。加载+初始化不崩即证明懒解析成立；
        // 若未来有人加了 Sable 类型的静态字段，此测试会以 NoClassDefFoundError 失败。
        // initialize=true: even static init must not reference Sable types (post-P2-19 the
        // class imports Sable APIs directly, but only inside method bodies protected by
        // guard short-circuits). Loading+initializing without error proves lazy resolution;
        // a future Sable-typed static field would fail this test with NoClassDefFoundError.
        ClassLoader cl = noSableLoader();
        Class<?> helper = Class.forName(
            "io.github.y15173334444.create_schematic_compute.network.SablePacketHelper", true, cl);
        assertNotNull(helper);
    }

    @Test
    void sableAccessFallsBackWithoutSable() throws Exception {
        ClassLoader cl = noSableLoader();
        Class<?> acc = Class.forName(
            "io.github.y15173334444.create_schematic_compute.compat.SableAccess", true, cl);
        Object c = acc.getMethod("getContainer", net.minecraft.world.level.Level.class)
            .invoke(null, (Object) null);
        assertNull(c, "无 Sable 时 SableAccess.getContainer 应返回 null");
    }

    @Test
    void sablePoseHelperFallsBackWithoutSable() throws Exception {
        ClassLoader cl = noSableLoader();
        Class<?> ph = Class.forName(
            "io.github.y15173334444.create_schematic_compute.compat.SablePoseHelper", true, cl);
        Object q = ph.getMethod("getSubLevelOrientationQuaternion",
                net.minecraft.world.level.Level.class, net.minecraft.core.BlockPos.class)
            .invoke(null, (Object) null, (Object) null);
        assertNull(q, "无 Sable 时 getSubLevelOrientationQuaternion 应返回 null");
    }

    @Test
    void findSubLevelBlockEntitySafeWithoutSable() throws Exception {
        // findSubLevelBlockEntity 的签名含 Sable 类型（SubLevel 参数），但其方法体
        // 只操作传入对象，无 Sable 时传 null 应返回 null 而不抛 NoClassDefFoundError
        //（懒解析——方法签名类型不触发加载）。
        // findSubLevelBlockEntity's signature references Sable types (SubLevel param),
        // but its body only operates on the passed object; with null and no Sable it
        // must return null without NoClassDefFoundError (lazy resolution).
        ClassLoader cl = noSableLoader();
        Class<?> ph = Class.forName(
            "io.github.y15173334444.create_schematic_compute.compat.SablePoseHelper", true, cl);
        // 按名字找方法（签名含 Sable 类型，无法用 getMethod(name, SubLevel.class) 直接匹配
        // 因为无 Sable classloader 拦截 dev.ryanhcode.*）。找第一个名为
        // findSubLevelBlockEntity 的方法即可，invoke(null,null,null) 验证懒加载安全。
        // Find by name (the signature references Sable types, which the no-Sable loader
        // blocks). Invoke with nulls to verify lazy-loading safety.
        java.lang.reflect.Method m = null;
        for (var candidate : ph.getMethods()) {
            if (candidate.getName().equals("findSubLevelBlockEntity")) { m = candidate; break; }
        }
        assertNotNull(m, "应找到 findSubLevelBlockEntity 方法");
        Object r = m.invoke(null, (Object) null, (Object) null);
        assertNull(r, "无 Sable 时 findSubLevelBlockEntity(null,null) 应返回 null");
    }

    @Test
    void sablePacketHelperGuardedEntriesFailSoftWithoutSable() throws Exception {
        // SablePacketHelper 的三个公共入口在空参时于 ModList 查询前短路：
        // findSubLevel → null、scanDevices → 空列表、isWithinReachableRange → false。
        // 在屏蔽 Sable 的加载器下执行这些守卫路径，不得抛 NoClassDefFoundError
        //（"功能关闭不崩"的 fail-soft 语义锚点）。
        // SablePacketHelper's three public entry points short-circuit on null args before
        // the ModList query: findSubLevel → null, scanDevices → empty list,
        // isWithinReachableRange → false. Executing those guard paths under the Sable-less
        // loader must not throw NoClassDefFoundError (the fail-soft anchor).
        ClassLoader cl = noSableLoader();
        Class<?> helper = Class.forName(
            "io.github.y15173334444.create_schematic_compute.network.SablePacketHelper", true, cl);

        Object sub = helper.getMethod("findSubLevel",
                net.minecraft.world.level.Level.class, net.minecraft.core.BlockPos.class)
            .invoke(null, (Object) null, (Object) null);
        assertNull(sub, "无 Sable 时 findSubLevel(null,null) 应返回 null");

        Object scanned = helper.getMethod("scanDevices",
                net.minecraft.server.level.ServerLevel.class, net.minecraft.core.BlockPos.class, int.class)
            .invoke(null, (Object) null, (Object) null, 0);
        assertNotNull(scanned, "无 Sable 时 scanDevices(null,null,0) 应返回空列表而非 null");
        assertTrue(((java.util.List<?>) scanned).isEmpty(),
            "无 Sable 时 scanDevices(null,null,0) 应返回空列表");

        Object reach = helper.getMethod("isWithinReachableRange",
                net.minecraft.server.level.ServerPlayer.class, net.minecraft.core.BlockPos.class, double.class)
            .invoke(null, (Object) null, (Object) null, 0.0);
        assertEquals(Boolean.FALSE, reach, "无 Sable 时 isWithinReachableRange(null,null,0) 应返回 false");
    }
}
