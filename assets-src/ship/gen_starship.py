# gen_starship.py — single source of truth for the Starship cuboid model.
#
# Writes, from one part table:
#   src/main/resources/assets/galactica/textures/entity/ship/starship.png   (128x128 box-UV texture)
#   src/main/resources/assets/galactica/textures/item/ship_beacon.png       (16x16 item icon)
#   assets-src/ship/starship.bbmodel                                        (Blockbench "modded_entity" project)
#   assets-src/ship/StarshipModel.parts.txt                                 (Java addBox lines pasted into StarshipModel)
#
# Run from the repo root:  python assets-src/ship/gen_starship.py
# Everything is procedural (seeded), so the output is reproducible and has no licensing strings attached.

import base64
import io
import json
import math
import random
import uuid
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
TEX = 128
rng = random.Random(0x5EED5A1)

# Design space: 1 unit = 1/16 block, y up, +z = nose, origin at the hull centre (renderer pivot, 0.75 block up).
# (name, x0, y0, z0, w, h, d, material, layer, uv_share, pivot)
PARTS = [
    ("fuselage_front", -6, -5, 0, 12, 10, 28, "hull_stripe", "hull", None, None),
    ("fuselage_rear", -6, -5, -28, 12, 10, 28, "hull_stripe", "hull", "fuselage_front", None),
    ("nose", -4, -4, 28, 8, 7, 8, "hull", "hull", None, None),
    ("nose_tip", -2, -2.5, 36, 4, 4, 4, "accent", "hull", None, None),
    ("canopy", -4, 5, 4, 8, 4, 16, "glass", "hull", None, None),
    ("spine", -3, 5, -14, 6, 3, 18, "hull", "hull", None, None),
    ("fin", -1, 8, -26, 2, 8, 12, "accent", "hull", None, None),
    ("belly", -4, -7, -16, 8, 2, 20, "metal", "hull", None, None),
    ("wing_left", 6, -3, -20, 16, 2, 20, "hull", "hull", None, None),
    ("wing_right", -22, -3, -20, 16, 2, 20, "hull", "hull", "wing_left", None),
    ("wingtip_left", 22, -4, -21, 2, 4, 20, "accent", "hull", None, None),
    ("wingtip_right", -24, -4, -21, 2, 4, 20, "accent", "hull", "wingtip_left", None),
    ("engine_left", 7, -5, -34, 7, 7, 20, "engine", "hull", None, None),
    ("engine_right", -14, -5, -34, 7, 7, 20, "engine", "hull", "engine_left", None),
    ("strut_front", -1, -11, 20, 2, 6, 2, "metal", "hull", None, None),
    ("strut_left", 9, -11, -24, 2, 6, 2, "metal", "hull", "strut_front", None),
    ("strut_right", -11, -11, -24, 2, 6, 2, "metal", "hull", "strut_front", None),
    ("foot_front", -2, -12, 19, 4, 1, 4, "metal", "hull", None, None),
    ("foot_left", 8, -12, -25, 4, 1, 4, "metal", "hull", "foot_front", None),
    ("foot_right", -12, -12, -25, 4, 1, 4, "metal", "hull", "foot_front", None),
    # Glow layer: drawn emissive and tinted by thrust. Plumes pivot on the nozzle plane so zScale stretches them aft.
    ("nozzle_left", 8, -4, -35, 5, 5, 1, "glow", "glow", None, None),
    ("nozzle_right", -13, -4, -35, 5, 5, 1, "glow", "glow", "nozzle_left", None),
    ("plume_left", 8.5, -3.5, -47, 4, 4, 12, "plume", "glow", None, (10.5, -1.5, -35)),
    ("plume_right", -12.5, -3.5, -47, 4, 4, 12, "plume", "glow", "plume_left", (-10.5, -1.5, -35)),
]


def uv_size(w, h, d):
    return 2 * (math.ceil(d) + math.ceil(w)), math.ceil(d) + math.ceil(h)


def pack():
    """Skyline packer (lowest fit, largest area first); mirrored parts reuse their twin's UV origin."""
    owners = [p for p in PARTS if p[9] is None]
    size = lambda p: uv_size(p[4], p[5], p[6])
    for key in (lambda p: -size(p)[1], lambda p: -size(p)[0], lambda p: -size(p)[0] * size(p)[1], lambda p: -max(size(p))):
        uv = skyline(sorted(owners, key=key))
        if uv is not None:
            break
    else:
        raise SystemExit("texture overflow")
    for p in PARTS:
        if p[9] is not None:
            uv[p[0]] = uv[p[9]]
    return uv


def skyline(owners):
    sky, uv = [0] * TEX, {}
    for p in owners:
        w, h = uv_size(p[4], p[5], p[6])
        best = None
        for x in range(0, TEX - w + 1):
            y = max(sky[x:x + w])
            if y + h <= TEX and (best is None or y < best[1]):
                best = (x, y)
        if best is None:
            return None
        uv[p[0]] = best
        for x in range(best[0], best[0] + w):
            sky[x] = best[1] + h
    return uv


def faces(u, v, w, h, d):
    w, h, d = math.ceil(w), math.ceil(h), math.ceil(d)
    return {
        "up": (u + d, v, w, d), "down": (u + d + w, v, w, d),
        "side_a": (u, v + d, d, h), "front": (u + d, v + d, w, h),
        "side_b": (u + d + w, v + d, d, h), "back": (u + 2 * d + w, v + d, w, h),
    }


def clamp(c):
    return max(0, min(255, int(c)))


def shade(rgb, k):
    return tuple(clamp(c * k) for c in rgb)


HULL, SEAM, EDGE = (232, 234, 238), (176, 181, 190), (150, 156, 166)
ORANGE, ORANGE_DARK = (240, 122, 26), (186, 84, 14)
GLASS, GLASS_HI = (24, 32, 46), (78, 112, 146)
METAL, METAL_DARK = (92, 98, 108), (60, 64, 72)
CYAN, CYAN_CORE = (34, 184, 224), (170, 250, 255)


def paint_face(img, rect, material, face):
    x0, y0, w, h = rect
    px = img.load()
    for j in range(h):
        for i in range(w):
            n = rng.uniform(-0.025, 0.025)
            edge = i == 0 or j == 0 or i == w - 1 or j == h - 1
            a = 255
            if material in ("hull", "hull_stripe"):
                c = HULL
                if (i % 8 == 7) or (j % 6 == 5):
                    c = SEAM
                if material == "hull_stripe" and face.startswith("side") and h >= 6 and j in (h // 2 - 1, h // 2):
                    c = ORANGE
                if edge:
                    c = EDGE
                c = shade(c, 1 + n)
            elif material == "accent":
                c = ORANGE_DARK if (edge or i % 6 == 5) else ORANGE
                c = shade(c, 1 + n)
            elif material == "glass":
                c = GLASS_HI if (i + j) % 11 in (0, 1) and j < h * 0.6 else GLASS
                if edge:
                    c = (52, 58, 70)
                c = shade(c, 1 + n)
            elif material in ("metal", "engine"):
                c = METAL_DARK if (edge or j % 5 == 4) else METAL
                if material == "engine" and face == "back":
                    r = math.hypot(i - (w - 1) / 2, j - (h - 1) / 2)
                    c = (28, 30, 34) if r < min(w, h) / 2 - 1 else METAL_DARK
                c = shade(c, 1 + n)
            elif material == "glow":
                r = math.hypot(i - (w - 1) / 2, j - (h - 1) / 2) / max(1, min(w, h) / 2)
                t = max(0.0, 1 - r)
                c = tuple(clamp(CYAN[k] + (CYAN_CORE[k] - CYAN[k]) * t) for k in range(3))
            elif material == "plume":
                # Sides and top/bottom fade along the long (d) axis; the end caps are a soft disc.
                if face in ("front", "back"):
                    r = math.hypot(i - (w - 1) / 2, j - (h - 1) / 2) / max(1, w / 2)
                    a = clamp(220 * max(0.0, 1 - r))
                    t = 1.0
                else:
                    along = (i / max(1, w - 1)) if face.startswith("side") else (j / max(1, h - 1))
                    a = clamp(210 * (1 - along) ** 1.5 * (0.85 + rng.uniform(0, 0.15)))
                    t = 1 - along
                c = tuple(clamp(CYAN[k] + (CYAN_CORE[k] - CYAN[k]) * t) for k in range(3))
            else:
                raise SystemExit(material)
            px[x0 + i, y0 + j] = (*c, a)


def build_texture(uv):
    img = Image.new("RGBA", (TEX, TEX), (0, 0, 0, 0))
    for p in PARTS:
        if p[9] is not None:
            continue
        for face, rect in faces(*uv[p[0]], p[4], p[5], p[6]).items():
            paint_face(img, rect, p[7], face)
    return img


def beacon_icon():
    """16x16 item: a white hull-panel puck with an orange chevron and a cyan beacon lamp, 1 px dark outline."""
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    body = set()
    for y in range(16):
        for x in range(16):
            dx, dy = x - 7.5, y - 9.0
            if (dx / 6.6) ** 2 + (dy / 4.6) ** 2 <= 1.0:
                body.add((x, y))
    lamp = {(x, y) for x in range(6, 10) for y in range(1, 6)}
    shape = body | lamp
    for (x, y) in shape:
        if (x, y) in lamp:
            t = 1 - abs(x - 7.5) / 2.5
            c = tuple(clamp(CYAN[k] + (CYAN_CORE[k] - CYAN[k]) * t * (1 - (y - 1) / 6)) for k in range(3))
        else:
            c = HULL if y < 10 else (196, 200, 208)
            if y == 9 and 3 <= x <= 12:
                c = SEAM
            if y in (11, 12) and abs(x - 7.5) <= 4 - (y - 11) * 1.5 and abs(x - 7.5) >= 1:
                c = ORANGE
        px[x, y] = (*c, 255)
    outline = (40, 44, 54, 255)
    for (x, y) in shape:
        for nx, ny in ((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)):
            if (nx, ny) not in shape:
                px[x, y] = outline
                break
    return img


def to_model(p):
    """Design (y up) -> ModelPart space (y down, renderer applies scale(-1,-1,1) so x mirrors too)."""
    name, x0, y0, z0, w, h, d = p[:7]
    mx0, my0 = -(x0 + w), -(y0 + h)
    if p[10] is None:
        return (0.0, 0.0, 0.0), (mx0, my0, z0)
    px, py, pz = p[10]
    ox, oy, oz = -px, -py, pz
    return (ox, oy, oz), (mx0 - ox, my0 - oy, z0 - oz)


def java_lines(uv):
    out = []
    for p in PARTS:
        pivot, (bx, by, bz) = to_model(p)
        u, v = uv[p[0]]
        pose = "PartPose.ZERO" if p[10] is None else f"PartPose.offset({pivot[0]:.1f}F, {pivot[1]:.1f}F, {pivot[2]:.1f}F)"
        out.append(f'{p[8]}:{p[0]}: .texOffs({u}, {v}).addBox({bx:.1f}F, {by:.1f}F, {bz:.1f}F, {p[4]:.1f}F, {p[5]:.1f}F, {p[6]:.1f}F), {pose}')
    return "\n".join(out)


def bbmodel(uv, tex_png):
    elements, groups = [], {"hull": [], "glow": []}
    for p in PARTS:
        name, x0, y0, z0, w, h, d = p[:7]
        uid = str(uuid.UUID(int=rng.getrandbits(128)))
        # Blockbench entity space: y up with the feet at 0 (pivot sits 12 px above).
        frm = [x0, y0 + 12, z0]
        to = [x0 + w, y0 + h + 12, z0 + d]
        origin = [p[10][0], p[10][1] + 12, p[10][2]] if p[10] else [0, 12, 0]
        elements.append({"name": name, "box_uv": True, "rescale": False, "locked": False, "from": frm, "to": to,
                         "autouv": 0, "color": 0, "origin": origin, "uv_offset": list(uv[name]),
                         "faces": {f: {"uv": [0, 0, 0, 0], "texture": 0} for f in ("north", "east", "south", "west", "up", "down")},
                         "type": "cube", "uuid": uid})
        groups[p[8]].append(uid)
    outliner = [{"name": g, "origin": [0, 12, 0], "color": 0, "uuid": str(uuid.UUID(int=rng.getrandbits(128))),
                 "export": True, "isOpen": True, "visibility": True, "children": kids} for g, kids in groups.items()]
    return {
        "meta": {"format_version": "4.10", "model_format": "modded_entity", "box_uv": True},
        "name": "starship", "model_identifier": "starship", "visible_box": [3, 1.5, 0.75],
        "resolution": {"width": TEX, "height": TEX},
        "elements": elements, "outliner": outliner,
        "textures": [{"path": "", "name": "starship.png", "folder": "", "namespace": "", "id": "0", "width": TEX, "height": TEX,
                      "uv_width": TEX, "uv_height": TEX, "particle": False, "render_mode": "default", "visible": True,
                      "mode": "bitmap", "saved": True, "uuid": str(uuid.UUID(int=rng.getrandbits(128))),
                      "source": "data:image/png;base64," + base64.b64encode(tex_png).decode()}],
    }


def main():
    uv = pack()
    tex = build_texture(uv)
    ship_dir = ROOT / "src/main/resources/assets/galactica/textures/entity/ship"
    item_dir = ROOT / "src/main/resources/assets/galactica/textures/item"
    ship_dir.mkdir(parents=True, exist_ok=True)
    item_dir.mkdir(parents=True, exist_ok=True)
    tex.save(ship_dir / "starship.png")
    beacon_icon().save(item_dir / "ship_beacon.png")
    buf = io.BytesIO()
    tex.save(buf, "PNG")
    here = Path(__file__).resolve().parent
    (here / "starship.bbmodel").write_text(json.dumps(bbmodel(uv, buf.getvalue()), indent=1), encoding="utf-8")
    (here / "StarshipModel.parts.txt").write_text(java_lines(uv) + "\n", encoding="utf-8")
    print(java_lines(uv))


if __name__ == "__main__":
    main()
