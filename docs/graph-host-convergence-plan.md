# 图宿主收敛重构 · 待办文档 / Graph Host Convergence — TODO

> **目标**：统一成一个 BE 形态 = **两条继承线共享同一个 GraphHostCore 引擎 + 同一个
> GraphBlockEntity 契约，各自只剩薄壳和类型钩子**。
> **状态**：📋 待办（2026-08-28 立项）。基线分支 `docs/programmable-gearbox`（`cfd1c5b`+）。
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
1. **运行时恢复不一致（今日 `dedaf73` 只修了 GraphHost 一侧）**：`GraphHost.loadHostNBT`
   完整恢复 pid/延时/触发器/脉冲/调试时间/nodeEdge；`SyncedGraphBlockEntity.loadAdditional`
   **仍只恢复 pidState**——flipflop/延时/触发电平在原生线重载即丢（含触发误重触发隐患）。
2. nodeEdge 触发电平：两线的 recompileEvaluatorFull 均已保留（`dedaf73`），但持久化语义
   文档只在运动块侧落地。
3. 老路径 `recompileEvaluator()`（全清版）仍存在于原生线，调用点未清。
4. 编辑回弹保护（pendingLocalOps / 像素编辑 / 显示拖拽查询）两处各写一份。

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

### 阶段 0 · 对齐分叉（先行，独立于收敛）
- [ ] `SyncedGraphBlockEntity.loadAdditional` 运行时**完整恢复**（对齐 `dedaf73` 的
      GraphHost 修复：pid/delay/flipflop/pulse/debugTime/nodeEdge 全量 putAll），附回归测试。
- [ ] 原生线 `recompileEvaluator()`（全清老路径）废弃或删除，调用点全部迁 `recompileEvaluatorFull()`。

### 阶段 1 · 状态收敛（SyncedGraphBlockEntity 薄壳化，7 个子类编译零改动为验收线）
- [ ] 抽象基类全部托管字段替换为 `private final GraphHost host`（或更名 GraphHostCore），
      原 public/protected 成员逐一改为委托桥，**子类可见 API 签名不变**。
- [ ] 子类/工具类直摸字段处（`host.graph`、`runtimeState.*`、`cachedEvalSnapshot` 等）
      逐一盘点：能走契约的走契约，必须暴露的以委托属性保留。
- [ ] `loadAdditional/saveAdditional` 委托 `host.loadHostNBT/saveHostNBT`，类型段由
      loadTypeSpecific/saveTypeSpecific 钩子继续承载。
- [ ] 编辑回弹判断（Minecraft.getInstance().screen instanceof GraphEditor.Host …）抽为
      GraphHostOwner 客户端钩子，删除引擎/基类内的重复实现。
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
- **行为变更显式化**：阶段 0 让原生线运行时全量持久化是**有意变更**（flipflop/触发电平
  跨重载存活）——与 `dedaf73` 在 Kinetic 线的语义对齐，README changelog 记录。
- **多人协议不许回归**：pendingLocalOps 回弹保护 / flagFullSync / 40-tick grace 冲刷 /
  EvalSnapshot 广播，迁移前后逐一对比（两客户端实测）。
- **每 tick 路径零新增分配**：委托桥不得在 tick 热路径 new 对象。
- **测试基线**：325 例全绿；阶段 1 前先补"原生线运行时恢复"回归测试（阶段 0 的验收物）。
- nodeEdge 触发电平持久化语义（`dedaf73`）以本文档 §二.2 为准，两线统一后写回交接文档。

## 六、验证清单 / Verification

- [ ] Blueprint：编辑 / 保存 / 存档重载 / 多人加入全量同步 / 编辑中 NBT 不回弹。
- [ ] Monitor / Radar：快照渲染 + BUS 频道注册与注销（含删除节点的 unRegister）。
- [ ] ControlSeat：输入态（SeatInputState）全链路。
- [ ] 变速器 / 数控齿轮箱：现有 RCON 矩阵重跑（§交接文档 二）。
- [ ] 触发电平（nodeEdge）：两线"常高信号重载/重建后不误触发"一致。
- [ ] 编译零改动验收：阶段 1 合入时 7 个存量子类 diff 为空。
- [ ] 性能抽查：每 tick 无新增 GC 压力（委托桥直通字段）。

**完成定义（DoD）**：两线所有图宿主 BE 均为薄壳 + 类型钩子；引擎与契约各自单点；
325+ 测试全绿；两线 NBT 互通；本文件全部勾选并归档。
