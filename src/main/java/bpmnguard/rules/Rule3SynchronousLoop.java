package bpmnguard.rules;

import bpmnguard.graph.ProcessGraph;
import bpmnguard.model.FlowNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * R3 — synchronous infinite loops.
 *
 * Exact and polynomial: delete every vertex that is a wait state or carries an async
 * continuation; any strongly connected component left in the residual graph is, by
 * construction, a cycle with zero wait states and zero save points. Sample cycle
 * paths are then enumerated per component (bounded) for the evidence.
 */
final class Rule3SynchronousLoop implements Rule {

    @Override
    public List<Violation> apply(ProcessGraph g, RuleOptions opts) {
        Set<String> residual = new LinkedHashSet<>();
        for (FlowNode n : g.model().nodes().values()) {
            if (!n.isWaitState() && !n.isAsyncBoundary()) residual.add(n.id());
        }
        List<Violation> out = new ArrayList<>();
        for (Set<String> scc : g.stronglyConnectedSets(residual, e -> true)) {
            List<String> ordered = new ArrayList<>(scc);
            ProcessGraph.CycleSearch cs = scc.size() <= opts.maxSccEnumeration()
                    ? g.simpleCycles(scc, opts.maxCyclesPerScc())
                    : new ProcessGraph.CycleSearch(List.of(), true);
            for (String id : ordered) {
                out.add(Rules.at(g, RuleId.R3, Severity.CRITICAL, id)
                        .message(String.format("%s lies on a synchronous cycle %s that contains no wait state (userTask/receiveTask/catch "
                                        + "event/external task) and no camunda:asyncBefore/asyncAfter. The engine will spin inside one "
                                        + "thread and one transaction until StackOverflowError or OOM.", Rules.label(g, id), ordered))
                        .related(ordered)
                        .evidence("scc", ordered).evidence("sampleCycles", cs.cycles()).evidence("cyclesTruncated", cs.truncated())
                        .build());
            }
        }
        return out;
    }
}
