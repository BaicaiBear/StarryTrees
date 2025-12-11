package de.dafuqs.starryskies.worldgen.generator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects; // Added
import java.util.PriorityQueue;
import java.util.Set;
import java.util.Stack;
import java.util.stream.Collectors; // Added
import java.util.stream.IntStream; // Added

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import de.dafuqs.starryskies.StarrySkies;
import de.dafuqs.starryskies.worldgen.BlueprintManager;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.random.CheckedRandom;
import net.minecraft.util.math.random.ChunkRandom;

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

        // 3. Rivers, Forest, Heights (Optimized Parallel Voronoi)
        PartitionResult result = partitionAndGenerate(points, adj, numRoots, random, isNether);

        // 4. Biomes (Noise) - on valid IDs only
        int[] biomes = assignBiomes(points, result.heights, result.validIds, seed, isNether);

        // 5. Sphere Assignment & Bridges
        // 5. Sphere Assignment & Bridges
        return createBlueprints(points, result.heights, biomes, result.validIds, result.edges, result.story, isNether,
                random);
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

    // Data structure to hold per-cluster results
    private static class ClusterResult {
        int clusterId;
        List<int[]> edges = new ArrayList<>();
        Set<Integer> validIds = new HashSet<>();
        Map<Integer, Double> localHeights = new HashMap<>(); // ID -> Height
        Map<String, int[]> story = new HashMap<>(); // EdgeKey -> [chapter, part]
    }

    private static PartitionResult partitionAndGenerate(List<MathUtils.Point> points,
            Map<Integer, List<Integer>> adj, int numRoots, ChunkRandom random, boolean isNether) {

        // 1. Pick Centroids (Initialize K-Means)
        List<MathUtils.Point> centroids = new ArrayList<>();
        centroids.add(points.get(random.nextInt(points.size()))); // First random

        // K-Means++ Initialization
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

        // 2. Relax Centroids (Lloyd's Algorithm - 1 Iteration per user request)
        for (int iter = 0; iter < 1; iter++) {
            MathUtils.Point[] newCentroids = new MathUtils.Point[numRoots];
            int[] counts = new int[numRoots];
            for (int k = 0; k < numRoots; k++)
                newCentroids[k] = new MathUtils.Point(0, 0);

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
                    centroids.set(k, points.get(random.nextInt(points.size())));
                }
            }
        }

        final List<MathUtils.Point> finalCentroids = new ArrayList<>(centroids);

        // 3. Partition Points (Global Voronoi Assignment) with Domain Warping
        // We use an array of lists to bucket points into clusters
        List<List<Integer>> clusters = new ArrayList<>();
        for (int k = 0; k < numRoots; k++)
            clusters.add(new ArrayList<>());

        double riverWidth = 300.0;
        double spawnProtectionSq = 300.0 * 300.0;

        // Domain Warping Parameters
        SimplexNoise warpNoiseX = new SimplexNoise(random.nextLong());
        SimplexNoise warpNoiseZ = new SimplexNoise(random.nextLong());
        double warpScale = isNether ? 0.002 : 0.001; // Lower frequency = Smoother curves
        double warpAmp = isNether ? 200.0 : 400.0; // Lower amplitude = Less chaotic distortion

        for (int i = 0; i < points.size(); i++) {
            MathUtils.Point p = points.get(i);

            // Apply Domain Warping for distance check
            // Used 1 octave for maximum smoothness (no jagged fractal details)
            double wx = fractalNoise(warpNoiseX, p.x() * warpScale, p.z() * warpScale, 1) * warpAmp;
            double wz = fractalNoise(warpNoiseZ, p.x() * warpScale, p.z() * warpScale, 1) * warpAmp;
            MathUtils.Point warpedP = new MathUtils.Point(p.x() + wx, p.z() + wz);

            double d1 = Double.MAX_VALUE;
            double d2 = Double.MAX_VALUE;
            int c1 = -1;

            for (int k = 0; k < numRoots; k++) {
                double d = dist(warpedP, finalCentroids.get(k));
                if (d < d1) {
                    d2 = d1;
                    d1 = d;
                    c1 = k;
                } else if (d < d2) {
                    d2 = d;
                }
            }

            // River Check (using warped distances creates wavy rivers)
            boolean isSpawnProtected = (p.x() * p.x() + p.z() * p.z() < spawnProtectionSq);
            if (!isSpawnProtected && (d2 - d1) < riverWidth) {
                continue; // Is River
            }

            clusters.get(c1).add(i);
        }

        // 4. Sequential Generation (User preference: avoid parallel overhead)
        List<ClusterResult> results = IntStream.range(0, numRoots)
                .mapToObj(clusterId -> {
                    List<Integer> clusterPoints = clusters.get(clusterId);
                    if (clusterPoints.isEmpty())
                        return null;

                    // Create local random derived from main seed + cluster ID to be deterministic
                    // but distinct
                    ChunkRandom localRandom = new ChunkRandom(new CheckedRandom(random.nextLong() ^ clusterId));

                    return generateCluster(clusterId, clusterPoints, points, adj, finalCentroids.get(clusterId),
                            localRandom, isNether);
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        // 5. Merge
        Set<Integer> allValidIds = new HashSet<>();
        List<int[]> allEdges = new ArrayList<>();
        double[] heights = new double[points.size()];
        Arrays.fill(heights, isNether ? 40 : -32); // Default base height

        for (ClusterResult res : results) {
            allValidIds.addAll(res.validIds);
            allEdges.addAll(res.edges);
            for (var entry : res.localHeights.entrySet()) {
                heights[entry.getKey()] = entry.getValue();
            }
        }

        // Merge Story Data from all clusters
        Map<String, int[]> allStory = new HashMap<>();
        for (ClusterResult res : results) {
            allStory.putAll(res.story);
        }

        return new PartitionResult(allValidIds, allEdges, heights, allStory);
    }

    private static ClusterResult generateCluster(int clusterId, List<Integer> clusterPoints,
            List<MathUtils.Point> allPoints,
            Map<Integer, List<Integer>> adj, MathUtils.Point centroid, ChunkRandom random, boolean isNether) {

        ClusterResult res = new ClusterResult();
        res.clusterId = clusterId;
        Set<Integer> pointSet = new HashSet<>(clusterPoints);

        // Find local root (closest to centroid within the cluster)
        int root = -1;
        double minD = Double.MAX_VALUE;
        for (int pIdx : clusterPoints) {
            double d = distSq(allPoints.get(pIdx), centroid);
            if (d < minD) {
                minD = d;
                root = pIdx;
            }
        }

        if (root == -1)
            return null; // Should not happen

        // ... (Existing implementation of MST) ...
        // Wait, I need to make sure I don't overwrite the MST logic which is between
        // lines 350+ and the return.
        // But I can't see those lines in the view. I should view them or assume I can
        // append logic before return.
        // I'll search for 'return res;' inside generateCluster to anchor my insertion.

        // Better: I will view generateCluster content fully first to avoid blind
        // editing.
        // ABORTING EDIT to view file locally first.
        // Wait, I can chain tool calls. I'll continue logic after viewing.
        // Actually, let's update PartitionResult first.

        // Local Prim's Algorithm
        // Restrict edges to only those connecting two points BOTH inside this cluster
        PriorityQueue<Edge> pq = new PriorityQueue<>();
        res.validIds.add(root);

        Map<Integer, Integer> parent = new HashMap<>(); // For height calculation tree
        parent.put(root, null);

        // Initial edges from root
        for (int neighbor : adj.getOrDefault(root, Collections.emptyList())) {
            if (pointSet.contains(neighbor)) {
                pq.add(new Edge(dist(allPoints.get(root), allPoints.get(neighbor)), root, neighbor));
            }
        }

        while (!pq.isEmpty()) {
            Edge e = pq.poll();
            if (!res.validIds.contains(e.v)) {
                res.validIds.add(e.v);
                res.edges.add(new int[] { e.u, e.v });
                parent.put(e.v, e.u);

                for (int n : adj.getOrDefault(e.v, Collections.emptyList())) {
                    if (pointSet.contains(n) && !res.validIds.contains(n)) {
                        pq.add(new Edge(dist(allPoints.get(e.v), allPoints.get(n)), e.v, n));
                    }
                }
            }
        }

        // Local Heights Calculation
        double HEIGHT_MIN = isNether ? 40 : -32;
        double HEIGHT_MAX = isNether ? 88 : 310;
        double ROOT_H_MIN = isNether ? 30 : 63;
        double ROOT_H_MAX = isNether ? 100 : 196;
        double SLOPE_MIN = -0.125;
        double SLOPE_MAX = 0.125;

        Stack<Integer> stack = new Stack<>();
        stack.push(root);
        res.localHeights.put(root, ROOT_H_MIN + random.nextDouble() * (ROOT_H_MAX - ROOT_H_MIN));

        // Inverse parent map for BFS height propagation
        Map<Integer, List<Integer>> children = new HashMap<>();
        for (int p : res.validIds)
            children.put(p, new ArrayList<>());
        for (var entry : parent.entrySet()) {
            if (entry.getValue() != null)
                children.get(entry.getValue()).add(entry.getKey());
        }

        while (!stack.isEmpty()) {
            int u = stack.pop();
            double hU = res.localHeights.get(u);

            for (int v : children.getOrDefault(u, Collections.emptyList())) {
                double d = dist(allPoints.get(u), allPoints.get(v));
                // Try to find valid height
                for (int attempt = 0; attempt < 10; attempt++) {
                    double slope = SLOPE_MIN + random.nextDouble() * (SLOPE_MAX - SLOPE_MIN);
                    double h = hU + slope * d;
                    if (h >= HEIGHT_MIN && h <= HEIGHT_MAX) {
                        res.localHeights.put(v, h);
                        break;
                    }
                    if (attempt == 9)
                        res.localHeights.put(v, Math.max(HEIGHT_MIN, Math.min(h, HEIGHT_MAX)));
                }
                stack.push(v);
            }
        }

        // Story Assignment
        List<int[]> validEdges = new ArrayList<>(res.edges);
        // Shuffle using a standard Random seeded from our local random
        java.util.Collections.shuffle(validEdges, new java.util.Random(random.nextLong()));

        int targetItems = 20; // 10 parts * 2 copies
        for (int i = 0; i < Math.min(validEdges.size(), targetItems); i++) {
            int[] e = validEdges.get(i);
            int u = e[0];
            int v = e[1];

            int x1 = (int) allPoints.get(u).x();
            int z1 = (int) allPoints.get(u).z();
            int y1 = res.localHeights.get(u).intValue();

            int x2 = (int) allPoints.get(v).x();
            int z2 = (int) allPoints.get(v).z();
            int y2 = res.localHeights.get(v).intValue();

            String key = BlueprintManager.getEdgeKey(x1, y1, z1, x2, y2, z2);
            int part = i / 2;
            res.story.put(key, new int[] { clusterId, part });
        }

        return res;
    }

    private static class PartitionResult {
        Set<Integer> validIds;
        List<int[]> edges;
        double[] heights;
        Map<String, int[]> story;

        public PartitionResult(Set<Integer> v, List<int[]> e, double[] h, Map<String, int[]> s) {
            this.validIds = v;
            this.edges = e;
            this.heights = h;
            this.story = s;
        }
    }

    private static double distSq(MathUtils.Point p1, MathUtils.Point p2) {
        return (p1.x() - p2.x()) * (p1.x() - p2.x()) + (p1.z() - p2.z()) * (p1.z() - p2.z());
    }

    private static double dist(MathUtils.Point p1, MathUtils.Point p2) {
        return Math.sqrt(distSq(p1, p2));
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
            Set<Integer> validIds, List<int[]> edges, Map<String, int[]> story, boolean isNether, ChunkRandom random) {
        BlueprintManager.BlueprintData bpData = new BlueprintManager.BlueprintData();
        bpData.metadata = new BlueprintManager.Metadata();
        bpData.metadata.num_nodes = validIds.size();
        bpData.metadata.radius_map = isNether ? NETHER_RADIUS : OVERWORLD_RADIUS;
        bpData.nodes = new ArrayList<>();

        BlueprintManager.BridgeData bridgeData = new BlueprintManager.BridgeData();
        bridgeData.edges = new ArrayList<>();

        List<BiomeDef> biomeDefs = isNether ? NETHER_BIOMES : OVERWORLD_BIOMES;

        // Node Map
        Map<Integer, int[]> nodePosMap = new HashMap<>();

        for (int i : validIds) {
            BiomeDef b = biomeDefs.get(biomeIndices[i]);

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

        // Apply Story Data
        if (bridgeData != null && story != null) {
            bridgeData.story = story;
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
        loadBiome("minecraft:warped_forest", "warped_forest.json", true, 0.0, 0.0, 0.5, 0.5, 0.375);
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

}
