# 宿主套件波 2 实施方案 / Host Suite (Wave 2) Plan

> 日期 / Date：2026-11-21
> 口径：机制级描述，类名为引用锚；本文是动手前的设计定稿（设计意图存档，防实施漂移）。
> 基座：`csc-host-suite` 分支叠于 `csc-api-version` 顶（D9 独立版本线已拍板，波内按新功能升 0.2.0）。

---

## 1. 背景与目标 / Background & goals

- 公开 API 波 1（自定义节点注册）已全量落地；**波 2（宿主套件）尚未开工**：第三方宿主接口（图存取/持久化钩子/准入偏好声明）+ `GraphHost` 骨架接入 + 编辑器打开路由 + 组合线先例的接口化提炼（探索稿 §9 波 2 行）。
- 附属「电推」实证了需求形态：其 BE/Screen 现全部踩在主模组内部面（`SyncedGraphBlockEntity`、`GraphHost.TickHooks`、`AbstractGraphScreen`、`BlueprintTogglePacket`），名单键借用蓝图机占位，javadoc 自留「波 2 落地后迁移」锚。主仓任何内部重构都会碎掉它。
- 结构性根因：`blocks/GraphBlockEntity` 契约签名携带图域类型（`NodeGraph`/`NodeAllowance`/`EvalSnapshot`/`NodeType`）。按 api 结构规则（**api 签名只用 JDK/MC 公共类型 + api 自有值类型**，探索稿 §6.1），第三方在 api 域内**没有任何可实现的宿主接口**——`instanceof GraphBlockEntity` 的服务端门、`GraphEditor.Host` 的屏面、`EditSessionTarget` 的会话面全部可望不可即。
- 附带前置：登记册条目 19（`evaluatorView()` 名为只读实返活 `GraphEvaluator` + 探针耗时表装箱）明确「宿主套件波前承接」——契约收敛先于对外开放。

目标：第三方模组经 api 包声明并驱动一个图宿主（图存取、tick 推进、持久化、准入偏好），并用一行调用打开本模组的图编辑器；条目 19 两件套同步收敛。

## 2. 总体形态：门面 → 适配器 → 引擎 / Shape: facade → adapter → engine

沿波 1 先例（`CustomNodes` 门面 + `CustomNodeType` schema 值类型 + graph 实现侧委托），宿主套件三层：

1. **api 契约面**（`api` 包新增，签名全图域无）：声明 + 缝 + 引擎柄 + 门面。
2. **内部适配器**（`blocks/` 包，本模组私有）：把 api 契约适配成完整 `GraphBlockEntity`，内嵌真正的 `GraphHost` 引擎。
3. **既有引擎与门**（`blocks/GraphHost`、network 包各 handler、`client/editor/`）：不改引擎编排，只把「`instanceof GraphBlockEntity`」的识别点收敛为统一 resolver。

第三方的 BE 只做三件事：构造时 `bind` 拿引擎柄、tick/生命周期/NBT 转发给柄、方块 `useWithoutItem` 里调 `openEditor`。

## 3. api 契约面明细 / The api contract

### 3.1 类型族（命名沿波 1 对称：Type=声明、Behavior=行为、Handle=柄）

| api 类型 | 形态 | 内容 |
|---|---|---|
| `HostGraphs` | 门面（final，私有构造） | `bind(BlockEntity owner, HostGraphType spec, HostGraphBehavior behavior)` → `HostGraphHandle`；`openEditor(BlockPos)`（客户端）；键登记表查询 |
| `HostGraphType` | record，声明面 | hostKey、`HostAdmission`、api `EvalVariant`（FULL_STATE/BASIC/EMPTY_TABLE）、api `StopSlot`（BEFORE_GRAPH_CHECK/AFTER_GRAPH_CHECK/AFTER_RECOMPILE）、`lightRecompile`/`litAbsentAbortsTick`/`stopEndsTick`/`activeInputRefresh`/`wantsStep7Recover`/`wantsFlipflopDiff` 布尔策略、`litProperty`（`BooleanProperty` = MC 公共类型，@Nullable） |
| `HostGraphBehavior` | 接口，缝面 | 缝 0–10（afterGuard/beforeLit/beforeGraphCheck/beforeInputs/afterRecover/beforeEval/afterEval/afterWriteOutputs/afterEvalBroadcast/beforeSetChanged/afterSetChanged）+ `stopBody(HostGraphHandle)`，全部 default，默认值镜像内部 `GraphHost.TickHooks` 的 Blueprint 基准 |
| `HostGraphHandle` | 接口，引擎柄（不透明） | `tick()`、`save(CompoundTag)`/`load(CompoundTag)`、`onHostLoad()`/`onHostChunkUnloaded()`/`onHostRemoved()` |
| `HostAdmission` | record，准入偏好 | 类目集（api 枚举）+ 排除节点 id（`Set<String>`，内置节点 id 字符串） |
| `HostCategory` | 枚举 | 镜像内部 `NodeCategory` 的类目值（VALUES/MATH_BASIC/MATH_ADVANCED/TRIG/LOGIC/CONTROL/CUSTOM/DEBUG） |

- **`seatInput()` 不进波 2 契约**（可否决）：内部 `TickHooks.seatInput()` 返回图域 `GraphEvaluator.SeatInputState`（28 字段）。适配器恒给零值座位输入，BASIC 档语义不受影响；真座位输入宿主（Sensor/ControlSeat/Radar 类）留扩展批。`evalVariant()` 在 spec 里可选三档，选 FULL_STATE/EMPTY_TABLE 的第三方在无座位输入下同样成立（空座位输入走同路径）。
- `stopBody` 收柄不收引擎：内部缝 `stopBody(GraphHost)` 的参数在 api 侧换成 `HostGraphHandle`，适配器解包后转发，默认行为 = `engine.onStopRunning()` 同款。
- 全部新类型带双语 javadoc，doclint 零告警口径与波 1 一致。

### 3.2 bind 的语义与校验

- `bind` 即**宿主键自声明**：键进登记表（§4），登记内容 = 键本身。provider 归属取 **hostKey 的命名空间段**（键强制 `modid:name` 形态，与波 1 节点 id `"modid:node_id"` 同约定）——不经 BE 类型注册表查询（无头测试与早绑定期都无需 registry 引导）。
- bind 校验（fail-fast，启动即炸口径）：owner 非空、spec.hostKey 合法形态且命名空间**不在保留集**（`csc`/`create_schematic_compute`，api 门面是第三方专用口，本模组宿主走内部登记缝）、同 hostKey 重复 bind 抛 `IllegalStateException`（与波 1 重复注册同款）。
- bind 登记 BE→适配器映射（WeakHashMap 语义：BE 为键、适配器为值；`onHostRemoved` 时摘除）。**引擎归适配器所有**：适配器构造时 `new GraphHost(self)`，图数据、求值器、BUS、同步全部住在引擎里，第三方 BE 不接触任何图域对象。

### 3.3 openEditor 的分发机制

api 包**不 import client 类**（专用服务器类加载纪律）。`HostGraphs.openEditor(BlockPos)` 经内部分发钩子：本模组客户端初始化时注册真正的开启实现（client-only 类），专用服务器上钩子为 null → 抛 `IllegalStateException`（开编辑器是客户端行为）。第三方方块 `useWithoutItem` 的客户端分支一行调用。

## 4. 宿主键登记表与 admitToHost 归属升级 / Key registry & ownership

- `CustomNodeRoster.ENROLABLE_HOST_KEYS`（现硬编码 `Set.of(蓝图机键)`）改**动态登记表**：本模组 12 宿主启动时经内部缝登记（等价现状）；第三方经 bind 登记。`forHost(hostKey)` 查表，未登记键 = 默认拒绝（名单制语义不变）。
- `admitToHost(nodeId, hostKey)` 归属门从「键 ∈ 显式白名单」升级为两路放行：
  1. 键 ∈ **CSC 显式白名单**（现 = 蓝图机键，本模组后续想开放更多自家宿主时扩行）；
  2. 键已登记（第三方声明）**且键命名空间 == 节点 id 命名空间**——宿主声明者给自己的图收自己的节点。
  其余（含他人声明的键）维持 `IllegalArgumentException`（D5 名单制口径）。

## 5. 适配器与 resolver / Adapter & resolver

- **适配器**（内部类，`blocks/` 包）：implements `GraphBlockEntity`（含 `EditSessionTarget` + `EditStateView` 全部成员）。图存取/运行态/快照/同步/BUS 生命周期/NBT 全部委托内嵌 `GraphHost`；`nodeAllowance()` 由 spec 的 `HostAdmission` 转 `NodeAllowance` 得来；`pendingLocalOps` 守卫与 `peekSubStateFlipflops` 自持（镜像 `SyncedGraphBlockEntity` 的形态）；`evaluatorView()` 返回批 A 的只读窄视图。
- **resolver**（内部静态入口）：`resolve(BlockEntity)` → 原生宿主（`instanceof GraphBlockEntity`）直通；否则查 bind 登记表返回适配器；都无 → 空（**fail-closed 保持**，未登记的第三方 BE 一律照旧拒绝）。
- 识别点改造清单（全部改为走 resolver）：`GraphJoinPacket`（join 门）、`GraphSaveRequestPacket`（保存门）、`BlueprintTogglePacket`（开关门 + 会话成员校验不变）、`GraphEditOpPacket`（op 链 + 距离/成员校验不变）、`ClientboundGraphEvalPacket`（快照下发门）、`EditSessionRegistry.applyOp`（`EditSessionTarget` 应答源）、便携终端列表（`PortableTerminalScreen`）。
- 既有 12 宿主零改动：它们的 BE 本就是 `GraphBlockEntity`，resolver 直通。

## 6. 编辑器打开路由 / Editor open route

- 主模组新增**泛用屏**（`client/editor/`，内部类）：implements `GraphEditor.Host` 的非 default 五方法（getGraph/saveGraph/toggleRunning/isRunning/asScreen）+ `getBE()`（EditStateView 协变返回适配器视图）+ `setNodeAllowance(适配器名单)`；构造只吃 `BlockPos`，图与运行态从客户端 BE 的适配器视图来。
- `toggleRunning` = 本地乐观写 + `BlueprintTogglePacket`（CSC 屏同款）；保存 = `GraphSaveRequestPacket`（op 通道权威不变，客户端只是视图）。
- 便携终端：ROUTE_KEYS 未命中但 BE 可解析（resolver 有适配器）→ 默认开泛用屏——封掉「列表在、点开静默无效」的历史事故族；命中既有路由的 CSC 设备行为不变（先专后泛）。

## 7. 条目 19 前置收敛 / Entry 19 pre-convergence

- ① `evaluatorView()` 返回类型 `GraphEvaluator` → 真只读窄接口（**内部面，不进 api**——观测非 D1–D8 拍板能力）：`long nodeCostNs(int nodeId)` + `int[] nodeIds()`（+ `hasCosts()` 供判空）。四处实现（SyncedGraphBlockEntity + 三组合线 BE）与唯一消费方 `/cscperf` 的 `formatPerfSummary` 同步换；标题解析仍走 `getNodeGraph()`（指令面内部可达）。
- ② `GraphEvaluator` 耗时表 `HashMap<Integer, Long>` → **原始 long 开放寻址表**（节点 id → long；随求值器存亡、图编辑整表换血语义不变），消除每节点每 tick 键+值双装箱。`NodePerfProbeTest` 锚随改。

## 8. 版本与文档口径 / Version & docs

- 波 2 新公开面 = 向后兼容的新增 → 按 D9 升 `ApiVersion.CURRENT` **0.1.0 → 0.2.0**。
- CHANGELOG Unreleased 块补 ✨ 条目；探索稿 §9 补波 2 落地注记 + §8 补决策行（键归属两路放行口径）；登记册条目 19 → 已解决、条目 15 的波 2 行更新、键族注记。

## 9. 批次与验证 / Batches & verification

- **批 0**：本文档入库 → **批 A**：条目 19 两件套 → **批 B**：HostCategory/HostAdmission + 键登记表 + 归属升级 → **批 C**：契约四件 + 适配器 + resolver 改造 → **批 D**：泛用屏 + openEditor + 便携终端默认路由 → **批 E**（可否决）：内部组合线宿主一例（KineticGauge 线）收编套件样板 → **批 F**：版本 0.2.0 + CHANGELOG/探索稿/登记册收口 → **批 G**：电推迁移验证（附属仓换真键 + BE/Screen 迁套件面；主仓 jar 产物 → 附属重编 → runClient 冒烟：放图/开编辑器/节点名单可见/图跑）。
- 每批全量 `./gradlew test` 绿；批 F 收 `./gradlew javadoc` api doclint 零告警；中文编辑随批 grep FFFD 码点验收；测试形态沿既有约定（无头不可入处用源码锚测试）。

## 10. 边界与可否决项 / Boundaries & vetoables

- **本波不做**：第三方子编辑器（像素/谱面）复用缝、占用保活契约复用、自绘 UI（D4 已砍）、总线/私有信号对第三方开放（D6/D7 已砍）、超出宿主所需的图存取公开面。
- **可否决**：seatInput 不进波 2 契约；观测窄视图不进 api；批 E 内部收编可退场；api 类型命名（HostGraphs/HostGraphType/HostGraphBehavior/HostGraphHandle/HostAdmission/HostCategory）与泛用屏命名。

## 11. 交叉引用 / Cross references

- 探索稿：`docs/1.3.0-public-api-exploration.md`（§4.1 两大件、§6.1 包结构纪律、§6.2 D9 版本口径、§8 决策表、§9 波次）
- 登记册：`docs/known-open-issues.md`（条目 15/19/21）
- 组合线先例：`blocks/KineticGaugeBlockEntity`、`blocks/CncGearboxBlockEntity`、`blocks/ProgrammableTransmissionBlockEntity`；继承线薄壳：`blocks/SyncedGraphBlockEntity`
- 迁移锚：附属仓 `csc-electric-push`（`ElectricPushBlockEntity`/`ElectricPushScreen`/`ElectricPushNodes` 借键占位注释）
