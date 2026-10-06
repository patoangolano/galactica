"""Generates Galactica content textures (16x16 item icons, 64x32 EVA suit equipment layers).

Deterministic: run `python tools/gen_content_textures.py .` from the repo root to rebuild the committed PNGs.
"""
import math
import os
import random
import sys

from PIL import Image

ROOT = sys.argv[1] if len(sys.argv) > 1 else "."
ITEM = os.path.join(ROOT, "src/main/resources/assets/galactica/textures/item")
EQ = os.path.join(ROOT, "src/main/resources/assets/galactica/textures/entity/equipment")
os.makedirs(ITEM, exist_ok=True)
for d in ("humanoid", "humanoid_leggings"):
    os.makedirs(os.path.join(EQ, d), exist_ok=True)


def shade(c, f):
    return tuple(max(0, min(255, int(v * f))) for v in c[:3]) + (255,)


def outline(img):
    """Darkens every opaque pixel that touches transparency: the 1px Minecraft-style outline."""
    w, h = img.size
    px = img.load()
    src = img.copy().load()
    for y in range(h):
        for x in range(w):
            if src[x, y][3] == 0:
                continue
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nx, ny = x + dx, y + dy
                if not (0 <= nx < w and 0 <= ny < h) or src[nx, ny][3] == 0:
                    px[x, y] = shade(src[x, y], 0.42)
                    break
    return img


def icon(name, painter):
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    painter(img.load())
    outline(img).save(os.path.join(ITEM, name + ".png"))


WHITE = (232, 236, 240, 255)
GREY = (150, 158, 170, 255)
DARK = (70, 76, 88, 255)
GOLD = (236, 176, 52, 255)
ORANGE = (240, 120, 40, 255)
BLUE = (70, 170, 240, 255)
RED = (255, 80, 70, 255)
GREEN = (120, 230, 120, 255)


def lit(c, x, y, cx, cy, r):
    """Key light from the upper left, as vanilla item art."""
    return shade(c, 1.12 - 0.28 * ((x - cx) + (y - cy)) / max(1, 2 * r))


def helmet(p):
    for y in range(2, 15):
        for x in range(2, 14):
            if (x - 7.5) ** 2 / 36 + (y - 8.5) ** 2 / 42 <= 1:
                p[x, y] = lit(WHITE, x, y, 7.5, 8.5, 6)
    for y in range(6, 11):
        for x in range(4, 12):
            if (x - 7.5) ** 2 / 14 + (y - 8) ** 2 / 7 <= 1:
                p[x, y] = lit(GOLD, x, y, 7.5, 8, 3)
    p[5, 7] = (255, 244, 200, 255)
    for x in range(4, 12):
        p[x, 13] = GREY


def suit(p):
    rows = {2: (4, 12), 3: (2, 14), 4: (1, 15), 5: (1, 15), 6: (1, 15), 7: (1, 15), 8: (1, 15), 9: (1, 15), 10: (1, 15)}
    for y in range(2, 15):
        x0, x1 = rows.get(y, (4, 12))
        for x in range(x0, x1):
            p[x, y] = lit(WHITE, x, y, 8, 8, 7)
    for y in range(8, 11):
        p[1, y] = p[2, y] = p[13, y] = p[14, y] = GREY
    for x in range(6, 10):
        p[x, 2] = GREY
    for y in range(5, 9):
        for x in range(6, 10):
            p[x, y] = DARK
    p[7, 6], p[8, 6], p[7, 7] = BLUE, RED, GREEN
    for x in range(4, 12):
        p[x, 12] = ORANGE


def leggings(p):
    for y in range(2, 15):
        for x in range(3, 13):
            if y >= 6 and 7 <= x <= 8:
                continue
            p[x, y] = lit(WHITE, x, y, 8, 8, 6)
    for x in range(3, 13):
        p[x, 2] = p[x, 3] = GREY
    for x in (4, 5, 10, 11):
        p[x, 9] = ORANGE


def boots(p):
    for y in range(6, 13):
        for x in list(range(2, 7)) + list(range(9, 14)):
            p[x, y] = lit(WHITE, x, y, 8, 10, 5)
    for x in list(range(1, 7)) + list(range(9, 15)):
        p[x, 13] = p[x, 14] = DARK
    for x in list(range(2, 7)) + list(range(9, 14)):
        p[x, 8] = GREY


def tank(p):
    for y in range(3, 15):
        for x in range(5, 11):
            p[x, y] = lit(BLUE, x, y, 7.5, 9, 6)
    for x in range(6, 10):
        p[x, 2] = WHITE
    for y in (0, 1):
        for x in range(7, 9):
            p[x, y] = GREY
    p[9, 1] = p[10, 1] = GREY
    for y in range(4, 13):
        p[6, y] = shade(BLUE, 1.35)
    for x in range(5, 11):
        p[x, 7] = WHITE
        p[x, 14] = GREY


def hydrogen(p):
    glass = (200, 220, 240, 255)
    for y in range(3, 13):
        for x in range(4, 12):
            p[x, y] = glass
    rnd = random.Random(7)
    for y in range(5, 12):
        for x in range(5, 11):
            # Balmer-series discharge glow: H-alpha red blended with H-beta cyan reads as pink.
            p[x, y] = (230, 120 + int(40 * rnd.random()), 200, 255)
    for x in range(4, 12):
        p[x, 2] = p[x, 13] = GREY
        p[x, 1] = p[x, 14] = DARK
    p[5, 6] = p[5, 7] = (255, 230, 250, 255)
    p[7, 0] = p[8, 0] = ORANGE


def exotic(p):
    for y in range(16):
        for x in range(16):
            dx, dy = x - 7.5, y - 7.5
            r = math.hypot(dx, dy)
            if r > 6.6:
                continue
            swirl = 0.5 + 0.5 * math.sin(3 * math.atan2(dy, dx) + r * 1.3)
            core = max(0.0, 1 - r / 6.6)
            p[x, y] = (int(40 + 140 * swirl * core + 60 * core), int(10 + 40 * swirl), int(90 + 150 * core), 255)
    p[6, 6] = p[7, 7] = (250, 230, 255, 255)
    p[5, 5] = (255, 255, 255, 255)


icon("eva_helmet", helmet)
icon("eva_suit", suit)
icon("eva_leggings", leggings)
icon("eva_boots", boots)
icon("oxygen_tank", tank)
icon("hydrogen_cell", hydrogen)
icon("exotic_matter", exotic)

# --- Equipment layers, vanilla 64x32 humanoid armour UV layout --------------------------------------
rnd = random.Random(42)


def fabric(x, y):
    """Beta-cloth white with a faint 2 px weave."""
    n = rnd.randint(-6, 6) + (4 if (x + y) % 4 < 2 else 0)
    return tuple(max(0, min(255, c + n)) for c in WHITE[:3]) + (255,)


def box(px, u, v, w, h, d, painter):
    """Paints a cuboid's six faces at texture offset (u, v), faces w x h x d (Minecraft box UV)."""
    faces = {
        "top": (u + d, v, w, d), "bottom": (u + d + w, v, w, d),
        "right": (u, v + d, d, h), "front": (u + d, v + d, w, h),
        "left": (u + d + w, v + d, d, h), "back": (u + 2 * d + w, v + d, w, h),
    }
    for face, (x0, y0, fw, fh) in faces.items():
        for yy in range(fh):
            for xx in range(fw):
                c = painter(face, xx, yy, fw, fh)
                if c is not None:
                    px[x0 + xx, y0 + yy] = c


def helmet_layer(face, x, y, w, h):
    if face == "front" and 1 <= x <= 6 and 2 <= y <= 6:
        return (255, 244, 200, 255) if (x, y) == (2, 3) else shade(GOLD, 1.15 - 0.05 * (x + y))
    if face in ("right", "left") and 3 <= y <= 4 and 2 <= x <= 5:
        return GREY
    if face not in ("top", "bottom") and y == h - 1:
        return GREY
    return fabric(x, y)


def torso_layer(face, x, y, w, h):
    if face == "front":
        if 2 <= x <= 5 and 2 <= y <= 4:
            return {(3, 3): BLUE, (4, 3): RED}.get((x, y), DARK)
        if y == 7:
            return ORANGE
    if face == "back" and 1 <= x <= 6 and 1 <= y <= 9:
        return GREY if (x in (1, 6) or y in (1, 9)) else shade(GREY, 1.15)
    if y == h - 1 and face not in ("top", "bottom"):
        return GREY
    return fabric(x, y)


def arm_layer(face, x, y, w, h):
    if face in ("top", "bottom"):
        return fabric(x, y) if face == "top" else GREY
    if y >= h - 2:
        return GREY
    if y == 3:
        return ORANGE
    return fabric(x, y)


def boot_layer(face, x, y, w, h):
    if face == "top":
        return None
    if face == "bottom" or y >= h - 2:
        return DARK
    if y >= h - 6:
        return GREY if y == h - 6 else fabric(x, y)
    return None


def leg_layer(face, x, y, w, h):
    if face in ("top", "bottom"):
        return fabric(x, y)
    if face == "front" and 5 <= y <= 6:
        return GREY
    if y == 0:
        return GREY
    return fabric(x, y)


def waist_layer(face, x, y, w, h):
    if face == "top":
        return None
    if face == "bottom":
        return GREY
    if y >= h - 5:
        return GREY if y == h - 5 else fabric(x, y)
    return None


hum = Image.new("RGBA", (64, 32), (0, 0, 0, 0))
p = hum.load()
box(p, 0, 0, 8, 8, 8, helmet_layer)
box(p, 16, 16, 8, 12, 4, torso_layer)
box(p, 40, 16, 4, 12, 4, arm_layer)
box(p, 0, 16, 4, 12, 4, boot_layer)
hum.save(os.path.join(EQ, "humanoid", "eva_suit.png"))

legs = Image.new("RGBA", (64, 32), (0, 0, 0, 0))
p = legs.load()
box(p, 16, 16, 8, 12, 4, waist_layer)
box(p, 0, 16, 4, 12, 4, leg_layer)
legs.save(os.path.join(EQ, "humanoid_leggings", "eva_suit.png"))
print("textures written")
