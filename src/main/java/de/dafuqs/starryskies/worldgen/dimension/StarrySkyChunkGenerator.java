package de.dafuqs.starryskies.worldgen.dimension;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.jetbrains.annotations.NotNull;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import de.dafuqs.starryskies.StarrySkies;
import de.dafuqs.starryskies.registries.StarryRegistryKeys;
import de.dafuqs.starryskies.worldgen.BlueprintManager;
import de.dafuqs.starryskies.worldgen.ConfiguredSphere;
import de.dafuqs.starryskies.worldgen.PlacedSphere;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.enums.SlabType;
import net.minecraft.registry.Registry;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.random.CheckedRandom;
import net.minecraft.util.math.random.ChunkRandom;
import net.minecraft.util.math.random.RandomSeed;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BrushableBlockEntity;
import net.minecraft.loot.LootTables;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.registry.RegistryKey;
import net.minecraft.loot.LootTable;
import net.minecraft.world.ChunkRegion;
import net.minecraft.world.HeightLimitView;
import net.minecraft.world.Heightmap;
import net.minecraft.world.SpawnHelper;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeKeys;
import net.minecraft.world.biome.source.BiomeAccess;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.Blender;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.chunk.VerticalBlockSample;
import net.minecraft.world.gen.noise.NoiseConfig;

public class StarrySkyChunkGenerator extends ChunkGenerator {

	public static final MapCodec<StarrySkyChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(
			(instance) -> instance.group(
					BiomeSource.CODEC.fieldOf("biome_source").forGetter((generator) -> generator.biomeSource),
					Identifier.CODEC.fieldOf("blueprint_id").forGetter((generator) -> generator.blueprintId))
					.apply(instance, StarrySkyChunkGenerator::new));

	private final Identifier blueprintId;

	public StarrySkyChunkGenerator(BiomeSource biomeSource, Identifier blueprintId) {
		super(biomeSource);
		this.blueprintId = blueprintId;
	}

	public Identifier getBlueprintId() {
		return this.blueprintId;
	}

	@Override
	protected MapCodec<? extends ChunkGenerator> getCodec() {
		return CODEC;
	}

	@Override
	public void buildSurface(ChunkRegion region, StructureAccessor structures, NoiseConfig noiseConfig, Chunk chunk) {
		// No floor generation in blueprint mode
	}

	@Override
	public void carve(ChunkRegion chunkRegion, long seed, NoiseConfig noiseConfig, BiomeAccess biomeAccess,
			StructureAccessor structureAccessor, Chunk chunk) {

		// 1. Generate Bridges
		generateBridges(chunk, chunkRegion, noiseConfig);

		// 2. Generate Spheres
		// 2. Generate Spheres
		List<BlueprintManager.BlueprintNode> nodes = BlueprintManager.get().getSpheresInChunk(this.blueprintId,
				chunk.getPos());

		Registry<ConfiguredSphere<?, ?>> sphereRegistry = chunkRegion.getRegistryManager()
				.getOrThrow(StarryRegistryKeys.CONFIGURED_SPHERE);

		for (BlueprintManager.BlueprintNode node : nodes) {
			Identifier sphereId = Identifier.tryParse(node.type);
			if (sphereId == null || sphereId.getNamespace().equals("minecraft")) {
				if (!node.type.contains(":")) {
					sphereId = Identifier.of(StarrySkies.MOD_ID, node.type);
				}
			}

			ConfiguredSphere<?, ?> configuredSphere = sphereRegistry.get(sphereId);

			// Fallback: Try prefixing based on dimension/blueprint
			if (configuredSphere == null) {
				String fallbackPrefix = this.blueprintId.getPath().contains("nether") ? "nether/" : "overworld/";
				if (!node.type.startsWith(fallbackPrefix)) {
					Identifier fallbackId = Identifier.of(StarrySkies.MOD_ID, fallbackPrefix + node.type);
					configuredSphere = sphereRegistry.get(fallbackId);
					if (configuredSphere != null) {
						sphereId = fallbackId;
					}
				}
			}

			if (configuredSphere != null) {
				BlockPos pos = new BlockPos(node.getX(), node.getY(), node.getZ());
				ChunkRandom random = new ChunkRandom(new CheckedRandom(seed));
				random.setCarverSeed(seed, node.getX(), node.getZ());

				PlacedSphere<?> sphere = configuredSphere.generate(random, chunkRegion.getRegistryManager(), pos,
						(float) node.radius);
				sphere.setPosition(pos);

				boolean intersects = sphere.isInChunk(chunk.getPos());

				if (intersects) {
					sphere.generate(chunk, chunkRegion.getRegistryManager());
				}
			}
		}
	}

	private void generateBridges(Chunk chunk, ChunkRegion chunkRegion, NoiseConfig noiseConfig) {
		ChunkPos chunkPos = chunk.getPos();
		int chunkMinX = chunkPos.getStartX();
		int chunkMaxX = chunkPos.getEndX();
		int chunkMinZ = chunkPos.getStartZ();
		int chunkMaxZ = chunkPos.getEndZ();

		List<BlueprintManager.Edge> bridges = BlueprintManager.get().getBridgesInRegion(this.blueprintId,
				chunkPos.getCenterX(),
				chunkPos.getCenterZ());

		if (bridges == null || bridges.isEmpty())
			return;

		// Global stiffness parameter A
		double A_BASE = 50.0;

		for (BlueprintManager.Edge bridge : bridges) {
			int[] start = bridge.getStart();
			int[] end = bridge.getEnd();

			// 1. Fast AABB Check
			int minBX = Math.min(start[0], end[0]);
			int maxBX = Math.max(start[0], end[0]);
			int minBZ = Math.min(start[2], end[2]);
			int maxBZ = Math.max(start[2], end[2]);

			if (maxBX < chunkMinX || minBX > chunkMaxX || maxBZ < chunkMinZ || minBZ > chunkMaxZ)
				continue;

			// 2. Precise Line Intersection & Rasterization
			double dX = end[0] - start[0];
			double dZ = end[2] - start[2];
			double dist2D = Math.sqrt(dX * dX + dZ * dZ);

			if (dist2D < 1.0)
				continue;

			// Catenary Setup
			double y1 = start[1];
			double y2 = end[1];

			// Adjust A if slope is too steep
			double A = Math.max(A_BASE, dist2D / 0.9);

			// Solve for horizontal shift 'p'
			double p = solveCatenaryShift(dist2D, y2 - y1, A);

			// Calculate constants
			double k = y1 - A * Math.cosh(-p / A);

			// Rasterize line
			int steps = (int) Math.ceil(dist2D * 1.5);

			for (int i = 0; i <= steps; i++) {
				double t = i / (double) steps;
				double worldX = start[0] + t * dX;
				double worldZ = start[2] + t * dZ;

				int bX = (int) Math.round(worldX);
				int bZ = (int) Math.round(worldZ);

				// Check if this block is in current chunk
				if (bX >= chunkMinX && bX <= chunkMaxX && bZ >= chunkMinZ && bZ <= chunkMaxZ) {
					double currentDist = t * dist2D;
					double yRaw = A * Math.cosh((currentDist - p) / A) + k;

					// Round Y to nearest 0.5
					double yRounded = Math.round(yRaw * 2) / 2.0;
					int yBlock = (int) Math.floor(yRounded);
					boolean isTop = (yRounded - yBlock) > 0.25;

					// Get Biome-Specific Block State
					RegistryEntry<Biome> biomeEntry = this.biomeSource.getBiome(bX >> 2, yBlock >> 2, bZ >> 2,
							noiseConfig.getMultiNoiseSampler());

					// Determine if Overworld
					boolean isOverworld = !this.blueprintId.getPath().contains("nether");

					// Determine Snow Condition (Deterministic)
					boolean trySnow = false;
					if (isTop && isCold(biomeEntry)) {
						long snowHash = MathHelper.hashCode(bX, yBlock, bZ);
						trySnow = (snowHash & 15) < 8; // ~50% chance
					}

					// Place center and neighbors with mosaic/hole logic
					// Center
					placeBridgePart(chunk, bX, yBlock, bZ, isTop, biomeEntry, isOverworld, trySnow);

					// Neighbors (Cross Shape)
					// We must query biome/palette for each neighbor to get correct mosaic effect
					int[] dx = { 1, -1, 0, 0 };
					int[] dz = { 0, 0, 1, -1 };

					for (int n = 0; n < 4; n++) {
						int nx = bX + dx[n];
						int nz = bZ + dz[n];
						// Use helper
						placeBridgePart(chunk, nx, yBlock, nz, isTop, biomeEntry, isOverworld, trySnow);
					}
					// Snow loop removed (handled inside placeBridgePart)
				}
			}
		}
	}

	private Block getBridgeBlockState(RegistryEntry<Biome> biomeEntry, int x, int y, int z) {
		// 1. Hole Logic (3% Global Chance)
		long hash = MathHelper.hashCode(x, y, z);
		if (Math.abs(hash % 100) < 3) {
			return null; // Hole
		}

		int val = Math.abs((int) (hash % 10));

		// 2. Palette Selection (9 Specific Biomes)
		if (biomeEntry.matchesKey(BiomeKeys.WARM_OCEAN)) {
			// Smooth Sandstone (60%), Sandstone (30%), Cut Sandstone (10%)
			if (val < 6)
				return Blocks.SMOOTH_SANDSTONE_SLAB;
			if (val < 9)
				return Blocks.SANDSTONE_SLAB;
			return Blocks.CUT_SANDSTONE_SLAB;

		} else if (biomeEntry.matchesKey(BiomeKeys.FOREST)) {
			// Oak (50%), Cobble (30%), Mossy Cobble (20%)
			if (val < 5)
				return Blocks.OAK_SLAB;
			if (val < 8)
				return Blocks.COBBLESTONE_SLAB;
			return Blocks.MOSSY_COBBLESTONE_SLAB;

		} else if (biomeEntry.matchesKey(BiomeKeys.DEEP_FROZEN_OCEAN)) {
			// Prismarine (50%), Dark Prismarine (30%), Prismarine Brick (20%)
			if (val < 5)
				return Blocks.PRISMARINE_SLAB;
			if (val < 8)
				return Blocks.DARK_PRISMARINE_SLAB;
			return Blocks.PRISMARINE_BRICK_SLAB;

		} else if (biomeEntry.matchesKey(BiomeKeys.SWAMP)) {
			// Mossy Cobble (50%), Cobble (30%), Mossy Brick (20%)
			if (val < 5)
				return Blocks.MOSSY_COBBLESTONE_SLAB;
			if (val < 8)
				return Blocks.COBBLESTONE_SLAB;
			return Blocks.MOSSY_STONE_BRICK_SLAB;

		} else if (biomeEntry.matchesKey(BiomeKeys.SNOWY_TAIGA)) {
			// Spruce (70%), Dark Oak (30%)
			if (val < 7)
				return Blocks.SPRUCE_SLAB;
			return Blocks.DARK_OAK_SLAB;

		} else if (biomeEntry.matchesKey(BiomeKeys.LUSH_CAVES)) {
			// Mossy Stone Brick (60%), Cobble (40%)
			if (val < 6)
				return Blocks.MOSSY_STONE_BRICK_SLAB;
			return Blocks.COBBLESTONE_SLAB;

		} else if (biomeEntry.matchesKey(BiomeKeys.DESERT)) {
			// Red Sandstone (60%), Sandstone (30%), Cut Red (10%)
			if (val < 6)
				return Blocks.RED_SANDSTONE_SLAB;
			if (val < 9)
				return Blocks.SANDSTONE_SLAB;
			return Blocks.CUT_RED_SANDSTONE_SLAB;

		} else if (biomeEntry.matchesKey(BiomeKeys.STONY_PEAKS)) {
			// Stone (40%), Andesite (30%), Cobble (30%)
			if (val < 4)
				return Blocks.STONE_SLAB;
			if (val < 7)
				return Blocks.ANDESITE_SLAB;
			return Blocks.COBBLESTONE_SLAB;

		} else if (biomeEntry.matchesKey(BiomeKeys.FROZEN_PEAKS)) {
			// Spruce (50%), Diorite (30%), Stone (20%)
			if (val < 5)
				return Blocks.SPRUCE_SLAB;
			if (val < 8)
				return Blocks.DIORITE_SLAB;
			if (val < 8)
				return Blocks.DIORITE_SLAB;
			return Blocks.STONE_SLAB;

		} else if (biomeEntry.matchesKey(BiomeKeys.NETHER_WASTES)) {
			// Nether Brick (60%), Red Nether Brick (40%)
			if (val < 6)
				return Blocks.NETHER_BRICK_SLAB;
			return Blocks.RED_NETHER_BRICK_SLAB;

		} else if (biomeEntry.matchesKey(BiomeKeys.SOUL_SAND_VALLEY)) {
			// Smooth Quartz (40%), Quartz (40%), Polished Blackstone (20%)
			if (val < 4)
				return Blocks.SMOOTH_QUARTZ_SLAB;
			if (val < 8)
				return Blocks.QUARTZ_SLAB;
			return Blocks.POLISHED_BLACKSTONE_SLAB;

		} else if (biomeEntry.matchesKey(BiomeKeys.CRIMSON_FOREST)) {
			// Crimson (50%), Red Nether Brick (30%), Nether Brick (20%)
			if (val < 5)
				return Blocks.CRIMSON_SLAB;
			if (val < 8)
				return Blocks.RED_NETHER_BRICK_SLAB;
			return Blocks.NETHER_BRICK_SLAB;

		} else if (biomeEntry.matchesKey(BiomeKeys.WARPED_FOREST)) {
			// Warped (50%), Blackstone (30%), Polished Blackstone Brick (20%)
			if (val < 5)
				return Blocks.WARPED_SLAB;
			if (val < 8)
				return Blocks.BLACKSTONE_SLAB;
			return Blocks.POLISHED_BLACKSTONE_BRICK_SLAB;

		} else if (biomeEntry.matchesKey(BiomeKeys.BASALT_DELTAS)) {
			// Polished Blackstone Brick (50%), Blackstone (30%), Polished Blackstone (20%)
			if (val < 5)
				return Blocks.POLISHED_BLACKSTONE_BRICK_SLAB;
			if (val < 8)
				return Blocks.BLACKSTONE_SLAB;
			return Blocks.POLISHED_BLACKSTONE_SLAB;
		}

		// Fallback Default: Stone (50%), Cobblestone (30%), Stone Bricks (20%)
		if (val < 5)
			return Blocks.STONE_SLAB;
		if (val < 8)
			return Blocks.COBBLESTONE_SLAB;
		return Blocks.STONE_BRICK_SLAB;
	}

	private boolean placeBridgePart(Chunk chunk, int x, int y, int z, boolean isTop, RegistryEntry<Biome> biomeEntry,
			boolean isOverworld, boolean addSnow) {
		long hash = MathHelper.hashCode(x, y, z);
		boolean isSuspicious = isTop && isOverworld && Math.abs(hash % 100) == 3;

		if (isSuspicious) {
			// Suspicious Block Logic - NO SNOW
			Block susBlock = Blocks.SUSPICIOUS_GRAVEL;
			RegistryKey<LootTable> lootTableId = LootTables.OCEAN_RUIN_COLD_ARCHAEOLOGY;

			if (biomeEntry.matchesKey(BiomeKeys.DESERT) || biomeEntry.matchesKey(BiomeKeys.WARM_OCEAN)) {
				susBlock = Blocks.SUSPICIOUS_SAND;
				lootTableId = LootTables.DESERT_WELL_ARCHAEOLOGY;
			}
			// Place Suspicious Block (Full Block) which aligns with Top Slab surface
			placeBridgeBlock(chunk, x, y, z, susBlock.getDefaultState(), lootTableId);

			// Place Support Slab (Top Slab at y-1) to support the Falling Block
			Block supportBlock = getBridgeBlockState(biomeEntry, x, y, z);
			if (supportBlock != null) {
				placeBridgeBlock(chunk, x, y - 1, z,
						supportBlock.getDefaultState().with(SlabBlock.TYPE, SlabType.TOP));
			}

			// Clean above (ensure no slab covers it)
			placeBridgeBlock(chunk, x, y + 1, z, Blocks.AIR.getDefaultState());

		} else {
			// Normal Logic
			Block block = getBridgeBlockState(biomeEntry, x, y, z);
			if (block != null) {
				BlockState state = block.getDefaultState().with(SlabBlock.TYPE, isTop ? SlabType.TOP : SlabType.BOTTOM);
				placeBridgeBlock(chunk, x, y, z, state);
			} else {
				// No block placed, so no snow
				return false;
			}
		}

		// Snow Logic (Applies to both)
		if (addSnow && isTop) {
			BlockPos pos = new BlockPos(x, y, z);
			BlockState current = chunk.getBlockState(pos);

			// Verify support conditions:
			// 1. Is a Full Block (like Suspicious Sand/Gravel) -> supports snow
			// 2. Is a Slab with TYPE=TOP -> supports snow
			// 3. (Implicitly) Is not Air
			boolean supportsSnow = false;
			if (current.getBlock() == Blocks.SUSPICIOUS_SAND || current.getBlock() == Blocks.SUSPICIOUS_GRAVEL) {
				supportsSnow = true;
			} else if (current.contains(SlabBlock.TYPE)) {
				if (current.get(SlabBlock.TYPE) == SlabType.TOP) {
					supportsSnow = true;
				}
			} else if (!current.isAir()) {
				// Assume other solid blocks (full blocks) support snow
				supportsSnow = true;
			}

			if (supportsSnow) {
				placeSnowBlock(chunk, x, y + 1, z, Blocks.SNOW.getDefaultState());
			}
		}
		return true;
	}

	private boolean isCold(RegistryEntry<Biome> biomeEntry) {
		return biomeEntry.value().getTemperature() < 0.15f;
	}

	private void placeBridgeBlock(Chunk chunk, int x, int y, int z, BlockState state) {
		placeBridgeBlock(chunk, x, y, z, state, null);
	}

	private void placeBridgeBlock(Chunk chunk, int x, int y, int z, BlockState state,
			RegistryKey<LootTable> lootTableId) {
		if (chunk.getPos().getStartX() <= x && chunk.getPos().getEndX() >= x && chunk.getPos().getStartZ() <= z
				&& chunk.getPos().getEndZ() >= z && y >= chunk.getBottomY()
				&& y < (chunk.getBottomY() + chunk.getHeight())) {

			BlockPos pos = new BlockPos(x, y, z);
			chunk.setBlockState(pos, state, 0);

			if (lootTableId != null) {
				BlockEntity blockEntity = chunk.getBlockEntity(pos);
				if (blockEntity == null) {
					blockEntity = new BrushableBlockEntity(pos, state);
					chunk.setBlockEntity(blockEntity);
				}
				if (blockEntity instanceof BrushableBlockEntity brushableBlockEntity) {
					brushableBlockEntity.setLootTable(lootTableId, pos.asLong());
				}
			}
		}
	}

	private void placeSnowBlock(Chunk chunk, int x, int y, int z, BlockState state) {
		if (chunk.getPos().getStartX() <= x && chunk.getPos().getEndX() >= x &&
				chunk.getPos().getStartZ() <= z && chunk.getPos().getEndZ() >= z &&
				y >= chunk.getBottomY() && y < (chunk.getBottomY() + chunk.getHeight())) {

			BlockPos pos = new BlockPos(x, y, z);
			// Only place snow if air
			if (chunk.getBlockState(pos).isAir()) {
				chunk.setBlockState(pos, state, 0);
			}
		}
	}

	private double solveCatenaryShift(double d, double dy, double A) {
		double p = d / 2.0;
		double target = dy / A;

		for (int i = 0; i < 10; i++) {
			double val = Math.cosh((d - p) / A) - Math.cosh(p / A);
			double diff = val - target;
			if (Math.abs(diff) < 1e-5)
				break;

			double slope = (-1.0 / A) * (Math.sinh((d - p) / A) + Math.sinh(p / A));
			if (Math.abs(slope) < 1e-9)
				break;

			p = p - diff / slope;
		}
		return p;
	}

	@Override
	public int getWorldHeight() {
		return 384; // Standard world height
	}

	@Override
	public int getSeaLevel() {
		return 0;
	}

	@Override
	public int getMinimumY() {
		return -64;
	}

	@Override
	public CompletableFuture<Chunk> populateNoise(Blender blender, NoiseConfig noiseConfig,
			StructureAccessor structureAccessor, Chunk chunk) {
		return CompletableFuture.completedFuture(chunk);
	}

	@Override
	public void populateEntities(@NotNull ChunkRegion chunkRegion) {
		ChunkPos chunkPos = chunkRegion.getCenterPos();
		RegistryEntry<Biome> biome = chunkRegion.getBiome(chunkPos.getStartPos().withY(chunkRegion.getTopYInclusive()));
		ChunkRandom chunkRandom = new ChunkRandom(new CheckedRandom(RandomSeed.getSeed()));
		chunkRandom.setPopulationSeed(chunkRegion.getSeed(), chunkPos.getStartX(), chunkPos.getStartZ());
		SpawnHelper.populateEntities(chunkRegion, biome, chunkPos, chunkRandom);

		// 2. Generate Spheres
		List<BlueprintManager.BlueprintNode> nodes = BlueprintManager.get().getSpheresInChunk(this.blueprintId,
				chunkPos);
		Registry<ConfiguredSphere<?, ?>> sphereRegistry = chunkRegion.getRegistryManager()
				.getOrThrow(StarryRegistryKeys.CONFIGURED_SPHERE);

		for (BlueprintManager.BlueprintNode node : nodes) {
			Identifier sphereId = Identifier.tryParse(node.type);
			if (sphereId == null || !node.type.contains(":")) {
				sphereId = Identifier.of(StarrySkies.MOD_ID, node.type);
			}

			// Fallback logic
			ConfiguredSphere<?, ?> configuredSphere = sphereRegistry.get(sphereId);
			if (configuredSphere == null && !node.type.startsWith("overworld/")) {
				Identifier fallbackId = Identifier.of(StarrySkies.MOD_ID, "overworld/" + node.type);
				ConfiguredSphere<?, ?> fallbackSphere = sphereRegistry.get(fallbackId);
				if (fallbackSphere != null) {
					configuredSphere = fallbackSphere;
				}
			}

			if (configuredSphere != null) {
				BlockPos pos = new BlockPos(node.getX(), node.getY(), node.getZ());
				ChunkRandom random = new ChunkRandom(new CheckedRandom(chunkRegion.getSeed()));
				random.setCarverSeed(chunkRegion.getSeed(), node.getX(), node.getZ());

				PlacedSphere<?> sphere = configuredSphere.generate(random, chunkRegion.getRegistryManager(), pos,
						(float) node.radius);
				sphere.setPosition(pos);

				sphere.populateEntities(chunkPos, chunkRegion, chunkRandom);
			}
		}
	}

	@Override
	public com.mojang.datafixers.util.Pair<BlockPos, net.minecraft.registry.entry.RegistryEntry<net.minecraft.world.gen.structure.Structure>> locateStructure(
			net.minecraft.server.world.ServerWorld world,
			net.minecraft.registry.entry.RegistryEntryList<net.minecraft.world.gen.structure.Structure> structures,
			BlockPos center, int radius, boolean skipExistingChunks) {
		// If searching for meaningful structure (Stronghold) which is usually in the
		// "Eye of Ender Located" tag
		// Depending on MC version, we check if the set contains the Stronghold key
		net.minecraft.registry.RegistryKey<net.minecraft.world.gen.structure.Structure> strongholdKey = net.minecraft.registry.RegistryKey
				.of(net.minecraft.registry.RegistryKeys.STRUCTURE,
						net.minecraft.util.Identifier.of("minecraft", "stronghold"));

		// We need to resolve the entry from the registry for the check
		// Using getOptional to avoid crash if not present, and handling RegistryKey
		// lookup
		java.util.Optional<net.minecraft.registry.entry.RegistryEntry.Reference<net.minecraft.world.gen.structure.Structure>> strongholdEntry = world
				.getRegistryManager().getOrThrow(net.minecraft.registry.RegistryKeys.STRUCTURE)
				.getOptional(strongholdKey);

		if (strongholdEntry.isPresent() && structures.contains(strongholdEntry.get())) {
			// Find nearest stronghold sphere
			BlueprintManager.BlueprintNode node = BlueprintManager.get().getNearestNode(this.blueprintId, center.getX(),
					center.getZ(),
					type -> type != null && type.contains("stronghold"));
			if (node != null) {
				return new com.mojang.datafixers.util.Pair<>(new BlockPos(node.getX(), node.getY(), node.getZ()),
						strongholdEntry.get());
			}
		}
		return super.locateStructure(world, structures, center, radius, skipExistingChunks);
	}

	@Override
	public void generateFeatures(StructureWorldAccess world, Chunk chunk, StructureAccessor structureAccessor) {
		// super.generateFeatures(world, chunk, structureAccessor); // Disabled to
		// prevent vanilla structures

		ChunkPos chunkPos = chunk.getPos();
		long seed = world.getSeed();

		List<BlueprintManager.BlueprintNode> nodes = BlueprintManager.get().getSpheresInChunk(this.blueprintId,
				chunkPos);
		Registry<ConfiguredSphere<?, ?>> sphereRegistry = world.getRegistryManager()
				.getOrThrow(StarryRegistryKeys.CONFIGURED_SPHERE);

		for (BlueprintManager.BlueprintNode node : nodes) {
			Identifier sphereId = Identifier.tryParse(node.type);
			if (sphereId == null || !node.type.contains(":")) {
				sphereId = Identifier.of(StarrySkies.MOD_ID, node.type);
			}

			// Fallback logic
			ConfiguredSphere<?, ?> configuredSphere = sphereRegistry.get(sphereId);
			if (configuredSphere == null) {
				String fallbackPrefix = this.blueprintId.getPath().contains("nether") ? "nether/" : "overworld/";
				if (!node.type.startsWith(fallbackPrefix)) {
					Identifier fallbackId = Identifier.of(StarrySkies.MOD_ID, fallbackPrefix + node.type);
					ConfiguredSphere<?, ?> fallbackSphere = sphereRegistry.get(fallbackId);
					if (fallbackSphere != null) {
						configuredSphere = fallbackSphere;
					}
				}
			}

			if (configuredSphere != null) {
				BlockPos pos = new BlockPos(node.getX(), node.getY(), node.getZ());
				ChunkRandom random = new ChunkRandom(new CheckedRandom(seed));
				random.setCarverSeed(seed, node.getX(), node.getZ());

				PlacedSphere<?> sphere = configuredSphere.generate(random, world.getRegistryManager(), pos,
						(float) node.radius);
				sphere.setPosition(pos);

				sphere.decorate(world, chunkPos.getStartPos(), random);
			}
		}
	}

	@Override
	public int getHeight(int x, int z, Heightmap.Type heightmap, HeightLimitView world, NoiseConfig noiseConfig) {
		return 0;
	}

	@Override
	public void appendDebugHudText(List<String> text, NoiseConfig noiseConfig, BlockPos pos) {
	}

	@Override
	public VerticalBlockSample getColumnSample(int x, int z, HeightLimitView world, NoiseConfig noiseConfig) {
		BlockState[] states = new BlockState[world.getHeight()];
		Arrays.fill(states, Blocks.AIR.getDefaultState());
		return new VerticalBlockSample(world.getBottomY(), states);
	}
}