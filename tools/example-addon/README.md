# CSC Example Addon（公开 API 示例模组）

Create: Schematic Compute 公开 API 的第三方示例模组 —— **完整接入链的参考载体**：
第三方方块 ↔ 自定义节点 ↔ 图计算 ↔ 输出，闭环可跑。

A third-party example addon for Create: Schematic Compute's public API — the reference
carrier for the full integration chain: third-party block ↔ custom node ↔ graph ↔ output, a runnable
loop.

## 演示内容 / What it demonstrates

| 节点 | 演示点 |
|---|---|
| `cscexample:signal` | **提供方直连**：求值回调里经 `ctx.level()` 读 `SignalProviderBlockEntity` 的信号值（TEXT 参数 `pos` = `"x y z"` 绝对坐标）；「参数即寻址」约定的最小实现 |
| `cscexample:heavy` | **可恢复重节点**：伪工作循环在边界检查 `ctx.sliceExpired()`，进度打包进检查点跨 tick 续算；把 `iterations` 调大到几百万即可用 `/cscperf` 观察耗时与挂起轮转 |

两者都走完整接入链：`CustomNodes.register(schema, behavior)` →
`CustomNodes.admitToHost(nodeId, CustomNodes.HOST_BLUEPRINT)`（**注册 ≠ 准入**：注册登记节点类型，还需显式登记进目标宿主的准入名单）。

## 构建 / Build

本工程住在 CSC 仓库内（`tools/example-addon/`），构建时对主工程的产物 jar 编译：

```bash
# ① 先出主工程 jar（仓库根）
../../gradlew jar

# ② 构建示例模组
./gradlew -p tools/example-addon build
# 产物：tools/example-addon/build/libs/csc-example-addon-1.0.0.jar
```

第三方移植本示例时：把 `build.gradle` 里 `cscJar` 指向自己的 CSC jar 所在处
（`compileOnly` 即可——运行环境里由已安装的 CSC 提供 api）。

## 实机闭环 / In-game loop

1. 放置蓝图机与 **Example Signal Provider**（外观复用原版铁块占位，右键信号 0→5→10→15 循环）。
2. 蓝图机图内添加 **Example: Signal Read** 节点，TEXT 参数 `pos` 填提供方块坐标
   （如 `12 -58 -204`）；节点输出连 `REDSTONE_OUT` 或任意数值面。
3. 添加 **Example: Heavy (Resumable)**，`iterations` 调大，图运行后用
   `/cscperf`（看向蓝图机）观察其逐 tick 轮转推进与耗时。
4. 移除 CSC 后重进：节点降级为占位壳、图不损，提供方回归后自动恢复形状。
