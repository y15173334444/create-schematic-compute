# IMAGE/IMAGE_SEQUENCE 初始化与同步修复

> 日期：2026-07-24

## 问题

IMAGE_SEQUENCE 节点在以下场景中像素数据丢失：
- Ctrl+D 复制后其他客户端看到空白
- 远程同步（ADD_NODE + flushCopyGroup）后显示全透明
- 保存时 `imageSequenceFrames` 为 null，帧数据不写入 NBT

## 根因

`OpExecutor.SET_IMAGE_PIXELS` 处理 IMAGE_SEQUENCE 时，仅当 `imageSequenceFrames != null` 才更新帧数组。但 IMAGE_SEQUENCE 构造时 `imageSequenceFrames = null`（构造器只初始化了 `imagePixels`）。服务端/远程客户端收到 `SET_IMAGE_PIXELS` 后：

1. `imagePixels` 被正确设置
2. `imageSequenceFrames` 保持 null → 帧数据丢弃
3. 渲染时 `imageSequenceFrames == null` → 跳过渲染
4. `save()` 时 `imageSequenceFrames == null` → `iframes` 标签不写入 → 永久丢失

## 修复

`OpExecutor.java` SET_IMAGE_PIXELS 处理改为延迟初始化 + 扩容：

```java
if (n.type == NodeType.IMAGE_SEQUENCE) {
    if (n.imageSequenceFrames == null)
        n.imageSequenceFrames = new ArrayList<>();
    int fi = op.paramIndex();
    while (n.imageSequenceFrames.size() <= fi)
        n.imageSequenceFrames.add(new int[256]); // blank
    n.imageSequenceFrames.set(fi, pixels.clone());
    n.imagePixels = n.imageSequenceFrames.get(fi); // re-link
}
```

无论帧数据以何种顺序到达，IMAGE_SEQUENCE 都能正确初始化帧列表并链接 `imagePixels`。
