//! 紧凑字节码 token：Java `List<Object>` RPN 的固定宽度形态（16B/项 → 8B/项），
//! 求值零分支开销、可整块下沉到 native。
//! Compact bytecode token: fixed-width form of the Java `List<Object>` RPN,
//! dispatchable without per-token boxing.

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

/// 单个 RPN token。定宽枚举（8B）：Const/Var 携带 f64/u16，其余为判别。
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
