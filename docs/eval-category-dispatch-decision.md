# eval() 按 NodeCategory 分派化 · 决策文档 / eval() Category-Dispatch Decision

> **状态**：✅ 批准（2026-10-17，用户拍板"启动"）。本文即《GUI 拆解计划》§五 GraphEvaluator
> "不拆"裁定出口条款要求的"另立规划文档"；该裁定自本文起**作废**，cross-be §6.2 同步改写。
> **Status**: approved 2026-10-17. This is the "separate planning document" the GUI plan's
> out-of-scope ruling itself demanded; the ruling is hereby superseded.

## 一、旧裁定与出处 / The Superseded Ruling

- 原文在 [`gui-decomposition-plan.md`](gui-decomposition-plan.md) §五「明确不在本轮范围」表：
  GraphEvaluator「求值器是服务端权威单一实现（CLAUDE.md 代码规范），已有 `GraphEvaluatorTest`
  等；**拆分收益低于破坏风险**」；上位原则是 §二-4「不要为了拆而拆」。
- [`cross-be-latency-observation.md`](cross-be-latency-observation.md) §6.2 转录了该结论，并留有
  ⚠️ 复审注记（2026-10-11：建议重审）。
- 裁定自带出口：「若后续仍要动……应**另立一份规划文档**」——本文即该文档。

## 二、重审理由 / Why Revisit（[`file-topology-split-review-2026-10-11.md`](file-topology-split-review-2026-10-11.md) P3）

1. **尺寸漂移**：裁定作出时 eval() 约 835 行；现已涨至约 1067 行（其中 +232 音频分支），
   单方法约占文件三分之二（文件约 1.7k 行）。
2. **风险面反转**："破坏风险"的前提是当年测试稀薄；graph/ 现已是全仓测试最厚分区
   （41 个测试文件、854 例全绿），拆分有完整回归网兜底。
3. **修复落点**：封装透传（P1-4/P1-5 类）与音频注入修复都要动 ENCAP/音频注入区——分派化后
   注入清单收敛到 context 构造一处，K22 埋点（evalNs 三段包夹）也只剩一处可插。

## 三、新裁定 / Ruling

1. **批准 eval() 按 `NodeCategory` 分派化**，一阶段只加一层（静态处理器 + 共享 `EvalContext`）。
2. **切法**：eval() 保留骨架（参数覆盖注记、输出数组绑定、尾部 `outputs.put` 记账），分类
   分支体抽成静态处理器；处理器内部按 `node.type` 的 case 体**原样搬迁**（方法体不改写，
   只改接收者/可见性/`static` 归属）。
3. **扁平口径不变**：处理器是函数包不是类层次，**不建继承体系**（CLAUDE.md）。
4. **行为零变更**，下列现存怪癖原样保留、不顺手修：
   - 4 参无状态 `evaluate()`（Sensor/ControlSeat 的 SeatInput 档）对 evalExt 七类
     （DELAY/LATCH/GATE/T_FLIPFLOP/PULSE_EXTEND/LOOP/FUSE）**静默空转**——菜单准入本就
     不含时序/逻辑类，但 op 注入（P2-7 准入仅客户端把守）与旧档形态会踩到（已登记
     `known-open-issues.md`）。
   - ENCAPSULATION 完成脉冲的主/子图 id 空间歧义残留（代码注释记载，宿主侧另批）。
5. **验收口径**：每抽一类跑 `./gradlew test` 全绿（基线 854）；搬迁与改写不同批；每批独立
   可回退。提交纪律沿用施工计划（代理只改码，验收后每批一笔 `refactor:`）。

## 四、EvalContext 与处理器分组 / Shape

**EvalContext**（`graph/EvalContext.java`）：扁平数据载体，求值器的状态即 context 的字段
（`GraphEvaluator` 保留 setter/getter 门面、写穿 context）。字段域：图与输出表（`graph` /
`outputs` / `audioRefs`）、运行时（`runtimeState` / `debugTime` / `completedNodeId` /
`audioTickStamp`）、子图机械（`subEvaluators` / `subGraphGenerations` / `subDelayQueues` /
`subFlipflopStates` / `subPulseTimers`）、各端口（`signalBus` / `audioBands` / `encoderView` /
`kineticNetworkView` / `commandSink` / `speakerSink` / `audioTransports` / `audioHostPos` /
`radarPos` / `targetLookup`）、每调用槽（`inputs` / `pidState` / `dt` / `seat` / 时序三表）、
每节点槽（`node` / `o`）。端口族提供 `inheritPortsFrom(src)`：封装子求值器的注入清单
（原 11 个 setter）收敛为一处构造/继承。

| 处理器 | 覆盖 NodeCategory | 备注 |
|---|---|---|
| `FormulaEval` | MATH_BASIC / MATH_ADVANCED / TRIG / LOGIC / CONTROL | 含 FORMULA/PID；**不含 GATE**（状态体在 evalExt 档，见 TimingEval；4 参档怪癖依赖此缺省） |
| `TimingEval` | SEQUENTIAL_ACC / SEQUENTIAL_STATE | `evalState()` 承接 evalExt 七类（含 GATE）；`eval()` 档只实现 ACCUMULATOR/INTEGRATOR |
| `MotionEval` | GEARBOX / TRANSMISSION / SPEED / KINETIC | 运动指令与动力读数 |
| `BusEval` | VALUES / OUTPUT | CONST / REDSTONE_IN/OUT / PRIVATE_* / BUS_*（BUS_IN 音频分支随行） |
| `AudioEval` | AUDIO | MUSIC / AMP / WSHAPE / CHANNEL / AUDIO_OUT / SPEAKER_PLAY |
| `InputEval` | INPUT_CTRL / INPUT_VIEW / INPUT_MOTION / INPUT_POSE | 座舱/姿态输入 + POSITION |
| `RadarEval` | RADAR | TARGET_OUT |
| `DisplayEval` | DISPLAY | HUD_PITCH_LADDER + TEXT/DATA/IMAGE/IMAGE_SEQUENCE 空体 |
| `DebugEval` | DEBUG | DEBUG_SIGNAL_GEN / DEBUG_PROBE / COMMENT |
| `EncapEval` | STRUCTURE | 仅 ENCAPSULATION（ENCAP_INPUT/OUTPUT 空体，原样）；注入收敛处 |

**分派骨架**：`eval()` 末态为 `switch (NodeCategory.of(node.type))`（覆盖校验启动即炸，分派
必然穷尽）→ 处理器；`evalExt()` 保留参数覆盖/恢复与记账骨架，七类委托 `TimingEval.evalState`。

## 五、阶段与回退 / Stages

- 阶段 0：EvalContext 落位（字段迁入 + 门面写穿），行为零变更，全量测试绿。
- 阶段 1..N：每批抽取一个处理器（case 体原样搬迁），每批全量测试绿。
- 回退粒度 = 批；搬迁与改写永不混批。
