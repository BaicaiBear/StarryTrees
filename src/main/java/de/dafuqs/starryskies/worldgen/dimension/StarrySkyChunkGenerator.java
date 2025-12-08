package de.dafuqs.starryskies.worldgen.dimension;

import com.mojang.serialization.*;
import com.mojang.serialization.codecs.*;
import de.dafuqs.starryskies.StarrySkies;
import de.dafuqs.starryskies.registries.*;
import de.dafuqs.starryskies.worldgen.*;
import net.minecraft.block.*;
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
		// Generate Spheres
		List<BlueprintManager.BlueprintNode> nodes = BlueprintManager.get().getSpheresInChunk(chunk.getPos());

		// Debug Log for chunks near origin or periodically
		if (chunk.getPos().x >= -2 && chunk.getPos().x <= 2 && chunk.getPos().z >= -2 && chunk.getPos().z <= 2) {
			System.out.println(
					"StarrySkies: Carving chunk " + chunk.getPos() + ". Found " + nodes.size() + " potential spheres.");
			if (!nodes.isEmpty()) {
				for (BlueprintManager.BlueprintNode node : nodes) {
					System.out.println("   - Potential Sphere: " + node.type + " at " + Arrays.toString(node.pos));
				}
			}
		}

		Registry<ConfiguredSphere<?, ?>> sphereRegistry = chunkRegion.getRegistryManager()
				.getOrThrow(StarryRegistryKeys.CONFIGURED_SPHERE);

		for (BlueprintManager.BlueprintNode node : nodes) {
			Identifier sphereId = Identifier.tryParse(node.type);
			if (sphereId == null || sphereId.getNamespace().equals("minecraft")) {
				// If no namespace is provided, or it defaults to minecraft (via tryParse on
				// path), assume starry_skies
				// But wait, Identifier.tryParse("foo/bar") returns "minecraft:foo/bar".
				// We want to force starry_skies if the input string didn't have a colon.
				if (!node.type.contains(":")) {
					sphereId = Identifier.of(StarrySkies.MOD_ID, node.type);
				}
			}

			ConfiguredSphere<?, ?> configuredSphere = sphereRegistry.get(sphereId);

			// Fallback: Try prefixing with "overworld/" if not found
			// The blueprint seems to use short names "ores/iron" but the registry has
			// "overworld/ores/iron"
			if (configuredSphere == null && !node.type.startsWith("overworld/")) {
				Identifier fallbackId = Identifier.of(StarrySkies.MOD_ID, "overworld/" + node.type);
				configuredSphere = sphereRegistry.get(fallbackId);
				if (configuredSphere != null) {
					// Update sphereId for logging purposes
					sphereId = fallbackId;
				}
			}

			if (configuredSphere != null) {
				// Debug: Resolution success
				if (chunk.getPos().x >= -2 && chunk.getPos().x <= 2 && chunk.getPos().z >= -2
						&& chunk.getPos().z <= 2) {
					// System.out.println(" -> Resolved sphere type " + sphereId + "
					// successfully.");
				}

				BlockPos pos = new BlockPos(node.getX(), node.getY(), node.getZ());
				// Generate placed sphere
				ChunkRandom random = new ChunkRandom(new CheckedRandom(seed));
				// Use deterministic seed based on sphere position to ensure consistency
				random.setCarverSeed(seed, node.getX(), node.getZ());

				PlacedSphere<?> sphere = configuredSphere.generate(random, chunkRegion.getRegistryManager(), pos,
						(float) node.radius);
				sphere.setPosition(pos);

				boolean intersects = sphere.isInChunk(chunk.getPos());

				// Debug log for generation
				if (chunk.getPos().x >= -2 && chunk.getPos().x <= 2 && chunk.getPos().z >= -2
						&& chunk.getPos().z <= 2) {
					if (intersects) {
						System.out.println("   -> MATCH! Generating sphere " + sphereId + " at " + pos + " (R="
								+ node.radius + ") in chunk " + chunk.getPos());
					}
				}

				if (intersects) {
					sphere.generate(chunk, chunkRegion.getRegistryManager());
				}
			} else {
				if (chunk.getPos().x >= -2 && chunk.getPos().x <= 2 && chunk.getPos().z >= -2
						&& chunk.getPos().z <= 2) {
					System.err.println(
							"   -> WARNING: Failed to resolve sphere type: " + sphereId + " (Raw: " + node.type + ")");
				}
			}
		}
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