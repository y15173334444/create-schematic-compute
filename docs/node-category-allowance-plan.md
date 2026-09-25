# 节点分类与方块名单重构 / Node Category &amp; Per-Block Allowance Plan — ✅ 已实施

> **状态**：✅ **已实施（2026-09-25）**。规划起草于 2026-09-25，基线 `bb98e8c`；
> 实现落地 `NodeCategory` / `NodeAllowance` / `BlockNodeAllowances`，10 个屏幕改走
> `setNodeAllowance`。蓝图额外保留 `input_pose`（见 §4.5）。
> **目标**：把 10 份「逐节点枚举」的方块名单改为 **分类粒度白名单 + 类内黑名单**。
> Goal: replace ten per-node enumerations with **category-level allowlists plus
> in-category exclusions**.
> 关联：本文件是 2026-09-25「节点分类检查」与「每方块节点名单检查」两项审计的落地方案，
> 那两项发现的问题在此逐条给出处置。

---

## 核心结论 / Summary

1. **名单机制必须换**：当前 9 个方块逐节点枚举（CncGearbox 46 项、ProgramComputer 39 项…），
   新增节点要同步改 10 处，且已经漏了 —— 齿轮箱与程序计算机**能做 SIN/LOG 却不能做 ADD、
   不能比较大小**（缺 `math_basic` 与 `GT/LT/GE/LE/EQ`），就是逐节点枚举漏项的直接后果。
2. **换机制前必须先切分类边界**：`SPEED_CTRL` 被**全部 10 个方块**排除、`TX_OUT` 只有变速器要、
   `TARGET_OUT` 只有雷达要 —— 这几个节点在现分类里是异类。若直接按现分类做白名单，
   每个方块都要挂一串类内例外，**等于没简化**。所以本方案分两步：先切分类，再换机制。
3. **切完后 10 个方块里 8 个的类内例外为 0**，只有 2 处例外（见 §4）。

---

## 1. 现状 / Current state

| 项 | 事实 |
|---|---|
| 分类 | `NodeAddMenu.CATEGORIES`，14 个，覆盖 95 个 `NodeType`（无遗漏 / 无重复） |
| 名单 | 10 个 `*Screen.setNodeFilter` → `GraphEditor.nodeFilter` |
| 名单风格 | **Blueprint 黑名单**（排除 34 项、放行 61）；其余 **9 个白名单**（逐节点枚举） |
| 子图 | `GraphEditor:627` 覆盖为只允许 `ENCAP_INPUT` / `ENCAP_OUTPUT`（本次不动） |
| 权威 | `docs/node-guide.md` 逐节点声明归属（"仅控制椅" / "仅显示器图" / "仅动力宿主图"…） |

审计发现的问题（本方案逐条处置）：

| # | 问题 | 处置 |
|---|---|---|
| A | `STRESS`/`RPM` 与文档冲突：文档"仅动力宿主图"，蓝图（黑名单）未排除 → 蓝图可放 | §4 蓝图不再含 `kinetic` |
| B | 齿轮箱 / 程序计算机缺 `math_basic` 与比较运算，却有全套三角对数 | §5 能力补充（**已定夺**：按 §3 表全开 23 项） |
| C | 蓝图黑名单默认放行，新增节点自动进入蓝图 | §4 蓝图改白名单，与其余一致 |
| D | `ACCUMULATOR`/`INTEGRATOR` 与同类时序节点处理不一致（蓝图排除 DELAY 系却允许这两个） | §2 拆 `sequential` 为两簇，语义自然 |
| E | `ROUND` 与 `CEIL`/`FLOOR` 分家；`SQRT/LN/LOG/EXP` 挤在"三角函数" | §2 一并归位 |
| F | `COMMENT` 注释说"并入 display"、实现在 `debug` | §2 注释与实现取其一（**已定夺**：归 `debug`，不单独开类） |

---

## 2. 步骤 1 —— 切分类边界 / Step 1: redraw category boundaries

原则：**一个分类要么被某个方块整类接受，要么整类不接受**；做不到就把分类拆开。

| 现分类 | 问题 | 调整 |
|---|---|---|
| `values` | 干净 | 保留 |
| `math_basic` | 缺 `ROUND` | 移入 `ROUND`；同时移入 `SQRT`/`LN`/`LOG`/`EXP`（与 `POW`/`ROOT` 同族） |
| `math_advanced` | 混装：公式 / 姿态转换 / 拆分 / 插值 / 取整 | 移出 `ROUND`；移出 `POSE_CONVERT`+`SPLIT` 到新 `input_pose` |
| `trig` | 混装：`SQRT/LN/LOG/EXP` 不是三角函数；`DIRECTION` 是参数化数据源 | 移出 `SQRT/LN/LOG/EXP` 到 `math_basic`；`DIRECTION` 移入 `math_advanced` |
| `logic` | 干净 | 保留 |
| `control` | 干净 | 保留 |
| `sequential` | 混装：蓝图只接受 `ACCUMULATOR`/`INTEGRATOR` | **拆**为 `sequential_acc`（累加 / 积分）与 `sequential_state`（延时 / 锁存 / 触发器 / 脉冲 / 循环 / 保险丝） |
| `input` | 严重混装：键鼠手柄 / 视角 / 姿态运动 / 雷达目标 | **拆**为 `input_ctrl`、`input_view`、`input_motion`；`TARGET_OUT` 独立为 `radar` |
| `gearbox` | 混装：`TX_OUT` 属变速器 | 移出 `TX_OUT` 到新 `transmission` |
| `kinetic` | 干净（`STRESS`/`RPM`） | 保留 |
| `output` | 混装：`SPEED_CTRL` 被全部方块排除（仅转速代理要） | 移出 `SPEED_CTRL` 到新 `speed` |
| `display` | 干净 | 保留 |
| `structure` | `ENCAPSULATION`（主图，仅蓝图）与 `ENCAP_INPUT/OUTPUT`（仅子图）用途不同 | 保留；`ENCAP_INPUT/OUTPUT` 由子图过滤器负责（不动），主图名单不含本类（蓝图除外，见 §4） |
| `debug` | 干净（含 `COMMENT`） | 保留；`COMMENT` 归 `debug`（已定夺，2026-09-25）——单独开类会占用节点菜单高度，不拆 |

### 步骤 1 产出：新分类表（21 类）

```
values            CONST, REDSTONE_IN, PRIVATE_IN, BUS_IN
math_basic        ADD, SUB, MUL, DIV, MOD, POW, ROOT, ABS, CEIL, FLOOR,
                  ROUND, SQRT, LN, LOG, EXP
math_advanced     FORMULA, INTERP, DIRECTION
trig              SIN, COS, TAN, ASIN, ACOS, ATAN2, SINH, COSH, SEC, CSC, COT,
                  ANGLE_UNWRAP
logic             GT, LT, GE, LE, EQ, BOOL, GATE, OR, RELAY_A, RELAY_B
control           PID, PID_POWER, CLAMP, MAP
sequential_acc    ACCUMULATOR, INTEGRATOR
sequential_state  DELAY, LATCH, T_FLIPFLOP, PULSE_EXTEND, LOOP, FUSE
input_ctrl        KEYBOARD, MOUSE_BUTTON, MOUSE_JOYSTICK,
                  GAMEPAD_JOYSTICK, GAMEPAD_BUTTON, GAMEPAD_TRIGGER
input_view        VIEW_ANGLE, WORLD_VIEW
input_motion      ATTITUDE, FORWARD, ACCELERATION, VELOCITY, POSITION
input_pose        POSE_CONVERT, SPLIT          （新）
gearbox           MOVE, ROTATE, WAIT, CLUTCH, ENCODER
transmission      TX_OUT                        （新）
speed             SPEED_CTRL                    （新）
kinetic           STRESS, RPM
output            REDSTONE_OUT, PRIVATE_OUT, BUS_OUT
radar             TARGET_OUT                    （新）
display           TEXT, DATA, IMAGE, IMAGE_SEQUENCE, HUD_PITCH_LADDER
structure         ENCAPSULATION, ENCAP_INPUT, ENCAP_OUTPUT
debug             DEBUG_SIGNAL_GEN, DEBUG_PROBE, COMMENT
```

覆盖校验：**95 个枚举必须全部落入且仅落入一类**（脚本比对，无遗漏 / 无重复）。

---

## 3. 步骤 2 —— 换名单机制 / Step 2: category allowlist + in-category exclusions

### 数据结构

```java
public enum NodeCategory {
    VALUES("category.create_schematic_compute.values", CONST, REDSTONE_IN, ...),
    ...;
    public final String langKey;
    public final EnumSet<NodeType> types;
}

/** 反查：NodeType → 所属分类（构建时填充，运行期 O(1)）。
 *  Reverse map: NodeType → its category. */
private static final Map<NodeType, NodeCategory> CATEGORY_OF;

/** 方块的节点准入：分类白名单 + 类内黑名单。
 *  A block's allowance: category allowlist plus in-category exclusions. */
public record NodeAllowance(Set<NodeCategory> categories, Set<NodeType> exclusions) {
    public boolean allows(NodeType t) {
        if (exclusions.contains(t)) return false;
        var c = CATEGORY_OF.get(t);
        return c != null && categories.contains(c);
    }
}
```

`AbstractGraphScreen` 增 `setNodeAllowance(NodeAllowance)`；`setNodeFilter(Predicate)`
保留（子图过滤器等一次性谓词仍需要）。

### 各方块新名单

| 方块 | 允许的分类 | 类内黑名单 |
|---|---|---|
| Blueprint | values, math_basic, math_advanced, trig, logic, control, output, **sequential_acc**, debug, structure | `ENCAP_INPUT`, `ENCAP_OUTPUT`（主图；子图由 `GraphEditor:627` 负责） |
| ProgramComputer | values, math_basic, math_advanced, trig, logic, control, output, sequential_acc, sequential_state, debug | — |
| CncGearbox | values, math_basic, math_advanced, trig, logic, control, output, sequential_acc, sequential_state, gearbox, kinetic, debug | — |
| KineticGauge | values, logic, kinetic, output, debug | — |
| ControlSeat | input_ctrl, input_view, input_motion, input_pose, output, debug | `FORWARD`（文档已注明控制椅菜单不列出它） |
| Sensor | input_motion, input_pose, output, debug | — |
| Monitor | values, display, debug | — |
| Radar | output, radar, debug | — |
| SpeedProxy | values, speed, debug | — |
| Transmission | values, kinetic, transmission, debug | — |

**10 个方块中 8 个零例外**，仅 Blueprint（2 项）与 ControlSeat（1 项）有类内黑名单。

---

## 4. 步骤 3 —— 行为变更 / Step 3: behaviour changes

按上面表格迁移后，相对现状会发生这些**玩家可感知**的变化：

1. **蓝图计算机失去 `STRESS` / `RPM`**（对齐文档"仅动力宿主图"，修问题 A）—— 已存在于玩家图里的
   这类节点不会被删除，但无法再新建。
2. **蓝图计算机保留 `ACCUMULATOR` / `INTEGRATOR`**（已定夺，2026-09-25）：上表给蓝图保留
   `sequential_acc`，即**保留**这两个（维持现状）。业务上蓝图需要这两个节点，即使「蓝图放置后
   状态丢失」也接受——不从蓝图去掉。
3. **齿轮箱 / 程序计算机获得整类能力补齐**（已定夺，2026-09-25：按 §3 表全开，零类内例外）。
   相对现状 39 项白名单净增 **23 个**：
   - `math_basic` 补缺 11：`ADD` `SUB` `MUL` `DIV` `MOD` `POW` `ROOT` `ABS` `CEIL` `FLOOR` `ROUND`
     （`SQRT`/`LN`/`LOG`/`EXP` 已有，仅归类搬家）
   - `logic` 补缺 6：`GT` `LT` `GE` `LE` `EQ` + `OR`（`BOOL`/`GATE`/`RELAY_A`/`RELAY_B` 已有）
   - `math_advanced` 补缺 2：`FORMULA` `INTERP`（`DIRECTION` 已有）
   - `control` 整类 4：`PID` `PID_POWER` `CLAMP` `MAP`（原先一个都没有）
   这是能力净增，会影响 §4.1 的"允许集不变"回归基线，需单列。
4. **菜单分类结构变化**：`input` 拆成四类、`sequential` 拆两类、新增
   `transmission`/`speed`/`radar`/`input_pose`。玩家看到的分组会变。
5. **蓝图保留 `input_pose`（实施时修正）**：`POSE_CONVERT`/`SPLIT` 原在蓝图可用
   （旧 `math_advanced`），迁到 `input_pose` 后 §3 表未把该类给蓝图——会静默砍掉能力。
   实现里给蓝图补上 `input_pose`，保持能力不变。传感器按 §3 表**净增**这两个
   （姿态转换归传感器语义自然）。

---

## 5. 注意事项 / Caveats

- **子图过滤器不动**：`GraphEditor:627` 的 `ENCAP_INPUT`/`ENCAP_OUTPUT` 过滤器与本次正交。
- **`isMonitorOnly()` 仍需保留**：`HUD_PITCH_LADDER` 属 `display`，蓝图不含 display 即可覆盖，
  但当迁移未完成时应保留该方法作为兜底（可在迁移完成后评估是否删除）。
- **分类拆分会改变菜单显示**：属 UI 变更，需在 CHANGELOG 记录。
- **提交拆分**：分类调整（步骤 1）与机制替换（步骤 2）分开提交，便于回滚；文档单独提交。
- **lang**：新增 9 个分类键（`input_ctrl`/`input_view`/`input_motion`/`input_pose`/
  `transmission`/`speed`/`radar`/`sequential_acc`/`sequential_state`），中英文都要加。

---

## 6. 验证清单 / Verification

- [x] **覆盖比对**：95 个 `NodeType` 全部落入且仅落入一个新分类（`NodeCategory` 静态初始化 + `NodeAllowanceMigrationTest`）
- [x] **允许集回归测试**：迁移前后每个方块的允许集逐一比对；差异**必须**等于 §4 声明的变更
      （`NodeAllowanceMigrationTest`，旧允许集固化为基线快照）
- [x] **类内例外最小化**：断言 8 个方块零例外、Blueprint 2 项、ControlSeat 1 项
- [x] **文档对齐**：`docs/node-guide.md` 的归属声明与代码名单一致（`STRESS`/`RPM` 不再冲突）
- [x] **全量测试**：迁移后 434 绿（含新增回归）
- [ ] **游戏内实测**：每个方块打开节点菜单，确认分组与可添加项符合上表；蓝图确认无
      `STRESS`/`RPM`；控制椅确认无 `FORWARD`
