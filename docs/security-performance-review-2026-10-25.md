# 全仓安全与性能审查（2026-10-25）/ Full-Repo Security & Performance Review — 2026-10-25

> **状态**：🔶 **审查已完成，所列缺陷待处置**。审查基线 `d8d83fe`（1.3.0 WIP，工作树干净）；
> 方法：七个分区扫查（网络解码 / 服务端 op 链与持久化 / 求值核心 / 宿主方块 tick / 客户端渲染与
> GUI / 音频链端到端 / 跨切面），每条发现经读码核实，全部 P1 与 P2 头条指控另行逐条二次人工核验。
> **本文只记录问题与建议，不改源码。**
> **Status**: 🔶 **review complete, findings pending disposition.** Baseline `d8d83fe` (1.3.0 WIP,
> clean tree); method: seven area sweeps (network decode / server op-chain & persistence /
> eval core / host-block tick / client render & GUI / audio chain end-to-end / cross-cutting),
> each finding code-verified, and every P1 and P2 headline claim re-verified by a second manual
> pass. **This document records findings only — no source changes.**
> **查重基准 / Dedup baselines**：[`known-open-issues.md`](known-open-issues.md)（10 条已登记项，
> 引用按标题）、[`tech-debt-review-2026-10-11.md`](tech-debt-review-2026-10-11.md)（上轮全仓审查，
> 基线 `e3e7866`——其后 168 个提交、226 文件变动 +28980/−8466 行）、`docs/reviews/2026-07-30`
> code-health review（Sable compat 早期条目）。已登记项不重复立案，上轮条目只报现状（§四）。
> **快照口径 / Snapshot**：本文是 `d8d83fe` 时点的快照；修复逐条落地时以
> [`known-open-issues.md`](known-open-issues.md) 与 [`CHANGELOG.md`](../CHANGELOG.md) 登记为权威，
> 本文不逐条回标。
> **标注约定**：【核实】= 读本仓代码确认；【待核实】= 有证据但未闭合。「上轮已知未修」=
> 上轮已发现、本轮确认仍在且未进登记册（登记册的完善是处置阶段的事，见 §六）。

---

## 〇、结论摘要 / Summary

上轮的两个 P0（解码器先分配后校验、BlobRegistry 无界重组）确认**已完整修复且带纵深防御**，
本轮**无新增 P0**：服务端可达的 C→S 解码面逐包核验干净（decode 钳制 + registry 二次拦截 +
归属作用域 + 周期回收），攻击面整体从「单包打崩服务端」收敛到了「持续资源消耗 + 权限门补齐」
的层面。本轮需要优先看的新事实：

1. **服务端 op 链的执行层出现两个输入零校验缺口（P1-1 + P2-1）**：伪造 `ADD_NODE` 携带
   null/未知 NodeType 可在服务端主线程触发 NPE 且**无 REJECT 回执**（违反项目明文 REJECT
   纪律）；客户端可控的节点 id 无唯一性校验，可造成**持久化图腐坏**（重复 id 级联删除）。
2. **音频下发面无上限（P1-2）**：恶意构造的 NBS 曲目（tempo 655 + 密层 + 循环）可把
   ~500KB 单包、MB/s 级流量放大到**全部追踪该区块的玩家**——radius 不参与服务端剔除；
   叠加 NaN/Inf 穿透 `Math.max/min` 钳制导致的**全混音静默/限幅塌陷**（P2-6，跨音响殃及）。
3. **持久化读侧「写有钳、读裸奔」**：`icount`/`scanRange` 两处读侧无钳制把世界文件向量留开
   （畸形存档单节点 OOM / 雷达巨盒冻 tick），`rs_in` 键 `Long.parseLong` 无守卫可致整个
   监视器 BE 被丢弃。
4. **性能面呈「骨架已收敛、常数项与像素级未治」形态**：求值核心的结构大雷（重编译风暴、
   每 tick 重解析、递归无界）已系统性修掉；上轮登记未修的两大老账（K9 逐 tick 分配总账、
   P2-1 O(E) 连线扫描）本轮完成全面量化，另发现求值快照「发送有门控、捕获全量重建」与
   DELAY 参数无上界两个新形态。渲染侧缓存骨架已搭好（主题/token/视觉行/拓扑/节流），
   但**像素级绘制与每帧重建族全线未缓存**——像素编辑器与显示器世界渲染两大热点按量化
   场景定 P1。
5. **跨切面干净，且出现一个前提级反证**：Mixin 五点全部实证锚定（含对 Sable jar 的反汇编
   验证）、Sable 反射层已退役、静态表有系统清理；两个 Sable jar 内**未发现任何物理线程
   移交**，`sable$physicsTick` 大概率随服务端主线程同步执行——上轮起的「物理线程写、游戏
   线程读」volatile 系列遗留整体降为条件性 P3，建议先打点定案线程模型再决定去留。

| 严重度 / Severity | 数量 / Count | 说明 / Note |
|---|---|---|
| P0 | 0 | 上轮两个 P0 已修（纵深防御在位）；本轮无新增 / previous P0s fixed, no new one-packet killers |
| P1 | 4 | op 链 NPE 无回执 · 音频流量放大 · 像素级渲染热点 ×2 / op-chain NPE without REJECT · audio traffic amplification · pixel-level render hotspots ×2 |
| P2 | 20 | 安全 7（含 1 待核实）+ 性能 13 / 7 security (1 unverified) + 13 performance |
| P3 | 35 | 卫生 / 潜伏 / 有意保留（含上轮已知未修重列 7 项）/ hygiene, latent, intentional (incl. 7 known-unfixed relisted) |
| 上轮条目核验 | 33 项 | 已修/收敛 25、部分修 2、未修 6（§四）/ 25 fixed, 2 partial, 6 unfixed (§四) |

---

## 一、P1 / Headline Findings

### P1-1 ADD_NODE 系 op 携带 null/未知 NodeType：服务端主线程 NPE，无 REJECT 回执

**位置**：`network/GraphEditOpPacket`（decode 的 NodeType 可空读取）、`blocks/EditSessionRegistry#applyOp`
（op 执行段）、`graph/OpExecutor#apply`（ADD_NODE / ADD_NODE_REQUEST case）、`graph/GraphNode`
（构造器）。

**机制**：decode 明文允许 nodeType 为 null——布尔哨兵为假，或类型串未知（`NodeType.BY_ID`
是普通 Map，未知串返回 null）。`handleServer` 的会话成员门与 actor 覆写都在，但
`applyOp → OpExecutor.apply → graph.addNode(null, …)` 一路无 null 检查，GraphNode 构造器
`type.paramNames.length` 解引用 NPE。applyOp 的 try 块只有 finally（BlobRegistry 作用域清理）
没有 catch；异常最终落在 vanilla 主线程任务包装里被记日志——**服务不崩，但不发 REJECT**：
客户端 pending-ACK 计数泄漏、每个伪造包一条完整堆栈（无限流下可刷盘）。攻击门槛低：任意
128 格内玩家经 GraphJoin（无所有权门）成为会话成员即可发送。对照：NBT 加载路径对同类输入
已有兜底（未知类型→CONST+WARN，F15），op 路径没有。

**修复方向**：`opBoundsViolation` 预检（或 decode 处）对 ADD_NODE 系 op 拒收 null NodeType，
走 REJECT；一行守卫级。

**状态**：【核实】（二次核验通过：decode / applyOp / 构造器三段引用均复核）。新发现，未登记。

### P1-2 恶意 NBS 曲目经 NoteEventPacket 的服务端→客户端流量放大

**位置**：`graph/MusicTransport#advance`（`PREROLL_SECONDS=2.0` 全窗展开）、`graph/NbsSong#read`
（tempo 无符号 short，无上界）、`blocks/SpeakerBlockEntity#play`（`sendToPlayersTrackingChunk`
全扇出）。

**机制**：曲目上传受 `NbsSong.MAX_BYTES`=256KB 上限，但文件内 tempo 可达 655.35 tick/s、每
tick 音符数只受字节数约束。构造 ~28 层 × 1311 tick 的曲目可把 ~3.6 万音符全部塞进 2 秒预播窗，
一次 `advance` 即展开为**单包 3.6 万 NoteEvent**（14B/音符 ≈ 514KB，低于客户端载荷上限可整包
送达）；`loop=true` 时每 2 秒全曲循环，**无需再操作**即对每个追踪该区块的玩家持续输出
≈250KB/s；高频 play/seek 抖动可再放大。`play` 的扇出用 chunk 追踪分发，**radius 不参与服务端
接收判定**（距离衰减纯客户端），radius=1 的音响照样向整个视距内玩家发全量音符流；放大系数
= 听者数 × 音符速率 × 音响台数，无任何上限/节流。客户端侧：单包 3.6 万事件在网络线程逐条
schedule，声部打满 1024 后每条触发 O(1024) 剥夺扫描（≈3800 万次比较）——网络线程数百毫秒
停顿，20Hz 抖动即客户端音频/网络线程级 DoS。

**修复方向**：服务端按「事件数/包」与「事件数/秒/音响」设上限（单包数百级，超限丢弃+告警）；
NoteEvent 下发按 radius × 追踪距离做粗剔除。

**状态**：【核实】（二次核验通过：advance 展开循环、tempo 读法 `getShort() & 0xFFFF`、chunk
扇出与 radius 仅作载荷三段复核）。新发现，未登记。

### P1-3 像素编辑器全链路每像素每帧重绘：32×32 序列 + 网格 + 帧条 ≈ 1.5 万 draw-op/帧

**位置**：`client/PixelEditorScreen#renderCanvas`、`client/PixelEditorFrameStrip#renderThumb`。

**机制**：画布每帧对 imgW×imgH 每格 1 次 `g.fill`，网格开启时每格再 1 次 `g.renderOutline`
（=4 次 fill）；帧条每个可见缩略图整幅重画。上限 `IMAGE_MAX_SIZE`=32：单帧画布 ≈5120
draw-op，序列编辑器 10 帧可见再加 ≈1 万，合计 ≈1.5 万/帧（≈6 万顶点、~1MB 顶点数据，GUI
BufferBuilder 多次中途 flush）。**无任何脏标记/纹理化**——只有内容或缩放变化才需要重画。

**修复方向**：画布与缩略图纹理化（内容变化才重建），或至少脏区重绘。

**状态**：【核实】。新发现（上轮只列了 BER 侧的每像素 quad，GUI 像素编辑器/帧条未入账）。

### P1-4 显示器世界渲染每像素每帧 4 顶点 quad 无缓存（3D+HUD 双路径），HUD 每不透明像素再 new float[8]，恒渲染不进视锥剔除

**位置**：`client/renderer/MonitorBlockEntityRenderer#render`、`client/renderer/HudRenderer#renderHud`。

**机制**：3D 模式每图像每帧对非透明像素逐个 `addVertex×4`（32×32 ≈ 1024 quad/帧/显示器），
内容不变也重发；HUD 模式更重：每不透明像素 `new float[8]` + 6 顶点三角形扇。叠加
`shouldRenderOffScreen()=true`（显示器恒渲染、不进视锥剔除）——图像墙场景（N 显示器 × M
图像）顶点数与分配线性叠加，HUD 模式每帧数万 float[8] 造成 GC 抖动。3D 路径几何只依赖 BE
参数 + 快照值，可按代际缓存 mesh。

**修复方向**：3D 路径按图代际缓存网格；HUD 路径像素四边形数组复用；复核 off-screen 恒渲染
是否为必要语义。

**状态**：【核实】。**本体上轮已列**（tech-debt P3「MonitorBER 每像素每帧 quad」）、1.3.0
HudRenderer 拆分批明示「每帧分配治理……未做——行为变更与结构变更不同批」；本轮新增 HUD
侧每像素分配与恒渲染两个加重因子，按图像墙场景量化上调为 P1。未进登记册。

---

## 二、P2 / High-Risk Debt & Structural Findings

### 安全（网络 / op 链 / 持久化 / 音频数值链）

#### P2-1 presence 中继零发送者门：非会话成员、任意距离、任意速率可向任意编辑会话注入 presence 并扇出

**位置**：`network/GraphPresencePacket#handleServer`、`network/GraphLeavePacket#handle`。

**机制**：与同族其余 C→S 包（BlobPacketHandler、GraphEditOpPacket、MonitorSettingsPacket 等
均有「距离 + 会话成员」双门）不同，presence 中继两包**一道门都没有**：handleServer 只验
ServerPlayer，随后把包内容（身份字段已由服务端回填）转发给 `pkt.pos` 的全部编辑者。攻击
路径：任意已连接玩家（无需加入会话、无需在方块附近，甚至可在另一维度——getEditors 只查
发送者所在维度的同名坐标）高频发送 presence，使所有编辑者 UI 持续出现幽灵光标与**最多
4096 个节点的多选软锁**——`isNodeLockedByOther` 是硬性 UI 门（命中/交互/子编辑器入口对
被锁节点跳过），等同 30 秒续期的全图节点锁定拒绝服务。放大：每入站包 →（编辑者数−1）份
出站转发，无速率限制（合法客户端 120ms 间隔，攻击者可按线速发）。

**修复方向**：补同款双门，或至少「发送者 ∈ editors」一行校验。

**状态**：【核实】（二次核验通过：handler 全文无成员/距离/速率检查；软锁门在 GraphEditor
多处引用复核）。上轮 P1-2（身份伪造）已修、K17 已登记「服务端纯中继」设计口径——本条
指出的是**发送者侧**完全无门，超出该口径，未单独登记。

#### P2-2 BlobRegistry 单玩家内存预算 ≈0.5–1GB，且 PENDING 可低速率 touch 续命无限滞留

**位置**：`network/BlobRegistry`（MAX_ENTRIES、PendingBlob.touch、receive）。

**机制**：上轮 P0-2 修复（64 片 × 30KB 钳制、归属分键、256 条软上限、20 tick 回收）在位，
但剩余预算偏大：每玩家可持有 PENDING 256 条 × 1.92MB ≈ 491MB + COMPLETED 256 条 × 1.92MB
≈ 491MB。COMPLETED 诚实 30 秒过期；**PENDING 每收一片就 touch() 续期**——攻击者先真实传输
~491MB 填满（线缆成本 1:1，无放大），之后每条每 <30 秒补 1 片 30KB（合计 ~256KB/30s 的
滴注）即可**无限期钉住全部内存**。业务真实需求仅 SET_SONG ≤256KB ≈ 单条 9 片；多人各自
UUID 独立预算，线性叠加。

**修复方向**：每 owner 总字节上限（如 2MB），或 MAX_ENTRIES 按业务量级收紧（≤8）。顺带更正
`MAX_TOTAL_CHUNKS` 注释失真（称上界由「NbsSong.MAX_BYTES（1MB）」推导，实际常量 256KB）。

**状态**：【核实】（算术自代码常量推得，未实测）。上轮 P0-2 已修的是无界版本；本条为修复后
的残余预算问题，未登记。

#### P2-3 ADD_NODE 允许客户端指定节点 id（撤销路径依存），撞既有 id → 重复 id 图腐坏并持久化

**位置**：`graph/OpExecutor#apply`（ADD_NODE case）、`graph/NodeGraph`（removeNode /
rebuildNodeMap）。

**机制**：客户端可直接发 `ADD_NODE`（撤销 REMOVE_NODE 的逆 op 就这么设计——用原 id 恢复），
服务端 `graph.addNode` 分配 id 后被 `node.id = op.targetNodeId()` **客户端值覆写**，无唯一性
校验。targetNodeId = 某现存节点 id 时，nodes 列表出现双份同 id：rebuildNodeMap 后新节点遮蔽
旧节点（findNode/编辑 op 打到新节点），removeNode 的 `removeIf(n -> n.id == id)` **一并级联
删除两个节点**。腐坏随 markDirty 落盘。

**修复方向**：服务端 ADD_NODE 校验 id 唯一性（不与现存节点冲突；对未来分配冲突的治理可与
「节点准入名单仅客户端把守」（登记册条目，含 K10 同批口径）合并立案）。

**状态**：【核实】（二次核验通过：覆写语句与 removeIf 级联复核）。新发现，未登记。

#### P2-4 GraphNode.load 的 icount 读侧未钳制——畸形存档单节点声明 2³¹-1 物品槽数组（加载期 OOM/Error）

**位置**：`graph/GraphNode#load`。

**机制**：世界文件 NBT 的 `icount` 直接 `new ItemStack[ic]` 先分配后按 `contains("i"+i)` 填充
——声明 2×10⁹ 即 ~16GB 引用数组。网络面不可达（SET_HOTBAR_ITEM 被钳 0..8），属**世界文件/
`/data merge` 向量**；区块加载路径若抛 OutOfMemoryError（Error 不被 vanilla 的 catch(Exception)
吞掉）可打崩服务端。对照：同函数 `pcount` 有 `i < params.length` 守卫、`iw/ih` 有 1..32 钳制，
唯 icount 裸奔。

**修复方向**：读侧钳制（如 ≤16，超限截断 + WARN），与写侧对称。

**状态**：【核实】（二次核验通过）。新发现，未登记。

#### P2-5 Radar scanRange 存档加载无上界钳制——畸形存档构造巨型 AABB 实体查询冻死 tick

**位置**：`blocks/RadarBlockEntity`（loadTypeSpecific 的 scanRange 读取、扫描使用点）。

**机制**：网络侧 `RadarSettingsPacket#handle` 已钳 `max(1, min(128, …))`，但存档加载
`if (t.contains("scanRange")) scanRange = t.getInt(...)` **只判存在不判范围**；tick 扫描按该值
构造 AABB 做实体查询——声明 2×10⁹ 时按方块盒遍历实体分段坐标区间，服务端 tick 近乎永久
挂死。世界文件向量（上轮 P1-3 只修了 ScanSablePacket 的平方溢出面，此面未覆盖）。同款：
Speaker 的 radius 读侧亦只 `Math.max(1, …)` 无上界（见 P3 重列口径）。

**修复方向**：所有「写有钳」的数值字段在读侧补同一钳制（写读对称清单化）。

**状态**：【核实】（二次核验通过）。新发现，未登记。

#### P2-6 音频数值链的 NaN/Inf 钳制盲区：`Math.max/min` 对 NaN 透传，Inf 曲线 y 打穿限幅器 → 客户端全混音静默/塌陷（跨音响殃及）

**位置**：`blocks/SpeakerBlockEntity#applySettings`、`graph/GraphOp#parseCtrlPoints`、
`graph/AudioEval`（AMP）、`client/audio/AudioMixer`、`client/audio/CscAudioStream`。

**机制**：两个入点——① SpeakerSettingsPacket 的 gain 为任意 float，
`Math.max(0f, Math.min(4f, NaN)) == NaN`，NaN 绕过 [0,4] 钳制写进 BE；②
`parseCtrlPoints` 用 `Float.parseFloat`（接受 "NaN"/"Infinity"，点数已限 64 但值域不校验），
AMP/WSHAPE 曲线 ys 带 NaN/Inf 进链。后果（客户端合成链逐行核实）：NaN 增益通过
`gainL==0f && gainR==0f` 判（NaN≠0），声部入混后 `out[f] += s*NaN` 使**该帧整个混音总线**
（含其他所有音响的声部）变 NaN → `(short)(NaN*32767f)==0`，全混音在 NaN 声部持续期间整体
无声；Inf y → 块峰值 Inf → 限幅 target=LIMIT/Inf=0 → limitGain 压到 0 且按 15%/块缓慢恢复
（≈1.4s），同为客户级全局殃伤。攻击门槛：128 格内一条 NaN 设置包或一个曲线 op。注：报的
不是「没有归一化」（保原始电平是项目口径），仅**非有限值传播**。

**修复方向**：增益/曲线全部入点加 `Float.isFinite` 拒收（或 NaN→0）；
`scheduleAtFrame` 对 gainL/gainR 兜底 isFinite。

**状态**：【核实】（二次核验通过：NaN 穿透钳制与 `==0f` 判定复核）。新发现，未登记。

#### P2-7【待核实】CscAudioEngine.ensureChannel 在网络/渲染线程直接调用 ChannelAccess.createHandle——声部池认领的线程亲缘性契约存疑

**位置**：`client/audio/CscAudioEngine#ensureChannel`（由 NoteEventPacket 网络线程直处理路径
触发）。

**机制**：类注释声称「AL 调用全部经 ChannelHandle.execute 落在声音引擎线程」，但这只覆盖
`h.execute(...)`；`ca.createHandle(Library.Pool.STREAMING)` 本身运行在**调用线程**（Netty
网络线程，或编辑器试听时的渲染线程）。原版 SoundEngine 自身的 createHandle 调用经其单线程
executor 串行化；本模组从旁路线程并调时，`Library` 的空闲声部池结构与声音引擎线程的认领
之间存在数据竞争窗口（与原版音乐/唱片同时播放时可达）。无崩溃证据、仓内无原版源码可证
createHandle 是否自带 marshalling。

**定案方法**：反编译原版 `ChannelAccess.createHandle`，或运行时在两线程打点观测。

**状态**：【待核实】。新发现，未登记。

### 性能（求值核心 / tick 骨架 / 渲染与 GUI）

#### P2-8 O(E) 无缓存连线扫描 ×4 处仍在热路径（BUS_IN 每频段一次最重）【上轮 P2-1，未修】

**位置**：`graph/NodeGraph#isAudioWired / #hasAudioSinkConnection`；调用点 `graph/BusEval`
（PRIVATE_IN、BUS_IN 每频段、BUS_OUT pinValues 遍历）、`graph/EncapEval`（ENCAP 外部输入
注入）。

**机制**：每 tick × 每节点（BUS_IN 是每 tick × 每频段）全量遍历 connections（O(E)），对每条
命中边再做 nodeMap.get + inputDomain。B 个频段的 BUS_IN = B×O(E)/tick/节点。反例范式仓内
已有：`isTransferWired`/`isBandPinAudio` 用 topoVersion 版本化缓存（稳态零开销），这几处未接。
100 节点/500 连线、30 个总线节点 ≈ 1.5 万次迭代/tick/宿主，多宿主叠加。

**状态**：【核实】。上轮已发现（P2-1），仍未修、未进登记册。

#### P2-9 求值快照每 tick 全量重建（含子图输出双克隆），发送门控只省了网络不省构建

**位置**：`graph/GraphEvaluator#captureSnapshot`、`graph/EvalSnapshot#capture`、
`blocks/GraphHost#broadcastEvalSnapshot`（tick 步骤 ⑪）。

**机制**：发送侧已按 `sameContent` dirty 门控（上轮 K8 修复），但**构建侧**仍每 tick 全量
防御拷贝：主图输出每节点一次 `float[].clone()` + Integer 装箱；子图输出**克隆两次**
（GraphEvaluator.captureSnapshot 一次、EvalSnapshot.capture 再一次）；spreads 全节点扫描。
空闲图（值不变不发包）照付全部构建成本 + sameContent 的 O(节点数×引脚数) 深比较。另
`saveDebugTimes` 每 tick clear+putAll 全表。量化：100 运行宿主 × 30 输出节点 × 20tps ≈
6 万次数组克隆/秒。注意捕获有副作用（清 subEval outputs、刷新 cachedEvalSnapshot 供显示
读取），不能简单跳过——修法是「比较先行」的重排。

**状态**：【核实】（两分区独立发现交叉印证）。新发现（上轮 K8 只登记发送侧）。

#### P2-10 K9 族逐 tick 分配总账【上轮 K9，未修；本轮量化补全】

**位置**：`graph/GraphEvaluator`（尾部记账）、`graph/FormulaEval` / `graph/FormulaParser`
（FORMULA case 与栈机）、`graph/TimingEval`（时序三表装箱）、`graph/BusEval`（BUS_OUT 双
集合）、`graph/EncapEval`、`graph/AudioEval` / `graph/MusicTransport`（音频族）。

**机制**（每 tick × 每节点 × 每宿主累加）：eval 尾部 `outputs.put(node.id, ctx.o.clone())`
每节点一次克隆 + 装箱；FORMULA 每 tick new HashMap env + 旧 RPN 路径**每赋值语句/每输出**
新建 env、栈机**每 token** push 一个 `new Value.Scalar`；时序表 DELAY/flipflop/PID 每 tick
2-3 次 Integer/Float 装箱；BUS_OUT 每 tick new HashMap + new HashSet（内容门控只省发送侧）；
ENCAP 每 tick 2× getSubNodes（new ArrayList + sort）+ `new float[]{val}`；音频族 MUSIC 每
tick new AudioRef、`MusicTransport.advance` 无条件 new ArrayList（空闲也分配）、AMP 非恒等
曲线每 tick new ArrayList + 每音符 withInstanceVolume 副本；PRIVATE_OUT 写 SIGNALS（CHM）
每 tick 装 Float、BUS_IN 读 `getOrDefault(key, 0f)` 的 0f 每次 new Float（Float 无缓存）。
中大型图 × 多宿主下为持续 young-gen GC 压力，单项不致命。

**状态**：【核实】。上轮已发现（K9 族），仍未修；本轮把量化面补全到尾部记账、栈机逐 token、
BUS_OUT 集合、音频族与 CHM 装箱。

#### P2-11 DELAY 队列长度仅受用户参数约束（无上界 clamp），并经 NBT 逐档放大

**位置**：`graph/TimingEval`（DELAY case）、`graph/RuntimeState#save`（"delay" FloatTag 列表）。

**机制**：`ticks = Math.max(1, (int)node.params[0])` 只有下界；参数可经编辑框/连线覆盖设到
任意大，队列每 tick +1 持续增长：10 万 tick 延时 ≈ 2-3MB/节点（Float 装箱 + deque 槽），且
RuntimeState.save 把整队列序列化成 FloatTag 列表（每元素一个 NBT 对象），随常规区块保存反复
重写。LOOP 计数/FUSE 定时器均已核实有界，DELAY 是唯一无界的（安全性面：会话成员可将参数
拉满做慢速内存消耗）。

**修复方向**：参数上界 clamp（与 K15 上界族同口径）。

**状态**：【核实】。新发现，未登记。

#### P2-12 ensureBusRegistered 的每 tick 基线税：三段全节点扫描 + 固定分配，停机宿主照付

**位置**：`blocks/GraphHost#ensureBusRegistered / #pushAudioFlagChanges`、
`network/BusChannelHelper#convergeBusInBands / #resolveBusInBands`。

**机制**：每宿主每 tick（**图停止也跑**，语义要求自愈）：① recoverConflictedChannels O(N)
节点扫描；② convergeBusInBands 每次 new LinkedHashMap + 有 BUS_IN 时 new HashMap + 每频道
resolveBusInBands 内 new ArrayList（稳态空转也在分配）+ 整表比较，解析为 O(BUS_IN × 节点数)；
③ pushAudioFlagChanges 每 tick new HashSet ×2 + 每频道 `"B:"+busName` 字符串拼接做查表键。
100 宿主（多数停机）≈ 300 次全节点扫描 + 500-1000 次小分配/tick——单项皆轻，合计是收敛后
骨架的结构性常数项。

**状态**：【核实】。新发现（K23 收敛把这三段铺到 12 宿主后的合计面；单段挂点语义上轮已知，
未按成本口径登记）。

#### P2-13 控制椅每 tick 两次实体段查询（inflate(2) 恒跑 + mode-1 时 inflate(50)）【上轮矩阵已知，未修】

**位置**：`blocks/ControlSeatBlockEntity`（consumeInput / beforeEval）。

**机制**：consumeInput 每 tick 无条件 `getEntitiesOfClass(ControlSeatEntity, inflate(2))`
（含 AABB 分配）；玩家以输入模式 1 乘坐时 beforeEval 再做一次 **inflate(50)**（100³ 盒）
查询——跨数十个实体段，每 tick 每椅。20 座椅 mode-1 ≈ 400 次/秒大盒查询。座椅实体引用本可
由 mySeatEntity/乘坐关系直接定位。

**状态**：【核实】。上轮 tick 矩阵已知（ControlSeat 行「mode-1 每 tick inflate(50)」独有缺口），
收敛批次按零行为变更原样保留，仍未修、未进登记册。

#### P2-14 GraphEditor.renderBg 每帧全量重建族：流排序 + 空间索引清空重建 + lockedNodes/杂项每帧分配

**位置**：`blocks/GraphEditor#renderBg` / #sortNodesByB / #buildLockedNodesMap、
`graph/SpatialIndex#build`。

**机制**：每帧 ① sortNodesByB = stream+sorted+collect（3 次分配 + O(n log n)，节点内容未变也
重排）；② spatialIndex.build 清空后重灌（每占据格 `computeIfAbsent(... new ArrayList<>())`
——千节点图每帧数千小 ArrayList）；③ buildLockedNodesMap 无条件 new HashMap（无人协作也
分配）；④ pollGamepadBindings 每帧 new ArrayList；⑤ refreshBusBandEditStates 对每个展开 BUS
节点每帧逐频段字符串比较。索引与排序只依赖（图代际, 展开集, 实测高度），可脏标记缓存。

**状态**：【核实】。lockedNodes 一项上轮已列（P3）仍未修；排序/索引重建为本轮细化。

#### P2-15 NodeRenderer.drawNode 每节点两次 g.flush()（强制中断 GUI 批处理）+ 每节点每帧 MessageFormat/I18n

**位置**：`blocks/NodeRenderer#drawNode`（注释节点另见 `blocks/NodeCommentRenderer`）。

**机制**：每个可见节点每帧 2 次 `g.flush()`（注释自述为防 MC 双通道填充/文字交错），每注释
节点再 1 次——每次 flush 即一次批中断/提交，密集视口 100 节点 = 200+ 次/帧；另每帧每节点
`I18n.get(title)`、每 ENCAPSULATION 节点 `MessageFormat.format`（每次调用内部重新构造
MessageFormat 解析模板）+ 字符串拼接。

**状态**：【核实】。新发现（上轮未列 flush 与 MessageFormat）。

#### P2-16 注释节点每帧全文双重 rewrap + 每段 regex 去格式（无任何缓存）

**位置**：`blocks/NodeCommentRenderer#drawCommentNode / #countWrappedLinesLocal / #plainText`。

**机制**：非编辑态注释每帧跑两遍全文换行计算，且收集全部段后只画可见滚动窗；
`plainText` 用 `String.replaceAll`（每次调用 Pattern.compile），即**每段每帧一次正则编译**。
长注释每帧数百次正则编译 + 数百字符串分配，60fps 下 GC 压力显著。缓存键只需（文本, availW）。

**状态**：【核实】。新发现。

#### P2-17 EditPanel.renderAt 展开节点每帧 split/MessageFormat/频段数组重建

**位置**：`blocks/EditPanel#renderAt`。

**机制**：每个展开 FORMULA 节点每帧把整段脚本 `split("\n",-1)`（MLE 本身有视觉行缓存，行号
前缀循环不复用逻辑行结构、每行再 trim 一次）；formula_summary/click_bind 走 MessageFormat
（模板每帧重解析）；展开 BUS 节点每帧 `new float[bandCount]` 重建 bandPinY。

**状态**：【核实】。新发现（与登记册条目『打字途中面板重建』是不同面——那是控件换实例，
此处是渲染期每帧分配）。

#### P2-18 调试图表每帧重采样重排序：公式模式 61 次公式求值 + 装箱排序【上轮 P3，部分修】

**位置**：`blocks/NodeCharts#renderDebugSignalGenChart / #renderDebugProbeChart`、
`graph/DebugSignals#computeVisibleRange`。

**机制**：DEBUG_SIGNAL_GEN 公式模式每帧 computeVisibleRange 61 次 evalFormula + 61 个装箱
Float + sort，随后曲线再 61 次求值；DEBUG_PROBE 每帧 new ArrayList（≤100 装箱 Float）+ sort；
另有每帧 String.format 范围串。曲线/范围只随（公式, 控制点, setMode）变化，可整图缓存。上轮
「探针每帧 new+sort 最重」的控制点池化已落地（修掉 float[2] 一半），求值/装箱/排序面留守。

**状态**：【核实】。上轮已发现（P3），部分修。

#### P2-19 显示器布局编辑器：运行态元素缓存恒失效 + 图层面板每帧排序重建 + 元素/缩略图逐像素 fill

**位置**：`blocks/MonitorDisplayCanvas#renderDisplayArea`、`blocks/MonitorLayerPanel#renderLayerPanel`、
`blocks/MonitorDisplayEditor#collectDisplayElements / #renderPixels`。

**机制**：① Phase 2 元素缓存的失效条件含 `isRunning`——图运行时（显示器常态）每帧
collectDisplayElements（new ArrayList + 每显示节点一条 record + getInputValue×N + sort）；
② renderLayerPanel 每帧 getDisplayLayers（new ArrayList + 全图扫 + sort，无缓存）；③ 每个
IMAGE 元素 renderPixels 至多 256 fill/帧、每个图层缩略图 cellSz=1 再 256 fill/帧；④ 协作者
在场时每帧再 collect 一份元素表。**新拆三件（2026-10-24/25）为逐字搬迁，未发现每击键/每帧
控件树重建同款病**——此条是渲染期浪费而非重建节奏。

**状态**：【核实】。新发现。

#### P2-20 连线逐段 fill 采样：每条可见线数十~数百 quad/帧

**位置**：`blocks/NodeWireRenderer#bezier / #renderConnections`。

**机制**：每条线 `steps = max(10, dist*0.15)`，每步再按段长逐像素聚合成水平 run 逐个
`g.fill`——500px 长线 ≈75 步 × 每步若干 fill；100 条可见连线 ≈ 数千 quad/帧（仅 GUI 打开时
付费）。视口裁剪已有，但无曲线段缓存/折线批处理。

**状态**：【核实】。新发现（结构性）。

---

## 三、P3（卫生 / 潜伏 / 有意保留）/ Hygiene, Latent & Intentional

> 紧凑登记：每条给位置与一句话机制。「上轮已知未修」= 上轮已发现、确认仍在、未进登记册。

### 网络 / 会话 / op 链

1. **五个 S→C 包 decode 仍按声明长度即时分配**：`network/ClientboundGraphEvalPacket`
   （`new float[len]` 裸 VarInt）、`network/NoteEventPacket`（ArrayList 容量与 float[points]）、
   `network/ScanSableResponsePacket`、`network/RadarTargetsPacket`（ArrayList 容量）、
   `network/BusBandSyncPacket`（flags 列表用声明容量——同 codec 内 bands 列表用无参构造，
   自相矛盾）。攻击者须为恶意服务端，出主威胁模型，降卫生级；修法照搬 C→S 侧已统一的
   「超限=协议错误 DecoderException」口径。【核实】
2. **ScanSablePacket 无速率限制**：每包 enqueueWork 主线程遍历附近 Sable 子级全部已加载
   区块 BE 并做四元数变换，线速发包可持续吃主线程预算（TPS 劣化非 OOM）；原点/半径钳制/
   日志降级均已修，唯无每玩家冷却。【核实】
3. **SpeakerSettingsPacket / AmplifierSettingsPacket 缺会话成员校验**：Monitor/Radar 设置包
   都是「距离+会话」双检，这两个只检距离——128 格内任意玩家可改他人音响 gain/静音、切功放
   监听目标（数值有钳，纯恶扰面）。Speaker wire 侧 radius/gain 全程无钳为同源口径（BE 侧
   radius 无上界为上轮已知未修，见本节第 26 条）。【核实】
4. **ADD_BOOKMARK 无数量上限**（K10/P2-8 同族新实例）：每条随图 NBT 落盘并全量同步，
   30 秒可刷数万条（名 ≤32KB）造成存档膨胀 + 带宽放大。【核实】
5. **子图路由不校验 ownerNodeId 节点类型**：任意类型节点可被挂上隐藏子图并随档持久化
   （会话成员可把整段隐藏图塞进 CONST 节点；求值器/频道注册不进子图故无频道副作用，
   属 smuggling + 存档膨胀）。修 undo 需求时应仅 ENCAPSULATION 放行。【核实】
6. **ADD_CONN 引脚索引不验存在性**：fromPin/toPin 越界时连线照加（pinId=null）并 ACK——
   求值侧被守卫挡住不崩，但幽灵连线占 inputCache 槽、参与成环 BFS、随档保存。pinId 路径
   有 `<0` 拒绝，仅旧整数路径缺口。【核实】
7. **Monitor loadTypeSpecific 的 rs_in 键 Long.parseLong 无守卫**：畸形键抛 NFE 使整个 BE 被
   区块加载器丢弃 → 该监视器整图数据丢失（世界文件向量；写侧键恒为数字，纯读侧健壮性）。
   【核实】（二次核验通过）
8. **GraphMigration 对「未来版本」档位不设防**：ver > 当前版的档被原样交给 loadCurrent 按
   当前格式解读（降级加载静默漂移无告警）。升级阶梯本身健壮（顺序步进/防死循环/未知类型
   兜底）。【核实】

### 音频链

9. **NoteEvent.key 按有符号 byte 读回**：NBS key 128-255 翻为负 → 音符近乎无声且 step 趋近
   0 时声部近乎永占（MAX_VOICES 剥夺兜底内存不涨，满载时 ~45M 插值/s 在声音引擎线程）。
   注释明说 instrument/panning 按无符号读回，key 是漏网者。【核实】
10. **WSHAPE 曲线链随每批音符全量重发**：内容近乎不变的曲线以 20Hz × 每音响 × 每听者重复
    上线（4 条 64 点曲线 ≈ 2KB/包，对比稳态音符负载 ~60B 为 30 倍），客户端每包重烘 LUT。
    真实图 0-2 条时无害。【核实】
11. **引擎首次发声后永不下线**：STREAMING 通道空闲后仍持续泵送静音（~176KB/s direct
    ByteBuffer 分配 churn + AL buffer 入队/销毁循环，直到资源重载）。无泄漏，常驻小额成本。
    【核实】
12. **SongSync/SET_SONG 每次上传向其他在册编辑者全量重传曲目字节**：无内容指纹去重、无
    节流，两名共谋玩家交替上传即形成 1:(编辑者数−1) 重复下行流 + 服务端主线程 NbsSong.read
    解析。【核实】
13. **【上轮已知未修】首奏 Ogg 解码在 Netty 网络线程执行 + CscAudioEngine.mixer 静态字段非
    volatile**（良性数据竞争，最坏丢一次清除）。上轮 P3 原样。【核实】

### 求值核心 / tick 骨架

14. **音频频段每 tick 三重字符串键拼接**（bandKey/consumerKey/CONSUMED 游标键）：每频段读写
    每 tick 构造 3+ 个短命 String。【核实】
15. **BASIC/EMPTY_TABLE 求值档每 tick 丢弃式分配**：3×HashMap + 零值 SeatInputState（Monitor/
    Gauge/SpeedProxy 的零值包装可为常量）。【核实】
16. **AudioBands 游标/占用表无消费者级清理**：CONSUMED/OWNERS 键随「删建音频节点的次数」累积，
    仅 server stop 全清（对照 SignalBus 已有的 releaseChannel 纪律）。慢泄漏、量级小。【核实】
17. **broadcastFlipflopDiff 子图基线比较每 tick 整表拷贝**：无变化时也全量 HashMap 克隆
    （主图侧是零拷贝 equals 对照面）。【核实】
18. **FORMULA 坏脚本的错误路径日志急切求值**：debug 关闭时 e.getMessage() 与捕获链照付，
    每 tick × 每坏输出（雷达侧同形态 P2-16 已修，此处是错误路径专属）。【核实】
19. **op 路径一次性成本**：wouldCreateCycle 每 ADD_CONN 全量建邻接表（大图粘贴 O(E²)）、
    每 ADD_NODE rebuildNodeMap O(n)（批量 O(n²)）——网络事件路径 ≤ 人手速度，仅批量粘贴可感。
    【核实】
20. **解释器续算 containsLoop 用流递归**：spread 挂起脚本每 tick 续算时每语句层分配流管道。
    【核实】
21. **雷达语义门控自身每 tick 全量构建视图**：P1-7 修复后全量 NBT 只在语义变化时推，但门控
    判定每 tick 构建排序字符串键（T 个目标 ≈ T+3 个短命串 + 两次排序/雷达）。发送面干净，
    判定面有浪费。【核实】
22. **雷达空表档每 tick 3×HashMap + 扫描段集合重建**（targetOutNodes/AABB/validIds）——纯
    GC 压力卫生级。【核实】
23. **六宿主每 tick 双跑 recoverConflictedChannels**（主挂点 + 遗留挂点）：收敛批次有意保留
    （零行为变更纪律，javadoc 自知），O(N) 空转级——纪律注记，非缺陷。【核实】
24. **雷达工厂每次实例化打 INFO 日志**（双端，区块加载突发放大）+ Sensor 每 tick `ModList.isLoaded`
    + 2-3 个 JOML 小对象。卫生级。【核实】
25. **PT 孤儿预检盲区分支**：无源且零速（常态）时每 tick 6 向 getBlockEntity（100 台空闲
    PT ≈ 600 次查表/tick）。结构必要，可节流。【核实】
26. **【上轮已知未修】Speaker 半径无上界钳制（BE 侧 Math.max(1,rad) + wire 侧零钳制）与
    audioTransports 无按存活 MUSIC 剪枝**（功放侧同批已加 removeIf，音响漏掉；慢泄漏 +
    NBT 膨胀）。上轮 P3 原样。【核实】

### 渲染 / GUI

27. **添加节点菜单搜索态每帧 ~101×2 次 toLowerCase**（谓词内重复小写化，≈12k 串/秒）。
    【核实】
28. **【上轮已知未修】便携终端搜索激活时每帧 toLowerCase + 坐标串拼接重建列表**。上轮 P3
    原样。【核实】
29. **MLE 高亮渲染 token 有缓存、布局无缓存**：每可见行每帧 substring + 每段 prefixWidth
    二次 substring 与 O(前缀) 字宽扫描。可见行数受框高限制，成本有界。【核实】
30. **拖线预览目标域检测每帧全节点线性扫描**（dragTargetAudio 未走已有 spatialIndex；同文件
    hitNode/tryWirePinClick 均已走）。千节点图拖线时 O(n×pins)/帧。【核实】
31. **世界侧小渲染器每 BE 每帧若干 new + String.format 卫生族**：KineticGauge（Quaternionf×2/
    Matrix4f×2/ArrayList×2/每数值节点 String.format）、CncGearbox/Transmission/KineticShaft
    （各 ~5 处）、AmplifierComputer（Matrix4f）。RadarBlockEntityRenderer 复用范式为正面对照。
    【核实】
32. **NBS 双视图每帧每可见 tick notesAtTick new ArrayList**（最小缩放 ~170/tick）+ 传输条
    String.format 每帧。温和 churn。【核实】
33. **hitConn 删除连线命中点击成本注记**：O(E×steps) + 遮挡判定最坏 O(E×steps×N)，仅点击
    路径付费（大图点击一次卡 1-2ms），与画线同源的正确性保持（v1.2.6.1 修复），无正确性
    问题。【核实】

### 跨切面（线程 / 静态表 / 兼容）

34. **「Sable 物理线程」前提与 1.2.2 jar 内证据矛盾**【待核实】：对两个 Sable jar 全量二进制
    grep，Thread/ExecutorService 仅出现在客户端渲染调度器；物理链路（SubLevelPhysicsSystem
    → ServerSubLevel.prePhysicsTick → sable$physicsTick）反汇编显示同步直调，无线程移交。
    **若同线程成立**，下述 volatile 系列只是卫生问题；**若跨线程成立**（上轮与代码注释的
    口径），则升 P2 可见性缺陷。建议先用运行时断言（Thread 打点）钉死线程模型再决定去留。
    - 34a. **物理路径写出的派生输出字段仍非 volatile**（上轮 H-3 只修了 rawVel）：Sensor 的
      attitude*/forward*、ControlSeat 的 keyBits/mouseJoystick*/attitude*/forward*/blockYaw
      在 sable$physicsTick 写、游戏线程读，同线程=无害、跨线程=JMM 可见性缺口（组合姿态
      字段可能跨物理步撕裂）。【核实（字段与写读路径）；线程前提待核实】
    - 34b. **ControlSeatBlockEntitySable.savedLevel 非 volatile**：同一模式 4 类中唯一漏网
      （Sensor/Radar/Monitor 均 volatile）。【核实】
35. **ChannelEntry.refCount 非原子**：单人共享 JVM 下客户端 GUI 线程（编辑器直写共享表，
      设计内）与服务端线程并发 decrement/increment + 复制转发循环 → 丢失更新 → 频道提前
      摘除或计数泄漏（ServerStopping clear 兜底）。窗口极窄。【核实】
36. **BlobRegistry 清理只挂服务端 tick**：纯客户端实例（连专用服）的 S2C 中继路径本地累积
      PENDING/COMPLETED 且无周期清理，死条目可占满 MAX_ENTRIES → 新 blob 静默拒绝 + 内存
      滞留（30s 过期仅在同 key 访问时惰性生效）。主类 ServerStopping 清理清单亦不含
      BlobRegistry。【核实】
37. **TargetAssignment 以裸 BlockPos 为键，无维度隔离**：同服多维度同坐标雷达互相覆盖（对照
      EditSessionRegistry 用 GlobalPos 的正确示范）。【核实】
38. **【上轮已知未修】ControlSeatEntity 硬 import Sable 主类**（0730 review 条目 6：靠
      ModList 短路 + JVM 惰性解析能跑但脆弱）；**resolveSubLevel 缓存包装仍三连拷贝**
      （上轮 C-2 主体已收敛到 SablePoseHelper，残留 ~10 行 identical 包装 ×3）。【核实】
39. **NBS 导入先全量读内存后查大小上限**（Files.readAllBytes 再检 256KB；文件来源限定本地
      目录列表，纯卫生）。【核实】

---

## 四、上轮条目现状核验 / Disposition of Previous Findings

> 上轮 = [`tech-debt-review-2026-10-11.md`](tech-debt-review-2026-10-11.md)（基线 `e3e7866`），
> 另有标注 0730 = `reviews/2026-07-30-code-health-review.md`。✅ 已修 / 🔶 部分修 / ❌ 未修
> （未修且未进登记册的项，处置时建议按登记册规范补登记）。

| 上轮条目 | 现状 | 说明 |
|---|---|---|
| P0-1 三处解码器先分配后校验 | ✅ | 三处钳制在位（count 4096 / imgLen 1024² / len 30KB），DecoderException 口径统一，CHANGELOG 有登记 |
| P0-2 BlobRegistry 无界重组 + 跨玩家串包 | ✅ | 64 片/30KB、归属分键 fail-closed、256 条软上限、20 tick 回收；残余预算见 P2-2 |
| P1-1 ControlSeatInput 骑乘校验 | ✅ | 非骑乘者/位置不匹配整包忽略，静态 PLAYER_INPUTS 登出/停服双清 |
| P1-2 presence 身份伪造 | ✅ | UUID/名字服务端回填；发送者门缺失为本轮 P2-1 |
| P1-3 ScanSable 扫描 | 🔶 | 原点服务端权威/半径钳 1..128/日志降 DEBUG 已修；无节流残留（P3-2） |
| P1-4/P1-5 封装子图状态透传/时间戳冻结 | ✅ | inheritPortsFrom 收敛注入 + 每 tick setAudioTickStamp + subView 共享映射 |
| P1-6 公式递归无深度上限 | ✅ | MAX_PARSE_DEPTH + MAX_EXEC_DEPTH 双防线 |
| P1-7 雷达每 tick 全量 NBT | ✅ | 数据面拆分（RadarTargetsPacket 轻量通道）+ RadarSyncGate 语义门控；门控自身成本见 P3-21 |
| P1-8 flushPendingFullSync 只接 4/12 宿主 | ✅ | tickBody 骨架 ① 后无条件冲刷，12/12 |
| P2-1 O(E) 无缓存连线扫描 | ❌ | 本轮 P2-8 重新量化，未进登记册 |
| P2-8 频段上传上限 | ✅ | bands ≤64 解码层钳制 |
| P2-9 未知 op 静默丢弃 | ✅ | 未知 op 序号走 REJECT 不断连 |
| P2-10 EditSessionRegistry 生命周期 | ✅ | setRemoved→dropBlock（editors/editVersions/opLogs 全收）+ opLog 200 FIFO |
| P2-12/13 冲突自愈/flipflop 宿主覆盖 | ✅ | 12/12 主挂点；六宿主双跑为有意保留（P3-23） |
| P2-14/15/16 雷达双序列化/静态表泄漏/急切日志 | ✅ | saveTypeSpecific 单遍、CLIENT_RADARS 三路清理、isDebugEnabled 守卫 |
| P2-17 Monitor 红石逐条发包 | ✅ | redstonePushDiff 变化才推 |
| P2-18 SpeedProxy 缺步 | ✅ | 骨架 + L 档声明，javadoc 自述补齐 |
| K8 全图广播无节流 | ✅/🔶 | 发送侧 dirty 门控已修（sameContent）；捕获侧全量重建为本轮 P2-9 |
| K9 逐 tick 分配总账 | ❌ | 本轮 P2-10 量化补全，未进登记册 |
| K10 数量上限仅客户端 | ❌ | 现状维持（登记册『节点准入名单仅客户端把守』条目已注明同批口径）；ADD_BOOKMARK 为同族新实例（P3-4） |
| K15 各类上界 | ✅ | 槽位 ≤9/帧 ≤64/指令栈 64/曲目 256KB 在位；DELAY 参数是漏网者（P2-11） |
| K16 GraphJoin 无所有权模型 | ❌ | 设计取舍维持（已登记口径）；无 Sable 时距离检查退化为仅 XZ 平面忽略 Y |
| K17/K21 无鉴权/无限流 | 🔶 | op 链与会话门已补齐；presence/ScanSable/GraphSaveRequest 三处限流缺口为本轮 P2-1/P3-2 |
| K23 十二份 tick 体未收敛 | ✅ | GraphHost.tickBody + TickHooks 单一骨架，十二宿主前段化 |
| K26 SableReflection 样板 | ✅ | 反射层整体退役（P2-19），全库 Class.forName 对 Sable 归零 |
| F13 DEBUG_SIGNAL_GEN 无限 WARN | ✅ | 失败态按节点缓存、告警一次 |
| F15 未知 NodeType 旧档 | ✅ | CONST 兜底 + WARN；op 路径缺口为本轮 P1-1 |
| 0730 H-3 rawVelX/Y/Z volatile | ✅ | 已修；派生输出字段残留见 P3-34a |
| 0730 C-1 Sensor 缺 savedLevel | ✅ | 已修并统一四类；ControlSeat 侧 savedLevel 非 volatile 见 P3-34b |
| 0730 H-4 反射类路径漂移 | ✅ | 随反射层退役消除 |
| 0730 C-2 resolveSubLevel 三连拷贝 | ✅/🔶 | 主体收敛到 SablePoseHelper；~10 行包装 ×3 残留（P3-38） |
| 0730 #5 initialSubYaw 不持久化 | ✅ | saveTypeSpecific/loadTypeSpecific 落地 |
| 0730 #6 ControlSeatEntity 硬 import Sable | ❌ | 原样（P3-38） |
| 上轮 P3 GUI：MonitorBER 像素 quad | ❌ | 本轮 P1-4 上调（新增两个加重因子） |
| 上轮 P3 GUI：lockedNodes 每帧分配 | ❌ | 并入本轮 P2-14 |
| 上轮 P3：终端 toLowerCase / Speaker radius 上界 / audioTransports 剪枝 / Ogg netty 解码 / mixer 非 volatile | ❌ | P3-28 / P3-26 / P3-13 原样重列 |
| v1.2.6 登记待修：AMP 曲线 x 轴 + WSHAPE 量化阶梯 | ✅ | v1.2.6「曲线双节点」批即该修复（控制点交付 + 恒等真旁路，代码现状吻合） |

---

## 五、已核实为干净的面 / Verified Clean

**安全面**：
- **C→S 解码全干净**：全部 14 个客户端可达载荷逐包核验「先钳制后分配」——定长标量包无
  分配；带计数分配的四包（presence/editOp/blobData/busBandUpload）均有 DecoderException
  钳制；唯一未钳制的 bands 列表用无参构造 + 逐条读（超缓冲即断连）。
- **方向注册与线程纪律**：AllPackets 21 项逐一核对，无 C→S 包注册到客户端执行；除音频两包
  （网络线程直处理，书面论证成立）外全部 enqueueWork。
- **身份伪造面封死**：op 的 actor UUID、presence 的 UUID/名字均服务端回填。
- **会话生命周期完整**：登出 / 拆方块 / 停服三路回收 + opLog FIFO 上界。
- **op 公共闸门在位**：未知 op REJECT、K15 越界预检、128 格距离（Sable 感知）、会话成员门、
  actor 覆写、blob 归属作用域。
- **扳手与战利品 12/12 全覆盖**（onSneakWrenched + saveToItem 模板 + loot_table 一一对应）；
  BE save/load 公共段单点对称。
- **命令面为空**（无自定义命令注册，无命令权限面）。
- **文件 IO 无路径穿越**（sanitizeFileName 剥离分隔符，全部固定 config/导出目录，tick/帧
  路径零 IO）；Mixin 五点全部实证锚定（含 Sable jar 反汇编验证目标存在 + mods.toml versionRange
  与 jar 实测一致 + `required:false` 二保）；客户端类仅出现在 enqueueWork lambda 内（专用
  服务端安全）；rcon-*.ps1 为开发工具、src/ 零引用。

**性能面**：
- **公式解析缓存契约成立**（ensureScriptParsed 单一真相源，无每 tick 重解析旁路）；
  **重编译风暴已修**（代数比对，一 tick 至多一次，批量 op 合并；MOVE/SET_PARAM 未变守卫在位）；
  **撤销/redo/opLog 全部有界**；LOOP/FUSE/GATE 等状态节点均有界且按存活剪除；拓扑序与输入
  查表全 O(1)/缓存。
- **发送门控族一致**：eval 快照（sameContent）、flipflop 差分、BusBandSync（哈希门控）、
  audio flags（stamp）、Monitor 红石（值缓存）、KineticGauge（三浮点缓存）、PT 代理（双值
  差分）、雷达（语义门控）——「变化才发」纪律全面执行。
- **tick 内无 IO/注册表重查**；全部分区 Block 的 ticker 服务端专用（客户端无 ticking BE）；
  graph/ 包无锁、热路径无日志。
- **渲染侧缓存骨架健全**：主题色实时取值无分配（P2-23 已根除）、MLE 视觉行/token 缓存、
  formulaIssues 首帧校验、拓扑版本缓存、HudRenderer ByteBufferBuilder 2MB 池复用；打字/
  击键不重建控件树（除已登记的公式框条目）；节流到位（拖拽 50ms/布局 100ms/presence 120ms）；
  GUI 不轮询服务端；渲染路径无每帧日志。
- **音频资源上限在位**：MAX_VOICES=1024 + 剥夺最轻、shapedCache 32 项 LRU、SampleBank 16
  音色常驻；OpenAL 生命周期成对（惰性挂接/自愈重挂/失败复位），断线/世界卸载无客户端泄漏；
  SpectrumSync 16B/包变化才发无放大；AmplifierSettings 的监听目标校验到位。
- **静态表清理接线**：SignalBus/AudioBands ServerStopping 全清、PLAYER_INPUTS 登出清、
  EditSessionRegistry 三路回收、CLIENT_RADARS 修复为真、tempViewByPos LoggingOut 清。

---

## 六、建议处置顺序 / Suggested Disposition Order（供决策，未动代码）

1. **P1-1 null NodeType REJECT**（一行守卫级）——REJECT 纪律缺口 + 日志刷盘面，最小改动。
2. **音频下发上限 + 半径剔除**（P1-2）——事件数/包与事件数/秒/音响双上限 + radius 粗剔除。
3. **ADD_NODE id 唯一性**（P2-3）——与登记册『节点准入名单仅客户端把守』（K10 同批口径）
   合并立案，一次收口 op 链执行层的三个「仅客户端把守点」。
4. **读侧钳制三连**（P2-4/P2-5/P3-7）——icount/scanRange/rs_in，建议做成「写读对称」清单
   批（含 Speaker radius 读侧）。
5. **presence 发送者门**（P2-1）——一行成员校验对齐全族口径。
6. **NaN/Inf 入点拒收**（P2-6）——增益/曲线 isFinite 守卫，客户端 scheduleAtFrame 兜底。
7. **BlobRegistry 预算收紧**（P2-2）——每 owner 字节上限或 MAX_ENTRIES 按业务量级（顺带
   纯客户端清理缺口 P3-36 与注释失真）。
8. **渲染像素级两大热点**（P1-3/P1-4）——纹理化/脏标记/mesh 缓存，建议 GUI 拆分线完成后
   单批（与 1.3.0 清理线的「行为变更另批」口径一致）。
9. **逐 tick 分配总账 + O(E) 扫描 + 快照捕获侧重排**（P2-8/9/10，K9/P2-1 老账）——随 eval/
   tick 后续批次，量化输入已齐。
10. **Sable 线程模型打点定案**（P3-34）——一次性运行时断言，决定 volatile 系列补齐或删除
    防御性标注；顺带 ChannelEntry.refCount 原子化（P3-35）。
11. 其余 P2（GUI 每帧重建族 P2-14..20、tick 基线税 P2-12/13、DELAY 上界 P2-11）与 P3 族按
    所属批次消化；「上轮已知未修」七项处置时按登记册规范补登记。

---

*审查方法补记：七个分区由子代理并行扫查（并发受限、分批执行），主审对全部 P1 与 P2 头条
指控逐条二次读码核验（decode/applyOp/构造器三段、advance 展开/tempo/chunk 扇出、presence
handler 全文、BlobRegistry 常量算术、NaN 钳制与 mixer 判定、rs_in parseLong 均复核通过）；
分区报告与本文件的差异以二次核验后的表述为准。*
