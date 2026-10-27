# bpmn-guard (Java)

Deterministic pre-deployment linter for Camunda 7 BPMN 2.0 files. It parses the
XML, builds a directed graph of every sequence flow, runs seven graph rules that
predict OOM / deadlock / thread-lock behaviour, renders the diagram itself from
the file's DI section with `java.awt`, overlays colour-coded findings with rule
badges, and ships a Swing desktop UI for the same engine.

**Zero runtime dependencies.** JDK 17+ only: `javax.xml` for parsing, `java.awt`
for rendering, `javax.swing` for the UI. Graph algorithms (Tarjan SCC, BFS
reachability, Kahn topological sort, bounded cycle enumeration) are in
`ProcessGraph`. JUnit 5 is the only (test-scope) dependency. No LLM, no network,
no Camunda / Fluxnova / Groovy on the classpath — scripts are scanned textually,
never evaluated.

```
src/main/java/bpmnguard/
  model/     FlowNode, SequenceFlow, VariableWrite, Bounds, Diagram, BpmnModel   (immutable records)
  parse/     Xml (namespace-agnostic DOM helpers, XXE-hardened), BpmnParser, VariableWriteScanner
  graph/     ProcessGraph                                                         (multigraph + algorithms)
  rules/     RuleId (catalogue: title, colour class, remediation), Rule1..Rule7, Rules, Violation
  report/    Validator (entry point), Report, ReportWriter (text/JSON), Json
  render/    DiagramRenderer (base diagram), ViolationOverlay (halos, badges, hit regions), Transform
  gui/       GuardFrame                                                           (Swing)
  Main.java  dual-mode CLI / UI
src/main/resources/samples/risky-order.bpmn   every rule fires on this file
src/test/java/bpmnguard/RulesTest.java
```

## Build and run

```bash
mvn package                                  # compiles, runs tests, builds target/bpmn-guard-1.0.0.jar
java -jar target/bpmn-guard-1.0.0.jar        # desktop UI
java -jar target/bpmn-guard-1.0.0.jar order.bpmn                 # headless text report, exit 2 on critical
java -jar target/bpmn-guard-1.0.0.jar order.bpmn order.png       # + annotated PNG drawn from the DI
java -jar target/bpmn-guard-1.0.0.jar order.bpmn order.png --json --scale 1.5
```

Without Maven (the project has no dependencies, so plain `javac` works):

```bash
mkdir -p out && javac -d out $(find src/main/java -name '*.java') && cp -r src/main/resources/* out/
java -cp out bpmnguard.Main order.bpmn
```

Options: `--json`, `--fail-on high|critical` (default critical), `--chain-threshold N`
(default 5), `--scale S`, `--annotate base.png out.png` (overlay onto an externally
rendered PNG instead). Exit codes: 0 ok · 1 high with `--fail-on high` · 2 critical ·
64 usage · 65 input · 66 render.

From Java:

```java
Report r = Validator.validate(Path.of("order.bpmn"), RuleOptions.DEFAULT);
r.summary();                                   // {CRITICAL=4, HIGH=2, MEDIUM=0}
for (Violation v : r.violations()) {
    v.rule; v.severity; v.elementId; v.message; v.remediation; v.evidence; v.bounds;
}
ReportWriter.json(r, true);
BufferedImage img = ViolationOverlay.renderReport(r, RenderOptions.DEFAULT);
```

## The UI

**Open .BPMN file…** validates and renders immediately. Top: the scrollable annotated
diagram (zoom 0.5×–3×). Bottom-left: the findings table — Rule, Severity, Element ID,
Element Name, Type, Finding (prefixed `[summary]` / `[writer]` / `[loop]` for R1), Remediation;
long cells wrap and rows grow to fit. Bottom-right: the full finding, remediation,
related elements and JSON evidence for the selected row. Selecting a row scrolls the
diagram to the element; clicking a highlighted element or loop outline on the diagram
selects its row. **Export annotated PNG…** saves the canvas.

The file chooser starts in the last directory used, then `$BPMN_GUARD_DIR`, then — under
WSL — `/mnt/c/Users/<user>`, then `user.home`. Hidden directories are shown.

## Rules

| Rule | Severity | Detection |
|---|---|---|
| **R1** Sequential MI variable accumulation | critical / high | For every container with `isSequential="true"`: finds every statically visible non-local write in the body — `camunda:resultVariable`, `camunda:outputParameter` (plain and connector), and `execution./connector.setVariable(` in any script (listeners, input/output mappings, connector mappings; comments stripped first); `setVariableLocal` and `inputParameter` are exempt. Writes whose name or value looks like an API response / request body are marked payload-sized. Emits one **summary** on the container (write count, payload count, inner-loop multipliers, formula `|collection| × (writes + retries×re-executed)`), one finding per writing activity, and one **grouped** finding per inner cycle stating its back-edge condition and whether it is counter-bounded. |
| **R2** Scope bypass / orphaned tokens | critical / high | (a) sequence flow whose source and target are in different scopes; (b) boundary event declared outside its host's container; (c) boundary-event path that re-enters its host's body; (d) non-end node with no outgoing flow (event sub-processes and compensation handlers exempt). |
| **R3** Synchronous infinite loop | critical | Delete every wait state (`userTask`, `receiveTask`, `intermediateCatchEvent`, `eventBasedGateway`, timer/message/signal/conditional boundary, `camunda:type="external"`) and every node with `asyncBefore`/`asyncAfter`. Any strongly connected component left is a cycle with zero commits. Sample cycles are enumerated per component (bounded). Boundary events get a synthetic `host → boundary` edge so error-boundary retry loops are caught. |
| **R4** XOR/OR split into AND join | critical / high / medium | For each diverging `exclusiveGateway`/`inclusiveGateway`, compute the reachable set of each branch (never re-entering the split). For each `parallelGateway` join in that cone, count which join inputs each branch feeds. If no single branch guarantees every input, the join deadlocks. Critical for XOR with two or more partial branches; one level lower for inclusive. |
| **R5** Parallel MI without save point | critical | `multiInstanceLoopCharacteristics` with `isSequential` false/absent and no `camunda:asyncBefore="true"`. |
| **R6** Unbroken compute chain | high / critical | Chain graph = `serviceTask`/`scriptTask`/`businessRuleTask`/`sendTask` (external tasks excluded), keeping edge `u → v` only when `u` lacks `asyncAfter` and `v` lacks `asyncBefore`. If acyclic: longest path in topological order, report every maximal chain ≥ `chainThreshold`. If cyclic: the component is an unbounded chain. |
| **R7** Event-based gateway target | critical | Every immediate target of an `eventBasedGateway` must be a `receiveTask` or an `intermediateCatchEvent` with a message/timer/signal/conditional definition. |

## Rendering

`DiagramRenderer` draws the whole diagram from the `.bpmn` file: canvas = DI extent ×
scale + 2 × padding; rounded rectangles for activities (thicker border for call
activities, `|||` / `≡` multi-instance markers, `+` for collapsed sub-processes),
diamonds with X / + / ○ / double-circle markers for gateways, circles for events with
primitive-drawn timer / message / signal / terminate markers, polylines with arrowheads
for sequence flows (slash for default, diamond for conditional), text annotations and
dashed associations.

`ViolationOverlay` then colours each finding by rule class — **red** R1/R3/R5
(OOM/crash), **orange** R2/R4/R7 (deadlock/orphaned tokens), **amber** R6 (transaction
starvation) — with a tight halo (rounded rect for activities, circle for events and
gateways), one badge per element listing every rule that hit it, and for grouped R1
loop findings a single dashed outline around the cycle with a `[R1: loop ×n]` badge.
`hitRegions` / `hitTest` give a UI the pixel regions to map clicks back to findings.

## Jenkins

```groovy
stage('BPMN lint') {
  steps {
    sh 'java -jar bpmn-guard-1.0.0.jar src/main/resources/processes/order.bpmn order-lint.png --json > bpmn-report.json'
    archiveArtifacts artifacts: 'order-lint.png, bpmn-report.json'
    // exit 2 => critical finding fails the stage; 1 => high (with --fail-on high)
  }
}
```

## Known limits

* Script analysis is a textual scan for `setVariable(` calls, not a Groovy parser.
  Writes made inside Java delegates are invisible to any static tool; `resultVariable`
  and `outputParameter` are the signals it relies on.
* R4 does not evaluate conditions: an XOR whose branches are mutually exhaustive and
  both reach the join is still flagged — intended, since it still deadlocks on the untaken branch.
* R1 can tell you which writes happen and how they multiply, not the runtime variable
  count; confirm with `SELECT NAME_, COUNT(*) FROM ACT_HI_VARINST WHERE PROC_INST_ID_ = ? GROUP BY NAME_`.
