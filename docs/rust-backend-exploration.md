# 公式求值 Rust 后端 · 探索线（csc-rust-backend-explore）

> 状态:**✅ 已拍板立项,R1/R2 已落地**(2026-11-14 拍板见 §六;R1 桥对拍 17/17 绿;R2 构建接线落地——cargo 23/23 + gradle e2e 4 用例绿;R3 求值接线待开工)
> 施工面:worktree `C:/Users/Administrator/csc-rust-backend-wt`,分支 `csc-rust-backend-explore`(自 main 5528caa3 分出)
> 触发:公式节点预算池开到 20ms/tick 时,服务端内存分配率达 1GB/s 级——求值链路的堆分配成为主要代价。
> 产物:`native/formula-core/`(零分配求值核心,13/13 与 Java 链对拍含弹道真脚本)、`bench/java/`(Java 基线基准)、本文。

---

## 一、结论速览

- **根因坐实**:`FormulaParser.evaluateValue` 的热路径每 token 堆分配——每次调用 `new ArrayDeque<Value>()`、每个常量/运算结果 `new Value.Scalar(d)`、标量函数 `new double[arity]`、向量运算 `new Vec3Val(...)`。实测稳态 **296–520 B/循环迭代**(标量/vec3),预算打满即 ~3.6–4.0 GB/s(详见 §二;用户观测的 1GB/s 对应更轻的脚本构成或 slice 均分稀释)。
- **Rust 后端原型达成目标**:`native/formula-core` 以 tagged 寄存器 + 定长表达式栈 + 显式 carrier 实现同语义求值,**稳态零堆分配**(2s 测量窗 3 次分配 224B,均为首次缓冲扩容);吞吐全面不劣于 Java,标量快路径 **~1.9x**(见 §三)。
- **分配归零仍是主卖点,吞吐也不再吃亏**:1GB/s 分配 = young gen 高频回收 + 卡表/缓存污染,这才是预算打满时的真实代价;零分配直接消除。标量快路径对 Java ~1.9x,向量/闭式 1.5x+。无 JIT 预热/去优化,墙钟预算语义(16 迭代边界 + K6 每 256 ops 采样)逐字保持。
- **Java 解析器仍是唯一前端**。Rust 只做执行引擎:Java 侧把自家 RPN 编译为紧凑 `Tok` 字节码下发,不维护第二套解析器语义。
- **拍板点**(§六):JNI vs Panama FFM(Java 21 下 FFM 是 preview)、是否立项、排进哪把刀。

## 二、问题定位与 Java 基线

### 2.1 分配热点(机制级)

- 表达式求值入口 `evaluateValue(rpn, env)`:每次调用分配一个 `ArrayDeque<Value>` 作求值栈;循环体里**每条赋值语句每迭代**调一次。
- 每个数字常量、每次运算的中间结果都 `new Value.Scalar(d)`(24B/个);向量运算 `new Vec3Val(...)`;标量函数调用 `new double[arity]`。
- 语句解释器侧的 `Carrier`/`Ctx`/`HashMap` 快照只在挂起时分配,非稳态热点。
- C2 的逃逸分析削掉了部分分配,但无法覆盖跨方法边界的 megamorphic 分派路径——残余 ~300–500 B/迭代。

### 2.2 实测基线(javac 直跑,Zulu 21,-Xms1g,2s 测量窗,ThreadMXBean 线程分配计数器)

| 脚本形态 | 吞吐 | 单迭代耗时 | 分配率 | 单迭代分配 |
|---|---|---|---|---|
| 标量热循环(repeat 100k 双赋值) | 12.86M it/s | 77.8ns | **3631 MB/s** | 296 B |
| vec3 热循环 | 8.05M it/s | 124.2ns | **3992 MB/s** | 520 B |
| 闭式长表达式(单次求值) | 1.97M 次/s | 508ns | **1877 MB/s** | 1000 B/次 |

基准脚本与运行方法:`bench/java/FormulaAllocBench.java`(公式链是纯 Java,仅 slf4j 依赖,javac 直跑不进 gradle)。

## 三、Rust 原型与实测

### 3.1 结构(`native/formula-core`,std-only 零依赖,离线可构建)

- `value.rs` — `V`:标量/vec3 双形态 tagged 值,32B `Copy`,全部栈上传递(对应 Java `Value` record 的堆形态)。
- `tok.rs` — `Tok`:定宽字节码(Java `List<Object>` RPN 的紧凑形态);函数表与 Java `FormulaFunctions` 逐字对齐。
- `ast.rs` — 语句树 + 槽位表 + `progress_of`(镜像 `progressOf`:最外层 repeat 为主、内层份额叠加、while 回退最内层)。
- `interp.rs` — 求值引擎:
  - 环境 = 编译期槽位化的 `Vec<V>` 寄存器(未定义变量 lenient 0);
  - 表达式 = 定长 `[V; 64]` 栈机,无装箱;
  - 控制流 = 显式循环栈 + carrier 快照挂起/寻径续算(k 滞后推进、break 内层捕获、顶层 lenient 等语义逐字镜像);
  - 预算 = 循环边界每 16 迭代墙钟(slice 挂起)+ 表达式机每 256 ops 采样 deadline(K6 兜底,转 fromDeadline 挂起)+ MAX_ITER=1M spread 硬 shed;
  - 缓冲 = 调用方持有(regs/loop_stack 跨 exec 复用)→ 稳态零分配的关键形态。
- `compiler.rs` — mini 前端,**仅基准脚手架**:正式桥接不走这里。已把 `compileTokens` 的 lenient 规则族逐字复刻并用探针实证(§5.1)。

### 3.2 对拍与基准

对拍测试 `tests/parity.rs` **11/11 绿**，与 javac 直跑的 Java 探针逐位一致:

- scalarLoop `acc=4999950.0, i=100000.0`(逐位);
- vec3Loop `p/v` 六个分量逐位一致(含 `-47999.5000000018` 这类浮点尾数);
- closedForm `y=2.0`(未知函数 lenient 链路的栈顶语义,见 §5.1);
- 续算守恒:25µs slice 反复挂起/续算,终值与不限预算逐位一致(零迭代丢失/重复);while 同;
- MAX_ITER 硬 shed;一元负号 `[0,x,-]`;未知函数 `min(1,2)→2.0 / floor(2.5)→2.5`;
- **标量快路径双路对拍**:纯标量脚本 fast/full 两路终值逐位一致;vec3/swizzle 程序探测自检。

池化基准(2s 测量窗,计数分配器,三轮中位数;Java 侧同脚本同窗复跑两次读数稳定):

| 脚本形态 | Java | Rust full(tagged 栈) | Rust fast(f64 裸栈) | fast/full | fast/Java |
|---|---|---|---|---|---|
| 标量热循环 | 78.8ns(12.7M it/s),296B/iter | 76.2ns(13.1M),0B | **40.6ns(24.6M),0B** | **1.87x** | **1.94x** |
| vec3 热循环 | 121.6ns(8.2M),520B/iter | 80.6ns(12.4M),0B | n/a(非纯标量) | — | 1.51x |
| 闭式长表达式 | ~430–510ns(2.1–2.3M),1000B/次 | 320ns(3.1M),0B | **247ns(4.0M),0B** | **1.30x** | **~1.9x** |

要点:

- **分配 296–520 B/迭代 → 0**(fast/full 两路同);测量窗总分配 224B = 首次缓冲扩容。
- **标量快路径**(编译期探测纯标量程序 → `f64` 裸栈求值机,8B 搬运、零 tag 检查)把 tagged 栈机的结构性开销拿掉:**对 Java 1.9x**。探测规则:全程序无向量函数、无 swizzle token(vec3 只能由向量函数产生);快路径语义由双路对拍测试锁定。
- 修正早前读数:原型首版 full 路径测得 99.6ns/iter(-23% vs Java),三轮复跑稳定在 76.2ns——**tagged full 路径与 Java JIT 打平**(该首测差异属代码布局/单次采样噪声);「原生必慢于 JIT」的假设不成立,慢的是第一版实现里 32B 值搬运 + 16B token 步长,快路径验证了这一归因。
- 运行:`cargo run --release -p formula-core --bin alloc-bench`(fast/full 双跑输出)。

### 3.3 真实重节点:火控弹道反解脚本(docs/examples/ballistic_solver.formula)

拿仓库真脚本跑通(粗扫 46 点×400 tick 模拟 + 细扫 17 点 + 割线 ≤10 轮)。为此 mini 前端补齐了三种语法形态:行内块 `{ a; b }`(含嵌套)、无花括号 if 体(`if (still > 20) break`)、`@output` 声明行(lenient 跳过)。5 个 CBC 金标准案例:**ay/ap/hit/vx0/vy0 全部与 Java 逐位一致**(整条模拟链),唯 case 2 的 `vz0`(≈1e-15 浮点尘埃)差 3.3e-4 相对值——根因见 §5.3,非求值引擎差异。

对拍口径 = Java 探针解析缓存(同模组 `cachedScript` 行为;解析本身 1.1ms/520KB,可忽略):

| 500 格中程案例 | Java | Rust fast | Rust full | fast/Java |
|---|---|---|---|---|
| 单次求解耗时 | 14.98ms | **5.83ms** | 11.1ms | **2.57x** |
| 单次求解分配 | **43.3 MB** | **0**(整窗 6.5KB 缓冲首扩) | 0 | — |

**这就是 1GB/s 的真身**:Java 下一次火控解算 = 43MB 垃圾(分配速率 2.9GB/s),20ms 预算每 tick 只能塞一次解算还附带一次 young GC 压力;Rust 快路径同预算能塞 ~3 次且零垃圾。快路径生效(脚本纯标量)外加消掉了 Java 侧 HashMap env 的每变量查表。

## 四、桥接契约(待拍板的实施形态)

### 4.1 编译下沉(Java 侧)

- 编辑/加载时:`FormulaParser.parseScript` 照常产出 AST;新增一步把每个 RPN 叶子编译为 `Tok[]`(`short[]`/`long[]` 打包),缓存在节点上(与 `cachedScript` 同生命周期)。
- 槽位表 = 变量名互敦表(输入钉 + 脚本变量),`@output` 展开、`vec3Vars` 推断照旧走 Java 层。
- Tok 编码建议:`Const(f64)` 8B + 判别;`Var(u16)` 槽位;单 char 运算直存 ASCII;函数/比较小枚举。目标:单 token ≤ 16B,整块数组一次 JNI 传递。

### 4.2 FFI 面(三选一,拍板点)

| 方案 | 优点 | 代价 |
|---|---|---|
| 手写 JNI(`jni` crate) | 稳定、零 preview 依赖 | 样板代码多;`System.nanoTime` 回调或时钟锚点约定 |
| Panama FFM(JEP 442,Java 21 preview) | 无 C 胶水、downcall 直达 | 21 上要 `--enable-preview`;NeoForge 运行时加 preview 标志的兼容面 |
| 升 22+ 用 final FFM | 同上无 preview | MC 1.21 工具链锁 21,动工具链是大动作 |

### 4.3 墙钟与预算的跨界

- Java 传 `anchor + slice`:exec 开始时取 `System.nanoTime()`,Rust 侧用自己的单调钟(`Instant`)从锚点起算 delta——两个单调钟 epoch 不同但**进程内 delta 一致**,零回调开销;
- 或每 256 ops 回调 `System.nanoTime()`(JNI 频率 ~每微秒级计算一次回调,可忽略);
- K6 fromDeadline 挂起、16 迭代 slice 挂起、MAX_ITER shed 三语义原样跨界,carrier 序列化为字节数组回传(或 Java 侧持 opaque handle)。

### 4.4 分发与回退

- native 库随 jar 分发(`natives/<os>-<arch>/` 提取 + `System.load`),先覆盖 Windows/Linux x64,macOS/arm64 视用户群;
- **Java 解释器完整保留**为无 native 时的兜底(加载失败/陌生平台自动降级),两条路径由对拍测试网锁定同语义;
- 引脚/Value/去重缓存/冻结门面(K6 freeze、emit-on-done)全部不动,只换 `evaluateValue`+解释器这一层。

### 4.5 调用次数形态:批量入口(全局拓扑落地后启用)

逐节点 exec 的调用数 = 每 tick 活跃公式节点数。批量形态把 JNI 面收敛为四类:

- **session 建/销**(世界级):编译产物、寄存器/循环栈池、carrier 状态全部常驻 native;服务端线程独占,无锁;
- **program 上传**(编辑级,仅脚本变更):Tok 字节码 + 槽位表(按名映射,引脚序≠源码首现序);
- **tick 批**(每依赖波 1 次):全局拓扑给出活跃节点集与依赖波划分 → native 按波内顺序求值;输入/输出/状态走复用的 direct ByteBuffer;carrier 挂起/续算 native 侧自持,不跨界;heavy-yield 记账下沉,Java 只收每节点状态数组;
- **观测/试算**(交互级,非每 tick)。

调用数 = 公式节点依赖深度(常规构造 1–3 次/tick;病态长链退化为链长,是语义不变下的下界)。若单独拍板接受「跨公式节点输入延迟 1 tick」语义(以 cross-be 0/1-tick 实测为依据),可压到恒 1 次/tick。

诚实校准:逐节点调用开销本身不是瓶颈(50 节点 ≈ 数 µs/tick,对照 20ms 预算可忽略);批量化的实质收益是 ①Java 侧每节点准备/收尾(env 组装、输出拷贝、yield 记账)一并零分配 ②FFI 契约面收敛为一处可审计 ③公平记账下沉。**R3 以批量入口为目标形态;全局拓扑未落地前先用逐节点入口过渡,两者共用同一 session,切换不换契约。**

## 五、语义对齐备忘(探索期实证,增量约束)

### 5.1 lenient 规则族(Java 探针实证,Rust 已逐字复刻)

- 未知函数 `f(args...)`:lexer 只认表内名为 FUNCTION;未知名走 IDENT → 名字当变量压栈,**逗号把分组 `(` 弹进输出** → RPN 形如 `[min, 1, '(', 2]`,求值时 `'('` 吃两项得 0,最终结果 = 栈顶 = 最后参数(实测 `min(1,2)→2.0`、`foo(5)→5.0`);
- 一元负号 = `[0, x, '-']`(0- 技巧);一元 `+` 同口径也走 `-`;
- `PI/E`:ops 栈顶是分组 `(` 才是常量字面量,否则变量引用(`sin(PI)` 的 PI 是变量、出引脚);
- `^` 左结合(`>=` 弹栈);`==/!=` 容差 1e-6;三角函数角度制、定义域外 lenient 0;yaw/pitch 逐字对齐 DIRECTION 节点;
- 除零/模零 → 0;向量非法组合(v×v、标量÷v、% ^)→ 零向量;多分量 swizzle/标量取分量 → 0。

### 5.2 Java 侧顺带发现(建议登记册收编)

- **畸形 RPN 每 tick 异常**:括号不闭合(如 `y = 1 + (2`)能通过解析进入 AST,exec 时抛 `NoSuchElementException`——被 eval 分支 `catch (Exception)` 兜住(输出归零),但坏档/坏编辑形态下是每 tick 异常。建议解析期把「括号不闭合」收编为 ERROR issue(与 P1-6 深度守卫同模式)。本条出自本探索线的对拍准备,归属主仓缺陷面,由用户拍板后进登记册。

### 5.3 libm 精度差异(桥接期决策点)

弹道 5 案例对拍中 4 例**整链逐位一致**,唯 case 2 的 `vz0`(≈1e-15 浮点尘埃)差 3.3e-4 相对值。根因探针实证:**windows-gnu 链接的 msvcrt/UCRT libm 在 π/2 倍数附近用低精度 π 做参数归约**——`cos(90°)` Java(fdlibm 血统)= 6.123233995736766e-17(数学正确),msvcrt = 6.123031769111886e-17。对模拟主量(ay/ap/hit/vx0/vy0)无影响(扫描/割线的阈值量化吸收了轨迹端差异),只在尘埃直通输出处暴露。

**已收口(R1 落地)**:捆绑 libm crate 不够——musl 血统与 Java fdlibm 5.3 在 sin 1.1%/cos 0.8%/**log10 6.7%** 参数差 1 ulp,模拟链(弹道 46×400 迭代)放大到 ap ~76 ulp,全逐位破产。最终形态 = **双端同锚 fdlibm 5.3**:①Rust 侧 `src/fdlibm.rs` 逐行移植 Zulu 21 `FdLibm.java` 的 Sin/Cos/RemPio2/KernelRemPio2/Log/Log10(14300 样本位模式夹具逐位验证,tests/trig_anchor.rs);②Java 侧 FormulaFunctions/FormulaParser 求值 `Math`→`StrictMath`(HotSpot Math 内在函数是 Intel 系血统且随 CPU 厂商漂移,本机实测与 StrictMath 差 2–3% 参数;StrictMath = 跨平台确定锚,弹道金标准值不变)。收口后弹道 5 案例全链**全逐位**(含尘埃值),尘埃带容差已删除。libm crate 继续承担 asin/acos/atan2/exp/sinh/cosh/pow/tan(夹具域内已逐位)与 scalbn。

## 六、拍板记录(2026-11-14 定案)

1. **立项:全量实施**。原型迁主仓 `native/`,按桥接契约(§四)实施;Java 解释器完整保留为无 native 兜底回退(加载失败/陌生平台自动降级,两条路径由对拍测试网锁定同语义)。
2. **FFI:手写 JNI**。初选 Panama FFM,经事实核查修正:Java 21 的 FFM 是 **preview**(JEP 442,final 在 Java 22/JEP 454)——`--enable-preview` 必须玩家手动加 JVM 参数(模组无法从内部注入),且 preview 编译的类文件标记会污染整个 jar,其余用户直接 `UnsupportedClassVersionError`。「普遍配备 Java 21」≠「FFM 可用」。JNI 零用户侧要求、全用户受益;每 tick 每节点数次的 downcall 粒度下,JNI 与 FFM 开销差异可忽略。
3. **libm:捆绑 `libm` crate**(纯 Rust fdlibm 移植,与 Java StrictMath 同源)——恢复全逐位对拍(含尘埃带)且跨平台确定,§5.3 的 msvcrt 弱归约问题就此收口。
4. **排期:独立批**,不占 1.3.0 现行批次(cross-be 五波)施工面;主工作树是并发会话施工面,实施批在隔离 worktree 进行。
5. **原型去留:迁主仓**(随 1 落地,分支在实施批合并后归档)。

**实施拆批**(每批独立提交、独立可回退):

- **R1 Tok 编码器:✅ 已落地**(17/17 对拍绿):`src/fdlibm.rs` 移植 + Java 侧 StrictMath 锚定(见 §5.3);Java `FormulaTokEncoder`(AST+RPN→定宽 Tok 字节码,槽位首现序互敦、loop id 前序分配、Fn/Cmp 显式序数表)⇄ Rust `src/bridge.rs` 解码器,格式契约 = bridge.rs 模块头,夹具四脚本(.tokbin+.golden.txt)双端逐位对拍,解码器带 magic/版本/截断/**剩余字节**四重契约检查(编码端首版变宽 payload 即被剩余字节检查当场暴露);carrier 字节序列化往返 + 续算等价测试(与 JNI 形态同构);`native/include/formula_native.h` + Java `FormulaNative` 声明骨架(加载探测 + AVAILABLE 兜底门,不接 FormulaEval)。**待办教训**:assign 互敦漏 RHS 变量(输入钉不进表)= 槽位契约测试当场暴露。
- **R2 构建接线:✅ 已落地**(2026-11-14;cargo test 23/23 绿 + gradle 全套件 1285 测试 0 失败,无 cargo 机器构建仍绿):
  - **Rust cdylib + JNI shim**:`src/ffi.rs` 落 formula_native.h 全部 6 个导出(exec/abiVersion/sessionCreate/sessionDestroy/programUpload/programRelease)。纯 Rust `Session`(可单测,不经 JVM):程序槽位表——释放只置 None 保 programId 稳定、后续上传复用空洞;每程序独占 regs/loop_stack 复用缓冲,引擎路径稳态零分配,挂起路径 carrier_to_bytes 一次分配(与 bridge.rs「分配只发生在挂起时刻」同口径)。exec 按契约映射:programId 查已上传 Program → Exec::new/run;sliceNanos>0 → Budget::slice 否则 unlimited;Done 终值写 outputs(短缓冲 lenient、超槽位数 = -3);Suspended 经 carrierOut 回传字节。JNI 纪律:非法输入一律负错误码(·-1 会话 ·-2 程序 id ·-3 缓冲尺寸 ·-4 carrier 解码 ·-5 字节码解码,programUpload 专用),**绝不 panic 跨 FFI**;JVM 数组经 region API 拷入拷出——胶水每次 exec 两个小 Vec 是过渡逐节点形态的固有开销,§4.5 批量入口切 direct ByteBuffer 时消除。
  - **构建/打包**:`[lib] name = "formula_native"` + `crate-type = ["cdylib","rlib"]`——**lib 名 = 产物名**(formula_native.dll),bin/测试 use 路径同步改名(语义零改动);`jni = "0.21"` 是运行时零原生链接的第二依赖(符号由加载方 JVM 提供)。gradle `buildNative` task:cargo 不在 PATH/CARGO_HOME → warn 跳过绝不 fail 构建;产物复制到 `build/natives/<os>-<arch>/` 并经 processResources 进 jar。os/arch → 目录映射(gradle 与 Java **两端写死一致**,改动必须同步):windows/linux/macos × x86_64(amd64)/aarch64(arm64),库文件名 dll/dylib/so。
  - **加载路径**:FormulaNative 弃 System.loadLibrary → classpath `natives/<os>-<arch>/` 资源提取到 `java.io.tmpdir/csc-formula-natives/<os>-<arch>/`(**存在且尺寸一致则复用**,零重复解压;临时名+原子 move,进程中断不留半截文件冒充完整库)→ System.load 绝对路径;加载失败/abiVersion 不匹配 → AVAILABLE=false 走 Java 解释器兜底。native 声明改包内可见(FormulaEval R3 与 e2e 测试同包调用);`carrierLength` 按 CSCC 自描述头读回挂起写入字节数(exec 只回状态码,长度必须从头部恢复)。
  - **e2e 测试**(`FormulaNativeTest`,assumeTrue 无 native 整类跳、套件保持绿):scalarLoop 终值逐位(acc=4999950.0/i=100000.0);弹道真脚本**全槽位**终值与 Java `FormulaInterpreter.exec` 逐位(期望值测试内现跑 Java 链,不硬编码;输入按名查槽——槽位序=源码首现序≠引脚序);2ms slice 反复挂起续算(carrierOut→carrierIn 直到 Done)终值与不限预算逐位;丢弃路径(programRelease 后 exec = -2)+ 非法会话(-1)负码契约。
- **R3 求值接线**:FormulaEval/FormulaCompute 分流 native/Java、carrier 跨界序列化(字节数组)、deadline 锚点传递(anchor+delta 口径,§4.3)、对拍网扩到全函数表+脚本矩阵(弹道 5 案例 + 预算脚本集全量)。
- **R4 收尾**:CHANGELOG 行、畸形 RPN(括号不闭合)登记册收编(§5.2)、性能验收(弹道 20ms 预算实测 + GC 压力对比)、文档收口。

## 七、复现手册

```bash
# Java 基线(不进 gradle,直接 javac)
javac -encoding UTF-8 -d /tmp/csc-bench-classes -cp slf4j-api.jar \
  src/main/java/.../graph/{FormulaParser,FormulaLexer,FormulaFunctions,FormulaAstParser,
  FormulaInterpreter,FormulaCompute,Value,FormulaAst,FormulaValidator}.java
javac -encoding UTF-8 -d /tmp/csc-bench-classes -cp "/tmp/csc-bench-classes;slf4j-api.jar" bench/java/FormulaAllocBench.java
java -Xms1g -cp "/tmp/csc-bench-classes;slf4j-api.jar" FormulaAllocBench 800 2000

# Rust 原型(工具链:stable-x86_64-pc-windows-gnu,MSVC 机器无 link.exe 时必须)
cd native/formula-core
cargo test --release     # 23/23(13 parity + 3 bridge + 1 trig anchor + 6 ffi/session 单测)
cargo run --release --bin alloc-bench
```
