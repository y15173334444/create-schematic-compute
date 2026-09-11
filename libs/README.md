# libs/ — 编译期与测试期依赖 / Compile- and Test-Time Dependencies

本目录的 jar 由 `.gitignore` 的 `!libs/*.jar` 显式保留在版本控制中，用于本地编译与测试。

The jars in this directory are deliberately kept in version control (`.gitignore`, `!libs/*.jar`) for local compilation and testing.

**许可与义务的完整分析见 [`docs/third-party-licenses.md`](../docs/third-party-licenses.md)。**

**For the full license and obligation analysis see [`docs/third-party-licenses.md`](../docs/third-party-licenses.md).**

> **2026-09-12 变更 / Change**
>
> Create 与 Create Aeronautics 已从此目录移除：它们的 `assets/**` 是 All Rights Reserved，而本仓库是公开的。Create 改由 Modrinth Maven 拉取；Create Aeronautics 直接删除（源码零引用）。`catnip-only.jar` 也已删除 —— `ponder` 已 shade 全部 catnip 类，而这个 stub 只在 `libs/` 里存在过，玩家安装里从来没有它。
>
> Create and Create Aeronautics were removed from this directory: their `assets/**` are All Rights Reserved and this repository is public. Create now comes from Modrinth Maven; Create Aeronautics was deleted outright (zero source references). `catnip-only.jar` is gone too — `ponder` already shades the catnip classes, and the stub only ever existed in `libs/`, never in a player's install.

---

## 清单 / Inventory

| jar | 许可 / License | 版权 / Copyright | 编译是否需要 / Needed to compile |
| --- | --- | --- | --- |
| `sable-neoforge-1.21.1-1.2.2.jar` | **PolyForm Shield 1.0.0** + natives Apache-2.0 + 内嵌 **Veil (LGPLv3)** / embedded **Veil (LGPLv3)** | RyanHCode / dimforge (Rapier) / amo, Cappin, Ocelot (Veil) | ✅ 必需 / required |
| `flywheel-neoforge-1.21.1-1.0.6.jar` | MIT（纯 MIT，无 assets 保留条款） / MIT (plain, no asset carve-out) | (c) 2021-2024 Jozufozu | ✅ 必需 / required（javac 看不到 Create 内嵌的那份 / javac cannot see Create's nested copy） |
| `ponder-neoforge-1.0.85+mc1.21.1.jar` | MIT | (c) 2022 The Create Team | ✅ 必需 / required（它 shade 了 catnip，源码用的 4 个 catnip 类由它提供 / it shades catnip and supplies all four catnip classes the source uses） |
| `sable-companion-common-1.21.1-1.6.0.jar` | MIT | (c) 2026 RyanHCode | ✅ 必需：与 Sable 内嵌副本同哈希，但 javac 看不到嵌套 jar，且我们调用的 Sable API 签名里带 companion 类型 / required: same hash as Sable's embedded copy, but javac cannot see nested jars and the Sable API called here returns companion types |

Create 不在这里了 —— 见 `build.gradle` 的 `maven.modrinth:create`（坐标版本在 `gradle.properties` 的 `create_maven_version`）。

Create is no longer here — see `maven.modrinth:create` in `build.gradle` (version in `gradle.properties` → `create_maven_version`).

---

## 递归看一层：宿主 jar 内嵌了什么 / One Level Down: What the Host Jars Embed

**一个 jar ≠ 一份许可。** `sable` 用 JIJ 内嵌了 **Veil（LGPLv3）** 与 sable-companion；Create 的 jar（现在从 Modrinth 拉取）内嵌了 flywheel / ponder / Registrate。

**One jar ≠ one license.** `sable` embeds **Veil (LGPLv3)** and sable-companion via jar-in-jar; Create's jar (now pulled from Modrinth) embeds flywheel / ponder / Registrate.

请勿重打包或裁剪这些 jar（见下方硬规则 1）。完整清单见 [`docs/third-party-licenses.md`](../docs/third-party-licenses.md) §2.8。

Do not repackage or prune these jars (hard rule 1 below). Full list in [`docs/third-party-licenses.md`](../docs/third-party-licenses.md) §2.8.

---

## 三条硬规则 / Three Hard Rules

**1. 不要重打包任何 jar。 / Do not repackage any jar.**

`sable-neoforge` 内的 `natives/sable_rapier/LICENSE-RAPIER`（Apache-2.0）与 `README.md`（修改声明）必须原样随分发传递。裁剪或重打包会破坏 Apache-2.0 §4(a)(b) 的合规性。

`natives/sable_rapier/LICENSE-RAPIER` (Apache-2.0) and `README.md` (the modification notice) inside `sable-neoforge` must travel with any distribution, unmodified. Pruning or repackaging breaks Apache-2.0 §4(a)(b).

更重的一条：Sable 的 jar 内嵌 **Veil（LGPLv3）**，LGPL §4 要求接收方能够替换/修改该库 —— 裁掉嵌套 jar 就从"漏一份通知"变成"实质违反 LGPL"。

This one weighs more: Sable's jar embeds **Veil (LGPLv3)**, and LGPL §4 requires that recipients stay able to replace or modify that library — dropping the nested jar turns "a missing notice" into "a substantive LGPL violation."

**2. 不要把这些 jar 并入以 MIT 发布的产物。 / Do not merge these jars into anything released under MIT.**

PolyForm Shield 的 "No Other Rights" 禁止 sublicense / transfer，`libs/` 只能作为构建依赖（`compileOnly` / `runtimeOnly`）。

PolyForm Shield's "No Other Rights" forbids sublicensing and transfer, so `libs/` may only serve as build dependencies (`compileOnly` / `runtimeOnly`).

**3. 不要再把带 ARR 资产的 jar 提交进来。 / Never commit ARR-bearing jars again.**

Create / Create Aeronautics 的 `assets/**` 是 All Rights Reserved —— 这正是 2026-09-12 把它们移出本目录的原因。同样地，仓库根目录的 `assets/`（本地参考贴图）与 `texture_reference/` 由 `.gitignore:20` / `:34` 挡住，**不要**加 `!` 例外。需要新的第三方依赖时，优先找版权方自己的 Maven（如 Modrinth Maven），而不是把 jar 拷进来。

Create / Create Aeronautics `assets/**` are All Rights Reserved — that is precisely why they were removed from this directory on 2026-09-12. Likewise, the repo-root `assets/` (local reference textures) and `texture_reference/` are blocked by `.gitignore:20` / `:34`; **do not** add `!` exceptions. When you need a new third-party dependency, prefer the authors' own Maven channel (such as Modrinth Maven) over vendoring the jar.

---

## 已完成的迁移 / Migration Done (2026-09-12)

```gradle
// 原 libs/create-1.21.1-6.0.10.jar → compileOnly/runtimeOnly "maven.modrinth:create:6.0.10+mc1.21.1"
// was libs/create-1.21.1-6.0.10.jar   → compileOnly/runtimeOnly "maven.modrinth:create:6.0.10+mc1.21.1"
```

落实在 `build.gradle` 的 `exclusiveContent { ... 'https://api.modrinth.com/maven' }` 与 `def createDep = "maven.modrinth:create:${create_maven_version}"`。

Implemented in `build.gradle` via `exclusiveContent { ... 'https://api.modrinth.com/maven' }` and `def createDep = "maven.modrinth:create:${create_maven_version}"`.

**行为等价性已验证**：Modrinth 那份与原先 `libs/` 里的 **SHA256 完全相同**（`EF87FE57…E37A`，19,123,767 B），所以 dev 运行行为不变。

**Behaviour equivalence verified**: the Modrinth copy and the jar that used to sit in `libs/` have an **identical SHA256** (`EF87FE57…E37A`, 19,123,767 B), so dev runtime behaviour is unchanged.

**一个必须记住的坑**：Modrinth 生成的 POM **不含 `<dependencies>`**（实测 475 B），所以 Flywheel / Ponder **不会**被传递进来 —— 它们必须继续留在 `libs/`，否则编译期看不到 `dev.engine_room.flywheel.*` 与 `net.createmod.catnip.*`。

**One trap to remember**: Modrinth's generated POM declares **no `<dependencies>`** (measured: 475 B), so Flywheel / Ponder are **not** pulled in transitively — they must stay in `libs/`, otherwise the compile classpath cannot see `dev.engine_room.flywheel.*` or `net.createmod.catnip.*`.

**残留事项**：两个 jar 的 blob 仍在 git 历史里（`git rev-list --objects --all` 可查到）。本次迁移让 HEAD 与后续 clone 不再携带它们；历史未重写（仓库文档大量引用 SHA，代价高于收益）。

**Residual**: both jars' blobs still live in git history (`git rev-list --objects --all` finds them). This migration stops HEAD and future clones from carrying them; history was **not** rewritten (repo docs cite many SHAs, so the cost outweighs the benefit).

---

## 通知 / Notices

```text
Third-Party Notices / 第三方声明

Sable is licensed under the PolyForm Shield License 1.0.0:
https://polyformproject.org/licenses/shield/1.0.0
Its bundled Rapier natives are a modified version of
https://github.com/ryanhcode/rapier, under Apache License 2.0
(see natives/sable_rapier/LICENSE-RAPIER inside the jar).
Sable also bundles Veil (modId "veil", authors amo, Cappin, Ocelot),
licensed under the GNU Lesser General Public License v3
(see META-INF/jarjar/veil-neoforge-1.21.1-4.0.0.jar inside the jar).

Create, Flywheel, Ponder and Catnip code is MIT licensed. Create is consumed
from Modrinth Maven and is not redistributed by this project; the All Rights
Reserved assets inside its jar are not redistributed either.

--- 中文版 ---

Sable 以 PolyForm Shield License 1.0.0 授权：
https://polyformproject.org/licenses/shield/1.0.0
它内嵌的 Rapier natives 是 https://github.com/ryanhcode/rapier 的修改版，
以 Apache License 2.0 授权（见 jar 内 natives/sable_rapier/LICENSE-RAPIER）。
Sable 还内嵌了 Veil（modId "veil"，作者 amo、Cappin、Ocelot），
以 GNU Lesser General Public License v3 授权
（见 jar 内 META-INF/jarjar/veil-neoforge-1.21.1-4.0.0.jar）。

Create、Flywheel、Ponder、Catnip 的代码以 MIT 授权。Create 从 Modrinth Maven
获取，本项目不再分发它，也不分发其 jar 内 All Rights Reserved 的资产。
```
