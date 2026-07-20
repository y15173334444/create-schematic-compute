# v1.2.4 调试工具链 — 开发技术规格

> 本文档为开发实现规格，覆盖三项功能：模拟输入节点 `DEBUG_SIGNAL_GEN`、模拟显示节点 `DEBUG_PROBE`、视角书签系统。
> 所有代码片段基于 `5c5b39c` 实际架构，文件路径相对仓库根 `create-schematic-compute/`。
> 包根：`io.github.y15173334444.create_schematic_compute`（下文简称 `csc`）。

---

## 目录

1. [架构总览](#1-架构总览)
2. [共享基础设施](#2-共享基础设施)
3. [模拟输入节点 DEBUG_SIGNAL_GEN](#3-模拟输入节点-debug_signal_gen)
4. [模拟显示节点 DEBUG_PROBE](#4-模拟显示节点-debug_probe)
5. [视角书签系统](#5-视角书签系统)
6. [改动文件清单](#6-改动文件清单)
7. [实施顺序](#7-实施顺序)
8. [验收清单](#8-验收清单)

---

## 1. 架构总览

### 1.1 数据流

```
服务端 BlockEntity.tick()
  → GraphEvaluator.evaluate()
      ├─ DEBUG_SIGNAL_GEN : 推进内部时间 t，按 mode 计算 signal(t)，写 o[0]
      └─ DEBUG_PROBE      : o[0] = 输入引脚值（pass-through）
  → EvalSnapshot.capture(outputs)
  → ClientboundGraphEvalPacket 广播

客户端 SyncedGraphBlockEntity.cachedEvalSnapshot
  ← GraphEditor.clientTick()  [新增]
      ├─ 遍历 DEBUG_PROBE：snap.get(id,0) → 写入 probeHistory 环形缓冲
      └─ 推进书签视角过渡动画
  ← NodeRenderer.renderNodes()
      ├─ DEBUG_SIGNAL_GEN : 绘制 XY 网格 + 波形曲线 + 控制点 + 当前 X 标记
      └─ DEBUG_PROBE      : 绘制当前值 + 迷你趋势折线图

视角书签：纯客户端
  config/create_schematic_compute/bookmarks.json  ← ClientBookmarkStore
  GraphEditor.camX/camY/zoom  ← 过渡动画每帧推进
```

### 1.2 设计要点

| 功能 | 节点系统 | 服务端 | 客户端配置 |
|---|---|---|---|
| 模拟输入节点 | 新增 `NodeType` + eval case + 渲染 + 交互 | eval 计算 signal | 否 |
| 模拟显示节点 | 新增 `NodeType` + eval case + 渲染 | eval pass-through | 否 |
| 视角书签 | 不触达 | 不触达 | `bookmarks.json` |

### 1.3 关键架构约束（实现须遵守）

- 节点是单一数据类 `GraphNode`（`graph/GraphNode.java`），所有类型共用字段，无基类继承。
- 求值集中在服务端 `GraphEvaluator.eval()`（巨型 switch，`graph/GraphEvaluator.java` L314）。客户端**禁止**实例化 `GraphEvaluator`，只读 `EvalSnapshot`。
- `GraphEditor`（`blocks/GraphEditor.java`）无 `tick()`；宿主 `Screen.containerTick()` 是客户端 tick 钩子。
- 渲染统一用 `GuiGraphics.fill/drawString/renderOutline`，不直接用 `Tessellator`。
- 节点类型为编译期 `NodeType` 枚举，新增类型必须修改枚举。
- 客户端配置无 NeoForge `ModConfigSpec`，用手写 JSON（GSON，参考 `client/colorpicker/RecentColors.java`）。

---

## 2. 共享基础设施

本节改动被三项功能共用，须先完成。

### 2.1 NodeType 扩展

**文件**：`src/main/java/io/github/y15173334444/create_schematic_compute/graph/NodeType.java`

在 `COMMENT` 之前（L92 前）新增两个枚举值：

```java
// Debug tools / 调试工具
DEBUG_SIGNAL_GEN("debug_signal_gen", "node.create_schematic_compute.debug_signal_gen", 0, 1, "mode,amplitude,frequency,phase,dutyCycle,speed"),
DEBUG_PROBE("debug_probe", "node.create_schematic_compute.debug_probe", 1, 1, "windowSize,autoScale"),
COMMENT("comment", "node.create_schematic_compute.comment", 0, 0, "");
```

字段含义：
- `DEBUG_SIGNAL_GEN`：0 输入，1 输出（float 信号值），6 个参数（自动获得 6 个参数引脚，可被连线覆盖，复用 `GraphEvaluator` L299-312 通用参数覆盖机制）。
- `DEBUG_PROBE`：1 输入（被监视信号），1 输出（pass-through，渲染时标注为探针输出），2 个参数。

新增 `isDebug()` 方法（放在枚举体末尾，`getTitle()` 之前）：

```java
/** 是否为调试节点（导出/封装时过滤）。 / Whether this is a debug node (filtered on export/encapsulate). */
public boolean isDebug() {
    return this == DEBUG_SIGNAL_GEN || this == DEBUG_PROBE;
}
```

`editableParamCount()`（L133）默认返回 `paramNames.length`，两个新类型不在异常列表中，因此自动获得参数引脚，**无需修改**。

`inputLabel`/`outputLabel` 的 `default` 分支已返回 `pk("in")` / `""`，对调试节点足够。如需更友好标签，在 switch 中补 case（可选）。

### 2.2 GraphNode 字段扩展

**文件**：`src/main/java/io/github/y15173334444/create_schematic_compute/graph/GraphNode.java`

在现有 transient 字段附近（`remoteLerpT` 等，约 L57-58 之后）新增：

```java
// ── DEBUG_SIGNAL_GEN 控制点（transient，session 内有效，不序列化）──
// Control points for DEBUG_SIGNAL_GEN; transient, session-only, not serialized.
// X 固定排序递增（0~1），Y 为信号值。默认两点：起点 (0,0)、终点 (1,0)。
public transient float[] debugCtrlX = new float[]{0f, 1f};
public transient float[] debugCtrlY = new float[]{0f, 0f};
// 自定义公式编译缓存（避免每 tick 重新编译）
public transient java.util.List<Object> debugFormulaRpn;

// ── DEBUG_PROBE 历史采样（transient，不序列化）──
// Ring buffer of recent samples; transient, not serialized.
public transient float[] probeHistory = new float[100];
public transient int probeHead = 0;
public transient int probeCount = 0;
public transient boolean probeFrozen = false;
```

构造函数 `GraphNode(int id, NodeType type, float x, float y)`（L155）中，在 `GAMEPAD_BUTTON` 默认值之后追加：

```java
if (type == NodeType.DEBUG_SIGNAL_GEN) {
    this.params[0] = 2f;      // mode = SINE
    this.params[1] = 1.0f;    // amplitude
    this.params[2] = 1.0f;    // frequency
    this.params[3] = 0f;      // phase
    this.params[4] = 0.5f;    // dutyCycle
    this.params[5] = 1f / 20f; // speed (每 tick 推进 1/20，1 秒循环一次)
}
if (type == NodeType.DEBUG_PROBE) {
    this.params[0] = 50f;     // windowSize = 50 ticks
    this.params[1] = 1f;      // autoScale = 1 (开启)
}
```

`outputValues` 已由 `this.outputValues = new float[... type.outputs]`（L217）自动分配为长度 1，**无需改动**。

### 2.3 序列化

`GraphNode.save()`（L257）和 `load()`（L323）无需改动 —— 调试节点的 `params` 和 `formula` 已由通用逻辑序列化；新增字段全部 `transient`，不入 NBT。

> 控制点与历史缓冲设计为 session-only。若未来需持久化控制点，在 `save` 末尾追加 `t.putFloatArray("dcx", debugCtrlX)` 与 `t.putFloatArray("dcy", debugCtrlY)`，`load` 对应读取。本规格不实现。

### 2.4 GraphEditor.Host 接口扩展

**文件**：`src/main/java/io/github/y15173334444/create_schematic_compute/blocks/GraphEditor.java`

在 `Host` 接口（L40-68）的 `getEditor()` 之前新增：

```java
/** 获取客户端缓存的求值快照（供 DEBUG_PROBE 采样）。服务端或无 BE 时返回 null。
 *  Get the client-cached eval snapshot (for DEBUG_PROBE sampling). Null on server or no BE. */
default io.github.y15173334444.create_schematic_compute.graph.EvalSnapshot getCachedEvalSnapshot() { return null; }
```

### 2.5 各 Host Screen 实现

需修改 7 个 Screen 的 `containerTick()` 与 `getCachedEvalSnapshot()`。以 `BlueprintScreen` 为模板：

**文件**：`src/main/java/io/github/y15173334444/create_schematic_compute/blocks/BlueprintScreen.java`

`containerTick()`（L78）改为：

```java
@Override protected void containerTick() {
    super.containerTick();
    if (minecraft != null && minecraft.level != null && menu.blockPos != null) {
        if (!(minecraft.level.getBlockEntity(menu.blockPos) instanceof BlueprintBlockEntity)) {
            onClose();
            return;
        }
    }
    editor.clientTick(); // [新增] 调试采样 + 书签动画
}

@Override public io.github.y15173334444.create_schematic_compute.graph.EvalSnapshot getCachedEvalSnapshot() {
    BlueprintBlockEntity be = getBE();
    return be != null ? be.cachedEvalSnapshot : null;
}
```

对其余 6 个 Screen 做同样改动：`MonitorScreen`、`ProgramComputerScreen`、`ControlSeatScreen`、`SensorScreen`、`SpeedProxyScreen`、`RadarScreen`。各 Screen 的 `getXxxBE()` 方法名不同，按实际方法调用。

### 2.6 便携终端委托修复

**文件**：`src/main/java/io/github/y15173334444/create_schematic_compute/client/PortableTerminalScreen.java`

`openBlockUI()`（L295）创建的匿名包装 `Screen`（L310-348）需补 `containerTick` 委托，确保内部 Screen 的 `editor.clientTick()` 被调用：

```java
Screen wrapper = new Screen(inner.getTitle()) {
    { this.minecraft = mc; }
    @Override public void containerTick() { inner.containerTick(); } // [新增]
    @Override public void render(GuiGraphics g, int mx, int my, float pt) { inner.render(g, mx, my, pt); }
    // ... 其余委托保持不变
};
```

### 2.7 i18n

**文件**：`src/main/resources/assets/create_schematic_compute/lang/zh_cn.json` 与 `en_us.json`

新增键：

```json
{
  "node.create_schematic_compute.debug_signal_gen": "信号发生器",
  "node.create_schematic_compute.debug_probe": "信号探针",
  "category.create_schematic_compute.debug": "调试",
  "pin.create_schematic_compute.signal_out": "信号",
  "pin.create_schematic_compute.probe_in": "输入",
  "pin.create_schematic_compute.probe_out": "镜像",
  "pin.create_schematic_compute.mode": "模式",
  "pin.create_schematic_compute.amplitude": "幅值",
  "pin.create_schematic_compute.frequency": "频率",
  "pin.create_schematic_compute.phase": "相位",
  "pin.create_schematic_compute.duty_cycle": "占空比",
  "pin.create_schematic_compute.speed": "速度",
  "pin.create_schematic_compute.window_size": "窗口",
  "pin.create_schematic_compute.auto_scale": "自动缩放"
}
```

英文版对应翻译。

---

## 3. 模拟输入节点 DEBUG_SIGNAL_GEN

### 3.1 功能规格

在编辑器内生成测试信号，支持 8 种模式：常量、阶跃、正弦、方波、三角波、噪声、脉冲、自定义公式。节点主体内嵌 XY 坐标图，可拖动控制点、双击添加、右键删除。每 tick 自动推进时间，输出当前信号值。

### 3.2 信号模式定义

**新文件**：`src/main/java/io/github/y15173334444/create_schematic_compute/graph/DebugSignals.java`

```java
package io.github.y15173334444.create_schematic_compute.graph;

import java.util.List;
import java.util.Map;

/** DEBUG_SIGNAL_GEN 的信号计算（无状态静态方法）。
 *  Signal computation for DEBUG_SIGNAL_GEN (stateless static methods). */
public final class DebugSignals {

    public static final int
        MODE_CONSTANT = 0, MODE_STEP = 1, MODE_SINE = 2, MODE_SQUARE = 3,
        MODE_TRIANGLE = 4, MODE_NOISE = 5, MODE_PULSE = 6, MODE_CUSTOM = 7;

    private DebugSignals() {}

    /** 计算信号值。t 为归一化时间（0~1 循环）。
     *  Compute signal value. t is normalized time (0~1 looping). */
    public static float compute(int mode, float amp, float freq, float phase,
                                float duty, float t, String formula, List<Object> cachedRpn) {
        // 规整 t 到 [0,1)
        double tn = t - Math.floor(t);
        return switch (mode) {
            case MODE_CONSTANT -> amp;
            case MODE_STEP -> tn < phase ? 0f : amp;
            case MODE_SINE -> amp * (float) Math.sin(2 * Math.PI * freq * tn + phase);
            case MODE_SQUARE -> amp * (((tn * freq) % 1.0) < duty ? 1f : -1f);
            case MODE_TRIANGLE -> {
                double x = (tn * freq) % 1.0;
                if (x < 0) x += 1.0;
                yield amp * (float) (x < 0.5 ? 4 * x - 1 : 3 - 4 * x);
            }
            case MODE_NOISE -> amp * (float) (Math.random() * 2 - 1);
            case MODE_PULSE -> (((tn * freq) % 1.0) < 0.05) ? amp : 0f;
            case MODE_CUSTOM -> evalFormula(formula, cachedRpn, tn);
            default -> 0f;
        };
    }

    /** 自定义公式求值。变量 t 为 0~1。
     *  注意：FormulaParser 的 sin/cos 输入为度，故 sin(2π·t) 应写作 sin(t*360)。
     *  Custom formula evaluation. Variable t is 0~1.
     *  Note: FormulaParser sin/cos take degrees, so sin(2π·t) is written as sin(t*360). */
    private static float evalFormula(String formula, List<Object> cachedRpn, double t) {
        if (formula == null || formula.isBlank()) return 0f;
        try {
            List<Object> rpn = cachedRpn;
            if (rpn == null) {
                rpn = FormulaParser.compile(formula);
            }
            return (float) FormulaParser.evaluate(rpn, Map.of("t", t));
        } catch (Exception e) {
            return 0f;
        }
    }

    public static List<Object> compileFormula(String formula) {
        if (formula == null || formula.isBlank()) return null;
        try { return FormulaParser.compile(formula); }
        catch (Exception e) { return null; }
    }

    public static String modeName(int mode) {
        return switch (mode) {
            case 0 -> "CONST";
            case 1 -> "STEP";
            case 2 -> "SINE";
            case 3 -> "SQUARE";
            case 4 -> "TRIANGLE";
            case 5 -> "NOISE";
            case 6 -> "PULSE";
            case 7 -> "CUSTOM";
            default -> "?";
        };
    }
}
```

> **公式角度约定**：`FormulaParser` 的 `sin/cos/tan` 输入为度（见 `FormulaParser.java` L184 注释）。自定义公式中要表达 `sin(2π·f·t)`，应写作 `sin(t * f * 360)`。在节点 UI 提示与文档中说明此约定。

### 3.3 服务端求值

**文件**：`src/main/java/io/github/y15173334444/create_schematic_compute/graph/GraphEvaluator.java`

在类字段区（约 L25-48 附近）新增时间状态：

```java
/** DEBUG_SIGNAL_GEN 的内部时间状态（nodeId → 归一化时间 0~1）。
 *  Internal time state for DEBUG_SIGNAL_GEN (nodeId → normalized time 0~1). */
private final Map<Integer, Float> debugTime = new java.util.HashMap<>();
```

在 `eval()` 方法的 switch（L314）中，`CONST` case 之前或之后新增：

```java
case DEBUG_SIGNAL_GEN -> {
    int mode = node.params.length > 0 ? (int) node.params[0] : 2;
    float amp = node.params.length > 1 ? node.params[1] : 1f;
    float freq = node.params.length > 2 ? node.params[2] : 1f;
    float phase = node.params.length > 3 ? node.params[3] : 0f;
    float duty = node.params.length > 4 ? node.params[4] : 0.5f;
    float speed = node.params.length > 5 ? node.params[5] : (1f / 20f);
    float t = debugTime.getOrDefault(node.id, 0f);
    t += speed;
    t -= Math.floor(t); // 规整到 [0,1)
    debugTime.put(node.id, t);
    // 编译缓存（避免每 tick 重新编译公式）
    if (mode == DebugSignals.MODE_CUSTOM && node.debugFormulaRpn == null) {
        node.debugFormulaRpn = DebugSignals.compileFormula(node.formula);
    }
    o[0] = DebugSignals.compute(mode, amp, freq, phase, duty, t, node.formula, node.debugFormulaRpn);
}
```

> **时间状态持久化**：`debugTime` 是 evaluator 实例字段，服务端 BE 重载后会归零（信号相位跳变）。第一版可接受（重载发生频率低）。若需持久化，将 `debugTime` 内容存入 `RuntimeState`（参考 `pidState` 的处理方式，`GraphEvaluator` L60 `restoreSubState`）。

### 3.4 渲染

**文件**：`src/main/java/io/github/y15173334444/create_schematic_compute/blocks/NodeRenderer.java`

#### 3.4.1 尺寸

修改 `nw()`（L164）和 `nh()`（L171）：

```java
public static int nw(GraphNode n) {
    if (n == null) return NW;
    if (n.type == NodeType.COMMENT) return Math.round(n.commentWidth);
    if (n.type == NodeType.FORMULA) return WIDE_NW;
    if (n.type == NodeType.DEBUG_SIGNAL_GEN || n.type == NodeType.DEBUG_PROBE) return WIDE_NW; // [新增]
    return NW;
}

public static float nh(GraphNode n) {
    if (n == null) return HH + PH * 2;
    if (n.type == NodeType.COMMENT) return n.commentHeight;
    float base = HH + PH * (n.functionalInputs() + n.outputs());
    if (n.type == NodeType.DEBUG_SIGNAL_GEN) return base + 84; // [新增] XY 图区域
    if (n.type == NodeType.DEBUG_PROBE) return base + 64;      // [新增] 数值 + 趋势图
    return base;
}
```

#### 3.4.2 节点菜单分类

在 `CATEGORIES` 数组（L186-201）末尾、`annotation` 之前新增：

```java
new NodeCategory("category.create_schematic_compute.debug",
    new NodeType[]{NodeType.DEBUG_SIGNAL_GEN, NodeType.DEBUG_PROBE}),
```

#### 3.4.3 渲染方法

在 `renderNodes` 分派逻辑中（找到现有按 type 分派的位置），对 `DEBUG_SIGNAL_GEN` 调用新方法：

```java
/** 绘制 DEBUG_SIGNAL_GEN 节点：XY 坐标图 + 波形曲线 + 控制点 + 当前 X 标记。 */
private void renderDebugSignalGen(GuiGraphics g, GraphNode n,
        float camX, float camY, float zoom, int screenX, int screenY) {
    int w = nw(n);
    float bodyH = HH + PH * (n.functionalInputs() + n.outputs());
    // XY 图区域：节点主体下方 84px
    int chartX = screenX;
    int chartY = screenY + (int) bodyH;
    int chartW = w;
    int chartH = 80;

    // 背景
    g.fill(chartX, chartY, chartX + chartW, chartY + chartH, 0xFF1A1A2E);

    // 网格线（每 1/4 一条浅灰线）
    int gridCol = 0xFF2A2A4E;
    for (int i = 1; i < 4; i++) {
        int gx = chartX + chartW * i / 4;
        g.fill(gx, chartY, gx + 1, chartY + chartH, gridCol);
        int gy = chartY + chartH * i / 4;
        g.fill(chartX, gy, chartX + chartW, gy + 1, gridCol);
    }
    // 中线（0 值线）
    int midY = chartY + chartH / 2;
    g.fill(chartX, midY, chartX + chartW, midY + 1, 0xFF3A3A6E);

    // 读取参数
    int mode = n.params.length > 0 ? (int) n.params[0] : 2;
    float amp = n.params.length > 1 ? n.params[1] : 1f;
    float freq = n.params.length > 2 ? n.params[2] : 1f;
    float phase = n.params.length > 3 ? n.params[3] : 0f;
    float duty = n.params.length > 4 ? n.params[4] : 0.5f;

    // 绘制波形曲线（采样 60 点）
    int samples = 60;
    int prevPX = -1, prevPY = -1;
    int curveCol = 0xFF4ADE80;
    for (int i = 0; i <= samples; i++) {
        float t = (float) i / samples;
        float v = DebugSignals.compute(mode, amp, freq, phase, duty, t, n.formula, n.debugFormulaRpn);
        int px = chartX + (int) (t * chartW);
        int py = midY - (int) ((v / Math.max(amp, 0.001f)) * (chartH / 2f - 2));
        if (prevPX >= 0) {
            drawLine(g, prevPX, prevPY, px, py, curveCol);
        }
        prevPX = px; prevPY = py;
    }

    // 控制点（仅 CUSTOM 模式或节点展开时显示）
    boolean showCtrl = (mode == DebugSignals.MODE_CUSTOM) || expandedSetContains(n.id);
    if (showCtrl && n.debugCtrlX != null) {
        for (int i = 0; i < n.debugCtrlX.length; i++) {
            int cpx = chartX + (int) (n.debugCtrlX[i] * chartW);
            int cpy = midY - (int) (n.debugCtrlY[i] * (chartH / 2f - 2));
            g.fill(cpx - 2, cpy - 2, cpx + 3, cpy + 3, 0xFFFBBF24);
        }
    }

    // 当前 X 标记（从 EvalSnapshot 或 GraphEditor 读取当前 t —— 此处用节点 outputValues 推断不可行，
    //   改为从 GraphEditor 缓存的服务端 t 读取；若不可得，省略此标记）
    // 简化：此处不画当前 X 标记，因 t 在服务端。如需显示，见 3.4.4。

    // 模式标签
    g.drawString(font, DebugSignals.modeName(mode), chartX + 4, chartY + 2, 0xFFCCCCCC);
}

/** 逐像素画线（GuiGraphics 无直接画线 API）。 */
private static void drawLine(GuiGraphics g, int x0, int y0, int x1, int y1, int col) {
    int dx = Math.abs(x1 - x0), dy = Math.abs(y1 - y0);
    int sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1;
    int err = dx - dy;
    int x = x0, y = y0;
    while (true) {
        g.fill(x, y, x + 1, y + 1, col);
        if (x == x1 && y == y1) break;
        int e2 = 2 * err;
        if (e2 > -dy) { err -= dy; x += sx; }
        if (e2 < dx) { err += dx; y += sy; }
    }
}
```

> `expandedSetContains` 需从 `GraphEditor` 传入展开节点集合，或由 `GraphEditor` 在调用渲染时传入。参考现有 `renderNodes` 调用签名（`GraphEditor.java` L1108）。

#### 3.4.4 当前 X 标记（可选增强）

`debugTime` 在服务端，客户端无法直接读取当前 t。两种方案：

- **方案 1**（推荐）：在 `EvalSnapshot` 中额外携带 `Map<Integer, Float> debugTime` 快照。需扩展 `EvalSnapshot` record 与 `ClientboundGraphEvalPacket`。
- **方案 2**（简化）：不画当前 X 标记，仅显示波形曲线。

第一版采用方案 2。

### 3.5 交互

**文件**：`src/main/java/io/github/y15173334444/create_schematic_compute/blocks/GraphEditor.java`

#### 3.5.1 控制点拖拽状态

在 `GraphEditor` 字段区（L278-285 附近）新增：

```java
// DEBUG_SIGNAL_GEN 控点拖拽
private int draggingCtrlNode = -1;
private int draggingCtrlIdx = -1;
```

#### 3.5.2 命中检测

新增辅助方法：

```java
/** 检测鼠标是否命中 DEBUG_SIGNAL_GEN 的控制点。返回 [nodeId, ctrlIdx] 或 null。 */
private int[] hitControlPoint(double mx, double my) {
    for (GraphNode n : getGraph().nodes) {
        if (n.type != NodeType.DEBUG_SIGNAL_GEN) continue;
        if (!(mode == DebugSignals.MODE_CUSTOM || expandedNodeIds.contains(n.id))) continue;
        // 仅在 CUSTOM 模式或展开时显示控制点
        int mode = n.params.length > 0 ? (int) n.params[0] : 2;
        if (mode != DebugSignals.MODE_CUSTOM && !expandedNodeIds.contains(n.id)) continue;
        float sx = c2sX(n.x), sy = c2sY(n.y);
        int w = NodeRenderer.WIDE_NW;
        float bodyH = NodeRenderer.HH + NodeRenderer.PH * (n.functionalInputs() + n.outputs());
        int chartX = (int) sx;
        int chartY = (int) (sy + bodyH);
        int chartW = w;
        int chartH = 80;
        int midY = chartY + chartH / 2;
        if (n.debugCtrlX == null) continue;
        for (int i = 0; i < n.debugCtrlX.length; i++) {
            int cpx = chartX + (int) (n.debugCtrlX[i] * chartW);
            int cpy = midY - (int) (n.debugCtrlY[i] * (chartH / 2f - 2));
            if (Math.abs(mx - cpx) <= 4 && Math.abs(my - cpy) <= 4) {
                return new int[]{n.id, i};
            }
        }
    }
    return null;
}
```

#### 3.5.3 mouseClicked 集成

在 `mouseClicked`（L1395）中，节点主体点击检测（L2059）**之前**插入控制点检测：

```java
// [新增] DEBUG_SIGNAL_GEN 控制点命中
if (btn == 0) {
    int[] hit = hitControlPoint(mx, my);
    if (hit != null) {
        draggingCtrlNode = hit[0];
        draggingCtrlIdx = hit[1];
        return true;
    }
}
// 双击空白处添加控制点
if (btn == 0 && doubleClick) { // 需检测双击（见下）
    GraphNode hover = hitNode(mx, my);
    if (hover != null && hover.type == NodeType.DEBUG_SIGNAL_GEN) {
        addControlPoint(hover, mx, my);
        return true;
    }
}
// 右键控制点删除
if (btn == 1) {
    int[] hit = hitControlPoint(mx, my);
    if (hit != null) {
        removeControlPoint(getGraph().findNode(hit[0]), hit[1]);
        return true;
    }
}
```

> **双击检测**：Minecraft 的 `mouseClicked` 每次按下都触发。需自行记录上次点击时间，间隔 < 300ms 视为双击。在 `GraphEditor` 新增 `private long lastClickMs = 0;` 字段。

#### 3.5.4 控制点增删改

```java
private void addControlPoint(GraphNode n, double mx, double my) {
    // 计算鼠标对应的 (t, v)
    float sx = c2sX(n.x), sy = c2sY(n.y);
    int w = NodeRenderer.WIDE_NW;
    float bodyH = NodeRenderer.HH + NodeRenderer.PH * (n.functionalInputs() + n.outputs());
    int chartX = (int) sx;
    int chartY = (int) (sy + bodyH);
    int chartW = w;
    int chartH = 80;
    int midY = chartY + chartH / 2;
    float t = (float) ((mx - chartX) / chartW);
    float v = (float) ((midY - my) / (chartH / 2f - 2));
    t = Math.max(0f, Math.min(1f, t));
    v = Math.max(-1f, Math.min(1f, v));
    // 按 t 升序插入
    int idx = 0;
    while (idx < n.debugCtrlX.length && n.debugCtrlX[idx] < t) idx++;
    n.debugCtrlX = insert(n.debugCtrlX, idx, t);
    n.debugCtrlY = insert(n.debugCtrlY, idx, v);
}

private void removeControlPoint(GraphNode n, int idx) {
    if (n == null || n.debugCtrlX == null || n.debugCtrlX.length <= 2) return; // 保留至少 2 点
    n.debugCtrlX = remove(n.debugCtrlX, idx);
    n.debugCtrlY = remove(n.debugCtrlY, idx);
}

private static float[] insert(float[] arr, int idx, float val) {
    float[] r = new float[arr.length + 1];
    System.arraycopy(arr, 0, r, 0, idx);
    r[idx] = val;
    System.arraycopy(arr, idx, r, idx + 1, arr.length - idx);
    return r;
}
private static float[] remove(float[] arr, int idx) {
    float[] r = new float[arr.length - 1];
    System.arraycopy(arr, 0, r, 0, idx);
    System.arraycopy(arr, idx + 1, r, idx, arr.length - idx - 1);
    return r;
}
```

#### 3.5.5 mouseDragged / mouseReleased

在 `mouseDragged`（L2518）中处理控制点拖拽（在节点拖拽逻辑之前）：

```java
if (draggingCtrlNode >= 0 && draggingCtrlIdx >= 0) {
    GraphNode n = getGraph().findNode(draggingCtrlNode);
    if (n != null && n.debugCtrlY != null && draggingCtrlIdx < n.debugCtrlY.length) {
        float sx = c2sX(n.x), sy = c2sY(n.y);
        int w = NodeRenderer.WIDE_NW;
        float bodyH = NodeRenderer.HH + NodeRenderer.PH * (n.functionalInputs() + n.outputs());
        int chartH = 80;
        int midY = (int) (sy + bodyH) + chartH / 2;
        float v = (float) ((midY - my) / (chartH / 2f - 2));
        n.debugCtrlY[draggingCtrlIdx] = Math.max(-1f, Math.min(1f, v));
    }
    return true;
}
```

在 `mouseReleased`（L2217）中清除拖拽状态：

```java
draggingCtrlNode = -1;
draggingCtrlIdx = -1;
```

> **控制点不同步**：控制点坐标为 transient，session-only，不通过 GraphOp 同步。各客户端独立维护自己的控制点。参数（mode/amplitude 等）的修改走现有 `SET_PARAM` op，正常同步。

### 3.6 参数编辑

mode/amplitude/frequency/phase/dutyCycle/speed 通过现有展开节点 EditBox 编辑（复用 `createEditState`，L626 模式）。无需新增 UI 组件。mode 字段是 float，用户输入 0-7 整数。

自定义公式（mode=7）：复用 `formula` 字段。在 EditState 中为 `DEBUG_SIGNAL_GEN` 添加公式 EditBox（参考 `FORMULA` 节点的公式编辑处理）。修改 `formula` 走 `SET_FORMULA` op。

---

## 4. 模拟显示节点 DEBUG_PROBE

### 4.1 功能规格

实时显示连接到该节点的信号值，并绘制迷你趋势图（最近 N tick 历史）。数值带颜色编码，趋势图自动缩放或固定范围。

### 4.2 服务端求值

**文件**：`src/main/java/io/github/y15173334444/create_schematic_compute/graph/GraphEvaluator.java`

在 `eval()` switch 中新增：

```java
case DEBUG_PROBE -> {
    // pass-through：输出 = 输入，供客户端从 EvalSnapshot 读取
    o[0] = graph.getInputValue(node.id, 0, outputs);
}
```

一行即可。探针不影响图逻辑。

### 4.3 客户端采样

**文件**：`src/main/java/io/github/y15173334444/create_schematic_compute/blocks/GraphEditor.java`

新增 `clientTick()` 方法：

```java
/** 客户端每 tick 调用（由各 Host Screen 的 containerTick 触发）。
 *  - 推进 DEBUG_PROBE 历史采样
 *  - 推进书签视角过渡动画
 *  Client tick (called by each Host Screen's containerTick). */
public void clientTick() {
    advanceCameraTransition(); // 见第 5 章
    var snap = host.getCachedEvalSnapshot();
    if (snap == null || snap == io.github.y15173334444.create_schematic_compute.graph.EvalSnapshot.EMPTY) return;
    var graph = getGraph();
    for (GraphNode n : graph.nodes) {
        if (n.type != NodeType.DEBUG_PROBE) continue;
        if (n.probeFrozen) continue;
        float v = snap.get(n.id, 0);
        n.probeHistory[n.probeHead] = v;
        n.probeHead = (n.probeHead + 1) % n.probeHistory.length;
        if (n.probeCount < n.probeHistory.length) n.probeCount++;
    }
}
```

> **未运行时**：蓝图未运行时 `cachedEvalSnapshot` 为 `EMPTY`，探针不采样、显示"未运行"提示（见 4.4）。

### 4.4 渲染

**文件**：`src/main/java/io/github/y15173334444/create_schematic_compute/blocks/NodeRenderer.java`

新增 `renderDebugProbe` 方法，在 `renderNodes` 分派中调用：

```java
/** 绘制 DEBUG_PROBE 节点：当前数值 + 迷你趋势图。 */
private void renderDebugProbe(GuiGraphics g, GraphNode n,
        float camX, float camY, float zoom, int screenX, int screenY,
        io.github.y15173334444.create_schematic_compute.graph.EvalSnapshot snap) {
    int w = nw(n);
    float bodyH = HH + PH * (n.functionalInputs() + n.outputs());
    int valY = screenY + (int) bodyH;
    int chartY = valY + 20;
    int chartH = 44;

    // 读取当前值
    float curVal = (snap != null) ? snap.get(n.id, 0) : 0f;
    boolean running = snap != null && snap != io.github.y15173334444.create_schematic_compute.graph.EvalSnapshot.EMPTY;

    // 数值显示
    String valStr = running ? String.format("%.3f", curVal) : "---";
    int valCol = !running ? 0xFF888888
        : (Float.isNaN(curVal) || Float.isInfinite(curVal) ? 0xFF888888
        : (Math.abs(curVal) > 10f ? 0xFFFF4444 : 0xFF4ADE80));
    g.drawCenteredString(font, valStr, screenX + w / 2, valY + 2, valCol);

    // 趋势图背景
    g.fill(screenX, chartY, screenX + w, chartY + chartH, 0xFF1A1A2E);
    // 中线
    int midY = chartY + chartH / 2;
    g.fill(screenX, midY, screenX + w, midY + 1, 0xFF2A2A4E);

    if (!running) {
        g.drawCenteredString(font, "未运行", screenX + w / 2, chartY + chartH / 2 - 4, 0xFF666666);
        return;
    }

    // 读取参数
    int windowSize = n.params.length > 0 ? (int) n.params[0] : 50;
    windowSize = Math.max(2, Math.min(n.probeHistory.length, windowSize));
    boolean autoScale = n.params.length > 1 ? n.params[1] != 0 : true;
    float fixedRange = 10f;

    // 计算窗口内数据范围
    float minV = Float.MAX_VALUE, maxV = -Float.MAX_VALUE;
    int start = (n.probeHead - windowSize + n.probeHistory.length) % n.probeHistory.length;
    int count = Math.min(n.probeCount, windowSize);
    if (count < 2) {
        g.drawCenteredString(font, "采样中...", screenX + w / 2, chartY + chartH / 2 - 4, 0xFF666666);
        return;
    }
    for (int i = 0; i < count; i++) {
        int idx = (start + i) % n.probeHistory.length;
        float v = n.probeHistory[idx];
        if (v < minV) minV = v;
        if (v > maxV) maxV = v;
    }
    float range = autoScale ? Math.max(maxV - minV, 0.001f) : (2 * fixedRange);
    float base = autoScale ? minV : -fixedRange;

    // 绘制折线
    int prevPX = -1, prevPY = -1;
    int lineCol = 0xFF4ADE80;
    for (int i = 0; i < count; i++) {
        int idx = (start + i) % n.probeHistory.length;
        float v = n.probeHistory[idx];
        int px = screenX + (int) ((float) i / (count - 1) * w);
        int py = chartY + chartH - (int) ((v - base) / range * chartH);
        py = Math.max(chartY, Math.min(chartY + chartH - 1, py));
        if (prevPX >= 0) drawLine(g, prevPX, prevPY, px, py, lineCol);
        prevPX = px; prevPY = py;
    }

    // 冻结指示
    if (n.probeFrozen) {
        g.drawString(font, "FROZEN", screenX + w - 40, valY + 2, 0xFFFFAA00);
    }
}
```

> `drawLine` 方法见 3.4.3。`snap` 参数由 `GraphEditor` 从 `host.getCachedEvalSnapshot()` 获取后传入。

### 4.5 交互

- **参数编辑**：`windowSize`（输入 25/50/100）、`autoScale`（0/1）通过展开节点 EditBox 编辑，走 `SET_PARAM` op。
- **右键菜单**：在节点右键菜单（`showMenu` 逻辑）中为 `DEBUG_PROBE` 新增"清除历史""冻结/解冻"选项：
  - 清除历史：`n.probeHead = 0; n.probeCount = 0;`（transient，不同步）
  - 冻结/解冻：`n.probeFrozen = !n.probeFrozen;`（transient，不同步）

这两个操作仅修改 transient 字段，各客户端独立，不入撤销栈，不发 op。

### 4.6 输出引脚渲染

`DEBUG_PROBE` 有 1 个输出引脚（pass-through）。在 `NodeRenderer.renderNodes` 中正常绘制该引脚，但标签用 `probe_out`（"镜像"）以表明用途。如希望完全隐藏输出引脚，可在引脚绘制循环中对 `DEBUG_PROBE` 跳过输出引脚绘制 —— 但这样会导致无法从探针引出连线。建议保留可见。

---

## 5. 视角书签系统

### 5.1 功能规格

保存当前编辑器视角（camX/camY/zoom）为命名书签，支持快捷跳转、重置视角、临时视角自动保存/恢复。书签存储在客户端本地，与方块无关，全局有效。

### 5.2 数据模型

**新文件**：`src/main/java/io/github/y15173334444/create_schematic_compute/client/ClientBookmarkStore.java`

```java
package io.github.y15173334444.create_schematic_compute.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.world.phys.Vec3;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ClientBookmarkStore {
    private static final Path CONFIG_DIR = Path.of("config", "create_schematic_compute");
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("bookmarks.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX_BOOKMARKS = 50;

    public record Bookmark(String name, float camX, float camY, float zoom) {}

    private final List<Bookmark> bookmarks = new ArrayList<>();
    private Bookmark temporaryView = null;

    public void load() {
        try {
            if (!Files.exists(CONFIG_FILE)) return;
            String json = Files.readString(CONFIG_FILE);
            var obj = GSON.fromJson(json, StoreData.class);
            if (obj != null) {
                bookmarks.clear();
                if (obj.bookmarks != null) bookmarks.addAll(obj.bookmarks);
                temporaryView = obj.temporaryView;
            }
        } catch (Exception e) {
            // 静默失败，使用默认空列表
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_DIR);
            var data = new StoreData();
            data.bookmarks = bookmarks;
            data.temporaryView = temporaryView;
            Files.writeString(CONFIG_FILE, GSON.toJson(data));
        } catch (Exception e) {
            // 静默失败
        }
    }

    public boolean add(Bookmark b) {
        if (bookmarks.size() >= MAX_BOOKMARKS) return false;
        bookmarks.add(b);
        save();
        return true;
    }

    public void remove(int idx) {
        if (idx >= 0 && idx < bookmarks.size()) {
            bookmarks.remove(idx);
            save();
        }
    }

    public Bookmark get(int idx) {
        return (idx >= 0 && idx < bookmarks.size()) ? bookmarks.get(idx) : null;
    }

    public List<Bookmark> all() { return Collections.unmodifiableList(bookmarks); }
    public int size() { return bookmarks.size(); }

    public Bookmark getTemporaryView() { return temporaryView; }
    public void setTemporaryView(Bookmark v) { temporaryView = v; save(); }

    private static class StoreData {
        List<Bookmark> bookmarks;
        Bookmark temporaryView;
    }
}
```

`config/create_schematic_compute/bookmarks.json` 格式：

```json
{
  "bookmarks": [
    {"name": "书签 1", "camX": 120.0, "camY": -80.0, "zoom": 1.5}
  ],
  "temporaryView": {"name": "", "camX": 0.0, "camY": 0.0, "zoom": 1.0}
}
```

### 5.3 GraphEditor 集成

**文件**：`src/main/java/io/github/y15173334444/create_schematic_compute/blocks/GraphEditor.java`

#### 5.3.1 字段

在 `camX/camY/zoom`（L273）附近新增：

```java
private final io.github.y15173334444.create_schematic_compute.client.ClientBookmarkStore bookmarks =
    new io.github.y15173334444.create_schematic_compute.client.ClientBookmarkStore();
private boolean showBookmarkPanel = false;
private String bookmarkNameDraft = "";
private boolean editingBookmarkName = false;

// 视角过渡动画
private float transFromX, transFromY, transFromZoom;
private float transToX, transToY, transToZoom;
private long transStartMs = 0;
private static final long TRANSITION_MS = 200;
```

构造函数（L878）末尾追加：

```java
bookmarks.load();
// 恢复临时视角
var tv = bookmarks.getTemporaryView();
if (tv != null) { camX = tv.camX(); camY = tv.camY(); zoom = tv.zoom(); }
```

#### 5.3.2 视角过渡

新增方法：

```java
private void startTransition(float toX, float toY, float toZoom) {
    transFromX = camX; transFromY = camY; transFromZoom = zoom;
    transToX = toX; transToY = toY; transToZoom = toZoom;
    transStartMs = System.currentTimeMillis();
}

/** 每帧推进过渡动画。在 renderBg 开头调用（帧率平滑）。 */
private void advanceCameraTransition() {
    if (transStartMs == 0) return;
    long elapsed = System.currentTimeMillis() - transStartMs;
    float t = Math.min(1f, elapsed / (float) TRANSITION_MS);
    // ease-in-out
    float e = t < 0.5f ? 2 * t * t : 1 - (float) Math.pow(-2 * t + 2, 2) / 2;
    camX = lerp(transFromX, transToX, e);
    camY = lerp(transFromY, transToY, e);
    zoom = lerp(transFromZoom, transToZoom, e);
    if (t >= 1f) transStartMs = 0;
}

private static float lerp(float a, float b, float t) { return a + (b - a) * t; }
```

> **调用点**：在 `renderBg`（L975）最开头调用 `advanceCameraTransition()`。注意 `clientTick` 中也调用了 `advanceCameraTransition()`（见 4.3），但 `renderBg` 每帧调用更平滑。为避免重复，仅在 `renderBg` 调用，从 `clientTick` 中移除该调用。

#### 5.3.3 快捷键

在 `keyPressed`（L2570）中，在 `Ctrl+Z/Y`（L2659）之后、`Ctrl+D`（L2664）之前插入：

```java
// [新增] 视角书签快捷键
if (net.minecraft.client.gui.screens.Screen.hasControlDown()) {
    // Ctrl+B: 保存书签
    if (key == 66) { // GLFW_KEY_B
        editingBookmarkName = true;
        bookmarkNameDraft = "书签 " + (bookmarks.size() + 1);
        return true;
    }
    // Ctrl+1~9: 跳转书签
    if (key >= 49 && key <= 57) { // GLFW_KEY_1 ~ GLFW_KEY_9
        int idx = key - 49;
        var bm = bookmarks.get(idx);
        if (bm != null) startTransition(bm.camX(), bm.camY(), bm.zoom());
        return true;
    }
    // Ctrl+Shift+S: 保存临时视角；Ctrl+Shift+L: 恢复临时视角
    if (net.minecraft.client.gui.screens.Screen.hasShiftDown()) {
        if (key == 83) { // S
            bookmarks.setTemporaryView(new ClientBookmarkStore.Bookmark("", camX, camY, zoom));
            return true;
        }
        if (key == 76) { // L
            var tv = bookmarks.getTemporaryView();
            if (tv != null) startTransition(tv.camX(), tv.camY(), tv.zoom());
            return true;
        }
    }
}
// Home: 重置视角
if (key == 268) { // GLFW_KEY_HOME
    startTransition(0, 0, 1f);
    return true;
}
```

#### 5.3.4 书签命名对话框

`editingBookmarkName` 为 true 时，在 `renderBg` 的 overlay 层（A=4，L1111 附近）绘制命名对话框（复用 `EditBox` + 提交逻辑，参考 `showExportDialog` 的处理 L2573-2582）：

- Enter 提交：`bookmarks.add(new Bookmark(name, camX, camY, zoom)); editingBookmarkName = false;`
- Esc 取消：`editingBookmarkName = false;`

#### 5.3.5 书签列表面板

`showBookmarkPanel` 为 true 时，在 `renderBg` overlay 层绘制侧边面板：

- 工具栏新增 📐 按钮（在 `renderButtons` 中），点击切换 `showBookmarkPanel`
- 面板内容：书签列表（名称 + 跳转/删除按钮）、"新建"按钮、"重置视角 (Home)"按钮
- 面板点击处理在 `mouseClicked` 中（L1498 工具栏按钮区域附近）

#### 5.3.6 临时视角自动保存

在 `GraphEditor` 新增 `onClose()` 钩子（或由各 Screen 的 `removed()`/`onClose()` 调用）：

```java
/** 编辑器关闭时调用，保存临时视角。 */
public void onClose() {
    bookmarks.setTemporaryView(new ClientBookmarkStore.Bookmark("", camX, camY, zoom));
}
```

各 Host Screen 的 `onClose()`/`removed()` 中调用 `editor.onClose()`。例如 `BlueprintScreen.onClose()`：

```java
@Override public void onClose() {
    editor.onClose(); // [新增]
    super.onClose();
}
```

> 注意：便携终端包装 Screen 的 `onClose()`（PortableTerminalScreen L335+）返回终端而非关闭，也需调用 `editor.onClose()` 保存临时视角。

### 5.4 与其他系统的关系

| 系统 | 关系 |
|---|---|
| 撤回/重做 | 无关，视角操作不影响节点图 |
| 蓝图保存 | 无关，书签是客户端本地数据 |
| 封装/导出 | 无关 |
| 便携终端 | 兼容，书签全局有效 |
| 多人协作 | 兼容，每玩家独立保存自己的书签 |

---

## 6. 改动文件清单

| 文件 | 改动类型 | 内容 |
|---|---|---|
| `graph/NodeType.java` | 修改 | 新增 `DEBUG_SIGNAL_GEN`、`DEBUG_PROBE` 枚举值 + `isDebug()` 方法 |
| `graph/GraphNode.java` | 修改 | 新增 transient 字段 + 构造默认值 |
| `graph/DebugSignals.java` | **新增** | 信号计算静态方法 |
| `graph/GraphEvaluator.java` | 修改 | eval switch 新增 2 个 case + `debugTime` Map |
| `blocks/NodeRenderer.java` | 修改 | `nw`/`nh` 尺寸 + 菜单分类 + 2 个渲染方法 + `drawLine` |
| `blocks/GraphEditor.java` | 修改 | `clientTick` + 书签 + 控制点交互 + 导出过滤 + `Host.getCachedEvalSnapshot` |
| `blocks/BlueprintScreen.java` | 修改 | `containerTick` + `getCachedEvalSnapshot` + `onClose` |
| `blocks/MonitorScreen.java` | 修改 | 同上 |
| `blocks/ProgramComputerScreen.java` | 修改 | 同上 |
| `blocks/ControlSeatScreen.java` | 修改 | 同上 |
| `blocks/SensorScreen.java` | 修改 | 同上 |
| `blocks/SpeedProxyScreen.java` | 修改 | 同上 |
| `blocks/RadarScreen.java` | 修改 | 同上 |
| `client/PortableTerminalScreen.java` | 修改 | 包装 Screen `containerTick` 委托 + `onClose` 调用 |
| `client/ClientBookmarkStore.java` | **新增** | 书签读写 |
| `assets/.../lang/zh_cn.json` | 修改 | i18n 键 |
| `assets/.../lang/en_us.json` | 修改 | i18n 键 |

---

## 7. 实施顺序

按依赖关系排序，每步可独立验证。

### 第 1 步：共享基础设施
- `NodeType` 新增 2 枚举值 + `isDebug()`
- `GraphNode` 新增 transient 字段 + 构造默认值
- `NodeRenderer` 菜单新增 "debug" 分类 + `nw`/`nh` 尺寸
- `Host` 接口新增 `getCachedEvalSnapshot()`
- i18n 键
- **验证**：菜单出现 "调试" 分类，能放置两种节点，连线正常

### 第 2 步：DEBUG_SIGNAL_GEN 求值
- 新建 `DebugSignals.java`
- `GraphEvaluator.eval` 新增 case + `debugTime` Map
- **验证**：运行蓝图，SINE 模式输出正弦波（用 DATA 节点或红石输出观察）

### 第 3 步：DEBUG_SIGNAL_GEN 渲染
- `NodeRenderer.renderDebugSignalGen` + `drawLine`
- **验证**：节点显示 XY 网格 + 波形曲线，切换 mode 曲线变化

### 第 4 步：DEBUG_SIGNAL_GEN 交互
- `GraphEditor` 控制点命中/拖拽/增删
- 参数 EditBox（mode/amplitude/frequency/phase/dutyCycle/speed）
- 自定义公式（mode=7）复用 formula 字段编辑
- **验证**：拖控制点、切模式、调参数、写公式均生效

### 第 5 步：DEBUG_PROBE 求值与采样
- `GraphEvaluator.eval` 新增 pass-through case
- `GraphEditor.clientTick()` 采样
- 各 Screen `containerTick` 调用 `editor.clientTick()` + `getCachedEvalSnapshot`
- 便携终端委托修复
- **验证**：连接信号源到探针，运行蓝图，历史缓冲正确滚动

### 第 6 步：DEBUG_PROBE 渲染与交互
- `NodeRenderer.renderDebugProbe`
- 右键菜单"清除历史""冻结/解冻"
- **验证**：趋势图实时滚动，冻结/清除可用，未运行时显示"未运行"

### 第 7 步：导出/封装过滤
- `GraphEditor.exportEncapNode` + 子图过滤增加 `isDebug()` 检查
- **验证**：导出时调试节点被跳过

### 第 8 步：视角书签
- 新建 `ClientBookmarkStore.java`
- `GraphEditor` 书签字段 + load/save + 快捷键 + 命名对话框 + 列表面板 + 过渡动画 + `onClose` 临时视角
- 各 Screen `onClose` 调用 `editor.onClose()`
- **验证**：书签增删跳转、过渡动画、临时视角、跨会话持久化

---

## 8. 验收清单

### 8.1 模拟输入节点

- [ ] 菜单 "调试" 分类下出现 "信号发生器"
- [ ] 放置节点，显示 XY 坐标图 + 波形曲线
- [ ] mode 参数 0-7 切换，曲线正确变化（CONST/STEP/SINE/SQUARE/TRIANGLE/NOISE/PULSE/CUSTOM）
- [ ] amplitude/frequency/phase/dutyCycle/speed 参数调整生效
- [ ] CUSTOM 模式输入公式（如 `sin(t*360)`），曲线与输出正确
- [ ] 控制点可拖拽（CUSTOM 模式或展开时）
- [ ] 双击空白添加控制点，右键控制点删除（保留至少 2 点）
- [ ] 运行蓝图，输出引脚输出真实信号值（用探针或 DATA 节点验证）
- [ ] 参数修改通过 SET_PARAM op 多人同步
- [ ] 导出/封装时节点被跳过

### 8.2 模拟显示节点

- [ ] 菜单出现 "信号探针"
- [ ] 放置节点，连接信号源，运行蓝图
- [ ] 数值显示实时更新（3 位小数，颜色编码）
- [ ] 趋势图实时滚动（最近 50 tick）
- [ ] windowSize 参数 25/50/100 切换生效
- [ ] autoScale 参数切换（自动缩放/固定 ±10）
- [ ] 右键"清除历史"清空趋势图
- [ ] 右键"冻结/解冻"暂停/恢复更新
- [ ] 未运行时显示"未运行"
- [ ] 便携终端打开的编辑器中探针正常采样
- [ ] 多人协作各客户端独立采样

### 8.3 视角书签

- [ ] Ctrl+B 弹出命名对话框，Enter 保存书签
- [ ] 书签列表面板显示所有书签
- [ ] Ctrl+1~9 跳转对应书签，200ms 平滑过渡
- [ ] Home 键重置视角到 (0,0,1)
- [ ] 工具栏 📐 按钮切换书签面板
- [ ] 面板内跳转/删除按钮可用
- [ ] Ctrl+Shift+S 保存临时视角，Ctrl+Shift+L 恢复
- [ ] 关闭编辑器后重开，临时视角自动恢复
- [ ] `config/create_schematic_compute/bookmarks.json` 正确读写
- [ ] 书签跨所有 GraphEditor 实例全局有效
- [ ] 多人协作各玩家独立保存自己的书签

### 8.4 兼容性

- [ ] 撤销/重做：参数调整入栈，控制点/历史/冻结 transient 不入栈
- [ ] 蓝图保存：节点 params/formula 序列化，transient 字段不序列化
- [ ] 便携终端：调试节点与书签均可用
- [ ] 多人协作：参数 op 同步，transient 各客户端独立
