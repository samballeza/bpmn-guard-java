package bpmnguard.model;

/** &lt;multiInstanceLoopCharacteristics&gt; as read from the XML. */
public record MultiInstance(boolean sequential, String collection, String elementVariable,
                            boolean asyncBefore, boolean asyncAfter) {
}
