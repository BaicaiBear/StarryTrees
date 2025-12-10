package de.dafuqs.starryskies.worldgen.generator;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import de.dafuqs.starryskies.StarrySkies;

import de.dafuqs.starryskies.worldgen.BlueprintManager;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.random.CheckedRandom;
import net.minecraft.util.math.random.ChunkRandom;
import net.minecraft.util.math.random.RandomSeed;
import net.minecraft.util.Identifier;

import java.util.*;

public class BlueprintGenerator {

    private static final int OVERWORLD_RADIUS = 10000;
    private static final int OVERWORLD_POINTS = 50000; // Restored to high count for quality
    private static final int OVERWORLD_MIN_DIST = 30;
    private static final int OVERWORLD_ROOTS = 10;

    private static final int NETHER_RADIUS = 2500;
    private static final int NETHER_POINTS = 10000; // Restored to high count
    private static final int NETHER_MIN_DIST = 40;
    private static final int NETHER_ROOTS = 1;

    public static GenerationResult generate(long worldSeed, Identifier dimId) {
        long seed = worldSeed + (dimId.getPath().contains("nether") ? 1 : 0);
        ChunkRandom random = new ChunkRandom(new CheckedRandom(seed));

        boolean isNether = dimId.getPath().contains("nether");
        int radius = isNether ? NETHER_RADIUS : OVERWORLD_RADIUS;
        int numPoints = isNether ? NETHER_POINTS : OVERWORLD_POINTS;
        int minDist = isNether ? NETHER_MIN_DIST : OVERWORLD_MIN_DIST;
        int numRoots = isNether ? NETHER_ROOTS : OVERWORLD_ROOTS;

        StarrySkies.LOGGER
                .info("Generating Starry Skies blueprints for " + dimId + " with " + numPoints + " points...");

        // 1. Geometry: Poisson Disk Sampling
        List<MathUtils.Point> points = poissonSampling(radius, minDist, numPoints, random);

        // 2. Topology: Delaunay Triangulation (Optimized via JTS)
        List<MathUtils.Triangle> triangles = MathUtils.triangulate(points, -radius, -radius, radius, radius);
        Map<Integer, List<Integer>> adj = buildAdjacency(points, triangles);

        // 3. Rivers & Roots
        RiverResult riverResult = processRivers(points, adj, triangles, numRoots, random);

        // 4. Forest Growth (BFS)
        ForestResult forestResult = buildForest(points, adj, riverResult.roots, riverResult.treeIds,
                riverResult.removed, random);

        // 5. Heights
        double[] heights = calcHeights(points, riverResult.roots, forestResult.parent, forestResult.validIds, random,
                isNether);

        // 6. Biomes (Noise)
        int[] biomes = assignBiomes(points, heights, forestResult.validIds, seed, isNether);

        // 7. Sphere Assignment & Bridges
        return createBlueprints(points, heights, biomes, forestResult.validIds, forestResult.edges, isNether, random);
    }

    private static List<MathUtils.Point> poissonSampling(double radius, double minDist, int n, ChunkRandom random) {
        List<MathUtils.Point> points = new ArrayList<>();
        double cellSize = minDist / Math.sqrt(2);
        Map<Long, List<MathUtils.Point>> grid = new HashMap<>();

        int gridWidth = (int) Math.ceil((2 * radius) / cellSize);

        int maxAttempts = n * 50;
        int attempts = 0;

        while (points.size() < n && attempts < maxAttempts) {
            double rRand = radius * Math.sqrt(random.nextDouble());
            double theta = random.nextDouble() * 2 * Math.PI;
            double x = rRand * Math.cos(theta);
            double z = rRand * Math.sin(theta);

            int gx = (int) ((x + radius) / cellSize);
            int gz = (int) ((z + radius) / cellSize);

            boolean valid = true;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    // Replaced undefined getSeedAt with local hash
                    long gridKey = asLong(gx + dx, gz + dz);
                    if (grid.containsKey(gridKey)) {
                        for (MathUtils.Point p : grid.get(gridKey)) {
                            double d2 = (x - p.x()) * (x - p.x()) + (z - p.z()) * (z - p.z());
                            if (d2 < minDist * minDist) {
                                valid = false;
                                break;
                            }
                        }
                    }
                    if (!valid)
                        break;
                }
                if (!valid)
                    break;
            }

            if (valid) {
                MathUtils.Point p = new MathUtils.Point(x, z);
                points.add(p);
                long key = asLong(gx, gz);
                grid.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
            }
            attempts++;
        }
        return points;
    }

    private static long asLong(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private static Map<Integer, List<Integer>> buildAdjacency(List<MathUtils.Point> points,
            List<MathUtils.Triangle> triangles) {
        Map<Integer, List<Integer>> adj = new HashMap<>();
        for (int i = 0; i < points.size(); i++)
            adj.put(i, new ArrayList<>());

        // Map points to indices for lookup? Or simplified assuming order preserved?
        // MathUtils.triangulate uses point objects.
        // We need efficient index lookup.
        Map<MathUtils.Point, Integer> pointToIndex = new HashMap<>();
        for (int i = 0; i < points.size(); i++)
            pointToIndex.put(points.get(i), i);

        for (MathUtils.Triangle t : triangles) {
            int i1 = pointToIndex.get(t.p1());
            int i2 = pointToIndex.get(t.p2());
            int i3 = pointToIndex.get(t.p3());

            addEdge(adj, i1, i2);
            addEdge(adj, i2, i3);
            addEdge(adj, i3, i1);
        }
        return adj;
    }

    private static void addEdge(Map<Integer, List<Integer>> adj, int u, int v) {
        List<Integer> listU = adj.get(u);
        if (!listU.contains(v))
            listU.add(v);
        List<Integer> listV = adj.get(v);
        if (!listV.contains(u))
            listV.add(u);
    }

    private static class Edge implements Comparable<Edge> {
        double weight;
        int u, v;

        public Edge(double weight, int u, int v) {
            this.weight = weight;
            this.u = u;
            this.v = v;
        }

        public int compareTo(Edge o) {
            return Double.compare(this.weight, o.weight);
        }
    }

    private static RiverResult processRivers(List<MathUtils.Point> points, Map<Integer, List<Integer>> adj,
            List<MathUtils.Triangle> triangles, int numRoots, ChunkRandom random) {
        // 1. Pick Centroids (K-Means++)
        List<MathUtils.Point> centroids = new ArrayList<>();
        // Pick random first center
        centroids.add(points.get(random.nextInt(points.size())));

        for (int k = 1; k < numRoots; k++) {
            double[] minDistSq = new double[points.size()];
            Arrays.fill(minDistSq, Double.MAX_VALUE);
            double totalDistSq = 0;

            for (int i = 0; i < points.size(); i++) {
                MathUtils.Point p = points.get(i);
                for (MathUtils.Point c : centroids) {
                    double d2 = distSq(p, c);
                    if (d2 < minDistSq[i])
                        minDistSq[i] = d2;
                }
                totalDistSq += minDistSq[i];
            }

            double target = random.nextDouble() * totalDistSq;
            double current = 0;
            for (int i = 0; i < points.size(); i++) {
                current += minDistSq[i];
                if (current >= target) {
                    centroids.add(points.get(i));
                    break;
                }
            }
        }

        // Relax centroids (Lloyd's algorithm - simplified 5 iters)
        for (int iter = 0; iter < 5; iter++) {
            MathUtils.Point[] newCentroids = new MathUtils.Point[numRoots];
            int[] counts = new int[numRoots];
            for (int k = 0; k < numRoots; k++)
                newCentroids[k] = new MathUtils.Point(0, 0); // Accumulator

            for (MathUtils.Point p : points) {
                int nearest = 0;
                double minD = Double.MAX_VALUE;
                for (int k = 0; k < numRoots; k++) {
                    double d = distSq(p, centroids.get(k));
                    if (d < minD) {
                        minD = d;
                        nearest = k;
                    }
                }
                newCentroids[nearest] = new MathUtils.Point(newCentroids[nearest].x() + p.x(),
                        newCentroids[nearest].z() + p.z());
                counts[nearest]++;
            }

            for (int k = 0; k < numRoots; k++) {
                if (counts[k] > 0) {
                    centroids.set(k,
                            new MathUtils.Point(newCentroids[k].x() / counts[k], newCentroids[k].z() / counts[k]));
                } else {
                    centroids.set(k, points.get(random.nextInt(points.size()))); // Respawn if empty
                }
            }
        }

        // Find actual root nodes closest to centroids
        List<Integer> roots = new ArrayList<>();
        for (MathUtils.Point c : centroids) {
            int nearest = -1;
            double minD = Double.MAX_VALUE;
            for (int i = 0; i < points.size(); i++) {
                double d = distSq(points.get(i), c);
                if (d < minD) {
                    minD = d;
                    nearest = i;
                }
            }
            roots.add(nearest);
        }

        // Multi-Source Prim's
        int[] treeIds = new int[points.size()];
        Arrays.fill(treeIds, -1);
        PriorityQueue<Edge> pq = new PriorityQueue<>();

        for (int r : roots) {
            treeIds[r] = r;
            for (int v : adj.getOrDefault(r, Collections.emptyList())) {
                pq.add(new Edge(dist(points.get(r), points.get(v)), r, v));
            }
        }

        while (!pq.isEmpty()) {
            Edge e = pq.poll();
            if (treeIds[e.v] == -1) {
                treeIds[e.v] = treeIds[e.u]; // Propagate ID
                for (int n : adj.getOrDefault(e.v, Collections.emptyList())) {
                    if (treeIds[n] == -1) {
                        pq.add(new Edge(dist(points.get(e.v), points.get(n)), e.v, n));
                    }
                }
            }
        }

        // Identify Boundaries (Rivers)
        Set<String> boundaries = new HashSet<>();
        for (int u = 0; u < points.size(); u++) {
            for (int v : adj.getOrDefault(u, Collections.emptyList())) {
                if (treeIds[u] != -1 && treeIds[v] != -1 && treeIds[u] != treeIds[v]) {
                    int min = Math.min(u, v), max = Math.max(u, v);
                    boundaries.add(min + "-" + max);
                }
            }
        }

        Set<Integer> removed = new HashSet<>();
        // Optimize: Use Spatial Grid for River Width Check
        // Grid size = river width (200)
        double riverWidth = 200.0;
        double riverWidthSq = riverWidth * riverWidth;
        Map<Long, List<MathUtils.Point>> boundaryGrid = new HashMap<>();
        double cellSize = riverWidth;

        for (String key : boundaries) {
            String[] parts = key.split("-");
            int u = Integer.parseInt(parts[0]);
            int v = Integer.parseInt(parts[1]);
            MathUtils.Point p1 = points.get(u);
            MathUtils.Point p2 = points.get(v);
            MathUtils.Point mid = new MathUtils.Point((p1.x() + p2.x()) / 2, (p1.z() + p2.z()) / 2);

            int gx = (int) Math.floor(mid.x() / cellSize);
            int gz = (int) Math.floor(mid.z() / cellSize);
            long bk = asLong(gx, gz);
            boundaryGrid.computeIfAbsent(bk, k -> new ArrayList<>()).add(mid);
        }

        for (int i = 0; i < points.size(); i++) {
            MathUtils.Point p = points.get(i);
            int gx = (int) Math.floor(p.x() / cellSize);
            int gz = (int) Math.floor(p.z() / cellSize);

            boolean isRemoved = false;
            // Check 3x3 neighbor grids
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    long bk = asLong(gx + dx, gz + dz);
                    if (boundaryGrid.containsKey(bk)) {
                        for (MathUtils.Point b : boundaryGrid.get(bk)) {
                            if (distSq(p, b) < riverWidthSq) {
                                removed.add(i);
                                isRemoved = true;
                                break;
                            }
                        }
                    }
                    if (isRemoved)
                        break;
                }
                if (isRemoved)
                    break;
            }
        }

        // Roots mapping (Old Root -> New Root closest to it if removed)
        Map<Integer, Integer> finalRoots = new HashMap<>();
        for (int r : roots) {
            if (removed.contains(r)) {
                // Determine which cluster this belonged to
                int clusterId = treeIds[r];
                // Find nearest surviving node in same cluster
                int best = -1;
                double minD = Double.MAX_VALUE;
                for (int i = 0; i < points.size(); i++) {
                    if (treeIds[i] == clusterId && !removed.contains(i)) {
                        double d = distSq(points.get(r), points.get(i));
                        if (d < minD) {
                            minD = d;
                            best = i;
                        }
                    }
                }
                if (best != -1)
                    finalRoots.put(r, best);
            } else {
                finalRoots.put(r, r);
            }
        }

        return new RiverResult(finalRoots, treeIds, removed);
    }

    private static ForestResult buildForest(List<MathUtils.Point> points, Map<Integer, List<Integer>> adj,
            Map<Integer, Integer> roots, int[] oldIds, Set<Integer> removed, ChunkRandom random) {
        List<int[]> edges = new ArrayList<>();
        Map<Integer, Integer> parent = new HashMap<>();
        Set<Integer> validIds = new HashSet<>();
        int[] newIds = new int[points.size()];
        Arrays.fill(newIds, -1);

        for (Map.Entry<Integer, Integer> entry : roots.entrySet()) {
            int oldR = entry.getKey(); // Original root ID (used as cluster ID)
            int newR = entry.getValue(); // Actual point index for the root

            // Per-component Prim's algorithm (or BFS/Dijkstra for shortest path tree)
            PriorityQueue<Edge> pq = new PriorityQueue<>();
            newIds[newR] = oldR; // Mark this point as belonging to this forest component
            validIds.add(newR);
            parent.put(newR, null); // Root has no parent

            for (int v : adj.getOrDefault(newR, Collections.emptyList())) {
                // Only consider neighbors that are not removed and belong to the same original
                // cluster
                if (!removed.contains(v) && oldIds[v] == oldR) {
                    pq.add(new Edge(dist(points.get(newR), points.get(v)), newR, v));
                }
            }

            while (!pq.isEmpty()) {
                Edge e = pq.poll();
                // If the destination node 'v' has not been visited yet for this forest
                // component
                if (newIds[e.v] == -1) {
                    newIds[e.v] = oldR; // Assign it to this component
                    validIds.add(e.v);
                    edges.add(new int[] { e.u, e.v }); // Add edge to the forest
                    parent.put(e.v, e.u); // Set parent

                    // Add neighbors of 'v' to the priority queue
                    for (int n : adj.getOrDefault(e.v, Collections.emptyList())) {
                        // Only consider neighbors that are not removed, not yet visited in this
                        // component,
                        // and belong to the same original cluster
                        if (!removed.contains(n) && newIds[n] == -1 && oldIds[n] == oldR) {
                            pq.add(new Edge(dist(points.get(e.v), points.get(n)), e.v, n));
                        }
                    }
                }
            }
        }

        return new ForestResult(edges, parent, validIds);
    }

    private static double distSq(MathUtils.Point p1, MathUtils.Point p2) {
        return (p1.x() - p2.x()) * (p1.x() - p2.x()) + (p1.z() - p2.z()) * (p1.z() - p2.z());
    }

    private static double dist(MathUtils.Point p1, MathUtils.Point p2) {
        return Math.sqrt(distSq(p1, p2));
    }

    private static double[] calcHeights(List<MathUtils.Point> points, Map<Integer, Integer> roots,
            Map<Integer, Integer> parent, Set<Integer> validIds, ChunkRandom random, boolean isNether) {

        double HEIGHT_MIN = isNether ? 40 : -32;
        double HEIGHT_MAX = isNether ? 88 : 310;
        double ROOT_H_MIN = isNether ? 30 : 63;
        double ROOT_H_MAX = isNether ? 100 : 196;
        double SLOPE_MIN = -0.125;
        double SLOPE_MAX = 0.125;

        double[] heights = new double[points.size()];
        Arrays.fill(heights, HEIGHT_MIN - (isNether ? 5.0 : 10.0));

        // Inverse parent map (children)
        Map<Integer, List<Integer>> children = new HashMap<>();
        for (int i = 0; i < points.size(); i++)
            children.put(i, new ArrayList<>());
        for (Map.Entry<Integer, Integer> entry : parent.entrySet()) {
            if (entry.getValue() != null) {
                children.get(entry.getValue()).add(entry.getKey());
            }
        }

        Stack<Integer> stack = new Stack<>();
        stack.addAll(roots.values());

        for (int r : stack) {
            heights[r] = ROOT_H_MIN + random.nextDouble() * (ROOT_H_MAX - ROOT_H_MIN);
        }

        while (!stack.isEmpty()) {
            int u = stack.pop();
            for (int v : children.get(u)) {
                if (!validIds.contains(v))
                    continue;
                double dist = dist(points.get(u), points.get(v));
                // Try to find valid height
                for (int attempt = 0; attempt < 10; attempt++) {
                    double slope = SLOPE_MIN + random.nextDouble() * (SLOPE_MAX - SLOPE_MIN);
                    double h = heights[u] + slope * dist;
                    if (h >= HEIGHT_MIN && h <= HEIGHT_MAX) {
                        heights[v] = h;
                        break;
                    }
                    if (attempt == 9)
                        heights[v] = Math.max(HEIGHT_MIN, Math.min(h, HEIGHT_MAX));
                }
                stack.push(v);
            }
        }
        return heights;
    }

    private static int[] assignBiomes(List<MathUtils.Point> points, double[] heights, Set<Integer> validIds, long seed,
            boolean isNether) {
        int[] biomeIndices = new int[points.size()];

        // Simple Perlin Noise for Temp/Humidity
        // We can use a lightweight implementation since we just need 2D noise
        SimplexNoise noiseTemp = new SimplexNoise(seed + 101);
        SimplexNoise noiseHum = new SimplexNoise(seed + 202);

        double NOISE_SCALE = isNether ? 2.0 : 4.0;
        double RADIUS = isNether ? NETHER_RADIUS : OVERWORLD_RADIUS; // Assuming these constants are defined elsewhere

        List<BiomeDef> biomes = isNether ? NETHER_BIOMES : OVERWORLD_BIOMES;

        for (int i : validIds) {
            MathUtils.Point p = points.get(i);

            double normX = (p.x() + RADIUS) / (2 * RADIUS) * NOISE_SCALE;
            double normY = (p.z() + RADIUS) / (2 * RADIUS) * NOISE_SCALE;

            double rawTemp = fractalNoise(noiseTemp, normX, normY, 2);
            double rawHum = fractalNoise(noiseHum, normX + 12.0, normY - 12.0, 3);

            double temp, hum;

            if (isNether) {
                // Map [-1, 1] to [-2.22, 2.22] approx range logic from python
                // Actually Python uses fractal_noise which returns ~ -1 to 1?
                // Python: interp(raw, [-1, 1], [-2.22, 2.22])
                temp = map(rawTemp, -1, 1, -2.22, 2.22);
                hum = map(rawHum, -1, 1, -1.69, 1.69);

                // Nether Biome Logic: Distance to target points
                double minMetric = Double.MAX_VALUE;
                int bestBiome = 0;

                for (int b = 0; b < biomes.size(); b++) {
                    BiomeDef bd = biomes.get(b);
                    // dist_lower = max(0, min - val)
                    // dist_upper = max(0, val - max)
                    double dt = Math.max(0, bd.tMin - temp) + Math.max(0, temp - bd.tMax);
                    double dh = Math.max(0, bd.hMin - hum) + Math.max(0, hum - bd.hMax);

                    double metric = (bd.offset * bd.offset) + (dt * dt + dh * dh);
                    if (metric < minMetric) {
                        minMetric = metric;
                        bestBiome = b;
                    }
                }
                biomeIndices[i] = bestBiome;

            } else {
                // Overworld logic
                temp = map(rawTemp, -0.6, 0.6, -10, 40);
                double humidity = map(rawHum, -0.6, 0.6, 0, 100);
                double height = heights[i];

                double minScore = Double.MAX_VALUE;
                int bestBiome = 0;

                for (int b = 0; b < biomes.size(); b++) {
                    BiomeDef bd = biomes.get(b);
                    double dh = Math.max(0, bd.hMin - height) + Math.max(0, height - bd.hMax); // Height
                    double dt = Math.max(0, bd.tMin - temp) + Math.max(0, temp - bd.tMax); // Temp
                    double dw = Math.max(0, bd.wMin - humidity) + Math.max(0, humidity - bd.wMax); // Wetness/Humidity

                    double score = dh * dh + dt * dt + dw * dw;
                    if (score < minScore) {
                        minScore = score;
                        bestBiome = b;
                    }
                }
                biomeIndices[i] = bestBiome;
            }
        }
        return biomeIndices;
    }

    private static GenerationResult createBlueprints(List<MathUtils.Point> points, double[] heights, int[] biomeIndices,
            Set<Integer> validIds, List<int[]> edges, boolean isNether, ChunkRandom random) {
        BlueprintManager.BlueprintData bpData = new BlueprintManager.BlueprintData();
        bpData.metadata = new BlueprintManager.Metadata();
        bpData.metadata.num_nodes = validIds.size();
        bpData.metadata.radius_map = isNether ? NETHER_RADIUS : OVERWORLD_RADIUS; // Assuming these constants are
                                                                                  // defined elsewhere
        bpData.nodes = new ArrayList<>();

        BlueprintManager.BridgeData bridgeData = new BlueprintManager.BridgeData();
        bridgeData.edges = new ArrayList<>();

        List<BiomeDef> biomes = isNether ? NETHER_BIOMES : OVERWORLD_BIOMES;

        // Node Map
        Map<Integer, int[]> nodePosMap = new HashMap<>();

        for (int i : validIds) {
            BiomeDef b = biomes.get(biomeIndices[i]);

            // Pick Sphere Type
            // Weighted random choice
            String typeId = "minecraft:air";
            int radius = 5;

            if (!b.configs.isEmpty()) {
                double totalWeight = 0;
                for (SphereConfig sc : b.configs)
                    totalWeight += sc.weight;

                double r = random.nextDouble() * totalWeight;
                for (SphereConfig sc : b.configs) {
                    r -= sc.weight;
                    if (r <= 0) {
                        typeId = sc.id;
                        radius = sc.minSize + random.nextInt(sc.maxSize - sc.minSize + 1);
                        break;
                    }
                }
            }

            BlueprintManager.BlueprintNode node = new BlueprintManager.BlueprintNode();
            node.id = i;
            node.biome = b.name; // This should be string ID like "minecraft:forest"
            node.type = typeId;
            node.radius = radius;

            // Pos: x, y, z
            // Python: y = height - radius (for center?)
            // Wait, Python: int(np.ceil(heights[i]) - np.ceil(radii[i]))
            // So height is top of sphere?
            node.pos = new double[] {
                    points.get(i).x(),
                    heights[i] - radius,
                    points.get(i).z()
            };

            bpData.nodes.add(node);

            nodePosMap.put(i, new int[] { (int) node.pos[0], (int) heights[i], (int) node.pos[2] }); // Storing top Y
                                                                                                     // for bridge
                                                                                                     // check? Python
                                                                                                     // stores height
        }

        // Bridges
        for (int[] e : edges) {
            if (nodePosMap.containsKey(e[0]) && nodePosMap.containsKey(e[1])) {
                List<List<Integer>> bridge = new ArrayList<>();
                int[] p1 = nodePosMap.get(e[0]);
                int[] p2 = nodePosMap.get(e[1]);
                // Store as list of lists
                List<Integer> pt1 = List.of(p1[0], p1[1], p1[2]);
                List<Integer> pt2 = List.of(p2[0], p2[1], p2[2]);
                bridge.add(pt1);
                bridge.add(pt2);
                bridgeData.edges.add(bridge);
            }
        }

        return new GenerationResult(bpData, bridgeData);
    }

    // --- Helpers ---

    private static double map(double val, double min1, double max1, double min2, double max2) {
        return min2 + (max2 - min2) * ((val - min1) / (max1 - min1));
    }

    private static double fractalNoise(SimplexNoise noise, double x, double z, int octaves) {
        double total = 0;
        double freq = 1;
        double amp = 1;
        double max = 0;
        for (int i = 0; i < octaves; i++) {
            total += noise.eval(x * freq, z * freq) * amp;
            max += amp;
            amp *= 0.5;
            freq *= 2;
        }
        return total / max;
    }

    // --- Data Definitions ---

    private record SphereConfig(String id, int minSize, int maxSize, double weight) {
    }

    private static class BiomeDef {
        String name;
        double hMin, hMax, tMin, tMax, wMin, wMax, offset;
        List<SphereConfig> configs = new ArrayList<>();

        public BiomeDef(String name, double hMin, double hMax, double tMin, double tMax, double wMin, double wMax) {
            this(name, hMin, hMax, tMin, tMax, wMin, wMax, 0);
        }

        public BiomeDef(String name, double tMin, double tMax, double hMin, double hMax, double offset) {
            // Nether Constructor
            this(name, hMin, hMax, tMin, tMax, 0, 0, offset);
        }

        public BiomeDef(String name, double hMin, double hMax, double tMin, double tMax, double wMin, double wMax,
                double offset) {
            this.name = name;
            this.hMin = hMin;
            this.hMax = hMax;
            this.tMin = tMin;
            this.tMax = tMax;
            this.wMin = wMin;
            this.wMax = wMax;
            this.offset = offset;
        }
    }

    private static final List<BiomeDef> OVERWORLD_BIOMES = new ArrayList<>();
    private static final List<BiomeDef> NETHER_BIOMES = new ArrayList<>();

    // --- Dynamic Config Loading ---

    static {
        loadBiomeConfigs();
    }

    private static void loadBiomeConfigs() {
        // OVERWORLD
        loadBiome("minecraft:warm_ocean", "warm_ocean.json", false, 40, 200, 5, 30, 60, 100);
        loadBiome("minecraft:forest", "forest.json", false, 100, 200, 10, 30, 40, 80);
        loadBiome("minecraft:deep_frozen_ocean", "deep_frozen_ocean.json", false, 40, 80, -10, 5, 60, 100);
        loadBiome("minecraft:swamp", "swamp.json", false, 50, 70, 10, 25, 50, 80);
        loadBiome("minecraft:snowy_taiga", "snowy_taiga.json", false, 80, 200, -10, 10, 30, 100);
        loadBiome("minecraft:lush_caves", "lush_caves.json", false, -64, 40, -10, 35, 10, 100);
        loadBiome("minecraft:desert", "desert.json", false, 50, 320, 20, 35, 0, 40);
        loadBiome("minecraft:stony_peaks", "stony_peaks.json", false, 250, 320, 10, 30, 40, 100);
        loadBiome("minecraft:frozen_peaks", "frozen_peaks.json", false, 250, 320, -10, 10, 30, 100);

        // NETHER
        loadBiome("minecraft:basalt_deltas", "basalt_deltas.json", true, -0.5, -0.5, 0.0, 0.0, 0.175);
        loadBiome("minecraft:crimson_forest", "crimson_forest.json", true, 0.4, 0.4, 0.0, 0.0, 0.0);
        loadBiome("minecraft:nether_wastes", "nether_wastes.json", true, 0.0, 0.0, 0.0, 0.0, 0.0);
        loadBiome("minecraft:soul_sand_valley", "soul_sand_valley.json", true, 0.0, 0.0, -0.5, -0.5, 0.0);
        loadBiome("minecraft:warped_forest", "warped_forest.json", true, 0.0, 0.0, 0.5, 0.5, 0.175);
    }

    private static void loadBiome(String name, String filename, boolean isNether, double... params) {
        List<SphereConfig> configs = new ArrayList<>();
        try (java.io.InputStream in = BlueprintGenerator.class
                .getResourceAsStream("/assets/starry_skies/biome_weights/" + filename)) {
            if (in != null) {
                JsonArray json = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in))
                        .getAsJsonArray();
                for (JsonElement e : json) {
                    JsonObject obj = e.getAsJsonObject();
                    String id = obj.get("id").getAsString();
                    // Fix IDs: Add prefix if missing
                    if (!id.startsWith("overworld/") && !id.startsWith("nether/") && !id.startsWith("minecraft:")) {
                        id = (isNether ? "nether/" : "overworld/") + id;
                    }
                    if (!id.contains(":"))
                        id = "starry_skies:" + id;

                    int min = obj.get("size").getAsJsonArray().get(0).getAsInt();
                    int max = obj.get("size").getAsJsonArray().get(1).getAsInt();
                    double w = obj.get("normalized_weight").getAsDouble();
                    configs.add(new SphereConfig(id, min, max, w));
                }
                // Normalize weights to sum to 1.0 (just in case)
                double total = configs.stream().mapToDouble(c -> c.weight).sum();
                if (total > 0) {
                    List<SphereConfig> norm = new ArrayList<>();
                    for (SphereConfig c : configs)
                        norm.add(new SphereConfig(c.id, c.minSize, c.maxSize, c.weight / total));
                    configs = norm;
                }
            } else {
                StarrySkies.LOGGER.error("Could not find biome config: " + filename);
            }
        } catch (Exception e) {
            StarrySkies.LOGGER.error("Failed to load biome config " + filename + ": " + e.getMessage());
            e.printStackTrace();
        }

        if (isNether) {
            // params: t1, t2, h1, h2, off
            double t1 = params[0], t2 = params[1], h1 = params[2], h2 = params[3], off = params[4];
            BiomeDef b = new BiomeDef(name, t1, t2, h1, h2, off);
            b.configs = configs;
            NETHER_BIOMES.add(b);
        } else {
            // params: h1, h2, t1, t2, w1, w2
            double h1 = params[0], h2 = params[1], t1 = params[2], t2 = params[3], w1 = params[4], w2 = params[5];
            BiomeDef b = new BiomeDef(name, h1, h2, t1, t2, w1, w2);
            b.configs = configs;
            OVERWORLD_BIOMES.add(b);
        }
    }

    // --- Simplex Noise Class (Self-contained) ---
    private static class SimplexNoise {
        // Simple implementation or use external
        // For brevity, using a placeholder linear congruential generator + simple hash
        // for checking
        // Actually, let's use a very simple noise function for now or copy a small one.
        // Or reuse Random with coordinate seeding (Value Noise) which is easier to
        // implement.
        // Python used Perlin. Value noise is close enough for biomes.
        private long seed;

        public SimplexNoise(long seed) {
            this.seed = seed;
        }

        public double eval(double x, double y) {
            // Value noise with cubic interpolation
            int xi = (int) Math.floor(x);
            int yi = (int) Math.floor(y);
            double xf = x - xi;
            double yf = y - yi;

            double v00 = hash(xi, yi);
            double v10 = hash(xi + 1, yi);
            double v01 = hash(xi, yi + 1);
            double v11 = hash(xi + 1, yi + 1);

            double u = fade(xf);
            double v = fade(yf);

            double lerpX0 = lerp(v00, v10, u);
            double lerpX1 = lerp(v01, v11, u);
            return lerp(lerpX0, lerpX1, v);
        }

        private double hash(int x, int y) {
            long h = seed + x * 374761393L + y * 668265263L;
            h = (h ^ (h >> 13)) * 1274126177L;
            return ((h & 0xFFFF) / 65535.0) * 2.0 - 1.0; // Map to [-1, 1]
        }

        private double fade(double t) {
            return t * t * t * (t * (t * 6 - 15) + 10);
        }

        private double lerp(double a, double b, double t) {
            return a + t * (b - a);
        }
    }

    public record GenerationResult(BlueprintManager.BlueprintData blueprint, BlueprintManager.BridgeData bridges) {
    }

    private record RiverResult(Map<Integer, Integer> roots, int[] treeIds, Set<Integer> removed) {
    }

    private record ForestResult(List<int[]> edges, Map<Integer, Integer> parent, Set<Integer> validIds) {
    }
}
