//! formula-core — CSC 公式求值的 Rust 后端原型（探索线 csc-rust-backend-explore）。
//!
//! 目标：把 `FormulaParser.evaluateValue`（每 token 一次堆分配的 Boxed 栈机）与
//! `FormulaInterpreter`（语句解释器）的语义逐字搬到零分配实现上：
//! - 值域 = tagged 寄存器 `V`（标量/vec3 双形态，32B Copy，栈上传递）；
//! - 表达式 = 定长 `[V; STACK_MAX]` 原始栈机（对应 Java 的 `ArrayDeque<Value>`）；
//! - 环境 = 编译期槽位化的 `Vec<V>` 寄存器（对应 Java 的 `Map<String, Value>`）；
//! - 控制流 = 显式循环栈 + suspend/resume carrier（对应 Java 的
//!   `SuspendSignal` 异常 + `Carrier` 快照）——Rust 侧不用异常展开，挂起是
//!   一次普通的 `Err(Carrier)` 返回；
//! - 预算 = 两种口径与 Java 逐字对齐：循环边界每 16 迭代墙钟（slice 挂起）+
//!   表达式机每 256 ops 采样 deadline（K6 兜底），MAX_ITER=1M 跨 tick 累计硬 shed。
//!
//! 语义权威 = 主仓 graph 包（FormulaInterpreter/FormulaParser/FormulaFunctions），
//! 逐字对照的偏差一律算本 crate 的 bug。Java 解析器仍是唯一前端：正式桥接形态下
//! Java 侧把 RPN 编译成 `Tok` 字节码下发；本 crate 的 mini compiler 只是基准脚手架
//! （让基准脚本同源），不进桥接契约。
//!
//! Rust backend prototype for CSC formula evaluation (exploration line
//! csc-rust-backend-explore). Semantics authority = the main-repo graph package;
//! the Java parser stays the single frontend — the mini compiler here is bench
//! scaffolding only, not part of the bridge contract.

mod ast;
mod compiler;
pub mod fdlibm;
mod interp;
mod tok;
mod value;

pub use ast::{Program, Stmt};
pub use interp::{Budget, Carrier, Exec, LoopEntry, Outcome, Signal};
pub use value::V;

/// 解析 + 编译（bench 脚手架；正式形态由 Java 前端产出 Program）。
/// Parse + compile (bench scaffolding; the real bridge builds Programs on the Java side).
pub fn compile(src: &str) -> Result<Program, String> {
    compiler::compile(src)
}
