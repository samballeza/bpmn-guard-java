package bpmnguard.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** All DI geometry of a file, keyed by bpmnElement id. Empty when the file has no DI. */
public final class Diagram {

    public record Extent(double minX, double minY, double maxX, double maxY) {
        public double width() { return maxX - minX; }
        public double height() { return maxY - minY; }
    }

    private final Map<String, Bounds> byId;
    private final List<String> warnings;

    public Diagram(Map<String, Bounds> byId, List<String> warnings) {
        this.byId = Collections.unmodifiableMap(new LinkedHashMap<>(byId));
        this.warnings = List.copyOf(warnings);
    }

    public static Diagram empty(String warning) {
        return new Diagram(Map.of(), List.of(warning));
    }

    public boolean isEmpty() { return byId.isEmpty(); }
    public Bounds get(String id) { return byId.get(id); }
    public Map<String, Bounds> all() { return byId; }
    public List<String> warnings() { return warnings; }

    public Map<String, Bounds> shapes() {
        return byId.entrySet().stream().filter(e -> e.getValue().isShape())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
    }

    public Map<String, Bounds> edges() {
        return byId.entrySet().stream().filter(e -> e.getValue().isEdge())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
    }

    /** Bounding box of every shape, waypoint and label; null when there is no DI. */
    public Extent extent() {
        if (byId.isEmpty()) return null;
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (Bounds b : byId.values()) {
            for (Bounds r : b.label() == null ? List.of(b) : List.of(b, b.label())) {
                minX = Math.min(minX, r.x()); minY = Math.min(minY, r.y());
                maxX = Math.max(maxX, r.maxX()); maxY = Math.max(maxY, r.maxY());
            }
        }
        return new Extent(minX, minY, maxX, maxY);
    }
}
