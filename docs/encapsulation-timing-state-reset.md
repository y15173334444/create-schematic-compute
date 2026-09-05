# 封装节点输出假数值——V3 子评估器缓存掩盖的时序组件复位问题

> 最终根因：`recompileEvaluatorFull()` 的 `runtimeState.clear()` 清除了封装子图内时序组件（DELAY、LATCH、T_FLIPFLOP 等）的状态。
> 1.2.3 的子评估器缓存（创建后永不失效）意外屏蔽了此问题——缓存复用时状态保留在旧的 `subDelayQueues` 等 Map 中。
> 1.2.4 修复了缓存失效机制后（`subGraphGenerations` 检测），每次主图重编译子评估器重建，时序状态丢失 =
> 时序节点输出 0 或默认值 → 依赖其输出的公式计算偏差 → 封装整体输出错误的"假数值"。
> 任何图操作（移动节点、连线探针）触发重编译 → 错误的时序状态刚好被"重置"到初始值，反而碰巧输出正确。

---

## 1. 链路分析

```
v1.2.3（bug 被掩盖）:
  子评估器创建后永不失效 → subDelayQueues 等 Map 跨 tick 保留
  时序组件状态正确累积 → 公式输出正确

v1.2.4.1 修复缓存失效后（bug 暴露）:
  每次主图重编译 → runtimeState.clear() → subStates 全部清除
  新评估器的 subDelayQueues 等为空 → 时序组件从零开始
  → 输出偏差

任何图操作（移动节点、连线探针）→ bumpGeneration → recompileEvaluatorFull
→ runtimeState.clear() 把所有状态"复位"到 0 → 碰巧与初始状态一致 → "正确"
```

---

## 2. 修复

**`SyncedGraphBlockEntity.recompileEvaluatorFull()`** — 在 `runtimeState.clear()` 前保存 `subStates`，重编译后恢复到 `runtimeState`，再传给 `restoreSubState()`。与 `debugTime` 的保存/恢复逻辑一致。

```java
// clear() 前
Map<Integer, RuntimeState.SubState> savedSubStates = new HashMap<>(runtimeState.subStates);
runtimeState.clear();

// 重建评估器后
runtimeState.subStates.putAll(savedSubStates);
evaluator.restoreSubState(runtimeState);
```

---

## 3. 其他相关修复（v1.2.4.1 完整清单）

| # | 修复 | 说明 |
|---|------|------|
| 1 | 数值输入回弹 | 编辑器打开时跳过 NBT 图替换 |
| 2 | 公式双缓存→单缓存 | `GraphEvaluator` 移除 `scriptCache`，统一用 `node.cachedScript` |
| 3 | `ensureScriptParsed` 新鲜度检测 | `sourceFormula` 字段检测公式变更 |
| 4 | `inputPinIndex` 添加 `ensureScriptParsed()` | 与 `outputPinIndex` 对称，首次加载即可解析命名 pinId |
| 5 | 旧版动态引脚兼容 | `inputPinId`/`outputPinId` 越界时返回数字回退 |
| 6 | V3→V4 迁移单源 | `GraphMigration` 复用 `GraphNode` pinId 方法 |
| 7 | 编译 BUS 断线 | `loadGraphFromBytes` 移除 `cleanupBusChannels` |
| 8 | 公式清空回弹 A+B | `createEditState` 不再强制默认值 |
| 9 | 公式编辑器光标/选区 | MLE 图空间坐标转换 + 方向键折叠选区 |
| 10 | 临时视角按 BlockPos | `Map<BlockPos, float[]>` 替代 static |
| 11 | 子图展开状态 | `enterSubGraph/exitSubGraph` 重置 `lastInitGeneration` |
| 12 | Sable 重连 | `getPlot(chunkPos)` 安全返回子世界依赖 |
| 13 | 封装子评估器陈旧 | `subGraphGenerations` 检测世代变化 |
| 14 | BUS 信号快照（未采用） | ~~`SignalBus.snapshot()` 冻结 tick 开始时的全局信号~~——代码库从未实现该方法（全量搜索无任何 `snapshot()` 调用点/定义），清单更正为「未采用」；当前 BUS/PRIVATE 频道为即时读写共享表，跨方块传播按各宿主自身求值顺序生效 |
| 15 | 重编译保留子图状态 | `recompileEvaluatorFull()` 保存/恢复 `subStates` |
