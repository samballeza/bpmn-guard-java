package bpmnguard.rules;

import bpmnguard.graph.ProcessGraph;
import bpmnguard.model.FlowNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * R4 — XOR / OR split converging on an AND join.
 * For each diverging exclusive/inclusive gateway compute the reachable set of every
 * branch (never re-entering the split). For each parallel join in that cone count which
 * join inputs each branch feeds; if no single branch guarantees every input, the join
 * deadlocks on the untaken branch.
 */
final class Rule4GatewayMismatch implements Rule {

    @Override
    public List<Violation> apply(ProcessGraph g, RuleOptions opts) {
        List<Violation> out = new ArrayList<>();
        for (FlowNode split : g.model().nodes().values()) {
            boolean xor = "exclusiveGateway".equals(split.type());
            if (!(xor || "inclusiveGateway".equals(split.type())) || g.outDegree(split.id()) <= 1) continue;

            Map<ProcessGraph.Edge, Set<String>> reach = new LinkedHashMap<>();
            Set<String> cone = new LinkedHashSet<>();
            for (ProcessGraph.Edge b : g.outEdges(split.id())) {
                Set<String> r = g.reachableFrom(b.target(), Set.of(split.id()));
                reach.put(b, r);
                cone.addAll(r);
            }
            for (FlowNode join : g.model().nodes().values()) {
                if (!"parallelGateway".equals(join.type()) || g.inDegree(join.id()) <= 1 || !cone.contains(join.id())) continue;
                List<ProcessGraph.Edge> ins = g.inEdges(join.id());
                Map<ProcessGraph.Edge, Set<ProcessGraph.Edge>> feeds = new LinkedHashMap<>();
                for (ProcessGraph.Edge b : reach.keySet()) {
                    Set<ProcessGraph.Edge> fs = new LinkedHashSet<>();
                    for (ProcessGraph.Edge in : ins) if (reach.get(b).contains(in.source())) fs.add(in);
                    if (!fs.isEmpty()) feeds.put(b, fs);
                }
                if (feeds.isEmpty() || feeds.values().stream().allMatch(fs -> fs.size() == ins.size())) continue;

                int nFeeding = feeds.size();
                int bypassing = reach.size() - nFeeding;
                Severity sev = (xor && nFeeding >= 2) ? Severity.CRITICAL : (xor || nFeeding >= 2) ? Severity.HIGH : Severity.MEDIUM;
                String branchDesc = feeds.entrySet().stream()
                        .map(e -> e.getKey().id() + "→" + e.getValue().size() + "/" + ins.size() + " inputs")
                        .collect(Collectors.joining(", "));
                Map<String, Object> coverage = new LinkedHashMap<>();
                for (ProcessGraph.Edge b : reach.keySet()) {
                    coverage.put(b.id(), feeds.getOrDefault(b, Set.of()).stream().map(ProcessGraph.Edge::id).toList());
                }
                List<String> related = new ArrayList<>(List.of(split.id()));
                feeds.keySet().forEach(b -> related.add(b.id()));
                out.add(Rules.at(g, RuleId.R4, sev, join.id())
                        .message(String.format("parallelGateway %s joins %d incoming flows but is fed by %s %s whose branches each activate "
                                        + "only a subset (%s)%s. The AND join waits for every incoming flow, so with a %s choice at least "
                                        + "one token waits forever: deterministic deadlock.",
                                join.label(), ins.size(), split.type(), split.label(), branchDesc,
                                bypassing > 0 ? ", and " + bypassing + " branch(es) bypass the join entirely" : "",
                                xor ? "single-branch" : "partial"))
                        .related(related)
                        .evidence("split", split.id()).evidence("splitType", split.type())
                        .evidence("joinInputs", ins.size()).evidence("branchCoverage", coverage)
                        .build());
            }
        }
        return out;
    }
}
