package bpmnguard.render;

import bpmnguard.model.Association;
import bpmnguard.model.Bounds;
import bpmnguard.model.BpmnModel;
import bpmnguard.model.Diagram;
import bpmnguard.model.FlowNode;
import bpmnguard.model.MultiInstance;
import bpmnguard.model.SequenceFlow;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Draws the base BPMN diagram from the file's own DI section using only java.awt:
 * rounded rectangles for activities, diamonds for gateways, circles for events,
 * polylines with arrowheads for sequence flows, text annotations and labels.
 */
public final class DiagramRenderer {

    static final Color BACKGROUND = Color.WHITE;
    static final Color SHAPE_FILL = new Color(242, 242, 242);
    static final Color SCOPE_FILL = new Color(250, 250, 250);
    static final Color SHAPE_LINE = new Color(70, 70, 70);
    static final Color EDGE_LINE = new Color(60, 60, 60);
    static final Color TEXT = new Color(30, 30, 30);
    static final Color UNKNOWN = new Color(200, 200, 200);

    private DiagramRenderer() {}

    public static BufferedImage render(BpmnModel model, Diagram di, RenderOptions o) {
        if (di.isEmpty()) throw new IllegalArgumentException("BPMN file has no DI section; nothing to draw");
        Transform t = new Transform(di, o);
        BufferedImage img = new BufferedImage(t.width, t.height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            quality(g);
            g.setColor(BACKGROUND);
            g.fillRect(0, 0, t.width, t.height);

            Map<String, FlowNode> nodes = model.nodes();
            Map<String, SequenceFlow> flows = model.flows();
            Set<String> assoc = new HashSet<>();
            for (Association a : model.associations()) assoc.add(a.id());

            // expanded containers first, then by area descending, so children sit on top
            List<Map.Entry<String, Bounds>> order = new ArrayList<>(di.shapes().entrySet());
            order.sort(Comparator.<Map.Entry<String, Bounds>>comparingInt(e -> {
                FlowNode n = nodes.get(e.getKey());
                return n != null && n.container() ? 0 : 1;
            }).thenComparingDouble(e -> -e.getValue().width() * e.getValue().height()));

            for (Map.Entry<String, Bounds> e : order) {
                String text = model.annotations().get(e.getKey());
                if (text != null) drawAnnotation(g, t.rect(e.getValue()), text, o);
                else drawShape(g, t, e.getValue(), nodes.get(e.getKey()), o);
            }
            for (Map.Entry<String, Bounds> e : di.edges().entrySet()) {
                SequenceFlow f = flows.get(e.getKey());
                if (f != null && !assoc.contains(e.getKey())) drawEdge(g, t, e.getValue(), f, o);
                else drawAssociation(g, t, e.getValue());
            }
            for (Map.Entry<String, Bounds> e : order) {
                if (model.annotations().containsKey(e.getKey())) continue;
                drawLabel(g, t, e.getValue(), nodes.get(e.getKey()), o);
            }
        } finally {
            g.dispose();
        }
        return img;
    }

    static void quality(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    }

    static BasicStroke stroke(double w) {
        return new BasicStroke((float) w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    }

    static BasicStroke dashed(double w) {
        return new BasicStroke((float) w, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{6f, 4f}, 0f);
    }

    // -----------------------------------------------------------------------
    // Text
    // -----------------------------------------------------------------------

    static List<String> wrap(FontMetrics fm, String text, double maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String w : text.trim().split("\\s+")) {
            if (w.isEmpty()) continue;
            String cand = line.length() == 0 ? w : line + " " + w;
            if (line.length() == 0 || fm.stringWidth(cand) <= maxWidth) {
                line.setLength(0); line.append(cand);
            } else {
                lines.add(line.toString());
                line.setLength(0); line.append(w);
            }
        }
        if (line.length() > 0) lines.add(line.toString());
        return lines;
    }

    /** Draw wrapped text centred on cx; vertical centre at cy when middle, top at cy otherwise. */
    static void textBlock(Graphics2D g, String text, double cx, double cy, double maxWidth, boolean middle, Color color) {
        if (text == null || text.isBlank()) return;
        FontMetrics fm = g.getFontMetrics();
        List<String> lines = wrap(fm, text, maxWidth);
        int lh = fm.getHeight();
        double top = middle ? cy - (lh * lines.size()) / 2.0 : cy;
        g.setColor(color);
        for (int i = 0; i < lines.size(); i++) {
            String ln = lines.get(i);
            g.drawString(ln, (int) Math.round(cx - fm.stringWidth(ln) / 2.0), (int) Math.round(top + i * lh + fm.getAscent()));
        }
    }

    // -----------------------------------------------------------------------
    // Shapes
    // -----------------------------------------------------------------------

    private static void fillOutline(Graphics2D g, java.awt.Shape s, Color fill, Color line, double w) {
        g.setColor(fill); g.fill(s);
        g.setColor(line); g.setStroke(stroke(w)); g.draw(s);
    }

    private static void drawShape(Graphics2D g, Transform t, Bounds b, FlowNode n, RenderOptions o) {
        Rectangle2D.Double r = t.rect(b);
        if (n == null) {
            g.setColor(UNKNOWN); g.setStroke(dashed(1.2));
            g.draw(new RoundRectangle2D.Double(r.x, r.y, r.width, r.height, 4, 4));
        } else if (n.isGateway()) {
            drawGateway(g, r, n, o);
        } else if (n.isEvent()) {
            drawEvent(g, r, n);
        } else {
            drawActivity(g, r, n, b, o);
        }
    }

    private static void drawActivity(Graphics2D g, Rectangle2D.Double r, FlowNode n, Bounds b, RenderOptions o) {
        double arc = n.container() ? 14 : 10;
        RoundRectangle2D.Double rr = new RoundRectangle2D.Double(r.x, r.y, r.width, r.height, arc, arc);
        fillOutline(g, rr, n.container() ? SCOPE_FILL : SHAPE_FILL, SHAPE_LINE, 1.6);
        if ("callActivity".equals(n.type())) { g.setStroke(stroke(3.5)); g.draw(rr); }
        MultiInstance mi = n.multiInstance();
        if (mi != null) {
            double cx = r.x + r.width / 2, by = r.y + r.height - 6;
            g.setColor(SHAPE_LINE); g.setStroke(stroke(1.6));
            for (int k = -1; k <= 1; k++) {
                if (mi.sequential()) g.draw(new Line2D.Double(cx - 6, by + k * 4 - 4, cx + 6, by + k * 4 - 4));
                else g.draw(new Line2D.Double(cx + k * 5, by - 10, cx + k * 5, by));
            }
        }
        if (n.container() && !b.expanded()) {
            g.setFont(o.markerFont());
            textBlock(g, "+", r.x + r.width / 2, r.y + r.height - 14, 20, true, SHAPE_LINE);
        }
    }

    private static void drawGateway(Graphics2D g, Rectangle2D.Double r, FlowNode n, RenderOptions o) {
        double cx = r.x + r.width / 2, cy = r.y + r.height / 2;
        Path2D.Double p = new Path2D.Double();
        p.moveTo(cx, r.y); p.lineTo(r.x + r.width, cy); p.lineTo(cx, r.y + r.height); p.lineTo(r.x, cy); p.closePath();
        fillOutline(g, p, SHAPE_FILL, SHAPE_LINE, 1.6);
        g.setFont(o.markerFont());
        switch (n.type()) {
            case "exclusiveGateway" -> textBlock(g, "X", cx, cy, r.width, true, SHAPE_LINE);
            case "parallelGateway" -> textBlock(g, "+", cx, cy, r.width, true, SHAPE_LINE);
            case "inclusiveGateway" -> { g.setStroke(stroke(2.0)); g.draw(new Ellipse2D.Double(cx - 9, cy - 9, 18, 18)); }
            case "eventBasedGateway" -> {
                g.setStroke(stroke(1.4));
                g.draw(new Ellipse2D.Double(cx - 9, cy - 9, 18, 18));
                g.draw(new Ellipse2D.Double(cx - 6, cy - 6, 12, 12));
            }
            case "complexGateway" -> textBlock(g, "*", cx, cy, r.width, true, SHAPE_LINE);
            default -> { }
        }
    }

    private static void drawEvent(Graphics2D g, Rectangle2D.Double r, FlowNode n) {
        String type = n.type();
        fillOutline(g, new Ellipse2D.Double(r.x, r.y, r.width, r.height), Color.WHITE, SHAPE_LINE, "endEvent".equals(type) ? 3.5 : 1.6);
        if (type.startsWith("intermediate") || "boundaryEvent".equals(type)) {
            g.setStroke(stroke(1.6));
            g.draw(new Ellipse2D.Double(r.x + 3, r.y + 3, r.width - 6, r.height - 6));
        }
        Set<String> defs = n.eventDefs();
        double cx = r.x + r.width / 2, cy = r.y + r.height / 2, rad = Math.min(r.width, r.height) / 4;
        g.setColor(SHAPE_LINE); g.setStroke(stroke(1.4));
        if (defs.contains("timerEventDefinition")) {
            g.draw(new Ellipse2D.Double(cx - rad, cy - rad, 2 * rad, 2 * rad));
            g.draw(new Line2D.Double(cx, cy, cx, cy - 0.8 * rad));
            g.draw(new Line2D.Double(cx, cy, cx + 0.6 * rad, cy));
        } else if (defs.contains("messageEventDefinition")) {
            double mw = 1.6 * rad, mh = 1.1 * rad, lx = cx - mw / 2, ty = cy - mh / 2;
            g.draw(new Rectangle2D.Double(lx, ty, mw, mh));
            g.draw(new Line2D.Double(lx, ty, cx, cy));
            g.draw(new Line2D.Double(lx + mw, ty, cx, cy));
        } else if (defs.contains("terminateEventDefinition")) {
            g.fill(new Ellipse2D.Double(cx - rad, cy - rad, 2 * rad, 2 * rad));
        } else if (defs.contains("signalEventDefinition")) {
            Path2D.Double tri = new Path2D.Double();
            tri.moveTo(cx, cy - rad); tri.lineTo(cx + rad, cy + rad); tri.lineTo(cx - rad, cy + rad); tri.closePath();
            g.draw(tri);
        } else {
            String letter = defs.contains("errorEventDefinition") ? "!" : defs.contains("escalationEventDefinition") ? "^"
                    : defs.contains("conditionalEventDefinition") ? "?" : defs.contains("compensateEventDefinition") ? "<<"
                    : defs.contains("linkEventDefinition") ? ">" : null;
            if (letter != null) {
                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
                textBlock(g, letter, cx, cy, r.width, true, SHAPE_LINE);
            }
        }
    }

    private static void drawAnnotation(Graphics2D g, Rectangle2D.Double r, String text, RenderOptions o) {
        g.setColor(SHAPE_LINE); g.setStroke(stroke(1.2));
        Path2D.Double p = new Path2D.Double();
        p.moveTo(r.x + 10, r.y); p.lineTo(r.x, r.y); p.lineTo(r.x, r.y + r.height); p.lineTo(r.x + 10, r.y + r.height);
        g.draw(p);
        g.setFont(o.font());
        FontMetrics fm = g.getFontMetrics();
        List<String> lines = wrap(fm, text, r.width - 12);
        g.setColor(TEXT);
        for (int i = 0; i < lines.size(); i++) {
            g.drawString(lines.get(i), (int) (r.x + 8), (int) (r.y + 4 + fm.getAscent() + i * fm.getHeight()));
        }
    }

    // -----------------------------------------------------------------------
    // Edges
    // -----------------------------------------------------------------------

    static void polyline(Graphics2D g, double[][] pts, Color color, double w) {
        g.setColor(color); g.setStroke(stroke(w));
        for (int i = 1; i < pts.length; i++) g.draw(new Line2D.Double(pts[i - 1][0], pts[i - 1][1], pts[i][0], pts[i][1]));
    }

    static void arrowhead(Graphics2D g, double[] a, double[] b, Color color) {
        double dx = b[0] - a[0], dy = b[1] - a[1], len = Math.hypot(dx, dy);
        if (len == 0) return;
        double ux = dx / len, uy = dy / len, size = 10, half = 4.5;
        double bx = b[0] - ux * size, by = b[1] - uy * size;
        Polygon p = new Polygon();
        p.addPoint((int) Math.round(b[0]), (int) Math.round(b[1]));
        p.addPoint((int) Math.round(bx - uy * half), (int) Math.round(by + ux * half));
        p.addPoint((int) Math.round(bx + uy * half), (int) Math.round(by - ux * half));
        g.setColor(color); g.fill(p);
    }

    private static void drawEdge(Graphics2D g, Transform t, Bounds b, SequenceFlow f, RenderOptions o) {
        double[][] pts = t.polyline(b);
        if (pts.length < 2) return;
        polyline(g, pts, EDGE_LINE, 1.6);
        arrowhead(g, pts[pts.length - 2], pts[pts.length - 1], EDGE_LINE);

        double x1 = pts[0][0], y1 = pts[0][1], dx = pts[1][0] - x1, dy = pts[1][1] - y1;
        double len = Math.max(1, Math.hypot(dx, dy)), ux = dx / len, uy = dy / len;
        if (f.isDefault()) {
            double sx = x1 + ux * 9, sy = y1 + uy * 9;
            g.draw(new Line2D.Double(sx - uy * 5 - ux * 3, sy + ux * 5 - uy * 3, sx + uy * 5 + ux * 3, sy - ux * 5 + uy * 3));
        } else if (f.conditional()) {
            double cx = x1 + ux * 8, cy = y1 + uy * 8;
            Path2D.Double d = new Path2D.Double();
            d.moveTo(cx - ux * 8, cy - uy * 8); d.lineTo(cx - uy * 4, cy + ux * 4);
            d.lineTo(cx + ux * 8, cy + uy * 8); d.lineTo(cx + uy * 4, cy - ux * 4); d.closePath();
            fillOutline(g, d, Color.WHITE, EDGE_LINE, 1.4);
        }
        if (f.name() != null) {
            g.setFont(o.font());
            double lx, ly;
            if (b.label() != null) {
                lx = t.x(b.label().x() + b.label().width() / 2); ly = t.y(b.label().y());
            } else {
                double[] mid = pts[pts.length / 2], prev = pts[Math.max(0, pts.length / 2 - 1)];
                lx = (mid[0] + prev[0]) / 2; ly = (mid[1] + prev[1]) / 2 - 16;
            }
            textBlock(g, f.name(), lx, ly, 140, false, TEXT);
        }
    }

    private static void drawAssociation(Graphics2D g, Transform t, Bounds b) {
        double[][] pts = t.polyline(b);
        g.setColor(SHAPE_LINE); g.setStroke(dashed(1.2));
        for (int i = 1; i < pts.length; i++) g.draw(new Line2D.Double(pts[i - 1][0], pts[i - 1][1], pts[i][0], pts[i][1]));
    }

    // -----------------------------------------------------------------------
    // Labels
    // -----------------------------------------------------------------------

    private static void drawLabel(Graphics2D g, Transform t, Bounds b, FlowNode n, RenderOptions o) {
        if (n == null || n.name() == null) return;
        Rectangle2D.Double r = t.rect(b);
        double cx = r.x + r.width / 2;
        g.setFont(o.font());
        if (n.container() && b.expanded()) {
            textBlock(g, n.name(), cx, r.y + 6, r.width - 16, false, TEXT);
        } else if (!n.isGateway() && !n.isEvent()) {
            textBlock(g, n.name(), cx, r.y + r.height / 2, r.width - 12, true, TEXT);
        } else if (b.label() != null) {
            Rectangle2D.Double lr = t.rect(b.label());
            textBlock(g, n.name(), lr.x + lr.width / 2, lr.y, Math.max(90, lr.width), false, TEXT);
        } else {
            textBlock(g, n.name(), cx, r.y + r.height + 4, 120, false, TEXT);
        }
    }
}
