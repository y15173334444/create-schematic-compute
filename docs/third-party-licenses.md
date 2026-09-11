# 第三方依赖许可清单 / Third-Party Licenses

> 日期 / Date：2026-09-12
> 范围 / Scope：`libs/` 下 4 个已入库 jar；`assets/` 与 `texture_reference/`（未入库，仅本地参考）/ 4 tracked jars under `libs/`; `assets/` and `texture_reference/` (untracked, local reference only)
> 仓库可见性 / Repository visibility：**公开**（2026-09-12 由仓库所有者确认）/ **public** (confirmed by the repository owner, 2026-09-12)
> 证据来源 / Evidence：直接读取 jar 内 `LICENSE*` 条目原文，非二手转述 / read verbatim from the `LICENSE*` entries inside the jars, not second-hand

---

## 0. 结论摘要 / Executive Summary

**最严格的是 Create 系列的 ARR 资产，不是 Sable。** Sable 用的 PolyForm Shield 1.0.0 明文**授予**分发权（Distribution License），只限制"不得做竞品"；而 Create / Create Aeronautics 的 `assets/**` 是 All Rights Reserved，**连分发都不允许**。

**The strictest terms come from Create's ARR assets, not from Sable.** Sable's PolyForm Shield 1.0.0 explicitly **grants** distribution rights (Distribution License) and only forbids competing products; Create / Create Aeronautics `assets/**` are All Rights Reserved and do **not** permit distribution at all.

| 严格度 / Strictness | 依赖 / Dependency | 许可 / License | 对"把 jar 提交进仓库"的态度 / Stance on committing the jar |
| --- | --- | --- | --- |
| 1（最严 / strictest） | ~~create / create-aeronautics~~ | 代码 MIT + **assets ARR** / MIT code + **ARR assets** | ✅ **2026-09-12 已解决 / resolved**：aeronautics 删除、create 改走 Modrinth Maven，见 §3 O4 / aeronautics deleted, create now via Modrinth Maven — see §3 O4 |
| 2 | sable | **PolyForm Shield 1.0.0** | 可以分发（须带条款或 URL），不得做竞品 / may distribute (terms or URL required), must not compete |
| 3 | sable 内 Rapier natives / Rapier natives inside sable | Apache-2.0 | 可以，须原样保留许可与修改声明 / allowed, keep license and modification notice intact |
| 4 | flywheel / ponder / sable-companion | MIT | 可以，须保留版权与许可声明 / allowed, keep copyright and license notices |
| — | **veil**（内嵌在 Sable 里 / embedded in Sable） | **LGPLv3** | 唯一的 copyleft 项，见 §2.8 / the only copyleft item — see §2.8 |

**2026-09-12 更新**：ARR 缺口已消除 —— `create-aeronautics-bundled` 删除、`create` 改由 Modrinth Maven 拉取（§2.2 / §3 O4），`libs/` 入库体积 66 MB → 13.8 MB，且不再含任何 ARR 资产。

**Update 2026-09-12**: the ARR gap is closed — `create-aeronautics-bundled` was deleted and `create` now comes from Modrinth Maven (§2.2 / §3 O4). The tracked size of `libs/` dropped from 66 MB to 13.8 MB and no longer contains any ARR assets.

递归看还有一层：Sable 的 jar 内嵌 **Veil**，许可是 **LGPLv3**。清点必须递归，见 §2.8。

There is one more level: Sable's jar embeds **Veil**, licensed **LGPLv3**. Any inventory must recurse — see §2.8.

---

## 1. 为什么需要这份文档 / Why This Document Exists

`.gitignore` 第 6 行显式写 `!libs/*.jar`，于是 `libs/` 下的 jar 都会入库。把 jar 提交进仓库，等于**对外再分发**这些第三方软件，于是触发它们的许可义务。这份文档记录每个依赖的实际许可、我们的义务、以及已经做对的地方。

Line 6 of `.gitignore` says `!libs/*.jar`, so every jar under `libs/` gets tracked. Committing a jar into the repository amounts to **redistributing** that third-party software, which triggers its license obligations. This document records each dependency's actual license, our obligations, and the things we already get right.

截至 2026-09-12，`libs/` 入库 4 个 jar（约 13.8 MB）：Sable、Flywheel、Ponder、Sable Companion。**Create、Create Aeronautics 与 catnip-only 已不在其中**，原因与处置见 §3 O4 与 §2.7。

As of 2026-09-12, `libs/` tracks 4 jars (~13.8 MB): Sable, Flywheel, Ponder, Sable Companion. **Create, Create Aeronautics and catnip-only are no longer among them** — see §3 O4 and §2.7.

已经做对、需要保持的两条 / Two things already correct that must stay correct：

- `.gitignore:20` 的 `/assets/` 挡住了本地留存的 Create 座位贴图（33 个 png，6.6 KB，位于 `assets/create/textures/block/seat/`）——**不要**为了"方便"给它加 `!` 例外。
  `.gitignore:20`'s `/assets/` blocks the locally kept Create seat textures (33 PNGs, 6.6 KB, under `assets/create/textures/block/seat/`) — **do not** add a `!` exception for convenience.
- `.gitignore:34` 的 `texture_reference/` 同理。
  Same for `texture_reference/` on `.gitignore:34`.

---

## 2. 清单 / Inventory

### 2.1 `create-1.21.1-6.0.10.jar`（18.2 MB）— **已改为 Modrinth Maven 拉取 / now pulled from Modrinth Maven**

**2026-09-12**：不再提交进仓库，改由 `maven.modrinth:create:6.0.10+mc1.21.1` 拉取（§3 O4）。许可记录保留如下。

**2026-09-12**: no longer committed to the repository; it is pulled as `maven.modrinth:create:6.0.10+mc1.21.1` (§3 O4). The license record is kept below.

jar 内 `LICENSE.md_create-1.21.1`（1411 B，注意文件名被改成带 mod 后缀的形式，不是标准 `LICENSE.md`）：**双许可结构**

Inside the jar, `LICENSE.md_create-1.21.1` (1411 B — note the filename was renamed with a mod suffix rather than a standard `LICENSE.md`): **a dual-license structure**

- **Assets License (All Rights Reserved)** — 适用于 `./src/main/resources/assets/` 下所有文件，版权 `The Create Team / The Creators of Create`
  **Assets License (All Rights Reserved)** — applies to every file under `./src/main/resources/assets/`, copyright `The Create Team / The Creators of Create`
- **Code License (MIT)** — 其余所有文件，同一版权方
  **Code License (MIT)** — all other files, same copyright holder

### 2.2 `create-aeronautics-bundled-1.21.1-1.2.1.jar`（31.5 MB）— **已移除（2026-09-12）/ removed (2026-09-12)**

**2026-09-12 从 `libs/` 移除**（`git rm`）。理由与依据：

**Removed from `libs/` on 2026-09-12** (`git rm`). Reasons and evidence:

- `src/` 里对 aeronautics / simulated / offroad **零引用**，它对编译没有任何贡献；
  `src/` has **zero references** to aeronautics / simulated / offroad — it contributes nothing to compilation;
- 它是 ARR 资产最集中的那个 jar（内嵌 aeronautics 25.6 MB + simulated 6.2 MB + offroad 826 KB，三个都是"代码 MIT + assets ARR"）；
  it was the jar with the highest concentration of ARR assets (embedding aeronautics 25.6 MB + simulated 6.2 MB + offroad 826 KB, all three being "MIT code + ARR assets");
- 移除**不影响 dev 运行期**：`runs/client/mods`、`runs/client2/mods`、`runs/server/mods` 三个实例各自都有这个 jar 的本地副本（与 `libs/` 内那份同尺寸 33,030,286 B），而 `runs/` 已被 `.gitignore` 挡住、不随仓库分发；
  removal **does not affect dev runtime**: the three instances `runs/client/mods`, `runs/client2/mods` and `runs/server/mods` each hold a local copy of this jar (same size as the `libs/` one, 33,030,286 B), and `runs/` is blocked by `.gitignore` so it is never distributed;
- 这与仓库自己的结论一致：`docs/v1.2.4.1-regression-audit.md:12-13` 先判它"残留可清理"，随后在同日更正为"**玩家环境的必要 mod**（非残留）——`src/main` 零代码依赖，但玩家生态需要它，**保留在 mods 文件夹**"。注意落点是 `mods/`，不是 `libs/` —— 本次移除的正是 `libs/` 那一份。
  this matches the repository's own conclusion: `docs/v1.2.4.1-regression-audit.md:12-13` first judged it "a leftover, safe to clean up" and then corrected itself the same day to "**a mod required by the player environment** (not a leftover) — `src/main` has zero code dependency on it, but the player ecosystem needs it; **keep it in the mods folder**". Note the destination is `mods/`, not `libs/` — what was removed here is exactly the `libs/` copy.

许可证记录保留如下（将来若有人重新引入，义务照旧）：

The license record is kept below (if anyone reintroduces it later, the same obligations apply):

jar 内 `LICENSE.md`（1538 B），双许可 / inside the jar, `LICENSE.md` (1538 B), dual-licensed：

- **Assets ARR** — 范围为三个源目录（打进 jar 后路径已扁平化）：`aeronautics/.../assets/`、`simulated/.../assets/`、`offroad/.../assets/`
  **Assets ARR** — covering three source directories (paths flattened once packed into the jar): `aeronautics/.../assets/`, `simulated/.../assets/`, `offroad/.../assets/`
- **Code MIT** — 版权 `The Simulated Team / The Creators of Aeronautics`
  **Code MIT** — copyright `The Simulated Team / The Creators of Aeronautics`

### 2.3 `sable-neoforge-1.21.1-1.2.2.jar`（12.1 MB）— 唯一非开源许可 / the only non-open-source license

| 条目 / Entry | 大小 / Size | 内容 / Contents |
| --- | --- | --- |
| `LICENSE.md` | 5747 B / 89 行 / 89 lines | **PolyForm Shield 1.0.0**，逐段与官方文本一致 / section-by-section identical to the official text |
| `natives/sable_rapier/LICENSE-RAPIER` | 11347 B / 202 行 / 202 lines | Apache License 2.0 |
| `natives/sable_rapier/README.md` | 188 B | 声明 natives 是 <https://github.com/ryanhcode/rapier> 的**略作修改**版本 / states the natives are a **slightly modified** version of <https://github.com/ryanhcode/rapier> |
| `natives/sable_rapier/sable_rapier_binaries.zip.l4z` | 8.9 MB | 二进制 / binaries |

（该 jar 还内嵌了 `veil-neoforge-1.21.1-4.0.0.jar` 与 `sable-companion-common-1.21.1-1.6.0.jar`，见 §2.8。）

(This jar also embeds `veil-neoforge-1.21.1-4.0.0.jar` and `sable-companion-common-1.21.1-1.6.0.jar` — see §2.8.)

**关键事实：Sable 的 `LICENSE.md` 全文没有版权行、没有 `Required Notice:` 行、没有 `Licensor Line of Business:` 行。**（逐行核对 89 行确认。）这三条缺失各有法律后果，见 §3 O1 与 O6。

**Key fact: Sable's `LICENSE.md` contains no copyright line, no `Required Notice:` line, and no `Licensor Line of Business:` line anywhere.** (Verified line by line across all 89 lines.) Each of those three absences has a legal consequence — see §3 O1 and O6.

### 2.4 `sable-companion-common-1.21.1-1.6.0.jar` — 编译期必需 / required at compile time

jar 内 `LICENSE`（1066 B）：MIT License，`Copyright (c) 2026 RyanHCode`。

Inside the jar, `LICENSE` (1066 B): MIT License, `Copyright (c) 2026 RyanHCode`.

**这不是冗余副本，而是编译期必需依赖 —— 2026-09-12 实测确认。** 内容确实与 `sable-neoforge` jar 内嵌的那份**逐字节相同**（SHA256 `873633e3…3bed`，35,444 B），但**内嵌的那份 javac 看不见**（与 Flywheel / Ponder 同一条规则：javac 看不到 `META-INF/jarjar/` 下的嵌套 jar）。

把它从 `libs/` 移走后，`./gradlew runClient` 在 `:compileJava` 阶段失败，4 个错误、4 个不同的缺失类：

| 源文件:行 / Source:line | javac 找不到的类 / Missing class |
| --- | --- |
| `entity/ControlSeatEntity.java:124` | `dev.ryanhcode.sable.companion.SableCompanion` |
| `client/ControlSeatInputHandler.java:141` | `dev.ryanhcode.sable.companion.ClientSubLevelAccess` |
| `network/SablePacketHelper.java:77` | `dev.ryanhcode.sable.companion.SubLevelAccess` |
| `compat/ControlSeatBlockEntitySable.java:181` | `dev.ryanhcode.sable.companion.math.Pose3d` |

**为什么 `import` 扫描和字符串搜索都找不到它们**：源码从**没有写出**这些类名。它们是我们直接调用的 Sable API 的**签名类型** —— 例如 `ControlSeatBlockEntitySable.java:181` 的 `subLevel.logicalPose()` 返回 `Pose3d`，源码用 `var` 接住，于是 `Pose3d` 从未出现在任何一行源码里；`SubLevelAccess` / `ClientSubLevelAccess` / `SableCompanion` 同理（返回值或参数类型）。javac 仍然必须能从编译期 classpath 解析它们。

另有一条**独立的运行期路径**（不是上面这些错误的原因，但同样指向 companion）：`compat/SableReflection.java:173` 的 `Class.forName("dev.ryanhcode.sable.companion.math.Pose3dc")`（注意是接口 `Pose3dc`，与上面的类 `Pose3d` 不同）。它位于 `SableReflection` 初始化第二阶段（best-effort），失败会提前 `return` 并让 `posePosition` / `poseOrientation` 保持 null。

**结论：留在 `libs/`，不要动。** 当初把它标为"可删的重复项"是错的 —— 判断依据（无 import、与内嵌副本同哈希）漏掉了「签名类型对 javac 可见」这一层。

**This is not a redundant duplicate — it is a required compile-time dependency, confirmed by test on 2026-09-12.** Its content is **byte-identical** to the copy embedded in the `sable-neoforge` jar (SHA256 `873633e3…3bed`, 35,444 B), but **javac cannot see the embedded one** (the same rule as Flywheel / Ponder: javac cannot see jars nested under `META-INF/jarjar/`).

With the jar moved out of `libs/`, `./gradlew runClient` failed at `:compileJava` with four errors naming four distinct missing classes:

**Why neither import scanning nor a string search finds them**: the source **never writes** these class names. They are **signature types** of the Sable API the source calls directly — `ControlSeatBlockEntitySable.java:181` does `subLevel.logicalPose()`, which returns `Pose3d`, and the source captures it with `var`, so `Pose3d` appears on no line of source at all; `SubLevelAccess`, `ClientSubLevelAccess` and `SableCompanion` are the same story (return or parameter types). javac still has to resolve them from the compile classpath.

There is also a **separate runtime path** (not the cause of those errors, but pointing at the companion all the same): `compat/SableReflection.java:173` calls `Class.forName("dev.ryanhcode.sable.companion.math.Pose3dc")` — note the interface `Pose3dc`, not the class `Pose3d`. It sits in phase 2 of `SableReflection`'s init (best-effort); failing it returns early and leaves `posePosition` / `poseOrientation` null.

**Verdict: it stays in `libs/`; do not touch it.** Labelling it a deletable duplicate was wrong — the evidence used (no imports, same hash as the embedded copy) missed the layer where signature types must be visible to javac.

### 2.5 `flywheel-neoforge-1.21.1-1.0.6.jar`

jar 内 `META-INF/LICENSE.md`（1057 B）：**纯 MIT**，`Copyright (c) 2021-2024 Jozufozu`。

Inside the jar, `META-INF/LICENSE.md` (1057 B): **plain MIT**, `Copyright (c) 2021-2024 Jozufozu`.

注意两点 / two things to note：

- 它是**纯** MIT 文本，**没有** Create / Aeronautics 那种 "Assets License (All Rights Reserved)" 段落。整个 jar 只有 2 个图片文件（`assets/flywheel/textures/flywheel/noise/blue.png` 7,115 B、`logo.png` 15,359 B），`assets/` 下其余内容是 45 个 `.glsl` + 14 个 `.vert` + 10 个 `.frag` 着色器源码 —— 属于代码，不是美术资源。
  It is **plain** MIT text with **no** "Assets License (All Rights Reserved)" section of the kind Create / Aeronautics have. The entire jar holds only two image files (`assets/flywheel/textures/flywheel/noise/blue.png` 7,115 B and `logo.png` 15,359 B); the rest of `assets/` is 45 `.glsl` + 14 `.vert` + 10 `.frag` shader sources — code, not artwork.
- 它与 Create 内嵌的那份**逐字节相同**：SHA256 `31dda15c…337e`，873,570 B。
  It is **byte-identical** to the copy embedded in Create: SHA256 `31dda15c…337e`, 873,570 B.

**它对编译仍是必需的**：CSC 有 6 个文件 import `dev.engine_room.flywheel.*`，而 javac 看不到 Create jar 内 `META-INF/jarjar/` 里的嵌套 jar。保留在 `libs/` 是正当的。

**It is still required for compilation**: six CSC files import `dev.engine_room.flywheel.*`, and javac cannot see jars nested under `META-INF/jarjar/` inside Create's jar. Keeping it in `libs/` is legitimate.

### 2.6 `ponder-neoforge-1.0.85+mc1.21.1.jar`

jar 内 `LICENSE_Ponder`（1072 B，无扩展名）：MIT License，`Copyright (c) 2022 The Create Team`。

Inside the jar, `LICENSE_Ponder` (1072 B, no extension): MIT License, `Copyright (c) 2022 The Create Team`.

**这个 jar 顺便 shade 了 catnip**：内含 279 个 `net/createmod/catnip/**` 类。CSC 引用的四个类全部由它提供 —— `data/Couple.class`、`data/Pair.class`、`animation/AnimationTickHolder.class`、`math/VecHelper.class`（已逐个在 jar 内确认存在）。这直接决定了 §2.7 的结论。

**This jar also shades catnip**: it contains 279 `net/createmod/catnip/**` classes. All four classes CSC references come from it — `data/Couple.class`, `data/Pair.class`, `animation/AnimationTickHolder.class`, `math/VecHelper.class` (each confirmed present inside the jar). That is what settles §2.7.

版本对照：Create 6.0.10 内嵌的是 **1.0.82**（820,109 B），`libs/` 里是 **1.0.85**（820,193 B），Create 的 JIJ 声明版本范围是开区间 `[1.0.82+mc1.21.1,)`，所以 1.0.85 合法。

Version comparison: Create 6.0.10 embeds **1.0.82** (820,109 B) while `libs/` holds **1.0.85** (820,193 B). Create's JIJ metadata declares the open-ended range `[1.0.82+mc1.21.1,)`, so 1.0.85 is valid.

### 2.7 `catnip-only.jar`（6,492 B）— **已删除（2026-09-12）/ deleted (2026-09-12)**

**2026-09-12 从 `libs/` 移除**（`git rm`）。该 jar 的全部条目如下：

**Removed from `libs/` on 2026-09-12** (`git rm`). Its complete entry list:

```
META-INF/
META-INF/MANIFEST.MF
net/createmod/catnip/data/Couple.class
net/createmod/catnip/data/Pair.class
```

这是 Create 的 `catnip` 工具库里两个数据类的编译桩，内容属于 Create 的 MIT 部分，权利上没问题；但该 jar 自身不带任何许可文本或版权行。

This is a compile stub for two data classes from Create's `catnip` utility library. The content belongs to Create's MIT portion, so there is no rights problem; but the jar itself carries no license text or copyright line.

**为何可以直接删：**

1. §2.6 已确认 `ponder` jar 提供了 `Couple` 与 `Pair`（以及 CSC 另外两个 catnip 依赖），而 Create 的 jar 里一个 `net/createmod/catnip` 条目都没有；
2. 源码实际只用到一个类：`Couple`（`ControlSeatBlockEntity.java:8` 与 `RedstoneLinkHelper.java:10`，后者还出现在 `getNetworkKey()` 的签名里），`Pair` **一次都没用**；
3. **决定性理由**：这个 stub 只存在于本仓库的 `libs/` 里 —— 玩家的安装里从来没有它。所以生产环境里 `Couple` 本来就是由 `ponder` shade 的那份提供的（Create 用 JIJ 内嵌 ponder）。删掉它反而让 dev 的 classpath 更接近生产，而不是更远。

顺带，本清单也就少掉唯一的"来源可推断、义务未声明"项。

**Why it can go:**

1. §2.6 confirms the `ponder` jar supplies `Couple` and `Pair` (plus the other two catnip classes CSC depends on), while Create's jar has not a single `net/createmod/catnip` entry;
2. the source uses exactly one of them: `Couple` (`ControlSeatBlockEntity.java:8` and `RedstoneLinkHelper.java:10`, the latter in the signature of `getNetworkKey()`); `Pair` is **never used**;
3. **the decisive point**: this stub only ever existed in this repo's `libs/` — a player's install never had it. In production `Couple` has therefore always come from ponder's shaded copy (Create embeds ponder through JIJ). Removing it moves the dev classpath *closer* to production, not further away.

As a bonus the inventory loses its only "provenance inferable, obligations undeclared" item.

### 2.8 内嵌依赖（Jar-in-Jar）：一个 jar ≠ 一份许可 / Embedded Dependencies (Jar-in-Jar): One Jar ≠ One License

清点许可必须**递归**。宿主 jar 用 NeoForge 的 JIJ 机制内嵌了别的 mod，这些嵌套 jar 随宿主一起被分发：

Any license inventory must **recurse**. Host jars embed other mods via NeoForge's JIJ mechanism, and those nested jars are distributed along with the host:

| 宿主 / Host | 内嵌 jar / Nested jar | 大小 / Size | 内嵌许可 / Nested license |
| --- | --- | --- | --- |
| create | `flywheel-neoforge-1.21.1-1.0.6.jar` | 873,570 B | MIT |
| create | `ponder-neoforge-1.0.82+mc1.21.1.jar` | 820,109 B | MIT（并 shade catnip / also shades catnip） |
| create | `Registrate-MC1.21-1.3.0+67.jar` | 181,448 B | **无许可与 notice 条目 / no license or notice entry**（扫描 `licen\|notice\|copying` 无命中 / scan for `licen\|notice\|copying` found nothing） |
| create-aeronautics-bundled | ~~aeronautics / offroad / simulated（3 个 / 3 jars）~~ | ~~32.6 MB~~ | **宿主 jar 已于 2026-09-12 移除**（见 §2.2）/ **host jar removed 2026-09-12** (see §2.2) |
| sable | `veil-neoforge-1.21.1-4.0.0.jar` | 3,308,207 B | **LGPLv3** ⚠️ |
| sable | `sable-companion-common-1.21.1-1.6.0.jar` | 35,444 B | MIT |

**Veil 是全清单唯一的 copyleft 项。** 两个独立来源互相印证：jar 内 `LICENSE_Veil`（165 行 / 7,651 B）是 GNU LGPL v3 全文；其 `META-INF/neoforge.mods.toml` 里写着 `license="LGPLv3"`、`modId="veil"`、`authors="amo, Cappin, Ocelot"`。它嵌在 Sable 里，所以**只要分发 Sable 的 jar，就同时在分发一个 LGPLv3 组件**。

**Veil is the only copyleft item in this inventory.** Two independent sources corroborate it: `LICENSE_Veil` inside the jar (165 lines / 7,651 B) is the full GNU LGPL v3 text, and its `META-INF/neoforge.mods.toml` declares `license="LGPLv3"`, `modId="veil"`, `authors="amo, Cappin, Ocelot"`. It is embedded in Sable, so **distributing Sable's jar means distributing an LGPLv3 component at the same time**.

这带来两条实际影响 / two practical consequences：

1. **"不要重打包"这条规则的分量升级了**（见 §3 O5）。Apache-2.0 只要求保留许可与声明，而 LGPLv3 还要求接收方**能够替换/修改该库**（§4 的 Combined Works 条款）。裁掉或重打包嵌套的 veil 会让问题从"漏了一份通知"变成"实质性违反 LGPL"。
   **The "do not repackage" rule now carries more weight** (§3 O5). Apache-2.0 only requires keeping the license and notices, whereas LGPLv3 additionally requires that recipients stay **able to replace or modify that library** (the Combined Works clause in §4). Pruning or repackaging the nested veil turns "a missing notice" into "a substantive LGPL violation."
2. **对我们自己的发行物无影响**：这些 jar 是 `compileOnly` / `runtimeOnly`，不会进入 CSC 的 mod jar；`neoforge.mods.toml` 里也只声明了 minecraft / neoforge / create。Veil 的 LGPL 义务落在分发整个 Sable jar 的人身上 —— 现阶段是 GitHub 上的 `libs/`（即 O4）。
   **No impact on our own artifacts**: these jars are `compileOnly` / `runtimeOnly` and never enter CSC's mod jar; `neoforge.mods.toml` declares only minecraft / neoforge / create. Veil's LGPL obligations fall on whoever distributes the whole Sable jar — at this stage, `libs/` on GitHub (i.e. O4).

**升级版本时务必重新做这张表。** 嵌套依赖的许可和宿主 jar 的许可无关：`veil` 4.0.0 与更新版本、以及将来若重新引入 `aeronautics`（1.2.1 与 1.3.x）都可能有不同的许可条款。

**Redo this table whenever versions change.** A nested dependency's license is unrelated to its host's: `veil` 4.0.0 versus newer versions, and `aeronautics` 1.2.1 versus 1.3.x should it ever be reintroduced, may all carry different terms.

---

## 3. 我们的义务 / Our Obligations

### O1 — 传递许可（成本：一行 URL）/ Pass On the License (cost: one URL line)

Shield 的 Notices 条款要求：拿到副本的人同时拿到**这些条款或其 URL**，以及许可人提供的所有以 `Required Notice:` 开头的纯文本行。

Shield's Notices clause requires that anyone receiving a copy also receives **these terms or the URL for them**, plus every plain-text line beginning with `Required Notice:` that the licensor provided.

Sable **没有提供任何 `Required Notice:` 行** → 义务退化为**只带条款或 URL**。最小合规动作：

Sable **provides no `Required Notice:` line at all** → the obligation degrades to **carrying the terms or the URL only**. The minimum compliant action:

```
https://polyformproject.org/licenses/shield/1.0.0
```

直接落一行进 `libs/README.md` 与发行说明即可（§5 有可粘贴的块）。

One line in `libs/README.md` and in the release notes is enough (§4 has a paste-ready block).

### O2 — 不得做竞品 / No Competing Product

Shield 全文只有一条实质限制 / Shield has exactly one substantive restriction：

> Any purpose is a permitted purpose, except for providing any product that competes with the software or any product the licensor or any of its affiliates provides using the software.

§Competition 把"竞争"定义得极宽：**不同接口/不同技术平台也算**（应用 vs 服务、库 vs 插件、框架 vs 开发工具都算）、**写得不同语言或不同架构也算**、**免费提供也算**、"作为实用替代品营销"则必定算。

§Competition defines "compete" very broadly: **different interfaces or technology platforms still count** (applications can compete with services, libraries with plugins, frameworks with development tools), **different languages or architectures still count**, **free-of-charge still counts**, and marketing something "as a practical substitute" definitely counts.

CSC 是 Create 的计算/图扩展，与 Sable（物理引擎库）不构成竞争 → 就本条而言安全。**红线**：不要基于 Sable 代码做一个"Sable 替代品"。

CSC is a computation/graph extension for Create and does not compete with Sable (a physics engine library) → safe on this clause. **Red line**: do not build a "Sable replacement" on top of Sable's code.

### O3 — 不得再许可（No Other Rights）/ No Sublicensing (No Other Rights)

> These terms do not allow you to sublicense or transfer any of your licenses to anyone else.

因此 `libs/` 里的 jar **不能**被并入以 MIT 发布的模块，README 里也不能笼统写"本仓库代码均以 MIT 授权"。现状是用 flatDir 文件依赖做 `compileOnly` / `runtimeOnly`（`build.gradle:67/68/74`），不构成再许可 —— **保持现状**。

Therefore the jars in `libs/` **must not** be merged into anything released under MIT, and the README must not claim blanket "all code in this repo is MIT". The current setup uses flatDir file dependencies for `compileOnly` / `runtimeOnly` (`build.gradle:67/68/74`), which is not sublicensing — **keep it that way**.

### O4 — 不再分发 ARR 资产（**已解决，2026-09-12**）/ Stop Redistributing ARR Assets (**resolved 2026-09-12**)

**原缺口**：Create 与 Create Aeronautics 的 jar 内 `assets/**` 是 All Rights Reserved，而这两个 jar 曾经入库。仓库所有者确认该仓库公开可见，因此当时是实际存在的再分发。

**The original gap**: `assets/**` inside the Create and Create Aeronautics jars is All Rights Reserved, and both jars used to be tracked. The repository owner confirmed the repository is publicly visible, so this was a real, existing redistribution at the time.

**处置（同日完成）/ Remediation (completed the same day)**：

| jar | 处置 / Action | 依据 / Basis |
| --- | --- | --- |
| `create-aeronautics-bundled-1.21.1-1.2.1.jar` | **直接删除 / deleted outright** | `src/` 零引用；`runs/{client,client2,server}/mods` 各有本地副本（`runs/` 不入库）/ zero `src/` references; local copies live in `runs/{client,client2,server}/mods` (and `runs/` is untracked) |
| `create-1.21.1-6.0.10.jar` | **改由 Modrinth Maven 拉取 / now pulled from Modrinth Maven** | 见下 / see below |

迁移落在 `build.gradle`（`exclusiveContent` → `https://api.modrinth.com/maven`，`def createDep = "maven.modrinth:create:${create_maven_version}"`）与 `gradle.properties`（`create_maven_version = 6.0.10+mc1.21.1`）。

The migration lives in `build.gradle` (`exclusiveContent` → `https://api.modrinth.com/maven`, `def createDep = "maven.modrinth:create:${create_maven_version}"`) and `gradle.properties` (`create_maven_version = 6.0.10+mc1.21.1`).

**行为等价性已验证到底**：从 Modrinth 拉下来的那份与原先 `libs/` 里的**字节完全相同** —— 两边都是 19,123,767 B，SHA256 都是 `EF87FE5709F1BA1F5B8BB20A2925B5AFB4669E178FD6D8BF10C167759EEFE37A`。所以 dev 运行行为不变，只是分发方从我们换成了版权方自己。

**Behaviour equivalence was verified all the way down**: the copy pulled from Modrinth is **byte-identical** to the one that used to sit in `libs/` — both are 19,123,767 B with SHA256 `EF87FE5709F1BA1F5B8BB20A2925B5AFB4669E178FD6D8BF10C167759EEFE37A`. So dev runtime behaviour is unchanged; only the distributor changed from us to the copyright holder.

由此 `libs/` 的入库体积从约 66 MB 降到 13.8 MB，且**不再包含任何 ARR 资产**。

As a result the tracked size of `libs/` fell from about 66 MB to 13.8 MB and it **no longer contains any ARR assets**.

**必须记住的坑**：Modrinth 生成的 POM **不含 `<dependencies>`**（实测 475 B），所以 Flywheel / Ponder **不会**被传递进来 —— 它们必须继续留在 `libs/`，否则编译期看不到 `dev.engine_room.flywheel.*` 与 `net.createmod.catnip.*`。Create 自己的 `META-INF/jarjar/metadata.json` 倒是逐条声明了这三个坐标（`dev.engine-room.flywheel:flywheel-neoforge-1.21.1` `[1.0.6,2.0)`、`net.createmod.ponder:ponder-neoforge` `[1.0.82+mc1.21.1,)`、`com.tterrag.registrate:Registrate`）。

**A trap to remember**: Modrinth's generated POM contains **no `<dependencies>`** (measured: 475 B), so Flywheel / Ponder are **not** pulled in transitively — they must stay in `libs/`, otherwise the compile classpath cannot see `dev.engine_room.flywheel.*` or `net.createmod.catnip.*`. Create's own `META-INF/jarjar/metadata.json` does declare all three coordinates (`dev.engine-room.flywheel:flywheel-neoforge-1.21.1` `[1.0.6,2.0)`, `net.createmod.ponder:ponder-neoforge` `[1.0.82+mc1.21.1,)`, `com.tterrag.registrate:Registrate`).

**CI 侧**：`.github/workflows/pr-test.yml` 只在 PR 上跑 `./gradlew test`，本身就要联网拉 NeoGradle，因此 Maven 坐标不影响它。**本次是把 build 脚本与文件删除一起改的** —— 正是为了避免"只删 jar 不改脚本"导致 PR 上的单元测试编译失败（`testImplementation fileTree(dir:'libs')` 通配，且 CI 拿 jar 的唯一途径是 git checkout）。⚠️ 这次改动**尚未在本机编译验证**（本机 Maven Central / neoForm 不可达），第一次 PR 才是真正的验证。

**On the CI side**: `.github/workflows/pr-test.yml` runs `./gradlew test` on pull requests only, and it already needs network access for NeoGradle, so Maven coordinates do not disturb it. **The build script and the file deletions were changed together** — precisely to avoid "deleting the jar without touching the script" breaking test compilation on PRs (`testImplementation fileTree(dir:'libs')` is a wildcard, and the only way CI obtains those jars is `git checkout`). ⚠️ This change has **not** been compile-verified locally (Maven Central / neoForm are unreachable from this machine); the first PR is the real verification.

**残留**：两个 jar 的 blob 仍在 git 历史里（`git rev-list --objects --all` 可查到）。本次只让 HEAD 与后续 clone 不再携带它们；**未重写历史** —— 仓库文档大量引用 SHA，代价高于收益。

**Residual**: both jars' blobs remain in git history (`git rev-list --objects --all` finds them). This change only stops HEAD and future clones from carrying them; history was **not** rewritten — repo docs cite many SHAs, so the cost outweighs the benefit.

**其余 jar 无需迁移**：`flywheel`、`ponder`、`catnip`、`sable-companion` 是 MIT 或"内容属 Create 的 MIT 部分"，没有 ARR 问题；`sable` 的 PolyForm Shield 明文授予分发权，也不是必须迁。顺带记一笔：这几个 slug 在 Modrinth Maven 上要么没有 1.21.1 的对应版本（flywheel 最新只到 `0.6.8`，2023 年），要么是同名的另一个项目（ponder 列的是 2.x +fabric/+forge 的另一条线），**不要照抄 slug 迁移**。

**No other jar needs migrating**: `flywheel`, `ponder`, `catnip` and `sable-companion` are MIT or "content belongs to Create's MIT portion" with no ARR problem, and `sable`'s PolyForm Shield explicitly grants distribution, so it is not mandatory either. One aside worth recording: on Modrinth Maven these slugs either have no 1.21.1 version (flywheel stops at `0.6.8`, from 2023) or are an unrelated project of the same name (ponder lists a different 2.x +fabric/+forge line) — **do not copy the slug and expect it to work**.

明确**不要**做的：把 `assets/create/textures/**` 或 `texture_reference/**` 加进版本控制（`.gitignore:20` / `:34` 已挡住）。

What **not** to do: add `assets/create/textures/**` or `texture_reference/**` to version control (`.gitignore:20` / `:34` already block them).

### O5 — Apache-2.0（Rapier）+ LGPLv3（Veil）：原样保留，不要重打包 / Keep Intact, Do Not Repackage

`natives/sable_rapier/` 下的 `LICENSE-RAPIER`、`README.md`、二进制包必须**原样保留**，不要重打包或裁剪 Sable jar（例如"只留 API 做 compileOnly，把 natives 删掉"）——那样 Apache-2.0 §4(a) 的许可文本与 §4(b) 的修改声明就不再随分发传递。

`LICENSE-RAPIER`, `README.md` and the binaries under `natives/sable_rapier/` must be **kept intact**; do not repackage or prune the Sable jar (for example "keep only the API for compileOnly and drop the natives") — doing so would stop Apache-2.0 §4(a)'s license text and §4(b)'s modification notice from travelling with the distribution.

同理，**不要动 jar 内 `META-INF/jarjar/` 里的嵌套 jar**。自 §2.8 起这条规则的分量更重了：Sable 内嵌 **Veil（LGPLv3）**，而 LGPL §4 的 Combined Works 条款要求接收方**能够替换/修改该库**。裁掉或重打包嵌套的 veil，性质就从"漏了一份通知"变成"实质性违反 LGPL"。原样转发则许可文本与 `neoforge.mods.toml` 里的 `license="LGPLv3"` 都随 jar 一起到位。

Likewise, **do not touch the nested jars under `META-INF/jarjar/`**. Since §2.8 this rule weighs more: Sable embeds **Veil (LGPLv3)**, and the Combined Works clause in LGPL §4 requires that recipients stay **able to replace or modify that library**. Pruning or repackaging the nested veil turns "a missing notice" into "a substantive LGPL violation." Forwarding the jar unchanged delivers both the license text and the `license="LGPLv3"` line in `neoforge.mods.toml`.

由于 jar 内**没有** NOTICE 文件，Apache-2.0 §4(d)（保留 NOTICE）不触发。

Because the jar contains **no** NOTICE file, Apache-2.0 §4(d) (retain the NOTICE) is not triggered.

### O6 — 借调：`Licensor Line of Business:` 缺失是"利好下游" / The Missing `Licensor Line of Business:` Favours Downstream

Shield 的 §Discontinued Products 规定：许可人停售某产品线后，任何人都可以开始用它做竞品——**除非**许可人随软件提供了 `Licensor Line of Business:` 行。

Shield's §Discontinued Products provides that once the licensor stops offering a line of business, anyone may begin competing with it — **unless** the licensor supplied a `Licensor Line of Business:` line with the software.

Sable 没有提供该行，意味着这条解禁条款对 Sable **完全生效**：若 Sable 停止提供（以及任何用 Sable 提供的产品），我们做竞品在法律上就不受 §Noncompete 约束了。记下来，将来若真有分歧用得上。

Sable supplied no such line, which means this release clause applies to Sable **in full**: should Sable stop being offered (along with any product provided using Sable), building a competing product would no longer be constrained by §Noncompete. Noting it here in case a future dispute ever needs it.

---

## 4. 通知块（可直接粘贴）/ Notice Block (Paste-Ready)

放进 `libs/README.md`、`README.md` 的致谢节，或发行说明。英文为权威文本，中文版附在其后。

Put this into `libs/README.md`, the acknowledgements section of `README.md`, or the release notes. The English text is authoritative; a Chinese rendering follows it.

```text
Third-Party Notices / 第三方声明

The jars in this directory are used as compile-time and test-time dependencies only.
本目录下的 jar 仅作为编译期与测试期依赖使用，不对其内容主张任何权利。

- Sable (sable-neoforge, sable-companion-common) is licensed under the
  PolyForm Shield License 1.0.0: https://polyformproject.org/licenses/shield/1.0.0
  Sable's bundled Rapier natives are a modified version of
  https://github.com/ryanhcode/rapier, licensed under Apache License 2.0;
  see natives/sable_rapier/LICENSE-RAPIER inside the jar.
  Sable also bundles Veil (modId "veil", authors amo, Cappin, Ocelot),
  licensed under the GNU Lesser General Public License v3; see the nested
  META-INF/jarjar/veil-neoforge-1.21.1-4.0.0.jar inside the jar.

- Create, Flywheel, Ponder, Catnip: code under the MIT License;
  Copyright (c) The Create Team / The Creators of Create,
  Copyright (c) 2021-2024 Jozufozu, Copyright (c) 2022 The Create Team.
  Create is consumed from Modrinth Maven and is NOT redistributed by this
  project; the All Rights Reserved assets inside its jar are not redistributed
  either. Create Aeronautics is no longer a dependency of this project.

--- 中文版 ---

Sable（sable-neoforge、sable-companion-common）以 PolyForm Shield License 1.0.0 授权：
https://polyformproject.org/licenses/shield/1.0.0
它内嵌的 Rapier natives 是 https://github.com/ryanhcode/rapier 的修改版，
以 Apache License 2.0 授权；见 jar 内 natives/sable_rapier/LICENSE-RAPIER。
Sable 还内嵌了 Veil（modId "veil"，作者 amo、Cappin、Ocelot），
以 GNU Lesser General Public License v3 授权；见 jar 内
META-INF/jarjar/veil-neoforge-1.21.1-4.0.0.jar。

Create、Flywheel、Ponder、Catnip 的代码以 MIT 授权；
版权归 The Create Team / The Creators of Create、Jozufozu (2021-2024)、
The Create Team (2022)。Create 从 Modrinth Maven 获取，本项目不再分发它，
也不分发其 jar 内 All Rights Reserved 的资产。Create Aeronautics 不再是本项目的依赖。
```

---

## 5. 未决项 / Open Items

1. ~~`catnip-only.jar` 的来源与用途~~ **已删除**（2026-09-12，依据见 §2.7）。`ponder` 的 jar 提供了 CSC 用到的 catnip 类；该 stub 只在 `libs/` 里存在过，玩家安装里从来没有它。
   ~~The provenance and purpose of `catnip-only.jar`~~ **deleted** (2026-09-12, basis in §2.7). The `ponder` jar supplies the catnip class CSC uses; the stub only ever existed in `libs/` and was never part of a player's install.
2. ~~仓库可见性未确认。~~ **已确认公开**（2026-09-12，仓库所有者）。因此 O4 从"条件性风险"变为现存缺口 —— 处置方案、已验证的 Modrinth Maven 替换坐标与两个待实测点见 §3 O4。
   ~~Repository visibility unconfirmed.~~ **Confirmed public** (2026-09-12, repository owner). That turned O4 from a conditional risk into an existing gap — see §3 O4 for the remediation, the verified Modrinth Maven replacement coordinates and the two points that had to be tested first.
3. **Sable 的署名人。** `LICENSE.md` 里没有版权行，"licensor"只能从 mod 元数据确定（包名 `dev.ryanhcode.sable`，Modrinth 名 RyanHCode，与 `sable-companion` 的 `Copyright (c) 2026 RyanHCode` 一致）。要在文档里写署名，用 `neoforge.mods.toml` / Modrinth 的官方写法，不要自己编。
   **Sable's attribution name.** `LICENSE.md` has no copyright line, so the "licensor" can only be determined from mod metadata (package `dev.ryanhcode.sable`, Modrinth name RyanHCode, consistent with `Copyright (c) 2026 RyanHCode` in `sable-companion`). If you write an attribution, use the official `neoforge.mods.toml` / Modrinth wording — do not invent one.
4. ~~`create-aeronautics-bundled` 到底还用不用。~~ **已定论：从 `libs/` 删除**（2026-09-12）。静态引用分析显示 `src/` 里没有任何 aeronautics / simulated / offroad 的 import，它对编译毫无贡献；同时 `runs/{client,client2,server}/mods` 三个 dev 实例各有本地副本，删除不影响运行期。注意仓库自己的审计文档 `docs/v1.2.4.1-regression-audit.md:12-13` 已经精确区分过这一点：该 mod 是**玩家环境的必要 mod**（保留在 `mods/`），但对 `src/main` 零代码依赖 —— 所以清掉的是 `libs/` 那一份。
   ~~Whether `create-aeronautics-bundled` is still used at all.~~ **Settled: removed from `libs/`** (2026-09-12). Static reference analysis shows no aeronautics / simulated / offroad imports in `src/`, so it contributes nothing to compilation; and the three dev instances `runs/{client,client2,server}/mods` each hold a local copy, so removal does not affect runtime. Note that the repo's own audit, `docs/v1.2.4.1-regression-audit.md:12-13`, already drew exactly this distinction: the mod is **required by the player environment** (keep it in `mods/`) while `src/main` has zero code dependency on it — so what was cleaned up is the `libs/` copy.
5. **Veil 的 LGPLv3 是否要额外动作。** 目前只是原样转发 Sable 的 jar，许可文本与 `license="LGPLv3"` 都在 jar 内，粗看没有额外欠账。但若将来要**发行**任何内嵌 Sable 的产物（而非仅在 GitHub 上放依赖 jar），LGPL §4 的可替换性要求就需要单独评估。建议把这一条挂到"Sable 是否从 compileOnly 改为 bundling"那个决策上。
   **Does Veil's LGPLv3 require extra action?** For now we merely forward Sable's jar unchanged, with the license text and `license="LGPLv3"` both inside it, so on a first reading nothing further is owed. But if any artifact **embedding Sable** is ever **released** (rather than a dependency jar simply sitting on GitHub), the replaceability requirement in LGPL §4 needs its own assessment. Suggest attaching this item to the "should Sable move from compileOnly to bundling" decision.
6. ~~`libs/` 里的重复副本。~~ **已结案（2026-09-12 实测）：`libs/` 里没有可删的重复副本。**
   - `flywheel-neoforge-1.21.1-1.0.6.jar`：**必需** —— javac 看不到 Create jar 内 `META-INF/jarjar/` 的嵌套 jar，而源码 import 了 `dev.engine_room.flywheel.*`。
   - `sable-companion-common-1.21.1-1.6.0.jar`：**同样必需**，尽管它与 Sable 内嵌副本同哈希。移走后 `:compileJava` 报 4 个缺失类（`SableCompanion`、`ClientSubLevelAccess`、`SubLevelAccess`、`Pose3d`），它们是我们直接调用的 Sable API 的**签名类型**，源码里从不出现这些名字 —— 详见 §2.4。
   - `catnip-only.jar`：**已删**（2026-09-12，见 §2.7）。这是唯一真正冗余的那个。

   **教训**：判断"重复 jar 能否删"不能只看 `import` 与哈希。javac 必须解析所调用方法的返回值/参数类型，即使这些类型从未被写进任何一行源码（源码用 `var` 接住时尤其如此）。
   ~~Duplicate jars in `libs/`.~~ **Closed by test on 2026-09-12: there is no deletable duplicate in `libs/`.** `flywheel` is required (javac cannot see Create's nested jar and the source imports `dev.engine_room.flywheel.*`); `sable-companion-common` is **equally required** despite being identical in hash to Sable's embedded copy — removing it made `:compileJava` report four missing classes (`SableCompanion`, `ClientSubLevelAccess`, `SubLevelAccess`, `Pose3d`), all of them **signature types** of the Sable API the source calls, never written out in the source itself (§2.4). Only `catnip-only.jar` was genuinely redundant and it is deleted (§2.7). **Lesson**: deciding whether a duplicate jar can go takes more than imports and hashes — javac must resolve the return and parameter types of the methods being called, even when the source captures them with `var` and never names them.
