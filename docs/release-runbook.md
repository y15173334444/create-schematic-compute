# 发布 Runbook / Release Runbook

> 状态 / Status：✅ 现行流程（实证于 v1.2.6.1 `5178fd9`、v1.2.6.2 `ef40756` 两次发布）。
> 范围 / Scope：**维护者**发版动作的全手动流程——仓库无 release workflow（仅 `pr-test.yml` 供 PR 跑单测）；Modrinth 侧手动上传。
>
> The full manual release process for **maintainers** — there is no release workflow in the repo (only `pr-test.yml` for PR unit tests); the Modrinth side is uploaded by hand. Verified against the v1.2.6.1 and v1.2.6.2 releases.

---

## 0. 安全红线 / Security rules

- **令牌（Modrinth PAT / GitHub token）永不写入仓库、记忆、持久配置或聊天记录**：需要 API 发布时经环境变量临时传递，用完**立即建议用户撤销换新**。/ **Tokens (Modrinth PAT / GitHub token) never enter the repository, memory, persistent config or chat logs**: pass them via environment variables when an API release is attempted, then **immediately recommend revoking and rotating**.
- 发布正文只引用 CHANGELOG 里的公开内容，不带内部审查记录、AI 会话文档路径。/ Release notes quote only public CHANGELOG content — no internal review records, no AI session document paths.

## 1. 发版前检查 / Pre-flight

1. **版本号两处一致**：`gradle.properties` 的 `mod_version`（单一真相源）+ `CHANGELOG.md` 对应板块；`build.gradle` 的 `version = mod_version` 自动派生无需改。/ **Version in two places agree**: `gradle.properties`' `mod_version` (single source of truth) + the matching `CHANGELOG.md` section; `build.gradle` derives `version = mod_version` automatically.
2. **CHANGELOG 覆盖完整**：`<上一版本 tag>..HEAD` 的全部提交都被目标版本的 `<details>` 块摘要行覆盖；**已发布版本的 `details` 块不改写**（已发布 bug 的修复记进当前版本板块）。/ **CHANGELOG covers everything**: every commit in `<previous tag>..HEAD` is summarised in the target version's `<details>` block; **released blocks are never rewritten** (fixes for shipped bugs land in the current version's section).
3. **README 版本表补行**：Changelog 表一行/版本（它同时是 Modrinth 门面）。/ **README version table gains its row** (it doubles as the Modrinth front page).
4. **lang 键对账**：`zh_cn.json` / `en_us.json` 键数一致、无缺键。/ **lang keys reconcile**: `zh_cn.json` / `en_us.json` have identical key sets.
5. **测试与回归**：`./gradlew test` 全绿 + 实机回归清单（`docs/1.3.0-game-regression-checklist.md`）跑完勾选。/ **Tests & regression**: `./gradlew test` green + the in-game regression checklist (`docs/1.3.0-game-regression-checklist.md`) checked off.
6. **无未合 PR / 无关改动混入**；jar 资源无第三方 ARR 素材（NBS 采样等许可红线）。/ **No stray PRs / unrelated changes**; the jar carries no third-party ARR assets (NBS samples etc. are licence red lines).

## 2. 构建 / Build

```bash
./gradlew build
# 产物 / artifact: build/libs/create-schematic-compute-<version>.jar
```

对产物 jar 记 SHA-256（发布正文用）。/ Record the artifact's SHA-256 (it goes into the release notes).

## 3. 打 tag / Tag

```bash
git tag v<X.Y.Z>            # tag 打在发布提交上 / tag the release commit
git push origin main v<X.Y.Z>
```

## 4. GitHub Release / GitHub Release

```bash
gh release create v<X.Y.Z> \
  --title "Create: Schematic Compute <X.Y.Z>" \
  build/libs/create-schematic-compute-<X.Y.Z>.jar
```

- **正文 = 双语五段式摘要**（与 v1.2.6.1/v1.2.6.2 同构）：Fixes 列表 + Behaviour changes（如有）+ Requirements（MC/NeoForge/Create 版本）+ Install + SHA-256。素材取自 CHANGELOG 该版本块的摘要行。/ **Body = the bilingual five-part summary** (same shape as v1.2.6.1/v1.2.6.2): Fixes + Behaviour changes (if any) + Requirements (MC/NeoForge/Create versions) + Install + SHA-256. Source the wording from the CHANGELOG block's summary rows.

## 5. Modrinth（手动）/ Modrinth (manual)

1. Modrinth 版本页手动**上传 jar**。/ Upload the jar by hand on the Modrinth version page.
2. **依赖声明**：Minecraft `1.21.1` / NeoForge `21.1.233+` / Create `6.0.10+`；Sable 可选。/ **Dependencies**: Minecraft `1.21.1` / NeoForge `21.1.233+` / Create `6.0.10+`; Sable optional.
3. **正文同步 README**（MR 页与 README 共用门面口径）；发布正文可另存桌面文档供复制，**不入仓库**。/ **Body mirrors the README** (the MR page shares the README's front-page voice); a desktop copy-paste document is fine but **stays out of the repository**.

## 6. 发布后 / After

- 确认 GitHub release 附件、Modrinth 版本页、README 表、CHANGELOG 链接锚全部就位。/ Confirm the GitHub release asset, the Modrinth version page, the README table row and the CHANGELOG anchor links.
- 若要走 API 自动化发布：令牌经环境变量临时传递，发布完成后**撤销换新**（见 §0）。/ For an API-driven release: pass the token via a temporary environment variable and **revoke + rotate** afterwards (see §0).

---

## 附：历史案例 / Appendix: past cases

- **v1.2.6.1**（tag `5178fd9`）：发版前体检发现 CHANGELOG 索引行缺 3 小节摘要 + README 表缺行——**发版前检查第 2、3 条正是为此设的**。/ The pre-flight caught a missing CHANGELOG index summary and a missing README row — checks 2 and 3 exist because of this.
- **v1.2.6.2**（tag `e437c4a`，合入 `ef40756`）：hotfix 整线合入 main 后，1.3.0 WIP 表中已随 hotfix 发布的四行**撤下**（1.3.0 发版不得重复计数）；合并顺手修复了一处行号 sed 误删的孤儿表。/ After the hotfix line merged into main, four rows already shipped with the hotfix were **removed** from the 1.3.0 WIP table (a 1.3.0 release must not double-count them); the merge also repaired an orphan table left by a line-number sed mishap.
