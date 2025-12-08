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
		generateBridges(chunk, chunkRegion);

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
			} else {
				// Warning is useful but maybe too spammy if user asked to remove "all" logs.
				// Keeping it as System.err for real errors.
				// System.err.println(" -> WARNING: Failed to resolve sphere type: " +
				// sphereId);
			}
		}
	}

	private void generateBridges(Chunk chunk, ChunkRegion chunkRegion) {
		ChunkPos chunkPos = chunk.getPos();
		int chunkMinX = chunkPos.getStartX();
		int chunkMaxX = chunkPos.getEndX();
		int chunkMinZ = chunkPos.getStartZ();
		int chunkMaxZ = chunkPos.getEndZ();

		List<BlueprintManager.Edge> bridges = BlueprintManager.get().getBridgesInRegion(chunkPos.getCenterX(),
				chunkPos.getCenterZ());

		if (bridges == null || bridges.isEmpty())
			return;

		BlockState slabBottom = Blocks.STONE_SLAB.getDefaultState().with(SlabBlock.TYPE, SlabType.BOTTOM);
		BlockState slabTop = Blocks.STONE_SLAB.getDefaultState().with(SlabBlock.TYPE, SlabType.TOP);

		// Global stiffness parameter A
		double A_BASE = 150.0;

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

					BlockState state = isTop ? slabTop : slabBottom;

					// Place center and neighbors
					placeBridgeBlock(chunk, bX, yBlock, bZ, state);
					placeBridgeBlock(chunk, bX + 1, yBlock, bZ, state);
					placeBridgeBlock(chunk, bX - 1, yBlock, bZ, state);
					placeBridgeBlock(chunk, bX, yBlock, bZ + 1, state);
					placeBridgeBlock(chunk, bX, yBlock, bZ - 1, state);
				}
			}
		}
	}

	private void placeBridgeBlock(Chunk chunk, int x, int y, int z, BlockState state) {
		if (chunk.getPos().getStartX() <= x && chunk.getPos().getEndX() >= x &&
				chunk.getPos().getStartZ() <= z && chunk.getPos().getEndZ() >= z &&
				y >= chunk.getBottomY() && y < (chunk.getBottomY() + chunk.getHeight())) {

			BlockPos pos = new BlockPos(x, y, z);
			chunk.setBlockState(pos, state, 0);
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

			ConfiguredSphere<?, ?> configuredSphere = sphereRegistry.get(sphereId);
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