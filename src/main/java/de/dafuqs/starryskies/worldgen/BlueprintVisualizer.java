package de.dafuqs.starryskies.worldgen;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import javax.imageio.ImageIO;

import de.dafuqs.starryskies.StarrySkies;

public class BlueprintVisualizer {

    private static final int IMG_SIZE = 4096;
    private static final Map<String, Color> BIOME_COLORS = new HashMap<>();

    static {
        // Overworld
        BIOME_COLORS.put("minecraft:warm_ocean", new Color(0, 200, 255));
        BIOME_COLORS.put("minecraft:forest", new Color(34, 139, 34));
        BIOME_COLORS.put("minecraft:deep_frozen_ocean", new Color(0, 0, 139));
        BIOME_COLORS.put("minecraft:swamp", new Color(47, 79, 79));
        BIOME_COLORS.put("minecraft:snowy_taiga", new Color(200, 200, 255));
        BIOME_COLORS.put("minecraft:lush_caves", new Color(124, 252, 0));
        BIOME_COLORS.put("minecraft:desert", new Color(240, 230, 140));
        BIOME_COLORS.put("minecraft:stony_peaks", new Color(128, 128, 128));
        BIOME_COLORS.put("minecraft:frozen_peaks", new Color(240, 248, 255));

        // Nether
        BIOME_COLORS.put("minecraft:basalt_deltas", new Color(40, 40, 40));
        BIOME_COLORS.put("minecraft:crimson_forest", new Color(220, 20, 60));
        BIOME_COLORS.put("minecraft:nether_wastes", new Color(139, 69, 19));
        BIOME_COLORS.put("minecraft:soul_sand_valley", new Color(165, 42, 42));
        BIOME_COLORS.put("minecraft:warped_forest", new Color(0, 128, 128));
    }

    public static void visualize(BlueprintManager.BlueprintData data, String filename, Path outputDir) {
        if (data == null || data.nodes == null || data.nodes.isEmpty()) return;

        double radiusMap = data.metadata.radius_map;
        // Map range [-radius, radius] to [0, IMG_SIZE]
        
        BufferedImage image = new BufferedImage(IMG_SIZE, IMG_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = image.createGraphics();

        // Background
        g2d.setColor(Color.BLACK);
        g2d.fillRect(0, 0, IMG_SIZE, IMG_SIZE);

        // Anti-aliasing
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        double scale = IMG_SIZE / (2.0 * radiusMap);
        double offset = radiusMap; // Shift coordinates so (0,0) is center (radius, radius)

        for (BlueprintManager.BlueprintNode node : data.nodes) {
            Color c = BIOME_COLORS.getOrDefault(node.biome, Color.WHITE);
            g2d.setColor(c);

            double worldX = node.pos[0];
            double worldZ = node.pos[2];
            double r = node.radius;

            // Convert to image coordinates
            // imgX = (worldX + radiusMap) * scale
            double imgX = (worldX + offset) * scale;
            double imgY = (worldZ + offset) * scale;
            double imgR = r * scale;

            // Draw filled circle
            // Ellipse2D.Double(x, y, w, h) - x,y are top-left corner
            Ellipse2D.Double circle = new Ellipse2D.Double(imgX - imgR, imgY - imgR, imgR * 2, imgR * 2);
            g2d.fill(circle);
            
            // Optional: Draw border for contrast
            g2d.setColor(c.darker());
            g2d.draw(circle);
        }
        
        // Draw bridge lines? 
        // Need bridge data passed in? The request explicitly asked for spheres positions and sizes with biomes.
        // Keeping it simple for now as per request "spheres locations and sizes with their belonging biomes".

        // ... existing drawing code ...
        // Note: The loop for nodes ends at line 83 in original file.
        // We will insert legend drawing after that.

        // Draw Legend
        drawLegend(g2d);

        g2d.dispose();

        Path outFile = outputDir.resolve(filename);
        try {
            ImageIO.write(image, "png", outFile.toFile());
            StarrySkies.LOGGER.info("Saved visualization to " + outFile);
        } catch (IOException e) {
            StarrySkies.LOGGER.error("Failed to save visualization: " + e.getMessage());
        }
    }

    private static void drawLegend(Graphics2D g2d) {
        int x = 50;
        int y = 50;
        int boxSize = 40;
        int padding = 20;
        int fontSize = 32;

        g2d.setFont(new Font("Arial", Font.BOLD, fontSize));
        
        // Calculate legend background size (optional, but good for readability)
        // Keep it simple: text with shadow or just overlay.
        // Let's draw a semi-transparent background for the legend.
        
        int legendWidth = 600; // Estimate
        int legendHeight = (BIOME_COLORS.size() * (boxSize + padding)) + padding;
        
        g2d.setColor(new Color(0, 0, 0, 150));
        g2d.fillRect(x - padding, y - padding, legendWidth, legendHeight);

        for (Map.Entry<String, Color> entry : BIOME_COLORS.entrySet()) {
            g2d.setColor(entry.getValue());
            g2d.fillRect(x, y, boxSize, boxSize);
            
            g2d.setColor(Color.WHITE);
            // removing "minecraft:" prefix for cleaner legend
            String name = entry.getKey().replace("minecraft:", ""); 
            g2d.drawString(name, x + boxSize + padding, y + boxSize - 5);
            
            y += boxSize + padding;
        }
    }
}
