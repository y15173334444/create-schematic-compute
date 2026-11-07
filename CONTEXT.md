# Create: Schematic Compute

节点图计算模组的领域语言：玩家在方块里编排「节点图」求值信号与音频，多方块经总线/私有信号互联。本文件是**术语表**（glossary and nothing else）——文档与讨论用词以此为准，实现细节归 `docs/code-architecture.md`。

The domain language of this node-graph compute mod: players compose node graphs inside blocks to evaluate signals and audio, and interconnect blocks over the bus and private-signal layers. This file is a **glossary and nothing else** — documentation and discussion use these words; implementation detail lives in `docs/code-architecture.md`.

## Language

### 图域结构 / Graph structure

**图（Graph）**：
宿主持有的一张节点网络，持久化于宿主 NBT；求值与编辑的对象。
_Avoid_: 电路、逻辑图、网络（circuit, logic diagram, network）

**宿主（Host / GraphHost）**：
承载一张图并逐 tick 驱动其求值的方块实体骨架（十二宿主共用 tickBody 模板步序 + 偏差声明）。
_Avoid_: 载体、容器、图容器（carrier, container）

**节点（Node）**：
图内的单个求值单元；存储共用 `GraphNode` 单类，以 `NodeType`（内置）或 `customId`（自定义）判别，不建继承体系。
_Avoid_: 元件、图元（component, element）

**方块（Block）**：
世界里的物理方块。与节点分属两层：一个方块宿主一张图，图内才是节点。
_Avoid_: 把方块叫「节点」（calling a block a node）

**引脚（Pin / pinId）**：
节点的输入/输出端口；连线绑定按稳定 `fromPinId/toPinId`（v1.2.4+），不是整数索引。
_Avoid_: 端口、接口（port, interface）

**连线（Wire）**：
两引脚间的有向连接，携带音频/浮点等信号域类型。
_Avoid_: 边、链路（edge, link）

**图域（graph-domain）**：
求值与图数据的业务域。公开 API 的结构性约束：**api 签名里禁出现图域类型**（门面对 graph 的实现委托不在此列）。
_Avoid_: 图内域（直译混用）

### 求值 / Evaluation

**求值器（GraphEvaluator）**：
宿主持有的求值引擎，服务端权威执行；客户端不经它、只消费快照。
_Avoid_: 引擎（泛称 engine）

**快照（EvalSnapshot）**：
求值结果下发客户端的只读消费面——客户端唯一读取形态。
_Avoid_: 缓存、镜像；与「恢复快照」（编辑 op 的 NBT 快照）严格区分

**份额（slice）**：
求值栅栏下一次求值调用可占用的时间片；超份额的挂起计入预算池。
_Avoid_: 时间片、切片（time slice, cut）

**重节点（Heavy Node）**：
可能跨 tick 分片推进的自定义节点（检查点续算的可恢复契约）。「异步车道」被否——重节点仍是同步栅栏内的分片。
_Avoid_: 长任务节点、异步节点（async node）

**检查点（Checkpoint）**：
重节点跨 tick 携带的进度状态（32KB SNBT 帽）。
_Avoid_: 存档点、断点（save point, breakpoint）

**求值栅栏（Evaluation Fence）**：
对第三方求值回调的契约：预算 + 超份额挂起 + 破约处置（可恢复契约）。
_Avoid_: 围栏（直译 fence 单用）

**预算（Budget）**：
每 tick 的求值时间额度；FORMULA 池与自定义节点池互不挤压（独立账本）。
_Avoid_: 配额、quota

**升级梯子（Escalation Ladder）**：
破约节点的处置序列：告警 → 降频 → 熔断。
_Avoid_: 断路器（circuit breaker——熔断只是梯子的末档）

### 信号域 / Signal domain

**总线（BUS）**：
跨方块的信号通道层（BUS_IN/BUS_OUT 节点）；通道分层的主力层。
_Avoid_: 公共信号（public signal）

**频道（Channel）**：
总线上的命名信号源，由 BUS_OUT 发布方持有；生命周期归 owner 门（发布方确证离开才退役）。
_Avoid_: 频段、波段、channel 与 band 混称

**频段（Band）**：
频道下的混载分流槽，按 `频道\0频段` 键分流音频/浮点；传输视角上即「车道」，勿另立词。
_Avoid_: 车道、lane、子频道（sub-channel）

**私有信号（Private Signal）**：
宿主间点对点的信号引脚域（音频/浮点），不经总线。
_Avoid_: 专线、直连信号（与「节点直连提供者方块」的值交换主路径区分）

**红石兜底（Redstone Fallback）**：
0–15 整数的原版红石接口（通道分层的兜底层，精度最低）。
_Avoid_: 红石通道（redstone channel）

**owner 门（Owner Gate）**：
频道释放/清理只由**确证的持有者**发起的资格门控（「缺席不是定义」等守卫同族）。
_Avoid_: 所有权锁（ownership lock）

**退役（Retire）**：
频道定义的生命周期终点；候选感知——确证无持有者才剪线保图。
_Avoid_: 删除、释放、清频段（delete, release——「清频段」是症状语不是机制名）

**自愈（Self-heal）**：
每 tick 收敛之前的频道重挂/清残留不变量（图停止也跑，先于频段收敛）。
_Avoid_: 修复（泛称 fix）

### 编辑与协作 / Editing & collaboration

**op（Graph Operation）**：
图编辑的原子操作，统一出口下发、服务端裁决（ADD_NODE_REQUEST 等）。
_Avoid_: 命令、指令（command——聊天指令另有所指）、packet（那是传输层载体）

**ACK**：
op 的服务端回执，携带真实 id 重映射（temp-id → 真实 id）。
_Avoid_: 确认、回包（confirm, reply）

**挂起队列（Pending Queue）**：
ACK 前引用 temp-id 的 op 的暂存处；关屏即弃（遗留窗口见登记册）。
_Avoid_: 缓冲区（buffer）

**整图推送（Graph Replace）**：
全量图 NBT 下发；与本地在编操作互斥（`isGraphReplaceBlocked`），禁止用来代替节点级 op。
_Avoid_: 全量同步（full sync 泛称）

**准入（Admission）**：
自定义节点进入某宿主的名单制许可。**注册 ≠ 准入**：`register` 只登记节点类型，还要 `admitToHost` 进目标宿主名单。
_Avoid_: 授权、把注册叫准入（authorization, conflating register with admit）

**名单（Roster）**：
宿主的自定义节点准入清单（`CustomNodeRoster`），id 级裁定。
_Avoid_: 白名单、allowlist（whitelist, allowlist）

**普查（Census）**：
装载时从旧档扫出的 `customId` 全集——提供方缺席时旧档可加载的兼容依据（「凭空捏造才拒」）。
_Avoid_: 扫描结果（scan result 泛称）

### 方块数据与几何 / Block data & geometry

**搬迁（Wrench Relocation）**：
扳手（潜行+右键）收回方块、NBT 随物品保留。
_Avoid_: 拆除（那是镐挖）、收回（泛称 pickup）

**拆除（Demolition）**：
镐挖/爆炸掉落**无 NBT 纯物品**——设计意图，不是缺陷。
_Avoid_: 破坏、挖掘掉落实（dig drop 泛称）

**逻辑姿态（logicalPose）**：
位置敏感内容的坐标变换口径（子级方块坐标=父维度网格本地）；渲染姿态（renderPose）**禁原地改写**（撕裂渲染姿态）。
_Avoid_: renderPose（作为位置变换口径使用时是禁用词）

**子级（sub-level）**：
Sable 绘图网格下的嵌套方块坐标系。
_Avoid_: 子世界、嵌套维度（sub-world, nested dimension）

**迁移（Migration）**：
NBT 数据版本升级的兼容步骤（`GraphMigration` + `NbtVersions.DATA_VERSION`）。
_Avoid_: 移植、泛称升级（port, upgrade）
