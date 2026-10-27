package bpmnguard.report;

import bpmnguard.graph.ProcessGraph;
import bpmnguard.model.Bounds;
import bpmnguard.model.Diagram;
import bpmnguard.parse.BpmnParser;
import bpmnguard.rules.RuleOptions;
import bpmnguard.rules.Rules;
import bpmnguard.rules.Violation;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Public entry point: parse, build the graph, run every rule, attach DI geometry.
 *
 * <pre>
 *   Report r = Validator.validate(Path.of("order.bpmn"), RuleOptions.DEFAULT);
 * </pre>
 * Throws {@link BpmnParser.BpmnParseException} on unreadable or malformed input.
 */
public final class Validator {

    private Validator() {}

    public static Report validate(Path file, RuleOptions opts) {
        return build(BpmnParser.parse(file), file.toString(), opts);
    }

    public static Report validate(InputStream in, String sourceName, RuleOptions opts) {
        return build(BpmnParser.parse(in, sourceName), sourceName, opts);
    }

    private static Report build(BpmnParser.Parsed parsed, String source, RuleOptions opts) {
        Diagram di = parsed.diagram();
        ProcessGraph g = new ProcessGraph(parsed.model());
        List<Violation> raw = Rules.runAll(g, opts);

        List<Violation> vs = new ArrayList<>();
        List<String> noDi = new ArrayList<>();
        for (Violation v : raw) {
            Map<String, Bounds> rel = new LinkedHashMap<>();
            for (String id : v.related) {
                Bounds b = di.get(id);
                if (b != null) rel.put(id, b);
            }
            Bounds own = di.get(v.elementId);
            if (own == null) noDi.add(v.elementId);
            vs.add(v.withGeometry(own, rel));
        }

        List<String> warnings = new ArrayList<>(parsed.model().warnings());
        warnings.addAll(di.warnings());
        if (!di.isEmpty() && !noDi.isEmpty()) warnings.add("No DI shape for flagged element(s): " + noDi);

        return new Report(source, parsed.model(), di, List.copyOf(vs), Report.summarise(vs), List.copyOf(warnings));
    }
}
