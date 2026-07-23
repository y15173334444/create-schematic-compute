# 代码结构文档 / Code Architecture

> 更新日期 / Last Updated：2026-07-24
> 版本 / Version：1.2.4

---

## 包结构总览 / Package Overview

```
io.github.y15173334444.create_schematic_compute/
├── SchematicCompute.java          ← @Mod 入口 / @Mod entry point
├── ModUtils.java                  ← 工具方法 / Utility methods
├── graph/          (14 files)     ← 节点图核心引擎 / Node graph core engine
├── blocks/         (22 files)     ← 方块·BE·Screen·Menu·编辑器 / Blocks, BEs, Screens, Editor
├── network/        (25 files)     ← 网络包·BUS 总线·Sable 兼容 / Packets, BUS, Sable compat
├── client/         (12 files)     ← 客户端渲染·颜色选择器·便携终端 / Client rendering
├── compat/          (4 files)     ← Sable 物理引擎兼容层 / Sable physics compat layer
├── radar/           (2 files)     ← 雷达目标管理 / Radar target management
├── entity/          (1 file)      ← 控制座椅隐形实体 / Control seat invisible entity
├── items/           (1 file)      ← 便携终端物品 / Portable terminal item
└── mixin/           (1 file)      ← Mixin 注入 / Mixin injection
```

---

## 1. `graph/` — 节点图核心引擎 / Node Graph Core Engine

### NodeType (enum)
84 种节点类型枚举，定义每种节点的 `id`（稳定 NBT 字符串）、输入/输出引脚数、参数名列表。
/ 84 node-type enum defining the stable NBT `id`, input/output pin counts, and parameter names for each type.

| 分类 / Category | 节点 / Nodes |
|------|------|
| 数值 / Values | CONST, REDSTONE_IN, PRIVATE_IN, BUS_IN |
| 数学 / Math | ADD, SUB, MUL, DIV, MOD, POW, ROOT, ABS, CEIL, FLOOR, ROUND, INTERP, SPLIT |
| 三角 / Trig | SIN, COS, TAN, ASIN, ACOS, ATAN2, SINH, COSH, SQRT, LN, LOG, EXP, SEC, CSC, COT, ANGLE_UNWRAP, DIRECTION |
| 逻辑 / Logic | GT, LT, GE, LE, EQ, OR, BOOL, GATE |
| 控制 / Control | PID, PID_POWER, CLAMP, MAP |
| 输出 / Output | REDSTONE_OUT, PRIVATE_OUT, BUS_OUT, SPEED_CTRL |
| 时序 / Sequential | DELAY, LATCH, T_FLIPFLOP, PULSE_EXTEND, LOOP, FUSE, ACCUMULATOR, INTEGRATOR |
| 输入 / Input | KEYBOARD, MOUSE_JOYSTICK, VIEW_ANGLE, MOUSE_BUTTON, GAMEPAD_JOYSTICK, GAMEPAD_BUTTON, GAMEPAD_TRIGGER, WORLD_VIEW, ATTITUDE, FORWARD, ACCELERATION, VELOCITY, POSITION, TARGET_OUT |
| 显示 / Display | TEXT, DATA, IMAGE, IMAGE_SEQUENCE |
| 结构 / Structure | ENCAPSULATION, ENCAP_INPUT, ENCAP_OUTPUT |
| 调试 / Debug | DEBUG_SIGNAL_GEN, DEBUG_PROBE, COMMENT |

### GraphNode
节点数据类。所有 84 种类型共用同一个类（无继承），通过 `type` 字段区分。
/ Node data class. All 84 types share a single flat class (no inheritance); distinguished by the `type` field.

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
- `busConflict` — 频道名已被其他方块占用 / Channel name taken by another block
- `outputValues[]` — 运行时计算值（由 GraphEvaluator 填充）/ Runtime values filled by evaluator
- `displayText, textColor, imagePixels[]` — 显示节点数据 / Display node data
- `commentWidth, commentHeight, commentBgColor` — COMMENT 节点样式 / Comment node styling
- `subGraph` — ENCAPSULATION 子图（递归嵌套）/ Nested sub-graph
- `expanded` — 编辑器展开状态 / Editor expanded state
- `sortB` — Z 序 / Z-order
- `debugCtrlX[], debugCtrlY[]` — DEBUG_SIGNAL_GEN 控制点（transient）/ Control points (transient)
- `probeHistory[]` — DEBUG_PROBE 采样缓冲（transient）/ Sample ring buffer (transient)

**序列化 / Serialization**：`save()` / `load()` 通过 NBT。transient 字段不入 NBT。

### NodeGraph
图的容器。管理节点列表、连接列表、O(1) 查找缓存、拓扑排序。
/ Graph container. Manages node list, connection list, O(1) lookup caches, and topological sort.

**核心方法 / Core Methods**：
- `addNode(type, x, y)` — 创建节点 + 分配 ID / Create node + allocate ID
- `removeNode(id)` — 删除节点 + 级联删除所有关联连接 / Remove node + cascade delete connections
- `addConnection(fromId, fromPin, toId, toPin)` — 去重 + 自环保护 / Dedup + self-loop guard
- `removeConnection(fromId, fromPin, toId, toPin)`
- `getTopoOrder()` — 缓存 Kahn 算法拓扑排序 / Cached Kahn topological sort
- `hasCycles()` — topo 排序大小 < 节点数 → 有环 / Sort size < node count → cycle exists
- `wouldCreateCycle(fromId, toId)` — BFS 预检测 / BFS pre-check
- `getInputValue(nodeId, pin, outputs)` — O(1) 输入查询 / O(1) input lookup via inputCache
- `copy()` — 深拷贝（新 ID）/ Deep copy with new IDs
- `save()` / `load()` — NBT 序列化，带版本迁移 / NBT serialization with migration

### GraphEvaluator
服务端唯一求值器。客户端不实例化此类，通过 `ClientboundGraphEvalPacket` 接收 `EvalSnapshot`。
/ Server-side only evaluator. Clients never instantiate this; they receive `EvalSnapshot` via `ClientboundGraphEvalPacket`.

**求值流程 / Evaluation Flow**：
1. `graph.getTopoOrder()` 获取拓扑排序 / Get cached topological order
2. 对每个节点调用 `eval()` switch 分发 / Switch-dispatch `eval()` per node
3. 生成 `OutputResult` 列表（REDSTONE_OUT 节点）/ Collect OutputResult list
4. `captureSnapshot()` 创建 `EvalSnapshot` 广播客户端 / Capture and broadcast to clients

**状态管理 / State Management**：
- `outputs` — `Map<nodeId, float[]>` 中间输出 / Intermediate outputs
- `pidState` — PID 积分值 / PID integral accumulation
- `delayQueues` — DELAY 节点延时队列 / DELAY per-tick queues
- `flipflopStates` — LATCH/T_FLIPFLOP/GATE 布尔状态 / Boolean state
- `pulseTimers` — PULSE_EXTEND/LOOP/FUSE 计时器 / Tick counters
- `debugTime` — DEBUG_SIGNAL_GEN 相位时间 / Phase time (persisted via RuntimeState)
- `subEvaluators` — ENCAPSULATION 子图递归求值器（懒创建）/ Lazy-created sub-evaluators

### RuntimeState
可序列化的运行时状态快照，由 BE 持有。
/ Serializable runtime state snapshot, owned by the BE.

**持久化状态 / Persisted State**：`pidState`, `delayQueues`, `flipflopStates`, `pulseTimers`, `debugTime`
**子图状态 / Sub-graph State**：`SubState` — 每个 ENCAPSULATION 节点独立的上述五类状态 / Independent state per encapsulation node

### FormulaParser
数学表达式解析器。调车场算法编译中缀→RPN，支持多行脚本。
/ Math expression parser. Shunting-yard algorithm: infix → RPN. Supports multi-line scripts.

**功能 / Features**：
- `compile(formula)` — 编译中缀表达式为 RPN token 列表 / Compile infix to RPN token list
- `evaluate(rpn, vars)` — 执行 RPN / Execute RPN with variable bindings
- `parseScript(formula)` — v1.2+ 多行脚本（赋值、@output、注释、续行）/ Multi-line scripts
- 18 个数学函数（三角函数取度为输入）/ 18 math functions (trig takes degrees)

### OpExecutor
`GraphOp` 应用执行器。服务端和客户端共享，确保变更逻辑单一定义。
/ Shared `GraphOp` executor used by both server and client — single source of truth for all mutations.

**处理 35 种 OpType / Handles 35 OpTypes**：ADD_NODE, REMOVE_NODE, MOVE_NODE, ADD_CONN, REMOVE_CONN, SET_PARAM, SET_FORMULA, SET_DISPLAY_TEXT, SET_TEXT_COLOR, SET_ZORDER, SET_BANDS, SET_HOTBAR_ITEM, SET_IMAGE_PIXELS, SET_CTRL_POINTS, ADD/REMOVE/RENAME/MOVE_BOOKMARK, EXPAND/COLLAPSE_NODE, etc.

### GraphOp / OpType
`GraphOp`：26 字段 record + 16 个静态工厂方法 / 26-field record + 16 static factory methods.
`OpType`：35 种操作枚举 / 35-operation enum.

### DebugSignals
DEBUG_SIGNAL_GEN 信号计算（无状态静态方法）。8 种模式：CONST, STEP, SINE, SQUARE, TRIANGLE, NOISE, PULSE, CUSTOM。
/ Stateless signal computation for DEBUG_SIGNAL_GEN. 8 modes.

### EvalSnapshot
不可变 record：`(outputs, debugTimes, subOutputs, subDebugTimes)`。服务端→客户端广播。
/ Immutable record for server→client broadcast.

---

## 2. `blocks/` — 方块与编辑器 / Blocks & Editor

### SyncedGraphBlockEntity (抽象基类 / abstract base class)
统一 7 个 BE 的共享字段和生命周期 / Consolidates shared fields and lifecycle for all 7 BEs.

**共享字段 / Shared Fields**：`graph`, `running`, `runtimeState`, `evaluator`, `lastEvaluatedGraph`, `lastGraphGeneration`, `needsFullSync`, `cachedEvalSnapshot`, `graphReady`

**共享方法 / Shared Methods**：
- `ensureBusRegistered()` — 首次 tick 注册 BUS 频道 / Register BUS on first tick
- `recompileEvaluator()` / `recompileEvaluatorFull()` / `recompileEvaluatorLight()` — 三级求值器重建 / Three-tier evaluator rebuild
- `broadcastEvalSnapshot()` — 广播 EvalSnapshot → ClientboundGraphEvalPacket
- `getUpdateTag()` — 网络同步（含 40 tick 宽限期）/ Network sync with 40-tick grace period
- `flagFullSync()` — 触发完整图同步 / Trigger full graph sync
- `loadGraphFromBytes()` — 网络包加载完整图 / Load full graph from network bytes

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

### GraphEditor (~3000 行 / lines)
核心节点图编辑器。承载所有渲染/输入/交互逻辑。
/ Core node graph editor. All rendering, input, and interaction logic.

**关键子系统 / Key Subsystems**：
- 节点渲染 / Node rendering (`drawNode`)
- A/B/C 三层遮挡 / Three-layer occlusion (Grid→Comments→Connections→Nodes→Overlays)
- `undoStack` / `redoStack`（旧静态栈 / old static）+ `localUndoStack2`（新 per-instance op 栈 / new per-instance op stack）
- Ctrl+D 复制 / Copy → `PendingCopyGroup` → `flushCopyGroup()`
- 多人协作 Presence / Multiplayer presence (光标/节点锁/金色边框 / cursor/lock/golden border)
- 书签系统 / Bookmark system
- BUS 冲突检测 / BUS conflict detection (`reevaluateBusConflicts`)
- 调试工具交互 / Debug tool interaction (控制点拖拽、探针冻结 / control point drag, probe freeze)

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

| 方向 / Dir | 包 / Packet | 用途 / Purpose |
|------|-----|------|
| C→S | `GraphEditOpPacket` | 编辑操作（含安全校验）/ Edit with validation |
| C→S | `GraphJoinPacket` / `GraphLeavePacket` | 加入/离开编辑会话 / Join/leave edit session |
| C→S | `GraphPresencePacket` | 光标/选区位置 / Cursor/selection position |
| C→S | `BlueprintSavePacket` | 完整图覆盖（兼容路径）/ Full graph overwrite (legacy) |
| C→S | `BlueprintTogglePacket` | 启动/停止执行 / Start/stop execution |
| C→S | `BusBandUploadPacket` | BUS 频段上传 / BUS band upload |
| C→S | `BlobDataPacket` | 分片大数据传输（图像像素等）/ Chunked bulk data (image pixels) |
| C→S | `ControlSeatInputPacket` | 座椅输入 / Seat input |
| C→S | `RadarSettingsPacket` / `RadarLockPacket` | 雷达设置/锁定 / Radar settings/lock |
| C→S | `MonitorSettingsPacket` | 屏幕参数 / Monitor screen params |
| C→S | `ScanSablePacket` | 便携终端扫描 / Portable terminal scan |
| S→C | `GraphEditOpSyncPacket` | 远程编辑操作同步 / Remote edit sync |
| S→C | `GraphEditAckPacket` | ADD_NODE_REQUEST 回执 / Server ID allocation ack |
| S→C | `GraphPresenceSyncPacket` | 远程光标同步 / Remote cursor sync |
| S→C | `ClientboundGraphEvalPacket` | 求值结果快照 / Eval result snapshot |
| S→C | `BusBandSyncPacket` | BUS 频段同步 / BUS band sync |
| S→C | `RuntimeStateSyncPacket` | T-FlipFlop 状态同步 / Flipflop state sync |
| S→C | `BlobDataSyncPacket` | Blob 数据转发 / Blob data relay |

**安全校验 / Security**：所有 C→S 包通过 `SablePacketHelper.isWithinReachableRange(sp, pos, 128²)` + `EditSessionRegistry.getEditors(sl, pos).contains(sp.getUUID())` 双重验证。
/ All C→S packets pass dual validation: distance check + editor membership check.

### SignalBus
全局静态 BUS 注册表 / Global static BUS registry (`ConcurrentHashMap`).

- `SIGNALS` — PRIVATE_IN/OUT 信号 / Private signals (`name → float`)
- `CHANNELS` — BUS_OUT 频道 / BUS output channels (`name → ChannelEntry`)
- `BAND_REGISTRY` — 频段定义 / Band definitions (`name → List<String>`)

**核心方法 / Core Methods**：
- `registerChannel(name, map, owner)` — 首次注册或 refCount++，不同 owner 返回 false / First registration or increment ref; different owner → conflict (false)
- `updateChannel(name, map, owner)` — 更新 map 引用不改变 refCount / Update map ref without touching refCount
- `unregisterChannel(name, owner)` — refCount--，归零时移除 + clearBus / Decrement ref; remove + clearBus at zero
- `registerBands(name, bands)` / `getBands(name)` / `clearBus(name)`

### BusChannelHelper
BUS 频道生命周期管理器 / BUS channel lifecycle manager.

- `registerChannels()` — 注册所有 BUS_OUT，设置 busConflict，同步 bands / Register all, set conflict flag, sync bands
- `unregisterChannels()` — 注销所有 BUS_OUT / Unregister all
- `reRegisterChannels()` — 差异式重注册（只注销移除的，保留现有的）/ Diff-based: unregister removed only
- `recoverConflictedChannels()` — 原 owner 消失时接管频道 / Take over when original owner gone
- `syncBandsFromServer()` — 服务端推送频段到客户端图（跳过冲突 BUS_OUT）/ Push bands to client graph
- `syncIfBandsChanged()` — tick 级频段变更检测 / Per-tick band change detection

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
| `GeometryConstants.java` | 统一布局常量 / Unified layout constants |
| `MultiLineEditBox.java` | 多行文本编辑 / Multi-line text editing |
| `PortableTerminalScreen.java` | 便携终端 UI / Portable terminal UI |
| `RadarLockHandler.java` | 雷达锁定交互 / Radar lock interaction |
| `colorpicker/ColorPickerButton.java` | 颜色选择按钮 / Color picker button |
| `colorpicker/ColorPickerWidget.java` | 颜色选择器浮层 / Color picker overlay |
| `colorpicker/ColorUtils.java` | 颜色工具 / Color utilities |
| `colorpicker/RecentColors.java` | 最近使用颜色持久化 / Recent colors persistence |
| `renderer/MonitorBlockEntityRenderer.java` | 全息显示器 3D 渲染（BER）/ Holographic monitor 3D renderer |
| `renderer/RadarBlockEntityRenderer.java` | 雷达 3D 渲染（BER）/ Radar 3D renderer |

---

## 5. `compat/` — Sable 兼容层 / Sable Compat Layer

通过反射访问 Sable 物理引擎 API。Sable 未安装时优雅降级。
/ Reflection-based access to Sable physics engine API. Graceful degradation when Sable is absent.

| 类 / Class | 功能 / Function |
|----|------|
| `ControlSeatBlockEntitySable.java` | 座椅实体 yaw 追踪子世界旋转 / Seat entity yaw tracks sub-level rotation |
| `RadarBlockEntitySable.java` | 雷达子层级扫描 / Radar sub-level scanning |
| `SensorBlockEntitySable.java` | 姿态传感器读取 logicalPose 四元数 / Read logicalPose quaternion |
| `SablePoseHelper.java` | 从 SubLevel 提取欧拉角 / Extract Euler angles from SubLevel |

---

## 数据流 / Data Flows

### 单 tick 求值周期 / Single-Tick Evaluation Cycle

```
ServerLevel.tick()
  → BE.tick()
    → ensureBusRegistered()        [首次 tick / first tick]
    → recoverConflictedChannels()  [每 tick / every tick]
    → graphChanged()? → recompileEvaluator()
    → evaluator.evaluate(inputs, pidState, dt)
      → topo order → eval(node) → outputs
    → saveDebugTimes(runtimeState) [服务端持久化 / server persistence]
    → broadcastEvalSnapshot()
      → ClientboundGraphEvalPacket → tracking clients
```

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
loadAdditional() → registerBusChannels() → registerChannels()
  → SignalBus.registerChannel(name, map, owner)
    → CHANNELS.putIfAbsent → ChannelEntry(map, owner, refCount=1)
  → n.busConflict = !ok

recompileEvaluator()
  → unregisterRemovedBusOutNodes()  [通过 lastBusOutKeys 检测移除 / detect removals]
  → reRegisterChannels(graph, oldGraph)
    → 移除的节点 / removed: unregisterChannel() → refCount-- → zero? → remove + clearBus
    → 保留的节点 / kept: updateChannel()       [不改变 refCount / refCount unchanged]
    → 新增的节点 / new: registerChannel()      [首次注册 / first registration]
  → snapshotBusOutKeys()  [保存当前快照 / save current snapshot]

onChunkUnloaded / setRemoved
  → cleanupBusChannels()  [清空 BusBandSync / clear band syncs]
  → unregisterBusChannels()
    → unregisterChannel() → refCount-- → refCount==0 → CHANNELS.remove() + clearBus()
```
