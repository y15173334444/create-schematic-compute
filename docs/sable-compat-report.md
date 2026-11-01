# Sable 版本兼容性矩阵报告

- 日期 / date：2026-11-01；被测提交 / commit：`cf20128`
- 被测 checkout / tested checkout：`C:\Users\Administrator\csc-sable-compat-wt`
- 版本来源 / versions：Modrinth `sable` 项目 NeoForge 线 + MC 1.21.1 全量（`tools/sable-compat/fetch_sable_jars.py`）
- 基线对 / baseline pair：`libs/sable-neoforge-1.21.1-1.2.2.jar` + `libs/sable-companion-common-1.21.1-1.6.0.jar`（mods.toml 区间的实测锚；区间值以 gradle.properties 的 sable_version_range 为准）
- 口径 / semantics：每个版本全量重编（clean 打头）；companion 取该 sable jar 内嵌 jarJar 的配套版本。

## 矩阵 / matrix

| Sable 版本 | 发布日 | 链接点 | 编译 | 单测 | 结论 |
|---|---|---|---|---|---|
| 1.0.4+mc1.21.1 | 2026-04-18 | 30/30 | 通过 | 通过（1098 用例） | 编译+单测通过（行为级待实测） |
| 1.0.5+mc1.21.1 | 2026-04-18 | 30/30 | 通过 | 通过（1098 用例；初跑计时 flake，见复核注记） | 编译+单测通过（行为级待实测） |
| 1.0.6+mc1.21.1 | 2026-04-19 | 30/30 | 通过 | 通过（1098 用例；初跑计时 flake，见复核注记） | 编译+单测通过（行为级待实测） |
| 1.1.1+mc1.21.1 | 2026-04-22 | 30/30 | 通过（初跑基建故障，见复核注记） | 通过（1098 用例） | 编译+单测通过（行为级待实测） |
| 1.1.3+mc1.21.1 | 2026-04-22 | 30/30 | 通过 | 通过（1098 用例） | 编译+单测通过（行为级待实测） |
| 1.2.1+mc1.21.1 | 2026-04-28 | 30/30 | 通过 | 通过（1098 用例） | 编译+单测通过（行为级待实测） |
| 1.2.2+mc1.21.1 | 2026-04-28 | 30/30 | 通过 | 通过（1098 用例） | 编译+单测通过（行为级待实测） |
| 2.0.0+mc1.21.1 | 2026-06-13 | 30/30 | 通过 | 通过（1098 用例） | 编译+单测通过（行为级待实测） |
| 2.0.1+mc1.21.1 | 2026-06-13 | 30/30 | 通过 | 通过（1098 用例） | 编译+单测通过（行为级待实测） |
| 2.0.2+mc1.21.1 | 2026-06-15 | 30/30 | 通过 | 通过（1098 用例） | 编译+单测通过（行为级待实测） |
| 2.0.3+mc1.21.1 | 2026-06-17 | 30/30 | 通过 | 通过（1098 用例） | 编译+单测通过（行为级待实测） |
| 2.0.4+mc1.21.1 | 2026-08-17 | 30/30 | 通过 | 通过（1098 用例） | 编译+单测通过（行为级待实测） |
| 2.0.5+mc1.21.1 | 2026-08-18 | 30/30 | 通过 | 通过（1098 用例） | 编译+单测通过（行为级待实测） |
| 2.0.6+mc1.21.1 | 2026-10-03 | 30/30 | 通过 | 通过（1098 用例） | 编译+单测通过（行为级待实测） |

链接点清单共 30 条（`tools/sable-compat/linkage-points.json`），
含 ControlSeatCameraMixin 的字符串目标 `EntitySubLevelRotationHelper.shouldCameraRotate` ——
它经 mixin 反射链接，编译查不到，只有探针能查。

## 基线 / baseline（被测提交自带的红）

被测提交基线全绿。

## 复核注记 / re-verification notes

首轮矩阵有三处异常，逐一复核后全部归因**环境噪声**，无一例是版本差异：

- **1.0.5 / 1.0.6 初跑单测红**——同一条 `AudioMixerShapingPerfTest` 计时断言
  （shaped render 28.7 / 28.3 ms 每 80 ms 块，超 35% 预算）。该测试只引用本模组自己的
  `AudioCurve`，**不触及任何 Sable 类**，版本归因不成立；初跑时并发会话同机满载跑测试，
  属机器负载敏感的 perf 计时 flake。复跑两版均 1098 全绿。
  / a wall-clock perf assertion on our own AudioCurve (no Sable import at all); flaked under
  concurrent CPU load, both re-runs green.
- **1.1.1 初跑编译基建故障**——Gradle daemon 被「JVM garbage collector is thrashing」
  杀掉（双会话内存压力）。复跑仍撞瞬时基建噪声后，手跑 `compileJava compileTestJava`
  与 `test` 均一次通过（1098 用例）。
  / the gradle daemon was GC-thrash-killed under memory pressure; a manual compile+test
  pass completed the evidence.

方法论提醒：矩阵的三关断言里有 wall-clock 计时类（perf 测试），高负载并行环境下会 flake；
解读报告时失败项先看「测试本身碰不碰 Sable」，再看是否负载/基建噪声。

## 覆盖边界 / what this matrix does NOT cover

三关都是**静态关**：成员在不在、javac 过不过、无头单测过不过。以下行为耦合只能 runClient 实测
（`tools/sable-compat/runclient_with_sable.py <版本>` + `docs/1.3.0-game-regression-checklist.md`）：

- Sable 的骑手踢出 / `retain_in_sub_level` 行为（ControlSeatEntity 依赖其接管语义）
- plot 网格宿主模型（worldPosition 是网格本地坐标、1.2.2 起中心+本地坐标系的口径）
- 跨类 loader 的 BE 复制（SablePacketHelper 的同名接口双 Class 应对）
- mixin 实际注入效果（探针只证目标方法还在，不证注入成功）
- 无 Sable 环境的优雅缺席（SableAbsenceTest 锚定的是本模组一侧）

因此「编译+单测通过」≠「实测兼容」；区间是否放开以行为级实测为准。

---

操作手册见 `docs/sable-compat-testing.md`。
