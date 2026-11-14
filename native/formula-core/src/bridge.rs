//! Tok 二进制桥：Java 前端（FormulaTokEncoder）产出的 Program 字节码在 native
//! 侧的解码 + carrier 序列化。**格式契约**（双端共同权威，改动必须两端同步并
//! 重生成夹具）：
//!
//! ```text
//! Program（小端;v3 —— v2 输出段 + 引脚槽位段,warm refresh 的契约面）:
//!   "CSCF" magic + u32 version(=3)
//!   u32 slot_count;  每 slot: u16 name_len + UTF-8
//!   u32 repeat_count; 每 项: u16 loop_id + u64 count
//!   u32 top_stmt_count; Stmt*:
//!     u8 tag; 0=Assign{u16 slot, Rpn} 1=Expr{Rpn} 2=Repeat{u16 id,u64 count,u32 n,Stmt*}
//!             3=While{u16 id, Rpn cond, u32 n, Stmt*} 4=If{Rpn,u32 n,Stmt*,u32 m,Stmt*}
//!             5=Break 6=Continue
//!   u32 output_count; Rpn*(= Java outputRpns;Done 时对终值寄存器逐条求值)
//!   u32 pin_count(= Java inputVars.size()); 每 钉: u32 slot(u32::MAX = 声明未引用)
//!   Rpn: u32 tok_count; Tok 16B/个: u8 tag + 7B pad + 8B payload(小端)
//!     tag 0 Const: f64 bits   1 Var: u16 slot   2 Op: u8 char
//!     3 Cmp: u8(0..5)  4 Not: 0  5 Fn: u8(枚举序)  6 Swz: u8(0..2, 9=lenient 恒零)
//! Carrier（小端）:
//!   "CSCC" + u32 version(=1) + u32 loop_count + (u16 id, u64 k)* + u32 reg_count
//!   + (u8 shape(0=S,1=V3) + f64 v0 [+ f64 v1, f64 v2])* + u64 total_iterations
//! ```
//!
//! Binary bridge between the Java frontend (FormulaTokEncoder) and the native
//! engine: Program decode + carrier serialization. The format above is the
//! two-sided contract — changes must be synced and fixtures regenerated.

use crate::ast::{Program, Stmt};
use crate::interp::LoopEntry;
use crate::tok::Tok;
use crate::value::V;

const PROGRAM_MAGIC: &[u8; 4] = b"CSCF";
const PROGRAM_VERSION: u32 = 3;
const CARRIER_MAGIC: &[u8; 4] = b"CSCC";
const CARRIER_VERSION: u32 = 1;
/// 语句嵌套解码深度帽（镜像 Java FormulaParser.MAX_PARSE_DEPTH：合法程序必然
/// 通过——编码器不可能产出更深 AST；字节码攻击面在此收口）。
/// Statement decode depth cap, mirroring FormulaParser.MAX_PARSE_DEPTH — every
/// legitimate program passes (the encoder cannot emit deeper ASTs); the crafted-
/// bytecode attack surface ends here.
const MAX_DECODE_DEPTH: usize = 128;

/// 解码错误（桥接层失败 = 编辑期就能暴露，绝不静默漂移）。
#[derive(Debug, PartialEq)]
pub enum DecodeError {
    Truncated,
    BadMagic,
    BadVersion(u32),
    BadTag(u8),
    BadFnOrdinal(u8),
    BadCmpOrdinal(u8),
    BadShape(u8),
    BadUtf8,
    /// 解码后仍有剩余字节 = 编码端与契约错位（Java 首版变宽 payload 即此）。
    TrailingBytes(usize),
    /// 语句嵌套深度超帽（字节码是攻击面：存档篡改/反射直调——深度帽镜像 Java
    /// MAX_PARSE_DEPTH，防 JNI 调用内 Rust 栈溢出）。
    /// Statement nesting over the cap (bytecode is an attack surface: tampered
    /// saves / reflective native calls) — mirrors Java MAX_PARSE_DEPTH to keep a
    /// crafted deep tree from overflowing the native stack inside a JNI call.
    TooDeep,
    /// 引脚槽位越界（字节码攻击面：越界槽位会在 warm refresh 写寄存器时越界）。
    /// Pin slot out of range (bytecode attack surface: an out-of-range slot would
    /// write out of bounds at warm refresh).
    BadPinSlot(u32),
}

struct Cursor<'a> {
    b: &'a [u8],
    p: usize,
}

impl<'a> Cursor<'a> {
    fn new(b: &'a [u8]) -> Self {
        Cursor { b, p: 0 }
    }
    fn take(&mut self, n: usize) -> Result<&'a [u8], DecodeError> {
        if self.p + n > self.b.len() {
            return Err(DecodeError::Truncated);
        }
        let s = &self.b[self.p..self.p + n];
        self.p += n;
        Ok(s)
    }
    fn u8(&mut self) -> Result<u8, DecodeError> {
        Ok(self.take(1)?[0])
    }
    fn u16(&mut self) -> Result<u16, DecodeError> {
        let s = self.take(2)?;
        Ok(u16::from_le_bytes([s[0], s[1]]))
    }
    fn u32v(&mut self) -> Result<u32, DecodeError> {
        let s = self.take(4)?;
        Ok(u32::from_le_bytes([s[0], s[1], s[2], s[3]]))
    }
    fn u64v(&mut self) -> Result<u64, DecodeError> {
        let s = self.take(8)?;
        Ok(u64::from_le_bytes([
            s[0], s[1], s[2], s[3], s[4], s[5], s[6], s[7],
        ]))
    }
    fn f64v(&mut self) -> Result<f64, DecodeError> {
        Ok(f64::from_bits(self.u64v()?))
    }
}

/// Java FdLibm 无关——Fn/Cmp 序数与 Java 编码器共享的显式映射。
mod ordinals {
    use crate::tok::{Cmp, Fn};
    pub fn fn_from(o: u8) -> Option<Fn> {
        Some(match o {
            0 => Fn::Vec3,
            1 => Fn::Length,
            2 => Fn::Normalize,
            3 => Fn::Dot,
            4 => Fn::Cross,
            5 => Fn::Dist,
            6 => Fn::Yaw,
            7 => Fn::Pitch,
            8 => Fn::Sin,
            9 => Fn::Cos,
            10 => Fn::Tan,
            11 => Fn::Asin,
            12 => Fn::Acos,
            13 => Fn::Atan2,
            14 => Fn::Sinh,
            15 => Fn::Cosh,
            16 => Fn::Sqrt,
            17 => Fn::Ln,
            18 => Fn::Log,
            19 => Fn::Exp,
            20 => Fn::Sec,
            21 => Fn::Csc,
            22 => Fn::Cot,
            _ => return None,
        })
    }
    pub fn cmp_from(o: u8) -> Option<Cmp> {
        Some(match o {
            0 => Cmp::Eq,
            1 => Cmp::Ne,
            2 => Cmp::Le,
            3 => Cmp::Ge,
            4 => Cmp::And,
            5 => Cmp::Or,
            _ => return None,
        })
    }
}

fn decode_tok(c: &mut Cursor) -> Result<Tok, DecodeError> {
    let head = c.take(16)?;
    let tag = head[0];
    let payload = &head[8..16];
    Ok(match tag {
        0 => Tok::Const(f64::from_bits(u64::from_le_bytes(payload.try_into().unwrap()))),
        1 => {
            let slot = u16::from_le_bytes([payload[0], payload[1]]);
            Tok::Var(slot)
        }
        2 => Tok::Op(payload[0]),
        3 => Tok::Cmp(ordinals::cmp_from(payload[0]).ok_or(DecodeError::BadCmpOrdinal(payload[0]))?),
        4 => Tok::Not,
        5 => Tok::Fn(ordinals::fn_from(payload[0]).ok_or(DecodeError::BadFnOrdinal(payload[0]))?),
        6 => Tok::Swz(payload[0]),
        other => return Err(DecodeError::BadTag(other)),
    })
}

fn decode_rpn(c: &mut Cursor) -> Result<Box<[Tok]>, DecodeError> {
    let n = c.u32v()? as usize;
    let mut v = Vec::with_capacity(n.min(4096));
    for _ in 0..n {
        v.push(decode_tok(c)?);
    }
    Ok(v.into_boxed_slice())
}

fn decode_stmts(c: &mut Cursor, n: u32, depth: usize) -> Result<Vec<Stmt>, DecodeError> {
    if depth > MAX_DECODE_DEPTH {
        return Err(DecodeError::TooDeep);
    }
    let mut out = Vec::with_capacity(n.min(4096) as usize);
    for _ in 0..n {
        out.push(match c.u8()? {
            0 => {
                let slot = c.u16()?;
                let rpn = decode_rpn(c)?;
                Stmt::Assign { slot, rpn }
            }
            1 => Stmt::Expr(decode_rpn(c)?),
            2 => {
                let id = c.u16()?;
                let count = c.u64v()?;
                let n = c.u32v()?;
                let body = decode_stmts(c, n, depth + 1)?;
                Stmt::Repeat { id, count, body: body.into_boxed_slice() }
            }
            3 => {
                let id = c.u16()?;
                let cond = decode_rpn(c)?;
                let n = c.u32v()?;
                let body = decode_stmts(c, n, depth + 1)?;
                Stmt::While { id, cond, body: body.into_boxed_slice() }
            }
            4 => {
                let cond = decode_rpn(c)?;
                let n = c.u32v()?;
                let body = decode_stmts(c, n, depth + 1)?;
                let en = c.u32v()?;
                let els = decode_stmts(c, en, depth + 1)?;
                Stmt::If { cond, body: body.into_boxed_slice(), els: els.into_boxed_slice() }
            }
            5 => Stmt::Break,
            6 => Stmt::Continue,
            other => return Err(DecodeError::BadTag(other)),
        });
    }
    Ok(out)
}

/// 解码 Java 前端产出的 Program 字节码。
pub fn decode_program(bytes: &[u8]) -> Result<Program, DecodeError> {
    let mut c = Cursor::new(bytes);
    if c.take(4)? != PROGRAM_MAGIC {
        return Err(DecodeError::BadMagic);
    }
    let version = c.u32v()?;
    if version != PROGRAM_VERSION {
        return Err(DecodeError::BadVersion(version));
    }
    let slot_count = c.u32v()? as usize;
    let mut names = Vec::with_capacity(slot_count.min(4096));
    for _ in 0..slot_count {
        let len = c.u16()? as usize;
        let s = c.take(len)?;
        names.push(String::from_utf8(s.to_vec()).map_err(|_| DecodeError::BadUtf8)?);
    }
    let repeat_count = c.u32v()? as usize;
    let mut repeat_counts = Vec::with_capacity(repeat_count.min(1024));
    for _ in 0..repeat_count {
        let id = c.u16()?;
        let count = c.u64v()?;
        repeat_counts.push((id, count));
    }
    let top = c.u32v()?;
    let body = decode_stmts(&mut c, top, 0)?;
    // v2 输出段：u32 条数 + 逐条 Rpn（Java outputRpns；空 @output 脚本 = 0 条，
    // 调用方输出缓冲按「oi ≥ 条数 = 0」口径收尾——与 Java List.of(0.0) 同语义）。
    // v2 output section: count + one Rpn each (Java's outputRpns; a script without
    // @output encodes zero entries — the caller's buffer fills 0 for oi ≥ count,
    // the same semantics as Java's List.of(0.0) fallback).
    let out_count = c.u32v()? as usize;
    let mut outputs = Vec::with_capacity(out_count.min(4096));
    for _ in 0..out_count {
        outputs.push(decode_rpn(&mut c)?);
    }
    // v3 引脚槽位段（warm refresh 的契约面）：u32 条数 = Java inputVars.size()，
    // 逐钉 u32 槽位索引（u32::MAX = 声明未引用的缺席钉——槽位表只收脚本引用过的
    // 变量，缺席钉在 refresh 里按跳过处理，与 Java「env.put 无读者」同观测）。
    // v3 pin-slot section (the warm-refresh contract face): u32 count = Java's
    // inputVars.size(), then one u32 slot index per pin (u32::MAX = a declared
    // pin the script never references — the slot table only interns referenced
    // variables; refresh skips absent pins, observationally identical to Java's
    // reader-less env.put).
    let pin_count = c.u32v()? as usize;
    let mut pin_slots = Vec::with_capacity(pin_count.min(4096));
    for _ in 0..pin_count {
        let s = c.u32v()?;
        // 越界槽位拒绝于解码期（u32::MAX = 缺席钉放行）——warm refresh 会按槽位
        // 写寄存器，越界即 JNI 内越界写，攻击面在桥接层收口。
        // Reject out-of-range slots at decode (u32::MAX = absent pin passes) —
        // warm refresh writes registers by slot; an out-of-range slot would be an
        // out-of-bounds write inside JNI. The attack surface ends at the bridge.
        if s != u32::MAX && s as usize >= slot_count {
            return Err(DecodeError::BadPinSlot(s));
        }
        pin_slots.push(s);
    }
    if c.p != bytes.len() {
        return Err(DecodeError::TrailingBytes(c.p));
    }
    // scalar_only 由 native 侧按同一规则自测（与编码器解耦；输出段共判——
    // 输出里藏向量函数/swizzle 会让快路径漏判 vec3 值域）。
    let body = body.into_boxed_slice();
    let scalar_only =
        crate::ast::scan_scalar_only_with_outputs(&body, &outputs);
    Ok(Program {
        body,
        names,
        repeat_counts,
        outputs: outputs.into_boxed_slice(),
        pin_slots,
        scalar_only,
    })
}

// ─────────────────────────────────────────────────────────────────────────────
// carrier 序列化（挂起态跨界：Java 侧存/传字节数组，native 侧自持时可不用）
// ─────────────────────────────────────────────────────────────────────────────

use crate::interp::Carrier;

/// carrier → 字节（格式见模块头）。分配只发生在挂起时刻（每 slice 一次）。
pub fn carrier_to_bytes(car: &Carrier) -> Vec<u8> {
    let mut out = Vec::with_capacity(16 + car.loop_stack.len() * 10 + car.regs.len() * 25);
    out.extend_from_slice(CARRIER_MAGIC);
    out.extend_from_slice(&CARRIER_VERSION.to_le_bytes());
    out.extend_from_slice(&(car.loop_stack.len() as u32).to_le_bytes());
    for e in &car.loop_stack {
        out.extend_from_slice(&e.id.to_le_bytes());
        out.extend_from_slice(&e.k.to_le_bytes());
    }
    out.extend_from_slice(&(car.regs.len() as u32).to_le_bytes());
    for r in &car.regs {
        match r {
            V::S(v) => {
                out.push(0);
                out.extend_from_slice(&v.to_le_bits_arr());
            }
            V::V3(a) => {
                out.push(1);
                for comp in a {
                    out.extend_from_slice(&comp.to_le_bits_arr());
                }
            }
        }
    }
    out.extend_from_slice(&car.total_iterations.to_le_bytes());
    out
}

/// 字节 → carrier。
pub fn carrier_from_bytes(bytes: &[u8]) -> Result<Carrier, DecodeError> {
    let mut c = Cursor::new(bytes);
    if c.take(4)? != CARRIER_MAGIC {
        return Err(DecodeError::BadMagic);
    }
    if c.u32v()? != CARRIER_VERSION {
        return Err(DecodeError::BadVersion(c.u32v()?));
    }
    let loops = c.u32v()? as usize;
    let mut loop_stack = Vec::with_capacity(loops.min(256));
    for _ in 0..loops {
        let id = c.u16()?;
        let k = c.u64v()?;
        loop_stack.push(LoopEntry { id, k });
    }
    let regs_n = c.u32v()? as usize;
    let mut regs = Vec::with_capacity(regs_n.min(4096));
    for _ in 0..regs_n {
        match c.u8()? {
            0 => regs.push(V::S(c.f64v()?)),
            1 => {
                let a = [c.f64v()?, c.f64v()?, c.f64v()?];
                regs.push(V::V3(a));
            }
            s => return Err(DecodeError::BadShape(s)),
        }
    }
    let total = c.u64v()?;
    Ok(Carrier { loop_stack, regs, total_iterations: total })
}

trait ToLeBits {
    fn to_le_bits_arr(&self) -> [u8; 8];
}
impl ToLeBits for f64 {
    fn to_le_bits_arr(&self) -> [u8; 8] {
        self.to_bits().to_le_bytes()
    }
}
