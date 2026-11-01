# Sable 版本兼容性测试手册 / Sable Version Compatibility Testing

> **状态**：基建建于 2026-10-31（分支 `csc-sable-compat`），首轮全量矩阵已跑。
> 矩阵结论见 [`sable-compat-report.md`](sable-compat-report.md)。
> **Status**: tooling landed 2026-10-31; the first full matrix is recorded in the report.

本手册解决的问题：mods.toml 的 Sable 可选依赖区间最初收在 `[1.2.2, 1.3)`，收口理由是
「mixin 深入 Sable 内部、未验证版本（含 2.x）应显式报警而非静默失效」（P2-21）。收口是对的，
但「哪些版本其实能用」此前没有证据 —— 区间要不要放、放到哪，只能靠这套矩阵 + 行为级实测说话。
首轮矩阵（2026-11-01）验证 1.x/2.0.x 线三关全绿后，区间已放宽到 `[1.2.2, 2.1)`：下限保留
1.2.2（plot 网格中心+本地坐标框架的口径起点），上限收在 2.1 之前 —— 未验证的版本线照旧
显式报警，扩窗前仍先跑矩阵。

## 一、组件 / components

工具都在 `tools/sable-compat/`（脚本与清单入库；下载的 jar 不入库）：

| 文件 | 职责 |
|---|---|
| `fetch_sable_jars.py` | 从 Modrinth 拉 Sable NeoForge 线 + MC 1.21.1 全量版本到 `jars/<版本>/`，并从每个 jar 内嵌 jarJar 包抽出配套 companion；写 `jars/manifest.json` 清单 |
| `linkage-points.json` | 链接点清单：本模组依赖的 Sable 成员全集（含 mixin 字符串目标），探针与矩阵的口径来源 |
| `check_linkage.py` | 链接点探针（纯 Python 读 class 文件，不依赖 JVM）：逐条核对成员在候选 jar 里是否还在；秒级 |
| `run_compat_matrix.py` | 矩阵驱动：逐版本「链接点 → 全量重编 → 单测」三关，产出报告 |
| `runclient_with_sable.py` | 切任意版本起 runClient，行为级实测的入口 |

构建侧两个旋钮（`build.gradle`，不传时行为与原先一致）：

- `-PsableCompatDir=<目录>` —— 用该目录里的 sable + companion jar 对替换 `libs/` 里的基线对，
  编译/运行/测试 classpath 同步换；`libs/` 的 `sable-*.jar` 同时被排除，防止两份同名类撞车。
- `-PsableVersionRange=<区间>` —— 覆盖 mods.toml 的 `sable_version_range` token。
  切版本实测时由 `runclient_with_sable.py` 自动带上候选 jar 自述版本的精确区间，
  免得「可选依赖版本不符」警告混进实测噪声；仓库里的 `gradle.properties` 基线区间不动。

## 二、工作流 / workflow

1. **拉版本**（增量，已校验的跳过）：

   ```
   python tools/sable-compat/fetch_sable_jars.py
   ```

2. **跑矩阵**（全量重编口径，一个版本约 2–4 分钟，全量约半小时）：

   ```
   python tools/sable-compat/run_compat_matrix.py                # 全量 + 写报告
   python tools/sable-compat/run_compat_matrix.py --only 1.0.4   # 挑版本复核
   ```

   报告覆盖写入 `docs/sable-compat-report.md`。矩阵开跑先无旋钮捕获**基线失败集**
   （被测提交自带的红测试），版本行只计相对基线的**新增**失败——否则一条既有红会把
   全部版本误判成「单测不兼容」。基线自身编不过/基建故障时矩阵中止并报因。

   **并行会话隔离跑法**（主工作树有别的会话在改 src/ 时必须用）：拿被测提交开一个干净
   的 detached worktree（`git worktree add --detach <路径> <提交>`），然后 `--repo` 指过去 ——
   gradle 在那边编译测试，报告仍写回本仓 docs/。非隔离跑会被并行会话改到一半的源码污染
   结论（实证过），也别拿矩阵的 clean 去踩别人正在用的 build/。

3. **行为级实测**（矩阵三关都是静态关，行为结论从这里出）：

   ```
   python tools/sable-compat/runclient_with_sable.py 2.0.6       # 起客户端（--print 只看命令）
   ```

   启动后按 [`1.3.0-game-regression-checklist.md`](1.3.0-game-regression-checklist.md) 实测，
   实测结论记回报告的实测栏。客户端启动与实测由人执行。

## 三、报告解读 / reading the report

结论词表（三关任一红即不给「通过」）：

| 结论 | 含义 |
|---|---|
| 链接点断裂 | 我们依赖的某条 Sable 成员在该版本消失/变形。最值得注意的是 `EntitySubLevelRotationHelper.shouldCameraRotate` —— 它是 ControlSeatCameraMixin 的 @Inject 字符串目标，javac 不校验，且该 mixin 配置 `required:false`，目标没了只会在游戏里静默失效 |
| 编译不兼容 | javac 不过：签名漂移实锤（含泛型/异常层面，比链接点更严） |
| 单测不兼容 | 编译过了但无头单测不过（多为 companion 版本漂移、测试双轨构造失效） |
| 基建故障待重跑 | **不是不兼容**：gradle 网络坑（拉不到 Mojang 元数据等）会伪装成构建错，矩阵把这类归到这里，重跑即可 |
| 编译+单测通过（行为级待实测） | 三关全绿。**不等于实测兼容** —— 见下节覆盖边界 |

## 四、覆盖边界 / what the matrix cannot see

矩阵是静态关：成员在不在、javac 过不过、无头单测过不过。以下行为耦合只能 runClient 实测：

- Sable 的骑手踢出 / `retain_in_sub_level` 接管语义（ControlSeatEntity 依赖它）
- plot 网格宿主模型（worldPosition 是网格本地坐标；1.2.2 起中心+本地坐标系的口径）
- 跨类 loader 的 BE 复制（SablePacketHelper 的同名接口双 Class 应对）
- mixin 实际注入效果（探针只证目标方法还在，不证注入成功）
- 无 Sable 环境的优雅缺席（SableAbsenceTest 锚定的是本模组一侧的行为）

因此区间是否放开，以行为级实测为准；矩阵负责先排除「根本链不上/编不过」的版本。

## 五、链接点清单维护 / maintaining linkage-points.json

口径：**新增 Sable 接触面先补清单再补代码**。描述符不要手编，从基线对 dump：

```
python tools/sable-compat/check_linkage.py --dump <类FQN> --dir libs
```

探针自证（基线对必须全绿）：`python tools/sable-compat/check_linkage.py`。

## 六、接口参考源 / API reference sources

查 Sable 接口实现细节时：

- **Sable 2.0.3 源码**（开发期接口参考）：桌面 `sable-main (1)\sable-main\`，多模块
  （common/fabric/neoforge/sable_rapier）。
- **其他版本源码**：GitHub [`ryanhcode/sable`](https://github.com/ryanhcode/sable)（按 tag/提交历史定位版本）。
- 源码**不入库、不构建**：PolyForm Shield 许可（仅授权原作者的使用场景），构建也不需要它 ——
  矩阵吃的是 Modrinth 发布 jar。companion（`Pose3dc`/`Pose3d` 所在）是独立仓库
  `ryanhcode/sable-companion`，各 sable jar 以 jarJar 内嵌固定版本。

## 七、许可 / licensing

`jars/` 整目录不入库（`.gitignore`）：一是体量（14 版数百 MB），二是 PolyForm Shield 的
jar 再分发受限。`fetch_sable_jars.py` 随时可重建整个目录；Modrinth 是上游自己的分发渠道，
我们拉取自用、不转发。`libs/` 里现有的基线对（1.2.2 + companion 1.6.0）维持原状。
