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

    /** abi 版本（与头文件/ffi.rs 契约同步递增；不匹配 = 拒载走兜底）。v2：输出表达式
     *  段进 Program 契约、exec 增 deadline/warm/progress 三参、状态码 3/4 落地。 */
    public static final int ABI_VERSION = 2;

    /** 程序字节码上限（native 侧拷贝前检查；与 ffi.rs PROGRAM_MAX_BYTES 锁步）。 */
    static final int PROGRAM_MAX_BYTES = 16 * 1024 * 1024;

    /**
     * carrierOut 出参缓冲的标准尺寸公式（§4.7-1）：{@code slots×25 + 1332} —— 24B 头
     * （magic+version+loop_count）+ 循环栈帽 128×10B（语句嵌套帽，合法程序必然装下）
     * + 4B 寄存器计数 + slots×25B（每寄存器最坏 vec3 形态）+ 8B 迭代数。调用方按
     * 程序槽数取缓冲，native 侧的受控拒绝（装不下 = -3）作为防御面兜底。
     * Standard carrierOut size formula (§4.7-1): slots×25 + 1332 — the 24B header +
     * the 128-deep loop-stack cap (the statement nesting cap; every legit program
     * fits) + 4B register count + slots×25B (worst-case vec3 per register) + 8B
     * iteration count. Callers size the buffer from the program's slot count; the
     * native side's controlled rejection (undersized = -3) stays as the guard.
     */
    static int carrierBufferFor(int slots) {
        return slots * 25 + 1332;
    }

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
     * 文件存在且尺寸一致 **且 CRC32 一致**——只看尺寸的旧口径在「重建后尺寸恰好
     * 相同」的 dll 上会拿 stale 件冒充新库（R3 批实测踩坑：修复版进不了测试），
     * 内容校验从构造上排除。提取走「临时名 + 原子 move」：进程中断不会留半截文件
     * 冒充完整库（下一次加载按内容不匹配重提）。
     * Extracts the classpath native library into a temp cache dir and returns the
     * absolute path; an existing cache file is reused only when both size AND CRC32
     * match — the old size-only check once let a stale dll (an identical-size
     * rebuild) pose as the fixed build, so content verification closes that hole.
     * Extraction goes through a temp name + atomic move so a killed process never
     * leaves a truncated library posing as complete (content mismatch forces a
     * re-extract next load).
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
        byte[] jarBytes;
        try (InputStream in = url.openStream()) {
            jarBytes = in.readAllBytes();
        }
        long expectedCrc = crc32(jarBytes);
        Path cacheDir = Path.of(System.getProperty("java.io.tmpdir", "."), "csc-formula-natives", dir);
        Path target = cacheDir.resolve(fileName);
        if (jarBytes.length > 0 && Files.isRegularFile(target)
                && Files.size(target) == jarBytes.length && crc32(Files.readAllBytes(target)) == expectedCrc) {
            return target.toAbsolutePath().toString();
        }
        Files.createDirectories(cacheDir);
        Path tmp = cacheDir.resolve(fileName + ".tmp-" + ProcessHandle.current().pid());
        try (java.io.OutputStream out = Files.newOutputStream(tmp)) {
            out.write(jarBytes);
        }
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target.toAbsolutePath().toString();
    }

    /** 缓存命中判定的内容指纹。 / the cache-hit content fingerprint. */
    private static long crc32(byte[] data) {
        var crc = new java.util.zip.CRC32();
        crc.update(data);
        return crc.getValue();
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

    /**
     * 逐节点求值（v2，过渡形态；拓扑批已落地，批形态 tickBatch 为 R3b 目标）。
     * inputs    按槽位序的输入值（长度 ≤ 槽数；未给满 lenient 0；warm 续算时 = 引脚
     *           序输入按 pinSlots 预映到槽位，回灌后写入）
     * carrierIn 上次挂起的 carrier 字节（可空 = 全新求值）
     * sliceNanos       本节点配额（0 不限；墙钟从调用进入点起算，Java Ctx 同语义）
     * deadlineInNanos  本 tick 预算剩余（{@code <0} = 已过期哨兵——首个 256-op 采样即
     *           命中，与 deadlineExhausted() 恒真形态同语义；0 = 未接线不限；>0 =
     *           剩余纳秒。Java 表达式机采样的是 tick 级 deadline，不是 slice）
     * refreshAfterResume warm 续算旗标（carrier 回灌后再写 inputs）
     * outputs   出参缓冲（o 语义：Done 全量写输出表达式值；OutputShed 写已完成位、
     *           余位保持调用方 seed 的原值；调用前把节点当前输出灌入 = seed）
     * carrierOut 出参缓冲（挂起两态写 carrier 字节；尺寸按 {@link #carrierBufferFor}）
     * progressOut 出参 f32[1]（挂起两态写 spread 进度，Java progressOf 镜像）
     * 返回：0 Done 1 Suspended 2 Shed(MAX_ITER) 3 OutputShed 4 DeadlineSuspended
     * 5 ExecError(受控求值错误——Java catch(Exception) 同型)，负数 = 错误
     * （-1 会话无效 -2 程序 id 无效 -3 输入缓冲超槽 -4 carrier 解码失败）。
     */
    static native int exec(long session, int programId,
                           double[] inputs, byte[] carrierIn,
                           long sliceNanos, long deadlineInNanos, boolean refreshAfterResume,
                           double[] outputs, byte[] carrierOut, float[] progressOut);

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

    /**
     * 挂起 carrier 循环栈首条的 k（native 路径的测试/观测视图——Java 路径的
     * {@code formulaCarrier.loopStack.get(0).k} 镜像）。畸形/空循环栈 = -1。
     * 头部布局（bridge.rs carrier 契约）：magic4 + version4 + loop_count4，条目 =
     * u16 id + u64 k 紧排无对齐填充 → 首条 k 在 [14, 22)。
     * The suspended carrier's first loop-stack k (the native-path test/observability
     * view mirroring Java's formulaCarrier.loopStack.get(0).k); -1 on malformed/empty.
     */
    static long carrierFirstLoopK(byte[] buf) {
        if (buf == null || buf.length < 22) {
            return -1;
        }
        if ((buf[0] & 0xFF) != 'C' || (buf[1] & 0xFF) != 'S'
                || (buf[2] & 0xFF) != 'C' || (buf[3] & 0xFF) != 'C') {
            return -1;
        }
        if (readU32(buf, 8) < 1) {
            return -1;
        }
        long k = 0;
        for (int i = 0; i < 8; i++) {
            k |= (buf[14 + i] & 0xFFL) << (8 * i);
        }
        return k;
    }
}
