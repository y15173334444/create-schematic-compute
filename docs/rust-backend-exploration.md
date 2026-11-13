# 公式求值 Rust 后端 · 探索线（csc-rust-backend-explore）

> 状态:**🔬 探索原型已落地、待拍板**(2026-11-14)
> 施工面:worktree `C:/Users/Administrator/csc-rust-backend-wt`,分支 `csc-rust-backend-explore`(自 main 5528caa3 分出)
> 触发:公式节点预算池开到 20ms/tick 时,服务端内存分配率达 1GB/s 级——求值链路的堆分配成为主要代价。
> 产物:`native/formula-core/`(零分配求值核心,9/9 与 Java 链逐位对拍)、`bench/java/`(Java 基线基准)、本文。

---

## 一、结论速览

- **根因坐实**:`FormulaParser.evaluateValue` 的热路径每 token 堆分配——每次调用 `new ArrayDeque<Value>()`、每个常量/运算结果 `new Value.Scalar(d)`、标量函数 `new double[arity]`、向量运算 `new Vec3Val(...)`。实测稳态 **296–520 B/循环迭代**(标量/vec3),预算打满即 ~3.6–4.0 GB/s(详见 §二;用户观测的 1GB/s 对应更轻的脚本构成或 slice 均分稀释)。
- **Rust 后端原型达成目标**:`native/formula-core` 以 tagged 寄存器 + 定长表达式栈 + 显式 carrier 实现同语义求值,**稳态零堆分配**(2s 测量窗 3 次分配 224B,均为首次缓冲扩容),吞吐同量级(vec3 +25%、闭式长表达式 +59%、纯标量热循环 -23%,见 §三)。
- **卖点不是峰值吞吐,是分配与可预测性**:1GB/s 分配 = young gen 高频回收 + 卡表/缓存污染,这才是预算打满时的真实代价;零分配直接消除。且无 JIT 预热/去优化,墙钟预算语义(16 迭代边界 + K6 每 256 ops 采样)逐字保持。
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

对拍测试 `tests/parity.rs` **9/9 绿**,与 javac 直跑的 Java 探针逐位一致:

- scalarLoop `acc=4999950.0, i=100000.0`(逐位);
- vec3Loop `p/v` 六个分量逐位一致(含 `-47999.5000000018` 这类浮点尾数);
- closedForm `y=2.0`(未知函数 lenient 链路的栈顶语义,见 §5.1);
- 续算守恒:25µs slice 反复挂起/续算,终值与不限预算逐位一致(零迭代丢失/重复);while 同;
- MAX_ITER 硬 shed;一元负号 `[0,x,-]`;未知函数 `min(1,2)→2.0 / floor(2.5)→2.5`。

池化基准(2s 测量窗,计数分配器):

| 脚本形态 | 吞吐 | 单迭代耗时 | 测量窗总分配 |
|---|---|---|---|
| 标量热循环 | 10.04M it/s | 99.6ns | **3 次 / 224 B**(首次扩容) |
| vec3 热循环 | 10.05M it/s | 99.5ns | **3 次 / 224 B** |
| 闭式长表达式 | 3.13M 次/s | 320.0ns | **2 次 / 224 B** |

与 Java 对照:**分配 296–520 B/迭代 → 0**;吞吐 vec3 +25%、闭式 +59%、纯标量 -23%(C2 JIT 对紧标量循环优化到位,原型未追这个点)。运行:`cargo run --release -p formula-core --bin alloc-bench`。

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

## 五、语义对齐备忘(探索期实证,增量约束)

### 5.1 lenient 规则族(Java 探针实证,Rust 已逐字复刻)

- 未知函数 `f(args...)`:lexer 只认表内名为 FUNCTION;未知名走 IDENT → 名字当变量压栈,**逗号把分组 `(` 弹进输出** → RPN 形如 `[min, 1, '(', 2]`,求值时 `'('` 吃两项得 0,最终结果 = 栈顶 = 最后参数(实测 `min(1,2)→2.0`、`foo(5)→5.0`);
- 一元负号 = `[0, x, '-']`(0- 技巧);一元 `+` 同口径也走 `-`;
- `PI/E`:ops 栈顶是分组 `(` 才是常量字面量,否则变量引用(`sin(PI)` 的 PI 是变量、出引脚);
- `^` 左结合(`>=` 弹栈);`==/!=` 容差 1e-6;三角函数角度制、定义域外 lenient 0;yaw/pitch 逐字对齐 DIRECTION 节点;
- 除零/模零 → 0;向量非法组合(v×v、标量÷v、% ^)→ 零向量;多分量 swizzle/标量取分量 → 0。

### 5.2 Java 侧顺带发现(建议登记册收编)

- **畸形 RPN 每 tick 异常**:括号不闭合(如 `y = 1 + (2`)能通过解析进入 AST,exec 时抛 `NoSuchElementException`——被 eval 分支 `catch (Exception)` 兜住(输出归零),但坏档/坏编辑形态下是每 tick 异常。建议解析期把「括号不闭合」收编为 ERROR issue(与 P1-6 深度守卫同模式)。本条出自本探索线的对拍准备,归属主仓缺陷面,由用户拍板后进登记册。

## 六、拍板点与建议

1. **是否立项**:建议立项。分配归零解决的是 20ms 预算档位的真实痛点(GC 压力),语义风险已被对拍网压到最低;纯标量峰值吞吐不涨是已知且可接受的。
2. **FFI 形态**:建议手写 JNI 起步(零 preview 依赖、NeoForge 兼容面最小),FFM 留待工具链升级后重估。
3. **排期建议**:不动 1.3.0 现行批次;作为独立批在公式链稳定期落地(桥接 + natives 打包 + 双路径回退 + 对拍网扩到全函数表)。
4. **原型处置**:formula-core 保留在本分支;立项则迁主仓 `native/` 并接 CI(cargo build + 对拍矩阵);否决则只留本文与基准数据。

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
cargo test --release     # 9/9 对拍
cargo run --release --bin alloc-bench
```
