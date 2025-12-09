package de.dafuqs.starryskies.worldgen.dimension;

import com.mojang.serialization.*;
import com.mojang.serialization.codecs.*;
import de.dafuqs.starryskies.StarrySkies;
import de.dafuqs.starryskies.registries.*;
import de.dafuqs.starryskies.worldgen.*;
import net.minecraft.block.*;
import net.minecraft.block.enums.SlabType;
import net.minecraft.registry.Registry;
import net.minecraft.registry.entry.*;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.*;
import net.minecraft.util.math.random.CheckedRandom;
import net.minecraft.util.math.random.ChunkRandom;
import net.minecraft.util.math.random.RandomSeed;
import net.minecraft.world.*;
import net.minecraft.world.biome.*;
import net.minecraft.world.biome.source.*;
import net.minecraft.world.chunk.*;
import net.minecraft.world.gen.*;
import net.minecraft.world.gen.chunk.*;
import net.minecraft.world.gen.noise.*;
import org.jetbrains.annotations.*;

import java.util.*;
import java.util.concurrent.*;

public class StarrySkyChunkGenerator extends ChunkGenerator {

	public static final MapCodec<StarrySkyChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(
			(instance) -> instance.group(
					BiomeSource.CODEC.fieldOf("biome_source").forGetter((generator) -> generator.biomeSource))
					.apply(instance, StarrySkyChunkGenerator::new));

	public StarrySkyChunkGenerator(BiomeSource biomeSource) {
		super(biomeSource);
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
		List<BlueprintManager.BlueprintNode> nodes = BlueprintManager.get().getSpheresInChunk(chunk.getPos());

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

			// Fallback: Try prefixing with "overworld/" if not found
			if (configuredSphere == null && !node.type.startsWith("overworld/")) {
				Identifier fallbackId = Identifier.of(StarrySkies.MOD_ID, "overworld/" + node.type);
				configuredSphere = sphereRegistry.get(fallbackId);
				if (configuredSphere != null) {
					sphereId = fallbackId;
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

		List<BlueprintManager.Edge> bridges = BlueprintManager.get().getBridgesInRegion(chunkPos.getCenterX(),
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

					// Place center and neighbors with mosaic/hole logic
					// Center
					Block centerBlock = getBridgeBlockState(biomeEntry, bX, yBlock, bZ);
					if (centerBlock != null) {
						BlockState state = centerBlock.getDefaultState().with(SlabBlock.TYPE,
								isTop ? SlabType.TOP : SlabType.BOTTOM);
						placeBridgeBlock(chunk, bX, yBlock, bZ, state);
					}

					// Neighbors (Cross Shape)
					// We must query biome/palette for each neighbor to get correct mosaic effect
					int[] dx = { 1, -1, 0, 0 };
					int[] dz = { 0, 0, 1, -1 };

					for (int n = 0; n < 4; n++) {
						int nx = bX + dx[n];
						int nz = bZ + dz[n];
						// Re-query biome for strict accuracy, or just reuse center biome
						// (faster/cleaner).
						// Let's reuse center biome for consistency of theme, but vary coordinates for
						// noise.
						Block nBlock = getBridgeBlockState(biomeEntry, nx, yBlock, nz);
						if (nBlock != null) {
							BlockState nState = nBlock.getDefaultState().with(SlabBlock.TYPE,
									isTop ? SlabType.TOP : SlabType.BOTTOM);
							placeBridgeBlock(chunk, nx, yBlock, nz, nState);
						}
					}

					// Add Snow if Cold (Randomly)
					if (isTop && isCold(biomeEntry)) {
						// Deterministic random check based on position to avoid chunk border artifacts
						// Simple hash: (x ^ z ^ y) mix
						long hash = MathHelper.hashCode(bX, yBlock, bZ);
						if ((hash & 15) < 8) { // ~50% chance
							BlockState snow = Blocks.SNOW.getDefaultState();
							// Place snow above bridge components ONLY if they exist
							if (centerBlock != null)
								placeSnowBlock(chunk, bX, yBlock + 1, bZ, snow);

							for (int m = 0; m < 4; m++) {
								int nx = bX + dx[m];
								int nz = bZ + dz[m];
								// We need to check if the neighbor exists. Technically we should re-calculate
								// the hash or check the chunk.
								// But checking the chunk read/write might be slow or complex if not placed yet.
								// Better: Re-run the deterministic check (fast).
								Block nBlock = getBridgeBlockState(biomeEntry, nx, yBlock, nz);
								if (nBlock != null) {
									placeSnowBlock(chunk, nx, yBlock + 1, nz, snow);
								}
							}
						}
					}
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
			return Blocks.STONE_SLAB;
		}

		// Fallback Default: Stone (50%), Cobblestone (30%), Stone Bricks (20%)
		if (val < 5)
			return Blocks.STONE_SLAB;
		if (val < 8)
			return Blocks.COBBLESTONE_SLAB;
		return Blocks.STONE_BRICK_SLAB;
	}

	private boolean isCold(RegistryEntry<Biome> biomeEntry) {
		return biomeEntry.value().getTemperature() < 0.15f;
	}

	private void placeBridgeBlock(Chunk chunk, int x, int y, int z, BlockState state) {
		if (chunk.getPos().getStartX() <= x && chunk.getPos().getEndX() >= x &&
				chunk.getPos().getStartZ() <= z && chunk.getPos().getEndZ() >= z &&
				y >= chunk.getBottomY() && y < (chunk.getBottomY() + chunk.getHeight())) {

			BlockPos pos = new BlockPos(x, y, z);
			chunk.setBlockState(pos, state, 0);
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

		List<BlueprintManager.BlueprintNode> nodes = BlueprintManager.get().getSpheresInChunk(chunkPos);
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
	public void generateFeatures(StructureWorldAccess world, Chunk chunk, StructureAccessor structureAccessor) {
		super.generateFeatures(world, chunk, structureAccessor);

		ChunkPos chunkPos = chunk.getPos();
		long seed = world.getSeed();

		List<BlueprintManager.BlueprintNode> nodes = BlueprintManager.get().getSpheresInChunk(chunkPos);
		Registry<ConfiguredSphere<?, ?>> sphereRegistry = world.getRegistryManager()
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