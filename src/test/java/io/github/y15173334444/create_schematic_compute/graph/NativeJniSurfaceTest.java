package io.github.y15173334444.create_schematic_compute.graph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JNI 面源码锚（headless 加载不了 native，走源码扫描——PresenceOverlayLayerTest 同款手法）：
 * {@code FormulaNative} 的每个 {@code static native} 声明都必须在 Rust {@code ffi.rs} 有同名
 * 导出、在 {@code formula_native.h} 有声明，且三方无多余符号。
 *
 * <p>R3b 曾漏掉 {@code clearNodeSlot} 的 Rust 导出——Java 声明在、头文件在、cdylib 却没有
 * 这个符号；单测走 Java 兜底路径不报错，实机一旦走到 dedup 命中/done-skip 的 carrier 清
 * 即 {@code UnsatisfiedLinkError} 崩服务端 tick 循环（2026-11-22 实机实证）。本测试封口整族。
 *
 * <p>Source anchors for the JNI surface (headless cannot load the native): every
 * {@code static native} declaration in {@code FormulaNative} must have a matching export in
 * {@code ffi.rs} and a declaration in {@code formula_native.h}, with no extra symbols on
 * either side — R3b shipped {@code clearNodeSlot} in Java and the header but never in the
 * cdylib, so the dedup-hit path crashed the server tick loop with
 * {@code UnsatisfiedLinkError} in-game.
 */
class NativeJniSurfaceTest {

    private static final String FORMULA_NATIVE =
        "src/main/java/io/github/y15173334444/create_schematic_compute/graph/FormulaNative.java";
    private static final String FFI_RS = "native/formula-core/src/ffi.rs";
    private static final String HEADER = "native/include/formula_native.h";

    /** JNI 导出的名字前缀（包名里每个 '_' 转义成 '_1'）。/ JNI name prefix ('_' → '_1'). */
    private static final String JNI_PREFIX =
        "Java_io_github_y15173334444_create_1schematic_1compute_graph_FormulaNative_";

    @Test
    @DisplayName("Java native 声明 == Rust 导出 / every native declaration has a Rust export")
    void javaDeclarationsMatchRustExports() throws Exception {
        Set<String> java = scan(FORMULA_NATIVE, "static\\s+native\\s+[\\w\\[\\]\\.]+\\s+(\\w+)\\s*\\(");
        Set<String> rust = scan(FFI_RS, "fn\\s+" + Pattern.quote(JNI_PREFIX) + "(\\w+)");
        assertFalse(java.isEmpty(), "no static native declarations found in FormulaNative");
        assertEquals(java, rust, diff("FormulaNative.java", java, "ffi.rs", rust));
    }

    @Test
    @DisplayName("Java native 声明 == 头文件声明 / every native declaration is in the header")
    void javaDeclarationsMatchHeader() throws Exception {
        Set<String> java = scan(FORMULA_NATIVE, "static\\s+native\\s+[\\w\\[\\]\\.]+\\s+(\\w+)\\s*\\(");
        // 头文件里的声明行以 JNI 名起头（可能换行续参，但名字总在行首）。/ Header decls start the line.
        Set<String> header = scan(HEADER, "(?m)^" + Pattern.quote(JNI_PREFIX) + "(\\w+)\\s*\\(");
        assertFalse(header.isEmpty(), "no JNI declarations found in formula_native.h");
        assertEquals(java, header, diff("FormulaNative.java", java, "formula_native.h", header));
    }

    private static Set<String> scan(String path, String regex) throws Exception {
        String text = Files.readString(Path.of(path));
        Set<String> names = new TreeSet<>();
        Matcher m = Pattern.compile(regex).matcher(text);
        while (m.find()) names.add(m.group(1));
        return names;
    }

    private static String diff(String leftName, Set<String> left, String rightName, Set<String> right) {
        Set<String> onlyLeft = new TreeSet<>(left);
        onlyLeft.removeAll(right);
        Set<String> onlyRight = new TreeSet<>(right);
        onlyRight.removeAll(left);
        return "missing in " + rightName + ": " + onlyLeft + " | extra in " + rightName + ": " + onlyRight;
    }
}
