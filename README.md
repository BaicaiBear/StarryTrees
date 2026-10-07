# Starry Trees

Starry Trees is a server-side Fabric world generation mod for Minecraft 26.2, built from [Starry Skies](https://github.com/DaFuqs/StarrySkies). It keeps the sphere generation content and replaces the normal Overworld and Nether terrain with infinite, seeded trees of floating spheres and bridges. The End uses the Starry Skies system generator. The three extra `starry_skies:*` dimensions, custom portal block, client skybox, and sphere advancements have been removed. The server sends only vanilla blocks, biomes, and dimension IDs to clients.

## World generation

The Overworld and Nether are generated on demand in 512×512 and 256×256 block regions. Each region contains a local three-dimensional tree. A deterministic parent-region rule joins all regions to the origin, so each dimension has one connected tree regardless of exploration order. Boundary spheres use globally seeded cells and can extend into neighboring regions, closing the former empty band without overlap. Regions contain up to about 285 Overworld or 230 Nether spheres, with at least two blocks between sphere surfaces. Bridges sample the catenary at half-block vertical intervals and add ladders in steep columns. Complete spheres keep bridge landings near the top; pond and bee hive spheres use landings on their usable surfaces. Biome regions are about 512 blocks wide, with separate low, middle, and high altitude choices. The biome catalog contains all 55 naturally generated Overworld biomes and all five Nether biomes from Minecraft 26.2; a particular explored area need not contain all of them. Each biome has a sphere and bridge palette in `assets/starry_skies/blueprint_biomes.json`. The End continues to use the Starry Skies system generator, with a larger central island, obsidian spikes, and restored sphere structure templates.

Each region's design is saved as a compressed snapshot under the world's `starrytrees/infinite-overworld/regions/` or `infinite-nether/regions/`. Keep these files and each dimension's `manifest.json` with the save. Rule versions are checked at startup; incompatible versions require a new world. This version does not load the earlier finite blueprint format. For a fresh test server, set `level-name` in `run/server.properties` to a new name before running `./gradlew runServer`.

The mod replaces Minecraft's `normal` world preset. New worlds using that preset receive Starry Trees generation in `minecraft:overworld`, `minecraft:the_nether`, and `minecraft:the_end`. Existing worlds retain the generators saved in their world data. Worlds that used the removed custom dimensions need migration before loading. Back up worlds before moving them between Minecraft versions.

Starry Trees 2.0 removes the fork's story books, story loot tables, story suspicious blocks, and cartographer story maps. Ordinary Starry Skies archaeology spheres remain.

## Requirements

- Minecraft Java Edition 26.2
- Fabric Loader 0.19.3 or newer
- Fabric API, Cloth Config, and Java 25

Install the mod and its dependencies on the server. Vanilla clients can join; the mod has no client entrypoint or synced custom sphere registries. The vanilla sky's dark lower horizon remains, especially near or below Y=0.

## Build

Use JDK 25 and run `./gradlew build`. The distributable JAR is written to `build/libs/`. `./gradlew auditInfiniteRegions -PauditCount=10000` stress checks the coordinate-only region generator. `python3 tools/verify_infinite_regions.py run/<world-name>/starrytrees` checks generated regions; add `--window-radius 1 --closed` to verify a connected origin window after its chunks have generated. `python3 tools/build_blueprint_biomes.py` rebuilds the biome catalog and visual overrides from the reviewed design document using the locally cached Minecraft 26.2 JAR.

## Credits and license

Original Starry Skies by DaFuqs. Starry Trees additions by BaicaiBear. See [LICENSE](LICENSE) for the LGPL-3.0 license text.
