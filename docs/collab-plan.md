# 多人节点编辑协作方案（create-schematic-compute）

> 适用范围：基于 `create-schematic-compute`（NeoForge 模组）的「原理图 / 蓝图节点编辑器」多人实时协同编辑。
> 本文档所有结论均基于对当前代码的实地阅读，引用的类名 / 方法 / 行号来自 `src/main/java/io/github/y15173334444/create_schematic_compute/`。

---

## 1. 背景与目标

### 1.1 现状（代码事实）

通过阅读 `graph/`、`blocks/`、`network/`、`client/` 与 `GraphEditor`，现状可概括为：

- **单人、客户端权威编辑模型**：`BlueprintScreen` 把鼠标 / 键盘事件直接转发给 `GraphEditor`，`GraphEditor` 直接 `mutate` 客户端的 `NodeGraph` / `GraphNode`（无中间 Controller 层）。
- **整图保存**：编辑结果只在点击「Recompile」时，经 `BlueprintScreen.saveGraph()` → `BlueprintSavePacket(pos, byte[])`（整张 `NodeGraph` 压缩 NBT，上限 256 KB）→ 服务端 `loadGraphFromBytes` **整体替换** `graph`。
- **服务端是求值权威，不是编辑权威**：`BlueprintBlockEntity.tick()` 仅在 `ServerLevel` 跑 `GraphEvaluator.evaluate`，客户端 BE 只持副本、不求值。
- **无会话 / 权限 / 锁**：`GraphBlockEntity` 接口被 8 个 BE 实现（`Blueprint`/`ControlSeat`/`Monitor`/`ProgramComputer`/`Radar`/`Sensor`/`SpeedProxy`…），全仓无 `owner/permission/accessor` 概念；任何人打开同一 BE 都能编辑，服务端不感知「谁在编辑」。
- **节点 ID 自增**：`NodeGraph.nextNodeId` 是 `int` 自增分配器，是多人协作的首要冲突源（两人同时 `addNode` 会得到相同 id）。
- **撤销栈静态共享**：`GraphEditor.undoStack/redoStack` 是静态成员，跨所有编辑器实例共享，当前单人未暴露问题，多人下会串栈。
- **唯一的「owner」概念**在 `SignalBus`：`ChannelOwner = record(BlockPos pos, int nodeId)`，用于 BUS 频道命名归属——可作为节点级寻址的现成范式。

### 1.2 设计目标

1. **实时多人同编**：多名玩家可同时打开同一个图（主图或某个 ENCAPSULATION 子图）并看到彼此的改动，延迟尽量低（亚秒级）。
2. **服务端权威**：以 `BlueprintBlockEntity.graph` 为唯一真相源，所有结构性变更先到服务端、应用后广播，避免分裂。
3. **无破坏性冲突**：节点 ID 不冲突；移动 / 参数编辑采用乐观并发 + 节点级软锁，冲突可被看见而非静默覆盖。
4. **复用现有资产**：尽量复用 `NodeGraph` 的变更方法、`GraphEditor.Host` 接口、`SignalBus` 寻址范式、`PacketDistributor.sendToPlayersTrackingChunk` 广播模式，避免重写。
5. **向后兼容**：保留整图 `BlueprintSavePacket` 作为导入 / 兼容路径；单人游玩体验不退化。

### 1.3 非目标（范围边界）

- 不解决「跨存档 / 跨服务器」的图同步（仅同世界内同图）。
- 不做 CRDT 全量无服务器协同（模组本身有服务端，服务端权威更简单可靠）。
- 不做图级权限系统（OP / 白名单），仅做轻量「在线协作者 + 节点软锁」。

---

## 2. 总体架构

推荐采用 **「服务端权威 + 增量 Op + Presence」** 三层架构，而非 OT / CRDT 全文协同。理由：模组已有中心化服务端权威求值（`BlueprintBlockEntity.tick`），且节点图规模有限（单图通常在数百节点内），服务端仲裁 + 乐观并发足够，引入 CRDT 的复杂度收益比过低。

```
                ┌─────────────┐  鼠标/键盘    ┌────────────────┐
  玩家A ───────▶│ BlueprintScreen │──────────▶│   GraphEditor   │
 (客户端)        │  (Host 接口)    │           │  (输入/渲染)     │
                └─────────────┘               └────────┬───────┘
                                                         │ emitOp(op)
                                                         ▼
                                                ┌────────────────┐
                                                │ GraphEditOpPacket│ C→S
                                                └────────┬───────┘
                                                         │ (playToServer)
                                                         ▼
                              ┌──────────────────────────────────────────┐
                              │  服务端  BlueprintBlockEntity (权威 graph)   │
                              │  GraphEditSession:                          │
                              │   · applyOp(op)  →  NodeGraph 变更           │
                              │   · editVersion++                           │
                              │   · broadcastToOtherEditors(op)             │
                              └───────────────────────┬────────────────────┘
                                          S→C 增量 op  │  S→C Presence(光标/锁)
                                                         ▼
                ┌─────────────┐  onRemoteOp   ┌────────────────┐
  玩家B ───────▶│ BlueprintScreen │◀──────────│   GraphEditor   │◀── 应用远程 op
 (客户端)        └─────────────┘             └────────────────┘
```

**关键数据流**：用户操作 → `GraphEditor` 把变更封装成 `Op` → 发服务端 → 服务端 `applyOp`（分配 id、改权威图、`editVersion++`）→ 广播给其他编辑者 → 其他客户端 `onRemoteOp` 应用。发起者本地**乐观应用**同一 op 以保持即时反馈。

---

## 3. 数据模型改造

### 3.1 节点 ID：服务端分配（解决自增冲突）

`NodeGraph.nextNodeId` 不能在客户端并发自增。改造为：

- 新增 **`ADD_NODE_REQUEST`**（C→S，客户端发起）：payload 含 `BlockPos`、`ownerNodeId`（子图归属，-1=主图）、`NodeType`、`x`、`y`、**客户端临时本地 id（tempId）**。
- 服务端收到后用 `nextNodeId++` 分配**权威 id**，构造 `ADD_NODE` op（含最终 id），广播给所有编辑者（含发起者）。
- 发起者本地：收到 `ADD_NODE` 时，将之前用 tempId 创建的占位节点替换为权威 id（或一开始就只乐观画一个临时框，收到权威 op 后再定稿）。
- 其余所有 op（REMOVE/MOVE/CONN/SET_PARAM…）都引用**已被服务端分配过的 id**，天然不冲突。

> 好处：保持 `int` id 体系（`inputCache` 的 `long key`、拓扑排序、`NodeConnection` 全部不变），避免把全代码改为 String id 的巨型重构。

### 3.2 逻辑版本时钟

复用已有的 `NodeGraph.graphGeneration`（结构 / 参数 / 位置变更时 `bumpGeneration`）。在协同层新增服务端侧 **`editVersion`**（long，每次 `applyOp` 自增），用于：

- op 排序与陈旧检测；
- 客户端 `lastAckedVersion` 用于乐观更新回滚判断；
- 断线重连时拉取 `editVersion` 之后的 op 日志（可选，见 §11 重连）。

### 3.3 增量操作（Op）定义

统一 `Op` 结构（建议 Java record 或 NBT）：

```java
enum OpType {
    ADD_NODE, REMOVE_NODE, MOVE_NODE,
    ADD_CONN, REMOVE_CONN,
    SET_PARAM, SET_FORMULA, SET_COMMENT,
    SET_DISPLAY, SET_ZORDER,
    SET_BANDS, ENTER_SUBGRAPH, EXIT_SUBGRAPH
}

record GraphOp(
    OpType type,
    BlockPos graphPos,          // 哪个 BE 的图
    int ownerNodeId,            // -1=主图；否则 ENCAPSULATION 节点 id（子图路由）
    int targetNodeId,           // 目标节点（MOVE/PARAM/...）
    int fromId, int fromPin,    // ADD_CONN / REMOVE_CONN
    int toId, int toPin,
    int tempId,                 // ADD_NODE 时客户端占位
    float x, float y,           // MOVE / ADD_NODE
    float[] params,             // SET_PARAM
    String formula,             // SET_FORMULA
    String displayText,         // SET_COMMENT / SET_DISPLAY
    int commentBgColor, ...,    // SET_COMMENT 颜色
    int sortB,                  // SET_ZORDER
    List<String> bands,         // SET_BANDS
    int editorVersion,          // 发起时的 editVersion（陈旧检测）
    UUID actor                  // 谁发起（用于软锁/高亮/undo 归属）
)
```

> payload 字段直接复用 `GraphNode.save/load` 已有的 NBT 字段名（x/y/params/formula/displayText/comment*/sortB…），减少新定义。

**子图路由**：`ownerNodeId` 由现有 `GraphEditor.getGraph()` 已经能区分「主图 vs `encapsulationParent.subGraph`」的逻辑推导——进入 ENCAPSULATION 时 `getGraph()` 返回子图，此时 op 的 `ownerNodeId` 设为该封装节点 id 即可，服务端据此 `node.subGraph` 应用。

---

## 4. 增量网络协议（network/ 包）

复用 `AllPackets` 的 `PayloadRegistrar` 注册方式，新增以下包：

| 包 | 方向 | 用途 | 复用 |
|---|---|---|---|
| `GraphEditOpPacket` | C→S | 客户端发射单条 op（含 ADD_NODE_REQUEST 形态） | 同 `BlueprintSavePacket` 的 `pos` 寻址 |
| `GraphEditOpPacket` | S→C | 服务端广播已应用的 op 给其他编辑者 | `PacketDistributor.sendToPlayersTrackingChunk` |
| `GraphEditAckPacket` | S→C | 回执：含分配的权威 id、新 `editVersion` | 给发起者定稿 / 更新 `lastAckedVersion` |
| `GraphPresencePacket` | S→C（周期性） | 协作者光标位置、选区、正在编辑的节点 id | 类似 Figma 光标 |
| `GraphJoinPacket` / `GraphLeavePacket` | C→S | 玩家打开 / 关闭该 BE 的编辑 UI | 维护在线集合 |

- **广播分发**：照搬 `RuntimeStateSyncPacket` / `BusBandSyncPacket` 已有的「`sendToPlayersTrackingChunk(chunkPos, packet)`」模式，只发给追踪该图所在 chunk 的玩家（其余玩家不关心，省带宽）。
- **大小**：单 op 通常 < 200 B，远优于 256 KB 整图上限；大批量操作（如粘贴）可合成单条 `ADD_NODES` 批量 op。

> 保留 `BlueprintSavePacket` 整图通道作为「导入 / 兼容 / 单人 Recompile」路径，实时编辑走增量 op，二者不冲突。

---

## 5. 服务端协同引擎

### 5.1 会话注册表 `EditSessionRegistry`

新增轻量注册表（可挂在 `BlueprintBlockEntity` 或独立类）：

- `Map<BlockPos, Set<UUID>> editors`：当前打开该图编辑 UI 的玩家集合。
- `Map<BlockPos, Long> editVersion`：每图的编辑版本号。
- `Map<BlockPos, List<GraphOp>> opLog`：可选 op 日志（用于重连回放 / 审计）。

在 `GraphJoinPacket` / `GraphLeavePacket` 的 handler 中维护集合；BE 被移除 / 世界卸载时清理。

### 5.2 `applyOp(op)` 流程（服务端）

```
1. 校验：BE 存在、玩家在 editors 集合（或允许旁观）、op 字段合法、无环（ADD_CONN 后查 hasCycles）。
2. 路由子图：ownerNodeId==-1 → graph；否则 node.subGraph（递归支持嵌套 ENCAPSULATION）。
3. 执行：
   · ADD_NODE_REQUEST → 分配 id，构造 ADD_NODE，调用 graph.addNode(type,x,y)（已是 O(1) 缓存友好）。
   · REMOVE_NODE      → graph.removeNode(id)（会顺带删连接，BUS_OUT 调 SignalBus.clearBus）。
   · MOVE_NODE        → node.x/y = x,y; bumpGeneration()。
   · ADD_CONN         → graph.addConnection(...)（含去重 + 自环保护）。
   · SET_PARAM        → node.params[i] = v。
   · ... 其余一一对应现有 GraphEditor 的 mutate 点。
4. editVersion++；若影响循环则按需停止运行（沿用现有 graphHasCycles + setRunning(false) 逻辑）。
5. 广播：将 op（含最终 id / editVersion）发给 editors 中除 actor 外的玩家。
6. 回执：给 actor 发 GraphEditAckPacket（权威 id + editVersion）。
```

> 执行目标**直接复用** `NodeGraph` 现成的 `addNode/removeNode/addConnection/removeConnection` 与 `bumpGeneration`，无需重写图逻辑。

### 5.3 避免「整图回写破坏编辑态」

现有 `loadGraphFromBytes` + `sendBlockUpdated` 会把**编辑者自己的**客户端 graph 也整图换掉，导致其 `selectedNode` / `nodeEditStatesById` 悬空（既有隐藏 bug，多人下更严重）。协同期改为：

- 编辑者只收 `GraphEditAckPacket`（定稿 + 版本号），**不**收整图 update；
- 非编辑旁观者仍可用 `getUpdateTag` 整图同步（或改为收增量 op，体验更好）。

---

## 6. 客户端改造

### 6.1 `GraphEditor` 发射 op（替换直接 mutate）

在现有所有 mutate 入口处插入 `emitOp`，覆盖 Explore 报告 §1 整张表：

- 添加节点：`mouseClicked` 右键菜单 → 改发 `ADD_NODE_REQUEST`（本地乐观画临时框）。
- 删除：`X` / `Delete` → `REMOVE_NODE`（本地先移除，发 op）。
- 移动 / 拖拽：`mouseMoved` 实时改 `node.x/y` 做乐观更新；**节流**发送 `MOVE_NODE`（拖拽中每 ~50 ms 或 `mouseReleased` 发最终值）。
- 连线 / 断线：`addConnection` / `removeConnection` → 对应 op。
- 参数 / formula / comment / display / z-order：各 EditBox 提交处，把 `node.xxx = v` 改为「本地赋值 + emitOp」。

实现方式：在 `GraphEditor.Host` 接口新增 `sendOp(GraphOp)` 与 `onRemoteOp(GraphOp)`，`BlueprintScreen` 等 Host 实现里调用 `GraphEditOpPacket` 的发送。8 个宿主 BE 共享同一 `GraphEditor`，**一次改 Host 即统一覆盖**。

### 6.2 乐观更新与回滚

- 发起者本地立即应用 op（即时反馈），记 `pendingOps`（含 `tempId` / 期望值 / `editVersion`）。
- 收到 `GraphEditAckPacket`：把 tempId 节点替换为权威 id，`lastAckedVersion = ack.version`。
- 若收到 `editVersion` 落后他人的冲突提示（§8），按策略回滚或高亮。
- 断线期间本地仍可编辑（乐观），重连后与服务端 `editVersion` 对齐（见 §11）。

### 6.3 接收远程 op

`onRemoteOp`：直接调用与本地一致的 `NodeGraph` 变更方法（复用 §5.2 的执行逻辑，抽成共享 `OpExecutor.apply(graph, op)`），应用后 `bumpGeneration` 触发渲染重画。`selectedNode` 等 UI 状态若指向被远程删除的节点，做空安全处理。

### 6.4 协作者 Presence UI

- 渲染其他玩家的**光标**（不同颜色 + 名字标签），位置来自 `GraphPresencePacket`。
- 显示「正在编辑的节点」软锁高亮（见 §8）。
- 编辑器角落显示在线协作者头像 / 列表。

### 6.5 撤销栈 per-session 化

将 `GraphEditor` 静态 `undoStack/redoStack` 改为 `Map<Pair<BlockPos,UUID>, Deque<CompoundTag>>`，避免多人串栈；快照标注 `actor`。

---

## 7. 冲突处理策略

推荐 **「节点级软锁 + 乐观并发 + 可视化」** 组合，复杂度低、体验好：

1. **节点软锁（Presence 派生）**：任一玩家开始拖拽 / 编辑某节点参数时，`GraphPresencePacket` 携带 `editingNodeId`；其他客户端对该节点置为「被 X 编辑中」——视觉高亮且禁止同时拖拽（但允许查看）。锁随鼠标离开 / 操作结束释放，无需显式加解锁协议。
2. **移动并发**：两人同时拖同一节点，后到的 op 覆盖前者，但软锁已让第二人看到「此节点被占用」并大概率避开；极端同帧冲突采用「后写覆盖 + 短暂闪烁提示」。
3. **结构性冲突（删除 vs 连线）**：远端删了某节点，本地正要连向它 → 服务端 `applyOp` 时检测目标不存在，拒绝该 `ADD_CONN` 并回 `REJECT`，客户端撤回乐观连线。
4. **BUS 频道冲突**：复用现有 `SignalBus.registerChannel` 返回 `false` 的冲突检测，给编辑者 `busConflict` 提示（已有机制）。

> 不采用硬锁 / 全局编辑锁——那会严重影响实时协作手感。软锁 + 后写覆盖在节点图场景下足够。

---

## 8. 撤销 / 重做改造（协同感知）

- **per-session 栈**：见 §6.5，`Map<(pos,player), Deque>`。
- **协同 undo**：`Ctrl+Z` 时，不只在本地弹栈，而是把当前栈顶变更**反向生成 op** 发服务端（如 MOVE 的反向是旧坐标、ADD_NODE 的反向是 REMOVE_NODE）。服务端照常 `applyOp` 并广播，所有客户端一致撤销。
- **他人 undo 可见**：因为 undo 也是 op，所有协作者看到 graph 同步回退，符合直觉。
- 粒度维持「整图快照」即可（当前 `takeSnapshot` 已是整图 NBT），但栈按 session 隔离；若日后要细粒度，可改为「op 反向日志」。

---

## 9. 子图（ENCAPSULATION）协同

- 路由靠 op 的 `ownerNodeId`：进入子图编辑时，`GraphEditor` 已知 `encapsulationParent`，发射 op 带上其 id；服务端 `applyOp` 沿 `node.subGraph` 路由（嵌套层级同理递归）。
- 软锁 / 光标在子图内独立坐标，Presence 包附带 `ownerNodeId` 以便正确显示。
- 复用现有 `getGraph()` 主图 / 子图切换逻辑，无需改动寻址基础设施。

---

## 10. 性能与网络考量

- **节流**：`MOVE_NODE`、连续参数拖动在客户端节流（~20–50 Hz 发送，渲染仍 60 FPS 本地乐观）。
- **批量**：粘贴 / 多节点操作合成一条批量 op，减少包数。
- **分发范围**：仅 `sendToPlayersTrackingChunk`，非全服广播。
- **压缩**：单 op 极小，通常无需压缩；整图导入仍走现有压缩 NBT。
- **大图**：增量 op 天然规避 256 KB 上限；仅整图导入需注意（可分批或提示）。

---

## 11. 分阶段实施路线图

**Phase 0 — 基础设施**
- 定义 `OpType` / `GraphOp` / `OpExecutor.apply`（复用 `NodeGraph` 变更方法）。
- 新增 `GraphEditOpPacket`（C→S、S→C）、`GraphEditAckPacket`、`GraphPresencePacket`、`GraphJoin/LeavePacket`，在 `AllPackets` 注册。
- 新增 `EditSessionRegistry`（维护 editors / editVersion / opLog）。

**Phase 1 — 基础结构协同（最高优先级）**
- `ADD_NODE_REQUEST` + 服务端分配 id + 广播 + ack（解决头号冲突）。
- `REMOVE_NODE` / `MOVE_NODE` / `ADD_CONN` / `REMOVE_CONN` 增量协同。
- `GraphEditor.Host` 增加 `sendOp/onRemoteOp`，`BlueprintScreen` 实现并接通 8 宿主。

**Phase 2 — 属性协同 + Presence**
- `SET_PARAM` / `SET_FORMULA` / `SET_COMMENT` / `SET_DISPLAY` / `SET_ZORDER` / `SET_BANDS` 协同。
- 协作者光标、在线列表、节点软锁高亮 UI。

**Phase 3 — 冲突与撤销**
- 节点软锁 + 冲突可视化 + REJECT 撤回。
- per-session 撤销栈 + 协同 undo（反向 op）。

**Phase 4 — 健壮性优化**
- 断线重连：重连后按 `editVersion` 对齐，拉取缺失 op（用 opLog）。
- 节流 / 批量 / 大图处理；整图回写 bug 修复（§5.3）。
- 可选：opLog 持久化以支持「历史回放 / 时间旅行调试」。

---

## 12. 关键文件改动清单

**新增**
- `network/GraphEditOpPacket.java`（C→S / S→C 复用同一 record + 两方向 handler）
- `network/GraphEditAckPacket.java`
- `network/GraphPresencePacket.java`
- `network/GraphJoinPacket.java` / `GraphLeavePacket.java`
- `graph/GraphOp.java`（`OpType` + record）
- `graph/OpExecutor.java`（`apply(NodeGraph, GraphOp)` 统一执行，供客户端 `onRemoteOp` 与服务端 `applyOp` 共用）
- `blocks/EditSessionRegistry.java`（或并入 `BlueprintBlockEntity`）

**修改**
- `network/AllPackets.java`：注册上述新包。
- `blocks/GraphEditor.java`：所有 mutate 入口插入 `emitOp`；`undoStack/redoStack` 改为 per-session；新增 `onRemoteOp` 入口。（方法：`getGraph` L250、`recompile` L2190、`enterSubGraph/exitSubGraph` L216/L238、`takeSnapshot/performUndo/performRedo` L73/L82/L92、`replaceGraph` L101，以及 §1 表中的 `mouseClicked/mouseMoved/mouseReleased/keyPressed` 各操作点）
- `blocks/BlueprintScreen.java`：`saveGraph()` L69 不再每次整图发送（保留 Recompile 兼容路径）；实现 `Host.sendOp/onRemoteOp`。（`getGraph` L62、`toggleRunning` L83）
- `blocks/BlueprintBlockEntity.java`：`loadGraphFromBytes` L139 与 `getUpdateTag` L183 在协同期避免整图冲掉编辑者；`tick()` L81 维持服务端权威求值；承载 `EditSessionRegistry` 或 `applyOp`。
- `blocks/GraphBlockEntity.java` 接口：可选新增 `sendOp` / 在线状态钩子（让 8 个 BE 统一支持）。
- `graph/NodeGraph.java`：`nextNodeId` 保持服务端分配语义；`graphGeneration` 继续作脏标记（L34-35）。

---

## 13. 风险与缓解

| 风险 | 缓解 |
|---|---|
| 整图 update 冲掉编辑者本地状态（既有 bug） | §5.3：编辑者只收 ack，不收整图；`selectedNode` 等做空安全 |
| `nextNodeId` 并发冲突 | 服务端分配 id（§3.1） |
| 撤销栈串栈 | per-session 栈（§6.5） |
| 网络延迟导致乐观更新抖动 | 节流 + 软锁提示 + ack 定稿 |
| 删除 / 连线竞态 | 服务端 `applyOp` 校验目标存在，REJECT 撤回 |
| 256 KB 上限 | 增量 op 无此问题；仅整图导入需注意 |
| 子图并发编辑 | `ownerNodeId` 路由 + 子图内独立软锁 |
| 大图广播带宽 | `sendToPlayersTrackingChunk` + 节流 + 批量 |

---

## 14. 结论

当前代码是「客户端本地编辑 + 整图保存」的单人模型，但已具备三项可复用基石：**服务端权威求值（`BlueprintBlockEntity.tick`）**、**`GraphEditor.Host` 统一编辑入口**、**`SignalBus.ChannelOwner(pos,nodeId)` 节点级寻址范式**。

因此最务实的多人协作路线是：**以服务端 `graph` 为权威，把 `GraphEditor` 的「直接 mutate」升级为「发射增量 Op」，服务端 `applyOp` 后广播，客户端乐观更新 + 节点软锁 + 协同撤销**。改动集中在 `GraphEditor` 的 mutate 切面与新增一组 `network/` 包，无需重写图模型，且向后兼容现有整图保存路径。按 Phase 0→4 渐进落地，可先打通「加 / 删 / 移 / 连」四大基础操作，再补属性协同、Presence 与冲突处理。
