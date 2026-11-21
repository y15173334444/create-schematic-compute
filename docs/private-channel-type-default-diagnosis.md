# 私有频道未声明态被折叠为 f32 —— 诊断与修复

> 日期：2026-11-21
> 症状：新建的私有信号输入（PRIVATE_IN）引脚/类型芯片默认显示 `f32`，且不跟随私有信号输出（PRIVATE_OUT）的声明类型。

---

## 核心结论

根因是一条不可达分支：`SignalBus.privateType()` 在频道**无定义**时返回 `FLOAT` 而不是 `EMPTY`，导致 `PinType.effective()` 文档承诺的"两侧皆空才显灰"在私有频道上永远触发不了。

展示面类型 = `PinType.effective(本地声明, SignalBus.privateType(频道名))`（`NodeRenderer.java:629-639`、`EditPanel.java:422-426`）。新建 PRIVATE_IN 的本地声明是 `EMPTY`（`GraphNode.java:684-690`），此时若频道未定义：

```
effective(EMPTY, FLOAT) = FLOAT     ← synced 非 EMPTY，直接返回 synced → 显示 f32
effective(EMPTY, EMPTY) = EMPTY     ← 修复后：显灰，等待频道定义
```

修复：`privateType()` 无定义返回 `EMPTY`。**原则**：定义表的"无定义"默认值必须是"未声明"（EMPTY），不能是某个具体载荷类型（FLOAT），否则合并规则会把"没定义"和"定义了 float"吞成同一状态，订阅方永远无法区分。

---

## 节点接口

| 节点 | 声明类型来源 | 展示面取型 |
|------|-------------|-----------|
| PRIVATE_OUT | 本端 `bandTypes[0]` = 频道声明类型（发布方定义） | `inputType(0)` → 直读声明表 |
| PRIVATE_IN | 本地 `bandTypes[0]`（连线自动声明）+ 频道同步定义 | `outputType(0)` ∨ `privateType(signalName)` 经 `effective()` 合并 |

相关接口：

- `SignalBus.setPrivateType(channel, type)` — 发布方求值写入（`BusEval.java:116-124`），内容变化才写 + 自增版本戳
- `SignalBus.privateType(channel)` — 订阅方读取（渲染/芯片/拖拽预览三处），**本次修复点**
- `GraphHost.pushTypeChanges()` — 版本戳变化时推 `PrivateChannelTypePacket`（`GraphHost.java:613-644`）
- `PrivateChannelTypePacket.handle()` — 客户端写本地定义表，未知码保留现值（`PrivateChannelTypePacket.java:33-45`）

---

## 原理

### 1. 类型从发布方到订阅方的完整链路

```
PRIVATE_OUT 声明 bandTypes[0]
   │  BusEval 求值：setPrivateType(name, inputType(0))
   ▼
服务端 SignalBus.PRIVATE_TYPES[name]（版本戳 ++）
   │  GraphHost.pushTypeChanges 每 tick 检测戳变化 → PrivateChannelTypePacket
   ▼
客户端 SignalBus.PRIVATE_TYPES[name]
   │  NodeRenderer / EditPanel / NodeWireRenderer 读 privateType(name)
   ▼
effective(本地声明, synced) → 引脚着色 + 芯片文本
```

### 2. 合并规则（`PinType.effective`，`PinType.java:66-71`）

强序 `AUDIO > VEC3 > 本地声明（落线自动声明，见 §5）> 同步值`，`EMPTY` 是最弱态：本地空声明让位频道定义，两侧皆空才显灰。这条规则本身正确——问题出在喂给它的 `synced` 永远不可能是 EMPTY。

### 3. 为什么"不受 PRIVATE_OUT 控制"

不是同步链路断了，而是空态被提前折叠：

- 频道**已定义**（PRIVATE_OUT 求值过、版本戳推送到位）时，跟随是正常的——测试 `AudioNodesEvalTest:483/499` 证实 AUDIO/FLOAT 都能写入并读到。
- 频道**无定义**（无发布方 / 发布方未求值 / 跨区块未推送）时，`privateType` 返回 FLOAT，`effective(EMPTY, FLOAT)` 产出 f32。用户看到的"f32 且不跟随"正是这个状态——它根本没进入到定义同步链路里。

修复后无定义时返回 EMPTY，引脚显灰，把"频道无定义"这个事实诚实地暴露在展示面上。

### 4. 空态无处可达的证据

`GraphNode.java:684-688` 的注释记录了 2026-11-08 的实测："缺了它 bandType(0) 永远降级 f32、连接自动声明无槽可写（私有类型不随线变化）"——补 EMPTY 槽是当时修复私有类型的关键，但定义表侧的默认值没同步改，等于修了一半。

### 5. 关于"本地显式声明"——它其实就是落线自动声明

`effective(local, synced)` 里的 `local` 常被误读为用户手动选的类型，实际不是。编辑面板的类型芯片是只读的（`EditPanel.java:434` 传 `readOnly=true`），不响应点击。落线声明机制（`autoDeclareChannelSlot:157-165`）只作用于**发布侧**——目标是 BUS_OUT/PRIVATE_OUT 的输入脚时，槽位声明切成对端类型。**PRIVATE_IN 是订阅侧、位于输出脚，没有任何线会"落到"它上面，因此运行时没有自动路径改写它的 local**——它在正常操作中恒为构造时的 EMPTY（`GraphNode.java:684-690`）。

各写入路径对 PRIVATE_IN 的覆盖情况（枚举 `bandTypes.set` / `setPrivatePinType` 的全部调用点）：

| 路径 | 代码 | 覆盖 PRIVATE_IN？ |
|------|------|------|
| 落线即声明 | `NodeGraph.autoDeclareChannelSlot:157-165` | 否 — 只认 `toNode` 为 BUS_OUT/PRIVATE_OUT，PRIVATE_IN 是输出侧，永不命中 |
| 摘线回灰 | `NodeGraph.disconnectChannelSlot:177-185` | 否 — 同上只认 OUT |
| SET_PIN_TYPE op | `OpExecutor.java:503-537` | 代码分支认 PRIVATE_IN，但**无 UI 入口会向它发 op**：芯片只读、`maybeAutoDeclareBandType:3246` 只认 OUT |
| 旧档迁移 | `GraphMigration.java:694/707` | 是 — 加载时一次性写 FLOAT/AUDIO，属迁移语义 |

所以 PRIVATE 和 BUS **用的是同一套落线自动声明机制**（`autoDeclareChannelSlot` 名单里 PRIVATE_OUT 与 BUS_OUT 并列，判定单源 `PinType.autoDeclareFrom`；`BandAutoDeclareTest.privateOutFollowsWireLifecycle:346` 为回归），本次修复没有动它。

**于是 `effective` 里的 local 优先级对 PRIVATE_IN 是死代码**：local 恒 EMPTY，结果恒等于 synced——"属性跟频道走"天然成立。local 的 AUDIO/VEC3 强序只在 PRIVATE_OUT（local 即频道定义本身）上有意义。

真正的差异在**订阅侧的缺席语义**：BUS 有 `convergeBusInBands` 的"频道缺席不是定义、整轮跳过"门控（`GraphHost.java:574`）加 `BusBandSyncPacket` 的显式频段下发；PRIVATE 只有 `PrivateChannelTypePacket` 的单值下发，没有缺席门控，默认值直接暴露在展示面。把 `privateType()` 的默认改成 EMPTY，等价于给 PRIVATE 补上了 BUS 那层"缺席不是定义"的语义。

---

## 注意事项

1. **范围外、未改动的两个断链点**（属于设计边界，不在本次修复内）：
   - 写入门控：`BusEval.java:122` 仅在 `!busConflict && !signalName.isEmpty()` 时写定义表；PRIVATE_OUT 未求值、被占用或空名时定义表确实无值（此时修复后显灰是正确的展示，不是 bug）。
   - 推送范围：`GraphHost.java:639` 只推给追踪 PRIVATE_OUT 所在区块的玩家（`sendToPlayersTrackingChunk`）；跨区块订阅方依赖自己 host 每 tick 的那次推送。

2. **推送链连锁修正（无需额外改动）**：`GraphHost.pushTypeChanges()` 用 `privateType(name).code` 填 `PrivateChannelTypePacket`（`GraphHost.java:641`），客户端 handle 里 `byCode("empty")` 可解析为 EMPTY 并正常写表（`PrivateChannelTypePacket.java:43-44`，未知码门控不拦 EMPTY）。所以一处默认值改动后，无定义频道推送的码也从 `float` 变 `empty`，全链路单一真相源，不需要动包格式或客户端逻辑。修复前推的 `float` 正是把假定义灌进订阅方的路径之一。

3. **行为变更**：所有无定义的私有频道，展示面从 `f32` 变为灰色 EMPTY。这是可观察的用户可见变化，需写入 CHANGELOG。

4. **不受影响**：已定义频道（AUDIO/VEC3/FLOAT）的展示与同步完全不变；求值侧 `BusEval.java:103-104` 对 EMPTY 仍走 FLOAT 行为，求值不断档；BUS 侧 `bandType()` 口径未改。

5. **为何不改 `bandType()`**：BUS 侧有 `convergeBusInBands` 的"缺席不是定义，整轮跳过"门控（`GraphHost.java:574`），且频段列表随 `BusBandSyncPacket` 显式下发，无定义态另有语义；私有频道没有这层门控，二者不通用。

---

## 验证清单

- [x] `privateType("未定义频道") == EMPTY`（新增负例，修复前会失败）
- [x] `setPrivateType(name, AUDIO)` 后 `privateType(name) == AUDIO`（既有测试）
- [x] `clearSignal(name)` 后 `privateType(name) == EMPTY`（更新既有断言 2 处）
- [x] 定义版本戳只在内容变化时自增（既有测试）
- [x] `effective(EMPTY, EMPTY) == EMPTY`、`effective(EMPTY, FLOAT) == FLOAT`（既有 BandAutoDeclareTest）
- [x] 相关 JUnit 在主环境跑通（见下方验证记录）

### 验证记录（2026-11-21）

- 编写环境说明：本修复与本文档产自 WorkBuddy 沙箱 agent 环境，沙箱不便跑模组测试链；单元测试的执行与记录一律以主仓环境（真实 gradle 工具链）为准，沙箱侧只写断言与推理，不写测试结果。
- 方式：worktree `workbuddy/main-02b60573` 下 `./gradlew test --tests PrivateChannelOccupancyTest --tests BandAutoDeclareTest --tests AudioNodesEvalTest`（按相关类过滤）。
- 结果：`PrivateChannelOccupancyTest` 8/8、`BandAutoDeclareTest` 21/21、`AudioNodesEvalTest` 19/19，共 48/48 全绿，0 失败 0 跳过（build/test-results/test/ JUnit XML 实录）。
- 负例自证：`undefinedChannelIsEmptyNotFloat` 在修复前（无定义返回 FLOAT）必然失败——断言 `privateType("never-declared") == EMPTY` 直接锁死修复点。
- 全量回归未在本次运行（仅按相关类过滤），合入 main 后由 CI 跑全量兜底。
