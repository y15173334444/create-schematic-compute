/*
 * formula_native.h — CSC 公式求值 Rust 后端的 JNI 契约（拍板 §六-2：手写 JNI）。
 * 与 Java 侧 io.github.y15173334444.create_schematic_compute.graph.FormulaNative
 * 的 native 声明一一同名对齐；格式契约（Program/Carrier 字节码）见 formula-core
 * src/bridge.rs 模块头。R2 落地 cdylib 构建 + 打包，R3 接线 FormulaEval。
 *
 * 会话语义：session 由服务端线程独占创建/销毁（世界加载/卸载各一次），内部持有
 * 已上传 Program 与各节点寄存器/循环栈池——**非线程安全**，调用方保证单线程访问。
 * Program 字节码上传在脚本编辑时发生（非每 tick）；exec 的调用数 = 每 tick 活跃
 * 公式节点数（过渡形态），全局拓扑落地后切 tickBatch 每依赖波一次（§4.5）。
 *
 * 返回码（exec）：0=Done 1=Suspended(carrierOut 有效) 2=Shed(输出保持 lastGood)
 * 负数=错误（-1 会话无效 -2 程序 id 无效 -3 输入/输出缓冲尺寸不符 -4 carrier 解码失败）。
 */
#ifndef FORMULA_NATIVE_H
#define FORMULA_NATIVE_H

#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

/* 会话生命周期（世界级）。 */
JNIEXPORT jlong JNICALL
Java_io_github_y15173334444_create_1schematic_1compute_graph_FormulaNative_sessionCreate(JNIEnv*, jclass);

JNIEXPORT void JNICALL
Java_io_github_y15173334444_create_1schematic_1compute_graph_FormulaNative_sessionDestroy(JNIEnv*, jclass, jlong session);

/* Program 字节码上传（编辑级）。返回 programId（>=0）或负错误码。 */
JNIEXPORT jint JNICALL
Java_io_github_y15173334444_create_1schematic_1compute_graph_FormulaNative_programUpload(JNIEnv*, jclass, jlong session, jbyteArray program);

JNIEXPORT jint JNICALL
Java_io_github_y15173334444_create_1schematic_1compute_graph_FormulaNative_programRelease(JNIEnv*, jclass, jlong session, jint programId);

/* 逐节点求值（过渡形态；R3 拓扑落地后增 tickBatch 每波一次）。
 * inputs    : 按槽位序的输入值（长度 = 槽位数；未给的槽 lenient 0）
 * carrierIn : 上次挂起的 carrier 字节（可空 = 全新求值）
 * sliceNanos: 本节点配额（0 = 不限；墙钟从调用进入点起算，Java Ctx 同语义）
 * outputs   : 出参缓冲（Done 时写终值，按槽位序）
 * carrierOut: 出参缓冲（Suspended 时写入 carrier 字节；可空则挂起即丢态）
 */
JNIEXPORT jint JNICALL
Java_io_github_y15173334444_create_1schematic_1compute_graph_FormulaNative_exec(JNIEnv*, jclass,
    jlong session, jint programId,
    jdoubleArray inputs, jbyteArray carrierIn,
    jlong sliceNanos,
    jdoubleArray outputs, jbyteArray carrierOut);

/* ABI/诊断。 */
JNIEXPORT jint JNICALL
Java_io_github_y15173334444_create_1schematic_1compute_graph_FormulaNative_abiVersion(JNIEnv*, jclass);

#ifdef __cplusplus
}
#endif
#endif /* FORMULA_NATIVE_H */
