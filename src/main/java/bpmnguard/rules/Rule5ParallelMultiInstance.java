package bpmnguard.rules;

import bpmnguard.graph.ProcessGraph;
import bpmnguard.model.FlowNode;
import bpmnguard.model.MultiInstance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** R5 — parallel multi-instance without an async save point. */
final class Rule5ParallelMultiInstance implements Rule {

    @Override
    public List<Violation> apply(ProcessGraph g, RuleOptions opts) {
        List<Violation> out = new ArrayList<>();
        for (FlowNode n : g.model().nodes().values()) {
            MultiInstance mi = n.multiInstance();
            if (mi == null || mi.sequential() || n.asyncBefore()) continue;
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("sequential", false); ev.put("collection", mi.collection()); ev.put("elementVariable", mi.elementVariable());
            out.add(Rules.at(g, RuleId.R5, Severity.CRITICAL, n.id())
                    .message(String.format("%s is a parallel multi-instance %s without camunda:asyncBefore=\"true\". All instances over %s "
                                    + "are created in the caller's thread and transaction; a large collection exhausts heap and the JDBC "
                                    + "transaction in one shot.",
                            n.label(), n.type(), mi.collection() == null ? "the collection" : "collection '" + mi.collection() + "'"))
                    .evidence("multiInstance", ev).build());
        }
        return out;
    }
}
