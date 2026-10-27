package bpmnguard.parse;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Namespace-agnostic DOM helpers. Elements and attributes are matched by
 * LOCAL name so the engine accepts any prefix (bpmn:, bpmn2:, none) and any
 * Camunda prefix. The parser is hardened against XXE / external DTDs.
 */
public final class Xml {

    private static final String CAMUNDA_NS_FRAGMENT = "camunda.org/schema";

    private Xml() {}

    public static Document parse(InputStream in) throws IOException, SAXException {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            DocumentBuilder b = f.newDocumentBuilder();
            b.setErrorHandler(null);
            return b.parse(in);
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("XML parser misconfigured", e);
        }
    }

    public static String local(Node n) {
        String l = n.getLocalName();
        return l != null ? l : n.getNodeName();
    }

    public static boolean is(Node n, String local) {
        return n.getNodeType() == Node.ELEMENT_NODE && local.equals(local(n));
    }

    /** Attribute by local name, any namespace. */
    public static String attr(Element el, String local) {
        NamedNodeMap attrs = el.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Attr a = (Attr) attrs.item(i);
            if (local.equals(local(a))) return a.getValue();
        }
        return null;
    }

    /** Attribute by local name restricted to the Camunda namespace (or unqualified, for hand-edited files). */
    public static String camundaAttr(Element el, String local) {
        NamedNodeMap attrs = el.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Attr a = (Attr) attrs.item(i);
            String ns = a.getNamespaceURI();
            if (local.equals(local(a)) && (ns == null || ns.contains(CAMUNDA_NS_FRAGMENT))) return a.getValue();
        }
        return null;
    }

    public static boolean boolAttr(Element el, String local) {
        String v = attr(el, local);
        return v != null && "true".equals(v.trim());
    }

    public static boolean camundaBool(Element el, String local) {
        String v = camundaAttr(el, local);
        return v != null && "true".equals(v.trim());
    }

    public static List<Element> children(Element el) {
        List<Element> out = new ArrayList<>();
        NodeList nl = el.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            if (nl.item(i).getNodeType() == Node.ELEMENT_NODE) out.add((Element) nl.item(i));
        }
        return out;
    }

    public static List<Element> childrenNamed(Element el, String local) {
        List<Element> out = new ArrayList<>();
        for (Element c : children(el)) if (local.equals(local(c))) out.add(c);
        return out;
    }

    public static Element firstChild(Element el, String local) {
        for (Element c : children(el)) if (local.equals(local(c))) return c;
        return null;
    }

    /** All descendant elements, depth-first, excluding {@code el} itself. */
    public static List<Element> descendants(Element el) {
        List<Element> out = new ArrayList<>();
        collect(el, out);
        return out;
    }

    private static void collect(Element el, List<Element> out) {
        for (Element c : children(el)) {
            out.add(c);
            collect(c, out);
        }
    }

    public static List<Element> descendantsNamed(Element el, String local) {
        List<Element> out = new ArrayList<>();
        for (Element d : descendants(el)) if (local.equals(local(d))) out.add(d);
        return out;
    }

    /** Direct text content (text and CDATA children only, not nested elements). */
    public static String text(Element el) {
        StringBuilder sb = new StringBuilder();
        NodeList nl = el.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE) {
                sb.append(n.getNodeValue());
            }
        }
        return sb.toString();
    }
}
