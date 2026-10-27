package bpmnguard.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The flat, namespace-agnostic model of one BPMN file. Everything downstream
 * (graph, rules, rendering) consumes this and never touches the XML again.
 */
public final class BpmnModel {

    private final List<ProcessDef> processes;
    private final Map<String, FlowNode> nodes;
    private final Map<String, SequenceFlow> flows;
    private final Map<String, String> annotations;
    private final List<Association> associations;
    private final List<String> warnings;

    public BpmnModel(List<ProcessDef> processes, Map<String, FlowNode> nodes, Map<String, SequenceFlow> flows,
                     Map<String, String> annotations, List<Association> associations, List<String> warnings) {
        this.processes = List.copyOf(processes);
        this.nodes = Collections.unmodifiableMap(new LinkedHashMap<>(nodes));
        this.flows = Collections.unmodifiableMap(new LinkedHashMap<>(flows));
        this.annotations = Collections.unmodifiableMap(new LinkedHashMap<>(annotations));
        this.associations = List.copyOf(associations);
        this.warnings = List.copyOf(warnings);
    }

    public List<ProcessDef> processes() { return processes; }
    public Map<String, FlowNode> nodes() { return nodes; }
    public Map<String, SequenceFlow> flows() { return flows; }
    public Map<String, String> annotations() { return annotations; }
    public List<Association> associations() { return associations; }
    public List<String> warnings() { return warnings; }

    public FlowNode node(String id) { return nodes.get(id); }
    public SequenceFlow flow(String id) { return flows.get(id); }
}
