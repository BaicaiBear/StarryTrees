package de.dafuqs.starryskies.worldgen.generator;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.Identifier;

/** Finds sphere types whose top center is not a reliable bridge landing. */
public final class BridgeAnchorPolicy {
    public enum Mode { TOP, POND_RIM, BEE_RING }

    private static final Map<String, Mode> CACHE = new ConcurrentHashMap<>();

    private BridgeAnchorPolicy() {}

    public static Mode modeFor(String sphereId) {
        return CACHE.computeIfAbsent(sphereId, BridgeAnchorPolicy::readMode);
    }

    private static Mode readMode(String sphereId) {
        Identifier id = Identifier.tryParse(sphereId);
        if (id == null) return Mode.TOP;
        String path = "/data/" + id.getNamespace() + "/starry_skies/configured_sphere/"
                + id.getPath() + ".json";
        try (var stream = BridgeAnchorPolicy.class.getResourceAsStream(path)) {
            if (stream == null) return Mode.TOP;
            JsonObject json = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            if (json.get("type").getAsString().endsWith(":bee_hive")) return Mode.BEE_RING;
            JsonObject config = json.getAsJsonObject("config");
            if (config == null || !config.has("decorators")) return Mode.TOP;
            JsonObject decorators = config.getAsJsonObject("decorators");
            for (String key : decorators.keySet()) {
                if (key.startsWith("starry_skies:center_pond")) return Mode.POND_RIM;
            }
            return Mode.TOP;
        } catch (RuntimeException | java.io.IOException e) {
            throw new IllegalStateException("Could not inspect bridge anchor for " + sphereId, e);
        }
    }
}
