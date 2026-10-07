package de.dafuqs.starryskies.commands;

import com.mojang.brigadier.CommandDispatcher;
import de.dafuqs.starryskies.StarrySkies;
import de.dafuqs.starryskies.Support;
import de.dafuqs.starryskies.worldgen.BlueprintManager;
import de.dafuqs.starryskies.worldgen.dimension.BlueprintChunkGenerator;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;

public class ClosestSphereCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext registryAccess) {
        dispatcher.register(Commands.literal("starryskies_locate")
                .requires(source -> source.permissions().hasPermission(new Permission.HasCommandLevel(
                        PermissionLevel.byId(StarrySkies.CONFIG.locateSphereCommandRequiredPermissionLevel))))
                .executes(context -> execute(context.getSource())));
    }

    private static int execute(CommandSourceStack source) {
        BlockPos pos = BlockPos.containing(source.getPosition());
        if (source.getLevel().getChunkSource().getGenerator() instanceof BlueprintChunkGenerator generator) {
            BlueprintManager.BlueprintNode node = BlueprintManager.get().getNearestNode(
                    generator.getBlueprintId(), pos.getX(), pos.getY(), pos.getZ());
            if (node != null) {
                source.sendSuccess(() -> Component.literal("Nearest sphere: " + node.type + " at "
                        + node.getX() + " " + node.getY() + " " + node.getZ()), false);
                return 1;
            }
        } else {
            var nearest = Support.getClosestSphere(source.getLevel(), pos);
            if (nearest.isPresent() && nearest.get().sphere != null) {
                var sphere = nearest.get().sphere;
                source.sendSuccess(() -> Component.literal("Nearest sphere: " + sphere.getID(source.registryAccess())
                        + " at " + sphere.getPosition().toShortString()), false);
                return 1;
            }
        }
        source.sendFailure(Component.literal("No sphere found."));
        return 0;
    }
}
