package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.blocks.GraphBlockEntity;
import io.github.y15173334444.create_schematic_compute.blocks.StubSableDevice;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 「Sable 结构上的图方块实体」跨类加载器名字解析的回归测试。
 * <p>
 * 用户症状：变速器/齿轮箱放在 Sable 结构上时，便携终端列表不显示自定义名。
 * 根因：Sable 将方块实体拷贝进其结构时使用独立类加载器，同一个 FQN 的
 * {@code GraphBlockEntity} 接口在 JVM 里是<b>两份</b> Class 对象 ——
 * {@code be instanceof GraphBlockEntity} 快速路径必然为 false，自定义名被跳过，
 * 列表回退到方块类型名（本地区块扫描不受影响，因此只在结构上出现）。
 * <p>
 * 复现手法：子优先类加载器（对 blocks/graph 包不委派父加载器），加载
 * {@link StubSableDevice} —— 它实现的接口与生产代码里的接口同 FQN 不同 Class，
 * 精确模拟 Sable 拷贝。断言 {@code resolveCustomName} 仍能取到自定义名。
 * <p>
 * Regression test for cross-classloader custom-name resolution of graph block
 * entities copied into Sable structures (user symptom: transmission/gearbox
 * custom names missing from the portable terminal list). Sable copies BEs with
 * its own classloader, so the same-FQN {@code GraphBlockEntity} interface exists
 * as <b>two</b> Class objects — the fast-path {@code instanceof} always fails and
 * the name falls back to the block type name. A child-first classloader loading
 * {@link StubSableDevice} reproduces exactly that; the assertion is that
 * {@code resolveCustomName} still returns the custom name.
 */
class SableDeviceNameCrossLoaderTest {

    private static final String MOD = "io.github.y15173334444.create_schematic_compute.";

    /**
     * 模拟 Sable 的类加载器：blocks/graph 包子优先（接口成为两份 Class），
     * MC/NBT 等其余类一律委派父加载器（与真实环境共享，否则连字符串都无法互通）。
     * Simulates Sable's classloader: blocks/graph child-first (the interface becomes
     * two Class objects), everything else delegated to the parent (shared, as in the
     * real environment).
     */
    private static ClassLoader sableCopyLoader() throws Exception {
        ClassLoader parent = SableDeviceNameCrossLoaderTest.class.getClassLoader();
        URL main = Paths.get("build/classes/java/main").toUri().toURL();
        URL test = Paths.get("build/classes/java/test").toUri().toURL();
        return new URLClassLoader(new URL[]{main, test}, parent) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> c = findLoadedClass(name);
                    if (c == null && (name.startsWith(MOD + "blocks.") || name.startsWith(MOD + "graph."))) {
                        try {
                            c = findClass(name);
                        } catch (ClassNotFoundException e) {
                            // 主源集里没有该类时回退父加载器（如 test 桩自身的依赖链）
                            // Fall back to the parent when the class is not in the main sources.
                        }
                    }
                    if (c == null) c = super.loadClass(name, resolve);
                    if (resolve) resolveClass(c);
                    return c;
                }
            }
        };
    }

    @Test
    void foreignCopyStillExposesCustomName() throws Exception {
        ClassLoader cl = sableCopyLoader();
        // 前置条件：接口确实是两份 Class —— 这就是 Sable 拷贝的真实形态。
        // Precondition: the interface really is two Class objects — the real Sable-copy shape.
        Class<?> foreignIface = cl.loadClass(MOD + "blocks.GraphBlockEntity");
        assertNotSame(GraphBlockEntity.class, foreignIface,
            "classloader isolation must duplicate the interface");

        Object foreign = cl.loadClass(MOD + "blocks.StubSableDevice").getConstructor().newInstance();
        // 生产判定形态：instanceof GraphBlockEntity（本加载器）对 foreign 必然为 false。
        // The production shape: instanceof against THIS loader's interface must be false.
        assertFalse(foreign instanceof GraphBlockEntity, "fast-path instanceof must fail on the copy");

        assertEquals(StubSableDevice.NAME, SablePacketHelper.resolveCustomName(foreign),
            "Sable 拷贝上的自定义名必须仍可解析 / a Sable copy must still resolve its custom name");
    }

    @Test
    void sameLoaderFastPathUnaffected() {
        assertEquals(StubSableDevice.NAME, SablePacketHelper.resolveCustomName(new StubSableDevice()),
            "同加载器（本地扫描）路径不得回归 / the same-loader (local scan) path must not regress");
    }

    @Test
    void nonGraphObjectYieldsEmpty() {
        assertEquals("", SablePacketHelper.resolveCustomName(new Object()),
            "非图方块实体回退空串（由调用方回退到类型名）/ non-graph objects yield empty (caller falls back to the type name)");
    }
}
