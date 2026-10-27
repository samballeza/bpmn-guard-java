package bpmnguard.rules;

import bpmnguard.graph.ProcessGraph;

import java.util.List;

/** A deterministic rule: pure function ProcessGraph -&gt; findings. */
public interface Rule {
    List<Violation> apply(ProcessGraph g, RuleOptions opts);
}
