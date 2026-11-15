# 高精度手势缩放：输入协议与投递通道机制简报

> 目标平台：Minecraft NeoForge 1.21.1 桌面端（GLFW 3.4 via LWJGL 3.3.3，Java 21）。
> 范围：触屏（touchscreen）、数位板（graphics tablet）、触摸板（touchpad，含 Apple Magic Trackpad 2 走 Windows 精密触摸板协议）、旋钮/拨盘（radial controller / dial）。
> 体例：每条结论标注【确证】（官方文档 / 源码 / 官方反编译产物）或【推断】（社区实测、厂商惯例、由机制外推）；专有名词保留英文。本文只讲机制与协议语义，不含代码。

---

## 对本仓的实现建议

### 现状（确证·本仓源码）

- `GraphEditor.mouseScrolled`（`src/main/java/io/github/y15173334444/create_schematic_compute/client/editor/GraphEditor.java:4559`）：按 `sy` **符号**逐事件 ×1.12 步进，clamp 0.25–4，绕光标锚点修正 camX/camY；**物理 Ctrl 被按住时**（`glfwGetKey` 轮询）改作注释文本滚动。`PixelEditorScreen` 同构（×1.1，0.4–8）。
- 致命细节：步进只看符号、**丢弃增量幅度**。Windows 精密触摸板（PTP）两指滑动以「超高精度滚轮消息」（delta=1 量级、每条消息 ≈ 1/120 notch）高频到达，逐消息 ×1.12 会瞬间冲到 clamp 边界——这正是「连续高精度化」的直接动因。【确证·MS 兼容手册 delta 档位 + 本仓源码】

### 按通道：拿到什么输入 → 映射到什么缩放行为

| # | 通道 | 在 Screen.mouseScrolled 里实际拿到什么 | 建议映射 | 可用性 |
|---|------|--------------------------------------|----------|--------|
| 1 | 鼠标/传统滚轮 | `dy` ≈ ±1.0 / notch（GLFW 把 WM_MOUSEWHEEL 的 delta 归一为 1/120 单位后×灵敏度） | 保持手感：每 notch 一次指数步进（现行 ×1.12 即 k=ln 1.12），只把「按符号」改为「按 sign(δ)·min(\|δ\|,1)」以防合成小增量被放大 | **GLFW 直通可用** |
| 2 | PTP 两指滚动（含 Magic Trackpad 2 @Windows） | `dy` = 1/120 量级的**分数增量流**（ultra-high precision，delta=1/消息，几十至几百 Hz） | **幅度驱动连续缩放**：zoom′ = clamp(zoom·exp(k·δ), 0.25, 4)，k 取「一整段惯用滑动 ≈ 原 3–5 个 notch」标定（k≈0.2–0.4/单位 δ 起调） | **GLFW 直通可用**（δ 需按幅度消费，绝不可按符号步进） |
| 3 | PTP 捏合（pinch/zoom） | 默认**不**给原生捏合：系统默认处理后以**鼠标滚轮消息**形态到达（触屏路径官方确证为 WM_MOUSEWHEEL + MK_CONTROL；PTP 路径官方措辞为「typically … mouse wheel messages」，社区实测一致为 Ctrl+滚轮）。到 GLFW 后就是普通滚动流，MK_CONTROL 标志**丢失**（GLFW 只取 delta） | 归入通道 2 的幅度映射即可得到「捏合=连续缩放」（合成量与捏合速率相关）；**无法**在 GLFW 层区分「真滚轮」与「合成捏合」。若要分流/取捏合中心：WndProc 钩子（读 MK_CONTROL）或新 PTP API（RegisterTouchpadCapableWindow + WM_POINTER/PT_TOUCHPAD + GetPointerTouchpadInfo，文档标注 Windows 11、带预发布声明） | 直通=可用（不可分辨）；精确通道=**登记暂缓**（需 Win32 钩子，JNA 可行，见 §3） |
| 4 | 触屏双指捏合 | 不处理 WM_GESTURE 时：DefWindowProc 默认手势处理合成 **WM_MOUSEWHEEL + MK_CONTROL**（官方确证表）→ 同通道 3 | 同通道 3；若走钩子拿 WM_GESTURE/GID_ZOOM：语义为**累计两指间距**（首条 GF_BEGIN 只建基准、其后相对首条求比例），锚点=ptsLocation「缩放中心」，惯性仅 GID_PAN 内建（GID_ZOOM 无内建惯性） | 同上 |
| 5 | 触屏单指（pan 跟随） | WM_POINTER（PT_TOUCH）/ WM_TOUCH——**GLFW 3.4 完全不转发** | 拿不到，登记暂缓（钩子可拿） | 暂缓 |
| 6 | 数位板（Wacom 类） | 笔：WM_POINTER/PT_PEN（POINTER_PEN_INFO：pressure 0–1024、tiltX/Y ±90°、rotation 0–359）——GLFW 不转发；触控环/快捷键/笔侧键：**驱动层映射**为滚轮消息或键盘按键（厂商 per-app profile） | 笔压/倾角 → 连续量：暂缓（钩子可拿）；触控环/按键 → 走通道 1 / 快捷键，直通可用 | 部分可用 |
| 7 | 旋钮/拨盘（Surface Dial 类） | 未定制时：系统菜单内置 Scroll/Zoom 工具，旋转被系统合成为滚动类输入 → 通道 1/2；定制通道为 RadialController（WinRT，桌面经 IRadialControllerInterop/ConfigurationInterop），RotationChanged 给**连续角度增量**（默认 10° 才发一档，可调） | 合成滚动 → 通道 2 幅度映射；RadialController 直连 → 暂缓登记（WinRT 互操作，与 JNA 钩子同级工程量） | 合成=可用；定制=暂缓 |
| 8 | Loupedeck / MIDI 旋钮 | 厂商软件映射为键盘/HID 消息或虚拟滚轮；MIDI 旋钮走 MIDI API（winmm/Windows MIDI Services），**不是 Win32 窗口消息** | 虚拟滚轮 → 通道 1/2；MIDI → 暂缓（需 MIDI 读取层） | 部分可用 |

### 统一数值建议

- **一处映射函数、多通道喂入**：zoom′ = clamp(zoom · exp(k·δ), 0.25, 4)，δ 为各通道归一化连续增量；滚轮 notch 的 δ=1 对应 k = ln(1.12)≈0.113（保持现手感）。锚点：滚轮/合成捏合=光标；将来拿到捏合中心（GID_ZOOM 的 ptsLocation / PTP 双指质心）则换「锚点不动点」。
- **差异化手感**：刻度量（滚轮、旋钮档位）走指数步进；连续量（PTP 分数增量、捏合比例、RadialController 角度）走 exp 直驱——这与苹果协议的二分（magnify=连续增量事件流、滚轮=刻度）一致（见 §1、§4）。
- **必须防御 `discreteMouseScroll` 选项**：开启时 MC 把所有 δ 变成 ±1 再乘灵敏度（确证·反编译），连续输入退化为步进——建议在图编辑器内检测并降级为步进映射，或提示用户关闭。
- **Ctrl+滚轮冲突勘定**：现「Ctrl+滚动=注释文本滚动」用的是**物理键态**（glfwGetKey 轮询）；Windows 合成捏合的 Ctrl 是**消息级 MK_CONTROL**、物理 Ctrl 并未按下 → 现行为下捏合会落到缩放分支（正确方向），真·Ctrl+滚轮在注释框命中时走注释滚动、否则回落缩放分支。若将来钩 WndProc 能读到 MK_CONTROL，建议把「消息级 Ctrl」判为捏合/缩放、「物理 Ctrl」保留给注释滚动，避免两条通道互相抢。
- **惯性（可选）**：平台不统一（macOS 有 momentumPhase 流、Windows GID_PAN 内建惯性而 GID_ZOOM 无、PTP 新 API 要求应用自己 ReportWindowContentInertia）。若自实现：按速度指数衰减、帧率无关化，每 1/60 s 速度乘数在 0.95–0.998 区间起步调（推断·业界惯例，见 §4）；放大缩放这类「指数映射」的惯性建议只作用于 δ 的残余速度而非 zoom 本身。

### 暂缓清单（拿不到 / 需钩子，先登记）

1. 触屏单指 pan（WM_POINTER/PT_TOUCH）与触屏原生 GID_ZOOM 相位/锚点——需 WndProc 钩子（JNA 可行）。
2. PTP 捏合与真滚轮的**可分辨**通道（MK_CONTROL 或 PT_TOUCHPAD 原生双指）——需钩子或 Win11 新 PTP API（后者文档仍带预发布声明）。
3. 数位板笔压/倾角（POINTER_PEN_INFO / Wintab）——需钩子或厂商 SDK。
4. RadialController 直连（Surface Dial 连续角增量）与 MIDI 旋钮——需 WinRT 互操作 / MIDI 层。
5. macOS 触摸板捏合（magnify 事件）——GLFW 3.4 无任何手势事件出口（确证·源码），需要 AppKit 层面钩子，超出本次范围（本仓为 Windows 桌面场景）。

---

## 1. 苹果触摸板/触控协议的缩放语义

来源均为 Apple Developer（developer.apple.com）文档正文（经其文档数据端点取得，同源官方文本）→ 全部【确证·官方文档】。

### 1.1 macOS 触控板捏合（magnify）的投递模型

- **相位（phase）**：`NSEvent.phase` 描述「fluid gesture event」的流式相位，取值 began / changed / ended / cancelled / mayBegin / stationary（枚举名 `NSEvent.Phase`）。官方原文要点：手势以 began 开始、以 ended 或 cancelled 结束；事件投递给**手势开始时刻光标下的 view**。两指刚落下时触控板会先发 mayBegin（「scrolling is about to begin before the gesture has technically started」），随后才是 began/changed/cancelled；Magic Mouse 不发 mayBegin。压力事件（pressure 类型）同样是带 phase 的流式手势。【确证】
- **动量相位（momentumPhase）**：官方描述为「the momentum phase for a scroll or flick gesture」；正文：Magic Mouse 与部分触控板在 flick/滚轮后产生「a stream of scroll events that **dissipate over time**」（随时间衰减的事件流），这些事件挂在 flick 时指针所在 view 上；`momentumPhase` 用于识别这类合成动量事件。真实手指事件 phase 非 None、momentumPhase 为 None；惯性阶段反之；传统滚轮两者皆 None。【确证】（注：官方文字仅就 scroll/flick 表述；捏合惯性事件同样经 momentumPhase 标记属【推断·社区/实测】）
- **magnification 是增量不是累计量**：`NSEvent.magnification` 官方原文「The **change** in magnification that should be **added to the current scaling** of an item to achieve the new scale factor」，仅对 magnify 类型事件有效——即**每事件增量**，应用自行累乘。【确证】
- **对照：手势识别器是累计量**：`NSMagnificationGestureRecognizer.magnification` 官方原文「contains the **current magnification in effect** for the gesture. Add the value … to 1.0 to get the scale factor」——即**自手势开始的累计比例**（scale = 1 + magnification）。同一概念在两套 API 下一增量一累计，移植时最易踩坑。【确证】
- **滚动的连续/刻度二分**：`hasPreciseScrollingDeltas` 官方原文：普通滚轮给「rather coarse scroll deltas」，部分鼠标/触控板提供「much more precise delta」，该布尔决定 scrollingDeltaX/Y 的解读方式。这即是「捏合/触控板=连续量、滚轮=刻度量」的官方表述落点。【确证】

### 1.2 捏合锚点语义与惯性衰减

- **锚点**：Apple 文档**没有**给出 magnify 事件的官方锚点定义（`NSEvent.magnification` 与 `NSMagnificationGestureRecognizer` 均无 anchor/center 语义）；各触点位置需经 `touches(matching:in:)`（归一化 normalizedPosition）自取质心。业界惯例与 Windows GID_ZOOM 的官方锚点（ptsLocation=「center of the zoom」，见 §2）一致：**捏合中心/质心为不动点**。→ 【推断·由 NSTouch 数据模型与 Windows 对位语义外推；社区/示例代码惯例】
- **惯性**：官方语义只有「momentumPhase + dissipate over time」（见 1.1）；**没有**官方衰减系数。→ 系数全属【推断/惯例】（见 §4）。

### 1.3 Force Touch / Apple Pencil 的「压力/倾角 → 连续量」映射惯例

- **Force Touch 触控板**：`NSEvent.pressure` 官方定义为「a **normalized value** that indicates the degree of pressure」；每个 stage 有独立压力曲线——stage 1 事件取值 0.0–1.0，stage 2 事件**也是** 0.0–1.0，官方明言「should be retrieved for a single stage … **not be combined**」；`stage` 取值 0（压力不足/结束）、1（相当于 mouse-down）、2（更重，用于 peek/Quick Look 类即时动作），阶段可跳变（2 直接到 0）。另有 `stageTransition`（阶段间过渡连续值）与 `pressureBehavior`。【确证】惯例：**归一化无单位连续量 + 阈值分段（stage），不跨段合并**。
- **Apple Pencil（iOS/iPadOS 层）**：`UITouch.force`——「a value of 1.0 represents the force of an **average touch**（system-predetermined）」，另有 `maximumPossibleForce` 提供动态范围；笔的力沿笔轴测量，垂直分量需自行换算；初值为估计值、可能延迟更新。`altitudeAngle`——弧度，0=平行于屏幕、π/2=垂直；`azimuthAngle(in:)`——弧度，笔尾方向相对屏幕 +x 轴、顺时针增大。【确证】惯例：**力=归一化到系统标定值、倾角=弧度连续量**；官方另设示例页「Computing the perpendicular force of Apple Pencil」「Illustrating the force, altitude, and azimuth properties of touch input」。
- **macOS 数位板**（顺带，与 §2 数位板对位）：`NSEvent` 的 tablet pointing 字段——absoluteX/Y/Z、rotation、tangentialPressure、tilt（缩放倾斜值）——即 macOS 侧的倾角/旋转/侧压连续量入口。【确证·字段存在于官方文档】

---

## 2. Windows 侧投递通道：设备事件如何到达 GLFW 应用

### 2.1 两条架构路径（总纲）

- **触屏（Windows Touch）**：驱动 → OS 生成 **WM_TOUCH** 或 **WM_GESTURE** 发给应用 HWND。默认**应用收到的就是手势**（WM_GESTURE）；只有用 RegisterTouchWindow 注册过的窗口才收原始 WM_TOUCH。「Applications receive gestures by default」【确证·MS 架构总览】。手势/触摸消息是贪婪的（grab）：手势开始后全部消息归该窗口直到结束。【确证】
- **精密触摸板（PTP，Win8.1+，本仓 Magic Trackpad 2 所走路经）**：PTP 上报的是**手指位置与状态**，由 Windows 判定动作类别（mousing / gesturing / actions）。官方（2026-03 更新，带预发布声明）：「**Applications do not directly receive gesturing touchpad input by default**」；仅 Direct Manipulation / InteractionTracker 等被「开化」的 UI 技术直接消费；其余「the touchpad input is redirected to **ninput.dll** for default input handling, which **typically results in the generation of mouse wheel messages**」。【确证·MS Precision Touchpad 门户】

### 2.2 PTP 两指滚动与捏合的消息形态（核心问题）

- **两指滚动**：默认生成「**ultra-high precision scroll wheel messages**」——兼容手册给出三档 delta：ultra-high=**1**（默认）、high=**40**、standard=**120**；应用可用 manifest 的 highResolutionScrollingAware / ultraHighResolutionScrollingAware 降档。【确证·MS 兼容手册】即：PTP 滚动 = 高频 WM_MOUSEWHEEL 流，每条 delta≈1（1/120 notch）。
- **捏合在「不处理手势的窗口」里会不会被合成 Ctrl+滚轮？**
  - 触屏路径（Windows Touch 手势识别器）：**官方确证会**。官方「Legacy Support」表：Zoom 手势「triggers messages that are similar to **holding the CTRL key, and spinning the mouse wheel**」→ 生成 **WM_MOUSEWHEEL 且 wParam 带 MK_CONTROL**；Pan → WM_VSCROLL/WM_HSCROLL；Press-and-Hold → WM_RBUTTONDOWN/UP。合成的机制链也官方确证：未处理的 WM_GESTURE 若冒泡到 **DefWindowProc**，「Windows interprets WM_GESTURE messages … and then will SEND or POST appropriate messages that map to the gesture」。【确证·MS Windows Touch Gestures Overview + 架构总览】
  - PTP 路径：官方措辞是「typically results in the generation of **mouse wheel messages**」（未逐手势明示）；社区/主流应用（Electron 系、JetBrains 系、Chromium 系）一致观测到 **PTP 捏合以 Ctrl+滚轮形态出现**。→ 判定【推断·高置信（官方合成表 + 官方 PTP 措辞 + 社区实测三方一致）】。
  - **GLFW 层的后果（确证·GLFW 3.4 Win32 源码）**：GLFW 只取滚轮消息的 delta，**不读 MK_CONTROL**；因此合成捏合与真滚轮在 GLFW scroll callback 里**不可区分**，且 `glfwGetKey` 轮询也拿不到（那是消息级标志、物理 Ctrl 并未按下）。
- **PTP 原生高保真通道（新）**：RegisterTouchpadCapableWindow / RegisterTouchpadCapableThread 注册后，两指手势以 **WM_POINTER**（pointerType = **PT_TOUCHPAD**）投递，配 GetPointerTouchpadInfo 系列取扩展信息（POINTER_TOUCH_INFO，与触屏同构）。语义要点：ptPixelLocation 固定为「手势开始时的鼠标指针位置」、ptHimetricLocation 为相对触控板设备坐标；系统先做消歧，判为手势才补发合成的 DOWN 帧；喂 Interaction Context（ProcessPointerFramesInteractionContext2）可得 manipulation 输出（含惯性），himetric 输出按**手指速度**缩放、screen 输出按显示缩放因子缩放。惯性侧有 ReportWindowContentInertia 与 WM_STOPINERTIA / WM_ENDINERTIA。【确证·MS GetPointerTouchpadInfo / PTP 编程指南】文档 Requirements 标注 **Windows 11**，且整组页面带「pre-released product」声明——登记为前瞻通道。

### 2.3 触屏（touchscreen）单指/双指的消息形态

- 单指：WM_POINTER 系列（PT_TOUCH）为现代形态；WM_TOUCH 需 RegisterTouchWindow。【确证·架构总览】
- 双指捏合：默认 WM_GESTURE，GID_ZOOM 语义（见 2.4）；可用 SetGestureConfig 配置 want/block（GC_ZOOM、GC_PAN_*、GC_ROTATE…，GC_ALLGESTURES 全开/全关；全关后走原始 WM_TOUCH 注册路径）。动态配置在 WM_GESTURENOTIFY 时进行；配置对窗口终身有效。【确证·SetGestureConfig 文档】

### 2.4 WM_GESTURE / GID_ZOOM 的相位与锚点（如走钩子可得）

- GID_ZOOM 参数：ullArguments = **两指间距**（distance between the two points），ptsLocation = **缩放中心**（center of the zoom，物理屏幕坐标）。**首条 GID_ZOOM（GF_BEGIN）只建基准、不产生缩放；第二条起相对首条状态缩放** → 判定为**累计量语义**（应用按「当前间距 / 基准间距」求比例）。【确证·WM_GESTURE + GESTUREINFO 文档】
- 相位标志：GF_BEGIN / GF_INERTIA / GF_END。惯性：官方明言「**GID_PAN has built-in inertia**」（结束时 OS 继续补发 pan 消息）；GID_ZOOM **无**内建惯性（GF_INERTIA 主要服务 pan）。【确证】
- 未处理消息必须转发 DefWindowProc（否则泄漏触摸句柄）；GID_BEGIN/GID_END 必须交给 DefWindowProc（默认处理器依赖，吞掉行为未定义）。【确证】

### 2.5 旋钮/拨盘（Surface Dial、Loupedeck、MIDI 旋钮）

- **Surface Dial / Windows wheel device**：官方通道是 **RadialController**（WinRT `Windows.UI.Input`；桌面经 IRadialControllerInterop / IRadialControllerConfigurationInterop 互操作，MS 附桌面示例）。旋转事件给 **RotationDeltaInDegrees**（连续角度增量），分辨率 RotationResolutionInDegrees 可调；**默认每 10° 才投递一档**并震动一次，连续/高灵敏工具官方建议关触觉反馈、分辨率 <5°。点击≈鼠标左键语义；长按=系统菜单。【确证·MS RadialController 文档】
- 未定制时系统菜单内置工具集：**system volume、scroll、zoom in/out、undo/redo**（桌面再加亮度、媒体播放加曲目控制）——即拨盘旋转默认可被系统当「滚动/缩放」消费。旋转→聚焦窗口的具体消息形态官方未明示，桌面应用通常观察为滚轮类消息 → 【推断】。方向映射（顺时针=右/下/In）官方有表。【确证】
- **Loupedeck 类**：常见做法是设备以键盘/HID consumer 控制器出现、厂商软件把旋钮映射为滚轮量或快捷键（per-app profile）→ 【推断·厂商惯例】。
- **MIDI 旋钮**：走 MIDI API（winmm / Windows MIDI Services）或厂商虚拟 HID，**不是 Win32 窗口消息**，GLFW/MC 天然拿不到 → 【推断·协议性质】。

### 2.6 数位板（Wacom 类）在 Windows 的常见映射

- 笔（现代）：WM_POINTER / **PT_PEN**，POINTER_PEN_INFO 字段官方定义：pressure 归一化 **0–1024**、rotation **0–359**（顺时针 twist）、tiltX/tiltY **−90…+90°**；侧键/橡皮走 penFlags（PEN_FLAG 常量族）。【确证·MS POINTER_PEN_INFO】
- 笔（传统）：**Wintab** 厂商事实标准 API（独立消息/队列，不经窗口消息）→ 【推断·业界事实】。
- 触控环（touch ring）/ ExpressKey / 笔侧键的**应用内行为**：由厂商驱动按 per-app profile 映射为键盘快捷键或滚轮量（驱动侧 remap），应用通常只看到 WM_KEY*/WM_MOUSEWHEEL → 【推断·厂商驱动惯例，无单一官方文档】。

---

## 3. GLFW / LWJGL / Minecraft 1.21.1 的实际可用面

### 3.1 GLFW 3.4 scroll callback 能拿到什么

- 文档口径：scroll callback 收到「two-dimensional scroll offsets」，未标单位；实现口径（**确证·GLFW 3.4 源码**）：
  - Win32：WM_MOUSEWHEEL 的 delta **除以 WHEEL_DELTA(120)** 作为 yoffset；WM_MOUSEHWHEEL 同理（x 轴取反）。因此 offset = 「传统刻度的分数」：鼠标一格=1.0；PTP 超高精度 delta=1 → **1/120≈0.0083/条**，高频小步长——这就是「分数增量」的物理来源。
  - macOS：scrollWheel 取 scrollingDeltaX/Y；hasPreciseScrollingDeltas 时**乘 0.1** 折算为刻度类单位后投递。
- **无高分辨率滚轮特殊通道**：分数性来自「device 发非 120 倍数 delta」+ 除法归一，无 API 分辨 deltaMode。【确证·源码】
- **触摸/手势转发：不转发（预期确认）**。GLFW 3.4 的输入面只有键盘/鼠标（按钮、光标、滚轮）/joystick/gamepad；Win32 后端源码无任何 WM_GESTURE、WM_TOUCH、WM_POINTER 分支，未处理消息一律走 DefWindowProcW（这正是 §2 合成链能生效的原因）；macOS 后端无 magnify/rotate 处理（捏合根本不进 GLFW）。issue/PR 口径：触摸支持长期挂起（glfw/glfw#42「Touch input」）；社区 PR glfw/glfw#2419「Add macOS & Wayland events for trackpad zoom and rotate gestures」**仍为 OPEN**、且不含 Windows——即官方线至今无捏合事件 API。【确证·源码 + GitHub】

### 3.2 MC 的 Screen.mouseScrolled 与 dy 语义

- 签名形态（本仓编译面）：`mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY)`（1.21.1 四参，AbstractGraphScreen 覆写处可证）。【确证·本仓源码】
- **dy 是不是原始分数增量？**是「**GLFW 归一化后的刻度分数**」，不是设备原始单位。完整链（**确证·NeoForge 反编译产物 MouseHandler**，位于本机 Gradle 缓存 ng_execute 输出）：
  1. GLFW scroll callback → MouseHandler 的 onScroll（GLFW 注册目标，水平/垂直两参）。
  2. 乘 `mouseWheelSensitivity` 选项；若 `discreteMouseScroll` 选项开启，则 δ 先被**符号化为 ±1**（连续性被毁）。
  3. 有 Screen 时：**逐事件、不累积**地转投 `screen.mouseScrolled(…)`，鼠标坐标换算为 GUI-scaled 坐标；（NeoForge 另有 onScreenMouseScrollPre/Post、onMouseScroll 三个注入点。）
  4. 无 Screen 的游戏内路径才走 accumulatedScrollX/Y 累加→整数格（切物品栏用），与 GUI 无关。
- 结论：图编辑器拿到的 `sy` = GLFW 刻度分数 × 灵敏度（除非 discrete 模式），PTP 下典型每条 1/120 量级、鼠标每格 1.0——**幅度信息完好，正是连续缩放该用的量**。【确证·反编译 + GLFW 源码】

### 3.3 运行时可用面：lwjgl-windows？FFM？JNA？

- **MC 1.21.1 官方运行时不含 lwjgl-windows**（User32 绑定所在模块）。确证·Mojang 版本清单（piston-meta 1.21.1.json）所列 LWJGL 模块：lwjgl core、lwjgl-glfw、lwjgl-jemalloc、lwjgl-openal、lwjgl-opengl、lwjgl-stb、lwjgl-tinyfd、lwjgl-freetype（+natives）——无 lwjgl-windows（该模块是 LWJGL 可选 bundle）。
- **但捆绑了 JNA 5.14.0（jna + jna-platform，无平台限制 rules）**【确证·同一清单】：jna-platform 自带 Win32 封装（User32/WinUser 族：窗口子类化换 WndProc、SetGestureConfig、GetGestureInfo、钩子 API 等），即 **Win32 手势钩子 / WndProc hook 在不加新依赖的前提下可行**【推断·按 JNA 平台绑定覆盖面】。2026 新增的 RegisterTouchpadCapableWindow 等在 JNA 5.14 中大概率缺席，需自行按名称动态绑定【推断】。
- **Java 21 的 FFM 是 preview、不能用于 mod**：JEP 442「Foreign Function & Memory API (Third Preview)」在 Java 21 为 **preview feature**（需 --enable-preview 编译+运行；MC/NeoForge 运行时不开）；JEP 454 才在 Java 22 转正。→ mod 不可用 FFM，走 JNA（或自带 JNI/捆绑 lwjgl-windows）。【确证·OpenJEP 页面】

### 3.4 GLFW 对 WM_GESTURE 的处理现状

- 结论一句话：**不处理、不转发、无 API**；消息经 DefWindowProcW 回落给系统的默认手势处理器，后者按 §2.4 的 legacy 表合成（zoom→MK_CONTROL+WM_MOUSEWHEEL）。想要原生 GID_ZOOM（累计间距+缩放中心锚点）只有 WndProc 钩子一条路（JNA 可行），或等 glfw PR #2419 类事件 API 落地（现 OPEN、仅 macOS/Wayland）。【确证·源码 + 官方文档 + GitHub】

---

## 4. 业界连续缩放的数值惯例

- **指数映射（乘法）优于线性（加法）**：缩放是对数感知量，通行做法 zoom′ = zoom · exp(k·δ)（或等价 2^(c·δ)）。可引证实现：d3-zoom 的滚轮处理即「按 delta 换算指数，scale 乘 2 的幂」（其默认换算含 deltaMode 与 Ctrl 加速系数）——典型「连续量直驱=乘法」范例。【确证·d3-zoom 源码；「应乘法」的普适性=惯例/推断】
- **每 notch ≈10% 手感参数**：×1.10–1.15 / notch 是图形编辑器常见手感（本仓现值 1.12；Blender/Krita/浏览器缩放梯级也在 ~10–15%/步量级）。→ 【推断·惯例】。映射到 k：k_notch = ln(1.12) ≈ 0.113。
- **惯性衰减系数**：无跨平台官方系数；可引证的官方锚点只有语义——Apple「dissipate over time」的 momentumPhase 流、Windows GID_PAN 内建惯性、PTP 新 API 的 ReportWindowContentInertia 协作模型（应用报告自己的惯性进行中，供系统在新输入到来时打断）。数值惯例：帧率无关的指数衰减，每 1/60 s 速度乘数 0.95（快停）–0.998（长滑），时间常数 ~0.1–0.5 s；UIScrollView.DecelerationRate 提供 normal/fast 两档常量（SDK 头的原始数值 0.998/0.99 属社区/SDK 镜像口径）。【推断/惯例 + 确证·Apple 常量名与 MS 惯性协作语义】
- **捏合直驱 vs 滚轮步进的差异化**：协议层本身已二分——Apple magnify=每事件增量（连续、带 phase/momentumPhase）、滚轮=刻度（hasPreciseScrollingDeltas=false 时 coarse）；Windows 侧 GID_ZOOM=累计间距连续量、legacy 滚轮=刻度消息（PTP 只是把刻度细化到 delta=1）。惯例：**连续量按 exp 直驱（可含 phase 门控与惯性）、刻度量按 exp 步进（每 notch 一档）**；两者共用同一映射函数、只换 δ 的标定与锚点来源。【确证·Apple/MS 语义 + 惯例】

---

## 来源

**Apple（官方）**
- NSEvent（magnification / phase / momentumPhase / hasPreciseScrollingDeltas / pressure / stage / tilt 等）: https://developer.apple.com/documentation/appkit/nsevent
- NSEvent.magnification: https://developer.apple.com/documentation/appkit/nsevent/magnification
- NSEvent.phase / NSEvent.Phase: https://developer.apple.com/documentation/appkit/nsevent/phase-swift.property · https://developer.apple.com/documentation/appkit/nsevent/phase-swift.struct
- NSEvent.momentumPhase: https://developer.apple.com/documentation/appkit/nsevent/momentumphase
- NSEvent.hasPreciseScrollingDeltas: https://developer.apple.com/documentation/appkit/nsevent/hasprecisescrollingdeltas
- NSEvent.pressure / stage / stageTransition: https://developer.apple.com/documentation/appkit/nsevent/pressure · …/stage · …/stagetransition
- NSMagnificationGestureRecognizer.magnification: https://developer.apple.com/documentation/appkit/nsmagnificationgesturerecognizer/magnification
- UITouch.force / altitudeAngle / azimuthAngle(in:) / maximumPossibleForce: https://developer.apple.com/documentation/uikit/uitouch/force · …/altitudeangle · …/azimuthangle(in:) · …/maximumpossibleforce
- Computing the perpendicular force of Apple Pencil: https://developer.apple.com/documentation/uikit/computing-the-perpendicular-force-of-apple-pencil
- Illustrating the force, altitude, and azimuth properties of touch input: https://developer.apple.com/documentation/uikit/illustrating-the-force-altitude-and-azimuth-properties-of-touch-input
- UIScrollView.DecelerationRate: https://developer.apple.com/documentation/uikit/uiscrollview/decelerationrate-swift.struct
- WWDC 2015 Session 232「Designing for Force Touch」: https://developer.apple.com/videos/play/wwdc2015/232/

**Microsoft（官方）**
- Windows Touch Gestures Overview（legacy 合成表：Zoom→WM_MOUSEWHEEL+MK_CONTROL）: https://learn.microsoft.com/en-us/windows/win32/wintouch/windows-touch-gestures-overview
- Windows Touch 架构总览（WM_GESTURE 默认投递 / DefWindowProc 合成链）: https://learn.microsoft.com/en-us/windows/win32/wintouch/architectural-overview
- WM_GESTURE: https://learn.microsoft.com/en-us/windows/win32/wintouch/wm-gesture
- GESTUREINFO: https://learn.microsoft.com/en-us/windows/win32/api/winuser/ns-winuser-gestureinfo
- SetGestureConfig: https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-setgestureconfig
- Precision Touchpad Input 门户（ninput.dll 默认处理 / RegisterTouchpadCapable* / WM_POINTER）: https://learn.microsoft.com/en-us/windows/win32/input-precisiontouchpad/precision-touchpad-portal
- Precision Touchpad Programming Guide: https://learn.microsoft.com/en-us/windows/win32/input-precisiontouchpad/precision-touchpad-guide
- GetPointerTouchpadInfo（PT_TOUCHPAD 语义、惯性协作、Win11 要求）: https://learn.microsoft.com/en-us/windows/win32/input-precisiontouchpad/getpointertouchpadinfo
- RegisterTouchpadCapable: https://learn.microsoft.com/en-us/windows/win32/input-precisiontouchpad/registertouchpadcapable
- 兼容手册：Windows precision touchpad devices（ultra-high/high/standard delta=1/40/120 + manifest 降档）: https://learn.microsoft.com/en-us/windows/compatibility/precision-touchpad-devices
- POINTER_PEN_INFO（pressure 0–1024 / tilt ±90 / rotation 0–359）: https://learn.microsoft.com/en-us/windows/win32/api/winuser/ns-winuser-pointer_pen_info
- Surface Dial interactions / RadialController walkthrough（RotationDeltaInDegrees、默认内置 Scroll/Zoom 工具、桌面 interop）: https://learn.microsoft.com/en-us/windows/uwp/ui-input/windows-wheel-interactions · https://learn.microsoft.com/en-us/windows/uwp/ui-input/radialcontroller-walkthrough

**GLFW / LWJGL / Minecraft / Java**
- GLFW 3.4 Win32 后端源码（WM_MOUSEWHEEL ÷ WHEEL_DELTA、无 WM_GESTURE/WM_TOUCH/WM_POINTER 分支、DefWindowProcW 兜底）: https://github.com/glfw/glfw/blob/3.4/src/win32_window.c
- GLFW 3.4 Cocoa 后端源码（scrollingDeltaX/Y、precise×0.1、无 magnify）: https://github.com/glfw/glfw/blob/3.4/src/cocoa_window.m
- GLFW 输入指南: https://www.glfw.org/docs/latest/input_guide.html
- glfw/glfw#42（Touch input）: https://github.com/glfw/glfw/issues/42 · glfw/glfw#2419（trackpad zoom/rotate，OPEN）: https://github.com/glfw/glfw/pull/2419
- Mojang 1.21.1 版本清单（LWJGL 3.3.3 模块清单、JNA 5.14.0、Java 21）: https://piston-meta.mojang.com/v1/packages/9aa86ea608c3ec777dcdc09e6c5a06c85b53599e/1.21.1.json
- mappings.dev MouseHandler（onScroll/累积字段索引）: https://mappings.dev/1.21.1/net/minecraft/client/MouseHandler.html
- NeoForge 反编译 MouseHandler（onScroll→Screen.mouseScrolled 链、discreteMouseScroll、mouseWheelSensitivity）: 本机 `~/.gradle/caches/ng_execute/**/output/net/minecraft/client/MouseHandler.java`
- JEP 442（FFM Third Preview，Java 21）: https://openjdk.org/jeps/442 · JEP 454（Java 22 转正）: https://openjdk.org/jeps/454

**业界惯例**
- d3-zoom（指数映射滚轮缩放）: https://github.com/d3/d3-zoom（src/zoom.js：k·2^f(delta)，含 deltaMode 与 Ctrl 系数）
- 惯性系数、每 notch 手感参数、Wacom/Loupedeck/MIDI 映射：无单一官方来源，按上文标注为【推断/惯例】。
