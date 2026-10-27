package bpmnguard.rules;

import bpmnguard.graph.ProcessGraph;
import bpmnguard.model.FlowNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** R7 — every immediate target of an eventBasedGateway must be a catching intermediate event or a receiveTask. */
final class Rule7EventBasedGatewayTargets implements Rule {

    private static final Set<String> VALID_DEFS = Set.of(
            "messageEventDefinition", "timerEventDefinition", "signalEventDefinition", "conditionalEventDefinition");

    @Override
    public List<Violation> apply(ProcessGraph g, RuleOptions opts) {
        List<Violation> out = new ArrayList<>();
        for (FlowNode ebg : g.model().nodes().values()) {
            if (!"eventBasedGateway".equals(ebg.type())) continue;
            for (ProcessGraph.Edge e : g.outEdges(ebg.id())) {
                FlowNode tgt = g.node(e.target());
                boolean ok = "receiveTask".equals(tgt.type())
                        || ("intermediateCatchEvent".equals(tgt.type()) && tgt.eventDefs().stream().anyMatch(VALID_DEFS::contains));
                if (ok) continue;
                out.add(Rules.at(g, RuleId.R7, Severity.CRITICAL, tgt.id())
                        .message(String.format("eventBasedGateway %s leads directly to %s (%s) via %s. The engine requires every target of an "
                                        + "event-based gateway to be a catching intermediate event or a receiveTask; deployment is rejected "
                                        + "or the gateway can never fire.", ebg.label(), tgt.label(), tgt.type(), e.id()))
                        .related(List.of(ebg.id(), e.id()))
                        .evidence("gateway", ebg.id()).evidence("flow", e.id()).evidence("targetType", tgt.type())
                        .evidence("targetEventDefs", new ArrayList<>(tgt.eventDefs()))
                        .build());
            }
        }
        return out;
    }
}
