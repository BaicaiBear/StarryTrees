package de.dafuqs.starryskies.registries;

import java.util.Optional;

import de.dafuqs.starryskies.StarrySkies;
import de.dafuqs.starryskies.worldgen.BlueprintManager;
import de.dafuqs.starryskies.worldgen.dimension.StarrySkyChunkGenerator;
import net.fabricmc.fabric.api.object.builder.v1.trade.TradeOfferHelper;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.MapColorComponent;
import net.minecraft.component.type.MapDecorationsComponent;
import net.minecraft.component.type.MapIdComponent;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.map.MapDecorationTypes;
import net.minecraft.item.map.MapState;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TradeOffers;
import net.minecraft.village.VillagerProfession;
import net.minecraft.world.World;
import net.minecraft.world.gen.chunk.ChunkGenerator;

public class StarryTrades {

    public static void register() {
        // Remove Vanilla Explorer Maps
        removeVanillaExplorerMaps();

        // Register for Master Cartographer (Level 5)
        TradeOfferHelper.registerVillagerOffers(VillagerProfession.CARTOGRAPHER, 5, factories -> {
            factories.add(new StoryMapFactory(0, "Beginning", 12)); // Part 1
            factories.add(new StoryMapFactory(3, "Middle", 24)); // Part 4
            factories.add(new StoryMapFactory(9, "Finale", 36)); // Part 10
        });
    }

    private static void removeVanillaExplorerMaps() {
        it.unimi.dsi.fastutil.ints.Int2ObjectMap<TradeOffers.Factory[]> trades = TradeOffers.PROFESSION_TO_LEVELED_TRADE
                .get(VillagerProfession.CARTOGRAPHER);
        if (trades != null) {
            for (int level : trades.keySet()) {
                TradeOffers.Factory[] factories = trades.get(level);
                if (factories != null) {
                    java.util.List<TradeOffers.Factory> kept = new java.util.ArrayList<>();
                    for (TradeOffers.Factory factory : factories) {
                        if (!(factory instanceof TradeOffers.SellMapFactory)) {
                            kept.add(factory);
                        }
                    }
                    trades.put(level, kept.toArray(new TradeOffers.Factory[0]));
                }
            }
        }
    }

    private static class StoryMapFactory implements TradeOffers.Factory {
        private final int targetPart;
        private final String suffix;
        private final int price;

        public StoryMapFactory(int targetPart, String suffix, int price) {
            this.targetPart = targetPart;
            this.suffix = suffix;
            this.price = price;
        }

        @Override
        public TradeOffer create(net.minecraft.entity.Entity entity, Random random) {
            World world = entity.getWorld();
            if (world.isClient || !(world instanceof net.minecraft.server.world.ServerWorld serverWorld))
                return null;

            ChunkGenerator cg = serverWorld.getChunkManager().getChunkGenerator();
            if (!(cg instanceof StarrySkyChunkGenerator starryGen)) {
                return null;
            }

            Identifier blueprintId = starryGen.getBlueprintId();
            BlockPos center = entity.getBlockPos();

            // Find nearest story part
            BlockPos targetPos = BlueprintManager.get().getNearestStoryPos(blueprintId, this.targetPart, center);

            if (targetPos != null) {
                // Create Map
                ItemStack mapStack = FilledMapItem.createMap(serverWorld, targetPos.getX(), targetPos.getZ(), (byte) 2,
                        true,
                        true);

                // Add Decoration
                MapState mapState = FilledMapItem.getMapState(mapStack, serverWorld);
                if (mapState != null) {
                    mapState.addDecoration(MapDecorationTypes.RED_X, serverWorld, "+", targetPos.getX(),
                            targetPos.getZ(), 180.0, null);
                }

                mapStack.set(DataComponentTypes.ITEM_NAME,
                        Text.translatable("item.starry_skies.story_map." + suffix.toLowerCase())
                                .styled(style -> style.withColor(0xFFD700))); // Gold color

                // Create Trade (Emeralds + Compass -> Map)
                return new TradeOffer(
                        new net.minecraft.village.TradedItem(Items.EMERALD, this.price),
                        Optional.of(new net.minecraft.village.TradedItem(Items.COMPASS, 1)),
                        mapStack,
                        12, // max uses
                        10, // experience
                        0.2f // price multiplier
                );
            }

            return null;
        }
    }
}
