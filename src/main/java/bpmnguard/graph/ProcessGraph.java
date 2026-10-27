package bpmnguard.graph;

import bpmnguard.model.BpmnModel;
import bpmnguard.model.FlowNode;
import bpmnguard.model.SequenceFlow;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * Layer 2: the directed graph of a process.
 *
 * Vertices are flow-node ids; edges are sequence flows. BPMN allows parallel
 * edges (two conditional flows A-&gt;B) and self loops, so this is a multigraph.
 * Boundary events get a <em>synthetic</em> edge host -&gt; boundaryEvent so that
 * boundary-driven retry loops take part in cycle detection.
 *
 * Every algorithm takes a vertex subset and an edge predicate, which is how the
 * rules express "the sub-graph restricted to X".
 */
public final class ProcessGraph {

    public record Edge(String id, String source, String target, SequenceFlow flow) {
        public boolean synthetic() { return flow.isSynthetic(); }
    }

    public record CycleSearch(List<List<String>> cycles, boolean truncated) {}

    private final BpmnModel model;
    private final Map<String, List<Edge>> out = new LinkedHashMap<>();
    private final Map<String, List<Edge>> in = new LinkedHashMap<>();
    private final List<Edge> edges = new ArrayList<>();

    public ProcessGraph(BpmnModel model) {
        this.model = model;
        for (String id : model.nodes().keySet()) {
            out.put(id, new ArrayList<>());
            in.put(id, new ArrayList<>());
        }
        for (SequenceFlow f : model.flows().values()) addEdge(new Edge(f.id(), f.source(), f.target(), f));
        for (FlowNode n : model.nodes().values()) {
            if ("boundaryEvent".equals(n.type()) && n.attachedTo() != null && model.nodes().containsKey(n.attachedTo())) {
                SequenceFlow synthetic = new SequenceFlow(n.attachedTo() + "~>" + n.id(), null, n.attachedTo(), n.id(),
                        n.scope(), false, null, false, "boundary");
                addEdge(new Edge(synthetic.id(), synthetic.source(), synthetic.target(), synthetic));
            }
        }
    }

    private void addEdge(Edge e) {
        edges.add(e);
        out.get(e.source()).add(e);
        in.get(e.target()).add(e);
    }

    // -----------------------------------------------------------------------
    // Accessors
    // -----------------------------------------------------------------------

    public BpmnModel model() { return model; }
    public FlowNode node(String id) { return model.node(id); }
    public Set<String> vertices() { return out.keySet(); }
    public List<Edge> edges() { return Collections.unmodifiableList(edges); }
    public List<Edge> outEdges(String id) { return Collections.unmodifiableList(out.getOrDefault(id, List.of())); }
    public List<Edge> inEdges(String id) { return Collections.unmodifiableList(in.getOrDefault(id, List.of())); }
    public int outDegree(String id) { return out.getOrDefault(id, List.of()).size(); }
    public int inDegree(String id) { return in.getOrDefault(id, List.of()).size(); }

    public List<String> successors(String id) {
        List<String> s = new ArrayList<>();
        for (Edge e : outEdges(id)) s.add(e.target());
        return s;
    }

    public List<String> predecessors(String id) {
        List<String> s = new ArrayList<>();
        for (Edge e : inEdges(id)) s.add(e.source());
        return s;
    }

    /** Ids of every node transitively contained in container {@code containerId}. */
    public Set<String> descendantIds(String containerId) {
        Map<String, List<String>> byScope = new HashMap<>();
        for (FlowNode n : model.nodes().values()) byScope.computeIfAbsent(n.scope(), k -> new ArrayList<>()).add(n.id());
        Set<String> acc = new LinkedHashSet<>();
        Deque<String> todo = new ArrayDeque<>();
        todo.add(containerId);
        while (!todo.isEmpty()) {
            for (String kid : byScope.getOrDefault(todo.poll(), List.of())) {
                acc.add(kid);
                if (node(kid).container()) todo.add(kid);
            }
        }
        return acc;
    }

    // -----------------------------------------------------------------------
    // Traversal
    // -----------------------------------------------------------------------

    /** Vertices reachable from {@code start} (inclusive) without expanding any vertex in {@code blocked}. */
    public Set<String> reachableFrom(String start, Set<String> blocked) {
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> q = new ArrayDeque<>();
        seen.add(start);
        q.add(start);
        while (!q.isEmpty()) {
            String v = q.poll();
            if (blocked.contains(v)) continue;
            for (String w : successors(v)) if (seen.add(w)) q.add(w);
        }
        return seen;
    }

    /**
     * Tarjan's strongly connected components on the sub-graph (vertices, edgeFilter).
     * Returns only non-trivial components: size &gt; 1, or a single vertex with a self loop.
     */
    public List<Set<String>> stronglyConnectedSets(Set<String> vertices, Predicate<Edge> edgeFilter) {
        Map<String, Integer> index = new HashMap<>();
        Map<String, Integer> low = new HashMap<>();
        Set<String> onStack = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        List<Set<String>> result = new ArrayList<>();
        int[] counter = {0};
        for (String v : vertices) {
            if (!index.containsKey(v)) tarjan(v, vertices, edgeFilter, index, low, onStack, stack, result, counter);
        }
        List<Set<String>> nonTrivial = new ArrayList<>();
        for (Set<String> scc : result) {
            if (scc.size() > 1) { nonTrivial.add(scc); continue; }
            String v = scc.iterator().next();
            for (Edge e : outEdges(v)) if (e.target().equals(v) && edgeFilter.test(e)) { nonTrivial.add(scc); break; }
        }
        return nonTrivial;
    }

    private void tarjan(String v, Set<String> vs, Predicate<Edge> ef, Map<String, Integer> index, Map<String, Integer> low,
                        Set<String> onStack, Deque<String> stack, List<Set<String>> result, int[] counter) {
        // iterative to survive deep graphs
        Deque<Object[]> work = new ArrayDeque<>();
        work.push(new Object[]{v, 0});
        index.put(v, counter[0]); low.put(v, counter[0]); counter[0]++;
        stack.push(v); onStack.add(v);
        while (!work.isEmpty()) {
            Object[] frame = work.peek();
            String u = (String) frame[0];
            int i = (int) frame[1];
            List<Edge> es = out.get(u);
            if (i < es.size()) {
                frame[1] = i + 1;
                Edge e = es.get(i);
                String w = e.target();
                if (!vs.contains(w) || !ef.test(e)) continue;
                if (!index.containsKey(w)) {
                    index.put(w, counter[0]); low.put(w, counter[0]); counter[0]++;
                    stack.push(w); onStack.add(w);
                    work.push(new Object[]{w, 0});
                } else if (onStack.contains(w)) {
                    low.put(u, Math.min(low.get(u), index.get(w)));
                }
            } else {
                work.pop();
                if (!work.isEmpty()) {
                    String parent = (String) work.peek()[0];
                    low.put(parent, Math.min(low.get(parent), low.get(u)));
                }
                if (low.get(u).equals(index.get(u))) {
                    Set<String> scc = new TreeSet<>();
                    String w;
                    do { w = stack.pop(); onStack.remove(w); scc.add(w); } while (!w.equals(u));
                    result.add(scc);
                }
            }
        }
    }

    /** True when the sub-graph has no cycle. */
    public boolean isAcyclic(Set<String> vertices, Predicate<Edge> edgeFilter) {
        return topologicalOrder(vertices, edgeFilter) != null;
    }

    /** Kahn's algorithm; null when the sub-graph is cyclic. */
    public List<String> topologicalOrder(Set<String> vertices, Predicate<Edge> edgeFilter) {
        Map<String, Integer> indeg = new LinkedHashMap<>();
        for (String v : vertices) indeg.put(v, 0);
        for (String v : vertices) for (Edge e : outEdges(v)) {
            if (vertices.contains(e.target()) && edgeFilter.test(e)) indeg.merge(e.target(), 1, Integer::sum);
        }
        Deque<String> q = new ArrayDeque<>();
        for (Map.Entry<String, Integer> en : indeg.entrySet()) if (en.getValue() == 0) q.add(en.getKey());
        List<String> order = new ArrayList<>();
        while (!q.isEmpty()) {
            String v = q.poll();
            order.add(v);
            for (Edge e : outEdges(v)) {
                if (!vertices.contains(e.target()) || !edgeFilter.test(e)) continue;
                if (indeg.merge(e.target(), -1, Integer::sum) == 0) q.add(e.target());
            }
        }
        return order.size() == vertices.size() ? order : null;
    }

    /**
     * Enumerate simple cycles inside {@code vertices} with a bounded DFS. Each cycle is
     * reported once, rooted at its lowest-ordered vertex. Stops after {@code maxCycles}
     * results or 200,000 edge visits ({@code truncated} says so).
     */
    public CycleSearch simpleCycles(Set<String> vertices, int maxCycles) {
        List<String> order = new ArrayList<>(new TreeSet<>(vertices));
        Map<String, Integer> idx = new HashMap<>();
        for (int i = 0; i < order.size(); i++) idx.put(order.get(i), i);
        Map<String, List<String>> adj = new HashMap<>();
        for (String v : order) {
            Set<String> ts = new LinkedHashSet<>();
            for (Edge e : outEdges(v)) if (vertices.contains(e.target())) ts.add(e.target());
            adj.put(v, new ArrayList<>(ts));
        }
        List<List<String>> found = new ArrayList<>();
        long[] steps = {0};
        boolean[] stop = {false};
        for (String s : order) {
            if (stop[0]) break;
            dfsCycles(s, s, new ArrayList<>(List.of(s)), new HashSet<>(Set.of(s)), adj, idx, found, maxCycles, steps, stop);
        }
        return new CycleSearch(found, stop[0]);
    }

    private void dfsCycles(String start, String v, List<String> path, Set<String> onPath, Map<String, List<String>> adj,
                           Map<String, Integer> idx, List<List<String>> found, int max, long[] steps, boolean[] stop) {
        for (String w : adj.get(v)) {
            if (stop[0]) return;
            if (++steps[0] > 200_000) { stop[0] = true; return; }
            if (w.equals(start)) {
                if (found.size() < max) found.add(List.copyOf(path)); else { stop[0] = true; return; }
            } else if (idx.get(w) > idx.get(start) && !onPath.contains(w)) {
                path.add(w); onPath.add(w);
                dfsCycles(start, w, path, onPath, adj, idx, found, max, steps, stop);
                path.remove(path.size() - 1); onPath.remove(w);
            }
        }
    }
}
