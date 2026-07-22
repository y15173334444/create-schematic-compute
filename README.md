# Create: Schematic Compute

<p align="center">
  <b>🎮 7 Programmable Blocks · 84 Node Types · Multiplayer Collaboration · Debug Tools</b><br>
  <b>七种可编程方块 · 84种节点 · 多人实时协作 · 调试工具链</b><br>
  <i>Drag, connect, and build logic — just like Unreal Engine Blueprints!</i><br>
  <i>拖拽连接，构建逻辑 — 像虚幻引擎蓝图一样直观！</i><br>
  <i>Created by <b>StarryNight_Luo</b> (y15173334444)</i>
</p>

<p align="center">
  <a href="https://github.com/y15173334444/create-schematic-compute"><img src="https://img.shields.io/badge/GitHub-y15173334444/create--schematic--compute-blue?style=flat-square&logo=github" alt="GitHub"></a>
  <a href="https://github.com/y15173334444/create-schematic-compute/blob/main/LICENSE"><img src="https://img.shields.io/badge/License-MIT-green?style=flat-square" alt="License"></a>
  <a href="https://github.com/y15173334444/create-schematic-compute/releases"><img src="https://img.shields.io/badge/Version-1.2.4-blue?style=flat-square" alt="Version"></a>
  <a href="https://neoforged.net/"><img src="https://img.shields.io/badge/NeoForge-21.1.233-orange?style=flat-square" alt="NeoForge"></a>
  <a href="https://modrinth.com/mod/create"><img src="https://img.shields.io/badge/Create-6.0.10-brightgreen?style=flat-square" alt="Create"></a>
  <a href="https://www.minecraft.net/"><img src="https://img.shields.io/badge/Minecraft-1.21.1-8B4513?style=flat-square" alt="MC"></a>
</p>

---

## 📖 Overview / 简介

**🇬🇧** Create: Schematic Compute is a **Create mod addon** that introduces **7 programmable blocks + 1 portable terminal** with a **visual node-based programming system**. Instead of writing complex redstone circuits, simply drag and connect nodes to build logic — just like Unreal Engine Blueprints or Blender Geometry Nodes. Each computer runs at **20Hz (every game tick)** for real-time control. **All 7 blocks support real-time multiplayer collaborative editing** with live cursor tracking and node lock protection.

**🇨🇳** **机械动力：蓝图计算机** 是一个机械动力附属模组，添加了**七种可编程方块和一个便携终端**，采用**可视化节点图编程系统**。无需搭建复杂红石电路，只需拖拽连接节点即可构建逻辑——就像虚幻引擎的蓝图系统或 Blender 的几何节点一样直观。每台设备拥有独立的节点图，以 **20Hz（每游戏刻）** 的频率运行，适合实时控制应用。**全部 7 种方块支持多人实时协作编辑**，带实时光标追踪和节点锁定保护。

---

## 🖥️ Blocks / 方块

### 🖥️ Holographic Monitor / 全息显示器
**3D floating display / 3D 悬浮显示方块**

| Feature / 功能 | Description / 说明 |
|----------------|-------------------|
| 🖼️ Display Nodes / 显示节点 | TEXT, DATA, IMAGE, IMAGE_SEQUENCE / 文本、数值、图片、动画 |
| 🎨 16×16 Pixel Editor / 16×16像素编辑器 | Multi-frame animation + undo/redo / 多帧动画+撤销重做 |
| 📋 Layer Panel / 图层面板 | Drag-drop reorder + 24×24 thumbnails / 拖拽排序+缩略图预览 |
| 🎯 3D Positioning / 3D定位 | X/Y/Z + Roll/Pitch/Yaw freely adjustable / 自由调整位置和旋转 |
| 📡 Signal-Driven / 信号驱动 | IMAGE position/rotation via input signals / 通过输入信号驱位置旋转 |
| 📡 Redstone Input / 红石输入 | Read Redstone Link signals / 从红石链接网络读取信号 |

---

### 🖥️ Blueprint Computer / 蓝图计算机
**Redstone Link controller / 红石链接控制器**

| Feature / 功能 | Description / 说明 |
|----------------|-------------------|
| 📡 Redstone I/O / 红石I/O | Read/Write Redstone Link network / 读写红石链接网络 |
| 🔗 Private Signal / 私有信号 | Named channel cross-computer communication / 命名通道跨计算机通信 |
| 🚌 Bus System / 总线系统 | BUS_IN/BUS_OUT multi-band data sharing / 多频段数据共享 |
| 📦 Encapsulation I/O / 封装导入导出 | File browser import/export .nbt files / 文件浏览器导入导出 |

---

### ⚡ Speed Proxy / 转速代理控制器
**Speed Controller direct control / 转速直控**

| Feature / 功能 | Description / 说明 |
|----------------|-------------------|
| 🔄 Speed Control / 转速控制 | Set adjacent Speed Controller RPM (-256~256) / 设置相邻转速控制器RPM |
| 🔗 Private Signal / 私有信号输入 | Named channel cross-computer coordination / 命名通道跨计算机联动 |

---

### 🔌 Program Computer / 编程计算机
**Sequential logic / 时序逻辑专用机**

| Feature / 功能 | Description / 说明 |
|----------------|-------------------|
| 📡 Redstone I/O / 红石I/O | Redstone Link network communication / 红石链接网络通信 |
| 🔗 Private Signal / 私有信号 | PRIVATE_IN/PRIVATE_OUT named channel I/O / 命名通道I/O |
| ⏱️ Sequential Nodes / 时序节点 | Delay/Latch/T Flip-Flop/Gate/Pulse Extend/Loop/Fuse/Accumulator/Integrator / 延时/锁存器/T触发器/闸门/脉冲延长/循环/保险/累计器/连续积分器 |

---

### 🪑 Control Seat / 控制座椅
**Sit-able controller / 可乘坐控制器**

| Feature / 功能 | Description / 说明 |
|----------------|-------------------|
| ⌨️ 58 Key Bindings / 58键绑定 | Click-to-bind UI / 点击绑定 |
| 🖱️ Dual Mode / 双模式 | Joystick (mouse delta) / View Angle (rotation difference) / 摇杆/视角差 |
| 🎮 Gamepad / 手柄 | Dual stick + 15 buttons + analog triggers LT/RT / 双摇杆+15键+模拟扳机 |
| 🔄 Sable Compatible / Sable兼容 | Entity yaw sync with sub-level rotation / 实体yaw自动追踪子世界 |
| 🚪 Controls / 操作 | Right-click sit / `Shift`+Right-click editor / `~` dismount / `TAB` mode / `ESC` release |

---

### 📐 Attitude Sensor / 姿态传感器
**Physics structure orientation / 物理结构姿态读取**

| Feature / 功能 | Description / 说明 |
|----------------|-------------------|
| 📐 ATTITUDE / 姿态 | Sub-level pitch and roll / 子世界俯仰和横滚 |
| 🧭 FORWARD / 前方朝向 | World-space forward yaw/pitch / 结构世界空间朝向 |
| ⚡ ACCELERATION / 加速度 | Structure-local X/Y/Z acceleration / 结构本地加速度 |
| 🚀 VELOCITY / 速度 | Structure-local velocity ×2 m/s / 结构本地速度 |
| 🔄 POSE_CONVERT / 姿态换算 | Coordinate conversion / 坐标系转换 |

---

### 📡 3D Holographic Radar / 3D全息显示雷达
**Real-time scanner / 实时实体扫描器**

| Feature / 功能 | Description / 说明 |
|----------------|-------------------|
| 📡 Scan Range / 扫描范围 | 1-128 blocks configurable / 1-128格可配置 |
| 🎯 Target Lock / 目标锁定 | Manual right-click + auto closest / 手动右键锁定+自动最近 |
| 🖥️ Display Style / 显示风格 | Classic XYZ axes / Holographic (white cube + blue plane) / 经典/全息 |
| 📊 TARGET_OUT | Output X/Y/Z/entity ID/distance / 输出坐标/实体ID/距离 |
| 🔍 Filters / 过滤 | Show/hide players, mobs, Sable structures / 独立显示玩家/生物/Sable |

---

### 📱 Portable Terminal / 便携终端
**Handheld remote editor / 手持远程编辑器**

| Feature / 功能 | Description / 说明 |
|----------------|-------------------|
| 📡 Device Scan / 设备扫描 | Scan 1-256 blocks for programmable blocks / 扫描1-256格可编程方块 |
| ✏️ One-Click Edit / 一键编辑 | Open native GUI instantly / 即时打开原生GUI |
| 📦 All 7 Blocks / 全7方块 | Monitor, Blueprint, Program, Radar, Seat, Sensor, SpeedProxy |
| 🔄 Sable Compatible / Sable兼容 | Sub-level scanning with rotation correction / 子世界扫描+旋转修正 |

---

## 👥 Multiplayer Collaboration / 多人协作（v1.2.4+）

Real-time collaborative graph editing for all 7 block types. Multiple players can edit the same graph simultaneously.
全部 7 种方块支持多人实时协作编辑同一节点图。

| Feature / 功能 | Description / 说明 |
|----------------|-------------------|
| 🖱️ Live Cursor Tracking / 实时光标 | Colored crosshairs with player names / 彩色十字准星+玩家名 |
| 📦 Remote Drag / 远程拖拽 | Smooth animated node movement / 平滑动画节点移动 |
| 🔗 Wire Preview / 连线预览 | Live bezier curve while dragging / 实时贝塞尔曲线预览 |
| 👤 Player List / 玩家列表 | Right-side vertical list, host highlighted / 右侧竖向列表，房主高亮 |
| 🔒 Node Lock / 节点锁定 | IMAGE nodes protected during pixel edit / 像素编辑时锁定IMAGE节点 |
| 🚪 Auto-Close / 自动关闭 | UI closes when block destroyed / 方块破坏时自动关闭 |
| ⚡ Join/Leave / 加入离开 | Appear immediately on open, disappear on close / 打开即现，关闭即消 |

---

## 📝 Formula Script Node / 公式脚本节点

Multi-line script editor (v1.2.0+) — assignments, named outputs, comments, line continuation.
多行脚本编辑器 — 支持赋值、命名输出、注释、续行。

```
-- Ballistic calculation / 弹道计算
dx = X1 - X0
dz = Z1 - Z0
w = sqrt(dx*dx + dz*dz)
secTheta = 1 / cos(THETA)
y = (99*secTheta/(20*N)+tan(THETA))*w + 99*ln(1-2*(w*secTheta-K)/(199*N))/(20*ln(100/99)) - 99*K/(20*N) + 2
@output y
@output w
@output secTheta
```
→ **7 inputs + 3 outputs / 7输入 + 3输出**

**18 Functions / 18个数学函数：** `sin` `cos` `tan` `asin` `acos` `atan2` `sinh` `cosh` `sqrt` `ln` `log` `exp` `sec` `csc` `cot` `abs`

---

## 🚌 BUS System / BUS 总线系统

Global named-channel communication across computers. Like publish-subscribe message bus.
全局命名通道跨计算机通信系统，类似发布-订阅消息总线。

| Feature / 功能 | Description / 说明 |
|----------------|-------------------|
| 🚌 BUS_OUT / 总线输出 | Write values to named channel with bands / 写入命名通道+频段 |
| 🚌 BUS_IN / 总线输入 | Read band values from channel / 从通道读取频段值 |
| 📋 Band System / 频段 | Named sub-fields per channel / 每通道命名字段 |
| 🔢 Ref Counting / 引用计数 | Auto-cleanup when no BUS_OUT references / 无引用时自动清理 |
| ⚠️ Conflict Detection / 冲突检测 | Reject duplicate channel names / 拒绝重名通道 |

---

## 🧩 Node Reference / 节点参考（84 种）

<details>
<summary><b>📦 Values / 数值</b></summary>

| Node / 节点 | Description / 说明 |
|-------------|-------------------|
| Constant / 常量 | Outputs constant value / 输出常量值 |
| Redstone Input / 红石输入 | Reads from Redstone Link / 从红石链接网络读取 |
| Private Signal Input / 私有信号输入 | Reads float from named channel / 从命名通道读取浮点数 |
| Bus Input / 总线输入 | Reads bus channel bands / 从总线通道读取频段值 |

</details>

<details>
<summary><b>🔢 Basic Math / 基础运算</b></summary>

| Node / 节点 | Description / 说明 |
|-------------|-------------------|
| Add / 加法 | A + B |
| Subtract / 减法 | A - B |
| Multiply / 乘法 | A × B |
| Divide / 除法 | A ÷ B (0 if B=0) |
| Modulo / 模运算 | A % B |
| Power / 次幂 | A ^ B |
| Root / 次方根 | B-th root of A |
| Absolute Value / 绝对值 | \|input\| |
| Ceil / 向上取整 | Round up |
| Floor / 向下取整 | Round down |

</details>

<details>
<summary><b>📐 Advanced Math / 高级运算</b></summary>

| Node / 节点 | Description / 说明 |
|-------------|-------------------|
| Formula / 公式 | Multi-line script editor / 多行脚本编辑器 |
| Round / 保留N位小数 | Round to N decimals / 保留N位小数 |
| Comparison Router / 比较路由 | \|A-B\| smart routing / 智能信号分流 |
| Pose Convert / 姿态换算 | Pitch/Yaw/Roll coordinate conversion / 姿态角转换 |
| Split / 分割 | Positive/negative signal split / 正负信号分离 |

**Trig / 三角函数（度）：** Sine · Cosine · Tangent · Arc Sine · Arc Cosine · Arc Tangent 2 · Hyperbolic Sine · Hyperbolic Cosine

**Other / 其他：** Square Root · Natural Log · Base-10 Log · Exponential · Secant · Cosecant · Cotangent · Angle Unwrap · Direction (3-in 3-out)

</details>

<details>
<summary><b>🧠 Logic / 逻辑</b></summary>

| Node / 节点 | Description / 说明 |
|-------------|-------------------|
| Greater Than / 大于 | A > B |
| Less Than / 小于 | A < B |
| Greater or Equal / 大于等于 | A ≥ B |
| Less or Equal / 小于等于 | A ≤ B |
| Equals / 等于 | A = B |
| OR Gate / 或门 | A > 0.5 or B > 0.5 |
| Bool / 布尔 | Boolean with invert toggle / 布尔（可反转） |
| Gate / 闸门 | Signal gate with Set/Reset/Toggle / 信号门 |

</details>

<details>
<summary><b>🎛️ Control / 控制</b></summary>

| Node / 节点 | Description / 说明 |
|-------------|-------------------|
| PID Controller / PID控制器 | SP/PV PID (0~16), anti-windup / PID算法，抗积分饱和 |
| Power PID / 动力PID | PID with base power input / 带基础动力PID |
| Clamp / 限制 | Min/Max clamp / 限幅 |
| Map Range / 映射范围 | Range mapping / 范围映射 |

</details>

<details>
<summary><b>📤 Output / 输出</b></summary>

| Node / 节点 | Description / 说明 |
|-------------|-------------------|
| Redstone Output / 红石输出 | Write to Redstone Link (0~15) / 写入红石链接 |
| Private Signal Output / 私有信号输出 | Write to named channel / 写入命名通道 |
| Bus Output / 总线输出 | Writes to bus channel / 写入总线通道 |
| Speed Control / 转速控制 | Speed Controller RPM (-256~256) / 转速控制 |

</details>

<details>
<summary><b>⏱️ Sequential / 时序</b> (Program Computer only / 仅编程计算机)</summary>

| Node / 节点 | Description / 说明 |
|-------------|-------------------|
| Delay / 延时 | Delay N ticks / 延时N tick |
| Latch / 锁存器 | Set/Reset latch, configurable default / 可配置默认状态 |
| T Flip-Flop / T触发器 | Toggle flip-flop, configurable default / 可配置默认状态 |
| Pulse Extender / 脉冲延长 | Extend input pulse N ticks / 脉冲延长N tick |
| Loop / 循环 | Fire pulse every interval, repeat count times / 循环脉冲 |
| Safety Timer / 保险 | Trigger → 2-tick pulse → cooldown / 触发→脉冲→冷却 |
| Accumulator / 累计器 | Rising-edge step counter / 累计器 |
| Continuous Integrator / 连续积分器 | Continuous integration, configurable limit / 连续积分器 |

</details>

<details>
<summary><b>🎮 Input Controls / 操作输入</b> (Control Seat only / 仅控制座椅)</summary>

| Node / 节点 | Description / 说明 |
|-------------|-------------------|
| Keyboard Key / 键盘按键 | 58 bindable keys / 58键绑定 |
| Mouse Joystick / 鼠标摇杆 | Mouse delta -1~1 / 鼠标增量 |
| View Angle / 视角差 | View angle delta / 视角差 |
| Mouse Button / 鼠标按键 | Left/Right mouse buttons / 鼠标按键 |
| Gamepad Joystick / 手柄摇杆 | Dual stick LX/LY/RX/RY / 双摇杆 |
| Gamepad Button / 手柄按键 | 15 buttons / 15按键 |
| Gamepad Trigger / 手柄扳机 | Analog triggers LT/RT (0~1) / 模拟扳机 |

</details>

<details>
<summary><b>📡 Sensors / 传感器</b> (Attitude Sensor + Control Seat / 姿态传感器+控制座椅)</summary>

| Node / 节点 | Description / 说明 |
|-------------|-------------------|
| World View / 世界视角 | Player absolute world view direction / 玩家绝对视角 |
| Attitude / 姿态 | Sub-level pitch and roll / 子世界姿态 |
| Forward / 前方朝向 | World-space forward yaw/pitch / 结构朝向 |
| Acceleration / 加速度 | Structure-local X/Y/Z acceleration / 结构本地加速度 |
| Velocity / 速度 | Structure-local velocity ×2 m/s / 结构本地速度 |
| World Position / 世界坐标 | World position with offset / 世界坐标（可偏移） |
| Target Output / 目标输出 | Radar target X/Y/Z/entityId/distance / 雷达目标 |

</details>

<details>
<summary><b>🖼️ Display / 显示</b> (Monitor only / 仅全息显示器)</summary>

| Node / 节点 | Description / 说明 |
|-------------|-------------------|
| Text / 文本 | Text display with color / 文字显示 |
| Data Display / 数值显示 | Float value display / 数值显示 |
| Image / 图像 | 16×16 pixel image, signal-driven position / 像素图片 |
| Image Sequence / 图像序列 | Multi-frame animation, signal-driven frame / 多帧动画 |

</details>

<details>
<summary><b>📦 Structure / 结构</b></summary>

| Node / 节点 | Description / 说明 |
|-------------|-------------------|
| Encapsulation / 封装 | Nest sub-graphs inside a node / 嵌套子图 |
| ENCAP_INPUT / 封装输入 | External input pin / 外部输入引脚 |
| ENCAP_OUTPUT / 封装输出 | External output pin / 外部输出引脚 |

</details>

<details>
<summary><b>📝 Annotation / 注释</b></summary>

| Node / 节点 | Description / 说明 |
|-------------|-------------------|
| Comment / 便利贴 | Sticky-note annotation, resizable (80~8000×40~6000), scrollable, 3-color customizable. Drag header to move, parent-move contains nodes. Pure visual — skipped during evaluation. Press **C** with nodes selected to wrap. / 可调大小/滚动/三色自定义。拖拽顶部移动，父级移动携带内部节点。纯视觉辅助。选中节点按 **C** 包裹。 |

</details>

<details>
<summary><b>🔧 Debug / 调试</b> (All blocks / 全部方块)</summary>

| Node / 节点 | Description / 说明 |
|-------------|-------------------|
| Signal Generator / 信号发生器 | Test signal source with XY curve preview, manual control-point curve or custom f(x) formula, frequency-generate or input-driven output modes / 测试信号源，XY曲线预览，手动控制点曲线或自定义f(x)公式，频率发生/指定模式输出 |
| Signal Probe / 信号探针 | Real-time signal monitor with 100-tick trend chart, auto-scale Y axis with outlier clipping, freeze/clear / 实时信号监视，100 tick趋势图，Y轴自动缩放+离群截断，冻结/清除 |

**Signal Generator Modes / 信号发生器模式：**

| Mode / 模式 | Description / 说明 |
|-------------|-------------------|
| 🎯 Manual Curve / 手动曲线 | Drag control points on XY chart. Double-click to add, right-click to delete, drag X/Y freely (X clamped between neighbors, server-sorted). Points synced via multiplayer collaboration. / XY图上拖拽控制点。双击添加，右键删除，XY双向自由拖拽（X被相邻点夹持，服务端排序）。多人协作同步。 |
| 📐 Custom f(x) / 自定义公式 | Enter formula expression using variable `x` (0~1). Supports all math functions (trig in degrees), auto full-width paren conversion. Speed/amplitude disabled — formula controls everything. / 输入公式表达式，变量 `x`（0~1）。支持全部数学函数（三角函数用度），自动全角括号转换。speed/amplitude 禁用。 |
| 🔄 Frequency Generate / 频率发生 | X auto-advances 0→1 cyclically. Speed control (manual mode only). Current X position shown as sky-blue marker on chart. / X自动0→1循环推进。speed控制速度（仅手动模式）。天蓝色标记线显示当前X位置。 |
| 🎯 Input-Driven / 指定模式 | X set by dragging sky-blue marker line on chart. No EditBox — pure drag interaction. / 拖拽天蓝色标记线设置X值。无输入框，纯拖拽交互。 |

**Signal Probe Features / 信号探针功能：**
- 100-tick ring buffer trend chart with auto-scale Y axis
- Outlier clipping (±5) prevents spikes from flattening the waveform
- Right-click: Freeze/Unfreeze, Clear History
- Shows "---" when blueprint not running

</details>

---

## 🎮 Controls / 操作指南

| Action / 操作 | Input / 按键 |
|---------------|-------------|
| Add node menu / 添加节点 | Right-click empty / 右键空白 |
| Edit params / 编辑参数 | Left-click → ▶ / 左键→▶ |
| Connect / 连接 | Drag output pin → input pin / 拖拽输出→输入 |
| Delete node / 删除节点 | Hover + `X` / 悬停+`X` |
| Delete connection / 删除连线 | `TAB` + Left-click / `TAB`+左键点击 |
| Delete selected / 删除选中 | `Delete` / `Backspace` |
| Box select / 框选 | `TAB` + drag / `TAB`+拖拽 |
| Duplicate / 复制 | `Ctrl + D` |
| Undo / 撤回 | `Ctrl + Z` |
| Redo / 重做 | `Ctrl + Y` |
| Wrap in Comment / 注释包裹 | Select nodes + `C` / 选中节点+`C` |
| Edit Comment text / 编辑注释文本 | Double-click comment body / 双击注释节点 |
| Resize Comment / 调整注释大小 | Drag bottom-right corner / 拖动右下角 |
| Scroll Comment / 滚动注释 | `Ctrl` + Scroll / `Ctrl`+滚轮 |
| Zoom / 缩放 | Scroll wheel / 滚轮 |
| Pan / 平移 | Right-click drag / 右键拖拽 |
| Open editor (most blocks) / 打开编辑器 | Right-click / 右键 |
| **Control Seat — Sit / 乘坐** | Right-click (empty hand) / 右键（空手） |
| **Control Seat/Radar — Editor / 编辑器** | `Shift` + Right-click / `Shift`+右键 |
| **Control Seat — Dismount / 下马** | `~` |
| **Control Seat — Switch mode / 切换模式** | `TAB` |
| **Control Seat — Release mouse / 释放鼠标** | `ESC` |
| **Signal Gen — Add control point / 添加控制点** | Double-click XY chart / 双击XY图 |
| **Signal Gen — Delete control point / 删除控制点** | Right-click control point / 右键控制点 |
| **Signal Gen — Drag control point / 拖拽控制点** | Left-drag (X clamped, Y free) / 左键拖拽 |
| **Signal Gen — Drag X marker / 拖拽X标记** | Left-drag sky-blue line (input mode) / 左键拖拽天蓝色线 |
| **Signal Gen — Switch mode / 切换模式** | Click toggle button (confirm with second click) / 点击切换按钮（二次点击确认） |
| **Probe — Freeze/Unfreeze / 冻结解冻** | Double-click probe node / 双击探针节点 |
| **Probe — Clear/Clear History / 清除历史** | Right-click probe node / 右键探针节点 |
| **Bookmark — Open panel / 打开书签面板** | Click ★ bottom-right / 点击右下角★ |
| **Bookmark — Add / 添加书签** | `[+]` in panel or `Ctrl+M` / 面板内`[+]`或`Ctrl+M` |
| **Bookmark — Rename / 重命名** | Click ✎ on bookmark row / 点击书签行✎ |
| **Bookmark — Delete / 删除** | Click × on bookmark row / 点击书签行× |
| **Bookmark — Jump / 跳转** | Click → or name / 点击→或名称 |
| **Bookmark — Reorder / 拖拽排序** | Drag name area to new position / 拖拽名称区域到新位置 |
| **Bookmark — Reset view / 重置视角** | `[↺]` in panel or `Home` key / 面板`[↺]`或`Home`键 |

---

## 🔄 Sable Physics Integration / Sable 物理集成

Deep integration with Sable physics engine for rotating structures. / 与Sable物理引擎深度集成，支持旋转结构。

| Block / 方块 | Feature / 功能 |
|-------------|---------------|
| Control Seat / 控制座椅 | Entity yaw tracks sub-level rotation / 实体yaw追踪子世界 |
| Attitude Sensor / 姿态传感器 | Read `logicalPose()` quaternion / 读取姿态四元数 |

> **Thread safe / 线程安全**: Shared fields `volatile`. **Without Sable / 无Sable**: Control Seat fully functional, Sensor outputs 0 / 控制座椅完全可用，传感器输出0

---

## 💾 Schematic Support / 蓝图兼容

All 7 blocks support **Create's Schematicannon** — graphs and state fully preserved. / 全部七种方块支持**蓝图大炮**，图与状态完整保留。

Uses Create's `IMergeableBE` + `SafeNbtWriter` / 采用 Create 官方接口

---

## 📦 Recipes / 合成配方

| Block / 方块 | Materials / 材料 |
|-------------|-----------------|
| 🖥️ Monitor / 全息显示器 | Redstone Link×2 + Precision Mechanism + Glass Pane×2 + Brass Casing + Glowstone Dust×2 |
| 🖥️ Blueprint / 蓝图计算机 | Redstone Link×2 + Precision Mechanism + Glass Pane×2 + Repeater + Comparator + Brass Casing×2 |
| ⚡ Speed Proxy / 转速代理 | Brass Ingot×4 + Cogwheel + Glass Pane×2 + Comparator + Andesite Casing |
| 🔌 Program / 编程计算机 | Andesite Casing×4 + Repeater + Glass Pane×2 + Comparator + Andesite Alloy |
| 🪑 Control Seat / 控制座椅 | Heavy Weighted Pressure Plate + Iron Ingot×2 + Brass Casing + Redstone + Redstone Link×4 |
| 📐 Attitude Sensor / 姿态传感器 | Iron Ingot×6 + Repeater + Comparator + Brass Casing×2 |
| 📡 Radar / 雷达 | Monitor×2 + Iron Ingot×4 + Brass Casing + Redstone Block×2 |
| 📱 Portable Terminal / 便携终端 | Redstone Link×4 + Blueprint Computer + Glass Pane×4 |

---

## ⚙️ Block Properties / 方块属性

| Property / 属性 | Value / 值 |
|-----------------|-----------|
| Hardness / 硬度 | 1.0 (hand breakable / 空手可破坏) |
| Hand break / 空手破坏 | Drops without NBT / 掉落无NBT |
| Wrench right-click / 扳手右键 | Rotate FACING / 旋转方向 |
| Wrench Shift+right-click / 扳手Shift+右键 | Pick up with full NBT / 收回保留NBT |

---

## 🔧 Technical Highlights / 技术亮点

| Feature / 特性 | Description / 说明 |
|----------------|-------------------|
| 👥 Multiplayer Collaboration / 多人协作 | Real-time editing, cursor tracking, node lock / 实时编辑、光标追踪、节点锁 |
| ⚡ Server-Authoritative Eval / 服务端权威评估 | Client receives `EvalSnapshot` — no local evaluator / 客户端接收快照，无本地评估器 |
| ⚡ Topological Sort Eval / 拓扑排序求值 | O(1) input query cache / O(1) 输入查询缓存 |
| 🚀 GC-Friendly / GC友好 | Reused evaluator instances / 重用求值器 |
| 🔄 Signal Bus / 信号总线 | Global named-channel communication / 全局命名通道通信 |
| 🧹 PID Anti-Windup / PID抗饱和 | Integral capping / 积分上限钳制 |
| 🛡️ Cycle Detection / 环检测 | Compile-time circular dependency check / 编译时循环引用检测 |
| 🎮 GLFW Raw Input / GLFW原始输入 | Bypass Minecraft keybinding system / 绕过MC键位系统 |
| 🔄 Sable Integration / Sable集成 | `BlockEntitySubLevelActor` sub-level pose reading / 子世界姿态读取 |

---

## 📜 Changelog / 更新日志

<details>
<summary><b>v1.2.4</b> — Multiplayer Collaboration + Debug Toolchain / 多人协作 + 调试工具链</summary>

### 👥 Multiplayer Collaboration / 多人协作
All 7 blocks now support real-time collaborative graph editing — multiple players can edit the same node graph simultaneously. / 全部 7 种方块支持多人实时协作编辑同一节点图。

| Feature / 功能 | Description / 说明 |
|----------------|-------------------|
| 🖱️ Live Cursors / 实时光标 | Colored crosshairs with player names / 彩色十字准星 + 玩家名 |
| 📦 Remote Node Drag / 远程拖拽节点 | Smooth lerp animation on remote moves / 远程移动平滑插值动画 |
| 🔗 Wire Preview / 连线预览 | Live bezier curve while dragging wires / 拖拽连线时实时贝塞尔曲线 |
| 👤 Player List / 玩家列表 | Right-side vertical list, host highlighted / 右侧竖向列表，房主高亮 |
| 🔒 Node Lock / 节点锁定 | IMAGE nodes protected during pixel editing / 像素编辑时自动锁定 |
| ⚡ Join/Leave / 加入离开 | Appear immediately on open, disappear on close / 打开即现，关闭即消 |
| ✏️ Op-Based Editing / 操作同步 | `GraphOp` + `OpExecutor` model, server-authoritative ID allocation / 服务器权威 ID 分配 |

### ⚡ Architecture Refactoring / 架构重构

| Change / 改动 | Description / 说明 |
|---------------|-------------------|
| 🖥️ Server-Authoritative Eval / 服务端评估 | Client-side `GraphEvaluator` removed. All evaluation runs server-side; results synced via `ClientboundGraphEvalPacket` + `EvalSnapshot`. Fixes PRIVATE_IN/BUS_IN always returning 0 on client. |
| 🏗️ Unified BE Base / 统一 BE 基类 | `SyncedGraphBlockEntity` consolidates ~200 lines duplicated across 7 BEs (BUS lifecycle, RedstoneLink, NBT, sync, EvalSnapshot). |
| 📦 Blob Data Channel / Blob 通道 | `BlobDataPacket` + `BlobRegistry` for chunked large data. `SET_IMAGE_PIXELS` from Base64 to direct `int[]`. |

### 🔧 Debug Toolchain / 调试工具链

| Tool / 工具 | Description / 说明 |
|-------------|-------------------|
| 📶 Signal Generator / 信号发生器 | Test signal source. Manual curve mode (draggable control points, X-clamped, server-sorted) or custom f(x) formula (all math functions, auto full-width paren conversion). Frequency-generate (auto-cycling X) or input-driven (drag sky-blue marker). Auto-scale Y axis with ±5 outlier clipping. |
| 📊 Signal Probe / 信号探针 | Real-time monitor with 100-tick trend chart. Auto-scale Y axis with outlier clipping. Right-click freeze/unfreeze/clear. |

### 📌 View Bookmarks / 视角书签
- ★ button (bottom-right, above ▼) toggles bookmark panel / ★按钮开关书签面板
- `[+]` / `Ctrl+M` save current view, `[↺]` / `Home` reset to origin
- `[✎]` rename, `[✕]` delete, `[→]` or click name to jump with 200ms ease-in-out transition
- Drag name area to reorder, synced via `MOVE_BOOKMARK` op
- Click outside naming dialog to cancel; Esc handled by unified popup stack
- Multiplayer-synced via `ADD_BOOKMARK` / `REMOVE_BOOKMARK` / `RENAME_BOOKMARK` / `MOVE_BOOKMARK` ops

### 🐛 Fixes & Polish / 修复与打磨
- 🔧 **Graph Init** — `onLoad()` bumps generation to force full recompile on first tick.
- 🚌 **BUS Channel** — `registerChannels()` no longer requires `bandCount()>0`; empty-band channels register so BUS_IN reads immediately.
- 🎨 **Color Picker UX** — ESC closes picker + panels together. Duplicated nodes get `sortB = original+1`.
- 📝 **Bilingual Comments** — All `graph/`, `blocks/`, `network/` source comments now Chinese+English.
- 🐛 **Encapsulation DEBUG Visibility** — `EvalSnapshot` now captures sub-evaluator outputs + debugTimes. Signal Generator (blue X marker) and Signal Probe work correctly inside encapsulation sub-graphs. / 封装内信号发生器（蓝色X标记线）和探针现在正确显示。
- ⌨️ **Esc Key Delegation** — Esc now closes sub-UI (bookmark rename, export/import dialog, color picker) before closing the entire editor screen. / Esc 先关闭子 UI 再关整个编辑界面。
- 🔒 **Soft-Lock Scope** — Node locking now scoped by `ownerNodeId`. Selecting a node inside encapsulation no longer falsely locks main-graph nodes with the same ID. / 封装内选中节点不再误锁主图同 ID 节点。
- 🖱️ **Cursor Scope Isolation** — Remote player cursors are now filtered by scope; cursors inside encapsulation are hidden from main-graph view and vice versa. / 远端光标按作用域隔离。
- 🟡 **ENCAPSULATION Occupant Highlight** — Golden border + player name label on ENCAPSULATION nodes in the main graph when other players are editing inside. / 主图中被占用的封装节点显示金色边框+玩家名。
- 📝 **ENCAP I/O Rename Sync** — Renaming `ENCAP_INPUT` / `ENCAP_OUTPUT` now sends `SET_DISPLAY_TEXT` op for server sync + undo support. / 封装I/O改名现在同步到服务端并支持撤销。
- 📋 **Ctrl+D Copy Fix** — Copy now uses server-authoritative ID allocation (`ADD_NODE_REQUEST` → ACK); data ops are deferred until all real IDs assigned. Sub-graph content recursively synced for ENCAPSULATION nodes. Fixes "empty node on other clients". / 复制走服务端权威ID分配，封装子图递归同步。
- 📐 **Manual Curve Fixed Y-Axis** — Signal Generator manual curve mode now uses fixed Y range `[-1.1, 1.1]`; auto-scaling retained for formula mode. Control points clamped to visible range and rendered above border. / 手动曲线Y轴固定，控制点钳制+边框上方渲染。

</details>

<details>
<summary><b>v1.2.3</b> — A.B.C Occlusion System + Comment Node / A.B.C遮挡系统+注释节点</summary>

- 🔄 A.B.C Three-Layer Occlusion System — Grid→Comments→Connections→Nodes→Overlays→Tooltips / 三层遮挡系统
- 📊 Dynamic B-Value Ordering — Drag to top, auto-renormalize / 动态B值排序
- 🎯 Spatial Index — Grid-based spatial hash, O(k) filtering / 空间索引加速
- 📝 COMMENT Node (82 total) — Sticky-note, resizable, 3-color customizable / 便利贴注释节点
- 🐛 Dedicated Server crash fix / 专用服务器崩溃修复

</details>

<details>
<summary><b>v1.2.2</b> — Portable Terminal + Layer Panel + Undo/Redo / 便携终端+图层面板+撤销重做</summary>

- 📱 Portable Terminal — Handheld remote editor, scan 1-256 blocks / 便携终端
- 🖼️ Layer Panel — Photoshop-style with drag-drop + thumbnails / 图层面板
- ↩️ Undo/Redo — Graph + pixel editor, 50-step history / 撤销重做

</details>

<details>
<summary><b>v1.2.1</b> — Performance + Atomic Colors / 性能优化+原子调色板</summary>

- ⚡ GUI perf — Eliminated per-frame allocations / 消除每帧分配
- 🎨 Atomic colors — No cross-thread tearing / 原子调色板
- 🏗️ Dirty flags — Cache invalidation / 脏标记缓存失效
- 📐 Precision — Unified layout constants / 统一布局常量

</details>

<details>
<summary><b>v1.2.0</b> — Formula Script + Radar + Bus / 公式脚本+雷达+总线</summary>

- ✨ Formula → Multi-line script editor / 公式→多行脚本编辑器
- ✨ 8 new math nodes / 8个新数学节点
- ✨ 3D Holographic Radar / 3D全息雷达
- ✨ BUS_IN/BUS_OUT system / BUS总线系统
- ✨ Encapsulation import/export / 封装导入导出

</details>

<details>
<summary><b>v1.1.x</b> — Monitor + Seat + Sensor / 全息显示器+控制座椅+姿态传感器</summary>

**v1.1.0**: Monitor, Control Seat, Attitude Sensor, 14 new nodes / 显示器、座椅、传感器、14新节点
**v1.1.1**: Encapsulation node, Redstone input for Monitor, Mixin / 封装节点、显示器红石输入
**v1.1.2**: Gate node, Monitor GUI fixes / 闸门节点、显示器修复
**v1.1.3**: 7 new nodes, IMAGE rotation input, i18n / 7新节点、图像旋转、多语言
**v1.1.4**: Velocity node, universal param pins, NBT migration v1→v2 / 速度节点、参数引脚、NBT兼容
**v1.1.5**: Latch config panel, runtime state sync / 锁存器面板、运行时状态同步

</details>

<details>
<summary><b>v1.0.0</b> — Initial Release / 初始发布</summary>

3 programmable computers, 24 node types, visual node editor, Redstone Link integration.
3台可编程计算机、24种节点、可视化编辑器、红石链接集成。

</details>

---

## 🔗 Quick Reference / 快速参考

| Category / 分类 | Nodes / 节点 |
|-----------------|-------------|
| **Values / 数值** | CONST, REDSTONE_IN, PRIVATE_IN, BUS_IN |
| **Math / 数学** | ADD, SUB, MUL, DIV, MOD, POW, ROOT, ABS, CEIL, FLOOR, FORMULA, ROUND, INTERP, SPLIT, POSE_CONVERT |
| **Trig / 三角** | SIN, COS, TAN, ASIN, ACOS, ATAN2, SINH, COSH, SQRT, LN, LOG, EXP, SEC, CSC, COT, ANGLE_UNWRAP, DIRECTION |
| **Logic / 逻辑** | GT, LT, GE, LE, EQ, OR, BOOL, GATE |
| **Control / 控制** | PID, PID_POWER, CLAMP, MAP |
| **Output / 输出** | REDSTONE_OUT, PRIVATE_OUT, BUS_OUT, SPEED_CTRL |
| **Sequential / 时序** | DELAY, LATCH, T_FLIPFLOP, PULSE_EXTEND, LOOP, FUSE, ACCUMULATOR, INTEGRATOR |
| **Input / 输入** | KEYBOARD, MOUSE_JOYSTICK, VIEW_ANGLE, MOUSE_BUTTON, GAMEPAD_JOYSTICK, GAMEPAD_BUTTON, GAMEPAD_TRIGGER |
| **Sensor / 传感器** | WORLD_VIEW, ATTITUDE, FORWARD, ACCELERATION, VELOCITY, POSITION, TARGET_OUT |
| **Display / 显示** | TEXT, DATA, IMAGE, IMAGE_SEQUENCE |
| **Structure / 结构** | ENCAPSULATION, ENCAP_INPUT, ENCAP_OUTPUT |
| **Annotation / 注释** | COMMENT |
| **Debug / 调试** | DEBUG_SIGNAL_GEN, DEBUG_PROBE |

---

## ❓ FAQ / 常见问题

<details>
<summary><b>Node editor laggy? / 节点编辑器卡顿？</b></summary>
Too many nodes or complex PID. Keep PIDs reasonable. / 节点过多或PID复杂，单计算机PID不宜超过5-6个。
</details>

<details>
<summary><b>Speed Proxy not working? / 转速代理不工作？</b></summary>
Place directly adjacent to a Speed Controller. / 放置在转速控制器相邻面。
</details>

<details>
<summary><b>State lost after schematic? / 蓝图放置后状态丢失？</b></summary>
Use Create 6.0.10+. Full NBT interfaces registered. / 确保使用Create 6.0.10+。
</details>

<details>
<summary><b>Can computers communicate? / 计算机可以通信吗？</b></summary>
Yes — Private Signal I/O (named channels) or BUS_IN/BUS_OUT (banded). / 可以——私有信号I/O或BUS总线。
</details>

---

## 📥 Installation / 安装

| Dependency / 依赖 | Version / 版本 |
|------------------|---------------|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.233+ |
| Create | 6.0.10+ |

*Sable is optional / Sable 为可选*

1. Install NeoForge + Create / 安装 NeoForge + Create
2. Place `.jar` in `mods` folder / 将 `.jar` 放入 `mods`
3. Launch! / 启动！

---

## 🌐 Links / 链接

- **GitHub**: [github.com/y15173334444/create-schematic-compute](https://github.com/y15173334444/create-schematic-compute)
- **Modrinth**: [modrinth.com/mod/create-schematic-compute](https://modrinth.com/mod/create-schematic-compute)
- **License / 许可证**: MIT © 2026 StarryNight_Luo

<p align="center">
  <b>⭐ If you enjoy this mod, star us on GitHub! / 喜欢请在GitHub点⭐！</b><br>
  <i>Unleash Create's potential with visual programming! / 用可视化编程释放机械动力的潜力！🚀</i>
</p>
