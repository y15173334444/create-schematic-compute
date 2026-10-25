# 遗留问题登记 / Known Open Issues
> 更新日期 / Updated：2026-10-25
> 版本 / Version：1.3.0（WIP）
> 范围 / Scope：本文件登记**已定性、暂缓修复**的缺陷——每条含症状、机制、触发条件、修复方向与状态。新条目追加在汇总表与正文之后。
> 状态 / Status：🔶 待办 10 项（详见汇总表）；修复落地后把对应条目改为 ✅ 已解决并注明落地版本。
> ⚠️ 引用约定 / Reference convention：引用本册条目一律用**标题**（如「known-open-issues 条目『频道释放依赖快照路径』」），
> **不要用 `#N`** —— GitHub 会把 `#N` 自动链接到仓库的真实 issue（`issue #10/#11/#12/#15/#17` 等才是真链接），
> 本册的 `## 1./## 2.` 只是本地序号。/ Always cite register entries by **title**, never as `#N` —
> GitHub auto-links `#N` to the repository's real issues (`issue #10/#11/#12/#15/#17` are the real
> ones); the `## 1./## 2.` headings here are local numbering only.

---

## 汇总 / Overview

| # | 标题 / Title | 症状一句话 / One-line symptom | 修复建议 / Fix direction | 状态 / Status |
|---|--------------|------------------------------|--------------------------|---------------|
| 1 | temp-id 挂起队列随关屏丢弃 | 加点后立刻输入、一个 RTT 内关界面 → 重开回到默认值 | op 日志回放（另立项） | 待立项 |
| 2 | 频道释放依赖快照路径（含别名死守卫） | 删除/改名的频道释放靠重编译快照兜底，旧图 diff 双守卫恒空转 | 消除别名死守卫 + 收窄释放窗口（撤销/重做语义随做） | 🔶 待办（机制已修正 2026-10-10，影响面降级） |
| 3 | 打字途中面板重建（补全弹窗被吞 · 输入框每击键换血） | 公式脚本框打字时补全候选框闪现即失，每次击键整个输入框被换实例 | 三处一批：指纹剔公式文本 + SET_FORMULA 远端定点刷新 + 草稿策略 | 🔶 待办（幽灵选区本体已修 2026-10-11） |
| 4 | 功放开盖模型越出方块背面 0.75px | `lit=true` 时盖子最外上角到 Z16.75，紧邻方块背面可见穿模 | 铰链前移 1px / 盖子做矮到 8px / 接受现状 | 🔶 待办（建模决定） |
| 5 | NBS 笔划分组漏掉按下格（一次笔划 = 两条撤销） | 一次涂抹/擦除笔划要撤销两次：拖过格并成一条、按下格独立一条 | beginBatch 提到落点之前 + 一条回归测试 | 🔶 待办 |
| 6 | 像素编辑器配色半收编（static final 捕获 + 硬编码按钮/悬停） | 与 NBS 收编前同款病：panel_bg/panel_border 类加载时捕获、按钮/悬停写死，换主题不跟 | 照 NBS 收编的同款映射一次搬 | 🔶 待办 |
| 7 | 频道清理残余豁口（ChannelOwner 无图域 + 客户端旗标/镜像清理） | 主图/子图节点 id 撞号时 owner 门失真；syncBusBands/空表 clearBus 靠旗标或镜像语义门控 | ChannelOwner 加图域全链带上 + 两处清理改 owner 门控 | 🔶 待办 |
| 8 | 时序/逻辑节点在 SeatInput 档宿主静默空转（op 注入/旧档形态） | Sensor/ControlSeat 的 4 参 evaluate() 对七类时序/状态节点无 case，输出逐拍冻结 | 档位合一或档外显式拒绝（行为变更，待拍板） | 🔶 待办（2026-10-25 更新：eval 分派化已落地 main，怪癖按决策 §三-4 原样保留并收敛到单一事实源） |
| 9 | 节点准入名单仅客户端把守（改包客户端可任意 ADD_NODE） | 服务端 op 链不校验 `BlockNodeAllowances`，改包客户端可把任意类型塞进任意宿主 | 服务端 op 链按准入名单校验，超名单 REJECT（与 K10 服务端化同批） | 🔶 待办（2026-10-17 补登记，复审 P2-7） |
| 10 | AST 与 RPN 双前端并行（旧表示退役时间点未定） | 同一份公式有两套可执行表示并存于 FORMULA 求值（`script.ast` 空 → 旧扁平 RPN 循环；非空 → 刀3 解释器），维护面翻倍 | 拍板退役时间点与迁移口径后单批删除旧路径 | 🔶 待办（拓扑 P5 拆分已把两前端物理分离，退役不再受阻） |

---

## 1. temp-id 挂起队列随关屏丢弃（编辑窗口极端形态）

范围 / Scope：`GraphEditor`（统一 op 出口与挂起队列）、`GraphOpHistory`（ACK 重映射）、`GraphEditAckPacket`（ACK 投递约定）

### 症状 / Symptom

新建节点后**立刻**输入参数/连线，并在一个网络往返（RTT）内关闭图编辑器——下次打开编辑器全量同步后，该节点的参数回到默认值，窗口内输入的编辑全部丢失。

### 机制 / Mechanism

背景是 temp-id 窗口守卫：加点 op（`ADD_NODE_REQUEST`）携带客户端本地临时 id，服务端按自己的计数器分配真实 id；两者漂移时，ACK 回来之前发出的编辑 op 引用临时 id，服务端 `findNode` 落空会被静默丢弃。守卫的做法是把引用未 ACK 临时 id 的 op 在统一出口（`GraphEditor.sendOp`）**挂起**，ACK 到达（`remapNodeId`）后改写 id 补发。

遗留的极端窗口在 ACK 的投递约定上：ACK 处理器只对**仍打开**的编辑器做重映射与补发（界面已关则有意丢弃该 ACK，约定下次打开走全量同步）。若编辑器在 ACK 回来之前关闭：

- 挂起队列随编辑器实例一起清空（关屏即丢弃未冲刷的挂起 op）；
- 迟到的 ACK 没有接收者，重映射与补发窗口彻底关闭；
- 但 `ADD_NODE_REQUEST` 本身早已到达服务端——节点创建成功，只是它在窗口内的参数/连线 op 永远没有补发机会。

### 触发条件 / Trigger

「加点 → 立刻输入 → 一个 RTT 内关界面」。高延迟服务器、或玩家快速开关界面时概率上升；正常输入速度（编辑动作间隔超过一个 RTT）不受影响。

### 影响面 / Impact

仅极端时序窗口。损失范围 = 该节点在窗口内的全部编辑内容（重开后呈默认值）；节点本体与图结构不丢。

### 修复方向 / Fix direction

彻底解法是 **op 日志回放**，把补发窗口从编辑器实例的生命周期解耦：

- 挂起队列下移到 BE/会话级（或服务端 `EditSessionRegistry` 既有 opLog 按其重连回放设计消费），迟到 ACK 仍能触发补发；或
- 客户端关屏时把未 ACK 的挂起 op 持久化，重开时**先补发、再全量同步**——顺序不能反，否则全量同步会把补发值再次冲掉。

两条路线都要处理与多人并发、撤销/重做语义的交互，建议另立项。

### 状态 / Status

**待立项。** 当前守卫已覆盖绝大多数窗口（仅「关屏时序」例外）。回归锚点：`EditPanelUploadRoundTripTest` 的「新节点 ACK 未回时输入的参数必须在 ID 重映射后仍到达服务端」（该用例覆盖的是窗口内的正常路径，即 ACK 在编辑器仍打开时到达）。

---

## 2. 频道释放依赖快照路径（含别名死守卫）

范围 / Scope：`GraphHost`（重编译期频道生命周期）、`BusChannelHelper`（`reRegisterChannels` / `syncDeletedBusNames` / 注册内核）、`SignalBus`（`CHANNELS` 引用计数）

> 🔶 机制已于 2026-10-10 按代码核实修正（本条初版的「幽灵占名持续到服务端重启」不成立，见下）；
> 改名一侧已由 `BusChannelHelper.applyBusOutRename` 当场迁移（见 CHANGELOG「BUS_OUT 改名/首次命名丢频道」）。

### 症状 / Symptom

删除（或改名）BUS_OUT 后的短窗口内，同名查询/重建会看到旧状态：同名新建的 BUS_OUT 可能被误标「频道已占用」（`busConflict`）而静默。窗口远小于初版登记的「直到服务端重启」——但窗口的存在与守卫的脆弱性仍值得登记。

### 机制 / Mechanism（修正版 / corrected）

频道生命周期维护实际有四个挂点：宿主首 tick 注册（`registerChannels`）；**每次 graphChanged 重编译**（`recompileEvaluatorFull`/`Light`）按 `name@id` 快照差集释放已删除/已改名的发布者（`unregisterRemovedBusOutNodes`）并做差集重注册（`reRegisterChannels`）；宿主卸载时注销；以及 op 应用时的改名迁移（`applyBusOutRename`，2026-10-10 起）。因此：

- **删除会在下一次重编译（约一 tick）释放**，改名亦然（快照键含名字，旧键消失即按 owner 注销）——初版「幽灵占用持续到服务端重启」的描述**不成立**；
- 真正的残留问题是**别名死守卫**：`reRegisterChannels` 的「旧图 diff」（仅注销被移除节点）与 `syncDeletedBusNames`（删除名的客户端清表推送）拿的 `oldGraph = lastEvaluatedGraph` 与当前 `graph` 是**同一个对象**（图被原地修改），diff 两边恒相等、两守卫在编辑会话中**从不生效**（CLAUDE.md「死守卫」警示的形态）——移除/改名清理实际完全押在 `lastBusOutKeys` 快照路径上，一旦该路径缺席（如首 tick 注册后、首个快照落位前删除节点）即泄漏 `CHANNELS` 条目，且**旧名的客户端 BAND_REGISTRY 残留无人清**（`syncDeletedBusNames` 空转的直接后果）；
- 另有一个一 tick 级窗口：op 落地到下一次重编译之间，查表仍见旧状态（改名侧已由迁移当场关闭）。

### 触发条件 / Trigger

删除/改名 BUS_OUT 后立刻（同一 tick 内）同名查询或重建；或在「首 tick 注册后、首个重编译快照前」的窄窗内删除。跨方块同名占用判定本身是预期行为（issue #12/#14），不在此列。

### 影响面 / Impact

一 tick 级的查表/重建不一致；窄窗删除泄漏的 `CHANNELS` 条目会造成同名重建被误标冲突（需重启或等残留 owner 回收）；客户端 `BAND_REGISTRY` 的旧名残留仅影响编辑器着色/定义显示，不影响求值。

### 修复方向 / Fix direction

让移除/改名清理不依赖单一路径：给 `reRegisterChannels` / `syncDeletedBusNames` 传入**真正的旧图快照**（或改用不别名的键集差集，与 `lastBusOutKeys` 同源），消除死守卫；删除路径顺带补撤销语义（撤销删除恢复节点时频道条目随恢复注册，与 `applyBusOutRename` 同一 owner 纪律与幂等内核）。

### 状态 / Status

🔶 待办。已落地部分：改名迁移（`applyBusOutRename`，服务端/客户端幂等双跑）与 REJECT 化的 op 静默丢弃修复；回归参照 `BusOutRenameChannelTest`。初版登记的「同名重建被误标占用」症状保留为窄窗形态。

> 🔧 2026-10-11 补注（评审轮）：「确证离开 → 退役定义」规则已统一到 `SignalBus.releaseChannel`
> 一处，覆盖改名 / 删除 / 显式清空三条产生路径（本批补上了删除路径与空上传的 owner 门控，
> 「改名 retire / 删除 unregister」的不对称已消除）；瞬时缺席（宿主卸载 / 区块 unloaded）保持
> 「未定义」语义不动。残留两条：① 已定义为空的 `BAND_REGISTRY` 条目**无终局清理**，退役后
> 名字若永不再用会滞留到停机；② 频段上传路径不 `setChanged`（对比收敛路径显式落盘）——
> 既有现状、非回归。/ Uniform rule "provable departure -> retire the definition" now lives in
> `SignalBus.releaseChannel` across rename / delete / explicit-empty (this batch closed the
> delete-path and empty-upload gaps; the rename-retire/delete-unregister asymmetry is gone);
> transient absence keeps its "undefined" semantics. Two residuals: retired BAND_REGISTRY
> entries have no final cleanup (they linger until shutdown if the name is never reused), and
> the band-upload path does not call setChanged (the convergence path persists explicitly) —
> pre-existing, not a regression.

---

## 3. 打字途中面板重建（补全弹窗被吞 · 输入框每击键换血）

范围 / Scope：`GraphEditor.editStateSignature`（结构指纹）、`GraphEditor.restoreOrRebuildEditStates`（恢复/重建门）、`GraphRemoteApplier`（远端 op 的编辑面板刷新）、`NodeEditStateFactory.create`（控件重建）

### 症状 / Symptom

在公式脚本框打字时，自动补全候选框（`FormulaSuggestPopup`）刚弹出就被吞掉；每次击键整个输入框实例被换掉（视觉行缓存、弹窗状态随之清零）。幽灵选区（删除/输入时部分内容被全选）本体已于 2026-10-11 修复（CHANGELOG 条目「公式编辑区删除/输入时部分内容被全选」），**本条登记的是重建节奏本身的残留**。

### 机制 / Mechanism

打字 → 响应器改 `node.formula` 并发 SET_FORMULA → 图代际变化/远端回声，**两条通道**都重建面板：

1. **指纹通道**：`editStateSignature` 把 `formula.hashCode()` 编进「结构指纹」，公式文本每键变化都被判为结构变化 → `restoreOrRebuildEditStates` 换掉输入框实例。指纹契约的既有例外是「参数**数值**不进指纹」（排除理由正是「值一变就重建会刷掉草稿」）——公式文本是脚本框的「值」，却进了指纹，与该例外自相矛盾。
2. **远端回声通道**：`GraphRemoteApplier` 对 SET_FORMULA（一切非 SET_PARAM op）走「整个 EditState 重建」——本地 op 的服务器回声每击键重建一次。SET_PARAM 有定点刷新（草稿字符串 setValue + 聚焦守卫），SET_FORMULA 没有。

补全弹窗挂在 MLE 实例上，实例被换掉 → 弹窗即失。

### 触发条件 / Trigger

展开 FORMULA 节点并打字（每击键一次）。键入 `@output` 等改变引脚数的内容会经 `inputs()/outputs()` 触发重建——那是真实结构变化，属正常路径。

### 影响面 / Impact

纯 UX 磨损：补全弹窗在打字中基本不可用；每击键重解析公式 + 校验 + 重建控件与视觉行缓存。不再有数据破坏（幽灵选区与双发 SET_FORMULA 已修，锚点成对恢复/选区契约有回归锚点 `MultiLineEditBoxSelectionTest`、`EditStateRebuildSelectionTest` 锁定）。

### 修复方向 / Fix direction

三处一批，**缺一无效**：

- **指纹剔除 `formula.hashCode()`**：文本的结构效应已由 `inputs()/outputs()/bandCount` 覆盖；同步排查依赖指纹刷新的派生显示（错误边框已由响应器实时 `setHasError`，不依赖重建）。
- **`GraphRemoteApplier` 的 SET_FORMULA 改定点刷新**：参照 SET_PARAM 分支（草稿字符串 `setValue` + 聚焦守卫），不再整框重建。
- **草稿策略取舍（动手前先拍板）**：对端 SET_FORMULA 到达时本端正聚焦输入——跳过保草稿（与 SET_PARAM/busBox 同口径）还是强制收敛。

⚠️ 只删指纹那一行是错的做法：远端回声通道照旧每键重建，症状原样复现，且会引入「对端编辑在开着的面板里不显示」的多人新回归。

### 状态 / Status

**🔶 待办。** 幽灵选区本体已修（2026-10-11，v1.2.6 WIP）；本条按「三处一批」另批立项，落地后改 ✅ 并注明版本。

---

## 4. 功放开盖模型越出方块背面 0.75px

范围 / Scope：`models/block/amplifier_computer_on.json`（`PM` 件 +22.5° 旋转）、手稿 `assets-src/amplifier_computer/gf.json`、`AmplifierComputerBlock`（形状口径，已于 2026-10-11 落地）

### 症状 / Symptom

功放电脑进入运行态（`lit=true`，开盖）后，盖子最外侧的上角到达 **Z=16.75**，比方块背面（Z=16）高出 0.75px（≈1/21 格）。紧贴方块背面放置方块时可见穿模；两台功放背靠背摆放时互相插入。

### 机制 / Mechanism

`PM` 件 `[1,4,12]–[15,14,13]` 绕铰链（Y4/Z12）转 +22.5° 后，其 AABB = X 1–15 / Y 3.62–13.24 / Z 12–16.75。越界量 = `盖高·sin22.5° + 盖厚·cos22.5° − (16 − 铰链Z)` = `10×0.3827 + 1×0.9239 − 4 = 0.75`。原版元素旋转角白名单只允许 **0 / ±22.5 / ±45**（`BlockElement.Deserializer.getAngle` 抛 `Invalid rotation`），22.5° 已是最小的合法非零角 —— 「把角度调小一点」这条路在静态模型里不存在。

### 触发条件 / Trigger

`lit=true`（图运行）且方块背面（盖倾方向）或紧邻位置存在其他方块。

### 影响面 / Impact

纯视觉穿模。碰撞箱不受影响 —— 碰撞只取底座（Y0–4、X/Z 满幅，与朝向和开合都无关）；轮廓/瞄准靶为整机包络，不参与物理。

### 修复方向 / Fix direction

三选一，属**建模决定**：

- **铰链前移 1px**：键盘面 Z2–12 → Z1–11、铰链 Z12 → 11（机箱与蓝件不动），开盖最大 Z = 15.75 ✓；
- **盖子做矮到 8px**（Y4–12）：最大 Z = 15.98 ✓，代价是屏面从 14×10 缩到 14×8；
- **接受现状**：0.75px 在 1/16 网格下肉眼几乎不可见，只在紧邻方块时穿模。

### 状态 / Status

**🔶 待办（等建模决定）。** 相关的碰撞箱口径已落地：`AmplifierComputerBlock` 碰撞 = `Block.box(0,0,0,16,4,16)`、轮廓 = 整机包络 `Block.box(0,0,0,16,13.25,16.75)`；`SpeakerBlock` 碰撞与轮廓 = 模型本体 `Block.box(3,0,3,13,14,13)`。

---

## 5. NBS 笔划分组漏掉按下格（一次笔划 = 两条撤销）

范围 / Scope：`NbsEditorScreen`（鼠标按下路由的 `beginBatch` 时序）、`NbsEditorKernel`（笔划分组）

### 症状 / Symptom

卷帘/层列一次拖动涂抹或右键擦除笔划（按下 1 格 + 拖过 N 格）需要**两次撤销**才能清空：拖过的格被并成一条撤销记录，按下那一格是独立一条。内核文档口径「一次笔划的多格落点合并成一条撤销」只兑现了一半。

### 机制 / Mechanism

`mouseClicked` 先调 `roll.mouseDown` / `laneGrid.mouseDown`（其内部已经 `apply` 落下第一格），**之后**才 `kernel.beginBatch()`——首格编辑在批量组开组之前直接入撤销栈，此后拖动的编辑才进组。`mouseReleased` 的 `endBatch` 只收口了拖动部分。

### 触发条件 / Trigger

任何落点笔划（两视图、左右键同病）：撤销次数 = 2 × 笔划数。

### 影响面 / Impact

撤销粒度不齐（连画三笔要按六次撤销），无谱面数据正确性问题；批量组状态本身成对开合，不会泄漏。

### 修复方向 / Fix direction

把 `beginBatch` 提到落点调用之前、未消费即刻 `endBatch`（或把开组责任下放给 `mouseDown`），一处时序调整；配一条回归测试「一次笔划 = 一条撤销」。

### 状态 / Status

**🔶 待办。** 随「卷帘同列第二笔挪走第一笔」诊断（2026-10-18）发现并登记，本批未动。

---

## 6. 像素编辑器配色半收编（static final 捕获 + 硬编码按钮/悬停）

范围 / Scope：`PixelEditorScreen` / `PixelEditorToolRail`（client 包）

### 症状 / Symptom

像素编辑器只把 `panel_bg`/`panel_border` 两个色接进了用户颜色配置，且是 `static final` **类加载时捕获**；按钮底/悬停高亮等仍硬编码。换主题/改配色后像素编辑器要么不跟、要么只在游戏重启后才跟。

### 机制 / Mechanism

`private static final int C_BG = NodeRenderer.PBG();` 这类写法把配置色在类初始化时定格；`applyTheme` 的原子数组交换对已捕获的常量无效。其余色值直接写字面量。

### 触发条件 / Trigger

任何运行中的主题/配色切换；或在配色面板保存自定义色后打开像素编辑器。

### 影响面 / Impact

纯视觉一致性：像素编辑器与图/NBS 编辑器在同一主题下观感分叉。无数据/功能影响。

### 修复方向 / Fix direction

照 NBS 编辑器收编同款（2026-10-18 批）：主题相关色改渲染时读 `NodeRenderer` 访问器（复用优先：bg/panel_bg/panel_border/inset_bg/accent/hover/error + CT/CD），内容色（画布像素）保持内置。

### 状态 / Status

**🔶 部分已修。** static final 捕获半已随 1.3.0 的 EditorTheme 批根除（2026-10-25：三件套——含后来新增的 `PixelEditorFrameStrip`——捕获解冻为 `EditorTheme.get()` 实时取值，`NodeRendererPaletteFacadeTest` 锚死门面委托不回潮）；按钮底/悬停/选中三硬编码色（`C_BTN`/`C_HOVER`/`C_SEL`）仍留守——槽位映射（按钮底对应哪个主题槽）是设计判断，留待收编批。发现于「NBS 编辑器配色收编」同日（同族病、按用户口径彼批只收 NBS）。

---

## 7. 频道清理残余豁口（ChannelOwner 无图域 + 客户端旗标/镜像清理）

范围 / Scope：`ChannelOwner` / `SignalBus`（owner 键空间）、`GraphBusEditor.syncBusBands`（客户端同名对齐 + 定义写入）、`BusBandSyncPacket`（客户端镜像清理）

> 🔶 2026-10-23 随「冲突者改名走人误退役频道」（v1.2.6.1）同批判定：该修复把退役改成候选感知、
> 把删除/BUS_IN 改名收口到 owner 门控 `releaseChannel`，本条登记的是同族的**剩余**豁口，本批不动。

### 症状 / Symptom

同宿主内主图与封装子图各建同名 BUS_OUT 时，频道条目会错挂到「同 id 的另一个图的节点」上；
或在冲突旗标/频段上传/空表同步包的窄窗里，客户端的清理与定义写入互相踩掉存活 owner 的数据。

### 机制 / Mechanism

两块独立的缺口：

1. **`ChannelOwner` 无图域**：owner 键 =（方块坐标, 节点 id），而节点 id 只在单个 `NodeGraph` 内唯一——
   主图与封装子图的 id 计数器各自独立、数值必然撞号（编辑器侧 temp-id 守卫的注释已明示这一点）。
   撞号时：注册被当成「同 owner 重注册」**静默接管**对方的 `internalMap` 引用；改名走人时又按 owner
   **匹配**释放掉对方的条目与定义——owner 门控在撞号面前整体失真。
2. **客户端旗标/镜像清理**：`syncBusBands` 写 `BAND_REGISTRY` 只靠**客户端冲突旗标**门控（旗标随图同步
   有合并延迟，滞后窗口里候选节点的频段会被灌成频道定义）；`BusBandSyncPacket` 收到空表时直接
   `clearBus`（不带 owner 门——过期空包在单机共享静态表上会清掉存活 owner 的定义与值）
   ——✅ 空表一路已修（1.2.6.1 实测二轮：只清无主镜像，存活 owner 的定义与值不碰），`syncBusBands` 一路仍在本条目下。

### 触发条件 / Trigger

同宿主主图 + 子图建同名 BUS_OUT（撞号必现）；或改名/冲突旗标同步与频段上传、空表同步包同帧交错（窄窗）。

### 影响面 / Impact

撞号场景是确定性破坏（频道条目/定义错挂、改名误释放）；旗标/包时序是窄窗级，但同为「频段被一起清理」
症状族。单机（共享静态表）比联机更易显形。

### 修复方向 / Fix direction

`ChannelOwner` 增加图域维度（`ownerNodeId`）并全链带上（注册/释放/快照键同口径）；
`syncBusBands` 的 `registerBands` 改成与 `releaseChannel` 同款的 owner 门控（客户端镜像写入只认 owner）。

### 状态 / Status

🔶 待办。

---

## 8. 时序/逻辑节点在 SeatInput 档宿主静默空转（op 注入/旧档形态）

### 症状 / Symptom

Sensor / 控制椅图里若出现 DELAY / LATCH / GATE / T_FLIPFLOP / PULSE_EXTEND / LOOP / FUSE
节点（菜单之外的形态），该节点每 tick 输出冻结（逐拍写回旧值），图"在跑但那几拍是死的"。

### 机制 / Mechanism

求值路径按宿主分档：全状态档（6 参）走 `evalExt`，七类时序/状态节点各有 case；SeatInput 档
（4 参 `evaluate()`，Sensor/ControlSeat 在用）直接进 `eval()`，而这七类在 `eval()` 的分派里
**没有 case**——switch 落空后尾部照常记账（`outputs.put(node.id, o.clone())`），把上一拍/
初始的 `outputValues` 原样写回。GATE 的状态体也在 `evalExt` 档，此路径同样空转。
菜单准入（`BlockNodeAllowances`）对 Sensor/ControlSeat 本就排除 SEQUENTIAL_STATE / LOGIC
类，常规搭建踩不到。

### 触发条件 / Trigger

- 改包客户端经 op 通道 `ADD_NODE` 注入（准入名单仅客户端把守，P2-7 同族进路）；
- 历史/外部存档携带此类节点。

### 影响面 / Impact

无数据破坏；注入/旧档形态下行为**静默错误**（输出冻结而非拒绝或告警）。与音响 writeOutputs
"防御性接线"（op 通道不校验准入、旧档也可能携带 → 写出口径与全宿主一致）的处置口径不一致：
求值侧选择了"静默跳过"。

### 修复方向 / Fix direction

两条路择一，随 eval 档统一 / K23 tick 体收敛拍板：SeatInput 档也接全状态三表（档位合一），
或对档外节点显式拒绝/告警。eval() 分派化后怪癖收敛到单一事实源（`TimingEval.evalState` 七类
/ `FormulaEval` 缺 GATE case 的缺省语义），改动面小。见
[`eval-category-dispatch-decision.md`](eval-category-dispatch-decision.md) §三-4。

### 状态 / Status

**🔶 待办。** 2026-10-17 随 eval() 分派化决策文档定性登记；**2026-10-25 eval() 按 NodeCategory 分派化已落地 main**（三批：EvalContext 落位 → Formula/Timing/Motion 抽取 → 其余七处理器 + 分派翻转），本怪癖按决策 §三-4 **原样保留**（搬迁纪律 = 行为零变更，不顺手修）——现在怪癖收敛到单一事实源：`TimingEval.evalState` 承接七类（evalExt 档）、`TimingEval.eval` 只实现 ACCUMULATOR/INTEGRATOR（4 参档缺省 = 静默空转）、`FormulaEval` 无 GATE。修法本身（档位合一 vs 档外显式拒绝）是**行为变更**，待拍板后另批实施。

---

## 9. 节点准入名单仅客户端把守（改包客户端可任意 ADD_NODE）

### 症状 / Symptom

改包客户端可绕过节点菜单，把任意 `NodeType` 加进任意宿主的图（如给传感器塞 FORMULA、
给音响塞 REDSTONE_OUT）；菜单准入纪律「方块整类接受或不接受」只对正版客户端生效。

### 机制 / Mechanism

`BlockNodeAllowances` 的准入名单只被客户端 Screen（菜单过滤）引用；服务端 op 链
（ADD_NODE 一类）**零校验**——复审 P2-7 定性为"仅客户端把守点"，与 K10（数量上限仅
客户端）同族。eval() 分派化后越类节点的求值表现依 eval 档而异（部分宿主静默空转，见
条目「时序/逻辑节点在 SeatInput 档宿主静默空转」）。

### 触发条件 / Trigger

任意会话成员用改包客户端发 ADD_NODE op；历史/外部存档携带超名单节点同理可见。

### 影响面 / Impact

无直接数据破坏；越类节点绕过分类边界纪律，在宿主图里产生菜单造不出的形态（求值静默
错误或行为异常），且是 K10 之外的第二个"仅客户端把守点"。

### 修复方向 / Fix direction

服务端 op 链按 `BlockNodeAllowances` 校验节点类型（ADD_NODE 及任何落类型的地方），超名单
按 REJECT 纪律拒绝；与 K10 数量上限的服务端化**同批做**——两个"仅客户端把守点"一次收口。

### 状态 / Status

**🔶 待办。** 2026-10-17 债务对表时发现账面悬空（波次表 / 延期表 / 本册三处皆无），
补登记（复审 P2-7）。

---

## 10. AST 与 RPN 双前端并行（旧表示退役时间点未定）

范围 / Scope：`FormulaParser`（RPN 旧前端本体与模式路由）、`FormulaAstParser`（AST 前端）、
`FormulaEval`（FORMULA case 的双路径分派）、`GraphNode.ensureScriptParsed`（唯一真相源缓存）

### 症状 / Symptom

（非用户可见缺陷，属结构性债。）同一份公式脚本存在两套可执行表示：无控制流/向量语法的脚本
（含旧档单行表达式）编译为扁平 RPN（assignments + outputRpns 的循环求值）；触发 AST 语法的脚本
解析为刀3 语句树（`FormulaInterpreter` 逐语句执行 + carrier 机制）。`FormulaEval` 的 FORMULA case
按 `script.ast != null` 在两套路径间分派，双路径都要随公式语义演进同步维护。

### 机制 / Mechanism

`FormulaParser.parseScript` 的模式检测决定产物形态：AST 触发词（控制流关键字/大括号/swizzle/
向量函数）走 `FormulaAstParser`，其余走逐行 RPN 前端；AST 模式下语句树的表达式叶子本身也是 RPN
（共享同一 Value 栈机求值），因此「旧前端」特指 parseScript 的逐行解析 + compile 直连形态及其
求值循环。拓扑审查 §三 P5（2026-10-25 落地，提交 `c1c742b`）已把两前端物理分家，删除旧路径的
改动面收窄到 FormulaParser 本体与 FormulaEval 的 else 分支，但删除本身是**行为变更**（存量
脚本的求值路径切换），须单独立项拍板，不在清理线自主范围。

### 修复方向 / Fix direction

先拍板退役时间点与迁移口径：是否把无控制流脚本统一升格 AST（或保持 legacy 快路径到某版本）、
旧扁平 RPN 前端保留到哪个版本、`isLegacy` 标记与旧档兼容如何退场；随后单批删除旧路径（物理
分离后删除面已收窄）。

### 状态 / Status

**🔶 待办。** 2026-10-25 拓扑 P5 拆分落地时按暂缓备忘记补登记：拆分解耦了退役决策（先物理
分离、决策后删一期即可），本条目只登记决策欠账，不阻塞任何在途批次。
