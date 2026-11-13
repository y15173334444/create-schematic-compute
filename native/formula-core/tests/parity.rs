//! 对拍测试：终值与主仓 Java 链（javac 直跑 Parity 探针）逐位对齐；
//! suspend/resume 迭代守恒；MAX_ITER shed；lenient 规则实证。
//!
//! Parity tests: final values bit-aligned against the main-repo Java chain
//! (javac-run Parity probe); suspend/resume iteration conservation; MAX_ITER
//! shed; lenient rules.

use std::time::Duration;

use formula_core::{compile, Budget, Exec, Outcome, V};

struct Rt {
    regs: Vec<V>,
    loops: Vec<formula_core::LoopEntry>,
    out: [V; 8],
}

impl Rt {
    fn new() -> Rt {
        Rt { regs: Vec::new(), loops: Vec::new(), out: [V::ZERO; 8] }
    }
}

fn run_all(src: &str, inputs: &[(&str, V)]) -> (formula_core::Program, Vec<V>) {
    let prog = compile(src).expect("compile");
    let mut slots = vec![V::ZERO; prog.names.len()];
    for (name, v) in inputs {
        let i = prog.names.iter().position(|n| n == name).expect("input slot");
        slots[i] = *v;
    }
    let mut rt = Rt::new();
    loop {
        let ex = Exec::new(&prog, &mut rt.regs, &mut rt.loops, &slots, None, Budget::unlimited());
        match ex.run(&mut rt.out) {
            Outcome::Done => return (prog, rt.out.to_vec()),
            Outcome::Suspended(c) => {
                let ex = Exec::new(&prog, &mut rt.regs, &mut rt.loops, &[], Some(c), Budget::unlimited());
                match ex.run(&mut rt.out) {
                    Outcome::Done => return (prog, rt.out.to_vec()),
                    Outcome::Suspended(_) => unreachable!("loop must alternate"),
                    Outcome::Shed => panic!("unbudgeted run must not shed"),
                }
            }
            Outcome::Shed => panic!("unbudgeted run must not shed"),
        }
    }
}

fn scalar(regs: &[V], prog: &formula_core::Program, name: &str) -> f64 {
    let i = prog.names.iter().position(|n| n == name).expect("slot");
    regs.get(i).map(|v| v.as_scalar()).unwrap_or(0.0)
}

/// Java 探针实测（bench/java 链路，Zulu 21）：acc=4999950.0, i=100000.0
#[test]
fn scalar_loop_parity() {
    let src = "i = 0\nacc = 0\nrepeat 100000 {\n    acc = acc + i * 0.001\n    i = i + 1\n}";
    let (prog, regs) = run_all(src, &[]);
    assert_eq!(scalar(&regs, &prog, "acc"), 4999950.0);
    assert_eq!(scalar(&regs, &prog, "i"), 100000.0);
}

/// Java 探针实测：p=999.9999999992356,-47999.5000000018,3000.000000004729
///              v=1.0,-98.00000000010378,3.0
#[test]
fn vec3_loop_parity() {
    let src = "p = vec3(0, 0, 0)\nv = vec3(1, 2, 3)\nrepeat 100000 {\n    p = p + v * 0.01\n    v = v - vec3(0, 0.001, 0)\n}";
    let (prog, regs) = run_all(src, &[]);
    let p = regs[prog.names.iter().position(|n| n == "p").unwrap()].as_vec();
    let v = regs[prog.names.iter().position(|n| n == "v").unwrap()].as_vec();
    assert_eq!(p[0], 999.9999999992356);
    assert_eq!(p[1], -47999.5000000018);
    assert_eq!(p[2], 3000.000000004729);
    assert_eq!(v[0], 1.0);
    assert_eq!(v[1], -98.00000000010378);
    assert_eq!(v[2], 3.0);
}

/// Java 探针实测：y=2.0（未知函数 lenient 链路 + 逗号 '(' 弹栈 + 栈顶即结果）。
#[test]
fn closed_form_parity() {
    let src = "y = sin(x) * cos(2*x) + sqrt(abs(x - 0.5)) + log(1 + x*x) ^ 2 \\\n    + sin(x*3) * 0.5 + floor(x) + min(x, 1) + max(x, 2)";
    let (prog, regs) = run_all(src, &[("x", V::S(0.5))]);
    assert_eq!(scalar(&regs, &prog, "y"), 2.0);
}

/// 未知函数 lenient（Java 实测：min(1,2)=2.0、floor(2.5)=2.5、foo(5)=5.0）。
#[test]
fn unknown_fn_lenient() {
    let (p1, r1) = run_all("y = min(1,2)", &[]);
    assert_eq!(scalar(&r1, &p1, "y"), 2.0);
    let (p2, r2) = run_all("y = floor(2.5)", &[]);
    assert_eq!(scalar(&r2, &p2, "y"), 2.5);
    let (p3, r3) = run_all("y = foo(5)", &[]);
    assert_eq!(scalar(&r3, &p3, "y"), 5.0);
}

/// 一元负号 = 0- 技巧（Java RPN [0, x, -, 3, +] 实测）。
#[test]
fn unary_minus() {
    let (prog, regs) = run_all("y = -x + 3", &[("x", V::S(5.0))]);
    assert_eq!(scalar(&regs, &prog, "y"), -2.0);
}

/// while + break + if（Java 测试同款语义：退出阈值）。
#[test]
fn while_break_if() {
    let src = "err = 1\nwhile (err > 0.001) {\n    err = err * 0.5\n    if (err < 0.001) {\n        break\n    }\n}";
    let (prog, regs) = run_all(src, &[]);
    assert!(scalar(&regs, &prog, "err") < 0.001);
}

/// MAX_ITER（1M spread-wide）硬 shed。
#[test]
fn max_iter_shed() {
    let src = "n = 0\nrepeat 2000000 {\n    n = n + 1\n}";
    let prog = compile(src).unwrap();
    let mut rt = Rt::new();
    let ex = Exec::new(&prog, &mut rt.regs, &mut rt.loops, &[], None, Budget::unlimited());
    let out = ex.run(&mut rt.out);
    assert!(matches!(out, Outcome::Shed), "repeat 2M must hard-shed at 1M iterations");
}

/// slice 挂起 + 续算守恒：小 slice 反复挂起，终值与不限预算完全一致；
/// 嵌套循环 + 累计（寻径续算的回归网）。
#[test]
fn suspend_resume_conservation() {
    let src = "n = 0\nrepeat 50 {\n    repeat 100 {\n        n = n + 1\n    }\n}";
    let prog = compile(src).unwrap();
    let mut regs = Vec::new();
    let mut loops = Vec::new();
    let mut out = [V::ZERO; 8];
    // 不限预算基线
    let ex = Exec::new(&prog, &mut regs, &mut loops, &[], None, Budget::unlimited());
    assert!(matches!(ex.run(&mut out), Outcome::Done));
    let idx = prog.names.iter().position(|p| p == "n").unwrap();
    assert_eq!(out[idx].as_scalar(), 5000.0);

    // 25µs slice：反复挂起/续算，终值必须逐位一致（迭代零丢失零重复）
    let mut regs = Vec::new();
    let mut loops = Vec::new();
    let mut resume: Option<formula_core::Carrier> = None;
    let mut suspends = 0u32;
    loop {
        let ex = Exec::new(&prog, &mut regs, &mut loops, &[], resume.take(), Budget::slice(Duration::from_micros(25)));
        match ex.run(&mut out) {
            Outcome::Done => break,
            Outcome::Suspended(c) => {
                suspends += 1;
                assert!(suspends < 10_000, "resume loop not converging");
                resume = Some(c);
            }
            Outcome::Shed => panic!("conservation script must not shed"),
        }
    }
    assert!(suspends > 0, "script must have actually suspended at least once");
    assert_eq!(out[idx].as_scalar(), 5000.0, "budgeted run must conserve iterations exactly");
}

/// while 循环的挂起/续算（k 不推进语义：cond 每轮重评）——干净的续算循环形态。
#[test]
fn while_suspend_resume() {
    let src = "i = 0\nacc = 0\nwhile (i < 20000) {\n    i = i + 1\n    acc = acc + i\n}";
    let prog = compile(src).unwrap();
    let mut regs = Vec::new();
    let mut loops = Vec::new();
    let mut out = [V::ZERO; 8];
    let mut resume: Option<formula_core::Carrier> = None;
    let mut suspends = 0u32;
    loop {
        let ex = Exec::new(&prog, &mut regs, &mut loops, &[], resume.take(), Budget::slice(Duration::from_micros(30)));
        match ex.run(&mut out) {
            Outcome::Done => break,
            Outcome::Suspended(c) => {
                suspends += 1;
                assert!(suspends < 10_000, "resume loop not converging");
                resume = Some(c);
            }
            Outcome::Shed => panic!("bounded while must not shed"),
        }
    }
    assert!(suspends > 0, "must actually suspend");
    // sum(1..20000) = 20000*20001/2
    let acc = out[prog.names.iter().position(|n| n == "acc").unwrap()].as_scalar();
    let i = out[prog.names.iter().position(|n| n == "i").unwrap()].as_scalar();
    assert_eq!(acc, 20000.0 * 20001.0 / 2.0);
    assert_eq!(i, 20000.0);
}
