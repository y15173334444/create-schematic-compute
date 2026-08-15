# FORMULA 语法手册 · Formula Syntax Manual

> 状态:**✅ 已实施**(v1.2.6,六刀落地:刀1 `4076747` / 刀2 `5865b46` / 刀3 `cbc39de` / 刀4 `f479c1f` / 刀5 `08e8cda` / 刀6 本文档)
> 适用:FOMULA 节点的脚本语言(v1.2.6 起)。旧脚本(赋值 + 表达式)无需任何修改,逐位兼容。
> 决策依据:[`formula-budget-syntax-decisions.md`](formula-budget-syntax-decisions.md)(压力测试收口的权威决策账)

---

## 一、总览 / Overview

- **两种模式**:**RPN 模式**(旧脚本:赋值 + 末尾独立表达式)与 **AST 模式**(含控制流关键字、`{}`、swizzle、vec3/向量函数时自动触发)。同一解析器、同一求值路径,无双引擎。
- **值类型**:标量(scalar)与向量(vec3)。变量无声明,首次赋值即定;类型由保守推断(任何处赋值为向量的变量即 vec3)。
- **作用域**:全局扁平,循环/分支不新建作用域。
- **角度约定**:三角函数入参、反三角/`yaw`/`pitch` 出参一律**度**(degrees),与 DIRECTION 节点逐字对齐。

---

## 二、变量与输入引脚 / Variables & Input Pins

```
x = a + 1        // 赋值;x 为内部变量,a 成为输入引脚
v = v * 0.9 + 1  // 内部变量自引用
```

- **输入引脚规则**:任何地方被赋值的名字 = 内部变量;其余被读取的名字 = 输入引脚(按首次出现顺序排列)。
- **WARN**:循环/分支内被赋值、且同时被读取的变量(如 `repeat { i = i + 1 }` 的 `i`)会得到校验器 WARN——计数器应视为内部变量,但会被引脚收集规则计入读路径。
- 引脚名即变量名,连线按稳定 pinId 绑定,变量改名不会移位断线(v1.2.4+ 稳定 pinId)。
- 注释:`--` 行注释(编辑区行首前缀 §8 深灰)。

---

## 三、运算符 / Operators

| 类别 / Category | 运算符 / Operators | 说明 / Notes |
|---|---|---|
| 算术 / Arithmetic | `+` `-` `*` `/` `%` `^` | 除零/模零宽容归 0;`^` = `pow` |
| 比较 / Comparison | `<` `>` `<=` `>=` | 精确比较 |
| 相等 / Equality | `==` `!=` | **1e-6 容差**(跨服蓝图结果确定性,容差不进配置) |
| 逻辑 / Logical | `&&` `\|\|` `!` | 以 `!= 0` 判真 |
| 赋值 / Assignment | `=` | |
| 行延续 / Line continuation | `\` | 行尾反斜杠续写下一行(编译期跳过) |

**优先级 / Precedence**:`!` > 比较/相等 > `&&` > `\|\|`(与决策账 §五一致)。

```
(a > 5) && (b != 0)     // 真 = 1.0,假 = 0.0
!x                      // 逻辑非
```

---

## 四、控制流 / Control Flow

| 语句 / Statement | 形式 / Form | 说明 / Notes |
|---|---|---|
| `repeat` | `repeat N { ... }` | N 为常量表达式(解析期求值);协作超时的挂起点 |
| `while` | `while (cond) { ... }` | 条件每轮重求;进度条显示为不定态(-1) |
| `if` / `else` | `if (cond) { ... } else { ... }` | 可选 else |
| `break` / `continue` | 循环体内 | 顶层 lenient 忽略(校验期已提示) |

```
acc = 0
repeat 100 { acc = acc + 1 }
if (acc > 50) { acc = 0 } else { acc = 1 }
while (acc < 10) { acc = acc + 1 }
```

- `;` 作为语句终结符可选支持。
- 大块脚本在单 tick 内跑不完时由**协作超时**挂起跨 tick 续算(见 §七),语义不变。

---

## 五、vec3 与向量函数 / vec3 & Vector Functions

**构造 / Construction**:`vec3(x, y, z)`

**分量访问 / Swizzle(初版仅单分量)**:`v.x` `v.y` `v.z`(组合分量如 `v.xy` 推迟)

**向量运算类型规则 / Vector Op Rules**(歧义不猜,校验期 ERROR):

| 运算 / Operation | 规则 / Rule |
|---|---|
| `v ± v` | 逐分量 |
| `v ± s`、`v * s`、`v / s` | 标量广播 |
| `v * v`、`v / v`、`s / v` | **校验期 ERROR**(dot/cross/length 有专职函数) |
| 向量参与比较/逻辑、函数参数形态不符 | **校验期 ERROR** |
| 运行时兜底 | 宽容归 0(校验期已拦截,兜底不改变语义) |

**向量函数 / Vector Functions**:

| 函数 / Function | 参数 / Args | 说明 / Notes |
|---|---|---|
| `vec3(x, y, z)` | 3 标量 | 构造向量 |
| `length(v)` | vec3 | 模长 |
| `normalize(v)` | vec3 | 单位化 |
| `dot(a, b)` | vec3, vec3 | 点积 |
| `cross(a, b)` | vec3, vec3 | 叉积 |
| `dist(a, b)` | vec3, vec3 | 两点距离 |
| `yaw(v)` | vec3 | `degrees(atan2(v.x, −v.z))` 归一化 **[0, 360)** |
| `pitch(v)` | vec3 | `degrees(atan2(−v.y, √(v.x²+v.z²)))` |

> `yaw`/`pitch` 公式**逐字对齐 DIRECTION 节点**(`GraphEvaluator.java:577-579`),蓝图语义与节点行为一致。

**标量函数 / Scalar Functions**(角度均按度):`sin cos tan asin acos atan2 sinh cosh sqrt ln log exp sec csc cot`

---

## 六、@output 与引脚模型 / @output & Pin Model

- `@output name` 或 `@output expr`:解析期 hoist,声明位置任意(块内声明 WARN 引导顶层)。
- **vec3 输出展开**(刀 4):`@output v`(v 为 vec3)→ 自动展开为 `v.x`/`v.y`/`v.z` **三个标量输出引脚**,下游连线按展开后的标签绑定;展开后超过 16 个输出时超出部分截断(校验期 WARN)。
- **默认输出**:未声明 `@output` 时,最后一行独立表达式为默认输出;纯赋值脚本输出恒 0。
- **FORMULA 求值策略参数 `warm`**(刀 5):**无引脚**的编辑区设置(2026-08-15 联调决策)——它是求值时的执行策略而非数据输入,在编辑区以**切换按钮**呈现(GATE 同款:✔ 温启动 / 严格冻结),值存 `params[0]`,点击经 `TOGGLE_BOOL` op 同步。脚本文本分享不携带该参数。

---

## 七、预算与协作挂起 / Budget & Cooperative Suspend

FORMULA 在拓扑位置原地求值;**节点入口永远准入(保底),循环边界按配额 yield**。无中央队列、无 1-tick 延迟。

| 机制 / Mechanism | 行为 / Behavior |
|---|---|
| slice | `formulaBudgetMs / max(1, N_heavy_prev)`,N_heavy = 上一 tick 实际 yield 的公式节点数;轻节点永不 yield、不占分母 |
| 协作超时 | 每 16 迭代测一次墙钟;超 slice 在**循环边界**挂起,保存 carrier(循环栈计数 + Env 快照) |
| 续算(寻径执行) | 下 tick 恢复 Env 快照,跳过已快照化的前缀语句直达挂起循环,从计数续轮——前缀不重跑、迭代不丢失 |
| emit-on-done | spread 期间输出冻结为上一轮完整解,**只有 done 才写入新值**(半收敛解永不流出) |
| 输入冻结(默认) | spread 期间按首 tick 输入快照续算;每引脚 `\|新值 − 冻结值\| > 1e-3` 判变 |
| 温启动(`warm=1`,opt-in) | 输入变更时刷新输入变量与冻结快照、**保留循环进度继续迭代**——求解器始终朝当前目标,进度条不重置;持续变化输入(信号发生器等)不重启 |
| done 跳过 / 冷复位 | done 且输入未变:整节点跳过(输出保持);done 且输入变更:冷复位重算(两模式相同) |
| MAX_ITER | 1M,按 spread 跨 tick 累计;超限 → lastGood + WARN 一次,**shed 冻结**直到公式被编辑 |
| 去重 dedup | (脚本, 输入)本 tick 内去重;carrier 在 done 边界冷复位,保持纯函数性 |
| 渲染态进度条 | 节点下方细进度条(无数值):0..1 定长 repeat 进度,-1 不定(while 呼吸式) |
| 配置 | `formulaBudgetMs`(ModConfigSpec,SERVER,默认 3.0,范围 0.5–20);语义常数(1e-6 容差、MAX_ITER、角度约定)硬编码不进配置 |

**carrier 生命周期**:仅活在「被中断的 spread」期间,done 即冷复位——`repeat { x = x + step }` 类累加脚本与升级前行为逐位一致;transient 不进 NBT(chunk 卸载自然消亡),`graphGeneration`/公式文本变更即作废。

---

## 八、兼容性 / Compatibility

- 旧脚本(赋值 + 独立表达式)**文本/NBT/引脚全部不动**,只换求值路径(RPN 与 AST 走同一 Value 栈机),逐位不变。
- legacy 额外引脚(dynamicInputCount > inputVars.size() 的旧存档)保留数字 pinId,与参数引脚互不相撞(参数引脚以 `functionalInputs()` 为基数)。

---

> 关联文档:[`formula-budget-syntax-decisions.md`](formula-budget-syntax-decisions.md)(决策账)、[`code-architecture.md`](code-architecture.md)(求值模型章节)、[`formula-pin-render-tech-debt.md`](formula-pin-render-tech-debt.md)(引脚模型历史背景)、README changelog v1.2.6(权威变更日志)。
