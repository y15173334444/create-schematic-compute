//! R1c/R1d 桥对拍：Java FormulaTokEncoder 产出的 .tokbin 夹具 → bridge::decode_program
//! → 求值 → 与 .golden.txt（StrictMath 链终值，hex 位模式）逐位比对。
//! carrier 序列化：挂起态 to_bytes/from_bytes 往返 + 续算等价。
//!
//! R1c/R1d bridge cross-check: decode the Java-encoded fixtures, evaluate, and
//! compare finals bit-exact against the StrictMath-chain goldens; carrier
//! serialization roundtrip must preserve suspend/resume equivalence.

use std::time::Duration;

use formula_native::bridge::{carrier_from_bytes, carrier_to_bytes, decode_program, DecodeError};
use formula_native::{Budget, Exec, Outcome, V};

/// golden 文件形态：`# slot table` 段（name=十进制槽位）→ `# finals` 段
/// （name=hex 位模式）。按段注释切换解析。
struct Golden {
    slot_table: Vec<(String, usize)>,
    finals: Vec<(String, f64)>,
}

fn parse_golden(text: &str) -> Golden {
    let mut slot_table = Vec::new();
    let mut finals = Vec::new();
    let mut in_finals = false;
    for line in text.lines() {
        if line.starts_with('#') {
            if line.contains("finals") {
                in_finals = true;
            }
            continue;
        }
        if line.trim().is_empty() {
            continue;
        }
        let (k, v) = line.split_once('=').expect("golden line k=v");
        if in_finals {
            let bits = u64::from_str_radix(v, 16).expect("hex bits");
            finals.push((k.to_string(), f64::from_bits(bits)));
        } else {
            slot_table.push((k.to_string(), v.parse().expect("decimal slot index")));
        }
    }
    Golden { slot_table, finals }
}

fn read_fixture(name: &str) -> (Vec<u8>, Golden) {
    let dir = format!("{}/native/formula-core/tests/fixtures", env_fix_root());
    let bin = std::fs::read(format!("{dir}/{name}.tokbin"))
        .unwrap_or_else(|e| panic!("fixture {name}.tokbin: {e}"));
    let golden = std::fs::read_to_string(format!("{dir}/{name}.golden.txt"))
        .unwrap_or_else(|e| panic!("golden {name}: {e}"));
    (bin, parse_golden(&golden))
}

/// 夹具根：cargo test cwd = native/formula-core，仓库根在其上两级。
fn env_fix_root() -> &'static str {
    "../.."
}

fn run_program(bin: &[u8], inputs: &[(&str, f64)]) -> Vec<V> {
    let prog = decode_program(bin).expect("decode");
    let mut slots = vec![V::ZERO; prog.names.len()];
    for (name, v) in inputs {
        let i = prog.names.iter().position(|n| n == name).unwrap_or_else(|| {
            panic!("input slot {name} missing; decoded names = {:?}", prog.names)
        });
        slots[i] = V::S(*v);
    }
    let mut regs = Vec::new();
    let mut loops = Vec::new();
    let mut out = vec![V::ZERO; prog.names.len()];
    let ex = Exec::new(&prog, &mut regs, &mut loops, &slots, None, Budget::unlimited());
    match ex.run(&mut out) {
        Outcome::Done => out,
        _ => panic!("fixture program must complete unbudgeted"),
    }
}

/// 四个夹具（含弹道真脚本）解码 + 求值 + 终值与 Java StrictMath 链逐位一致。
#[test]
fn bridge_decode_eval_parity() {
    // 弹道：6 输入按名映射（槽位序 = 源码首现序 ≠ 引脚序）
    let (bin, golden) = read_fixture("ballistic");
    let prog = decode_program(&bin).expect("decode ballistic");
    // 夹具的输入值取 slot table 旁证 + golden 无输入钉终值——固定用 500 格中程输入
    let got = run_program(
        &bin,
        &[("mx", 0.0), ("my", 80.0), ("mz", 0.0), ("tx", 500.0), ("ty", 70.0), ("tz", -100.0)],
    );
    for (name, want) in &golden.finals {
        let i = prog.names.iter().position(|n| n == name).unwrap_or_else(|| panic!("slot {name}"));
        assert_eq!(got[i].as_scalar(), *want, "ballistic {name} bit-exact via bridge");
    }

    for (name, inputs) in [
        ("scalarLoop", Vec::new()),
        ("vec3Loop", Vec::new()),
        ("closedForm", vec![("x", 0.5)]),
    ] {
        let (bin, golden) = read_fixture(name);
        let prog = decode_program(&bin).expect("decode");
        let got = run_program(&bin, &inputs);
        for (fname, want) in &golden.finals {
            let i = prog.names.iter().position(|n| n == fname).unwrap_or_else(|| panic!("slot {fname}"));
            assert_eq!(got[i].as_scalar(), *want, "{name} {fname} bit-exact via bridge");
        }
    }
}

/// 解码器健壮性：magic/版本/截断的受控失败（绝不 panic）。
#[test]
fn bridge_decode_rejects_garbage() {
    let (bin, _) = read_fixture("scalarLoop");
    assert!(matches!(decode_program(&bin[1..]), Err(DecodeError::BadMagic)));
    assert!(matches!(decode_program(&bin[..bin.len() - 1]), Err(DecodeError::Truncated)));
    let mut bad_version = bin.clone();
    bad_version[4] = 9;
    assert!(matches!(decode_program(&bad_version), Err(DecodeError::BadVersion(9))));
}

/// R1d：carrier 序列化往返 + 续算等价（弹道真脚本，2ms slice 反复挂起，
/// 每次 carrier 走字节序列化跨界——与 R3 的 JNI 形态同构）。
#[test]
fn carrier_bytes_roundtrip_resume_equivalence() {
    let src = std::fs::read_to_string("../../docs/examples/ballistic_solver.formula")
        .expect("ballistic source (cwd = native/formula-core)");
    let prog = formula_native::compile(&src).unwrap();
    let mut slots = vec![V::ZERO; prog.names.len()];
    for (name, v) in
        [("mx", 0.0), ("my", 80.0), ("mz", 0.0), ("tx", 500.0), ("ty", 70.0), ("tz", -100.0)]
    {
        let i = prog.names.iter().position(|n| n == name).unwrap();
        slots[i] = V::S(v);
    }

    fn run(prog: &formula_native::Program, slots: &[V], slice: Option<Duration>, serialize: bool) -> Vec<V> {
        let mut regs = Vec::new();
        let mut loops = Vec::new();
        let mut out = vec![V::ZERO; prog.names.len()];
        let mut resume: Option<formula_native::Carrier> = None;
        let mut rounds = 0u32;
        loop {
            rounds += 1;
            assert!(rounds < 200_000, "not converging");
            let budget = match slice {
                Some(d) => Budget::slice(d),
                None => Budget::unlimited(),
            };
            let taken = resume.take();
            // 序列化形态：挂起态先转字节再回灌（模拟 JNI 跨界）
            let taken = taken.map(|c| {
                if serialize {
                    let bytes = carrier_to_bytes(&c);
                    carrier_from_bytes(&bytes).expect("carrier roundtrip")
                } else {
                    c
                }
            });
            let ex = Exec::new(prog, &mut regs, &mut loops, slots, taken, budget);
            match ex.run(&mut out) {
                Outcome::Done => return out,
                Outcome::Suspended(c) => resume = Some(c),
                Outcome::Shed => panic!("must not shed"),
            }
        }
    }

    let base = run(&prog, &slots, None, false);
    let sliced = run(&prog, &slots, Some(Duration::from_millis(2)), true);
    for i in 0..prog.names.len() {
        assert_eq!(
            base[i],
            sliced[i],
            "slot {} ({}) diverged via serialized carrier resume",
            i,
            prog.names[i]
        );
    }
}
