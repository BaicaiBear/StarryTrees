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
    private BlueprintData data;
    private KDTree kdTree;
    private double maxSphereRadius = 300.0; // Default fallback

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
        this.data = null;
        Path blueprintPath = null;
        this.maxSphereRadius = 300.0;

        try {
            Path configDir = FabricLoader.getInstance().getConfigDir();
            blueprintPath = configDir.resolve("starry_skies_blueprint.json");

            if (Files.exists(blueprintPath)) {
                try (Reader reader = Files.newBufferedReader(blueprintPath)) {
                    this.data = new Gson().fromJson(reader, BlueprintData.class);
                    StarrySkies.LOGGER.info("Loaded blueprint from Fabric Config: " + blueprintPath);
                }
            } else {
                StarrySkies.LOGGER.warn("Blueprint config not found at " + blueprintPath + ". Generating blank file.");
                generateBlankBlueprint(blueprintPath);
            }
        } catch (Exception e) {
            StarrySkies.LOGGER.error("Failed to load blueprint from Fabric Config: " + e.getMessage());
        }

        // Finalize Safety Check
        if (this.data == null) {
            this.data = new BlueprintData();
            this.data.nodes = new ArrayList<>();
            this.data.metadata = new Metadata();
        }

        if (this.data.nodes == null) {
            this.data.nodes = new ArrayList<>();
        }

        // Scan for max radius
        if (!this.data.nodes.isEmpty()) {
            double maxR = 0;
            for (BlueprintNode node : this.data.nodes) {
                if (node.radius > maxR) {
                    maxR = node.radius;
                }
            }
            this.maxSphereRadius = maxR + 50.0; // Add padding for chunks
            StarrySkies.LOGGER.info("Blueprint scanning: Max Sphere Radius found = " + maxR
                    + ". Setting Search Radius to " + this.maxSphereRadius);
        }

        buildSpatialIndex();
        if (!this.data.nodes.isEmpty()) {
            StarrySkies.LOGGER.info("Blueprint loaded successfully. Nodes: " + this.data.nodes.size());
        }

        loadBridges();
    }

    private void generateBlankBlueprint(Path path) {
        this.data = new BlueprintData();
        this.data.nodes = new ArrayList<>();
        this.data.metadata = new Metadata();
        this.data.metadata.seed = 0;
        this.data.metadata.num_nodes = 0;
        this.data.metadata.radius_map = 0;

        try (Writer writer = Files.newBufferedWriter(path)) {
            new GsonBuilder().setPrettyPrinting().create().toJson(this.data, writer);
        } catch (IOException e) {
            StarrySkies.LOGGER.error("Failed to generate blank blueprint file: " + e.getMessage());
        }
    }

    private void buildSpatialIndex() {
        // Build 2D KD-Tree on X, Z
        if (this.data.nodes != null && !this.data.nodes.isEmpty()) {
            List<BlueprintNode> nodes = new ArrayList<>(this.data.nodes);
            this.kdTree = new KDTree(nodes);
        } else {
            this.kdTree = new KDTree(Collections.emptyList());
        }
    }

    public BlueprintNode getNearestNode(int x, int z) {
        if (kdTree == null || kdTree.root == null)
            return null;
        return kdTree.nearest(x, z);
    }

    public List<BlueprintNode> getSpheresInChunk(ChunkPos chunkPos) {
        if (kdTree == null)
            return Collections.emptyList();

        int chunkCenterX = chunkPos.getCenterX();
        int chunkCenterZ = chunkPos.getCenterZ();
        // Use dynamically calculated max radius
        return kdTree.rangeSearch(chunkCenterX, chunkCenterZ, this.maxSphereRadius);
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

    // Simple 2D KD-Tree
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

            int axis = depth % 2; // 0 for X, 1 for Z
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
            return nearest(root, x, z, root.data, Double.MAX_VALUE, 0);
        }

        private BlueprintNode nearest(Node node, double targetX, double targetZ, BlueprintNode best, double bestDistSq,
                int depth) {
            if (node == null)
                return best;

            double d = distSq(node.data.pos[0], node.data.pos[2], targetX, targetZ);
            if (d < bestDistSq) {
                bestDistSq = d;
                best = node.data;
            }

            int axis = depth % 2;
            double diff = (axis == 0) ? targetX - node.data.pos[0] : targetZ - node.data.pos[2];

            Node near = diff < 0 ? node.left : node.right;
            Node far = diff < 0 ? node.right : node.left;

            best = nearest(near, targetX, targetZ, best, bestDistSq, depth + 1);

            // Check if we need to search the other side
            double planeDistSq = diff * diff;
            d = distSq(best.pos[0], best.pos[2], targetX, targetZ);

            if (planeDistSq < d) {
                best = nearest(far, targetX, targetZ, best, d, depth + 1);
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
            if (d <= radiusSq) {
                result.add(node.data);
            }

            int axis = depth % 2;
            double val = (axis == 0) ? node.data.pos[0] : node.data.pos[2];
            double target = (axis == 0) ? x : z;
            double radius = Math.sqrt(radiusSq);

            if (target - radius <= val) {
                rangeSearch(node.left, x, z, radiusSq, result, depth + 1);
            }
            if (target + radius >= val) {
                rangeSearch(node.right, x, z, radiusSq, result, depth + 1);
            }
        }
    }

    // Bridge Support
    private BridgeData bridgeData;
    private Map<Long, List<Edge>> bridgeIndex;
    private static final int BRIDGE_REGION_SIZE = 512;

    private void loadBridges() {
        this.bridgeData = null;
        Path bridgePath = null;

        // 1. Try Config File (Fabric Recommended)
        try {
            Path configDir = FabricLoader.getInstance().getConfigDir();
            bridgePath = configDir.resolve("bridges_blueprint.json");

            if (Files.exists(bridgePath)) {
                try (Reader reader = Files.newBufferedReader(bridgePath)) {
                    this.bridgeData = new Gson().fromJson(reader, BridgeData.class);
                    StarrySkies.LOGGER.info("Loaded bridges from Fabric Config: " + bridgePath);
                }
            } else {
                StarrySkies.LOGGER.warn("Bridge config not found at " + bridgePath + ". Generating blank file.");
                generateBlankBridges(bridgePath);
            }
        } catch (Exception e) {
            StarrySkies.LOGGER.error("Failed to load bridges from Fabric Config: " + e.getMessage());
        }

        // Finalize Safety Check
        if (this.bridgeData == null) {
            this.bridgeData = new BridgeData();
            this.bridgeData.edges = new ArrayList<>();
        }
        if (this.bridgeData.edges == null) {
            this.bridgeData.edges = new ArrayList<>();
        }

        if (!this.bridgeData.edges.isEmpty()) {
            buildBridgeIndex();
            StarrySkies.LOGGER.info("Bridge index built. Edges: " + this.bridgeData.edges.size());
        } else {
            StarrySkies.LOGGER.info("Bridge data is empty (no edges). Skipping index build.");
            this.bridgeIndex = new HashMap<>(); // Empty map
        }
    }

    private void generateBlankBridges(Path path) {
        this.bridgeData = new BridgeData();
        this.bridgeData.edges = new ArrayList<>();

        try (Writer writer = Files.newBufferedWriter(path)) {
            new GsonBuilder().setPrettyPrinting().create().toJson(this.bridgeData, writer);
        } catch (IOException e) {
            StarrySkies.LOGGER.error("Failed to generate blank bridge file: " + e.getMessage());
        }
    }

    private void buildBridgeIndex() {
        this.bridgeIndex = new HashMap<>();
        for (List<List<Integer>> rawEdge : this.bridgeData.edges) {
            Edge edge = new Edge(rawEdge);
            if (edge.size() < 2)
                continue;
            // Get bounding box of the edge
            int minX = Math.min(edge.getStart()[0], edge.getEnd()[0]);
            int maxX = Math.max(edge.getStart()[0], edge.getEnd()[0]);
            int minZ = Math.min(edge.getStart()[2], edge.getEnd()[2]);
            int maxZ = Math.max(edge.getStart()[2], edge.getEnd()[2]);

            // Fix: Check floorDiv behavior for negative numbers if minX is negative
            // Math.floorDiv accounts for negative numbers correctly (rounds towards
            // negative infinity)
            int minRegX = Math.floorDiv(minX, BRIDGE_REGION_SIZE);
            int maxRegX = Math.floorDiv(maxX, BRIDGE_REGION_SIZE);
            int minRegZ = Math.floorDiv(minZ, BRIDGE_REGION_SIZE);
            int maxRegZ = Math.floorDiv(maxZ, BRIDGE_REGION_SIZE);

            for (int rX = minRegX; rX <= maxRegX; rX++) {
                for (int rZ = minRegZ; rZ <= maxRegZ; rZ++) {
                    long key = ChunkPos.toLong(rX, rZ);
                    this.bridgeIndex.computeIfAbsent(key, k -> new ArrayList<>()).add(edge);
                }
            }
        }
    }

    public List<Edge> getBridgesInRegion(int blockX, int blockZ) {
        if (bridgeIndex == null)
            return Collections.emptyList();
        long key = ChunkPos.toLong(Math.floorDiv(blockX, BRIDGE_REGION_SIZE),
                Math.floorDiv(blockZ, BRIDGE_REGION_SIZE));
        return bridgeIndex.getOrDefault(key, Collections.emptyList());
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
