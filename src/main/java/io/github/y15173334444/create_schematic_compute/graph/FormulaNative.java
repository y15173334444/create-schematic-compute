package io.github.y15173334444.create_schematic_compute.graph;

import java.io.InputStream;
import java.net.URL;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Rust 后端 JNI 门面（拍板 §六-2 手写 JNI）。R1 落声明与探测骨架；R2 落构建/打包/
 * 加载：cargo 产 cdylib（gradle {@code buildNative}）→ jar 内 {@code natives/<os>-<arch>/}
 * → 本类从 classpath 提取到临时缓存目录后 {@link System#load(String)}。**不接入
 * FormulaEval**（R3 接线）；头文件 = native/include/formula_native.h。
 *
 * <p>加载路径（os/arch → 目录名映射与 build.gradle 写死一致，改动双端同步）：
 * os.name 含 windows/linux/mac os → windows/linux/macos；os.arch amd64|x86_64 →
 * x86_64、aarch64|arm64 → aarch64；其余平台不支持。库文件名：windows = {@code formula_native.dll}、
 * macOS = {@code libformula_native.dylib}、Linux = {@code libformula_native.so}。提取目标 =
 * {@code java.io.tmpdir}/csc-formula-natives/{@code <os>-<arch>}/，已存在且尺寸一致则复用
 * （每次加载零重复解压）；提取/加载/abiVersion 任一失败 → {@link #AVAILABLE} 为 false，
 * 求值走 Java 解释器兜底（双路径由对拍测试网锁定同语义）。
 *
 * <p>会话与调用语义见头文件注释。exec 的每 tick 调用数收敛规划见探索文档 §4.5
 * （过渡逐节点 → 拓扑后每依赖波一批）。
 *
 * <p>JNI facade for the Rust formula engine. R2 lands build/packaging/loading: the
 * cargo cdylib ships inside the jar under {@code natives/<os>-<arch>/} (mapping
 * identical to build.gradle) and is extracted to a temp cache directory before
 * {@link System#load(String)}. On any load failure or ABI mismatch {@link #AVAILABLE}
 * stays false and the Java interpreter remains the fallback (parity-locked). Wiring
 * into FormulaEval is R3.
 */
public final class FormulaNative {

    private FormulaNative() {}

    /** 库名（= cargo [lib] name = cdylib 产物名，平台后缀由 {@link #libFileName()} 补齐）。 */
    static final String LIB_NAME = "formula_native";

    /** abi 版本（与头文件/ffi.rs 契约同步递增；不匹配 = 拒载走兜底）。 */
    public static final int ABI_VERSION = 1;

    /** 程序字节码上限（native 侧拷贝前检查；与 ffi.rs PROGRAM_MAX_BYTES 锁步）。 */
    static final int PROGRAM_MAX_BYTES = 16 * 1024 * 1024;

    /** native 库是否可用（加载探测一次，进程级）。 / whether the native lib loaded. */
    public static final boolean AVAILABLE = probe();

    private static boolean probe() {
        try {
            System.load(extractNativeLibrary());
            return abiVersion() == ABI_VERSION;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 从 classpath 提取 native 库到临时缓存目录，返回绝对路径。缓存命中口径 =
     * 文件存在且尺寸与 jar 内资源一致。提取走「临时名 + 原子 move」：进程中断不会
     * 留半截文件冒充完整库（下一次加载按尺寸不匹配重提）。
     * Extracts the classpath native library into a temp cache dir and returns the
     * absolute path; an existing cache file of equal size is reused. Extraction goes
     * through a temp name + atomic move so a killed process never leaves a truncated
     * library posing as complete (size mismatch forces a re-extract next load).
     */
    private static String extractNativeLibrary() throws Exception {
        String dir = osArchDir();
        if (dir == null) {
            throw new UnsupportedOperationException("no formula_native natives for this os/arch");
        }
        String fileName = libFileName();
        String resource = "natives/" + dir + "/" + fileName;
        URL url = FormulaNative.class.getClassLoader().getResource(resource);
        if (url == null) {
            throw new IllegalStateException("native library missing from classpath: " + resource);
        }
        long expected = url.openConnection().getContentLengthLong();
        Path cacheDir = Path.of(System.getProperty("java.io.tmpdir", "."), "csc-formula-natives", dir);
        Path target = cacheDir.resolve(fileName);
        if (expected > 0 && Files.isRegularFile(target) && Files.size(target) == expected) {
            return target.toAbsolutePath().toString();
        }
        Files.createDirectories(cacheDir);
        Path tmp = cacheDir.resolve(fileName + ".tmp-" + ProcessHandle.current().pid());
        try (InputStream in = url.openStream()) {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target.toAbsolutePath().toString();
    }

    /**
     * os/arch → natives 目录名（与 build.gradle 的 nativeOs/nativeArch 映射逐字一致；
     * 不支持的平台返回 null → AVAILABLE=false 走 Java 兜底）。 / os/arch mapping,
     * verbatim-identical to build.gradle; null = unsupported platform, Java fallback.
     */
    private static String osArchDir() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String osPart;
        if (os.contains("windows")) {
            osPart = "windows";
        } else if (os.contains("linux")) {
            osPart = "linux";
        } else if (os.contains("mac os") || os.contains("darwin")) {
            osPart = "macos";
        } else {
            return null;
        }
        String a = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String arch;
        if (a.equals("amd64") || a.equals("x86_64")) {
            arch = "x86_64";
        } else if (a.equals("aarch64") || a.equals("arm64")) {
            arch = "aarch64";
        } else {
            return null;
        }
        return osPart + "-" + arch;
    }

    /** cdylib 文件名按平台（cargo 产物名 = LIB_NAME）。 / cdylib file name per platform. */
    private static String libFileName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("windows")) {
            return LIB_NAME + ".dll";
        }
        return os.contains("mac os") || os.contains("darwin")
            ? "lib" + LIB_NAME + ".dylib"
            : "lib" + LIB_NAME + ".so";
    }

    // ── native 声明（与 formula_native.h 一一同名；session 由服务端线程独占） ──
    // 包内可见：FormulaEval（R3）与 FormulaNativeTest（R2 e2e）同包调用；private 挡测试无意义。
    // Package-private: FormulaEval (R3) and the R2 e2e test call these from the same package.

    static native long sessionCreate();

    static native void sessionDestroy(long session);

    static native int programUpload(long session, byte[] program);

    static native int programRelease(long session, int programId);

    static native int exec(long session, int programId,
                           double[] inputs, byte[] carrierIn,
                           long sliceNanos,
                           double[] outputs, byte[] carrierOut);

    static native int abiVersion();

    /**
     * 从 CSCC carrier 头部读回本次挂起写入的字节长度。exec 只回状态码，长度自描述
     * 于头部（小端）：{@code "CSCC" magic + u32 version + u32 loop_count +
     * loops×(u16 id + u64 k) + u32 reg_count + regs×(u8 shape + f64×(1|3)) + u64
     * total_iterations}——契约 = bridge.rs carrier 格式，Java 侧按头部逐段走一遍
     * （shape 0 = 标量 9B、1 = vec3 25B）。畸形头/截断返回 -1。
     * Reads the written length back out of the CSCC carrier header (contract =
     * bridge.rs): walks the self-describing header little-endian, returning -1 on any
     * malformed shape or truncation.
     */
    static int carrierLength(byte[] buf) {
        if (buf == null || buf.length < 24) {
            return -1;
        }
        if ((buf[0] & 0xFF) != 'C' || (buf[1] & 0xFF) != 'S'
                || (buf[2] & 0xFF) != 'C' || (buf[3] & 0xFF) != 'C') {
            return -1;
        }
        long loops = readU32(buf, 8);
        int off = 12 + (int) loops * 10; // 跳过 loop 栈条目 / skip the loop-stack entries
        if (off + 4 > buf.length) {
            return -1;
        }
        long regs = readU32(buf, off);
        off += 4;
        for (long r = 0; r < regs; r++) {
            if (off + 1 > buf.length) {
                return -1;
            }
            int shape = buf[off] & 0xFF;
            if (shape == 0) {
                off += 9; // S: u8 + f64 / scalar
            } else if (shape == 1) {
                off += 25; // V3: u8 + 3×f64
            } else {
                return -1; // 未知 shape = 畸形（native 侧 BadShape 同口径）/ unknown shape
            }
            if (off > buf.length) {
                return -1;
            }
        }
        off += 8; // total_iterations
        return off <= buf.length ? off : -1;
    }

    /** 小端 u32。 / little-endian u32. */
    private static long readU32(byte[] b, int off) {
        return (b[off] & 0xFFL) | ((b[off + 1] & 0xFFL) << 8)
            | ((b[off + 2] & 0xFFL) << 16) | ((b[off + 3] & 0xFFL) << 24);
    }
}
