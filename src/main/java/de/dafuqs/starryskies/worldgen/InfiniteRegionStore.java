package de.dafuqs.starryskies.worldgen;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import de.dafuqs.starryskies.StarrySkies;
import de.dafuqs.starryskies.worldgen.generator.InfiniteRegionGenerator;
import de.dafuqs.starryskies.worldgen.generator.BlueprintBiomeCatalog;
import de.dafuqs.starryskies.worldgen.generator.InfiniteRegionGenerator.RegionData;
import de.dafuqs.starryskies.worldgen.generator.InfiniteRegionGenerator.RegionKey;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import net.minecraft.world.level.ChunkPos;

/** Bounded cache and durable first-visit snapshots for infinite regions. */
public final class InfiniteRegionStore implements AutoCloseable {
    private static final int CACHE_LIMIT = 96;
    private static final int MAX_SPHERE_RADIUS = 39;
    private static final Gson GSON = new GsonBuilder().create();
    private final InfiniteRegionGenerator generator;
    private final Path directory;
    private final ExecutorService workers;
    private final Map<RegionKey, CompletableFuture<RegionData>> cache = new LinkedHashMap<>(128, 0.75f, true);

    private record Manifest(long seed, int topologyVersion, int ruleVersion,
                            String paletteHash, boolean nether) {}

    public InfiniteRegionStore(Path worldDir, long seed, boolean nether) {
        generator = new InfiniteRegionGenerator(seed, nether);
        directory = worldDir.resolve(nether ? "infinite-nether" : "infinite-overworld");
        try {
            Files.createDirectories(directory.resolve("regions"));
            Path marker = directory.resolve("manifest.json");
            if (Files.exists(marker)) {
                try (Reader reader = Files.newBufferedReader(marker, StandardCharsets.UTF_8)) {
                    Manifest previous = GSON.fromJson(reader, Manifest.class);
                    if (previous == null || previous.seed != seed || previous.nether != nether
                            || previous.topologyVersion != InfiniteRegionGenerator.VERSION
                            || previous.ruleVersion != InfiniteRegionGenerator.VERSION
                            || !BlueprintBiomeCatalog.INSTANCE.fingerprint().equals(previous.paletteHash)) {
                        throw new IllegalStateException("Infinite world rules differ from saved manifest: " + marker);
                    }
                }
            } else {
                writeAtomic(marker, GSON.toJson(new Manifest(seed, InfiniteRegionGenerator.VERSION,
                        InfiniteRegionGenerator.VERSION, BlueprintBiomeCatalog.INSTANCE.fingerprint(), nether))
                        .getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot prepare infinite region store " + directory, e);
        }
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, "starrytrees-region-" + (nether ? "nether" : "overworld"));
            thread.setDaemon(true);
            return thread;
        };
        workers = Executors.newFixedThreadPool(2, factory);
    }

    public InfiniteRegionGenerator generator() { return generator; }

    public RegionData get(int rx, int rz) {
        RegionKey key = new RegionKey(rx, rz);
        CompletableFuture<RegionData> future;
        synchronized (cache) {
            future = cache.get(key);
            if (future == null) {
                future = new CompletableFuture<>();
                cache.put(key, future);
                CompletableFuture<RegionData> pending = future;
                workers.execute(() -> {
                    try { pending.complete(loadOrGenerate(key)); }
                    catch (Throwable failure) { pending.completeExceptionally(failure); }
                });
                evictCompleted();
            }
        }
        return future.join();
    }

    private void evictCompleted() {
        if (cache.size() <= CACHE_LIMIT) return;
        var iterator = cache.entrySet().iterator();
        while (cache.size() > CACHE_LIMIT && iterator.hasNext()) {
            if (iterator.next().getValue().isDone()) iterator.remove();
        }
    }

    private RegionData loadOrGenerate(RegionKey key) {
        Path path = directory.resolve("regions").resolve("r." + key.x() + "." + key.z() + ".json.gz");
        try {
            if (Files.exists(path)) {
                try (Reader reader = new java.io.InputStreamReader(
                        new GZIPInputStream(Files.newInputStream(path)), StandardCharsets.UTF_8)) {
                    RegionData data = GSON.fromJson(reader, RegionData.class);
                    if (data == null || data.version != InfiniteRegionGenerator.VERSION
                            || data.regionX != key.x() || data.regionZ != key.z()
                            || data.nodes == null || data.edges == null)
                        throw new IOException("Invalid region snapshot " + path);
                    return data;
                }
            }
            long start = System.nanoTime();
            RegionData data = generator.generate(key.x(), key.z());
            byte[] json = GSON.toJson(data).getBytes(StandardCharsets.UTF_8);
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp." + Thread.currentThread().threadId());
            try {
                try (GZIPOutputStream output = new GZIPOutputStream(Files.newOutputStream(temporary))) {
                    output.write(json);
                }
                moveAtomic(temporary, path);
            } finally {
                Files.deleteIfExists(temporary);
            }
            StarrySkies.LOGGER.debug("Infinite region {},{}: {} spheres, {} bridges in {} ms",
                    key.x(), key.z(), data.nodes.size(), data.edges.size(),
                    (System.nanoTime() - start) / 1_000_000);
            return data;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read or save infinite region " + path, e);
        }
    }

    private static void writeAtomic(Path path, byte[] contents) throws IOException {
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.write(temporary, contents);
            moveAtomic(temporary, path);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void moveAtomic(Path from, Path to) throws IOException {
        try { Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException ignored) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public List<BlueprintManager.BlueprintNode> spheres(ChunkPos chunk) {
        int extra = MAX_SPHERE_RADIUS + 2;
        int minX = chunk.getMinBlockX(), maxX = chunk.getMaxBlockX();
        int minZ = chunk.getMinBlockZ(), maxZ = chunk.getMaxBlockZ();
        List<BlueprintManager.BlueprintNode> found = new ArrayList<>();
        for (RegionData region : regions(minX - extra, maxX + extra, minZ - extra, maxZ + extra)) {
            for (BlueprintManager.BlueprintNode node : region.nodes) {
                double radius = node.radius + 1;
                if (node.getX() + radius >= minX && node.getX() - radius <= maxX
                        && node.getZ() + radius >= minZ && node.getZ() - radius <= maxZ) found.add(node);
            }
        }
        return found;
    }

    public List<BlueprintManager.Edge> bridges(int blockX, int blockZ) {
        int minX = blockX - 8, maxX = blockX + 7;
        int minZ = blockZ - 8, maxZ = blockZ + 7;
        int extra = InfiniteRegionGenerator.MAX_BRIDGE_SPAN + 4;
        List<BlueprintManager.Edge> found = new ArrayList<>();
        for (RegionData region : regions(minX - extra, maxX + extra, minZ - extra, maxZ + extra)) {
            for (InfiniteRegionGenerator.BridgeRecord bridge : region.edges) {
                int[] a = {bridge.points.get(0).get(0), bridge.points.get(0).get(1), bridge.points.get(0).get(2)};
                int[] b = {bridge.points.get(1).get(0), bridge.points.get(1).get(1), bridge.points.get(1).get(2)};
                if (Math.max(a[0], b[0]) + 2 < minX || Math.min(a[0], b[0]) - 2 > maxX
                        || Math.max(a[2], b[2]) + 2 < minZ || Math.min(a[2], b[2]) - 2 > maxZ) continue;
                found.add(new BlueprintManager.Edge(bridge.points, bridge.startType, bridge.endType));
            }
        }
        return found;
    }

    public BlueprintManager.BlueprintNode nearest(int x, int y, int z, boolean twoDimensional,
                                                   java.util.function.Predicate<String> predicate) {
        BlueprintManager.BlueprintNode best = null;
        double distance = Double.POSITIVE_INFINITY;
        int centerX = generator.regionAt(x), centerZ = generator.regionAt(z);
        int maxRing = predicate == null ? 1 : 8;
        for (int ring = 0; ring <= maxRing; ring++) {
            for (int dx = -ring; dx <= ring; dx++) for (int dz = -ring; dz <= ring; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                for (BlueprintManager.BlueprintNode node : get(centerX + dx, centerZ + dz).nodes) {
                    if (predicate != null && !predicate.test(node.type)) continue;
                    double d = Math.pow(node.getX() - x, 2) + Math.pow(node.getZ() - z, 2)
                            + (twoDimensional ? 0 : Math.pow(node.getY() - y, 2));
                    if (d < distance) { distance = d; best = node; }
                }
            }
            if (best != null && (predicate != null || ring >= 1)) break;
        }
        return best;
    }

    public BlueprintManager.BlueprintNode nearestStronghold(int x, int z) {
        int centerX = generator.regionAt(x), centerZ = generator.regionAt(z);
        BlueprintManager.BlueprintNode best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int ring = 0; ring <= 64; ring++) {
            for (int dx = -ring; dx <= ring; dx++) for (int dz = -ring; dz <= ring; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                int rx = centerX + dx, rz = centerZ + dz;
                if (!generator.strongholdCandidate(rx, rz)) continue;
                for (BlueprintManager.BlueprintNode node : get(rx, rz).nodes) {
                    if (!node.type.contains("stronghold")) continue;
                    double distance = Math.hypot(node.getX() - x, node.getZ() - z);
                    if (distance < bestDistance) { best = node; bestDistance = distance; }
                }
            }
            if (best != null && ring * generator.size() - generator.size() > bestDistance) break;
        }
        return best;
    }

    private List<RegionData> regions(int minX, int maxX, int minZ, int maxZ) {
        List<RegionData> result = new ArrayList<>();
        for (int rx = generator.regionAt(minX); rx <= generator.regionAt(maxX); rx++)
            for (int rz = generator.regionAt(minZ); rz <= generator.regionAt(maxZ); rz++)
                result.add(get(rx, rz));
        return result;
    }

    @Override public void close() { workers.shutdown(); }
}
