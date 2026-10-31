# 遗留问题登记 / Known Open Issues
> 更新日期 / Updated：2026-10-31
> 版本 / Version：1.3.0（WIP）
> 范围 / Scope：本文件登记**已定性、暂缓修复**的缺陷——每条含症状、机制、触发条件、修复方向与状态。新条目追加在汇总表与正文之后。
> 状态 / Status：🔶 共 17 项（✅ 已实施 10 项〔6/8/9/10/11/12/13/14/16/17〕；已拍板待实施 1 项〔15 公开 API，波 1 批 D 已落地、剩 E 收尾〕；待办 6 项〔其中条目 1 待立项〕，详见汇总表）；修复落地后把对应条目改为 ✅ 已解决并注明落地版本。
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
| 6 | 像素编辑器配色半收编（static final 捕获 + 硬编码按钮/悬停） | 与 NBS 收编前同款病：panel_bg/panel_border 类加载时捕获、按钮/悬停写死，换主题不跟 | 照 NBS 收编的同款映射一次搬 | ✅ 已实施（2026-10-31，1012 绿；三件套四色映射 insetBg/hover/accent/error + 帧选中/拖拽高亮，画布内容色按口径保持内置，回潮锚 PixelEditorThemeAdoptionTest） |
| 7 | 频道清理残余豁口（ChannelOwner 无图域 + 客户端旗标/镜像清理） | 主图/子图节点 id 撞号时 owner 门失真；syncBusBands/空表 clearBus 靠旗标或镜像语义门控 | ChannelOwner 加图域全链带上 + 两处清理改 owner 门控 | 🔶 待办 |
| 8 | 时序/逻辑节点在 SeatInput 档宿主静默空转（op 注入/旧档形态） | Sensor/ControlSeat 的 4 参 evaluate() 对七类时序/状态节点无 case，输出逐拍冻结 | 档位合一或档外显式拒绝（行为变更） | 🔶 待办（2026-10-25 更新：eval 分派化已落地 main，怪癖按决策 §三-4 原样保留并收敛到单一事实源；**2026-10-31 拍板：档位合一**，✅ 已实施（2026-10-31 档位合一批 0e9cc29）） |
| 9 | 节点准入名单仅客户端把守（改包客户端可任意 ADD_NODE） | 服务端 op 链不校验 `BlockNodeAllowances`，改包客户端可把任意类型塞进任意宿主 | 服务端 op 链按准入名单校验，超名单 REJECT（与 K10 服务端化同批） | ✅ 已实施（2026-10-31 准入服务端化批：`graph.NodeAdmission` 准入门 + 装载普查旧档兼容口径，K10 数量上限同批收口；1027 绿） |
| 10 | AST 与 RPN 双前端并行（旧表示退役时间点未定） | 同一份公式有两套可执行表示并存于 FORMULA 求值（`script.ast` 空 → 旧扁平 RPN 循环；非空 → 刀3 解释器），维护面翻倍 | 单批删除旧路径 | 🔶 待办（拓扑 P5 拆分已把两前端物理分离，退役不再受阻；**2026-10-31 拍板：1.3.0 内退役**——无控制流脚本统一升格 AST，旧扁平 RPN 前端随批删除，✅ 已实施（2026-10-31 双前端退役批）） |
| 11 | 反向包边成环（blocks ↔ client.editor / network → blocks） | 包归位把同包互访显形为跨包 import，blocks 与 client.editor 互引成环、network 新增回引 blocks 边，与「blocks 回归世界侧」收口叙事反向 | 依赖倒置解环（Host 查询方法 / markDirty 形参收窄 / 窄接口，E2 `EditAckSink` 手法）或接受现状不立分层规则 | 🔶 待办（2026-10-31 补登记，拓扑批 4 注记⑥ 留痕边清单与根因；**2026-10-31 拍板：解环（依赖倒置）**，✅ 已实施（2026-10-31 解环批）） |
| 12 | 预算兜底未接线（deadline 兑底承诺未兑现） | `deadlineExhausted()` 全仓零调用点，FormulaCompute javadoc 却写着「deadline 仅作兜底（闭式巨脚本的紧急 shed）」——注释替未实现机制背书 | 接线；设置面板新增选项卡 + 两条指令（指令打开设置面板 / 指令直接调整），调整均需 OP，作用域 = 当前世界设置 | ✅ 已实施（2026-10-31，`e8a5ce4` 接线 + `01fcac3` 交互面，1010 绿；shed 语义=保持+升级冻结经追问拍板；审查修正 = 公平计数每 tick 一次 / 编辑清 streak / 控制台可调预算） |
| 13 | validate 双份 parseScript（同一脚本解析两遍） | `FormulaValidator.validate` 内部完整重跑 `parseScript`，校验路径重复解析 | 去重复用解析产物，单独立批（行为批口径） | ✅ 已实施（2026-10-31，1044 绿；validate 增复用预解析产物重载，三调用点接线，每次击键两次解析降为一次） |
| 14 | 显示器/HUD 每帧分配（图像墙 GC 抖动） | 3D 每像素 quad 无缓存、HUD 每不透明像素 `new float[8]`、恒渲染不进视锥剔除——图像墙场景顶点与分配线性叠加（复审 P1-4） | 只修分配（3D 按图代际缓存 mesh + HUD 像素四边形数组复用），**不碰恒渲染语义** | ✅ 已实施（2026-10-31，`d9c745a`，1044 绿；mesh 代际缓存 + 像素版本钩子 + HUD 复用缓冲，恒渲染语义原样） |
| 15 | 1.3.0 公开 API 暴露面（外部模组高精度联动） | 对外联动只有红石兜底 0–15 与动力网邻接两条精路；进程内总线与一批 public static 设施"恰好可达"但无契约 | **两大件**：自定义节点（完全自定义求值 + 可恢复契约与独立预算池 + 名单制准入）+ 宿主套件；节点直连提供者方块，外部频道层砍掉；排期 = 先清 4 小批 → P2-7 并入节点波 → 宿主套件波 | 🔶 待办（**2026-10-31 拍板落定 D1–D8**，决策记录见探索稿 §8，实施按探索稿 §9 波次） |
| 17 | 公开 API 批 D 承接：ITEM 编辑 UI 与 SET_CUSTOM_TEXT 文本帽 | ITEM 控件存储/op/K15 全通但无点击设置 UI；TEXT 写入服务端无显式帽（wire 32KB 全局帽在，但 32 控件×32KB 按节点聚合 + 撤销快照携带不受限） | 批 D：ITEM 编辑 UI 走 REDSTONE 槽位同款交互；TEXT 帽比照批 B blob 32KB 纪律（执行器拒收+节流告警） | ✅ 已实施（2026-11-01 批 D：`d42762a`+`cba36bf`，1108 绿） |
| 16 | ADD_NODE 快照恢复路径不校验内容（K15 钳制可被绕过） | 撤销恢复的 NBT 快照由客户端手工构造且无对账——改包客户端可借合法类型 + 手工快照绕过 op 级钳制 | 恢复路径补同款钳制 + 快照类型与 op.nodeType 一致（2026-10-31 拍板 = 方向①内容卫生 + 深度预扫描） | ✅ 已实施（2026-10-31，1055 绿；三道门 + 常量同源，11 锚 AddNodeSnapshotSanitizeTest；②opLog 对账经勘察否定，见条目实施记录） |

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

**✅ 已实施（2026-10-31，1012 绿 = 1010 + 2 回潮锚，本地待用户游戏内视觉验证后推送）。** static final 捕获半已随 1.3.0 的 EditorTheme 批根除（2026-10-25：三件套——含后来新增的 `PixelEditorFrameStrip`——捕获解冻为 `EditorTheme.get()` 实时取值，`NodeRendererPaletteFacadeTest` 锚死门面委托不回潮）；三硬编码色收编随 2026-10-31 拍板落地：**C_BTN→insetBg**（与设置面板非激活 tab 同源的内凹井色）、**C_HOVER→hover**、**C_SEL→accent**（NBS 收编同款）、**C_DEL→error**（FrameStrip 同名常量一并收），帧条选中帧高亮（原内联 `0xFF3A5A2A`）→accent、拖拽帧高亮（原内联 `0xFF7A4A3A`）→hover（与选中区分）；画布内容色（画布底/格线/透明度滑杆/文本）按条目口径保持内置；回潮由 `PixelEditorThemeAdoptionTest` 源码锚钉死（四旧字面量禁重现 + 三件套仍走 `EditorTheme.get()` 实时取值）。

---

**🎯 2026-10-31 拍板 / Ruling（仅登记，实施待排期）**：复用现有槽位收编——C_BTN/C_HOVER/C_SEL 映射进 EditorTheme 现有槽位（如 panelBg/insetBg、hover、accent 一类搭配），不新增专用槽位（四主题直接带值、配置格式不变）。/ Reuse existing theme slots for C_BTN/C_HOVER/C_SEL; no new dedicated slots.

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

**✅ 已修（2026-10-31 档位合一批）。** 2026-10-17 随 eval() 分派化决策文档定性登记；**2026-10-25 eval() 按 NodeCategory 分派化已落地 main**（三批：EvalContext 落位 → Formula/Timing/Motion 抽取 → 其余七处理器 + 分派翻转），本怪癖按决策 §三-4 **原样保留**（搬迁纪律 = 行为零变更，不顺手修）——现在怪癖收敛到单一事实源：`TimingEval.evalState` 承接七类（evalExt 档）、`TimingEval.eval` 只实现 ACCUMULATOR/INTEGRATOR（4 参档缺省 = 静默空转）、`FormulaEval` 无 GATE。修法本身（档位合一 vs 档外显式拒绝）是**行为变更**，待拍板后另批实施。

---

**🎯 2026-10-31 拍板 / Ruling（仅登记，实施待排期）**：档位合一——4 参档与全状态档合一，让七类时序/状态节点在 Sensor/ControlSeat 上真正工作。行为变更，实施须独立批次。/ Unify the 4-arg tier with the full-state tier so the seven timing/state types actually run on Sensor/ControlSeat; behaviour change, own batch.

**✅ 2026-10-31 实施完毕 / Implemented（档位合一批，提交 `0e9cc29`）**：4 参档与全状态档合一——`GraphEvaluator` 的 4 参 `evaluate(..., seat)` 重载收编进全状态路径（随调用弃置三表，无状态循环退役，七类一律经 evalExt 落 `TimingEval.evalState`）；`GraphHost.EvalVariant` 四档并三档（SEAT_STATELESS 常量删除），Sensor/ControlSeat 走 seat + `runtimeState` 三表（七类跨 tick 演进、NBT 随 `runtime` 键持久化，旧档携带此类节点即开始正确工作）；⑬ 触发器差分广播随批补挂（用户拍板随带，编辑区 LATCH/GATE/T_FLIPFLOP 实时状态镜像）。BASIC / EMPTY_TABLE 两档不变。行为锚 `SeatTierUnificationTest` 3 例（LATCH/GATE 4 参即时响应、DELAY 统一调用形态跨 tick 演进）+ 矩阵锚翻转与 SEAT_STATELESS 退役锁，998 绿。/ Implemented: the 4-arg overload now delegates to the full-state path (throwaway tables), EvalVariant collapses to three forms, Sensor/ControlSeat ride seat + runtimeState's three tables with the step-13 diff wired in (user scope call); BASIC/EMPTY_TABLE unchanged, 998 tests green.

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

**✅ 已实施（2026-10-31 准入服务端化批，1027 绿 = 1012 基线 + 15 新锚）。** 2026-10-17 债务对表时发现账面悬空（波次表 / 延期表 / 本册三处皆无），补登记（复审 P2-7）。

**✅ 2026-10-31 实施完毕 / Implemented（准入服务端化批，K10 同批收口）**：服务端 op 链在建点前过准入门 `graph/NodeAdmission`（纯函数 `violation(op, allowance, loadTimeTypes, targetGraph)`，`EditSessionRegistry.applyOp` 在子图解析后、建节点前调用，REJECT 走既有回执）——审 ADD_NODE / ADD_NODE_REQUEST 两个落类型 op，三条判定：① 类型可解码（线上 NodeType 按字符串 id 编码，未知 id 解码为 null 即拒，P2-9 同族口径）；② 类型获准入 = **现行名单 ∪ 装载普查**（旧档兼容口径按拍板取**装载时遗留集**：`GraphHost.loadHostNBT` 图替换点对图树（主图 + 递归封装子图）做一次类型普查，装载时已在场的类型本会话内永远可再进来、不随节点增删变化——撤销恢复（REMOVE_NODE 撤销的 ADD_NODE + 快照）必然放行，合法流零影响）；③ K10 数量上限 = 目标图节点数达 `NodeAdmission.MAX_NODES=1024` 拒（主图与封装子图各自计，与客户端同口径；`GraphEditor.MAX_NODES` 改为权威值别名，两侧永不漂移）。宿主→名单经 `EditSessionTarget` 窄口两问（`nodeAllowance()` 12 宿主各答自己的条目 / `loadTimeNodeTypes()` 转发引擎普查），名单缺席 fail-closed。客户端 REJECT 分发（`GraphRemoteApplier`）补 ADD_NODE 回滚分支——直发加点（撤销恢复 / 子图复制）首次可能被拒（仅 1024 满额边角），本地乐观节点随回执移除，REJECT 契约全路径铺满。回归锚 `NodeAdmissionGateTest`（15 例：门本体行为锚 + applyOp/GraphHost/回滚/委托四个接线源锚）。**节点波（公开 API D5 名单制）在本门上扩展字符串 id 名单**——引擎管事实（普查）、门管政策（算术）的分工即为波 1 预留的缝。

---

**🎯 2026-10-31 拍板 / Ruling（已实施）**：运行时服务端化——注意各版本整理的各个方块的节点筛选有差异，直接静态校验可能会导致有些旧版本的图用不了；服务端化以运行时校验形态做，须带旧档兼容口径（用户原话约束）。实施时遗留集机制经追问拍板 = **装载时遗留集**（装载后算一次「在场类型 − 现行名单」，会话内永久放行；否决了 op 时在场判定——那会在删光后撤销时丢节点）。/ Runtime server-side validation with a load-time legacy census (ruled over op-time live presence, which would lose nodes on undo after deleting the last instance).

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

**✅ 2026-10-31 实施完毕 / Implemented（双前端退役批）**：旧扁平 RPN 前端单批删除——`parseScript` 的模式路由/旧单行表达式模式/逐行赋值输出解析全部退役，统一走 `parseScriptAst`（表达式叶子仍是共享 Value 栈机 RPN，`compile`/`evaluateValue` 等栈机入口留守服务叶子与 DEBUG 信号）；`FormulaEval` 的 RPN 求值循环删除（`ast==null` 仅剩深度守卫降级形态 = 输出恒 0 + ERROR 徽章 + 去重照记）；`Assignment`/`assignments`/`isLegacy`/`extractVariables`（连测试）随批退场，`ScriptParseResult` 收缩。**旧档兼容口径设计**：① 持久化只存 formula 源文本 + 引脚计数（无编译产物入档），旧档加载（含 GraphMigration 世界加载）即经统一前端重编译，零存档迁移；② 值与引脚面等价——AST 前端设计即对齐旧规则（默认输出=顶层末行独立表达式、赋值序执行、输出后算、inputVars=引用−赋名，parseScriptAst 内两处「与旧模式一致」注记），`FormulaUnifiedFrontendTest` 7 锚钉住旧单行/多行赋值/@output/多输出形态的逐值等价与 extractVariables 引脚面口径（sin(PI)/裸 PI/E=引脚、(PI)/(E)=字面量）；③ 连线按 pinId 名绑定（v1.2.4），变量顺序差异不改连线语义；④ 旧版额外引脚（dynamicInputCount>inputVars 的数字名引脚）口径不动；⑤ 校验口径变化（设计内接受）：原旧路径脚本现在会过类型感知校验（编辑器徽章面，运行时仍 lenient）；⑥ 逐语句错误隔离随旧前端退役（设计内接受）：旧前端逐行 catch（坏赋值→该变量=0、坏输出→该输出=0、其余照常）不再有——升格后坏叶子 RPN 逃到 eval 分支 catch → 整节点输出清零 + 编辑器 ERROR 徽章（原 AST 控制流脚本本就此语义）；影响面仅旧档里**本来就坏的**非控制流脚本，行为从「局部 0 其余工作」变「整节点 0 + 徽章」——坏脚本本就该修，徽章面反而更醒目。1001 绿（1000+7 新锚−6 退役旧锚）。/ Implemented: the flat-RPN frontends are deleted in one batch; legacy-save compatibility = source-text persistence with zero save migration, value/pin-face equivalence pinned by FormulaUnifiedFrontendTest, name-bound connections, legacy extra-pin shapes untouched; former legacy-path scripts now get type-aware validation (editor badges only, by design), and per-statement error isolation retires with the old frontend (a broken leaf now zeroes the whole node with an ERROR badge instead of zeroing one statement — affects only already-broken legacy scripts, by design).

**🎯 2026-10-31 拍板 / Ruling（仅登记，实施待排期）**：1.3.0 内退役——无控制流脚本统一升格 AST，旧扁平 RPN 前端单批删除（P5 物理分离后删除面已收窄）。实施含旧档兼容口径设计。/ Retire within 1.3.0: unify all non-control-flow scripts onto the AST frontend and delete the legacy flat-RPN path in one batch.

## 11. 反向包边成环（blocks ↔ client.editor / network → blocks）

范围 / Scope：`client.editor/GraphEditor`（三处 `host instanceof BlueprintScreen`、一处 `be instanceof GraphBlockEntity`）、
`network/EditSessionRegistry`（`markDirty`/`getBlockEntity` 对 `GraphBlockEntity`、一处 `instanceof MonitorBlockEntity`）；
根因与边清单见拓扑评审 §五批 4 注记⑥（2026-10-31 登记）。

### 症状 / Symptom

（非用户可见缺陷，属结构性债。）拓扑批 4 包归位（`e831c94`/`5122149`）把编辑器家族迁入 `client.editor/`、
`EditSessionRegistry` 迁入 `network/` 后，包图上出现反向边：`client.editor → blocks`（GraphEditor 回引
BlueprintScreen/GraphBlockEntity）与既有的 `blocks → client.editor`（宿主屏继承 AbstractGraphScreen、调用编辑器）
互为镜像，blocks ↔ client.editor **成环**；`network → blocks` 亦为新增边。方向与「blocks 回归世界侧」的收口叙事相反，
将来若立「client 不得回引 blocks」类分层规则，此环是第一个违规点。

### 机制 / Mechanism

耦合不是搬包新造的，是搬包**显形**的：搬迁前这些全是 blocks 包内裸名互访（零 import、零包边），搬后成为跨包 import。
四处实义耦合各有语义理由——
① 编辑器按宿主屏具体类型分叉布局（蓝图机顶栏 326px 与按钮命中区异于常规 250px）；
② GraphEditor 经 `GraphBlockEntity` 接口读封装子图 flipflop 状态渲染徽标；
③ `EditSessionRegistry.markDirty` 落盘要 BE——但形参 `GraphBlockEntity` 纯属名义（方法体第一行即上转
`BlockEntity` 调 `setChanged()`）；④ 显示拖拽流 20Hz op 的限流全量同步要认出 `MonitorBlockEntity`。
环两侧各自都有留守的正当理由：宿主屏按「不给每个宿主立子包（纵切片同包内聚优先）」必须留 blocks，
编辑器又必须知道宿主几何与宿主 BE 类型——除非宿主屏一并迁走（撕裂纵切片内聚），否则环在包图层面不可消。

### 修复方向 / Fix direction

依赖倒置（同 E2 `EditAckSink` 的缝-实现分离手法）：① 三处 `instanceof BlueprintScreen` 改为问
`GraphEditor.Host` 接口查询方法（如 `toolbarWidth()`——该缝现成，宿主屏本就实现 Host）；② `markDirty` 形参
收窄为 `BlockEntity`（一行事，类型本就多余）；③ `MonitorBlockEntity` 那处收成「请求全量同步」窄接口。
或接受现状——但「client 不得回引 blocks」类分层规则随之不可立。两条路均为代码/结构变更，
按红线单独立项，不在清理线自主范围。**待拍板**：解环 vs 接受现状（含是否立分层规则）。

### 状态 / Status

**✅ 已修（2026-10-31 解环批）。** 2026-10-31 批 4 收账时按暂缓备忘记补登记：拓扑批 4 注记⑥已留痕边清单与根因，
本条目登记决策欠账，不阻塞任何在途批次。

**🎯 2026-10-31 拍板 / Ruling（仅登记，实施待排期）**：解环（依赖倒置）——按 E2 EditAckSink 手法：GraphEditor.Host 查询方法（缝现成）/ markDirty 形参收窄为 BlockEntity / MonitorBlockEntity 窄接口。解环后「client 不得回引 blocks」类分层规则可立。/ Break the cycle via dependency inversion (the E2 EditAckSink playbook).

**✅ 2026-10-31 实施完毕 / Implemented（解环批）**：四缝全量倒置——① `GraphEditor.Host` 增能力查询（`toolbarWidth` 默认 250/蓝图 326、`supportsEncapImportExport`、`peekSubStateFlipflops`），GraphEditor 三处 `instanceof BlueprintScreen` 与子图徽标的 level 查找全部换查询（子图徽标改宿主屏答自其 BE）；② `AbstractGraphScreen.getBE()` 收窄为 `EditStateView` 窄视图、`GraphRemoteApplier` 的 `instanceof` 同换（pending-ops 记账面）；③ Monitor 四面板（DisplayEditor/SettingsPanel/LayerPanel/Canvas）经 `MonitorBeView` 窄视图（几何/图/求值快照/HUD 模式/运行态）；④ `EditSessionRegistry` 经 `EditSessionTarget` 窄口（图访问/applyCurrentState/requestFullSync/setChanged）+ `markDirty` 形参收窄（直调 `setChanged()`）+ `CoalescedFullSync` 窄口（Monitor 限流全量同步，`instanceof` 具体类型退役）。**范围注记**：条目 scope 只点名 GraphEditor/EditSessionRegistry 七处，但环要真消须 client.editor 全线回引归零——`AbstractGraphScreen`/`GraphRemoteApplier`/Monitor 面板的存量边同批倒置（同手法自然延伸），机制段「环在包图层面不可消」的悲观结论由此推翻。**跨类加载器约束发现**：窄口签名含图域类型（NodeGraph/EvalSnapshot）者（`EditSessionTarget`/`MonitorBeView`）须住 `graph/` 复制域——与 `blocks.GraphBlockEntity` 同处 Sable 子优先复制域；住共享包会让共享接口签名跨加载器域引用被复制类型，触发 JVM loader constraint（SableDeviceNameCrossLoaderTest 首爆，修法=窄口随复制域）。`EditStateView`/`CoalescedFullSync` 签名全为共享类型，住消费方包无碍。client/editor 与 EditSessionRegistry 回引 blocks **全归零**，`PackageEdgeDirectionTest` 方向锁钉住；「client.editor 不得回引 blocks」分层规则已立（锁测试即规则）。1000 绿（998+2 锁）。/ Implemented: all four seams inverted via host capability queries, EditStateView/MonitorBeView/EditSessionTarget/CoalescedFullSync narrow ports and the markDirty param narrowing; the scope extended to the four residual editor files so the cycle genuinely breaks; graph-domain ports stay in the Sable duplication domain (loader-constraint finding, surfaced by SableDeviceNameCrossLoaderTest); zero reverse edges with a direction-lock test; 1000 tests green.

## 12. 预算兜底未接线（deadline 兑底承诺未兑现）

范围 / Scope：`graph/FormulaCompute`（`deadlineExhausted()` 定义处，全仓零调用点）、公式求值预算链路；
源头 cross-be 债务审计 9.2-1「预算兜底未接线」。

### 症状 / Symptom

`deadlineExhausted()` 全仓零调用点——预算 deadline 只设定不消费；但 `FormulaCompute` 的 javadoc 写着
「deadline 仅作兜底（闭式巨脚本的紧急 shed）」，代码注释在替未实现机制背书，「文档承诺 > 实现」从 docs
蔓延到了源码注释。

### 机制 / Mechanism

刀 1/3/5 的公式预算机制里，deadline 在 tick 期初复位、但求值循环边界从不查询——闭式巨脚本（单表达式
巨量展开、无循环边界可 yield）没有紧急 shed 兜底，只有协作式 slice yield 一条路。javadoc 的承诺路径
（超预算紧急 shed）物理上不存在。

### 修复方向 / Fix direction（2026-10-31 拍板，用户原话约束）

**接线**（不走文档降级）。交互面与权限约束：
① 设置面板新增**选项卡**；
② **调用指令**两条——一条指令打开设置面板、一条指令直接调整；
③ 调整**均需 OP**；
④ 作用域 = **当前世界设置**（非全局）。

### 状态 / Status

**✅ 已实施（2026-10-31，`e8a5ce4` 接线 + `01fcac3` 交互面，1010 绿 = 1001 基线 + 9 新锚，本地待
用户游戏内验证后推送）。** 实施记录：采样 = 表达式栈机**调用内局部计数**每 256 ops（共享计数器
形态曾让测试类间静态状态泄漏误伤——BallisticSolverTest/FormulaParserSyntaxTest 首爆——故取局部口径，
微小表达式 ≤256 ops 永不采样）；shed 语义经追问拍板 = **保持 + 升级冻结**（解释器转 fromDeadline
挂起：carrier 保住、输出保持 lastGood、不计 yield；公平规则 = 仅节点自身超份额才计连续超时，20 tick
冻结走 MAX_ITER 同款告警一次；**冻结与冻结时的预算值绑定——预算调大下一 tick 自动解冻重试（装不下
再重新冻结并记录新值；MAX_ITER 冻结按迭代数裁定、不参与解冻）**；聚合压力受害者只保持）；FORMULA 输出阶段撞限保持该输出并作废 done
全量重试；DEBUG 试算/曲线瞬态豁免。交互面四约束全数落地：设置面板「公式预算」选项卡（OP 步进
±0.5ms/应用/恢复默认，非 OP 只读）+ `/cscsettings`（打开面板直达预算 tab）+ `/cscbudget <ms>`（直接
调整）均需 OP + 作用域 = 当前世界 SERVER 配置（登录同步 + 运行期广播刷新）。
审查修正（2026-10-31 会话内复审，`f17e01a` 拆分 + `4e591f8` 修 + 本笔文档账）：公平规则处置在
输出阶段**每 tick 恰计一次**（逐输出累加会让 N 输出节点 ⌈20/N⌉ tick 冻结，偏离「连续 20 tick」拍板，
双巨输出锚钉住）；编辑公式随其余挂起态一并清 `formulaDeadlineStreak`（编辑是冻结的补救之一）；
`/cscbudget` 放开控制台（拍板只要求 OP 未要求限玩家，`requires` 按权限等级把门）；
二轮实测口径（2026-10-31）：`/cscsettings` **放开全员**、默认第一选项卡（开面板是查看面，调整仍
各自把关），输入框与按钮同高对齐、回车后框内文本吸附钳制值、说明文本标注 0.5~20 ms 范围；解冻批
（原 a8bf462，按「文档与代码分开」拆为 `f17e01a` 代码 + `6f76b33` 台账）。

## 13. validate 双份 parseScript（同一脚本解析两遍）

范围 / Scope：`graph/FormulaValidator.validate`、`graph/FormulaParser.parseScript`；复审 P3 定性，
拓扑 P5 拆分时按「行为/结构不同批」红线未顺路修。

### 症状 / Symptom

`validate` 内部完整重跑一遍 `parseScript`——同一脚本在校验路径解析两遍，解析成本翻倍。

### 机制 / Mechanism

校验入口与解析入口各自完整解析：validate 先自己跑 parseScript 拿产物做启发式检查，调用方随后再解析
一次拿可用产物。P5 拆分后两者已物理分家（FormulaValidator / FormulaAstParser），去重的改动面已清晰，
但去重本身是**行为批**口径，须单独立批。

### 修复方向 / Fix direction（2026-10-31 拍板）

去重复用解析产物，**单独立批**（行为批口径，不与其他变更混批）。

### 状态 / Status

**✅ 已实施（2026-10-31 去重批，`68ef4a3`，1044 绿）。** 实施记录：validate 增**复用预解析产物**的重载
（门面增量重载、签名零破坏）——产物 `sourceFormula` 与 sanitize 后源一致时组 13 直接复用、不完整重跑；
不匹配/缺席回退内部解析，两形行为等价（parseScript 确定性）。三调用点接线：击键响应器（第 478 行解析
产物直喂校验）、面板初始化路径、徽章渲染路径（复用节点 `cachedScript` 同文本缓存）——每次击键由两次
完整解析降为一次。等价锚 `FormulaValidateDedupTest`（干净/坏/控制流三形态徽章面逐条相同 + 陈旧产物
回退 + 接线源锚）。

## 14. 显示器/HUD 每帧分配（图像墙 GC 抖动）

范围 / Scope：`client/renderer/MonitorBlockEntityRenderer#render`、`client/renderer/HudRenderer#renderHud`；
复审 P1-4（2026-10-25 安全与性能审查，二次核验通过）。

### 症状 / Symptom

3D 模式每图像每帧对非透明像素逐个 `addVertex×4`（32×32 ≈ 1024 quad/帧/显示器），内容不变也重发；
HUD 模式每不透明像素 `new float[8]` + 6 顶点三角形扇；叠加 `shouldRenderOffScreen()=true`（显示器恒渲染、
不进视锥剔除）——图像墙场景（N 显示器 × M 图像）顶点数与分配线性叠加，HUD 模式每帧数万 float[8] 造成
GC 抖动。

### 机制 / Mechanism

3D 路径几何只依赖 BE 参数 + 快照值，可按代际缓存 mesh；HUD 路径的每像素四边形数组可复用；
恒渲染语义是否必要属另一议题（本条不碰）。

### 修复方向 / Fix direction（2026-10-31 拍板）

**只修分配**：3D 路径按图代际缓存 mesh + HUD 路径像素四边形数组复用（消灭每像素 `new float[8]`）。
**不碰恒渲染语义**（`shouldRenderOffScreen` 行为保持原样，视锥剔除复核不在本批）。

### 状态 / Status

**✅ 已实施（2026-10-31 分配治理批，`d9c745a`，1044 绿）。** 实施记录：3D 半边 = 逐像素顶点流按
**图代际 ∨ 像素引用 ∨ 像素版本 ∨ 画布尺寸 ∨ 显示缩放** 缓存（纯函数 `MonitorClipMath.buildImageMesh`
一次性烘焙，绕序/颜色/发射顺序与原逐帧扫描逐字节一致；WeakHashMap 按 BE 持有、卸载随 GC 无泄漏），
逐帧仅发射缓存顶点、信号偏移/旋转/位置仍在矩阵里——**恒渲染语义原样**。**像素版本钩子**：
`GraphNode.imagePixelsVersion`（transient）——原位绘画路径（PixelEditorKernel 的 paintBrush/floodFill，
绘画既不 bump 图代际也不换数组引用）必须递增，否则缓存在画画时显示陈旧内容；换引用路径（op 应用/
undo/redo/切帧）由引用比较覆盖。HUD 半边 = 遮罩 4 边形与像素 quad 两块 renderHud 局部缓冲逐图/逐像素
重填复用（每不透明像素一个 `float[8]` 的主项消灭；两个裁剪函数均只读输入、返回新数组，复用安全）。
同族遗留：`emitTextGlyph` 的每字形 float[8] 量级小一个数量级、未随批（登记留痕）。锚
`MonitorImageMeshTest`（烘焙行为/退化输入 + 版本钩子 + 接线源锚）。

## 15. 1.3.0 公开 API 暴露面（外部模组高精度联动）

范围 / Scope：新增 `api` 包（`io.github.y15173334444.create_schematic_compute.api`）与
`network/SignalBus` 的外部 owner 扩展；**不引入任何图域类型签名**（跨加载器窄口教训的结构性要求）。

### 症状 / Symptom

其他模组与本模组联动今天只有两条精路：Create 红石兜底（整数 0–15）与动力网邻接；进程内总线
（float32 主力通道）与一批 public static 内部设施"恰好可达"，但无契约、无稳定性承诺——即
`cross-be-latency-observation.md` §7.8 定性的"对世界耦合面窄"，其正面解法在 §十一开放问题 15 悬置。

### 机制 / Mechanism

`docs/1.3.0-public-api-exploration.md`（2026-10-31 探索稿）已核实：候选暴露面五层
（L1 总线级信号桥 / L2 节点级第三方 I/O / L3 执行传感接口 / L4 图编辑 / L5 静态点官方化）、
运行时硬约束十条、语义承诺草案与八个决策点（D1–D8）。建议第一刀 = **L1**：外部频道发布/订阅，
复用 `SignalBus` 的 owner 门控、频段注册、BUS_IN 订阅与频段同步全套机制，编辑器零改动。

### 修复方向 / Fix direction（2026-10-31 拍板）

**两大件**（决策记录见探索稿 §8）：**自定义节点**（完全自定义求值逻辑；动态注册表与 `NodeType`
枚举共存；可恢复契约 + 独立重节点预算池——与 FORMULA `reportYield()` 机制同构；声明式 schema +
通用渲染器；名单制准入与 P2-7 服务端化合流）+ **宿主套件**（第三方 BE 接入 `GraphHost` 骨架 +
本模组编辑器）。值交换主路径 = 节点直连提供者自己的方块（时序归本模组求值循环）；总线/私有保持
玩家面，**外部频道层（原探索稿 L1）砍掉**。API 签名零图域类型（跨加载器教训）。

### 状态 / Status

**🔶 待办（2026-10-31 探索稿完成 + D1–D8 拍板落定）。** 实施按探索稿 §9 波次：先清 4 个独立
小批（K6/像素三色/validate/HUD）→ 自定义节点波（含 P2-7 合流）→ 宿主套件波；21 条回归发布前统一跑。

## 16. ADD_NODE 快照恢复路径不校验内容（K15 钳制可被绕过）

范围 / Scope：`graph/OpExecutor`（ADD_NODE case 的 `restoreNodeFromNbt` 通路）、K15 钳制族
（`GraphEditOpPacket.opBoundsViolation` 预检）

### 症状 / Symptom

（非用户可见缺陷，属安全加固缺口。）改包客户端可发一条 ADD_NODE op，stringValue 以 `{` 开头
即被当作 REMOVE_NODE 撤销的恢复快照，执行器直接 parseTag 并 `restoreNodeFromNbt`——全程
不校验快照内容；K15 预检只覆盖 SET_HOTBAR_ITEM / SET_IMAGE_PIXELS / SET_CTRL_POINTS 三个
op 类型，快照恢复路径绕过全部 op 级钳制。

### 机制 / Mechanism

撤销恢复协议：客户端撤销 REMOVE_NODE 时重发 ADD_NODE（targetNodeId=原 id）+
stringValue=NBT 快照，服务端从快照恢复完整节点数据。快照由客户端构造、协议无签名无对账，
而 op 级预检对 ADD_NODE 家族无内容检查——超大控制点表 / 超限帧列表 / 越界热栏槽位等可经
快照直达执行器。P2-7 准入门（2026-10-31 已落地）管**类型准入**，管不了快照**内容**：快照里
的类型是合法类型时，超限内容照常夹带。

### 触发条件 / Trigger

改包客户端发一条带手工构造快照的 ADD_NODE op。正常客户端的快照由 GraphNode.save 产生，
内容本就经过 op 级纪律，不受影响。

### 影响面 / Impact

无直接崩溃路径（NBT 解析失败静默跳过、节点空壳）；成功注入时绕过 K15 钳制造成超大结构
（内存 / tick 压力）与未预期节点内容。

### 修复方向 / Fix direction

两条路择一：① 恢复路径补与 op 级同值的钳制（控制点 ≤64 / 帧 ≤64 / 热栏槽 0..8 等）+ 快照
类型必须与 op.nodeType 一致（内容卫生，简单）；② opLog 对账——快照仅在对得上会话日志中
对应 REMOVE_NODE 记录时接受（最严，但耦合 opLog 上界轮转与跨会话撤销不可用性）。

### 状态 / Status

✅ 已实施（2026-10-31，提交 `60ded8d`，1055 绿）。

**拍板记录**：处置方案 = **修复方向①（executor 侧内容卫生）+ 深度预扫描**（2026-10-31 本
会话拍板）。方向②（opLog 对账）经实施前勘察**否定**，勘误存档防复发：opLog（P2-10b，200
条 FIFO、零读取方）里的 REMOVE_NODE 记录**不含快照**（快照只存在于客户端撤销时重发的
ADD_NODE 上），「对得上」只能比 targetNodeId 等弱字段——改包客户端先真删一个自己刚建的
节点（opLog 合法记账）再拿伪造快照撤销它，对账照样通过，**②按原文写法是无效防线**；要真
防住必须升级为「服务端在 REMOVE_NODE 时自存权威快照」（协议重做批：每条删除账本内存翻倍、
撤销深度跨端契约、opLog 内存态重启清空 → 跨会话撤销报废），成本与收益严重倒挂，不采。

**落地内容**（`OpExecutor` ADD_NODE case 自审三道门，任一门不过即跳过恢复留空壳，与 NBT
解析失败同口径）：① NBT 深度预扫描 `snapshotNbtDepthExceeds`（引号感知——字符串值内的
花括号是内容不是结构，误计会错杀含字面花括号的合法注释/公式快照；上限
`MAX_SNAPSHOT_DEPTH=64`，防 32KB 深链直达 TagParser 打出 catch (Exception) 接不住的
StackOverflowError）；② 类型一致门（快照必须含 `type` 键且与 op.nodeType 一致——
GraphNode.save 必写 type，合法客户端零成本通过）；③ 内容钳制（控制点对 ≤
`GraphOp.MAX_CTRL_POINTS`、帧 ≤ `MAX_SEQUENCE_FRAMES`、频段条数 ≤ 新常量
`OpExecutor.MAX_BANDS`——线缆解码侧的字面量 64 同步收编为该常量，两侧同源防漂移）。
顺带修：控制点恢复循环原以 dcx.length 为界直取 dcy[i]，两数组长度不对称的畸形快照会
AIOOBE 落入外层 catch——现按对数取 min。同源结构锁与三道门行为锚 = `AddNodeSnapshotSanitizeTest`
（11 锚：逐门行为 + 合法快照 round-trip 零损 + 解码/恢复同源锁）。

## 17. 公开 API 批 D 承接：ITEM 编辑 UI 与 SET_CUSTOM_TEXT 文本帽

范围 / Scope：`client/editor`（物品槽交互）、`graph/OpExecutor`（TEXT 帽）

### 症状 / Symptom

（非用户可见缺陷，属分期追踪。）① ITEM 控件：存储面（构造期 itemParams 预分配）、op 面
（SET_HOTBAR_ITEM CUSTOM 形态）与 K15 静态界（MAX_SCHEMA_PARAMS）均已随批 C 落地，但编辑器
没有点击设置物品的 UI——玩家无法给自定义节点的 ITEM 控件赋值（验收①闭环不依赖：示例模组
schema 不用 ITEM）。② TEXT 写入：服务端无显式文本帽——wire 层 readUtf 的全局 32KB 帽在，
但 32 控件 × 32KB 按节点聚合无界，且撤销快照携带不受限，与批 B 状态 blob 的 32KB 帽纪律
不对称（审查批 (a)2）。

### 修复方向 / Fix direction

批 D 一并承接：① ITEM 编辑 UI 走 REDSTONE 频率槽同款交互（选中槽位 → 热栏点击赋值）；
② TEXT 帽比照批 B blob 纪律——执行器按 SNBT 长度拒收 + 节流告警（fail-closed，不写入）。

### 状态 / Status

✅ 已实施（2026-11-01，批 D：代码 `d42762a` + `cba36bf`，1108 绿）。

**落地内容**：① ITEM 编辑 UI = REDSTONE 频率槽同款交互——EditPanel 每个 ITEM 控件一行
18px 槽位（标签 + 槽框，选中高亮 ACC、物品渲染深度屏蔽同 REDSTONE 纪律），GraphEditor
点击段选中（`freqSlotSelected` = itemParams 序位）+ 热栏弹窗赋值（既有 `SET_HOTBAR_ITEM`
通路，服务端按批 C 落地的 CUSTOM itemParams 界校验）；三方几何账本（EditPanel 绘制 /
点击段 / `SpatialIndex.editHeight`）同行进 `items * 18`（e26bf73 全链清点纪律）；热栏弹窗
矩形收敛单源 `hotbarPopupRect()`（渲染与点击各算一份 = 559202d 几何漂移同族陷阱；CUSTOM
弹窗落位走 expandedEditHeight 实测口径）。② TEXT 帽双层：包层 per-control
`api.CustomNodeType.MAX_TEXT_PARAM_LENGTH`(32768) 走 K15 预检 REJECT（客户端回滚，不再
依赖线缆 readUtf 静默截断出「写进去了但被剪」的错文本）+ 60s 墙钟节流 WARN（P2-11/K21
纪律）；执行器 per-node 聚合帽 `OpExecutor.MAX_TEXT_TOTAL_PER_NODE`(32768/节点，比照批 B
状态 blob 纪律) 替换记账拒收——聚合只能住执行器（包层预检看不到节点现有文本），超帽客户端
写入静默不生效（K15 纵深防御同口径）。锚 = `CustomNodeEditorFaceTest` +2（⑧菜单名单同源
+ 宿主键注入链；⑨ITEM 槽三方账本 + 弹窗单源）+ `CustomNodeTextCapTest` 3 条（per-control
边界 / 聚合替换记账 / 接线源锚）。


