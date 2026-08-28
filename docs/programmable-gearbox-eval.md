# 可编程齿轮箱 · 技术方案评估

> **状态（2026-08-27）：MVP 已落地并完成服务端 RCON 全回归。**
> 实测过程中方案经历了一次关键演化：评估时选定的 **SplitShaft 从动件路线被证伪**——
> Create 传播器存在四类无法绕开的暗规则（同网加速摧毁 :269 / 跨号冲突摧毁 :246 /
> missing-source 重建把本方块按旧符号重灌 :368-380 / KineticBlock 放置语义永久清空
> 速度字段 wasMoved），从动件路线下的任何手搓节拍都会踩中其一。最终落地采用
> **GeneratingKineticBlockEntity 路线**（CreativeMotor 同基类）：齿轮箱是动力源，
> 输出 = 图程序目标 RPM；目标变更经「单调扫描（每 tick ≤8 RPM）+ 原子提交
> （照抄 applyNewSpeed 全部分支动作、零字段分岔）」驱动，RCON 全回归六用例全绿、零方块销毁。
> 「跟随上游转速」等语义由图程序以 ENCODER 反馈组合表达。收敛重构（两线共同委托 core）按决策押后。

> 基线：Create 主仓库本地副本 `Desktop/Create-mc1.21.1-dev/`（mc1.21.1/dev）；本模组 `create_schematic_compute 1.2.5`（NeoForge 21.1.233 / create_version_range [6.0.10,)）。
> **需求**：模组自研可编程齿轮箱，方块自身是传动网成员——**输入轴接收上游动力、输出轴输出程序计算的转速**；不再走「反射改相邻 SpeedController 的 targetSpeed」的代理方块路线。
> **结论先行**：可行。推荐 **SplitShaft 因子法**（Gearshift 同款公开钩子），配合**组合式图托管**。 SpeedController 的「绝对定速输出」无钩子、不可复刻——但只要接受「输出 = 输入 × 程序因子」（真实变速箱语义），整条链路都在官方公开 API 内。详细理由见下。

---

## 一、需求重述与术语

- **现有 `SpeedProxyBlockEntity`** 就是用户说的「代理方块」：它 extends 本模组 `SyncedGraphBlockEntity`，每 tick 反射查找并改写**相邻** `SpeedControllerBlockEntity.targetSpeed`（`blocks/SpeedProxyBlockEntity.java:58-114`）。缺陷：必须额外摆一个 Create 的 SpeedController 方块+大齿轮+独立动力源；反射耦合 int 量化 ±256。
- **新目标**：一个自研方块，输入端像普通机械件一样接上游轴收动力，输出端的转速由模组蓝图图程序实时决定。「输入/输出」= 传动网的一等成员，非旁路代理。

## 二、已核实的 Create API 事实（全部逐行核对）

### F1 存在官方公开的传导因子钩子（本方案地基）

- `RotationPropagator.getConveyedSpeed()`（`RotationPropagator.java:122-136`）：任何速度传递最终都是 `from.getTheoreticalSpeed() × modifier`。
- modifier 计算入口对第三方开放两处：
  - `KineticBlockEntity.propagateRotationTo(target, stateFrom, stateTo, diff, connectedByAxis, connectedByGears)`（`KineticBlockEntity.java:541-544`），在传播器里最先被调用（`RotationPropagator.java:71-73` `float custom = from.propagateRotationTo(...); if (custom != 0) return custom;`）。
  - **SplitShaft 回调**：轴对轴连接时 `getAxisModifier(be, direction)` 对 `SplitShaftBlockEntity` 子类调用其抽象方法 `getRotationSpeedModifier(Direction face)`（`RotationPropagator.java:157-170`）。**Gearshift / SequencedGearshift 正是这个机制的现成实现**（`SequencedGearshiftBlockEntity` 覆写返回 `(!hasSource() || face == getSourceFacing()) ? 1 : getModifier()`）。最贴近、有官方成熟先例 → 推荐主路径。

### F2 SpeedController 的「绝对定速」不可复刻（原方案死因）

- 「无视输入幅值、直接输出 targetSpeed」依赖 `isLargeCogToSpeedController` 硬编码特判（`RotationPropagator.java:127-132、187-198`）：判定仅认 `AllBlocks.ROTATION_SPEED_CONTROLLER` 与大齿轮几何关系。第三方的 KineticBlockEntity 拿不到该分支。
- 结论：不修改 Create 就做不到「与输入无关的绝对定速」。可编程齿轮箱的可编程维度=**减速比因子 f**：输出 = 当前输入 × f。这同时天然带来「无输入则无输出」的动力门控语义。

### F3 同一网络允许各处转速不同（齿轮比合法）

- speed 是每个 BE 自己的字段，`KineticNetwork` 不存 speed、只聚合 capacity/stress（`KineticNetwork.java:11-20` 无 speed 字段；`add()` 仅 source 进 sources 表）。同网异速遍布原版（齿轮 −2/−0.5、Gearshift −1 等）。我们的「输入 32 → 输出 64」不需要特殊处理。

### F4 运行时改变因子后必须手动触发重传播（有现成范式）

- 传播是**放置/变更事件驱动**的惰性写树：仅改自家 `getRotationSpeedModifier` 返回值不会自动生效，需重建下游子树。
- 官方两套做法：
  - ChainDrive（连续变速红石链）：`detachKinetics(); removeSource(); attachKinetics();` 三连同步完成（`ChainDriveBlockEntity.analogSignalChanged`，chainDrive 包）。
  - SequencedGearshift：`detachKinetics()` + `level.setBlock(..., UPDATE_ALL)` 配合 block 侧 `areStatesKineticallyEquivalent == false` 强制邻居重挂（transmission 包 `run(int)`）。
- 方法可见性足够子类调用：`removeSource()` public（`KineticBlockEntity.java:326`）、`attachKinetics()` :363、`detachKinetics()` :368。

### F5 flickerScore —— 调速频率的硬约束（炸方块风险）

- `onSpeedChanged(prev)` 在**过零或换向**时 `flickerTally += 5`（`KineticBlockEntity.java:195`）；每 tick 衰减 1（:117-118）；传播时若 `flickerScore > MAX_FLICKER_SCORE(128)` 直接 `world.destroyBlock(pos, true)`（`RotationPropagator.java:33、239-243`）。
- 含义：输出持续高频过零振荡 ≈30 tick 内就会炸掉网络里的方块。**BE 层必须强制节流**：量化死区 + 最小变更间隔 + 过零迟滞（hysteresis），不信任图程序算出的任意波形。

### F6 过速钳制同理

- 传播时 `|newSpeed| > maxRotationSpeed` 也 destroyBlock（`RotationPropagator.java:235-242`）。计算 f 时须按当前 |input| 动态钳制：`|f| ≤ maxRot / max(|input|, ε)`；且 input 后续升高时要复核（在 `onSpeedChanged` 里重新 clamp 再考虑重传播）。

### F7 应力过载不炸方块，只是停转

- 过载仅置 `overStressed=true` 并令实际转速归零（`updateFromNetwork`，`KineticBlockEntity.java:155-169`）→ **位置积分用 `getSpeed()`（实际值）而非理论值即可自然暂停**，无需自己检测应力。

### F8 单继承冲突与图生态耦合点（改造成本已清点）

- `SyncedGraphBlockEntity extends BlockEntity`（原生线）；Create 的 `KineticBlockEntity extends SmartBlockEntity → CachedSyncBlockEntity → BlockEntity`。Java 单继承 → 图托管不能继承得到，只能**组合**。
- 全仓 grep 结果：消费侧按具体类型写死的只有 **5 文件 6 处** `instanceof SyncedGraphBlockEntity`：
  1. `blocks/AbstractGraphScreen.java:169` `protected abstract SyncedGraphBlockEntity getBE()`
  2. `blocks/GraphEditor.java:916`（读 subStates flipflop）
  3. `blocks/GraphEditor.java:1010`（pendingLocalOps--）
  4. `network/GraphEditAckPacket.java:85-88`
  5. `network/ClientboundGraphEvalPacket.java:173-176`
  另 `network/GraphJoinPacket.java:74-80` 有 7 连 instanceof 调 `flagFullSync()`（新 BE 必须加入或改为接口方法）。
- 其余生态已面向 `GraphBlockEntity` 接口（EditSessionRegistry、所有 Blueprint/Toggle/RuntimeState/BusBand 包、PortableTerminalScreen、BusChannelHelper 等）——**扩展接口成员即可复用**。

### F9 ticker 接线（沿用 Create 模式，无需自己注册）

- Create 方块经 `IBE` 接口默认 `getTicker` 返回 `SmartBlockEntityTicker`（`foundation/block/IBE.java:63-68`），驱动 `SmartBlockEntity.tick()`。我们的 Block `implements IBE<ProgrammableGearboxBlockEntity>` 即免费获得行为与既有机器一致的 ticking；自定义逻辑写在 BE `tick()` 内并记得 `super.tick()`。

### F10 方块外形基类

- `HorizontalAxisKineticBlock`：沿水平轴两端出轴（`hasShaftTowards` = face.getAxis()==axis），SpeedController/数字 Gearshift 同款外形基类，适合「左入右出」的变速箱造型；放旋转模型另配 renderer/visual 可后补。

---

## 三、路线对比（三条候选）

| 维度 | A. SplitShaft 因子法（推荐） | B. propagateRotationTo 自定义连接 | C. Generating 源路径 |
| --- | --- | --- | --- |
| 机制 | 继承 `SplitShaftBlockEntity`，覆写 `getRotationSpeedModifier(face)`：源面=1、输出面=f | 覆写 `propagateRotationTo` 返回因子 + `isCustomConnection` 自定义邻接判定 | 继承 `GeneratingKineticBlockEntity`，覆写 `getGeneratedSpeed()` 作竞争性动力源 |
| 官方先例 | Gearshift / SequencedGearshift（一对一） | 无内置使用方，行为面更冷 | CreativeMotor |
| 能否放大功率到超过 maxRot | 否（传播器 tooFast 校验兜底，需自行 clamp） | 同左 | 会被上游反号冲突 `destroyBlock`（applyNewSpeed :145-147） |
| 输出与输入的关系 | 输出=输入×f（f∈[-maxF,maxF] 浮点可任意编程） | 同左但连接拓扑也要自己管 | 与输入无关（绝对定速）——语义偏离「齿轮箱」，且无法被上游压制共存 |
| 变更传播 | 三连 detach/remove/attach（F4 先例） | 因子每边可不同、双向一致性难保证 | updateGeneratedRotation |
| 结论 | ✅ 选定 | 弃（收益相同复杂度更高） | 留作未来可选「恒转速模式」开关 |

> B 与 A 给的都是乘法因子，但 A 直接借用了 SplitShaft 的双向传播模型（正转/反转都能驱动），而 B 的 `from/to` 两向都要自洽否则触发冲突摧毁（`propagateNewSource` 同号覆盖规则 :250-286）。A 明显稳。

---

## 四、推荐架构设计

### 4.1 方块与 BE

```
ProgrammableGearboxBlock extends HorizontalAxisKineticBlock implements IBE<ProgrammableGearboxBlockEntity>
ProgrammableGearboxBlockEntity extends SplitShaftBlockEntity implements GraphBlockEntity(扩宽版)
```

- BE 核心字段：`factor`(float，当前生效)、`lastAppliedFactor`、`minIntervalGuardTick`、组合的图托管核心（见 4.4）、编码器状态 `positionDeg / positionMeters`。
- `tick()`（SmartBlockEntity 链内，服务端分支）：
  1. 图求值（20Hz，dt=0.05）→ 读 GEAR_OUT 节点输出 = **期望输出 RPM**；
  2. 保护层：`desiredOut = clamp(raw, ±maxRot)`；`fTarget = desiredOut / max(|input|, ε)`；死区量化（如 0.05% 相对值或 Δout<1 RPM 忽略）+ 最小重传播间隔（建议 ≥10 tick，可配置）+ 过零迟滞；
  3. 若超阈值：`detachKinetics(); removeSource()?… attachKinetics();`（采 ChainDrive 三连；removeSource 仅用于彻底停机场景）→ 记录 `lastAppliedFactor`；
  4. 编码器积分：`position += getSpeed() × dt × 360 × ratioScale`（旋转）/×leadPitch（线性）——用实际 speed，过载自动冻结（F7）。
- `getRotationSpeedModifier(face)`：`!hasSource() ? factor : (face == getSourceFacing() ? 1 : factor)`（照抄 SequencedGearshift 结构）。
- `onSpeedChanged(prev)` 覆写：input 幅值变化后复核 `|input×f| ≤ maxRot`，需要时排队下一次受控重传播（不立即，交给节流器）。

### 4.2 图程序驱动的运动控制（MVP 砍掉 FIFO/PID 状态机）

旧设计（WorkBuddy 文档）把 MOVE/ROTATE/WAIT 指令栈+PID 闭环塞进 BE 每 tick 写 targetSpeed。在本架构下不可行也不必要：

- 每次 f 变更 = O(下游子树) 重传播（F4/F5），PID 每 tick 改写必然炸 flickerScore；
- 本模组图系统**自带** INTEGRATOR/PID/SUB/MUL/LATCH/DELAY 节点与状态持久化（`RuntimeState.pidState/delayQueues/...`），位置闭环完全可在图内组合表达——这正是本模组的产品定位；
- `完成` 语义可用比较节点/LATCH 组合，不用新建状态机。

**新增节点只做两个（宿主注入型，与 TARGET_OUT 注入 radarPos 同范式）：**

| NodeType | 输入 | 输出 | 语义 |
| --- | --- | --- | --- |
| `GEAR_OUT`（设定输出） | 目标RPM (可选默认取输入转速透传) | 实际输出RPM | eval 期只读不改宿主；BE 每 tick 从求值快照取 o[0] 作为 desiredOut，经保护层应用 |
| `ENCODER`（编码器） | 无 | 角度deg(或米)/实际转速/到位(bool) | eval 期从 BE 读 position/speed，供图中 PID/比较组合闭环 |

- GraphEvaluator 增加 host 引用注入（`setGearboxHost(ProgrammableGearboxBlockEntity)` 或中性 `setHost(Object)`）——与 `radarPos` 字段（`GraphEvaluator.java:34-35`）一致的重编译注入点，齿轮箱覆写 `recompileEvaluator*` 重新注入。
- 进阶模板图随模组发布（定位/往复/跟随三例），用户也可 ENCAPSULATION 封装自己的指令库。

### 4.3 与 Create 的关系边界

- 不修改 Create、不注入 RotationPropagator 特判、不注册进 `CAPACITIES/IMPACTS` 以外的数据（默认应力容量/冲击从 config 注册表给合理小值，或不给=0 起步）。
- 现有 `SpeedProxy` 保持原样不删（向后兼容），两种玩法并存；文档标注新方块为推荐替代。

### 4.4 图托管组合的实现方式（成本最小化）

分两层落地：

1. **接口放宽（唯一存量重构，6 处 + 1 批量点）**
   - `GraphBlockEntity` 扩增：`getRuntimeState()`、`get/setCachedEvalSnapshot(...)`、`incPendingLocalOps()/decPendingLocalOps()`（或 get/set）、`flagFullSync()`、`requestFullSync()`、`isGraphReady()`；
   - 上述 6 处 instanceof 改为面向扩宽接口；`GraphJoinPacket` 七连 instanceof 收敛为接口调用；
   - `AbstractGraphScreen.getBE()` 抽象类型放宽为 `GraphBlockEntity`，7 个现存屏幕以协变返回不受影响。
2. **托管核心移植为委托对象（零回归）**
   - 新建包私有 `GraphHostCore`（约 400 行，从 `SyncedGraphBlockEntity` 移植 graph/runtimeState/evaluator/recompileEvaluator*/BUS 注册注销/loadGraphFromBytes/saveAdditional 相关逻辑），由 `ProgrammableGearboxBlockEntity` 持有并转发生命周期钩子（onLoad/onChunkUnloaded/setRemoved/saveAdditional/loadAdditional/getUpdateTag）；
   - **不动** `SyncedGraphBlockEntity` 与现有 7 种 BE（后续若想去重再做提取公共父类的大重构，本 MVP 刻意避免回归风险）。
   - 屏幕端新 `GearboxScreen extends AbstractGraphScreen`（放宽后的 getBE 协变返回 BE 类型）+ `setNodeFilter` 白名单只暴露通用节点+GEAR_OUT+ENCODER。

### 4.5 需要新建/修改的文件清单

| 类别 | 文件 | 动作 |
| --- | --- | --- |
| 新增 | `blocks/ProgrammableGearboxBlock.java` | HorizontalAxisKineticBlock + IBE + useWithoutItem 开屏 +IWrenchable（IRotate 继承而来） |
| 新增 | `blocks/ProgrammableGearboxBlockEntity.java` | SplitShaft 子类、保护层/三连重传播/编码器积分、GraphBlockEntity 实现 |
| 新增 | `graph/GraphHostCore.java` | 组合式托管核心（移植自 SyncedGraphBlockEntity） |
| 编辑 | `blocks/GraphBlockEntity.java` | 扩接口成员 |
| 编辑 | `blocks/AbstractGraphScreen.java` / `GraphEditor.java` | 放宽 3 处 instanceof/抽象返回类型 |
| 编辑 | `network/GraphEditAckPacket.java` / `ClientboundGraphEvalPacket.java` / `GraphJoinPacket.java` | 放宽 4 处 |
| 编辑 | `graph/NodeType.java` / `GraphEvaluator.java` | 新节点常量+label 分支；eval case；host 注入字段与注入方法 |
| 编辑 | `SchematicCompute.java` | BLOCK/ITEM/BE 注册 + creative tab + SafeNbtWriters（不加则蓝图系统不保存 NBT） |
| 资源 | lang/model/blockstate/goggle overlay(可选) | 中文名「数控齿轮箱」等 |
| 测试 | `src/test/.../GearboxNodesEvalTest.java` | 图内边沿/积分/编码器读写用例（仿 HudPitchLadderEvalTest） |

规模估计：核心 ~700 行新代码 + ~80 行存量改动 + 资源/lang。**不含**贴图雕刻与 goggle 视觉打磨。

---

## 五、风险清单（按严重度）

1. **[高] flickerScore 炸块（F5）**：任何绕过节流器的写入路径都可能让玩家网络方块被销毁。缓解：所有 f 变更收敛到单一 `applyFactor(f)` 私有入口，量化+迟滞+最小间隔三重闸；测试覆盖连续振荡输入下的实际更新频率上限。
2. **[高] tooFast 炸块（F6）**：input 升速后旧 f 超限。缓解：`onSpeedChanged` 复核 clamp；保险丝——`attachKinetics` 前最后校验一次。
3. **[中] 重传播性能**：O(下游树) 重建发生在 server tick 内。典型玩家网络 <50 方块无感；超大网络下最小间隔仍可能抖动。缓解：间隔默认 10 tick 且可配置；未来可探测 `network.getSize()` 动态放宽。
4. **[中] 图内闭环失稳**：用户图乱调 Kp 造成期望输出高频振荡 → 被 BE 死区吸收成阶跃（可控），但位置精度下降。缓解：模板图给保守参数；goggle 显示当前有效更新率。
5. **[低] 编码器开环漂移**：与旧设计相同的积分位移假设（不打滑）。外力扳动结构会漂。可后续加「回零」手段（红石信号触发 position 归零）。
6. **[低] 多人协同**：factor 属服务端权威、sendData 下发；图编辑走既有 op 协议，新增节点无特殊多人逻辑。
7. **[低] 兼容性**：create_version_range [6.0.10,) 内 SplitShaft 钩子稳定存在（1.18 起未变）；反射不再使用，去掉了一层脆弱性。

## 六、验证清单（P0~P2 验收）

- [ ] 输入轴接水车 32 RPM，设定 f=2 → 下游链条稳定 64 RPM；拆除输入轴 → 输出停（动力门控天然成立）。
- [ ] f=−1 反向、跨 maxRot 自动 clamp（不炸块）。
- [ ] 红石快速翻转使期望输出每 tick 振荡 ≥60s：无方块损毁，实测 f 更新频率=节流参数值。
- [ ] 应力过载（超载鼓风箱压力）：整网停转、编码器位置冻结；卸载后恢复继续。
- [ ] `ENCODER+SUB+MUL+GEAR_OUT` 组装的「转到 90°」模板图：误差收敛、静止段 BE 无重传播发生（日志断言）。
- [ ] 图编辑器右键打开、节点增删连线保存、多人增量协议、重编译后 ENCODER/GEAR_OUT 注入引用不丢（`recompileEvaluatorFull` 覆写生效）。
- [ ] 急停：清 running 或专用 CANCEL 图逻辑 → 输出归零安全停止。
- [ ] 回归：`SpeedProxy` 原行为不变；7 个既有编辑屏打开正常（getBE 放宽后编译+冒烟）。

## 七、遗留决策点（可先按默认推进）

1. 外形朝向：默认 HorizontalAxis 两端（X/Z 轴），是否需要六向吸附（DirectionalAxisKineticBlock）？默认取两端轴。
2. 「恒转速模式」（C 路线作为 BE 内部第二模式，如创意模式供电）不在 MVP，留到验证后再议。
3. 旧文档 `WorkBuddy.../docs/programmable-gearbox-design.md` 中执行器章节已被本文取代；其蓝图节点/生态接入章节的结论与本文 4.2/4.4 一致的部分保留参考。
