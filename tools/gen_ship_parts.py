"""Generates the ship-part blocks' assets and data: 16x16 block textures, blockstates, block and item models,
loot tables, recipes and block tags, plus the thruster flame texture and the blueprint item icons.

Deterministic: run `python tools/gen_ship_parts.py .` from the repo root to rebuild the committed files.
"""
import json
import math
import os
import random
import sys

from PIL import Image

ROOT = sys.argv[1] if len(sys.argv) > 1 else "."
RES = os.path.join(ROOT, "src/main/resources")
A = os.path.join(RES, "assets/galactica")
D = os.path.join(RES, "data")
TEX = os.path.join(A, "textures/block")
ITEM_TEX = os.path.join(A, "textures/item")
for d in (TEX, ITEM_TEX, os.path.join(A, "blockstates"), os.path.join(A, "models/block"), os.path.join(A, "models/item"),
          os.path.join(A, "items"), os.path.join(A, "textures/entity/ship"), os.path.join(D, "galactica/loot_table/blocks"),
          os.path.join(D, "galactica/recipe"), os.path.join(D, "minecraft/tags/block/mineable"),
          os.path.join(D, "galactica/tags/block")):
    os.makedirs(d, exist_ok=True)


def write_json(path, obj):
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(obj, f, indent=2)
        f.write("\n")


def clamp(v):
    return max(0, min(255, int(v)))


def rgb(c, k=1.0, a=255):
    return (clamp(c[0] * k), clamp(c[1] * k), clamp(c[2] * k), a)


HULL = (178, 184, 192)
DARK = (70, 76, 88)
ORANGE = (236, 122, 40)
CYAN = (90, 210, 240)
PURPLE = (150, 70, 230)
WHITE = (232, 236, 240)


def panel(seed, base=HULL, rivets=True):
    """Brushed metal panel: subtle noise, a bevel, rivets in the corners."""
    rnd = random.Random(seed)
    img = Image.new("RGBA", (16, 16))
    p = img.load()
    for y in range(16):
        for x in range(16):
            k = 0.94 + 0.08 * rnd.random() - 0.015 * (y % 4 == 0)
            p[x, y] = rgb(base, k)
    for i in range(16):
        p[i, 0] = rgb(base, 1.18)
        p[0, i] = rgb(base, 1.12)
        p[i, 15] = rgb(base, 0.66)
        p[15, i] = rgb(base, 0.72)
    if rivets:
        for (x, y) in ((2, 2), (13, 2), (2, 13), (13, 13)):
            p[x, y] = rgb(base, 0.6)
            p[x - 1, y - 1] = rgb(base, 1.25)
    return img


def save(img, name, where=TEX):
    img.save(os.path.join(where, name + ".png"))


def stripe(img, y0, y1, colour):
    p = img.load()
    for y in range(y0, y1):
        for x in range(1, 15):
            p[x, y] = rgb(colour, 0.95 + 0.1 * ((x + y) % 2))


# --- textures -------------------------------------------------------------------------------------------------
save(panel(1), "ship_hull_metal")

plating = panel(2, (150, 156, 166))
p = plating.load()
for i in range(16):
    p[i, 7] = rgb((150, 156, 166), 0.62)
    p[7, i] = rgb((150, 156, 166), 0.62)
    p[i, 8] = rgb((150, 156, 166), 1.15)
    p[8, i] = rgb((150, 156, 166), 1.15)
save(plating, "ship_hull_plating")

cushion = Image.new("RGBA", (16, 16))
p = cushion.load()
for y in range(16):
    for x in range(16):
        quilt = 0.85 + 0.15 * (math.cos(x * math.pi / 4) * math.cos(y * math.pi / 4))
        p[x, y] = rgb((52, 58, 72), quilt)
save(cushion, "ship_seat_cushion")

console = panel(3, DARK, rivets=False)
p = console.load()
for y in range(3, 13):
    for x in range(2, 14):
        glow = 0.55 + 0.45 * (1 - abs(y - 8) / 6)
        p[x, y] = rgb((20, 60, 80), 1)
for x in range(3, 13):
    p[x, 5] = rgb(CYAN, 1.0)
for x in range(3, 9):
    p[x, 8] = rgb((120, 240, 140), 1.0)
for x in range(3, 11):
    p[x, 10] = rgb(ORANGE, 1.0)
p[11, 8] = p[12, 8] = rgb(CYAN, 1.2)
save(console, "ship_helm_console")

nozzle = Image.new("RGBA", (16, 16))
p = nozzle.load()
for y in range(16):
    for x in range(16):
        r = math.hypot(x - 7.5, y - 7.5)
        if r > 7.6:
            p[x, y] = rgb(HULL, 0.8)
        elif r > 6.2:
            p[x, y] = rgb(DARK, 1.2 - 0.06 * (x + y) / 4)
        elif r > 3.0:
            p[x, y] = rgb((40, 40, 46), 0.8 + 0.3 * (r - 3) / 3.2)
        else:
            p[x, y] = rgb((255, 150, 60), 1.0 - 0.15 * r)
save(nozzle, "ship_thruster_nozzle")

side = panel(4)
stripe(side, 6, 10, ORANGE)
save(side, "ship_thruster_side")
back = panel(5, DARK)
save(back, "ship_thruster_back")

rcs = panel(6)
p = rcs.load()
for (cx, cy) in ((4, 4), (11, 4), (4, 11), (11, 11)):
    for y in range(cy - 2, cy + 2):
        for x in range(cx - 2, cx + 2):
            p[x, y] = rgb(DARK, 0.7 + 0.2 * ((x + y) % 2))
    p[cx - 1, cy - 1] = rgb(WHITE, 0.8)
for i in range(4, 12):
    p[i, 7] = p[i, 8] = rgb(ORANGE, 1.0)
save(rcs, "ship_rcs")

tank_side = panel(7, (220, 224, 230))
stripe(tank_side, 2, 4, ORANGE)
stripe(tank_side, 12, 14, ORANGE)
p = tank_side.load()
for y in range(5, 11):
    p[7, y] = p[8, y] = rgb((230, 120, 200), 1.0)  # H2 gauge
save(tank_side, "ship_hydrogen_tank_side")
tank_top = panel(8, (220, 224, 230))
p = tank_top.load()
for y in range(16):
    for x in range(16):
        if math.hypot(x - 7.5, y - 7.5) < 3:
            p[x, y] = rgb(DARK, 1.0)
save(tank_top, "ship_hydrogen_tank_top")

core = Image.new("RGBA", (16, 16))
p = core.load()
for y in range(16):
    for x in range(16):
        r = math.hypot(x - 7.5, y - 7.5)
        swirl = 0.5 + 0.5 * math.sin(3 * math.atan2(y - 7.5, x - 7.5) + r * 1.2)
        if x in (0, 15) or y in (0, 15):
            p[x, y] = rgb(DARK, 1.1)
        else:
            k = max(0.25, 1 - r / 9)
            p[x, y] = (clamp(60 + 170 * k * swirl), clamp(20 + 60 * swirl * k), clamp(120 + 135 * k), 255)
save(core, "ship_hyperdrive_core")

gear_side = panel(9)
p = gear_side.load()
for y in range(2, 14):
    p[7, y] = p[8, y] = rgb(DARK, 1.0)
for x in range(3, 13):
    p[x, 14] = rgb((30, 30, 34), 1.0)
stripe(gear_side, 3, 5, ORANGE)
save(gear_side, "ship_landing_gear_side")
gear_bottom = panel(10, (40, 42, 48), rivets=False)
p = gear_bottom.load()
for y in range(3, 13):
    for x in range(3, 13):
        p[x, y] = rgb((25, 25, 28), 0.9 + 0.2 * ((x * 3 + y) % 3 == 0))
save(gear_bottom, "ship_landing_gear_bottom")

# --- ship systems: docking port, hangar bay, shield generator, reactor, scanner ----------------------------------
YELLOW = (240, 200, 40)

port_face = panel(11, DARK, rivets=False)
p = port_face.load()
for y in range(16):
    for x in range(16):
        r = math.hypot(x - 7.5, y - 7.5)
        if 5.2 < r < 7.4:
            # Hazard ring: yellow and black chevrons round the mating collar.
            p[x, y] = rgb(YELLOW if int((math.atan2(y - 7.5, x - 7.5) + math.pi) / (math.pi / 6)) % 2 == 0 else (30, 30, 34), 1.0)
        elif r < 4.6:
            p[x, y] = rgb((60, 66, 78), 0.85 + 0.1 * ((x + y) % 2))
for (x, y) in ((7, 3), (8, 12), (3, 8), (12, 7)):
    p[x, y] = rgb(CYAN, 1.1)
save(port_face, "ship_docking_port_front")
port_side = panel(12)
stripe(port_side, 1, 3, YELLOW)
save(port_side, "ship_docking_port_side")

bay = panel(13, (92, 98, 110), rivets=False)
p = bay.load()
for i in range(16):
    # Diagonal yellow-black landing stripes along two edges, a centre deck grating.
    for w in range(3):
        c = YELLOW if ((i + w) // 3) % 2 == 0 else (28, 28, 30)
        p[i, w] = rgb(c, 1.0)
        p[i, 15 - w] = rgb(c, 1.0)
for y in range(4, 12):
    for x in range(1, 15):
        if x % 3 == 0 or y % 3 == 0:
            p[x, y] = rgb((60, 64, 72), 1.0)
save(bay, "ship_hangar_bay_top")

shield = Image.new("RGBA", (16, 16))
p = shield.load()
for y in range(16):
    for x in range(16):
        if x in (0, 15) or y in (0, 15):
            p[x, y] = rgb(DARK, 1.1)
            continue
        r = math.hypot(x - 7.5, y - 7.5)
        hexes = 0.5 + 0.5 * math.cos(x * 1.4) * math.cos(y * 1.4 + (x % 2) * 0.7)
        k = max(0.3, 1 - r / 10)
        p[x, y] = (clamp(40 + 60 * k), clamp(140 + 100 * k * hexes), clamp(200 + 55 * k), 255)
save(shield, "ship_shield_generator")

reactor_side = panel(14, (120, 124, 132))
p = reactor_side.load()
for y in range(3, 13):
    for x in range(5, 11):
        glow = 1 - abs(x - 7.5) / 3.5
        p[x, y] = (clamp(255), clamp(140 + 100 * glow), clamp(40 + 60 * glow), 255)
stripe(reactor_side, 1, 2, ORANGE)
stripe(reactor_side, 14, 15, ORANGE)
save(reactor_side, "ship_reactor_side")
reactor_top = panel(15, DARK)
p = reactor_top.load()
for y in range(16):
    for x in range(16):
        r = math.hypot(x - 7.5, y - 7.5)
        if r < 4.5:
            p[x, y] = (clamp(255), clamp(200 - 25 * r), clamp(90 - 15 * r), 255)
save(reactor_top, "ship_reactor_top")

scanner = panel(16, (200, 206, 214))
p = scanner.load()
for y in range(16):
    for x in range(16):
        r = math.hypot(x - 7.5, y - 10.5)
        if 3 < r < 6.5 and y < 11:
            p[x, y] = rgb((70, 80, 96), 1.0)
p[7, 6] = p[8, 6] = rgb(CYAN, 1.2)
p[7, 7] = p[8, 7] = rgb(CYAN, 0.9)
for y in range(8, 14):
    p[7, y] = p[8, y] = rgb(DARK, 1.0)
save(scanner, "ship_scanner")

# Thruster flame (entity texture, drawn additive): hot white core fading to blue-violet and transparent at the tip.
flame = Image.new("RGBA", (16, 64))
p = flame.load()
for v in range(64):
    t = v / 63.0
    for u in range(16):
        s = abs(u - 7.5) / 7.5
        width = 1 - 0.75 * t
        a = max(0.0, 1 - (s / max(0.05, width)) ** 2) * (1 - t) ** 1.3
        core = max(0.0, 1 - s / max(0.05, 0.4 * width)) * (1 - t) ** 3
        r = 120 + 135 * core
        g = 150 + 105 * core
        b = 255
        p[u, v] = (clamp(r), clamp(g), clamp(b), clamp(255 * a))
flame.save(os.path.join(A, "textures/entity/ship/thruster_flame.png"))


# --- blueprint item icons -------------------------------------------------------------------------------------
def blueprint(name, shape):
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    p = img.load()
    for y in range(1, 15):
        for x in range(1, 15):
            p[x, y] = (40, 90, 170, 255) if (x + y) % 2 else (46, 98, 180, 255)
    for i in range(1, 15):
        p[i, 1] = p[i, 14] = p[1, i] = p[14, i] = (200, 225, 255, 255)
    for (x, y) in shape:
        p[x, y] = (235, 245, 255, 255)
    img.save(os.path.join(ITEM_TEX, name + ".png"))


def outline_ship(points):
    out = set()
    for (x0, y0, x1, y1) in points:
        for x in range(x0, x1 + 1):
            for y in range(y0, y1 + 1):
                out.add((x, y))
    return out


blueprint("ship_blueprint_kestrel", outline_ship([(7, 3, 8, 12), (4, 8, 11, 9), (6, 12, 9, 12)]))
blueprint("ship_blueprint_shuttle", outline_ship([(5, 4, 10, 11), (6, 3, 9, 3), (4, 10, 11, 11)]))
blueprint("ship_blueprint_freighter", outline_ship([(4, 3, 11, 12), (3, 5, 3, 10), (12, 5, 12, 10)]))
blueprint("ship_blueprint_explorer", outline_ship([(7, 2, 8, 13), (5, 6, 10, 10), (3, 9, 12, 9)]))

# --- blockstates and models -----------------------------------------------------------------------------------
BS = os.path.join(A, "blockstates")
MB = os.path.join(A, "models/block")


def g(name):
    return "galactica:block/" + name


def item_def(name, model=None):
    write_json(os.path.join(A, "items", name + ".json"), {"model": {"type": "minecraft:model", "model": model or g(name)}})


def simple(name, model):
    write_json(os.path.join(MB, name + ".json"), model)
    write_json(os.path.join(BS, name + ".json"), {"variants": {"": {"model": g(name)}}})
    item_def(name)


def horizontal(name, model):
    write_json(os.path.join(MB, name + ".json"), model)
    rot = {"north": 0, "east": 90, "south": 180, "west": 270}
    write_json(os.path.join(BS, name + ".json"),
               {"variants": {f"facing={f}": ({"model": g(name), "y": r} if r else {"model": g(name)}) for f, r in rot.items()}})
    item_def(name)


def directional(name, model):
    write_json(os.path.join(MB, name + ".json"), model)
    rot = {"north": {}, "east": {"y": 90}, "south": {"y": 180}, "west": {"y": 270}, "up": {"x": 270}, "down": {"x": 90}}
    write_json(os.path.join(BS, name + ".json"),
               {"variants": {f"facing={f}": dict({"model": g(name)}, **r) for f, r in rot.items()}})
    item_def(name)


def box(frm, to, faces):
    return {"from": frm, "to": to, "faces": faces}


def all_faces(tex, frm, to, cull=None):
    out = {}
    for f in ("down", "up", "north", "south", "west", "east"):
        face = {"texture": tex}
        if cull and f in cull:
            face["cullface"] = f
        out[f] = face
    return out


simple("ship_hull_plating", {"parent": "minecraft:block/cube_all", "textures": {"all": g("ship_hull_plating")}})
simple("ship_rcs", {"parent": "minecraft:block/cube_all", "textures": {"all": g("ship_rcs")}})
simple("ship_hyperdrive_core", {"parent": "minecraft:block/cube_all", "textures": {"all": g("ship_hyperdrive_core")}})
simple("ship_hydrogen_tank", {"parent": "minecraft:block/cube_column",
                              "textures": {"side": g("ship_hydrogen_tank_side"), "end": g("ship_hydrogen_tank_top")}})
simple("ship_landing_gear", {"parent": "minecraft:block/cube_bottom_top",
                             "textures": {"side": g("ship_landing_gear_side"), "top": g("ship_hull_metal"),
                                          "bottom": g("ship_landing_gear_bottom")}})
directional("ship_thruster", {
    "parent": "minecraft:block/block",
    "textures": {"front": g("ship_thruster_nozzle"), "side": g("ship_thruster_side"), "back": g("ship_thruster_back"),
                 "particle": g("ship_thruster_side")},
    "elements": [box([0, 0, 0], [16, 16, 16], {
        "down": {"texture": "#side", "cullface": "down"}, "up": {"texture": "#side", "cullface": "up"},
        "north": {"texture": "#front", "cullface": "north"}, "south": {"texture": "#back", "cullface": "south"},
        "west": {"texture": "#side", "cullface": "west"}, "east": {"texture": "#side", "cullface": "east"}})]})

directional("ship_docking_port", {
    "parent": "minecraft:block/block",
    "textures": {"front": g("ship_docking_port_front"), "side": g("ship_docking_port_side"), "back": g("ship_hull_metal"),
                 "particle": g("ship_docking_port_side")},
    "elements": [box([0, 0, 0], [16, 16, 16], {
        "down": {"texture": "#side", "cullface": "down"}, "up": {"texture": "#side", "cullface": "up"},
        "north": {"texture": "#front", "cullface": "north"}, "south": {"texture": "#back", "cullface": "south"},
        "west": {"texture": "#side", "cullface": "west"}, "east": {"texture": "#side", "cullface": "east"}})]})
simple("ship_hangar_bay", {"parent": "minecraft:block/cube_bottom_top",
                           "textures": {"side": g("ship_hull_metal"), "top": g("ship_hangar_bay_top"), "bottom": g("ship_hull_metal")}})
simple("ship_shield_generator", {"parent": "minecraft:block/cube_all", "textures": {"all": g("ship_shield_generator")}})
simple("ship_reactor", {"parent": "minecraft:block/cube_column",
                        "textures": {"side": g("ship_reactor_side"), "end": g("ship_reactor_top")}})
simple("ship_scanner", {"parent": "minecraft:block/cube_bottom_top",
                        "textures": {"side": g("ship_hull_metal"), "top": g("ship_scanner"), "bottom": g("ship_hull_metal")}})

SEAT_ELEMENTS = [
    # Cushion and back rest (the back on the south side: the block faces north, the way the seated rider looks).
    box([1, 0, 1], [15, 7, 15], {"down": {"texture": "#metal", "cullface": "down"}, "up": {"texture": "#cushion"},
                                 "north": {"texture": "#metal"}, "south": {"texture": "#metal"},
                                 "west": {"texture": "#metal"}, "east": {"texture": "#metal"}}),
    box([1, 7, 13], [15, 16, 15], {"down": {"texture": "#metal"}, "up": {"texture": "#metal"},
                                   "north": {"texture": "#cushion"}, "south": {"texture": "#metal"},
                                   "west": {"texture": "#metal"}, "east": {"texture": "#metal"}}),
]
horizontal("ship_crew_seat", {"parent": "minecraft:block/block", "ambientocclusion": False,
                              "textures": {"metal": g("ship_hull_metal"), "cushion": g("ship_seat_cushion"),
                                           "particle": g("ship_hull_metal")},
                              "elements": SEAT_ELEMENTS})
horizontal("ship_helm", {"parent": "minecraft:block/block", "ambientocclusion": False,
                         "textures": {"metal": g("ship_hull_metal"), "cushion": g("ship_seat_cushion"),
                                      "console": g("ship_helm_console"), "particle": g("ship_helm_console")},
                         "elements": SEAT_ELEMENTS + [
                             # Arm consoles either side of the seat, screens on top.
                             box([0, 0, 1], [1, 10, 12], {"down": {"texture": "#metal"}, "up": {"texture": "#console"},
                                                          "north": {"texture": "#metal"}, "south": {"texture": "#metal"},
                                                          "west": {"texture": "#metal", "cullface": "west"},
                                                          "east": {"texture": "#metal"}}),
                             box([15, 0, 1], [16, 10, 12], {"down": {"texture": "#metal"}, "up": {"texture": "#console"},
                                                            "north": {"texture": "#metal"}, "south": {"texture": "#metal"},
                                                            "west": {"texture": "#metal"},
                                                            "east": {"texture": "#metal", "cullface": "east"}}),
                         ]})

for cls in ("kestrel", "shuttle", "freighter", "explorer"):
    name = "ship_blueprint_" + cls
    write_json(os.path.join(A, "models/item", name + ".json"),
               {"parent": "minecraft:item/generated", "textures": {"layer0": "galactica:item/" + name}})
    item_def(name, "galactica:item/" + name)

# --- loot tables, tags, recipes -------------------------------------------------------------------------------
BLOCKS = ["ship_helm", "ship_crew_seat", "ship_thruster", "ship_rcs", "ship_hydrogen_tank", "ship_hyperdrive_core",
          "ship_landing_gear", "ship_hull_plating", "ship_docking_port", "ship_hangar_bay", "ship_shield_generator",
          "ship_reactor", "ship_scanner"]
for b in BLOCKS:
    write_json(os.path.join(D, "galactica/loot_table/blocks", b + ".json"), {
        "type": "minecraft:block",
        "pools": [{"rolls": 1, "condition": {"type": "minecraft:survives_explosion"},
                   "entries": [{"type": "minecraft:item", "name": "galactica:" + b}]}],
        "random_sequence": "galactica:blocks/" + b})
write_json(os.path.join(D, "minecraft/tags/block/mineable/pickaxe.json"), {"values": ["galactica:" + b for b in BLOCKS]})
write_json(os.path.join(D, "minecraft/tags/block/needs_stone_tool.json"), {"values": ["galactica:" + b for b in BLOCKS]})
# Natural ground the assembly flood fill stops at (the ship's own blocks are never these).
STOP = ["#minecraft:dirt", "#minecraft:sand", "#minecraft:base_stone_overworld", "#minecraft:base_stone_nether",
        "#minecraft:terracotta", "#minecraft:leaves", "#minecraft:logs_that_burn", "#minecraft:ice", "#minecraft:snow",
        "minecraft:gravel", "minecraft:clay", "minecraft:end_stone", "minecraft:bedrock",
        "minecraft:sandstone", "minecraft:red_sandstone", "#minecraft:coal_ores", "#minecraft:iron_ores",
        "#minecraft:copper_ores", "#minecraft:gold_ores", "#minecraft:redstone_ores", "#minecraft:lapis_ores",
        "#minecraft:diamond_ores", "#minecraft:emerald_ores"]
# Tag references are optional: a tag a later version renames must not fail the whole tag.
write_json(os.path.join(D, "galactica/tags/block/ship_assembly_stop.json"),
           {"values": [{"id": v, "required": False} if v.startswith("#") else v for v in STOP]})


def shaped(name, pattern, key, count=1):
    write_json(os.path.join(D, "galactica/recipe", name + ".json"), {
        "type": "minecraft:crafting_shaped", "category": "misc", "key": key, "pattern": pattern,
        "result": {"id": "galactica:" + name, "count": count} if count > 1 else {"id": "galactica:" + name}})


shaped("ship_helm", [" G ", "ICI", "IWI"], {"G": "minecraft:glass_pane", "C": "minecraft:compass",
                                            "I": "minecraft:iron_ingot", "W": "#minecraft:wool"})
shaped("ship_crew_seat", ["W  ", "WWW", "I I"], {"W": "#minecraft:wool", "I": "minecraft:iron_ingot"})
shaped("ship_thruster", ["III", "IFI", "I I"], {"I": "minecraft:iron_ingot", "F": "minecraft:blast_furnace"})
shaped("ship_rcs", [" I ", "IPI", " I "], {"I": "minecraft:iron_ingot", "P": "minecraft:piston"})
shaped("ship_hydrogen_tank", ["III", "IHI", "III"], {"I": "minecraft:iron_ingot", "H": "galactica:hydrogen_cell"})
shaped("ship_hyperdrive_core", ["OEO", "EDE", "OEO"], {"O": "minecraft:obsidian", "E": "galactica:exotic_matter",
                                                       "D": "minecraft:diamond"})
shaped("ship_landing_gear", ["I", "I", "B"], {"I": "minecraft:iron_ingot", "B": "minecraft:iron_block"})
shaped("ship_hull_plating", ["II", "II"], {"I": "minecraft:iron_ingot"}, count=4)
shaped("ship_docking_port", ["IYI", "I I", "IRI"], {"I": "minecraft:iron_ingot", "Y": "minecraft:yellow_dye",
                                                    "R": "minecraft:redstone"})
shaped("ship_hangar_bay", ["YBY", "III"], {"Y": "minecraft:yellow_dye", "B": "minecraft:black_dye",
                                           "I": "minecraft:iron_ingot"}, count=3)
shaped("ship_shield_generator", ["IDI", "QRQ", "IDI"], {"I": "minecraft:iron_ingot", "D": "minecraft:diamond",
                                                        "Q": "minecraft:quartz", "R": "minecraft:redstone_block"})
shaped("ship_reactor", ["IHI", "BGB", "IHI"], {"I": "minecraft:iron_ingot", "H": "galactica:hydrogen_cell",
                                               "B": "minecraft:blast_furnace", "G": "minecraft:glowstone"})
shaped("ship_scanner", [" C ", "IRI", "III"], {"C": "minecraft:compass", "I": "minecraft:iron_ingot",
                                              "R": "minecraft:redstone"})

# --- language keys (merged into the existing files, existing keys kept) ---------------------------------------
LANG = {
    "en_us": {
        "block.galactica.ship_helm": "Ship Helm",
        "block.galactica.ship_crew_seat": "Ship Crew Seat",
        "block.galactica.ship_thruster": "Ship Thruster",
        "block.galactica.ship_rcs": "RCS Thruster Quad",
        "block.galactica.ship_hydrogen_tank": "Hydrogen Tank",
        "block.galactica.ship_hyperdrive_core": "Hyperdrive Core",
        "block.galactica.ship_landing_gear": "Landing Gear",
        "block.galactica.ship_hull_plating": "Hull Plating",
        "item.galactica.ship_blueprint_kestrel": "Ship Blueprint: Kestrel",
        "item.galactica.ship_blueprint_shuttle": "Ship Blueprint: Shuttle",
        "item.galactica.ship_blueprint_freighter": "Ship Blueprint: Freighter",
        "item.galactica.ship_blueprint_explorer": "Ship Blueprint: Explorer",
        "tooltip.galactica.ship_blueprint": "Use to deploy this ship, fuelled, where you look",
        "tooltip.galactica.ship_helm": "The ship's nose points where you face when placing it. Use it to assemble the ship",
        "tooltip.galactica.ship_thruster": "The nozzle points back at you when placed",
        "message.galactica.ship.assembled": "Ship assembled: %s blocks, %s t, main engine %s g, lift %s g, H₂ tanks %s, jump %s ly. Sneak + use the ship (from outside) to set its blocks down again",
        "message.galactica.ship.error.too_many_blocks": "Too many blocks at %s %s %s: a ship may hold at most %4$s. Leave a gap between the ship and anything around it",
        "message.galactica.ship.error.too_large": "The ship is too large at %s %s %s: at most %5$s × %6$s × %5$s blocks. Leave a gap between the ship and anything around it",
        "message.galactica.ship.error.unmovable_block": "The ship touches an unmovable block at %s %s %s",
        "message.galactica.ship.error.no_thruster": "The ship needs at least one Ship Thruster",
        "message.galactica.ship.error.no_main_thruster": "No thruster pushes the ship forward: place one with its nozzle facing aft (away from where the helm faces)",
        "message.galactica.ship.error.no_fuel": "The ship needs a Hydrogen Tank",
        "message.galactica.ship.error.two_helms": "A second helm at %s %s %s: a ship has exactly one",
        "message.galactica.ship.error.no_headroom": "No headroom above the helm at %s %s %s: the pilot's head needs an empty block",
        "message.galactica.ship.disassembled": "Ship set down: %s blocks back on the ground",
        "message.galactica.ship.disassemble.crew": "Everyone must leave the ship first",
        "message.galactica.ship.disassemble.moving": "The ship must be still",
        "message.galactica.ship.disassemble.airborne": "Land the ship first",
        "message.galactica.ship.disassemble.tilted": "Level the ship first (within 25°)",
        "message.galactica.ship.disassemble.blocked": "%4$s at %1$s %2$s %3$s is in the way",
        "hud.galactica.hyperjump.no_drive": "No hyperdrive aboard: build a Hyperdrive Core into the ship",
        "message.galactica.ship.no_room": "No room for that ship there",
        "block.galactica.ship_docking_port": "Docking Port",
        "block.galactica.ship_hangar_bay": "Hangar Bay Floor",
        "block.galactica.ship_shield_generator": "Shield Generator",
        "block.galactica.ship_reactor": "Fusion Reactor",
        "block.galactica.ship_scanner": "Scanner Array",
        "key.galactica.dock": "Dock / undock",
        "key.galactica.cargo": "Open ship cargo",
        "key.galactica.cockpit_page": "Cockpit display page",
        "hud.galactica.dock.released": "Undocked",
        "hud.galactica.dock.no_port": "No docking port within %s blocks of one of yours",
        "hud.galactica.dock.too_fast": "Closing at %s m/s: slow below %s m/s to dock",
        "hud.galactica.dock.docked": "Docked to %s",
        "hud.galactica.dock.hangar": "Clamped in the hangar of %s (dock key releases)",
        "hud.galactica.cargo.none": "No cargo containers aboard",
        "container.galactica.ship_cargo": "Cargo: %s (%s/%s)",
        "hud.galactica.ship.wreck": "Hull 0%: engines dead",
        "hud.galactica.ship.shield": "Shield %s/%s",
        "hud.galactica.ship.docked": "Docked (%s: undock)",
        "hud.galactica.ship.in_hangar": "In a hangar (%s: release)",
        "hud.galactica.target.line_here": "Target %s (%s)",
        "hud.galactica.target.landed": "Landed on target",
        "hud.galactica.target.on_target": "On target, %s above the ground",
        "map.galactica.panel.scanner": "Scanner (%s m)",
        "map.galactica.panel.scanner_empty": "nothing in range",
        "config.galactica.ship_destruction": "Ship destruction",
        "config.galactica.ship_destruction.desc": "A built ship whose hull reaches 0 breaks up: the crew is set down safely, docked craft fly free, containers spill and part of the blocks drop. Off, it stays a wreck with dead engines, to repair with iron ingots.",
        "config.galactica.wreck_drop_share": "Wreck drops",
        "config.galactica.wreck_drop_share.desc": "Share of a destroyed ship's blocks that survive as items.",
        "tooltip.galactica.ship_docking_port": "Its yellow face mates with another ship's port: fly within 4 blocks and press the dock key",
        "tooltip.galactica.ship_hangar_bay": "The space above these floor blocks is a hangar: a lighter ship that settles in slowly is carried",
        "tooltip.galactica.ship_shield_generator": "Absorbs damage before the hull; recharged by a Fusion Reactor",
        "tooltip.galactica.ship_reactor": "Powers shields and scanners, burning a little hydrogen",
        "tooltip.galactica.ship_scanner": "Lists ships, derelicts, ore and bodies around the ship (needs a reactor)",
    },
    "pt_br": {
        "block.galactica.ship_helm": "Leme da Nave",
        "block.galactica.ship_crew_seat": "Assento de Tripulante",
        "block.galactica.ship_thruster": "Propulsor da Nave",
        "block.galactica.ship_rcs": "Quadra de Propulsores RCS",
        "block.galactica.ship_hydrogen_tank": "Tanque de Hidrogênio",
        "block.galactica.ship_hyperdrive_core": "Núcleo do Hiperpropulsor",
        "block.galactica.ship_landing_gear": "Trem de Pouso",
        "block.galactica.ship_hull_plating": "Blindagem do Casco",
        "item.galactica.ship_blueprint_kestrel": "Planta de Nave: Kestrel",
        "item.galactica.ship_blueprint_shuttle": "Planta de Nave: Ônibus Espacial",
        "item.galactica.ship_blueprint_freighter": "Planta de Nave: Cargueiro",
        "item.galactica.ship_blueprint_explorer": "Planta de Nave: Exploradora",
        "tooltip.galactica.ship_blueprint": "Use para implantar esta nave, abastecida, onde você olha",
        "tooltip.galactica.ship_helm": "O nariz da nave aponta para onde você olha ao colocá-lo. Use-o para montar a nave",
        "tooltip.galactica.ship_thruster": "O bocal aponta de volta para você ao ser colocado",
        "message.galactica.ship.assembled": "Nave montada: %s blocos, %s t, motor principal %s g, sustentação %s g, tanques de H₂ %s, salto %s al. Agache e use a nave (de fora) para devolver os blocos ao chão",
        "message.galactica.ship.error.too_many_blocks": "Blocos demais em %s %s %s: uma nave tem no máximo %4$s. Deixe um vão entre a nave e o que está em volta",
        "message.galactica.ship.error.too_large": "A nave é grande demais em %s %s %s: no máximo %5$s × %6$s × %5$s blocos. Deixe um vão entre a nave e o que está em volta",
        "message.galactica.ship.error.unmovable_block": "A nave encosta num bloco imóvel em %s %s %s",
        "message.galactica.ship.error.no_thruster": "A nave precisa de pelo menos um Propulsor da Nave",
        "message.galactica.ship.error.no_main_thruster": "Nenhum propulsor empurra a nave para a frente: coloque um com o bocal para trás (oposto ao leme)",
        "message.galactica.ship.error.no_fuel": "A nave precisa de um Tanque de Hidrogênio",
        "message.galactica.ship.error.two_helms": "Um segundo leme em %s %s %s: uma nave tem exatamente um",
        "message.galactica.ship.error.no_headroom": "Sem espaço acima do leme em %s %s %s: a cabeça do piloto precisa de um bloco vazio",
        "message.galactica.ship.disassembled": "Nave desmontada: %s blocos de volta ao chão",
        "message.galactica.ship.disassemble.crew": "Todos precisam sair da nave antes",
        "message.galactica.ship.disassemble.moving": "A nave precisa estar parada",
        "message.galactica.ship.disassemble.airborne": "Pouse a nave antes",
        "message.galactica.ship.disassemble.tilted": "Nivele a nave antes (até 25°)",
        "message.galactica.ship.disassemble.blocked": "%4$s em %1$s %2$s %3$s está no caminho",
        "hud.galactica.hyperjump.no_drive": "Sem hiperpropulsor a bordo: construa um Núcleo do Hiperpropulsor na nave",
        "message.galactica.ship.no_room": "Não há espaço para essa nave ali",
        "block.galactica.ship_docking_port": "Porta de Acoplamento",
        "block.galactica.ship_hangar_bay": "Piso de Hangar",
        "block.galactica.ship_shield_generator": "Gerador de Escudo",
        "block.galactica.ship_reactor": "Reator de Fusão",
        "block.galactica.ship_scanner": "Conjunto de Sensores",
        "key.galactica.dock": "Acoplar / desacoplar",
        "key.galactica.cargo": "Abrir a carga da nave",
        "key.galactica.cockpit_page": "Página do painel do cockpit",
        "hud.galactica.dock.released": "Desacoplada",
        "hud.galactica.dock.no_port": "Nenhuma porta de acoplamento a menos de %s blocos de uma das suas",
        "hud.galactica.dock.too_fast": "Aproximando a %s m/s: abaixo de %s m/s para acoplar",
        "hud.galactica.dock.docked": "Acoplada a %s",
        "hud.galactica.dock.hangar": "Presa no hangar de %s (a tecla de acoplar solta)",
        "hud.galactica.cargo.none": "Nenhum contêiner de carga a bordo",
        "container.galactica.ship_cargo": "Carga: %s (%s/%s)",
        "hud.galactica.ship.wreck": "Casco 0%: motores mortos",
        "hud.galactica.ship.shield": "Escudo %s/%s",
        "hud.galactica.ship.docked": "Acoplada (%s: desacoplar)",
        "hud.galactica.ship.in_hangar": "Num hangar (%s: soltar)",
        "hud.galactica.target.line_here": "Alvo %s (%s)",
        "hud.galactica.target.landed": "Pousada no alvo",
        "hud.galactica.target.on_target": "No alvo, %s acima do chão",
        "map.galactica.panel.scanner": "Sensores (%s m)",
        "map.galactica.panel.scanner_empty": "nada no alcance",
        "config.galactica.ship_destruction": "Destruição de naves",
        "config.galactica.ship_destruction.desc": "Uma nave construída com o casco a 0 se desfaz: a tripulação desce em segurança, naves acopladas ficam livres, os contêineres se esvaziam e parte dos blocos cai. Desligado, ela fica um destroço sem motores, para consertar com barras de ferro.",
        "config.galactica.wreck_drop_share": "Restos dos destroços",
        "config.galactica.wreck_drop_share.desc": "Fração dos blocos de uma nave destruída que sobrevive como itens.",
        "tooltip.galactica.ship_docking_port": "A face amarela se acopla à porta de outra nave: chegue a 4 blocos e aperte a tecla de acoplar",
        "tooltip.galactica.ship_hangar_bay": "O espaço acima destes blocos é um hangar: uma nave mais leve que entra devagar é carregada",
        "tooltip.galactica.ship_shield_generator": "Absorve dano antes do casco; recarregado por um Reator de Fusão",
        "tooltip.galactica.ship_reactor": "Alimenta escudos e sensores, queimando um pouco de hidrogênio",
        "tooltip.galactica.ship_scanner": "Lista naves, destroços, minérios e corpos em volta da nave (precisa de um reator)",
    },
}
for lang, keys in LANG.items():
    path = os.path.join(A, "lang", lang + ".json")
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    for k, v in keys.items():
        data[k] = v
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)
        f.write("\n")
print("ship parts generated")
