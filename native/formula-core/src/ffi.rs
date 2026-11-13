//! R2 JNI shim：`formula_native.h` 契约的 Rust 落地。两个层次：
//! - `Session`（纯 Rust）：已上传 Program 的槽位表（释放槽位复用，programId 稳定）
//!   + 每程序独占的 regs/loop_stack 复用缓冲——exec 走 `bridge::decode_program` 已
//!   上传的字节码 → `Exec::new/run`，引擎路径稳态零分配；挂起路径
//!   `carrier_to_bytes` 一次分配（与 bridge.rs「分配只发生在挂起时刻」同口径）。
//! - 5 个 JNI 导出（与 formula_native.h 一一同名）：薄翻译层。JVM 数组经 region
//!   API 拷入/拷出——胶水每次 exec 分配两个小 Vec，属过渡逐节点形态的固有开销
//!   （§4.5 批量入口切 direct ByteBuffer 时一并消除），引擎路径零分配不受影响。
//!
//! JNI 异常纪律：全部非法输入（坏会话/坏 id/坏缓冲/坏字节码）走负错误码返回，
//! **绝不 panic 跨 FFI**（panic 跨 FFI = abort）；jvm 数组操作失败同样映射错误码。
//! 纯 Rust 单测（`tests` mod）不经真 JVM 验证 Session 编排逻辑。
//!
//! R2 JNI shim: the Rust landing of the formula_native.h contract. `Session` is
//! pure Rust (uploaded programs in a slot table with stable ids, per-program
//! reusable buffers — steady-state zero-alloc on the engine path); the 5 JNI
//! exports are a thin translation layer where every invalid input returns a
//! negative error code instead of panicking across the FFI boundary.

use std::time::Duration;

use jni::objects::{JByteArray, JClass, JDoubleArray};
use jni::sys::{jbyte, jint, jlong};
use jni::JNIEnv;

use crate::ast::Program;
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
/// -1 会话无效。 / invalid session.
const ERR_SESSION: jint = -1;
/// -2 程序 id 无效（未上传或已释放）。 / unknown or released program id.
const ERR_PROGRAM_ID: jint = -2;
/// -3 输入/输出缓冲尺寸不符（超槽位数为契约外形态；未给满按 lenient 0）。
/// / buffer size mismatch (oversized = contract violation; short = lenient zero fill).
const ERR_BUFFER: jint = -3;
/// -4 carrier 解码失败。 / carrier decode failure.
const ERR_CARRIER: jint = -4;
/// -5 Program 字节码解码失败（programUpload 专用；编辑期暴露优于静默漂移）。
/// / program bytecode decode failure (programUpload only; surfaced at edit time).
const ERR_PROGRAM_BYTES: jint = -5;

// ───────────────────────── 纯 Rust 会话核心（单测覆盖） ─────────────────────────

/// 已上传程序 + 该程序独占的求值缓冲（跨 exec 复用 → 稳态零分配）。
/// One uploaded program plus its exclusive eval buffers (reused across execs).
struct UploadedProgram {
    prog: Program,
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

/// 会话：世界级生命周期（服务端线程独占创建/销毁，非线程安全——契约由调用方保证）。
/// Session: world-scoped lifetime, server-thread confined, not thread-safe (caller's contract).
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

// ───────────────────────── JNI 导出（formula_native.h 一一同名） ─────────────────────────

/// 裸指针还原（契约：只接收本库 sessionCreate 发出的指针，Java 侧不外传）。
/// Raw-pointer restore (contract: only pointers issued by sessionCreate come back here).
unsafe fn session_ref(session: jlong) -> Option<&'static mut Session> {
    if session == 0 {
        return None;
    }
    Some(&mut *(session as *mut Session))
}

#[no_mangle]
pub extern "system" fn Java_io_github_y15173334444_create_1schematic_1compute_graph_FormulaNative_sessionCreate<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jlong {
    Box::into_raw(Box::new(Session::new())) as jlong
}

#[no_mangle]
pub extern "system" fn Java_io_github_y15173334444_create_1schematic_1compute_graph_FormulaNative_sessionDestroy<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    session: jlong,
) {
    if session != 0 {
        // 契约：只销毁本库发出的指针。 / Contract: only pointers issued by sessionCreate.
        drop(unsafe { Box::from_raw(session as *mut Session) });
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
    let Some(s) = (unsafe { session_ref(session) }) else {
        return ERR_SESSION;
    };
    let bytes = match env.convert_byte_array(program) {
        Ok(b) => b,
        // null 数组或 JVM 侧失败 → 契约外字节码（jni crate 的 non_null 不悬挂异常）。
        // / null array or JVM-side failure → contract-violating bytecode (no pending exception).
        Err(_) => return ERR_PROGRAM_BYTES,
    };
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
    let Some(s) = (unsafe { session_ref(session) }) else {
        return ERR_SESSION;
    };
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
    let Some(s) = (unsafe { session_ref(session) }) else {
        return ERR_SESSION;
    };
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
    // ① carrierIn 解码（resume 路径；坏字节 = -4）。
    // ① carrierIn decode (resume path; bad bytes = -4).
    let resume: Option<Vec<u8>> = if carrier_in.is_null() {
        None
    } else {
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

// ───────────────────────── 纯 Rust 单测（不经真 JVM） ─────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    /// scalarLoop 夹具（Java FormulaTokEncoder 产出，R1 落盘）：槽位表 i=0 acc=1，
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
