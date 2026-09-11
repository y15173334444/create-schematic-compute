# 编辑器输入框丢焦点 / 丢选中 —— 根因分析与修复 / Editor Input Focus & Selection Loss — Root Cause

> **状态**：✅ **已实施**（2026-09-11）。步骤 1–3 与焦点修复落地于 `db38d34`，步骤 5 落地于 `1e1a6a6`，步骤 6 键盘路径同期修复；同日评审补充鼠标路径与 `cullStaleEditStates` 保留调整（见「同日补充」）。`compileJava` + `./gradlew test` 通过；**第四节实机验收仍待执行**。
> Status: ✅ **implemented** (2026-09-11). Steps 1–3 and the focus fix landed in `db38d34`, step 5 in `1e1a6a6`, the step-6 keyboard path fixed in the same batch; a same-day review pass added the mouse path and the `cullStaleEditStates` retention adjustment (see "Same-day addenda"). `compileJava` + `./gradlew test` pass; the **in-game acceptance in section 4 is still pending**.
> **现象**：在节点输入框里输入时，**焦点/节点选中会突然消失**，但输入框本身还在；有时"吞一个字符后节点才重新被选中"。
> **关联**：[`gui-decomposition-plan.md`](gui-decomposition-plan.md)（步骤 2 拆出 `MonitorDisplayEditor` 后开始关注输入路径）、
> [`code-architecture.md`](code-architecture.md)（编辑界面与 `GraphEditor` 契约）。

---

## 一、结论摘要 / Summary

**根因**：`GraphEditor.renderBg()` 用**一个裸 int**（`lastInitGeneration`）跟当前图的 `graphGeneration` 比较，
一旦"不相等"就把**所有展开节点**的编辑状态（`EditState`，即输入框那一整批控件）**整体重建**：

```java
// GraphEditor.renderBg()
if (lastInitGeneration != graph.graphGeneration) {   // ← 跨实例比较，必然误判
    lastInitGeneration = graph.graphGeneration;
    expandedInitDone = false;                        // → 下一段把每个展开节点 createEditState() 一遍
}
if (!expandedInitDone) {
    for (var n : graph.nodes)
        if (n.expanded && n.type != NodeType.ENCAPSULATION && shouldOpenPanel(n))
            nodeEditStatesById.put(n.id, createEditState(n));   // ← 整批换掉 EditBox 实例
    expandedInitDone = true;
}
```

而 `getGraph()` 在不同时刻**返回不同的 `NodeGraph` 实例**（主图 / 子图 / 重载后的新实例），
每个实例各有自己的 `graphGeneration` 计数。运行取证显示代际呈 **1→2→3→4→1 循环**（非单调递增），
于是这段"全量重建"在**交互期间每秒触发 3–8 次**，每次把 4 个展开节点的输入框全部换成新实例。
新实例默认不聚焦、且不属于 `selectedNodes` 里的旧实例，于是表现为**输入中焦点与选中同时丢失**。

**Why it looked like "selection loss"**：选中高亮判定用的是**对象相等**——`NodeRenderer` 里
`selectedNodes.contains(n)`（`NodeRenderer:346/370`）。控件/节点实例一旦被替换，
视觉判定就匹配不上旧实例，表现为"高亮掉了"，而 `selectedNode` 引用其实还在。

---

## 二、运行取证 / Runtime Evidence

> 方法：在关键路径打点（`SchematicCompute.LOGGER.info`），客户端连本地 dev 服务端复现后读 `runs/client/logs/latest.log`。
> 埋点在修复前已全部移除，源码不放诊断语句。

### 2.1 已排除的假设（都有 0 计数支撑）

| 假设 | 实测结果 |
|------|---------|
| 输入触发的 op 回声导致重建 | 收到的 op 计数 **0**（客户端根本没收到服务端广播） |
| 输入字符被吞 | `charTyped` 兜底分支命中 **0** 次 |
| 选中被置 `null` | 全部 `selectedNode = null` 赋值点 **0** 命中 |
| `selectedNodes` 被集合操作清除 | 12 处 `remove/clear` 逐条核对触发条件，**打字路径均不可达**（TAB 多选 / 框选松手 / DELETE / DUPLICATE） |

### 2.2 已确认的事实

| 事实 | 证据 |
|------|------|
| **重建高频发生** | 交互 14 秒内 `rebuild` **43–56 次**，固定节点批次（1/2/3/4），每秒 3–8 次；`prevFocusIdx` 几乎恒为 -1（聚焦框也在被重建） |
| **代际非单调** | `gen-change 2->3 / 3->1 / 1->2 / 2->3 / 3->4 / 4->1` —— 典型的**多实例计数器交叉** |
| **推高代际的调用者** | `NodeGraph.removeNode(:83) ← GraphEditor.keyPressed(:5001) ← MonitorScreen.keyPressed`（删除键路径）<br>`GraphEditor.markDirty(:1845) ← toggleExpand(:1807) ← mouseClicked(:3531)`（展开/折叠路径） |
| **高亮用对象相等** | `NodeRenderer.renderNodes` → `drawNode(..., selectedNodes.contains(n), n == primaryNode, ...)` |

### 2.3 推导出的机制链

```
用户点开面板（4 个节点 expanded）
  → 任一操作 bump 了某个 NodeGraph 实例的代际（或 getGraph() 换到了另一个实例）
  → renderBg 发现 lastInitGeneration != graph.graphGeneration（跨实例比较）
  → expandedInitDone = false
  → 下一帧把 4 个展开节点全部 createEditState()（换掉全部 EditBox 实例）
  → 正在输入的框变成"旧实例"：焦点留在旧实例上、渲染按新实例走
  → 视觉上：输入框还在（新实例），焦点/选中消失（旧实例的状态不再被读）
```

---

## 三、修复方案 / Fix Plan

### 步骤 1（核心，治根）：代际比较绑定到**图实例**
`lastInitGeneration` 由裸 `int` 改为**同时记住实例与代际**（或把该字段移到与图实例绑定的位置），
只有"**同一个实例**的代际变了"才触发重建。这样多实例交叉不再误判。

### 步骤 2（减少 churn）：按**节点级结构指纹**增量重建
即使确属同实例的代际变化，也**只重建结构真的变了的节点**：
为每个已展开节点记录其结构指纹（类型 / 展开态 / 入出引脚数 / 参数 / 连线给定的引脚数等），
指纹未变则跳过 `createEditState`。新增节点首次出现时指纹视为"变化"，自然会被建立。

### 步骤 3（安全网，已实施）：重建时保留焦点与光标
`createEditState` 重建前记录聚焦字段的下标 + 光标位置，重建后按下标还原；
光标仅对 `MultiLineEditBox` 且文本仍够长时还原（越界光标会让 `TextFieldHelper.insert` 抛异常）。

### 步骤 4（已实施）：注释框自动聚焦只在**新建面板**时生效
`if (oldStRef == null) mle.setFocused(true);`
避免重建时"抢焦点"，与 `syncEditStateToSelection()` 的
「非选中节点不得持焦点」规则相冲突。

### 步骤 5（已实施）：渲染侧**按 id** 判定选中（修"高亮框消失/回弹"）
`NodeRenderer` 的高亮判定原本是**对象相等**（`selectedNodes.contains(n)` / `n == primaryNode`，原 `:346/370`）。
整图同步或存档重载会替换节点实例，旧实例于是永远匹配不上 →
**高亮框消失**；下一个 op 把实例换回来时又**回弹**（用户实测："高亮框消失，有时输入时还会被覆盖/回弹"，
而**字符仍能正常输入**，与埋点数据 `focusCount` 始终为 1 完全吻合）。

**修法（最终版：按 id 判定）**：两处判定改为
`NodeRenderer.isSelectedById(Set<GraphNode>, GraphNode)`（遍历选中集按 `sn.id == n.id`）
与 `NodeRenderer.isPrimaryById(GraphNode primary, GraphNode n)`（`primary.id == n.id`）。
下游 `drawNode` / `drawCommentNode` 本来就用 `n.id` 做后续判断（如 `expandedNodeIds.contains(n.id)`），
所以这两处是**唯一**的实例相等依赖点。

> **演进记录**：最初采用"每次渲染前把选中集重映射到活动实例"
> （`GraphEditor.remapSelectionToLiveGraph()`）。它能修好症状，但属于**绕路**——
> 把"实例可能失效"这一前提固化进了渲染循环。改为按 id 判定后，重映射已**整体删除**
> （方法 + 每帧调用），依赖更少、语义更直接：`selectedNode` / `selectedNodes` 即使短暂持有
> 已离开图的实例，高亮判定也只依赖 id，不再需要每帧修补。

### 步骤 6（已实施）：修掉拆分回归 —— 设置面板开着时按键被吞
**症状定位**：用户实测"**只有全息显示器**的方块图里丢焦点"。

**根因（步骤 2 拆分引入的回归）**：`MonitorDisplayEditor.handleKeyPressed` 的结尾是
**无条件 `return true`**，父类据此认为"显示编辑器已处理"，按键再也到不了节点图输入框。
而**设置面板在离开显示模式后并不关闭**（`showSettings` 只在 ESC/× 时清），
用户的真实操作流是"先在显示模式点开设置面板 → 回节点图改输入框"，于是只有显示器这一台设备持续命中。

原版语义对照：`displayMode == false` 时原 `keyPressed` 会继续往下走到 `editor.keyPressed(...)`；
拆分后这条下落路径丢失。同类问题也存在于 `handleCharTyped`（`return false` 阻断了下落）。

**修法**：在设置面板块之后加 `if (!active) return null;`（`keyPressed`）/ `if (active) return false;`（`charTyped`），
把"仅面板打开（非显示模式）且面板未消费"的输入**交还屏幕**；
父类 `charTyped` 的条件同步改为 `active() || settingsOpen()`，与 `keyPressed` 一致。

> **教训**：跨类拆分时，**返回值的语义（谁消费、谁下落）与被搬运的代码同等重要**。
> 拆分清单里应包含"每个 boolean/Boolean 返回值在无匹配分支下应当是什么"。

### 同日补充（评审修复，2026-09-11）/ Same-day addenda (review fixes, 2026-09-11)

1. **步骤 6 的鼠标补丁 / mouse patch for step 6**：步骤 6 只修了键盘路径；同状态的**鼠标**路径有同样的洞。
   原 `MonitorScreen.mouseClicked` 的 `if (showSettings) return handleSettingsClick(...)` 与模式无关，
   拆分后屏幕侧只剩 `if (displayEditor.active())`——节点图模式下面板渲染着却点击穿透到图编辑区
   （关闭/保存按钮、输入框全部不可点）。修法：`MonitorDisplayEditor.handleSettingsClick` 提为 public，
   屏幕在 `settingsOpen()` 时直接路由（显示模式经 `handleClick` 的原转发语义不变）。
   / Step 6 fixed the keyboard path only; the **mouse** path had the same hole for the same state.
   The split dropped the mode-independent `if (showSettings)` click priority, so in graph mode the
   visible panel let clicks fall through to the graph editor (close/save buttons and EditBoxes
   unreachable). Fix: make `handleSettingsClick` public and route it from the screen whenever
   `settingsOpen()` (display mode keeps its original `handleClick` forwarding).
2. **`cullStaleEditStates` 折叠保留 / collapsed-state retention in cullStaleEditStates**：
   随步骤 2 机制引入的清理原本把「折叠但仍在图」的节点状态也一并删除。用户点击折叠
   （`toggleExpand`）本来就会删状态（前后一致），但**整图同步替换实例导致的折叠**原本保留旧状态，
   再展开时经 `createEditState` 的旧状态引用保住 busBox 未提交文本——cull 把这条路也断了。
   现改回：cull 只清「已离开图」的节点；折叠节点保留状态与指纹、只退出展开集合（幂等，不触发
   下一帧恢复）。指纹保留范围从「展开集合」改为「仍在图中」。
   / The cull added with the step-2 machinery also dropped states of collapsed-but-present nodes.
   A user-initiated collapse already drops the state (unchanged), but a collapse arriving via a
   whole-graph sync used to keep the old state, preserving uncommitted busBox text on re-expand
   through `createEditState`'s old-state reference — the cull cut that path too. Restored: the
   cull only drops nodes that left the graph; collapsed nodes keep their state and fingerprint
   and only leave the expanded set (idempotent, no extra restore pass). Fingerprints are now
   retained for nodes still in the graph rather than only the expanded ones.

---

## 四、验证方式 / Verification

1. `./gradlew compileJava test`（本仓测试覆盖 `graph/` 与 `network/`，**不覆盖 GUI**）。
2. **实机验收（唯一有效）**：连本地 dev 服务端 → 打开含输入框的节点图 → 连续输入 ≥10 字符 → 观察焦点与高亮是否保持。
3. **抖动量化**：修复前后对比"交互期间 `createEditState` 调用次数/秒"（修复后应显著下降，理想为 0——无结构变化即不重建）。
4. **回归**：展开/折叠、总线名框、公式编辑器、双人同时改同一节点。

> ⚠️ GUI 层零自动化测试是既有事实（`src/test` 37 个测试类无一覆盖 Screen/控件），
> 因此**步骤 1/2 的收益必须由实机验收与调用次数统计共同确认**，不能只看编译通过。

---

## 五、未决 / Open Questions

1. **为什么 `getGraph()` 会在实例间切换**：需要定位"哪条路径把子图/新实例暴露给 `renderBg`"（候选：子图进出、整图同步替换、BE 重建）。
   本文件记录了症状与调用者，但**实例切换的确切触发点尚未定位**——步骤 1 的修法对"多实例"是通用的，不依赖该定位。
2. **删除键路径**：`MonitorScreen.keyPressed → GraphEditor.keyPressed:5001 → NodeGraph.removeNode` 说明删除键会真的删节点。
   若用户打字时误触默认绑定（Delete），"选中消失"就是节点被删。是否需要为该动作加"输入框聚焦时不响应"的守卫，待定。
