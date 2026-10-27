package bpmnguard.rules;

import bpmnguard.graph.ProcessGraph;
import bpmnguard.model.FlowNode;
import bpmnguard.model.SequenceFlow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * R2 — scope-boundary bypass and orphaned tokens.
 * (a) sequence flow whose source and target live in different scopes;
 * (b) boundary event declared in a different container than its host;
 * (c) boundary-event path that re-enters its host's body without passing through the host;
 * (d) non-end node with no outgoing flow (its token ends silently).
 */
final class Rule2ScopeBypass implements Rule {

    @Override
    public List<Violation> apply(ProcessGraph g, RuleOptions opts) {
        List<Violation> out = new ArrayList<>();
        Map<String, FlowNode> nodes = g.model().nodes();

        for (SequenceFlow f : g.model().flows().values()) {
            FlowNode src = nodes.get(f.source()), tgt = nodes.get(f.target());
            if (src.scope().equals(tgt.scope())) continue;
            out.add(Rules.at(g, RuleId.R2, Severity.CRITICAL, f.id())
                    .message(String.format("sequenceFlow %s crosses from scope '%s' into scope '%s' (%s -> %s). A token leaving a "
                                    + "sub-process through a plain sequence flow never completes the sub-process scope execution; "
                                    + "the engine leaves it active in ACT_RU_EXECUTION.",
                            f.id(), src.scope(), tgt.scope(), src.label(), tgt.label()))
                    .related(List.of(f.source(), f.target()))
                    .evidence("sourceScope", src.scope()).evidence("targetScope", tgt.scope())
                    .build());
        }

        for (FlowNode n : nodes.values()) {
            if (!"boundaryEvent".equals(n.type())) continue;
            FlowNode host = n.attachedTo() == null ? null : nodes.get(n.attachedTo());
            if (host == null) continue;
            if (!host.scope().equals(n.scope())) {
                out.add(Rules.at(g, RuleId.R2, Severity.CRITICAL, n.id())
                        .message(String.format("boundaryEvent %s is attached to %s but declared in scope '%s' instead of '%s'.",
                                n.id(), host.label(), n.scope(), host.scope()))
                        .related(List.of(host.id())).evidence("attachedTo", host.id()).build());
            }
            Set<String> inside = g.descendantIds(host.id());
            Set<String> reach = g.reachableFrom(n.id(), Set.of(host.id()));
            reach.remove(n.id());
            Set<String> hit = new TreeSet<>();
            for (String r : reach) if (inside.contains(r)) hit.add(r);
            if (!hit.isEmpty()) {
                List<String> rel = new ArrayList<>(List.of(host.id()));
                rel.addAll(hit);
                out.add(Rules.at(g, RuleId.R2, Severity.CRITICAL, n.id())
                        .message(String.format("boundaryEvent %s on %s routes back into the host's body (%s) without passing through "
                                        + "the host — the body scope is re-entered while its original scope execution has been cancelled/left behind.",
                                n.id(), host.label(), hit))
                        .related(rel).evidence("host", host.id()).evidence("reEntered", new ArrayList<>(hit)).build());
            }
        }

        for (FlowNode n : nodes.values()) {
            if (g.outDegree(n.id()) != 0 || "endEvent".equals(n.type()) || n.eventSubprocess()
                    || n.eventDefs().contains("compensateEventDefinition") || "boundaryEvent".equals(n.type())) continue;
            out.add(Rules.at(g, RuleId.R2, Severity.HIGH, n.id())
                    .message(String.format("%s has no outgoing sequence flow and is not an end event. Its token ends silently; if a "
                            + "sibling branch is still active the enclosing scope '%s' can never complete.", n.label(), n.scope()))
                    .evidence("outDegree", 0).build());
        }
        return out;
    }
}
