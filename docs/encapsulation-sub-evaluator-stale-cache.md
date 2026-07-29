# 封装节点输出假数值——子评估器缓存失效问题

> 现象：封装节点（ENCAPSULATION）输出"假数值"（通常为 0），移动其连接的探针（DEBUG_PROBE）后恢复正常。
> 关联文件：`graph/GraphEvaluator.java`（ENCAPSULATION eval 分支、`captureSnapshot`、`subEvaluators`）

---

## 0. 一句话结论

`GraphEvaluator` 对每个 ENCAPSULATION 节点维护一个**子评估器缓存**（`subEvaluators`，`Map<Integer, GraphEvaluator>`）。子图被编辑后（如修改内部公式节点），`EditSessionRegistry.applyOp` 会 bump 主图 generation → 主评估器重建 → `subEvaluators` 随新主评估器清空。但 `captureSnapshot()` 在每 tick 求值后调用 `subEval.outputs.clear()` **清空子图输出**——如果子评估器因任何原因未随主评估器重建，下个 tick 的 ENCAP_OUTPUT 节点就会读到空 map → 输出 0（假数值）。

---

## 1. 链路分析

```
每 tick 求值:
  ENCAPSULATION eval:
    subEvaluators.get(node.id) → 复用缓存（或懒创建）
    注入 ENCAP_INPUT → subEval.outputs
    拓扑求值子图节点（含 FORMULA）→ subEval.outputs
    ENCAP_OUTPUT 从 subEval.outputs 读取 → 封装输出 o[]

  captureSnapshot():
    遍历 subEvaluators → 复制 subEval.outputs → subEval.outputs.clear()  ← 清空！
    广播 ClientboundGraphEvalPacket（含 subOutputs）

子图编辑后:
  OpExecutor.apply → bump 子图 generation
  EditSessionRegistry.applyOp → bump 主图 generation
  下个 tick: graphChanged() → recompileEvaluatorFull()
    → evaluator = new GraphEvaluator(graph)  ← subEvaluators 随旧评估器丢弃
    → 子评估器在下一次 ENCAPSULATION eval 时懒创建（新鲜）

但如果主评估器未被重建（generation 恰未变化）:
  subEvaluators 保持旧引用 → subEval.outputs 已被 clear() → 空
  ENCAP_OUTPUT 读到 0 → 假数值
```

---

## 2. 为什么"移动探针就正常了"

移动探针 → 断开旧连接 + 建立新连接 → 触发 `EditSessionRegistry.applyOp`（ADD_CONN） → bump 主图 generation → `recompileEvaluatorFull()` → 新主评估器 → 子评估器重建 → 输出正确。观察者效应再次出现。

---

## 3. 修复

**治本：子评估器自己检测子图 generation 变化，无需依赖主评估器重建。**

在 `GraphEvaluator` 中新增 `subGraphGenerations` 映射（`encapId → lastKnownGeneration`）。ENCAPSULATION eval 分支在复用子评估器之前，比较当前 `node.subGraph.graphGeneration` 与缓存值——不一致则丢弃旧评估器，创建新的：

```java
case ENCAPSULATION -> {
    ...
    int currentGen = node.subGraph.graphGeneration;
    Integer lastGen = subGraphGenerations.get(node.id);
    if (lastGen == null || lastGen != currentGen) {
        subEvaluators.remove(node.id);  // 丢弃陈旧缓存
        subGraphGenerations.put(node.id, currentGen);
    }
    var subEval = subEvaluators.get(node.id);
    if (subEval == null) {
        subEval = new GraphEvaluator(node.subGraph);
        subEvaluators.put(node.id, subEval);
        ...
    }
    ...
}
```

同时需要在 `captureSnapshot` 中迁移（而非清空）子评估器的 outputs 克隆，保持与原来一致的语义（清除残留节点 ID）。

---

## 4. 验证

- 封装节点内含公式节点 → 编辑公式 → 探针应立即显示新值（不再需要移动探针触发）
- 多人协作：远程玩家编辑子图内公式 → 本地探针显示更新
- 反复重进存档：子评估器不残留陈旧缓存
