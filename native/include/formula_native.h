/*
 * formula_native.h — CSC 公式求值 Rust 后端的 JNI 契约（拍板 §六-2：手写 JNI）。
 * formula_native.h — JNI contract of the CSC formula Rust backend (ruling §6-2:
 * hand-written JNI).
 * 与 Java 侧 io.github.y15173334444.create_schematic_compute.graph.FormulaNative
 * 的 native 声明一一同名对齐；格式契约（Program/Carrier 字节码）见 formula-core
 * src/bridge.rs 模块头。v2（R3 接线）：输出表达式段进 Program 契约（Done 时 native
 * 对终值寄存器逐条求值输出表达式 = Java outputRpns 镜像）、exec 增 deadline/warm/
 * progress 三参、状态码 3/4 落地。R2 落地 cdylib 构建 + 打包，R3 接线 FormulaEval。
 * Native declarations align 1:1 by name with the Java-side FormulaNative; the
 * byte format contract (Program/Carrier) lives in the formula-core src/bridge.rs
 * module docs. v2 (R3 wiring): the output-expression segment joins the Program
 * contract (on Done the engine evaluates the output expressions over final
 * registers = the mirror of Java outputRpns), exec gains deadline/warm/progress,
 * and status codes 3/4 land. R2 delivered the cdylib build + packaging; R3 wires
 * FormulaEval.
 *
 * 会话语义：session 由服务端线程独占创建/销毁（懒建 + ServerStopping 销毁），
 * 内部持有已上传 Program 与各程序寄存器/循环栈复用缓冲——**非线程安全**，调用方
 * 保证单线程访问。Program 字节码上传在脚本编辑时发生（非每 tick）；exec 的调用数
 * = 每 tick 活跃公式节点数（过渡形态），批形态 tickBatch 每连续段一次（§4.7-6）。
 * Session semantics: created/destroyed exclusively on the server thread (lazy
 * ensure + ServerStopping destroy); holds uploaded Programs plus per-program
 * register/loop-stack reuse buffers — **not thread-safe**, callers guarantee
 * single-threaded access. Program bytes upload at script-edit time (not per
 * tick); per-node exec runs once per active formula node per tick (transitional
 * form), and batch-form tickBatch runs once per contiguous segment (§4.7-6).
 *
 * 返回码（exec）：0=Done 1=Suspended(carrierOut/progressOut 有效)
 * 2=Shed(MAX_ITER,输出保持 lastGood) 3=OutputShed(输出阶段撞 deadline——已完成位
 * 新鲜、余位保持 seed 原值、carrier 作废) 4=DeadlineSuspended(carrierOut 有效,
 * fromDeadline——免计 yield 走公平规则) 5=ExecError(受控求值错误:栈下溢/嵌套超深
 * ——Java catch(Exception) 同型:输出归零、carrier 作废、不进 dedup)
 * 负数=错误（-1 会话无效 -2 程序 id 无效 -3 输入缓冲超槽 -4 carrier 解码失败）。
 * Return codes (exec): 0=Done; 1=Suspended (carrierOut/progressOut valid);
 * 2=Shed (MAX_ITER, outputs hold lastGood); 3=OutputShed (output phase hit the
 * deadline — finished slots fresh, the rest keep their seeded values, carrier
 * void); 4=DeadlineSuspended (carrierOut valid, fromDeadline — yield-free per
 * the fairness rules); 5=ExecError (controlled eval error: stack underflow /
 * nesting overflow — same shape as Java catch(Exception): outputs zeroed,
 * carrier void, excluded from dedup). Negative = error (-1 invalid session,
 * -2 invalid program id, -3 input buffer exceeds slots, -4 carrier decode).
 */
#ifndef FORMULA_NATIVE_H
#define FORMULA_NATIVE_H

#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

/* 会话生命周期（世界级）。 / Session lifecycle (world-scoped). */
JNIEXPORT jlong JNICALL
Java_io_github_y15173334444_create_1schematic_1compute_graph_FormulaNative_sessionCreate(JNIEnv*, jclass);

JNIEXPORT void JNICALL
Java_io_github_y15173334444_create_1schematic_1compute_graph_FormulaNative_sessionDestroy(JNIEnv*, jclass, jlong session);

/* Program 字节码上传（编辑级）。返回 programId（>=0）或负错误码：
 * -1 会话无效 -5 字节码解码失败（R2 落地口径；编辑期暴露优于静默漂移）。
 * Program bytecode upload (edit-time); returns programId (>=0) or a negative
 * error code: -1 invalid session, -5 bytecode decode failure (R2 stance —
 * surfacing at edit time beats silent drift). */
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
 * Per-node evaluation (v2 transitional form; batch-form tickBatch runs once per
 * contiguous segment, §4.7-6).
 * inputs    : input values in slot order (length ≤ slots; missing slots lenient
 *             0; on warm resume the snapshot is poured back before writing)
 * carrierIn : carrier bytes from the last suspension (nullable = fresh evaluation)
 * sliceNanos: this node's quota (0 = unlimited; wall clock from call entry, same
 *             semantics as the Java Ctx)
 * deadlineInNanos: this tick's remaining budget (<0 = expired sentinel — hits at
 *             the first 256-op sample; 0 = unwired/unlimited; >0 = nanoseconds
 *             left. The expression machine samples the tick deadline, not slice)
 * refreshAfterResume: warm-resume flag (0/1; carrier poured back, then inputs
 *             written)
 * outputs   : out buffer (o semantics: Done writes all output-expression values;
 *             OutputShed writes finished slots, the rest keep the caller-seeded
 *             values; pour the node's current outputs in before the call = seed)
 * carrierOut: out buffer (both suspended states write carrier bytes; standard
 *             size = slots×25+1332)
 * progressOut: out f32[1] (both suspended states write the spread progress, the
 *             mirror of Java progressOf; nullable)
 */
JNIEXPORT jint JNICALL
Java_io_github_y15173334444_create_1schematic_1compute_graph_FormulaNative_exec(JNIEnv*, jclass,
    jlong session, jint programId,
    jdoubleArray inputs, jbyteArray carrierIn,
    jlong sliceNanos, jlong deadlineInNanos, jboolean refreshAfterResume,
    jdoubleArray outputs, jbyteArray carrierOut, jfloatArray progressOut);

/* 节点态槽位分配（R3b 批量形态;slotId 稳定,空洞复用;会话无效 = -1）。
 * Node-state slot allocation (R3b batch form; stable slotId, holes reused;
 * invalid session = -1). */
JNIEXPORT jint JNICALL
Java_io_github_y15173334444_create_1schematic_1compute_graph_FormulaNative_allocNodeSlot(JNIEnv*, jclass, jlong session);

/* 清空节点槽位挂起态（dedup 命中/done-skip 的 carrier 清镜像;幂等）。
 * Clears a node slot's suspended state (the carrier-clear mirror of dedup
 * hits/done-skip; idempotent). */
JNIEXPORT void JNICALL
Java_io_github_y15173334444_create_1schematic_1compute_graph_FormulaNative_clearNodeSlot(JNIEnv*, jclass, jlong session, jint slot);

/* 节点槽位挂起态的循环栈首条 k(测试/观测视图;无挂起态/空栈 = -1)。
 * First loop-stack entry k of a node slot's suspended state (test/observation
 * view; no suspended state or empty stack = -1). */
JNIEXPORT jlong JNICALL
Java_io_github_y15173334444_create_1schematic_1compute_graph_FormulaNative_nodeSlotFirstLoopK(JNIEnv*, jclass, jlong session, jint slot);

/* 批量求值（R3b,§4.7-6）：一次调用按序求值一批成员;挂起态会话内自持(carrier 不跨界);
 * 扁平缓冲 + 偏移给出每成员的输入/输出窗口;逐成员状态/进度/耗时回传;个别成员契约
 * 错误落成员状态、批继续。返回 0 = 处理完毕;-1 会话无效 -3 数组校验失败。
 * Batch evaluation (R3b, §4.7-6): one call evaluates a batch of members in
 * order; suspended state stays session-side (the carrier never crosses the
 * boundary); flat buffers with per-member input/output offset windows;
 * per-member status/progress/elapsed returned; a member contract error lands on
 * that member's status and the batch continues. Returns 0 = processed; -1
 * invalid session; -3 array validation failure. */
JNIEXPORT jint JNICALL
Java_io_github_y15173334444_create_1schematic_1compute_graph_FormulaNative_tickBatch(JNIEnv*, jclass,
    jlong session, jint count,
    jintArray nodeSlots, jintArray programIds, jintArray refreshFlags,
    jdoubleArray inputs, jintArray inputOffsets,
    jlong sliceNanos, jlong deadlineInNanos,
    jdoubleArray outputs, jintArray outputOffsets,
    jintArray statuses, jfloatArray progress, jlongArray elapsedNs);

/* ABI/诊断。 / ABI/diagnostics. */
JNIEXPORT jint JNICALL
Java_io_github_y15173334444_create_1schematic_1compute_graph_FormulaNative_abiVersion(JNIEnv*, jclass);

#ifdef __cplusplus
}
#endif
#endif /* FORMULA_NATIVE_H */
