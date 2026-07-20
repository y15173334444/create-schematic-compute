# v1.2.4 调试工具链 — 实现规划

> 作用域：模拟输入节点（Signal Generator）、模拟显示屏节点（Debug Probe）、视角书签（View Bookmarks）。  
> 本规划已对齐 `5c5b39c` 实际代码架构，并标注了原规格与代码不符处的修正方案。

---

## 0. 原规格与实际架构的关键差异（必须先对齐）

原规格基于若干与代码不符的假设。下表逐条列出差异并给出修正策略，后续章节均以修正后方案为准。

| 原规格假设                       | 实际代码                                                                         | 影响                 | 修正策略                                                                      |
| --------------------------- | ---------------------------------------------------------------------------- | ------------------ | ------------------------------------------------------------------------- |
| 存在 `AbstractNode` 基类，新节点继承它 | 节点是单一数据类 `GraphNode`（`graph/GraphNode.java`），所有类型共用同一组字段，类型由 `NodeType` 枚举区分 | 新节点不能"继承"          | 新增 `NodeType` 枚举值 + 复用/扩展 `GraphNode` 字段                                  |
| 节点有 `isCompilable()` 方法     | 全仓库无此方法；"封装/导出忽略"靠 `NodeType` 过滤，无运行时编译判定                                    | "调试节点编译时忽略"无现成机制   | 新增 `NodeType.isDebug()` 方法 + 在导出/封装流程中过滤                                  |
| 节点有 `evaluate()` 方法，客户端可调   | 求值集中在服务端 `GraphEvaluator.eval()`（巨型 switch）；客户端**禁止**实例化 evaluator（类注释明确禁止）  | "编辑器内每 tick 求值"不成立 | 调试节点的"运行时值"由服务端 eval 提供；客户端读 `EvalSnapshot`                               |
| `GraphEditor` 有 `tick()`    | `GraphEditor` 无 tick；宿主 `Screen.containerTick()` 仅检查方块是否被破坏                  | 探针"每 tick 采样"无钩子   | 在 `GraphEditor` 新增 `clientTick()`，由各 `Host` Screen 的 `containerTick()` 调用 |
| 用 `Tessellator` 绘制          | 现有渲染全部用 `GuiGraphics.fill/drawString/renderOutline`，`Tessellator` 未使用        | 可用但与现有风格不一致        | 优先用 `GuiGraphics`；折线图用密集 `fill` 像素段或 `BufferBuilder` 直线                   |
| 控制点坐标存入 NBT                 | `GraphNode` 无 `List<Point2D>` 字段                                             | 需新增字段或编码           | 在 `GraphNode` 新增 `float[] debugCtrlX/Y` + save/load 支持                    |

**核心结论**：调试节点应当作为**真实节点**加入 `NodeType` 枚举，正常序列化、正常参与服务端求值。"调试"属性体现在两点 —— (1) 交互式编辑器（XY 拖点、迷你趋势图）；(2) 可选的 `isDebug()` 标记，用于导出/封装时过滤。原规格"运行时输出 0"的方案会令节点在运行时毫无作用，且需要搭建客户端预览求值基础设施（成本高、收益低），**不推荐**。详见第 2、3 章的"方案选择"。

---

## 1. 总体架构

### 1.1 数据流

```
┌─────────────────────────────────────────────────────────────┐
│ 服务端 BlockEntity.tick()                                     │
│   GraphEvaluator.evaluate()                                   │
│     ├─ DEBUG_SIGNAL_GEN → 计算 signal(time)                   │
│     └─ DEBUG_PROBE      → pass-through (o[0] = inputValue)    │
│   EvalSnapshot.capture(outputs)                               │
│   → ClientboundGraphEvalPacket 广播                            │
└──────────────────────┬──────────────────────────────────────┘
                       ▼
┌─────────────────────────────────────────────────────────────┐
│ 客户端 SyncedGraphBlockEntity.cachedEvalSnapshot              │
│   ↑ 被以下读取：                                               │
│   ├─ GraphEditor.clientTick()  [新增]                         │
│   │    └─ 遍历 DEBUG_PROBE 节点 → recordSample(snapshot.get)  │
│   └─ NodeRenderer.renderNodes()                               │
│        ├─ DEBUG_SIGNAL_GEN → 绘制 XY 图 + 控制点 + 当前 X 标记  │
│        └─ DEBUG_PROBE      → 绘制数值 + 迷你趋势图              │
└─────────────────────────────────────────────────────────────┘
                       ▲
┌──────────────────────────────┴───────────────────────────────┐
│ 视角书签：纯客户端，config/create_schematic_compute/bookmarks.json │
│   GraphEditor.camX/camY/zoom ← ClientBookmarkStore             │
└────────────────────────────────────────────────────────────────┘
```

### 1.2 三项功能的定位

| 功能      | 是否触达节点系统                        | 是否触达服务端              | 是否触达客户端配置         |
| ------- | ------------------------------- | -------------------- | ----------------- |
| 模拟输入节点  | 是（新增 NodeType + eval + 渲染 + 交互） | 是（eval 计算 signal）    | 否                 |
| 模拟显示屏节点 | 是（新增 NodeType + eval + 渲染）      | 是（eval pass-through） | 否                 |
| 视角书签    | 否                               | 否                    | 是（bookmarks.json） |

---

## 2. 功能一：模拟输入节点（DEBUG_SIGNAL_GEN）

### 2.1 方案选择

| 方案           | 描述                                          | 优点                          | 缺点                      | 推荐    |
| ------------ | ------------------------------------------- | --------------------------- | ----------------------- | ----- |
| A. 真实信号源     | 节点正常参与服务端 eval，输出 signal(time)              | 运行时真正可用；无需客户端预览基础设施；可驱动下游节点 | 偏离"调试专用"语义              | ✅ 推荐  |
| B. 调试专用（原规格） | eval 输出 0；客户端渲染时本地计算 signal 供"预览"           | 符合原规格"运行时无效"                | 运行时无用；需搭建客户端本地求值（与架构相悖） | ❌ 不推荐 |
| C. 混合        | 加 `debugMode` 参数；开启时 eval 输出 0，关闭时输出 signal | 两种模式兼得                      | 复杂度高                    | 可选    |

**推荐方案 A**。理由：原规格"运行时输出 0"的初衷是"避免调试节点影响真实逻辑"，但本架构中节点是显式添加的——玩家不连它就不会影响任何东西。让节点在运行时真正生成信号，反而能让它兼任"测试信号源"和"简易函数发生器"双重角色。如用户坚持调试语义，可采用方案 C（加一个 `debugMode` 布尔参数）。

### 2.2 数据模型

#### 2.2.1 NodeType 新增

`graph/NodeType.java`，在 `COMMENT` 之前新增：

```java
DEBUG_SIGNAL_GEN("debug_signal_gen", "node.create_schematic_compute.debug_signal_gen", 0, 1, "mode,amplitude,frequency,phase,dutyCycle,speed"),
```

- `inputs = 0`（可选触发输入见 2.2.3）
- `outputs = 1`（float 信号值）
- `paramNames` = 6 个参数，全部可通过 EditBox 编辑，且因 `editableParamCount()` 默认返回 `paramNames.length`，会自动获得 6 个参数引脚（可被连线覆盖，复用现有通用参数覆盖机制 L299-312）

#### 2.2.2 GraphNode 字段复用与新增

复用现有字段：

- `params[0]` = mode（0=CONST, 1=STEP, 2=SINE, 3=SQUARE, 4=TRIANGLE, 5=NOISE, 6=PULSE, 7=CUSTOM）
- `params[1]` = amplitude（默认 1.0）
- `params[2]` = frequency（默认 1.0）
- `params[3]` = phase（默认 0）
- `params[4]` = dutyCycle（默认 0.5）
- `params[5]` = speed（每 tick 时间推进量，默认 1/20）
- `formula` 字段 = 自定义公式字符串（mode==7 时生效，复用 `FormulaParser`）

新增字段（`GraphNode.java`，约 L60 附近）：

```java
// DEBUG_SIGNAL_GEN 控制点（X 固定排序 0~1，Y 为信号值），transient 不参与 NBT
// Control points for DEBUG_SIGNAL_GEN; transient, not serialized
public transient float[] debugCtrlX = new float[]{0f, 1f};
public transient float[] debugCtrlY = new float[]{0f, 0f};
```

> **控制点是否序列化？** 原规格要求"控制点坐标列表存入 NBT"。但若控制点仅用于"控制点插值"模式（mode=POINTS），且该模式不在 8 种预设内，可暂不序列化控制点（仅 session 内有效）。若需持久化，在 `save/load` 增加 `dcx/dcy` 键（详见 2.5）。

#### 2.2.3 触发引脚（可选）

原规格提到"触发引脚每来一个上升沿推进一个 X 步进"。这需要时序状态（类似 DELAY 的队列）。实现方式：

- 将 `inputs` 改为 1，新增一个触发输入引脚
- 在 `evalExt`（时序节点处理，L172）添加 `case DEBUG_SIGNAL_GEN`，维护 `Map<Integer, Float> debugTime` 状态
- 检测上升沿：比较上一 tick 触发值与当前值

**建议第一版不实现触发引脚**，仅做"每 tick 自动推进 time"。触发引脚作为后续增强。

### 2.3 求值行为（GraphEvaluator）

`graph/GraphEvaluator.java`，在 `eval()` 方法的 switch（L314）中新增 case：

```java
case DEBUG_SIGNAL_GEN -> {
    int mode = node.params.length > 0 ? (int)node.params[0] : 2; // 默认 SINE
    float amp = node.params.length > 1 ? node.params[1] : 1f;
    float freq = node.params.length > 2 ? node.params[2] : 1f;
    float phase = node.params.length > 3 ? node.params[3] : 0f;
    float duty = node.params.length > 4 ? node.params[4] : 0.5f;
    float speed = node.params.length > 5 ? node.params[5] : (1f/20f);
    // 时间推进：用服务端累计 tick（需在 evaluator 维护 debugTime Map，或在 RuntimeState 中存）
    float t = advanceDebugTime(node.id, speed); // 见下
    o[0] = computeSignal(mode, amp, freq, phase, duty, t, node.formula);
}
```

时间状态存储：在 `GraphEvaluator` 新增 `private final Map<Integer, Float> debugTime = new HashMap<>();`，`advanceDebugTime` 每次调用 `t += speed; t %= 1f`。该状态需在 `RuntimeState` 中持久化（类似 `pidState`），否则重载后时间归零（可接受）。

`computeSignal` 静态方法：

```java
static float computeSignal(int mode, float amp, float freq, float phase, float duty, float t, String formula) {
    return switch (mode) {
        case 0 -> amp;                                          // CONST
        case 1 -> t < phase ? 0 : amp;                           // STEP
        case 2 -> amp * (float)Math.sin(2*Math.PI*freq*t + phase); // SINE
        case 3 -> amp * ((t * freq) % 1.0 < duty ? 1 : -1);     // SQUARE
        case 4 -> {                                              // TRIANGLE
            double x = (t * freq) % 1.0;
            yield amp * (float)(x < 0.5 ? 4*x - 1 : 3 - 4*x);
        }
        case 5 -> amp * (float)(Math.random()*2 - 1);           // NOISE
        case 6 -> (t * freq) % 1.0 < 0.05 ? amp : 0;            // PULSE
        case 7 -> {                                              // CUSTOM
            try {
                var rpn = FormulaParser.compile(formula);
                var vars = Map.of("t", (double)t);
                yield (float) FormulaEvaluator.evaluate(rpn, vars);
            } catch (Exception e) { yield 0; }
        }
        default -> 0;
    };
}
```

> **注意**：`FormulaParser` 的 `sin/cos` 输入为**度**（见 FormulaParser.java L184 注释）。自定义公式中 `sin(t * 2 * PI)` 会按度计算导致错误。需在文档中说明用 `sin(t * 360)`，或在 `computeSignal` 中对 `t` 做度转换。建议前者（与现有 FORMULA 节点一致）。

### 2.4 渲染（NodeRenderer）

`blocks/NodeRenderer.java`，新增方法 `renderDebugSignalGen(GuiGraphics g, GraphNode n, ...)`，在 `renderNodes`（L1108 调用）中对 `DEBUG_SIGNAL_GEN` 类型调用。

节点尺寸：用 `WIDE_NW`（240px）宽度，高度 = `HH + PH * functionalInputs + 80`（80px 给 XY 图区域）。

绘制内容（全部用 `GuiGraphics`）：

1. **坐标网格**：浅灰线，`g.fill(x, y, x+1, y+H, CGL())` 每隔 30px 画竖线/横线
2. **曲线**：按 mode 调用 `computeSignal` 采样 60 个点，用密集 `fill` 像素段连线（或 `BufferBuilder` 画 `DebugLineRenderer`，需 `MultiBufferSource`）
3. **控制点**：圆形（用 `g.fill` 画 6x6 方块近似圆），仅在 mode==POINTS 或节点展开时显示
4. **当前 X 标记**：竖线 `g.fill(curX, gridTop, curX+1, gridBottom, 0xFFFF0000)`
5. **模式标签**：`g.drawString(font, modeName, x+4, y+2, ...)`

参数滑块复用现有 `EditState` 机制（展开节点后的编辑区），无需新增 UI 组件。

### 2.5 交互（GraphEditor）

`blocks/GraphEditor.java`：

**控制点拖拽**（`mouseClicked` L1395 + `mouseDragged`）：

- 在节点命中检测前，对展开的 `DEBUG_SIGNAL_GEN` 节点做控制点命中测试（6px 半径）
- 命中则进入 `draggingCtrlPoint` 状态（新增字段 `private int draggingCtrlPointNode = -1, draggingCtrlPointIdx = -1`）
- `mouseDragged` 更新 `debugCtrlY[idx]`（Y 值由鼠标 Y 转换为信号值）
- `mouseReleased` 提交（若需同步，发 `SET_PARAM` op 或新增 `SET_DEBUG_CTRL` op）

**双击添加控制点 / 右键删除**：

- `mouseClicked` btn==0 双击空白处 → 添加控制点（插入排序保持 X 递增）
- btn==1 点击控制点 → 删除

**模式切换**：通过参数 EditBox 修改 `params[0]`（复用现有参数编辑流程）。

### 2.6 序列化

`GraphNode.save`（L257）/ `load`（L323）：

- `params`、`formula` 已有通用序列化，无需改动
- 若需持久化控制点，新增：
  ```java
  // save:
  if (type == NodeType.DEBUG_SIGNAL_GEN && debugCtrlX != null) {
      t.putFloatArray("dcx", debugCtrlX);
      t.putFloatArray("dcy", debugCtrlY);
  }
  // load:
  if (type == NodeType.DEBUG_SIGNAL_GEN) {
      debugCtrlX = tag.getFloatArray("dcx");
      debugCtrlY = tag.getFloatArray("dcy");
      if (debugCtrlX.length == 0) { debugCtrlX = new float[]{0f,1f}; debugCtrlY = new float[]{0f,0f}; }
  }
  ```

### 2.7 撤销/重做

- 模式切换、参数调整：复用现有 `SET_PARAM` op（已支持，`recordOp` L626 模式）
- 控制点增删改：若控制点不序列化 → 不入栈（session-only）；若序列化 → 需新增 `SET_DEBUG_CTRL` op 类型 + `reverseOp` 支持
- **建议第一版控制点不入栈**（仅 session 内有效，避免新增 op 类型）

### 2.8 导出/封装过滤

`NodeType` 新增方法：

```java
public boolean isDebug() {
    return this == DEBUG_SIGNAL_GEN || this == DEBUG_PROBE;
}
```

在 `GraphEditor.exportEncapNode`（L3132）和封装子图过滤（L478-485）中，跳过 `isDebug()` 节点（或弹提示"调试节点将被移除"）。

### 2.9 兼容性矩阵

| 系统    | 兼容性                                          | 说明 |
| ----- | -------------------------------------------- | -- |
| 撤回/重做 | ✅ 参数调整入栈；控制点 session-only 不入栈                |    |
| 蓝图保存  | ✅ params + formula + 控制点(可选) 序列化             |    |
| 封装节点  | ✅ `isDebug()` 过滤                             |    |
| 导出    | ✅ `isDebug()` 跳过                             |    |
| 便携终端  | ✅ 走同一 GraphEditor                            |    |
| 多人协作  | ✅ params 变更经 SET_PARAM op 同步；控制点若不序列化则各客户端独立 |    |

---

## 3. 功能二：模拟显示屏节点（DEBUG_PROBE）

### 3.1 方案选择

探针需要"显示连接到它的信号值"。客户端无法直接求值，必须从 `EvalSnapshot` 读取。

| 方案                               | 描述                                                        | 推荐             |
| -------------------------------- | --------------------------------------------------------- | -------------- |
| A. 1 输入 + 1 隐藏输出（pass-through）   | eval 中 `o[0] = inputValue`；客户端读 `snapshot.get(nodeId, 0)` | ✅ 推荐           |
| B. 1 输入 + 0 输出 + 扩展 EvalSnapshot | 让 EvalSnapshot 也存输入值                                      | ❌ 改动核心数据结构，影响大 |

**推荐方案 A**。节点有 1 个输出引脚，但渲染时隐藏（或标注"探针输出"）。这样客户端通过 `cachedEvalSnapshot.get(probeNodeId, 0)` 即可拿到当前值，无需任何核心改动。

### 3.2 数据模型

#### NodeType 新增

```java
DEBUG_PROBE("debug_probe", "node.create_schematic_compute.debug_probe", 1, 1, "windowSize,autoScale"),
```

- `inputs = 1`（被监视信号）
- `outputs = 1`（pass-through，用于客户端读取）
- `paramNames` = 2（windowSize: 25/50/100；autoScale: 0/1）

#### GraphNode 新增字段

```java
// DEBUG_PROBE 历史采样缓冲，transient，不序列化
public transient float[] probeHistory = new float[100];
public transient int probeHistoryHead = 0;
public transient int probeHistoryCount = 0;
public transient boolean probeFrozen = false;
```

### 3.3 求值行为

`GraphEvaluator.eval` switch 新增：

```java
case DEBUG_PROBE -> {
    o[0] = graph.getInputValue(node.id, 0, outputs); // pass-through
}
```

一行即可。探针不影响图逻辑（输出 = 输入）。

### 3.4 客户端采样（clientTick）

**新增 `GraphEditor.clientTick()`**：

```java
public void clientTick() {
    var be = host instanceof Screen s ? ... : null; // 获取 SyncedGraphBlockEntity
    EvalSnapshot snap = getCachedEvalSnapshot(); // 从 BE 读取
    if (snap == null || snap == EvalSnapshot.EMPTY) return;
    for (GraphNode n : getGraph().nodes) {
        if (n.type != NodeType.DEBUG_PROBE) continue;
        if (n.probeFrozen) continue;
        float v = snap.get(n.id, 0);
        n.probeHistory[n.probeHistoryHead] = v;
        n.probeHistoryHead = (n.probeHistoryHead + 1) % n.probeHistory.length;
        if (n.probeHistoryCount < n.probeHistory.length) n.probeHistoryCount++;
    }
}
```

**调用点**：修改所有 `GraphEditor.Host` 实现的 `containerTick()`：

- `BlueprintScreen.containerTick()` (L78)
- `MonitorScreen.containerTick()` (L145)
- `ProgramComputerScreen` / `ControlSeatScreen` / `SensorScreen` / `SpeedProxyScreen` / `RadarScreen` 同类
- 在现有 `super.containerTick()` + 方块存在检查后，追加 `editor.clientTick();`

**获取 EvalSnapshot**：需在 `Host` 接口新增 `default EvalSnapshot getCachedEvalSnapshot() { return null; }`，各 Screen 实现从对应 BE 读取 `cachedEvalSnapshot`。

> **便携终端场景**：便携终端打开的 Screen 包装在匿名 `Screen` 中（PortableTerminalScreen L310-348），其 `containerTick` 未委托。需在包装 Screen 中也调用 `innerScreen` 的 `containerTick`，或确保 `editor.clientTick()` 被调用。

### 3.5 渲染（NodeRenderer）

新增 `renderDebugProbe(GuiGraphics g, GraphNode n, ...)`：

- 节点尺寸：`WIDE_NW`（240）宽，高度 = `HH + 60`（数值区 20 + 趋势图区 40）
- **数值显示**：`g.drawString(font, String.format("%.3f", currentValue), ...)`，颜色：绿色正常 / 红色超限 / 灰色无效
- **趋势图**：浅灰背景 `g.fill(...)`；折线用密集 `g.fill` 像素段连接 `probeHistory` 的最近 `windowSize` 个点；Y 轴自动缩放（找 min/max）或固定 ±10
- **冻结指示**：`probeFrozen` 时叠加 "FROZEN" 文字

### 3.6 交互

- **双击节点**：调整 `windowSize` / `autoScale`（复用参数 EditBox）
- **右键菜单**：新增"清除历史""冻结/解冻"选项 → 修改 transient 字段（不入栈、不同步，各客户端独立）

### 3.7 兼容性矩阵

| 系统    | 兼容性                                      | 说明 |
| ----- | ---------------------------------------- | -- |
| 撤回/重做 | ✅ 参数调整入栈；历史数据 transient 不入栈              |    |
| 蓝图保存  | ✅ 节点本身序列化（params）；历史数据不序列化               |    |
| 封装节点  | ✅ `isDebug()` 过滤                         |    |
| 导出    | ✅ `isDebug()` 跳过                         |    |
| 便携终端  | ⚠️ 需确保包装 Screen 调用 `editor.clientTick()` |    |
| 多人协作  | ✅ 每客户端独立采样自己的 `cachedEvalSnapshot`       |    |

---

## 4. 功能三：视角书签（View Bookmarks）

### 4.1 数据模型

新增类 `client/ClientBookmarkStore.java`：

```java
public record Bookmark(String name, float camX, float camY, float zoom) {}

public class ClientBookmarkStore {
    private static final Path CONFIG_DIR = Path.of("config", "create_schematic_compute");
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("bookmarks.json");
    private static final int MAX_BOOKMARKS = 50;

    private final List<Bookmark> bookmarks = new ArrayList<>();
    private Bookmark temporaryView = null;

    public void load() { /* GSON 反序列化 */ }
    public void save() { /* GSON 序列化，创建目录 */ }
    public void add(Bookmark b) { ... }
    public void remove(int idx) { ... }
    public Bookmark get(int idx) { ... }
    public List<Bookmark> all() { return Collections.unmodifiableList(bookmarks); }
    public Bookmark getTemporaryView() { return temporaryView; }
    public void setTemporaryView(Bookmark v) { temporaryView = v; save(); }
}
```

格式（`bookmarks.json`）：

```json
{
  "bookmarks": [
    {"name": "书签 1", "camX": 120.0, "camY": -80.0, "zoom": 1.5},
    ...
  ],
  "temporaryView": {"name": "", "camX": 0, "camY": 0, "zoom": 1.0}
}
```



> 项目已有用 GSON 写 JSON 的先例（`RecentColors.java`），可复用其模式。GSON 由 Minecraft 依赖提供（`com.google.gson`）。

### 4.2 GraphEditor 集成

`GraphEditor` 新增字段：

```java
private final ClientBookmarkStore bookmarks = new ClientBookmarkStore();
private boolean showBookmarkPanel = false;
// 视角过渡动画
private float transitionFromX, transitionFromY, transitionFromZoom;
private float transitionToX, transitionToY, transitionToZoom;
private long transitionStartMs = 0;
private static final long TRANSITION_DURATION_MS = 200;
```

构造函数中 `bookmarks.load()`。

### 4.3 快捷键（keyPressed L2570）

在现有快捷键链中新增（注意顺序：在 EditBox 聚焦判断之后，避免吞输入）：

| 快捷键                         | 行为            | 实现                                                               |
| --------------------------- | ------------- | ---------------------------------------------------------------- |
| `Ctrl+B` (66)               | 保存当前视角为书签     | 弹命名对话框（复用 `EditBox` + `showExportDialog` 模式）                     |
| `Ctrl+1`~~`Ctrl+9` (49~~57) | 跳转到第 N 个书签    | `startTransition(bookmarks.get(n))`                              |
| `Home` (268)                | 重置视角到 (0,0,1) | `startTransition(0, 0, 1)`                                       |
| `Ctrl+Shift+S`              | 保存临时视角        | `bookmarks.setTemporaryView(new Bookmark("", camX, camY, zoom))` |
| `Ctrl+Shift+L`              | 恢复临时视角        | `if (bookmarks.getTemporaryView() != null) startTransition(...)` |

> `Ctrl+B` 与浏览器/MC 无冲突。`Home` 键 MC 未占用。`Ctrl+数字` 需检查是否与热栏冲突——当前代码中 `Ctrl+D` 已用于复制，`Ctrl+1~9` 未被占用。

### 4.4 视角过渡动画

在 `clientTick()` 或 `renderBg()` 开头推进：

```java
if (transitionStartMs > 0) {
    long elapsed = System.currentTimeMillis() - transitionStartMs;
    float t = Math.min(1f, elapsed / (float)TRANSITION_DURATION_MS);
    // ease-in-out
    float e = t < 0.5f ? 2*t*t : 1 - (float)Math.pow(-2*t+2, 2)/2;
    camX = lerp(transitionFromX, transitionToX, e);
    camY = lerp(transitionFromY, transitionToY, e);
    zoom = lerp(transitionFromZoom, transitionToZoom, e);
    if (t >= 1f) transitionStartMs = 0;
}
```

放在 `renderBg` 开头（每帧推进）比 `clientTick`（每 tick）更平滑。

### 4.5 UI

**工具栏按钮**：在 `renderButtons`（L1111 调用）中新增书签图标按钮（📐 或用文字"BM"），点击切换 `showBookmarkPanel`。

**书签列表面板**：`showBookmarkPanel` 为 true 时绘制侧边面板：

- 列出所有书签（名称 + 跳转/删除按钮）
- "新建"按钮（等同 Ctrl+B）
- "重置视角 (Home)"按钮

**临时视角自动保存/恢复**：

- 编辑器关闭时（`removed()` 或 `onClose()`）：`bookmarks.setTemporaryView(new Bookmark("", camX, camY, zoom))`
- 编辑器打开时（构造函数）：`if (bookmarks.getTemporaryView() != null) { camX = tv.camX; ... }`（直接设置，不动画）

### 4.6 兼容性矩阵

| 系统    | 兼容性               | 说明 |
| ----- | ----------------- | -- |
| 撤回/重做 | ❌ 不涉及（视角操作不影响节点图） |    |
| 蓝图保存  | ❌ 不涉及（书签是客户端本地数据） |    |
| 封装节点  | ❌ 不涉及             |    |
| 便携终端  | ✅ 全局有效（书签与方块无关）   |    |
| 多人协作  | ✅ 每玩家独立保存自己的书签    |    |

---

## 5. 跨功能共享改动清单

### 5.1 NodeType.java

- 新增 `DEBUG_SIGNAL_GEN`、`DEBUG_PROBE` 枚举值
- 新增 `isDebug()` 方法
- `editableParamCount()` 中将两者加入返回 0 的列表（若不想要参数引脚）或保持默认（想要参数引脚）

### 5.2 GraphNode.java

- 新增 `debugCtrlX/Y`（transient）、`probeHistory/head/count/frozen`（transient）
- 构造函数初始化 `DEBUG_SIGNAL_GEN` 的 params 默认值
- `save/load` 增加控制点序列化（可选）

### 5.3 GraphEvaluator.java

- `eval` switch 新增 `DEBUG_SIGNAL_GEN`、`DEBUG_PROBE` case
- 新增 `debugTime` Map + `advanceDebugTime` 方法
- `computeSignal` 静态方法

### 5.4 NodeRenderer.java

- 新增 `renderDebugSignalGen`、`renderDebugProbe` 方法
- `renderNodes` 分派调用
- 节点菜单分类数组（L187-200）新增 "debug" 分类：`new NodeCategory("category.create_schematic_compute.debug", new NodeType[]{DEBUG_SIGNAL_GEN, DEBUG_PROBE})`
- `nw()`/`nh()` 对新类型返回定制尺寸

### 5.5 GraphEditor.java

- 新增 `clientTick()` 方法（采样 + 视角动画推进）
- 新增书签字段、快捷键处理、书签面板渲染、视角过渡
- 新增控制点拖拽状态与命中检测
- `exportEncapNode` + 子图过滤增加 `isDebug()` 过滤

### 5.6 Host 接口 + 各 Screen

- `Host` 新增 `default EvalSnapshot getCachedEvalSnapshot() { return null; }`
- 7 个 Screen 的 `containerTick()` 追加 `editor.clientTick()`
- `PortableTerminalScreen` 的包装 Screen 确保委托 `containerTick`

### 5.7 ClientBookmarkStore.java（新文件）

- 完整书签读写逻辑

### 5.8 i18n（assets/lang/zh_cn.json + en_us.json）

- `node.create_schematic_compute.debug_signal_gen` / `debug_probe`
- `category.create_schematic_compute.debug`
- `pin.create_schematic_compute.*` 新引脚标签
- 书签 UI 文本

---

## 6. 风险与未决问题

| #  | 风险/问题                                                       | 影响                                  | 建议                                   |
| -- | ----------------------------------------------------------- | ----------------------------------- | ------------------------------------ |
| R1 | `DEBUG_SIGNAL_GEN` 的时间状态 `debugTime` 需跨 tick 持久化，否则服务端重载后归零 | 运行时信号相位跳变                           | 第一版可接受（重载少）；后续可存入 `RuntimeState`     |
| R2 | 控制点拖拽若需多人同步，要新增 `SET_DEBUG_CTRL` op 类型                      | 增加 op 协议复杂度                         | 第一版控制点 session-only，不同步              |
| R3 | `FormulaParser.sin/cos` 输入为度，自定义公式中 `sin(t*2*PI)` 会出错       | 用户公式结果错误                            | 文档说明用 `sin(t*360)`；或新增 `rad()` 包装函数  |
| R4 | 便携终端包装 Screen 的 `containerTick` 是否委托 `innerScreen`          | 探针在便携终端打开时不采样                       | 需验证并补委托                              |
| R5 | `Ctrl+1~9` 与未来可能的快捷键冲突                                      | 潜在键位竞争                              | 文档登记占用                               |
| R6 | 节点菜单新增 "debug" 分类，但部分 Screen 用 `nodeFilter` 屏蔽节点            | 调试节点在某些 Screen（如 SensorScreen）可能不可用 | 确认调试节点在所有 GraphEditor 中均可用（不过滤）      |
| R7 | `EvalSnapshot` 在蓝图未运行时为 `EMPTY`                             | 探针显示 0 或无数据                         | 探针应显示"未运行"提示；或在编辑器内即使未运行也触发一次轻量 eval |
| R8 | 控制点插值模式（mode=POINTS）未在 8 种预设中                               | 原规格提到"控制点插值"但未列入 mode 枚举            | 可作为第 9 种 mode，或用控制点编辑预设的参数           |

---

## 7. 实施顺序建议

按依赖关系排序，每步可独立验证：

**第 1 步：基础设施**

- `NodeType` 新增两个枚举值 + `isDebug()`
- `GraphNode` 新增 transient 字段 + 构造默认值
- `GraphEvaluator.eval` 新增两个 case（DEBUG_PROBE 先做 pass-through；DEBUG_SIGNAL_GEN 先做 SINE 一种）
- `NodeRenderer` 节点菜单新增 "debug" 分类
- i18n 键
- **验证**：能从菜单放置节点，连线，运行时 SINE 节点输出正弦波，PROBE 节点 pass-through

**第 2 步：DEBUG_SIGNAL_GEN 完整化**

- 实现 `computeSignal` 全部 8 种 mode
- 实现 `debugTime` 状态推进
- 实现自定义公式 mode（复用 `FormulaParser`）
- **验证**：各 mode 输出正确，公式 mode 可用

**第 3 步：DEBUG_SIGNAL_GEN 渲染与交互**

- `NodeRenderer.renderDebugSignalGen`：XY 网格 + 曲线 + 当前 X 标记
- `GraphEditor` 控制点拖拽、双击添加、右键删除
- 参数 EditBox（mode/amplitude/frequency/phase/dutyCycle/speed）
- **验证**：拖拽控制点、切换 mode、调整参数均生效

**第 4 步：DEBUG_PROBE 客户端采样**

- `Host.getCachedEvalSnapshot()` + 各 Screen 实现
- `GraphEditor.clientTick()` 采样逻辑
- 各 Screen `containerTick` 调用 `editor.clientTick()`
- 便携终端包装 Screen 委托修复
- **验证**：探针每 tick 采样，历史缓冲正确滚动

**第 5 步：DEBUG_PROBE 渲染与交互**

- `NodeRenderer.renderDebugProbe`：数值 + 趋势图
- 双击调整 windowSize/autoScale
- 右键清除/冻结
- **验证**：趋势图实时滚动，冻结/清除可用

**第 6 步：导出/封装过滤**

- `exportEncapNode` + 子图过滤增加 `isDebug()` 检查
- **验证**：导出时调试节点被跳过

**第 7 步：视角书签**

- `ClientBookmarkStore` 完整实现
- `GraphEditor` 书签字段 + load/save
- 快捷键（Ctrl+B/1-9/Home/Ctrl+Shift+S/L）
- 书签面板 UI
- 视角过渡动画
- 临时视角自动保存/恢复
- **验证**：书签增删跳转、过渡动画、临时视角、跨会话持久化

**第 8 步：收尾**

- 控制点序列化（若决定持久化）
- i18n 全量补全
- 兼容性矩阵逐项验证
- R1-R8 风险项处理

---

## 附录 A：受影响文件清单

| 文件                                   | 改动类型                                                |
| ------------------------------------ | --------------------------------------------------- |
| `graph/NodeType.java`                | 新增枚举值 + `isDebug()`                                 |
| `graph/GraphNode.java`               | 新增 transient 字段 + 构造默认 + save/load                  |
| `graph/GraphEvaluator.java`          | eval switch 新增 case + `computeSignal` + `debugTime` |
| `blocks/NodeRenderer.java`           | 新增渲染方法 + 菜单分类 + 尺寸                                  |
| `blocks/GraphEditor.java`            | `clientTick` + 书签 + 控制点交互 + 过滤                      |
| `blocks/GraphEditor.Host`（内部接口）      | 新增 `getCachedEvalSnapshot()`                        |
| `blocks/BlueprintScreen.java`        | `containerTick` 调用 + `getCachedEvalSnapshot`        |
| `blocks/MonitorScreen.java`          | 同上                                                  |
| `blocks/ProgramComputerScreen.java`  | 同上                                                  |
| `blocks/ControlSeatScreen.java`      | 同上                                                  |
| `blocks/SensorScreen.java`           | 同上                                                  |
| `blocks/SpeedProxyScreen.java`       | 同上                                                  |
| `blocks/RadarScreen.java`            | 同上                                                  |
| `client/PortableTerminalScreen.java` | 包装 Screen containerTick 委托                          |
| `client/ClientBookmarkStore.java`    | **新文件**                                             |
| `assets/lang/zh_cn.json`             | i18n                                                |
| `assets/lang/en_us.json`             | i18n                                                |

## 附录 B：原规格偏离记录

| 原规格条目                                     | 偏离                                       | 理由                     |
| ----------------------------------------- | ---------------------------------------- | ---------------------- |
| `DebugSignalGenNode extends AbstractNode` | 改为新增 `NodeType` 枚举值                      | 无 AbstractNode 基类      |
| `isCompilable()` 返回 false                 | 改为 `NodeType.isDebug()` + 导出过滤           | 无 isCompilable 机制      |
| `evaluate()` 客户端调用                        | 改为服务端 `GraphEvaluator.eval`              | 客户端禁止实例化 evaluator     |
| 运行时输出 0                                   | 改为运行时输出真实信号（方案 A）                        | 原方案需客户端预览基础设施，成本高收益低   |
| `GraphEditor.tick()` 每 tick 采样            | 改为新增 `clientTick()` 由 `containerTick` 调用 | GraphEditor 无 tick     |
| `Tessellator` 绘制                          | 改为 `GuiGraphics.fill/drawString`         | 与现有渲染风格一致              |
| 控制点存入 NBT                                 | 改为 transient（第一版）                        | 简化；后续可加                |
| `DebugProbeNode` 无输出引脚                    | 改为 1 隐藏输出（pass-through）                  | 客户端需从 EvalSnapshot 读取值 |
