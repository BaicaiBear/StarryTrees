package de.dafuqs.starryskies.worldgen;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import de.dafuqs.starryskies.StarrySkies;
import net.minecraft.util.math.ChunkPos;

public class BlueprintManager {

    private static BlueprintManager INSTANCE;

    // Maps keyed by Dimension ID (e.g., "starry_skies:overworld")
    private final Map<net.minecraft.util.Identifier, BlueprintData> blueprints = new HashMap<>();
    private final Map<net.minecraft.util.Identifier, KDTree> kdTrees = new HashMap<>();
    private final Map<net.minecraft.util.Identifier, Double> maxRadii = new HashMap<>();

    private final Map<net.minecraft.util.Identifier, BridgeData> bridges = new HashMap<>();
    private final Map<net.minecraft.util.Identifier, Map<Long, List<Edge>>> bridgeIndices = new HashMap<>();

    private BlueprintManager() {
        // Lazy init
    }

    public static BlueprintManager get() {
        if (INSTANCE == null) {
            INSTANCE = new BlueprintManager();
        }
        return INSTANCE;
    }

    /**
     * Initializes the BlueprintManager for the given server instance.
     * Checks for world-specific blueprints; if missing, generates them using the
     * world seed.
     */
    public void initialize(net.minecraft.server.MinecraftServer server) {
        this.blueprints.clear();
        this.kdTrees.clear();
        this.maxRadii.clear();
        this.bridges.clear();
        this.bridgeIndices.clear();

        Path worldDir = server.getSavePath(net.minecraft.util.WorldSavePath.ROOT).resolve("starrytrees");
        try {
            if (!Files.exists(worldDir)) {
                Files.createDirectories(worldDir);
            }
        } catch (IOException e) {
            StarrySkies.LOGGER.error("Failed to create world config directory: " + e.getMessage());
            return;
        }

        long seed = server.getSaveProperties().getGeneratorOptions().getSeed();

        // 1. Overworld
        net.minecraft.util.Identifier overworldId = StarrySkies.id("overworld");
        loadOrGenerate(overworldId, worldDir, "overworld_blueprint.json", "overworld_bridges.json", seed);

        // 2. Nether
        net.minecraft.util.Identifier netherId = StarrySkies.id("nether");
        loadOrGenerate(netherId, worldDir, "nether_blueprint.json", "nether_bridges.json", seed);
    }

    private void loadOrGenerate(net.minecraft.util.Identifier id, Path dir, String bpMetaName, String bridgeName,
            long seed) {
        Path bpPath = dir.resolve(bpMetaName);
        Path bgPath = dir.resolve(bridgeName);

        if (!Files.exists(bpPath) || !Files.exists(bgPath)) {
            StarrySkies.LOGGER.info("Generating new blueprints for " + id + " with seed " + seed);
            // Generate
            de.dafuqs.starryskies.worldgen.generator.BlueprintGenerator.GenerationResult result = de.dafuqs.starryskies.worldgen.generator.BlueprintGenerator
                    .generate(seed, id);

            // Save
            saveJson(bpPath, result.blueprint());
            saveJson(bgPath, result.bridges());

            // Visualize
            String vizName = bpMetaName.replace(".json", ".png");
            BlueprintVisualizer.visualize(result.blueprint(), vizName, dir);

            // Load directly
            loadFromData(id, result.blueprint(), result.bridges());
        } else {
            StarrySkies.LOGGER.info("Loading existing blueprints for " + id + " from " + dir);
            loadFromFile(id, bpPath, bgPath);
        }
    }

    private void saveJson(Path path, Object data) {
        try (Writer writer = Files.newBufferedWriter(path)) {
            new GsonBuilder().setPrettyPrinting().create().toJson(data, writer);
        } catch (IOException e) {
            StarrySkies.LOGGER.error("Failed to save blueprint data to " + path + ": " + e.getMessage());
        }
    }

    private void loadFromData(net.minecraft.util.Identifier id, BlueprintData bpData, BridgeData bridgeData) {
        // Blueprint
        Double maxR = 300.0;
        if (bpData != null) {
            if (bpData.nodes == null)
                bpData.nodes = new ArrayList<>();
            if (!bpData.nodes.isEmpty()) {
                double r = 0;
                for (BlueprintNode node : bpData.nodes) {
                    if (node.radius > r)
                        r = node.radius;
                }
                maxR = r + 50.0;
            }
            this.blueprints.put(id, bpData);
            this.kdTrees.put(id, new KDTree(new ArrayList<>(bpData.nodes)));
        }
        this.maxRadii.put(id, maxR);

        // Bridges
        if (bridgeData != null) {
            if (bridgeData.edges == null)
                bridgeData.edges = new ArrayList<>();
            this.bridges.put(id, bridgeData);
            if (!bridgeData.edges.isEmpty()) {
                buildBridgeIndex(id, bridgeData);
            } else {
                this.bridgeIndices.put(id, new HashMap<>());
            }
        }
    }

    private void loadFromFile(net.minecraft.util.Identifier id, Path bpPath, Path bgPath) {
        BlueprintData bpData = null;
        BridgeData bridgeData = null;
        Gson gson = new Gson();

        try (Reader reader = Files.newBufferedReader(bpPath)) {
            bpData = gson.fromJson(reader, BlueprintData.class);
        } catch (Exception e) {
            StarrySkies.LOGGER.error("Failed to load blueprint from " + bpPath + ": " + e.getMessage());
        }

        try (Reader reader = Files.newBufferedReader(bgPath)) {
            bridgeData = gson.fromJson(reader, BridgeData.class);
        } catch (Exception e) {
            StarrySkies.LOGGER.error("Failed to load bridges from " + bgPath + ": " + e.getMessage());
        }

        loadFromData(id, bpData, bridgeData);
    }

    // Cleaned up unused methods

    private static final int BRIDGE_REGION_SIZE = 512;

    private void buildBridgeIndex(net.minecraft.util.Identifier id, BridgeData data) {
        Map<Long, List<Edge>> index = new HashMap<>();
        for (List<List<Integer>> rawEdge : data.edges) {
            Edge edge = new Edge(rawEdge);
            if (edge.size() < 2)
                continue;

            // Story
            if (data.story != null) {
                String key = getEdgeKey(edge.getStart()[0], edge.getStart()[1], edge.getStart()[2], edge.getEnd()[0],
                        edge.getEnd()[1], edge.getEnd()[2]);
                edge.story = data.story.get(key);
            }

            int minX = Math.min(edge.getStart()[0], edge.getEnd()[0]);
            int maxX = Math.max(edge.getStart()[0], edge.getEnd()[0]);
            int minZ = Math.min(edge.getStart()[2], edge.getEnd()[2]);
            int maxZ = Math.max(edge.getStart()[2], edge.getEnd()[2]);

            int minRegX = Math.floorDiv(minX, BRIDGE_REGION_SIZE);
            int maxRegX = Math.floorDiv(maxX, BRIDGE_REGION_SIZE);
            int minRegZ = Math.floorDiv(minZ, BRIDGE_REGION_SIZE);
            int maxRegZ = Math.floorDiv(maxZ, BRIDGE_REGION_SIZE);

            for (int rX = minRegX; rX <= maxRegX; rX++) {
                for (int rZ = minRegZ; rZ <= maxRegZ; rZ++) {
                    long key = ChunkPos.toLong(rX, rZ);
                    index.computeIfAbsent(key, k -> new ArrayList<>()).add(edge);
                }
            }
        }
        this.bridgeIndices.put(id, index);
    }

    // Accessors
    public BlueprintNode getNearestNode(net.minecraft.util.Identifier id, int x, int z) {
        KDTree tree = kdTrees.get(id);
        if (tree == null || tree.root == null)
            return null;
        return tree.nearest(x, z);
    }

    public BlueprintNode getNearestNode(net.minecraft.util.Identifier id, int x, int z,
            java.util.function.Predicate<String> typePredicate) {
        KDTree tree = kdTrees.get(id);
        if (tree == null || tree.root == null)
            return null;
        return tree.nearest(x, z, typePredicate);
    }

    public List<BlueprintNode> getSpheresInChunk(net.minecraft.util.Identifier id, ChunkPos chunkPos) {
        KDTree tree = kdTrees.get(id);
        Double maxR = maxRadii.getOrDefault(id, 300.0);
        if (tree == null)
            return Collections.emptyList();

        return tree.rangeSearch(chunkPos.getCenterX(), chunkPos.getCenterZ(), maxR);
    }

    public List<Edge> getBridgesInRegion(net.minecraft.util.Identifier id, int blockX, int blockZ) {
        Map<Long, List<Edge>> index = bridgeIndices.get(id);
        if (index == null)
            return Collections.emptyList();

        long key = ChunkPos.toLong(Math.floorDiv(blockX, BRIDGE_REGION_SIZE),
                Math.floorDiv(blockZ, BRIDGE_REGION_SIZE));
        return index.getOrDefault(key, Collections.emptyList());
    }

    public net.minecraft.util.math.BlockPos getNearestStoryPos(net.minecraft.util.Identifier id, int targetPart,
            net.minecraft.util.math.BlockPos center) {
        BridgeData data = bridges.get(id);
        if (data == null || data.story == null || data.story.isEmpty())
            return null;

        double bestDistSq = Double.MAX_VALUE;
        net.minecraft.util.math.BlockPos bestPos = null;

        for (Map.Entry<String, int[]> entry : data.story.entrySet()) {
            if (entry.getValue()[1] == targetPart) {
                // Parse key: "x1_y1_z1_x2_y2_z2"
                String[] parts = entry.getKey().split("_");
                if (parts.length == 6) {
                    try {
                        int x1 = Integer.parseInt(parts[0]);
                        int z1 = Integer.parseInt(parts[2]);
                        int x2 = Integer.parseInt(parts[3]);
                        int z2 = Integer.parseInt(parts[5]);

                        int mx = (x1 + x2) / 2;
                        int mz = (z1 + z2) / 2;

                        double d = (mx - center.getX()) * (mx - center.getX())
                                + (mz - center.getZ()) * (mz - center.getZ());
                        if (d < bestDistSq) {
                            bestDistSq = d;
                            bestPos = new net.minecraft.util.math.BlockPos(mx, 100, mz);
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        return bestPos;
    }

    // Data Classes
    public static class BlueprintData {
        public Metadata metadata;
        public List<BlueprintNode> nodes;
    }

    public static class Metadata {
        public long seed;
        public int num_nodes;
        public double radius_map;
    }

    public static class BlueprintNode {
        public int id;
        public double[] pos; // [x, y, z]
        public String biome;
        public String type;
        public double radius;
        public double[][] voronoi_region;

        public int getX() {
            return (int) pos[0];
        }

        public int getY() {
            return (int) pos[1];
        }

        public int getZ() {
            return (int) pos[2];
        }
    }

    // KD-Tree Implementation
    private static class KDTree {
        Node root;

        private static class Node {
            BlueprintNode data;
            Node left, right;

            Node(BlueprintNode data) {
                this.data = data;
            }
        }

        KDTree(List<BlueprintNode> nodes) {
            this.root = build(nodes, 0);
        }

        private Node build(List<BlueprintNode> nodes, int depth) {
            if (nodes.isEmpty())
                return null;
            int axis = depth % 2;
            nodes.sort((a, b) -> {
                if (axis == 0)
                    return Double.compare(a.pos[0], b.pos[0]);
                else
                    return Double.compare(a.pos[2], b.pos[2]);
            });
            int mid = nodes.size() / 2;
            Node node = new Node(nodes.get(mid));
            node.left = build(nodes.subList(0, mid), depth + 1);
            node.right = build(nodes.subList(mid + 1, nodes.size()), depth + 1);
            return node;
        }

        public BlueprintNode nearest(double x, double z) {
            return nearest(root, x, z, null, Double.MAX_VALUE, 0, null);
        }

        public BlueprintNode nearest(double x, double z, java.util.function.Predicate<String> typePredicate) {
            return nearest(root, x, z, null, Double.MAX_VALUE, 0, typePredicate);
        }

        private BlueprintNode nearest(Node node, double targetX, double targetZ, BlueprintNode best, double bestDistSq,
                int depth, java.util.function.Predicate<String> typePredicate) {
            if (node == null)
                return best;

            double d = distSq(node.data.pos[0], node.data.pos[2], targetX, targetZ);
            if (d < bestDistSq && (typePredicate == null || typePredicate.test(node.data.type))) {
                bestDistSq = d;
                best = node.data;
            }

            int axis = depth % 2;
            double diff = (axis == 0) ? targetX - node.data.pos[0] : targetZ - node.data.pos[2];
            Node near = diff < 0 ? node.left : node.right;
            Node far = diff < 0 ? node.right : node.left;

            best = nearest(near, targetX, targetZ, best, bestDistSq, depth + 1, typePredicate);

            double planeDistSq = diff * diff;
            if (best != null) {
                bestDistSq = distSq(best.pos[0], best.pos[2], targetX, targetZ);
            }
            if (planeDistSq < bestDistSq) {
                best = nearest(far, targetX, targetZ, best, bestDistSq, depth + 1, typePredicate);
            }
            return best;
        }

        private double distSq(double x1, double z1, double x2, double z2) {
            double dx = x1 - x2;
            double dz = z1 - z2;
            return dx * dx + dz * dz;
        }

        public List<BlueprintNode> rangeSearch(double x, double z, double radius) {
            List<BlueprintNode> result = new ArrayList<>();
            rangeSearch(root, x, z, radius * radius, result, 0);
            return result;
        }

        private void rangeSearch(Node node, double x, double z, double radiusSq, List<BlueprintNode> result,
                int depth) {
            if (node == null)
                return;
            double d = distSq(node.data.pos[0], node.data.pos[2], x, z);
            if (d <= radiusSq)
                result.add(node.data);

            int axis = depth % 2;
            double val = (axis == 0) ? node.data.pos[0] : node.data.pos[2];
            double target = (axis == 0) ? x : z;
            double radius = Math.sqrt(radiusSq);

            if (target - radius <= val)
                rangeSearch(node.left, x, z, radiusSq, result, depth + 1);
            if (target + radius >= val)
                rangeSearch(node.right, x, z, radiusSq, result, depth + 1);
        }
    }

    public static class BridgeData {
        public List<List<List<Integer>>> edges;
        public Map<String, int[]> story; // Key: "x1_y1_z1_x2_y2_z2", Value: [chapter, part]
    }

    public static class Edge {
        public int[] story;
        private final int[] start;
        private final int[] end;
        private final List<List<Integer>> raw;

        public Edge(List<List<Integer>> rawData) {
            this.raw = rawData;
            this.start = new int[] { rawData.get(0).get(0), rawData.get(0).get(1), rawData.get(0).get(2) };
            this.end = new int[] { rawData.get(1).get(0), rawData.get(1).get(1), rawData.get(1).get(2) };
        }

        public int[] getStart() {
            return start;
        }

        public int[] getEnd() {
            return end;
        }

        public int size() {
            return raw == null ? 0 : raw.size();
        }
    }

    public static String getEdgeKey(int x1, int y1, int z1, int x2, int y2, int z2) {
        if (x1 < x2 || (x1 == x2 && (y1 < y2 || (y1 == y2 && z1 < z2)))) {
            return x1 + "_" + y1 + "_" + z1 + "_" + x2 + "_" + y2 + "_" + z2;
        } else {
            return x2 + "_" + y2 + "_" + z2 + "_" + x1 + "_" + y1 + "_" + z1;
        }
    }
}
