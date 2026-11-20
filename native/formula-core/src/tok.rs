//! 紧凑字节码 token：Java `List<Object>` RPN 的定宽形态（**16B/项**——实测口径,
//! `Const(f64)` 撑大整枚举,`size_of::<Tok>() == 16`）。历史注释宣称的「8B 定宽」
//! 从未兑现;常量池化（`Const(u32 索引)` + 池挂 Rpn → 8B）2026-11-21 实测未达
//! 判定门、已整体回退（分布与判定见 docs/rust-backend-optimization.md §五.3）。
//! 求值零分支开销、可整块下沉到 native。
//! Compact bytecode token: fixed-width form of the Java `List<Object>` RPN
//! (16B per item — measured; `Const(f64)` inflates the enum,
//! `size_of::<Tok>() == 16`). The historical "8B fixed-width" claim never
//! materialized; constant pooling (`Const(u32 index)` + a pool on Rpn → 8B) was
//! measured on 2026-11-21, missed its decision gate and was reverted whole (the
//! distributions and verdict live in docs/rust-backend-optimization.md §5.3).
//! Dispatchable without per-token boxing.

/// 标量/向量内建函数（表与 Java FormulaFunctions 逐字对齐；未知函数在编译期
/// 按 Java 的 lenient 规则降级为「名字当变量、括号消失、结果=栈顶」）。
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(u8)]
pub enum Fn {
    Vec3,
    Length,
    Normalize,
    Dot,
    Cross,
    Dist,
    Yaw,
    Pitch,
    Sin,
    Cos,
    Tan,
    Asin,
    Acos,
    Atan2,
    Sinh,
    Cosh,
    Sqrt,
    Ln,
    Log,
    Exp,
    Sec,
    Csc,
    Cot,
}

impl Fn {
    /// 元数（与 Java FUNCTIONS 表一致）。
    #[inline]
    pub const fn arity(self) -> usize {
        match self {
            Fn::Atan2 | Fn::Dot | Fn::Cross | Fn::Dist => 2,
            _ => 1,
        }
    }

    /// Java FUNCTIONS 表里的向量函数（按参数形态分派的那一半）。
    #[inline]
    pub const fn is_vector_fn(self) -> bool {
        matches!(
            self,
            Fn::Vec3 | Fn::Length | Fn::Normalize | Fn::Dot | Fn::Cross | Fn::Dist | Fn::Yaw | Fn::Pitch
        )
    }

    /// 求值期实际弹栈数（栈效应认证用；与 eval_full 各分支逐字一致）。
    /// vec3 三元、atan2/dot/cross/dist 二元、其余一元。注意 `arity()` 是 Java
    /// FUNCTIONS 表口径（vec3 记 1），与这里的**求值弹栈数**不是一回事——证书
    /// 必须按弹栈数算,否则 vec3 程序会被误认证。
    /// Actual pop count at eval time (stack-effect certification; mirrors each
    /// eval_full branch verbatim). vec3 pops three, atan2/dot/cross/dist two, the
    /// rest one. Note `arity()` is the Java FUNCTIONS-table arity (vec3 reads 1)
    /// and is NOT the eval pop count — certification must use this number or
    /// vec3 programs get mis-certified.
    #[inline]
    pub const fn consumed(self) -> usize {
        match self {
            Fn::Vec3 => 3,
            Fn::Atan2 | Fn::Dot | Fn::Cross | Fn::Dist => 2,
            _ => 1,
        }
    }

    pub fn from_name(name: &str) -> Option<Fn> {
        Some(match name {
            "vec3" => Fn::Vec3,
            "length" => Fn::Length,
            "normalize" => Fn::Normalize,
            "dot" => Fn::Dot,
            "cross" => Fn::Cross,
            "dist" => Fn::Dist,
            "yaw" => Fn::Yaw,
            "pitch" => Fn::Pitch,
            "sin" => Fn::Sin,
            "cos" => Fn::Cos,
            "tan" => Fn::Tan,
            "asin" => Fn::Asin,
            "acos" => Fn::Acos,
            "atan2" => Fn::Atan2,
            "sinh" => Fn::Sinh,
            "cosh" => Fn::Cosh,
            "sqrt" => Fn::Sqrt,
            "ln" => Fn::Ln,
            "log" => Fn::Log,
            "exp" => Fn::Exp,
            "sec" => Fn::Sec,
            "csc" => Fn::Csc,
            "cot" => Fn::Cot,
            _ => return None,
        })
    }
}

/// 双字符比较/逻辑运算（Java OpToken 族）。
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(u8)]
pub enum Cmp {
    Eq,
    Ne,
    Le,
    Ge,
    And,
    Or,
}

/// 单个 RPN token。定宽枚举（16B——`Const(f64)` 撑大,见模块头）：Const/Var 携带
/// f64/u16，其余为判别。
/// Java 侧的 SwizzleToken 在此收敛为 `Swz(0..3)`；多分量/标量取分量的
/// lenient 归零在求值期复刻（base 非向量或分量越界 → 0）。
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Tok {
    Const(f64),
    /// 变量槽位（编译期互敦；未定义/未知函数名的 lenient 形态也落槽位，恒读 0）。
    Var(u16),
    /// 单字符运算：'+' '-' '*' '/' '%' '^' '<' '>'（ASCII 直存，与 Java char 分支一致）。
    Op(u8),
    Cmp(Cmp),
    Not,
    Fn(Fn),
    /// 分量访问：0=x 1=y 2=z（越界 = Java 多分量形态 → lenient 0）。
    Swz(u8),
}
