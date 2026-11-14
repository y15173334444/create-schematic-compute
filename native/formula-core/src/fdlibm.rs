//! fdlibm 5.3 的 sin/cos/log/log10 移植——逐行对照 Zulu 21 的
//! `java.base/java/lang/FdLibm.java`（Java StrictMath 的权威实现，C→Java 的
//! 忠实翻译），再忠实翻译到 Rust。目的：与 Java StrictMath **逐位一致**——
//! libm crate 是 musl 血统，与 fdlibm 5.3 在 sin/cos/log10 有 ~1% 参数差 1 ulp，
//! 经模拟链（弹道 46×400 迭代）放大后破坏全逐位对拍（§5.3/§3.3）。
//! 验证网：tests/trig_anchor.rs（14300 样本位模式夹具）。
//!
//! 其余函数（asin/acos/atan2/exp/sinh/cosh/pow/tan）在夹具域内与 libm crate
//! 已逐位一致，继续用 libm crate；sqrt 是 IEEE 精确舍入（硬件 sqrtsd = Java）。
//! 十六进制浮点常量一律取 fdlibm 注释中的全精度十进制（Rust 正确舍入解析 =
//! 同一双精度值）。
//!
//! Rust port of Java StrictMath's FdLibm sin/cos/log/log10 — the authoritative
//! fdlibm 5.3 translation — for bit-exact cross-end parity.

/// Java `__HI`：双精度高位字（带符号 int）。
#[inline]
fn hi(x: f64) -> i32 {
    (x.to_bits() >> 32) as u32 as i32
}

/// Java `__LO`：低位字。
#[inline]
fn lo(x: f64) -> i32 {
    x.to_bits() as u32 as i32
}

/// Java `__HI(x, v)`：替换高位字。
#[inline]
fn with_hi(x: f64, v: i32) -> f64 {
    f64::from_bits(((v as u32 as u64) << 32) | (x.to_bits() & 0xFFFF_FFFF))
}

/// Java `__LO(x, v)`：替换低位字。
#[inline]
fn with_lo(x: f64, v: i32) -> f64 {
    f64::from_bits((x.to_bits() & 0xFFFF_FFFF_0000_0000) | (v as u32 as u64))
}

/// Java `__HI_LO(hi, lo)`：以 (高位字, 低位字) 组装双精度。
#[inline]
fn from_hi_lo(hi: i32, lo: i32) -> f64 {
    f64::from_bits(((hi as u32 as u64) << 32) | (lo as u32 as u64))
}

const EXP_BITS: i32 = 0x7ff0_0000;
const EXP_SIGNIF_BITS: i32 = 0x7fff_ffff;
const SIGN_BIT: u32 = 0x8000_0000;
const TWO24: f64 = 16_777_216.0; // 0x1.0p24
const TWO54: f64 = 18_014_398_509_481_984.0; // 0x1.0p54

// ─────────────────────────────────────────────────────────────────────────────
// sin / cos（__kernel_sin / __kernel_cos / __ieee754_rem_pio2）
// ─────────────────────────────────────────────────────────────────────────────

const S1: f64 = -1.66666666666666324348e-01;
const S2: f64 = 8.33333333332248946124e-03;
const S3: f64 = -1.98412698298579493134e-04;
const S4: f64 = 2.75573137070700676789e-06;
const S5: f64 = -2.50507602534068634195e-08;
const S6: f64 = 1.58969099521155010221e-10;

const C1: f64 = 4.16666666666666019037e-02;
const C2: f64 = -1.38888888888741095749e-03;
const C3: f64 = 2.48015872894767294178e-05;
const C4: f64 = -2.75573143513906633035e-07;
const C5: f64 = 2.08757232129817482790e-09;
const C6: f64 = -1.13596475577881948265e-11;

/// fdlibm __kernel_sin：[-π/4, π/4] 上的核函数。x 有界 ≈π/4，y = x 的尾部，
/// iy=0 表示 y 视为 0。
fn kernel_sin(x: f64, y: f64, iy: i32) -> f64 {
    let ix = hi(x) & EXP_SIGNIF_BITS;
    if ix < 0x3e40_0000 {
        // |x| < 2**-27
        if (x as i32) == 0 {
            return x; // generate inexact
        }
    }
    let z = x * x;
    let v = z * x;
    let r = S2 + z * (S3 + z * (S4 + z * (S5 + z * S6)));
    if iy == 0 {
        x + v * (S1 + z * r)
    } else {
        x - ((z * (0.5 * y - v * r) - y) - v * S1)
    }
}

/// fdlibm __kernel_cos：[-π/4, π/4] 上的核函数。
fn kernel_cos(x: f64, y: f64) -> f64 {
    let ix = hi(x) & EXP_SIGNIF_BITS;
    if ix < 0x3e40_0000 {
        if (x as i32) == 0 {
            return 1.0; // generate inexact
        }
    }
    let z = x * x;
    let r = z * (C1 + z * (C2 + z * (C3 + z * (C4 + z * (C5 + z * C6)))));
    if ix < 0x3FD3_3333 {
        // |x| < 0.3
        1.0 - (0.5 * z - (z * r - x * y))
    } else {
        let qx = if ix > 0x3fe9_0000 {
            // x > 0.78125
            0.28125
        } else {
            from_hi_lo(ix - 0x0020_0000, 0)
        };
        let hz = 0.5 * z - qx;
        let a = 1.0 - qx;
        a - (hz - (z * r - x * y))
    }
}

/// fdlibm sin（Java FdLibm.Sin.compute）。
pub fn sin(x: f64) -> f64 {
    let mut y = [0.0f64; 2];
    let ix = hi(x) & EXP_SIGNIF_BITS;
    if ix <= 0x3fe9_21fb {
        // |x| ~< π/4
        return kernel_sin(x, 0.0, 0);
    }
    if ix >= EXP_BITS {
        return x - x; // sin(Inf/NaN) = NaN
    }
    let n = rem_pio2(x, &mut y);
    match n & 3 {
        0 => kernel_sin(y[0], y[1], 1),
        1 => kernel_cos(y[0], y[1]),
        2 => -kernel_sin(y[0], y[1], 1),
        _ => -kernel_cos(y[0], y[1]),
    }
}

/// fdlibm cos（Java FdLibm.Cos.compute）。
pub fn cos(x: f64) -> f64 {
    let mut y = [0.0f64; 2];
    let ix = hi(x) & EXP_SIGNIF_BITS;
    if ix <= 0x3fe9_21fb {
        return kernel_cos(x, 0.0);
    }
    if ix >= EXP_BITS {
        return x - x;
    }
    let n = rem_pio2(x, &mut y);
    match n & 3 {
        0 => kernel_cos(y[0], y[1]),
        1 => -kernel_sin(y[0], y[1], 1),
        2 => -kernel_cos(y[0], y[1]),
        _ => kernel_sin(y[0], y[1], 1),
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// __ieee754_rem_pio2（参数归约）
// ─────────────────────────────────────────────────────────────────────────────

/// 2/π 的 24 位分段表（fdlibm 原表逐字）。
const TWO_OVER_PI: [i32; 66] = [
    0xA2F983, 0x6E4E44, 0x1529FC, 0x2757D1, 0xF534DD, 0xC0DB62, 0x95993C, 0x439041, 0xFE5163,
    0xABDEBB, 0xC561B7, 0x246E3A, 0x424DD2, 0xE00649, 0x2EEA09, 0xD1921C, 0xFE1DEB, 0x1CB129,
    0xA73EE8, 0x8235F5, 0x2EBB44, 0x84E99C, 0x7026B4, 0x5F7E41, 0x3991D6, 0x398353, 0x39F49C,
    0x845F8B, 0xBDF928, 0x3B1FF8, 0x97FFDE, 0x05980F, 0xEF2F11, 0x8B5A0A, 0x6D1F6D, 0x367ECF,
    0x27CB09, 0xB74F46, 0x3F669E, 0x5FEA2D, 0x7527BA, 0xC7EBE5, 0xF17B3D, 0x0739F7, 0x8A5292,
    0xEA6BFB, 0x5FB11F, 0x8D5D08, 0x560330, 0x46FC7B, 0x6BABF0, 0xCFBC20, 0x9AF436, 0x1DA9E3,
    0x91615E, 0xE61B08, 0x659985, 0x5F14A0, 0x68408D, 0xFFD880, 0x4D7327, 0x310606, 0x1556CA,
    0x73A8C9, 0x60E27B, 0xC08C6B,
];

const NPIO2_HW: [i32; 32] = [
    0x3FF921FB, 0x400921FB, 0x4012D97C, 0x401921FB, 0x401F6A7A, 0x4022D97C, 0x4025FDBB,
    0x402921FB, 0x402C463A, 0x402F6A7A, 0x4031475C, 0x4032D97C, 0x40346B9C, 0x4035FDBB,
    0x40378FDB, 0x403921FB, 0x403AB41B, 0x403C463A, 0x403DD85A, 0x403F6A7A, 0x40407E4C,
    0x4041475C, 0x4042106C, 0x4042D97C, 0x4043A28C, 0x40446B9C, 0x404534AC, 0x4045FDBB,
    0x4046C6CB, 0x40478FDB, 0x404858EB, 0x404921FB,
];

const INVPIO2: f64 = 6.36619772367581382433e-01; // 0x1.45f306dc9c883p-1
const PIO2_1: f64 = 1.57079632673412561417e+00; // 0x1.921fb544p0
const PIO2_1T: f64 = 6.07710050650619224932e-11; // 0x1.0b4611a626331p-34
const PIO2_2: f64 = 6.07710050630396597660e-11; // 0x1.0b4611a6p-34
const PIO2_2T: f64 = 2.02226624879595063154e-21; // 0x1.3198a2e037073p-69
const PIO2_3: f64 = 2.02226624871116645580e-21; // 0x1.3198a2ep-69
const PIO2_3T: f64 = 8.47842766036889956997e-32; // 0x1.b839a252049c1p-104

/// fdlibm __ieee754_rem_pio2：返回 n，y[0]+y[1] = x - n·π/2，|y| < π/2。
fn rem_pio2(x: f64, y: &mut [f64; 2]) -> i32 {
    let hx = hi(x);
    let ix = hx & EXP_SIGNIF_BITS;
    if ix <= 0x3fe9_21fb {
        // |x| ~<= π/4，无需归约
        y[0] = x;
        y[1] = 0.0;
        return 0;
    }
    if ix < 0x4002_d97c {
        // |x| < 3π/4，n=±1 特例
        if hx > 0 {
            let mut z = x - PIO2_1;
            if ix != 0x3ff9_21fb {
                // 33+53 位 π 足够
                y[0] = z - PIO2_1T;
                y[1] = (z - y[0]) - PIO2_1T;
            } else {
                // 近 π/2，用 33+33+53 位 π
                z -= PIO2_2;
                y[0] = z - PIO2_2T;
                y[1] = (z - y[0]) - PIO2_2T;
            }
            return 1;
        } else {
            let mut z = x + PIO2_1;
            if ix != 0x3ff9_21fb {
                y[0] = z + PIO2_1T;
                y[1] = (z - y[0]) + PIO2_1T;
            } else {
                z += PIO2_2;
                y[0] = z + PIO2_2T;
                y[1] = (z - y[0]) + PIO2_2T;
            }
            return -1;
        }
    }
    if ix <= 0x4139_21fb {
        // |x| ~<= 2^19·(π/2)，中等大小
        let mut t = x.abs();
        let n = (t * INVPIO2 + 0.5) as i32;
        let f_n = n as f64;
        let mut r = t - f_n * PIO2_1;
        let mut w = f_n * PIO2_1T; // 1 轮精度 85 bit
        if n < 32 && ix != NPIO2_HW[(n - 1) as usize] {
            y[0] = r - w; // 快速路径无相消
        } else {
            let j = ix >> 20;
            y[0] = r - w;
            let mut i = j - ((hi(y[0]) >> 20) & 0x7ff);
            if i > 16 {
                // 需要第 2 轮，精度 118 bit
                t = r;
                w = f_n * PIO2_2;
                r = t - w;
                w = f_n * PIO2_2T - ((t - r) - w);
                y[0] = r - w;
                i = j - ((hi(y[0]) >> 20) & 0x7ff);
                if i > 49 {
                    // 需要第 3 轮，151 bit 累计（覆盖一切情形）
                    t = r;
                    w = f_n * PIO2_3;
                    r = t - w;
                    w = f_n * PIO2_3T - ((t - r) - w);
                    y[0] = r - w;
                }
            }
        }
        y[1] = (r - y[0]) - w;
        if hx < 0 {
            y[0] = -y[0];
            y[1] = -y[1];
            return -n;
        }
        return n;
    }
    // 其余（大参数）
    if ix >= EXP_BITS {
        // x 是 Inf/NaN
        y[0] = x - x;
        y[1] = x - x;
        return 0;
    }
    // z = scalbn(|x|, ilogb(x)-23)
    let mut z = with_lo(x, lo(x));
    let e0 = (ix >> 20) - 1046; // e0 = ilogb(z) - 23
    z = with_hi(z, ix - (e0 << 20));
    let mut tx = [0.0f64; 3];
    for item in tx.iter_mut().take(2) {
        *item = (z as i32) as f64;
        z = (z - *item) * TWO24;
    }
    tx[2] = z;
    let mut nx = 3usize;
    while tx[nx - 1] == 0.0 {
        nx -= 1; // 跳过零项
    }
    let n = kernel_rem_pio2(&tx, y, e0, nx as i32, 2, &TWO_OVER_PI);
    if hx < 0 {
        y[0] = -y[0];
        y[1] = -y[1];
        return -n;
    }
    n
}

// ─────────────────────────────────────────────────────────────────────────────
// __kernel_rem_pio2（大参数归约核）
// ─────────────────────────────────────────────────────────────────────────────

const INIT_JK: [i32; 4] = [2, 3, 4, 6];

const PIO2: [f64; 8] = [
    1.57079625129699707031e+00, // 0x1.921fb4p0
    7.54978941586159635335e-08, // 0x1.4442dp-24
    5.39030252995776476554e-15, // 0x1.846988p-48
    3.28200341580791294123e-22, // 0x1.8cc516p-72
    1.27065575308067607349e-29, // 0x1.01b838p-96
    1.22933308981111328932e-36, // 0x1.a25204p-120
    2.73370053816464559624e-44, // 0x1.382228p-145
    2.16741683877804819444e-51, // 0x1.9f31dp-169
];

const TWON24: f64 = 5.96046447753906250000e-08; // 0x1.0p-24

/// fdlibm __kernel_rem_pio2：y = x - n·π/2（|y| < π/2），x 全为正，返回 n & 7。
fn kernel_rem_pio2(x: &[f64], y: &mut [f64; 2], e0: i32, nx: i32, prec: i32, ipio2: &[i32]) -> i32 {
    let mut iq = [0i32; 20];
    let mut f = [0.0f64; 20];
    let mut fq = [0.0f64; 20];
    let mut q = [0.0f64; 20];

    let jk = INIT_JK[prec as usize];
    let jp = jk;
    let jx = nx - 1;
    let mut jv = (e0 - 3) / 24;
    if jv < 0 {
        jv = 0;
    }
    let mut q0 = e0 - 24 * (jv + 1);

    // f[0..=jx+jk]：f[jx+jk] = ipio2[jv+jk]
    let m = jx + jk;
    let mut j = jv - jx;
    for i in 0..=m {
        f[i as usize] = if j < 0 { 0.0 } else { ipio2[j as usize] as f64 };
        j += 1;
    }

    // q[0..=jk]
    for i in 0..=jk {
        let mut fw = 0.0f64;
        for jj in 0..=jx {
            fw += x[jj as usize] * f[(jx + i - jj) as usize];
        }
        q[i as usize] = fw;
    }

    let mut jz = jk;
    // (n, z, ih)：z 与 ih 在 Java 版循环后继续使用
    let (n, z, ih) = 'recompute: loop {
        // q[] 逆序蒸馏进 iq[]
        let mut z = q[jz as usize];
        let mut ii = 0i32;
        let mut jj = jz;
        while jj > 0 {
            let fw = (TWON24 * z) as i32 as f64;
            iq[ii as usize] = (z - TWO24 * fw) as i32;
            z = q[(jj - 1) as usize] + fw;
            ii += 1;
            jj -= 1;
        }

        // 计算 n
        let mut z = libm::scalbn(z, q0); // z 的真值
        z -= 8.0 * (z * 0.125).floor(); // 去掉 ≥8 的整数
        let mut n = z as i32;
        z -= n as f64;
        let mut ih = 0i32;
        if q0 > 0 {
            // 需要 iq[jz-1] 定 n
            let i = iq[(jz - 1) as usize] >> (24 - q0);
            n += i;
            iq[(jz - 1) as usize] -= i << (24 - q0);
            ih = iq[(jz - 1) as usize] >> (23 - q0);
        } else if q0 == 0 {
            ih = iq[(jz - 1) as usize] >> 23;
        } else if z >= 0.5 {
            ih = 2;
        }

        if ih > 0 {
            // q > 0.5
            n += 1;
            let mut carry = 0i32;
            for i in 0..jz {
                // 计算 1-q
                let jv2 = iq[i as usize];
                if carry == 0 {
                    if jv2 != 0 {
                        carry = 1;
                        iq[i as usize] = 0x100_0000 - jv2;
                    }
                } else {
                    iq[i as usize] = 0xff_ffff - jv2;
                }
            }
            if q0 > 0 {
                // 罕见：1/12 概率
                match q0 {
                    1 => iq[(jz - 1) as usize] &= 0x7f_ffff,
                    2 => iq[(jz - 1) as usize] &= 0x3f_ffff,
                    _ => {}
                }
            }
            if ih == 2 {
                z = 1.0 - z;
                if carry != 0 {
                    z -= libm::scalbn(1.0, q0);
                }
            }
        }

        // 检查是否需要重算
        if z == 0.0 {
            let mut j2 = 0i32;
            for i in (jk..jz).rev() {
                j2 |= iq[i as usize];
            }
            if j2 == 0 {
                // 需要重算
                let mut k = 1i32;
                while iq[(jk - k) as usize] == 0 {
                    k += 1; // k = 需要的项数
                }
                for i in (jz + 1)..=(jz + k) {
                    f[(jx + i) as usize] = ipio2[(jv + i) as usize] as f64;
                    let mut fw = 0.0f64;
                    for jj in 0..=jx {
                        fw += x[jj as usize] * f[(jx + i - jj) as usize];
                    }
                    q[i as usize] = fw;
                }
                jz += k;
                continue 'recompute;
            }
        }
        break (n, z, ih);
    };

    // 切掉零项
    if z == 0.0 {
        jz -= 1;
        q0 -= 24;
        while iq[jz as usize] == 0 {
            jz -= 1;
            q0 -= 24;
        }
    } else {
        // z 按 24 位拆分（必要时）
        let z = libm::scalbn(z, -q0);
        if z >= TWO24 {
            let fw = (TWON24 * z) as i32 as f64;
            iq[jz as usize] = (z - TWO24 * fw) as i32;
            jz += 1;
            q0 += 24;
            iq[jz as usize] = fw as i32;
        } else {
            iq[jz as usize] = z as i32;
        }
    }

    // 整数"位"块转浮点
    let mut fw = libm::scalbn(1.0, q0);
    for i in (0..=jz).rev() {
        q[i as usize] = fw * iq[i as usize] as f64;
        fw *= TWON24;
    }

    // PIo2[0..jp]·q[jz..0]
    for i in (0..=jz).rev() {
        let mut fw = 0.0f64;
        let mut k = 0i32;
        while k <= jp && k <= jz - i {
            fw += PIO2[k as usize] * q[(i + k) as usize];
            k += 1;
        }
        fq[(jz - i) as usize] = fw;
    }

    // fq[] 压缩进 y[]（prec=0/1/2；本调用恒 prec=2，prec=0 分支保留对齐）
    match prec {
        0 => {
            let mut fw = 0.0f64;
            for i in (0..=jz).rev() {
                fw += fq[i as usize];
            }
            y[0] = if ih == 0 { fw } else { -fw };
        }
        1 | 2 => {
            let mut fw = 0.0f64;
            for i in (0..=jz).rev() {
                fw += fq[i as usize];
            }
            y[0] = if ih == 0 { fw } else { -fw };
            fw = fq[0] - fw;
            for i in 1..=jz {
                fw += fq[i as usize];
            }
            y[1] = if ih == 0 { fw } else { -fw };
        }
        // prec=3（quad）分支不移植：本端口仅 prec=2 调用（Java 同款），y 为二元组
        _ => unreachable!("kernel_rem_pio2 called with prec {prec} (only 0/1/2 ported)"),
    }
    n & 7
}

// ─────────────────────────────────────────────────────────────────────────────
// log / log10
// ─────────────────────────────────────────────────────────────────────────────

const LN2_HI: f64 = 6.93147180369123816490e-01; // 0x1.62e42feep-1
const LN2_LO: f64 = 1.90821492927058770002e-10; // 0x1.a39ef35793c76p-33

const LG1: f64 = 6.666666666666735130e-01; // 0x1.5555555555593p-1
const LG2: f64 = 3.999999999940941908e-01; // 0x1.999999997fa04p-2
const LG3: f64 = 2.857142874366239149e-01; // 0x1.2492494229359p-2
const LG4: f64 = 2.222219843214978396e-01; // 0x1.c71c51d8e78afp-3
const LG5: f64 = 1.818357216161805012e-01; // 0x1.7466496cb03dep-3
const LG6: f64 = 1.531383769920937332e-01; // 0x1.39a09d078c69fp-3
const LG7: f64 = 1.479819860511658591e-01; // 0x1.2f112df3e5244p-3

/// fdlibm log（Java FdLibm.Log.compute，= StrictMath.log）。
pub fn log(x: f64) -> f64 {
    let mut hx = hi(x);
    let lx = lo(x);

    let mut k = 0i32;
    if hx < 0x0010_0000 {
        // x < 2**-1022
        if ((hx & EXP_SIGNIF_BITS) | lx) == 0 {
            return -TWO54 / 0.0; // log(±0) = -inf
        }
        if hx < 0 {
            return (x - x) / 0.0; // log(-#) = NaN
        }
        k -= 54;
        let x = x * TWO54; // 次正规数，放大
        hx = hi(x);
    }
    if hx >= EXP_BITS {
        return x + x;
    }
    let mut x = x;
    k += (hx >> 20) - 1023;
    hx &= 0x000f_ffff;
    let i = (hx + 0x9_5f64) & 0x10_0000;
    x = with_hi(x, hx | (i ^ 0x3ff0_0000)); // 归一化 x 或 x/2
    let k = k + (i >> 20);
    let f = x - 1.0;
    if (0x000f_ffff & (2 + hx)) < 3 {
        // |f| < 2**-20
        if f == 0.0 {
            if k == 0 {
                return 0.0;
            }
            let dk = k as f64;
            return dk * LN2_HI + dk * LN2_LO;
        }
        let r = f * f * (0.5 - 0.33333333333333333 * f);
        if k == 0 {
            return f - r;
        }
        let dk = k as f64;
        return dk * LN2_HI - ((r - dk * LN2_LO) - f);
    }
    let s = f / (2.0 + f);
    let dk = k as f64;
    let z = s * s;
    let mut i = hx - 0x6_147a;
    let w = z * z;
    let j = 0x6b851 - hx;
    let t1 = w * (LG2 + w * (LG4 + w * LG6));
    let t2 = z * (LG1 + w * (LG3 + w * (LG5 + w * LG7)));
    i |= j;
    let r = t2 + t1;
    if i > 0 {
        let hfsq = 0.5 * f * f;
        if k == 0 {
            f - (hfsq - s * (hfsq + r))
        } else {
            dk * LN2_HI - ((hfsq - (s * (hfsq + r) + dk * LN2_LO)) - f)
        }
    } else if k == 0 {
        f - s * (f - r)
    } else {
        dk * LN2_HI - ((s * (f - r) - dk * LN2_LO) - f)
    }
}

const IVLN10: f64 = 4.34294481903251816668e-01; // 0x1.bcb7b1526e50ep-2
const LOG10_2HI: f64 = 3.01029995663611771306e-01; // 0x1.34413509f6p-2
const LOG10_2LO: f64 = 3.69423907715893078616e-13; // 0x1.9fef311f12b36p-42

/// fdlibm log10（Java FdLibm.Log10.compute，= StrictMath.log10）。
pub fn log10(x: f64) -> f64 {
    let mut hx = hi(x);
    let lx = lo(x);

    let mut k = 0i32;
    if hx < 0x0010_0000 {
        // x < 2**-1022
        if ((hx & EXP_SIGNIF_BITS) | lx) == 0 {
            return -TWO54 / 0.0; // log10(±0) = -inf
        }
        if hx < 0 {
            return (x - x) / 0.0; // log10(-#) = NaN
        }
        k -= 54;
        let x = x * TWO54; // 次正规数，放大
        hx = hi(x);
    }
    if hx >= EXP_BITS {
        return x + x;
    }
    let mut x = x;
    k += (hx >> 20) - 1023;
    // 无符号移位（Java >>>）
    let i = ((k as u32 & SIGN_BIT) >> 31) as i32;
    hx = (hx & 0x000f_ffff) | ((0x3ff - i) << 20);
    let y = (k + i) as f64;
    x = with_hi(x, hx);
    let z = y * LOG10_2LO + IVLN10 * log(x);
    z + y * LOG10_2HI
}
