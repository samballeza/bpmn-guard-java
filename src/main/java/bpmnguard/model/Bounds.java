package bpmnguard.model;

import java.util.List;

/**
 * Diagram-interchange geometry for one element.
 * Shapes carry dc:Bounds; edges carry their waypoint polyline plus its bounding box.
 */
public record Bounds(double x, double y, double width, double height, Kind kind,
                     boolean expanded, List<double[]> waypoints, Bounds label) {

    public enum Kind { SHAPE, EDGE }

    public static Bounds shape(double x, double y, double w, double h, boolean expanded, Bounds label) {
        return new Bounds(x, y, w, h, Kind.SHAPE, expanded, List.of(), label);
    }

    public static Bounds edge(List<double[]> pts, Bounds label) {
        double x0 = Double.MAX_VALUE, y0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y1 = -Double.MAX_VALUE;
        for (double[] p : pts) {
            x0 = Math.min(x0, p[0]); y0 = Math.min(y0, p[1]);
            x1 = Math.max(x1, p[0]); y1 = Math.max(y1, p[1]);
        }
        return new Bounds(x0, y0, x1 - x0, y1 - y0, Kind.EDGE, false, List.copyOf(pts), label);
    }

    public double maxX() { return x + width; }
    public double maxY() { return y + height; }
    public boolean isShape() { return kind == Kind.SHAPE; }
    public boolean isEdge() { return kind == Kind.EDGE; }
}
