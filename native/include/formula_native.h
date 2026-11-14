/*
 * formula_native.h — CSC 公式求值 Rust 后端的 JNI 契约（拍板 §六-2：手写 JNI）。
 * 与 Java 侧 io.github.y15173334444.create_schematic_compute.graph.FormulaNative
 * 的 native 声明一一同名对齐；格式契约（Program/Carrier 字节码）见 formula-core
 * src/bridge.rs 模块头。v2（R3 接线）：输出表达式段进 Program 契约（Done 时 native
 * 对终值寄存器逐条求值输出表达式 = Java outputRpns 镜像）、exec 增 deadline/warm/
 * progress 三参、状态码 3/4 落地。R2 落地 cdylib 构建 + 打包，R3 接线 FormulaEval。
 *
 * 会话语义：session 由服务端线程独占创建/销毁（懒建 + ServerStopping 销毁），
 * 内部持有已上传 Program 与各程序寄存器/循环栈复用缓冲——**非线程安全**，调用方
 * 保证单线程访问。Program 字节码上传在脚本编辑时发生（非每 tick）；exec 的调用数
 * = 每 tick 活跃公式节点数（过渡形态），批形态 tickBatch 每连续段一次（§4.7-6）。
 *
 * 返回码（exec）：0=Done 1=Suspended(carrierOut/progressOut 有效)
 * 2=Shed(MAX_ITER,输出保持 lastGood) 3=OutputShed(输出阶段撞 deadline——已完成位
 * 新鲜、余位保持 seed 原值、carrier 作废) 4=DeadlineSuspended(carrierOut 有效,
 * fromDeadline——免计 yield 走公平规则) 5=ExecError(受控求值错误:栈下溢/嵌套超深
 * ——Java catch(Exception) 同型:输出归零、carrier 作废、不进 dedup)
 * 负数=错误（-1 会话无效 -2 程序 id 无效 -3 输入缓冲超槽 -4 carrier 解码失败）。
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

/* Program 字节码上传（编辑级）。返回 programId（>=0）或负错误码：
 * -1 会话无效 -5 字节码解码失败（R2 落地口径；编辑期暴露优于静默漂移）。 */
JNIEXPORT jint JNICALL
Java_io_github_y15173334444_create_1schematic_1compute_graph_FormulaNative_programUpload(JNIEnv*, jclass, jlong session, jbyteArray program);

JNIEXPORT jint JNICALL
Java_io_github_y15173334444_create_1schematic_1compute_graph_FormulaNative_programRelease(JNIEnv*, jclass, jlong session, jint programId);

/* 逐节点求值（v2 过渡形态；批形态 tickBatch 每连续段一次，§4.7-6）。
 * inputs    : 按槽位序的输入值（长度 ≤ 槽数；未给的槽 lenient 0；warm 续算时回灌后再写）
 * carrierIn : 上次挂起的 carrier 字节（可空 = 全新求值）
 * sliceNanos: 本节点配额（0 = 不限；墙钟从调用进入点起算，Java Ctx 同语义）
 * deadlineInNanos: 本 tick 预算剩余（<0 = 已过期哨兵——首个 256-op 采样即命中；
 *             0 = 未接线不限；>0 = 剩余纳秒。表达式机采样查 tick deadline 非 slice）
 * refreshAfterResume: warm 续算旗标（0/1；carrier 回灌后再写 inputs）
 * outputs   : 出参缓冲（o 语义：Done 全量写输出表达式值；OutputShed 写已完成位、
 *             余位保持调用方 seed 的原值；调用前灌入节点当前输出 = seed）
 * carrierOut: 出参缓冲（挂起两态写 carrier 字节；标准尺寸 = slots×25+1332）
 * progressOut: 出参 f32[1]（挂起两态写 spread 进度，Java progressOf 镜像；可空）
 */
JNIEXPORT jint JNICALL
Java_io_github_y15173334444_create_1schematic_1compute_graph_FormulaNative_exec(JNIEnv*, jclass,
    jlong session, jint programId,
    jdoubleArray inputs, jbyteArray carrierIn,
    jlong sliceNanos, jlong deadlineInNanos, jboolean refreshAfterResume,
    jdoubleArray outputs, jbyteArray carrierOut, jfloatArray progressOut);

/* ABI/诊断。 */
JNIEXPORT jint JNICALL
Java_io_github_y15173334444_create_1schematic_1compute_graph_FormulaNative_abiVersion(JNIEnv*, jclass);

#ifdef __cplusplus
}
#endif
#endif /* FORMULA_NATIVE_H */
