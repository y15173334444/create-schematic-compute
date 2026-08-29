# 图宿主收敛重构 · 待办文档 / Graph Host Convergence — TODO

> **目标**：统一成一个 BE 形态 = **两条继承线共享同一个 GraphHostCore 引擎 + 同一个
> GraphBlockEntity 契约，各自只剩薄壳和类型钩子**。
> **状态**：🚧 阶段 0 已完成（2026-08-29）；**阶段 1 进行中** —— 三项减法已落地
> （合并逻辑上提 / Radar 重复加载删除 / 回弹判定收敛），剩余字段委托待办（2026-08-28 立项）。
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
> **零逻辑变更**，编译通过且 332 例测试全绿。
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
- [ ] 抽象基类全部托管字段替换为 `private final GraphHost host`（或更名 GraphHostCore），
      原 public/protected 成员逐一改为委托桥，**子类可见 API 签名不变**。
- [ ] 子类/工具类直摸字段处（`host.graph`、`runtimeState.*`、`cachedEvalSnapshot` 等）
      逐一盘点：能走契约的走契约，必须暴露的以委托属性保留。
- [ ] `loadAdditional/saveAdditional` 委托 `host.loadHostNBT/saveHostNBT`，类型段由
      loadTypeSpecific/saveTypeSpecific 钩子继续承载。
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
- [ ] 全量同步协议字段（needsFullSync / lastFullSyncGameTime / 40-tick grace）随字段入引擎，
      行为不变。

### 阶段 2 · 契约收敛
- [ ] `GraphBlockEntity` 补缺（以两线实际用到的成员面为准），SablePacketHelper / 两屏 /
      渲染快照读取改为**只依赖契约**，不再 instanceof SyncedGraphBlockEntity。
- [ ] `GraphHostOwner` 并入 `GraphBlockEntity`（或拆成其服务端子接口 `GraphHostBinding`），
      引擎构造参数类型随之统一。
- [ ] 委托桥上标注 `@Deprecated`（子类迁移指南），阶段 3 后按 BE 逐个直连引擎再删。

### 阶段 3 · 薄壳化验收与清理
- [ ] 7 个存量 BE + 2 个 Kinetic BE 全部"仅剩：类型注册 / 钩子 / 薄桥"。
- [ ] 删除 SyncedGraphBlockEntity 与 GraphHost 间的全部重复逻辑（GraphHost 现承载的
      466 行为唯一实现）。
- [ ] 文档回写：本文件勾结 + `code-architecture.md` 更新 + 交接文档同步。

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
- **测试基线**：332 例全绿（阶段 0 后）。原生线运行时恢复的回归测试
  （`RuntimeStateRestoreTest`，7 例）已随阶段 0 落地，是阶段 0 的验收物。
  沙箱内 `gradle test` 无法联网运行，离线验证方式见 §七。
- nodeEdge 触发电平持久化语义（`dedaf73`）以本文档 §二.2 为准，两线统一后写回交接文档。

## 六、验证清单 / Verification

- [ ] Blueprint：编辑 / 保存 / 存档重载 / 多人加入全量同步 / 编辑中 NBT 不回弹。
- [ ] Monitor / Radar：快照渲染 + BUS 频道注册与注销（含删除节点的 unRegister）。
- [ ] ControlSeat：输入态（SeatInputState）全链路。
- [ ] 变速器 / 数控齿轮箱：现有 RCON 矩阵重跑（§交接文档 二）。
- [ ] 触发电平（nodeEdge）：两线"常高信号重载/重建后不误触发"一致。
- [ ] **阶段 1 合入验收**（§四已修订，以此条为准）：7 个存量子类的 diff **仅限机械改写**
      （字段 → 访问器 / 逻辑上提基类），**零逻辑变更**；编译通过且 332 例测试全绿。
      ~~原"diff 为空"不可达成~~——Java 无字段委托，`graph`/`running` 在 7/7 子类里被直接
      赋值，委托后必然编译失败；详见 §四的验收线修订说明。
- [ ] 性能抽查：每 tick 无新增 GC 压力（委托桥直通字段）。

**完成定义（DoD）**：两线所有图宿主 BE 均为薄壳 + 类型钩子；引擎与契约各自单点；
332+ 测试全绿；两线 NBT 互通；本文件全部勾选并归档。

## 七、沙箱内的离线验证 / Offline verification inside the sandbox

本仓库的 `gradle build` / `gradle test` 在离线沙箱里会失败（NeoForge 要下载
1.21.1 client.jar）。阶段 0 建立的替代链路，改代码后用它在本地完成编译 + 全量测试验证：

- **Minecraft 类**：`build/neoForm/neoFormJoined1.21.1-20240808.144430/steps/packRecomp/output.jar`
  （joined + 已重映射，含 client 与 server 类，无需 client.jar）。
- **其余依赖**：`libs/*.jar`（Create / Sable / Ponder / aeronautics / catnip）+
  `~/.gradle/caches/modules-2/files-2.1` 下的全部 jar（含 neoforge-21.1.233-universal、
  joml、netty、datafixerupper、junit 5.11.4）。
- **两个坑**：
  1. classpath 有 233 个 jar，**命令行会超长** —— 必须经 `@argfile` 传给 javac/java，
     不能靠 `-cp` 内联。
  2. 必须加 `-proc:none`，否则 mixin 注解处理器报 "Mixin has no targets"。
- **跑测试**：没有 `junit-platform-console-standalone`，用 `LauncherFactory` +
  `SummaryGeneratingListener` 写个 20 行的 runner 即可。
- 本文件 §五 的 332 例基线即由此链路测得（非 gradle 数字，但同一批测试）。
