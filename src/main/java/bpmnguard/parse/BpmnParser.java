package bpmnguard.parse;

import bpmnguard.model.Association;
import bpmnguard.model.Bounds;
import bpmnguard.model.BpmnModel;
import bpmnguard.model.Diagram;
import bpmnguard.model.FlowNode;
import bpmnguard.model.MultiInstance;
import bpmnguard.model.ProcessDef;
import bpmnguard.model.SequenceFlow;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Layer 1: XML -&gt; {@link BpmnModel} + {@link Diagram}.
 *
 * <pre>
 *   Parsed p = BpmnParser.parse(Path.of("order.bpmn"));
 *   p.model();   // nodes, flows, processes
 *   p.diagram(); // DI geometry (may be empty)
 * </pre>
 */
public final class BpmnParser {

    /** Thrown for unreadable input or malformed / non-BPMN XML. */
    public static final class BpmnParseException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public BpmnParseException(String msg, Throwable cause) { super(msg, cause); }
        public BpmnParseException(String msg) { super(msg); }
    }

    public record Parsed(BpmnModel model, Diagram diagram) {}

    private BpmnParser() {}

    public static Parsed parse(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return parse(in, file.toString());
        } catch (IOException e) {
            throw new BpmnParseException("Cannot read BPMN source: " + e.getMessage(), e);
        }
    }

    public static Parsed parse(InputStream in, String sourceName) {
        Document doc;
        try {
            doc = Xml.parse(in);
        } catch (SAXException e) {
            throw new BpmnParseException("Malformed BPMN XML in " + sourceName + ": " + e.getMessage(), e);
        } catch (IOException e) {
            throw new BpmnParseException("Cannot read BPMN source " + sourceName + ": " + e.getMessage(), e);
        }
        Element root = doc.getDocumentElement();
        if (!"definitions".equals(Xml.local(root))) {
            throw new BpmnParseException("Root element is <" + Xml.local(root) + ">, not <definitions>; is this a BPMN 2.0 file?");
        }
        return new Parsed(lowerModel(root), extractDiagram(root));
    }

    // -----------------------------------------------------------------------
    // Model
    // -----------------------------------------------------------------------

    private static BpmnModel lowerModel(Element root) {
        List<Element> processEls = Xml.childrenNamed(root, "process");
        List<ProcessDef> processes = new ArrayList<>();
        List<FlowNode> nodes = new ArrayList<>();
        List<SequenceFlow> flows = new ArrayList<>();
        Map<String, String> annotations = new LinkedHashMap<>();
        List<Association> associations = new ArrayList<>();

        for (Element p : processEls) {
            processes.add(new ProcessDef(id(p), Xml.attr(p, "name"), Xml.boolAttr(p, "isExecutable")));
            walkContainer(p, nodes, flows);
            for (Element a : Xml.descendantsNamed(p, "textAnnotation")) {
                Element t = Xml.firstChild(a, "text");
                annotations.put(id(a), t == null ? "" : Xml.text(t).trim());
            }
            for (Element a : Xml.descendantsNamed(p, "association")) {
                associations.add(new Association(id(a), Xml.attr(a, "sourceRef"), Xml.attr(a, "targetRef")));
            }
        }

        List<String> warnings = new ArrayList<>();
        if (processEls.isEmpty()) warnings.add("No <process> elements found");

        Map<String, FlowNode> nodeIndex = new LinkedHashMap<>();
        Set<String> dupNodes = new LinkedHashSet<>();
        for (FlowNode n : nodes) if (nodeIndex.put(n.id(), n) != null) dupNodes.add(n.id());
        dupNodes.forEach(d -> warnings.add("Duplicate flow node id '" + d + "'"));

        Map<String, SequenceFlow> flowIndex = new LinkedHashMap<>();
        Set<String> dupFlows = new LinkedHashSet<>();
        for (SequenceFlow f : flows) {
            List<String> missing = new ArrayList<>();
            if (!nodeIndex.containsKey(f.source())) missing.add(f.source());
            if (!nodeIndex.containsKey(f.target())) missing.add(f.target());
            if (!missing.isEmpty()) {
                warnings.add("sequenceFlow '" + f.id() + "' references unknown node(s) " + missing);
                continue;
            }
            if (flowIndex.put(f.id(), f) != null) dupFlows.add(f.id());
        }
        dupFlows.forEach(d -> warnings.add("Duplicate sequenceFlow id '" + d + "'"));

        return new BpmnModel(processes, nodeIndex, flowIndex, annotations, associations, warnings);
    }

    private static void walkContainer(Element container, List<FlowNode> nodes, List<SequenceFlow> flows) {
        String scopeId = id(container);
        List<Element> kids = Xml.children(container);

        Set<String> defaults = new HashSet<>();
        for (Element k : kids) {
            String d = Xml.attr(k, "default");
            if (d != null) defaults.add(d);
        }
        for (Element k : kids) {
            String type = Xml.local(k);
            if (FlowNode.FLOW_NODE_TYPES.contains(type)) nodes.add(toNode(k, scopeId));
            if ("sequenceFlow".equals(type)) flows.add(toFlow(k, scopeId, defaults));
            if (FlowNode.CONTAINER_TYPES.contains(type)) walkContainer(k, nodes, flows);
        }
    }

    private static FlowNode toNode(Element el, String scopeId) {
        String type = Xml.local(el);
        Set<String> defs = new LinkedHashSet<>();
        for (Element c : Xml.children(el)) {
            String l = Xml.local(c);
            if (l.endsWith("EventDefinition")) defs.add(l);
        }
        MultiInstance mi = null;
        Element miEl = Xml.firstChild(el, "multiInstanceLoopCharacteristics");
        if (miEl != null) {
            mi = new MultiInstance(Xml.boolAttr(miEl, "isSequential"),
                    Xml.camundaAttr(miEl, "collection"),
                    Xml.camundaAttr(miEl, "elementVariable"),
                    Xml.camundaBool(miEl, "asyncBefore"),
                    Xml.camundaBool(miEl, "asyncAfter"));
        }
        boolean boundary = "boundaryEvent".equals(type);
        return new FlowNode(
                id(el), type, Xml.attr(el, "name"), scopeId,
                Xml.camundaBool(el, "asyncBefore"), Xml.camundaBool(el, "asyncAfter"),
                !"false".equals(Xml.camundaAttr(el, "exclusive")),
                Set.copyOf(defs), mi,
                List.copyOf(VariableWriteScanner.scan(el)),
                FlowNode.CONTAINER_TYPES.contains(type),
                Xml.boolAttr(el, "triggeredByEvent"),
                Xml.camundaAttr(el, "type"),
                boundary ? Xml.attr(el, "attachedToRef") : null,
                boundary && !"false".equals(Xml.attr(el, "cancelActivity")));
    }

    private static SequenceFlow toFlow(Element el, String scopeId, Set<String> defaults) {
        Element cond = Xml.firstChild(el, "conditionExpression");
        String condText = cond == null ? null : Xml.text(cond).trim().replaceAll("\\s+", " ");
        String fid = id(el);
        return new SequenceFlow(fid, Xml.attr(el, "name"), Xml.attr(el, "sourceRef"), Xml.attr(el, "targetRef"),
                scopeId, cond != null, condText, defaults.contains(fid), null);
    }

    private static String id(Element el) {
        String id = Xml.attr(el, "id");
        return id != null ? id : "anon-" + Xml.local(el) + "-" + Integer.toHexString(System.identityHashCode(el));
    }

    // -----------------------------------------------------------------------
    // Diagram interchange
    // -----------------------------------------------------------------------

    private static Diagram extractDiagram(Element root) {
        List<Element> diagrams = Xml.childrenNamed(root, "BPMNDiagram");
        if (diagrams.isEmpty()) {
            return Diagram.empty("No <bpmndi:BPMNDiagram> found — violations will carry no coordinates and no diagram can be drawn");
        }
        Map<String, Bounds> byId = new LinkedHashMap<>();
        for (Element d : diagrams) {
            for (Element plane : Xml.childrenNamed(d, "BPMNPlane")) {
                for (Element e : Xml.descendants(plane)) {
                    String id = Xml.attr(e, "bpmnElement");
                    if (id == null) continue;
                    if (Xml.is(e, "BPMNShape")) {
                        Bounds b = shape(e);
                        if (b != null) byId.put(id, b);
                    } else if (Xml.is(e, "BPMNEdge")) {
                        Bounds b = edge(e);
                        if (b != null) byId.put(id, b);
                    }
                }
            }
        }
        List<String> warnings = new ArrayList<>();
        if (byId.isEmpty()) warnings.add("BPMNDiagram present but contains no parseable shapes");
        return new Diagram(byId, warnings);
    }

    private static Bounds rect(Element boundsEl, boolean expanded, Bounds label) {
        Double x = num(Xml.attr(boundsEl, "x")), y = num(Xml.attr(boundsEl, "y"));
        Double w = num(Xml.attr(boundsEl, "width")), h = num(Xml.attr(boundsEl, "height"));
        if (x == null || y == null || w == null || h == null) return null;
        return Bounds.shape(x, y, w, h, expanded, label);
    }

    private static Bounds labelOf(Element el) {
        Element lbl = Xml.firstChild(el, "BPMNLabel");
        if (lbl == null) return null;
        Element b = Xml.firstChild(lbl, "Bounds");
        return b == null ? null : rect(b, false, null);
    }

    private static Bounds shape(Element shapeEl) {
        Element b = Xml.firstChild(shapeEl, "Bounds");
        if (b == null) return null;
        return rect(b, Xml.boolAttr(shapeEl, "isExpanded"), labelOf(shapeEl));
    }

    private static Bounds edge(Element edgeEl) {
        List<double[]> pts = new ArrayList<>();
        for (Element wp : Xml.childrenNamed(edgeEl, "waypoint")) {
            Double x = num(Xml.attr(wp, "x")), y = num(Xml.attr(wp, "y"));
            if (x != null && y != null) pts.add(new double[]{x, y});
        }
        return pts.isEmpty() ? null : Bounds.edge(pts, labelOf(edgeEl));
    }

    private static Double num(String v) {
        if (v == null) return null;
        try { return Double.parseDouble(v.trim()); } catch (NumberFormatException e) { return null; }
    }
}
