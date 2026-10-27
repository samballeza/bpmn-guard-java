package bpmnguard.rules;

/**
 * The rule catalogue: id, short badge title, colour class and remediation advice.
 * Shared by the renderer (badges / colours) and the GUI (table).
 */
public enum RuleId {

    R1("Seq MI Var Leak", ColorClass.OOM,
            "Keep per-iteration values out of the process-instance scope: (1) in connector/listener scripts use "
          + "execution.setVariableLocal, and have connector output scripts return only the fields you need instead of "
          + "S(response); (2) add an end executionListener on the multi-instance body that removes payload variables "
          + "(execution.removeVariable) once consumed; (3) reset retry counters at the start of each iteration; "
          + "(4) lower history level to ACTIVITY or exclude these variables via a HistoryLevel/HistoryEventProducer "
          + "filter so retries do not add ACT_HI_DETAIL rows; (5) if the loop is only a retry, prefer the job "
          + "executor's retry cycle (camunda:failedJobRetryTimeCycle) over a modelled loop."),

    R2("Scope Bypass", ColorClass.DEADLOCK,
            "Route the token through the sub-process end event (or an error/escalation end event caught by a "
          + "boundary event); never cross a scope with a plain sequence flow; give every non-end node an outgoing flow."),

    R3("Sync Loop", ColorClass.OOM,
            "Put camunda:asyncBefore=\"true\" on the first node of the loop (or a timer intermediate catch event) so "
          + "each iteration commits and releases the thread; bound retries with a counter variable on the gateway condition."),

    R4("XOR→AND Deadlock", ColorClass.DEADLOCK,
            "Join exclusive branches with an exclusiveGateway (or inclusiveGateway for OR splits); only join with a "
          + "parallelGateway what a parallelGateway split."),

    R5("Parallel MI Burst", ColorClass.OOM,
            "Set camunda:asyncBefore=\"true\" (and usually asyncAfter) on the multi-instance activity so instances are "
          + "created by the job executor in separate transactions; consider isSequential=\"true\" or batching the collection."),

    R6("Long Sync Chain", ColorClass.STARVATION,
            "Insert camunda:asyncBefore=\"true\" every 2–3 service tasks (or on the slowest remote call) so the chain "
          + "spans several short transactions."),

    R7("Bad EBG Target", ColorClass.DEADLOCK,
            "An eventBasedGateway may only lead to intermediateCatchEvents (message, timer, signal, conditional) or "
          + "receiveTasks; move any task/gateway behind the catching event.");

    /** Colour class used by the renderer. */
    public enum ColorClass { OOM, DEADLOCK, STARVATION }

    public final String title;
    public final ColorClass colorClass;
    public final String remediation;

    RuleId(String title, ColorClass colorClass, String remediation) {
        this.title = title;
        this.colorClass = colorClass;
        this.remediation = remediation;
    }
}
