# Create: Schematic Compute

[![GitHub](https://img.shields.io/badge/GitHub-y15173334444/create--schematic--compute-blue?style=flat-square&logo=github)](https://github.com/y15173334444/create-schematic-compute)
[![License](https://img.shields.io/badge/License-MIT-green?style=flat-square)](LICENSE)
[![Version](https://img.shields.io/badge/Version-1.1.3-blue?style=flat-square)](https://github.com/y15173334444/create-schematic-compute/releases)
[![NeoForge](https://img.shields.io/badge/NeoForge-21.1.233-orange?style=flat-square)](https://neoforged.net/)
[![Create](https://img.shields.io/badge/Create-6.0.10-brightgreen?style=flat-square)](https://www.curseforge.com/minecraft/mc-mods/create)

---

<p align="center">
  <b>🎮 Six Programmable Blocks with a Visual Node-Based Programming System</b><br>
  <i>Drag, connect, and build logic — just like Unreal Engine Blueprints or Blender Geometry Nodes!</i><br>
  <i>Created by <b>StarryNight_Luo</b> (y15173334444)</i>
</p>

---

## 🇬🇧 English

### What is Create: Schematic Compute?

**Create: Schematic Compute** is a **Create mod addon** that introduces **six programmable blocks** with a **visual node-based programming system**. Instead of writing complex redstone circuits or struggling with command blocks, you simply drag and connect nodes to build logic — just like in Unreal Engine's Blueprint system or Blender's Geometry Nodes.

Each computer has its own internal node graph that runs at **20Hz (every game tick)**, making it suitable for real-time control applications.

---

### Blocks

#### 🖥️ Holographic Monitor
A **3D floating display block** that renders node graph output as a virtual screen in the world.

- **Display Nodes** — TEXT, DATA, IMAGE, IMAGE_SEQUENCE for visual output
- **16×16 Pixel Editor** — Built-in pixel art editor with multi-frame animation support
- **3D Positioning** — Freely position and rotate the floating screen in world space (X/Y/Z + Roll/Pitch/Yaw)
- **Signal-Driven Movement** — Drive IMAGE/IMAGE_SEQUENCE position (X/Y) and rotation via input signals with per-axis move scale, rotation scale, and invert toggles
- **Redstone Input** — Read signals from Create's Redstone Link network (shared frequency)
- **Real-time Preview** — GUI display mode with WYSIWYG editing of layout, scale, and rotation
- **Custom Model** — Full Blockbench custom model support

#### 🖥️ Blueprint Computer
Control Create's **Redstone Link network** through visual programming.

- **Redstone Input** — Reads signals from Create's Redstone Link network using frequency items
- **Redstone Output** — Writes computed signals back to the Redstone Link network
- **Private Signal Output** — Transmits float values across named channels to other computers
- **Private Signal Input** — Reads float values from named channels

#### ⚡ Speed Proxy Controller
Directly control the target RPM of Create's **Speed Controller** blocks on adjacent faces.

- **Speed Control** — Sets the RPM of nearby Speed Controllers (-256 ~ 256 RPM)
- **Private Signal Input** — Reads float values from named channels for cross-computer coordination

#### 🔌 Program Computer
A **sequential logic computer** for timing, counting, and pulse control applications.

- **Redstone I/O** — Communicates through Create's Redstone Link network
- **Dedicated sequential nodes**: Delay, Latch, T Flip-Flop (configurable default), Pulse Extender, Loop, Safety Timer, Accumulator, Continuous Integrator

#### 🪑 Control Seat
A **sit-able controller seat** with real-time keyboard, mouse, and gamepad input capture.

- **58 assignable keys** — Bind any key via click-to-bind UI
- **Two input modes**: Joystick (mouse delta) and View Angle (player rotation difference)
- **Gamepad support** — Dual-stick, 15 buttons, analog triggers (LT/RT) via GLFW gamepad API
- **Sable physics compatible** — Entity yaw syncs with sable sublevel rotation
- **Smooth mode transitions** — No view jump when switching between Joystick and View Angle modes
- **Press `~` to dismount**, **`TAB` to switch input mode**, **`ESC` for pause menu**

#### 📐 Attitude Sensor
Reads the orientation of sable physics structures through a node-based graph.

- **ATTITUDE node** — Outputs pitch and roll from the sublevel's logical pose
- **FORWARD node** — Outputs the world-space forward yaw/pitch of the structure
- **WORLD_VIEW node** — Reads the player's absolute view direction when seated
- **POSE_CONVERT node** — Converts pitch/yaw/roll between coordinate conventions

---

### Node Reference (56 Types)

| Category | Nodes |
|----------|-------|
| **Values** | CONST, Redstone Input, Private Signal Input |
| **Math** | Add, Subtract, Multiply, Divide, Modulo, Power (A^B), Root (B-th Root), Absolute Value, **Round (N Decimals)**, Comparison Router (\|A-B\|), Ceil, Floor, **Formula** |
| **Logic** | Greater Than, Less Than, **Greater Than or Equal**, **Less Than or Equal**, Equals, **OR Gate**, Bool (Toggle), Gate |
| **Control** | PID Controller, Power PID, Clamp, Map Range |
| **Output** | Redstone Output, Private Signal Output, Speed Control |
| **Display (Monitor only)** | TEXT, DATA, IMAGE, IMAGE_SEQUENCE |
| **Sequential** | Delay, Latch, T Flip-Flop, Pulse Extender, Loop, Safety Timer, Accumulator, **Continuous Integrator** |
| **Input Ctrl** | KEYBOARD (58 keys), Mouse Joystick (X/Y), View Angle, Mouse Button (L/R), Gamepad Joystick (LX/LY/RX/RY), Gamepad Button (15 buttons), **Gamepad Trigger (LT/RT)** |
| **Sensor** | World View (yaw/pitch), Attitude (pitch/roll), Forward (yaw/pitch), **Acceleration (X/Y/Z)**, Pose Convert (3-in 2-out), Split |

#### Detailed Node Table

##### Values
| Node | Inputs | Output | Params | Description |
|------|--------|--------|--------|-------------|
| CONST | - | float | value | Outputs a constant value |
| REDSTONE_IN | - | signal | frequency item ×2 | Reads from Redstone Link network |
| PRIVATE_IN | - | val | channel | Reads float from named channel |

##### Math
| Node | Inputs | Output | Description |
|------|--------|--------|-------------|
| ADD | A, B | float | A + B |
| SUB | A, B | float | A - B |
| MUL | A, B | float | A × B |
| DIV | A, B | float | A ÷ B (returns 0 if B=0) |
| MOD | A, B | float | A % B |
| POW | A, B | float | A ^ B (A to the power of B) |
| ROOT | A, B | float | B-th root of A (returns 0 if B=0) |
| ABS | in | float | Absolute value of input |
| ROUND | in | float | decimals | Round to N decimal places (default 2) |
| Comparison Router | A, B | A, B | A≥B → A port outputs A-B, else B port outputs \|B-A\| |
| CEIL | in | int | Round up to nearest integer |
| FLOOR | in | int | Round down to nearest integer |
| FORMULA | var(A-Z) | float | formula | Custom math expression, auto-creates input pins per variable (e.g. `AB*2+Speed`) |

##### Logic
| Node | Inputs | Output | Params | Description |
|------|--------|--------|--------|-------------|
| GT | A, B | bool | - | A > B → 1 |
| LT | A, B | bool | - | A < B → 1 |
| GE | A, B | bool | - | A >= B → 1 |
| LE | A, B | bool | - | A <= B → 1 |
| EQ | A, B | bool | - | A = B → 1 |
| OR | A, B | bool | - | A > 0.5 or B > 0.5 → 1 |
| BOOL | in | bool | inverted | in > 0 → 1, ≤ 0 → 0; inverted=1 flips output |
| GATE | val, Open, Close, Tog | out | default | val passes thru when open; Open/Close set state, Tog toggles |

##### Control
| Node | Inputs | Output | Params | Description |
|------|--------|--------|--------|-------------|
| PID | SP | ctrl | kp, ki, kd, scale | Classic PID, output 0~16, I-term resets on zero error (anti-windup) |
| PID_POWER | SP, base | power | kp, ki | PID with base power input, ideal for minimum output maintenance |
| CLAMP | In, Min, Max | float | - | Clamp input between Min and Max |
| MAP | In, InMin, InMax, OutMin, OutMax | float | - | Map input range to output range |

##### Output
| Node | Inputs | Output | Params | Description |
|------|--------|--------|--------|-------------|
| REDSTONE_OUT | In | - | frequency item ×2 | Writes signal to Redstone Link network (clamped 0~15) |
| PRIVATE_OUT | val | - | channel | Writes float to named channel |
| SPEED_CTRL | speed, dir | rpm | - | Sets Speed Controller RPM; dir>0.5 reverses direction |

##### Display (Monitor only)
| Node | Inputs | Output | Params | Description |
|------|--------|--------|--------|-------------|
| TEXT | - | - | text, color | Displays text content |
| DATA | val | - | color | Displays input float value |
| IMAGE | X, Y, rotation | - | moveScaleX/Y, rotationScale, invertX/Y | 16×16 pixel image, signal-driven position + rotation |
| IMAGE_SEQUENCE | X, Y, frame, rotation | - | moveScaleX/Y, rotationScale, invertX/Y, frames | Multi-frame animation, signal-driven position + rotation + frame select |

##### Sequential (Program Computer only)
| Node | Inputs | Output | Params | Description |
|------|--------|--------|--------|-------------|
| DELAY | in | out | ticks | Delays output by N ticks |
| LATCH | S, R | q | - | S≥1 sets, R≥1 resets, holds value |
| T_FLIPFLOP | in | tog | - | Toggles output on rising edge |
| PULSE_EXTEND | in | pulse | ticks | Extends input pulse by N ticks |
| LOOP | in | clk | count, interval | Fires pulse every interval tick, repeats count times |
| FUSE | in | pulse | cooldown | Trigger → 2-tick pulse → cooldown N ticks |
| ACCUMULATOR | +, - | val | step | Rising-edge triggered; + adds step, - subtracts step |
| INTEGRATOR | +, -, clear | val | step, interval, limit | Continuous integration every N ticks; both + and - active → hold; clear resets to 0; clamped [0, limit] |

##### Input Ctrl (Control Seat only)
| Node | Inputs | Output | Params | Description |
|------|--------|--------|--------|-------------|
| KEYBOARD | - | 1/0 | key | Bindable keyboard key (58 keys), outputs 1 when pressed |
| MOUSE_JOYSTICK | - | X, Y | - | Mouse delta output -1~1 (joystick mode) |
| VIEW_ANGLE | - | pitch, yaw | - | Player view angle delta (view angle mode) |
| MOUSE_BUTTON | - | L, R | - | Left/right mouse button state |
| GAMEPAD_JOYSTICK | - | LX, LY, RX, RY | - | Dual-stick gamepad axes -1~1 |
| GAMEPAD_BUTTON | - | 1/0 | button | Gamepad button (15 buttons), click-to-bind via frame-polling |
| GAMEPAD_TRIGGER | - | LT, RT | - | Gamepad analog triggers (0.0 ~ 1.0) |

##### Sensor (Attitude Sensor only)
| Node | Inputs | Output | Params | Description |
|------|--------|--------|--------|-------------|
| WORLD_VIEW | - | yaw, pitch | - | Player absolute world view direction |
| ATTITUDE | - | pitch, roll | - | Sublevel attitude (pitch/roll) |
| FORWARD | - | yaw, pitch | - | Sublevel forward direction in world space |
| ACCELERATION | - | X, Y, Z | - | Structure-local acceleration (fwd/back, up/down, left/right), computed at 20Hz from velocity |
| POSE_CONVERT | pitch_a, yaw_a, roll | pitch_b, yaw_b | - | Pose conversion (3-in 2-out) |
| SPLIT | in | +out, -out | - | Split positive/negative: positive→+out, negative→\|-out\|

---

### Featured Algorithm Nodes

| Node | Description |
|------|-------------|
| **PID Controller** | Classic PID algorithm, output 0~16, I-term resets on zero error (anti-windup) |
| **Power PID** | PID with base power input, ideal for maintaining minimum output |
| **Comparison Router** | A≥B → A port outputs A-B, else B port outputs \|B-A\|. Smart signal routing |
| **Pulse Extender** | Extends input pulse by N ticks |
| **Loop** | Fires pulses every interval ticks, repeat count times |
| **Formula** | Custom math expressions with multi-letter variable names, auto-creates input pins |

---

### How to Use

1. **Place** one of the six blocks
2. **Right-click** to open the node editor
3. **Right-click empty space** to open the add-node menu (categorized & collapsible)
4. **Left-click a node** to edit its parameters
5. **Drag from output pins** to **input pins** to connect nodes
6. **Press `X`** to delete a node (hover over it), or **`TAB` + Left-click** a connection to delete it
7. Press **Compile**, then **Run**

**Controls:**
| Action | Input |
|--------|-------|
| Open add-node menu | Right-click on empty space |
| Edit node parameters | Left-click node, then click ▶ |
| Connect nodes | Drag from output pin to input pin |
| Delete node | Press **X** while hovering over it |
| Delete connection | **TAB** + Left-click on connection |
| Delete selected node(s) | Delete / Backspace |
| Box select nodes | TAB + Left-click drag |
| Toggle node selection | TAB + Left-click on node |
| Move selected nodes | TAB + Left-click drag on selected node |
| Duplicate node(s) | Ctrl + D |
| Expand/collapse edit area | Click ▶ / ▼ on node header |
| Color customization | Click **Style** button |
| Toggle grid snap | Click **Grid** button |
| Zoom in/out | Scroll wheel |
| Pan canvas | Right-click drag |
| **Control Seat — Sit down** | Right-click on seat (empty hand) |
| **Control Seat — Open editor** | Shift + Right-click on seat |
| **Control Seat — Dismount** | Press **`~`** |
| **Control Seat — Switch mode** | Press **`TAB`** |
| **Control Seat — Pause / release mouse** | Press **`ESC`** |

---

### Schematic Support
All six blocks fully support **Create's Schematicannon**. Your node graphs, parameters, and running state are preserved when saving and loading schematics — no data loss.

This means you can:
- 🏗️ **Build complex logic** in creative mode, then **print it in survival** with the Schematicannon
- 📋 **Copy and paste** computer configurations across your world
- 🌍 **Share your creations** as schematic files with other players

> Uses Create's official `IMergeableBE` interface and `SafeNbtWriter` registration for reliable data preservation.

---

### Block Properties

All six blocks share consistent properties:

| Property | Value |
|----------|-------|
| **Hardness** | 1.0 (breakable by hand, no tool required) |
| **Hand break** | Drops block item **without** NBT (fresh block) |
| **Wrench (right-click)** | Rotates block (cycles FACING direction) |
| **Wrench (shift + right-click)** | Picks up block **with** full NBT preservation (graph, running state, pid values) |



### Sable Physics Integration

This mod has **deep integration** with the **Sable physics engine** for Minecraft. The Control Seat and Attitude Sensor are designed to work on rotating physics structures.

#### How it works

Both blocks implement `BlockEntitySubLevelActor` — Sable's interface for block entities that participate in subworld physics simulation. When a block is placed inside a sable sublevel:

1. **`sable$physicsTick()`** fires every physics tick (concurrent with the server tick)
2. The sublevel's **logical pose** (orientation quaternion) is read via `subLevel.logicalPose()`
3. Euler angles are extracted using JOML's `getEulerAnglesYXZ()` — matching Minecraft's YXZ rotation convention
4. Yaw is converted from JOML's CCW-positive convention to Minecraft's CW-positive convention
5. A **relative rotation** is computed (subtracting the initial sublevel offset) so that at-rest structures read as 0° deviation

#### Thread safety

The sable physics thread and the Minecraft server thread run concurrently. Shared fields (`cachedSubYaw`, `cachedSubPitch`, `cachedSubRoll`, `hasSubPose`, `cachedBlockFacingYaw`, `initialSubYaw`) are all marked **`volatile`** to ensure cross-thread visibility.

#### Control Seat + Sable

| Feature | Description |
|---------|-------------|
| **Entity yaw sync** | The ControlSeatEntity's yaw tracks the sublevel rotation (`blockFacing - relativeYaw`), so the player's camera follows the structure |
| **Joystick mode** | Player view locks to entity yaw, automatically rotating with the physics structure |
| **View angle mode** | Client sends `playerYaw - vehicleYaw` delta; server reconstructs absolute world yaw using entity yaw |
| **Relative rotation tracking** | Initial sublevel yaw recorded on first physics tick; subsequent rotations measured as offsets from initial |

#### Attitude Sensor + Sable

| Node | Source | Convention |
|------|--------|------------|
| **ATTITUDE** | `subLevel.logicalPose().orientation()` → `getEulerAnglesYXZ()` | Output: pitch (X), roll (Z) |
| **FORWARD** | Block facing vector rotated by sublevel quaternion | Output: world-space yaw/pitch |
| **ACCELERATION** | `subLevel.latestLinearVelocity` differentiated at 20Hz | Structure-local X/Y/Z (fwd/back, up/down, left/right) |
| **WORLD_VIEW** | Player absolute yaw (view angle diff + entity yaw) | Updates in view angle mode only |

#### Without Sable

**Sable is optional.** When sable is not installed:

| Component | Behavior |
|-----------|----------|
| Control Seat | Fully functional — keyboard/mouse/gamepad input, key binding, two input modes, ESC menu |
| Attitude Sensor | Graph editor, math/logic/output nodes, redstone I/O work. ATTITUDE/FORWARD/WORLD_VIEW output 0 |

---

### Technical Highlights

| Feature | Description |
|---------|-------------|
| ⚡ **Topological sort evaluation** | Nodes evaluated in dependency order, O(1) input query cache |
| 🚀 **GC-friendly** | `GraphEvaluator` instances reused across ticks, reducing GC pressure |
| 🔄 **Private Signal Bus** | Global named-channel float communication across computers |
| 🎯 **Reflection-based speed control** | Directly sets `SpeedControllerBlockEntity.targetSpeed` field |
| 🧹 **PID anti-windup** | I-term auto-resets when error reaches zero |
| 🛡️ **Cycle detection** | Blocks execution if circular dependencies are detected in the graph |
| 🎮 **GLFW raw input** | Control Seat reads keyboard/mouse/gamepad at the GLFW level, not through Minecraft's keybinding system |
| 🔄 **Sable physics integration** | Control Seat and Attitude Sensor implement `BlockEntitySubLevelActor` for sublevel pose reading |

---

### Recipes

| Block | Materials |
|-------|-----------|
| **Blueprint Computer** | 2× Redstone Link, 1× Precision Mechanism, 2× Glass Pane, 1× Repeater, 1× Comparator, 2× Brass Casing |
| **Speed Proxy Controller** | 4× Brass Ingot, 1× Cogwheel, 2× Glass Pane, 1× Comparator, 1× Andesite Casing |
| **Program Computer** | 4× Andesite Casing, 1× Repeater, 2× Glass Pane, 1× Comparator, 1× Andesite Alloy |
| **Control Seat** | 1× Heavy Weighted Pressure Plate, 2× Iron Ingot, 1× Brass Casing, 1× Redstone, 4× Redstone Link |
| **Attitude Sensor** | 6× Iron Ingot, 1× Repeater, 1× Comparator, 2× Brass Casing |
| **Holographic Monitor** | 2× Redstone Link, 1× Precision Mechanism, 2× Glass Pane, 1× Brass Casing, 2× Glowstone Dust |

*(Requires JEI to view in-game)*

---

### Dependencies

- **Minecraft**: 1.21.1
- **NeoForge**: 21.1.233+
- **Create**: 6.0.10+

---

### Creative Ideas

| Idea | Description |
|------|-------------|
| 🏭 **Smart factory control** | Use PID nodes for automatic RPM regulation |
| 🔄 **Automated sequences** | Build complex automation with Delay and Loop nodes |
| 🎛️ **Multi-computer coordination** | Link computers via Private Signal Bus |
| 🖥️ **Live factory dashboards** | Use Holographic Monitor to display sensor data and KPIs in real-time |
| 🎯 **Wireless remote control** | Combine with Redstone Link network |
| ⚙️ **Precision speed matching** | Speed Proxy Controller for exact RPM matching |

---

### FAQ

**Q: The node editor feels laggy?**  
A: Check if you have too many nodes or complex PID operations. Keep the number of continuously running PIDs reasonable.

**Q: Speed Proxy Controller not working?**  
A: Make sure the Speed Proxy Controller is placed directly adjacent (one of 6 faces) to a Speed Controller.

**Q: Computer state lost after schematic placement?**  
A: Make sure you're using Create 6.0.10+. This mod registers complete NBT save/load interfaces.

**Q: Can computers communicate with each other?**  
A: Yes! Use Private Signal Input/Output nodes with named channels. Blueprint Computers and Program Computers can exchange float values across distances.

---

### Source Code

📦 **GitHub Repository**: [https://github.com/y15173334444/create-schematic-compute](https://github.com/y15173334444/create-schematic-compute)

The project is fully open-source under the **MIT License**. Contributions, issues, and feature requests are welcome!

---

### Installation

1. Install **NeoForge 21.1.233+**
2. Install **Create 6.0.10+**
3. Place the mod `.jar` file into your `mods` folder
4. Launch the game!

---

### License

MIT License © 2026 StarryNight_Luo

---

## 🇨🇳 中文

### 什么是 Create: Schematic Compute？

**Create: Schematic Compute（机械动力：蓝图计算机）** 是一个**机械动力附属模组**，添加了**六种可编程方块**，采用**可视化节点图编程系统**。无需编写代码或搭建复杂的红石电路，只需拖拽连接节点即可构建逻辑——类似虚幻引擎的蓝图系统或 Blender 的几何节点。

每台计算机拥有独立的节点图，以 **20Hz（每游戏刻）** 的频率运行，适合实时控制应用。

---

### 方块

#### 🖥️ 全息显示器
一个**3D 悬浮显示方块**，将节点图输出渲染为世界中的虚拟屏幕。

- **显示节点** — TEXT、DATA、IMAGE、IMAGE_SEQUENCE 用于视觉输出
- **16×16 像素编辑器** — 内置像素画编辑器，支持多帧动画
- **3D 自由定位** — 在世界空间中自由定位和旋转屏幕（X/Y/Z + 滚转/俯仰/偏航）
- **信号驱动移动** — IMAGE/IMAGE_SEQUENCE 通过 X/Y 输入信号驱动位置，移动比例可配置
- **红石输入** — 从红石链接网络读取信号（共享频率）
- **实时预览** — GUI 显示模式，所见即所得的布局/缩放/旋转编辑
- **自定义模型** — 完整 Blockbench 自定义模型支持

#### 🖥️ 蓝图计算机
通过可视化编程控制机械动力的**红石链接网络**。

- **红石输入** — 使用频率物品从机械动力的红石链接网络读取信号
- **红石输出** — 将计算后的信号写回红石链接网络
- **私有信号输出** — 通过命名通道将浮点数传输到其他计算机
- **私有信号输入** — 从命名通道读取浮点数

#### ⚡ 转速代理控制器
直接控制相邻 6 个面上机械动力**转速控制器**的目标 RPM。

- **转速控制** — 设置附近转速控制器的目标转速（-256 ~ 256 RPM）
- **私有信号输入** — 从命名通道读取浮点数，实现跨计算机联动

#### 🔌 编程计算机
专为**时序逻辑**设计的计算机，适用于延时、计数和脉冲控制。

- **红石 I/O** — 通过机械动力的红石链接网络通信
- **专用时序节点**：延时、锁存器、T 触发器、脉冲延长、循环、保险

#### 🪑 控制座椅
一个**可乘坐的控制座椅**，支持实时键盘、鼠标和手柄输入捕获。

- **58 个可绑定按键** — 通过点击绑定 UI 分配按键
- **两种输入模式**：摇杆（鼠标增量）和视角差（玩家旋转差）
- **手柄支持** — 双摇杆、15 个按钮（通过 GLFW 手柄 API）
- **Sable 物理兼容** — 实体 yaw 与子世界旋转同步
- **按 `~` 下马**、**`TAB` 切换模式**、**`ESC` 打开菜单释放鼠标**

#### 📐 姿态传感器
通过节点图读取 sable 物理结构的姿态。

- **姿态节点** — 输出子世界的俯仰（pitch）和横滚（roll）
- **前方朝向节点** — 输出结构的全局朝向偏航/俯仰
- **世界视角节点** — 读取玩家在座椅上的绝对视角方向
- **姿态换算节点** — 在不同坐标系间转换 pitch/yaw/roll

---

### 节点参考（43 种）

| 分类 | 节点 |
|------|------|
| **数值** | 常量、红石输入、私有信号输入 |
| **运算** | 加、减、乘、除、模运算、次幂、次方根、绝对值、比较路由、向上取整、向下取整、**公式** |
| **逻辑** | 大于、小于、等于、布尔（反转） |
| **控制** | PID 控制器（误差归零时 I 项复位）、动力 PID、限幅、映射范围 |
| **输出** | 红石输出、私有信号输出、转速控制 |
| **时序** | 延时、锁存器、T 触发器、脉冲延长、循环、保险 |
| **显示** | TEXT（文字）、DATA（数值）、IMAGE（图片）、IMAGE_SEQUENCE（动画） |
| **操作输入** | 键盘按键（58键）、鼠标摇杆（X/Y）、视角差、鼠标按键（左/右）、手柄摇杆（LX/LY/RX/RY）、手柄按键（15键） |
| **传感器** | 世界视角（偏航/俯仰）、姿态（俯仰/横滚）、前方朝向（偏航/俯仰）、姿态换算（3入2出）、分割 |

#### 详细节点表

##### 数值
| 节点 | 输入 | 输出 | 参数 | 说明 |
|------|------|------|------|------|
| CONST | - | float | value | 输出常量值 |
| REDSTONE_IN | - | signal | 频率物品×2 | 从机械动力红石链接读取信号 |
| PRIVATE_IN | - | val | channel | 从命名通道读取浮点数 |

##### 运算
| 节点 | 输入 | 输出 | 说明 |
|------|------|------|------|
| ADD | A, B | float | A + B |
| SUB | A, B | float | A - B |
| MUL | A, B | float | A × B |
| DIV | A, B | float | A ÷ B（B=0 时返回 0） |
| MOD | A, B | float | A % B（取模） |
| POW | A, B | float | A 的 B 次幂 |
| ROOT | A, B | float | A 的 B 次方根（B=0 时返回 0） |
| ABS | in | float | 输入值的绝对值 |
| Comparison Router | A, B | A, B | A≥B 时 A 口输出 A-B，否则 B 口输出 \|B-A\| |
| CEIL | in | int | 向上取整 |
| FLOOR | in | int | 向下取整 |
| FORMULA | 变量名(A-Z) | float | formula | 自定义数学公式，自动根据变量名创建输入引脚 |

##### 逻辑
| 节点 | 输入 | 输出 | 参数 | 说明 |
|------|------|------|------|------|
| GT | A, B | bool | - | A > B → 1 |
| LT | A, B | bool | - | A < B → 1 |
| EQ | A, B | bool | - | A = B → 1 |
| BOOL | in | bool | inverted | 输入 > 0 → 1, ≤ 0 → 0; inverted=1 时反转 |

##### 控制
| 节点 | 输入 | 输出 | 参数 | 说明 |
|------|------|------|------|------|
| PID | SP | ctrl | kp, ki, kd, scale | PID 控制器，输出 0~16，误差归零时 I 项复位 |
| PID_POWER | SP, base | power | kp, ki | 带基础动力的 PID，输出 0~16 |
| CLAMP | In, Min, Max | float | - | 限幅 |
| MAP | In, InMin, InMax, OutMin, OutMax | float | - | 映射范围 |

##### 输出
| 节点 | 输入 | 输出 | 参数 | 说明 |
|------|------|------|------|------|
| REDSTONE_OUT | In | - | 频率物品×2 | 将信号写入机械动力红石链接（限幅 0~15） |
| PRIVATE_OUT | val | - | channel | 将浮点数写入命名通道 |
| SPEED_CTRL | speed, dir | rpm | - | 设置转速控制器的 RPM; dir>0.5 时反转方向 |

##### 显示（仅全息显示器专用）
| 节点 | 输入 | 输出 | 参数 | 说明 |
|------|------|------|------|------|
| TEXT | - | - | text, color | 显示文字内容 |
| DATA | val | - | color | 显示输入浮点数值 |
| IMAGE | X, Y | - | imageData | 16×16 像素图片，XY 信号驱动位置 |
| IMAGE_SEQUENCE | X, Y, frame | - | frames, fps | 多帧动画，frame 输入切换帧 |

##### 时序（仅编程计算机）
| 节点 | 输入 | 输出 | 参数 | 说明 |
|------|------|------|------|------|
| DELAY | in | out | ticks | 延时 N tick 后输出 |
| LATCH | S, R | q | - | S≥1 置位，R≥1 复位，保持 |
| T_FLIPFLOP | in | tog | - | 上升沿翻转输出 |
| PULSE_EXTEND | in | pulse | ticks | 输入高电平时脉冲延长 N tick |
| LOOP | in | clk | count, interval | 收到触发后每 interval tick 输出脉冲，重复 count 次 |
| FUSE | in | pulse | cooldown | 收到信号→2 tick 脉冲→冷却 N tick |

##### 操作输入（控制座椅专用）
| 节点 | 输入 | 输出 | 参数 | 说明 |
|------|------|------|------|------|
| KEYBOARD | - | 1/0 | key | 绑定键盘按键（58键），按下输出 1 |
| MOUSE_JOYSTICK | - | X, Y | - | 鼠标增量输出 -1~1（摇杆模式） |
| VIEW_ANGLE | - | pitch, yaw | - | 玩家视角差（视角差模式） |
| MOUSE_BUTTON | - | L, R | - | 鼠标左/右键状态 |
| GAMEPAD_JOYSTICK | - | LX, LY, RX, RY | - | 手柄双摇杆 -1~1 |
| GAMEPAD_BUTTON | - | 1/0 | button | 手柄按键（15键） |

##### 传感器（姿态传感器专用）
| 节点 | 输入 | 输出 | 参数 | 说明 |
|------|------|------|------|------|
| WORLD_VIEW | - | yaw, pitch | - | 玩家世界视角方向 |
| ATTITUDE | - | pitch, roll | - | 子世界姿态（俯仰/横滚） |
| FORWARD | - | yaw, pitch | - | 子世界前方朝向 |
| POSE_CONVERT | pitch_a, yaw_a, roll | pitch_b, yaw_b | - | 姿态换算（3入2出） |
| SPLIT | in | +out, -out | - | 正负分离：正数输出+，负数绝对值输出- |

---

### 使用方法

1. **放置**任意一个方块
2. **右键**打开节点编辑器
3. **右键空白处**打开添加节点菜单（支持分类折叠）
4. **左键节点**编辑参数
5. **从输出端口拖拽到输入端口**连接节点
6. **按 X 键**删除节点（悬停其上），或 **TAB + 左键**点击连线删除
7. 点击 **Compile** 编译，然后点击 **Run** 运行

**操作指南：**
| 操作 | 方法 |
|------|------|
| 打开节点菜单 | 右键空白处 |
| 编辑参数 | 左键节点，再点击 ▶ |
| 连接节点 | 从输出端口拖到输入端口 |
| 删除节点 | **X 键**（悬停节点上） |
| 删除连线 | **TAB + 左键**点击连线 |
| 删除选中节点 | Delete / Backspace |
| 框选节点 | TAB + 左键拖拽 |
| 切换选中 | TAB + 左键点击节点 |
| 拖拽移动选中 | TAB + 左键拖拽已选中节点 |
| 复制节点 | Ctrl + D |
| 展开/折叠编辑区 | 点击节点标题的 ▶ / ▼ |
| 颜色自定义 | 点击 **Style** 按钮 |
| 网格吸附开关 | 点击 **Grid** 按钮 |
| 缩放 | 滚轮 |
| 平移画布 | 右键拖拽 |
| **控制座椅 — 乘坐** | 右键点击座椅（空手） |
| **控制座椅 — 打开编辑器** | Shift + 右键点击座椅 |
| **控制座椅 — 下马** | 按 **`~`** 键 |
| **控制座椅 — 切换模式** | 按 **`TAB`** 键 |
| **控制座椅 — 暂停/释放鼠标** | 按 **`ESC`** 键 |

---

### 蓝图兼容
全部六种方块完全支持**机械动力的蓝图大炮（Schematicannon）**。节点图、参数和运行状态在保存和放置蓝图时都会**完整保留**，不会丢失数据。

> 采用 Create 官方 `IMergeableBE` 接口和 `SafeNbtWriter` 注册，确保蓝图数据的可靠保存与恢复。

---

### 技术亮点

| 特性 | 说明 |
|------|------|
| ⚡ **拓扑排序求值** | 节点图按拓扑顺序求值，支持 O(1) 输入查询缓存 |
| 🚀 **GC 友好** | 重用 `GraphEvaluator` 实例，减少每 tick 垃圾回收压力 |
| 🔄 **私有信号总线** | 全局命名通道通信，跨计算机联动 |
| 🎯 **精准反射** | 通过反射直接设置转速控制器的内部字段 |
| 🧹 **积分防饱和** | PID 控制器误差归零时自动复位积分项 |
| 🛡️ **环检测** | 编译时自动检测循环引用并阻止运行 |
| 🎮 **GLFW 原始输入** | 控制座椅通过 GLFW 直接读取键盘/鼠标/手柄，绕过 Minecraft 按键绑定系统 |
| 🔄 **Sable 物理集成** | 控制座椅和姿态传感器实现 `BlockEntitySubLevelActor` 接口读取子世界姿态 |

---

### 方块属性
所有 6 个方块共享一致的属性：

| 属性 | 值 |
|------|-----|
| **硬度** | 1.0（空手可破坏，无需工具） |
| **空手破坏** | 掉落方块物品**不含 NBT**（全新方块） |
| **扳手（右键）** | 旋转方块（循环 FACING 方向） |
| **扳手（Shift + 右键）** | 收回方块**保留完整 NBT**（节点图、运行状态、PID 参数） |



### Sable 物理集成

本模组与 **Sable 物理引擎** 深度集成。控制座椅和姿态传感器专为在旋转的物理结构上工作而设计。

#### 工作原理

两个方块都实现了 `BlockEntitySubLevelActor` — Sable 用于参与子世界物理模拟的方块实体接口。当方块放置在 sable 子世界中时：

1. **`sable$physicsTick()`** 每个物理 tick 触发（与服务端 tick 并发运行）
2. 子世界的 **逻辑姿态**（方向四元数）通过 `subLevel.logicalPose()` 读取
3. 使用 JOML 的 `getEulerAnglesYXZ()` 提取欧拉角（匹配 Minecraft 的 YXZ 旋转约定）
4. 偏航角从 JOML 的逆时针正方向转换为 Minecraft 的顺时针正方向
5. 计算**相对旋转**（减去初始子世界偏移），使静止结构读取为 0° 偏差

#### 线程安全

Sable 物理线程和 Minecraft 服务端线程并发运行。共享字段（`cachedSubYaw`、`cachedSubPitch`、`cachedSubRoll`、`hasSubPose`、`cachedBlockFacingYaw`、`initialSubYaw`）都标记为 **`volatile`** 确保跨线程可见性。

#### 控制座椅 + Sable

| 特性 | 说明 |
|------|------|
| **实体 yaw 同步** | 座椅实体 yaw 追踪子世界旋转（`blockFacing - relativeYaw`），玩家视角随结构转动 |
| **摇杆模式** | 玩家视角锁定到实体 yaw，自动跟随物理结构旋转 |
| **视角差模式** | 客户端发送 `playerYaw - vehicleYaw` 差值，服务端用实体 yaw 重建绝对世界偏航 |
| **相对旋转追踪** | 首次 physics tick 记录初始子世界 yaw，后续旋转测量为与初始值的偏移 |

#### 姿态传感器 + Sable

| 节点 | 数据来源 | 说明 |
|------|----------|------|
| **ATTITUDE** | `subLevel.logicalPose().orientation()` → `getEulerAnglesYXZ()` | 输出俯仰 pitch（X）和横滚 roll（Z） |
| **FORWARD** | 方块朝向向量经子世界四元数旋转 | 输出结构在世界空间中的前方偏航/俯仰 |
| **WORLD_VIEW** | 玩家绝对偏航（视角差 + 实体 yaw） | 仅在视角差模式更新，摇杆模式冻结 |

#### 无 Sable 时

**Sable 是可选的。** 未安装 sable 时：

| 组件 | 行为 |
|------|------|
| 控制座椅 | **完全可用** — 键盘/鼠标/手柄输入、按键绑定、两种输入模式、ESC 菜单 |
| 姿态传感器 | **部分可用** — 节点编辑器、所有数学/逻辑/输出节点、红石 I/O 正常工作。ATTITUDE/FORWARD/WORLD_VIEW 节点输出 **0**（无子世界姿态数据） |

---

### 合成配方

| 方块 | 材料 |
|------|------|
| **蓝图计算机** | 无线红石信号终端 ×2 + 精密构件 + 玻璃板 ×2 + 中继器 + 比较器 + 黄铜外壳 ×2 |
| **转速代理控制器** | 黄铜锭 ×4 + 齿轮 + 玻璃板 ×2 + 比较器 + 安山岩外壳 |
| **编程计算机** | 安山岩外壳 ×4 + 中继器 + 玻璃板 ×2 + 比较器 + 安山合金 |
| **控制座椅** | 重质测重压力板 ×1 + 铁锭 ×2 + 黄铜机壳 ×1 + 红石 ×1 + 无线红石信号终端 ×4 |
| **姿态传感器** | 铁锭 ×6 + 中继器 ×1 + 比较器 ×1 + 黄铜机壳 ×2 |
| **全息显示器** | 无线红石信号终端 ×2 + 精密构件 + 玻璃板 ×2 + 黄铜机壳 + 荧石粉 ×2 |

*(需要 JEI 模组在游戏中查看)*

---

### 创意用法

| 用途 | 说明 |
|------|------|
| 🏭 **智能工厂控制** | 使用 PID 节点实现转速自动调节 |
| 🔄 **自动化时序** | 构建复杂的自动化序列 |
| 🎛️ **多级联动** | 多台计算机通过私有信号总线协同工作 |
| 🖥️ **实时工厂仪表盘** | 用全息显示器实时展示传感器数据和运行指标 |
| 🎯 **无线远程控制** | 结合红石链接网络实现远程控制 |
| ⚙️ **速度匹配** | 转速代理控制器让转速精确匹配 |

---

### 常见问题

**Q: 节点编辑器响应迟缓？**  
A: 检查是否节点过多或 PID 运算复杂，建议控制持续运行的 PID 数量。

**Q: 转速代理控制器不工作？**  
A: 确保转速代理控制器放置在转速控制器的相邻 6 个面之一。

**Q: 蓝图放置后计算机状态丢失？**  
A: 确保使用 Create 6.0.10+，本模组已注册完整的 NBT 保存/加载接口。

**Q: 计算机之间可以通信吗？**  
A: 可以！使用私有信号输入/输出节点配合命名通道，蓝图计算机和编程计算机可以跨距离交换浮点数值。

---

### 安装

1. 安装 **NeoForge 21.1.233+**
2. 安装 **Create 6.0.10+**
3. 将模组的 `.jar` 文件放入 `mods` 文件夹
4. 启动游戏！

---

### 源代码

📦 **GitHub 仓库**：[https://github.com/y15173334444/create-schematic-compute](https://github.com/y15173334444/create-schematic-compute)

本项目完全开源，基于 **MIT 许可证**。欢迎提交 Issue 和 Pull Request！

---

### 许可

MIT License © 2026 StarryNight_Luo

---

## 📝 Changelog

### v1.1.3
- **Add: OR Gate** — 2 inputs (A,B), 1 output (bool), Logic category, Blueprint Computer
- **Add: GE / LE** — Greater Than or Equal / Less Than or Equal comparison nodes in Logic category
- **Add: ROUND** — round to N decimal places (Advanced Math, Blueprint only)
- **Add: Continuous Integrator** — 3 inputs (+,-,clear), configurable step/interval/limit, Program Computer
- **Add: Acceleration** — structure-local X/Y/Z acceleration from sable physics (Control Seat + Attitude Sensor)
- **Add: Gamepad Trigger** — analog LT/RT outputs (0.0~1.0) for Control Seat
- **Add: IMAGE/IMAGE_SEQUENCE rotation input** — signal-driven rotation with per-axis moveScale (X/Y), rotationScale, invertX/Y toggles
- **Add: T_FLIPFLOP edit panel** — configurable default on/off state toggle
- **Add: i18n** — ~130 new translation keys for node edit panels, pin labels, toolbar, toggles (zh_cn + en_us)
- **Fix: GAMEPAD_BUTTON binding** — moved from keyPressed() to frame-polling with edge detection; works with mapping software
- **Fix: Gamepad trigger clamp** — axes clamped to [0,1] for phone/Bluetooth gamepads
- **Fix: IMAGE_SEQUENCE frame display** — 3D renderer now reads frame input pin and selects correct frame
- **Fix: Monitor display rotation** — center-based rotation with correct direction matching GUI preview
- **Fix: Double checkmark** — removed duplicate ✔ from BOOL/GATE toggle i18n strings
- **Fix: LATCH pin label** — resolved duplicate `r` key conflict between LATCH Reset and Mouse Right
- **Perf: Acceleration** — pure float trig instead of JOML object allocations, no GC stutter on sable structures

### v1.1.2
- **Add: GATE node** — signal gate with 4 inputs (value/open/close/toggle) and NBT-persistent state; available on Blueprint and Program computers
- **Fix: Node connection culling** — connections on the left/top side of the GUI are no longer incorrectly culled
- **Fix: Monitor GUI rotation** — display element rotation direction now matches between GUI editor and 3D screen
- **Fix: Monitor GUI margins** — component positions in GUI now include the 0.04-block bezel margin matching the 3D screen
- **Fix: Monitor IMAGE clamping** — rotated IMAGE elements use AABB-aware edge clamping matching the 3D renderer
- **Fix: Monitor settings data loss** — changing screen settings no longer resets display element positions/rotations
- **Fix: Monitor live preview** — display area updates in real-time while editing screen settings
- **Fix: ACCUMULATOR crash** — placing accumulator node on Program Computer no longer crashes the game

### v1.1.1
- **Add: Encapsulation node** — nest sub-graphs inside a single node, double-click to expand, 48→49 types
- **Add: Monitor Redstone Input** — Holographic Monitor now reads Redstone Link network signals via REDSTONE_IN node
- **Fix: Control Seat view jump** — smooth transition between Joystick and View Angle modes (Mixin-based raw mouse delta capture)
- **Fix: Monitor redstone registration** — late-registered listeners now receive initial signal state via force-resend
- **Fix: Monitor 3D renderer** — correctly evaluates REDSTONE_IN nodes, evaluator caching for 60+ FPS performance
- **Perf: Code audit** — NaN guards on all arithmetic nodes (ADD/SUB/MUL/MAP/INTERP), FORMULA compilation cache, Sensor reflection cache, volatile thread safety
- **Refactor: RedstoneLinkHelper** — ~400 lines of duplicated redstone link code deduplicated across Blueprint, ProgramComputer, ControlSeat, and Sensor
- **Refactor: GraphBlockEntity interface** — replaces 6-branch instanceof chains in BlueprintSavePacket and BlueprintTogglePacket
- **Mixin infrastructure** — added Mixin AP for Entity.turn() interception in Control Seat

### v1.1.0
- **Add: Holographic Monitor block** — floating 3D display screen with pixel editor, display mode, signal-driven movement
- **Add: Control Seat block** — sit-able controller with keyboard/mouse/gamepad input, 58 assignable keys, dual input modes (Joystick / View Angle), Sable physics compatibility
- **Add: Attitude Sensor block** — reads Sable physics structure orientation via ATTITUDE/FORWARD/WORLD_VIEW nodes
- **Add: 14 new node types** — 4 Display (TEXT, DATA, IMAGE, IMAGE_SEQUENCE), 10 Input/Sensor (KEYBOARD, MOUSE_JOYSTICK, VIEW_ANGLE, MOUSE_BUTTON, GAMEPAD_JOYSTICK, GAMEPAD_BUTTON, WORLD_VIEW, ATTITUDE, FORWARD, SPLIT, POSE_CONVERT), bringing total from 24→48
- **Add: FORMULA node** — custom math expressions with multi-letter variable names, auto-created input pins
- **Add: Accumulator node** — +/- dual-input rising-edge counter
- **Add: Pixel editor** — 16×16 grid with 2-column palette, HEX input, multi-frame animation
- **Add: Color customization** — 16-color theming, ARGB text color for TEXT/DATA nodes
- **Add: IWrenchable support** — wrench rotation and shift+wrench NBT pick-up for all 6 blocks
- **Add: Multilingual support** — full EN/ZH localization for Monitor and all GUIs
- **Change: Create-style warm metallic GUI palette** (brass/copper/steel)
- **Change: Redstone output clamped to 0-15**
- **Fix: World reload bug** — all blocks work correctly after save/quit/reload
- **Fix: SignalBus cross-world pollution** — static state properly cleared on server stop
- **Fix: NaN propagation** — guarded POW, ROOT, PID against NaN/Infinity
- **Fix: SpeedProxy shared static PID map → per-instance**

### v1.0.0
- **Initial release**: 3 programmable computers (Blueprint, Speed Proxy, Program)
- 24 node types across 6 categories (Values, Math, Logic, Control, Output, Sequential)
- Visual node-based graph editor with drag-connect workflow
- Create Redstone Link network integration
- Private Signal Bus for cross-computer communication
- Schematicannon compatibility via IMergeableBE + SafeNbtWriter
- Add: Create `IMergeableBE` interface for reliable data restoration
- Add: `SafeNbtWriter` registration for Create schematic compatibility
- Add: POW (A^B) and ROOT (B-th Root of A) math nodes
- Add: BOOL node with invert toggle for logic control
- Add: SPEED_CTRL direction control (2nd input pin `dir`)
- Add: TAB + box select, multi-drag, multi-copy, multi-delete
- Add: ABS (Absolute Value) and Comparison Router (|A-B|) nodes
- Change: Remove PV input from PID and PID_POWER nodes
- Change: Rename Interpolation → Comparison Router
