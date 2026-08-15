# FORMULA 语法手册 · Formula Syntax Manual

> 状态:**✅ 已实施**(自 1.2.5 以后,六刀落地:刀1 `4076747` / 刀2 `5865b46` / 刀3 `cbc39de` / 刀4 `f479c1f` / 刀5 `08e8cda` / 刀6 本文档)
> Status: **✅ implemented** (since v1.2.5; six knives landed: k1 `4076747` / k2 `5865b46` / k3 `cbc39de` / k4 `f479c1f` / k5 `08e8cda` / k6 this manual)
> 适用:FORMULA 节点的脚本语言(自 1.2.5 起)。旧脚本(赋值 + 表达式)无需任何修改,逐位兼容。
> Applies to: the FORMULA node's scripting language (since v1.2.5). Legacy scripts (assignments + expressions) work unchanged, bit for bit.
> 决策依据:[`formula-budget-syntax-decisions.md`](formula-budget-syntax-decisions.md)(压力测试收口的权威决策账)
> Decisions: [`formula-budget-syntax-decisions.md`](formula-budget-syntax-decisions.md) (the authoritative decision ledger from the grilling closeout)

---

## 一、总览 / Overview

- **两种模式**:**RPN 模式**(旧脚本:赋值 + 末尾独立表达式)与 **AST 模式**(含控制流关键字、`{}`、swizzle、vec3/向量函数时自动触发)。同一解析器、同一求值路径,无双引擎。
  **Two modes**: **RPN mode** (legacy scripts: assignments + a trailing standalone expression) and **AST mode** (auto-triggered by control-flow keywords, `{}`, swizzle, vec3/vector functions). One parser, one evaluation path — no dual engine.
- **值类型**:标量(scalar)与向量(vec3)。变量无声明,首次赋值即定;类型由保守推断(任何处赋值为向量的变量即 vec3)。
  **Value types**: scalar and vector (vec3). Variables need no declaration — first assignment fixes them; types come from conservative inference (any variable assigned a vector anywhere is a vec3).
- **作用域**:全局扁平,循环/分支不新建作用域。
  **Scope**: flat and global — loops/branches do not create new scopes.
- **角度约定**:三角函数入参、反三角/`yaw`/`pitch` 出参一律**度**(degrees),与 DIRECTION 节点逐字对齐。
  **Angle convention**: trig inputs and inverse-trig/`yaw`/`pitch` outputs are all in **degrees**, matching the DIRECTION node exactly.

---

## 二、变量与输入引脚 / Variables & Input Pins

```
x = a + 1        // 赋值;x 为内部变量,a 成为输入引脚 / assignment; x is internal, a becomes an input pin
v = v * 0.9 + 1  // 内部变量自引用 / internal variable self-reference
```

- **输入引脚规则**:任何地方被赋值的名字 = 内部变量;其余被读取的名字 = 输入引脚(按首次出现顺序排列)。
  **Input-pin rule**: any name assigned anywhere is an internal variable; any other name that is read becomes an input pin (ordered by first appearance).
- **WARN**:循环/分支内被赋值、且同时被读取的变量(如 `repeat { i = i + 1 }` 的 `i`)会得到校验器 WARN——计数器应视为内部变量,但会被引脚收集规则计入读路径。
  **WARN**: a variable assigned inside a loop/branch and also read (like `i` in `repeat { i = i + 1 }`) gets a validator WARN — counters should be internal, but the pin-collection rule counts the read path.
- 引脚名即变量名,连线按稳定 pinId 绑定,变量改名不会移位断线(v1.2.4+ 稳定 pinId)。
  Pin names are variable names; connections bind stable pinIds, so renames never shift or break wires (stable pinId since v1.2.4).
- 注释:`--` 行注释(编辑区行首前缀 §8 深灰)。
  Comments: `--` line comments (deep-grey line prefixes in the edit panel).
- **中文输入即转**:中文/全角符号、字母、数字与空格在输入时实时转换为半角 ASCII——`（）→()`、`×→*`、`≥→>=`、`ｘ→x`、全角空格→半角空格(脚本框与信号发生器公式框共用 `sanitizeFullwidth`)。
  **CJK input converts live**: Chinese/full-width symbols, letters, digits and spaces convert to half-width ASCII as you type — `（）→()`, `×→*`, `≥→>=`, `ｘ→x`, ideographic space→space (shared `sanitizeFullwidth` with the signal generator's formula box).

---

## 三、运算符 / Operators

| 类别 / Category | 运算符 / Operators | 说明 / Notes |
|---|---|---|
| 算术 / Arithmetic | `+` `-` `*` `/` `%` `^` | 除零/模零宽容归 0;`^` = `pow` / div/mod by zero are lenient 0; `^` = `pow` |
| 比较 / Comparison | `<` `>` `<=` `>=` | 精确比较 / exact comparison |
| 相等 / Equality | `==` `!=` | **1e-6 容差**(跨服蓝图结果确定性,容差不进配置)/ **1e-6 tolerance** (cross-server blueprint determinism; the tolerance is not configurable) |
| 逻辑 / Logical | `&&` `\|\|` `!` | 以 `!= 0` 判真 / truthiness is `!= 0` |
| 赋值 / Assignment | `=` | / |
| 行延续 / Line continuation | `\` | 行尾反斜杠续写下一行(编译期跳过)/ trailing backslash continues the next line (skipped at compile time) |

**优先级 / Precedence**:`!` > 比较/相等 > `&&` > `\|\|`(与决策账 §五一致)。
/ `!` > comparison/equality > `&&` > `\|\|` (per decisions §五).

```
(a > 5) && (b != 0)     // 真 = 1.0,假 = 0.0 / true = 1.0, false = 0.0
!x                      // 逻辑非 / logical not
```

---

## 四、控制流 / Control Flow

| 语句 / Statement | 形式 / Form | 说明 / Notes |
|---|---|---|
| `repeat` | `repeat N { ... }` | N 为常量表达式(解析期求值);协作超时的挂起点 / N is a constant expression (evaluated at parse time); the cooperative-timeout suspension point |
| `while` | `while (cond) { ... }` | 条件每轮重求;进度条显示为不定态(-1)/ condition re-evaluated each iteration; the progress bar shows indeterminate (-1) |
| `if` / `else` | `if (cond) { ... } else { ... }` | 可选 else / optional else |
| `break` / `continue` | 循环体内 / inside loops | 顶层 lenient 忽略(校验期已提示)/ leniently ignored at the top level (flagged at validation) |

```
acc = 0
repeat 100 { acc = acc + 1 }
if (acc > 50) { acc = 0 } else { acc = 1 }
while (acc < 10) { acc = acc + 1 }
```

- `;` 作为语句终结符可选支持。 / `;` is optionally supported as a statement terminator.
- 大块脚本在单 tick 内跑不完时由**协作超时**挂起跨 tick 续算(见 §七),语义不变。
  Large scripts that cannot finish within one tick suspend via the **cooperative timeout** and resume across ticks (see §七) — semantics unchanged.

---

## 五、vec3 与向量函数 / vec3 & Vector Functions

**构造 / Construction**:`vec3(x, y, z)`

**分量访问 / Swizzle(初版仅单分量)**:`v.x` `v.y` `v.z`(组合分量如 `v.xy` 推迟)
/ **Swizzle (single component only for now)**: `v.x` `v.y` `v.z` (combined components like `v.xy` are deferred)

**向量运算类型规则 / Vector Op Rules**(歧义不猜,校验期 ERROR / ambiguity is never guessed — validation-time ERROR):

| 运算 / Operation | 规则 / Rule |
|---|---|
| `v ± v` | 逐分量 / component-wise |
| `v ± s`、`v * s`、`v / s` | 标量广播 / scalar broadcast |
| `v * v`、`v / v`、`s / v` | **校验期 ERROR**(dot/cross/length 有专职函数)/ **validation ERROR** (dot/cross/length are the dedicated functions) |
| 向量参与比较/逻辑、函数参数形态不符 | **校验期 ERROR** / **validation ERROR** |
| 运行时兜底 | 宽容归 0(校验期已拦截,兜底不改变语义)/ lenient 0 at runtime (validation has already caught it; the fallback never changes semantics) |

**向量函数 / Vector Functions**:

| 函数 / Function | 参数 / Args | 说明 / Notes |
|---|---|---|
| `vec3(x, y, z)` | 3 标量 / 3 scalars | 构造向量 / construct a vector |
| `length(v)` | vec3 | 模长 / magnitude |
| `normalize(v)` | vec3 | 单位化 / normalize |
| `dot(a, b)` | vec3, vec3 | 点积 / dot product |
| `cross(a, b)` | vec3, vec3 | 叉积 / cross product |
| `dist(a, b)` | vec3, vec3 | 两点距离 / distance between two points |
| `yaw(v)` | vec3 | `degrees(atan2(v.x, −v.z))` 归一化 **[0, 360)** / normalized to **[0, 360)** |
| `pitch(v)` | vec3 | `degrees(atan2(−v.y, √(v.x²+v.z²)))` / as written |

> `yaw`/`pitch` 公式**逐字对齐 DIRECTION 节点**(`GraphEvaluator.java:577-579`),蓝图语义与节点行为一致。
> The `yaw`/`pitch` formulas **mirror the DIRECTION node verbatim** (`GraphEvaluator.java:577-579`) — blueprint semantics match the node's behavior.

**标量函数 / Scalar Functions**(角度均按度 / trig in degrees):`sin cos tan asin acos atan2 sinh cosh sqrt ln log exp sec csc cot`

---

## 六、@output 与引脚模型 / @output & Pin Model

- `@output name` 或 `@output expr`:解析期 hoist,声明位置任意(块内声明 WARN 引导顶层)。
  `@output name` or `@output expr`: hoisted at parse time, any declaration position (inside blocks → WARN guiding to the top level).
- **vec3 输出展开**(刀 4):`@output v`(v 为 vec3)→ 自动展开为 `v.x`/`v.y`/`v.z` **三个标量输出引脚**,下游连线按展开后的标签绑定;展开后超过 16 个输出时超出部分截断(校验期 WARN)。
  **vec3 output expansion** (knife 4): `@output v` (v is vec3) auto-expands into three scalar output pins `v.x`/`v.y`/`v.z`; downstream wires bind the expanded labels; beyond 16 outputs the excess is truncated (validation WARN).
- **默认输出**:未声明 `@output` 时,最后一行独立表达式为默认输出;纯赋值脚本输出恒 0。
  **Default output**: without `@output`, the last standalone expression becomes the default output; assignment-only scripts output constant 0.
- **FORMULA 求值策略参数 `warm`**(刀 5):**无引脚**的编辑区设置(2026-08-15 联调决策)——它是求值时的执行策略而非数据输入,在编辑区以**切换按钮**呈现(两段式:✔ 温启动 / 严格冻结),值存 `params[0]`,点击经 `SET_PARAM` op 同步。脚本文本分享不携带该参数。
  **FORMULA eval-policy param `warm`** (knife 5): a **pinless** edit-panel setting (2026-08-15 session decision) — an evaluation policy, not a data input. Rendered as a segmented toggle (✔ Warm Restart / Strict Freeze), stored in `params[0]`, synced via the `SET_PARAM` op. Sharing the script text does not carry this parameter.

---

## 七、预算与协作挂起 / Budget & Cooperative Suspend

FORMULA 在拓扑位置原地求值;**节点入口永远准入(保底),循环边界按配额 yield**。无中央队列、无 1-tick 延迟。
FORMULA evaluates in place at its topological position; **node entry is always admitted (guaranteed service), loop boundaries yield by quota**. No central queue, no 1-tick latency.

| 机制 / Mechanism | 行为 / Behavior |
|---|---|
| slice | `formulaBudgetMs / max(1, N_heavy_prev)`,N_heavy = 上一 tick 实际 yield 的公式节点数;轻节点永不 yield、不占分母 / N_heavy = formula nodes that actually yielded last tick; light nodes never yield and don't dilute the denominator |
| 协作超时 | 每 16 迭代测一次墙钟;超 slice 在**循环边界**挂起,保存 carrier(循环栈计数 + Env 快照)/ wall clock checked every 16 iterations; past the slice, suspend at the **loop boundary** and save a carrier (loop-stack counters + Env snapshot) |
| 续算(寻径执行) | 下 tick 恢复 Env 快照,跳过已快照化的前缀语句直达挂起循环,从计数续轮——前缀不重跑、迭代不丢失 / next tick restores the Env snapshot, seeks past the snapshotted prefix straight to the suspended loop, and resumes from the counters — prefixes don't re-run, iterations are never lost |
| emit-on-done | spread 期间输出冻结为上一轮完整解,**只有 done 才写入新值**(半收敛解永不流出)/ outputs freeze at the last complete solution during the spread; **only done writes new values** (half-converged solutions never leak) |
| 输入冻结(默认) | spread 期间按首 tick 输入快照续算;每引脚 `\|新值 − 冻结值\| > 1e-3` 判变 / the spread continues on the first-tick input snapshot; per-pin `\|new − frozen\| > 1e-3` counts as a change |
| 温启动(`warm=1`,opt-in) | 输入变更时刷新输入变量与冻结快照、**保留循环进度继续迭代**——求解器始终朝当前目标,进度条不重置;持续变化输入(信号发生器等)不重启 / on input change, refresh the input vars and the freeze snapshot and **keep iterating with the current loop progress** — the solver always tracks the current target, the bar never resets, and continuously-changing inputs (signal generator etc.) don't restart |
| done 跳过 / 冷复位 | done 且输入未变:整节点跳过(输出保持);done 且输入变更:冷复位重算(两模式相同)/ done + unchanged inputs: skip the node (outputs hold); done + changed inputs: cold reset and recompute (same in both modes) |
| MAX_ITER | 1M,按 spread 跨 tick 累计;超限 → lastGood + WARN 一次,**shed 冻结**直到公式被编辑 / 1M, accumulated spread-wide; exceeded → lastGood + one WARN, **shed-freeze** until the formula is edited |
| 去重 dedup | (脚本, 输入)本 tick 内去重;carrier 在 done 边界冷复位,保持纯函数性 / per-tick dedup on (script, inputs); the carrier cold-resets at the done boundary, preserving purity |
| 渲染态进度条 | 节点下方细进度条(无数值):0..1 定长 repeat 进度,-1 不定(while 呼吸式)/ a thin bar below the node (no values): 0..1 progress for bounded repeats, -1 indeterminate (breathing fill) for while |
| 配置 | `formulaBudgetMs`(ModConfigSpec,SERVER,默认 3.0,范围 0.5–20);语义常数(1e-6 容差、MAX_ITER、角度约定)硬编码不进配置 / `formulaBudgetMs` (ModConfigSpec, SERVER, default 3.0, range 0.5–20); semantic constants (1e-6 tolerance, MAX_ITER, angle convention) stay hardcoded — configuring them would break cross-server determinism |

**carrier 生命周期**:仅活在「被中断的 spread」期间,done 即冷复位——`repeat { x = x + step }` 类累加脚本与升级前行为逐位一致;transient 不进 NBT(chunk 卸载自然消亡),`graphGeneration`/公式文本变更即作废。
**Carrier lifetime**: lives only during an interrupted spread; done cold-resets it — accumulator scripts like `repeat { x = x + step }` behave bit-identically to before the upgrade; transient (never in NBT, dies naturally on chunk unload); invalidated by `graphGeneration`/formula-text changes.

---

## 八、兼容性 / Compatibility

- 旧脚本(赋值 + 独立表达式)**文本/NBT/引脚全部不动**,只换求值路径(RPN 与 AST 走同一 Value 栈机),逐位不变。
  Legacy scripts (assignments + standalone expression) keep their **text/NBT/pins untouched** — only the evaluation path changes (RPN and AST share one Value stack machine), bit-identical results.
- legacy 额外引脚(dynamicInputCount > inputVars.size() 的旧存档)保留数字 pinId,与参数引脚互不相撞(参数引脚以 `functionalInputs()` 为基数)。
  Legacy extra pins (old saves with dynamicInputCount > inputVars.size()) keep numeric pinIds and can never collide with param pins (param pins are based at `functionalInputs()`).

---

> 关联文档:[`formula-budget-syntax-decisions.md`](formula-budget-syntax-decisions.md)(决策账)、[`code-architecture.md`](code-architecture.md)(求值模型章节)、[`formula-pin-render-tech-debt.md`](formula-pin-render-tech-debt.md)(引脚模型历史背景)、README changelog v1.2.5(权威变更日志)。
> Related: [`formula-budget-syntax-decisions.md`](formula-budget-syntax-decisions.md) (decision ledger), [`code-architecture.md`](code-architecture.md) (evaluation-model chapter), [`formula-pin-render-tech-debt.md`](formula-pin-render-tech-debt.md) (pin-model history), README changelog v1.2.5 (authoritative changelog).
