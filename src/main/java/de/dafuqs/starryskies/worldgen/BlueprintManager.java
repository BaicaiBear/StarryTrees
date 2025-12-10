package de.dafuqs.starryskies.worldgen;

import com.google.gson.*;
import de.dafuqs.starryskies.StarrySkies;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.math.ChunkPos;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class BlueprintManager {

    private static BlueprintManager INSTANCE;

    // Maps keyed by Dimension ID (e.g., "starry_skies:overworld")
    private final Map<net.minecraft.util.Identifier, BlueprintData> blueprints = new HashMap<>();
    private final Map<net.minecraft.util.Identifier, KDTree> kdTrees = new HashMap<>();
    private final Map<net.minecraft.util.Identifier, Double> maxRadii = new HashMap<>();

    private final Map<net.minecraft.util.Identifier, BridgeData> bridges = new HashMap<>();
    private final Map<net.minecraft.util.Identifier, Map<Long, List<Edge>>> bridgeIndices = new HashMap<>();

    private BlueprintManager() {
        load();
    }

    public static BlueprintManager get() {
        if (INSTANCE == null) {
            INSTANCE = new BlueprintManager();
        }
        return INSTANCE;
    }

    public void load() {
        this.blueprints.clear();
        this.kdTrees.clear();
        this.maxRadii.clear();
        this.bridges.clear();
        this.bridgeIndices.clear();

        // 1. Load Overworld
        net.minecraft.util.Identifier overworldId = StarrySkies.id("overworld");
        loadBlueprint(overworldId, "starry_skies_blueprint.json");
        loadBridges(overworldId, "bridges_blueprint.json");

        // 2. Load Nether
        net.minecraft.util.Identifier netherId = StarrySkies.id("nether");
        loadBlueprint(netherId, "starry_skies_nether_blueprint.json");
        loadBridges(netherId, "bridges_nether_blueprint.json");
    }

    private void loadBlueprint(net.minecraft.util.Identifier id, String filename) {
        BlueprintData data = null;
        Double maxR = 300.0;

        try {
            Path configDir = FabricLoader.getInstance().getConfigDir().resolve("starrytrees");
            if (!Files.exists(configDir)) {
                Files.createDirectories(configDir);
            }
            Path path = configDir.resolve(filename);

            ensureFileExists(path, filename);

            if (Files.exists(path)) {
                try (Reader reader = Files.newBufferedReader(path)) {
                    data = new Gson().fromJson(reader, BlueprintData.class);
                    StarrySkies.LOGGER.info("Loaded blueprint for " + id + " from " + path);
                }
            } else {
                StarrySkies.LOGGER.warn("Blueprint config not found at " + path + " and could not be created.");
                data = new BlueprintData();
                data.nodes = new ArrayList<>();
                data.metadata = new Metadata();
            }
        } catch (Exception e) {
            StarrySkies.LOGGER.error("Failed to load blueprint " + filename + ": " + e.getMessage());
        }

        // Safety
        if (data == null) {
            data = new BlueprintData();
            data.nodes = new ArrayList<>();
            data.metadata = new Metadata();
        }
        if (data.nodes == null)
            data.nodes = new ArrayList<>();

        // Scan Max Radius
        if (!data.nodes.isEmpty()) {
            double r = 0;
            for (BlueprintNode node : data.nodes) {
                if (node.radius > r)
                    r = node.radius;
            }
            maxR = r + 50.0;
        }

        this.blueprints.put(id, data);
        this.maxRadii.put(id, maxR);

        // Build Spatial Index based on 2D nodes
        List<BlueprintNode> nodes = new ArrayList<>(data.nodes);
        this.kdTrees.put(id, new KDTree(nodes));
    }

    private void loadBridges(net.minecraft.util.Identifier id, String filename) {
        BridgeData data = null;
        try {
            Path configDir = FabricLoader.getInstance().getConfigDir().resolve("starrytrees");
            if (!Files.exists(configDir)) {
                Files.createDirectories(configDir);
            }
            Path path = configDir.resolve(filename);

            ensureFileExists(path, filename);

            if (Files.exists(path)) {
                try (Reader reader = Files.newBufferedReader(path)) {
                    data = new Gson().fromJson(reader, BridgeData.class);
                    StarrySkies.LOGGER.info("Loaded bridges for " + id + " from " + path);
                }
            } else {
                StarrySkies.LOGGER.warn("Bridge config not found at " + path + " and could not be created.");
                data = new BridgeData();
                data.edges = new ArrayList<>();
            }
        } catch (Exception e) {
            StarrySkies.LOGGER.error("Failed to load bridges " + filename + ": " + e.getMessage());
        }

        if (data == null) {
            data = new BridgeData();
            data.edges = new ArrayList<>();
        }
        if (data.edges == null)
            data.edges = new ArrayList<>();

        this.bridges.put(id, data);

        if (!data.edges.isEmpty()) {
            buildBridgeIndex(id, data);
        } else {
            this.bridgeIndices.put(id, new HashMap<>());
        }
    }

    private void ensureFileExists(Path path, String filename) {
        if (!Files.exists(path)) {
            try (java.io.InputStream in = BlueprintManager.class.getResourceAsStream("/blueprints/" + filename)) {
                if (in != null) {
                    Files.copy(in, path);
                    StarrySkies.LOGGER.info("Created default config for " + filename);
                } else {
                    StarrySkies.LOGGER
                            .warn("Default blueprint/bridge file not found in resources: /blueprints/" + filename);
                }
            } catch (IOException e) {
                StarrySkies.LOGGER.error("Failed to create default config for " + filename + ": " + e.getMessage());
            }
        }
    }

    private static final int BRIDGE_REGION_SIZE = 512;

    private void buildBridgeIndex(net.minecraft.util.Identifier id, BridgeData data) {
        Map<Long, List<Edge>> index = new HashMap<>();
        for (List<List<Integer>> rawEdge : data.edges) {
            Edge edge = new Edge(rawEdge);
            if (edge.size() < 2)
                continue;

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
    }

    public static class Edge {
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
}
