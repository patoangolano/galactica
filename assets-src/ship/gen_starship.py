# gen_starship.py - single source of truth for the Starship cuboid model (v3, "Kestrel" heavy fighter).
#
# Writes, from one part table:
#   src/main/resources/assets/galactica/textures/entity/ship/starship.png           256x256 material-atlas texture
#   src/main/resources/assets/galactica/textures/item/ship_beacon.png               16x16 item icon (unchanged since v2)
#   src/main/resources/assets/galactica/geckolib/models/entity/ship.geo.json        Bedrock geometry, hull layer only, for the optional GeckoLib renderer
#   src/main/resources/assets/galactica/geckolib/animations/entity/ship.animation.json  gear / canopy keyframe animations
#   src/main/java/dev/galactica/client/ship/StarshipGeometry.java                    vanilla ModelPart boxes (all three layers)
#   assets-src/ship/starship.geo.json                                                 the full geometry (all layers), for Blockbench import
#   assets-src/ship/starship.bbmodel                                                  Blockbench "modded_entity" project
#
# Run from anywhere:  python assets-src/ship/gen_starship.py
# Everything is procedural and seeded, so the output is reproducible. The design is original: a cube-built heavy
# fighter (twin large rear thruster pods, swept wings with canted tip fins, forward glass cockpit, tricycle gear,
# dark slate hull with orange accents, cyan and orange emissive lights). No third-party geometry or texture is used.
#
# Texture strategy: a material atlas. Every material owns a uniformly patterned swatch, and each cube's box-UV unwrap
# is placed somewhere inside its material's swatch (offset varied per cube so panel lines do not repeat). This keeps
# ~280 cubes on one 256x256 texture with plain vanilla box UV, which ModelPart, Blockbench and GeckoLib all share.

import base64
import hashlib
import io
import json
import math
import random
import uuid
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
TEX = 256
FEET = 12  # design origin (hull centre, the renderer pivot) sits 12 px = 0.75 block above the entity's feet
rng = random.Random(0x5EED5A3)

# ----------------------------------------------------------------------------------------------------------------
# Materials: swatch rectangle in the atlas, and the layer the cube goes to.
# hull = opaque, lit; light = emissive, always on; core = emissive, brightness follows the main-engine throttle.
MATERIALS = {
    "slate":      ((0, 0, 128, 104), "hull"),
    "slate_dark": ((128, 0, 128, 56), "hull"),
    "orange":     ((128, 56, 128, 48), "hull"),
    "metal":      ((0, 104, 96, 56), "hull"),
    "dark_metal": ((96, 104, 64, 56), "hull"),
    "glass":      ((160, 104, 96, 40), "hull"),
    "emi_cyan":   ((160, 144, 48, 24), "light"),
    "emi_orange": ((208, 144, 48, 24), "light"),
    "emi_core":   ((0, 160, 96, 48), "core"),
    "emi_red":    ((96, 160, 16, 16), "light"),
    "emi_green":  ((112, 160, 16, 16), "light"),
    "emi_white":  ((128, 160, 32, 16), "light"),
}

# ----------------------------------------------------------------------------------------------------------------
# Bones. Design space: 1 unit = 1/16 block, y up, +z = nose, +x = port (the body frame of ShipFlightModel),
# origin = hull centre. Pivots are absolute design coordinates.
GROUPS = {
    "body": (0.0, 0.0, 0.0),
    "canopy": (0.0, 4.5, 2.5),          # rear hinge: the glass swings up and forward-open
    "gear_front": (0.0, -5.0, 21.0),
    "gear_left": (14.0, -6.0, -17.0),
    "gear_right": (-14.0, -6.0, -17.0),
    "nozzle_left": (14.0, -0.5, -35.0),  # gimbal point at the nozzle throat
    "nozzle_right": (-14.0, -0.5, -35.0),
}

PARTS = []  # dicts: name, group, x0, y0, z0, w, h, d, mat, pivot, rot


def box(name, group, x0, y0, z0, w, h, d, mat, pivot=None, rot=None):
    assert mat in MATERIALS, mat
    assert group in GROUPS, group
    assert all(p["name"] != name for p in PARTS), name
    PARTS.append(dict(name=name, group=group, x0=x0, y0=y0, z0=z0, w=w, h=h, d=d, mat=mat,
                      pivot=pivot, rot=rot))


def centred(name, group, cx, cy, z0, w, h, d, mat, pivot=None, rot=None):
    box(name, group, cx - w / 2, cy - h / 2, z0, w, h, d, mat, pivot, rot)


def swap_side(s):
    return s.replace("left", "\0").replace("right", "left").replace("\0", "right")


def mirror_from(start):
    """Mirrors every part added since index `start` across x = 0 (names and groups swap left <-> right)."""
    for p in list(PARTS[start:]):
        piv = p["pivot"]
        rot = p["rot"]
        box(swap_side(p["name"]), swap_side(p["group"]), -(p["x0"] + p["w"]), p["y0"], p["z0"], p["w"], p["h"], p["d"],
            p["mat"], None if piv is None else (-piv[0], piv[1], piv[2]),
            None if rot is None else (rot[0], -rot[1], -rot[2]))


def polygon_prism(name, group, cx, cy, z0, length, apothem, mat, sides=8):
    """A prism along z whose cross-section is a regular polygon (8 or 16 sides) with the given apothem, built as
    the union of sides/2 bars of 2a x 2a*tan(pi/sides), each turned 360/sides degrees about the prism axis. Every bar
    corner lands on a polygon vertex and every bar end is a polygon edge, so the union is exactly the polygon."""
    n = sides // 2
    thick = 2 * apothem * math.tan(math.pi / sides)
    for i in range(n):
        ang = i * 360.0 / sides
        centred(f"{name}_{i}", group, cx, cy, z0, 2 * apothem, thick, length, mat,
                pivot=(cx, cy, z0 + length / 2), rot=None if ang == 0 else (0, 0, ang))


# ---------------------------------------------------------------------------------------------------- fuselage
# (z0, z1, half-width, y-bottom, y-top, material)
FUSELAGE = [
    (38, 42, 1.4, -2.2, 0.2, "orange"),
    (34, 38, 2.2, -2.8, 1.0, "slate"),
    (30, 34, 3.2, -3.4, 1.8, "slate"),
    (26, 30, 4.2, -3.9, 2.6, "slate"),
    (20, 26, 5.2, -4.4, 3.4, "slate"),
    (12, 20, 6.2, -4.9, 4.0, "slate"),
    (-6, 12, 7.0, -5.4, 4.5, "slate"),
    (-20, -6, 6.6, -5.0, 4.2, "slate"),
    (-30, -20, 5.2, -4.2, 3.4, "slate_dark"),
]
for i, (z0, z1, hw, yb, yt, mat) in enumerate(FUSELAGE):
    # Stepped section: a full-width waist with narrower deck and belly plates, the cube-built way to bevel an edge.
    c = min(1.6, (yt - yb) * 0.22, hw * 0.35)
    if c >= 0.6:
        box(f"fuse_{i}", "body", -hw, yb + c, z0, 2 * hw, yt - yb - 2 * c, z1 - z0, mat)
        box(f"fuse_{i}_deck", "body", -hw + c, yt - c, z0, 2 * (hw - c), c, z1 - z0, mat)
        box(f"fuse_{i}_belly", "body", -hw + c, yb, z0, 2 * (hw - c), c, z1 - z0, "slate_dark")
    else:
        box(f"fuse_{i}", "body", -hw, yb, z0, 2 * hw, yt - yb, z1 - z0, mat)

# Dorsal spine behind the canopy, and a belly keel.
box("spine_0", "body", -3.2, 4.0, -8, 6.4, 3.0, 10.5, "slate")
box("spine_1", "body", -2.6, 3.8, -22, 5.2, 2.6, 14, "slate")
box("spine_2", "body", -1.8, 3.2, -30, 3.6, 2.2, 8, "slate_dark")
centred("spine_ridge", "body", 0, 7.15, -20, 1.0, 0.6, 22, "orange")
box("keel_0", "body", -4.0, -6.6, -16, 8.0, 1.4, 26, "slate_dark")
box("keel_1", "body", -2.6, -6.2, 10, 5.2, 1.0, 12, "slate_dark")
for i, z in enumerate((-12, -4, 4)):
    box(f"keel_rib_{i}", "body", -4.4, -6.9, z, 8.8, 0.5, 1.2, "metal")

# Nose sensor and chin landing light.
box("nose_probe", "body", -0.3, -1.1, 42, 0.6, 0.6, 3, "metal")
box("chin_light", "body", -1.0, -3.6, 35, 2.0, 0.6, 1.0, "emi_white")
# Cheek armour plates with orange flash, and the cockpit side stripe.
start = len(PARTS)
box("cheek_left", "body", 6.0, -3.6, 13, 0.9, 5.4, 9, "slate_dark", pivot=(6.0, 0, 17.5), rot=(0, 8, 0))
box("cheek_flash_left", "body", 6.5, -1.4, 14, 0.6, 1.2, 7, "orange", pivot=(6.0, 0, 17.5), rot=(0, 8, 0))
box("side_strip_left", "body", 7.0, -0.6, -12, 0.25, 0.7, 22, "emi_cyan")
box("panel_left_0", "body", 7.0, 1.2, -4, 0.4, 2.2, 12, "slate_dark")
box("panel_left_1", "body", 7.0, -4.2, -4, 0.4, 2.4, 12, "slate_dark")
box("panel_left_2", "body", 6.6, 1.0, -19, 0.4, 2.0, 12, "slate_dark")
# Intake under the canopy rail: dark mouth with an orange lip.
box("intake_left", "body", 6.2, -3.0, 2, 3.6, 4.6, 9, "slate")
box("intake_mouth_left", "body", 6.6, -2.6, 10.6, 2.8, 3.8, 0.6, "dark_metal")
box("intake_lip_left", "body", 6.0, 1.6, 2, 4.0, 0.6, 9.2, "orange")
# Nose RCS quad (one of the yaw/pitch thruster blocks the RCS puffs come from).
box("rcs_nose_left", "body", 3.6, -1.2, 30.5, 1.4, 1.6, 2.2, "dark_metal")
box("rcs_nose_left_port", "body", 4.9, -0.8, 31.0, 0.3, 0.8, 1.2, "emi_orange")
mirror_from(start)

# ------------------------------------------------------------------------------------------------------ canopy
# Stepped glass bubble on its own bone, framed by dark struts, with a cyan instrument glow under the glass.
CANOPY = [  # (z0, z1, half-width, y-bottom, y-top)
    (2.5, 8, 3.6, 4.0, 8.2),
    (8, 14, 3.3, 4.0, 7.8),
    (14, 19, 2.9, 3.8, 6.9),
    (19, 23.5, 2.3, 3.4, 5.6),
]
for i, (z0, z1, hw, yb, yt) in enumerate(CANOPY):
    box(f"canopy_glass_{i}", "canopy", -hw, yb, z0, 2 * hw, yt - yb, z1 - z0, "glass")
    box(f"canopy_arch_{i}", "canopy", -hw - 0.25, yb, z0 - 0.3, 2 * hw + 0.5, yt - yb + 0.3, 0.6, "dark_metal")
box("canopy_spine", "canopy", -0.4, 7.85, 2.2, 0.8, 0.5, 6.0, "dark_metal")
box("canopy_spine_fwd", "canopy", -0.4, 7.4, 8.0, 0.8, 0.5, 6.0, "dark_metal")
start = len(PARTS)
box("canopy_rail_left", "canopy", 3.4, 3.7, 2.2, 0.6, 0.6, 21.3, "orange")
mirror_from(start)
box("dash_glow", "canopy", -2.4, 4.0, 18.5, 4.8, 0.8, 1.2, "emi_cyan")
box("headrest", "body", -1.8, 4.2, 1.6, 3.6, 3.6, 1.2, "dark_metal")

# --------------------------------------------------------------------------------------------- thruster pods
POD_X, POD_Y, POD_A = 14.0, -0.5, 6.0
start = len(PARTS)
polygon_prism("pod_body_left", "body", POD_X, POD_Y, -34, 28, POD_A, "slate", sides=16)
polygon_prism("pod_nose_left", "body", POD_X, POD_Y, -6, 4, POD_A - 1.2, "slate", sides=16)
polygon_prism("pod_tip_left", "body", POD_X, POD_Y, -2, 2.5, POD_A - 2.6, "slate_dark", sides=8)
polygon_prism("pod_ring_a_left", "body", POD_X, POD_Y, -12, 1.6, POD_A + 0.45, "metal")
polygon_prism("pod_ring_b_left", "body", POD_X, POD_Y, -22, 1.6, POD_A + 0.45, "metal")
polygon_prism("pod_band_left", "body", POD_X, POD_Y, -28, 1.2, POD_A + 0.3, "orange")
polygon_prism("pod_aft_left", "body", POD_X, POD_Y, -35.2, 1.4, POD_A + 0.6, "dark_metal")
# Pylon tying the pod to the fuselage, with cooling fins along the pod's spine.
box("pylon_left", "body", 6.0, -3.2, -30, 4.0, 5.0, 24, "slate_dark")
box("pylon_cap_left", "body", 6.0, 1.6, -28, 3.6, 0.8, 20, "orange")
for i in range(5):
    box(f"pod_fin_{i}_left", "body", POD_X - 2.6, POD_Y + POD_A - 0.3, -31 + 4 * i, 5.2, 1.6, 0.8, "dark_metal")
box("pod_light_left", "body", POD_X + POD_A + 0.15, POD_Y - 0.5, -18, 0.3, 1.0, 5, "emi_orange")
box("pod_rcs_top_left", "body", POD_X - 1.0, POD_Y + POD_A + 0.1, -33.5, 2.0, 0.9, 2.0, "dark_metal")
box("pod_rcs_bottom_left", "body", POD_X - 1.0, POD_Y - POD_A - 1.0, -33.5, 2.0, 0.9, 2.0, "dark_metal")
# Canted tail fin on top of the pod (orange leading block, slate blade).
FIN_PIVOT = (POD_X, POD_Y + POD_A - 0.2, -30)
box("tail_fin_left", "body", POD_X - 0.6, POD_Y + POD_A - 0.4, -36, 1.2, 9.5, 10, "slate", pivot=FIN_PIVOT, rot=(0, 0, -16))
box("tail_fin_cap_left", "body", POD_X - 0.7, POD_Y + POD_A + 8.8, -37, 1.4, 1.2, 9, "orange", pivot=FIN_PIVOT, rot=(0, 0, -16))
box("tail_fin_root_left", "body", POD_X - 0.9, POD_Y + POD_A - 0.4, -27, 1.8, 3.0, 4, "slate_dark", pivot=FIN_PIVOT, rot=(0, 0, -16))
box("tail_fin_light_left", "body", POD_X - 0.75, POD_Y + POD_A + 9.0, -28.4, 1.5, 0.8, 0.6, "emi_red")

# Nozzle bell on its gimbal bone: flared rings with a hot core plate inside the exit plane.
polygon_prism("nozzle_throat_left", "nozzle_left", POD_X, POD_Y, -37, 2.0, 4.6, "dark_metal")
polygon_prism("nozzle_bell_a_left", "nozzle_left", POD_X, POD_Y, -39, 2.0, 5.2, "metal")
polygon_prism("nozzle_bell_b_left", "nozzle_left", POD_X, POD_Y, -41, 2.0, 5.7, "dark_metal")
polygon_prism("nozzle_lip_left", "nozzle_left", POD_X, POD_Y, -41.6, 0.6, 6.0, "orange", sides=8)
polygon_prism("nozzle_core_left", "nozzle_left", POD_X, POD_Y, -41.75, 0.2, 4.4, "emi_core", sides=8)

# Main gear under the pod: oleo strut, torque link, twin-wheel bogie.
box("gear_strut_left", "gear_left", POD_X - 0.8, -11.0, -17.8, 1.6, 5.2, 1.6, "metal")
box("gear_oleo_left", "gear_left", POD_X - 0.5, -11.6, -17.5, 1.0, 1.2, 1.0, "dark_metal")
box("gear_link_left", "gear_left", POD_X - 0.4, -9.4, -16.4, 0.8, 2.6, 0.8, "orange")
box("gear_foot_left", "gear_left", POD_X - 2.2, -12.0, -20.5, 4.4, 1.0, 7.0, "dark_metal")
box("gear_door_left", "gear_left", POD_X + 1.0, -9.8, -19.8, 0.4, 4.2, 5.6, "slate")
mirror_from(start)

# ------------------------------------------------------------------------------------------------------- wings
# Root at the pod's outer flank, swept 30 degrees aft, with a 3-step chord taper; canted tip fin; nav lights.
SWEEP = 30
WING_PIVOT = (19.0, -1.2, -12.0)
start = len(PARTS)
box("wing_root_left", "body", 18.0, -2.0, -26, 6, 1.8, 20, "slate", pivot=WING_PIVOT, rot=(0, SWEEP, 0))
box("wing_mid_left", "body", 24.0, -1.8, -24, 8, 1.5, 16, "slate", pivot=WING_PIVOT, rot=(0, SWEEP, 0))
box("wing_out_left", "body", 32.0, -1.6, -22, 7, 1.2, 12, "slate", pivot=WING_PIVOT, rot=(0, SWEEP, 0))
box("wing_skin_left", "body", 19.0, -0.4, -23, 18, 0.5, 13, "slate_dark", pivot=WING_PIVOT, rot=(0, SWEEP, 0))
box("wing_edge_left", "body", 18.0, -1.7, -6.8, 20.5, 1.2, 1.4, "orange", pivot=WING_PIVOT, rot=(0, SWEEP, 0))
box("wing_flap_left", "body", 22.0, -1.5, -26.5, 12, 0.9, 3.0, "metal", pivot=WING_PIVOT, rot=(0, SWEEP, 0))
for i in range(3):
    box(f"wing_vent_{i}_left", "body", 21.0 + 5 * i, 0.1, -20, 3.2, 0.4, 0.8, "dark_metal", pivot=WING_PIVOT, rot=(0, SWEEP, 0))
box("wing_hardpoint_left", "body", 27.0, -3.4, -18, 2.0, 1.8, 9, "dark_metal", pivot=WING_PIVOT, rot=(0, SWEEP, 0))
box("wing_missile_left", "body", 27.3, -4.4, -20, 1.4, 1.2, 12, "metal", pivot=WING_PIVOT, rot=(0, SWEEP, 0))
box("wing_missile_tip_left", "body", 27.5, -4.2, -8.2, 1.0, 0.8, 1.2, "orange", pivot=WING_PIVOT, rot=(0, SWEEP, 0))
TIP_PIVOT = (38.5, -1.0, -17.0)
box("tip_fin_left", "body", 38.0, -1.6, -24, 1.0, 7.5, 9, "slate", pivot=TIP_PIVOT, rot=(0, SWEEP, -12))
box("tip_fin_edge_left", "body", 37.9, -1.6, -15.6, 1.2, 7.5, 1.2, "orange", pivot=TIP_PIVOT, rot=(0, SWEEP, -12))
box("tip_fin_ventral_left", "body", 38.0, -4.8, -22, 1.0, 3.4, 6, "slate_dark", pivot=TIP_PIVOT, rot=(0, SWEEP, 10))
box("nav_light_left", "body", 38.2, -1.4, -15.0, 0.9, 0.9, 0.9, "emi_red", pivot=TIP_PIVOT, rot=(0, SWEEP, -12))
box("tip_rcs_left", "body", 37.6, -2.6, -20, 1.6, 0.6, 1.6, "dark_metal", pivot=TIP_PIVOT, rot=(0, SWEEP, -12))
mirror_from(start)
# Port is red, starboard green (ICAO / maritime convention): recolour the mirrored nav light.
for p in PARTS:
    if p["name"] == "nav_light_right":
        p["mat"] = "emi_green"
    if p["name"] == "tail_fin_light_right":
        p["mat"] = "emi_green"

# ---------------------------------------------------------------------------------------------------- nose gear
box("gear_front_strut", "gear_front", -0.7, -10.6, 20.3, 1.4, 5.8, 1.4, "metal")
box("gear_front_link", "gear_front", -0.4, -9.0, 21.6, 0.8, 2.6, 0.8, "orange")
box("gear_front_foot", "gear_front", -1.8, -12.0, 18.8, 3.6, 1.4, 4.4, "dark_metal")
box("gear_front_door", "gear_front", -1.6, -6.0, 18.5, 3.2, 0.4, 6.0, "slate")
box("gear_front_lamp", "gear_front", -0.5, -8.2, 22.0, 1.0, 0.8, 0.4, "emi_white")
box("beacon_dorsal", "body", -0.5, 7.4, -9.0, 1.0, 0.7, 1.0, "emi_orange")
box("beacon_ventral", "body", -0.5, -7.4, -9.0, 1.0, 0.6, 1.0, "emi_orange")


# ================================================================================================= emission
def layer_of(p):
    return MATERIALS[p["mat"]][1]


def uv_size(p):
    w, h, d = math.ceil(p["w"]), math.ceil(p["h"]), math.ceil(p["d"])
    return 2 * (d + w), d + h


def place_uvs():
    """Each cube's unwrap goes inside its material swatch, at an offset hashed from its name (stable, varied)."""
    uv = {}
    for p in PARTS:
        (sx, sy, sw, sh), _ = MATERIALS[p["mat"]]
        uw, uh = uv_size(p)
        if uw > sw or uh > sh:
            raise SystemExit(f"{p['name']}: unwrap {uw}x{uh} does not fit swatch {p['mat']} {sw}x{sh}")
        hsh = int(hashlib.sha1(p["name"].encode()).hexdigest(), 16)
        uv[p["name"]] = (sx + hsh % (sw - uw + 1), sy + (hsh >> 16) % (sh - uh + 1))
    return uv


def clamp(c):
    return max(0, min(255, int(round(c))))


def shade(rgb, k):
    return tuple(clamp(c * k) for c in rgb)


BASE = {
    "slate": (80, 87, 99), "slate_dark": (50, 55, 64), "orange": (226, 112, 30), "metal": (112, 118, 128),
    "dark_metal": (30, 32, 37), "glass": (20, 32, 46), "emi_cyan": (80, 220, 255), "emi_orange": (255, 146, 44),
    "emi_core": (196, 228, 255), "emi_red": (255, 46, 40), "emi_green": (50, 255, 110), "emi_white": (244, 248, 255),
}


def paint(img):
    px = img.load()
    for mat, ((x0, y0, w, h), _layer) in MATERIALS.items():
        base = BASE[mat]
        tone = {}
        for j in range(h):
            for i in range(w):
                n = rng.uniform(-0.035, 0.035)
                c = base
                if mat in ("slate", "slate_dark"):
                    pw, ph = (8, 6) if mat == "slate" else (6, 4)
                    key = (i // pw, j // ph)
                    if key not in tone:
                        tone[key] = rng.uniform(-0.07, 0.07)
                    c = shade(base, 1 + tone[key])
                    if i % pw == 0 or j % ph == 0:
                        c = shade(base, 0.72)          # panel seam
                    elif i % pw == 1 and j % ph == 1 and rng.random() < 0.5:
                        c = shade(base, 1.35)          # fastener
                    if mat == "slate" and i % 32 == 17 and j % ph in (2, 3):
                        c = (180, 92, 30)              # sparse orange service markings
                elif mat == "orange":
                    c = shade(base, 0.78) if (i % 8 == 0 or j % 6 == 0) else base
                    if (i + j) % 16 in (0, 1) and j % 6 > 3:
                        c = shade(base, 0.9)
                elif mat == "metal":
                    c = shade(base, 0.7) if j % 3 == 2 else base
                elif mat == "dark_metal":
                    c = shade(base, 1.3) if (i * 7 + j * 3) % 23 == 0 else base
                elif mat == "glass":
                    t = j / max(1, h - 1)
                    c = tuple(clamp(base[k] * (1.2 - 0.4 * t)) for k in range(3))
                    if (i - j * 2) % 19 in (0, 1, 2):
                        c = (96, 146, 186)             # reflection streaks
                elif mat == "emi_core":
                    c = shade(base, 0.92 + 0.08 * math.sin(i * 0.7) * math.sin(j * 0.9))
                elif mat.startswith("emi_"):
                    c = shade(base, 1.0 if (i + j) % 4 else 0.86)
                c = shade(c, 1 + n)
                px[x0 + i, y0 + j] = (*c, 255)


def beacon_icon():
    """16x16 item: a white hull-panel puck with an orange chevron and a cyan beacon lamp, 1 px dark outline."""
    HULL, SEAM, ORANGE = (232, 234, 238), (176, 181, 190), (240, 122, 26)
    CYAN, CYAN_CORE = (34, 184, 224), (170, 250, 255)
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
            c = tuple(max(0, min(255, int(CYAN[k] + (CYAN_CORE[k] - CYAN[k]) * t * (1 - (y - 1) / 6)))) for k in range(3))
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


# ===================================================================================== ModelPart (Java) export
def to_model_point(x, y, z):
    """Design -> ModelPart space. The renderer applies scale(-1,-1,1), a 180 degree turn about z, so x and y negate."""
    return -x, -y, z


def to_model_rot(rot):
    """Under that turn a rotation about x or y reverses sign and one about z keeps it (radians out)."""
    rot = rot or (0, 0, 0)
    return -math.radians(rot[0]), -math.radians(rot[1]), math.radians(rot[2])


def f(v):
    return f"{v:.3f}F"


def java_geometry(uv):
    out = {"hull": [], "light": [], "core": []}
    for layer in out:
        lines = out[layer]
        for g, gp in GROUPS.items():
            m = to_model_point(*gp)
            lines.append(f'        PartDefinition {g} = r.addOrReplaceChild("{g}", CubeListBuilder.create(), '
                         f'PartPose.offset({f(m[0])}, {f(m[1])}, {f(m[2])}));')
        for p in PARTS:
            if layer_of(p) != layer:
                continue
            gp = to_model_point(*GROUPS[p["group"]])
            piv = to_model_point(*(p["pivot"] or GROUPS[p["group"]]))
            mn = to_model_point(p["x0"] + p["w"], p["y0"] + p["h"], p["z0"])  # min corner after the flip
            off = (piv[0] - gp[0], piv[1] - gp[1], piv[2] - gp[2])
            rx, ry, rz = to_model_rot(p["rot"])
            if p["pivot"] is None and p["rot"] is None:
                pose = "PartPose.ZERO"
            else:
                pose = (f"PartPose.offsetAndRotation({f(off[0])}, {f(off[1])}, {f(off[2])}, "
                        f"{rx:.5f}F, {ry:.5f}F, {rz:.5f}F)")
            u, v = uv[p["name"]]
            lines.append(f'        {p["group"]}.addOrReplaceChild("{p["name"]}", CubeListBuilder.create().texOffs({u}, {v})'
                         f'.addBox({f(mn[0] - piv[0])}, {f(mn[1] - piv[1])}, {f(mn[2] - piv[2])}, '
                         f'{f(p["w"])}, {f(p["h"])}, {f(p["d"])}), {pose});')
    nl = chr(10)
    return f"""package dev.galactica.client.ship;

import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.PartDefinition;

/**
 * Starship cuboids, generated by assets-src/ship/gen_starship.py from its part table (do not edit by hand:
 * change the table and rerun). The texture at textures/entity/ship/starship.png is painted by the same run.
 * {len(PARTS)} cubes ({6 * len(PARTS)} faces) in {len(GROUPS)} bones and three layers: the opaque hull, the
 * always-on lights and the engine cores (brightness follows thrust). Every bone exists in every layer so the
 * animation code poses them alike.
 */
final class StarshipGeometry {{
    private StarshipGeometry() {{}}

    static final int TEXTURE_SIZE = {TEX};
    /** Bone names, in the order the generator declares them. */
    static final String[] BONES = {{{", ".join(f'"{g}"' for g in GROUPS)}}};

    static void hull(PartDefinition r) {{
{nl.join(out["hull"])}
    }}

    static void lights(PartDefinition r) {{
{nl.join(out["light"])}
    }}

    static void cores(PartDefinition r) {{
{nl.join(out["core"])}
    }}
}}
"""


# ============================================================================================ Bedrock / GeckoLib
def r4(v):
    return round(v, 4)


def geo_json(uv, hull_only=True):
    """Bedrock geometry 1.12: x is mirrored relative to Blockbench's view (origin.x = -to.x, pivot.x negated) and
    rotations about x and y change sign, as Blockbench's own bedrock codec writes them.

    hull_only (the GeckoLib asset): the GeckoLib renderer draws this lit by the lightmap, and the shared vanilla code draws the
    emissive light and engine-core layers on top; including them here would draw them twice at the same depth
    (z-fighting, and cores that never go dark at idle). The Blockbench import copy keeps every layer."""
    bones = []
    for g, (px, py, pz) in GROUPS.items():
        bone = {"name": g, "pivot": [r4(-px), r4(py + FEET), r4(pz)], "cubes": []}
        if g != "body":
            bone["parent"] = "body"
        for p in PARTS:
            if p["group"] != g or (hull_only and layer_of(p) != "hull"):
                continue
            cube = {"origin": [r4(-(p["x0"] + p["w"])), r4(p["y0"] + FEET), r4(p["z0"])],
                    "size": [r4(p["w"]), r4(p["h"]), r4(p["d"])], "uv": list(uv[p["name"]])}
            if p["rot"] is not None:
                cx, cy, cz = p["pivot"]
                cube["pivot"] = [r4(-cx), r4(cy + FEET), r4(cz)]
                cube["rotation"] = [r4(-p["rot"][0]), r4(-p["rot"][1]), r4(p["rot"][2])]
            bone["cubes"].append(cube)
        if not bone["cubes"]:
            del bone["cubes"]
        bones.append(bone)
    return {"format_version": "1.12.0", "minecraft:geometry": [{
        "description": {"identifier": "geometry.galactica.ship", "texture_width": TEX, "texture_height": TEX,
                        "visible_bounds_width": 6, "visible_bounds_height": 3, "visible_bounds_offset": [0, 1, 0]},
        "bones": bones}]}


# Animation angles, Blockbench (design) convention, degrees. ShipAnimator.java uses the same numbers.
GEAR_FRONT_FOLD, GEAR_MAIN_FOLD, CANOPY_OPEN = -95.0, 92.0, -38.0
GEAR_SECONDS, CANOPY_SECONDS = 1.5, 1.2


def bedrock_rot_x(deg):
    return [r4(-deg), 0, 0]


def animation_json():
    def keyed(t, frm, to):
        return {"0.0": frm, f"{t}": to}

    gear_up = {
        "gear_front": {"rotation": keyed(GEAR_SECONDS, [0, 0, 0], bedrock_rot_x(GEAR_FRONT_FOLD))},
        "gear_left": {"rotation": keyed(GEAR_SECONDS, [0, 0, 0], bedrock_rot_x(GEAR_MAIN_FOLD))},
        "gear_right": {"rotation": keyed(GEAR_SECONDS, [0, 0, 0], bedrock_rot_x(GEAR_MAIN_FOLD))},
    }
    gear_down = {b: {"rotation": {"0.0": v["rotation"][f"{GEAR_SECONDS}"], f"{GEAR_SECONDS}": [0, 0, 0]}}
                 for b, v in gear_up.items()}
    return {"format_version": "1.8.0", "animations": {
        "gear.retract": {"loop": "hold_on_last_frame", "animation_length": GEAR_SECONDS, "bones": gear_up},
        "gear.extend": {"loop": "hold_on_last_frame", "animation_length": GEAR_SECONDS, "bones": gear_down},
        "gear.up": {"loop": "hold_on_last_frame", "animation_length": 0.05,
                    "bones": {b: {"rotation": {"0.0": v["rotation"][f"{GEAR_SECONDS}"]}} for b, v in gear_up.items()}},
        "canopy.open": {"loop": "hold_on_last_frame", "animation_length": CANOPY_SECONDS,
                        "bones": {"canopy": {"rotation": keyed(CANOPY_SECONDS, [0, 0, 0], bedrock_rot_x(CANOPY_OPEN))}}},
        "canopy.close": {"loop": "hold_on_last_frame", "animation_length": CANOPY_SECONDS,
                         "bones": {"canopy": {"rotation": keyed(CANOPY_SECONDS, bedrock_rot_x(CANOPY_OPEN), [0, 0, 0])}}},
    }}


# ======================================================================================== Blockbench project
def bbmodel(uv, tex_png):
    elements, by_group = [], {g: [] for g in GROUPS}
    for p in PARTS:
        uid = str(uuid.UUID(int=rng.getrandbits(128)))
        piv = p["pivot"] or GROUPS[p["group"]]
        elements.append({
            "name": p["name"], "box_uv": True, "rescale": False, "locked": False,
            "from": [p["x0"], p["y0"] + FEET, p["z0"]],
            "to": [p["x0"] + p["w"], p["y0"] + p["h"] + FEET, p["z0"] + p["d"]],
            "autouv": 0, "color": list(MATERIALS).index(p["mat"]) % 8,
            "origin": [piv[0], piv[1] + FEET, piv[2]], "rotation": list(p["rot"] or (0, 0, 0)),
            "uv_offset": list(uv[p["name"]]),
            "faces": {fc: {"uv": [0, 0, 0, 0], "texture": 0} for fc in ("north", "east", "south", "west", "up", "down")},
            "type": "cube", "uuid": uid})
        by_group[p["group"]].append(uid)

    def group_node(g):
        px, py, pz = GROUPS[g]
        kids = list(by_group[g])
        if g == "body":
            kids += [group_node(c) for c in GROUPS if c != "body"]
        return {"name": g, "origin": [px, py + FEET, pz], "rotation": [0, 0, 0], "color": 0,
                "uuid": str(uuid.UUID(int=rng.getrandbits(128))), "export": True, "isOpen": g == "body",
                "visibility": True, "children": kids}

    return {
        "meta": {"format_version": "4.10", "model_format": "modded_entity", "box_uv": True},
        "name": "starship", "model_identifier": "starship", "visible_box": [4, 2, 0.75],
        "resolution": {"width": TEX, "height": TEX},
        "elements": elements, "outliner": [group_node("body")],
        "textures": [{"path": "", "name": "starship.png", "folder": "", "namespace": "", "id": "0", "width": TEX,
                      "height": TEX, "uv_width": TEX, "uv_height": TEX, "particle": False, "render_mode": "default",
                      "visible": True, "mode": "bitmap", "saved": True,
                      "uuid": str(uuid.UUID(int=rng.getrandbits(128))),
                      "source": "data:image/png;base64," + base64.b64encode(tex_png).decode()}],
    }


def main():
    uv = place_uvs()
    tex = Image.new("RGBA", (TEX, TEX), (0, 0, 0, 0))
    paint(tex)
    res = ROOT / "src/main/resources/assets/galactica"
    (res / "textures/entity/ship").mkdir(parents=True, exist_ok=True)
    (res / "textures/item").mkdir(parents=True, exist_ok=True)
    (res / "geckolib/models/entity").mkdir(parents=True, exist_ok=True)
    (res / "geckolib/animations/entity").mkdir(parents=True, exist_ok=True)
    tex.save(res / "textures/entity/ship/starship.png")
    icon, icon_path = beacon_icon(), res / "textures/item/ship_beacon.png"
    # Only rewrite on a pixel change, so a different Pillow's PNG encoder does not churn the file.
    if not icon_path.exists() or Image.open(icon_path).convert("RGBA").tobytes() != icon.tobytes():
        icon.save(icon_path)
    (res / "geckolib/models/entity/ship.geo.json").write_text(json.dumps(geo_json(uv), indent=1), encoding="utf-8")
    (HERE / "starship.geo.json").write_text(json.dumps(geo_json(uv, hull_only=False), indent=1), encoding="utf-8")
    (res / "geckolib/animations/entity/ship.animation.json").write_text(json.dumps(animation_json(), indent=1), encoding="utf-8")
    buf = io.BytesIO()
    tex.save(buf, "PNG")
    (HERE / "starship.bbmodel").write_text(json.dumps(bbmodel(uv, buf.getvalue()), indent=1), encoding="utf-8")
    (ROOT / "src/main/java/dev/galactica/client/ship/StarshipGeometry.java").write_text(java_geometry(uv), encoding="utf-8")
    stale = HERE / "StarshipModel.parts.txt"
    if stale.exists():
        stale.unlink()
    layers = {}
    for p in PARTS:
        layers[layer_of(p)] = layers.get(layer_of(p), 0) + 1
    print(f"{len(PARTS)} cubes, {6 * len(PARTS)} faces, layers {layers}")


if __name__ == "__main__":
    main()
