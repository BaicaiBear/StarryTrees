#!/usr/bin/env python3
"""Build the 26.2 blueprint biome catalog and visual overrides from the reviewed plan."""

from __future__ import annotations

import json
import re
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PLAN = ROOT / "docs/MC26.2_WORLDGEN_PROPOSAL.md"
SPHERES = ROOT / "src/main/resources/data/starry_skies/starry_skies/configured_sphere"
ASSETS = ROOT / "src/main/resources/assets/starry_skies"
GAME_JAR = Path.home() / ".gradle/caches/fabric-loom/26.2/minecraft-merged.jar"

COLORS = {
    "A": ("#78A7FF", None), "B": ("#91B8FF", "#B5D0E8"),
    "C": ("#AFC6D5", "#B4C4C7"), "D": ("#C9DFEF", "#D6E7EF"),
    "E": ("#E2CDA5", "#D9C49D"), "F": ("#93BBDF", "#8EAFA1"),
    "G": ("#8194A3", "#788B8F"), "H": ("#899E9D", "#667D76"),
    "I": ("#B9B9B9", "#817770"), "J": ("#74BDD8", "#5FADC0"),
    "K": ("#86AACB", "#6487A6"), "L": ("#68839C", "#4E6683"),
    "M": ("#A0C58F", "#698F70"), "N": ("#8B8E98", "#5E626D"),
    "O": ("#B5A36D", "#8CB831"),
}

SLABS = {
    "橡木": "oak_slab", "圆石": "cobblestone_slab", "竹马赛克": "bamboo_mosaic_slab",
    "樱花木": "cherry_slab", "桦木": "birch_slab", "苔石": "mossy_cobblestone_slab",
    "深色橡木": "dark_oak_slab", "苍白橡木": "pale_oak_slab", "石砖": "stone_brick_slab",
    "磨制闪长岩": "polished_diorite_slab", "云杉": "spruce_slab", "平滑石": "smooth_stone_slab",
    "闪长岩": "diorite_slab", "石台阶": "stone_slab", "磨制凝灰岩": "polished_tuff_slab",
    "平滑砂岩": "smooth_sandstone_slab", "砂岩": "sandstone_slab", "红砂岩": "red_sandstone_slab",
    "磨制朱砂": "polished_cinnabar_slab", "金合欢": "acacia_slab", "丛林木": "jungle_slab",
    "泥砖": "mud_brick_slab", "红树木": "mangrove_slab", "海晶石": "prismarine_slab",
    "深色海晶石": "dark_prismarine_slab", "苔石砖": "mossy_stone_brick_slab",
    "凝灰岩": "tuff_slab", "深板岩砖": "deepslate_brick_slab",
    "磨制深板岩": "polished_deepslate_slab", "磨制硫磺": "polished_sulfur_slab",
    "朱砂砖": "cinnabar_brick_slab", "下界砖": "nether_brick_slab",
    "红色下界砖": "red_nether_brick_slab", "平滑石英": "smooth_quartz_slab",
    "磨制黑石": "polished_blackstone_slab", "绯红木": "crimson_slab", "诡异木": "warped_slab",
    "黑石": "blackstone_slab", "磨制黑石砖": "polished_blackstone_brick_slab",
    "末地石砖": "end_stone_brick_slab", "紫珀": "purpur_slab",
}


def category(biome: str) -> str:
    if biome in {"basalt_deltas", "crimson_forest", "nether_wastes", "soul_sand_valley", "warped_forest"}:
        return "nether"
    if biome in {"the_end", "end_highlands", "end_midlands", "end_barrens", "small_end_islands"}:
        return "end"
    if biome in {"lush_caves", "dripstone_caves", "deep_dark", "sulfur_caves"}:
        return "cave"
    if "ocean" in biome:
        return "ocean"
    if biome in {"frozen_peaks", "jagged_peaks", "stony_peaks", "snowy_slopes", "grove", "meadow"} or biome.startswith("windswept_"):
        return "mountain"
    if biome in {"snowy_plains", "ice_spikes", "snowy_taiga", "taiga", "old_growth_pine_taiga", "old_growth_spruce_taiga", "frozen_river", "snowy_beach"}:
        return "cold"
    if biome in {"desert", "badlands", "eroded_badlands", "wooded_badlands", "savanna", "savanna_plateau", "windswept_savanna", "jungle", "sparse_jungle", "bamboo_jungle", "warm_ocean", "lukewarm_ocean", "deep_lukewarm_ocean"}:
        return "warm"
    if biome in {"river", "beach", "stony_shore"}:
        return "shore"
    return "temperate"


def sphere_size(identifier: str, known: dict[str, list[int]]) -> list[int]:
    if identifier in known:
        return known[identifier]
    path = SPHERES / (identifier.split(":", 1)[1] + ".json")
    config = json.loads(path.read_text())["config"]
    size = config.get("size", {})
    if size.get("type") == "minecraft:uniform":
        return [int(size["min_inclusive"]), int(size["max_exclusive"]) - 1]
    return [5, 12]


def main() -> None:
    plan = PLAN.read_text()
    recipe_part = plan.split("## 2. 球体配方", 1)[1].split("## 3. 66 个群系的分配表", 1)[0]
    table_part = plan.split("## 3. 66 个群系的分配表", 1)[1].split("### 群系分配的实现约束", 1)[0]

    known_sizes = {}
    output = ASSETS / "blueprint_biomes.json"
    if output.exists():
        for entry in json.loads(output.read_text()).values():
            for item in entry["spheres"]:
                known_sizes[item["id"]] = item["size"]

    recipes = {}
    for line in recipe_part.splitlines():
        match = re.match(r"^\| ([PNE]\d\d) [^|]+ \| ([^|]+) \|", line)
        if not match:
            continue
        items = []
        for dim, name, weight in re.findall(r"`([one]):([^`]+)`\s+(\d+)", match[2]):
            identifier = "starry_skies:" + {"o": "overworld", "n": "nether", "e": "end"}[dim] + "/" + name
            assert (SPHERES / (identifier.split(":", 1)[1] + ".json")).exists(), identifier
            items.append({"id": identifier, "size": sphere_size(identifier, known_sizes), "weight": int(weight)})
        assert sum(item["weight"] for item in items) == 100, match[1]
        recipes[match[1]] = items

    catalog = {}
    for line in table_part.splitlines():
        cells = [cell.strip() for cell in line.split("|")]
        if len(cells) < 6 or not re.match(r"^[PNE]\d\d$", cells[2]):
            continue
        for biome in re.findall(r"`([a-z_]+)`", cells[1]):
            if biome == "the_void":
                continue
            parts = re.findall(r"([^/\d]+?)\s+(\d+)(?=\s*/|$)", cells[3])
            bridge = [["minecraft:" + SLABS[name.strip()], int(weight)] for name, weight in parts]
            assert bridge and sum(weight for _, weight in bridge) == 100, (biome, cells[3])
            color_code = cells[4].split(" ")[0]
            if color_code not in COLORS:
                color_code = None
            catalog[biome] = {"profile": cells[2], "category": category(biome),
                              "spheres": recipes[cells[2]], "bridge": bridge,
                              "sky": COLORS[color_code][0] if color_code else None,
                              "fog": COLORS[color_code][1] if color_code else None}
            if biome.startswith("deep_") and "ocean" in biome:
                catalog[biome]["sky"], catalog[biome]["fog"] = COLORS["L"]

    with zipfile.ZipFile(GAME_JAR) as jar:
        vanilla = {name.rsplit("/", 1)[-1][:-5] for name in jar.namelist()
                   if name.startswith("data/minecraft/worldgen/biome/") and name.endswith(".json")}
        assert vanilla - {"the_void"} == set(catalog), sorted(vanilla - set(catalog))
        for biome, entry in catalog.items():
            if entry["sky"] is None:
                continue
            source = json.loads(jar.read(f"data/minecraft/worldgen/biome/{biome}.json"))
            attributes = source.setdefault("attributes", {})
            attributes["minecraft:visual/sky_color"] = entry["sky"].lower()
            if entry["fog"]:
                attributes["minecraft:visual/fog_color"] = entry["fog"].lower()
            path = ROOT / f"src/main/resources/data/minecraft/worldgen/biome/{biome}.json"
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(json.dumps(source, indent=2) + "\n")

    output.write_text(json.dumps(catalog, indent=2) + "\n")
    print(f"Wrote {len(catalog)} biome entries with {len(recipes)} recipes to {output}")


if __name__ == "__main__":
    main()
