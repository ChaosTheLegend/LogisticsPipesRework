package logisticspipes.routing.astar;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.ToDoubleBiFunction;

/**
 * The one shortest-path routine of the junction router.
 * <p>
 * A* is Dijkstra with a heuristic added to the queue priority, so there is a single implementation and the call sites
 * only differ in their arguments:
 * <ul>
 * <li>{@code heuristic == null}: plain Dijkstra ordering.</li>
 * <li>{@code heuristic != null}: A* ordering by {@code g + h}, where {@code h(n)} is the minimum of the heuristic over
 * all targets. A minimum of consistent heuristics is consistent, so a settled route is final and nothing is ever
 * re-opened.</li>
 * <li>one target: stop as soon as that target is settled.</li>
 * <li>several targets: stop as soon as every target is settled (one-to-many; shared exploration).</li>
 * <li>{@code targets == null}: settle everything reachable (used for the legacy whole-network views).</li>
 * </ul>
 * "Settled" is per flag: a target is done once every flag the source can emit has arrived over a filter-free route (see
 * {@link RouteLabel} for the filter rule). Queue entries that cannot deliver a flag still missing at any remaining
 * target are dropped, so a flag that never reaches a target does not make the search drain the component.
 */
public final class JunctionSearch {

    /** Above this many targets the per-node minimum over targets costs more than the goal direction saves. */
    static final int MAX_HEURISTIC_TARGETS = 64;

    private JunctionSearch() {}

    private static final class QueueEntry implements Comparable<QueueEntry> {

        final int node;
        final double g;
        final double f;
        final int flags;
        final List<?> filters;
        final int blockDistance;
        final RouteLabel parent;
        final CorridorEdge via;
        final long seq;

        QueueEntry(int node, double g, double f, int flags, List<?> filters, int blockDistance, RouteLabel parent,
                CorridorEdge via, long seq) {
            this.node = node;
            this.g = g;
            this.f = f;
            this.flags = flags;
            this.filters = filters;
            this.blockDistance = blockDistance;
            this.parent = parent;
            this.via = via;
            this.seq = seq;
        }

        @Override
        public int compareTo(QueueEntry o) {
            int c = Double.compare(f, o.f);
            if (c != 0) {
                return c;
            }
            c = Double.compare(g, o.g);
            if (c != 0) {
                return c;
            }
            c = Integer.compare(node, o.node);
            if (c != 0) {
                return c;
            }
            return Long.compare(seq, o.seq);
        }
    }

    /** Per-thread scratch arrays, reset through a touched list so a search costs O(visited), not O(graph). */
    private static final class Scratch {

        int[] closed = new int[0];
        List<?>[][] seen = new List<?>[0][];
        int[] targetSlot = new int[0];
        double[] h = new double[0];
        boolean[] touchedFlag = new boolean[0];
        int[] touched = new int[64];
        int touchedCount;

        void ensure(int capacity) {
            if (closed.length >= capacity) {
                return;
            }
            int n = Math.max(capacity, closed.length * 2);
            closed = new int[n];
            seen = new List<?>[n][];
            targetSlot = new int[n];
            Arrays.fill(targetSlot, -1);
            h = new double[n];
            Arrays.fill(h, Double.NaN);
            touchedFlag = new boolean[n];
            touchedCount = 0;
        }

        void touch(int i) {
            if (!touchedFlag[i]) {
                touchedFlag[i] = true;
                if (touchedCount == touched.length) {
                    touched = Arrays.copyOf(touched, touched.length * 2);
                }
                touched[touchedCount++] = i;
            }
        }

        void reset() {
            for (int k = 0; k < touchedCount; k++) {
                int i = touched[k];
                closed[i] = 0;
                seen[i] = null;
                targetSlot[i] = -1;
                h[i] = Double.NaN;
                touchedFlag[i] = false;
            }
            touchedCount = 0;
        }
    }

    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    /**
     * @param graph     the snapshot to search; nothing else is read
     * @param source    start junction
     * @param targets   junctions to settle, or {@code null} for every reachable junction
     * @param heuristic lower bound on the remaining cost between two junctions, or {@code null} for Dijkstra
     */
    public static SearchResult search(NetworkGraph graph, JunctionId source, Set<JunctionId> targets,
            ToDoubleBiFunction<JunctionId, JunctionId> heuristic) {
        Scratch s = SCRATCH.get();
        s.ensure(graph.capacity());
        try {
            return run(graph, source, targets, heuristic, s);
        } finally {
            s.reset();
        }
    }

    private static SearchResult run(NetworkGraph graph, JunctionId source, Set<JunctionId> requestedTargets,
            ToDoubleBiFunction<JunctionId, JunctionId> heuristic, Scratch s) {
        final boolean exhaustive = requestedTargets == null;
        Map<JunctionId, List<RouteLabel>> routes = new HashMap<>();
        List<RouteLabel> settledOrder = new ArrayList<>();
        Set<JunctionId> unreachable = new LinkedHashSet<>();

        JunctionNode sourceNode = graph.node(source);
        if (sourceNode == null) {
            if (!exhaustive) {
                unreachable.addAll(requestedTargets);
            }
            return new SearchResult(graph, source, routes, settledOrder, unreachable, 0, 0);
        }

        int emitMask = 0;
        for (CorridorEdge e : sourceNode.edges) {
            emitMask |= e.flags;
        }

        // ---- targets: dedupe, drop the source, pre-filter with the union-find component check
        JunctionId[] targets = new JunctionId[0];
        int[] open = new int[0];
        int[] openCount = new int[RoutingFlags.FLAG_COUNT];
        int remaining = 0;
        if (!exhaustive) {
            List<JunctionId> list = new ArrayList<>(requestedTargets.size());
            for (JunctionId t : requestedTargets) {
                if (t == null || t.equals(source)) {
                    continue;
                }
                if (t.index() < graph.capacity() && s.targetSlot[t.index()] >= 0) {
                    continue; // duplicate
                }
                if (!graph.sameComponent(source, t) || emitMask == 0) {
                    unreachable.add(t);
                    continue;
                }
                s.touch(t.index());
                s.targetSlot[t.index()] = list.size();
                list.add(t);
            }
            targets = list.toArray(new JunctionId[0]);
            open = new int[targets.length];
            Arrays.fill(open, emitMask);
            for (int b = 0; b < RoutingFlags.FLAG_COUNT; b++) {
                if ((emitMask & (1 << b)) != 0) {
                    openCount[b] = targets.length;
                }
            }
            remaining = targets.length;
            if (remaining == 0) {
                return new SearchResult(graph, source, routes, settledOrder, unreachable, 0, 0);
            }
        }
        int openUnion = exhaustive ? RoutingFlags.ALL : emitMask;
        boolean useHeuristic = heuristic != null && !exhaustive && targets.length <= MAX_HEURISTIC_TARGETS;

        PriorityQueue<QueueEntry> queue = new PriorityQueue<>();
        long seq = 0;

        // The source never routes to itself through a loop.
        int srcIdx = source.index();
        s.touch(srcIdx);
        s.closed[srcIdx] = RoutingFlags.ALL;

        for (CorridorEdge e : sourceNode.edges) {
            if ((e.flags & openUnion) == 0) {
                continue;
            }
            int t = e.to.index();
            double h = useHeuristic ? heuristicAt(graph, s, t, targets, heuristic) : 0;
            queue.add(new QueueEntry(t, e.weight, e.weight + h, e.flags, e.filters, e.blockDistance, null, e, seq++));
        }

        int settledNodes = 0;
        int polled = 0;
        QueueEntry cur;
        while ((cur = queue.poll()) != null) {
            polled++;
            if (!exhaustive && remaining == 0) {
                break;
            }
            if ((cur.flags & openUnion) == 0) {
                continue;
            }
            int idx = cur.node;
            if (!graph.isActive(idx)) {
                continue;
            }
            s.touch(idx);
            int closed = s.closed[idx];
            int fresh = cur.flags & ~closed;
            if (fresh == 0) {
                continue;
            }
            List<?>[] seen = s.seen[idx];
            if (seen != null && !hasNewInformation(cur, fresh, seen)) {
                continue;
            }

            // ---- accept
            if (closed == 0 && seen == null) {
                settledNodes++;
            }
            JunctionNode node = graph.node(idx);
            RouteLabel label = new RouteLabel(
                    source,
                    node.id,
                    cur.g,
                    cur.flags,
                    fresh,
                    cur.filters,
                    cur.blockDistance,
                    cur.parent,
                    cur.via);
            settledOrder.add(label);
            int slot = exhaustive ? -1 : s.targetSlot[idx];
            if (exhaustive || slot >= 0) {
                routes.computeIfAbsent(node.id, k -> new ArrayList<>(2)).add(label);
            }

            // ---- expand with every flag the route carries, like the link-state router did
            for (CorridorEdge e : node.edges) {
                int nf = cur.flags & e.flags;
                if ((nf & openUnion) == 0) {
                    continue;
                }
                int t = e.to.index();
                if (t == srcIdx) {
                    continue;
                }
                double g = cur.g + e.weight;
                double h = useHeuristic ? heuristicAt(graph, s, t, targets, heuristic) : 0;
                queue.add(
                        new QueueEntry(
                                t,
                                g,
                                g + h,
                                nf,
                                concat(cur.filters, e.filters),
                                cur.blockDistance + e.blockDistance,
                                label,
                                e,
                                seq++));
            }

            // ---- close
            if (seen == null) {
                seen = new List<?>[RoutingFlags.FLAG_COUNT];
                s.seen[idx] = seen;
            }
            for (int b = 0; b < RoutingFlags.FLAG_COUNT; b++) {
                if ((fresh & (1 << b)) != 0) {
                    @SuppressWarnings("unchecked")
                    List<List<?>> lists = (List<List<?>>) seen[b];
                    if (lists == null) {
                        lists = new ArrayList<>(1);
                        seen[b] = lists;
                    }
                    lists.add(cur.filters);
                }
            }
            if (cur.filters.isEmpty()) {
                int newClosed = closed | cur.flags;
                s.closed[idx] = newClosed;
                if (slot >= 0) {
                    int before = open[slot];
                    int after = before & ~newClosed;
                    if (after != before) {
                        open[slot] = after;
                        for (int b = 0; b < RoutingFlags.FLAG_COUNT; b++) {
                            int bit = 1 << b;
                            if ((before & bit) != 0 && (after & bit) == 0 && --openCount[b] == 0) {
                                openUnion &= ~bit;
                            }
                        }
                        if (after == 0) {
                            remaining--;
                        }
                    }
                }
            }
        }

        if (!exhaustive) {
            for (JunctionId t : targets) {
                if (!routes.containsKey(t)) {
                    unreachable.add(t);
                }
            }
        }
        return new SearchResult(graph, source, routes, settledOrder, unreachable, settledNodes, polled);
    }

    /**
     * The link-state router's rule for routes through filters: a route to an already-visited junction is still useful
     * if, for one of its flags not yet closed there, no earlier route arrived with a subset of its filters.
     */
    private static boolean hasNewInformation(QueueEntry cur, int fresh, List<?>[] seen) {
        for (int b = 0; b < RoutingFlags.FLAG_COUNT; b++) {
            if ((fresh & (1 << b)) == 0) {
                continue;
            }
            @SuppressWarnings("unchecked")
            List<List<?>> lists = (List<List<?>>) seen[b];
            if (lists == null) {
                return true;
            }
            boolean dominated = false;
            for (List<?> filter : lists) {
                if (cur.filters.containsAll(filter)) {
                    dominated = true;
                    break;
                }
            }
            if (!dominated) {
                return true;
            }
        }
        return false;
    }

    private static double heuristicAt(NetworkGraph graph, Scratch s, int node, JunctionId[] targets,
            ToDoubleBiFunction<JunctionId, JunctionId> heuristic) {
        double cached = s.h[node];
        if (!Double.isNaN(cached)) {
            return cached;
        }
        JunctionId id = JunctionId.of(node);
        double best = Double.POSITIVE_INFINITY;
        for (JunctionId t : targets) {
            double v = heuristic.applyAsDouble(id, t);
            if (v < best) {
                best = v;
            }
        }
        if (Double.isInfinite(best) || best < 0) {
            best = 0;
        }
        s.touch(node);
        s.h[node] = best;
        return best;
    }

    private static List<?> concat(List<?> a, List<?> b) {
        if (b.isEmpty()) {
            return a;
        }
        if (a.isEmpty()) {
            return b;
        }
        List<Object> list = new ArrayList<>(a.size() + b.size());
        list.addAll(a);
        list.addAll(b);
        return Collections.unmodifiableList(list);
    }
}
