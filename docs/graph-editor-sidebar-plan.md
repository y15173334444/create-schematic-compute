# 图编辑器侧边栏计划 / Graph Editor Sidebar Plan — 探索中 / Exploring

> **状态**：探索线草案（分支 `csc-gui-sandbox`），起草于 2026-11-20。
> 设计以用户手绘草图 + 两问拍板为准；本文只做机制级记录，行号不作锚点。
> **Status**: exploration draft (branch `csc-gui-sandbox`), drafted 2026-11-20. The design
> follows the user's hand-drawn sketch plus two rulings; this note is mechanism-level
> only — line numbers are never anchors here.

---

## 一、目标 / Goal

把图界面散着的工具「东西」全部收进**右缘侧边栏**，画布占满其余空间；
玩家高频三键 **[X] 关闭 · [编译] · [运行/停止]** 留在外面。
Tuck every scattered tool of the graph UI into a **right-edge sidebar**, leaving the
canvas to fill the rest; the three high-frequency buttons **[X] close · [compile] ·
[run/stop]** stay outside.

## 二、用户拍板 / Rulings

1. **收进范围**：除 [X][编译][运行] 三键外，其余工具全部收进侧边栏。
   *Scope*: everything except [X][compile][run] goes into the sidebar.
2. **设置去向**：设置入口进侧边栏，指南/颜色/键位/预算四 tab 面板**保留整屏**。
   *Settings*: the entry moves into the sidebar; the four-tab panel screen stays a
   full screen.
3. **（第二轮）顶栏整条收起**：图名编辑、子图返回、封装模式标识全部收进侧边栏「图」板块；
   [X][编译][运行] 贴左上角留外面（拍板 1 的高频三键口径不变）。
   *(Round 2) The top bar goes away entirely*: the graph-name edit, sub-graph Back and
   the encapsulation-mode indicator move into the sidebar's "Graph" section; the
   [X][compile][run] buttons hug the top-left corner (ruling 1's three-key carve-out
   stands).

## 三、布局机制 / Layout mechanics

- **图标条（收起态）**：右缘常驻竖条，宽约 22px，竖排图标 = 板块入口。
  *Icon rail (collapsed)*: a permanent vertical strip on the right edge, ~22px wide,
  stacked icons = section entries.
- **展开**：点图标 → 面板自图标条向左展开（宽约 200px）；同屏只开一个板块；
  点面板关闭钮或再点同一图标收起。宽度本轮固定，不拖宽。
  *Expand*: clicking an icon opens the panel leftward from the rail (~200px); one
  section at a time; the close button or a second click on the same icon collapses it.
  Width is fixed this round — no drag-resize.
- **顶置工具栏**：缩为 [X][编译][运行] 三键，贴左上角（y=2），不再有整条顶栏。
  *Top toolbar*: just the three kept buttons, hugging the top-left corner (y=2) —
  no full-width top bar anymore.
- **顶栏**：已整条退役；图名/子图返回/模式标识住进侧边栏「图」板块，22px 高度归还画布。
  `TOP_BAR_H` 常量保留给监视器显示模式等自带顶条的表面，图编辑器自身不再引用它排版。
  *Top bar*: retired outright; name / Back / mode indicator live in the sidebar's
  "Graph" section and the 22px returns to the canvas. The `TOP_BAR_H` constant stays
  for surfaces with their own top strip (monitor display mode); the graph editor no
  longer lays itself out against it.

## 四、板块 / Sections

| 板块 Section | 内容 Contents | 来源 Source |
|---|---|---|
| 节点库 Node library | 分类列表 + 搜索 + 滚动（在面板体内渲染） | `NodeAddMenu`（锚点改面板矩形） |
| 图 Graph | 图名编辑（主图）/ 模式标识 + 返回（子图）；进子图时自动展开 | 原顶栏整条 |
| 书签 Bookmarks | 视角书签面板 | `GraphViewBookmarks`（角标按钮退役） |
| 工具 Tools | 网格吸附、工具栏置底、封装导入/导出（蓝图机专属） | 原工具栏/右下角逐个搬入 |
| 设置 Settings | 单动作：打开设置整屏 | 原顶栏设置按钮 |

- 添加节点的右键弹出路径**保留**（CONTEXT_MENU 弹窗照旧）；侧边栏只是常驻新家。
  The right-click add-menu popup stays as-is; the sidebar is its new permanent home.
- 控制台（`/` 键）、热栏选物品、颜色拾取等**上下文弹层**不是「东西」，本轮不动。
  Context popups (the `/` console, per-node hotbar picker, colour picker) are not
  "things" — untouched this round.

## 五、几何与路由约束 / Geometry & routing constraints

- 图标条与面板的矩形**单一来源**计算，渲染与点击命中同用（命中几何漂移教训）。
  The rail and panel rects come from **one** geometry source used by both render and
  hit-testing (the wire-hit geometry-drift lesson).
- 侧边栏命中优先于画布/节点点击；展开面板打开时不吃画布拖拽。
  Sidebar hits beat canvas/node clicks; while the panel is open it does not leak
  canvas drags.
- 主题色继续走 `EditorTheme`/`NodeRenderer` 色板单源。
  Theme colours stay on the `EditorTheme`/`NodeRenderer` single source.

## 六、本轮不做 / Out of scope this round

- 面板拖宽、多板块并排、悬停自动展开/收起。
  Drag-resize, side-by-side sections, hover-to-expand.
- 设置四 tab 内容入栏；监视器图层/设置面板、雷达工具栏等宿主专属面板入栏。
  Settings tabs into the rail; host-specific panels (monitor layers/settings, radar
  toolbar) into the rail.

## 七、开放项 / Open items

- 书签面板体并入侧栏面板（本轮先接入口，面板体沿用自身几何）。
  Folding the bookmark panel body into the sidebar panel (this round wires the entry
  only; the body keeps its own geometry).
- 图标纹样与竖排标签样式（草图竖排板块名的字形以用户原话为准）。
  Icon glyphs and vertical label styling (the sketch's vertical section names follow
  the user's own wording).
