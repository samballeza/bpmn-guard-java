package bpmnguard.render;

import bpmnguard.model.Bounds;
import bpmnguard.model.Diagram;

import java.awt.geom.Rectangle2D;

/** DI units -&gt; pixels: pixel = (di - extentMin) * scale + padding. */
public final class Transform {

    private final double minX, minY, scale;
    private final int padding;
    public final int width, height;

    public Transform(Diagram di, RenderOptions o) {
        Diagram.Extent ex = di.extent();
        this.minX = ex == null ? 0 : ex.minX();
        this.minY = ex == null ? 0 : ex.minY();
        this.scale = o.scale();
        this.padding = o.padding();
        double w = ex == null ? 400 : ex.width(), h = ex == null ? 200 : ex.height();
        this.width = (int) Math.ceil(2 * padding + w * scale);
        this.height = (int) Math.ceil(2 * padding + h * scale);
    }

    public double x(double diX) { return padding + (diX - minX) * scale; }
    public double y(double diY) { return padding + (diY - minY) * scale; }
    public double s(double v) { return v * scale; }

    public Rectangle2D.Double rect(Bounds b) {
        return new Rectangle2D.Double(x(b.x()), y(b.y()), s(b.width()), s(b.height()));
    }

    public double[][] polyline(Bounds edge) {
        double[][] pts = new double[edge.waypoints().size()][];
        for (int i = 0; i < pts.length; i++) {
            double[] p = edge.waypoints().get(i);
            pts[i] = new double[]{x(p[0]), y(p[1])};
        }
        return pts;
    }
}
