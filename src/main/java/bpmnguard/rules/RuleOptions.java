package bpmnguard.rules;

/**
 * Tunables.
 *
 * @param chainThreshold     R6: minimum length of a synchronous compute chain to report
 * @param maxSccEnumeration  R3: enumerate sample cycles only for components up to this size
 * @param maxCyclesPerScc    R3: cap on sample cycles per component
 */
public record RuleOptions(int chainThreshold, int maxSccEnumeration, int maxCyclesPerScc) {
    public static final RuleOptions DEFAULT = new RuleOptions(5, 20, 25);

    public RuleOptions withChainThreshold(int n) { return new RuleOptions(n, maxSccEnumeration, maxCyclesPerScc); }
}
