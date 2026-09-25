# -*- coding: utf-8 -*-
"""
动力传感器 kinetic_gauge 资产生成脚本 —— 仓库内唯一真相源。
Kinetic gauge asset generator — the repo's single source of truth.

两个**手工源模型 + 各自一张贴图**被原样采用，脚本不做任何几何派生：
Two hand-made source models with one texture each, adopted as-is; nothing is derived:
  assets-src/kinetic_gauge/chuangan.json + chuangan.png
      → models/block/kinetic_gauge.json + textures/block/kinetic_gauge.png            （基础 / 讲台变体）
  assets-src/kinetic_gauge/chuangan_shaft_y.json + chuangan_y.png
      → models/block/kinetic_gauge_shaft_y.json + textures/block/kinetic_gauge_shaft_y.png（竖直轴变体）
其余产物：物品模型、blockstates 12 状态表、战利品表、配方。
Also: item model, the 12-state blockstate table, loot table, recipe.

契约（画模型必须满足，脚本强制校验，违反即报错且不写盘）/ contract, enforced before writing:
  1. **必须有一个元素命名 `screen`**，它就是屏幕件；屏幕面 = 该元素两个大面里**离方块中心更远**
     的那个（朝外）。命名是主标识 —— 换贴图/换图块都不会破坏识别
     / exactly one element named `screen`; the screen face is the farther-from-centre of its
     two large faces (the outward one). Naming is the primary identifier.
  2. 屏幕件的贴图覆盖：屏幕面不得取到**全透明**像素（其余面同样检查；部分透明只警告）。
     需要 Pillow，缺失时跳过 / no face may sample fully transparent texels (Pillow; else skipped)
  3. 框沿件建议命名 `bezel*`（缺失只警告）/ bezel elements should be named `bezel*` (warning)
  4. 元素的**旋转后包围盒**必须在方块内（±1 单位内只警告，超出才报错）。按渲染后的真实范围判定，
     不看未旋转的 from/to —— 斜板的原始盒子常在方块外，转过去才落回方块内
     / the AABB *after* the element rotation must lie inside the cube (warning within ±1, error
     beyond); raw from/to is not the rendered extent

锚点 / screen anchors：脚本反算屏幕面的 中心/法线/右/上/半宽高 并打印。**基必须是右手系**
（`right × up = normal`，渲染器据此构造 det=+1 的矩阵，否则文字镜像）：右 = 两条面内轴里更水平的
那条，若右手系不成立则取反。渲染器常量 `KineticGaugeStates.PANEL_BASE` / `PANEL_SHAFT_Y` 仍写死在
Java 里，由 `KineticGaugeStatesTest` 守住（锚点落在屏幕面上、窗口不超出面、基为右手系）。
The script derives and prints each screen plane with a right-handed basis; the renderer constants
stay in Java and are pinned by KineticGaugeStatesTest.

旋转约定（原版历史怪癖，两条路径方向相反）/ rotation conventions (vanilla quirk, opposite signs):
  - 元素 rotation：`FaceBakery.applyElementRotation` 用 `Quaternionf.rotationAxis(+angle, axis)`
    → **右手系**（从正轴看逆时针）。本脚本的 rot_dir 实现的就是这一套。
  - blockstate 变体的 x/y：**左手系**（从正轴看顺时针 = JOML 负角），渲染器里 `-yRotation` 即此。

运行 / usage：
    python tools/gen_gauge_assets.py            # 校验源模型并生成全部资产 / validate + write
    python tools/gen_gauge_assets.py --check    # 只校验（源模型契约 + 产物一致性），不写盘 / verify only
"""
import json
import math
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC_DIR = os.path.join(ROOT, "assets-src", "kinetic_gauge")
ASSETS = os.path.join(ROOT, "src", "main", "resources", "assets", "create_schematic_compute")
DATA = os.path.join(ROOT, "src", "main", "resources", "data", "create_schematic_compute")

BASE_MODEL = "kinetic_gauge"
SHAFT_Y_MODEL = "kinetic_gauge_shaft_y"

# 每个变体：源模型 / 源贴图 / 产物贴图 / 产物里引用的贴图名
VARIANTS = {
    BASE_MODEL: {
        "model_src": os.path.join(SRC_DIR, "chuangan.json"),
        "tex_src": os.path.join(SRC_DIR, "chuangan.png"),
        "tex_product": os.path.join(ASSETS, "textures", "block", f"{BASE_MODEL}.png"),
        "tex_ref": f"create_schematic_compute:block/{BASE_MODEL}",
    },
    SHAFT_Y_MODEL: {
        "model_src": os.path.join(SRC_DIR, "chuangan_shaft_y.json"),
        "tex_src": os.path.join(SRC_DIR, "chuangan_y.png"),
        "tex_product": os.path.join(ASSETS, "textures", "block", f"{SHAFT_Y_MODEL}.png"),
        "tex_ref": f"create_schematic_compute:block/{SHAFT_Y_MODEL}",
    },
}

SCREEN_NAME = "screen"                  # 屏幕件必须叫这个名字 / the screen element must be named this
BEZEL_RE = re.compile(r"^bezel", re.IGNORECASE)
BOUNDS_SOFT = 1.0                       # 越出方块多少单位以内只警告 / soft margin outside the cube
BOUNDS_EPS = 1e-6

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")

try:
    from PIL import Image                # 仅用于贴图覆盖校验 / texture coverage check only
except Exception:                        # pragma: no cover
    Image = None


# ─────────────────────────── 源模型采用 / adopt sources ───────────────────────────

def normalize_source(path, tex_ref):
    """采用手工源模型：丢掉 Blockbench 的 credit/groups，把**所有**贴图键改写到产物贴图
    （源里可能是 `"0"`/`"1"`/绝对路径，一律归一）。"""
    if not os.path.exists(path):
        raise SystemExit(f"缺少手工源模型 / missing source model: {os.path.relpath(path, ROOT)}")
    m = json.load(open(path, encoding="utf-8"))
    m.pop("credit", None)
    m.pop("groups", None)
    keys = list((m.get("textures") or {"0": None, "particle": None}).keys())
    m["textures"] = {k: tex_ref for k in keys}
    return m


# ─────────────────── 几何工具（与 Java 测试同一套约定）/ geometry helpers ───────────────────

# 面基向量：{外法线, 面内轴 A, 面内轴 B}；FACE_DIMS = (A 的维, B 的维, 法线的维)
FACE_BASIS = {
    "north": ((0, 0, -1), (1, 0, 0), (0, 1, 0)),
    "south": ((0, 0, 1), (1, 0, 0), (0, 1, 0)),
    "east": ((1, 0, 0), (0, 0, 1), (0, 1, 0)),
    "west": ((-1, 0, 0), (0, 0, 1), (0, 1, 0)),
    "up": ((0, 1, 0), (1, 0, 0), (0, 0, 1)),
    "down": ((0, -1, 0), (1, 0, 0), (0, 0, 1)),
}
FACE_DIMS = {"north": (0, 1, 2), "south": (0, 1, 2), "east": (2, 1, 0),
             "west": (2, 1, 0), "up": (0, 2, 1), "down": (0, 2, 1)}


def rot_dir(v, axis, ang):
    """方向向量按**元素 rotation** 约定旋转（原版 = 右手系，见文件头）。"""
    if axis is None or not ang:
        return tuple(v)
    a = math.radians(ang)
    c, s = math.cos(a), math.sin(a)
    x, y, z = v
    if axis == "x":
        return (x, y * c - z * s, y * s + z * c)
    if axis == "y":
        return (x * c + z * s, y, -x * s + z * c)
    if axis == "z":
        return (x * c - y * s, x * s + y * c, z)
    raise SystemExit(f"未知旋转轴 / unknown axis: {axis}")


def rot_pt(p, axis, ang, org):
    q = tuple(p[i] - org[i] for i in range(3))
    r = rot_dir(q, axis, ang)
    return tuple(r[i] + org[i] for i in range(3))


def cross(a, b):
    return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])


def dot(a, b):
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]


def neg(v):
    return (-v[0], -v[1], -v[2])


def fmt(v):
    return "(" + ", ".join(f"{x:.4f}" for x in v) + ")"


# ─────────────────────────── 契约校验 / contract validation ───────────────────────────

def find_screen_element(model, label):
    """按命名找到屏幕件（命名是主标识）。/ locate the screen element by name (primary id)."""
    hits = [el for el in model["elements"] if (el.get("name") or "").strip().lower() == SCREEN_NAME]
    if len(hits) != 1:
        names = [el.get("name") for el in model["elements"] if el.get("name")]
        raise SystemExit(
            f"[{label}] 必须恰好有一个元素命名 `{SCREEN_NAME}`，实测 {len(hits)} 个"
            f"（现有命名: {names}）/ exactly one element must be named `{SCREEN_NAME}`")
    return hits[0]


def pick_screen_face(el):
    """屏幕面 = 屏幕件两个大面里中心离方块中心 (8,8,8) 更远的那个（朝外）。
    The screen face = the farther-from-centre of the element's two large faces."""
    ext = [abs(el["to"][k] - el["from"][k]) for k in range(3)]
    thin = min(range(3), key=lambda k: ext[k])
    candidates = [f for f, dims in FACE_DIMS.items() if dims[2] == thin]
    rot = el.get("rotation")
    axis = rot.get("axis") if rot else None
    ang = rot.get("angle", 0) if rot else 0
    org = rot.get("origin", [0, 0, 0]) if rot else [0, 0, 0]
    best, best_d = None, -1.0
    for fname in candidates:
        dim_a, dim_b, dim_n = FACE_DIMS[fname]
        n_l = FACE_BASIS[fname][0]
        half_n = abs(el["to"][dim_n] - el["from"][dim_n]) / 2
        mid = [(el["from"][k] + el["to"][k]) / 2 for k in range(3)]
        c = rot_pt([mid[k] + n_l[k] * half_n for k in range(3)], axis, ang, org)
        d = sum((c[k] - 8.0) ** 2 for k in range(3))
        if d > best_d:
            best, best_d = fname, d
    return best


def element_rotated_aabb(el):
    """元素**旋转后**的世界包围盒（未旋转的 from/to 不能用来判越界 —— 斜板的原始盒子常常在方块外，
    转过去才落回方块内）。/ The AABB after the element rotation; raw from/to is not the rendered extent."""
    f, t = el["from"], el["to"]
    rot = el.get("rotation")
    axis = rot.get("axis") if rot else None
    ang = rot.get("angle", 0) if rot else 0
    org = rot.get("origin", [0, 0, 0]) if rot else [0, 0, 0]
    corners = [(x, y, z) for x in (f[0], t[0]) for y in (f[1], t[1]) for z in (f[2], t[2])]
    pts = [rot_pt(c, axis, ang, org) for c in corners]
    return ([min(p[k] for p in pts) for k in range(3)],
            [max(p[k] for p in pts) for k in range(3)])


def check_bounds(model, label):
    hard, soft = [], []
    for i, el in enumerate(model["elements"]):
        lo, hi = element_rotated_aabb(el)
        for k in range(3):
            if lo[k] < -BOUNDS_SOFT - BOUNDS_EPS or hi[k] > 16 + BOUNDS_SOFT + BOUNDS_EPS:
                hard.append(f"el[{i}] {'xyz'[k]} 轴 {lo[k]:.2f} .. {hi[k]:.2f}")
            elif lo[k] < -BOUNDS_EPS or hi[k] > 16 + BOUNDS_EPS:
                soft.append((i, k, lo[k], hi[k]))
    if hard:
        raise SystemExit(f"[{label}] 元素（旋转后）超出方块超过 {BOUNDS_SOFT} 单位 / far outside the cube:\n  "
                         + "\n  ".join(hard))
    if soft:
        lo = min(min(v[2] for v in soft), 16 - max(v[3] for v in soft))
        print(f"[{label}] WARN: {len(soft)} 处旋转后略微越出方块（最大 {abs(lo):.2f} 单位）"
              f" / slightly outside the cube after rotation")
    else:
        print(f"[{label}] bounds OK: 所有元素旋转后都在 0..16 方块内"
              f" / every element stays inside the cube after rotation")


def derive_screen_plane(el, fname):
    """反算屏幕面平面：中心/法线/右/上 + 面内半宽高；基保证右手系 right×up=normal。
    Derive the screen plane; the basis is right-handed (right × up = normal)."""
    f, t = el["from"], el["to"]
    rot = el.get("rotation")
    axis = rot.get("axis") if rot else None
    ang = rot.get("angle", 0) if rot else 0
    org = rot.get("origin", [0, 0, 0]) if rot else [0, 0, 0]

    n_l, a_l, b_l = FACE_BASIS[fname]
    dim_a, dim_b, dim_n = FACE_DIMS[fname]
    n = rot_dir(n_l, axis, ang)
    a = rot_dir(a_l, axis, ang)
    b = rot_dir(b_l, axis, ang)
    half_a = abs(t[dim_a] - f[dim_a]) / 2
    half_b = abs(t[dim_b] - f[dim_b]) / 2
    half_n = abs(t[dim_n] - f[dim_n]) / 2
    mid = [(f[k] + t[k]) / 2 for k in range(3)]
    centre = rot_pt([mid[k] + n_l[k] * half_n for k in range(3)], axis, ang, org)

    # 右 = 更水平的那条面内轴；再强制 up 朝上、基为右手系
    if abs(a[1]) <= abs(b[1]):
        right, up, half_right, half_up = a, b, half_a, half_b
    else:
        right, up, half_right, half_up = b, a, half_b, half_a
    if up[1] < 0:
        up = neg(up)
    if dot(cross(right, up), n) < 0:
        right = neg(right)
    assert abs(dot(cross(right, up), n) - 1.0) < 1e-6, "面板基不是右手系 / basis is not right-handed"
    return {"centre": centre, "normal": n, "right": right, "up": up,
            "half_right": half_right, "half_up": half_up}


def unoccluded_span(model, screen_el, fname, margin=0.15):
    """屏幕面被 `bezel*` 元素盖住边缘后**真正可见**的区间（模型单位，沿面的两条面内轴）。
    排版窗口必须落在这里面，否则文字/进度条会压到框沿上（2026-09-13 实测："超出左右一点点"）。
    The span of the screen face that the bezel* elements do NOT cover — the layout window must stay
    inside it, otherwise text/bar spill onto the bezel."""
    plane = derive_screen_plane(screen_el, fname)
    right, up, centre = plane["right"], plane["up"], plane["centre"]
    lo_r, hi_r = -plane["half_right"], plane["half_right"]
    lo_u, hi_u = -plane["half_up"], plane["half_up"]

    def ab(p):
        d = tuple(p[k] - centre[k] for k in range(3))
        return (dot(d, right), dot(d, up))

    for el in model["elements"]:
        if not BEZEL_RE.match((el.get("name") or "").strip()):
            continue
        rot = el.get("rotation")
        axis = rot.get("axis") if rot else None
        ang = rot.get("angle", 0) if rot else 0
        org = rot.get("origin", [0, 0, 0]) if rot else [0, 0, 0]
        pts = [ab(rot_pt((x, y, z), axis, ang, org))
               for x in (el["from"][0], el["to"][0])
               for y in (el["from"][1], el["to"][1])
               for z in (el["from"][2], el["to"][2])]
        a_lo, a_hi = min(p[0] for p in pts), max(p[0] for p in pts)
        b_lo, b_hi = min(p[1] for p in pts), max(p[1] for p in pts)
        if b_lo < hi_u and b_hi > lo_u:          # 沿「右」轴盖住一端的条（横跨整条轴的侧轨要排除）
            min_end_r = a_lo <= lo_r + 1e-6
            max_end_r = a_hi >= hi_r - 1e-6
            if min_end_r and not max_end_r:
                lo_r = max(lo_r, a_hi)
            if max_end_r and not min_end_r:
                hi_r = min(hi_r, a_lo)
        if a_lo < hi_r and a_hi > lo_r:          # 沿「上」轴盖住一端的条
            min_end_u = b_lo <= lo_u + 1e-6
            max_end_u = b_hi >= hi_u - 1e-6
            if min_end_u and not max_end_u:
                lo_u = max(lo_u, b_hi)
            if max_end_u and not min_end_u:
                hi_u = min(hi_u, b_lo)
    return (lo_r + margin, hi_r - margin, lo_u + margin, hi_u - margin)


def report_anchor(label, el, fname, plane, span=None):
    print(f"[{label}] screen = element {el.get('name')!r} face={fname}")
    print(f"    centre={fmt(plane['centre'])}  normal={fmt(plane['normal'])}")
    print(f"    right ={fmt(plane['right'])}  up    ={fmt(plane['up'])}")
    print(f"    face half extents: right={plane['half_right']:.4f}  up={plane['half_up']:.4f}")
    if span:
        lo_r, hi_r, lo_u, hi_u = span
        print(f"    被框沿遮挡后可见范围 / unoccluded span: "
              f"right {lo_r:.2f} .. {hi_r:.2f}（半宽 {min(-lo_r, hi_r):.2f}）  "
              f"up {lo_u:.2f} .. {hi_u:.2f}（半高 {min(-lo_u, hi_u):.2f}）")
        print(f"    -> 排版窗口上限 / layout window cap: "
              f"{min(-lo_r, hi_r):.2f} × {min(-lo_u, hi_u):.2f}（KineticGaugeStates 里的 halfRight/halfUp）")


def check_bezel_names(model, label):
    if not any(BEZEL_RE.match((el.get("name") or "").strip()) for el in model["elements"]):
        print(f"[{label}] WARN: 没有以 bezel 开头的元素（契约建议命名框沿件）/ no element named bezel*")


def check_texture_coverage(model, label, im):
    """任何面都不许取到全透明像素；部分透明只警告。
    No face may sample fully transparent texels; partial transparency is a warning."""
    px = im.load()
    scale = im.size[0] / 16.0
    errors, partial_faces, partial_pixels = [], [], 0
    for i, el in enumerate(model["elements"]):
        for fname, fd in el["faces"].items():
            u0, v0, u1, v1 = fd["uv"]
            x0, x1 = sorted((int(round(u0 * scale)), int(round(u1 * scale))))
            y0, y1 = sorted((int(round(v0 * scale)), int(round(v1 * scale))))
            x1, y1 = max(x1, x0 + 1), max(y1, y0 + 1)
            holes = partial = 0
            for y in range(y0, y1):
                for x in range(x0, x1):
                    alpha = px[x, y][3]
                    if alpha == 0:
                        holes += 1
                    elif alpha < 255:
                        partial += 1
            if holes:
                errors.append(f"el[{i}] {fname} uv={fd['uv']} texel=({x0},{y0})-({x1},{y1}) 有 {holes} 个全透明像素")
            elif partial:
                partial_faces.append(f"el[{i}] {fname}")
                partial_pixels += partial
    if errors:
        raise SystemExit(f"[{label}] 贴图覆盖校验失败（取到了未绘制区域）/ texture coverage failed:\n  "
                         + "\n  ".join(errors))
    if partial_faces:
        print(f"[{label}] WARN: {len(partial_faces)} 个面取到半透明像素（共 {partial_pixels} 像素）："
              f"{', '.join(partial_faces[:6])}" + (" ..." if len(partial_faces) > 6 else ""))


def validate_variant(model, label, tex_src):
    el = find_screen_element(model, label)
    fname = pick_screen_face(el)
    check_bounds(model, label)
    check_bezel_names(model, label)
    plane = derive_screen_plane(el, fname)
    report_anchor(label, el, fname, plane, unoccluded_span(model, el, fname))
    if Image is not None and os.path.exists(tex_src):
        check_texture_coverage(model, label, Image.open(tex_src).convert("RGBA"))
    return plane


# ─────────────────────────── 其余产物 / remaining products ───────────────────────────

# facing = 显示面朝向（见 KineticGaugeStates#facingForPlacement）；
# axis_along_first 按 DirectionalAxisKineticBlock.getRotationAxis 决定轴。
# 竖直轴 4 态（north/south A=false、east/west A=true）用 _shaft_y 变体。
# 同一旋转轴的 4 态必须 4 个互不重合的 (x,y)（KineticGaugeStates 有同样约束），
# 否则扳手绕轴循环会出现「转了但看起来没变」。
# The four states sharing a shaft axis need four distinct (x,y) pairs.
# 横置 W/N/E/S 全部 x=0 上仰（点上/下偏航四步都朝玩家）；UP/DOWN 用 x=180。
# 同轴四态 (x,y) 互不重合。See KineticGaugeStates.xRotation/yRotation.
BS = {
    "facing=north,axis_along_first=true":  (BASE_MODEL, 0, 90),
    "facing=north,axis_along_first=false": (SHAFT_Y_MODEL, 0, 90),
    "facing=south,axis_along_first=true":  (BASE_MODEL, 0, 270),
    "facing=south,axis_along_first=false": (SHAFT_Y_MODEL, 0, 270),
    "facing=east,axis_along_first=false":  (BASE_MODEL, 0, 180),
    "facing=east,axis_along_first=true":   (SHAFT_Y_MODEL, 0, 180),
    "facing=west,axis_along_first=false":  (BASE_MODEL, 0, 0),
    "facing=west,axis_along_first=true":   (SHAFT_Y_MODEL, 0, 0),
    "facing=up,axis_along_first=false":    (BASE_MODEL, 180, 180),
    "facing=up,axis_along_first=true":     (BASE_MODEL, 180, 90),
    "facing=down,axis_along_first=false":  (BASE_MODEL, 180, 0),
    "facing=down,axis_along_first=true":   (BASE_MODEL, 180, 270),
}


def make_blockstate():
    variants = {}
    for key, (model, x, y) in BS.items():
        rec = {"model": f"create_schematic_compute:block/{model}"}
        if x:
            rec["x"] = x
        if y:
            rec["y"] = y
        variants[key] = rec
    return {"variants": variants}


def make_item_model():
    return {
        "parent": f"create_schematic_compute:block/{BASE_MODEL}",
        "display": {
            "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.375, 0.375, 0.375]},
            "thirdperson_lefthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.375, 0.375, 0.375]},
            "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 0, 0], "scale": [0.4, 0.4, 0.4]},
            "firstperson_lefthand": {"rotation": [0, 225, 0], "translation": [0, 0, 0], "scale": [0.4, 0.4, 0.4]},
            "ground": {"translation": [0, 3, 0], "scale": [0.25, 0.25, 0.25]},
            "gui": {"rotation": [30, 225, 0], "scale": [0.625, 0.625, 0.625]},
        },
    }


def make_loot_table():
    return {
        "type": "minecraft:block",
        "pools": [{"rolls": 1,
                   "entries": [{"type": "minecraft:item", "name": "create_schematic_compute:kinetic_gauge"}],
                   "conditions": [{"condition": "minecraft:survives_explosion"}]}],
    }


def make_recipe():
    return {
        "type": "minecraft:crafting_shaped",
        "category": "misc",
        "pattern": ["ISI", "SCS", "ISI"],
        "key": {
            "I": {"item": "minecraft:iron_ingot"},
            "S": {"item": "create:shaft"},
            "C": {"item": "create:brass_casing"},
        },
        "result": {"id": "create_schematic_compute:kinetic_gauge", "count": 1},
    }


# ─────────────────────── 产物组装 / product assembly ───────────────────────

def build_products():
    print(f"texture coverage check: {'on' if Image is not None else 'SKIPPED (无 Pillow)'}")
    products = {}
    for label, cfg in VARIANTS.items():
        model = normalize_source(cfg["model_src"], cfg["tex_ref"])
        validate_variant(model, label, cfg["tex_src"])
        products[os.path.join(ASSETS, "models", "block", f"{label}.json")] = \
            json.dumps(model, ensure_ascii=False, indent="\t")
        products[cfg["tex_product"]] = open(cfg["tex_src"], "rb").read()

    products[os.path.join(ASSETS, "blockstates", f"{BASE_MODEL}.json")] = \
        json.dumps(make_blockstate(), ensure_ascii=False, indent="\t")
    products[os.path.join(ASSETS, "models", "item", f"{BASE_MODEL}.json")] = \
        json.dumps(make_item_model(), ensure_ascii=False, indent="\t")
    products[os.path.join(DATA, "loot_table", "blocks", f"{BASE_MODEL}.json")] = \
        json.dumps(make_loot_table(), ensure_ascii=False, indent="\t")
    products[os.path.join(DATA, "recipe", f"{BASE_MODEL}.json")] = \
        json.dumps(make_recipe(), ensure_ascii=False, indent="\t")
    return products


def main():
    check_only = "--check" in sys.argv[1:]
    products = build_products()

    if check_only:
        bad = 0
        for path, want in products.items():
            rel = os.path.relpath(path, ROOT)
            if not os.path.exists(path):
                print(f"MISSING  {rel}")
                bad += 1
                continue
            got = open(path, "rb").read() if isinstance(want, bytes) else open(path, encoding="utf-8").read()
            if got == want:
                print(f"OK       {rel}")
            else:
                print(f"DIFF     {rel}")
                bad += 1
        print("check:", "all products match the generator" if bad == 0 else f"{bad} product(s) differ")
        return 1 if bad else 0

    for path, content in products.items():
        os.makedirs(os.path.dirname(path), exist_ok=True)
        if isinstance(content, bytes):
            with open(path, "wb") as fh:
                fh.write(content)
        else:
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(content)
    print("OK: assets written")
    return 0


if __name__ == "__main__":
    sys.exit(main())
