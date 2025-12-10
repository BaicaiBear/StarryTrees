package de.dafuqs.starryskies.worldgen.generator;

import java.util.*;

public class MathUtils {

    public record Point(double x, double z) {
    }

    public record Edge(Point p1, Point p2) {
    }

    public record Triangle(Point p1, Point p2, Point p3) {
    }

    /**
     * Optimized Delaunay Triangulation using JTS (O(N log N)).
     */
    public static List<Triangle> triangulate(List<Point> points, double minX, double minZ, double maxX, double maxZ) {
        if (points.size() < 3)
            return new ArrayList<>();

        // Convert to JTS Coordinates
        List<org.locationtech.jts.geom.Coordinate> coords = new ArrayList<>(points.size());
        for (Point p : points) {
            coords.add(new org.locationtech.jts.geom.Coordinate(p.x, p.z));
        }

        // Use JTS Builder
        org.locationtech.jts.triangulate.DelaunayTriangulationBuilder builder = new org.locationtech.jts.triangulate.DelaunayTriangulationBuilder();
        builder.setSites(coords);

        // Optional: Set tolerance if needed, but defaults are usually fine
        // builder.setTolerance(0.001);

        // Get Triangles as Geometry
        org.locationtech.jts.geom.GeometryFactory geomFactory = new org.locationtech.jts.geom.GeometryFactory();
        org.locationtech.jts.geom.Geometry trianglesGeom = builder.getTriangles(geomFactory);

        List<Triangle> result = new ArrayList<>();
        int numGeoms = trianglesGeom.getNumGeometries();

        for (int i = 0; i < numGeoms; i++) {
            org.locationtech.jts.geom.Polygon poly = (org.locationtech.jts.geom.Polygon) trianglesGeom.getGeometryN(i);
            org.locationtech.jts.geom.Coordinate[] vertices = poly.getCoordinates();
            // JTS returns closed polygon (4 coords: A, B, C, A).
            if (vertices.length >= 4) {
                Point p1 = new Point(vertices[0].x, vertices[0].y);
                Point p2 = new Point(vertices[1].x, vertices[1].y);
                Point p3 = new Point(vertices[2].x, vertices[2].y);
                result.add(new Triangle(p1, p2, p3));
            }
        }

        // JTS computes the Convex Hull triangulation.
        // If we want to constrain to the bounding box (Super Triangle technique),
        // we might miss the corners if no points are there?
        // But for Poisson sampling inside a radius, the Hull is usually sufficient.
        // If strict square coverage is needed, add corner points before triangulating.
        // But the original code just used super triangle to ensure coverage.
        // Poisson points are dense, so Hull is fine.

        return result;
    }

    // Helper for Voronoi regions could be added here, OR we can approximate/skip
    // Voronoi for game logic
    // The Python script saves Voronoi regions but mostly for visualization or
    // region checking.
    // In-game, we mostly need the points and their biome/radii. The Voronoi polys
    // are useful for debug
    // or if we needed to fill the void with something, but currently we just use
    // nearest neighbor lookup (KDTree).
    // So we might NOT need to fully implement Voronoi generation if we rely on
    // KDTree.
}
