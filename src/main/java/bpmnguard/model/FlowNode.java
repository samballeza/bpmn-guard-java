package bpmnguard.model;

import java.util.List;
import java.util.Set;

/**
 * One BPMN flow node (task, event, gateway, sub-process) lowered from the XML.
 * Immutable; all collections are unmodifiable.
 */
public record FlowNode(
        String id,
        String type,                 // BPMN local name, e.g. "serviceTask"
        String name,
        String scope,                // id of the enclosing process / subProcess
        boolean asyncBefore,
        boolean asyncAfter,
        boolean exclusive,
        Set<String> eventDefs,       // e.g. "timerEventDefinition"
        MultiInstance multiInstance, // null when not multi-instance
        List<VariableWrite> variableWrites,
        boolean container,           // process / subProcess / transaction / adHocSubProcess
        boolean eventSubprocess,
        String implementation,       // camunda:type, "external" => wait state
        String attachedTo,           // boundary events only
        boolean cancelActivity) {

    public static final Set<String> CONTAINER_TYPES =
            Set.of("process", "subProcess", "transaction", "adHocSubProcess");

    public static final Set<String> FLOW_NODE_TYPES = Set.of(
            "startEvent", "endEvent", "intermediateCatchEvent", "intermediateThrowEvent", "boundaryEvent",
            "task", "userTask", "serviceTask", "scriptTask", "businessRuleTask", "sendTask", "receiveTask",
            "manualTask", "callActivity", "subProcess", "transaction", "adHocSubProcess",
            "exclusiveGateway", "inclusiveGateway", "parallelGateway", "eventBasedGateway", "complexGateway");

    public static final Set<String> ACTIVITY_TYPES = Set.of(
            "task", "userTask", "serviceTask", "scriptTask", "businessRuleTask", "sendTask", "receiveTask",
            "manualTask", "callActivity", "subProcess", "transaction", "adHocSubProcess");

    private static final Set<String> CATCHING_DEFS = Set.of(
            "timerEventDefinition", "messageEventDefinition", "signalEventDefinition", "conditionalEventDefinition");

    public boolean isActivity() { return ACTIVITY_TYPES.contains(type); }

    public boolean isGateway() { return type.endsWith("Gateway"); }

    public boolean isEvent() { return type.endsWith("Event"); }

    /** Synchronous, CPU/DB-bound task types that run inside the caller's transaction. */
    public boolean isComputeTask() {
        return switch (type) {
            case "serviceTask", "scriptTask", "businessRuleTask", "sendTask" -> !"external".equals(implementation);
            default -> false;
        };
    }

    /** True when the engine commits and releases the thread at this node. */
    public boolean isWaitState() {
        boolean catching = eventDefs.stream().anyMatch(CATCHING_DEFS::contains);
        return switch (type) {
            case "userTask", "receiveTask", "intermediateCatchEvent", "eventBasedGateway" -> true;
            case "boundaryEvent" -> catching;
            default -> false;
        } || "external".equals(implementation) || asyncBefore || asyncAfter;
    }

    public boolean isAsyncBoundary() { return asyncBefore || asyncAfter; }

    public String label() {
        return name == null || name.isBlank() ? id : "'" + name + "' (" + id + ")";
    }
}
