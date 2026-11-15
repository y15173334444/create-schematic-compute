# 虚拟画布与视觉抖动治理 / Graph Canvas & Visual Jitter — 🟡 施工中 / In progress

> **状态 / Status**：🟡 **施工中**：JIT-1 核心（统一变换与取整，`ScreenTransform` 作像素对齐唯一入口）与
> JIT-2（网格整数格）已于 2026-11-14 落地（渲染性能线 `csc-render-perf`），锚 `ScreenTransformTest`；
> 同日按 [`blender-shimmer-free-drawing-brief.md`](blender-shimmer-free-drawing-brief.md) 启示 1 落地
> **残余治理批 1**（平移相位锁定：平移项整像素量化 + 网格原点收编 c2s(0)；连线采样步数 16px 分桶），
> 锚 `GraphCameraPhaseTest`，实机验收通过；
> **批 2**（边缘羽化：清晰芯 + 1px 软肩）经实机裁决**整体移除**（边缘雾感；
> 方形节点硬边审美，不搞圆角感）——相位锁定与连线采样稳定不受影响；
> 边框屏幕线宽随缩放已按 **pose 局部**（编辑区分割线同口径）修正；
> 批 3=文字像素对齐/字号档位（收编 JIT-3），待拍板。
> JIT-4 / JIT-5 / JIT-6 未动。
> 起草于 2026-10-24，基线 `718d38b`（`main`）。
> **问题 / Problem**：平移/缩放时节点框、文字、网格出现 1px 级抖动（shimmer）；命中位置与画面偶发错位。
> 这是**视觉稳定性**问题，与 `graph-editor-render-performance-plan.md` 的帧率问题相互独立。
> **目标 / Goal**：所有几何共用一个变换、一套取整规则；平移/缩放时画面元素不互相爬动；点击位置与绘制位置一致。
> **约束 / Constraint**：不改变命中优先级与交互语义；不引入新的渲染后端；逐项可回滚。
> **交叉引用 / Cross-refs**：
> [`graph-editor-interaction-render-layering-plan.md`](graph-editor-interaction-render-layering-plan.md)（P2 控件几何单一真源）、
> [`graph-editor-render-performance-plan.md`](graph-editor-render-performance-plan.md)（帧率与采样）、
> [`blender-shimmer-free-drawing-brief.md`](blender-shimmer-free-drawing-brief.md)（Blender 无闪烁绘制机制简报——
> 残余抖动治理的方法论输入：边缘覆盖率软边、文字像素对齐/档位、相机相位锁定）、
> [`gui-decomposition-plan.md`](gui-decomposition-plan.md)、[`code-architecture.md`](code-architecture.md)。

---

## 一、Blender / UE 是怎么做到不抖的 / How They Do It

| 机制 | Blender | UE (Slate / Blueprint) |
|---|---|---|
| 坐标空间 | 节点/连线存图空间，绘制时经 `View2D` 变换一次 | 控件存局部空间，`FGeometry` 的 RenderTransform 在 paint 时一次应用 |
| 变换精度 | float 几何，交给 GPU 光栅化；不在每个元素上单独取整 | 同上 |
| 缩放 | 节点编辑器连续缩放，但文字/图标按像素对齐绘制 | **离散缩放档位**（1:1、0.75、0.5…），避免位图文字非整数缩放 |
| 裁剪 | 按 `v2d->cur` 视口逐节点跳过 | `SNodePanel::Paint` 只画与视口相交的节点；连线按包围盒剔除 |
| 批处理 | 同类图元一个 GPU batch；字体/图标走图集 | 控件树 paint 成 draw list，按 shader/纹理合批 |
| 重绘 | 脏区域标记，不变不重画 | `SInvalidationPanel` / `SRetainerWidget` 把子树缓存为纹理 |
| LOD | 缩放过小时简化绘制 | 低缩放下节点简化 |

核心两条：

1. **几何在 float 空间里只变换一次，取整只发生在光栅化**；
2. **看不到的不画、没变的不重画**。

---

## 二、本仓的抖动来源 / Root Causes

### 2.1 节点框先取整，内容却是 float

- 节点头部：`g.fill((int)sx+2, (int)sy+2, …)`（`NodeRenderer.java:388`）
- 内容：`pose.translate(sx, sy); pose.scale(zoom)`（`:389-392`）
- 引脚：又一个 pose（`:504-507`）

结果：**框按 1px 跳，里面的字和引脚平滑移动**，两者相对位置每帧变化 → 视觉抖动。

### 2.2 取整语义混用

渲染 / 命中路径中有 **85 处** `(int)` 或 `Math.round`：

- `(int)` 向零截断（负数不对称），`Math.round` 四舍五入；
- 同一元素的不同部件用了不同规则，边界互相爬；
- 命中侧 `(int)((mx-nsx)/zoom)` 与渲染侧的 `Math.round` 不一致，放大后点击会偏 1px。

### 2.3 网格逐线独立取整

`NodeRenderer.java:250-254` 每条网格线独立 `Math.round(x)`，而间距是 `GS*zoom`（非整数）。
平移/缩放时每条线各自跳 1px → 网格「呼吸」。

### 2.4 连续缩放 + float 相机

- `GraphEditor.java:4096`：每次滚轮 ×1.12，范围 0.25–4，永远是非整数缩放；
- 位图字体被非整数缩放 → 边缘蠕、发糊；
- `GraphEditor.java:462` 相机为 float，`:4097` 的绕光标缩放增量累加，长时间操作累积误差。

### 2.5 命中几何依赖渲染副作用

- `EditPanel.java:189` 在 `renderAt` 里写 `st.bandRemoveBtnX/Y`；
- `GraphEditor.java:2752` 在命中测试里读同一组字段。

即**命中依赖上一帧是否画过该节点**；渲染被裁剪或跳过时读到陈旧矩形。
同时 `tryExpandedEditAreaClicks` 的几何与 `EditPanel.renderAt` 是两份手抄副本（`:2602` 注释自述）。

### 2.6 渲染帧改状态

`renderBg` 内还有手柄轮询、相机过渡、总线同步（`:1345`、`:1462`、`:1679`），
输入与画面可能不在同一状态快照上，表现为「点不准 / 拖拽跟手性差」。

---

## 三、修复方案 / Fixes

### JIT-1 · 统一变换与取整（P0，最高性价比）

> **✅ 2026-11-14 落地**：节点每帧吸附一次原点（框/内容 pose/引脚/连线根/成对命中换算共用），框两缘
> 共用一次取整尺寸；`ScreenTransform` 承担唯一取整入口与「吸附原点 + 局部偏移×zoom」的锚点派生。
> 尚余：装饰件内部偏移的裸 `(int)`（吸附后为常量偏移，不参与拖动爬移）、`ScreenTransform` 持相机态的
> 纯函数化、以及与 JIT-5 合并的命中几何单一真源。

- 所有节点内部绘制放进**同一个 pose**：框、文字、引脚都在 `translate(sx,sy) + scale(zoom)` 内，
  用节点局部整数坐标绘制。
- 禁止在 render / hit 路径使用裸 `(int)`；只保留文字原点 `Math.round`。
- 抽出唯一入口：

```java
final class ScreenTransform {
    double camX, camY, zoom;
    float sx(float gx) { … }          // 图 → 屏
    float sy(float gy) { … }
    static int px(float v) { return Math.round(v); }   // 唯一的像素对齐
}
```

- 验收：慢速平移录屏逐帧对比，节点框与内部元素不再相对跳动。

### JIT-2 · 网格整数化

> **✅ 2026-11-14 落地**：原点吸附 + 逐线 `round(k·GS·zoom)`（整格刚性平移、单线与真实世界线偏差
> ≤1px）；未走纹理工装。

- 每帧计算一次 `int spacing = Math.max(1, Math.round(GS * zoom))`、
  `int offset = Math.round(camOffset) % spacing`，所有线由整数推出；
- 或直接绘制一张平铺网格纹理。
- 验收：`zoom` 0.25–4、慢速平移下网格线间距稳定，无逐线漂移。

### JIT-3 · 文字离散缩放 / LOD

- 文字只在档位缩放（1、0.75、0.5…）绘制，或按最近档位绘制后反向补偿；
- `zoom < 0.5` 只画标题（与性能方案 PERF-1 共用同一套阈值）。
- 验收：缩放过程中文字不蠕、不糊到不可读。

### JIT-4 · 相机 double + 单一锚点

- `camX/camY` 改 `double`；绕光标缩放从锚点公式一次算出，不做增量累加漂移。
- 验收：连续 100 次缩放后回到原位，画面与初始一致（误差 < 0.5px）。

### JIT-5 · 命中几何单一真源

- 引入 `EditPanelLayout.compute(node, editState)` 纯函数，渲染与命中共用；
- 删除渲染写入的 `bandRemoveBtnX/Y`、`freqSlotX/Y` 等字段；
- 与分层方案 P2 是同一刀，不要重复实现。
- 验收：渲染被裁剪时命中仍正确；`EditAreaOcclusionGateTest` 绿。

### JIT-6 · 输入与渲染同快照

- 依赖分层方案 P1：`FrameContext` 每帧只读，输入与渲染消费同一个快照。
- 验收：拖拽跟手性、点击位置在缩放 0.25/1/4 下均一致。

---

## 四、实施顺序 / Order

```
JIT-1（统一变换）→ JIT-2（网格）→ JIT-3（文字档位）
        ↓
JIT-5（几何单一真源，与分层 P2 合并）
        ↓
JIT-4（double 相机）→ JIT-6（同快照，依赖分层 P1）
```

JIT-1 与 JIT-2 可以独立提交；JIT-3 与性能方案 PERF-1 合并做，避免两套阈值。

---

## 五、验收 / Acceptance

| 项 | 方法 |
|---|---|
| 无相对抖动 | 固定场景慢速平移 10 秒录屏，逐帧检查节点框与内容相对位移 |
| 网格稳定 | 缩放 0.25/0.5/1/2/4，观察线距是否逐帧漂移 |
| 命中一致 | 缩放 0.25/1/4 下点击各控件边缘，命中区域与视觉边界误差 ≤1px |
| 相机无漂移 | 缩放 100 次后回原位，截图 A/B 对比 |
| 自动化 | 现有 `EditAreaOcclusionGateTest`、`NodeAddMenuHitTest`、`WireDeleteHitRegressionTest`、`EditorKeysPixelAndWireTest` 保持绿；新增布局/变换单测 |

---

## 六、风险 / Risks

| 风险 | 缓解 |
|---|---|
| 统一 pose 后文字/边框出现半像素模糊 | 只对文字原点取整；框体允许 float 平移，与 UE/Blender 一致 |
| 网格整数化导致与真实缩放轻微偏差 | 偏差上限 1px，优先稳定性 |
| 离散缩放手感变化 | 档位间可用最近档位 + 平滑过渡；先做 LOD，再评估档位 |
| 与功能开发冲突 | 同分层方案：功能冻结窗口 + 小提交 |

---

## 七、开放问题 / Open Questions

1. 文字离散缩放是否接受「档位切换时轻微跳变」？还是只在 LOD 阈值处隐藏？
2. 网格用整数算术还是纹理平铺？后者更稳但要处理缩放过滤。
3. 是否把 `ScreenTransform` 同时作为命中与渲染的唯一变换入口（推荐是）？
4. 现有玩家/自动化是否依赖当前 1px 级命中边界？

---

## 八、不做的事 / Non-goals

- 不改渲染后端、不引入 GPU 批量。
- 不重写 `NodeRenderer` / `EditPanel` 的绘制内容。
- 不在本文件处理帧率/采样：见性能方案。
