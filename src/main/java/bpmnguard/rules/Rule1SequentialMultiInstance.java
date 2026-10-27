package bpmnguard.rules;

import bpmnguard.graph.ProcessGraph;
import bpmnguard.model.FlowNode;
import bpmnguard.model.MultiInstance;
import bpmnguard.model.SequenceFlow;
import bpmnguard.model.VariableWrite;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * R1 — variable accumulation inside a sequential multi-instance body.
 *
 * Per sequential multi-instance container it emits:
 * <ul>
 *   <li>ONE summary finding on the container (critical when payload-sized values are
 *       written) with the per-iteration write count, the loop multipliers and the
 *       resulting write formula;</li>
 *   <li>one finding per writing activity listing exactly which variables it writes,
 *       by which mechanism, and whether each value is payload-sized;</li>
 *   <li>one GROUP finding per inner cycle (drawn as a single outline around the cycle)
 *       stating the back-edge condition, whether the loop looks bounded, and which
 *       writes it re-executes.</li>
 * </ul>
 */
final class Rule1SequentialMultiInstance implements Rule {

    private static final Pattern COUNTER_BOUND = Pattern.compile("[<>]=?\\s*\\d+|\\d+\\s*[<>]=?");

    private record Write(VariableWrite w, String nodeId) {}

    private record Loop(List<String> scc, List<String> path, String anchor, List<String> conditions,
                        String bound, List<String> waits, List<Write> reWrites) {}

    @Override
    public List<Violation> apply(ProcessGraph g, RuleOptions opts) {
        List<Violation> out = new ArrayList<>();
        for (FlowNode sp : g.model().nodes().values()) {
            MultiInstance mi = sp.multiInstance();
            if (!sp.container() || mi == null || !mi.sequential()) continue;
            analyse(g, sp, mi, out);
        }
        return out;
    }

    private void analyse(ProcessGraph g, FlowNode sp, MultiInstance mi, List<Violation> out) {
        Set<String> bodyIds = g.descendantIds(sp.id());
        List<FlowNode> writers = bodyIds.stream().map(g::node).filter(n -> !n.variableWrites().isEmpty()).toList();
        List<Write> allWrites = new ArrayList<>();
        for (FlowNode n : writers) for (VariableWrite w : n.variableWrites()) allWrites.add(new Write(w, n.id()));
        List<Write> payloads = allWrites.stream().filter(w -> w.w().payload()).toList();

        List<Loop> loops = new ArrayList<>();
        for (Set<String> scc : g.stronglyConnectedSets(bodyIds, e -> true)) {
            String anchor = scc.stream()
                    .filter(id -> !g.node(id).variableWrites().isEmpty())
                    .max(Comparator.comparingInt(id -> g.node(id).variableWrites().size()))
                    .orElse(scc.iterator().next());
            List<String> path = cycleOrder(g, scc, anchor);
            List<String> conds = new ArrayList<>();
            for (SequenceFlow f : g.model().flows().values()) {
                if (scc.contains(f.source()) && scc.contains(f.target()) && f.conditional() && f.condition() != null) conds.add(f.condition());
            }
            String bound = conds.stream().filter(c -> COUNTER_BOUND.matcher(c).find()).findFirst().orElse(null);
            List<String> waits = scc.stream().filter(id -> g.node(id).isWaitState()).sorted().toList();
            List<Write> re = allWrites.stream().filter(w -> scc.contains(w.nodeId())).toList();
            loops.add(new Loop(new ArrayList<>(new TreeSet<>(scc)), path, anchor, conds, bound, waits, re));
        }

        if (writers.isEmpty() && loops.isEmpty()) return;

        String collection = mi.collection() == null ? "collection" : mi.collection();
        int nWrites = allWrites.size();
        String formula = loops.isEmpty()
                ? "|" + collection + "| × " + nWrites
                : "|" + collection + "| × (" + nWrites + " + "
                  + loops.stream().map(l -> "retries×" + l.reWrites().size()).collect(Collectors.joining(" + ")) + ")";

        // --- summary on the container ---------------------------------------------
        StringBuilder m = new StringBuilder();
        m.append(String.format("Sequential multi-instance %s iterates over '%s'%s. ", sp.label(),
                mi.collection() == null ? "?" : mi.collection(),
                mi.elementVariable() == null ? "" : " (element variable '" + mi.elementVariable() + "')"));
        if (!writers.isEmpty()) {
            m.append(String.format("Each iteration performs %d non-local variable write(s) from %d activit%s: %s. ",
                    nWrites, writers.size(), writers.size() == 1 ? "y" : "ies",
                    allWrites.stream().map(w -> w.w().display()).collect(Collectors.joining(", "))));
        } else {
            m.append("The body performs no statically visible non-local variable writes. ");
        }
        if (!payloads.isEmpty()) {
            m.append(String.format("%d of them (marked *) carry API responses or request bodies, which are serialised into ACT_GE_BYTEARRAY on every write. ",
                    payloads.size()));
        }
        for (Loop l : loops) {
            m.append(String.format("An inner cycle %s%s re-executes %d of these writes per pass%s. ",
                    l.path(),
                    l.bound() != null ? " bounded by `" + l.bound() + "`" : " with NO counter-based bound visible",
                    l.reWrites().size(),
                    l.waits().isEmpty() ? "" : " (it commits at " + l.waits() + ", so it is not a thread lock)"));
        }
        m.append(String.format("Writes per process instance ≈ %s; every write is also a history row (ACT_HI_VARINST / ACT_HI_DETAIL at FULL history level). ", formula));
        m.append("Variables written with execution.setVariable that do not already exist locally land on the process instance and survive all iterations.");

        List<String> related = new ArrayList<>();
        writers.forEach(w -> related.add(w.id()));
        loops.forEach(l -> related.addAll(l.scc()));
        Violation.Builder summary = Rules.at(g, RuleId.R1, payloads.isEmpty() ? Severity.HIGH : Severity.CRITICAL, sp.id())
                .message(m.toString()).related(related).kind("summary")
                .evidence("collection", mi.collection())
                .evidence("elementVariable", mi.elementVariable())
                .evidence("writes", allWrites.stream().map(Rule1SequentialMultiInstance::writeMap).toList())
                .evidence("payloadWrites", payloads.stream().map(w -> w.w().variable()).toList())
                .evidence("loops", loops.stream().map(Rule1SequentialMultiInstance::loopMap).toList())
                .evidence("formula", formula);
        out.add(summary.build());

        // --- one finding per writing activity ------------------------------------
        for (FlowNode n : writers) {
            List<VariableWrite> ws = n.variableWrites();
            boolean payload = ws.stream().anyMatch(VariableWrite::payload);
            Loop inLoop = loops.stream().filter(l -> l.scc().contains(n.id())).findFirst().orElse(null);
            String msg = String.format("%s writes %d non-local variable(s) on every iteration of %s: %s.%s%s",
                    n.label(), ws.size(), sp.label(),
                    ws.stream().map(VariableWrite::display).collect(Collectors.joining(", ")),
                    payload ? " Starred values are API payloads." : "",
                    inLoop == null ? "" : " This activity also sits on the inner cycle " + inLoop.path() + ", so these writes repeat on every retry.");
            out.add(Rules.at(g, RuleId.R1, payload ? Severity.CRITICAL : Severity.HIGH, n.id())
                    .message(msg).related(List.of(sp.id())).kind("writer")
                    .evidence("loop", sp.id())
                    .evidence("writes", ws.stream().map(w -> writeMap(new Write(w, n.id()))).toList())
                    .build());
        }

        // --- one GROUP finding per inner cycle -----------------------------------
        for (Loop l : loops) {
            String msg = String.format("Inner cycle %s inside sequential multi-instance %s%s. Each pass re-executes %d variable write(s)%s; "
                            + "the effective iteration count is collection size × passes.%s",
                    l.path(), sp.label(),
                    l.bound() != null ? " is bounded by `" + l.bound() + "`" : " has no counter-based bound in any back-edge condition",
                    l.reWrites().size(),
                    l.reWrites().isEmpty() ? "" : ": " + l.reWrites().stream().map(w -> w.w().display()).collect(Collectors.joining(", ")),
                    l.waits().isEmpty() ? " The cycle has no wait state — see R3."
                                        : " The cycle commits at " + l.waits() + ", so it is a variable/history multiplier, not a thread lock.");
            out.add(Rules.at(g, RuleId.R1, Severity.HIGH, l.anchor())
                    .message(msg).related(l.scc()).kind("loop").group()
                    .evidence("loop", sp.id()).evidence("cycle", l.path()).evidence("conditions", l.conditions())
                    .evidence("bound", l.bound()).evidence("reWrites", l.reWrites().stream().map(w -> w.w().variable()).toList())
                    .build());
        }
    }

    /** Walk the cycle from the anchor following in-SCC successors so the message reads as a path. */
    private static List<String> cycleOrder(ProcessGraph g, Set<String> scc, String anchor) {
        List<String> out = new ArrayList<>(List.of(anchor));
        Set<String> seen = new LinkedHashSet<>(List.of(anchor));
        String v = anchor;
        while (true) {
            String next = null;
            for (String w : g.successors(v)) if (scc.contains(w) && !seen.contains(w)) { next = w; break; }
            if (next == null) return out;
            out.add(next); seen.add(next); v = next;
        }
    }

    private static Map<String, Object> writeMap(Write w) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", w.w().kind()); m.put("variable", w.w().variable()); m.put("where", w.w().where());
        m.put("expression", w.w().expression()); m.put("payload", w.w().payload()); m.put("node", w.nodeId());
        return m;
    }

    private static Map<String, Object> loopMap(Loop l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scc", l.scc()); m.put("path", l.path()); m.put("anchor", l.anchor()); m.put("conditions", l.conditions());
        m.put("bound", l.bound()); m.put("waits", l.waits());
        m.put("reWrites", l.reWrites().stream().map(w -> w.w().variable()).toList());
        return m;
    }
}
