# Contributing / 贡献指南

> 本文是**人类贡献者**的完整入口：环境、构建、测试、规范、PR 流程。
> 本文与 [`CLAUDE.md`](CLAUDE.md) **同步维护**——CLAUDE.md 是 AI 助手（ZCode / Claude Code）自动载入的项目规范全量版；修改任何规范时两边同步。
>
> This document is the complete entry point for **human contributors**: environment, build, testing, conventions and the PR process. It is kept **in sync with [`CLAUDE.md`](CLAUDE.md)**, the full project-conventions file that AI assistants (ZCode / Claude Code) auto-load — update both when a convention changes.

---

## 开发环境 / Development Environment

- **JDK 21**（Gradle toolchain 强制，装好即可，无需手动指向）。/ **JDK 21** (enforced by the Gradle toolchain; just install it).
- **Gradle**：一律用仓库自带 wrapper（`./gradlew` / `gradlew.bat`），不要装全局 Gradle。/ **Gradle**: always use the bundled wrapper (`./gradlew` / `gradlew.bat`), never a global Gradle.
- **依赖**：Minecraft `1.21.1`、NeoForge `21.1.233+`、Create `6.0.10+`（maven: blamejared）；Sable 可选（兼容区间 `[1.2.2,2.1)`）。版本号以 `gradle.properties` 为准。/ **Dependencies**: Minecraft `1.21.1`, NeoForge `21.1.233+`, Create `6.0.10+` (maven: blamejared); Sable optional (compat range `[1.2.2,2.1)`). `gradle.properties` is the source of truth.
- **首次构建需联网**：Gradle 会拉 Mojang 元数据（piston-meta）与依赖。若报 `run type not found` / 元数据下载失败，先查网络（代理断线会**伪装成构建错误**），不要动代码或缓存。/ **First build needs network**: Gradle fetches Mojang metadata (piston-meta) and dependencies. A `run type not found` / metadata-download failure is usually a dropped connection **masquerading as a build error** — check the network first; don't touch code or caches.

## 构建与运行 / Build & Run

- 构建：`./gradlew build`；单测：`./gradlew test`。/ Build: `./gradlew build`; unit tests: `./gradlew test`.
- **客户端：`./gradlew runClient --no-daemon`** —— `--no-daemon` 必带（daemon 模式会在 early-display 字体初始化崩，unionfs NPE；`HeadlessException` 是噪声不是真因）。/ **Client: `./gradlew runClient --no-daemon`** — the flag is mandatory (the daemon dies in early-display font init with a unionfs NPE; a `HeadlessException` is noise over the real cause).
- 服务端：`./gradlew runServer`。/ Server: `./gradlew runServer`.
- 多人联调：`./gradlew runClient -Pusername=Player1` + `./gradlew runClient2 -Pusername2=Player2`。/ Multiplayer testing: `./gradlew runClient -Pusername=Player1` + `./gradlew runClient2 -Pusername2=Player2`.
- **正确关闭**：服务端在控制台 `save-all` → `stop`（headless 用 `rcon-stop.ps1`）；客户端正常关窗口。**切勿强杀 JVM**（`kill -9` / `taskkill /F`）——会跳过存档保存导致数据损坏，那是强杀的预期后果、不是模组 bug。/ **Shutdown correctly**: on a server run `save-all` then `stop` (headless: `rcon-stop.ps1`); close the client window normally. **Never hard-kill the JVM** (`kill -9` / `taskkill /F`) — it skips world saving and corrupts data; that is the expected outcome of a hard kill, not a mod bug.

## 测试 / Testing

- JUnit 5；`./gradlew test` 全量跑（当前约 150 个测试类/文件）。/ JUnit 5; run the full suite with `./gradlew test` (currently ~150 test files).
- **`graph/` 核心逻辑改动应配单元测试**（`src/test/` 按主代码包镜像布局）：FormulaParser、GraphMigration、pinId 解析、OpExecutor 等。/ **Changes to `graph/` core logic come with unit tests** (`src/test/` mirrors the main package layout): FormulaParser, GraphMigration, pinId resolution, OpExecutor and friends.
- **全局静态状态用完必须清理**（`@AfterEach`）——跨类共享的静态表（如 AudioBands）不清会跨测试污染。/ **Global statics must be cleaned up in `@AfterEach`** — shared static tables (e.g. AudioBands) leak state across test classes otherwise.
- 发版前跑实机回归清单：[`docs/1.3.0-game-regression-checklist.md`](docs/1.3.0-game-regression-checklist.md)。/ Before a release, run the in-game regression checklist: [`docs/1.3.0-game-regression-checklist.md`](docs/1.3.0-game-regression-checklist.md).

## 代码规范 / Code Style

- **双语注释**：核心源码（`graph/`、`blocks/`、`network/` 等）的注释保持中文+英文双语（中文为主）。新代码延续此约定。/ **Bilingual comments**: core sources (`graph/`, `blocks/`, `network/`, …) carry Chinese + English comments (Chinese first). New code follows suit.
- **扁平数据结构**：节点共用 `GraphNode` 单类、不建继承体系，用 `NodeType` 枚举区分类型。/ **Flat data structures**: nodes share the single `GraphNode` class — no inheritance hierarchy; discriminate with the `NodeType` enum.
- **服务端权威**：求值只在服务端（`GraphEvaluator`）；客户端只消费 `EvalSnapshot`，不建本地求值器。/ **Server authoritative**: evaluation happens only on the server (`GraphEvaluator`); clients consume `EvalSnapshot` and never build a local evaluator.
- **单一真相源**：FORMULA 引脚解析与求值共用 `node.cachedScript`（`ensureScriptParsed()`），避免双缓存漂移；版本号同理（见「版本管理」）。/ **Single source of truth**: FORMULA pin resolution and evaluation share `node.cachedScript` (`ensureScriptParsed()`) to avoid dual-cache drift; the same goes for the version number (see "Versioning").
- **稳定 pinId**：连线绑定 `fromPinId/toPinId`（v1.2.4+），不要回退到纯整数索引绑定。/ **Stable pinIds**: wires bind `fromPinId/toPinId` (since v1.2.4) — never regress to plain integer-index binding.

### 权威状态与数据面 / Authoritative State & Data Plane

- **单一权威**：跨端共享的状态（图数据、频道/频段定义、冲突标志）只在持有权威的一侧计算（本仓 = **服务端**）。客户端可以补足它**本地能证明**的部分，但**绝不下调它证明不了的部分**。/ **Single authority**: cross-side shared state (graph data, channel/band definitions, conflict flags) is computed only where the authority lives (here: the **server**). A client may fill in what it can **locally prove**, but must never downgrade what it cannot prove.
- **最小数据面**：权威值变化时只发**与该变化等量**的数据（节点级 op / 针对性数据包），不要用整图 NBT 推送代替——那会冲掉正在进行的编辑。/ **Minimal data plane**: when an authoritative value changes, send exactly that change (node-level ops / targeted packets), never a whole-graph NBT push — that clobbers edits in flight.
- **不本端重算权威值**：本端推导只允许作为一次性初始化或可证明的补充，不得成为每 tick / 每次收包后的常规路径。/ **Never recompute authoritative values locally**: local derivation is allowed only as one-shot initialization or provable enrichment — never as the per-tick / per-packet routine.
- **权威值必须被持续维护**：定义变化后**所有**持有该值的实体都要被更新到；自愈式不变量优于一次性事件推送。/ **Authoritative values must stay maintained**: after a definition change, **every** holder gets updated; self-healing invariants beat one-shot event pushes.
- **动手前三问**：这个值由谁权威决定？我发出去的数据是否刚好等于这个变化？我本端算的东西会不会覆盖别人算的？/ **Three questions before you code**: who is authoritative for this value? does my payload equal exactly this change? can my local computation overwrite someone else's?

### 方块数据保留 / Block Data Retention

- **扳手收回保存 NBT 走模组自有通道**：持久化用户数据的方块覆写 `IWrenchable#onSneakWrenched`，对 BlockItem 掉落物调 `be.saveToItem(stack, registryAccess)`（`AmplifierComputerBlock` 模板）。不走 Create 的 `copy_components` 战利品表路线。/ **Wrench pickup preserves NBT through the mod's own channel**: blocks with persistent user data override `IWrenchable#onSneakWrenched` and call `be.saveToItem(stack, registryAccess)` on the BlockItem drop (`AmplifierComputerBlock` is the template). Do not use Create's `copy_components` loot-table route.
- **新方块自查两处**：战利品表（掉落本体）+ `onSneakWrenched` 覆写（NBT 随物品），缺任一即丢数据。/ **New blocks check two places**: the loot table (the drop itself) + the `onSneakWrenched` override (NBT rides the item) — miss either and data is lost.
- **双路径是设计意图**：扳手（潜行+右键）= 搬迁、NBT 随物品；镐挖/爆炸 = 拆除、掉纯物品。后者不是 bug。/ **The dual path is by design**: wrench (sneak + right-click) = relocation with NBT; pickaxe/explosion = demolition dropping plain items. The latter is not a bug.

## 提交规范 / Commit Convention

- Conventional Commits 风格，**英文 subject**：`fix:` / `feat:` / `docs:` / `test:` / `refactor:` / `revert:` / `test+fix:`；需要时可加 `vX.Y.Z: ...` 前缀。/ Conventional Commits with **English subjects**: `fix:` / `feat:` / `docs:` / `test:` / `refactor:` / `revert:` / `test+fix:`; an optional `vX.Y.Z: ...` prefix.
- 一次提交聚焦一件事；**文档与代码改动分开提交**。/ One concern per commit; **docs and code changes go in separate commits**.
- **禁用 `git add -A`**：逐文件 / 逐路径 add——防 CRLF 化混入源码锚文件、防未跟踪下载物误入库（`tools/sable-compat/jars/` 有 .gitignore 挡着，但下载物类目录一律别扫）。/ **Never `git add -A`**: add file by file / path by path — keeps CRLF conversions out of source-anchor files and untracked downloads (`tools/sable-compat/jars/` is gitignored, but never sweep download directories) out of the repo.

## 文档规范 / Docs Convention

- **`CHANGELOG.md` 是权威变更日志**：每个版本一个 `<details>` 块，发布前更新；README 的 Changelog 版本表**每版补一行**（它同时是 Modrinth 门面）。/ **`CHANGELOG.md` is the authoritative changelog**: one `<details>` block per version, updated before release; the README changelog table **gains one row per version** (it doubles as the Modrinth front page).
- **已发布版本的条目不改写**：发现已发布版本的 bug，修完写进**当前 WIP 版本板块**，不改旧版 `details` 块。/ **Never rewrite released entries**: a bug in a shipped version gets its fix recorded in the **current WIP section**, not by editing an old `details` block.
- `docs/` 规划/分析/计划类文档**以中文为主**（中文 token 高密度；2026-11-08 约定），带状态横幅（✅ 已解决 / 🔶 待办）与交叉引用；架构改动同步 [`docs/code-architecture.md`](docs/code-architecture.md)；面向用户的文档（README/CONTRIBUTING/runbook）与 API javadoc 保持双语。/ `docs/` planning/analysis/plan documents stay **Chinese-first** (token density), with status banners (✅ resolved / 🔶 todo) and cross-references; architecture changes update [`docs/code-architecture.md`](docs/code-architecture.md); user-facing docs (README/CONTRIBUTING/runbook) and API javadoc remain bilingual.
- AI 工作文档（会话 handoff、`.zcode/` 配置等）**不入仓库**。/ AI working documents (session handoffs, `.zcode/` config, …) **stay out of the repository**.

## 版本管理 / Versioning

- 升版本同步**两处**：`gradle.properties` 的 `mod_version`（**单一真相源**，`build.gradle` 的 `version = mod_version` 自动派生、勿手写双开）+ `CHANGELOG.md`（README 版本表同步补行）。/ Bump **two places**: `gradle.properties`' `mod_version` (**single source of truth** — `build.gradle` derives `version = mod_version`, never hand-write both) + `CHANGELOG.md` (and the README version table).
- NBT 数据格式变更：提升 `NbtVersions.DATA_VERSION`，在 `GraphMigration` 添加迁移步骤并保持向后兼容。/ NBT format changes: bump `NbtVersions.DATA_VERSION`, add a `GraphMigration` step, and keep it backward compatible.

## PR 流程 / Pull Request Process

- 从 `main` 开分支；长任务 / 探索线建议独立 worktree（`git worktree add`），主工作树可能是并发会话的公共施工面。/ Branch from `main`; long tasks / exploration lines get their own worktree (`git worktree add`) — the main worktree may be a shared construction surface for concurrent sessions.
- CI（`.github/workflows/pr-test.yml`）在 PR 上跑单测；本地先 `./gradlew test` 绿再推。/ CI (`.github/workflows/pr-test.yml`) runs unit tests on PRs; go green locally with `./gradlew test` first.
- 用 [GitHub Issues](https://github.com/y15173334444/create-schematic-compute/issues) 提 bug / 需求，triage 标签见 [`docs/agents/triage-labels.md`](docs/agents/triage-labels.md)（`needs-triage`、`needs-info`、`ready-for-agent`、`ready-for-human`、`wontfix`）。/ File bugs / requests as [GitHub Issues](https://github.com/y15173334444/create-schematic-compute/issues); triage labels are listed in [`docs/agents/triage-labels.md`](docs/agents/triage-labels.md).
- PR 描述写清：动机、行为变化、测试方式（含实机验证情况）。/ PR descriptions state the motivation, behaviour changes and how it was tested (including any in-game verification).

## 发布 / Release

发布是维护者动作，流程见 [`docs/release-runbook.md`](docs/release-runbook.md)（发版前检查 → 构建 → tag → GitHub Release → Modrinth）。/ Releasing is a maintainer action — the runbook lives at [`docs/release-runbook.md`](docs/release-runbook.md) (pre-flight → build → tag → GitHub Release → Modrinth).

## AI 协作 / AI Assistance

- 本仓库的 AI 助手项目规范在 [`CLAUDE.md`](CLAUDE.md)（ZCode / Claude Code 打开仓库时自动载入）。规范类改动**两边同步**；AI 行为纪律（grilling 触发、后台执行、杀进程核实等）以 CLAUDE.md 为准。/ AI assistant conventions live in [`CLAUDE.md`](CLAUDE.md) (auto-loaded by ZCode / Claude Code). Convention changes are applied **to both files**; AI behaviour discipline (grilling triggers, background execution, kill-process checks, …) is authoritative in CLAUDE.md.
