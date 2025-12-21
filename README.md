# Starry Trees

**A Minecraft Fabric mod featuring spherical planetoids connected by bridges in a void dimension**

Modified by BaicaiBear based on the original Starry Skies mod by DaFuqs.

## Overview

Starry Trees transforms Minecraft's world generation into a unique experience where players explore floating spheres connected by procedurally-generated bridges. The mod generates three custom dimensions based on Overworld, Nether, and End equivalents, but with a completely different structure.

### What Makes This Special?

- **Blueprint-Based Generation**: Unlike typical Minecraft world generation, spheres and bridges are pre-generated using sophisticated algorithms (Poisson disk sampling, Delaunay triangulation, and MST-based connectivity) and stored as blueprints per world
- **Tree-Like Structure**: Spheres are organized into tree clusters, with bridges connecting them to form navigable paths
- **Story System**: Special story blocks are placed along bridges, containing lore that unfolds as players explore
- **315+ Sphere Types**: From simple stone spheres to complex structures with dungeons, ores, and biome-specific content

## Quick Start for Developers

### Prerequisites

- Java 21 or higher
- Gradle (wrapper included)
- Fabric Loader and Fabric API

### Building

```bash
./gradlew build
```

The compiled JAR will be in `build/libs/`.

### Running in Development

```bash
# Client
./gradlew runClient

# Server
./gradlew runServer

# Data Generation
./gradlew runDatagen
```

### Project Structure

```
src/main/java/de/dafuqs/starryskies/
├── StarrySkies.java              # Main mod entry point
├── StarrySkiesConfig.java        # Configuration (system size)
├── worldgen/
│   ├── BlueprintManager.java     # Manages sphere/bridge blueprints
│   ├── generator/
│   │   └── BlueprintGenerator.java  # Generates sphere layouts
│   ├── dimension/
│   │   ├── StarrySkyChunkGenerator.java       # Blueprint-based generation
│   │   └── StarrySkySystemChunkGenerator.java # Legacy system-based generation
│   ├── spheres/                  # 14 sphere type implementations
│   ├── decorators/               # Sphere surface decorators
│   └── Spheres.java              # Sphere registry
├── registries/                   # Dimension, biome, and registry keys
└── data_loaders/                 # Custom resource loaders

src/main/resources/
├── data/starry_skies/
│   └── starry_skies/
│       ├── configured_sphere/    # 315+ sphere definitions
│       ├── generation_group/     # Sphere grouping for generation
│       └── system_generator/     # Generation settings per dimension
├── assets/starry_skies/
│   ├── lang/                     # Translations (en_us, zh_cn)
│   ├── biome_weights/            # Biome spawn weights
│   └── textures/
└── data/starryskies/
    └── story.json                # Story content for bridge blocks
```

## Architecture Deep Dive

### 1. Blueprint Generation System

The core innovation of Starry Trees is its blueprint system, which pre-generates the entire sphere layout for a world.

#### Why Blueprints?

Traditional Minecraft generation is stateless - chunks generate independently. Starry Trees needs global structure (bridges connecting specific spheres), so it:

1. Generates a complete layout on first world load
2. Saves blueprints to `<world>/starrytrees/`
3. Loads blueprints for consistent generation across sessions

#### Generation Algorithm

**Phase 1: Poisson Disk Sampling** (`poissonSampling()`)
- Creates evenly-distributed points in a circular area (radius: 10000 for Overworld, 2500 for Nether)
- Minimum distance constraint (30-40 blocks) prevents overlapping spheres
- Generates 50,000 points for Overworld, 10,000 for Nether

**Phase 2: Delaunay Triangulation** (`MathUtils.triangulate()`)
- Builds connectivity graph between nearby points
- Uses constrained Delaunay triangulation for stable topology

**Phase 3: Voronoi Partitioning** (`partitionAndGenerate()`)
- Divides points into tree clusters (10 for Overworld, 1 for Nether)
- Domain warping creates organic, wavy river boundaries between clusters
- Points too close to boundaries are removed (become rivers/void)

**Phase 4: Spanning Tree Generation** (per cluster)
- Builds Minimum Spanning Tree within each cluster
- Ensures all spheres are reachable
- Creates tree-like structure with root node near cluster center

**Phase 5: Story Assignment**
- Each edge in the spanning tree gets a chapter (cluster ID) and part number
- Story blocks on bridges display corresponding text from `story.json`

**Phase 6: Biome & Sphere Assignment**
- Biomes assigned using Simplex noise
- Sphere types selected based on biome and generation groups

#### Blueprint Files

Generated blueprints are saved as JSON:

**`overworld_blueprint.json`** / **`nether_blueprint.json`**
```json
{
  "nodes": [
    {
      "x": 1234.5,
      "z": 5678.9,
      "y": 90,
      "radius": 15,
      "type": "overworld/wood/oak_wood"
    },
    ...
  ]
}
```

**`overworld_bridges.json`** / **`nether_bridges.json`**
```json
{
  "edges": [
    {
      "from": 0,
      "to": 5,
      "weight": 234.5
    },
    ...
  ],
  "story": {
    "0-5": [0, 1],  // Edge from node 0 to 5: Chapter 0, Part 1
    ...
  }
}
```

**`overworld_blueprint.png`** / **`nether_blueprint.png`**
- Visual representation of the generated layout
- Green = spheres, White = bridges, Red = roots

### 2. Chunk Generation

When a chunk is requested, `StarrySkyChunkGenerator`:

1. **Queries Blueprint** (`BlueprintManager.getSpheresInChunk()`)
   - Uses KD-tree spatial indexing for fast sphere lookup
   - Finds all spheres that overlap with the chunk

2. **Generates Bridges** (`carveBridges()`)
   - Checks if any bridge edges pass through the chunk
   - Places bridge blocks (wood planks) with gradual height transitions
   - Embeds story blocks (suspicious sand/gravel) at intervals

3. **Generates Spheres** (`carve()`)
   - For each sphere overlapping chunk, delegates to sphere type handler
   - Sphere types use various algorithms (geometric shapes, noise-based caves, etc.)
   - Applies decorators (plants, ores, structures) to sphere surface

### 3. Sphere System

#### Sphere Types

14 base sphere types with different generation patterns:

| Type | Description | Use Cases |
|------|-------------|-----------|
| `SIMPLE` | Solid sphere of one block type | Basic resource spheres (stone, dirt) |
| `SHELL` | Core with outer shell | Wood spheres (log core, leaf shell) |
| `CORE` | Multi-layered concentric shells | Ore spheres with layers |
| `MODULAR` | Layer-based configurable | Complex compositions |
| `CAVE` | Hollow with 3D noise caves | Cave exploration spheres |
| `FLUID` | Filled with fluid | Water/lava spheres |
| `FLUID_CORE` | Fluid center, solid shell | Lava core with obsidian shell |
| `SHELL_CORE` | Multiple shells + core | Nested layer spheres |
| `MUSHROOM` | Tree-like mushroom shape | Mushroom biome spheres |
| `HORIZONTAL_STACKED` | Horizontal layers | Beach/sand/water layers |
| `STRUCTURE_INTERIOR` | Contains structure | Stronghold, fortress, end city |
| `GEODE` | Crystalline interior | Amethyst geodes |
| `CORALS` | Coral formations | Ocean spheres |
| `BEE_HIVE` | Honeycomb pattern | Bee-themed spheres |
| `OCEAN_MONUMENT` | Guardian monument | Ocean monument spheres |

#### Sphere Configuration

Each sphere is defined in JSON under `data/starry_skies/starry_skies/configured_sphere/`:

```json
{
  "type": "starry_skies:shell",
  "config": {
    "size": {
      "type": "minecraft:uniform",
      "min_inclusive": 8,
      "max_exclusive": 15
    },
    "main_block": {
      "type": "minecraft:simple_state_provider",
      "state": { "Name": "minecraft:oak_log" }
    },
    "shell_block": {
      "type": "minecraft:simple_state_provider",
      "state": { "Name": "minecraft:oak_leaves" }
    },
    "shell_thickness": {
      "type": "minecraft:uniform",
      "min_inclusive": 2,
      "max_inclusive": 3
    },
    "decorators": { ... },
    "spawns": [ ... ],
    "generation": {
      "weight": 10.0,
      "group": "starry_skies:overworld/wood"
    }
  }
}
```

#### Sphere Categories

Spheres are organized by dimension and category:

**Overworld** (`overworld/`)
- `essential/` - Basic stone/dirt spheres
- `wood/` - Tree spheres (oak, birch, spruce, jungle, etc.)
- `ores/` - Ore-filled spheres (coal, iron, gold, diamond, etc.)
- `fluid/` - Water/lava spheres
- `decorative/` - Glass, ice, colored blocks
  - `rainbow/` - Rainbow-colored sphere sets
  - `pride/` - Pride flag themed spheres
- `dungeon/` - Mob spawner dungeons
- `treasure/` - Rare/valuable spheres

**Nether** (`nether/`)
- `essential/` - Netherrack, basalt, blackstone
- `wood/` - Crimson/warped wood
- `ores/` - Nether ores (quartz, gold, ancient debris)
- `fluid/` - Lava spheres
- `decorative/` - Nether-themed decorative
- `dungeon/` - Nether mob spawners
- `treasure/` - Nether fortress pieces

**End** (`end/`)
- `essential/` - End stone
- `decorative/` - End-themed blocks
- `dungeon/` - End mob spawners
- `treasure/` - End city pieces

**Spawn** (`spawn/`)
- Special spheres that always spawn at world origin (16, 90, 16)

### 4. Decorators

Decorators add surface features to spheres after generation:

| Decorator | Purpose |
|-----------|---------|
| `SingleBlockDecorator` | Places single blocks (flowers, mushrooms) |
| `DoubleBlockDecorator` | Places tall plants |
| `GroundBlockDecorator` | Covers surface (grass, snow) |
| `PlantAroundPondDecorator` | Generates small water ponds with plants |
| `HangingBlockDecorator` | Hangs vines, glow lichen |
| `CaveColumnDecorator` | Adds stalactites/stalagmites |
| `LootChestDecorator` | Places loot chests |
| `MobSpawnerDecorator` | Adds mob spawners |
| `RuinedPortalDecorator` | Generates ruined portals |
| `BrushableBlockDecorator` | Places suspicious blocks |

Decorators are configured per sphere in the `decorators` section.

### 5. Generation Groups

Generation groups control sphere spawn rates:

**File**: `data/starry_skies/starry_skies/generation_group/end/<category>.json`

```json
{
  "weight": 10.0,
  "system_generator": "starry_skies:end"
}
```

Higher weight = more common. Spheres reference groups in their `generation.group` field.

### 6. Portal System

**Overworld Access**:
- Build packed ice frame (like nether portal)
- Light with flint & steel
- Configurable in `StarrySkiesConfig`

**Nether/End Access**:
- Standard nether/end portals within Starry dimensions
- Can be disabled in config

### 7. Story System

Story content is defined in `data/starryskies/story.json`:

```json
{
  "title": "The Starry Chronicles",
  "author": "BaicaiBear",
  "chapters": [
    {
      "title": "The Awakening",
      "parts": [
        ["Line 1", "Line 2"],
        ["Next part line 1", "Next part line 2"],
        ...
      ]
    },
    ...
  ]
}
```

During bridge generation:
- Each edge gets assigned `[chapter, part]` based on cluster ID and position
- Story blocks (brushable suspicious blocks) are placed at intervals
- Breaking while sneaking reveals the story text
- Non-sneak breaking is prevented with a warning message

### 8. Biome System

Three custom biomes:
- `starry_skies:overworld` - "Starry Sky"
- `starry_skies:nether` - "Scary Sky"
- `starry_skies:end` - "Scarcy Sky"

Biome weights (in `assets/starry_skies/biome_weights/`) control vanilla mob spawns per biome.

## Extending the Mod

### Adding a New Sphere Type

1. **Create Java Implementation**:
```java
public class MySphere extends Sphere<MySphere.Config> {
    public MySphere(MapCodec<Config> codec) { super(codec); }
    
    @Override
    public boolean place(/* ... */) {
        // Generation logic
    }
    
    public record Config(/* fields */) implements SphereConfig { }
}
```

2. **Register in `Spheres.java`**:
```java
public static final Sphere<MySphere.Config> MY_SPHERE = 
    register("my_sphere", new MySphere(MySphere.Config.CODEC));
```

3. **Create JSON configuration** in `configured_sphere/`:
```json
{
  "type": "starry_skies:my_sphere",
  "config": { /* ... */ }
}
```

### Adding New Sphere Definitions

Simply add JSON files to `data/starry_skies/starry_skies/configured_sphere/` using existing sphere types.

### Mod Compatibility

The `goodies/` directory contains optional sphere definitions for other mods:
- `mod_compat_spheres/` - Sphere definitions using other mods' blocks
- `mod_compat_decorators/` - Decorators using other mods' features

These are not included by default but can be copied to the data directory.

## Configuration

**`StarrySkiesConfig.java`** (currently limited):
- `systemSizeChunks` - Affects legacy system generator (not used in blueprint mode)

**In-game config** (via Cloth Config and ModMenu):
- Portal frame block
- Portal color
- Cloud height
- Rainbow skybox toggle
- Nether/End portal behavior
- Command permissions

## Performance Considerations

### Blueprint Generation
- Only happens once per world (or when files are deleted)
- Can take 10-30 seconds for initial generation
- Visualizer generates PNG - disable for faster startup

### Chunk Generation
- KD-tree spatial indexing makes sphere queries O(log n)
- Bridge generation checks only nearby edges
- Sphere generation is localized to relevant chunks

### Memory
- Blueprint data kept in memory (< 10MB for Overworld)
- KD-tree structure for fast spatial queries
- Bridge index maps chunk positions to edges

## Debugging

### Locate Command
```
/starry_skies_locate <sphere_type>
```
Finds nearest sphere of specified type. Requires permission level (configurable).

### Blueprint Visualization
Blueprint PNGs are generated in world directory:
- Green dots = sphere positions
- White lines = bridges
- Red dots = tree roots

### Logs
Check `logs/latest.log` for:
- Blueprint generation progress
- Sphere placement details
- Missing spheres/decorators warnings

## Translation

Translations in `assets/starry_skies/lang/`:
- `en_us.json` - English (US)
- `zh_cn.json` - Simplified Chinese

Add keys following pattern:
```json
{
  "advancements.starry_skies.<sphere_name>.title": "Sphere: Name",
  "advancements.starry_skies.<sphere_name>.description": "Find your first Name sphere"
}
```

## Credits

- **Original Mod**: Starry Skies by DaFuqs
- **Modified by**: BaicaiBear (blueprint system, tree structure, story blocks, bridge generation)
- **Inspired by**: Planetoids world generator by Seibai (Minecraft 1.2.5, 2012)

## License

MIT License - See LICENSE file

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Test thoroughly
5. Submit a pull request

## Links

- **GitHub**: https://github.com/BaicaiBear/StarryTrees
- **Issues**: https://github.com/BaicaiBear/StarryTrees/issues
