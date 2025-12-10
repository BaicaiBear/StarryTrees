package de.dafuqs.starryskies.worldgen.dimension;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import de.dafuqs.starryskies.worldgen.BlueprintManager;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryOps;
import net.minecraft.registry.RegistryEntryLookup;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.Identifier;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeKeys;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.biome.source.util.MultiNoiseUtil;

import java.util.stream.Stream;

public class BlueprintBiomeSource extends BiomeSource {

    public static final MapCodec<BlueprintBiomeSource> CODEC = RecordCodecBuilder.<BlueprintBiomeSource>mapCodec(
            (instance) -> instance.group(
                    RegistryOps.<Biome, BlueprintBiomeSource>getEntryLookupCodec(RegistryKeys.BIOME),
                    Identifier.CODEC.fieldOf("blueprint_id")
                            .forGetter((BlueprintBiomeSource source) -> source.blueprintId))
                    .apply(instance, BlueprintBiomeSource::new));

    private final RegistryEntryLookup<Biome> biomeRegistry;
    private final Identifier blueprintId;

    public BlueprintBiomeSource(RegistryEntryLookup<Biome> biomeRegistry, Identifier blueprintId) {
        this.biomeRegistry = biomeRegistry;
        this.blueprintId = blueprintId;
    }

    @Override
    protected MapCodec<? extends BiomeSource> getCodec() {
        return CODEC;
    }

    @Override
    protected Stream<RegistryEntry<Biome>> biomeStream() {
        return Stream.of(this.biomeRegistry.getOrThrow(BiomeKeys.PLAINS));
    }

    @Override
    public RegistryEntry<Biome> getBiome(int x, int y, int z, MultiNoiseUtil.MultiNoiseSampler noise) {
        // BiomeSource receives generic coordinates (Quartiles usually).
        // Convert to block coordinates for Blueprint lookup.
        // Assuming 1.18+ behavior where inputs are quartiles (1/4 block).
        int blockX = x * 4;
        int blockZ = z * 4;

        // Use BlueprintManager to find the nearest node
        BlueprintManager.BlueprintNode node = BlueprintManager.get().getNearestNode(this.blueprintId, blockX, blockZ);
        if (node != null) {
            String biomeIdStr = node.biome;

            Identifier biomeId = Identifier.tryParse(biomeIdStr);
            if (biomeId == null) {
                // Fallback or try appending minecraft namespace
                biomeId = Identifier.of("minecraft", biomeIdStr);
            }

            // Debug Log: Check biome assignment at world origin
            if (blockX >= -40 && blockX <= 40 && blockZ >= -40 && blockZ <= 40 && y == 64
                    && (blockX + blockZ) % 20 == 0) {
                System.out.println("BlueprintBiomeSource: At Block " + blockX + "," + blockZ + " (Quartile " + x + ","
                        + z + ") nearest node is " + node.type
                        + " (Biome: " + node.biome + ") -> Resolved: " + biomeId);
            }

            if (this.biomeRegistry.getOptional(RegistryKey.of(RegistryKeys.BIOME, biomeId)).isPresent()) {
                return this.biomeRegistry.getOrThrow(RegistryKey.of(RegistryKeys.BIOME, biomeId));
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
            return this.biomeRegistry.getOrThrow(BiomeKeys.NETHER_WASTES);
        }
        return this.biomeRegistry.getOrThrow(BiomeKeys.PLAINS);
    }
}
