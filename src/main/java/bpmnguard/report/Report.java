package bpmnguard.report;

import bpmnguard.model.BpmnModel;
import bpmnguard.model.Diagram;
import bpmnguard.rules.Severity;
import bpmnguard.rules.Violation;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** The outcome of validating one file: model, diagram, findings, summary, warnings. */
public record Report(String source, BpmnModel model, Diagram diagram, List<Violation> violations,
                     Map<Severity, Integer> summary, List<String> warnings) {

    public int count(Severity s) { return summary.getOrDefault(s, 0); }

    public static Map<Severity, Integer> summarise(List<Violation> vs) {
        Map<Severity, Integer> m = new EnumMap<>(Severity.class);
        for (Severity s : Severity.values()) m.put(s, 0);
        for (Violation v : vs) m.merge(v.severity, 1, Integer::sum);
        return m;
    }

    /** Exit code convention: 2 on critical, 1 on high (when failOnHigh), else 0. */
    public int exitCode(boolean failOnHigh) {
        if (count(Severity.CRITICAL) > 0) return 2;
        if (failOnHigh && count(Severity.HIGH) > 0) return 1;
        return 0;
    }
}
