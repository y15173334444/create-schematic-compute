# C→S 包洪水治理：拖动临场流 + 丢弃回执 · 方案 / C→S packet-flood remediation: drag presence stream + drop receipts

> **目标**：把「图内多节点拖动 / 高频操作 → 触发服务端限流断开」修到机制上不再可能，
> 并把限流拒绝的静默丢包从「无声丢编辑」改为「客户端可见、状态收敛」。
> **Goal**: make "multi-node drag / frequent ops → rate-limit disconnect" mechanically
> impossible, and turn silent rate-limit drops into visible, state-converging receipts.
>
> **状态**：✅ 批 1（§三拖动临场流）+ 批 1.1/1.2（实机回归修复）已落地并双人实机验证通过
> （2026-11-21）；批 2（§四丢弃回执）未动工。落地注记见 §七。
> **Status**: batch 1 (§3 drag presence stream) plus batches 1.1/1.2 (live-regression fixes)
> landed and two-player verified (2026-11-21); batch 2 (§4 drop receipts) not started.
> See §7 for landing notes.
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

---

## 七、落地注记 / Landing notes

（§六四个决策点均按本文取值落地；2026-11-21 双人实机验证通过。/ All four §6 decision
points landed as written here; two-player live-verified on 2026-11-21.）

- **批 1**（`d10e2856`）：§三全量——payload×2、预算行 `DRAG_STREAM=(20, 10.0)`、四条拖拽
  路径入流（100ms 节流 + 512 上限旋转续传）、服务端只中继不落图、编辑者侧**帧影覆盖层**
  （渲染几何段前把新鲜覆盖写进节点字段、`finally` 还原图真值——渲染器几何代码零改动，
  帧间命中测试恒见图真值；流静默 500ms 过期回落）。
  / Batch 1: the §3 mechanism in full — two payloads, the DRAG_STREAM budget row, the four
  drag paths feeding a 100 ms-paced stream with 512-cap rotating continuation, a relay-only
  server, and the editor-side frame-shadow overlay.
- **批 1.1**（`a8c290d0`，实机「跟随一格一格」）：影子 smoothstep 0.25/帧（4 帧收敛）短于
  100ms 流窗口——每窗滑完即驻停，视觉上步进。改 **0.12/帧**（与远端 op 平滑
  `advanceRemoteMoveLerp` 同节奏：滑行时长超过发包间隔，窗口重叠成连续运动）。另加**权威
  接管**：`MOVE_NODE`/`SET_COMMENT_SIZE` 落地时（`GraphRemoteApplier` 主应用后，REJECT 已
  提前返回）把仍新鲜覆盖层的目标接管为权威终值——松手尾巴 ≤100ms 的位移只存在于松手 op，
  无接管则影子冻结在流末位、过期时跳变。
  / Batch 1.1 ("steppy following"): the shadow converged in 4 frames, shorter than the
  100 ms window, so each window held still after gliding — retimed to 0.12/frame (the
  remote-op lerp's cadence); plus authoritative adoption so the shadow glides into the
  release-op final instead of freezing and jumping on expiry.
- **批 1.2**（`b1478085`，实机「按住不动未松手，旁观端节点回原位」）：流只由 mouseMoved
  驱动——停手即静默，覆盖层 500ms 过期回落图真值。修法 = 拖拽进行中 clientTick **保活补发**
  当前几何（拖拽状态 → 条目为纯函数 `buildDragEntries`，优先级照分发序：尺寸调整 &gt;
  注释框拖动 &gt; 多选 &gt; 单节点；tracker 的 100ms 节流统一管速）。真卡死/掉线时保活自然停
  ——过期回落的安全性质保留。
  / Batch 1.2 ("snap-back while holding still"): the stream was mouseMoved-driven, so a
  held-still drag went silent and the overlay expired; clientTick now keeps the stream
  alive while a drag is live (pure drag-state-to-entries mapping, dispatch-ordered), and a
  true stall still stops the keep-alive, preserving the expiry fallback.
- **实测结论**：拖动跟随连续顺滑；按住不动旁观端钉住不回位；松手平滑落位无跳变；多选拖
  不再触发限流断开。
  / Live verdict: fluid following, a held-still drag pins the watcher's view, release lands
  smoothly, and the rate-limit disconnect no longer reproduces.
- **未动 / open**：批 2（§四丢弃回执）；残留 = 显示器布局拖拽（`MonitorDisplayCanvas`）仍
  流式发 `SET_DISPLAY_LAYOUT` op——同 20Hz 持续暴露面（约 92 秒拖空 EDIT_OP 桶），建议后续
  并入临场流或同款保活/节流治理。
  / Open: batch 2 (§4); residual = display-layout drags still stream SET_DISPLAY_LAYOUT ops
  (the same 20 Hz sustained exposure) — fold them into the stream or apply the same
  keep-alive/throttle treatment later.

### 7.1 批 2 + 残留治理落地（2026-11-21）/ Batch 2 + residual landing

- **批 2**（`7ae01a5f`）——实现取型**偏离 §四原文，取更简形态**：限流拒绝与 handler 级拒绝
  语义同构（op 未被应用），故不加专用丢弃包、不加客户端序号登记表、不动任何 codec——
  `rateLimited` 包装器对编辑 op 类型 opt-in 一个「被拒 op 提取器」，桶耗尽拒绝时以
  **REJECT 同款回执**（`GraphOp.reject` 全字段被拒 op 经 `GraphEditOpSyncPacket`）发回发起者。
  客户端零改动走既有 REJECT 契约（乐观态回滚 / pendingLocalOps 归还 / temp-id 挂起了断，
  契约覆盖 = `RejectReceiptTest`）；零 wire-format 变更；其余 C→S 类型无客户端挂起状态、
  保持静默；DISCONNECT 不回执。批 1 定性的次生链（静默丢包 → pendingLocalOps 泄漏 →
  回弹保护闩死 → 重开丢编辑）由此封口。
  / Batch 2 deviates from §4's letter for a simpler shape: a rate-limit rejection is
  semantically identical to a handler-level refusal, so the wrapper gains an opt-in
  refused-op extractor and answers drops with the exact REJECT receipt the handler path
  already uses — no dedicated packet, no sequence registry, no codec change, zero client
  changes, contract coverage via RejectReceiptTest. The batch-1-diagnosed secondary chain
  is thereby sealed.
- **残留治理**（`03edc0b4`）：显示器布局拖拽实测形态 = 已 100ms 节流、**单元素无乘法**，
  10 包/s 对 EDIT_OP 回填 8/s 持续净耗 2/s —— 约 9 分钟连续拖才拖空（低烈度）。取最小修：
  间隔 100ms → **150ms**（6.7 包/s &lt; 回填 8/s，桶恒不拖空、合法拖拽零拒绝）；旁观端由
  layoutLerp 平滑；停手保活不需要（显示布局无过期机制，静默期远端钉在最后位置恰好正确）。
  并入临场流的重形态留作后续选项。
  / Residual treatment: display drags are already 100 ms-throttled and single-element (no
  multiplication); 10 pkt/s against the 8/s refill drained the bucket after ~9 minutes of
  continuous dragging. Minimal fix: interval to 150 ms (6.7 pkt/s &lt; refill — the bucket
  never drains); watchers smooth via layoutLerp; no keep-alive needed (no expiry in display
  mode). Folding into the stream remains a future option.
- **重启边界**：批 2 为服务端行为（客户端契约复用既有路径）——服务端重启生效；显示间隔
  为客户端行为——客户端重启生效。
  / Restart boundary: batch 2 is server-side (the client contract is the existing path);
  the display interval is client-side.

### 7.2 批 3：显示器拖拽并入临场流（2026-11-21）/ Batch 3: display drags join the stream

- **动因 / Trigger**：150ms 权宜间隔后实机反馈「有平滑，但采样少会卡顿」。根因有二：6.7Hz 采样
  稀疏；且每条拖拽 op 触发合并全量同步（~2 秒节奏），整图替换**打断旁观端插值中段**。
  / After the 150 ms stopgap, live feedback: smoothing existed but samples were sparse, and
  each drag op triggered a coalesced full sync (~2 s cadence) whose graph replacement aborted
  the watcher's lerp mid-flight.
- **落地**（`24615b4c`）：显示器拖拽**并入拖动临场流**——`KIND_DISPLAY_LAYOUT` 条目（归一化
  布局坐标）走 tracker 的 100ms 节流（DRAG_STREAM 桶恒满零拒绝）；拖拽期间零 op（EDIT_OP 只
  吃松手收口，且拖拽中不再有全量同步打断插值）；canvas 渲染接入同款帧影（缓存重建前把新鲜
  覆盖写进 layoutX/Y、重建后立即还原，缓存持有快照故帧间输入路径恒见真值；异常滞留的 stash
  由下一帧 computeIfAbsent 命中原值自愈）；clientTick 保活经 presence 的模式感知拖拽 id 延伸
  到显示拖拽（按住不动旁观端钉住）；权威 `SET_DISPLAY_LAYOUT` 松手 op 接管仍新鲜覆盖层目标
  （无尾巴跳变）。150ms 权宜间隔与其墙钟闸删除；临时活体探针全部移除。
  / Batch 3 lands the fold-in: a KIND_DISPLAY_LAYOUT entry rides the tracker's 100 ms pacing
  (DRAG_STREAM stays full), zero ops while dragging (no mid-drag graph replacements), the
  canvas renders through the same frame-shadow pattern, the keep-alive extends to display
  drags via the presence mode-aware drag id, and authoritative release ops adopt fresh
  overlays. The 150 ms stopgap interval is gone; all temp probes removed.
- **教训 / Lesson**：权宜治理（调间隔）治不了「op 通道自带的全量同步副作用」——同类残留的
  正解是并入临场流，让 op 通道回归纯离散动作。
  / A pacing stopgap cannot fix the op channel's full-sync side effects — the real treatment
  for this class of residual is folding into the stream, returning the op channel to pure
  discrete actions.

### 7.3 批 3 后续两笔实机反馈修复（2026-11-21）/ Batch 3 follow-ups from live feedback

- **软锁框钉旧位**（`e9576fd2`）：协作叠加层在帧影窗口**外**重读 layoutX 真值——窗口原只罩
  画布缓存重建。帧影 begin/end 上移到 `MonitorDisplayEditor.render` 整帧入口：缓存重建、图
  层面板、协作叠加层全在窗内读影子位置，帧末还原（帧间输入路径恒见图真值）；画布侧只保留
  「影子活跃 → 强制重建缓存」的感知。
  / The soft-lock frame re-read live layoutX outside the shadow window (which only covered
  the canvas cache rebuild) — begin/end moved up to the frame entry so the rebuild, the
  layer panel and the overlay all read shadowed positions; truth restored at frame end.
- **显示模式远端光标无插值**（`3ff826ff`）：`renderDisplayPresence` 直画 presence 原始坐标
  （8.3Hz 阶跃）——节点模式的 cursorLerp 未移植，且其存值是图空间换算（对显示画布坐标是错
  空间，接了也不能用）。显示模式补独立逐玩家 smoothstep（`advanceCursorLerp` 纯静态
  0.12/帧、画布坐标空间、退场 retainAll 清理）。
  / Display-mode remote cursors were drawn raw at the presence cadence; the node mode's
  cursorLerp was never portable (its stored values are graph-space transforms). Display now
  keeps its own per-player canvas-space smoothstep.
- **实测结论**（2026-11-21）：节点拖拽三件套、显示器拖拽跟随、软锁框实时贴位、远端光标
  平滑——全部通过，限流断开不再复现。
  / Live verdict: all collaboration surfaces smooth; the rate-limit disconnect no longer
  reproduces.
