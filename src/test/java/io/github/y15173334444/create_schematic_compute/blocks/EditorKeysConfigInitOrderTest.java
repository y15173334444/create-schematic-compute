package io.github.y15173334444.create_schematic_compute.blocks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 键位配置初始化序守卫：{@code EditorKeys} 的 static 块在类初始化序里调用 {@code load()}，
 * 而 Java 静态初始化按<b>源码声明顺序</b>执行——{@code CONFIG_PATH} 必须声明在块内
 * {@code load()} 调用之前，否则块读到 null 路径，{@code Path.of(null)} 抛 NPE、被
 * {@code load()} 的 catch 静默吞掉：配置从未在启动时加载、表恒为出厂默认，且下一次保存
 * 会把文件整表冲成默认（a80fe1a 为测试钩子去 final 后静默发生——非 final 字段不会被
 * 编译期内联，final 常量才会；无头测试经 {@code setConfigPathForTest} 绕开 clinit，从未暴露）。
 * <p>类初始化完成后行为上已无从观测 clinit 顺序，源序守卫是唯一能运行时钉住它的方式。
 * Keybinding config init-order guard: {@code EditorKeys}' static block calls {@code load()}
 * during class init, and Java runs static initialization in <b>source-declaration order</b> —
 * {@code CONFIG_PATH} must be declared before the block's {@code load()} call, or the block
 * reads a null path and the NPE dies silently in {@code load()}'s catch: the config never
 * loads at boot, the table stays factory, and the next save rewrites the file to factory
 * wholesale (a80fe1a dropped {@code final} for the test hook and this happened silently — a
 * non-final field is never compile-time inlined, only final constants are; the headless tests
 * bypass clinit via {@code setConfigPathForTest} and never saw it). Clinit ordering is
 * unobservable once the class is initialized — a source-order guard is the only runtime pin.
 */
class EditorKeysConfigInitOrderTest {

    @Test
    @DisplayName("CONFIG_PATH 声明先于 static 块的 load() 调用 / CONFIG_PATH is declared before the static block's load()")
    void configPathDeclaredBeforeStaticLoad() throws Exception {
        var src = Files.readString(Path.of(
            "src/main/java/io/github/y15173334444/create_schematic_compute/blocks/EditorKeys.java"));
        int decl = src.indexOf("CONFIG_PATH = \"config/");
        int load = src.indexOf("load();");
        assertTrue(decl >= 0, "CONFIG_PATH 初始化声明必须存在 / the CONFIG_PATH initializer must exist");
        assertTrue(load >= 0, "static 块的 load() 调用必须存在 / the static block's load() call must exist");
        assertTrue(decl < load,
            "CONFIG_PATH 声明必须先于 static 块的 load()——声明落在块后会让 clinit 读到 null 路径，"
                + "键位配置静默不加载（机制与教训见 EditorKeys.CONFIG_PATH 字段注释）/\n"
                + "CONFIG_PATH must be declared BEFORE the static block's load(): a later declaration "
                + "makes clinit read a null path and the keybinding config silently never loads "
                + "(mechanism and history in the EditorKeys.CONFIG_PATH field comment)");
    }
}
