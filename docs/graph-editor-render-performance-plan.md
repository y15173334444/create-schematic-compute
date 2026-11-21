# GraphEditor 渲染性能方案 / GraphEditor Render Performance Plan — 🔶 PERF-8′（死 flush 清除）与 PERF-1/4 待实施 / PERF-8′ + PERF-1/4 pending

> **状态 / Status**：🔶 **连线批已落地并复测（2026-11-14，§3.7）——canvas −49%（低缩放）/−83%（zoom 1.0）；
> PERF-8 批语义取证完成（2026-11-21，§3.8）——q04 破绽根因=试验的「文本层后置」、flush 时序无罪，
> 该形态永久弃案，改判为 PERF-8′（死 flush 清除）；PERF-1/4 文字两项待做**。
> 起草于 2026-10-24，基线 `718d38b`（`main`）；2026-11-14 复核，基线 `8b0203d`（新增 PERF-8/9/10、
> §5.4 外部参照与复核补充）；同日实机测量（PERF-7 工具 + jstack）——**连线 78–83% 为首恶，
> §一/§四/§五.3/§六 已按实测重排**；2026-11-21 取证新增 §3.8。
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
2. 归因顺序（2026-11-14 复核推测 → 同日实机测量**推翻重排**）：实测为**连线 > 节点体/提交 >
   文字 > 布局/索引**——jstack 采样（1024 场景）中 `NodeWireRenderer.bezier` 占 78–83%，
   `drawNode` 12–17%，文字 ≈0–10%，`SpatialIndex.build` ≈1/24。连线首恶的机制根因见 §3.7：
   `steps` 随屏幕距离线性增长 + 横跨视口的连线完全没有截断。
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
| **每节点提交** | 结尾 1 次 `g.flush()`（**2026-11-21 §3.8：实为空 flush**，缓冲已被逐图元 auto-flush 清空） | **~1k 次提交/帧**（空转开销） | `NodeRenderer.drawNode()`、`NodeCommentRenderer.drawCommentNode()` |
| **布局重复计算** | 宽高 / 展开高度算 2–3 遍 | ~2–3k 次/帧 | `renderNodes()`、`drawNode()`、`EditPanel.calcRenderHeight()`、`SpatialIndex.build()` |
| **每帧全量遍历** | 排序 / 索引 / 注释 / 连线 / 节点 / 总线刷新 / 脚本解析 | **6+ 遍/帧** | `GraphEditor.renderBg()` 调用链 |
| 排序 | `nodes.stream().sorted(...)` | O(n log n) + stream 分配 | `GraphEditor.sortNodesByB()` |
| 空间索引 | `spatialIndex.build(...)` | 每帧重建 | `GraphEditor.rebuildSpatialIndex()` |
| 每帧分配 | `new HashMap` × 多处 + stream | 每秒数百次 | `renderBg()` 调用链 |
| 选中判定 | `isSelectedById` 遍历选中集 | 全选时 **O(n²) ≈ 100 万次** | `NodeRenderer.isSelectedById()` |

**关键结论**：文字与连线都是 O(节点数 / 连线数) 的 CPU 工作，且**没有任何 LOD**——
缩放到 0.25 倍、文字只有 2–4 像素高时仍然逐字绘制。这是最可疑的部分。

**2026-11-14 复核补充 / Review additions**（原成本模型未覆盖）：

- **每节点一次 `g.flush()`**：`drawNode()` 与 `drawCommentNode()` 每节点强制提交。
  **2026-11-21 口径修正（§3.8）**：1.21.1 `GuiGraphics` 逐图元自动 flush，这两次 flush 是**空 flush**
  （缓冲已空），作用 ≈ 0，纯剩 GL 状态翻转与空 endBatch 开销——清除项（PERF-8′），非「切断跨节点批处理」
  （本端根本不存在攒批）。
- **布局重复计算**：同一节点的宽高、展开高度在裁剪、绘制、编辑区、空间索引里各算一遍，
  应合并为每帧一次（PERF-9）。
- **每帧 6+ 遍全量遍历**：排序、索引、注释、连线、节点、总线频段刷新、脚本解析彼此独立，
  合并后可少扫 3–4 遍（PERF-3 / PERF-9）。
- **渲染路径里的网络副作用**：`renderTopBar()` 每帧调用 `presence.sendPresenceIfNeeded()`，
  属分层方案 P1 的「渲染副作用外移」；不计入渲染预算，但会污染计时与归因。
- **网格成本随缩放反向增长**：步长是 `GS*zoom`，缩到 0.25 时线条约 4 倍（PERF-10）。
- **文字重复查表 / 测量**：`I18n.get` 与部分 `font.width` 每帧重复，与 LOD 一起做收益更明显（PERF-4）。

**2026-11-14 实机测量修正 / Measurement corrections**（全表见 §3.7）——静态估算与实测的偏差方向：

- **连线被低估**：估算未覆盖「横跨视口的连线无截断」——行转移长线（跨全图 ~3200px）以
  `steps = dist×0.15 ≈ 480` 全量绘制，单条数百次 `fill`；这是 zoom 1.0 下画布成本的主项。
- **文字被高估**：估算按每节点 ~10 次 `drawString`；实测 480p/1080p 视口下文字占比 ≈0–10%
  （PERF-1 的「2–4×」预期不成立，收益上限约 10%）。
- **布局/索引接近零**：`SpatialIndex.build` 在 24 样本中仅 1 次——PERF-3/9 保留「消除卡顿
  尖峰」定位，不是帧时间主项。
- **空场景基线**：frame avg ≈8.5ms、canvas ≈0.7ms（854×480）——非画布开销（世界渲染 +
  chrome + 引擎）恒定 ≈7.8ms，画布优化的全部空间在 canvas 项内。

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

### 3.7 实测结果（2026-11-14）/ Measured results

**条件**：RTX 4060；VSync 关、帧率无限；超平坦+和平静、渲染距离 2；静止画面与鼠标；
HUD（§3.2）10 秒稳态读数；`F10` 场景生成器（§3.6，ADD/PID 交替网格 + 链式连线）；
小窗 854×480 + 大窗 1904×1021 复测（GDI 截不到硬件全屏，用 MoveWindow 等效大视口）。

**帧时间矩阵**（frame avg / p95 / canvas，ms）：

| 场景 | FPS | avg | p95 | canvas |
|---|---|---|---|---|
| 0 节点（基线） | 117.8 | 8.49 | 10.29 | 0.73 |
| 256 @ zoom 1.0 | 58.3 | 17.17 | 21.40 | 12.35 |
| 256 @ 0.5 | 50.8 | 19.63 | 23.46 | 18.73 |
| **256 @ 0.25** | **29.1** | 34.36 | 40.62 | 33.99 |
| 512 @ 1.0 | 46.9 | 21.31 | 25.84 | 20.06 |
| 512 @ 0.5 | 41.6 | 24.05 | 30.27 | 21.90 |
| 512 @ 0.25 | 26.0 | 38.50 | 46.04 | 40.93 |
| 1024 @ 1.0 | 36.5 | 27.35 | 32.37 | 25.29 |
| 1024 @ 0.5 | 35.0 | 28.61 | 33.72 | 30.17 |
| **1024 @ 0.25**（小窗） | **23.3** | 42.95 | 53.40 | 42.54 |
| **1024 @ 0.25**（大窗） | **25.7** | 38.91 | 46.53 | 43.81 |
| 1024 @ 1.0（大窗） | 42.9 | 23.28 | 29.28 | 22.31 |

**jstack 归因**（Render thread；24 样本 @1024/zoom 1.0、18 样本 @1024/zoom 0.25）：
`NodeWireRenderer.bezier` **20/24 = 83%、14/18 = 78%**；`drawNode` 2/24、3/18；
`drawString` 0–2；`SpatialIndex.build` 1/24。256@0.25 时节点/连线对半开（drawNode 12/24、
bezier 9/24）——规模小则节点贵，规模大则连线压倒。

**三条直接推论**：

1. **横跨视口的连线不裁剪是首恶**：`NodeWireRenderer.renderConnections` 的旧裁剪只查
   「两端同侧越界」，行转移长线（跨全图）永远全量绘制且 `steps ∝ 屏幕距离` → 单条 ~480 步。
   **PERF-2 升第一优先**，扩充为「bbox 裁剪 + 视口内分段截取 + steps 上限」三刀。
2. **256 全可见即过线**（29 FPS < 60）——目标场景不是 1024 专属，低缩放全可见本身就超载。
3. **canvas ≈ 帧时间 − 8ms**：canvas 占比随规模升至 ~80%，非画布开销恒定；验收线
   （1024@0.25 canvas < 16.7ms）需削减画布 ~60%，连线三刀的 3–5× 即为此设。

**PERF-2 复测（2026-11-14，同日同场景）**：三刀（bbox 裁剪 + 视口内分段截取 + steps 6..48）
落地后——

| 场景 | 前 canvas | 后 canvas | 前 FPS | 后 FPS |
|---|---|---|---|---|
| 1024@0.25 小窗（854×480） | 42.54 | **21.87（−49%）** | 23.3 | 49.6 |
| 1024@0.25 大窗（1904×1021） | 43.81 | **22.46（−49%）** | 25.7 | 40.4 |
| 1024@1.0 大窗 | 22.31 | **3.68（−83%）** | 42.9 | 116.2 |

zoom 1.0 下长线截取收益全额兑现（canvas 近清零）；低缩放全可见场景 canvas 减半但**仍未达
验收线**——剩余大头转为节点层绘制（`drawNode` + 每节点双 `g.flush()` + 文字），按 §六流程
进入 PERF-8（提交收敛）决策点。视觉回归：截取后曲线平滑、无断线/缺线（验收截图 rig r03–r05）。

**PERF-8 试验与回滚（2026-11-14，同日）**：重叠门控（`FlushGate`）在干净态达成 canvas 19.0ms（小窗）/
20.8ms（大窗，frame −16%），但拖拽重叠实机捕获单帧 z 序破绽（下层引脚标签浮在上层节点体上，rig q04；
吸附后稳态正确，q05）——机制未吃透，**已回滚**并登记（known-open-issues 36）；另发现多次 resize 后的
会话级渲染劣化（37，独立缺陷）。60 线在本方案约束内（不换渲染后端）判定为**不可达**——剩余为纯
几何/文字量，进一步需 PERF-6（离屏纹理 POC）或接受极端压力场景 <60 FPS。
**2026-11-21 取证改判**：q04 破绽与 flush 时序无关、来自试验并行的「文本层后置」发射序改动；
干净态收益（−2.87ms）全部来自删掉 2048 次空 flush——证据链见 §3.8。

### 3.8 BufferSource 批语义取证（2026-11-21）/ BufferSource batch-semantics audit

> 口径：NeoForge 21.1.233（MC 1.21.1）**运行时 jar** Vineflower 反编译（`MultiBufferSource` /
> `GuiGraphics` / `RenderType` / `RenderBuffers` / `GameRenderer` / `Gui`），非凭记忆口径；
> userdev 补丁清单核实 GuiGraphics 补丁仅含 float 重载 / item decorator / tooltip 扩展，**不触 flush 语义**。

**事实链 / Chain of facts**：

1. Screen 渲染的 `GuiGraphics` 由 `GameRenderer.render` 构造，bufferSource = `RenderBuffers.bufferSource()`；
   全程 `managed=false`——vanilla 只在记分板与物品激活动画两处用 `drawManaged` 包裹。
2. `fill` 五参→七参链与 `drawString` **每个图元末尾**调 `flushIfUnmanaged()`；unmanaged 下即
   `flush()` = `bufferSource.endBatch()` + depth test 关/开。`renderOutline` 是 4 次 `fill`。
3. `BufferSource.getBuffer`：同类型 QUADS（`canConsolidateConsecutiveGeometry()=true`）可续攒，
   **但攒批存活窗口只在连续两个同类型图元之间，而每图元后必 flush——窗口恒空**；
   共享↔共享类型切换才 endBatch 上一共享批；切 fixed 类型不清共享批。
4. `RenderBuffers.bufferSource()` 的 fixed 表只含方块/实体/glint/destroy 类型，**无 font**——
   字体走共享缓冲。
5. **净结论：Screen 渲染中绘制序 = 提交序，逐图元落盘，不存在「fill 全落再 text 全落」的两段式。**
   两段式是逐图元 flush 引入之前的旧语义；per-node flush 注释里的「two-pass buffer flush」正属旧口径。

**对既有机制的三点改判 / Three verdicts**：

1. **per-node `g.flush()` 是空 flush**：缓冲已被上一图元的 auto-flush 清空，每次成本 ≈ 2 次真实
   GL depth-test 翻转 + 空 endBatch + fixed 表扫描。FlushGate 实测 −2.87ms ÷ 2048 次 ≈ 1.4µs/次，
   与该成本签名吻合——**试验收益全部来自删空 flush，与门控、批序、时序零关系**。
2. **flush 时序对 z 序零影响**（逐图元语义下序只由发射序决定）→ q04 破绽不可能由门控时序产生，
   必来自试验并行的发射序改动——方案 PERF-8 行明载「文本层后置」：延迟文本统一画，低层节点的
   文字自然浮上高层节点体。登记册 36 原怀疑（remote-lerp 判定/提交时序错位、批语义帧级窗口）
   **均不成立——不存在窗口**。
3. **「文本层后置」在逐图元语义下纯负收益**：延迟不减少 flush 次数（每字符串照样逐个落盘），
   只损失遮挡序 → **永久弃案**，FlushGate 门控不再重设计。

**新形态 PERF-8′（死 flush 清除）**：删除 `drawNode()` / `drawCommentNode()` 三处空 `g.flush()`
并把注释归真——零 z 序风险（绘制序=提交序可证），收益即试验实测的 −2.87ms。
`pendingOverlay`（A=5 报告框）**保留**：报告框必须后画于全部节点，这是 A/B/C 层级的**绘制序**
设计而非批语义，逐图元时代理由照旧成立（若在节点循环内就地画，后画的更高 B 层节点体会盖住它）。

方法论注：登记册 36 原记「headless 锚测覆盖不了批序」——本次取证表明批序根本不需要测：语义可证。
实机视觉回归只对「删除后画面无变化」做一次确认。

/ The audit read the runtime jar (NeoForge 21.1.233) instead of trusting memory: in 1.21.1 the
Screen-side `GuiGraphics` flushes per primitive (every `fill`/`drawString` ends in
`flushIfUnmanaged()`; screens never run managed; the fixed-buffer table has no font types), so
draw order equals emission order and the old "two-pass flush" never happens. Verdicts: the three
per-node `g.flush()` calls are empty flushes (~1.4µs each — the entire −2.87 ms FlushGate win came
from deleting them); flush timing cannot affect z-order, so the q04 break came from the trial's
text-last component; text-last batching is a pure loss now and is permanently shelved. PERF-8′ =
delete the three dead flushes (provably zero ordering risk); the A=5 pending-overlay deferral stays
(it is draw-order layering, not batch semantics).

---

## 四、优化清单 / Optimization Backlog

> 下表按 **2026-11-14 实测（§3.7）重排**：连线 ≫ 节点层 > 文字 > 缓存/布局；预期收益一栏
> 同步按实测修正（原静态估算的偏差见 §二）。

| ID | 改动 | 预期收益 | 风险 | 备注 |
|---|---|---|---|---|
| **PERF-2** | **连线三刀（实测第一优先）**：bbox 裁剪 + 视口内分段截取（单调贝塞尔二分反解 t）+ `steps = clamp(可见弦长×0.15, 6, 48)`；进一步可自定义 `RenderType` 批提交 | 连线 fill 3–5×+ | 中（外观简化已拍板，§八 Q3） | **2026-11-14 前三刀已落地**（`NodeWireRenderer.bezier` 重写 + `crossT` 锚测）；命中侧 `WireGeometry` 自采样不受影响；复测验证 |
| **PERF-7** | 调试统计（§3.2） | —（使能项） | 低 | **已落地**（F9 HUD + F10 场景生成器，§3.6） |
| **PERF-8** | ~~提交收敛~~ **死 flush 清除（PERF-8′，2026-11-21 取证后改判，§3.8）**：删除 `drawNode()` / `drawCommentNode()` 三处空 `g.flush()`（1.21.1 逐图元语义下零作用）；注释归真 | 同试验实测 −2.87ms（其全部来源即删空 flush，§3.8 改判 ①） | 低——绘制序=提交序可证，零 z 序风险 | ~~按层提交 / 文本层后置~~ **永久弃案**：q04 破绽根因即文本后置（§3.8 改判 ②③），FlushGate 不再重设计 |
| **PERF-1** | 文字 LOD：`zoom < 0.5` 跳过次要文字（引脚标签 + encap 摘要，标签计算一并省——I18n 查表 / `privateBandPinLabel` 分配 / 输出侧 `font.width` 测量）；标题 / 警告 / 锁名 / 在场者行恒画；展开编辑节点不降档 | 上限 ~10%（实测修正，非 2–4×） | 低（视觉） | **2026-11-21 已落地**（`NodeRenderer.detailText` 纯逻辑 + `NodeRendererTextLodTest` 锚：边界含等号 / 低于跳过 / 展开不降档）；阈值取 §八 Q2 候选 **0.5 先行**（0.75 备选，拍板改一处常量）；注释节点正文（内容而非 chrome）不降档 |
| **PERF-4** | 文字缓存：`I18n.get` / `FormattedCharSequence` / `EvalSnapshot` 代际 | 与 PERF-1 合计 ~15% | 低 | 维持原序「排在连线批复测之后」——PERF-1 落地后低缩放场景的文字查表已大头消减，剩余形态与「语言切换失效」风险（§七）值不值，**按复测数据再定** |
| **PERF-3** | 每帧派生数据缓存（排序/索引/锁表/选中集按 `graphGeneration` 失效） | 消除卡顿尖峰（非帧时主项，实测 1/24） | 低 | 维持；不改变画面 |
| **PERF-9** | 布局单遍化（宽高/展开高度/引脚坐标每帧一次） | 尖峰项（原 10–20% 系高估） | 低-中 | 维持「与命中侧同源」约束 |
| **PERF-10** | 网格 LOD | 小（实测 0 节点 canvas 0.61→0.73ms @0.25，480p 可忽略） | 低 | 大分辨率再评估 |
| PERF-5 | 连线几何缓存 | 连线批后大概率不再需要 | 中 | **实测后搁置**：截取+封顶可能已够 |
| PERF-6 | 离屏纹理缓存（`RenderTarget`） | 待测 | 中高 | 维持「低于验收线前不做」 |

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

- 性能优化先走 **PERF-2（连线三刀，实测第一优先）**，不碰渲染后端；PERF-3/9/1/4 按连线批复测的剩余占比再排。
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

可借鉴：LOD、拓扑 / 布局缓存、预览异步。（~~提交收敛、文本后置~~ 2026-11-21 弃案：Blender 可批是因为它自己
攒顶点；1.21.1 `GuiGraphics` 逐图元自动 flush（§3.8），本端不存在可收敛的批。）
不可借鉴：实例化与自定义 shader（1.21.1 GUI 管线不支持）、事件驱动零重绘（MC `Screen` 每帧重画）、
离屏纹理（待测，见 PERF-6）。

---

## 六、实施顺序 / Order

```
PERF-7（采样）＋ 实机测量（§3.7）                          ← 2026-11-14 完成
   ↓
PERF-2（连线三刀：bbox 裁剪 + 分段截取 + 步数封顶）          ← 实测第一优先，本批落地
   ↓
（同一场景复测）
   ├─ 达标（≥60 FPS @1024@0.25）→ PERF-5 / PERF-6 永久搁置，线收尾
   └─ 未达标 → PERF-8′（死 flush 清除，§3.8；单独提交 + 视觉回归 + 可回滚）
                → PERF-1 / PERF-4（文字，合计上限 ~15%）
                → PERF-3 / PERF-9 / PERF-10（尖峰项，随时可做）
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
| ~~提交收敛破坏图层顺序~~ **已随取证关闭（§3.8）**：逐图元语义下 flush 时序不影响序，q04 根因是文本后置而非 flush；PERF-8′（删空 flush）绘制序=提交序可证。视觉回归清单仍保留：重叠节点、拖拽连线、编辑区、悬停报告框、锁定徽章 |
| 与功能开发冲突（近 60 天 60 次提交） | 功能冻结窗口；每项小提交 |
| 优化了错的地方 | PERF-7 先行；没有数据不做 PERF-5/6 |

---

## 八、开放问题 / Open Questions

1. 1024 节点全可见是否是**要支持的目标场景**，还是极端压力测试？影响是否值得做 PERF-6。
2. 低缩放隐藏引脚名的阈值（0.5 / 0.75）是否接受？
3. ~~是否允许 `wire` 外观在极端情况下简化~~ **已拍板（2026-11-14）**：接受——`steps` 封顶
   48，1px 线宽下折线感可接受（随 PERF-2 落地）。
4. 是否需要把统计做成常驻调试 HUD（而非 F9 临时开关）？
5. 性能目标是否只针对 RTX 4060，还是要覆盖核显 / 低端卡？
6. ~~文本统一后置在极端重叠场景是否可接受？~~ **已弃案（2026-11-21 §3.8）**：逐图元语义下文本
   后置纯负收益（flush 次数不减、遮挡序受损，q04 根因），不再设计。
7. 布局单遍化是否允许把宽高 / 引脚坐标写回节点字段（需与命中、空间索引强制同源）？

---

## 九、不做的事 / Non-goals

- 不更换 `GuiGraphics` 后端，不引入 GL 直绘 / 实例化。
- 不为 GUI 单独开渲染线程。
- 不改变 MC `Screen` 每帧重画的模型：靠缓存把「每帧重算」变成「每帧读缓存」，不引入脏区域跳过帧。
- 不改 `GraphOp` / 求值 / 网络路径——本文件只处理渲染成本。
- 不把「视觉抖动」混进来：见 [`graph-editor-canvas-jitter-plan.md`](graph-editor-canvas-jitter-plan.md)。
