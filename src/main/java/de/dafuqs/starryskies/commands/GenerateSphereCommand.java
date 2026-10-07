package de.dafuqs.starryskies.commands;

import com.mojang.brigadier.*;
import com.mojang.brigadier.arguments.StringArgumentType;
import de.dafuqs.starryskies.*;
import de.dafuqs.starryskies.registries.StarryRegistryKeys;
import de.dafuqs.starryskies.worldgen.*;
import net.minecraft.commands.*;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.*;
import net.minecraft.resources.Identifier;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.*;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import org.jspecify.annotations.*;

public class GenerateSphereCommand {
	
	public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext registryAccess) {
		dispatcher.register(Commands.literal("starryskies_generate")
				.requires((source) -> source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.byId(StarrySkies.CONFIG.generateSphereCommandRequiredPermissionLevel))))
				.then(Commands.argument("sphere", StringArgumentType.word())
						.executes(context -> execute(context.getSource(), null, StringArgumentType.getString(context, "sphere")))
						.then(Commands.argument("pos", BlockPosArgument.blockPos())
								.executes(context -> execute(context.getSource(), BlockPosArgument.getLoadedBlockPos(context, "pos"), StringArgumentType.getString(context, "sphere"))))));
	}
	
	private static int execute(CommandSourceStack source, @Nullable BlockPos pos, String sphereName) {
		Identifier id = Identifier.tryParse(sphereName);
		ConfiguredSphere<?, ?> sphere = id == null ? null
				: source.registryAccess().lookupOrThrow(StarryRegistryKeys.CONFIGURED_SPHERE).getValue(id);
		if (sphere == null) {
			source.sendFailure(Component.literal("Unknown sphere: " + sphereName));
			return 0;
		}
		if (pos == null) {
			pos = BlockPos.containing(source.getPosition());
		}
		
		ServerLevel level = source.getLevel();
		RandomSource random = level.getRandom();
		WorldgenRandom chunkRandom = new WorldgenRandom(random);
		PlacedSphere<?> placed = sphere.generate(chunkRandom, level, pos, sphere.getSize(chunkRandom));
		placed.setPosition(new BlockPos(pos.getX(), pos.getY(), pos.getZ()));
		
		placed.streamChunksWithSphere().forEach(chunkPos -> placed.generate(level.getChunk(chunkPos.getWorldPosition()), level));
		placed.streamChunksWithSphere().forEach(chunkPos -> placed.decorate(level, chunkPos.getWorldPosition(), random));
		placed.populateEntities(ChunkPos.containing(pos), level, chunkRandom);
		placed.streamBlockPosesOfSpheres().forEach(blockPos -> level.getChunkSource().blockChanged(blockPos));
		
		return 0;
	}
	
	
}
