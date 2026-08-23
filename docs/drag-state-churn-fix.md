# 拖拽刷新全部节点状态修复 / Drag No Longer Refreshes All Node States

> 日期 / Date：2026-08-24
> 状态 / Status：✅ 已解决 / Resolved

## 问题 / Problem

在节点图中拖动组件（节点 / 注释 / 多选组）时，会**持续刷新全部节点状态**：
/ Dragging a component in the node graph (node / comment / multi-select) continuously **refreshes all node states**:

- **编辑区 / Edit area**：所有展开节点的 EditPanel / EditState 被整体销毁重建（EditBox、公式编辑器闪烁）。
- **时序 / Sequential**：DELAY 队列、flipflop、pulse timer、PID 累加器全部清零重跑（锁存器闪断、积分器归零、DELAY 凭空丢队列）。
- **Monitor 显示模式**：拖显示组件时向所有追踪客户端推送整张图 NBT，多人时带宽爆炸。

## 根因 / Root Cause

单一代际 `NodeGraph.graphGeneration` 被当作「任何改动」的失效信号，同时驱动服务端求值器重建、
客户端编辑区重建、Monitor 显示缓存失效——而**拖拽发送的纯视觉 op 也无条件 bump 它**：

1. **`OpExecutor` 所有 SET_/MOVE op 无条件 `graph.bumpGeneration()`**
   （`MOVE_NODE` 分支 + `EditSessionRegistry.applyOp` 第 9 步又补一次，共 2 次）。
   拖拽按 `DRAG_SEND_INTERVAL_MS = 50` 节流发送（≤20 op/秒，`GraphEditor.java:722`）。
2. 服务端下一 tick `graphChanged()` 为 true → `recompileEvaluatorFull()`
   （`BlueprintBlockEntity.java:44` / `ProgramComputerBlockEntity.java:44`）→
   **`runtimeState.clear()`** —— 主图 DELAY/flipflop/pulse/PID 全部清零（时序刷新）。
3. 客户端 `renderBg` 检测代际变化 → `expandedInitDone = false` → **重建全部展开节点的 EditState**
   （`GraphEditor.java:2066-2079`）—— 远端协作者每收到一个 MOVE_NODE 就全量重建一次（编辑区刷新）。
4. Monitor 显示拖拽：`SET_DISPLAY_LAYOUT` 等 → `applyOp` 第 10 步 `flagFullSync()` →
   `sendBlockUpdated` → `getUpdateTag()` 发送完整图 NBT（20Hz × 全图）。`FULL_SYNC_GRACE_TICKS`（40 tick）
   节流字段早已声明，但**从未被读取**（死代码）。

节点 `x/y` 是纯视觉坐标，不进求值器；只有 `ENCAP_INPUT/OUTPUT` 重排影响求值——而那条路径由
`rebuildInputCache()`（内部 `anyIndexChanged` 才 bump）独立兜底，不依赖 MOVE_NODE 的裸 bump。

## 修复 / Fix

### 1. op 语义分类：纯视觉 op 不再 bump（`graph/OpExecutor.java`）

不 bump 的 op：**`MOVE_NODE`、`SET_ZORDER`、`SET_COMMENT_TEXT`、`SET_COMMENT_COLORS`、`SET_COMMENT_SIZE`**
（纯画布视觉，不进求值器、非 Monitor 显示内容）。

保留 bump 的 op：`SET_PARAM`、`SET_FORMULA`、`SET_DISPLAY_TEXT`、`SET_BANDS`、`SET_KEY_BINDING`、
`TOGGLE_BOOL`、`SET_CTRL_POINTS`（影响求值）；`SET_TEXT_COLOR`、`SET_DISPLAY_LAYOUT`、`SET_LAYER_INDEX`、
`SET_IMAGE_PIXELS`、`SET_IMAGE_SIZE`、`SET_IMAGE_FRAME_TOGGLE`（Monitor 显示缓存以代际为失效信号，
bump 使远端显示模式视图刷新）。

### 2. 移除 applyOp 的无条件父图 bump（`blocks/EditSessionRegistry.java`）

第 9 步不再 `gbe.getNodeGraph().bumpGeneration()`（保留 `markDirty` 持久化）。结构 op 由
`NodeGraph` 内部 bump；子图 op 不 bump 父图——子求值器陈旧检测按子图代际（`GraphEvaluator.subGraphGenerations`），
ENCAP 引脚重映射由已有的 `rebuildInputCache()` 负责。

### 3. 注释拖拽热路径去掉 per-move markDirty（`blocks/GraphEditor.java`）

注释拖拽每帧 `markDirty()`（= bump 代际）改为只在松手时经 op 同步——拖拽期间不再触发编辑区全量重建。

### 4. Monitor 全量同步限流（`blocks/SyncedGraphBlockEntity.java` + `MonitorBlockEntity.java`）

新增 `requestFullSync()`（延迟请求）+ `flushPendingFullSync()`（tick 内按 `FULL_SYNC_GRACE_TICKS`
合并冲刷，冲刷后清 `needsFullSync` 防空转）；`applyOp` 第 10 步显示 op 改走 `requestFullSync()`。
`flagFullSync()` 保持立即发送（加入会话路径仍即时拿到全图）。效果：显示拖拽 20Hz 的 op 只推送
~0.5Hz 全图 NBT；编辑者仍经 op 广播实时同步，非编辑者在下一次冲刷拿到最新图（getUpdateTag
始终携带完整图，合并后的推送反映最新状态）。

## 验证 / Verification

- 新增回归测试 `src/test/java/.../graph/OpGenerationTest.java`：
  - MOVE_NODE 落地坐标但**不** bump 代际；
  - SET_ZORDER / SET_COMMENT_* 不 bump；
  - SET_PARAM / SET_FORMULA / SET_TEXT_COLOR / SET_DISPLAY_LAYOUT / SET_CTRL_POINTS / SET_BANDS 仍 bump；
  - 结构变更（addConnection）仍 bump（基线）。
- 运行 `./gradlew test` 全量通过。

## 交叉引用 / Cross-references

- [`code-architecture.md`](./code-architecture.md) — `graphGeneration` / 三级重编译 / `RuntimeState.clear()` / `FULL_SYNC_GRACE_TICKS`
- README v1.2.5 changelog — 本次修复条目
