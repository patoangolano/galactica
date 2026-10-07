# preview_starship.py - software turntable of the generated Starship, for reviewing the part table without
# Blockbench. Renders the design-space parts of gen_starship.py with the generated texture, box UV and a
# Lambert key light (emissive materials unlit), from six angles into one PNG.
#
#   python assets-src/ship/preview_starship.py out.png          gear down, canopy shut, nozzles centred
#   python assets-src/ship/preview_starship.py out.png flight   gear up, canopy open, nozzles gimballed 8 degrees
#   python assets-src/ship/preview_starship.py out.png crew     canopy glass left out, seated crew as grey blocks
import importlib.util
import math
import sys
from pathlib import Path

import numpy as np
from PIL import Image

spec = importlib.util.spec_from_file_location("gen", Path(__file__).with_name("gen_starship.py"))
gen = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gen)
TEX_PATH = gen.ROOT / "src/main/resources/assets/galactica/textures/entity/ship/starship.png"
tex = np.asarray(Image.open(TEX_PATH).convert("RGBA")).astype(np.float32) / 255
POSE = sys.argv[2] if len(sys.argv) > 2 else ""


def rmat(rx, ry, rz):
    """Rz * Ry * Rx, the order Blockbench and ModelPart.translateAndRotate both use."""
    rx, ry, rz = map(math.radians, (rx, ry, rz))
    X = np.array([[1, 0, 0], [0, math.cos(rx), -math.sin(rx)], [0, math.sin(rx), math.cos(rx)]])
    Y = np.array([[math.cos(ry), 0, math.sin(ry)], [0, 1, 0], [-math.sin(ry), 0, math.cos(ry)]])
    Z = np.array([[math.cos(rz), -math.sin(rz), 0], [math.sin(rz), math.cos(rz), 0], [0, 0, 1]])
    return Z @ Y @ X


GROUP_ROT = {}
if POSE == "flight":
    GROUP_ROT = {"gear_front": (gen.GEAR_FRONT_FOLD, 0, 0), "gear_left": (gen.GEAR_MAIN_FOLD, 0, 0),
                 "gear_right": (gen.GEAR_MAIN_FOLD, 0, 0), "canopy": (gen.CANOPY_OPEN, 0, 0),
                 "nozzle_left": (0, 8, 0), "nozzle_right": (0, 8, 0)}


def triangles():
    uv = gen.place_uvs()
    out = []
    parts = list(gen.PARTS)
    if POSE == "crew":
        parts = [p for p in parts if p["mat"] != "glass"] + crew()
    for p in parts:
        x0, y0, z0, w, h, d = p["x0"], p["y0"], p["z0"], p["w"], p["h"], p["d"]
        piv = np.array(p["pivot"] or gen.GROUPS[p["group"]], float)
        rot = rmat(*(p["rot"] or (0, 0, 0)))
        gp = np.array(gen.GROUPS[p["group"]], float)
        grot = rmat(*GROUP_ROT.get(p["group"], (0, 0, 0)))
        u, v = uv.get(p["name"], (0, 104 * gen.UV_K))
        cw, ch, cd = math.ceil(w), math.ceil(h), math.ceil(d)
        x1, y1, z1 = x0 + w, y0 + h, z0 + d
        faces = [  # corners counter-clockwise from outside, and the box-UV rectangle of that face
            ([(x0, y1, z0), (x0, y1, z1), (x1, y1, z1), (x1, y1, z0)], (u + cd, v, cw, cd)),
            ([(x0, y0, z1), (x0, y0, z0), (x1, y0, z0), (x1, y0, z1)], (u + cd + cw, v, cw, cd)),
            ([(x1, y0, z0), (x1, y1, z0), (x1, y1, z1), (x1, y0, z1)], (u, v + cd, cd, ch)),
            ([(x0, y0, z0), (x0, y1, z0), (x1, y1, z0), (x1, y0, z0)], (u + cd, v + cd, cw, ch)),
            ([(x0, y0, z1), (x0, y1, z1), (x0, y1, z0), (x0, y0, z0)], (u + cd + cw, v + cd, cd, ch)),
            ([(x1, y0, z1), (x1, y1, z1), (x0, y1, z1), (x0, y0, z1)], (u + 2 * cd + cw, v + cd, cw, ch)),
        ]
        emissive = gen.MATERIALS[p["mat"]][1] not in ("hull", "glass")
        for corners, (fu, fv, fw, fh) in faces:
            c = np.array(corners, float)
            c = (c - piv) @ rot.T + piv
            c = (c - gp) @ grot.T + gp
            n = np.cross(c[2] - c[0], c[1] - c[0])
            if np.linalg.norm(n) < 1e-9:
                continue
            n /= np.linalg.norm(n)
            t = [(fu, fv + fh), (fu, fv), (fu + fw, fv), (fu + fw, fv + fh)]
            out.append((c[[0, 1, 2]], np.array([t[0], t[1], t[2]]), n, emissive))
            out.append((c[[0, 2, 3]], np.array([t[0], t[2], t[3]]), n, emissive))
    return out


def crew():
    """Seated player models (feet 0.6 block under the hip, head top 1.875 above the feet), model pixels."""
    out = []
    for i, z in enumerate((gen.PILOT_Z, gen.PASSENGER_Z)):
        hip = gen.SEAT_Y * gen.SCALE
        feet = hip - 0.6 * 16
        zc = z * gen.SCALE
        for name, (w, y0, y1, d) in {"torso": (7.5, feet + 11.25, feet + 22.5, 3.75), "head": (7.5, feet + 22.5, feet + 30, 7.5),
                                     "legs": (7.5, hip - 2, hip + 2, 11.25)}.items():
            dz = 0 if name != "legs" else d / 2
            out.append(dict(name=f"crew_{i}_{name}", group="body", x0=-w / 2, y0=y0, z0=zc - d / 2 + dz, w=w, h=y1 - y0, d=d,
                            mat="metal", pivot=None, rot=None))
    return out


LIGHT = np.array([0.4, 0.8, 0.45])
LIGHT /= np.linalg.norm(LIGHT)


def render(tris, yaw, pitch, size=520, scale=5.4 / gen.SCALE):
    img = np.zeros((size, size, 3), np.float32) + np.array([0.10, 0.11, 0.14])
    zb = np.full((size, size), -1e9)
    view = rmat(pitch, 0, 0) @ rmat(0, yaw, 0)
    for verts, tuv, n, emissive in tris:
        p = verts @ view.T
        sx = size / 2 + p[:, 0] * scale
        sy = size / 2 - p[:, 1] * scale
        lit = 1.0 if emissive else 0.3 + 0.7 * max(0.0, float(n @ LIGHT))
        xmin, xmax = int(max(0, math.floor(sx.min()))), int(min(size - 1, math.ceil(sx.max())))
        ymin, ymax = int(max(0, math.floor(sy.min()))), int(min(size - 1, math.ceil(sy.max())))
        if xmin > xmax or ymin > ymax:
            continue
        xs, ys = np.meshgrid(np.arange(xmin, xmax + 1) + 0.5, np.arange(ymin, ymax + 1) + 0.5)
        det = (sx[1] - sx[0]) * (sy[2] - sy[0]) - (sx[2] - sx[0]) * (sy[1] - sy[0])
        if abs(det) < 1e-9:
            continue
        l1 = ((sx[1] - xs) * (sy[2] - ys) - (sx[2] - xs) * (sy[1] - ys)) / det
        l2 = ((sx[2] - xs) * (sy[0] - ys) - (sx[0] - xs) * (sy[2] - ys)) / det
        l3 = 1 - l1 - l2
        m = (l1 >= -1e-4) & (l2 >= -1e-4) & (l3 >= -1e-4)
        if not m.any():
            continue
        z = l1 * p[0, 2] + l2 * p[1, 2] + l3 * p[2, 2]
        sub = zb[ymin:ymax + 1, xmin:xmax + 1]
        m &= z > sub
        if not m.any():
            continue
        uu = l1 * tuv[0, 0] + l2 * tuv[1, 0] + l3 * tuv[2, 0]
        vv = l1 * tuv[0, 1] + l2 * tuv[1, 1] + l3 * tuv[2, 1]
        col = tex[np.clip(vv.astype(int), 0, gen.TEX - 1), np.clip(uu.astype(int), 0, gen.TEX - 1), :3] * lit
        sub[m] = z[m]
        block = img[ymin:ymax + 1, xmin:xmax + 1]
        block[m] = col[m]
    return (np.clip(img, 0, 1) * 255).astype(np.uint8)


def main():
    tris = triangles()
    # yaw 0 looks at the tail from behind (camera on -z looking +z is yaw 180 here); pitch tilts the camera down.
    views = [render(tris, a, b) for a, b in ((145, 22), (-40, 18), (90, 0), (0, 89.9), (180, 8), (0, 4))]
    sheet = np.concatenate([np.concatenate(views[:3], 1), np.concatenate(views[3:], 1)], 0)
    Image.fromarray(sheet).save(sys.argv[1])
    print(f"{len(tris)} triangles -> {sys.argv[1]}")


if __name__ == "__main__":
    main()
