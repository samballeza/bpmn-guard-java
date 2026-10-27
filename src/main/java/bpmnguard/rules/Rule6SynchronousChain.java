package bpmnguard.rules;

import bpmnguard.graph.ProcessGraph;
import bpmnguard.model.FlowNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * R6 — unbroken synchronous compute chains.
 *
 * Chain graph = compute tasks only, keeping edge u-&gt;v only if neither u's asyncAfter
 * nor v's asyncBefore commits in between. If acyclic, one longest-path pass in
 * topological order yields every maximal chain in O(V+E). A cyclic chain graph is
 * unbounded by definition.
 */
final class Rule6SynchronousChain implements Rule {

    @Override
    public List<Violation> apply(ProcessGraph g, RuleOptions opts) {
        Set<String> compute = new LinkedHashSet<>();
        for (FlowNode n : g.model().nodes().values()) if (n.isComputeTask()) compute.add(n.id());
        if (compute.isEmpty()) return List.of();

        Predicate<ProcessGraph.Edge> chainEdge = e -> compute.contains(e.source()) && compute.contains(e.target())
                && !e.synthetic() && !g.node(e.source()).asyncAfter() && !g.node(e.target()).asyncBefore();

        List<Violation> out = new ArrayList<>();
        List<String> order = g.topologicalOrder(compute, chainEdge);
        if (order == null) {
            for (Set<String> scc : g.stronglyConnectedSets(compute, chainEdge)) {
                out.add(chain(g, new ArrayList<>(scc), Severity.CRITICAL, " Chain is cyclic: unbounded length."));
            }
            return out;
        }

        Map<String, Integer> len = new HashMap<>();
        Map<String, String> prev = new HashMap<>();
        for (String v : order) {
            int best = 1; String from = null;
            for (ProcessGraph.Edge e : g.inEdges(v)) {
                if (!chainEdge.test(e)) continue;
                int cand = len.getOrDefault(e.source(), 1) + 1;
                if (cand > best) { best = cand; from = e.source(); }
            }
            len.put(v, best); prev.put(v, from);
        }
        Set<List<String>> chains = new LinkedHashSet<>();
        for (String v : order) {
            boolean terminal = g.outEdges(v).stream().noneMatch(chainEdge);
            if (len.get(v) >= opts.chainThreshold() && terminal) {
                List<String> c = new ArrayList<>();
                for (String x = v; x != null; x = prev.get(x)) c.add(x);
                Collections.reverse(c);
                chains.add(c);
            }
        }
        for (List<String> c : chains) out.add(chain(g, c, Severity.HIGH, ""));
        return out;
    }

    private static Violation chain(ProcessGraph g, List<String> chain, Severity sev, String extra) {
        return Rules.at(g, RuleId.R6, sev, chain.get(chain.size() - 1))
                .message(String.format("Chain of %d consecutive synchronous compute tasks %s with no camunda:asyncBefore save point. The "
                                + "whole chain executes in one JDBC transaction holding its connection and row locks; exceeds "
                                + "transaction/lock timeouts under load.%s", chain.size(), chain, extra))
                .related(chain).evidence("chain", chain).evidence("length", chain.size()).build();
    }
}
