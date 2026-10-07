"""Generates Galactica planet-surface datapack JSON: 16 slot dimensions, the shared dimension type,
one biome per (landable body type, sky class), and the planet lang part. Run from the repository root: python tools/planet/gen_planet_data.py ."""
import json
import os
import sys

ROOT = sys.argv[1]
DATA = os.path.join(ROOT, "src/main/resources/data/galactica")
ASSETS = os.path.join(ROOT, "src/main/resources/assets/galactica")
SLOTS = 16

TYPES = ["lava", "barren", "desert", "greenhouse", "terran", "ocean", "ice"]
TYPE_LABEL = {
    "lava": ("Lava", "de lava"), "barren": ("Barren", "árido"), "desert": ("Desert", "desértico"),
    "greenhouse": ("Greenhouse", "estufa"), "terran": ("Temperate", "temperado"),
    "ocean": ("Ocean", "oceânico"), "ice": ("Ice", "gelado"),
}
# Must match SurfaceAtmosphere.SkyClass (sky, fog, cloud ARGB or None for the dimension default).
SKIES = {
    "blue": ("#78a7ff", "#c0d8ff", None),
    "butterscotch": ("#c9a37a", "#d9b48f", "#00000000"),
    # Opaque decks (Venus, steam worlds, Titan's haze) are a continuous overcast: the sky colour carries it,
    # and vanilla's cubic clouds would only draw stray cells (seen black from below at 92 bar).
    "orange": ("#d2873a", "#c0782f", "#00000000"),
    "yellow": ("#e6d8a2", "#e9ddb0", "#00000000"),
    "steam": ("#d9dcdf", "#e4e6e8", "#00000000"),
    "black": ("#000000", "#050505", "#00000000"),
}
SKY_LABEL = {
    "blue": ("blue sky", "céu azul"), "butterscotch": ("dusty sky", "céu de poeira"),
    "orange": ("hazy sky", "céu de névoa"), "yellow": ("sulphur sky", "céu de enxofre"),
    "steam": ("steam sky", "céu de vapor"), "black": ("airless", "sem ar"),
}
TEMPERATURE = {"lava": 2.0, "desert": 2.0, "greenhouse": 2.0, "ice": -0.5}
WATER = {"terran": "#3f76e4", "ocean": "#2d5fcf", "ice": "#3d57d6"}

ORES = ["minecraft:ore_coal_upper", "minecraft:ore_coal_lower", "minecraft:ore_iron_upper", "minecraft:ore_iron_middle",
        "minecraft:ore_iron_small", "minecraft:ore_gold", "minecraft:ore_gold_lower", "minecraft:ore_redstone",
        "minecraft:ore_redstone_lower", "minecraft:ore_diamond", "minecraft:ore_diamond_medium",
        "minecraft:ore_diamond_large", "minecraft:ore_diamond_buried", "minecraft:ore_lapis",
        "minecraft:ore_lapis_buried", "minecraft:ore_copper"]
PLANTS = ["minecraft:trees_plains", "minecraft:flower_plains", "minecraft:patch_grass_plain"]

NO_SPAWNS = {
    "argument": {"spawn_costs": {}, "spawns_by_category": {
        k: [] for k in ["ambient", "axolotls", "creature", "misc", "monster",
                        "underground_water_creature", "water_ambient", "water_creature"]}},
    "modifier": "overlay",
}


def write(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(obj, f, indent=2, ensure_ascii=False)
        f.write("\n")


biome_ids = []
for t in TYPES:
    for s, (sky, fog, cloud) in SKIES.items():
        name = f"planet_{t}_{s}"
        biome_ids.append(f"galactica:{name}")
        attrs = {
            "minecraft:visual/sky_color": sky,
            "minecraft:visual/fog_color": fog,
            "minecraft:gameplay/natural_mob_spawns": NO_SPAWNS,
        }
        if cloud is not None:
            attrs["minecraft:visual/cloud_color"] = cloud
        if s == "black":
            attrs["minecraft:visual/sunrise_sunset_color"] = "#00000000"
        features = [[] for _ in range(11)]
        features[6] = ORES
        if t == "terran" and s == "blue":
            features[9] = PLANTS
        wet = t in ("terran", "ocean") and s in ("blue", "steam")
        write(os.path.join(DATA, "worldgen/biome", name + ".json"), {
            "attributes": attrs,
            "carvers": [],
            "downfall": 0.5 if wet else 0.0,
            "effects": {"water_color": WATER.get(t, "#3f76e4")},
            "features": features,
            "has_precipitation": wet,
            "temperature": TEMPERATURE.get(t, 0.8),
        })

# Y -512..1279 (112 sections). Must match TerrainSampler.MIN_Y/MAX_Y; the top stays below the entry rows
# (TransferAltitude.ENTRY_Y 1500 minus the 64-block arrival band and the 100-block touchdown margin).
write(os.path.join(DATA, "dimension_type/surface.json"), {
    "ambient_light": 0.0,
    "attributes": {
        "minecraft:visual/cloud_color": "#ccffffff",
        "minecraft:visual/cloud_height": 320.0,
        "minecraft:visual/fog_color": "#c0d8ff",
        "minecraft:visual/sky_color": "#78a7ff",
        "minecraft:gameplay/can_start_raid": False,
        "minecraft:gameplay/can_pillager_patrol_spawn": False,
        "minecraft:gameplay/respawn_anchor_works": False,
    },
    "coordinate_scale": 1.0,
    "default_clock": "minecraft:overworld",
    "has_ceiling": False,
    "has_ender_dragon_fight": False,
    "has_skylight": True,
    "height": 1792,
    "infiniburn": "#minecraft:infiniburn_overworld",
    "logical_height": 1792,
    "min_y": -512,
    "monster_spawn_block_light_limit": 0,
    "monster_spawn_light_level": 0,
    "skybox": "overworld",
    "timelines": "#minecraft:in_overworld",
})

for k in range(SLOTS):
    write(os.path.join(DATA, f"dimension/surface_{k}.json"), {
        "type": "galactica:surface",
        "generator": {
            "type": "galactica:planet",
            "slot": k,
            "biome_source": {"type": "galactica:planet", "slot": k, "biomes": biome_ids},
        },
    })

en, pt = {}, {}
for t in TYPES:
    for s in SKIES:
        en[f"biome.galactica.planet_{t}_{s}"] = f"{TYPE_LABEL[t][0]} world, {SKY_LABEL[s][0]}"
        pt[f"biome.galactica.planet_{t}_{s}"] = f"Mundo {TYPE_LABEL[t][1]}, {SKY_LABEL[s][1]}"
msgs = {
    "galactica.planet.entering": ("Entering %s: %s g, %s bar", "Entrando em %s: %s g, %s bar"),
    "galactica.planet.leaving": ("Leaving %s", "Saindo de %s"),
    "galactica.planet.no_slot": ("All %s landing sites hold other worlds. Restart the server to recycle the oldest.",
                                 "Todos os %s locais de pouso guardam outros mundos. Reinicie o servidor para reciclar os mais antigos."),
    "galactica.planet.not_landable": ("%s (%s) has no solid surface to land on", "%s (%s) não tem superfície sólida para pousar"),
    "galactica.planet.unknown": ("No body named %s", "Nenhum corpo chamado %s"),
    "galactica.planet.unreachable": ("Cannot fix a landing site on %s from here", "Não é possível definir um local de pouso em %s daqui"),
    "galactica.planet.heating": ("Entry heating", "Aquecimento de reentrada"),
    "galactica.planet.descent_over": ("Descent flight ended", "Voo de descida encerrado"),
}
for k, (e, p) in msgs.items():
    en[k] = e
    pt[k] = p
# Merge into the game's lang files (other areas own the rest of their keys).
for code, entries in (("en_us", en), ("pt_br", pt)):
    path = os.path.join(ASSETS, "lang", code + ".json")
    merged = json.load(open(path, encoding="utf-8")) if os.path.exists(path) else {}
    merged.update(entries)
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(merged, fh, indent=2, ensure_ascii=False)
        fh.write(chr(10))
print(len(biome_ids), "biomes,", SLOTS, "dimensions")
