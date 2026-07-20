# 协作方案 vs 实际实现 — 对照验证报告

> 验证对象：仓库 `create-schematic-compute` 自 `49c3099` 快进到 `5c5b39c`
> （`v1.2.4: Multiplayer Collaboration + Architecture Refactoring`，12 个提交）后的协作实现。
> 对照基准：本文档 `docs/collab-plan.md`（7-12 撰写的方案）。
> 方法：逐文件阅读 `graph/` `network/` `blocks/` 下的新增/改写实现，与方案 §3–§12 逐点比对。

---

## 0. 总判定

**方案的设计骨架被几乎 1:1 落地，且在多处比方案更优、更完整。**

- 方案 Phase 0–3 列出的全部核心机制（增量 Op、服务端 ID 分配、EditSessionRegistry、会话 Join/Leave、Presence、REJECT 撤回、服务端权威求值广播、整图回写规避、协同撤销）**均已实现**。
- 实现额外补强了方案未细化的部分：大文件 **Blob 分片通道**、**更细粒度的 Op 集合**（把 `SET_DISPLAY` 拆成 text/layout/color，新增 image/hotbar/toggle/expand 等）、**客户端 MOVE 插值动画**、**编辑距离校验 + actor 鉴权**、**op 反向协同撤销**。
- 仅 **Phase 4 的"断线重连 opLog 回放"** 明确留作未来（实现里 `opLogs` 已建但 `GraphJoinPacket` 未回放）。
- 发现 **2 处需注意**：旧静态撤销栈未清理（死代码/潜在浪费）、Monitor 显示类 op 仍触发整图 `flagFullSync`（可能让编辑者自己收到整图覆盖，需核实）。

---

## 1. 覆盖率对照表（方案 → 实现）

| 方案设计点 | 位置 | 实现情况 | 备注 |
|---|---|---|---|
| `GraphOp` / `OpType` / `OpExecutor` | §3.3 / §12 | ✅ `graph/GraphOp.java` `OpType.java` `OpExecutor.java` | 字段几乎一致；OpExecutor 仅做"原始变更"，校验交给调用方——与方案 §12 约定一致 |
| 服务端分配节点 ID（解决自增冲突） | §3.1 | ✅ `OpExecutor.apply(ADD_NODE_REQUEST)` 忽略 `tempId`、用服务端 `nextNodeId`；广播 `ADD_NODE` + `GraphEditAckPacket(tempId→assignedId)` | 完全符合 |
| `EditSessionRegistry`（editors / editVersion / opLog） | §5.1 | ✅ `blocks/EditSessionRegistry.java` | 用 `GlobalPos`（维度+坐标）替代方案里的 `BlockPos`，更正确；`opLog` 上限 200 |
| `applyOp` 流程（校验→路由→执行→广播→ack） | §5.2 | ✅ `EditSessionRegistry.applyOp` | 顺序与方案逐条对应：BE 查找→`ownerNodeId` 子图路由→结构校验→执行→广播给他人→ack 发起者 |
| 避免整图回写破坏编辑态 | §5.3 | ✅ `applyOp` 对编辑者**不**调 `sendBlockUpdated`，只发增量 `GraphEditOpSyncPacket`；非编辑旁观者走 `getUpdateTag`/`flagFullSync` | 见 §3 风险点 |
| `GraphEditOpPacket` (C→S) | §4 / §12 | ✅ `network/GraphEditOpPacket.java` | 含自定义 `StreamCodec`，带宽优化（ItemStack/图像仅按需序列化） |
| `GraphEditOpSyncPacket` (S→C) | §4 | ✅ `network/GraphEditOpPacket.java`→`GraphEditOpSyncPacket` | NeoForge 不允许同一 TYPE 双向注册，故拆成独立包复用同一 CODEC——方案里已预见到此约束 |
| `GraphEditAckPacket` | §4 | ✅ `network/GraphEditAckPacket.java` | 携带 `tempId / assignedId / editVersion` |
| `GraphJoinPacket` / `GraphLeavePacket` | §4 | ✅ `network/GraphJoinPacket.java` `GraphLeavePacket.java` | Join 带 80 格距离校验 + BE 类型校验；Leave 向其余编辑者广播"离开" |
| `GraphPresencePacket`（光标/选区/编辑中节点） | §6.4 / §7 | ✅ `network/GraphPresencePacket.java` `GraphPresenceSyncPacket.java` | 含 `cursorX/Y`、`selectedNodeId`、`editingNodeId`、连线拖拽状态——`editingNodeId` 天然实现方案里的"节点软锁" |
| REJECT 撤回（删/连竞态） | §7.3 | ✅ `OpType.REJECT` + `applyOp` 对 `ADD_CONN` 做目标存在性 & 环检测，失败回 `REJECT`；客户端 `onRemoteOp` 收到 REJECT 撤回本地连线 | 完全符合 |
| 服务端权威求值广播 | §2 原则 | ✅ `network/ClientboundGraphEvalPacket.java` + `SyncedGraphBlockEntity.broadcastEvalSnapshot()` | 替代"客户端本地跑 GraphEvaluator"旧架构，与方案"服务端权威"原则一致 |
| `GraphEditor` 发射 op（替换直接 mutate） | §6.1 | ✅ `GraphEditor` 所有编辑入口改 `host.sendOp(op)`；并 `recordOp(...)` 记录 | 覆盖加/删/移/连/参数/formula/comment/display/zorder/hotbar/image 等 |
| `Host.sendOp` / `onRemoteOp` / `handleAck` | §6.1 / §6.3 | ✅ `GraphEditor.Host` 接口；`BlueprintScreen` 已覆写 `sendOp`（发 `GraphEditOpPacket`）、`onRemoteOp`（委托 `editor.onRemoteOp`）、`handleAck` | 8 宿主共享同一 `GraphEditor`，一次改 Host 覆盖全部——与方案 §6.1 预期一致 |
| 协同撤销（反向 op） | §8 | ✅ `GraphEditor.localUndoStack2/localRedoStack2` + `reverseOp()` 生成反向 op 并重发服务端 | **比方案"整图快照 per-session"更优**：细粒度、所有人可见一致回退 |
| 子图（ENCAPSULATION）路由 | §9 | ✅ `applyOp` 与 `GraphEditOpSyncPacket.handle` 均按 `ownerNodeId` 路由到 `encap.subGraph` | 符合 |
| 保留整图 `BlueprintSavePacket` 兼容路径 | §4 | ✅ `BlueprintSavePacket` 仍在 `BlueprintScreen.saveGraph()` 保留（Recompile/导入） | 符合 |
| `AllPackets` 注册新包 | §12 | ✅ `AllPackets.register` 注册全部 8 个协作包（+ `ClientboundGraphEvalPacket` + Blob 2 包） | 符合 |
| 统一 BE 基类承载图/同步 | §1.1 / §12 | ✅ 新增 `blocks/SyncedGraphBlockEntity.java` 抽象基类，整合 7 类 BE 重复的 graph/运行时/BUS/同步逻辑 | 方案仅说"可选并入 BlueprintBlockEntity"，实现做得更彻底（架构重构） |

---

## 2. 实现优于方案之处（方案未覆盖或弱覆盖）

1. **Blob 分片大文件通道**（`network/BlobDataPacket`/`BlobDataSyncPacket`/`BlobRegistry`/`BlobType`）。
   方案 §10 只说"增量 op 无 256 KB 问题"，但图像像素类大负载仍可能超单包上限。实现用分片（chunk）+ 30s 超时重组缓存解决，**真正消除了大负载上限**。同时 `GraphOp` 也保留 `imageData[]` 直传小图，双轨并存。

2. **更细粒度的 Op 集合**。方案只列了 `SET_DISPLAY` 等粗粒度；实现拆为 `SET_DISPLAY_TEXT` / `SET_DISPLAY_LAYOUT` / `SET_TEXT_COLOR`，并新增 `SET_IMAGE_PIXELS` `SET_IMAGE_FRAME_TOGGLE` `SET_HOTBAR_ITEM` `SET_KEY_BINDING` `TOGGLE_BOOL` `EXPAND_NODE` `COLLAPSE_NODE` `SET_COMMENT_SIZE/COLORS`。粒度更细 = 网络更省、冲突面更小。

3. **客户端 MOVE 插值动画**（`OpExecutor.apply(op, animateMoves=true)`）：远程大位移走渲染循环 lerp，落地仍立即权威坐标。方案未提，体验加分。

4. **编辑距离 + actor 鉴权**：`GraphEditOpPacket.handleServer` 与 `GraphJoinPacket.handle` 均校验玩家距 BE ≤80 格；且**忽略客户端自报 `actor` UUID，统一用 `sp.getUUID()`**。方案 §13 列了"网络延迟/作弊"风险但未给鉴权细节，实现补上了。

5. **架构重构**：把 7 个 BE 重复的 graph/运行时/BUS/同步逻辑抽到 `SyncedGraphBlockEntity` 基类。方案只建议"复用"，实现直接消除了重复代码，降低后续维护成本。

---

## 3. 需关注 / 与方案有出入的点

### 3.1 旧静态撤销栈未清理（低风险，建议清理）
- 方案 §6.5 要求把 `GraphEditor.undoStack/redoStack` 改为 per-session。实现**新增**了 per-player 的 `localUndoStack2/localRedoStack2` 并接到 `Ctrl+Z`/`Ctrl+Y`（`opUndo/opRedo`）。
- 但 **旧的 `private static final List<CompoundTag> undoStack/redoStack` 及其 `takeSnapshot`/`performUndo`/`performRedo` 快照逻辑仍在**（L79-80、L234-255），`Host.performUndo/performRedo` 仅是 default 空实现。
- 影响：旧静态栈已不再作为撤销路径被读取（死代码），但 `takeSnapshot` 仍可能在每次操作前 `undoStack.add(...)`，造成无谓的整图 NBT 序列化 + 静态共享态残留。**多人下虽因不被读取而功能无碍，但属代码债务**，建议删除或确认无其它引用。

### 3.2 Monitor 显示类 op 仍触发整图 `flagFullSync`（中风险，建议核实）
- `EditSessionRegistry.applyOp` 对 `SET_DISPLAY_LAYOUT/SET_PARAM/SET_DISPLAY_TEXT/SET_TEXT_COLOR/SET_IMAGE_PIXELS/SET_IMAGE_FRAME_TOGGLE/ADD_NODE/REMOVE_NODE` 会调 `MonitorBlockEntity.flagFullSync()` → `sendBlockUpdated` 给**所有追踪该 chunk 的客户端**（含正在编辑的自己）。
- 编辑者虽然会收到增量 `GraphEditOpSyncPacket`，但**同一变更还会让客户端 BE 整图重载**（`getUpdateTag` → `loadAdditional` 替换 `graph`）。若 Screen 在打开期间未屏蔽 block update packet，会把编辑者的 `selectedNode`/`nodeEditStatesById` 等本地态冲掉——正是方案 §5.3 想规避的 bug。
- 需核实：编辑中 Screen 是否对来自服务端的整图 update 做了忽略/合并。**建议加固**：`flagFullSync` 只对"非当前打开该 UI 的玩家"发送，或编辑者客户端在 `onUpdateTag` 时跳过整图覆盖（优先用已收到的增量 op）。

### 3.3 断线重连回放未实现（已知，符合路线图）
- `EditSessionRegistry.opLogs` 已建（上限 200），但 `GraphJoinPacket.handle` 仅 `flagFullSync` 发整图，**未回放 `opLog`**。方案 §4 Phase 4 本就将其列为"未来"，故一致；如需真正增量重连（避免整图拉取），后续可在 Join 时把 `editVersion` 之后的 op 重发给重连者。

### 3.4 移动节流（方案建议，实现未明显体现）
- 方案 §10 建议 `MOVE_NODE` 节流（~20–50 Hz）。当前 `GraphEditor` 在 `mouseDragged` 中按鼠标事件直接 `host.sendOp(moveNode(...))`，高频拖拽会产生较多包。Presence 同理。功能正确，但在大图/低带宽下建议加客户端节流（方案 §10 已提示，实现可补）。

### 3.5 `editVersion` 未用于乐观回滚判定
- 方案 §6.2 设想客户端用 `lastAckedVersion` + 服务端 `editVersion` 做陈旧检测/回滚。当前实现里 `editVersion` 随广播 op 与 ack 下发，但客户端**未做版本比对回滚**，冲突主要靠 `REJECT` + 后写覆盖解决。对节点图场景够用，但若要支持"断线期间本地乐观编辑后重连合并"，此字段需被真正消费（与 §3.3 联动）。

---

## 4. 结论

方案的**架构选型（服务端权威 + 增量 Op + Presence）被证明是正确的**——作者用几乎相同的文件结构和命名落地了全部核心机制，并在大文件传输、撤销粒度、鉴权、架构去重上做了超出方案的补强。

**建议的后续动作（按性价比排序）：**
1. **核实并加固 §3.2**（Monitor 整图回写对编辑者的影响）——最高优先级，直接关系到协作编辑时的 UX 正确性。
2. **清理 §3.1 旧静态撤销栈**——消除代码债务与潜在竞态隐患。
3. **实现 §3.3 断线重连 opLog 回放**——让重连走增量而非整图（可选，按需）。
4. **补 §3.4 移动/Presence 客户端节流**——优化带宽。

逐文件改动清单与方案 §12 一致，无预期外的大重构遗漏。
