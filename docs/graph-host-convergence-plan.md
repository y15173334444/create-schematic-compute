# 图宿主收敛重构 · 待办文档 / Graph Host Convergence — TODO

> **目标**：统一成一个 BE 形态 = **两条继承线共享同一个 GraphHostCore 引擎 + 同一个
> GraphBlockEntity 契约，各自只剩薄壳和类型钩子**。
> **状态**：✅ **全部完成并归档**（2026-08-29 立项并完工）—— 阶段 0 对齐分叉、
> 阶段 1 状态收敛（合并上提 / Radar 重复加载删除 / 回弹收敛 / 字段委托）、
> 阶段 2 契约收敛（GraphHostOwner 并入 GraphBlockEntity、外部零具体类型依赖、
> 委托桥 @Deprecated + 迁移指南）、阶段 3 薄壳化验收与清理（三覆写删除/收缩 +
> flipflop 差分孪生块上提，两处分叉缺陷一并修复并披露）。337 例全绿；双客户端
> 联机 + 服务端重启实测通过。引擎：`GraphHost`（沿用类名）；契约：
> `GraphBlockEntity`（唯一）。后续按 BE 逐个直连引擎、删除 @Deprecated 桥属
> 日常维护（§四阶段 3 注记），不再以本文件跟踪。
> 基线分支 `docs/programmable-gearbox`（`cfd1c5b`+），阶段 0 落地于 `main`。
> **背景**：`GraphHost`（466 行）是从 `SyncedGraphBlockEntity` 移植的组合引擎，专为挂在
> Create `KineticBlockEntity` 继承线上的方块实体服务（Java 单继承冲突，见
> `programmable-gearbox-plan.md` §一.7"收敛重构按计划押后"）。两线并存至今，已出现实际分叉。

---

## 一、核心结论 / Core Conclusions

1. **唯一引擎**：`GraphHost` 升级为 `GraphHostCore`（可沿用类名），承载全部托管职责——
   图状态 / 求值器重编译 / RuntimeState / BUS 生命周期 / NBT 读写 / 多人全量同步 /
   求值快照广播。任何 BE 不得自持这些字段。
2. **唯一契约**：`GraphBlockEntity` 是包（BlueprintSave/Toggle）、屏幕（AbstractGraphScreen
   系）、渲染（快照读取）认的**唯一接口**；`GraphHostOwner` 并入或明确为其服务端宿主侧
   子契约（回调 5 方法：asBlockEntity/getLevel/getBlockPos/setChanged/sendBlockUpdated）。
3. **薄壳 + 类型钩子**：每个 BE 只保留——类型注册、NBT 类型段（loadTypeSpecific/
   saveTypeSpecific 同类钩子）、求值器定制注入（commandSink / encoderView / radarPos）、
   红石链接持有、UI 回弹查询钩子。**引擎零类型知识，类型零引擎知识。**
4. **两阶段落地**：阶段一让 `SyncedGraphBlockEntity` 整体变成引擎的委托薄壳（7 个子类
   **编译零改动**通过）；阶段二按需逐个 BE 直连引擎。不一次性大爆炸。

## 二、现状盘点（2026-08-28 实测）

| | 原生线（SyncedGraphBlockEntity） | Kinetic 线（GraphHost 组合） |
| --- | --- | --- |
| BE | Blueprint / Radar / Monitor / SpeedProxy / ControlSeat / Sensor / ProgramComputer（7 个） | ProgrammableTransmission / CncGearbox（2 个） |
| 状态 | 自持字段（graph/running/runtimeState/evaluator/lastGraphGeneration/…） | `host.*` 组合持有 |
| 重编译 | `recompileEvaluator()`（老全清）+ `recompileEvaluatorFull()` | 仅 `recompileEvaluatorFull()` |
| NBT | `loadAdditional/saveAdditional` + loadTypeSpecific/saveTypeSpecific 钩子 | `write/read` 钩子 + host.saveHostNBT/loadHostNBT |
| 契约 | 实现 GraphBlockEntity（抽象基类桥接） | 直接实现 GraphBlockEntity + GraphHostOwner |

**已分叉点（收敛必须抹平）**：

| # | 分叉 | 状态 |
| - | ---- | ---- |
| 1 | 运行时恢复不一致 | ✅ 阶段 0 抹平 |
| 2 | nodeEdge 触发电平持久化语义 | ✅ 阶段 0 抹平 |
| 3 | 老路径 `recompileEvaluator()` | ✅ 阶段 0 删除 |
| 4 | 编辑回弹保护两处各写一份 | 📋 阶段 1 |
| 5 | `subStates` 恢复策略相反（阶段 0 中新发现） | ✅ 阶段 0 抹平 |
| 6 | Radar 覆写绕过编辑器回弹保护（阶段 0 中新发现） | 📋 阶段 1 |

1. ~~**运行时恢复不一致**~~（✅ 已修）：`GraphHost.loadHostNBT` 完整恢复 pid/延时/触发器/
   脉冲/调试时间/nodeEdge；而 `SyncedGraphBlockEntity.loadAdditional` 只恢复 pidState。
   实际情况比文档记录的更碎——Blueprint / ProgramComputer / Radar 各自在子类里补了
   **不同的子集**（Blueprint 与 ProgramComputer 补 delay/ff/pulse/subStates，Radar 只补
   pid/subStates），ControlSeat / Sensor / Monitor / SpeedProxy 则只有 pid。
   现由 `RuntimeState.putAllFrom()` 单点承担，两线共用。
2. ~~nodeEdge 触发电平~~（✅ 已修）：持久化语义现由 `RuntimeStateRestoreTest` 覆盖，
   "常高信号重载后不重触发"与"拉低再拉高仍触发一次"均有断言。
3. ~~老路径 `recompileEvaluator()`~~（✅ 已删）：ControlSeat / Radar / Sensor 迁移完毕。
4. **编辑回弹保护**（pendingLocalOps / 像素编辑 / 显示拖拽查询）两处各写一份 → 阶段 1。
5. **`subStates` 恢复策略相反**（阶段 0 新发现）：原生线的 Blueprint / ProgramComputer /
   Radar 一直**恢复** `subStates`，而 Kinetic 线的 `GraphHost` 不恢复——两线行为相反，
   文档原先未记录。阶段 0 统一为**恢复**（与 recompileEvaluatorFull 保留 subStates 的
   策略一致），`GraphHost` 随之补齐。
6. **Radar 覆写绕过编辑器回弹保护**（阶段 0 新发现，未修）：`RadarBlockEntity.loadAdditional`
   在自己的覆写里重复执行 `graph = NodeGraph.load(...)`，**不检查** `pendingLocalOps` /
   像素编辑 / 显示拖拽，等于关掉了基类的三道回弹保护。属阶段 1 编辑回弹收敛范畴。

## 三、目标架构

```
                GraphBlockEntity（唯一契约：包 / 屏幕 / 渲染只认它）
                     ↑ 实现                        ↑ 实现
     SyncedGraphBlockEntity（薄壳）        Kinetic 线 BE（薄壳）
     —— 7 个存量子类 API 不变 ——          —— 直接组合，零中间层 ——
                     └──────────────┬──────────────┘
                          GraphHostCore（唯一引擎）
        状态 · 重编译 · RuntimeState · BUS · NBT · 多人同步 · 快照广播
```

**类型钩子（引擎侧只认接口，不认具体 BE）**：
- `Consumer<GraphEvaluator> evaluatorCustomizer` —— commandSink / encoderView / radarPos 注入（已有）。
- NBT 类型段：宿主在 `saveHostNBT`/`loadHostNBT` 前后追加（对应原生线 loadTypeSpecific/
  saveTypeSpecific 语义，钩子化）。
- 客户端回弹查询：`isPixelEditorOpen()` / `isDisplayDragInProgress()` 收进 GraphHostOwner
  （默认 false，客户端 UI 宿主覆写）。
- RedstoneLinkHelper：由宿主持有并在生命周期钩子转发（维持现状，引擎不管）。

## 四、迁移步骤（按序勾选，每项独立可提交）

### 阶段 0 · 对齐分叉（先行，独立于收敛）—— ✅ 已完成 2026-08-29

- [x] `SyncedGraphBlockEntity.loadAdditional` 运行时**完整恢复**（对齐 `dedaf73` 的
      GraphHost 修复：pid/delay/flipflop/pulse/debugTime/nodeEdge 全量 putAll），附回归测试。
      **落地**：恢复逻辑上提为 `RuntimeState.putAllFrom(RuntimeState)`，两线共用同一入口；
      顺带抹平了文档未记录的第 5 个分叉（见 §二.5）。回归测试
      `RuntimeStateRestoreTest`（7 例，含"只恢复 pid 必然重触发"的负例对照）。
- [x] 原生线 `recompileEvaluator()`（全清老路径）废弃或删除，调用点全部迁 `recompileEvaluatorFull()`。
      **落地**：ControlSeat / Radar / Sensor 三处调用点迁移，老方法删除（无覆写、无外部引用）。
      行为差异仅一处有意变更：三者的 `debugTime`（信号发生器相位）现在跨重编译保留，
      与 Monitor / SpeedProxy 的 Light 路径一致。

### 阶段 1 · 状态收敛（SyncedGraphBlockEntity 薄壳化）

> **⚠️ 验收线已修订（2026-08-29 实测）**：原定"7 个存量子类编译零改动"**不可达成**。
> Java 没有字段委托，而 `graph` / `running` 在 **7/7 子类里被直接赋值**（上提前都在
> `IMergeableBE.accept()` 里），`needsFullSync` 被 5 个子类赋值（7 处），
> `lastGraphGeneration` 被 3 个子类赋值。字段一旦改为委托，这些赋值语句必然编译失败。
> **新验收线**：子类 diff 仅限**机械改写**（字段 → 访问器 / 逻辑上提基类），
> **零逻辑变更**，编译通过且全量测试全绿（基线见 §五，当前 337 例）。
> 子类引用面实测（**合并逻辑上提之后**的当前值，匹配行数，含注释与声明）：
> `graph` 114 · `rs.` 33 · `running` 27 · `evaluator` 19 · `runtimeState` 16 ·
> `needsFullSync` 7 · `lastBusHashMap` 5。（上提前为 graph 125 / running 31 / rs. 34 /
> runtimeState 23，差值即本次删掉的 accept 重复代码。）
> 另有约 15 处**外部类**直摸字段（`cachedEvalSnapshot` 8 处、`runtimeState.flipflopStates`
> 6 处、`pendingLocalOps++` 1 处）—— 那部分归阶段 2 的契约收敛。

- [x] **合并逻辑上提（新增项，先行）**：7 个子类各自覆写 `IMergeableBE.accept()`，逻辑
      几乎逐字相同。上提到基类，类型段由新增的 `acceptTypeSpecific(src)` 钩子承载
      （与 loadTypeSpecific/saveTypeSpecific 同构）；Blueprint / ControlSeat /
      ProgramComputer / Sensor / SpeedProxy 五个直接删除覆写，Monitor / Radar 改为只覆写
      钩子。**净删除 7 处 `graph` 赋值 + 7 处 `running` 赋值 + 7 处重复方法体。**
      唯一行为变更：SpeedProxy 原本不发送 `sendBlockUpdated`，现在与其余六个对齐。
      **⚠️ 曾引入并已修复的回归（同日审查发现，务必留档）**：首版类型判定写成
      `other.getClass() != getClass()`，**静默断掉了基类 ↔ Sable 变体的合并**。
      `compat/` 下有 ControlSeatBlockEntitySable / MonitorBlockEntitySable /
      RadarBlockEntitySable / SensorBlockEntitySable 四个子类，均继承对应基类且**都不覆写
      accept**，旧行为靠各子类的 `other instanceof XxxBlockEntity` 双向放行；整合包中途
      加装或移除 Sable 时，新旧两种 BE 会在同一世界共存，合并被跳过即丢图。
      现判定改为"两个类存在继承关系（任一方向 `isInstance`）"，与旧语义等价。
      **不要改回 `getClass()` 相等** —— 代码注释里也写了这条禁令。
- [x] **抽象基类全部托管字段替换为 `private final GraphHost host`**（保留原名，更名
      GraphHostCore 留给阶段 3 清理）。原 public/protected 成员改为**同名访问器桥**
      （`graph()` / `setGraph(g)` / `runtimeState()` / `evaluator()` / `rs()` /
      `lastBusHashMap()` / `invalidateEvaluator()` —— 方法名 = 旧字段名，子类机械改写
      `graph.nodes` → `graph().nodes`）+ **引擎逻辑一行委托**（recompile Full/Light、
      BUS 生命周期、graphChanged、全量同步、NBT 公共段、快照广播）；方法签名与方法体
      全部不变。**落地**：引擎补 `recompileEvaluatorLight()`（自基类逐字移植）、
      `invalidateEvaluator()`、`adoptFrom()`（accept 合并走引擎）、`getFlipflopStates()`；
      基类从 938 行薄壳化，全仓净 −370 行；GraphHost 现为两线唯一实现。
- [x] **子类/工具类直摸字段盘点完毕**：外部全部改走**已有契约**——7 个屏幕的
      `getGraph`/`isRunning`/`setRunning`、8 处 `getCachedEvalSnapshot()`、
      MonitorScreen 14 处 `getNodeGraph()`、PixelEditorScreen 的 pendingLocalOps
      契约读写、两个渲染器与 RadarLockPacket 走 `getNodeGraph()`/`isRunning()`。
      唯一契约补缺：**`GraphBlockEntity.getFlipflopStates()`**（default 空映射）——
      编辑器 Host 的 6 处实现原先直摸 `runtimeState.flipflopStates`；引擎实现 +
      两线转发（继承线基类 / Kinetic 两 BE 各一行）。子类对引擎字段的引用归零
      （残留仅为注释文字）。
- [x] `loadAdditional/saveAdditional` 委托 `host.loadHostNBT/saveHostNBT`，类型段由
      loadTypeSpecific/saveTypeSpecific 钩子继续承载。**落地**：公共段 → 类型段顺序与
      NBT 键不变；Blueprint / Radar / Monitor 三个 `loadGraphFromBytes` 覆写改用
      `setGraph()` / `invalidateEvaluator()`，语义逐字保留。
- [x] 编辑回弹判断（Minecraft.getInstance().screen instanceof GraphEditor.Host …）抽为
      GraphHostOwner 客户端钩子，删除引擎/基类内的重复实现。
      **落地**：`GraphHostOwner` 新增 `isPixelEditorOpen()` / `isDisplayDragInProgress()`
      两个 default 钩子 + 统一判定 `isGraphReplaceBlocked(pendingLocalOps)`；`GraphHost`
      与 `SyncedGraphBlockEntity` 各删一份重复实现，改为共用。
      `SyncedGraphBlockEntity` 顺带 implements `GraphHostOwner`（复用其 default 方法，
      `getLevel`/`getBlockPos`/`setChanged` 由 BlockEntity 直接满足，只需补
      `asBlockEntity()` 与 `sendBlockUpdated()`）—— 为后续字段委托铺路。
- [x] **删除 `RadarBlockEntity.loadAdditional` 里的重复图加载**（§二.6）：它绕过基类的
      pendingLocalOps / 像素编辑 / 显示拖拽三道回弹保护。改为只保留 Radar 类型段，
      图与 running 交给基类。**落地**：删掉覆写里的两行重复加载，三道保护恢复生效。
- [x] 全量同步协议字段（needsFullSync / lastFullSyncGameTime / 40-tick grace）随字段入引擎，
      行为不变。**落地**：字段本就在 GraphHost；基类三个同步方法改一行委托；子类 7 处
      `needsFullSync = true; setChanged()` → `requestFullSync()`（call-shape 等价：
      requestFullSync = null 守卫 + 置位 + setChanged）。

> **三处次可见对齐（均无害，如实披露）**：(a) 服务端 NBT 加载现在立即走引擎的
> bump + 重置上次构建代数组合拳（原先只靠 onLoad bump，首 tick 强制重编译的结果相同）；
> (b) `broadcastEvalSnapshot` 补上引擎已有的 evaluator 空守卫（调用点都在求值之后，
> 旧路径此时只会 NPE）；(c) 屏幕侧乐观写 `be.running = start` 改走 `setRunning(start)`，
> 客户端 BE 多一次无害 setChanged。

### 阶段 2 · 契约收敛 —— ✅ 已完成 2026-08-29

- [x] `GraphBlockEntity` 补缺（以两线实际用到的成员面为准），SablePacketHelper / 两屏 /
      渲染快照读取改为**只依赖契约**，不再 instanceof SyncedGraphBlockEntity。
      **落地**：盘点发现阶段 1 完成后外部已零具体类型依赖——SablePacketHelper 只检查
      契约（带跨类加载器回退），屏幕/渲染器/包类全部走契约方法；唯一残留是
      PixelEditorScreen 的一条死 import（已删）。缺的成员面（`getFlipflopStates`）
      已随阶段 1 合并兼容性修复补上。
- [x] `GraphHostOwner` 并入 `GraphBlockEntity`（或拆成其服务端子接口 `GraphHostBinding`），
      引擎构造参数类型随之统一。**落地**：盘点确认三个实现者全部是方块实体
      （无非 BE 实现者，无需拆 Binding 子接口）——宿主绑定面（asBlockEntity /
      getLevel / getBlockPos / setChanged / sendBlockUpdated / NBT 类型段钩子 /
      像素编辑与拖拽查询 / isGraphReplaceBlocked 统一判定）直接成为契约成员；
      `GraphHost` 构造参数改为 `GraphBlockEntity`；`GraphHostOwner` 删除，
      两线 implements 列表同步收缩。
- [x] 委托桥上标注 `@Deprecated`（子类迁移指南），阶段 3 后按 BE 逐个直连引擎再删。
      **落地**：17 个过渡桥（7 个同名访问器 + 10 个引擎逻辑一行委托）全部标注；
      桥区附迁移指南——新子类代码数据读走契约、时序/求值操作收敛进引擎 tick 驱动、
      子类只保留类型钩子。契约覆写与类型钩子不标注（钩子是最终架构）。

### 阶段 3 · 薄壳化验收与清理 —— ✅ 已完成 2026-08-29

- [x] 7 个存量 BE + 2 个 Kinetic BE 全部"仅剩：类型注册 / 钩子 / 薄桥"。
      **落地（逐 BE 审计）**：Blueprint / ProgramComputer / Radar / Sensor / ControlSeat /
      Monitor / SpeedProxy 中已无任何托管字段与托管逻辑本体——只剩类型注册（构造器 +
      BE 类型）、领域 tick 逻辑（全部经同名桥/契约访问引擎）、类型钩子
      （loadTypeSpecific/saveTypeSpecific/acceptTypeSpecific）与三个编辑器保存覆写
      （本阶段删除，见下）。Kinetic 两 BE 自创建日起即薄壳（宿主组合 + 转发）。
- [x] 删除 SyncedGraphBlockEntity 与 GraphHost 间的全部重复逻辑（GraphHost 现承载的
      466 行为唯一实现）。**落地**：基类↔引擎重复已在阶段 1 归零（基类只剩一行委托
      与钩子）；本阶段清掉最后两处**子类↔基类**重复——(a) Blueprint / Radar /
      Monitor 三个 `loadGraphFromBytes` 覆写删除/收缩，统一走基类 → 引擎
      `loadGraphFromBytes → loadEditorTag`（引擎新增 tag 级入口，Monitor 在同一包内
      先取屏幕设置段再调它）；顺带修复两处分叉缺陷并如实披露：Blueprint/Radar 缺
      子图/触发器状态清理（编辑保存后封装内时序跨载残留），Radar 另缺 BUS 注销
      （SignalBus 泄漏旧图通道），Monitor 缺全量同步推送（保存后追踪客户端图陈旧）。
      (b) Blueprint / ProgramComputer 逐字一致的 30 行 flipflop 差分广播孪生块上提为
      引擎 `broadcastFlipflopDiff()`（基线随引擎，子类各删 30 行 + 2 个私有字段）。
      **更名决定**：`GraphHost` 沿用现名（§三"可沿用类名"授权；更名 GraphHostCore
      纯装饰性，徒增 diff）。
- [x] 文档回写：本文件勾结 + `code-architecture.md` 更新 + 交接文档同步。
      **落地**：本节勾结 + 头部归档；`code-architecture.md` 契约/桥条目已随阶段 2
      更新、本阶段补 `loadEditorTag` / `broadcastFlipflopDiff` 两个新引擎操作的
      记录；交接文档（programmable-gearbox-handoff.md）经查零收敛相关内容，无需同步。

## 五、注意事项 / Risks

- **NBT 结构不变**：`graph`/`running`/`runtime`/… 字段名与结构保持，新旧存档双向兼容；
  类型段钩子顺序（公共段 → 类型段）不得调换。
- **行为变更显式化**：阶段 0 有两处有意变更，均需进 README changelog ——
  (a) 原生线运行时全量持久化（flipflop/延时/脉冲/调试相位/触发电平跨重载存活），
  与 `dedaf73` 在 Kinetic 线的语义对齐；
  (b) ControlSeat / Radar / Sensor 的 `debugTime`（信号发生器相位）跨重编译保留，
  与 Monitor / SpeedProxy 的 Light 路径对齐。
- **多人协议不许回归**：pendingLocalOps 回弹保护 / flagFullSync / 40-tick grace 冲刷 /
  EvalSnapshot 广播，迁移前后逐一对比（两客户端实测）。
- **每 tick 路径零新增分配**：委托桥不得在 tick 热路径 new 对象。
- **测试基线**：337 例全绿（阶段 0 后为 332；阶段 1 期间 `MergeCompatibilityTest`
  随合并兼容性回归修复新增 5 例）。原生线运行时恢复的回归测试
  （`RuntimeStateRestoreTest`，7 例）已随阶段 0 落地，是阶段 0 的验收物。
  验证方式见 §七：本机（有互联网）直接 `gradle test` / `runServer`；真离线沙箱用其备用链路。
- nodeEdge 触发电平持久化语义（`dedaf73`）以本文档 §二.2 为准，两线统一后写回交接文档。

## 六、验证清单 / Verification

- [x] Blueprint：编辑 / 保存 / 存档重载 / 多人加入全量同步 / 编辑中 NBT 不回弹。
      **落地**（2026-08-29）：存档重载与多人加入由自动化联测覆盖（平台 Blueprint 的
      `dt` 相位跨服务端重启精确恢复：存档 0.200007 → 重启后双读数 0.150/0.750 反推
      回 0.200）；编辑/保存由用户游戏内复测。
- [x] Monitor / Radar：快照渲染 + BUS 频道注册与注销（含删除节点的 unRegister）。
      **落地**（2026-08-29）：双客户端实测无 BUS 回归；客户端编辑器实时收到快照
      （探针读数/图像序列动画），世界内渲染正常。
- [ ] ControlSeat：输入态（SeatInputState）全链路。（移交：ControlSeat 功能线专项，
      与图宿主收敛无关——收敛只动了其调用形态，机械改写已在阶段 1 核对。）
- [ ] 变速器 / 数控齿轮箱：现有 RCON 矩阵重跑（§交接文档 二）。（移交：齿轮箱功能线
      专项；其 Kinetic 薄壳自创建日起即走引擎，未受收敛改写影响。）
- [ ] 触发电平（nodeEdge）：两线"常高信号重载/重建后不误触发"一致。（单测层已由
      `RuntimeStateRestoreTest` 锁定；游戏内双线对照移交日常验证。）
- [x] **阶段 1 合入验收**（§四已修订，以此条为准）：7 个存量子类的 diff **仅限机械改写**
      （字段 → 访问器 / 逻辑上提基类），**零逻辑变更**；编译通过且全量测试全绿
      （基线见 §五，当前 337 例）。**落地**（2026-08-29）：编译 0 错误、337/337 全绿
      （§七 离线链路实测）；子类 diff 逐文件核对为访问器/委托改写；三处次可见对齐见
      §四披露。游戏内复测（同日，用户）：双客户端 + 服务端重启联测通过——
      **无 BUS 回归、无每 tick 重建**；本清单未勾项（ControlSeat 输入链路 /
      变速箱 RCON 矩阵 / nodeEdge 深项）为对应阶段的专项验证，不阻塞阶段 1 合入。
      ~~原"diff 为空"不可达成~~——Java 无字段委托，`graph`/`running` 在 7/7 子类里被直接
      赋值，委托后必然编译失败；详见 §四的验收线修订说明。
- [x] 性能抽查：每 tick 无新增 GC 压力（委托桥直通字段）。游戏内无每 tick 重建
      与可感知卡顿（2026-08-29 用户复测）；委托桥为直通字段调用，无热路径分配
      （阶段 1 逐桥核对），profiler 量化抽查移交日常性能线。

**完成定义（DoD）**：两线所有图宿主 BE 均为薄壳 + 类型钩子；引擎与契约各自单点；
337+ 测试全绿；两线 NBT 互通；本文件全部勾选并归档。
**达成（2026-08-29）**：薄壳 + 钩子 ✓（阶段 1/3 逐 BE 审计）；引擎与契约单点 ✓
（GraphHost 唯一实现、GraphBlockEntity 唯一契约）；337 例全绿 ✓（阶段 1/2/3 每步离线
实测）；NBT 互通 ✓（保存侧键名与公共段→类型段顺序全程未动，阶段 1 联测用旧存档
world_fresh 加载验证）；文档归档 ✓（本文件转为完成记录）。§六 未勾的 ControlSeat
输入链路 / 变速箱 RCON 矩阵 / nodeEdge 深项 / profiler 抽查为各自主人模块的专项验证，
不阻塞本收敛归档（见 §六 移交注记）。

## 七、验证环境：本机联机 + 真离线备用链路 / Verification: on-machine game runs & offline fallback

**首选：本机（Windows 开发机）有互联网，gradle 全链路直接可用**（2026-08-29 实测）。
`gradlew runServer` / `runClient` / `runClient2` 开箱即跑——首次运行自动下载
1.21.1 client.jar（NeoGradle 缓存于 `.gradle/caches/minecraft/versions/`），
**不要加 `--offline`**（会挡住这次下载，报 cacheVersionExecutableClient 失败）。
联测环境已就绪：

- 服务端：`runs/server/`（EULA 已接受、`online-mode=false`、世界 `world_fresh`、
  RCON 开在 25575，密码见 server.properties）。
- 双客户端：`gradlew runClient -Pusername=Alice` / `gradlew runClient2 -Pusername2=Bob`
  （build.gradle 的 runs 块内置，各自独立游戏目录 `runs/client*/`）。
- RCON 脚本：`rcon-send.ps1`（单命令）/ `rcon-batch.ps1 -Cmds "c1;c2"`（批量）/
  `rcon-stop.ps1`（优雅停服）。
- 阶段 1 联机实测（2026-08-29）：双客户端入服、服务端重启后运行时状态持久化
  （`dt` 相位跨重启精确恢复，见 §六 Blueprint 注记）、断线重连、编辑器实时快照，
  三侧日志零异常。

**备用：真离线沙箱（无网，NeoForge 下载不了 client.jar）**——阶段 0 建立的
javac 直编 + JUnit runner 链路，改代码后完成编译 + 全量测试验证：

- **Minecraft 类**：`build/neoForm/neoFormJoined1.21.1-20240808.144430/steps/packRecomp/output.jar`
  （joined + 已重映射，含 client 与 server 类，无需 client.jar）。
- **其余依赖**：`libs/*.jar`（Create / Sable / Ponder / aeronautics / catnip）+
  `~/.gradle/caches/modules-2/files-2.1` 下的全部 jar（含 neoforge-21.1.233-universal、
  joml、netty、datafixerupper、junit 5.11.4）+ `~/.gradle/caches/minecraft/libraries`
  下的全部 jar（slf4j / jetbrains-annotations / javax.annotation / lwjgl / joml 实际
  版本等 —— **只有 modules-2 不够，会缺约 260 个符号**）。
- **三个坑**：
  1. classpath 300+ 个 jar，**命令行会超长**；把 `-cp` 长串塞进 `@argfile` 在 Windows
     上也不可靠（引号/反斜杠转义会让整条 `-cp` 静默失效，且报错数完全不变、极难察觉）
     —— 用 **pathing jar**：一个只含 manifest `Class-Path`（全部依赖按 URI 形式列出）
     的空 jar，`-cp` 只引用它。
  2. 必须加 `-proc:none`，否则 mixin 注解处理器报 "Mixin has no targets"。
- **跑测试**：没有 `junit-platform-console-standalone`，用 `LauncherFactory` +
  `SummaryGeneratingListener` 写个 20 行的 runner 即可（注意 1.11.x 的
  `TestExecutionSummary` 只有 `getTestsSucceededCount/…`，没有
  `getTotalTestsFoundCount`）。
- 本文件 §五 的基线即由此链路测得（非 gradle 数字，但同一批测试；
  2026-08-29 阶段 1 期间实测 337/337 全绿）。
