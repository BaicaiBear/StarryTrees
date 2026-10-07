package de.dafuqs.starryskies.worldgen;

import de.dafuqs.starryskies.StarrySkies;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;

/** The chunk generator's bounded view of the on-demand regional tree. */
public final class BlueprintManager {
    private static final BlueprintManager INSTANCE = new BlueprintManager();
    private volatile Map<Identifier, InfiniteRegionStore> regions = Map.of();

    private BlueprintManager() {}
    public static BlueprintManager get() { return INSTANCE; }

    public synchronized void initialize(MinecraftServer server) {
        close();
        Path worldDir = server.getWorldPath(LevelResource.ROOT).resolve("starrytrees");
        long seed = server.getWorldGenSettings().options().seed();
        regions = Map.of(
                StarrySkies.id("overworld"), new InfiniteRegionStore(worldDir, seed, false),
                StarrySkies.id("nether"), new InfiniteRegionStore(worldDir, seed, true));
        StarrySkies.LOGGER.info("Initialized on-demand infinite sphere trees for seed {}", seed);
    }

    public synchronized void close() {
        regions.values().forEach(InfiniteRegionStore::close);
        regions = Map.of();
    }

    private InfiniteRegionStore store(Identifier id) { return regions.get(id); }

    public String biomeAt(Identifier id, int x, int y, int z) {
        InfiniteRegionStore store = store(id);
        return store == null ? null : store.generator().biomes().at(x, y, z);
    }

    public BlueprintNode getNearestNode(Identifier id, int x, int z) {
        InfiniteRegionStore store = store(id);
        return store == null ? null : store.nearest(x, 0, z, true, null);
    }

    public BlueprintNode getNearestNode(Identifier id, int x, int y, int z) {
        InfiniteRegionStore store = store(id);
        return store == null ? null : store.nearest(x, y, z, false, null);
    }

    public BlueprintNode getNearestNode(Identifier id, int x, int z,
                                       java.util.function.Predicate<String> typePredicate) {
        InfiniteRegionStore store = store(id);
        return store == null ? null : store.nearest(x, 0, z, true, typePredicate);
    }

    public BlueprintNode getNearestStronghold(Identifier id, int x, int z) {
        InfiniteRegionStore store = store(id);
        return store == null ? null : store.nearestStronghold(x, z);
    }

    public List<BlueprintNode> getSpheresInChunk(Identifier id, ChunkPos chunk) {
        InfiniteRegionStore store = store(id);
        return store == null ? List.of() : store.spheres(chunk);
    }

    public List<Edge> getBridgesInRegion(Identifier id, int blockX, int blockZ) {
        InfiniteRegionStore store = store(id);
        return store == null ? List.of() : store.bridges(blockX, blockZ);
    }

    public int getFormatVersion(Identifier id) {
        return de.dafuqs.starryskies.worldgen.generator.InfiniteRegionGenerator.VERSION;
    }

    public static final class BlueprintNode {
        public int id;
        public double[] pos;
        public String biome;
        public String type;
        public double radius;
        public int getX() { return (int) pos[0]; }
        public int getY() { return (int) pos[1]; }
        public int getZ() { return (int) pos[2]; }
    }

    public static final class Edge {
        private final int[] start;
        private final int[] end;
        private final String startType;
        private final String endType;

        public Edge(List<List<Integer>> points, String startType, String endType) {
            this.start = new int[] {points.get(0).get(0), points.get(0).get(1), points.get(0).get(2)};
            this.end = new int[] {points.get(1).get(0), points.get(1).get(1), points.get(1).get(2)};
            this.startType = startType;
            this.endType = endType;
        }
        public int[] getStart() { return start; }
        public int[] getEnd() { return end; }
        public String getStartType() { return startType; }
        public String getEndType() { return endType; }
    }
}
