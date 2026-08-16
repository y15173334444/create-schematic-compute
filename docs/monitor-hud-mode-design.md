# 全息显示器 HUD 模式设计文档（收敛版 · AR 共形式）

> 基线：`04bf7de`。本文是用户原始 HUD spec 经代码核对后的**修正 + 收敛版**，所有结论均锚定真实代码，非臆测。
> 关键决策（2026-08-16）：
> - 3D↔HUD 切换 = **全息显示器内部设置的选项卡切换**，互斥、参数不冲突。
> - 样式 = **战斗机 AR HUD（方案 B 共形瞄准）**：面板世界固定尺寸+透视正确，符号共形锁定世界（俯仰梯/地平线/航向带/速度矢量）。**姿态数值由玩家自己在图中接线驱动（控制座椅/姿态传感器/公式等任意节点皆可），刷新频率 20Hz（tick 率）**。不开独立开关/包/类/渲染器。
> 前置进展（2026-08-16）：§十二 依赖的 ⑤（IMAGE/IMAGE_SEQUENCE 自定义 W×H 1..32）已落地；§三 提及的历史渲染 bug（补丁评审 ①）已修复。落地记录与提交见 `docs/monitor-image-fixes-audit.md`。

## 〇、一句话定位

你要的「3D 世界锚定的战斗机 AR HUD」**完全可行**。最有利事实：Monitor 本就是 3D 锚定面板（非 2D GUI 覆盖层），且现有图管道已能输出座椅姿态/速度。HUD 模式 = 加「刚性玻璃 quad + 共形符号节点」，复用 100% 现有基础设施。

## 一、设计哲学（三条不可妥协）

1. **面板锚定方块**：玻璃中心点/位姿由 `FACING` + `BlockPos` 决定，与玩家视点无关。
2. **显示组件恒定物理尺寸**：玻璃在 3D 空间固定世界尺寸，远处小近处大（透视），不漂移。
3. **符号共形锁定世界**：俯仰梯/航向带/速度矢量对齐真实世界（地平线/航向/航迹），转头时相对世界固定。
4. **多人共享**：所有玩家看到同一玻璃，内容服务端权威同步。

## 二、与原始 spec 的关键修正（务必看）

| 原始 spec | 真实代码 / 修正 |
|---|---|
| `HoloDisplayBlockEntity` | → 直接扩展 `MonitorBlockEntity`（`blocks/`） |
| `HoloDisplayHudRenderer` | → 不新建，在 `MonitorBlockEntityRenderer.render()` 加 `if(be.hudMode) renderHud() else renderNormal()` 分支 |
| `HudModeData` 类 | → 不新建类，`MonitorBlockEntity` 的字段 + NBT（`loadSettings`/`saveAdditional`） |
| `SetHudModePacket` | → **不新建**，扩展已有 `MonitorSettingsPacket`（C2S，已有 `BlockPos pos` 形态，同 radar/monitor 设置） |
| `Framebuffer` 每帧 `new` | → **禁止**。每 BE 缓存一个 FBO，懒建 + 尺寸变化 resize + `onChunkUnloaded`/`onDispose` 时 dispose（否则 GPU 显存泄漏） |
| BER.render() 内混画 FBO | → **禁止**。应在世界渲染前独立 pass（`RenderLevelStageEvent` / `RenderHandEvent`）离屏画到 FBO，BER 主 pass 只**采样缓存纹理**画玻璃 quad |
| FBO 宽高比随意 | → 必须等于 `panelSizeX / panelSizeY`，否则符号拉伸（如 2.0×1.2 面板配 1024×512 纹理=2:1≠5:3 → 拉伸） |
| HUD 透传 `packedLight` | → 改为自发光：`0xF000F0` 或 `noLight`/`ambient` 的 `RenderType`，否则被方块光照压暗 |

## 三、3D 锚定早已存在（核对事实）

`MonitorBlockEntityRenderer.java`：
- L72-80：`be.getBlockState().getValue(MonitorBlock.FACING).toYRot()` 取朝向，`poseStack.translate(0.5+tx, …)` 移到方块中心，在**方块局部 3D 帧**绘制显示元素。
- L250-251（编辑器侧）：`screenX/Z` 按 `FACING` 旋转。

⇒ Monitor 已是 3D 锚定、不受 GUI Scale 影响。HUD 复用此坐标系即可，无需自己定位方块。

> ⚠️ 历史 bug（见 `docs/monitor-image-fixes.patch-review.md` ①，已于 2026-08-16 修复）：世界渲染器 clamp 上界曾用中心锚定公式（`1-bbHalfW`，左上角锚定的正确值为 `1-2*bbHalfW`），导致缩放图像在边框处偏移——注意编辑器与世界渲染器**两侧均为左上角锚定**，修复仅改上界。教训：编辑器与渲染器的锚点语义与 clamp 上界必须一致。HUD 玻璃 quad 是全新绘制路径，建议**以面板中心为锚点**（新代码无历史锚点包袱），且共形符号投影见 §九之一。

## 四、尺寸与距离属性（用户 12:21 确认）

- **玻璃面板**：世界尺寸恒定 = `panelSizeX × panelSizeY` 方块单位；表观大小随距离透视缩放（远小近大，物理正确的座舱玻璃行为）；FBO 纹理分辨率恒定（如 1024×512）不糊；不受 GUI Scale 影响。
- **AR 共形样式（目标）**：用户确认「保持透视缩放」——玻璃仍按真实 3D 透视，仅符号做共形对齐（方案 B）。**counter-perspective（符号/面板随距离反向放大、永远不变大小）不做**，那是 AR 护目镜风、会打破「面板固定世界尺寸」原则。
- 共形符号（俯仰梯/地平线/航向带/速度矢量）投影数学见 §九之一；符号在玻璃上随玩家视角变化位置，但始终「贴」在对应世界方向上。

## 五、切换机制：选项卡切换（用户 11:53 确认）

`MonitorScreen` 设置面板加 tab 条：`[ 3D 模式 ] [ HUD 模式 ]`。
- tab 切换 = 模式切换：`hudMode` 即「当前 tab 身份」，互斥、参数分属各自 tab、互不冲突。
- 选中 HUD tab 即刻发 `MonitorSettingsPacket(hudMode=true)`；切回 3D tab 发 `false`。
- 面板变换 5 参数仅在 HUD tab 可见。建议「立即切换世界渲染」（所见即所得）。

```
MonitorScreen 设置区 (现 L868 发 MonitorSettingsPacket)
   └─ tab 条: [3D 模式] [HUD 模式]
        │ 选中 HUD tab: MonitorSettingsPacket(pos, hudMode=true, panelSizeX/Y, offsetX/Y, distance)
        ▼
MonitorBlockEntity
   ├─ 字段: boolean hudMode + 5×float
   ├─ NBT: loadSettings() / saveAdditional()
   └─ 变更: setChanged() + sendBlockUpdated(pos, state, state, 3)   ← 同 toggleRunning(L40)，广播所有客户端
        ▼ 标准 BE getUpdateTag(L133) 同步
MonitorBlockEntityRenderer.render()
   └─ if (be.hudMode) renderHud(...) else renderNormal(...)
```

净效果：切换是显示器自身的一个设置项，与其他 Monitor 设置（红石同步、显示缩放等）并列，服务端权威、所有客户端即时同步，**零新增广播包**。

## 六、数据模型（MonitorBlockEntity 字段 + NBT）

```java
// MonitorBlockEntity 新增字段
public boolean hudMode = false;          // 当前 tab（false=3D, true=HUD）
public float panelSizeX = 2.0f;          // 面板宽（方块单位）
public float panelSizeY = 1.2f;          // 面板高（方块单位）
public float panelOffsetX = 0.0f;        // 相对方块中心横向偏移
public float panelOffsetY = 0.0f;        // 相对方块中心纵向偏移
public float panelDistance = 0.05f;      // 面板距方块表面距离（沿 FACING 法线，须 ≥~0.05 防 z-fighting）

// NBT：saveAdditional / loadSettings 序列化以上 6 字段
// 同步：变更时 setChanged() + level.sendBlockUpdated(pos, state, state, 3)
```

## 七、网络（扩展 MonitorSettingsPacket）

`MonitorSettingsPacket`（C2S）新增字段：`boolean hudMode` + `float panelSizeX, panelSizeY, panelOffsetX, panelOffsetY, panelDistance`。
服务端 `MonitorBlockEntity` 收到后写字段 + `setChanged()` + `sendBlockUpdated` 广播。**不新建任何 Packet 类。**

## 八、渲染管线（BER 分支 + FBO 离屏 pass）

### 8.1 离屏 pass（世界渲染前，独立）
每个 `MonitorBlockEntity` 持有一个缓存 `Framebuffer`（懒建、尺寸变化 resize、卸载 dispose）：
- 把 HUD 画布（固定玻璃符号 + **共形符号**见 §九之一）按 `panelSizeX/panelSizeY` 比例渲到 FBO 纹理。
- 节流：按 20Hz（tick 率）重绘，而非 60Hz（活 HUD 每帧内容都变，「脏区域」优化基本无效）。

### 8.2 BER 主 pass（采样缓存纹理）
```java
public void render(be, partialTick, stack, buffer, packedLight, packedOverlay) {
    if (!be.hudMode) { renderNormal(...); return; }
    // 1. 移到方块中心 + 偏移
    stack.translate(0.5 + be.panelOffsetX, 0.5 + be.panelOffsetY, 0.5);
    // 2. 应用 FACING 旋转
    stack.mulPose(Axis.YP.rotation(-be.getBlockState().getValue(FACING).toYRot()));
    // 3. 推到面板距离（沿 FACING 法线）
    stack.translate(0, 0, be.panelDistance);
    // 4. 画四边形：尺寸 panelSizeX × panelSizeY，中心锚点
    // 5. 材质 = FBO 颜色纹理，RenderType.entityTranslucent / noLight，关深度写入 + 混合
    //    packedLight = 0xF000F0（自发光）
}
```

关键：FBO 尺寸固定 → 清晰度恒定；面板位姿由方块定 → 屏幕中心随方块非玩家；现有图驱动 + `EvalSnapshot` → 多人一致。

## 九、显示组件系统

**MVP（V1，可零新节点类型）**：直接复用现有 `TEXT`（文字=空速/高度数字）+ `IMAGE`（姿态仪贴图）节点当 HUD 元素，只加「hudMode 开关 + 玻璃 quad」。

**Phase 2 组件套件**：新增 `HUD_*` 节点类型（`NodeType.java` 加枚举，用**稳定 id 字符串**而非序号，迁移安全，见 `BY_ID` 查找表 L99-116）：
俯仰梯 `HUD_PITCH_LADDER`、航向带 `HUD_HEADING_TAPE`、空速 `HUD_AIRSPEED`、高度 `HUD_ALTITUDE`、准星 `HUD_RETICLE`、文字 `HUD_TEXT`。
每新增类型需在 4 处加分支：
1. `GraphEvaluator` — 计算输出值（多数 HUD 节点透传姿态输入，符号形态由渲染解释）；
2. `MonitorScreen.collectDisplayElements`(L911) — 让节点进编辑器、拿 layout；
3. `MonitorBlockEntityRenderer` — 按值画符号；
4. lang 文件 — `node.create_schematic_compute.hud_*`。
注意 `editableParamCount()`(L138)、`inputLabel/outputLabel`(L158-228) 也需覆盖新类型。

**布局**：节点已有 `layoutX/layoutY`（归一化 [0,1] 画布坐标）+ `displayScale`，spec 的 `(u,v,w,h)` 直接映射现有字段，Phase 6 工作量被高估。

### 9.1 共形符号投影（方案 B，用户 12:21 确认目标样式）

玻璃 quad 仍世界固定尺寸、透视正确（§四）。共形符号 = 把「世界方向/世界点」经**玩家视线**投射到玻璃平面，得玻璃局部 2D 坐标 → FBO UV。逐客户端计算（每玩家相机不同，FBO 仅客户端）。

投影公式（每条符号/每帧）：
- 玻璃中心世界坐标 `C = blockPos + 0.5 + offset(沿FACING) + panelDistance·N`，法线 `N = FACING 方向`，由 `N` 与世界上方构造右向量 `R`、上向量 `U`（见 §8.2 旋转）。
- 玩家眼 `E`（相机位置）。对世界点 `P`（或对世界方向 `Dir` 取 `P = E + Dir·RANGE`，RANGE 任取大值）：
  - 射线 `D = normalize(P - E)`
  - `t = dot(C - E, N) / dot(D, N)`；若 `t > 0`（玻璃在眼前）则 `hit = E + D·t`
  - `local = hit - C`；`r = dot(local, R)`；`u = dot(local, U)`
  - UV = `(r / panelSizeX + 0.5, u / panelSizeY + 0.5)`，落在 [0,1] 内才绘制
- 符号因此「贴在世界上」：转头/移动时相对世界固定，符合战斗机 HUD 共形体验。枪炮十字(boresight)等固定符号仍按方案 A 贴玻璃（不投影）。

### 9.2 共形符号数据来源（首批：俯仰梯/地平线、航向带、速度矢量）

**姿态数值由玩家自己在图中接线驱动 —— 上游不硬编码任何座椅字段，HUD_* 节点就是普通图节点，其输入引脚由玩家从任意节点接（控制座椅 / 姿态传感器 / 公式 / 总线等皆可）。** 这样「数据从哪来」完全交给图，模组只负责把接进来的数值做共形投影渲染。具体：

- **俯仰梯 + 地平线**：取 HUD 节点接进来的俯仰/滚转输入，在玻璃上画一系列不同俯仰角的水平线（按 §9.1 对「各俯仰角方向射线」求交），滚转旋转整组 → 地平线始终水平于世界。
- **航向带**：取接进来的航向/yaw 输入，画横向滚动刻度（各航向角方向射线求交）。
- **速度矢量（飞行航迹点）**：取接进来的世界速度向量 `V`，对其方向射线求交得玻璃点 → 画航迹标记。
- *目标框（暂不做）*：需雷达目标世界坐标 / 指定世界坐标节点输出（`POSITION`/`TARGET_OUT`），留待后续（无雷达依赖时不实现）。

**刷新频率 20Hz（tick 率）**：即图求值/广播的节拍（现有 `ClientboundGraphEvalPacket` 每 tick 推一次 outputs）。HUD 节点读到的输入本就 20Hz 更新 → 共形投影与 FBO 重绘自然落在 20Hz，无需额外轮询机制。这也呼应 §8.2 的 FBO 20Hz 节流（二者同源）。

## 十、多人同步（已就绪）

- 图编辑：`GraphEditOpPacket` / `GraphEditOpSyncPacket` 已同步 → 新增 HUD 节点自动多人可见。
- 求值结果：`ClientboundGraphEvalPacket` 已广播 `Map<Integer,float[]> outputs`（俯仰/滚转/航向/空速/高度/速度向量走这条）。
- 模式/变换：扩 `MonitorSettingsPacket` → 零新 eval/sync 基建。
- 共形符号投影在各客户端本地算（相机不同），服务端只同步原始姿态/速度世界坐标，不传纹理。
⇒ 用户原始 spec「复用 100% 基础设施」成立。

## 十一、风险与缓解

| 风险 | 缓解 |
|---|---|
| FBO 每帧 `new` → 泄漏 | 每 BE 缓存一个，dispose 在卸载时 |
| BER 内混画 FBO 打乱渲染目标 | 世界渲染前独立 pass 离屏画，主 pass 只采样 |
| 深度冲突（面板与方块重叠） | `panelDistance ≥ ~0.05` |
| 透明排序（被其他透明方块覆盖） | `RenderType.entityTranslucent` + 适当顺序；必要时 `noCull` |
| 渲染包围盒裁剪 | **扩大 `getRenderBoundingBox()` / `shouldRenderOffScreen`** 覆盖面板体积（面板比方块大时必做，否则某些视角凭空消失） |
| 背面单面消隐 | 单面 quad 背面不可见（物理正确）；要双面玻璃用 `noCull` |
| FBO 每帧重绘性能 | 20Hz 节流；N 个 HUD×~2MB 上传/帧，少量 OK、几十个需降分辨率/节流 |
| 共形投影在玻璃背后（t≤0） | 该符号不绘制（落在视野外），避免错误地镜像到玻璃 |
| GUI Scale 影响 | 无（3D 渲染，正是你要的） |

## 十二、与上次 bug 修复协同

✅ ⑤（图像节点 W×H 长方形，1..32）已于 2026-08-16 落地（含像素编辑器/渲染器/网络全链路，见 `docs/monitor-image-fixes-audit.md`），飞行姿态仪的长方形画布前置条件已就绪。新增 `RETICLE` 节点仍随 HUD 排期。

## 十三、开发路线（收敛到真实文件）

- **Phase 1（MVP）**：`MonitorBlockEntity` 字段+NBT+同步；`MonitorSettingsPacket` 扩展；`MonitorScreen` tab+HUD 开关+5 滑块；渲染器 `hudMode` 分支 + 每 BE 缓存 FBO + 玻璃 quad（中心锚点）；复用 `TEXT`/`IMAGE` 节点。→ 单人可见 3D HUD 玻璃，固定符号居中稳定。
- **Phase 2（AR 共形）**：`HUD_*` 组件套件 + **共形投影（§9.1）**：俯仰梯/地平线、航向带、速度矢量（数据来源 §9.2，由玩家在图中自接输入引脚驱动，刷新 20Hz）；FBO 20Hz 节流；面板变换在编辑器内调参。
- **Phase 3**：多人验证（扩包已在 Phase 1 完成，本阶段只验证共形符号各客户端一致）。
- **Phase 4（后续，可选）**：目标框（需雷达 `POSITION`/`TARGET_OUT` 世界坐标输出）；counter-perspective（AR 护目镜式不变大小，默认不做）。

## 十四、落点文件清单

| 文件 | 改动 |
|---|---|
| `blocks/MonitorBlockEntity.java` | 加 6 字段 + NBT + `setChanged()` 同步（L40/L112/L133 附近） |
| `network/MonitorSettingsPacket.java` | 扩展携带 hudMode + 5 float |
| `blocks/MonitorScreen.java` | 设置区加 tab + HUD 开关 + 5 滑块（L868 附近发包点）；`collectDisplayElements`(L911) 覆盖 HUD 节点 |
| `client/renderer/MonitorBlockEntityRenderer.java` | `render()` 加 `hudMode` 分支；中心锚点玻璃 quad（✅ 历史 bug① 已修复：两侧左上角锚定 + clamp 上界 `1-2*bbHalf`，见补丁评审）；共形符号投影（§9.1） |
| `client/renderer/MonitorRenderTypes.java` | 加自发光/透明玻璃 `RenderType`（或复用现有） |
| `graph/NodeType.java` | Phase 2 加 `HUD_*` 枚举（稳定 id，L99-116 / L138 / L158-228） |
| `graph/GraphEvaluator.java` | Phase 2 HUD 节点输出值（透传玩家自接的输入：姿态/速度等） |
| `graph/GraphNode.java` | ✅ ⑤ W×H 已落地（字段/NBT `iw`/`ih`/迁移保护/`resizeImagePixels`，L429 起） |

## 十五、待定 / 开放问题

1. tab 切换是否立即切换世界渲染？→ 建议「是」（已记入 §五）。
2. counter-perspective（AR 式不随距离缩小）？→ **用户 12:21 确认不做**（保持透视缩放）。
3. 首批共形符号？→ **俯仰梯/地平线、航向带、速度矢量**（由座椅姿态+速度驱动）；目标框暂不做（需雷达）。
4. 面板变换默认值（2.0×1.2）是否合适？用户可在 HUD tab 调。
5. ⑤ W×H 图像是否并入 Phase 1？→ ✅ **已提前落地**（2026-08-16，见 `docs/monitor-image-fixes-audit.md`），不再依赖 HUD 排期。
6. 姿态/速度数据来源？→ **已澄清（用户 12:31）**：姿态数值由玩家自己在图中接线驱动（HUD_* 节点输入引脚接自任意节点），模组不耦合 `ControlSeatBlockEntity` 字段；刷新频率 20Hz（tick 率，即 `ClientboundGraphEvalPacket` 广播节拍）。本开放问题关闭。
