//! 语句树（镜像 Java FormulaAst）+ Program 容器。
//! Statement tree (mirror of Java FormulaAst) + the Program container.

use crate::tok::Tok;

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
            Stmt::Assign { rpn, .. } | Stmt::Expr(rpn) => rpn_scalar(rpn),
            Stmt::Repeat { body, .. } => stmts_scalar(body),
            Stmt::While { cond, body, .. } => rpn_scalar(cond) && stmts_scalar(body),
            Stmt::If { cond, body, els } => rpn_scalar(cond) && stmts_scalar(body) && stmts_scalar(els),
            Stmt::Break | Stmt::Continue => true,
        })
    }
    stmts_scalar(body)
}

/// 语句（与 FormulaAst.Stmt 逐字对齐；repeat/while 携带编译期唯一 id，
/// 供循环栈与 seek 续算按身份命中——对应 Java 的「同次 parse 内 AST 实例身份」）。
#[derive(Clone)]
pub enum Stmt {
    /// 赋值:var = expr。 / Assignment: var = expr.
    Assign { slot: u16, rpn: Box<[Tok]> },
    /// 独立表达式语句(末行表达式 = 默认输出)。 / Standalone expression (last line = default output).
    Expr(Box<[Tok]>),
    /// 定长循环 repeat N。 / Fixed-count loop.
    Repeat { id: u16, count: u64, body: Box<[Stmt]> },
    /// 条件循环 while。 / Conditional loop.
    While {
        id: u16,
        cond: Box<[Tok]>,
        body: Box<[Stmt]>,
    },
    /// 条件分支。 / Conditional branch.
    If {
        cond: Box<[Tok]>,
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
