# Create: Schematic Compute

[![GitHub](https://img.shields.io/badge/GitHub-y15173334444/create--schematic--compute-blue)](https://github.com/y15173334444/create-schematic-compute)
[![License](https://img.shields.io/badge/License-MIT-green)](LICENSE)
[![NeoForge](https://img.shields.io/badge/NeoForge-21.1.233-orange)](https://neoforged.net/)
[![Create](https://img.shields.io/badge/Create-6.0.10-brightgreen)](https://www.curseforge.com/minecraft/mc-mods/create)

一个机械动力（Create）附属模组，添加三种可编程计算机：**蓝图计算机**、**转速代理控制器**、**编程计算机**。

---

## 📦 方块总览

### 1. 蓝图计算机 (Blueprint Computer)
通过节点图编程控制机械动力的**红石链接网络**。
- **红石输入** — 从机械动力红石链接频段读取信号
- **红石输出** — 将信号写入机械动力红石链接频段
- **私有信号输出** — 通过命名通道传输浮点数到其他计算机

### 2. 转速代理控制器 (Speed Proxy Controller)
通过节点图直接控制机械动力**转速控制器**的目标转速。
- **转速控制** — 将计算结果设为附近转速控制器的 RPM
- **私有信号输入** — 从命名通道读取浮点数

### 3. 编程计算机 (Program Computer)
通过节点图编程实现**时序逻辑**，通过机械动力红石链接网络 I/O。
- 专用时序节点（延时、锁存器、T 触发器、脉冲延长、循环、保险）

---

## 🔷 节点参考（24 种）

### 数值 (Values)
| 节点 | 输入 | 输出 | 参数 | 说明 |
|------|------|------|------|------|
| CONST | - | float | value | 输出常量值 |
| REDSTONE_IN | - | signal | 频率物品×2 | 从机械动力红石链接读取信号 |
| PRIVATE_IN | - | val | channel | 从命名通道读取浮点数 |

### 运算 (Math)
| 节点 | 输入 | 输出 | 说明 |
|------|------|------|------|
| ADD | A, B | float | A + B |
| SUB | A, B | float | A - B |
| MUL | A, B | float | A × B |
| DIV | A, B | float | A ÷ B（B=0 时返回 0） |
| MOD | A, B | float | A % B（取模） |
| CEIL | in | int | 向上取整 |
| FLOOR | in | int | 向下取整 |

### 逻辑 (Logic)
| 节点 | 输入 | 输出 | 说明 |
|------|------|------|------|
| GT | A, B | bool | A > B → 1 |
| LT | A, B | bool | A < B → 1 |
| EQ | A, B | bool | A = B → 1 |

### 控制 (Control)
| 节点 | 输入 | 输出 | 参数 | 说明 |
|------|------|------|------|------|
| PID | SP, PV | ctrl | kp, ki, kd, scale | PID 控制器，输出 0~16，误差归零时 I 项复位 |
| PID_POWER | SP, PV, base | power | kp, ki, kd | 带初始动力的 PID，输出 0~16 |
| CLAMP | In, Min, Max | float | - | 限幅 |
| MAP | In, InMin, InMax, OutMin, OutMax | float | - | 映射范围 |

### 输出 (Output)
| 节点 | 输入 | 输出 | 参数 | 说明 |
|------|------|------|------|------|
| REDSTONE_OUT | In | - | 频率物品×2 | 将信号写入机械动力红石链接 |
| PRIVATE_OUT | val | - | channel | 将浮点数写入命名通道 |
| SPEED_CTRL | speed | - | - | 设置附近转速控制器的 RPM |

### 时序 (Sequential) — 仅编程计算机
| 节点 | 输入 | 输出 | 参数 | 说明 |
|------|------|------|------|------|
| DELAY | in | out | ticks | 延时 N tick 后输出 |
| LATCH | S, R | q | - | S≥1 置位，R≥1 复位，保持 |
| T_FLIPFLOP | in | tog | - | 上升沿翻转输出 |
| PULSE_EXTEND | in | pulse | ticks | 输入高电平时脉冲延长 N tick |
| LOOP | in | clk | count, interval | 收到触发后每 interval tick 输出脉冲，重复 count 次 |
| FUSE | in | pulse | cooldown | 收到信号→2 tick 脉冲→冷却 N tick |

---

## 🔧 使用方法

### 蓝图计算机 → 红石链接
1. 放置蓝图计算机 + 机械动力红石链接收发器
2. 编辑 REDSTONE_IN/OUT 节点，设置频率物品
3. 频率物品与红石链接收发器中的物品一致
4. 按 **Compile** → **Run**

### 转速代理 → 转速控制器
1. 放置转速代理控制器 + 机械动力转速控制器（6 格范围内）
2. 添加 CONST → SPEED_CTRL，设置目标 RPM
3. 按 **Run**
4. 转速控制器会自动被设置为目标转速

### 编程计算机 → 红石编程
1. 放置编程计算机，配置 REDSTONE_IN/OUT 频率物品
2. 使用时序节点实现延时、计数、脉冲等逻辑
3. 通过红石链接收发器连接普通红石电路

---

## 🎨 操作说明

| 操作 | 功能 |
|------|------|
| **左键节点** | 打开编辑面板（参数/频率/通道名） |
| **右键空白** | 打开添加节点菜单（分类折叠） |
| **左键拖拽连线端口** | 连接节点 |
| **右键节点/连线** | 删除 |
| **Ctrl+D** | 复制选中节点 |
| **滚轮** | 缩放 |
| **右键拖拽** | 平移画布 |
| **Compile** | 编译保存图形 |
| **Run / Stop** | 启动/停止运行 |

---

## 📄 许可

MIT License © 2026 y15173334444

GitHub: https://github.com/y15173334444/create-schematic-compute
