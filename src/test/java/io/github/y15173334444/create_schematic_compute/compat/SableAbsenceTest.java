package io.github.y15173334444.create_schematic_compute.compat;

import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证「无 Sable 环境」下 compat 编译期桥的安全回退。
 * 用自定义类加载器排除 {@code dev.ryanhcode.*}（模拟 Sable 未安装），
 * 加载 SableReflection / SablePoseHelper / SableAccess，断言：
 *  - 类可加载（懒加载，不因 Sable 缺席崩）
 *  - isAvailable() == false
 *  - getContainer / getSubLevelOrientationQuaternion 返回 null
 *
 * Verifies safe fallback without Sable installed: an isolated classloader
 * that refuses {@code dev.ryanhcode.*} still loads our compat classes and
 * all Sable-accessors fall back to false/null without NoClassDefFoundError.
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
    void sableReflectionLoadsWithoutSable() throws Exception {
        // 仅验证类可加载（initialize=false，避免触发 SchematicCompute 静态初始化，
        // 那需要完整 NeoForge 环境）。加载不崩即证明懒加载：无 Sable 时类能进 JVM。
        // Only verify the class can be LOADED (initialize=false avoids triggering
        // SchematicCompute's static init, which needs a full NeoForge env). Loading
        // without error proves lazy loading: the class enters the JVM with no Sable.
        ClassLoader cl = noSableLoader();
        Class<?> ref = Class.forName(
            "io.github.y15173334444.create_schematic_compute.compat.SableReflection", false, cl);
        assertNotNull(ref);
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
    void sableAccessFallsBackWithoutSable() throws Exception {
        ClassLoader cl = noSableLoader();
        Class<?> acc = Class.forName(
            "io.github.y15173334444.create_schematic_compute.compat.SableAccess", true, cl);
        Object c = acc.getMethod("getContainer", net.minecraft.world.level.Level.class)
            .invoke(null, (Object) null);
        assertNull(c, "无 Sable 时 SableAccess.getContainer 应返回 null");
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
}
