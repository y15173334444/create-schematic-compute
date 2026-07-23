# collab-plan.md 实施验证报告

> 对照 `docs/collab-plan.md` 对实际代码进行逐项验证。
> 验证日期：2026-07-23，基于代码探索和审计结果。

---

## 验证总览

| # | collab-plan 项目 | 状态 | 备注 |
|---|-----------------|------|------|
| 1 | EditSessionRegistry | ✅ 完整实施 | `blocks/EditSessionRegistry.java` (240行) — editors/editVersions/opLogs 三个 Map + join/leave/applyOp |
| 2 | GraphJoinPacket / GraphLeavePacket | ✅ 完整实施 | 距离检查 + BE 类型检查 + EditSessionRegistry 管理 + fullSync 触发 |
| 3 | GraphEditOpPacket (双向) | ✅ 完整实施 | C→S 经 `handleServer()` 带距离+编辑会话双重验证；S→C 拆分为 `GraphEditOpSyncPacket`（NeoForge 限制） |
| 4 | GraphEditAckPacket | ✅ 完整实施 | tempId→assignedId 映射 + editVersion 回执 |
| 5 | GraphPresencePacket / GraphPresenceSyncPacket | ✅ 完整实施 | 光标坐标/选中节点/编辑节点/连线预览，12 字段 |
| 6 | OpExecutor.java | ✅ 完整实施 | `graph/OpExecutor.java` (342行) — 35 种操作，服务端+客户端共享 |
| 7 | OpType + GraphOp | ✅ 完整实施 | `OpType.java` (35 枚举) + `GraphOp.java` (26 字段 + 工厂方法) |
| 8 | Host.sendOp/onRemoteOp/handleAck | ✅ 完整实施 | `GraphEditor.Host` 接口默认方法，含 ID 重映射 |
| 9 | BlueprintScreen 增量编辑 | ✅ 实施 | sendOp/onRemoteOp 已实现；saveGraph() 保留作为兼容/Recompile 路径 |
| 10 | SyncedGraphBlockEntity | ✅ 完整实施 | 抽象基类统一 7 个 BE 的 ~200 行重复代码 |
| 11 | ClientboundGraphEvalPacket | ✅ 完整实施 | 携带 outputs/debugTimes/subOutputs/subDebugTimes 四层数据 |
| 12 | 服务端权威求值 | ✅ 完整实施 | 客户端无 `GraphEvaluator`，所有求值走 `ClientboundGraphEvalPacket` |
| 13 | 节点 ID 服务端分配 | ✅ 完整实施 | 通过 `ADD_NODE_REQUEST`→服务端 `nextNodeId++`→`ADD_NODE` ack 完成 |
| 14 | 多人 Presence UI | ✅ 实施 | 彩色光标+玩家名/节点锁/金色边框/玩家列表/ESC 委托关闭 |
| 15 | Blob 通道 (BlobDataPacket + BlobRegistry) | ✅ 完整实施 | 分片大数据传输 (30KB/片)，用于图像像素/ItemStack NBT |
| 16 | RuntimeState / RuntimeStateSyncPacket | ✅ 完整实施 | flipflopStates 同步客户端显示；其余状态服务端持久化 |
| 17 | BUS 总线系统 | ✅ 完整实施 | BUS_IN/BUS_OUT + ChannelEntry + 引用计数 + 冲突检测 |

---

## ⚠️ 残余差异

### 1. 撤销栈不完全迁移

**状态**: 两套系统并存

- **旧静态栈**: `GraphEditor.java:96-97` — `private static final List<CompoundTag> undoStack/redoStack`
  - 仍被 11 处旧代码调用（`takeSnapshot()`/`performUndo()`/`performRedo()`）
  - Ctrl+D 复制、删除连线、节点删除等路径仍推入静态栈
- **新 per-instance op 栈**: `GraphEditor.java:108-119` — `localUndoStack2`/`localRedoStack2`
  - Ctrl+Z/Y 路由到 op 栈
  - 发送反向 op 到服务端实现协同撤销

**建议**: 暂不清理（风险高，牵涉多处调用点），记录为已知技术债。未来版本可考虑统一为 op 栈并移除静态栈。

### 2. 整图更新可能冲刷编辑者

`EditSessionRegistry.applyOp()` 中对 Monitor 显示相关操作触发 `mbe.flagFullSync()`，这会导致 `sendBlockUpdated` 发送完整图给所有追踪客户端（包括正在编辑的玩家），可能冲刷本地 UI 状态。

**建议**: 编辑者应只收增量 op，不收整图更新。或在客户端添加保护逻辑。

### 3. 重连 opLog 回放未实现

`GraphJoinPacket.handle()` 仅触发 `flagFullSync()` + `getUpdatePacket()`（整图同步），不使用 `EditSessionRegistry.opLogs` 进行增量回放。opLog 数据已收集但未被消费。

**建议**: 未来版本实现断线重连增量同步，减少带宽。

### 4. MOVE_NODE 节流未实现

`GraphEditor.mouseDragged()` 中每次鼠标事件都发送 `MOVE_NODE` op，无节流 (~20-50ms)。高 DPI 鼠标可能导致高频发包。

**建议**: 添加客户端节流（如每 50ms 最多发送一次）。

### 5. editVersion 乐观更新回滚未使用

客户端 `lastAckedVersion` 字段存在，但与服务端 `editVersion` 的比较/回滚逻辑未实施。冲突检测依赖节点软锁（Presence 派生）而非版本比较。

**建议**: 作为额外安全层，在收到 op 时检查版本号，陈旧 op 触发视觉提示。

---

## 超出计划范围的实施

| 项目 | 说明 |
|------|------|
| GraphEditOpSyncPacket | NeoForge 限制导致 S→C 拆分，是对计划"单包双向"的合理偏离 |
| SET_IMAGE_PIXELS / SET_CTRL_POINTS | OpType 超出计划原定 13 种，实际 35 种 |
| BlobDataPacket + BlobRegistry | 独立分片 blob 通道，解决单包 256KB 限制 |
| 书签系统 | ADD/REMOVE/RENAME/MOVE_BOOKMARK 4 种 op，不在原始计划中 |
| Comment 节点全特性 | 大小/颜色/Z序/滚动，超出计划最小化设计 |
| 调试工具链 | DEBUG_SIGNAL_GEN + DEBUG_PROBE，不在原始计划中 |
| 便携终端 | PortableTerminalItem + PortableTerminalScreen，不在原始计划中 |

---

## 总结

**collab-plan.md 的核心架构已全部实施**：服务端权威 + 增量 Op + Presence 三层架构、EditSessionRegistry、OpExecutor、节点 ID 服务端分配、多人协作 UI。

残余的 5 项差异均为优化/健壮性增强（节流、重连回放、乐观回滚），不阻塞核心协作功能。撤销栈双系统是最大的技术债，建议在后续大版本清理。
