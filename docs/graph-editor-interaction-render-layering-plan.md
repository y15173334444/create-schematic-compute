# GraphEditor 交互与渲染分层方案 / GraphEditor Interaction & Render Layering Plan — 🔶 待实施 / Proposed

> **状态 / Status**：🔶 **待实施（方案评审中）**。起草于 2026-10-24，基线 `718d38b`（`main`），
> `GraphEditor.java` **5,069 行**。
> **目标 / Goal**：以**可维护性、可扩展性**为首要目标，把 `GraphEditor` 的**交互**与**渲染**拆成
> 可独立阅读、可独立测试、可独立回归的分层；**不更换 `GuiGraphics` 渲染后端**。
> **约束 / Constraint**：
> (a) 外部门面 `GraphEditor.Host` 与现有 public 成员签名不变（外部 `editor.*` 引用 78 处、约 20 个成员）；
> (b) 行为逐像素一致——z 序遮挡、软锁、热栏、文本焦点、拖拽守卫、temp-id/ACK 语义全部保留；
> (c) 每阶段单独提交、可独立回滚，文档与代码分开提交。
> **交叉引用 / Cross-refs**：
> [`graph-editor-render-performance-plan.md`](graph-editor-render-performance-plan.md)（性能、采样、GPU 可行性）、
> [`graph-editor-canvas-jitter-plan.md`](graph-editor-canvas-jitter-plan.md)（虚拟画布与视觉抖动）、
> [`gui-decomposition-plan.md`](gui-decomposition-plan.md)（步骤 6 已收束，6f 后半延后）、
> [`code-architecture.md`](code-architecture.md)（编辑界面架构）、
> [`file-topology-split-review-2026-10-11.md`](file-topology-split-review-2026-10-11.md)、
> [`tech-debt-review-2026-10-11.md`](tech-debt-review-2026-10-11.md)、
> [`CLAUDE.md`](../CLAUDE.md)（权威状态与数据面规范）。

---

## 一、结论 / Decision

| 方案 | 判断 | 理由 |
|---|---|---|
| 自研渲染引擎替换 `GuiGraphics` | ❌ 不做 | MC 1.21.1 的 GUI 只能走 `Screen#render(GuiGraphics)`；4 个文件共 **155 处** `GuiGraphics` 绘制调用，换后端＝纯迁移 + 兼容风险，收益为负 |
| 全量重写 `GraphEditor` | ❌ 不做 | 交互语义（op、撤销、ACK、presence）与业务强耦合，重写等于重造 bug |
| **分层收编（本方案）** | ✅ 采用 | 保留门面，把「命中 → 状态机 → 命令 → op」显式化；渲染拆成有序层但后端不变 |

**一句话 / One line**：换框架不会减少分支，只有把按 `NodeType` 的 if 链改成**数据驱动 + 单一几何真源**才会。

---

## 二、现状证据 / Evidence

### 2.1 体量与集中度

`GraphEditor.java` 5,069 行；最大的 8 段方法占 **39%**：

| 行数 | 位置 | 方法 | 性质 |
|---:|---|---|---|
| 498 | `GraphEditor.java:2449` | `tryExpandedEditAreaClicks` | 按 NodeType 的编辑区 if 链 |
| 361 | `:1344` | `renderBg` | 分层立即模式绘制 + **副作用** |
| 278 | `:3370` | `mouseReleased` | 拖拽落定 + 撤销批次 |
| 245 | `:4107` | `keyPressed` | 快捷键 + 文本编辑 |
| 201 | `:1980` | `tryCommentClick` | 注释交互 |
| 181 | `:3830` | `mouseMoved` | 悬停 + 图表控制点 |
| 106 | `:4422` | `renderTopBar` | 顶栏绘制 + 命中几何 |
| 98 | `:1727` | `mouseClicked` | 命中优先级链 |

### 2.2 三个结构性缺陷

1. **渲染帧在改状态、发网络包**（`renderBg` 内）：
   - `:1345` `viewBookmarks.advanceCameraTransition()` —— 每帧推进相机动画
   - `:1387` `spatialIndex.build(...)` —— 重建空间索引
   - `:1462` `bus.syncBusBands(n)` —— 可能 `sendOp`
   - `:1659` `selectedMenuType = renderer.renderAddNodeMenu(...)` —— 渲染返回值即状态
   - `:1679` `glfwGetGamepadState` + `commitKeyBinding` —— 每帧轮询手柄并提交绑定
   - `:1362-1364` 写 `lastRenderedGen/Cam/Screen` —— 但这套「Phase 2 渲染缓存」字段**全文无任何读取**（死代码）

2. **命中几何依赖渲染副作用**：
   - `EditPanel.java:189` 在 `renderAt` 里写 `st.bandRemoveBtnX/Y`
   - `GraphEditor.java:2752` 在命中测试里读同一组字段
   - `tryExpandedEditAreaClicks` 的注释直接写明「几何与 `EditPanel.renderAt` 对应段落逐行对齐」(`:2602`)——
     即**绘制几何与命中几何是两份手抄副本**，任何 UI 微调都可能只改一边。
   - 可复用的例外：`WireGeometry` 已做到渲染与删除命中同源（提交 `94fee36`），是本方案要推广的模式。

3. **输入路径直接发射业务操作**：全文件 **110 处** `sendOp` / `recordOp` / `history.*` / `host.saveGraph` / `markDirty`，
   多数位于 `mouseReleased` / `keyPressed` / `tryExpandedEditAreaClicks` 内——命中、状态变更、网络 op 三合一，
   无法单测，也无法安全拆分。

### 2.3 有利条件

- **门面窄**：外部只引用 `editor` 的约 20 个成员 / 78 处；`GraphEditor.Host`（`:57`）是唯一契约。
- **已有协作者**：`GraphOpHistory`、`GraphRemoteOpApplier`、`GraphPresenceTracker`、`GraphBusEditor`、
  `GraphViewBookmarks`、`NodeEditStateFactory`、`EditPanel`、`NodeRenderer` 均已拆出。
- **已有测试兜底**（`src/test/.../blocks/`）：`EditAreaOcclusionGateTest`、`NodeAddMenuHitTest`、
  `WireDeleteHitRegressionTest`、`EditorKeysPixelAndWireTest`、`EditPanelViewportCullTest`、
  `DisplaySelectionLockTest`、`NodeRendererPrivateInPinTextTest`。
- **空间索引已存在**：`SpatialIndex` 同时供渲染裁剪与命中候选（`:1387`、`:1804`、`:1943`、`:1986`）。

---

## 三、目标架构 / Target Architecture

```
┌──────────────────────────── Screen（7 个消费者，零改动）────────────────────────────┐
│  AbstractGraphScreen: render/mouse*/key* → GraphEditor 门面（签名不变）              │
└───────────────────────────────────────┬─────────────────────────────────────────────┘
                                        │
┌───────────────────────────────────────▼─────────────────────────────────────────────┐
│ GraphEditor（门面：字段、Host、对外方法；逐步变薄）                                   │
│                                                                                     │
│  ┌───────────────┐   ┌──────────────────┐   ┌────────────────┐   ┌───────────────┐  │
│  │ FrameContext  │──▶│ RenderPipeline   │   │ HitChain       │──▶│ InteractionFSM│  │
│  │（每帧只读快照）│   │ 有序 RenderLayer │   │ 有序纯命中规则 │   │ 状态机        │  │
│  └───────┬───────┘   └────────┬─────────┘   └───────┬────────┘   └───────┬───────┘  │
│          │                    │                     │                    │          │
│          └──────────┬─────────┴─────────┬───────────┴────────────────────┘          │
│                     ▼                   ▼                                           │
│            ┌─────────────────┐   ┌──────────────────┐                               │
│            │ EditPanelLayout │   │ EditorCommands   │──▶ sendOp / recordOp / undo   │
│            │（单一几何真源） │   │（唯一业务出口）  │    host.saveGraph / presence  │
│            └─────────────────┘   └──────────────────┘                               │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

### 3.1 FrameContext（每帧只读）

```java
record FrameContext(
    NodeGraph graph, float camX, float camY, float zoom,
    int screenW, int screenH, int mouseX, int mouseY,
    Set<Integer> selectedIds, GraphNode selectedNode,
    Set<Integer> expandedIds, Map<Integer, EditState> editStates,
    EvalSnapshot eval, Map<Integer, Boolean> flipflops,
    boolean inSubGraph, int encapId, Set<Integer> lockedNodeIds, long nowMs) {}
```

- 渲染与命中**只读**；任何每帧推进（相机过渡、手柄轮询、总线同步）移到 `clientTick()` / `beforeRender()`。
- 不引入场景树、不做 retained mode——`FrameContext` 只是把现有参数收成一个结构。

### 3.2 RenderPipeline（顺序冻结）

```java
interface RenderLayer { void render(GuiGraphics g, FrameContext ctx); }
```

顺序**逐行登记现状，不重排**：
`grid → comments → connections → draggingWire → nodes(含 EditPanel) → feedback → tooltipOverlay →`
`bookmarks → dialogs → colorPicker → topBar → addNodeMenu → presence`。

- `NodeRenderer` / `EditPanel` 内部实现**不动**，只由层调用。
- 层顺序写成一个 `List<RenderLayer>` 常量，并加顺序单测（断言 id 序列），防止后续插入错层。
- 不做脏区缓存：先量再定（现有「渲染缓存」是死的，见 §2.2）。

### 3.3 EditPanelLayout（单一几何真源）

```java
record WidgetRect(String id, int x, int y, int w, int h) {}   // 节点局部坐标
final class EditPanelLayout {
    static List<WidgetRect> compute(GraphNode n, EditState st);  // 纯函数
}
```

- `EditPanel.renderAt` 改为**消费**布局结果绘制；`HitChain` 消费同一结果命中。
- 删除 `EditState` 里「渲染时写入、命中时读取」的字段（`bandRemoveBtnX/Y/W/H`、`freqSlotX/Y` 等），
  改为布局按需计算或缓存一次（缓存必须以 `(nodeId, generation, editStateSignature)` 为键）。
- `tryExpandedEditAreaClicks` 的 498 行 if 链改为：`Map<WidgetKind, WidgetHandler>`，
  每个 `NodeType` 只登记自己需要的控件；新节点类型只加一个 handler。

### 3.4 HitChain（纯命中）

```java
sealed interface Hit permits
    MenuHit, TopBarHit, BookmarkHit, NameDialogHit, DebugChartHit, CommentPopupHit,
    ImportDialogHit, ExportDialogHit, ChromeHit, HotbarHit, ColorPickerHit,
    WidgetHit, ExpandIndicatorHit, CommentHit, PinHit, WireHit, NodeHit, BlankHit, MissHit {}
```

- 每条规则是**纯函数**：`Hit rule(FrameContext ctx, double mx, double my, int button, int mods)`，
  只读、无 `sendOp`、无状态赋值。
- 规则顺序**逐条照抄** `mouseClicked` 现有优先级（`:1727-1827`），包括：
  菜单盖顶栏 → 顶栏 → 命名对话框 → 书签面板 → 调试图表 → 注释色弹窗 → 导入/导出对话框 →
  chrome → 添加菜单 → `EditorKeys.feedClick` 序列引擎 → 热栏 → 调色器 → 平移门控 → 编辑区 →
  Tab 交互 → 展开按钮 → 注释 → 引脚 → 节点/空白。
- 每条规则配边界测试（真实坐标 + 缩放 + 相机），复用现有测试风格。

### 3.5 InteractionFSM + EditorCommands

状态：`IDLE / PRESSED / DRAG_NODE / DRAG_WIRE / DRAG_CONTROL_POINT / DRAG_X_MARKER /`
`BOX_SELECT / PAN / COMMENT_RESIZE / SCROLL_MENU / TEXT_EDIT`。

```java
sealed interface EditorCommand {
    record MoveNodes(List<Integer> ids, float dx, float dy) implements EditorCommand {}
    record SetParam(int nodeId, int index, float value) implements EditorCommand {}
    record ToggleBool(int nodeId) implements EditorCommand {}
    record Connect(int fromId, String fromPin, int toId, String toPin) implements EditorCommand {}
    record DeleteNodes(List<Integer> ids) implements EditorCommand {}
    record DuplicateSelection() implements EditorCommand {}
    // …
}
```

- **唯一业务出口**：命令执行器内部才调用 `sendOp` / `recordOp` / 撤销批次 / `markDirty`，
  并集中保留：temp-id 窗口守卫、ACK 后延迟 op、`isGraphReplaceBlocked` 类守卫、软锁检查。
- FSM 只做「事件 → 状态迁移 + 命令」，不碰渲染、不碰网络。
- 拖拽中的高频移动可以走 `TickCommand`/节流器，保持现有 50ms 发送限频（`lastDragSendTime`）。

---

## 四、实施阶段 / Phases

> 每阶段一个提交；`./gradlew test` 必须绿；每阶段结束跑一次手动回归清单（§五）。

### P0 · 基线护栏（无行为变更）

- 记录 `GraphEditor` 当前方法行数基线与本文件交叉引用。
- 删除死缓存字段 `lastRenderedGen/CamX/CamY/Zoom/ScreenW/ScreenH`（`:476-479`、`:1362-1364`），
  或明确接线——**二选一，不留半死代码**。
- 把 `tryExpandedEditAreaClicks` 内所有控件矩形整理成文档内清单（作为 P2 的搬迁核对表）。
- 验收：`./gradlew test` 绿；无行为变化；行数变化仅来自死代码删除。

### P1 · 渲染副作用外移（render 只读）

- 把 `:1345` 相机过渡、`:1387` 索引重建、`:1462` 总线同步、`:1659` 菜单返回值、`:1679` 手柄轮询
  迁至 `clientTick()` / 新增 `beforeRenderFrame()`。
- `renderBg` 只读 `FrameContext`，不再写任何字段。
- 风险：手柄轮询与相机动画可能晚/早一帧；逐项验证。
- 验收：`renderBg` 无 `=` 赋值（除局部变量）；总线 op 发送次数不增加；手柄绑定手动回归。

### P2 · 控件几何单一真源 + 编辑区分发表驱动

- 新增 `EditPanelLayout`；`EditPanel.renderAt` 改为消费布局。
- 删除渲染侧写入的几何字段，改为布局计算（必要时按 generation 缓存）。
- `tryExpandedEditAreaClicks` → `Map<WidgetKind, WidgetHandler>`；498 行目标 ≤ 180 行（含分发与公共守卫）。
- 每类节点一个 handler：BOOL/GATE/LATCH、REDSTONE 频段槽、音频按钮、FORMULA warm、
  ANGLE_UNWRAP、BUS 频段 ±、DEBUG_SIGNAL_GEN 模式、文本编辑器。
- 验收：`EditAreaOcclusionGateTest` 绿；新增布局几何单测（每类节点，缩放 1.0/0.5/2.0）；
  手动回归所有节点类型的每个编辑区按钮。

### P3 · HitChain 纯函数化

- 按 §3.4 顺序抽出规则；`mouseClicked` 退化为「建 `FrameContext` → 遍历规则 → 首个非 null」。
- 规则不读可变字段（软锁查询经 `ctx.lockedNodeIds` 注入）。
- 验收：新增优先级测试（至少覆盖：菜单盖顶栏、弹窗模态、平移键门控、展开按钮优先于节点、
  注释优先于空白、引脚优先于节点拖动）；现有 4 个命中测试绿。

### P4 · InteractionFSM + EditorCommands

- 引入状态机与命令层；把 110 处业务调用收口到命令执行器。
- `mouseReleased` / `keyPressed` / `mouseDragged` 只做「事件 → FSM」。
- 保留 `Host` 门面与所有外部签名；外部 78 处引用零改动。
- 验收：op 序列快照测试（相同操作序列 → 相同 `GraphOp` 列表）；撤销/重做回归；单人/双人联调。

### P5 · 渲染层列表化

- `renderBg` / `renderTopBar` 按 §3.2 拆成 `RenderLayer`；顺序常量 + 顺序单测。
- `renderTopBar` 的命中几何同时交给 `EditPanelLayout` 风格的 `ChromeLayout`（可选，若 P3 已够用则不拆）。
- 验收：固定相机/状态下的截图 A/B 对比；层顺序单测。

**依赖关系**：P0 → P1 → P2 → P3 → P4 → P5。P2 依赖 P1（布局可在渲染外计算）；P4 依赖 P3。

**规模预估**：`GraphEditor` 约 1,100 行迁移/重写，`EditPanel` 约 200 行改造，新增类 8–12 个，
净行数预计 −300 到 −600；主要收益是可测试性与新节点类型的接入成本。

---

## 五、测试与回归 / Test Matrix

| 层级 | 内容 | 手段 |
|---|---|---|
| 单元 | `EditPanelLayout` 几何（各 NodeType × 缩放） | JUnit 5，纯函数 |
| 单元 | `HitChain` 优先级与边界 | JUnit 5，构造 `FrameContext` |
| 单元 | FSM 迁移表（事件 × 状态 → 命令） | JUnit 5 |
| 单元 | 命令 → `GraphOp` 序列 | 快照断言 |
| 集成 | 现有 7 个 GUI/hit 测试 | `./gradlew test` |
| 手动 | 点击优先级、z 序遮挡、拖拽、缩放、热栏、文本焦点、多人软锁、封装子图 | 清单 D（`gui-decomposition-plan.md`）+ 逐阶段补充 |
| 视觉 | 固定相机/状态截图 A/B | P5 必做；P1/P2 可选 |

---

## 六、风险 / Risks

| 风险 | 影响 | 缓解 |
|---|---|---|
| 文件热度：近 60 天 60 次提交 | 与功能开发冲突、合并灾难 | 功能冻结窗口；每阶段小提交；不与 1.3.0 分支并行 |
| 命中依赖上一帧渲染几何（`EditPanel.java:189` → `GraphEditor.java:2752`） | P2 前若跳过渲染会读到陈旧矩形 | P2 改为按需计算布局；过渡期保留「渲染后布局」兜底 |
| 渲染副作用外移的时序变化 | 手柄轮询/相机过渡差一帧 | P1 单独提交 + 逐项手动回归 |
| 多人语义丢失（temp-id、ACK、软锁、冲突） | 数据面 bug | 命令层集中守卫 + op 序列快照测试 |
| 7 个消费者共享门面 | 某屏行为回归 | 门面签名冻结；逐屏冒烟 |
| 手机端构建/测试耗时 | 反馈慢 | 后台执行、完成通知后取最终结果（遵循 CLAUDE.md 测试规范） |

---

## 七、开放问题 / Open Questions

1. **包位置**：新类放 `blocks/`（保留包级可见性，`GraphEditor` 的 `EditState`、`presence`、
   `nodeEditStatesById` 等是包级/公有）还是新建 `blocks/editor/`？倾向 `blocks/`，避免扩大可见性。
2. **P1 是否接受一帧时序变化**？若不接受，手柄轮询保留在 `renderBg`，但标记为唯一例外。
3. **`EditState` 是否随 P2 重构**？倾向只删「渲染写入」字段，不改类结构，降低风险。
4. **是否同步 1.3.0 分支**？建议只在 `main` 落地，1.3.0 清理分支不反向合并。
5. **P5 是否值得**？若 P1 已把 `renderBg` 压到 ~250 行且层边界清晰，P5 可降级为「只抽 `renderTopBar`」。

---

## 八、不做的事 / Non-goals

- 不更换 `GuiGraphics` 后端，不引入 GL 直绘、场景树、retained mode。
- 不重写 `NodeRenderer` / `EditPanel` 的绘制实现（只改几何来源与调用方式）。
- 不改 `GraphOp` 协议、撤销栈语义、NBT 格式。
- 不改变 `GraphEditor.Host` 外部契约。
- 不在本方案内处理 `keyPressed` 的公式编辑器内部逻辑（只搬路由）。

---

## 九、相邻方案 / Related Plans

| 主题 | 文档 |
|---|---|
| 渲染性能、FPS 采样、GPU 可行性 | [`graph-editor-render-performance-plan.md`](graph-editor-render-performance-plan.md) |
| 虚拟画布、视觉抖动、统一变换 | [`graph-editor-canvas-jitter-plan.md`](graph-editor-canvas-jitter-plan.md) |

GPU 加速评估已移至性能方案 §五；本文件只保留**架构分层**（FrameContext / RenderPipeline /
HitChain / InteractionFSM / Commands），避免与性能文档重复。
