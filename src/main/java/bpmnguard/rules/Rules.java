package bpmnguard.rules;

import bpmnguard.graph.ProcessGraph;
import bpmnguard.model.FlowNode;
import bpmnguard.model.SequenceFlow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Runs every rule and returns the findings sorted by severity, rule, element. */
public final class Rules {

    public static final List<Rule> ALL = List.of(
            new Rule1SequentialMultiInstance(),
            new Rule2ScopeBypass(),
            new Rule3SynchronousLoop(),
            new Rule4GatewayMismatch(),
            new Rule5ParallelMultiInstance(),
            new Rule6SynchronousChain(),
            new Rule7EventBasedGatewayTargets());

    private Rules() {}

    public static List<Violation> runAll(ProcessGraph g, RuleOptions opts) {
        List<Violation> all = new ArrayList<>();
        for (Rule r : ALL) all.addAll(r.apply(g, opts));
        all.sort(Comparator.comparingInt((Violation v) -> v.severity.rank())
                .thenComparing(v -> v.rule)
                .thenComparing(v -> v.elementId));
        return all;
    }

    /** Builder pre-filled with the element's type / name / scope, for a node or a sequence flow id. */
    static Violation.Builder at(ProcessGraph g, RuleId rule, Severity sev, String elementId) {
        Violation.Builder b = Violation.builder(rule, sev, elementId);
        FlowNode n = g.node(elementId);
        if (n != null) return b.element(n.type(), n.name(), n.scope());
        SequenceFlow f = g.model().flow(elementId);
        if (f != null) return b.element("sequenceFlow", f.name(), f.scope());
        return b.element(null, null, null);
    }

    static String label(ProcessGraph g, String id) {
        FlowNode n = g.node(id);
        return n == null ? id : n.label();
    }
}
