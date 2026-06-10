# Create: Schematic Compute

[![GitHub](https://img.shields.io/badge/GitHub-y15173334444/create--schematic--compute-blue?style=flat-square&logo=github)](https://github.com/y15173334444/create-schematic-compute)
[![License](https://img.shields.io/badge/License-MIT-green?style=flat-square)](LICENSE)
[![NeoForge](https://img.shields.io/badge/NeoForge-21.1.233-orange?style=flat-square)](https://neoforged.net/)
[![Create](https://img.shields.io/badge/Create-6.0.10-brightgreen?style=flat-square)](https://www.curseforge.com/minecraft/mc-mods/create)

---

<p align="center">
  <b>🎮 Three Programmable Computers with a Visual Node-Based Programming System</b><br>
  <i>Drag, connect, and build logic — just like Unreal Engine Blueprints or Blender Geometry Nodes!</i>
</p>

---

## 🇬🇧 English

### What is Create: Schematic Compute?

**Create: Schematic Compute** is a **Create mod addon** that introduces **three programmable computers** with a **visual node-based programming system**. Instead of writing complex redstone circuits or struggling with command blocks, you simply drag and connect nodes to build logic — just like in Unreal Engine's Blueprint system or Blender's Geometry Nodes.

Each computer has its own internal node graph that runs at **20Hz (every game tick)**, making it suitable for real-time control applications.

---

### Blocks

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
- **Dedicated sequential nodes**: Delay, Latch, T Flip-Flop, Pulse Extender, Loop, Safety Timer

---

### Node Reference (32 Types)

| Category | Nodes |
|----------|-------|
| **Values** | CONST, Redstone Input, Private Signal Input |
| **Math** | Add, Subtract, Multiply, Divide, Modulo, Power (A^B), Root (B-th Root), Absolute Value, Comparison Router (\|A-B\|), Ceil, Floor, **Formula** |
| **Logic** | Greater Than, Less Than, Equals, Bool (Toggle) |
| **Control** | PID Controller (I-term resets on zero error), Power PID, Clamp, Map Range |
| **Output** | Redstone Output, Private Signal Output, Speed Control |
| **Sequential** | Delay, Latch, T Flip-Flop, Pulse Extender, Loop, Safety Timer |

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
| Comparison Router | A, B | A, B | A≥B → A port outputs A-B, else B port outputs \|B-A\| |
| CEIL | in | int | Round up to nearest integer |
| FLOOR | in | int | Round down to nearest integer |
| FORMULA | var(A-Z) | float | formula | Custom math expression, auto-creates input pins per variable (e.g. `AB*2+Speed`) |

##### Logic
| Node | Inputs | Output | Params | Description |
|------|--------|--------|--------|-------------|
| GT | A, B | bool | - | A > B → 1 |
| LT | A, B | bool | - | A < B → 1 |
| EQ | A, B | bool | - | A = B → 1 |
| BOOL | in | bool | inverted | in > 0 → 1, ≤ 0 → 0; inverted=1 flips output |

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

##### Sequential (Program Computer only)
| Node | Inputs | Output | Params | Description |
|------|--------|--------|--------|-------------|
| DELAY | in | out | ticks | Delays output by N ticks |
| LATCH | S, R | q | - | S≥1 sets, R≥1 resets, holds value |
| T_FLIPFLOP | in | tog | - | Toggles output on rising edge |
| PULSE_EXTEND | in | pulse | ticks | Extends input pulse by N ticks |
| LOOP | in | clk | count, interval | Fires pulse every interval tick, repeats count times |
| FUSE | in | pulse | cooldown | Trigger → 2-tick pulse → cooldown N ticks |

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

1. **Place** one of the three computers
2. **Right-click** to open the node editor
3. **Right-click empty space** to open the add-node menu (categorized & collapsible)
4. **Left-click a node** to edit its parameters
5. **Drag from output pins** to **input pins** to connect nodes
6. **Right-click** a node or connection to delete it
7. Press **Compile**, then **Run**

**Controls:**
| Action | Input |
|--------|-------|
| Open add-node menu | Right-click on empty space |
| Edit node parameters | Left-click node, then click ▶ |
| Connect nodes | Drag from output pin to input pin |
| Delete node/connection | Right-click on it |
| Box select nodes | TAB + Left-click drag |
| Toggle node selection | TAB + Left-click on node |
| Move selected nodes | TAB + Left-click drag on selected node |
| Duplicate node(s) | Ctrl + D |
| Delete selected node(s) | Delete / Backspace |
| Expand/collapse edit area | Click ▶ / ▼ on node header |
| Color customization | Click **Style** button |
| Toggle grid snap | Click **Grid** button |
| Zoom in/out | Scroll wheel |
| Pan canvas | Right-click drag |

---

### Schematic Support
All three computers fully support **Create's Schematicannon**. Your node graphs, parameters, and running state are preserved when saving and loading schematics — no data loss.

This means you can:
- 🏗️ **Build complex logic** in creative mode, then **print it in survival** with the Schematicannon
- 📋 **Copy and paste** computer configurations across your world
- 🌍 **Share your creations** as schematic files with other players

> Uses Create's official `IMergeableBE` interface and `SafeNbtWriter` registration for reliable data preservation.

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

---

### Recipes

| Block | Materials |
|-------|-----------|
| **Blueprint Computer** | 2× Redstone Link, 1× Precision Mechanism, 2× Glass Pane, 1× Repeater, 1× Comparator, 2× Brass Casing |
| **Speed Proxy Controller** | 4× Brass Ingot, 1× Cogwheel, 2× Glass Pane, 1× Comparator, 1× Andesite Casing |
| **Program Computer** | 4× Andesite Casing, 1× Repeater, 2× Glass Pane, 1× Comparator, 1× Andesite Alloy |

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

**Create: Schematic Compute（机械动力：蓝图计算机）** 是一个**机械动力附属模组**，添加了**三种可编程计算机**，采用**可视化节点图编程系统**。无需编写代码或搭建复杂的红石电路，只需拖拽连接节点即可构建逻辑——类似虚幻引擎的蓝图系统或 Blender 的几何节点。

每台计算机拥有独立的节点图，以 **20Hz（每游戏刻）** 的频率运行，适合实时控制应用。

---

### 方块

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

---

### 节点参考（32 种）

| 分类 | 节点 |
|------|------|
| **数值** | 常量、红石输入、私有信号输入 |
| **运算** | 加、减、乘、除、模运算、次幂、次方根、绝对值、比较路由、向上取整、向下取整、**公式** |
| **逻辑** | 大于、小于、等于、布尔（反转） |
| **控制** | PID 控制器（误差归零时 I 项复位）、动力 PID、限幅、映射范围 |
| **输出** | 红石输出、私有信号输出、转速控制 |
| **时序** | 延时、锁存器、T 触发器、脉冲延长、循环、保险 |

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

##### 时序（仅编程计算机）
| 节点 | 输入 | 输出 | 参数 | 说明 |
|------|------|------|------|------|
| DELAY | in | out | ticks | 延时 N tick 后输出 |
| LATCH | S, R | q | - | S≥1 置位，R≥1 复位，保持 |
| T_FLIPFLOP | in | tog | - | 上升沿翻转输出 |
| PULSE_EXTEND | in | pulse | ticks | 输入高电平时脉冲延长 N tick |
| LOOP | in | clk | count, interval | 收到触发后每 interval tick 输出脉冲，重复 count 次 |
| FUSE | in | pulse | cooldown | 收到信号→2 tick 脉冲→冷却 N tick |

---

### 使用方法

1. **放置**任意一台计算机
2. **右键**打开节点编辑器
3. **右键空白处**打开添加节点菜单（支持分类折叠）
4. **左键节点**编辑参数
5. **从输出端口拖拽到输入端口**连接节点
6. **右键节点或连线**删除
7. 点击 **Compile** 编译，然后点击 **Run** 运行

**操作指南：**
| 操作 | 方法 |
|------|------|
| 打开节点菜单 | 右键空白处 |
| 编辑参数 | 左键节点，再点击 ▶ |
| 连接节点 | 从输出端口拖到输入端口 |
| 删除节点/连线 | 右键节点或连线 |
| 框选节点 | TAB + 左键拖拽 |
| 切换选中 | TAB + 左键点击节点 |
| 拖拽移动选中 | TAB + 左键拖拽已选中节点 |
| 复制节点 | Ctrl + D |
| 删除选中节点 | Delete / Backspace |
| 展开/折叠编辑区 | 点击节点标题的 ▶ / ▼ |
| 颜色自定义 | 点击 **Style** 按钮 |
| 网格吸附开关 | 点击 **Grid** 按钮 |
| 缩放 | 滚轮 |
| 平移画布 | 右键拖拽 |

---

### 蓝图兼容
三台计算机完全支持**机械动力的蓝图大炮（Schematicannon）**。节点图、参数和运行状态在保存和放置蓝图时都会**完整保留**，不会丢失数据。

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

---

### 合成配方

| 方块 | 材料 |
|------|------|
| **蓝图计算机** | 无线红石信号终端 ×2 + 精密构件 + 玻璃板 ×2 + 中继器 + 比较器 + 黄铜外壳 ×2 |
| **转速代理控制器** | 黄铜锭 ×4 + 齿轮 + 玻璃板 ×2 + 比较器 + 安山岩外壳 |
| **编程计算机** | 安山岩外壳 ×4 + 中继器 + 玻璃板 ×2 + 比较器 + 安山合金 |

*(需要 JEI 模组在游戏中查看)*

---

### 创意用法

| 用途 | 说明 |
|------|------|
| 🏭 **智能工厂控制** | 使用 PID 节点实现转速自动调节 |
| 🔄 **自动化时序** | 构建复杂的自动化序列 |
| 🎛️ **多级联动** | 多台计算机通过私有信号总线协同工作 |
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

### v1.0.1
- Add: FORMULA node with custom math expressions (e.g. `ABD+Speed`)
- Add: Multi-letter variable names, auto-created input pins per variable
- Add: Color customization (16-color theming with System/Input/Output borders)
- Add: Inline node editing via ▶/▼ expand with zoom-synced controls
- Add: Multi-node simultaneous expand (independent per-node edit state)
- Add: Hotbar popup for frequency slot item selection
- Add: Grid snap toggle with config persistence
- Add: Render priority system (overlays properly cover edit areas)
- Add: Blueprint Computer now supports Private Signal Input node
- Add: i18n for all GUI buttons and color names (EN/ZH)
- Change: Create-style warm metallic GUI palette (brass/copper/steel)
- Change: Node edit panel moved inside node (no floating side panel)
- Change: Manual expand/collapse only via ▶/▼ (no auto-collapse)
- Change: Redstone output clamped to 0-15
- Fix: SignalBus cross-computer pollution (remove destructive clear())
- Fix: SpeedProxy shared static PID map → per-instance
- Fix: Node expand state survives server sync (tracked by node ID)
- Fix: Compile/Run buttons no longer collapse edit areas
- Cleanup: EditPanel stripped from 285 to 64 lines (dead code removal)

### v1.0.0
- Initial release
- 24 node types across 6 categories
- 3 programmable computers (Blueprint, Speed Proxy, Program)
- Visual node-based graph editor

**Post-release fixes:**
- Fix: Block entity data (node graph + running state) now properly preserved with Create's Schematicannon
- Fix: GUI no longer shows stale empty graph after schematic placement
- Fix: Client-server synchronization of block entity data
- Add: Create `IMergeableBE` interface for reliable data restoration
- Add: `SafeNbtWriter` registration for Create schematic compatibility
- Add: POW (A^B) and ROOT (B-th Root of A) math nodes
- Add: BOOL node with invert toggle for logic control
- Add: SPEED_CTRL direction control (2nd input pin `dir`)
- Add: TAB + box select, multi-drag, multi-copy, multi-delete
- Add: ABS (Absolute Value) and Comparison Router (|A-B|) nodes
- Change: Remove PV input from PID and PID_POWER nodes
- Change: Rename Interpolation → Comparison Router
