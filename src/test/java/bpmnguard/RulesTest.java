package bpmnguard;

import bpmnguard.model.Bounds;
import bpmnguard.parse.BpmnParser;
import bpmnguard.render.RenderOptions;
import bpmnguard.render.Transform;
import bpmnguard.render.ViolationOverlay;
import bpmnguard.report.Report;
import bpmnguard.report.ReportWriter;
import bpmnguard.report.Validator;
import bpmnguard.rules.RuleId;
import bpmnguard.rules.RuleOptions;
import bpmnguard.rules.Severity;
import bpmnguard.rules.Violation;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RulesTest {

    private static String sampleXml() throws Exception {
        try (InputStream in = RulesTest.class.getResourceAsStream("/samples/risky-order.bpmn")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Report validate(String xml) {
        return Validator.validate(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "test.bpmn", RuleOptions.DEFAULT);
    }

    private static Set<String> ids(Report r, RuleId rule) {
        return r.violations().stream().filter(v -> v.rule == rule).map(v -> v.elementId).collect(Collectors.toSet());
    }

    private static Violation first(Report r, RuleId rule, String id) {
        return r.violations().stream().filter(v -> v.rule == rule && v.elementId.equals(id)).findFirst().orElseThrow();
    }

    @Test
    void sampleTriggersEveryRule() throws Exception {
        Report r = validate(sampleXml());
        assertAll(
                () -> assertEquals(Set.of("PerItem", "SumItem"), ids(r, RuleId.R1), "R1 summary + writer"),
                () -> assertTrue(ids(r, RuleId.R2).contains("f17"), "R2 cross-scope flow"),
                () -> assertEquals(Set.of("CallPricing", "PricingOk"), ids(r, RuleId.R3), "R3 sync loop"),
                () -> assertEquals(Set.of("JoinApproval"), ids(r, RuleId.R4), "R4 XOR into AND"),
                () -> assertEquals(Severity.CRITICAL, first(r, RuleId.R4, "JoinApproval").severity),
                () -> assertEquals(Set.of("NotifyPartners"), ids(r, RuleId.R5), "R5 parallel MI"),
                () -> assertEquals(Set.of("Poll"), ids(r, RuleId.R7), "R7 event-based gateway target"));

        Violation chain = r.violations().stream().filter(v -> v.rule == RuleId.R6).findFirst().orElseThrow();
        assertEquals(List.of("Enrich1", "Enrich2", "Enrich3", "Enrich4", "Enrich5", "NotifyPartners"), chain.evidence.get("chain"));

        Violation summary = first(r, RuleId.R1, "PerItem");
        assertEquals("summary", summary.kind);
        assertEquals("items", summary.evidence.get("collection"));
        assertTrue(summary.message.contains("|items|"));

        assertTrue(r.violations().stream().allMatch(v -> !v.remediation.isBlank()));
        assertTrue(r.count(Severity.CRITICAL) > 0);
        assertEquals(2, r.exitCode(false));
    }

    @Test
    void diBoundsAreAttachedIncludingEdgeWaypoints() throws Exception {
        Report r = validate(sampleXml());
        Bounds b = first(r, RuleId.R5, "NotifyPartners").bounds;
        assertNotNull(b);
        assertEquals(1520.0, b.x()); assertEquals(160.0, b.y()); assertEquals(100.0, b.width()); assertEquals(80.0, b.height());
        Bounds edge = first(r, RuleId.R2, "f17").bounds;
        assertTrue(edge.isEdge());
        assertEquals(4, edge.waypoints().size());
    }

    @Test
    void fixesSilenceRules() throws Exception {
        String xml = sampleXml();
        Report r3 = validate(xml.replace("<bpmn:serviceTask id=\"CallPricing\" name=\"Call pricing API\"",
                "<bpmn:serviceTask id=\"CallPricing\" name=\"Call pricing API\" camunda:asyncBefore=\"true\""));
        assertTrue(ids(r3, RuleId.R3).isEmpty(), "asyncBefore breaks the synchronous loop");

        Report r5 = validate(xml.replace("<bpmn:serviceTask id=\"NotifyPartners\" name=\"Notify partners\"",
                "<bpmn:serviceTask id=\"NotifyPartners\" name=\"Notify partners\" camunda:asyncBefore=\"true\""));
        assertTrue(ids(r5, RuleId.R5).isEmpty(), "asyncBefore on parallel MI silences R5");
    }

    @Test
    void commentedOutSetVariableIsNotAWrite() throws Exception {
        String xml = sampleXml().replace(
                "<bpmn:script>return (execution.getVariable(\"runningTotal\") ?: 0) + item.price</bpmn:script>",
                "<bpmn:script>// execution.setVariable(\"ghost\", 1)\n/* execution.setVariable(\"ghost2\", 1) */\nreturn 1</bpmn:script>");
        Report r = validate(xml);
        Violation w = first(r, RuleId.R1, "SumItem");
        assertEquals(1, w.evidence.get("writes") instanceof List<?> l ? l.size() : -1, "only resultVariable remains");
    }

    @Test
    void missingDiIsAWarningNotAnError() throws Exception {
        String xml = sampleXml();
        String noDi = xml.substring(0, xml.indexOf("<bpmndi:BPMNDiagram")) + "</bpmn:definitions>";
        Report r = validate(noDi);
        assertFalse(r.violations().isEmpty());
        assertTrue(r.violations().stream().allMatch(v -> v.bounds == null));
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("BPMNDiagram")));
        assertTrue(r.diagram().isEmpty());
    }

    @Test
    void badInputIsAParseException() {
        assertThrows(BpmnParser.BpmnParseException.class, () -> Validator.validate(java.nio.file.Path.of("/definitely/not/here.bpmn"), RuleOptions.DEFAULT));
        assertThrows(BpmnParser.BpmnParseException.class, () -> validate("<definitions><process>"));
        assertThrows(BpmnParser.BpmnParseException.class, () -> validate("<html/>"));
    }

    @Test
    void jsonOutputIsWellFormedAndLanguageNeutral() throws Exception {
        String json = ReportWriter.json(validate(sampleXml()), true);
        assertTrue(json.startsWith("{"));
        assertTrue(json.contains("\"violations\""));
        assertTrue(json.contains("\"elementId\": \"JoinApproval\""));
        assertFalse(json.contains(":R"), "no EDN-style keywords leak into JSON");
    }

    @Test
    void renderDrawsDiagramAndOverlay() throws Exception {
        Report r = validate(sampleXml());
        RenderOptions o = RenderOptions.DEFAULT;
        BufferedImage img = ViolationOverlay.renderReport(r, o);
        Transform t = new Transform(r.diagram(), o);

        assertTrue(img.getWidth() > 2300, "canvas sized from DI extent plus padding");
        assertEquals(40.0, t.x(100.0), 1e-9, "min DI x is 100 (Start shape)");
        assertEquals(0xFFFFFF, img.getRGB(5, 5) & 0xFFFFFF, "white background");
        assertEquals(0xF2F2F2, img.getRGB((int) t.x(1540), (int) t.y(170)) & 0xFFFFFF, "task fill inside NotifyPartners");

        // NotifyPartners has R5 (critical, red) + R6 (high, amber): the halo must be RED.
        // Halo is a rounded rect haloPadding px outside the shape; scan a few rows around its bottom edge.
        boolean red = false;
        int x = (int) t.x(1570), edgeY = (int) (t.y(240) + o.haloPadding());
        for (int dy = -3; dy <= 3; dy++) {
            int rgb = img.getRGB(x, edgeY + dy);
            int rr = (rgb >> 16) & 0xFF, gg = (rgb >> 8) & 0xFF, bb = rgb & 0xFF;
            if (rr > 180 && gg < 90 && bb < 90) red = true;
        }
        assertTrue(red, "red halo around NotifyPartners");

        // Enrich5 is on the R6 chain but not the flagged element: no red halo there
        int x2 = (int) t.x(1440);
        for (int dy = -3; dy <= 3; dy++) {
            int rgb = img.getRGB(x2, edgeY + dy);
            int rr = (rgb >> 16) & 0xFF, gg = (rgb >> 8) & 0xFF;
            assertFalse(rr > 180 && gg < 90, "Enrich5 must not carry a red halo");
        }

        List<ViolationOverlay.HitRegion> regions = ViolationOverlay.hitRegions(r, o);
        assertEquals(9, regions.size());
        int hit = ViolationOverlay.hitTest(regions, t.x(1570), t.y(200));
        assertTrue(hit >= 0 && r.violations().get(hit).elementId.equals("NotifyPartners"), "click inside NotifyPartners selects it");
        assertEquals(-1, ViolationOverlay.hitTest(regions, 2, 2));
    }
}
