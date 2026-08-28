# 可编程动力齿轮箱 · 规划文档 / Programmable Kinetic Gearbox — Plan

> **状态：🟡 实现完成，P0 主链路 RCON 验证通过（2026-08-27）**
> 已落地：Mixin 定速委托、指令栈三节点+完成脉冲、动力门控、GraphHost 接口化、接口放宽 6 处、保护层（官方重谈式扫描）、goggle、资源、单测 319 绿。
> RCON 已验：回落定速→转 90°→完成断开；显式 rpm（安全域内）闭环。
> 待闭环：① 指令 rpm 大幅超过输入速度的场景 —— 传播器同网加速分支（:272 epsilon）数学必炸，已实现安全域限幅（|rpm| ≤ 输入速度）并留有 destroy-site 栈探针（DiagnosticsMixin）；官方 256 存活案例运行于 Sable simple_kinetic 非标准拓扑，不构成反例。② WAIT/门控/急停的 RCON 细项勾选。
> 本文档是本功能在 `docs/programmable-gearbox` 分支（与 `origin/main` 同进度，`effdb6f`）上的**规划文档**：
> 记录需求、已敲定的设计决策（含逐条追问结论）、架构草案、文件清单、风险与验证清单。
> 实现落地后需：更新 README changelog（权威变更日志，`<details>` 块）并把本横幅改为 ✅ 已解决，同时把
> 决策与实现差异回写到本文档。
>
> **基线 / Baselines**
> - 技术评估：`docs/programmable-gearbox-eval.md`（2026-08-27，Create API 逐行核对 + 三条路线对比）。
> - 前代实现（仅参考，已按用户要求硬重置丢弃，仍存于对象库 `157b4be`/`3d256d3`/`63c2377`）：
>   `docs/programmable-gearbox-design.md`（457 行）+ `ProgrammableGearboxBlock(Entity/Screen)`、
>   `GraphHost`/`GraphHostOwner`、`MotionCommand`/`GearboxCommandSink`、`RotationPropagatorMixin`、
>   `ProgrammableGearboxEvalTest`（251 行，RCON 全回归六用例全绿）。
> - Create 主仓库本地副本：`Desktop/Create-mc1.21.1-dev/`（分支 `mc1.21.1/dev`），下文源码引用均指此副本。
> - 风格模板：Sable（核心结论 / 现状 / 方案 / 注意事项 / 验证清单）。

---

## 一、核心结论 / Core Conclusions

1. **产品形态**：模组自研**可编程动力齿轮箱**，方块自身是 Create 传动网的一等成员——**一个输入轴 + 一个输出轴**。
   输入轴接收上游动力（提供 `hasSource()` 与门控信号），输出轴传播程序决定的转速。
2. **输出语义 = 定速（指令指定 RPM）**：执行指令时输出轴按**指令指定的 RPM** 旋转；空闲（无指令）与 `WAIT` 等待期间输出 **0**（纯程序控制，空闲即断开）。语义对齐"转速控制器"。
3. **指令栈**：方块维护 **FIFO 指令栈**（`MotionCommand`：`ROTATE` 度 / `MOVE` 米 / `WAIT` tick），
   由蓝图图节点（触点上升沿）压入，逐条执行。
4. **指定转速的粒度 = 指令级**：`ROTATE`/`MOVE` 节点带**可选 rpm 引脚**；为 0 或未接时回落**输入轴转速**。
5. **线性换算 = 官方同款固定常量**：`convertToLinear(speed) = speed / 512` 米/tick（`KineticBlockEntity`），
   开环计时执行，无导程配置、无位置反馈（对齐 Create SequencedGearshift 的 `TURN_DISTANCE` 做法）。
6. **定速传播靠 Mixin 复刻**：Create 的 SpeedController 定速传播是 `RotationPropagator.getConveyedSpeed`
   里的硬编码大齿轮特判，第三方拿不到 → 用 `RotationPropagatorMixin` 把我们的齿轮箱委托给
   自研 `getConveyedSpeed` 静态助手复刻该语义（前代 MVP 已验证走通）。**这是本设计唯一的 Create 侵入点**。
7. **图托管走组合**：BE `extends KineticBlockEntity`（无法继承 `SyncedGraphBlockEntity`），节点图托管
   用 `GraphHost` 组合（`GraphHostOwner` 回调接口）移植 `SyncedGraphBlockEntity` 的图/求值/同步逻辑。
8. **保护层是生死线**：Create 对高频变速有 `flickerScore`（超 128 炸方块）与 `maxRotationSpeed`（超速炸方块）
   双重硬约束 → 目标 RPM 变更必须「**单调扫描（每 tick ≤ 8 RPM）+ 原子提交（照抄 `applyNewSpeed`
   全部分支动作、零字段分岔）**」+ 双钳制。前代 MVP 实测此保护下 RCON 六用例全绿、零方块销毁。
9. **兼容**：现有 `SpeedProxyBlockEntity` 保留原样（向后兼容，两种玩法并存）；不修改 Create 主仓库源码
   （mixin 为运行时注入，但需随 Create/NeoForge 升级适配）。

---

## 二、需求与术语 / Requirements & Terminology

| 术语 | 含义 |
| --- | --- |
| 输入轴 / Input shaft | 齿轮箱一端轴，接入上游传动网（动力源），为执行提供 `hasSource()` 与门控 |
| 输出轴 / Output shaft | 另一端轴，按程序指定的 RPM 驱动下游 |
| 指令栈 / Command stack | BE 维护的 FIFO（`ArrayDeque<MotionCommand>`），队首执行、队尾压入 |
| 触点引脚 / Trigger pin | 图节点输入引脚（boolean），**上升沿**触发压入一条指令 |
| 输入值引脚 / Value pin | 图节点输入引脚（number）：ROTATE=度、MOVE=米、WAIT=tick |
| rpm 引脚 | ROTATE/MOVE 节点的可选输入（number）：本指令输出转速；0/未接 → 回落输入轴转速 |
| 完成脉冲 / Done pulse | 指令到位/计时到后，对应节点输出一帧高电平（可串联下游逻辑） |
| 动力门控 / Power gating | 输入轴转速为 0 → 暂停执行（冻结指令现场，不丢指令），恢复后续跑 |

需求条目（用户原话归纳）：

- 新增一个可编程动力齿轮箱，一个输入轴、一个输出轴；
- 新节点带触点/输入值引脚：**转动 x 度 / 移动 x 米 / 等待 x tick**；
- 维护一个指令栈执行指令；
- 相当于机械动力的「可编程齿轮箱 + 转速控制器」——即**能输出指定转速的旋转行为**。

---

## 三、Create API 事实（已逐行核对的依据）/ Verified Create API Facts

> 全部来自 `Desktop/Create-mc1.21.1-dev/`（`mc1.21.1/dev`）。与前代评估 `docs/programmable-gearbox-eval.md`
> 结论一致，此处补充本次针对"定速/移动米"的专项核对。

### F1 SpeedController 的"定速"是硬编码大齿轮特判
- `RotationPropagator.getConveyedSpeed(from, to)`（`content/kinetics/RotationPropagator.java`）只有
  `isLargeCogToSpeedController` 一种特判：**SpeedController 正下方一格必须是大齿轮**（cog 轴水平、控制器轴与其不同向）。
  只有这种摆放才走 `SpeedControllerBlockEntity.getConveyedSpeed`，否则它只是普通 `KineticBlockEntity`。
- 该特判硬编码 `AllBlocks.ROTATION_SPEED_CONTROLLER`，**第三方方块不可能命中** → 复刻定速语义必须 mixin。

### F2 SpeedController 定速的精确语义（`getDesiredOutputSpeed`，逐行核对）
- `targetSpeed`（int，±`maxRotationSpeed`，默认 16）；改值回调 `updateTargetRotation()` → 整网重建
  （`handleRemoved` + `removeSource` + `attachKinetics`）→ **高频改写 = 反复重建网络，必须节流**。
- `targetSpeed == 0` → 输出 0（断开）；
- 控制器无源时：cog→控制器方向返回 `targetSpeed`（可凭空当源），控制器→cog 方向返回 0；
- 被大齿轮驱动（`wheelPowersController`）时：两个方向都返回 `targetSpeed`；
- `getConveyedSpeed` 内同号取 `max/min(desired, compare)`——**驱动齿轮比 targetSpeed 更快时实际输出取快的那个**
  （非严格"绝对定速"，是"至少转 targetSpeed"）。前代 MVP 的 `getConveyedSpeed`/`getDesiredOutputSpeed`
  静态助手照抄了以上全部分支。

### F3 SequencedGearshift 的"移动 x 米"= 固定常量 + 开环计时（官方先例）
- `SequencerInstructions` 枚举（`content/kinetics/transmission/sequencer/`）：`TURN_ANGLE`（度，max 360）、
  **`TURN_DISTANCE`（米，max 128）**、`DELAY`（tick，max 600）、`AWAIT`（红石等待）、`END`。
- 换算常量（`content/kinetics/base/KineticBlockEntity.java`）：
  - `convertToAngular(speed) = speed * 360/60/20 = speed * 0.3`（RPM → 度/tick）；
  - **`convertToLinear(speed) = speed / 512`**（RPM → 米/tick；纯调参常量，无 π、无直径、无导程概念）。
- 执行（`Instruction.getDuration/getTickProgress`）：`metersPerTick = convertToLinear(speed)`，
  `duration = ceil((value - progress) / metersPerTick) + 2`（+2 格过冲、到位早停），每 tick 累计
  `speed/512` 米。**无位置反馈、无 PID、无导程配置**——按当前转速开环跑满时长。
- 官方序列齿轮箱本身是**因子变速**（`SplitShaftBlockEntity.getRotationSpeedModifier`：源面=1、输出面=步骤
  modifier、空闲=0），它不做定速——"定速 + 指令栈"正是本设计在其之上的增量。

### F4 flickerScore / maxRotationSpeed —— 硬约束
- 过零或换向 `flickerTally += 5`、每 tick 衰减 1，传播时 `flickerScore > 128` → `destroyBlock(pos, true)`
  （`RotationPropagator`）；`|newSpeed| > maxRotationSpeed` 同样 `destroyBlock`。
- 含义：**任何绕过保护层的连续高频变速都会炸掉玩家网络里的方块**。前代 MVP 的解法：
  「单调扫描（每 tick ≤ 8 RPM）+ 原子提交（照抄 `applyNewSpeed` 全部分支动作、零字段分岔）」实测有效。

### F5 图托管组合的必要性（Java 单继承）
- `SyncedGraphBlockEntity extends BlockEntity`（本模组图托管线）与 `KineticBlockEntity extends SmartBlockEntity`
  （Create 传动线）是两条继承线 → 齿轮箱 BE 只能 `extends KineticBlockEntity`，图托管用 **`GraphHost` 组合**
  （`GraphHostOwner` 回调：`asBlockEntity/getLevel/getBlockPos/getBlockState/setChanged/sendBlockUpdated`），
  从 `SyncedGraphBlockEntity` 移植 graph/runtimeState/evaluator/BUS/同步逻辑（前代 MVP 已实现并回归）。
- 存量消费侧按具体类型写死的 `instanceof SyncedGraphBlockEntity` 共 6 处（`AbstractGraphScreen`、
  `GraphEditor`×2、`GraphEditAckPacket`、`ClientboundGraphEvalPacket`）+ `GraphJoinPacket` 7 连 instanceof，
  需放宽为接口（`GraphBlockEntity` 扩宽或 `GraphHostOwner`）。

---

## 四、总体架构 / Architecture

### 4.1 方块与 BE

```
ProgrammableGearboxBlock extends HorizontalAxisKineticBlock implements IBE<ProgrammableGearboxBlockEntity>
ProgrammableGearboxBlockEntity extends KineticBlockEntity
        implements GearboxCommandSink, GraphHostOwner, GraphBlockEntity
```

- 方块：两端轴（一端输入、一端输出），`HorizontalAxisKineticBlock`（SpeedController/数字 Gearshift 同款外形基类）。
- BE 核心字段：
  - 指令栈 `ArrayDeque<MotionCommand>`（FIFO）+ `currentCommand`/`currentProgress`/`timer`；
  - 目标 RPM 状态：`targetRpm`（当前生效值）、`lastAppliedRpm`（传播提交值）、单调扫描剩余量；
  - 编码器位置 `positionDeg`（旋转累计，度）；
  - 门控 `paused`/`pauseReason`（`NONE`/`NO_POWER`）+ 聚合 `status`（`IDLE`/`RUNNING`/`PAUSED`）；
  - 组合的 `GraphHost graphHost`（图/求值/同步）。
- `tick()`（服务端分支）时序（对齐 BlueprintBlockEntity「先 evaluate 再 writeOutputs」）：
  1. `graphHost` 保证图注册、检测图变更 → 重编译求值器（重注入 `commandSink`）；
  2. `evaluator.evaluate(...)`——MOVE/ROTATE/WAIT 节点在此上升沿 `enqueue`（副作用）；
  3. `runMotionControl(dt)`：动力门控 → 取指令 → 定速输出（单调扫描 + 原子提交）→ 位置积分 → 到位判定/完成脉冲；
  4. `broadcastEvalSnapshot()` + `setChanged()`。

### 4.2 定速传播（RotationPropagatorMixin）

- `mixin/RotationPropagatorMixin.java`：`@Inject` 到 `RotationPropagator.getConveyedSpeed` 的 `HEAD`
  （`remap=false`，注入目标为 Create 方法），当 `to/from instanceof ProgrammableGearboxBlockEntity` 时
  `cir.setReturnValue(...)` 委托给 BE 静态助手 `getConveyedSpeed(from, gearbox, targeting)`。
- BE 静态助手照抄 `SpeedControllerBlockEntity.getConveyedSpeed/getDesiredOutputSpeed` 全部分支（F2），
  把 `targetSpeed` 换成**当前目标 RPM**（执行指令时 = 指令 rpm 或回落值；空闲/WAIT = 0）。
- **NeoForge 注入段位注意**（前代 `63c2377` 教训）：mixin 需注册在 NeoForge **服务端运行段**（common 段
  不受支持），`create_schematic_compute.mixins.json` 的 `neoforge` 段 + `mixins` 段配位要正确。

### 4.3 指令栈与运动控制步（runMotionControl）

```
loop (server tick, dt = 1/20s):
  // —— 动力门控：输入轴转速 0 → 暂停（冻结现场，不丢指令）——
  inputSpeed = 输入轴网络转速（hasSource 时上游 getSpeed 的绝对值）
  if (inputSpeed < EPS):
      paused = true; pauseReason = NO_POWER; return        // 不取指令、不输出、不积分
  paused = false

  if currentCommand == null && !stack.isEmpty():
      currentCommand = stack.poll()
      target = 当前值 + command.value          // 相对运动
      timer = 0
      // 目标 RPM：指令 rpm 非 0 用指令值，否则回落输入轴转速
      targetRpm = (command.rpm != 0) ? command.rpm : inputSpeed

  switch (currentCommand.kind):
    ROTATE:  推进 positionDeg += convertToAngular(实际输出 RPM) * dt；|target - positionDeg| < tol(0.5°) → 完成
    MOVE:    推进 positionMeters += convertToLinear(实际输出 RPM) * dt（= speed/512 米/tick）；|target - pos| < tol(0.01m) → 完成
    WAIT:    timer += dt（tick 计）；timer >= command.value → 完成
    完成 → 置 completedNodeId = cmd.sourceNodeId（下一 tick 求值输出完成脉冲）→ currentCommand = null
```

- **定速输出（保护层，唯一写入口 `applyTargetRpm(int rpm)`）**：
  1. 双钳：`clamp(rpm, ±maxRotationSpeed)`；
  2. **单调扫描**：相对上一生效值每 tick 最多变化 8 RPM（`targetRpm` 朝 `rpm` 单调逼近）；
  3. **原子提交**：目标达成后按 `applyNewSpeed` 全部分支动作执行网络更新（零字段分岔），
     `lastAppliedRpm = 新值`；空闲/WAIT 目标 = 0 同样走此入口（等效 SpeedController `targetSpeed=0` 断开）。
- **急停 `emergencyStop()`**（CANCEL 节点 / 屏幕按钮）：清空栈、`currentCommand=null`、目标 RPM=0、
  清扫描余量、`status=IDLE`；恢复动力后因栈空落到 `IDLE`，不会自动续跑。

### 4.4 图节点（MOVE / ROTATE / WAIT）

| NodeType | 输入引脚 | 输出 | 语义（触点上升沿 → enqueue） |
| --- | --- | --- | --- |
| `MOVE` | 触点(boolean)、数值(米)、rpm(可选) | 完成(boolean 脉冲) | `MotionCommand(ADVANCE, 米, rpm, nodeId)` |
| `ROTATE` | 触点(boolean)、数值(度)、rpm(可选) | 完成(boolean 脉冲) | `MotionCommand(ROTATE, 度, rpm, nodeId)` |
| `WAIT` | 触点(boolean)、数值(tick) | 完成(boolean 脉冲) | `MotionCommand(WAIT, tick, 0, nodeId)` |

- 边沿去重：`RuntimeState.nodeEdge`（`Map<Integer,Boolean>`，键=节点 id）记忆上一 tick 触点电平，
  `cur && !prev` 才 enqueue 一次；`数值`/`rpm` 在上升沿当帧快照入指令（指令对象自带副本）。
- 完成脉冲：`GraphEvaluator.completedNodeId`（瞬态）在指令完成时置为 `sourceNodeId`，
  下一 tick 对应节点 `o[0]=1f`，其余 0；可串联 `DELAY`/`LATCH` 等下游。
- 求值期副作用：`GraphEvaluator` 持 `GearboxCommandSink`（`enqueue`/`emergencyStop`），
  BE 覆写 `recompileEvaluatorFull()`/`loadGraphFromBytes`/`onLoad()` 后重新 `setCommandSink(this)`
  （重编译会 `new GraphEvaluator`，引用必须重注入）。
- 屏幕：`ProgrammableGearboxScreen extends AbstractGraphScreen`（`getBE` 放宽为 `GraphBlockEntity` 后协变返回），
  节点白名单 = 通用节点 + MOVE/ROTATE/WAIT（+ 可选 CANCEL）。

### 4.5 持久化与多人

- 指令栈随 BE NBT 持久化（`MotionCommand.save/load`，字段 `k/v/r/src`；`write/read` 走 `sendData` 下发客户端用于显示）；
- 图/运行时状态（`nodeEdge` 等）走 `GraphHost` 既有 NBT 与多人增量协议（`GraphEditOpPacket`）；
- 服务端权威：目标 RPM、指令栈执行、位置积分全部只在服务端 tick 推进，客户端只消费快照/显示。

---

## 五、决策记录 / Decision Log（2026-08-27 逐条确认）

| # | 决策点 | 结论 | 依据/备注 |
| --- | --- | --- | --- |
| D1 | 输出语义 | **定速（指令指定 RPM）**，mixin 复刻 SpeedController | 用户明确"可输出指定转速的旋转行为"；SpeedController 特判第三方不可达（F1） |
| D2 | 线性换算 | **官方同款** `speed/512` 米/tick + 开环计时 | 用户要求查官方做法；SequencedGearshift `TURN_DISTANCE` 先例（F3） |
| D3 | 空闲/等待输出 | **空闲=0、WAIT=0（纯程序控制）** | 用户选择；等效 SpeedController `targetSpeed=0` 断开 |
| D4 | 等待单位 | **tick**（用户原话"等待 xtick"） | 官方 `DELAY` 同为 tick（显示层可换算秒） |
| D5 | 指定转速粒度 | **指令级 rpm 引脚**（0/未接回落输入轴转速） | 用户选择；旧设计同款三引脚 |
| D6 | 节点形态 | MOVE/ROTATE（触点+数值+rpm）、WAIT（触点+数值），完成脉冲 | 用户表述"触点/输入值引脚"三指令枚举吻合 |
| D7 | 变更保护 | 单调扫描 ≤8 RPM/tick + 原子提交 + 双钳 | 前代 MVP 实测（F4）；不可省 |

> 已否决的路线（记录备查）：① SpeedController 代理方块（旧 `SpeedProxy` 玩法，保留兼容但新方块不走）；
> ② 因子变速 `SplitShaft`（官方 API 零侵入，但无"定速"语义，D1 否决）；③ 图内 PID 组合闭环替代指令栈
> （eval §4.2 曾推荐，但用户需求明确要 BE 指令栈——保留 ENCODER 反馈节点作为后续增强，见 §七）。

---

## 六、文件清单 / File List

| 类别 | 文件 | 动作 | 说明 |
| --- | --- | --- | --- |
| 新增 | `blocks/ProgrammableGearboxBlock.java` | — | `HorizontalAxisKineticBlock` + `IBE` + 开屏（`useWithoutItem`） |
| 新增 | `blocks/ProgrammableGearboxBlockEntity.java` | — | `extends KineticBlockEntity`；定速语义静态助手、指令栈、运动控制步、保护层、`GraphHost` 组合 |
| 新增 | `blocks/GraphHost.java` + `blocks/GraphHostOwner.java` | — | 组合式图托管核心（自 `SyncedGraphBlockEntity` 移植，前代 MVP 431+35 行） |
| 新增 | `blocks/ProgrammableGearboxScreen.java` | — | `AbstractGraphScreen` 子类 + 节点白名单 |
| 新增 | `graph/MotionCommand.java` + `graph/GearboxCommandSink.java` | — | 指令数据对象（NBT 持久化）+ sink 接口 |
| 新增 | `mixin/RotationPropagatorMixin.java` | — | `getConveyedSpeed` 委托注入（neoforge 服务端段注册） |
| 编辑 | `graph/NodeType.java` | MOVE/ROTATE/WAIT + label 分支 | 枚举构造 `(id, langKey, inputs, outputs, params)` |
| 编辑 | `graph/GraphEvaluator.java` | commandSink 注入 + 三节点 case + `completedNodeId` | 仿 `radarPos` 注入范式 |
| 编辑 | `graph/RuntimeState.java` | `nodeEdge` map + 剪除 + 持久化 | 仿 `flipflopStates` |
| 编辑 | `blocks/GraphBlockEntity.java` / `AbstractGraphScreen.java` / `GraphEditor.java` | 接口放宽 4 处 | 6 处 instanceof 收敛 |
| 编辑 | `network/GraphEditAckPacket.java` / `ClientboundGraphEvalPacket.java` / `GraphJoinPacket.java` | 接口放宽 | 7 连 instanceof 收敛 |
| 编辑 | `SchematicCompute.java` | 方块/BE/标签注册 | + creative tab + SafeNbtWriters |
| 资源 | `lang/*.json`、`blockstates/gearbox.json`、`models/`、`loot_table`、`recipe` | 新增 | 中文名「数控齿轮箱/可编程齿轮箱」等 |
| 测试 | `src/test/.../ProgrammableGearboxEvalTest.java` | 新增 | 仿 `HudPitchLadderEvalTest`：边沿去重、rpm 回落、门控暂停/续跑、到位脉冲 |

规模估计：核心 ~700 行新代码 + ~80 行存量放宽 + 资源/lang/测试。不含贴图雕刻与 goggle 视觉打磨。

---

## 七、风险清单 / Risks（按严重度）

1. **[高] Mixin 与 Create/NeoForge 版本适配**：`RotationPropagator.getConveyedSpeed` 是 Create 内部方法，
   mixin 注入点/签名随升级可能变动；NeoForge 段位（服务端 segment）注册错误直接崩服
   （前代 `63c2377` 正是修此）。缓解：注入点集中一个方法、升级时专项回归、`remap=false` 配位文档化。
2. **[高] flickerScore 炸块**：任何绕过保护层的连续变速都会销毁玩家网络方块（F4）。缓解：所有目标 RPM
   变更收敛到单一 `applyTargetRpm` 入口（单调扫描 + 原子提交），测试覆盖振荡输入下实际更新频率上限。
3. **[高] maxRotationSpeed 超速炸块**：指令 rpm 或回落值可能超限。缓解：双钳（进入即 clamp + 提交前复核）。
4. **[中] 指令栈无上限积压**：高频触发节点可能无限入队。缓解：MVP 定栈容量上限（如 64 条），满则拒收并 goggle 提示。
5. **[中] 开环位置漂移**：位置 = 积分转速（不打滑假设），外力/打滑会漂（官方同款限制）。缓解：文档声明开环；
   后续可加 ENCODER 反馈节点 + 图内闭环（保留项）。
6. **[中] 多人/存档一致性**：指令栈、`nodeEdge`、完成脉冲的跨端一致性。缓解：服务端权威 + 既有增量协议 +
   `GraphHost` 既有同步；新增 NBT 字段走 `NbtVersions`/`GraphMigration` 兼容路径。
7. **[低] 与 SpeedProxy 并存**：两种玩法共存，文档标注新方块为推荐替代；回归保证旧方块行为不变。

---

## 八、验证清单 / Verification（P0~P2）

- [ ] **P0 定速**：输入轴接水车，执行 `ROTATE 90°（rpm=32）` → 输出轴实测 32 RPM、转过 ≈90°（容差内）、到位后输出 0。
- [ ] **P0 空闲/等待**：无指令输出 0；`WAIT 20t` 期间输出 0，计时到后执行下一条。
- [ ] **P0 rpm 回落**：rpm 引脚 0/未接 → 输出 = 输入轴转速；显式 rpm → 输出该值（双钳内）。
- [ ] **P0 MOVE 换算**：`MOVE 2（米）` 按 `speed/512` 米/tick 开环计时，位移 ≈2 米（官方同款公式）。
- [ ] **P0 保护层**：红石/图逻辑使目标 RPM 每 tick 振荡 ≥60s：无方块损毁，实测提交频率受单调扫描约束。
- [ ] **P0 动力门控**：输入轴停转 → `PAUSED/NO_POWER`、位置冻结、不输出；恢复后续跑（非重跑）。
- [ ] **P0 急停**：`emergencyStop()` 清栈、停转、复位；恢复动力后落到 `IDLE` 不自动续跑。
- [ ] **P1 图节点**：触点上升沿只 enqueue 一次（`nodeEdge` 去重）；完成脉冲一帧高电平可串联 `DELAY`/`LATCH`。
- [ ] **P1 托管图**：右键开屏、节点增删连线保存、多人增量协议；重编译后 `commandSink` 注入不丢。
- [ ] **P1 持久化**：存档重载后指令栈/`nodeEdge` 恢复；旧档缺新字段回落默认。
- [ ] **P2 回归**：`SpeedProxy` 原行为不变；7 个既有编辑屏正常（`getBE` 放宽后编译+冒烟）。
- [ ] **P2 单元测试**：`ProgrammableGearboxEvalTest`（`./gradlew test`）覆盖边沿/回落/门控/脉冲。

---

## 九、遗留决策点 / Open Items（可先按默认推进）

1. 外形：默认两端轴（X/Z 水平轴），六向吸附（`DirectionalAxisKineticBlock`）后续可议。
2. `SPIN`（开环持续转 N tick）指令：官方 SequencedGearshift 无此步骤（官方为 `DELAY`+`AWAIT`），
   **MVP 不纳入**，与用户三指令枚举保持一致；需要时后续加。
3. ENCODER 反馈节点（图内闭环增强）：前代 eval 曾建议，MVP 不做，保留为后续项。
4. goggle 视觉/贴图打磨：后补（`SequencedGearshiftGenerator` 类视觉方案可参考）。
5. 版本目标：v1.2.6（未定）；发布时按项目规范同步 `gradle.properties mod_version` +
   `build.gradle version` + README changelog 三处。

---

## 十、交叉引用 / Cross-References

- `docs/programmable-gearbox-eval.md` —— 技术评估（Create API 事实 F1~F10、三路线对比、风险）。
- 前代 `docs/programmable-gearbox-design.md`（对象库 `157b4be`，已丢弃）—— 指令栈/节点/门控细节的原始设计。
- 前代实现 `157b4be`/`3d256d3`/`63c2377`（已丢弃）—— GraphHost、MotionCommand、Mixin、测试的参考实现。
- Create 源码：`RotationPropagator.java`、`speedController/SpeedControllerBlockEntity.java`、
  `transmission/sequencer/SequencedGearshiftBlockEntity.java` + `Instruction.java` + `SequencerInstructions.java`、
  `base/KineticBlockEntity.java`（`convertToLinear`）。
- 本模组架构：`docs/code-architecture.md`、`docs/formula-syntax-manual.md`。
