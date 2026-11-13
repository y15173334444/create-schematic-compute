//! 对拍测试：终值与主仓 Java 链（javac 直跑 Parity 探针）逐位对齐；
//! suspend/resume 迭代守恒；MAX_ITER shed；lenient 规则实证。
//!
//! Parity tests: final values bit-aligned against the main-repo Java chain
//! (javac-run Parity probe); suspend/resume iteration conservation; MAX_ITER
//! shed; lenient rules.

use std::time::Duration;

use formula_native::{compile, Budget, Exec, Outcome, V};

struct Rt {
    regs: Vec<V>,
    loops: Vec<formula_native::LoopEntry>,
    out: [V; 8],
}

impl Rt {
    fn new() -> Rt {
        Rt { regs: Vec::new(), loops: Vec::new(), out: [V::ZERO; 8] }
    }
}

fn run_all(src: &str, inputs: &[(&str, V)]) -> (formula_native::Program, Vec<V>) {
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

fn scalar(regs: &[V], prog: &formula_native::Program, name: &str) -> f64 {
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
    let mut out = vec![V::ZERO; prog.names.len()];
    // 不限预算基线
    let ex = Exec::new(&prog, &mut regs, &mut loops, &[], None, Budget::unlimited());
    assert!(matches!(ex.run(&mut out), Outcome::Done));
    let idx = prog.names.iter().position(|p| p == "n").unwrap();
    assert_eq!(out[idx].as_scalar(), 5000.0);

    // 25µs slice：反复挂起/续算，终值必须逐位一致（迭代零丢失零重复）
    let mut regs = Vec::new();
    let mut loops = Vec::new();
    let mut resume: Option<formula_native::Carrier> = None;
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

/// 真实重节点工作负载：docs/examples/ballistic_solver.formula（火控弹道反解，
/// 粗扫 46×400 tick 模拟 + 细扫 17 点 + 割线 ≤10 轮）。5 案例终值与 Java 链
/// （javac 直跑 BallisticProbe2，解析缓存口径）对齐；快路径自动生效（纯标量脚本）。
#[test]
fn ballistic_solver_java_parity() {
    let src = include_str!("../../../docs/examples/ballistic_solver.formula");
    let prog = compile(src).expect("ballistic script must compile");
    assert!(prog.scalar_only, "ballistic script is scalar-only -> fast path");
    let cases: [(f64, f64, f64, f64, f64, f64); 5] = [
        (0.0, 80.0, 0.0, 250.0, 82.0, -30.0),
        (0.0, 80.0, 0.0, 500.0, 70.0, -100.0),
        (0.0, 200.0, 0.0, 400.0, 20.0, 0.0),
        (0.0, 80.0, 0.0, 3.0, 190.0, 0.0),
        (0.0, 80.0, 0.0, 3000.0, 50.0, 0.0),
    ];
    // Java 探针实测（Double.toString 最短往返表示，f64 解析精确还原）
    let golden: [[f64; 6]; 5] = [
        [83.15722658736907, 7.808572044524522, 1.0, 157.38726603948803, 21.738207535385484, -18.88647192473855],
        [78.69006752597979, 28.3210790441535, 1.0, 138.11327846592283, 75.9059365520921, -27.622655693184562],
        [90.0, -11.01004380103375, 1.0, 157.0549952072379, -30.55697106151846, -9.61684485853234e-15],
        [90.0, 88.399, 1.0, 4.470253633129445, 159.9375404101723, -2.7372409015944007e-16],
        [90.0, 10.100999999999999, 0.0, 157.52001905563515, 28.06142542196212, -9.645319356905682e-15],
    ];
    let names = ["ay", "ap", "hit", "vx0", "vy0", "vz0"];
    for (ci, (mx, my, mz, tx, ty, tz)) in cases.into_iter().enumerate() {
        // 槽位按名映射（源码首次出现序 ≠ 引脚序：DT/HIT/P0… 等常量槽在前）
        let mut slots = vec![V::ZERO; prog.names.len()];
        for (name, v) in [("mx", mx), ("my", my), ("mz", mz), ("tx", tx), ("ty", ty), ("tz", tz)] {
            let i = prog.names.iter().position(|n| n == name).expect("input slot");
            slots[i] = V::S(v);
        }
        let mut regs = Vec::new();
        let mut loops = Vec::new();
        let mut out = vec![V::ZERO; prog.names.len()];
        let ex = Exec::new(&prog, &mut regs, &mut loops, &slots, None, Budget::unlimited());
        assert!(matches!(ex.run(&mut out), Outcome::Done), "case {ci} must complete");
        for (k, name) in names.iter().enumerate() {
            let i = prog.names.iter().position(|n| n == name).unwrap_or_else(|| panic!("slot {name}"));
            let got = out[i].as_scalar();
            let want = golden[ci][k];
            // fdlibm 移植后（src/fdlibm.rs = Java FdLibm 逐行翻译）全链逐位一致，
            // 含尘埃值（曾因 msvcrt/musl 弱归约失配的 vz0 ≈ 1e-15 带已随 libm→fdlibm 收口）。
            assert_eq!(got, want, "case {ci} {name}: fast path vs Java bit-exact");
        }
    }
}

/// 弹道求解的 slice 挂起/续算守恒：2ms slice 反复挂起，终值与不限预算逐位一致。
#[test]
fn ballistic_solver_sliced_conservation() {
    let src = include_str!("../../../docs/examples/ballistic_solver.formula");
    let prog = compile(src).expect("ballistic script must compile");
    let mut slots = vec![V::ZERO; prog.names.len()];
    for (name, v) in [("mx", 0.0), ("my", 80.0), ("mz", 0.0), ("tx", 500.0), ("ty", 70.0), ("tz", -100.0)] {
        let i = prog.names.iter().position(|n| n == name).expect("input slot");
        slots[i] = V::S(v);
    }
    let run = |slice: Budget| -> Vec<V> {
        let mut regs = Vec::new();
        let mut loops = Vec::new();
        let mut out = vec![V::ZERO; prog.names.len()];
        let mut resume: Option<formula_native::Carrier> = None;
        let mut suspends = 0u32;
        loop {
            let ex = Exec::new(&prog, &mut regs, &mut loops, &slots, resume.take(), slice);
            match ex.run(&mut out) {
                Outcome::Done => break out.to_vec(),
                Outcome::Suspended(c) => {
                    suspends += 1;
                    assert!(suspends < 100_000, "resume loop not converging");
                    resume = Some(c);
                }
                Outcome::Shed => panic!("ballistic solve must not shed"),
            }
        }
    };
    let full = run(Budget::unlimited());
    let sliced = run(Budget::slice(Duration::from_millis(2)));
    for i in 0..prog.names.len() {
        assert_eq!(
            full.get(i).copied().unwrap_or(V::ZERO),
            sliced.get(i).copied().unwrap_or(V::ZERO),
            "slot {} ({}) diverged under slicing",
            i,
            prog.names[i]
        );
    }
}

/// 标量快路径双路对拍：纯标量脚本 fast/full 两路终值逐位一致。
/// fast = 编译探测；full = 同一 Program 克隆翻转 scalar_only。
#[test]
fn scalar_fast_path_dual_parity() {
    let cases = [
        ("i = 0\nacc = 0\nrepeat 100000 {\n    acc = acc + i * 0.001\n    i = i + 1\n}", vec![]),
        ("y = sin(x) * cos(2*x) + sqrt(abs(x - 0.5)) + log(1 + x*x) ^ 2 \\\n    + sin(x*3) * 0.5 + floor(x) + min(x, 1) + max(x, 2)", vec![("x", V::S(0.5))]),
        ("y = -x + 3", vec![("x", V::S(5.0))]),
        ("y = min(1,2)", vec![]),
        ("err = 1\nwhile (err > 0.001) {\n    err = err * 0.5\n    if (err < 0.001) {\n        break\n    }\n}", vec![]),
        ("n = 0\nrepeat 50 {\n    repeat 100 {\n        n = n + 1\n    }\n}", vec![]),
    ];
    for (src, inputs) in cases {
        let prog = compile(src).expect("compile");
        assert!(prog.scalar_only, "script should be detected scalar-only: {src}");
        let mut slots = vec![V::ZERO; prog.names.len()];
        for (name, v) in &inputs {
            let i = prog.names.iter().position(|n| n == name).expect("input slot");
            slots[i] = *v;
        }
        // fast
        let mut regs_f = Vec::new();
        let mut loops_f = Vec::new();
        let mut out_f = [V::ZERO; 8];
        let ex = Exec::new(&prog, &mut regs_f, &mut loops_f, &slots, None, Budget::unlimited());
        assert!(matches!(ex.run(&mut out_f), Outcome::Done));
        // full（克隆翻转）
        let mut full = prog.clone();
        full.scalar_only = false;
        let mut regs_b = Vec::new();
        let mut loops_b = Vec::new();
        let mut out_b = [V::ZERO; 8];
        let ex = Exec::new(&full, &mut regs_b, &mut loops_b, &slots, None, Budget::unlimited());
        assert!(matches!(ex.run(&mut out_b), Outcome::Done));
        // 全槽位逐位一致
        for i in 0..prog.names.len() {
            assert_eq!(
                out_f.get(i).copied().unwrap_or(V::ZERO),
                out_b.get(i).copied().unwrap_or(V::ZERO),
                "slot {} ({}) diverged between fast/full for: {src}",
                i,
                prog.names[i]
            );
        }
    }
}

/// vec3 程序不得被判为纯标量（探测自检）。
#[test]
fn vec3_program_not_scalar_only() {
    let prog = compile("p = vec3(0, 0, 0)\np = p + vec3(1, 2, 3)").unwrap();
    assert!(!prog.scalar_only);
    let prog2 = compile("y = v.x").unwrap();
    assert!(!prog2.scalar_only, "swizzle must disqualify the fast path");
}

/// while 循环的挂起/续算（k 不推进语义：cond 每轮重评）——干净的续算循环形态。
#[test]
fn while_suspend_resume() {
    let src = "i = 0\nacc = 0\nwhile (i < 20000) {\n    i = i + 1\n    acc = acc + i\n}";
    let prog = compile(src).unwrap();
    let mut regs = Vec::new();
    let mut loops = Vec::new();
    let mut out = vec![V::ZERO; prog.names.len()];
    let mut resume: Option<formula_native::Carrier> = None;
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
