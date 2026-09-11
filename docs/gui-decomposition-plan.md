# GUI 巨型文件拆分路线图 / GUI Decomposition Plan — TODO

> **状态**：🔶 **进行中**。起草于 2026-09-11，基线 `dfedbef`。
> **步骤 1 已完成**（`c8643fd`）：`MonitorClipMath` 已拆出，1742 → 1465 行，390 测试全绿。
> **步骤 2 已完成**（`23ec19d`）：显示编辑 GUI 迁至 `MonitorDisplayEditor`，1767 → 368 行。
> Status: 🔶 **in progress.** Drafted 2026-09-11 against `dfedbef`; step 1 landed in `c8643fd`.
> **目标 / Goal**：把 GUI/渲染层的巨型类按**单一职责边界**拆成可独立阅读、可独立回归的文件，
> 首选交付物是**把全息显示器的显示编辑 GUI 从图编辑器中剥离**（见 §3 步骤 2）。
> **约束 / Constraint**：GUI 层**零自动化测试兜底**（见 §1.3），因此每步必须"可编译 + 行为零变更 + 可手动回归"，
> 不接受一次性大搬家。
> **交叉引用 / Cross-refs**：[`code-architecture.md`](code-architecture.md)（7 个编辑界面 · 无 Menu 架构）、
> [`screen-migration-plan.md`](screen-migration-plan.md)（上一次 GUI 大重构：容器界面 → 纯 `Screen`）、
> [`monitor-mode-settings-merge-plan.md`](monitor-mode-settings-merge-plan.md)（显示器设置面板合并）、
> [`formula-pin-render-tech-debt.md`](formula-pin-render-tech-debt.md)（引脚渲染技术债）、
> [`CLAUDE.md`](../CLAUDE.md)（项目规范）。

---

## 一、现状盘点 / Current State

### 1.1 规模基线（2026-09-11，`dfedbef`，口径：`[System.IO.File]::ReadAllLines` 行数）

`src/main/java` 共 **131 个文件 / 35,857 行**。≥1000 行 **8 个**，500–999 行 **8 个**，其余 115 个 <500 行。
**Top 10 占全部 main 代码 47.3%**，且 GUI/渲染层（`*Screen` / `NodeRenderer` / `MonitorBlockEntityRenderer`）独占 8 席。

| # | 行数 | 大小 | 文件 |
|---|------|------|------|
| 1 | 5,671 | 352 KB | `blocks/GraphEditor.java` |
| 2 | 1,767 | 96 KB | `blocks/MonitorScreen.java` |
| 3 | 1,742 | 114 KB | `client/renderer/MonitorBlockEntityRenderer.java` |
| 4 | 1,706 | 90 KB | `client/PixelEditorScreen.java` |
| 5 | 1,640 | 84 KB | `graph/FormulaParser.java` |
| 6 | 1,500 | 86 KB | `blocks/NodeRenderer.java` |
| 7 | 1,226 | 74 KB | `graph/GraphEvaluator.java` |
| 8 | 1,204 | 75 KB | `blocks/EditorSettingsScreen.java` |
| 9 | 1,006 | 58 KB | `blocks/RadarBlockEntity.java` |
| 10 | 775 | 38 KB | `client/MultiLineEditBox.java` |
| 11 | 763 | 37 KB | `client/colorpicker/ColorPickerWidget.java` |
| 12 | 857 | 45 KB | `client/PortableTerminalScreen.java` |
| 13 | 744 | 44 KB | `graph/GraphNode.java` |
| 14 | 655 | 39 KB | `blocks/EditPanel.java` |
| 15 | 632 | 33 KB | `blocks/GraphHost.java` |
| 16 | 628 | 33 KB | `graph/OpExecutor.java` |

> **行号口径警告**：本文所有行号由上述口径得出；代码编辑后再读会偏移。
> **实施时以方法名 / 记录名为主锚点，行号仅作快速定位。**
> **Line-number caveat**: line numbers use the above convention — treat method/record names as the real anchors.

### 1.2 耦合与改动热度（决定施工顺序的两个量）

| 文件 | 被 `main` 引用于 | 近 60 天改动 | 测试兜底 |
|------|-----------------|-------------|---------|
| `GraphEditor` | **19 文件 / 94 处** | **58 次提交**（全仓最热） | ❌ 无 |
| `NodeRenderer` | **15 文件 / 235 处**（全仓最广） | 2026-09-06 | ❌ 无 |
| `MonitorScreen` | 5 文件 / 19 处 | 2026-09-06 | ❌ 无 |
| `PixelEditorScreen` | 4 文件 / 9 处 | 2026-09-06 | ❌ 无 |
| `EditorSettingsScreen` | **2 文件 / 7 处**（最孤立） | 2026-09-06 | ❌ 无 |
| `MonitorBlockEntityRenderer` | 3 文件 / 5 处 | 2026-08-30 | ✅ **2 个测试类直接测它** |
| `RadarBlockEntity` / `GraphNode` / `GraphEvaluator` / `FormulaParser` / `OpExecutor` | 广 | 2026-08-15 ~ 08-29 | ✅ 多 |

**结论 / Conclusion**：
- **改动热度 ≠ 拆分价值**。`GraphEditor` 债务最大却最热（58 次/60 天），在那里做跨类拆分＝持续与功能开发冲突，故**必须垫后**（步骤 6）。
- **被引用广度决定外溢风险**。`NodeRenderer`（235 处）与 `GraphEditor`（94 处）只能"保持门面签名不变、只抽内部实现"。
- **孤立度决定首刀**。`EditorSettingsScreen`（2 文件）与 `MonitorBlockEntityRenderer`（静态纯函数 + 已有测试）是低成本高确定性的起点。

### 1.3 测试现实 / Test Reality（本路线图的第一约束）

`src/test` 共 **37 个测试类**，覆盖 `graph/`（求值、公式、迁移、节点）、`network/`、`compat/`。
**直接测试 GUI 巨型类的只有 2 个**：

| 测试类 | 测什么 |
|--------|--------|
| `client/renderer/ConformalProjectionTest.java` | `MonitorBlockEntityRenderer` 的 `ladderCanvasY` / `projectGlassCornersToCanvas` / `pointInConvexQuad` / `clipPolyToQuad` / `polyAabb` / `rotatedAabb`（HUD 画布数学） |
| `client/renderer/FacingOverflowDiagTest.java` | `MonitorBlockEntityRenderer` 的 `clipPolyByDepth` / `cameraPlaneState` / `clipPolyToCameraPlane` / `MAX_ANCHOR_S`（顶点级深度锚定） |

> 即：**整个 GUI 交互层（点击/拖拽/面板/协作叠加）没有任何自动化测试**。
> 这直接决定了两条实施纪律：**① 每步独立提交、可编译；② 先纯搬迁（方法体一字不改）再谈清理。**
> There is **no automated coverage of the GUI interaction layer** — hence one step per commit, pure-move first.

### 1.4 唯一顺手的可测试播种点

GUI 层几乎不可单测，但有两处**纯逻辑**可以在拆分时**顺手补 JUnit**，把"只能手动回归"变成"有断言"：

1. `MonitorBlockEntityRenderer` 的裁剪/投影数学（步骤 1 的产物）——已有测试，迁走即继承。
2. `PixelEditorScreen` 的像素内核（`paintBrush` / `floodFill` / `drawLineCells` / `drawRectCells` / `blendAlpha` +
   `List<int[]>` 撤销栈三件套，约 1322–1705）——**无 I/O、无 Minecraft 依赖**，可脱离屏幕单测（步骤 4）。

---

## 二、拆分原则 / Principles

1. **行为零变更优先**：先做**纯搬迁**（方法体不修改，仅改可见性 / 接收者 / `static` 归属），
   再做后续清理。**同一提交内不混合"搬迁"与"改写"。**
2. **门面保留**：被广泛引用的类（`NodeRenderer` / `GraphEditor`）**对外签名不变**，只把内部实现挪走；
   调用方零改动是可验证的强信号。
3. **几何单一来源**：本仓已有约定——渲染 / 命中测试 / 拖拽共用同一份几何函数（见 `EditorSettingsScreen`
   内 `// ── 键位列表几何（渲染 / 命中 / 拖拽共用单一来源）` 注释）。拆分**不得**把一份几何裂成两份。
4. **不要为了拆而拆**：`FormulaParser`（1,640）`GraphEvaluator`（1,226）`GraphNode`（744）`OpExecutor`（628）
   是**内聚的领域逻辑**且已有测试，**不在本轮范围**（§5）。
5. **双语注释延续**：新增文件的注释保持中文+英文双语（CLAUDE.md 代码规范）。
6. **一次提交一件事**：`refactor:` 前缀，文档与代码分开提交（CLAUDE.md 提交规范）。

---

## 三、路线图 / Roadmap

排序依据：**风险从低到高 × 收益确定度从高到低**。步骤 1–5 均不触碰 `GraphEditor`，可在任何时间并行于功能开发；
步骤 6 需要功能冻结窗口。

```
Step 1  MonitorBlockEntityRenderer 裁剪数学      ✅ 已完成 c8643fd（390 测试全绿）
Step 2  MonitorScreen 显示编辑 GUI 脱离          ✅ 已完成 23ec19d（1767 → 368 行）
Step 3  EditorSettingsScreen 按 tab 拆分         🟢 低风险（最孤立）
Step 4  PixelEditorScreen 内核 / 帧条拆分        🟡 中（可补单测）
Step 5  NodeRenderer 按渲染品类拆（保门面）      🟡 中（引用最广）
Step 6  GraphEditor 五刀 + 内部方法级拆解        🔴 高（最热文件，需冻结窗口）
Step 7  小文件批量归位                           ⚪ 择机
```

### 步骤 1 · `MonitorBlockEntityRenderer` 抽出 HUD 裁剪/投影数学

- **抽什么**（`public static` 纯函数，约 200–250 行）：`clipPolyToCameraPlane`、`cameraPlaneState`、
  `projectGlassCornersToCanvas`、`pointInConvexQuad`、`cross2`、`intersect2`、`clipPolyToQuad`、
  `clipPolyByDepth`、`polyAabb`、`rotatedAabb`、`ladderCanvasY`，以及常量 `MAX_ANCHOR_S` /
  `CAM_FRONT` / `CAM_CROSSING` / `CAM_BEHIND`。
- **目标文件**：`client/renderer/MonitorClipMath.java`（新）。
- **为什么放第一**：这些方法**已经是 `public static` 且被 2 个测试类直接调用** → 搬迁后测试只需改引用，
  **行为零变更可被断言验证**，是全仓唯一"有测试兜底"的 GUI 拆分。
- **风险**：仅 2 处调用方（渲染器自身 + 测试）。注意 `MAX_ANCHOR_S` 是包级 `static final`，
  测试直接读它，可见性必须保持 `public`（或包级 + 测试同包）。
- **PR 拆分**：1 个提交即可（`refactor: extract monitor HUD clip math into MonitorClipMath`）。

#### ✅ 实施记录 / Implementation record（已完成）

落地为 `client/renderer/MonitorClipMath.java`（315 行，`public final` + 私有构造）。实际搬走 **4 组**：

| 组 | 内容 |
|----|------|
| 一 | 姿态仪纯函数：`LADDER_CANVAS_SCALE` + `ladderCanvasY` |
| 二 | 相机平面裁剪：`EMPTY_POLY`、`CAM_PLANE_MARGIN`、`clipPolyToCameraPlane`、`CAM_FRONT/CROSSING/BEHIND`、`cameraPlaneState`，以及 `MAX_ANCHOR_S` |
| 三 | 4 边形遮罩投影 + 多边形工具：`projectGlassCornersToCanvas`、`pointInConvexQuad`、`cross2`、`intersect2`、`clipPolyToQuad`、`clipPolyByDepth`、`polyAabb`、`rotatedAabb` |
| 四 | （**未搬**，见下方"实施发现"②）`cameraPlaneSection` 的 Javadoc —— 该函数在本仓并不存在 |

`MonitorBlockEntityRenderer` 1742 → **1465 行**（−277）；测试 2 个类共 31 处引用改为 `MonitorClipMath.*`。

**实施发现（供后续步骤参考的三条硬经验）**：

1. **抽取必须用"花括号自平衡"做客观校验**。用锚点取块时，仅靠肉眼选边界会漏行：首次尝试漏掉了
   `clipPolyByDepth` 的调用行与 `CAM_FRONT/CROSSING/BEHIND` 常量（它们正好落在所选区间之外），
   编译期报 11 个"找不到符号"。教训：**取块后先断言 `{` 数 == `}` 数，再断言关键符号存在**，
   比事后编译试错快得多。
2. **存在"孤儿 Javadoc"**：`MonitorBlockEntityRenderer` 里有一段描述"玻璃矩形投影到画布平面的截面"
   的 Javadoc（原 1481–1495 行）**挂在 `flushTextNoCull` 头上但描述的是另一个函数**，被描述的
   `cameraPlaneSection` 在渲染器里根本不存在（疑似历史删除遗留）。本次搬迁**未动它**（零行为变更
   优先），但**建议后续单独确认其归属**——它属于技术债，不属于本步骤。
3. **同名成员会造成静态导入冲突**：`MAX_ANCHOR_S` 在渲染器里有第二份声明（第 315 行），与
   `import static ...MAX_ANCHOR_S` 直接冲突（Java 报错）。处理方式：**删掉本地重复声明，统一由
   静态导入引用**（常量在 `MonitorClipMath` 只有一份，符合"单一真相源"）。后续步骤抽常量时，
   先 `grep` 目标文件里是否已有同名声明。

### 步骤 2 · `MonitorScreen` 显示编辑 GUI 脱离 ★（本路线首选交付物）

**现状**：`MonitorScreen`（1,767 行）用 `private boolean displayMode` 在一个类里混装**两个模式**：

| 行段 | 约行数 | 归属 |
|------|-------|------|
| 字段 29–120 + 构造 122–150 + Host 实现 152–190 | ~180 | 两模式共用（构造里 `editor.setNodeFilter` 属图模式） |
| `renderGraphCanvas` 的 else 分支 204–207 + 切换按钮 1265–1269 | ~10 | 节点图模式 |
| 双击 IMAGE/IMAGE_SEQUENCE → 像素编辑器 1270–1296 + `openPixelEditor` / `computePixelEditorReturn` 1165–1197 | ~60 | 节点图模式 |
| **显示区渲染** `renderDisplayArea` 316–521 + 元素收集/缓存 1041–1147 + `getContentArea` / `getEffectiveScreen*` / `getEvalOutputs` 291–315 | **~400** | **显示模式** |
| **图层面板** `renderLayerPanel` 602–728 + `renderLayerThumbnail` 534–601 + 点击/拖拽/自动滚动/滚动条 729–854 | **~330** | **显示模式** |
| **设置面板** `renderSettingsPanel` 856–951 + `saveAllSettings` 952–984 + `sendTabMode` 985–995 + `handleSettingsClick` 996–1040 + `SETTING_KEYS` / `HUD_SETTING_KEYS` 102–114 | **~185** | **显示模式** |
| **工具条 / 切换按钮** `renderDisplayToggleButton` 1156–1173 + `drawBtn` 1148–1154 | ~30 | 显示模式（入口在图模式） |
| **显示模式输入路由** 工具栏与 S/R 编辑点击 1300–1472、`updateDisplayDrag` 1529–1571、`renderDisplayPresence` 223–269，以及 `mouseClicked/Moved/Dragged/Released/Scrolled/keyPressed/charTyped/preClose` 中的 `if (displayMode)` 分支 | **~450** | **显示模式** |
| presence 上报 1198–1208 | ~10 | 依赖显示模式状态 |

- **抽什么**：上表**显示模式**行全部（约 800–900 行）→ `blocks/MonitorDisplayEditor.java`。
- **`MonitorScreen` 保留**：`AbstractGraphScreen` 子类职责（`getBE` / `isBlockEntityValid` / `saveGraph` /
  `toggleRunning` / Host 实现）、节点图模式渲染与输入、像素编辑器双击入口，以及**组合转发**
  （把屏幕事件在显示模式下转交新类）。
- **为什么边界干净**：(a) 显示模式状态全是 `private`；(b) 外部只经 `GraphEditor.Host` 读 3 个方法
  （`getPresenceMode` / `getPresenceCursorX` / `getPresenceCursorY`）与 `isDisplayDragInProgress()`，
  这些**继续由 `MonitorScreen` 委派**，Host 契约不变；(c) 几何常量与工具函数已在 `client/GeometryConstants`
  （`MONITOR_TOOLBAR_H` / `MONITOR_SETTINGS_PANEL_W` / `LAYER_PANEL_W` / `LAYER_ROW_H` /
  `LAYER_THUMB_SIZE` / `LAYER_DRAG_THRESHOLD` / `TOOLBAR_BUTTONS` 等），不构成阻碍。
- **✅ 已核对的额外好消息**：`MonitorScreen` 里那批几何助手**已经是 `GeometryConstants` 的薄包装**，
  不是第二份实现——搬迁时**直接把这一层包装删掉、改调 `GeometryConstants`** 即可，
  不会违反原则 3（几何单一来源）：
  `elemRotAABB`（MonitorScreen 1127–1129 → `GeometryConstants.elemRotAABB`）、
  `clampImageNorm`（1121–1125 → `GeometryConstants.clampImageNorm`）。
  **搬迁时顺手清掉这层冗余包装，是本步附带的收益。**
- **目标文件**：`blocks/MonitorDisplayEditor.java`（**同包** → 零 import 变动），
  由 `MonitorScreen` 构造并持有一个实例。

#### ⚠️ 必须原样保留的隐性契约 / Hidden invariants to preserve

这些是从既有注释与提交史里挖出的**行为约束**，搬迁时最容易踩碎，逐条对照回归：

| # | 契约 | 出处 | 若破坏的后果 |
|---|------|------|-------------|
| 1 | 显示模式也要持续调用 `editor.sendPresenceIfNeeded()` | `renderGraphCanvas` 注释：节点图模式的 `renderBg` 不在显示模式运行 | 显示布局模式下队友光标/在线列表停止更新 |
| 2 | 拖拽期间流式发送 `GraphOp.setDisplayLayout` 必须维持 `pendingLocalOps > 0` | `updateDisplayDrag` 注释：整图同步守卫的第二道防线 | 拖拽中收到整图替换 → 位置跳变 |
| 3 | `updateDisplayDrag` 必须同时挂在 `mouseMoved` **与** `mouseDragged` | 注释：触屏只产生 `mouseDragged` | 触屏拖拽本地渲染冻结、松手才同步 |
| 4 | 整图替换后 `selectedDisplayNode` 按 id 重映射到当前图 | `handleDisplayAreaClick` 内注释（长注释，解释"首次拖动正常、之后本地视觉不更新"） | 拖拽绑定到孤儿节点：渲染冻结、松手才同步、远端却可见 |
| 5 | `pixelEditorTransfer` + `skipLeaveOnClose()`：转移到独立像素编辑器时**跳过**离开协作会话 | 字段注释 + `AbstractGraphScreen.onClose` 注释 | 打开像素编辑器会断开协作会话，回来需重 join |
| 6 | 显示区渲染缓存以 `generation` + `screenW/L` 为键 | 字段 84–88「Phase 2: Display area render cache」 | 每帧重建元素列表 → 帧率下降 |
| 7 | 工具栏 / 设置面板 / 图层面板的几何在渲染与命中测试之间必须为**同一来源** | 步骤 2 涉及的 4 处几何计算 | 点击热区与画面对不上（本仓已修过同类 bug） |

- **PR 拆分（建议 3 刀，每刀独立可编译）**：
  1. **纯搬迁**：把显示模式状态 + 方法整体移入新类，`MonitorScreen` 只保留转发。
     **不放任何行为改动**（此刀最大、最需要 review 的是"有没有漏搬状态"）。
  2. 抽取两模式共用的**屏幕几何助手**（`computeDisplayArea` / content 区换算）。
  3. 按面板二次拆（图层面板 / 设置面板成为新类的内部协作对象或独立文件）——**可选**，视第 1 刀后的文件大小决定。
- **验证**：见 §4 手动回归清单 A（**含双客户端协作项**）。

#### ✅ 实施记录 / Implementation record（已落地 `23ec19d`，待游戏内手动回归）

落地为 `blocks/MonitorDisplayEditor.java`（1,579 行，`public final`，构造注入 `Host` 接缝）。
`MonitorScreen` **1767 → 368 行**：保留节点图模式、`GraphEditor.Host` 样板、像素编辑器双击入口与显示切换按钮，
并实现 `MonitorDisplayEditor.Host`。

| 搬迁内容 | 说明 |
|---------|------|
| 显示区渲染 | `DisplayArea` 几何、`renderDisplayArea`、元素收集 + Phase-2 渲染缓存、`renderPixels` |
| 图层面板 | 缩略图、行命中、拖拽排序、自动滚动、滚动条（按下 + 拖动两条路径） |
| 设置面板 | 屏幕 8 参数 + HUD 模式 + 虚像缩放、实时预览、保存/应用 |
| 输入路由 | `handleClick` / `handleMouseMoved` / `handleMouseDragged` / `handleMouseReleased` / `handleMouseScrolled` / `handleKeyPressed` / `handleCharTyped` |
| 协作叠加层 | 显示模式队友光标 + 拖拽描边、存在包模式覆写点 |
| 转移与收尾 | `pixelEditorTransfer` 标记、`preClose` 拖拽补发 |

**结构性调整（唯一一处非机械改动，已写进提交信息）**：显示模式的**工具栏条**改由屏幕侧绘制
（`Host#drawToolbarStrip`），以保持原来"基础按钮先画、切换按钮覆盖其上"的绘制顺序；
S/R 编辑项所需状态经 `selectedNode()` / `editingScale()` / `editScaleBuf()` 等只读访问器暴露。

**实施中发现（四条，供后续步骤复用）**：

1. **隐式外层访问要用带排除的 token 级重写**。`blockPos → host.blockPos()` 这类简单 `String.Replace`
   会把替换结果再替换一次（`host.host.blockPos()()`）；`width` 又会命中 `font.width(`。必须用
   `(?<![\w.])width(?!\s*\()` 形式，并在完成后断言 `host.host.` 出现次数为 0。
2. **搬迁会"漏"父类里与已移动方法同名的作用域**。首次抽取把 `handleLayerScrollbarPress` 的定义搬进新类，
   却**漏改 `handleClick` 里的调用**，导致滚动条拇指按下在两端都没有实现——静态"调用点 vs 定义"核对才发现。
   教训：**GUI 层零测试，"编译通过"不等于"没丢逻辑"**，必须成对核对调用与定义。
3. **跨类使用要提可见性，且会撞名**。`MAX_ANCHOR_S` / `ff0..ff3` 需要 `public`；
   字段名与访问器同名会自相冲突（本次踩到：字段保留 `showSettings`，访问器改名 `settingsOpen()`）。
4. **模式开关不能只改一半**。设置面板在**两个模式下都会渲染**（图模式是覆盖层），因此 `keyPressed`
   的委托条件必须是 `active() || settingsOpen()`，否则"离开显示模式后面板还在、但按键失效"。

### 步骤 3 · `EditorSettingsScreen` 按 tab 拆分

#### ✅ 实施记录 · 指南 tab（已完成 `e3cacdf`）

`EditorSettingsScreen` 1204 → **1031 行**；新增 `EditorSettingsGuideTab.java`（242 行）与
`EditorSettingsHost.java`（62 行，tab 视图的宿主接口）。

| 变更 | 说明 |
|------|------|
| 搬入 tab 类 | `renderGuideTab` / `renderGuidePane` / `guideDescLines` / `guideListTop`·`guideListBot`·`guideVisibleRows`·`guideMaxScroll`·`guideRowRight`·`guidePaneW`·`paneX`·`paneTextW`·`paneBot` / `guideScrollbarThumb` / `applyGuideScrollbarDrag` / `guideCollapse` |
| 状态随迁 | `guideScroll` / `guideTarget` / `guideDetailScroll` / `guideScrollbarDrag(StartY/StartOff)` 搬入 tab 类，父类经访问器读写 |
| 父类保留 | tab 列、公共布局、输入分发（16 处改为 `guideTab.*` 转发）、`expanded`（三 tab 共享） |

**两个必须记住的坑（已写进代码注释）**：

1. **`Host` 不能写成嵌套接口** —— 实现方正是 `EditorSettingsScreen` 自身，嵌套声明构成
   `javac: cyclic inheritance`；必须是**独立顶层接口** `EditorSettingsHost`。
2. **`cy()` 由 `static` 变为实例方法**（Host 需要实例面），而 `keysListTop()` / `colorsListTop()`
   这两个**静态**几何方法仍在调它 → 编译失败。处理：在这两处内联常量 `8` 并注释说明（行为不变）。

另外父类的 `paletteX/Y/Scale/DoneY`、`tabColumnClick`、`collapseExpanded`、`beginAdjust`、
`collapsePalette`、`rebindPicker`、`applyKeysScrollbarDrag`、`applyColorScrollbarDrag`
已提升为 `public @Override`（实现 Host 所需）。

#### 🔶 待办 · 颜色 tab（方案已定，未实施，**新行号**）

- **渲染簇**：`renderColorsTab`（498–583，逐字搬迁）
- **几何**：`colorsListTop/Bot/VisibleRows/MaxScroll/RowRight`（974–991）+ `colorsScrollbarThumb` + `applyColorScrollbarDrag`（993–1002）
- **调色板逻辑**：`beginAdjust`（872–891）/ `fillWorkingColor`（895–898）/ `collapsePalette`（901–904）/ `rebindPicker`（907–912）
- **状态随迁**：`colorScroll`、`colorScrollbarDrag(StartY/StartOff)`、`adjustIndex`、`workingColor`、`stagingInited`
  （父类输入分发 16 处读写需改走访问器）
- **共享不搬**：`expanded`（与键位 tab 共享，Host 已提供 getter/setter）
- **风险点**：调色板绑定（`picker.open/rebind` 双回调）与 `adjustIndex` 生命周期；`expanded` 同时受键位分支影响

#### 🔶 待办 · 键位 tab（方案已定，未实施，**新行号**）

- **实现块是碎片化的**（这是它比指南 tab 难的原因）：
  `Keycap` + `cap` + `KEY_ROWS`（589–620）、`keysListTop/Bot/VisibleRows/MaxScroll` + `KEYS_CHIPS_W`（622–628）、
  `keysUnit/keysGap/keysGridW`（631–657）、`renderKeysTab`（659–806）、
  `handleKeycapClick` / `handleChipClick` / `confirmKeybind` / `selectKeybindRow`（810–853）、
  `KEY_ROW_H`（932）、`keysScrollbarThumb` + `applyKeysScrollbarDrag`（937–957）、`keysBarGeometry`（963–1003）
- **共享工具**：`seqText` / `keyName`（1005–1030）被键位 tab 使用 → 建议**留在父类并经 Host 暴露**
  （或下沉到 `EditorKeys`），不要在 tab 里复制一份
- **共享状态**：`collapseExpanded()`（856–864）同时清颜色 / 键位 / 指南三处 → **保留在父类**，
  各 tab 提供"只清自己"的方法供其调用（指南已是 `guideCollapse()` 这个形状）
- **状态随迁**：`keysScroll`、`keysScrollbarDrag(StartY/StartOff)`、`keybindTarget`、`latchedMods`、`rebindConflict`、`pendingSeq`
- **父类改动**：`renderKeysTab` 调用点 + 输入分发读写（约 25 处）


- **现状**：1,204 行 / **只有 2 个文件引用它、7 处**，是全仓最孤立的千行 GUI 类；内部已是 3 个 tab：
  颜色（`renderColorsTab` 674–764 + `beginAdjust` / `fillWorkingColor` / `collapsePalette` / `rebindPicker` 1047–1095
  + 颜色列表几何 1144–1177）、键位（`renderKeysTab` 834–984 + `Keycap` / `KEY_ROWS` 765–833 +
  `handleKeycapClick` / `handleChipClick` / `confirmKeybind` / `selectKeybindRow` / `collapseExpanded` 985–1046
  + 键位几何 1111–1143）、节点指南（`renderGuideTab` 268–335 + `renderGuidePane` 336–383 +
  指南几何 186–267）。
- **抽什么**：每 tab 的渲染 + 输入 + 几何三件套 → `EditorColorsTab` / `EditorKeysTab` / `EditorGuideTab`
  （或单个 `EditorTabs.java` 内的三个静态嵌套类——**取决于是否要为 tab 建立统一接口**，见下方追问点）。
- **为什么**：耦合最低、单客户端可完整回归（改键位 / 调颜色 / 查指南各点一遍），**适合作为验证新边界的试点**。
- **风险**：`paletteScale(height)` / `cy()` / `cy()` 等静态几何助手被多 tab 共用；`ColorPickerWidget` 单例与
  `rebinding` 状态跨 tab（重绑监听 + ESC 优先）需明确归属。
- **PR 拆分**：3 个提交（一 tab 一刀），第 3 刀后再决定是否引入 tab 接口。

### 步骤 4 · `PixelEditorScreen` 拆"内核 / 工具面板 / 帧条"

- **现状**：1,706 行，三个可分离块：
  **像素内核**（`paintBrush` 1322–1339 / `blendAlpha` 1340–1349 / `floodFill` 1350–1376 /
  `drawLineCells` 1377–1389 / `drawRectCells` 1390–1398 + 撤销栈 `undoStack`/`undoMeta`/`redoStack`/`redoMeta`
  与 `performUndo`/`performRedo`/`captureStrokeUndo`/`pushFramesUndo`/`pushResizeUndo`/`applyResizeUndoRedo` 1551–1705）、
  **帧条**（`renderFrameStrip` 786–860 / `renderThumb` 861–885 + 帧操作与拖拽排序 1399–1497）、
  **工具面板**（`renderLeftPanel` 606–718 + 几何 361–390 + `applyToolClick` 1087–1123）。
- **抽什么**：先内核（**可单测**，见 §1.4），再帧条，最后工具面板。
- **为什么值得**：GUI 层少见的**纯逻辑可测试点**——把 `int[]` 像素数组上的操作抽成无 GUI 依赖的类后，
  可补 `PixelEditorKernelTest`（笔刷/填充/直线/矩形 + 撤销重做往返），把步骤 4 从"手动回归"升级为"有断言"。
- **注意**：内核状态与渲染紧耦合（`zoom` / `panX` / `panY` / `tool` / `brushSize` / `brushOpacity`），
  **只抽"像素数组上的算法 + 撤销栈"，不抽视图状态**。

### 步骤 5 · `NodeRenderer` 按渲染品类拆分（**保门面**）

- **现状**：1,500 行，**235 处引用 / 15 个文件**，全仓被引用最广。品类：注释节点渲染、
  添加节点菜单（`renderAddNodeMenu` + 搜索框 + 滚动 + 分类）、连线/引脚渲染、调色板与主题
  （`PBG` / `PBR` / `ACC` / `PINS` / `PHT` / `CSB` 等被 15 个文件直接读的静态色 + `_NUM_COLORS`）。
- **硬约束**：**对外签名与静态色访问器一律不变**（`NodeRenderer.PBG()` 这类被大量外部调用），
  拆的只是内部实现；否则会同时改动 15 个文件，失去"调用方零改动"的可验证性。
- **顺序**：菜单（含搜索/滚动，状态最独立）→ 注释节点 → 连线/引脚。色板与主题**最后**（改动波及最广）。

### 步骤 6 · `GraphEditor` 五刀（🔴 最大债、最热文件）

**现状构成**（按方法体量）：

| 起始行 | 行数 | 方法 |
|--------|------|------|
| 2717 | **1,081** | `mouseClicked` —— 单方法千行：线/引脚、拖动、框选、热键栏、菜单点击… |
| 2121 | **492** | `renderBg`（整块渲染 + 在线玩家列表） |
| 1197 | **370** | `createEditState`（为各 `NodeType` 建参数编辑控件；含 `createDebugSignalGenEditState` 1575–1663、`handleModeToggleClick` 1664–1751） |
| 4802 | **341** | `keyPressed`（快捷键 + 公式编辑器按键） |
| 4058 | 291 | `mouseReleased`（拖拽落定、撤销批次、推送/包含节点回弹） |
| 4515 | 187 | `mouseMoved`（悬停 / 图表控制点命中） |
| 1059 | 133 | `onRemoteOp`（远端 op 应用 + 撤销抑制） |
| 2613 | 100 | `renderPresenceOverlay`（协作光标 + 在线列表） |

按**独立度从高到低**排刀，每刀单独提交：

| 刀 | 抽什么 | 约行数 | 独立度 | 备注 |
|----|--------|-------|--------|------|
| 6a | `createEditState` + 调试信号发生器编辑态 + 模式切换点击 → `blocks/NodeEditStateFactory.java` | ~550 | 高 | 输入＝节点，输出＝控件集合，依赖 `nodeEditStatesById` |
| 6b | 协作 presence：`storeRemotePresence` / `cleanupStalePresences` / `sendPresenceIfNeeded` / `renderPresenceOverlay` / `getRemotePresences` / `isNodeLocked*` | ~350 | 高 | 数据面已是 `GraphPresencePacket` record，天然可切 |
| 6c | 远端 op 应用 + 撤销/重做栈：`onRemoteOp` / `opUndo` / `opRedo` / `recordOp` / `reverseOp` / `withTargetId` / `withFromToId` / `remapNodeId` / `resetBatch` / `beginUndoBatch` / `endUndoBatch` | ~430 | 中 | 与 `pendingLocalOps`、`GraphOp`、`EditState` 强耦合——**风险最高的一刀** |
| 6d | 总线编辑：`commitBusBox` / `releaseOldBusName` / `clearBusNode` / `reevaluateBusConflicts*` / `syncBusBands` + `BUS_EDIT_DEBOUNCE_TICKS` 去抖 | ~250 | 中 | 与 `localBusNames` / `EditState` 耦合 |
| 6e | 相机与视图：`startTransition` / `advanceCameraTransition` / 书签面板 / `tempViewByPos` | ~150 | 高 | 状态自包含 |
| 6f | **本文件内**方法级拆解：`mouseClicked`（1081 行）按命中目标分发为私有方法；`renderBg`（492 行）、`keyPressed`（341 行）同理 | — | 最低 | **不动跨类边界**，纯私有方法重排 |

- **风险（必须写进提交说明）**：近 60 天 **58 次提交** 触碰此文件——跨类拆分期间任何功能改动都会与之冲突。
  **建议**：① 先完成步骤 1–5（都不碰它）；② 挑功能平静期动 6 步；③ 6 步期间冻结其它 GUI 改动；
  ④ 6f 可随时做（不引入冲突面）。
- **顺序建议**：6a → 6b → 6e → 6d → 6c → 6f（独立度递减；6f 无外部影响可随时插入）。

### 步骤 7 · 小文件批量归位（⚪ 择机）

`EditPanel`(655) / `MultiLineEditBox`(775) / `ColorPickerWidget`(763) / `PortableTerminalScreen`(857) /
`RadarBlockEntity`(1,006) / `GraphHost`(632)。等 1–5 步把边界理顺后再评估；其中
`RadarBlockEntity` 属 BE 而非 GUI，若拆分应先核对 `graph-host-convergence-plan.md` 的收敛结论。

---

## 四、验证协议 / Verification Protocol

> GUI 层零自动化测试（§1.3），**"编译过"不等于"没回归"**。每步统一走四关。

1. **编译**：`./gradlew compileJava` —— **后台执行**，完成后只取一次退出码（CLAUDE.md 测试规范：
   不做日志轮询 / 不 sleep 等待）。
2. **现有单测**：`./gradlew test` —— 后台执行，只取最终结论。步骤 1 必须看到那 2 个渲染器测试仍绿。
3. **手动回归清单**：每步交付时附一份**该 GUI 的 5–10 条检查清单**（下表 A/B/C 为模板）。
4. **提交**：每步独立 `refactor:` 提交；**纯搬迁提交不得混入行为改动**，便于 `git revert` 单步回滚。

### 清单 A · 显示编辑 GUI（步骤 2 用）

| # | 操作 | 期望 |
|---|------|------|
| A1 | 打开显示器 GUI → 点右上「显示」切到显示布局模式 | 面板/元素正常绘制，无闪烁 |
| A2 | 拖拽元素 | 元素跟随；松手落定；再次拖拽仍正常（**验证契约 4 的孤儿节点重映射**） |
| A3 | 拖拽中另一玩家用同一图 | 远端实时看到移动（**契约 2 流式 op**）；拖拽方本地不跳变 |
| A4 | 打开图层面板：滚动 / 拖动排序 / 滚动条拖拽 | 缩略图顺序、放置指示线、滚动位置正确（**契约 7 几何同源**） |
| A5 | 打开设置面板：改屏幕宽/长/偏移/旋转 → 实时预览 → 保存 | 预览即时生效；保存后重开仍生效；HUD 模式开关与虚像缩放正确 |
| A6 | 双击 IMAGE 节点 → 像素编辑器 → 关闭返回 | 返回后能继续编辑；**协作会话未断开**（契约 5） |
| A7 | 触屏 / 高 DPI（若可）拖拽 | 本地渲染实时跟随，非松手才动（**契约 3**） |
| A8 | 显示模式下看队友光标与拖拽描边 | 光标与描边随 `SET_DISPLAY_LAYOUT` 实时更新（**契约 1 presence**） |
| A9 | `Esc` 退出、破坏方块后自动关闭 | 正常关闭；会话正确 leave；无残留 pending 状态 |

### 清单 B · 编辑器设置三 tab（步骤 3 用）

B1 绑定快捷键（含多步序列 + 修饰键）→ 重开界面仍生效；B2 冲突提示正确；
B3 改主题色 → 应用 → 各处界面同步变色；B4 恢复默认；B5 节点指南滚动 / 详情面板 / 收起展开。
**单客户端可完整验证。**

### 清单 C · 像素编辑器（步骤 4 用）

C1 画笔/橡皮/填充/直线/矩形基础操作；C2 缩放平移；C3 撤销重做往返（含帧数变化与画布尺寸变化）；
C4 帧条增删/切换/拖拽排序；C5 关闭后主图与节点数据完好。
**其中 C1/C3 若有新增单测，可降级为"看测试结果"。**

---

## 五、明确不在本轮范围 / Out of Scope

| 文件 | 行数 | 不拆的理由 |
|------|------|-----------|
| `graph/FormulaParser.java` | 1,640 | 内聚的领域逻辑（分词→校验→AST→RPN），已有 `FormulaParser*Test` 多套断言；拆它属"重构解析器"而非"拆巨型文件" |
| `graph/GraphEvaluator.java` | 1,226 | 求值器是服务端权威单一实现（CLAUDE.md 代码规范），已有 `GraphEvaluatorTest` 等；拆分收益低于破坏风险 |
| `graph/GraphNode.java` | 744 | 扁平数据结构的**单一节点类**（CLAUDE.md：不建继承体系） |
| `graph/OpExecutor.java` | 628 | 已接近合理体量，且有 op 测试 |
| `compat/SableReflection.java` / `EditSessionRegistry` / `RuntimeState` | <500 | 不在巨型阈值内 |

> 若后续仍要动 `FormulaParser`，应**另立一份规划文档**（本仓 `docs/` 的既定做法，参见
> `programmable-gearbox-plan.md` / `screen-migration-plan.md` 等），不混入本 GUI 路线图。

---

## 六、风险登记 / Risk Register

| 风险 | 影响 | 缓解 |
|------|------|------|
| GUI 无自动化测试 | 回归只能靠肉眼，漏测成本高 | 每步一张手动清单；优先选可单测的切口（步骤 1、4 内核） |
| `GraphEditor` 高频改动（58 次/60 天） | 拆分与功能开发持续冲突 | 步骤 6 垫后 + 功能冻结窗口 + 6f 先行（无冲突面） |
| `NodeRenderer` 235 处引用 | 签名一变外溢 15 个文件 | 只抽内部实现、门面签名冻结 |
| 协作行为只在大改动下暴露 | 单人测试通过、联机才炸 | 清单含双客户端项（A3/A4/A8），必要时 `runs/server` 起服务端用 `runClient` + `runClient2` |
| 几何被裂成两份 | 点击热区与画面对不上 | 原则 3：几何单一来源，搬迁时逐处核对 |
| 行号漂移 | 文档与代码失配 | 以方法名为主锚点；每步实施后回写本文档"实施记录" |

---

## 七、待拍板的开放问题 / Open Questions

1. **新文件组织**：显示编辑器用"单个 `MonitorDisplayEditor` 类" vs "显示编辑器 + 图层面板 + 设置面板 三个类"？
   （倾向：第 1 刀单类，第 3 刀视体量再拆。）
2. **tab 是否引入接口**：`EditorSettingsScreen` 三个 tab 拆出后是否定义 `EditorTab` 接口统一 render/click？
   （倾向：先不引入——三个 tab 的输入契约差异较大，接口会变成"什么都传"的参数包。）
3. **步骤 6e 之后是否继续**：`GraphEditor` 拆到 6c 后仍可能 >3,000 行，是否接受"部分收敛"作为终点？
4. **是否补 GUI 冒烟测试**：是否值得为像素内核（§1.4）写单测作为本路线图的副产品？
