# Create: Schematic Compute

[![GitHub](https://img.shields.io/badge/GitHub-y15173334444/create--schematic--compute-blue?style=flat-square&logo=github)](https://github.com/y15173334444/create-schematic-compute)
[![License](https://img.shields.io/badge/License-MIT-green?style=flat-square)](LICENSE)
[![NeoForge](https://img.shields.io/badge/NeoForge-21.1.233-orange?style=flat-square)](https://neoforged.net/)
[![Create](https://img.shields.io/badge/Create-6.0.10-brightgreen?style=flat-square)](https://www.curseforge.com/minecraft/mc-mods/create)

---

## 🇬🇧 English

### What is Create: Schematic Compute?

**Create: Schematic Compute** is a **Create mod addon** that introduces **three programmable computers** with a **visual node-based programming system**. Instead of writing complex redstone circuits or struggling with command blocks, you simply drag and connect nodes to build logic — just like in Unreal Engine's Blueprint system or Blender's Geometry Nodes.

Each computer has its own internal node graph that runs at 20Hz (every game tick), making it suitable for real-time control applications.

---

### Blocks

#### 🖥️ Blueprint Computer
Control Create's **Redstone Link network** through visual programming.

- **Redstone Input** — Reads signals from Create's Redstone Link network using frequency items
- **Redstone Output** — Writes computed signals back to the Redstone Link network
- **Private Signal Output** — Transmits float values across named channels to other computers

#### ⚡ Speed Proxy Controller
Directly control the target RPM of Create's **Speed Controller** blocks on adjacent faces.

- **Speed Control** — Sets the RPM of nearby Speed Controllers
- **Private Signal Input** — Reads float values from named channels

#### 🔌 Program Computer
A **sequential logic computer** for timing, counting, and pulse control applications.

- **Redstone I/O** — Communicates through Create's Redstone Link network
- **Dedicated sequential nodes**: Delay, Latch, T Flip-Flop, Pulse Extender, Loop, Safety Timer

---

### Node Reference (28 Types)

| Category | Nodes |
|----------|-------|
| **Values** | CONST, Redstone Input, Private Signal Input |
| **Math** | Add, Subtract, Multiply, Divide, Modulo, Power (A^B), Root (B-th Root), Absolute Value, Comparison Router (\|A-B\|), Ceil, Floor |
| **Logic** | Greater Than, Less Than, Equals, Bool (Toggle) |
| **Control** | PID Controller (I-term resets on zero error), Power PID, Clamp, Map Range |
| **Output** | Redstone Output, Private Signal Output, Speed Control |
| **Sequential** | Delay, Latch, T Flip-Flop, Pulse Extender, Loop, Safety Timer |

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
| Edit node parameters | Left-click on node |
| Connect nodes | Drag from output pin to input pin |
| Delete node/connection | Right-click on it |
| Box select nodes | TAB + Left-click drag |
| Toggle node selection | TAB + Left-click on node |
| Move selected nodes | TAB + Left-click drag on selected node |
| Duplicate node(s) | Ctrl + D |
| Delete selected node(s) | Delete / Backspace |
| Zoom in/out | Scroll wheel |
| Pan canvas | Right-click drag |

---

### Schematic Support
All three computers fully support **Create's Schematicannon**. Your node graphs, parameters, and running state are preserved when saving and loading schematics — no data loss.

This means you can:
- **Build complex logic** in creative mode, then **print it in survival** with the Schematicannon
- **Copy and paste** computer configurations across your world
- **Share your creations** as schematic files with other players

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

### Source Code

📦 **GitHub Repository**: [https://github.com/y15173334444/create-schematic-compute](https://github.com/y15173334444/create-schematic-compute)

The project is fully open-source under the **MIT License**. Contributions, issues, and feature requests are welcome!

---

### License

MIT License © 2026 y15173334444

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

#### ⚡ 转速代理控制器
直接控制相邻 6 个面上机械动力**转速控制器**的目标 RPM。

- **转速控制** — 设置附近转速控制器的转速
- **私有信号输入** — 从命名通道读取浮点数

#### 🔌 编程计算机
专为时序逻辑设计的**编程计算机**，适用于延时、计数和脉冲控制。

- **红石 I/O** — 通过机械动力的红石链接网络通信
- **专用时序节点**：延时、锁存器、T 触发器、脉冲延长、循环、保险

---

### 节点参考（28 种）

| 分类 | 节点 |
|------|------|
| **数值** | 常量、红石输入、私有信号输入 |
| **运算** | 加、减、乘、除、模运算、次幂、次方根、绝对值、比较路由、向上取整、向下取整 |
| **逻辑** | 大于、小于、等于、布尔（反转） |
| **控制** | PID 控制器（误差归零时 I 项复位）、动力 PID、限幅、映射范围 |
| **输出** | 红石输出、私有信号输出、转速控制 |
| **时序** | 延时、锁存器、T 触发器、脉冲延长、循环、保险 |

#### 详细节点表

##### 数值 (Values)
| 节点 | 输入 | 输出 | 参数 | 说明 |
|------|------|------|------|------|
| CONST | - | float | value | 输出常量值 |
| REDSTONE_IN | - | signal | 频率物品×2 | 从机械动力红石链接读取信号 |
| PRIVATE_IN | - | val | channel | 从命名通道读取浮点数 |

##### 运算 (Math)
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
| Comparison Router | A, B | A, B | A>=B 时 A 口输出 A-B，否则 B 口输出 \|B-A\| |
| CEIL | in | int | 向上取整 |
| FLOOR | in | int | 向下取整 |

##### 逻辑 (Logic)
| 节点 | 输入 | 输出 | 参数 | 说明 |
|------|------|------|------|------|
| GT | A, B | bool | - | A > B → 1 |
| LT | A, B | bool | - | A < B → 1 |
| EQ | A, B | bool | - | A = B → 1 |
| BOOL | in | bool | inverted | 输入 > 0 → 1, ≤ 0 → 0; inverted=1 时反转 |

##### 控制 (Control)
| 节点 | 输入 | 输出 | 参数 | 说明 |
|------|------|------|------|------|
| PID | SP | ctrl | kp, ki, kd, scale | PID 控制器，输出 0~16，误差归零时 I 项复位 |
| PID_POWER | SP, base | power | kp, ki | 带初始动力的 PID，输出 0~16 |
| CLAMP | In, Min, Max | float | - | 限幅 |
| MAP | In, InMin, InMax, OutMin, OutMax | float | - | 映射范围 |

##### 输出 (Output)
| 节点 | 输入 | 输出 | 参数 | 说明 |
|------|------|------|------|------|
| REDSTONE_OUT | In | - | 频率物品×2 | 将信号写入机械动力红石链接 |
| PRIVATE_OUT | val | - | channel | 将浮点数写入命名通道 |
| SPEED_CTRL | speed, dir | rpm | - | 设置转速控制器的 RPM; dir>0.5 时反转方向 |

##### 时序 (Sequential) — 仅编程计算机
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
| 编辑参数 | 左键节点 |
| 连接节点 | 从输出端口拖到输入端口 |
| 删除节点/连线 | 右键节点或连线 |
| 框选节点 | TAB + 左键拖拽 |
| 切换选中 | TAB + 左键点击节点 |
| 拖拽移动选中 | TAB + 左键拖拽已选中节点 |
| 复制节点 | Ctrl + D |
| 删除选中节点 | Delete / Backspace |
| 缩放 | 滚轮 |
| 平移画布 | 右键拖拽 |

### 蓝图兼容
三台计算机完全支持**机械动力的蓝图大炮（Schematicannon）**。节点图、参数和运行状态在保存和放置蓝图时都会完整保留，不会丢失数据。

这意味着你可以：
- **在创造模式搭建复杂逻辑**，然后在**生存模式用蓝图大炮打印出来**
- 在世界中**复制粘贴**计算机配置
- 将你的创作**分享为蓝图文件**给其他玩家

---

### 合成配方

| 方块 | 材料 |
|------|------|
| **蓝图计算机** | 无线红石信号终端 ×2 + 精密构件 + 玻璃板 ×2 + 中继器 + 比较器 + 黄铜外壳 ×2 |
| **转速代理控制器** | 黄铜锭 ×4 + 齿轮 + 玻璃板 ×2 + 比较器 + 安山岩外壳 |
| **编程计算机** | 安山岩外壳 ×4 + 中继器 + 玻璃板 ×2 + 比较器 + 安山合金 |

*(需要 JEI 模组在游戏中查看)*

---

### 依赖

- **Minecraft**: 1.21.1
- **NeoForge**: 21.1.233+
- **Create**: 6.0.10+

---

### 源代码

📦 **GitHub 仓库**：[https://github.com/y15173334444/create-schematic-compute](https://github.com/y15173334444/create-schematic-compute)

本项目完全开源，基于 **MIT 许可证**。欢迎提交 Issue 和 Pull Request！

---

### 许可

MIT License © 2026 y15173334444

---

## 📝 Changelog

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
