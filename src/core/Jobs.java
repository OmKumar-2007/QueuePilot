package core;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static core.Jobs.JobState.BLOCKED;
import static core.Jobs.JobState.CANCELLED;
import static core.Jobs.JobState.FAILED;
import static core.Jobs.JobState.QUEUED;
import static core.Jobs.JobState.RUNNING;
import static core.Jobs.JobState.SKIPPED;
import static core.Jobs.JobState.SUCCEEDED;

/** Job, attempt and worker states and the rules for moving between them. */
public final class Jobs {

    private Jobs() {
    }

    /** Lifecycle states of a job. Allowed transitions are defined in {@link JobStateMachine}. */
    public enum JobState {
        /** Waiting for prerequisite jobs to succeed. */
        BLOCKED,
        /** Ready; may be claimed once {@code readyAt} has passed (retry delay). */
        QUEUED,
        /** An attempt is executing on a worker. */
        RUNNING,
        SUCCEEDED,
        FAILED,
        CANCELLED,
        /** Not executed because a prerequisite did not succeed. */
        SKIPPED;

        public boolean isTerminal() {
            return this == SUCCEEDED || this == FAILED || this == CANCELLED || this == SKIPPED;
        }

        /** Cancellation is only guaranteed before execution starts (SRS U4). */
        public boolean isCancellable() {
            return this == BLOCKED || this == QUEUED;
        }
    }

    /** Outcome of one execution attempt of a job on a worker. */
    public enum AttemptState {
        RUNNING,
        SUCCEEDED,
        /** The worker reported an error. */
        FAILED,
        /** Ran longer than the task definition's timeout. */
        TIMED_OUT,
        /** No heartbeat renewed the lease in time. */
        LEASE_EXPIRED,
        /** The worker was declared offline, deregistered or was killed by failure simulation. */
        ABANDONED
    }

    /** Health / availability of a registered worker. */
    public enum WorkerStatus {
        /** Healthy and accepting new work. */
        ONLINE,
        /** Healthy, finishing current attempts, accepting nothing new. */
        DRAINING,
        /** Healthy and heartbeating, but told by an administrator to hold all work. */
        PAUSED,
        /** Heartbeats stopped or the worker deregistered; must re-register. */
        OFFLINE,
        /** Switched off by an administrator. */
        DISABLED;

        public boolean acceptsWork() {
            return this == ONLINE;
        }
    }

    /** Thrown when code tries to move a job or attempt into a state the lifecycle does not allow. */
    public static class IllegalStateTransitionException extends RuntimeException {

        public IllegalStateTransitionException(String message) {
            super(message);
        }
    }

    /**
     * The single place that defines which job state changes are legal.
     * See the state diagram in docs/architecture/architecture.md §7.
     */
    public static final class JobStateMachine {

        private static final Map<JobState, Set<JobState>> ALLOWED = new EnumMap<>(JobState.class);

        static {
            ALLOWED.put(BLOCKED, EnumSet.of(QUEUED, SKIPPED, CANCELLED));
            ALLOWED.put(QUEUED, EnumSet.of(RUNNING, CANCELLED));
            // RUNNING -> QUEUED is a retry after a failed, expired or timed-out attempt.
            ALLOWED.put(RUNNING, EnumSet.of(SUCCEEDED, FAILED, QUEUED));
            for (JobState terminal : EnumSet.of(SUCCEEDED, FAILED, CANCELLED, SKIPPED)) {
                ALLOWED.put(terminal, EnumSet.noneOf(JobState.class));
            }
        }

        private JobStateMachine() {
        }

        public static boolean canTransition(JobState from, JobState to) {
            return ALLOWED.get(from).contains(to);
        }

        public static void check(JobState from, JobState to) {
            if (!canTransition(from, to)) {
                throw new IllegalStateTransitionException("Job cannot move from " + from + " to " + to);
            }
        }
    }

    /**
     * Bounded retry with exponential backoff: delay = base * 2^(retryNumber - 1), capped at max.
     *
     * @param baseDelay delay before the first retry
     * @param maxDelay  upper bound for any delay
     */
    public record RetryPolicy(Duration baseDelay, Duration maxDelay) {

        public RetryPolicy {
            if (baseDelay.isNegative() || maxDelay.compareTo(baseDelay) < 0) {
                throw new IllegalArgumentException("Require 0 <= baseDelay <= maxDelay");
            }
        }

        /**
         * Whether another attempt is allowed. {@code attemptsUsed} counts attempts already started,
         * so with {@code maxRetries = 3} a job may run at most 4 times.
         */
        public static boolean canRetry(int attemptsUsed, int maxRetries) {
            return attemptsUsed <= maxRetries;
        }

        /** Delay before retry number {@code retryNumber} (1 = first retry). */
        public Duration delayBefore(int retryNumber) {
            int exponent = Math.max(0, Math.min(retryNumber - 1, 20));
            long delayMs = baseDelay.toMillis() * (1L << exponent);
            return Duration.ofMillis(Math.min(delayMs, maxDelay.toMillis()));
        }
    }

    /** Thrown when a set of dependencies contains a cycle, e.g. A -> B -> A. */
    public static class CycleDetectedException extends RuntimeException {

        public CycleDetectedException(List<String> cycle) {
            super("Dependency cycle: " + String.join(" -> ", cycle));
        }
    }

    /**
     * Directed graph of "job depends on prerequisite" edges with cycle detection and topological
     * ordering. Node ids are plain strings (job ids or workflow step keys).
     *
     * <p>Cycle detection is a depth-first search with three colours: WHITE = not visited,
     * GREY = on the current DFS path, BLACK = finished. Reaching a GREY node again means the
     * path loops back on itself, i.e. a cycle.</p>
     */
    public static class DependencyGraph {

        private enum Colour { WHITE, GREY, BLACK }

        /** node -> its prerequisites; insertion order kept so results are deterministic. */
        private final Map<String, Set<String>> prerequisites = new LinkedHashMap<>();

        public void addNode(String node) {
            prerequisites.computeIfAbsent(node, key -> new LinkedHashSet<>());
        }

        public void addDependency(String node, String prerequisite) {
            addNode(node);
            addNode(prerequisite);
            prerequisites.get(node).add(prerequisite);
        }

        public Set<String> prerequisitesOf(String node) {
            return Collections.unmodifiableSet(prerequisites.getOrDefault(node, Set.of()));
        }

        /** Returns one cycle as a path that starts and ends with the same node, if any exists. */
        public Optional<List<String>> findCycle() {
            Map<String, Colour> colours = new HashMap<>();
            for (String node : prerequisites.keySet()) {
                colours.put(node, Colour.WHITE);
            }
            for (String node : prerequisites.keySet()) {
                if (colours.get(node) == Colour.WHITE) {
                    List<String> path = new ArrayList<>();
                    Optional<List<String>> cycle = visit(node, colours, path);
                    if (cycle.isPresent()) {
                        return cycle;
                    }
                }
            }
            return Optional.empty();
        }

        private Optional<List<String>> visit(String node, Map<String, Colour> colours, List<String> path) {
            colours.put(node, Colour.GREY);
            path.add(node);
            for (String next : prerequisites.get(node)) {
                Colour colour = colours.get(next);
                if (colour == Colour.GREY) {
                    List<String> cycle = new ArrayList<>(path.subList(path.indexOf(next), path.size()));
                    cycle.add(next);
                    return Optional.of(cycle);
                }
                if (colour == Colour.WHITE) {
                    Optional<List<String>> cycle = visit(next, colours, path);
                    if (cycle.isPresent()) {
                        return cycle;
                    }
                }
            }
            path.remove(path.size() - 1);
            colours.put(node, Colour.BLACK);
            return Optional.empty();
        }

        /**
         * Orders nodes so every prerequisite comes before the nodes that depend on it.
         *
         * @throws CycleDetectedException if the graph has a cycle
         */
        public List<String> topologicalOrder() {
            findCycle().ifPresent(cycle -> {
                throw new CycleDetectedException(cycle);
            });
            List<String> order = new ArrayList<>();
            Set<String> placed = new LinkedHashSet<>();
            for (String node : prerequisites.keySet()) {
                place(node, placed, order);
            }
            return order;
        }

        private void place(String node, Set<String> placed, List<String> order) {
            if (placed.contains(node)) {
                return;
            }
            for (String prerequisite : prerequisites.get(node)) {
                place(prerequisite, placed, order);
            }
            placed.add(node);
            order.add(node);
        }
    }
}
