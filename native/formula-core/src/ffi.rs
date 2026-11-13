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

use jni::objects::{JByteArray, JClass, JDoubleArray};
use jni::sys::{jbyte, jint, jlong};
use jni::JNIEnv;

use crate::bridge::{carrier_from_bytes, carrier_to_bytes, decode_program, DecodeError};
use crate::interp::{Budget, Exec, LoopEntry, Outcome};
use crate::value::V;

/// ABI 版本（与 Java FormulaNative.ABI_VERSION 及 formula_native.h 同步递增）。
/// ABI version, kept in lockstep with FormulaNative.ABI_VERSION and the header.
pub(crate) const ABI_VERSION: jint = 1;

// exec 返回码（契约 = formula_native.h）。 / exec return codes (contract = the header).
const OK_DONE: jint = 0;
const OK_SUSPENDED: jint = 1;
const OK_SHED: jint = 2;
/// -1 会话无效（null 哨兵/过期句柄/敌意句柄）。 / invalid session (null/stale/forged).
const ERR_SESSION: jint = -1;
/// -2 程序 id 无效（未上传或已释放）。 / unknown or released program id.
const ERR_PROGRAM_ID: jint = -2;
/// -3 输入/输出缓冲尺寸不符（超槽位数为契约外形态；未给满按 lenient 0）。
/// / buffer size mismatch (oversized = contract violation; short = lenient zero fill).
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

/// exec 终态（JNI 层映射回 jint）。挂起时携带本次写入 carrier 字节数。
/// Terminal exec status (mapped to jint by the JNI layer); carries the written
/// carrier length on suspension.
#[derive(Debug, PartialEq)]
pub enum ExecStatus {
    Done,
    Suspended { carrier_len: usize },
    Shed,
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
    /// exec 内部暂存：inputs 的 V 形态 / Exec.run 终值中转（容量随首次调用到位后稳定）。
    inputs_v: Vec<V>,
    out_v: Vec<V>,
}

impl Session {
    pub fn new() -> Session {
        Session { programs: Vec::new(), inputs_v: Vec::new(), out_v: Vec::new() }
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

    /// 一次求值。`inputs` 按槽位序（可短于槽位数，其余 lenient 0；超长 = 契约外）；
    /// `resume` = 上次挂起的 carrier 字节（None = 全新求值）；`slice` = None 不限预算；
    /// `out` 按槽位序收终值（可短于槽位数）；`carrier_out` = Suspended 时收 carrier
    /// 字节（None = 挂起即丢态）。
    /// One evaluation. inputs are slot-ordered (short is lenient-zero, over-length is a
    /// contract violation); resume carries the suspended carrier bytes; Done copies finals
    /// into out; a suspension serializes into carrier_out (None drops the state).
    pub fn exec(
        &mut self,
        id: usize,
        inputs: &[f64],
        resume: Option<&[u8]>,
        slice: Option<Duration>,
        out: &mut [f64],
        mut carrier_out: Option<&mut Vec<u8>>,
    ) -> Result<ExecStatus, ExecError> {
        let slot = self
            .programs
            .get_mut(id)
            .and_then(|p| p.as_mut())
            .ok_or(ExecError::UnknownProgram)?;
        let slot_n = slot.prog.names.len();
        if inputs.len() > slot_n || out.len() > slot_n {
            return Err(ExecError::BadBuffer);
        }
        let resume_carrier =
            resume.map(carrier_from_bytes).transpose().map_err(|_| ExecError::BadCarrier)?;
        // 暂存缓冲：容量到位后 clear/extend 不再分配（引擎路径稳态零分配的前提）。
        // Scratch buffers: after the first calls clear/extend never allocate again.
        self.inputs_v.clear();
        self.inputs_v.extend(inputs.iter().map(|&v| V::S(v)));
        self.out_v.clear();
        self.out_v.resize(out.len(), V::ZERO);
        let budget = match slice {
            Some(d) => Budget::slice(d),
            None => Budget::unlimited(),
        };
        // 字段级拆借：Program 只读，regs/loops 独占可变（Exec 契约：调用方缓冲跨 exec 复用）。
        // Field-split borrows: Program read-only, regs/loops exclusively mutable.
        let ex = Exec::new(
            &slot.prog,
            &mut slot.regs,
            &mut slot.loops,
            &self.inputs_v,
            resume_carrier,
            budget,
        );
        match ex.run(&mut self.out_v) {
            Outcome::Done => {
                for (o, v) in out.iter_mut().zip(self.out_v.iter()) {
                    *o = v.as_scalar();
                }
                Ok(ExecStatus::Done)
            }
            Outcome::Suspended(c) => {
                let bytes = carrier_to_bytes(&c);
                if let Some(buf) = carrier_out.as_deref_mut() {
                    buf.clear();
                    buf.extend_from_slice(&bytes);
                }
                Ok(ExecStatus::Suspended { carrier_len: bytes.len() })
            }
            Outcome::Shed => Ok(ExecStatus::Shed),
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
    outputs: JDoubleArray<'local>,
    carrier_out: JByteArray<'local>,
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
    // 槽位数校验先于任何数组搬运（错误码优先，杜绝半途丢弃的半写状态）。
    // Validate against the slot count before touching any array (errors precede partial writes).
    let Some(slot_n) = s.program_slots(program_id as usize) else {
        return ERR_PROGRAM_ID;
    };
    if in_len > slot_n || out_len > slot_n {
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
    let slice = if slice_nanos > 0 { Some(Duration::from_nanos(slice_nanos as u64)) } else { None };
    let status = match s.exec(
        program_id as usize,
        &in_buf,
        resume.as_deref(),
        slice,
        &mut out_buf,
        Some(&mut carrier_buf),
    ) {
        Ok(st) => st,
        Err(ExecError::UnknownProgram) => return ERR_PROGRAM_ID,
        Err(ExecError::BadCarrier) => return ERR_CARRIER,
        Err(ExecError::BadBuffer) => return ERR_BUFFER,
    };
    match status {
        ExecStatus::Done => {
            if out_len > 0 && env.set_double_array_region(outputs, 0, &out_buf).is_err() {
                return ERR_BUFFER;
            }
            OK_DONE
        }
        ExecStatus::Suspended { carrier_len } => {
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
            OK_SUSPENDED
        }
        ExecStatus::Shed => OK_SHED,
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

    fn scalar_loop_slots() -> (usize, usize) {
        let prog = decode_program(&scalar_loop_bytes()).expect("decode");
        let acc = prog.names.iter().position(|n| n == "acc").expect("acc slot");
        let i = prog.names.iter().position(|n| n == "i").expect("i slot");
        (acc, i)
    }

    #[test]
    fn session_upload_exec_done_bit_exact() {
        let bin = scalar_loop_bytes();
        let (acc_slot, i_slot) = scalar_loop_slots();
        let mut s = Session::new();
        let id = s.upload(&bin).expect("upload");
        assert_eq!(id, 0);
        let mut out = vec![0.0; s.program_slots(id).unwrap()];
        let st = s.exec(id, &[], None, None, &mut out, None).expect("exec");
        assert_eq!(st, ExecStatus::Done);
        assert_eq!(out[acc_slot].to_bits(), 4999950.0f64.to_bits(), "acc bit-exact");
        assert_eq!(out[i_slot].to_bits(), 100000.0f64.to_bits(), "i bit-exact");
        // 重复 exec（同会话复用缓冲）：幂等且终值不变。
        // / Re-exec on the same session (reused buffers): idempotent, same finals.
        let st2 = s.exec(id, &[], None, None, &mut out, None).expect("re-exec");
        assert_eq!(st2, ExecStatus::Done);
        assert_eq!(out[acc_slot].to_bits(), 4999950.0f64.to_bits());
    }

    #[test]
    fn session_slice_suspend_resume_converges() {
        let bin = scalar_loop_bytes();
        let (acc_slot, i_slot) = scalar_loop_slots();
        let mut s = Session::new();
        let id = s.upload(&bin).expect("upload");
        let mut out = vec![0.0; s.program_slots(id).unwrap()];
        // 不限预算基准。 / unbudgeted baseline.
        s.exec(id, &[], None, None, &mut out, None).expect("baseline");
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
            match s.exec(id, &[], resume, Some(Duration::from_nanos(500_000)), &mut out, Some(&mut carrier)).expect("sliced exec") {
                ExecStatus::Done => break,
                ExecStatus::Suspended { carrier_len } => {
                    assert!(carrier_len <= carrier.len());
                    assert_eq!(carrier_len, carrier.len(), "exec writes exactly carrier_len bytes");
                    // 写回前缀必须是合法 carrier（自描述头可解码）。/ prefix must decode.
                    carrier_from_bytes(&carrier[..carrier_len]).expect("carrier bytes decode");
                }
                ExecStatus::Shed => panic!("must not shed"),
            }
        }
        assert_eq!(out[acc_slot].to_bits(), want[acc_slot].to_bits(), "acc converges bit-exact");
        assert_eq!(out[i_slot].to_bits(), want[i_slot].to_bits(), "i converges bit-exact");
    }

    #[test]
    fn session_release_invalidates_exec() {
        let bin = scalar_loop_bytes();
        let mut s = Session::new();
        let id = s.upload(&bin).expect("upload");
        assert!(s.release(id));
        let mut out = vec![0.0; 2];
        assert_eq!(s.exec(id, &[], None, None, &mut out, None), Err(ExecError::UnknownProgram));
        // 已释放槽再释放 / 未知 id 同口径。 / double release and unknown id are both invalid.
        assert!(!s.release(id));
        assert!(!s.release(99));
        assert_eq!(s.program_slots(id), None);
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
        let (acc_slot, _) = scalar_loop_slots();
        let mut s = Session::new();
        let id = s.upload(&bin).expect("upload");
        let slot_n = s.program_slots(id).unwrap();
        // 超槽位数 = 契约外（-3）；短缓冲 lenient 收尾值。 / over-length rejected; short is fine.
        let mut out = vec![0.0; slot_n + 1];
        assert_eq!(s.exec(id, &[], None, None, &mut out, None), Err(ExecError::BadBuffer));
        let in_over = vec![0.0; slot_n + 1];
        let mut out_ok = vec![0.0; slot_n];
        assert_eq!(
            s.exec(id, &in_over, None, None, &mut out_ok, None),
            Err(ExecError::BadBuffer)
        );
        // 短 outputs 只收前缀（acc 在槽 1，lenient 0 语义下仍可全量对拍——这里只验长度口径）。
        let mut short_out = vec![0.0; acc_slot];
        assert_eq!(s.exec(id, &[], None, None, &mut short_out, None), Ok(ExecStatus::Done));
        // 坏 carrier = -4。 / bad carrier = -4.
        let mut out_full = vec![0.0; slot_n];
        assert_eq!(
            s.exec(id, &[], Some(b"CSCC\x99"), None, &mut out_full, None),
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
