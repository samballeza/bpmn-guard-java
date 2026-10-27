package bpmnguard.rules;

public enum Severity {
    CRITICAL, HIGH, MEDIUM;

    public int rank() { return ordinal(); }   // 0 = most severe
}
