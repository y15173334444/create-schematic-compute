# NBS 编辑器批次改进计划 / NBS Editor Batch Improvement Plan

> **状态：✅ 批次一 + 批次二（双样式）已落地（2026-10-17）；🔶 批次三/四待办**
> 日期：2026-10-17 起草
> 影响：`NbsEditorScreen` / `NbsEditorKernel` / `NbsPianoRoll` / `NbsSong` / `MusicTransport`
> 交叉引用：[`tech-debt-review-2026-10-11.md`](tech-debt-review-2026-10-11.md)（P1-10 / P2-22 / P2-23）、[`known-open-issues.md`](known-open-issues.md)、CHANGELOG「Unreleased（v1.2.6 WIP）」NBS 钢琴卷帘编辑器条目
> 对照基准：Open Note Block Studio（`OpenNBS/NoteBlockStudio`，v3.11.0，**MIT**，本机克隆 `C:\Users\Administrator\Desktop\NoteBlockStudio`）——NBS 格式原厂编辑器的社区续作

## 0. 背景与口径 / Background & Scope

游戏内 NBS 钢琴卷帘编辑器（v1.2.6 WIP）的骨架健康：点放/涂抹语义与社区一致（点已有音符=搬移换键、同键=删、空格=放置）、撤销双轨（逐格逆操作 + 结构快照）、NBS v1–v5 读 / v5 写保真。对照成熟社区编辑器，差距集中在**编辑表达力**（选择→批量编辑→搬移→剪贴板的工作流只有单格级）与一批已登记的可靠性缺口。

The in-game NBS piano-roll editor has a healthy skeleton (community-parity placement semantics, dual-track undo, faithful v1–v5 read / v5 write). Against the mature community editor the gaps are **editing expressiveness** (the select → bulk-edit → move → clipboard workflow exists only at single-cell granularity) plus a set of registered reliability defects.

口径：只取**社区核心工作流**，桌面 DAW 关注点（MIDI 双向、录音模式、音频/schematic/datapack 导出、宏库、主题系统、.nbp 图案）明确不追——本编辑器是模组演奏链路（MUSIC 节点 → 音响/功放）的创作面，表达力重心在图编程通道。参考仓为 MIT：语义可自由对齐，移植代码须保留其版权与许可文本。

## 1. 批次一：可靠性缺口 / Batch 1: Reliability Gaps

**状态：✅ 已解决（2026-10-17）**（A1–A4 全部落地，回归锚点 `NbsEditorLayoutTest`）

**实施记录 / Implementation Record（2026-10-17）**

```
903f8d1 fix: restore the 256 KB song upload cap after the density soak
b6cef64 fix: nbs editor panel defects - import wheel, bar geometry, blur commits
24cfbae fix: route nbs editor ops through the temp-id funnel
```

### A1. 编辑 op 出口并入 temp-id 挂起漏斗 / Route edit ops through the temp-id funnel

- **缺口**（tech-debt P1-10，批次一最重）：`NbsEditorScreen` 的 `getEditor()` 写死返回 null、`sendOp` 直发——新建 MUSIC 节点后一个 RTT 内打开编辑器并保存，`SET_SONG` 携带未 ACK 的临时节点 id，服务端 `findNode` 落空静默丢弃 → **曲目丢失**。与「temp-id 挂起队列随关屏丢弃」同族，是编辑 op 出口收口批次漏掉的最后一个出口。
- **机制**：客户端所有编辑 op 的统一出口在 `GraphEditor.sendOp`（temp-id 挂起守卫 + ACK 重映射 + `pendingLocalOps` 计数）；像素编辑器已按「来源屏共享同一编辑会话的漏斗实例」收口。NBS 编辑器同属来源屏编辑会话（`returnScreen` 返回），照同款模板收口：`getEditor()` 委托 `returnScreen`（`GraphEditor.Host`）取漏斗实例，`sendOp` 优先走漏斗，取不到才退回直发 + 手动计数。
- **回归锚点**：既有 `RejectReceiptTest` / temp-id 漂移族测试不回归；漏斗行为本身依赖 MC 网络栈，按仓库无头测试纪律以代码走查 + 游戏内复现路径验收（新建 MUSIC → 立即开编辑器 → 保存 → 重开核对曲目）。

### A2. 导入对话框滚轮滚动 / Import dialog wheel scrolling

- **缺口**：弹窗打开时 `mouseScrolled` 整体吞掉滚轮，导入列表的 `importScroll` 永不推进——目录超过一屏（约 11 个文件）后后面的文件点不到（死状态字段）。
- **机制**：导入弹窗打开时把滚轮路由到 `importScroll`（按行高钳位到 `[0, max(0, 文件数-可见行)]`），其余弹窗维持吞滚轮口径。

### A3. 面板缺陷打包 / Panel defect bundle（tech-debt P2-22 + P3 小项）

- **顶栏/传输条 render/click 几何双写**：按钮坐标由渲染与点击两侧各自维护，几何失步即按错按钮。收敛为**单一几何源**（`topBarRects` / `transportBarRects` 矩形表，渲染与命中共用；传输条同病同治），矩形表为纯函数可单测。
- **层名/速度框失焦丢字**：输入框失焦时未回写，改动随焦点丢失静默蒸发（层名还会被渲染每帧用层值覆盖）。失焦走与 Enter 相同的提交路径；内核同值提交不记撤销，blur 提交不制造垃圾撤销记录。
- **onClose 不查 BE 存活**：关闭时未确认方块实体仍存活就走同步/回屏，BE 已消失时可能回到旧实例。关屏路径先查 `getBE()`，缺失则直接 `setScreen(null)`（与 `tick` 的存活守卫同口径）。

### A4. 上传上限回归 256 KB / Restore the 256 KB upload cap

- **背景**：`NbsSong.MAX_BYTES` 曾临时放大到 1024 KB 供高密度压测（TODO(v1.2.6-test)：测毕回 256 KB）。压测已完成，按 TODO 口径回归 256 KB。
- **默认值口径**：这是「未调整玩家行为」面的默认值——超 256 KB 的曲目会重新被导入/上传拒收。如需把 1 MB 转正为产品上限，一行改 `MAX_BYTES` 并同步 CHANGELOG 口径即可（待用户拍板，默认遵 TODO）。

## 2. 批次二：双样式视图（层序器 + 卷帘）/ Batch 2: Dual-Style Views

**状态：✅ 已解决（2026-10-17）**（用户拍板「做双样式」；回归锚点 `NbsLaneViewTest` + `NbsEditorKernelTest`）

社区主流（OpenNBS / NBS 原厂）的网格模型是 **tick × 层行**（音高 = 格子属性、底部横排钢琴条选键），
与卷帘（tick × 音高）是两种编辑面。双样式在同一数据模型（`(tick, layer)` 单元）上提供两个视图：

- **ROLL（卷帘，默认）**：既有视图，未调整行为不变。
- **LANE（层序器 + 底部钢琴条）**：社区原生布局与交互——
  - **布局签名**：层行与左层面板逐行对齐（同滚动、同行高，行高 20px）；tick 刻度尺在网格顶。
  - **格子语义**（内核 `placeNote`）：左键单击空格放音符、点已有格**整格覆盖**为工具属性
    （`add_block_manual`/`change_block_manual` 合一，不保留旧属性）；同值 = 空操作不入撤销；
    锁定层拒绝；属性钳位（键 0–87、力度 0–100、声像 0–200、细音高 ±32767）。
  - **左键拖动刻意留白**：社区左键拖 = 框选（随批次三 B1 落地），涂抹会污染肌肉记忆。
  - **右键按住擦除**（每格一次、笔划分组撤销）。
  - **试听**：落点/覆盖/选键即发声（社区签名习惯）。
  - **底部钢琴条**：点键 = 选工具键 + 试听（默认 45，NBS 基准键）；◀/▶ 或滚轮平移八度窗；
    白键按槽、黑键骑缝（黑键命中优先，纯几何 `keyRects` 单一真相源）；发音窗口 33–57 外遮罩。
  - **打字 = 琴键**：社区映射 `1Q2W3ER5T6YU8I9O0PZSXDCVG` → 键 33..57（恰好覆盖精确窗口）。
  - **滚轮**：横滚时间 / Ctrl 缩放时间 / Shift 翻层；钢琴条上滚轮平移八度。
- 顶栏「卷帘 / 层列」按钮切换（**默认卷帘**——未调整行为不变）；两视图各持自己的时间缩放/滚动。

**实施记录 / Implementation Record（2026-10-17）**

```
feat: dual-style lane view for the nbs editor
feat: lane-view placement semantics in the nbs kernel
```

（哈希见 `git log`；`NbsEditorLayoutTest` 顶栏断言随视图按钮扩为 8 键。）

### 批次二·二期：信息排布 + 播放习惯（社区对照反馈）/ Dual-Style Phase 2: Clarity & Playback Habits

**状态：✅ 已解决（2026-10-17）**（用户对照社区截图反馈三件：信息排布不清晰、播放不翻页、播放中可改图）

- **信息排布清晰化**：①层行补信息——音量% + 声像(±) + 锁定标记（社区层行的信息密度）；
  ②滑杆数值截断修复（旧 x+w+4 把 "100"/"+100" 画出面板外，改为右对齐面板缘 + 滑杆让位）；
  ③钢琴条键帽——每键全音名 + 打字映射字符（社区同款键帽信息）、选中键名前置到范围标签、
  发音窗口外改**红色提示**（社区同款，替代灰遮罩）；④层序器音符块——音名标签（够宽才画）+
  力度明暗（社区 draw_block 同款）；⑤刻度尺时间副标（tick 下加 m:ss）。
- **播放自动翻页**（社区 page-by-page 语义）：播放头越过右缘 → 翻页、头落新页左缘；回退越过
  左缘 → 头落右缘；**视口内不接管**（不与用户滚动抢方向）。两视图同款（`followPlayhead`，
  取代原预留的 `ensureTickVisible`；`resetView` 死方法一并清除）。
- **播放锁**（社区 `playing = 0` 语义——播放中网格坐标不落、完全不可编辑）：播放中放置/擦除/
  笔划全部拒绝并提示「播放中不可编辑」；刻度尺 seek、钢琴条选键/试听、层面板编辑不受限。
- 回归锚点：`NbsLaneViewTest`（翻页/键帽）。

### 批次二·三期：跟弹与声调操作（社区对照反馈二）/ Phase 3: Play-Along & Pitch Operations

**状态：✅ 已解决（2026-10-17）**（用户第二轮反馈三件：钢琴条不跟弹、缺滚轮调声调、音符声调不显示）

- **钢琴条跟弹**：播放/试听的音符按键点亮 250ms（社区钢琴条随谱子按键的习惯）；放键/覆盖/
  选键/滚轮调声调同样点亮。状态来源 = 编辑器试听传输每刻展开的 NoteEvent。
- **悬停滚轮调声调**（社区悬停微调习惯）：悬停音符格 + 滚轮 = 音高 ±1 半音、Shift = ±12 八度
  （力度/声像/细音高保留，内核 `changeNoteKey` 精确逆撤销、同键空操作、锁定层拒绝、键钳 0–87）；
  块面音名即时更新并回显新键名；播放锁同样拦下。滚轮语义优先级：悬停音符 > Shift 翻层 >
  时间滚动/缩放（原语义保留）。
- **音符声调常显**：块面标签全名（F#3）优先、窄格降级音级（F#/C）、再窄不画——层列默认缩放
  放宽到 16px/tick 使音级标签在常规视图常显。卷帘不加块面标签（纵轴即音高，位置已表音）。
- 回归锚点：`NbsEditorKernelTest.changeNoteKeyKeepsDynamicsAndGuards`、`NbsLaneViewTest`。

### 批次二·四期：横竖双滚动条（社区对照反馈三）/ Phase 4: Scrollbar Pair

**状态：✅ 已解决（2026-10-17）**（用户第三轮反馈：只靠滚轮会迷路、翻很久找不到位置）

- **横滚动条 = 时间轴**（tick），**纵滚动条 = 层行**（层列视图）/ **键轴**（卷帘视图，自顶向下）。
- 行为对齐社区滚动条职责：滑块比例 = 可见/总量（最小 12px 保证可抓）、**拖滑块定位**、
  **点轨道翻页**（朝点击侧一页）；悬停亮边。
- 几何与映射是纯函数（`NbsScrollBar.thumbRect` / `posFromThumbDrag` / `posFromTrackClick`），
  渲染与命中共用同一张滑块矩形（单一真相源）；状态映射（tick/层行/键轴）收敛在屏幕的
  `hScrollState`/`vScrollState`/`apply*Scroll` 一处。
- 布局：网格可视区右缘与底缘各让位 10px 给滚动条；钢琴条/传输条/层面板位置不变。
- 回归锚点：`NbsEditorLayoutTest`（滑块比例/钳位/拖动映射/翻页钳位）。

### 批次二·五期：滚轮防误触（社区对照反馈四）/ Phase 5: Wheel Mis-Touch Guard

**状态：✅ 已解决（2026-10-17）**（用户第四轮反馈：滚轮滚谱子时会误碰音符）

- **纯滚轮永不改谱**：滚轮调声调改 **Alt 门控**（Alt + 悬停音符 + 滚轮 = ±1 半音、Shift = ±12
  八度）；纯滚轮一律只滚动/缩放/翻层。社区对同一风险的解法是 `changepitch` 模式开关
  （关=纯滚动），我们用 Alt 门控达成同等保证且无模式状态可忘。
- **滚动后点击防误触**：滚轮活动后 200ms（`SCROLL_GUARD_MS`）内的网格编辑点击视为滚动时
  手指蹭键——吞掉并提示「防误触」；刻度尺 seek 等无害点击不受限。

### 批次二·六期：键位系统作用域 + NBS 命令入系统 / Phase 6: Binding Scopes & NBS Commands

**状态：✅ 已解决（2026-10-17）**（用户拍板：NBS 命令进键位系统 + 「三个屏幕可绑同键」设计）

- **作用域模型**：`EditorKeys.Scope {GRAPH, PIXEL, NBS}` —— 三个编辑器屏幕是三个独立绑定空间，
  **同一键可分别绑定三个作用域的动作**；序列匹配只在本作用域内进行（`feedKey`/`feedClick`
  传作用域，换域自动清半截缓冲——跨屏组合不接龙）；查冲突只在同作用域内（含 PAN 抓图键
  独占也只限图域——拦截只发生在图编辑器屏幕内）。配置格式零变更（动作名唯一），旧配置照读。
- **NBS 命令入系统**（13 个动作）：播放/暂停、停止、撤销、重做、保存、导入、导出、视图切换、
  循环、乐器前后、八度窗前后。出厂默认 = 旧硬编码（Space / Ctrl+Z / Ctrl+Y），其余出厂不绑
  （按钮即默认）；`Ctrl+Shift+Z` 重做别名按 DELETE_WIRE 老组合先例保留。NBS 面板按钮与键位
  绑定共用同一派发路径（`dispatchNbsAction`），行为不漂移。
- **一致性伤治愈**：撤销/重做按作用域各绑各的（NBS_UNDO 与图域 UNDO 同键出厂），重绑互不
  干扰——正是「同一系统两套行为」问题的正解。
- **补丁（用户实测反馈）：「绑定空键」**——NBS 多数动作出厂未绑（显示「—」），但系统原先
  不允许改回未绑（`确定绑定` 拒绝空录入队列，且空值读取时回落出厂、清绑存不住）。现
  **空队列 + 确定 = 解除绑定**（清除 → 确定即绑空键），引擎新增 `clearSequence`，持久化为
  **显式空 seq**（load 区分「显式空=未绑」与「缺键=出厂」，旧配置兼容）。
- 回归锚点：`EditorKeysSequenceTest`（同键三域/换域清缓冲/清绑往返）、`EditorKeysPixelAndWireTest`
  （跨域可绑语义更新）。

## 3. 批次三：编辑表达力 / Batch 3: Editing Expressiveness

**状态：🔶 待办**。社区核心工作流三件套，内核测试先行（`NbsEditorKernelTest` 扩展为回归锚点）；两视图通用（LANE 视图的左键拖留白即为 B1 框选预留）。

### B1. 选择 + 剪贴板 / Selection & clipboard

框选（左键拖矩形）、Ctrl 加/减选、复制/剪切/粘贴/删除、全选/反选。内核引入 Selection 模型（选区 = tick×layer 矩形 + 单格集合），选区级操作走现有 `SnapshotEdit` 快照撤销；剪贴板为曲目片段（音符+层映射），可与系统剪贴板互通（参考实现的压缩串剪贴板语义可对齐，MIT）。

### B2. 音符拖移搬移 / Drag-to-move notes

数据层 `NbsSong.moveNote` 已在（现为死代码），补内核 MoveEdit 与拖动手势，与现有「同 tick 内换键=音符跟随」合成统一拖动语义：拖到新格=搬移（跨 tick/层），同格换键=改音高（社区同款）。

### B3. 逐音符力度/声像/细音高编辑 / Per-note velocity / panning / pitch editing

字段与播放链路一直是全的（放置默认 100/居中/0，`NoteEvent` 已消费），只缺编辑面。最小闭环：选中后 Set 弹窗（绝对值/增量两种模式）+ 快捷键或悬停滚轮微调，不抄参考的四模式工具栏全套。依赖 B1 的选区。

## 4. 批次四：播放与层体验 / Batch 4: Playback & Layer Polish

**状态：🔶 待办**。

- **播放跟随**：✅ page-by-page 翻页已随批次二·二期落地（两视图 `followPlayhead`）；剩居中模式与播放区域（section）。
- **循环区域 UI**：`loopStartTick`/`maxLoopCount` 数据模型与回绕逻辑已就绪、随文件保真，只缺编辑入口（时间轴拖段 + 右键菜单简化版 + 层面板数字框）。
- **层重排（上移/下移）**：NBS 层序语义保真需要（参考的 Shift up/down）；层 solo/静音牵动 `MusicTransport` 烘焙过滤，列在重排之后单独评估。

## 5. 明确不追与远期 / Out of Scope & Far Future

**不追**（桌面 DAW 关注点）：MIDI 双向、录音模式、音频/schematic/datapack 导出、宏库（tremolo/vibrato/…）、主题系统、.nbp 图案、曲谱视觉特效。

**远期单独评估**：曲中变速（参考用「Tempo Changer 伪乐器」实现，生态兼容特性、成本高）；自定义乐器发声（现只保数据，牵动资源包/采样链路）；多人逐音协作 op（plan §3.2 已列后续）。

## 6. 与参考的功能对照 / Feature Parity Snapshot

| 面 / Area | 我们 / Ours | OpenNBS 参考 | 批次归属 |
|---|---|---|---|
| 网格模型（轴向） | ✅ 双样式：LANE=层行 / ROLL=音高 | 时间 × 层行，音高=格子属性 | ✅ 批次二 |
| 钢琴条（选键/试听/琴键映射） | ✅ LANE 底部横排（含打字弹琴） | 底部横排 | ✅ 批次二 |
| 放置/擦除/涂抹、点放语义 | ✅ 语义一致 | ✅ | — |
| 选择 + 剪贴板 | ❌ | ✅ 框选/加减选/压缩串剪贴板 | B1（批次三） |
| 跨 tick 拖移搬移 | ❌（数据层在、无手势） | ✅ 拖选区/拖块 | B2（批次三） |
| 逐音符 vel/pan/pitch 编辑 | ❌（字段全、无 UI） | ✅ 四模式 + Set 对话框 + 滚轮微调 | B3（批次三） |
| 层属性（名/音量/声像/锁定） | ✅ | ✅ | — |
| 层重排/solo | ❌ | ✅ Shift up/down + solo | 批次四 |
| 循环（含回绕参数） | 数据在、UI 缺 | ✅ 完整 | 批次四 |
| 播放跟随/播放区域 | ❌ | ✅ 翻页/居中 + section | 批次四 |
| NBS 读/写保真 | v1–v5 读 / v5 写 | v0–v5 读 + 降级保存 | 不追 v0 |
| 自定义乐器 | 保数据不发声 | ✅ 240 个 + 采样管理 | 远期 |
| 撤销模型 | ✅ 双轨 + 笔划分组 | ✅ 13 类历史 | — |
