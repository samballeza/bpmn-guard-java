package bpmnguard.report;

import bpmnguard.model.Bounds;
import bpmnguard.model.ProcessDef;
import bpmnguard.rules.Severity;
import bpmnguard.rules.Violation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Text and JSON renderings of a {@link Report}. */
public final class ReportWriter {

    private ReportWriter() {}

    public static String text(Report r) {
        StringBuilder sb = new StringBuilder();
        sb.append("bpmn-guard: ").append(r.source()).append('\n');
        sb.append(String.format("  critical=%d  high=%d  medium=%d%n",
                r.count(Severity.CRITICAL), r.count(Severity.HIGH), r.count(Severity.MEDIUM)));
        for (String w : r.warnings()) sb.append("  warn: ").append(w).append('\n');
        sb.append('\n');
        for (Violation v : r.violations()) {
            String at = v.bounds == null ? " @(no DI)"
                    : String.format(" @(%.0f,%.0f %.0fx%.0f)", v.bounds.x(), v.bounds.y(), v.bounds.width(), v.bounds.height());
            sb.append(String.format("[%s %-8s] %s %s%s%s%n    %s%n    fix: %s%n",
                    v.rule, v.severity, v.elementType, v.elementId, v.kind == null ? "" : " [" + v.kind + "]", at,
                    v.message, v.remediation));
        }
        return sb.toString();
    }

    public static String json(Report r, boolean pretty) {
        return Json.write(toMap(r), pretty);
    }

    public static Map<String, Object> toMap(Report r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", r.source());
        List<Map<String, Object>> ps = new ArrayList<>();
        for (ProcessDef p : r.model().processes()) {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("id", p.id()); pm.put("name", p.name()); pm.put("executable", p.executable());
            ps.add(pm);
        }
        m.put("processes", ps);
        Map<String, Object> summary = new LinkedHashMap<>();
        for (Severity s : Severity.values()) summary.put(s.name().toLowerCase(), r.count(s));
        m.put("summary", summary);
        m.put("warnings", r.warnings());
        List<Map<String, Object>> vs = new ArrayList<>();
        for (Violation v : r.violations()) vs.add(toMap(v));
        m.put("violations", vs);
        return m;
    }

    public static Map<String, Object> toMap(Violation v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rule", v.rule.name());
        m.put("title", v.rule.title);
        m.put("severity", v.severity.name().toLowerCase());
        m.put("kind", v.kind);
        m.put("elementId", v.elementId);
        m.put("elementType", v.elementType);
        m.put("elementName", v.elementName);
        m.put("scope", v.scope);
        m.put("message", v.message);
        m.put("remediation", v.remediation);
        m.put("related", v.related);
        m.put("presentation", v.presentation.name().toLowerCase());
        m.put("bounds", bounds(v.bounds));
        Map<String, Object> rb = new LinkedHashMap<>();
        v.relatedBounds.forEach((k, b) -> rb.put(k, bounds(b)));
        m.put("relatedBounds", rb);
        m.put("evidence", v.evidence);
        return m;
    }

    public static Map<String, Object> bounds(Bounds b) {
        if (b == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("x", b.x()); m.put("y", b.y()); m.put("width", b.width()); m.put("height", b.height());
        m.put("kind", b.kind().name().toLowerCase());
        if (b.isEdge()) m.put("waypoints", b.waypoints());
        return m;
    }
}
