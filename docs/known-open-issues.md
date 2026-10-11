# 遗留问题登记 / Known Open Issues

> 更新日期 / Updated：2026-10-11
> 版本 / Version：1.2.6（WIP）
> 范围 / Scope：本文件登记**已定性、暂缓修复**的缺陷——每条含症状、机制、触发条件、修复方向与状态。新条目追加在汇总表与正文之后。
> 状态 / Status：🔶 待办 3 项（详见汇总表）；修复落地后把对应条目改为 ✅ 已解决并注明落地版本。
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
