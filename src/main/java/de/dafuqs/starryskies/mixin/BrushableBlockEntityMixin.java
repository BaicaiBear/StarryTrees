package de.dafuqs.starryskies.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.block.entity.BrushableBlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

@Mixin(BrushableBlockEntity.class)
public abstract class BrushableBlockEntityMixin extends BlockEntity {

    public BrushableBlockEntityMixin(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    private static java.lang.reflect.Field LOOT_TABLE_FIELD;
    private static java.lang.reflect.Field BRUSHES_COUNT_FIELD;
    private static java.lang.reflect.Field NEXT_DUST_TIME_FIELD;
    private static java.lang.reflect.Method SPAWN_ITEM_METHOD;
    private static boolean starry_skies$initialized = false;

    private static void starry_skies$lazyInit() {
        if (starry_skies$initialized)
            return;
        starry_skies$initialized = true;

        try {
            for (java.lang.reflect.Field f : BrushableBlockEntity.class.getDeclaredFields()) {
                if (f.getName().equals("lootTable") || f.getType().getName().contains("RegistryKey")) {
                    f.setAccessible(true);
                    LOOT_TABLE_FIELD = f;
                } else if (f.getName().equals("brushesCount") || f.getName().equals("field_42806")) {
                    f.setAccessible(true);
                    BRUSHES_COUNT_FIELD = f;
                } else if (f.getName().equals("nextDustTime") || f.getName().equals("field_42808")) {
                    f.setAccessible(true);
                    NEXT_DUST_TIME_FIELD = f;
                }
            }

            for (java.lang.reflect.Method m : BrushableBlockEntity.class.getDeclaredMethods()) {
                if (m.getParameterCount() == 3
                        && m.getParameterTypes()[0].equals(net.minecraft.server.world.ServerWorld.class)
                        && m.getParameterTypes()[1].equals(net.minecraft.entity.LivingEntity.class)
                        && m.getParameterTypes()[2].equals(net.minecraft.item.ItemStack.class)) {
                    m.setAccessible(true);
                    SPAWN_ITEM_METHOD = m;
                    break;
                }
            }

            if (SPAWN_ITEM_METHOD == null)
                System.out.println("STARRYSKIES: Could not find spawnItem method during lazy init");
            if (LOOT_TABLE_FIELD == null)
                System.out.println("STARRYSKIES: Could not find lootTable field during lazy init");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Capture Loot Table when brushing starts/continues
    @Inject(method = "brush", at = @At("HEAD"))
    public void starry_skies$captureLootTable(long worldTime, net.minecraft.server.world.ServerWorld world,
            net.minecraft.entity.LivingEntity user, net.minecraft.util.math.Direction direction,
            net.minecraft.item.ItemStack brush,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> ci) {
        starry_skies$lazyInit();
        if (LOOT_TABLE_FIELD != null) {
            try {
                Object key = LOOT_TABLE_FIELD.get(this);
                if (key != null) {
                    String keyStr = key.toString();
                    if (keyStr.contains("starryskies:chests/lore/")) {
                        this.starry_skies$capturedLootTableKey = key;
                        this.starry_skies$isStoryBlock = true;
                    }
                }
            } catch (Exception e) {
                // Ignore
            }
        }
    }

    private Object starry_skies$capturedLootTableKey = null;
    private boolean starry_skies$isStoryBlock = false;

    @Inject(method = "finishBrushing", at = @At("HEAD"), cancellable = true)
    public void starry_skies$onFinishBrushing(net.minecraft.server.world.ServerWorld world,
            net.minecraft.entity.LivingEntity user, net.minecraft.item.ItemStack brush, CallbackInfo ci) {
        starry_skies$lazyInit();
        if (this.starry_skies$isStoryBlock && this.starry_skies$capturedLootTableKey != null) {
            try {
                // 1. Restore Loot Table (Required for spawnItem to work)
                if (LOOT_TABLE_FIELD != null) {
                    LOOT_TABLE_FIELD.set(this, this.starry_skies$capturedLootTableKey);
                }

                // 2. Spawn Item (This likely consumes/clears the loot table)
                if (SPAWN_ITEM_METHOD != null) {
                    SPAWN_ITEM_METHOD.invoke(this, world, user, brush);
                }

                // 3. Re-Restore Loot Table (For Reusability and Break Protection)
                if (LOOT_TABLE_FIELD != null) {
                    LOOT_TABLE_FIELD.set(this, this.starry_skies$capturedLootTableKey);
                }

                // 4. Reset Counters
                if (BRUSHES_COUNT_FIELD != null)
                    BRUSHES_COUNT_FIELD.setInt(this, 0);
                if (NEXT_DUST_TIME_FIELD != null)
                    NEXT_DUST_TIME_FIELD.setLong(this, 0L);

                // 5. Reset Block State (Visuals)
                BlockState state = this.getCachedState();
                if (state.getProperties().contains(net.minecraft.state.property.Properties.DUSTED)) {
                    world.setBlockState(pos, state.with(net.minecraft.state.property.Properties.DUSTED, 0), 3);
                }

                // 6. Cancel Break
                ci.cancel();

            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
