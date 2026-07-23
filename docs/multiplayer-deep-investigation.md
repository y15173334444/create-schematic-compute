# 多人协作深度排查报告

> 排查日期：2026-07-23
> 版本：1.2.4

---

## 1. Ctrl+D 复制数据流分析

### 完整路径

```
Ctrl+D (GraphEditor.keyPressed L3706)
  → shallowCopyWithNewId(tempId)  // 发起者本地完整副本
  → addNodeRequest()              // 构造 ADD_NODE_REQUEST op, 仅含 (type, nodeType, x, y, tempId, actor)
  → 服务端 EditSessionRegistry.applyOp()
    → OpExecutor.apply(): ADD_NODE_REQUEST → graph.addNode() 创建骨架节点(默认值)
    → 广播 ADD_NODE 给其他编辑器 (同样只有骨架)
    → 发送 GraphEditAckPacket(tempId, assignedNodeId) 给发起者
  → 发起者 remapNodeId() → flushCopyGroup()
    → 发送 SET_PARAM / SET_FORMULA / SET_DISPLAY_TEXT / ADD_CONN 等数据op
```

### flushCopyGroup() 缺失字段

| 缺失字段 | 严重度 | 说明 |
|---------|--------|------|
| `textColor` | HIGH | 无对应 op 类型存在 |
| `imageSequenceFrames[1..N]` | HIGH | 仅帧0通过 SET_IMAGE_PIXELS 发送 |
| `layoutX/Y`, `displayScale`, `displayRotation` | MEDIUM | 无对应 op |
| `itemParams[]` | MEDIUM | 无 SET_HOTBAR_ITEM |
| `signalBands` | MEDIUM | 从未发送 |
| `sortB`, `moveScale` | LOW | 非关键元数据 |

---

## 2. 图同步初始化时序

### 玩家加入编辑会话

```
帧0: 客户端创建Screen → be.graph = new NodeGraph() (空)
     → init() 发送 GraphJoinPacket

帧1-3: 服务端处理 → 注册编辑者 → flagFullSync() → getUpdateTag() 返回完整图
     → 客户端 loadAdditional() 替换 be.graph
     → 下一个渲染帧显示完整图

帧1-3: 第一个 ClientboundGraphEvalPacket 到达 (可能有0-1 tick延迟)
```

**部分初始化窗口**：~16-48ms，太短暂人类无法触发。但 Screen 在此期间已交互。

### chunk-load 场景 (无 UI 打开)

玩家走入渲染距离 → vanilla chunk sync → `getUpdateTag()`。如果 `needsFullSync` 已被消费（例如之前的 join 或 block update），则只返回 `{"running":true}`。客户端 BE 初始化时 graph 为空且 running=true，永不会收到完整图（直到下一次 flagFullSync 触发）。

**受影响**: Blueprint, ControlSeat, Sensor, SpeedProxy, ProgramComputer, Radar (部分)
**不受影响**: MonitorBlockEntity (总是返回完整数据)

---

## 3. 竞态条件

| # | 问题 | 严重度 | 文件 |
|---|------|--------|------|
| R1 | getUpdateTag() 对新 chunk-loader 只返回 running | MEDIUM | SyncedGraphBlockEntity.java:284-294 |
| R2 | BlobRegistry.cleanup() 从未调用 — 内存泄漏 | MEDIUM | BlobRegistry.java:68 |
| R3 | REJECT op (ADD_CONN) 缺少连接信息，客户端无法回滚 | MEDIUM | EditSessionRegistry.java:136-137 |
| R4 | ENCAPSULATION 子图 debugTime 在重载后未恢复 | MEDIUM | GraphEvaluator.java:69-86, 827-830 |
| R5 | recompileEvaluatorFull() 中 clear() 意外清除 debugTime | LOW | SyncedGraphBlockEntity.java:168-179 |
| R6 | 加入时 eval snapshot 短暂缺失 (0-1 tick) | LOW | SyncedGraphBlockEntity.java:205-213 |
| R7 | EditSessionRegistry 无同步 — 依赖 NeoForge 单线程 | LOW | EditSessionRegistry.java |
| R8 | 多 op 节点删除竞态 — 已优雅处理 | LOW | EditSessionRegistry.java |

---

## 4. 修复优先级

| 优先级 | 修复项 | 预计工作量 |
|--------|--------|-----------|
| HIGH | flushCopyGroup() 补完缺失字段 | Medium |
| HIGH | getUpdateTag() 对新 chunk-loader 返回完整图 | Small |
| MEDIUM | BlobRegistry.cleanup() 定时器 | Small |
| MEDIUM | REJECT op 包含完整连接信息 | Trivial |
| MEDIUM | 子图 debugTime 恢复 | Small |
| LOW | recompileEvaluatorFull() 保护 debugTime | Trivial |
| LOW | EditSessionRegistry 线程文档 | Trivial |
| LOW | 客户端 graph-ready 加载标志 | Small |
