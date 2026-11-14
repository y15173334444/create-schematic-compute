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
    let ex = Exec::new(&prog, &mut regs, &mut loops, &slots, None, false, Budget::unlimited());
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
            let ex = Exec::new(prog, &mut regs, &mut loops, slots, taken, false, budget);
            match ex.run(&mut out) {
                Outcome::Done => return out,
                Outcome::Suspended(c) => resume = Some(c),
                // deadline 未接线（None）→ 不可能走到；穷举臂兜底。
                Outcome::DeadlineSuspended(_) => panic!("deadline unwired — unreachable"),
                Outcome::Error => panic!("no error path in the ballistic script"),
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

// ───────────────────────── 防御对抗（R2.5）：字节码是攻击面 ─────────────────────────
//
// 威胁模型：字节码/挂起态来自可被篡改的边界（存档改写、反射直调 native、未来
// 的网络同步）——解码必须**任何输入永不 panic**（panic 跨 FFI = abort），失败一律
// 受控 Err。全部变异用确定性 PRNG（无外部 fuzz 依赖、失败可复现）。

/// xorshift64*：确定性、无依赖。 / deterministic dependency-free PRNG.
struct XorShift(u64);

impl XorShift {
    fn next(&mut self) -> u64 {
        let mut x = self.0;
        x ^= x >> 12;
        x ^= x << 25;
        x ^= x >> 27;
        self.0 = x;
        x.wrapping_mul(0x2545F4914F6CDD1D)
    }
    fn below(&mut self, n: usize) -> usize {
        (self.next() % n as u64) as usize
    }
}

/// 变异 fuzz：合法字节码 + 随机字节扰动（单/多点写、截断、头部破坏），解码永不
/// panic（Err 或 Ok 皆可——Ok 只会出现在扰动落在无害区时）。
#[test]
fn adversarial_mutation_fuzz_decode_never_panics() {
    let (bin, _) = read_fixture("ballistic");
    let (small, _) = read_fixture("scalarLoop");
    let mut rng = XorShift(0xC5C_C5C5_C5C5_C5C5);
    for round in 0..10_000u32 {
        for src in [bin.as_slice(), small.as_slice()] {
            let mut bytes = src.to_vec();
            match rng.below(4) {
                0 => {
                    // 单字节翻转 / single-byte flip
                    if !bytes.is_empty() {
                        let i = rng.below(bytes.len());
                        bytes[i] = (bytes[i] as u64 ^ rng.next()).clamp(0, 255) as u8;
                    }
                }
                1 => {
                    // 1..=16 字节随机覆盖 / short random overwrite
                    let i = rng.below(bytes.len());
                    let n = 1 + rng.below(16.min(bytes.len() - i));
                    for b in &mut bytes[i..i + n] {
                        *b = rng.next() as u8;
                    }
                }
                2 => {
                    // 截断 / truncate
                    let i = rng.below(bytes.len());
                    bytes.truncate(i);
                }
                _ => {
                    // 尾部追加垃圾 / append garbage
                    for _ in 0..rng.below(64) {
                        bytes.push(rng.next() as u8);
                    }
                }
            }
            // 不 panic 即通过（结果无所谓）。 / must not panic — result is irrelevant.
            let _ = decode_program(&bytes);
        }
        let _ = round;
    }
}

/// 挂起态变异 fuzz：carrier_from_bytes 永不 panic。
#[test]
fn adversarial_mutation_fuzz_carrier_never_panics() {
    // 先产一份合法 carrier（scalarLoop 挂起 1µs 即得）。
    let prog = formula_native::compile("i = 0\nacc = 0\nrepeat 100000 {\n    acc = acc + i * 0.001\n    i = i + 1\n}")
        .expect("compile");
    let mut regs = Vec::new();
    let mut loops = Vec::new();
    let mut out = vec![V::ZERO; prog.names.len()];
    let ex = Exec::new(&prog, &mut regs, &mut loops, &[], None, false, Budget::slice(Duration::from_nanos(1)));
    let carrier = match ex.run(&mut out) {
        Outcome::Suspended(c) => carrier_to_bytes(&c),
        _ => panic!("must suspend under 1ns slice"),
    };
    assert!(carrier.len() > 24, "carrier must carry a real payload");
    let mut rng = XorShift(0xB0B_B0B0_B0B0_B0B0);
    for _ in 0..10_000u32 {
        let mut bytes = carrier.clone();
        match rng.below(3) {
            0 => {
                let i = rng.below(bytes.len());
                bytes[i] ^= rng.next() as u8;
            }
            1 => {
                bytes.truncate(rng.below(bytes.len()));
            }
            _ => {
                for _ in 0..rng.below(32) {
                    bytes.push(rng.next() as u8);
                }
            }
        }
        let _ = carrier_from_bytes(&bytes);
    }
}

/// 全前缀扫描：合法程序字节流的每个截断点解码受控失败（永不 panic）。
#[test]
fn adversarial_all_prefixes_terminate_cleanly() {
    let (bin, _) = read_fixture("scalarLoop");
    for cut in 0..bin.len() {
        let _ = decode_program(&bin[..cut]);
    }
}

/// 深嵌套构造：语句嵌套超帽（>128）= TooDeep 受控失败—— crafted 字节码打不出
/// Rust 栈溢出（JNI 调用内的 abort 即进程崩溃，这是本批堵住的洞）。
#[test]
fn adversarial_deep_nesting_is_rejected_not_crashed() {
    // 手工构造 400 层嵌套 Repeat（合法编码形态，只是深度越界）：
    // Repeat{ id, count=1, body=[ 同构嵌套 ... , Assign ] }，前序 id 递增。
    let mut bytes: Vec<u8> = Vec::new();
    bytes.extend_from_slice(b"CSCF");
    bytes.extend_from_slice(&3u32.to_le_bytes()); // v3（TooDeep 在语句段命中,无需尾段）
    bytes.extend_from_slice(&0u32.to_le_bytes()); // 0 slots
    bytes.extend_from_slice(&0u32.to_le_bytes()); // 0 repeats（表与语句流的 id 各自独立——本构造用语句内 id 即可）
    bytes.extend_from_slice(&1u32.to_le_bytes()); // 1 top stmt
    const DEPTH: u32 = 400;
    for i in 0..DEPTH {
        bytes.push(2); // Repeat
        bytes.extend_from_slice(&(i as u16).to_le_bytes());
        bytes.extend_from_slice(&1u64.to_le_bytes()); // count=1
        bytes.extend_from_slice(&1u32.to_le_bytes()); // body 1 stmt
    }
    // 叶子：Assign slot0 = const 1
    bytes.push(0); // Assign
    bytes.extend_from_slice(&0u16.to_le_bytes());
    bytes.extend_from_slice(&1u32.to_le_bytes()); // rpn 1 token
    bytes.push(0); // Const
    bytes.extend_from_slice(&[0u8; 7]);
    bytes.extend_from_slice(&1.0f64.to_bits().to_le_bytes());
    match decode_program(&bytes) {
        Err(formula_native::bridge::DecodeError::TooDeep) => {} // 受控失败 = 修复生效
        Err(other) => panic!("expected TooDeep, got {other:?}"),
        Ok(_) => panic!("deep nesting must be rejected"),
    }
    // 逐层削叶：合法深度（≤128）必须通过——帽不误伤正常程序。
    let mut ok_bytes = bytes.clone();
    // 把 400 层砍到 128 层：截断到前 128 层壳 + 叶子 + 收尾 count 调整复杂，
    // 这里直接重造一份 100 层的合法程序验证帽不误伤。
    let mut shallow: Vec<u8> = Vec::new();
    shallow.extend_from_slice(b"CSCF");
    shallow.extend_from_slice(&3u32.to_le_bytes());
    shallow.extend_from_slice(&1u32.to_le_bytes()); // 1 slot（叶子 Assign slot0 的名字省略不可——补全）
    // 槽位表要真实：1 个名字 "v"
    shallow.truncate(8);
    shallow.extend_from_slice(&1u32.to_le_bytes()); // slots=1
    let nm = b"v";
    shallow.extend_from_slice(&(nm.len() as u16).to_le_bytes());
    shallow.extend_from_slice(nm);
    shallow.extend_from_slice(&0u32.to_le_bytes()); // 0 repeats
    shallow.extend_from_slice(&1u32.to_le_bytes()); // 1 top stmt
    for i in 0..100u32 {
        shallow.push(2);
        shallow.extend_from_slice(&(i as u16).to_le_bytes());
        shallow.extend_from_slice(&1u64.to_le_bytes());
        shallow.extend_from_slice(&1u32.to_le_bytes());
    }
    shallow.push(0);
    shallow.extend_from_slice(&0u16.to_le_bytes());
    shallow.extend_from_slice(&1u32.to_le_bytes());
    shallow.push(0);
    shallow.extend_from_slice(&[0u8; 7]);
    shallow.extend_from_slice(&1.0f64.to_bits().to_le_bytes());
    shallow.extend_from_slice(&0u32.to_le_bytes()); // 输出段：0 条
    shallow.extend_from_slice(&0u32.to_le_bytes()); // v3 引脚槽位段：0 钉
    let prog = decode_program(&shallow).expect("100-deep nesting must decode (cap is 128)");
    assert_eq!(prog.names, vec!["v"]);
}
