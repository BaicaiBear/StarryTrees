package de.dafuqs.starryskies.datagen;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.fabricmc.fabric.api.datagen.v1.FabricDataOutput;
import net.minecraft.data.DataOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.DataWriter;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.Identifier;

public class StoryLootTableGenerator implements DataProvider {

    private final FabricDataOutput output;
    private final CompletableFuture<RegistryWrapper.WrapperLookup> registryLookup;

    public StoryLootTableGenerator(FabricDataOutput output,
            CompletableFuture<RegistryWrapper.WrapperLookup> registryLookup) {
        this.output = output;
        this.registryLookup = registryLookup;
    }

    @Override
    public CompletableFuture<?> run(DataWriter writer) {
        return registryLookup.thenAcceptAsync((lookup) -> {
            try {
                generate(writer);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private void generate(DataWriter writer) throws Exception {
        InputStream stream = getClass().getClassLoader().getResourceAsStream("data/starryskies/story.json");
        if (stream == null) {
            throw new RuntimeException("Could not find data/starryskies/story.json");
        }

        Gson gson = new Gson();
        JsonObject story = gson.fromJson(new InputStreamReader(stream, StandardCharsets.UTF_8), JsonObject.class);

        String mainTitle = story.get("title").getAsString();
        String author = story.get("author").getAsString();
        JsonArray chapters = story.getAsJsonArray("chapters");

        List<CompletableFuture<?>> futures = new ArrayList<>();

        for (int chapIdx = 0; chapIdx < chapters.size(); chapIdx++) {
            JsonObject chapter = chapters.get(chapIdx).getAsJsonObject();
            String chapTitle = chapter.get("title").getAsString();
            JsonArray parts = chapter.getAsJsonArray("parts");

            for (int partIdx = 0; partIdx < parts.size(); partIdx++) {
                JsonArray pagesJson = parts.get(partIdx).getAsJsonArray();

                // Build JSON Manually for 1.21 Data Components
                JsonObject root = new JsonObject();
                // root.addProperty("type", "minecraft:chest"); // Optional for generic loot,
                // but good practice
                // Actually 1.21 archaeology uses "minecraft:archaeology" type or just generic.

                JsonArray pools = new JsonArray();
                JsonObject pool = new JsonObject();
                pool.addProperty("rolls", 1);

                JsonArray entries = new JsonArray();
                JsonObject entry = new JsonObject();
                entry.addProperty("type", "minecraft:item");
                entry.addProperty("name", "minecraft:written_book");

                JsonArray functions = new JsonArray();
                JsonObject function = new JsonObject();
                function.addProperty("function", "minecraft:set_components");

                JsonObject components = new JsonObject();
                JsonObject bookContent = new JsonObject();
                // Title limit is 32 characters. Format: "Ch X: [Title]"
                String fullTitle = "Ch " + (chapIdx + 1) + ": " + chapTitle;
                if (fullTitle.length() > 32) {
                    fullTitle = fullTitle.substring(0, 32);
                }
                bookContent.addProperty("title", fullTitle);
                bookContent.addProperty("author", author);

                JsonArray pages = new JsonArray();
                for (JsonElement p : pagesJson) {
                    // Filterable text structure {"text": "..."} or just string?
                    // WrittenBookContent uses Filterable<Component>.
                    // In JSON it usually accepts raw strings or objects.
                    // For simplicity, let's use the string directly which parses to literal text.
                    // Or object {"text": "..."}
                    JsonObject pageObj = new JsonObject();
                    pageObj.addProperty("text", p.getAsString());
                    pages.add(pageObj);
                }
                bookContent.add("pages", pages);

                components.add("minecraft:written_book_content", bookContent);
                function.add("components", components);

                functions.add(function);
                entry.add("functions", functions);

                entries.add(entry);
                pool.add("entries", entries);

                pools.add(pool);
                root.add("pools", pools);

                // Identifier: starryskies:chests/lore/chapter_X_part_Y
                Identifier id = Identifier.of("starryskies", "chests/lore/chapter_" + chapIdx + "_part_" + partIdx);
                Path path = output.getResolver(DataOutput.OutputType.DATA_PACK, "loot_table").resolveJson(id);

                futures.add(DataProvider.writeToPath(writer, root, path));
            }
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }

    @Override
    public String getName() {
        return "Story Loot Tables";
    }
}
