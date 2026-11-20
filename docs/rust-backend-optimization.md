# 公式求值 Rust 后端 · 性能优化线（csc-rust-perf）

> 状态:**探索中**（2026-11-20 开线）
> 施工面:worktree `C:/Users/Administrator/csc-rust-perf-wt`,分支 `csc-rust-perf`（自 main 757e550 分出）
> 触发:用户指令「开一条RUST后端优化分支看看还能不能优化一下性能」;承接 `rust-backend-exploration.md` R4.5 记录在案的剩余头寸（分支/比较派发 ~2× 纯算术成本,估 20–40%,当时判不立项）。
> 方法:**测量驱动**——每根杠杆先 hack 测上限,达判定门才落正式形态;读数取三轮中位;逐位对拍网是语义不变的唯一验收面。

---

## 一、基线（2026-11-20 本机复现,alloc-bench 引擎级,三轮中位）

| 脚本形态 | fast | full |
|---|---|---|
| scalarLoop | 31.6ns/iter | 57.3ns/iter |
| vec3Loop | n/a（非纯标量） | 58.4ns/iter |
| closedForm | 212.1ns/eval | 322.0ns/eval |
| **弹道真脚本（生产代表）** | **4.89ms/eval** | 8.99ms/eval |

（三轮中位;轮间散布 ~±10%,单项结论按同窗对照判读。）

对照口径:R4.5 收口时生产 JNI 面（FormulaFaceBench）弹道 5.27–5.32ms/eval、对 Java ~3×;本线收口后跑 JNI 面复测。零分配基线保持（测量窗内仅首扩缓冲）。

## 二、解剖发现（开线即得）

1. **Tok 内存布局 16B,「8B 定宽」注释从未兑现**。`Const(f64)` 撑大整枚举（`size_of::<Tok>() == 16` 实测）;tok.rs 模块头宣称的 8B 形态不存在。探索线 §3.2 当年把「16B token 步长」列为第一版慢因之一,后续只修了值搬运（TokStack）,token 步长原样。→ 杠杆 **L2**。
2. **解码期槽位认证缺口（对抗面,新发现）**。`Tok::Var` 与 `Stmt::Assign` 的槽位索引无上界校验（v3 pin_slots 有,Var/Assign 没有）;位翻转字节码只要解码通过,exec 时 `regs[slot]` 越界 = slice 索引 panic,release profile `panic = "abort"` ⇒ JNI 调用内进程崩——违反 R2.5「敌意输入绝不 JVM 崩溃/挂死」口径。既有轰炸测试只断言上传 rc ∈ {-5, ≥0},从不 exec 合法化的变异体,缺口未被测试网覆盖。→ 收口与杠杆 **L1** 同机制（解码期认证）。

## 三、候选杠杆与判定门

**L1 每 op 动态开销**（栈 push/pop 逐次边界/下溢检查 + 逐 token deadline 计数 + 寄存器读边界检查）。
机理:R4.5 分解显示分支/比较派发 ~2× 纯算术成本;当前每个二元 op 付 3 次栈界检查 + 2 次下溢分支,每 token 付计数器增/测/跳,每 Var 读付一次槽位界检查。
形态:**解码期静态认证**。表达式 RPN 是直线流（无控制流）,每 token 栈效应恒定（Op/Cmp −2+1、Not/Swz −1+1、Fn −arity+1、Const/Var +1）⇒ 最大栈深与下溢点在解码期可精确算出;槽位上界同场校验。认证通过的表达式走 unchecked 快路径,认证失败走现 checked 兜底（语义逐位同）。顺带收口 §二.2 对抗缺口。
判定门:hack 上限 ≥10% 才落正式形态。

**L2 Tok 16B→8B**（常量池化）。`Const(f64)` → `Const(u32 索引)` + Program 级常量池;Tok 枚举缩至 8B,token 流缓存足迹减半。**线格式（wire format）不动**——池化是 native 内存形态,解码器边解边入池。
判定门:≥3% 才保留。

**L3 PGO**（两遍构建:profile-generate 训练 → profile-use）。无语义风险、无代码改动;先本地测量,收益可观再谈 buildNative 接线。
判定门:≥3%。

**L4 扁平化语句派发 / threaded code**（R4.5 记录的结构性杠杆,估 20–40%）。仅当 L1–L3 落地后 BODY 型差距仍显著时立项;工程量大、收益不确定,判定门到点再拍板。

**已关闭杠杆不重开**（均有实测否决记录）:target-cpu=x86-64-v3（劣化）、常量折叠（上限 3.2%）、fdlibm 微成本（非瓶颈）、direct ByteBuffer（~3%）、引用计数程序缓存（MB 级上界）。

## 四、红线

- **逐位语义不变**:cargo parity 30/30 + gradle 对拍网全绿是每批验收;不改浮点运算顺序、不做可能改舍入的代数变换（含 pow→mul 一类）。
- **绝不 panic 跨 FFI**:unchecked 访问只走认证通过路径;认证失败/敌意输入一律受控负错误码;`panic = "abort"` 下 panic 即进程崩,视同崩溃级缺陷。
- **未发版形态不写兼容**:wire format v3、ABI 2、错误码表不动;优化只动 native 内存/求值形态。
- **unsafe 纪律**:仅限认证后的栈/槽位访问;逐处注释认证依据;认证函数本身零 unsafe。

## 五、实验记录（随批追加）

## 六、复现手册

```bash
# 引擎级基准 + 测试（工具链:stable-x86_64-pc-windows-gnu）
cd native/formula-core
cargo test --release
cargo run --release --bin alloc-bench   # fast/full 双跑,三轮中位

# JNI 面基准（收口时）:bench/java/FormulaFaceBench,脱离 gradle javac 直跑,
# 见 rust-backend-exploration.md §七同款口径
```
