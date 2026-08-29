# 代码结构文档 / Code Architecture

> 更新日期 / Last Updated：2026-08-14
> 版本 / Version：1.2.5

---

## 包结构总览 / Package Overview

```
io.github.y15173334444.create_schematic_compute/
├── SchematicCompute.java          ← @Mod 入口 / @Mod entry point
├── ModUtils.java                  ← 工具方法 / Utility methods
├── graph/          (16 files)     ← 节点图核心引擎 / Node graph core engine
├── blocks/         (29 files)     ← 方块·BE·Screen·编辑器 / Blocks, BEs, Screens, Editor
├── network/        (31 files)     ← 网络包·BUS 总线·Sable 兼容 / Packets, BUS, Sable compat
├── client/         (15 files)     ← 客户端渲染·颜色选择器·便携终端 / Client rendering
├── compat/          (6 files)     ← Sable 物理引擎兼容层 / Sable physics compat layer
├── radar/           (2 files)     ← 雷达目标管理 / Radar target management
├── entity/          (1 file)      ← 控制座椅隐形实体 / Control seat invisible entity
├── items/           (1 file)      ← 便携终端物品 / Portable terminal item
└── mixin/           (2 files)     ← Mixin 注入 / Mixin injection
```

---

## 1. `graph/` — 节点图核心引擎 / Node Graph Core Engine

### NodeType (enum)
86 种节点类型枚举，定义每种节点的 `id`（稳定 NBT 字符串）、输入/输出引脚数、参数名列表。
/ 86 node-type enum defining the stable NBT `id`, input/output pin counts, and parameter names for each type.

| 分类 / Category | 节点 / Nodes |
|------|------|
| 数值 / Values | CONST, REDSTONE_IN, PRIVATE_IN, BUS_IN |
| 数学 / Math | ADD, SUB, MUL, DIV, MOD, POW, ROOT, ABS, CEIL, FLOOR, ROUND, INTERP, SPLIT, FORMULA, POSE_CONVERT |
| 三角 / Trig | SIN, COS, TAN, ASIN, ACOS, ATAN2, SINH, COSH, SQRT, LN, LOG, EXP, SEC, CSC, COT, ANGLE_UNWRAP, DIRECTION |
| 逻辑 / Logic | GT, LT, GE, LE, EQ, OR, BOOL, GATE, RELAY_A, RELAY_B |
| 控制 / Control | PID, PID_POWER, CLAMP, MAP |
| 输出 / Output | REDSTONE_OUT, PRIVATE_OUT, BUS_OUT, SPEED_CTRL |
| 时序 / Sequential | DELAY, LATCH, T_FLIPFLOP, PULSE_EXTEND, LOOP, FUSE, ACCUMULATOR, INTEGRATOR |
| 输入 / Input | KEYBOARD, MOUSE_JOYSTICK, VIEW_ANGLE, MOUSE_BUTTON, GAMEPAD_JOYSTICK, GAMEPAD_BUTTON, GAMEPAD_TRIGGER, WORLD_VIEW, ATTITUDE, FORWARD, ACCELERATION, VELOCITY, POSITION, TARGET_OUT |
| 显示 / Display | TEXT, DATA, IMAGE, IMAGE_SEQUENCE |
| 结构 / Structure | ENCAPSULATION, ENCAP_INPUT, ENCAP_OUTPUT |
| 调试 / Debug | DEBUG_SIGNAL_GEN, DEBUG_PROBE, COMMENT |

### GraphNode
节点数据类。所有 86 种类型共用同一个类（无继承），通过 `type` 字段区分。
/ Node data class. All 86 types share a single flat class (no inheritance); distinguished by the `type` field.

**核心字段 / Core Fields**：
- `id` — 唯一标识，服务端权威分配 / Unique ID, server-authoritative allocation
- `type` — NodeType 枚举 / NodeType enum
- `x, y` — 图中位置 / Graph-space position
- `params[]` — 数值参数（如 PID 的 kp/ki/kd、CONST 的值）/ Numeric parameters
- `itemParams[]` — 物品参数（红石频率堆）/ Item parameters (redstone frequency stacks)
- `formula` — FORMULA 节点的公式文本 / Formula expression for FORMULA nodes
- `signalName` — BUS/PRIVATE/REDSTONE 的信号名 / Signal name for I/O nodes
- `signalBands` — BUS 频段名列表 / BUS band name list
- `busInternalMap` — BUS_OUT 求值输出映射（与 SignalBus CHANNELS 共享引用）/ Shared map with SignalBus
- `busConflict` / `busConflictTicks` / `bandsDirty` — BUS 冲突标记与脏检查 / BUS conflict flags
- `outputValues[]` — 运行时计算值（由 GraphEvaluator 填充）/ Runtime values filled by evaluator
- `displayText, textColor, imagePixels[]` — 显示节点数据 / Display node data
- `layerIndex, imageSequenceFrames, layoutX, layoutY, displayScale, displayRotation, moveScale` — IMAGE 显示布局 / Image display layout
- `commentWidth, commentHeight, commentBgColor, commentBorderColor, commentTextColor, commentScrollOff` — COMMENT 节点样式 / Comment node styling
- `subGraph` — ENCAPSULATION 子图（递归嵌套）/ Nested sub-graph
- `expanded` — 编辑器展开状态 / Editor expanded state
- `sortB` — Z 序 / Z-order
- `dynamicInputCount, dynamicOutputCount, outputLabels, cachedScript, formulaIssues` — FORMULA 解析状态（`ensureScriptParsed()` 维护）/ Parsed script state
- `formulaCarrier, formulaSpreadProgress, formulaShedWarned` — 刀5 挂起-lite 收敛态（**transient**，不进 NBT；generation/公式文本变更即作废）/ Knife-5 suspend-lite convergence state (transient; invalidated by generation/formula change)
- `debugCtrlX[], debugCtrlY[]` — DEBUG_SIGNAL_GEN 控制点（**持久化** NBT `dcx`/`dcy`，非 transient）/ Control points (persisted, synced via SET_CTRL_POINTS)
- `debugFormulaRpn` — 公式模式 RPN 编译缓存 / Compiled formula cache
- `probeHistory[], probeHead, probeCount, probeFrozen` — DEBUG_PROBE 采样缓冲 / Probe ring buffer
- `runtimeStickX, runtimeStickY` — MOUSE_JOYSTICK 绝对值累积 / Absolute-joystick accumulation
- `remoteLerpT, remoteStartX/Y, remoteTargetX/Y` — 多人协作远程拖拽插值 / Remote-drag lerp (transient)

**序列化 / Serialization**：`save()` / `load()` 通过 NBT。transient 字段不入 NBT（remote lerp 字段不入 NBT）。

**稳定引脚方法 / Stable pinId methods (v1.2.4)**：
- `inputPinId(i)` / `outputPinId(i)` — 引脚索引 → 稳定 pinId（FORMULA=变量名、ENCAP=子节点 ID、BUS=频段名、通用=十进制索引）
- `inputPinIndex(pinId)` / `outputPinIndex(pinId)` — pinId → 当前索引（找不到返回 -1）
- `getSubNodes(NodeType)` — 子图 ENCAP_INPUT/OUTPUT 按 Y 升序、同 Y 按 ID 排序 / Sort I/O sub-nodes deterministically
- `ensureScriptParsed()` — 按 `sourceFormula` 检测公式陈旧，刷新 dynamicInput/OutputCount（引脚解析与求值共用的唯一真相源）；公式变更同时作废刀5 carrier 与 shed 冻结 / Re-parse FORMULA when stale; a formula change also invalidates the knife-5 carrier and shed freeze
- `functionalInputs()` — 功能引脚数（= `inputs() − editableParamCount()`）；FORMULA 参数引脚（warm）索引的基数——pinId 解析、`inputLabel`、求值器 `extraBase` 三处同源 / Param-pin index base — single source across pinId resolution, labels and evaluator extraBase

### NodeConnection (v1.2.4 起 / since v1.2.4)
连线数据类。整数索引 `fromPin`/`toPin` 是从稳定 pinId 派生的缓存值；`fromPinId`/`toPinId` 为稳定字符串。
/ Connection data class. `fromPin`/`toPin` are cached int indices derived from the stable `fromPinId`/`toPinId` strings.

- 自 v1.2.4 起连线绑定稳定 pinId：引脚插入/删除/重排不再使既有连线串线 / Connections bind stable pinIds since v1.2.4
- `save()` / `load()` — NBT 键 `fPin/tPin/fPinId/tPinId`，向后兼容（旧存档无 pinId 字段）/ NBT, backward-compatible
- 两个构造函数：纯索引（旧）/ 索引+pinId（新）/ Two constructors: index-only (legacy) and index+pinId

### NodeGraph
图的容器。管理节点列表、连接列表、O(1) 查找缓存、拓扑排序。
/ Graph container. Manages node list, connection list, O(1) lookup caches, and topological sort.

**核心方法 / Core Methods**：
- `addNode(type, x, y)` — 创建节点 + 分配 ID / Create node + allocate ID
- `removeNode(id)` — 删除节点 + 级联删除所有关联连接 / Remove node + cascade delete connections
- `addConnection(fromId, fromPin, toId, toPin)` — 去重 + 自环保护，自动派生 pinId / Dedup + self-loop guard, derives pinIds
- `addConnectionWithPinIds(fromId, fromPinId, toId, toPinId)` — v1.2.4 按稳定 pinId 建连 / Connect by stable pinIds
- `removeConnection(fromId, fromPin, toId, toPin)` — 索引优先，pinId 回退 / Index-first, pinId fallback
- `rebuildInputCache()` — 将 pinId 重新解析为当前索引，剪除失效连接（拓扑变化时自动调用）/ Re-resolve pinIds → indices, prune stale
- `getInputValue(nodeId, pin, outputs)` / `getInputValueOrDefault(...)` / `hasInputConnection(...)` — O(1) 输入查询 / O(1) input lookup via inputCache
- `getTopoOrder()` — 缓存 Kahn 算法拓扑排序 / Cached Kahn topological sort
- `hasCycles()` — topo 排序大小 < 节点数 → 有环 / Sort size < node count → cycle exists
- `wouldCreateCycle(fromId, toId)` — BFS 预检测 / BFS pre-check
- `bumpGeneration()` / `graphGeneration` — 图代际号（求值器重建判定）/ Generation counter for evaluator recompile
- `findNode(id)` / `adoptNode(node)` / `rebuildNodeMap()` — 节点查找与归属 / Node lookup / adoption
- `topoVersion()` — 拓扑版本号 / Topology version
- `copy()` — 深拷贝（新 ID）/ Deep copy with new IDs
- `save()` / `load()` — NBT 序列化，带版本迁移 / NBT serialization with migration
- 书签 / Bookmarks — `List<Bookmark>` 记录（`addBookmark`/`moveBookmark`/`renameBookmark`/`removeBookmark` 经 op 同步）/ Bookmark list synced via ops

### GraphEvaluator
服务端唯一求值器。客户端不实例化此类，通过 `ClientboundGraphEvalPacket` 接收 `EvalSnapshot`。
/ Server-side only evaluator. Clients never instantiate this; they receive `EvalSnapshot` via `ClientboundGraphEvalPacket`.

**求值流程 / Evaluation Flow**：
1. `graph.getTopoOrder()` 获取拓扑排序 / Get cached topological order
2. 对每个节点调用 `eval()` switch 分发 / Switch-dispatch `eval()` per node
3. 生成 `OutputResult` 列表（REDSTONE_OUT 节点）/ Collect OutputResult list
4. `captureSnapshot()` 创建 `EvalSnapshot` 广播客户端 / Capture and broadcast to clients

**求值器自身字段 / Evaluator-Owned Fields**：
- `outputs` — `Map<nodeId, float[]>` 中间输出 / Intermediate outputs
- `subEvaluators` — ENCAPSULATION 子图递归求值器（懒创建 + generation 陈旧检测）/ Lazy sub-evaluators with stale detection
- `subGraphGenerations` — 子图代际缓存 / Sub-graph generation cache
- `subDelayQueues` / `subFlipflopStates` / `subPulseTimers` — 子图时序组件状态 / Sub-graph sequential state
- `debugTime` — DEBUG_SIGNAL_GEN 相位时间 / Phase time (persisted via RuntimeState)
- `runtimeState` — 运行时状态引用（读写子图状态持久化）/ RuntimeState reference
- `radarPos` — 雷达扫描位置上下文 / Radar scan position context

> 主图时序状态（`pidState`/`delayQueues`/`flipflopStates`/`pulseTimers`）**不是**求值器字段——由 `RuntimeState` 持有，作为 `evaluate()` 参数传入并回写。
> / Main-graph sequential state is NOT a field — it lives in `RuntimeState` and is passed into `evaluate()`.

**ENCAP 注入 / ENCAP Injection (v1.2.4)**：外部连线按 **pinId**（`String.valueOf(subNode.id)`）匹配并注入 `ENCAP_INPUT`，不依赖缓存位置；子图输出经 `captureSnapshot()` 合并进 `EvalSnapshot.subOutputs`/`subDebugTimes`。
/ Outer inputs injected into ENCAP_INPUT by pinId; sub-graph outputs merged into the snapshot.

**FORMULA 内联门控 / FORMULA Inline Gating (v1.2.5 刀5)**：FORMULA 在拓扑位置原地求值（无中央队列、无 1-tick 延迟）；循环边界协作超时（每 16 迭代墙钟检查，超 `FormulaCompute.sliceNs()` 即挂起存 carrier 于 `GraphNode.formulaCarrier`，下 tick 寻径续算）；emit-on-done——spread 期间输出冻结，done 才写新值；done 且输入未变整节点跳过（1e-3 容差）；输入变更默认严格冻结、`warm` 参数 opt-in 温启动；MAX_ITER 1M 按 spread 累计兜底 shed；tick 级去重（脚本,输入）由 `FormulaCompute` 提供。
/ FORMULA evaluates in place at its topological position (no central queue, no added tick latency); cooperative timeout at loop boundaries (wall clock every 16 iterations, suspends past `FormulaCompute.sliceNs()` with a carrier on `GraphNode.formulaCarrier`, resumed next tick via seek execution); emit-on-done — outputs frozen during spread, fresh on done; done nodes skip while inputs unchanged (1e-3); input change = strict freeze by default, warm restart opt-in via the `warm` param; MAX_ITER 1M spread-wide sheds pathological loops; per-tick (script, inputs) dedup via `FormulaCompute`.

### RuntimeState
可序列化的运行时状态快照，由 BE 持有。
/ Serializable runtime state snapshot, owned by the BE.

**持久化状态 / Persisted State**：`pidState`, `delayQueues`, `flipflopStates`, `pulseTimers`, `debugTime`, `nodeEdge`（触发电平，v1.2.5 起持久化）/ Trigger level, persisted since v1.2.5
**子图状态 / Sub-graph State**：`SubState` — 每个 ENCAPSULATION 节点独立的上述五类状态 / Independent state per encapsulation node
**API**：`clear()`、`save()`/`load()`（NBT 键 `pid/nodeEdge/delay/ff/pt/dt/sub`）、`putAllFrom(src)`（存档恢复的唯一入口，继承线与 Kinetic 线共用）、`getOrCreateSubState(id)`、`pruneToAliveIds(aliveIds)`（按存活节点剪除时序/积分状态含辅助槽 `-(id+1)`/`id+100000`/`id+200000`，重编译保留状态用）/ prune sequential/integral state to alive nodes incl. aux slots (used by recompiles to preserve state)

> **恢复语义（v1.2.5 阶段 0 统一）/ Restore semantics (unified in v1.2.5 phase 0)**：
> `save()` 始终写全量七类，存档恢复必须经 `putAllFrom()` 一并恢复全部七类。此前各 BE 恢复项
> 互不相同（Blueprint / ProgramComputer / Radar 各补不同子集，其余 BE 只有 `pidState`，
> Kinetic 线又不恢复 `subStates`），导致延时/触发器/脉冲/相位/触发电平在重载后静默归零。
> `save()` always writes all seven categories, and a save restore must go through
> `putAllFrom()` to bring back all seven. Previously each BE restored a different subset
> (Blueprint / ProgramComputer / Radar each patched in a different one, four BEs got only
> `pidState`, and the kinetic line skipped `subStates`), so delay/flipflop/pulse/phase and
> trigger-level state silently reset on every reload.

### FormulaParser
数学表达式解析器。调车场算法编译中缀→RPN，支持多行脚本。
/ Math expression parser. Shunting-yard algorithm: infix → RPN. Supports multi-line scripts.

**功能 / Features**：
- `compile(formula)` — 编译中缀表达式为 RPN token 列表 / Compile infix to RPN token list
- `evaluate(rpn, vars)` — 执行 RPN / Execute RPN with variable bindings
- `parseScript(formula)` — v1.2+ 多行脚本（赋值、@output、注释、续行）/ Multi-line scripts
- **AST 模式（刀3/刀4，v1.2.5）**：控制流关键字/`{}`/swizzle/vec3 触发 Stmt 树 + RPN 叶子；`@output` hoist、vec3 输出展开为 3 标量引脚、保守类型推断、向量形态校验 ERROR / AST mode (knife 3/4): control-flow keywords/braces/swizzle/vec3 produce a Stmt tree with RPN leaves; `@output` hoisting, vec3 output expansion, conservative type inference, vector-shape validation errors
- `evaluateValue(rpn, env)` — 统一 `Value` 栈机（标量与 AST 共用单一求值引擎，刀3）/ Unified Value stack machine — one eval engine for scalar and AST scripts
- `tokenize()` / `validate()` / `extractVariables()` — 语法高亮、实时校验、变量提取（v1.2.0）/ Tokenize, validate, extract variables
- 记录类型 / Records：`Token`、`FormulaIssue`、`Assignment`、`ScriptParseResult`（含 `sourceFormula` 陈旧检测字段）；AST 记录见 `FormulaAst`（Stmt/RPN 混合）
- **15 个标量函数**（三角函数取度为输入）+ **7 个向量函数**（`vec3 length normalize dot cross dist yaw pitch`，yaw/pitch 逐字对齐 DIRECTION 节点）
  / **15 scalar functions** (trig takes degrees) + **7 vector functions** (`vec3 length normalize dot cross dist yaw pitch`, yaw/pitch mirror the DIRECTION node):
  标量 `sin` `cos` `tan` `asin` `acos` `atan2` `sinh` `cosh` `sqrt` `ln` `log` `exp` `sec` `csc` `cot`

### FormulaInterpreter / FormulaCompute（v1.2.5 刀3/刀5）
- `FormulaInterpreter` — AST 语句解释器：控制流语句级执行、表达式走 `evaluateValue` 栈机；循环边界协作超时（`CHECK_EVERY=16` 墙钟检查）挂起 `SuspendSignal` 携 carrier（循环栈计数 + Env 快照），续算**寻径执行**跳过已快照化前缀；`MAX_ITER=1M` 按 spread 累计 → `ShedSignal`。/ AST statement interpreter with cooperative suspend at loop boundaries and seek-execution resume.
- `FormulaCompute` — 预算门面（刀1）：`beginTick()`（ServerTickEvent.Pre 单点复位 + 轮转 `N_heavy_prev` + 清 dedup 表）、`sliceNs() = budgetMs / max(1, N_heavy_prev)`、`reportYield()`、tick 级去重缓存。/ Budget facade: per-tick reset/rotate/dedup-clear, adaptive slice, tick-level dedup.

### OpExecutor
`GraphOp` 应用执行器。服务端和客户端共享，确保变更逻辑单一定义。
/ Shared `GraphOp` executor used by both server and client — single source of truth for all mutations.

**处理 31 种 OpType / Handles 31 OpTypes**：ADD_NODE_REQUEST, ADD_NODE, REMOVE_NODE, MOVE_NODE, ADD_CONN, REMOVE_CONN, SET_PARAM, SET_FORMULA, SET_COMMENT_TEXT, SET_COMMENT_COLORS, SET_COMMENT_SIZE, SET_DISPLAY_TEXT, SET_TEXT_COLOR, SET_BANDS, SET_ZORDER, SET_KEY_BINDING, SET_IMAGE_FRAME_TOGGLE, SET_DISPLAY_LAYOUT, TOGGLE_BOOL, SET_HOTBAR_ITEM, SET_IMAGE_PIXELS, SET_IMAGE_SIZE, REMOVE_IMAGE_FRAME, MOVE_IMAGE_FRAME, EXPAND_NODE, COLLAPSE_NODE, ADD_BOOKMARK, REMOVE_BOOKMARK, RENAME_BOOKMARK, MOVE_BOOKMARK, SET_CTRL_POINTS, REJECT

### GraphOp / OpType
`GraphOp`：**28 字段** record + **20 个静态方法**（19 个工厂 + `parseCtrlPoints` helper）。
/ 28-field record + 20 static methods (19 factories + 1 helper).
- `blobRefId` — 非零 → 经 `BlobRegistry` 取大数据 / non-zero → BlobRegistry lookup
- `imageData` — IMAGE 像素直接以 `int[]` 传输（替代 Base64 `stringValue`）/ direct pixel array
- `OpType`：**31 种**操作枚举 / 31-operation enum

### DebugSignals
DEBUG_SIGNAL_GEN 信号计算（无状态静态方法）。
/ Stateless signal computation for DEBUG_SIGNAL_GEN.

- **设置模式 / Set Modes**：`SET_MANUAL`（手动控制点插值）、`SET_FORMULA`（自定义 f(x)）
- **输出模式 / Output Modes**：`OUT_FREQ`（x 自动 0→1 循环）、`OUT_INPUT`（x 由输入引脚指定）
- 方法 / Methods：`computeCurve()`、`compileFormula()`、`setModeName()`、`computeVisibleRange()`（p1–p99 稳健自动缩放 / percentile robust auto-scale）

### EvalSnapshot
不可变 record：`(outputs, debugTimes, subOutputs, subDebugTimes, formulaSpreads)`。服务端→客户端广播。
/ Immutable record for server→client broadcast. Sub-graph (ENCAP) outputs and debug times merged since v1.2.4; `formulaSpreads`（刀5）携带 FORMULA 节点 spread 渲染态进度（0=空闲、0..1=repeat 进度、-1=while 不定），仅渲染进度条、无数值。/ `formulaSpreads` (knife 5) carries FORMULA spread render-state progress (0 = idle, 0..1 = repeat progress, -1 = indeterminate while) — render-only, no values.

### GraphMigration / NbtVersions
- `NbtVersions.DATA_VERSION = 4`（`VERSION_KEY = "data_version"`）/ Data format version 4
- `GraphMigration.migrate(rawTag, registries)` — 顺序执行 V1→V2→V3→**V4** 迁移
- **V3→V4（v1.2.4）**：为每条连线派生稳定 `fPinId`/`tPinId`（FORMULA=变量名、ENCAP=子节点 ID、BUS=频段名）；pinId 无法映射的连线丢弃 / Derive stable pinIds; drop unmappable connections

### SpatialIndex / ZOrder（v1.2.3 遮挡系统 / occlusion system）
- `ZOrder` — A/B/C 三层遮挡记录（网格→注释→连线→节点→覆盖层→工具提示）/ Occlusion record
- `SpatialIndex` — 网格空间哈希（`CELL_SIZE=256`），O(k) 命中过滤 / Grid spatial hash used by the editor

---

## 2. `blocks/` — 方块与编辑器 / Blocks & Editor

### SyncedGraphBlockEntity (抽象基类 / abstract base class)
统一 7 个 BE 的共享字段和生命周期 / Consolidates shared fields and lifecycle for all 7 BEs.

**共享字段 / Shared Fields**：`graph`, `running`, `runtimeState`, `evaluator`, `lastEvaluatedGraph`, `lastGraphGeneration`, `needsFullSync`, `cachedEvalSnapshot`, `graphReady`, `rs`（RedstoneLinkHelper）, `lastBusHashMap`, `lastBusOutKeys`, `busRegistrationPending`, `lastFullSyncGameTime`

**共享方法 / Shared Methods**：
- `ensureBusRegistered()` — 首次 tick 注册 BUS 频道 / Register BUS on first tick
- `recompileEvaluatorFull()` / `recompileEvaluatorLight()` — 两级求值器重建（老 `recompileEvaluator()` 已于 v1.2.5 阶段 0 删除，调用点全部迁 Full）/ Two-tier evaluator rebuild — the legacy `recompileEvaluator()` was removed in v1.2.5 phase 0 and every call site moved to Full
  - `recompileEvaluatorFull()` — 保留主图 + 子图全部运行时状态（含 `debugTime` 相位），剪除已删节点；Blueprint / ProgramComputer / Radar / Sensor / ControlSeat 使用 / preserves all main-graph and sub-graph runtime state (incl. the `debugTime` phase), pruning removed nodes
  - `recompileEvaluatorLight()` — 无 BUS 生命周期操作，仅保留 `debugTime` 相位；Monitor / SpeedProxy 使用 / no BUS lifecycle work, keeps only the `debugTime` phase
- `onLoad()` — 服务端 `graph.bumpGeneration()` 强制首 tick 全量重编译 / Bump generation to force first-tick full recompile
- `loadGraphFromBytes()` — 网络包加载完整图（v1.2.4.1：`bumpGeneration()` + `lastGraphGeneration = -1` 强制重编译，**跳过** `cleanupBusChannels`）/ Load full graph; force recompile, skip bus cleanup
- `broadcastEvalSnapshot()` — 广播 EvalSnapshot → ClientboundGraphEvalPacket
- `getUpdateTag()` — 网络同步（始终发送完整图）/ Network sync (full graph, unconditional)
- `flagFullSync()` — 触发完整图同步 / Trigger full graph sync
- `loadAdditional()` — 客户端守卫 `!editorOpen || pendingLocalOps <= 0`：仅当本地玩家有未 ACK 编辑 op 时跳过图替换（回弹保护）；加入者/无编辑者总是应用服务端权威图 / Client guard: replace the graph unless the local editor has un-ACKed ops (bounce-back protection); joiners with no local edits always apply the authoritative graph

### 7 个方块类 / 7 Block Types

| 类 / Class | 功能 / Function | 特殊能力 / Special Capability |
|----|------|---------|
| `BlueprintBlockEntity` | 蓝图计算机 / Blueprint Computer | 红石 I/O + BUS + 时序节点 / Redstone I/O + BUS + sequential |
| `MonitorBlockEntity` | 全息显示器 / Holographic Monitor | 3D 悬浮屏幕 + 像素编辑器 / 3D floating screen + pixel editor |
| `RadarBlockEntity` | 3D 全息雷达 / 3D Holographic Radar | 实体扫描 + Sable 兼容 / Entity scanning + Sable compat |
| `ControlSeatBlockEntity` | 控制座椅 / Control Seat | 58 键 + 手柄 + Sable 姿态 / 58 keys + gamepad + Sable pose |
| `SensorBlockEntity` | 姿态传感器 / Attitude Sensor | Sable 子世界姿态读取 / Sable sub-level pose reading |
| `SpeedProxyBlockEntity` | 转速代理 / Speed Proxy | Create SpeedController 直控 / Direct speed controller access |
| `ProgramComputerBlockEntity` | 编程计算机 / Program Computer | 时序逻辑专用 / Sequential logic only |

### GraphEditor (~5300 行 / lines)
核心节点图编辑器。承载所有渲染/输入/交互逻辑。
/ Core node graph editor. All rendering, input, and interaction logic.

**关键子系统 / Key Subsystems**：
- 节点渲染 / Node rendering — `renderBg()` 委托 `renderer.renderNodes(...)`（`NodeRenderer`）/ Node drawing lives in NodeRenderer
- A=0~A=5 六层遮挡 / Six-layer occlusion (A=0 Grid → A=1 Comment backgrounds → A=2 Connections → A=3 Node bodies + edit areas → A=4 Overlays → A=5 Tooltips/menu)
- `undoStack2` / `redoStack2`（per-instance `ArrayDeque<UndoEntry>`，`MAX_UNDO2 = 100`）/ Per-instance op undo/redo
- Ctrl+D 复制 / Copy → `PendingCopyGroup` → `flushCopyGroup()`
- 添加节点菜单搜索框 / Add-node menu search (`NodeRenderer.menuSearchText` + `appendMenuSearch`/`menuSearchBackspace`，双语 `search_hint`)/ Menu search box
- 多人协作 Presence / Multiplayer presence (光标/节点锁/金色边框 / cursor/lock/golden border)
- 书签系统 / Bookmark system
- BUS 冲突检测 / BUS conflict detection (`reevaluateBusConflicts`)
- 调试工具交互 / Debug tool interaction (控制点拖拽、探针冻结 / control point drag, probe freeze)

### NodeRenderer
节点图渲染器：`renderNodes()`、`drawNode()`、添加节点菜单（多列 + 搜索框 + scissor 裁剪 + 高度封顶）、`SpatialIndex` 命中过滤、A/B/C 遮挡排序。
/ Graph renderer: nodes, add-node menu (multi-column + search + scissor + height cap), spatial culling, occlusion ordering.

### 7 个编辑界面 / The 7 Editor Screens（无 Menu 架构 / menu-less, v1.2.5）

自 v1.2.5 起，7 个编辑界面全部继承 **`AbstractGraphScreen`**（`extends Screen implements GraphEditor.Host`），不再使用 `AbstractContainerScreen`/`Menu` 体系。
/ Since v1.2.5 all 7 editors extend `AbstractGraphScreen`; the container-screen/menu system is gone.

| Screen | BlockEntity | 打开方式 / Opened by |
|--------|-------------|----------------------|
| `BlueprintScreen` | `BlueprintBlockEntity` | `BlueprintBlock.useWithoutItem` → 客户端 `setScreen` / client-side setScreen |
| `SpeedProxyScreen` | `SpeedProxyBlockEntity` | 同上 / same |
| `ProgramComputerScreen` | `ProgramComputerBlockEntity` | 同上 / same |
| `SensorScreen` | `SensorBlockEntity` | 同上 / same |
| `ControlSeatScreen` | `ControlSeatBlockEntity` | Shift+右键 → `setScreen` / Shift+RMB |
| `MonitorScreen` | `MonitorBlockEntity` | 同上 / same |
| `RadarScreen` | `RadarBlockEntity` | Shift+右键 → `setScreen` / Shift+RMB |

> **dist 边界 / dist boundary**：打开动作不能把客户端类的 `new` 指令写进公共 Block 代码——专用服务端校验公共类时会尝试加载 `Screen` 导致 `invalid dist DEDICATED_SERVER` 崩溃。每个 Block 的 `useWithoutItem` 调用私有 `@OnlyIn(Dist.CLIENT) openScreen(pos)` 助手，方法体由 `runtimedistcleaner` 在专用服务端剥离（`0f033a2`）。
> / The opener cannot inline `new XxxScreen(...)` in common block code — the dedicated server fails class verification with `invalid dist`. Each block calls a private `@OnlyIn(Dist.CLIENT) openScreen(pos)` helper whose body the runtime dist cleaner strips on the server.

**`AbstractGraphScreen` 基类职责 / Base class responsibilities**：
- 持有 `blockPos` + `GraphEditor`，构造器 `(Component title, BlockPos pos)`；子类通过 `setNodeFilter()` 设置节点过滤器 / Holds blockPos + GraphEditor; subclasses set node filters
- `init()` 发送 `GraphJoinPacket`；`onClose()` 依次执行 `preClose()` 钩子 → `pendingLocalOps=0` 复位（`5892caa` 守卫）→ `editor.onClose()` → `clearRemotePresences()` → 发送 `GraphLeavePacket` / Full close lifecycle
- `tick()` 通过子类 `isBlockEntityValid()` 检查 BE，失效自动 `onClose()` / Auto-close on BE invalidation
- `render()` 契约：`renderBackground` → `renderGraphCanvas()` 钩子（默认 `editor.renderBg`，Radar 叠加工具栏、Monitor 切换显示模式画布）→ `renderables` widget → tooltip 由编辑器自绘 / Canvas hook for per-screen overlays
- `sendOp()` 自增 `pendingLocalOps` 后发送 `GraphEditOpPacket` / pending-op guard on send
- 输入事件（mouse/key/char）统一委托 `GraphEditor` / Unified input delegation

**不再存在的部分 / Removed**：7 个 `XxxMenu` 类、`SchematicCompute.MENUS` DeferredRegister（7 个 MenuType 注册项）、`ClientSetup.registerScreens`、`SyncedGraphBlockEntity implements MenuProvider` 及 7 个 BE 的 `getDisplayName`/`createMenu`。
/ The 7 menu classes, MENUS registry, registerScreens, MenuProvider and per-BE getDisplayName/createMenu are all deleted.

**终端虚拟路径 / Terminal path**：`PortableTerminalScreen.openBlockUI()` 直接 `new XxxScreen(editingPos)` 并包进 `TerminalWrapper`，不再构造虚拟 Menu。
/ The portable terminal constructs the screens directly from editingPos, no virtual menus.

### EditSessionRegistry
多人编辑会话注册表 / Multiplayer edit session registry.

**三个 Map / Three Maps**：
- `editors` — `GlobalPos → Set<UUID>` 活跃编辑者 / Active editors
- `editVersions` — `GlobalPos → Long` 单调自增版本号 / Monotonically increasing version
- `opLogs` — `GlobalPos → Deque<GraphOp>` 操作日志（最多 200 条 / max 200 entries）

**核心方法 / Core Methods**：
- `join(level, pos, uuid)` — 加入会话 / Join session
- `leave(level, pos, uuid)` — 离开会话 / Leave session
- `applyOp(level, pos, op, player)` — 验证 + 执行 + 广播 + ACK / Validate + execute + broadcast + ack

---

## 3. `network/` — 网络包与 BUS 总线 / Network & BUS

### 网络包分类 / Packet Catalog

**注册中枢 / Registration hub**：`AllPackets`（`@EventBusSubscriber`）注册 **13 个 C→S + 9 个 S→C = 22 个**包。

| 方向 / Dir | 包 / Packet | 用途 / Purpose |
|------|-----|------|
| C→S | `GraphEditOpPacket` | 编辑操作（含安全校验）/ Edit with validation |
| C→S | `GraphJoinPacket` / `GraphLeavePacket` | 加入/离开编辑会话 / Join/leave edit session |
| C→S | `GraphPresencePacket` | 光标/选区位置 / Cursor/selection position |
| C→S | `BlueprintSavePacket` | 完整图覆盖（兼容路径）/ Full graph overwrite (legacy) |
| C→S | `BlueprintTogglePacket` | 启动/停止执行 / Start/stop execution |
| C→S | `BusBandUploadPacket` | BUS 频段上传 / BUS band upload |
| C→S | `BlobDataPacket` | 分片大数据上传（≤30KB/片）/ Chunked bulk data upload |
| C→S | `ControlSeatInputPacket` | 座椅输入 / Seat input |
| C→S | `RadarSettingsPacket` / `RadarLockPacket` | 雷达设置/锁定 / Radar settings/lock |
| C→S | `MonitorSettingsPacket` | 屏幕参数 / Monitor screen params |
| C→S | `ScanSablePacket` | 便携终端扫描请求 / Portable terminal scan request |
| S→C | `GraphEditOpSyncPacket` | 远程编辑操作同步 / Remote edit sync |
| S→C | `GraphEditAckPacket` | ADD_NODE_REQUEST 回执 / Server ID allocation ack |
| S→C | `GraphPresenceSyncPacket` | 远程光标同步 / Remote cursor sync |
| S→C | `ClientboundGraphEvalPacket` | 求值结果快照 / Eval result snapshot |
| S→C | `BusBandSyncPacket` | BUS 频段同步 / BUS band sync |
| S→C | `RuntimeStateSyncPacket` | 时序组件状态同步（含子图 flipflop）/ Sequential state sync (incl. sub-graph flipflop) |
| S→C | `BlobDataSyncPacket` | Blob 数据转发 / Blob data relay |
| S→C | `MonitorRedstoneSyncPacket` | Monitor 红石输入同步 / Monitor redstone input sync |
| S→C | `ScanSableResponsePacket` | 便携终端扫描结果 / Terminal scan response |

**辅助类型 / Helper types**：`BlobPacketHandler`（Blob 收发）、`BlobType`（IMAGE_PIXELS/ITEMSTACK_NBT/RAW_BYTES）、`ChannelEntry`（CHANNELS 值类型，含 refCount）、`ChannelOwner`（`BlockPos+nodeId` 所有者标识）

**安全校验 / Security**：双重验证（距离 + 编辑会话成员）**仅适用于编辑类包**（`GraphEditOpPacket`、`BlobDataPacket`）。
/ Dual validation (distance + editor-membership) applies only to edit-type packets.
- `ControlSeatInputPacket` / `RadarLockPacket` — 仅距离检查 / distance only
- `ScanSablePacket` — 无距离/成员校验 / no validation before scan
- `GraphJoinPacket` — 距离 + 目标为 GraphBlockEntity / distance + block-type check
- `GraphPresencePacket` / `GraphLeavePacket` — 无成员校验（Presence 转发给会话编辑者）/ no membership check

### SignalBus
全局静态 BUS 注册表 / Global static BUS registry (`ConcurrentHashMap`).

- `SIGNALS` — PRIVATE_IN/OUT 信号 / Private signals (`name → float`)
- `CHANNELS` — BUS_OUT 频道 / BUS output channels (`name → ChannelEntry`)
- `BAND_REGISTRY` — 频段定义 / Band definitions (`name → List<String>`)

**核心方法 / Core Methods**：
- `registerChannel(name, map, owner)` — 首次注册创建（refCount=1）；**同 owner 重注册不递增 refCount**，仅更新 map 引用并保留原计数；不同 owner 返回 false / First registration creates; same-owner re-registration preserves ref-count; different owner → conflict (false)
- `updateChannel(name, map, owner)` — 更新 map 引用不改变 refCount / Update map ref without touching refCount
- `unregisterChannel(name, owner)` — refCount--，归零时移除 + clearBus / Decrement ref; remove + clearBus at zero
- `registerBands(name, bands)` / `getBands(name)` / `clearBus(name)`

### BusChannelHelper
BUS 频道生命周期管理器 / BUS channel lifecycle manager.

- `registerChannels()` — 注册所有 BUS_OUT，设置 busConflict，同步 bands / Register all, set conflict flag, sync bands
- `unregisterChannels()` — 注销所有 BUS_OUT / Unregister all
- `reRegisterChannels()` — 差异式重注册（只注销移除的，保留现有的）/ Diff-based: unregister removed only
- `recoverConflictedChannels()` — 原 owner 消失时接管频道 / Take over when original owner gone
- `syncBandsFromServer()` — 服务端推送频段到客户端图（仅断开实际删除的频段，保留重排频段）/ Push bands; disconnect only actually-removed bands
- `syncIfBandsChanged()` — tick 级频段变更检测 / Per-tick band change detection
- `cleanupClientBands(graph, pos, level)` — 卸载/销毁前清空 BUS_OUT 频段同步 + PRIVATE_OUT / Clear client bands before unload
- `syncDeletedBusNames(oldGraph, newGraph, pos, level)` — 旧图有而新图无的 BUS_OUT 名发空同步 / Sync deleted bus names

> **v1.2.4.1 行为要点 / Behavior note**：`loadGraphFromBytes` **跳过** `cleanupBusChannels`（避免向客户端广播空频段、永久删除连线），并**跳过立即重编译**——通过 `graph.bumpGeneration()` + `lastGraphGeneration = -1` 推迟到下一 tick 重编译时恢复频段。
> / loadGraphFromBytes skips bus cleanup and immediate recompile; next-tick recompile restores correct bands.

### SablePacketHelper
Sable 子层级兼容工具 / Sable sub-level compat utilities.

- `findSubLevel(overworld, pos)` — 查找包含 pos 的子层级 / Find sub-level containing pos
- `scanDevices(overworld, playerPos, range)` — 扫描 Sable 结构上的可编程方块 / Scan for graph blocks on Sable structures
- `isWithinReachableRange(sp, pos, maxDistSq)` — Sable 感知距离检查（含变换缓存）/ Sable-aware distance check with cached transforms
- `getOrComputeSubTransform(subLevel)` — 子层级变换缓存 / Sub-level transform cache (`ConcurrentHashMap`)

### BlobRegistry
分片数据重组器。接收 `BlobDataPacket` 分片（≤30KB/片），重组后供 `GraphOp.blobRefId` 引用。30 秒超时，每 20 tick 清理一次。
/ Chunked data reassembler. 30 KB/chunk, 30s timeout, cleaned every 20 ticks.

---

## 4. `client/` — 客户端渲染 / Client Rendering

| 类 / Class | 功能 / Function |
|----|------|
| `ClientSetup.java` | 客户端初始化 / Client init |
| `ControlSeatInputHandler.java` | 座椅 GLFW 原始输入 / Raw GLFW input bypassing MC keybindings |
| `FormulaCompletion.java` | FORMULA 编辑器自动补全候选构建 / Autocomplete candidate builder |
| `FormulaSuggestPopup.java` | 自动补全浮层（光标附近，z 层 C=5.5）/ Suggestion dropdown overlay |
| `GeometryConstants.java` | 统一布局常量 / Unified layout constants |
| `MultiLineEditBox.java` | 多行文本编辑 / Multi-line text editing |
| `PortableTerminalScreen.java` | 便携终端 UI / Portable terminal UI |
| `PixelEditorScreen.java` | 独立像素编辑器（v1.2.6+，绘画软件式 UI：**响应式瓦片布局**——顶栏 + 左面板（PS 式单列工具列：紧贴左缘/右分隔线、无单独按钮边框、仅悬停/选中时整块高亮矩形，约 30px 宽；笔刷/透明度/当前色当前隐藏）+ 默认收起的右取色器面板已改为**内嵌式常驻右侧面板**（约 0.8x，面板铺满右缘到屏幕底部、无标题，常用/最近标题字放大+更多行，实心底+左分隔线、非浮空弹窗）+ 可缩放平移画布（无边框、无 scissor 遮罩，画布被组件用深度缓冲遮挡）+ 底部序列区（±/导航等按钮行紧靠在缩略图条上方，缩略图条紧贴屏幕底部、缩略图按宽高比动态缩放：高固定、宽动态、无边框）；每个面板内容约束在自身条带内、控件相对面板锚点定位，任何窗口尺寸不重叠；画布尺寸为「画布」按钮弹窗；另有 G 网格开关、Fit 缩放按钮、上下文状态栏与 B/E/F/I/L/R/H、1..7、`[`/`]` 快捷键；实现 `GraphEditor.Host` 使整图同步守卫与 sendOp 计数生效；双击 IMAGE/IMAGE_SEQUENCE 节点从 MonitorScreen 打开，关闭后恢复图编辑器或终端包装）/ Standalone pixel editor (painting-app UI: **responsive tile layout** — top bar + left panel (PS-style single-column tool rail: flush against the left edge and the right divider, no per-cell borders, only a full-cell highlight on hover/selected, ~30px wide; brush/opacity/current color currently hidden) + collapsed-by-default right color-picker panel replaced by an embedded always-on right panel (scaled ~0.8x, the panel fills the right band down to the screen bottom with no title, bigger section titles and more favorite/recent rows, solid bg + left divider, not a floating popup) + zoomable/pannable canvas (borderless, no scissor mask — the canvas is occluded by the panels via the depth buffer) + bottom sequence area (a button row with ◀/▶ nav, +New and Delete directly above a thumbnail strip that is flush against the bottom of the screen, with thumbnails scaled to the image aspect — fixed height, dynamic width, borderless); each panel keeps its content in its own strip and positions controls relative to its own anchor, so nothing overlaps at any window size; canvas size via a "Canvas" button popup; plus a G grid toggle, a Fit zoom button, a context status bar and B/E/F/I/L/R/H + 1..7 + `[`/`]` shortcuts; implements `GraphEditor.Host` so the full-sync guard and sendOp counting keep working; opened by double-clicking an IMAGE/IMAGE_SEQUENCE node, restores the graph editor or terminal wrapper on close) |
| `RadarLockHandler.java` | 雷达锁定交互 / Radar lock interaction |
| `colorpicker/ColorPickerButton.java` | 颜色选择按钮 / Color picker button |
| `colorpicker/ColorPickerWidget.java` | 颜色选择器组件：支持 `setScale`（缩放渲染 + 鼠标逆变换）与 `setEmbedded`（内嵌模式：无浮空外框、无标题、无「确定/橡皮擦」按钮、不随外部点击关闭；常用/最近 4 行、标题字放大、更高）供像素编辑器内嵌式常驻调色板停靠；**这些行数/高度/标题改动只在内嵌模式生效**，浮空模式（GraphEditor/MonitorScreen）保持原 2 行、246 高、确定键在原位；橡皮擦按钮及逻辑已整体移除；其余调用方仍作浮空弹窗使用（浮空模式保留「确定」按钮）/ Color picker widget: supports `setScale` (scaled render + inverse mouse mapping) and `setEmbedded` (embedded mode: no floating frame, no title, no OK/eraser buttons, no outside-click close; 4 favorite/recent rows, bigger titles, taller) for the pixel editor's embedded palette; **these row/height/title changes apply only in embedded mode** — floating mode (GraphEditor/MonitorScreen) keeps the original 2-row, 246px layout and OK position; the eraser button & logic were removed; other callers keep the floating-popup behaviour (floating mode retains the OK button) |
| `colorpicker/ColorUtils.java` | 颜色工具 / Color utilities |
| `colorpicker/RecentColors.java` | 最近使用颜色持久化 / Recent colors persistence |
| `renderer/MonitorBlockEntityRenderer.java` | 全息显示器 3D 渲染 + HUD 虚像（近处玻璃 + 远处画布 + 相机空间深度锚定 + 手动字形文字）/ Holographic monitor 3D renderer + HUD virtual image (near glass + far canvas + camera-space depth anchor + manual-glyph text) |
| `renderer/MonitorRenderTypes.java` | Monitor 自定义 RenderType（SCREEN_PIXEL / HUD_DEPTH_ANCHOR）/ Custom RenderTypes |
| `renderer/RadarBlockEntityRenderer.java` | 雷达 3D 渲染（BER）/ Radar 3D renderer |

### HUD 虚像渲染架构（v1.2.5，2026-08-24 更新）/ HUD Virtual-Image Rendering Architecture

✅ 已解决 — 深度锚定（东西溢出）/ 文字遮挡 / 俯仰梯样式；详见 [monitor-hud-mode-design.md](./monitor-hud-mode-design.md) 交叉引用。

- **近处玻璃 + 远处画布**：近处玻璃只画边框（`SCREEN_PIXEL`）；内容画在沿 -FACING 100 格的虚像画布（×D 缩放保持角尺寸，`VIRTUAL_IMAGE_D=100`）。
- **相机空间深度锚定（`emitAnchored`，2026-08-24）**：BER poseStack 只含相机平移（相机旋转在 RenderSystem modelView 栈），此前直接取世界 Z 分量当视线深度——玩家面朝东西时 `fz≈0` → `s=zAnchor/fz` 爆炸（虚像溢出）。现在顶点先经 `viewRot`（`camera.rotation()` 的逆）到**真正相机空间**取视线深度，锚定 `V'=(fx·gz/fz, fy·gz/fz, gz)` 到玻璃平面再经 `viewRotInv` 回世界；`s` 钳制 ±`MAX_ANCHOR_S` 防掠射 `fz→0⁻` 的 float 溢出（GPU 视锥干净裁剪）。
- **文字手动字形锚定（方案 X，2026-08-24）**：`font.drawInBatch` 的 buffer 被 Iris `BufferSourceWrapper` 包装（`instanceof BufferSource` 失效，拦截路径不可行）→ 改为反射 `Font.getFontSet`（包私有）+ `BakedGlyph` 私有字段（left/right/up/down/u0/v1，锁定 1.21.1 稳定），手动生成字形 4 顶点（`charMat` 复刻原 drawInBatch 变换链）+ 锚定到玻璃深度，写入独立 `textBuf`（`TRIANGLES` + `POSITION_COLOR_TEX_LIGHTMAP`）。**字符级部分遮罩**：字形 4 角与玩家屏幕 4-gon 求交（`clipPolyToQuad`）+ UV 双线性插值 + 三角扇；冲刷用字形自身 RenderType（正确字体 atlas，`RenderType.text` 在部分环境纹理绑定不可靠→紫黑块）+ `depthMask(false)`（不写深度）+ `disableCull`。
- **俯仰梯（`drawPitchLadder`）**：tan 透视 `ladderCanvasY(pitch−θ)`（+10° 刻度在中心上方，抬头地平线下移）；两段式中空绿色档线 + **两段式白色地平线**（中心留空让准星通过）+ ±10° 起白色度数标注（**±90 分侧**一边一个——tan 周期 180° 使 ±90 刻度同 y，数字分侧避免重叠；其余度数两端标注）。准星 = 固定白色四段中空十字（1/3 尺寸）。
- **三层裁剪（指示线不超出虚像屏幕）**：① **相机平面**（`clipPolyByDepth` 逐顶点 fz，阈值 0，只切相机后方镜像内容）；② **画布矩形**（±hw×±hh 屏幕边框——贴脸时 mask > 画布，档线 pitch 大时兜底）；③ **玩家屏幕 4-gon 遮罩**（`projectGlassCornersToCanvas` + `clipPolyToQuad` + `pointInConvexQuad`）。
- **RenderType**：`HUD_DEPTH_ANCHOR`（vanilla `position_color`，LEQUAL + COLOR_WRITE 不写深度——前方遮挡/后方不遮挡、不污染深度缓冲）；`SCREEN_PIXEL`（玻璃边框）。纯函数（`ladderCanvasY`/`projectGlassCornersToCanvas`/`clipPolyToQuad`/`clipPolyByDepth`/`pointInConvexQuad`/`polyAabb`/`rotatedAabb`）均有单测（`ConformalProjectionTest`、`FacingOverflowDiagTest`）。

---

## 5. `compat/` — Sable 兼容层 / Sable Compat Layer

v1.2.4.1 起访问机制为**编译期桥 + 反射混合**：入口 `SubLevelContainer` 经编译期桥（专用服务器安全），仅部分深层类型（`SubLevel.logicalPose/getLevel`、`Pose3dc`/`Vector3dc`/`Quaterniondc`、`Plot`/`PlotChunkHolder`、包围盒）保留反射。无 Sable 时优雅降级。
/ Since v1.2.4.1: a mix of compile-time bridge (dedicated-server safe) + reflection for deep internals. Graceful degradation without Sable.

| 类 / Class | 功能 / Function |
|----|------|
| `SableAccess.java` | **编译期桥** — `SubLevelContainer.getContainer(level)` / `getAllSubLevels` / `getSubLevelLevel`（`isClientSide` + `ModList.isLoaded("sable")` 守卫）/ Compile-time bridge entry point |
| `SableReflection.java` | 反射助手 — SubLevel/Pose3dc/Plot/包围盒；`SubLevelContainer` 访问委托给 `SableAccess` / Reflection helper for deep internals |
| `SablePoseHelper.java` | 从 SubLevel 提取欧拉角；`isWithinReachableRange` 直接用编译期 API（无反射）/ Extract Euler angles; direct compile-time API |
| `ControlSeatBlockEntitySable.java` | 座椅实体 yaw 追踪子世界旋转（`BlockEntitySubLevelActor`）/ Seat entity yaw tracks sub-level rotation |
| `RadarBlockEntitySable.java` | 雷达子层级扫描 / Radar sub-level scanning |
| `SensorBlockEntitySable.java` | 姿态传感器读取 logicalPose 四元数 / Read logicalPose quaternion |

---

## 6. `mixin/` — Mixin 注入 / Mixin Injection

| 类 / Class | 目标 / Target | 功能 / Function | 配置 / Config |
|----|------|---------|------|
| `LocalPlayerMixin.java` | `Entity.turn()` | 摇杆抑制 + 鼠标增量导出（HEAD 拦截）/ Joystick suppression + raw mouse delta export | `create_schematic_compute.mixins.json` (client) |
| `ControlSeatCameraMixin.java` | Sable 相机 | 禁用 Sable 相机旋转防双重旋转 / Disable Sable camera rotation to prevent double-rotation | `create_schematic_compute.sable.mixins.json` (`required:false`) |

---

## 数据流 / Data Flows

### 单 tick 求值周期 / Single-Tick Evaluation Cycle

```
ServerLevel.tick()
  → FormulaCompute.beginTick()     [ServerTickEvent.Pre:轮转 N_heavy_prev、清 dedup 表]
  → BE.tick()
    → ensureBusRegistered()        [首次 tick / first tick]
    → recoverConflictedChannels()  [每 tick / every tick]
    → graphChanged()? → recompileEvaluatorFull()   [Blueprint / ProgramComputer / Radar / Sensor / ControlSeat]
                      → recompileEvaluatorLight()  [Monitor / SpeedProxy]
    → evaluator.evaluate(inputs, pidState, dt)
      → topo order → eval(node) → outputs
        └ FORMULA:dedup 查表 → 未命中 → 解释器(刀5:循环边界挂起/续算、emit-on-done)
    → saveDebugTimes(runtimeState) [服务端持久化 / server persistence]
    → broadcastEvalSnapshot()
      → ClientboundGraphEvalPacket → tracking clients
```

> 刀5 挂起时 FORMULA 输出冻结（emit-on-done），spread 进度经 `EvalSnapshot.formulaSpreads` 同步客户端渲染进度条。
> / On knife-5 suspension the FORMULA outputs stay frozen (emit-on-done); spread progress syncs to clients via `EvalSnapshot.formulaSpreads` for the render bar.

### 编辑操作流程 / Edit Operation Flow

```
Client A 编辑节点 / edits node
  → GraphEditor → emitOp(GraphOp)
  → GraphEditOpPacket C→S
    → SablePacketHelper.isWithinReachableRange()  [128 格 / blocks]
    → EditSessionRegistry.getEditors()             [编辑会话 / session member]
  → EditSessionRegistry.applyOp()
    → OpExecutor.apply(graph, op)
    → 广播 / broadcast GraphEditOpSyncPacket → 其他编辑器 / other editors → onRemoteOp()
    → ACK / ack GraphEditAckPacket → 发起者 / originator → handleAck()
```

### BUS 频道生命周期 / BUS Channel Lifecycle

```
loadAdditional()/onLoad() → registerBusChannels() → registerChannels()
  → SignalBus.registerChannel(name, map, owner)
    → CHANNELS.putIfAbsent → ChannelEntry(map, owner, refCount=1)
  → n.busConflict = !ok

recompileEvaluatorFull()  [Light 路径同流程 / the Light path follows the same flow]
  → unregisterRemovedBusOutNodes()  [通过 lastBusOutKeys 检测移除 / detect removals]
  → reRegisterChannels(graph, oldGraph)
    → 移除的节点 / removed: unregisterChannel() → refCount-- → zero? → remove + clearBus
    → 保留的节点 / kept: updateChannel()       [不改变 refCount / refCount unchanged]
    → 新增的节点 / new: registerChannel()      [首次注册 / first registration]
  → snapshotBusOutKeys()  [保存当前快照 / save current snapshot]

loadGraphFromBytes()（v1.2.4.1）
  → unregisterBusChannels(graph)
  → 跳过 cleanupBusChannels [不向客户端广播空频段 / no empty band syncs]
  → graph.bumpGeneration() + lastGraphGeneration = -1 [下一 tick 重编译恢复频段 / next-tick recompile restores bands]

onChunkUnloaded / setRemoved
  → cleanupBusChannels()  [清空 BusBandSync / clear band syncs]
  → unregisterBusChannels()
    → unregisterChannel() → refCount-- → refCount==0 → CHANNELS.remove() + clearBus()
```
