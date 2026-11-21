# 图编辑器侧边栏计划 / Graph Editor Sidebar Plan — 已并入 main / Merged into main

> **状态**：第十二轮起在 main 上推进（2026-11-22 由 `csc-gui-sandbox` 合入，起点 7b994f10），
> 主图实际施工轮次以本文件拍板条目为准；起草于 2026-11-20。
> 设计以用户手绘草图 + 逐轮拍板为准；本文只做机制级记录，行号不作锚点。
> **Status**: landing on main since round 12 (merged from `csc-gui-sandbox` on 2026-11-22 at
> 7b994f10); the rulings below are the record of what shipped. Drafted 2026-11-20. The design
> follows the user's hand-drawn sketch plus the round-by-round rulings; this note is
> mechanism-level only — line numbers are never anchors here.

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
4. **（第三轮）工具板块解散**：网格吸附 / 工具栏置底 / 封装导入导出 三选项改为图标条
   **直控图标**（一键切换，不再开面板）；图标条改**自制像素图标**（不用字形/符号），
   最上方常驻**展开/收起按钮**。
   *(Round 3) The Tools section is dissolved*: grid snap / bottom toolbar / encap IO
   become **direct toggle icons on the rail** (one click, no panel); the rail switches
   to **self-drawn pixel icons** (no glyphs or symbols) with a permanent
   **expand/collapse button at the very top**.
5. **（第四轮）展开钮改文字签 + 图标重绘**：最上方按钮换成竖排文字「展开侧栏 / 收起侧栏」；
   全部图标按 16×16 手绘位图重绘（节点库=框+加号、图=两节点连线、书签=绸带、
   网格吸附=三横三纵、工具栏置底=下箭头+底条、封装=文件夹、设置=八齿齿轮）。
   *(Round 4) Text tab for expand + redrawn icons*: the top button becomes a vertical
   text tab "展开侧栏 / 收起侧栏"; every icon is redrawn as a hand-authored 16×16
   bitmap (node library = frame + plus, graph = two linked nodes, bookmark = ribbon,
   grid snap = three-by-three grid, bottom toolbar = down arrow over a bar, encap =
   folder, settings = eight-tooth gear).
6. **（第五轮）封装入口改占位面板**：文件夹图标不再一键弹导入/导出对话框，改为展开
   侧栏「文件」面板（最初叫「封装」，因文件系统扩到图级而改名）：新建图 / 导入 / 导出
   三行按钮 + 撤销、重做并排一行（沿用 `editorkeys.undo/redo` 的词）；五个按钮
   **暂不接行为**，等导入导出文件系统重做后填实。
   *(Round 5) The encap entry becomes a placeholder panel*: the folder icon no longer
   fires the import/export dialog in one click; it opens a sidebar "Files" panel with
   three stacked buttons (new graph / import / export) plus undo and redo side by side
   (reusing the `editorkeys.undo/redo` wording). All five buttons are **inert** for now,
   to be wired once the import/export file system is redone.

7. **（第六轮）点击回弹 + 单浮窗口径**：图标条每一项（含展开文字签）按下时朝画布弹出 4px
   再回位、同时提亮（170ms 正弦曲线）；打开任一板块或书签浮窗前先收起其它浮窗，
   同屏只留一个侧栏浮窗（书签面板原先是独立开关，可与板块同时开着）。
   *(Round 6) Press bounce + one flyout at a time*: every rail item (the expand text tab
   included) pops 4px toward the canvas and settles back while brightening (a 170ms sine
   curve); opening any section or the bookmark flyout first closes the others, so only one
   sidebar flyout is on screen (the bookmark panel used to toggle independently and could
   stay open beside a section).

8. **（第七轮）展开侧栏＝图标条宽度开关**：顶部那行不再开合面板，而是让图标条向左延伸一点，
   让每个图标右侧露出文字；板块仍由各自图标开合。收起态顶部仍是竖排「展开侧栏」，
   展开态变成「chevron + 收起侧栏」一行。
   *(Round 7) "Expand" is the rail's width toggle*: the top row no longer opens panels; it
   widens the rail leftwards so every label shows beside its icon, while sections still
   open from their own icons. Collapsed, the top row stays the stacked "展开侧栏"; expanded,
   it becomes a single "chevron + 收起侧栏" row.

9. **（第八轮）工具栏位置由图标反馈 + 浮窗贴顶**：控制 [X][编译][运行] 三键位置的图标改
   **双态**——条在顶 = 顶部横条 + 上箭头，条在底 = 底部横条 + 下箭头；文字同步报位置
   （工具栏置顶 / 工具栏置底）；不再常亮，高亮只留点击那一下的回弹闪亮。侧栏打开的浮窗
   默认**贴顶**（书签面板原先挂在右下角，改为从顶部排起）。
   *(Round 8) The toolbar icon reports position + flyouts hug the top*: the icon that moves
   the [X][compile][run] cluster is now **two-state** — top bar + up arrow when the cluster
   sits on top, bottom bar + down arrow when it sits at the bottom; the label reports the
   same position (top toolbar / bottom toolbar); it no longer stays lit, the highlight is
   just the click bounce. Sidebar flyouts **hug the top** by default (the
   bookmark panel used to hang off the bottom-right corner).

10. **（第九轮）浮窗弹出动画**：侧栏打开的每个浮窗（图板块、文件面板、钉住的节点库、
    书签面板）打开时**从图标条底下滑出**——起始整窗藏在图标条后（其余部分在屏幕外），
    170ms ease-out 三次曲线滑到就位，与按压回弹同时长；**关闭不动画**（即时消失）。
    偏移量进各浮窗的几何单源（图/文件面板的 `panelRect`、书签的 `panelX`、节点库在
    屏幕钳制**之后**施加的平移参数），渲染、裁剪与命中一起动；四个浮窗共用一口钟。
    *(Round 9) Flyout pop animation*: every sidebar flyout (Graph section, Files panel,
    pinned node library, bookmark panel) now **slides out from under the rail** when
    opened — starting fully hidden behind the rail (the rest off-screen), easing to its
    resting spot over 170ms with an ease-out cubic, the same duration as the press
    bounce; **closing does not animate** (it vanishes instantly). The offset lives in
    each flyout's geometry single source (the graph/files `panelRect`, the bookmarks'
    `panelX`, and the node library's translation parameter applied **after** its screen
    clamp) so drawing, scissoring and hit-testing move together; the four flyouts share
    one clock.

11. **（第十轮）性能监视器进侧栏**：图标条新增**监视器图标**（心电脉冲线，文件面板之后、
    设置沉底之前），打开**只读全高面板**：帧率（FPS · 帧间隔 ms）、帧率走势（250ms 一格、
    46 格 ≈ 11.5 秒的像素条状图，最新一格全亮；满格**动态封顶**——随窗口峰值上浮，向上取到
    常见刷新率阶梯 30/60/75/90/120/144/165/240/…，封顶值显示在走势行右侧）、内存（MB 数值 +
    占用条）、当前图节点数 / 连线数 / 缩放百分比。面板打开期间才采样；弹出动画与单浮窗口径
    同其余板块。
    *(Round 10) A performance monitor joins the sidebar*: the rail gains a **monitor icon**
    (an ECG pulse line, after the files panel and before the docked settings) opening a
    **read-only full-height panel**: FPS (with frame-time ms), an FPS trend as pixel bars
    (one 250ms bucket each, 46 buckets ≈ 11.5s, the latest bucket fully lit; the full
    scale is **dynamically capped** — it floats with the window's peak, rounded up to the
    common refresh-rate ladder 30/60/75/90/120/144/165/240/…, the cap shown right of the
    trend label), memory (MB value + usage bar), and the current graph's node / wire
    counts plus the zoom percentage. Sampling accrues only while the panel is open; the
    pop animation and the one-flyout discipline match the other sections.

12. **（第十一轮）终端控制台进侧栏**：图标条新增**终端图标**（窗框 + 提示符，监视器之后、
    设置沉底之前），打开**加宽全高面板**（300px，比其余板块宽——输出行要宽度）：输出区镜像
    客户端收到的**聊天与系统消息**（命令反馈、加入/退出提示都在内，`ClientChatReceivedEvent`
    一并接住，环形缓冲 200 行全局共享），新行沉底、滚轮翻历史、超宽截断；底部**输入行**
    （EditBox，夺焦时其余编辑框让位）：Enter 提交走原版语义（`sendChat`——`/` 开头即命令，
    命令反馈经系统消息回流到输出区；无 `/` 的文本作为聊天发出），Esc 失焦，点画布失焦。
    弹出动画与单浮窗口径同其余板块。
    *(Round 11) A terminal console joins the sidebar*: the rail gains a **terminal icon**
    (window frame + prompt, after the monitor and before the docked settings) opening a
    **wider full-height panel** (300px — wider than the other sections; output lines want
    width): the output area mirrors the **chat and system messages** the client receives
    (command feedback and join/leave notices included, all caught by the single
    `ClientChatReceivedEvent`, a 200-line ring buffer shared globally), newest at the
    bottom, wheel through history, overlong lines truncated; the **input row** at the
    bottom (an EditBox; on focus all other edit boxes yield): Enter submits with vanilla
    semantics (`sendChat` — a "/" prefix sends a command whose feedback flows back as
    system messages, text without "/" goes out as chat), Esc blurs, clicking the canvas
    blurs. The pop animation and the one-flyout discipline match the other sections.

13. **（第十二轮）`/` 控制台整体搬进终端面板、`/` 开台键退役**：终端面板＝CSC 控制台——
    输出区只显示 CSC 命令回执（本地回显 `> cmd` + 服务端响应行，400 行环、滚轮翻历史、
    超宽截断），输入行 trim 后**原样**发服务端（`ConsoleQueryPacket`，客户端零文法；
    `help`/`stale`/`topo`/`ring`/`bus`/`hosts`）；原聊天/系统消息镜像（`SidebarConsoleFeed`）
    与原版 `sendChat` 玩家输入一并退役。原以 `/` 开台的独立模态叠加层退役：面板只从图标条
    终端图标开，开面板即聚焦输入行（点面板任意位置也聚焦），Esc / 点画布失焦；聚焦态吞掉
    全部按键（聚焦输入框优先，含拦住 Screen 的 ESC 关屏），失焦态一律放行；首次聚焦出一次
    `help` 提示，提交后等待标记照旧。
    *(Round 12) The "/" console moves wholly into the terminal panel; the "/" opener retires*:
    the terminal panel **is** the CSC console — the output area shows CSC command replies only
    (local echo `> cmd` + server response lines, a 400-line ring, wheel through history,
    overlong lines truncated), and the input row sends the trimmed text **verbatim** to the
    server (`ConsoleQueryPacket`, zero grammar client-side; `help`/`stale`/`topo`/`ring`/`bus`/
    `hosts`). The chat/system mirror (`SidebarConsoleFeed`) and the vanilla `sendChat` player
    input both retire. The modal "/"-opened overlay retires too: the panel opens only from the
    rail's terminal icon, opening it focuses the input row (any click inside the panel focuses
    it as well), Esc / a canvas click blurs; while focused every key is swallowed (a focused
    input beats the rest of the guard chain, Screen's ESC close included) and while blurred
    everything passes; the first focus prints the `help` hint once and the awaiting marker
    stays as before.

## 三、布局机制 / Layout mechanics

- **图标条（常驻）**：右缘竖条，宽约 22px，**自制像素图标**（16×16 手绘位图 +
  悬停提示，不用字形/符号）。自上而下：展开/收起文字签、节点库、图、书签、
  网格吸附、工具栏置底、文件面板（蓝图机专属）、性能监视器、终端，设置沉底。
  *Icon rail (permanent)*: a vertical strip on the right edge, ~22px wide, with
  **self-drawn pixel icons** (hand-authored 16×16 bitmaps + hover tips; no glyphs or
  symbols). Top to bottom: expand/collapse text tab, node library, graph, bookmarks,
  grid snap, bottom toolbar, files panel (blueprint only), performance monitor,
  terminal; settings docked at the bottom.
- **展开侧栏**：最上方那一行是**图标条本身的宽度开关**，不是板块开关——收起态 22px 纯图标、
  那一行显示竖排「展开侧栏」；展开态图标条向左延伸（图标 16 + 间距 + 当前语言最长标签 +
  留白，实测宽度），每个图标右侧露出文字，那一行变成「chevron + 收起侧栏」。点击只换宽度。
  *Expand the rail*: the top row is the **rail's own width toggle**, not a section toggle —
  collapsed it is a 22px icon-only strip whose top row reads the stacked "展开侧栏";
  expanded the rail grows leftwards (icon 16 + gap + the widest label in the current
  language + padding, measured live) so every label shows beside its icon, and the top
  row becomes "chevron + 收起侧栏". Clicking only swaps the width.
- **板块面板**：节点库 / 图 / 文件各自由图标开合，面板自图标条左侧展开（宽 200px），
  同屏只开一个板块；再点同一图标或换开别的即替换。面板打开时图标条宽度不随之变化。
  *Section panels*: node library / graph / files each open from their own icon; the panel
  unfolds leftwards from the rail (200px wide), one section at a time — clicking the same
  icon again or another icon replaces it. Opening a panel does not change the rail width.
- **直控图标**：网格吸附、工具栏置底直接在图标条上切换。网格吸附用点亮表示开关；
  工具栏位置改由图标双态（条在顶 / 条在底）表达，不留常亮。
  *Direct icons*: grid snap and the toolbar position toggle straight from the rail.
  Grid snap lights up while on; the toolbar position is carried by the icon's two states
  (bar on top / bar at the bottom) with no steady highlight.
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
| 书签 Bookmarks | 视角书签面板（图标条单动作，面板体沿用自身几何） | `GraphViewBookmarks` |
| 设置 Settings | 单动作：打开设置整屏 | 原顶栏设置按钮 |
| 文件（占位）Files | 新建图 / 导入 / 导出 / 撤销 / 重做；按钮暂不接行为 | 原封装导入导出动作 |
| 直控 Rail toggles | 网格吸附、工具栏置底——原「工具」板块解散 | 原工具栏/右下角逐个搬入 |

- 添加节点的右键弹出路径**保留**（CONTEXT_MENU 弹窗照旧）；侧边栏只是常驻新家。
  The right-click add-menu popup stays as-is; the sidebar is its new permanent home.
- 从节点库添加的节点落在**可见画布中心**（浮窗左侧空档的中点、半高），不再跟随最近瞄准点
  ——那个点常常正好被浮窗盖住，加完看不见。右键弹出路径的落点仍是鼠标处。
  Nodes added from the library land at the **centre of the visible canvas** (midway of the
  free gap left of the flyout, half height) instead of the last aim point, which often sat
  under the flyout. The right-click popup path still drops at the cursor.
- 热栏选物品、颜色拾取等**上下文弹层**不是「东西」，本轮不动（`/` 控制台已搬进侧栏终端
  面板，见第十二轮）。
  Context popups (the per-node hotbar picker, colour picker) are not "things" — untouched
  this round (the `/` console already moved into the sidebar terminal panel, round 12).

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
- 板块名竖排字样（草图里「节点库 / 图」的竖排文字）：本轮图标条仍是图标 + 悬停提示，
  只有最上方展开签是文字；是否给板块也加竖排小字待定。
  Vertical section lettering (the sketch's stacked "节点库 / 图"): the rail still uses
  icons + hover tips this round — only the top expand tab is text; whether sections
  also get vertical captions is still open.
- 「文件」面板五个按钮待接线：文件系统施工线暂存在分支 `csc-gui-filesys-wip`（导出 = 选区优先、
  空选整图；导入 = 整文件并入当前图，与 Ctrl+D 同一 ACK 流程、一次撤销批次），本分支只留占位。
  「新建图」口径仍待拍板（清空当前图 / 另开一张 / 走宿主方块重建）。
  The five "Files" panel buttons are unwired: the file-system work is parked on branch
  `csc-gui-filesys-wip` (export = selection first, whole graph when empty; import = merge
  a whole file in through the same ACK flow as Ctrl+D, one undo batch); this branch keeps
  the placeholder only. The "new graph" semantics are still open (clear the current graph /
  open a fresh one / rebuild through the host block).
