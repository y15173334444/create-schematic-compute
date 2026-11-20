# 公式求值 Rust 后端 · 性能优化线（csc-rust-perf）

> 状态:**收口**（2026-11-20 开线同日收口:L1 落地 + 对抗缺口收口,L3 工具链关闭;L2 于 2026-11-21 实测未达判定门、全退（§五.3）;测量方法学发现见 §五.0/§五.3——跨二进制 ±25% 布局噪声 + 跨时段负载漂移,可靠口径 = 同二进制探针 / 同窗交错对照）
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
2. **解码期槽位认证缺口（对抗面,新发现;✅ 已收口,见 §五.2）**。`Tok::Var` 与 `Stmt::Assign` 的槽位索引无上界校验（v3 pin_slots 有,Var/Assign 没有）;位翻转字节码只要解码通过,exec 时 `regs[slot]` 越界 = slice 索引 panic,release profile `panic = "abort"` ⇒ JNI 调用内进程崩——违反 R2.5「敌意输入绝不 JVM 崩溃/挂死」口径。既有轰炸测试只断言上传 rc ∈ {-5, ≥0},从不 exec 合法化的变异体,缺口未被测试网覆盖。→ 收口与杠杆 **L1** 同机制（解码期认证）。

## 三、候选杠杆与判定门

**L1 每 op 动态开销**（栈 push/pop 逐次边界/下溢检查 + 逐 token deadline 计数 + 寄存器读边界检查）。
机理:R4.5 分解显示分支/比较派发 ~2× 纯算术成本;当前每个二元 op 付 3 次栈界检查 + 2 次下溢分支,每 token 付计数器增/测/跳,每 Var 读付一次槽位界检查。
形态:**解码期静态认证**。表达式 RPN 是直线流（无控制流）,每 token 栈效应恒定 ⇒ 最大栈深与下溢点在解码期可精确算出;槽位上界同场校验。认证通过的表达式走 unchecked 快路径,认证失败走现 checked 兜底（语义逐位同）。顺带收口 §二.2 对抗缺口。
判定门:hack 上限 ≥10% 才落正式形态。→ **✅ 已落地**（同二进制实测 full 路 +10~11%,形态与测量口径见 §五.1/§五.0）。

**L2 Tok 16B→8B**（常量池化）。`Const(f64)` → `Const(u32 索引)` + Program 级常量池;Tok 枚举缩至 8B,token 流缓存足迹减半。**线格式（wire format）不动**——池化是 native 内存形态,解码器边解边入池。
判定门:≥3% 才保留。→ **❌ 全退**（2026-11-21 实测:同窗抖动分布 7 线无一达 +3%、3 线中位回退 >3%——弹道 full −11.5%;实施形态、两轮分布与判定见 §五.3）。

**L3 PGO**（两遍构建:profile-generate 训练 → profile-use）。无语义风险、无代码改动;先本地测量,收益可观再谈 buildNative 接线。
判定门:≥3%。→ **❌ 关闭**（工具链无 profiler_builtins,见 §五.0）。

**L4 扁平化语句派发 / threaded code**（R4.5 记录的结构性杠杆,估 20–40%）。仅当 L1–L3 落地后 BODY 型差距仍显著时立项;工程量大、收益不确定,判定门到点再拍板。→ 现状:检查价已实测 10~11%（非 2×）,R4.5 的「派发 2×」归因被 §五.0 修正——L4 的预期收益基础变弱,维持不立项。

**已关闭杠杆不重开**（均有实测否决记录）:target-cpu=x86-64-v3（劣化）、常量折叠（上限 3.2%）、fdlibm 微成本（非瓶颈）、direct ByteBuffer（~3%）、引用计数程序缓存（MB 级上界）。

## 四、红线

- **逐位语义不变**:cargo parity 30/30 + gradle 对拍网全绿是每批验收;不改浮点运算顺序、不做可能改舍入的代数变换（含 pow→mul 一类）。
- **绝不 panic 跨 FFI**:unchecked 访问只走认证通过路径;认证失败/敌意输入一律受控负错误码;`panic = "abort"` 下 panic 即进程崩,视同崩溃级缺陷。
- **未发版形态不写兼容**:wire format v3、ABI 2、错误码表不动;优化只动 native 内存/求值形态。
- **unsafe 纪律**:仅限认证后的栈/槽位访问;逐处注释认证依据;认证函数本身零 unsafe。

## 五、实验记录（2026-11-20 收口）

### 0. 测量方法学的重要发现(先读,决定一切读数的可信度)

- **跨二进制对比在本负载上被布局彩票淹没**。同一份标量求值代码,仅因 binary 里 eval_full 的 monomorph 形态不同(dual / 恒 checked / 恒 unchecked),JNI 面标量负载(弹道)实测摆幅 **±25%**:b4x 对照二进制的标量路代码与 b4f 逐字节同构,面读数 5.0ms → 6.4ms;alloc-bench 同样 ±10–20%(EXP-A/B/D 三个语义近同形态散在 30.8–37.9ns)。**任何 ≤20% 的 A/B 二进制差异不可归因**——此前的「标量面 −5%/−7%」读数全部作废,不得引用。
- **可靠口径 = 同二进制内对照**:同一 binary 同一进程,认证流(走 unchecked 机)与摘证流(走 checked 机)交替计时。此口径下:
  - **逐 op 检查在 tagged full 路的真实代价 = +10~11%**(vec3Loop +11.0%、scalarLoop-full +10.4%,探针交替三轮)。
  - E1 hack 当时读到的 26–46% 是跨二进制假象,**收回**。
- PGO 本是对布局彩票的对症药,但本机工具链不可用:stable-x86_64-pc-windows-gnu 无 `profiler_builtins`(E0463),MSVC 目标无 link.exe。**L3 关闭**(工具链级约束;换工具链可重开)。
- 生产口径基准 = `FormulaFaceBench`(JNI 四面);同窗 java 面是漂移对照。弹道是纯标量脚本 → 生产标量路,full 路收益(向量/混排脚本)在引擎级口径测。

### 1. L1 落地形态:解码期认证 + unchecked full 双态(已落地,38/38 cargo 绿)

- **Rpn 认证书**(`ast::Rpn::from_toks`,构造期一次走查):每 token 栈效应 Const/Var +1、Op/Cmp −2+1(含 `'('` lenient 吃两项)、Not/Swz −1+1、Fn −consumed+1——**`consumed()` 与 `arity()` 不是一回事:vec3 求值弹 3 个而 arity() 表口径记 1,证书按弹栈数算,否则 vec3 程序误认证**。certified = 全流无下溢 ∧ 峰值深 ≤ 64 ∧ 全部 Var 槽位在界。
- **求值双态**:`eval_full::<CHECKED>` 双 monomorph 共享同一 match 体;认证流走 unchecked(表达式栈 push/pop 与 Var 读零边界检查),未认证流走 checked 兜底(出错 token 时点与 Java 逐字一致,含 deadline 采样竞争面)。标量路恒 checked 单态(f64 栈检查近零成本,语义全保真)。未认证流含 hand-built 短 regs 错配 → checked 机 `.get()` 受控 Underflow,绝不 panic/UB。
- **同二进制实测收益:full 路 +10~11%**。生产含义 = vec3/混排脚本受益;纯标量脚本(弹道类重节点)走 checked 标量机,收益 0。跨二进制的标量面差异不可测(§五.0)。
- 语义验收:双路逐位对拍(certified vs decertified 全值位一致,含 vec3 槽)+ 路由 parity + parity/bridge/trig 既有网全绿;wire format v3 / ABI 2 / 错误码表零改动。

### 2. 对抗缺口收口(顺带发现,已落地)

- `Tok::Var` / `Stmt::Assign` 槽位**解码期拒绝**(`DecodeError::BadSlot` → programUpload −5),与 BadPinSlot 同口径;对抗测试补「位翻转字节码 → BadSlot 受控拒绝,绝不 panic」exec 面。
- 原缺口:位翻转字节码解码通过后,exec 的 `regs[slot]` 越界 = slice 索引 panic,release `panic = "abort"` ⇒ JNI 调用内进程崩——违反 R2.5「敌意输入绝不 JVM 崩溃」。轰炸测试只断言上传 rc ∈ {−5, ≥0}、从不 exec 合法化变异体,缺口未被测试网覆盖。此发现与 L1 的槽位认证同源,一并收口。

### 3. L2(Tok 16B→8B 常量池化)——实测未达判定门,**全退**（2026-11-21,代码零落地）

- **实施形态**（测毕整体回退,工作树未落任何代码提交）:`Tok::Const(u32 池索引)` + 池挂 `Rpn`（`Rpn { toks, consts: Box<[f64]>, certified, slot_hi }`——自包含不变量:池与索引同源构造,解码器边解边入池、mini 编译器编译期入池,eval `rpn.consts[idx]` 一跳;与 slot_hi 的安全模式一致,无跨结构前置条件;池挂 Rpn 而非 Program:免去跨结构前置条件、`eval_output` 签名不动,代价是跨表达式常量不去重——实测每表达式池极小,不构成考量）。`size_of::<Tok>()` 16→8（单测断言钉死）。**wire format v3 一字节未动**:.tokbin 夹具不重生成、Java 编码器零改动;池索引内部生成、不上线 ⇒ 对抗面零新增（实测确认）。同步面:认证书栈效应对 Const 形态不变,补池索引界内走查(越界=摘证书,checked 机 `read_const` 受控 Underflow,绝不 panic);`read_const` CHECKED 双态镜像 `read_reg`。语义网全绿后回退:certification 9（含新增池索引对抗）+ parity 14 + bridge_cross 7 + trig_anchor 2（tok 布局单测随 include 双跑）。
- **测量方法学（抖动分布法）**:每变体（池化 vs 16B 现状）造 5 个布局抖动二进制——bin-crate 内 5 档 `#[inline(never)]` pad 死代码（`--cfg layout_padN` 选档,64~16384 条 black_box 语句,防常量链强度折叠）。实测校准:pad 放 **lib-crate 会被 LTO 排到 eval 区之后**（linker map 证实只推移 rust_eh_personality,eval 区逐字节不动）——不构成抖动,弃;pad 放 **bin-crate 推移整个 lib/eval 区的绝对地址**（探针 = compile/eval_output/fdlibm::sin 地址低 12 位,逐档不同 = 布局真异）,采纳。每二进制 alloc-bench 三轮取中位,每线 5 点分布。⚠️ 抖动只采样「绝对地址/对齐」轴;§五.0 的 ±25% 另有一半来自「monomorph 形状」轴（本杠杆单形态采不到,同二进制探针照旧不适用）。
- **关键发现:跨时段序列 A/B 同样不可归因——负载漂移可伪造全线 ±30%**。先行序列测量（先 base 后 pooled,各 5×3）给出全线 +20~34% 的「大胜」;但**同一批 base 二进制** 30 分钟后同窗交错复测,全线读数掉 30~35%（scalarLoop fast 45.1→29.1ns）,pooled 两轮读数一致——序列差即机器负载漂移（本机常驻多会话/杀软）,非代码效应。**判定以同窗交错复测为准**（10 二进制配对交错、同一时间窗、散布收窄至 1.5~8%）。§五.0 由此补强:不仅跨二进制 A/B,**跨测量窗的先后对比同样不可归因,必须同窗对照**。
- **分布表 A——序列轮（方法学规定的 5×3 抖动分布;受负载漂移污染,存档不作判定）**:

| 线 | base min/median/max | pooled min/median/max | 中位差(正=池化快) |
|---|---|---|---|
| scalarLoop fast (ns/iter) | 35.7 / 45.1 / 55.8 | 30.2 / 31.2 / 32.7 | +30.8% |
| scalarLoop full (ns/iter) | 49.0 / 56.4 / 63.9 | 40.7 / 42.2 / 44.1 | +25.2% |
| vec3Loop full (ns/iter) | 53.7 / 69.1 / 75.4 | 45.5 / 47.4 / 55.6 | +31.4% |
| closedForm fast (ns/eval) | 227.9 / 302.2 / 438.3 | 199.4 / 209.2 / 257.8 | +30.8% |
| closedForm full (ns/eval) | 305.6 / 343.3 / 410.9 | 244.0 / 249.8 / 263.3 | +27.2% |
| ballistic fast (ms/eval) | 6.12 / 6.95 / 7.48 | 4.45 / 4.61 / 4.99 | +33.7% |
| ballistic full (ms/eval) | 7.68 / 8.05 / 8.56 | 6.10 / 6.42 / 6.81 | +20.3% |

（base spread 高达 92%（closedForm fast）、pooled spread 仅 8%——两个负载体制,序列对比之差即漂移本身。）

- **分布表 B——同窗交错轮（判定依据;10 二进制配对交错,三轮中位 × 5 抖动）**:

| 线 | base min/median/max | pooled min/median/max | 中位差(正=池化快) |
|---|---|---|---|
| scalarLoop fast (ns/iter) | 28.9 / 29.1 / 31.2 | 29.4 / 30.0 / 31.0 | **−3.1%** |
| scalarLoop full (ns/iter) | 39.8 / 40.4 / 41.1 | 40.7 / 41.4 / 41.4 | −2.5% |
| vec3Loop full (ns/iter) | 45.2 / 45.5 / 45.9 | 44.7 / 45.2 / 46.1 | +0.7% |
| closedForm fast (ns/eval) | 194.4 / 195.9 / 199.3 | 195.0 / 199.1 / 200.1 | −1.6% |
| closedForm full (ns/eval) | 234.8 / 238.8 / 240.4 | 244.2 / 246.9 / 259.0 | **−3.4%** |
| ballistic fast (ms/eval) | 4.37 / 4.40 / 4.57 | 4.33 / 4.47 / 4.61 | −1.7% |
| ballistic full (ms/eval) | 5.44 / 5.48 / 5.59 | 6.07 / 6.11 / 6.40 | **−11.5%** |

- **判定:全退**（判定门「中位改善 ≥3% 且无任何一线中位回退 >3%」两头都不满足）。7 线无一达 +3%;3 线中位回退超 3%（scalarLoop fast −3.1%、closedForm full −3.4%、ballistic full −11.5%）。机理:本负载 RPN 流很小（常驻 L1）,token 步长减半的足迹收益近零,而每个 Const 多一跳池读实付——常量密集的向量路（弹道 full）回撤最大;与「常量折叠 ≤3%」同族教训:表达式机每 token 成本大头在派发/栈搬运,不在 token 取指。分布重叠处如实标注（vec3Loop full、ballistic fast 两变体 min–max 互相跨入）,按「重叠不挑好轮」纪律不得据此宣称收益。

### 4. 维持关闭的杠杆(R4.5 判决不重开)

target-cpu=x86-64-v3(劣化)、常量折叠(上限 3.2%)、fdlibm 微成本(非瓶颈)、direct ByteBuffer(~3%)、程序缓存引用计数(MB 级上界)。

## 六、复现手册

```bash
# 引擎级基准 + 测试（工具链:stable-x86_64-pc-windows-gnu）
cd native/formula-core
cargo test --release
cargo run --release --bin alloc-bench   # fast/full 双跑,三轮中位

# JNI 面基准（收口时）:bench/java/FormulaFaceBench,脱离 gradle javac 直跑,
# 见 rust-backend-exploration.md §七同款口径
```
