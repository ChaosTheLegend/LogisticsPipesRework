package logisticspipes.routing;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import logisticspipes.proxy.SimpleServiceLocator;
import logisticspipes.ticks.RoutingTableUpdateThread;

/**
 * Benchmark counters for the link-state router ({@link ServerRouter}), shown by {@code /lp rt} and reset by
 * {@code /lp rt-clear}. Pure instrumentation: counting does not change what the router does.
 */
public final class RouterStats {

    // route table (Dijkstra) rebuilds
    static final LongAdder tableRuns = new LongAdder();
    static final LongAdder tableNanos = new LongAdder();
    static final AtomicLong tableMaxNanos = new AtomicLong();
    static final LongAdder tableNodesPolled = new LongAdder();
    /** Rebuilds that ran on the thread asking for the route (the server thread for item routing), not a worker. */
    static final LongAdder callerRuns = new LongAdder();
    static final LongAdder callerNanos = new LongAdder();

    // lookups
    static final LongAdder pairLookups = new LongAdder();
    static final LongAdder networkViewLookups = new LongAdder();

    // edits
    static final LongAdder adjacencyChecks = new LongAdder();
    static final LongAdder adjacencyChanged = new LongAdder();
    static final LongAdder adjacencyNanos = new LongAdder();
    static final LongAdder lsaFloods = new LongAdder();
    static final LongAdder lsaFloodNanos = new LongAdder();
    static final LongAdder routersFlagged = new LongAdder();

    private RouterStats() {}

    static void recordTable(long nanos, int polled) {
        tableRuns.increment();
        tableNanos.add(nanos);
        tableNodesPolled.add(polled);
        tableMaxNanos.accumulateAndGet(nanos, Math::max);
        if (!(Thread.currentThread() instanceof RoutingTableUpdateThread)) {
            callerRuns.increment();
            callerNanos.add(nanos);
        }
    }

    static void recordAdjacencyCheck(long nanos, boolean changed) {
        adjacencyChecks.increment();
        adjacencyNanos.add(nanos);
        if (changed) {
            adjacencyChanged.increment();
        }
    }

    static void recordLsaFlood(long nanos) {
        lsaFloods.increment();
        lsaFloodNanos.add(nanos);
    }

    public static void reset() {
        for (LongAdder a : Arrays.asList(
                tableRuns,
                tableNanos,
                tableNodesPolled,
                callerRuns,
                callerNanos,
                pairLookups,
                networkViewLookups,
                adjacencyChecks,
                adjacencyChanged,
                adjacencyNanos,
                lsaFloods,
                lsaFloodNanos,
                routersFlagged)) {
            a.reset();
        }
        tableMaxNanos.set(0);
    }

    private static long avgMicros(LongAdder nanos, LongAdder count) {
        long n = count.sum();
        return n == 0 ? 0 : nanos.sum() / n / 1000;
    }

    public static List<String> describe() {
        int routers = 0;
        int adjacencies = 0;
        for (IRouter r : SimpleServiceLocator.routerManager.getRouters()) {
            if (r instanceof ServerRouter) {
                routers++;
                adjacencies += ((ServerRouter) r)._adjacentRouter.size();
            }
        }
        long runs = tableRuns.sum();
        return Arrays.asList(
                "Link-state router: " + routers
                        + " routers, "
                        + adjacencies
                        + " adjacencies, queued table updates "
                        + RoutingTableUpdateThread.size(),
                "Lookups: pair " + pairLookups.sum() + ", network views " + networkViewLookups.sum(),
                "Route tables rebuilt " + runs
                        + " (on caller thread "
                        + callerRuns.sum()
                        + ", "
                        + callerNanos.sum() / 1_000_000
                        + "ms total), avg "
                        + avgMicros(tableNanos, tableRuns)
                        + "us, max "
                        + tableMaxNanos.get() / 1000
                        + "us, nodes polled/run "
                        + (runs == 0 ? 0 : tableNodesPolled.sum() / runs),
                "Edits: adjacency checks " + adjacencyChecks.sum()
                        + " (changed "
                        + adjacencyChanged.sum()
                        + ", avg "
                        + avgMicros(adjacencyNanos, adjacencyChecks)
                        + "us), LSA floods "
                        + lsaFloods.sum()
                        + " (avg "
                        + avgMicros(lsaFloodNanos, lsaFloods)
                        + "us), routers flagged "
                        + routersFlagged.sum());
    }
}
