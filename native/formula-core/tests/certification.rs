//! L1 认证书测试网：静态证书正确性、unchecked/checked 双路逐位对拍、
//! 对抗槽位拒绝（解码期 BadSlot）。语义不变的验收面 = 双路输出逐位一致
//! + 既有 parity/bridge 网全绿。
//!
//! L1 certificate nets: static certificate correctness, unchecked/checked
//! dual-path bit-exactness, adversarial slot rejection (decode-time BadSlot).
//! The semantic-invariance acceptance face = bit-identical dual-path outputs
//! plus the existing parity/bridge nets.

use formula_native::bridge::{decode_program, DecodeError};
use formula_native::{
    compile, eval_output, Budget, Exec, Outcome, Program, Rpn, Signal, Stmt, Tok, V,
};

// ───────────────── 静态证书正确性 ─────────────────

fn rpn(toks: Vec<Tok>, slot_count: u32) -> Rpn {
    Rpn::from_toks(toks.into_boxed_slice(), slot_count)
}

#[test]
fn cert_static_profile() {
    // 平衡二元:Const Const + → 深 2,无变量。
    let r = rpn(vec![Tok::Const(1.0), Tok::Const(2.0), Tok::Op(b'+')], 0);
    assert!(r.certified, "balanced binary must certify");
    assert_eq!(r.slot_hi, 0);

    // 变量在界:slot 5 / 槽数 6。
    let r = rpn(vec![Tok::Var(5), Tok::Const(1.0), Tok::Op(b'+')], 6);
    assert!(r.certified, "in-range var must certify");
    assert_eq!(r.slot_hi, 6);

    // 变量越界:摘证书(解码器另行拒绝,这里只看证书)。
    let r = rpn(vec![Tok::Var(5), Tok::Const(1.0), Tok::Op(b'+')], 5);
    assert!(!r.certified, "out-of-range var must not certify");

    // 下溢:二元 op 开头。
    let r = rpn(vec![Tok::Op(b'+')], 0);
    assert!(!r.certified, "underflow must not certify");

    // vec3 消费 3 个(arity() 表口径记 1——证书必须按弹栈数,否则误认证)。
    let r = rpn(vec![Tok::Fn(formula_native::Fn::Vec3)], 0);
    assert!(!r.certified, "vec3 on empty stack must not certify");
    let r = rpn(
        vec![Tok::Var(0), Tok::Var(0), Tok::Var(0), Tok::Fn(formula_native::Fn::Vec3)],
        1,
    );
    assert!(r.certified, "vec3 with three args must certify");

    // 未知函数 lenient 形态 `[min, 1, '(', 2]`:'(' 吃两项得 0,栈效应二元。
    let r = rpn(
        vec![Tok::Var(0), Tok::Const(1.0), Tok::Op(b'('), Tok::Const(2.0)],
        1,
    );
    assert!(r.certified, "lenient open-paren form must certify");

    // 深度边界:64 个 Const = 深 64 → 过;65 → 摘证书走 checked 兜底。
    let deep64: Vec<Tok> = (0..64).map(|_| Tok::Const(1.0)).collect();
    assert!(rpn(deep64.clone(), 0).certified, "depth 64 must certify");
    let mut deep65 = deep64;
    deep65.push(Tok::Const(1.0));
    assert!(!rpn(deep65, 0).certified, "depth 65 must not certify");

    // 二元运算的峰值在中段:Const×46 之后才做 op,峰值 46。
    let mut t: Vec<Tok> = (0..46).map(|_| Tok::Const(1.0)).collect();
    for _ in 0..45 {
        t.push(Tok::Op(b'+'));
    }
    assert!(rpn(t, 0).certified, "peak-depth 46 must certify");
}

// ───────────────── 双路逐位对拍 ─────────────────

/// 递归摘除全部证书 → 强制 checked 兜底路。 / strip every certificate to force
/// the checked fallback path.
fn decertify(prog: &mut Program) {
    fn strip_stmts(stmts: &mut [Stmt]) {
        for s in stmts.iter_mut() {
            match s {
                Stmt::Assign { rpn, .. } | Stmt::Expr(rpn) => rpn.certified = false,
                Stmt::While { cond, body, .. } => {
                    cond.certified = false;
                    strip_stmts(body);
                }
                Stmt::If { cond, body, els } => {
                    cond.certified = false;
                    strip_stmts(body);
                    strip_stmts(els);
                }
                Stmt::Repeat { body, .. } => strip_stmts(body),
                Stmt::Break | Stmt::Continue => {}
            }
        }
    }
    strip_stmts(&mut prog.body);
    for o in prog.outputs.iter_mut() {
        o.certified = false;
    }
}

/** 全值位对比(标量/vec3 两形态;as_scalar 会把 vec3 读成 0,比较太弱)。
Full-value bit comparison (both shapes; as_scalar would flatten vec3 to 0). */
fn v_bits(v: V) -> [u64; 4] {
    match v {
        V::S(d) => [d.to_bits(), 0, 0, 0],
        V::V3(a) => [a[0].to_bits(), a[1].to_bits(), a[2].to_bits(), 1],
    }
}

fn run_with(prog: &Program, inputs: &[V]) -> (Outcome, Vec<V>) {
    let mut regs = Vec::new();
    let mut loops = Vec::new();
    let mut out = vec![V::ZERO; prog.names.len()];
    let ex = Exec::new(prog, &mut regs, &mut loops, inputs, None, false, Budget::unlimited());
    let oc = ex.run(&mut out);
    (oc, out)
}

/// b4 形态的双路/认证门断言:
/// ① 原生 scalar_only —— 标量路恒 checked 单机,两证书态同值;
/// ② 强制 tagged full —— 认证流走 unchecked 机,值与标量路逐位一致(路由 parity);
/// ③ 未认证流进不了 unchecked 机 —— 认证门受控失败(b4 的兜底形态)。
/// b4-shape dual/gate assertions: (1) native scalar_only — the always-checked
/// scalar machine, identical values in both certificate states; (2) forced
/// tagged-full — certified streams run the unchecked machine bit-identical to
/// the scalar machine (routing parity); (3) uncertified streams never reach the
/// unchecked machine — the entry gate fails controlled (b4's fallback shape).
fn assert_dual_path(src: &str, inputs: &[V]) {
    let mut a = compile(src).expect("compile");
    let mut b = a.clone();
    decertify(&mut b);
    assert!(a.body.iter().any(certified_probe), "script should carry at least one certified rpn");

    // 形态一:原生 scalar_only(标量单态/向量双态,按探测分流)。
    check_same(&a, &b, inputs, "native");

    // 形态二:强制 tagged full 路(eval_full 双态的收口面——纯标量脚本也走)。
    let mut af = a.clone();
    af.scalar_only = false;
    let mut bf = b.clone();
    bf.scalar_only = false;
    check_same(&af, &bf, inputs, "full-tagged");
}

fn check_same(a: &Program, b: &Program, inputs: &[V], tag: &str) {
    let (oa, out_a) = run_with(a, inputs);
    let (ob, out_b) = run_with(b, inputs);
    assert!(matches!(oa, Outcome::Done), "{tag}: certified run must complete");
    assert!(matches!(ob, Outcome::Done), "{tag}: decertified run must complete (checked machine)");
    assert_eq!(out_a.len(), out_b.len());
    for (i, (x, y)) in out_a.iter().zip(out_b.iter()).enumerate() {
        assert_eq!(v_bits(*x), v_bits(*y), "{tag}: slot {i} diverged: unchecked vs checked");
    }
}

fn certified_probe(s: &Stmt) -> bool {
    match s {
        Stmt::Assign { rpn, .. } | Stmt::Expr(rpn) => rpn.certified,
        Stmt::While { cond, body, .. } => cond.certified || body.iter().any(certified_probe),
        Stmt::If { cond, body, els } => {
            cond.certified || body.iter().any(certified_probe) || els.iter().any(certified_probe)
        }
        Stmt::Repeat { body, .. } => body.iter().any(certified_probe),
        Stmt::Break | Stmt::Continue => false,
    }
}

#[test]
fn dual_path_scalar_script() {
    assert_dual_path(
        "i = 0\nacc = 0\nrepeat 500 {\n    acc = acc + i * 0.001\n    i = i + 1\n}",
        &[],
    );
}

#[test]
fn dual_path_vec3_script() {
    assert_dual_path(
        "p = vec3(0, 0, 0)\nv = vec3(1, 2, 3)\nrepeat 200 {\n    p = p + v * 0.01\n    v = v - vec3(0, 0.001, 0)\n}",
        &[],
    );
}

#[test]
fn dual_path_control_flow() {
    // if/else 走弹道真脚本同款内联花括号形态(跨行 `} else {` 不是 mini 前端
    // 受支持形态——编译器在收尾 else 处只认行内或独占行)。
    assert_dual_path(
        "i = 0\nacc = 0\nwhile (i < 50) {\n    if (i > 20) { acc = acc + i } else { acc = acc - 1 }\n    i = i + 1\n}",
        &[],
    );
}

#[test]
fn dual_path_lenient_forms() {
    // 未知函数 lenient 链 + 一元负号 + 除零 0 + 容差比较——checked 兜底与
    // unchecked 快路径逐位一致的边角面。
    assert_dual_path(
        "y = min(1, 2) + -(3) + (1 / 0) + (0.1 + 0.2 == 0.3)",
        &[],
    );
}

// ───────────────── eval_output 短 regs 受控降级 ─────────────────

#[test]
fn eval_output_short_regs_degrades_gracefully() {
    let prog = compile("y = x * 2 + 1").expect("compile");
    let rpn = match &prog.body[0] {
        Stmt::Assign { rpn, .. } => rpn,
        _ => unreachable!(),
    };
    assert!(rpn.certified);
    assert_eq!(rpn.slot_hi, 2, "y=slot0 先驻,x=slot1 → RHS slot_hi=2");

    // regs 够长 → 认证快路径,值与 x=3 一致(x 在槽位 1)。
    let regs = vec![V::ZERO, V::S(3.0)];
    let v = match eval_output(rpn, &regs, true, &Budget::unlimited()) {
        Ok(v) => v,
        Err(_) => panic!("certified eval must succeed"),
    };
    assert_eq!(v.to_bits(), 7.0f64.to_bits());

    // full 路(scalar_only=false)同值——路由 parity 的输出段形态。
    let vf = match eval_output(rpn, &regs, false, &Budget::unlimited()) {
        Ok(v) => v,
        Err(_) => panic!("certified full-path eval must succeed"),
    };
    assert_eq!(vf.to_bits(), 7.0f64.to_bits(), "full path must agree bit-exact");

    // regs 短于 slot_hi:标量路受控降级 checked 机(越界读 = Underflow 族);
    // full 路走认证门(同样 Underflow)——两路都绝不 UB/panic。
    // Short regs: the scalar path degrades to the checked machine (out-of-range
    // reads join the Underflow family); the full path hits the entry gate (same
    // Underflow) — never UB/panic on either.
    let short: Vec<V> = vec![];
    assert!(
        matches!(eval_output(rpn, &short, true, &Budget::unlimited()), Err(Signal::Underflow)),
        "short regs must degrade to a controlled error (scalar path)"
    );
    assert!(
        matches!(eval_output(rpn, &short, false, &Budget::unlimited()), Err(Signal::Underflow)),
        "short regs must fail controlled at the entry gate (full path)"
    );
}

// ───────────────── 对抗:槽位越界解码期拒绝 ─────────────────

fn tok_const(d: f64) -> [u8; 16] {
    let mut t = [0u8; 16];
    t[0] = 0;
    t[8..16].copy_from_slice(&d.to_bits().to_le_bytes());
    t
}

fn tok_var(s: u16) -> [u8; 16] {
    let mut t = [0u8; 16];
    t[0] = 1;
    t[8..10].copy_from_slice(&s.to_le_bytes());
    t
}

/// 最小合法程序字节形态:1 槽位、1 条 Assign、给定 RPN、无输出/引脚。
/// Minimal legitimate program byte shape: 1 slot, one Assign, the given RPN,
/// no outputs/pins.
fn build_program(assign_slot: u16, rpn_toks: &[[u8; 16]]) -> Vec<u8> {
    let mut b = Vec::new();
    b.extend_from_slice(b"CSCF");
    b.extend_from_slice(&3u32.to_le_bytes());
    b.extend_from_slice(&1u32.to_le_bytes()); // slot_count
    b.extend_from_slice(&1u16.to_le_bytes()); // name len
    b.push(b'a');
    b.extend_from_slice(&0u32.to_le_bytes()); // repeat_count
    b.extend_from_slice(&1u32.to_le_bytes()); // top stmt count
    b.push(0); // Assign
    b.extend_from_slice(&assign_slot.to_le_bytes());
    b.extend_from_slice(&(rpn_toks.len() as u32).to_le_bytes());
    for t in rpn_toks {
        b.extend_from_slice(t);
    }
    b.extend_from_slice(&0u32.to_le_bytes()); // outputs
    b.extend_from_slice(&0u32.to_le_bytes()); // pins
    b
}

#[test]
fn adversarial_var_slot_rejected_at_decode() {
    // 合法形态先自证字节构建器(Var 0 在界 → 解码通过)。
    let ok = build_program(0, &[tok_var(0), tok_const(1.0), {
        let mut t = [0u8; 16];
        t[0] = 2;
        t[8] = b'+';
        t
    }]);
    assert!(decode_program(&ok).is_ok(), "in-range builder sanity");

    // Var 5 越界(槽位表只有 1)→ BadSlot,受控拒绝,绝不 panic。
    let bad = build_program(0, &[tok_var(5), tok_const(1.0)]);
    assert!(matches!(decode_program(&bad), Err(DecodeError::BadSlot(5))));
}

#[test]
fn adversarial_assign_slot_rejected_at_decode() {
    // Assign 目标槽位 7 越界 → BadSlot(写寄存器面同口径)。
    let bad = build_program(7, &[tok_const(1.0)]);
    assert!(matches!(decode_program(&bad), Err(DecodeError::BadSlot(7))));
}

