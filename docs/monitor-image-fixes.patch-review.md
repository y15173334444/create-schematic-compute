# 全息显示器 / 图像系统 修复补丁评审

> 基线：`04bf7de`。本文档为**审查用补丁**，未落入任何源码文件。  
> 所有改动需在可编译环境（NeoForge + Minecraft 工件）验证后再合入。  
> 改动之间的依赖：① 与 ② 同源（clamp 上界修正同时消除缩放偏差与边缘伸出）；⑤ 较多且机械，集中在 `MonitorScreen`。

> ✅ 已解决（2026-08-16）：全部补丁经实际代码核对后落地，含下述**评审修正**。修正明细、grilling 决策与提交记录见 `docs/monitor-image-fixes-audit.md`。要点：① 根因与修复方案按评审修正（两侧均为左上角锚定，修 clamp 上界缺因子 2，原"世界渲染器改中心锚定"方案弃用）；③a 补充渲染器 margin 同步；③b 落地重命名 `gi`；⑤ 补充遗漏点与撤销栈标记机制。

---

## ① 缩放过的图像对不齐设定位置（clamp 上界缺因子 2）

**根因（评审修正）**：编辑器 `MonitorScreen.renderDisplayArea`（≈L370-386）与世界渲染器 `MonitorBlockEntityRenderer`（L130-165）**两侧都把 `layoutX/Y` 当成图像左上角**——编辑器 L383 的"Center-based rotation"注释指**旋转轴**而非锚点，拖拽写入（L1425-1436）、命中测试（L1312-1332）同为左上角语义。真正的 bug 是世界渲染器 clamp 上界用了**中心锚定公式** `1-bbHalfW`；左上角锚定的正确上界应为 `1-2*bbHalfW`（须减去完整旋转 AABB）。旧公式让图像在右/下边缘多伸出半个图像宽度；编辑器另有第二道全 AABB clamp（L376-379）把它拉回框内，两侧因此在边框附近错位，偏差最大半幅、随 `displayScale` 线性放大。

**修复（grilling 决策：方案 A，保持左上角锚定）**：只修 clamp 上界，编辑器不动。

`client/renderer/MonitorBlockEntityRenderer.java`（替换 L130-140 的 clamp 部分）：

```diff
             // Clamp so rotated bounding box doesn't overflow right/bottom
             float cell = 0.03f * n.displayScale;
             float iw = 8f * cell, ih = 8f * cell;
             float rA = (float)Math.abs(Math.cos(Math.toRadians(effectiveRot)));
             float rB = (float)Math.abs(Math.sin(Math.toRadians(effectiveRot)));
             float bbHalfW = (iw * rA + ih * rB) / cw;
             float bbHalfH = (iw * rB + ih * rA) / ch;
             float rawX = n.layoutX + dx;
             float rawY = n.layoutY + dy;
-            float cpx = Math.max(0, Math.min(1 - bbHalfW, rawX));
-            float cpy = Math.max(0, Math.min(1 - bbHalfH, rawY));
+            // 左上角锚点 clamp：layoutX/Y 是图像左上角（与编辑器绘制/拖拽/命中一致），
+            // 上界须减去完整旋转 AABB（2*bbHalf）。旧公式只减半幅，导致右/下边缘伸出半幅。
+            // Top-left anchor clamp: the upper bound must subtract the FULL rotated AABB.
+            float cpx = Math.max(0, Math.min(1 - 2 * bbHalfW, rawX));
+            float cpy = Math.max(0, Math.min(1 - 2 * bbHalfH, rawY));
```

> `GeometryConstants.clampImageNorm`（L80-94）同公式同步修正。原方案的"世界渲染器改中心锚定"会使 IMAGE 与编辑器、与同渲染器 TEXT 分支（L174-189，左上角）在**所有位置**差半个图像尺寸，故弃用。

> 像素绘制循环（`x0=px*cell … y0=-py*cell …`）不变：局部原点 (0,0) 仍是图像左上角，平移点仍是"左上角 + halfW/halfH"，两侧锚点语义保持对称。

---

## ② 位移飞出边框（与①同源）

> 评审修正：原描述"位移越界时图像飞出边框"与实际代码不符——clamp 恒有效（`cpx ∈ [0, 1-bbHalfW]`），图像最多在右/下边缘**伸出半幅**，不会整体飞出（即使 bbHalfW>1 时 clamp 钉在 0）。① 的上界修正后该观感一并消除；额外的位移软上限（`Math.clamp(dx, -MAXD, MAXD)`）非必须，未实现。

---

## ③ 放置辅助线非整数倍 + 死区/边框偏大

### 3a. 缩小死区（BEZEL_MARGIN）

`client/GeometryConstants.java`（L13）：

```diff
-    /** Bezel margin in blocks on each edge of the monitor screen */
-    public static final float BEZEL_MARGIN = 0.04f;
+    /** Bezel margin in blocks on each edge of the monitor screen */
+    public static final float BEZEL_MARGIN = 0.02f;
```

> `getContentArea()` / `getContentWorldW()` / `GeometryConstants.clampImageNorm()` 都引用该常量，改一处即全局生效（死区由 0.08 → 0.04 屏宽）。如想更小可继续下调；若要做成“每块可调设置”需改 `MonitorBlockEntity` 存字段并在设置面板加滑块（见 §4.5 同类 UI 改动）。

> 评审补充：世界渲染器 `MonitorBlockEntityRenderer`（L90-93）的 `margin = 0.04f` 是独立硬编码，必须同步改为读 `GeometryConstants.BEZEL_MARGIN`，否则编辑器内容区与世界内容区失配（落地时已一并修改）。

### 3b. 辅助线绑定内容区 + 中心十字（消除 7.1 格非整数）

`blocks/MonitorScreen.java` `renderDisplayArea`（替换 L326-331 的 30px 固定网格）：

```diff
-        int gs = 30;
-        for (int gx = da.x; gx < da.x + da.w; gx += gs)
-            g.fill(gx, da.y, gx + 1, da.y + da.h, 0xFF2C2A24);
-        for (int gy = da.y; gy < da.y + da.h; gy += gs)
-            g.fill(da.x, gy, da.x + da.w, gy + 1, 0xFF2C2A24);
-        g.renderOutline(da.x, da.y, da.w, da.h, 0xFF3A3A3A);
+        // Placement guide grid aligned to the CONTENT (display) area so cells are exact
+        // integers and the center is always locatable. 16 divisions = image-native resolution;
+        // major lines every 4 cells; bold center cross at division 8/8.
+        var ci = getContentArea(da);
+        final int GDIV = 16;
+        for (int gx = 0; gx <= GDIV; gx++) {
+            int x = ci[0] + Math.round(ci[2] * (float) gx / GDIV);
+            int c = (gx == GDIV / 2) ? 0xFF5A4D3A : (gx % 4 == 0 ? 0xFF3A3A3A : 0xFF2C2A24);
+            g.fill(x, ci[1], x + 1, ci[1] + ci[3], c);
+        }
+        for (int gy = 0; gy <= GDIV; gy++) {
+            int y = ci[1] + Math.round(ci[3] * (float) gy / GDIV);
+            int c = (gy == GDIV / 2) ? 0xFF5A4D3A : (gy % 4 == 0 ? 0xFF3A3A3A : 0xFF2C2A24);
+            g.fill(ci[0], y, ci[0] + ci[2], y + 1, c);
+        }
+        g.renderOutline(ci[0], ci[1], ci[2], ci[3], 0xFF5A4D3A);
```

> 若你更希望“格子数随缩放变化（1倍=16格、0.5倍=8格）”，把 `GDIV` 改成 `Math.max(1, Math.round(16f * elem.scale))` 并取当前选中图像的 scale 即可；但固定 16 格更利于“整数/0.5倍对齐 + 找中心”，先给固定版。

> 评审补充：新代码中的 `var ci = getContentArea(da);` 与同方法 `renderDisplayArea` 内 L356 已有的 `ci` 局部变量重复声明，会导致编译错误——落地时已重命名为 `gi`。

---

## ④ 图像管理页“先点1再拖2”卡顿/不跟手（陈旧选择）

**根因**：`handleDisplayAreaClick` 在画布空白/未命中时，会拿上一次选中的 `selectedDisplayNode` 直接开拖（原 L1334-1344），于是你抓的是图2、拖的却是图1（陈旧状态）。

`blocks/MonitorScreen.java`（替换 L1334-1344）：

```diff
-            // No element hit — if already selected via layer panel, start dragging it
-            if (selectedDisplayNode != null) {
-                draggedDisplayNode = selectedDisplayNode;
-                var da2 = computeDisplayArea();
-                var ci2 = getContentArea(da2);
-                float sx = ci2[0] + selectedDisplayNode.layoutX * ci2[2];
-                float sy = ci2[1] + selectedDisplayNode.layoutY * ci2[3];
-                dragOffX = (float)(mx - sx);
-                dragOffY = (float)(my - sy);
-                return true;
-            }
-            selectedDisplayNode = null;
+            // No element hit under cursor. Only drag the selected node if the press point is
+            // actually inside its (clamped) AABB — never grab a stale selection. This fixes the
+            // "select image 1, then drag image 2 → image 1 moves / image 2 doesn't follow" bug.
+            if (selectedDisplayNode != null) {
+                var da2 = computeDisplayArea();
+                var ci2 = getContentArea(da2);
+                float s2 = da2.w * FONT_BLOCK_SCALE / Math.max(getContentWorldW(), 0.01f) * selectedDisplayNode.displayScale;
+                float ex = ci2[0] + selectedDisplayNode.layoutX * ci2[2];
+                float ey = ci2[1] + selectedDisplayNode.layoutY * ci2[3];
+                float[] bb = elemRotAABB(ex, ey,
+                    (selectedDisplayNode.type == NodeType.IMAGE || selectedDisplayNode.type == NodeType.IMAGE_SEQUENCE
+                        ? GeometryConstants.IMAGE_GRID : 10) * IMAGE_CELL_FONT * s2,
+                    (selectedDisplayNode.type == NodeType.IMAGE || selectedDisplayNode.type == NodeType.IMAGE_SEQUENCE
+                        ? GeometryConstants.IMAGE_GRID : 10) * IMAGE_CELL_FONT * s2,
+                    selectedDisplayNode.displayRotation);
+                if (mx >= bb[0] && mx <= bb[2] && my >= bb[1] && my <= bb[3]) {
+                    draggedDisplayNode = selectedDisplayNode;
+                    dragOffX = (float)(mx - ex);
+                    dragOffY = (float)(my - ey);
+                    return true;
+                }
+            }
+            selectedDisplayNode = null;
```

> 图层面板拖动路径（`handleLayerPanelClick` → `layerDragNode = selectedDisplayNode`）本身已正确，无需改。若你那套复现路径是“图层面板行 → 画布移动”，上述守卫即可根除。

> 评审补充（落地实现）：守卫的 AABB 补加了与绘制路径一致的全 AABB clamp（同 L376-379 的四行 clamp），保证边框处守卫区域与实际渲染位置吻合；⑤ 落地后 `IMAGE_GRID` 尺寸改为 `elem.imgW/imgH`。

---

## ⑤ 图像节点支持宽/高（非锁定 16×16）

> 你确认“基础设施完善，可支持自定义画布大小，但界面得改”。以下为引擎侧补丁；UI（EditPanel 加 W/H 输入框）在 §4.5 指引。

### 4.1 GraphNode — 增加字段 + 按尺寸分配/拷贝/存档

`graph/GraphNode.java`：

```diff
     public int[] imagePixels;                      // IMAGE 节点：16×16 ARGB 像素（延迟分配）
+    public int imageWidth = 16, imageHeight = 16;   // IMAGE 节点画布尺寸（默认 16×16，可长方形）
```

L428-430 构造分配：

```diff
-            this.imagePixels = new int[256];
-            java.util.Arrays.fill(this.imagePixels, 0x00000000);
+            this.imagePixels = new int[imageWidth * imageHeight];
+            java.util.Arrays.fill(this.imagePixels, 0x00000000);
```

L479 拷贝：

```diff
-        if (imagePixels != null) n.imagePixels = imagePixels.clone();
+        if (imagePixels != null) n.imagePixels = imagePixels.clone();
+        n.imageWidth = imageWidth; n.imageHeight = imageHeight;
```

L557-559 存档（加宽高，仅非默认时写以省空间）：

```diff
         if (type == NodeType.IMAGE || type == NodeType.IMAGE_SEQUENCE) {
             if (imagePixels != null) tag.putIntArray("ipx", imagePixels);
+            if (imageWidth != 16) tag.putInt("iw", imageWidth);
+            if (imageHeight != 16) tag.putInt("ih", imageHeight);
         }
```

L632 读档（含长度不匹配的迁移保护）：

```diff
         if (tag.contains("ipx")) node.imagePixels = tag.getIntArray("ipx");
+        if (tag.contains("iw")) node.imageWidth = tag.getInt("iw");
+        if (tag.contains("ih")) node.imageHeight = tag.getInt("ih");
+        // 迁移保护：旧档 ipx 长度可能与新尺寸不符，按最小长度复制
+        if (node.imagePixels != null && node.imagePixels.length != node.imageWidth * node.imageHeight) {
+            int[] fixed = new int[node.imageWidth * node.imageHeight];
+            System.arraycopy(node.imagePixels, 0, fixed, 0,
+                Math.min(node.imagePixels.length, fixed.length));
+            node.imagePixels = fixed;
+        }
```

### 4.2 OpExecutor — 空白帧按尺寸分配 + 读档补尺寸

`graph/OpExecutor.java`：

```diff
-                    int[] blank = new int[256];
+                    int[] blank = new int[n.imageWidth * n.imageHeight];
```

（L372 附近；`n` 为当前 IMAGE 节点，上下文可见）

L494 读档路径（若 `n` 在该作用域）：

```diff
-        if (tag.contains("ipx")) node.imagePixels = tag.getIntArray("ipx");
+        if (tag.contains("ipx")) node.imagePixels = tag.getIntArray("ipx");
+        if (tag.contains("iw")) node.imageWidth = tag.getInt("iw");
+        if (tag.contains("ih")) node.imageHeight = tag.getInt("ih");
```

### 4.3 MonitorBlockEntityRenderer — 循环按尺寸 + 半尺寸按尺寸

L127 长度校验：

```diff
-            if (pixels == null || pixels.length != 256) continue;
+            if (pixels == null || pixels.length != n.imageWidth * n.imageHeight) continue;
```

L149-151 循环：

```diff
-            for (int py = 0; py < 16; py++) {
-                for (int px = 0; px < 16; px++) {
-                    int idx = py * 16 + px;
+            for (int py = 0; py < n.imageHeight; py++) {
+                for (int px = 0; px < n.imageWidth; px++) {
+                    int idx = py * n.imageWidth + px;
```

并在 §① 的同一行把半尺寸改为实际尺寸（二者同处，合并应用）：

```diff
-            float halfW = 8f * cell, halfH = 8f * cell;
+            float halfW = (n.imageWidth * 0.5f) * cell, halfH = (n.imageHeight * 0.5f) * cell;
```

### 4.4 MonitorScreen — DisplayElement 加尺寸 + 替换所有 `IMAGE_GRID` 尺寸用法 + renderPixels 签名

**(a) DisplayElement 记录加 `imgW, imgH`**（L903-904）：

```diff
-    private record DisplayElement(int nodeId, NodeType type, String text, float value, int[] pixels,
-        String label, float x, float y, float scale, float rotation, int color) {}
+    private record DisplayElement(int nodeId, NodeType type, String text, float value, int[] pixels,
+        String label, float x, float y, float scale, float rotation, int color, int imgW, int imgH) {}
```

**(b) collectDisplayElements 四处构造调用补尺寸**：TEXT/DATA 传 `0, 0`；IMAGE / IMAGE_SEQUENCE 传 `n.imageWidth, n.imageHeight`。例（L917、L923、L938、L959）：

```diff
-                    list.add(new DisplayElement(n.id, n.type, n.displayText, 0, null, "", n.layoutX, n.layoutY, n.displayScale, n.displayRotation, tc));
+                    list.add(new DisplayElement(n.id, n.type, n.displayText, 0, null, "", n.layoutX, n.layoutY, n.displayScale, n.displayRotation, tc, 0, 0));
...
-                    list.add(new DisplayElement(n.id, n.type, "", val, null, lbl, n.layoutX, n.layoutY, n.displayScale, n.displayRotation, dc));
+                    list.add(new DisplayElement(n.id, n.type, "", val, null, lbl, n.layoutX, n.layoutY, n.displayScale, n.displayRotation, dc, 0, 0));
...
-                    list.add(new DisplayElement(n.id, n.type, "", 0, n.imagePixels, "", cp[0], cp[1], n.displayScale, effRot, 0));
+                    list.add(new DisplayElement(n.id, n.type, "", 0, n.imagePixels, "", cp[0], cp[1], n.displayScale, effRot, 0, n.imageWidth, n.imageHeight));
...
-                    list.add(new DisplayElement(n.id, n.type, "", 0, pixels, "", cp[0], cp[1], n.displayScale, effRot, 0));
+                    list.add(new DisplayElement(n.id, n.type, "", 0, pixels, "", cp[0], cp[1], n.displayScale, effRot, 0, n.imageWidth, n.imageHeight));
```

**(c) 元素尺寸统一用 `elem.imgW/imgH`**（替换所有 `IMAGE_GRID * IMAGE_CELL_FONT`）。下列行都把 `IMAGE_GRID * IMAGE_CELL_FONT` 改为 `elem.imgW * IMAGE_CELL_FONT`（宽） / `elem.imgH * IMAGE_CELL_FONT`（高）：

- L365, L368（绘制尺寸）
- L410, L413（选中描边尺寸）
- L469, L470（命中尺寸）
- L1278, L1302（图层缩略图命中尺寸）
- L1416, L1417（拖动时尺寸）

**(d) renderPixels 签名加 `gridW, gridH`**（`renderPixels` 定义处，原 `int gridSize` 单参 → 双参）：

```diff
-    private void renderPixels(GuiGraphics g, int[] pixels, int x, int y, int cellSize, int gridSize) {
+    private void renderPixels(GuiGraphics g, int[] pixels, int x, int y, int cellSize, int gridW, int gridH) {
```

函数体内 `py < gridSize / px < gridSize / idx = py * gridSize + px` 改为 `gridH / gridW / py * gridW + px`。  
调用点：

- L399 `renderPixels(g, elem.pixels, 0, 0, 2, 16)` → `renderPixels(g, elem.pixels, 0, 0, 2, elem.imgW, elem.imgH)`
- L536 `renderPixels(g, node.imagePixels, offsetX, offsetY, cellSz, 16)` → `renderPixels(g, node.imagePixels, offsetX, offsetY, cellSz, node.imageWidth, node.imageHeight)`
- L552 同 L536 模式，按节点尺寸。

**(e) 像素编辑器网格按节点尺寸**（两处：L1037-1111 与 L1524-1539）：

- `int gridPx = cellSize * 16;` → `cellSize * node.imageWidth`（其中 `node` 为该像素编辑目标；若该作用域无 node 变量，用 `pixelEdit.node.imageWidth`）。
- `int[] frame = new int[256];`（L1117）、`int[] newFrame = new int[256];`（L1591、L1609） → `new int[pixelEdit.node.imageWidth * pixelEdit.node.imageHeight]`。
- 循环 `for py < 16 / px < 16 / idx = py*16+px`（L1055-1057、L1383-1384、L1535-1536） → 用 `pixelEdit.node.imageHeight / imageWidth`。
- `offsetX/Y` 居中计算（L534-535、L550-551、L1110-1111）：`(size - 16*cellSz)/2` → `(size - node.imageWidth*cellSz)/2`（高同理）。

**(f) 图层缩略图 `renderLayerThumbnail`**：内部若有 `IMAGE_GRID` 尺寸用法，同样改为传入节点的 `imageWidth/Height`（该函数在 L656-669 附近被调用，需顺带把节点尺寸传入或在其内读 `layerDragNode.imageWidth`）。

### 4.5 编辑器 UI（EditPanel）加 W/H 输入 — 「界面得改」

你已确认基础设施支持，UI 需改。落点在 `blocks/EditPanel.java`：

- L79 `if (IMAGE/IMAGE_SEQUENCE) h += 54 + 32;`：加两行数字输入的高度（如 `+ 20`）。
- L434 `if (IMAGE/IMAGE_SEQUENCE && node.params.length > 3)` 附近：新增两个整型输入框绑定 `node.imageWidth / node.imageHeight`，变更时走 `GraphOp`/`sendOp` 更新节点（参考同文件其它数字字段的改法）。
- 注意联动：改尺寸时应按新 `W*H` 重新分配 `imagePixels` 并保留旧内容（用 §4.1 的迁移式 `System.arraycopy`），避免像素丢失；缩略图/编辑器网格随之刷新。

> `GraphMigration.java` 仅判断 `isImage`，无硬编码 256，无需为 ⑤ 改动迁移逻辑。

### 4.6 评审补充的遗漏点（落地时已并入实现）

原补丁未覆盖、评审核对实际代码时发现的缺口：

1. `GeometryConstants.clampImageNorm` 半宽/半高硬编码 `8f * IMAGE_CELL_BLOCK`（编辑器 collect 路径在用）→ 签名加 `imageW/imageH` 参数，按节点尺寸计算。
2. `MonitorScreen.sendFrameSync`（L172）回退数组 `new int[256]` → 按 `node.imageWidth * node.imageHeight`。
3. `OpExecutor.readNode`（L494）读档路径补 `iw/ih` 读取与迁移保护，与 `GraphNode.load` 共用 `GraphNode.fixImagePixelsToSize()`。
4. IMAGE_SEQUENCE **全部帧**需随 W/H 重分配（`GraphNode.resizeImagePixels()` 统一处理），否则旧帧过不了渲染器长度校验被整体跳过。
5. 像素撤销栈 count-marker 与像素数组混用（`top.length == 1` 判定）在 1×1 画布下误判 → 落地改为并行元数据列表（-1=像素条目，N=帧数标记）；并补全 `performPixelRedo` 缺失的帧路径（原实现会把 `int[]{curCount}` 标记误当像素数组赋给 `imagePixels`）。

### 4.7 落地决策（grilling 确认）

- 尺寸范围 **1..32**（`GraphNode.IMAGE_MAX_SIZE`）；上限 32 平衡渲染开销（32×32=1024 像素/节点，每像素一个 quad）。
- resize 语义：左上角逐行拷贝保留内容、新增区域透明；IMAGE_SEQUENCE 全帧同步；像素撤销栈随会话重建（每次开像素编辑器新建 `PixelEditState`，天然清空）。
- NBT 新增可选键 `iw`/`ih`（仅非默认值写入）：旧档缺省 16×16、旧版客户端读取时忽略未知键 → 无需升 `DATA_VERSION`，无 `GraphMigration` 变更。
- 网络：新增 `SET_IMAGE_SIZE` op（paramIndex=w, keyIndex=h），复用 `GraphEditOpPacket` 通用全字段序列化，零新增包类；`EditSessionRegistry` 同步列入 flagFullSync 清单。

---

## 验证清单（合入前自查 → 2026-08-16 落地状态）

- [x] ① 编辑器与世界渲染器同为左上角锚定；clamp 上界含完整旋转 AABB（`1-2*bbHalf`），边框处不再伸出半幅，偏差不再随 scale 增大。
- [x] ② 位移 `msX` 拉满时图像钉在框内（clamp 恒有效；修正后边缘伸出消除）。
- [x] ③a 死区 0.08→0.04 屏宽（编辑器与世界渲染器 margin 同源常量）；③b 辅助线绑定内容区、16 等分、中心十字（落地重命名 `gi`）。
- [x] ④ 先点图1、再在画布抓图2拖动 → 图2 跟手移动；图1 不动；空白处点击正确取消选择。
- [x] ⑤ 新建 IMAGE 节点设 W=32,H=8 → 像素编辑器为 32×8 网格；世界渲染为长方形；旧 16×16 存档读入不丢像素（迁移保护由 `GraphNodeImageSizeTest` 覆盖）。
- [x] 全量编译通过；`./gradlew test` 245 例全绿（⑤ 未触碰求值器）。
- [ ] 游戏内观感验证（渲染/多人同步）——待 `runClient` 人工确认。

