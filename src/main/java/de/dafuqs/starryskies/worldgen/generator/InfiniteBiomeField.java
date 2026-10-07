package de.dafuqs.starryskies.worldgen.generator;

import java.util.SplittableRandom;

/** A coordinate-only biome field, shared by sphere selection and the biome source. */
public final class InfiniteBiomeField {
    private static final int CELL = 512;
    private final long seed;
    private final boolean nether;

    public InfiniteBiomeField(long seed, boolean nether) {
        this.seed = seed ^ (nether ? 0x74c3e1L : 0x5a9d3bL);
        this.nether = nether;
    }

    public String at(int x, int y, int z) {
        int cx = Math.floorDiv(x, CELL);
        int cz = Math.floorDiv(z, CELL);
        int layer = nether ? 0 : y < 70 ? 0 : y >= 205 ? 2 : 1;
        long salt = mix(seed ^ ((long) cx * 0x9e3779b97f4a7c15L)
                ^ ((long) cz * 0xbf58476d1ce4e5b9L) ^ ((long) layer * 0x94d049bb133111ebL));
        SplittableRandom random = new SplittableRandom(salt);
        if (nether) return BlueprintBiomeCatalog.INSTANCE.chooseBiome("nether", random);
        double px = (cx + 0.5) * CELL;
        double pz = (cz + 0.5) * CELL;
        double heat = noise(seed ^ 0x7a2934L, px / 2300, pz / 2300);
        double land = noise(seed ^ 0x58d129L, px / 2900, pz / 2900);
        String category;
        if (layer == 0 && random.nextDouble() < 0.62) category = "cave";
        else if (layer == 2 && random.nextDouble() < 0.65) category = "mountain";
        else if (land < -0.22) category = "ocean";
        else if (land < -0.12 && random.nextDouble() < 0.65) category = "shore";
        else if (heat < -0.18) category = "cold";
        else if (heat > 0.18) category = "warm";
        else category = "temperate";
        return BlueprintBiomeCatalog.INSTANCE.chooseBiome(category, random);
    }

    private static double noise(long seed, double x, double z) {
        int ix = (int) Math.floor(x), iz = (int) Math.floor(z);
        double fx = fade(x - ix), fz = fade(z - iz);
        double a = lerp(hash(seed, ix, iz), hash(seed, ix + 1, iz), fx);
        double b = lerp(hash(seed, ix, iz + 1), hash(seed, ix + 1, iz + 1), fx);
        return lerp(a, b, fz);
    }

    private static double hash(long seed, int x, int z) {
        return ((mix(seed ^ ((long) x * 0x9e3779b97f4a7c15L)
                ^ ((long) z * 0xbf58476d1ce4e5b9L)) >>> 11) * 0x1.0p-53) * 2 - 1;
    }

    private static double fade(double t) { return t * t * (3 - 2 * t); }
    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }

    public static long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }
}
