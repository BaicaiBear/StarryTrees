package de.dafuqs.starryskies.worldgen.dimension;

import com.mojang.serialization.*;
import de.dafuqs.starryskies.registries.StarryRegistryKeys;
import de.dafuqs.starryskies.worldgen.*;
import net.minecraft.registry.Registry;
import net.minecraft.util.math.*;
import net.minecraft.world.gen.feature.*;
import net.minecraft.world.gen.feature.util.*;
import org.jetbrains.annotations.*;

public class SphereDecorationFeature extends Feature<DefaultFeatureConfig> {

	public SphereDecorationFeature(Codec<DefaultFeatureConfig> configCodec) {
		super(configCodec);
	}

	@Override
	public boolean generate(@NotNull FeatureContext featureContext) {
		if (featureContext.getGenerator() instanceof StarrySkyChunkGenerator) {
			// Blueprint integration
			// FeatureContext gives origin block pos.
			// But we need to check spheres overlapping this chunk/pos.
			// Actually getSpheresInChunk handles checking spheres overlapping the chunk.

			var chunkPos = new ChunkPos(featureContext.getOrigin());
			var nodes = BlueprintManager.get().getSpheresInChunk(chunkPos);

			net.minecraft.registry.DynamicRegistryManager registryManager = featureContext.getWorld()
					.getRegistryManager();
			Registry<ConfiguredSphere<?, ?>> sphereRegistry = registryManager
					.getOrThrow(StarryRegistryKeys.CONFIGURED_SPHERE);

			for (BlueprintManager.BlueprintNode node : nodes) {
				net.minecraft.util.Identifier id = net.minecraft.util.Identifier.tryParse(node.type);
				if (id == null)
					id = net.minecraft.util.Identifier.of("minecraft", node.type);

				ConfiguredSphere<?, ?> configuredSphere = sphereRegistry.get(id);
				if (configuredSphere != null) {
					// Create minimal PlacedSphere to call decorate
					long seed = featureContext.getWorld().getSeed();
					net.minecraft.util.math.random.ChunkRandom random = new net.minecraft.util.math.random.ChunkRandom(
							new net.minecraft.util.math.random.CheckedRandom(seed));
					random.setCarverSeed(seed, node.getX(), node.getZ());

					BlockPos pos = new BlockPos(node.getX(), node.getY(), node.getZ());
					PlacedSphere<?> sphere = configuredSphere.generate(random, registryManager, pos,
							(float) node.radius);
					sphere.setPosition(pos);

					if (sphere.isInChunk(chunkPos)) {
						sphere.decorate(featureContext.getWorld(), featureContext.getOrigin(),
								featureContext.getRandom());
					}
				}
			}
		}
		return false;
	}

}
