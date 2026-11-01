# -*- coding: utf-8 -*-
"""
Sable 链接点探针 —— 逐条核对本模组依赖的 Sable 成员在候选 jar 里是否还在。
Sable linkage probe — verifies every Sable member our mod links against still exists
in a candidate jar pair.

为什么编译之外还要这个探针 / why a probe beyond compilation:
  1. mixin 的 @Inject(method="shouldCameraRotate") 是**字符串目标**，javac 不校验 ——
     而该 mixin 配置 required:false，目标没了只会静默失效（P2-21 的病灶形态）
     / mixin string targets are not compile-checked, and the config is required:false,
     so a vanished target fails silently
  2. 编译报错停在第一个错；探针一次给出**全清单**的逐点结论，断点一目了然
     / compilation aborts at the first error; the probe reports every point at once
  3. 不跑 gradle、秒级完成 / no gradle, runs in seconds

实现：纯 Python 解析 class 文件常量池与字段/方法表（不依赖 javap / JVM）。
Implementation: a plain-Python class-file reader (no javap / JVM dependency).

用法 / usage：
    python tools/sable-compat/check_linkage.py                  # 核对 libs/ 基线对（探针自证）
    python tools/sable-compat/check_linkage.py --dir <jar目录>   # 核对一个候选对
    python tools/sable-compat/check_linkage.py --all            # 核对 jars/manifest.json 全部版本
    python tools/sable-compat/check_linkage.py --dump <类FQN>   # 打印某类的成员清单（维护链接点用）

契约 / contract：
  1. 检查口径 = linkage-points.json（数据驱动；新增接触面先补清单再补代码）
     / the checklist lives in linkage-points.json; extend it before extending the surface
  2. 匹配是「成员名 + 描述符」精确匹配；desc 可省（仅存在性），重载点必须写 desc
     / exact name+descriptor match; descriptors are optional except for overload points
  3. 退出码 = 失败点数（0 全绿）/ exit code = number of failed points
"""
import argparse
import functools
import json
import struct
import sys
import zipfile
from pathlib import Path

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
sys.stderr.reconfigure(encoding='utf-8', errors='replace')

REPO_ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
POINTS_PATH = HERE / 'linkage-points.json'
JARS_DIR = HERE / 'jars'
BASELINE_DIR = REPO_ROOT / 'libs'


# ---------------------------------------------------------------- class 文件读取
# --------------------------------------------------------- class-file reading

def _read_cp(data: bytes, pos: int):
    """读常量池，返回 (池表[1..n], 新pos)。Long/Double 占两槽（JVM 规范）。
    Reads the constant pool; Long/Double occupy two slots per the JVM spec."""
    (count,) = struct.unpack_from('>H', data, pos)
    pos += 2
    pool: list = [None] * count
    i = 1
    while i < count:
        tag = data[pos]
        pos += 1
        if tag == 1:  # Utf8
            (length,) = struct.unpack_from('>H', data, pos)
            pos += 2
            pool[i] = data[pos:pos + length].decode('utf-8', errors='replace')
            pos += length
        elif tag in (3, 4):  # Integer, Float
            pos += 4
        elif tag in (5, 6):  # Long, Double —— 占两槽 / two slots
            pos += 8
            i += 1
        elif tag in (7, 8, 16, 19, 20):  # Class, String, MethodType, Module, Package
            pos += 2
        elif tag in (9, 10, 11, 12, 17, 18):  # refs, NameAndType, Dynamic, InvokeDynamic
            pos += 4
        elif tag == 15:  # MethodHandle
            pos += 3
        else:
            raise ValueError(f'未知常量池 tag {tag} @ {pos - 1}')
        i += 1
    return pool, pos


def class_members(jar_path: Path, class_fqn: str) -> dict:
    """返回某 jar 内某类的成员表：{'class': bool, 'fields': {(名, 描述符)}, 'methods': {...}}。
    Returns the member table of a class inside a jar."""
    entry_name = class_fqn.replace('.', '/') + '.class'
    with zipfile.ZipFile(jar_path) as z:
        try:
            data = z.read(entry_name)
        except KeyError:
            return {'class': False, 'fields': set(), 'methods': set()}
    if data[:4] != b'\xca\xfe\xba\xbe':
        raise ValueError(f'{jar_path.name}!{entry_name}: 非法 class 头')
    pool, pos = _read_cp(data, 8)
    pos += 2 + 2 + 2  # access_flags, this_class, super_class
    (iface_count,) = struct.unpack_from('>H', data, pos)
    pos += 2 + 2 * iface_count

    members = {'class': True, 'fields': set(), 'methods': set()}
    for kind in ('fields', 'methods'):
        (count,) = struct.unpack_from('>H', data, pos)
        pos += 2
        for _ in range(count):
            _access, name_idx, desc_idx, attr_count = struct.unpack_from('>HHHH', data, pos)
            pos += 8
            members[kind].add((pool[name_idx], pool[desc_idx]))
            for _ in range(attr_count):
                _aname, alen = struct.unpack_from('>HI', data, pos)
                pos += 6 + alen
    return members


def collect_members(jar_dir: Path, class_fqn: str) -> dict:
    """在目录内所有 jar 里找类，合并成员表（companion 与 sable 分居两 jar）。
    Scans every jar in the directory (sable + companion live in separate jars)."""
    merged = {'class': False, 'fields': set(), 'methods': set()}
    for jar in sorted(jar_dir.glob('*.jar')):
        m = class_members(jar, class_fqn)
        if m['class']:
            merged['class'] = True
            merged['fields'] |= m['fields']
            merged['methods'] |= m['methods']
    return merged


# ---------------------------------------------------------------- 探针 / probe

@functools.lru_cache(maxsize=1)
def load_points() -> tuple:
    """清单只读一次并缓存：跑批中途有人动/删清单文件不会把矩阵打崩（实证踩过）。
    Loads the checklist once and caches it: mid-run tree churn must not kill the matrix."""
    doc = json.loads(POINTS_PATH.read_text(encoding='utf-8'))
    return tuple(doc['points'])


def check_point(point: dict, jar_dir: Path) -> tuple[bool, str]:
    members = collect_members(jar_dir, point['class'])
    if point['kind'] == 'class':
        return (True, '') if members['class'] else (False, '类缺席 / class missing')
    if not members['class']:
        return False, '类缺席 / class missing'
    bucket = members['fields'] if point['kind'] == 'field' else members['methods']
    name = point['name']
    if 'desc' in point:
        hit = (name, point['desc']) in bucket
    else:
        hit = any(n == name for n, _d in bucket)
    if hit:
        return True, ''
    near = sorted(f'{n}{d}' for n, d in bucket if n == name) or \
           sorted(f'{n}{d}' for n, d in bucket if name in n)
    detail = f'{point["kind"]} {name} 缺席 / missing'
    if near:
        detail += '；同名/近名成员 / near-miss: ' + ', '.join(near[:4])
    return False, detail


def check_dir(jar_dir: Path, points=None) -> list[tuple[dict, bool, str]]:
    points = points if points is not None else load_points()
    return [(p,) + check_point(p, jar_dir) for p in points]


def dump_class(class_fqn: str, jar_dir: Path) -> None:
    members = collect_members(jar_dir, class_fqn)
    if not members['class']:
        print(f'{class_fqn}: 类缺席 / class missing in {jar_dir}')
        return
    print(f'{class_fqn}  @ {jar_dir}')
    for n, d in sorted(members['fields']):
        print(f'  field  {n} {d}')
    for n, d in sorted(members['methods']):
        print(f'  method {n}{d}')


def _print_results(label: str, results: list[tuple[dict, bool, str]]) -> int:
    failed = 0
    print(f'== {label} ==')
    for point, ok, detail in results:
        mark = 'PASS' if ok else 'FAIL'
        if not ok:
            failed += 1
        line = f'  [{mark}] {point["id"]}'
        if detail:
            line += f' —— {detail}'
        print(line)
    print(f'  小结 / total: {len(results) - failed} pass, {failed} fail')
    return failed


def main() -> int:
    parser = argparse.ArgumentParser(description='Sable 链接点探针')
    parser.add_argument('--dir', help='候选 jar 目录（含 sable + companion 两个 jar）')
    parser.add_argument('--all', action='store_true', help='核对 jars/manifest.json 全部版本')
    parser.add_argument('--dump', metavar='类FQN', help='打印某类成员清单后退出')
    args = parser.parse_args()

    if args.dump:
        dump_class(args.dump, Path(args.dir) if args.dir else BASELINE_DIR)
        return 0

    if args.all:
        manifest = json.loads((JARS_DIR / 'manifest.json').read_text(encoding='utf-8'))
        total_failed = 0
        for record in manifest['versions']:
            jar_dir = JARS_DIR / record['version_number']
            total_failed += _print_results(record['version_number'], check_dir(jar_dir))
        return 1 if total_failed else 0

    jar_dir = Path(args.dir) if args.dir else BASELINE_DIR
    if not jar_dir.is_dir():
        print(f'目录不存在 / not a directory: {jar_dir}', file=sys.stderr)
        return 2
    label = f'{jar_dir}' + ('  (libs/ 基线对 / baseline pair)' if not args.dir else '')
    failed = _print_results(label, check_dir(jar_dir))
    return 1 if failed else 0


if __name__ == '__main__':
    raise SystemExit(main())
