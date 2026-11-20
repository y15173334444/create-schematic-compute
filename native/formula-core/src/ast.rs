//! 语句树（镜像 Java FormulaAst）+ Program 容器。
//! Statement tree (mirror of Java FormulaAst) + the Program container.

use crate::tok::Tok;

/// RPN 表达式 + 解码期认证书（L1）。RPN 是直线流（无控制流）,每 token 的栈效应
/// 恒定 ⇒ 最大栈深/下溢点/槽位上界在**构造期一次算清**;求值热路径据此走
/// unchecked 快路径（表达式栈与 Var 读零边界检查）,认证不通过走 checked 兜底,
/// 两路语义逐位相同（对拍网锁定）。
/// RPN expression + the decode-time certificate (L1). An RPN stream is straight
/// line code (no control flow) and every token has a constant stack effect, so
/// max depth / underflow points / slot upper bound are computed exactly once at
/// construction; the hot eval path takes the unchecked fast path (zero bounds
/// checks on the expression stack and Var reads) when the certificate holds and
/// falls back to the checked path otherwise — bit-identical semantics, locked by
/// the parity nets.
#[derive(Clone)]
pub struct Rpn {
    pub toks: Box<[Tok]>,
    /// 认证通过:①全流无栈下溢 ②最大栈深 ≤ 表达式栈上限 ③全部 Var 槽位 < slot_count。
    /// Certified: (1) no stack underflow anywhere, (2) max depth within the
    /// expression-stack cap, (3) every Var slot < slot_count.
    pub certified: bool,
    /// 流内最大 Var 槽位 + 1（0 = 无变量）。unchecked Var 读的前置条件锚点:
    /// 求值入口一次性核对 `regs.len() >= slot_hi`,不满足即降 checked 路。
    /// One past the highest Var slot in the stream (0 = no variables). The
    /// precondition anchor for unchecked Var reads: the eval entry checks
    /// `regs.len() >= slot_hi` once and degrades to the checked path otherwise.
    pub slot_hi: u32,
}

impl Rpn {
    /// 构造 + 认证（O(tokens),构造期一次）。`slot_count` = 程序槽位表长度
    /// （Program.names.len() 的同一事实源）。畸形流（下溢/超深/越界槽位）不
    /// 拒绝——只摘掉证书,由 checked 兜底复刻既有运行期语义（栈下溢 =
    /// Java NoSuchElementException 族,越界槽位由解码器另行拒绝）。
    /// Build + certify (O(tokens), once at construction). `slot_count` is the
    /// program slot-table length (same fact source as Program.names.len()).
    /// Malformed streams (underflow / over-deep / out-of-range slots) are NOT
    /// rejected here — they merely lose the certificate and ride the checked
    /// fallback, which reproduces the existing runtime semantics verbatim
    /// (stack underflow = the Java NoSuchElementException family; out-of-range
    /// slots are separately rejected by the decoder).
    pub fn from_toks(toks: Box<[Tok]>, slot_count: u32) -> Rpn {
        let mut depth: i32 = 0;
        let mut max_depth: i32 = 0;
        let mut clean = true;
        let mut slot_hi: u32 = 0;
        for t in toks.iter() {
            let (pops, pushes): (i32, i32) = match t {
                Tok::Const(_) => (0, 1),
                Tok::Var(s) => {
                    slot_hi = slot_hi.max(*s as u32 + 1);
                    (0, 1)
                }
                // 含 '(' 吃两项得 0 的 lenient 形态——栈效应同二元。
                // Including the '(' lenient shape (consume two, push zero) —
                // same stack effect as a binary op.
                Tok::Op(_) | Tok::Cmp(_) => (2, 1),
                Tok::Not | Tok::Swz(_) => (1, 1),
                // vec3 弹 3 个（arity() 记 1 是 Java 表口径,证书按弹栈数算）。
                Tok::Fn(f) => (f.consumed() as i32, 1),
            };
            depth -= pops;
            if depth < 0 {
                clean = false;
                depth = 0;
            }
            depth += pushes;
            max_depth = max_depth.max(depth);
        }
        let certified = clean
            && max_depth <= crate::interp::STACK_MAX_I32
            && slot_hi <= slot_count;
        Rpn { toks, certified, slot_hi }
    }
}

/// 纯标量探测：全程序（含嵌套体/循环条件）无向量函数、无 swizzle → 快路径资格。
/// vec3 值只能由向量函数产生，swizzle 保守排除（标量取分量虽也是标量，但罕见、
/// 不值得为它复杂化快路径）。mini 编译器与二进制桥（bridge.rs）共用同一规则，
/// 保证两条前端路径的快路径判定一致。
pub fn scan_scalar_only(body: &[Stmt]) -> bool {
    fn rpn_scalar(rpn: &[Tok]) -> bool {
        rpn.iter().all(|t| match t {
            Tok::Fn(f) => !f.is_vector_fn(),
            Tok::Swz(_) => false,
            _ => true,
        })
    }
    fn stmts_scalar(stmts: &[Stmt]) -> bool {
        stmts.iter().all(|s| match s {
            Stmt::Assign { rpn, .. } | Stmt::Expr(rpn) => rpn_scalar(&rpn.toks),
            Stmt::Repeat { body, .. } => stmts_scalar(body),
            Stmt::While { cond, body, .. } => rpn_scalar(&cond.toks) && stmts_scalar(body),
            Stmt::If { cond, body, els } => {
                rpn_scalar(&cond.toks) && stmts_scalar(body) && stmts_scalar(els)
            }
            Stmt::Break | Stmt::Continue => true,
        })
    }
    stmts_scalar(body)
}

/// v2 形态：输出表达式与语句体共用同一快路径判定（输出里藏向量函数/swizzle 会
/// 让标量快路径漏判 vec3 值域）。mini 编译器（无输出段）走空表重载。
/// v2 shape: output expressions share the fast-path scan with the body (a vector
/// function/swizzle hiding in an output would let the scalar fast path miss vec3
/// values). The mini compiler (no output section) rides the empty-slice overload.
pub fn scan_scalar_only_with_outputs(body: &[Stmt], outputs: &[Rpn]) -> bool {
    fn out_scalar(rpn: &[Tok]) -> bool {
        rpn.iter().all(|t| match t {
            Tok::Fn(f) => !f.is_vector_fn(),
            Tok::Swz(_) => false,
            _ => true,
        })
    }
    scan_scalar_only(body) && outputs.iter().all(|rpn| out_scalar(&rpn.toks))
}

/// 语句（与 FormulaAst.Stmt 逐字对齐；repeat/while 携带编译期唯一 id，
/// 供循环栈与 seek 续算按身份命中——对应 Java 的「同次 parse 内 AST 实例身份」）。
#[derive(Clone)]
pub enum Stmt {
    /// 赋值:var = expr。 / Assignment: var = expr.
    Assign { slot: u16, rpn: Rpn },
    /// 独立表达式语句(末行表达式 = 默认输出)。 / Standalone expression (last line = default output).
    Expr(Rpn),
    /// 定长循环 repeat N。 / Fixed-count loop.
    Repeat { id: u16, count: u64, body: Box<[Stmt]> },
    /// 条件循环 while。 / Conditional loop.
    While {
        id: u16,
        cond: Rpn,
        body: Box<[Stmt]>,
    },
    /// 条件分支。 / Conditional branch.
    If {
        cond: Rpn,
        body: Box<[Stmt]>,
        els: Box<[Stmt]>,
    },
    Break,
    Continue,
}

impl Stmt {
    /// 该语句(或嵌套体)是否包含目标循环（Java containsLoop 的 id 版）。
    pub fn contains_loop(&self, target: u16) -> bool {
        match self {
            Stmt::Repeat { id, body, .. } => *id == target || body.iter().any(|s| s.contains_loop(target)),
            Stmt::While { id, body, .. } => *id == target || body.iter().any(|s| s.contains_loop(target)),
            Stmt::If { body, els, .. } => {
                body.iter().any(|s| s.contains_loop(target)) || els.iter().any(|s| s.contains_loop(target))
            }
            _ => false,
        }
    }
}

/// 编译产物：语句树 + 槽位表 + 进度元数据 + 标量快路径探测。
/// Compiled program: statement tree + slot table + progress metadata + scalar
/// fast-path detection.
#[derive(Clone)]
pub struct Program {
    pub body: Box<[Stmt]>,
    /// 槽位名（调试/桥接回读用；求值期不查名）。 / Slot names (debug/bridge introspection only).
    pub names: Vec<String>,
    /// repeat id → count（progress 计算用，镜像 Java progressOf 的 ro.count()）。
    pub repeat_counts: Vec<(u16, u64)>,
    /// 输出表达式（v2 契约：Done 时对最终寄存器逐条求值 → 调用方输出缓冲，Java
    /// `outputRpns` 对 env 求值 → asScalar 的镜像；`oi ≥ len` 的输出 = 0）。
    /// Output expressions (v2 contract): evaluated against the final registers on
    /// Done into the caller's output buffer — the mirror of Java's outputRpns-vs-env
    /// evaluation; outputs beyond the list read as 0.
    pub outputs: Box<[Rpn]>,
    /// 输入引脚槽位（v3 契约：按引脚声明序；`u32::MAX` = 声明未引用的缺席钉）。
    /// warm 续算的 refresh 只写这些槽位——内部累加器等非引脚寄存器保持快照回灌
    /// 值，与 Java「env.putAll(快照) 后仅按引脚刷新输入变量」逐位同构。
    /// Input-pin slots (v3 contract, in pin declaration order; `u32::MAX` = a
    /// declared pin the script never references). Warm refresh writes ONLY these
    /// slots — non-pin registers (internal accumulators etc.) keep the restored
    /// snapshot, bit-identical to Java's "env.putAll(snapshot), then refresh the
    /// input vars by pin".
    pub pin_slots: Vec<u32>,
    /// 纯标量探测：全程序无向量函数/swizzle token → 求值可走 `f64` 裸栈快路径
    /// （值域不可能是 vec3：vec3 只能由向量函数产生）。基准/测试会克隆后翻转此位
    /// 做双路对拍；桥接层不要动它。
    /// True when no vector function/swizzle token exists anywhere — the eval loop
    /// may take the plain-`f64` fast path (vec3 can only be produced by vector
    /// functions). Bench/tests clone and flip this bit for cross-path parity.
    pub scalar_only: bool,
}

impl Program {
    /// Java progressOf：最外层 repeat 为主、内层份额平滑叠加；最外层 while →
    /// 回退最内层 repeat；都没有 → -1（不定）。
    pub fn progress_of(&self, loop_stack: &[crate::LoopEntry]) -> f32 {
        if loop_stack.is_empty() {
            return 0.0;
        }
        let count_of = |id: u16| self.repeat_counts.iter().find(|(i, _)| *i == id).map(|(_, c)| *c);
        let outer = &loop_stack[0];
        if let Some(oc) = count_of(outer.id).filter(|c| *c > 0) {
            let mut p = outer.k as f64 / oc as f64;
            let inner = &loop_stack[loop_stack.len() - 1];
            if inner.id != outer.id {
                if let Some(ic) = count_of(inner.id).filter(|c| *c > 0) {
                    p += (inner.k as f64 / ic as f64) / oc as f64;
                }
            }
            return p.clamp(0.0, 1.0) as f32;
        }
        // 最外层是 while → 退回最内层 repeat / outermost is while → fall back to innermost repeat
        let inner = &loop_stack[loop_stack.len() - 1];
        count_of(inner.id)
            .filter(|c| *c > 0)
            .map(|c| (inner.k as f64 / c as f64).clamp(0.0, 1.0) as f32)
            .unwrap_or(-1.0)
    }
}
