//! R2 JNI shim + 防御对抗硬化：`formula_native.h` 契约的 Rust 落地。三个层次：
//! - `Session`（纯 Rust）：已上传 Program 的槽位表（释放槽位复用，programId 稳定）
//!   + 每程序独占的 regs/loop_stack 复用缓冲——exec 走 `bridge::decode_program` 已
//!   上传的字节码 → `Exec::new/run`，引擎路径稳态零分配；挂起路径
//!   `carrier_to_bytes` 一次分配（与 bridge.rs「分配只发生在挂起时刻」同口径）。
//! - **会话句柄表（防御对抗批）**：jlong 句柄 = 全局表键（原子序号，0 恒为 null
//!   哨兵），**不再是裸指针**——double-free / use-after-free / 伪造句柄从构造上
//!   消灭。destroy 幂等（二次/敌意句柄查无此人 = 无操作）；exec 持 `Arc<Session>`，
//!   destroy 与进行中的求值并发安全；表锁短临界区（只取 Arc 副本），会话锁
//!   per-session（长求值互不阻塞）；锁中毒恢复（本层闭包不 panic，表不变式 =
//!   insert/remove 原子完成）。package-private 不是安全边界：Java 侧反射/同包
//!   类定义都摸得到这些 native——**native 侧验证一切**才是边界本身。
//! - JNI 翻译层：JVM 数组经 region API 搬运；字节码/挂起态**尺寸帽在拷贝之前**
//!   检查（敌意大数组不产生 native 侧大分配）。全部非法输入走负错误码，**绝不
//!   panic 跨 FFI**（panic 跨 FFI = abort）。
//!
//! R2 JNI shim + defense hardening: `Session` is pure Rust (slot table with stable
//! ids, per-program reusable buffers — steady-state zero-alloc); session handles
//! are global-table keys, never raw pointers (double-free/UAF/forged handles are
//! structurally impossible; destroy is idempotent; per-session locks isolate long
//! evaluations); bytecode/carrier size caps checked before any copy; every invalid
//! input returns a negative code instead of panicking across the FFI boundary.

use std::time::Duration;

use jni::objects::{JByteArray, JClass, JDoubleArray, JFloatArray, JIntArray, JLongArray};
use jni::sys::{jbyte, jfloat, jint, jlong};
use jni::JNIEnv;

use crate::bridge::{carrier_from_bytes, carrier_to_bytes, decode_program, DecodeError};
use crate::interp::{eval_output, Budget, Carrier, Exec, LoopEntry, Outcome};
use crate::value::V;

/// ABI 版本（与 Java FormulaNative.ABI_VERSION 及 formula_native.h 同步递增）。
/// ABI version, kept in lockstep with FormulaNative.ABI_VERSION and the header.
pub(crate) const ABI_VERSION: jint = 2;

// exec 返回码（契约 = formula_native.h）。 / exec return codes (contract = the header).
const OK_DONE: jint = 0;
const OK_SUSPENDED: jint = 1;
const OK_SHED: jint = 2;
/// 3 = 输出阶段撞 K6 deadline（已完成输出新鲜、撞线者与其后超帽者保持、carrier
/// 作废——Java outputShed 口径）。 / deadline hit in the output phase.
const OK_OUTPUT_SHED: jint = 3;
/// 4 = fromDeadline 挂起（carrierOut 有效；免计 yield，Java 走公平规则）。
/// / fromDeadline suspend (carrierOut valid; no yield count, Java fairness rule).
const OK_DEADLINE_SUSPENDED: jint = 4;
/// 5 = 受控求值错误（栈下溢/嵌套超深——Java catch(Exception) 同型:输出归零、carrier
/// 作废、不进 dedup）。 / controlled eval error (underflow/depth — Java
/// catch(Exception) shape: outputs zeroed, carrier dropped, no dedup).
const OK_EXEC_ERROR: jint = 5;
/// -1 会话无效（null 哨兵/过期句柄/敌意句柄）。 / invalid session (null/stale/forged).
const ERR_SESSION: jint = -1;
/// -2 程序 id 无效（未上传或已释放）。 / unknown or released program id.
const ERR_PROGRAM_ID: jint = -2;
/// -3 输入缓冲尺寸不符（超槽位数为契约外形态；未给满按 lenient 0；输出缓冲按
/// o 语义无槽位上界——v2 起 outputs = 输出引脚值，不再对齐槽数）。
/// / input buffer size mismatch (outputs lose the slot bound in v2 — they carry
/// output-pin values, not slot finals).
const ERR_BUFFER: jint = -3;
/// -4 carrier 解码失败或超尺寸帽。 / carrier decode failure or over the size cap.
const ERR_CARRIER: jint = -4;
/// -5 Program 字节码解码失败或超尺寸帽（programUpload 专用；编辑期暴露优于静默漂移）。
/// / program bytecode decode failure or over the size cap (programUpload only).
const ERR_PROGRAM_BYTES: jint = -5;

// ───────────────────────── 纯 Rust 会话核心（单测覆盖） ─────────────────────────

/// 已上传程序 + 该程序独占的求值缓冲（跨 exec 复用 → 稳态零分配）。
/// One uploaded program plus its exclusive eval buffers (reused across execs).
struct UploadedProgram {
    prog: crate::ast::Program,
    regs: Vec<V>,
    loops: Vec<LoopEntry>,
}

/// exec 终态（JNI 层映射回 jint）。挂起态携带本次写入 carrier 字节数 + spread 进度
/// （Java progressOf 镜像，来自 prog.repeat_counts 与挂起循环栈）。
/// Terminal exec status (mapped to jint by the JNI layer); suspension carries the
/// written carrier length plus the spread progress (Java progressOf mirror).
#[derive(Debug, PartialEq)]
pub enum ExecStatus {
    /// Done：输出表达式已逐条求值写入 `out`（无 OutputShed）。
    /// Done with every output evaluated and written into `out`.
    Done,
    Suspended { carrier_len: usize, progress: f32 },
    Shed,
    /// 输出阶段撞 deadline：已完成输出已写入 `out`，撞线者保持（未写）。
    /// Deadline in the output phase: completed outputs written, the hit one holds.
    OutputShed,
    DeadlineSuspended { carrier_len: usize, progress: f32 },
    /// 受控求值错误（栈下溢/嵌套超深——Java catch(Exception) 同型）。
    /// Controlled eval error (underflow / depth overflow — the Java catch(Exception) shape).
    Error,
}

/// Session 编排层错误（JNI 层映射：UnknownProgram→-2 BadCarrier→-4 BadBuffer→-3）。
/// Session-level errors (JNI mapping: -2 / -4 / -3 respectively).
#[derive(Debug, PartialEq)]
pub enum ExecError {
    UnknownProgram,
    BadCarrier,
    BadBuffer,
}

/// 会话：世界级生命周期（服务端线程独占的**使用**契约——但句柄表保证并发误用
/// 也只是数据竞争级别的串行化，绝不是内存不安全）。
/// Session: world-scoped lifetime. The server-thread-exclusive contract is about
/// serialization, never memory safety — the handle table makes concurrent misuse
/// merely serialized, never unsafe.
pub struct Session {
    /// 程序槽位表：释放只置 None 不收缩 → programId 稳定，后续上传复用空洞。
    programs: Vec<Option<UploadedProgram>>,
    /// 逐节点 native 态槽位（R3b 批量形态）：挂起 carrier 会话内自持、不跨界。
    /// 释放只置 None 保 slotId 稳定；节点删除的槽位随世界销毁回收（§4.7-2 同口径）。
    /// Per-node native state slots (R3b batch form): the suspended carrier lives in
    /// the session, never crossing the seam. Freed slots keep their ids; deleted
    /// nodes' slots reclaim at world teardown (same contract as §4.7-2).
    nodes: Vec<Option<NodeState>>,
    /// exec 内部暂存：inputs 的 V 形态 / Exec.run 终值中转（容量随首次调用到位后稳定）。
    inputs_v: Vec<V>,
    out_v: Vec<V>,
}

/// 一个节点槽位的 native 自持挂起态。 / one node slot's native-held suspend state.
#[derive(Default)]
struct NodeState {
    carrier: Option<Carrier>,
}

impl Session {
    pub fn new() -> Session {
        Session { programs: Vec::new(), nodes: Vec::new(), inputs_v: Vec::new(), out_v: Vec::new() }
    }

    /// 分配节点态槽位（空洞优先复用，slotId 稳定）。 / allocate a node-state slot.
    pub fn alloc_node_slot(&mut self) -> usize {
        match self.nodes.iter().position(|n| n.is_none()) {
            Some(free) => {
                self.nodes[free] = Some(NodeState::default());
                free
            }
            None => {
                self.nodes.push(Some(NodeState::default()));
                self.nodes.len() - 1
            }
        }
    }

    /// 解码并上传 Program 字节码，返回稳定 programId（释放槽位优先复用）。
    /// Decode + upload; returns a stable program id (freed slots are reused first).
    pub fn upload(&mut self, bytes: &[u8]) -> Result<usize, DecodeError> {
        let prog = decode_program(bytes)?;
        let uploaded = UploadedProgram { prog, regs: Vec::new(), loops: Vec::new() };
        match self.programs.iter().position(|p| p.is_none()) {
            Some(free) => {
                self.programs[free] = Some(uploaded);
                Ok(free)
            }
            None => {
                self.programs.push(Some(uploaded));
                Ok(self.programs.len() - 1)
            }
        }
    }

    /// 释放程序（槽位置空保留 id）；返回 false = id 无效或已释放。
    /// Release (slot kept, id preserved); false = unknown or already released.
    pub fn release(&mut self, id: usize) -> bool {
        match self.programs.get_mut(id) {
            Some(slot @ Some(_)) => {
                *slot = None;
                true
            }
            _ => false,
        }
    }

    /// 活跃程序的槽位数（缓冲校验依据）。 / Slot count of a live program (buffer validation base).
    pub fn program_slots(&self, id: usize) -> Option<usize> {
        self.programs.get(id).and_then(|p| p.as_ref()).map(|p| p.prog.names.len())
    }

    /// 一次求值（v2）。`inputs` 按槽位序（可短于槽位数，其余 lenient 0；超长 = 契约外）；
    /// `resume` = 上次挂起的 carrier 字节（None = 全新求值）；`refresh_inputs_after_resume`
    /// = warm 续算（回灌后再写 inputs）；`slice`/`deadline_in` = 两维预算（None 不限）；
    /// `out` = 输出引脚值（o 语义：Done/OutputShed 逐条写输出表达式值，长度任意 ≤
    /// 调用方口径——v2 起不再对齐槽数）；`carrier_out` = 挂起时收 carrier 字节
    /// （None = 挂起即丢态）。
    /// One evaluation (v2). inputs are slot-ordered (short is lenient-zero, over-length
    /// is a contract violation); resume carries the suspended carrier bytes;
    /// refresh_inputs_after_resume = warm resume (inputs land after the snapshot);
    /// slice/deadline_in are the two budget dimensions (None = unlimited); `out`
    /// carries output-pin values (the o semantics — evaluated per output expression,
    /// no slot bound since v2); a suspension serializes into carrier_out.
    pub fn exec(
        &mut self,
        id: usize,
        inputs: &[f64],
        resume: Option<&[u8]>,
        refresh_inputs_after_resume: bool,
        slice: Option<Duration>,
        deadline_in: Option<Duration>,
        out: &mut [f64],
        mut carrier_out: Option<&mut Vec<u8>>,
    ) -> Result<ExecStatus, ExecError> {
        let slot = self
            .programs
            .get_mut(id)
            .and_then(|p| p.as_mut())
            .ok_or(ExecError::UnknownProgram)?;
        let slot_n = slot.prog.names.len();
        if inputs.len() > slot_n {
            return Err(ExecError::BadBuffer);
        }
        let resume_carrier =
            resume.map(carrier_from_bytes).transpose().map_err(|_| ExecError::BadCarrier)?;
        // 暂存缓冲：容量到位后 clear/extend 不再分配（引擎路径稳态零分配的前提）。
        // 终值缓冲按全槽位（输出表达式求值要对任意变量回读）。
        // Scratch buffers: after the first calls clear/extend never allocate again;
        // the finals buffer spans all slots (output expressions read any variable).
        self.inputs_v.clear();
        self.inputs_v.extend(inputs.iter().map(|&v| V::S(v)));
        self.out_v.clear();
        self.out_v.resize(slot_n, V::ZERO);
        let budget = Budget::slice_with_deadline(slice, deadline_in);
        // 字段级拆借：Program 只读，regs/loops 独占可变（Exec 契约：调用方缓冲跨 exec 复用）。
        // Field-split borrows: Program read-only, regs/loops exclusively mutable.
        let ex = Exec::new(
            &slot.prog,
            &mut slot.regs,
            &mut slot.loops,
            &self.inputs_v,
            resume_carrier,
            refresh_inputs_after_resume,
            budget,
        );
        match ex.run(&mut self.out_v) {
            Outcome::Done => {
                // v2 输出段：对终值寄存器逐条求值输出表达式（Java outputRpns 镜像）。
                // 输出阶段预算 = 同锚 + 仅 deadline 维度（slice None：Java 输出阶段
                // 不查 slice、不推 MAX_ITER）；撞 deadline 的输出保持（不写），畸形
                // RPN lenient 0，`oi ≥ 输出条数` 恒 0（Java List.of(0.0) 同口径）。
                // v2 output section: evaluate each output expression against the final
                // registers. The output phase rides the same anchor with only the
                // deadline dimension (Java's output phase checks no slice, pushes no
                // MAX_ITER); a deadline hit holds that output (no write), malformed
                // RPNs read lenient 0, and oi ≥ output-count reads 0.
                let out_budget =
                    Budget { start: budget.start, slice: None, deadline_in: budget.deadline_in };
                let scalar_only = slot.prog.scalar_only;
                let mut shed = false;
                for (oi, o) in out.iter_mut().enumerate() {
                    match slot.prog.outputs.get(oi) {
                        Some(rpn) => match eval_output(rpn, &self.out_v, scalar_only, &out_budget) {
                            Ok(v) => *o = v,
                            Err(crate::interp::Signal::Deadline) => shed = true,
                            Err(_) => *o = 0.0,
                        },
                        None => *o = 0.0,
                    }
                }
                Ok(if shed { ExecStatus::OutputShed } else { ExecStatus::Done })
            }
            Outcome::Suspended(c) => {
                let progress = slot.prog.progress_of(&c.loop_stack);
                let bytes = carrier_to_bytes(&c);
                if let Some(buf) = carrier_out.as_deref_mut() {
                    buf.clear();
                    buf.extend_from_slice(&bytes);
                }
                Ok(ExecStatus::Suspended { carrier_len: bytes.len(), progress })
            }
            Outcome::DeadlineSuspended(c) => {
                let progress = slot.prog.progress_of(&c.loop_stack);
                let bytes = carrier_to_bytes(&c);
                if let Some(buf) = carrier_out.as_deref_mut() {
                    buf.clear();
                    buf.extend_from_slice(&bytes);
                }
                Ok(ExecStatus::DeadlineSuspended { carrier_len: bytes.len(), progress })
            }
            Outcome::Error => Ok(ExecStatus::Error),
            Outcome::Shed => Ok(ExecStatus::Shed),
        }
    }

    /// 一次批量成员求值（R3b）：挂起态从节点槽位取/存（**carrier 不跨界**），
    /// 其余与 exec 逐语义一致（warm 回灌、两维预算、输出段求值、状态分态）。
    /// 挂起态的 carrier_len 恒 0（无字节跨界；进度仍回传）。
    /// One batch-member evaluation (R3b): suspend state comes from / returns to the
    /// node slot (**carrier never crosses the seam**); everything else matches exec
    /// verbatim (warm refresh, two budget dimensions, output-section evaluation,
    /// status split). Suspended carrier_len is always 0 (no bytes cross; progress
    /// still returns).
    pub fn batch_exec(
        &mut self,
        node_slot: usize,
        id: usize,
        inputs: &[f64],
        refresh_after_resume: bool,
        slice: Option<Duration>,
        deadline_in: Option<Duration>,
        out: &mut [f64],
    ) -> Result<ExecStatus, ExecError> {
        // 先校验程序与缓冲，再取挂起态——校验失败绝不丢 carrier。
        // Validate the program and buffers BEFORE taking the suspend state — a failed
        // check never drops the carrier.
        let slot_n = self
            .programs
            .get(id)
            .and_then(|p| p.as_ref())
            .map(|p| p.prog.names.len())
            .ok_or(ExecError::UnknownProgram)?;
        if inputs.len() > slot_n {
            return Err(ExecError::BadBuffer);
        }
        let node = self
            .nodes
            .get_mut(node_slot)
            .and_then(|n| n.as_mut())
            .ok_or(ExecError::UnknownProgram)?;
        let resume = node.carrier.take();
        let slot = self
            .programs
            .get_mut(id)
            .and_then(|p| p.as_mut())
            .ok_or(ExecError::UnknownProgram)?;
        self.inputs_v.clear();
        self.inputs_v.extend(inputs.iter().map(|&v| V::S(v)));
        self.out_v.clear();
        self.out_v.resize(slot_n, V::ZERO);
        let budget = Budget::slice_with_deadline(slice, deadline_in);
        let ex = Exec::new(
            &slot.prog,
            &mut slot.regs,
            &mut slot.loops,
            &self.inputs_v,
            resume,
            refresh_after_resume,
            budget,
        );
        let prog = &slot.prog;
        let results = ex.run(&mut self.out_v);
        // 尾态写回：完成/shed/error 丢态（Java 侧状态机同口径清 done/frozen），
        // 挂起两态回存 carrier。
        // Tail-state write-back: Done/Shed/Error drop the state (the Java state
        // machine clears done/frozen the same way), both suspends store the carrier.
        match results {
            Outcome::Done => {
                node.carrier = None;
                let out_budget =
                    Budget { start: budget.start, slice: None, deadline_in: budget.deadline_in };
                let mut shed = false;
                for (oi, o) in out.iter_mut().enumerate() {
                    match prog.outputs.get(oi) {
                        Some(rpn) => match eval_output(rpn, &self.out_v, prog.scalar_only, &out_budget) {
                            Ok(v) => *o = v,
                            Err(crate::interp::Signal::Deadline) => shed = true,
                            Err(_) => *o = 0.0,
                        },
                        None => *o = 0.0,
                    }
                }
                Ok(if shed { ExecStatus::OutputShed } else { ExecStatus::Done })
            }
            Outcome::Suspended(c) => {
                let progress = prog.progress_of(&c.loop_stack);
                node.carrier = Some(c);
                Ok(ExecStatus::Suspended { carrier_len: 0, progress })
            }
            Outcome::DeadlineSuspended(c) => {
                let progress = prog.progress_of(&c.loop_stack);
                node.carrier = Some(c);
                Ok(ExecStatus::DeadlineSuspended { carrier_len: 0, progress })
            }
            Outcome::Error => {
                node.carrier = None;
                Ok(ExecStatus::Error)
            }
            Outcome::Shed => {
                node.carrier = None;
                Ok(ExecStatus::Shed)
            }
        }
    }
}

// ───────────────────────── 会话句柄表（防御对抗核心） ─────────────────────────
//
// jlong 句柄 = 全局表键；0 恒为 null 哨兵、从不发放。表值 = Arc<Mutex<Session>>：
// destroy 只从表移除（进行中的 exec 持 Arc 安全收尾），double-free/UAF 无从谈起。

use std::collections::BTreeMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, PoisonError};

/// 程序字节码上限（合法脚本 KB 级；16MB = 弹道脚本 ×1000 余量）。
/// Program bytecode cap (legit scripts are KB-scale; 16MB ≈ ×1000 the ballistic script).
const PROGRAM_MAX_BYTES: usize = 16 * 1024 * 1024;
/// 挂起态字节上限（合法最坏 ≈ 槽位数×25B + 头部 ≈ KB 级；8MB = ×4000 余量）。
/// Suspended-carrier cap (legit worst ≈ slots×25B + header ≈ KBs).
const CARRIER_MAX_BYTES: usize = 8 * 1024 * 1024;

static SESSIONS: Mutex<BTreeMap<u64, Arc<Mutex<Session>>>> = Mutex::new(BTreeMap::new());
static SESSION_SEQ: AtomicU64 = AtomicU64::new(1);

/// 表锁（中毒恢复；临界区只做 insert/remove/clone——panic 中途的不变式破坏不存在）。
/// Table lock (poison-recovering; critical sections are insert/remove/clone only).
fn lock_table() -> std::sync::MutexGuard<'static, BTreeMap<u64, Arc<Mutex<Session>>>> {
    SESSIONS.lock().unwrap_or_else(PoisonError::into_inner)
}

/// 会话查表（敌意/过期句柄 = 查无此人）。 / Table lookup (hostile/stale = unknown).
fn session_cell(session: jlong) -> Option<Arc<Mutex<Session>>> {
    if session <= 0 {
        return None;
    }
    lock_table().get(&(session as u64)).cloned()
}

/// 会话锁（中毒恢复）。 / Session lock (poison-recovering).
fn lock_session(cell: &Arc<Mutex<Session>>) -> std::sync::MutexGuard<'_, Session> {
    cell.lock().unwrap_or_else(PoisonError::into_inner)
}

// ───────────────────────── JNI 导出（formula_native.h 一一同名） ─────────────────────────

#[no_mangle]
pub extern "system" fn Java_io_github_y15173334444_create_1schematic_1compute_graph_FormulaNative_sessionCreate<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jlong {
    let id = SESSION_SEQ.fetch_add(1, Ordering::Relaxed);
    lock_table().insert(id, Arc::new(Mutex::new(Session::new())));
    id as jlong
}

#[no_mangle]
pub extern "system" fn Java_io_github_y15173334444_create_1schematic_1compute_graph_FormulaNative_sessionDestroy<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    session: jlong,
) {
    if session > 0 {
        // 幂等：二次 destroy / 敌意句柄查无此人 = 无操作（不炸、不双重释放）。
        // Idempotent: double-destroy / hostile handles are no-ops by construction.
        lock_table().remove(&(session as u64));
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_y15173334444_create_1schematic_1compute_graph_FormulaNative_programUpload<
    'local,
>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    session: jlong,
    program: JByteArray<'local>,
) -> jint {
    let Some(cell) = session_cell(session) else {
        return ERR_SESSION;
    };
    // 尺寸帽在拷贝之前（敌意大数组不产生 native 侧大分配）。
    // Size cap BEFORE the copy (hostile huge arrays never allocate natively).
    let len = match env.get_array_length(&program) {
        Ok(l) => l as usize,
        Err(_) => return ERR_PROGRAM_BYTES,
    };
    if len > PROGRAM_MAX_BYTES {
        return ERR_PROGRAM_BYTES;
    }
    let bytes = match env.convert_byte_array(program) {
        Ok(b) => b,
        Err(_) => return ERR_PROGRAM_BYTES,
    };
    let mut s = lock_session(&cell);
    match s.upload(&bytes) {
        Ok(id) => id as jint,
        Err(_) => ERR_PROGRAM_BYTES,
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_y15173334444_create_1schematic_1compute_graph_FormulaNative_programRelease<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    session: jlong,
    program_id: jint,
) -> jint {
    let Some(cell) = session_cell(session) else {
        return ERR_SESSION;
    };
    let mut s = lock_session(&cell);
    if s.release(program_id as usize) {
        0
    } else {
        ERR_PROGRAM_ID
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_y15173334444_create_1schematic_1compute_graph_FormulaNative_exec<
    'local,
>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    session: jlong,
    program_id: jint,
    inputs: JDoubleArray<'local>,
    carrier_in: JByteArray<'local>,
    slice_nanos: jlong,
    deadline_in_nanos: jlong,
    refresh_after_resume: jint,
    outputs: JDoubleArray<'local>,
    carrier_out: JByteArray<'local>,
    progress_out: JFloatArray<'local>,
) -> jint {
    let Some(cell) = session_cell(session) else {
        return ERR_SESSION;
    };
    let mut s = lock_session(&cell);
    // 缓冲长度：null 数组 = 空缓冲口径；JVM 侧取长失败按缓冲契约外处置。
    // Buffer lengths: null arrays read as empty; JVM-side failures map to the buffer code.
    let in_len = if inputs.is_null() {
        0
    } else {
        match env.get_array_length(&inputs) {
            Ok(l) => l as usize,
            Err(_) => return ERR_BUFFER,
        }
    };
    let out_len = if outputs.is_null() {
        0
    } else {
        match env.get_array_length(&outputs) {
            Ok(l) => l as usize,
            Err(_) => return ERR_BUFFER,
        }
    };
    // 槽位数校验先于任何数组搬运（错误码优先，杜绝半途丢弃的半写状态）；v2 起
    // 只有 inputs 受槽位上界约束——outputs 承载输出引脚值（o 语义），长度按调用方。
    // Slot validation precedes any array copy (errors precede partial writes); since
    // v2 only inputs are slot-bounded — outputs carry output-pin values (o semantics).
    let Some(slot_n) = s.program_slots(program_id as usize) else {
        return ERR_PROGRAM_ID;
    };
    if in_len > slot_n {
        return ERR_BUFFER;
    }
    // ① carrierIn 尺寸帽 + 解码（resume 路径；坏字节 = -4）。帽在拷贝之前。
    // ① carrierIn size cap + decode (resume path; bad bytes = -4). Cap precedes the copy.
    let resume: Option<Vec<u8>> = if carrier_in.is_null() {
        None
    } else {
        let carrier_len = match env.get_array_length(&carrier_in) {
            Ok(l) => l as usize,
            Err(_) => return ERR_CARRIER,
        };
        if carrier_len > CARRIER_MAX_BYTES {
            return ERR_CARRIER;
        }
        match env.convert_byte_array(carrier_in) {
            Ok(b) => Some(b),
            Err(_) => return ERR_CARRIER,
        }
    };
    // ② JVM 数组 ↔ 引擎缓冲的胶水搬运（region API；过渡形态固有开销，见模块头）。
    // ② Glue copies between JVM arrays and engine buffers (region API; transitional cost).
    let mut in_buf = vec![0.0f64; in_len];
    if in_len > 0 && env.get_double_array_region(inputs, 0, &mut in_buf).is_err() {
        return ERR_BUFFER;
    }
    let mut out_buf = vec![0.0f64; out_len];
    let mut carrier_buf: Vec<u8> = Vec::new();
    // 两维预算：slice = 本节点配额（0 不限）；deadline_in = tick 预算剩余（负 = 已
    // 过期哨兵 → Some(ZERO)：首个 256-op 采样即命中，与 Java deadlineExhausted()
    // 恒真形态同语义；0 = 未接线不限）。
    // Two budget dimensions: slice = per-node quota (0 unlimited); deadline_in = the
    // tick's remaining budget (negative = expired sentinel → Some(ZERO): the first
    // 256-op sample fires, matching Java's permanently-true deadlineExhausted shape;
    // 0 = unwired, unlimited).
    let slice =
        if slice_nanos > 0 { Some(Duration::from_nanos(slice_nanos as u64)) } else { None };
    let deadline_in = if deadline_in_nanos < 0 {
        Some(Duration::ZERO)
    } else if deadline_in_nanos > 0 {
        Some(Duration::from_nanos(deadline_in_nanos as u64))
    } else {
        None
    };
    let status = match s.exec(
        program_id as usize,
        &in_buf,
        resume.as_deref(),
        refresh_after_resume != 0,
        slice,
        deadline_in,
        &mut out_buf,
        Some(&mut carrier_buf),
    ) {
        Ok(st) => st,
        Err(ExecError::UnknownProgram) => return ERR_PROGRAM_ID,
        Err(ExecError::BadCarrier) => return ERR_CARRIER,
        Err(ExecError::BadBuffer) => return ERR_BUFFER,
    };
    // 进度出参（挂起两态写入；null = 调用方不关心）。
    // Progress out-param (written on both suspend statuses; null = caller opts out).
    let write_progress = |env: &JNIEnv<'local>, progress: f32| -> bool {
        progress_out.is_null()
            || env.set_float_array_region(&progress_out, 0, &[progress as jfloat]).is_ok()
    };
    match status {
        ExecStatus::Done => {
            if out_len > 0 && env.set_double_array_region(outputs, 0, &out_buf).is_err() {
                return ERR_BUFFER;
            }
            OK_DONE
        }
        // OutputShed：已完成输出（新鲜）+ 撞线者（缓冲原值 = Java seed 的保持值）
        // 整段写回——「已完成位新鲜、余位保持」逐位成立。
        // OutputShed: completed outputs (fresh) + held ones (the caller's seeded
        // values) written back wholesale — "completed fresh, the rest hold" bit-exact.
        ExecStatus::OutputShed => {
            if out_len > 0 && env.set_double_array_region(outputs, 0, &out_buf).is_err() {
                return ERR_BUFFER;
            }
            OK_OUTPUT_SHED
        }
        ExecStatus::Suspended { carrier_len, progress } => {
            // 可空出参：null = 挂起即丢态（Java 侧自知）。给了但装不下 = 缓冲契约外。
            // Nullable out param: null drops the state; undersized = contract violation.
            if !carrier_out.is_null() {
                if (carrier_out_len(&env, &carrier_out) as usize) < carrier_len {
                    return ERR_BUFFER;
                }
                let jbytes: Vec<jbyte> =
                    carrier_buf[..carrier_len].iter().map(|&b| b as jbyte).collect();
                if env.set_byte_array_region(carrier_out, 0, &jbytes).is_err() {
                    return ERR_BUFFER;
                }
            }
            if !write_progress(&env, progress) {
                return ERR_BUFFER;
            }
            OK_SUSPENDED
        }
        ExecStatus::DeadlineSuspended { carrier_len, progress } => {
            if !carrier_out.is_null() {
                if (carrier_out_len(&env, &carrier_out) as usize) < carrier_len {
                    return ERR_BUFFER;
                }
                let jbytes: Vec<jbyte> =
                    carrier_buf[..carrier_len].iter().map(|&b| b as jbyte).collect();
                if env.set_byte_array_region(carrier_out, 0, &jbytes).is_err() {
                    return ERR_BUFFER;
                }
            }
            if !write_progress(&env, progress) {
                return ERR_BUFFER;
            }
            OK_DEADLINE_SUSPENDED
        }
        ExecStatus::Error => OK_EXEC_ERROR,
        ExecStatus::Shed => OK_SHED,
    }
}

/// 批量求值（R3b，§4.7-6）：一次 JNI 调用按序求值一批成员——挂起态会话内自持
/// （carrier 不跨界）、逐成员 slice/deadline 同 exec 口径、逐成员状态/进度/耗时回传。
/// 返回 0 = 全部成员处理完毕（逐员结果在 statuses；个别成员的契约错误也落成员状态，
/// 其余成员继续）；负数 = 批级错误（-1 会话无效 -3 数组校验失败）。
/// inputs/outputs = 扁平缓冲，inputOffsets/outputOffsets 给出每成员的 [起,止)；
/// outputs 语义与 exec 相同（in/out：调用前由调用方 seed 保持值）。
/// Batch evaluation (R3b, §4.7-6): one JNI call evaluates the members in order —
/// suspend state lives in the session (carrier never crosses), per-member
/// slice/deadline match exec, and per-member status/progress/elapsed come back.
/// Returns 0 when all members were processed (per-member results in statuses; a
/// member's contract error lands in its own status and the rest continue);
/// negative = batch-level error (-1 invalid session, -3 array validation).
#[no_mangle]
pub extern "system" fn Java_io_github_y15173334444_create_1schematic_1compute_graph_FormulaNative_tickBatch<
    'local,
>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    session: jlong,
    count: jint,
    node_slots: JIntArray<'local>,
    program_ids: JIntArray<'local>,
    refresh_flags: JIntArray<'local>,
    inputs: JDoubleArray<'local>,
    input_offsets: JIntArray<'local>,
    slice_nanos: jlong,
    deadline_in_nanos: jlong,
    outputs: JDoubleArray<'local>,
    output_offsets: JIntArray<'local>,
    statuses: JIntArray<'local>,
    progress: JFloatArray<'local>,
    elapsed_ns: JLongArray<'local>,
) -> jint {
    let Some(cell) = session_cell(session) else {
        return ERR_SESSION;
    };
    let n = count as usize;
    if n == 0 {
        return 0;
    }
    // 校验先于任何搬运（错误码优先）。 / validation precedes any copy.
    let int_len = |a: &JIntArray<'_>| env.get_array_length(a).map(|l| l as usize).unwrap_or(0);
    let float_len = |a: &JFloatArray<'_>| env.get_array_length(a).map(|l| l as usize).unwrap_or(0);
    let long_len = |a: &JLongArray<'_>| env.get_array_length(a).map(|l| l as usize).unwrap_or(0);
    if int_len(&node_slots) < n
        || int_len(&program_ids) < n
        || int_len(&refresh_flags) < n
        || int_len(&input_offsets) < n + 1
        || int_len(&output_offsets) < n + 1
        || int_len(&statuses) < n
        || (!progress.is_null() && float_len(&progress) < n)
        || (!elapsed_ns.is_null() && long_len(&elapsed_ns) < n)
    {
        return ERR_BUFFER;
    }
    let mut slots_buf = vec![0i32; n];
    let mut prog_buf = vec![0i32; n];
    let mut refresh_buf = vec![0i32; n];
    let mut in_off = vec![0i32; n + 1];
    let mut out_off = vec![0i32; n + 1];
    if env.get_int_array_region(&node_slots, 0, &mut slots_buf).is_err()
        || env.get_int_array_region(&program_ids, 0, &mut prog_buf).is_err()
        || env.get_int_array_region(&refresh_flags, 0, &mut refresh_buf).is_err()
        || env.get_int_array_region(&input_offsets, 0, &mut in_off).is_err()
        || env.get_int_array_region(&output_offsets, 0, &mut out_off).is_err()
    {
        return ERR_BUFFER;
    }
    let in_len = if inputs.is_null() {
        0
    } else {
        match env.get_array_length(&inputs) {
            Ok(l) => l as usize,
            Err(_) => return ERR_BUFFER,
        }
    };
    let out_len = if outputs.is_null() {
        0
    } else {
        match env.get_array_length(&outputs) {
            Ok(l) => l as usize,
            Err(_) => return ERR_BUFFER,
        }
    };
    // offsets 单调不越界校验。 / offsets must be monotonic and in range.
    for i in 0..=n {
        let (io, oo) = (in_off[i] as usize, out_off[i] as usize);
        if io > in_len || oo > out_len {
            return ERR_BUFFER;
        }
        if i > 0 && (io < in_off[i - 1] as usize || oo < out_off[i - 1] as usize) {
            return ERR_BUFFER;
        }
    }
    let mut in_flat = vec![0.0f64; in_len];
    if in_len > 0 && env.get_double_array_region(&inputs, 0, &mut in_flat).is_err() {
        return ERR_BUFFER;
    }
    let mut out_flat = vec![0.0f64; out_len];
    if out_len > 0 && env.get_double_array_region(&outputs, 0, &mut out_flat).is_err() {
        return ERR_BUFFER;
    }
    let slice =
        if slice_nanos > 0 { Some(Duration::from_nanos(slice_nanos as u64)) } else { None };
    let deadline_in = if deadline_in_nanos < 0 {
        Some(Duration::ZERO)
    } else if deadline_in_nanos > 0 {
        Some(Duration::from_nanos(deadline_in_nanos as u64))
    } else {
        None
    };
    let mut s = lock_session(&cell);
    let mut status_buf = vec![0i32; n];
    let mut prog_out_buf = vec![0.0f32; n];
    let mut elapsed_buf = vec![0i64; n];
    for i in 0..n {
        let i0 = in_off[i] as usize;
        let i1 = in_off[i + 1] as usize;
        let o0 = out_off[i] as usize;
        let o1 = out_off[i + 1] as usize;
        let t0 = std::time::Instant::now();
        let st = s.batch_exec(
            slots_buf[i] as usize,
            prog_buf[i] as usize,
            &in_flat[i0..i1],
            refresh_buf[i] != 0,
            slice,
            deadline_in,
            &mut out_flat[o0..o1],
        );
        let took = t0.elapsed().as_nanos() as i64;
        match st {
            Ok(ExecStatus::Done) => status_buf[i] = OK_DONE,
            Ok(ExecStatus::OutputShed) => status_buf[i] = OK_OUTPUT_SHED,
            Ok(ExecStatus::Suspended { carrier_len: _, progress }) => {
                status_buf[i] = OK_SUSPENDED;
                prog_out_buf[i] = progress;
            }
            Ok(ExecStatus::DeadlineSuspended { carrier_len: _, progress }) => {
                status_buf[i] = OK_DEADLINE_SUSPENDED;
                prog_out_buf[i] = progress;
            }
            Ok(ExecStatus::Error) => status_buf[i] = OK_EXEC_ERROR,
            Ok(ExecStatus::Shed) => status_buf[i] = OK_SHED,
            // 逐员契约错误落成员状态（未知槽位/程序 = -2、缓冲 = -3），批继续。
            // A member's contract error lands in its own status; the batch continues.
            Err(ExecError::UnknownProgram) => status_buf[i] = ERR_PROGRAM_ID,
            Err(ExecError::BadCarrier) => status_buf[i] = ERR_CARRIER,
            Err(ExecError::BadBuffer) => status_buf[i] = ERR_BUFFER,
        }
        elapsed_buf[i] = took;
    }
    // outputs 整段写回（Done 全量覆写、OutputShed 已完成位新鲜 + 余位保持 seed 值）。
    // Write the outputs region back wholesale (Done overwrites; OutputShed keeps the
    // caller's seeded values beyond the completed prefix).
    if out_len > 0 && env.set_double_array_region(&outputs, 0, &out_flat).is_err() {
        return ERR_BUFFER;
    }
    if env.set_int_array_region(&statuses, 0, &status_buf).is_err() {
        return ERR_BUFFER;
    }
    if !progress.is_null() && env.set_float_array_region(&progress, 0, &prog_out_buf).is_err() {
        return ERR_BUFFER;
    }
    if !elapsed_ns.is_null() && env.set_long_array_region(&elapsed_ns, 0, &elapsed_buf).is_err() {
        return ERR_BUFFER;
    }
    0
}

/// 分配节点态槽位（R3b 批量形态；slotId 稳定，空洞复用）。
/// Allocate a node-state slot (R3b batch form; stable ids, holes reused).
#[no_mangle]
pub extern "system" fn Java_io_github_y15173334444_create_1schematic_1compute_graph_FormulaNative_allocNodeSlot<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    session: jlong,
) -> jint {
    let Some(cell) = session_cell(session) else {
        return ERR_SESSION;
    };
    let mut s = lock_session(&cell);
    s.alloc_node_slot() as jint
}

/// 节点槽位挂起态的循环栈首条 k（R3b 测试/观测视图——carrier 自持后 Java 侧的
/// loopStack.get(0).k 镜像;无挂起态/空栈 = -1）。
/// The node slot's suspend-state first loop-stack k (the R3b test/observability view
/// mirroring Java's loopStack.get(0).k once the carrier is native-held; -1 when
/// absent/empty).
#[no_mangle]
pub extern "system" fn Java_io_github_y15173334444_create_1schematic_1compute_graph_FormulaNative_nodeSlotFirstLoopK<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    session: jlong,
    slot: jint,
) -> jlong {
    let Some(cell) = session_cell(session) else {
        return -1;
    };
    let s = lock_session(&cell);
    match s
        .nodes
        .get(slot as usize)
        .and_then(|n| n.as_ref())
        .and_then(|n| n.carrier.as_ref())
    {
        Some(c) if !c.loop_stack.is_empty() => c.loop_stack[0].k as jlong,
        _ => -1,
    }
}

/// carrierOut 数组长度（前置校验已排除 null）。 / Length of the carrierOut array (null pre-checked).
fn carrier_out_len(env: &JNIEnv<'_>, arr: &JByteArray<'_>) -> jint {
    env.get_array_length(arr).unwrap_or(0) as jint
}

#[no_mangle]
pub extern "system" fn Java_io_github_y15173334444_create_1schematic_1compute_graph_FormulaNative_abiVersion<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jint {
    ABI_VERSION
}

// ───────────────────────── 纯 Rust 单测（不经 JVM） ─────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    /// scalarLoop 夹具（Java FormulaTokEncoder 产出）：槽位表 i=0 acc=1，
    /// 终值 acc=4999950.0 / i=100000.0（golden 见 tests/fixtures/scalarLoop.golden.txt）。
    fn scalar_loop_bytes() -> Vec<u8> {
        let p = concat!(env!("CARGO_MANIFEST_DIR"), "/tests/fixtures/scalarLoop.tokbin");
        std::fs::read(p).expect("scalarLoop fixture")
    }

    #[test]
    fn session_upload_exec_done_bit_exact() {
        let bin = scalar_loop_bytes();
        let mut s = Session::new();
        let id = s.upload(&bin).expect("upload");
        assert_eq!(id, 0);
        // v2：outputs = 输出引脚值（夹具末行独立表达式 acc）——o[0] 携 acc 终值逐位；
        // 终值(全槽位)面由引擎级对拍(parity.rs / bridge_cross.rs)锁。
        // v2: outputs carry output-pin values (the fixture's trailing standalone
        // expression `acc`) — o[0] holds acc bit-exact; all-slot finals stay locked at
        // the engine level (parity.rs / bridge_cross.rs).
        let mut out = vec![0.0; 1];
        let st = s.exec(id, &[], None, false, None, None, &mut out, None).expect("exec");
        assert_eq!(st, ExecStatus::Done);
        assert_eq!(out[0].to_bits(), 4999950.0f64.to_bits(), "acc bit-exact via output");
        // 重复 exec（同会话复用缓冲）：幂等且终值不变。
        // / Re-exec on the same session (reused buffers): idempotent, same finals.
        let st2 = s.exec(id, &[], None, false, None, None, &mut out, None).expect("re-exec");
        assert_eq!(st2, ExecStatus::Done);
        assert_eq!(out[0].to_bits(), 4999950.0f64.to_bits());
    }

    #[test]
    fn session_slice_suspend_resume_converges() {
        let bin = scalar_loop_bytes();
        let mut s = Session::new();
        let id = s.upload(&bin).expect("upload");
        let mut out = vec![0.0; 1];
        // 不限预算基准。 / unbudgeted baseline.
        s.exec(id, &[], None, false, None, None, &mut out, None).expect("baseline");
        let want = out.clone();
        // 500µs slice 反复挂起/续算（carrier 字节跨界，与 JNI 形态同构）到 Done。
        // / Repeated suspend/resume with 500µs slices, carrier bytes crossing the seam.
        let mut carrier: Vec<u8> = Vec::new();
        let mut rounds = 0u32;
        loop {
            rounds += 1;
            assert!(rounds < 200_000, "not converging");
            // Java 形态：carrierIn 与 carrierOut 是两个独立数组——先把上一段字节
            // 复制进独立输入缓冲再写新挂起态（借用也由此分离）。
            // Java shape: carrierIn and carrierOut are two distinct arrays — copy the
            // previous slice into a separate input buffer before writing the next one.
            let resume_bytes = carrier.clone();
            let resume = if resume_bytes.is_empty() { None } else { Some(&resume_bytes[..]) };
            match s.exec(id, &[], resume, false, Some(Duration::from_nanos(500_000)), None, &mut out, Some(&mut carrier)).expect("sliced exec") {
                ExecStatus::Done => break,
                ExecStatus::OutputShed => panic!("output phase cannot shed with no deadline wired"),
                // deadline 未接线 → 不可能；穷举臂兜底。 / deadline unwired — exhaustive arm.
                ExecStatus::DeadlineSuspended { .. } => panic!("deadline unwired — unreachable"),
                ExecStatus::Error => panic!("no error path in this script"),
                ExecStatus::Suspended { carrier_len, .. } => {
                    assert!(carrier_len <= carrier.len());
                    assert_eq!(carrier_len, carrier.len(), "exec writes exactly carrier_len bytes");
                    // 写回前缀必须是合法 carrier（自描述头可解码）。/ prefix must decode.
                    carrier_from_bytes(&carrier[..carrier_len]).expect("carrier bytes decode");
                }
                ExecStatus::Shed => panic!("must not shed"),
            }
        }
        assert_eq!(out[0].to_bits(), want[0].to_bits(), "acc converges bit-exact via output");
    }

    #[test]
    fn session_release_invalidates_exec() {
        let bin = scalar_loop_bytes();
        let mut s = Session::new();
        let id = s.upload(&bin).expect("upload");
        assert!(s.release(id));
        let mut out = vec![0.0; 1];
        assert_eq!(s.exec(id, &[], None, false, None, None, &mut out, None), Err(ExecError::UnknownProgram));
        // 已释放槽再释放 / 未知 id 同口径。 / double release and unknown id are both invalid.
        assert!(!s.release(id));
        assert!(!s.release(99));
        assert_eq!(s.program_slots(id), None);
    }

    #[test]
    fn session_batch_exec_native_state() {
        let bin = scalar_loop_bytes();
        let mut s = Session::new();
        let id = s.upload(&bin).expect("upload");
        let slot = s.alloc_node_slot();
        // 不限预算基准:Done,o[0] = 输出表达式(acc)终值逐位。
        let mut out = vec![0.0; 1];
        assert_eq!(
            s.batch_exec(slot, id, &[], false, None, None, &mut out).expect("baseline"),
            ExecStatus::Done
        );
        assert_eq!(out[0].to_bits(), 4999950.0f64.to_bits(), "acc bit-exact via batch face");
        // 500µs slice 反复挂起:状态会话内自持(carrier 不跨界),收敛后与基准逐位。
        // Repeated suspend with the state held in the session (no carrier crossing):
        // converges bit-exact against the unbudgeted baseline.
        let mut sliced = vec![0.0; 1];
        let mut rounds = 0u32;
        loop {
            rounds += 1;
            assert!(rounds < 200_000, "not converging");
            match s
                .batch_exec(slot, id, &[], false, Some(Duration::from_nanos(500_000)), None, &mut sliced)
                .expect("sliced batch exec")
            {
                ExecStatus::Done => break,
                ExecStatus::Suspended { carrier_len, .. } => assert_eq!(carrier_len, 0, "no bytes cross"),
                ExecStatus::DeadlineSuspended { .. } => {}
                other => panic!("unexpected {other:?}"),
            }
        }
        assert_eq!(sliced[0].to_bits(), out[0].to_bits(), "native-held state converges bit-exact");
    }

    #[test]
    fn session_reuses_freed_slot_ids() {
        let bin = scalar_loop_bytes();
        let mut s = Session::new();
        let a = s.upload(&bin).expect("upload a");
        let b = s.upload(&bin).expect("upload b");
        assert_eq!((a, b), (0, 1));
        assert!(s.release(a));
        let c = s.upload(&bin).expect("upload c");
        assert_eq!(c, 0, "freed slot reused — program ids stay stable");
    }

    #[test]
    fn session_upload_rejects_garbage() {
        let bin = scalar_loop_bytes();
        let mut s = Session::new();
        assert!(matches!(s.upload(&bin[..bin.len() - 1]), Err(DecodeError::Truncated)));
        assert!(matches!(s.upload(&bin[1..]), Err(DecodeError::BadMagic)));
    }

    #[test]
    fn session_buffer_contract() {
        let bin = scalar_loop_bytes();
        let mut s = Session::new();
        let id = s.upload(&bin).expect("upload");
        let slot_n = s.program_slots(id).unwrap();
        // 超槽位数 inputs = 契约外；v2 起 outputs 是输出引脚值——不再受槽位上界。
        // over-slot inputs are contract-out; since v2 outputs are pin values, unbounded.
        let in_over = vec![0.0; slot_n + 1];
        let mut out_ok = vec![0.0; 1];
        assert_eq!(
            s.exec(id, &in_over, None, false, None, None, &mut out_ok, None),
            Err(ExecError::BadBuffer)
        );
        // 超长 outputs 合法：oi ≥ 输出条数的位收 0（Java List.of(0.0) 同口径）。
        // over-long outputs are legal: oi ≥ output-count fills 0.
        let mut out_long = vec![-1.0; slot_n + 3];
        assert_eq!(
            s.exec(id, &[], None, false, None, None, &mut out_long, None),
            Ok(ExecStatus::Done)
        );
        assert_eq!(out_long[0].to_bits(), 4999950.0f64.to_bits(), "o[0] = acc");
        for v in &out_long[1..] {
            assert_eq!(*v, 0.0, "oi beyond output-count reads 0");
        }
        // 坏 carrier = -4。 / bad carrier = -4.
        let mut out_full = vec![0.0; 1];
        assert_eq!(
            s.exec(id, &[], Some(b"CSCC\x99"), false, None, None, &mut out_full, None),
            Err(ExecError::BadCarrier)
        );
    }
}

// ───────────────────────── 句柄表对抗单测（不经 JVM，直接打 JNI 函数） ─────────────────────────
//
// 这些测试直接调用 #[no_mangle] 导出函数（JNIEnv/JClass 传 null 句柄——jni crate
// 允许非 null 检查弱化的构造路径；本层导出对 env 的使用只有数组访问，句柄对抗路径
// 不触 env）。单线程执行（会话表是进程级全局——测试串行是前提，cargo test 默认
// 同进程多线程会互踩：全部 handle 表测试收拢在**一个** #[test] 里保证顺序）。

#[cfg(test)]
mod handle_table_tests {
    use super::*;

    /// 一个测试内顺序执行全部句柄对抗路径（会话表是全局态，避免并行测试互踩）。
    /// All handle-table adversarial paths in one #[test] (global state → serialized).
    #[test]
    fn handle_table_adversarial_matrix() {
        // 哨兵与伪句柄：create 正常返回非零；0/负数句柄一律查无此人。
        let h1 = unsafe {
            Java_io_github_y15173334444_create_1schematic_1compute_graph_FormulaNative_sessionCreate(
                std::mem::zeroed(),
                std::mem::zeroed(),
            )
        };
        assert!(h1 > 0, "issued handle must be nonzero");
        // sessionDestroy 幂等：同一句柄销毁两次不炸（第二次查无此人）。
        unsafe {
            Java_io_github_y15173334444_create_1schematic_1compute_graph_FormulaNative_sessionDestroy(
                std::mem::zeroed(),
                std::mem::zeroed(),
                h1,
            );
            // 销毁后所有操作 = -1（UAF 从构造上不存在——句柄只是表键）。
            assert_eq!(
                Java_io_github_y15173334444_create_1schematic_1compute_graph_FormulaNative_programUpload(
                    std::mem::zeroed(), std::mem::zeroed(), h1, JByteArray::<'_>::from_raw(std::ptr::null_mut()),
                ),
                ERR_SESSION
            );
            // 二次 destroy = 无操作。
            Java_io_github_y15173334444_create_1schematic_1compute_graph_FormulaNative_sessionDestroy(
                std::mem::zeroed(),
                std::mem::zeroed(),
                h1,
            );
            // 伪句柄矩阵：负数 / 0 / 巨数 全部查无此人。
            for hostile in [-1i64, 0i64, i64::MAX, (1i64 << 40) + 12345] {
                assert_eq!(
                    Java_io_github_y15173334444_create_1schematic_1compute_graph_FormulaNative_programRelease(
                        std::mem::zeroed(), std::mem::zeroed(), hostile, 0,
                    ),
                    ERR_SESSION
                );
            }
        }
    }
}
