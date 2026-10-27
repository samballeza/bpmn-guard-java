package bpmnguard.parse;

import bpmnguard.model.VariableWrite;
import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds every statically visible way an element writes a NON-local process variable:
 * <ul>
 *   <li>camunda:resultVariable</li>
 *   <li>camunda:outputParameter (plain inputOutput and connector inputOutput)</li>
 *   <li>execution./connector./delegateExecution.setVariable("x", ...) in ANY script that
 *       belongs to the element: &lt;bpmn:script&gt;, camunda:script in listeners,
 *       input/output parameters and connector mappings</li>
 * </ul>
 * setVariableLocal, camunda:inputParameter and removeVariable are NOT writes.
 *
 * This is a textual scan, not a Groovy parser: nothing here evaluates or executes
 * script code, and writes done inside Java delegates are invisible to it.
 */
final class VariableWriteScanner {

    private static final Pattern SET_VARIABLE = Pattern.compile(
            "\\b(?:execution|connector|delegateExecution|runtimeService|task)\\s*\\.\\s*setVariable\\s*\\(\\s*[\"']([^\"']+)[\"']");
    private static final Pattern SET_VARIABLE_LOCAL = Pattern.compile(
            "\\bsetVariableLocal\\s*\\(\\s*[\"']([^\"']+)[\"']");
    private static final Pattern PAYLOAD = Pattern.compile(
            "(?i)response|payload|request|json|spin|\\bS\\s*\\(|body|result|record|list|projection");

    private VariableWriteScanner() {}

    static List<VariableWrite> scan(Element el) {
        List<VariableWrite> out = new ArrayList<>();

        String resultVar = Xml.camundaAttr(el, "resultVariable");
        if (resultVar != null) {
            out.add(new VariableWrite("result-variable", resultVar, "camunda:resultVariable", null, payloadLike(resultVar)));
        }

        for (Element ext : Xml.childrenNamed(el, "extensionElements")) {
            List<Element> connectors = Xml.descendantsNamed(ext, "connector");
            for (Element out1 : Xml.descendantsNamed(ext, "outputParameter")) {
                String name = Xml.attr(out1, "name");
                if (name == null) continue;
                boolean insideConnector = connectors.stream().anyMatch(c -> Xml.descendants(c).contains(out1));
                Element nested = Xml.firstChild(out1, "script");
                String exprText = nested != null ? Xml.text(nested) : Xml.text(out1);
                out.add(new VariableWrite(
                        insideConnector ? "connector-output" : "output-parameter",
                        name,
                        (insideConnector ? "connector outputParameter '" : "outputParameter '") + name + "'",
                        excerpt(exprText),
                        payloadLike(name, exprText)));
            }
        }

        // scripts: the element's own <bpmn:script> plus every camunda:script under its extensions
        for (Element sc : Xml.childrenNamed(el, "script")) {
            scanScript(Xml.text(sc), "script", out);
        }
        for (Element ext : Xml.childrenNamed(el, "extensionElements")) {
            walkScripts(ext, new ArrayList<>(), out);
        }
        return out;
    }

    private static void walkScripts(Element node, List<Element> path, List<VariableWrite> out) {
        path.add(node);
        if (Xml.is(node, "script")) {
            scanScript(Xml.text(node), location(path), out);
        }
        for (Element c : Xml.children(node)) walkScripts(c, path, out);
        path.remove(path.size() - 1);
    }

    private static final Pattern COMMENTS = Pattern.compile("/\\*.*?\\*/|//[^\\n]*", Pattern.DOTALL);

    private static void scanScript(String rawScript, String where, List<VariableWrite> out) {
        // commented-out code is not a write
        String script = COMMENTS.matcher(rawScript).replaceAll("");
        Set<String> locals = new LinkedHashSet<>();
        Matcher ml = SET_VARIABLE_LOCAL.matcher(script);
        while (ml.find()) locals.add(ml.group(1));

        Set<String> seen = new LinkedHashSet<>();
        Matcher m = SET_VARIABLE.matcher(script);
        while (m.find()) {
            String v = m.group(1);
            if (locals.contains(v) || !seen.add(v)) continue;
            // right-hand side of this call, for the payload heuristic
            String rhs = null;
            Matcher r = Pattern.compile("setVariable\\s*\\(\\s*[\"']" + Pattern.quote(v) + "[\"']\\s*,\\s*([^\\n;]{0,120})")
                    .matcher(script);
            if (r.find()) rhs = r.group(1);
            out.add(new VariableWrite("script-set-variable", v, where, rhs == null ? null : excerpt(rhs), payloadLike(v, rhs)));
        }
    }

    private static String location(List<Element> path) {
        boolean listener = false, task = false, connector = false, outP = false, inP = false;
        String paramName = null;
        String event = null;
        for (Element e : path) {
            switch (Xml.local(e)) {
                case "executionListener" -> { listener = true; event = Xml.attr(e, "event"); }
                case "taskListener" -> task = true;
                case "connector" -> connector = true;
                case "outputParameter" -> { outP = true; paramName = Xml.attr(e, "name"); }
                case "inputParameter" -> { inP = true; paramName = Xml.attr(e, "name"); }
                default -> { }
            }
        }
        if (listener) return "executionListener/" + (event == null ? "?" : event);
        if (task) return "taskListener";
        if (connector) return (outP ? "connector outputParameter '" : "connector inputParameter '") + paramName + "'";
        if (outP) return "outputParameter '" + paramName + "'";
        if (inP) return "inputParameter '" + paramName + "'";
        return "script";
    }

    static boolean payloadLike(String... texts) {
        for (String t : texts) if (t != null && PAYLOAD.matcher(t).find()) return true;
        return false;
    }

    /** First meaningful line of a script/expression, capped at 120 chars. */
    static String excerpt(String text) {
        if (text == null) return null;
        for (String raw : text.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("import ") || line.startsWith("//")) continue;
            return line.length() > 120 ? line.substring(0, 117) + "..." : line;
        }
        return "";
    }
}
