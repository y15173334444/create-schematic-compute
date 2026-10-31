# -*- coding: utf-8 -*-
"""
Sable 全版本拉取脚本 —— 兼容性矩阵的版本仓库唯一来源。
Sable version fetcher — the single source of the version store behind the compat matrix.

从 Modrinth（project `sable`）拉取 NeoForge 线 + Minecraft 1.21.1 的**全量版本 jar**，
存到 tools/sable-compat/jars/<version_number>/，并从每个 sable jar 内嵌 jarJar 包里抽出
配套的 sable-companion-common jar（Sable 把 companion jarJar 进自己的 jar，javac 看不到
嵌套 jar，编译期必须单独放出来）。抽不出来时回落 libs/ 的基线 companion 并在清单里记明。
Pulls every NeoForge + MC 1.21.1 Sable version jar from Modrinth into
tools/sable-compat/jars/<version_number>/ and extracts the matching sable-companion-common
jar each sable jar bundles via jarJar (javac cannot see nested jars, so the companion must
sit beside it for compilation). If extraction is impossible the manifest records a fallback
to the baseline companion in libs/.

清单 / manifest：jars/manifest.json —— 版本号、Modrinth 版本 id、发布日期、sable jar 文件名
与 SHA1、companion 文件名与来源（extracted / fallback-libs）。矩阵与切版本脚本只认清单。

用法 / usage：
    python tools/sable-compat/fetch_sable_jars.py            # 增量补齐（已校验的跳过）
    python tools/sable-compat/fetch_sable_jars.py --force    # 全部重新下载
    python tools/sable-compat/fetch_sable_jars.py --match 2.0  # 只处理版本号含 2.0 的

jar 本身**不入库**（PolyForm Shield 再分发受限 + 数百 MB），本脚本可随时重建整个 jars/。
The jars themselves are never committed; this script rebuilds jars/ on demand.

契约 / contract:
  1. 只拉 loader=neoforge 且 game_versions 含 1.21.1 的版本 —— 本模组是 NeoForge 模组，
     fabric 线没有可测对象 / only the NeoForge 1.21.1 line is testable against our mod
  2. 已存在的 jar 逐 SHA1 核验 Modrinth 哈希，一致才跳过 / existing jars are kept only if
     their SHA1 matches Modrinth's
  3. companion 优先从 jarJar 嵌套包抽取（文件名含 `companion`），否则回落 libs/ 基线对，
     每个版本的来源都写进清单 / companion is extracted from the jarJar nesting when
     possible, else the libs/ baseline pair; every version's provenance lands in the manifest
"""
import argparse
import hashlib
import json
import re
import shutil
import sys
import urllib.request
import zipfile
from pathlib import Path

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
sys.stderr.reconfigure(encoding='utf-8', errors='replace')

API_VERSIONS = 'https://api.modrinth.com/v2/project/sable/version'
USER_AGENT = 'create-schematic-compute-compat-matrix/1.0 (tools/sable-compat/fetch_sable_jars.py)'
MC_VERSION = '1.21.1'
LOADER = 'neoforge'

REPO_ROOT = Path(__file__).resolve().parents[2]
JARS_DIR = Path(__file__).resolve().parent / 'jars'
MANIFEST_PATH = JARS_DIR / 'manifest.json'
BASELINE_COMPANION = REPO_ROOT / 'libs' / 'sable-companion-common-1.21.1-1.6.0.jar'


def semver_key(version_number: str):
    """版本号排序键：取前导数字段（2.0.6+mc1.21.1 → (2,0,6)）。
    Sort key: leading numeric segments of the version number."""
    parts = re.findall(r'\d+', version_number.split('+')[0])
    return tuple(int(p) for p in parts[:3]) + (0,) * (3 - min(len(parts), 3))


def http_get(url: str) -> bytes:
    req = urllib.request.Request(url, headers={'User-Agent': USER_AGENT})
    with urllib.request.urlopen(req, timeout=60) as resp:
        return resp.read()


def sha1_of(path: Path) -> str:
    h = hashlib.sha1()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()


def fetch_version_list(match: str | None) -> list[dict]:
    versions = json.loads(http_get(API_VERSIONS))
    picked = []
    for v in versions:
        if LOADER not in v.get('loaders', []):
            continue
        if MC_VERSION not in v.get('game_versions', []):
            continue
        if match and match not in v['version_number']:
            continue
        primary = next((f for f in v.get('files', []) if f.get('primary')), None)
        if primary is None and v.get('files'):
            primary = v['files'][0]
        if primary is None:
            print(f"  [跳过/skip] {v['version_number']}: 无文件/no files")
            continue
        picked.append({
            'version_number': v['version_number'],
            'version_id': v['id'],
            'date_published': v.get('date_published', '')[:10],
            'filename': primary['filename'],
            'url': primary['url'],
            'sha1': primary.get('hashes', {}).get('sha1', ''),
            'size': primary.get('size', 0),
        })
    picked.sort(key=lambda e: semver_key(e['version_number']))
    return picked


def extract_companion(sable_jar: Path, dest_dir: Path) -> tuple[str, str]:
    """从 sable jar 的 jarJar 嵌套包抽 companion；返回 (文件名, 来源)。
    Extract the companion jar from the sable jar's jarJar nesting; returns
    (filename, provenance)."""
    with zipfile.ZipFile(sable_jar) as z:
        nested = [n for n in z.namelist()
                  if n.endswith('.jar') and 'companion' in Path(n).name.lower()]
        if len(nested) == 1:
            name = Path(nested[0]).name
            target = dest_dir / name
            if not target.exists():
                with z.open(nested[0]) as src, open(target, 'wb') as out:
                    shutil.copyfileobj(src, out)
            return name, 'extracted'
        if len(nested) > 1:
            raise RuntimeError(f'{sable_jar.name}: 嵌套 companion 不唯一 {nested}')
    # 回落基线 companion：javac 编译期需要 companion 类型在场
    # Fallback to the baseline companion so javac still sees companion types.
    name = BASELINE_COMPANION.name
    target = dest_dir / name
    if not target.exists():
        shutil.copy2(BASELINE_COMPANION, target)
    return name, 'fallback-libs'


def ensure_version(entry: dict, force: bool) -> dict:
    version_number = entry['version_number']
    dest_dir = JARS_DIR / version_number
    dest_dir.mkdir(parents=True, exist_ok=True)
    jar_path = dest_dir / entry['filename']

    if jar_path.exists() and not force:
        actual = sha1_of(jar_path)
        if entry['sha1'] and actual != entry['sha1']:
            print(f"  [校验失败/rehash] {version_number}: SHA1 不符，重新下载")
            jar_path.unlink()
        else:
            print(f"  [已就位/cached] {version_number}")
    if not jar_path.exists():
        print(f"  [下载/downloading] {version_number} ← {entry['url']}")
        tmp = jar_path.with_suffix('.part')
        tmp.write_bytes(http_get(entry['url']))
        if entry['sha1']:
            actual = sha1_of(tmp)
            if actual != entry['sha1']:
                tmp.unlink()
                raise RuntimeError(f"{version_number}: 下载后 SHA1 不符 {actual} != {entry['sha1']}")
        tmp.rename(jar_path)

    companion_name, companion_source = extract_companion(jar_path, dest_dir)
    return {
        'version_number': version_number,
        'version_id': entry['version_id'],
        'date_published': entry['date_published'],
        'sable_jar': entry['filename'],
        'sable_sha1': entry['sha1'],
        'companion_jar': companion_name,
        'companion_source': companion_source,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description='拉取 Sable 全版本 jar 到 tools/sable-compat/jars/')
    parser.add_argument('--force', action='store_true', help='忽略已有文件重新下载')
    parser.add_argument('--match', help='只处理版本号包含此子串的版本')
    args = parser.parse_args()

    if not BASELINE_COMPANION.exists():
        print(f'错误/error: 基线 companion 不在 {BASELINE_COMPANION}，无法回落', file=sys.stderr)
        return 2

    print(f'[Modrinth] 查询 {API_VERSIONS} …')
    entries = fetch_version_list(args.match)
    if not entries:
        print('没有匹配的版本 / no matching versions', file=sys.stderr)
        return 1
    print(f'[Modrinth] NeoForge {MC_VERSION} 线共 {len(entries)} 个版本 / versions')

    records = []
    for entry in entries:
        records.append(ensure_version(entry, args.force))

    manifest = {
        'source': 'https://api.modrinth.com/project/sable',
        'loader': LOADER,
        'minecraft_version': MC_VERSION,
        'baseline_companion': BASELINE_COMPANION.name,
        'versions': records,
    }
    MANIFEST_PATH.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
    print(f'[完成/done] 清单已写入 {MANIFEST_PATH.relative_to(REPO_ROOT)}（{len(records)} 个版本）')
    fallbacks = [r['version_number'] for r in records if r['companion_source'] != 'extracted']
    if fallbacks:
        print(f'[注意/note] companion 回落 libs/ 基线的版本: {", ".join(fallbacks)}')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
