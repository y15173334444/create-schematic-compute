# -*- coding: utf-8 -*-
"""
Sable 切版本 runClient 实测助手 —— 矩阵三关都是静态关，行为级结论从这里出。
runClient harness for per-Sable-version hands-on testing — the matrix's three gates are
static; behavioural verdicts come from here.

把某个已拉取的 Sable 版本放到开发运行 classpath 上，再拉起 runClient：
Puts a fetched Sable version onto the dev runtime classpath and starts runClient:
    python tools/sable-compat/runclient_with_sable.py 2.0.6
    python tools/sable-compat/runclient_with_sable.py 2.0.6 --print   # 只打印命令
    python tools/sable-compat/runclient_with_sable.py baseline        # 回 libs/ 基线对
    python tools/sable-compat/runclient_with_sable.py 2.0.6 -- -Pquickplay=127.0.0.1:25565

机制 / mechanism：
  - `-PsableCompatDir=jars/<版本>` 把 sable + 配套 companion 对换上编译/运行 classpath
  - `-PsableVersionRange=[<该 jar 自述版本>]` 覆盖 mods.toml 区间 token —— 免得「可选依赖
    版本不符」警告混进实测噪声（实测什么版本就写什么区间，不改仓库里的 gradle.properties）
  - `--no-daemon` 是硬要求：daemon 起 runClient 会在 early-display 字段初始化崩（已知坑）

启动后按 `docs/1.3.0-game-regression-checklist.md` 实测；行为级结论记回
`docs/sable-compat-report.md` 的实测栏。客户端启动与实测由人执行，本脚本不代做。
"""
import argparse
import json
import re
import subprocess
import sys
import zipfile
from pathlib import Path

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
sys.stderr.reconfigure(encoding='utf-8', errors='replace')

HERE = Path(__file__).resolve().parent
REPO_ROOT = HERE.parents[1]
JARS_DIR = HERE / 'jars'
MANIFEST_PATH = JARS_DIR / 'manifest.json'


def gradle_prefix() -> list[str]:
    """gradle 前缀：Windows 的 .bat 必须经 cmd /c 执行（CreateProcess 不认批处理）。
    On Windows a .bat must go through cmd /c — CreateProcess refuses batch files."""
    if sys.platform == 'win32':
        return ['cmd', '/c', str(REPO_ROOT / 'gradlew.bat')]
    return [str(REPO_ROOT / 'gradlew')]


def version_range_token(jar_dir: Path, sable_jar_name: str) -> str:
    """从候选 jar 的 mods.toml 读它自述的版本，给出精确区间。
    Reads the candidate jar's own mods.toml version for an exact-match range token."""
    try:
        with zipfile.ZipFile(jar_dir / sable_jar_name) as z:
            toml_text = z.read('META-INF/neoforge.mods.toml').decode('utf-8', errors='replace')
        m = re.search(r'(?m)^version\s*=\s*"([^"]+)"', toml_text)
        if m and '${' not in m.group(1):
            return f'[{m.group(1)}]'
    except OSError:
        pass
    return '[1.0,)'  # 读不到就放开区间 / open range as a fallback


def resolve_version(selector: str) -> dict:
    manifest = json.loads(MANIFEST_PATH.read_text(encoding='utf-8'))
    matches = [r for r in manifest['versions']
               if selector == r['version_number'] or selector in r['version_number']]
    if not matches:
        known = ', '.join(r['version_number'] for r in manifest['versions'])
        raise SystemExit(f'没有匹配 "{selector}" 的版本 / no match. 已有 / available: {known}')
    if len(matches) > 1:
        ambiguous = ', '.join(r['version_number'] for r in matches)
        raise SystemExit(f'"{selector}" 匹配到多个版本 / ambiguous: {ambiguous}')
    return matches[0]


def main() -> int:
    parser = argparse.ArgumentParser(
        description='切 Sable 版本起 runClient 实测（未识别参数原样透传给 gradle）')
    parser.add_argument('version', help='版本号（子串匹配）或 baseline=用 libs/ 基线对')
    parser.add_argument('--print', dest='print_only', action='store_true',
                        help='只打印命令不执行')
    # 透传参数用 parse_known_args 而非 REMAINDER —— REMAINDER 会把 --print 一并吞走
    # parse_known_args, not REMAINDER: the latter swallows flags like --print whole
    args, extra = parser.parse_known_args()
    extra = [a for a in extra if a != '--']

    if args.version == 'baseline':
        cmd = gradle_prefix() + ['runClient', '--no-daemon', '--console=plain']
    else:
        record = resolve_version(args.version)
        jar_dir = JARS_DIR / record['version_number']
        version_range = version_range_token(jar_dir, record['sable_jar'])
        cmd = gradle_prefix() + ['runClient', '--no-daemon', '--console=plain',
                                 f'-PsableCompatDir={jar_dir}',
                                 f'-PsableVersionRange={version_range}']
        print(f'[版本/version] Sable {record["version_number"]}（{record["companion_jar"]}，'
              f'来源/{record["companion_source"]}）')
        print(f'[区间/range] mods.toml sable_version_range → {version_range}')

    cmd += extra
    print(f'    $ {" ".join(cmd)}')
    if args.print_only:
        return 0

    print('[提示/hint] 启动后按 docs/1.3.0-game-regression-checklist.md 实测；'
          '进程归你控制（Ctrl+C 退出）。')
    proc = subprocess.run(cmd, cwd=REPO_ROOT)
    return proc.returncode


if __name__ == '__main__':
    raise SystemExit(main())
