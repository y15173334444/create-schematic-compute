//! 统一值域：标量 / vec3 双形态，32B Copy 值——与 Java `Value`（Scalar/Vec3Val
//! record）语义逐字对齐，但全部走栈上传递，零堆分配。
//! Unified value domain: scalar/vec3 dual shape, 32-byte Copy — semantics aligned
//! verbatim with the Java `Value` records, but passed on the stack (zero heap).

/// 求值值域。`V3` 的未用分量恒为 0（广播语义由 as_scalar/as_vec 承担）。
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum V {
    S(f64),
    V3([f64; 3]),
}

impl V {
    /// 共享零值等价物（Java Value.Scalar.ZERO 的栈上形态）。
    pub const ZERO: V = V::S(0.0);

    /// 取标量分量；向量 lenient 归 0（Java FormulaParser.asScalar）。
    #[inline]
    pub fn as_scalar(self) -> f64 {
        match self {
            V::S(v) => v,
            V::V3(_) => 0.0, // 向量入标量位 → lenient 0 / vectors read as scalar → lenient 0
        }
    }

    /// 取向量形态；标量 lenient 广播为 (s, 0, 0)（Java FormulaParser.asVec）。
    #[inline]
    pub fn as_vec(self) -> [f64; 3] {
        match self {
            V::V3(a) => a,
            V::S(s) => [s, 0.0, 0.0],
        }
    }

    /// 条件真值：标量 != 0；向量条件 lenient 假。
    #[inline]
    pub fn truthy(self) -> bool {
        matches!(self, V::S(v) if v != 0.0)
    }
}
