# 全息显示器 HUD 模式设计文档（收敛版 · AR 共形式）

> 状态：🔶 待办（设计已收敛、代码锚点已于 2026-08-16 评审 #2 全部重新核对）
> 基线：`1170bac`（评审 #2）。本文是用户原始 HUD spec 经代码核对后的**修正 + 收敛版**，所有结论均锚定真实代码，非臆测。
> 关键决策（2026-08-16，均已用户确认）：
> - 3D↔HUD 切换 = **全息显示器内部设置的选项卡切换**，互斥、参数不冲突。
> - 样式 = **战斗机 AR HUD（方案 B 共形瞄准）**：面板世界固定尺寸+透视正确，符号共形锁定世界（俯仰梯/地平线/航向带/速度矢量）。**姿态数值由玩家自己在图中接线驱动（控制座椅/姿态传感器/公式等任意节点皆可），刷新频率 20Hz（tick 率）**。不开独立开关/包/类/渲染器。
> - counter-perspective（AR 护目镜式不变大小）**明确不做**。
> - **渲染管线（2026-08-16 补充决策）**：**弃用离屏 FBO**，HUD 内容在 BER 主 pass 直接绘制——纯官方接口（`PoseStack` + `MultiBufferSource` + `MonitorRenderTypes` + `Font.drawInBatch`），与 3D 模式同一套 Sodium/Iris 已验证管线。原因：Sodium/Veil 世界管线在 flush 时重置视口/裁剪（读回实证），且采样渲染目标纹理在批处理 BER 管线不显示；直接绘制无纹理采样、无裸 GL、无 20Hz 节流（与 3D 模式同开销，见 §八）。
> 前置进展（截至 `1170bac`）：§十二 依赖的 ⑤（IMAGE/IMAGE_SEQUENCE 自定义 W×H 1..32）已落地；§三 提及的历史渲染 bug（补丁评审 ①）已修复；另已落地：像素编辑器定向同步、显示区拖拽稳定性/触屏、显示布局实时协作（存在包+软锁）、关屏定向提交。落地记录见 `docs/monitor-image-fixes-audit.md`。

## 评审修订记录（#2，2026-08-16）

| 位置 | 原断言 | 核对结果（代码现状） |
|---|---|---|
| §三 L72-80 / L250-251 | 渲染器 FACING 取角与「编辑器侧」L250-251 | ✅ 语义成立；行号漂移：世界渲染器现为 `MonitorBlockEntityRenderer` L71-87（FACING→toYRot + screenX/Z 旋转 + 中心平移 + yaw/pitch/roll 四元数）。原「编辑器侧 L250-251」已不在 BER——编辑器 2D 显示区现由 `MonitorScreen` 绘制（displayMode 路径，`collectDisplayElements` L1042 起），BER 只画世界侧 |
| §五「L868 发 MonitorSettingsPacket」 | 设置区发包点 | ❌ 行号失效。现为：设置面板 `renderSettingsPanel` L931、`handleSettingsClick` L1007、`saveAllSettings` L983（发包 L999-1001）、Enter 快捷保存 L1925。**设置面板已是显式 Apply 契约**（Apply 按钮/Enter 提交，ESC/× 放弃）——HUD tab 切换仍按设计「点击即发」（显式动作），5 个滑块随 Apply/Enter 提交 |
| §五 `toggleRunning(L40)` / `getUpdateTag(L133)` | 同步模式参考点 | ✅ 精确命中：`MonitorBlockEntity.toggleRunning` L40、`getUpdateTag` L133-135；`applySettings` L98-105 即「写字段 + setChanged + sendBlockUpdated(…,3)」模板，新 hudMode 字段照此办理 |
| §八 `RenderHandEvent` | 离屏 pass 入口 | ❌ **错误**。世界锚定面板的离屏 pass 应挂 `RenderLevelStageEvent`（如 `Stage.AFTER_SETUP`/`BEFORE_BLOCK_ENTITIES`），`RenderHandEvent` 只适用于第一人称手持渲染，不覆盖第三人称/其他玩家视角 |
| §八 FBO 宽高比示例 | 2.0×1.2 配 1024×512 → 拉伸 | ✅ 正确（2:1 ≠ 5:3）。补充正例：默认面板 2.0×1.2 = 5:3 → FBO 建议 **1280×768**（恰为 5:3） |
| §十 `GraphEditOpSyncPacket` | 图编辑同步包名 | ❌ **该类不存在**。实际链路：`GraphEditOpPacket`（C2S 通用 op）→ 服务端 `EditSessionRegistry.applyOp`（`flagFullSync()`，EditSessionRegistry L386）→ `sendBlockUpdated`/`getUpdateTag` 广播 → 客户端 `SyncedGraphBlockEntity.loadAdditional` 守卫替换（L686）。另存在 `GraphEditAckPacket`（ack/pendingLocalOps）与新增的 `GraphPresencePacket`（存在包，见 §十） |
| §十四 `MonitorRenderTypes.java`「加 RenderType（或复用现有）」 | 按新文件列出 | ❌ **该文件已存在**（`SCREEN_PIXEL` 等，Iris 兼容已验证）。HUD 玻璃 RenderType 加在此文件即可，非新文件 |
| 全文「FBO 每帧 new 禁止」 | 性能/显存约束 | ✅ 保留，且**当前代码库没有任何 Framebuffer 先例**——HUD 玻璃是首个 FBO，懒建/resize/dispose 生命周期必须一次做对（见 §十一） |
| §9.2 数据来源「姿态/速度」 | 由图中任意节点接线驱动 | ✅ 强化：`NodeType` 已内置 `ATTITUDE`(L78)、`VELOCITY`(L81)、`VIEW_ANGLE`(L72)、`DIRECTION`(L63)、`POSITION`(L64)、`POSE_CONVERT`(L68)——姿态/速度源节点**现成**，HUD_* 只需透传（见 §9.2） |
| §十 20Hz | `ClientboundGraphEvalPacket` 每 tick 广播 | ✅ 精确命中：`SyncedGraphBlockEntity.broadcastEvalSnapshot` L532-541，`MonitorBlockEntity.tick` L63 每 tick 调用；`ControlSeatBlockEntity` L352 同样广播 |
| §十一 包围盒裁剪 | 需扩大 getRenderBoundingBox | ✅ 风险仍在，但**模板已存在**：BER `getRenderBoundingBox` L238 起已实现「FACING 旋转 + yaw/pitch/roll 的旋转 AABB」，HUD 玻璃扩进同一 AABB 即可 |
| 新增（评审 #2 发现） | — | 客户端 `loadAdditional` 的守卫（SyncedGraphBlockEntity L686）只拦**图替换**；`loadTypeSpecific`（含 settings 字段）**无条件应用**（L703）→ hudMode/面板参数在编辑器开着时也能即时到达，恰好支撑「所见即所得」；反之图替换会被编辑中的客户端拦下（HUD 符号读的是 EvalSnapshot 输出，不依赖图对象本身，无冲突） |

## 〇、一句话定位

你要的「3D 世界锚定的战斗机 AR HUD」**完全可行**。最有利事实：Monitor 本就是 3D 锚定面板（非 2D GUI 覆盖层），且现有图管道已能输出座椅姿态/速度（`ATTITUDE`/`VELOCITY` 节点 + `ClientboundGraphEvalPacket` 20Hz 广播）。HUD 模式 = 加「刚性玻璃 quad + 共形符号节点」，复用 100% 现有基础设施。

## 一、设计哲学（三条不可妥协）

1. **面板锚定方块**：玻璃中心点/位姿由 `FACING` + `BlockPos` 决定，与玩家视点无关。
2. **显示组件恒定物理尺寸**：玻璃在 3D 空间固定世界尺寸，远处小近处大（透视），不漂移。
3. **符号共形锁定世界**：俯仰梯/航向带/速度矢量对齐真实世界（地平线/航向/航迹），转头时相对世界固定。
4. **多人共享**：所有玩家看到同一玻璃，内容服务端权威同步（玻璃几何共享；共形符号按各客户端相机本地投影，见 §9.1）。

## 二、与原始 spec 的关键修正（务必看）

| 原始 spec | 真实代码 / 修正 |
|---|---|
| `HoloDisplayBlockEntity` | → 直接扩展 `MonitorBlockEntity`（`blocks/`；其已继承 `SyncedGraphBlockEntity` L19，图/eval/同步全都有） |
| `HoloDisplayHudRenderer` | → 不新建，在 `MonitorBlockEntityRenderer.render()` 加 `if(be.hudMode) renderHud() else renderNormal()` 分支（render 入口 L45） |
| `HudModeData` 类 | → 不新建类，`MonitorBlockEntity` 的字段 + NBT（模式参考 `saveSettings` L107 / `loadSettings` L112 / `saveTypeSpecific` L120） |
| `SetHudModePacket` | → **不新建**，扩展已有 `MonitorSettingsPacket`（C2S，`BlockPos pos` + 8 float，见 §七） |
| ~~`Framebuffer` 每帧 `new`~~ | ❌ 已废弃（2026-08-16）：**无 FBO**——内容每帧直接生成顶点画到面板平面，无纹理上传、无显存生命周期（见 §八） |
| ~~BER.render() 内混画 FBO~~ | ❌ 已废弃：内容**就在** BER 主 pass 画（官方 BER 管线，与 3D 模式同路径），无离屏 pass、无 `RenderLevelStageEvent`/`RenderHandEvent` 挂钩 |
| ~~FBO 宽高比~~ | ❌ 已废弃：无 FBO 纹理，内容为世界几何，与面板 `panelSizeX/panelSizeY` 天然一致、任意距离不糊 |
| HUD 透传 `packedLight` | → 改为自发光：`0xF000F0`（现有 TEXT/DATA 渲染已这么干，BER L198），否则被方块光照压暗 |

## 三、3D 锚定早已存在（核对事实）

`MonitorBlockEntityRenderer.java`（L71-87）：
- `be.getBlockState().getValue(MonitorBlock.FACING).toYRot()` 取朝向；`screenX/Z` 按朝向旋转后 `translate(0.5+tx, screenY, 0.5+tz)` 移到方块中心；再依次乘 yaw/pitch/roll 四元数——在**方块局部 3D 帧**绘制。
- 编辑器侧 2D 显示区（displayMode）由 `MonitorScreen` 绘制（`collectDisplayElements` L1042、显示区渲染 L430 起），BER 只负责世界侧。

⇒ Monitor 已是 3D 锚定、不受 GUI Scale 影响。HUD 复用此坐标系即可，无需自己定位方块。

> ⚠️ 历史 bug（见 `docs/monitor-image-fixes.patch-review.md` ①，已于 2026-08-16 修复）：世界渲染器 clamp 上界曾用中心锚定公式（`1-bbHalfW`，左上角锚定的正确值为 `1-2*bbHalfW`，现 BER L148-149），导致缩放图像在边框处偏移——注意编辑器与世界渲染器**两侧均为左上角锚定**，修复仅改上界。教训：编辑器与渲染器的锚点语义与 clamp 上界必须一致。HUD 玻璃 quad 是全新绘制路径，建议**以面板中心为锚点**（新代码无历史锚点包袱），且共形符号投影见 §九之一。

## 四、尺寸与距离属性（用户 12:21 确认）

- **玻璃面板**：世界尺寸恒定 = `panelSizeX × panelSizeY` 方块单位；表观大小随距离透视缩放（远小近大，物理正确的座舱玻璃行为）；内容为世界几何直接绘制（非纹理采样）——任意距离锐利、随透视正确缩放；不受 GUI Scale 影响。
- **AR 共形样式（目标）**：用户确认「保持透视缩放」——玻璃仍按真实 3D 透视，仅符号做共形对齐（方案 B）。**counter-perspective（符号/面板随距离反向放大、永远不变大小）不做**，那是 AR 护目镜风、会打破「面板固定世界尺寸」原则。
- 共形符号（俯仰梯/地平线/航向带/速度矢量）投影数学见 §九之一；符号在玻璃上随玩家视角变化位置，但始终「贴」在对应世界方向上。

## 五、切换机制：选项卡切换（用户 11:53 确认）

`MonitorScreen` 设置面板加 tab 条：`[ 3D 模式 ] [ HUD 模式 ]`。
- tab 切换 = 模式切换：`hudMode` 即「当前 tab 身份」，互斥、参数分属各自 tab、互不冲突。
- 选中 HUD tab 即刻发 `MonitorSettingsPacket(hudMode=true)`；切回 3D tab 发 `false`（tab 点击是显式动作，与设置面板「显式 Apply 契约」不冲突）。
- 面板变换 5 参数（§六）仅在 HUD tab 可见，随面板 Apply 按钮/Enter 提交（现 `saveAllSettings` L983、Enter 快捷 L1925）；所见即所得的即时预览可复用现有 `previewScreenW/L` 机制（renderSettingsPanel L968-972）。

```
MonitorScreen 设置面板 (renderSettingsPanel L931 / saveAllSettings L983 / handleSettingsClick L1007)
   └─ tab 条: [3D 模式] [HUD 模式]
        │ 选中 HUD tab: MonitorSettingsPacket(pos, hudMode=true, panelSizeX/Y, offsetX/Y, distance)
        ▼
MonitorBlockEntity
   ├─ 字段: boolean hudMode + 5×float
   ├─ NBT: saveSettings(L107)/loadSettings(L112) + saveTypeSpecific/loadTypeSpecific(L120-129)
   └─ 变更: setChanged() + sendBlockUpdated(pos, state, state, 3)   ← 同 applySettings(L98-105)/toggleRunning(L40)，广播所有客户端
         ▼ 标准 BE getUpdateTag(L133-135) 同步
MonitorBlockEntityRenderer.render(L45)
   └─ if (be.hudMode) renderHud(...) else renderNormal(...)
```

净效果：切换是显示器自身的一个设置项，与其他 Monitor 设置（红石同步、显示缩放等）并列，服务端权威、所有客户端即时同步，**零新增广播包**。
客户端接收细节（评审 #2 确认）：`SyncedGraphBlockEntity.loadAdditional` 的守卫（L686）只拦图替换，`loadTypeSpecific`（含 settings）无条件应用（L703）→ 编辑器开着时 hudMode 也能即时生效；HUD 符号读 `cachedEvalSnapshot` 输出（BER L54-55），不依赖图对象引用，与守卫无冲突。

## 六、数据模型（MonitorBlockEntity 字段 + NBT）

```java
// MonitorBlockEntity 新增字段（模式参考现有 screenWidth 等 L21-23）
public boolean hudMode = false;          // 当前 tab（false=3D, true=HUD）
public float panelSizeX = 2.0f;          // 面板宽（方块单位）
public float panelSizeY = 1.2f;          // 面板高（方块单位）
public float panelOffsetX = 0.0f;        // 相对方块中心横向偏移
public float panelOffsetY = 0.0f;        // 相对方块中心纵向偏移
public float panelDistance = 0.05f;      // 面板距方块表面距离（沿 FACING 法线，须 ≥~0.05 防 z-fighting）

// NBT：saveSettings/loadSettings 增写 6 字段（旧档缺省 → 默认值，无需 DATA_VERSION 迁移）
// 同步：变更时 setChanged() + level.sendBlockUpdated(pos, state, state, 3)  ← 同 applySettings L98-105
// 数值 clamp 照抄 applySettings 的 Math.max/min 模式（L99-102）
```

## 七、网络（扩展 MonitorSettingsPacket）

`MonitorSettingsPacket`（C2S，`network/MonitorSettingsPacket.java`）：record 现为 `pos + 8 float`（L12-15），手写 `StreamCodec`（L20-35），`handle()` 有可达距离校验（L44）+ **编辑会话成员校验**（L45-46，`EditSessionRegistry.getEditors`）。扩展方案：
- record 与 codec 增写 `boolean hudMode` + `float panelSizeX/Y/OffsetX/Y/Distance`（共 6 个新字段）；
- `handle()` 调 `mbe.applySettings(...)` 时把新字段传入；`MonitorBlockEntity.applySettings` 同步扩参并 clamp。
- 会话成员校验意味着：**关屏（离开会话）后无法再切模式**——合理（只有编辑者可改显示器设置）。不新建任何 Packet 类。

## 八、渲染管线（BER 直接绘制，无离屏 FBO）

> 2026-08-16 决策：**弃用离屏 FBO 方案**（原 §8.1/8.2 已删除）。原因与替代见下。

### 8.1 决策背景（FBO 方案为何废弃）
FBO 方案经 11 个修复提交 + 实证仍不可用：
- **视口/裁剪被世界管线重置**：`RenderLevelStageEvent` 阶段绘制时，Sodium/Veil 世界管线在 flush 时把视口重置回窗口尺寸（854×480），内容只落进纹理一角（像素读回实证）；移入 `RenderGuiEvent.Post` 仍被改状态。
- **采样渲染目标纹理不显示**：玻璃 quad 采样「曾用作渲染目标的纹理」在批处理 BER 管线里无输出（劫持实验），被迫 glCopyTexSubImage2D 复制到普通纹理 + 大量裸 GL 状态操纵（视口/scissor/depth/cull/blend）——自研 GL 方案，违背官方接口原则。

### 8.2 现方案：BER 主 pass 直接绘制（纯官方接口）
`render()` 的 `hudMode` 分支（`renderHud`）在 BER 主 pass 直接画，**与 3D 模式同一套已验证的 Sodium/Iris 兼容路径**（`PoseStack` + `MultiBufferSource` + `MonitorRenderTypes.SCREEN_PIXEL` + `Font.drawInBatch`，无任何裸 GL）：
```java
public void render(be, partialTick, stack, buffer, packedLight, packedOverlay) {
    if (!be.hudMode) { renderNormal(...); return; }   // 3D 模式原样
    // 1. 移到方块中心 + 面板偏移（FACING 局部帧）
    stack.translate(0.5 + be.panelOffsetX, 0.5 + be.panelOffsetY, 0.5);
    // 2. 应用 FACING 旋转（yaw=0 → 方块正面）
    stack.mulPose(Axis.YP.rotation(-be.getBlockState().getValue(FACING).toYRot()));
    // 3. 沿 FACING 法线推到面板距离
    stack.translate(0, 0, be.panelDistance);
    // 4. 玻璃 tint quad：SCREEN_PIXEL（POSITION_COLOR，半透明深绿 0.35α），面板整幅无边框
    // 5. 内容：IMAGE 逐像素 quad + TEXT/DATA 用 font.drawInBatch，布局数学与 3D 模式
    //    完全一致（layoutX/Y 归一化、layerIndex 排序、旋转、信号偏移、左上角 clamp），
    //    内容区 = 整幅面板（无 bezel 边距），左上角锚点 y-up；packedLight = 0xF000F0 自发光
    // 6. flushTextNoCull(buffer) 冲刷文字（NO_CULL，与 3D 模式同）
}
```

关键结论：
- **清晰度**：内容是世界几何（逐像素 quad / 字体网格），任意距离锐利、随透视正确缩放——优于 FBO 纹理（近看糊、远看像素化）。
- **性能**：与 3D 模式同一开销模型（每帧生成顶点），无 FBO 纹理上传、无渲染端节流；20Hz 数据节拍由 `ClientboundGraphEvalPacket` 天然提供（§9.2）。
- **Sodium/Iris 兼容**：沿用 `MonitorRenderTypes.SCREEN_PIXEL`（`rendertype_position_color`，Iris 保留，BER L97-99 注释）——**无需新增任何 RenderType**（旧 `hudGlass` 已删除）。
- **包围盒**：面板尺寸 > 方块时，`getRenderBoundingBox` 必须把玻璃体积并入（`hudGlassAabb`，FACING 旋转 AABB 模板），否则某些视角凭空消失。

## 九、显示组件系统

**MVP（V1，可零新节点类型）**：直接复用现有 `TEXT`（文字=空速/高度数字）+ `IMAGE`（姿态仪贴图）节点当 HUD 元素，只加「hudMode 开关 + 玻璃 quad」。世界渲染器已支持这两类节点的 layerIndex 排序/旋转/全亮绘制（BER L102-200），复用面很完整。

**Phase 2 组件套件**：新增 `HUD_*` 节点类型（`NodeType.java` 加枚举，用**稳定 id 字符串**而非序号，迁移安全，见 `BY_ID` 查找表 L107-115）：
俯仰梯 `HUD_PITCH_LADDER`、航向带 `HUD_HEADING_TAPE`、空速 `HUD_AIRSPEED`、高度 `HUD_ALTITUDE`、准星 `HUD_RETICLE`、文字 `HUD_TEXT`。
每新增类型需在 4 处加分支：
1. `GraphEvaluator` — 计算输出值（多数 HUD 节点透传姿态输入，符号形态由渲染解释）；
2. `MonitorScreen.collectDisplayElements`(L1042) — 让节点进编辑器、拿 layout；
3. `MonitorBlockEntityRenderer` — 按值画符号；
4. lang 文件 — `node.create_schematic_compute.hud_*`。
注意 `editableParamCount()`(L138)、`inputLabel`/`outputLabel`(L158/L187) 也需覆盖新类型。

**布局**：节点已有 `layoutX/layoutY`（归一化 [0,1] 画布坐标）+ `displayScale`，spec 的 `(u,v,w,h)` 直接映射现有字段，Phase 6 工作量被高估。

### 9.1 共形符号投影（方案 B，用户 12:21 确认目标样式）

玻璃面板仍世界固定尺寸、透视正确（§四）。共形符号 = 把「世界方向/世界点」经**玩家视线**投射到玻璃平面，得面板局部 2D 坐标（与 layoutX/Y 同构，直接用于 BER 绘制）。逐客户端计算（每玩家相机不同，渲染本就是逐客户端）。

投影公式（每条符号/每帧）：
- 玻璃中心世界坐标 `C = blockPos + 0.5 + offset(沿FACING) + panelDistance·N`，法线 `N = FACING 方向`，由 `N` 与世界上方构造右向量 `R`、上向量 `U`（见 §8.2 旋转）。
- 玩家眼 `E`（相机位置）。对世界点 `P`（或对世界方向 `Dir` 取 `P = E + Dir·RANGE`，RANGE 任取大值）：
  - 射线 `D = normalize(P - E)`
  - `t = dot(C - E, N) / dot(D, N)`；若 `t > 0`（玻璃在眼前）则 `hit = E + D·t`
  - `local = hit - C`；`r = dot(local, R)`；`u = dot(local, U)`
  - UV = `(r / panelSizeX + 0.5, u / panelSizeY + 0.5)`，落在 [0,1] 内才绘制
- 符号因此「贴在世界上」：转头/移动时相对世界固定，符合战斗机 HUD 共形体验。枪炮十字(boresight)等固定符号仍按方案 A 贴玻璃（不投影）。

### 9.2 共形符号数据来源（首批：俯仰梯/地平线、航向带、速度矢量）

**姿态数值由玩家自己在图中接线驱动 —— 上游不硬编码任何座椅字段，HUD_* 节点就是普通图节点，其输入引脚由玩家从任意节点接（控制座椅 / 姿态传感器 / 公式 / 总线等皆可）。** 这样「数据从哪来」完全交给图，模组只负责把接进来的数值做共形投影渲染。**数据源节点现成**（`NodeType.java`）：`ATTITUDE`(L78)、`VELOCITY`(L81)、`VIEW_ANGLE`(L72)、`DIRECTION`(L63)、`POSITION`(L64)、`POSE_CONVERT`(L68)——控制座椅等源 BE 每 tick 广播求值快照（`ControlSeatBlockEntity` L352 / `broadcastEvalSnapshot` L532）。具体：

- **俯仰梯 + 地平线**：取 HUD 节点接进来的俯仰/滚转输入，在玻璃上画一系列不同俯仰角的水平线（按 §9.1 对「各俯仰角方向射线」求交），滚转旋转整组 → 地平线始终水平于世界。
- **航向带**：取接进来的航向/yaw 输入，画横向滚动刻度（各航向角方向射线求交）。
- **速度矢量（飞行航迹点）**：取接进来的世界速度向量 `V`，对其方向射线求交得玻璃点 → 画航迹标记。
- *目标框（暂不做）*：需雷达目标世界坐标 / 指定世界坐标节点输出（`POSITION`/`TARGET_OUT`），留待后续（无雷达依赖时不实现）。

**刷新频率 20Hz（tick 率）**：即图求值/广播的节拍（`ClientboundGraphEvalPacket` 每 tick 推一次 outputs，`broadcastEvalSnapshot` L532-541）。HUD 节点读到的输入本就 20Hz 更新 → 共形投影与 FBO 重绘自然落在 20Hz，无需额外轮询机制。这也呼应 §8.2 的 FBO 20Hz 节流（二者同源）。
注意：图未运行时快照为 null（BER L54-55 `evalAvailable`），共形符号此时不画（与 DATA 节点同规则）。

## 十、多人同步（已就绪，评审 #2 修正包名）

- 图编辑：`GraphEditOpPacket`（C2S 通用 op）→ 服务端 `EditSessionRegistry.applyOp` → `flagFullSync()`（EditSessionRegistry L386）→ `sendBlockUpdated`/`getUpdateTag` 广播 → 客户端 `SyncedGraphBlockEntity.loadAdditional` 守卫替换（L686）。**不存在 `GraphEditOpSyncPacket`**（旧版笔误）。另有 `GraphEditAckPacket` 做 ack/`pendingLocalOps` 回弹保护。
- 求值结果：`ClientboundGraphEvalPacket` 已广播 `Map<Integer,float[]> outputs`（俯仰/滚转/航向/空速/高度/速度向量走这条）。
- 模式/变换：扩 `MonitorSettingsPacket` → 零新 eval/sync 基建。
- 共形符号投影在各客户端本地算（相机不同），服务端只同步原始姿态/速度世界坐标，不传纹理。
- **存在包（新增基建）**：`GraphPresencePacket` 现有 mode 0=图编辑/1=显示模式（`MonitorScreen.getPresenceMode` L1296）。HUD tab 位于图模式设置面板内 → 归属 mode 0，**无需第三模式**；若未来给 HUD tab 做独立全屏编辑器，再扩一个 mode 值即可（模式隔离机制已就绪）。
⇒ 用户原始 spec「复用 100% 基础设施」成立。

## 十一、风险与缓解

| 风险 | 缓解 |
|---|---|
| ~~FBO 每帧 `new` → 泄漏（本代码库首个 FBO）~~ | ✅ 无 FBO（2026-08-16 决策）——内容直接绘制，无显存泄漏面 |
| ~~BER 内混画 FBO 打乱渲染目标~~ | ✅ 无离屏 pass——内容在 BER 主 pass 官方管线内画，不切换渲染目标 |
| 深度冲突（面板与方块重叠） | `panelDistance ≥ ~0.05` |
| 透明排序（被其他透明方块覆盖） | `RenderType.entityTranslucent` + 适当顺序；必要时 `noCull` |
| 渲染包围盒裁剪 | **扩大 `getRenderBoundingBox()`** 覆盖面板体积（模板：BER L238 的旋转 AABB；面板比方块大时必做，否则某些视角凭空消失） |
| 背面单面消隐 | 单面 quad 背面不可见（物理正确）；要双面玻璃用 `noCull` |
| HUD 每帧顶点开销 | 与 3D 模式同一模型（逐像素 quad + 字体网格）；图像分辨率越大开销越高，与 3D 模式共享既有 displayScale 调参 |
| 共形投影在玻璃背后（t≤0） | 该符号不绘制（落在视野外），避免错误地镜像到玻璃 |
| GUI Scale 影响 | 无（3D 渲染，正是你要的） |
| Iris/Sodium 兼容 | `MonitorRenderTypes.SCREEN_PIXEL` 已 Iris 保留（BER L97-99），与 3D 模式同一路径；无新增 RenderType |
| 编辑中客户端收不到 hudMode | 不会：`loadAdditional` 守卫只拦图替换，settings 字段无条件应用（L703） |
| 符号内容与图编辑状态互相干扰 | 不会：HUD 符号读 `cachedEvalSnapshot` 输出，不引用图对象；图替换守卫与符号渲染解耦 |

## 十二、与已落地修复协同

- ✅ ⑤（图像节点 W×H 长方形，1..32）已落地（含像素编辑器/渲染器/网络全链路，见 `docs/monitor-image-fixes-audit.md`），飞行姿态仪的长方形画布前置条件已就绪。
- ✅ ① 历史渲染 bug（clamp 上界）已修复（补丁评审 ①）。
- ✅ 像素编辑器定向同步、显示区拖拽稳定性/触屏、显示布局实时协作（存在包+软锁+模式隔离）、关屏定向提交均已落地——HUD Phase 1 的「tab 即发 + Apply 契约」与这些修复共用同一套设置面板/网络基建。
- 新增 `RETICLE` 节点仍随 HUD 排期（Phase 2 的 `HUD_RETICLE`）。

## 十三、开发路线（收敛到真实文件）

- **Phase 1（MVP）**：`MonitorBlockEntity` 字段+NBT+同步（照 `applySettings` L98-105 模板）；`MonitorSettingsPacket` 扩展；`MonitorScreen` 设置面板 tab+HUD 开关+5 滑块（`renderSettingsPanel` L931 / `saveAllSettings` L983，遵循显式 Apply 契约）；渲染器 `hudMode` 分支 + 玻璃面板 + 内容直接绘制（纯官方接口，见 §八）；复用 `TEXT`/`IMAGE` 节点。→ 单人可见 3D HUD 玻璃，固定符号居中稳定。
- **Phase 2（AR 共形）**：`HUD_*` 组件套件 + **共形投影（§9.1）**：俯仰梯/地平线、航向带、速度矢量（数据来源 §9.2，`ATTITUDE`/`VELOCITY` 等现成节点，由玩家在图中自接输入引脚驱动，数据刷新 20Hz）；面板变换在编辑器内调参。
- **Phase 3**：多人验证（扩包已在 Phase 1 完成，本阶段只验证共形符号各客户端一致）。
- **Phase 4（后续，可选）**：目标框（需雷达 `POSITION`/`TARGET_OUT` 世界坐标输出）；counter-perspective（AR 护目镜式不变大小，默认不做）。

## 十四、落点文件清单

| 文件 | 改动 |
|---|---|
| `blocks/MonitorBlockEntity.java` | 加 6 字段 + NBT（saveSettings L107/loadSettings L112）+ `applySettings` 扩参（L98-105 模板）+ `setChanged()`/`sendBlockUpdated` 同步；`getUpdateTag` L133 已覆盖 |
| `network/MonitorSettingsPacket.java` | 扩展携带 hudMode + 5 float（record L12-15 + 手写 codec L20-35 + handle L39-52） |
| `blocks/MonitorScreen.java` | 设置区加 tab + HUD 开关 + 5 滑块（`renderSettingsPanel` L931 / `handleSettingsClick` L1007 / `saveAllSettings` L983 / Enter L1925）；`collectDisplayElements`(L1042) 覆盖 HUD 节点 |
| `client/renderer/MonitorBlockEntityRenderer.java` | `render()`(L45) 加 `hudMode` 分支 → `renderHud`：玻璃 tint quad + 内容直接绘制（布局数学与 3D 模式一致，左上角锚定 + clamp 上界 `1-2*bbHalf`）；共形符号投影（§9.1，Phase 2）；`getRenderBoundingBox` 经 `hudGlassAabb` 并入玻璃体积 |
| `client/renderer/MonitorRenderTypes.java` | **已存在**（`SCREEN_PIXEL` 等）——直接复用，**无需新增 RenderType**（旧 `hudGlass` 已删） |
| `graph/NodeType.java` | Phase 2 加 `HUD_*` 枚举（稳定 id，`BY_ID` L107-115 / `editableParamCount` L138 / `inputLabel` L158 / `outputLabel` L187） |
| `graph/GraphEvaluator.java` | Phase 2 HUD 节点输出值（透传玩家自接的输入：姿态/速度等） |
| `graph/GraphNode.java` | ✅ ⑤ W×H 已落地（字段/NBT `iw`/`ih`/迁移保护/`resizeImagePixels`），无需再动 |
| `blocks/SyncedGraphBlockEntity.java` | 无需改动（评审 #2 核对：settings 字段经 `loadTypeSpecific` 无条件同步 L703，守卫 L686 只拦图替换，与 HUD 无冲突） |

## 十五、待定 / 开放问题

1. tab 切换是否立即切换世界渲染？→ 建议「是」（已记入 §五；tab 点击本身是显式动作，与 Apply 契约兼容）。
2. counter-perspective（AR 式不随距离缩小）？→ **用户 12:21 确认不做**（保持透视缩放）。
3. 首批共形符号？→ **俯仰梯/地平线、航向带、速度矢量**（由座椅姿态+速度驱动）；目标框暂不做（需雷达）。
4. 面板变换默认值（2.0×1.2）是否合适？→ 用户可在 HUD tab 调（无 FBO 配套尺寸问题——内容为世界几何，直接随面板缩放）。
5. ⑤ W×H 图像是否并入 Phase 1？→ ✅ **已提前落地**（2026-08-16，见 `docs/monitor-image-fixes-audit.md`），不再依赖 HUD 排期。
6. 姿态/速度数据来源？→ **已澄清（用户 12:31）**：姿态数值由玩家自己在图中接线驱动（HUD_* 节点输入引脚接自任意节点，`ATTITUDE`/`VELOCITY` 等源节点现成），模组不耦合 `ControlSeatBlockEntity` 字段；刷新频率 20Hz（tick 率，即 `ClientboundGraphEvalPacket` 广播节拍）。本开放问题关闭。
7. （评审 #2 新增）HUD tab 是否需要第三存在包模式？→ 当前无需（tab 在图模式设置面板内，mode 0）；独立全屏 HUD 编辑器时再扩。
