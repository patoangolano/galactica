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
# Linear scale from the design units below (laid out on a 1/16-block grid) to model pixels. 2.5 makes the Kestrel
# 13.6 m long with a 12.2 m span (F-16: 15.0 m / 9.8 m; Saab Gripen: 14.1 m / 8.4 m), so a 1.8 m pilot sits under
# the canopy with the helmet in the glass and the body in the fuselage. ShipDimensions.SCALE must match.
SCALE = 2.5
# The atlas grows with the model so a texel stays 1/16 block (vanilla density): the 256-unit swatch layout below is
# multiplied by UV_K, which must be >= SCALE so every scaled unwrap still fits its swatch.
UV_K = 4
TEX = 256 * UV_K
FEET = 12  # design origin (hull centre, the renderer pivot) sits 12 design units above the entity's feet
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
    # Cockpit interior (v4).
    "cockpit_grey": ((0, 208, 64, 48), "hull"),
    "panel_black":  ((64, 208, 48, 48), "hull"),
    "seat":         ((112, 208, 48, 48), "hull"),
    "emi_amber":    ((160, 208, 24, 16), "light"),
}
# The canopy glass is its own translucent layer, so the pilot shows through it.
MATERIALS["glass"] = (MATERIALS["glass"][0], "glass")

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
    "cockpit": (0.0, 0.0, 0.0),          # interior: drawn as its own layers (lit and emissive), never animated
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
# Cockpit well cut through the deck under the canopy: (z0, z1, half-width). Tandem seats: the pilot forward, a
# passenger behind; the opening follows the canopy's own taper so the glass covers it from above.
COCKPIT_WELL = [(2.0, 14.0, 3.1), (14.0, 18.6, 2.7)]
# Seated hips (Starship.PILOT_SEAT / PASSENGER_SEAT, ShipDimensions.SEAT_Y), the cockpit floor under the crew's legs,
# and the bottom of the floor plate: the well is hollow from FLOOR_BASE up to the deck, so the seated body sits in it
# instead of in solid fuselage. The vanilla rider's head top is 8.16 units over the hips (8.54 with a helmet layer).
SEAT_Y = -1.6
FLOOR_TOP = SEAT_Y - 1.0
FLOOR_BASE = FLOOR_TOP - 0.3


def deck_with_well(name, x0, y0, z0, w, h, z1, mat):
    """The deck plate from z0 to z1, split around COCKPIT_WELL into full-width pieces and side strips."""
    cuts = sorted(c for c in COCKPIT_WELL if c[0] < z1 and c[1] > z0)
    z, k = z0, 0
    for (cz0, cz1, half) in cuts:
        a, b = max(cz0, z0), min(cz1, z1)
        if a > z:
            box(f"{name}_{k}", "body", x0, y0, z, w, h, a - z, mat)
            k += 1
        side = x0 + w - half
        if side > 0.05:
            box(f"{name}_{k}_l", "body", half, y0, a, side, h, b - a, mat)
            box(f"{name}_{k}_r", "body", x0, y0, a, side, h, b - a, mat)
            k += 1
        z = b
    if z < z1:
        box(f"{name}_{k}" if k else name, "body", x0, y0, z, w, h, z1 - z, mat)


for i, (z0, z1, hw, yb, yt, mat) in enumerate(FUSELAGE):
    # Stepped section: a full-width waist with narrower deck and belly plates, the cube-built way to bevel an edge.
    c = min(1.6, (yt - yb) * 0.22, hw * 0.35)
    if c >= 0.6:
        if any(cz0 < z1 and cz1 > z0 for (cz0, cz1, _h) in COCKPIT_WELL):
            # The waist under the well: solid below the cockpit floor, side walls only above it.
            box(f"fuse_{i}", "body", -hw, yb + c, z0, 2 * hw, FLOOR_BASE - (yb + c), z1 - z0, mat)
            deck_with_well(f"fuse_{i}_wall", -hw, FLOOR_BASE, z0, 2 * hw, yt - c - FLOOR_BASE, z1, mat)
        else:
            box(f"fuse_{i}", "body", -hw, yb + c, z0, 2 * hw, yt - yb - 2 * c, z1 - z0, mat)
        deck_with_well(f"fuse_{i}_deck", -hw + c, yt - c, z0, 2 * (hw - c), c, z1, mat)
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
    # Frame hoop (two posts and a bow), not a bulkhead: the glass between hoops stays see-through.
    if i > 0:
        box(f"canopy_bow_{i}", "canopy", -hw - 0.25, yt - 0.25, z0 - 0.3, 2 * hw + 0.5, 0.55, 0.6, "dark_metal")
        box(f"canopy_post_{i}_left", "canopy", hw - 0.15, yb, z0 - 0.3, 0.4, yt - yb, 0.6, "dark_metal")
        box(f"canopy_post_{i}_right", "canopy", -hw - 0.25, yb, z0 - 0.3, 0.4, yt - yb, 0.6, "dark_metal")
# Spines ride on top of the glass (outside it), never through the crew's helmets.
box("canopy_spine", "canopy", -0.4, 8.15, 2.2, 0.8, 0.5, 6.0, "dark_metal")
box("canopy_spine_fwd", "canopy", -0.4, 7.75, 8.0, 0.8, 0.5, 6.0, "dark_metal")
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


# ----------------------------------------------------------------------------------------------- cockpit (v4)
# Tandem cockpit in the well: the pilot sits forward (hip at z 11), a passenger behind (z 4.6), both hips at SEAT_Y
# (0.25 block under the hull centre after scaling) on cushions at that height, legs out over the floor (FLOOR_TOP).
# The rider's eye is 6.53 units over the hips (y 4.93): the glare shield's far edge sits 2 degrees under the horizon
# and the display fills 7.5-21.5 degrees below it, above the vanilla hotbar, hearts and armour rows at every auto GUI
# scale (70 degree FOV). Port throttle, starboard side stick (F-16 layout), nothing between the pilot and the display.
# The only frame in the forward view is the thin windscreen pillars at z 19; the canopy hoops are outside the glass.
# The panel face is SCREEN_Z; the Java side draws the live display just in front.
PILOT_Z, PASSENGER_Z = 11.0, 4.6
EYE_Y = SEAT_Y + 6.528
SCREEN = (-2.5, 2.5, 2.78, 4.21, 16.45)  # x0, x1, y0, y1, z: the instrument display rectangle, design units
KEY_Y = 3.37  # console tops (forearm height)
box("pit_floor", "cockpit", -3.0, FLOOR_BASE, 2.0, 6.0, 0.3, 16.6, "cockpit_grey")
start = len(PARTS)
box("pit_wall_aft_left", "cockpit", 2.85, FLOOR_TOP, 2.0, 0.25, 4.4 - FLOOR_TOP, 12.0, "cockpit_grey")
box("pit_wall_fwd_left", "cockpit", 2.45, FLOOR_TOP, 14.0, 0.25, 4.0 - FLOOR_TOP, 4.6, "cockpit_grey")
box("pit_sill_aft_left", "cockpit", 2.85, 4.4, 2.0, 0.45, 0.35, 12.0, "dark_metal")
box("pit_sill_fwd_left", "cockpit", 2.45, 4.0, 14.0, 0.45, 0.35, 4.6, "dark_metal")
box("windscreen_pillar_left", "cockpit", 2.35, 3.6, 19.0, 0.25, 2.6, 0.3, "dark_metal")
# Seat bolsters.
box("seat_bolster_left", "cockpit", 1.7, SEAT_Y - 0.3, 9.3, 0.6, 2.0, 3.0, "seat")
box("seat_pax_bolster_left", "cockpit", 1.7, SEAT_Y - 0.3, 2.8, 0.6, 2.0, 3.0, "seat")
# Side console beside the pilot (an armrest at forearm height), with its key rows.
box("console_left", "cockpit", 1.95, FLOOR_TOP, 10.2, 0.9, KEY_Y - 0.1 - FLOOR_TOP, 3.8, "cockpit_grey")
box("console_top_left", "cockpit", 2.05, KEY_Y - 0.1, 10.4, 0.7, 0.1, 3.4, "panel_black")
mirror_from(start)
box("seat_back", "cockpit", -1.8, SEAT_Y + 0.6, 8.4, 3.6, 3.8, 0.9, "seat")
box("seat_headrest", "cockpit", -1.3, SEAT_Y + 5.0, 8.5, 2.6, 2.8, 0.7, "seat")
box("seat_cushion", "cockpit", -1.7, SEAT_Y - 0.3, 9.3, 3.4, 0.3, 3.0, "seat")
box("seat_pax_cushion", "cockpit", -1.7, SEAT_Y - 0.3, 2.8, 3.4, 0.3, 3.0, "seat")
box("seat_pax_back", "cockpit", -1.8, SEAT_Y - 0.3, 2.0, 3.6, 4.9, 0.8, "seat")
# Passenger's repeater panel on the back of the pilot's seat, at the passenger's eye line.
box("pax_panel", "cockpit", -1.4, EYE_Y - 1.36, 8.15, 2.8, 1.2, 0.25, "panel_black")
box("pax_panel_glow", "cockpit", -1.1, EYE_Y - 0.71, 8.1, 2.2, 0.25, 0.05, "emi_cyan")
for row in range(3):
    for col in range(2):
        for side, sx in (("left", 1), ("right", -1)):
            mat = ("emi_amber", "emi_cyan", "emi_green")[(row + col + (side == "right")) % 3]
            x = 2.2 + 0.3 * col if sx > 0 else -(2.2 + 0.3 * col) - 0.2
            box(f"console_key_{row}_{col}_{side}", "cockpit", x, KEY_Y, 10.8 + 0.8 * row + (1.2 if side == "left" else 0), 0.2, 0.06, 0.25, mat)
# Throttle quadrant (port).
box("throttle_base", "cockpit", 2.25, KEY_Y, 11.0, 0.5, 0.3, 1.9, "dark_metal")
box("throttle_lever", "cockpit", 2.4, KEY_Y + 0.3, 12.1, 0.2, 0.85, 0.25, "metal")
box("throttle_handle", "cockpit", 2.15, KEY_Y + 1.1, 11.9, 0.7, 0.5, 0.7, "panel_black")
box("throttle_button", "cockpit", 2.4, KEY_Y + 1.6, 12.1, 0.2, 0.08, 0.25, "emi_red")
# Side stick (starboard).
box("stick_boot", "cockpit", -2.85, KEY_Y, 12.3, 0.7, 0.3, 0.8, "panel_black")
box("stick_shaft", "cockpit", -2.6, KEY_Y + 0.3, 12.58, 0.2, 0.6, 0.24, "metal")
box("stick_grip", "cockpit", -2.75, KEY_Y + 0.9, 12.4, 0.5, 0.75, 0.6, "panel_black")
box("stick_trigger", "cockpit", -2.58, KEY_Y + 1.25, 13.0, 0.16, 0.2, 0.08, "emi_red")
# Instrument panel (down to the floor: the front of the footwell), display bezel and glare shield.
SX0, SX1, SY0, SY1, SZ = SCREEN
box("panel", "cockpit", -2.6, FLOOR_TOP, 16.6, 5.2, SY1 + 0.14 - FLOOR_TOP, 2.0, "cockpit_grey")
box("panel_bezel", "cockpit", -2.6, SY0 - 0.1, 16.5, 5.2, SY1 - SY0 + 0.2, 0.1, "dark_metal")
box("glare_shield", "cockpit", -2.7, SY1 + 0.14, 16.2, 5.4, 0.27, 2.4, "panel_black")
box("caution_light", "cockpit", 2.1, SY1 + 0.2, 16.15, 0.35, 0.15, 0.05, "emi_amber")
box("warning_light", "cockpit", -2.45, SY1 + 0.2, 16.15, 0.35, 0.15, 0.05, "emi_red")
box("panel_strip", "cockpit", -2.0, SY0 - 0.08, 16.45, 4.0, 0.05, 0.05, "emi_cyan")


def check_crew_clearance():
    """Fails the run if a seated rider's head (with a helmet layer) touches anything opaque, or the torso and
    hips touch the hull: the cockpit layout must hold for any change to the table above."""
    def overlaps(p, lo, hi):
        if p["rot"] is not None:
            return False  # rotated parts are all far outside the cockpit (wings, fins, pods)
        a = (p["x0"], p["y0"], p["z0"])
        b = (p["x0"] + p["w"], p["y0"] + p["h"], p["z0"] + p["d"])
        return all(a[k] < hi[k] - 1e-6 and b[k] > lo[k] + 1e-6 for k in range(3))
    for z in (PILOT_Z, PASSENGER_Z):
        head = ((-1.8, SEAT_Y + 5.16, z - 1.6), (1.8, SEAT_Y + 8.54, z + 1.6))
        torso = ((-1.3, SEAT_Y, z - 0.8), (1.3, SEAT_Y + 5.16, z + 0.8))
        for p in PARTS:
            if MATERIALS[p["mat"]][1] != "glass" and overlaps(p, *head):
                raise SystemExit(f"{p['name']} cuts the head of the rider at z {z}")
            if p["group"] != "cockpit" and overlaps(p, *torso):
                raise SystemExit(f"{p['name']} cuts the body of the rider at z {z}")
        glass_top = min(c[4] for c in CANOPY if c[0] < z + 1.6 and c[1] > z - 1.6)
        if glass_top - head[1][1] < 0.6:
            raise SystemExit(f"helmet clearance under the glass at z {z}: {glass_top - head[1][1]:.2f} units")


check_crew_clearance()


# ================================================================================================= emission
# Everything above is in design units; everything below exports model pixels. Scale the table once, here.
DESIGN_CANOPY = [tuple(v * SCALE for v in row) for row in CANOPY]
for _p in PARTS:
    for _k in ("x0", "y0", "z0", "w", "h", "d"):
        _p[_k] *= SCALE
    if _p["pivot"] is not None:
        _p["pivot"] = tuple(v * SCALE for v in _p["pivot"])
GROUPS = {g: tuple(v * SCALE for v in piv) for g, piv in GROUPS.items()}
FEET *= SCALE
MATERIALS = {m: (tuple(v * UV_K for v in rect), layer) for m, (rect, layer) in MATERIALS.items()}
LAYERS = ("hull", "glass", "light", "core", "cockpit", "cockpit_light")


def layer_of(p):
    layer = MATERIALS[p["mat"]][1]
    if p["group"] == "cockpit":
        return "cockpit_light" if layer in ("light", "core") else "cockpit"
    return layer


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
    "cockpit_grey": (86, 90, 96), "panel_black": (34, 36, 40), "seat": (52, 46, 42), "emi_amber": (255, 180, 50),
}
# Canopy glass alpha: tinted, with brighter streaks (the glass layer draws translucent).
GLASS_ALPHA, STREAK_ALPHA = 96, 150


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
                    t = (j % 40) / 39
                    c = tuple(clamp(base[k] * (1.2 - 0.4 * t)) for k in range(3))
                    a = GLASS_ALPHA
                    if (i - j * 2) % 47 in (0, 1, 2, 3, 4):
                        c, a = (96, 146, 186), STREAK_ALPHA  # reflection streaks
                elif mat == "cockpit_grey":
                    c = shade(base, 0.8) if (i % 24 == 0 or j % 16 == 0) else base
                    if i % 24 == 2 and j % 16 == 2:
                        c = shade(base, 1.3)
                elif mat == "panel_black":
                    c = shade(base, 1.25) if (i * 5 + j * 11) % 37 == 0 else base
                elif mat == "seat":
                    c = shade(base, 0.75) if (i % 10 == 0 or j % 10 == 0) else base
                elif mat == "emi_core":
                    c = shade(base, 0.92 + 0.08 * math.sin(i * 0.7) * math.sin(j * 0.9))
                elif mat.startswith("emi_"):
                    c = shade(base, 1.0 if (i + j) % 4 else 0.86)
                c = shade(c, 1 + n)
                px[x0 + i, y0 + j] = (*c, a if mat == "glass" else 255)


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
    out = {layer: [] for layer in LAYERS}
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
    px = SCALE / 16.0

    def blocks(v):
        return f"{v * px / SCALE:.5f}F"
    canopy_rows = ", ".join("{" + ", ".join(blocks(v) for v in row) + "}" for row in DESIGN_CANOPY)
    screen = ", ".join(f"{v * px:.5f}F" for v in SCREEN)
    return f"""package dev.galactica.client.ship;

import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.PartDefinition;

/**
 * Starship cuboids, generated by assets-src/ship/gen_starship.py from its part table (do not edit by hand:
 * change the table and rerun). The texture at textures/entity/ship/starship.png is painted by the same run.
 * {len(PARTS)} cubes ({6 * len(PARTS)} faces) in {len(GROUPS)} bones and six layers: the opaque hull, the
 * translucent canopy glass, the always-on lights, the engine cores (brightness follows thrust), and the cockpit
 * interior (lit, and its emissive keys). Every bone exists in every layer so the animation code poses them alike.
 * Design units were scaled by {SCALE} (model pixels, 1/16 block).
 */
final class StarshipGeometry {{
    private StarshipGeometry() {{}}

    static final int TEXTURE_SIZE = {TEX};
    /** Design units to model pixels; must equal {{@code ShipDimensions.SCALE}} (checked by a unit test). */
    static final float SCALE = {SCALE}F;
    /** Canopy glass sections, body frame, blocks: z0, z1, half-width, y-bottom, y-top (y from the hull centre). */
    static final float[][] CANOPY = {{{canopy_rows}}};
    /** Instrument display rectangle on the panel, body frame, blocks: x0, x1, y0, y1, z. */
    static final float[] SCREEN = {{{screen}}};
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

    static void glass(PartDefinition r) {{
{nl.join(out["glass"])}
    }}

    static void cockpit(PartDefinition r) {{
{nl.join(out["cockpit"])}
    }}

    static void cockpitLights(PartDefinition r) {{
{nl.join(out["cockpit_light"])}
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
        if hull_only and g == "cockpit":
            continue
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
                        "visible_bounds_width": 6 * SCALE, "visible_bounds_height": 3 * SCALE, "visible_bounds_offset": [0, 1, 0]},
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
        "name": "starship", "model_identifier": "starship", "visible_box": [4 * SCALE, 2 * SCALE, 0.75 * SCALE],
        "resolution": {"width": TEX, "height": TEX},
        "elements": elements, "outliner": [group_node("body")],
        "textures": [{"path": "", "name": "starship.png", "folder": "", "namespace": "", "id": "0", "width": TEX,
                      "height": TEX, "uv_width": TEX, "uv_height": TEX, "particle": False, "render_mode": "default",
                      "visible": True, "mode": "bitmap", "saved": True,
                      "uuid": str(uuid.UUID(int=rng.getrandbits(128))),
                      "source": "data:image/png;base64," + base64.b64encode(tex_png).decode()}],
    }


# ============================================================================================ collision probes
def part_corners(p):
    """The eight corners of a part in design-scaled model pixels (body frame), its rotation applied about its pivot
    (Blockbench order: x, then y, then z)."""
    xs = (p["x0"], p["x0"] + p["w"])
    ys = (p["y0"], p["y0"] + p["h"])
    zs = (p["z0"], p["z0"] + p["d"])
    rx, ry, rz = (math.radians(a) for a in (p["rot"] or (0, 0, 0)))
    piv = p["pivot"] or GROUPS[p["group"]]
    out = []
    for x in xs:
        for y in ys:
            for z in zs:
                v = [x - piv[0], y - piv[1], z - piv[2]]
                v = [v[0], v[1] * math.cos(rx) - v[2] * math.sin(rx), v[1] * math.sin(rx) + v[2] * math.cos(rx)]
                v = [v[0] * math.cos(ry) + v[2] * math.sin(ry), v[1], -v[0] * math.sin(ry) + v[2] * math.cos(ry)]
                v = [v[0] * math.cos(rz) - v[1] * math.sin(rz), v[0] * math.sin(rz) + v[1] * math.cos(rz), v[2]]
                out.append((v[0] + piv[0], v[1] + piv[1], v[2] + piv[2]))
    return out


PROBE_SECTORS = 24
PROBE_MIN_RADIUS = 2.4  # blocks: nearer to the hull centre, the collision box itself covers the hull


def hull_probes():
    """Collision probes for the hull beyond the central box (Starship's terrain check): in each of PROBE_SECTORS
    azimuth sectors of the body XZ plane, the farthest, the highest and the lowest of the outer corners (within 85 % of the
    sector's farthest reach), over every fixed exterior part (gear and nozzles move, and stay inside the box)."""
    pts = [c for p in PARTS if p["group"] == "body" for c in part_corners(p)]
    blocks = [(x / 16, y / 16, z / 16) for (x, y, z) in pts]
    chosen = []
    for k in range(PROBE_SECTORS):
        sec = [b for b in blocks if int((math.atan2(b[0], b[2]) + math.pi) / (2 * math.pi) * PROBE_SECTORS) % PROBE_SECTORS == k]
        sec = [b for b in sec if math.hypot(b[0], b[2]) >= PROBE_MIN_RADIUS]
        if not sec:
            continue
        reach = max(math.hypot(b[0], b[2]) for b in sec)
        outer = [b for b in sec if math.hypot(b[0], b[2]) >= 0.85 * reach]
        far = max(sec, key=lambda b: math.hypot(b[0], b[2]))
        for b in (far, max(outer, key=lambda b: (b[1], math.hypot(b[0], b[2]))), min(outer, key=lambda b: (b[1], -math.hypot(b[0], b[2])))):
            if b not in chosen:
                chosen.append(b)
    return chosen


def java_probes(probes):
    rows = ",\n            ".join("{" + ", ".join(f"{v:.3f}F" for v in b) + "}" for b in probes)
    reach = max(math.sqrt(b[0] ** 2 + b[1] ** 2 + b[2] ** 2) for b in probes)
    return f"""package dev.galactica.ship;

/**
 * Collision probes on the Kestrel's outer hull, generated by assets-src/ship/gen_starship.py (do not edit by hand).
 * Body frame (+X port, +Y dorsal, +Z nose), blocks from the hull centre ({{@link ShipDimensions#PIVOT}} above the
 * entity origin). The vanilla collision box is square and does not turn with the hull, so it covers only the
 * fuselage and pods; these points stand for the nose, wings, fins and tail beyond it, the farthest, highest and lowest outer
 * corner in each of {PROBE_SECTORS} azimuth sectors. {len(probes)} points, farthest {reach:.2f} blocks out.
 */
public final class ShipHullProbes {{
    private ShipHullProbes() {{}}

    public static final float[][] POINTS = {{
            {rows}}};
}}
"""


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
    (ROOT / "src/main/java/dev/galactica/ship/ShipHullProbes.java").write_text(java_probes(hull_probes()), encoding="utf-8")
    stale = HERE / "StarshipModel.parts.txt"
    if stale.exists():
        stale.unlink()
    layers = {}
    for p in PARTS:
        layers[layer_of(p)] = layers.get(layer_of(p), 0) + 1
    print(f"{len(PARTS)} cubes, {6 * len(PARTS)} faces, layers {layers}")


if __name__ == "__main__":
    main()
