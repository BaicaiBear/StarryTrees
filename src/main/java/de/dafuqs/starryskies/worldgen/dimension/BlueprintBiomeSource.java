package de.dafuqs.starryskies.worldgen.dimension;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import de.dafuqs.starryskies.worldgen.BlueprintManager;
import de.dafuqs.starryskies.worldgen.generator.BlueprintBiomeCatalog;
import java.util.stream.Stream;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;

public class BlueprintBiomeSource extends BiomeSource {

    public static final MapCodec<BlueprintBiomeSource> CODEC = RecordCodecBuilder.<BlueprintBiomeSource>mapCodec(
            (instance) -> instance.group(
                    RegistryOps.<Biome, BlueprintBiomeSource>retrieveGetter(Registries.BIOME),
                    Identifier.CODEC.fieldOf("blueprint_id")
                            .forGetter((BlueprintBiomeSource source) -> source.blueprintId))
                    .apply(instance, BlueprintBiomeSource::new));

    private final HolderGetter<Biome> biomeRegistry;
    private final Identifier blueprintId;

    public BlueprintBiomeSource(HolderGetter<Biome> biomeRegistry, Identifier blueprintId) {
        this.biomeRegistry = biomeRegistry;
        this.blueprintId = blueprintId;
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        return CODEC;
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        boolean nether = this.blueprintId.getPath().contains("nether");
        return BlueprintBiomeCatalog.INSTANCE.allBiomes(nether).stream()
                .map(name -> ResourceKey.create(Registries.BIOME,
                        Identifier.fromNamespaceAndPath("minecraft", name)))
                .map(this.biomeRegistry::getOrThrow);
    }

    @Override
    public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler noise) {
        // BiomeSource receives generic coordinates (Quartiles usually).
        // Convert to block coordinates for Blueprint lookup.
        // Assuming 1.18+ behavior where inputs are quartiles (1/4 block).
        int blockX = x * 4;
        int blockZ = z * 4;

        String infiniteBiome = BlueprintManager.get().biomeAt(this.blueprintId, blockX, y * 4, blockZ);
        if (infiniteBiome != null) {
            Identifier id = Identifier.tryParse(infiniteBiome);
            if (id != null) {
                var entry = this.biomeRegistry.get(ResourceKey.create(Registries.BIOME, id));
                if (entry.isPresent()) return entry.get();
            }
        }

        // Use BlueprintManager to find the nearest node
        BlueprintManager.BlueprintNode node = BlueprintManager.get().getNearestNode(this.blueprintId, blockX, y * 4, blockZ);
        if (node != null) {
            String biomeIdStr = node.biome;

            Identifier biomeId = Identifier.tryParse(biomeIdStr);
            if (biomeId == null) {
                // Fallback or try appending minecraft namespace
                biomeId = Identifier.fromNamespaceAndPath("minecraft", biomeIdStr);
            }

            if (this.biomeRegistry.get(ResourceKey.create(Registries.BIOME, biomeId)).isPresent()) {
                return this.biomeRegistry.getOrThrow(ResourceKey.create(Registries.BIOME, biomeId));
            } else {
                // Log warning if biome not found
                if (x % 100 == 0 && z % 100 == 0) {
                    System.err.println(
                            "BlueprintBiomeSource: Biome '" + biomeId + "' (raw: " + node.biome
                                    + ") not found in registry! Fallback to default.");
                }
            }
        }

        // Fallback
        if (this.blueprintId.getPath().contains("nether")) {
            return this.biomeRegistry.getOrThrow(Biomes.NETHER_WASTES);
        }
        return this.biomeRegistry.getOrThrow(Biomes.PLAINS);
    }
}
