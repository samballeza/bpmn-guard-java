package bpmnguard.model;

/**
 * A sequence flow (or a synthetic host -&gt; boundaryEvent edge, marked by {@code synthetic}).
 */
public record SequenceFlow(String id, String name, String source, String target, String scope,
                           boolean conditional, String condition, boolean isDefault, String synthetic) {

    public boolean isSynthetic() { return synthetic != null; }
}
