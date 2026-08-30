# Create: Schematic Compute

<p align="center">
  <b>🎮 7 Programmable Blocks · 86 Node Types · Formula Syntax Highlighting & Autocomplete · Multiplayer Collaboration</b><br>
  <b>七种可编程方块 · 86种节点 · 公式语法高亮与自动补全 · 多人实时协作</b><br>
  <i>Drag, connect, and build logic — just like Unreal Engine Blueprints!</i><br>
  <i>拖拽连接，构建逻辑 — 像虚幻引擎蓝图一样直观！</i><br>
  <i>Created by <b>StarryNight_Luo</b> (y15173334444)</i>
</p>

<p align="center">
  <a href="https://github.com/y15173334444/create-schematic-compute"><img src="https://img.shields.io/badge/GitHub-y15173334444/create--schematic--compute-blue?style=flat-square&logo=github" alt="GitHub"></a>
  <a href="https://github.com/y15173334444/create-schematic-compute/blob/main/LICENSE"><img src="https://img.shields.io/badge/License-MIT-green?style=flat-square" alt="License"></a>
  <a href="https://github.com/y15173334444/create-schematic-compute/releases"><img src="https://img.shields.io/badge/Version-1.2.5-blue?style=flat-square" alt="Version"></a>
  <a href="https://neoforged.net/"><img src="https://img.shields.io/badge/NeoForge-21.1.233-orange?style=flat-square" alt="NeoForge"></a>
  <a href="https://modrinth.com/mod/create"><img src="https://img.shields.io/badge/Create-6.0.10-brightgreen?style=flat-square" alt="Create"></a>
  <a href="https://www.minecraft.net/"><img src="https://img.shields.io/badge/Minecraft-1.21.1-8B4513?style=flat-square" alt="MC"></a>
</p>

---

## 📖 Overview / 简介

**🇬🇧** Create: Schematic Compute is a **Create mod addon** that introduces **7 programmable blocks + 1 portable terminal** with a **visual node-based programming system**. Instead of writing complex redstone circuits, simply drag and connect nodes to build logic — just like Unreal Engine Blueprints or Blender Geometry Nodes. Each computer runs at **20Hz (every game tick)** for real-time control. **All 7 blocks support real-time multiplayer collaborative editing** with live cursor tracking and node lock protection. The **FORMULA script editor** features syntax highlighting (9 token colours), intelligent autocomplete (functions, variables, `@output`), real-time validation with error badges, and named constants `(PI)`/`(E)`.

**🇨🇳** **机械动力：蓝图计算机** 是一个机械动力附属模组，添加了**七种可编程方块和一个便携终端**，采用**可视化节点图编程系统**。无需搭建复杂红石电路，只需拖拽连接节点即可构建逻辑——就像虚幻引擎的蓝图系统或 Blender 的几何节点一样直观。每台设备拥有独立的节点图，以 **20Hz（每游戏刻）** 的频率运行，适合实时控制应用。**全部 7 种方块支持多人实时协作编辑**，带实时光标追踪和节点锁定保护。**FORMULA 公式脚本编辑器** 支持语法高亮（9 种词法颜色）、智能自动补全（函数、变量、`@output`）、实时校验与错误徽章、以及命名常量 `(PI)`/`(E)`。

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
| 🔄 Sable Compatible / Sable兼容 | Two camera modes (FIXED / VIEW_DIFFERENCE), world-orientation tracking via quaternion / 双相机模式，四元数世界朝向追踪 |
| 🚪 Controls / 操作 | Right-click sit / `Shift`+Right-click editor / `~` dismount / `TAB` mode / `ESC` release |

---

### 📐 Attitude Sensor / 姿态传感器
**Physics structure orientation / 物理结构姿态读取**

| Feature / 功能 | Description / 说明 |
|----------------|-------------------|
| 📐 ATTITUDE / 姿态 | Block world-space pitch and roll (facing × sub-level rotation) / 方块自身世界姿态俯仰与横滚（朝向 × 子世界旋转） |
| 🧭 FORWARD / 前方朝向 | World-space forward yaw/pitch / 结构世界空间朝向 |
| ⚡ ACCELERATION / 加速度 | Block-local X/Y/Z acceleration (structure motion in block axes) / 方块本地加速度（结构运动按方块朝向分解） |
| 🚀 VELOCITY / 速度 | Block-local velocity ×2 m/s (structure motion in block axes) / 方块本地速度（结构运动按方块朝向分解） |
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
| 📡 Device Scan / 设备扫描 | Scan 1-128 blocks for programmable blocks / 扫描1-128格可编程方块 |
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

Multi-line script editor (v1.2.0+) — assignments, control flow, vec3, named outputs, comments, line continuation.
多行脚本编辑器 — 支持赋值、控制流、vec3、命名输出、注释、续行。完整语法见 [`docs/formula-syntax-manual.md`](https://github.com/y15173334444/create-schematic-compute/blob/main/docs/formula-syntax-manual.md)。

### 🧮 Syntax Overview / 语法速览

**赋值与输入引脚 / Assignments & input pins** — any name assigned anywhere is an internal variable; every other name that is read becomes an input pin. 任何处被赋值的名字是内部变量，其余被读取的名字成为输入引脚：
```
x = a + 1        -- x 内部变量 / internal; a 成为输入引脚 / input pin
@output x
```
→ 1 input pin + 1 output / 1 输入 + 1 输出

**控制流 / Control flow** — `repeat` / `while` / `if` / `else` / `break` / `continue`：
```
acc = 0
repeat 100 { acc = acc + 1 }
if (acc > 50) { acc = 0 } else { acc = 1 }
@output acc
```

**比较与逻辑 / Comparison & logic** — `< > <= >=` are exact; `==`/`!=` use a 1e-6 tolerance; `&&` `||` `!` judge truthiness as `!=0`. `< > <= >=` 精确；`==`/`!=` 1e-6 容差；`&&` `||` `!` 以 `!=0` 判真。

**vec3 与向量函数 / vec3 & vector functions**：
```
v = vec3(3, 4, 0)
@output length(v)     -- 5
@output yaw(v)        -- 角度制,与 DIRECTION 节点一致 / degrees, mirrors DIRECTION
@output v             -- vec3 自动展开为 v.x/v.y/v.z 三个输出引脚 / expands into 3 scalar pins
```
Vector functions: `vec3 length normalize dot cross dist yaw pitch`; component access `v.x/y/z`. 向量函数：`vec3 length normalize dot cross dist yaw pitch`；分量访问 `v.x/y/z`。

**函数表 / Functions**（角度均按度 / trig in degrees）：
**15 个标量函数 / 15 scalar functions** — `sin` `cos` `tan` `asin` `acos` `atan2` `sinh` `cosh` `sqrt` `ln` `log` `exp` `sec` `csc` `cot`
**7 个向量函数 / 7 vector functions** — `vec3` `length` `normalize` `dot` `cross` `dist` `yaw` `pitch`

**中文输入即转 / CJK input converts live** — `（）→()`、`×→*`、`≥→>=`，full-width letters/digits/spaces convert to half-width as you type. `（）→()`、`×→*`、`≥→>=`、全角字母/数字/空格即输即转半角。

**预算池 / Budget pool** — loop-heavy scripts spread across ticks: a thin progress bar below the node shows solve progress; outputs freeze during the spread and update only on completion (emit-on-done); the `warm` edit-panel toggle controls whether an input change keeps iterating or strictly freezes. 循环重负载脚本跨 tick 分摊：节点下方进度条显示解算进度，spread 期间输出冻结、完成才更新（emit-on-done）；`warm` 编辑区开关控制输入变更时继续迭代还是严格冻结。典型应用见下方火控弹道解算示例。

### 🎯 火控弹道解算示例 / Fire-Control Ballistic Solver Example

Newton-iteration aim solver ported from a Python reference (CreateBigCannons ballistic model: semi-implicit Euler dt=1/20, linear/quadratic drag), verified against four reference scenarios.
牛顿迭代弹道反解，移植自 Python 参考实现（CreateBigCannons 弹道模型：半隐式欧拉 dt=1/20、线性/二次阻力），四组场景对拍通过。
Full paste-ready script: [`docs/examples/ballistic_solver.formula`](https://github.com/y15173334444/create-schematic-compute/blob/main/docs/examples/ballistic_solver.formula) — ~600k interpreter iterations per solve, spread across ticks by the budget pool with a progress bar.
完整可粘贴脚本：[`docs/examples/ballistic_solver.formula`](https://github.com/y15173334444/create-schematic-compute/blob/main/docs/examples/ballistic_solver.formula)（约 60 万次迭代，由预算池跨 tick 分摊、带进度条）。

```
-- 输入:mx,my,mz 炮口 / tx,ty,tz 目标 / v0 初速 / g 重力(正) / fd 阻力系数 / qd 二次阻力 / den 密度
-- inputs: mx,my,mz muzzle / tx,ty,tz target / v0 speed / g gravity(+) / fd drag / qd quadratic drag / den density
-- 输出:ay 射向角[0,360) / ap 射角 / hit 可达 / vx0,vy0,vz0 初速向量
-- outputs: ay aim yaw [0,360) / ap aim pitch / hit reachable / vx0,vy0,vz0 velocity vector
ay = atan2(tx - mx, 0 - (tz - mz))
if (ay < 0) ay = ay + 360
cy = cos(ay)
sy = sin(ay)
-- 俯仰粗扫描(361 点)+ 轨迹模拟(半隐式欧拉 dt=1/20,阻力/重力积分,记录最近距离)
-- pitch coarse scan (361 points) + trajectory simulation (semi-implicit Euler dt=1/20, drag/gravity integration, track closest distance)
p = -89.899
bestd = 1000000
repeat 361 {
  vx = v0 * cos(p) * sy
  vy = v0 * sin(p)
  vz = v0 * cos(p) * (0 - cy)
  -- ... 1200 步轨迹模拟(完整脚本见上方链接) ... / 1200-step simulation (full script linked above)
  p = p + 0.49944
}
-- 牛顿迭代精化(≤50 轮,中心差分,阻尼 0.5) / Newton refinement (≤50 rounds, central difference, damping 0.5)
@output ay
@output ap
@output hit
```
→ **11 inputs + 6 outputs / 11输入 + 6输出**

### 🎨 Syntax Highlighting / 语法高亮
Real-time colour-coded editing with 9 token categories.
9 种词法分类的实时彩色标注。

| Token Type / 词法类型 | Colour / 颜色 | Examples / 示例 |
|----------------------|-------------|-----------------|
| Functions / 函数 | 🟡 Yellow / 黄色 | `sin`, `cos`, `sqrt`, `exp` |
| Constants / 常量 | 🩷 Pink / 粉色 | `(PI)`, `(E)` — 仅分组括号内视为字面量 |
| Identifiers / 标识符 | 🩵 Light Cyan / 浅青 | `x`, `speed`, `myVar` |
| Numbers / 数字 | 🟠 Orange / 橙色 | `3.14`, `42`, `0.5` |
| Operators / 运算符 | ⬜ Grey / 灰色 | `+`, `-`, `*`, `/`, `^`, `%` |
| Parens / 括号 | ⬜ Grey / 灰色 | `(`, `)` |
| Comments / 注释 | 🟢 Green / 绿色 | `-- this is a comment` |
| @output / 输出 | 🟣 Purple / 紫色 | `@output` |
| Assignment / 赋值 | 🟣 Purple / 紫色 | `=` |
| Unknown / 未知 | 🔴 Red / 红色 | Invalid characters / 非法字符 |

### 🔍 Autocomplete / 自动补全
Type to trigger suggestions near the caret, rendered above all pins.
输入即触发，候选框显示在光标下方、所有引脚上方。

| Trigger / 触发方式 | Behaviour / 行为 |
|-------------------|-----------------|
| Type identifier char / 输入标识符字符 | Filtered dropdown: functions, constants, current variables / 过滤候选：函数、常量、当前变量 |
| Type `@` / 输入 `@` | Immediately suggests `@output` / 立即建议 `@output` |
| `Tab` / `Enter` | Accept selected candidate / 接受选中候选项 |
| `↑` `↓` | Navigate candidates / 导航候选项 |
| `Esc` / any other key | Close popup / 关闭候选框 |
| Click candidate / 点击候选项 | Accept and insert / 接受并插入 |
| Zoom-aware / 缩放感知 | Popup scales with graph zoom level / 候选框随图缩放 |

### ✅ Real-Time Validation / 实时校验
Issues shown as red ⚠ badge on the node title bar. Hover the badge to see details.
错误以红色 ⚠ 徽章显示在节点标题栏，悬停查看详情。

| Check / 校验项 | Type / 类型 |
|---------------|-----------|
| Bracket matching / 括号匹配 | Error / 错误 |
| Unknown function / 未知函数 | Error / 错误 |
| Function arity / 函数参数数量不符 | Error / 错误 |
| Invalid assignment / 无效赋值 | Error / 错误 |
| Duplicate output names / 重复输出名 | Warning / 警告 |
| @output invalid start / @output 起始非法 | Warning / 警告（表达式输出合法，如 `@output length(v)`） |
| Red border on MLE / 输入框红色边框 | Visual feedback / 视觉反馈 |

### 📐 Named Constants / 命名常量
`(PI)` and `(E)` in grouping parentheses are literal constants (π ≈ 3.14159, e ≈ 2.71828).
Bare `PI` / `E` or `PI` / `E` inside function calls like `sin(PI)` are treated as variable references (create input pins).
`(PI)` 和 `(E)` 在分组括号内视为字面常量。裸 `PI`/`E` 或函数调用内的 `sin(PI)` 视为变量（创建输入引脚）。

```
-- (PI) = literal π, not a variable / 字面量π，不是变量
-- sin(PI) = PI is a variable input / PI 是变量输入
result = (PI) + sin(PI)
@output result
```
→ 1 input pin (PI) + 1 output / 1 输入引脚 + 1 输出

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

## 🧩 Node Reference / 节点参考（86 种）

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
| Relay A / 继电器A | SPDT relay — contact false→A, contact true→B / 双掷继电器 |
| Relay B / 继电器B | SPST relay — out = contact ? B : A / 单掷合并继电器 |

</details>

<details>
<summary><b>🎛️ Control / 控制</b></summary>

| Node / 节点 | Description / 说明 |
|-------------|-------------------|
| PID Controller / PID控制器 | SP/PV PID (scalable output, anti-windup) / SP/PV 双输入 PID（输出可缩放，抗积分饱和） |
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
| Safety Timer / 保险 | Trigger (rising edge) or held-high input → 2-tick pulse → cooldown; held-high repeats as pulse generator / 触发（上升沿）或持续高电平 → 2 tick 脉冲 → 冷却；持续高电平自动循环（脉冲发生器） |
| Accumulator / 累计器 | Rising-edge step counter / 累计器 |
| Continuous Integrator / 连续积分器 | Continuous integration, configurable limit / 连续积分器 |

</details>

<details>
<summary><b>🎮 Input / 输入</b> (Control Seat + Attitude Sensor / 控制座椅+姿态传感器)</summary>

| Node / 节点 | Description / 说明 |
|-------------|-------------------|
| Keyboard Key / 键盘按键 | 58 bindable keys / 58键绑定 |
| Mouse Joystick / 鼠标摇杆 | Dual mode: incremental (mouse delta) / absolute (stick with memory). Toggle in edit panel. / 双模式：增量/绝对值，编辑区切换 |
| View Angle / 视角差 | View angle delta / 视角差 |
| Mouse Button / 鼠标按键 | Left/Right mouse buttons / 鼠标按键 |
| Gamepad Joystick / 手柄摇杆 | Dual stick LX/LY/RX/RY / 双摇杆 |
| Gamepad Button / 手柄按键 | 15 buttons / 15按键 |
| Gamepad Trigger / 手柄扳机 | Analog triggers LT/RT (0~1) / 模拟扳机 |
| World View / 世界视角 | Player absolute world view direction / 玩家绝对视角 |
| Attitude / 姿态 | Block world-space pitch and roll (facing × sub-level rotation) / 方块自身世界姿态俯仰与横滚（朝向 × 子世界旋转） |
| Forward / 前方朝向 | World-space forward yaw/pitch / 结构朝向 |
| Acceleration / 加速度 | Block-local X/Y/Z acceleration (structure motion in block axes) / 方块本地加速度（结构运动按方块朝向分解） |
| Velocity / 速度 | Block-local velocity ×2 m/s (structure motion in block axes) / 方块本地速度（结构运动按方块朝向分解） |
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
<summary><b>🔧 Debug / 调试</b> (All blocks / 全部方块)</summary>

| Node / 节点 | Description / 说明 |
|-------------|-------------------|
| Comment / 便利贴 | Sticky-note annotation, resizable (80~8000×40~6000), scrollable, 3-color customizable. Drag header to move, parent-move contains nodes. Pure visual — skipped during evaluation. Press **C** with nodes selected to wrap. / 可调大小/滚动/三色自定义。拖拽顶部移动，父级移动携带内部节点。纯视觉辅助。选中节点按 **C** 包裹。 |
| Signal Generator / 信号发生器 | Test signal source with XY curve preview + Y-axis range label, manual control-point curve or custom f(x) formula, frequency-generate or input-driven output modes, percentile-based robust auto-scale / 测试信号源，XY曲线预览+Y轴范围标注，手动控制点曲线或自定义f(x)公式，频率发生/指定模式输出，百分位数稳健自动缩放 |
| Signal Probe / 信号探针 | Real-time signal monitor with 100-tick trend chart, percentile-based robust auto-scale (p1-p99), freeze/clear / 实时信号监视，100 tick趋势图，百分位数稳健自动缩放（p1-p99），冻结/清除 |

**Signal Generator Modes / 信号发生器模式：**

| Mode / 模式 | Description / 说明 |
|-------------|-------------------|
| 🎯 Manual Curve / 手动曲线 | Drag control points on XY chart. Double-click to add, right-click to delete, drag X/Y freely (X clamped between neighbors, server-sorted). Points synced via multiplayer collaboration. / XY图上拖拽控制点。双击添加，右键删除，XY双向自由拖拽（X被相邻点夹持，服务端排序）。多人协作同步。 |
| 📐 Custom f(x) / 自定义公式 | Enter formula expression using variable `x` (0~1). Supports all math functions (trig in degrees), auto full-width paren conversion. Speed/amplitude disabled — formula controls everything. / 输入公式表达式，变量 `x`（0~1）。支持全部数学函数（三角函数用度），自动全角括号转换。speed/amplitude 禁用。 |
| 🔄 Frequency Generate / 频率发生 | X auto-advances 0→1 cyclically. Speed control (manual mode only). Current X position shown as sky-blue marker on chart. / X自动0→1循环推进。speed控制速度（仅手动模式）。天蓝色标记线显示当前X位置。 |
| 🎯 Input-Driven / 指定模式 | X set by dragging sky-blue marker line on chart. No EditBox — pure drag interaction. / 拖拽天蓝色标记线设置X值。无输入框，纯拖拽交互。 |

**Signal Probe Features / 信号探针功能：**
- 100-tick ring buffer trend chart with percentile-based robust auto-scale (p1-p99)
- Percentile filtering replaces fixed ±5 clipping — correctly displays large-range data (e.g. 0~360)
- Right-click: Freeze/Unfreeze, Clear History
- Shows "---" when blueprint not running

</details>

---

## 🎮 Controls / 操作指南

| Action / 操作 | Input / 按键 |
|---------------|-------------|
| Add node menu / 添加节点 | Right-click empty / 右键空白 |
| **Two-column layout / 双列布局** | Click the state-label button on the menu title row (`1 Col`/`2 Col`), gold = two columns on / 点击菜单标题行右侧状态文字按钮（`单列`/`双列`），金色=双列开启 |
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
<summary><b>v1.2.5</b> — 公式语言升级：控制流 + vec3 + 预算池 / GUI 架构迁移 / 像素编辑器 / 可编程变速箱 / Formula Language Upgrade: Control Flow + vec3 + Budget Pool / GUI Architecture Migration / Pixel Editor / Programmable Gearbox</summary>

### 🧮 公式语法升级 / Formula Syntax Upgrade

| Feature / 功能 | Description / 说明 |
|----------------|-------------------|
| 🔁 Control Flow / 控制流 | `repeat` / `while` / `if` / `else` / `break` / `continue` — loop-heavy formulas spread across ticks / 循环公式跨 tick 分摊 |
| 🧊 vec3 + Vector Functions / 向量 | `vec3(x,y,z)`, swizzle `v.x/y/z`, `length`/`normalize`/`dot`/`cross`/`dist`/`yaw`/`pitch` (`yaw`/`pitch` aligned with the DIRECTION node, degrees, yaw ∈ [0,360)) |
| ⚖️ Comparison & Logic / 比较逻辑 | `< > <= >=` exact; `==`/`!=` 1e-6 tolerance; `&&` `\|\|` `!` with `!=0` truthiness |
| 📐 Scalar Functions / 标量函数 | `sin cos tan asin acos atan2 sinh cosh sqrt ln log exp sec csc cot` (degrees convention) |
| 🎯 @output vec3 Expansion / 输出展开 | `@output v` (vec3) auto-expands into 3 scalar pins `v.x`/`v.y`/`v.z` with stable pinIds |
| 🔗 Warm Restart Toggle / 温启动开关 | Pinless eval-policy setting in the formula edit panel (segmented toggle like the signal generator's mode switch): warm keeps iterating toward new inputs without resetting progress vs strict freeze (default) / 编辑区无引脚两段式按钮：温启动保留进度继续迭代 vs 严格冻结（默认） |
| 🌐 CJK Input / 中文输入 | Chinese/full-width symbols, letters, digits and spaces convert to half-width ASCII as you type (（）→(), ×→*, ≥→>=, full-width ｘ→x …) / 中文/全角符号输入即转半角 |
| ✍️ Editor Support / 编辑器支持 | Syntax highlighting, autocomplete and validation for all new tokens; `--` line comments / 新语法高亮补全校验；`--` 行注释 |

- **统一求值引擎 / Unified eval engine**：single `Value` stack machine for legacy RPN and new AST scripts — old formulas are byte-identical, no migration, no dual-engine drift. / 单一栈机统一求值,旧脚本逐位不变。
- 详见 [`docs/formula-syntax-manual.md`](https://github.com/y15173334444/create-schematic-compute/blob/main/docs/formula-syntax-manual.md)。

### ⏱️ 公式预算池 / Formula Budget Pool

| Mechanism / 机制 | Behavior / 行为 |
|------------------|----------------|
| 🕒 Per-Node Slice / 节点配额 | `slice = formulaBudgetMs / N_heavy_prev` (default 3.0ms, configurable 0.5–20); every node admitted every tick — zero starvation / 保底零饿死 |
| 🤝 Cooperative Suspend / 协作挂起 | Wall-clock check every 16 iterations at loop boundaries; carrier (loop stack + Env snapshot) saved, resumed next tick via seek execution — no lost iterations / 循环边界挂起续算不丢迭代 |
| 📤 Emit-on-Done / 收敛输出 | Outputs frozen during spread, fresh value only on done — half-converged values never leak / 半收敛解永不流出 |
| 🧊 Freeze / Warm / 冻结与温启动 | Input change mid-spread: strict freeze completes the old snapshot (default); warm keeps iterating toward the new inputs without resetting progress (opt-in) / 温启动保留进度继续迭代 |
| 🛡️ MAX_ITER Backstop / 兜底 | 1M iterations spread-wide → shed to lastGood + one-shot warning, unfrozen on formula edit / 超限冻结直到编辑 |
| ⚡ Dedup / 去重 | Same script + same inputs deduplicated per tick (pure functions, array-isolated) / tick 级去重 |
| 📊 Progress Bar / 进度条 | Thin render-state bar on FORMULA nodes (no values): 0..1 progress, breathing fill for `while` (indeterminate) / 渲染态进度条 |

- **架构 / Architecture**：inline gating — FORMULA evaluates in place at its topological position; no central queue, no added tick latency. / 内联门控,无中央队列无延迟。

### 🎯 火控弹道解算示例 / Fire-Control Ballistic Solver Example

[`docs/examples/ballistic_solver.formula`](https://github.com/y15173334444/create-schematic-compute/blob/main/docs/examples/ballistic_solver.formula) — Newton-iteration aim solver ported to the FORMULA language (CreateBigCannons ballistic model: semi-implicit Euler dt=1/20, linear/quadratic drag). 361-point pitch scan + damped Newton refinement; ~600k interpreter iterations per solve, spread across ticks by the budget pool with a progress bar. 11 input pins (muzzle/target positions, v0, gravity, drag, density) → 6 outputs (yaw/pitch/reachable + velocity vector). Validated against a Python reference implementation across four scenarios.
/ 牛顿迭代弹道反解：361 点俯仰扫描 + 阻尼牛顿精化，单次约 60 万次解释器迭代、由预算池跨 tick 分摊（带进度条）。11 输入（炮口/目标坐标、初速、重力、阻力、密度）→ 6 输出（射向角/射角/可达 + 初速向量）。四组场景与 Python 参考实现对拍通过。

### 🖥️ GUI 架构迁移 / GUI Architecture Migration

- **7 个编辑界面脱离容器体系 / All 7 editors leave the container system**：全部 7 个编辑界面（蓝图 / 转速代理 / 编程计算机 / 传感器 / 控制座椅 / 显示器 / 雷达）从 `AbstractContainerScreen` + `Menu` 体系迁移为继承新基类 `AbstractGraphScreen`（纯 `Screen`），由方块在客户端直接 `setScreen` 打开——无 Menu 注册、无网络 round-trip、即时打开。/ All 7 editors (blueprint / speed proxy / program computer / sensor / control seat / monitor / radar) now extend the new `AbstractGraphScreen` base (plain `Screen`) and open client-side via `setScreen` — no menu registrations, no network round-trip, instant open.
- **第三方模组注入规避 / Third-party mod injection avoided**：界面不再被 FTB Quests、Quark 等模组识别为容器界面，节点编辑器画布上不再出现无关按钮、任务覆盖层或装饰边框。/ Screens are no longer recognized as container GUIs, so unrelated buttons, quest overlays and decorative frames no longer appear on the node canvas.
- **容器设施全部删除 / Container plumbing removed**：删除 7 个 `XxxMenu` 类、`SchematicCompute.MENUS` 注册（7 个 MenuType）、`ClientSetup.registerScreens`、`SyncedGraphBlockEntity` 的 `MenuProvider` 以及 7 个 BE 的 `getDisplayName`/`createMenu`。/ Deleted the 7 menu classes, the MENUS DeferredRegister, registerScreens, MenuProvider on the base BE, and getDisplayName/createMenu on all 7 BEs.
- **便携终端路径 / Portable terminal path**：终端打开设备编辑界面改为直接构造 `XxxScreen(editingPos)`，不再构造虚拟 Menu。/ The terminal now constructs editor screens directly from the edit position — no virtual menus.
- **守卫保持 / Guards preserved**：`pendingLocalOps` 回弹保护（`5892caa`）、BE 失效自动关界面、`GraphJoin/LeavePacket` 协作生命周期全部迁入基类，行为与迁移前一致；雷达设置面板 EditBox 写回经 `preClose()` 钩子保留。/ The pendingLocalOps bounce-back guard, BE-invalidation auto-close and join/leave collaboration lifecycle all moved into the base class with unchanged behavior; the radar settings EditBox write-back survives via the `preClose()` hook.

### 🖼️ 显示器图像系统修复 / Monitor Image System Fixes

| Fix / 修复 | Description / 说明 |
|------------|-------------------|
| ⚓️ 图像锚点对齐 / Image anchor clamp | 编辑器与世界渲染器统一为左上角锚点语义；修正世界渲染器 clamp 上界缺因子 2（`1-bbHalfW` → `1-2*bbHalfW`），缩放图像不再在右/下边框伸出半个图像宽度、与编辑器错位 / Editor and world renderer now share the top-left anchor; the world clamp bound missing the factor-2 was fixed, so scaled images no longer overhang the right/bottom border by half their size |
| 📏 边框与辅助线 / Bezel + placement grid | 死区减半（0.08→0.04 屏宽，编辑器与世界渲染器同源常量）；摆放辅助线绑定内容区 16 等分 + 中心十字，整格对齐 / Bezel margin halved (shared constant across editor and world renderer); the placement grid is bound to the content area in 16 exact divisions with a bold center cross |
| 🖱️ 陈旧选择拖拽 / Stale-selection drag | 空白处按下不再抓取图层面板的旧选择——仅当按下点落在已选元素（裁剪后的旋转 AABB）内才开拖；"先点图1、再拖图2"不再误移图1 / Pressing empty canvas no longer grabs the layer-panel selection; the press must fall inside the selected element's clamped rotated AABB, fixing the "select image 1, drag image 2 → image 1 moves" bug |
| 🧩 图像画布尺寸 / IMAGE canvas size | IMAGE/IMAGE_SEQUENCE 支持自定义 W×H（1..32，默认 16×16）：EditPanel 宽高输入、像素编辑器/缩略图/世界渲染/命中/拖拽全链路按节点尺寸、改尺寸左上角保留内容且全帧同步、旧档迁移保护；像素撤销计数标记改用并行元数据（1×1 画布不再冲突），并补全帧操作缺失的重做路径 / Custom W×H canvas (1..32, default 16×16) with edit-panel inputs, size-aware pixel editor/thumbnails/renderer/hit-tests, top-left-preserving resize across all frames, and legacy-save migration guard; pixel-undo count markers moved to parallel metadata (no more 1×1 collision) and the missing frame-redo path was completed |
| 🖌️ 像素同步与拖拽稳定性 / Pixel sync + drag stability | 像素编辑器所有关闭路径统一走定向同步（SET_IMAGE_PIXELS op），颜色确认（Enter/OK）不再静默关闭丢画；**不再全量上传整图**（避免冲掉其他玩家并发编辑）；显示区拖拽改节流流式同步（100ms，松手终发）并支持触屏（mouseDragged 同样更新）；整图同步替换后 selectedDisplayNode 按 id 重映射，消除"首次拖正常、之后本地冻结"的孤儿引用 / Every pixel-editor close path now runs the targeted SET_IMAGE_PIXELS sync — color confirm (Enter/OK) no longer closes silently and loses the painting; no more full-graph upload on close (which clobbered other players' concurrent edits); display drags throttle-stream their layout (100ms, final op on release), work on touch (mouseDragged updates too), and selectedDisplayNode is remapped by id after full-graph syncs, fixing the orphaned-reference freeze after the first drag |
| 🤝 显示布局实时协作 / Display-layout collaboration | 拖拽位置实时同步给队友；显示模式下渲染队友光标+名字+拖拽组件彩色描边（组件软锁：队友拖拽中的组件不可抓取）；存在光标严格按模式隔离（节点图模式与显示模式互不串场）/ Drag positions sync live to teammates; the display editor renders teammates' cursors with name tags and colored outlines around components being dragged (soft lock: those components cannot be grabbed); presence cursors are strictly per-mode (node-graph and display modes no longer bleed into each other) |
| 🚪 关屏定向提交 / Close-time targeted commit | 显示器关屏不再全量上传整图（旧行为会用本客户端快照覆盖服务端、冲掉其他玩家并发的编辑）；改为只把尚未同步的局部编辑定向提交：EditBox 输入（含 TAB 切焦点遗留文本）、busBox 频道名与频段改名、像素编辑器当前帧、进行中的显示区拖拽终态——全部走定向 op/包，图数据早已由各 op 实时同步，服务端即最新真相；设置面板保持显式 Apply 契约 / Closing the monitor screen no longer uploads the whole graph (which overwrote the server graph with this client's snapshot and clobbered other players' concurrent edits); only unsynced in-progress edits are committed via targeted ops/packets: EditBox text (incl. text left by TAB focus moves), busBox channel names + band renames, the pixel editor's current frame, and the final state of an in-flight display drag — the graph is already kept in sync live by ops, so the server holds the truth; the settings panel keeps its explicit-Apply contract |

- 详见 [`docs/monitor-image-fixes-audit.md`](https://github.com/y15173334444/create-schematic-compute/blob/main/docs/monitor-image-fixes-audit.md)（评审结论、grilling 决策与提交记录）。

### 🎨 像素编辑器 / Pixel Editor

| Feature / 功能 | Description / 说明 |
|----------------|-------------------|
| 🖥️ Standalone Screen / 独立界面 | Double-click an IMAGE/IMAGE_SEQUENCE node opens a dedicated `PixelEditorScreen` (painting-app layout) instead of an in-editor overlay; ESC/✕ returns to the graph editor (portable-terminal wrapper preserved) / 双击 IMAGE/IMAGE_SEQUENCE 节点打开独立 `PixelEditorScreen`（绘画软件布局），替代编辑器内浮层；ESC/✕ 回到图编辑器（便携终端包装保持） |
| 🧰 Toolbar / 工具栏 | Responsive tile layout that **never overlaps**: left panel (single-column compact tool rail: Brush, Eraser, Paint-bucket fill, Eyedropper, Line, Rectangle — brush size/opacity/current colour now live in the top bar), slim top bar (brush state + Fit + Guide + Undo/Redo + Canvas + Close, right-aligned) — the color palette is an **embedded always-on right panel** (see below), so the canvas fills the whole central column / 响应式瓦片布局，**任何窗口尺寸都不重叠**：左面板（单列窄工具列：画笔、橡皮、油漆桶、取色器、直线、矩形——笔刷大小/透明度/当前色已移入顶栏）、瘦身顶栏（画笔状态 + 适配 + 指南 + 撤销/重做 + 画布 + 关闭，右对齐）——取色器为**内嵌式常驻右面板**（见下），画布占满整条中央列 |
| 📐 Layout / 布局 | Every panel keeps its content inside its own strip (top bar `y<30`, left panel `x<30`, palette in the reserved right band, canvas/frame strip in the central column), so components can never overlap at any window size; internal controls are all positioned relative to their own panel anchor (no hard-coded absolute screen coords) / 每个面板内容都约束在自身条带内（顶栏 `y<30`、左面板 `x<30`、取色器在右缘预留带、画布/帧条在中央列），因此任何窗口尺寸都不重叠；内部控件全部相对各自面板锚点定位（不再使用硬编码绝对屏幕坐标） |
| 🖌️ PS Tool Rail / PS 式工具列 | The left tool rail is now PS-style: it hugs the left edge and the right divider (no empty gap), drops the per-button border outline, and highlights the whole cell rectangle only on hover/selected; the rail is slimmed to ~30px so the canvas gets more room / 左侧工具列改为 PS 式：紧贴左缘与右分隔线（不留空隙），去掉每个按钮的单独边框，仅悬停/选中时高亮整块矩形；工具列收窄到约 30px，把更多空间让给画布 |
| ✨ Beautify / 美化 | PS-style polish: cleaner two-tone tool icons, a context status bar (current tool / brush size / zoom (cell px) / grid state / hovered cell / sequence frame), a Fit zoom button, a grid toggle (`G`), and keyboard shortcuts (`B/E/F/I/L/R/H` tools, `1..7` rail order, `[`/`]` brush size, `Ctrl+Z/Y` undo/redo); the brush size also has a draggable **slider on the right of the top-bar status row** (1–32, same range as `[`/`]`) / PS 风格打磨：双色调更清晰的工具图标、上下文状态栏（当前工具/笔刷大小/缩放(单元格 px)/网格状态/光标格/序列帧号）、Fit 缩放按钮、网格开关（`G`）、键盘快捷键（`B/E/F/I/L/R/H` 工具、`1..7` 按顺序、`[`/`]` 笔刷大小、`Ctrl+Z/Y` 撤销重做）；笔刷大小另有**顶栏状态行右侧可拖动滑块**（1–32，与 `[`/`]` 同范围） |
| ✋ Hand Tool / 抓手工具 | A Hand tool at the end of the tool rail (shortcut `H`): with it selected, left-click-drag pans the canvas — no need for middle-mouse or Space / 工具列末尾新增抓手工具（快捷键 `H`）：选中后左键拖拽即可平移画布，无需中键或空格 |
| 🎨 Embedded Palette / 内嵌调色板 | The color picker is an **embedded** always-on panel on the right (scaled ~0.8x): the panel fills the right band down to the screen bottom, with a solid background + left divider like the other panels (not a floating popup), no toggle, no outside-click close, and the canvas stops at its left edge. The Favorites/Recent titles are drawn at full size (readable) and each shows more rows; the **OK and Eraser buttons are hidden** (in the embedded palette) and the eraser logic was removed from `ColorPickerWidget` entirely / 取色器改为**内嵌式**常驻右侧面板（约 0.8x）：面板铺满右缘到屏幕底部，实心底 + 左侧分隔线（非浮空弹窗）、无开关、点外部不关闭，画布止于其左缘；「常用/最近使用」标题字放大、各显示更多行；**不再显示「确定」和「橡皮擦」按钮**，且从 `ColorPickerWidget` 里**彻底移除了橡皮擦逻辑** |
| 🖱️ RMB Erase / 右键擦除 | Right-click erases to transparent under any tool / 任何工具下右键直接擦为透明 |
| 🩸 Eyedropper Sync / 取色器同步 | The eyedropper now syncs the embedded palette: picking a colour on the canvas updates the palette's SV plane / hue / alpha bars and hex field in real time / 吸管取色后内嵌调色板实时同步：SV 平面、色相/透明度滑条与 hex 输入框都切到吸取的颜色 |
| 🔍 Zoom & Pan / 缩放平移 | Mouse-wheel zoom (anchored at cursor), middle-drag or Space+LMB to pan / 滚轮缩放（光标锚定），中键或空格+左键平移 |
| ↩️ Undo/Redo Buttons / 撤销重做按钮 | Top-bar Undo/Redo buttons + Ctrl+Z/Y; resize undo restores size and all frames / 顶栏撤销/重做按钮 + Ctrl+Z/Y；尺寸撤销恢复旧尺寸与全部帧 |
| 🎞️ Frame Strip / 帧条 | Bottom thumbnail strip with click-to-switch, ◀/▶ nav, +New (blank / from current), Delete frame, and drag-to-reorder — new `REMOVE_IMAGE_FRAME` / `MOVE_IMAGE_FRAME` ops keep frame edits server-authoritative / 底部缩略图条：点击切换、◀/▶ 导航、+New（空白/复制当前）、删除帧、拖拽重排 — 新增 `REMOVE_IMAGE_FRAME` / `MOVE_IMAGE_FRAME` op 保持帧编辑服务端权威 |
| 🎞️ Sequence Area Layout / 序列区布局 | The sequence area is now stacked: a button row (◀/▶ nav + frame count, +New, Delete) sits directly above the thumbnail strip, and the thumbnail strip is flush against the bottom of the screen — the +/- buttons no longer sit inside the strip, and thumbnails fill it from the left margin / 序列区改为上下两段：±/导航 + 帧号、+New、删除 的按钮行紧挨在缩略图条上方，缩略图条紧贴屏幕底部（按钮不再嵌在条内），缩略图从左边缘起填满 |
| 🖼️ Dynamic Thumbnails / 缩略图动态缩放 | Sequence-frame thumbnails now scale dynamically: the height stays fixed (36px — bigger, compact) while the width follows the image's aspect ratio (a wide frame gets a wide thumb, a tall frame a narrow one), rendered to fill by per-pixel integer rects; the per-thumbnail cell borders are removed, the current frame is highlighted with a coloured backdrop instead, and the strip is tightened so the images sit flush at the bottom with no gap / 序列帧缩略图改为动态缩放：**高度固定（36px，更大更紧凑）、宽度随图像宽高比**（宽图宽缩略图、高图窄缩略图），用逐像素整数矩形缩放填满；去掉了每帧的边框，当前帧改用底色高亮替代，并收紧条高让图像**紧贴底部无间隙** |
| 📐 Canvas Size / 画布尺寸 | A "Canvas" button opens a small W/H popup (1..32) with separate Apply/Cancel — Apply resizes every frame / 「画布」按钮弹出 W/H 小窗（1..32）＋ 应用/取消，应用时对所有帧生效 |

### 🪟 全息显示器 HUD 模式（Phase 1）/ Monitor HUD Mode (Phase 1)

- **选项卡切换**：显示器设置面板新增 `[3D 模式] [HUD 模式]` tab——点击立即切换（服务端权威、`MonitorSettingsPacket` 广播、所见即所得）；HUD tab 提供面板宽/高/横纵偏移/距离 5 参数，沿用显式 Apply/Enter 契约。设计文档 [`docs/monitor-hud-mode-design.md`](https://github.com/y15173334444/create-schematic-compute/blob/main/docs/monitor-hud-mode-design.md) / Settings panel gains `[3D Mode] [HUD Mode]` tabs: clicking switches immediately (server-authoritative, broadcast via MonitorSettingsPacket, WYSIWYG); the HUD tab exposes panel width/height/lateral/vertical offsets and standoff distance under the explicit Apply/Enter contract.
- **世界内玻璃面板**：中心锚点玻璃 quad 按 `FACING` 对齐、距方块表面可调（≥0.05 防 z-fighting）、尺寸 0.1–10 方块、世界固定尺寸远小近大；内容复用现有 `TEXT`/`DATA`/`IMAGE`/`IMAGE_SEQUENCE` 节点，在 BER 主 pass **直接绘制**（纯官方接口：`PoseStack` + `MultiBufferSource` + `MonitorRenderTypes.SCREEN_PIXEL` + `Font.drawInBatch`，与 3D 模式同一套 Sodium/Iris 已验证管线；弃用离屏 FBO——Sodium/Veil 世界管线在 flush 时重置视口/裁剪，读回实证内容只落进纹理一角），玻璃自发光全亮（`0xF000F0`），任意视角不消失（裁剪包围盒覆盖玻璃体积） / In-world glass panel: center-anchored quad aligned to `FACING` with adjustable standoff (≥0.05 anti-z-fighting), 0.1–10 blocks, fixed world size (perspective-correct near/far); content reuses `TEXT`/`DATA`/`IMAGE`/`IMAGE_SEQUENCE` nodes drawn **directly in the BER main pass** (official interfaces only: `PoseStack` + `MultiBufferSource` + `MonitorRenderTypes.SCREEN_PIXEL` + `Font.drawInBatch` — the same Sodium/Iris-proven pipeline as 3D mode; the offscreen FBO was dropped because the Sodium/Veil world pipeline resets viewport/scissor at flush time, with readback proving content only landed in one corner of the texture); the glass is fully self-lit and never vanishes at odd angles (cull box covers the glass volume).
- **AR HUD Phase 2（共形俯仰梯 + 地平线，2026-08-19 首批）**：新增 `HUD_PITCH_LADDER` 节点（输入 pitch/roll + **透传输出**，玩家在图中自接 `ATTITUDE`/`VIEW_ANGLE`/公式/总线等任意源；刻度参数 range/interval，默认 **±90° 全姿态**——Sable 物理引擎四元数旋转、无万向锁）。刻度族贴世界水平面共形投影（§9.1：玩家相机 → 世界方向族 → 玻璃平面求交，t≤0/出界自动裁剪），地平线加粗高亮，pitch/roll 输入驱动绿色姿态标记。**普通组件（TEXT/IMAGE 等）可选「贴玻璃 / 贴世界」锚定模式**（世界绝对方向 yaw/pitch，创建时默认取玩家当前视线，`GraphNode` 独立字段 + NBT v5 迁移）；编辑器用固定相机模拟预览。数据 20Hz 随 `ClientboundGraphEvalPacket` 刷新。 / AR HUD Phase 2 (conformal pitch ladder + horizon, first batch 2026-08-19): new `HUD_PITCH_LADDER` node (inputs pitch/roll + passthrough outputs, wired by the player to any source — `ATTITUDE`/`VIEW_ANGLE`/formula/bus; ladder params range/interval, default **±90° full attitude** — Sable's quaternion physics has no gimbal lock). Ladder lines are world-horizontal direction fans projected conformally (§9.1: player camera → world direction fan → glass-plane intersection, t≤0/off-panel auto-clipped), the horizon is bold, and the pitch/roll inputs drive a green attitude marker. **Ordinary components (TEXT/IMAGE etc.) can choose on-glass / on-world anchoring** (world-absolute yaw/pitch, defaulting to the player's current view at creation; `GraphNode` fields + NBT v5 migration); the editor shows a fixed-camera mock preview. Data refreshes at 20Hz via `ClientboundGraphEvalPacket`.
- Phase 2 后续（航向带/速度矢量/其余 `HUD_*` 组件）随排期，见设计文档 §十三 / Remaining Phase 2 (heading tape, velocity vector, other HUD_* components) stays on the roadmap, see design doc §13.
- **近处屏幕 + 远处虚像画布（2026-08-21 定稿：顶点级深度锚定 + 玩家屏幕 4 边形遮罩）**：HUD 按真实原理实现——近处玻璃屏幕只画**边框**，内容画在**沿 -FACING（玩家面前）100 格的虚像画布**上（尺寸 ×100 保持角尺寸，内容恒定大小浮在远处 = 无限远聚焦）；画布世界固定（poseStack 局部坐标）→ **天然共形（贴世界）且不依赖玩家相机投影，Sable 结构天然支持**；俯仰梯 = 画布内姿态仪（tan 透视刻度 + pitch 平移地平线 + roll 旋转，`ladderCanvasY` 纯函数）；**深度锚定（顶点级几何实现，2026-08-21）**：顶点构造为 `V'=(fx·gz/fz, fy·gz/fz, gz)`——屏幕位置保持远处画布投影、深度 = 玻璃平面 gz（官方接口 + vanilla `position_color` shader，无自定义 shader——Veil 4.0 拦截自定义 ShaderInstance 绑定）→ **前方物体遮挡虚像、后方物体不遮挡**（真实 HUD 遮挡关系）；**玩家屏幕定位遮罩（4 边形）**：玻璃面板 4 角点从玩家眼睛投影到画布平面（`projectGlassCornersToCanvas`），内容（IMAGE 像素 / 俯仰梯刻度）经 Sutherland-Hodgman 凸裁剪（`clipPolyToQuad`）**只在玩家透过玻璃看到的 4 边形区域内显示**——「hud 只在玻璃上显示」，视线离开玻璃（画布不在玻璃投影内）内容消失；组件级剔除（逐像素/逐字符画布矩形裁剪、view-ray）已移除（GPU 省不了多少、CPU 开销大）；姿态数据 20Hz 客户端指数插值到 60fps。`ConformalProjectionTest` 覆盖 4 边形投影与裁剪纯函数 / Near screen + far virtual-image canvas (2026-08-21 final: vertex-level depth anchor + player-screen 4-gon mask): HUD follows the real principle — a near glass screen drawing only a **border**, with content on a **virtual-image canvas pushed 100 blocks along -FACING (in front of the player)** (size ×100 preserves angular size; content floats at constant size far away = infinite focus); the canvas is world-fixed (poseStack local frame) → **natively conformal (world-anchored) with no player-camera projection, Sable structures work natively**; the pitch ladder is an in-canvas attitude indicator (tan-perspective ticks, pitch-shifted horizon, roll rotation, pure `ladderCanvasY`); **depth anchoring (vertex-level geometric, 2026-08-21)**: vertices are built as `V'=(fx·gz/fz, fy·gz/fz, gz)` — screen position keeps the far-canvas projection while depth lands on the glass plane gz (official interfaces + the vanilla `position_color` shader; no custom shader — Veil 4.0 skips custom ShaderInstance binding) → **near objects occlude the image, far ones do not** (real-HUD occlusion); **player-screen-positioned 4-gon mask**: the glass panel's 4 corners project from the player's eye onto the canvas plane (`projectGlassCornersToCanvas`), content (IMAGE pixels / pitch-ladder ticks) is Sutherland-Hodgman-clipped (`clipPolyToQuad`) **to show only inside the 4-gon region seen through the glass** — "HUD shows only on the glass", looking away (canvas outside the glass projection) hides it; component-level culling (per-pixel/per-glyph canvas-rect clipping, view-ray) was removed (negligible GPU savings, heavy CPU cost); 20Hz attitude data is exponentially interpolated client-side to smooth 60fps. `ConformalProjectionTest` covers the 4-gon projection and clipping pure functions.

### 🖱️ 拖拽不再刷新全部节点状态 / Drag No Longer Refreshes All Node States

| Fix / 修复 | Description / 说明 |
|------------|-------------------|
| 🎨 视觉 op 不 bump 代际 / Visual ops no longer bump | `MOVE_NODE` / `SET_ZORDER` / `SET_COMMENT_TEXT` / `SET_COMMENT_COLORS` / `SET_COMMENT_SIZE` 是纯视觉 op（不进求值器、非 Monitor 显示内容），不再 `bumpGeneration()`——此前拖拽节流（50ms）下每个 op 都触发服务端 `recompileEvaluatorFull` → `runtimeState.clear()` 清零全部时序状态，并让客户端 `renderBg` 重建所有展开节点的编辑区 / These visual-only ops (never read by the evaluator, not monitor display content) no longer bump the generation — previously every throttled drag op (50ms) triggered a server full recompile → `runtimeState.clear()` wiped all sequential state and rebuilt every expanded EditState on clients. |
| 🧩 移除 applyOp 无条件父图 bump / No unconditional parent bump | `EditSessionRegistry.applyOp` 不再无条件 bump 父图代际——结构 op 由 `NodeGraph` 内部 bump，子图 op 靠子图代际陈旧检测 + `rebuildInputCache()` 兜底（仅引脚映射变化才 bump）/ No more unconditional parent-graph bump in `applyOp` — structural ops bump inside `NodeGraph`, sub-graph ops rely on per-subgraph staleness + `rebuildInputCache()` (bumps only when pin indices actually change). |
| 📡 Monitor 全量同步限流 / Coalesced monitor full sync | 显示拖拽的 `flagFullSync` 改为 `requestFullSync` + tick 内 `flushPendingFullSync` 合并冲刷——20Hz 的显示 op 只向非编辑者推 ~0.5Hz 全图 NBT（`FULL_SYNC_GRACE_TICKS` 节流字段此前是死代码）；编辑者仍经 op 广播实时同步 / Display-drag full syncs now coalesce via `requestFullSync` + tick `flushPendingFullSync` — 20Hz display ops push the full graph NBT at ~0.5Hz to non-editors (the `FULL_SYNC_GRACE_TICKS` throttle was dead code); editors still sync in real time via op broadcast. |
| 🧪 回归测试 / Regression tests | `OpGenerationTest`（10 例）：MOVE/视觉 op 不 bump、求值 op 仍 bump、结构变更仍 bump / 10 cases: move/visual ops don't bump, eval ops still bump, structural changes still bump. |

- 详见 [`docs/drag-state-churn-fix.md`](https://github.com/y15173334444/create-schematic-compute/blob/main/docs/drag-state-churn-fix.md)。

### 🔄 编辑不再清空时序/积分状态 / Edits No Longer Reset Sequential & Integral State

| Fix / 修复 | Description / 说明 |
|------------|-------------------|
| 🧬 重编译保留主图状态 / Recompiles preserve main-graph state | `recompileEvaluatorFull` 改为像子图状态一样**保存→剪除→恢复**主图五类状态（`pidState` 含 PID/ACCUMULATOR/INTEGRATOR、`delayQueues`、`flipflopStates`、`pulseTimers`、`debugTime`）——连线、添加节点、公式输入、参数编辑（如时序节点参数引脚动态调参）**不再清空时序与积分**；删除节点仅剪除该节点状态（含辅助槽位 `-(id+1)`/`id+100000`/`id+200000`）/ `recompileEvaluatorFull` now saves→prunes→restores the five main-graph state maps (like sub-graph state): wiring, adding nodes, formula input and param edits (e.g. dynamic tuning via sequential-node param pins) no longer wipe timing or integrals; removing a node prunes only its own state incl. auxiliary slots. |
| ⚙️ base/light 重编译同步修复 / base & light recompiles fixed too | `recompileEvaluator` / `recompileEvaluatorLight`（Sensor/ControlSeat/Radar/Monitor/SpeedProxy）不再 `pidState.clear()`——其他方块编辑时 PID 积分同样保留 / These no longer clear `pidState` — PID integrals survive edits on the other five block types too. |
| 🗑️ 语义保持 / Semantics kept | 整图替换（`loadGraphFromBytes`/关屏上传）仍显式清空（旧节点 ID 无意义）；编译按钮的 Latch/GATE/T_FLIPFLOP 当前状态回归初始（`params[1]`）不变 / Whole-graph replacement still clears explicitly (old IDs meaningless); the compile button's latch-state reset stays unchanged. |
| 🧪 测试 / Tests | `RuntimeState.pruneToAliveIds` + 3 例单测（保留含辅助槽/剪除死节点/空集合），全量测试通过 / prune logic + 3 unit tests; full suite green. |

### 🗂️ 添加节点菜单双列切换 / Add-Node Menu Two-Column Toggle

| Feature / 功能 | Description / 说明 |
|----------------|-------------------|
| 🖱️ 手动双列开关 / Manual two-column toggle | 菜单标题行右侧的状态文字按钮（`单列`/`双列`，双语），点击切换，开启时金色文字+金框高亮：开启后**所有展开分类**均按双列渲染；关闭时全部单列（三角函数不再自动双列）/ A state-label button on the menu title row (`1 Col`/`2 Col`, localized): click to toggle, gold text+border while active. When on, **every expanded category** renders in two columns; when off, all are single-column (Trig no longer auto-expands to two). |
| 📏 宽度常驻 / Persistent width | 双列开启时面板宽度**常驻双列**——即使没有任何展开分类也保持，切换不跳动 / While two-column is on, the panel width stays two-column even with nothing expanded — no resizing jump. |
| 🔍 搜索列数同步 / Search columns follow | 搜索模式扁平列表列数跟随开关（开启=2 列，关闭=1 列），不再硬编码 2 / The search flat list follows the toggle (on=2, off=1) instead of a hardcoded 2. |
| 🖱️ 搜索可滚动 / Search scrollable | 搜索模式 `totalH` 改为扁平列表真实高度——匹配多时出现滚动条，滚轮/拖拽可滚动（此前恒短、无法滚动）/ Search-mode `totalH` now reflects the flat-list height — the scrollbar appears for many matches and wheel/drag scrolling works (previously the list was always shorter than the panel and could not scroll). |
| 📐 布局联动 / Layout consistency | 面板宽度 / 高度封顶 / 滚动条 / 点击命中全部按当前生效列数计算，切换瞬间重排无错位 / Panel width, height cap, scrollbar and click hit-testing all follow the effective column count — no misalignment on toggle. |

### 📐 姿态传感器 ATTITUDE 修复 / Attitude Sensor ATTITUDE Fix

- **ATTITUDE 节点随方块朝向变换 / ATTITUDE now follows the block facing**：同一 Sable 结构上朝向不同的姿态传感器，ATTITUDE（pitch/roll）输出此前完全相同——旧实现只取**结构级**俯仰/横滚（`cachedSubPitch/cachedSubRoll`），与方块朝向无关。该结构级语义自 **v1.0.1 首次 Sable 集成（`ca514a1`）** 起遗留至今：当时及 1.1.x 只实测了 VELOCITY/FORWARD 等朝向类节点，ATTITUDE 从未对照方块朝向验证。修复后由「方块朝向 × 子世界旋转」的**局部基向量**推导方块自身世界姿态：前向向量决定俯仰（与 FORWARD 节点同公式、同符号约定），上向量绕前向轴的倾斜决定横滚。同一结构上侧向安装的传感器，其结构俯仰表现为自身横滚，输出不再相同。/ ATTITUDE (pitch/roll) outputs were identical across differently-faced sensors on the same Sable structure — the old implementation used structure-level pitch/roll only, ignoring the block facing. That structure-level semantics had been inherited since the first Sable integration at **v1.0.1 (`ca514a1`)**: only VELOCITY/FORWARD heading-type nodes were verified in 1.0.1–1.1.x, and ATTITUDE was never checked against block facings. Now the block's world-space attitude is derived from its local basis rotated by facing × sub-world pose: the forward vector yields pitch (same formula and sign convention as the FORWARD node) and the up vector's tilt around the forward axis yields roll. A sideways-mounted sensor reports the structure pitch as its own roll.
- **输出引脚不变 / Output pins unchanged**：仍为 2 引脚（pitch、roll），无图迁移。/ Still 2 pins (pitch, roll) — no graph migration.
- **纯函数可单测 / Pure-function testable**：新增 `SensorAttitudeMath.blockAttitude()`（零 Minecraft/Sable 依赖）+ `SensorAttitudeMathTest`（7 例，含实测数值锁定：结构 yaw=6.37°/pitch=19.03°/roll=-0.28° 时，面 WEST→(0.27,-19.03)、面 NORTH→(19.03,0.28)、面 SOUTH→(-19.03,-0.28)）。/ New `SensorAttitudeMath.blockAttitude()` (zero Minecraft/Sable deps) + `SensorAttitudeMathTest` (7 cases, locking measured values).
- **注意 / Note**：pitch 符号约定由旧的欧拉角约定改为前向仰角约定（与 FORWARD 一致）——面朝与结构相反方向的传感器 pitch/roll 符号可能翻转；依赖旧数值的现有图需复查。/ The pitch sign convention switched from the legacy Euler-angle convention to the forward-elevation convention (matching FORWARD) — sensors facing opposite the structure may flip sign; existing graphs relying on legacy values should be re-checked.
- **文档更正 / Docs correction**：VELOCITY / ACCELERATION 描述由「结构本地 / Structure-local」更正为「方块本地 / Block-local」——代码始终按方块 FACING 将结构运动分解到方块自身坐标轴（与 FORWARD/ATTITUDE 的朝向相关语义一致），文档此前与代码不符。/ VELOCITY / ACCELERATION descriptions corrected from "Structure-local" to "Block-local" — the code always resolves structure motion into the block's own axes via FACING (consistent with the facing-dependent FORWARD/ATTITUDE semantics); the docs previously contradicted the code.

### ⏱️ 保险节点长信号支持 / FUSE Long-Signal Support

- **长信号 = 脉冲发生器 / Held-high input = pulse generator**：FUSE（保险）此前只在输入**上升沿**触发一次（2 tick 脉冲 → 冷却），持续高电平期间冷却结束后不会再次触发，无法当脉冲发生器。现在输入**持续高电平**时，冷却结束后自动再触发（周期 ≈ 2 + cooldown），输入变低即停止；上升沿仍立即触发、冷却期间的新上升沿仍被忽略、输入在脉冲/冷却中途变低时当前一轮完整走完——旧行为完全兼容。/ FUSE previously fired only once on a rising edge (2-tick pulse → cooldown) and never re-fired while the input stayed high, so it could not act as a pulse generator. A held-high input now re-fires after each cooldown (period ≈ 2 + cooldown) and stops when the input drops; rising edges still fire immediately, new rising edges during cooldown are still ignored, and a drop mid-cycle lets the current cycle finish — fully backward compatible.
- **脉冲宽度修正 / Pulse-width fix**：文档与注释均写「2 tick 脉冲」，旧代码因计数器 off-by-one 实际只输出 1 tick——已修正为真正的 2 tick（触发 tick + 1）。/ The docs and comments claimed a "2-tick pulse" but an off-by-one in the pulse counter emitted only 1 tick — now a true 2-tick pulse (fire tick + 1).
- **回归测试 / Regression tests**：新增 `FuseLongSignalTest`（4 例）：上升沿 2 tick 脉冲+冷却、持续高电平循环脉冲（t0/t6/t12 触发）、中途变低当前轮走完+重武装、冷却期上升沿忽略+长信号再触发。/ New `FuseLongSignalTest` (4 cases): rising-edge 2-tick pulse + cooldown, held-high repeating pulses (fires at t0/t6/t12), mid-cycle drop completes then re-arms, cooldown-period rising edge ignored + held-high re-fire.

### 🔌 时序节点参数引脚修复 / Sequential-Node Param-Pin Fix

- **可连线编辑区参数引脚生效 / Wireable edit-area (param) pins now work**：DELAY/PULSE_EXTEND/LOOP/FUSE 等时序节点的参数引脚（可连线编辑区）此前连上信号**没有任何效果**——通用参数覆盖机制（连线值临时覆盖 `node.params`）只应用在 `eval()` 默认路径，时序节点走 `evalExt()` 直接读 `node.params`，从未应用覆盖。修复后 `evalExt` 顶部统一应用、尾部恢复（连线值只在该 tick 生效，不污染 EditBox/NBT，断开连线恢复默认）。/ The wireable edit-area (param) pins of sequential nodes (DELAY/PULSE_EXTEND/LOOP/FUSE) previously had **no effect** when wired — the generic override mechanism (wired values temporarily replace `node.params`) only ran in the `eval()` default path, while sequential nodes go through `evalExt()` and read `node.params` directly. `evalExt` now applies the override up front and restores afterwards (wired values last one tick only — the EditBox/NBT stay clean and un-wiring restores the default).
- **DELAY 入队移入求值器 / DELAY enqueue moved into the evaluator**：DELAY 的入队此前在方块实体（求值器外）读取 `params[0]`——参数恢复后读不到连线值。现入队并入 DELAY 求值分支（与子图一致），连线的 duration 即刻生效；BE 侧入队代码移除。/ The DELAY enqueue previously lived in the block entities (outside the evaluator), reading `params[0]` after the override was restored — so a wired duration never applied. The enqueue now lives inside the DELAY evaluation branch (matching sub-graphs); the BE-side enqueue was removed.
- **边界行为（维持现状）/ Edge behavior (unchanged)**：`<0`/`0` 一律钳制为 1（最小时长），非整数向零截断（2.9→2）；INTEGRATOR limit<0 输出恒 0、负 step 反向计数由玩家自行负责。/ Values `<0`/`0` clamp to 1 (minimum duration); non-integers truncate toward zero (2.9→2); INTEGRATOR limit<0 forces output 0 and negative step counts backwards — left to the player.
- **回归测试 / Regression tests**：新增 `SequentialParamPinTest`（3 例）：FUSE cooldown 连线（cd=5→周期 6）、PULSE_EXTEND duration 连线（3 tick）、LOOP count+interval 连线（count=2/interval=3）。/ New `SequentialParamPinTest` (3 cases): FUSE cooldown wired (cd=5 → period 6), PULSE_EXTEND duration wired (3 ticks), LOOP count+interval wired (count=2/interval=3).

### 🖥️ 显示器设置面板合并 + 统一细边框 / Monitor Settings-Panel Merge + Unified Thin Border

| Feature / 功能 | Description / 说明 |
|----------------|-------------------|
| 🔀 设置面板合并 / Merged settings panel | 3D 与 HUD 两组设置不再用 tab 切换——面板顶部一个 **HUD 模式复选框**（`hudMode` 布尔开关），下方 3D 8 项 + HUD 6 项字段**常显**，非激活组置灰禁用（保存时两组都写入，切回模式数值不丢）/ 3D and HUD settings no longer switch via tabs — a single **HUD-mode checkbox** (`hudMode` boolean) sits at the top with both field groups (3D 8 + HUD 6) always visible; the inactive group is greyed out (both groups are still saved, so values survive mode switches) |
| 📏 统一细边框 / Unified thin border | 3D 模式丢弃 0.04 方块粗边框（`drawBorderFace` 已删除），改用与 HUD 一致的 4 条 `addThickLine` 细线（≈1 像素）——两模式视觉一致 / 3D mode drops the 0.04-block border (`drawBorderFace` removed) for the same 4 `addThickLine` fine lines as HUD (≈1 px) — both modes now look identical |
| 🔍 虚像屏幕大小 / Virtual-image scale | 新增设置 `virtualImageScale`（虚像缩放系数，默认 1.0，范围 0.25–4.0）：只缩放 HUD 虚像**内容画布**，与物理玻璃面板（`panelSizeX/Y`）解耦——调大虚像时玻璃不变，超出玻璃视口的内容被 4 边形遮罩裁剪（透过玻璃看 HUD，物理正确）/ New setting `virtualImageScale` (default 1.0, range 0.25–4.0): scales only the HUD virtual-image **content canvas**, decoupled from the physical glass (`panelSizeX/Y`) — enlarging the image leaves the glass unchanged and content beyond the viewport is clipped by the 4-gon mask (physically correct through-glass view) |
| 💾 数据契约扩展 / Data-contract extension | `MonitorSettingsPacket` 增至 15 字段（`virtualImageScale` 追加在 HUD 组末尾）；BE 新增 NBT key `vis`——旧档缺省回落 1.0，无迁移 / `MonitorSettingsPacket` grows to 15 fields (`virtualImageScale` appended after the HUD group); new BE NBT key `vis` — legacy saves default to 1.0, no migration |
| 🧪 编解码回归测试 / Codec regression test | 新增 `MonitorSettingsPacketCodecTest`：15 字段编解码往返逐字段一致 + 字段流顺序断言（vis 位于 HUD 组之后）/ New `MonitorSettingsPacketCodecTest`: 15-field codec round-trip + byte-stream order assertion (vis sits after the HUD group) |

- 详见 [`docs/monitor-mode-settings-merge-plan.md`](https://github.com/y15173334444/create-schematic-compute/blob/main/docs/monitor-mode-settings-merge-plan.md)。

### 🎯 可编程变速箱 + 数控齿轮箱 / Programmable Transmission + CNC Gearbox

| Feature / 功能 | Description / 说明 |
|----------------|-------------------|
| ⚙️ 可编程变速器 / Programmable Transmission | 新方块 `programmable_transmission`：由节点图编程目标转速（`TX_OUT` 节点），经 `RotationPropagatorMixin` 以**绝对速度**传导到机械网络 / New block: programs an absolute target RPM from the node graph (`TX_OUT` node), conveyed to the kinetic network via `RotationPropagatorMixin` |
| 🎛️ 数控齿轮箱 / CNC Gearbox | 新方块 `cnc_gearbox`：离合 + 运动配额（quota）——速度由上游变速器决定，运动方块只做接合/脱开 / New block: clutch + motion quota — speed comes from the upstream transmission; the motion block only clutches |
| 📜 指令栈节点 / Command-stack nodes | `MOVE`（米）/ `ROTATE`（度）/ `WAIT`（tick）——触点上升沿入队；`CLUTCH` 保持常接合意图；`ENCODER` 报告位置/速度，带电平触发复位引脚 / Rising edge enqueues; `CLUTCH` keeps standing engagement; `ENCODER` reports position/velocity with a level-triggered reset pin |
| 🧩 参数引脚 / Param pins | 运动类节点输入成为可编辑参数（可选连线覆盖），与 CLAMP min/max 同机制 / Motion-category node inputs become editable params with optional wire override |
| 🧠 触发级内存 / Trigger-level memory | `nodeEdge` 状态跨重编译、BE 重建与存档重载存活 / survives recompiles, BE recreation and reloads |
| 🐛 修复 / Fixes | 负方向网络不再卡死运动配额（quota 永续楔死）/ negative-direction networks no longer wedge the motion quota forever |
| 📚 文档 / Docs | [`docs/programmable-gearbox-plan.md`](https://github.com/y15173334444/create-schematic-compute/blob/main/docs/programmable-gearbox-plan.md) · [`docs/programmable-gearbox-eval.md`](https://github.com/y15173334444/create-schematic-compute/blob/main/docs/programmable-gearbox-eval.md) · [`docs/programmable-gearbox-handoff.md`](https://github.com/y15173334444/create-schematic-compute/blob/main/docs/programmable-gearbox-handoff.md) · [`docs/graph-host-convergence-plan.md`](https://github.com/y15173334444/create-schematic-compute/blob/main/docs/graph-host-convergence-plan.md) |

### 💾 运行时状态跨存档重载存活 / Runtime State Survives World Reloads

| Fix / 修复 | Description / 说明 |
|------------|-------------------|
| 🗃️ 全量恢复 / Full restore | 存档写入的始终是完整运行时状态，但恢复侧长期只读回 `pidState` 一项——`delayQueues`、`flipflopStates`、`pulseTimers`、`debugTime`、`nodeEdge`、`subStates` 六类在每次重载后静默归零。现由 `RuntimeState.putAllFrom()` 单点全量恢复，继承线与 Kinetic 线共用同一入口 / Saves always wrote the full runtime state, but the restore side read back only `pidState` — six more categories silently reset on every world reload. `RuntimeState.putAllFrom()` now restores all seven in one place, shared by the inheritance and the kinetic line. |
| ⚠️ 触发误重触发 / Spurious re-trigger | 触发电平（`nodeEdge`）丢失会把"常高"信号误判为新的上升沿 → 存档重载或区块重载后 `MOVE`/`ROTATE`/`WAIT` 指令被反复重新入队（"输入一次指令后一直转"）。已修复并由回归测试守着 / Losing the trigger level re-fired held-high signals as fresh rising edges, re-enqueuing motion commands after every reload ("one command, spins forever"). Fixed and covered by regression tests. |
| 🧩 分叉抹平 / Divergence removed | Blueprint / ProgramComputer / Radar 原先各自在子类里补**互不相同**的恢复子集，其余四种 BE 只有 pid；现统一上提到基类，子类不再打补丁 / those three used to patch in **different** subsets while four other BEs got pid only; it now happens once in the base class. |
| ⚙️ Kinetic 线子图状态 / Kinetic-line sub-graph state **(行为变更)** | **可编程变速器 / 数控齿轮箱此前不恢复 `subStates`**——封装（ENCAPSULATION）内的 DELAY / LATCH / T_FLIPFLOP / PID 在存档重载或离合翻转导致的 BE 重建后归零。现与原生线一致恢复。**注意三线的性质不同**：原生线 Blueprint / ProgramComputer / Radar 原本就在恢复（保持行为），ControlSeat / Sensor / Monitor / SpeedProxy 为新增恢复，Kinetic 线为新增恢复（行为变更）/ The transmission and CNC gearbox used to skip `subStates`, so every timing node inside an encapsulation reset on world reload or on the BE recreation caused by a clutch flip. Now restored like the native line. **The three groups differ in kind**: Blueprint / ProgramComputer / Radar already restored it (behaviour kept), ControlSeat / Sensor / Monitor / SpeedProxy gain it, and the kinetic line gains it (a behaviour change). |
| ⏱️ 信号发生器相位 / Generator phase **(行为变更)** | ControlSeat / Radar / Sensor 改用 `recompileEvaluatorFull()`，`debugTime`（信号发生器相位）跨重编译保留，与 Monitor / SpeedProxy 的 light 路径对齐；老路径 `recompileEvaluator()` 已删除 / these three now keep the generator phase across recompiles, matching the light path used by Monitor / SpeedProxy; the legacy `recompileEvaluator()` path is gone. |
| 🧪 测试 / Tests | `RuntimeStateRestoreTest` 新增 7 例，含"只恢复 pid 必然重触发"的负例对照；全量 332 例通过 / 7 new cases including a negative guard that proves the old behaviour re-fires; full suite of 332 green. |

### 🔀 图宿主收敛 · 阶段 1（合并逻辑与回弹保护）/ Graph Host Convergence · Phase 1

| Change / 变更 | Description / 说明 |
|---------------|-------------------|
| 🧩 合并逻辑上提 / Merge hoisted | 7 个 BE 各自覆写的 `IMergeableBE.accept()` 上提到 `SyncedGraphBlockEntity`，类型特定字段改由新增的 `acceptTypeSpecific()` 钩子承载（与 `loadTypeSpecific` 同构）。七个近乎逐字相同的实现删去六份半，**纯去重**（唯一例外见下条）/ Six and a half near-verbatim copies removed; pure de-duplication except for the item below. |
| 📡 SpeedProxy 补发方块更新 **(行为变更)** | SpeedProxy 合并时原本**不**发送 `sendBlockUpdated`，客户端拿不到新的 `getUpdateTag`；现与其余六个 BE 对齐 / SpeedProxy used to skip it, so tracking clients never received a fresh update tag after a merge. |
| 🛡️ Radar 回弹保护恢复 **(行为变更，bug 修复)** | Radar 的 `loadAdditional` 原先**重复**执行图加载，且**不检查** pendingLocalOps / 像素绘制 / 显示拖拽 —— 等于关掉基类三道回弹保护：编辑器打开、正在绘画或拖拽元素时，服务端同步包会砸掉本地图（孤儿化 `pixelEdit.node` / `draggedDisplayNode`，表现为"图像变透明""拖拽不跟手"）。现已恢复三道护栏 / Radar used to re-load the graph with none of the three guards, so a server sync could clobber in-progress edits (wiped pixels, drags that stop following the cursor). |
| 🧭 回弹判定收敛 / Guard converged | 三道护栏的判定收敛到 `GraphHostOwner.isGraphReplaceBlocked(pendingLocalOps)` 一处，继承线与组合线（`GraphHost`）共用，不再各写一份导致判定漂移 / One shared implementation instead of two copies that could drift apart. |
| 🤝 跨变体合并保持 / Cross-variant merge kept | 上提后的类型判定允许基类 ↔ Sable 兼容变体（`compat/*BlockEntitySable` 四个子类不覆写 `accept`）双向合并，与旧 `instanceof` 语义一致 —— 整合包中途加装/移除 Sable 时两种 BE 会在同一世界共存 / Base ↔ Sable variant merges still work, matching the old `instanceof` semantics; both kinds coexist when Sable is installed or removed mid-game. |

### 🔀 图宿主收敛 · 阶段 3（薄壳化清理与归档）/ Graph Host Convergence · Phase 3

| Change / 变更 | Description / 说明 |
|---------------|-------------------|
| 🧹 编辑器保存路径统一 / Editor-save path unified **(行为变更，bug 修复)** | Blueprint / Radar / Monitor 三个各自为政的 `loadGraphFromBytes` 覆写删除/收缩，统一走引擎 `loadGraphFromBytes → loadEditorTag`。顺带修复三处分叉缺陷：**Radar** 缺 BUS 注销——编辑保存后旧图通道在 SignalBus 泄漏；**Blueprint / Radar** 缺子图/触发器状态清理——封装内时序跨编辑保存残留；**Monitor** 缺全量同步推送——保存后其他客户端的显示器图保持陈旧 / The three divergent loadGraphFromBytes overrides are gone in favor of the engine path (loadEditorTag). Three latent divergences fixed: Radar skipped BUS unregistration (SignalBus leak across editor saves), Blueprint/Radar skipped the sub-graph/flipflop clear (stale encapsulation timing), Monitor skipped the full-sync push (tracking clients kept a stale monitor graph). |
| 📻 flipflop 差分广播上提 / Flipflop diff hoisted | Blueprint / ProgramComputer 逐字一致的 30 行触发器差分广播块上提为引擎 `broadcastFlipflopDiff()`（基线随引擎），两个子类各删 30 行与两个基线字段 / The byte-identical 30-line flipflop diff-sync twins moved into GraphHost.broadcastFlipflopDiff() with the baselines; 30 lines and two fields gone from each subclass. |
| 🏛️ 契约单点 / One contract | `GraphHostOwner` 并入 `GraphBlockEntity`（唯一契约，引擎构造参数随之统一），`GraphHost` 沿用现名；17 个过渡委托桥已标 `@Deprecated` 并附迁移指南，按 BE 逐个直连后再删（日常维护，不再以计划文档跟踪）/ GraphHostOwner merged into GraphBlockEntity (the single contract, engine takes it as constructor parameter); GraphHost keeps its name; the 17 transitional bridges carry @Deprecated plus a migration guide. |

### 🐛 联测修复：配额换算与雷达同步 / Fixes from playtesting: quota conversion & radar sync

| Fix / 修复 | Description / 说明 |
|------------|-------------------|
| ⏱️ MOVE 配额换算 **(行为变更，bug 修复)** | `MotionQuota` 的米制换算多乘了 dt(0.05)：官方换算是 `speed/512` **每 tick**（Create 字节码核实：机械活塞以 `convertToLinear` 为每 tick 位移）。多出的因子让 MOVE 慢 20 倍——90 米 @64RPM 记账 0.00625 米/tick 要 12 分钟，体感即"触发指令后一直旋转"。现 90 米 @64RPM = 720 tick = 36 秒；编码器积分与配额共用同一常量，换算修复后两者保持一致 / The meters conversion carried an extra dt(0.05): the official rate is speed/512 per tick (verified against Create bytecode). MOVE was 20x slower - 90 m at 64 RPM took 12 minutes, reading as "spins forever". Now 36 s. Encoder and quota share the constant, so they stay consistent. |
| 📡 雷达目标推送门控 **(行为变更，性能修复)** | 雷达 tick 末尾**无条件**推送完整 BE NBT（含整张图）——自雷达诞生起如此。所有追踪客户端每 tick 重载图：编辑雷达时编辑器"图每次重建"的根因。现仅目标列表有变化时推送；blip 的实时数值本就走 EvalSnapshot 广播，空闲时零推送 / The radar's tick ended with an unconditional full-BE-NBT push (whole graph included) every tick since the radar was born - every tracking client reloaded the graph per tick, which read as "the radar graph rebuilds all the time" in the editor. Now gated on an actual target-list change; live blip values already flow via EvalSnapshot. |
| 🏗️ 字段委托引擎 / Fields delegated to the engine | 阶段 1 改写完成：基类只剩 `private final GraphHost host` + 同名访问器桥（`graph()` 等）+ 契约委托，7 个子类与全部外部类（屏幕 / 渲染器 / 包类）改走访问器与契约；引擎补 `recompileEvaluatorLight()` / `invalidateEvaluator()` / `adoptFrom()` / `getFlipflopStates()`，**GraphHost 成为两线唯一引擎实现**（净 −370 行）。三处次可见对齐已披露（服务端 NBT 加载立即强制重编译组合拳、快照广播补求值器空守卫、屏幕侧 `be.running = start` 改走 `setRunning()`），均可观察行为不变 / Phase-1 rewrite done: the base class keeps only a `private final GraphHost host` plus same-name accessor bridges and contract delegation; all external pokes moved onto the contract. The engine gains `recompileEvaluatorLight()` / `invalidateEvaluator()` / `adoptFrom()` / `getFlipflopStates()`, making **GraphHost the single engine implementation for both lines** (−370 lines net). Three sub-visible alignments disclosed, none observable. |
| 🏷️ 总线/频段命名实时同步 **(行为变更)** | 总线名与频段名的输入框此前既无 responder 也不走 enterActions —— 只在点**编译**或**关屏**时才批量上传，协作者看不到正在改的名字；而 PRIVATE / TEXT 等同类命名框一直是逐字符同步的，行为不一致。现停止输入约 0.5 秒（10 tick）即自动提交，仍走原本的定向 op 与 BusBandUploadPacket（不做整图上传）。**用防抖而非逐字符**：总线名提交要清旧频道全局数据、重评估冲突、按 BUS_IN/BUS_OUT 分别处理频段，逐字符会把 "abc" 打成 a→ab→abc 三个频道，对端 BUS_IN 还会反复跟着换频段定义；防抖一次只发最终值。编译/关屏的兜底提交保留 / Bus and band name boxes had neither responder nor enterActions — they were only uploaded on **compile** or **screen close**, so collaborators never saw a rename in progress, unlike the PRIVATE/TEXT name boxes which synced per keystroke. Now auto-committed ~0.5 s (10 ticks) after typing stops, still via targeted op / BusBandUploadPacket (no whole-graph upload). **Debounced, not per-keystroke**: committing a bus name clears the old channel's global data, re-evaluates conflicts and handles bands per direction, so per-keystroke would create a→ab→abc as three channels and keep swapping the peer's BUS_IN band definition; debouncing sends only the final value. The compile/close fallback stays. |
| 👻 AR HUD 鬼影溢出 **(bug 修复)** | 画布与字形顶点跑到相机后方（fz>0）时，锚定比例 `s=zAnchor/fz` 变负 → 顶点被**镜像**到屏幕对侧，quad 的一条边横扫整个屏幕。实测 **θ=60°**（普通斜视，远未到掠射）即触发：fz=+34、s=−0.08，而 s 钳制阈值是 ±1e4 —— 量级差五个数量级，完全拦不住（历史测试也注明"s 钳制不覆盖镜像剔除"）。现渲染前把几何裁到相机平面，**留 1 格边距**：裁到 fz=0 会让边界顶点正好落在相机平面上 → s=zAnchor/0=−Infinity → 又被镜像。全部顶点在相机前方时短路，逐像素零额外开销 / When canvas or glyph vertices fall behind the camera (fz>0), the anchor ratio `s=zAnchor/fz` goes negative and the vertex is **mirrored** across the screen, stretching one quad edge over the whole view. Measured at **θ=60°** — an ordinary oblique view, nowhere near grazing: fz=+34, s=−0.08, while the s clamp sits at ±1e4, five orders of magnitude away (the older test even noted "s clamp does not cover mirror rejection"). Geometry is now clipped to the camera plane before emission, **with a 1-block margin**: clipping to fz=0 puts the boundary vertex exactly on the camera plane → s=zAnchor/0=−Infinity → mirrored again. Short-circuits when everything is in front, so per-pixel cost stays zero. |

</details>

<details>
<summary><b>v1.2.4.1</b> — 回归审计 · 总线系统 · 封装状态 · 公式一致性 · Sable 加固 / Regression Audit · Bus System · Encapsulation State · Formula Consistency · Sable Hardening</summary>

### 🔍 回归审计 + 总线系统 / Regression Audit + Bus System

- **回归审计 / Regression audit**：对 `1915202` 起的改动做全量业务逻辑审计（6 路并行 + 对抗验证），修复雷达红石输出失效、传感器子关卡姿态误用、flipflop 同步风暴、子图状态泄漏、Sable 专用服务器反射错误等。/ Full business-logic audit of changes since `1915202` (6 parallel + adversarial verification): fixed radar redstone output, sensor sub-level pose misuse, flipflop sync storm, sub-graph state leak, and Sable dedicated-server reflection errors.
- **总线跨方块编辑修复 / BUS cross-block editing fixes**：创建同名 BUS_OUT 不再自动同步覆盖原频道 owner 的 band 定义；改名保留自身 band 与连线（BUS_IN 采用新频道 band）；点击空白处提交频道名；修复反复编译+运行导致 BUS_IN 读 0（`loadGraphFromBytes` 的 generation 冲突）；自身 BUS_OUT 不再误报冲突。/ Creating a same-name BUS_OUT no longer overwrites the original owner's band definitions; rename preserves the node's bands and connections (BUS_IN adopts the new channel's bands); clicking empty space commits the channel name; fixed BUS_IN reading 0 after repeated compile+run (generation conflict in `loadGraphFromBytes`); a node's own BUS_OUT no longer reports a false conflict.
- **编译 BUS 断线修复 / Compile-time BUS disconnection fix**：移除 `loadGraphFromBytes` 中 `cleanupBusChannels()`，防止编译时向客户端广播空频段导致连线永久丢失。/ Removed `cleanupBusChannels()` from `loadGraphFromBytes` to stop empty band syncs from permanently deleting connections.
- **编辑器冲突检测 / Editor conflict detection**：修复 `crossConflict` 死代码（客户端从不显示跨方块冲突），改名/删除时 `localBusNames` 保持同步。/ Fixed the `crossConflict` dead code (cross-block conflicts were never shown client-side); `localBusNames` stays in sync on rename/delete.

### 📦 封装节点 / Encapsulation Nodes

- **封装节点输出假数值 / Fake outputs from encapsulated nodes**：修复 `recompileEvaluatorFull()` 的 `runtimeState.clear()` 清除子图时序组件状态（DELAY/LATCH/flipflop 等），导致封装内部计算偏差。1.2.3 的子评估器缓存掩盖了此问题，修复缓存失效后暴露。现在重编译前保存并恢复 `subStates`。/ `runtimeState.clear()` no longer wipes sub-graph sequential state (DELAY/LATCH/flipflop etc.) — `subStates` are saved and restored before a full recompile; previously masked by the v1.2.3 sub-evaluator cache.
- **封装内时序节点编辑区状态 / In-encapsulation sequential node state**：扩展 `RuntimeStateSyncPacket` 携带子图 flipflop 状态，编辑器在子图内显示正确的时序节点实时状态。/ `RuntimeStateSyncPacket` now carries sub-graph flipflop state so the editor shows correct live sequential state inside encapsulation.
- **子图展开状态初始化 / Sub-graph expansion state init**：修复进出封装节点后 `expandedInitDone` 未重置，导致子图展开节点不恢复。/ Fixed `expandedInitDone` not resetting when entering/leaving encapsulation, so expanded sub-graph nodes fail to restore.

### 📝 公式编辑器 / Formula Editor

- **公式节点双缓存统一 / Unified formula cache**：移除 `GraphEvaluator.scriptCache`，改为 `node.cachedScript` 单一真相源，消除引脚解析与求值的缓存漂移。/ Removed `GraphEvaluator.scriptCache`; `node.cachedScript` is now the single source of truth, eliminating pin-resolution vs. evaluation cache drift.
- **公式节点清空回弹 A+B / Formula empty-value bounce-back A+B**：`createEditState` 不再强制默认值。/ `createEditState` no longer forces default values.
- **公式编辑器光标与选区 / Formula editor caret & selection**：MLE 图空间坐标转换 + 方向键折叠选区。/ MLE graph-space coordinate conversion + arrow-key selection folding.
- **V3→V4 迁移 / V3→V4 migration**：`GraphMigration` 复用 `GraphNode.inputPinId`/`outputPinId`，支持旧版动态引脚连线保留。/ Migration reuses `GraphNode.inputPinId`/`outputPinId` so legacy dynamic-pin connections are preserved.

### 🔄 Sable 兼容 / Sable Compat

- **Sable 兼容层加固 / Compat layer hardening**：反射访问改编译期桥，专用服务器不再触发 ClientLevel 加载错误；雷达 Sable 结构扫描在专用服务器正常；无 Sable 环境安全回退。/ Reflection access replaced with a compile-time bridge — dedicated servers no longer trigger ClientLevel load errors; radar Sable structure scanning works on dedicated servers; safe fallback without Sable.
- **Sable 重连 / Sable reconnection**：`sable$getLoadingDependencies` 通过 `getPlot(chunkPos)` 安全返回子世界引用，修复重进存档后 Sable 节点失效。/ Safe sub-level reference via `getPlot(chunkPos)` — Sable nodes survive world reload.

### 🛠️ 其他修复 / Other Fixes

- **雷达锁定 / Radar lock**：空中 blip 锁定不再被方块 UI 拦截；移除编辑会话成员校验（锁定是使用操作）。/ Air-blip lock is no longer intercepted by the block-UI; removed the edit-session membership check (locking is a use operation).
- **数值输入回弹 / Numeric input bounce-back**：编辑器打开时跳过 NBT 全量图替换，防止服务端同步覆盖本地编辑值。/ Skipped full-graph NBT replacement on editor open so server sync cannot overwrite local edits.
- **临时视角跨方块污染 / Temp camera-view contamination**：改为按 `BlockPos` 存储。/ Temp camera view is now stored per `BlockPos`.
- **中途加入玩家获取完整图 / Mid-game joiners get the full graph**：`loadAdditional` 守卫由 `graphReady` 锁存改为本地待 ACK 编辑计数（`pendingLocalOps`）—— 加入者无本地编辑时总是应用服务端权威图；活跃编辑者仍受回弹保护（仅在发送 op 且未 ACK 期间跳过替换）。/ The `loadAdditional` guard now uses a pending-local-op counter instead of the `graphReady` latch — joiners with no local edits always apply the authoritative graph, while active editors keep bounce-back protection (replacement skipped only while ops are un-ACKed).

</details>

<details>
<summary><b>v1.2.4</b> — Multiplayer Collaboration + Debug Toolchain + Formula Editor UX / 多人协作 + 调试工具链 + 公式编辑器体验</summary>

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

### 🔗 Stable PinId Refactoring / 稳定引脚ID重构
Connections now bind to **stable string pin identifiers** instead of positional integer indices. Pin insertion, deletion, or reordering no longer breaks existing connections — they follow the pin by name.

| System / 系统 | pinId Source / pinId 来源 | Before / 修复前 | After / 修复后 |
|---------------|--------------------------|----------------|---------------|
| FORMULA inputs | Variable name (e.g. `A`, `B`, `x`) | Adding/removing variables shifted pin indices — connections broke or pointed to wrong pins | Connections follow variable names; `ensureScriptParsed()` eliminates lazy-parse race conditions |
| FORMULA outputs | `@output` label (e.g. `result`, `angle`) | Output reordering broke downstream connections | Connections track output labels; `"out0"` default handled correctly |
| ENCAPSULATION I/O | Sub-node ID (sorted by Y, then ID) | Dragging ENCAP_INPUT/OUTPUT nodes changed pin order — external connections silently shifted | pinId = sub-node ID, invariant under drag; parent cache rebuilt after sub-graph structural edits |
| BUS bands | Band name (e.g. `band_0`, `band_1`) | Inserting/removing/reordering bands cleared all connections or caused index drift | Only connections on actually-deleted bands are removed; reordered bands preserved |

**Key changes:**
- `NodeConnection` gains `fromPinId` / `toPinId` fields; `save()` / `load()` backward-compatible
- `GraphNode` adds `inputPinIndex(id)` / `outputPinIndex(id)` / `inputPinId(i)` / `outputPinId(i)` — pinId↔index resolution per node type
- `NodeGraph.rebuildInputCache()` resolves all pinIds to current indices, prunes stale connections
- `GraphMigration` V3→V4: one-time NBT upgrade converting integer pins to stable pinIds for FORMULA, ENCAP, BUS, and generic nodes (recursive into sub-graphs)
- `GraphEvaluator` BUS evaluation and ENCAP pin injection now match by pinId rather than cache position
- `BusChannelHelper.syncBandsFromServer` only disconnects actually-removed bands (by name), preserving reordered bands
- `NbtVersions.DATA_VERSION` bumped 3→4
- Eliminates ~200 lines of REWIRE/reconnect complexity from the v1.2.5 roadmap — pin reordering is now free

Related docs: [`docs/v1.2.4-pin-id-stability-plan.md`](https://github.com/y15173334444/create-schematic-compute/blob/main/docs/v1.2.4-pin-id-stability-plan.md)

### 🧠 Relay Nodes / 继电器节点
Two new logic nodes for conditional signal routing — available in both Blueprint and Program Computers.

| Node / 节点 | Description / 说明 |
|-------------|-------------------|
| Relay A / 继电器A | SPDT (双掷) — 3 inputs (A, B, Contact), 2 outputs. Contact ≤0.5 → A输出=A, B输出=0; Contact >0.5 → A输出=0, B输出=B. Mutually exclusive throws like a physical relay. |
| Relay B / 继电器B | SPST (单掷) — 3 inputs (A, B, Contact), 1 output. Contact >0.5 → out=B; else → out=A. Merged single-throw variant. |

Both use the standard `>0.5` threshold consistent with `BOOL`/`GATE`/`OR`. No parameters, pure combinatorial logic — compatible with multiplayer collaboration and encapsulation out of the box.

### 🪑 Sable Sub-Level Control Seat Camera / Sable 子关卡控制座椅相机
Two camera modes for Control Seat riders inside Sable rotating structures. Camera orientation computed client-side from block FACING + Sable render-pose quaternion — bypasses entity yaw sync (unreliable for `retain_in_sub_level` entities).
Sable 旋转结构上控制座椅的两种相机模式。座椅世界朝向由客户端根据方块 FACING + Sable 渲染姿态四元数实时计算，不依赖 entity yaw 同步。

| Mode / 模式 | Camera / 相机 | Output / 输出 |
|-------------|--------------|---------------|
| **FIXED** (default) | Locked to seat world orientation (yaw + pitch). `ControlSeatCameraMixin` disables Sable camera rotation to prevent double-rotation. / 锁定到座椅世界朝向（偏航+俯仰） | Joystick `mx/my` from raw mouse delta / 摇杆来自鼠标增量 |
| **VIEW_DIFFERENCE** | Free camera, mouse-controlled. Sable does not rotate the view. / 自由相机，鼠标控制 | `vy = playerYaw - seatWorldYaw`, `vp = playerPitch - seatWorldPitch` |

- `ControlSeatBlockEntitySable`: fixed missing `setYRot()` — entity yaw now properly updates both `yRot` and `yRotO`. / 修复缺失的 setYRot()。
- `ControlSeatEntity`: manual `setPos` skipped inside sub-levels (Sable handles positioning). / 子关卡内跳过手动 setPos。
- `ControlSeatCameraMixin`: registered in `required:false` sable mixin config — silently skipped without Sable. / 无 Sable 时静默跳过。

### 🎮 MOUSE_JOYSTICK Absolute Mode / 鼠标摇杆绝对值模式
Per-node toggle in the edit panel switches between **incremental** (direct mouse delta, default) and **absolute** (accumulated stick position with memory, clamped to `[-1,1]`).
编辑区提供每节点独立开关，在增量（默认，鼠标位移即输出）和绝对值（累积摇杆位置，停手保持）之间切换。

- Uses `TOGGLE_BOOL` op pipeline — server-authoritative, undo-supported, multiplayer-synced. / 复用 BOOL 开关流水线。
- Absolute accumulation uses `ABS_SCALE=1/6` per tick for smooth control. / 绝对值每 tick 累积系数 1/6。
- Stick position persists across GUI open/close. / 开菜单再关闭位置保持。
- Both x and y axes accumulate independently. / X/Y 轴独立累积。

### 🔧 Signal Generator Auto-Scale Fix / 信号发生器自动缩放修复
- **Root cause**: `computeVisibleRange()` used fixed ±5 clipping, squashing large-range formulas (e.g. `x*360`) into a ~6-unit Y window while rendering used raw values → curve painted to chart corners looking like an inverse-proportional function.
- **Fix**: Replaced ±5 hard clipping with **percentile-based robust range** (p1–p99). Only extreme outliers (|v| ≥ 1e6) and NaN/Inf are filtered. Both `DEBUG_SIGNAL_GEN` and `DEBUG_PROBE` charts use the same logic.
- **Y-axis range label**: Chart top-right now shows `min … max` (e.g. `0.0 … 360.0`) so the scale is immediately visible.
- **Cache**: Formula compilation cache (`debugFormulaRpn`) properly invalidated on formula edits — chart refreshes instantly.

### 🎨 Formula Editor UX / 公式编辑器体验

**Syntax Highlighting / 语法高亮** — Real-time token-based colouring with 9 categories: functions (yellow), constants (pink), identifiers (cyan), numbers (orange), operators/parens (grey), comments (green), @output/assignment (purple), unknown (red underline). Token cache avoids per-frame re-parsing. / 实时词法彩色标注，9 种分类：函数（黄）、常量（粉）、标识符（青）、数字（橙）、运算符/括号（灰）、注释（绿）、@output/赋值（紫）、未知（红色下划线）。Token 缓存避免每帧重复解析。

**Autocomplete Popup / 自动补全候选框** — Type identifier characters to trigger filtered dropdown (functions, named constants, current variables); type `@` for immediate `@output`. Keyboard: `↑↓` navigate, `Tab`/`Enter` accept, `Esc` dismiss. Mouse: click any candidate to accept. Popup renders **above all pins** (z-layer C=5.5) with **zoom-aware scaling** — text and layout scale proportionally with graph zoom. Deleted variables immediately disappear from suggestions. / 输入标识符触发过滤候选框；输入 `@` 立即建议 `@output`。键盘导航/接受/关闭，点击候选项接受。候选框渲染在**所有引脚上方**（C=5.5 层），支持**缩放感知**。删除变量立即从候选消失。

**Real-Time Validation / 实时校验** — Red `⚠` badge on FORMULA node title bar; hover for tooltip list. Checks: bracket matching, unknown function, wrong arity, invalid assignment (errors), duplicate outputs, @output without identifier (warnings). Red border on the edit box when errors present. Validation cached after NBT reload to avoid per-frame re-parse. / 红色 ⚠ 徽章 + 悬停工具提示。校验：括号匹配、未知函数、参数数错误、无效赋值（错误），重复输出名、@output 缺变量（警告）。输入框红色边框。NBT 重载后缓存避免每帧重解析。

**Named Constants / 命名常量** — `(PI)` and `(E)` in grouping parentheses are literal constants (π / e). Bare `PI`/`E` or inside function calls like `sin(PI)` are variable references (create input pins). Consistent across `extractVariables()`, `compile()`, and `tokenize()`. / `(PI)`/`(E)` 在分组括号内为字面常量。裸 `PI`/`E` 或函数调用内 `sin(PI)` 视为变量。三种解析入口行为一致。

**Robustness / 健壮性** — Mouse drag selection restored (hlPos sync scoped). SET_FORMULA self-skip prevents EditState recreation → no focus loss during typing. Formula responder re-fetches graph node each keystroke (handles NBT sync between keystrokes). Connection cleanup corrected to use `inputs()`/`outputs()` clamps. Screen-width hardcoded 1920 replaced with `getGuiScaledWidth()`. Null safety in suggestion filtering. / 拖拽选区修复、SET_FORMULA 自跳、按键间图引用重获取、连线清理用 clamp 值、屏幕宽度动态获取、候选过滤 null 防护。

**Tests / 测试** — 27 new unit tests covering tokenize, extractVariables, compile/evaluate, validate, parseScript, countFunctionArgs, and edge cases. / 27 个新单元测试。

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

- 📱 Portable Terminal — Handheld remote editor, scan 1-128 blocks / 便携终端
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
