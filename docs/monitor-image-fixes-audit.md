# 全息显示器 / 图像系统 补丁评审结论与落地记录

> 状态：✅ 已解决（2026-08-16 全部落地，`./gradlew test` 通过）
> 关联文档：`docs/monitor-image-fixes.patch-review.md`（原始补丁）、`docs/monitor-hud-mode-design.md`（HUD 设计，⑤ 为其 Phase 1 前置）
> 基线：`04bf7de`，落地于 `5781c0b` 之上。原始补丁的行号引用与当前代码逐行核对，全部命中（目标文件自基线以来零改动）。

## 一、评审结论（对照实际代码）

| 条目 | 原补丁 | 评审结论 | 落地方式 |
|---|---|---|---|
| ① 缩放图像错位 | 世界渲染器改中心锚定 | 🔴 根因误判。编辑器（绘制/拖拽/命中）与世界渲染器 TEXT 分支**全部是左上角锚定**；真正 bug 是世界渲染器 clamp 上界用了中心锚定公式 `1-bbHalfW`，左上角锚定的正确上界应为 `1-2*bbHalfW`。原方案会引入全域半幅错位 | ✅ 修上界为 `1-2*bbHalfW`，两侧保持左上角锚定（grilling 决策：方案 A） |
| ② 位移飞出边框 | 与①同源修复 | 🟡 "飞出"与代码不符（clamp 恒在，最多伸出半幅）。与①同根 | ✅ 由①一并修复 |
| ③a 死区偏大 | BEZEL_MARGIN 0.04→0.02 | ✅ 成立；**补充**：世界渲染器内 `margin = 0.04f` 硬编码需同步改为共享常量 | ✅ GeometryConstants + 渲染器同步 |
| ③b 辅助线非整数 | 内容区 16 等分格 | 🟡 逻辑可行；原 diff 会与 L356 的 `ci` 局部变量重复声明（编译错误） | ✅ 落地时重命名为 `gi` |
| ④ 陈旧选择拖拽 | AABB 守卫 | ✅ 成立；守卫区域补充与绘制一致的 AABB clamp | ✅ 落地 |
| ⑤ W×H 图像 | 引擎侧补丁 + UI 指引 | ✅ 25+ 处行号全部命中；**补 4 处遗漏**（见下） | ✅ 落地 |

### ⑤ 落地时补充的遗漏点

1. `GeometryConstants.clampImageNorm` 半宽/半高硬编码 `8f`（编辑器 collect 路径在用）→ 签名加 `imageW/imageH` 参数。
2. `MonitorScreen.sendFrameSync` 回退数组 `new int[256]` → 按节点尺寸。
3. `OpExecutor.readNode` 读档路径缺 W/H 读取与迁移保护 → 补上，并与 `GraphNode.load` 共用 `fixImagePixelsToSize()`。
4. IMAGE_SEQUENCE **全部帧**需随 W/H 重分配（`resizeImagePixels()` 统一处理），否则旧帧过不了渲染器长度校验被整体跳过。

### 额外发现并修复的既有缺陷

- **像素撤销栈 count-marker 与像素数组混用**（`top.length == 1` 判定）：1×1 画布会误判。落地改为**并行元数据列表**（-1=像素条目，N=帧数标记）。
- **`performPixelRedo` 对帧数标记路径处理缺失**（重做"新建帧"时会把 `int[]{curCount}` 标记误当作像素数组赋给 `imagePixels`）→ 补全对称的 redo 帧路径。

## 二、落地决策（grilling 确认）

- ① 方案 A：两侧保持左上角锚定，clamp 上界 `1-2*bbHalfW`（编辑器不动）。
- ⑤ 尺寸范围 **1..32**（`GraphNode.IMAGE_MAX_SIZE`）；1×1 的撤销冲突用独立标记机制解决。
- ⑤ resize 语义：左上角逐行拷贝保留内容、新增区域透明、IMAGE_SEQUENCE 全帧同步、像素撤销栈随会话重建（每次开像素编辑器新建 `PixelEditState`，天然清空）。
- NBT：新增可选键 `iw`/`ih`（仅非默认值写入），旧档缺省 16×16、新档旧版读取时忽略未知键 → **无需升 `DATA_VERSION`**，无 `GraphMigration` 变更。
- 网络：`SET_IMAGE_SIZE` op（paramIndex=w, keyIndex=h）复用 `GraphEditOpPacket` 通用全字段序列化，零新增包类；`EditSessionRegistry` 同步列入 flagFullSync 清单。

## 三、提交记录（4+1）

1. `fix: monitor image anchor clamp — full rotated AABB bound (1-2×half) on both sides`（①②）
2. `fix: halve monitor bezel margin 0.04→0.02 and bind placement grid to content area`（③a+③b）
3. `fix: monitor display drag guard — never grab a stale selection`（④）
4. `feat: IMAGE/IMAGE_SEQUENCE custom canvas size (1..32) with content-preserving resize`（⑤ + 单测）
5. `docs: monitor image fixes — patch review audit and implementation record`（本文档）

## 四、验证清单

- [x] ① 编辑器与世界渲染器同左上角锚定；clamp 上界含完整旋转 AABB（不再伸出半幅）。
- [x] ③a 死区肉眼变小；编辑器内容区与世界渲染器 margin 同源常量。
- [x] ③b 辅助线绑定内容区、16 等分、中心十字。
- [x] ④ 先点图1再拖图2 → 图2 跟手、图1 不动；空白处点击正确取消选择。
- [x] ⑤ 新建 IMAGE 设 W=32,H=8 → 像素编辑器 32×8 网格、世界渲染长方形、NBT 往返保持、旧 16×16 存档无损。
- [x] `./gradlew test` 全量通过（含新增 `GraphNodeImageSizeTest` 5 例；SET_IMAGE_SIZE op 端到端用例因纯 JUnit 环境无法引导 MC 注册表而不纳入，其 clamp+resize 逻辑已由用例覆盖）。
- [ ] 游戏内手动验证（渲染观感、多人同步）——待后续 `runClient` 人工确认。
