package bpmnguard.render;

import bpmnguard.model.Bounds;
import bpmnguard.model.FlowNode;
import bpmnguard.report.Report;
import bpmnguard.rules.RuleId;
import bpmnguard.rules.Violation;

import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;

/**
 * Draws findings over the base diagram and exposes the pixel regions it drew so a UI
 * can map clicks back to findings.
 * <ul>
 *   <li>GROUP findings (R1 inner loops): one dashed outline around the union of the
 *       related shapes, one badge.</li>
 *   <li>shapes: a tight halo (rounded rect for activities, circle for events/gateways),
 *       ONE badge per element listing every rule that hit it.</li>
 *   <li>sequence flows: re-stroked thick in colour along their waypoints.</li>
 * </ul>
 */
public final class ViolationOverlay {

    public record HitRegion(int index, Rectangle2D.Double rect) {}

    private static final Color OOM = new Color(215, 25, 25);
    private static final Color DEADLOCK = new Color(240, 125, 0);
    private static final Color STARVATION = new Color(225, 175, 0);

    private ViolationOverlay() {}

    public static Color color(RuleId rule) {
        return switch (rule.colorClass) {
            case OOM -> OOM;
            case DEADLOCK -> DEADLOCK;
            case STARVATION -> STARVATION;
        };
    }

    public static String badgeText(RuleId rule) {
        return "[" + rule.name() + ": " + rule.title + "]";
    }

    // -----------------------------------------------------------------------
    // Public pipeline
    // -----------------------------------------------------------------------

    /** Base diagram drawn from the report's DI, then the findings on top. */
    public static BufferedImage renderReport(Report r, RenderOptions o) {
        BufferedImage img = DiagramRenderer.render(r.model(), r.diagram(), o);
        Transform t = new Transform(r.diagram(), o);
        Graphics2D g = img.createGraphics();
        try {
            DiagramRenderer.quality(g);
            overlay(g, t, r, o);
        } finally {
            g.dispose();
        }
        return img;
    }

    /** Legacy: overlay onto an externally rendered PNG whose origin is the DI extent (bpmn-to-image default). */
    public static BufferedImage annotate(BufferedImage base, Report r, RenderOptions o) {
        RenderOptions opts = o.withPadding(0);
        Transform t = new Transform(r.diagram(), opts);
        BufferedImage out = new BufferedImage(base.getWidth(), base.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            DiagramRenderer.quality(g);
            g.drawImage(base, 0, 0, null);
            overlay(g, t, r, opts);
        } finally {
            g.dispose();
        }
        return out;
    }

    public static void writePng(BufferedImage img, Path target) throws IOException {
        if (!ImageIO.write(img, "png", target.toFile())) throw new IOException("No PNG writer available in this JVM");
    }

    public static void writePng(BufferedImage img, OutputStream out) throws IOException {
        if (!ImageIO.write(img, "png", out)) throw new IOException("No PNG writer available in this JVM");
    }

    public static BufferedImage readImage(File f) throws IOException {
        BufferedImage img = ImageIO.read(f);
        if (img == null) throw new IOException("ImageIO could not decode " + f);
        return img;
    }

    // -----------------------------------------------------------------------
    // Overlay
    // -----------------------------------------------------------------------

    private static Rectangle2D.Double grow(Rectangle2D.Double r, double p) {
        return new Rectangle2D.Double(r.x - p, r.y - p, r.width + 2 * p, r.height + 2 * p);
    }

    private static Rectangle2D.Double union(List<Rectangle2D.Double> rs) {
        Rectangle2D.Double u = null;
        for (Rectangle2D.Double r : rs) { if (u == null) u = (Rectangle2D.Double) r.clone(); else Rectangle2D.union(u, r, u); }
        return u;
    }

    private static List<Rectangle2D.Double> groupRects(Transform t, Violation v) {
        Map<String, Bounds> all = new LinkedHashMap<>(v.relatedBounds);
        if (v.bounds != null) all.put(v.elementId, v.bounds);
        List<Rectangle2D.Double> rs = new ArrayList<>();
        for (Bounds b : all.values()) if (b.isShape()) rs.add(t.rect(b));
        return rs;
    }

    static void overlay(Graphics2D g, Transform t, Report r, RenderOptions o) {
        Map<String, FlowNode> nodes = r.model().nodes();
        List<Violation> vs = r.violations();

        // groups first (underneath)
        for (Violation v : vs) {
            if (!v.isGroup()) continue;
            List<Rectangle2D.Double> rs = groupRects(t, v);
            if (rs.isEmpty()) continue;
            Rectangle2D.Double u = grow(union(rs), 14 + o.haloPadding());
            Color c = color(v.rule);
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 28));
            g.fill(new RoundRectangle2D.Double(u.x, u.y, u.width, u.height, 18, 18));
            g.setColor(c); g.setStroke(DiagramRenderer.dashed(o.stroke()));
            g.draw(new RoundRectangle2D.Double(u.x, u.y, u.width, u.height, 18, 18));
            if (o.badges()) {
                FontMetrics fm = g.getFontMetrics(o.badgeFont());
                badge(g, "[" + v.rule.name() + ": loop ×" + v.related.size() + "]", u.x, u.y - 2 - fm.getHeight(), c, o);
            }
        }

        // single elements: one halo + one badge per element, most severe first
        Map<String, List<Violation>> byElement = vs.stream().filter(v -> !v.isGroup() && v.bounds != null)
                .collect(Collectors.groupingBy(v -> v.elementId, LinkedHashMap::new, Collectors.toList()));
        for (Map.Entry<String, List<Violation>> e : byElement.entrySet()) {
            List<Violation> list = new ArrayList<>(e.getValue());
            list.sort(Comparator.comparingInt(v -> v.severity.rank()));
            Violation lead = list.get(0);
            Bounds b = lead.bounds;
            Color c = color(lead.rule);
            Rectangle2D.Double rect = t.rect(b);
            double bx, by;
            if (b.isEdge()) {
                double[][] pts = t.polyline(b);
                DiagramRenderer.polyline(g, pts, new Color(c.getRed(), c.getGreen(), c.getBlue(), 200), 3 + o.stroke());
                DiagramRenderer.arrowhead(g, pts[pts.length - 2], pts[pts.length - 1], c);
                double[] mid = pts[pts.length / 2];
                bx = mid[0] + 6; by = mid[1] - 6;
            } else {
                halo(g, nodes.get(e.getKey()), rect, c, o);
                FontMetrics fm = g.getFontMetrics(o.badgeFont());
                bx = rect.x + rect.width - o.haloPadding() + 2;
                by = rect.y - o.haloPadding() - 2 - fm.getHeight();
            }
            if (o.badges()) badge(g, combinedBadge(list), bx, by, c, o);
        }
    }

    private static void halo(Graphics2D g, FlowNode n, Rectangle2D.Double r, Color c, RenderOptions o) {
        g.setColor(c); g.setStroke(DiagramRenderer.stroke(o.stroke()));
        double pad = o.haloPadding();
        boolean round = n == null || !(n.isEvent() || n.isGateway());
        if (round) {
            g.draw(new RoundRectangle2D.Double(r.x - pad, r.y - pad, r.width + 2 * pad, r.height + 2 * pad, 14, 14));
        } else {
            double cx = r.x + r.width / 2, cy = r.y + r.height / 2, rad = Math.hypot(r.width, r.height) / 2 + pad / 2;
            g.draw(new Ellipse2D.Double(cx - rad, cy - rad, 2 * rad, 2 * rad));
        }
    }

    private static String combinedBadge(List<Violation> list) {
        List<RuleId> rules = list.stream().map(v -> v.rule).distinct().toList();
        if (rules.size() == 1) return badgeText(rules.get(0));
        return "[" + rules.stream().map(RuleId::name).collect(Collectors.joining("+")) + ": " + list.get(0).rule.title + "]";
    }

    private static void badge(Graphics2D g, String text, double x, double y, Color c, RenderOptions o) {
        g.setFont(o.badgeFont());
        FontMetrics fm = g.getFontMetrics();
        int w = fm.stringWidth(text) + 10, h = fm.getHeight() + 4;
        g.setColor(c);
        g.fill(new RoundRectangle2D.Double(x, y, w, h, 8, 8));
        g.setColor(Color.WHITE);
        g.drawString(text, (int) Math.round(x + 5), (int) Math.round(y + 2 + fm.getAscent()));
    }

    // -----------------------------------------------------------------------
    // Hit testing
    // -----------------------------------------------------------------------

    /** Regions matching what {@link #overlay} drew; index = position in report.violations(). */
    public static List<HitRegion> hitRegions(Report r, RenderOptions o) {
        Transform t = new Transform(r.diagram(), o);
        List<HitRegion> out = new ArrayList<>();
        List<Violation> vs = r.violations();
        for (int i = 0; i < vs.size(); i++) {
            Violation v = vs.get(i);
            if (v.isGroup() || v.bounds == null) continue;
            out.add(new HitRegion(i, grow(t.rect(v.bounds), 4 + o.haloPadding())));
        }
        for (int i = 0; i < vs.size(); i++) {
            Violation v = vs.get(i);
            if (!v.isGroup()) continue;
            List<Rectangle2D.Double> rs = groupRects(t, v);
            if (!rs.isEmpty()) out.add(new HitRegion(i, grow(union(rs), 14 + o.haloPadding())));
        }
        return out;
    }

    /** Index of the finding under (px, py), preferring the smallest region; -1 if none. */
    public static int hitTest(List<HitRegion> regions, double px, double py) {
        return regions.stream().filter(h -> h.rect().contains(px, py))
                .min(Comparator.comparingDouble(h -> h.rect().width * h.rect().height))
                .map(HitRegion::index).orElse(-1);
    }
}
