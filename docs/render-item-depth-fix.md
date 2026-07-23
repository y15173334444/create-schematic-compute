# 红石频段物品图标渲染优先级修复

> 日期：2026-07-24

## 问题

REDSTONE_IN / REDSTONE_OUT 节点展开时，频段槽位中的物品图标穿透 EditBox 文本和节点边框。

## 根因

`GuiGraphics.renderItem()` 内部调用 `ItemRenderer` 渲染 3D 模型，硬编码 Z≈250（`GuiGraphics` 中 `pose.translate(x+8, y+8, 150 + (model.isGui3d() ? 100 : 0))`）。Minecraft GUI 正交投影中 Z=250 映射的深度值低于 Z=0 的 2D 元素，导致深度测试把后续的 EditBox 文本（Z=0）判定为被遮挡。

## 修复

渲染物品前 `depthMask(false)` → 物品写入颜色缓冲但不写入深度缓冲 → 渲染后 `depthMask(true)`。

```java
RenderSystem.depthMask(false);
g.renderItem(item, x, y);
RenderSystem.depthMask(true);
```

物品本身正常显示，其深度值不残留，后续 2D UI 不受影响。

## 修改文件

| 文件 | 位置 | 修改 |
|------|------|------|
| `blocks/EditPanel.java:400` | REDSTONE_IN/OUT 频段槽位 | 物品渲染包裹 `depthMask(false/true)` |
| `blocks/GraphEditor.java:1822` | 热栏物品选择器 | 同上 |
