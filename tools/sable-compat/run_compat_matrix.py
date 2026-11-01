# -*- coding: utf-8 -*-
"""
Sable 版本兼容性矩阵驱动 —— 逐版本产出「链接点 / 编译 / 单测」三列证据。
Sable version compatibility matrix — per-version evidence across linkage, compile, tests.

对 jars/manifest.json 里的每个 Sable 版本依次做：
For every Sable version in jars/manifest.json:
  0. 先无旋钮跑一遍单测捕获**基线失败集**（被测提交自带的红）—— 版本行只计相对基线的新增失败，
     否则一条既有红测试会把全部版本误判成「单测不兼容」
     / first a knob-less test run captures the baseline failure set; version rows only
     count NEW failures beyond it
  1. 链接点探针（check_linkage.py，秒级）/ linkage probe
  2. `gradlew clean compileJava compileTestJava -PsableCompatDir=<版本目录>` —— clean 打头是
     刻意的：增量编译可能拿旧 classpath 的产物充数，矩阵结论必须是**全量重编**的结论
     / the clean is deliberate: incremental compilation can reuse artifacts built against a
     previous classpath, and a matrix verdict must be a full-rebuild verdict
  3. 编译通过才跑 `gradlew test -PsableCompatDir=<版本目录>`，并从 build/test-results/test/*.xml
     取测试数与失败明细 / tests run only after a clean compile, with counts and failure
     details read from the JUnit XML

失败分类 / failure classification（语义不同，报告里分开呈现）：
  linkage-break  链接点断（某条我们依赖的成员在该版本消失/变形）
  compile-error  编译不兼容（签名漂移实锤）
  test-failure   编译过了但单测不过（多为 companion/测试双轨漂移）
  infra-error    构建基建故障（网络拉不到 Mojang 元数据、超时、OOM）——**不算不兼容**，
                 重跑即可；gradle 的网络坑会伪装成构建错，必须归到这一类
                 / build-infrastructure trouble, NOT incompatibility; gradle's network
                 traps masquerade as build errors and must land here

用法 / usage：
    python tools/sable-compat/run_compat_matrix.py                 # 全量矩阵 + 写报告
    python tools/sable-compat/run_compat_matrix.py --only 1.0.4 2.0.6  # 挑版本
    python tools/sable-compat/run_compat_matrix.py --skip-clean    # 不 clean（快，但结论打折）
    python tools/sable-compat/run_compat_matrix.py --report out.md # 报告另放
    python tools/sable-compat/run_compat_matrix.py --repo <checkout>  # gradle 在另一个
        # checkout 里跑（隔离 worktree 防并行会话改 src/；报告仍写回本仓 docs/）

报告 / report：默认 docs/sable-compat-report.md（覆盖写）。
"""
import argparse
import datetime
import json
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
sys.stderr.reconfigure(encoding='utf-8', errors='replace')

HERE = Path(__file__).resolve().parent
REPO_ROOT = HERE.parents[1]
JARS_DIR = HERE / 'jars'
MANIFEST_PATH = JARS_DIR / 'manifest.json'
DEFAULT_REPORT = REPO_ROOT / 'docs' / 'sable-compat-report.md'
GRADLE_TIMEOUT_S = 1800


def gradle_prefix() -> list[str]:
    """gradle 前缀：Windows 的 .bat 必须经 cmd /c 执行（CreateProcess 不认批处理）。
    On Windows a .bat must go through cmd /c — CreateProcess refuses batch files."""
    if sys.platform == 'win32':
        return ['cmd', '/c', str(REPO_ROOT / 'gradlew.bat')]
    return [str(REPO_ROOT / 'gradlew')]

# gradle 网络/基建故障签名 —— 命中即归 infra-error，不算不兼容
# build-infrastructure signatures: hits land in infra-error, never in "incompatible"
INFRA_SIGNATURES = [
    'piston-meta.mojang.com', 'launchermeta.mojang.com', 'piston-data.mojang.com',
    'Could not download', 'Could not GET ', 'Could not resolve ',
    'UnknownHostException', 'Connection reset', 'Connection refused',
    'Connection timed out', 'Read timed out', 'SocketTimeoutException',
    'OutOfMemoryError', 'Could not determine java version',
]

sys.path.insert(0, str(HERE))
import check_linkage  # noqa: E402  探针复用 / reuse the probe


def semver_key(version_number: str):
    parts = re.findall(r'\d+', version_number.split('+')[0])
    return tuple(int(p) for p in parts[:3]) + (0,) * (3 - min(len(parts), 3))


def run_gradle(tasks: list[str], jar_dir: Path | None, extra: list[str],
               repo: Path) -> tuple[int, str]:
    knob = [f'-PsableCompatDir={jar_dir}'] if jar_dir is not None else []
    cmd = gradle_prefix() + [*tasks, *knob, '--console=plain', *extra]
    print(f'    $ {" ".join(cmd)}')
    try:
        proc = subprocess.run(
            cmd, cwd=repo, capture_output=True, text=True,
            encoding='utf-8', errors='replace', timeout=GRADLE_TIMEOUT_S,
        )
        return proc.returncode, proc.stdout + proc.stderr
    except subprocess.TimeoutExpired:
        return 124, f'[infra] gradle 超时 {GRADLE_TIMEOUT_S}s'


def classify(output: str, returncode: int) -> tuple[str, str]:
    """返回 (分类, 证据摘要) / returns (classification, evidence excerpt)."""
    for sig in INFRA_SIGNATURES:
        if sig in output:
            idx = output.find(sig)
            excerpt = output[max(0, idx - 120):idx + 200].strip()
            return 'infra-error', f'命中基建签名 `{sig}`：…{excerpt}…'
    if returncode == 0:
        return 'pass', ''
    compile_fail = ':compileJava FAILED' in output or ':compileTestJava FAILED' in output
    if compile_fail:
        errors = [ln.strip() for ln in output.splitlines() if 'error:' in ln][:10]
        return 'compile-error', '\n'.join(errors) or output[-600:]
    if ':test FAILED' in output:
        failed = [ln.strip() for ln in output.splitlines()
                  if 'FAILED' in ln and 'Task' not in ln][:10]
        return 'test-failure', '\n'.join(failed) or output[-600:]
    return 'infra-error', f'未识别的构建失败（returncode={returncode}）：…{output[-400:]}…'


def read_test_results(repo: Path) -> tuple[int, dict[str, str]]:
    """解析 JUnit XML：(用例数, {失败用例ID: 消息}) / parse JUnit XML results."""
    total = 0
    failures: dict[str, str] = {}
    results_dir = repo / 'build' / 'test-results' / 'test'
    if not results_dir.is_dir():
        return total, failures
    for xml_file in sorted(results_dir.glob('TEST-*.xml')):
        root = ET.parse(xml_file).getroot()
        total += int(root.get('tests', 0))
        for case in root.iter('testcase'):
            for bad in list(case):
                if bad.tag in ('failure', 'error'):
                    case_id = f"{case.get('classname', '').rsplit('.', 1)[-1]}.{case.get('name')}"
                    msg = (bad.get('message') or bad.get('type') or '').strip().replace('\n', ' ')
                    failures[case_id] = msg[:160]
                    break
    return total, failures


def probe_one(jar_dir: Path) -> tuple[str, list[str]]:
    results = check_linkage.check_dir(jar_dir)
    fails = [p['id'] for p, ok, _ in results if not ok]
    return f'{len(results) - len(fails)}/{len(results)}', fails


def main() -> int:
    parser = argparse.ArgumentParser(description='Sable 版本兼容性矩阵')
    parser.add_argument('--only', nargs='+', help='只跑这些版本号（子串匹配）')
    parser.add_argument('--skip-clean', action='store_true', help='不 clean（快，但结论打折）')
    parser.add_argument('--repo', help='gradle 的 checkout 路径（默认=本仓）。'
                                       '用隔离 worktree 跑可避开并行会话对 src/ 的改动，'
                                       '也互不踩 build/；报告仍写回本仓 docs/')
    parser.add_argument('--report', default=str(DEFAULT_REPORT), help='报告输出路径')
    args = parser.parse_args()
    gradle_repo = Path(args.repo).resolve() if args.repo else REPO_ROOT

    manifest = json.loads(MANIFEST_PATH.read_text(encoding='utf-8'))
    records = sorted(manifest['versions'], key=lambda r: semver_key(r['version_number']))
    if args.only:
        records = [r for r in records
                   if any(sel in r['version_number'] for sel in args.only)]
        if not records:
            print('没有匹配的版本 / no matching versions', file=sys.stderr)
            return 2

    probe_points_total = len(check_linkage.load_points())
    print(f'[矩阵/matrix] {len(records)} 个版本；链接点 {probe_points_total} 条/版')

    # 基线失败集：无旋钮先跑一遍单测，把被测提交自带的红记下来 —— 版本行只计「新增」失败，
    # 否则一条既有红测试会把 14 个版本全判成「单测不兼容」。
    # Baseline failure set: a knob-less test run records the reds the tested commit already
    # carries; version rows only count NEW failures. Without this, one pre-existing red test
    # would mark every Sable version "tests incompatible".
    print('\n== 基线 / baseline（无旋钮） ==')
    base_code, base_output = run_gradle(['test'], None, [], gradle_repo)
    base_class, base_evidence = classify(base_output, base_code)
    if base_class in ('infra-error', 'compile-error'):
        print(f'    基线自身 {base_class}，无法归因版本失败，中止 / baseline unhealthy, aborting')
        print(f'    {base_evidence}')
        return 3
    baseline_total, baseline_failures = read_test_results(gradle_repo)
    print(f'    基线：{baseline_total} 用例，{len(baseline_failures)} 条既有红 / pre-existing reds')
    for case_id, msg in sorted(baseline_failures.items()):
        print(f'      [基线红] {case_id} —— {msg}')

    rows = []
    for record in records:
        version = record['version_number']
        jar_dir = JARS_DIR / version
        print(f'\n== {version} ==')
        probe_summary, probe_fails = probe_one(jar_dir)
        print(f'    [链接点/linkage] {probe_summary}')

        tasks = (['clean'] if not args.skip_clean else []) + ['compileJava', 'compileTestJava']
        code, output = run_gradle(tasks, jar_dir, [], gradle_repo)
        compile_class, compile_evidence = classify(output, code)
        print(f'    [编译/compile] {compile_class}')

        test_class, test_evidence, test_total = 'skipped', '', 0
        test_new: dict[str, str] = {}
        test_known: dict[str, str] = {}
        if compile_class == 'pass':
            code, output = run_gradle(['test'], jar_dir, [], gradle_repo)
            test_class, test_evidence = classify(output, code)
            test_total, run_failures = read_test_results(gradle_repo)
            test_new = {k: v for k, v in run_failures.items() if k not in baseline_failures}
            test_known = {k: v for k, v in run_failures.items() if k in baseline_failures}
            if test_class == 'test-failure' and not test_new:
                # 只有基线已知红 —— 不算版本不兼容 / only baseline-known reds: not a version break
                test_class = 'pass'
                test_evidence = ''
            elif test_new:
                test_class = 'test-failure'
                test_evidence = '\n'.join(f'{k} —— {v}' for k, v in list(test_new.items())[:10])
            note = f'；基线红 {len(test_known)} 条不计' if test_known else ''
            print(f'    [单测/test] {test_class}（{test_total} 用例{note}）')

        rows.append({
            'version': version,
            'date': record['date_published'],
            'probe_summary': probe_summary,
            'probe_fails': probe_fails,
            'compile_class': compile_class,
            'compile_evidence': compile_evidence,
            'test_class': test_class,
            'test_evidence': test_evidence,
            'test_total': test_total,
            'test_new': test_new,
            'test_known': test_known,
        })

    report_path = Path(args.report)
    report_path.write_text(
        render_report(rows, probe_points_total, baseline_total, baseline_failures, gradle_repo),
        encoding='utf-8')
    print(f'\n[完成/done] 报告已写入 {report_path}')
    return 0


def verdict_of(row: dict) -> str:
    if row['compile_class'] == 'infra-error' or row['test_class'] == 'infra-error':
        return '基建故障待重跑'
    if row['probe_fails']:
        return '链接点断裂'
    if row['compile_class'] == 'compile-error':
        return '编译不兼容'
    if row['test_class'] == 'test-failure':
        return '单测不兼容'
    return '编译+单测通过（行为级待实测）'


def render_report(rows: list[dict], probe_points_total: int,
                  baseline_total: int, baseline_failures: dict[str, str],
                  tested_repo: Path) -> str:
    today = datetime.date.today().isoformat()
    try:
        commit = subprocess.run(['git', 'rev-parse', '--short', 'HEAD'], cwd=tested_repo,
                                capture_output=True, text=True).stdout.strip()
    except OSError:
        commit = '未知/unknown'

    lines = [
        '# Sable 版本兼容性矩阵报告',
        '',
        f'- 日期 / date：{today}；被测提交 / commit：`{commit}`',
        f'- 被测 checkout / tested checkout：`{tested_repo}`',
        '- 版本来源 / versions：Modrinth `sable` 项目 NeoForge 线 + MC 1.21.1 全量（`tools/sable-compat/fetch_sable_jars.py`）',
        '- 基线对 / baseline pair：`libs/sable-neoforge-1.21.1-1.2.2.jar` + `libs/sable-companion-common-1.21.1-1.6.0.jar`（mods.toml 区间的实测锚；区间值以 gradle.properties 的 sable_version_range 为准）',
        '- 口径 / semantics：每个版本全量重编（clean 打头）；companion 取该 sable jar 内嵌 jarJar 的配套版本。',
        '',
        '## 矩阵 / matrix',
        '',
        f'| Sable 版本 | 发布日 | 链接点 | 编译 | 单测 | 结论 |',
        '|---|---|---|---|---|---|',
    ]
    for row in rows:
        if row['test_class'] == 'pass':
            note = f"；基线红 {len(row['test_known'])} 条不计" if row['test_known'] else ''
            test_cell = f"通过（{row['test_total']} 用例{note}）"
        elif row['test_class'] == 'skipped':
            test_cell = '—'
        else:
            test_cell = f"不兼容（新增 {len(row['test_new'])} 条）"
        compile_cell = {'pass': '通过', 'compile-error': '不兼容'}.get(row['compile_class'], row['compile_class'])
        probe_cell = row['probe_summary'] if not row['probe_fails'] else f"**{row['probe_summary']}**"
        lines.append(f"| {row['version']} | {row['date']} | {probe_cell} | {compile_cell} | {test_cell} | {verdict_of(row)} |")

    lines += [
        '',
        f'链接点清单共 {probe_points_total} 条（`tools/sable-compat/linkage-points.json`），',
        '含 ControlSeatCameraMixin 的字符串目标 `EntitySubLevelRotationHelper.shouldCameraRotate` ——',
        '它经 mixin 反射链接，编译查不到，只有探针能查。',
        '',
        '## 基线 / baseline（被测提交自带的红）',
        '',
    ]
    if baseline_failures:
        lines.append(f'被测提交在**无旋钮**状态下本就有 {len(baseline_failures)} 条红'
                     f'（{baseline_total} 用例）：')
        lines.append('')
        for case_id, msg in sorted(baseline_failures.items()):
            lines.append(f'- `{case_id}` —— {msg}')
        lines += [
            '',
            '这些红与 Sable 版本无关（换任何版本都同红），矩阵不计入版本结论、不写进失败明细。',
            '它们是被测提交自身的问题，归 custom-node 编辑器面（波1批C）那条线。',
            '',
        ]
    else:
        lines += ['被测提交基线全绿。', '']

    failing = [r for r in rows if verdict_of(r) != '编译+单测通过（行为级待实测）']
    if failing:
        lines.append('## 失败明细 / failure details')
        lines.append('')
        for row in failing:
            lines.append(f"### {row['version']} —— {verdict_of(row)}")
            lines.append('')
            if row['probe_fails']:
                lines.append('链接点断裂 / linkage breaks：')
                for point_id in row['probe_fails']:
                    lines.append(f'- `{point_id}`')
                lines.append('')
            if row['compile_class'] == 'compile-error':
                lines.append('编译证据 / compile evidence：')
                lines.append('```')
                lines.append(row['compile_evidence'])
                lines.append('```')
                lines.append('')
            if row['test_class'] == 'test-failure':
                lines.append('单测**新增**失败 / new test failures（相对基线）：')
                lines.append('```')
                for case_id, msg in row['test_new'].items():
                    lines.append(f'{case_id} —— {msg}')
                lines.append('```')
                lines.append('')
            if row['compile_class'] == 'infra-error':
                lines.append(f"基建故障 / infra：{row['compile_evidence']}")
                lines.append('')
            if row['test_class'] == 'infra-error':
                lines.append(f"基建故障 / infra：{row['test_evidence']}")
                lines.append('')
    else:
        lines.append('## 失败明细 / failure details')
        lines.append('')
        lines.append('无 —— 全部版本链接点、编译、单测三关通过。')
        lines.append('')

    lines += [
        '## 覆盖边界 / what this matrix does NOT cover',
        '',
        '三关都是**静态关**：成员在不在、javac 过不过、无头单测过不过。以下行为耦合只能 runClient 实测',
        '（`tools/sable-compat/runclient_with_sable.py <版本>` + `docs/1.3.0-game-regression-checklist.md`）：',
        '',
        '- Sable 的骑手踢出 / `retain_in_sub_level` 行为（ControlSeatEntity 依赖其接管语义）',
        '- plot 网格宿主模型（worldPosition 是网格本地坐标、1.2.2 起中心+本地坐标系的口径）',
        '- 跨类 loader 的 BE 复制（SablePacketHelper 的同名接口双 Class 应对）',
        '- mixin 实际注入效果（探针只证目标方法还在，不证注入成功）',
        '- 无 Sable 环境的优雅缺席（SableAbsenceTest 锚定的是本模组一侧）',
        '',
        '因此「编译+单测通过」≠「实测兼容」；区间是否放开以行为级实测为准。',
        '',
        '---',
        '',
        '操作手册见 `docs/sable-compat-testing.md`。',
        '',
    ]
    return '\n'.join(lines)


if __name__ == '__main__':
    raise SystemExit(main())
