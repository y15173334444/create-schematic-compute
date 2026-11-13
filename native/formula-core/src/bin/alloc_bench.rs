//! 分配率基准：与 bench/java/FormulaAllocBench.java 同脚本、同口径对拍。
//! 计数分配器统计测量窗口内的堆分配次数/字节——稳态预期为 **0**。
//!
//! Allocation-rate benchmark: same scripts and metrics as the Java bench.
//! A counting allocator reports heap allocations inside the measured window —
//! steady state is expected to be exactly zero.

use std::alloc::{GlobalAlloc, Layout, System};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::time::{Duration, Instant};

use formula_core::{compile, Budget, Exec, V};

static ALLOCS: AtomicU64 = AtomicU64::new(0);
static BYTES: AtomicU64 = AtomicU64::new(0);
static COUNTING: AtomicBool = AtomicBool::new(false);

struct Counting;

unsafe impl GlobalAlloc for Counting {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        if COUNTING.load(Ordering::Relaxed) {
            ALLOCS.fetch_add(1, Ordering::Relaxed);
            BYTES.fetch_add(layout.size() as u64, Ordering::Relaxed);
        }
        System.alloc(layout)
    }
    unsafe fn dealloc(&self, ptr: *mut u8, layout: Layout) {
        System.dealloc(ptr, layout)
    }
}

#[global_allocator]
static A: Counting = Counting;

struct Script {
    name: &'static str,
    src: &'static str,
    iters_per_exec: u64,
    input_x: f64,
}

/// 脚本级续行符用 `\\`（Rust 转义后落到脚本文本的是 `\`，与 Java bench 同源）。
const SCRIPTS: [Script; 3] = [
    Script {
        name: "scalarLoop",
        src: "i = 0\nacc = 0\nrepeat 100000 {\n    acc = acc + i * 0.001\n    i = i + 1\n}",
        iters_per_exec: 100_000,
        input_x: 0.0,
    },
    Script {
        name: "vec3Loop",
        src: "p = vec3(0, 0, 0)\nv = vec3(1, 2, 3)\nrepeat 100000 {\n    p = p + v * 0.01\n    v = v - vec3(0, 0.001, 0)\n}",
        iters_per_exec: 100_000,
        input_x: 0.0,
    },
    Script {
        name: "closedForm",
        src: "y = sin(x) * cos(2*x) + sqrt(abs(x - 0.5)) + log(1 + x*x) ^ 2 \\\n    + sin(x*3) * 0.5 + floor(x) + min(x, 1) + max(x, 2)",
        iters_per_exec: 1,
        input_x: 0.5,
    },
];

fn run_for(script: &Script, prog: &formula_core::Program, ms: u64) -> (u64, Duration) {
    let inputs = vec![V::S(script.input_x)];
    // 桥接形态：缓冲跨 exec 复用（首次分配后稳态零分配）。
    let mut regs = Vec::new();
    let mut loops = Vec::new();
    let mut out = [V::ZERO; 8];
    let end = Instant::now() + Duration::from_millis(ms);
    let mut iters = 0u64;
    let mut ns = Duration::ZERO;
    while Instant::now() < end {
        let t0 = Instant::now();
        let ex = Exec::new(prog, &mut regs, &mut loops, &inputs, None, Budget::unlimited());
        // 求值结果丢弃（分配率测量不关心终值；对拍在测试里做）
        let _ = ex.run(&mut out);
        ns += t0.elapsed();
        iters += script.iters_per_exec;
    }
    (iters, ns)
}

fn bench_one(tag: &str, script: &Script, prog: &formula_core::Program) {
    run_for(script, prog, 500); // 触页/稳态预热
    COUNTING.store(true, Ordering::Relaxed);
    let (iters, ns) = run_for(script, prog, 2000);
    COUNTING.store(false, Ordering::Relaxed);
    let allocs = ALLOCS.swap(0, Ordering::Relaxed);
    let bytes = BYTES.swap(0, Ordering::Relaxed);

    let s = ns.as_secs_f64();
    println!(
        "{:<10} {:<5} iter/s={:>12.0}  ns/iter={:>8.1}  alloc/s={:>8.1} MB  alloc/iter={:>6.1} B  ({} allocs, {} B in window)",
        script.name,
        tag,
        iters as f64 / s,
        ns.as_nanos() as f64 / iters as f64,
        bytes as f64 / s / 1048576.0,
        bytes as f64 / iters as f64,
        allocs,
        bytes
    );
}

fn main() {
    for script in &SCRIPTS {
        let prog = match compile(script.src) {
            Ok(p) => p,
            Err(e) => {
                println!("{:<10} COMPILE FAILED: {}", script.name, e);
                continue;
            }
        };
        // 双跑对量化：fast = 探测结果（纯标量程序）；full = 同一 Program 克隆翻转。
        // vec3Loop 本身就非纯标量，fast/full 同路，只跑一次。
        if prog.scalar_only {
            bench_one("fast", script, &prog);
            let mut full = prog.clone();
            full.scalar_only = false;
            bench_one("full", script, &full);
        } else {
            bench_one("full", script, &prog);
        }
    }
}
