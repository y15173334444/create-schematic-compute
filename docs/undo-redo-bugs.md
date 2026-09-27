# 撤销/重做系统 — 最终设计文档

> 更新日期 / Updated：2026-07-26
> 版本 / Version：1.2.4
> 范围 / Scope：`GraphEditor.java`, `MonitorScreen.java`, `OpExecutor.java`, `GraphPresencePacket.java`

---

## 架构 / Architecture

撤销系统从**静态全局 NBT 快照**重构为**每实例增量 op 级撤销**。

```
用户操作 → sendOp (多人同步) → recordOp (本地入栈)
                               ↓
Ctrl+Z → opUndo() → reverseOp() → OpExecutor.apply (本地还原)
                    → host.sendOp(rev) → 服务端 → 广播给其他客户端
```

### 核心结构

| 组件 | 位置 | 说明 |
|------|------|------|
| `UndoEntry` | `GraphEditor` 内部类 | 单条撤销记录：op + 旧值（oldX/Y/Val/Str），`op` 字段可变用于 ACK 重映射 |
| `undoStack2` | `GraphEditor` 实例字段 | `ArrayDeque<UndoEntry>`（max 100），每编辑器独立 |
| `redoStack2` | `GraphEditor` 实例字段 | `ArrayDeque<UndoEntry>` |
| `currentBatch` | `GraphEditor` 实例字段 | 批量操作暂存，`endUndoBatch()` 时合并为一个 entry |
| `restoreNodeFromNbt` | `OpExecutor` | 从 NBT 字符串恢复节点全部字段 |
| `enterActions` | `GraphEditor` | `Map<EditBox, Runnable>` 失焦/回车/Ctrl+Z 前提交编辑 |
| `selectedNodeIds` | `GraphPresencePacket` | 所有选中节点 ID 数组，用于多人多选锁定 |

---

## 撤销策略 / Undo Policy

### 入栈（recordOp）

| 操作类型 | OpType | 触发时机 | 粒度 |
|---------|--------|---------|------|
| 添加节点 | `ADD_NODE_REQUEST` | 菜单点击/Ctrl+D | 单条（ACK 前 oldVal=本地 ID 兜底） |
| 删除节点 | `REMOVE_NODE` + 同批 `REMOVE_CONN`×N | X 键/Delete | 批量组：先摘连线（含 pinId）再删节点；NBT 快照仅节点本体 |
| 多选移动 | `MOVE_NODE` | `multiDragging` mouseReleased | 批量组（一次 Ctrl+Z 全部归位） |
| 注释移动 | `MOVE_NODE` | comment drag mouseReleased | 批量组（注释+内节点一次归位） |
| 添加连线 | `ADD_CONN` | 拖拽释放 | 单条（携带 pinId） |
| 删除连线 | `REMOVE_CONN` | Tab+左键 / 删除连线键 | 单条（携带 pinId） |
| 数值参数 | `SET_PARAM` | 失焦/回车/Ctrl+Z 前 | 整个编辑会话=1条 |
| 短文本 | `SET_DISPLAY_TEXT` | 失焦/回车/Ctrl+Z 前 | 整个编辑会话=1条 |
| 文本颜色 | `SET_TEXT_COLOR` | 颜色选择器变更 | 单条 |
| 注释颜色 | `SET_COMMENT_COLORS` | 颜色选择器变更 | 单条 |
| 注释尺寸 | `SET_COMMENT_SIZE` | mouseReleased | 批量组（含被推节点） |
| 热栏物品 | `SET_HOTBAR_ITEM` | 选择物品 | 单条 |
| 按键绑定 | `SET_KEY_BINDING` | 按下按键 | 单条 |
| 图像帧开关 | `SET_IMAGE_FRAME_TOGGLE` | 点击 | 单条 |
| 布尔开关 | `TOGGLE_BOOL` | 点击 | 单条 |
| 控制点拖拽 | `SET_CTRL_POINTS` | mouseReleased | 单条 |
| 控制点增删 | `SET_CTRL_POINTS` | add/removeControlPoint | 单条 |
| 模式切换 | `SET_PARAM/FORMULA/CTRL_POINTS` | 二次确认 | 批量组 |

### 不入栈

| 操作 | 原因 |
|------|------|
| 公式编辑（`SET_FORMULA`） | 文本类，逐字触发 |
| 注释文本（`SET_COMMENT_TEXT`） | 文本类，逐字触发 |
| BUS 改名（`commitBusBox`） | 涉及 SignalBus 副作用，不纳入 |
| 展开/折叠 | UI 状态 |
| Z 序同步 | 视觉层，不纳入 |
| 视角书签 | 暂不纳入 |

---

## reverseOp 覆盖矩阵

| OpType | 逆向 | 数据来源 |
|--------|------|---------|
| `ADD_NODE` | → `REMOVE_NODE` | — |
| `ADD_NODE_REQUEST` | → `REMOVE_NODE` | targetNodeId（ACK 后）；fallback oldVal（本地 ID） |
| `REMOVE_NODE` | → `ADD_NODE` | oldStr=NBT 快照完整恢复（**不含连线**——连线由同批 `REMOVE_CONN` 单独记） |
| `MOVE_NODE` | → `MOVE_NODE` | oldX/oldY=旧坐标 |
| `ADD_CONN` | → `REMOVE_CONN` | fromId/fromPin/toId/toPin + `stringValue` 中的稳定 pinId |
| `REMOVE_CONN` | → `ADD_CONN` | oldX/oldY/oldVal=from/pin/to, op.toPin() + `stringValue` 中的稳定 pinId |
| `SET_PARAM` | → `SET_PARAM` | oldVal=旧值 |
| `SET_DISPLAY_TEXT` | → `SET_DISPLAY_TEXT` | oldStr=旧文本 |
| `SET_TEXT_COLOR` | → `SET_TEXT_COLOR` | oldVal=旧颜色 |
| `SET_COMMENT_COLORS` | → `SET_COMMENT_COLORS` | oldX/Y/Val=旧 bg/border/text |
| `SET_COMMENT_SIZE` | → `SET_COMMENT_SIZE` | oldX/oldY=旧宽/高 |
| `SET_HOTBAR_ITEM` | → `SET_HOTBAR_ITEM` | oldStr=旧物品 NBT |
| `SET_IMAGE_FRAME_TOGGLE` | → `SET_IMAGE_FRAME_TOGGLE` | 自逆 |
| `SET_KEY_BINDING` | → `SET_KEY_BINDING` | oldVal=旧绑定 |
| `SET_CTRL_POINTS` | → `SET_CTRL_POINTS` | oldStr=旧控制点编码 |
| `TOGGLE_BOOL` | → `TOGGLE_BOOL` | 自逆 |
| 其他 | → null | 不入栈 |

### 删节点恢复连线（pinId + 占用检测）/ Node-delete wire restore

> ✅ **已实现**（X 单删 + Delete 多选，含多选节点间互连）。

- **捕获**：`removeNode` 之前，把触及被删节点集的连线（按 pinId 去重，互连只记一次）逐条记为
  `REMOVE_CONN`（`GraphOp.removeConn(..., fromPinId, toPinId, ...)`）+ `sendOp`，与 `REMOVE_NODE` 同一 undo batch。
- **pinId 载体**：编入 op `stringValue`（`GraphOp.packConnPinIds` / `parseConnPinIds`，分隔符 U+0001）——协议零扩面。
- **撤销顺序**：batch 逆序 = 先 `ADD_NODE`（NBT 恢复节点）再 `ADD_CONN`（接线）。
- **占用/失效**：`OpExecutor.ADD_CONN` 有 pinId 编码（**哪怕只有一端**）时整条走 pinId 路径——
  缺的一端从当前节点补全；补不齐、pinId 解析不到或**输入脚已被占用**则静默跳过（不整数回落，
  避免引脚漂移接错脚）。无 pinId 编码才走整数引脚（同样有占用检查）。
- **去重键**：被删节点集的连线按字段 List（id + pinId + 索引）去重，不用字符串拼接
  （pinId 可能含 `>` 等任意字符）。
- **重做**：正向重放 `REMOVE_CONN`（`removeConnectionByPinIds`）+ `REMOVE_NODE`。
- **附带**：删线（Tab+左键 / 删除连线键）、拖线创建、Ctrl+D 复制内部连线、子图同步
  均携带 pinId，删除/撤销按 pinId 绑定。

---

## 关键设计决策 / Key Decisions

### 1. 编辑区参数：失焦提交 vs 逐键入栈

- 旧方案：每个 keystroke 调 recordOp → Ctrl+Z 逐字撤销
- 新方案：EditBox 响应器中只 sendOp 同步多人；失焦/Enter/Ctrl+Z 前通过 `enterActions`/`commitFocusedEditBox` 一次性 recordOp
- 结果：整个编辑会话 = 1 个 undo 条目

### 2. ADD_NODE_REQUEST 的 ACK 时序问题

- `addNodeRequest()` 工厂设置 `targetNodeId=0`，真实 ID 由服务端 ACK 异步分配
- `remapNodeId` 在 ACK 到达后将 undo 条目中的 targetNodeId 从 0 更新为服务端 ID
- 兜底：`recordOp` 在 `oldVal` 存本地节点 ID，`reverseOp` 优先用 targetNodeId（>0），否则用 oldVal

### 3. REMOVE_NODE 的 NBT 快照恢复

- 删除节点时调用 `saveNodeNbt()` 保存完整节点 NBT 到 `oldStr`
- `reverseOp` 产生 `ADD_NODE` 时把 NBT 放入 `stringValue`
- `OpExecutor.apply` 检测 `stringValue` 以 `{` 开头则调用 `restoreNodeFromNbt` 恢复所有字段
- **连线不在 NBT 快照里**：由同批 `REMOVE_CONN` 单独记，撤销时在节点恢复之后按 pinId 接回
  （见下文「删节点恢复连线」）
- Connections are NOT in the node NBT snapshot — they ride separate `REMOVE_CONN` batch
  entries and are rewired by pinId after the node comes back.

### 4. MonitorScreen 与 GraphEditor 的撤销分离

- 像素编辑器：独立的 `pixelUndoStack`（int[] 级别），Ctrl+Z 走 `performPixelUndo`
- 图编辑：Ctrl+Z 透传给 `editor.keyPressed()` → `opUndo()`
- 像素编辑器打开时颜色选择器可见，Ctrl+Z 在 colorPicker.isVisible() 分支内优先处理

### 5. 文本操作不入栈

- 公式编辑器、注释文本：sendOp 同步但不 recordOp

### 6. 多选拖动走 multiDragging

- Tab+点击已选节点触发 `multiDragging` 模式
- mouseReleased 为所有选中节点发 MOVE op + recordOp + beginUndoBatch/endUndoBatch
- per-frame 实时节流同步所有节点位置到多人

### 7. 多人多选锁定

- `GraphPresencePacket` 新增 `int[] selectedNodeIds` 字段
- `sendPresenceIfNeeded()` 发送全部选中节点 ID
- `isNodeLocked`/`isNodeLockedByOther`/lock 渲染均遍历数组

---

## 已知限制 / Known Limitations

### 封装子图跨边界撤销

- 子图内编辑 → 子图内 Ctrl+Z ✅
- 子图内编辑 → 退出子图 → Ctrl+Z ❌（`getGraph()` 已切回主图）

### beginUndoBatch 不可重入

- 当前无嵌套使用场景，`resetBatch()` 在每次 `mouseClicked` 开始时清理残留批次

---

## 修改文件清单 / Changed Files

| 文件 | 主要改动 |
|------|---------|
| `GraphEditor.java` | UndoEntry 定义、reverseOp 完整覆盖、enterActions 提交、recordOp 全覆盖、remapNodeId 含批量条目、comment drag preDragX/Y、comment resize 旧坐标捕获、multiDragging mouse release MOVE+undo、multiDragging per-frame 同步、control point drag recordOp、mode toggle batch undo |
| `MonitorScreen.java` | 像素编辑器 Ctrl+Z 路径修复、死代码清理 |
| `OpExecutor.java` | ADD_NODE NBT 恢复、restoreNodeFromNbt 辅助方法 |
| `GraphPresencePacket.java` | 新增 `int[] selectedNodeIds` 字段 + codec |
| `GraphLeavePacket.java` | 适配新构造函数 |
