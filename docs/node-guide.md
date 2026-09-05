# 节点指南（全节点参考）— Node Guide Reference

> 状态：审阅稿（内容同时写入 `en_us.json` / `zh_cn.json` 的 `gui.create_schematic_compute.guide.<TYPE>` 键，设置 → 节点指南面板读取同一份文案）。
> 本文由 `.workbuddy/guide-build` 流水线从 lang 键生成；节点名、引脚/参数数量来自 `graph/NodeType.java`。中英双语，中文为主。

## 通用约定 / Conventions

- 求值仅发生在服务端，每个游戏 tick（20 Hz）一次；所有数值为 32 位浮点，未连线输入按 0。 / Evaluation runs only on the server, once per game tick (20 Hz); all values are 32-bit floats and unwired inputs read 0.
- 布尔真值约定：除 BOOL（阈值 >0）外，一律 `> 0.5` 判高。 / Boolean truth: every node judges high at `> 0.5`, except BOOL which uses `> 0`.
- 参数引脚：数值参数可展开节点后编辑；多数类型还自动附带一个可连线输入引脚，接线后以连线值覆盖编辑值（断开后需重新编辑）。 / Param pins: numeric parameters are edited after expanding the node; most types also attach a wireable pin per parameter, and a wire overrides the edited value (re-edit after unplugging).
- 角度单位：全模组统一为「度」。 / Angles: degrees throughout.
- 引脚的 i18n 标签与节点名分别来自 `pin.*` / `node.*` lang 键；`guide.*` 键只承载本说明。 / Pin labels and node names come from the `pin.*` / `node.*` lang keys; `guide.*` keys carry only this copy.

## 审校注意 / Review notes

> 以下条目由代码调研标出，撰写文案时已按代码口径处理，仍有待人工复核或后续修复：
> - BOOL 真值阈值为「>0」与 README 旧表述「>0.5」不符，以代码为准；其它布尔判定一律 >0.5。
> - FUSE 实测可再触发间隔 = cooldown+1（README「≈2+cooldown」为近似措辞）；输出脉冲已修正为真正的 2 tick。
> - ENCODER 生产侧宿主此前漏接 setEncoderView 导致实机恒输出 0——已修复（齿轮箱构造器现注入编码器视图），若仍异常请复核。
> - POSE_CONVERT 的旋转正方向 / A、B 坐标轴系对应未在代码中注释，文案只描述其数学变换。
> - ACCELERATION / VELOCITY 的分量正方向与量纲（blocks/tick 系 vs m/s）代码口径不一，以实机为准。
> - English: BOOL truth is `> 0` in code despite older README wording; FUSE re-fire interval measures cooldown+1; ENCODER previously read 0 in game because the gearbox never injected the encoder view — fixed by wiring `setEncoderView` in the constructor; POSE_CONVERT axis conventions are uncommented; ACCELERATION/VELOCITY units follow the build.

---

## 1. 常量 / Constant

- **枚举名 / Type**: `CONST` · 稳定 id: `const`
- **接口 / Interface**: 0 入 in → 1 出 out · 参数 params: `value`

**说明（中文）**

常量源：输出一个可编辑的数值供其他节点读取。展开节点后可直接在编辑框输入值，也可以从上游接线实时改写（接线后编辑框隐藏）；新建默认值为 1。适合做基准/系数，或临时把一段表达式结果改写为常数值。

**Description (English)**

Constant source: outputs an editable number for other nodes to read. Expand the node to type a value, or wire an upstream signal to override it live (the edit box hides once wired); new nodes default to 1. Good for baselines and coefficients, or pinning an expression's output to a fixed value.

---

## 2. 红石输入 / Redstone Input

- **枚举名 / Type**: `REDSTONE_IN` · 稳定 id: `redstone_in`
- **接口 / Interface**: 0 入 in → 1 出 out

**说明（中文）**

红石输入：按频率键从 Create 的红石链接网络读取信号强度（0-15）接入图内。编辑区的两个物品槽决定频率键——与配对的 REDSTONE_OUT 用相同的物品组合即可互通。

**Description (English)**

Redstone input: reads a signal strength (0-15) from Create's redstone-link network into the graph, keyed by frequency. The two item slots in its edit panel define the key — use the same pair of items as the matching REDSTONE_OUT.

---

## 3. 红石输出 / Redstone Output

- **枚举名 / Type**: `REDSTONE_OUT` · 稳定 id: `redstone_out`
- **接口 / Interface**: 1 入 in → 0 出 out

**说明（中文）**

红石输出：把图内数值写回 Create 的红石链接网络——先四舍五入、再钳到 0-15。两个物品槽决定频率键（与 REDSTONE_IN 配对）。

**Description (English)**

Redstone output: writes a graph value back to Create's redstone-link network, rounded and clamped to 0-15. The two item slots set the frequency key (pair them with a REDSTONE_IN).

---

## 4. 加法 / Add

- **枚举名 / Type**: `ADD` · 稳定 id: `add`
- **接口 / Interface**: 2 入 in → 1 出 out

**说明（中文）**

加法：输出 A+B。任一输入非有限（NaN/+/-∞）时整体输出 0；结果溢出不再复查。适合信号叠加与偏置。

**Description (English)**

Addition: outputs A+B. If either input is non-finite (NaN/+/-∞) the whole result is 0; overflow of the sum is not re-checked. Use for summing or offsetting signals.

---

## 5. 减法 / Subtract

- **枚举名 / Type**: `SUB` · 稳定 id: `sub`
- **接口 / Interface**: 2 入 in → 1 出 out

**说明（中文）**

减法：输出 A-B；任一输入非有限时输出 0。适合求差值与误差，如目标值减去当前值。

**Description (English)**

Subtraction: outputs A-B; non-finite inputs yield 0. Use for differences and error terms, e.g. target minus current.

---

## 6. 乘法 / Multiply

- **枚举名 / Type**: `MUL` · 稳定 id: `mul`
- **接口 / Interface**: 2 入 in → 1 出 out

**说明（中文）**

乘法：输出 A*B；输入非有限时输出 0。适合增益与缩放系数——注意未接线的输入按 0 读取，缩放时别留空悬端口（*0 恒为 0）。

**Description (English)**

Multiplication: outputs A*B; non-finite inputs yield 0. Use for gain and scaling — note an unwired input reads 0, so never leave a port dangling when scaling.

---

## 7. 除法 / Divide

- **枚举名 / Type**: `DIV` · 稳定 id: `div`
- **接口 / Interface**: 2 入 in → 1 出 out

**说明（中文）**

除法：输出 A÷B。除数为 0 或任一输入非有限时输出 0（不会产生 +/-∞/NaN）；极小但非零的除数仍可能令结果溢出。适合归一化与比例换算。

**Description (English)**

Division: outputs A÷B. A zero divisor or any non-finite input yields 0 (never +/-∞/NaN), though a tiny-but-finite divisor can still overflow the result. Use for normalising and ratios.

---

## 8. 模运算 / Modulo

- **枚举名 / Type**: `MOD` · 稳定 id: `mod`
- **接口 / Interface**: 2 入 in → 1 出 out

**说明（中文）**

浮点取模：输出 A%B。除数为 0 或输入非有限时输出 0。结果的符号跟随被除数 A（如 -5 % 3 = -2），不是恒非负的数学模。适合做循环/环绕量或余数判断。

**Description (English)**

Floating-point modulo: outputs A%B; a zero divisor or non-finite inputs yield 0. The sign follows the dividend A (-5 % 3 = -2), so it is a remainder, not a true modulo. Use for wrapping or remainder checks.

---

## 9. 次幂/POW / Power (A^B)

- **枚举名 / Type**: `POW` · 稳定 id: `pow`
- **接口 / Interface**: 2 入 in → 1 出 out

**说明（中文）**

幂运算：输出 |A|^B。底数先取绝对值，负底数开分数次幂不会产生 NaN；指数可为负（等价取倒数）。结果非有限时输出 0。适合多项式与反比类曲线。

**Description (English)**

Power: outputs |A|^B. The base is abs-ed first, so negative bases never produce NaN; a negative exponent means the reciprocal. Non-finite results collapse to 0. Polynomials and inverse curves.

---

## 10. 次方根/ROOT / Root (B-th Root of A)

- **枚举名 / Type**: `ROOT` · 稳定 id: `root`
- **接口 / Interface**: 2 入 in → 1 出 out

**说明（中文）**

方根：输出 A 的 B 次方根，即 A^(1/B)。被开方数 A<0 或 B=0 时输出 0；B 可为负或小数。SQRT 是它 B=2 的特例节点。

**Description (English)**

Root: outputs the B-th root of A, i.e. A^(1/B). A negative radicand or B = 0 yields 0; B may be negative or fractional. SQRT is the B = 2 special case.

---

## 11. 绝对值/ABS / Absolute Value

- **枚举名 / Type**: `ABS` · 稳定 id: `abs`
- **接口 / Interface**: 1 入 in → 1 出 out

**说明（中文）**

绝对值：输出输入的 |值|。无有限性预检，NaN 会原样传出。适合取幅度，或作为 SPLIT 的前级。

**Description (English)**

Absolute value: outputs |input| with no finiteness guard, so NaN passes through unchanged. Take magnitudes, e.g. ahead of SPLIT.

---

## 12. 比较路由/INTERP / Comparison Router (|A-B|)

- **枚举名 / Type**: `INTERP` · 稳定 id: `interp`
- **接口 / Interface**: 2 入 in → 2 出 out

**说明（中文）**

比较路由：把 |A-B| 只送给输入较大的一侧输出，另一侧输出 0——等价于 max(0, A-B) 与 max(0, B-A)。名字带 INTERP 但与「插值」无关。适合双向差值检测，如比较双引擎转速时超量只出现在偏高的一侧。

**Description (English)**

Comparison router: sends |A-B| to the output of whichever input is larger and 0 to the other — equivalent to max(0, A-B) and max(0, B-A). Despite the name it does not interpolate. Two-sided mismatch detection: the excess appears only on the higher side (e.g. comparing twin engine speeds).

---

## 13. 向上取整/CEIL / Ceil

- **枚举名 / Type**: `CEIL` · 稳定 id: `ceil`
- **接口 / Interface**: 1 入 in → 1 出 out

**说明（中文）**

向上取整：输出不小于输入的最小整数，负数向正无穷（ceil(-1.2) = -1）。值存于浮点容器但恒为整数值。适合把刻数/格数向上取整。

**Description (English)**

Ceiling: outputs the smallest integer not below the input; negatives go toward +∞ (ceil(-1.2) = -1). Stored as float but always integral. Round ticks or cells up.

---

## 14. 向下取整/FLOOR / Floor

- **枚举名 / Type**: `FLOOR` · 稳定 id: `floor`
- **接口 / Interface**: 1 入 in → 1 出 out

**说明（中文）**

向下取整：输出不大于输入的最大整数，负数向负无穷（floor(-1.2) = -2）。适合把数量向下取整。

**Description (English)**

Floor: outputs the largest integer not above the input; negatives go toward -∞ (floor(-1.2) = -2). Round quantities down.

---

## 15. 大于 / Greater Than

- **枚举名 / Type**: `GT` · 稳定 id: `gt`
- **接口 / Interface**: 2 入 in → 1 出 out

**说明（中文）**

大于：A>B 时输出 1，否则 0。精确比较、无容差，半开区间（A=B → 0）；NaN 参与时恒判假。适合阈值触发。

**Description (English)**

Greater-than: outputs 1 when A>B, else 0. Exact, no tolerance, half-open (A=B → 0); NaN never compares true. Threshold triggers.

---

## 16. 小于 / Less Than

- **枚举名 / Type**: `LT` · 稳定 id: `lt`
- **接口 / Interface**: 2 入 in → 1 出 out

**说明（中文）**

小于：A<B 时输出 1，否则 0。规则与大于相同（无容差）。适合下限触发。

**Description (English)**

Less-than: outputs 1 when A<B, else 0, with the same exact semantics as greater-than. Lower-bound triggers.

---

## 17. 等于 / Equals

- **枚举名 / Type**: `EQ` · 稳定 id: `eq`
- **接口 / Interface**: 2 入 in → 1 出 out

**说明（中文）**

等于：|A-B| < 0.01 时输出 1，否则 0——带 +/-0.01 的绝对容差。注意 FORMULA 语言内 `==` 的容差是 1e-6，与本节点不同。适合状态判定与对齐检测。

**Description (English)**

Equal: outputs 1 when |A-B| < 0.01 — an absolute +/-0.01 tolerance. Note FORMULA's `==` uses a 1e-6 tolerance instead. State checks and alignment detection.

---

## 18. 大于等于 / Greater Than or Equal

- **枚举名 / Type**: `GE` · 稳定 id: `ge`
- **接口 / Interface**: 2 入 in → 1 出 out

**说明（中文）**

大于等于：A>=B 时输出 1，闭区间（A=B → 1）。适合含边界的阈值判断。

**Description (English)**

Greater-or-equal: outputs 1 when A>=B; closed, so A=B → 1. Thresholds that include the bound.

---

## 19. 小于等于 / Less Than or Equal

- **枚举名 / Type**: `LE` · 稳定 id: `le`
- **接口 / Interface**: 2 入 in → 1 出 out

**说明（中文）**

小于等于：A<=B 时输出 1，闭区间（A=B → 1）。适合含边界的下限判断。

**Description (English)**

Less-or-equal: outputs 1 when A<=B; closed, so A=B → 1. Bounds that include the edge.

---

## 20. PID 控制器/PID / PID Controller

- **枚举名 / Type**: `PID` · 稳定 id: `pid`
- **接口 / Interface**: 2 入 in → 1 出 out · 参数 params: `kp, ki, kd, scale, ilimit`

**说明（中文）**

闭环 PID 控制器：sp 为目标值、pv 为当前值，输出控制量 ctrl = (kp*err + 积分项 + kd*微分项)*scale。误差 |err|<=0.001 时积分清零；积分贡献钳在 +/-ilimit（抗饱和），输出本身不限幅。kp/ki/kd/scale/ilimit 均可展开编辑或连线覆盖，默认 1/0.1/0.05/1/3。每张图建议不超过 5-6 个。

**Description (English)**

Closed-loop PID controller: sp is the setpoint and pv the measured value, producing ctrl = (kp*err + integral + kd*derivative)*scale. The integral clears when |err| <= 0.001 and its contribution is clamped to +/-ilimit (anti-windup); the output itself is not clamped. kp/ki/kd/scale/ilimit are editable and wire-overridable (defaults 1/0.1/0.05/1/3). Keep to about 5-6 per graph.

---

## 21. 动力 PID/PID_POWER / Power PID

- **枚举名 / Type**: `PID_POWER` · 稳定 id: `pid_power`
- **接口 / Interface**: 3 入 in → 1 出 out · 参数 params: `kp, ki, kd, ilimit`

**说明（中文）**

带基准功率的动力 PID：输出 power = base + kp*err + 积分项 + kd*微分项——base 作为前馈直加，不经增益也不限幅。无 scale 参数；sp/pv/base 任一非有限按 0 处理。kp/ki/kd/ilimit 可编辑或连线覆盖，默认 2/0.05/3/3。适合直接生成动力类指令。

**Description (English)**

PID with a base-power feed-forward: power = base + kp*err + integral + kd*derivative, where base adds directly with no gain or limiting. There is no scale parameter; a non-finite sp/pv/base is treated as 0. kp/ki/kd/ilimit are editable/wire-overridable (defaults 2/0.05/3/3). Drive-level power commands.

---

## 22. 限制/CLAMP / Clamp

- **枚举名 / Type**: `CLAMP` · 稳定 id: `clamp`
- **接口 / Interface**: 1 入 in → 1 出 out · 参数 params: `min, max`

**说明（中文）**

限幅：把输入限制到 [min, max]。min/max 默认 0，展开节点后可编辑或从上游接线覆盖。仅输入有 NaN 防护；若 min>max 结果会恒为 min，需自行保证 min<=max。适合给输出做安全限幅。

**Description (English)**

Clamp: bounds the input to [min, max]. Both bounds default to 0 and can be edited or wire-overridden after expanding the node. Only the value is NaN-guarded — if min > max the result sticks at min, so keep min <= max. Safety limiting of outputs.

---

## 23. 映射范围/MAP / Map Range

- **枚举名 / Type**: `MAP` · 稳定 id: `map`
- **接口 / Interface**: 1 入 in → 1 出 out · 参数 params: `in_min, in_max, out_min, out_max`

**说明（中文）**

范围映射：把 [in_min, in_max] 线性映射到 [out_min, out_max]。五路任一非有限 → 输出 0；输入区间等宽时直接输出 out_min；超出输入区间的值会线性外推，需要饱和请前置 CLAMP。适合传感器量程重映射。

**Description (English)**

Map: linearly remaps [in_min, in_max] onto [out_min, out_max]. A non-finite value on any of the five ports yields 0; a zero-width input range outputs out_min directly; out-of-range inputs extrapolate linearly (prepend CLAMP to saturate). Sensor range scaling.

---

## 24. 转速控制 / Speed Control

- **枚举名 / Type**: `SPEED_CTRL` · 稳定 id: `speed_ctrl`
- **接口 / Interface**: 2 入 in → 1 出 out

**说明（中文）**

转速控制：Speed Proxy（转速代理控制器）图专用，把目标转速直接写到相邻的 Create 转速控制器。speed 为目标转速；dir 大于 0.5 时反转（对 speed 取反号），未接线的 dir 视为正转。其声明输出在编辑器中不渲染、不可连线——宿主每 tick 直接读取，并把转速钳制在 +/-256，只作用于相邻 6 面的控制器。

**Description (English)**

Speed control: available only inside a Speed Proxy graph; writes a target RPM straight to an adjacent Create Speed Controller. speed is the target; dir > 0.5 reverses the sign (an unwired dir reads forward). Its declared output is not rendered or wireable — the host reads it each tick, clamps to +/-256, and applies it to controllers on the six neighbouring faces.

---

## 25. 布尔 / Bool (Toggle)

- **枚举名 / Type**: `BOOL` · 稳定 id: `bool`
- **接口 / Interface**: 1 入 in → 1 出 out · 参数 params: `inverted`

**说明（中文）**

布尔规整：输入严格大于 0 时输出 1，否则 0——把任意浮点规整为 0/1 布尔流，负数判假。inverted 是整行开关（无连线引脚），开启后输出取反，可当「非门」用。

**Description (English)**

Boolean: quantises any float to 1/0 — truth is strictly > 0, so negative values read false. The inverted toggle is a row switch (no pin) that flips the output, handy as a NOT gate.

---

## 26. 闸门 / Gate

- **枚举名 / Type**: `GATE` · 稳定 id: `gate`
- **接口 / Interface**: 4 入 in → 1 出 out · 参数 params: `default`

**说明（中文）**

可控信号闸门：开启时把 val 原样放行（任意浮点），关闭时输出 0。open / close 是电平控制（持续高恒开 / 恒关，open 优先），tog 为上升沿翻转，同时有效时 open/close 胜出。「初始状态：开启/关闭」是行内按钮开关。适合按条件放行一路信号。

**Description (English)**

Controlled signal gate: while open it passes val through unchanged (any float); while closed it outputs 0. open/close are level controls (sustained high keeps it open/closed; open wins), tog flips it on a rising edge, and open/close beat tog when simultaneous. The initial state is a row switch (Open/Closed). Conditionally let a signal through.

---

## 27. 或门 / OR Gate

- **枚举名 / Type**: `OR` · 稳定 id: `or`
- **接口 / Interface**: 2 入 in → 1 出 out

**说明（中文）**

或门：A 或 B 大于 0.5 时输出 1，否则 0。BOOL/比较节点的 1/0 输出可直接级联；NaN 判假。

**Description (English)**

OR: outputs 1 when either A or B exceeds 0.5, else 0. The 1/0 outputs of BOOL and comparisons cascade directly; NaN reads false.

---

## 28. 私有信号输入 / Private Signal Input

- **枚举名 / Type**: `PRIVATE_IN` · 稳定 id: `private_in`
- **接口 / Interface**: 0 入 in → 1 出 out

**说明（中文）**

私有信号输入：按频道名从进程级共享表读取一个浮点值。服务端任意位置同名 PRIVATE_OUT 写入的值这里都能读到——适合跨方块/跨计算机协调（读写先后随各宿主自己的求值节拍）。频道名最多 32 字符。

**Description (English)**

Private signal input: reads a single float from a process-wide table by channel name. Any PRIVATE_OUT with the same name anywhere on the server is visible here — cross-block, cross-computer coordination (reads follow each host's own tick order). Channel names are up to 32 characters.

---

## 29. 私有信号输出 / Private Signal Output

- **枚举名 / Type**: `PRIVATE_OUT` · 稳定 id: `private_out`
- **接口 / Interface**: 1 入 in → 0 出 out

**说明（中文）**

私有信号输出：每个 tick 把图内值写入同名频道（覆盖式写入），供任意宿主上的同名 PRIVATE_IN 读取。频道是单值、无频段结构，多个同名输出共享同一频道属正常现象（不是冲突）。方块卸载时频道会被清理。

**Description (English)**

Private signal output: writes the graph value into the named channel every tick (overwrite semantics), readable by any same-named PRIVATE_IN on any host. Channels hold a single value with no band structure, so several same-named outputs sharing one channel is normal, not a conflict. Channels are cleaned up when the block unloads.

---

## 30. 总线输入/BUS_IN / Bus Input

- **枚举名 / Type**: `BUS_IN` · 稳定 id: `bus_in`
- **接口 / Interface**: 0 入 in → 0 出 out

**说明（中文）**

总线输入：从同名 BUS_OUT 注册的频道按频段名读取一组值——每个频段对应一个输出引脚（引脚出现在编辑区内，不在节点体上）。频段由对端 BUS_OUT 定义，本侧只读；频道未注册时输出全 0。适合跨方块/跨设备共享多路信号。

**Description (English)**

Bus input: reads a banded channel owned by a same-named BUS_OUT — one output pin per band (the pins live in the edit panel, not on the node body). Bands are defined by the peer BUS_OUT and are read-only here; unregistered channels output zeros. Share multiple signals across blocks and devices.

---

## 31. 总线输出/BUS_OUT / Bus Output

- **枚举名 / Type**: `BUS_OUT` · 稳定 id: `bus_out`
- **接口 / Interface**: 0 入 in → 0 出 out

**说明（中文）**

总线输出：注册一个频道，并把图内各频段的值实时写入（每频段一个编辑区输入引脚），全局立即可见。频道注册权归首个注册者，第二台同名的 BUS_OUT 会显示冲突且不写入。频道名 <=32、频段名 <=16 字符，频段数上限 64。

**Description (English)**

Bus output: registers a channel and streams its banded values from the graph (one edit-panel input pin per band), visible globally in real time. Channel ownership belongs to the first registrant; a second same-named BUS_OUT is flagged as a conflict and does not write. Channels <=32 and bands <=16 characters, up to 64 bands.

---

## 32. 延时 / Delay

- **枚举名 / Type**: `DELAY` · 稳定 id: `delay`
- **接口 / Interface**: 1 入 in → 1 出 out · 参数 params: `ticks`

**说明（中文）**

延时：把输入数值原样（任意浮点、可负）延后 N 个 tick 输出。ticks 默认 10，可展开编辑或连线覆盖（<=0 按 1）。新建队列的头几个 tick 输出 0，队列填满前会比标称延时提前。适合对齐时序或过滤瞬时尖峰。

**Description (English)**

Delay: passes the input through unchanged (any float, negatives included) and emits it N ticks later. ticks defaults to 10 and is editable/wire-overridable (<=0 → 1). A fresh queue outputs 0 and reaches its nominal latency only once filled. Timing alignment or spike filtering.

---

## 33. 锁存器 / Latch

- **枚举名 / Type**: `LATCH` · 稳定 id: `latch`
- **接口 / Interface**: 2 入 in → 1 出 out · 参数 params: `default`

**说明（中文）**

S/R 锁存器：s 大于 0.5 置位、否则 r 大于 0.5 复位（电平触发，s 优先），输出严格 0/1。无连线参数，「初始状态：置位/复位」是行内按钮开关。持续高 s 会一直保持置位。编译或整图替换后从初始状态重启。适合保持/门闩逻辑。

**Description (English)**

S/R latch: s > 0.5 sets it, otherwise r > 0.5 resets it (level-triggered, s wins); the output is strictly 0/1. Its only setting is an initial-state row switch (Set/Reset) — no pins. A sustained high s keeps it set. Restarts from the initial state after recompiling or graph replacement. Hold/latch logic.

---

## 34. T 触发器 / T Flip-Flop

- **枚举名 / Type**: `T_FLIPFLOP` · 稳定 id: `t_flipflop`
- **接口 / Interface**: 1 入 in → 1 出 out · 参数 params: `default`

**说明（中文）**

T 触发器：输入上升沿（低→高，按 >0.5 判高）翻转一次输出，持续高电平只翻一次；输出严格 0/1。初始开/关是行内按钮开关。适合二分频或做切换状态。编译或整图替换后从初始状态重启。

**Description (English)**

T flip-flop: each rising edge (low→high, judged at > 0.5) toggles the output once; a sustained high toggles only once. Output is strictly 0/1 and the initial state is a row switch. Divide-by-two or state toggling. Restarts from the initial state after recompile or replacement.

---

## 35. 脉冲延长 / Pulse Extender

- **枚举名 / Type**: `PULSE_EXTEND` · 稳定 id: `pulse_extend`
- **接口 / Interface**: 1 入 in → 1 出 out · 参数 params: `ticks`

**说明（中文）**

脉冲延长（可重触发单稳）：输入拉高后，输出保持高 ticks 个 tick；期间再次拉高会重新计时（持续高 = 输出恒高）。ticks 默认 10，可编辑或连线覆盖（<=0 按 1）。把短脉冲拉长给后续时序逻辑使用。

**Description (English)**

Pulse extender (retriggerable one-shot): a high input drives the output high for ticks ticks; pulling it high again restarts the timer, so a sustained input stays high. ticks defaults to 10, editable/wire-overridable (<=0 → 1). Stretch short pulses for downstream sequential logic.

---

## 36. 循环 / Loop

- **枚举名 / Type**: `LOOP` · 稳定 id: `loop`
- **接口 / Interface**: 1 入 in → 1 出 out · 参数 params: `count, interval`

**说明（中文）**

循环脉冲发生器：输入上升沿（且处于空闲）启动后，每 interval 个 tick 输出一拍 1 tick 的高脉冲，共 count 次，从 clk 发出。count/interval 默认 5/10，可编辑或连线覆盖（<=0 按 1）。运行期间忽略新的上升沿，中途回落也不打断已启动的序列。

**Description (English)**

Loop pulse train: a rising edge (while idle) starts a run that emits a one-tick high every interval ticks, count times, on clk. count/interval default to 5/10 and are editable/wire-overridable (<=0 → 1). New rising edges are ignored while running, and dropping the input does not cancel the train.

---

## 37. 保险 / Safety Timer

- **枚举名 / Type**: `FUSE` · 稳定 id: `fuse`
- **接口 / Interface**: 1 入 in → 1 出 out · 参数 params: `cooldown`

**说明（中文）**

保险 / 安全定时器：触发后先输出 2 tick 脉冲，再进入 cooldown 冷却，冷却结束才允许再次触发。持续高输入会把它变成门控脉冲发生器（每冷却完触发一次）。cooldown 默认 40 tick，可编辑或连线覆盖（<=0 按 1）；实测可再触发间隔为 cooldown+1。防止同一事件在冷却期内重复触发。

**Description (English)**

Fuse / safety timer: after triggering it emits a 2-tick pulse and then cools down for cooldown ticks before it may fire again. A sustained high input turns it into a gated pulse generator (it fires once per cool-down). cooldown defaults to 40 ticks, editable/wire-overridable (<=0 → 1); the measured re-fire interval is cooldown+1. Keep one-shot events from re-triggering within the cool-down.

---

## 38. 保留N位小数/ROUND / Round (N Decimals)

- **枚举名 / Type**: `ROUND` · 稳定 id: `round`
- **接口 / Interface**: 1 入 in → 1 出 out · 参数 params: `decimals`

**说明（中文）**

保留小数：把输入四舍五入到 N 位小数。decimals 参数可展开编辑或连线覆盖，默认 2；只钳下限不钳上限，取值极大时可能溢出为 NaN/∞。负半值向正取整（round(-1.5) = -1）。适合量化显示或输出前的数据。

**Description (English)**

Round: rounds the input to N decimals. decimals is editable/wire-overridable and defaults to 2; it is only floor-clamped, so extreme values can overflow to NaN/∞, and -1.5 rounds to -1. Quantise displayed or exported values.

---

## 39. 正弦/SIN / Sine

- **枚举名 / Type**: `SIN` · 稳定 id: `sin`
- **接口 / Interface**: 1 入 in → 1 出 out

**说明（中文）**

正弦：输入角度（单位度），输出 [-1,1] 区间内的正弦值。非有限输入 → 0。

**Description (English)**

Sine: sine of the input angle in degrees, in [-1,1]. Non-finite inputs give 0.

---

## 40. 余弦/COS / Cosine

- **枚举名 / Type**: `COS` · 稳定 id: `cos`
- **接口 / Interface**: 1 入 in → 1 出 out

**说明（中文）**

余弦：输入角度（度）→ 余弦值，约定与正弦相同。

**Description (English)**

Cosine: cosine of the input angle in degrees. Same convention as sine.

---

## 41. 正切/TAN / Tangent

- **枚举名 / Type**: `TAN` · 稳定 id: `tan`
- **接口 / Interface**: 1 入 in → 1 出 out

**说明（中文）**

正切：输入角度（度）。+/-90度 等奇点附近没有保护，可能得到很大的值或 +/-∞（取决于 Java 双精度对该角度的表示）。非有限输入 → 0。

**Description (English)**

Tangent: tangent of the input angle in degrees. There is no guard near +/-90 degrees singularities — expect very large or +/-∞ values there; non-finite inputs give 0.

---

## 42. 反正弦/ASIN / Arc Sine

- **枚举名 / Type**: `ASIN` · 稳定 id: `asin`
- **接口 / Interface**: 1 入 in → 1 出 out

**说明（中文）**

反正弦：输入比例值（须在 [-1,1] 内），输出角度，范围 [-90,90] 度。越界或非有限输入 → 0。

**Description (English)**

Arcsine: input a ratio within [-1,1] and get an angle in [-90,90] degrees. Out-of-range or non-finite inputs give 0.

---

## 43. 反余弦/ACOS / Arc Cosine

- **枚举名 / Type**: `ACOS` · 稳定 id: `acos`
- **接口 / Interface**: 1 入 in → 1 出 out

**说明（中文）**

反余弦：输入 [-1,1] 内的比例值，输出 [0,180] 度的角度。越界或非有限输入 → 0。

**Description (English)**

Arccosine: input a ratio within [-1,1] and get an angle in [0,180] degrees. Otherwise 0.

---

## 44. 反正切2/ATAN2 / Arc Tangent 2

- **枚举名 / Type**: `ATAN2` · 稳定 id: `atan2`
- **接口 / Interface**: 2 入 in → 1 出 out

**说明（中文）**

四象限反正切：引脚 0 是 y、引脚 1 是 x（注意顺序），输出 (-180,180] 度的方向角。由两个分量求方向时用它；任一输入非有限 → 0。

**Description (English)**

Four-quadrant arctangent: pin 0 is y and pin 1 is x (mind the order); returns the direction angle in (-180,180] degrees. Use it to get a heading from two components; a non-finite input gives 0.

---

## 45. 双曲正弦/SINH / Hyperbolic Sine

- **枚举名 / Type**: `SINH` · 稳定 id: `sinh`
- **接口 / Interface**: 1 入 in → 1 出 out

**说明（中文）**

双曲正弦：输入输出均为原值——双曲函数没有周期，不做度→弧度换算。超大输入会溢出为 +/-∞。

**Description (English)**

Hyperbolic sine: raw value in, raw value out — hyperbolic functions are not periodic, so no degree conversion happens. Huge inputs can overflow to +/-∞.

---

## 46. 双曲余弦/COSH / Hyperbolic Cosine

- **枚举名 / Type**: `COSH` · 稳定 id: `cosh`
- **接口 / Interface**: 1 入 in → 1 出 out

**说明（中文）**

双曲余弦：原值输入输出；有限输入的结果 >=1。超大输入可溢出为 ∞。

**Description (English)**

Hyperbolic cosine: raw in and out; results are >=1 while finite. Huge inputs can overflow to ∞.

---

## 47. 平方根/SQRT / Square Root

- **枚举名 / Type**: `SQRT` · 稳定 id: `sqrt`
- **接口 / Interface**: 1 入 in → 1 出 out

**说明（中文）**

平方根：对 >=0 的输入输出 √x；负数或非有限输入 → 0。

**Description (English)**

Square root: √x for x >= 0; negative or non-finite inputs give 0.

---

## 48. 自然对数/LN / Natural Log

- **枚举名 / Type**: `LN` · 稳定 id: `ln`
- **接口 / Interface**: 1 入 in → 1 出 out

**说明（中文）**

自然对数 ln：输入须 >0 才有值；<=0 或非有限输入 → 0。

**Description (English)**

Natural logarithm: defined only for x > 0; non-positive or non-finite inputs give 0.

---

## 49. 常用对数/LOG / Base-10 Log

- **枚举名 / Type**: `LOG` · 稳定 id: `log`
- **接口 / Interface**: 1 入 in → 1 出 out

**说明（中文）**

常用对数（以 10 为底）：输入须 >0 才有值；<=0 或非有限输入 → 0。

**Description (English)**

Base-10 logarithm: defined only for x > 0; non-positive or non-finite inputs give 0.

---

## 50. 指数/EXP / Exponential

- **枚举名 / Type**: `EXP` · 稳定 id: `exp`
- **接口 / Interface**: 1 入 in → 1 出 out

**说明（中文）**

指数函数 eˣ：非有限输入 → 0；较大的有限输入（约 >88.7）会溢出为 ∞（不钳制）。

**Description (English)**

Exponential eˣ: non-finite inputs give 0; large finite inputs (≳88.7) overflow to ∞, unclamped.

---

## 51. 正割/SEC / Secant

- **枚举名 / Type**: `SEC` · 稳定 id: `sec`
- **接口 / Interface**: 1 入 in → 1 出 out

**说明（中文）**

正割（= 1/cos）：输入角度（度）。cos 接近 0 的奇点（+/-90度+/-k*180度，判定阈值 1e-12）或非有限输入 → 0。

**Description (English)**

Secant (1/cos): input in degrees. Near cos ~ 0 singularities (+/-90 degrees +/- k*180 degrees, threshold 1e-12) or non-finite inputs give 0.

---

## 52. 余割/CSC / Cosecant

- **枚举名 / Type**: `CSC` · 稳定 id: `csc`
- **接口 / Interface**: 1 入 in → 1 出 out

**说明（中文）**

余割（= 1/sin）：输入角度（度）。sin 接近 0 的奇点（0度+/-k*180度，阈值 1e-12）或非有限输入 → 0。

**Description (English)**

Cosecant (1/sin): input in degrees. Near sin ~ 0 singularities (0 degrees +/- k*180 degrees, threshold 1e-12) or non-finite inputs give 0.

---

## 53. 余切/COT / Cotangent

- **枚举名 / Type**: `COT` · 稳定 id: `cot`
- **接口 / Interface**: 1 入 in → 1 出 out

**说明（中文）**

余切（= 1/tan）：输入角度（度）。tan 接近 0 的奇点（0度+/-k*180度，阈值 1e-12）或非有限输入 → 0。

**Description (English)**

Cotangent (1/tan): input in degrees. Near tan ~ 0 singularities (0 degrees +/- k*180 degrees, threshold 1e-12) or non-finite inputs give 0.

---

## 54. 角度解绕/A_UNWRAP / Angle Unwrap

- **枚举名 / Type**: `ANGLE_UNWRAP` · 稳定 id: `angle_unwrap`
- **接口 / Interface**: 1 入 in → 1 出 out

**说明（中文）**

角度解绕：把逐 tick 输入的角度序列展开成连续累计角——每一步变化量折入 (-180,180]，输出随旋转超出 +/-180 也不会跳变。首次求值以当前输入为起点；累计状态跨 tick、跨重编译保留。适合跟踪转轴/编码器角度。单位度。

**Description (English)**

Angle unwrap: unfolds a per-tick angle series into a continuous accumulated angle — each step's change folds into (-180,180], so the output can exceed +/-180 without ever jumping. The first tick seeds from the current input and the state survives ticks and recompiles. Track shaft or encoder angles. Degrees.

---

## 55. 向量转角度/DIRECTION / Direction

- **枚举名 / Type**: `DIRECTION` · 稳定 id: `direction`
- **接口 / Interface**: 0 入 in → 3 出 out · 参数 params: `ax, ay, az, bx, by, bz`

**说明（中文）**

向量转角度：由 A→B 的世界空间差向量求 yaw/pitch/distance。A、B 两点共 6 个可编辑参数（展开后可用连线覆盖）。yaw = 0 指向 -Z、90 指向 +X，规范到 [0,360)；pitch 与相机符号一致（目标在上方为负）；distance 为三维欧氏距离。两点重合时输出全 0。求指向/瞄准角时使用，约定与 FORMULA 内建 yaw()/pitch() 一致。

**Description (English)**

Direction: from the world-space difference A→B it outputs yaw/pitch/distance. A and B are six editable parameters (wire-overridable once expanded). yaw = 0 points toward -Z and 90 toward +X, normalised to [0,360); pitch follows the camera sign convention (a target above reads negative); distance is 3-D Euclidean. Identical points output all zeros. Aiming/heading math, consistent with FORMULA's built-in yaw()/pitch().

---

## 56. 世界坐标/POSITION / World Position

- **枚举名 / Type**: `POSITION` · 稳定 id: `position`
- **接口 / Interface**: 0 入 in → 3 出 out · 参数 params: `offsetX, offsetY, offsetZ`

**说明（中文）**

世界坐标：输出宿主方块的世界空间坐标（格），可加偏移。挂在 Sable 结构上时，坐标按结构位姿换算为对主世界有意义的数值；偏移沿方块本地系旋转（+X 为方块右侧、+Z 为前方）。三个偏移 offsetX/Y/Z 可编辑或连线覆盖；无 Sable 时输出方块中心。

**Description (English)**

World position: the host block's world-space coordinates (in blocks), plus optional offsets. On a Sable structure the coordinates convert to main-world values via the structure pose; offsets rotate with the block's local frame (+X = block right, +Z = forward). offsetX/Y/Z are editable or wire-overridable; without Sable the block centre is output.

---

## 57. 累计器 / Accumulator

- **枚举名 / Type**: `ACCUMULATOR` · 稳定 id: `accumulator`
- **接口 / Interface**: 2 入 in → 1 出 out · 参数 params: `step`

**说明（中文）**

累计器：plus / minus 各自出现上升沿时，把累计值 +/-step（默认 1，可编辑或连线覆盖）；持续高只计一次，同 tick 双沿先加后减。累计值不钳制，可为负，float 累积需留意溢出。输出 val。适合事件计数。

**Description (English)**

Accumulator: each rising edge on plus/minus adds/subtracts step (default 1, editable/wire-overridable). A sustained high counts once; simultaneous edges in one tick net to zero. The value is unclamped — it can go negative and float accumulation can overflow. Outputs val. Event counting.

---

## 58. 连续积分器 / Continuous Integrator

- **枚举名 / Type**: `INTEGRATOR` · 稳定 id: `integrator`
- **接口 / Interface**: 3 入 in → 1 出 out · 参数 params: `step, interval, limit`

**说明（中文）**

连续积分器：每 interval 个 tick 采样一次电平——仅 plus 高则 +step、仅 minus 高则 -step、同高或同低则保持；clear 电平随时清零并重置节拍。累计值钳在 [0, limit]（step/interval/limit 默认 1/1/1000，均可编辑或连线覆盖）。把电平状态积成位置类量。

**Description (English)**

Integrator: samples the levels every interval ticks — plus alone adds step, minus alone subtracts, both-high or both-low holds; a high clear zeroes the value and resets the beat immediately. The value clamps to [0, limit] (defaults step=1, interval=1, limit=1000; all editable/wire-overridable). Integrate levels into a position-like quantity.

---

## 59. 公式/FORMULA / Formula

- **枚举名 / Type**: `FORMULA` · 稳定 id: `formula`
- **接口 / Interface**: 0 入 in → 1 出 out · 参数 params: `warm`

**说明（中文）**

公式：多行脚本节点，支持赋值、注释、if/while/repeat、vec3 向量与向量函数、@output 命名输出。引脚由脚本自动生成：被读取的名字成为输入（A-Z，最多 26 个）；每个 @output 成为一个输出（最多 16 个，vec3 输出自动展开成 .x/.y/.z 三个标量引脚）；没有 @output 时以最后一行独立表达式为唯一默认输出。warm 参数决定长循环跨 tick 求值时遇到输入变化是继续沿用旧输入还是刷新后接着算。内建 15 个标量函数（三角按度、双曲函数按原值）与 vec3/length/normalize/dot/cross/dist/yaw/pitch；比较 `==`/`!=` 的容差为 1e-6。语法详见 docs/formula-syntax-manual.md。

**Description (English)**

Formula: a multi-line scripting node with assignments, comments, if/while/repeat, vec3 vectors and vector functions, and @output named outputs. Pins are derived from the script: every read name becomes an input (A-Z, up to 26); every @output becomes an output (up to 16; vec3 outputs auto-expand into .x/.y/.z scalar pins); with no @output, the last standalone expression is the single default output. The warm parameter decides whether a long cross-tick loop refreshes its inputs when they change mid-run or keeps the old ones. Built-ins: 15 scalar functions (trig in degrees, hyperbolic functions raw) plus vec3/length/normalize/dot/cross/dist/yaw/pitch; `==`/`!=` use a 1e-6 tolerance. Syntax: see docs/formula-syntax-manual.md.

---

## 60. 姿态换算 / Pose Convert

- **枚举名 / Type**: `POSE_CONVERT` · 稳定 id: `pose_convert`
- **接口 / Interface**: 3 入 in → 2 出 out

**说明（中文）**

姿态换算：把 (pitch_a, yaw_a) 姿态对按 roll 在 pitch/yaw 平面内旋转，输出 (pitch_b, yaw_b)。全部角度单位度；是纯旋转变换，无归一化或有限性守卫。用于把某一参考系的姿态折算到另一参考系，具体轴系对应需结合使用场景确认。

**Description (English)**

Pose convert: rotates the (pitch_a, yaw_a) pair by roll within the pitch/yaw plane, outputting (pitch_b, yaw_b). All angles in degrees; a pure rotation with no normalisation or finiteness guards. Convert an attitude between reference frames — the exact axis conventions depend on the use case.

---

## 61. 键盘按键 / Keyboard Key

- **枚举名 / Type**: `KEYBOARD` · 稳定 id: `keyboard`
- **接口 / Interface**: 0 入 in → 1 出 out · 参数 params: `key`

**说明（中文）**

键盘按键：输出座位上玩家是否正按住所绑定按键，恒 0/1。点开绑定区后按下物理键即可重绑（key 索引覆盖 A-Z、0-9、Space、Shift/Ctrl/Alt、Enter 等 58 个键）。仅玩家骑乘控制椅时才有值，空座输出 0。仅控制椅编辑器的图内可用。

**Description (English)**

Keyboard key: outputs 1 while the seated player holds the bound key (strict 0/1). Click the bind zone and press a physical key to rebind (58 keys: A-Z, 0-9, Space, Shift/Ctrl/Alt, Enter, etc.). Values appear only while a player rides the Control Seat; empty seat reads 0. Control Seat graphs only.

---

## 62. 鼠标摇杆 / Mouse Joystick

- **枚举名 / Type**: `MOUSE_JOYSTICK` · 稳定 id: `mouse_joystick`
- **接口 / Interface**: 0 入 in → 2 出 out · 参数 params: `abs`

**说明（中文）**

鼠标摇杆：把鼠标位移转成双轴摇杆，x 水平、y 垂直。默认增量模式：只在鼠标移动的 tick 输出（~满偏 3度/tick，钳 +/-1），停手归零；abs=1 绝对模式把位移累积成带记忆的摇杆位置（钳 +/-1）。仅 FIXED 相机模式且界面关闭时采样，VIEW_DIFFERENCE 模式恒 0。仅控制椅。

**Description (English)**

Mouse joystick: turns mouse movement into a two-axis stick (x horizontal, y vertical). Increment mode (default) reports only while the mouse moves (~3 degrees/tick of movement, clamped +/-1) and returns to zero on rest; abs=1 accumulates movement into a remembered stick position (clamped +/-1). Sampled only in FIXED camera mode with the GUI closed; always 0 in VIEW_DIFFERENCE mode. Control Seat only.

---

## 63. 视角差 / View Angle

- **枚举名 / Type**: `VIEW_ANGLE` · 稳定 id: `view_angle`
- **接口 / Interface**: 0 入 in → 2 出 out

**说明（中文）**

视角差：玩家视线相对座椅世界前方的俯仰/偏航差，单位度。TAB 切到 VIEW_DIFFERENCE 模式才有真实值（yaw 规范到 +/-180，pitch 为原生差值）；FIXED 模式输出 0。无 Sable 也能用（座椅朝向回落实体 yaw）。仅控制椅。

**Description (English)**

View difference: how far the player's view is from the seat's world-forward, in degrees (pitch/yaw). Real values appear only in VIEW_DIFFERENCE mode (TAB); yaw normalises to +/-180, pitch is the raw difference. FIXED mode outputs 0. Works without Sable (the seat falls back to the entity yaw). Control Seat only.

---

## 64. 鼠标按键 / Mouse Button

- **枚举名 / Type**: `MOUSE_BUTTON` · 稳定 id: `mouse_button`
- **接口 / Interface**: 0 入 in → 2 出 out

**说明（中文）**

鼠标按键：左/右两路按下状态 0/1，读原始 GLFW 键状态——即便正常点击动作被吞掉也能检测到按下。中键会被采集但不输出。仅控制椅、骑乘时有效。

**Description (English)**

Mouse buttons: left/right press states (0/1) read from the raw GLFW state, so presses register even though normal click actions are swallowed. The middle button is sampled but not exposed. Control Seat only, while riding.

---

## 65. 手柄摇杆 / Gamepad Joystick

- **枚举名 / Type**: `GAMEPAD_JOYSTICK` · 稳定 id: `gamepad_joystick`
- **接口 / Interface**: 0 入 in → 4 出 out

**说明（中文）**

手柄摇杆：输出 1 号手柄左右摇杆四轴 lx/ly/rx/ry，GLFW 原生范围 +/-1，未做翻转——按手柄惯例向上为负、向下为正。无手柄或无人骑乘时输出 0。仅控制椅。

**Description (English)**

Gamepad sticks: the four axes of gamepad 1's left/right sticks (lx/ly/rx/ry), passed through in native +/-1 without inversion — up reads negative and down positive, per the GLFW convention. Zero without a gamepad or rider. Control Seat only.

---

## 66. 手柄按键 / Gamepad Button

- **枚举名 / Type**: `GAMEPAD_BUTTON` · 稳定 id: `gamepad_button`
- **接口 / Interface**: 0 入 in → 1 出 out · 参数 params: `button`

**说明（中文）**

手柄按键：输出所选手柄按键的 0/1。button 默认 A（索引 0），点开绑定区后按实际手柄键即可重绑；序列表为 A/B/X/Y、LB/RB、Back/Start/Guide、L3/R3、方向键。仅控制椅。

**Description (English)**

Gamepad button: outputs 1 while the chosen button is held. Defaults to A (index 0); click the bind zone and press the real button to rebind. Order: A/B/X/Y, LB/RB, Back/Start/Guide, L3/R3, D-pad. Control Seat only.

---

## 67. 手柄扳机 / Gamepad Trigger

- **枚举名 / Type**: `GAMEPAD_TRIGGER` · 稳定 id: `gamepad_trigger`
- **接口 / Interface**: 0 入 in → 2 出 out

**说明（中文）**

手柄扳机：左右模拟扳机量 lt/rt，范围 0..1（负数半段按 0 截断）。仅控制椅。

**Description (English)**

Gamepad triggers: left/right analogue trigger amounts (lt/rt) in 0..1 — negative samples clamp to 0. Control Seat only.

---

## 68. 世界视角 / World View

- **枚举名 / Type**: `WORLD_VIEW` · 稳定 id: `world_view`
- **接口 / Interface**: 0 入 in → 2 出 out

**说明（中文）**

世界视角：玩家在世界空间的绝对视线方向——偏航/俯仰（度，yaw 规范到 +/-180）。只在 VIEW_DIFFERENCE 模式更新，并依赖 Sable 写入的实体朝向（子关卡外恒 0）。注意引脚顺序是 yaw、pitch，与「视角差」相反。仅控制椅。

**Description (English)**

World view: the player's absolute view direction in world space as yaw/pitch in degrees (yaw normalised to +/-180). Updated only in VIEW_DIFFERENCE mode and only when Sable writes the entity heading (0 outside a sub-level). Pin order is yaw, pitch — the reverse of View Difference. Control Seat only.

---

## 69. 姿态 / Attitude

- **枚举名 / Type**: `ATTITUDE` · 稳定 id: `attitude`
- **接口 / Interface**: 0 入 in → 2 出 out

**说明（中文）**

姿态：宿主结构的俯仰 + 横滚（度），不含 yaw。控制椅输出 Sable 子世界 logicalPose 的欧拉角；姿态传感器则推导方块自身在世界中的姿态（pitch 为前向仰角、正 = 抬头，roll 为上向量绕前向轴的倾角）。真实值依赖 Sable 子关卡物理，无 Sable 时输出 0。控制椅与传感器图内可用。

**Description (English)**

Attitude: the host structure's pitch + roll in degrees (yaw is not exposed). On a Control Seat these are the Euler angles of the Sable sub-world logical pose; a Sensor derives the block's own world attitude (pitch = forward elevation, positive = looking up; roll = the up vector's tilt around the forward axis). Real values need a Sable sub-level; 0 without one. Available in Control Seat and Sensor graphs.

---

## 70. 前方朝向 / Forward (World XY)

- **枚举名 / Type**: `FORWARD` · 稳定 id: `forward`
- **接口 / Interface**: 0 入 in → 2 出 out

**说明（中文）**

前方朝向：方块面朝方向在世界空间的偏航/俯仰（度，yaw 规范 +/-180）。控制椅侧 = 方块朝向经子世界相对旋转修正；传感器侧由前向向量推导（pitch 上为正）。无 Sable 时控制椅回落为方块 FACING、传感器输出 0。传感器图可用（控制椅编辑菜单未列出它，但拷入的节点仍按座椅侧数据求值）。

**Description (English)**

Forward: the block's facing in world space as yaw/pitch (degrees; yaw normalised to +/-180). On a Control Seat it is the facing corrected by the sub-world's relative rotation; a Sensor derives it from the forward vector with pitch positive upward. Without Sable the seat falls back to the raw block facing and the Sensor outputs 0. Available in Sensor graphs (the Control Seat menu omits it, but pasted copies still evaluate from seat data).

---

## 71. 加速度 / Acceleration

- **枚举名 / Type**: `ACCELERATION` · 稳定 id: `acceleration`
- **接口 / Interface**: 0 入 in → 3 出 out

**说明（中文）**

加速度：宿主方块本地坐标系的三轴加速度——X 前后、Y 上下、Z 左右。由 Sable 物理的速度每 tick 差分得出（Δt = 1 tick），仅在有 Sable 子关卡数据时有效，否则恒 0。控制椅与传感器图内可用。分量的正方向与量纲以实机为准（代码注释存在 blocks/tick² 与 m/s² 两种口径）。

**Description (English)**

Acceleration: the host block's acceleration along its local axes — X front/back, Y up/down, Z left/right — differenced from the Sable physics velocity every tick (Δt = 1 tick). Meaningful only with a Sable sub-level present (0 otherwise). Available in Control Seat and Sensor graphs. Positive directions and exact units follow the build (code comments disagree between blocks/tick² and m/s²).

---

## 72. 速度 / Velocity

- **枚举名 / Type**: `VELOCITY` · 稳定 id: `velocity`
- **接口 / Interface**: 0 入 in → 3 出 out

**说明（中文）**

速度：宿主方块本地坐标系的三轴速度——X 前后、Y 上下、Z 左右。仅由 Sable 物理每 tick 写入，节点输出为原始值 *2；无 Sable 时恒 0。控制椅与传感器图内可用（v1.1.4+）。分量的正方向与量纲以实机为准。

**Description (English)**

Velocity: the host block's velocity along its local axes — X front/back, Y up/down, Z left/right. Written only by Sable physics each tick; the node outputs 2* the raw value and reads 0 without Sable. Available in Control Seat and Sensor graphs (v1.1.4+). Positive directions and exact units follow the build.

---

## 73. 分割 / Split

- **枚举名 / Type**: `SPLIT` · 稳定 id: `split`
- **接口 / Interface**: 1 入 in → 2 出 out

**说明（中文）**

分割：把有符号输入拆成正部/负部两路（均 >=0）——正部 = max(0, v)，负部 = max(0, -v)，且恒有「正部 - 负部 = v」。分离正负信号时使用。

**Description (English)**

Split: separates a signed value into its positive and negative parts (both >= 0) — plus = max(0, v), minus = max(0, -v), and plus - minus always equals v. Positive/negative signal separation.

---

## 74. 文本 / Text

- **枚举名 / Type**: `TEXT` · 稳定 id: `text`
- **接口 / Interface**: 0 入 in → 0 出 out

**说明（中文）**

文本：显示器上的静态文本元素。内容在编辑区直接编辑（<=256 字符），颜色/布局/缩放/旋转/层级由显示界面调整（layerIndex 越大越靠前）。无引脚、不参与求值，图不运行也照常显示。仅显示器图。

**Description (English)**

Text: a static text element on the Monitor. Edit the copy inline (<=256 chars); colour, layout, scale, rotation and layer are set in the display editor (higher layerIndex renders in front). No pins and no evaluation — it shows even when the graph is not running. Monitor graphs only.

---

## 75. 数据显示 / Data Display

- **枚举名 / Type**: `DATA` · 稳定 id: `data`
- **接口 / Interface**: 1 入 in → 0 出 out

**说明（中文）**

数据显示：把入边数值实时画成文本，保留一位小数（如 12.3），默认绿色。依赖图的运行快照——未运行或快照缺失时不渲染（与静态的 TEXT/IMAGE 不同）。颜色与布局可调。仅显示器图。

**Description (English)**

Data display: draws the wired value live as text with one decimal place (e.g. 12.3), green by default. It depends on the graph's evaluation snapshot, so it is skipped while the graph is off (unlike static TEXT/IMAGE). Colour and layout are adjustable. Monitor graphs only.

---

## 76. 图像 / Image

- **枚举名 / Type**: `IMAGE` · 稳定 id: `image`
- **接口 / Interface**: 3 入 in → 0 出 out · 参数 params: `moveScaleX, moveScaleY, rotationScale, invertX, invertY`

**说明（中文）**

图像：显示器上的像素画布层。双击节点打开像素编辑器绘图（边长 1-32，默认 16*16）。x/y/rotation 三路输入实时驱动平移/旋转：默认每单位信号把画布移动 1% 宽度（moveScaleX/Y 可调），旋转量 = rotation*rotationScale；invertX/invertY 反转对应轴。图未运行时按输入 0 渲染。仅显示器图。

**Description (English)**

Image: a pixel-canvas layer on the Monitor. Double-click the node to draw in the pixel editor (edge 1-32; default 16*16). Inputs x/y/rotation drive pan and rotation live — by default each signal unit shifts the canvas by 1% of its width (moveScaleX/Y adjust this), and rotation = rotation * rotationScale; invertX/invertY flip the axes. With the graph off it renders at zero inputs. Monitor graphs only.

---

## 77. 图像序列 / Image Sequence

- **枚举名 / Type**: `IMAGE_SEQUENCE` · 稳定 id: `image_sequence`
- **接口 / Interface**: 4 入 in → 0 出 out · 参数 params: `moveScaleX, moveScaleY, rotationScale, invertX, invertY`

**说明（中文）**

图像序列：显示器上的多帧像素动画层。x/y 平移、frame 选择帧（四舍五入并钳到有效范围）、rotation 旋转；参数与 IMAGE 相同。像素编辑器带底部帧条：可新建、删除或拖拽重排帧；改尺寸会同步所有帧；删到最后一帧时清空为透明而不是删除帧。帧选择实时依赖求值。仅显示器图。

**Description (English)**

Image sequence: a multi-frame pixel animation layer on the Monitor. x/y pan, frame picks the frame (rounded and clamped) and rotation spins it; parameters match IMAGE. The pixel editor has a frame strip — add, delete or drag to reorder; resizing applies to every frame, and deleting the last frame clears it to transparent instead of removing it. Frame selection needs a running graph. Monitor graphs only.

---

## 78. 俯仰梯 / Pitch Ladder

- **枚举名 / Type**: `HUD_PITCH_LADDER` · 稳定 id: `hud_pitch_ladder`
- **接口 / Interface**: 2 入 in → 2 出 out · 参数 params: `range, interval`

**说明（中文）**

俯仰梯：显示器 AR-HUD（虚像大屏 HUD 模式）专用的姿态仪表——白地平线随 pitch 上下移动、绿色档线随 roll 绕中心旋转、按 +/-10度 步进画刻度。pitch/roll 输入原样透传到输出（供其它节点复用）。range 决定可视刻度范围、interval 决定刻度间隔（默认 90/5）。仅 HUD 模式渲染。

**Description (English)**

Pitch ladder: an AR-HUD attitude instrument for the Monitor's HUD mode — the horizon slides with pitch, the green wing bars rotate with roll and scale marks step by +/-10 degrees. pitch/roll pass straight through to the outputs for reuse. range sets the visible scale span and interval the mark spacing (defaults 90/5). Rendered in HUD mode only.

---

## 79. 封装 / Encapsulation

- **枚举名 / Type**: `ENCAPSULATION` · 稳定 id: `encapsulation`
- **接口 / Interface**: 0 入 in → 0 出 out

**说明（中文）**

封装：把一组节点打包成带动态引脚的单节点——输入/输出引脚由内部 ENCAP_INPUT / ENCAP_OUTPUT 的数量与名字决定；双击进入子图编辑，可导出为 .nbt 复用。子图内不允许再嵌套封装，导出时会剔除调试节点。只能在蓝图编辑器中新建。

**Description (English)**

Encapsulation: packs a group of nodes into a single node with dynamic pins — inputs/outputs mirror the inner ENCAP_INPUT/ENCAP_OUTPUT count and names. Double-click to enter the sub-graph; export to .nbt for reuse. Encapsulations cannot nest inside each other, and debug nodes are stripped on export. Creatable in the Blueprint editor only.

---

## 80. 封装输入 / Input

- **枚举名 / Type**: `ENCAP_INPUT` · 稳定 id: `encap_input`
- **接口 / Interface**: 0 入 in → 1 出 out · 参数 params: `name`

**说明（中文）**

封装输入：只在封装子图内使用。它把父图上连到该封装节点第 i 个引脚的值作为 val 暴露给子图内部节点读取；其名字（<=32 字符）就是父图对应引脚的标签。

**Description (English)**

Encapsulation input: only inside a sub-graph. It exposes the value wired to the encapsulation's i-th pin as val for the inner nodes to read; its name (<=32 chars) labels that pin on the parent encapsulation.

---

## 81. 封装输出 / Output

- **枚举名 / Type**: `ENCAP_OUTPUT` · 稳定 id: `encap_output`
- **接口 / Interface**: 1 入 in → 0 出 out · 参数 params: `name`

**说明（中文）**

封装输出：只在封装子图内使用。它把子图内部某个值收集为该封装节点的第 i 个动态输出引脚；名字就是父图上的引脚标签，删除或重排它会牵动父图既有连线。

**Description (English)**

Encapsulation output: only inside a sub-graph. It collects an inner value as the encapsulation's i-th dynamic output pin; its name labels the pin on the parent, and deleting or reordering it affects existing parent connections.

---

## 82. 目标输出 / Target Output

- **枚举名 / Type**: `TARGET_OUT` · 稳定 id: `target_out`
- **接口 / Interface**: 0 入 in → 5 出 out

**说明（中文）**

目标输出：雷达（Radar）图专用。雷达每 tick 先分配目标再广播：x/y/z 为世界坐标、entity_id 为目标网络 ID（Sable 结构用按坐标派生的负 ID）、distance 为与扫描中心的直线距离（米 = 格）。多目标模式按节点 ID 轮流分配第 i 近目标；单目标模式所有节点都指向同一个最近目标；手动锁定只输出被锁实体。未分配到目标时输出 0。仅雷达编辑器的图内可用。

**Description (English)**

Target output: radar graphs only. Each tick the radar assigns targets, then broadcasts world x/y/z, entity_id (Sable structures use a coordinate-derived negative id) and the straight-line distance to the scan centre (metres = blocks). Multi-target mode hands the i-th nearest target to nodes by id order; single-target mode feeds every node the same nearest target; manual lock outputs only the locked entity. Unassigned → 0. Radar graphs only.

---

## 83. 目标转速 / Target Speed

- **枚举名 / Type**: `TX_OUT` · 稳定 id: `tx_out`
- **接口 / Interface**: 1 入 in → 1 出 out

**说明（中文）**

目标转速：可编程变速器（Programmable Transmission）图专用。把输入 rpm 四舍五入并钳到服务器最大转速（默认 +/-256）后写到输出轴；图停止时回落为滚轮设定值。图中存在多张时只取遍历到的第一张生效。仅变速器图。

**Description (English)**

Target RPM: programmable-transmission graphs only. Rounds the input rpm, clamps it to the server's max rotation speed (default +/-256) and drives the output shaft; when the graph stops it falls back to the wheel setting. If several TX_OUT nodes exist, only the first in traversal order acts. Transmission graphs only.

---

## 84. 移动 / Move

- **枚举名 / Type**: `MOVE` · 稳定 id: `move`
- **接口 / Interface**: 1 入 in → 1 出 out · 参数 params: `meters`

**说明（中文）**

移动：数控齿轮箱指令节点——触点上升沿把「移动 X 米」压入指令栈，由齿轮箱逐条执行，完成那一帧 done 输出 1。数值（米）默认 0，可编辑或连线覆盖；速度跟随输入轴，无动力时配额不消耗（指令冻结），恢复动力后续跑。仅数控齿轮箱图。

**Description (English)**

Move: CNC gearbox command node — a rising edge on the trigger enqueues "move X metres" onto the command stack; the gearbox runs commands one by one and done pulses 1 for the frame each finishes. The distance (metres) is editable or wire-overridable (default 0). Speed follows the input shaft: without power the quota stops consuming (commands freeze) and resumes when power returns. Gearbox graphs only.

---

## 85. 转动 / Rotate

- **枚举名 / Type**: `ROTATE` · 稳定 id: `rotate`
- **接口 / Interface**: 1 入 in → 1 出 out · 参数 params: `degrees`

**说明（中文）**

转动：数控齿轮箱指令节点——触点上升沿把「转 X 度」压入指令栈，执行期间离合保持接合，完成那一帧 done 输出 1。角度（度）默认 0，可编辑或连线覆盖；实际消耗按 |转速|*0.3/每 tick 折算。仅数控齿轮箱图。

**Description (English)**

Rotate: CNC gearbox command node — a rising edge on the trigger enqueues "turn X degrees". The clutch stays engaged while running and done pulses 1 for the frame it completes. The angle (degrees) is editable or wire-overridable (default 0); travel consumes |speed|*0.3 per tick. Gearbox graphs only.

---

## 86. 等待 / Wait

- **枚举名 / Type**: `WAIT` · 稳定 id: `wait`
- **接口 / Interface**: 1 入 in → 1 出 out · 参数 params: `ticks`

**说明（中文）**

等待：数控齿轮箱指令节点——触点上升沿把「等待 X tick」压入指令栈，纯计时、与转速无关，完成那一帧 done 输出 1。时长默认 0，可编辑或连线覆盖。仅数控齿轮箱图。

**Description (English)**

Wait: CNC gearbox command node — a rising edge on the trigger enqueues "wait X ticks". It is a pure timer, independent of shaft speed, and done pulses 1 for the frame it finishes. The duration is editable or wire-overridable (default 0). Gearbox graphs only.

---

## 87. 离合 / Clutch

- **枚举名 / Type**: `CLUTCH` · 稳定 id: `clutch`
- **接口 / Interface**: 0 入 in → 1 出 out · 参数 params: `engaged`

**说明（中文）**

离合：数控齿轮箱「常接合意图」节点——输出 >0.5 时令离合保持接合，空闲时允许分离。engaged 默认 0，可编辑或连线覆盖。注意它只表达接合意图、不控制速度：输出转速始终跟随输入网络。仅数控齿轮箱图。

**Description (English)**

Clutch: CNC gearbox standing-engagement intent — an output above 0.5 keeps the clutch engaged and lets it disengage when idle. engaged defaults to 0 and is editable or wire-overridable. It expresses engagement only, never speed — the output shaft speed always follows the input network. Gearbox graphs only.

---

## 88. 编码器 / Encoder

- **枚举名 / Type**: `ENCODER` · 稳定 id: `encoder`
- **接口 / Interface**: 0 入 in → 3 出 out · 参数 params: `reset`

**说明（中文）**

编码器：数控齿轮箱运动反馈节点——报告角度位置（0-360度）、线性位置（米）与实际转速（RPM、带符号，无动力/过载为 0）。两个位置是对转速的开环积分，打滑或外力扳动会漂移；reset 电平触发复位（默认 0，可编辑或连线覆盖），持续拉高会一直保持归零。仅数控齿轮箱图。

**Description (English)**

Encoder: CNC gearbox motion feedback — angle position (0-360 degrees), linear position (metres) and live signed RPM (0 when unpowered or overloaded). The two positions are an open-loop integration of shaft speed, so slip or forced movement drifts them; a high reset (editable or wire-overridable, default 0) zeroes both instantly and stays zero while held. Gearbox graphs only.

---

## 89. 信号发生器 / Signal Generator

- **枚举名 / Type**: `DEBUG_SIGNAL_GEN` · 稳定 id: `debug_signal_gen`
- **接口 / Interface**: 0 入 in → 1 出 out · 参数 params: `setMode, outMode, speed, amplitude, inputX`

**说明（中文）**

信号发生器（调试）：输出 y = f(x)*amp 的测试波形。setMode 选手动控制点折线或 f(x) 公式（公式内三角按度）；outMode 选频率发生（x 每 tick 自动 0→1 循环）或指定模式（x 手动给定，可画布拖拽）。speed/amplitude 仅手动曲线模式生效。导出封装子图时自动滤除。各图编辑器均可用。

**Description (English)**

Signal generator (debug): emits a test waveform y = f(x)*amp. setMode picks manual control points or an f(x) formula (trig in degrees); outMode picks free-running (x cycles 0→1 each tick) or a manual x you can drag on the canvas. speed/amplitude apply to the manual curve only. Dropped automatically when exporting an encapsulation. Available in every graph editor.

---

## 90. 信号探针 / Signal Probe

- **枚举名 / Type**: `DEBUG_PROBE` · 稳定 id: `debug_probe`
- **接口 / Interface**: 1 入 in → 1 出 out · 参数 params: `windowSize, autoScale`

**说明（中文）**

信号探针（调试）：把输入值原样透传，同时在节点内画该信号的历史波形（windowSize 默认 50，autoScale 自动缩放）。双击节点可冻结画面。它的输出引脚不渲染、不可连线——只作监视用。导出封装子图时自动滤除。各图编辑器均可用。

**Description (English)**

Signal probe (debug): passes the input through unchanged while plotting its history inside the node (windowSize defaults to 50; autoScale on). Double-click the node to freeze the view. Its output pin is neither rendered nor wireable — it is a monitor only. Dropped automatically when exporting an encapsulation. Available in every graph editor.

---

## 91. 继电器A / Relay A

- **枚举名 / Type**: `RELAY_A` · 稳定 id: `relay_a`
- **接口 / Interface**: 3 入 in → 2 出 out

**说明（中文）**

双掷继电器（SPDT）：触点 >0.5 时接通 B 掷、否则接通 A 掷；被断开的一侧输出 0，两侧互斥。纯组合、无状态，可在蓝图与编程计算机的图内做信号切换，也能直接放进封装。

**Description (English)**

Relay A (SPDT): the contact (>0.5) chooses the live throw — contact high connects B, low connects A, and the disconnected side outputs 0. Purely combinational and stateless: switch signals in Blueprint and Program Computer graphs, including inside encapsulations.

---

## 92. 继电器B / Relay B

- **枚举名 / Type**: `RELAY_B` · 稳定 id: `relay_b`
- **接口 / Interface**: 3 入 in → 1 出 out

**说明（中文）**

合并继电器：触点 >0.5 时输出 B、否则输出 A（单掷选择），纯组合、无状态。可用宿主与继电器 A 相同（蓝图与编程计算机）。

**Description (English)**

Relay B (selector): outputs B when the contact is high (>0.5), otherwise A. Purely combinational and stateless; same hosts as Relay A (Blueprint and Program Computer graphs).

---

## 93. 注释 / Comment

- **枚举名 / Type**: `COMMENT` · 稳定 id: `comment`
- **接口 / Interface**: 0 入 in → 0 出 out

**说明（中文）**

注释：画布上的一块多行文本框——可拖动、缩放，背景/边框/文字颜色均可调。零求值零引脚，也不出现在显示器实体上。几乎所有编辑器（包括封装子图）都放行它。

**Description (English)**

Comment: a multi-line text box on the canvas — draggable and resizable, with editable background, border and text colours. No pins and no evaluation; it never renders on the Monitor. Allowed in virtually every editor, including sub-graphs.

---
