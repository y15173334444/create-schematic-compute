# 可编程变速器 + 数控齿轮箱 · 开发交接文档 / Handoff

> **用途**：新上下文接手开发用。分支 `docs/programmable-gearbox`（HEAD `1d6b200`+）。
> 配套：`docs/programmable-gearbox-plan.md`（历史方案，单方块时代，已过时）、
> `docs/programmable-gearbox-eval.md`（Create API 暗规则，仍有效）。
> 桌面同源副本：`programmable-gearbox-handoff.md`。Create 官方源码本地副本：
> `Desktop/Create-mc1.21.1-dev/`（下文官方源码引用均指此副本）。

---

## 一、当前架构（2026-08-28 用户拍板：两方块拆分）

```
电机 ──→ 【可编程变速器】 ──→ 【数控齿轮箱】 ──→ 负载
        从动件+绝对定速输出        从动件+离合器+配额运动
        (mixin 复刻官方 SC)        (不动转速，只做接合/记账)
```

单方块方案（独立源）已**移除**（`1d6b200`）：它对抗官方传播器（收编/镜像/接合闸门）
且游离于官方应力网络之外。两个新方块都是官方网络一等成员，应力由真电机承担。

### 可编程变速器（`programmable_transmission`）
- `KineticBlockEntity` 从动件，水平轴两端轴面，无指定面（source 谁先认领谁是输入侧）。
- **绝对定速输出**：`RotationPropagatorMixin` 在 `getConveyedSpeed` HEAD 注入，
  委托 `ProgrammableTransmissionBlockEntity.getConveyedSpeed` —— 官方 SC 逐行复刻
  （max/min 符号钳制、四方向覆盖；官方"无源自驱"怪癖**必须保留**——它是放置引导
  路径，删掉会鸡生蛋死锁：conveyed=0 → 没人认领我们 → 永远无源）。
- **目标变更 = 官方拆建序列**（`updateTargetRotation`：network.remove → handleRemoved
  → removeSource → attachKinetics，桌面源码逐行核对）+ 4 tick 冷却限频（护 flickerScore 128）。
- 目标来源：图运行且有 `TX_OUT` 节点 → 节点输出；否则滚轮 ValueBox（官方 SC 同款）。
- 特性：输入解耦（16 转 input → 128 转 output 成立 = 可放大）、拆电机 → 输出 0。

### 数控齿轮箱（`cnc_gearbox`，运动块）
- `KineticBlockEntity` 从动件 + 离合器。输入面恒有轴面（INPUT_NEGATIVE + 放置自动感知
  + 扳手翻转）；输出面仅 ENGAGED 时有轴面。
- **不动转速**：速度完全跟随网络；指令执行中或 `CLUTCH` 节点意图 >0.5 → 接合；
  空闲 → 分离。接合 = attachKinetics（下游合并并入本网络）；分离 = detachKinetics
  （下游官方失源归零）。自身源/速度不受离合影响。
- **配额完成**（`MotionQuota`，纯类可单测）：度 = speed×0.3/tick、米 = speed/512×dt，
  剩余量按 |travel| 递减至 0 即完成 → 完成脉冲。前代"位置窗口采样"（0.5° 窗）在高转速
  跳窗导致指令永不完成、栈卡死（用户报的"转动指令发下去了但输出停不下来"）—— 配额模型
  数学上不可能卡死。指令节点已**移除 rpm 引脚**（速度是变速器的职责）。
- `ENCODER` 节点已启用（3 输出：度/米/rpm），BE implements `KineticEncoderView`。
- 急停 `emergencyStop()`（清栈+清配额）与 NO_SOCKET 类门控已随单方块移除；
  运动块无动力时指令自然冻结（配额不消耗，动力恢复即续跑）。

## 二、已验证（RCON 全绿，全程 [PropDestroy]=0）

- 变速器：放置引导（self-drive bootstrap）、绝对 conveyed（输出=target）、
  目标变更拆建（64→128 零销毁）、输入解耦+放大（16 in → 128 out）、拆电机归 0。
- 运动块：空闲分离（ENGAGED=false + 支路 0）、CLUTCH 接合/分离（支路 128/0）、
  ROTATE 90° 配额完成 + 自动分离、无动力指令冻结。
- 注意：**无玩家在线时区块几乎不 tick**（每次 RCON 读取间仅数个游戏刻）——时间敏感
  断言要按事件验证，别按墙钟。玩家在线时的时序回归（客户端双人）仍待做。
- 单测 320 例全绿（`MotionQuotaTest` 新增；变速器/运动块 eval 测试适配 TX_OUT/CLUTCH）。

## 三、官方暗规则速查（详见 eval 文档，全部实测/源码核对）

1. propagateNewSource：同网加速 epsilon 摧毁 / 跨号摧毁 / 收编（`|opposite|>|current|`
   → setSource+setSpeed）/ "弱者夺支"（重速 DOWN 安全）。
2. Generating 源 `applyNewSpeed`：speed==0 且 hasSource 时只动应力**不清网速**
   （旧"applied=0 仍转动"的根因）；异号提交直接炸方块。
3. 从动件改速**唯一安全序列 = 官方 SC 拆建四连**（network.remove → handleRemoved →
   removeSource → attachKinetics）；in-place 重速必踩 epsilon。
4. wasMoved（ schematic/contraption 放置）永久清零速度字段。
5. 无发电机的网络由官方过载保护兜底（容量 0 → overStressed → getSpeed()=0）——
   这是"无输入动力 → 变速器无输出"的应力诚实保障。

## 五、核心机制速查

- 变速器 BE：`tick()` = super → host（bus/sync/图变更/求值）→ 目标对账
  （desired vs applied，冷却内只记账）→ `updateTargetRotation()` 拆建 → setChanged。
  conveyed 静态方法对 `appliedTarget` 生效（mixin 唯一入口，勿在别处改速度）。
- 运动块 BE：`tick()` = super → 编码器积分（恒转，离合不影响本方块自转）→ 图求值 →
  `runMotionControl()`（CLUTCH 意图 + 指令栈配额）→ `updateClutchState()` → setChanged。
- GraphHost/GraphBlockEntity/GraphHostOwner 与两方块解耦复用；求值器定制器注入
  commandSink（运动块）——变速器无指令栈。
- 调色板 `NodeRenderer.CATEGORIES`："Gearbox Motion" 分类 = MOVE/ROTATE/WAIT/CLUTCH/
  ENCODER/TX_OUT；两屏幕（TransmissionScreen/CncGearboxScreen）各自白名单。

## 七、调试设施

- ~~DiagnosticsMixin（server 段）：[Prop] enter + [PropDestroy] 栈帧~~ ✅ 已移除（2026-08-29，研究完成）。
- `rcon-batch.ps1 -Cmds "c1;c2;..."` 批量 RCON（单连接）。**探针陷阱**：
  `execute ... run say X` 走服务端日志不走 RCON 响应；grep 到的"命令回显"≠执行结果，
  状态探测要看 `/tmp/csc-serverN.log` 或直接 `data get`。
- creative motor 改速必须**整块重放**（data merge 不触发 updateGeneratedRotation）。
- 复现拓扑（y=72,z=220，沿 X）：motor(224,facing=west)→shaft(223)→
  transmission(222)→shaft(221)→cnc_gearbox(220,input_negative=false)→shaft(219)。
  图 NBT 模板见 git log 1d6b200 的 RCON 记录。

## 八、遗留待办

1. 玩家在线时双人时序回归（客户端已装新构建需要重启）。
2. 端面贴图打磨（新块沿用蓝/红混凝土占位）。
3. README changelog + `gradle.properties mod_version`。
4. ~~ENCODER 语义~~ 已拍板（2026-08-28）：**保持恒积分**（空转读数由玩家自己承担，
   用户原话），ENCODER 节点新增**复位引脚**（电平触发：拉高清零角度+线性累计，
   持续拉高=持续保持零）。
5. ~~诊断探针降级/移除~~ ✅ 探针已移除（2026-08-29）；client 段 mixin 共存回归。
6. 变速器拆建在玩家持续高频改速下的 flickerScore 长时压测。

## 九、运行环境现状（交接时刻）

- 服务端：运行中（RotationPropagatorMixin 保留；DiagnosticsMixin 探针已于 2026-08-29 移除），RCON 127.0.0.1:25575。
- 客户端：已停止（旧构建）；重启用 `gradlew runClient` / `runClient2`。
- 世界残留：测试台架已清理；旧单方块在 (202,72,200)（BE 已跳过，方块随未注册消失）。
