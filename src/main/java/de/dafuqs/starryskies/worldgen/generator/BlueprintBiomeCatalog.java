package de.dafuqs.starryskies.worldgen.generator;

import com.google.gson.Gson;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/** Immutable, data driven palette shared by blueprint generation and bridges. */
public final class BlueprintBiomeCatalog {
    public static final BlueprintBiomeCatalog INSTANCE = new BlueprintBiomeCatalog();

    public static final class SphereChoice {
        public String id;
        public int[] size;
        public int weight;
    }

    public static final class BiomeEntry {
        public String profile;
        public String category;
        public List<SphereChoice> spheres;
        public List<List<com.google.gson.JsonElement>> bridge;
        public String sky;
        public String fog;
    }

    private final Map<String, BiomeEntry> entries;
    private final String fingerprint;
    private final Map<String, List<String>> categories = new HashMap<>();

    private BlueprintBiomeCatalog() {
        try (var stream = BlueprintBiomeCatalog.class.getResourceAsStream("/assets/starry_skies/blueprint_biomes.json")) {
            if (stream == null) throw new IllegalStateException("Missing blueprint_biomes.json");
            byte[] bytes = stream.readAllBytes();
            fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            var reader = new StringReader(new String(bytes, StandardCharsets.UTF_8));
            var type = new com.google.gson.reflect.TypeToken<Map<String, BiomeEntry>>() {}.getType();
            entries = new Gson().fromJson(reader, type);
            for (var item : entries.entrySet()) {
                categories.computeIfAbsent(item.getValue().category, ignored -> new ArrayList<>()).add(item.getKey());
            }
            categories.values().forEach(list -> list.sort(String::compareTo));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load blueprint biome catalog", e);
        }
    }

    public BiomeEntry get(String biome) {
        return entries.get(biome.startsWith("minecraft:") ? biome.substring(10) : biome);
    }

    public String fingerprint() { return fingerprint; }

    public List<String> inCategory(String category) {
        return categories.getOrDefault(category, List.of());
    }

    public List<String> allBiomes(boolean nether) {
        return entries.entrySet().stream()
                .filter(item -> nether == item.getValue().category.equals("nether") &&
                        !item.getValue().category.equals("end"))
                .map(Map.Entry::getKey).sorted().toList();
    }

    public SphereChoice chooseSphere(String biome, SplittableRandom random) {
        BiomeEntry entry = get(biome);
        if (entry == null || entry.spheres == null || entry.spheres.isEmpty()) {
            throw new IllegalArgumentException("No sphere palette for " + biome);
        }
        int roll = random.nextInt(100);
        for (SphereChoice choice : entry.spheres) {
            roll -= choice.weight;
            if (roll < 0) return choice;
        }
        return entry.spheres.getLast();
    }

    public String chooseBiome(String category, SplittableRandom random) {
        List<String> list = inCategory(category);
        if (list.isEmpty()) list = inCategory("temperate");
        int total = 0;
        for (String biome : list) total += regionWeight(biome);
        int roll = random.nextInt(total);
        for (String biome : list) {
            roll -= regionWeight(biome);
            if (roll < 0) return "minecraft:" + biome;
        }
        return "minecraft:" + list.getLast();
    }

    private int regionWeight(String biome) {
        return switch (biome) {
            case "plains", "forest", "desert", "taiga", "ocean", "nether_wastes" -> 8;
            case "mushroom_fields", "pale_garden", "ice_spikes", "eroded_badlands", "sulfur_caves" -> 1;
            case "cherry_grove", "bamboo_jungle", "deep_dark", "warm_ocean", "basalt_deltas" -> 2;
            default -> 4;
        };
    }
}
