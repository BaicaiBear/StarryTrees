package de.dafuqs.starryskies.worldgen.dimension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.jspecify.annotations.NonNull;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import de.dafuqs.starryskies.StarrySkies;
import de.dafuqs.starryskies.registries.StarryRegistryKeys;
import de.dafuqs.starryskies.worldgen.BlueprintManager;
import de.dafuqs.starryskies.worldgen.ConfiguredSphere;
import de.dafuqs.starryskies.worldgen.PlacedSphere;
import de.dafuqs.starryskies.worldgen.generator.BlueprintBiomeCatalog;
import de.dafuqs.starryskies.worldgen.generator.BridgeAnchorPolicy;

public class BlueprintChunkGenerator extends ChunkGenerator {
	private static final Map<String, Block[]> BRIDGE_PALETTES = new ConcurrentHashMap<>();

	public static final MapCodec<BlueprintChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(
			(instance) -> instance.group(
					BiomeSource.CODEC.fieldOf("biome_source").forGetter((generator) -> generator.biomeSource),
					Identifier.CODEC.fieldOf("blueprint_id").forGetter((generator) -> generator.blueprintId))
					.apply(instance, BlueprintChunkGenerator::new));

	private final Identifier blueprintId;

	public BlueprintChunkGenerator(BiomeSource biomeSource, Identifier blueprintId) {
		super(biomeSource);
		this.blueprintId = blueprintId;
	}

	public Identifier getBlueprintId() {
		return this.blueprintId;
	}

	@Override
	protected MapCodec<? extends ChunkGenerator> codec() {
		return CODEC;
	}

	@Override
	public void buildSurface(WorldGenRegion region, StructureManager structures, RandomState noiseConfig, ChunkAccess chunk) {
		// No floor generation in blueprint mode
	}

	@Override
	public void applyCarvers(WorldGenRegion chunkRegion, long seed, RandomState noiseConfig, BiomeManager biomeAccess,
			StructureManager structureAccessor, ChunkAccess chunk) {

		// 1. Generate Bridges (Structure Phase)
		carveBridges(chunk, chunkRegion, noiseConfig);

		// 2. Generate Spheres
		List<BlueprintManager.BlueprintNode> nodes = BlueprintManager.get().getSpheresInChunk(this.blueprintId,
				chunk.getPos());

		Registry<ConfiguredSphere<?, ?>> sphereRegistry = chunkRegion.registryAccess()
				.lookupOrThrow(StarryRegistryKeys.CONFIGURED_SPHERE);

		for (BlueprintManager.BlueprintNode node : nodes) {
			Identifier sphereId = Identifier.tryParse(node.type);
			if (sphereId == null || sphereId.getNamespace().equals("minecraft")) {
				if (!node.type.contains(":")) {
					sphereId = Identifier.fromNamespaceAndPath(StarrySkies.MOD_ID, node.type);
				}
			}

			ConfiguredSphere<?, ?> configuredSphere = sphereRegistry.getValue(sphereId);

			// Fallback: Try prefixing based on dimension/blueprint
			if (configuredSphere == null) {
				String fallbackPrefix = this.blueprintId.getPath().contains("nether") ? "nether/" : "overworld/";
				if (!node.type.startsWith(fallbackPrefix)) {
					Identifier fallbackId = Identifier.fromNamespaceAndPath(StarrySkies.MOD_ID, fallbackPrefix + node.type);
					configuredSphere = sphereRegistry.getValue(fallbackId);
					if (configuredSphere != null) {
						sphereId = fallbackId;
					}
				}
			}

			if (configuredSphere != null) {
				BlockPos pos = new BlockPos(node.getX(), node.getY(), node.getZ());
				WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(seed));
				random.setLargeFeatureSeed(seed, node.getX(), node.getZ());

				PlacedSphere<?> sphere = configuredSphere.generate(random, chunkRegion, pos,
						(float) node.radius);
				sphere.setPosition(pos);

				boolean intersects = sphere.isInChunk(chunk.getPos());

				if (intersects) {
					sphere.generate(chunk, chunkRegion);
				}
			}
		}
	}

	private void carveBridges(ChunkAccess chunk, WorldGenRegion chunkRegion, RandomState noiseConfig) {
		boolean modern = BlueprintManager.get().getFormatVersion(this.blueprintId) >= 2;
		ChunkPos chunkPos = chunk.getPos();
		int chunkMinX = chunkPos.getMinBlockX();
		int chunkMaxX = chunkPos.getMaxBlockX();
		int chunkMinZ = chunkPos.getMinBlockZ();
		int chunkMaxZ = chunkPos.getMaxBlockZ();

		List<BlueprintManager.Edge> bridges = BlueprintManager.get().getBridgesInRegion(this.blueprintId,
				chunkPos.getMiddleBlockX(),
				chunkPos.getMiddleBlockZ());

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
			int steps = bridgeSteps(modern, dist2D, y2 - y1);
			int columnX = Integer.MIN_VALUE, columnZ = Integer.MIN_VALUE;
			int columnMinY = Integer.MAX_VALUE, columnMaxY = Integer.MIN_VALUE;

			for (int i = 0; i <= steps; i++) {
				double t = i / (double) steps;
				double worldX = start[0] + t * dX;
				double worldZ = start[2] + t * dZ;

				int bX = (int) Math.round(worldX);
				int bZ = (int) Math.round(worldZ);
				double currentDist = t * dist2D;
				double yRaw = modern ? modernBridgeY(y1, y2, t, dist2D)
						: A * Math.cosh((currentDist - p) / A) + k;
				double yRounded = Math.round(yRaw * 2) / 2.0;
				int yBlock = (int) Math.floor(yRounded);
				boolean isTop = (yRounded - yBlock) > 0.25;
				if (modern && (bX != columnX || bZ != columnZ)) {
					if (columnMaxY - columnMinY > 1 && columnX >= chunkMinX && columnX <= chunkMaxX
							&& columnZ >= chunkMinZ && columnZ <= chunkMaxZ) {
						placeClimbLadder(chunk, columnX, columnZ, columnMinY, columnMaxY, dX, dZ);
					}
					columnX = bX;
					columnZ = bZ;
					columnMinY = yBlock;
					columnMaxY = yBlock;
				} else if (modern) {
					columnMinY = Math.min(columnMinY, yBlock);
					columnMaxY = Math.max(columnMaxY, yBlock);
				}

				// Check if this block is in current chunk
				if (bX >= chunkMinX && bX <= chunkMaxX && bZ >= chunkMinZ && bZ <= chunkMaxZ) {

					// Get Biome-Specific Block State
					Holder<Biome> biomeEntry = this.biomeSource.getNoiseBiome(bX >> 2, yBlock >> 2, bZ >> 2,
							noiseConfig.sampler());

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
			if (modern && columnMaxY - columnMinY > 1 && columnX >= chunkMinX && columnX <= chunkMaxX
					&& columnZ >= chunkMinZ && columnZ <= chunkMaxZ) {
				placeClimbLadder(chunk, columnX, columnZ, columnMinY, columnMaxY, dX, dZ);
			}
		}
	}

	private void decorateBridges(WorldGenLevel world, ChunkAccess chunk) {
		boolean modern = BlueprintManager.get().getFormatVersion(this.blueprintId) >= 2;
		ChunkPos chunkPos = chunk.getPos();
		int chunkMinX = chunkPos.getMinBlockX();
		int chunkMaxX = chunkPos.getMaxBlockX();
		int chunkMinZ = chunkPos.getMinBlockZ();
		int chunkMaxZ = chunkPos.getMaxBlockZ();
		int bottomY = chunk.getMinY();
		int topY = chunk.getMaxY();

		List<BlueprintManager.Edge> bridges = BlueprintManager.get().getBridgesInRegion(this.blueprintId,
				chunkPos.getMiddleBlockX(),
				chunkPos.getMiddleBlockZ());

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
			int steps = bridgeSteps(modern, dist2D, y2 - y1);

			for (int i = 0; i <= steps; i++) {
				double t = i / (double) steps;
				double worldX = start[0] + t * dX;
				double worldZ = start[2] + t * dZ;

				int bX = (int) Math.round(worldX);
				int bZ = (int) Math.round(worldZ);

				// STRICT Check: block is in current chunk
				if (bX >= chunkMinX && bX <= chunkMaxX && bZ >= chunkMinZ && bZ <= chunkMaxZ) {
					double currentDist = t * dist2D;
					double yRaw = modern ? modernBridgeY(y1, y2, t, dist2D)
							: A * Math.cosh((currentDist - p) / A) + k;
					double yRounded = Math.round(yRaw * 2) / 2.0;
					int yBlock = (int) Math.floor(yRounded);
					boolean isTop = (yRounded - yBlock) > 0.25;

					if (yBlock < bottomY || yBlock >= topY)
						continue;

					BlockPos pos = new BlockPos(bX, yBlock, bZ);
					BlockState currentState = world.getBlockState(pos);

					// DECORATION PHASE: Only modify existing slabs
					if (currentState.hasProperty(SlabBlock.TYPE)) {

						long hash = Mth.getSeed(bX, yBlock, bZ);

						if (!modern && Math.abs(hash % 100) < 6) {
							world.setBlock(pos, Blocks.AIR.defaultBlockState(), 0);
							continue;
						}

						// Snow Logic (Increased to ~87%)
						if (isTop) {
							Holder<Biome> biomeEntry = world.getBiome(pos);
							if (isCold(biomeEntry)) {
								if ((hash & 15) < 14) {
									if (yBlock + 1 < topY) {
										if (world.getBlockState(pos.above()).isAir()) {
											world.setBlock(pos.above(), Blocks.SNOW.defaultBlockState(), 0);
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

	private void placeSupport(WorldGenLevel world, BlockPos pos, Holder<Biome> biome, int bottomY,
			int topY) {
		BlockPos supportPos = pos.below();
		if (supportPos.getY() < bottomY || supportPos.getY() >= topY)
			return;

		// Use the bridge material's slab state, but force it to TOP
		Block bridgeMaterial = getBridgeBlockState(biome, pos.getX(), pos.getY(), pos.getZ());
		if (bridgeMaterial != null) {
			BlockState supportState = bridgeMaterial.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP);
			world.setBlock(supportPos, supportState, 0);
		}
	}

	private Block getBridgeBlockState(Holder<Biome> biomeEntry, int x, int y, int z) {
		Identifier biomeId = biomeEntry.unwrapKey().map(ResourceKey::identifier).orElse(null);
		if (biomeId != null && BlueprintManager.get().getFormatVersion(this.blueprintId) >= 2) {
			Block[] palette = BRIDGE_PALETTES.computeIfAbsent(biomeId.toString(),
					BlueprintChunkGenerator::buildBridgePalette);
			if (palette.length == 100) return palette[Math.floorMod(Mth.getSeed(x, y, z), 100)];
		}
		// Hole Logic REMOVED - now handled in decorateBridges

		long hash = Mth.getSeed(x, y, z);
		int val = Math.abs((int) (hash % 10));

		// 2. Palette Selection (9 Specific Biomes)
		if (biomeEntry.is(Biomes.WARM_OCEAN)) {
			// Smooth Sandstone (60%), Sandstone (30%), Cut Sandstone (10%)
			if (val < 6)
				return Blocks.SMOOTH_SANDSTONE_SLAB;
			if (val < 9)
				return Blocks.SANDSTONE_SLAB;
			return Blocks.CUT_SANDSTONE_SLAB;

		} else if (biomeEntry.is(Biomes.FOREST)) {
			// Oak (50%), Cobble (30%), Mossy Cobble (20%)
			if (val < 5)
				return Blocks.OAK_SLAB;
			if (val < 8)
				return Blocks.COBBLESTONE_SLAB;
			return Blocks.MOSSY_COBBLESTONE_SLAB;

		} else if (biomeEntry.is(Biomes.DEEP_FROZEN_OCEAN)) {
			// Prismarine (50%), Dark Prismarine (30%), Prismarine Brick (20%)
			if (val < 5)
				return Blocks.PRISMARINE_SLAB;
			if (val < 8)
				return Blocks.DARK_PRISMARINE_SLAB;
			return Blocks.PRISMARINE_BRICK_SLAB;

		} else if (biomeEntry.is(Biomes.SWAMP)) {
			// Mossy Cobble (50%), Cobble (30%), Mossy Brick (20%)
			if (val < 5)
				return Blocks.MOSSY_COBBLESTONE_SLAB;
			if (val < 8)
				return Blocks.COBBLESTONE_SLAB;
			return Blocks.MOSSY_STONE_BRICK_SLAB;

		} else if (biomeEntry.is(Biomes.SNOWY_TAIGA)) {
			// Spruce (70%), Dark Oak (30%)
			if (val < 7)
				return Blocks.SPRUCE_SLAB;
			return Blocks.DARK_OAK_SLAB;

		} else if (biomeEntry.is(Biomes.LUSH_CAVES)) {
			// Mossy Stone Brick (60%), Cobble (40%)
			if (val < 6)
				return Blocks.MOSSY_STONE_BRICK_SLAB;
			return Blocks.COBBLESTONE_SLAB;

		} else if (biomeEntry.is(Biomes.DESERT)) {
			// Red Sandstone (60%), Sandstone (30%), Cut Red (10%)
			if (val < 6)
				return Blocks.RED_SANDSTONE_SLAB;
			if (val < 9)
				return Blocks.SANDSTONE_SLAB;
			return Blocks.CUT_RED_SANDSTONE_SLAB;

		} else if (biomeEntry.is(Biomes.STONY_PEAKS)) {
			// Stone (40%), Andesite (30%), Cobble (30%)
			if (val < 4)
				return Blocks.STONE_SLAB;
			if (val < 7)
				return Blocks.ANDESITE_SLAB;
			return Blocks.COBBLESTONE_SLAB;

		} else if (biomeEntry.is(Biomes.FROZEN_PEAKS)) {
			// Spruce (50%), Diorite (30%), Stone (20%)
			if (val < 5)
				return Blocks.SPRUCE_SLAB;
			if (val < 8)
				return Blocks.DIORITE_SLAB;
			if (val < 8)
				return Blocks.DIORITE_SLAB;
			return Blocks.STONE_SLAB;

		} else if (biomeEntry.is(Biomes.NETHER_WASTES)) {
			// Nether Brick (60%), Red Nether Brick (40%)
			if (val < 6)
				return Blocks.NETHER_BRICK_SLAB;
			return Blocks.RED_NETHER_BRICK_SLAB;

		} else if (biomeEntry.is(Biomes.SOUL_SAND_VALLEY)) {
			// Smooth Quartz (40%), Quartz (40%), Polished Blackstone (20%)
			if (val < 4)
				return Blocks.SMOOTH_QUARTZ_SLAB;
			if (val < 8)
				return Blocks.QUARTZ_SLAB;
			return Blocks.POLISHED_BLACKSTONE_SLAB;

		} else if (biomeEntry.is(Biomes.CRIMSON_FOREST)) {
			// Crimson (50%), Red Nether Brick (30%), Nether Brick (20%)
			if (val < 5)
				return Blocks.CRIMSON_SLAB;
			if (val < 8)
				return Blocks.RED_NETHER_BRICK_SLAB;
			return Blocks.NETHER_BRICK_SLAB;

		} else if (biomeEntry.is(Biomes.WARPED_FOREST)) {
			// Warped (50%), Blackstone (30%), Polished Blackstone Brick (20%)
			if (val < 5)
				return Blocks.WARPED_SLAB;
			if (val < 8)
				return Blocks.BLACKSTONE_SLAB;
			return Blocks.POLISHED_BLACKSTONE_BRICK_SLAB;

		} else if (biomeEntry.is(Biomes.BASALT_DELTAS)) {
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

	private static Block[] buildBridgePalette(String biome) {
		var entry = BlueprintBiomeCatalog.INSTANCE.get(biome);
		if (entry == null || entry.bridge == null) return new Block[0];
		Block[] result = new Block[100];
		int position = 0;
		for (var item : entry.bridge) {
			Block block = BuiltInRegistries.BLOCK.getValue(Identifier.parse(item.get(0).getAsString()));
			if (block == null || !block.defaultBlockState().hasProperty(SlabBlock.TYPE)) return new Block[0];
			int weight = item.get(1).getAsInt();
			for (int i = 0; i < weight && position < result.length; i++) result[position++] = block;
		}
		return position == 100 ? result : new Block[0];
	}

	private void placeBridgeBlock(ChunkAccess chunk, int x, int y, int z, boolean isTop, Holder<Biome> biomeEntry) {
		Block block = getBridgeBlockState(biomeEntry, x, y, z);
		if (block != null) {
			BlockState state = block.defaultBlockState().setValue(SlabBlock.TYPE, isTop ? SlabType.TOP : SlabType.BOTTOM);

			// Check bounds
			if (chunk.getPos().getMinBlockX() <= x && chunk.getPos().getMaxBlockX() >= x &&
					chunk.getPos().getMinBlockZ() <= z && chunk.getPos().getMaxBlockZ() >= z &&
					y >= chunk.getMinY() && y < (chunk.getMinY() + chunk.getHeight())) {

				BlockPos pos = new BlockPos(x, y, z);
				if (BlueprintManager.get().getFormatVersion(this.blueprintId) >= 2) {
					BlockState previous = chunk.getBlockState(pos);
					if (previous.is(block) && previous.hasProperty(SlabBlock.TYPE)
							&& previous.getValue(SlabBlock.TYPE) != state.getValue(SlabBlock.TYPE)) {
						state = state.setValue(SlabBlock.TYPE, SlabType.DOUBLE);
					}
				}
				chunk.setBlockState(pos, state, 0);
			}
		}
	}

	private double modernBridgeY(double startY, double endY, double t, double horizontal) {
		double sag = Math.min(12, horizontal * 0.08);
		if (Math.abs(endY - startY) > horizontal * 0.5) sag = Math.min(sag, 3);
		double catenary = (Math.cosh(2 * t - 1) - Math.cosh(1)) / (Math.cosh(1) - 1);
		return startY + (endY - startY) * t + sag * catenary;
	}

	private int bridgeSteps(boolean modern, double horizontal, double vertical) {
		if (!modern) return (int) Math.ceil(horizontal * 1.5);
		double sag = Math.min(12, horizontal * 0.08);
		if (Math.abs(vertical) > horizontal * 0.5) sag = Math.min(sag, 3);
		// The maximum derivative of the catenary term is below 4.34 * sag.
		// Every sample therefore advances at most half a block in both axes.
		return (int) Math.ceil(Math.max(horizontal * 2, 2 * (Math.abs(vertical) + 4.34 * sag)));
	}

	private void placeClimbLadder(ChunkAccess chunk, int x, int z, int fromY, int toY, double dx, double dz) {
		boolean alongZ = Math.abs(dx) < Math.abs(dz);
		int sign = alongZ
				? (x + 3 <= chunk.getPos().getMaxBlockX() ? 1 : -1)
				: (z + 3 <= chunk.getPos().getMaxBlockZ() ? 1 : -1);
		int offsetX = alongZ ? 2 * sign : 0;
		int offsetZ = alongZ ? 0 : 2 * sign;
		Direction face = alongZ
				? (sign > 0 ? Direction.WEST : Direction.EAST)
				: (sign > 0 ? Direction.NORTH : Direction.SOUTH);
		Block backing = this.blueprintId.getPath().contains("nether") ? Blocks.CRIMSON_PLANKS : Blocks.OAK_PLANKS;
		int low = Math.min(fromY, toY), high = Math.max(fromY, toY);
		for (int y = low + 1; y <= high + 1; y++) {
			if (y < chunk.getMinY() || y >= chunk.getMaxY()) continue;
			int ladderX = x + offsetX, ladderZ = z + offsetZ;
			int supportX = ladderX + (offsetX == 0 ? 0 : sign);
			int supportZ = ladderZ + (offsetZ == 0 ? 0 : sign);
			if (chunk.getPos().getMinBlockX() <= supportX && supportX <= chunk.getPos().getMaxBlockX()
					&& chunk.getPos().getMinBlockZ() <= supportZ && supportZ <= chunk.getPos().getMaxBlockZ()) {
				chunk.setBlockState(new BlockPos(supportX, y, supportZ), backing.defaultBlockState(), 0);
			}
			if (chunk.getPos().getMinBlockX() <= ladderX && ladderX <= chunk.getPos().getMaxBlockX()
					&& chunk.getPos().getMinBlockZ() <= ladderZ && ladderZ <= chunk.getPos().getMaxBlockZ()) {
				chunk.setBlockState(new BlockPos(ladderX, y, ladderZ),
						Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, face), 0);
			}
			int vineX = supportX + (offsetX == 0 ? 0 : sign);
			int vineZ = supportZ + (offsetZ == 0 ? 0 : sign);
			if (Math.floorMod(Mth.getSeed(x, y, z), 6) == 0
					&& chunk.getPos().getMinBlockX() <= vineX && vineX <= chunk.getPos().getMaxBlockX()
					&& chunk.getPos().getMinBlockZ() <= vineZ && vineZ <= chunk.getPos().getMaxBlockZ()) {
				BlockPos vinePos = new BlockPos(vineX, y, vineZ);
				if (chunk.getBlockState(vinePos).isAir()) {
					chunk.setBlockState(vinePos, Blocks.VINE.defaultBlockState()
							.setValue(VineBlock.getPropertyForFace(face), true), 0);
				}
			}
		}
	}

	private boolean isCold(Holder<Biome> biomeEntry) {
		return biomeEntry.value().getBaseTemperature() < 0.15f;
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
	public int getGenDepth() {
		return 384; // Standard world height
	}

	@Override
	public int getSeaLevel() {
		return 0;
	}

	@Override
	public int getMinY() {
		return -64;
	}

	@Override
	public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState noiseConfig,
			StructureManager structureAccessor, ChunkAccess chunk) {
		return CompletableFuture.completedFuture(chunk);
	}

	@Override
	public void spawnOriginalMobs(@NonNull WorldGenRegion chunkRegion) {
		ChunkPos chunkPos = chunkRegion.getCenter();
		Holder<Biome> biome = chunkRegion.getBiome(chunkPos.getWorldPosition().atY(chunkRegion.getMaxY()));
		WorldgenRandom chunkRandom = new WorldgenRandom(new LegacyRandomSource(RandomSupport.generateUniqueSeed()));
		chunkRandom.setDecorationSeed(chunkRegion.getSeed(), chunkPos.getMinBlockX(), chunkPos.getMinBlockZ());
		NaturalSpawner.spawnMobsForChunkGeneration(chunkRegion, biome, chunkPos, chunkRandom);

		// 2. Generate Spheres
		List<BlueprintManager.BlueprintNode> nodes = BlueprintManager.get().getSpheresInChunk(this.blueprintId,
				chunkPos);
		Registry<ConfiguredSphere<?, ?>> sphereRegistry = chunkRegion.registryAccess()
				.lookupOrThrow(StarryRegistryKeys.CONFIGURED_SPHERE);

		for (BlueprintManager.BlueprintNode node : nodes) {
			Identifier sphereId = Identifier.tryParse(node.type);
			if (sphereId == null || !node.type.contains(":")) {
				sphereId = Identifier.fromNamespaceAndPath(StarrySkies.MOD_ID, node.type);
			}

			// Fallback logic
			ConfiguredSphere<?, ?> configuredSphere = sphereRegistry.getValue(sphereId);
			if (configuredSphere == null && !node.type.startsWith("overworld/")) {
				Identifier fallbackId = Identifier.fromNamespaceAndPath(StarrySkies.MOD_ID, "overworld/" + node.type);
				ConfiguredSphere<?, ?> fallbackSphere = sphereRegistry.getValue(fallbackId);
				if (fallbackSphere != null) {
					configuredSphere = fallbackSphere;
				}
			}

			if (configuredSphere != null) {
				BlockPos pos = new BlockPos(node.getX(), node.getY(), node.getZ());
				WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(chunkRegion.getSeed()));
				random.setLargeFeatureSeed(chunkRegion.getSeed(), node.getX(), node.getZ());

				PlacedSphere<?> sphere = configuredSphere.generate(random, chunkRegion, pos,
						(float) node.radius);
				sphere.setPosition(pos);

				sphere.populateEntities(chunkPos, chunkRegion, chunkRandom);
			}
		}
	}

	@Override
	public com.mojang.datafixers.util.Pair<BlockPos, net.minecraft.core.Holder<net.minecraft.world.level.levelgen.structure.Structure>> findNearestMapStructure(
			net.minecraft.server.level.ServerLevel world,
			net.minecraft.core.HolderSet<net.minecraft.world.level.levelgen.structure.Structure> structures,
			BlockPos center, int radius, boolean skipExistingChunks) {
		// If searching for meaningful structure (Stronghold) which is usually in the
		// "Eye of Ender Located" tag
		// Depending on MC version, we check if the set contains the Stronghold key
		net.minecraft.resources.ResourceKey<net.minecraft.world.level.levelgen.structure.Structure> strongholdKey = net.minecraft.resources.ResourceKey
				.create(net.minecraft.core.registries.Registries.STRUCTURE,
						net.minecraft.resources.Identifier.fromNamespaceAndPath("minecraft", "stronghold"));

		// We need to resolve the entry from the registry for the check
		// Using getOptional to avoid crash if not present, and handling RegistryKey
		// lookup
		java.util.Optional<net.minecraft.core.Holder.Reference<net.minecraft.world.level.levelgen.structure.Structure>> strongholdEntry = world
				.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.STRUCTURE)
				.get(strongholdKey);

		if (strongholdEntry.isPresent() && structures.contains(strongholdEntry.get())) {
			// Find nearest stronghold sphere
			BlueprintManager.BlueprintNode node = BlueprintManager.get().getNearestStronghold(
					this.blueprintId, center.getX(), center.getZ());
			if (node != null) {
				return new com.mojang.datafixers.util.Pair<>(new BlockPos(node.getX(), node.getY(), node.getZ()),
						strongholdEntry.get());
			}
		}
		return super.findNearestMapStructure(world, structures, center, radius, skipExistingChunks);
	}

	@Override
	public void applyBiomeDecoration(WorldGenLevel world, ChunkAccess chunk, StructureManager structureAccessor) {
		// super.generateFeatures(world, chunk, structureAccessor); // Disabled to
		// prevent vanilla structures

		ChunkPos chunkPos = chunk.getPos();
		long seed = world.getSeed();

		List<BlueprintManager.BlueprintNode> nodes = BlueprintManager.get().getSpheresInChunk(this.blueprintId,
				chunkPos);
		Registry<ConfiguredSphere<?, ?>> sphereRegistry = world.registryAccess()
				.lookupOrThrow(StarryRegistryKeys.CONFIGURED_SPHERE);

		for (BlueprintManager.BlueprintNode node : nodes) {
			// Some upstream decorators inspect their sphere center as well as the
			// target chunk. Keep both within the 26.2 feature region's read radius.
			if (Math.abs(Math.floorDiv(node.getX(), 16) - chunkPos.x()) > 1
					|| Math.abs(Math.floorDiv(node.getZ(), 16) - chunkPos.z()) > 1) continue;
			Identifier sphereId = Identifier.tryParse(node.type);
			if (sphereId == null || !node.type.contains(":")) {
				sphereId = Identifier.fromNamespaceAndPath(StarrySkies.MOD_ID, node.type);
			}

			// Fallback logic
			ConfiguredSphere<?, ?> configuredSphere = sphereRegistry.getValue(sphereId);
			if (configuredSphere == null) {
				String fallbackPrefix = this.blueprintId.getPath().contains("nether") ? "nether/" : "overworld/";
				if (!node.type.startsWith(fallbackPrefix)) {
					Identifier fallbackId = Identifier.fromNamespaceAndPath(StarrySkies.MOD_ID, fallbackPrefix + node.type);
					ConfiguredSphere<?, ?> fallbackSphere = sphereRegistry.getValue(fallbackId);
					if (fallbackSphere != null) {
						configuredSphere = fallbackSphere;
					}
				}
			}

			if (configuredSphere != null) {
				BlockPos pos = new BlockPos(node.getX(), node.getY(), node.getZ());
				WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(seed));
				random.setLargeFeatureSeed(seed, node.getX(), node.getZ());

				PlacedSphere<?> sphere = configuredSphere.generate(random, world, pos,
						(float) node.radius);
				sphere.setPosition(pos);

				sphere.decorate(world, chunkPos.getWorldPosition(), random);
			}
		}

		// Decorate bridges with holes and snow
		// This runs after all structures (spheres) are placed, ensuring we only modify
		// valid bridge slabs.
		decorateBridges(world, chunk);
		if (BlueprintManager.get().getFormatVersion(this.blueprintId) >= 3) {
			reinforceBridgeLandings(world, chunk);
		}
	}

	private void reinforceBridgeLandings(WorldGenLevel world, ChunkAccess chunk) {
		ChunkPos chunkPos = chunk.getPos();
		for (BlueprintManager.Edge edge : BlueprintManager.get().getBridgesInRegion(this.blueprintId,
				chunkPos.getMiddleBlockX(), chunkPos.getMiddleBlockZ())) {
			int[] start = edge.getStart();
			int[] end = edge.getEnd();
			String startType = edge.getStartType();
			String endType = edge.getEndType();
			if (startType != null && BridgeAnchorPolicy.modeFor(startType) != BridgeAnchorPolicy.Mode.TOP)
				placeBridgeLanding(world, chunk, start, end);
			if (endType != null && BridgeAnchorPolicy.modeFor(endType) != BridgeAnchorPolicy.Mode.TOP)
				placeBridgeLanding(world, chunk, end, start);
		}
	}

	private void placeBridgeLanding(WorldGenLevel world, ChunkAccess chunk, int[] endpoint, int[] other) {
		double dx = endpoint[0] - other[0];
		double dz = endpoint[2] - other[2];
		double horizontal = Math.hypot(dx, dz);
		if (horizontal < 1) return;
		ChunkPos chunkPos = chunk.getPos();
		for (int step = 1; step <= 3; step++) {
			int x = (int) Math.round(endpoint[0] + dx / horizontal * step);
			int z = (int) Math.round(endpoint[2] + dz / horizontal * step);
			int y = endpoint[1];
			if (x < chunkPos.getMinBlockX() || x > chunkPos.getMaxBlockX()
					|| z < chunkPos.getMinBlockZ() || z > chunkPos.getMaxBlockZ()
					|| y < chunk.getMinY() || y + 2 >= chunk.getMaxY()) continue;
			BlockPos pos = new BlockPos(x, y, z);
			Block material = getBridgeBlockState(world.getBiome(pos), x, y, z);
			if (material == null) continue;
			world.setBlock(pos, material.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.DOUBLE), 0);
			world.setBlock(pos.above(), Blocks.AIR.defaultBlockState(), 0);
			world.setBlock(pos.above(2), Blocks.AIR.defaultBlockState(), 0);
		}
	}

	@Override
	public int getBaseHeight(int x, int z, Heightmap.Types heightmap, LevelHeightAccessor world, RandomState noiseConfig) {
		return 0;
	}

	@Override
	public void addDebugScreenInfo(List<String> text, RandomState noiseConfig, BlockPos pos) {
	}

	@Override
	public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor world, RandomState noiseConfig) {
		BlockState[] states = new BlockState[world.getHeight()];
		Arrays.fill(states, Blocks.AIR.defaultBlockState());
		return new NoiseColumn(world.getMinY(), states);
	}
}
