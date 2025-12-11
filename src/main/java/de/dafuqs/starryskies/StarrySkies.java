package de.dafuqs.starryskies;

import de.dafuqs.starryskies.data_loaders.*;
import de.dafuqs.starryskies.registries.*;
import de.dafuqs.starryskies.state_providers.*;
import de.dafuqs.starryskies.worldgen.*;
import de.dafuqs.starryskies.worldgen.dimension.*;
import it.unimi.dsi.fastutil.objects.*;
import java.util.*;
import net.fabricmc.api.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.resource.*;
import net.minecraft.registry.*;
import net.minecraft.resource.*;
import net.minecraft.server.world.*;
import net.minecraft.util.*;
import net.minecraft.world.gen.chunk.*;
import org.slf4j.*;

public class StarrySkies implements ModInitializer {

	public static final String MOD_ID = "starry_skies";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	public static final StarrySkiesConfig CONFIG = new StarrySkiesConfig();

	public static Identifier id(String name) {
		return Identifier.of(MOD_ID, name);
	}

	public static String idPlain(String name) {
		return id(name).toString();
	}

	public static boolean isStarryWorld(ServerWorld world) {
		ChunkGenerator chunkGenerator = world.getChunkManager().getChunkGenerator();
		return chunkGenerator instanceof StarrySkyChunkGenerator;
	}

	@Override
	public void onInitialize() {
		LOGGER.info("Starting up...");

		ResourceManagerHelper.get(ResourceType.SERVER_DATA).registerReloadListener(UniqueBlockGroupDataLoader.INSTANCE);
		ResourceManagerHelper.get(ResourceType.SERVER_DATA)
				.registerReloadListener(WeightedBlockGroupDataLoader.INSTANCE);

		// Register all the stuff
		Registry.register(Registries.CHUNK_GENERATOR, StarrySkies.id("starry_skies"), StarrySkyChunkGenerator.CODEC);
		Registry.register(Registries.CHUNK_GENERATOR, StarrySkies.id("starry_skies_system"),
				StarrySkySystemChunkGenerator.CODEC);

		StarryRegistries.register();
		StarryStateProviders.register();
		Spheres.initialize();
		StarryFeatures.initialize();
		SphereDecorators.initialize();
		StarryTrades.register();

		// Initialize BlueprintManager on server start to use world seed
		/*
		 * Removed legacy load() calls.
		 * BlueprintManager now initializes per-world in the SERVER_STARTING event.
		 */

		// Build a final map of sphere generation data for each chunk generator
		ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			// Initialize with world seed and path
			BlueprintManager.get().initialize(server);

			Registry<GenerationGroup> generationGroupRegistry = server.getRegistryManager()
					.getOrThrow(StarryRegistryKeys.GENERATION_GROUP);
			Registry<SystemGenerator> systemGeneratorRegistry = server.getRegistryManager()
					.getOrThrow(StarryRegistryKeys.SYSTEM_GENERATOR);
			Registry<ConfiguredSphere<?, ?>> configuredSphereRegistry = server.getRegistryManager()
					.getOrThrow(StarryRegistryKeys.CONFIGURED_SPHERE);

			for (GenerationGroup generationGroup : generationGroupRegistry) {
				// cursed generator group id lookup. Using getEntries() does return random
				// order, making worldgen undeterministic :C
				Identifier generationGroupId = generationGroupRegistry.getKey(generationGroup).get().getValue();
				Identifier systemGeneratorId = generationGroup.systemGeneratorId();

				SystemGenerator systemGenerator = systemGeneratorRegistry.get(systemGeneratorId);
				if (systemGenerator == null) {
					LOGGER.error(
							"System generator with id {} referenced in starry skies generation group {} was not found",
							generationGroup.systemGeneratorId(), generationGroupId);
					continue;
				}

				Map<ConfiguredSphere<?, ?>, Float> weightedSpheres = new Object2ObjectArrayMap<>();
				for (ConfiguredSphere<?, ?> sphere : configuredSphereRegistry) {
					Optional<SphereConfig.Generation> sphereGenerationGroup = sphere.getGenerationGroup();
					if (sphereGenerationGroup.isPresent()
							&& sphereGenerationGroup.get().group().equals(generationGroupId)) {
						weightedSpheres.put(sphere, sphereGenerationGroup.get().weight());
					}
				}

				if (!weightedSpheres.isEmpty()) {
					systemGenerator.addGenerationGroup(weightedSpheres, generationGroup.weight());
				}
			}
		});

		LOGGER.info("Finished loading.");
	}

}
