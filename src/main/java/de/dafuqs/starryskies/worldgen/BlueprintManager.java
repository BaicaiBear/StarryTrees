package de.dafuqs.starryskies.worldgen;

import com.google.gson.*;
import de.dafuqs.starryskies.StarrySkies;
import net.minecraft.util.math.ChunkPos;

import java.util.*;

public class BlueprintManager {

    private static BlueprintManager INSTANCE;
    private BlueprintData data;
    private KDTree kdTree;

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

        // 1. Try Classpath
        try (java.io.InputStream stream = getClass()
                .getResourceAsStream("/data/starry_skies/starry_skies/starry_skies_blueprint.json")) {
            if (stream != null) {
                try (java.io.InputStreamReader reader = new java.io.InputStreamReader(stream)) {
                    this.data = new Gson().fromJson(reader, BlueprintData.class);
                }
            }
        } catch (Exception e) {
            StarrySkies.LOGGER.error("Failed to load blueprint from CLASSPATH: " + e.getMessage());
        }

        // 2. Try Reference File (Dev Env)
        if (this.data == null) {
            java.io.File refFile = new java.io.File("reference/starry_skies_blueprint.json");
            if (refFile.exists()) {
                try (java.io.FileReader reader = new java.io.FileReader(refFile)) {
                    this.data = new Gson().fromJson(reader, BlueprintData.class);
                } catch (Exception e) {
                    StarrySkies.LOGGER.error("Failed to load blueprint from REFERENCE FILE: " + e.getMessage());
                }
            }
        }

        // 3. Try Config File (Legacy)
        if (this.data == null) {
            java.io.File configFile = new java.io.File("run/config/starry_skies_blueprint.json");
            if (configFile.exists()) {
                try (java.io.FileReader reader = new java.io.FileReader(configFile)) {
                    this.data = new Gson().fromJson(reader, BlueprintData.class);
                } catch (Exception e) {
                    StarrySkies.LOGGER.error("Failed to load blueprint from CONFIG FILE: " + e.getMessage());
                }
            }
        }

        // Finalize
        if (this.data == null || this.data.nodes == null) {
            StarrySkies.LOGGER.error("CRITICAL: Failed to load blueprint from ANY source.");
            this.data = new BlueprintData();
            this.data.nodes = new ArrayList<>();
        } else {
            buildSpatialIndex();
            StarrySkies.LOGGER.info("Blueprint loaded successfully. Nodes: " + this.data.nodes.size());
            loadBridges();
        }
    }

    private void buildSpatialIndex() {
        // Build 2D KD-Tree on X, Z
        List<BlueprintNode> nodes = new ArrayList<>(this.data.nodes);
        this.kdTree = new KDTree(nodes);
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
        double searchRadius = 300.0;
        return kdTree.rangeSearch(chunkCenterX, chunkCenterZ, searchRadius);
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

        // 1. Try Classpath
        try (java.io.InputStream stream = getClass()
                .getResourceAsStream("/data/starry_skies/starry_skies/bridges_blueprint.json")) {
            if (stream != null) {
                try (java.io.InputStreamReader reader = new java.io.InputStreamReader(stream)) {
                    this.bridgeData = new Gson().fromJson(reader, BridgeData.class);
                }
            }
        } catch (Exception e) {
            StarrySkies.LOGGER.error("Failed to load bridges from CLASSPATH: " + e.getMessage());
        }

        // 2. Try Reference File
        if (this.bridgeData == null) {
            java.io.File refFile = new java.io.File("reference/bridges_blueprint.json");
            if (refFile.exists()) {
                try (java.io.FileReader reader = new java.io.FileReader(refFile)) {
                    this.bridgeData = new Gson().fromJson(reader, BridgeData.class);
                } catch (Exception e) {
                    StarrySkies.LOGGER.error("Failed to load bridges from REFERENCE FILE: " + e.getMessage());
                }
            }
        }

        // 3. Try Config File (Legacy / Run Dir)
        if (this.bridgeData == null) {
            java.io.File configFile = new java.io.File("run/config/bridges_blueprint.json");
            if (configFile.exists()) {
                try (java.io.FileReader reader = new java.io.FileReader(configFile)) {
                    this.bridgeData = new Gson().fromJson(reader, BridgeData.class);
                } catch (Exception e) {
                    StarrySkies.LOGGER.error("Failed to load bridges from CONFIG FILE: " + e.getMessage());
                }
            } else {
                // Try simple "config/bridges_blueprint.json" in case CWD is inside run
                java.io.File simpleConfig = new java.io.File("config/bridges_blueprint.json");
                if (simpleConfig.exists()) {
                    try (java.io.FileReader reader = new java.io.FileReader(simpleConfig)) {
                        this.bridgeData = new Gson().fromJson(reader, BridgeData.class);
                    } catch (Exception e) {
                        StarrySkies.LOGGER.error("Failed to load bridges from SIMPLE CONFIG FILE: " + e.getMessage());
                    }
                }
            }
        }

        if (this.bridgeData != null && this.bridgeData.edges != null) {
            buildBridgeIndex();
            StarrySkies.LOGGER.info("Bridge index built. Edges: " + this.bridgeData.edges.size());
        } else {
            StarrySkies.LOGGER.error("Failed to load any bridge data.");
            this.bridgeData = new BridgeData();
            this.bridgeData.edges = new ArrayList<>();
            this.bridgeIndex = new HashMap<>(); // Empty map
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
