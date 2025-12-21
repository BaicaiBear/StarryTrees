# Starry Trees - Player Guide

## World Overview

Starry Trees creates a world of floating spherical planetoids connected by wooden bridges in the void. You'll explore a network of 50,000 spheres (Overworld) or 10,000 spheres (Nether) organized into tree-like clusters.

## What to Expect

### Dimensions

**Starry Sky (Overworld)**
- Access via packed ice portal (configurable)
- 50,000 spheres across 20km diameter
- 10 separate tree clusters with river-like gaps between them
- 9 biomes: warm_ocean, forest, deep_frozen_ocean, swamp, snowy_taiga, lush_caves, desert, stony_peaks, frozen_peaks
- Heights vary by biome (-64 to 320 blocks)
- Spawn sphere at X:16, Y:90, Z:16 (oak wood sphere, always present)

**Scary Sky (Nether)**
- Access via nether portal from Starry Sky
- 10,000 spheres across 5km diameter
- 1 connected tree structure
- 5 biomes: basalt_deltas, crimson_forest, nether_wastes, soul_sand_valley, warped_forest
- Standard nether mobs and resources

**Scarcy Sky (End)**
- Access via End portal in stronghold spheres
- End-themed spheres and structures

### Sphere Categories

Spheres are organized by type and distributed based on biome:

**Essential Spheres** - Stone, cobblestone, dirt, grass, sand, gravel, various stone types (andesite, granite, diorite, deepslate, tuff)

**Wood Spheres** - Oak, birch, spruce, jungle, acacia, dark oak, azalea, crimson (nether), warped (nether). Structure: log core with leaf shell.

**Ore Spheres** - Coal, copper, iron, lapis, redstone, gold, diamond, emerald. The entire sphere is composed of the ore-bearing blocks.

**Fluid Spheres** - Water, lava, with variants like packed ice, blue ice, magma blocks.

**Decorative Spheres** - Glass, stained glass, ice, prismarine, amethyst, rainbow sets (wool, concrete, terracotta, glass in all colors).

**Cave Spheres** - Hollow spheres with cave-like interiors and decorators.

**Structure Spheres** - Contain Minecraft structures: stronghold (with End portal), nether fortress, ocean monument, End city.

**Dungeon Spheres** - Mob spawners with loot chests.

**Special Spheres** - Bee hive, mushroom, coral, unique formations.

### Bridges & Navigation

- **Wooden bridges** automatically generated following tree structure edges
- **Story blocks** placed at intervals (brushable suspicious sand/gravel)
- Hold Shift to break story blocks and read lore
- Bridge network follows Minimum Spanning Tree, ensuring connectivity

### Story System

10 chapters of "The Starry Chronicles" embedded in story blocks:
1. The Awakening
2. The Bridge Builders
3. Whispers of the Void
4. The Amber Staff
5. Skyfall
6. Roots of Despair
7. Starlight's End
8. The New Dawn
9. Echoes of the Past
10. Eternal Canopy

Each tree cluster has its own chapter, with parts distributed along its bridges.

### Resources & Distribution

**Key Differences from Vanilla**:
- No underground ore veins - ores are concentrated in dedicated spheres
- No infinite terrain - resource spheres are limited but numerous
- Biomes determine sphere type probabilities, not continuous terrain
- Height affects sphere types (high-altitude biomes spawn at higher Y levels)

**Findable Resources**:
- All vanilla overworld resources available in sphere form
- Nether resources in dedicated Scary Sky spheres
- Ancient debris in rare nether spheres (entire sphere is ancient debris)
- Structures (strongholds, fortresses, monuments) in dedicated spheres

### Commands

`/starry_skies_locate <sphere_type>` - Find nearest sphere of specified type (requires permission)

### Configuration

Access via in-game config menu (requires ModMenu + Cloth Config):
- Portal frame block customization
- Portal color
- Dimension portal behavior (nether/end destinations)
- Rainbow skybox toggle
- Cloud height
- Command permissions

### Technical Notes

**Blueprint Generation**:
- Happens once per world on first load
- Stored in `<world>/starrytrees/` directory
- Includes visualization PNG showing sphere positions and bridge network
- Uses world seed for deterministic generation

**Performance**:
- KD-tree spatial indexing for efficient sphere queries
- Blueprint pre-computation prevents runtime overhead
- Chunk generation queries pre-computed structures

## Resources

- GitHub: https://github.com/BaicaiBear/StarryTrees
- Issues: https://github.com/BaicaiBear/StarryTrees/issues
