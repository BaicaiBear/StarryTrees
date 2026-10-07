package de.dafuqs.starryskies.worldgen.generator;

import de.dafuqs.starryskies.worldgen.BlueprintManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/** Pure, order-independent design for one finite piece of an infinite tree. */
public final class InfiniteRegionGenerator {
    public static final int VERSION = 11;
    public static final int MAX_BRIDGE_SPAN = 192;
    private static final int CLEARANCE = 2;
    private static final int GRID = 80;
    private static final int SEAM_CELL = 32;
    private static final int SEAM_WIDTH = 100;
    private final long seed;
    private final boolean nether;
    private final int size;
    private final InfiniteBiomeField biomes;

    public InfiniteRegionGenerator(long seed, boolean nether) {
        this.seed = seed ^ (nether ? 0x4b93e1782L : 0x735a802c1L);
        this.nether = nether;
        this.size = nether ? 256 : 512;
        this.biomes = new InfiniteBiomeField(seed, nether);
    }

    public int size() { return size; }
    public InfiniteBiomeField biomes() { return biomes; }
    public int regionAt(int block) { return Math.floorDiv(block + size / 2, size); }

    public record RegionKey(int x, int z) {}
    private record CellKey(int x, int y, int z) {}
    private record SeamSite(BlueprintManager.BlueprintNode node, long priority) {}

    public static class RegionData {
        public int version;
        public int regionX;
        public int regionZ;
        public List<BlueprintManager.BlueprintNode> nodes;
        public List<BridgeRecord> edges;
    }

    public static class BridgeRecord {
        public List<List<Integer>> points;
        public int startId;
        public int endId;
        public String startType;
        public String endType;

        public BridgeRecord() {}

        BridgeRecord(BlueprintManager.BlueprintNode a, BlueprintManager.BlueprintNode b) {
            points = List.of(anchor(a, b), anchor(b, a));
            startId = a.id;
            endId = b.id;
            startType = a.type;
            endType = b.type;
        }
    }

    public RegionKey parent(RegionKey key) {
        int x = key.x(), z = key.z();
        if (x == 0 && z == 0) return null;
        if (x == 0) return new RegionKey(0, z - Integer.signum(z));
        if (z == 0) return new RegionKey(x - Integer.signum(x), 0);
        long h = hash(x, z, 0x77a1);
        return (h & 1) == 0
                ? new RegionKey(x - Integer.signum(x), z)
                : new RegionKey(x, z - Integer.signum(z));
    }

    public boolean strongholdCandidate(int rx, int rz) {
        return !nether && Math.hypot((double) rx * size, (double) rz * size) > 1800
                && Math.floorMod(hash(rx, rz, 0x5700), 32) == 0;
    }

    public RegionData generate(int rx, int rz) {
        RegionKey here = new RegionKey(rx, rz);
        int cx = Math.multiplyExact(rx, size), cz = Math.multiplyExact(rz, size);
        RegionData result = skeleton(rx, rz);
        Map<Long, List<BlueprintManager.BlueprintNode>> occupied = new HashMap<>();
        for (BlueprintManager.BlueprintNode node : result.nodes) insert(occupied, node);

        // Fixed neighboring spheres and their bridges protect the entire seam, including corners.
        List<BridgeRecord> neighborBridges = new ArrayList<>();
        List<BlueprintManager.BlueprintNode> neighborNodes = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dz == 0) continue;
            RegionData neighbor = skeleton(rx + dx, rz + dz);
            for (BlueprintManager.BlueprintNode node : neighbor.nodes) insert(occupied, node);
            neighborNodes.addAll(neighbor.nodes);
            neighborBridges.addAll(neighbor.edges);
        }

        List<BridgeRecord> reservedBridges = new ArrayList<>(result.edges);
        RegionKey parentRegion = parent(here);
        if (parentRegion != null) {
            // The parent owns this edge, but this side must keep its corridor clear too.
            reservedBridges.add(new BridgeRecord(port(here, parentRegion, -1),
                    port(parentRegion, here, -1)));
        }
        reservedBridges.addAll(neighborBridges);

        // Global seam cells may jitter across a region line, but each cell has one stable owner.
        // Comparing raw neighboring cells makes overlap resolution independent of visit order.
        List<BlueprintManager.BlueprintNode> candidates = new ArrayList<>();
        List<BlueprintManager.BlueprintNode> ghostSites = new ArrayList<>();
        Map<CellKey, SeamSite> seamCache = new HashMap<>();
        int minCellX = Math.floorDiv(cx - size / 2 - SEAM_WIDTH, SEAM_CELL);
        int maxCellX = Math.floorDiv(cx + size / 2 + SEAM_WIDTH, SEAM_CELL);
        int minCellZ = Math.floorDiv(cz - size / 2 - SEAM_WIDTH, SEAM_CELL);
        int maxCellZ = Math.floorDiv(cz + size / 2 + SEAM_WIDTH, SEAM_CELL);
        for (int gx = minCellX; gx <= maxCellX; gx++)
            for (int gz = minCellZ; gz <= maxCellZ; gz++)
                for (int gy = nether ? 0 : -2; gy <= (nether ? 7 : 9); gy++) {
                    CellKey cell = new CellKey(gx, gy, gz);
                    SeamSite site = seamWinner(cell, seamCache);
                    if (site == null) continue;
                    BlueprintManager.BlueprintNode node = site.node();
                    if (regionAt(gx * SEAM_CELL + SEAM_CELL / 2) != rx
                            || regionAt(gz * SEAM_CELL + SEAM_CELL / 2) != rz) {
                        ghostSites.add(node);
                    } else if (separated(node, occupied) && clearOfBridges(node, reservedBridges)) {
                        candidates.add(node);
                        insert(occupied, node);
                    }
                }

        // Interior sites stay far enough from seams to be independent of other regions.
        int target = nether ? 230 : 285;
        SplittableRandom random = new SplittableRandom(hash(rx, rz, 0x456c));
        int attempts = 0;
        while (candidates.size() + result.nodes.size() < target && attempts++ < target * 28) {
            int x = cx - size / 2 + SEAM_WIDTH + random.nextInt(size - 2 * SEAM_WIDTH);
            int z = cz - size / 2 + SEAM_WIDTH + random.nextInt(size - 2 * SEAM_WIDTH);
            int y = nether ? 22 + random.nextInt(193) : -30 + random.nextInt(311);
            String biome = biomes.at(x, y, z);
            BlueprintBiomeCatalog.SphereChoice choice = BlueprintBiomeCatalog.INSTANCE.chooseSphere(biome, random);
            int radius = choice.size[0] + random.nextInt(choice.size[1] - choice.size[0] + 1);
            if (biome.contains("deep_") && biome.contains("ocean")) {
                radius = Math.min(36, (int) Math.round(radius * 1.25));
                y -= 20;
            }
            if (y - radius < (nether ? 4 : -55) || y + radius > (nether ? 248 : 312)) continue;
            BlueprintManager.BlueprintNode node = node(-1, x, y, z, radius, biome, choice.id);
            if (!separated(node, occupied) || !clearOfBridges(node, reservedBridges)) continue;
            candidates.add(node);
            insert(occupied, node);
        }
        candidates.sort(Comparator.comparingDouble((BlueprintManager.BlueprintNode n) -> distanceToBackbone(n, result.nodes))
                .thenComparingInt(BlueprintManager.BlueprintNode::getX)
                .thenComparingInt(BlueprintManager.BlueprintNode::getY)
                .thenComparingInt(BlueprintManager.BlueprintNode::getZ));
        List<BlueprintManager.BlueprintNode> allSites = new ArrayList<>(result.nodes);
        allSites.addAll(candidates);
        allSites.addAll(ghostSites);
        allSites.addAll(neighborNodes);
        for (BlueprintManager.BlueprintNode candidate : candidates) {
            int parentId = nearestConnectable(candidate, result.nodes, allSites);
            if (parentId < 0) continue;
            candidate.id = result.nodes.size();
            result.nodes.add(candidate);
            link(result, parentId, candidate.id);
        }

        // Rare strongholds are selected from already connected, large land spheres.
        if (strongholdCandidate(rx, rz)) {
            for (int i = 9; i < result.nodes.size(); i++) {
                BlueprintManager.BlueprintNode n = result.nodes.get(i);
                if (n.radius >= 24 && !n.biome.contains("ocean") && !n.biome.contains("caves")) {
                    n.type = "starry_skies:overworld/treasure/stronghold";
                    break;
                }
            }
        }
        return result;
    }

    private RegionData skeleton(int rx, int rz) {
        RegionKey here = new RegionKey(rx, rz);
        int cx = Math.multiplyExact(rx, size), cz = Math.multiplyExact(rz, size);
        int gridStep = nether ? 50 : 128;
        int gridRadius = nether ? 12 : 16;
        String backboneType = nether ? "starry_skies:nether/decorative/blackstone"
                : "starry_skies:overworld/essential/grass";
        int centerY = (nether ? 100 : 95) + (rx == 0 && rz == 0 ? 0 : (int) Math.floorMod(hash(rx, rz, 1), 41) - 20);
        RegionData result = new RegionData();
        result.version = VERSION;
        result.regionX = rx;
        result.regionZ = rz;
        result.nodes = new ArrayList<>();
        result.edges = new ArrayList<>();
        Map<Long, List<BlueprintManager.BlueprintNode>> occupied = new HashMap<>();
        int[][] gridIds = new int[3][3];

        // The nine guaranteed spheres make a short, connected local backbone.
        for (int gx = -1; gx <= 1; gx++) for (int gz = -1; gz <= 1; gz++) {
            int jitter = nether ? 2 : 14;
            boolean worldRoot = rx == 0 && rz == 0 && gx == 0 && gz == 0;
            int x = cx + gx * gridStep + (worldRoot ? 0
                    : (int) Math.floorMod(hash(rx * 11 + gx, rz * 11 + gz, 0x71), 2 * jitter + 1) - jitter);
            int z = cz + gz * gridStep + (worldRoot ? 0
                    : (int) Math.floorMod(hash(rx * 11 + gx, rz * 11 + gz, 0x72), 2 * jitter + 1) - jitter);
            int y = gx == 0 && gz == 0 ? centerY
                    : centerY + (int) Math.floorMod(hash(rx * 11 + gx, rz * 11 + gz, 2), 51) - 25;
            int radius = gx == 0 && gz == 0 ? (nether ? 18 : 22) : gridRadius;
            BlueprintManager.BlueprintNode node = add(result, occupied, x, y, z, radius,
                    biomes.at(x, y, z), backboneType);
            gridIds[gx + 1][gz + 1] = node.id;
        }
        for (int gx = -1; gx <= 1; gx++) for (int gz = -1; gz <= 1; gz++) {
            if (gx == 0 && gz == 0) continue;
            int px = gx == 0 ? 0 : gx - Integer.signum(gx);
            int pz = gx == 0 ? gz - Integer.signum(gz) : gz;
            link(result, gridIds[px + 1][pz + 1], gridIds[gx + 1][gz + 1]);
        }

        // A parent port and every child port are stable across independent region generation.
        for (int[] direction : new int[][] {{-1, 0}, {1, 0}, {0, -1}, {0, 1}}) {
            RegionKey neighbor = new RegionKey(rx + direction[0], rz + direction[1]);
            boolean parent = neighbor.equals(parent(here));
            boolean child = here.equals(parent(neighbor));
            if (!parent && !child) continue;
            BlueprintManager.BlueprintNode port = port(here, neighbor, result.nodes.size());
            if (!separated(port, occupied)) throw new IllegalStateException("Port collision at " + here);
            result.nodes.add(port);
            insert(occupied, port);
            int nearest = nearestBackbone(port, result.nodes, 9);
            link(result, nearest, port.id);
            if (child) {
                BlueprintManager.BlueprintNode far = port(neighbor, here, -1);
                result.edges.add(new BridgeRecord(port, far));
            }
        }

        return result;
    }

    private SeamSite seamWinner(CellKey cell, Map<CellKey, SeamSite> cache) {
        SeamSite own = seamSite(cell, cache);
        if (own.node() == null) return null;
        for (int dx = -3; dx <= 3; dx++) for (int dy = -3; dy <= 3; dy++)
            for (int dz = -3; dz <= 3; dz++) {
                if (dx == 0 && dy == 0 && dz == 0) continue;
                CellKey otherKey = new CellKey(cell.x() + dx, cell.y() + dy, cell.z() + dz);
                SeamSite other = seamSite(otherKey, cache);
                if (other.node() == null) continue;
                if (Long.compareUnsigned(other.priority(), own.priority()) > 0
                        || other.priority() == own.priority() && cellOrder(otherKey, cell) > 0) {
                    if (overlaps(own.node(), other.node())) return null;
                }
            }
        return own;
    }

    private SeamSite seamSite(CellKey cell, Map<CellKey, SeamSite> cache) {
        return cache.computeIfAbsent(cell, key -> {
            int baseX = key.x() * SEAM_CELL + SEAM_CELL / 2;
            int baseZ = key.z() * SEAM_CELL + SEAM_CELL / 2;
            int rx = regionAt(baseX), rz = regionAt(baseZ);
            int centerX = rx * size, centerZ = rz * size;
            int edgeDistance = Math.min(size / 2 - Math.abs(baseX - centerX),
                    size / 2 - Math.abs(baseZ - centerZ));
            if (edgeDistance > SEAM_WIDTH) return new SeamSite(null, 0);
            long salt = InfiniteBiomeField.mix(seed ^ ((long) key.x() * 0x9e3779b97f4a7c15L)
                    ^ ((long) key.y() * 0xbf58476d1ce4e5b9L)
                    ^ ((long) key.z() * 0x94d049bb133111ebL) ^ 0x5ea0b0aL);
            SplittableRandom random = new SplittableRandom(salt);
            if (random.nextDouble() >= (nether ? 0.60 : 0.12)) return new SeamSite(null, 0);
            int x = baseX + random.nextInt(-24, 24);
            int y = key.y() * SEAM_CELL + SEAM_CELL / 2
                    + random.nextInt(-SEAM_CELL / 2, SEAM_CELL / 2);
            int z = baseZ + random.nextInt(-24, 24);
            String biome = biomes.at(x, y, z);
            BlueprintBiomeCatalog.SphereChoice choice = BlueprintBiomeCatalog.INSTANCE.chooseSphere(biome, random);
            int radius = choice.size[0] + random.nextInt(choice.size[1] - choice.size[0] + 1);
            if (biome.contains("deep_") && biome.contains("ocean")) {
                radius = Math.min(36, (int) Math.round(radius * 1.25));
                y -= 20;
            }
            if (y - radius < (nether ? 4 : -55) || y + radius > (nether ? 248 : 312))
                return new SeamSite(null, 0);
            return new SeamSite(node(-1, x, y, z, radius, biome, choice.id),
                    InfiniteBiomeField.mix(salt ^ 0x951ac3L));
        });
    }

    private static int cellOrder(CellKey a, CellKey b) {
        int x = Integer.compare(a.x(), b.x());
        if (x != 0) return x;
        int y = Integer.compare(a.y(), b.y());
        return y != 0 ? y : Integer.compare(a.z(), b.z());
    }

    private static boolean overlaps(BlueprintManager.BlueprintNode a, BlueprintManager.BlueprintNode b) {
        double dx = a.getX() - b.getX(), dy = a.getY() - b.getY(), dz = a.getZ() - b.getZ();
        double limit = a.radius + b.radius + CLEARANCE;
        return dx * dx + dy * dy + dz * dz < limit * limit;
    }

    private BlueprintManager.BlueprintNode port(RegionKey here, RegionKey neighbor, int id) {
        int dx = Integer.compare(neighbor.x(), here.x());
        int dz = Integer.compare(neighbor.z(), here.z());
        RegionKey first = here.x() < neighbor.x() || here.x() == neighbor.x() && here.z() < neighbor.z()
                ? here : neighbor;
        RegionKey second = first.equals(here) ? neighbor : here;
        long h = hash(first.x() * 31 + second.x(), first.z() * 31 + second.z(), 0x91ad);
        int offset = nether ? 34 : 56;
        int along = (int) Math.floorMod(h >>> 1, nether ? 117 : 313) - (nether ? 58 : 156);
        int x = here.x() * size + (dx == 0 ? along : dx * (size / 2 - offset));
        int z = here.z() * size + (dz == 0 ? along : dz * (size / 2 - offset));
        int y = (nether ? 105 : 110) + (int) Math.floorMod(h >>> 17, 51) - 25;
        return node(id, x, y, z, nether ? 18 : 20, biomes.at(x, y, z),
                nether ? "starry_skies:nether/decorative/blackstone" : "starry_skies:overworld/essential/grass");
    }

    private static int nearestBackbone(BlueprintManager.BlueprintNode node,
                                       List<BlueprintManager.BlueprintNode> nodes, int count) {
        int best = 0;
        double cost = Double.POSITIVE_INFINITY;
        for (int i = 0; i < count; i++) {
            BlueprintManager.BlueprintNode other = nodes.get(i);
            if (Math.hypot(node.getX() - other.getX(), node.getZ() - other.getZ())
                    > MAX_BRIDGE_SPAN - node.radius - other.radius) continue;
            double d = score(node, other);
            if (d < cost) { cost = d; best = i; }
        }
        if (!Double.isFinite(cost)) throw new IllegalStateException("No short bridge to border port");
        return best;
    }

    private static double distanceToBackbone(BlueprintManager.BlueprintNode node,
                                             List<BlueprintManager.BlueprintNode> nodes) {
        double best = Double.POSITIVE_INFINITY;
        for (int i = 0; i < Math.min(9, nodes.size()); i++) best = Math.min(best, score(node, nodes.get(i)));
        return best;
    }

    private static int nearestConnectable(BlueprintManager.BlueprintNode node,
            List<BlueprintManager.BlueprintNode> connected, List<BlueprintManager.BlueprintNode> allSites) {
        int[] bestIds = new int[8];
        double[] bestCosts = new double[8];
        java.util.Arrays.fill(bestIds, -1);
        java.util.Arrays.fill(bestCosts, Double.POSITIVE_INFINITY);
        for (BlueprintManager.BlueprintNode other : connected) {
            double horizontal = Math.hypot(node.getX() - other.getX(), node.getZ() - other.getZ());
            if (horizontal > MAX_BRIDGE_SPAN - node.radius - other.radius
                    || horizontal < node.radius + other.radius + 12) continue;
            double d = score(node, other);
            for (int i = 0; i < bestIds.length; i++) {
                if (d < bestCosts[i] || d == bestCosts[i] && other.id < bestIds[i]) {
                    for (int j = bestIds.length - 1; j > i; j--) {
                        bestIds[j] = bestIds[j - 1];
                        bestCosts[j] = bestCosts[j - 1];
                    }
                    bestIds[i] = other.id;
                    bestCosts[i] = d;
                    break;
                }
            }
        }
        for (int id : bestIds) {
            if (id >= 0 && clearBridge(node, connected.get(id), allSites)) return id;
        }
        return -1;
    }

    private static boolean clearOfBridges(BlueprintManager.BlueprintNode node, List<BridgeRecord> bridges) {
        for (BridgeRecord edge : bridges)
            if (intersects(node, edge.points.get(0), edge.points.get(1))) return false;
        return true;
    }

    private static boolean clearBridge(BlueprintManager.BlueprintNode from,
            BlueprintManager.BlueprintNode to, List<BlueprintManager.BlueprintNode> sites) {
        List<Integer> a = anchor(from, to), b = anchor(to, from);
        for (BlueprintManager.BlueprintNode obstacle : sites) {
            if (obstacle != from && obstacle != to && intersects(obstacle, a, b)) return false;
        }
        return true;
    }

    private static boolean intersects(BlueprintManager.BlueprintNode sphere, List<Integer> a, List<Integer> b) {
        double dx = b.get(0) - a.get(0), dz = b.get(2) - a.get(2);
        double horizontalSq = dx * dx + dz * dz;
        if (horizontalSq < 1) return false;
        double projection = ((sphere.getX() - a.get(0)) * dx + (sphere.getZ() - a.get(2)) * dz) / horizontalSq;
        if (projection < -0.1 || projection > 1.1) return false;
        double horizontal = Math.sqrt(horizontalSq);
        double sag = Math.min(12, horizontal * 0.08);
        if (Math.abs(b.get(1) - a.get(1)) > horizontal * 0.5) sag = Math.min(sag, 3);
        for (double shift : new double[] {-0.08, 0, 0.08}) {
            double t = Math.max(0, Math.min(1, projection + shift));
            double x = a.get(0) + t * dx, z = a.get(2) + t * dz;
            double y = a.get(1) + (b.get(1) - a.get(1)) * t + sag
                    * (Math.cosh(2 * t - 1) - Math.cosh(1)) / (Math.cosh(1) - 1);
            double d = Math.sqrt(Math.pow(x - sphere.getX(), 2) + Math.pow(y - sphere.getY(), 2)
                    + Math.pow(z - sphere.getZ(), 2));
            if (d < sphere.radius + 3) return true;
        }
        return false;
    }

    private static double score(BlueprintManager.BlueprintNode a, BlueprintManager.BlueprintNode b) {
        double horizontal = Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
        double vertical = Math.abs(a.getY() + a.radius - b.getY() - b.radius);
        return Math.hypot(horizontal, vertical) + vertical * 0.7
                + Math.max(0, vertical - horizontal * 0.35) * 8;
    }

    private static void link(RegionData data, int a, int b) {
        data.edges.add(new BridgeRecord(data.nodes.get(a), data.nodes.get(b)));
    }

    private static BlueprintManager.BlueprintNode add(RegionData data,
            Map<Long, List<BlueprintManager.BlueprintNode>> occupied,
            int x, int y, int z, int radius, String biome, String type) {
        BlueprintManager.BlueprintNode n = node(data.nodes.size(), x, y, z, radius, biome, type);
        if (!separated(n, occupied)) throw new IllegalStateException("Backbone collision at " + x + "," + z);
        data.nodes.add(n);
        insert(occupied, n);
        return n;
    }

    private static BlueprintManager.BlueprintNode node(int id, int x, int y, int z,
                                                        int radius, String biome, String type) {
        BlueprintManager.BlueprintNode n = new BlueprintManager.BlueprintNode();
        n.id = id;
        n.pos = new double[] {x, y, z};
        n.radius = radius;
        n.biome = biome;
        n.type = type;
        return n;
    }

    private static boolean separated(BlueprintManager.BlueprintNode n,
            Map<Long, List<BlueprintManager.BlueprintNode>> occupied) {
        int gx = Math.floorDiv(n.getX(), GRID), gy = Math.floorDiv(n.getY(), GRID),
                gz = Math.floorDiv(n.getZ(), GRID);
        for (int dx = -2; dx <= 2; dx++) for (int dy = -2; dy <= 2; dy++) for (int dz = -2; dz <= 2; dz++) {
            List<BlueprintManager.BlueprintNode> bucket = occupied.get(key(gx + dx, gy + dy, gz + dz));
            if (bucket == null) continue;
            for (BlueprintManager.BlueprintNode other : bucket) {
                double dist = Math.sqrt(Math.pow(n.getX() - other.getX(), 2)
                        + Math.pow(n.getY() - other.getY(), 2) + Math.pow(n.getZ() - other.getZ(), 2));
                if (dist < n.radius + other.radius + CLEARANCE) return false;
            }
        }
        return true;
    }

    private static void insert(Map<Long, List<BlueprintManager.BlueprintNode>> occupied,
                               BlueprintManager.BlueprintNode n) {
        long key = key(Math.floorDiv(n.getX(), GRID), Math.floorDiv(n.getY(), GRID), Math.floorDiv(n.getZ(), GRID));
        occupied.computeIfAbsent(key, ignored -> new ArrayList<>()).add(n);
    }

    private static long key(int x, int y, int z) {
        return InfiniteBiomeField.mix((long) x * 0x9e3779b97f4a7c15L
                ^ (long) y * 0xbf58476d1ce4e5b9L ^ (long) z * 0x94d049bb133111ebL);
    }

    private long hash(int x, int z, long purpose) {
        return InfiniteBiomeField.mix(seed ^ ((long) x * 0x9e3779b97f4a7c15L)
                ^ ((long) z * 0xbf58476d1ce4e5b9L) ^ purpose);
    }

    private static List<Integer> anchor(BlueprintManager.BlueprintNode sphere,
                                        BlueprintManager.BlueprintNode neighbor) {
        BridgeAnchorPolicy.Mode mode = BridgeAnchorPolicy.modeFor(sphere.type);
        if (mode == BridgeAnchorPolicy.Mode.TOP)
            return List.of(sphere.getX(), (int) Math.round(sphere.getY() + sphere.radius), sphere.getZ());
        double dx = neighbor.getX() - sphere.getX(), dz = neighbor.getZ() - sphere.getZ();
        double distance = Math.hypot(dx, dz);
        double outward = mode == BridgeAnchorPolicy.Mode.BEE_RING ? sphere.radius + 1 : sphere.radius * 0.8;
        int x = (int) Math.round(sphere.getX() + dx / distance * outward);
        int z = (int) Math.round(sphere.getZ() + dz / distance * outward);
        int y = mode == BridgeAnchorPolicy.Mode.BEE_RING ? sphere.getY() + 1
                : sphere.getY() + (int) Math.floor(Math.sqrt(Math.max(0,
                        Math.pow(sphere.radius + 0.49, 2) - Math.pow(x - sphere.getX(), 2)
                                - Math.pow(z - sphere.getZ(), 2)))) + 1;
        return List.of(x, y, z);
    }
}
