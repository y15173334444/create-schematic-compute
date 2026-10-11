# 文件级架构拓扑与巨型类拆分审查（2026-10-11）/ File-Level Topology & Giant-Class Split Review — 2026-10-11

> **状态**：🔶 **审查完成，拆分方案待拍板**。审查基线 `1f76f6c`（v1.2.6 WIP）。
> **Status**: 🔶 **review complete, split plan pending decision.** Baseline `1f76f6c` (v1.2.6 WIP).
> **方法**：程序化构建文件级依赖图（import + 同包引用，**剥离注释后统计**，通配导入展开），
> 三层下钻：包级 → 文件级（扇入/扇出/不稳定度/Tarjan SCC）→ 方法级（千行文件的方法跨度 inventory）。
> 分析脚本存档于仓库外工作区，方法学与口径见文末附录，可复现。
> **口径声明**：本文的"依赖条数"= **代码级引用的文件对边**（剥离注释、按文件对去重），与外部包级
> 拓扑分析的"原始引用计数"口径不同（如 blocks→graph：外部分析记 161，本文记 121 条文件对边）
> ——形状一致，数字不可直比。行数是**度量不是锚点**，切口一律以方法名为锚。
> **交叉引用 / Cross-refs**：[`tech-debt-review-2026-10-11.md`](tech-debt-review-2026-10-11.md)
> （下称"复审"，本文的 P2-22 / P2-23 / P2-19 与雷达批次引自它）、
> [`gui-decomposition-plan.md`](gui-decomposition-plan.md)（收束记录：6f 后半延后 IDE 重构、步骤 7 未排期）、
> [`cross-be-latency-observation.md`](cross-be-latency-observation.md) §6.2 / §6.3（GraphEvaluator"不拆"旧结论、tick 体收敛）、
> [`code-architecture.md`](code-architecture.md)、[`known-open-issues.md`](known-open-issues.md)（K28）、
> [`CLAUDE.md`](../CLAUDE.md)（扁平结构口径）。

---

## 〇、结论摘要 / Summary

**拓扑的好消息比包级分析显示的更好，坏消息更集中。**

1. **graph 核已经是理想形态**：剥离注释后 `graph` 包**代码级零外部依赖**（I=0.00，41 文件 / 10k LOC
   完全自洽）。包级分析里 graph→network(4) 的边**已不存在**——端口下沉重构（FormulaCompute /
   OpExecutor / TargetLookup / SignalBusPort 注入）把最后一条向上边切断了。`NodeType`
   （in=41 / out=0）、`EvalSnapshot`（in=20 / out=0）是教科书级稳定叶子。**这一层不需要动。**
2. **真正的结构问题只有三个，全部可定位到具体文件**：
   - **blocks 上帝包**（64 文件 / 23.3k LOC，I=0.79）：Block+BE、Screen、图编辑器全家、节点渲染器、
     会话注册表、宿主引擎混在一包——"包名与实际内容不符"的切法见 §五（18 个文件的编辑器家族）。
   - **46 文件 / 19.6k LOC 的编辑器网 SCC**：`GraphEditor`（in=20 / out=20 的双向枢纽）及其卫星、
     `NodeRenderer`（in=23，全仓 275 处引用）、`BusChannelHelper`、`EditSessionRegistry`、
     一组 S→C 包互相咬合成环。
   - **`SchematicCompute` 便利枢纽**（in=81，全仓第一）：76 处 `SchematicCompute.LOGGER` 把所有包
     拉进 (root) 的下游——一行 logger 治理能拆掉全仓最大的一束边。
3. **巨型文件的可拆性差异极大**：`GraphEvaluator` 的债集中在一个方法（eval 实测 **~1067 行**，比
   v1.2.5.2 审计时的 835 又涨 232——音频分支）；`MonitorBlockEntityRenderer` 一半在 `renderHud()`
   （745 行）；`FormulaParser` 是四阶段管线挤一文件；而 `PixelEditorScreen` **没有超过 50 行的方法**
   ——它需要的是基座统一（复审 P2-22），不是继续切。
4. **五组 Block/BE/Screen 纵切片三角环是设计内聚**，不建议拆——架构工作应集中在编辑器网。

| 层 | 关键数字 |
|---|---|
| 包 | graph I=0.00 自洽；blocks I=0.79 上帝包；client I=0.89；network I=0.59 含 18 条向上边 |
| 文件环 | 46 文件编辑器网（19.6k）；GraphNode↔NodeGraph↔GraphMigration 三角（1.9k）；8 组宿主纵切片三角（设计内聚） |
| 枢纽 | SchematicCompute in=81；NodeType in=41；NodeGraph in=30；GraphNode in=28；NodeRenderer in=23；GraphEditor in=20/out=20 |
| 方法 | GraphEvaluator.eval ~1067L；MonitorBER.renderHud 745L；GraphEditor.tryExpandedEditAreaClicks 428L；NodeRenderer.isPrimaryById 393L；FormulaParser.validate 227L |

---

## 一、包级拓扑判读 / Package-Level Reading

| 包 | 文件/LOC | I | 代码级去向 | 判读 |
|---|---|---|---|---|
| graph | 41 / 10,047 | **0.00** | **无**（零跨包出边） | ✅ 引擎自洽，端口重构已兑现"稳定核" |
| blocks | 64 / 23,328 | 0.79 | graph(121), (root)(38), network(36), client.colorpicker(7) | ❌ 上帝包：世界侧 BE+Block、客户端 Screen、编辑器全家、渲染器、会话、引擎同包 |
| client | 20 / 7,038 | 0.89 | blocks(23), graph(21), (root)(8), network(8) | ⚠️ 指向 blocks 的 23 条里，编辑器/渲染器类贡献大半——它们本就住错了包 |
| network | 37 / 4,567 | 0.59 | (root)(29), blocks(18), graph(13), client.audio(2) | ⚠️ →(root) 全是 LOGGER；→blocks 18 条里 7 条打 GraphBlockEntity 契约（正确），其余见 §四 |
| client.renderer | 5 / 2,618 | 1.00 | graph(7), blocks(5), client(4) | ✅ 纯下游，BE 渲染器该有的样子 |
| compat | 8 / 2,213 | 0.90 | blocks(6), (root)(3) | ✅ 方向正确（复审 P2-19：SableReflection 整体可退役，退役后更干净） |
| client.colorpicker | 4 / 1,200 | 0.10 | blocks(1) | ⚠️ 唯一出边 = ColorPickerWidget→NodeRenderer **取色**——主题提取后即归零（§四 E1/E3） |
| (root) | 2 / 318 | 0.02 | entity(1), items(1) | ⚠️ in=81 的被依赖枢纽（LOGGER/Config），问题在被依赖侧 |
| mixin / entity / radar / items | — | ≤0.67 | — | ✅ 健康 |

**与外部包级分析结论的差异**：外部分析称"11/13 个包在环上"——文件级看，跨包环的全部实质就是
**编辑器网 SCC**（blocks↔network↔client↔(root) 各贡献几个咬合点，见 §二）＋ LOGGER 束。
把这两束拆掉，跨包环自然消失，不需要"重新分包"。

---

## 二、文件级拓扑：环与枢纽 / Cycles & Hubs

### 2.1 代码级 SCC（剥离注释后）

| 环 | 规模 | 成员（按 LOC 降序，节选） | 判读 |
|---|---|---|---|
| **编辑器网** | **46 文件 / 19,613L** | GraphEditor(4942), MonitorDisplayEditor(1661), NodeRenderer(1040), RadarBlockEntity(1003), ColorPickerWidget(768), NodeEditStateFactory(755), EditPanel(750), BusChannelHelper(738), EditorKeys(710), GraphHost(684), EditSessionRegistry(580), …＋一组 S→C 包与 BE | ❌ 唯一要拆的大环；咬合点见 2.2 |
| graph 三角 | 3 文件 / 1,913L | GraphNode ↔ NodeGraph ↔ GraphMigration | ⚠️ 小而稳；GraphNode→NodeGraph 是奇异边（getSubNodes/resizeImagePixels 一族），顺手动它时再断，不立项 |
| 宿主纵切片 ×8 | 各 205–895L | Radar / Transmission / CncGearbox / ControlSeat / Sensor / Amplifier / SpeedProxy / ProgramComputer 的 Block+BE+Screen(+packet) | ✅ **设计内聚，不拆**——Screen↔BE 双向是 Minecraft 惯例（Create 同款），它们是"竖切片"不是环债 |
| CscAudioEngine↔CscAudioStream | 2 文件 / 373L | 引擎与 OpenAL 适配层 | ✅ 可接受 |

**被注释夸大的环（更正）**：像素编辑器四件套（PixelEditorScreen / FrameStrip / Kernel / ToolRail）、
MonitorBER↔MonitorClipMath、NBS 三件套、MultiLineEditBox↔FormulaSuggestPopup、FormulaParser↔
GraphEvaluator——注释里提到类名造成的假边，**代码级均为单向**。像素 / NBS 拆解的门面-内核方向是对的。

### 2.2 编辑器网的四个咬合点（拆环即拆这四处）

1. **GraphEditor 双向枢纽**（in=20 / out=20）：出边是卫星（EditPanel、GraphBusEditor、
   NodeEditStateFactory、GraphOpHistory、GraphRemoteApplier、GraphViewBookmarks、EditorKeys、
   NodeRenderer…），入边是同一批卫星 + 8 个宿主 Screen + **GraphEditAckPacket**（network 包的 S→C
   包直接 import 客户端编辑器类——网络层伸进 UI 的实锤）。
   → 拆法：编辑器家族整体迁出 blocks（§五）+ ACK 经 client 侧监听器接口投递（§四 E2）。
2. **NodeRenderer 高扇入**（in=23，全仓 **275 处** `NodeRenderer.` 引用，较 GUI 计划期的 235 又涨）：
   其中大量是**取色/度量**（GraphEditor 一家 11 处取色调用，ColorPickerWidget、MultiLineEditBox、
   Pixel 三件套都只为颜色引用它）。→ 拆法：**EditorTheme（调色板+度量）独立成类**，一次解决复审
   P2-23（static final 固化主题色）＋ colorpicker→blocks 边归零＋ NodeRenderer 扇入减半。
3. **EditSessionRegistry 的错位**（blocks 包，4 个 network 包依赖它，它又依赖 2 个包 + OpExecutor）：
   它是网络层会话服务却住在 blocks。→ 拆法：迁 network（或 session/），4 条 network→blocks 变包内。
4. **BusChannelHelper 桥接**（network 包，7 个 BE 依赖，出边打 SignalBus+BusBandSyncPacket）：服务端
   注册核与客户端频段同步挤一文件（convergeBusInBands 216L + syncBandsFromServer 134L 是客户端侧）。
   → 拆法：按端拆两文件（§三 P8），K28 死守卫修复与它同刀。

### 2.3 枢纽表（in-degree Top）

| 文件 | in | out | I | LOC | 判读 |
|---|---|---|---|---|---|
| SchematicCompute (root) | **81** | 3 | 0.04 | 284 | LOGGER/Config 便利枢纽 → 每类独立 logger（SignalBus 先例） |
| NodeType (graph) | 41 | 0 | 0.00 | 368 | ✅ 理想叶子，不动 |
| NodeGraph (graph) | 30 | 6 | 0.17 | 600 | ✅ 稳定核 |
| GraphNode (graph) | 28 | 6 | 0.18 | 816 | ✅ 稳定核（三角见 2.1） |
| NodeRenderer (blocks) | 23 | 10 | 0.30 | 1040 | ⚠️ 渲染门面 + 意外兼职"调色板" |
| GraphEditor (blocks) | 20 | 20 | 0.50 | 4942 | ❌ 双向枢纽 |
| EvalSnapshot (graph) | 20 | 0 | 0.00 | 139 | ✅ 干净 DTO |
| GraphBlockEntity (blocks) | 16 | 3 | 0.16 | 210 | ✅ 契约，7 个包走它 |

---

## 三、巨型文件逐个切口（方法级证据）/ Giant-File Cut Plans

> 行数为脚本实测（约数）；**切口以方法名为锚**。顺序 = 建议施工顺序（风险从低到高）。

### P1｜MonitorBlockEntityRenderer（1,593L）——最便宜的大刀

| 方法 | 行数 | 去向 |
|---|---|---|
| renderHud() | **745** | 整体迁出 → `client/renderer/HudRenderer`（HUD 锚定文字/字形发射/玻璃 AABB） |
| ctor | 264 | 随 HUD 状态初始化走一半 |
| drawTextAnchored() / ladderCanvasY() | 131 / 130 | HUD 家族同行 |

- 理由：全文件 11 个成员里 renderHud 一家占 47%；GUI 计划记录它**已有 2 个测试类直接测**——全仓
  唯一有测试兜底的巨型文件，首刀最稳。复审 P3 的每帧分配（每字形 Matrix4f/Vector3f×4/float[8]、
  每像素 4 顶点 quad）就在这批方法里，**拆与修同刀**。
- 切后主类 ≈ 650L（BER 编排 + IMAGE/TEXT 路径）。

### P2｜NodeRenderer（1,040L，in=23）——"调色板兼职"是最大的拓扑收益

| 方法 | 行数 | 去向 |
|---|---|---|
| isPrimaryById() | **393** | 393 行的谓词！逐 NodeType 硬编码 → **数据表**（枚举→主引脚映射，放 NodeType 或 NodeShapes） |
| renderDebugSignalGenChart / renderDebugProbeChart / renderCurveChart | 92+88+52 | → `NodeCharts`（图表渲染，~250L） |
| loadColorConfig / saveColorConfig / withAlpha / P* 常量族 | ~100 | → **`EditorTheme`**（调色板 + 度量单一真相源） |

- 理由：275 处引用里过半只要颜色/度量；EditorTheme 落地后（a）P2-23 的 static final 固化一并修
  （theme 是实例态，运行时改色即时生效），（b）colorpicker→blocks 归零，（c）GraphEditor / Pixel /
  NBS / MultiLineEditBox 对 NodeRenderer 的依赖只剩"真渲染"。
- isPrimaryById 表驱动是典型的"扁平结构配数据表"——符合 CLAUDE.md 扁平口径，比拆类更对味。

### P3｜GraphEvaluator（1,591L）——eval() 一个方法 = 全文件 67%

| 方法 | 行数 | 去向 |
|---|---|---|
| **eval()** | **~1067** | 分派化（见下） |
| applyParamPinOverrides() | 158 | 保留（参数覆盖横切） |
| 其余全部 | 各 ≤53 | 不动 |

- **历史结论要重审**：cross-be §6.2 记录"GUI 计划明确不拆 GraphEvaluator（拆分收益低于破坏风险）"。
  该结论作出时 eval=835L；现 1067L（+232 音频），且复审 P1-4 / P1-5（封装透传、时间戳冻结）的修复
  恰好都要动 ENCAP/音频注入区——**反正要动，分派化让修复有落点**。风险面也变了：graph/ 是全仓
  测试最厚分区（41 测试文件），与 GUI 拆解当时"零测试兜底"的处境不同。
- **切法**（保守，一阶段只加一层）：按既有 `NodeCategory` 分派（它已有启动即炸的覆盖校验）——
  `eval` 保留读输入/写输出的骨架，各 category 分支体抽成 `MotionEval / AudioEval / BusEval /
  FormulaEval / TimingEval` 等静态处理器，共享一个 `EvalContext`（inputs / outputs / runtimeState /
  各端口）。每抽一类跑全量测试。**不建继承体系**（扁平口径不变：处理器是函数包不是类层次）。
- 收益联动：P1-4 / P1-5 的注入清单收敛到 context 构造一处；K22 埋点（evalNs 三段包夹）也只有一处可插。

### P4｜GraphEditor（4,942L，129 成员）——沿既定路线，不另起炉灶

| 方法 | 行数 | 状态 |
|---|---|---|
| tryExpandedEditAreaClicks() | **428** | ❗ **未在任何计划里**——6f 第一刀只拆了 mouseClicked（1055→69），点击路由的其余三兄弟没跟上 |
| renderBg() | 379 | 6f 后半，明确延后 IDE 重构 |
| mouseReleased() | 285 | ❗ 同上未排期 |
| keyPressed() | 251 | 6f 后半 |
| tryCommentClick() | 207 | ❗ 未排期 |
| mouseMoved() | 189 | ❗ 未排期（含三段几乎同构的滚动 delta→offset 换算，复审 P2-22 滚动条几何 6 份之一） |
| clientTick() | 163 | op 冲刷/心跳 |

- **口径**：gui-decomposition-plan 的收束记录明确 6f 后半走 **IDE extract-method**（脚本化搬迁试过
  一次已回滚）——尊重该决定，IDE 里做；428+379+285+251+207+189 ≈ **1,735 行（35%）**是剩余可拆体，
  加 clientTick 后近 40%。每抽一个方法一个提交，配手动回归清单 D。
- **先决**：复审 P2-22 的基座统一（Pixel / NBS 接 AbstractGraphScreen 式 Host 基座 + NBS 漏斗收口）
  **先于**继续拆 GraphEditor——先让卫星离开主类，再切主类方法，避免边拆边接新线。
- **不新增计划**：拆完上述方法后 GraphEditor ≈ 3,200L，剩余是字段网与状态机——那是 IDE 重构的
  长期活，不设行数目标（GUI 计划"腾出的空间被功能填回"的教训写在收束记录里）。

### P5｜FormulaParser（1,641L）——四阶段管线挤一文件，且背着一套并行旧表示

| 相位 | 方法（行数） | 去向 |
|---|---|---|
| 词法 | sanitizeFullwidth 34 + tokenize 133 | → `FormulaLexer` |
| AST 前端 | parseScriptAst 62 + parseStatement 50 + parseKeywordStmt 42 + parseBody/… | → `FormulaAstParser` |
| **RPN 前端（旧）** | parseScript 198 + compileTokens 93 + evaluateValue 79 | 保留或退役（见下） |
| 函数表 | applyFunction 70 + applyVectorFunction 51 + rpnYieldsVec3 37 | → `FormulaFunctions`（数据表化，K14 扩容的落点） |
| 校验 | **validate 227** + typeCheckRpn 52 | → `FormulaValidator`（顺路修"validate 内部完整重跑 parseScript"的双份工作，复审 P3） |

- **语义决策先行**：eval 同时走 AST 与 RPN 两条输出循环（复审 F12 证据）——RPN 是旧表示、AST 是新
  表示，**双前端的退役时间点**是个独立决策，拆文件不依赖它（先物理分离，决策后删一期即可）。
- K6 族之外，复审 P1-6（递归深度上限）的落点在 AstParser 与 Interpreter 两侧，拆完再修边界清晰。

### P6｜RadarBlockEntity（1,003L）——与缺陷修复合流的专项

| 职责簇 | 方法（行数） | 去向 |
|---|---|---|
| Sable 扫描/位姿缓存 | scanSableStructures 53 + getScanLevel 48 + tryBootstrapSableCache 46 + clearCachedSubPose 40 | → `RadarSableScan`（协作类，非继承） |
| 锁定/准星 | findBlipUnderCrosshair 100 + getMaxLocks 65 + toggleLock 32 | → `RadarLockController` |
| NBT 双写 | loadAdditional 93 + saveAdditional 59 + getUpdateTag 43 | 收敛回 SyncedGraphBlockEntity 类型钩子（消复审 P2-14） |
| tick 187 | 五职责共写 | 拆完上述后只剩编排 + 修复审 P1-7 门控 |

- 复审已把雷达列为债密度最高单点（P1-7 / P2-14 / 15 / 16）；**拆分与那批修复必须同一专项**，避免
  两次动同一 tick 体。

### P7｜MonitorDisplayEditor（1,661L）——render/handle 平行对是天然切缝

- 三分：`DisplayAreaCanvas`（renderDisplayArea 216 + handleDisplayAreaClick 207 +
  collectDisplayElements 72 + 尺寸/网格）、`LayerPanel`（renderLayerPanel 127 + 缩略图 68 +
  滚动/重排 46+43）、`SettingsPanel`（96 + 45 + saveAllSettings 33）。主类剩 Host 适配与 drag 状态。
- 它是 GUI 拆解步骤 2 的产物，切口沿用该计划的"行为零变更 + 手动回归"约束。

### P8｜BusChannelHelper（738L）——按端一刀

- 服务端注册核（registerGraphChannels 84 + unregisterChannels 21 + recoverConflictedChannels 43 +
  takeoverPrivateChannel 27 + staleOwnerReclaim 20 ≈ 195L）与客户端频段同步（convergeBusInBands 216
  + syncBandsFromServer 134 + cleanupClientBands 74 ≈ 424L）**几乎不共享状态**，拆两文件。
- K28 死守卫（reRegisterChannels / syncDeletedBusNames 的别名 diff）与残留清理都在这两个方法里——
  **先修死守卫再拆**，避免把坏逻辑搬进新家。

### 不拆清单（明确说"不"）/ Explicitly Not Splitting

- **八组宿主 Block/BE/Screen 纵切片**：三角环是设计内聚（Screen 需要读 BE、BE 需要 Screen 工厂），
  Create 同款惯例；拆了只会把一个竖切面摊到三个包。
- **PixelEditorScreen（1,076L）**：无 50 行以上方法——已薄；它要的是接基座（复审 P2-22），不是切。
- **GraphNode↔NodeGraph↔GraphMigration 三角**：1.9k、三个都是稳定核文件；顺手动
  resizeImagePixels / getSubNodes 时顺手断 GraphNode→NodeGraph 边即可，不立项。
- **GraphNode（816L）**：save/load/ctor 占大头，形状健康；ensureScriptParsed 是 CLAUDE.md 认证的
  单一真相源，不因为"引用了解析器"就搬。

---

## 四、边治理（不动结构的 cheap wins）/ Edge Treatments

| # | 动作 | 拆掉的边 | 成本 |
|---|---|---|---|
| E1 | **每类独立 logger**（SignalBus 已有先例）：SchematicCompute.LOGGER 的 76 处引用逐包替换 | (root) 的 in=81 → ≈个位数；network→(root) 29 条、blocks→(root) 38 条同时消失 | 机械，一个包一个提交 |
| E2 | **GraphEditAckPacket 与 GraphEditor 解耦**：network 定义 `EditAckSink` 监听接口，client 启动时注册，包只回调接口 | network→blocks 的 UI 直连边；顺带把复审 P3"REJECT 发射侧无测试"的锚点立在这 | 小 |
| E3 | **EditorTheme 提取**（复审 P2-23 的正解，放 client 或 client.colorpicker） | colorpicker→blocks 归零；NodeRenderer in=23 → 约 10；GraphEditor/Pixel/NBS/MultiLineEditBox 的取色引用改挂 theme | 中（275 处引用里约半数是取色，逐文件改） |
| E4 | **EditSessionRegistry 迁 network**（或 session/） | 4 条 network→blocks 变包内；blocks 卸掉一个非世界侧文件 | 小（含 4 个包的 import 改） |
| E5 | **specific-BE 包维持现状**：RadarLockPacket→RadarBlockEntity 这类"专属包→专属 BE"是合理耦合，**不要**为它建处理器注册表 | — | 零 |

> E1+E3 做完后，跨包环的三个咬合点里两个消失，编辑器网 SCC 预计缩到 ~30 文件；
> 再做 §五（编辑器家族迁出）后 client→blocks 只剩渲染器注册等合法边。

---

## 五、包归位（blocks 上帝包裂化，纯机械、最后做）/ Package Re-Homing

blocks 64 文件的实际构成：宿主 Block+BE+Screen 纵切片 ×8 组（~26 文件）＋ **编辑器家族 ~18 文件
（约 8k LOC：GraphEditor、AbstractGraphScreen、EditPanel、GraphBusEditor、NodeEditStateFactory、
GraphOpHistory、GraphRemoteApplier、GraphPresenceTracker、GraphViewBookmarks、EditorKeys、
EditorSettingsScreen+3Tab、NodeAddMenu、NodeCommentRenderer、NodeWireRenderer、MonitorDisplayEditor、
NodeRenderer）**＋ 引擎（GraphHost、SyncedGraphBlockEntity、GraphBlockEntity）＋ 会话
（EditSessionRegistry）＋ 共享 BE 基建。

- **方案**：编辑器家族整体迁 **`client.editor/`**（它们全是客户端类；NodeRenderer 家族同行）——
  blocks 回归"世界侧"（Block / BE / 引擎 / 基建），client→blocks 的 23 条里编辑器部分变 client 内部
  依赖，"包名与实际内容不符"直接消掉。**不**给每个宿主立子包（纵切片同包内聚优先）。
- **时机**：必须在 P4（GraphEditor 方法拆解）**之后**——包移动会让 6f 后半的 IDE extract-method
  diff 混进 import 噪音，二分定位失效（gui 计划"行为零变更可二分"的约束）。作为 gui 计划步骤 7
  （从未排期）的全面版执行：纯移动、零代码变更、一次一个家族、每步全量编译 + 全量测试。

---

## 六、施工顺序总表 / Batching

| 批 | 内容 | 性质 | 依据 |
|---|---|---|---|
| 0 | E1 独立 logger + E3 EditorTheme | 机械+小改，拓扑收益最大 | 本文 §四 |
| 1 | P1 MonitorBER→HudRenderer；P2 NodeRenderer→NodeCharts+isPrimaryById 表驱动；P5 FormulaParser 按相拆文件 | 纯提取、门面冻结、行为零变更 | 有测试侧（MonitorBER 2 测试类）或纯机械 |
| 2 | eval() 分派化（先立决策文档更新 cross-be §6.2 的"不拆"旧结论）+ 复审 P1-4 / P1-5 / P2-3 修复顺路；P8 BusChannelHelper 先修 K28 死守卫再按端拆 | 服务端，测试最厚 | 复审 §八批 3 合流 |
| 3 | P4 GraphEditor：IDE extract-method 六方法 + P2-22 基座统一先行；P7 MonitorDisplayEditor 三分 | GUI，手动回归清单 D | gui 计划收束记录 + 复审 P2-22 |
| 4 | §五 包归位：编辑器家族→client.editor、E4 EditSessionRegistry→network、E2 ACK 解耦 | 纯机械，最后做 | gui 计划步骤 7 全面版 |
| 5 | P6 雷达专项：复审 P1-7 / P2-14 / 15 / 16 修复时同步拆 RadarSableScan+RadarLockController | 与缺陷批次合流 | 复审 §八批 4 |

**两条红线**（沿用既有计划约束）：① 每批独立提交、行为变更与结构变更不同批（cross-be §6.3 的
"先收敛再加钩子"同款）；② 拆分不建继承体系——处理器 / 协作类 / 数据表，不设类层次
（CLAUDE.md 扁平口径）。

---

## 附录：数据口径与可复现性 / Appendix: Method & Reproducibility

- 拓扑数据来自一次性静态分析：剥离注释（/*…*/ 与 //）后解析项目内 import（含通配展开）+ 同包类名
  词匹配，构建文件对边表；SCC 用 Tarjan；方法跨度按"签名到下一成员签名"近似（含 inner class）。
  分析脚本存档于仓库外工作区（桌面 mod-tech-debt/_analysis/），同口径可复跑；行数以审查基线
  `1f76f6c` 工作树为准。
- 假边更正记录（方法学的必要性证明）：FormulaParser→GraphEvaluator、像素编辑器四件套回边、
  MultiLineEditBox↔FormulaSuggestPopup 均为注释提及造成的假边，剥离后消失——**一切环结论以代码级
  统计为准**。
- 关键抽查：`SchematicCompute.LOGGER` 76 处；`NodeRenderer.` 全仓 275 处（GraphEditor 独占 11 处
  取色）；`SchematicCompute` 以 `import blocks.*` 通配引用世界侧。
