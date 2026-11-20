# C→S 包洪水治理：拖动临场流 + 丢弃回执 · 方案 / C→S packet-flood remediation: drag presence stream + drop receipts

> **目标**：把「图内多节点拖动 / 高频操作 → 触发服务端限流断开」修到机制上不再可能，
> 并把限流拒绝的静默丢包从「无声丢编辑」改为「客户端可见、状态收敛」。
> **Goal**: make "multi-node drag / frequent ops → rate-limit disconnect" mechanically
> impossible, and turn silent rate-limit drops into visible, state-converging receipts.
>
> **状态**：📋 待拍板（决策点见 §六；本文档先于实现入库）。
> **Status**: pending sign-off (decision points in §6; this doc lands before any code).
>
> **基线**：`csc-packet-flood` 分支（基于 main @ acf0f5ee），诊断期 2026-11-21。
> **Baseline**: branch `csc-packet-flood` (off main @ acf0f5ee), diagnosed 2026-11-21.

---

## 一、问题定性 / Problem statement

踢人文案来自模组自身的 `C2SPacketRateGuard` 令牌桶（issue #24 §4.4 第 1 层，由
`AllPackets` 注册面统一包装），而非原版。**原版 1.21.1 对自定义负载零限流**——对 joined
jar 反汇编实证：`ServerCommonPacketListenerImpl` 常量池只有 keepalive / 资源包字符串，
`ServerGamePacketListenerImpl` 只有移动/聊天专用的 `disconnect.spam`。因此本模组的预算表
是唯一闸门，任何「桶标定 × 流量形态」错配都会直接变成踢人。
/The kick comes from our own `C2SPacketRateGuard` token buckets, not vanilla. Vanilla
1.21.1 rate-limits nothing on custom payloads (verified by disassembling the joined jar);
our budget table is the only gate, so any mismatch between bucket calibration and traffic
shape turns directly into a disconnect.

洪水源 = 拖动被翻译成**逐节点、逐 50ms 窗口的独立编辑 op 包**：
/The flood source: drags are translated into per-node, per-50ms-window individual edit ops:

| 拖动路径 / drag path | 每窗口发送量 / sends per window |
| --- | --- |
| 多选拖动 `handleMultiDrag` | 每个选中节点各 1 个 MOVE op / one MOVE op per selected node |
| 注释框拖动 `handleCommentParentMove` | 1 + 框内节点数 + 被撞开节点数 / 1 + contained + pushed-aside nodes |
| 注释尺寸调整 `handleCommentResize` | 1 个 SET_COMMENT_SIZE op / one SET_COMMENT_SIZE op |
| 单节点拖动 `handleNodeDrag` | 1 个 MOVE op / one MOVE op |

`DRAG_SEND_INTERVAL_MS = 50` 是墙钟闸而非合并队列——窗口到点仍按节点数逐个发包。
/It is a wall-clock gate, not a merge queue: at each window tick it still sends one packet
per node.

EDIT_OP 桶按「松手一次性突发」标定（burst 1100 ≈ 1024 节点图上限每节点 1 op + 余量；
refill 8/s ≈ 人类离散操作节奏），而拖动是**持续乘法负载**：多选拖 N 节点 = 20N 包/s，
N≈55 时一秒拖空桶，之后拒绝流持续约 1.1–1.3 秒即断开；单节点持续拖约 92 秒后同样进踢。
/The EDIT_OP bucket is calibrated for a one-shot release burst (burst 1100), but a drag is
a sustained multiplicative load: 20N packets/s for N selected nodes — N≈55 empties the
bucket in one second, and the sustained rejection stream disconnects ~1.1–1.3 s later; a
continuous single-node drag crosses the line after ~92 s.

**次生灾害（比断开更隐蔽）**：被限流拒绝的包在 handler 之前静默丢弃、无任何回执 →
客户端 `pendingLocalOps` 只增不减（唯一减点 = 收到 ACK）→ 回弹保护
`isGraphReplaceBlocked` 闩死，编辑会话内服务端全量同步全被挡；被丢 op 服务端从未应用，
重开界面 join 全量快照覆盖本地后，这批编辑**无声消失**。屏幕关闭时的复位是唯一愈合点。
/Secondary damage (stealthier than the kick): rejected packets are dropped before any
handler with no receipt at all → `pendingLocalOps` leaks (only ACKs decrement it) → the
bounce-back protection latches and blocks every full sync for the session; dropped ops
were never applied server-side, so after reopening (join full snapshot), those edits
*silently vanish*. The screen-close reset is the only heal point.

已排除的嫌疑：显示类 op 的全量 NBT 广播已有 40-tick 合并（`CoalescedFullSync`）；
presence / 座椅输入 / 总线名防抖各自预算恒满不拒。服务端侧既有缓解健康，问题集中在
op 包数量本身。
/Ruled out: display-op full NBT broadcast is already coalesced (40-tick); presence / seat
input / bus-name debounce each have budgets that never reject. Server-side mitigation is
healthy; the problem is the op packet count itself.

## 二、方案总览 / Overview

两条互补机制，独立可分批落地：
/Two complementary mechanisms, independently landable:

- **A 拖动临场流（治本）**：拖动期间的位置/尺寸更新不再走编辑 op，改走一条 presence
  同款形态的临场流——客户端节流、服务端只中继不落图、编辑者侧渲染覆盖层。拖动流量从
  「20N 包/s 的 op 风暴」塌缩到「~10 包/s 的一条流」，服务端广播放大与逐包磁盘写同步
  消失；松手时由既有 finish* 路径发持久 op（提交语义变为显式「松手即提交」）。
  /A drag presence stream (root cause): transient drag state leaves the op channel and
  rides a presence-shaped stream — client-throttled, server relays without touching the
  authoritative graph, editors render it as an overlay. Drag traffic collapses from a
  20N-packets/s op storm to one ~10-packets/s stream; commit semantics become explicit
  "commit on release" via the existing finish* paths.
- **D 丢弃回执（治次生）**：编辑 op 携带客户端序号；限流拒绝时服务端回一条丢弃回执，
  客户端按既有 REJECT 契约收敛 `pendingLocalOps` / 回滚乐观态 / 终止 deferredOps 挂起。
  静默丢失从「闩死会话」变成「与 REJECT 同路径的一等公民」。
  /D drop receipts (secondary chain): edit ops carry a client sequence number; on a
  rate-limit drop the server answers with a receipt and the client runs the existing
  REJECT contract — pending counter converges, optimistic state rolls back, deferred
  temp-id waits terminate.

不取的备选 / Rejected alternatives:
- **B 客户端出站漏斗**（全类型队列 + 按回填节奏 flush）：覆盖面最全但改动面最大，且
  必须过 exactly-once / 骤停重发验证（音频面事件批的教训）。A 把主病灶移出 op 通道后，
  B 不再必要。
  /A client-side outbound funnel is the heaviest option and needs exactly-once / crash-
  resend verification; once A moves the main offender out of the op channel it is moot.
- **C 仅上调 EDIT_OP 预算**：治标——乘法风暴与「每 op × 编辑者数」的广播放大仍在，
  且与桶的「人类节奏」标定哲学冲突。
  /Raising the EDIT_OP budget alone treats the symptom; the multiplicative storm and the
  per-op × per-editor broadcast amplification remain.

## 三、方案 A：拖动临场流 / Drag presence stream

### 3.1 包形态 / Packet shapes

- C→S `GraphDragStreamPacket`：graphPos + ownerNodeId + 条目列表。条目 =
  { nodeId, kind, x, y, w, h }，kind ∈ { NODE_POS, COMMENT_SIZE }（kind 决定哪些字段
  有效）。单包条目上限 512（对齐 BlobDataPacket 分片尺寸的既有安全余量；图容量 1024
  节点时下一窗口续传——临场流 last-write-wins，截断无损语义）。
  /C2S drag stream: graph position + owner + entries capped at 512 per packet (aligned
  with the blob-chunk sizing headroom; overflow rides the next window — the stream is
  last-write-wins, truncation is loss-free by construction).
- S→C `GraphDragStreamSyncPacket`：服务端权威回填 actor 后按编辑者逐个转发，跳过发起者
  （presence 中继同款纪律：身份字段服务端派生，绝不转发客户端自带值）。
  /S2C relay: server derives the authoritative actor and forwards per editor, skipping
  the sender — the presence-relay discipline verbatim.

### 3.2 预算与节流 / Budget & throttle

- 客户端节流 100ms，仅拖动进行中发送；无 presence 那样的强制立即旁路语义。
  /Client throttle 100 ms, sending only while a drag is live; no forced-send bypass.
- 预算表新增 `DRAG_STREAM = (burst 20, refill 10.0)`：refill ≥ 发送速率 → 恒满零拒绝；
  burst 20 吸收鼠标事件抖动。与 presence 桶同款哲学。
  /New budget row DRAG_STREAM (20, 10.0): refill ≥ send rate keeps the bucket full —
  zero rejections by construction; same philosophy as the presence bucket.
- EDIT_OP 预算不动：松手收口仍是一次性突发（≤1024 op < burst 1100），桶的「离散动作」
  标定从此名实相符。
  /EDIT_OP stays as-is: the release is still a one-shot burst within budget, and the
  bucket's "discrete actions" calibration finally matches reality.

### 3.3 服务端语义：只中继不落图 / Server semantics: relay-only

临场流**不触碰服务端权威图**：不应用、不 markDirty、不分配版本、不进 opLog、不触发
全量同步。/The transient stream never touches the authoritative graph: no apply, no
markDirty, no version allocation, no opLog, no full sync. 理由 / Why:
- 拖动未松手 = 未提交；服务端图在松手前保持原值，拖动者崩溃/掉线天然无残留——提交
  语义从「持续覆盖」改为显式「松手即提交」。
  /An unfinished drag is not a commit; the server graph keeps its pre-drag values until
  release, so a mid-drag crash leaves no residue — "commit on release" replaces
  "continuously overwrite".
- 观察者（非编辑者）本来看不到拖动中的位置（MOVE 从不在全量同步触发集内），无回退。
  /Observers never saw live drag positions anyway (MOVE is not in the full-sync trigger
  set) — no regression.
- 消掉现状「每个被放行 op 都 markDirty」的逐包磁盘写放大。
  /Removes today's per-applied-op markDirty disk-write amplification.

校验照编辑 op 的门槛裁剪：距离校验 + 会话成员校验保留；准入门 / 环检测 / 存在性校验
不适用（不改图）。
/Validation reuses the edit-op gates trimmed to fit: distance + session-membership stay;
admission / cycle / existence checks do not apply (nothing changes).

### 3.4 编辑者侧：渲染覆盖层而非本地图改写 / Editor side: render overlay

其他编辑者把临场条目应用到**渲染覆盖层**（presence 光标同款），不改写本地图节点：
渲染时若某节点存在新鲜临场覆盖（条目带时间戳，超时即失效），画覆盖位置，否则画本地图；
松手后的持久 op 经既有 op 广播路径落地，覆盖层自然失效。
/Other editors apply transient entries to a render overlay (presence-cursor style),
never to their local graph nodes: while a fresh overlay entry exists (timestamped,
expires on silence) the node draws at the overlay position, otherwise at the local
graph's; the release ops land via the existing op broadcast and the overlay dissolves.

（对照被否形态：直接改写其他编辑者本地图——需要服务端跟踪拖动活性并在静默后广播终结
标记，状态面大且与 presence 的既有卫生模型分叉，不取。）
/Rejected shape: mutating the remote editors' local graphs — that needs server-side drag
liveness tracking and end-marker sweeps, a large state surface diverging from the
presence hygiene model.

### 3.5 客户端收口 / Client integration

四条拖拽路径（`handleMultiDrag` / `handleNodeDrag` / `handleCommentParentMove` /
`handleCommentResize`）的拖动中发送全部改为入流；松手 finish* 路径保持既有持久 op
（多选松手逐节点 MOVE、注释松手 SET_COMMENT_SIZE + 被撞开节点 MOVE 等）不变。
`sendOp` 的 `pendingLocalOps` 计数语义不变——只有离散动作进 op 通道。
/All four drag paths feed the stream while dragging; the finish* release paths keep
sending durable ops exactly as today. `pendingLocalOps` accounting is untouched — only
discrete actions enter the op channel.

## 四、方案 D：丢弃回执 / Drop receipts

### 4.1 序号与回执 / Sequence & receipt

- `GraphEditOpPacket` 增加客户端序号字段 `clientOpSeq`（发送侧单调递增；1.3.0 dev
  阶段直接改 codec，不写跨版本兼容——既有纪律）。codec 与 `GraphEditOpSyncPacket`
  共享：S→C 方向序号由服务端原值回填，远端应用方忽略。
  /GraphEditOpPacket gains a monotonic clientOpSeq (codec changed outright — unreleased
  shapes carry no compat, per standing discipline). The codec is shared with the sync
  packet; server echoes the seq unchanged and remote appliers ignore it.
- 新增 S→C `GraphOpDroppedPacket`：graphPos + clientOpSeq。仅编辑 op 类型 opt-in
  （限流包装器按类型开关并从 payload 提取回执字段；其余 C→S 类型被拒仍静默——它们没有
  客户端挂起状态）。
  /New S2C GraphOpDroppedPacket (graphPos + clientOpSeq), opt-in for edit ops only — the
  rate-limit wrapper extracts receipt fields from the payload per type; other types stay
  silent (their clients track no pending state).
- 客户端 `GraphAckRouter` 增加丢弃分支：走既有 REJECT 契约（pendingLocalOps−−、乐观态
  回滚、REJECT 提示）。引用未 ACK 临时 id 的挂起 op 同样按 seq 终止并提示——
  ADD_NODE_REQUEST 被丢意味着临时节点永不落地，必须显式了断，不能留悬空等待。
  /GraphAckRouter gains a drop branch running the existing REJECT contract; deferred
  ops waiting on un-ACKed temp ids terminate by seq with a notice — a dropped
  ADD_NODE_REQUEST means the temp node will never materialize and must be resolved
  explicitly, never left dangling.

### 4.2 边界 / Boundaries

- 丢弃回执本身是 S→C 包，本就不在限流面内；服务端每个被拒 C→S 包至多回一条，量级
  天然受控。
  /The receipt is an S2C packet (never rate-limited); at most one per rejected C2S
  packet, so its volume is bounded by the flood itself.
- strike 断开语义不变：回执不改变「持续超限逐出」的判定，只把**单包丢弃**变得可见。
  /Strike-based disconnects are unchanged: receipts make *individual drops* visible,
  they do not alter the sustained-flood eviction judgement.

## 五、落地批次与验收 / Batches & acceptance

- **批 1 = A**（主病灶）：payload ×2 + 注册 + 预算行 + 四条拖拽路径改造 + 渲染覆盖层。
  验收：headless（codec 往返、DRAG_STREAM 桶判定、条目合并与 512 截断续传）；实机
  ≥55 节点多选连续拖 ≥15 秒不被断开（现状约 2–3 秒即踢）、第二编辑者实时看到拖动、
  松手后全端一致、拖动中杀掉客户端无残留。
  /Batch 1 = A: two payloads + registration + budget row + four drag paths + overlay.
  Accept: headless codec/bucket/merge tests; live 55+-node multi-drag ≥15 s without
  disconnect (today it kicks in ~2–3 s), live view on a second editor, all-end
  consistency after release, no residue when the dragger is killed mid-drag.
- **批 2 = D**（次生链）：codec 加序号 + 回执 payload + ACK 路由丢弃分支 + deferredOps
  终止。验收：headless（回执→pendingLocalOps 收敛、tempId 挂起终止、非编辑 op 不发
  回执）；实机用临时调低 burst 的调试开关人为制造丢弃，确认不闩死、有提示、无静默
  丢编辑。
  /Batch 2 = D: seq field + receipt payload + ACK-router drop branch + deferred
  termination. Accept: headless convergence/termination tests; live with a debug switch
  that lowers the burst to force drops — no latch, a notice is shown, no silent edit
  loss.
- 每批独立提交；实机回归需双人（拖动者 + 旁观编辑者）。批次内不动模组自身版本号。
  /Each batch commits separately; live regression needs two players (dragger + observer).
  No mod-version bump inside a fix batch.

## 六、决策点（可否决） / Decision points (vetoable)

| # | 决策 / decision | 本文取值 / taken here | 备选 / alternative |
| --- | --- | --- | --- |
| 1 | 临场流服务端语义 / server semantics of the stream | 只中继不落图（松手即提交）/ relay-only, commit on release | 服务端临场落图（需活性跟踪 + 磁盘节流）/ transient apply with liveness tracking |
| 2 | 编辑者侧应用方式 / how remote editors apply it | 渲染覆盖层（presence 同款）/ render overlay, presence-style | 直接改写本地图 / mutate local graphs (needs end-sweep) |
| 3 | 流条目范围 / stream entry scope | 拖动中发送的四条路径全部入流 / all four drag paths | 仅位置，注释尺寸另调间隔 / positions only, resize interval-tuned |
| 4 | 批次顺序 / batch order | 先 A 后 D / A first, D second | 先 D（更小）后 A / D first (smaller) |
