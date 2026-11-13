//! 零分配求值引擎：语句级解释 + 定长表达式栈机 + 显式挂起/续算。
//! 语义权威 = 主仓 FormulaInterpreter / FormulaParser.evaluateValue，逐字对齐。
//!
//! Zero-allocation engine: statement interpreter + fixed expression stack +
//! explicit suspend/resume. Semantics authority = the main-repo FormulaInterpreter
//! and FormulaParser.evaluateValue, mirrored verbatim.

use std::time::{Duration, Instant};

use crate::ast::{Program, Stmt};
use crate::tok::{Cmp, Fn, Tok};
use crate::value::V;

/// 表达式栈上限（深度受 MAX_PARSE_DEPTH 类约束；64 足够，溢出 = lenient 归零口径）。
/// Expression stack cap (parse depth bounds nesting; overflow reads as lenient zero).
const STACK_MAX: usize = 64;

/// 每 16 迭代测一次墙钟（Java CHECK_EVERY）。 / Wall clock every 16 iterations.
const CHECK_EVERY: u32 = 16;
/// 病态循环硬上限，按 spread 累计（Java MAX_ITER）。 / Pathological-loop hard cap, spread-wide.
const MAX_ITER: u64 = 1_000_000;
/// 语句嵌套递归上限（Java MAX_EXEC_DEPTH）。 / Statement nesting cap.
const MAX_EXEC_DEPTH: usize = 256;

/// 循环栈条目（Java LoopState）。k = 下一轮序号；repeat 在 body 完成后才推进。
#[derive(Clone, Copy, Debug)]
pub struct LoopEntry {
    pub id: u16,
    pub k: u64,
}

/// 挂起收敛态（Java Carrier）：循环栈 + 寄存器快照 + spread 累计迭代。
/// 存盘丢弃、跨 tick 复用（suspend-lite transient）。
pub struct Carrier {
    pub loop_stack: Vec<LoopEntry>,
    pub regs: Vec<V>,
    pub total_iterations: u64,
}

/// 求值预算（对应 Java Ctx 的 sliceNs + nodeStartNs：**每次 exec 调用独立**，
/// 续算不继承上一段的墙钟——Java 的 Ctx 每次 exec 新建）。
#[derive(Clone, Copy)]
pub struct Budget {
    pub start: Instant,
    pub slice: Option<Duration>,
}

impl Budget {
    pub fn unlimited() -> Budget {
        Budget { start: Instant::now(), slice: None }
    }
    pub fn slice(d: Duration) -> Budget {
        Budget { start: Instant::now(), slice: Some(d) }
    }
}

/// 控制流信号（Java 的 BreakSignal/ContinueSignal/ShedSignal/DeadlineShedException
/// 的显式返回值形态——不用异常展开）。
pub enum Signal {
    Break,
    Continue,
    /// MAX_ITER 硬 shed（病态脚本）。 / hard shed.
    Shed,
    /// 语句嵌套超限（Java ExecTooDeepException）。 / nesting over the cap.
    TooDeep,
    /// 表达式栈下溢（Java 对畸形 RPN 抛 NoSuchElementException，eval 分支兜底归零）。
    Underflow,
    /// K6 deadline 兜底（表达式机采样命中）。 / K6 deadline backstop.
    Deadline,
    /// slice 挂起（循环边界墙钟命中），携带 carrier。 / slice suspend with carrier.
    Suspend(Carrier),
}

/// 单趟结果。Done 时终值已拷入调用方输出缓冲；Suspended 带 carrier 供下 tick
/// 续算；Shed 无终值（上层输出保持 lastGood，与 Java 同口径）。
pub enum Outcome {
    Done,
    Suspended(Carrier),
    Shed,
}

#[inline]
fn copy_out(regs: &[V], out: &mut [V]) {
    let n = regs.len().min(out.len());
    out[..n].copy_from_slice(&regs[..n]);
}

/// 一次 exec 的求值状态。`ops` 是**调用内局部**的 K6 采样计数器（Java 同款：
/// 微小表达式永不采样，共享计数器曾有跨类泄漏前科）。
pub struct Exec<'p, 'b> {
    prog: &'p Program,
    regs: &'b mut Vec<V>,
    loop_stack: &'b mut Vec<LoopEntry>,
    budget: Budget,
    iters: u32,
    total: u64,
}

impl<'p, 'b> Exec<'p, 'b> {
    /// 新建求值。寄存器/循环栈缓冲由调用方提供并**跨 exec 复用**（桥接层每节点
    /// 持一对缓冲 → 稳态零堆分配；`Vec` 只在首次/扩容时分配）。`inputs`: 槽位 →
    /// 初值（长度可小于槽位数，其余 lenient 0）。`resume`: 上一个 carrier
    /// （Some = 寻径续算；快照回灌后循环栈接管所有权、寄存器拷贝）。
    pub fn new(
        prog: &'p Program,
        regs: &'b mut Vec<V>,
        loop_stack: &'b mut Vec<LoopEntry>,
        inputs: &[V],
        resume: Option<Carrier>,
        budget: Budget,
    ) -> Exec<'p, 'b> {
        regs.clear();
        regs.resize(prog.names.len().max(inputs.len()), V::ZERO);
        for (i, v) in inputs.iter().enumerate() {
            regs[i] = *v;
        }
        match resume {
            Some(c) => {
                // 快照回灌：寄存器拷贝（输入钉若需更新由调用方直接改 c.regs 后再传入）、
                // 循环栈整体接管。语义对应 Java 的「恢复 Env 快照 + resume 栈」。
                for (i, v) in c.regs.iter().enumerate() {
                    if i < regs.len() {
                        regs[i] = *v;
                    }
                }
                let total = c.total_iterations;
                *loop_stack = c.loop_stack;
                Exec { prog, regs, loop_stack, budget, iters: 0, total }
            }
            None => Exec { prog, regs, loop_stack, budget, iters: 0, total: 0 },
        }
    }

    /// 执行到完成 / 挂起 / shed。Done 时终值拷入 `out`（Java 侧提供的输出缓冲，
    /// 长度可小于槽位数）。
    pub fn run(mut self, out: &mut [V]) -> Outcome {
        let body = &*self.prog.body;
        let r = self.run_body(body, 0, 0);
        match r {
            Ok(()) => {
                copy_out(self.regs, out);
                Outcome::Done
            }
            Err(Signal::Break) | Err(Signal::Continue) => {
                // 顶层 break/continue lenient 忽略（F14，与 Java exec 同口径）。
                copy_out(self.regs, out);
                Outcome::Done
            }
            Err(Signal::Suspend(c)) => Outcome::Suspended(c),
            Err(Signal::Shed) => Outcome::Shed,
            Err(e) => {
                // TooDeep / Underflow / Deadline 在 Java 里是异常族、由 eval 分支
                // 捕获（输出归零/保持）；Deadline 在 Java 由 exec() 包装转
                // fromDeadline 挂起。此处 Deadline 也转挂起，其余上抛给调用方按
                // 「输出保持」处置。
                if matches!(e, Signal::Deadline) {
                    Outcome::Suspended(self.snapshot())
                } else {
                    Outcome::Shed
                }
            }
        }
    }

    /// 挂起快照（Java Ctx.snapshot）。 / Suspension snapshot.
    fn snapshot(&self) -> Carrier {
        Carrier {
            loop_stack: self.loop_stack.clone(),
            regs: self.regs.clone(),
            total_iterations: self.total,
        }
    }

    /// 循环边界检查（Java boundaryCheck）：每 16 迭代测墙钟 → 挂起；MAX_ITER → shed。
    #[inline]
    fn boundary_check(&mut self) -> Result<(), Signal> {
        self.iters += 1;
        if self.iters >= CHECK_EVERY {
            self.iters = 0;
            if self.over_slice() {
                return Err(Signal::Suspend(self.snapshot()));
            }
        }
        self.total += 1;
        if self.total > MAX_ITER {
            return Err(Signal::Shed);
        }
        Ok(())
    }

    #[inline]
    fn over_slice(&self) -> bool {
        match self.budget.slice {
            Some(s) => self.budget.start.elapsed() > s,
            None => false,
        }
    }

    /// 语句体执行；resume 模式（depth < loop_stack.len()）走寻径执行——跳过已
    /// 快照化的前缀语句直达挂起循环（Java runBody seek 逐字对齐）。
    fn run_body(&mut self, stmts: &[Stmt], depth: usize, rec: usize) -> Result<(), Signal> {
        if rec > MAX_EXEC_DEPTH {
            return Err(Signal::TooDeep);
        }
        if depth < self.loop_stack.len() {
            let target = self.loop_stack[depth].id;
            let mut i = 0;
            while i < stmts.len() {
                if stmts[i].contains_loop(target) {
                    self.exec_stmt(&stmts[i], depth, rec)?;
                    i += 1;
                    break;
                }
                i += 1;
            }
            for s in &stmts[i..] {
                self.exec_stmt(s, depth, rec)?;
            }
            Ok(())
        } else {
            for s in stmts {
                self.exec_stmt(s, depth, rec)?;
            }
            Ok(())
        }
    }

    fn find_or_push(&mut self, id: u16, depth: usize) -> usize {
        if depth < self.loop_stack.len() && self.loop_stack[depth].id == id {
            return depth;
        }
        self.loop_stack.push(LoopEntry { id, k: 0 });
        self.loop_stack.len() - 1
    }

    fn exec_stmt(&mut self, s: &Stmt, depth: usize, rec: usize) -> Result<(), Signal> {
        match s {
            Stmt::Assign { slot, rpn } => {
                let v = self.eval(rpn)?;
                self.regs[*slot as usize] = v;
                Ok(())
            }
            Stmt::Expr(rpn) => self.eval(rpn).map(|_| ()),
            Stmt::Repeat { id, count, body } => {
                let st_depth = self.find_or_push(*id, depth);
                let mut k = self.loop_stack[st_depth].k;
                while k < *count {
                    // 挂起时 carrier 保留当前轮序号：body 完成前不推进 k。
                    self.loop_stack[st_depth].k = k;
                    self.boundary_check()?;
                    match self.run_body(body, st_depth + 1, rec + 1) {
                        Ok(()) => {}
                        Err(Signal::Continue) => {}
                        Err(Signal::Break) => break,
                        Err(e) => return Err(e),
                    }
                    k += 1;
                    self.loop_stack[st_depth].k = k;
                }
                self.loop_stack.pop();
                Ok(())
            }
            Stmt::While { id, cond, body } => {
                let st_depth = self.find_or_push(*id, depth);
                loop {
                    let c = self.eval(cond)?;
                    if !c.truthy() {
                        break;
                    }
                    self.boundary_check()?;
                    match self.run_body(body, st_depth + 1, rec + 1) {
                        Ok(()) => {}
                        Err(Signal::Continue) => {}
                        Err(Signal::Break) => break,
                        Err(e) => return Err(e),
                    }
                }
                self.loop_stack.pop();
                Ok(())
            }
            Stmt::If { cond, body, els } => {
                let take = self.eval(cond)?.truthy();
                if take {
                    self.run_body(body, depth, rec + 1)
                } else {
                    self.run_body(els, depth, rec + 1)
                }
            }
            Stmt::Break => Err(Signal::Break),
            Stmt::Continue => Err(Signal::Continue),
        }
    }

    // ───────────────── 表达式栈机（Java evaluateValue 逐字镜像） ─────────────────

    fn eval(&mut self, rpn: &[Tok]) -> Result<V, Signal> {
        let mut stack = [V::ZERO; STACK_MAX];
        let mut sp = 0usize;
        let mut ops: u32 = 0; // 调用内局部 K6 计数器（Java 同款）
        for tok in rpn {
            ops += 1;
            if ops & 0xFF == 0 && self.over_slice() {
                return Err(Signal::Deadline);
            }
            match tok {
                Tok::Const(d) => {
                    push(&mut stack, &mut sp, V::S(*d))?;
                }
                Tok::Var(slot) => {
                    let v = self.regs[*slot as usize];
                    push(&mut stack, &mut sp, v)?;
                }
                Tok::Op(op) => {
                    let b = pop(&mut stack, &mut sp)?;
                    let a = pop(&mut stack, &mut sp)?;
                    if matches!(a, V::V3(_)) || matches!(b, V::V3(_)) {
                        push(&mut stack, &mut sp, vector_op(*op, a, b))?;
                        continue;
                    }
                    let b = b.as_scalar();
                    let a = a.as_scalar();
                    let r = match op {
                        b'+' => a + b,
                        b'-' => a - b,
                        b'*' => a * b,
                        b'/' => {
                            if b != 0.0 {
                                a / b
                            } else {
                                0.0
                            }
                        }
                        b'%' => {
                            if b != 0.0 {
                                a % b
                            } else {
                                0.0
                            }
                        }
                        b'^' => a.powf(b),
                        b'<' => {
                            if a < b {
                                1.0
                            } else {
                                0.0
                            }
                        }
                        b'>' => {
                            if a > b {
                                1.0
                            } else {
                                0.0
                            }
                        }
                        _ => 0.0,
                    };
                    push(&mut stack, &mut sp, V::S(r))?;
                }
                Tok::Cmp(c) => {
                    let b = pop(&mut stack, &mut sp)?;
                    let a = pop(&mut stack, &mut sp)?;
                    // 向量不可比较 → lenient 0（运行期；校验期报错）。
                    if matches!(a, V::V3(_)) || matches!(b, V::V3(_)) {
                        push(&mut stack, &mut sp, V::ZERO)?;
                        continue;
                    }
                    let b = b.as_scalar();
                    let a = a.as_scalar();
                    const EQ_TOL: f64 = 1e-6; // Java EQ_TOLERANCE
                    let r = match c {
                        Cmp::Eq => {
                            if (a - b).abs() < EQ_TOL {
                                1.0
                            } else {
                                0.0
                            }
                        }
                        Cmp::Ne => {
                            if (a - b).abs() < EQ_TOL {
                                0.0
                            } else {
                                1.0
                            }
                        }
                        Cmp::Le => {
                            if a <= b {
                                1.0
                            } else {
                                0.0
                            }
                        }
                        Cmp::Ge => {
                            if a >= b {
                                1.0
                            } else {
                                0.0
                            }
                        }
                        Cmp::And => {
                            if a != 0.0 && b != 0.0 {
                                1.0
                            } else {
                                0.0
                            }
                        }
                        Cmp::Or => {
                            if a != 0.0 || b != 0.0 {
                                1.0
                            } else {
                                0.0
                            }
                        }
                    };
                    push(&mut stack, &mut sp, V::S(r))?;
                }
                Tok::Not => {
                    let v = pop(&mut stack, &mut sp)?;
                    push(&mut stack, &mut sp, V::S(if v.as_scalar() != 0.0 { 0.0 } else { 1.0 }))?;
                }
                Tok::Swz(c) => {
                    let base = pop(&mut stack, &mut sp)?;
                    let r = match (base, *c) {
                        (V::V3(a), 0..=2) => V::S(a[*c as usize]),
                        _ => V::ZERO, // 标量取分量/多分量 → lenient 零
                    };
                    push(&mut stack, &mut sp, r)?;
                }
                Tok::Fn(f) => {
                    if f.is_vector_fn() {
                        // 向量函数按参数形态分派（Java applyVectorFunction）。
                        match f {
                            Fn::Vec3 => {
                                let z = pop(&mut stack, &mut sp)?.as_scalar();
                                let y = pop(&mut stack, &mut sp)?.as_scalar();
                                let x = pop(&mut stack, &mut sp)?.as_scalar();
                                push(&mut stack, &mut sp, V::V3([x, y, z]))?;
                            }
                            Fn::Length => {
                                let v = pop(&mut stack, &mut sp)?.as_vec();
                                push(&mut stack, &mut sp, V::S(norm(v)))?;
                            }
                            Fn::Normalize => {
                                let v = pop(&mut stack, &mut sp)?.as_vec();
                                let l = norm(v);
                                push(
                                    &mut stack,
                                    &mut sp,
                                    if l > 1e-12 {
                                        V::V3([v[0] / l, v[1] / l, v[2] / l])
                                    } else {
                                        V::V3([0.0; 3])
                                    },
                                )?;
                            }
                            Fn::Dot => {
                                let b = pop(&mut stack, &mut sp)?.as_vec();
                                let a = pop(&mut stack, &mut sp)?.as_vec();
                                push(&mut stack, &mut sp, V::S(dot(a, b)))?;
                            }
                            Fn::Cross => {
                                let b = pop(&mut stack, &mut sp)?.as_vec();
                                let a = pop(&mut stack, &mut sp)?.as_vec();
                                push(
                                    &mut stack,
                                    &mut sp,
                                    V::V3([
                                        a[1] * b[2] - a[2] * b[1],
                                        a[2] * b[0] - a[0] * b[2],
                                        a[0] * b[1] - a[1] * b[0],
                                    ]),
                                )?;
                            }
                            Fn::Dist => {
                                let b = pop(&mut stack, &mut sp)?.as_vec();
                                let a = pop(&mut stack, &mut sp)?.as_vec();
                                push(&mut stack, &mut sp, V::S(norm([a[0] - b[0], a[1] - b[1], a[2] - b[2]])))?;
                            }
                            Fn::Yaw => {
                                // 逐字对齐 DIRECTION 节点（yaw = toDegrees(atan2(x, -z))，+360 % 360）。
                                let v = pop(&mut stack, &mut sp)?.as_vec();
                                let yaw = (v[0].atan2(-v[2]).to_degrees() + 360.0) % 360.0;
                                push(&mut stack, &mut sp, V::S(yaw))?;
                            }
                            Fn::Pitch => {
                                let v = pop(&mut stack, &mut sp)?.as_vec();
                                let h = (v[0] * v[0] + v[2] * v[2]).sqrt();
                                push(&mut stack, &mut sp, V::S((-v[1]).atan2(h).to_degrees()))?;
                            }
                            _ => unreachable!("vector fn set"),
                        }
                    } else {
                        // 标量函数：弹出 arity 个参数（Java 标量分支）。
                        let arity = f.arity();
                        let mut args = [0.0f64; 2];
                        for a in (0..arity).rev() {
                            args[a] = pop(&mut stack, &mut sp)?.as_scalar();
                        }
                        push(&mut stack, &mut sp, V::S(scalar_fn(*f, args)))?;
                    }
                }
            }
        }
        if sp == 0 {
            Ok(V::ZERO)
        } else {
            Ok(stack[sp - 1])
        }
    }
}

#[inline]
fn push(stack: &mut [V; STACK_MAX], sp: &mut usize, v: V) -> Result<(), Signal> {
    if *sp >= STACK_MAX {
        return Err(Signal::Underflow); // 溢出口径与下溢同：畸形形态，上层兜底
    }
    stack[*sp] = v;
    *sp += 1;
    Ok(())
}

#[inline]
fn pop(stack: &mut [V; STACK_MAX], sp: &mut usize) -> Result<V, Signal> {
    if *sp == 0 {
        return Err(Signal::Underflow); // Java: NoSuchElementException（畸形 RPN）
    }
    *sp -= 1;
    Ok(stack[*sp])
}

#[inline]
fn norm(v: [f64; 3]) -> f64 {
    (v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).sqrt()
}

#[inline]
fn dot(a: [f64; 3], b: [f64; 3]) -> f64 {
    a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
}

/// 向量参与的逐分量运算（Java applyVectorOp 矩阵逐字对齐）。
fn vector_op(op: u8, av: V, bv: V) -> V {
    let a_vec = matches!(av, V::V3(_));
    let b_vec = matches!(bv, V::V3(_));
    let a = av.as_vec();
    let b = bv.as_vec();
    let sa = av.as_scalar();
    let sb = bv.as_scalar();
    match op {
        b'+' => {
            if a_vec && b_vec {
                V::V3([a[0] + b[0], a[1] + b[1], a[2] + b[2]])
            } else if a_vec {
                V::V3([a[0] + sb, a[1] + sb, a[2] + sb])
            } else {
                V::V3([b[0] + sa, b[1] + sa, b[2] + sa])
            }
        }
        b'-' => {
            if a_vec && b_vec {
                V::V3([a[0] - b[0], a[1] - b[1], a[2] - b[2]])
            } else if a_vec {
                V::V3([a[0] - sb, a[1] - sb, a[2] - sb])
            } else {
                V::V3([sa - b[0], sa - b[1], sa - b[2]])
            }
        }
        b'*' => {
            if !b_vec {
                V::V3([a[0] * sb, a[1] * sb, a[2] * sb])
            } else if !a_vec {
                V::V3([sa * b[0], sa * b[1], sa * b[2]])
            } else {
                V::V3([0.0; 3]) // v×v 无良定义 / undefined
            }
        }
        b'/' => {
            if a_vec && !b_vec && sb != 0.0 {
                V::V3([a[0] / sb, a[1] / sb, a[2] / sb])
            } else {
                V::V3([0.0; 3]) // v÷v、标量÷v 无良定义 / undefined
            }
        }
        _ => V::V3([0.0; 3]), // % ^ 对向量无良定义 / undefined
    }
}

/// 标量内建函数（Java applyFunction 逐字对齐：角度为度、定义域外 lenient 0）。
fn scalar_fn(f: Fn, args: [f64; 2]) -> f64 {
    let v = args[0];
    match f {
        Fn::Sin => {
            if v.is_finite() {
                v.to_radians().sin()
            } else {
                0.0
            }
        }
        Fn::Cos => {
            if v.is_finite() {
                v.to_radians().cos()
            } else {
                0.0
            }
        }
        Fn::Tan => {
            if v.is_finite() {
                v.to_radians().tan()
            } else {
                0.0
            }
        }
        Fn::Asin => {
            if v.is_finite() && (-1.0..=1.0).contains(&v) {
                v.asin().to_degrees()
            } else {
                0.0
            }
        }
        Fn::Acos => {
            if v.is_finite() && (-1.0..=1.0).contains(&v) {
                v.acos().to_degrees()
            } else {
                0.0
            }
        }
        Fn::Atan2 => {
            let (y, x) = (args[0], args[1]);
            if y.is_finite() && x.is_finite() {
                y.atan2(x).to_degrees()
            } else {
                0.0
            }
        }
        Fn::Sinh => {
            if v.is_finite() {
                v.sinh()
            } else {
                0.0
            }
        }
        Fn::Cosh => {
            if v.is_finite() {
                v.cosh()
            } else {
                0.0
            }
        }
        Fn::Sqrt => {
            if v.is_finite() && v >= 0.0 {
                v.sqrt()
            } else {
                0.0
            }
        }
        Fn::Ln => {
            if v.is_finite() && v > 0.0 {
                v.ln()
            } else {
                0.0
            }
        }
        Fn::Log => {
            if v.is_finite() && v > 0.0 {
                v.log10()
            } else {
                0.0
            }
        }
        Fn::Exp => {
            if v.is_finite() {
                v.exp()
            } else {
                0.0
            }
        }
        Fn::Sec => {
            if v.is_finite() && v.to_radians().cos().abs() > 1e-12 {
                1.0 / v.to_radians().cos()
            } else {
                0.0
            }
        }
        Fn::Csc => {
            if v.is_finite() && v.to_radians().sin().abs() > 1e-12 {
                1.0 / v.to_radians().sin()
            } else {
                0.0
            }
        }
        Fn::Cot => {
            if v.is_finite() && v.to_radians().tan().abs() > 1e-12 {
                1.0 / v.to_radians().tan()
            } else {
                0.0
            }
        }
        _ => 0.0, // 向量函数不会走到这（is_vector_fn 已分派）
    }
}
