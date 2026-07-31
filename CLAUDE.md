## Agent skills

### Issue tracker

GitHub Issues，外部 PR 也作为 triage 需求来源。见 `docs/agents/issue-tracker.md`。

### Triage labels

使用默认的五个 triage 标签名：`needs-triage`、`needs-info`、`ready-for-agent`、`ready-for-human`、`wontfix`。见 `docs/agents/triage-labels.md`。

### Domain docs

单上下文布局 — 根目录下的 `CONTEXT.md` + `docs/adr/`。见 `docs/agents/domain.md`。

## 项目规范 / Project Conventions

> 从现有代码与提交历史提炼的约定。改动代码 / 提交 / 文档时遵循以下规范。

### 代码规范 / Code Style
- **双语注释**：`graph/`、`blocks/`、`network/` 等核心源码的注释保持中文+英文双语（中文为主）。新增代码必须延续此约定。
- **扁平数据结构**：节点共用 `GraphNode` 单类、不建继承体系，用 `NodeType` 枚举区分类型。
- **服务端权威**：求值只在服务端（`GraphEvaluator`）；客户端只消费 `EvalSnapshot`，不建本地求值器。
- **单一真相源**：FORMULA 引脚解析与求值共用 `node.cachedScript`（`ensureScriptParsed()`），避免双缓存漂移。
- **稳定 pinId**：连线绑定 `fromPinId/toPinId`（v1.2.4+），不要回退到纯整数索引绑定。

### 提交规范 / Commit Convention
- Conventional Commits 风格，英文 subject：`fix:` / `feat:` / `docs:` / `test:` / `refactor:` / `revert:` / `test+fix:`。
- 需要版本标注时可加 `vX.Y.Z: ...` 前缀。
- 一次提交聚焦一件事；文档与代码改动分开提交。

### 文档规范 / Docs Convention
- README 是**权威变更日志**：每个版本一个 `<details>` 块，发布前更新。
- `docs/` 规划/分析文档双语，带状态横幅（✅ 已解决 / 🔶 待办）与交叉引用。
- 架构文档见 `docs/code-architecture.md`；改动架构时同步更新。

### 版本管理 / Versioning
- 升版本需同步三处：`gradle.properties` 的 `mod_version` + `build.gradle` 的 `version` + README changelog。
- NBT 数据格式变更：提升 `NbtVersions.DATA_VERSION`，在 `GraphMigration` 添加迁移步骤并保持向后兼容。

### 测试规范 / Testing
- JUnit 5，运行 `./gradlew test`。
- `graph/` 核心逻辑（FormulaParser、GraphMigration、pinId 解析、OpExecutor）改动应配单元测试（`src/test/`）。

### 构建与运行 / Build & Run
- 客户端 `./gradlew runClient`，服务端 `./gradlew runServer`。
- 多人联调：`./gradlew runClient -Pusername=Player1` + `./gradlew runClient2 -Pusername2=Player2`。

### 正确关闭服务端 / 客户端 / Graceful Shutdown
> **切勿强杀**运行中的服务端/客户端 JVM（`kill -9`、`taskkill /F`、任务管理器「结束任务」）。强杀会跳过 Minecraft 的存档保存（`level.dat`、节点图 NBT 等），导致数据损坏——这是强杀的**预期后果**，**不是模组 bug**。排查 bug 前先确认损坏是否由强杀引起。

- **服务端（`runServer`）**：在控制台输入 `save-all`（先刷盘）→ `stop` 优雅退出（`stop` 本身也会先保存）。后台/无人值守运行时，通过 stdin 发送 `stop\n`，或用 tmux/screen 向窗口发送按键。等待日志出现 `Stopping server` → `Server stopped` 后再进行其他操作。
- **Headless 服务端（无窗口、stdin 为管道，如后台 `runServer`）**：控制台命令与 `taskkill`（无 `/F`）均不可达，**必须用 RCON**。已配置：`runs/server/server.properties` 中 `enable-rcon=true`、`rcon.port=25575`、`server-ip=127.0.0.1`（仅本机可连）。**重启服务端后生效**，之后运行 `powershell -ExecutionPolicy Bypass -File rcon-stop.ps1` 即可发送 `save-all` + `stop` 优雅关闭。
- **客户端（`runClient`）**：正常关闭游戏窗口（ESC → 退出 / 关闭窗口），Minecraft 会自动保存。不要强杀窗口进程。
- **Windows 环境**：确需终止进程时优先 `taskkill /PID <pid>`（发送窗口消息的优雅退出），**不要加 `/F`**。注意 `./gradlew --stop` 只停 Gradle daemon，不会保存游戏。
- **损坏判定标准**：只有**优雅停止后仍出现**的存档损坏 / 数据异常才可作为潜在 bug 上报；强杀后的损坏先恢复备份或重新生成世界再验证。

## 追问规则

当我提出任何方案、设计、架构决策、或实现方案——任何涉及在动手前需要在多个选择之间做取舍的情况——**主动调用 `grilling` 技能**进行压力测试。触发场景包括但不限于：
- "我要做 X"
- "我们应该用 Y"
- "方案是 Z"
- 任何架构或设计提案
- 任何非平凡的实现决策
- "A 还是 B？"

追问引擎会逐个质询设计的每个分支，在写代码之前解决决策依赖。能在代码库里验证的问题先查代码库，剩下的再追问我。
