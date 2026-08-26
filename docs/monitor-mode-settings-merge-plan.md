# 全息显示器：双模式设置合并 + 统一细边框

> 状态：✅ 已解决（v1.2.5 实现，代码事实基于 `2aff365`）
> 风格模板：Sable（核心结论 / 现状 / 方案 / 注意事项 / 验证清单）

---

## 一、核心结论

1. **双模式设置合并为单一面板**：3D 虚拟屏与 HUD 虚像大屏的设置不再用 tab 切换，统一进一个设置面板。
2. **模式选择收敛为一个布尔开关 `hudMode`（是否启用 HUD 模式）**：HUD 本就是"虚像大屏"，无需另设一套独立 UI。`hudMode=false` 走 3D 画布变换（`screenWidth/screenLength/...` 8 项），`hudMode=true` 走面板变换（`panelSizeX/panelSizeY/...` 5 项）。两组参数全部保留在同一个面板、同一个设置包里。
3. **普通 3D 模式也改用 HUD 模式的细线边框（"1 像素"）**：替换现有的 0.04 方块单位粗边框，使两种模式视觉一致。
4. **新增「虚像屏幕大小」设置（HUD 模式）**：新增独立参数 `virtualImageScale`（虚像缩放系数，默认 1.0），只缩放 HUD 虚像**内容画布**（`cw/ch` @ `MonitorBlockEntityRenderer.java` L430），与物理玻璃面板（`panelSizeX/panelSizeY`，近处边框 L319）解耦。调大虚像时物理玻璃不变、超出玻璃视口的内容被 4 边形遮罩裁剪（透过玻璃看 HUD，物理正确）。

> 范围说明：第 1/3 项（合并面板 + 统一细边框）**只动 UI 呈现层 + 边框绘制层，数据契约零变化**；第 4 项（虚像屏幕大小）**新增 1 个设置字段**，属于数据契约扩展（见 §3.3）。旧存档缺该字段时回落默认 1.0，无迁移风险。

---

## 二、现状（代码事实，2aff365）

### 设置数据模型
- `MonitorSettingsPacket`（C2S）：3D 8 项 `screenWidth, screenLength, screenX, screenY, screenZ, screenRoll, screenPitch, screenYaw` + `hudMode`(bool) + HUD 5 项 `panelSizeX, panelSizeY, panelOffsetX, panelOffsetY, panelDistance`（共 14 字段）。
- `MonitorBlockEntity`：同上 13 个字段 + NBT（`ss_w/ss_l/ss_x/ss_y/ss_z/ss_r/ss_p/ss_yw` + `hm` + `ps_x/ps_y/po_x/po_y/pd`）；`applySettings(...)` / `saveSettings(...)` / `loadSettings(...)` 已含 `hudMode`。
- **（规划新增）`virtualImageScale`(float, 默认 1.0)**：虚像内容画布缩放系数。HUD 模式新增第 6 项设置；详见 §3.4。当前 L430 `cw = be.panelSizeX, ch = be.panelSizeY` 未含缩放，故现有虚像大小完全由 `panelSizeX/panelSizeY` 绑定（物理玻璃与内容耦合），本次用独立系数解耦。

### 设置 UI（`MonitorScreen.renderSettingsPanel`，L847）
- 用 `settingsTabHud` 布尔做 **`[3D 模式] / [HUD 模式]` tab 切换**（`drawSettingsTab` 两个调用，L885-888）。
- `rows = settingsTabHud ? 5 : 8`（L850）—— 按 tab 决定显示 8 项还是 5 项。
- 两组 EditBox：`settingFields[8]`（3D）、`hudSettingFields[5]`（HUD），打开时都从 BE 加载（L865-877），`settingsTabHud = mbe.hudMode`（L878）使 tab 跟随服务端模式。
- `saveAllSettings()` 一次把 14 字段全部写进 `MonitorSettingsPacket` 发出。

### 渲染器边框（两种模式不一致）
- **3D 模式（主 `render` 路径 L130-141）**：`drawBorderFace(sceneBuf, m, l, r, t, b, bw=0.04, ±1)` —— **0.04 方块单位粗边框**（`l,r,t,b = -hw,hw,-hh,hh`，`hw=screenWidth*0.5`）。
- **HUD 模式（`renderHud` L399-402）**：`addThickLine(tintBuf, m, -hw,-hh, hw,-hh, 0.01, 0.0005f, r,g,b,a)` 等 4 条 —— 半厚 `0.0005f` → 约 0.001 方块，即 **"1 像素"细线**，几何取自 `panelSizeX/panelSizeY`。
- 两者都用 `MonitorRenderTypes.SCREEN_PIXEL`（position_color）缓冲，仅粗细不同。
- `drawBorderFace` 仅被 3D 模式调用（L140-141，定义 L1513），HUD 走 `addThickLine`。

---

## 三、调整方案

### 3.1 设置面板合并（UI 层）
- 删除 `settingsTabHud` 的 tab 条（`drawSettingsTab` 两处调用 + `rows` 三元的 8/5 分支）。
- 面板**顶部加一个 `hudMode` 复选框**（标签「HUD 模式（虚像大屏）」）。
- 下方**始终渲染两组字段**：`settingFields`（3D 8 项）+ `hudSettingFields`（HUD 6 项，含新增 `virtualImageScale`）；**非激活一组置灰禁用**（依 `hudMode` 取反：`f.active = (该组为激活组)`）。
- 打开面板时：`hudMode` 复选框取 `mbe.hudMode`；两组字段照旧从 BE 加载（保留现有 init 逻辑，去掉 `settingsTabHud = mbe.hudMode` 这行，改用复选框状态驱动）；`virtualImageScale` 从 `mbe.virtualImageScale` 加载（缺省 1.0）。
- 保存（`saveAllSettings`）：行为不变 —— 全部字段写进 `MonitorSettingsPacket`（3D 8 + `hudMode` + HUD 6 = 15 字段，含新增 `virtualImageScale`）。

### 3.2 边框统一（渲染层）
- 3D 模式**丢弃 `drawBorderFace(..., bw=0.04, ±1)` 两次调用**，改调与 HUD 一致的 4 条 `addThickLine(... w=0.0005f ...)`（复用 `MonitorRenderTypes.SCREEN_PIXEL` 缓冲）。边框几何仍取自 3D 画布 `l,r,t,b = -hw,hw,-hh,hh`（`hw=screenWidth*0.5`）。
- HUD 模式边框**不变**（`addThickLine` 细线，几何取自 `panelSizeX/panelSizeY`）。
- 两模式视觉一致：细线勾勒屏幕/玻璃轮廓，无底色填充（HUD 已弃用 tint 底色，见 L388-392 注释）。
- `drawBorderFace`（L1513）在 3D 改走 `addThickLine` 后**成为死代码，可删除**（已确认仅 3D 调用）。

### 3.3 设置包 / 数据模型（合并 + 边框部分）
- **合并面板与统一细边框（第 1/3 项）不改数据契约**：`MonitorSettingsPacket` 字段与编解码、`MonitorBlockEntity.applySettings/saveSettings/loadSettings`、NBT key 全部保持（14 字段）。仅 UI 呈现 + 边框绘制层改动，旧存档/旧图无迁移。

### 3.4 新增「虚像屏幕大小」设置（HUD 模式，第 4 项）
- **新增参数 `virtualImageScale`（虚像缩放系数）**：float，默认 `1.0`，建议范围 `[0.25, 4.0]`，步进 `0.05`（UI 滑块）。含义：虚像内容画布尺寸 = `panelSizeX * virtualImageScale` × `panelSizeY * virtualImageScale`。
- **解耦物理玻璃**：`panelSizeX/panelSizeY` 仍只描述**近处玻璃面板**（L319 `hw=panelSizeX*0.5`）与遮罩投影（L386 `projectGlassCornersToCanvas` 用 `hw/hh`）；`virtualImageScale` 仅作用于虚像内容画布（L430 `cw/ch`），二者互不干扰。
- **渲染器改动**（`MonitorBlockEntityRenderer.renderHud` L430）：
  ```java
  // 原：float cw = be.panelSizeX, ch = be.panelSizeY;
  float cw = be.panelSizeX * be.virtualImageScale;   // 虚像内容尺寸随缩放系数
  float ch = be.panelSizeY * be.virtualImageScale;
  ```
  其余依赖 `cw/ch` 的绘制（俯仰梯、文字、图层）自动随缩放；**遮罩与玻璃边框仍用 `panelSizeX/panelSizeY`**，保证调大虚像时超出玻璃视口的内容被裁剪（透过玻璃看 HUD，物理正确）。
- **数据契约扩展**（新增 1 字段）：
  - `MonitorSettingsPacket`：增加 `virtualImageScale` float（现共 15 字段）；编解码顺序追加在 HUD 组末尾。
  - `MonitorBlockEntity`：新增字段 `virtualImageScale`（默认 1.0）+ NBT key `vis`；`applySettings/saveSettings/loadSettings` 增入该字段。
  - `MonitorScreen`：`hudSettingFields` 数组扩为 6，`virtualImageScale` 对应滑块（行 6）。
- **旧存档兼容**：NBT 缺 `vis` key 时回落默认 `1.0`，无需迁移脚本；首次保存即写入。

---

## 四、改动清单（文件 / 位置）

| 文件 | 位置 | 改动 |
|---|---|---|
| `MonitorScreen.java` | `renderSettingsPanel` L847-920 | 删除 tab 条（`drawSettingsTab` 两调用 + `rows` 三元的 8/5 分支）；顶部加 `hudMode` 复选框；两组字段常显、非激活组 `f.active=false` 置灰；移 `settingsTabHud = mbe.hudMode`（L878）改为复选框初始化；`hudSettingFields` 扩为 6 项，新增 `virtualImageScale` 滑块（行 6）。 |
| `MonitorScreen.java` | 字段声明 L95 | `settingsTabHud` 移除（或仅留作内部状态）；新增复选框控件字段；`hudSettingFields` 数组长度 5→6。 |
| `MonitorBlockEntity.java` | 字段 + `applySettings/saveSettings/loadSettings` | 新增 `virtualImageScale`（默认 1.0）+ NBT key `vis`；三处方法增入该字段读写。 |
| `MonitorSettingsPacket.java` | 编解码 | 新增 `virtualImageScale` float 字段，编解码顺序接在 HUD 组（`panelDistance`）之后（现共 15 字段）。 |
| `MonitorBlockEntityRenderer.java` | 3D 边框 L130-141 | 移除 `drawBorderFace(...,0.04,±1)` 两次调用，替换为 4×`addThickLine(...,0.0005f,...)`（几何同原 `l,r,t,b`）。 |
| `MonitorBlockEntityRenderer.java` | `renderHud` 内容画布 L430 | `cw/ch` 改为 `be.panelSizeX * be.virtualImageScale` / `be.panelSizeY * be.virtualImageScale`；玻璃边框与遮罩仍用未缩放的 `panelSizeX/panelSizeY`。 |
| `MonitorBlockEntityRenderer.java` | `drawBorderFace` L1511+ | 仅 3D 调用、现被 3D 弃用 → 删除死代码（HUD 不调它）。 |

---

## 五、注意事项

1. **`drawBorderFace` 仅 3D 调用**：已 grep 确认无其他调用点（仅 L140-141），删除安全；HUD 路径走 `addThickLine`，不受影响。
2. **3D 细线 z 偏移**：`addThickLine` 第 8 参数 `z` 在 HUD 用 `0.0005f` 防 z-fight；3D 模式边框原在屏幕平面，复用同值即可。
3. **复选框与 `hudMode` 字段同步**：面板打开/保存要确保 `hudMode` 复选状态正确往返 `MonitorBlockEntity.hudMode`（现有 `applySettings` 已含 `hudMode` 参数，无需改包）。
4. **置灰非激活组**：仅 UI 禁用 EditBox（`f.active=false`），保存时仍读值写包，保证切回模式时数值不丢；或仅读激活组 —— 建议两组都写，非激活组值保留在 BE。
5. **旧存档兼容（合并+边框）**：NBT key 未变，仅 UI/渲染层改动，旧图打开即新版面板，无迁移成本。
6. **面板高度自适应**：去掉 tab 条后，面板需同时容纳 1（复选框）+ 8 + 6 字段，原 `ph = 56 + 20 + rows*20 + 30` 计算需改为固定 15 行（或滚动），避免溢出屏幕。
7. **新增 `virtualImageScale` 的旧档兼容**：NBT 缺 `vis` key 时 `MonitorBlockEntity` 回落默认 `1.0`，无需迁移；首次保存即写入，不影响既有 3D/HUD 存档。
8. **遮罩与玻璃不随虚像缩放**：`projectGlassCornersToCanvas`（L386）与近处边框（L319）仍用未缩放 `panelSizeX/panelSizeY`，仅 `cw/ch`（L430）乘 `virtualImageScale`；确保"放大虚像 → 内容在玻璃视口外被裁剪"的物理正确行为。
9. **缩放上界钳制**：`virtualImageScale` 上限建议 `4.0`；过大时虚像内容画布（×`VIRTUAL_IMAGE_D=100`）世界尺寸达数百格，掠射角触发 `MAX_ANCHOR_S` 钳制（已有，L294），正常显示不受影响，但极端值仍可能让内容远超玻璃视口几乎不可见 —— UI 滑块封顶即可。

---

## 六、验证清单

- [ ] 设置面板无 tab，仅一个 `hudMode` 复选框 + 两组字段常显。
- [ ] 勾选 `hudMode` → 3D 字段置灰、HUD 字段可编辑；取消 → 反之。
- [ ] 保存后 `mbe.hudMode` 与服务端一致；重开面板复选状态正确。
- [ ] 3D 模式边框为细线（≈HUD 粗细），不再有 0.04 粗框。
- [ ] HUD 模式边框不变（细线 + 玻璃面）。
- [ ] 旧存档/旧图仍能正常打开设置、两种模式切换无 NBT 报错。
- [ ] `drawBorderFace` 已删除、无编译引用残留。
- [ ] HUD 设置面板新增「虚像缩放」滑块（行 6），范围/步进正确，默认值 1.0。
- [ ] 调大 `virtualImageScale` → 虚像内容（俯仰梯/文字/图层）整体变大；物理玻璃边框与遮罩不变。
- [ ] 调大至内容超出玻璃视口时，超出部分被 4 边形遮罩裁剪（透过玻璃看，物理正确）。
- [ ] 旧存档（无 `vis` NBT）打开回落 1.0；保存后写入 `vis`，重开数值保留。
- [ ] `MonitorSettingsPacket` 编解码含 `virtualImageScale`，与 `MonitorBlockEntity` NBT 往返一致。
