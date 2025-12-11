package de.dafuqs.starryskies.worldgen.dimension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.google.common.collect.Sets;
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
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BrushableBlockEntity;
import net.minecraft.loot.LootTable;
import net.minecraft.loot.LootTables;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.random.CheckedRandom;
import net.minecraft.util.math.random.ChunkRandom;
import net.minecraft.util.math.random.RandomSeed;
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

		// 1. Generate Bridges (Structure Phase)
		carveBridges(chunk, chunkRegion, noiseConfig);

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

	private void carveBridges(Chunk chunk, ChunkRegion chunkRegion, NoiseConfig noiseConfig) {
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

					// Standard structural generation
					// Center
					placeBridgeBlock(chunk, bX, yBlock, bZ, isTop, biomeEntry);

					// Neighbors (Cross Shape)
					int[] dx = { 1, -1, 0, 0 };
					int[] dz = { 0, 0, 1, -1 };

					for (int n = 0; n < 4; n++) {
						int nx = bX + dx[n];
						int nz = bZ + dz[n];
						placeBridgeBlock(chunk, nx, yBlock, nz, isTop, biomeEntry);
					}
				}
			}
		}
	}

	private void decorateBridges(StructureWorldAccess world, Chunk chunk) {
		ChunkPos chunkPos = chunk.getPos();
		int chunkMinX = chunkPos.getStartX();
		int chunkMaxX = chunkPos.getEndX();
		int chunkMinZ = chunkPos.getStartZ();
		int chunkMaxZ = chunkPos.getEndZ();
		int bottomY = chunk.getBottomY();
		int topY = chunk.getTopYInclusive();

		List<BlueprintManager.Edge> bridges = BlueprintManager.get().getBridgesInRegion(this.blueprintId,
				chunkPos.getCenterX(),
				chunkPos.getCenterZ());

		if (bridges == null || bridges.isEmpty())
			return;

		double A_BASE = 50.0;

		for (BlueprintManager.Edge bridge : bridges) {

			int[] start = bridge.getStart();
			int[] end = bridge.getEnd();

			// AABB Check
			int minBX = Math.min(start[0], end[0]);
			int maxBX = Math.max(start[0], end[0]);
			int minBZ = Math.min(start[2], end[2]);
			int maxBZ = Math.max(start[2], end[2]);

			if (maxBX < chunkMinX || minBX > chunkMaxX || maxBZ < chunkMinZ || minBZ > chunkMaxZ)
				continue;

			double dX = end[0] - start[0];
			double dZ = end[2] - start[2];
			double dist2D = Math.sqrt(dX * dX + dZ * dZ);
			if (dist2D < 1.0)
				continue;

			double y1 = start[1];
			double y2 = end[1];
			double A = Math.max(A_BASE, dist2D / 0.9);
			double p = solveCatenaryShift(dist2D, y2 - y1, A);
			double k = y1 - A * Math.cosh(-p / A);
			int steps = (int) Math.ceil(dist2D * 1.5);

			// Lore Logic: Find Target Step
			int targetStep = -1;
			RegistryKey<LootTable> storyLoot = null;
			int finalLoreStep = -1;
			BlockPos finalLorePos = null;

			if (bridge.story != null) {
				List<Integer> candidates = new ArrayList<>();
				for (int i = 0; i <= steps; i++) {
					double t = i / (double) steps;
					double currentDist = t * dist2D;
					double yRaw = A * Math.cosh((currentDist - p) / A) + k;
					double yRounded = Math.round(yRaw * 2) / 2.0;
					int yBlock = (int) Math.floor(yRounded);
					boolean isTop = (yRounded - yBlock) > 0.25;
					if (isTop)
						candidates.add(i);
				}

				if (!candidates.isEmpty()) {
					long hashA = MathHelper.hashCode(start[0], start[1], start[2]);
					long hashB = MathHelper.hashCode(end[0], end[1], end[2]);
					long bridgeHash = hashA ^ hashB;
					int index = Math.abs((int) (bridgeHash % candidates.size()));
					targetStep = candidates.get(index);

					Identifier id = Identifier.of("starryskies",
							"chests/lore/chapter_" + bridge.story[0] + "_part_" + bridge.story[1]);
					storyLoot = RegistryKey.of(RegistryKeys.LOOT_TABLE, id);
				}

				// Smart Fallback for Lore Spot
				if (targetStep != -1) {
					finalLoreStep = targetStep; // default

					// Check validity of original target
					double t = targetStep / (double) steps;
					BlockPos targetPos = new BlockPos(
							(int) Math.round(start[0] + t * dX),
							(int) Math.floor(Math.round((A * Math.cosh((t * dist2D - p) / A) + k) * 2) / 2.0),
							(int) Math.round(start[2] + t * dZ));

					finalLorePos = targetPos; // Initial guess

					if (targetPos.getX() >= chunkMinX && targetPos.getX() <= chunkMaxX && targetPos.getZ() >= chunkMinZ
							&& targetPos.getZ() <= chunkMaxZ) {
						if (!chunk.getBlockState(targetPos).contains(SlabBlock.TYPE)) {
							// Target is bad. Scan.
							boolean found = false;
							for (int offset = 1; offset < 20; offset++) {
								for (int sign = -1; sign <= 1; sign += 2) {
									int candidate = targetStep + (offset * sign);
									if (candidate < 0 || candidate > steps)
										continue;
									// Check candidate
									double tC = candidate / (double) steps;
									BlockPos posC = new BlockPos(
											(int) Math.round(start[0] + tC * dX),
											(int) Math.floor(
													Math.round((A * Math.cosh((tC * dist2D - p) / A) + k) * 2) / 2.0),
											(int) Math.round(start[2] + tC * dZ));

									if (posC.getX() >= chunkMinX && posC.getX() <= chunkMaxX && posC.getZ() >= chunkMinZ
											&& posC.getZ() <= chunkMaxZ) {
										if (chunk.getBlockState(posC).contains(SlabBlock.TYPE)) {
											finalLoreStep = candidate;
											finalLorePos = posC;
											found = true;
											break;
										}
									}
								}
								if (found)
									break;
							}
						}
					}
				}
			}

			for (int i = 0; i <= steps; i++) {
				double t = i / (double) steps;
				double worldX = start[0] + t * dX;
				double worldZ = start[2] + t * dZ;

				int bX = (int) Math.round(worldX);
				int bZ = (int) Math.round(worldZ);

				// STRICT Check: block is in current chunk
				if (bX >= chunkMinX && bX <= chunkMaxX && bZ >= chunkMinZ && bZ <= chunkMaxZ) {
					double currentDist = t * dist2D;
					double yRaw = A * Math.cosh((currentDist - p) / A) + k;
					double yRounded = Math.round(yRaw * 2) / 2.0;
					int yBlock = (int) Math.floor(yRounded);
					boolean isTop = (yRounded - yBlock) > 0.25;

					if (yBlock < bottomY || yBlock >= topY)
						continue;

					BlockPos pos = new BlockPos(bX, yBlock, bZ);
					BlockState currentState = chunk.getBlockState(pos);

					// DECORATION PHASE: Only modify existing slabs
					// Note: Suspicious Sand/Gravel does NOT have SlabBlock.TYPE, so they are safe
					// from modification
					if (currentState.contains(SlabBlock.TYPE)) {

						long hash = MathHelper.hashCode(bX, yBlock, bZ);

						// 1. Lore (Suspicious Blocks)
						if (i == finalLoreStep && storyLoot != null) {
							// Apply Lore if selected
							RegistryEntry<Biome> biomeEntry = world.getBiome(pos);
							boolean isDesert = biomeEntry.matchesKey(BiomeKeys.DESERT)
									|| biomeEntry.matchesKey(BiomeKeys.WARM_OCEAN);
							Block susBlock = isDesert ? Blocks.SUSPICIOUS_SAND : Blocks.SUSPICIOUS_GRAVEL;

							BlockState susState = susBlock.getDefaultState();

							// PLACE SUPPORT for gravity block
							// User requested "top-state of bridge material slab"
							placeSupport(chunk, pos, biomeEntry, bottomY, topY);

							// Place Sus Block
							chunk.setBlockState(pos, susState, 0);

							// Set Block Entity
							BlockEntity existing = chunk.getBlockEntity(pos);
							if (existing != null)
								chunk.removeBlockEntity(pos);

							BrushableBlockEntity be = new BrushableBlockEntity(pos, susState);
							be.setLootTable(storyLoot, pos.asLong());

							if (be.getType().supports(susState)) {
								chunk.setBlockEntity(be);
							}

							// Clean above
							if (yBlock + 1 < topY) {
								chunk.setBlockState(pos.up(), Blocks.AIR.getDefaultState(), 0);
							}
							continue; // Skip hole/snow
						}

						// 2. Hole Logic (Increased to 6%)
						// PROTECT STORY LORE: Do not make a hole if this is the target story pos
						boolean isStoryPos = (finalLorePos != null && pos.equals(finalLorePos));
						if (!isStoryPos && Math.abs(hash % 100) < 6) {
							chunk.setBlockState(pos, Blocks.AIR.getDefaultState(), 0);
							continue;
						}

						// 3. Snow Logic (Increased to ~87%)
						if (isTop) {
							RegistryEntry<Biome> biomeEntry = world.getBiome(pos);
							if (isCold(biomeEntry)) {
								if ((hash & 15) < 14) {
									if (yBlock + 1 < topY) {
										if (chunk.getBlockState(pos.up()).isAir()) {
											chunk.setBlockState(pos.up(), Blocks.SNOW.getDefaultState(), 0);
										}
									}
								}
							}
						}
					}
				}
			}
		}
	}

	private void placeSupport(Chunk chunk, BlockPos pos, RegistryEntry<Biome> biome, int bottomY, int topY) {
		BlockPos supportPos = pos.down();
		if (supportPos.getY() < bottomY || supportPos.getY() >= topY)
			return;

		// Use the bridge material's slab state, but force it to TOP
		Block bridgeMaterial = getBridgeBlockState(biome, pos.getX(), pos.getY(), pos.getZ());
		if (bridgeMaterial != null) {
			BlockState supportState = bridgeMaterial.getDefaultState().with(SlabBlock.TYPE, SlabType.TOP);
			chunk.setBlockState(supportPos, supportState, 0);
		}
	}

	private Block getBridgeBlockState(RegistryEntry<Biome> biomeEntry, int x, int y, int z) {
		// Hole Logic REMOVED - now handled in decorateBridges

		long hash = MathHelper.hashCode(x, y, z);
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

	private void placeBridgeBlock(Chunk chunk, int x, int y, int z, boolean isTop, RegistryEntry<Biome> biomeEntry) {
		Block block = getBridgeBlockState(biomeEntry, x, y, z);
		if (block != null) {
			BlockState state = block.getDefaultState().with(SlabBlock.TYPE, isTop ? SlabType.TOP : SlabType.BOTTOM);

			// Check bounds
			if (chunk.getPos().getStartX() <= x && chunk.getPos().getEndX() >= x &&
					chunk.getPos().getStartZ() <= z && chunk.getPos().getEndZ() >= z &&
					y >= chunk.getBottomY() && y < (chunk.getBottomY() + chunk.getHeight())) {

				BlockPos pos = new BlockPos(x, y, z);
				chunk.setBlockState(pos, state, 0);
			}
		}
	}

	private boolean isCold(RegistryEntry<Biome> biomeEntry) {
		return biomeEntry.value().getTemperature() < 0.15f;
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

		// 3. Decorate Bridges (Holes, Lore, Snow)
		// This runs after all structures (spheres) are placed, ensuring we only modify
		// valid bridge slabs.
		decorateBridges(world, chunk);
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