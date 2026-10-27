package bpmnguard.model;

/**
 * A statically visible write to a NON-local process variable.
 *
 * @param kind       result-variable | output-parameter | connector-output | script-set-variable
 * @param variable   variable name
 * @param where      human-readable location, e.g. "connector outputParameter 'statusCode'"
 * @param expression one-line excerpt of the value expression / script (may be null)
 * @param payload    true when the value looks like an API response / request body / JSON
 */
public record VariableWrite(String kind, String variable, String where, String expression, boolean payload) {

    public String display() {
        return variable + (payload ? "*" : "") + " (" + where + ")";
    }
}
