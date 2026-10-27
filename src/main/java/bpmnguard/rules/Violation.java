package bpmnguard.rules;

import bpmnguard.model.Bounds;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One finding. Immutable; built through {@link Builder}. DI geometry
 * ({@code bounds}, {@code relatedBounds}) is attached after the rules run.
 */
public final class Violation {

    public enum Presentation { ELEMENT, GROUP }

    public final RuleId rule;
    public final Severity severity;
    public final String elementId;
    public final String elementType;
    public final String elementName;
    public final String scope;
    public final String message;
    public final String remediation;
    public final List<String> related;
    public final Map<String, Object> evidence;
    public final Presentation presentation;
    /** finer label within a rule, e.g. "summary" / "writer" / "loop"; may be null */
    public final String kind;
    public final Bounds bounds;
    public final Map<String, Bounds> relatedBounds;

    private Violation(Builder b) {
        this.rule = b.rule;
        this.severity = b.severity;
        this.elementId = b.elementId;
        this.elementType = b.elementType;
        this.elementName = b.elementName;
        this.scope = b.scope;
        this.message = b.message;
        this.remediation = b.rule.remediation;
        this.related = List.copyOf(b.related);
        this.evidence = Collections.unmodifiableMap(new LinkedHashMap<>(b.evidence));
        this.presentation = b.presentation;
        this.kind = b.kind;
        this.bounds = b.bounds;
        this.relatedBounds = Collections.unmodifiableMap(new LinkedHashMap<>(b.relatedBounds));
    }

    public boolean isGroup() { return presentation == Presentation.GROUP; }

    public Violation withGeometry(Bounds bounds, Map<String, Bounds> relatedBounds) {
        Builder b = toBuilder();
        b.bounds = bounds;
        b.relatedBounds = new LinkedHashMap<>(relatedBounds);
        return b.build();
    }

    public Builder toBuilder() {
        Builder b = new Builder(rule, severity, elementId);
        b.elementType = elementType; b.elementName = elementName; b.scope = scope; b.message = message;
        b.related = new java.util.ArrayList<>(related); b.evidence = new LinkedHashMap<>(evidence);
        b.presentation = presentation; b.kind = kind; b.bounds = bounds; b.relatedBounds = new LinkedHashMap<>(relatedBounds);
        return b;
    }

    public static Builder builder(RuleId rule, Severity severity, String elementId) {
        return new Builder(rule, severity, elementId);
    }

    public static final class Builder {
        private final RuleId rule;
        private final Severity severity;
        private final String elementId;
        private String elementType, elementName, scope, message, kind;
        private List<String> related = new java.util.ArrayList<>();
        private Map<String, Object> evidence = new LinkedHashMap<>();
        private Presentation presentation = Presentation.ELEMENT;
        private Bounds bounds;
        private Map<String, Bounds> relatedBounds = new LinkedHashMap<>();

        private Builder(RuleId rule, Severity severity, String elementId) {
            this.rule = rule; this.severity = severity; this.elementId = elementId;
        }
        public Builder element(String type, String name, String scope) { this.elementType = type; this.elementName = name; this.scope = scope; return this; }
        public Builder message(String m) { this.message = m; return this; }
        public Builder related(List<String> ids) { this.related = new java.util.ArrayList<>(new java.util.LinkedHashSet<>(ids)); return this; }
        public Builder evidence(String key, Object value) { this.evidence.put(key, value); return this; }
        public Builder group() { this.presentation = Presentation.GROUP; return this; }
        public Builder kind(String k) { this.kind = k; return this; }
        public Violation build() {
            if (message == null) throw new IllegalStateException("violation without message");
            return new Violation(this);
        }
    }

    @Override
    public String toString() {
        return "[" + rule + " " + severity + "] " + elementType + " " + elementId + ": " + message;
    }
}
