"""Writes the content area's item models, equipment asset, recipes and lang part. Run from the repo root."""
import json
import os

R = "src/main/resources"
A = R + "/assets/galactica"
D = R + "/data/galactica/recipe"


def w(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(json.dumps(obj, indent=2, ensure_ascii=False) + "\n")


ITEMS = ["eva_helmet", "eva_suit", "eva_leggings", "eva_boots", "oxygen_tank", "hydrogen_cell", "exotic_matter"]
for n in ITEMS:
    w(f"{A}/items/{n}.json", {"model": {"type": "minecraft:model", "model": f"galactica:item/{n}"}})
    w(f"{A}/models/item/{n}.json", {"parent": "minecraft:item/generated", "textures": {"layer0": f"galactica:item/{n}"}})

w(f"{A}/equipment/eva_suit.json",
  {"layers": {"humanoid": [{"texture": "galactica:eva_suit"}], "humanoid_leggings": [{"texture": "galactica:eva_suit"}],
             "humanoid_baby": [{"texture": "galactica:eva_suit"}]}})


def shaped(n, cat, pat, key, count=1):
    res = {"id": "galactica:" + n}
    if count > 1:
        res["count"] = count
    w(f"{D}/{n}.json", {"type": "minecraft:crafting_shaped", "category": cat, "key": key, "pattern": pat, "result": res})


I, L, G = "minecraft:iron_ingot", "minecraft:leather", "minecraft:glass"
shaped("eva_helmet", "equipment", ["LIL", "IGI"], {"L": L, "I": I, "G": G})
shaped("eva_suit", "equipment", ["L L", "IGI", "LIL"], {"L": L, "I": I, "G": G})
shaped("eva_leggings", "equipment", ["ILI", "L L", "I I"], {"L": L, "I": I})
shaped("eva_boots", "equipment", ["L L", "I I"], {"L": L, "I": I})
shaped("oxygen_tank", "equipment", [" I ", "IBI", " I "], {"I": I, "B": "minecraft:glass_bottle"})
shaped("hydrogen_cell", "misc", ["R", "W", "B"],
       {"R": "minecraft:redstone", "W": "minecraft:water_bucket", "B": "minecraft:glass_bottle"})
shaped("exotic_matter", "misc", [" E ", "ANA", " E "],
       {"E": "minecraft:ender_eye", "A": "minecraft:amethyst_shard", "N": "minecraft:nether_star"}, 16)

EN = {
    "itemGroup.galactica": "Galactica",
    "item.galactica.eva_helmet": "EVA Helmet", "item.galactica.eva_suit": "EVA Suit",
    "item.galactica.eva_leggings": "EVA Leggings", "item.galactica.eva_boots": "EVA Boots",
    "item.galactica.oxygen_tank": "Oxygen Tank", "item.galactica.hydrogen_cell": "Hydrogen Cell",
    "item.galactica.exotic_matter": "Exotic Matter",
    "tooltip.galactica.oxygen": "O₂ %s / %s",
    "tooltip.galactica.eva_suit": "Full set seals the suit against vacuum, cold, heat and pressure",
    "tooltip.galactica.hydrogen_cell": "Drive fuel: 20 s of main thrust",
    "tooltip.galactica.exotic_matter": "Hyperjump fuel: 1 unit per light-year",
    "hud.galactica.vacuum": "Vacuum", "hud.galactica.cabin": "Cabin",
    "hud.galactica.breathable": "Air", "hud.galactica.toxic": "Unbreathable",
    "hud.galactica.o2": "O₂ suit %s   reserve %s",
    "hud.galactica.suit_sealed": "Suit sealed", "hud.galactica.suit_open": "Suit incomplete",
    "hud.galactica.suffocating": "⚠ NO OXYGEN", "hud.galactica.freezing": "⚠ FREEZING",
    "hud.galactica.overheating": "⚠ OVERHEATING", "hud.galactica.overpressure": "⚠ OVERPRESSURE",
}
PT = {
    "itemGroup.galactica": "Galactica",
    "item.galactica.eva_helmet": "Capacete EVA", "item.galactica.eva_suit": "Traje EVA",
    "item.galactica.eva_leggings": "Calças EVA", "item.galactica.eva_boots": "Botas EVA",
    "item.galactica.oxygen_tank": "Tanque de Oxigênio", "item.galactica.hydrogen_cell": "Célula de Hidrogênio",
    "item.galactica.exotic_matter": "Matéria Exótica",
    "tooltip.galactica.oxygen": "O₂ %s / %s",
    "tooltip.galactica.eva_suit": "O conjunto completo veda o traje contra vácuo, frio, calor e pressão",
    "tooltip.galactica.hydrogen_cell": "Combustível do motor: 20 s de empuxo principal",
    "tooltip.galactica.exotic_matter": "Combustível de hipersalto: 1 unidade por ano-luz",
    "hud.galactica.vacuum": "Vácuo", "hud.galactica.cabin": "Cabine",
    "hud.galactica.breathable": "Ar", "hud.galactica.toxic": "Irrespirável",
    "hud.galactica.o2": "O₂ traje %s   reserva %s",
    "hud.galactica.suit_sealed": "Traje vedado", "hud.galactica.suit_open": "Traje incompleto",
    "hud.galactica.suffocating": "⚠ SEM OXIGÊNIO", "hud.galactica.freezing": "⚠ CONGELANDO",
    "hud.galactica.overheating": "⚠ SUPERAQUECENDO", "hud.galactica.overpressure": "⚠ SOBREPRESSÃO",
}
assert EN.keys() == PT.keys()
w(f"{A}/lang/parts/content.json", {"en_us": EN, "pt_br": PT})
print("data written")
