# 撤销栈统一重构计划

> 日期：2026-07-25
> 当前状态：两套撤销系统并存（静态全图快照 + per-instance op），有技术债

---

## 现状分析

### 旧系统（静态全图快照）

```
private static final List<CompoundTag> undoStack = new ArrayList<>();  // 跨实例共享！
private static final List<CompoundTag> redoStack = new ArrayList<>();
```

- `takeSnapshot(graph, registries)` → NBT 序列化整张图 → push undoStack（12 处调用）
- `performUndo(host, registries)` → 整图替换 → 原子恢复所有节点
- `performRedo(host, registries)` → 整图替换

**缺点**：
- **静态共享**：跨所有 GraphEditor 实例，多人串栈（collab-plan 已知问题）
- **全量序列化**：200+ 节点图每次 NBT 序列化浪费 CPU/内存
- **粗粒度**：只能整图回退，无法选择性撤销
- **Dead code**：Ctrl+Z 从不调用它（走 `opUndo`），12 处 `takeSnapshot` 全白做

### 新系统（per-instance op）

```
private final List<UndoEntry> localUndoStack2 = new ArrayList<>();    // per-instance
private final List<UndoEntry> localRedoStack2 = new ArrayList<>();
```

- `recordOp(op, oldX, oldY, oldVal, oldStr)` → push 单条 op
- `opUndo()` → 批量处理连续 MOVE_NODE（刚加的 workaround）
- `opRedo()` → 同上
- Ctrl+Z/Y 走这套

**缺点**：
- 无原生批量支持（刚加的连续 MOVE 收集是 hack）
- 所有调用方各自实现 `recordOp`，无统一入口

---

## 目标

1. **删除旧系统**：移除 `undoStack`/`redoStack` 静态字段和 `takeSnapshot`/`performUndo`/`performRedo`/`replaceGraph`
2. **保留新系统**：`localUndoStack2` → 正名 `undoStack`；标记旧的 12 处 `takeSnapshot` 调用为 dead code 并删除
3. **批量撤销**：加 `beginUndoBatch()`/`endUndoBatch()` API，一个批次 = 一次 Ctrl+Z
4. **统一入口**：所有可撤销操作走同一套 API

---

## 实施步骤

### Step 1: 添加 UndoBatch API

```java
private final Deque<UndoEntry> undoStack = new ArrayDeque<>();
private final Deque<UndoEntry> redoStack = new ArrayDeque<>();
private int batchDepth = 0;
private final List<UndoEntry> currentBatch = new ArrayList<>();

void beginUndoBatch() { batchDepth++; }
void endUndoBatch() {
    if (batchDepth <= 0) return;
    batchDepth--;
    if (batchDepth == 0 && !currentBatch.isEmpty()) {
        undoStack.add(new UndoEntry(currentBatch)); // batch marker
        currentBatch.clear();
        if (undoStack.size() > MAX_UNDO) undoStack.removeFirst();
        redoStack.clear();
    }
}
void recordOp(GraphOp op, float oldX, float oldY, float oldVal, String oldStr) {
    var entry = new UndoEntry(op, oldX, oldY, oldVal, oldStr);
    if (batchDepth > 0) { currentBatch.add(entry); }
    else { undoStack.add(entry); if (undoStack.size() > MAX_UNDO) undoStack.removeFirst(); redoStack.clear(); }
}
```

`UndoEntry` 增加 `batch` 字段：`List<UndoEntry> batch`（null = 单条）。

### Step 2: 改造 opUndo / opRedo 支持 batch

```java
void opUndo() {
    var entry = undoStack.pollLast();
    if (entry == null) return;
    if (entry.isBatch()) {
        // 逆序撤销 batch 中所有 op
        for (int i = entry.batch.size() - 1; i >= 0; i--) {
            var e = entry.batch.get(i);
            var rev = reverseOp(e);
            if (rev != null) { OpExecutor.apply(graph, rev); host.sendOp(rev); }
        }
        redoStack.add(entry); // 整体入 redo
    } else {
        var rev = reverseOp(entry);
        if (rev != null) { OpExecutor.apply(graph, rev); host.sendOp(rev); redoStack.add(entry); }
    }
}
```

### Step 3: 注释拖动使用 batch

```java
// 拖动开始
beginUndoBatch();

// ...拖动过程...

// 释放时
for (var cn : preDragSortBs.keySet()) {
    var op = GraphOp.moveNode(...);
    host.sendOp(op);
    recordOp(op, containedOrigins.get(cn.id)[0], containedOrigins.get(cn.id)[1], 0, null);
}
// 注释本身
var moveOp = GraphOp.moveNode(...);
host.sendOp(moveOp);
recordOp(moveOp, preDragX, preDragY, 0, null);
endUndoBatch();  // 一个 batch = 一次 Ctrl+Z 全部撤销
```

### Step 4: 其他 11 处 takeSnapshot 迁移

对照 12 处 `takeSnapshot` 调用，逐处迁移为 op-based recordOp：

| # | 位置 | 操作 | 迁移方式 |
|---|------|------|---------|
| 1 | L2299 | 删除连线 | recordOp(REMOVE_CONN, ...) |
| 2 | L2644 | 热栏物品变更 | recordOp(SET_HOTBAR_ITEM, ...) |
| 3 | L2723 | 删除节点 | beginBatch → recordOp(REMOVE_NODE × N) → endBatch |
| 4 | L2802 | 注释拖动 | beginBatch → recordOp(MOVE_NODE × N) → endBatch ✅ (Step 3) |
| 5 | L2864 | 多选拖动 | beginBatch → recordOp(MOVE_NODE × N) → endBatch |
| 6 | L3071 | 注释缩放 | recordOp(SET_COMMENT_SIZE, ...) + recordOp(MOVE_NODE × N) |
| 7 | L3189 | 连线 | recordOp(ADD_CONN, ...) |
| 8 | L3823 | 封装导出 | 不需要撤销（导出无副作用） → 直接删除 |
| 9 | L3852 | Ctrl+D 复制 | beginBatch → recordOp(ADD_NODE × N) → endBatch |
| 10 | L3901 | Delete 删除节点 | beginBatch → recordOp(REMOVE_NODE × N) → endBatch |
| 11 | L3937 | 删除连线(TAB+click) | recordOp(REMOVE_CONN, ...) |
| 12 | 同上 L3937 | X 键删除节点 | recordOp(REMOVE_NODE, ...) |

### Step 5: 删除旧系统

- 移除静态字段：`undoStack`, `redoStack`
- 移除静态方法：`takeSnapshot()`, `performUndo()`, `performRedo()`, `replaceGraph()`
- 移除 `Host.performUndo()`, `Host.pushUndoSnapshot()` 接口默认方法
- 重命名：`localUndoStack2` → `undoStack`, `localRedoStack2` → `redoStack`

---

## 风险评估

| 风险 | 缓解 |
|------|------|
| 旧栈被某处非 Ctrl+Z 路径消费 | Grep 所有 `performUndo`/`performRedo` 引用 → 已有 `MonitorScreen`、`PortableTerminalScreen` 各有一套自己的撤销，不受影响 |
| batch 内个别 op 不可逆（reverseOp 返回 null） | 跳过不可逆 op，只撤销可逆的 |
| 迁移遗漏导致某操作不可撤销 | 逐处对照 12 处 takeSnapshot，编译 + 测试覆盖 |

## 预期收益

- 删除 ~80 行 dead code（静态栈 + takeSnapshot/performUndo/performRedo/replaceGraph）
- 消除静态共享撤销栈（collab-plan 技术债）
- 注释拖动一次性撤销（自然批量，无需 hack）
- 所有操作统一 op-based 撤销，无两套系统冲突
