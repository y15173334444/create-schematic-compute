# FORMULA Node — 引脚变更后连线不显示（技术债）

> 发现日期: 2026-07-27 | 严重度: 高
>
> **计划调整**: 此问题原计划在 v1.2.5 中处理，因其影响较大（引脚变更后连线完全不显示，每次按键均可能触发），**提前至 v1.2.4 从根源清除**。根因与 v1.2.5 Bug B（ENCAP I/O 重排后连线不跟随）同源——连线用整数索引绑定，引脚集合变化时索引失效。本次修复消除 `inputLabel`/`outputLabel` 副作用 + 统一连线清理；stable pinId 重构（根治 ENCAP + FORMULA 两处）仍作为后续长线方案。
>
> | 阶段 | 内容 | 版本 |
> |------|------|------|
> | **提前清除**（原计划 v1.2.5） | 消除 `inputLabel()`/`outputLabel()` 副作用 + 统一连线清理逻辑 | v1.2.4 |
> | 长线 | stable pinId 重构（根治，同时解决 FORMULA 和 ENCAP I/O） | 后续 |

## 问题现象

当用户在 FORMULA 节点中修改公式（增/删变量）时，新出现的输入引脚**有引脚圆点但不显示连线**。连线仍然连在旧的 Y 位置上，或根本不渲染。

## 根因分析

### 主因：`inputLabel()` / `outputLabel()` 的懒解析副作用

**文件**: `GraphNode.java:127, 152`

```java
// inputLabel() — 在渲染时被调用，偷偷修改 dynamicInputCount
public String inputLabel(int i) {
    ...
    if (type == NodeType.FORMULA && !formula.isEmpty()) {
        if (cachedScript == null) cachedScript = FormulaParser.parseScript(formula);
        dynamicInputCount = Math.max(1, cachedScript.inputVars.size()); // ← 副作用!
        if (i < cachedScript.inputVars.size()) return cachedScript.inputVars.get(i);
    }
    ...
}
```

`outputLabel()` 有相同问题。

**为什么导致 Bug**：图形渲染分两个阶段，按顺序执行：

| 阶段 | 调用 | 使用的 pin 数量 | 结果 |
|------|------|----------------|------|
| **A=2** `renderConnections()` | 画连线 | `functionalInputs()` → 读 `dynamicInputCount` → **旧值** | 连线画在错误的 Y 位置 |
| **A=3** `renderNodes()` → `drawNode()` | 画引脚和标签 | `inputLabel(i)` → **偷偷更新** `dynamicInputCount` → **新值** | 引脚画在正确位置 |

**NBT 重载场景**：`cachedScript` 是 `transient` 字段，NBT 加载后为 null。此时 `dynamicInputCount` 来自 NBT 持久化值。如果公式被远程玩家修改后同步过来，NBT 中的 `dynamicInputCount` 可能是旧值。A=2 画线时读到旧值，连线位置错误。A=3 时 `inputLabel()` 触发懒解析修好值，但线已经画完了。

### 次因：连线清理不对称

**本地 Responder** (`GraphEditor.java:1125-1137`) 用原始 `dynamicInputCount` 逐 pin 遍历删除：

```java
// 本地 Responder: 用 oldIn/oldOut (可能为 0)
if (newIn < cur.dynamicInputCount) {
    for (int pi = newIn; pi < oldIn; pi++)
        host.getGraph().connections.removeIf(c -> c.toId == cur.id && c.toPin == pi);
}
```

**服务端 OpExecutor** (`OpExecutor.java:147-149`) 用 `inputs()`/`outputs()`（clamp 到 [1,26]/[1,16]）：

```java
// 服务端: 用 inputs() (clamp 过的值)
graph.connections.removeIf(c ->
    (c.toId == n.id && c.toPin >= n.inputs()) || ...);
```

**不一致场景**：如果 `dynamicInputCount` 到达 0（空公式），Responder 的 `oldIn=0` → 循环不执行 → 不删。OpExecutor 的 `inputs()=1` → 删掉 pin 0 的线。本地和服务端不同步。

### 附加风险

| # | 问题 | 严重度 | 位置 |
|---|------|--------|------|
| C | **每次按键都发网络包** — 快速输入时中间态可能误删连线 | 中 | `GraphEditor.java:1147` |
| D | **NBT 重载后 `cachedScript=null`** — 配合过期的 `dynamicInputCount` 导致首帧渲染错误 | 中 | `GraphNode.java:35` |
| E | **无按键防抖** — 高频 SET_FORMULA 包增加服务端负载和连线清理次数 | 低 | `GraphEditor.java:1147` |

## 修复方案

> **注意**: 以下方案**原计划在 v1.2.5 处理，因影响大提前至 v1.2.4 从根源清除**。根治方案（stable pinId）仍与 v1.2.5 ENCAP I/O 合并处理，详见 [`v1.2.5-plan-resolved.md` §3.3 方案 3](./v1.2.5-plan-resolved.md)。

### 1. 消除 `inputLabel()`/`outputLabel()` 的副作用（主因 · 先清除）

将懒解析 + 计数更新抽到独立方法（如 `ensureScriptParsed()`），在 `drawNode()` 最前面（A=2 连线渲染之前）主动调用一次：

```java
// GraphNode.java
public void ensureScriptParsed() {
    if (type == NodeType.FORMULA && !formula.isEmpty() && cachedScript == null) {
        cachedScript = FormulaParser.parseScript(formula);
        dynamicInputCount = Math.max(1, cachedScript.inputVars.size());
        dynamicOutputCount = Math.max(1, cachedScript.outputLabels.size());
        outputLabels = cachedScript.outputLabels;
    }
}

// inputLabel() 改为纯读取，删掉副作用
public String inputLabel(int i) {
    if (type == NodeType.FORMULA && !formula.isEmpty()) {
        if (i < cachedScript.inputVars.size()) return cachedScript.inputVars.get(i);
    }
    ...
}
```

**NodeRenderer.drawNode()** 中在渲染循环前调用：

```java
// 在所有渲染之前，确保 FORMULA 的脚本已解析、计数已更新
n.ensureScriptParsed();
// ...然后画标题、编辑区、引脚、连线...
```

### 2. 统一连线清理逻辑（次因）

让本地 Responder 调用 `OpExecutor.apply()` 或复用其清理逻辑：

```java
// Responder 中，替代手写的逐 pin 清理
// 方案 A: 委托给 OpExecutor（它处理所有情况）
OpExecutor.apply(host.getGraph(), 
    GraphOp.setFormula(pos, ownerNodeId, formulaNodeId, sanitized, actor));

// 方案 B: 复用相同的 clamp 逻辑
int clampedIn = Math.max(1, Math.min(newIn, 26));
graph.connections.removeIf(c ->
    (c.toId == cur.id && c.toPin >= clampedIn) ||
    (c.fromId == cur.id && c.fromPin >= Math.max(1, Math.min(newOut, 16))));
```

### 3. 可选：按键防抖

在 `setResponder` 中加 200-300ms 的防抖，减少中间态误删：

```java
// 伪代码
private int debounceTimer = -1;
mle.setResponder(t -> {
    if (debounceTimer >= 0) cancelTimer(debounceTimer);
    debounceTimer = scheduleTimer(250, () -> {
        // 实际的 sendOp 逻辑
    });
});
```

> **注意**：加防抖可能影响实时校验的响应速度。需权衡。

## 影响范围

- **触发条件**: FORMULA 节点编辑公式后、远程同步后、NBT 重载后
- **影响功能**: 连线渲染位置错误 → 视觉上连线消失或偏移
- **不影响**: 实际求值逻辑（连线数据未丢，只是渲染位置错）

## 与 v1.2.5 ENCAP I/O 引脚重排的关联

本问题与 [`v1.2.5-plan-resolved.md` §3](./v1.2.5-plan-resolved.md) 中的 **Bug B（封装 I/O 重排后外部连线不跟随）** 属于同一类根因：

> 连线存储使用整数 `fromPin`/`toPin` 索引，引脚顺序或数量变化时索引失效。

| 维度 | FORMULA 引脚 | ENCAP I/O 引脚 |
|------|------------|---------------|
| 触发条件 | 公式文本变化 → `dynamicInputCount` 变 | 移动 I/O 节点 → Y 排序变 |
| 症状 | 连线消失 / 位置错误 | 连线跟错引脚 / 数据流翻转 |
| 临时修复 | 消除 `inputLabel()` 副作用 + 统一清理 | 方案 2：MOVE 落定时重编号连线 |
| 根治方案 | **方案 3：稳定 pinId** | **同一个方案 3** |

**建议**：实施 v1.2.5 计划中的方案 3（约 200-260 行），为所有动态引脚节点（FORMULA、ENCAP_INPUT、ENCAP_OUTPUT）引入不可变的 `pinId`，一次性解决全部引脚-连线一致性问题。在此之前，FORMULA 的临时修复（消除副作用）和 ENCAP 的方案 2 可作为过渡方案分别处理。

## 相关文件

| 文件 | 相关内容 |
|------|---------|
| `GraphNode.java:122-167` | `inputLabel()` / `outputLabel()` 副作用 |
| `GraphEditor.java:1090-1148` | `createFormulaEditState()` 中的 Responder |
| `NodeRenderer.java:762-784` | A=3 引脚渲染（触发副作用） |
| `NodeRenderer.java:238-272` | A=2 连线渲染（读到旧值） |
| `OpExecutor.java:136-158` | 服务端 SET_FORMULA 处理 + 连线清理 |
| `EditSessionRegistry.java:183-203` | 服务端广播（跳过发送者） |
