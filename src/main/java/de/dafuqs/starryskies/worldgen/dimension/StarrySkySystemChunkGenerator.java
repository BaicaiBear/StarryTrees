package de.dafuqs.starryskies.worldgen.dimension;

import com.mojang.serialization.*;
import com.mojang.serialization.codecs.*;
import de.dafuqs.starryskies.*;
import de.dafuqs.starryskies.registries.*;
import de.dafuqs.starryskies.worldgen.*;
import net.minecraft.block.*;
import net.minecraft.registry.entry.*;
import net.minecraft.util.math.*;
import net.minecraft.util.math.random.*;
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
import java.util.stream.*;

public class StarrySkySystemChunkGenerator extends ChunkGenerator {

    public static final MapCodec<StarrySkySystemChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(
            (instance) -> instance.group(
                    BiomeSource.CODEC.fieldOf("biome_source").forGetter((generator) -> generator.biomeSource),
                    RegistryElementCodec.of(StarryRegistryKeys.SYSTEM_GENERATOR, SystemGenerator.CODEC)
                            .fieldOf("system_generator").forGetter((generator) -> generator.systemGenerator))
                    .apply(instance, StarrySkySystemChunkGenerator::new));

    protected final RegistryEntry<SystemGenerator> systemGenerator;

    public StarrySkySystemChunkGenerator(BiomeSource biomeSource, RegistryEntry<SystemGenerator> systemGenerator) {
        super(biomeSource);
        this.systemGenerator = systemGenerator;
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> getCodec() {
        return CODEC;
    }

    @Override
    public void buildSurface(ChunkRegion region, StructureAccessor structures, NoiseConfig noiseConfig, Chunk chunk) {
        ChunkPos chunkPos = chunk.getPos();

        int chunkPosStartX = chunkPos.getStartX();
        int chunkPosStartZ = chunkPos.getStartZ();

        // Generate floor if set
        if (systemGenerator.value().getFloorHeight() > 0) {
            for (int y = 0; y < getSeaLevel(); y++) {
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        chunk.setBlockState(new BlockPos(chunkPosStartX + x, y, chunkPosStartZ + z),
                                systemGenerator.value().getSeaBlock(y));
                    }
                }
            }
        }
    }

    @Override
    public void carve(ChunkRegion chunkRegion, long seed, NoiseConfig noiseConfig, BiomeAccess biomeAccess,
            StructureAccessor structureAccessor, Chunk chunk) {
        // no carver
        // generate spheres
        for (PlacedSphere<?> sphere : systemGenerator.value().getSystem(chunk, seed,
                structureAccessor.getRegistryManager())) {
            if (sphere.isInChunk(chunk.getPos())) {
                StarrySkies.LOGGER.debug("Generating sphere in chunk x:{} z:{} (StartX:{} StartZ:{}) {}",
                        chunk.getPos().x, chunk.getPos().z, chunk.getPos().getStartX(), chunk.getPos().getStartZ(),
                        sphere.getDescription(structureAccessor.getRegistryManager()));
                sphere.generate(chunk, structureAccessor.getRegistryManager());
                StarrySkies.LOGGER.debug("Generation Finished.");
            }
        }
    }

    @Override
    public int getWorldHeight() {
        return systemGenerator.value().getFloorHeight();
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

        for (PlacedSphere<?> sphere : systemGenerator.value().getSystem(chunkRegion.toServerWorld(),
                chunkRegion.getSeed(), chunkPos.x, chunkPos.z)) {
            sphere.populateEntities(chunkPos, chunkRegion, chunkRandom);
        }
    }

    @Override
    public void generateFeatures(StructureWorldAccess world, Chunk chunk, StructureAccessor structureAccessor) {
        super.generateFeatures(world, chunk, structureAccessor);

        // Manually trigger sphere decoration since we use vanilla biomes which lack the
        // feature
        ChunkPos chunkPos = chunk.getPos();
        ChunkRandom chunkRandom = new ChunkRandom(new CheckedRandom(RandomSeed.getSeed()));
        chunkRandom.setPopulationSeed(world.getSeed(), chunkPos.getStartX(), chunkPos.getStartZ());

        int x = chunkPos.x;
        int z = chunkPos.z;
        // Check local and neighbor chunks for spheres that might extend into this chunk
        for (PlacedSphere<?> sphere : systemGenerator.value().getSystem(world.toServerWorld(), world.getSeed(), x, z)) {
            if (sphere.isInChunk(chunkPos)) {
                sphere.decorate(world, chunkPos.getStartPos(), chunkRandom);
            }
        }
    }

    @Override
    public int getSeaLevel() {
        return systemGenerator.value().getFloorHeight();
    }

    @Override
    public int getMinimumY() {
        return 0;
    }

    @Override
    public int getHeight(int x, int z, Heightmap.Type heightmap, HeightLimitView world, NoiseConfig noiseConfig) {
        return systemGenerator.value().getFloorHeight();
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

    public SystemGenerator getSystemGenerator() {
        return this.systemGenerator.value();
    }
}
