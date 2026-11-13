package io.github.y15173334444.create_schematic_compute.graph;

/**
 * Rust 后端 JNI 门面（拍板 §六-2 手写 JNI）。R1 只落声明与加载探测——
 * **不接入 FormulaEval**（R2 构建/打包、R3 接线）；头文件 = native/include/formula_native.h。
 *
 * <p>会话与调用语义见头文件注释。加载失败（陌生平台/缺 natives）→ {@link #AVAILABLE}
 * 为 false，求值走 Java 解释器兜底（双路径由对拍测试网锁定同语义）。
 * exec 的每 tick 调用数收敛规划见探索文档 §4.5（过渡逐节点 → 拓扑后每依赖波一批）。
 *
 * <p>JNI facade for the Rust formula engine. R1 ships declarations + load probing
 * only; wiring into FormulaEval is R3. On any load failure the Java interpreter
 * stays the fallback (parity-locked).
 */
public final class FormulaNative {

    private FormulaNative() {}

    /** 库名（cdylib 产物名，R2 定稿跨平台后缀）。 / cdylib name (R2 finalizes). */
    static final String LIB_NAME = "formula_native";

    /** abi 版本（与头文件/bridge.rs 契约同步递增；不匹配 = 拒载走兜底）。 */
    public static final int ABI_VERSION = 1;

    /** native 库是否可用（加载探测一次，进程级）。 / whether the native lib loaded. */
    public static final boolean AVAILABLE = probe();

    private static boolean probe() {
        try {
            System.loadLibrary(LIB_NAME);
            return abiVersion() == ABI_VERSION;
        } catch (Throwable t) {
            return false;
        }
    }

    // ── native 声明（与 formula_native.h 一一同名；session 由服务端线程独占） ──

    private static native long sessionCreate();

    private static native void sessionDestroy(long session);

    private static native int programUpload(long session, byte[] program);

    private static native int programRelease(long session, int programId);

    private static native int exec(long session, int programId,
                                   double[] inputs, byte[] carrierIn,
                                   long sliceNanos,
                                   double[] outputs, byte[] carrierOut);

    private static native int abiVersion();
}
