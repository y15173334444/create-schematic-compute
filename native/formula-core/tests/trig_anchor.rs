//! StrictMath↔libm 逐位锚定：Java StrictMath（fdlibm 血统）生成的位模式夹具，
//! 逐行与 libm crate 结果按位比对。这是桥接契约的精度锚——两端同源才有全逐位对拍。
//!
//! Bit-exact anchor: Java StrictMath (fdlibm lineage) fixture vs the libm crate,
//! compared on raw bit patterns. This is the precision anchor of the bridge
//! contract — cross-end bit parity requires both ends on the same lineage.

use std::fmt::Write as _;

#[path = "../src/tok.rs"]
mod tok;

#[test]
fn strictmath_libm_bit_anchor() {
    let fixture = include_str!("fixtures/strictmath_trig.txt");
    let mut checked = 0u32;
    let mut total_mismatches = 0u32;
    let mut mismatches: Vec<String> = Vec::new();
    for line in fixture.lines() {
        let fields: Vec<&str> = line.split_ascii_whitespace().collect();
        // 行形态：fn arg... result（结果恒为末字段；单参 3 字段、双参 4 字段）
        let (f, want_bits, args) = match fields.as_slice() {
            [f, a0, r] => (*f, *r, [*a0, "0"]),
            [f, a0, a1, r] => (*f, *r, [*a0, *a1]),
            _ => panic!("bad fixture line: {line}"),
        };
        let arg0 = f64::from_bits(u64::from_str_radix(args[0], 16).unwrap());
        let arg1 = f64::from_bits(u64::from_str_radix(args[1], 16).unwrap());
        let want_bits = u64::from_str_radix(want_bits, 16).unwrap();
        // sin/cos/tan 夹具参数为度（Java 探针内部 toRadians 后求值）——求值引擎同款组合
        let (got, want) = match f {
            "sin" => (formula_native::fdlibm::sin(arg0.to_radians()), true),
            "cos" => (formula_native::fdlibm::cos(arg0.to_radians()), true),
            "tan" => (libm::tan(arg0.to_radians()), true),
            "asin" => (libm::asin(arg0), true),
            "acos" => (libm::acos(arg0), true),
            "atan2" => (libm::atan2(arg0, arg1), true),
            "exp" => (libm::exp(arg0), true),
            "ln" => (formula_native::fdlibm::log(arg0), true),
            "log" => (formula_native::fdlibm::log10(arg0), true),
            "sinh" => (libm::sinh(arg0), true),
            "cosh" => (libm::cosh(arg0), true),
            "pow" => (libm::pow(arg0, arg1), true),
            _ => panic!("unknown fixture fn: {f}"),
        };
        let _ = want;
        checked += 1;
        if got.to_bits() != want_bits {
            total_mismatches += 1;
            if mismatches.len() < 400 {
                mismatches.push(format!(
                    "{f}({arg0:e}{}) want {want_bits:016x} got {:016x} ({got:e})",
                    if f == "pow" || f == "atan2" {
                        format!(", {arg1:e}")
                    } else {
                        String::new()
                    },
                    got.to_bits()
                ));
            }
        }
    }
    assert!(
        total_mismatches == 0,
        "libm vs StrictMath mismatch on {total_mismatches}/{checked} samples (showing up to 8):\n{}",
        mismatches.join("\n")
    );
    let _ = writeln!(String::new(), "");
    assert!(checked > 10_000, "fixture unexpectedly small: {checked}");
}
