# 发布 jar 全平台 natives 矩阵 / All-platform natives matrix for release jars

> 状态 / Status：已拍板落地（2026-11-21）。机制级描述，行号一律不引。
> Decided and landed (2026-11-21). Mechanism-level; no line-number anchors anywhere.

## 背景 / Background

Rust 公式后端（formula_native cdylib）的识别矩阵是六平台：windows/linux/macos ×
x86_64/aarch64（`FormulaNative.osArchDir()` 与 build.gradle 映射逐字锁步）。但
`buildNative` 是 host-target 构建——发布 jar 里只有**构建机那一份** native；其余
平台虽然被加载器认识，拿到的 jar 却没有对应库，实际都落在 Java 解释器兜底上。
目标：发布 jar 六份齐装，六平台玩家全部吃到 Rust 后端。
The Rust formula backend recognizes six platforms (windows/linux/macos ×
x86_64/aarch64; `FormulaNative.osArchDir()` and build.gradle's mapping are
verbatim-locked). But `buildNative` is a host-target build — a released jar
carries only the **build machine's** copy; every other platform, though known to
the loader, finds no matching library and silently runs the Java interpreter.
Goal: release jars carry all six, so every platform gets the Rust backend.

## 不变量 / Invariants

- os/arch → 目录名映射两端写死锁步（Java ↔ gradle），本次不改映射，只改打包面。
- 运行时提取缓存（CRC32 + 原子 move）、ABI 探测、Java 兜底口径全部不动——jar 里
  多了几份别的平台库，`getResource("natives/<own-dir>/<lib>")` 语义不变。
- cargo 缺席 → buildNative 跳过打 warn、绝不 fail；Java 解释器兜底不变。
- The os/arch → dir-name mapping stays verbatim-locked on both sides; only the
  packaging surface changes.
- Runtime extraction (CRC32 + atomic move), the ABI probe and the Java-fallback
  policy are untouched — extra foreign-platform libs in the jar do not change
  `getResource("natives/<own-dir>/<lib>")` semantics.
- Missing cargo still skips `buildNative` with a warning, never fails; the Java
  interpreter remains the fallback.

## 方案 / Design

### 1. gradle 打包接线放宽 / Packaging wiring widened

`processResources` 的 include 从「host 目录那一个文件」放宽为「staging 根
（`build/natives/`）下**任何** os-arch 目录里的 cdylib」（`*/*.dll`、`*/*.so`、
`*/*.dylib`）。本机构建 staging 恒只有 host 一份 → 本地行为逐字节不变；CI 矩阵
把六份产物先铺进 `build/natives/` 再打 jar → 同一处接线服务两条产线。
The `processResources` include widens from "the one host file" to "every cdylib
under any os-arch dir in the staging root (`build/natives/`)". Local builds
stage exactly one host copy, so local behaviour is unchanged; the CI matrix
pre-stages all six before the jar task, so one wiring serves both pipelines.

### 2. CI 构建矩阵 / CI build matrix（native-matrix.yml）

| dir | runner | cargo target | 链接 / linkage |
|---|---|---|---|
| windows-x86_64 | windows-latest | x86_64-pc-windows-msvc | msvc + crt-static |
| windows-aarch64 | windows-latest | aarch64-pc-windows-msvc（交叉） | msvc + crt-static |
| linux-x86_64 | ubuntu-latest | x86_64-unknown-linux-gnu | 宿主 / host |
| linux-aarch64 | ubuntu-latest | aarch64-unknown-linux-gnu（交叉） | `gcc-aarch64-linux-gnu` |
| macos-aarch64 | macos-latest | aarch64-apple-darwin | 宿主 / host |
| macos-x86_64 | macos-latest | x86_64-apple-darwin（交叉） | Apple SDK 双架构互编 / same-SDK cross |

- Windows 两个目标统一 msvc + `-C target-feature=+crt-static`：dll 自包含 CRT，
  玩家机器免装 VC++ 运行库（本机 dev 构建仍是 gnu host，只影响本地产物，无碍）。
  Both Windows targets use msvc + `-C target-feature=+crt-static`: the dll
  carries its own CRT, no VC++ redist on player machines (local dev builds stay
  gnu host — affects only local artifacts, harmless).
- crate 纯 Rust（libm + jni，零 C 依赖、无 build.rs）→ 交叉编译只需换 target +
  链接器，无 sysroot 问题。构建一律 `--locked --target <triple>`（产物路径统一
  `target/<triple>/release/`）。/ The crate is pure Rust (libm + jni, zero C
  deps, no build.rs) → cross-compiling needs only a target + linker, no sysroot
  work. Builds always run `--locked --target <triple>` (uniform artifact path
  `target/<triple>/release/`).

### 3. CI verify 矩阵 / CI verify matrix

三 OS（ubuntu / windows / macos-latest=arm64）各跑一遍：

1. 汇总六份矩阵产物铺进 `build/natives/`，断言恰好六个目录；
2. `./gradlew jar test -x buildNative`——**`-x buildNative` 是刻意的**：GH runner
   预装 cargo，不排除会把 host 目录就地重编覆盖矩阵产物，反而掩盖坏工件；
3. 断言 jar 恰好带六份 `natives/<dir>/<lib>`；
4. 全量 `test`（本 OS 的 native 在 classpath 上，AVAILABLE=true 走 native 路）；
5. **jshell 探针**正面断言 `FormulaNative.AVAILABLE=true`——`FormulaNativeTest`
   在 native 缺席时整类 `assumeTrue` 跳过、套件照样绿，探针是「真的加载了」的
   唯一硬证据；
6. ubuntu job 另跑 `file` 架构指纹校验（六份逐一比对 ELF/PE/Mach-O 架构 token，
   防错位打包）并上传 `formula-jar-all-natives` 工件（发布附件用它）。

Each of three OSes (ubuntu / windows / macos-latest = arm64) runs:
1. stage all six matrix artifacts into `build/natives/`, assert exactly six dirs;
2. `./gradlew jar test -x buildNative` — **`-x buildNative` is deliberate**: GH
   runners preinstall cargo, so without the exclusion the host dir gets rebuilt
   in place, overwriting the matrix artifact and masking a broken one;
3. assert the jar carries exactly six `natives/<dir>/<lib>` entries;
4. the full `test` suite (this OS's native is on the classpath, AVAILABLE=true);
5. a **jshell probe** positively asserts `FormulaNative.AVAILABLE=true` — the
   e2e class `assumeTrue`-skips when natives are missing and the suite stays
   green, so the probe is the only hard evidence of a real load;
6. the ubuntu job additionally runs `file` arch fingerprints (one by one,
   ELF/PE/Mach-O tokens, catching mis-dir'd binaries) and uploads the
   `formula-jar-all-natives` artifact (releases attach this jar).

## 覆盖边界 / Coverage boundaries

- 三个 verify runner 各自**加载验证** windows-x86_64 / linux-x86_64 /
  macos-aarch64（三台恰为本架构）。三个换架构目标（windows-aarch64 /
  linux-aarch64 / macos-x86_64）CI 只做构建 + 架构指纹，**加载验证留实机**——
  x64 runner 上无法加载异架构库（arm64 云端 runner 仍是预览品，不绑）。
- pr-test.yml 不动：ubuntu 全量测试无 natives → 解释器路径基线，与矩阵工作流互补。
- The three verify runners **load-verify** windows-x86_64 / linux-x86_64 /
  macos-aarch64 (each is that runner's own arch). The three swapped-arch targets
  (windows-aarch64 / linux-aarch64 / macos-x86_64) get build + fingerprint only;
  **loading them is verified on real hardware** — x64 runners cannot load
  foreign-arch libraries (arm64 cloud runners are still preview; not bound).
- pr-test.yml stays untouched: the ubuntu suite without natives is the
  interpreter-path baseline, complementary to this workflow.

## 触发与发布接线 / Triggers & release wiring

- 触发：PR（paths：build.gradle / native/** / 本工作流）+ 手动
  `workflow_dispatch`。/ Triggers: PRs (paths: build.gradle / native/** / this
  workflow) plus manual `workflow_dispatch`.
- 发布口径（runbook §2.1）：发布附件 = `formula-jar-all-natives` 工件；本地
  `./gradlew build` 的 jar 只带 host 一份，仅用于开发。
- Release policy (runbook §2.1): release attachments use the
  `formula-jar-all-natives` artifact; a local `./gradlew build` jar carries only
  the host copy and is for development only.
