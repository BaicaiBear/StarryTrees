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
        System.out.println("StarrySkies: BlueprintManager loading started...");
        this.data = null;

        // 1. Try Classpath
        try (java.io.InputStream stream = getClass()
                .getResourceAsStream("/data/starry_skies/starry_skies/starry_skies_blueprint.json")) {
            if (stream != null) {
                try (java.io.InputStreamReader reader = new java.io.InputStreamReader(stream)) {
                    this.data = new Gson().fromJson(reader, BlueprintData.class);
                    System.out.println("StarrySkies: Loaded blueprint from CLASSPATH.");
                }
            }
        } catch (Exception e) {
            System.err.println("StarrySkies: Failed to load from CLASSPATH: " + e.getMessage());
        }

        // 2. Try Reference File (Dev Env)
        if (this.data == null) {
            java.io.File refFile = new java.io.File("reference/starry_skies_blueprint.json");
            if (refFile.exists()) {
                try (java.io.FileReader reader = new java.io.FileReader(refFile)) {
                    this.data = new Gson().fromJson(reader, BlueprintData.class);
                    System.out.println("StarrySkies: Loaded blueprint from REFERENCE FILE.");
                } catch (Exception e) {
                    System.err.println("StarrySkies: Failed to load from REFERENCE FILE: " + e.getMessage());
                }
            }
        }

        // 3. Try Config File (Legacy)
        if (this.data == null) {
            java.io.File configFile = new java.io.File("run/config/starry_skies_blueprint.json");
            if (configFile.exists()) {
                try (java.io.FileReader reader = new java.io.FileReader(configFile)) {
                    this.data = new Gson().fromJson(reader, BlueprintData.class);
                    System.out.println("StarrySkies: Loaded blueprint from CONFIG FILE.");
                } catch (Exception e) {
                    System.err.println("StarrySkies: Failed to load from CONFIG FILE: " + e.getMessage());
                }
            }
        }

        // Finalize
        if (this.data == null || this.data.nodes == null) {
            System.err
                    .println("StarrySkies: CRITICAL - Failed to load blueprint from ANY source. World will be empty!");
            StarrySkies.LOGGER.error("CRITICAL: Failed to load blueprint from ANY source.");
            this.data = new BlueprintData();
            this.data.nodes = new ArrayList<>();
        } else {
            buildSpatialIndex();
            System.out.println("StarrySkies: Blueprint loaded successfully. Nodes: " + this.data.nodes.size());
            StarrySkies.LOGGER.info("Blueprint loaded successfully. Nodes: " + this.data.nodes.size());
            if (!this.data.nodes.isEmpty()) {
                BlueprintNode first = this.data.nodes.get(0);
                System.out.println("StarrySkies: Sample Node: " + first.type + " at " + Arrays.toString(first.pos));

                // Self-test KDTree
                BlueprintNode nearest = getNearestNode((int) first.pos[0], (int) first.pos[2]);
                System.out.println(
                        "StarrySkies: KDTree Self-Test (Target Sample): " + (nearest == first ? "SUCCESS" : "FAILURE"));

                BlueprintNode origin = getNearestNode(0, 0);
                if (origin != null) {
                    double dist = Math.sqrt(Math.pow(origin.pos[0], 2) + Math.pow(origin.pos[2], 2));
                    System.out.println("StarrySkies: Nearest to Origin (0,0): " + origin.type + " at "
                            + Arrays.toString(origin.pos) + " Dist: " + dist);
                }
            }
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

    /**
     * Finds spheres that might intersect with the chunk.
     * Since chunks are small and spheres can be large, we query for nodes close to
     * the chunk center.
     * We'll use a generous range search or iterate if optimized.
     * For now, naive linear scan if N is small, but N=45k.
     * Range search on KDTree: Find nodes within (MaxSphereRadius + ChunkRadius) of
     * ChunkCenter.
     */
    public List<BlueprintNode> getSpheresInChunk(ChunkPos chunkPos) {
        if (kdTree == null)
            return Collections.emptyList();

        int chunkCenterX = chunkPos.getCenterX();
        int chunkCenterZ = chunkPos.getCenterZ();

        // Approximate range: Max sphere radius + Chunk radius
        // We iterate nearby nodes.
        // Let's use a larger safety buffer to ensure we catch large spheres.
        // If a sphere has radius R and is at distance D, it intersects if D < R +
        // ChunkRadius.
        // We search for centers within D. So searchRadius should be >=
        // MaxPossibleSphereRadius + ChunkRadius.
        // Assuming max sphere radius around 100-150? Let's use 300 to be safe.
        double searchRadius = 300.0;

        // Better: KDTree range search
        List<BlueprintNode> nearby = kdTree.rangeSearch(chunkCenterX, chunkCenterZ, searchRadius);
        return nearby;
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
            d = distSq(best.pos[0], best.pos[2], targetX, targetZ); // Re-calculate best dist

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
}
