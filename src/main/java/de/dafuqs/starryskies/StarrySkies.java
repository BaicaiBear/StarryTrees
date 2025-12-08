package de.dafuqs.starryskies;

import de.dafuqs.starryskies.data_loaders.*;
import de.dafuqs.starryskies.registries.*;
import de.dafuqs.starryskies.state_providers.*;
import de.dafuqs.starryskies.worldgen.*;
import de.dafuqs.starryskies.worldgen.dimension.*;
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

		StarryRegistries.register();
		StarryStateProviders.register();
		Spheres.initialize();
		StarryFeatures.initialize();
		SphereDecorators.initialize();

		// Initialize BlueprintManager immediately
		System.out.println("StarrySkies: Force init BlueprintManager during onInitialize");
		BlueprintManager.get().load();

		// Build a final map of sphere generation data for each chunk generator
		ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			// Verify or Reload if needed
			if (BlueprintManager.get() == null) {
				BlueprintManager.get().load();
			}
		});

		LOGGER.info("Finished loading.");
	}

}
