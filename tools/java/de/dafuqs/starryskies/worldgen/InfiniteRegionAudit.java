package de.dafuqs.starryskies.worldgen;

import com.google.gson.Gson;
import de.dafuqs.starryskies.worldgen.generator.InfiniteRegionGenerator;
import de.dafuqs.starryskies.worldgen.generator.InfiniteRegionGenerator.RegionData;
import de.dafuqs.starryskies.worldgen.generator.InfiniteRegionGenerator.RegionKey;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

/** Run with ./gradlew auditInfiniteRegions [-PauditCount=10000]. */
public final class InfiniteRegionAudit {
    public static void main(String[] args) {
        int count = Integer.parseInt(args[0]);
        SplittableRandom random = new SplittableRandom(0x7a4c11L);
        long[] times = new long[count];
        int min = Integer.MAX_VALUE, max = 0;
        for (int i = 0; i < count; i++) {
            long seed = i % 8 == 0 ? 0x2718abL : random.nextLong();
            boolean nether = (i & 1) == 1;
            InfiniteRegionGenerator generator = new InfiniteRegionGenerator(seed, nether);
            int rx = random.nextInt(-5000, 5001);
            int rz = random.nextInt(-5000, 5001);
            if (i % 20 == 0) { rx = i / 20; rz = -(i / 20); }
            long start = System.nanoTime();
            RegionData region = generator.generate(rx, rz);
            times[i] = (System.nanoTime() - start) / 1_000_000;
            int cross = 0;
            for (var edge : region.edges) {
                if (edge.endId < 0) cross++;
                else if (edge.startId < 0 || edge.startId >= region.nodes.size()
                        || edge.endId >= region.nodes.size()) throw new AssertionError("Invalid edge ID");
                var a = edge.points.get(0);
                var b = edge.points.get(1);
                if (Math.hypot(a.get(0) - b.get(0), a.get(2) - b.get(2))
                        > InfiniteRegionGenerator.MAX_BRIDGE_SPAN) throw new AssertionError("Long bridge");
            }
            if (region.edges.size() - cross != region.nodes.size() - 1)
                throw new AssertionError("Disconnected local tree at " + rx + "," + rz);
            RegionKey parent = generator.parent(new RegionKey(rx, rz));
            if (parent != null && Math.abs(parent.x()) + Math.abs(parent.z())
                    != Math.abs(rx) + Math.abs(rz) - 1) throw new AssertionError("Parent rank");
            min = Math.min(min, region.nodes.size());
            max = Math.max(max, region.nodes.size());
            if (i < Math.min(count, 32)) {
                String first = new Gson().toJson(region);
                String second = new Gson().toJson(generator.generate(rx, rz));
                if (!first.equals(second)) throw new AssertionError("Non-deterministic region at " + rx + "," + rz);
                for (var edge : region.edges) {
                    for (var obstacle : region.nodes) {
                        if (obstacle.id == edge.startId || obstacle.id == edge.endId) continue;
                        if (hits(edge.points.get(0), edge.points.get(1), obstacle))
                            throw new AssertionError("Bridge intersects sphere at " + rx + "," + rz);
                    }
                }
            }
            if (i < Math.min(count, 24)) {
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                    if (dx < 0 || dx == 0 && dz <= 0) continue;
                    RegionData neighbor = generator.generate(rx + dx, rz + dz);
                    for (var a : region.nodes) for (var b : neighbor.nodes) {
                        double x = a.getX() - b.getX(), y = a.getY() - b.getY(), z = a.getZ() - b.getZ();
                        double limit = a.radius + b.radius + 2;
                        if (x * x + y * y + z * z < limit * limit)
                            throw new AssertionError("Cross-region overlap at " + rx + "," + rz);
                    }
                }
            }
            if (i % 100 == 0 && parent != null) {
                RegionData parentData = generator.generate(parent.x(), parent.z());
                boolean foundPort = false;
                for (var edge : parentData.edges) {
                    if (edge.endId >= 0) continue;
                    for (var node : region.nodes) {
                        List<Integer> top = List.of(node.getX(), (int) (node.getY() + node.radius), node.getZ());
                        if (!edge.points.get(1).equals(top)) continue;
                        foundPort = true;
                        for (var obstacle : region.nodes) {
                            if (obstacle != node && hits(edge.points.get(0), edge.points.get(1), obstacle))
                                throw new AssertionError("Cross bridge cuts child sphere at " + rx + "," + rz);
                        }
                    }
                }
                if (!foundPort) throw new AssertionError("Missing parent bridge at " + rx + "," + rz);
            }
        }
        Arrays.sort(times);
        System.out.printf("%d regions: sphere count %d..%d, cold p50=%d ms, p95=%d ms, p99=%d ms, max=%d ms%n",
                count, min, max, times[count / 2], times[(int) (count * .95)],
                times[(int) (count * .99)], times[count - 1]);
    }

    private static boolean hits(List<Integer> a, List<Integer> b, BlueprintManager.BlueprintNode sphere) {
        double dx = b.get(0) - a.get(0), dz = b.get(2) - a.get(2);
        double horizontalSq = dx * dx + dz * dz;
        double projection = ((sphere.getX() - a.get(0)) * dx + (sphere.getZ() - a.get(2)) * dz)
                / horizontalSq;
        if (projection < -0.1 || projection > 1.1) return false;
        double horizontal = Math.sqrt(horizontalSq);
        double sag = Math.min(12, horizontal * .08);
        if (Math.abs(b.get(1) - a.get(1)) > horizontal * .5) sag = Math.min(sag, 3);
        for (double shift : new double[] {-0.08, 0, 0.08}) {
            double t = Math.max(0, Math.min(1, projection + shift));
            double x = a.get(0) + t * dx, z = a.get(2) + t * dz;
            double y = a.get(1) + (b.get(1) - a.get(1)) * t + sag
                    * (Math.cosh(2 * t - 1) - Math.cosh(1)) / (Math.cosh(1) - 1);
            if (Math.sqrt(Math.pow(x - sphere.getX(), 2) + Math.pow(y - sphere.getY(), 2)
                    + Math.pow(z - sphere.getZ(), 2)) < sphere.radius + 3) return true;
        }
        return false;
    }
}
