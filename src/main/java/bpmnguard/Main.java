package bpmnguard;

import bpmnguard.gui.GuardFrame;
import bpmnguard.parse.BpmnParser;
import bpmnguard.render.RenderOptions;
import bpmnguard.render.ViolationOverlay;
import bpmnguard.report.Report;
import bpmnguard.report.ReportWriter;
import bpmnguard.report.Validator;
import bpmnguard.rules.RuleOptions;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Dual-mode entry point.
 *
 * <pre>
 *   java -jar bpmn-guard.jar                           no args  -> desktop UI
 *   java -jar bpmn-guard.jar order.bpmn                headless, text report
 *   java -jar bpmn-guard.jar order.bpmn out.png        headless, renders annotated PNG from the DI
 *   java -jar bpmn-guard.jar order.bpmn out.png --json + JSON on stdout
 *       [--fail-on high|critical] [--chain-threshold N] [--scale S] [--annotate base.png out.png]
 * </pre>
 * Exit codes: 0 ok · 1 high (with --fail-on high) · 2 critical · 64 usage · 65 input · 66 render.
 */
public final class Main {

    private static final String USAGE = """
            usage: bpmn-guard                                  launch desktop UI
                   bpmn-guard <file.bpmn> [out.png] [--json] [--fail-on high|critical]
                              [--chain-threshold N] [--scale S] [--annotate base.png out.png]""";

    private Main() {}

    public static void main(String[] args) {
        List<String> positional = new ArrayList<>();
        boolean json = false, failOnHigh = false;
        Integer chainThreshold = null;
        Double scale = null;
        String annotateBase = null, annotateOut = null;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            try {
                switch (a) {
                    case "--json" -> json = true;
                    case "--fail-on" -> failOnHigh = "high".equalsIgnoreCase(args[++i]);
                    case "--chain-threshold" -> chainThreshold = Integer.parseInt(args[++i]);
                    case "--scale" -> scale = Double.parseDouble(args[++i]);
                    case "--annotate" -> { annotateBase = args[++i]; annotateOut = args[++i]; }
                    case "--help", "-h" -> { System.out.println(USAGE); return; }
                    default -> {
                        if (a.startsWith("--")) die(64, "Unknown option " + a + "\n" + USAGE);
                        positional.add(a);
                    }
                }
            } catch (ArrayIndexOutOfBoundsException | NumberFormatException e) {
                die(64, "Bad value for " + a + "\n" + USAGE);
            }
        }

        if (positional.isEmpty() && annotateBase == null) {
            GuardFrame.launch(null);   // interactive mode; the EDT keeps the JVM alive
            return;
        }
        if (positional.isEmpty() || positional.size() > 2) die(64, USAGE);

        Path file = Path.of(positional.get(0));
        Path outPng = positional.size() == 2 ? Path.of(positional.get(1)) : null;
        RuleOptions opts = chainThreshold == null ? RuleOptions.DEFAULT : RuleOptions.DEFAULT.withChainThreshold(chainThreshold);
        RenderOptions ro = scale == null ? RenderOptions.DEFAULT : RenderOptions.DEFAULT.withScale(scale);

        Report report;
        try {
            report = Validator.validate(file, opts);
        } catch (BpmnParser.BpmnParseException e) {
            die(65, "error: " + e.getMessage());
            return;
        }
        System.out.println(json ? ReportWriter.json(report, true) : ReportWriter.text(report));

        try {
            if (outPng != null) {
                if (report.diagram().isEmpty()) {
                    System.err.println("warn: no DI in file, PNG not written");
                } else {
                    ViolationOverlay.writePng(ViolationOverlay.renderReport(report, ro), outPng);
                    long drawn = report.violations().stream().filter(v -> v.bounds != null).count();
                    System.err.printf("rendered %s: %d violation(s) drawn%n", outPng, drawn);
                }
            }
            if (annotateBase != null) {
                BufferedImage base = ViolationOverlay.readImage(new File(annotateBase));
                ViolationOverlay.writePng(ViolationOverlay.annotate(base, report, ro), Path.of(annotateOut));
                System.err.printf("annotated %s%n", annotateOut);
            }
        } catch (Exception e) {
            die(66, "render error: " + e.getMessage());
        }
        System.exit(report.exitCode(failOnHigh));
    }

    private static void die(int code, String msg) {
        System.err.println(msg);
        System.exit(code);
    }
}
