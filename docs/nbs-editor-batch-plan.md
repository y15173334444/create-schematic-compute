# NBS 编辑器批次改进计划 / NBS Editor Batch Improvement Plan

> **状态：✅ 批次一已落地（2026-10-17）；🔶 批次二/三待办**
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

## 2. 批次二：编辑表达力 / Batch 2: Editing Expressiveness

**状态：🔶 待办**。社区核心工作流三件套，内核测试先行（`NbsEditorKernelTest` 扩展为回归锚点）。

### B1. 选择 + 剪贴板 / Selection & clipboard

框选（左键拖矩形）、Ctrl 加/减选、复制/剪切/粘贴/删除、全选/反选。内核引入 Selection 模型（选区 = tick×layer 矩形 + 单格集合），选区级操作走现有 `SnapshotEdit` 快照撤销；剪贴板为曲目片段（音符+层映射），可与系统剪贴板互通（参考实现的压缩串剪贴板语义可对齐，MIT）。

### B2. 音符拖移搬移 / Drag-to-move notes

数据层 `NbsSong.moveNote` 已在（现为死代码），补内核 MoveEdit 与拖动手势，与现有「同 tick 内换键=音符跟随」合成统一拖动语义：拖到新格=搬移（跨 tick/层），同格换键=改音高（社区同款）。

### B3. 逐音符力度/声像/细音高编辑 / Per-note velocity / panning / pitch editing

字段与播放链路一直是全的（放置默认 100/居中/0，`NoteEvent` 已消费），只缺编辑面。最小闭环：选中后 Set 弹窗（绝对值/增量两种模式）+ 快捷键或悬停滚轮微调，不抄参考的四模式工具栏全套。依赖 B1 的选区。

## 3. 批次三：播放与层体验 / Batch 3: Playback & Layer Polish

**状态：🔶 待办**。

- **播放跟随**：翻页/居中模式（`NbsPianoRoll.ensureTickVisible`/`resetView`/`scrollKeys` 三个预留方法激活），长曲边播边看。
- **循环区域 UI**：`loopStartTick`/`maxLoopCount` 数据模型与回绕逻辑已就绪、随文件保真，只缺编辑入口（时间轴拖段 + 右键菜单简化版 + 层面板数字框）。
- **层重排（上移/下移）**：NBS 层序语义保真需要（参考的 Shift up/down）；层 solo/静音牵动 `MusicTransport` 烘焙过滤，列在重排之后单独评估。

## 4. 明确不追与远期 / Out of Scope & Far Future

**不追**（桌面 DAW 关注点）：MIDI 双向、录音模式、音频/schematic/datapack 导出、宏库（tremolo/vibrato/…）、主题系统、.nbp 图案、曲谱视觉特效。

**远期单独评估**：曲中变速（参考用「Tempo Changer 伪乐器」实现，生态兼容特性、成本高）；自定义乐器发声（现只保数据，牵动资源包/采样链路）；多人逐音协作 op（plan §3.2 已列后续）。

## 5. 与参考的功能对照 / Feature Parity Snapshot

| 面 / Area | 我们 / Ours | OpenNBS 参考 | 批次归属 |
|---|---|---|---|
| 放置/擦除/涂抹、点放语义 | ✅ 语义一致 | ✅ | — |
| 选择 + 剪贴板 | ❌ | ✅ 框选/加减选/压缩串剪贴板 | B1 |
| 跨 tick 拖移搬移 | ❌（数据层在、无手势） | ✅ 拖选区/拖块 | B2 |
| 逐音符 vel/pan/pitch 编辑 | ❌（字段全、无 UI） | ✅ 四模式 + Set 对话框 + 滚轮微调 | B3 |
| 层属性（名/音量/声像/锁定） | ✅ | ✅ | — |
| 层重排/solo | ❌ | ✅ Shift up/down + solo | 批次三 |
| 循环（含回绕参数） | 数据在、UI 缺 | ✅ 完整 | 批次三 |
| 播放跟随/播放区域 | ❌ | ✅ 翻页/居中 + section | 批次三 |
| NBS 读/写保真 | v1–v5 读 / v5 写 | v0–v5 读 + 降级保存 | 不追 v0 |
| 自定义乐器 | 保数据不发声 | ✅ 240 个 + 采样管理 | 远期 |
| 撤销模型 | ✅ 双轨 + 笔划分组 | ✅ 13 类历史 | — |
