# GraphEditor 渲染性能方案 / GraphEditor Render Performance Plan — 🔶 待实施 / Proposed

> **状态 / Status**：🔶 **待实施（先测量，后优化）**。起草于 2026-10-24，基线 `718d38b`（`main`）；
> 2026-11-14 复核，基线 `8b0203d`（新增 PERF-8/9/10、§5.4 外部参照与复核补充）。
> **触发问题 / Trigger**：1024 节点同屏实测约 **30 FPS**（RTX 4060）。该显卡下 GPU 几乎不可能成为瓶颈，
> 指向 **CPU 端每帧顶点 / 文字生成**；复核后补上**每节点强制提交**与**每帧多遍全量遍历**两项。
> **目标 / Goal**：在同一场景下把帧时间压到 16.7ms 以内（≥60 FPS），并建立可长期复用的性能采样手段。
> **约束 / Constraint**：不改变既有交互语义；低缩放下的 LOD 必须视觉可接受；每项优化单独提交、可回滚。
> **前置 / Prerequisite**：`graph-editor-interaction-render-layering-plan.md` 的 P1（渲染副作用外移）——
> 否则 `renderBg` 每帧的手柄轮询 / 相机过渡 / 总线同步会污染计时与归因。
> **交叉引用 / Cross-refs**：
> [`graph-editor-interaction-render-layering-plan.md`](graph-editor-interaction-render-layering-plan.md)（分层重构）、
> [`graph-editor-canvas-jitter-plan.md`](graph-editor-canvas-jitter-plan.md)（视觉抖动）、
> [`gui-decomposition-plan.md`](gui-decomposition-plan.md)（步骤 5–6 的拆分记录）、
> [`code-architecture.md`](code-architecture.md)、[`CLAUDE.md`](../CLAUDE.md)。
>
> **引用约定 / Reference convention**：复核后的条目用方法 / 字段名引用（如 `NodeRenderer.drawNode()`），
> 不再依赖行号——代码一移动行号就失效。原行号引用保留作历史线索，实施前以符号为准。

---

## 一、结论 / Decision

1. **GPU 已经在用**（见 §五）：`GuiGraphics` → `BufferBuilder` → `RenderType` 批处理 → `RenderSystem`。
   「上 GPU 加速」不是本项目缺失的能力。
2. 30 FPS 的合理归因顺序（2026-11-14 复核修订）：**文字 > 每节点强制提交 > 连线描边 >
   每帧重复计算 / 分配**。复核新增的三项（每节点 `g.flush()`、6+ 遍全量遍历、布局重复计算）
   此前不在成本模型里。
3. **必须先采样再优化**。当前没有任何渲染计时或计数（`renderBg` 无 `nanoTime`）；
   「Phase 2 渲染缓存」字段 `lastRenderedGen / lastRenderedCamX / lastRenderedCamY /
   lastRenderedZoom` 仍只写不读（`lastRenderedScreenW/H` 已被 `renderGrid` 使用），
   同样说明此前没有真正测过。

---

## 二、每帧成本模型 / Cost Model

按 1024 节点全可见、静态画面估算。数字来自代码静态计数，**不是实测**，用于排序而非结论。

| 项 | 每节点 / 每条 | 1024 节点规模 | 代码依据 |
|---|---|---|---|
| 节点体 | 15 次 `fill` | ~15k 次 | `NodeRenderer.drawNode()` |
| 节点边框 | 8 次 `renderOutline`（每次 4 条边） | ~32k 次 | 同上 |
| pose push/pop | 7 次 | ~7k 次 | 同上 |
| 节点文字 | 约 10 次 `drawString` | **~10k 次/帧** | 同上 |
| I18n 查表 | 约 6 次 `I18n.get` | ~6k 次/帧（每帧重复） | 同上 |
| 文字测量 | 居中 / 宽度用的 `font.width` | 数百~数千次/帧 | `NodeRenderer`、`EditPanel`、`NodeCommentRenderer` |
| 连线 | `steps = max(10, dist*0.15)`，每步 ≥1 次 `fill` | 1000 条线 → **1 万~6 万次** | `NodeWireRenderer.bezier()` |
| 网格 | `width / (GS*zoom)` 量级的 `fill` | ~100 次（zoom 1）；**zoom 减半翻倍** | `NodeRenderer.renderGrid()` |
| **每节点提交** | 结尾 1 次 `g.flush()` | **~1k 次提交/帧** | `NodeRenderer.drawNode()`、`NodeCommentRenderer.drawCommentNode()` |
| **布局重复计算** | 宽高 / 展开高度算 2–3 遍 | ~2–3k 次/帧 | `renderNodes()`、`drawNode()`、`EditPanel.calcRenderHeight()`、`SpatialIndex.build()` |
| **每帧全量遍历** | 排序 / 索引 / 注释 / 连线 / 节点 / 总线刷新 / 脚本解析 | **6+ 遍/帧** | `GraphEditor.renderBg()` 调用链 |
| 排序 | `nodes.stream().sorted(...)` | O(n log n) + stream 分配 | `GraphEditor.sortNodesByB()` |
| 空间索引 | `spatialIndex.build(...)` | 每帧重建 | `GraphEditor.rebuildSpatialIndex()` |
| 每帧分配 | `new HashMap` × 多处 + stream | 每秒数百次 | `renderBg()` 调用链 |
| 选中判定 | `isSelectedById` 遍历选中集 | 全选时 **O(n²) ≈ 100 万次** | `NodeRenderer.isSelectedById()` |

**关键结论**：文字与连线都是 O(节点数 / 连线数) 的 CPU 工作，且**没有任何 LOD**——
缩放到 0.25 倍、文字只有 2–4 像素高时仍然逐字绘制。这是最可疑的部分。

**2026-11-14 复核补充 / Review additions**（原成本模型未覆盖）：

- **每节点一次 `g.flush()`**：为保持 fill/text 的图层顺序，`drawNode()` 与 `drawCommentNode()`
  在末尾强制提交。它切断了跨节点批处理——1024 节点至少四位数提交/帧，是 draw call 层面的主要成本（PERF-8）。
- **布局重复计算**：同一节点的宽高、展开高度在裁剪、绘制、编辑区、空间索引里各算一遍，
  应合并为每帧一次（PERF-9）。
- **每帧 6+ 遍全量遍历**：排序、索引、注释、连线、节点、总线频段刷新、脚本解析彼此独立，
  合并后可少扫 3–4 遍（PERF-3 / PERF-9）。
- **渲染路径里的网络副作用**：`renderTopBar()` 每帧调用 `presence.sendPresenceIfNeeded()`，
  属分层方案 P1 的「渲染副作用外移」；不计入渲染预算，但会污染计时与归因。
- **网格成本随缩放反向增长**：步长是 `GS*zoom`，缩到 0.25 时线条约 4 倍（PERF-10）。
- **文字重复查表 / 测量**：`I18n.get` 与部分 `font.width` 每帧重复，与 LOD 一起做收益更明显（PERF-4）。

---

## 三、采样方法 / Measurement

### 3.1 为什么 F3 不行

F3 debug overlay 属于 HUD 层，`Screen` 打开时不绘制；调试键也只在无界面时处理。
需要在 `Screen.render` 内自己计时。

### 3.2 屏幕内自绘（首选）

插入点：`AbstractGraphScreen.render`（`AbstractGraphScreen.java:110`）。
该方法是每个 GUI 帧的入口，`renderGraphCanvas` 即 `GraphEditor.renderBg`。

```java
private long lastFrameNs;
private final long[] frameNs = new long[240];
private int frameIdx;
private static boolean statsOn = false;   // F9 切换

@Override
public void render(GuiGraphics g, int mx, int my, float pt) {
    long now = System.nanoTime();
    if (lastFrameNs != 0) frameNs[frameIdx++ % frameNs.length] = now - lastFrameNs;
    lastFrameNs = now;

    this.renderBackground(g, mx, my, pt);
    long t0 = System.nanoTime();
    renderGraphCanvas(g, mx, my, pt);
    long canvasNs = System.nanoTime() - t0;
    for (var r : this.renderables) r.render(g, mx, my, pt);

    if (statsOn) drawStats(g, canvasNs);
}
```

叠加层显示：`FPS / frame avg / p95 / max / canvas 耗时 / 节点数 / 连线数`。

- **帧间隔在 `render` 入口测**，才是真实帧率；只看平均会漏掉尖峰，必须同时看 p95 和 max。
- `canvasNs` 单独统计，用于区分「GUI 慢」与「世界渲染 / 其他 mod 慢」。
- 默认关闭，F9 切换；`keyPressed` 中要在 `editor.keyPressed` 之前拦截。

### 3.3 分层计时（P1 之后）

`renderBg` 内按层插入 `nanoTime`：grid / comments / wires / nodes / overlays / chrome。
并把仍只写不读的 `lastRenderedGen / lastRenderedCamX / lastRenderedCamY / lastRenderedZoom`
删掉或真正接线（`lastRenderedScreenW/H` 已在使用）。

### 3.4 外部采样（归因）

| 工具 | 用法 | 说明 |
|---|---|---|
| async-profiler | `jps -l` 找 pid → `asprof -d 30 -e cpu -f gui.html <pid>` | 最优；过滤 Render thread 看热点栈 |
| JFR | `jcmd <pid> JFR.start duration=30s filename=gui.jfr` | JDK 自带，JMC 分析 |
| Spark | 界面打开时 `/spark profiler --timeout 30` | 装 mod 即用 |
| jstack | 连打 20 次，grep `Render thread` | 零依赖穷人版 |

`runClient` 是独立 JVM，`jps -l` 中类似 `net.minecraft.client.main.Main`；以上工具都是 attach，无需改 `build.gradle`。

### 3.5 测试协议

1. **关 VSync、最大帧率设为无限**。60Hz 屏上 30 FPS 很可能是 vsync 半速量化，真实帧时间可能远小于 33ms。
2. 超平坦 / 虚空世界，渲染距离 2，减少幕后世界渲染干扰。
3. 固定相机与缩放，鼠标不动。
4. 场景矩阵：节点数 **0 / 256 / 512 / 1024** × 缩放 **0.25 / 0.5 / 1.0**。
5. 每场景 10 秒，记录 **平均 / p95 / max**；优化前后用同一场景对比。
6. 若在服务器上测，注意单人暂停不影响渲染；世界仍会在 Screen 后面渲染。

---

### 3.6 测量配套工具 / Measurement companion tools

随 PERF-7 一并落地的两个调试入口（都拦在 `AbstractGraphScreen.keyPressed` 的
`editor.keyPressed` 之前，控制台打开时让位——模态叠加层优先吞键）：

- **F9 — 采样叠加层**：FPS / 帧时间 avg·p95·max（240 帧环）+ `canvasNs` +
  节点 / 连线数 + 缩放，画在顶栏与工具栏下方左上角；widget 之后、控制台之前绘制
  （控制台仍是最上层）。开关位 static，跨界面重建存活；关闭态开销 = 两次长整型赋值。
- **F10 — 测量场景生成器**（`GraphPerfScene`）：循环 **256 → 512 → 1024 → 0（清空）**。
  ADD / PID 交替网格排布（PID 带参数面板，作中重量代表），链式连线，相机对准场景中心、
  缩放不动。节点直接落在当前打开的**客户端图**上——服务端权威图不受影响，重开编辑器
  （重拉权威图）即自愈；仅测量会话使用，不写存档、不发操作包。

---

## 四、优化清单 / Optimization Backlog

| ID | 改动 | 预期收益 | 风险 | 备注 |
|---|---|---|---|---|
| **PERF-7** | 调试统计（§3.2） | —（使能项） | 低 | **先做**，否则无法验证后续收益 |
| **PERF-3** | 每帧派生数据缓存：`sortedByB`、`SpatialIndex`、锁定表、`Set<Integer>` 选中集、总线频段扫描结果按 `graphGeneration` 失效；复用 Map | 10–25%，消除卡顿尖峰 | 低 | 与 PERF-9 合并实施可少扫 3–4 遍；不改变画面 |
| **PERF-1** | **文字 LOD**：`zoom < 0.5` 只画标题；`zoom < 0.75` 不画引脚名；节点屏幕宽度 < 24px 只画色块 | **2–4×**（1024 全可见场景） | 中（视觉变更） | 低缩放下文字本就不可读；Blender 节点编辑器在缩放 < 0.2 时整类跳过细节，可作「LOD 有效」的旁证（阈值按可读性定，不照抄） |
| **PERF-2** | 连线减段 / 批量：`steps = clamp(dist/12, 6, 48)`；同一行 run 合并保留；进一步可用自定义 `RenderType` 一次提交三角形带 | 连线成本 3–5× | 中 | 先减段，批量后置 |
| **PERF-4** | 文字缓存：`I18n.get` 按 (type, pin) 缓存；标题/引脚名缓存 `FormattedCharSequence`；数值按 `EvalSnapshot` 代际重格式化 | 再降 20–40% | 低 | 与 PERF-1 协同 |
| **PERF-5** | 连线几何缓存：贝塞尔形状只依赖端点与 zoom，平移是整体平移；缓存像素 run 后整数偏移 | 长线多时有效 | 中 | 需要正确失效 |
| **PERF-6** | 离屏纹理缓存（`RenderTarget`，等价 UE `SRetainerWidget`） | 待测 | 中高 | 见 §五，POC 后再定 |
| **PERF-8** | **提交收敛**：把每节点 `g.flush()` 改为按层提交（A=1/2/3/5 各一次；文本层后置或合并 RenderType），保持遮挡正确 | 提交次数 ~1k → 个位数；draw call 层面收益最大 | **高**（图层顺序 / 悬停 / 编辑区） | 需专门视觉回归清单；单独提交、可回滚；与 PERF-2 协同 |
| **PERF-9** | **布局单遍化**：每帧一次算出节点宽高 / 展开高度 / 引脚坐标，存节点 runtime 供裁剪、绘制、索引、连线、命中共用 | 10–20% | 低-中 | 必须保持与命中侧同源（`WireGeometry`） |
| **PERF-10** | **网格 LOD**：按屏幕像素步长合并网格线（每 2/4 格一条），低缩放不再线性增长 | 小 | 低 | 可并入 PERF-1 一起验收 |

**参考验收线**：1024 节点、`zoom ≤ 0.5`、静止画面 ≥60 FPS（RTX 4060），
平移时 p95 帧时间 ≤16.7ms。低于此线前不做 PERF-6。

---

## 五、GPU 可行性 / GPU Acceleration

### 5.1 前提

Minecraft GUI **已经在 GPU 上**：`GuiGraphics.fill` / `drawString` 把顶点写进 `BufferBuilder`，
按 `RenderType` 批量提交，帧末由 `RenderSystem` 刷到 OpenGL。全仓只有两处直接碰
`RenderSystem`（`EditPanel.java:636`、`GraphEditor.java:1623` 的 `depthMask`）。
不存在「从 CPU 切到 GPU」的开关。

### 5.2 可选方案

| 方案 | 做法 | 改动面 | 风险 | 收益 | 建议 |
|---|---|---|---|---|---|
| 离屏纹理缓存 | 图内容渲染到 `RenderTarget`，平移/缩放只 blit | 中高 | 文字缩放糊、失效管理、显存、flush 顺序 | 平移大图时中高 | POC 后再定 |
| 自定义 RenderType / shader | 贝塞尔线在 GPU 细分 | 高 | 需注册；Iris/光影兼容不确定 | 仅大量连线时 | 不做 |
| 实例化四边形 | 一 draw call 画所有节点 | 高 | 1.21.1 GUI 管线不支持 instancing | 低 | 不做 |
| 每帧工作缓存 | 排序/索引/布局按代际缓存 | 低 | 低 | 中高 | **先做（PERF-3 + PERF-9）** |
| 文字 LOD | 缩放过小时跳过文字 | 低 | 低 | 中高 | **先做（PERF-1）** |

### 5.3 结论

- 性能优化先走 **PERF-3 + PERF-9 + PERF-1 + PERF-2**，不碰渲染后端。
- 只有在测量证明「每帧顶点生成」仍占大头时，才评估 `RenderTarget` POC；
  该项应另立文档，不并入本计划。

### 5.4 外部参照：Blender 节点编辑器 / External reference: Blender's node editor

2026-11-14 对 Blender 主分支做了稀疏浅克隆、只读复核（未摘抄代码），结论：

- **批处理**：所有连线每 256 条一批实例化；插槽每节点最多 32 个一批；同类控件合成一次调用。
- **GPU 几何**：曲线、箭头、虚线、圆角、描边由 shader 从参数生成，CPU 不逐段采样。
- **LOD**：缩放低于约 0.2 时整类跳过插槽 / 标签 / 分组细节，只留轮廓与标题。
- **缓存**：每棵树一份拓扑缓存（链接 / 插槽数组、邻接、拓扑序），节点声明按类型缓存；
  布局结果每帧算一次后供渲染、命中、滚动共同复用。
- **异步**：节点预览走后台任务，只算可见输出，最大边 128 px；绘制线程不等待。
- **裁剪**：节点 AABB 与视口求交；连线按 x 范围快速剔除。

可借鉴：LOD、拓扑 / 布局缓存、提交收敛、文本后置、预览异步。
不可借鉴：实例化与自定义 shader（1.21.1 GUI 管线不支持）、事件驱动零重绘（MC `Screen` 每帧重画）、
离屏纹理（待测，见 PERF-6）。

---

## 六、实施顺序 / Order

```
PERF-7（采样）
   ↓
PERF-3（缓存/分配）＋ PERF-9（布局单遍化）＋ PERF-1（文字 LOD）   ← 可并行，各自单独提交
   ↓
PERF-2（连线减段）
   ↓
PERF-4（文字缓存）＋ PERF-10（网格 LOD）
   ↓
PERF-8（提交收敛）   ← 高风险：单独提交 + 视觉回归 + 可回滚
   ↓
（测量后）PERF-5 / PERF-6
```

每步验收：`./gradlew test` 绿 + 同一场景 p95 帧时间不劣化 + 手动视觉回归。
构建与测试遵循 `CLAUDE.md`：后台执行，完成通知后取一次最终结果。

---

## 七、风险 / Risks

| 风险 | 缓解 |
|---|---|
| 测量受 VSync / 限帧污染 | 先关 VSync、无限帧率，再记录数据 |
| 世界渲染混入 GUI 帧时间 | 虚空世界 + 单独统计 `canvasNs` |
| LOD 被玩家视为「功能消失」 | 阈值可配置；只隐藏不可读尺寸的文字；保留标题 |
| 缓存失效错误（图整替换、子图切换、语言切换） | 缓存键包含 `graphGeneration` / encapId / 语言版本；配单测 |
| 提交收敛破坏图层顺序（文本盖节点 / 悬停框被盖） | 按 A=1..5 分层各 flush 一次 + 文本后置；视觉回归清单：重叠节点、拖拽连线、编辑区、悬停报告框、锁定徽章 |
| 与功能开发冲突（近 60 天 60 次提交） | 功能冻结窗口；每项小提交 |
| 优化了错的地方 | PERF-7 先行；没有数据不做 PERF-5/6 |

---

## 八、开放问题 / Open Questions

1. 1024 节点全可见是否是**要支持的目标场景**，还是极端压力测试？影响是否值得做 PERF-6。
2. 低缩放隐藏引脚名的阈值（0.5 / 0.75）是否接受？
3. 是否允许 `wire` 外观在极端情况下简化（段数降低会造成轻微折线感）？
4. 是否需要把统计做成常驻调试 HUD（而非 F9 临时开关）？
5. 性能目标是否只针对 RTX 4060，还是要覆盖核显 / 低端卡？
6. 文本统一后置在极端重叠场景是否可接受？还是按「节点与已绘节点是否重叠」条件回退到逐节点 flush？
7. 布局单遍化是否允许把宽高 / 引脚坐标写回节点字段（需与命中、空间索引强制同源）？

---

## 九、不做的事 / Non-goals

- 不更换 `GuiGraphics` 后端，不引入 GL 直绘 / 实例化。
- 不为 GUI 单独开渲染线程。
- 不改变 MC `Screen` 每帧重画的模型：靠缓存把「每帧重算」变成「每帧读缓存」，不引入脏区域跳过帧。
- 不改 `GraphOp` / 求值 / 网络路径——本文件只处理渲染成本。
- 不把「视觉抖动」混进来：见 [`graph-editor-canvas-jitter-plan.md`](graph-editor-canvas-jitter-plan.md)。
