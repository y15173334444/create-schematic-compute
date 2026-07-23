# 红石热栏同步修复

> 日期：2026-07-24

## 问题

1. **加入后热栏清空** — 其他玩家加入编辑会话后，REDSTONE_IN/OUT 的 `itemParams` 变为空
2. **实时更新不可见** — 修改热栏物品后其他客户端看不到，只有编译后才同步
3. **Ctrl+D 不复制** — 复制节点时热栏物品丢失

## 根因

三个问题的共同根因在 `OpExecutor.SET_HOTBAR_ITEM`（`graph/OpExecutor.java`）：

```java
// 旧代码
if (n != null && op.hotbarSlot() >= 0 && op.hotbarSlot() < n.itemParams.length) {
    n.itemParams[op.hotbarSlot()] = op.itemStack();
}
```

`GraphNode` 构造时 `itemParams = new ItemStack[0]`（长度 0）。服务端收到 SET_HOTBAR_ITEM op 时，`hotbarSlot()` 为 0，`itemParams.length` 也为 0，条件 `0 < 0` 为 false，op 被静默拒绝。

热栏修改是通过 op 发送的（`GraphEditor.java:2291`），但服务端静默丢弃了。其他玩家加入后从服务端拉取完整图时，服务端图中 `itemParams` 仍为空，覆盖客户端本来修补过的数据。

## 修复

`OpExecutor.java` SET_HOTBAR_ITEM 处理改为自动扩容：

```java
if (n.itemParams == null) n.itemParams = new ItemStack[0];
if (op.hotbarSlot() >= n.itemParams.length) {
    ItemStack[] expanded = new ItemStack[op.hotbarSlot() + 1];
    System.arraycopy(n.itemParams, 0, expanded, 0, n.itemParams.length);
    for (int i = n.itemParams.length; i < expanded.length; i++)
        expanded[i] = ItemStack.EMPTY;
    n.itemParams = expanded;
}
n.itemParams[op.hotbarSlot()] = op.itemStack();
n.graph.bumpGeneration();
```

`OpExecutor.java` 添加了 `import net.minecraft.world.item.ItemStack;`。
