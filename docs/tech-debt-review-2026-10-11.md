# 全仓技术债审查（2026-10-11）/ Full-Repo Tech Debt Review — 2026-10-11

> **状态**：🔶 **审查已完成，所列缺陷待处置**。审查基线 `e3e7866`（v1.2.6 WIP，工作树干净）；
> 方法：六个分区并行扫查（网络 / 求值核心 / 宿主方块 / GUI / 依赖面 / 工程面），每条发现经读码核实，
> P0/P1 头部指控另行二次核验。**本文只记录问题与建议，不改源码。**
> **Status**: 🔶 **review complete, findings pending disposition.** Baseline `e3e7866` (v1.2.6 WIP,
> clean tree); method: six parallel read-only area sweeps with per-finding code verification, and a
> second manual pass on every P0/P1 headline claim. **This document records findings only — no
> source changes.**
> **查重基准 / Dedup baselines**：[`cross-be-latency-observation.md`](cross-be-latency-observation.md)
> §九（短板清单，本文 K1–K23 / K27 / K28 的登记源）、[`known-open-issues.md`](known-open-issues.md)
> （已定性待办，本文不重复登记其条目）、2026-10-03 外部审计（v1.2.5.2 时点，存档于仓库外；
> 其结构债结论如 `GraphEditor` 体量、`GraphEvaluator.eval` 最大方法、SableReflection 样板重复，
> 本文以 K24–K26 引用不再展开）。
> **快照口径 / Snapshot**：本文是 `e3e7866` 时点的快照；修复逐条落地时以
> [`known-open-issues.md`](known-open-issues.md) 与 [`CHANGELOG.md`](../CHANGELOG.md) 登记为权威，
> 本文不逐条回标。
> **标注约定**：【核实】= 读本仓代码 / 实测确认；【待核实】= 有证据但未闭合。

---

## 〇、结论摘要 / Summary

这轮大改（音频批次、注入端口重构、频道退役统一）在它触碰过的面上质量守住了——扳手/战利品表
12/12 全覆盖、REJECT 通路收口、音频网络线程改动基本稳当、`RuntimeState` NBT 完全对称、
五个 Mixin 与全部反射目标逐项核实在位。但外围出现了一类系统性的新债：

1. **服务端攻击面集中在"解码层"，出现两个 P0**：三处"先分配后校验"的数组分配 + `BlobRegistry`
   的分片重组，任意已连接玩家可单包打爆服务端内存。这是全仓当前唯一"一击致命"的面，修复成本极低
   （钳制一行级）。
2. **"注入清单手工维护"模式漏了三个状态**：封装子图求值器缺 runtimeState / completedNodeId /
   audioTickStamp 透传，封装内时序节点与音频链的行为是坏的——这正是已修过的 v2"指令永不停止"
   事故的子图翻版。
3. **守卫/纪律有"绕行口"**：NBS 编辑器绕过 temp-id 挂起漏斗、客户端编辑器直写 `SignalBus`
   全局表绕过 owner 门控、节点准入名单服务端不校验——CLAUDE.md 写下的规则在新代码里有执行漏洞。
4. **tick 编排面的两线分叉**被完整量化（15 步 × 12 宿主偏差矩阵，§六）：全量同步冲刷只接了 4 宿主、
   冲突自愈只接了 6 宿主、flipflop 广播缺 2 宿主——K23 收敛的量化输入已经齐了。
5. **雷达是债密度最高单点**：每 tick 全量 NBT 推送（门控失效）、整图双序列化、客户端静态表泄漏、
   急切 debug 日志，叠在一个 1002 行五职责共写的类里。

| 严重度 / Severity | 数量 / Count | 说明 / Note |
|---|---|---|
| P0 | 2 | 可被单个伪造包打崩服务端 / one forged packet kills the server |
| P1 | 10 | 明确 bug / 高危债（含数据丢失路径 3 条）/ confirmed bugs, 3 data-loss paths |
| P2 | 19 | 结构性债 / 明确的静默错误行为 |
| P3 | 24 | 卫生 / 潜伏 / UX |
| 查重复核 | 30 项 | 4 项恶化或扩大证据，其余原样成立 |

---

## 一、P0（单包打崩服务端，建议立即修）/ P0: One-Packet Server Killers

### P0-1 三处解码器"先分配后校验"：客户端可控长度直接 new 数组

**位置**：`network/GraphPresencePacket`（decode 的 `new int[count]`）、`network/GraphEditOpPacket`
（imageData 的 `new int[imgLen]`——同 codec 里 bandCount 有 >64→0 的钳制，imageData 没有）、
`network/BlobDataPacket`（`new byte[len]`）。

**机制**：三处全部在读取任何数据字节之前按客户端声明的 varint 完成分配；varint 可声明至 2³¹-1，
线缆上限只约束实际跟随后面的字节数，不约束声明的 count。【核实】

**影响**：单个伪造包 → 网络线程一次分配最大 ~2GB/8GB → OutOfMemoryError（Error 不可捕获），
整个服务器实例瘫痪。任何已连接玩家零门槛触发。

**查重**：新发现。与 K15 不同层——K15 是 OpExecutor 槽位/帧索引的**渐近**放大，这里是 codec 层
**即时**分配；presence/blob 两处连编辑 op 都不是。

### P0-2 BlobRegistry：totalChunks 无上限 + 全局 blobId 命名空间跨玩家串包

**位置**：`network/BlobRegistry`（PendingBlob 构造 `new byte[pkt.totalChunks()][]`，守卫在构造之后
才生效）；blobId 由客户端自选，PENDING/COMPLETED 为全局表，`poll(blobRefId)` 不校验提交者身份。【核实】

**影响**：(a) 已入会话的恶意编辑者一个分片包 → 引用数组数 GB，worker 线程 OOM（P0）；
(b) 30 秒窗口内可开任意多 pending 占内存；(c) 玩家 A 用自选 blobId 预置字节，玩家 B 的 SET_SONG
按 id poll 拿到 A 的字节——同会话数据投毒（P1，SET_SONG 有 1MB 上限与 NBS 解析兜底缓解）。

**查重**：新发现。同族面：客户端侧 `BlobRegistry` 无 cleanup 调用（服务端有 Post 钩子全覆盖），
未再访问的条目整场滞留。

---

## 二、P1（明确 bug / 高危债）/ P1: Confirmed Bugs & High-Risk Debt

### 身份与输入信任（网络面）

**P1-1 座位控制输入无骑乘者校验**——`network/ControlSeatInputPacket` 仅有距离校验（128 格），
`ControlSeatBlockEntity` 无 rider 概念；玩家 B 可每 tick 覆盖玩家 A 的按键位掩码/视角/操纵杆，
完全劫持驾驶；对无人座位可遥控机器。【核实】新发现（K17 只登记了存在性包伪造）。

**P1-2 presence 中继转发客户端自带 UUID/名字（K17 恶化）**——`GraphPresencePacket` javadoc 两处
声明"UUID 始终取自 ServerPlayer.getUUID()，防止伪造"，但实现是原样转发入站包的 player/playerName
字段，senderUUID 仅用于 skip-self。**文档与实现相反**，伪造门槛比 K17 描述更低（连编辑者身份都
不需要）。【核实】

**P1-3 ScanSablePacket 无距离校验、坐标与半径全信任客户端**——唯一一个信任客户端坐标做扫描原点
的包；scanRange 平方还有 int 溢出（超大值反而全通过）；每次扫描两条 INFO 日志。任意玩家任意坐标
反复触发子级 BE 全量遍历 + 日志刷盘。

### 时序与状态透传（求值核心）

**P1-4 封装子图缺 runtimeState / completedNodeId 透传**——`graph/GraphEvaluator` ENCAPSULATION
分支的注入清单（encoderView→speakerSink→audioTickStamp 共 11 项）不含 `restoreSubState`
（子求值器 runtimeState 恒 null）也不含 `setCompletedNodeId`；`RuntimeState.SubState` 本身也没有
nodeEdge 槽。后果：封装内 MOVE/ROTATE/WAIT 在触发引脚保持高电平时**每 tick 重复入队一条新指令**
（v2"指令永不停止"事故的子图翻版）；封装内 MUSIC 每 tick 重头播/重跳；done 引脚在封装内永不
出现。【核实】

**P1-5 子求值器 audioTickStamp 冻结在创建 tick**——顶层求值器每 tick 被宿主刷新时间戳，但子求值器
被 `subEvaluators` 缓存跨 tick 复用、只在创建时赋值一次。AudioBands 新鲜度判据（stamp ≥ now-1）用
冻结的 now：封装内发布的音频对外部消费者 1–2 tick 后即判过期（**封装内→外静音**）；封装内消费永不
判陈旧（发布方拆除后**幽灵长音不停**）；冲突旗标永不越窗。

**P1-6 FORMULA 递归无深度上限，StackOverflowError 崩服 + 崩溃循环**——`graph/FormulaParser`
（parseStatement/parseBody 互相递归）与 `graph/FormulaInterpreter`（按 AST 深度递归）均无深度守卫；
eval 分支的 `catch (Exception)` 拦不住 Error；SO 发生在 `cachedScript` 赋值之前 → 下个 tick 重解析
再 SO，**崩溃循环**直到 NBT 被外部改掉。三条暴露路径：服务端求值、SET_FORMULA op（无 try）、
`GraphMigration.migrateV3toV4` **世界加载时**（坏档/恶意档 → 区块加载崩溃循环）。

### 同步与守卫绕行（宿主面 / GUI）

**P1-7 雷达目标门控失效：有目标移动时每 tick 推全量 BE NBT**——`blocks/RadarBlockEntity` 推送门控
是 `targets.equals(lastPushedTargets)`，而 TargetRecord 含 x/y/z 坐标逐字段比较，目标（玩家/生物）
几乎每 tick 都在动 → 门控形同虚设 → 每 tick `getUpdateTag` = saveAdditional 全量 NBT（整图+设置+
目标表）下发全部追踪客户端。代码注释自述防"编辑器图反复重建"，但门控字段选错了量化口径。

**P1-8 requestFullSync 冲刷只接了 4/12 宿主**——`blocks/EditSessionRegistry` 的 BUS_IN 改名路径
注释声称"补齐到全部宿主"，但 `flushPendingFullSync` 调用点只有 CNC 齿轮箱/可变速器/动力仪表/显示器
四处；其余八个继承线宿主的 `needsFullSync` 置位后**永远挂起**（写后即死）。后果：多人下 BUS_IN
改名后，未开编辑器的客户端频道名/频段列表无限期陈旧。

**P1-9 客户端编辑器直写 SignalBus 绕过 owner 门控**——`GraphEditor.deleteSelectedNodes`、
`GraphBusEditor.commitBusBox/releaseOldBusName/syncBusBands` 直接调 `SignalBus.clearBus/registerBands`
（无 owner 检查）。known-open-issues 条目「频道释放依赖快照路径」的"确证离开→退役"统一只收编了
`releaseChannel` 一处，这三处是统一之外的裸写口。单人/局域网（同 JVM）下：客户端删一个名叫
"alpha" 的 BUS_OUT 只查了**同图**重名，若另一方块持有 "alpha"，其频道定义被抹掉后对方宿主按
"空定义"收敛 → **对方 BUS_IN 的连线被永久剪掉**。

**P1-10 NBS 编辑器 op 出口绕过 temp-id 挂起漏斗**——`client/NbsEditorScreen.getEditor()` 写死
`return null`、`sendOp` 直发；类注释自称"像素编辑器同款管线"，但像素编辑器为防服务端静默丢弃专门
做了出口收口（getEditor 路由回来源 GraphEditor）。后果：新增 MUSIC 节点后一个 RTT 内打开 NBS
编辑器保存，SET_SONG 携带的还是未 ACK 的临时 id → 服务端 findNode 落空**静默丢弃，曲目丢失**
——绕过的正是为 known-open-issues 条目「temp-id 挂起队列随关屏丢弃」的窗口修建的守卫。【核实】

---

## 三、P2（结构性债 / 静默错误行为）/ P2: Structural & Silent-Misbehaviour Debt

### 求值核心

| # | 发现 | 位置 / 机制 | 影响 |
|---|---|---|---|
| P2-1 | 求值热路径 O(E) 全连线扫描 ×4 | `GraphEvaluator` BUS_OUT 分支与 ENCAPSULATION 外部输入注入、`NodeGraph.isAudioWired` / `hasAudioSinkConnection` 每频段每 tick 遍历 connections 全表 | 大图下每 tick 多次线性扫描；`transferPinVersion` 版本化缓存范式仓内已有但这几处未用；绕过 K22 不可观测 |
| P2-2 | eval 巨型 switch 无 default、无穷尽性测试 | 新增 NodeType 漏 case 时 `o = node.outputValues` 被原样再发布——**静默输出陈旧值**而非 0 | 与 NodeCategory 的启动即炸形成不对称：最危险的错误最安静 |
| P2-3 | pidState 魔法偏移键 + SubState 缺 nodeEdge 槽 | `id+100000/+200000/+300000` 承载 5 种语义，真相散在 eval 分支与 aliveStateKeys 注释 | 每加时序节点要人肉对齐三处；漂移即泄漏或状态互踩（P1-4 的结构根因之一） |
| P2-4 | 注入端口重构收尾：核心仍是静态单例 + 默认端口伪造业务值 | FormulaCompute/OpExecutor 静态可变态（预算、去重表、blobStore）；GraphEvaluator 匿名 `AudioBandsPort` 默认实现 `publish` 返回 true | 违反"禁伪造兜底"纪律（未接线宿主上 AUDIO_OUT 永远"发布成功"、冲突恒 false）；测试隔离只能串行 |
| P2-5 | PRIVATE_OUT 浮点路径缺空名守卫 | BUS_OUT 有 `isEmpty` 早退，PRIVATE_OUT 浮点 put 没有；SET_DISPLAY_TEXT 会把空名同步成空 signalName | 空 "" 幽灵频道：未命名节点互相串音，释放路径清不掉 |
| P2-6 | REMOVE_NODE 撤销恢复丢 song 与 subGraph | `OpExecutor.restoreNodeFromNbt` 恢复清单不含 `song`（MUSIC 曲目）与 `subGraph`（封装内容） | Ctrl+Z 看似成功，整段封装/整首曲目**静默丢失** |
| P2-7 | 节点准入名单仅客户端把守（K10 恶化） | `BlockNodeAllowances` 只被客户端 Screen 引用；服务端 op 链无校验 | 改包客户端可 ADD_NODE 任意类型到任意宿主；K10（数量上限）之外的第二个仅客户端把守点 |

### 网络 / 会话

| # | 发现 | 位置 / 机制 | 影响 |
|---|---|---|---|
| P2-8 | 频段上传无数量上限，与 op 路径不对称 | `BusBandUploadPacket` 的 list 无 count 校验（op 路径 bandCount 有 64 钳制），直入 reconcileBands → 图 NBT **持久化** | 会话成员可把数万条频段名写进权威图并落盘（存档膨胀）；叠加 K28 残留②该路径不 setChanged |
| P2-9 | OpType ordinal 无界解码 | `OpType.values()[readVarInt()]`，越界 → AIOOBE | NeoForge 断开该连接（连接级隔离，不到 P1），应优雅拒绝 |
| P2-10 | EditSessionRegistry 生命周期缺口 | `leave/leaveAll` 只清 editors；`editVersions`（GlobalPos→Long）与 `opLogs`（每图 ≤200 条 op，含像素帧 int[] 引用）**只在小停机清空**；方块拆除不回收会话 | 跨会话慢泄漏 + 幽灵编辑者集合（op 广播发给没开界面的人）；opLog 目前零读取方 |
| P2-11 | 丢弃路径 INFO 日志放大（K21 表现面） | GraphEditOpPacket 的 out-of-range / not-editor 分支每包一条 INFO | 配合 K21 无限流 → 日志刷盘/磁盘增长 |

### 宿主方块

| # | 发现 | 位置 / 机制 | 影响 |
|---|---|---|---|
| P2-12 | 六宿主从不跑 recoverConflictedChannels | Monitor/KineticGauge/SpeedProxy/Speaker/CncGearbox/Transmission 全无调用 | 组合线与显示类宿主的频道冲突**永不自愈**（接管/超时回收只存在于该方法），与继承线行为不一致 |
| P2-13 | CNC 齿轮箱/可变速器不 broadcastFlipflopDiff | 两宿主用全状态档求值（flipflop 真实演进）但无广播调用 | 图内 GATE/T_FLIPFLOP/LATCH 状态永不同步到客户端，编辑器徽标死值 |
| P2-14 | Radar saveAdditional 双写公共段 | 先 super（SyncedGraphBlockEntity 钩子）又手写 graph/runtime 覆盖同名键 | 每次导出整图序列化两遍；叠加 P1-7 每 tick 支付双重成本 |
| P2-15 | 客户端 CLIENT_RADARS 静态表泄漏 | 只在 setRemoved 移除，未接 onChunkUnloaded/LoggingOut | 区块卸载/换世界累积死 BE（持 Level 引用）；锁定 UI 遍历幽灵雷达 |
| P2-16 | 雷达每 tick 急切 debug 日志 | 三段 LOGGER.debug 实参（含逐实体 sqrt+装箱）在调用前求值 | 日志关闭也照付；雷达常开+实体多时纯浪费热点 |
| P2-17 | Monitor 每 tick 每红石输入无去重发包 | 不比较上次推送值，N 条链路 × 20 包/秒恒发 | 同族已有去重样板（KineticGauge pushReadoutIfChanged）未复用 |
| P2-18 | SpeedProxy tick 缺 checkGraphChanged 与 setChanged | 图 generation 变化无人查；运行态不主动落盘（12 宿主唯一） | 图内**新增** REDSTONE_IN/OUT 节点静默失效直到区块重载；运行态持久性弱于所有宿主 |

### 依赖面（Create / Sable）

> 依赖目标核实基线：运行时 jar（Sable 1.2.2 + companion 1.6.0、Create 6.0.10）与上游源码抽查
> （Sable 2.0.3、Create 6.0.11）。**五个 Mixin 与全部反射/编译期目标逐项签名一致，无现役漂移**。

| # | 发现 | 位置 / 机制 | 影响 |
|---|---|---|---|
| P2-19 | SableReflection 558 行反射层整体可退役 | javadoc 理由"编译期无 Sable 也能编译"已不成立：SableAccess 等已直接 import Sable 类，build.gradle 把 libs/ 全挂 compileOnly；反射当初的真实障碍（ClientLevel 重载触发 dist cleaner）已被编译期桥解决 | 只剩维护成本；四阶段降级语义无消费者 |
| P2-20 | subTransformCache 位置键缓存永不失效 | `network/SablePacketHelper` 以"原点坐标"为 key、全仓无 remove/clear | 结构**原地旋转**时位置不变、四元数已变 → 终端扫描持续用旧朝向（静默错误）+ 条目永久累积 |
| P2-21 | Sable 依赖零声明 | build.gradle 无坐标、mods.toml 无条目、版本只由 libs/ jar 文件名隐含 | 版本漂移（用户装 Sable 2.x）无任何声明可触发诊断；四条腿集成深度（mixin/标签/API BE/HELPER 直连）与声明缺失不匹配 |

### GUI

| # | 发现 | 位置 / 机制 | 影响 |
|---|---|---|---|
| P2-22 | 七编辑器 + 终端的重复脚手架（重复面已量化） | Host 样板回归 ×2（Pixel/NBS 未继承 AbstractGraphScreen，各写 10 方法）、命中助手 5 份、手绘按钮 4 份、滚动条几何 6 份、双击打开子编辑器算法 2 份、NBS 顶栏 render/click 步进双写、取色器守卫链 3 份 | AbstractGraphScreen 想消灭的同构债在七个编辑器上重新积累；几何失步即按错按钮 |
| P2-23 | 运行时可改主题色被 static final 固化 | PixelEditorScreen/PixelEditorFrameStrip/PixelEditorToolRail/NbsEditorScreen 类加载时捕获 `NodeRenderer.PBG()` 等 | 编辑器设置里改配色后，这些屏沿用旧色混新色直到重启游戏 |

---

## 四、P3（卫生 / 潜伏 / UX）/ P3: Hygiene, Latent, UX

**求值与公式**：MOVE/ROTATE/WAIT 分支的无效 if 残留与 FORMULA 输出循环死守卫（`oi < size` 在三元
求值前已越界，靠异常兜底）；DEBUG_SIGNAL_GEN 编译失败每 tick 重编译 + WARN 无限频；顶层 `continue`
逃出 exec 被当异常吞成整节点清零（对照 break 是 lenient 忽略）；未知 NodeType 静默降级 CONST 无日志
（未来删枚举即静默丢节点）；busConflict 持久化 vs audioConflict 不持久化口径相反；`RuntimeState.load`
坏键 NumberFormatException 直接炸区块加载（对照 GraphNode/NbsSong 均有坏档兜底）；`NbsSong.MAX_BYTES`
临时 1024KB 的 TODO(v1.2.6-test) 未回收（4× 既定上限默认转正）；LATCH/T_FLIPFLOP 参数引脚覆盖时
状态写进克隆数组被丢弃（未覆盖时写穿原数组，同语义两种行为）；Speaker 的 audioTransports 无
removeIf 修剪（不对称）；NodeGraph.save 不持久化 nextLayerIndex（重载后图层游标重置）。

**网络**：RadarSettingsPacket 的 scanMode 未钳制（displayStyle/lockMode 有）；NoteEventPacket 网络
线程路径的首奏 Ogg 解码在 Netty 线程上执行（毫秒级停顿）+ mixer 非 volatile（良性数据竞争，锁纪律
整体核实无误）。

**宿主**：Speaker 不 refreshInputs 不 writeOutputs（音响图红石面残缺且无提示）；Radar/Blueprint/
ProgramComputer 在 loadAdditional 里 sendBlockUpdated（加载期冗余包，三种口径之一）；12 份
onSneakWrenched 逐字复制（本次核实 12/12 一致无漏网，但下次变更仍是 12 处手工同步）；Speaker 半径
无上界钳制（注释写 1–4096 但无 max）；TargetAssignment 区块卸载不清理；Radar 把每 tick 重建的瞬态
activeTargets 写进 NBT。

**依赖**：SpeedProxy 对 Create 公开 API（SpeedControllerBlockEntity.targetSpeed 是 public）走反射
——Create 是 mandatory 依赖，纯多余间接 + 失败每 tick error 刷日志；SableReflection 的 BBox 访问器
失败返回 0（与合法 0 不可区分）且整组是死代码，RadarBlockEntity.tryAddSubEntity 整个方法无调用点
且内含无缓存反射；ControlSeatCameraMixin 目标是 Sable 内部 mixinhelper 包（非 api，required:false
静默失效——K20 的具体化）；create_version_range=[6.0.10,) 开口向上，仅核验过 6.0.10/6.0.11 两个点
（Sable 上游自用收敛式范围）；Sable 官方 Maven 实测可拉到锁定的 1.2.2，libs/ 13.8MB 二进制有替代
渠道。

**GUI**：渲染/每帧路径分配（NodeRenderer 探针每帧 new+sort 最重、GraphEditor renderBg 的
lockedNodes 无条件 new、MonitorBER 每像素每帧 4 顶点 quad 无缓存、终端每帧 toLowerCase+字符串
拼接）；取色器键盘模态三种口径 + ESC 语义跨屏不一致 + 裸 GLFW 键码魔数散布；onClose 三种变体
（NbsEditor 不查 BE 存活可能 setScreen 回旧实例）、NBS layerName 失焦丢字；i18n 漏网 4 处（中文
直发聊天栏/actionbar/hint、同屏中英混排）；双击命中块自造节点尺寸公式与 NodeRenderer 平行
（WIDE_NW=240 漂移潜伏【待核实 IMAGE/MUSIC 是否在宽名单内】）；像素笔划 mouseMoved+mouseDragged
双路径重复施色（opacity<1 时笔迹偏深【待核实】）。

**工程面**：src/main TODO 计数 0→1（即 NbsSong 临时上限；外部审计时点为 0）；formula-pin-render-
tech-debt.md 状态头未随 v1.2.4 收束（stable pinId 已落地仍称"长线方案"——K19 新实例）；
cross-be-latency-observation.md §6.1 的"改动在工作区、尚未提交"括注已过期（该修正已于 `75161ed`
提交）；`.zcode/` 忽略只在本地 `.git/info/exclude`（换机器即失效）；根目录 META-INF/jarjar 与
assets/create 是被正确忽略的 dev 残留（不进版本库，仅观感）；CI 在 K29 之上还叠三个维度：无 push
触发（main 直推永不验证）、只跑 test 不跑 build（LICENSE 打包/版本注入不在防线内）、无静态分析无
windows 矩阵；REJECT 服务端发射侧无直接测试（消费端有 RejectReceiptTest 锚）；colorpicker 纯逻辑
（ColorUtils/RecentColors）与 TargetAssignment 零测试；音频客户端三件套（CscAudioEngine/
CscAudioStream/SampleBank）零直测——headless 结构性缺口，混音核心 AudioMixer 覆盖良好。

---

## 五、查重复核结果（K1–K29）/ Dedup Cross-Check

**恶化 / 新证据（4 项）**：
- **K17**（存在性包无鉴权）→ P1-2：javadoc 与实现相反，伪造门槛更低。
- **K10**（上限仅客户端）→ P2-7：准入名单是第二个仅客户端把守点。
- **K15**（越界索引无上界）→ 证据面扩大：SET_HOTBAR_ITEM 的 `slot+1` 数组、parseCtrlPoints 点数
  无上限、Speaker 半径无上界（与 P0-1 的 codec 层不同层）。
- **K9**（双克隆+装箱）→ 新同族证据：ENCAP 分支每 tick `new float[nOut]`、每 ENCAP I/O 每 tick
  `new float[]{}`、FORMULA 每 tick `new HashMap` env。

**原样成立、无恶化（抽样实测确认）**：K1（12 宿主全字面量，Speaker 又一实例）、K3（Kahn 只遍历
有序表）、K4（三档入口，雷达确认空表档）、K5（游标去重仍只在音频局部）、K6（`deadlineExhausted`
仍零调用点；预算接线本身已修好：钳位/热重载/pre-load 回退齐全）、K7（挂起/出错结果仍无条件进去重
缓存，机制已精确定位到 AST 路径三出口）、K14（内建函数仍 23 个）、K20（无启动期自检，但 5 个注入
点全部逐项核实在位）、K22（全仓仍无耗时埋点）、K23（12 份 tick 体未收敛，偏差矩阵已产出见 §六）、
K24（GraphEditor 现 4941 行，轻微增长）、K25（无新 GUI 自动化测试，但僵尸实例地基覆盖面在扩大）、
K26（SableReflection 样板仍在且可整体退役，见 P2-19）、K28（两条残留均核实成立：BAND_REGISTRY
退役条目无终局清理、频段上传不 setChanged）、K29（CI 仍 PR-only）。

**known-open-issues 对照**：条目「temp-id 挂起队列随关屏丢弃」——P1-10 是**同族新洞**（NBS 编辑器
绕过守卫，不是守卫自身窗口）；条目「频道释放依赖快照路径」——其"客户端 BAND_REGISTRY 残留"登记面
覆盖了撤销改名的残渣形态（无独立新洞），但 P1-9 的客户端裸写口是统一规则之外的新进路。

---

## 六、十二份 tick 体偏差矩阵（K23 收敛底稿）/ Tick-Body Deviation Matrix

规范 15 步（以 Blueprint 为基准）：①ensureBusRegistered ②LIT 同步 ③rs.checkGraphChanged ④重编译
(F/L) ⑤停止路径 ⑥refreshInputs(A/H) ⑦recoverConflictedChannels ⑧buildInputs ⑨evaluate 档
(6=全状态/4=SeatInput/3=基础/空表) ⑩writeOutputs ⑪broadcastEvalSnapshot ⑫syncIfBandsChanged
⑬broadcastFlipflopDiff ⑭setChanged ⑮flushPendingFullSync

| 宿主 | ①②③ | ④ | ⑥ | ⑦ | ⑨ | ⑫ | ⑬ | ⑮ | 独有缺口 |
|---|---|---|---|---|---|---|---|---|---|
| Blueprint | ✓ | F | A | ✓ | 6 | ✓ | ✓ | ✗ | 加载即发包 |
| ProgramComputer | ✓ | F | A | ✓ | 6 | ✓ | ✓ | ✗ | 加载即发包 |
| AmplifierComputer | ✓ | F | A | ✓ | 6 | ✓ | ✓ | ✗ | 传输表按存活 MUSIC 剪枝 |
| Sensor | ✓ | F | H | ✓ | 4 | ✓ | —(4档) | ✗ | — |
| ControlSeat | ✓ | F | H | ✓ | 4 | ✓ | —(4档) | ✗ | mode-1 每 tick inflate(50) |
| Monitor | ✓ | L | H | **✗** | 3 | **✗** | ✗ | ✓ | 红石逐条发包无去重（P2-17） |
| KineticGauge | ②✗ | L | H | **✗** | 3 | **✗** | ✗ | ✓ | pushReadoutIfChanged 为正面样板 |
| CncGearbox | ✓ | F | A | **✗** | 6 | **✗** | **✗**(6档缺) | ✓ | — |
| ProgrammableTransmission | ②✗ | F | A | **✗** | 6 | **✗** | **✗**(6档缺) | ✓ | — |
| Radar | ✓ | F | H | ✓ | **空表** | ✓ | ✗ | ✗ | 停止路径内联不走 onStopRunning；P1-7/P2-14/15/16 |
| SpeedProxy | ③✗ | L | **✗** | **✗** | 3 | **✗** | ✗ | ✗ | P2-18：缺步最多（⑥⑧⑩⑭ 全缺） |
| Speaker | ②✗ | F | **✗** | **✗** | 6 | **✗** | ✓ | ✓ | ⑩writeOutputs 缺（P3）；停止早退早于默认图建立 |

共性缺口排名：⑮（8 缺）＞ ⑦（6 缺）＝ ⑫（6 缺）＞ ⑬（6 缺，其中 2 个是 6 参档实质缺失）＞
③⑥⑩⑭（各 1–2 缺）。结构级分叉：停止路径两套写法、加载即发包三种口径、NBT 钩子两种用法
（类型钩子 vs Radar 整段覆写）。

---

## 七、核实为干净的项（正面确认）/ Verified Clean

- **扳手/战利品表**：12 注册方块 × 12 loot table（掉本体+survives_explosion）× 12 onSneakWrenched
  （saveToItem）× 12 创造标签页条目，**无漏网**。
- **REJECT 通路**：服务端两个早退 + applyOp 五处早退全部回 REJECT，执行器级存在性检查在位；
  REJECT 为 1:1 无放大面。仅发射侧缺测试（P3）。
- **音频线程改动**（`fe97125`）：锁纪律整体正确（ANCHOR_LOCK 成对、AudioTimelineDiag 全
  synchronized、SampleBank ConcurrentHashMap 有界）；仅首奏解码位置与两处良性数据竞争（P3）。
- **RuntimeState NBT**：save 七类映射 + SubState 与 load 完全对称，删除节点经 pruneToAliveIds
  全表回收。
- **依赖目标**：5 个 Mixin + 全部反射/编译期 Sable/Create 目标，在运行时 jar（Sable 1.2.2+companion
  1.6.0、Create 6.0.10）与上游源码抽查（Sable 2.0.3、Create 6.0.11）中逐项签名一致，**无现役漂移**；
  无 AT、无 JIJ、Create API 走官方稳定层。
- **测试卫生**：@Disabled=0、sleep/轮询=0、无伪造业务值兜底（38 处 fake/stub 逐类核对均为测试替身
  基建）；696 @Test / 81 文件；新音频主链 14 个测试文件。
- **资产与文档口径**：blockstate→model→texture 引用零缺失；lang 774/774 双向对齐；README 节点 101 /
  方块 12 与代码实测吻合，FAQ 无残留旧句；版本三处一致（mod_version 单一真相源）。
- **广播路径**：GraphHost 的 pushAudioFlagChanges/ensureBusRegistered 有 stamp 去重、
  RuntimeStateSyncPacket 有 lastSynced 检测——K8 之外无新的每 tick 无条件重发点。
- **rcon 脚本**：无明文密码（运行时从被忽略的 server.properties 读），优雅关停符合规范。

---

## 八、建议处置顺序（供决策，未动代码）/ Suggested Disposition Order

1. **安全热修批**（每条都是一行级钳制，独立提交）：P0-1 三处 codec 上限 + P0-2
   totalChunks/blobId 归属 + P2-9 ordinal 防御 + P1-1 座位骑乘校验 + P1-3 ScanSable 距离校验
   ——与 K15 的上限下沉、K21 的服务端限流（cross-be 文档 §6.4 步骤 2b 本就计划独立提交）合成一个
   安全系列。
2. **数据丢失批**：P1-10 NBS 漏斗收口（一行路由，回归锚补 SongSync 路径）→ P2-6 undo 恢复补
   song/subGraph → P1-9 客户端裸写口收编进 releaseChannel 纪律 → P2-5 空名守卫。
3. **封装语义批**：P1-4 + P1-5 + P2-3 一起做（SubState 补 nodeEdge 槽 + 注入清单补两项 +
   子求值器时间戳改每 tick 刷新）——与 K23 收敛、K4 三档统一同向，收敛后这些注入只有一处可插。
4. **雷达专项**：P1-7 门控改"位置量化 + 半径档"口径、P2-14 双写、P2-15 泄漏、P2-16 日志——顺手
   评估 RadarBlockEntity 五职责拆分（cross-be 文档 §6.1 已提的 GUI 步骤 7 候选）。
5. **结构批**（排期，不紧急）：K23 收敛（底稿已齐）→ P2-19 SableReflection 退役 + P2-21 Sable
   依赖声明（含 Maven 化）→ P2-22 编辑器基座统一（新编辑器接入 AbstractGraphScreen 式基座 + 漏斗）
   → P2-4 注入端口静态残留清理。
6. **文档批**：K19 两处新实例（formula-pin-render 状态头、cross-be §6.1 括注）+ NbsSong TODO 回收
   挂 v1.2.6 发布检查单。

---

## 附录：K 编号速查（本文引用口径）/ Appendix: K-Number Quick Reference

K1 dt 硬编码 · K2 跨 BE 抖动+停机不清私有信号 · K3 环静默停更 · K4 求值入口三档 · K5 顺序重播/
游标去重缺 · K6 预算兜底未接线 · K7 去重缓存挂起/出错 · K8 全图广播无节流 · K9 双克隆+装箱 ·
K10 上限仅客户端 · K11 红石 4bit 无抖动 · K12 20Hz 上限 · K13 变速器无死区 · K14 公式函数集 ·
K15 越界索引无上界 · K16 无权限模型 · K17 存在性包无鉴权 · K18 运行时错误不可见 · K19 文档口径 ·
K20 Mixin 无自检 · K21 无服务端限流 · K22 无耗时埋点 · K23 十份 tick 体待收敛 · K24 GraphEditor
体量 · K25 GUI 零测试 · K26 SableReflection 样板重复 · K27 known-open-issues 条目「temp-id 挂起
队列随关屏丢弃」 · K28 known-open-issues 条目「频道释放依赖快照路径」及其 2026-10-11 补注的两条残留 ·
K29 CI 仅 PR 触发。

来源：K1–K23、K27、K28 出自 [`cross-be-latency-observation.md`](cross-be-latency-observation.md)
§九/§4.4/§8 与 [`known-open-issues.md`](known-open-issues.md)；K24–K26、K29 为 2026-10-03 外部审计
（v1.2.5.2 时点）登记、本仓长期成立的结构性项。
