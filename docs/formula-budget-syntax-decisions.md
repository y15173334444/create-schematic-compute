# 公式预算池 + 语法升级 · 决策记录(压力测试收口)

> 状态:**✅ 已实施**(2026-08-14 grilling 决策锁定;2026-08-15 六刀全部落地,实施记录见 §十)
> 对照代码基线:`HEAD = 5395c50`(v1.2.5);实施收口于 v1.2.6
> 来源方案(WorkBuddy,仓库外):
>
> - `formula-budget-complete-plan.md`(方案 C:统一 FORMULA 预算池)
> - `formula-syntax-upgrade.md`(Phase 3:vec3 + 控制流语法)
>
> 本文是两份来源方案经压力测试后的**权威决策账**;与来源方案的差异清单见 §七。落地提交拆分见 §六,验证清单见 §八。

---

## 一、结论速览

- **调度架构:内联门控**。FORMULA 留在拓扑位置原地求值,节点入口永远准入(保底),循环边界按配额 yield。**不做**中央队列(文档 §5.8)、**不做**全中央 tick 调度(方案 A)——二者均引入无条件 1-tick 延迟或全体错位(§二)。
- **公平:保底配额 + 自适应均分**。`slice = 3ms / N_heavy_prev`(上一 tick 实际 yield 的重节点数),任何节点任何负载下每 tick 必被服务,**零饿死**;无重排、无回流记账。
- **carrier(挂起-lite)**:循环边界快照 `{Env, 循环计数}`,引擎隐式,**不需要作者显式读写**;输入变更温启动重启(节点参数 opt-in,默认冻结)。
- **输出策略:emit-on-done** + 渲染态进度条(无数值)。
- **语法引擎:单 Value 栈机 + 语句解释器 + Stmt/RPN 混合 AST**;旧脚本/legacy 无感升级,无双引擎、无迁移。
- **配置系统:`ModConfigSpec`** 随刀 1 落地(后续设置面板/调试面板的基建),只装时序/资源旋钮,语义常数硬编码。

## 二、架构决策:内联门控(为什么否掉队列)

**核心矛盾(代码核实)**:中央优先队列与「同 tick 新鲜」互斥。

- `enqueue` 发生在各 BE 拓扑 walk 内(`GraphEvaluator.eval` 的 `case FORMULA`,`:834-880`);drainQueue 最早只能挂在**全部 7 个 BE 走完之后**(文档 §5.1 把 drain 画在 BE walk 之前,队列为空,不可实现)。
- BE 在 tick 内**当场消费**求值结果:`MonitorBlockEntity.tick()` `:56-64`(evaluate → writeOutputs → broadcastEvalSnapshot)、`SpeedProxyBlockEntity.java:49`(getNodeOutput)。
- pass1 期间 `outputs.put(F, F.outputValues.clone())`(`GraphEvaluator.java:980`)写入上一 tick 值;下游读 `NodeGraph.getInputValue`(`:229-236`)命中即取到旧值。

⇒ 中央队列下**所有 FORMULA→非FORMULA 边无条件、每 tick、永久 +1 tick(50ms)**,文档「零 A 类回归」不成立,验证清单「逐 tick 一致」必然失败。链式传播(FORMULA→GT→REDSTONE_OUT)使 per-BE drain 等补救均无效——唯一解法是 FORMULA 在拓扑位置上、消费它的节点之前完成计算 = 内联。

**方案 A(全中央)更差**:Pre 集中求值 → 全体输入错位 1 tick(REDSTONE_IN 红石传播、TARGET_OUT 雷达分配、座椅输入都在 tick 内更新);Post 集中求值 → 全体输出迟到。且其公平收益对状态节点无效(PID/DELAY/INTEGRATOR 不可重排),纯函数节点又不需要公平 ⇒ 公平增量 ≈0、回归面最大。**否决不变。**

**内联的饿死缺口如何闭合**:轻节点永远被服务(配额封顶恶霸);图内老化由**优先级感知拓扑线性化**实现(同一层 FORMULA 相互独立,顺序自由,按 starveTicks 排序弹出)——无需队列;跨 BE 公平由保底配额保证(§三)。中央队列的 1-tick 税从此买不到任何东西,内联严格支配。

## 三、预算账本

```
slice = budgetMs / max(1, N_heavy_prev)        -- N_heavy = 上一 tick 实际 yield 的公式节点数
节点入口:永远准入(保底)                          -- 不查 deadline
循环边界:墙钟检查 elapsed > slice → yield 存 carrier -- 每 16 迭代检查一次
全局 deadline:纯兜底(闭式巨脚本的紧急 shed)
```

- **保底语义(非上限)**:每个节点每 tick 必然被服务——饿死在定义上不可能。区分:配额是「保底可用时间片」,不是入口 gate。
- **自适应均分**:按「实际 yield 的重节点数」而非「全部公式节点数」切——轻节点 µs 级跑完、从不 yield、不占分母;空图/分布不均自然消解(计数按节点不按图);无回流池(预算天然守恒);重节点逐一收敛退出计数,剩余者自动加份额。
- **溢出治理**:墙钟检查(每 16 迭代,溢出 ≤16×循环体成本)替代文档的 `CHECK_EVERY=128` 迭代计数检查;原子迭代保证节点每 tick 至少完成 1 轮。
- **统计滞后一 tick**:`N_heavy` 为上一 tick 实测值;粘贴新重图的首 tick 有尖峰——与现状(新图全量求值)相同,非回归。
- **配置**(`ModConfigSpec`,SERVER 类型):`formulaBudgetMs`(默认 3.0)、slice 下限(可选)。**语义常数硬编码**:`==` 容差 1e-6、MAX_ITER(1M)、角度约定——进配置会破坏跨服蓝图结果确定性。
- **dedup**:(脚本, 输入)去重,纯函数安全;carrier 在 done 边界冷复位,保持节点纯函数性,dedup 键语义成立。

## 四、carrier 设计(挂起-lite)

- **存储**:`GraphNode` 上 transient 字段,不写 NBT(chunk 卸载自然消亡);`graphGeneration`/`sourceFormula` 变化即作废。
- **yield 点与快照**:只在**循环边界**(每轮迭代之间);carrier = `{循环栈计数, Env 快照}`;Env 即变量表,几十项 clone 一次 µs 级。
- **恢复**:下 tick 解释器从脚本头重跑(闭式前缀重算,输入冻结故结果恒等、纯浪费),到带 carrier 的循环处恢复 Env、从计数续轮。
- **输入冻结与变更检测**:spread 期间按首 tick 快照的输入续算;每 tick 入口对比,每引脚 `|新值 − 冻结值| > 1e-3` 判变。
- **输入变更策略 = 温启动(节点参数,默认冻结)**:变更时用上一轮 Env 作初值、按新输入重新冻结、k=0 重跑(在线不动点迭代,求解器始终朝当前目标;牛顿类 1–2 轮回到邻域)。**作用域限定**:carrier 只活在「被中断的 spread」期间,done 即冷复位——防止 `repeat { x = x + step }` 类累加脚本变成跨 tick 有状态构造、破坏纯函数前提。正常负载(循环一 tick 跑完)两策略行为相同,配置只在过载路径生效。
  - 参数形态:**节点参数**而非脚本指令(执行策略属节点配置,与 ROUND 小数位/PID 系数同层);默认冻结(文档原意、新机制不改变行为),温启动 opt-in。注意:脚本文本分享不携带该参数,README 工具提示说明。
  - **实施注意**:FORMULA 的参数引脚必须排在**动态变量引脚之后**——通用参数覆盖机制 `extraBase = node.type.inputs`(`GraphEvaluator.java:366-377`)对 FORMULA 是 0,会与变量引脚撞索引;`inputPinIndex` 解析同步处理(刀 4/5 联动)。
- **emit-on-done**:spread 期间输出冻结为上一轮完整解,只有 done 才写入新值——半收敛解(牛顿过冲)永不流出;「计算中」可观测性用**渲染态进度条**(无数值),EvalSnapshot 增加 spread 状态字段。
- **MAX_ITER**:1M 兜底,超限 → lastGood + WARN 日志。

## 五、语法语义决策

- **引擎结构**:单 `Value` 栈机(栈 `ArrayDeque<Value>`)+ 语句级解释器 + **Stmt 树 + RPN 叶子**(非文档 §4.1 的 Expr 树)。表达式语义全模组唯一一份(`applyFunction` 一条路),旧脚本/legacy 无感升级(文本/NBT/引脚不动,只换求值路径),无双引擎漂移。
- **比较/逻辑**:`< > <= >=` 精确;`==`/`!=` 容差 **1e-6**;`&& ||` 以 `!=0` 判真;优先级 `!` > 比较 > `&&` > `||`。
- **作用域**:全局扁平,循环/分支不新建作用域(与现状 `vars` 一致)。
- **inputVars 收集**:规则不变(任何地方被赋值 = 内部变量);validator 对「分支/循环内赋值的变量同时被读」给 WARN。不改「先读路径存在则算输入」——会把 `repeat { i = i + 1 }` 的计数器全误判成引脚。
- **`@output`**:保持解析期 hoist,块内声明 WARN 引导顶层;vec3 输出展开为 3 标量引脚(刀 4)。
- **vec3 约定**:
  - `yaw(v) = degrees(atan2(v.x, −v.z))` 归一化 `[0, 360)`;`pitch(v) = degrees(atan2(−v.y, √(v.x²+v.z²)))`——**逐字对齐 DIRECTION 节点**(`GraphEvaluator.java:577-579`)。
  - swizzle 初版仅单分量 `v.x/y/z`;组合分量(`v.xy`)推迟(vec2 类型不存在,返回形状是纯负担)。
  - 向量运算类型歧义规则:**`v±v` 逐分量;`v±标量`/`v×标量`/`v÷标量` 广播;`v×v`、`v÷v`、`标量÷v`、向量参与比较/逻辑、函数参数形态不符 → 校验期 ERROR**(歧义不猜,dot/cross/length 有专职函数,逐分量除有 swizzle+构造器逃生口)。运行时兜底仍按现状哲学宽容归 0。

## 六、提交拆分(六刀)

| 刀 | 内容 | 独立价值 |
|---|---|---|
| 1 | **ModConfigSpec 配置系统** + `FormulaCompute` 门面 + 内联门控 + dedup + slice 自适应 | 对现有 RPN 脚本即刻生效:有界延迟 + 去重,零语法改动 |
| 2 | Value 栈 + tokenizer 扩展(比较/逻辑/`vec3`/swizzle/`{}`/关键字)+ 编辑器补全/高亮/校验 | 词法层,旧脚本逐位不变 |
| 3 | 语句解析器 + Stmt/RPN 混合 AST + 类型推断 + 解释器 | `if`/`while`/`repeat` 可执行 |
| 4 | 引脚模型:vec3 输出展开 3 标量引脚、outputPinIndex/inputPinIndex 顺延、dynamicOutputCount 同步、16 上限处理、FORMULA 参数引脚避让 | vec3 对连线模型唯一侵入点闭环 |
| 5 | 协作超时 + carrier + 温启动节点参数 + emit-on-done + 进度条渲染态(EvalSnapshot spread 字段) | 预算闭环,重节点分摊落地 |
| 6 | 文档:语法手册 + README changelog + 实施记录 | 收口 |

每刀 `./gradlew test` + 联网 `gradle build`;文档与代码分开提交。

## 七、与来源方案的差异清单(收录时按此改写)

| 来源方案内容 | 决策差异 |
|---|---|
| §5.8 中央优先队列(SJF/老化/回流) | **不做**——1-tick 延迟(§二);公平由保底配额 + 图内优先级拓扑线性化实现 |
| §5.8 伪代码(回流记账反向、estCost 首版无来源、drainQueue 时序矛盾) | 随队列一并作废 |
| §6.2 emit-on-done vs emit-improving 二选 | 锁定 emit-on-done + 渲染态进度条 |
| §6.5「脚本须自己读回 carrier(作者责任)」 | 作废——引擎隐式(循环边界 Env 快照),无需语法支撑 |
| §6.5 输入冻结 | 改为:spread 期间冻结;变更 → 温启动重启(节点参数 opt-in,默认冻结) |
| §4.1 AST(Expr 树)/ §4.2 双路径(ast==null 走旧 evaluate) | 改 Stmt 树 + RPN 叶子、单 Value 栈机统一 |
| §4.3 `CHECK_EVERY=128` 迭代计数检查 | 改墙钟检查、每 16 迭代、查 slice |
| §九风险 4:`==` 容差 1e-9 建议 | 锁定 1e-6 |
| §九风险 7:向量运算歧义(未决) | 已决:向量对向量报错、标量广播、歧义不猜(§五) |
| §3.7 swizzle 组合分量 / yaw/pitch 公式未给 | swizzle 初版仅单分量;yaw/pitch 对齐 DIRECTION 节点、yaw ∈ [0,360) |
| §3.8-2 「`1.5` 会被拆成 `1`+`.`+`5`」 | **依据有误**——现有 tokenizer 数字分支已吞 `.`(`FormulaParser.java:197-199`),缺口只有 `v.x`;结论(需 swizzle token)不变 |
| §八 Step 0-3 提交拆分 | 替换为六刀(新增配置系统刀、引脚模型刀) |
| §十二「接入 Config.formulaBudgetMs」 | 仓库无配置系统 → 随刀 1 新建 ModConfigSpec;语义常数不配置化 |
| 文档基线 5892caa / SchematicCompute `:185-192` | 现基线 `5395c50`;Post 监听现 `SchematicCompute.java:167-174` |

## 八、验证清单(承接来源方案 §十,按新决策修订)

1. 联网 `gradle build`;`./gradlew test` 全绿。
2. **向后兼容**:现有含公式图逐 tick 输出与升级前一致(引擎统一后,标量脚本逐位不变)。
3. **保底零饿死**:构造 3 重 + 20 轻混合图 → 轻节点每 tick 全部新鲜;构造 20 重节点同图 → 每个每 tick 至少推进 1 轮迭代(slice=150µs)。
4. **自适应 slice**:`N_heavy_prev` 计数正确;重节点收敛退出后剩余节点份额增长。
5. **墙钟溢出**:单个重节点实际耗时 ≤ slice + 16×循环体成本;压测 15 重节点合计 ≤ 3ms + 兜底边界。
6. **dedup**:同脚本+同输入两节点,缓存命中一次,数组隔离。
7. **emit-on-done**:spread 期间输出冻结;done 后跳变到新值;渲染态进度条随 EvalSnapshot 同步。
8. **温启动**:参数开启的节点,输入变更后从上一轮 Env 续算(对比冷启动少迭代);参数关闭(默认)的节点,变更后完成旧快照解。
9. **carrier 生命周期**:done 冷复位(累加器脚本 `repeat { x = x + step }` 与升级前行为一致);generation/文本变更作废;存档不含 carrier。
10. **语法**:`repeat`/`while`/`if`/`break`/`continue` 正确;`==` 1e-6 容差生效;`(a>b)&&(c!=0)` 判真正确。
11. **引脚模型**:`@output v`(vec3)展开 3 标量引脚且 outputPinIndex 顺延一致;16 上限行为明确;FORMULA 参数引脚不撞动态变量引脚。
12. **MAX_ITER**:`while(true)` 被协作超时 shed + 计数上限兜底,整 tick 不卡死。

## 九、实施注意事项(代码核实过的坑)

- FORMULA 参数引脚索引避让(§四)必须与刀 4 引脚模型同刀验证。
- `dynamicOutputCount` 16 上限(`GraphNode.java:106`)与 vec3 展开的交互:展开后超 16 是截断还是提上限,刀 4 实施时定。
- 图内优先级拓扑线性化:同一层 FORMULA 按 `starveTicks` 排序弹出,保持拓扑合法;每 tick 每图 O(V log V)。
- `starveTicks` 在保底制下仅作观测计数(无 shed 场景时恒 0),保留字段供未来调试面板。
- 服务端优雅关闭约定(CLAUDE.md)与本次改动无关,联调验证流程不变。

## 十、实施记录 / Implementation Record

**六刀落地(v1.2.6)**:

| 刀 | 提交 | 内容 | 验证 |
|---|---|---|---|
| 1 | `4076747` | ModConfigSpec(`formulaBudgetMs`) + `FormulaCompute` 门面 + tick 级 dedup | ✅ `FormulaComputeTest` |
| 2 | `5865b46` | Value 栈 + tokenizer 扩展(比较/逻辑/vec3/swizzle/`{}`/关键字)+ 编辑器补全/高亮 | ✅ `FormulaParserSyntaxTest` |
| 3 | `cbc39de` | 语句解析器 + Stmt/RPN 混合 AST + 类型推断 + `FormulaInterpreter` | ✅ `FormulaInterpreterTest` |
| 4 | `f479c1f` | vec3 输出展开 3 标量引脚、pinId 顺延、16 上限、FORMULA 参数引脚避让 | ✅ `FormulaPinExpansionTest` |
| 5 | `08e8cda` | 协作超时 + carrier + 温启动参数 + emit-on-done + 进度条渲染态 | ✅ `FormulaBudgetSpreadTest`(7 个失败修复:循环边界推进时序、CONVERGE 脚本 @output、warm 引脚基数 `functionalInputs()`) |
| 6 | 本文档批次 | 语法手册 + README changelog + 架构文档同步 | ✅ 文档 |

**验证清单(§八)状态**:条目 1 完成(`./gradlew test` 235 绿 + 联网 `gradle build` 通过)。条目 2–12 中由单元测试覆盖的部分(向后兼容、挂起/续算不丢迭代、emit-on-done、冻结/温启动、done 冷复位、shed 冻结、语法、引脚模型)已通过;需游戏内验证的部分(保底零饿死的真实负载行为、进度条渲染效果、多人联调)按既有工作流由人工在游戏窗口内验证。

**与来源方案差异(§七)全部按决策落地**;来源方案文档未收录进仓库(差异清单 §七即收录依据,实施细节以本账与语法手册为准)。

---

> 关联文档:[`docs/formula-syntax-manual.md`](formula-syntax-manual.md)(语法手册)、[`docs/code-architecture.md`](code-architecture.md)(求值模型章节已同步)、[`docs/formula-pin-render-tech-debt.md`](formula-pin-render-tech-debt.md)(引脚模型历史背景)。
