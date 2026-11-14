# 图编辑器高精度手势缩放 / Graph Editor High-Precision Gesture Zoom — 🔶 施工中 / In progress

> **状态 / Status**：GZ-1（连续映射核心）与 GZ-2（捏合直驱，Win32 手势钩子）已落地并
> **实机验收通过**（2026-11-15：1:1 无极捏合、两指中心锚点）；GZ-3（动量）默认关（精度优先，留开关）。
> **问题 / Problem**：滚轮缩放按「每事件 ±1 刻度 ×1.12」量化（`GraphEditor.mouseScrolled`），
> `sy` 的大小被无视——高精度触摸板/数位板/旋钮/高分辨率滚轮的**分数增量全部被压成整刻度**，
> 手势缩放既不连续也不跟手。
> **目标 / Goal**：缩放随输入的**原始分数增量连续变化**，锚点不动（光标下图点固定），
> 触摸板两指捏合/滚动、数位板触控环、旋钮拨盘、高分辨率滚轮全部走同一条连续映射。
> **约束 / Constraint**：经典滚轮 ±1 刻度的手感不变（=×1.12）；不重绑「滚动=缩放」为平移
> （不动现有肌肉记忆）；缩放范围仍 0.25–4；逐项可回滚。
> **交叉引用 / Cross-refs**：[`blender-shimmer-free-drawing-brief.md`](blender-shimmer-free-drawing-brief.md)
> （滚轮固定步进 vs 拖拽连续缩放的对照）、[`graph-editor-canvas-jitter-plan.md`](graph-editor-canvas-jitter-plan.md)
> （相位锁定——连续缩放必须继续走 c2s 量化翻译项）、输入协议简报（待落地）。

---

## 一、通道现状 / Input channels today

| 设备 | 产生的输入 | GLFW/GL 能拿到吗 |
|---|---|---|
| 经典滚轮鼠标 | ±1 刻度滚轮 | ✅ `mouseScrolled` 的 `sy=±1`（多刻度一事件=±N） |
| 高分辨率滚轮 / 旋钮拨盘 | 分数刻度滚轮 | ✅ `sy` 为分数（当前被无视） |
| 触摸板两指滚动（含 Apple Magic Trackpad 2 走 Windows 精密触摸板） | 高频分数滚轮增量 | ✅ `sy` 为分数（当前被量化） |
| 触摸板两指捏合 | Windows 精密触摸板=WM_GESTURE(GID_ZOOM) | ❓待简报确证（GLFW 不转发手势；系统是否合成 Ctrl+滚轮未定） |
| 触屏捏合 | WM_GESTURE / WM_POINTER | ❓同上（预期拿不到） |
| 数位板触控环/笔按钮 | 通常映射为滚轮/键 | ✅ 走滚轮即连续 |

核心判断：**设备无关的连续化（GZ-1）用现有 `sy` 通道就能覆盖滚轮/触摸板滚动/旋钮/数位板四类**；
捏合（GZ-2）取决于协议简报对投递通道的裁定。

## 二、方案 / Fix plan

### GZ-1 · 连续指数映射（核心，本批落地）

- `zoom' = clamp(zoom · 1.12^sy, 0.25, 4)`——每 1.0 增量 = 12%，**经典 ±1 刻度手感逐像素不变**，
  分数增量连续跟手（sy=0.25 → +2.7%，sy=3 → 三刻度等价 1.405×）。
- 锚点沿用增量式 `cam += (mouse − 屏中)·(1/zoom' − 1/zoom)`：任意连续缩放序列下光标下图点不动。
- **锚点与相位**：缩放后平移翻译项仍走整像素量化（抖动治理批 1 的 `transX/transY`）——
  缩放不引入新的相位漂移；`sy=0` 事件退化为 no-op（顺带修正旧代码 sy=0 反向缩小的边角病）。

### GZ-2 · 捏合/触屏通道（已定案并验收：Win32 手势钩子，双通道）

- **实测语义（Win11 build 26300，与经典文档有出入）**：WM_GESTURE 的 wParam=手势 ID（GID_*）、
  lParam=HGESTUREINFO；GESTUREINFO 56B 但**距离字段在偏移 40**（@32 恒 0）；ptsLocation=质心
  屏幕坐标。锚点 = pts − 窗口原点（GetWindowRect 算术，**窗口查询必须用真 HWND**——
  `Window.getWindow()` 返回 GLFW 指针不是 HWND，拿它查窗恒 false，此前一切锚点怪相皆此根因）。
  **2026-11-15 实机验收通过**：1:1 无极捏合、两指中心锚点；诊断轨迹有界（每会话首几条 + 5s
  通道计数），发布前可裁。

- **实测裁定（2026-11-15 两轮探针 + 输入协议简报）**：Windows 把捏合**降级成滚轮刻度**投给
  不处理手势的窗口——简报确证机制：未处理的 WM_GESTURE 冒泡到 DefWindowProc 后由系统映射成
  **WM_MOUSEWHEEL + MK_CONTROL**（官方 Legacy Support 表），另有驱动层直接注入滚轮、不走
  WM_GESTURE 的路径；钩子单通道实测仍滚轮级（GetGestureInfo 失败会 fallthrough 到合成路径，
  或消息根本没到钩子）。
- **实现（v2 双通道）**：
  ① **主通道**：JNA 钩 WndProc + `SetGestureConfig` 申领 GID_ZOOM，相邻手势帧的**手指间距比**
  1:1 直驱（`ScreenTransform.pinchStep`）；GID_PAN 不申领，两指平移继续走滚轮合成给 GZ-1；
  申领失败时不消化手势流（防吞两指平移）。GESTUREINFO 读取按 cbSize 候选表探测
  （56/64/52/48/72/80，读通即缓存——cbSize 与系统 sizeof 不匹配时 API 直接 FALSE 且不写缓冲，
  实测 100% 读不出即此因）。
  ② **稳健回退**：捏合合成滚轮的认定 = **消息级 MK_CONTROL**（官方 Legacy 形态）**或**
  手势流后 400ms 内的滚轮（实测合成滚轮不带 MK_CONTROL——按关联性认定；GID_PAN 未申领，
  两指平移不产生 WM_GESTURE，真滚动不误吞），物理 Ctrl 未按时按细刻度连续映射
  （`ScreenTransform.wheelNotchFactor`，1.05/整刻度）。
  两通道 300ms 去重（手势优先）。锚点 = 捏合中心（桌面坐标 → ScreenToClient → MC GUI 缩放
  坐标，口径同 `MouseHandler.onScroll`）；窗口句柄变化（全屏切换重建窗口）自动重挂；
  非 Windows / 缺 JNA 静默停用，钩子内任何失败不碰渲染线；诊断计数（安装结果/通道计数/
  GetGestureInfo 失败）每 5s 进日志。
- **依赖注记**：LWJGL 3.3.3 的 User32 绑定**没有手势三件套**（GetGestureInfo /
  SetGestureConfig / GESTUREINFO 结构体），「lwjgl-windows」构件并不存在（Windows 绑定住在
  lwjgl core 里）——raw Win32 调用走 **JNA**（`net.java.dev.jna:jna:5.14.0`，单 jar 自带
  natives）。发布打包需把 jna 打进发布 jar（jarJar）；与 Sable/Flywheel 的外置 mod 依赖不同，
  JNA 无 mod 身份、不能走 mods.toml。
- **x64 ABI**：GESTUREINFO 按 Win32 自然对齐手写（56 字节）；32 位客户端不支持（无人跑）。
- **MC 辅助功能陷阱**：`discreteMouseScroll`（辅助功能→鼠标滚轮离散化）开着时，MC 在
  MouseHandler 里把一切滚轮增量 `signum` 成 ±1 再投给界面——触摸板的连续性会被它吃掉，
  GZ-1 拿到的已经是刻度。连续手势缩放要求该选项**关闭**。

### GZ-3 · 动量/惯性（默认关）

- 苹果协议的手势相位含动量段（惯性衰减），但**精度编辑器默认不加**（松手继续缩放=不跟手）；
- 若实测想要：连续源（分数增量）尾部接一段指数衰减续算，常量一键关。

## 三、验收 / Acceptance

| 项 | 方法 |
|---|---|
| 经典手感不变 | sy=±1 与旧版 zoom 结果在 1e-6 容差内一致（×1.12 / ÷1.12） |
| 分数连续 | sy=0.25/0.5/1/2/3 的映射单调连续，无刻度跳变 |
| 锚点不动 | 缩放前后光标下图点偏差 <0.5px（含连续多步序列） |
| 边界 | 0.25/4 钳制；sy=0 为 no-op |
| 自动化 | `ScreenTransform.zoomStep/anchorPan*` 纯函数锚；全量测试保持绿 |

## 四、不做的事 / Non-goals

- 不把滚动改绑为画布平移（触摸板用户两指滚动=平移的平台惯例**不动现有绑定**——要用另议）。
- 不做触屏原生多点追踪（GLFW 无通道时不自建输入栈）。
- 不动注释文本的 Ctrl+滚轮绑定（悬停拦截语义照旧）。
